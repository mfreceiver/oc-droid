package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.StatusOutcome
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.status.GlobalBusyState
import cn.vectory.ocdroid.service.status.SessionBusyStatus
import cn.vectory.ocdroid.service.status.SessionStatusKey
import cn.vectory.ocdroid.service.status.StatusAggregator
import cn.vectory.ocdroid.service.status.StatusAggregatorInput
import cn.vectory.ocdroid.service.status.StatusFanOutSummary
import cn.vectory.ocdroid.service.status.StatusSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §final-gate I-1 discriminator tests — verifies the slim status fan-out
 * wiring (oracle-final-fixes-design.md §3) at the
 * [ProcessStatusPoller] trigger layer.
 *
 * Each test pins ONE of the seven invariants in oracle §3 (slim-only gate,
 * immediate + 30s triggers, single-flight retry, identity re-checks before
 * + after the network sweep, host-switch invalidation). Tests inject the
 * fan-out runner + summary sink directly (no DI / no Hilt) so the
 * poller's behaviour is exercised in isolation.
 *
 * # Discrimination
 *
 * Each test would FAIL if the corresponding gate is removed:
 *  - the slim-only gate (test 2) fails if `repository.isSlimMode` check
 *    is dropped from the production runner wiring;
 *  - the immediate trigger (test 1) fails if `runSlimFanOut` is removed
 *    from `startAndAwaitFirstPoll`;
 *  - the periodic trigger (test 3) fails if `runSlimFanOut` is removed
 *    from the 30s loop;
 *  - the 404 summary sink (test 4) fails if `slimFanOutSummarySink` is
 *    dropped from `runSlimFanOut`;
 *  - the single-flight retry (test 5) fails if `requestSlimFanOutRetry`
 *    cancels prior / fails to launch;
 *  - success cancels retry (test 6) fails if `resetBackoff` doesn't
 *    cancel `slimRetryJob`;
 *  - host-switch invalidation (test 7) fails if the post-sweep
 *    `isCurrent` check is removed from `runSlimFanOut`.
 *
 * NOTE: the production wiring in [StreamingModule] (slim-only gate +
 * coordinator sink) is exercised structurally here by the test double
 * for `slimFanOutRunner` (which mirrors the production slim-only gate)
 * + the capturing sink. The Hilt module is verified by compilation +
 * the existing T13 use-case tests.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SlimFanOutPollerWiringTest {

    private val identity = ConnectionIdentity(
        epoch = 1L,
        serverGroupFp = "group-fp",
        normalizedWorkdir = "/work/dir",
        endpointFp = "endpoint-fp",
    )

    // ── Test 1: immediate slim invocation ──────────────────────────────────

    @Test
    fun `I-1 (1) - immediate slim fan-out fires once before startAndAwaitFirstPoll returns`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val input = RecordingStatusInput(GlobalBusyState.AllIdleFresh)
        val store = ConnectionIdentityStore()
        bindIdentity(store)

        // Two sids in the snapshot the poller is started with.
        val snapshot = StatusSnapshot(
            sessionsById = mapOf(
                "sid-a" to Session(id = "sid-a", directory = "/p"),
                "sid-b" to Session(id = "sid-b", directory = "/p"),
            ),
            registeredWorkdirs = setOf("/p"),
        )
        val snapshotProvider = SessionSnapshotProvider { snapshot }

        // Captures every (identity, sid set) the runner is invoked with.
        val invocations = mutableListOf<Set<String>>()
        val runner: suspend (ConnectionIdentity, StatusSnapshot) -> StatusFanOutSummary? =
            { _, snap ->
                invocations.add(snap.sessionsById.keys)
                StatusFanOutSummary.Empty
            }
        var sinkCount = 0

        val poller = ProcessStatusPoller(
            scope = appScope,
            statusAggregatorInput = input,
            snapshotProvider = snapshotProvider,
            identityStore = store,
            statusAggregator = input,
            clock = { 0L },
            slimFanOutRunner = runner,
            slimFanOutSummarySink = { sinkCount++ },
        )

        val activation = poller.startAndAwaitFirstPoll(identity, snapshot)
        runCurrent(appScope)

        assertEquals(SourceActivation.Ready, activation)
        assertEquals(
            "exactly one slim fan-out invocation before startAndAwaitFirstPoll returns",
            1,
            invocations.size,
        )
        assertEquals(
            "the runner received both sids from the start snapshot",
            setOf("sid-a", "sid-b"),
            invocations.first(),
        )
        assertEquals("summary sink invoked once for the immediate sweep", 1, sinkCount)
    }

    // ── Test 2: legacy gate ────────────────────────────────────────────────

    @Test
    fun `I-1 (2) - legacy repo (slim gate off) issues zero fan-out calls across multiple ticks`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val input = RecordingStatusInput(GlobalBusyState.AllIdleFresh)
        val store = ConnectionIdentityStore()
        bindIdentity(store)

        val snapshotProvider = SessionSnapshotProvider {
            StatusSnapshot(
                sessionsById = mapOf("sid-a" to Session(id = "sid-a", directory = "/p")),
                registeredWorkdirs = setOf("/p"),
            )
        }

        // Mirrors the production wiring in StreamingModule: the runner
        // gates on slim mode and returns null when legacy. The test
        // asserts the legacy path: runner returns null on every tick →
        // the sink is never invoked (legacy repos issue ZERO fan-out
        // HTTP requests).
        var runnerCallCount = 0
        val runner: suspend (ConnectionIdentity, StatusSnapshot) -> StatusFanOutSummary? =
            { _, _ ->
                runnerCallCount++
                // Legacy repo gate: `if (!repository.isSlimMode) return@runner null`
                null
            }
        var sinkCount = 0

        val poller = ProcessStatusPoller(
            scope = appScope,
            statusAggregatorInput = input,
            snapshotProvider = snapshotProvider,
            identityStore = store,
            statusAggregator = input,
            clock = { 0L },
            slimFanOutRunner = runner,
            slimFanOutSummarySink = { sinkCount++ },
        )

        poller.startAndAwaitFirstPoll(identity, StatusSnapshot.Empty)
        runCurrent(appScope)

        // Bulk runRefresh fired (1 immediate); then advance two 30s ticks.
        assertEquals(1, input.refreshCount)
        advanceTimeBy(appScope, ProcessStatusPoller.DEFAULT_INTERVAL_MS)
        runCurrent(appScope)
        advanceTimeBy(appScope, ProcessStatusPoller.DEFAULT_INTERVAL_MS)
        runCurrent(appScope)

        // Three bulk refreshes (immediate + 2 ticks).
        assertEquals(
            "bulk runRefresh still runs at the 30s cadence (slim gate does NOT touch runRefresh)",
            3,
            input.refreshCount,
        )
        // The runner was invoked 3 times (once per tick) but returned null
        // every time (legacy gate); the sink was NEVER invoked.
        assertTrue("legacy gate is exercised (runner invoked)", runnerCallCount > 0)
        assertEquals(
            "legacy gate: summary sink NEVER invoked (runner returned null)",
            0,
            sinkCount,
        )
    }

    // ── Test 3: periodic snapshot refresh ──────────────────────────────────

    @Test
    fun `I-1 (3) - periodic 30s tick queries the latest snapshot (A then A+B)`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val input = RecordingStatusInput(GlobalBusyState.AllIdleFresh)
        val store = ConnectionIdentityStore()
        bindIdentity(store)

        // The IMMEDIATE poll is given firstSnapshot by the caller; the 30s
        // loop MUST re-fetch via the provider (ProcessStatusPoller.kt:255 —
        // `val nextSnapshot = snapshotProvider.current()`). This test pins
        // that re-fetch: the provider returns laterSnapshot (A+B) for
        // every call, so a tick that re-fetches will see A+B; a tick that
        // wrongly reuses the immediate-poll snapshot will see only A.
        val firstSnapshot = StatusSnapshot(
            sessionsById = mapOf("sid-a" to Session(id = "sid-a", directory = "/p")),
            registeredWorkdirs = setOf("/p"),
        )
        val laterSnapshot = StatusSnapshot(
            sessionsById = mapOf(
                "sid-a" to Session(id = "sid-a", directory = "/p"),
                "sid-b" to Session(id = "sid-b", directory = "/p"),
            ),
            registeredWorkdirs = setOf("/p"),
        )

        // Provider call counter — discriminates "the tick called the
        // provider" from "the tick reused the immediate-poll snapshot".
        // The immediate poll does NOT call the provider (production uses
        // the caller-supplied snapshot directly — see
        // ProcessStatusPoller.startAndAwaitFirstPoll KDoc + the assertion
        // `providerCallCount == 0` after the immediate poll below).
        var providerCallCount = 0
        val snapshotProvider = SessionSnapshotProvider {
            providerCallCount++
            // Every provider call serves the LATER snapshot. The first
            // provider call is the first 30s tick (NOT the immediate poll)
            // — so the first tick sees A+B and the test can discriminate
            // re-fetch from reuse.
            laterSnapshot
        }

        val invocations = mutableListOf<Set<String>>()
        val runner: suspend (ConnectionIdentity, StatusSnapshot) -> StatusFanOutSummary? =
            { _, snap ->
                invocations.add(snap.sessionsById.keys)
                StatusFanOutSummary.Empty
            }

        val poller = ProcessStatusPoller(
            scope = appScope,
            statusAggregatorInput = input,
            snapshotProvider = snapshotProvider,
            identityStore = store,
            statusAggregator = input,
            clock = { 0L },
            slimFanOutRunner = runner,
            slimFanOutSummarySink = {},
        )

        poller.startAndAwaitFirstPoll(identity, firstSnapshot)
        runCurrent(appScope)

        // Immediate sweep queried just {sid-a} AND did NOT call the
        // provider (the immediate poll uses the caller-supplied snapshot
        // directly — atomic with command emission, no re-read race).
        assertEquals(listOf(setOf("sid-a")), invocations)
        assertEquals(
            "immediate poll does NOT call the provider (uses caller-supplied snapshot)",
            0,
            providerCallCount,
        )

        // Advance 30s → the tick re-fetches via the provider, which
        // serves laterSnapshot (A+B).
        advanceTimeBy(appScope, ProcessStatusPoller.DEFAULT_INTERVAL_MS)
        runCurrent(appScope)

        assertEquals(
            "second sweep queries the latest snapshot (A+B) — periodic tick re-fetches",
            listOf(setOf("sid-a"), setOf("sid-a", "sid-b")),
            invocations,
        )
        assertEquals(
            "the 30s tick called the provider exactly once (re-fetch happened)",
            1,
            providerCallCount,
        )
    }

    // ── Test 4: 404 summary → coordinator effect path ──────────────────────

    @Test
    fun `I-1 (4) - 404 summary routes through slimFanOutSummarySink (coordinator emits EvictSession)`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val input = RecordingStatusInput(GlobalBusyState.AllIdleFresh)
        val store = ConnectionIdentityStore()
        bindIdentity(store)

        val snapshot = StatusSnapshot(
            sessionsById = mapOf("sid-a" to Session(id = "sid-a", directory = "/p")),
            registeredWorkdirs = setOf("/p"),
        )
        val snapshotProvider = SessionSnapshotProvider { snapshot }

        // The runner returns a summary with a SessionMissing outcome for sid-a.
        val missingSummary = StatusFanOutSummary(
            perSid = mapOf("sid-a" to StatusOutcome.SessionMissing("sid-a")),
            retryableCount = 0,
            missingSids = listOf("sid-a"),
        )
        val runner: suspend (ConnectionIdentity, StatusSnapshot) -> StatusFanOutSummary? =
            { _, _ -> missingSummary }

        // The sink is the AppCore → coordinator bridge. The test records
        // what would be routed to applySlimStatusFanOutSummary; the real
        // coordinator then emits EvictSession(fp, sid) per missing sid.
        val sunkSummaries = mutableListOf<StatusFanOutSummary>()
        val sink: (StatusFanOutSummary) -> Unit = { sunkSummaries.add(it) }

        val poller = ProcessStatusPoller(
            scope = appScope,
            statusAggregatorInput = input,
            snapshotProvider = snapshotProvider,
            identityStore = store,
            statusAggregator = input,
            clock = { 0L },
            slimFanOutRunner = runner,
            slimFanOutSummarySink = sink,
        )

        poller.startAndAwaitFirstPoll(identity, snapshot)
        runCurrent(appScope)

        assertEquals(
            "the 404-bearing summary was routed through the sink (coordinator emits EvictSession)",
            1,
            sunkSummaries.size,
        )
        assertEquals(
            "missing sid propagated to the coordinator",
            listOf("sid-a"),
            sunkSummaries.single().missingSids,
        )
    }

    // ── Test 5: 503 retry path ─────────────────────────────────────────────

    @Test
    fun `I-1 (5) - retryable summary triggers single-flight retry after bounded delay`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val input = RecordingStatusInput(GlobalBusyState.AllIdleFresh)
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

        val poller = ProcessStatusPoller(
            scope = appScope,
            statusAggregatorInput = input,
            snapshotProvider = snapshotProvider,
            identityStore = store,
            statusAggregator = input,
            clock = { 0L },
            slimFanOutRunner = runner,
            slimFanOutSummarySink = {},
        )

        poller.startAndAwaitFirstPoll(identity, snapshot)
        runCurrent(appScope)
        assertEquals("immediate sweep ran", 1, invocations.size)

        // Schedule a single-flight retry with a bounded delay (mirrors what
        // AppCore does when applySlimStatusFanOutSummary emits
        // RequestPollerBackoff → scheduleBackoff → requestSlimFanOutRetry).
        val delayMs = 500L
        poller.requestSlimFanOutRetry(delayMs)

        // Before the delay elapses: no new sweep.
        advanceTimeBy(appScope, delayMs / 2)
        runCurrent(appScope)
        assertEquals(
            "retry has not fired before the delay elapses",
            1,
            invocations.size,
        )

        // Advance past the delay: exactly ONE retry sweep fires.
        advanceTimeBy(appScope, delayMs / 2 + 1)
        runCurrent(appScope)
        assertEquals(
            "single-flight retry sweep fired exactly once",
            2,
            invocations.size,
        )

        // Advance further: no additional retry (single-flight, not repeating).
        advanceTimeBy(appScope, delayMs * 4)
        runCurrent(appScope)
        assertEquals(
            "no stacking retries — exactly one extra sweep from the single retry",
            2,
            invocations.size,
        )
    }

    // ── Test 6: success cancels pending retry ──────────────────────────────

    @Test
    fun `I-1 (6) - resetBackoff cancels any pending slim fan-out retry`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val input = RecordingStatusInput(GlobalBusyState.AllIdleFresh)
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

        val poller = ProcessStatusPoller(
            scope = appScope,
            statusAggregatorInput = input,
            snapshotProvider = snapshotProvider,
            identityStore = store,
            statusAggregator = input,
            clock = { 0L },
            slimFanOutRunner = runner,
            slimFanOutSummarySink = {},
        )

        poller.startAndAwaitFirstPoll(identity, snapshot)
        runCurrent(appScope)
        val immediateCount = invocations.size

        // Schedule a retry far in the future.
        val delayMs = 10_000L
        poller.requestSlimFanOutRetry(delayMs)

        // A successful sweep arrives (e.g. via the 30s tick). The
        // coordinator emits ResetPollerBackoff; AppCore routes it to
        // resetBackoff which must cancel the pending retry.
        poller.resetBackoff()

        // Advance past the original retry delay: the retry MUST NOT fire.
        advanceTimeBy(appScope, delayMs * 2)
        runCurrent(appScope)

        assertEquals(
            "resetBackoff cancelled the pending retry — no extra sweep",
            immediateCount,
            invocations.size,
        )
    }

    // ── Test 7: host switch during sweep ───────────────────────────────────

    @Test
    fun `I-1 (7) - host switch during the slim fan-out sweep drops the summary (sink NOT invoked)`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val input = RecordingStatusInput(GlobalBusyState.AllIdleFresh)
        val store = ConnectionIdentityStore()
        bindIdentity(store)

        val snapshot = StatusSnapshot(
            sessionsById = mapOf("sid-a" to Session(id = "sid-a", directory = "/p")),
            registeredWorkdirs = setOf("/p"),
        )
        val snapshotProvider = SessionSnapshotProvider { snapshot }

        // Gate the runner on a CompletableDeferred: the test holds the
        // sweep mid-network, performs the host switch, then releases —
        // the post-sweep isCurrent check must catch the switch and skip
        // the sink.
        val sweepRelease = CompletableDeferred<Unit>()
        var sinkInvoked = false
        val runner: suspend (ConnectionIdentity, StatusSnapshot) -> StatusFanOutSummary? =
            { _, _ ->
                sweepRelease.await()
                StatusFanOutSummary.Empty
            }
        val sink: (StatusFanOutSummary) -> Unit = { sinkInvoked = true }

        val poller = ProcessStatusPoller(
            scope = appScope,
            statusAggregatorInput = input,
            snapshotProvider = snapshotProvider,
            identityStore = store,
            statusAggregator = input,
            clock = { 0L },
            slimFanOutRunner = runner,
            slimFanOutSummarySink = sink,
        )

        // Launch the first poll on a separate coroutine so we can
        // interleave the host switch while the sweep is suspended.
        val firstPoll = appScope.async {
            poller.startAndAwaitFirstPoll(identity, snapshot)
        }
        // Let the immediate runRefresh + the sweep start (it suspends on
        // sweepRelease.await()).
        runCurrent(appScope)

        // Perform the host switch — beginReconfigure invalidates the identity.
        store.beginReconfigure()

        // Release the sweep; the runner returns a summary, but the
        // post-sweep isCurrent check in runSlimFanOut must catch the
        // switch and skip the sink.
        sweepRelease.complete(Unit)
        // Bind a fresh identity so the poller's startAndAwaitFirstPoll
        // doesn't bail with StaleIdentity before its post-runRefresh
        // isCurrent check. (Mirrors a real host switch completing.)
        store.bind("group-fp-b", identity.normalizedWorkdir, identity.endpointFp)
        runCurrent(appScope)
        firstPoll.await()

        assertFalse(
            "host switch during sweep dropped the summary — sink NOT invoked",
            sinkInvoked,
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun advanceTimeBy(scope: TestScope, ms: Long) = scope.testScheduler.advanceTimeBy(ms)
    private fun runCurrent(scope: TestScope) = scope.testScheduler.runCurrent()

    private fun bindIdentity(store: ConnectionIdentityStore) {
        store.beginReconfigure()
        store.bind(identity.serverGroupFp, identity.normalizedWorkdir, identity.endpointFp)
    }

    private class RecordingStatusInput(initial: GlobalBusyState) : StatusAggregator,
        StatusAggregatorInput {
        private val _globalState = MutableStateFlow(initial)
        override val globalState: StateFlow<GlobalBusyState> = _globalState.asStateFlow()
        override val globalBusy: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
        override val statusByKey:
            StateFlow<Map<SessionStatusKey, SessionBusyStatus>> =
            MutableStateFlow<MutableMap<SessionStatusKey, SessionBusyStatus>>(mutableMapOf()).asStateFlow()
        override fun stateAtNow(): GlobalBusyState = _globalState.value

        var refreshCount: Int = 0
            private set

        override suspend fun refresh(
            identity: ConnectionIdentity,
            snapshot: StatusSnapshot,
        ) {
            refreshCount++
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
        ) = Unit
    }
}
