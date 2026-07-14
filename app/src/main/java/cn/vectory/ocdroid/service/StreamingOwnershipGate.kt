package cn.vectory.ocdroid.service

import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import javax.inject.Inject
import javax.inject.Singleton

/**
 * D4-B B1 — the result the [StreamingServiceLauncher] returns to
 * [cn.vectory.ui.controller.ConnectionCoordinator]. The launcher completes with
 * [Ready] ONLY after the Service has marked ownership Ready (Stage 2 —
 * transport-ready), so CC publishes [cn.vectory.ui.ConnectionPhase.Connected]
 * exclusively on a verified transport. A [Starting] ownership (Stage 1) does
 * NOT satisfy the launcher's readiness waiter.
 */
sealed interface OwnershipStartResult {
    /**
     * D4-B B1: ownership reached Stage 2 (Ready) — the SSE transport
     * delivered a valid current-identity frame AND the coordinator committed
     * the Bootstrap handoff. The launcher returns this; CC writes Connected.
     */
    data class Ready(val identity: ConnectionIdentity) : OwnershipStartResult

    data class Refused(val reason: OwnershipRefusal) : OwnershipStartResult
}

sealed interface OwnershipRefusal {
    data object Background : OwnershipRefusal
    data object AckTimeout : OwnershipRefusal
    data object StaleIdentity : OwnershipRefusal
    data class AlreadyOwned(val identity: ConnectionIdentity) : OwnershipRefusal
    data class PlatformRejected(val error: Throwable) : OwnershipRefusal
    data object ServiceStopped : OwnershipRefusal
    /** D4-B B1: bootstrap exhausted / transport rejected/timed out. */
    data object BootstrapFailed : OwnershipRefusal
}

/**
 * D5-2 (#4) — the prepared-attempt model that closes the orphan-owner hole.
 *
 * The launcher calls [StreamingOwnershipGate.prepareAttempt] BEFORE issuing
 * `startForegroundService`. The gate allocates a monotonic [attemptId] that
 * travels in the Service Intent ([OwnershipRequestParser.EXTRA_ATTEMPT_ID])
 * and is validated when the Service's `onStartCommand` calls
 * [StreamingOwnershipGate.registerStarting]. If the launcher's 5s
 * Starting-acceptance window elapses, [StreamingOwnershipGate.expireAttempt]
 * invalidates the attempt ID — any LATE Service invocation discovers its
 * attempt ID is expired and ABORTS (stopForeground + stopSelf + cancel SSE),
 * so it can NEVER register a Starting owner that would later promote to an
 * orphan Ready owner.
 *
 * The split-deferred model:
 *  - [starting] — completes with [StartingAck.Accepted] when the Service
 *    claims Stage 1 ownership; with [StartingAck.Refused] on early failure
 *    (AlreadyOwned / StaleIdentity / PlatformRejected). The launcher awaits
 *    this with a 5s wall-clock timeout.
 *  - [terminal] — completes with the final [OwnershipStartResult] (Ready on
 *    Stage 2 / Refused on rollback). The launcher awaits this WITHOUT a
 *    second wall-clock timeout — the 30s transport timeout runs ONLY inside
 *    [ServiceSseConnectionOwner.setupConnectLocked].
 */
data class PreparedOwnershipAttempt(
    val attemptId: Long,
    val launchRequired: Boolean,
    val starting: Deferred<StartingAck>,
    val terminal: Deferred<OwnershipStartResult>,
)

/**
 * D5-2 (#4) — the Stage 1 acceptance signal the launcher awaits within its 5s
 * window. Distinct from [OwnershipStartResult] because the launcher must
 * distinguish "Starting accepted, await terminal" (5s elapsed, proceed) from
 * "terminal settled" (Ready/Refused).
 */
sealed interface StartingAck {
    data class Accepted(val identity: ConnectionIdentity) : StartingAck
    data class Refused(val reason: OwnershipRefusal) : StartingAck
}

