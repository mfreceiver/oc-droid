package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.service.events.IdentifiedSseEvent
import cn.vectory.ocdroid.service.events.SseEventStream
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator
import cn.vectory.ocdroid.ui.ConnectionPhase
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * CP9 (notify Phase-0 switchover): the live SSE collector that USED to live
 * inside [cn.vectory.ocdroid.ui.controller.ConnectionCoordinator] (`sseJob`
 * + `launchSseCollection`) is now owned by the streaming Service.
 *
 * **Architecture (FGS spec §1 / §15.1)**: this class is the SOLE producer of
 * [IdentifiedSseEvent]s into the process-wide [SseEventStream]. The
 * [cn.vectory.ocdroid.service.bridge.SseEventBridge] subscribes eagerly from
 * AppCore init and routes each frame through the §2 epoch guard + the §11
 * dual-channel; AppCore re-emits each validated frame as
 * [ControllerEffect.OnSseEvent] for `SessionSyncCoordinator.fold`. The
 * downstream path is byte-for-byte preserved from CP3-8.
 *
 * **Not a Hilt singleton**: created by `SessionStreamingService.onCreate`
 * (one instance per Service instance) with Service-lifetime collaborators
 * that are themselves Hilt-provided. Test construction uses real
 * `OpenCodeRepository` / `ConnectionIdentityStore` / `SseEventStream` /
 * `SharedStateStore` / `SharedEffectBus` mocks/spies so the collector path
 * is exercised identically.
 *
 * **Identity contract (FGS spec §2)**: the [ConnectionIdentity] passed to
 * [connect] is the atomic capture (no re-read of `SettingsManager.currentWorkdir`).
 * A queued `StartSse` whose identity is no longer current
 * ([ConnectionIdentityStore.isCurrent] == false) is dropped before any
 * side effect — its host/workdir belongs to a reconfigure epoch that has
 * since been invalidated.
 *
 * **TOFU freeze**: while [ConnectionBootstrapCoordinator] holds a pending
 * TOFU trust prompt ([pendingTofuHostPort][cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator.pendingTofuHostPort]
 * != null), [connect] is a no-op. The TLS handshake would fail the same way
 * (the pin is not written until the user decides); resuming the bootstrap
 * retry loop after the user's decision re-issues `StartSse`.
 *
 * **Disconnect signal (FGS spec §1.1 / §4.1)**: [disconnect] emits exactly
 * one [ControllerEffect.CancelSse] when a live collector was actually
 * stopped AND `markGap == true`. This effect is now an OBSERVED
 * transport-disconnect signal (SessionSyncCoordinator stamps the current
 * session dirty + records the disconnect time so the next `server.connected`
 * reconciles) — NOT a request for CC to cancel a job (CC no longer owns a
 * job).
 *
 * @param scope the Service-lifetime [CoroutineScope] (Main.immediate in
 *   production; matches [StreamingLifecycleCoordinator]'s scope so command
 *   ordering + identity-check reads stay single-threaded).
 * @param repository SSE producer (FGS spec §15.1: `connectSSE(workdir)`).
 * @param identityStore the single process-level identity store (CP1).
 * @param bootstrapCoordinator the shared TOFU state (CP2); its
 *   `pendingTofuHostPort()` gates [connect] (TOFU freeze).
 * @param sseEventStream the process-wide stream the collector publishes to
 *   (CP3+). The bridge + downstream fold stay unchanged.
 * @param sharedStateStore SSE liveness writes (green-icon) land on
 *   `connectionFlow` here so the icon flips green even when the connection
 *   came in via the SSE auto-reconnect (the Service has no CC reference to
 *   delegate the write to).
 * @param sharedEffectBus event-level + collection-level failures emit
 *   `UiEvent.Error(R.string.error_sse_failed, ...)` here. Liveness + errors
 *   belong at the point that proves transport delivery (the collector), NOT
 *   in the bridge — see CP9 plan §D19.
 * @param onTerminalExhaustion invoked once after the collector exits a
 *   collection-level failure path (catch block); routes through
 *   [StreamingLifecycleCoordinator.onDisconnect] → L3 teardown. Skipped on
 *   normal cancellation (clean shutdown) and on stale-identity drops.
 */
