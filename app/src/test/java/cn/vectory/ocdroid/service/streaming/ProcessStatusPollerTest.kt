package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.status.GlobalBusyState
import cn.vectory.ocdroid.service.status.SessionBusyStatus
import cn.vectory.ocdroid.service.status.SessionStatusKey
import cn.vectory.ocdroid.service.status.StatusAggregator
import cn.vectory.ocdroid.service.status.StatusAggregatorInput
import cn.vectory.ocdroid.service.status.StatusSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D2 gate #4 / §4.4 / §6 — unit tests for [ProcessStatusPoller].
 *
 * Verifies the §4.4 acknowledgeable-handoff contract for the poller:
 *  - [ProcessStatusPoller.startAndAwaitFirstPoll] performs the IMMEDIATE
 *    refresh at virtual time ZERO (the §4.4 "final snapshot poll" — no 30s
 *    delay before the first refresh).
 *  - subsequent refreshes fire at the [ProcessStatusPoller.DEFAULT_INTERVAL_MS]
 *    cadence on the injected [CoroutineScope] (the @ApplicationScope in
 *    production — survives [cn.vectory.ocdroid.service.SessionStreamingService.onDestroy]).
 *  - [ProcessStatusPoller.stop] cancels the loop (no further refreshes).
 *  - single-flight: a second startAndAwaitFirstPoll cancels + joins the
 *    prior loop before performing its immediate refresh (no doubled
 *    refreshes per cycle).
 *
 * **L3 source survival**: the test injects a [TestScope] as the "process"
 * scope (mirroring `@ApplicationScope`); a separate "service" scope
 * (cancelled mid-test) holds the controller's poller command dispatch. The
 * L3 survival assertion advances the process scope's virtual clock after
 * the service scope is cancelled and verifies refreshes keep firing.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProcessStatusPollerTest {

    private val identity = ConnectionIdentity(
        epoch = 1L,
        serverGroupFp = "group-fp",
        normalizedWorkdir = "/work/dir",
        endpointFp = "endpoint-fp",
    )

    @Test
    fun `startAndAwaitFirstPoll performs refresh at virtual time ZERO`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val input = RecordingStatusInput(GlobalBusyState.AllIdleFresh)
        val store = ConnectionIdentityStore()
        bindIdentity(store)
        val snapshotProvider = SessionSnapshotProvider { StatusSnapshot.Empty }
        val poller = ProcessStatusPoller(
            scope = appScope,
            statusAggregatorInput = input,
            snapshotProvider = snapshotProvider,
            identityStore = store,
            statusAggregator = input,
            clock = { 0L },
        )

        // No virtual time advance before the call; the immediate refresh
        // happens BEFORE any delay.
        val activation = poller.startAndAwaitFirstPoll(identity, StatusSnapshot.Empty)
        runCurrent()

        assertEquals(
            "StartPoller performs refresh at virtual time ZERO (no delay(30s) first)",
            1,
            input.refreshCount,
        )
        assertEquals(SourceActivation.Ready, activation)

        // Subsequent refreshes at the 30s cadence.
        advanceTimeBy(appScope, ProcessStatusPoller.DEFAULT_INTERVAL_MS)
        runCurrent(appScope)
        assertEquals(2, input.refreshCount)
        advanceTimeBy(appScope, ProcessStatusPoller.DEFAULT_INTERVAL_MS)
        runCurrent(appScope)
        assertEquals(3, input.refreshCount)
    }

    @Test
    fun `stop cancels the poller loop - no further refreshes`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val input = RecordingStatusInput(GlobalBusyState.AllIdleFresh)
        val store = ConnectionIdentityStore()
        bindIdentity(store)
        val snapshotProvider = SessionSnapshotProvider { StatusSnapshot.Empty }
        val poller = ProcessStatusPoller(
            scope = appScope,
            statusAggregatorInput = input,
            snapshotProvider = snapshotProvider,
            identityStore = store,
            statusAggregator = input,
            clock = { 0L },
        )

        poller.startAndAwaitFirstPoll(identity, StatusSnapshot.Empty)
        runCurrent(appScope)
        val refreshesAtStop = input.refreshCount

        poller.stop()
        advanceTimeBy(appScope, ProcessStatusPoller.DEFAULT_INTERVAL_MS * 3)
        runCurrent(appScope)

        assertEquals(
            "stop() cancelled the loop — no further refreshes",
            refreshesAtStop,
            input.refreshCount,
        )
    }

    @Test
    fun `single-flight - second startAndAwaitFirstPoll cancels the prior loop`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val input = RecordingStatusInput(GlobalBusyState.AllIdleFresh)
        val store = ConnectionIdentityStore()
        bindIdentity(store)
        val snapshotProvider = SessionSnapshotProvider { StatusSnapshot.Empty }
        val poller = ProcessStatusPoller(
            scope = appScope,
            statusAggregatorInput = input,
            snapshotProvider = snapshotProvider,
            identityStore = store,
            statusAggregator = input,
            clock = { 0L },
        )

        poller.startAndAwaitFirstPoll(identity, StatusSnapshot.Empty)
        runCurrent(appScope)
        val firstRefreshes = input.refreshCount

        // Second call cancels + joins the prior; immediate refresh fires.
        poller.startAndAwaitFirstPoll(identity, StatusSnapshot.Empty)
        runCurrent(appScope)

        // Exactly ONE additional refresh (the second immediate refresh), not
        // a stacked second loop firing in parallel.
        val secondRefreshes = input.refreshCount
        assertEquals(
            "second startAndAwaitFirstPoll fires one immediate refresh",
            firstRefreshes + 1,
            secondRefreshes,
        )

        advanceTimeBy(appScope, ProcessStatusPoller.DEFAULT_INTERVAL_MS)
        runCurrent(appScope)
        assertEquals(
            "exactly one refresh per interval after restart (no stacking)",
            secondRefreshes + 1,
            input.refreshCount,
        )
    }

    @Test
    fun `L3 survival - service scope cancel does NOT cancel the process-scope poller`() = runTest {
        // Two distinct scopes: appScope (the @ApplicationScope proxy) + the
        // runTest's backgroundScope (the Service MainScope proxy). The poller
        // runs on appScope; cancelling backgroundScope simulates
        // Service.onDestroy. The poller MUST keep running.
        val appScope = TestScope(UnconfinedTestDispatcher())
        val input = RecordingStatusInput(GlobalBusyState.Busy)
        val store = ConnectionIdentityStore()
        bindIdentity(store)
        val snapshotProvider = SessionSnapshotProvider { StatusSnapshot.Empty }
        val poller = ProcessStatusPoller(
            scope = appScope,
            statusAggregatorInput = input,
            snapshotProvider = snapshotProvider,
            identityStore = store,
            statusAggregator = input,
            clock = { 0L },
        )

        // Start the poller.
        poller.startAndAwaitFirstPoll(identity, StatusSnapshot.Empty)
        runCurrent(appScope)
        val initialRefreshes = input.refreshCount

        // Simulate Service.onDestroy — cancel the service scope. The poller
        // lives on appScope so it survives.
        backgroundScope.cancel()

        // Advance the appScope virtual clock — refreshes keep firing.
        advanceTimeBy(appScope, ProcessStatusPoller.DEFAULT_INTERVAL_MS)
        runCurrent(appScope)
        assertTrue(
            "process-scope poller keeps refreshing after service-scope cancel",
            input.refreshCount > initialRefreshes,
        )

        advanceTimeBy(appScope, ProcessStatusPoller.DEFAULT_INTERVAL_MS)
        runCurrent(appScope)
        assertTrue(
            "process-scope poller continues across multiple intervals",
            input.refreshCount >= initialRefreshes + 2,
        )
    }

    @Test
    fun `stale identity is rejected before querying status`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val input = RecordingStatusInput(GlobalBusyState.AllIdleFresh)
        val store = ConnectionIdentityStore()
        bindIdentity(store)
        store.beginReconfigure()
        val poller = ProcessStatusPoller(
            scope = appScope,
            statusAggregatorInput = input,
            snapshotProvider = SessionSnapshotProvider { StatusSnapshot.Empty },
            identityStore = store,
            statusAggregator = input,
            clock = { 0L },
        )

        val activation = poller.startAndAwaitFirstPoll(identity, StatusSnapshot.Empty)

        assertEquals(SourceActivation.Rejected.StaleIdentity, activation)
        assertEquals(0, input.refreshCount)
    }

    @Test
    fun `stop during EnsurePoller first poll rejects late install and later ensure recovers`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val input = RecordingStatusInput(GlobalBusyState.Unknown)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        input.refreshEntered = entered
        input.refreshRelease = release
        val store = ConnectionIdentityStore()
        bindIdentity(store)
        val poller = ProcessStatusPoller(
            scope = appScope,
            statusAggregatorInput = input,
            snapshotProvider = SessionSnapshotProvider { StatusSnapshot.Empty },
            identityStore = store,
            statusAggregator = input,
            clock = { 0L },
        )

        val pending = backgroundScope.async {
            poller.ensureRunning(identity, StatusSnapshot.Empty)
        }
        entered.await()

        // This models coordinator Busy/AllIdleFresh -> StopPoller while the
        // EnsurePoller first-poll command is still suspended.
        poller.stop()
        release.complete(Unit)
        runCurrent(appScope)

        assertEquals(
            "generation guard rejects the late install",
            SourceActivation.Rejected.Superseded,
            pending.await(),
        )
        val refreshesAfterStop = input.refreshCount
        advanceTimeBy(appScope, ProcessStatusPoller.DEFAULT_INTERVAL_MS * 2)
        runCurrent(appScope)
        assertEquals("no loop survives StopPoller", refreshesAfterStop, input.refreshCount)

        // A later legitimate EnsurePoller can still install a fresh loop;
        // the generation guard is invalidation, not a permanent lockout.
        input.refreshEntered = null
        input.refreshRelease = null
        assertEquals(SourceActivation.Ready, poller.ensureRunning(identity, StatusSnapshot.Empty))
        advanceTimeBy(appScope, ProcessStatusPoller.DEFAULT_INTERVAL_MS)
        runCurrent(appScope)
        assertTrue("later ensure starts a fresh loop", input.refreshCount > refreshesAfterStop)
    }

    private fun advanceTimeBy(scope: TestScope, ms: Long) = scope.testScheduler.advanceTimeBy(ms)
    private fun runCurrent(scope: TestScope) = scope.testScheduler.runCurrent()

    private fun bindIdentity(store: ConnectionIdentityStore) {
        store.beginReconfigure()
        assertEquals(
            identity,
            store.bind(identity.serverGroupFp, identity.normalizedWorkdir, identity.endpointFp),
        )
    }

    private class RecordingStatusInput(initial: GlobalBusyState) : StatusAggregator,
        StatusAggregatorInput {
        private val _globalState = MutableStateFlow(initial)
        private val _globalBusy = MutableStateFlow(initial == GlobalBusyState.Busy)
        private val _statusByKey =
            MutableStateFlow<Map<SessionStatusKey, SessionBusyStatus>>(emptyMap())
        override val globalState: StateFlow<GlobalBusyState> = _globalState.asStateFlow()
        override val globalBusy: StateFlow<Boolean> = _globalBusy.asStateFlow()
        override val statusByKey:
            StateFlow<Map<SessionStatusKey, SessionBusyStatus>> = _statusByKey.asStateFlow()
        override fun stateAtNow(): GlobalBusyState = _globalState.value

        var refreshCount: Int = 0
            private set
        var refreshEntered: CompletableDeferred<Unit>? = null
        var refreshRelease: CompletableDeferred<Unit>? = null

        override suspend fun refresh(
            identity: ConnectionIdentity,
            snapshot: StatusSnapshot,
        ) {
            refreshEntered?.complete(Unit)
            refreshRelease?.await()
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
