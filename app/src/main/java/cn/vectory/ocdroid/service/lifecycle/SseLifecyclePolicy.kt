package cn.vectory.ocdroid.service.lifecycle

import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * L4 §2 / §3 — the process-level synchronous policy authority for the three
 * independent SSE lifecycle gates + the receive-only (R2) dual fence.
 *
 * **Thread safety**: all state reads/writes are guarded by a short
 * `synchronized(lock)` (NOT a coroutine Mutex — handler/REST-boundary checks
 * must be synchronous). The [snapshot] [StateFlow] is updated inside the lock.
 *
 * **Generation rules**:
 * - `foregroundGeneration` is bumped on every confirmed fg/bg edge.
 * - `lifecycleGeneration` is bumped on: confirmed foreground, confirmed
 *   background, healthy identity replaced or removed, terminal transition.
 *
 * **Recovery flag contract** (L4 → L3 forwarding seam):
 * - [markDirty] records per-sid dirty at the current recovery version.
 * - [markRecoveryNeeded] sets the global `recoveryNeeded` flag.
 * - [foregroundRecoveryFor] returns a claim when foreground + healthy + sid
 *   match is valid; [acknowledgeMessageRecoveryForwarded] clears only the sid's
 *   dirty entry (version-checked to not erase newer events).
 * - L4 does NOT clear `recoveryNeeded`; that is reserved for L5's
 *   [completeGlobalRecovery].
 */
@Singleton
class SseLifecyclePolicy @Inject constructor() {

    // ── Exported types ─────────────────────────────────────────────────────

    enum class SseLifecycleMode {
        FOREGROUND,
        BACKGROUND_RECEIVE_ONLY,
        NO_SOURCE_TERMINAL,
    }

    data class SseLifecycleSnapshot(
        val mode: SseLifecycleMode,
        val appInForeground: Boolean,
        val lifecycleGeneration: Long,
        val foregroundGeneration: Long,
        val healthyIdentity: ConnectionIdentity?,
    )

    data class SseFrameFence(
        val lifecycleGeneration: Long,
        val identity: ConnectionIdentity,
    )

    data class SseRestFence(
        val lifecycleGeneration: Long,
        val identity: ConnectionIdentity,
    )

    data class BackgroundDeadlineTicket(
        val foregroundGeneration: Long,
        val identity: ConnectionIdentity,
        val deadlineElapsedMs: Long,
    )

    enum class RecoveryCause {
        SERVER_CONNECTED,
        RESYNC,
        ARCHIVE_RESTORED,
        PERMISSION_EVENT,
        MESSAGE_CREATED,
        DELTA_OVERFLOW,
        TRANSPORT_LOST,
    }

    data class ForegroundRecoveryClaim(
        val sessionId: String,
        val version: Long,
    )

    // ── Internal state ─────────────────────────────────────────────────────

    private val lock = Any()

    private var _mode: SseLifecycleMode = SseLifecycleMode.FOREGROUND
    private var _appInForeground: Boolean = true
    private var _lifecycleGeneration: Long = 0L
    private var _foregroundGeneration: Long = 0L
    private var _healthyIdentity: ConnectionIdentity? = null

    private val _snapshot = MutableStateFlow(
        SseLifecycleSnapshot(
            mode = SseLifecycleMode.FOREGROUND,
            appInForeground = true,
            lifecycleGeneration = 0L,
            foregroundGeneration = 0L,
            healthyIdentity = null,
        ),
    )

    /** Recovery state fields. */
    private var recoveryVersion: Long = 0L
    private var recoveryNeeded: Boolean = false
    private val dirtyVersions: MutableMap<String, Long> = LinkedHashMap()
    private val forwardedVersionBySid: MutableMap<String, Long> = LinkedHashMap()

    // ── Public snapshot ────────────────────────────────────────────────────

    val snapshot: StateFlow<SseLifecycleSnapshot> = _snapshot.asStateFlow()

    // ── Foreground/background transitions ──────────────────────────────────

    /**
     * Called when the app transitions to foreground with the current (possibly
     * null) healthy identity. Bumps both generations.
     */
    fun onForeground(identity: ConnectionIdentity?) {
        synchronized(lock) {
            _appInForeground = true
            _foregroundGeneration++
            _lifecycleGeneration++
            _healthyIdentity = identity
            _mode = SseLifecycleMode.FOREGROUND
            publishSnapshotLocked()
        }
    }

