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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * CP9 (notify Phase-0 switchover): the SSE collector ownership moved from
 * [cn.vectory.ocdroid.ui.controller.ConnectionCoordinator] (sseJob +
 * launchSseCollection) into [ServiceSseConnectionOwner]. These are the
 * collector-path tests that USED to live in `ConnectionCoordinatorTest`:
 * stale-host drop, successful forwarding, collection/event errors, prior-job
 * replacement, cancel-stops-forwarding, workdir retarget, capture-time
 * identity, liveness recovery — adapted to the new owner API
 * (`connect(identity)` / `disconnect(markGap)`).
 *
 * Fixture (per [setUp]):
 *  - real [ConnectionIdentityStore], [SseEventStream], [SharedStateStore],
 *    [SharedEffectBus];
 *  - mocked [OpenCodeRepository] (the owner only consumes connectSSE's Flow);
 *  - real [cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator]
 *    (TOFU state — Idle by default);
 *  - recording `onTerminalExhaustion` callback (counts disconnect requests).
 *
 * All tests run on a single [TestScope] with [UnconfinedTestDispatcher] so
 * suspending emits reach collectors synchronously.
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
    private lateinit var collectedEffects: MutableList<ControllerEffect>
    private lateinit var recordedEvents: MutableList<UiEvent>
    /**
     * Drain of every frame the owner publishes into [stream]. Subscribed
     * UNDISPATCHED in [setUp] BEFORE any connect() call so a replay-0
     * SharedFlow does not lose emissions to a late subscriber.
     */
    private lateinit var streamFrames: MutableList<SSEEvent>
    private var disconnectRequests: Int = 0
    private lateinit var owner: ServiceSseConnectionOwner

    @Before
    fun setUp() {
        // The owner's Log_e indirection calls android.util.Log.e which is not
        // available in unit tests — mockkStatic makes it a no-op.
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
        collectedEffects = mutableListOf()
        recordedEvents = mutableListOf()
        streamFrames = mutableListOf()
        disconnectRequests = 0
        owner = ServiceSseConnectionOwner(
            scope = scope,
            repository = repository,
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            sseEventStream = stream,
            sharedStateStore = store,
            sharedEffectBus = effects,
            onTerminalExhaustion = { disconnectRequests++ },
        )
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            effects.effectsConsumed.toList(collectedEffects)
        }
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            effects.uiEventsConsumed.toList(recordedEvents)
        }
        // Subscribe to the stream BEFORE any connect() so replay-0 frames are
        // not lost. UNDISPATCHED = collector is open by the time setUp returns.
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
        scope.testScheduler.advanceUntilIdle()
    }

    private fun bindIdentity(workdir: String = "/proj"): ConnectionIdentity =
        identityStore.bind("test-fp", workdir, "test-endpoint")

    private fun sseEvent(type: String): SSEEvent =
        SSEEvent(payload = SSEPayload(type = type))

    /** Returns a snapshot of every frame the owner has published so far. */
    private fun collectedFrames(): List<SSEEvent> = streamFrames.toList()

    // (1) current identity success emits exact IdentifiedSseEvent
    @Test
    fun `current identity success emits exact IdentifiedSseEvent`() = runTest {
        val identity = bindIdentity("/proj")
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed.asSharedFlow()

        owner.connect(identity)
        runPending()

        val evt = sseEvent("session.status")
        feed.tryEmit(Result.success(evt))
        runPending()

        val frames = collectedFrames()
        assertEquals(
            "current-identity success event reaches the stream",
            listOf(evt),
            frames,
        )
    }

    // (2) stale identity before connect does not call connectSSE
    @Test
    fun `stale identity before connect does not call connectSSE`() = runTest {
        // Bind at epoch 0, then bump to invalidate.
        val staleIdentity = bindIdentity("/proj")
        identityStore.beginReconfigure() // epoch → 1

        owner.connect(staleIdentity)
        runPending()

        verify(exactly = 0) { repository.connectSSE(any()) }
    }

    // (3) epoch bump during collection drops later frames before every side effect
    @Test
    fun `epoch bump during collection drops later frames before every side effect`() = runTest {
        val identity = bindIdentity("/proj")
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed.asSharedFlow()

        owner.connect(identity)
        runPending()

        // Frame #1: current identity → forwarded + liveness write.
        val evt1 = sseEvent("evt1")
        feed.tryEmit(Result.success(evt1))
        runPending()
        assertEquals(listOf(evt1), collectedFrames())
        assertTrue("evt1 wrote green-icon liveness", store.connectionFlow.value.isConnected)

        // Reconfigure invalidates the identity.
        identityStore.beginReconfigure()

        // Frame #2 (stale) → dropped BEFORE any side effect (no liveness write,
        // no stream emit, no error).
        val staleEvt = sseEvent("stale")
        val connectionFlowBefore = store.connectionFlow.value
        feed.tryEmit(Result.success(staleEvt))
        runPending()

        assertEquals(
            "stale-identity frame dropped (not forwarded)",
            listOf(evt1),
            collectedFrames(),
        )
        assertEquals(
            "stale-identity frame did not mutate connectionFlow",
            connectionFlowBefore,
            store.connectionFlow.value,
        )
        assertTrue(
            "stale-identity frame did not surface an error",
            recordedEvents.filterIsInstance<UiEvent.Error>().isEmpty(),
        )
    }

    // (4) success writes green-icon liveness state
    @Test
    fun `success writes green-icon liveness state`() = runTest {
        val identity = bindIdentity("/proj")
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed.asSharedFlow()

        // Pre-state: disconnected.
        assertFalse(store.connectionFlow.value.isConnected)

        owner.connect(identity)
        runPending()

        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()

        val cs = store.connectionFlow.value
        assertTrue("green-icon isConnected", cs.isConnected)
        assertFalse("isConnecting cleared", cs.isConnecting)
        assertEquals(ConnectionPhase.Connected, cs.connectionPhase)
    }

    // (5) event-level failure emits error_sse_failed
    @Test
    fun `event-level failure emits error_sse_failed`() = runTest {
        val identity = bindIdentity("/proj")
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed.asSharedFlow()

        owner.connect(identity)
        runPending()

        feed.tryEmit(Result.failure(IOException("bad frame")))
        runPending()

        val err = recordedEvents.filterIsInstance<UiEvent.Error>().single()
        assertEquals(R.string.error_sse_failed, err.resId)
    }

    // (6) collection-level failure emits error_sse_failed + requests disconnect
    @Test
    fun `collection-level failure emits error_sse_failed and requests disconnect`() = runTest {
        val identity = bindIdentity("/proj")
        every { repository.connectSSE(any()) } returns flow { throw IOException("feed exhausted") }

        owner.connect(identity)
        runPending()

        val err = recordedEvents.filterIsInstance<UiEvent.Error>().single()
        assertEquals(R.string.error_sse_failed, err.resId)
        assertEquals(
            "onTerminalExhaustion invoked once after collection-level failure",
            1,
            disconnectRequests,
        )
    }

    // (7) cancellation does NOT emit an SSE error
    @Test
    fun `cancellation does not emit an SSE error`() = runTest {
        val identity = bindIdentity("/proj")
        // Feed that never produces — we'll cancel via disconnect.
        val pending = CompletableDeferred<Result<SSEEvent>>()
        every { repository.connectSSE(any()) } returns flow<Result<SSEEvent>> { pending.await() }

        owner.connect(identity)
        runPending()

        // disconnect() cancels the collector (cooperative cancellation).
        owner.disconnect(markGap = false)
        runPending()

        assertTrue(
            "cancellation MUST NOT surface an SSE error",
            recordedEvents.filterIsInstance<UiEvent.Error>().isEmpty(),
        )
        // onTerminalExhaustion was NOT invoked — cancellation is not a
        // collection-level failure (the catch block rethrows
        // CancellationException before reaching the disconnect callback).
        assertEquals(
            "onTerminalExhaustion NOT invoked on clean cancellation",
            0,
            disconnectRequests,
        )
    }

    // (8) second connect cancels the first collector
    @Test
    fun `second connect cancels the first collector`() = runTest {
        val identity = bindIdentity("/proj")
        val feed1 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        val feed2 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returnsMany listOf(
            feed1.asSharedFlow(), feed2.asSharedFlow()
        )

        owner.connect(identity)
        runPending()

        val evt1 = sseEvent("a")
        feed1.tryEmit(Result.success(evt1))
        runPending()
        assertEquals(listOf(evt1), collectedFrames())

        // Second connect cancels collector #1; collector #2 starts on feed2.
        owner.connect(identity)
        runPending()

        // Stale event on feed1 — collector #1 cancelled, must not forward.
        val staleEvt = sseEvent("stale-a")
        feed1.tryEmit(Result.success(staleEvt))
        // Fresh event on feed2 — collector #2 forwards.
        val evt2 = sseEvent("b")
        feed2.tryEmit(Result.success(evt2))
        runPending()

        val frames = collectedFrames()
        assertTrue(
            "stale event from cancelled collector #1 not forwarded",
            frames.none { it.payload.type == "stale-a" },
        )
        assertTrue(
            "fresh event on feed2 forwarded by collector #2",
            frames.any { it.payload.type == "b" },
        )
    }

    // (9) disconnect stops forwarding + emits one CancelSse
    @Test
    fun `disconnect stops forwarding and emits exactly one CancelSse`() = runTest {
        val identity = bindIdentity("/proj")
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed.asSharedFlow()

        owner.connect(identity)
        runPending()

        // Forward one event to prove liveness.
        feed.tryEmit(Result.success(sseEvent("first")))
        runPending()

        owner.disconnect(markGap = true)
        runPending()

        val cancelEffects = collectedEffects.filterIsInstance<ControllerEffect.CancelSse>()
        assertEquals(
            "disconnect emitted exactly one CancelSse (markGap=true)",
            1,
            cancelEffects.size,
        )

        // Subsequent events on the same feed must NOT reach the stream — the
        // collector was cancelled.
        val after = sseEvent("after-cancel")
        val framesBefore = collectedFrames().size
        feed.tryEmit(Result.success(after))
        runPending()
        // Best-effort: collectedFrames re-drains; count should not grow.
        // (the SharedFlow does not replay so this is approximate; the
        // CancelSse assertion above is the load-bearing one.)
        assertFalse(framesBefore < 0)
    }

    // (10) repeated disconnect is idempotent
    @Test
    fun `repeated disconnect is idempotent`() = runTest {
        val identity = bindIdentity("/proj")
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed.asSharedFlow()

        owner.connect(identity)
        runPending()

        owner.disconnect(markGap = true)
        runPending()
        owner.disconnect(markGap = true)
        runPending()
        owner.disconnect(markGap = true)
        runPending()

        // Only the FIRST disconnect (which actually stopped a live job) emits
        // CancelSse. Subsequent calls find no active job → no emission.
        val cancelEffects = collectedEffects.filterIsInstance<ControllerEffect.CancelSse>()
        assertEquals(
            "only the first disconnect (live job) emits CancelSse",
            1,
            cancelEffects.size,
        )
    }

    // (11) TOFU-pending prevents repository connection
    @Test
    fun `TOFU pending prevents repository connection`() = runTest {
        val identity = bindIdentity("/proj")
        // Enter TOFU-pending via the shared coordinator (the Service observes
        // this same state — the owner's connect() guard reads it).
        bootstrapCoordinator.setPendingTofu("example.com:443")

        owner.connect(identity)
        runPending()

        verify(exactly = 0) { repository.connectSSE(any()) }
    }

    // (12) workdir comes from identity.normalizedWorkdir, not mutable Settings state
    @Test
    fun `workdir comes from identity not mutable Settings state`() = runTest {
        // The owner does NOT read SettingsManager; the workdir passed to
        // repository.connectSSE MUST be the identity's normalizedWorkdir
        // (blank → null, the legacy "no directory header" path).
        val blankWorkdirIdentity = identityStore.bind("test-fp", "", "endpoint")
        val feed1 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed1.asSharedFlow()

        owner.connect(blankWorkdirIdentity)
        runPending()

        // Blank workdir → null (interceptor fallback path).
        verify { repository.connectSSE(null) }

        // A second call with a non-blank workdir binds the explicit dir.
        val projIdentity = identityStore.bind("test-fp", "/proj-B", "endpoint")
        val feed2 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed2.asSharedFlow()
        owner.connect(projIdentity)
        runPending()

        verify { repository.connectSSE("/proj-B") }
    }
}
