package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.StatusOutcome
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.status.StatusAggregator
import cn.vectory.ocdroid.service.status.StatusFanOutSummary
import cn.vectory.ocdroid.service.status.StatusSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * §final-gate I-1 discriminator tests — verifies the slim status fan-out
 * wiring at the [SlimFanOutRetryScheduler] trigger layer.
 *
 * Architecture-debt Batch 1 item 17 reduced the class from a full loop
 * poller ([ProcessStatusPoller]) to a slim retry-only scheduler. Tests
 * 1-4 (immediate fan-out via startAndAwaitFirstPoll, legacy gate ticks,
 * periodic snapshot refresh, 404 summary path) were deleted — they tested
 * the removed loop machinery. Tests 5-6 (single-flight retry, resetBackoff
 * cancels pending) are migrated here to drive the scheduler directly.
 * Test 7 (stale-identity during sweep) is covered by
 * [SlimFanOutRetrySchedulerTest]'s `mid-sweep host switch drops the
 * summary before sink` test (runSlimFanOut's 3rd isCurrent check).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SlimFanOutRetryWiringTest {

    private val identity = ConnectionIdentity(
        epoch = 1L,
        profileId = "group-fp",
        normalizedWorkdir = "/work/dir",
        endpointFp = "endpoint-fp",
    )

    // ── Test 5 (migrated): 503 retry path ───────────────────────────────────

    @Test
    fun `retryable summary triggers single-flight retry after bounded delay`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val store = ConnectionIdentityStore()
        bindIdentity(store)

        val snapshot = StatusSnapshot(
            sessionsById = mapOf("sid-a" to Session(id = "sid-a", directory = "/p")),
            registeredWorkdirs = setOf("/p"),
        )
        val snapshotProvider = SessionSnapshotProvider { snapshot }

        val retryableSummary = StatusFanOutSummary(
            perSid = mapOf("sid-a" to StatusOutcome.Retry("sid-a", null)),
            retryableCount = 1,
            missingSids = emptyList(),
        )

        val invocations = mutableListOf<Unit>()
        val runner: suspend (ConnectionIdentity, StatusSnapshot) -> StatusFanOutSummary? =
            { _, _ ->
                invocations.add(Unit)
                retryableSummary
            }

        val scheduler = SlimFanOutRetryScheduler(
            scope = appScope,
            snapshotProvider = snapshotProvider,
            identityStore = store,
            slimFanOutRunner = runner,
            slimFanOutSummarySink = {},
        )

        // Schedule a single-flight retry with a bounded delay (mirrors what
        // AppCore does when applySlimStatusFanOutSummary emits
        // RequestPollerBackoff → scheduleBackoff → requestSlimFanOutRetry).
        val delayMs = 500L
        scheduler.requestSlimFanOutRetry(delayMs)

        // Before the delay elapses: no new sweep.
        advanceTimeBy(appScope, delayMs / 2)
        runCurrent(appScope)
        assertEquals(
            "retry has not fired before the delay elapses",
            0,
            invocations.size,
        )

        // Advance past the delay: exactly ONE retry sweep fires.
        advanceTimeBy(appScope, delayMs / 2 + 1)
        runCurrent(appScope)
        assertEquals(
            "single-flight retry sweep fired exactly once",
            1,
            invocations.size,
        )

        // Advance further: no additional retry (single-flight, not repeating).
        advanceTimeBy(appScope, delayMs * 4)
        runCurrent(appScope)
        assertEquals(
            "no stacking retries — exactly one extra sweep from the single retry",
            1,
            invocations.size,
        )
    }

    // ── Test 6 (migrated): success cancels pending retry ─────────────────────

    @Test
    fun `resetBackoff cancels any pending slim fan-out retry`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val store = ConnectionIdentityStore()
        bindIdentity(store)

        val snapshot = StatusSnapshot(
            sessionsById = mapOf("sid-a" to Session(id = "sid-a", directory = "/p")),
            registeredWorkdirs = setOf("/p"),
        )
        val snapshotProvider = SessionSnapshotProvider { snapshot }

        val invocations = mutableListOf<Unit>()
        val runner: suspend (ConnectionIdentity, StatusSnapshot) -> StatusFanOutSummary? =
            { _, _ ->
                invocations.add(Unit)
                StatusFanOutSummary.Empty
            }

        val scheduler = SlimFanOutRetryScheduler(
            scope = appScope,
            snapshotProvider = snapshotProvider,
            identityStore = store,
            slimFanOutRunner = runner,
            slimFanOutSummarySink = {},
        )

        // Schedule a retry far in the future.
        val delayMs = 10_000L
        scheduler.requestSlimFanOutRetry(delayMs)

        // A successful sweep arrives. AppCore routes ResetPollerBackoff to
        // resetBackoff which must cancel the pending retry.
        scheduler.resetBackoff()

        // Advance past the original retry delay: the retry MUST NOT fire.
        advanceTimeBy(appScope, delayMs * 2)
        runCurrent(appScope)

        assertEquals(
            "resetBackoff cancelled the pending retry — no extra sweep",
            0,
            invocations.size,
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