    /**
     * Called when the app transitions to background (receive-only grace).
     * Bumps both generations.
     */
    fun onBackgroundReceiveOnly(identity: ConnectionIdentity?) {
        synchronized(lock) {
            _appInForeground = false
            _foregroundGeneration++
            _lifecycleGeneration++
            // healthyIdentity stays as-is (the identity is still valid,
            // just the app went background)
            _mode = SseLifecycleMode.BACKGROUND_RECEIVE_ONLY
            publishSnapshotLocked()
        }
    }

    /**
     * Called by the timer when background grace expires. Validates the ticket's
     * foregroundGeneration + identity match the current state. Returns true if
     * the terminal transition was accepted (entered NO_SOURCE_TERMINAL).
     */
    fun tryEnterNoSourceTerminal(ticket: BackgroundDeadlineTicket): Boolean {
        synchronized(lock) {
            if (_foregroundGeneration != ticket.foregroundGeneration) return false
            if (_healthyIdentity != ticket.identity) return false
            if (_mode != SseLifecycleMode.BACKGROUND_RECEIVE_ONLY) return false
            _mode = SseLifecycleMode.NO_SOURCE_TERMINAL
            _lifecycleGeneration++
            publishSnapshotLocked()
            return true
        }
    }

    /**
     * Called when the healthy identity changes (new identity after health
     * check, or null when identity is lost). Bumps [lifecycleGeneration].
     */
    fun onHealthyIdentityChanged(identity: ConnectionIdentity?) {
        synchronized(lock) {
            _healthyIdentity = identity
            _lifecycleGeneration++
            publishSnapshotLocked()
        }
    }

    // ── Gate predicates ────────────────────────────────────────────────────

    /**
     * §3.1: Main SSE connect allowed iff foreground + healthy identity available.
     * Grace: does NOT affect already-open sockets (they are retained in
     * BACKGROUND_RECEIVE_ONLY). The gate governs NEW connect / reconnect only.
     */
    fun mainSseConnectAllowed(identity: ConnectionIdentity): Boolean {
        synchronized(lock) {
            if (!_appInForeground) return false
            if (_healthyIdentity == null) return false
            return _healthyIdentity == identity
        }
    }

    /**
     * §3.2: Token stream allowed iff foreground + visibleChatSessionId matches sid.
     */
    fun tokenStreamAllowed(sessionId: String, visibleChatSessionId: String?): Boolean {
        synchronized(lock) {
            if (!_appInForeground) return false
            return sessionId == visibleChatSessionId
        }
    }

    // ── Frame / REST fence API (R2 dual gate) ──────────────────────────────

    /**
     * §5.1: Stamp a frame fence at the current lifecycle generation.
     * Returns null (refused) when identity is not the current healthy identity
     * or mode is terminal.
     */
    fun stampFrame(identity: ConnectionIdentity): SseFrameFence? {
        synchronized(lock) {
            if (_mode == SseLifecycleMode.NO_SOURCE_TERMINAL) return null
            if (_healthyIdentity != identity) return null
            return SseFrameFence(
                lifecycleGeneration = _lifecycleGeneration,
                identity = identity,
            )
        }
    }

    /**
     * §5.1: Check if a previously stamped fence is still current.
     */
    fun frameStillCurrent(fence: SseFrameFence): Boolean {
        synchronized(lock) {
            if (_lifecycleGeneration != fence.lifecycleGeneration) return false
            if (_healthyIdentity != fence.identity) return false
            return true
        }
    }

    /**
     * §5.2: Convert a frame fence to a REST fence.
     * Returns null when mode is NOT FOREGROUND (REST not allowed in receive-only).
     */
    fun restFenceFor(frame: SseFrameFence): SseRestFence? {
        synchronized(lock) {
            if (_mode != SseLifecycleMode.FOREGROUND) return null
            return SseRestFence(
                lifecycleGeneration = frame.lifecycleGeneration,
                identity = frame.identity,
            )
        }
    }

