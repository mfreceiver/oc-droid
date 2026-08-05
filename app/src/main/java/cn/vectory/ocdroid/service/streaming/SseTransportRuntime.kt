package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

// ── Transport state hierarchy ──────────────────────────────────────────────

sealed interface SseTransportState {
    data object Stopped : SseTransportState
    data class Connecting(val attempt: TransportAttemptToken) : SseTransportState
    data class Live(val attempt: TransportAttemptToken) : SseTransportState
    data class Retrying(val attempt: TransportAttemptToken) : SseTransportState
    data class Dropped(val ticket: TransportDropTicket) : SseTransportState
}

data class TransportAttemptToken(
    val attemptId: Long,
    val identity: ConnectionIdentity,
    val recoveryTicket: TransportDropTicket?,
)

data class TransportDropTicket(
    val dropId: Long,
    val identity: ConnectionIdentity,
    val reason: TransportDropReason,
)

enum class TransportDropReason {
    BACKGROUND_RECONNECT_REFUSED,
    RETRY_EXHAUSTED,
    SERVICE_DESTROYED,
    OWNER_MISSING,
}

// ── Runtime store ──────────────────────────────────────────────────────────

/**
 * Process-level transport truth authority (I1).
 *
 * Owns the current [SseTransportState] transition machine. Every public
 * mutation is **linearizable** — the read–check–ID allocation–state commit
 * runs atomically under `lock` so concurrent callers see a total order of
 * state transitions. A stale or foreign token is silently rejected.
 *
 * ## Linearization strategy
 *
 * A single `synchronized(lock)` guard covers every mutation method:
 * `beginAttempt`, `markRetrying`, `markLive`, `publishDropped`,
 * `acknowledgeRecovery`, `markStopped`. Inside the critical section:
 *
 * 1. read the current [state] value;
 * 2. validate preconditions (identity match, attempt-liveness, existing
 *    recovery ticket);
 * 3. allocate monotonic IDs via `AtomicLong` (the increment itself is atomic,
 *    but happens under `lock` for conceptual ordering with the state write);
 * 4. write the new state to `_state`;
 *
 * `sseConnectedFlow` is a read-only projection of `_state`; it has no separate
 * mutable state or transition machine. Its `value` therefore always evaluates
 * directly from the authoritative runtime state.
 *
 * ## Canonical attempt rule
 *
 * Every transition that carries a [TransportAttemptToken] validates the
 * caller-provided token against the **canonical** attempt stored in the
 * current state (matching attemptId + identity). The new state always uses
 * the canonical attempt, NOT the caller-provided token. This ensures that
 * after `acknowledgeRecovery()` clears the recovery ticket from the internal
 * attempt, a later `markRetrying` / `markLive` / `publishDropped` using the
 * same old handle does NOT resurrect the cleared ticket.
 *
 * @see TransportAttemptToken
 * @see TransportDropTicket
 */
@Singleton
class SseTransportRuntimeStore @Inject constructor() {

    /** Linearization lock — every mutation method runs inside this monitor. */
    private val lock = Any()

    private val _state = MutableStateFlow<SseTransportState>(SseTransportState.Stopped)
    val state: StateFlow<SseTransportState> = _state.asStateFlow()

    /**
     * Strict projection of [state]. This adapter intentionally derives `value`
     * directly from `_state`, avoiding a second writable MutableStateFlow that
     * could be observed out of sync with the runtime state.
     */
    @OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
    val sseConnectedFlow: StateFlow<Boolean> = object : StateFlow<Boolean> {
        override val replayCache: List<Boolean>
            get() = listOf(value)

        override val value: Boolean
            get() = _state.value is SseTransportState.Live

        override suspend fun collect(collector: FlowCollector<Boolean>): Nothing {
            _state
                .map { it is SseTransportState.Live }
                .distinctUntilChanged()
                .collect(collector)
            error("authoritative StateFlow collection unexpectedly completed")
        }
    }

    private val attemptCounter = AtomicLong(0)
    private val dropCounter = AtomicLong(0)

    // ── helpers ────────────────────────────────────────────────────────

    /**
     * Returns the canonical [TransportAttemptToken] when the current state
     * holds one (Connecting / Live / Retrying), or null for Dropped / Stopped.
     */
    private val SseTransportState.canonicalAttempt: TransportAttemptToken?
        get() = when (this) {
            is SseTransportState.Connecting -> attempt
            is SseTransportState.Live -> attempt
            is SseTransportState.Retrying -> attempt
            is SseTransportState.Stopped, is SseTransportState.Dropped -> null
        }

