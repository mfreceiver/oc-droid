package cn.vectory.ocdroid.service

import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import javax.inject.Inject
import javax.inject.Singleton

/**
 * D4-B B1 — the result the [StreamingServiceLauncher] returns to
 * [cn.vectory.ocdroid.ui.controller.ConnectionCoordinator]. The launcher completes with
 * [Ready] ONLY after the Service has marked ownership Ready (Stage 2 —
 * transport-ready), so CC publishes [cn.vectory.ocdroid.ui.ConnectionPhase.Connected]
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
    /**
     * §sse-disabled-debug-toggle: the client's DEBUG `sse_disabled` flag is ON
     * — the launcher REFUSED to start the SSE foreground service (REST-only
     * degraded mode). Surfaced to callers as a distinct refusal so the
     * connection phase can render [cn.vectory.ocdroid.ui.ConnectionPhase.SseDisabled]
     * instead of a generic Disconnected.
     */
    data object SseDisabled : OwnershipRefusal
}

/**
 * D5-2 (#4) — the prepared-attempt model that closes the orphan-owner hole.
 *
 * The launcher calls [StreamingOwnershipGate.prepareAttempt] BEFORE issuing
 * `startForegroundService`. The gate allocates a monotonic [attemptId] that
 * travels in the Service Intent as EXTRA_ATTEMPT_ID
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
    /** Launcher attempt that created this owner, or null for sticky/internal ownership. */
    val attemptId: Long?
    val disconnectAndJoin: suspend (Boolean) -> Unit
    val terminal: CompletableDeferred<OwnershipStartResult>

    data class Starting(
        override val identity: ConnectionIdentity,
        override val attemptId: Long?,
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
        override val attemptId: Long?,
        override val disconnectAndJoin: suspend (Boolean) -> Unit,
        override val terminal: CompletableDeferred<OwnershipStartResult>,
    ) : OwnershipState
}

@Singleton
class OwnershipAckPolicy @Inject constructor() {
    val timeoutMs: Long = 5_000L
}

/**
 * sse-zombie-fix (v4 R5): the ONLY legal way to run an extracted Starting
 * owner's teardown. Both callbacks fire outside any gate lock, in a fixed
 * order (transport cancel/join FIRST, then the shell abort). Callers of
 * [StreamingOwnershipGate.expireAttempt] / [failStarting] /
 * [failStartingIfTerminal] MUST route through this — invoking the two
 * callbacks by hand is forbidden (three call sites, one helper → the
 * "forgot the second callback" class is mechanically impossible).
 *
 * §sse-zombie-fix-impl-rev2 (rev-gpt v4-review critical C5): wrap
 * disconnectAndJoin in try/finally so [abortStartup] still runs if the
 * transport disconnect throws or the calling coroutine is cancelled during
 * the suspend cancel/join. Without this, a thrown disconnect would leave an
 * ownerless foreground Service / bootstrap shell.
 */
suspend fun OwnershipState.Starting.runTeardown(markGap: Boolean = false) {
    try {
        disconnectAndJoin(markGap)
    } finally {
        abortStartup()
    }
}
