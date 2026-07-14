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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * **Identity contract (FGS spec §2)**: the [ConnectionIdentity] passed to
 * [connect] is the atomic capture (no re-read of `SettingsManager.currentWorkdir`).
 * A queued `StartSse` whose identity is no longer current
 * ([ConnectionIdentityStore.isCurrent] == false) is dropped before any
 * side effect — its host/workdir belongs to a reconfigure epoch that has
 * since been invalidated. Returns [SourceActivation.Rejected.StaleIdentity].
 *
 * **TOFU freeze**: while [ConnectionBootstrapCoordinator] holds a pending
 * TOFU trust prompt ([pendingTofuHostPort][ConnectionBootstrapCoordinator.pendingTofuHostPort]
 * != null), [connect] is a no-op collector-wise. The TLS handshake would
 * fail the same way (the pin is not written until the user decides); resuming
 * the bootstrap retry loop after the user's decision re-issues `StartSse`.
 * Returns [SourceActivation.Rejected.TofuPending].
 *
 * **D2 gate #4 — acknowledgeable activation** → **D4-B M3 (transport-only
 * readiness)**: [connect] is a `suspend fun` returning [SourceActivation]:
 *  - [SourceActivation.Ready] — ONLY AFTER the first successful current-
 *    identity SSE frame proves the transport works. The readiness completes
 *    IMMEDIATELY on that frame (liveness + event publish + gap reset); it
 *    does NOT await or gate on a REST status baseline. The status authority
 *    (Busy / AllIdleFresh / Unknown) is consulted separately by the
 *    [StreamingLifecycleCoordinator] at handoff commit — a host whose
 *    snapshot is Unknown no longer hangs the SSE activation.
 *  - [SourceActivation.Rejected.TransportTimeout] — NO valid current-identity
 *    frame arrived within [TRANSPORT_READY_TIMEOUT_MS] (30s). The attempted
 *    collector is cancelled; the handoff commit routes through
 *    [StreamingOwnershipGate.failStarting] → full B1 rollback.
 *  - [SourceActivation.Rejected.StaleIdentity] / [SourceActivation.Rejected.TofuPending]
 *    — no network retry consumed.
 *  - [SourceActivation.Rejected.Exhausted] — §5 step 6 service-level retry
 *    budget spent; [onTerminalExhaustion] invoked exactly once.
 *
 * **D2 gate #7 — terminal SSE exhaustion + gap-dirty + service-level retry**:
 * the SSEClient's internal retry budget (10 attempts) is followed by 3
 * SERVICE-LEVEL retries with delays `30s / 2m / 5m` + ±20% jitter
 * ([SseRecoveryPolicy]). The gap-dirty signal ([ControllerEffect.CancelSse])
 * is emitted IDEMPOTENTLY per outage (independent of `Job.isActive`) via
 * [emitGapOnce] — repeated failures in the same outage do NOT emit duplicate
 * gap signals. Intentional cancellation (clean shutdown, reconfigure) +
 * stale-identity termination do NOT emit gap + do NOT start recovery.
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
 * @param recoveryPolicy D2 gate #7: the service-level retry schedule
 *   (30s / 2m / 5m + ±20% jitter).
 * @param transportTimeoutMs D4-B M3: the bounded window for the first valid
 *   current-identity frame. Default [TRANSPORT_READY_TIMEOUT_MS] (30s). If
 *   no frame arrives within this window the activation completes with
 *   [SourceActivation.Rejected.TransportTimeout].
 * @param onTerminalExhaustion invoked once after the collector exhausts the
 *   service-level retry budget (3 attempts past the SSEClient's internal 10);
 *   routes through [StreamingLifecycleCoordinator.onDisconnect] → L3 teardown.
 *   Skipped on normal cancellation (clean shutdown) and on stale-identity drops.
 */
class ServiceSseConnectionOwner(
    private val scope: CoroutineScope,
    private val repository: OpenCodeRepository,
    private val identityStore: ConnectionIdentityStore,
    private val bootstrapCoordinator: cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator,
    private val sseEventStream: SseEventStream,
    private val sharedStateStore: SharedStateStore,
    private val sharedEffectBus: SharedEffectBus,
    private val recoveryPolicy: SseRecoveryPolicy,
    private val transportTimeoutMs: Long = TRANSPORT_READY_TIMEOUT_MS,
    private val jitterSource: () -> Float = {
        kotlin.random.Random.nextFloat() * 0.4f - 0.2f
    },
    private val onTerminalExhaustion: () -> Unit,
) {
    /**
     * The live SSE collector job, or null when no collector is running.
     * Read/written under [connectMutex].
     */
    private var sseJob: Job? = null
    private var transportTimeoutJob: Job? = null
    private var activeIdentity: ConnectionIdentity? = null

    /**
     * D2 gate #4: the readiness deferred for the in-flight [connect]. The
     * `suspend fun connect` awaits this; the collector completes it on the
     * first current-identity frame (Ready), the transport timeout
     * (TransportTimeout), or [onTerminalExhaustion]'s exhausted signal.
     *
     * A SUPERSEDED connect (a newer [connect] arrived, or [disconnect] was
     * called) cancels this deferred — the older `connect` await throws
     * CancellationException + the controller's launch (which is just a
     * pass-through) propagates without acking.
     */
    private var pendingReadiness: CompletableDeferred<SourceActivation>? = null

    /**
     * D2 gate #7: monotonic transport-generation counter. Bumped on every
     * accepted [connect]; the gap-dirty emission uses it to keep
     * [gapEmittedForGen] scoped to ONE outage per generation (a successful
     * frame resets the gap flag; the next failure starts a NEW outage).
     */
    @Volatile
    private var transportGenerationCounter: Long = 0L

    /**
     * D2 gate #7: the transport generation for which the gap-dirty signal
     * has already been emitted. `-1L` = "no gap emitted for the current
     * generation". Reset to `-1L` on every successful current-identity frame
     * (a new outage can begin) + bumped to the current generation on the
     * first failure of each outage. Repeated failures in the same outage
     * (same generation, no successful frame between them) do NOT emit a
     * duplicate gap.
     */
    private var gapEmittedForGen: Long = -1L

    /**
     * D2 gate #7: the transport generation that has been reported via
     * [onTerminalExhaustion]. Ensures the L3 callback fires EXACTLY ONCE per
     * outage (a generation is one outage; a new [connect] bumps the
     * generation + re-arms the budget).
     */
    private var exhaustedReportedForGen: Long = -1L

    /**
     * D2 gate #7: guards [connect] (serializes the readiness deferred +
     * generation tracking + collector replacement). Without this, two
     * concurrent `scope.launch { connect(...) }` would race on `sseJob` +
     * `pendingReadiness`.
     */
    private val connectMutex = Mutex()

    /**
     * D2 gate #4 — launches one SSE collector bound to [identity], awaits
     * transport readiness OUTSIDE the [connectMutex] (so a newer [connect]
     * OR [disconnect] can supersede an in-flight await by cancelling
     * [pendingReadiness]), and returns the resulting [SourceActivation].
     *
     * **D4-B M3**: readiness completes on the FIRST valid current-identity
     * frame (transport-ready), NOT on a REST status baseline. A 30s transport
     * timeout produces [SourceActivation.Rejected.TransportTimeout].
     *
     * The setup (TOFU check, stale check, cancel prior collector, bump
     * generation, launch collector) runs under [connectMutex] so concurrent
     * connect calls are serialized on `sseJob` + `pendingReadiness`. The
     * await itself is OUTSIDE the lock.
     */
    suspend fun connect(identity: ConnectionIdentity): SourceActivation {
        val setup = connectMutex.withLock { setupConnectLocked(identity) }
        return when (setup) {
            is ConnectSetup.Rejected -> setup.activation
            is ConnectSetup.Started -> try {
                setup.readiness.await()
            } catch (e: CancellationException) {
                // The connect itself was superseded (a newer connect / a
                // disconnect cancelled this deferred). Propagate — the
                // controller's launch dies without acking.
                throw e
            }
        }
    }

    private sealed interface ConnectSetup {
        data class Started(val readiness: CompletableDeferred<SourceActivation>) : ConnectSetup
        data class Rejected(val activation: SourceActivation) : ConnectSetup
    }

    /**
     * The connect setup body — MUST be called under [connectMutex]. Performs
     * the TOFU / stale rejections synchronously (no collector started) +
     * otherwise launches the collector + returns the [ConnectSetup.Started]
     * carrying the in-flight readiness deferred.
     */
    private fun setupConnectLocked(identity: ConnectionIdentity): ConnectSetup {
        // §A3.1 — TOFU freeze.
        if (bootstrapCoordinator.pendingTofuHostPort() != null) {
            DebugLog.i(
                TAG,
                "connect: frozen — TOFU trust pending for " +
                    "${bootstrapCoordinator.pendingTofuHostPort()} (identity epoch=${identity.epoch})"
            )
            return ConnectSetup.Rejected(SourceActivation.Rejected.TofuPending)
        }
        // §A3.2 — stale-identity drop.
        if (!identityStore.isCurrent(identity)) {
            DebugLog.i(
                TAG,
                "connect: dropping stale queued StartSse " +
                    "(identity epoch=${identity.epoch} current=${identityStore.currentEpoch()})"
            )
            return ConnectSetup.Rejected(SourceActivation.Rejected.StaleIdentity)
        }
        // §A3.3 — cancel + null previous collector. Cancel prior readiness
        // (the prior connect's await throws CancellationException → its
        // controller launch dies without acking).
        sseJob?.cancel()
        sseJob = null
        transportTimeoutJob?.cancel()
        transportTimeoutJob = null
        activeIdentity = identity
        pendingReadiness?.cancel()
        val readiness = CompletableDeferred<SourceActivation>()
        pendingReadiness = readiness
        // §A3.4 — bump generation + reset per-generation gap/exhaustion flags.
        val generation = ++transportGenerationCounter
        gapEmittedForGen = -1L
        exhaustedReportedForGen = -1L
        // §A3.5 — launch one new job + the M3 transport-readiness timeout.
        sseJob = scope.launchSseCollector(identity, generation, readiness)
        transportTimeoutJob = scope.launchTransportTimeout(generation, readiness)
        return ConnectSetup.Started(readiness)
    }

    /**
     * D4-B M3 — the transport-readiness timeout. If no valid current-identity
     * frame completes [readiness] within [transportTimeoutMs], the activation
     * fails with [SourceActivation.Rejected.TransportTimeout] + the collector
     * is cancelled (the handoff commit routes through B1 rollback). Cancelled
     * by [onSuccessfulFrame] the moment a frame verifies.
     */
    private fun CoroutineScope.launchTransportTimeout(
        generation: Long,
        readiness: CompletableDeferred<SourceActivation>,
    ): Job = launch {
        try {
            delay(transportTimeoutMs)
        } catch (e: CancellationException) {
            return@launch
        }
        if (isCurrentTransport(generation) && !readiness.isCompleted) {
            DebugLog.w(TAG, "transport timeout (gen=$generation, ${transportTimeoutMs}ms, no frame)")
            // Cancel the collector so it does not keep churning after the
            // activation has been rejected.
            transportTimeoutJob = null
            sseJob?.cancel()
            sseJob = null
            readiness.complete(SourceActivation.Rejected.TransportTimeout)
        }
    }

    /**
     * D2 gate #7 — the SSE collector body, launched as [sseJob]. Runs the
     * §5 step 6 service-level retry loop: each iteration starts a fresh
     * `repository.connectSSE(workdir)` flow (which itself restarts the
     * SSEClient's internal 10-attempt budget); the first valid current-
     * identity frame completes [readiness] (Ready) and resets the retry
     * counter; after [SseRecoveryPolicy.attempts] failed iterations with no
     * frame, completes [readiness] with [SourceActivation.Rejected.Exhausted]
     * + invokes [onTerminalExhaustion] exactly once.
     *
     * **D4-B M3**: readiness completes on the first frame regardless of the
     * post-refresh status verdict — the status authority is consulted by the
     * coordinator at commit, not by the collector.
     *
     * Gap-dirty: idempotent per generation — [emitGapOnce] emits
     * [ControllerEffect.CancelSse] once per outage (reset by a successful
     * frame). Repeated failures in the same outage do not emit duplicates.
     *
     * Intentional cancellation (the job is cancelled by a newer connect /
     * disconnect / scope shutdown / transport-timeout) + stale-identity
     * termination do NOT emit gap + do NOT start recovery + do NOT invoke
     * [onTerminalExhaustion].
     */
    private fun CoroutineScope.launchSseCollector(
        identity: ConnectionIdentity,
        generation: Long,
        readiness: CompletableDeferred<SourceActivation>,
    ): Job = launch {
        val workdirArg: String? = identity.normalizedWorkdir.ifBlank { null }
        // Number of additional service-level attempts consumed in the current
        // outage. A valid frame resets the budget for a later outage.
        var retriesUsed = 0
        while (true) {
            if (!isCurrentTransport(identity, generation)) return@launch
            // 1. Collect one flow instance. Try/catch routes BOTH thrown
            //    exceptions AND unexpected normal completion (an SSE flow
            //    should be infinite — a normal completion is a failure).
            var failure: Throwable? = null
            try {
                repository.connectSSE(workdirArg).collect { result ->
                    // §A3.7 — re-check identity BEFORE every side effect.
                    if (!isCurrentTransport(identity, generation)) {
                        DebugLog.i(
                            TAG,
                            "drop stale-identity SSE event " +
                                "(epoch=${identity.epoch} → current=${identityStore.currentEpoch()})"
                        )
                        throw CancellationException("stale SSE transport generation")
                    }
                    result.onSuccess { event ->
                        // §A3.8 — liveness + identified emit + first-frame
                        // transport readiness + gap reset (new outage can
                        // begin). D4-B M3: NO status refresh — readiness
                        // completes on transport proof alone.
                        onSuccessfulFrame(identity, generation, event, readiness)
                        retriesUsed = 0
                    }.onFailure { error ->
                        // §A3.9 — event-level failure.
                        Log_e(TAG, "SSE event failed", error)
                        sharedEffectBus.tryEmitUiEvent(
                            UiEvent.Error(
                                R.string.error_sse_failed,
                                listOf(error.message ?: "unknown error"),
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                // Cooperative cancellation (newer connect / disconnect /
                // transport-timeout / scope shutdown). Do NOT emit gap, do NOT
                // start recovery, do NOT invoke onTerminalExhaustion. Propagate.
                throw e
            } catch (e: Throwable) {
                failure = e
            }
            // 2. If we get here, the flow either threw OR completed normally.
            //    Either way, it's a collection-level failure (no infinite
            //    stream should complete). Stale-identity termination exits
            //    silently (no gap, no recovery, no exhaustion callback).
            if (!isCurrentTransport(identity, generation)) {
                return@launch
            }
            // D4-B M3: if transport readiness already completed (timeout or
            // otherwise) the collector is being torn down — exit silently.
            if (readiness.isCompleted) {
                return@launch
            }
            val failureThrowable = failure
                ?: java.io.IOException("SSE flow completed without an explicit error")
            onCollectionException(identity, generation, failureThrowable)
            // 3. After handling the failure: if budget exhausted, exit.
            if (retriesUsed >= recoveryPolicy.attempts) {
                if (isCurrentTransport(identity, generation) &&
                    exhaustedReportedForGen != generation
                ) {
                    exhaustedReportedForGen = generation
                    // Write the shared connection state to disconnected/degraded.
                    sharedStateStore.mutateConnection {
                        it.copy(
                            isConnected = false,
                            isConnecting = false,
                            connectionPhase = ConnectionPhase.Disconnected,
                        )
                    }
                    readiness.complete(SourceActivation.Rejected.Exhausted)
                    onTerminalExhaustion()
                }
                return@launch
            }
            // 4. Retry: delay per policy (with jitter) + loop.
            retriesUsed++
            val jitter = jitterSource()
            val delayMs = recoveryPolicy.delayMs(retriesUsed, jitter)
            DebugLog.i(TAG, "SSE retry attempt=$retriesUsed delay=${delayMs}ms (gen=$generation)")
            try {
                delay(delayMs)
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    /**
     * D4-B M3 — first-frame transport readiness + gap-reset side-effect
     * handler. Called for every successful current-identity frame. On the
     * FIRST such frame: writes liveness, publishes the event, completes
     * [readiness] with [SourceActivation.Ready], cancels the transport
     * timeout, and resets the gap flag. Does NOT perform a REST status
     * refresh — the status authority is the coordinator's concern at commit.
     *
     * Resetting [gapEmittedForGen] = -1 means the next failure starts a NEW
     * outage (a new gap emission). This is the §1.1 "observed transport-
     * disconnect signal" semantics: a gap is per-outage, not per-failure.
     */
    private suspend fun onSuccessfulFrame(
        identity: ConnectionIdentity,
        generation: Long,
        event: cn.vectory.ocdroid.data.model.SSEEvent,
        readiness: CompletableDeferred<SourceActivation>,
    ) {
        if (!isCurrentTransport(identity, generation)) return
        // SSE liveness: a successful event (initial connect OR retryWhen
        // recovery after a network outage) proves the server is reachable.
        sharedStateStore.mutateConnection {
            it.copy(
                isConnected = true,
                isConnecting = false,
                connectionPhase = ConnectionPhase.Connected,
            )
        }
        // Publish into the process-wide stream — the bridge (subscribed
        // eagerly from AppCore) routes through the §2 epoch guard + §11
        // dual-channel + re-emits each validated frame as OnSseEvent for
        // SSC's fold.
        sseEventStream.emit(Result.success(IdentifiedSseEvent(identity, event)))
        // D4-B M3: transport readiness completes on the first frame — NO
        // status refresh, NO baseline gating. Cancel the transport timeout
        // (the transport proved itself).
        if (!readiness.isCompleted) {
            transportTimeoutJob?.cancel()
            transportTimeoutJob = null
            readiness.complete(SourceActivation.Ready)
        }
        // Reset the per-generation gap flag — a new outage can begin.
        gapEmittedForGen = -1L
    }

    /**
     * D2 gate #7 — collection-level exception handler. Emits the SSE error
     * UI event + the IDEMPOTENT gap-dirty signal (once per outage per
     * generation). The retry / exhaustion decision is made by the caller
     * ([launchSseCollector]'s loop).
     */
    private suspend fun onCollectionException(
        identity: ConnectionIdentity,
        generation: Long,
        error: Throwable,
    ) {
        Log_e(TAG, "SSE collection failed (gen=$generation)", error)
        // Only surface the failure if THIS collector's identity is still
        // current — a stale collector (the reconfigure cancelled us
        // mid-flight) emits NEITHER errors NOR a disconnect transition.
        if (!isCurrentTransport(identity, generation)) {
            return
        }
        sharedEffectBus.tryEmitUiEvent(
            UiEvent.Error(
                R.string.error_sse_failed,
                listOf(error.message ?: "unknown error"),
            )
        )
        // D2 gate #7: IDEMPOTENT gap-dirty emission. Independent of
        // Job.isActive. Uses the suspend effect-emission path (not silently
        // dropped).
        emitGapOnce(identity, generation)
    }

    /**
     * D2 gate #7 — emits the §1.1 gap-dirty signal ([ControllerEffect.CancelSse])
     * IDEMPOTENTLY per outage (per generation). Repeated failures in the same
     * outage (same generation, no successful frame between them) do NOT emit
     * duplicates.
     *
     * Per-generation idempotence: [gapEmittedForGen] tracks which generation
     * has already been gap-emitted; a successful frame resets it (`-1L`) so
     * a NEW outage (later failure in the same generation) emits a fresh gap.
     */
    private suspend fun emitGapOnce(identity: ConnectionIdentity, generation: Long) {
        // Stale-identity: do NOT emit gap (the §4.4 teardown of a stale
        // identity is not a transport disconnect).
        if (!isCurrentTransport(identity, generation)) return
        if (gapEmittedForGen == generation) return
        gapEmittedForGen = generation
        sharedEffectBus.emitEffect(ControllerEffect.CancelSse)
    }

    /**
     * D2 gate #7 — cancels the in-flight collector + invokes the SAME
     * idempotent [emitGapOnce] path the terminal-collection-exception branch
     * uses (when [markGap] is true). This closes the "job.isActive as the gap
     * predicate" bug: the gap signal is now independent of whether the job
     * was live at disconnect time.
     *
     * Idempotent: a second call with no live collector emits nothing (the
     * generation tracking prevents duplicate gap emissions across repeated
     * disconnect calls in the same outage).
     *
     * Cancels [pendingReadiness] so a suspended [connect] await throws
     * CancellationException (the controller's launch dies without acking).
     */
    suspend fun disconnect(markGap: Boolean = true) {
        val job = connectMutex.withLock {
            val job = sseJob
            sseJob = null
            transportTimeoutJob?.cancel()
            transportTimeoutJob = null
            val readiness = pendingReadiness
            pendingReadiness = null
            val generation = transportGenerationCounter
            job?.cancel()
            readiness?.cancel()
            val identity = activeIdentity
            if (markGap && identity != null) {
                emitGapOnce(identity = identity, generation = generation)
            }
            transportGenerationCounter += 1
            activeIdentity = null
            job
        }
        job?.cancelAndJoin()
    }

    suspend fun disconnectAndJoin(markGap: Boolean = true) = disconnect(markGap)

    /** Synchronous Service-destruction fallback; normal L3 teardown already joined. */
    fun cancelForShutdown() {
        transportGenerationCounter += 1
        sseJob?.cancel()
        sseJob = null
        transportTimeoutJob?.cancel()
        transportTimeoutJob = null
        pendingReadiness?.cancel()
        pendingReadiness = null
        activeIdentity = null
    }

    private fun isCurrentTransport(identity: ConnectionIdentity, generation: Long): Boolean =
        generation == transportGenerationCounter && identityStore.isCurrent(identity)

    private fun isCurrentTransport(generation: Long): Boolean =
        generation == transportGenerationCounter

    private companion object {
        private const val TAG = "ServiceSseOwner"

        /**
         * D4-B M3: the bounded window (30s) for the first valid current-
         * identity SSE frame. If no frame arrives within this window the
         * activation fails with [SourceActivation.Rejected.TransportTimeout]
         * and the handoff routes through B1 rollback. Generous vs. a LAN
         * server's first-frame latency; tight enough that a dead endpoint
         * does not stall the bootstrap.
         */
        const val TRANSPORT_READY_TIMEOUT_MS = 30_000L

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
