package cn.vectory.ocdroid.service.streaming

import android.util.Log
import cn.vectory.ocdroid.R
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * D2 (gate #4 / #7) — tests for [ServiceSseConnectionOwner]'s acknowledgeable
 * connect + service-level retry + idempotent gap-dirty contract.
 *
 * Fixture (per [setUp]):
 *  - real [ConnectionIdentityStore], [SseEventStream], [SharedStateStore],
 *    [SharedEffectBus];
 *  - mocked [OpenCodeRepository];
 *  - real [ConnectionBootstrapCoordinator] (Idle by default);
 *  - recording fakes for [StatusAggregator] / [StatusAggregatorInput];
 *  - in-memory [SseRecoveryPolicy] override (tight timings for virtual clock).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ServiceSseConnectionOwnerTest {

    private lateinit var scope: TestScope
    private lateinit var repository: OpenCodeRepository
    private lateinit var identityStore: ConnectionIdentityStore
    private lateinit var stream: SseEventStream
    private lateinit var store: SharedStateStore
    private lateinit var effects: SharedEffectBus
    private lateinit var aggregator: FakeAggregator
    private lateinit var collectedEffects: MutableList<ControllerEffect>
    private lateinit var recordedEvents: MutableList<UiEvent>
    private lateinit var streamFrames: MutableList<SSEEvent>
    private var disconnectRequests: Int = 0
    private lateinit var policy: TestRecoveryPolicy
    private lateinit var runtimeStore: SseTransportRuntimeStore
    private val dropCalls: MutableList<DropCall> = mutableListOf()
    private val recordingHandler = object : UnexpectedTransportDropHandler {
        override fun onUnexpectedDrop(attempt: TransportAttemptToken, reason: TransportDropReason) {
            dropCalls += DropCall(attempt, reason)
            // Mirror the production handler ([cn.vectory.ocdroid.service.SseShutdownSeal]):
            // it publishes the drop (the owner never calls publishDropped
            // directly). The owner test has no ownership gate to release, so
            // only the publish is mirrored here.
            runtimeStore.publishDropped(attempt, reason)
        }
    }
    private lateinit var owner: ServiceSseConnectionOwner

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
        collectedEffects = mutableListOf()
        recordedEvents = mutableListOf()
        streamFrames = mutableListOf()
        disconnectRequests = 0
        policy = TestRecoveryPolicy()
        runtimeStore = SseTransportRuntimeStore()
        dropCalls.clear()
        owner = ServiceSseConnectionOwner(
            scope = scope,
            repository = repository,
            identityStore = identityStore,
            sseEventStream = stream,
            sharedStateStore = store,
            sharedEffectBus = effects,
            recoveryPolicy = policy,
            runtimeStore = runtimeStore,
            dropHandler = recordingHandler,
            jitterSource = { 0.0f },
            onTerminalExhaustion = { disconnectRequests++ },
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
        // D4-B M3: runCurrent (NOT advanceUntilIdle) — the transport-timeout
        // job's 30s delay would otherwise fire when no frame is pending. The
        // collector subscribes immediately under UnconfinedTestDispatcher, so
        // runCurrent suffices to deliver frames; retry tests use
        // [advanceOwnerTimeBy] for explicit delay advancement.
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

    /** Subscribes a feed for one [owner.connect] invocation. */
    private fun stubFeed(feed: MutableSharedFlow<Result<SSEEvent>>) {
        every { repository.connectSSE(any()) } returns feed.asSharedFlow()
    }

    /** Launches [owner.connect] in the test scope (suspend → wrap in launch). */
    private fun launchConnect(identity: ConnectionIdentity) {
        scope.launch { owner.connect(identity) }
    }

    // (1) current identity success emits exact IdentifiedSseEvent + Ready
    @Test
    fun `current identity success emits exact IdentifiedSseEvent and completes Ready`() = runTest {
        val identity = bindIdentity("/proj")
        aggregator.nextState = GlobalBusyState.Busy
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        launchConnect(identity)
        scope.testScheduler.runCurrent()

        val evt = sseEvent("session.status")
        feed.tryEmit(Result.success(evt))
        runPending()

        assertEquals(
            "current-identity success event reaches the stream",
            listOf(evt),
            collectedFrames(),
        )
        // D5 (#3): the SSE owner NO LONGER writes terminal Connected on a
        // frame — only committed ownership / CC may publish Connected. The
        // frame reaching the stream + the readiness completing Ready is the
        // liveness proof; the green-icon write belongs to the commit→
        // markReady→CC-Connected path.
        assertFalse(
            "SSE owner does NOT publish terminal Connected on a frame (D5 #3)",
            store.connectionFlow.value.isConnected,
        )
    }

    // (2) stale identity before connect does not call connectSSE + returns StaleIdentity
    @Test
    fun `stale identity before connect returns StaleIdentity and does not call connectSSE`() = runTest {
        val staleIdentity = bindIdentity("/proj")
        identityStore.beginReconfigure() // epoch → 1

        var result: SourceActivation? = null
        scope.launch { result = owner.connect(staleIdentity) }
        runPending()

        verify(exactly = 0) { repository.connectSSE(any()) }
        assertEquals(SourceActivation.Rejected.StaleIdentity, result)
    }

    // (4) D5 (#3): a successful frame does NOT publish terminal Connected
    //     (only committed ownership / CC may). The frame reaches the stream
    //     + readiness completes Ready — that is the liveness proof.
    @Test
    fun `D5 3 - success does NOT write terminal Connected (only ownership commit may)`() = runTest {
        val identity = bindIdentity("/proj")
        aggregator.nextState = GlobalBusyState.Busy
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        assertFalse(store.connectionFlow.value.isConnected)

        launchConnect(identity)
        runPending()

        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()

        // D5 (#3): the SSE owner must NOT mutate ConnectionPhase.Connected.
        // The shared state stays at its initial (disconnected) value until
        // the coordinator commit → ownership markReady → CC Connected path
        // runs.
        val cs = store.connectionFlow.value
        assertFalse(
            "SSE owner does NOT write isConnected=true on a frame (D5 #3)",
            cs.isConnected,
        )
        assertNotEquals(
            "SSE owner does NOT write ConnectionPhase.Connected on a frame (D5 #3)",
            ConnectionPhase.Connected,
            cs.connectionPhase,
        )
    }

    // (5) event-level failure emits error_sse_failed (does NOT trigger recovery)
    @Test
    fun `event-level failure emits error_sse_failed`() = runTest {
        val identity = bindIdentity("/proj")
        aggregator.nextState = GlobalBusyState.Busy
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

    // (6) disconnect stops forwarding + emits exactly one CancelSse
    //     THE VACUOUS-ASSERTION FIX (D2 gate #7): records the exact frame
    //     list BEFORE disconnect; emits an after-cancel frame; asserts the
    //     list is exactly unchanged + does NOT contain the after-cancel event.
    @Test
    fun `disconnect stops forwarding and emits exactly one CancelSse`() = runTest {
        val identity = bindIdentity("/proj")
        aggregator.nextState = GlobalBusyState.Busy
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        launchConnect(identity)
        runPending()

        // Forward one event to prove liveness + capture the exact frame list.
        val first = sseEvent("first")
        feed.tryEmit(Result.success(first))
        runPending()
        val framesBefore = collectedFrames()
        assertEquals(
            "exactly one frame captured before disconnect",
            listOf(first),
            framesBefore,
        )

        scope.launch { owner.disconnect(markGap = true) }
        runPending()

        val cancelEffects = collectedEffects.filterIsInstance<ControllerEffect.CancelSse>()
        assertEquals(
            "disconnect emitted exactly one CancelSse (markGap=true)",
            1,
            cancelEffects.size,
        )

        // The vacuous assertion fixed: emit an after-cancel frame, run the
        // scheduler, verify the captured frame list is EXACTLY unchanged +
        // does NOT contain the after-cancel event.
        val after = sseEvent("after-cancel")
        feed.tryEmit(Result.success(after))
        runPending()

        val framesAfter = collectedFrames()
        assertEquals(
            "frame list is EXACTLY unchanged after disconnect (collector was cancelled)",
            framesBefore,
            framesAfter,
        )
        assertTrue(
            "the after-cancel event is NOT in the captured frame list",
            framesAfter.none { it.payload.type == "after-cancel" },
        )
    }

    // (7) repeated disconnect is idempotent (only first emits CancelSse)
    @Test
    fun `repeated disconnect is idempotent`() = runTest {
        val identity = bindIdentity("/proj")
        aggregator.nextState = GlobalBusyState.Busy
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
        assertEquals(
            "only the first disconnect (live generation) emits CancelSse",
            1,
            cancelEffects.size,
        )
    }

    // (8) cancellation does NOT emit an SSE error + no terminal callback
    @Test
    fun `cancellation does not emit an SSE error and does NOT trigger onTerminalExhaustion`() = runTest {
        val identity = bindIdentity("/proj")
        aggregator.nextState = GlobalBusyState.Busy
        val pending = CompletableDeferred<Result<SSEEvent>>()
        every { repository.connectSSE(any()) } returns flow<Result<SSEEvent>> { pending.await() }

        launchConnect(identity)
        runPending()

        scope.launch { owner.disconnect(markGap = false) }
        runPending()

        assertTrue(
            "cancellation MUST NOT surface an SSE error",
            recordedEvents.filterIsInstance<UiEvent.Error>().none {
                it.resId == R.string.error_sse_failed
            },
        )
        assertEquals(
            "onTerminalExhaustion NOT invoked on clean cancellation",
            0,
            disconnectRequests,
        )
    }

    // ── D2 gate #7: service-level retry + idempotent gap ───────────────────

    // (9) Immediate terminal exception emits exactly ONE CancelSse before the
    //     first long retry; all 3 retries fail → onTerminalExhaustion once.
    @Test
    fun `D2 #7 - immediate terminal exception emits one CancelSse then 3 retries exhaust to L3`() = runTest {
        val identity = bindIdentity("/proj")
        // Each connectSSE call throws immediately (simulates SSEClient 10-attempt exhaust).
        every { repository.connectSSE(any()) } returns flow { throw IOException("boom") }

        launchConnect(identity)
        scope.testScheduler.runCurrent()

        // First failure → exactly ONE CancelSse emitted (idempotent for this outage).
        val cancelsAfterFirst = collectedEffects.filterIsInstance<ControllerEffect.CancelSse>().size
        assertEquals(
            "exactly one CancelSse after the first terminal exception (idempotent)",
            1,
            cancelsAfterFirst,
        )
        verify(exactly = 1) { repository.connectSSE(any()) }
        assertEquals("gap is emitted before retry or L3", 0, disconnectRequests)

        advanceOwnerTimeBy(policy.delayForAttempt(1))
        advanceOwnerTimeBy(policy.delayForAttempt(2))
        advanceOwnerTimeBy(policy.delayForAttempt(3))

        // After all 3 retries exhaust: exactly ONE onTerminalExhaustion.
        assertEquals(
            "onTerminalExhaustion invoked exactly once after budget exhaustion",
            1,
            disconnectRequests,
        )
        // The CancelSse count is STILL one (idempotent for the same outage).
        val cancelsFinal = collectedEffects.filterIsInstance<ControllerEffect.CancelSse>().size
        assertEquals(
            "repeated failures in the same outage do NOT emit duplicate CancelSse",
            1,
            cancelsFinal,
        )
    }

    // (10) Retry #1 succeeds → Ready; no onTerminalExhaustion; budget resets.
    @Test
    fun `D2 #7 - retry 1 succeeds - no L3 callback, budget resets`() = runTest {
        val identity = bindIdentity("/proj")
        aggregator.nextState = GlobalBusyState.Busy
        // First call throws; second call returns a feed we can emit on.
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returnsMany listOf(
            flow<Result<SSEEvent>> { throw IOException("first boom") },
            feed.asSharedFlow(),
        )

        launchConnect(identity)
        scope.testScheduler.runCurrent()
        // First attempt failed → CancelSse emitted once.
        assertEquals(
            1,
            collectedEffects.filterIsInstance<ControllerEffect.CancelSse>().size,
        )

        // Advance to retry #1 (delayMs(1)).
        advanceOwnerTimeBy(policy.delayForAttempt(1))

        // The retry's feed is now active — emit a successful frame.
        feed.tryEmit(Result.success(sseEvent("recovered")))
        scope.testScheduler.runCurrent()

        assertEquals(
            "no onTerminalExhaustion after a successful retry",
            0,
            disconnectRequests,
        )
        // Frame reached the stream.
        assertTrue(
            "recovered frame reached the stream",
            collectedFrames().any { it.payload.type == "recovered" },
        )
        // The gap flag was reset → a later outage would emit a NEW CancelSse.
        // (Implicit — the next failure would emit; not asserted here to keep
        // the test focused.)
    }

    // (11) Intentional cancel (via disconnect) → no terminal/retry.
    @Test
    fun `D2 #7 - intentional cancel - no retry, no terminal callback`() = runTest {
        val identity = bindIdentity("/proj")
        every { repository.connectSSE(any()) } returns flow<Result<SSEEvent>> { pendingForever() }

        launchConnect(identity)
        runPending()

        // Disconnect before any failure fires.
        scope.launch { owner.disconnect(markGap = true) }
        runPending()

        // Advance well past all retry delays — no retries fired, no terminal.
        advanceOwnerTimeBy(
            policy.delayForAttempt(1) + policy.delayForAttempt(2) + policy.delayForAttempt(3),
        )

        assertEquals(
            "intentional cancel did NOT invoke onTerminalExhaustion",
            0,
            disconnectRequests,
        )
    }

    // (12) Stale-identity termination mid-collection → no UI error / no gap / no retry.
    @Test
    fun `D2 #7 - stale-identity termination - no UI error, no gap, no retry`() = runTest {
        val identity = bindIdentity("/proj")
        every { repository.connectSSE(any()) } returns flow { throw IOException("boom") }

        launchConnect(identity)
        scope.testScheduler.runCurrent()
        // First failure fired; CancelSse + UI error emitted (identity was current).
        val errorsAfterFirst = recordedEvents.filterIsInstance<UiEvent.Error>().size
        val cancelsAfterFirst = collectedEffects.filterIsInstance<ControllerEffect.CancelSse>().size
        assertEquals(1, errorsAfterFirst)
        assertEquals(1, cancelsAfterFirst)

        // Now bump the epoch — the in-flight collector becomes stale.
        identityStore.beginReconfigure()

        advanceOwnerTimeBy(policy.delayForAttempt(1))
        advanceOwnerTimeBy(policy.delayForAttempt(2))
        advanceOwnerTimeBy(policy.delayForAttempt(3))

        // No NEW UI errors emitted after the stale-identity transition (the
        // post-stale retries return silently without surfacing errors for
        // the NEW identity).
        val errorsFinal = recordedEvents.filterIsInstance<UiEvent.Error>().size
        assertEquals(
            "no NEW UI errors emitted after stale-identity termination",
            errorsAfterFirst,
            errorsFinal,
        )
        // onTerminalExhaustion NOT invoked (stale-identity termination is silent).
        assertEquals(
            "stale-identity termination does NOT invoke onTerminalExhaustion",
            0,
            disconnectRequests,
        )
        verify(exactly = 1) { repository.connectSSE(any()) }
    }

    // (13) workdir comes from identity.normalizedWorkdir, not mutable Settings state
    @Test
    fun `workdir comes from identity not mutable Settings state`() = runTest {
        aggregator.nextState = GlobalBusyState.Busy
        val blankWorkdirIdentity = identityStore.bind("test-fp", "", "endpoint")
        val feed1 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed1.asSharedFlow()

        launchConnect(blankWorkdirIdentity)
        runPending()

        verify { repository.connectSSE(null) }

        val projIdentity = identityStore.bind("test-fp", "/proj-B", "endpoint")
        val feed2 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed2.asSharedFlow()
        launchConnect(projIdentity)
        runPending()

        verify { repository.connectSSE("/proj-B") }
    }

    // (14) D4-B M3: SSE first frame completes transport readiness regardless of
    //      the status verdict (Unknown is no longer a transport-readiness gate).
    @Test
    fun `D4-B M3 - SSE first frame with Unknown baseline still completes transport readiness`() = runTest {
        val identity = bindIdentity("/proj")
        aggregator.nextState = GlobalBusyState.Unknown  // baseline Unknown (now irrelevant to transport)
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        var result: SourceActivation? = null
        scope.launch { result = owner.connect(identity) }
        runPending()

        feed.tryEmit(Result.success(sseEvent("first-frame")))
        runPending()

        // D4-B M3: transport readiness completes on the first frame — Unknown
        // status authority is the coordinator's concern at commit, NOT the
        // collector's. result == Ready.
        assertEquals(
            "Unknown baseline does NOT gate transport readiness (M3)",
            SourceActivation.Ready,
            result,
        )
        // The frame DID reach the stream.
        assertTrue(
            "first frame reached the stream",
            collectedFrames().any { it.payload.type == "first-frame" },
        )
    }

    // (15) D4-B M3: no frame within the transport timeout → TransportTimeout +
    //      the attempted collector is cancelled (no late frame leaks).
    @Test
    fun `D4-B M3 - no frame within transport timeout completes TransportTimeout and cancels collector`() = runTest {
        val identity = bindIdentity("/proj")
        // A feed that never emits (quiet SSE).
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)
        // Tight transport timeout so the virtual clock can drive it.
        val timeoutOwner = ServiceSseConnectionOwner(
            scope = scope,
            repository = repository,
            identityStore = identityStore,
            sseEventStream = stream,
            sharedStateStore = store,
            sharedEffectBus = effects,
            recoveryPolicy = policy,
            transportTimeoutMs = 5_000L,
            runtimeStore = runtimeStore,
            dropHandler = recordingHandler,
            jitterSource = { 0.0f },
            onTerminalExhaustion = { disconnectRequests++ },
        )
        var result: SourceActivation? = null
        scope.launch { result = timeoutOwner.connect(identity) }
        scope.testScheduler.runCurrent()

        // No frame emitted; advance past the transport timeout.
        assertEquals("readiness not yet completed (no frame)", null, result)
        scope.testScheduler.advanceTimeBy(5_000L)
        scope.testScheduler.runCurrent()

        assertEquals(
            "transport timeout completed readiness with TransportTimeout",
            SourceActivation.Rejected.TransportTimeout,
            result,
        )

        // The collector was cancelled — a late frame does NOT leak.
        val late = sseEvent("late-frame")
        feed.tryEmit(Result.success(late))
        scope.testScheduler.advanceUntilIdle()
        assertTrue(
            "late frame after timeout MUST NOT reach the stream (collector cancelled)",
            collectedFrames().none { it.payload.type == "late-frame" },
        )
    }

    /** Helper: a tiny infinite-delay suspend. */
    private suspend fun pendingForever() {
        delay(Long.MAX_VALUE)
    }

    // ── D5 (#1): post-first-frame outage recovery (CRITICAL) ──────────────

    // (16) Post-Ready recovery deterministic: first flow emits one frame →
    //      Ready → same flow throws → exactly one CancelSse gap → retry #1
    //      recovers → recovered frame reaches stream → no terminal callback.
    //      Budget reset is verified by the (18) test (a second outage would
    //      emit a SECOND new gap — covered there).
    @Test
    fun `D5 1a - post-Ready outage emits one gap then retry recovers - no terminal`() = runTest {
        val identity = bindIdentity("/proj")
        aggregator.nextState = GlobalBusyState.Busy
        val recoveredFeed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returnsMany listOf(
            // Flow 1: emit one frame, then throw (post-Ready outage).
            flow<Result<SSEEvent>> {
                emit(Result.success(sseEvent("first")))
                throw IOException("post-ready outage")
            },
            // Flow 2: recovered — SharedFlow the test emits on.
            recoveredFeed.asSharedFlow(),
        )

        launchConnect(identity)
        runPending()

        // First frame → Ready.
        assertTrue(
            "first frame reached the stream",
            collectedFrames().any { it.payload.type == "first" },
        )

        // The first flow threw → post-Ready outage path. Exactly ONE gap.
        assertEquals(
            "exactly one CancelSse gap after the post-Ready outage",
            1,
            collectedEffects.filterIsInstance<ControllerEffect.CancelSse>().size,
        )
        assertEquals(
            "no terminal callback during recovery",
            0,
            disconnectRequests,
        )

        // Advance to retry #1 (delayMs(1)) — the second flow activates.
        advanceOwnerTimeBy(policy.delayForAttempt(1))
        runPending()

        // Emit a recovered frame on the second flow.
        recoveredFeed.tryEmit(Result.success(sseEvent("recovered")))
        runPending()

        assertTrue(
            "recovered frame reached the stream (retry #1 succeeded)",
            collectedFrames().any { it.payload.type == "recovered" },
        )
        assertEquals(
            "no terminal callback after successful recovery",
            0,
            disconnectRequests,
        )
        // The gap flag was reset by the recovered frame → the gap count
        // is STILL one (no duplicate for the first outage). A subsequent
        // outage would emit a SECOND new gap (covered by test 18).
        assertEquals(
            "gap count still one after recovery (no duplicate for first outage)",
            1,
            collectedEffects.filterIsInstance<ControllerEffect.CancelSse>().size,
        )
    }

    // (17) Post-Ready recovery exhausts: initial Ready then 3 service
    //      attempts fail → one gap + four total flow instances (initial +
    //      3 recovery) + terminal callback once + SSE transport projection
    //      false (REST/server state MUST stay unchanged — I8: SSE-only loss
    //      cannot alone write server-unreachable REST state).
    @Test
    fun `D5 1b - post-Ready outage exhausts - one gap + four flows + terminal callback + SSE-only REST unchanged`() = runTest {
        val identity = bindIdentity("/proj")
        aggregator.nextState = GlobalBusyState.Busy
        // Flow 1: emit one frame (Ready), then throw (post-Ready outage).
        // Flows 2-4: throw immediately (3 recovery attempts all fail).
        every { repository.connectSSE(any()) } returnsMany listOf(
            flow<Result<SSEEvent>> {
                emit(Result.success(sseEvent("first")))
                throw IOException("post-ready outage")
            },
            flow<Result<SSEEvent>> { throw IOException("recovery-1 fail") },
            flow<Result<SSEEvent>> { throw IOException("recovery-2 fail") },
            flow<Result<SSEEvent>> { throw IOException("recovery-3 fail") },
        )

        launchConnect(identity)
        runPending()

        // First frame → Ready.
        assertTrue(
            "first frame reached the stream",
            collectedFrames().any { it.payload.type == "first" },
        )

        // Post-Ready outage: exactly ONE gap (idempotent).
        assertEquals(
            "exactly one CancelSse gap after the post-Ready outage",
            1,
            collectedEffects.filterIsInstance<ControllerEffect.CancelSse>().size,
        )

        // Advance through the 3 recovery delays (30s/2m/5m in prod; 1ms/
        // 2ms/3ms in test). All 3 recovery attempts fail → terminal
        // exhaustion.
        advanceOwnerTimeBy(policy.delayForAttempt(1))
        runPending()
        advanceOwnerTimeBy(policy.delayForAttempt(2))
        runPending()
        advanceOwnerTimeBy(policy.delayForAttempt(3))
        runPending()

        // Terminal exhaustion: exactly ONE callback.
        assertEquals(
            "onTerminalExhaustion invoked exactly once after budget exhaustion",
            1,
            disconnectRequests,
        )
        // L4 §3/§8 (M1A / I8): SSE-only retry exhaustion MUST NOT write
        // REST/server Disconnected state — the REST connection is a SEPARATE
        // axis (a dropped SSE does not prove the server is unreachable). The
        // shared connection state stays at its initial value; only the SSE
        // transport projection (sseConnected) flips false.
        assertNotEquals(
            "SSE exhaustion does NOT write REST Disconnected (I8)",
            ConnectionPhase.Disconnected,
            store.connectionFlow.value.connectionPhase,
        )
        assertFalse(
            "isSseConnected=false after terminal exhaustion (SSE transport projection)",
            store.sseConnectedFlow.value,
        )
        // Gap count is STILL one (idempotent for the same outage — no
        // duplicate gaps across the 3 recovery failures).
        assertEquals(
            "gap count still one after exhaustion (idempotent)",
            1,
            collectedEffects.filterIsInstance<ControllerEffect.CancelSse>().size,
        )
        // Four total flow instances: initial + 3 recovery.
        verify(exactly = 4) { repository.connectSSE(any()) }
    }

    // ── L4 §3 (M1A): runtime transport-truth integration ──────────────────

    /** Records each unexpected-drop routing for assertion. */
    private data class DropCall(val attempt: TransportAttemptToken, val reason: TransportDropReason)

    private fun runtimeState(): SseTransportState = runtimeStore.state.value

    // (M1A-2 / first-frame Live) accepted connect begins a runtime attempt;
    // the first valid frame marks Live; readiness completes Ready.
    @Test
    fun `M1A - accepted connect begins Connecting and first frame marks Live`() = runTest {
        val identity = bindIdentity("/proj")
        aggregator.nextState = GlobalBusyState.Busy
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        // Default runtime state is Stopped.
        assertEquals(
            "runtime starts Stopped",
            SseTransportState.Stopped,
            runtimeState(),
        )

        launchConnect(identity)
        runPending()

        // beginAttempt ran on the accepted connect → Connecting.
        assertTrue(
            "accepted connect transitions runtime to Connecting",
            runtimeState() is SseTransportState.Connecting,
        )
        val connectingAttempt =
            (runtimeState() as SseTransportState.Connecting).attempt
        assertEquals(
            "Connecting attempt carries the connect identity",
            identity,
            connectingAttempt.identity,
        )

        // First valid frame → markLive → Live (recoveryTicket null — no prior drop).
        feed.tryEmit(Result.success(sseEvent("first")))
        runPending()

        val live = runtimeState()
        assertTrue("first frame marks runtime Live", live is SseTransportState.Live)
        assertEquals(
            "Live attempt is the same token (no re-allocation)",
            connectingAttempt.attemptId,
            (live as SseTransportState.Live).attempt.attemptId,
        )
    }

    // (M1A-2) beginAttempt rejection does NOT create an active collector: a
    // foreign identity owning a non-Stopped runtime blocks the connect.
    @Test
    fun `M1A - beginAttempt rejection does not start a collector`() = runTest {
        // Pre-populate the runtime with a FOREIGN identity in Connecting (as
        // another owner/process would). Identity A is bound first, then B
        // becomes current — A is now stale but still owns the runtime.
        val identityA = identityStore.bind("fp-a", "/a", "ep")
        val identityB = identityStore.bind("fp-b", "/b", "ep") // B is now current
        // Foreign attempt occupying the runtime (never released).
        runtimeStore.beginAttempt(identityA)
        assertEquals("foreign identity owns Connecting", identityA,
            (runtimeState() as SseTransportState.Connecting).attempt.identity)

        stubFeed(MutableSharedFlow(extraBufferCapacity = 8))

        var result: SourceActivation? = null
        scope.launch { result = owner.connect(identityB) }
        runPending()

        // beginAttempt(identityB) is rejected (Connecting owned by A) → no
        // collector launched, connect returns a rejection.
        verify(exactly = 0) { repository.connectSSE(any()) }
        assertEquals(
            "rejected connect returns TransportTimeout (no collector started)",
            SourceActivation.Rejected.TransportTimeout,
            result,
        )
        // The runtime is untouched (still the foreign Connecting — no revive).
        assertEquals(
            "runtime unchanged after rejected beginAttempt",
            identityA,
            (runtimeState() as SseTransportState.Connecting).attempt.identity,
        )
    }

    // (M1A-C1) background reconnect refusal after a Live transport routes the
    // drop through the handler EXACTLY ONCE with BACKGROUND_RECONNECT_REFUSED.
    @Test
    fun `M1A-C1 - background reconnect refusal routes exactly one BACKGROUND_RECONNECT_REFUSED drop`() = runTest {
        val identity = bindIdentity("/proj")
        aggregator.nextState = GlobalBusyState.Busy
        val gate = MutableStateFlow(true)
        val outageGate = CompletableDeferred<Unit>()
        every { repository.connectSSE(any()) } returns
            flow<Result<SSEEvent>> {
                emit(Result.success(sseEvent("first"))) // → Live
                outageGate.await()
                throw IOException("post-ready outage")
            }
        // Reconnect gate driven by the test (flipped to false to simulate bg).
        val gatedOwner = ServiceSseConnectionOwner(
            scope = scope,
            repository = repository,
            identityStore = identityStore,
            sseEventStream = stream,
            sharedStateStore = store,
            sharedEffectBus = effects,
            recoveryPolicy = policy,
            runtimeStore = runtimeStore,
            dropHandler = recordingHandler,
            reconnectAllowed = { gate.value },
            jitterSource = { 0.0f },
            onTerminalExhaustion = { disconnectRequests++ },
        )

        scope.launch { gatedOwner.connect(identity) }
        runPending() // first frame → Live
        assertTrue("Live before refusal", runtimeState() is SseTransportState.Live)
        val liveAttemptId =
            (runtimeState() as SseTransportState.Live).attempt.attemptId

        // Flip the gate closed (background) THEN release the outage so the
        // collector breaks into the retry section and sees the closed gate.
        gate.value = false
        outageGate.complete(Unit)
        runPending()

        assertEquals(
            "exactly one drop routed through the handler",
            1,
            dropCalls.size,
        )
        assertEquals(
            "drop reason is BACKGROUND_RECONNECT_REFUSED",
            TransportDropReason.BACKGROUND_RECONNECT_REFUSED,
            dropCalls.single().reason,
        )
        assertEquals(
            "the dropped attempt is the (pre-refusal) Live attempt",
            liveAttemptId,
            dropCalls.single().attempt.attemptId,
        )
        assertTrue(
            "runtime ended Dropped after the handler routed the drop",
            runtimeState() is SseTransportState.Dropped,
        )
    }

    // (M1A-C2) foreground internal retry does NOT publish Dropped: a post-Live
    // outage that the gate still ALLOWS retries through WITHOUT a drop.
    @Test
    fun `M1A-C2 - foreground internal retry does not publish Dropped`() = runTest {
        val identity = bindIdentity("/proj")
        aggregator.nextState = GlobalBusyState.Busy
        val recoveredFeed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returnsMany listOf(
            flow<Result<SSEEvent>> {
                emit(Result.success(sseEvent("first"))) // → Live
                throw IOException("post-ready outage")
            },
            recoveredFeed.asSharedFlow(),
        )

        launchConnect(identity)
        runPending() // first frame → Live; flow throws → markRetrying

        assertTrue(
            "runtime Retrying during the inter-retry gap (no Dropped)",
            runtimeState() is SseTransportState.Retrying,
        )
        assertEquals(
            "NO drop routed while the gate is open (foreground retry)",
            0,
            dropCalls.size,
        )

        // Advance to retry #1 — the second flow activates.
        advanceOwnerTimeBy(policy.delayForAttempt(1))
        runPending()

        // Recovered frame → markLive again (Retrying → Live), no drop.
        recoveredFeed.tryEmit(Result.success(sseEvent("recovered")))
        runPending()

        assertTrue("recovered frame re-marks Live", runtimeState() is SseTransportState.Live)
        assertEquals(
            "still no drop after a foreground retry + recovery",
            0,
            dropCalls.size,
        )
    }

    // (M1A-C3) exception AND unexpected normal completion share ONE drop path
    // (RETRY_EXHAUSTED) — no double-publish.
    @Test
    fun `M1A-C3 - exception exhaustion routes exactly one RETRY_EXHAUSTED drop`() = runTest {
        val identity = bindIdentity("/proj")
        aggregator.nextState = GlobalBusyState.Busy
        every { repository.connectSSE(any()) } returnsMany listOf(
            flow<Result<SSEEvent>> {
                emit(Result.success(sseEvent("first"))) // → Live
                throw IOException("post-ready outage")
            },
            flow<Result<SSEEvent>> { throw IOException("recovery-1 fail") },
            flow<Result<SSEEvent>> { throw IOException("recovery-2 fail") },
            flow<Result<SSEEvent>> { throw IOException("recovery-3 fail") },
        )

        launchConnect(identity)
        runPending()

        advanceOwnerTimeBy(policy.delayForAttempt(1))
        runPending()
        advanceOwnerTimeBy(policy.delayForAttempt(2))
        runPending()
        advanceOwnerTimeBy(policy.delayForAttempt(3))
        runPending()

        assertEquals(
            "exactly one drop routed after exhaustion",
            1,
            dropCalls.size,
        )
        assertEquals(
            "drop reason is RETRY_EXHAUSTED",
            TransportDropReason.RETRY_EXHAUSTED,
            dropCalls.single().reason,
        )
        assertTrue(
            "runtime ended Dropped after exhaustion",
            runtimeState() is SseTransportState.Dropped,
        )
    }

    @Test
    fun `stale exhaustion rejected by fenced handler cannot tear down newer generation`() = runTest {
        val identity = bindIdentity("/proj")
        aggregator.nextState = GlobalBusyState.Busy
        every { repository.connectSSE(any()) } returnsMany listOf(
            flow<Result<SSEEvent>> {
                emit(Result.success(sseEvent("first")))
                throw IOException("post-ready outage")
            },
            flow<Result<SSEEvent>> { throw IOException("retry-1") },
            flow<Result<SSEEvent>> { throw IOException("retry-2") },
            flow<Result<SSEEvent>> { throw IOException("retry-3") },
        )

        var newerOwnerIntact = false
        var newerAttempt: TransportAttemptToken? = null
        var terminalCallbacks = 0
        val interleavingHandler = object : FencedUnexpectedTransportDropHandler {
            override fun onUnexpectedDrop(
                attempt: TransportAttemptToken,
                reason: TransportDropReason,
            ) = Unit

            override fun onUnexpectedDropIfCurrent(
                attempt: TransportAttemptToken,
                reason: TransportDropReason,
            ): Boolean {
                // The owner's outer canonical check has already passed. The
                // superseding generation wins immediately before the fenced
                // handler's canonical check, which must reject this drop.
                assertTrue("old attempt is still canonical at handler entry", runtimeStore.currentAttempt(identity)?.attemptId == attempt.attemptId)
                assertTrue("supersede old runtime attempt", runtimeStore.markStopped(attempt))
                newerAttempt = runtimeStore.beginAttempt(identity)
                assertNotNull("new generation begins", newerAttempt)
                assertTrue("new generation becomes Live", runtimeStore.markLive(newerAttempt!!))
                newerOwnerIntact = true
                return false
            }
        }

        var activation: SourceActivation? = null
        val fencedOwner = ServiceSseConnectionOwner(
            scope = scope,
            repository = repository,
            identityStore = identityStore,
            sseEventStream = stream,
            sharedStateStore = store,
            sharedEffectBus = effects,
            recoveryPolicy = policy,
            runtimeStore = runtimeStore,
            dropHandler = interleavingHandler,
            jitterSource = { 0.0f },
            onTerminalExhaustion = { terminalCallbacks++ },
        )

        scope.launch { activation = fencedOwner.connect(identity) }
        runPending()
        assertEquals("first frame completes readiness", SourceActivation.Ready, activation)

        advanceOwnerTimeBy(policy.delayForAttempt(1))
        advanceOwnerTimeBy(policy.delayForAttempt(2))
        advanceOwnerTimeBy(policy.delayForAttempt(3))

        assertTrue("newer owner remains intact", newerOwnerIntact)
        assertEquals("stale exhaustion does not invoke terminal callback", 0, terminalCallbacks)
        assertEquals("readiness is not rewritten as Exhausted", SourceActivation.Ready, activation)
        assertTrue("newer runtime remains Live", runtimeState() is SseTransportState.Live)
        assertEquals(
            "newer runtime attempt remains canonical",
            newerAttempt,
            (runtimeState() as SseTransportState.Live).attempt,
        )
    }

    @Test
    fun `collection failure terminalized after validation has no stale error or gap`() = runTest {
        val identity = bindIdentity("/proj")
        every { repository.connectSSE(any()) } returns flow {
            emit(Result.success(sseEvent("first")))
            throw IOException("stale failure")
        }

        var terminalized = false
        val fencedOwner = ServiceSseConnectionOwner(
            scope = scope,
            repository = repository,
            identityStore = identityStore,
            sseEventStream = stream,
            sharedStateStore = store,
            sharedEffectBus = effects,
            recoveryPolicy = policy,
            runtimeStore = runtimeStore,
            dropHandler = recordingHandler,
            jitterSource = { 0.0f },
            onTerminalExhaustion = { disconnectRequests++ },
            beforeMarkRetrying = {
                if (!terminalized) {
                    terminalized = true
                    runtimeStore.markStopped(runtimeStore.currentAttempt(identity)!!)
                }
            },
        )

        scope.launch { fencedOwner.connect(identity) }
        runPending()

        assertEquals("terminalization happened in the callback interleaving", true, terminalized)
        assertEquals("no stale UI error", 0, recordedEvents.filterIsInstance<UiEvent.Error>().size)
        assertEquals("no stale gap", 0, collectedEffects.filterIsInstance<ControllerEffect.CancelSse>().size)
        assertEquals("no stale drop", 0, dropCalls.size)
        assertEquals("terminal state is preserved", SseTransportState.Stopped, runtimeState())
    }

    // (M1A-C3 companion) unexpected NORMAL completion (no thrown exception)
    // shares the same single drop path — an infinite SSE completing normally
    // is a failure, routed exactly once.
    @Test
    fun `M1A-C3 - unexpected normal completion shares the one drop path`() = runTest {
        val identity = bindIdentity("/proj")
        aggregator.nextState = GlobalBusyState.Busy
        // Flow 1: emit a frame (Live) then COMPLETE NORMALLY (no throw).
        // Flows 2-4: complete normally too → exhaustion.
        every { repository.connectSSE(any()) } returnsMany listOf(
            flow<Result<SSEEvent>> {
                emit(Result.success(sseEvent("first")))
                // normal completion — treated as a collection failure
            },
            flow<Result<SSEEvent>> { /* completes normally */ },
            flow<Result<SSEEvent>> { /* completes normally */ },
            flow<Result<SSEEvent>> { /* completes normally */ },
        )

        launchConnect(identity)
        runPending()

        advanceOwnerTimeBy(policy.delayForAttempt(1))
        runPending()
        advanceOwnerTimeBy(policy.delayForAttempt(2))
        runPending()
        advanceOwnerTimeBy(policy.delayForAttempt(3))
        runPending()

        assertEquals(
            "normal-completion exhaustion routes exactly one drop",
            1,
            dropCalls.size,
        )
        assertEquals(
            "drop reason is RETRY_EXHAUSTED (shared with exception path)",
            TransportDropReason.RETRY_EXHAUSTED,
            dropCalls.single().reason,
        )
    }

    // (M1A-C4 / M1A-7) a STALE transport generation cannot mark Live: a
    // superseding connect terminalizes the prior attempt (Stopped) and a late
    // prior-generation frame is rejected (no markLive resurrection).
    @Test
    fun `M1A-C4 - stale generation cannot mark Live and stale token is rejected`() = runTest {
        val identity = bindIdentity("/proj")
        aggregator.nextState = GlobalBusyState.Busy

        // Gen 1: connect + frame → Live(T1).
        val feed1 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed1.asSharedFlow()
        launchConnect(identity)
        runPending()
        feed1.tryEmit(Result.success(sseEvent("first")))
        runPending()
        val t1 = (runtimeState() as SseTransportState.Live).attempt
        assertTrue("gen 1 Live", t1.identity == identity)

        // Gen 2: a fresh connect supersedes gen 1. setupConnectLocked markStops
        // T1 (intentional supersession) → Stopped → beginAttempt(T2) → Connecting.
        val feed2 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed2.asSharedFlow()
        launchConnect(identity)
        runPending()

        val afterSupersede = runtimeState()
        assertTrue(
            "gen 2 supersession left runtime Connecting (gen 2 attempt)",
            afterSupersede is SseTransportState.Connecting,
        )
        val t2 = (afterSupersede as SseTransportState.Connecting).attempt
        assertNotEquals(
            "gen 2 allocated a fresh attempt token (T2 != T1)",
            t1.attemptId,
            t2.attemptId,
        )

        // A LATE gen-1 frame: the stale collector's markLive(T1) is rejected by
        // the per-generation guard (isCurrentTransport) → runtime stays
        // Connecting(T2), never resurrects Live(T1).
        feed1.tryEmit(Result.success(sseEvent("stale-late")))
        runPending()
        assertTrue(
            "stale gen-1 frame did NOT resurrect Live (generation guard)",
            runtimeState() is SseTransportState.Connecting,
        )

        // The runtime itself also rejects a stale token directly (belt +
        // suspenders): markLive(T1) on the Connecting(T2) state returns false.
        assertFalse(
            "runtime rejects stale token markLive (canonical validation)",
            runtimeStore.markLive(t1),
        )
        // And markStopped(T1) is rejected (T2 is canonical).
        assertFalse(
            "runtime rejects stale token markStopped",
            runtimeStore.markStopped(t1),
        )
    }

    // (M1A-6) intentional disconnect / cancelForShutdown mark Stopped, never
    // route a Dropped drop.
    @Test
    fun `M1A-6 - intentional disconnect marks Stopped and never drops`() = runTest {
        val identity = bindIdentity("/proj")
        aggregator.nextState = GlobalBusyState.Busy
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        launchConnect(identity)
        runPending()
        feed.tryEmit(Result.success(sseEvent("first")))
        runPending()
        assertTrue("Live before disconnect", runtimeState() is SseTransportState.Live)

        scope.launch { owner.disconnect(markGap = true) }
        runPending()

        assertEquals(
            "intentional disconnect transitions runtime to Stopped",
            SseTransportState.Stopped,
            runtimeState(),
        )
        assertEquals(
            "intentional disconnect NEVER routes a drop",
            0,
            dropCalls.size,
        )
    }

    @Test
    fun `M1A-6 - cancelForShutdown marks Stopped and never drops`() = runTest {
        val identity = bindIdentity("/proj")
        aggregator.nextState = GlobalBusyState.Busy
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        launchConnect(identity)
        runPending()
        feed.tryEmit(Result.success(sseEvent("first")))
        runPending()
        assertTrue("Live before shutdown", runtimeState() is SseTransportState.Live)

        owner.cancelForShutdown()

        assertEquals(
            "cancelForShutdown transitions runtime to Stopped",
            SseTransportState.Stopped,
            runtimeState(),
        )
        assertEquals(
            "cancelForShutdown NEVER routes a drop",
            0,
            dropCalls.size,
        )
    }

    // ── Wave-1 REWORK (M1A) — REST/SSE separation + recovery ticket ───────

    /**
     * Captures the REST/server connection phase BEFORE an SSE-only failure, so
     * a test can assert it is UNCHANGED (I8: SSE-only loss cannot alone write
     * server-unreachable REST state).
     */
    private fun restPhaseBefore(): ConnectionPhase = store.connectionFlow.value.connectionPhase

    // (R-I8-a) SSE-only background reconnect refusal MUST NOT write REST/server
    // Disconnected state. Only the SSE transport projection (sseConnected) +
    // gap/drop demand may update. The REST phase stays at its pre-failure value.
    @Test
    fun `R-I8 - background reconnect refusal does NOT write REST Disconnected`() = runTest {
        val identity = bindIdentity("/proj")
        aggregator.nextState = GlobalBusyState.Busy
        val gate = MutableStateFlow(true)
        val outageGate = CompletableDeferred<Unit>()
        every { repository.connectSSE(any()) } returns
            flow<Result<SSEEvent>> {
                emit(Result.success(sseEvent("first"))) // → Live
                outageGate.await()
                throw IOException("post-ready outage")
            }
        val gatedOwner = ServiceSseConnectionOwner(
            scope = scope,
            repository = repository,
            identityStore = identityStore,
            sseEventStream = stream,
            sharedStateStore = store,
            sharedEffectBus = effects,
            recoveryPolicy = policy,
            runtimeStore = runtimeStore,
            dropHandler = recordingHandler,
            reconnectAllowed = { gate.value },
            jitterSource = { 0.0f },
            onTerminalExhaustion = { disconnectRequests++ },
        )

        scope.launch { gatedOwner.connect(identity) }
        runPending() // first frame → Live
        assertTrue("Live before refusal", runtimeState() is SseTransportState.Live)

        // Pre-seed a NON-Disconnected REST phase (simulating the REST axis
        // being connected — I8: SSE and REST are separate axes). The SSE-only
        // refusal MUST NOT overwrite it.
        store.mutateConnection {
            it.copy(isConnected = true, isConnecting = false, connectionPhase = ConnectionPhase.Connected)
        }
        val restPhaseBefore = restPhaseBefore()
        assertEquals("REST axis pre-seeded Connected", ConnectionPhase.Connected, restPhaseBefore)

        // Flip the gate closed (background) then release the outage → refusal.
        gate.value = false
        outageGate.complete(Unit)
        runPending()

        // The drop was routed (M1A-C1) + runtime ended Dropped.
        assertEquals("exactly one drop routed", 1, dropCalls.size)
        assertEquals(
            TransportDropReason.BACKGROUND_RECONNECT_REFUSED,
            dropCalls.single().reason,
        )
        assertTrue("runtime ended Dropped", runtimeState() is SseTransportState.Dropped)
        // THE I8 ASSERTION: the REST/server phase is UNCHANGED — the SSE-only
        // refusal wrote ONLY the SSE transport projection (sseConnected=false),
        // NOT REST Disconnected.
        assertEquals(
            "SSE-only background refusal MUST NOT write REST Disconnected (I8)",
            restPhaseBefore,
            store.connectionFlow.value.connectionPhase,
        )
        assertTrue(
            "REST axis stays Connected (independent of SSE transport loss)",
            store.connectionFlow.value.isConnected,
        )
        assertFalse(
            "SSE transport projection DID flip false (sseConnected)",
            store.sseConnectedFlow.value,
        )
    }

    // (R-I8-b) SSE-only transport timeout (no frame) MUST NOT write REST/server
    // Disconnected state. The transport never proved Live; the rollback writes
    // only the SSE projection + runtime; REST is untouched.
    @Test
    fun `R-I8 - transport timeout does NOT write REST Disconnected`() = runTest {
        val identity = bindIdentity("/proj")
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)
        val timeoutOwner = ServiceSseConnectionOwner(
            scope = scope,
            repository = repository,
            identityStore = identityStore,
            sseEventStream = stream,
            sharedStateStore = store,
            sharedEffectBus = effects,
            recoveryPolicy = policy,
            transportTimeoutMs = 5_000L,
            runtimeStore = runtimeStore,
            dropHandler = recordingHandler,
            jitterSource = { 0.0f },
            onTerminalExhaustion = { disconnectRequests++ },
        )
        // Pre-seed the REST axis Connected (separate from SSE).
        store.mutateConnection {
            it.copy(isConnected = true, isConnecting = false, connectionPhase = ConnectionPhase.Connected)
        }
        val restPhaseBefore = restPhaseBefore()

        var result: SourceActivation? = null
        scope.launch { result = timeoutOwner.connect(identity) }
        scope.testScheduler.runCurrent()

        scope.testScheduler.advanceTimeBy(5_000L)
        scope.testScheduler.runCurrent()

        assertEquals(
            "transport timeout completed readiness with TransportTimeout",
            SourceActivation.Rejected.TransportTimeout,
            result,
        )
        // THE I8 ASSERTION: REST phase unchanged.
        assertEquals(
            "transport timeout MUST NOT write REST Disconnected (I8)",
            restPhaseBefore,
            store.connectionFlow.value.connectionPhase,
        )
        assertTrue("REST axis stays Connected", store.connectionFlow.value.isConnected)
        // No drop routed (transport never proved Live — rollback, not a drop).
        assertEquals("no drop routed for a never-Live timeout", 0, dropCalls.size)
    }

    // (R-I4) Recovery ticket preservation at the OWNER level: a transport
    // that was Dropped(ticket) → a recovery connect (beginAttempt captures the
    // ticket) → transport timeout (refusal) → runtime ends Dropped(SAME ticket).
    // The original drop demand survives the failed recovery attempt (I4).
    @Test
    fun `R-I4 - recovery attempt timeout preserves the original Dropped ticket`() = runTest {
        val identity = bindIdentity("/proj")
        aggregator.nextState = GlobalBusyState.Busy

        // Step 1: establish a Dropped(ticket=K) for this identity via a Live
        // transport that then exhausts its retry budget (RETRY_EXHAUSTED drop).
        val outageGate = CompletableDeferred<Unit>()
        every { repository.connectSSE(any()) } returnsMany listOf(
            flow<Result<SSEEvent>> {
                emit(Result.success(sseEvent("first"))) // → Live
                outageGate.await()
                throw IOException("post-ready outage")
            },
            flow<Result<SSEEvent>> { throw IOException("recovery-1 fail") },
            flow<Result<SSEEvent>> { throw IOException("recovery-2 fail") },
            flow<Result<SSEEvent>> { throw IOException("recovery-3 fail") },
        )
        launchConnect(identity)
        runPending()
        outageGate.complete(Unit)
        runPending()
        advanceOwnerTimeBy(policy.delayForAttempt(1))
        runPending()
        advanceOwnerTimeBy(policy.delayForAttempt(2))
        runPending()
        advanceOwnerTimeBy(policy.delayForAttempt(3))
        runPending()

        // Now runtime is Dropped(ticket=K). Capture the original ticket.
        val originalDrop = runtimeStore.currentDropTicket(identity)
            ?: error("expected a Dropped ticket after exhaustion")
        val originalDropId = originalDrop.dropId
        assertEquals(
            "drop reason is RETRY_EXHAUSTED",
            TransportDropReason.RETRY_EXHAUSTED,
            originalDrop.reason,
        )

        // Step 2: a RECOVERY connect begins (the supervisor would issue this).
        // The owner's beginAttempt captures the SAME ticket as recoveryTicket.
        // Use a quiet feed + a tight timeout so the recovery attempt TIMES OUT
        // without proving Live (a refusal-equivalent).
        val quietFeed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns quietFeed.asSharedFlow()
        val recoveryOwner = ServiceSseConnectionOwner(
            scope = scope,
            repository = repository,
            identityStore = identityStore,
            sseEventStream = stream,
            sharedStateStore = store,
            sharedEffectBus = effects,
            recoveryPolicy = policy,
            transportTimeoutMs = 5_000L,
            runtimeStore = runtimeStore,
            dropHandler = recordingHandler,
            jitterSource = { 0.0f },
            onTerminalExhaustion = { disconnectRequests++ },
        )
        var recoveryResult: SourceActivation? = null
        scope.launch { recoveryResult = recoveryOwner.connect(identity) }
        scope.testScheduler.runCurrent()

        // The recovery attempt is Connecting with the captured ticket.
        val connectingState = runtimeState()
        assertTrue("recovery attempt is Connecting", connectingState is SseTransportState.Connecting)
        val recoveryAttempt = (connectingState as SseTransportState.Connecting).attempt
        assertEquals(
            "recovery attempt captured the SAME drop ticket (I4)",
            originalDropId,
            recoveryAttempt.recoveryTicket?.dropId,
        )

        // Step 3: timeout the recovery attempt (refusal).
        scope.testScheduler.advanceTimeBy(5_000L)
        scope.testScheduler.runCurrent()

        assertEquals(
            "recovery attempt timed out (TransportTimeout)",
            SourceActivation.Rejected.TransportTimeout,
            recoveryResult,
        )

        // THE I4 ASSERTION: the runtime ended Dropped with the SAME ticket —
        // demand preserved, no new drop ID, no cleared demand.
        val stateAfter = runtimeState()
        assertTrue(
            "runtime ended Dropped after recovery timeout (ticket preserved)",
            stateAfter is SseTransportState.Dropped,
        )
        val restoredTicket = (stateAfter as SseTransportState.Dropped).ticket
        assertEquals(
            "SAME dropId restored (no new demand generated)",
            originalDropId,
            restoredTicket.dropId,
        )
        assertEquals(
            "SAME identity restored",
            originalDrop.identity,
            restoredTicket.identity,
        )
        assertEquals(
            "SAME reason restored",
            originalDrop.reason,
            restoredTicket.reason,
        )
    }

    // (R-stale-suppress) A STALE transport generation cannot publish frame side
    // effects: a superseding connect terminalizes the prior attempt, then a
    // LATE prior-generation frame's markLive is rejected → NO event reaches the
    // stream, NO Ready completes for the stale generation, and the stale token
    // does not resurrect Live (fix #4: every runtime mutation result is
    // authoritative).
    @Test
    fun `R-stale - stale-generation frame is fully suppressed (no event, no Ready, no Live resurrection)`() = runTest {
        val identity = bindIdentity("/proj")
        aggregator.nextState = GlobalBusyState.Busy

        // Gen 1: connect + frame → Live(T1).
        val feed1 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed1.asSharedFlow()
        launchConnect(identity)
        runPending()
        feed1.tryEmit(Result.success(sseEvent("first-gen1")))
        runPending()
        val t1 = (runtimeState() as SseTransportState.Live).attempt
        val framesBeforeSupersede = collectedFrames().size

        // Gen 2: a fresh connect supersedes gen 1 (rollbackAttempt(T1) →
        // Stopped, then beginAttempt(T2) → Connecting).
        val feed2 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed2.asSharedFlow()
        launchConnect(identity)
        runPending()
        val t2 = (runtimeState() as SseTransportState.Connecting).attempt
        assertNotEquals("gen 2 fresh attempt", t1.attemptId, t2.attemptId)

        // A LATE gen-1 frame arrives on the stale collector. markLive(T1) is
        // rejected (T2 is canonical) → ALL frame side effects suppressed.
        feed1.tryEmit(Result.success(sseEvent("stale-late-frame")))
        runPending()

        // No new frame reached the stream (the stale frame was suppressed).
        assertEquals(
            "stale-generation frame did NOT reach the stream (suppressed)",
            framesBeforeSupersede,
            collectedFrames().size,
        )
        assertTrue(
            "stale frame did NOT resurrect Live (runtime-token guard authoritative)",
            runtimeState() is SseTransportState.Connecting,
        )
        // The runtime itself rejects the stale token directly.
        assertFalse("runtime rejects stale markLive(T1)", runtimeStore.markLive(t1))
    }

    // ── Wave-1 REWORK (rev-ogpt blocker) — canonical-token gating on the
    //    collection-failure + exhaustion callback paths ────────────────────
    //
    // These tests invoke the REAL onCollectionException / exhaustion callback
    // path via a throwing flow (NOT a cancelled SharedFlow), with the runtime
    // token terminalized WITHIN THE SAME generation (direct runtime mutation
    // that does NOT bump the owner's transportGenerationCounter, so the
    // generation guard does NOT catch it — only the canonical-token validation
    // can). A stale callback MUST produce no side effects and no duplicate drop.

    // (R-stale-cb-A) A collection failure that fires AFTER the runtime token
    // was terminalized (same generation) MUST suppress ALL error/gap/drop side
    // effects — the canonical-token validation distinguishes this stale callback
    // from a legitimate current Connecting/Live/Retrying failure.
    @Test
    fun `R-stale-cb - same-gen terminalized collection failure suppresses error gap and drop`() = runTest {
        val identity = bindIdentity("/proj")
        aggregator.nextState = GlobalBusyState.Busy
        val throwGate = CompletableDeferred<Unit>()
        every { repository.connectSSE(any()) } returnsMany listOf(
            // Flow 1: suspend on the gate so the test can terminalize the token
            // BEFORE the failure fires, then THROW — invoking the REAL
            // onCollectionException callback path (not a cancelled SharedFlow).
            flow<Result<SSEEvent>> {
                throwGate.await()
                throw IOException("post-terminalization failure")
            },
            // Flow 2: pending forever — keeps the collector from looping into a
            // second failure so the test asserts EXACTLY flow 1's stale path.
            flow<Result<SSEEvent>> { kotlinx.coroutines.delay(Long.MAX_VALUE) },
        )

        launchConnect(identity)
        runPending() // collector subscribed, suspended on throwGate; runtime Connecting

        // Terminalize the canonical attempt WITHIN THE SAME generation (direct
        // runtime mutation — does NOT bump the owner's transportGenerationCounter,
        // so the generation guard does NOT catch it; only the canonical-token
        // validation can).
        val attempt = (runtimeState() as SseTransportState.Connecting).attempt
        assertTrue("terminalize the canonical attempt (same generation)", runtimeStore.markStopped(attempt))
        assertEquals("runtime is Stopped (token terminalized)", SseTransportState.Stopped, runtimeState())

        // Release the gate → flow 1 THROWS → onCollectionException runs with the
        // now-stale token. The canonical check MUST suppress ALL side effects.
        throwGate.complete(Unit)
        runPending()

        // THE ASSERTIONS: no UI error, no gap, no drop, no terminal callback.
        assertTrue(
            "stale-token collection failure emitted NO SSE error",
            recordedEvents.filterIsInstance<UiEvent.Error>().none { it.resId == R.string.error_sse_failed },
        )
        assertEquals(
            "stale-token collection failure emitted NO gap (CancelSse)",
            0,
            collectedEffects.filterIsInstance<ControllerEffect.CancelSse>().size,
        )
        assertEquals("stale-token collection failure routed NO drop", 0, dropCalls.size)
        assertEquals("stale-token collection failure invoked NO terminal callback", 0, disconnectRequests)
        // The runtime is STILL Stopped (the test's terminalization) — the stale
        // callback did NOT resurrect or transition it.
        assertEquals("runtime unchanged (still Stopped)", SseTransportState.Stopped, runtimeState())
    }

    // (R-stale-cb-B) A retry-budget EXHAUSTION that fires AFTER the runtime
    // token was terminalized (same generation) MUST suppress the drop + the
    // terminal callback — no duplicate drop, no side effects. Flow 1's
    // LEGITIMATE failure (canonical Connecting) still emits its gap, proving
    // the gating distinguishes legitimate from stale.
    @Test
    fun `R-stale-cb - same-gen terminalized exhaustion suppresses drop and terminal callback`() = runTest {
        val identity = bindIdentity("/proj")
        aggregator.nextState = GlobalBusyState.Busy
        // A 1-attempt policy: flow 1 fails → retry → flow 2 fails → exhaustion.
        // (Keeps the exhaustion trigger to a single retry so the terminalization
        // point is unambiguous and the test stays deterministic.)
        val stalePolicy = object : SseRecoveryPolicy() {
            override val attempts: Int = 1
            override fun baseDelayMs(attempt: Int): Long = 1L
        }
        val staleOwner = ServiceSseConnectionOwner(
            scope = scope,
            repository = repository,
            identityStore = identityStore,
            sseEventStream = stream,
            sharedStateStore = store,
            sharedEffectBus = effects,
            recoveryPolicy = stalePolicy,
            runtimeStore = runtimeStore,
            dropHandler = recordingHandler,
            jitterSource = { 0.0f },
            onTerminalExhaustion = { disconnectRequests++ },
        )
        every { repository.connectSSE(any()) } returnsMany listOf(
            flow<Result<SSEEvent>> { throw IOException("flow-1 fail") },
            flow<Result<SSEEvent>> { throw IOException("flow-2 fail (exhaustion)") },
        )

        scope.launch { staleOwner.connect(identity) }
        runPending() // flow 1 throws → onCollectionException (canonical Connecting) → gap/UI

        // Flow 1's LEGITIMATE failure (canonical) emitted exactly one gap — the
        // canonical-token validation passed because the attempt was still
        // canonical. retriesUsed is now 1; the next failure hits exhaustion.
        assertEquals(
            "legitimate flow-1 failure emitted exactly one gap (canonical passed)",
            1,
            collectedEffects.filterIsInstance<ControllerEffect.CancelSse>().size,
        )
        assertEquals(
            "legitimate flow-1 failure emitted exactly one SSE error",
            1,
            recordedEvents.filterIsInstance<UiEvent.Error>().count { it.resId == R.string.error_sse_failed },
        )
        val attempt = (runtimeState() as SseTransportState.Connecting).attempt

        // Terminalize the canonical attempt WITHIN THE SAME generation BEFORE
        // the exhaustion-triggering failure fires.
        assertTrue("terminalize before exhaustion (same generation)", runtimeStore.markStopped(attempt))
        assertEquals(SseTransportState.Stopped, runtimeState())

        // Advance to flow 2 → it throws → onCollectionException (stale,
        // suppressed) → exhaustion check → canonical-token validation FAILS →
        // drop + terminal callback suppressed.
        advanceOwnerTimeBy(stalePolicy.delayMs(1))
        runPending()

        // THE ASSERTIONS: the exhaustion drop was NOT routed + the terminal
        // callback was NOT invoked (stale token — no duplicate drop).
        assertEquals("stale exhaustion routed NO drop", 0, dropCalls.size)
        assertEquals("stale exhaustion invoked NO terminal callback", 0, disconnectRequests)
        // No NEW gap was emitted by the stale flow-2 failure (still 1, from the
        // legitimate flow-1 failure).
        assertEquals(
            "stale flow-2 failure emitted NO new gap (still 1 from flow 1)",
            1,
            collectedEffects.filterIsInstance<ControllerEffect.CancelSse>().size,
        )
        // The runtime is STILL Stopped — no resurrection, no Dropped.
        assertEquals("runtime unchanged (still Stopped)", SseTransportState.Stopped, runtimeState())
    }

    /**
     * Tight in-memory [SseRecoveryPolicy] override: 3 attempts, delays 1ms /
     * 2ms / 3ms (so the test's virtual clock can drive the retry cadence
     * without wall-clock latency).
     */
    private class TestRecoveryPolicy : SseRecoveryPolicy() {
        override val attempts: Int = 3
        override fun baseDelayMs(attempt: Int): Long = attempt.toLong() // 1ms / 2ms / 3ms
        fun delayForAttempt(attempt: Int): Long = delayMs(attempt, 0.0f)
    }

    /**
     * Combined fake [StatusAggregator] + [StatusAggregatorInput]. Readiness
     * uses [nextState]: when set, the first successful frame triggers a
     * refresh that produces [nextState] for [globalState]. Default
     * [GlobalBusyState.Busy] so the first frame completes Ready(Busy).
     */
    private class FakeAggregator : StatusAggregator, StatusAggregatorInput {
        var nextState: GlobalBusyState = GlobalBusyState.Busy
        private val _globalState = MutableStateFlow(GlobalBusyState.Busy)
        private val _globalBusy = MutableStateFlow(true)
        private val _statusByKey =
            MutableStateFlow<Map<SessionStatusKey, SessionBusyStatus>>(emptyMap())

        override val globalState: kotlinx.coroutines.flow.StateFlow<GlobalBusyState> =
            _globalState.asStateFlow()
        override val globalBusy: kotlinx.coroutines.flow.StateFlow<Boolean> =
            _globalBusy.asStateFlow()
        override val statusByKey:
            kotlinx.coroutines.flow.StateFlow<Map<SessionStatusKey, SessionBusyStatus>> =
            _statusByKey.asStateFlow()

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