/**
 * D5-2 (#4) — the outcome of [StreamingOwnershipGate.registerStarting] for a
 * (identity, attemptId) pair. The Service consults this to decide whether to
 * proceed with the bootstrap or ABORT.
 *
 *  - [Accepted] — the attempt ID matches the live prepared attempt; the
 *    Starting owner was recorded and the launcher's [PreparedOwnershipAttempt.starting]
 *    deferred was completed with [StartingAck.Accepted].
 *  - [Expired] — the attempt ID does NOT match any live prepared attempt
 *    (launcher timed out and called [StreamingOwnershipGate.expireAttempt]).
 *    The late Service invocation MUST abort (stopForeground + stopSelf +
 *    cancel any SSE) so it does not become an orphan owner.
 *  - [Conflict] — a different-identity owner already holds the gate.
 */
sealed interface RegisterStartingOutcome {
    data object Accepted : RegisterStartingOutcome
    data object Expired : RegisterStartingOutcome
    data class Conflict(val ownerIdentity: ConnectionIdentity) : RegisterStartingOutcome
}

/**
 * D4-B B1 — the two-stage ownership state machine inside [StreamingOwnershipGate].
 *
 *  - [Starting] — the Service has verified identity + claimed the bootstrap/
 *    recovery ownership, but the SSE transport has NOT yet proven readiness.
 *    The launcher's [PreparedOwnershipAttempt.starting] deferred IS completed
 *    (with [StartingAck.Accepted]) — the launcher now awaits [OwnershipState.terminal]
 *    for the Stage 2 promotion. Reconfigure teardown observes + releases a
 *    Starting owner (no unowned gap), but CC does NOT publish Connected.
 *  - [Ready] — the SSE transport delivered a valid current-identity frame AND
 *    the coordinator committed the Bootstrap handoff. The launcher's
 *    [OwnershipState.terminal] deferred completes with [OwnershipStartResult.Ready].
 *
 * D5-2 (#4): both states carry the [terminal] deferred they share with the
 * launcher that prepared the attempt. Every release / promotion path settles
 * this deferred BEFORE clearing state, so the launcher never hangs.
 */
sealed interface OwnershipState {
    val identity: ConnectionIdentity
    val disconnectAndJoin: suspend (Boolean) -> Unit
    val terminal: CompletableDeferred<OwnershipStartResult>

    data class Starting(
        override val identity: ConnectionIdentity,
        override val disconnectAndJoin: suspend (Boolean) -> Unit,
        /**
         * D4-B B1: invoked by [StreamingOwnershipGate.failStarting] when a
         * Starting owner is rolled back (bootstrap exhaustion / transport
         * timeout / stale identity / explicit cancellation). The Service
         * supplies this lambda to perform shell-level cleanup that the gate
         * cannot reach (stopForeground / stopSelf / cancel SSE / write
         * shared connection state Disconnected).
         */
        val abortStartup: () -> Unit,
        override val terminal: CompletableDeferred<OwnershipStartResult>,
    ) : OwnershipState

    data class Ready(
        override val identity: ConnectionIdentity,
        override val disconnectAndJoin: suspend (Boolean) -> Unit,
        override val terminal: CompletableDeferred<OwnershipStartResult>,
    ) : OwnershipState
}