class ServiceSseConnectionOwner(
    private val scope: CoroutineScope,
    private val repository: OpenCodeRepository,
    private val identityStore: ConnectionIdentityStore,
    private val bootstrapCoordinator: cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator,
    private val sseEventStream: SseEventStream,
    private val sharedStateStore: SharedStateStore,
    private val sharedEffectBus: SharedEffectBus,
    private val onTerminalExhaustion: () -> Unit,
) {
    /** The live SSE collector job, or null when no collector is running. */
    private var sseJob: Job? = null

    /**
     * Launches one SSE collector bound to [identity]. CP9 plan §A3 (EXACT
     * order):
     *  1. TOFU freeze: return without touching the current job if a TOFU
     *     trust prompt is pending.
     *  2. Stale-identity drop: return if [identity] is no longer current
     *     (drops a stale queued `StartSse`).
     *  3. Cancel + null the previous collector so reconnects / retargets
     *     never leave two collectors racing.
     *  4. Launch one new job.
     *  5. Call `repository.connectSSE(identity.normalizedWorkdir.ifBlank { null })`.
     *     The command identity is the atomic capture — do NOT re-read
     *     `SettingsManager.currentWorkdir`.
     *  6. For every `Result<SSEEvent>`, re-check `identityStore.isCurrent`
     *     BEFORE any side effect.
     *  7. On success: write green-icon liveness + emit the identified event
     *     through the suspend [SseEventStream.emit].
     *  8. On event-level failure: log + emit `UiEvent.Error(error_sse_failed)`.
     *  9. On collection-level exception: rethrow `CancellationException`; if
     *     identity still current, log + emit the same SSE error; then invoke
     *     [onTerminalExhaustion] after the collector exits.
     *  10. A stale collector emits NEITHER events, liveness writes, errors,
     *      NOR disconnect transitions.
     */
    fun connect(identity: ConnectionIdentity) {
        // §A3.1 — TOFU freeze.
        if (bootstrapCoordinator.pendingTofuHostPort() != null) {
            DebugLog.i(
                TAG,
                "connect: frozen — TOFU trust pending for " +
                    "${bootstrapCoordinator.pendingTofuHostPort()} (identity epoch=${identity.epoch})"
            )
            return
        }
        // §A3.2 — stale-identity drop.
        if (!identityStore.isCurrent(identity)) {
            DebugLog.i(
                TAG,
                "connect: dropping stale queued StartSse " +
                    "(identity epoch=${identity.epoch} current=${identityStore.currentEpoch()})"
            )
            return
        }
        // §A3.3 — cancel + null previous collector.
        sseJob?.cancel()
        sseJob = null
        // §A3.4 — launch one new job.
        sseJob = scope.launch {
            // §A3.5 — atomic capture: use the command identity's workdir.
            // The launcher / coordinator do NOT re-read SettingsManager.
            val workdirArg: String? = identity.normalizedWorkdir.ifBlank { null }
            // §A3.6 — collect with a per-side-effect identity re-check.
            repository.connectSSE(workdirArg)
                .catch { error ->
                    // §A3.9 — collection-level exception.
                    // ALWAYS rethrow CancellationException (structured
                    // concurrency contract — never swallow cooperative
                    // cancellation).
                    if (error is CancellationException) throw error
                    Log_e(TAG, "SSE collection failed", error)
                    // Only surface the failure if THIS collector's identity
                    // is still current — a stale collector (the reconfigure
                    // cancelled us mid-flight) emits NEITHER errors NOR a
                    // disconnect transition.
                    if (identityStore.isCurrent(identity)) {
                        sharedEffectBus.tryEmitUiEvent(
                            UiEvent.Error(
                                R.string.error_sse_failed,
                                listOf(error.message ?: "unknown error"),
                            )
                        )
                        // §A3.9 — request the L3 teardown AFTER the collector
                        // exits. The coordinator's `onDisconnect` is the
                        // §4.1 disconnect entry; it transitions to L3
                        // (stopForeground + stopSelf + StopSse + arm poller
                        // + dismiss ongoing). The teardown emission sees a
                        // collector that is already exiting (we're in its
                        // catch block) so no double-cancel race.
                        onTerminalExhaustion()
                    }
                }
                .collect { result ->
                    // §A3.6 — re-check identity BEFORE every side effect.
                    if (!identityStore.isCurrent(identity)) {
                        DebugLog.i(
                            TAG,
                            "drop stale-identity SSE event " +
                                "(epoch=${identity.epoch} → current=${identityStore.currentEpoch()})"
                        )
                        return@collect
                    }
                    result.onSuccess { event ->
                        // §A3.7 — liveness + identified emit.
                        // SSE liveness: a successful event (initial connect
                        // OR retryWhen recovery after a network outage)
                        // proves the server is reachable. Mirror into
                        // connectionFlow so the server icon flips green even
                        // when recovery happened via the SSE auto-reconnect
                        // rather than a CC health probe.
                        sharedStateStore.mutateConnection {
                            it.copy(
                                isConnected = true,
                                isConnecting = false,
                                connectionPhase = ConnectionPhase.Connected,
                            )
                        }
                        // Publish into the process-wide stream — the bridge
                        // (subscribed eagerly from AppCore) routes through
                        // the §2 epoch guard + §11 dual-channel + re-emits
                        // each validated frame as OnSseEvent for SSC's fold.
                        sseEventStream.emit(Result.success(IdentifiedSseEvent(identity, event)))
                    }.onFailure { error ->
                        // §A3.8 — event-level failure.
                        Log_e(TAG, "SSE event failed", error)
                        sharedEffectBus.tryEmitUiEvent(
                            UiEvent.Error(
                                R.string.error_sse_failed,
                                listOf(error.message ?: "unknown error"),
                            )
                        )
                    }
                }
        }
    }

    /**
     * CP9 plan §A4: cancels the in-flight collector. Emits exactly ONE
     * [ControllerEffect.CancelSse] when a live job was actually stopped AND
     * `markGap == true` — the effect is now an OBSERVED
     * transport-disconnect signal (Service going away, user explicit close,
     * reconfigure teardown), NOT a request for CC to cancel a job (CC no
     * longer owns a job).
     *
     * Idempotent: a second call with no live collector emits nothing.
     */
    fun disconnect(markGap: Boolean = true) {
        val job = sseJob
        val wasActive = job?.isActive == true
        job?.cancel()
        sseJob = null
        if (wasActive && markGap) {
            // The SessionSyncCoordinator's init collector observes this and
            // stamps the current session dirty + records the disconnect time
            // so the next `server.connected` reconciles. AppCore's
            // dispatchForegroundCatchUpEffect(CancelSse) no longer recurses
            // into CC.cancelSse (CP9 §D21) — that loop is broken here.
            sharedEffectBus.tryEmitEffect(ControllerEffect.CancelSse)
        }
    }

    private companion object {
        private const val TAG = "ServiceSseOwner"

        // Indirection so tests do not need to mockkStatic(Log). Production
        // routes through android.util.Log; tests see a no-op.
        private fun Log_e(tag: String, msg: String, throwable: Throwable) {
            try {
                android.util.Log.e(tag, msg, throwable)
            } catch (_: Throwable) {
                // unit tests run without android.util.Log; the message is
                // still useful via DebugLog if a spy is attached.
            }
        }
    }
}
