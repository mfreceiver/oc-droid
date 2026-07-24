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
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * §breathing-indicator (item ①): tests for the OBSERVABLE SSE-transport-up
 * signal on [ServiceSseConnectionOwner] (exposed as
 * [SharedStateStore.sseConnectedFlow]).
 *
 * Contract:
 *  - `true` set on the first valid current-identity frame (transport readiness).
 *  - `true` re-asserted by a RECOVERED frame after a post-Ready retry gap.
 *  - `false` on post-Ready outage (inter-retry gap — the UI must not lie).
 *  - `false` on intentional disconnect (closingGeneration) — NOT a false outage
 *    (gap/recovery are separate); the flag just reflects "stream down".
 *  - `false` on terminal exhaustion.
 *  - `false` on service destruction ([cancelForShutdown]); a NEW owner reading
 *    the SAME @Singleton store field observes the persisted value (survives
 *    service recreation).
 *  - A STALE collector (superseded by a newer connect / disconnect) is a NO-OP:
 *    its `setSseConnected` write is dropped by the generation guard, so it can
 *    never flip the flag after replacement.
 *  - INDEPENDENT of [cn.vectory.ocdroid.ui.ConnectionState.isConnected]: a frame
 *    sets `isSseConnected=true` while `isConnected` stays false (health-settle
 *    is a separate axis).
 *
 * Fixture mirrors [ServiceSseConnectionOwnerResyncTest] / [ServiceSseConnectionOwnerTest]
 * (mocked repository + real identity store / event stream / store).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ServiceSseConnectionOwnerSseConnectedTest {

    private lateinit var scope: TestScope
    private lateinit var repository: OpenCodeRepository
    private lateinit var identityStore: ConnectionIdentityStore
    private lateinit var bootstrapCoordinator: cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator
    private lateinit var stream: SseEventStream
    private lateinit var store: SharedStateStore
    private lateinit var effects: SharedEffectBus
    private lateinit var aggregator: FakeAggregator
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
            onTerminalExhaustion = {},
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

    private fun advanceOwnerTimeBy(delayMs: Long) {
        scope.testScheduler.advanceTimeBy(delayMs)
        scope.testScheduler.runCurrent()
    }

    /** The flag under test. */
    private fun sseConnected(): Boolean = store.sseConnectedFlow.value

    // ── (1) connect → true on first valid current-identity frame ────────────

    @Test
    fun `first valid frame sets isSseConnected true (readiness)`() = runTest {
        val identity = bindIdentity()
        aggregator.nextState = GlobalBusyState.Busy
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        assertFalse("flag starts false", sseConnected())

        scope.launch { owner.connect(identity) }
        runPending()
        assertFalse("flag still false before any frame", sseConnected())

        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()

        assertTrue(
            "first valid current-identity frame MUST set isSseConnected=true",
            sseConnected(),
        )
    }

    // ── independence from ConnectionState.isConnected ───────────────────────

    @Test
    fun `isSseConnected is INDEPENDENT of ConnectionState isConnected`() = runTest {
        // A frame sets isSseConnected=true but MUST NOT publish terminal
        // Connected (D5 #3 — only committed ownership / CC may). The two axes
        // stay decoupled: transport-up vs health-settle.
        val identity = bindIdentity()
        aggregator.nextState = GlobalBusyState.Busy
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        scope.launch { owner.connect(identity) }
        runPending()
        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()

        assertTrue("transport-up flag is true after a frame", sseConnected())
        assertFalse(
            "health-settle isConnected MUST stay false (D5 #3) — independent axis",
            store.connectionFlow.value.isConnected,
        )
    }

    // ── (2) stale-generation frame after replacement is a NO-OP ─────────────

    @Test
    fun `stale-generation frame after replacement does NOT flip the flag`() = runTest {
        // Gen 1: connect + first frame → true.
        val identity = bindIdentity()
        aggregator.nextState = GlobalBusyState.Busy
        val feed1 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed1.asSharedFlow()

        scope.launch { owner.connect(identity) }
        runPending()
        feed1.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertTrue("gen 1 frame set the flag true", sseConnected())

        // Gen 2: a fresh connect supersedes gen 1. setupConnectLocked marks
        // gen 1 closing + bumps the counter, so the flag drops to false
        // (inter-reconfigure gap) until gen 2's first frame.
        val feed2 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed2.asSharedFlow()
        scope.launch { owner.connect(identity) }
        runPending()

        assertFalse(
            "supersession drops the flag (inter-reconfigure gap)",
            sseConnected(),
        )

        // Now emit a LATE frame on the STALE gen-1 feed. The gen-1 collector's
        // setSseConnected(true, gen=1) MUST be a no-op (generation guard) —
        // it cannot resurrect the flag for the dead generation.
        feed1.tryEmit(Result.success(sseEvent("stale-late-frame")))
        runPending()
        assertFalse(
            "stale gen-1 frame MUST NOT flip the flag (generation guard no-op)",
            sseConnected(),
        )

        // Gen 2's first frame re-asserts true.
        feed2.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertTrue(
            "gen 2 first frame re-asserts isSseConnected=true",
            sseConnected(),
        )
    }

    // ── (3) post-Ready outage → false ──────────────────────────────────────

    @Test
    fun `post-Ready outage drops isSseConnected false during the inter-retry gap`() = runTest {
        val identity = bindIdentity()
        aggregator.nextState = GlobalBusyState.Busy
        // Flow 1: emit one frame (Ready, flag→true), then SUSPEND on the gate
        // so the "true" window is OBSERVABLE, then throw (post-Ready outage).
        // Flow 2: recovered — a feed the test emits on.
        //
        // The gate is required because under UnconfinedTestDispatcher an atomic
        // `flow { emit(first); throw }` processes the frame AND the throw in ONE
        // dispatch tick (SseEventStream's SharedFlow emit never suspends with no
        // subscribers + a 256 buffer) — the transient "true" would already have
        // reverted to "false" by the time runPending() returns. Gating splits
        // the frame and the outage into separate ticks so the true→false FLIP
        // is observable (same pattern as the resync mutex-serialization test).
        val outageGate = CompletableDeferred<Unit>()
        val recoveredFeed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returnsMany listOf(
            flow<Result<SSEEvent>> {
                emit(Result.success(sseEvent("first")))
                outageGate.await()
                throw IOException("post-ready outage")
            },
            recoveredFeed.asSharedFlow(),
        )

        scope.launch { owner.connect(identity) }
        runPending() // flow 1 emits "first" → flag true → suspends on outageGate

        assertTrue(
            "first frame set the flag true before the outage",
            sseConnected(),
        )

        // Release the gate → flow 1 throws → onCollectionException sets false.
        outageGate.complete(Unit)
        runPending()
        assertFalse(
            "post-Ready outage drops the flag false (inter-retry gap)",
            sseConnected(),
        )

        // Advance to retry #1 (delayMs(1)) — the second flow activates.
        advanceOwnerTimeBy(policy.delayForAttempt(1))
        runPending()
        assertFalse("flag still false during the retry gap", sseConnected())

        // A recovered frame re-asserts true.
        recoveredFeed.tryEmit(Result.success(sseEvent("recovered")))
        runPending()
        assertTrue(
            "recovered frame re-asserts isSseConnected=true",
            sseConnected(),
        )
    }

    // ── (4) intentional disconnect (closingGeneration) → false ──────────────

    @Test
    fun `intentional disconnect sets isSseConnected false (closingGeneration - not a false outage)`() = runTest {
        val identity = bindIdentity()
        aggregator.nextState = GlobalBusyState.Busy
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        scope.launch { owner.connect(identity) }
        runPending()
        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertTrue("connected before disconnect", sseConnected())

        // Intentional disconnect → flag false (transport torn down). This is
        // NOT a false outage: closingGeneration suppresses gap/recovery, but
        // the transport-up flag honestly reports "stream down".
        scope.launch { owner.disconnect(markGap = true) }
        runPending()
        assertFalse(
            "intentional disconnect drops the flag false",
            sseConnected(),
        )

        // A late frame on the disconnected feed cannot resurrect the flag
        // (the generation was superseded by disconnect's counter bump).
        feed.tryEmit(Result.success(sseEvent("late-after-disconnect")))
        runPending()
        assertFalse(
            "late frame after disconnect MUST NOT resurrect the flag",
            sseConnected(),
        )
    }

    @Test
    fun `disconnect before any frame keeps the flag false (no false-ready)`() = runTest {
        // A disconnect that fires before the first frame MUST NOT leave the
        // flag stuck true (it was never set). Guards the "no frame → never
        // true" invariant on the closing path.
        val identity = bindIdentity()
        aggregator.nextState = GlobalBusyState.Busy
        every { repository.connectSSE(any()) } returns
            flow<Result<SSEEvent>> { kotlinx.coroutines.delay(Long.MAX_VALUE) }

        scope.launch { owner.connect(identity) }
        runPending()
        assertFalse("flag false before any frame", sseConnected())

        scope.launch { owner.disconnect(markGap = false) }
        runPending()
        assertFalse("flag stays false after pre-frame disconnect", sseConnected())
    }

    // ── (5) terminal exhaustion → false ────────────────────────────────────

    @Test
    fun `terminal exhaustion sets isSseConnected false`() = runTest {
        val identity = bindIdentity()
        aggregator.nextState = GlobalBusyState.Busy
        // Flow 1: emit one frame (Ready, flag→true), then SUSPEND on the gate
        // so the "true" window is OBSERVABLE, then throw (post-Ready outage).
        // Flows 2-4 throw immediately (3 recovery attempts all fail → exhaustion).
        //
        // The gate splits the frame and the outage into separate ticks (see the
        // post-Ready-outage test for the rationale — an atomic emit+throw under
        // UnconfinedTestDispatcher reverts the flag before runPending returns).
        val outageGate = CompletableDeferred<Unit>()
        every { repository.connectSSE(any()) } returnsMany listOf(
            flow<Result<SSEEvent>> {
                emit(Result.success(sseEvent("first")))
                outageGate.await()
                throw IOException("post-ready outage")
            },
            flow<Result<SSEEvent>> { throw IOException("recovery-1 fail") },
            flow<Result<SSEEvent>> { throw IOException("recovery-2 fail") },
            flow<Result<SSEEvent>> { throw IOException("recovery-3 fail") },
        )

        scope.launch { owner.connect(identity) }
        runPending() // flow 1 emits "first" → flag true → suspends on outageGate
        assertTrue("first frame set the flag true", sseConnected())

        // Release the gate → flow 1 throws → the 3 recovery attempts all fail
        // → terminal exhaustion.
        outageGate.complete(Unit)
        // runPending BEFORE advancing so flow 1's throw + its retry delay(1ms)
        // are scheduled at virtual-time 0 — otherwise the throw would land
        // inside the first advanceOwnerTimeBy and shift the retry schedule
        // off-by-one (flow 4 would never fire → verify(exactly=4) would fail).
        runPending()
        // Advance through the 3 recovery delays — all fail → exhaustion.
        advanceOwnerTimeBy(policy.delayForAttempt(1))
        runPending()
        advanceOwnerTimeBy(policy.delayForAttempt(2))
        runPending()
        advanceOwnerTimeBy(policy.delayForAttempt(3))
        runPending()

        assertFalse(
            "terminal exhaustion drops the flag false (transport permanently down)",
            sseConnected(),
        )
        // No new connectSSE call after exhaustion (4 total: initial + 3 recovery).
        verify(exactly = 4) { repository.connectSSE(any()) }
    }

    // ── (6) rapid connect / disconnect / reconfigure ordering ───────────────

    @Test
    fun `rapid connect then disconnect then reconnect tracks the flag each step`() = runTest {
        val identity = bindIdentity()
        aggregator.nextState = GlobalBusyState.Busy

        // Step 1: connect + frame → true.
        val feed1 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed1.asSharedFlow()
        scope.launch { owner.connect(identity) }
        runPending()
        feed1.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertTrue("step 1 connected", sseConnected())

        // Step 2: disconnect → false.
        scope.launch { owner.disconnect(markGap = true) }
        runPending()
        assertFalse("step 2 disconnected", sseConnected())

        // Step 3: reconnect with a fresh feed → false until the new frame.
        val feed2 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed2.asSharedFlow()
        scope.launch { owner.connect(identity) }
        runPending()
        assertFalse("step 3 inter-reconfigure gap (no frame yet)", sseConnected())

        // A stale frame on feed1 (gen 1, already disconnected) MUST NOT flip.
        feed1.tryEmit(Result.success(sseEvent("stale-from-gen1")))
        runPending()
        assertFalse("stale gen-1 frame ignored after reconnect", sseConnected())

        // Step 4: gen 2 first frame → true.
        feed2.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertTrue("step 4 reconnected", sseConnected())
    }

    // ── (7) service destruction / recreation ───────────────────────────────

    @Test
    fun `cancelForShutdown sets isSseConnected false (service destruction)`() = runTest {
        val identity = bindIdentity()
        aggregator.nextState = GlobalBusyState.Busy
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        scope.launch { owner.connect(identity) }
        runPending()
        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertTrue("connected before shutdown", sseConnected())

        // Synchronous destruction fallback — MUST drop the flag.
        owner.cancelForShutdown()
        assertFalse(
            "cancelForShutdown drops the flag false (service destroyed)",
            sseConnected(),
        )
    }

    @Test
    fun `flag survives service recreation via the singleton store`() = runTest {
        // The owner is service-instance-scoped + nullable, but the store is
        // @Singleton. A torn-down owner writing false MUST be observable by a
        // FRESH owner reading the same store field — proving the UI sees the
        // flag process-lifetime across a SessionStreamingService rebuild.
        val identity = bindIdentity()
        aggregator.nextState = GlobalBusyState.Busy
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        // Owner A (old service instance).
        scope.launch { owner.connect(identity) }
        runPending()
        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertTrue("owner A connected", sseConnected())

        owner.cancelForShutdown()
        assertFalse("owner A shutdown dropped the flag", sseConnected())

        // Owner B (new service instance) — constructed against the SAME store.
        val ownerB = ServiceSseConnectionOwner(
            scope = scope,
            repository = repository,
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            sseEventStream = stream,
            sharedStateStore = store,
            sharedEffectBus = effects,
            recoveryPolicy = policy,
            jitterSource = { 0.0f },
            onTerminalExhaustion = {},
        )
        // The flag is OBSERVABLE via the new owner's exposure (= the store's
        // projection), proving process-lifetime survival.
        assertFalse(
            "owner B observes the persisted false flag (survives recreation)",
            ownerB.isSseConnected.value,
        )
        // And via the store directly (the path the UI uses).
        assertFalse(
            "store projection survives recreation",
            store.sseConnectedFlow.value,
        )

        // Owner B connects fresh → first frame re-asserts true.
        val feedB = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feedB.asSharedFlow()
        scope.launch { ownerB.connect(identity) }
        runPending()
        feedB.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertTrue(
            "owner B first frame re-asserts the flag true",
            ownerB.isSseConnected.value,
        )
    }

    // ── (8) TOCTOU race: monotonic CAS rejects stale collector after teardown ─

    @Test
    fun `monotonic CAS invariant - newer generation wins, older rejected`() = runTest {
        // Pure store-level proof of the generation-stamped CAS that closes the
        // setSseConnected check-then-write TOCTOU: a write commits ONLY IF its
        // generation is >= the stored sseConnectedGeneration. Explicit
        // generations (decoupled from the owner's internal counter numbering)
        // make the monotonic invariant unambiguous.
        //
        // Gen 10 = a live connection's first frame proved transport.
        assertTrue("gen-10 frame commits (10 >= initial 0)", store.mutateSseConnected(true, 10L))
        assertTrue("flag true after gen-10 frame", sseConnected())

        // Teardown stamps the NEW generation (gen 11 = bumped) → false.
        assertTrue("gen-11 teardown commits (11 >= 10)", store.mutateSseConnected(false, 11L))
        assertFalse("flag false after teardown", sseConnected())

        // THE RACE: a stale gen-10 collector (its isCurrentTransport check
        // passed before the teardown bumped the generation) commits
        // setSseConnected(true, 10). The monotonic CAS MUST reject it (10 < 11).
        assertFalse(
            "stale gen-10 write REJECTED by monotonic CAS (10 < 11)",
            store.mutateSseConnected(true, 10L),
        )
        assertFalse(
            "flag stays false — stale collector cannot resurrect 'connected'",
            sseConnected(),
        )

        // An even-older stale generation is also rejected.
        assertFalse("stale gen-5 also rejected (5 < 11)", store.mutateSseConnected(true, 5L))
        assertFalse("flag still false", sseConnected())

        // A legit NEW generation (reconnect) wins the CAS (12 >= 11).
        assertTrue("gen-12 reconnect commits (12 >= 11)", store.mutateSseConnected(true, 12L))
        assertTrue("flag true after new-gen reconnect", sseConnected())
    }

    @Test
    fun `owner teardown stamps the NEW generation - stale collector loses the CAS (TOCTOU race)`() = runTest {
        // End-to-end: a REAL owner.disconnect stamps the BUMPED generation, so
        // a stale write carrying the pre-disconnect generation is rejected by
        // the monotonic CAS — proving the owner threads the correct generation
        // into setSseConnected on teardown.
        //
        // Generation derivation (deterministic, starts at counter=0):
        //  - setupConnectLocked does ++counter → gen 1; the frame stamps gen 1.
        //  - disconnect stamps closingGen+1 = 1+1 = 2 (the NEW generation) +
        //    bumps the counter to 2.
        //  - the next connect would be ++counter → gen 3.
        val identity = bindIdentity()
        aggregator.nextState = GlobalBusyState.Busy
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        // Gen 1: connect + first frame → flag true (CAS stamps gen 1).
        scope.launch { owner.connect(identity) }
        runPending()
        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertTrue("gen 1 connected", sseConnected())

        // Disconnect stamps the NEW generation (gen 2 = closingGen 1 + 1).
        scope.launch { owner.disconnect(markGap = true) }
        runPending()
        assertFalse("teardown dropped the flag false", sseConnected())

        // THE RACE: a stale gen-1 collector's setSseConnected(true, 1) — its
        // isCurrentTransport(1) check passed before disconnect bumped the
        // counter — now commits. The monotonic CAS MUST reject it (1 < 2): the
        // flag MUST stay false (no false "connected" lie after teardown).
        // setSseConnected is private + delegates to mutateSseConnected, so we
        // invoke that directly (the exact atomic primitive the owner calls).
        assertFalse(
            "stale gen-1 write REJECTED — owner stamped gen 2 on teardown (1 < 2)",
            store.mutateSseConnected(true, 1L),
        )
        assertFalse(
            "flag stays false — stale collector cannot resurrect 'connected'",
            sseConnected(),
        )

        // A legit gen-3 reconnect (the next ++counter) wins the CAS (3 >= 2).
        assertTrue(
            "new-gen-3 reconnect wins the CAS (3 >= stamped gen 2)",
            store.mutateSseConnected(true, 3L),
        )
        assertTrue("flag true after new-gen reconnect", sseConnected())
    }

    // ── Helper fakes (mirror ServiceSseConnectionOwnerTest) ─────────────────

    /**
     * §breathing-indicator (purge-clear defensive): the host-purge reducer
     * ([cn.vectory.ocdroid.ui.CrossSliceFieldsReducer.reduceHostStatePurged])
     * clears `isSseConnected=false` AND advances `sseConnectedGeneration` so a
     * stale collector carrying the pre-purge generation cannot resurrect `true`
     * via the monotonic CAS. The owner's normal teardown is the primary clear;
     * this pins the defensive backstop for a purge that does NOT go through
     * owner-disconnect.
     */
    @Test
    fun `host purge clears isSseConnected and advances the generation stamp - stale collector rejected`() = runTest {
        val identity = bindIdentity()
        aggregator.nextState = GlobalBusyState.Busy
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        // Gen 1: connect + frame → flag true (stamp gen 1).
        scope.launch { owner.connect(identity) }
        runPending()
        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertTrue("connected before purge", sseConnected())
        // Capture the pre-purge generation dynamically (robust to the owner's
        // internal counter seeding — do NOT hardcode).
        val prePurgeGen = store.sseConnectedGeneration
        assertTrue("pre-purge gen is set", prePurgeGen > 0L)

        // Host purge (dispatched through the real reducer path) — the
        // defensive backstop. The reducer advances the generation stamp
        // atomically (inside dispatch's state.update CAS) so any stale
        // collector carrying prePurgeGen loses.
        store.dispatch(cn.vectory.ocdroid.ui.AppAction.HostStatePurged(preserveServerGroupData = false))
        assertFalse("purge cleared the breath flag", sseConnected())
        assertTrue(
            "purge advanced the generation stamp (so stale writes lose the CAS)",
            store.sseConnectedGeneration > prePurgeGen,
        )

        // THE RACE: a stale collector (carrying the pre-purge generation, e.g.
        // its onSuccessfulFrame was in-flight when the purge committed) tries to
        // re-assert true. The monotonic CAS MUST reject it.
        assertFalse(
            "stale pre-purge-gen write REJECTED by CAS after purge",
            store.mutateSseConnected(true, prePurgeGen),
        )
        assertFalse(
            "flag stays false — no stale-true breath for a purged host",
            sseConnected(),
        )

        // Happy path: a fresh connect after the purge re-asserts true. The
        // owner re-seeds its counter from the (advanced) store stamp in
        // setupConnectLocked, so its supersession stamp wins the CAS.
        val feed2 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed2.asSharedFlow()
        scope.launch { owner.connect(identity) }
        runPending()
        feed2.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertTrue(
            "fresh connect after purge re-asserts the flag true",
            sseConnected(),
        )
    }

    // ── Helper fakes (mirror ServiceSseConnectionOwnerTest) ─────────────────

    private class TestRecoveryPolicy : SseRecoveryPolicy() {
        override val attempts: Int = 3
        override fun baseDelayMs(attempt: Int): Long = attempt.toLong() // 1ms / 2ms / 3ms
        fun delayForAttempt(attempt: Int): Long = delayMs(attempt, 0.0f)
    }

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
