package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SkeletonReloadCoordinator
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.controller.SseReconnectTrigger
import cn.vectory.ocdroid.ui.controller.SseSyncDecision
import cn.vectory.ocdroid.ui.controller.SseSyncState
import cn.vectory.ocdroid.ui.controller.reconcileGap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Holds the SSE gap reconciliation overlay state ([SseSyncState]) and the per-sid
 * stripe locks ([reconcileStripes]).
 *
 * **INVARIANT: all access must occur on Dispatchers.Main.immediate — this is a
 * thread-imprisonment contract, do not introduce concurrent primitives.**
 *
 * Owns:
 *  - [sseSyncState]: the CAS-copy gap-reconciliation state machine.
 *    `@Volatile` for forward-safety only (all access is Main.immediate today).
 *  - [reconcileStripes]: fixed array of 64 [Mutex] used to serialize competing
 *    per-sid reconcile triggers (digest-driven + resync-driven).
 */
internal class SseGapReconcileController(
    private val scope: CoroutineScope,
    private val effects: SharedEffectBus,
    private val slices: SliceFlows,
    private val identityStore: ConnectionIdentityStore?,
    private val skeletonReloadCoordinator: SkeletonReloadCoordinator?,
) {
    /** Stripe count — must match the companion constant in [SessionSyncCoordinator]. */
    internal companion object {
        const val STRIPES = 64
    }

    /**
     * §P1-10: the SSE gap reconciliation overlay state. Drives the explicit
     * invariant ([SseSyncState] + [reconcileGap]) on top of
     * [cn.vectory.ocdroid.ui.controller.ForegroundCatchUpController]'s 3-tier.
     *
     * `@Volatile` for forward-safety only — all reads/writes are confined to
     * the coordinator's single-threaded scope (Dispatchers.Main.immediate).
     */
    @Volatile
    private var sseSyncState: SseSyncState = SseSyncState()

    /**
     * Task 11 round-2 (oracle I5): a fixed array of 64 [Mutex] used to
     * serialize competing per-sid reconcile triggers. See kdoc on
     * [SessionSyncCoordinator.reconcileStripes] for detailed rationale.
     */
    private val reconcileStripes: Array<Mutex> = Array(STRIPES) { Mutex() }

    init {
        // §P1-10: observe the disconnect / host-reconfigure signals emitted on
        // the effects bus, so the overlay state stays in lock-step without
        // coupling the two controllers.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            effects.effects.collect { effect ->
                when (effect) {
                    is ControllerEffect.CancelSse -> {
                        val gen = currentEpoch()
                        val now = System.currentTimeMillis()
                        val dirty = listOfNotNull(slices.chat.value.currentSessionId).toSet()
                        val trigger = SseReconnectTrigger.Disconnected(now, dirty, gen)
                        sseSyncState = reconcileGap(sseSyncState, trigger).first
                    }
                    else -> {}
                }
            }
        }
    }

    // ── Public API (called from SessionSyncCoordinator) ──────────────────────

    /** Returns the per-sid stripe [Mutex]. See [SessionSyncCoordinator.stripeForImpl]. */
    fun stripeFor(sid: String): Mutex {
        val idx = ((sid.hashCode() % STRIPES) + STRIPES) % STRIPES
        return reconcileStripes[idx]
    }

    /** Diagnostic + test hook: snapshot of the current overlay state. */
    fun sseSyncStateSnapshot(): SseSyncState = sseSyncState

    /**
     * Mark [sessionId] as having an established cold-snapshot baseline.
     * Idempotent (set-add).
     */
    fun markSessionColdSnapshotted(sessionId: String) {
        if (sessionId.isBlank()) return
        if (sessionId !in sseSyncState.sessionsEverColdSnapshotted) {
            sseSyncState = sseSyncState.copy(
                sessionsEverColdSnapshotted = sseSyncState.sessionsEverColdSnapshotted + sessionId,
            )
        }
    }

    /**
     * Called on `server.connected`. Runs the pure [reconcileGap] function
     * against the current state and trigger, updates the overlay state, and
     * returns the [SseSyncDecision]s for the caller to apply as side effects.
     *
     * @param gen the current connection epoch (from [ConnectionIdentityStore]).
     * @return the list of decisions the caller should execute.
     */
    fun onServerConnected(
        currentSessionId: String?,
        gen: Long,
    ): List<SseSyncDecision> {
        val trigger = SseReconnectTrigger.ServerConnected(currentSessionId, gen)
        val (nextState, decisions) = reconcileGap(sseSyncState, trigger)
        sseSyncState = nextState
        return decisions
    }

    /** The current connection epoch, sourced from [ConnectionIdentityStore]. */
    fun currentEpoch(): Long = identityStore?.currentEpoch() ?: 0L

    /**
     * R-20 Phase 2 (G6): check if a session is already cold-snapshotted.
     */
    fun isSessionColdSnapshotted(sessionId: String): Boolean =
        sessionId in sseSyncState.sessionsEverColdSnapshotted
}
