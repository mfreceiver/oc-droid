package cn.vectory.ocdroid.service.streaming

/**
 * D2 (gate #4 / §4.4 «acknowledged readiness, not coroutine launch») → **D4-B
 * M3 (transport-readiness / status-authority separation)**: the result of
 * activating a streaming source (SSE collector OR the §6 background poller) —
 * the coordinator's handoff commit does not fire until one
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
 * NOT a status verdict. The coordinator consults the status authority at handoff
 * commit to decide the layer transition (Busy/AllIdleFresh vs Unknown). (D4-B
 * M3's `StatusAggregator.stateAtNow` consumer was retired in F6 — the read side
 * had no production consumer.) This
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
     * frame / poller first poll). The coordinator consults the status authority
     * at commit to decide the layer transition + whether to retire the
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
         * Legacy TOFU-freeze rejection (L7: TOFU removed). Retained as
         * dead variant for ABI compat — never produced at runtime.
         */
        data object TofuPending : Rejected

        /** Activation was explicitly replaced or stopped before readiness. */
        data object Superseded : Rejected

        /**
         * PRE-READY failure: the SSE collector's single attempt broke /
         * completed WITHOUT ever delivering a valid current-identity frame
         * (see [ServiceSseConnectionOwner.launchSseCollector] — single
         * attempt, NO service-level retry loop; the §5 step 6 3-retry loop
         * died in L2, see [StreamingModule] L2 removals). The collector
         * body atomically releases the ownership-gate lease + rolls back
         * the runtime attempt (§review-blocker-#2, under the drop-handler
         * monitor) and completes the readiness deferred with
         * [Rejected.Exhausted]. The handoff commit receives Exhausted,
         * cancels the activation, and leaves the prior layer + prior source
         * intact (per the [Rejected] contract above).
         *
         * **Exhausted does NOT invoke onTerminalDrop and does NOT emit a
         * gap-dirty signal** — the pre-ready path never established
         * transport, so there is no gap to signal. This is asserted by
         * `pre-ready flow throw rejects Exhausted, no gap, no terminal
         * callback` (ServiceSseConnectionOwnerTest).
         *
         * **Distinct from the POST-READY terminal drop**: when an
         * ALREADY-ready collector's flow later breaks/completes, the
         * collector body takes a different branch that DOES call
         * [ServiceSseConnectionOwner]'s `onTerminalDrop` (fenced exactly-
         * once by routeUnexpectedDrop) →
         * [cn.vectory.ocdroid.ui.controller.ControllerEffect.ColdStartReconnect]
         * → L3 teardown. That post-ready drop is a runtime outage route,
         * NOT a [SourceActivation] result (readiness was already completed
         * with [Ready]) — it must not be conflated with [Exhausted].
         */
        data object Exhausted : Rejected

        /**
         * **D4-B M3**: the SSE transport did NOT deliver a valid current-
         * identity frame within the 30s transport activation timeout
         * ([ServiceSseConnectionOwner.TRANSPORT_READY_TIMEOUT_MS]). Unlike
         * [Exhausted] (which fires when the single collector attempt
         * actively breaks/completes pre-ready), [TransportTimeout] fires
         * when that attempt merely hangs — no frame and no flow termination
         * within the bounded readiness window — the handoff commit treats
         * it as a bootstrap/transport failure and routes through
         * [StreamingOwnershipGate.failStarting] → full rollback (B1).
         */
        data object TransportTimeout : Rejected
    }
}