/**
 * CP9 §A6 + D3 (gate #3) → **D4-B B1 (two-stage Starting→Ready ownership)** +
 * **D4-B (runBlocking removal / synchronized refactor)** → **D5-2 (#4)
 * prepared-attempt + split-deferred + attempt-ID abort (orphan-owner closure)**.
 *
 * The single chokepoint that records which [ConnectionIdentity] the live
 * [SessionStreamingService] owns. The launcher ([StreamingServiceLauncher])
 * awaits ownership via [prepareAttempt]; the Service registers ownership in
 * two stages:
 *  1. [registerStarting] — Stage 1: claims the bootstrap/recovery ownership
 *     (closes the unowned window) but does NOT complete the launcher's
 *     terminal deferred. The launcher's [PreparedOwnershipAttempt.starting]
 *     deferred completes with [StartingAck.Accepted].
 *  2. [markReady] — Stage 2: the SSE transport proved readiness + the
 *     coordinator committed; completes the launcher's terminal deferred with
 *     [OwnershipStartResult.Ready].
 *
 * **D5-2 (#4) — orphan-owner closure**: the launcher's 5s Starting-acceptance
 * window is enforced by [expireAttempt]. When the launcher gives up, it
 * expires the attempt ID at the gate; a LATE Service `registerStarting` for
 * that expired ID returns [RegisterStartingOutcome.Expired] so the Service
 * runs its abort path instead of recording an owner that would later promote
 * to an orphan Ready owner with a live SSE while CC is terminal Disconnected.
 *
 * **Mandatory rollback** — [failStarting] is the full B1 rollback entry:
 * completes the launcher's terminal deferred with refusal, releases the
 * Starting owner, and returns the extracted [OwnershipState.Starting] so the
 * caller can invoke its [OwnershipState.Starting.abortStartup] +
 * [OwnershipState.disconnectAndJoin] outside the lock.
 *
 * **Thread safety (D4-B)**: all mutable state is guarded by a plain
 * `synchronized(lock)` section — NONE of the in-lock mutations suspend.
 * [disconnectAndRelease] / [failStarting] extract the owner under the lock
 * and invoke the suspend callback OUTSIDE the lock. [releaseNow] is a
 * non-suspend entry for [SessionStreamingService.onDestroy] (no main-thread
 * blocking).
 */
@Singleton
class StreamingOwnershipGate @Inject constructor() {
    private val lock = Any()
    private var owner: OwnershipState? = null
    private val waiters =
        mutableMapOf<ConnectionIdentity, MutableSet<CompletableDeferred<OwnershipStartResult>>>()

    /**
     * D5-2 (#4) — monotonic attempt ID counter. Every [prepareAttempt] that
     * requires a launch allocates a fresh ID; it travels in the Service Intent
     * ([OwnershipRequestParser.EXTRA_ATTEMPT_ID]) and is validated by
     * [registerStarting].
     */
    private var attemptIdCounter: Long = 0L

    /**
     * D5-2 (#4) — the live prepared attempt awaiting Stage 1 acceptance. At
     * most one is alive at a time (the launcher is the only preparer, and a
     * new [prepareAttempt] for the same identity short-circuits with the
     * existing [OwnershipState.terminal]). Cleared atomically either by
     * [registerStarting] (on acceptance) or by [expireAttempt] (on launcher
     * timeout). Null when no launcher attempt is in flight.
     */
    private var pendingAttempt: PreparedAttemptState? = null

    /**
     * D5-2 (#4) — internal mutable state for a prepared attempt. Exposed to
     * the launcher as an immutable [PreparedOwnershipAttempt] view.
     */
    private data class PreparedAttemptState(
        val attemptId: Long,
        val identity: ConnectionIdentity,
        val starting: CompletableDeferred<StartingAck>,
        val terminal: CompletableDeferred<OwnershipStartResult>,
    ) {
        fun toPublic(launchRequired: Boolean): PreparedOwnershipAttempt =
            PreparedOwnershipAttempt(
                attemptId = attemptId,
                launchRequired = launchRequired,
                starting = starting,
                terminal = terminal,
            )
    }

    /**
     * Returns the Ready owner's identity, or null when no Ready ownership
     * is held (Starting ownership does NOT count — the launcher must await
     * [markReady], not short-circuit on Starting).
     */
    fun readyIdentity(): ConnectionIdentity? = synchronized(lock) {
        (owner as? OwnershipState.Ready)?.identity
    }