    /**
     * Returns true when the current state holds [a] as its active attempt
     * (Connecting / Live / Retrying). Dropped and Stopped never match.
     */
    private fun SseTransportState.matchesAttempt(a: TransportAttemptToken): Boolean = when (this) {
        is SseTransportState.Stopped -> false
        is SseTransportState.Connecting ->
            a.attemptId == attempt.attemptId && a.identity == attempt.identity
        is SseTransportState.Live ->
            a.attemptId == attempt.attemptId && a.identity == attempt.identity
        is SseTransportState.Retrying ->
            a.attemptId == attempt.attemptId && a.identity == attempt.identity
        is SseTransportState.Dropped -> false
    }

    /**
     * Atomically commits [newState] and the derived `sseConnected` value.
     * MUST be called inside `synchronized(lock)`.
     */
    private fun commit(newState: SseTransportState) {
        _state.value = newState
    }

    // ── public API ─────────────────────────────────────────────────────

    /**
     * Allocates a monotonic [TransportAttemptToken] and transitions to
     * [SseTransportState.Connecting].
     *
     * Returns null when another identity owns a non-Stopped runtime state,
     * or when the same identity already has an active attempt
     * (Connecting/Live/Retrying). When the current state is [Dropped] with a
     * matching identity, the existing ticket is captured as [recoveryTicket].
     *
     * Linearizable: runs atomically under [lock].
     */
    fun beginAttempt(identity: ConnectionIdentity): TransportAttemptToken? = synchronized(lock) {
        val current = _state.value
        when (current) {
            is SseTransportState.Stopped -> {
                val token = TransportAttemptToken(
                    attemptId = attemptCounter.incrementAndGet(),
                    identity = identity,
                    recoveryTicket = null,
                )
                commit(SseTransportState.Connecting(token))
                token
            }
            is SseTransportState.Dropped -> {
                if (current.ticket.identity != identity) null
                else {
                    val token = TransportAttemptToken(
                        attemptId = attemptCounter.incrementAndGet(),
                        identity = identity,
                        recoveryTicket = current.ticket,
                    )
                    commit(SseTransportState.Connecting(token))
                    token
                }
            }
            is SseTransportState.Connecting,
            is SseTransportState.Live,
            is SseTransportState.Retrying -> {
                // Non-Stopped state exists — reject (same identity already
                // active, or different identity owns the runtime).
                null
            }
        }
    }

    /**
     * Transitions [Live] → [Retrying] for the given attempt. Uses the
     * canonical attempt from the current state, NOT [attempt]'s metadata,
     * ensuring that any recovery ticket previously cleared by
     * [acknowledgeRecovery] stays cleared.
     *
     * Returns false for stale/foreign attempts or when current state is not
     * Live. Linearizable.
     */
    fun markRetrying(attempt: TransportAttemptToken): Boolean = synchronized(lock) {
        val current = _state.value
        if (current !is SseTransportState.Live) return@synchronized false
        val ca = current.attempt
        if (ca.attemptId != attempt.attemptId || ca.identity != attempt.identity) {
            return@synchronized false
        }
        // Use canonical attempt — any recovery ticket nullity is preserved.
        commit(SseTransportState.Retrying(ca))
        true
    }

    /**
     * Transitions [Connecting] or [Retrying] → [Live] for the given attempt.
     * Uses the canonical attempt from the current state.
     * Does NOT clear the recovery ticket (that requires [acknowledgeRecovery]).
     *
     * Returns false for stale/foreign attempts. Linearizable.
     */
    fun markLive(attempt: TransportAttemptToken): Boolean = synchronized(lock) {
        val current = _state.value
        val ca = current.canonicalAttempt ?: return@synchronized false
        if (ca.attemptId != attempt.attemptId || ca.identity != attempt.identity) {
            return@synchronized false
        }
        // Use canonical attempt — recovery ticket (if any) is preserved.
        commit(SseTransportState.Live(ca))
        true
    }

    /**
     * Publishes [Dropped] for the current attempt.
     *
     * The recovery decision is based on the **canonical** attempt's recovery
     * ticket, not the caller-provided [attempt]:
     * - If the canonical attempt carries a non-null `recoveryTicket`, the
     *   exact same ticket (drop ID + identity + reason) is restored (I4).
     * - Otherwise a new monotonic drop ID is allocated.
     *
     * Returns null for stale/foreign attempts. The caller
     * ([UnexpectedTransportDropHandler]) must have released ownership before
     * calling this. Linearizable.
     */
    fun publishDropped(
        attempt: TransportAttemptToken,
        reason: TransportDropReason,
    ): TransportDropTicket? = synchronized(lock) {
        val current = _state.value
        val ca = current.canonicalAttempt ?: return@synchronized null
        if (ca.attemptId != attempt.attemptId || ca.identity != attempt.identity) {
            return@synchronized null
        }

        val ticket = if (ca.recoveryTicket != null) {
            // Restore exact existing ticket (I4).
            ca.recoveryTicket
        } else {
            TransportDropTicket(
                dropId = dropCounter.incrementAndGet(),
                identity = ca.identity,
                reason = reason,
            )
        }
        commit(SseTransportState.Dropped(ticket))
        ticket
    }

