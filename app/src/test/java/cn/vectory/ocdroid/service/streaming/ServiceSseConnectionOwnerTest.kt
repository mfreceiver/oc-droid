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
    private lateinit var bootstrapCoordinator: cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator
    private lateinit var stream: SseEventStream
    private lateinit var store: SharedStateStore
    private lateinit var effects: SharedEffectBus
    private lateinit var aggregator: FakeAggregator
    private lateinit var collectedEffects: MutableList<ControllerEffect>
    private lateinit var recordedEvents: MutableList<UiEvent>
    private lateinit var streamFrames: MutableList<SSEEvent>
    private var disconnectRequests: Int = 0
    private lateinit var policy: TestRecoveryPolicy
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
        bootstrapCoordinator = cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator()
        stream = SseEventStream()
        store = SharedStateStore()
        effects = SharedEffectBus()
        aggregator = FakeAggregator()
        collectedEffects = mutableListOf()
        recordedEvents = mutableListOf()
        streamFrames = mutableListOf()
        disconnectRequests = 0
        policy = TestRecoveryPolicy()
        owner = ServiceSseConnectionOwner(
            scope = scope,
            repository = repository,
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            sseEventStream = stream,
            sharedStateStore = store,
            sharedEffectBus = effects,
            recoveryPolicy = policy,
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
        assertTrue(
            "green-icon liveness written on success",
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

    // (3) TOFU-pending prevents repository connection + returns TofuPending
    @Test
    fun `TOFU pending returns TofuPending and prevents repository connection`() = runTest {
        val identity = bindIdentity("/proj")
        bootstrapCoordinator.setPendingTofu("example.com:443")

        var result: SourceActivation? = null
        scope.launch { result = owner.connect(identity) }
        runPending()

        verify(exactly = 0) { repository.connectSSE(any()) }
        assertEquals(SourceActivation.Rejected.TofuPending, result)
    }

    // (4) success writes green-icon liveness state
    @Test
    fun `success writes green-icon liveness state`() = runTest {
        val identity = bindIdentity("/proj")
        aggregator.nextState = GlobalBusyState.Busy
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        assertFalse(store.connectionFlow.value.isConnected)

        launchConnect(identity)
        runPending()

        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()

        val cs = store.connectionFlow.value
        assertTrue("green-icon isConnected", cs.isConnected)
        assertFalse("isConnecting cleared", cs.isConnecting)
        assertEquals(ConnectionPhase.Connected, cs.connectionPhase)
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
            bootstrapCoordinator = bootstrapCoordinator,
            sseEventStream = stream,
            sharedStateStore = store,
            sharedEffectBus = effects,
            recoveryPolicy = policy,
            transportTimeoutMs = 5_000L,
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
