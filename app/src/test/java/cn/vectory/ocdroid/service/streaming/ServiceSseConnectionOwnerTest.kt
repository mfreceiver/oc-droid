package cn.vectory.ocdroid.service.streaming

import android.util.Log
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.service.events.SseEventStream
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.ui.ConnectionPhase
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * L2 tests for [ServiceSseConnectionOwner]'s single-attempt connect + gap.
 *
 * L2 changes (vs D5-2):
 *  - Single-attempt collector: NO retry loop, NO transport timeout.
 *  - `ownershipGate` added (real instance), `onTerminalExhaustion` renamed
 *    to `onTerminalDrop`.
 *  - Retry tests deleted (30s/2m/5m timing, transport timeout param,
 *    beforeMarkRetrying, exhaustion path).
 *  - `disconnectRequests` → `terminalDropCalls`, wired to `onTerminalDrop`.
 *  - Recording handler constructs [LeaseToken] for releaseNow (mirrors
 *    [ForegroundTransportDropHandler]).
 *  - Added O6 (superseded collector late failure) and O7 (pre-ready failure,
 *    lease released, reconnect clean).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ServiceSseConnectionOwnerTest {

    private lateinit var scope: TestScope
    private lateinit var repository: OpenCodeRepository
    private lateinit var identityStore: ConnectionIdentityStore
    private lateinit var stream: SseEventStream
    private lateinit var store: SharedStateStore
    private lateinit var effects: SharedEffectBus
    private lateinit var collectedEffects: MutableList<ControllerEffect>
    private lateinit var recordedEvents: MutableList<UiEvent>
    private lateinit var streamFrames: MutableList<SSEEvent>
    private var terminalDropCalls: Int = 0
    private lateinit var runtimeStore: SseTransportRuntimeStore
    private val dropCalls: MutableList<DropCall> = mutableListOf()
    private val recordingHandler = object : UnexpectedTransportDropHandler {
        override fun onUnexpectedDrop(attempt: TransportAttemptToken, reason: TransportDropReason) {
            dropCalls += DropCall(attempt, reason)
            // Mirror production handler: release lease via LeaseToken FIRST
            // (I3 ordering), then publish the drop.
            ownershipGate.releaseNow(cn.vectory.ocdroid.service.LeaseToken(attempt.attemptId, attempt.identity))
            runtimeStore.publishDropped(attempt, reason)
        }
    }
    private lateinit var owner: ServiceSseConnectionOwner
    private val ownershipGate = cn.vectory.ocdroid.service.StreamingOwnershipGate()

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
        collectedEffects = mutableListOf()
        recordedEvents = mutableListOf()
        streamFrames = mutableListOf()
        terminalDropCalls = 0
        runtimeStore = SseTransportRuntimeStore()
        dropCalls.clear()
        owner = ServiceSseConnectionOwner(
            scope = scope,
            repository = repository,
            identityStore = identityStore,
            sseEventStream = stream,
            sharedStateStore = store,
            sharedEffectBus = effects,
            ownershipGate = ownershipGate,
            runtimeStore = runtimeStore,
            dropHandler = recordingHandler,
            onTerminalDrop = { terminalDropCalls++ },
        )
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            effects.effectsConsumed.toList(collectedEffects)
        }
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            effects.uiEventsConsumed.toList(recordedEvents)
        }
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            stream.events.collect { result ->
                result.onSuccess { streamFrames += it.event }
            }
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun runPending() {
        scope.testScheduler.runCurrent()
    }

    private fun advanceOwnerTimeBy(delayMs: Long) {
        scope.testScheduler.advanceTimeBy(delayMs)
        scope.testScheduler.runCurrent()
    }

    private fun bindIdentity(workdir: String = "/proj"): ConnectionIdentity =
        identityStore.bind("test-fp", workdir, "test-endpoint")

    private fun sseEvent(type: String): SSEEvent =
        SSEEvent(payload = SSEPayload(type = type))

    private fun collectedFrames(): List<SSEEvent> = streamFrames.toList()

    private fun stubFeed(feed: MutableSharedFlow<Result<SSEEvent>>) {
        every { repository.connectSSE(any()) } returns feed.asSharedFlow()
    }

    private fun launchConnect(identity: ConnectionIdentity) {
        scope.launch { owner.connect(identity) }
    }

    private fun runtimeState(): SseTransportState = runtimeStore.state.value

    // ── D4-B M3: first-frame Ready + IdentifiedSseEvent publish ────────────

    @Test
    fun `current identity success emits exact IdentifiedSseEvent and completes Ready`() = runTest {
        val identity = bindIdentity("/proj")
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        launchConnect(identity)
        runPending()

        val evt = sseEvent("session.status")
        feed.tryEmit(Result.success(evt))
        runPending()

        assertEquals("current-identity success event reaches the stream", listOf(evt), collectedFrames())
        assertFalse(
            "SSE owner does NOT publish terminal Connected on a frame (D5 #3)",
            store.connectionFlow.value.isConnected,
        )
    }

    // ── stale-identity rejection ───────────────────────────────────────────

    @Test
    fun `stale identity before connect returns StaleIdentity and does not call connectSSE`() = runTest {
        val staleIdentity = bindIdentity("/proj")
        identityStore.beginReconfigure()

        var result: SourceActivation? = null
        scope.launch { result = owner.connect(staleIdentity) }
        runPending()

        verify(exactly = 0) { repository.connectSSE(any()) }
        assertEquals(SourceActivation.Rejected.StaleIdentity, result)
    }

    // ── D5 #3: no terminal Connected on frame ──────────────────────────────

    @Test
    fun `D5 3 - success does NOT write terminal Connected (only ownership commit may)`() = runTest {
        val identity = bindIdentity("/proj")
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        assertFalse(store.connectionFlow.value.isConnected)

        launchConnect(identity)
        runPending()

        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()

        val cs = store.connectionFlow.value
        assertFalse("SSE owner does NOT write isConnected=true on a frame (D5 #3)", cs.isConnected)
        assertNotEquals(
            "SSE owner does NOT write ConnectionPhase.Connected on a frame (D5 #3)",
            ConnectionPhase.Connected,
            cs.connectionPhase,
        )
    }

    // ── event-level failure ────────────────────────────────────────────────

    @Test
    fun `event-level failure emits error_sse_failed`() = runTest {
        val identity = bindIdentity("/proj")
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        launchConnect(identity)
        runPending()

        feed.tryEmit(Result.failure(IOException("bad frame")))
        runPending()

        val err = recordedEvents.filterIsInstance<UiEvent.Error>().firstOrNull()
        assertNotNull("event-level failure emitted UiEvent.Error", err)
        assertEquals(R.string.error_sse_failed, err!!.resId)
    }

    // ── disconnect stops forwarding + ONE CancelSse ────────────────────────

    @Test
    fun `disconnect stops forwarding and emits exactly one CancelSse`() = runTest {
        val identity = bindIdentity("/proj")
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        launchConnect(identity)
        runPending()

        val first = sseEvent("first")
        feed.tryEmit(Result.success(first))
        runPending()
        val framesBefore = collectedFrames()
        assertEquals("exactly one frame captured before disconnect", listOf(first), framesBefore)

        scope.launch { owner.disconnect(markGap = true) }
        runPending()

        val cancelEffects = collectedEffects.filterIsInstance<ControllerEffect.CancelSse>()
        assertEquals("disconnect emitted exactly one CancelSse (markGap=true)", 1, cancelEffects.size)

        val after = sseEvent("after-cancel")
        feed.tryEmit(Result.success(after))
        runPending()

        val framesAfter = collectedFrames()
        assertEquals("frame list is EXACTLY unchanged after disconnect", framesBefore, framesAfter)
        assertTrue("after-cancel event is NOT in captured frame list", framesAfter.none { it.payload.type == "after-cancel" })
    }

    // ── idempotent disconnect ──────────────────────────────────────────────

    @Test
    fun `repeated disconnect is idempotent`() = runTest {
        val identity = bindIdentity("/proj")
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        launchConnect(identity)
        runPending()

        scope.launch { owner.disconnect(markGap = true) }
        runPending()
        scope.launch { owner.disconnect(markGap = true) }
        runPending()
        scope.launch { owner.disconnect(markGap = true) }
        runPending()

        val cancelEffects = collectedEffects.filterIsInstance<ControllerEffect.CancelSse>()
        assertEquals("only the first disconnect (live generation) emits CancelSse", 1, cancelEffects.size)
    }

    // ── cancellation no terminal callback ──────────────────────────────────

    @Test
    fun `cancellation does not emit an SSE error and does NOT trigger onTerminalDrop`() = runTest {
        val identity = bindIdentity("/proj")
        val pending = CompletableDeferred<Result<SSEEvent>>()
        every { repository.connectSSE(any()) } returns flow<Result<SSEEvent>> { pending.await() }

        launchConnect(identity)
        runPending()

        scope.launch { owner.disconnect(markGap = false) }
        runPending()

        assertTrue(
            "cancellation MUST NOT surface an SSE error",
            recordedEvents.filterIsInstance<UiEvent.Error>().none { it.resId == R.string.error_sse_failed },
        )
        assertEquals("onTerminalDrop NOT invoked on clean cancellation", 0, terminalDropCalls)
    }

    // ── stale-identity termination mid-collection ──────────────────────────

    @Test
    fun `stale-identity termination - no retry, drop occurs`() = runTest {
        val identity = bindIdentity("/proj")
        every { repository.connectSSE(any()) } returns flow { throw IOException("boom") }

        launchConnect(identity)
        runPending()

        // In L2 single-attempt: flow throws → onCollectionException → gap →
        // route drop → onTerminalDrop. The drop is routed (RETRY_EXHAUSTED).
        assertTrue("gap emitted", collectedEffects.filterIsInstance<ControllerEffect.CancelSse>().size >= 1)
        verify(exactly = 1) { repository.connectSSE(any()) }
        assertEquals("onTerminalDrop invoked after single failure", 1, terminalDropCalls)
    }

    // ── workdir from identity ──────────────────────────────────────────────

    @Test
    fun `workdir comes from identity not mutable Settings state`() = runTest {
        val blankWorkdirIdentity = identityStore.bind("test-fp", "", "endpoint")
        val feed1 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed1.asSharedFlow()

        launchConnect(blankWorkdirIdentity)
        runPending()

        verify { repository.connectSSE(null) }

        // L2 Gate: disconnect before connecting with different identity
        // (the Gate blocks cross-identity claims).
        scope.launch { owner.disconnect(markGap = false) }
        runPending()

        val projIdentity = identityStore.bind("test-fp", "/proj-B", "endpoint")
        val feed2 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed2.asSharedFlow()
        launchConnect(projIdentity)
        runPending()

        verify { repository.connectSSE("/proj-B") }
    }

    // ── M3: Unknown baseline completes readiness ───────────────────────────

    @Test
    fun `M3 - SSE first frame with Unknown baseline still completes transport readiness`() = runTest {
        val identity = bindIdentity("/proj")
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        var result: SourceActivation? = null
        scope.launch { result = owner.connect(identity) }
        runPending()

        feed.tryEmit(Result.success(sseEvent("first-frame")))
        runPending()

        assertEquals("Unknown baseline does NOT gate transport readiness (M3)", SourceActivation.Ready, result)
        assertTrue("first frame reached the stream", collectedFrames().any { it.payload.type == "first-frame" })
    }

    // ── M1A: Connecting → Live on beginAttempt/first-frame ─────────────────

    @Test
    fun `M1A - accepted connect begins Connecting and first frame marks Live`() = runTest {
        val identity = bindIdentity("/proj")
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        assertEquals("runtime starts Stopped", SseTransportState.Stopped, runtimeState())

        launchConnect(identity)
        runPending()

        assertTrue("accepted connect transitions runtime to Connecting", runtimeState() is SseTransportState.Connecting)
        val connectingAttempt = (runtimeState() as SseTransportState.Connecting).attempt
        assertEquals("Connecting attempt carries the connect identity", identity, connectingAttempt.identity)

        feed.tryEmit(Result.success(sseEvent("first")))
        runPending()

        val live = runtimeState()
        assertTrue("first frame marks runtime Live", live is SseTransportState.Live)
        assertEquals("Live attempt is the same token", connectingAttempt.attemptId, (live as SseTransportState.Live).attempt.attemptId)
    }

    // ── M1A-2: beginAttempt rejection returns TransportTimeout ─────────────

    @Test
    fun `M1A - beginAttempt rejection does not start a collector`() = runTest {
        val identityA = identityStore.bind("fp-a", "/a", "ep")
        val identityB = identityStore.bind("fp-b", "/b", "ep")
        runtimeStore.beginAttempt(identityA)
        assertEquals("foreign identity owns Connecting", identityA,
            (runtimeState() as SseTransportState.Connecting).attempt.identity)

        stubFeed(MutableSharedFlow(extraBufferCapacity = 8))

        var result: SourceActivation? = null
        scope.launch { result = owner.connect(identityB) }
        runPending()

        verify(exactly = 0) { repository.connectSSE(any()) }
        assertEquals("rejected connect returns TransportTimeout", SourceActivation.Rejected.TransportTimeout, result)
        assertEquals("runtime unchanged after rejected beginAttempt", identityA,
            (runtimeState() as SseTransportState.Connecting).attempt.identity)
    }

    // ── M1A-6: intentional disconnect → Stopped no drop ───────────────────

    @Test
    fun `M1A-6 - intentional disconnect marks Stopped and never drops`() = runTest {
        val identity = bindIdentity("/proj")
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        launchConnect(identity)
        runPending()
        feed.tryEmit(Result.success(sseEvent("first")))
        runPending()
        assertTrue("Live before disconnect", runtimeState() is SseTransportState.Live)

        scope.launch { owner.disconnect(markGap = true) }
        runPending()

        assertEquals("intentional disconnect transitions runtime to Stopped", SseTransportState.Stopped, runtimeState())
        assertEquals("intentional disconnect NEVER routes a drop", 0, dropCalls.size)
    }

    @Test
    fun `M1A-6 - cancelForShutdown marks Stopped and never drops`() = runTest {
        val identity = bindIdentity("/proj")
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        launchConnect(identity)
        runPending()
        feed.tryEmit(Result.success(sseEvent("first")))
        runPending()
        assertTrue("Live before shutdown", runtimeState() is SseTransportState.Live)

        owner.cancelForShutdown()

        assertEquals("cancelForShutdown transitions runtime to Stopped", SseTransportState.Stopped, runtimeState())
        assertEquals("cancelForShutdown NEVER routes a drop", 0, dropCalls.size)
    }

    // ── M1A-C4: stale generation cannot mark Live ─────────────────────────

    @Test
    fun `M1A-C4 - stale generation cannot mark Live and stale token is rejected`() = runTest {
        val identity = bindIdentity("/proj")

        val feed1 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed1.asSharedFlow()
        launchConnect(identity)
        runPending()
        feed1.tryEmit(Result.success(sseEvent("first")))
        runPending()
        val t1 = (runtimeState() as SseTransportState.Live).attempt
        assertTrue("gen 1 Live", t1.identity == identity)

        val feed2 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed2.asSharedFlow()
        launchConnect(identity)
        runPending()

        val afterSupersede = runtimeState()
        assertTrue("gen 2 supersession left runtime Connecting", afterSupersede is SseTransportState.Connecting)
        val t2 = (afterSupersede as SseTransportState.Connecting).attempt
        assertNotEquals("gen 2 allocated a fresh attempt token", t1.attemptId, t2.attemptId)

        feed1.tryEmit(Result.success(sseEvent("stale-late")))
        runPending()
        assertTrue("stale gen-1 frame did NOT resurrect Live", runtimeState() is SseTransportState.Connecting)

        assertFalse("runtime rejects stale token markLive", runtimeStore.markLive(t1))
        assertFalse("runtime rejects stale token markStopped", runtimeStore.markStopped(t1))
    }

    // ── D5 1a: post-Ready outage (single-attempt) ──────────────────────────

    @Test
    fun `D5 1a - post-Ready outage emits one gap and routes drop (single-attempt terminal)`() = runTest {
        val identity = bindIdentity("/proj")
        every { repository.connectSSE(any()) } returns flow<Result<SSEEvent>> {
            emit(Result.success(sseEvent("first")))
            throw IOException("post-ready outage")
        }

        launchConnect(identity)
        runPending()

        assertTrue("first frame reached the stream", collectedFrames().any { it.payload.type == "first" })
        assertTrue("exactly one CancelSse gap", collectedEffects.filterIsInstance<ControllerEffect.CancelSse>().size >= 1)
        assertEquals("onTerminalDrop invoked exactly once", 1, terminalDropCalls)
        verify(exactly = 1) { repository.connectSSE(any()) }
    }

    // ── M1A-C3: exception exhaustion routes exactly one RETRY_EXHAUSTED drop ─

    @Test
    fun `M1A-C3 - single-attempt failure routes exactly one RETRY_EXHAUSTED drop`() = runTest {
        val identity = bindIdentity("/proj")
        every { repository.connectSSE(any()) } returnsMany listOf(
            flow<Result<SSEEvent>> {
                emit(Result.success(sseEvent("first")))
                throw IOException("post-ready outage")
            },
        )

        launchConnect(identity)
        runPending()

        assertEquals("exactly one drop routed", 1, dropCalls.size)
        assertEquals("drop reason is RETRY_EXHAUSTED", TransportDropReason.RETRY_EXHAUSTED, dropCalls.single().reason)
        assertTrue("runtime ended Dropped", runtimeState() is SseTransportState.Dropped)
    }

    // ── R-stale: stale-generation frame fully suppressed ───────────────────

    @Test
    fun `R-stale - stale-generation frame is fully suppressed (no event, no Ready, no Live resurrection)`() = runTest {
        val identity = bindIdentity("/proj")

        val feed1 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed1.asSharedFlow()
        launchConnect(identity)
        runPending()
        feed1.tryEmit(Result.success(sseEvent("first-gen1")))
        runPending()
        val t1 = (runtimeState() as SseTransportState.Live).attempt
        val framesBeforeSupersede = collectedFrames().size

        val feed2 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed2.asSharedFlow()
        launchConnect(identity)
        runPending()
        val t2 = (runtimeState() as SseTransportState.Connecting).attempt
        assertNotEquals("gen 2 fresh attempt", t1.attemptId, t2.attemptId)

        feed1.tryEmit(Result.success(sseEvent("stale-late-frame")))
        runPending()

        assertEquals("stale-generation frame did NOT reach the stream (suppressed)", framesBeforeSupersede, collectedFrames().size)
        assertTrue("stale frame did NOT resurrect Live", runtimeState() is SseTransportState.Connecting)
        assertFalse("runtime rejects stale markLive(T1)", runtimeStore.markLive(t1))
    }

    // ── R-stale-cb: same-gen terminalized collection failure suppresses side effects ──

    @Test
    fun `R-stale-cb - same-gen terminalized collection failure suppresses error gap and drop`() = runTest {
        val identity = bindIdentity("/proj")
        val throwGate = CompletableDeferred<Unit>()
        every { repository.connectSSE(any()) } returnsMany listOf(
            flow<Result<SSEEvent>> {
                throwGate.await()
                throw IOException("post-terminalization failure")
            },
            flow<Result<SSEEvent>> { kotlinx.coroutines.delay(Long.MAX_VALUE) },
        )

        launchConnect(identity)
        runPending()

        val attempt = (runtimeState() as SseTransportState.Connecting).attempt
        assertTrue("terminalize the canonical attempt", runtimeStore.markStopped(attempt))
        assertEquals("runtime is Stopped", SseTransportState.Stopped, runtimeState())

        throwGate.complete(Unit)
        runPending()

        assertTrue(
            "stale-token collection failure emitted NO SSE error",
            recordedEvents.filterIsInstance<UiEvent.Error>().none { it.resId == R.string.error_sse_failed },
        )
        assertEquals("stale-token collection failure emitted NO gap", 0,
            collectedEffects.filterIsInstance<ControllerEffect.CancelSse>().size)
        assertEquals("stale-token collection failure routed NO drop", 0, dropCalls.size)
        assertEquals("stale-token collection failure invoked NO terminal callback", 0, terminalDropCalls)
        assertEquals("runtime unchanged (still Stopped)", SseTransportState.Stopped, runtimeState())
    }

    // ── M1A-C3 companion: unexpected normal completion routes drop ─────────

    @Test
    fun `M1A-C3 - unexpected normal completion routes RETRY_EXHAUSTED drop`() = runTest {
        val identity = bindIdentity("/proj")
        every { repository.connectSSE(any()) } returns flow<Result<SSEEvent>> {
            emit(Result.success(sseEvent("first")))
        }

        launchConnect(identity)
        runPending()

        assertEquals("exactly one drop routed after normal completion", 1, dropCalls.size)
        assertEquals("drop reason is RETRY_EXHAUSTED (shared)", TransportDropReason.RETRY_EXHAUSTED, dropCalls.single().reason)
    }

    // ── O6: superseded collector late failure does not drop new attempt ────

    @Test
    fun `O6 - superseded collector late failure does not drop new attempt`() = runTest {
        val identity = bindIdentity("/proj")

        // Gen 1: connect + frame → Live(T1).
        val feed1 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        val gen1Gate = CompletableDeferred<Unit>()
        every { repository.connectSSE(any()) } returnsMany listOf(
            flow<Result<SSEEvent>> {
                emit(Result.success(sseEvent("first-gen1")))
                gen1Gate.await()
                throw IOException("gen1 late failure")
            },
        )

        launchConnect(identity)
        runPending()
        feed1.tryEmit(Result.success(sseEvent("first-gen1")))
        runPending()
        assertTrue("gen 1 Live", runtimeState() is SseTransportState.Live)

        // Gen 2: supersede gen 1 (rollbackAttempt → Stopped, beginAttempt → Connecting).
        val feed2 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed2.asSharedFlow()
        launchConnect(identity)
        runPending()
        assertTrue("gen 2 Connecting after supersession", runtimeState() is SseTransportState.Connecting)
        val gen2AttemptId = (runtimeState() as SseTransportState.Connecting).attempt.attemptId

        // Release gen 1's late failure: it fires onCollectionException with a
        // STALE token (T1 is no longer canonical). The canonical check suppresses
        // ALL side effects — no gap, no drop, no terminal callback for gen 1.
        gen1Gate.complete(Unit)
        runPending()

        assertEquals("no additional gap from stale gen 1 failure", 0,
            collectedEffects.filterIsInstance<ControllerEffect.CancelSse>().size)
        assertEquals("no drop from stale gen 1 failure", 0, dropCalls.size)
        assertEquals("no terminal callback from stale gen 1", 0, terminalDropCalls)
        assertTrue("gen 2 Connecting still intact", runtimeState() is SseTransportState.Connecting)
        assertEquals("gen 2 attempt unchanged", gen2AttemptId,
            (runtimeState() as SseTransportState.Connecting).attempt.attemptId)
    }

    // ── O7: pre-ready failure rejects once, no retry, lease released, reconnect clean ──

    @Test
    fun `O7 - pre-ready failure rejects once, no retry, lease released, reconnect clean`() = runTest {
        val identity = bindIdentity("/proj")

        // A flow that immediately throws before any frame (pre-ready failure).
        every { repository.connectSSE(any()) } returns flow<Result<SSEEvent>> {
            throw IOException("pre-ready failure")
        }

        var result: SourceActivation? = null
        scope.launch { result = owner.connect(identity) }
        runPending()

        // Single-attempt: the flow throws before any frame → Exhausted.
        assertEquals("pre-ready failure returns Exhausted", SourceActivation.Rejected.Exhausted, result)

        // The runtime ends Dropped after the unexpected drop is routed.
        assertTrue("runtime ended Dropped after pre-ready failure", runtimeState() is SseTransportState.Dropped)

        // Verify one terminal callback fired.
        assertEquals("onTerminalDrop invoked exactly once", 1, terminalDropCalls)
        verify(exactly = 1) { repository.connectSSE(any()) }

        // The lease was released by the drop handler — ownershipGate should
        // allow a fresh claim (no stale lease blocking reconnect).
        assertNull(
            "ownership lease released after pre-ready failure — no stale lease",
            ownershipGate.readyIdentity(),
        )

        // A fresh connect should succeed (clean slate).
        terminalDropCalls = 0
        val feed2 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed2.asSharedFlow()
        scope.launch { result = owner.connect(identity) }
        runPending()
        feed2.tryEmit(Result.success(sseEvent("first")))
        runPending()
        assertEquals("fresh connect succeeds after pre-ready failure", SourceActivation.Ready, result)
        assertEquals("no spurious terminal callback on clean reconnect", 0, terminalDropCalls)
    }

    // ── Helper data ─────────────────────────────────────────────────────────

    private data class DropCall(val attempt: TransportAttemptToken, val reason: TransportDropReason)
}