    /**
     * The only operation that clears a recovery ticket (I5). Succeeds only
     * when the current state is [Live] for the given attempt AND the canonical
     * attempt carries a non-null [recoveryTicket]. On success the internal
     * attempt is replaced with one whose recoveryTicket is null.
     *
     * This must be called only after runtime Live + coordinator commit +
     * ownership Ready complete. Linearizable.
     */
    fun acknowledgeRecovery(attempt: TransportAttemptToken): Boolean = synchronized(lock) {
        val current = _state.value
        if (current !is SseTransportState.Live) return@synchronized false
        val ca = current.attempt
        if (ca.attemptId != attempt.attemptId || ca.identity != attempt.identity) {
            return@synchronized false
        }
        if (ca.recoveryTicket == null) return@synchronized false
        val cleared = ca.copy(recoveryTicket = null)
        commit(SseTransportState.Live(cleared))
        true
    }

    /**
     * Intentional teardown (I6). Rejected for stale/foreign attempts.
     * Linearizable.
     */
    fun markStopped(attempt: TransportAttemptToken): Boolean = synchronized(lock) {
        val current = _state.value
        if (!current.matchesAttempt(attempt)) return@synchronized false
        commit(SseTransportState.Stopped)
        true
    }

    /**
     * Recovery-rejected / attempt-rollback operation (I4 + I6).
     *
     * Used by the owner when a transport attempt is terminated by a
     * NON-intentional path that is still "the attempt failed, not a clean
     * teardown": the transport-readiness timeout, a background reconnect
     * refusal BEFORE the transport proved Live, or a same-identity
     * supersession. Unlike [markStopped] (which always publishes `Stopped`),
     * this preserves the drop demand when the attempt was a RECOVERY attempt:
     *
     *  - If the canonical attempt carries a non-null [recoveryTicket]
     *    (a `beginAttempt` that captured a prior `Dropped` ticket), the SAME
     *    ticket is restored → `Dropped(ticket)` (I4: a rejected recovery
     *    attempt restores the same drop ticket; it does not generate a new
     *    drop ID or clear demand). The supervisor's foreground retry watchdog
     *    then reuses that exact ticket on the next attempt.
     *  - If the canonical attempt has NO recovery ticket (a fresh attempt that
     *    never proved Live and was never a recovery), the rollback is an
     *    intentional teardown → `Stopped` (I6: never publish a spurious
     *    Dropped for an attempt that carried no recovery demand).
     *
     * Returns false for stale/foreign attempts (the current state is not the
     * given attempt's Connecting/Live/Retrying), leaving state unchanged.
     * Linearizable: runs atomically under [lock].
     */
    fun rollbackAttempt(attempt: TransportAttemptToken): Boolean = synchronized(lock) {
        val current = _state.value
        val ca = current.canonicalAttempt ?: return@synchronized false
        if (ca.attemptId != attempt.attemptId || ca.identity != attempt.identity) {
            return@synchronized false
        }
        val ticket = ca.recoveryTicket
        if (ticket != null) {
            // Recovery attempt rejected: restore the EXACT same drop ticket
            // (same dropId + identity + reason) so demand survives (I4).
            commit(SseTransportState.Dropped(ticket))
        } else {
            // Fresh attempt with no recovery demand: intentional teardown
            // → Stopped (I6, never a spurious Dropped).
            commit(SseTransportState.Stopped)
        }
        true
    }

    // ── non-mutating accessors ──────────────────────────────────────────
    //
    // These read _state.value without the lock. A stale-weather read is
    // harmless (the caller gets a point-in-time snapshot); the StateFlow
    // volatile field provides atomic visibility.

    fun currentAttempt(identity: ConnectionIdentity): TransportAttemptToken? {
        val current = _state.value
        return when (current) {
            is SseTransportState.Stopped -> null
            is SseTransportState.Connecting ->
                if (current.attempt.identity == identity) current.attempt else null
            is SseTransportState.Live ->
                if (current.attempt.identity == identity) current.attempt else null
            is SseTransportState.Retrying ->
                if (current.attempt.identity == identity) current.attempt else null
            is SseTransportState.Dropped -> null
        }
    }

    fun currentDropTicket(identity: ConnectionIdentity): TransportDropTicket? {
        val current = _state.value
        return if (current is SseTransportState.Dropped && current.ticket.identity == identity) {
            current.ticket
        } else null
    }
}
