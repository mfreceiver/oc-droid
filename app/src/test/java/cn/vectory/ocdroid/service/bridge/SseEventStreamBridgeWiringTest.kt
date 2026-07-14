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
import cn.vectory.ocdroid.service.streaming.ServiceSseConnectionOwner
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.SliceFlows
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CP3 (notify Phase-0) + CP9 switchover: end-to-end wiring tests for the
 * `Service-owned collector → SseEventStream → SseEventBridge → AppCore
 * dispatch → SSC fold` path.
 *
 * Proves:
 *  - A current-identity SSE frame published by [ServiceSseConnectionOwner]
 *    into [SseEventStream] flows through the started [SseEventBridge] →
 *    reaches AppCore's `ControllerEffect.OnSseEvent` emission → SSC's
 *    identity-checked `handleEvent(IdentifiedSseEvent)` folds it.
 *  - A stale-identity frame is dropped at the bridge's §2 epoch guard and
 *    NEVER reaches SSC.
 *  - Delta overflow populates `dirtySessions` and `consumeDirty()` returns
 *    + clears (§11 consumable overflow).
 *  - CP9: the collector producer IS [ServiceSseConnectionOwner] (replaces
 *    the previous ConnectionCoordinator.launchSseCollection).
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
    private lateinit var sseOwner: ServiceSseConnectionOwner
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

        // CP9: the collector is now ServiceSseConnectionOwner (replaces CC's
        // launchSseCollection). Wire it with the same collaborators the
        // Service injects in production. D2: the owner now needs the status
        // aggregator + input + snapshot provider + recovery policy for the
        // acknowledgeable-readiness contract.
        sseOwner = ServiceSseConnectionOwner(
            scope = kotlinx.coroutines.CoroutineScope(
                SupervisorJob() + Dispatchers.Unconfined
            ),
            repository = repository,
            identityStore = identityStore,
            bootstrapCoordinator = cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator(),
            sseEventStream = stream,
            sharedStateStore = store,
            sharedEffectBus = effects,
            recoveryPolicy = cn.vectory.ocdroid.service.streaming.SseRecoveryPolicy(),
            onTerminalExhaustion = {},
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
     * CP3 spec test: a current-identity SSE frame published into
     * [SseEventStream] flows through the started [SseEventBridge] → reaches
     * AppCore's `ControllerEffect.OnSseEvent` emission → SSC folds it.
     */
    @Test
    fun `CP3 current-identity frame flows CC to stream to bridge to OnSseEvent`() = runTest {
        // Bind identity at epoch 0.
        val identity = identityStore.bind("test-fp", "/proj", "endpoint")
        // Start the bridge (eagerly, as AppCore does).
        bridge.start(stream.events) { identityStore.currentEpoch() }

        // Publish a control event via the stream directly (the collector calls
        // stream.emit(Result.success(identified))).
        val event = SSEEvent(payload = SSEPayload(type = "session.status"))
        stream.emit(Result.success(IdentifiedSseEvent(identity, event)))
        advanceUntilIdle()

        // The bridge routed it to controlEvents.
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

        assertTrue(
            "stale-identity frame must not reach SSC fold — no sessionStatuses write",
            !slices.sessionList.value.sessionStatuses.containsKey("s-stale"),
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
     * CP9 spec test (replaces the CP3 CC-collector test): the
     * [ServiceSseConnectionOwner] publishes into SseEventStream end-to-end
     * (through the real collector path, not just stream.emit). Proves the
     * switchover relocated the producer from CC to the Service-owned owner
     * without disturbing the bridge wiring.
     */
    @Test
    fun `CP9 ServiceSseConnectionOwner publishes to SseEventStream and reaches bridge`() = runTest {
        val identity = identityStore.bind("test-fp", "/proj", "endpoint")

        // Feed that sseOwner.connect will collect from.
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed.asSharedFlow()

        bridge.start(stream.events) { identityStore.currentEpoch() }

        // Collect the bridge's control channel to observe the routed frame.
        val controlReceived = mutableListOf<IdentifiedSseEvent>()
        val controlJob = launch {
            bridge.controlEvents.collect { controlReceived.add(it) }
        }

        // Start the Service-owned collector (D2: connect is suspend → launch).
        launch { sseOwner.connect(identity) }
        advanceUntilIdle()

        // Emit a control event into the feed → sseOwner wraps + publishes to
        // stream → bridge routes to control channel.
        val event = SSEEvent(payload = SSEPayload(type = "session.status"))
        feed.tryEmit(Result.success(event))
        advanceUntilIdle()

        controlJob.cancel()

        assertEquals(
            "ServiceSseConnectionOwner published to stream → bridge routed to control channel",
            1,
            controlReceived.size,
        )
        assertEquals("session.status", controlReceived[0].event.payload.type)
        assertEquals(0L, controlReceived[0].identity.epoch)

        // No direct ControllerEffect.OnSseEvent was emitted on the effect bus
        // (the owner publishes to the stream, not the effect bus).
        assertTrue(
            "no direct OnSseEvent on effect bus (owner publishes to stream)",
            collectedEffects.filterIsInstance<ControllerEffect.OnSseEvent>().isEmpty(),
        )

        bridge.close()
    }
}
/**
 * Minimal [cn.vectory.ocdroid.service.status.StatusAggregatorInput] fake for
 * the bridge wiring test: refresh is a no-op (the bridge test does not
 * exercise readiness verification). Kept at file scope so the test class
 * stays focused on the bridge contract.
 */
private class BridgeFakeStatusInput : cn.vectory.ocdroid.service.status.StatusAggregatorInput {
    override suspend fun refresh(
        identity: cn.vectory.ocdroid.service.identity.ConnectionIdentity,
        snapshot: cn.vectory.ocdroid.service.status.StatusSnapshot,
    ) = Unit
    override fun applySseStatus(
        key: cn.vectory.ocdroid.service.status.SessionStatusKey,
        status: cn.vectory.ocdroid.service.status.SessionBusyStatus,
        sourceTimeMs: Long,
    ) = Unit
    override fun markRequestFailed(
        identity: cn.vectory.ocdroid.service.identity.ConnectionIdentity,
        snapshot: cn.vectory.ocdroid.service.status.StatusSnapshot,
        sourceTimeMs: Long,
    ) = Unit
}

/**
 * Minimal [cn.vectory.ocdroid.service.status.StatusAggregator] fake: reports
 * [cn.vectory.ocdroid.service.status.GlobalBusyState.AllIdleFresh] so the
 * owner's first-frame readiness completes immediately (the bridge test does
 * not exercise state-dependent readiness).
 */
private class BridgeFakeStatusAggregator : cn.vectory.ocdroid.service.status.StatusAggregator {
    private val _globalState = kotlinx.coroutines.flow.MutableStateFlow(
        cn.vectory.ocdroid.service.status.GlobalBusyState.AllIdleFresh
    )
    private val _globalBusy = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val _statusByKey: kotlinx.coroutines.flow.MutableStateFlow<
        Map<cn.vectory.ocdroid.service.status.SessionStatusKey,
        cn.vectory.ocdroid.service.status.SessionBusyStatus>> =
        kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
    override val globalState:
        kotlinx.coroutines.flow.StateFlow<cn.vectory.ocdroid.service.status.GlobalBusyState> =
        _globalState.asStateFlow()
    override val globalBusy: kotlinx.coroutines.flow.StateFlow<Boolean> = _globalBusy.asStateFlow()
    override val statusByKey:
        kotlinx.coroutines.flow.StateFlow<Map<cn.vectory.ocdroid.service.status.SessionStatusKey, cn.vectory.ocdroid.service.status.SessionBusyStatus>> =
        _statusByKey.asStateFlow()
    override fun stateAtNow(): cn.vectory.ocdroid.service.status.GlobalBusyState =
        cn.vectory.ocdroid.service.status.GlobalBusyState.AllIdleFresh
}
