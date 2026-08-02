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
import kotlinx.coroutines.cancelAndJoin
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
 * @param ownershipGate the singleton ownership lease authority. Used by
 *   [disconnectLocked] / [cancelForShutdown] to release the lease (Gate-first
 *   destroy order).
 * @param onTerminalDrop invoked once when the single attempt ends in a
 *   terminal drop (unexpected transport failure after Live). Replaces
 *   onTerminalExhaustion.
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

    private val connectMutex = Mutex()
    private var sseJob: Job? = null                       // under connectMutex
    private var pendingReadiness: CompletableDeferred<SourceActivation>? = null  // under connectMutex
    private var activeIdentity: ConnectionIdentity? = null // under connectMutex
    private var activeAttempt: TransportAttemptToken? = null  // under connectMutex; also read by cancelForShutdown

    /**
     * §breathing-indicator: monotonic write-stamp preventing stale-collector
     * [setSseConnected] wins after supersession. Seeded from the @Singleton
     * store at construction so recreated owners continue the sequence.
     */
    private val sseWriteStamp = AtomicLong(sharedStateStore.sseConnectedGeneration)

    val isSseConnected: StateFlow<Boolean>
        get() = sharedStateStore.sseConnectedFlow

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
        if (!connectMutex.tryLock()) {
            // Another thread holds the lock — another disconnect is in
            // progress. Release the lease here (Gate-first) since we cannot
            // enter disconnectLocked.
            val attempt = activeAttempt
            if (attempt != null) {
                ownershipGate.releaseNow(LeaseToken(attempt.attemptId, attempt.identity))
                synchronized(dropHandler) { runtimeStore.markStopped(attempt) }
            }
            setSseConnected(false)
            activeAttempt = null
            activeIdentity = null
            sseJob?.cancel()
            sseJob = null
            pendingReadiness?.cancel()
            pendingReadiness = null
            return
        }
        try {
            setSseConnected(false)
            val job = sseJob; sseJob = null
            val readiness = pendingReadiness; pendingReadiness = null
            job?.cancel(); readiness?.cancel()
            // Gate-first destroy order.
            activeAttempt?.let { attempt ->
                ownershipGate.releaseNow(LeaseToken(attempt.attemptId, attempt.identity))
                synchronized(dropHandler) { runtimeStore.markStopped(attempt) }
            }
            activeAttempt = null
            activeIdentity = null
        } finally {
            connectMutex.unlock()
        }
    }

    // ── internal ──────────────────────────────────────────────────────

    private sealed interface ConnectSetup {
        data class Started(val readiness: CompletableDeferred<SourceActivation>) : ConnectSetup
        data class Rejected(val activation: SourceActivation) : ConnectSetup
    }

    private fun setupConnectLocked(identity: ConnectionIdentity): ConnectSetup {
        if (!identityStore.isCurrent(identity))
            return ConnectSetup.Rejected(SourceActivation.Rejected.StaleIdentity)

        // Store-first: terminalize OUR prior attempt, then mint the new one.
        val attempt = synchronized(dropHandler) {
            activeAttempt?.let { prev ->
                if (prev.identity == identity) runtimeStore.rollbackAttempt(prev)
                else runtimeStore.markStopped(prev)
            }
            runtimeStore.beginAttempt(identity)
        } ?: return ConnectSetup.Rejected(SourceActivation.Rejected.TransportTimeout)

        // Gate-second: claim with token-guarded teardown. Takeover handles the
        // same-identity reconnect atomically; rejection → compensate the store.
        val lease = ownershipGate.claim(attempt) { markGap ->
            disconnectWithGuard(markGap) { activeAttempt?.attemptId == attempt.attemptId }
        } ?: run {
            synchronized(dropHandler) { runtimeStore.rollbackAttempt(attempt) }
            return ConnectSetup.Rejected(SourceActivation.Rejected.Superseded)
        }

        // Cancel + supersede the prior collector.
        setSseConnected(false)
        sseJob?.cancel(); sseJob = null
        activeIdentity = identity
        pendingReadiness?.cancel()
        val readiness = CompletableDeferred<SourceActivation>()
        pendingReadiness = readiness
        activeAttempt = attempt
        sseJob = scope.launchSseCollector(identity, attempt, readiness)
        return ConnectSetup.Started(readiness)
    }

    /**
     * ABA-safe teardown callback: the guard (evaluated under connectMutex)
     * compares attempt IDs so a late [disconnectAndRelease]-extracted teardown
     * cannot kill a newer generation's collector.
     */
    private suspend fun disconnectWithGuard(markGap: Boolean, guard: () -> Boolean): Boolean {
        var ran = false
        val job = connectMutex.withLock {
            if (!guard()) return@withLock null
            ran = true
            disconnectLocked(markGap)
        }
        job?.cancelAndJoin()
        return ran
    }

    /**
     * L2: single-attempt SSE collector. NO retry loop — the flow delivers
     * frames until it breaks/completes, then the failure is terminal.
     */
    private fun CoroutineScope.launchSseCollector(
        identity: ConnectionIdentity,
        attempt: TransportAttemptToken,
        readiness: CompletableDeferred<SourceActivation>,
    ): Job = launch {
        val workdirArg: String? = identity.normalizedWorkdir.ifBlank { null }
        var failure: Throwable? = null

        try {
            repository.connectSSE(workdirArg).collect { result ->
                if (!identityStore.isCurrent(identity))
                    throw CancellationException("stale SSE identity")
                result.onSuccess { onSuccessfulFrame(identity, attempt, it, readiness) }
                    .onFailure { error ->
                        Log_e(TAG, "SSE event failed", error)
                        sharedEffectBus.tryEmitUiEvent(
                            UiEvent.Error(R.string.error_sse_failed, listOf(error.message ?: "unknown error")))
                    }
            }
        } catch (e: CancellationException) { throw e }
          catch (e: Throwable) { failure = e }

        // Flow broke/completed — terminal for THIS attempt (no retry).
        if (!identityStore.isCurrent(identity)) return@launch
        if (runtimeStore.currentAttempt(identity)?.attemptId != attempt.attemptId) return@launch // stale
        val canonicalAfter = runtimeStore.currentAttempt(identity)
        if (canonicalAfter == null || canonicalAfter.attemptId != attempt.attemptId) return@launch
        if (readiness.isCompleted) {
            // Post-Ready drop. Fenced exactly-once drop route.
            setSseConnected(false)
            if (routeUnexpectedDrop(attempt, TransportDropReason.RETRY_EXHAUSTED)) {
                sharedEffectBus.emitEffect(ControllerEffect.CancelSse)
                onTerminalDrop()
            }
        } else {
            // Pre-Ready failure: single attempt rejected, NO retry. Gate-first destroy order.
            setSseConnected(false)
            ownershipGate.releaseNow(LeaseToken(attempt.attemptId, attempt.identity))
            synchronized(dropHandler) { runtimeStore.rollbackAttempt(attempt) }
            readiness.complete(SourceActivation.Rejected.Exhausted)
        }
    }

    private suspend fun onSuccessfulFrame(
        identity: ConnectionIdentity,
        attempt: TransportAttemptToken,
        event: cn.vectory.ocdroid.data.model.SSEEvent,
        readiness: CompletableDeferred<SourceActivation>,
    ) {
        if (!identityStore.isCurrent(identity)) return
        if (!runtimeStore.markLive(attempt)) return   // stale → suppress ALL side effects

        setSseConnected(true)
        sseEventStream.emit(Result.success(IdentifiedSseEvent(identity, event)))

        val type = event.payload.type
        val isResync = type == "resync"
        val isFirstFrameOfGen = !readiness.isCompleted
        val serverReasonRaw: String? = if (isResync) event.payload.getString("reason") else null
        if (isResync) {
            val serverReasonTyped = SlimapiResyncReason.fromRaw(serverReasonRaw)
            DebugLog.i(TAG, "slim resync reason: raw=$serverReasonRaw typed=$serverReasonTyped")
        }
        if (isFirstFrameOfGen || isResync) {
            triggerResync(
                reason = if (isFirstFrameOfGen) "first-frame type=$type" else "explicit-resync type=$type",
                attempt = attempt,
            )
        }
        if (!readiness.isCompleted) readiness.complete(SourceActivation.Ready)
    }

    /**
     * L2: 12-line replacement for the entire resyncMutex / resyncDirtyForGen /
     * resyncInFlightForGen / isColdStartTrigger / scheduleResync cluster.
     *
     * Off-frame launch so SSE delivery is not blocked. The [isStillCurrent]
     * callback the [onResync] lambda receives queries
     * [SseTransportRuntimeStore.currentAttempt] for canonical-token freshness.
     */
    private fun triggerResync(reason: String, attempt: TransportAttemptToken) {
        scope.launch {
            if (runtimeStore.currentAttempt(attempt.identity)?.attemptId != attempt.attemptId) return@launch
            DebugLog.i(TAG, "resync $reason")
            try {
                onResync { runtimeStore.currentAttempt(attempt.identity)?.attemptId == attempt.attemptId }
            } catch (e: CancellationException) { throw e }
              catch (e: Throwable) { DebugLog.w(TAG, "resync failed: ${e.message}") }
        }
    }

    /**
     * L2: 7-line CAS helper. Seeded from [sharedStateStore.sseConnectedGeneration]
     * at construction so the monotonic write stamp survives service recreation.
     * A stale collector whose generation is ≤ the current stamp loses the CAS
     * and cannot resurrect `true` after teardown.
     */
    private fun setSseConnected(value: Boolean) {
        while (true) {
            val stamp = sseWriteStamp.updateAndGet {
                maxOf(it, sharedStateStore.sseConnectedGeneration) + 1
            }
            if (sharedStateStore.mutateSseConnected(value, stamp)) return
        }
    }

    // ── disconnect / shutdown (Gate-first destroy order) ───────────────

    /**
     * L2: mutex-held disconnect body. Releases the lease via the Gate FIRST,
     * then marks the runtime Stopped (Gate-first destroy order).
     */
    private suspend fun disconnectLocked(markGap: Boolean): Job? {
        setSseConnected(false)
        val job = sseJob; sseJob = null
        val readiness = pendingReadiness; pendingReadiness = null
        job?.cancel(); readiness?.cancel()
        val identity = activeIdentity
        // Gate-first: release lease, then terminalize the store attempt.
        activeAttempt?.let { attempt ->
            ownershipGate.releaseNow(LeaseToken(attempt.attemptId, attempt.identity))
            synchronized(dropHandler) { runtimeStore.markStopped(attempt) }
        }
        if (markGap && identity != null) sharedEffectBus.emitEffect(ControllerEffect.CancelSse)
        activeAttempt = null; activeIdentity = null
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

    private companion object {
        private const val TAG = "ServiceSseOwner"

        private fun Log_e(tag: String, msg: String, throwable: Throwable) {
            try {
                android.util.Log.e(tag, msg, throwable)
            } catch (_: Throwable) { /* unit tests */ }
        }
    }
}
