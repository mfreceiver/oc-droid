package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.data.repository.StatusOutcome
import cn.vectory.ocdroid.data.state.AuthorityOp
import cn.vectory.ocdroid.service.status.StatusFanOutSummary
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SliceFlows
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §P1-B/E retry-queue wire (rev-glm N1): coordinator-level coverage for
 * [SessionSyncCoordinator.applySlimStatusFanOutSummary] retry-queue dispatch.
 *
 * The reducer-level tests in [cn.vectory.ocdroid.ui.AuthorityReducerTest] pin
 * the pure reducer behavior (enqueue / LRU / idempotent / fire / cleanup), but
 * the WIRING — the coordinator deciding WHEN to dispatch RetryQueued /
 * RetryFired, the snapshot-before-fire attempt increment, and the
 * fire-then-queue net effect on the same sid — was previously untested. This
 * file closes that gap so the acceptance criteria (#1/#2) are backed by a real
 * end-to-end dispatch path, not just reducer capability.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetryQueueWireTest {

    private val serverGroupFp = "test-fp"
    private lateinit var slices: SliceFlows
    private lateinit var effects: SharedEffectBus
    private lateinit var scope: TestScope
    private lateinit var coordinator: SessionSyncCoordinator
    private var clockNow: Long = 1_000L

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        io.mockk.every { Log.w(any<String>(), any<String>()) } returns 0
        io.mockk.every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        val store = cn.vectory.ocdroid.ui.SharedStateStore()
        slices = store.slices
        effects = SharedEffectBus()
        scope = TestScope(UnconfinedTestDispatcher())
        coordinator = SessionSyncCoordinator(
            scope = scope,
            slices = slices,
            settingsManager = mockk(relaxed = true),
            effects = effects,
            currentServerGroupFp = { serverGroupFp },
            identityStore = cn.vectory.ocdroid.service.identity.ConnectionIdentityStore(),
            statusAggregatorInput = null,
            clock = { clockNow },
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun retryQueue() = slices.store.stateFlow.value.authority.retryQueue

    @Test
    fun `fence DROPS stale summary when identity epoch changed between sweep-start and dispatch`() = runTest {
        // §U-CQ5: verify the initial identityEpoch is 0L.
        assertEquals(0L, slices.store.stateFlow.value.identityEpoch)
        // Bump identityEpoch via mutateHost to simulate a host switch.
        slices.store.mutateHost { it.copy(currentHostProfileId = "new-host") }
        assertEquals(1L, slices.store.stateFlow.value.identityEpoch)

        // Collect effects so we can assert ZERO dispatch across ALL side-effect
        // paths (EvictSession / RequestPollerBackoff / ResetPollerBackoff), not
        // just retryQueue. This locks the fence's position to BEFORE the
        // missingSids→EvictSession loop (regression guard: moving the fence to
        // after that loop would leak EvictSession, which this test now catches).
        val collected = mutableListOf<ControllerEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { effects.effectsConsumed.toList(collected) }

        // Construct a summary whose sweepStartEpoch (0L) != current epoch (1L)
        // → the fence MUST DROP the entire summary (no dispatch). Non-empty
        // missingSids + a Retry sid + retryableCount=1 so that WITHOUT the
        // fence ALL four side-effect paths would fire (EvictSession +
        // RequestPollerBackoff + RetryQueued). Failing-first: a fence placed
        // too late (e.g. after the EvictSession loop) would still enqueue s1
        // (queue non-empty) AND emit EvictSession — the old single-assertion
        // test caught only the queue; this one catches both.
        val staleSummary = StatusFanOutSummary(
            perSid = mapOf("s1" to StatusOutcome.Retry("s1", null)),
            retryableCount = 1,
            missingSids = listOf("stale-sid"),
            sweepStartEpoch = 0L,
        )
        coordinator.applySlimStatusFanOutSummary(staleSummary)
        advanceUntilIdle()
        job.cancel()

        // §U-CQ5: DROP → zero side effects across all paths.
        assertTrue("retryQueue empty after stale summary DROP", retryQueue().isEmpty())
        assertTrue("no EvictSession leaked after stale summary DROP",
            collected.none { it is ControllerEffect.EvictSession })
        assertTrue("no RequestPollerBackoff leaked after stale summary DROP",
            collected.none { it is ControllerEffect.RequestPollerBackoff })
    }

    @Test
    fun `fence PASS when sweepStartEpoch matches current epoch (non-zero explicit)`() {
        // Bump epoch first so we test with non-zero epoch (not just the default 0L path).
        slices.store.mutateHost { it.copy(currentHostProfileId = "new-host") }
        val currentEpoch = slices.store.stateFlow.value.identityEpoch
        assertEquals(1L, currentEpoch)

        clockNow = 5_000L
        val summary = StatusFanOutSummary(
            perSid = mapOf("s1" to StatusOutcome.Retry("s1", null)),
            retryableCount = 1,
            missingSids = emptyList(),
            sweepStartEpoch = currentEpoch,  // explicit match → fence passes
        )
        coordinator.applySlimStatusFanOutSummary(summary)

        // Normal dispatch: retryQueue has the entry.
        assertEquals(1, retryQueue().size)
        assertNotNull("s1 enqueued", retryQueue()["s1"])
        assertEquals(1, retryQueue()["s1"]?.attempt)
    }

    @Test
    fun `retryable sweep enqueues Retry sids with attempt 1 and nominal backoff`() {
        clockNow = 5_000L
        val summary = StatusFanOutSummary(
            perSid = mapOf(
                "s1" to StatusOutcome.Retry("s1", "upstream_unavailable"),
                "s2" to StatusOutcome.Retry("s2", null),
            ),
            retryableCount = 2,
            missingSids = emptyList(),
        )

        coordinator.applySlimStatusFanOutSummary(summary)

        val q = retryQueue()
        assertEquals(2, q.size)
        val e1 = q["s1"]
        assertNotNull(e1)
        assertEquals(1, e1!!.attempt)
        assertEquals(5_000L, e1.queuedAtMs)
        // attempt 0 → exponentialBackoffMs = BASE * 2^0 = 200
        assertEquals(200L, e1.backoffMs)
        val e2 = q["s2"]
        assertEquals(1, e2?.attempt)
        assertEquals(200L, e2?.backoffMs)
    }

    @Test
    fun `successful sweep does not enqueue and emits ResetPollerBackoff`() = runTest {
        clockNow = 5_000L
        val summary = StatusFanOutSummary(
            perSid = mapOf("s1" to StatusOutcome.Success(
                sessionId = "s1",
                status = cn.vectory.ocdroid.data.model.SessionStatus(type = "busy"),
            )),
            retryableCount = 0,
            missingSids = emptyList(),
        )

        val collected = mutableListOf<ControllerEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { effects.effectsConsumed.toList(collected) }
        coordinator.applySlimStatusFanOutSummary(summary)
        advanceUntilIdle()

        assertTrue("no entries on success", retryQueue().isEmpty())
        assertTrue("ResetPollerBackoff emitted on success",
            collected.any { it is ControllerEffect.ResetPollerBackoff })
        job.cancel()
    }

    @Test
    fun `retryable sweep emits RequestPollerBackoff alongside enqueue`() = runTest {
        clockNow = 5_000L
        val summary = StatusFanOutSummary(
            perSid = mapOf("s1" to StatusOutcome.Retry("s1", null)),
            retryableCount = 1,
            missingSids = emptyList(),
        )

        val collected = mutableListOf<ControllerEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { effects.effectsConsumed.toList(collected) }
        coordinator.applySlimStatusFanOutSummary(summary)
        advanceUntilIdle()

        assertTrue("RequestPollerBackoff emitted",
            collected.any { it is ControllerEffect.RequestPollerBackoff })
        job.cancel()
    }

    @Test
    fun `second retryable sweep increments attempt and refreshes the entry`() {
        clockNow = 5_000L
        val first = StatusFanOutSummary(
            perSid = mapOf("s1" to StatusOutcome.Retry("s1", null)),
            retryableCount = 1,
            missingSids = emptyList(),
        )
        coordinator.applySlimStatusFanOutSummary(first)
        assertEquals(1, retryQueue()["s1"]?.attempt)
        assertEquals(200L, retryQueue()["s1"]?.backoffMs)

        // Second sweep: s1 is still in the queue, still Retry.
        clockNow = 6_000L
        val second = StatusFanOutSummary(
            perSid = mapOf("s1" to StatusOutcome.Retry("s1", null)),
            retryableCount = 1,
            missingSids = emptyList(),
        )
        coordinator.applySlimStatusFanOutSummary(second)

        val entry = retryQueue()["s1"]
        assertNotNull(entry)
        assertEquals(2, entry!!.attempt)
        // attempt 1 → exponentialBackoffMs = BASE * 2^1 = 400
        assertEquals(400L, entry.backoffMs)
        assertEquals(6_000L, entry.queuedAtMs)
    }

    @Test
    fun `re-sweep of a previously-queued sid fires it first then re-queues with attempt increment`() {
        // Seed the queue with s1 at attempt 1.
        clockNow = 5_000L
        coordinator.applySlimStatusFanOutSummary(StatusFanOutSummary(
            perSid = mapOf("s1" to StatusOutcome.Retry("s1", null)),
            retryableCount = 1, missingSids = emptyList(),
        ))
        assertEquals(1, retryQueue()["s1"]?.attempt)

        // The re-sweep covers s1. The coordinator fires (dequeue) s1 FIRST,
        // then re-queues it at attempt+1. Net effect: one entry, attempt 2.
        clockNow = 6_000L
        coordinator.applySlimStatusFanOutSummary(StatusFanOutSummary(
            perSid = mapOf("s1" to StatusOutcome.Retry("s1", null)),
            retryableCount = 1, missingSids = emptyList(),
        ))

        val q = retryQueue()
        assertEquals("exactly one entry for s1 (fired then re-queued)", 1, q.size)
        assertEquals(2, q["s1"]?.attempt)
    }

    @Test
    fun `sweep covering a queued sid with non-Retry outcome fires it and does not re-queue`() {
        // Seed s1 as queued (Retry).
        clockNow = 5_000L
        coordinator.applySlimStatusFanOutSummary(StatusFanOutSummary(
            perSid = mapOf("s1" to StatusOutcome.Retry("s1", null)),
            retryableCount = 1, missingSids = emptyList(),
        ))
        assertTrue("s1 queued", "s1" in retryQueue())

        // Next sweep: s1 now returns Success (busy). The sweep covers s1 → fire.
        // Success is NOT Retry → no re-queue. s1 leaves the queue.
        clockNow = 6_000L
        coordinator.applySlimStatusFanOutSummary(StatusFanOutSummary(
            perSid = mapOf("s1" to StatusOutcome.Success(
                sessionId = "s1",
                status = cn.vectory.ocdroid.data.model.SessionStatus(type = "busy"),
            )),
            retryableCount = 0, missingSids = emptyList(),
        ))

        assertFalse("s1 fired by covering sweep (not re-queued: non-Retry)",
            "s1" in retryQueue())
    }

    @Test
    fun `snapshot-before-fire locks the attempt counter across the sweep`() {
        // This locks rev-glm N1's core concern: the auth snapshot is read ONCE
        // before any fire dispatch, so the attempt increment is consistent with
        // the fire decision. If the snapshot were re-read AFTER fire, attempt
        // would regress to 1 (fire removed the entry → prevAttempt=0).
        clockNow = 5_000L
        coordinator.applySlimStatusFanOutSummary(StatusFanOutSummary(
            perSid = mapOf("s1" to StatusOutcome.Retry("s1", null)),
            retryableCount = 1, missingSids = emptyList(),
        ))
        // Three consecutive re-sweeps → attempt should climb 2, 3, 4 (not reset).
        for (i in 2..4) {
            clockNow = 5_000L + i * 1_000L
            coordinator.applySlimStatusFanOutSummary(StatusFanOutSummary(
                perSid = mapOf("s1" to StatusOutcome.Retry("s1", null)),
                retryableCount = 1, missingSids = emptyList(),
            ))
            assertEquals("sweep #$i → attempt = $i",
                i, retryQueue()["s1"]?.attempt)
        }
    }

    @Test
    fun `missingSids also fire any queued entry for that sid (sweep covers it)`() {
        // Seed s1 as queued.
        clockNow = 5_000L
        coordinator.applySlimStatusFanOutSummary(StatusFanOutSummary(
            perSid = mapOf("s1" to StatusOutcome.Retry("s1", null)),
            retryableCount = 1, missingSids = emptyList(),
        ))
        assertTrue("s1 queued", "s1" in retryQueue())

        // Next sweep: s1 is now missing (404). It's still in summary.perSid
        // (the fold keeps it) → fire fires. Not Retry → not re-queued.
        clockNow = 6_000L
        coordinator.applySlimStatusFanOutSummary(StatusFanOutSummary(
            perSid = mapOf("s1" to StatusOutcome.SessionMissing(sessionId = "s1")),
            retryableCount = 0,
            missingSids = listOf("s1"),
        ))

        assertFalse("queued s1 fired when reported missing",
            "s1" in retryQueue())
    }

    @Test
    fun `nominal backoff clamped to BACKOFF_MAX_MS at high attempt counts`() {
        // rev-glm N3: exponentialBackoffMs overshoots at high attempts;
        // the coordinator clamps to BACKOFF_MAX_MS (30s) to match the poller.
        clockNow = 5_000L
        // Drive 10 consecutive retryable sweeps so attempt climbs well past
        // the BACKOFF_MAX_SHIFT=8 flat-top. exponentialBackoffMs at attempt=9
        // would be 200 * 2^8 = 51200 (> 30_000), but the clamp must bind.
        for (i in 1..10) {
            clockNow = 5_000L + i * 1_000L
            coordinator.applySlimStatusFanOutSummary(StatusFanOutSummary(
                perSid = mapOf("s1" to StatusOutcome.Retry("s1", null)),
                retryableCount = 1, missingSids = emptyList(),
            ))
        }
        val backoff = retryQueue()["s1"]?.backoffMs
        assertNotNull(backoff)
        assertTrue("backoff clamped to BACKOFF_MAX_MS (got $backoff)",
            backoff!! <= cn.vectory.ocdroid.service.streaming.ProcessStatusPoller.BACKOFF_MAX_MS)
    }

    @Test
    fun `retryQueueFlow reflects coordinator-driven enqueue and fire`() {
        clockNow = 5_000L
        // Enqueue.
        coordinator.applySlimStatusFanOutSummary(StatusFanOutSummary(
            perSid = mapOf("s1" to StatusOutcome.Retry("s1", null)),
            retryableCount = 1, missingSids = emptyList(),
        ))
        assertEquals(1, slices.store.retryQueueFlow.value["s1"]?.attempt)

        // Fire via covering sweep with non-Retry.
        clockNow = 6_000L
        coordinator.applySlimStatusFanOutSummary(StatusFanOutSummary(
            perSid = mapOf("s1" to StatusOutcome.Success(
                sessionId = "s1",
                status = cn.vectory.ocdroid.data.model.SessionStatus(type = "busy"),
            )),
            retryableCount = 0, missingSids = emptyList(),
        ))
        assertNull("flow reflects fire", slices.store.retryQueueFlow.value["s1"])
    }
}