    /**
     * D5-2 (#4) — REPLACES the prior `prepare(identity)` as the launcher's
     * entry. Allocates a [PreparedOwnershipAttempt] for [identity] that the
     * launcher uses to drive the split-deferred Starting/terminal flow.
     *
     * Four cases (oracle D5 spec):
     *  1. **Ready same identity** → [PreparedOwnershipAttempt.starting]
     *     pre-completed with [StartingAck.Accepted], [PreparedOwnershipAttempt.terminal]
     *     pre-completed with [OwnershipStartResult.Ready], [PreparedOwnershipAttempt.launchRequired]
     *     = false. The launcher short-circuits with Ready.
     *  2. **Starting same identity** → starting pre-completed with Accepted,
     *     terminal = the existing owner's terminal deferred (join the in-
     *     flight Stage 2 wait), launchRequired = false.
     *  3. **No owner** → allocate a fresh [attemptId], create both deferreds
     *     (neither completed), store [pendingAttempt], launchRequired = true.
     *  4. **Different owner** → both deferreds pre-completed with Refused(
     *     AlreadyOwned), launchRequired = false.
     */
    fun prepareAttempt(identity: ConnectionIdentity): PreparedOwnershipAttempt = synchronized(lock) {
        val ready = owner as? OwnershipState.Ready
        if (ready != null) {
            return@synchronized if (ready.identity == identity) {
                // Case 1: Ready same identity — synthesize a completed attempt.
                PreparedAttemptState(
                    attemptId = ++attemptIdCounter,
                    identity = identity,
                    starting = CompletableDeferred<StartingAck>().apply {
                        complete(StartingAck.Accepted(identity))
                    },
                    terminal = ready.terminal.also {
                        // Owner's terminal should already be completed with Ready;
                        // ensure it for safety.
                        it.complete(OwnershipStartResult.Ready(identity))
                    },
                ).toPublic(launchRequired = false)
            } else {
                // Case 4: different Ready owner.
                PreparedAttemptState(
                    attemptId = ++attemptIdCounter,
                    identity = identity,
                    starting = CompletableDeferred<StartingAck>().apply {
                        complete(StartingAck.Refused(OwnershipRefusal.AlreadyOwned(ready.identity)))
                    },
                    terminal = CompletableDeferred<OwnershipStartResult>().apply {
                        complete(OwnershipStartResult.Refused(OwnershipRefusal.AlreadyOwned(ready.identity)))
                    },
                ).toPublic(launchRequired = false)
            }
        }
        val starting = owner as? OwnershipState.Starting
        if (starting != null) {
            return@synchronized if (starting.identity == identity) {
                // Case 2: Starting same identity — join the in-flight terminal.
                PreparedAttemptState(
                    attemptId = ++attemptIdCounter,
                    identity = identity,
                    starting = CompletableDeferred<StartingAck>().apply {
                        complete(StartingAck.Accepted(identity))
                    },
                    terminal = starting.terminal,
                ).toPublic(launchRequired = false)
            } else {
                // Case 4: different Starting owner.
                PreparedAttemptState(
                    attemptId = ++attemptIdCounter,
                    identity = identity,
                    starting = CompletableDeferred<StartingAck>().apply {
                        complete(StartingAck.Refused(OwnershipRefusal.AlreadyOwned(starting.identity)))
                    },
                    terminal = CompletableDeferred<OwnershipStartResult>().apply {
                        complete(OwnershipStartResult.Refused(OwnershipRefusal.AlreadyOwned(starting.identity)))
                    },
                ).toPublic(launchRequired = false)
            }
        }
        // Case 3: no owner → allocate attempt, create both deferreds, store.
        val state = PreparedAttemptState(
            attemptId = ++attemptIdCounter,
            identity = identity,
            starting = CompletableDeferred(),
            terminal = CompletableDeferred(),
        )
        pendingAttempt = state
        state.toPublic(launchRequired = true)
    }

    /**
     * D5-2 (#4) — back-compat shim for legacy callers (D4-B integration tests).
     * Returns the [PreparedOwnershipAttempt.terminal] deferred of a fresh
     * [prepareAttempt]; behaves like the prior `prepare(identity)` waiter
     * (completes on Ready/Refused, NOT on Starting acceptance).
     */
    fun prepare(identity: ConnectionIdentity): CompletableDeferred<OwnershipStartResult> =
        prepareAttempt(identity).terminal as CompletableDeferred<OwnershipStartResult>

    fun cancelWaiter(identity: ConnectionIdentity, waiter: CompletableDeferred<OwnershipStartResult>) {
        synchronized(lock) {
            waiters[identity]?.let { set ->
                set.remove(waiter)
                if (set.isEmpty()) waiters.remove(identity)
            }
        }
    }

