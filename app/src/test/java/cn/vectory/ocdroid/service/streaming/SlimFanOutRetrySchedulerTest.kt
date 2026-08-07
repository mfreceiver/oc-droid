package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.status.SlimFanOutBackoffPolicy
import cn.vectory.ocdroid.service.status.StatusFanOutSummary
import cn.vectory.ocdroid.service.status.StatusSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SlimFanOutRetryScheduler].
 *
 * Migrated from the former [ProcessStatusPollerTest] (architecture-debt
 * Batch 1 item 17): backoff tests (growth/cap/reset/jitter-clamp/
 * default-sampler) plus new tests for the retry-scheduler-specific contract
 * (identity null no-op, host-switch drops, single-flight, resetBackoff
 * cancels pending).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SlimFanOutRetrySchedulerTest {

    private val identity = ConnectionIdentity(
        epoch = 1L,
        profileId = "group-fp",
        normalizedWorkdir = "/work/dir",
        endpointFp = "endpoint-fp",
    )

    // ── Backoff tests (migrated from ProcessStatusPollerTest) ──────────────

    private fun newScheduler(
        appScope: TestScope,
        identityStore: ConnectionIdentityStore = ConnectionIdentityStore().also { bindIdentity(it) },
    ): SlimFanOutRetryScheduler {
        return SlimFanOutRetryScheduler(
            scope = appScope,
            snapshotProvider = SessionSnapshotProvider { StatusSnapshot.Empty },
            identityStore = identityStore,
        )
    }

    @Test
    fun `scheduleBackoff grows exponentially with consecutive retryable sweeps`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val scheduler = newScheduler(appScope)

        // Deterministic (jitter = 0) so the schedule is purely exponential.
        val first = scheduler.scheduleBackoff(jitter = 0.0f)
        val second = scheduler.scheduleBackoff(jitter = 0.0f)
        val third = scheduler.scheduleBackoff(jitter = 0.0f)

        assertEquals(
            "first delay is the base",
            SlimFanOutBackoffPolicy.BACKOFF_BASE_MS,
            first,
        )
        assertEquals(
            "second delay doubles the base",
            SlimFanOutBackoffPolicy.BACKOFF_BASE_MS * 2L,
            second,
        )
        assertEquals(
            "third delay quadruples the base",
            SlimFanOutBackoffPolicy.BACKOFF_BASE_MS * 4L,
            third,
        )
        assertTrue(
            "nextDelay > base on 503 (second > first)",
            second > first,
        )
    }

    @Test
    fun `scheduleBackoff is capped at BACKOFF_MAX_MS - 30s`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val scheduler = newScheduler(appScope)

        // Many consecutive failures — the delay must stay ≤ BACKOFF_MAX_MS.
        var last = 0L
        repeat(20) { last = scheduler.scheduleBackoff(jitter = 0.0f) }

        assertEquals(
            "delay caps at BACKOFF_MAX_MS (= 30s)",
            SlimFanOutBackoffPolicy.BACKOFF_MAX_MS,
            last,
        )
    }

    @Test
    fun `resetBackoff returns the state to base on success`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val scheduler = newScheduler(appScope)

        scheduler.scheduleBackoff(jitter = 0.0f)
        scheduler.scheduleBackoff(jitter = 0.0f)
        scheduler.scheduleBackoff(jitter = 0.0f)
        assertTrue(
            "backoff state has grown",
            scheduler.currentBackoffDelayMs() > SlimFanOutBackoffPolicy.BACKOFF_BASE_MS,
        )

        // Success path: reset to base.
        scheduler.resetBackoff()
        assertEquals(
            "reset clears pending backoff",
            0L,
            scheduler.currentBackoffDelayMs(),
        )

        // Next schedule starts fresh from the base.
        val afterReset = scheduler.scheduleBackoff(jitter = 0.0f)
        assertEquals(
            "after reset, the next schedule is the base again",
            SlimFanOutBackoffPolicy.BACKOFF_BASE_MS,
            afterReset,
        )
    }

    @Test
    fun `scheduleBackoff jitter is clamped to plus-minus 20 percent`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val scheduler = newScheduler(appScope)

        // Out-of-range jitter (e.g., 5.0 = 500%) is clamped to +20%.
        val clampedHigh = scheduler.scheduleBackoff(jitter = 5.0f)
        scheduler.resetBackoff()
        val clampedLow = scheduler.scheduleBackoff(jitter = -5.0f)
        scheduler.resetBackoff()
        val exact = scheduler.scheduleBackoff(jitter = 0.2f)
        scheduler.resetBackoff()
        val exactLow = scheduler.scheduleBackoff(jitter = -0.2f)

        // Clamped to ±20%: high == exact (+20%), low == exactLow (-20%).
        assertEquals(
            "out-of-range +jitter clamps to +20%",
            exact,
            clampedHigh,
        )
        assertEquals(
            "out-of-range -jitter clamps to -20%",
            exactLow,
            clampedLow,
        )
        assertEquals(
            "+20% jitter = base * 1.2",
            (SlimFanOutBackoffPolicy.BACKOFF_BASE_MS * 1.2f).toLong(),
            exact,
        )
    }

    @Test
    fun `currentBackoffDelayMs starts at 0 - no backoff pending`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val scheduler = newScheduler(appScope)

        assertEquals(
            "no pending backoff on a fresh scheduler",
            0L,
            scheduler.currentBackoffDelayMs(),
        )
    }

    @Test
    fun `default scheduleBackoff samples jitter (non-deterministic within plusminus 20 percent)`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val scheduler = newScheduler(appScope)

        val samples = mutableSetOf<Long>()
        val base = SlimFanOutBackoffPolicy.BACKOFF_BASE_MS
        val lower = (base * 0.8f).toLong()
        val upper = (base * 1.2f).toLong()
        repeat(50) {
            // resetBackoff so each sample is the FIRST attempt (base delay).
            scheduler.resetBackoff()
            // DEFAULT call (no jitter arg) → must sample internally.
            val v = scheduler.scheduleBackoff()
            samples.add(v)
            assertTrue(
                "sampled value $v must be within ±20% of base ($lower..$upper)",
                v in lower..upper,
            )
        }
        assertTrue(
            "default sampling produced >1 distinct value (jitter is non-deterministic): $samples",
            samples.size > 1,
        )
    }

    @Test
    fun `explicit jitter is respected (not sampled)`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val scheduler = newScheduler(appScope)

        val deterministic = scheduler.scheduleBackoff(jitter = 0.0f)
        assertEquals(
            "explicit jitter=0.0f produces deterministic base",
            SlimFanOutBackoffPolicy.BACKOFF_BASE_MS,
            deterministic,
        )
    }

    // ── Retry-scheduler-specific tests ─────────────────────────────────────

    @Test
    fun `requestSlimFanOutRetry no-ops when identityStore currentIdentity is null`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val store = ConnectionIdentityStore()
        // No identity bound.
        val invocations = mutableListOf<Unit>()
        val scheduler = SlimFanOutRetryScheduler(
            scope = appScope,
            snapshotProvider = SessionSnapshotProvider { StatusSnapshot.Empty },
            identityStore = store,
            slimFanOutRunner = { _, _ ->
                invocations.add(Unit)
                StatusFanOutSummary.Empty
            },
        )

        scheduler.requestSlimFanOutRetry(100L)
        runCurrent()

        assertEquals("no sweep when no identity is bound", 0, invocations.size)
    }

    @Test
    fun `pending retry is dropped after host switch`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val store = ConnectionIdentityStore()
        store.beginReconfigure()
        store.bind(identity.profileId, identity.normalizedWorkdir, identity.endpointFp)

        val invocations = mutableListOf<Unit>()
        val scheduler = SlimFanOutRetryScheduler(
            scope = appScope,
            snapshotProvider = SessionSnapshotProvider { StatusSnapshot.Empty },
            identityStore = store,
            slimFanOutRunner = { _, _ ->
                invocations.add(Unit)
                StatusFanOutSummary.Empty
            },
        )

        val delayMs = 500L
        scheduler.requestSlimFanOutRetry(delayMs)

        // Switch identity before the retry fires.
        store.beginReconfigure()
        store.bind("group-fp-b", identity.normalizedWorkdir, identity.endpointFp)

        // Advance past the delay.
        advanceTimeBy(appScope, delayMs * 2)
        runCurrent(appScope)

        assertEquals(
            "retry dropped after host switch (identity no longer current)",
            0,
            invocations.size,
        )
    }

    @Test
    fun `mid-sweep host switch drops the summary before sink`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val store = ConnectionIdentityStore()
        store.beginReconfigure()
        store.bind(identity.profileId, identity.normalizedWorkdir, identity.endpointFp)

        // Gate the runner so the sweep suspends mid-flight; switch host
        // BEFORE releasing. This pins runSlimFanOut's 3rd isCurrent check
        // (AFTER the network sweep returns, BEFORE sinking the summary) —
        // the subtlest point of the retained 3-point identity discipline,
        // and distinct from the delay-period switch above (which only
        // exercises the retry-path check before the sweep starts).
        val sweepGate = CompletableDeferred<Unit>()
        val summaries = mutableListOf<StatusFanOutSummary>()
        val scheduler = SlimFanOutRetryScheduler(
            scope = appScope,
            snapshotProvider = SessionSnapshotProvider { StatusSnapshot.Empty },
            identityStore = store,
            slimFanOutRunner = { _, _ ->
                sweepGate.await()
                StatusFanOutSummary.Empty
            },
            slimFanOutSummarySink = { summaries.add(it) },
        )

        // delay(0) + Unconfined: the retry runs eagerly until the runner
        // suspends at sweepGate.await() (mid-sweep).
        scheduler.requestSlimFanOutRetry(0L)
        runCurrent(appScope)

        // Switch host WHILE the sweep is suspended → isCurrent(identity) false.
        store.beginReconfigure()
        store.bind("group-fp-b", identity.normalizedWorkdir, identity.endpointFp)

        // Release the gate: the sweep completes and returns a summary, but
        // the 3rd isCurrent check (before sink) now rejects it.
        sweepGate.complete(Unit)
        runCurrent(appScope)

        assertEquals(
            "mid-sweep host switch dropped the summary before sink",
            0,
            summaries.size,
        )
    }

    @Test
    fun `single-flight - second request cancels the first`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val store = ConnectionIdentityStore()
        store.beginReconfigure()
        store.bind(identity.profileId, identity.normalizedWorkdir, identity.endpointFp)

        val invocations = mutableListOf<Unit>()
        val scheduler = SlimFanOutRetryScheduler(
            scope = appScope,
            snapshotProvider = SessionSnapshotProvider { StatusSnapshot.Empty },
            identityStore = store,
            slimFanOutRunner = { _, _ ->
                invocations.add(Unit)
                StatusFanOutSummary.Empty
            },
        )

        // Request a retry with a long delay.
        scheduler.requestSlimFanOutRetry(10_000L)

        // Request a second retry with a short delay — cancels the first.
        scheduler.requestSlimFanOutRetry(0L)
        runCurrent(appScope)

        // Only one sweep (from the second request), not two.
        assertEquals(
            "second retry cancels the first — only one sweep fires",
            1,
            invocations.size,
        )
    }

    @Test
    fun `resetBackoff cancels pending retry and re-bases the schedule`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val store = ConnectionIdentityStore()
        store.beginReconfigure()
        store.bind(identity.profileId, identity.normalizedWorkdir, identity.endpointFp)

        val invocations = mutableListOf<Unit>()
        val scheduler = SlimFanOutRetryScheduler(
            scope = appScope,
            snapshotProvider = SessionSnapshotProvider { StatusSnapshot.Empty },
            identityStore = store,
            slimFanOutRunner = { _, _ ->
                invocations.add(Unit)
                StatusFanOutSummary.Empty
            },
        )

        // Schedule a retry far in the future.
        val delayMs = 10_000L
        scheduler.requestSlimFanOutRetry(delayMs)

        // ResetBackoff cancels the pending retry and re-bases backoff.
        scheduler.resetBackoff()

        // Advance past original delay: retry should not fire.
        advanceTimeBy(appScope, delayMs * 2)
        runCurrent(appScope)

        assertEquals(
            "resetBackoff cancelled the pending retry — no sweep fired",
            0,
            invocations.size,
        )

        // Backoff is re-based.
        val afterReset = scheduler.scheduleBackoff(jitter = 0.0f)
        assertEquals(
            "after reset, backoff returns to base",
            SlimFanOutBackoffPolicy.BACKOFF_BASE_MS,
            afterReset,
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun advanceTimeBy(scope: TestScope, ms: Long) = scope.testScheduler.advanceTimeBy(ms)
    private fun runCurrent(scope: TestScope) = scope.testScheduler.runCurrent()

    private fun bindIdentity(store: ConnectionIdentityStore) {
        store.beginReconfigure()
        store.bind(identity.profileId, identity.normalizedWorkdir, identity.endpointFp)
    }
}
