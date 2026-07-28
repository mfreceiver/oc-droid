package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.RecordingStreamingServiceLauncher
import cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import cn.vectory.ocdroid.data.model.HealthResponse
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.controller.sse.TokenStreamCoordinator
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.util.SettingsManager

/**
 * Threading-aware concurrency tests for [ConnectionCoordinator.cancelSseForReconfigure]
 * and [ConnectionCoordinator.coldStartReconnect].
 *
 * Uses real threads + CountDownLatch to expose races that single-threaded
 * dispatchers cannot reproduce. After the synchronized fix, these verify:
 *
 *   **Invariant**: ALL teardowns (registered before or during coldStart's
 *   join loop) complete before the health probe runs. The read-modify-write
 *   of the pending-teardown Job field is atomic so concurrent callers cannot
 *   both read null and launch parallel unsynchronized teardowns.
 *
 *   **TOCTOU closure**: coldStart's loop uses an atomic read-and-clear inside
 *   the lock so a concurrent cancelSseForReconfigure cannot slip between the
 *   identity check and the field nullification.
 *
 * NOTE: coldStartReconnect() is non-suspending (scope.launch + return), so
 * the test cannot use a simple latch after the call — the while-loop runs
 * in a child coroutine. Instead, tests poll coVerify or use a timing delay
 * (the mock's teardown barrier ensures deterministic ordering).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionCoordinatorConcurrentTest {

    private lateinit var slices: SliceFlows
    private lateinit var repository: OpenCodeRepository
    private lateinit var settingsManager: SettingsManager
    private lateinit var effects: SharedEffectBus
    private var now: Long = 100_000L
    private lateinit var identityStore: ConnectionIdentityStore
    private lateinit var bootstrapCoordinator: ConnectionBootstrapCoordinator
    private lateinit var launcher: RecordingStreamingServiceLauncher

    @Before
    fun setUp() {
        val stateStore = SharedStateStore()
        slices = stateStore.slices
        repository = mockk(relaxed = true)
        settingsManager = mockk(relaxed = true)
        effects = SharedEffectBus()
        identityStore = ConnectionIdentityStore()
        bootstrapCoordinator = ConnectionBootstrapCoordinator()
        launcher = RecordingStreamingServiceLauncher()
        every { settingsManager.currentWorkdir } returns null
        coEvery { repository.getSessionsForDirectory(any()) } returns Result.success(emptyList())
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── concurrent cancelSseForReconfigure ───────────────────────────────

    /**
     * Two real threads calling cancelSseForReconfigure simultaneously, released
     * by a CyclicBarrier.
     *
     * WITHOUT synchronized: both threads read prev=null → parallel teardowns →
     * only the second Job retained → first Job "lost".
     *
     * WITH synchronized: the second thread atomically reads the first's Job →
     * chains onto it → both teardowns run sequentially → ALL tracked.
     */
    @Test
    fun `concurrent cancelSseForReconfigure serializes all teardowns`() {
        val lifecycleCoordinator = mockk<StreamingLifecycleCoordinator>()
        val teardownCalled = AtomicInteger(0)
        val teardownComplete = CompletableDeferred<Unit>()
        coEvery { lifecycleCoordinator.teardownNoSourceAndAwait() } coAnswers {
            teardownCalled.incrementAndGet()
            teardownComplete.await()
            Unit
        }

        val scope = CoroutineScope(Dispatchers.Default)
        val cc = ConnectionCoordinator(
            scope = scope,
            slices = slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effects,
            serverCompatProfile = ServerCompatProfile(),
            clock = { now },
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            streamingServiceLauncher = launcher,
            streamingLifecycleCoordinator = lifecycleCoordinator,
        )

        val barrier = java.util.concurrent.CyclicBarrier(2)
        val doneLatch = CountDownLatch(2)

        val t1 = Thread {
            barrier.await()
            cc.cancelSseForReconfigure()
            doneLatch.countDown()
        }
        val t2 = Thread {
            barrier.await()
            cc.cancelSseForReconfigure()
            doneLatch.countDown()
        }

        t1.start()
        t2.start()
        assertTrue("both cancellers done", doneLatch.await(10, TimeUnit.SECONDS))
        assertFalse("t1 completed", t1.isAlive)
        assertFalse("t2 completed", t2.isAlive)

        // At most 1 teardown may have started (second call joined first).
        val started = teardownCalled.get()
        assertTrue(
            "At most 1 teardown before barrier (got $started; >1 means parallel launch)",
            started <= 1,
        )

        // Release barrier → both teardowns complete
        teardownComplete.complete(Unit)
        Thread.sleep(500)

        assertEquals("Both teardowns must complete", 2, teardownCalled.get())
        scope.cancel()
    }

    // ── coldStartReconnect + concurrent cancelSseForReconfigure ──────────

    /**
     * coldStartReconnect joins the pending teardown. WHILE it is blocked
     * inside job.join(), a new cancelSseForReconfigure fires. The fix ensures
     * that after the first join completes, the loop re-reads the field, finds
     * the new Job, and joins it too. Both teardowns finish before the probe.
     *
     * Uses CountDownLatch via the teardown mock — the barrier is what the
     * teardown body blocks on. Releasing it unblocks the first teardown,
     * which completes Job1, which unblocks coldStart's join, which reads the
     * second Job (set by cancel #2 during the join), joins it, then probes.
     * A small sleep after the barrier release gives the coroutines time to
     * propagate through the chain.
     */
    @Test
    fun `coldStartReconnect awaits all teardowns even when new cancel happens during join`() {
        val lifecycleCoordinator = mockk<StreamingLifecycleCoordinator>()
        val teardownCount = AtomicInteger(0)
        val teardownBarrier = CompletableDeferred<Unit>()
        val firstCancelDone = CountDownLatch(1)
        coEvery { lifecycleCoordinator.teardownNoSourceAndAwait() } coAnswers {
            teardownCount.incrementAndGet()
            teardownBarrier.await()
            Unit
        }
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = false, version = "1.0"))

        val scope = CoroutineScope(Dispatchers.Default)
        val cc = ConnectionCoordinator(
            scope = scope,
            slices = slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effects,
            serverCompatProfile = ServerCompatProfile(),
            clock = { now },
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            streamingServiceLauncher = launcher,
            streamingLifecycleCoordinator = lifecycleCoordinator,
        )

        // Step 1: register teardown #1 (blocked on teardownBarrier)
        scope.launch {
            cc.cancelSseForReconfigure()
            firstCancelDone.countDown()
        }
        assertTrue("first cancel registered", firstCancelDone.await(5, TimeUnit.SECONDS))

        // Step 2: launch coldStart — its while-loop joins Job1 (blocked)
        scope.launch { cc.coldStartReconnect() }
        Thread.sleep(300)

        // Step 3: register teardown #2 while coldStart is inside Job1.join()
        scope.launch { cc.cancelSseForReconfigure() }
        Thread.sleep(300)

        // Step 4: release barrier → Job1 teardown runs → Job1 completes →
        // coldStart joins Job2 (set by cancel #2) → Job2 teardown runs →
        // coldStart enters loop, reads null → break → probe
        teardownBarrier.complete(Unit)
        Thread.sleep(2000) // ample time for chain to propagate

        // All teardowns completed
        assertEquals("Both teardowns must run", 2, teardownCount.get())

        // Probe must have run after all teardowns
        coVerify(atLeast = 1) { repository.checkHealth() }
        scope.cancel()
    }

    /**
     * coldStartReconnect must NOT probe before the pending teardown completes.
     * Registers a teardown, launches coldStart, verifies zero probes while
     * the teardown is blocked, then releases the teardown and verifies probe.
     */
    @Test
    fun `coldStartReconnect no probe before teardown completes`() {
        val lifecycleCoordinator = mockk<StreamingLifecycleCoordinator>()
        val teardownBarrier = CompletableDeferred<Unit>()
        coEvery { lifecycleCoordinator.teardownNoSourceAndAwait() } coAnswers {
            teardownBarrier.await()
            Unit
        }
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = false, version = "1.0"))

        val scope = CoroutineScope(Dispatchers.Default)
        val cc = ConnectionCoordinator(
            scope = scope,
            slices = slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effects,
            serverCompatProfile = ServerCompatProfile(),
            clock = { now },
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            streamingServiceLauncher = launcher,
            streamingLifecycleCoordinator = lifecycleCoordinator,
        )

        // Register teardown
        val cancelDone = CountDownLatch(1)
        scope.launch {
            cc.cancelSseForReconfigure()
            cancelDone.countDown()
        }
        assertTrue("cancel registered", cancelDone.await(5, TimeUnit.SECONDS))

        // Launch coldStart — enters join loop, blocks on Job1.join()
        scope.launch { cc.coldStartReconnect() }
        Thread.sleep(500)

        // coldStart must be blocked in join — no probe yet
        coVerify(exactly = 0) { repository.checkHealth() }

        // Complete the teardown
        teardownBarrier.complete(Unit)
        Thread.sleep(2000)

        // coldStart's join returns → loop reads null → break → probe
        coVerify(atLeast = 1) { repository.checkHealth() }
        scope.cancel()
    }

    /**
     * RED-GREEN: cancel #2 during coldStart's join must NOT let Job2's
     * teardown body run before Job1 completes. Uses two independent barriers:
     *
     *   barrier1 — blocks Job1's teardown body from completing
     *   barrier2 — blocks Job2's teardown body from completing
     *
     * With the BUG (premature field clear inside lock before join):
     *   cancel #2 reads prev=null → Job2's body runs concurrently with Job1.
     *
     * With the FIX (identity-guarded clear after join):
     *   cancel #2 reads prev=Job1 → Job2 is blocked on prev?.join(Job1) →
     *   Job2's body has NOT entered while barrier1 is held.
     *
     * Uses a latch from inside the teardown mock so the assertion is
     * timing-independent: whether Job2 entered teardown is a boolean fact,
     * not a race-vs-sleep bet.
     */
    @Test
    fun `cancel during coldStart join does not let Job2 teardown body run before Job1 completes`() {
        val lifecycleCoordinator = mockk<StreamingLifecycleCoordinator>()
        val teardownEntered = AtomicInteger(0)
        val barrier1 = CompletableDeferred<Unit>()
        val barrier2 = CompletableDeferred<Unit>()
        val job1StartedLatch = CountDownLatch(1)
        val job2StartedLatch = CountDownLatch(1)
        coEvery { lifecycleCoordinator.teardownNoSourceAndAwait() } coAnswers {
            val n = teardownEntered.incrementAndGet()
            when (n) {
                1 -> {
                    job1StartedLatch.countDown()
                    barrier1.await()
                }
                2 -> {
                    job2StartedLatch.countDown()
                    barrier2.await()
                }
            }
            Unit
        }
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = false, version = "1.0"))

        val scope = CoroutineScope(Dispatchers.Default)
        val cc = ConnectionCoordinator(
            scope = scope,
            slices = slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effects,
            serverCompatProfile = ServerCompatProfile(),
            clock = { now },
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            streamingServiceLauncher = launcher,
            streamingLifecycleCoordinator = lifecycleCoordinator,
        )

        // Step 1: Cancel #1 → Job1 created, teardown body blocks on barrier1
        scope.launch { cc.cancelSseForReconfigure() }
        assertTrue(
            "Job1 must start teardown body",
            job1StartedLatch.await(5, TimeUnit.SECONDS),
        )

        // Step 2: coldStart enters while-loop, reads Job1, joins on it (blocked)
        scope.launch { cc.coldStartReconnect() }
        Thread.sleep(300) // allow coldStart to enter join

        // Step 3: Cancel #2 fires during coldStart's join of Job1
        scope.launch { cc.cancelSseForReconfigure() }
        Thread.sleep(500) // allow Job2 to be scheduled

        // KEY ASSERTION — RED with BUG, GREEN with FIX:
        // Job2's teardown body must NOT have entered while barrier1 is held.
        // With the BUG (premature clear inside lock), cancel #2 read prev=null
        // and Job2's body runs immediately → job2StartedLatch counts down.
        // With the FIX (identity-guarded clear after join), cancel #2 reads
        // prev=Job1 → Job2 is blocked on prev?.join(Job1) → latch stays 1.
        assertFalse(
            "Job2's teardown body must NOT enter before Job1 completes",
            job2StartedLatch.await(1, TimeUnit.SECONDS),
        )

        // Step 4: Release Job1 → Job1 complete → coldStart join returns →
        // coldStart loops, reads Job2, joins on it → Job2 teardown body runs
        // (blocked on barrier2)
        barrier1.complete(Unit)
        assertTrue(
            "Job2 must start teardown body after Job1 completes",
            job2StartedLatch.await(5, TimeUnit.SECONDS),
        )

        // Step 5: Release Job2 → all teardowns done
        barrier2.complete(Unit)
        Thread.sleep(500)

        assertEquals("Both teardowns must run", 2, teardownEntered.get())
        coVerify(atLeast = 1) { repository.checkHealth() }
        scope.cancel()
    }

    /**
     * coldStart's atomic read-check-clear eliminates the TOCTOU between
     * identity check and field nullification. Registers teardown #1, launches
     * coldStart, fires cancel #2 concurrently. Verifies both teardowns.
     */
    @Test
    fun `coldStartReconnect identity check to clear is atomic with concurrent cancel`() {
        val lifecycleCoordinator = mockk<StreamingLifecycleCoordinator>()
        val teardownBarrier = CompletableDeferred<Unit>()
        val teardownCount = AtomicInteger(0)
        coEvery { lifecycleCoordinator.teardownNoSourceAndAwait() } coAnswers {
            teardownCount.incrementAndGet()
            teardownBarrier.await()
            Unit
        }
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = false, version = "1.0"))

        val scope = CoroutineScope(Dispatchers.Default)
        val cc = ConnectionCoordinator(
            scope = scope,
            slices = slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effects,
            serverCompatProfile = ServerCompatProfile(),
            clock = { now },
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            streamingServiceLauncher = launcher,
            streamingLifecycleCoordinator = lifecycleCoordinator,
        )

        // Register teardown #1
        val cancelDone = CountDownLatch(1)
        scope.launch {
            cc.cancelSseForReconfigure()
            cancelDone.countDown()
        }
        assertTrue("cancel registered", cancelDone.await(5, TimeUnit.SECONDS))

        // Launch coldStart
        scope.launch { cc.coldStartReconnect() }
        Thread.sleep(300)

        // Concurrent cancel #2
        scope.launch { cc.cancelSseForReconfigure() }
        Thread.sleep(300)

        // Release barrier
        teardownBarrier.complete(Unit)
        Thread.sleep(2000)

        assertEquals("Both teardowns must run", 2, teardownCount.get())
        scope.cancel()
    }

    /**
     * RED-GREEN: cancelSseForReconfigure registers the teardown Job atomically
     * BEFORE any side effect (tokenStream.close). If close blocks (e.g. on
     * bundleCommitLock), coldStartReconnect must see a non-null pending Job
     * and join it, NOT probe before close completes.
     *
     * Uses two CountDownLatches for deterministic coordination:
     *   closeBlocker — blocks close(sid) from returning
     *   closeEntered — signals that close was entered
     *   probeLatch   — counts down when repository.checkHealth() is called
     *
     * With the BUG (close outside lock before Job registration):
     *   cancelSseForReconfigure enters close(sid) → blocks on closeBlocker →
     *   pendingReconfigureTeardown still null → coldStart reads null → probes →
     *   probeLatch triggered before close released → assertion FAILS (RED).
     *
     * With the FIX (LAZY Job body contains close):
     *   cancelSseForReconfigure sets pending field atomically (lock), then
     *   body starts asynchronously → close blocks inside body → coldStart
     *   reads non-null Job → joins (blocks on close completion) → probeLatch
     *   NOT triggered → assertion passes (GREEN).
     */
    @Test
    fun `cancelSseForReconfigure registers job before any close side effect`() {
        val lifecycleCoordinator = mockk<StreamingLifecycleCoordinator>()
        val closeBlocker = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val probeLatch = CountDownLatch(1)
        val tokenStream = mockk<TokenStreamCoordinator>()
        every { tokenStream.close(any()) } answers {
            closeEntered.countDown()
            assertTrue(closeBlocker.await(10, TimeUnit.SECONDS))
        }
        coEvery { lifecycleCoordinator.teardownNoSourceAndAwait() } returns Unit
        coEvery { repository.checkHealth() } coAnswers {
            probeLatch.countDown()
            Result.success(HealthResponse(healthy = false, version = "1.0"))
        }

        val scope = CoroutineScope(Dispatchers.Default)
        slices.mutateChat { it.copy(currentSessionId = "test-sid") }
        val cc = ConnectionCoordinator(
            scope = scope,
            slices = slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effects,
            serverCompatProfile = ServerCompatProfile(),
            clock = { now },
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            streamingServiceLauncher = launcher,
            streamingLifecycleCoordinator = lifecycleCoordinator,
            tokenStreamCoordinator = tokenStream,
        )

        // Launch cancelSseForReconfigure in background
        scope.launch { cc.cancelSseForReconfigure() }

        // Wait for close to be entered.
        // With OLD code: close runs synchronously, blocks on closeBlocker.
        // With NEW code: close is in LAZY body — may or may not have entered.
        closeEntered.await(5, TimeUnit.SECONDS)
        // At this point (for both OLD and NEW, with or without close entered):
        //   OLD: pendingReconfigureTeardown is still null (close blocked before lock)
        //   NEW: pendingReconfigureTeardown is set (atomic assignment in lock)

        // Launch coldStartReconnect
        scope.launch { cc.coldStartReconnect() }

        // KEY ASSERTION — fully deterministic via latch, no Thread.sleep:
        // Probe must NOT happen while close is blocked (or LAZY body not started).
        //   OLD: reads null → probes → probeLatch triggered → await returns true → FAIL (RED)
        //   NEW: reads Job → joins (blocked on close inside body) → probeLatch stays 1 → await timeout → false → PASS (GREEN)
        assertFalse(
            "Probe must NOT run before close completes",
            probeLatch.await(2, TimeUnit.SECONDS),
        )

        // Release close blocker
        closeBlocker.countDown()
        assertTrue(
            "Probe must run after teardown completes",
            probeLatch.await(5, TimeUnit.SECONDS),
        )

        scope.cancel()
    }

    // ── Result-aware barrier (rev-gpt R2): exception tests ────────────────

    /**
     * **Test A**: [TokenStreamCoordinator.close] throws → lifecycle teardown
     * STILL runs AND health probe does NOT fire.
     *
     * Close throws → caught inside the async body (allOk=false, lifecycle
     * continues). The deferred completes with [Result.failure]. coldStart
     * awaits it → sees failure → clears field → logs diagnostic → does NOT
     * probe. Lifecycle teardown invoked confirms close did NOT skip it.
     *
     * Uses deterministic latches — no Thread.sleep.
     */
    @Test
    fun `tokenStream close throws but lifecycle teardown still runs and cold probe skipped`() {
        val lifecycleCoordinator = mockk<StreamingLifecycleCoordinator>()
        val tokenStream = mockk<TokenStreamCoordinator>()
        val closeEntered = CountDownLatch(1)
        val lifecycleEntered = CountDownLatch(1)
        val probeLatch = CountDownLatch(1)
        every { tokenStream.close(any()) } answers {
            closeEntered.countDown()
            throw IOException("close boom")
        }
        coEvery { lifecycleCoordinator.teardownNoSourceAndAwait() } coAnswers {
            lifecycleEntered.countDown()
            Unit
        }
        coEvery { repository.checkHealth() } coAnswers {
            probeLatch.countDown()
            Result.success(HealthResponse(healthy = false, version = "1.0"))
        }

        val scope = CoroutineScope(Dispatchers.Default)
        slices.mutateChat { it.copy(currentSessionId = "test-sid") }
        val cc = ConnectionCoordinator(
            scope = scope,
            slices = slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effects,
            serverCompatProfile = ServerCompatProfile(),
            clock = { now },
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            streamingServiceLauncher = launcher,
            streamingLifecycleCoordinator = lifecycleCoordinator,
            tokenStreamCoordinator = tokenStream,
        )

        // Teardown: close throws → caught → lifecycle still runs
        scope.launch { cc.cancelSseForReconfigure() }
        assertTrue("close entered", closeEntered.await(5, TimeUnit.SECONDS))
        assertTrue(
            "lifecycle teardown MUST run even when close throws",
            lifecycleEntered.await(5, TimeUnit.SECONDS),
        )

        // coldStart → teardown deferred completed with failure → no probe
        scope.launch { cc.coldStartReconnect() }
        assertFalse(
            "health probe MUST NOT fire after failed close",
            probeLatch.await(2, TimeUnit.SECONDS),
        )

        scope.cancel()
    }

    /**
     * **Test B**: [StreamingLifecycleCoordinator.teardownNoSourceAndAwait]
     * throws → health probe does NOT fire.
     *
     * Teardown throws → caught inside the async body → deferred completes
     * with [Result.failure]. coldStart awaits it → sees failure → clears
     * field → does NOT probe.
     */
    @Test
    fun `teardownNoSourceAndAwait throws and cold probe skipped`() {
        val lifecycleCoordinator = mockk<StreamingLifecycleCoordinator>()
        val teardownEntered = CountDownLatch(1)
        val probeLatch = CountDownLatch(1)
        coEvery { lifecycleCoordinator.teardownNoSourceAndAwait() } coAnswers {
            teardownEntered.countDown()
            throw IOException("teardown boom")
        }
        coEvery { repository.checkHealth() } coAnswers {
            probeLatch.countDown()
            Result.success(HealthResponse(healthy = false, version = "1.0"))
        }

        val scope = CoroutineScope(Dispatchers.Default)
        slices.mutateChat { it.copy(currentSessionId = "test-sid") }
        val cc = ConnectionCoordinator(
            scope = scope,
            slices = slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effects,
            serverCompatProfile = ServerCompatProfile(),
            clock = { now },
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            streamingServiceLauncher = launcher,
            streamingLifecycleCoordinator = lifecycleCoordinator,
        )

        // Teardown fails
        scope.launch { cc.cancelSseForReconfigure() }
        assertTrue(
            "teardown entered",
            teardownEntered.await(5, TimeUnit.SECONDS),
        )

        // coldStart → sees Result.failure → no probe
        scope.launch { cc.coldStartReconnect() }
        assertFalse(
            "health probe MUST NOT fire after teardown throws",
            probeLatch.await(2, TimeUnit.SECONDS),
        )

        scope.cancel()
    }

    /**
     * **Test C (recovery)**: after a failed teardown, a subsequent successful
     * full teardown (concurrent cancelSseForReconfigure chains a new deferred)
     * recovers the barrier — coldStartReconnect then sees success and probes.
     */
    @Test
    fun `successful reconfigure after failed teardown recovers and allows cold probe`() {
        val lifecycleCoordinator = mockk<StreamingLifecycleCoordinator>()
        val tokenStream = mockk<TokenStreamCoordinator>()
        val closeCallCount = AtomicInteger(0)
        val teardownEntered1 = CountDownLatch(1)
        val probeLatch = CountDownLatch(1)

        // First close throws; subsequent succeed
        every { tokenStream.close(any()) } answers {
            if (closeCallCount.incrementAndGet() == 1) {
                throw IOException("first close fails")
            }
        }
        // First teardown enters (will succeed but close already failed)
        coEvery { lifecycleCoordinator.teardownNoSourceAndAwait() } coAnswers {
            teardownEntered1.countDown()
            Unit
        }
        coEvery { repository.checkHealth() } coAnswers {
            probeLatch.countDown()
            Result.success(HealthResponse(healthy = false, version = "1.0"))
        }

        val scope = CoroutineScope(Dispatchers.Default)
        slices.mutateChat { it.copy(currentSessionId = "test-sid") }
        val cc = ConnectionCoordinator(
            scope = scope,
            slices = slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effects,
            serverCompatProfile = ServerCompatProfile(),
            clock = { now },
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            streamingServiceLauncher = launcher,
            streamingLifecycleCoordinator = lifecycleCoordinator,
            tokenStreamCoordinator = tokenStream,
        )

        // Step 1: Teardown #1 fails (close throws)
        scope.launch { cc.cancelSseForReconfigure() }
        assertTrue("first teardown entered", teardownEntered1.await(5, TimeUnit.SECONDS))

        // Step 2: coldStart #1 → teardown #1 failed → no probe
        scope.launch { cc.coldStartReconnect() }
        assertFalse(
            "no probe after failed teardown",
            probeLatch.await(2, TimeUnit.SECONDS),
        )

        // Step 3: Teardown #2 succeeds (close no longer throws, chain on #1)
        val teardown2Done = CountDownLatch(1)
        scope.launch {
            cc.cancelSseForReconfigure()
            teardown2Done.countDown()
        }
        assertTrue("second teardown registered", teardown2Done.await(5, TimeUnit.SECONDS))

        // Step 4: coldStart #2 → teardown #2 succeeded → probe
        scope.launch { cc.coldStartReconnect() }
        assertTrue(
            "probe must run after successful recovery teardown",
            probeLatch.await(5, TimeUnit.SECONDS),
        )

        assertEquals(2, closeCallCount.get())
        scope.cancel()
    }

    // ── Item ①: Atomic handoff (probe inside lock, real thread competition) ──

    /**
     * RED-GREEN: coldStartReconnect's atomic handoff holds [reconfigureLock]
     * across the final pending==null check AND the probe invocation. A
     * concurrent [cancelSseForReconfigure] from a REAL second thread is
     * blocked by the held lock and cannot register until the probe completes.
     *
     * The [onHandoffProbeAboutToRun] seam blocks inside [reconfigureLock]
     * (via latches), creating an observable window where:
     *
     *   1. The cancel thread is released (cancelProceed) and signals
     *      [cancelAboutToBlock] just before entering [synchronized].
     *   2. The lock is still held by the seam — the cancel thread is BLOCKED.
     *   3. The main test thread verifies [cancelCompleted] is NOT counted
     *      down (cancel is blocked).
     *   4. The test releases the seam (continueSeam), probe runs inside lock.
     *   5. After probe returns → lock released → cancel thread acquires
     *      lock → registers → completes.
     *
     * This is qualitatively different from the old [onBeforeHandoffRecheck]
     * callback which called [cancelSseForReconfigure] in the SAME thread
     * via reentrant synchronized — that only proved reentrant lock
     * semantics, not real thread competition.
     */
    @Test
    fun `atomic handoff blocks concurrent cancel during probe via real thread`() {
        val lifecycleCoordinator = mockk<StreamingLifecycleCoordinator>()
        val probeLatch = CountDownLatch(1)
        val aboutToProbe = CountDownLatch(1)
        val cancelProceed = CountDownLatch(1)
        val cancelAboutToBlock = CountDownLatch(1)
        val continueSeam = CountDownLatch(1)
        val cancelCompleted = CountDownLatch(1)

        coEvery { lifecycleCoordinator.teardownNoSourceAndAwait() } returns Unit
        coEvery { repository.checkHealth() } coAnswers {
            probeLatch.countDown()
            Result.success(HealthResponse(healthy = false, version = "1.0"))
        }

        val scope = CoroutineScope(Dispatchers.Default)
        val cc = ConnectionCoordinator(
            scope = scope,
            slices = slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effects,
            serverCompatProfile = ServerCompatProfile(),
            clock = { now },
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            streamingServiceLauncher = launcher,
            streamingLifecycleCoordinator = lifecycleCoordinator,
        )

        // Seam: fires inside reconfigureLock, blocks to create observable window.
        cc.onHandoffProbeAboutToRun = {
            cancelProceed.countDown()               // (1) release cancel thread
            cancelAboutToBlock.await()               // (2) wait for cancel to signal attempt
            aboutToProbe.countDown()                 // (3) tell test coldStart is at handoff
            continueSeam.await()                     // (4) wait for test to verify block
            // (5) seam returns → coldStartReconnect runs inside lock
        }

        // ── Real cancel thread ──
        val cancelThread = Thread {
            cancelProceed.await()                    // wait for seam signal
            cancelAboutToBlock.countDown()           // signal: about to try lock
            cc.cancelSseForReconfigure()             // BLOCKED on reconfigureLock
            cancelCompleted.countDown()
        }
        cancelThread.start()

        // ── Launch coldStartReconnect (no initial teardown) ──
        scope.launch { cc.coldStartReconnect() }

        // Wait for aboutToProbe: coldStart is inside reconfigureLock,
        // holding it via the seam's latches.
        assertTrue(
            "coldStart must reach handoff probe point",
            aboutToProbe.await(10, TimeUnit.SECONDS),
        )

        // KEY ASSERTION: cancel thread has NOT completed its cancel call.
        // It is blocked on synchronized(reconfigureLock) — the lock is held
        // by the seam (which is awaiting continueSeam).
        assertFalse(
            "cancelSseForReconfigure must be blocked during handoff " +
                "(lock held by seam inside reconfigureLock)",
            cancelCompleted.await(500, TimeUnit.MILLISECONDS),
        )

        // Release the seam → coldStartReconnect runs inside the lock.
        // Cancel thread remains blocked until probe returns and lock exits.
        continueSeam.countDown()

        // Verify probe ran
        assertTrue(
            "probe must run after handoff",
            probeLatch.await(5, TimeUnit.SECONDS),
        )

        // Verify cancel completed after probe (lock released)
        assertTrue(
            "cancelSseForReconfigure must complete after probe releases the lock",
            cancelCompleted.await(10, TimeUnit.SECONDS),
        )
        assertFalse("cancel thread joined", cancelThread.isAlive)

        scope.cancel()
    }

    /**
     * D-fixup-r4 (Item ① close): the clear→probe window test.
     *
     * After [coldStartReconnect] releases [reconfigureLock] (probe coroutine
     * enqueued via scope.launch but not yet running on Dispatchers.Default), a
     * teardown registered in that window MUST be joined by the probe before it
     * does any real work (checkHealth). This is the vacuous-edge gap the old
     * [onHandoffProbeAboutToRun]-only test (which fires inside the lock BEFORE
     * launch) could not capture.
     *
     * Determinism: the probe coroutine fires [onProbeCoroutineStarted] at its
     * FIRST instruction (after launch, before the recheck). The test parks the
     * probe there, registers a teardown via a REAL cancelSseForReconfigure
     * call (real thread, real lock), then releases the probe. The probe's
     * recheck MUST find the teardown and join it; checkHealth MUST NOT be
     * reached until the teardown completes.
     *
     * Non-vacuous: without the recheck (the bug), the probe skips straight to
     * checkHealth while the teardown is still in flight — the first key
     * assertion (checkHealth NOT reached while teardown blocked) fails.
     */
    @Test
    fun `probe joins teardown registered in clear-to-probe window`() {
        val lifecycleCoordinator = mockk<StreamingLifecycleCoordinator>()
        val probeStarted = CountDownLatch(1)
        val teardownRegistered = CountDownLatch(1)
        val teardownStarted = CountDownLatch(1)
        val teardownRelease = CountDownLatch(1)
        val checkHealthReached = CountDownLatch(1)

        coEvery { lifecycleCoordinator.teardownNoSourceAndAwait() } coAnswers {
            teardownStarted.countDown()
            teardownRelease.await()
            Unit
        }
        coEvery { repository.checkHealth() } coAnswers {
            checkHealthReached.countDown()
            Result.success(HealthResponse(healthy = false, version = "1.0"))
        }

        val scope = CoroutineScope(Dispatchers.Default)
        val cc = ConnectionCoordinator(
            scope = scope,
            slices = slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effects,
            serverCompatProfile = ServerCompatProfile(),
            clock = { now },
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            streamingServiceLauncher = launcher,
            streamingLifecycleCoordinator = lifecycleCoordinator,
        )

        // Park the probe coroutine at its FIRST instruction. coldStart has
        // already released reconfigureLock by the time this fires — this is
        // the clear→probe window.
        cc.onProbeCoroutineStarted = {
            probeStarted.countDown()
            teardownRegistered.await()
        }

        // coldStart: no initial teardown → straight to handoff → launch probe
        // → release lock → probe coroutine fires the seam → parks.
        scope.launch { cc.coldStartReconnect() }

        assertTrue(
            "probe coroutine must enter its seam (lock already released)",
            probeStarted.await(10, TimeUnit.SECONDS),
        )

        // ── THE CLEAR→PROBE WINDOW ──
        // Register a teardown via a REAL cancelSseForReconfigure. This call
        // acquires reconfigureLock (free now), registers the pending
        // teardown, and starts the lazy async. It arrived AFTER the lock was
        // released and while the probe coroutine is parked — exactly the race
        // the old test could not capture.
        cc.cancelSseForReconfigure()
        assertTrue("teardown body must start", teardownStarted.await(10, TimeUnit.SECONDS))

        // Release the probe seam → recheck runs → finds the pending teardown.
        teardownRegistered.countDown()

        // KEY ASSERTION 1 (non-vacuous): the teardown is still in flight
        // (blocked on teardownRelease). The probe MUST have joined it, so
        // checkHealth MUST NOT be reached yet. Without the recheck fix the
        // probe would skip straight to checkHealth and this assertion fails.
        assertFalse(
            "checkHealth must NOT be reached while teardown is still in flight " +
                "(probe must join the teardown first)",
            checkHealthReached.await(500, TimeUnit.MILLISECONDS),
        )

        // Release the teardown → the probe's join completes → probe proceeds.
        teardownRelease.countDown()

        // KEY ASSERTION 2: checkHealth IS reached now — the probe proceeded
        // only AFTER the teardown completed. Proves teardown-before-checkHealth
        // ordering, i.e. the clear→probe window is closed.
        assertTrue(
            "probe must reach checkHealth after the teardown completes",
            checkHealthReached.await(10, TimeUnit.SECONDS),
        )

        scope.cancel()
    }

    /**
     * D-fixup-r4 (Item ① close): clear→probe window on the PRODUCTION engine
     * path. Production always wires [connectionBootstrapEngine]
     * (ControllerModule), so coldStartReconnect → testConnection takes the
     * [testConnectionWithEngine] branch (a SEPARATE scope.launch from the
     * legacy path). This test proves the recheck closes the window on the
     * engine path too: engine.bootstrap() is NOT reached while a teardown
     * registered in the clear→probe window is still in flight.
     *
     * Non-vacuous: without the engine-path recheck, engine.bootstrap() is
     * reached immediately while the teardown blocks — the first key assertion
     * fails. (rev-gpt round-3 found the legacy-only recheck insufficient.)
     */
    @Test
    fun `probe joins teardown registered in clear-to-probe window - engine path`() {
        val lifecycleCoordinator = mockk<StreamingLifecycleCoordinator>()
        val engine = mockk<cn.vectory.ocdroid.service.streaming.ConnectionBootstrapEngine>()
        val probeStarted = CountDownLatch(1)
        val teardownRegistered = CountDownLatch(1)
        val teardownStarted = CountDownLatch(1)
        val teardownRelease = CountDownLatch(1)
        val bootstrapReached = CountDownLatch(1)

        coEvery { lifecycleCoordinator.teardownNoSourceAndAwait() } coAnswers {
            teardownStarted.countDown()
            teardownRelease.await()
            Unit
        }
        coEvery { engine.bootstrap() } coAnswers {
            bootstrapReached.countDown()
            cn.vectory.ocdroid.service.streaming.ConnectionBootstrapOutcome.Failed(
                IOException("test"),
            )
        }

        val scope = CoroutineScope(Dispatchers.Default)
        val cc = ConnectionCoordinator(
            scope = scope,
            slices = slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effects,
            serverCompatProfile = ServerCompatProfile(),
            clock = { now },
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            streamingServiceLauncher = launcher,
            streamingLifecycleCoordinator = lifecycleCoordinator,
            connectionBootstrapEngine = engine,
        )

        // Same seam as the legacy test — fires at the engine coroutine's FIRST
        // instruction, AFTER coldStart released reconfigureLock.
        cc.onProbeCoroutineStarted = {
            probeStarted.countDown()
            teardownRegistered.await()
        }

        // coldStart → testConnection(force=true) → engine non-null →
        // testConnectionWithEngine → scope.launch → seam → park.
        scope.launch { cc.coldStartReconnect() }

        assertTrue(
            "probe coroutine must enter its seam (engine path, lock already released)",
            probeStarted.await(10, TimeUnit.SECONDS),
        )

        // ── THE CLEAR→PROBE WINDOW (engine path) ──
        cc.cancelSseForReconfigure()
        assertTrue("teardown body must start", teardownStarted.await(10, TimeUnit.SECONDS))

        // Release the probe seam → recheck runs → finds pending teardown.
        teardownRegistered.countDown()

        // KEY ASSERTION 1 (non-vacuous): teardown in flight → probe joined it →
        // engine.bootstrap() NOT reached yet. Without the engine-path recheck
        // the probe skips straight to bootstrap and this assertion fails.
        assertFalse(
            "engine.bootstrap() must NOT be reached while teardown is still in flight " +
                "(probe must join the teardown first)",
            bootstrapReached.await(500, TimeUnit.MILLISECONDS),
        )

        // Release the teardown → probe's join completes → bootstrap reached.
        teardownRelease.countDown()

        // KEY ASSERTION 2: bootstrap IS reached after teardown completes.
        assertTrue(
            "probe must reach engine.bootstrap() after the teardown completes",
            bootstrapReached.await(10, TimeUnit.SECONDS),
        )

        scope.cancel()
    }

    /**
     * Complementary test: a teardown registered BEFORE coldStartReconnect
     * acquires the handoff lock is caught by the re-check and awaited before
     * the probe (the pre-existing "teardown before handoff" path).
     *
     * pendingReconfigureTeardown is already non-null when coldStart enters
     * the handoff synchronized block, so it loops back to the outer while
     * and joins instead of probing.
     */
    @Test
    fun `atomic handoff loops back when teardown registered before lock`() {
        val lifecycleCoordinator = mockk<StreamingLifecycleCoordinator>()
        val probeBarrier = CompletableDeferred<Unit>()
        val teardownBarrier = CompletableDeferred<Unit>()
        val teardownEntered = CountDownLatch(1)

        coEvery { lifecycleCoordinator.teardownNoSourceAndAwait() } coAnswers {
            teardownEntered.countDown()
            teardownBarrier.await()
            Unit
        }
        coEvery { repository.checkHealth() } coAnswers {
            probeBarrier.await()
            Result.success(HealthResponse(healthy = false, version = "1.0"))
        }

        val scope = CoroutineScope(Dispatchers.Default)
        val cc = ConnectionCoordinator(
            scope = scope,
            slices = slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effects,
            serverCompatProfile = ServerCompatProfile(),
            clock = { now },
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            streamingServiceLauncher = launcher,
            streamingLifecycleCoordinator = lifecycleCoordinator,
        )

        // Step 1: Register teardown before coldStart
        scope.launch { cc.cancelSseForReconfigure() }
        assertTrue("teardown started", teardownEntered.await(5, TimeUnit.SECONDS))

        // Step 2: Launch coldStart — enters inner loop, finds d != null,
        // joins on d.await() (blocked on teardownBarrier).
        scope.launch { cc.coldStartReconnect() }
        Thread.sleep(300)

        // No probe while teardown is blocked
        coVerify(exactly = 0) { repository.checkHealth() }

        // Step 3: Release teardown → coldStart identity check → clear →
        // enters handoff synchronized → re-check → null → probes
        teardownBarrier.complete(Unit)
        Thread.sleep(500)

        coVerify(atLeast = 1) { repository.checkHealth() }

        probeBarrier.complete(Unit)
        scope.cancel()
    }
}
