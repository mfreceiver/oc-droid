package cn.vectory.ocdroid.service.bridge

import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.service.events.IdentifiedSseEvent
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 / dev-design P0.6 — unit tests for [SseEventBridge].
 *
 * Covers the two highest-value §11 / §2 invariants:
 *  - **§2 stale-identity drop**: frames whose `identity.epoch != current` are
 *    dropped BEFORE any side effect (no routing to either channel).
 *  - **§11 control channel isolation**: control-class events
 *    (`session.status` / `server.connected` / `permission.*` / `question.*`)
 *    are routed to a separate bounded channel and are NOT evicted by a delta
 *    flood — the delta path is non-blocking (`trySend`), so the collector
 *    never stalls behind a delta backlog. Delta overflow marks the session
 *    dirty for a later REST reconcile.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SseEventBridgeTest {

    @Test
    fun `stale-identity frame is dropped before any side effect (section 2)`() = runTest {
        val bridge = SseEventBridge(backgroundScope)
        val stale = identifiedEvent(epoch = 99L, type = "session.status", sessionId = "s1")
        val fresh = identifiedEvent(epoch = CURRENT_EPOCH, type = "session.status", sessionId = "s2")
        val events: Flow<Result<IdentifiedSseEvent>> = flow {
            emit(Result.success(stale))
            emit(Result.success(fresh))
        }

        bridge.start(events) { CURRENT_EPOCH }

        val received = bridge.controlEvents.first()
        assertEquals("stale frame must be dropped — only the fresh frame arrives", fresh, received)
        assertEquals(CURRENT_EPOCH, received.identity.epoch)
    }

    @Test
    fun `stale-identity delta frame is also dropped (not routed to delta channel)`() = runTest {
        val bridge = SseEventBridge(backgroundScope)
        val staleDelta = identifiedEvent(epoch = 99L, type = "message.part.delta", sessionId = "s1")
        val freshDelta = identifiedEvent(epoch = CURRENT_EPOCH, type = "message.part.delta", sessionId = "s2")
        val events: Flow<Result<IdentifiedSseEvent>> = flow {
            emit(Result.success(staleDelta))
            emit(Result.success(freshDelta))
        }

        bridge.start(events) { CURRENT_EPOCH }

        val received = bridge.deltaEvents.first()
        assertEquals("stale delta dropped — fresh delta arrives", CURRENT_EPOCH, received.identity.epoch)
        assertNotEquals("stale session s1 must not be the delivered one", "s1", received.event.payload.getString("sessionID"))
    }

    @Test
    fun `control event survives delta flood and is routed to control channel (section 11)`() = runTest {
        val bridge = SseEventBridge(backgroundScope)
        // A delta flood well beyond DELTA_CHANNEL_CAPACITY — without §11
        // isolation the control event would be stuck behind the backlog.
        val floodSize = SseEventBridge.DELTA_CHANNEL_CAPACITY + 80
        val controlEvent = identifiedEvent(
            epoch = CURRENT_EPOCH,
            type = "session.status",
            sessionId = CONTROL_SESSION_ID,
        )
        val events: Flow<Result<IdentifiedSseEvent>> = flow {
            repeat(floodSize) { i ->
                emit(Result.success(deltaEvent(i)))
            }
            emit(Result.success(controlEvent))
        }

        val controlDeferred = async { bridge.controlEvents.first() }
        bridge.start(events) { CURRENT_EPOCH }

        val received = controlDeferred.await()
        assertEquals(
            "control event must survive the delta flood",
            "session.status",
            received.event.payload.type,
        )
        assertEquals(CONTROL_SESSION_ID, received.event.payload.getString("sessionID"))
    }

    @Test
    fun `delta overflow marks session dirty without blocking control path (section 11)`() = runTest {
        val bridge = SseEventBridge(backgroundScope)
        val floodSize = SseEventBridge.DELTA_CHANNEL_CAPACITY + 40
        val controlEvent = identifiedEvent(epoch = CURRENT_EPOCH, type = "server.connected")
        val events: Flow<Result<IdentifiedSseEvent>> = flow {
            repeat(floodSize) { i ->
                emit(Result.success(deltaEvent(i)))
            }
            emit(Result.success(controlEvent))
        }

        val controlDeferred = async { bridge.controlEvents.first() }
        bridge.start(events) { CURRENT_EPOCH }
        controlDeferred.await()

        assertTrue(
            "delta overflow must mark the session dirty for REST reconcile (got ${bridge.dirtySessions.value})",
            DELTA_SESSION_ID in bridge.dirtySessions.value,
        )
    }

    @Test
    fun `control events routed to control channel, deltas to delta channel (section 11 routing)`() = runTest {
        val bridge = SseEventBridge(backgroundScope)
        val controlFrame = identifiedEvent(epoch = CURRENT_EPOCH, type = "permission.asked", sessionId = "s1")
        val deltaFrame = identifiedEvent(epoch = CURRENT_EPOCH, type = "message.part.delta", sessionId = "s1")
        val events: Flow<Result<IdentifiedSseEvent>> = flow {
            emit(Result.success(deltaFrame))
            emit(Result.success(controlFrame))
            emit(Result.success(deltaFrame))
        }

        val controlDeferred = async { bridge.controlEvents.first() }
        val deltaDeferred = async { bridge.deltaEvents.first() }
        bridge.start(events) { CURRENT_EPOCH }

        assertEquals("permission.asked routed to control", "permission.asked", controlDeferred.await().event.payload.type)
        assertEquals("message.part.delta routed to delta", "message.part.delta", deltaDeferred.await().event.payload.type)
    }

    @Test
    fun `close cancels collection and stops routing further events (section 11)`() = runTest {
        val bridge = SseEventBridge(backgroundScope)
        val source = kotlinx.coroutines.flow.MutableSharedFlow<Result<IdentifiedSseEvent>>(
            extraBufferCapacity = 8,
        )

        val received = mutableListOf<IdentifiedSseEvent>()
        val collector = backgroundScope.launch {
            bridge.controlEvents.collect { received.add(it) }
        }

        bridge.start(source) { CURRENT_EPOCH }
        runCurrent()

        // A control frame before close is routed normally.
        source.tryEmit(Result.success(identifiedEvent(epoch = CURRENT_EPOCH, type = "session.status")))
        runCurrent()
        assertEquals(1, received.size)

        bridge.close()
        runCurrent()

        // After close the collector is cancelled — no further frames are routed.
        source.tryEmit(Result.success(identifiedEvent(epoch = CURRENT_EPOCH, type = "question.asked")))
        runCurrent()
        assertEquals("close must stop routing new frames", 1, received.size)

        collector.cancel()
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private companion object {
        const val CURRENT_EPOCH = 7L
        const val DELTA_SESSION_ID = "delta-session"
        const val CONTROL_SESSION_ID = "control-session"

        fun sseEvent(type: String, sessionId: String?): SSEEvent {
            val props: JsonObject? = sessionId?.let {
                JsonObject(mapOf("sessionID" to JsonPrimitive(it)))
            }
            return SSEEvent(payload = SSEPayload(type = type, properties = props))
        }

        fun identifiedEvent(epoch: Long, type: String, sessionId: String? = null): IdentifiedSseEvent =
            IdentifiedSseEvent(
                identity = ConnectionIdentity(
                    epoch = epoch,
                    serverGroupFp = "group-fp",
                    normalizedWorkdir = "/work/dir",
                    endpointFp = "endpoint-fp",
                ),
                event = sseEvent(type, sessionId),
            )

        /** A `message.part.delta`-shaped frame on the current epoch for [DELTA_SESSION_ID]. */
        fun deltaEvent(index: Int): IdentifiedSseEvent =
            identifiedEvent(epoch = CURRENT_EPOCH, type = "message.part.delta", sessionId = DELTA_SESSION_ID)
    }
}