    /**
     * D5-2 (#4) — the validated Stage 1 registration. The Service MUST pass
     * the [attemptId] it received from the launcher Intent so the gate can
     * verify the attempt is still live (launcher has not yet timed out).
     *
     * Outcomes:
     *  - [RegisterStartingOutcome.Accepted] — Starting owner recorded, the
     *    matching [PreparedOwnershipAttempt.starting] deferred completed with
     *    Accepted. The Service proceeds with its bootstrap.
     *  - [RegisterStartingOutcome.Expired] — the [attemptId] does not match
     *    the live prepared attempt (launcher already expired it via
     *    [expireAttempt] after the 5s Starting-acceptance window). The late
     *    Service invocation MUST abort: stopForeground + stopSelf + cancel
     *    any SSE, so it does NOT record an owner that would later promote to
     *    an orphan Ready owner.
     *  - [RegisterStartingOutcome.Conflict] — a different-identity owner
     *    already holds the gate. The Service should refuse and abort.
     *
     * [attemptId] == [NO_ATTEMPT_ID] is the legacy / sticky-bootstrap path
     * (no launcher deadline). Skips the attempt validation; records the owner
     * carrying a fresh terminal deferred if no prepared attempt matches.
     */
    fun registerStarting(
        identity: ConnectionIdentity,
        attemptId: Long,
        disconnectAndJoin: suspend (Boolean) -> Unit,
        abortStartup: () -> Unit,
    ): RegisterStartingOutcome {
        val toRefuseWaiters = mutableMapOf<ConnectionIdentity, List<CompletableDeferred<OwnershipStartResult>>>()
        val outcome: RegisterStartingOutcome = synchronized(lock) {
            // Validate a launcher-issued ID BEFORE the same-identity
            // idempotence check below. A late delivery from an expired attempt
            // must be rejected even if a newer attempt for the same identity
            // has already become Starting/Ready; otherwise the old Service
            // could be mistaken for the current owner.
            if (attemptId != NO_ATTEMPT_ID) {
                val pending = pendingAttempt
                if (pending == null || pending.attemptId != attemptId || pending.identity != identity) {
                    return@synchronized RegisterStartingOutcome.Expired
                }
            }
            val current = owner
            if (current != null) {
                if (current.identity == identity) {
                    // D5-2 (#4): idempotent — an owner for this identity is
                    // already recorded (e.g. onStartCommand registered via the
                    // launcher attemptId, then the controller's bootstrap
                    // success invoked onBootstrapIdentity). Return Accepted
                    // WITHOUT replacing the owner or its terminal deferred
                    // (the launcher still awaits the original terminal).
                    return@synchronized RegisterStartingOutcome.Accepted
                }
                // Different identity owns — Conflict. Also drop waiters for [identity].
                val pending = pendingAttempt
                if (pending != null && pending.identity == identity &&
                    (attemptId == NO_ATTEMPT_ID || pending.attemptId == attemptId)
                ) {
                    pendingAttempt = null
                    val refusal = OwnershipRefusal.AlreadyOwned(current.identity)
                    pending.starting.complete(StartingAck.Refused(refusal))
                    pending.terminal.complete(OwnershipStartResult.Refused(refusal))
                }
                waiters.remove(identity)?.forEach {
                    it.complete(OwnershipStartResult.Refused(OwnershipRefusal.AlreadyOwned(current.identity)))
                }
                return@synchronized RegisterStartingOutcome.Conflict(current.identity)
            }
            val terminal: CompletableDeferred<OwnershipStartResult> =
                if (attemptId == NO_ATTEMPT_ID) {
                    // Back-compat / sticky path. Use pendingAttempt if it matches
                    // identity; else create a standalone terminal deferred.
                    val pending = pendingAttempt
                    if (pending != null && pending.identity == identity) {
                        pending.starting.complete(StartingAck.Accepted(identity))
                        pendingAttempt = null
                        pending.terminal
                    } else {
                        CompletableDeferred()
                    }
                } else {
                    // Validated path — attempt ID MUST match the live prepared attempt.
                    val pending = pendingAttempt
                    if (pending == null || pending.attemptId != attemptId || pending.identity != identity) {
                        // Attempt ID expired (launcher gave up) or superseded — REJECT.
                        // The late Service invocation MUST abort.
                        return@synchronized RegisterStartingOutcome.Expired
                    }
                    pending.starting.complete(StartingAck.Accepted(identity))
                    pendingAttempt = null
                    pending.terminal
                }
            owner = OwnershipState.Starting(identity, disconnectAndJoin, abortStartup, terminal)
            // Refuse waiters for OTHER identities (legacy waiter set; the new
            // model uses the per-attempt terminal deferred but we keep the old
            // waiter semantics for back-compat).
            val refused = waiters.filterKeys { it != identity }
            refused.forEach { (requested, deferreds) ->
                waiters.remove(requested)
                toRefuseWaiters[requested] = deferreds.toList()
            }
            RegisterStartingOutcome.Accepted
        }
        toRefuseWaiters.values.flatten().forEach {
            it.complete(OwnershipStartResult.Refused(OwnershipRefusal.AlreadyOwned(identity)))
        }
        return outcome
    }

