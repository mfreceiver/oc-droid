package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.SlimapiResyncReason
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.service.LeaseToken
import cn.vectory.ocdroid.service.StreamingOwnershipGate
import cn.vectory.ocdroid.service.events.IdentifiedSseEvent
import cn.vectory.ocdroid.service.events.SseEventStream
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Callback for unexpected transport drops. Implemented by
 * [ForegroundTransportDropHandler].
 */
interface UnexpectedTransportDropHandler {
    fun onUnexpectedDrop(attempt: TransportAttemptToken, reason: TransportDropReason)
}

/** Shared monitor/fence for owner supersession and unexpected-drop routing. */
internal interface FencedUnexpectedTransportDropHandler : UnexpectedTransportDropHandler {
    fun onUnexpectedDropIfCurrent(
        attempt: TransportAttemptToken,
        reason: TransportDropReason,
    ): Boolean
}

/**
 * L2: single-attempt SSE collector host.
 *
 * **CRITICAL**: [launchSseCollector] is a single-attempt body — NO `while(true)`
 * retry loop. Flow break / completion / throw = terminal for this attempt.
 * The controller's supervisor decides whether to retry (via a new [connect]
 * call), not this owner.
 *
 * **CRITICAL**: [onSuccessfulFrame] uses [SseTransportRuntimeStore.markLive]
 * as the canonical staleness gate. A false return suppresses ALL side effects
 * (event publish / sseConnected / resync / readiness).
 *
 * **CRITICAL**: [disconnectLocked] self-releases the lease via
 * [StreamingOwnershipGate.releaseNow] BEFORE marking the runtime Stopped
 * (Gate-first destroy order).
 *
 * L2 removals (vs D5-2 Owner):
 *  - Service-level retry loop (30s/2m/5m — dead; each attempt is single-shot)
 *  - [closingGeneration] / [markClosing] / [isClosing] — dead; no closing path
 *    races post-flow-break (no retry loop to guard).
 *  - [resyncMutex] / [resyncDirtyForGen] / [resyncInFlightForGen] /
 *    [isColdStartTrigger] / [scheduleResync] — replaced by 12-line
 *    [triggerResync].
 *  - [transportTimeoutJob] / [launchTransportTimeout] — dead; there is no
 *    transport-readiness timeout in L2 (the controller owns timeout decisions).
 *  - [beforeMarkRetrying] / [markRetrying] / [exhaustedReportedForGen] — dead.
 *  - [reconnectAllowed] / [SseRecoveryPolicy] / [jitterSource] — dead params.
 *  - [disconnectWithGuard] — dead; [disconnect] runs directly.
 *
 * @param ownershipGate the singleton ownership lease authority. Used by
 *   [disconnectLocked] / [cancelForShutdown] to release the lease (Gate-first
 *   destroy order).
 * @param onTerminalDrop invoked once when the single attempt ends in a
 *   terminal drop (unexpected transport failure after Live). Replaces
 *   [onTerminalExhaustion] — the "exhaustion" concept died with the retry loop.
 */
