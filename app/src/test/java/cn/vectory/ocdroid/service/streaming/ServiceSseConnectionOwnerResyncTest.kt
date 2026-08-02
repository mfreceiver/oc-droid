package cn.vectory.ocdroid.service.streaming

import android.util.Log
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.service.events.SseEventStream
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.status.GlobalBusyState
import cn.vectory.ocdroid.service.status.SessionBusyStatus
import cn.vectory.ocdroid.service.status.SessionStatusKey
import cn.vectory.ocdroid.service.status.StatusAggregator
import cn.vectory.ocdroid.service.status.StatusAggregatorInput
import cn.vectory.ocdroid.service.status.StatusSnapshot
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.util.DebugLog
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cluster A (slim SSE resync + P2.5 first-ready cold start): L2 tests for
 * [ServiceSseConnectionOwner] `onResync` callback wiring.
 *
 * L2 changes vs D5-2:
 *  - [triggerResync] replaces [scheduleResync] / [resyncMutex] /
 *    [resyncDirtyForGen] / [resyncInFlightForGen] / [isColdStartTrigger]
 *    cluster with a 12-line scope.launch. No Mutex serialization.
 *  - First-frame cold-start fires once per attempt via `!readiness.isCompleted`
 *    (not a per-generation latch).
 *  - Explicit `type=="resync"` fires every time (B2 semantics preserved,
 *    ungated).
 *
 * KEPT tests: first-frame once, explicit resync re-fires, non-resync no-op,
 * first-frame type=resync once, exception survival, default construction, T10.
 * DELETED tests: mutex-serialization, dirty-coalescing, stale-guard (deleted
 * machinery).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ServiceSseConnectionOwnerResyncTest {

    private lateinit var scope: TestScope
    private lateinit var repository: OpenCodeRepository
    private lateinit var identityStore: ConnectionIdentityStore
    private lateinit var stream: SseEventStream
    private lateinit var store: SharedStateStore
    private lateinit var effects: SharedEffectBus
    private lateinit var aggregator: FakeAggregator
    private lateinit var runtimeStore: SseTransportRuntimeStore
    private lateinit var owner: ServiceSseConnectionOwner
    private val resyncInvocations = AtomicInteger(0)
    private val recordingHandler = object : UnexpectedTransportDropHandler {
        override fun onUnexpectedDrop(attempt: TransportAttemptToken, reason: TransportDropReason) {
            runtimeStore.publishDropped(attempt, reason)
        }
    }

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0

        scope = TestScope(UnconfinedTestDispatcher())
        repository = mockk(relaxed = true)
        identityStore = ConnectionIdentityStore()
        stream = SseEventStream()
        store = SharedStateStore()
        effects = SharedEffectBus()
        aggregator = FakeAggregator()
        runtimeStore = SseTransportRuntimeStore()
        resyncInvocations.set(0)
        DebugLog.clear()

        owner = ServiceSseConnectionOwner(
            scope = scope,
            repository = repository,
            identityStore = identityStore,
            sseEventStream = stream,
            sharedStateStore = store,
            sharedEffectBus = effects,
            ownershipGate = cn.vectory.ocdroid.service.StreamingOwnershipGate(),
            runtimeStore = runtimeStore,
            dropHandler = recordingHandler,
            onTerminalDrop = {},
            onResync = {
                resyncInvocations.incrementAndGet()
            },
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun bindIdentity(workdir: String = "/proj"): ConnectionIdentity =
        identityStore.bind("test-fp", workdir, "test-endpoint")

    private fun sseEvent(type: String): SSEEvent =
        SSEEvent(payload = SSEPayload(type = type))

    private fun sseResyncEvent(reason: String?): SSEEvent {
        val props: JsonObject = if (reason == null) {
            JsonObject(emptyMap())
        } else {
            JsonObject(mapOf("reason" to JsonPrimitive(reason)))
        }
        return SSEEvent(payload = SSEPayload(type = "resync", properties = props))
    }

    private fun stubFeed(feed: MutableSharedFlow<Result<SSEEvent>>) {
        every { repository.connectSSE(any()) } returns feed.asSharedFlow()
    }

    private fun runPending() {
        scope.testScheduler.runCurrent()
    }

    @Test
    fun `first successful frame triggers onResync once (P2_5 cold start)`() = runTest {
        val identity = bindIdentity()
        aggregator.nextState = GlobalBusyState.Busy
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        scope.launch { owner.connect(identity) }
        runPending()

        assertEquals("no cold-start before first frame", 0, resyncInvocations.get())

        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()

        assertEquals(
            "first successful frame MUST trigger onResync exactly once",
            1,
            resyncInvocations.get(),
        )
    }

    @Test
    fun `resync after first-frame cold start RE-FIRES in same generation (B2 regression)`() = runTest {
        val identity = bindIdentity()
        aggregator.nextState = GlobalBusyState.Busy
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        scope.launch { owner.connect(identity) }
        runPending()

        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertEquals(1, resyncInvocations.get())

        feed.tryEmit(Result.success(sseEvent("resync")))
        runPending()
        assertEquals(
            "mid-stream event:resync MUST re-trigger onResync (B2 regression)",
            2,
            resyncInvocations.get(),
        )

        feed.tryEmit(Result.success(sseEvent("resync")))
        runPending()
        assertEquals(
            "each event:resync frame MUST trigger its own cold-start pull",
            3,
            resyncInvocations.get(),
        )
    }

    @Test
    fun `non-first non-resync frames do not re-trigger onResync`() = runTest {
        val identity = bindIdentity()
        aggregator.nextState = GlobalBusyState.Busy
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        scope.launch { owner.connect(identity) }
        runPending()

        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertEquals(1, resyncInvocations.get())

        feed.tryEmit(Result.success(sseEvent("session.status")))
        runPending()
        feed.tryEmit(Result.success(sseEvent("session.digest")))
        runPending()

        assertEquals(
            "subsequent non-resync frames MUST NOT re-trigger onResync",
            1,
            resyncInvocations.get(),
        )
    }

    @Test
    fun `resync after a new generation fires again`() = runTest {
        val identity = bindIdentity()
        aggregator.nextState = GlobalBusyState.Busy
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        scope.launch { owner.connect(identity) }
        runPending()
        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertEquals(1, resyncInvocations.get())

        // Simulate a new transport generation: disconnect + reconnect.
        scope.launch { owner.disconnect(markGap = false) }
        runPending()

        scope.launch { owner.connect(identity) }
        runPending()
        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()

        assertEquals(
            "a new generation MUST re-arm the cold-start/resync flag",
            2,
            resyncInvocations.get(),
        )
    }

    @Test
    fun `first frame that IS type=resync fires cold-start exactly once`() = runTest {
        val identity = bindIdentity()
        aggregator.nextState = GlobalBusyState.Busy
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        scope.launch { owner.connect(identity) }
        runPending()

        feed.tryEmit(Result.success(sseEvent("resync")))
        runPending()

        assertEquals(
            "first-frame type=resync MUST fire cold-start exactly once",
            1,
            resyncInvocations.get(),
        )

        feed.tryEmit(Result.success(sseEvent("resync")))
        runPending()
        assertEquals(
            "subsequent resync after first-frame resync MUST re-fire",
            2,
            resyncInvocations.get(),
        )
    }

    @Test
    fun `onResync exception does not crash the collector`() = runTest {
        val throwingOwner = ServiceSseConnectionOwner(
            scope = scope,
            repository = repository,
            identityStore = identityStore,
            sseEventStream = stream,
            sharedStateStore = store,
            sharedEffectBus = effects,
            ownershipGate = cn.vectory.ocdroid.service.StreamingOwnershipGate(),
            runtimeStore = runtimeStore,
            dropHandler = recordingHandler,
            onTerminalDrop = {},
            onResync = { error("cold-start refetch blew up") },
        )
        val identity = bindIdentity()
        aggregator.nextState = GlobalBusyState.Busy
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        scope.launch { throwingOwner.connect(identity) }
        runPending()
        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()

        var observed = false
        val watcherJob = scope.launch {
            stream.events.collect { result ->
                result.onSuccess { ev ->
                    if (ev.event.payload.type == "session.status") observed = true
                }
            }
        }
        feed.tryEmit(Result.success(sseEvent("session.status")))
        runPending()
        runPending()
        assertEquals("collector survives onResync exceptions", true, observed)
        watcherJob.cancel()
    }

    @Test
    fun `onResync default is non-null callback contract for production wiring`() {
        val defaultOwner = ServiceSseConnectionOwner(
            scope = scope,
            repository = repository,
            identityStore = identityStore,
            sseEventStream = stream,
            sharedStateStore = store,
            sharedEffectBus = effects,
            ownershipGate = cn.vectory.ocdroid.service.StreamingOwnershipGate(),
            runtimeStore = runtimeStore,
            dropHandler = recordingHandler,
            onTerminalDrop = {},
        )
        assertNotNull("default owner constructs", defaultOwner)
    }

    // ── T10 resync reason tests ────────────────────────────────────────────

    private fun connectAndFireFirstFrame(feed: MutableSharedFlow<Result<SSEEvent>>) {
        scope.launch { owner.connect(bindIdentity()) }
        runPending()
        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertEquals("baseline: first-frame cold-start fired exactly once", 1, resyncInvocations.get())
    }

    @Test
    fun `resync reason = reconnect_no_replay triggers onResync (T10-C3)`() = runTest {
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)
        aggregator.nextState = GlobalBusyState.Busy
        connectAndFireFirstFrame(feed)

        feed.tryEmit(Result.success(sseResyncEvent("reconnect_no_replay")))
        runPending()
        assertEquals("resync with reason=reconnect_no_replay MUST trigger onResync", 2, resyncInvocations.get())
    }

    @Test
    fun `resync reason = subscriber_backpressure triggers onResync (T10-C3)`() = runTest {
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)
        aggregator.nextState = GlobalBusyState.Busy
        connectAndFireFirstFrame(feed)

        feed.tryEmit(Result.success(sseResyncEvent("subscriber_backpressure")))
        runPending()
        assertEquals("resync with reason=subscriber_backpressure MUST trigger onResync", 2, resyncInvocations.get())
    }

    @Test
    fun `resync reason = implicit triggers onResync (T10-C3)`() = runTest {
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)
        aggregator.nextState = GlobalBusyState.Busy
        connectAndFireFirstFrame(feed)

        feed.tryEmit(Result.success(sseResyncEvent("implicit")))
        runPending()
        assertEquals("resync with reason=implicit MUST trigger onResync", 2, resyncInvocations.get())
    }

    @Test
    fun `resync with UNKNOWN reason string still triggers onResync (T10-C2 unknown)`() = runTest {
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)
        aggregator.nextState = GlobalBusyState.Busy
        connectAndFireFirstFrame(feed)

        feed.tryEmit(Result.success(sseResyncEvent("some-future-reason-v2")))
        runPending()
        assertEquals("resync with UNKNOWN reason MUST still trigger onResync", 2, resyncInvocations.get())
    }

    @Test
    fun `resync with NULL reason field still triggers onResync (T10-C2 null)`() = runTest {
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)
        aggregator.nextState = GlobalBusyState.Busy
        connectAndFireFirstFrame(feed)

        feed.tryEmit(Result.success(sseResyncEvent(reason = null)))
        runPending()
        assertEquals("resync with missing reason key MUST trigger onResync", 2, resyncInvocations.get())

        feed.tryEmit(Result.success(sseEvent("resync")))
        runPending()
        assertEquals("resync with null properties MUST trigger onResync", 3, resyncInvocations.get())
    }

    @Test
    fun `resync reason is parsed via JsonPrimitive content and logged via SlimapiResyncReason fromRaw (T10-C1)`() = runTest {
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)
        aggregator.nextState = GlobalBusyState.Busy
        connectAndFireFirstFrame(feed)
        DebugLog.clear()

        feed.tryEmit(Result.success(sseResyncEvent("reconnect_no_replay")))
        runPending()

        val resyncReasonLogs = DebugLog.entries.value.filter {
            it.tag == "ServiceSseOwner" && it.message.contains("slim resync reason")
        }
        assertTrue(
            "resync reason MUST be logged via DebugLog. Matching: $resyncReasonLogs",
            resyncReasonLogs.isNotEmpty(),
        )
        val firstMsg = resyncReasonLogs.first().message
        assertTrue("raw wire value `reconnect_no_replay` MUST appear in log. Got: $firstMsg",
            firstMsg.contains("reconnect_no_replay"))
        assertTrue("typed SlimapiResyncReason RECONNECT_NO_REPLAY MUST appear. Got: $firstMsg",
            firstMsg.contains("RECONNECT_NO_REPLAY"))
    }

    @Test
    fun `resync UNKNOWN reason logs null typed reason but still records raw wire value (T10-C1 forward-compat)`() = runTest {
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)
        aggregator.nextState = GlobalBusyState.Busy
        connectAndFireFirstFrame(feed)
        DebugLog.clear()

        feed.tryEmit(Result.success(sseResyncEvent("some-future-reason-v2")))
        runPending()

        val resyncReasonLogs = DebugLog.entries.value.filter {
            it.tag == "ServiceSseOwner" && it.message.contains("slim resync reason")
        }
        assertTrue("unknown reason MUST be logged. Matching: $resyncReasonLogs",
            resyncReasonLogs.isNotEmpty())
        val firstMsg = resyncReasonLogs.first().message
        assertTrue("raw unknown wire value MUST appear. Got: $firstMsg",
            firstMsg.contains("some-future-reason-v2"))
        assertTrue("typed reason MUST be null. Got: $firstMsg",
            firstMsg.contains("typed=null"))
    }

    // ── Helper fakes ────────────────────────────────────────────────────────

    private class FakeAggregator : StatusAggregator, StatusAggregatorInput {
        var nextState: GlobalBusyState = GlobalBusyState.Busy
        private val _globalState = MutableStateFlow(GlobalBusyState.Busy)
        private val _globalBusy = MutableStateFlow(true)
        private val _statusByKey =
            MutableStateFlow<Map<SessionStatusKey, SessionBusyStatus>>(emptyMap())

        override val globalState = _globalState.asStateFlow()
        override val globalBusy = _globalBusy.asStateFlow()
        override val statusByKey = _statusByKey.asStateFlow()

        override fun stateAtNow(): GlobalBusyState = _globalState.value

        override suspend fun refresh(
            identity: ConnectionIdentity,
            snapshot: StatusSnapshot,
        ) {
            _globalState.value = nextState
            _globalBusy.value = nextState == GlobalBusyState.Busy
        }

        override fun applySseStatus(
            key: SessionStatusKey,
            status: SessionBusyStatus,
            sourceTimeMs: Long,
        ) = Unit

        override fun markRequestFailed(
            identity: ConnectionIdentity,
            snapshot: StatusSnapshot,
            sourceTimeMs: Long,
        ) {
            _globalState.value = GlobalBusyState.Unknown
            _globalBusy.value = false
        }
    }
}