    /**
     * D4-B B1 (back-compat) — old signature without an attempt ID. Used by
     * the D4-B integration tests that pre-date the D5-2 attempt-ID model.
     * Routes to the new signature with [NO_ATTEMPT_ID] (no launcher deadline).
     *
     * @return `true` iff the Starting ownership was recorded (Accepted); `false`
     *   iff a different-identity owner already held the gate (Conflict).
     */
    fun registerStarting(
        identity: ConnectionIdentity,
        disconnectAndJoin: suspend (Boolean) -> Unit,
        abortStartup: () -> Unit,
    ): Boolean = when (registerStarting(identity, NO_ATTEMPT_ID, disconnectAndJoin, abortStartup)) {
        RegisterStartingOutcome.Accepted -> true
        is RegisterStartingOutcome.Conflict -> false
        RegisterStartingOutcome.Expired -> false
    }

    /**
     * D5-2 (#4) — expire the prepared attempt (the launcher's 5s Starting-
     * acceptance window elapsed without [registerStarting] accepting the
     * attempt). Clears the pending attempt so any LATE Service invocation
     * discovers its [attemptId] is expired and ABORTS. Also completes the
     * attempt's deferreds with [reason] so the launcher's awaits resolve.
     *
     * Race safety: if a Starting owner was JUST recorded for the matching
     * identity (extreme boundary race where starting completed at the same
     * instant the launcher gave up), the owner is rolled back so a later
     * [markReady] finds no owner and becomes a no-op (no orphan Ready owner).
     * The owner's terminal deferred is completed with [reason] BEFORE the
     * extraction so the launcher's await resolves consistently.
     */
    suspend fun expireAttempt(attemptId: Long, reason: OwnershipRefusal) {
        val extracted: OwnershipState.Starting?
        synchronized(lock) {
            val pending = pendingAttempt
            if (pending == null || pending.attemptId != attemptId) {
                // Already cleared or different attempt. Nothing to expire.
                extracted = null
            } else {
                pendingAttempt = null
                pending.terminal.complete(OwnershipStartResult.Refused(reason))
                pending.starting.complete(StartingAck.Refused(reason))
                // Race safety: roll back any Starting owner for this identity
                // so a late markReady cannot promote it to an orphan Ready.
                val ownerNow = owner
                extracted = if (ownerNow != null &&
                    ownerNow.identity == pending.identity &&
                    ownerNow is OwnershipState.Starting
                ) {
                    owner = null
                    ownerNow
                } else {
                    null
                }
            }
        }
        // Boundary-race cleanup: if Stage 1 was recorded just as the launcher
        // deadline fired, cancel/join its SSE and invoke the Service abort
        // callback outside the gate lock. The normal late-delivery path still
        // aborts in SessionStreamingService when registerStarting returns
        // Expired; this handles only the already-registered race.
        extracted?.disconnectAndJoin?.invoke(false)
        extracted?.abortStartup?.invoke()
    }

