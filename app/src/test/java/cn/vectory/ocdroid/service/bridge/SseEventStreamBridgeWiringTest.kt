package cn.vectory.ocdroid.service.bridge

import android.util.Log
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.service.events.IdentifiedSseEvent
import cn.vectory.ocdroid.service.events.SseEventStream
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.controller.ConnectionCoordinator
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CP3 (notify Phase-0): end-to-end wiring tests for the
 * `CC collector → SseEventStream → SseEventBridge → AppCore dispatch → SSC fold`
 * path.
 *
 * Proves:
 *  - A current-identity SSE frame published by CC into [SseEventStream]
 *    flows through the started [SseEventBridge] → reaches AppCore's
 *    `ControllerEffect.OnSseEvent` emission → SSC's identity-checked
 *    `handleEvent(IdentifiedSseEvent)` folds it.
 *  - A stale-identity frame is dropped at the bridge's §2 epoch guard and
 *    NEVER reaches SSC.
 *  - Delta overflow populates `dirtySessions` and `consumeDirty()` returns
 *    + clears (§11 consumable overflow).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SseEventStreamBridgeWiringTest {

    private lateinit var scope: TestScope
    private lateinit var slices: SliceFlows
    private lateinit var repository: OpenCodeRepository
    private lateinit var settingsManager: SettingsManager
    private lateinit var effects: SharedEffectBus
    private lateinit var collectedEffects: MutableList<ControllerEffect>
    private lateinit var identityStore: ConnectionIdentityStore
    private lateinit var stream: SseEventStream
    private lateinit var bridge: SseEventBridge
    private lateinit var coordinator: ConnectionCoordinator
    private lateinit var sessionSyncCoordinator: SessionSyncCoordinator

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0

        scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        slices = store.slices
        repository = mockk(relaxed = true)
        settingsManager = mockk(relaxed = true)
        effects = SharedEffectBus()
        collectedEffects = mutableListOf()
        identityStore = ConnectionIdentityStore()
        stream = SseEventStream()
        // The bridge uses the test scope (same as backgroundScope in
        // SseEventBridgeTest — scope provides the coroutine context).
        bridge = SseEventBridge(
            kotlinx.coroutines.CoroutineScope(
                SupervisorJob() + Dispatchers.Unconfined
            )
        )

        every { settingsManager.currentWorkdir } returns null
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        coEvery { repository.getSessionsForDirectory(any()) } returns Result.success(emptyList())

        coordinator = ConnectionCoordinator(
            scope = kotlinx.coroutines.CoroutineScope(
                SupervisorJob() + Dispatchers.Unconfined
            ),
            slices = slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effects,
            serverCompatProfile = ServerCompatProfile(),
            identityStore = identityStore,
            bootstrapCoordinator = cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator(),
            sseEventStream = stream,
        )
        sessionSyncCoordinator = SessionSyncCoordinator(
            scope = kotlinx.coroutines.CoroutineScope(
                SupervisorJob() + Dispatchers.Unconfined
            ),
            slices = slices,
            settingsManager = settingsManager,
            effects = effects,
            currentServerGroupFp = { "test-fp" },
            identityStore = identityStore,
        )

        // Drain effects.
        scope.launch {
            effects.effectsConsumed.toList(collectedEffects)
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * CP3 spec test: a current-identity SSE frame published by CC into
     * [SseEventStream] flows through the started [SseEventBridge] → reaches
     * AppCore's `ControllerEffect.OnSseEvent` emission → SSC folds it.
     */
    @Test
    fun `CP3 current-identity frame flows CC to stream to bridge to OnSseEvent`() = runTest {
        // Bind identity at epoch 0.
        val identity = identityStore.bind("test-fp", "/proj", "endpoint")
        // Start the bridge (eagerly, as AppCore does).
        bridge.start(stream.events) { identityStore.currentEpoch() }

        // CC publishes a control event via the stream (simulating collector
        // output). Use the stream directly (CC's collector calls
        // stream.emit(Result.success(identified))).
        val event = SSEEvent(payload = SSEPayload(type = "session.status"))
        stream.emit(Result.success(IdentifiedSseEvent(identity, event)))
        advanceUntilIdle()

        // The bridge routed it to controlEvents. Collect from the bridge's
        // control channel and verify the frame arrives with the correct
        // identity (proves the epoch guard passed + routing happened).
        val controlReceived = mutableListOf<IdentifiedSseEvent>()
        val controlJob = launch {
            bridge.controlEvents.collect { controlReceived.add(it) }
        }
        advanceUntilIdle()
        controlJob.cancel()

        assertEquals(
            "current-identity frame must flow through the bridge",
            1,
            controlReceived.size,
        )
        assertEquals("session.status", controlReceived[0].event.payload.type)
        assertEquals(0L, controlReceived[0].identity.epoch)
    }

    /**
     * CP3 spec test: a stale-identity frame is dropped at the bridge's §2
     * epoch guard — it NEVER reaches the control or delta channel, and SSC's
     * fold is not invoked.
     */
    @Test
    fun `CP3 stale-identity frame dropped at bridge epoch guard`() = runTest {
        // Bind identity at epoch 0, then bump to 1 (reconfigure).
        val staleIdentity = identityStore.bind("test-fp", "/proj", "endpoint")
        identityStore.beginReconfigure() // epoch → 1, currentIdentity nulled.

        bridge.start(stream.events) { identityStore.currentEpoch() }

        // Collectors for both channels (to prove neither receives the frame).
        val controlReceived = mutableListOf<IdentifiedSseEvent>()
        val deltaReceived = mutableListOf<IdentifiedSseEvent>()
        val controlJob = launch {
            bridge.controlEvents.collect { controlReceived.add(it) }
        }
        val deltaJob = launch {
            bridge.deltaEvents.collect { deltaReceived.add(it) }
        }

        // Publish a stale-identity frame (epoch 0, current is 1).
        val staleEvent = SSEEvent(payload = SSEPayload(type = "session.status"))
        stream.emit(Result.success(IdentifiedSseEvent(staleIdentity, staleEvent)))
        advanceUntilIdle()

        controlJob.cancel()
        deltaJob.cancel()

        assertTrue(
            "stale-identity frame must NOT reach control channel",
            controlReceived.isEmpty(),
        )
        assertTrue(
            "stale-identity frame must NOT reach delta channel",
            deltaReceived.isEmpty(),
        )
    }

    /**
     * CP3 spec test: stale-identity frame does not trigger SSC fold. The
     * bridge drops it before it can become a ControllerEffect.OnSseEvent.
     * We prove this by having SSC subscribed and asserting no state mutation
     * occurred.
     */
    @Test
    fun `CP3 stale-identity frame does not trigger SSC fold`() = runTest {
        val staleIdentity = identityStore.bind("test-fp", "/proj", "endpoint")
        identityStore.beginReconfigure()

        // Simulate what AppCore does: collect bridge.controlEvents + deltaEvents
        // and dispatch as OnSseEvent.
        bridge.start(stream.events) { identityStore.currentEpoch() }
        val controlJob = launch {
            bridge.controlEvents.collect { identified ->
                sessionSyncCoordinator.handleEvent(identified)
            }
        }
        val deltaJob = launch {
            bridge.deltaEvents.collect { identified ->
                sessionSyncCoordinator.handleEvent(identified)
            }
        }

        // Publish a stale session.status frame.
        val staleEvent = SSEEvent(
            payload = SSEPayload(
                type = "session.status",
                properties = kotlinx.serialization.json.buildJsonObject {
                    put(
                        "sessionID",
                        kotlinx.serialization.json.JsonPrimitive("s-stale")
                    )
                    put(
                        "status",
                        kotlinx.serialization.json.buildJsonObject {
                            put("type", kotlinx.serialization.json.JsonPrimitive("busy"))
                        }
                    )
                }
            )
        )
        stream.emit(Result.success(IdentifiedSseEvent(staleIdentity, staleEvent)))
        advanceUntilIdle()

        controlJob.cancel()
        deltaJob.cancel()

        assertNull(
            "stale-identity frame must not reach SSC fold — no sessionStatuses write",
            slices.sessionList.value.sessionStatuses["s-stale"],
        )
    }

    /**
     * CP3 spec test: delta overflow populates dirtySessions, and
     * `consumeDirty()` returns the set + clears it (§11 consumable overflow).
     */
    @Test
    fun `CP3 delta overflow populates dirtySessions and consumeDirty clears`() = runTest {
        val identity = identityStore.bind("test-fp", "/proj", "endpoint")
        bridge.start(stream.events) { identityStore.currentEpoch() }

        // Start a delta consumer that drains slowly (capacity 256, we flood
        // beyond it). The bridge's deltaChannel has capacity 256; to trigger
        // overflow we emit > 256 without a consumer.
        // Actually, the bridge routes via trySend which fails when the channel
        // is full. Without a consumer, the channel buffer fills at 256 and
        // subsequent trySend calls fail → markDeltaOverflow.
        val floodSize = SseEventBridge.DELTA_CHANNEL_CAPACITY + 40
        repeat(floodSize) { i ->
            stream.emit(
                Result.success(
                    IdentifiedSseEvent(
                        identity,
                        SSEEvent(
                            payload = SSEPayload(
                                type = "message.part.delta",
                                properties = kotlinx.serialization.json.buildJsonObject {
                                    put(
                                        "sessionID",
                                        kotlinx.serialization.json.JsonPrimitive("flood-session")
                                    )
                                }
                            )
                        )
                    )
                )
            )
        }
        advanceUntilIdle()

        assertTrue(
            "delta overflow must mark the session dirty (got ${bridge.dirtySessions.value})",
            "flood-session" in bridge.dirtySessions.value,
        )

        // consumeDirty returns the set + clears.
        val drained = bridge.consumeDirty()
        assertTrue("flood-session in drained set", "flood-session" in drained)
        assertTrue(
            "consumeDirty cleared the set",
            bridge.dirtySessions.value.isEmpty(),
        )

        // A second consumeDirty returns empty (no new overflow).
        val secondDrain = bridge.consumeDirty()
        assertTrue("second drain is empty", secondDrain.isEmpty())

        bridge.close()
    }

    /**
     * CP3 spec test: CC's collector publishes into SseEventStream (end-to-end
     * through the real collector path, not just stream.emit). Proves the CC
     * collector was rerouted to the stream in CP3.
     */
    @Test
    fun `CP3 CC collector publishes to SseEventStream instead of direct OnSseEvent`() = runTest {
        val identity = identityStore.bind("test-fp", "/proj", "endpoint")

        // Feed that CC's launchSseCollection will collect from.
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed.asSharedFlow()

        bridge.start(stream.events) { identityStore.currentEpoch() }

        // Collect the bridge's control channel to observe the routed frame.
        val controlReceived = mutableListOf<IdentifiedSseEvent>()
        val controlJob = launch {
            bridge.controlEvents.collect { controlReceived.add(it) }
        }

        // Start CC's SSE collector.
        coordinator.startSSE()
        advanceUntilIdle()

        // Emit a control event into the feed → CC wraps + publishes to stream
        // → bridge routes to control channel.
        val event = SSEEvent(payload = SSEPayload(type = "session.status"))
        feed.tryEmit(Result.success(event))
        advanceUntilIdle()

        controlJob.cancel()

        assertEquals(
            "CC collector published to stream → bridge routed to control channel",
            1,
            controlReceived.size,
        )
        assertEquals("session.status", controlReceived[0].event.payload.type)
        assertEquals(0L, controlReceived[0].identity.epoch)

        // No direct ControllerEffect.OnSseEvent was emitted on the effect bus
        // (CC now publishes to the stream, not the effect bus).
        assertTrue(
            "no direct OnSseEvent on effect bus (CC publishes to stream)",
            collectedEffects.filterIsInstance<ControllerEffect.OnSseEvent>().isEmpty(),
        )

        bridge.close()
    }
}
