package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.data.model.ResyncReason
import cn.vectory.ocdroid.data.model.TokenStreamFrame
import cn.vectory.ocdroid.data.repository.ClientBundle
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.NavRoute
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * §sse-self-cancel Fix① (sse-self-cancel-investigation §10.2 rev-opus 终审):
 * focused tests for the [TokenStreamCoordinator.open] IDEMPOTENT GUARD.
 *
 * The guard skips a duplicate open() — same (sid, directory) with an active
 * lifecycle already in [currentStreamJob] — WITHOUT superseding, so a digest
 * storm re-entering open() no longer cancels the live collector ~190ms after
 * connect (the "Socket closed" self-cancel loop). The 100ms debounce handles
 * sub-100ms bursts; this guard handles steady-state churn (>100ms).
 *
 * **Single-threaded premise (T1.1-C4)**: the synchronous read of
 * (currentSid, currentDirectory, currentStreamJob) + decision has no TOCTOU
 * window ONLY on a single-threaded dispatcher. Production runs on
 * `@UiApplicationScope = Dispatchers.Main.immediate`; these tests run on
 * `TestScope(UnconfinedTestDispatcher())` which models that single-threaded
 * execution. A future off-main call-site would make this guard unsafe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenStreamCoordinatorIdempotencyTest {

    private lateinit var scope: TestScope
    private lateinit var slices: SliceFlows
    private lateinit var stateStore: SharedStateStore
    private lateinit var fake: FakeStreamProvider
    private lateinit var bundleRepository: OpenCodeRepository
    private lateinit var coordinator: TokenStreamCoordinator

    @Before
    fun setUp() {
        scope = TestScope(UnconfinedTestDispatcher())
        stateStore = SharedStateStore()
        slices = stateStore.slices
        fake = FakeStreamProvider()
        bundleRepository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val bundle = bundleRepository.currentClientBundle()!!
        stateStore.dispatch(AppAction.BundlePublished(bundle.generation, bundle.endpointFp))
        // Large watchdog so runPending (= runCurrent) does not trip the timeout
        // → reconnect loop in non-watchdog tests.
        coordinator = buildCoordinator(watchdogMs = 10_000L)
    }

    private fun buildCoordinator(
        watchdogMs: Long = 10_000L,
        openDebounceMs: Long = 0L,
        streamProvider: (String, String?) -> Flow<TokenStreamFrame> = fake.provider,
        streamConnectionProvider: ((String, String?) -> TokenStreamConnection)? = null,
        currentBundleProvider: () -> ClientBundle? = { bundleRepository.currentClientBundle() },
        initialBackoffMs: Long = 50L,
    ): TokenStreamCoordinator = TokenStreamCoordinator(
        scope = scope,
        slices = slices,
        streamProvider = streamProvider,
        streamConnectionProvider = streamConnectionProvider,
        bundleCommitLock = bundleRepository,
        currentBundleProvider = currentBundleProvider,
        triggerSinceFetch = { _, _ -> },
        openDebounceMs = openDebounceMs,
        watchdogPollMs = 10L,
        watchdogMs = watchdogMs,
        initialBackoffMs = initialBackoffMs,
        maxBackoffMs = 200L,
        clock = { scope.testScheduler.currentTime },
    )

    @After
    fun tearDown() {
        try { scope.runCurrent() } catch (_: Throwable) {}
    }

    private fun runPending() = scope.runCurrent()

    private fun publishBundle(baseUrl: String): ClientBundle {
        bundleRepository.configure(baseUrl = baseUrl, slim = true)
        return bundleRepository.currentClientBundle()!!
    }

    // ── T1.1-C1: active + same sid + same dir → idempotent skip ────────────

    @Test
    fun `active lifecycle with same sid and same dir is idempotent — second open skips supersede`() {
        coordinator.open("s1", "/work", source = "effect-load")
        runPending()
        assertEquals("first open hits provider", 1, fake.openCount.get())
        val jobAfterFirst = coordinator.currentStreamJobSnapshot()
        assertTrue("lifecycle job is active after first open", jobAfterFirst!!.isActive)

        // Second open with IDENTICAL (sid, directory). Fix① → skip.
        coordinator.open("s1", "/work", source = "effect-load")
        runPending()

        // No new provider call — the duplicate open did NOT launch a new lifecycle.
        assertEquals("idempotent skip — openCount unchanged", 1, fake.openCount.get())
        // The SAME job reference is still current (no supersede / getAndSet).
        val jobAfterSecond = coordinator.currentStreamJobSnapshot()
        assertSame("currentStreamJob reference unchanged (no supersede)", jobAfterFirst, jobAfterSecond)
        assertTrue("prior lifecycle still active (NOT cancelled)", jobAfterSecond!!.isActive)
        // No overlapping collectors ever — max-1 invariant preserved.
        assertEquals("maxLiveCollectors never exceeds 1", 1, fake.maxLiveCollectors.get())

        coordinator.close("s1")
        runPending()
    }

    // ── T1.1-C2: same sid + DIFFERENT dir → supersede (no skip) ────────────

    @Test
    fun `same sid with different directory is NOT idempotent — supersedes the prior lifecycle`() {
        coordinator.open("s1", "/work-a")
        runPending()
        assertEquals(1, fake.openCount.get())
        val jobAfterFirst = coordinator.currentStreamJobSnapshot()
        assertTrue(jobAfterFirst!!.isActive)

        // Same sid but a DIFFERENT directory — the guard's directory check
        // fails, so this is a genuine (re)open → supersede the prior lifecycle.
        coordinator.open("s1", "/work-b")
        runPending()

        assertEquals("supersede hits provider again", 2, fake.openCount.get())
        val jobAfterSecond = coordinator.currentStreamJobSnapshot()
        assertNotSame("currentStreamJob replaced by a new lifecycle", jobAfterFirst, jobAfterSecond)
        assertFalse("prior lifecycle cancelled by supersede", jobAfterFirst.isActive)
        assertTrue("new lifecycle active", jobAfterSecond!!.isActive)

        coordinator.close("s1")
        runPending()
    }

    // ── T1.1-C2 supplement: different sid (same dir) → supersede ───────────

    @Test
    fun `different sid is NOT idempotent — supersedes the prior lifecycle`() {
        coordinator.open("s1", "/work")
        runPending()
        val jobAfterFirst = coordinator.currentStreamJobSnapshot()

        // Different sid → guard's sid check fails → supersede.
        coordinator.open("s2", "/work")
        runPending()

        assertEquals(2, fake.openCount.get())
        assertNotSame("new lifecycle for different sid", jobAfterFirst, coordinator.currentStreamJobSnapshot())
        assertFalse("prior lifecycle cancelled", jobAfterFirst!!.isActive)

        coordinator.close("s2")
        runPending()
    }

    // ── T1.1-C3: open(same sid) during reconnect-backoff → skip, backoff survives ─

    @Test
    fun `open during reconnect backoff is idempotent — pending reconnect survives and fires`() {
        coordinator = buildCoordinator(watchdogMs = 10_000L, initialBackoffMs = 200L)
        coordinator.open("s1", "/work")
        runPending()
        assertEquals(1, fake.openCount.get())

        // Drive a resync(reconnect_no_replay) → sentinel → throw → catch →
        // scheduleReconnect → J2 lands in delay(200). J1 (collector) unwinds.
        fake.send(snapshot(partId = "p1", text = "a"))
        runPending()
        fake.send(TokenStreamFrame.Resync(ResyncReason.RECONNECT_NO_REPLAY, "s1"))
        runPending()
        assertEquals("only J1's provider call so far", 1, fake.openCount.get())
        assertEquals("J1 unwound → liveCount back to 0", 0, fake.liveCollectors.get())

        val backoffJob = coordinator.currentStreamJobSnapshot()
        assertTrue("backoff job (J2) is active in currentStreamJob", backoffJob!!.isActive)

        // open(same sid + same dir) during backoff → Fix① skip. J2 is NOT
        // cancelled; currentStreamJob reference unchanged.
        coordinator.open("s1", "/work", source = "effect-load")
        runPending()
        assertEquals("idempotent skip — openCount unchanged during backoff", 1, fake.openCount.get())
        assertSame("currentStreamJob unchanged (backoff job NOT superseded)", backoffJob, coordinator.currentStreamJobSnapshot())
        assertTrue("backoff job still active after idempotent open", backoffJob.isActive)

        // Advance past backoff → J2 fires runStream → provider called → connect.
        scope.advanceTimeBy(300L)
        runPending()
        assertEquals("pending reconnect fires after backoff — openCount=2", 2, fake.openCount.get())
        assertEquals("maxLiveCollectors never exceeds 1", 1, fake.maxLiveCollectors.get())

        coordinator.close("s1")
        runPending()
    }

    // ── T1.1-C4: single-threaded premise documentation ─────────────────────

    /**
     * T1.1-C4: the idempotent guard's TOCTOU-freedom depends on a single-
     * threaded dispatcher. This test documents + exercises that premise under
     * `TestScope(UnconfinedTestDispatcher())` — which models production's
     * `Dispatchers.Main.immediate`. The two sequential opens run to completion
     * synchronously (no interleaving suspension between the guard's read and
     * the skip decision), so the guard observes a consistent snapshot.
     *
     * If the coordinator were ever migrated to a multi-threaded dispatcher,
     * this test's name + the open() KDoc premise comment are the tripwire.
     */
    @Test
    fun `idempotent guard is TOCTOU-free under single-threaded dispatcher - documents Main immediate premise`() {
        // Burst of 5 identical opens on the same (sid, dir). On a single-
        // threaded dispatcher exactly ONE reaches the provider; the rest are
        // absorbed by the guard. (The 0ms debounce means each open runs to
        // the guard synchronously before the next.)
        repeat(5) { coordinator.open("s1", "/work") }
        runPending()
        assertEquals("only the first open connects — 4 duplicates absorbed", 1, fake.openCount.get())
        assertTrue("single active lifecycle", coordinator.currentStreamJobSnapshot()!!.isActive)

        coordinator.close("s1")
        runPending()
    }

    // ── §B4 rev-gpt round3 MAJOR: same sid+dir with NEW route incarnation ──

    /**
     * §B4 rev-gpt round3 MAJOR: the [open] idempotent guard MUST include the
     * route token in its comparison, not just (sid, directory).
     *
     * Scenario: `navigateToChat(sid)` on an already-open same-session route
     * advances `chatRouteInstance` (the freshness CAS counter). Without the
     * token in the guard, this second open() would skip on (sid, dir) alone →
     * the prior lifecycle's stale captured token persists → token-stream
     * frames carry the OLD token → the reducer's [acceptsRouteUpdate] rejects
     * → the new incarnation receives NO real-time updates.
     *
     * With the fix, the guard sees the token mismatch and supersedes; the new
     * lifecycle captures the new token and dispatch is threaded verbatim.
     */
    @Test
    fun `same sid+dir with NEW route incarnation supersedes - not idempotent (rev-gpt round3 MAJOR)`() {
        // ── Stage 1: route points at s1 with chatRouteInstance=1. ────────────
        // Without this, slices.routeInstanceFor("s1") returns 0L (the legacy
        // bare-chat scope) and the guard's routeToken check is trivially
        // satisfied — the test would not exercise the round3 MAJOR branch.
        stateStore.mutateState {
            it.copy(
                nav = it.nav.copy(lastRoute = "chat/s1", lastNavPage = NavRoute.Chat.legacyPage),
                chat = it.chat.copy(currentSessionId = "s1"),
                chatRouteInstance = 1L,
            )
        }
        runPending()

        // ── Open #1: lifecycle captures token=1, provider called once. ──────
        coordinator.open("s1", "/work", source = "navigateToChat")
        runPending()
        assertEquals("first open hits provider", 1, fake.openCount.get())
        val jobAfterFirst = coordinator.currentStreamJobSnapshot()
        assertTrue("lifecycle active after first open", jobAfterFirst!!.isActive)

        // ── Stage 2: navigateToChat same-session re-entry advances the token. ─
        // Mirrors OrchestratorViewModel.navigateToChat's mutateState
        // (chatRouteInstance + 1L). The SessionSelected dispatch (route-aware
        // openForRoute path) is omitted here — the guard only reads the slice,
        // not the dispatch queue.
        stateStore.mutateState { it.copy(chatRouteInstance = 2L) }
        runPending()

        // ── Open #2: SAME sid + SAME dir but NEW route incarnation. ─────────
        // Pre-fix: guard skipped on (sid, dir) match → openCount stayed at 1,
        // and the new route never received real-time updates. Post-fix: the
        // routeToken check fails (lifecycleRouteInstance=1 != current=2) →
        // supersede → new lifecycle captures token=2.
        coordinator.open("s1", "/work", source = "navigateToChat")
        runPending()

        assertEquals(
            "new route incarnation supersedes — openCount=2 (NOT idempotent)",
            2,
            fake.openCount.get(),
        )
        val jobAfterSecond = coordinator.currentStreamJobSnapshot()
        assertNotSame(
            "currentStreamJob replaced by a new lifecycle",
            jobAfterFirst,
            jobAfterSecond,
        )
        assertFalse("prior lifecycle cancelled by supersede", jobAfterFirst!!.isActive)
        assertTrue("new lifecycle active", jobAfterSecond!!.isActive)
        // max-1 collector invariant still holds (the supersede cancels the
        // prior lifecycle before the new provider call connects).
        assertEquals("maxLiveCollectors never exceeds 1", 1, fake.maxLiveCollectors.get())

        coordinator.close("s1")
        runPending()
    }

    @Test
    fun `same sid and dir with a newly published bundle supersedes the old lifecycle`() {
        val publishedBundle = AtomicReference(bundleRepository.currentClientBundle()!!)
        val bundleAware = buildCoordinator(
            streamProvider = { _, _ -> error("bundle-aware provider must be used") },
            streamConnectionProvider = { _, _ ->
                TokenStreamConnection(fake.provider("s1", "/work"), publishedBundle.get())
            },
            currentBundleProvider = { publishedBundle.get() },
        )

        bundleAware.open("s1", "/work")
        runPending()
        val oldJob = bundleAware.currentStreamJobSnapshot()
        assertTrue(oldJob!!.isActive)

        publishedBundle.set(publishBundle("http://bundle-b.test"))
        bundleAware.open("s1", "/work")
        runPending()

        assertFalse("bundle change must supersede the old lifecycle", oldJob.isActive)
        assertNotSame(oldJob, bundleAware.currentStreamJobSnapshot())
        assertEquals(2, fake.openCount.get())

        bundleAware.close("s1")
        runPending()
    }

    @Test
    fun `bundle is revalidated after the open guard snapshot before skipping`() {
        val bundleA = bundleRepository.currentClientBundle()!!
        val bundleB = publishBundle("http://bundle-b.test")
        val bundleReads = AtomicInteger(0)
        val boundBundles = mutableListOf<ClientBundle>()
        val guardRace = buildCoordinator(
            streamProvider = { _, _ -> error("bundle-aware provider must be used") },
            streamConnectionProvider = { _, _ ->
                val bundle = if (bundleReads.get() <= 3) bundleA else bundleB
                boundBundles += bundle
                TokenStreamConnection(fake.provider("s1", "/work"), bundle)
            },
            currentBundleProvider = {
                bundleReads.incrementAndGet()
                if (bundleReads.get() <= 3) bundleA else bundleB
            },
        )

        guardRace.open("s1", "/work")
        runPending()
        val firstJob = guardRace.currentStreamJobSnapshot()

        // The provider's fourth published-bundle read models configure()'
        // publication between the first guard comparison and its skip return.
        guardRace.open("s1", "/work")
        runPending()

        assertFalse("bundle publication during guard must prevent idempotent skip", firstJob!!.isActive)
        assertEquals(2, boundBundles.size)
        assertEquals(bundleB, boundBundles.last())
        guardRace.close("s1")
        runPending()
    }

    /**
     * T3.3-C1(i): configure may publish B after open's guard has read A but
     * before the transport is connected. The resolve-time connection still
     * carries A, so lifecycle binding must reject it before A can deliver a
     * frame. This is a failing-first regression shape; it is green now because
     * T3.1/T3.2 already implement the published-bundle invariant and the
     * pre-connect lifecycle validation.
     */
    @Test
    fun `configure between open comparison and connect drops retired A before first frame`() {
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        repository.configure(baseUrl = "http://host-a.test", slim = true)
        val bundleA = repository.currentClientBundle()!!

        val race = buildCoordinator(
            streamProvider = { _, _ -> error("resolve-time provider must be used") },
            streamConnectionProvider = { _, _ ->
                // This is the configure() → publish(B) interleaving. The
                // connection has already captured A, exactly as a real
                // resolver/client construction can do before binding.
                val capturedA = repository.currentClientBundle()!!
                assertSame(bundleA, capturedA)
                repository.configure(baseUrl = "http://host-b.test", slim = true)
                TokenStreamConnection(fake.provider("s1", "/work"), capturedA)
            },
            currentBundleProvider = { repository.currentClientBundle() },
        )

        race.open("s1", "/work", source = "t3.3-c1-i")
        runPending()

        assertTrue("configure(B) retires the captured A generation", bundleA.isRetired)
        assertTrue(
            "retired A must not commit a first token frame",
            stateStore.chatFlow.value.streamingPartTexts.isEmpty(),
        )
        race.close("s1")
        runPending()
    }

    /**
     * T3.3-C1(iii): a configure() during the open debounce window must be
     * observed by resolve-time connection construction. The lifecycle is
     * consequently bound to B, not to the bundle read at open() entry.
     *
     * This was written failing-first and is directly green because T3.1/T3.2
     * keep the resolver as the single published-bundle read source and bind
     * the lifecycle only after debounce has elapsed.
     */
    @Test
    fun `configure during debounce resolves and binds the new bundle`() {
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        repository.onBundlePublished = { gen, fp ->
            stateStore.dispatch(AppAction.BundlePublished(gen, fp))
        }
        repository.configure(baseUrl = "http://host-a.test", slim = true)
        val bundleA = repository.currentClientBundle()!!
        val resolvedBundle = AtomicReference<ClientBundle?>(null)

        val debounced = buildCoordinator(
            openDebounceMs = 50L,
            streamProvider = { _, _ -> error("resolve-time provider must be used") },
            streamConnectionProvider = { sid, directory ->
                val bundle = repository.currentClientBundle()!!
                resolvedBundle.set(bundle)
                TokenStreamConnection(fake.provider(sid, directory), bundle)
            },
            currentBundleProvider = { repository.currentClientBundle() },
        )

        debounced.open("s1", "/work", source = "t3.3-c1-iii")
        repository.configure(baseUrl = "http://host-b.test", slim = true)
        val bundleB = repository.currentClientBundle()!!

        scope.advanceTimeBy(100L)
        runPending()

        assertNotSame("B must replace A during the debounce window", bundleA, bundleB)
        assertSame("resolve must capture the newly published B bundle", bundleB, resolvedBundle.get())
        fake.send(snapshot(partId = "debounced", text = "B-frame"))
        runPending()
        assertEquals("B-frame", stateStore.chatFlow.value.streamingPartTexts["debounced"])

        debounced.close("s1")
        runPending()
    }

    /**
     * T3.3-C1(iv): concurrent open() callers must observe the same published
     * immutable bundle reference, never a field-wise/torn connection snapshot.
     * The companion repository publication test races readers with configure;
     * this coordinator-level test pins the open() read path itself. It is
     * failing-first coverage and is directly green under the T3.1/T3.2 single
     * volatile publication invariant.
     */
    @Test
    fun `concurrent opens resolve one complete published bundle reference`() {
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        repository.configure(baseUrl = "http://host-a.test", slim = true)
        val published = repository.currentClientBundle()!!
        val resolved = ConcurrentLinkedQueue<ClientBundle>()
        val concurrent = buildCoordinator(
            streamProvider = { _, _ -> flow { } },
            currentBundleProvider = {
                repository.currentClientBundle()!!.also { bundle -> resolved.add(bundle) }
            },
        )

        val callers = (0 until 8).map { index ->
            thread(start = true) {
                concurrent.open("open-$index", "/work", source = "t3.3-c1-iv")
            }
        }
        callers.forEach { it.join() }
        runPending()

        assertTrue("every concurrent open must read the published bundle", resolved.size >= 8)
        assertTrue(
            "all opens must see the same complete published bundle object",
            resolved.all { it === published },
        )
        concurrent.close("open-7")
        runPending()
    }

    @Test
    fun `T3-3-C3 C7 frame from a retired bundle is dropped before reducer or chat commit`() {
        val publishedBundle = AtomicReference(bundleRepository.currentClientBundle()!!)
        val bundleAware = buildCoordinator(
            streamProvider = { _, _ -> error("bundle-aware provider must be used") },
            streamConnectionProvider = { _, _ ->
                TokenStreamConnection(fake.provider("s1", "/work"), publishedBundle.get())
            },
            currentBundleProvider = { publishedBundle.get() },
        )

        bundleAware.open("s1", "/work")
        runPending()
        val oldBundle = publishedBundle.get()
        val epoch = bundleAware.epochOf("s1")
        val generation = bundleAware.genOf("s1")

        publishedBundle.set(publishBundle("http://bundle-b.test"))
        bundleAware.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = generation,
            frame = snapshot(partId = "retired-part", text = "stale"),
            capturedRouteInstance = 0L,
            boundBundle = oldBundle,
        )

        assertTrue("retired bundle must not claim a part", bundleAware.ownedPartsForSid("s1").isEmpty())
        bundleAware.close("s1")
        runPending()
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun snapshot(
        partId: String = "p1",
        text: String? = "hello",
        done: Boolean = false,
        truncated: Boolean = false,
        sessionId: String = "s1",
        messageId: String = "m1",
    ) = TokenStreamFrame.PartSnapshot(sessionId, messageId, partId, text, done, truncated)

    /**
     * Channel-backed fake provider. Tracks [openCount] (provider invocations)
     * and the high-water mark of concurrently-live collectors ([maxLiveCollectors])
     * to directly test the max-1 invariant.
     */
    private class FakeStreamProvider {
        val openCount = AtomicInteger(0)
        val liveCollectors = AtomicInteger(0)
        val maxLiveCollectors = AtomicInteger(0)
        var currentChannel: Channel<TokenStreamFrame>? = null
            private set

        val provider: (String, String?) -> Flow<TokenStreamFrame> = { _, _ ->
            openCount.incrementAndGet()
            val ch = Channel<TokenStreamFrame>(Channel.UNLIMITED)
            currentChannel = ch
            flow {
                liveCollectors.incrementAndGet()
                maxLiveCollectors.updateAndGet { cur -> maxOf(cur, liveCollectors.get()) }
                try {
                    for (frame in ch) emit(frame)
                } finally {
                    liveCollectors.decrementAndGet()
                }
            }
        }

        fun send(frame: TokenStreamFrame) {
            val ch = currentChannel ?: error("no active channel")
            val result = ch.trySend(frame)
            assertTrue("send failed: ${result.exceptionOrNull()}", result.isSuccess)
        }
    }
}
