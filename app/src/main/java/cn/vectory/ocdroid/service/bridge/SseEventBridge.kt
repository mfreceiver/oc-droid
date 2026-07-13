package cn.vectory.ocdroid.service.bridge

import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.di.UiApplicationScope
import cn.vectory.ocdroid.service.events.IdentifiedSseEvent
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 0 / dev-design P0.6 — the identity-checked SSE event bridge.
 *
 * Collects [SessionStreamingService.events] (FGS spec §1 «模型 A 收敛»),
 * performs the **first** strong [ConnectionIdentity] epoch validation, and
 * routes events onto two separate surfaces so that control-class events can
 * never be evicted by a `message.part.delta` flood (FGS spec §11 /
 * gpter-MAJOR#4).
 *
 * **Identity validation (FGS spec §2)**: every frame whose
 * [ConnectionIdentity.epoch] != the current process-level epoch is dropped
 * **before any side effect** — it belongs to a stale host / pre-reconfigure
 * collector. The bridge is the first of TWO checks: the
 * `ControllerEffect.OnSseEvent` still carries identity so
 * `SessionSyncCoordinator.fold` can validate again (gpter-MAJOR#2: identity
 * must not be stripped before the fold). The current epoch is supplied by the
 * service via [start]`s [currentEpoch] provider — the service owns the
 * identity (it bumped it during reconfigure).
 *
 * **§11 dual-channel design**:
 *  - [controlEvents] — `session.status` / `server.connected` / `permission.*`
 *    / `question.*`. Backed by a bounded [Channel] (capacity
 *    [CONTROL_CHANNEL_CAPACITY]) that **suspends the sender on full** (NOT
 *    `DROP_OLDEST`), preserves FIFO, and is drained/cancelled on [close].
 *    `server.connected` / host epoch / terminal states are never silently
 *    dropped or reordered.
 *  - [deltaEvents] — everything else (predominantly `message.part.delta` and
 *    other high-frequency render frames). Backed by a bounded [Channel]
 *    (capacity [DELTA_CHANNEL_CAPACITY]); on overflow the frame is dropped
 *    and the session is marked dirty ([dirtySessions]) for a later REST
 *    reconcile — §11 «overflow → dirty + forced reconcile». The sender is
 *    NOT suspended on delta overflow (that would back-pressure the SSE
 *    collector and stall control delivery behind it).
 *
 * The split guarantees that even when delta frames overflow, control events
 * continue to flow promptly — the collector routing is non-blocking on the
 * delta path ([Channel.trySend]) and suspending only on the control path
 * ([Channel.send], rare and must-not-drop).
 *
 * **Switch-over seam**: the bridge is constructed standalone in Lane C; the
 * service wires `start(service.events, ::currentEpoch)` at switch-over, and
 * the dispatcher routes [controlEvents] / [deltaEvents] into the existing
 * `AppCore.dispatchConnectionEffect` / `SessionSyncCoordinator.fold` paths
 * (dev-design §5 table row "SSE → fold 桥").
 */
@Singleton
class SseEventBridge @Inject constructor(
    @UiApplicationScope private val scope: CoroutineScope,
) {
    /**
     * §11 control channel — bounded, suspends on full (never DROP_OLDEST),
     * FIFO. Single consumer (the dispatcher). `server.connected` and
     * terminal `session.status` frames must survive any delta flood.
     */
    private val controlChannel: Channel<IdentifiedSseEvent> =
        Channel(capacity = CONTROL_CHANNEL_CAPACITY, onBufferOverflow = BufferOverflow.SUSPEND)
    val controlEvents: Flow<IdentifiedSseEvent> = controlChannel.receiveAsFlow()

    /**
     * §11 delta channel — bounded; on overflow the frame is dropped and the
     * session marked dirty (NOT silently lost without trace — a later REST
     * reconcile recovers). Uses [BufferOverflow.SUSPEND] so that
     * [Channel.trySend] returns failure when full (which the routing uses as
     * the overflow signal); the collector is NEVER blocked by the delta path
     * (it routes via [Channel.trySend], not [Channel.send], so a delta flood
     * cannot back-pressure the control path).
     */
    private val deltaChannel: Channel<IdentifiedSseEvent> =
        Channel(capacity = DELTA_CHANNEL_CAPACITY, onBufferOverflow = BufferOverflow.SUSPEND)
    val deltaEvents: Flow<IdentifiedSseEvent> = deltaChannel.receiveAsFlow()

    /**
     * Sessions that observed delta overflow since the last reconcile. The
     * dispatcher / `SessionSyncCoordinator` consumes this to trigger a
     * forced REST reconcile (§11). Switch-over seam.
     */
    private val _dirtySessions = MutableStateFlow<Set<String>>(emptySet())
    val dirtySessions: StateFlow<Set<String>> = _dirtySessions.asStateFlow()

    private var collectionJob: Job? = null
    private var droppedDeltaCount: Long = 0L

    /**
     * Begins collecting [events]. Idempotent — a second call while a
     * collector is running is a no-op (the service is the single producer).
     *
     * @param events the service's process-level SSE event surface
     *  (`SessionStreamingService.events`).
     * @param currentEpoch supplies the current process-level epoch; frames
     *  whose `identity.epoch != currentEpoch()` are dropped as stale-host
     *  (FGS spec §2). Read fresh per frame.
     */
    fun start(
        events: Flow<Result<IdentifiedSseEvent>>,
        currentEpoch: () -> Long,
    ) {
        if (collectionJob?.isActive == true) {
            DebugLog.i(TAG, "start: collector already active — ignoring")
            return
        }
        collectionJob = scope.launch {
            events.collect { result ->
                result
                    .onSuccess { identified -> routeIfFresh(identified, currentEpoch()) }
                    .onFailure { error ->
                        // Transport-level failures are surfaced by the service /
                        // repository (UiEvent.Error) — the bridge does not
                        // synthesize a notification for them.
                        DebugLog.w(TAG, "SSE transport failure on bridge: ${error.message}")
                    }
            }
        }
    }

    /**
     * §2 epoch guard + §11 routing. Drops stale-identity frames before any
     * side effect, then routes control vs. delta.
     */
    private suspend fun routeIfFresh(event: IdentifiedSseEvent, currentEpoch: Long) {
        if (event.identity.epoch != currentEpoch) {
            DebugLog.i(
                TAG,
                "drop stale-identity frame (epoch ${event.identity.epoch} != current $currentEpoch, " +
                    "type=${event.event.payload.type})",
            )
            return
        }
        if (isControlEvent(event.event)) {
            // §11: control path suspends on full (preserves, FIFO, no silent drop).
            controlChannel.send(event)
        } else {
            // §11: delta path never blocks the collector; overflow → dirty.
            val result = deltaChannel.trySend(event)
            if (result.isFailure) {
                markDeltaOverflow(event)
            }
        }
    }

    /**
     * §11 control class: `session.status`, `server.connected`,
     * `permission.*`, `question.*`. Everything else is a delta/render frame.
     */
    private fun isControlEvent(event: SSEEvent): Boolean {
        val type = event.payload.type
        return type == "session.status" ||
            type == "server.connected" ||
            type.startsWith(PERMISSION_PREFIX) ||
            type.startsWith(QUESTION_PREFIX)
    }

    /**
     * §11 overflow recovery: mark the session dirty (a later REST reconcile
     * recovers). Falls back to [SSEEvent.directory] when no `sessionID` is
     * present in the payload.
     */
    private fun markDeltaOverflow(event: IdentifiedSseEvent) {
        val key = event.event.payload.getString(SESSION_ID_KEY)
            ?: event.event.directory
            ?: UNKNOWN_SESSION_KEY
        _dirtySessions.value = _dirtySessions.value + key
        droppedDeltaCount += 1
        DebugLog.w(TAG, "delta channel overflow — session '$key' marked dirty (dropped total=$droppedDeltaCount)")
    }

    /**
     * Cancels the collector and closes both channels. Per §11 the channels
     * are explicitly drained (closing a Channel flushes its buffered elements
     * to the consumer's `receiveAsFlow` before completing) and then
     * cancelled. After [close], the bridge must not be reused — construct a
     * new instance (or, in the Hilt `@Singleton` case, the process is going
     * down with the service).
     */
    fun close() {
        collectionJob?.cancel()
        collectionJob = null
        controlChannel.close()
        deltaChannel.close()
    }

    companion object {
        private const val TAG = "SseEventBridge"

        /** §11 control channel capacity. */
        internal const val CONTROL_CHANNEL_CAPACITY = 64

        /** §11 delta channel capacity. */
        internal const val DELTA_CHANNEL_CAPACITY = 256

        private const val PERMISSION_PREFIX = "permission."
        private const val QUESTION_PREFIX = "question."
        private const val SESSION_ID_KEY = "sessionID"
        private const val UNKNOWN_SESSION_KEY = "__unknown__"
    }
}
