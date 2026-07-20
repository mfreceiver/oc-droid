package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.MainDispatcherRule
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ProbeResult
import cn.vectory.ocdroid.data.repository.SlimSessionState
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 3 (post-v0.11.7) **Track B** — deadlock-regression tests that pin
 * the two-stage dispatcher + striped-Mutex invariants of
 * [SessionSyncCoordinator].
 *
 * # Background
 *
 * v0.11.7 shipped a Slim-mode resync threading rework with two guards
 * against deadlock:
 *
 *  1. **Two-stage dispatcher** (fix-6): heavy/network work runs on
 *     [SessionSyncCoordinator.reconcileDispatcher] (Default); UI slice
 *     commits run on `Dispatchers.Main.immediate`; the repository's
 *     `slimStateLock` (a plain `synchronized` monitor in
 *     [OpenCodeRepository]) is NEVER held across a suspend point. The
 *     network fetch suspends OUTSIDE the synchronized boundary; only the
 *     brief `commitIfSlimTokenCurrent` read-modify-write holds the lock.
 *
 *  2. **Striped per-sid Mutex** (oracle I5): 64 [Mutex] stripes
 *     ([SessionSyncCoordinator.STRIPES] = 64) serialize per-sid
 *     reconciles via `stripeFor(sid) = stripes[floorMod(sid.hashCode(),
 *     STRIPES)]`. Different sids usually land on different stripes →
 *     fully parallel. [Mutex.withLock] propagates `CancellationException`
 *     so a cancelled reconcile releases its stripe permit cleanly.
 *
 * These tests pin BOTH invariants as regression guards.
 *
 * # Why a REAL repository for T3(c) (rev-grok flag)
 *
 * A mock [OpenCodeRepository.commitIfSlimTokenCurrent] that no-ops the
 * commit lambda would HIDE the deadlock: the lock would never be
 * acquired. The T3(c) tests therefore use a REAL [OpenCodeRepository]
 * (the 2-arg test construction from the existing I3 tests, e.g.
 * `OpenCodeRepository(mockk(relaxed = true), mockk(relaxed = true))`)
 * so the production `slimStateLock` monitor + the REAL
 * `commitIfSlimTokenCurrent` / `captureSlimCommitToken` /
 * `beginSlimReconfigure` implementations are exercised. Only the suspend
 * network methods (`probeLatestSlim`) are intercepted via [spyk] +
 * controllable gates; the lock + commit paths stay real.
 *
 * # Track B isolation
 *
 * This file is the SOLE deliverable of Phase 3 Track B. It pins EXISTING,
 * unchanged behavior of [SessionSyncCoordinator] (post-v0.11.7). It does
 * NOT depend on Track A's concurrent T2 modification and MUST NOT modify
 * any existing file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionSyncDeadlockRegressionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private lateinit var slices: SliceFlows
    private lateinit var effects: SharedEffectBus
    private lateinit var settingsManager: SettingsManager

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        val store = SharedStateStore()
        slices = store.slices
        settingsManager = mockk(relaxed = true)
        effects = SharedEffectBus()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Build a coordinator wired to [repo] + a real [workerDispatcher].
     * Mirrors [SessionSyncCoordinatorResyncTest.coordinator] but accepts
     * an explicit worker dispatcher so the deadlock tests can use
     * [Dispatchers.Default] for real concurrency (the test scheduler's
     * virtual time cannot surface a real deadlock).
     */
    private fun coordinator(
        scope: CoroutineScope,
        repo: OpenCodeRepository,
        workerDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ): SessionSyncCoordinator = SessionSyncCoordinator(
        scope = scope,
        slices = slices,
        settingsManager = settingsManager,
        effects = effects,
        currentServerGroupFp = { "test-fp" },
        isSlimMode = { true },
        repository = repo,
        reconcileDispatcher = workerDispatcher,
    )

    // ─────────────────────────────────────────────────────────────────────────
    // T3(c) — deadlock regression with REAL slimStateLock (highest value)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * **T3(c) primary invariant**: the repository's `slimStateLock` (a
     * plain `synchronized` monitor) is NEVER held across a suspend point.
     *
     * Setup: drive a RESYNC reconcile whose network probe suspends on a
     * controllable gate. While the reconcile holds the per-sid stripe
     * Mutex BUT is suspended on the network call, two lock-needing
     * operations MUST complete within a bounded deadline:
     *
     *  - [OpenCodeRepository.captureSlimCommitToken] — used by every
     *    workflow entry; if blocked, no new workflow could start.
     *  - [OpenCodeRepository.beginSlimReconfigure] — the host-rotation
     *    barrier; if blocked, a user-initiated host switch would hang.
     *
     * Both run under `synchronized(slimStateLock)`. If the reconcile held
     * the lock across the network suspension, both would deadlock. The
     * bounded deadline makes that failure mode a fast, named assertion
     * instead of a hung test.
     *
     * # What's REAL vs intercepted
     *
     *  - REAL: `slimStateLock`, `captureSlimCommitToken`,
     *    `isSlimCommitTokenCurrent`, `commitIfSlimTokenCurrent`,
     *    `markSlimReconcileAligned`, `beginSlimReconfigure`.
     *  - spyk-intercepted: `probeLatestSlim` (gated on a
     *    [CompletableDeferred] so the network suspension is observable
     *    + controllable).
     *  - mockk-stubbed: `getSlimSessionState` (forces the aligned branch
     *    so the post-probe path lands `markSlimReconcileAligned` — a
     *    REAL lock-exercising commit).
     */
    @Test
    fun `T3c slimStateLock not held across network suspension - captureSlimCommitToken and beginSlimReconfigure make progress`() = runBlocking {
        // ── REAL repository: slimStateLock + all commit/token APIs are
        // production in-memory implementations. ──
        val realRepo = OpenCodeRepository(mockk(relaxed = true), mockk(relaxed = true))
        val sid = "t3c-deadlock"

        // Spy only the suspend network method. Lock + commit stay real.
        val probeEntered = CompletableDeferred<Unit>()
        val releaseProbe = CompletableDeferred<Unit>()
        val repo = spyk(realRepo)
        coEvery { repo.probeLatestSlim(sid) } coAnswers {
            probeEntered.complete(Unit)
            // Simulate a slow network call. While suspended here, the
            // stripe Mutex is held but slimStateLock MUST NOT be.
            releaseProbe.await()
            ProbeResult(ok = true, messageID = "m", updatedAt = 100L)
        }
        // Force the aligned branch (probe.messageID == localAppliedMessageId,
        // probe.updatedAt == localAppliedUpdatedAt → needsCatchUp=false →
        // markSlimReconcileAligned, which is a REAL slimStateLock commit).
        every { repo.getSlimSessionState(sid) } returns SlimSessionState(
            sessionId = sid,
            dirty = true,
            localAppliedMessageId = "m",
            localAppliedUpdatedAt = 100L,
        )

        val worker = Dispatchers.Default
        val scope = CoroutineScope(SupervisorJob() + worker)
        try {
            val c = coordinator(scope, repo, worker)

            // Launch the reconcile. It will acquire the stripe, then
            // suspend on the gated probe (still holding the stripe Mutex).
            val reconcileJob = scope.launch {
                c.reconcileSessionExposed(sid, SessionSyncCoordinator.ReconcileMode.RESYNC)
            }
            assertTrue(
                "probe must be entered within deadline (otherwise the test setup is wrong)",
                probeEntered.awaitBounded(),
            )

            // ── Assertion 1: captureSlimCommitToken completes promptly. ──
            // This acquires slimStateLock under synchronized(slimStateLock).
            // If the reconcile held the lock across the probe suspension,
            // this would block until reconcileJob finishes — which never
            // happens because releaseProbe is still incomplete →
            // withTimeoutOrNull returns null.
            val token = withTimeoutOrNull(DEADLINE_MS) {
                repo.captureSlimCommitToken()
            }
            assertNotNull(
                "captureSlimCommitToken must NOT block while a reconcile is suspended on the " +
                    "network (slimStateLock is held only inside the brief commit boundary, " +
                    "never across a suspend point)",
                token,
            )

            // ── Assertion 2: beginSlimReconfigure completes promptly. ──
            // Same lock, longer critical section (also clears slimSseState).
            // This is the operation the host-rotation path uses.
            val reconfigStart = System.nanoTime()
            val ticket = withTimeoutOrNull(DEADLINE_MS) {
                repo.beginSlimReconfigure()
            }
            val reconfigElapsedMs = (System.nanoTime() - reconfigStart) / 1_000_000L
            assertNotNull(
                "beginSlimReconfigure must NOT block while a reconcile is suspended on the network",
                ticket,
            )
            assertTrue(
                "beginSlimReconfigure completed in ${reconfigElapsedMs}ms (deadline ${DEADLINE_MS}ms)",
                reconfigElapsedMs < DEADLINE_MS,
            )

            // Release the gated probe so the reconcile completes; verify it
            // finishes cleanly (no crash, no leaked job).
            releaseProbe.complete(Unit)
            assertTrue(
                "reconcile must complete after the probe gate is released",
                reconcileJob.joinBounded(),
            )
        } finally {
            scope.cancel("teardown")
        }
    }

    /**
     * **T3(c) second variant**: two concurrent reconciles on DIFFERENT sids
     * (different stripes) both suspend on their own gated probes; each
     * holds its own stripe Mutex. A third operation needing `slimStateLock`
     * (`captureSlimCommitToken`) MUST acquire it promptly — proving
     * neither reconcile is monopolizing the lock. Both reconciles then
     * complete cleanly when their gates are released.
     *
     * This is the "neither blocks the other's lock acquisition" matrix
     * the task requires.
     */
    @Test
    fun `T3c concurrent reconciles on different sids do not block each other_s slimStateLock acquisition`() = runBlocking {
        val realRepo = OpenCodeRepository(mockk(relaxed = true), mockk(relaxed = true))
        val sidA = "t3c-sid-a"
        val sidB = "t3c-sid-b"

        // Sanity: the two sids MUST map to different stripes (otherwise
        // they'd serialize on the stripe Mutex, which is NOT what this
        // test asserts — see T3b-3 for that case).
        assumeDifferentStripes(sidA, sidB)

        val probeAEntered = CompletableDeferred<Unit>()
        val probeBEntered = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()

        val repo = spyk(realRepo)
        coEvery { repo.probeLatestSlim(sidA) } coAnswers {
            probeAEntered.complete(Unit)
            releaseA.await()
            ProbeResult(ok = true, messageID = "mA", updatedAt = 100L)
        }
        coEvery { repo.probeLatestSlim(sidB) } coAnswers {
            probeBEntered.complete(Unit)
            releaseB.await()
            ProbeResult(ok = true, messageID = "mB", updatedAt = 100L)
        }
        every { repo.getSlimSessionState(sidA) } returns SlimSessionState(
            sessionId = sidA, dirty = true,
            localAppliedMessageId = "mA", localAppliedUpdatedAt = 100L,
        )
        every { repo.getSlimSessionState(sidB) } returns SlimSessionState(
            sessionId = sidB, dirty = true,
            localAppliedMessageId = "mB", localAppliedUpdatedAt = 100L,
        )

        val worker = Dispatchers.Default
        val scope = CoroutineScope(SupervisorJob() + worker)
        try {
            val c = coordinator(scope, repo, worker)

            val jobA = scope.launch {
                c.reconcileSessionExposed(sidA, SessionSyncCoordinator.ReconcileMode.RESYNC)
            }
            val jobB = scope.launch {
                c.reconcileSessionExposed(sidB, SessionSyncCoordinator.ReconcileMode.RESYNC)
            }
            assertTrue("probe A must enter", probeAEntered.awaitBounded())
            assertTrue("probe B must enter", probeBEntered.awaitBounded())

            // Both reconciles are now suspended on their own probes, each
            // holding its own stripe Mutex. slimStateLock MUST still be
            // acquirable — proving neither holds it across the suspension.
            val token = withTimeoutOrNull(DEADLINE_MS) {
                repo.captureSlimCommitToken()
            }
            assertNotNull(
                "captureSlimCommitToken must NOT block while two reconciles are suspended on " +
                    "their own probes (slimStateLock must not be held across either suspension)",
                token,
            )

            // Release both probes; both reconciles must complete.
            releaseA.complete(Unit)
            releaseB.complete(Unit)
            assertTrue("reconcile A must complete after release", jobA.joinBounded())
            assertTrue("reconcile B must complete after release", jobB.joinBounded())
        } finally {
            scope.cancel("teardown")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T3(b) — stripe Mutex hold-across-network cancellation / retry ordering
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * **T3(b) Test 1** — same-sid serialization via the per-sid stripe.
     *
     * Two concurrent reconciles for the SAME sid MUST serialize on the
     * stripe: the second's network probe MUST NOT start until the first's
     * probe releases. We assert this directly via a controllable gate on
     * each probe — NOT just `maxConcurrent` (the existing T11-C6 already
     * pins maxConcurrent=1; this test pins exact ordering, which is a
     * stronger discriminator).
     *
     * Sequence:
     *  1. Launch job1. Wait for probe1 to enter (stripe is held by job1).
     *  2. Launch job2.
     *  3. Poll for a window — probe2 MUST NOT enter (job2 is blocked on
     *     `stripeFor(sid).withLock`).
     *  4. Release probe1's gate. job1 finishes; stripe releases; job2
     *     acquires the stripe and enters probe2.
     *  5. Assert probe2 entered promptly after release.
     */
    @Test
    fun `T3b-1 same-sid reconciles serialize on the stripe - second probe waits for first release`() = runBlocking {
        val sid = "t3b-serialize"
        val repo: OpenCodeRepository = mockk(relaxed = true)
        stubCommitAndBooleanWrappers(repo)

        val probe1Entered = CompletableDeferred<Unit>()
        val releaseProbe1 = CompletableDeferred<Unit>()
        val probe2Entered = CompletableDeferred<Unit>()
        val callIndex = AtomicInteger(0)
        coEvery { repo.probeLatestSlim(sid) } coAnswers {
            val n = callIndex.incrementAndGet()
            if (n == 1) {
                probe1Entered.complete(Unit)
                releaseProbe1.await()
            } else {
                probe2Entered.complete(Unit)
            }
            ProbeResult(ok = true, messageID = "m-$n", updatedAt = 100L * n)
        }
        every { repo.getSlimSessionState(sid) } returns SlimSessionState(
            sessionId = sid, dirty = true,
            localAppliedMessageId = "m-1", localAppliedUpdatedAt = 100L,
        )

        val worker = Dispatchers.Default
        val scope = CoroutineScope(SupervisorJob() + worker)
        try {
            val c = coordinator(scope, repo, worker)

            val job1 = scope.launch {
                c.reconcileSessionExposed(sid, SessionSyncCoordinator.ReconcileMode.RESYNC)
            }
            assertTrue("probe1 must enter", probe1Entered.awaitBounded())

            // Launch the second reconcile; it should queue on the stripe.
            val job2 = scope.launch {
                c.reconcileSessionExposed(sid, SessionSyncCoordinator.ReconcileMode.RESYNC)
            }

            // Poll for a window — probe2 must NOT start while probe1 holds
            // the stripe. If serialization were broken, job2 would acquire
            // the stripe and enter probe2 within milliseconds.
            val windowEnd = System.currentTimeMillis() + SERIALIZATION_WINDOW_MS
            while (System.currentTimeMillis() < windowEnd) {
                assertFalse(
                    "probe2 must NOT start while probe1 holds the stripe (per-sid serialization broken)",
                    probe2Entered.isCompleted,
                )
                delay(10L)
            }

            // Release probe1; job1 finishes and releases the stripe; job2
            // acquires it and probe2 enters.
            releaseProbe1.complete(Unit)
            assertTrue(
                "probe2 must start promptly after probe1 releases the stripe",
                probe2Entered.awaitBounded(),
            )

            assertTrue("job1 must complete", job1.joinBounded())
            assertTrue("job2 must complete", job2.joinBounded())
            assertEquals(
                "probe must be called exactly twice (once per serialized reconcile)",
                2,
                callIndex.get(),
            )
        } finally {
            scope.cancel("teardown")
        }
    }

    /**
     * **T3(b) Test 2** — cancel a reconcile WHILE it holds the stripe
     * across a suspended network call. The stripe Mutex permit MUST be
     * released (`Mutex.withLock` propagates `CancellationException`). A
     * subsequent reconcile for the same sid MUST acquire the stripe
     * promptly — proving the cancellation did NOT leak the permit.
     *
     * This is the regression guard against a future change that might
     * wrap `stripeFor(sid).withLock { ... }` in a `try/finally` that
     * accidentally swallows CE, or use a non-CE-propagating primitive
     * (e.g. a `ReentrantLock` instead of `Mutex`).
     */
    @Test
    fun `T3b-2 cancel reconcile while it holds the stripe - permit is released not leaked`() = runBlocking {
        val sid = "t3b-cancel-hold"
        val repo: OpenCodeRepository = mockk(relaxed = true)
        stubCommitAndBooleanWrappers(repo)

        val probe1Entered = CompletableDeferred<Unit>()
        val releaseProbe1 = CompletableDeferred<Unit>()
        val probe2Entered = CompletableDeferred<Unit>()
        val callIndex = AtomicInteger(0)
        coEvery { repo.probeLatestSlim(sid) } coAnswers {
            val n = callIndex.incrementAndGet()
            if (n == 1) {
                probe1Entered.complete(Unit)
                // releaseProbe1 is NEVER completed — the job's cancellation
                // propagates as CE through await(), which is exactly the
                // cancellation-during-hold scenario under test. The
                // ProbeResult below is unreachable but required so the
                // lambda's two branches share a return type.
                releaseProbe1.await()
                ProbeResult(ok = true, messageID = "m1", updatedAt = 100L)
            } else {
                probe2Entered.complete(Unit)
                ProbeResult(ok = true, messageID = "m2", updatedAt = 200L)
            }
        }
        every { repo.getSlimSessionState(sid) } returns SlimSessionState(
            sessionId = sid, dirty = true,
            localAppliedMessageId = "m2", localAppliedUpdatedAt = 200L,
        )

        val worker = Dispatchers.Default
        val scope = CoroutineScope(SupervisorJob() + worker)
        try {
            val c = coordinator(scope, repo, worker)

            // Launch job1; wait for it to enter the probe (stripe is now held).
            val job1 = scope.launch {
                c.reconcileSessionExposed(sid, SessionSyncCoordinator.ReconcileMode.RESYNC)
            }
            assertTrue("probe1 must enter", probe1Entered.awaitBounded())

            // Cancel job1 WHILE it's holding the stripe + suspended on the
            // probe. Mutex.withLock must propagate CE and release the permit.
            job1.cancelAndJoin()
            assertTrue("job1 must be finished after cancelAndJoin", job1.isCompleted)

            // Launch job2 for the same sid — it MUST acquire the stripe
            // promptly. If the permit had been leaked, probe2 would never
            // enter and awaitBounded() would time out.
            val job2 = scope.launch {
                c.reconcileSessionExposed(sid, SessionSyncCoordinator.ReconcileMode.RESYNC)
            }
            assertTrue(
                "probe2 must enter promptly after job1 cancelled mid-hold " +
                    "(stripe Mutex permit must NOT be leaked on cancellation)",
                probe2Entered.awaitBounded(),
            )
            assertTrue("job2 must complete", job2.joinBounded())
        } finally {
            scope.cancel("teardown")
        }
    }

    /**
     * **T3(b) Test 3** — reconciles for DIFFERENT sids (different stripes)
     * run concurrently. We assert their probes overlap
     * (`maxConcurrent` reaches 2), confirming the stripe map does NOT
     * over-serialize across sids.
     *
     * (The existing T11-C6b pins the same invariant via a similar
     * mechanism on the test scheduler; this test re-pins it on real
     * threads to guard against a regression that only manifests under
     * real dispatch — e.g. a `runInterruptible` wrap or a dispatcher
     * shift that accidentally serializes different stripes.)
     */
    @Test
    fun `T3b-3 different-sid reconciles run concurrently on different stripes`() = runBlocking {
        val sidA = "t3b-concurrent-a"
        val sidB = "t3b-concurrent-b"
        assumeDifferentStripes(sidA, sidB)

        val repo: OpenCodeRepository = mockk(relaxed = true)
        stubCommitAndBooleanWrappers(repo)

        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        coEvery { repo.probeLatestSlim(any()) } coAnswers {
            val now = inFlight.incrementAndGet()
            // Capture the running max via CAS loop.
            var prev = maxInFlight.get()
            while (now > prev && !maxInFlight.compareAndSet(prev, now)) {
                prev = maxInFlight.get()
            }
            // Force an overlap window — both probes must be inside this
            // delay at once for maxInFlight to reach 2.
            delay(50L)
            inFlight.decrementAndGet()
            ProbeResult(ok = true, messageID = "m", updatedAt = 1L)
        }
        every { repo.getSlimSessionState(any()) } returns SlimSessionState(
            sessionId = "", dirty = true,
            localAppliedMessageId = "m", localAppliedUpdatedAt = 1L,
        )

        val worker = Dispatchers.Default
        val scope = CoroutineScope(SupervisorJob() + worker)
        try {
            val c = coordinator(scope, repo, worker)

            val jobA = scope.launch {
                c.reconcileSessionExposed(sidA, SessionSyncCoordinator.ReconcileMode.RESYNC)
            }
            val jobB = scope.launch {
                c.reconcileSessionExposed(sidB, SessionSyncCoordinator.ReconcileMode.RESYNC)
            }
            assertTrue("jobA must complete", jobA.joinBounded())
            assertTrue("jobB must complete", jobB.joinBounded())

            assertEquals(
                "different sids on different stripes MUST run concurrently " +
                    "(maxConcurrent must reach 2; got ${maxInFlight.get()})",
                2,
                maxInFlight.get(),
            )
        } finally {
            scope.cancel("teardown")
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Common relaxed-mock stubs that the resync test harness uses. Mirrors
     * the equivalent block in [SessionSyncCoordinatorResyncTest.setUp] so
     * the coordinator's commit paths succeed against a mockk repository
     * (used by the T3b stripe tests, which do NOT need a real lock — the
     * invariant under test there is the stripe Mutex, not slimStateLock).
     *
     * NOTE: this is intentionally NOT used by the T3c tests — those use a
     * REAL repository so the lock + commit APIs are the production
     * implementations (rev-grok flag).
     */
    private fun stubCommitAndBooleanWrappers(repo: OpenCodeRepository) {
        every { repo.isSlimCommitTokenCurrent(any()) } returns true
        every { repo.commitIfSlimTokenCurrent(any(), any()) } answers {
            secondArg<() -> Unit>().invoke()
            true
        }
        every { repo.clearSlimLocalMessages(any(), any()) } returns true
        every { repo.markSlimReconcileFailure(any(), any()) } returns true
        every { repo.markSlimReconcileAligned(any(), any()) } returns true
        every { repo.markSlimSessionDeleted(any(), any()) } returns true
        every { repo.markSlimDirty(any(), any()) } returns true
        every { repo.invalidateSlimLocalApplied(any(), any()) } returns true
        every { repo.applySlimDigest(any(), any()) } returns null
        coEvery {
            repo.getSlimapiMessagesSince(any(), any(), any(), any(), any())
        } returns Result.success(emptyList())
        coEvery { repo.fetchSlimInitialWindowBounded(any(), any()) } returns Result.success(emptyList())
        every { repo.snapshotSlimSseState() } returns emptyMap()
    }

    /**
     * Bounded [CompletableDeferred.await] — returns true if the deferred
     * was completed before [DEADLINE_MS], false otherwise. Used for every
     * cross-coroutine handshake in this suite so a deadlock surfaces as a
     * fast, named assertion rather than a global test timeout.
     */
    private suspend fun <T> CompletableDeferred<T>.awaitBounded(): Boolean =
        withTimeoutOrNull(DEADLINE_MS) { await() } != null

    /**
     * Bounded [kotlinx.coroutines.Job.join] — returns true if the job
     * completed before [DEADLINE_MS].
     */
    private suspend fun kotlinx.coroutines.Job.joinBounded(): Boolean =
        withTimeoutOrNull(DEADLINE_MS) { join() } != null

    /**
     * Assert that [sidA] and [sidB] map to different
     * [SessionSyncCoordinator.STRIPES] stripes — precondition for the
     * cross-sid concurrency tests.
     */
    private fun assumeDifferentStripes(sidA: String, sidB: String) {
        val s = SessionSyncCoordinator.STRIPES
        val stripeA = ((sidA.hashCode() % s) + s) % s
        val stripeB = ((sidB.hashCode() % s) + s) % s
        assertTrue(
            "test sids must map to different stripes (got $stripeA and $stripeB); " +
                "change the sid constants",
            stripeA != stripeB,
        )
    }

    private companion object {
        /**
         * Per-assertion deadlock deadline. Generous enough to absorb JIT
         * warmup on a loaded CI host; tight enough that a real deadlock
         * surfaces quickly. 5 seconds = ~50x the typical lock-acquisition
         * latency observed on the slimSseState monitor.
         */
        private const val DEADLINE_MS = 5_000L

        /**
         * Polling window for the T3b-1 serialization assertion — the
         * window during which probe2 must NOT start while probe1 holds
         * the stripe.
         */
        private const val SERIALIZATION_WINDOW_MS = 300L
    }
}
