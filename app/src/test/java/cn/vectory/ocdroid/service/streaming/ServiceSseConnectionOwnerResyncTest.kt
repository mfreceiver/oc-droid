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
 * Cluster A (slim SSE resync + P2.5 first-ready cold start): tests for the
 * [ServiceSseConnectionOwner] `onResync` callback wiring.
 *
 * Contract (v1 §3/§4 + Phase 2 P2.5 + B2 fix rev-grok 🔴2):
 *  - **First-frame cold-start (P2.5)**: fires AT MOST ONCE per transport
 *    generation on the first successful current-identity frame — gated by
 *    `resyncHandledForGen`.
 *  - **Explicit `type=="resync"` (B2 fix)**: fires EVERY TIME a `resync`
 *    frame arrives mid-stream — NOT gated by the once-per-gen latch. The
 *    old Phase-2 wiring fed both triggers through one gate, so the first
 *    cold-start armed the latch and subsequent resyncs were silently
 *    dropped (incremental / snapshot recovery broken). Now: explicit
 *    `resync` → fresh cold-start pull (v1 §3/§4: resync = reuse cold-start).
 *  - **Concurrency (rev-grok)**: rapid resyncs queued behind an in-flight
 *    cold-start SERIALIZE via `resyncMutex` (coldStartSlimSync is not
 *    reentrant-safe) — never run in parallel, never dropped.
 *  - **Stale-apply guard (rev-grok 9.5 🟠2)**: a queued gen-N cold-start
 *    that wins the Mutex AFTER a fast reconnect to gen N+1 is SKIPPED via
 *    an `isCurrentTransport(generation)` re-check inside `withLock`
 *    (TOCTOU-safe). The new gen's own first-frame cold-start is unaffected.
 *
 * Fixture mirrors [ServiceSseConnectionOwnerTest] (mocked repository + real
 * identity store / event stream).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ServiceSseConnectionOwnerResyncTest {

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
    private val resyncInvocations = AtomicInteger(0)

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
        resyncInvocations.set(0)
        // T10: DebugLog is a process-singleton ring buffer; clear between
        // tests so reason-log assertions only see THIS test's emissions.
        DebugLog.clear()

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

    /**
     * T10 fixture: builds a `type=="resync"` frame carrying the given
     * server `reason` value (or no `reason` key when [reason] is null).
     * The shape mirrors the oc-slimapi sidecar's `event: resync` frame —
     * `data:` carries the WHOLE properties object (see SSEClient's B1 fix
     * path), so `reason` lives directly in `payload.properties`.
     */
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

        // First frame completes transport readiness AND fires cold-start.
        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()

        assertEquals(
            "first successful frame MUST trigger onResync exactly once (P2.5)",
            1,
            resyncInvocations.get(),
        )
    }

    @Test
    fun `resync after first-frame cold start RE-FIRES in same generation (B2 regression)`() = runTest {
        // rev-grok 🔴2 core regression: the old Phase-2 wiring fed BOTH the
        // first-frame cold-start AND the explicit `resync` trigger through
        // ONE once-per-gen gate, so the first-frame cold-start armed the
        // latch and every subsequent `event: resync` was silently dropped
        // (incremental / snapshot recovery path broken — the sidecar may
        // emit resync on upstream reconnect / `resync_all` WITHOUT dropping
        // the client SSE; v1 §3/§4: resync = reuse cold-start). This test
        // pins the fix: an explicit `resync` mid-stream MUST trigger a
        // fresh cold-start pull even though the first-frame cold-start has
        // already armed the per-generation latch.
        val identity = bindIdentity()
        aggregator.nextState = GlobalBusyState.Busy
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        scope.launch { owner.connect(identity) }
        runPending()

        // First frame completes transport readiness AND fires cold-start.
        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertEquals(1, resyncInvocations.get())

        // Mid-stream explicit resync MUST fire cold-start again.
        feed.tryEmit(Result.success(sseEvent("resync")))
        runPending()

        assertEquals(
            "mid-stream event:resync MUST re-trigger onResync (B2 regression) — " +
                "the per-generation latch must NOT gate the explicit-resync path",
            2,
            resyncInvocations.get(),
        )

        // A second mid-stream resync MUST ALSO fire — each `resync` is its
        // own cold-start pull (no coalescing into one — every signal is
        // honored, just never run in parallel).
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
        owner.disconnectAndJoin()
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
        // B2 fix branch-exclusivity: a first frame that is itself `type == "resync"`
        // MUST fire cold-start exactly ONCE (via the first-frame gate), NOT twice
        // (first-frame gate + explicit-resync path). The two trigger branches are
        // mutually exclusive so a sidecar that opens with `event: resync` does not
        // double-pull.
        val identity = bindIdentity()
        aggregator.nextState = GlobalBusyState.Busy
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        scope.launch { owner.connect(identity) }
        runPending()

        feed.tryEmit(Result.success(sseEvent("resync")))
        runPending()

        assertEquals(
            "first-frame type=resync MUST fire cold-start exactly once " +
                "(branch exclusivity — no double-pull)",
            1,
            resyncInvocations.get(),
        )

        // A subsequent mid-stream resync still re-fires (B2 regression guard).
        feed.tryEmit(Result.success(sseEvent("resync")))
        runPending()
        assertEquals(
            "subsequent resync after first-frame resync MUST re-fire",
            2,
            resyncInvocations.get(),
        )
    }

    @Test
    fun `multiple rapid resyncs serialize via mutex - no parallel cold-start`() = runTest {
        // rev-grok concurrency contract: "可加 in-flight 合并/串行，但不得静默丢".
        // This test pins the SERIALIZE choice — multiple rapid `resync` frames
        // queued behind an in-flight cold-start NEVER execute in parallel
        // (max-in-flight == 1) AND none are dropped (each fires its own pull).
        // [OpenCodeRepository.coldStartSlimSync] is not reentrant-safe (shared
        // bookmark state), so concurrent invocations would race; the Mutex
        // funnels them.
        val identity = bindIdentity()
        aggregator.nextState = GlobalBusyState.Busy
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        val gate = CompletableDeferred<Unit>()
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val seen = AtomicInteger(0)

        val gatedOwner = ServiceSseConnectionOwner(
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
            onResync = {
                val n = inFlight.incrementAndGet()
                maxInFlight.updateAndGet { maxOf(it, n) }
                seen.incrementAndGet()
                try {
                    // Hold the cold-start open until the test releases the gate.
                    gate.await()
                } finally {
                    inFlight.decrementAndGet()
                }
            },
        )

        scope.launch { gatedOwner.connect(identity) }
        runPending()
        // First-frame cold-start fires + suspends on gate.
        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertEquals("first-frame cold-start fired", 1, seen.get())
        assertEquals("first cold-start in-flight", 1, inFlight.get())

        // Fire two more explicit resyncs while the first cold-start is still
        // suspended on the gate — they MUST queue behind resyncMutex (not run
        // in parallel, not be dropped).
        feed.tryEmit(Result.success(sseEvent("resync")))
        runPending()
        feed.tryEmit(Result.success(sseEvent("resync")))
        runPending()

        assertEquals(
            "queued resyncs MUST NOT run in parallel with in-flight cold-start",
            1,
            maxInFlight.get(),
        )
        assertEquals(
            "queued resyncs MUST NOT fire until Mutex is released",
            1,
            seen.get(),
        )

        // Release the gate: queued cold-starts run sequentially, max-in-flight
        // stays at 1.
        gate.complete(Unit)
        // Multiple runPending() passes because UnconfinedTestDispatcher may
        // need separate ticks per Mutex release + acquire.
        runPending()
        runPending()
        runPending()

        assertEquals(
            "no two cold-starts ever overlapped (Mutex-serialized)",
            1,
            maxInFlight.get(),
        )
        assertEquals(
            "every trigger fired its own cold-start (no drops): 1 first-frame + 2 resync",
            3,
            seen.get(),
        )
    }

    @Test
    fun `queued resync for stale generation is skipped after reconnect (O2 stale-apply guard)`() = runTest {
        // rev-grok 9.5 🟠2: scheduleResync's Mutex serializes CONCURRENT
        // cold-starts but does NOT by itself prevent STALE execution. On a
        // fast reconnect / host switch, a queued gen-N trigger can win the
        // Mutex AFTER setupConnectLocked bumped transportGenerationCounter
        // to N+1 + established a new live slice. Running gen N's cold-start
        // in that window would fold gen N's snapshot into gen N+1's live
        // slice (stale apply). The isCurrentTransport(generation) guard
        // INSIDE the Mutex drops the stale trigger; the new generation's
        // own first-frame cold-start is unaffected.
        val identity = bindIdentity()
        aggregator.nextState = GlobalBusyState.Busy
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        // Gate the cold-start so gen N's queued trigger holds the Mutex
        // while we reconnect underneath it.
        val gate = CompletableDeferred<Unit>()
        val seen = AtomicInteger(0)
        val gatedOwner = ServiceSseConnectionOwner(
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
            onResync = {
                seen.incrementAndGet()
                // Hold the first cold-start open so a queued resync backs
                // up behind the Mutex.
                gate.await()
            },
        )

        // Gen 1: connect + first frame fires cold-start (suspended on gate).
        scope.launch { gatedOwner.connect(identity) }
        runPending()
        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        assertEquals("gen 1 first-frame cold-start fired", 1, seen.get())

        // Queue a gen-1 resync behind the Mutex (will not run yet — gate
        // is still holding the first cold-start).
        feed.tryEmit(Result.success(sseEvent("resync")))
        runPending()
        assertEquals("gen 1 resync queued behind Mutex (not yet run)", 1, seen.get())

        // Reconnect to gen 2 while the gen-1 resync is still queued.
        // disconnectAndJoin bumps transportGenerationCounter; the queued
        // resync is still behind the Mutex.
        gatedOwner.disconnectAndJoin()
        runPending()

        // Gen 2: connect again — first frame arms a fresh cold-start.
        scope.launch { gatedOwner.connect(identity) }
        runPending()
        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()
        // Gen 2's first-frame cold-start is queued behind the Mutex too
        // (gen 1's first cold-start still holds the lock). seen still == 1.
        assertEquals(
            "gen 2 cold-start queued behind gen-1 in-flight (Mutex held)",
            1,
            seen.get(),
        )

        // Release the gate: gen 1's first cold-start completes + releases
        // the Mutex. The queued gen-1 resync wins the Mutex next, BUT the
        // isCurrentTransport(generation=1) guard inside withLock now sees
        // current=2 → skips. Then gen 2's first-frame cold-start wins the
        // Mutex → isCurrentTransport(generation=2) → fires.
        gate.complete(Unit)
        runPending()
        runPending()
        runPending()

        assertEquals(
            "gen-1 queued resync MUST be skipped (stale generation); " +
                "only gen-2 first-frame cold-start fires after gate release",
            2,
            seen.get(),
        )

        // Sanity: gen 2 still fully functional — a fresh gen-2 resync fires
        // normally (proves the guard doesn't over-suppress).
        feed.tryEmit(Result.success(sseEvent("resync")))
        runPending()
        runPending()
        assertEquals(
            "gen 2 explicit resync fires normally post-reconnect " +
                "(guard does not over-suppress current-generation triggers)",
            3,
            seen.get(),
        )
    }

    @Test
    fun `onResync exception does not crash the collector`() = runTest {
        // Wire an onResync that throws; the SSE collector must survive.
        val throwingOwner = ServiceSseConnectionOwner(
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
            onResync = { error("cold-start refetch blew up") },
        )
        val identity = bindIdentity()
        aggregator.nextState = GlobalBusyState.Busy
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)

        scope.launch { throwingOwner.connect(identity) }
        runPending()
        // First frame triggers onResync which throws — collector must NOT die.
        feed.tryEmit(Result.success(sseEvent("server.connected")))
        runPending()

        // A subsequent frame must still be deliverable (proves the flow is alive).
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
        // Give the watcher a chance.
        runPending()
        assertEquals(
            "collector survives onResync exceptions",
            true,
            observed,
        )
        watcherJob.cancel()
    }

    @Test
    fun `onResync default is non-null callback contract for production wiring`() {
        // Construction with explicit onResync proves the Service can pass a
        // non-default lambda (P2.4). Default-arg construction remains valid
        // for tests that omit it.
        val defaultOwner = ServiceSseConnectionOwner(
            scope = scope,
            repository = repository,
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            sseEventStream = stream,
            sharedStateStore = store,
            sharedEffectBus = effects,
            recoveryPolicy = policy,
            onTerminalExhaustion = {},
        )
        // Smoke: default construction does not throw; production wiring
        // (SessionStreamingService) always passes a non-empty onResync.
        assertNotNull("default owner constructs", defaultOwner)
    }

    // ── T10 (slimapi v1 §3): resync `reason` sub-field parsing ────────────
    //
    // The v1 contract §3 says every `resync` SSE frame carries a `reason`
    // field INSIDE the payload (one of `reconnect_no_replay` /
    // `subscriber_backpressure` / `implicit`). The OWNER's catch-up action
    // is identical regardless of reason (cold-start / rebuild session list);
    // reason exists purely for telemetry + future backoff tuning. These
    // tests pin two T10 invariants:
    //  - **T10-C2/C3**: all 3 defined reasons + an unknown string + a
    //    missing/null field ALL reuse the existing `scheduleResync(reason,
    //    generation)` path (signature UNCHANGED) and ALL fire `onResync`.
    //    No control flow branches on the reason value.
    //  - **T10-C1**: the reason is parsed via `JsonPrimitive.content`
    //    (NOT `as? String`, which would always miss — JsonPrimitive is not
    //    a String) and the typed [SlimapiResyncReason] is logged.

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

        assertEquals(
            "explicit resync with reason=reconnect_no_replay MUST trigger onResync " +
                "(action is identical regardless of reason)",
            2,
            resyncInvocations.get(),
        )
    }

    @Test
    fun `resync reason = subscriber_backpressure triggers onResync (T10-C3)`() = runTest {
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)
        aggregator.nextState = GlobalBusyState.Busy
        connectAndFireFirstFrame(feed)

        feed.tryEmit(Result.success(sseResyncEvent("subscriber_backpressure")))
        runPending()

        assertEquals(
            "explicit resync with reason=subscriber_backpressure MUST trigger onResync " +
                "(action is identical regardless of reason)",
            2,
            resyncInvocations.get(),
        )
    }

    @Test
    fun `resync reason = implicit triggers onResync (T10-C3)`() = runTest {
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)
        aggregator.nextState = GlobalBusyState.Busy
        connectAndFireFirstFrame(feed)

        feed.tryEmit(Result.success(sseResyncEvent("implicit")))
        runPending()

        assertEquals(
            "explicit resync with reason=implicit MUST trigger onResync " +
                "(action is identical regardless of reason)",
            2,
            resyncInvocations.get(),
        )
    }

    @Test
    fun `resync with UNKNOWN reason string still triggers onResync (T10-C2 unknown)`() = runTest {
        // Forward-compat: the sidecar may ship a new reason value before the
        // client's enum catches up. `SlimapiResyncReason.fromRaw` returns null
        // for unknown wire values, but the catch-up MUST still fire — the
        // reason is observability, not a gate.
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)
        aggregator.nextState = GlobalBusyState.Busy
        connectAndFireFirstFrame(feed)

        feed.tryEmit(Result.success(sseResyncEvent("some-future-reason-v2")))
        runPending()

        assertEquals(
            "explicit resync with UNKNOWN reason MUST still trigger onResync " +
                "(forward-compat: reason is observability, not a gate)",
            2,
            resyncInvocations.get(),
        )
    }

    @Test
    fun `resync with NULL reason field still triggers onResync (T10-C2 null)`() = runTest {
        // Tolerates `properties.reason` being absent (e.g. a frame where the
        // sidecar omitted the field) AND `properties` itself being null
        // (the existing `sseEvent("resync")` helper builds `SSEPayload` with
        // default null `properties`). Both MUST trigger onResync.
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        stubFeed(feed)
        aggregator.nextState = GlobalBusyState.Busy
        connectAndFireFirstFrame(feed)

        // Sub-case A: properties present but `reason` key absent.
        feed.tryEmit(Result.success(sseResyncEvent(reason = null)))
        runPending()
        assertEquals(
            "resync with properties but missing `reason` key MUST trigger onResync",
            2,
            resyncInvocations.get(),
        )

        // Sub-case B: `properties` itself null (default-constructed payload).
        feed.tryEmit(Result.success(sseEvent("resync")))
        runPending()
        assertEquals(
            "resync with null properties (no reason field at all) MUST trigger onResync",
            3,
            resyncInvocations.get(),
        )
    }

    @Test
    fun `resync reason is parsed via JsonPrimitive content and logged via SlimapiResyncReason fromRaw (T10-C1)`() = runTest {
        // T10-C1: the `reason` sub-field lives in `payload.properties` as a
        // JsonPrimitive; the owner MUST extract its value via `.content`
        // (NOT `as? String` — JsonPrimitive is not a String and that cast
        // would always return null) and log the typed enum via
        // `SlimapiResyncReason.fromRaw`. This test pins the log emission so
        // a regression that drops parsing (or reverts to `as? String`) is
        // caught: the typed enum name and the raw wire value MUST appear in
        // the in-memory debug ring buffer for downstream observability.
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
            "resync reason MUST be logged via DebugLog (T10-C1). " +
                "Matching entries: $resyncReasonLogs",
            resyncReasonLogs.isNotEmpty(),
        )
        val firstMsg = resyncReasonLogs.first().message
        assertTrue(
            "raw wire value `reconnect_no_replay` MUST appear in log (proves JsonPrimitive.content " +
                "was used, NOT `as? String` which would miss). Got: $firstMsg",
            firstMsg.contains("reconnect_no_replay"),
        )
        assertTrue(
            "typed SlimapiResyncReason.RECONNECT_NO_REPLAY enum name MUST appear in log " +
                "(proves SlimapiResyncReason.fromRaw was invoked). Got: $firstMsg",
            firstMsg.contains("RECONNECT_NO_REPLAY"),
        )
    }

    @Test
    fun `resync UNKNOWN reason logs null typed reason but still records raw wire value (T10-C1 forward-compat)`() = runTest {
        // Companion to the test above: when the wire value is unknown, the
        // typed enum is null but the raw string is still logged — this is
        // how a future unmapped reason shows up in the in-app log viewer so
        // the user can self-diagnose a sidecar upgrade.
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
        assertTrue(
            "unknown reason MUST still be logged for observability. Matching: $resyncReasonLogs",
            resyncReasonLogs.isNotEmpty(),
        )
        val firstMsg = resyncReasonLogs.first().message
        assertTrue(
            "raw unknown wire value MUST appear in log. Got: $firstMsg",
            firstMsg.contains("some-future-reason-v2"),
        )
        assertTrue(
            "typed reason MUST be null for unknown wire value (fromRaw returned null). Got: $firstMsg",
            firstMsg.contains("typed=null"),
        )
    }

    // ── Helper fakes (mirror ServiceSseConnectionOwnerTest) ────────────────

    private class TestRecoveryPolicy : SseRecoveryPolicy() {
        override val attempts: Int = 3
        override fun baseDelayMs(attempt: Int): Long = attempt.toLong()
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
