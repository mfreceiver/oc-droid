package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.service.status.GlobalBusyState

/**
 * D2 (gate #4 / §4.4 «acknowledged readiness, not coroutine launch»): the
 * result of activating a streaming source (SSE collector OR the §6 background
 * poller) — the [StreamingLifecycleCoordinator]'s handoff commit does not
 * fire until one of these is received for the corresponding
 * [cn.vectory.ocdroid.service.lifecycle.LifecycleCommand.StartSse] /
 * [cn.vectory.ocdroid.service.lifecycle.LifecycleCommand.StartPoller]
 * command.
 *
 * **Why a sealed result rather than `Unit`**: previously the controller
 * launched the poller job / SSE collector and trusted that launch ==
 * readiness. That made the L2Active→L2Idle handoff commit `StopSse` on a
 * coroutine launch — even when the poller's immediate first poll would
 * discover `Busy` (and the §4.4 ordering invariant requires the new source
 * to have actually produced data before the old source is closed). The
 * acknowledgeable contract closes that gap: the activator returns
 * [SourceActivation.Ready] ONLY after a verifiable observation, and the
 * coordinator's handoff commit is the sole consumer of that signal.
 *
 * - [Ready] — the source produced at least one verifiable, current-identity
 *   observation. [Ready.state] is the time-correct
 *   [GlobalBusyState] the coordinator uses to decide whether to commit the
 *   layer transition (e.g. for L2Active→L2Idle: only [GlobalBusyState.AllIdleFresh]
 *   commits `StopSse`; [GlobalBusyState.Busy] / [GlobalBusyState.Unknown]
 *   cancel the activation, stop the new poller, and stay L2Active).
 * - [Rejected] — the source could NOT establish a verified baseline. The
 *   coordinator's handoff commit cancels the activation (stops the new
 *   source if it was started) and leaves the prior layer + prior source
 *   intact. Rejections never consume the §4.4 ordering invariant — they do
 *   NOT close the old source.
 */
sealed interface SourceActivation {

    /**
     * The source is ready — at least one verifiable, current-identity
     * observation established a baseline. [state] is the post-activation
     * verdict the coordinator consults to decide whether to commit the
     * transition.
     */
    data class Ready(val state: GlobalBusyState) : SourceActivation

    /**
     * The source could not establish a verified baseline. The handoff commit
     * MUST NOT close the prior source — the rejection leaves the layer +
     * source topology intact (the new source, if any, is torn down by the
     * commit).
     */
    sealed interface Rejected : SourceActivation {

        /**
         * SSE / poller activation arrived with an identity that
         * [cn.vectory.ocdroid.service.identity.ConnectionIdentityStore.isCurrent]
         * rejects (a queued StartSse / StartPoller from a prior epoch, or
         * the host reconfigured mid-activation). No network retry budget
         * consumed; no UI error surfaced for the new identity; no gap-dirty
         * signal emitted.
         */
        data object StaleIdentity : Rejected

        /**
         * SSE activation was rejected because the TOFU trust prompt is
         * pending ([cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator.pendingTofuHostPort]
         * != null) — the TLS handshake cannot succeed until the user
         * decides. No retry budget consumed; the bootstrap coordinator's
         * retry loop re-issues the activation after the decision.
         */
        data object TofuPending : Rejected

        /** Activation was explicitly replaced or stopped before readiness. */
        data object Superseded : Rejected

        /**
         * SSE activation exhausted the §5 step 6 service-level retry budget
         * (3 collector retries past the SSEClient's internal 10-attempt
         * exhaustion — see [SseRecoveryPolicy]). The collector has already
         * emitted the gap-dirty signal idempotently; the handoff commit
         * routes through
         * [cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator.onDisconnect]
         * → L3 teardown (exactly once).
         */
        data object Exhausted : Rejected
    }
}
