package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.notify.NotificationSpec

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
 * to `ServiceCompat` / `NotificationManagerCompat` / `stopSelf` / SSE stubs.
 *
 * **CP5-7 seam**: [connectSse] and [disconnectSse] are deliberately **no-op
 * stubs** on the production shell — the SSE collector stays in
 `ConnectionCoordinator` until CP9 (HARD constraint). The ServiceShell methods
 * still exist so the dispatch test can verify §4.4 source-switch ordering
 * (StartSse BEFORE StopPoller, etc.) — the *sequence* of side-effects is what
 * the contract protects; whether the body actually opens a socket today is a
 * CP9 concern.
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
     * §6 poller source arming marker. The controller owns the actual poller
     * job (launched on its scope); this call is the observable side-channel
     * tests use to assert §4.4 source-switch ordering
     * (StartPoller BEFORE StopSse, etc.). Production impl is a no-op marker.
     */
    fun startPoller()

    /**
     * §6 poller stop marker. Mirrors [startPoller] — the controller cancels
     * its poller job; this is the observable marker.
     */
    fun stopPoller()

    /**
     * **CP9 stub.** Start / retarget the SSE collector for [identity] (FGS
     * spec §1 / §2). SSE ownership stays in `ConnectionCoordinator` until
     * CP9; today the production body is a DEBUG log only. The dispatch test
     * still calls this so source-switch ordering is verifiable end-to-end.
     */
    fun connectSse(identity: ConnectionIdentity)

    /**
     * **CP9 stub.** Tear down the in-flight SSE collector (§1.1 / §4.4).
     * SSE ownership stays in `ConnectionCoordinator` until CP9.
     */
    fun disconnectSse()
}
