package cn.vectory.ocdroid.service.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-level SSE event surface (FGS spec §1 / §11).
 *
 * CP3 (notify Phase-0): the single process-wide conduit between the SSE
 * collector (today: `ConnectionCoordinator.launchSseCollection`; CP5+:
 * `SessionStreamingService.connectSse`) and the
 * [cn.vectory.ocdroid.service.bridge.SseEventBridge] which routes events
 * into the §11 control/delta dual-channel + identity validation →
 * `SessionSyncCoordinator.fold`.
 *
 * **Non-lossy**: backed by a [MutableSharedFlow] with `replay = 0` (no
 * replay for late subscribers — the bridge is subscribed eagerly at AppCore
 * init) and `extraBufferCapacity = 256` with [BufferOverflow.SUSPEND]
 * (the producer suspends when the buffer is full, NEVER silently drops).
 * The buffer capacity is consistent with the bridge's delta-channel capacity
 * ([cn.vectory.ocdroid.service.bridge.SseEventBridge.DELTA_CHANNEL_CAPACITY])
 * so the stream + the bridge's internal channels compose without premature
 * loss. A slow bridge consumer back-pressures the producer (CC's collector
 * suspends on `emit`), which is the desired §11 behavior for the SSE path
 * — it is preferable to stall SSE collection than to silently drop frames
 * that may be deltas (non-idempotent appends).
 *
 * `@Singleton` + `@Inject constructor` — Hilt auto-provides (concrete class,
 * no `@Binds`/`@Provides` module needed).
 *
 * **Switch-over seam (CP3 → CP5)**: today CC is the sole producer; when
 * the SSE ownership migrates to `SessionStreamingService` (CP9), the Service
 * simply calls [emit] (or the Service delegates its `events` field to
 * [events]) — the bridge and downstream fold path stay unchanged.
 */
@Singleton
class SseEventStream @Inject constructor() {

    private val _events: MutableSharedFlow<Result<IdentifiedSseEvent>> =
        MutableSharedFlow(
            replay = 0,
            extraBufferCapacity = STREAM_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )

    /**
     * The process-wide SSE event surface. Subscribed to by
     * [cn.vectory.ocdroid.service.bridge.SseEventBridge.start] (eagerly, from
     * AppCore init). Also surfaced as `SessionStreamingService.events` so
     * external observers (the future notification layer) can tap the same
     * stream.
     */
    val events: SharedFlow<Result<IdentifiedSseEvent>> = _events.asSharedFlow()

    /**
     * Publishes [result] into the stream. Suspends if the buffer is full
     * ([BufferOverflow.SUSPEND] — non-lossy). Called by the SSE collector
     * (CC today, SessionStreamingService in CP5+).
     *
     * Each [IdentifiedSseEvent] MUST carry the capture-time
     * [cn.vectory.ocdroid.service.identity.ConnectionIdentity] (CP1) so the
     * bridge + SSC can validate epoch BEFORE any side effect.
     */
    suspend fun emit(result: Result<IdentifiedSseEvent>) {
        _events.emit(result)
    }

    /**
     * Non-suspending emit. Returns `true` if the frame was buffered, `false`
     * if the buffer is full (caller decides whether to drop or back-pressure).
     * Kept for the future Service producer + tests that need a fire-and-forget
     * path; CC's collector uses the suspend [emit] (non-lossy).
     */
    fun tryEmit(result: Result<IdentifiedSseEvent>): Boolean = _events.tryEmit(result)

    companion object {
        /**
         * Buffer capacity — consistent with the bridge's delta-channel
         * capacity ([cn.vectory.ocdroid.service.bridge.SseEventBridge.DELTA_CHANNEL_CAPACITY]).
         */
        internal const val STREAM_BUFFER_CAPACITY = 256
    }
}
