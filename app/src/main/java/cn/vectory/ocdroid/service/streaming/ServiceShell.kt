package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.notify.NotificationSpec
import cn.vectory.ocdroid.service.status.StatusSnapshot

/**
 * FGS spec §1.0 — the side-effect surface [SessionStreamingController] drives.
 *
 * Pure-Kotlin interface so the command→action dispatch is unit-testable with a
 * recording fake shell + the real
 * [cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator] — no
 * Robolectric needed.
 *
 * The Android [cn.vectory.ocdroid.service.SessionStreamingService] is the only
 * production implementation: it holds a private `ServiceShell` that delegates
 * to `ServiceCompat` / `NotificationManagerCompat` / `stopSelf` /
 * [ServiceSseConnectionOwner] / [ProcessStatusPoller].
 *
 * **D2 gate #4 / §4.4 acknowledgeable source activation**: [connectSse] and
 * [startPoller] are `suspend` functions returning [SourceActivation]. The
 * controller awaits the activation result, then forwards it to
 * [cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator.onActivationAck]
 * — the coordinator's handoff commit runs ONLY after the source establishes
 * (or fails to establish) a verified baseline. The shell fakes in tests
 * script the activation (Ready / Rejected) to drive each handoff branch.
 *
 * **L3 source survival**: the production [startPoller] delegates to
 * [ProcessStatusPoller.startAndAwaitFirstPoll] which runs the loop on
 * `@ApplicationScope` (process-lifetime — survives
 * [cn.vectory.ocdroid.service.SessionStreamingService.onDestroy]). The shell
 * method itself is `suspend` so the controller's await measures the
 * immediate-first-poll latency.
 */
interface ServiceShell {

    /**
     * Promote the service to dataSync FGS with [spec] (§4.2 L1-idle→L1-busy
     * elevation, §5 step 2 placeholder, §5 step 4 keep-FGS). Idempotent if
     * already in foreground — the resulting notification replaces the prior
     * one (same [SESSION_STATUS_NOTIFICATION_ID]).
     */
    fun startForeground(spec: NotificationSpec)

    /**
     * Update the foreground notification's content WITHOUT changing FGS
     * promotion state. Used by the §5 degraded transition (no teardown, no
     * promotion — just surface the Open-app hint).
     */
    fun updateNotification(spec: NotificationSpec)

    /**
     * Downgrade from FGS to a normal started Service (§4.1 L1-idle, or
     * foreground return while idle). Does NOT stopSelf.
     */
    fun stopForeground()

    /**
     * §4.4 teardown terminal — stop the service after [stopForeground]. Maps
     * to [android.app.Service.stopSelf].
     *
     * Named `serviceStopSelf` because `Service.stopSelf()` is final and cannot
     * be overridden — the production shell wraps the call.
     */
    fun serviceStopSelf()

    /**
     * D2 §4.4 — start the §6 background poller bound to [identity] +
     * [snapshot], AWAIT its immediate-first-poll (no 30s delay), and return
     * the resulting [SourceActivation]. The controller forwards the
     * activation to
     * [cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator.onActivationAck];
     * the coordinator's handoff commit consumes [SourceActivation.Ready.state]
     * to decide `StopSse` (AllIdleFresh) vs `StopPoller` (Busy/Unknown —
     * stay L2Active).
     *
     * The production impl delegates to
     * [ProcessStatusPoller.startAndAwaitFirstPoll]; the poller loop runs on
     * `@ApplicationScope` and survives Service death.
     *
     * @param identity the atomic identity capture from the command (the
     *  controller reads `identityStore.currentIdentity.value` at command
     *  emission time — no re-read of SettingsManager).
     * @param snapshot the atomic snapshot capture from the command (the
     *  controller reads `sessionSnapshotProvider.current()` at command
     *  emission time — the immediate first poll uses this verbatim).
     */
    suspend fun startPoller(identity: ConnectionIdentity, snapshot: StatusSnapshot): SourceActivation

    /**
     * §6 poller stop marker — cancels the [ProcessStatusPoller]'s loop job.
     * Idempotent (no-op if no loop is running).
     */
    fun stopPoller()

    /**
     * D5 (#2) — ensure a SUPPLEMENTAL process poller is running for
     * [identity]. Delegates to [ProcessStatusPoller.ensureRunning]: same
     * current identity already running → return Ready WITHOUT cancel/
     * restart; otherwise startAndAwaitFirstPoll. The controller forwards
     * the activation to
     * [cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator.onEnsurePollerAck].
     *
     * @param identity the atomic identity capture from the command.
     * @param snapshot the atomic snapshot capture from the command.
     */
    suspend fun ensurePoller(
        identity: ConnectionIdentity,
        snapshot: StatusSnapshot,
    ): SourceActivation

    /**
     * D2 §4.4 / §1 — start / retarget the SSE collector for [identity],
     * AWAIT transport readiness, and return the resulting [SourceActivation].
     * [SourceActivation.Ready] is transport-only: the first valid
     * current-identity SSE frame proves the connection, while the coordinator
     * reads the status authority separately at commit. The controller forwards the
     * activation to
     * [cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator.onActivationAck];
     * the coordinator's handoff commit consumes [SourceActivation.Ready] to
     * commit `StopPoller` + the layer flip, OR consumes
     * [SourceActivation.Rejected] to leave the prior source intact.
     *
     * Returns [SourceActivation.Ready] after the first successful
     * current-identity frame. It does NOT await or gate on a REST status
     * baseline; `Unknown` is handled by the coordinator's supplemental-poller
     * decision at commit, and transport retry/exhaustion remains the owner's
     * responsibility.
     *
     * Returns [SourceActivation.Rejected]:
     *  - [SourceActivation.Rejected.StaleIdentity] — the identity is no
     *    longer current (reconfigure invalidated the queued StartSse); no
     *    network retry budget consumed, no gap-dirty signal emitted.
     *  - [SourceActivation.Rejected.TofuPending] — a TOFU trust prompt is
     *    pending (the TLS handshake cannot succeed); no retry budget consumed.
     *  - [SourceActivation.Rejected.Exhausted] — the §5 step 6 service-level
     *    retry budget (3 attempts past the SSEClient's internal 10) is spent;
     *    the gap-dirty signal has already been emitted (idempotently).
     */
    suspend fun connectSse(identity: ConnectionIdentity): SourceActivation

    /**
     * §4.4 SSE collector teardown — cancels the in-flight collector and
     * emits the §1.1 gap-dirty signal (CancelSse effect). Idempotent via
     * [ServiceSseConnectionOwner]'s transport-generation tracking.
     *
     * D2 gate #7: the gap-dirty signal is independent of `Job.isActive` —
     * [ServiceSseConnectionOwner.disconnect] invokes the same idempotent
     * `emitGapOnce` path that the terminal-collection-exception branch uses.
     */
    suspend fun disconnectSse()
}