    /**
     * D5-2 (#4) — exposed for tests / diagnostics: is the given [attemptId]
     * still the live prepared attempt? The Service does NOT need this (it
     * consults [registerStarting]'s outcome), but tests assert on it.
     */
    fun isAttemptLive(attemptId: Long): Boolean = synchronized(lock) {
        pendingAttempt?.attemptId == attemptId
    }

    /**
     * D4-B B1 Stage 2 — promote the Starting owner to Ready + complete the
     * matching launcher's terminal deferred with [OwnershipStartResult.Ready].
     * Called by the Service after the SSE transport delivers a valid current-
     * identity frame AND the coordinator commits the Bootstrap handoff.
     *
     * Idempotent: a second call for an already-Ready owner (e.g. an L2Idle-
     * exit SSE re-verifies transport) re-completes the terminal deferred
     * harmlessly and stays Ready. A mismatching identity / no owner is a no-op
     * (this is the orphan-owner guard: a late markReady after [expireAttempt]
     * cleared the Starting owner finds nothing to promote).
     */
    fun markReady(identity: ConnectionIdentity) {
        val toComplete: List<CompletableDeferred<OwnershipStartResult>> = synchronized(lock) {
            val current = owner ?: return@synchronized emptyList()
            if (current.identity != identity) return@synchronized emptyList()
            if (current is OwnershipState.Starting) {
                owner = OwnershipState.Ready(identity, current.disconnectAndJoin, current.terminal)
            }
            val list = mutableListOf<CompletableDeferred<OwnershipStartResult>>()
            current.terminal.let { list += it }
            waiters.remove(identity)?.toList()?.let { list += it }
            list
        }
        toComplete.forEach { it.complete(OwnershipStartResult.Ready(identity)) }
    }

    /**
     * D4-B B1 — refuse pending waiters for [identity] with [reason]. Does NOT
     * release an established owner (use [failStarting] / [release] for that).
     * Also completes any live prepared attempt's deferreds for [identity] so
     * the launcher does not hang on a vanished bootstrap.
     */
    fun refuse(identity: ConnectionIdentity, reason: OwnershipRefusal) {
        val pending = synchronized(lock) {
            val pendingList = waiters.remove(identity)?.toList().orEmpty()
            val p = pendingAttempt
            if (p != null && p.identity == identity) {
                pendingAttempt = null
                p.terminal.complete(OwnershipStartResult.Refused(reason))
                p.starting.complete(StartingAck.Refused(reason))
            }
            pendingList
        }
        pending.forEach { it.complete(OwnershipStartResult.Refused(reason)) }
    }

    /**
     * D4-B B1 mandatory rollback — the full Starting→refusal entry. Under the
     * lock: completes the owner's terminal deferred + matching waiters with
     * [OwnershipStartResult.Refused]([reason]) + extracts + nulls the Starting
     * owner whose identity matches [identity] (or ANY Starting owner when
     * [identity] is null). Returns the extracted [OwnershipState.Starting] so
     * the caller can invoke its [OwnershipState.Starting.abortStartup] +
     * [OwnershipState.disconnectAndJoin] OUTSIDE the lock (no suspension under
     * the lock).
     *
     * A Ready owner is NOT rolled back by this method (transport already
     * proved); only Starting ownership is. Returns null when no Starting
     * owner matched.
     */
    suspend fun failStarting(identity: ConnectionIdentity?, reason: OwnershipRefusal): OwnershipState.Starting? {
        val extracted: OwnershipState.Starting? = synchronized(lock) {
            val current = owner
            val starting = current as? OwnershipState.Starting
            if (starting == null) {
                // No Starting owner — still refuse any matching waiter so the
                // launcher does not hang on a vanished bootstrap.
                if (identity != null) {
                    waiters.remove(identity)?.forEach {
                        it.complete(OwnershipStartResult.Refused(reason))
                    }
                    val p = pendingAttempt
                    if (p != null && p.identity == identity) {
                        pendingAttempt = null
                        p.terminal.complete(OwnershipStartResult.Refused(reason))
                        p.starting.complete(StartingAck.Refused(reason))
                    }
                }
                return@synchronized null
            }
            if (identity != null && starting.identity != identity) {
                return@synchronized null
            }
            owner = null
            starting.terminal.complete(OwnershipStartResult.Refused(reason))
            waiters.remove(starting.identity)?.forEach {
                it.complete(OwnershipStartResult.Refused(reason))
            }
            starting
        }
        return extracted
    }