class ServiceSseConnectionOwner(
    private val scope: CoroutineScope,
    private val repository: OpenCodeRepository,
    private val identityStore: ConnectionIdentityStore,
    private val sseEventStream: SseEventStream,
    private val sharedStateStore: SharedStateStore,
    private val sharedEffectBus: SharedEffectBus,
    private val ownershipGate: StreamingOwnershipGate,
    private val runtimeStore: SseTransportRuntimeStore,
    private val dropHandler: UnexpectedTransportDropHandler,
    private val onTerminalDrop: () -> Unit,
    private val onResync: suspend (isStillCurrent: () -> Boolean) -> Unit = {},
) {
    // ── fields ────────────────────────────────────────────────────────

    private var sseJob: Job? = null
    private var activeIdentity: ConnectionIdentity? = null
    private var activeAttempt: TransportAttemptToken? = null
    private var pendingReadiness: CompletableDeferred<SourceActivation>? = null
    private var gapEmittedForGen: Long = -1L

    @Volatile
    private var transportGenerationCounter: Long = sharedStateStore.sseConnectedGeneration

    /**
     * §breathing-indicator: monotonic write-stamp preventing stale-collector
     * [setSseConnected] wins after supersession. Seeded from the @Singleton
     * store at construction so recreated owners continue the sequence.
     */
    private val sseWriteStamp = AtomicLong(sharedStateStore.sseConnectedGeneration)

    val isSseConnected: StateFlow<Boolean>
        get() = sharedStateStore.sseConnectedFlow

    private val connectMutex = Mutex()

    // ── public API ────────────────────────────────────────────────────

    /**
     * L2: launches ONE SSE collector bound to [identity] and awaits transport
     * readiness. Returns [SourceActivation.Ready] on the first valid current-
     * identity frame, or [SourceActivation.Rejected.Exhausted] on terminal
     * failure (drop routed). There is NO retry loop — a broken flow is final
     * for this attempt.
     */
    suspend fun connect(identity: ConnectionIdentity): SourceActivation {
        val setup = connectMutex.withLock { setupConnectLocked(identity) }
        return when (setup) {
            is ConnectSetup.Rejected -> setup.activation
            is ConnectSetup.Started -> try {
                setup.readiness.await()
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    /**
     * L2: disconnects the current attempt. Releases the lease (Gate-first),
     * marks the runtime Stopped, cancels the collector, and emits the gap
     * signal when [markGap] is true.
     *
     * Idempotent: a second call with no live collector is a safe no-op.
     */
    suspend fun disconnect(markGap: Boolean = true) {
        val job = connectMutex.withLock { disconnectLocked(markGap) }
        job?.cancelAndJoin()
    }

    /** Synchronous Service-destruction fallback. */
    fun cancelForShutdown() {
        val gen = transportGenerationCounter
        val attempt = activeAttempt
        val identity = activeIdentity
        if (attempt != null) {
            // Gate-first destroy order
            ownershipGate.releaseNow(LeaseToken(attempt.attemptId, attempt.identity))
            synchronized(dropHandler) { runtimeStore.markStopped(attempt) }
        }
        setSseConnected(false, gen + 1)
        transportGenerationCounter = gen + 1
        activeAttempt = null
        activeIdentity = null
        sseJob?.cancel()
        sseJob = null
        pendingReadiness?.cancel()
        pendingReadiness = null
    }

    // ── internal ──────────────────────────────────────────────────────

    private sealed interface ConnectSetup {
        data class Started(val readiness: CompletableDeferred<SourceActivation>) : ConnectSetup
        data class Rejected(val activation: SourceActivation) : ConnectSetup
    }

    private fun setupConnectLocked(identity: ConnectionIdentity): ConnectSetup {
        // Stale-identity rejection.
        if (!identityStore.isCurrent(identity)) {
            DebugLog.i(TAG, "connect: stale identity epoch=${identity.epoch}")
            return ConnectSetup.Rejected(SourceActivation.Rejected.StaleIdentity)
        }

        // Claim the lease.
        val lease = ownershipGate.claim(identity)
        if (lease == null) {
            DebugLog.w(TAG, "connect: lease claim rejected (different owner) epoch=${identity.epoch}")
            return ConnectSetup.Rejected(SourceActivation.Rejected.Exhausted)
        }

        // Terminalize the PRIOR runtime attempt.
        synchronized(dropHandler) {
            activeAttempt?.let { prev ->
                if (prev.identity == identity) runtimeStore.rollbackAttempt(prev)
                else runtimeStore.markStopped(prev)
            }
        }

        // Begin a fresh runtime attempt.
        val attempt = synchronized(dropHandler) { runtimeStore.beginAttempt(identity) }
        if (attempt == null) {
            ownershipGate.releaseNow(lease)
            DebugLog.w(TAG, "connect: runtime beginAttempt rejected epoch=${identity.epoch}")
            return ConnectSetup.Rejected(SourceActivation.Rejected.TransportTimeout)
        }

        // Re-seed generation counter if external purge advanced the store.
        if (transportGenerationCounter < sharedStateStore.sseConnectedGeneration) {
            transportGenerationCounter = sharedStateStore.sseConnectedGeneration
        }

        // Mark prior sseConnected false with a bumped generation stamp so
        // a stale prior-gen write loses the CAS.
        setSseConnected(false, transportGenerationCounter + 1)

        // Cancel prior collector + readiness.
        sseJob?.cancel()
        sseJob = null
        activeIdentity = identity
        pendingReadiness?.cancel()
        val readiness = CompletableDeferred<SourceActivation>()
        pendingReadiness = readiness

        // Bump generation, reset per-generation gap.
        val generation = ++transportGenerationCounter
        gapEmittedForGen = -1L
        activeAttempt = attempt

        // Launch one single-attempt collector.
        sseJob = scope.launchSseCollector(identity, generation, attempt, readiness)
        return ConnectSetup.Started(readiness)
    }

    /**
     * L2: single-attempt SSE collector. NO retry loop — the flow delivers
     * frames until it breaks/completes, then the failure is terminal.
     */
    private fun CoroutineScope.launchSseCollector(
        identity: ConnectionIdentity,
        generation: Long,
        attempt: TransportAttemptToken,
        readiness: CompletableDeferred<SourceActivation>,
    ): Job = launch {
        if (!isCurrentTransport(identity, generation)) return@launch

        val workdirArg: String? = identity.normalizedWorkdir.ifBlank { null }
        var failure: Throwable? = null

        try {
            repository.connectSSE(workdirArg).collect { result ->
                if (!isCurrentTransport(identity, generation)) {
                    throw CancellationException("stale SSE transport generation")
                }
                result.onSuccess { event ->
                    onSuccessfulFrame(identity, generation, attempt, event, readiness)
                }.onFailure { error ->
                    Log_e(TAG, "SSE event failed", error)
                    sharedEffectBus.tryEmitUiEvent(
                        UiEvent.Error(R.string.error_sse_failed, listOf(error.message ?: "unknown error"))
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure = e
        }

        // Flow break/completion — terminal for this attempt.
        if (!isCurrentTransport(identity, generation)) return@launch

        val error = failure ?: java.io.IOException("SSE flow completed without an explicit error")
        onCollectionException(identity, generation, attempt, error)
        // Re-check canonical AFTER onCollectionException: a concurrent path may
        // have terminalized the token within the same generation (e.g. another
        // thread called markStopped). The canonical check in onCollectionException
        // suppresses error/gap side effects for a stale token, but the drop
        // routing below runs unconditionally — re-check here so a stale token
        // does NOT route a drop for a superseding generation.
        val canonicalAfter = runtimeStore.currentAttempt(identity)
        if (canonicalAfter == null || canonicalAfter.attemptId != attempt.attemptId) return@launch
        // Route the drop exactly once.
        if (!routeUnexpectedDrop(attempt, TransportDropReason.RETRY_EXHAUSTED)) return@launch

        setSseConnected(false, generation)
        readiness.complete(SourceActivation.Rejected.Exhausted)
        onTerminalDrop()
    }

    private suspend fun onSuccessfulFrame(
        identity: ConnectionIdentity,
        generation: Long,
        attempt: TransportAttemptToken,
        event: cn.vectory.ocdroid.data.model.SSEEvent,
        readiness: CompletableDeferred<SourceActivation>,
    ) {
        // Generation guard (cheap early return for stale collector).
        if (!isCurrentTransport(identity, generation)) return

        // Canonical runtime validation (authoritative staleness gate).
        if (!runtimeStore.markLive(attempt)) {
            DebugLog.i(TAG, "markLive rejected stale token (gen=$generation) — frame suppressed")
            return
        }

        // §breathing-indicator: valid frame → SSE connected.
        setSseConnected(true, generation)

        // Publish into the process-wide stream.
        sseEventStream.emit(Result.success(IdentifiedSseEvent(identity, event)))

        // Handle resync (first-frame cold-start OR explicit resync frame).
        val type = event.payload.type
        val isResync = type == "resync"
        val isFirstFrameOfGen = !readiness.isCompleted
        val serverReasonRaw: String? =
            if (isResync) event.payload.getString("reason") else null
        if (isResync) {
            val serverReasonTyped: SlimapiResyncReason? =
                SlimapiResyncReason.fromRaw(serverReasonRaw)
            DebugLog.i(
                TAG,
                "slim resync reason: raw=$serverReasonRaw typed=$serverReasonTyped gen=$generation",
            )
        }
        if (isFirstFrameOfGen || isResync) {
            triggerResync(
                reason = if (isFirstFrameOfGen) "first-frame type=$type" else "explicit-resync type=$type",
                generation = generation,
                attempt = attempt,
            )
        }

        // Complete readiness on the first valid frame.
        if (!readiness.isCompleted) {
            readiness.complete(SourceActivation.Ready)
        }

        // Reset gap flag so a new outage can begin.
        gapEmittedForGen = -1L
    }

    private suspend fun onCollectionException(
        identity: ConnectionIdentity,
        generation: Long,
        attempt: TransportAttemptToken,
        error: Throwable,
    ) {
        Log_e(TAG, "SSE collection failed (gen=$generation)", error)
        if (!isCurrentTransport(identity, generation)) return

        // Canonical-token validation.
        val canonical = runtimeStore.currentAttempt(identity)
        if (canonical == null || canonical.attemptId != attempt.attemptId) {
            DebugLog.i(TAG, "onCollectionException: stale token (gen=$generation) — suppressed")
            return
        }

        // §breathing-indicator: transport gap.
        setSseConnected(false, generation)
        sharedEffectBus.tryEmitUiEvent(
            UiEvent.Error(R.string.error_sse_failed, listOf(error.message ?: "unknown error"))
        )
        emitGapOnce(identity, generation)
    }

    private suspend fun emitGapOnce(identity: ConnectionIdentity, generation: Long) {
        if (!isCurrentTransport(identity, generation)) return
        if (gapEmittedForGen == generation) return
        gapEmittedForGen = generation
        sharedEffectBus.emitEffect(ControllerEffect.CancelSse)
    }

    /**
     * L2: 12-line replacement for the entire [resyncMutex]/[resyncDirtyForGen]/
     * [resyncInFlightForGen]/[isColdStartTrigger]/[scheduleResync] cluster.
     *
     * Off-frame launch so SSE delivery is not blocked. The [isStillCurrent]
     * callback the [onResync] lambda receives queries
     * [SseTransportRuntimeStore.currentAttempt] for canonical-token freshness.
     */
    private fun triggerResync(reason: String, generation: Long, attempt: TransportAttemptToken) {
        scope.launch {
            if (!isCurrentTransport(generation)) return@launch
            DebugLog.i(TAG, "resync $reason gen=$generation")
            try {
                onResync {
                    runtimeStore.currentAttempt(attempt.identity)?.attemptId == attempt.attemptId
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                DebugLog.w(TAG, "resync failed: ${e.message}")
            }
        }
    }

    /**
     * L2: 7-line CAS helper. Seeded from [sharedStateStore.sseConnectedGeneration]
     * at construction so the monotonic write stamp survives service recreation.
     * A stale collector whose generation is ≤ the current stamp loses the CAS
     * and cannot resurrect `true` after teardown.
     */
    private fun setSseConnected(value: Boolean, generation: Long) {
        val prev = sseWriteStamp.get()
        if (generation < prev) return // stale write, will never win
        if (sseWriteStamp.compareAndSet(prev, generation)) {
            sharedStateStore.mutateSseConnected(value, generation)
        }
    }

    // ── disconnect / shutdown (Gate-first destroy order) ───────────────

    /**
     * L2: mutex-held disconnect body. Releases the lease via the Gate FIRST,
     * then marks the runtime Stopped (Gate-first destroy order).
     */
    private suspend fun disconnectLocked(markGap: Boolean): Job? {
        val gen = transportGenerationCounter
        val attempt = activeAttempt
        val identity = activeIdentity

        // Gate-first: release the lease.
        if (attempt != null) {
            ownershipGate.releaseNow(LeaseToken(attempt.attemptId, attempt.identity))
        }

        // Mark the runtime Stopped.
        synchronized(dropHandler) {
            attempt?.let { runtimeStore.markStopped(it) }
        }

        // §breathing-indicator: stamp the bumped generation so a stale
        // prior-gen write loses the CAS.
        setSseConnected(false, gen + 1)

        val job = sseJob
        sseJob = null
        val readiness = pendingReadiness
        pendingReadiness = null
        job?.cancel()
        readiness?.cancel()

        if (markGap && identity != null) {
            emitGapOnce(identity = identity, generation = gen)
        }
        transportGenerationCounter = gen + 1
        activeAttempt = null
        activeIdentity = null
        return job
    }

    private fun routeUnexpectedDrop(
        attempt: TransportAttemptToken,
        reason: TransportDropReason,
    ): Boolean = when (val handler = dropHandler) {
        is FencedUnexpectedTransportDropHandler ->
            handler.onUnexpectedDropIfCurrent(attempt, reason)
        else -> {
            handler.onUnexpectedDrop(attempt, reason)
            true
        }
    }

    private fun isCurrentTransport(identity: ConnectionIdentity, generation: Long): Boolean =
        generation == transportGenerationCounter && identityStore.isCurrent(identity)

    private fun isCurrentTransport(generation: Long): Boolean =
        generation == transportGenerationCounter

    private suspend fun Job.cancelAndJoin() {
        cancel()
        try { join() } catch (_: CancellationException) { /* joined */ }
    }

    private companion object {
        private const val TAG = "ServiceSseOwner"

        private fun Log_e(tag: String, msg: String, throwable: Throwable) {
            try {
                android.util.Log.e(tag, msg, throwable)
            } catch (_: Throwable) { /* unit tests */ }
        }
    }
}
