package cn.vectory.ocdroid.service.streaming

/**
 * D2 (gate #4 / §4.4 «acknowledged readiness, not coroutine launch») → **D4-B
 * M3 (transport-readiness / status-authority separation)**: the result of
 * activating a streaming source (SSE collector OR the §6 background poller) —
 * the [StreamingLifecycleCoordinator]'s handoff commit does not fire until one
 * of these is received for the corresponding
 * [LifecycleCommand.StartSse] / [LifecycleCommand.StartPoller] command.
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
 * **D4-B M3 — transport readiness vs status authority**: [Ready] now means
 * **transport-ready** (the SSE collector delivered at least one valid
 * current-identity frame; the poller completed its immediate first poll) —
 * NOT a status verdict. The coordinator consults
 * [cn.vectory.ocdroid.service.status.StatusAggregator.stateAtNow] at handoff
 * commit to decide the layer transition (Busy/AllIdleFresh vs Unknown). This
 * separates «can we prove the transport works» from «is the host busy» so
 * that a host whose REST status snapshot is Unknown (failed / not yet fresh)
 * no longer hangs the SSE bootstrap — the transport commits + a supplemental
 * poller keeps the status authority alive until a definitive verdict arrives.
 *
 *  - [Ready] — the source produced at least one verifiable, current-identity
 *    observation that proves the transport works. The coordinator reads
 *    status authority separately at commit.
 *  - [Rejected] — the source could NOT establish transport readiness. The
 *    coordinator's handoff commit cancels the activation (stops the new
 *    source if it was started) and leaves the prior layer + prior source
 *    intact (except for teardown decisions, which proceed regardless).
 *    Rejections never consume the §4.4 ordering invariant — they do NOT
 *    close the old source on non-teardown transitions.
 */
sealed interface SourceActivation {

    /**
     * **D4-B M3**: the source is transport-ready — at least one verifiable,
     * current-identity observation proved the transport works (SSE first
     * frame / poller first poll). The coordinator consults
     * [cn.vectory.ocdroid.service.status.StatusAggregator.stateAtNow] at
     * commit to decide the layer transition + whether to retire the
     * supplemental poller.
     */
    data object Ready : SourceActivation

    /**
     * The source could not establish transport readiness. The handoff commit
     * MUST NOT close the prior source on non-teardown transitions — the
     * rejection leaves the layer + source topology intact (the new source, if
     * any, is torn down by the commit).
     */
    sealed interface Rejected : SourceActivation {

        /**
         * SSE / poller activation arrived with an identity that
         * [ConnectionIdentityStore.isCurrent]
         * rejects (a queued StartSse / StartPoller from a prior epoch, or
         * the host reconfigured mid-activation). No network retry budget
         * consumed; no UI error surfaced for the new identity; no gap-dirty
         * signal emitted.
         */
        data object StaleIdentity : Rejected

        /**
         * SSE activation was rejected because the TOFU trust prompt is
         * pending ([ConnectionBootstrapCoordinator.pendingTofuHostPort]
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
         * [StreamingLifecycleCoordinator.onDisconnect]
         * → L3 teardown (exactly once).
         */
        data object Exhausted : Rejected

        /**
         * **D4-B M3**: the SSE transport did NOT deliver a valid current-
         * identity frame within the 30s transport activation timeout
         * ([ServiceSseConnectionOwner.TRANSPORT_READY_TIMEOUT_MS]). Unlike
         * [Exhausted] (which fires only after the full service-level retry
         * budget is spent), [TransportTimeout] fires once the FIRST
         * activation attempt proves unproductive for the bounded readiness
         * window — the handoff commit treats it as a bootstrap/transport
         * failure and routes through [StreamingOwnershipGate.failStarting]
         * → full rollback (B1).
         */
        data object TransportTimeout : Rejected
    }
}