    /**
     * D4-B — extracts + nulls the current owner (any state) + invokes its
     * [OwnershipState.disconnectAndJoin] suspend callback OUTSIDE the lock.
     * Used by [StreamingLifecycleCoordinator.teardownAndAwait] for the
     * Reconfigure / Disconnect / Timeout / UserClose paths.
     *
     * D5-2 (#4): settles the owner's terminal deferred with Refused(
     * ServiceStopped) BEFORE extracting so the launcher's await resolves.
     */
    suspend fun disconnectAndRelease(markGap: Boolean) {
        val current = synchronized(lock) {
            val c = owner
            c?.terminal?.complete(OwnershipStartResult.Refused(OwnershipRefusal.ServiceStopped))
            owner = null
            c
        }
        current?.disconnectAndJoin?.invoke(markGap)
    }

    /**
     * D4-B — release the owner if its identity matches. Non-suspend entry
     * for [SessionStreamingService.onDestroy] (replaces the prior
     * `runBlocking { release(identity) }` which blocked the main thread).
     *
     * D5-2 (#4): settles the owner's terminal deferred with Refused(
     * ServiceStopped) so the launcher's await resolves.
     */
    fun releaseNow(identity: ConnectionIdentity) {
        synchronized(lock) {
            val current = owner
            if (current?.identity == identity) {
                current.terminal.complete(OwnershipStartResult.Refused(OwnershipRefusal.ServiceStopped))
                owner = null
            }
        }
    }

    /** Suspend alias kept for compatibility; prefer [releaseNow] from non-suspend callers. */
    suspend fun release(identity: ConnectionIdentity) {
        releaseNow(identity)
    }

    companion object {
        /**
         * D5-2 (#4) — sentinel for "no launcher attempt ID". Used by the
         * back-compat [registerStarting] signature and by sticky-rebuild
         * Service invocations (null Intent → no EXTRA_ATTEMPT_ID) that have
         * no launcher deadline to honor.
         */
        const val NO_ATTEMPT_ID: Long = -1L
    }
}

@Singleton
class OwnershipAckPolicy @Inject constructor() {
    val timeoutMs: Long = 5_000L
}

object OwnershipRequestParser {
    const val EXTRA_EPOCH = "cn.vectory.ocdroid.extra.ownership.epoch"
    const val EXTRA_SERVER_GROUP_FP = "cn.vectory.ocdroid.extra.ownership.serverGroupFp"
    const val EXTRA_WORKDIR = "cn.vectory.ocdroid.extra.ownership.workdir"
    const val EXTRA_ENDPOINT_FP = "cn.vectory.ocdroid.extra.ownership.endpointFp"

    /**
     * D5-2 (#4) — carries the gate's monotonic attempt ID. The launcher
     * stamps this so the late-arriving Service `onStartCommand` can pass it
     * to [StreamingOwnershipGate.registerStarting]; if the launcher has
     * already expired the attempt (5s AckTimeout), the gate returns Expired
     * and the Service aborts (orphan-owner closure).
     */
    const val EXTRA_ATTEMPT_ID = "cn.vectory.ocdroid.extra.ownership.attemptId"

    fun parse(
        epoch: Long?,
        serverGroupFp: String?,
        workdir: String?,
        endpointFp: String?,
    ): ConnectionIdentity? = if (
        epoch != null && serverGroupFp != null && workdir != null && endpointFp != null
    ) {
        ConnectionIdentity(epoch, serverGroupFp, workdir, endpointFp)
    } else {
        null
    }
}
