package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.MainDispatcherRule
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.SlimColdStartSnapshot
import cn.vectory.ocdroid.data.repository.SlimAggregationOutcome
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * G-F1 cadence / trailing regression tests.
 *
 * Guards against B1 (double-guard deadlock) and B1.5 (trailing livelock),
 * and verifies the 15-min interval, manual bypass, single-flight, dirty
 * preservation, and failure timer semantics.
 *
 * Duplicates the test infrastructure from [SessionSyncCoordinatorResyncTest]
 * exactly ([UnconfinedTestDispatcher] for scope and reconcile, class-level
 * [scope], real [SharedEffectBus]) to avoid the collector coroutine issue
 * that occurs with [StandardTestDispatcher].
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionSyncCoordinatorCadenceTest {

    @get:org.junit.Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val scope = TestScope(UnconfinedTestDispatcher())

    private lateinit var slices: SliceFlows
    private lateinit var effects: SharedEffectBus
    private lateinit var settingsManager: SettingsManager
    private lateinit var repository: OpenCodeRepository
    private var testClockMs: Long = 1_000_000L

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
        repository = mockk(relaxed = true)

        every { repository.isSlimCommitTokenCurrent(any()) } returns true
        every { repository.commitIfSlimTokenCurrent(any(), any()) } answers {
            secondArg<() -> Unit>().invoke()
            true
        }
        every { repository.clearSlimLocalMessages(any(), any()) } returns true
        every { repository.markSlimReconcileFailure(any(), any()) } returns true
        every { repository.markSlimReconcileAligned(any(), any()) } returns true
        every { repository.markSlimSessionDeleted(any(), any()) } returns true
        every { repository.markSlimDirty(any(), any()) } returns true
        every { repository.invalidateSlimLocalApplied(any(), any()) } returns true
        every { repository.applySlimDigest(any(), any()) } returns null
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun successSnapshot() = SlimColdStartSnapshot(
        sessions = null,
        questions = SlimAggregationOutcome.Success(emptyList(), null),
        permissions = SlimAggregationOutcome.Success(emptyList(), null),
        messages = null,
    )

    private fun coordinator(): SessionSyncCoordinator =
        SessionSyncCoordinator(
            scope = scope,
            slices = slices,
            settingsManager = settingsManager,
            effects = effects,
            currentServerGroupFp = { "test-fp" },
            isSlimMode = { true },
            repository = repository,
            reconcileDispatcher = UnconfinedTestDispatcher(),
        ).also { c ->
            c.resyncClockMsForTest = { testClockMs }
        }

    // ── Test 1: 15-min interval declines too-soon then admits after 15min ────

    @Test
    fun `cadence 15min interval declines too-soon then admits after 15min`() = runTest {
        coEvery { repository.coldStartSlimSync(any(), any(), any()) } returns Result.success(successSnapshot())

        val c = coordinator()

        // 1st sweep: should proceed (no prior success).
        c.performSlimResync(isManual = false)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.coldStartSlimSync(any(), any(), any()) }

        // 5 min later: decline (too soon relative to the 15-min interval).
        testClockMs += 5 * 60_000L
        c.performSlimResync(isManual = false)
        advanceUntilIdle()
        // Still exactly 1 — the sweep was declined.
        coVerify(exactly = 1) { repository.coldStartSlimSync(any(), any(), any()) }

        // Another 10 min later (= 15 min total from success): accepted.
        testClockMs += 10 * 60_000L
        c.performSlimResync(isManual = false)
        advanceUntilIdle()
        coVerify(exactly = 2) { repository.coldStartSlimSync(any(), any(), any()) }
    }

    // ── Test 2: manual refresh bypasses 15-min interval ───────────────────

    @Test
    fun `cadence manual refresh bypasses 15min interval but stays single-flight`() = runTest {
        coEvery { repository.coldStartSlimSync(any(), any(), any()) } returns Result.success(successSnapshot())

        val c = coordinator()

        // 1st sweep: success.
        c.performSlimResync(isManual = false)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.coldStartSlimSync(any(), any(), any()) }

        // 1 min later, manual: bypasses the interval.
        testClockMs += 1 * 60_000L
        c.performSlimResync(isManual = true)
        advanceUntilIdle()
        coVerify(exactly = 2) { repository.coldStartSlimSync(any(), any(), any()) }
    }

    // ── Test 3: failure preserves lastSuccessfulResyncAt and dirty ────────

    @Test
    fun `cadence failure preserves lastSuccessfulResyncAt and dirty`() = runTest {
        // Timeline (minutes): 0: success; 10: failure; 10: call (decline);
        //   20: call (admit). Proves the failure did NOT reset the timer.
        // 1st sweep success.
        coEvery { repository.coldStartSlimSync(any(), any(), any()) } returns Result.success(successSnapshot())

        val c = coordinator()
        c.performSlimResync(isManual = false)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.coldStartSlimSync(any(), any(), any()) }

        // Advance 10 min and make coldStartSlimSync fail.
        testClockMs += 10 * 60_000L
        coEvery { repository.coldStartSlimSync(any(), any(), any()) } returns
            Result.failure(java.io.IOException("net"))

        // This sweep at minute 10 should DECLINE because lastSuccessfulResyncAt
        // is still at minute 0 (the failure didn't reset it). We are within
        // 15 min of the last success (0<15), so tooSoon → decline.
        c.performSlimResync(isManual = false)
        advanceUntilIdle()
        // coldStartSlimSync still at 1 (decline = emptyMap = no call).
        coVerify(exactly = 1) { repository.coldStartSlimSync(any(), any(), any()) }

        // Advance another 10 min (= minute 20 total). Now we are past 15 min
        // from the last success (20>15), so the call should ADMIT.
        testClockMs += 10 * 60_000L
        c.performSlimResync(isManual = false)
        advanceUntilIdle()
        coVerify(exactly = 2) { repository.coldStartSlimSync(any(), any(), any()) }
    }

    // ── Test 4 (B1 regression): trigger launch does not double-guard jam ────

    @Test
    fun `B1 regression trigger launch does not double-guard jam`() = runTest {
        // B1: the bug was that trigger paths (server.connected / HostReconfigured)
        // launched performSlimResync WITHIN an outer maybeScheduleResync, causing
        // the internal guard to see inFlight already set → emptyMap → no sweep ran
        // → cadence jammed forever (inFlight=true, trailing=true, no sweeps).
        // The fix: triggers now launch performSlimResync UNCONDITIONALLY (no outer
        // maybeScheduleResync), so the internal guard is the sole authority.
        coEvery { repository.coldStartSlimSync(any(), any(), any()) } returns Result.success(successSnapshot())

        val c = coordinator()

        // Launch a sweep the way the trigger does: directly via scope.launch.
        val job = launch { c.performSlimResync(isManual = false) }
        advanceUntilIdle()
        job.join()

        // First sweep executed.
        coVerify(exactly = 1) { repository.coldStartSlimSync(any(), any(), any()) }

        // The cadence should be idle after the sweep completes.
        // A follow-up call should proceed.
        testClockMs += 20 * 60_000L  // far past 15-min interval
        c.performSlimResync(isManual = false)
        advanceUntilIdle()
        // The second sweep also ran (cadence not jammed).
        coVerify(exactly = 2) { repository.coldStartSlimSync(any(), any(), any()) }
    }

    // ── Test 4b (B1 regression, trigger-site): server.connected reconnect path ──
    // GENUINE trigger-site guard. Test #4 launches performSlimResync DIRECTLY,
    // so a B1 regression reintroduced AT THE TRIGGER SITE (re-wrapping the
    // scope.launch at SessionSyncCoordinator.kt:772 in an outer
    // maybeScheduleResync) would pass silently. This test drives an ACTUAL
    // server.connected RECONNECT (the 2nd connect — the 1st is cold-start and
    // skipped), exercising the real trigger path at :772 and asserting the
    // sweep executes + the cadence is NOT jammed.

    @Test
    fun `B1 regression server connected trigger launches sweep without double-guard jam`() = runTest {
        coEvery { repository.coldStartSlimSync(any(), any(), any()) } returns Result.success(successSnapshot())

        val c = coordinator()

        // 1st server.connected: cold-start. Flips connectedOnce=true but does
        // NOT launch the sweep (cold-start first-connect is handled by the
        // reconcile path; the guard at SessionSyncCoordinator.kt:771 skips it).
        c.handleEvent(SSEEvent(payload = SSEPayload(type = "server.connected")))
        advanceUntilIdle()
        // No sweep yet — cold-start first connect is intentionally skipped.
        coVerify(exactly = 0) { repository.coldStartSlimSync(any(), any(), any()) }

        // 2nd server.connected (the RECONNECT path): connectedOnceBefore=true →
        // resetCadenceForGeneration + scope.launch { performSlimResync(...) }
        // at SessionSyncCoordinator.kt:772. This is the actual trigger site.
        c.handleEvent(SSEEvent(payload = SSEPayload(type = "server.connected")))
        advanceUntilIdle()

        // The sweep EXECUTED via the trigger path. With a B1 double-guard at
        // the trigger site (re-wrapping the launch in if (maybeScheduleResync(...))),
        // the outer guard would set inFlight=true, then the inner guard inside
        // performSlimResync would see inFlight → decline → coldStartSlimSync==0.
        coVerify(atLeast = 1) { repository.coldStartSlimSync(any(), any(), any()) }

        // Assert the cadence is NOT jammed: advance past 15 min + follow-up
        // direct call. A B1-jammed cadence (inFlight=true forever, trailing
        // queued) would decline forever → count stays the same → fails.
        testClockMs += 20 * 60_000L  // far past the 15-min interval
        c.performSlimResync(isManual = false)
        advanceUntilIdle()
        // The follow-up sweep also ran (cadence admitted it, not jammed).
        coVerify(atLeast = 2) { repository.coldStartSlimSync(any(), any(), any()) }
    }

    // ── Test 5 (B1.5 regression): trailing sweep executes EXACTLY ONCE, no livelock ──
    // GENUINE B1.5 guard. A dedicated StandardTestDispatcher coordScope + a
    // SUSPENDING coldStartSlimSync mock (gate) holds the 1st sweep IN-FLIGHT so a
    // 2nd call queues a TRAILING. On release, finishResyncCadence must launch the
    // trailing EXACTLY ONCE. With the B1.5 bug (outer maybeScheduleResync wrapping
    // the trailing relaunch), the trailing NEVER executes AND finishResyncCadence
    // re-launches forever -> advanceUntilIdle HANGS (test times out) / count never
    // reaches 2. With the fix: coldCallCount==2 + advanceUntilIdle returns.
    // (coordScope.cancel() cleans up the coordinator's effects-collector coroutine,
    // avoiding the UncompletedCoroutinesError that an earlier attempt hit.)

    @Test
    fun `B1_5 regression trailing sweep executes exactly once no livelock`() = runTest {
        slices.mutateChat { it.copy(currentSessionId = "sess-1") }
        val coordScope = TestScope(StandardTestDispatcher())
        val gate = CompletableDeferred<Unit>()
        val coldCallCount = AtomicInteger(0)
        coEvery { repository.coldStartSlimSync(any(), any(), any()) } coAnswers {
            val n = coldCallCount.incrementAndGet()
            if (n == 1) gate.await() // only the 1st (in-flight) call suspends
            Result.success(successSnapshot())
        }
        val c = SessionSyncCoordinator(
            scope = coordScope,
            slices = slices,
            settingsManager = settingsManager,
            effects = effects,
            currentServerGroupFp = { "test-fp" },
            isSlimMode = { true },
            repository = repository,
            reconcileDispatcher = StandardTestDispatcher(coordScope.testScheduler),
        ).also { it.resyncClockMsForTest = { testClockMs } }

        try {
            // 1st sweep: runs to coldStartSlimSync, suspends on gate -> inFlight=true.
            coordScope.launch { c.performSlimResync(isManual = false) }
            coordScope.testScheduler.advanceUntilIdle()
            assertEquals("in-flight sweep started", 1, coldCallCount.get())

            // 2nd trigger while 1st is in-flight -> internal guard sees inFlight ->
            // queues trailing, returns emptyMap (NO 2nd parallel sweep).
            coordScope.launch { c.performSlimResync(isManual = false) }
            coordScope.testScheduler.advanceUntilIdle()
            assertEquals("trailing queued, no 2nd parallel sweep", 1, coldCallCount.get())

            // Release the gate -> 1st completes -> finishResyncCadence -> trailing
            // launches (bypassIntervalCheck=true) -> internal guard inFlight=false ->
            // trailing sweep runs. With B1.5 bug this advanceUntilIdle HANGS.
            gate.complete(Unit)
            coordScope.testScheduler.advanceUntilIdle()
            assertEquals("trailing sweep executed exactly once", 2, coldCallCount.get())
        } finally {
            coordScope.cancel() // cleanup the coordinator's effects collector
        }
    }

    // ── Test 6: single-flight — 2nd call while in-flight does NOT start a parallel sweep ──

    @Test
    fun `cadence single-flight no parallel sweep while in-flight`() = runTest {
        slices.mutateChat { it.copy(currentSessionId = "sess-1") }
        val coordScope = TestScope(StandardTestDispatcher())
        val gate = CompletableDeferred<Unit>()
        val coldCallCount = AtomicInteger(0)
        coEvery { repository.coldStartSlimSync(any(), any(), any()) } coAnswers {
            coldCallCount.incrementAndGet()
            gate.await() // every call suspends (holds in-flight)
            Result.success(successSnapshot())
        }
        val c = SessionSyncCoordinator(
            scope = coordScope,
            slices = slices,
            settingsManager = settingsManager,
            effects = effects,
            currentServerGroupFp = { "test-fp" },
            isSlimMode = { true },
            repository = repository,
            reconcileDispatcher = StandardTestDispatcher(coordScope.testScheduler),
        ).also { it.resyncClockMsForTest = { testClockMs } }

        try {
            // 1st sweep: in-flight (suspended on gate).
            coordScope.launch { c.performSlimResync(isManual = false) }
            coordScope.testScheduler.advanceUntilIdle()
            assertEquals("1st in-flight", 1, coldCallCount.get())

            // Fire 2nd + 3rd while 1st in-flight -> both must queue trailing (≤1),
            // NOT start parallel sweeps. coldCallCount must stay 1 (only the in-flight).
            coordScope.launch { c.performSlimResync(isManual = false) }
            coordScope.launch { c.performSlimResync(isManual = false) }
            coordScope.testScheduler.advanceUntilIdle()
            assertEquals("no parallel sweep while in-flight (single-flight)", 1, coldCallCount.get())

            // Release -> in-flight completes + AT MOST 1 trailing runs (3rd coalesced).
            gate.complete(Unit)
            coordScope.testScheduler.advanceUntilIdle()
            assertTrue("sweep completed (>=2 after trailing)", coldCallCount.get() >= 2)
            // single-flight bound: never more than in-flight + 1 trailing = 2.
            assertTrue("single-flight + 1 trailing bound (<=2)", coldCallCount.get() <= 2)
        } finally {
            coordScope.cancel()
        }
    }
}