    /**
     * §5.3: Execute-SSE-REST effect boundary predicate (R2).
     * Three-way check: mode=FOREGROUND + lifecycleGeneration matches + identity matches.
     * This is the "执行边界谓词" — REST is only allowed when all three are current.
     */
    fun restEffectAllowed(fence: SseRestFence): Boolean {
        synchronized(lock) {
            if (_mode != SseLifecycleMode.FOREGROUND) return false
            if (_lifecycleGeneration != fence.lifecycleGeneration) return false
            if (_healthyIdentity != fence.identity) return false
            return true
        }
    }

    // ── Recovery/Dirty API (L4→L3 forwarding seam) ────────────────────────

    /**
     * Marks a session as dirty for the given cause.
     */
    fun markDirty(sessionId: String, cause: RecoveryCause, lifecycleGeneration: Long) {
        synchronized(lock) {
            recoveryVersion++
            dirtyVersions[sessionId] = recoveryVersion
            // L4 does NOT clear recoveryNeeded here
        }
    }

    /**
     * Marks global recovery needed.
     *
     * L4 §7 (lane-2): called by
     * [cn.vectory.ocdroid.service.streaming.ServiceSseConnectionOwner.markRecoveryNeededAndExit]
     * when the background SSE reconnect gate refuses (the socket dropped
     * while the app was in background — a reconnect is NOT attempted in
     * background per the user decision to keep background receive-only).
     * The flag survives until the next foreground return, where
     * [consumeRecoveryNeeded] reads + clears it so the coordinator
     * re-establishes the SSE transport.
     */
    fun markRecoveryNeeded(cause: RecoveryCause, lifecycleGeneration: Long) {
        synchronized(lock) {
            recoveryVersion++
            recoveryNeeded = true
        }
    }

    /**
     * L4 §7 (lane-2): atomically reads + clears the global recovery-needed
     * flag. Called by
     * [cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator.handleBackgroundGrace]
     * on foreground return to decide whether the SSE transport must be
     * re-established (it was lost during background grace). Consumed once
     * per foreground return so a subsequent foreground with a live
     * (re-established) socket does NOT spuriously reconnect.
     *
     * @return the prior value of the recovery-needed flag.
     */
    fun consumeRecoveryNeeded(): Boolean = synchronized(lock) {
        val wasNeeded = recoveryNeeded
        recoveryNeeded = false
        wasNeeded
    }

    /**
     * Returns a [ForegroundRecoveryClaim] when foreground + healthy identity +
     * sid match and the sid is dirty. Returns null if any condition fails.
     */
    fun foregroundRecoveryFor(sessionId: String): ForegroundRecoveryClaim? {
        synchronized(lock) {
            if (!_appInForeground) return null
            if (_healthyIdentity == null) return null
            val dirtyVersion = dirtyVersions[sessionId] ?: return null
            val forwardedVersion = forwardedVersionBySid[sessionId] ?: -1L
            if (dirtyVersion <= forwardedVersion) return null
            return ForegroundRecoveryClaim(
                sessionId = sessionId,
                version = dirtyVersion,
            )
        }
    }

    /**
     * Called after the L3 FORCE_RECONCILE has been submitted for a claim.
     * Clears the sid's dirty entry only if its version hasn't advanced (a newer
     * event arrived after the claim was issued).
     */
    fun acknowledgeMessageRecoveryForwarded(claim: ForegroundRecoveryClaim) {
        synchronized(lock) {
            val currentVersion = dirtyVersions[claim.sessionId]
            if (currentVersion != null && currentVersion <= claim.version) {
                dirtyVersions.remove(claim.sessionId)
            }
            // Always record the forwarded version so future claims compare
            // against the correct baseline.
            val existing = forwardedVersionBySid[claim.sessionId] ?: -1L
            if (claim.version > existing) {
                forwardedVersionBySid[claim.sessionId] = claim.version
            }
        }
    }

    /**
     * Reserved for L5: completes global recovery at [version].
     */
    fun completeGlobalRecovery(version: Long) {
        // L5 implementation — L4 no-ops
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    private fun publishSnapshotLocked() {
        _snapshot.value = SseLifecycleSnapshot(
            mode = _mode,
            appInForeground = _appInForeground,
            lifecycleGeneration = _lifecycleGeneration,
            foregroundGeneration = _foregroundGeneration,
            healthyIdentity = _healthyIdentity,
        )
    }
}
