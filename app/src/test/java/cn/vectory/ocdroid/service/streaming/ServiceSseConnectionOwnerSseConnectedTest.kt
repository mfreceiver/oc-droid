package cn.vectory.ocdroid.service.streaming

import android.util.Log
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.service.events.SseEventStream
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * L2 breathing-indicator tests for [ServiceSseConnectionOwner]'s SSE-up signal.
 *
 * Contract (unchanged from D5-2):
 *  - `true` on the first valid current-identity frame.
 *  - `false` on post-Ready outage (flow break → drop routed).
 *  - `false` on intentional disconnect.
 *  - `false` on cancelForShutdown.
 *  - CAS TOCTOU: stale collector writes rejected.
 *  - Host purge clears flag + advances stamp.
 *
 * L2 changes: single-attempt collector — no retry loop. After flow break,
 * flag drops to false and remains false permanently (no recovery within
 * the same connect attempt).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ServiceSseConnectionOwnerSseConnectedTest {

    private lateinit var scope: TestScope
    private lateinit var repository: OpenCodeRepository
    private lateinit var identityStore: ConnectionIdentityStore
    private lateinit var stream: SseEventStream
    private lateinit var store: SharedStateStore
    private lateinit var effects: SharedEffectBus
    private lateinit var runtimeStore: SseTransportRuntimeStore
    private lateinit var owner: ServiceSseConnectionOwner
    private val dropCalls: MutableList<DropCall> = mutableListOf()
    private val recordingHandler = object : UnexpectedTransportDropHandler {
        override fun onUnexpectedDrop(attempt: TransportAttemptToken, reason: TransportDropReason) {
            dropCalls += DropCall(attempt, reason)
            runtimeStore.publishDropped(attempt, reason)
        }
    }

    private data class DropCall(val attempt: TransportAttemptToken, val reason: TransportDropReason)

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
        runtimeStore = SseTransportRuntimeStore()
        dropCalls.clear()
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

    private fun stubFeed(feed: MutableSharedFlow<Result<SSEEvent>>) {
        every { repository.connectSSE(any()) } returns feed.asSharedFlow()
    }

    private fun runPending() {
        scope.testScheduler.runCurrent()
    }

    private fun sseConnected(): Boolean = store.sseConnectedFlow.value

    // ── (1) connect → true on first valid frame ───────────────────────────

    @Test
    fun `first valid frame sets isSseConnected true (readiness)`() = runTest {
        val identity = bindIdentity()
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        assertFalse("flag starts false", sseConnected())

        scope.launch { owner.connect(identity) }
        runPending()
        assertFalse("flag still false before any frame", sseConnected())

        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()

        assertTrue("first valid frame MUST set isSseConnected=true", sseConnected())
    }

    // ── runtime sseConnectedFlow projection ──────────────────────────────

    @Test
    fun `M1A - runtime sseConnectedFlow mirrors Live on first frame and Stopped on teardown`() = runTest {
        val identity = bindIdentity()
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        assertFalse("runtime projection false before any frame", runtimeStore.sseConnectedFlow.value)

        scope.launch { owner.connect(identity) }
        runPending()
        assertFalse("runtime projection false while Connecting", runtimeStore.sseConnectedFlow.value)

        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertTrue("runtime sseConnectedFlow true after markLive", runtimeStore.sseConnectedFlow.value)

        scope.launch { owner.disconnect(markGap = true) }
        runPending()
        assertFalse("runtime sseConnectedFlow false after markStopped", runtimeStore.sseConnectedFlow.value)
    }

    // ── independence from ConnectionState ─────────────────────────────────

    @Test
    fun `isSseConnected is INDEPENDENT of ConnectionState isConnected`() = runTest {
        val identity = bindIdentity()
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        scope.launch { owner.connect(identity) }
        runPending()
        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()

        assertTrue("transport-up flag true after a frame", sseConnected())
        assertFalse("health-settle isConnected MUST stay false (D5 #3)", store.connectionFlow.value.isConnected)
    }

    // ── stale-generation frame after replacement ──────────────────────────

    @Test
    fun `stale-generation frame after replacement does NOT flip the flag`() = runTest {
        val identity = bindIdentity()
        val feed1 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed1.asSharedFlow()

        scope.launch { owner.connect(identity) }
        runPending()
        feed1.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertTrue("gen 1 frame set the flag true", sseConnected())

        val feed2 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed2.asSharedFlow()
        scope.launch { owner.connect(identity) }
        runPending()

        assertFalse("supersession drops the flag (inter-reconfigure gap)", sseConnected())

        feed1.tryEmit(Result.success(sseEvent("stale-late-frame")))
        runPending()
        assertFalse("stale gen-1 frame MUST NOT flip the flag", sseConnected())

        feed2.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertTrue("gen 2 first frame re-asserts isSseConnected=true", sseConnected())
    }

    // ── post-Ready outage → false ─────────────────────────────────────────

    @Test
    fun `post-Ready outage drops isSseConnected false (single-attempt terminal)`() = runTest {
        val identity = bindIdentity()
        val outageGate = CompletableDeferred<Unit>()
        every { repository.connectSSE(any()) } returns flow<Result<SSEEvent>> {
            emit(Result.success(sseEvent("first")))
            outageGate.await()
            throw IOException("post-ready outage")
        }

        scope.launch { owner.connect(identity) }
        runPending()

        assertTrue("first frame set the flag true before outage", sseConnected())

        outageGate.complete(Unit)
        runPending()
        assertFalse("post-Ready outage drops the flag false (terminal)", sseConnected())
    }

    // ── intentional disconnect → false ───────────────────────────────────

    @Test
    fun `intentional disconnect sets isSseConnected false`() = runTest {
        val identity = bindIdentity()
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        scope.launch { owner.connect(identity) }
        runPending()
        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertTrue("connected before disconnect", sseConnected())

        scope.launch { owner.disconnect(markGap = true) }
        runPending()
        assertFalse("intentional disconnect drops the flag false", sseConnected())

        feed.tryEmit(Result.success(sseEvent("late-after-disconnect")))
        runPending()
        assertFalse("late frame after disconnect MUST NOT resurrect the flag", sseConnected())
    }

    @Test
    fun `disconnect before any frame keeps the flag false (no false-ready)`() = runTest {
        val identity = bindIdentity()
        every { repository.connectSSE(any()) } returns
            flow<Result<SSEEvent>> { kotlinx.coroutines.delay(Long.MAX_VALUE) }

        scope.launch { owner.connect(identity) }
        runPending()
        assertFalse("flag false before any frame", sseConnected())

        scope.launch { owner.disconnect(markGap = false) }
        runPending()
        assertFalse("flag stays false after pre-frame disconnect", sseConnected())
    }

    // ── rapid connect / disconnect ordering ───────────────────────────────

    @Test
    fun `rapid connect then disconnect then reconnect tracks the flag each step`() = runTest {
        val identity = bindIdentity()

        val feed1 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed1.asSharedFlow()
        scope.launch { owner.connect(identity) }
        runPending()
        feed1.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertTrue("step 1 connected", sseConnected())

        scope.launch { owner.disconnect(markGap = true) }
        runPending()
        assertFalse("step 2 disconnected", sseConnected())

        val feed2 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed2.asSharedFlow()
        scope.launch { owner.connect(identity) }
        runPending()
        assertFalse("step 3 inter-reconfigure gap (no frame yet)", sseConnected())

        feed1.tryEmit(Result.success(sseEvent("stale-from-gen1")))
        runPending()
        assertFalse("stale gen-1 frame ignored after reconnect", sseConnected())

        feed2.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertTrue("step 4 reconnected", sseConnected())
    }

    // ── cancelForShutdown → false ────────────────────────────────────────

    @Test
    fun `cancelForShutdown sets isSseConnected false (service destruction)`() = runTest {
        val identity = bindIdentity()
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        scope.launch { owner.connect(identity) }
        runPending()
        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertTrue("connected before shutdown", sseConnected())

        owner.cancelForShutdown()
        assertFalse("cancelForShutdown drops the flag false", sseConnected())
    }

    @Test
    fun `flag survives service recreation via the singleton store`() = runTest {
        val identity = bindIdentity()
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        scope.launch { owner.connect(identity) }
        runPending()
        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertTrue("owner A connected", sseConnected())

        owner.cancelForShutdown()
        assertFalse("owner A shutdown dropped the flag", sseConnected())

        val ownerB = ServiceSseConnectionOwner(
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
        assertFalse(
            "owner B observes the persisted false flag (survives recreation)",
            ownerB.isSseConnected.value,
        )
        assertFalse("store projection survives recreation", store.sseConnectedFlow.value)

        val feedB = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feedB.asSharedFlow()
        scope.launch { ownerB.connect(identity) }
        runPending()
        feedB.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertTrue("owner B first frame re-asserts the flag true", ownerB.isSseConnected.value)
    }

    // ── TOCTOU CAS ───────────────────────────────────────────────────────

    @Test
    fun `monotonic CAS invariant - newer generation wins, older rejected`() = runTest {
        assertTrue("gen-10 frame commits (10 >= initial 0)", store.mutateSseConnected(true, 10L))
        assertTrue("flag true after gen-10 frame", sseConnected())

        assertTrue("gen-11 teardown commits (11 >= 10)", store.mutateSseConnected(false, 11L))
        assertFalse("flag false after teardown", sseConnected())

        assertFalse("stale gen-10 write REJECTED by monotonic CAS (10 < 11)", store.mutateSseConnected(true, 10L))
        assertFalse("flag stays false — stale collector cannot resurrect 'connected'", sseConnected())

        assertFalse("stale gen-5 also rejected (5 < 11)", store.mutateSseConnected(true, 5L))
        assertFalse("flag still false", sseConnected())

        assertTrue("gen-12 reconnect commits (12 >= 11)", store.mutateSseConnected(true, 12L))
        assertTrue("flag true after new-gen reconnect", sseConnected())
    }

    @Test
    fun `owner teardown stamps the NEW generation - stale collector loses the CAS (TOCTOU race)`() = runTest {
        val identity = bindIdentity()
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        scope.launch { owner.connect(identity) }
        runPending()
        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertTrue("gen 1 connected", sseConnected())

        scope.launch { owner.disconnect(markGap = true) }
        runPending()
        assertFalse("teardown dropped the flag false", sseConnected())

        assertFalse(
            "stale gen-1 write REJECTED — owner stamped gen 2 on teardown",
            store.mutateSseConnected(true, 1L),
        )
        assertFalse("flag stays false — stale collector cannot resurrect 'connected'", sseConnected())

        assertTrue("new-gen-3 reconnect wins the CAS", store.mutateSseConnected(true, 3L))
        assertTrue("flag true after new-gen reconnect", sseConnected())
    }

    // ── Host purge ────────────────────────────────────────────────────────

    @Test
    fun `host purge clears isSseConnected and advances the generation stamp - stale collector rejected`() = runTest {
        val identity = bindIdentity()
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        scope.launch { owner.connect(identity) }
        runPending()
        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertTrue("connected before purge", sseConnected())
        val prePurgeGen = store.sseConnectedGeneration
        assertTrue("pre-purge gen is set", prePurgeGen > 0L)

        store.dispatch(cn.vectory.ocdroid.ui.AppAction.HostStatePurged)
        assertFalse("purge cleared the breath flag", sseConnected())
        assertTrue("purge advanced the generation stamp", store.sseConnectedGeneration > prePurgeGen)

        assertFalse("stale pre-purge-gen write REJECTED by CAS after purge", store.mutateSseConnected(true, prePurgeGen))
        assertFalse("flag stays false — no stale-true breath for a purged host", sseConnected())

        val feed2 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed2.asSharedFlow()
        scope.launch { owner.connect(identity) }
        runPending()
        feed2.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertTrue("fresh connect after purge re-asserts the flag true", sseConnected())
    }
}
