package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.lifecycle.Layer
import cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator
import cn.vectory.ocdroid.service.notify.NotificationStrings
import cn.vectory.ocdroid.service.status.GlobalBusyState
import cn.vectory.ocdroid.service.status.SessionBusyStatus
import cn.vectory.ocdroid.service.status.SessionStatusKey
import cn.vectory.ocdroid.service.status.StatusAggregator
import cn.vectory.ocdroid.service.status.StatusSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FGS spec §6 — poller behavior of [SessionStreamingController].
 *
 * Verifies the §6 background poller:
 *  - fires [cn.vectory.ocdroid.service.status.StatusAggregatorInput.refresh]
 *    on the test scheduler at the poller interval (virtual clock);
 *  - is cancelled on `StopPoller` (no further refresh calls after the stop);
 *  - is idempotent: a second `StartPoller` does not stack a second job (no
 *    doubled refresh calls per interval).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionStreamingControllerPollerTest {

    private val identity = ConnectionIdentity(
        epoch = 1L,
        serverGroupFp = "group-fp",
        normalizedWorkdir = "/work/dir",
        endpointFp = "endpoint-fp",
    )

    private val strings = NotificationStrings(
        appName = "OC Droid",
        restoringConnection = "Restoring connection…",
        busySingular = "1 task running",
        busyPluralFormat = "%1\$d tasks running",
        connected = "Connected",
        idleMonitoring = "Idle monitoring",
        degradedTitle = "Server trust required",
        degradedContent = "Open the app to confirm server trust",
    )

    @Test
    fun `StartPoller fires refresh on the poller interval`() = runTest {
        val fixture = newFixture(backgroundScope)
        fixture.controller.start()
        // Bootstrap background + Busy → L2Active directly.
        fixture.bootstrapRunner.enqueue(BootstrapResult.Success(identity))
        fixture.store.bind(
            serverGroupFp = identity.serverGroupFp,
            normalizedWorkdir = identity.normalizedWorkdir,
            endpointFp = identity.endpointFp,
        )
        fixture.aggregator.setState(GlobalBusyState.Busy)
        fixture.controller.bootstrapAsync()
        runCurrent()
        assertEquals(Layer.L2Active, fixture.coordinator.layer.value)

        // All-idle → 45s debounce → L2Idle emits StartPoller.
        fixture.aggregator.setState(GlobalBusyState.AllIdleFresh)
        runCurrent()
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
        runCurrent()
        assertEquals(Layer.L2Idle, fixture.coordinator.layer.value)

        val refreshesBefore = fixture.input.refreshCount
        // Advance one poll interval — should fire exactly one refresh.
        advanceTimeBy(SessionStreamingController.DEFAULT_POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(
            "one refresh per interval",
            refreshesBefore + 1,
            fixture.input.refreshCount,
        )

        advanceTimeBy(SessionStreamingController.DEFAULT_POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(refreshesBefore + 2, fixture.input.refreshCount)
    }

    @Test
    fun `StopPoller cancels the poller - no further refresh calls`() = runTest {
        val fixture = newFixture(backgroundScope)
        fixture.controller.start()
        fixture.bootstrapRunner.enqueue(BootstrapResult.Success(identity))
        fixture.store.bind(
            serverGroupFp = identity.serverGroupFp,
            normalizedWorkdir = identity.normalizedWorkdir,
            endpointFp = identity.endpointFp,
        )
        fixture.aggregator.setState(GlobalBusyState.Busy)
        fixture.controller.bootstrapAsync()
        runCurrent()
        // Background busy → L2Active. Now idle → debounce → L2Idle.
        fixture.aggregator.setState(GlobalBusyState.AllIdleFresh)
        runCurrent()
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
        runCurrent()
        assertEquals(Layer.L2Idle, fixture.coordinator.layer.value)

        // Then busy again — L2Idle → L2Active IN-PLACE emits StopPoller.
        fixture.aggregator.setState(GlobalBusyState.Busy)
        runCurrent()
        assertEquals(Layer.L2Active, fixture.coordinator.layer.value)

        val refreshesAtStop = fixture.input.refreshCount
        // Advance well past the poll interval — no new refreshes should fire.
        advanceTimeBy(SessionStreamingController.DEFAULT_POLL_INTERVAL_MS * 3)
        runCurrent()
        assertEquals(
            "StopPoller cancelled the poller — no further refreshes",
            refreshesAtStop,
            fixture.input.refreshCount,
        )
    }

    @Test
    fun `poller is idempotent - second StartPoller does not stack a job`() = runTest {
        val fixture = newFixture(backgroundScope)
        fixture.controller.start()
        // Drive into L2Idle once.
        fixture.bootstrapRunner.enqueue(BootstrapResult.Success(identity))
        fixture.store.bind(
            serverGroupFp = identity.serverGroupFp,
            normalizedWorkdir = identity.normalizedWorkdir,
            endpointFp = identity.endpointFp,
        )
        fixture.aggregator.setState(GlobalBusyState.Busy)
        fixture.controller.bootstrapAsync()
        runCurrent()
        fixture.aggregator.setState(GlobalBusyState.AllIdleFresh)
        runCurrent()
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
        runCurrent()
        assertEquals(Layer.L2Idle, fixture.coordinator.layer.value)

        // Cycle back through L2Active → L2Idle to force a second StartPoller
        // (the controller should have cancelled the prior poller first).
        fixture.aggregator.setState(GlobalBusyState.Busy)
        runCurrent()
        assertEquals(Layer.L2Active, fixture.coordinator.layer.value)
        fixture.aggregator.setState(GlobalBusyState.AllIdleFresh)
        runCurrent()
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
        runCurrent()
        assertEquals(Layer.L2Idle, fixture.coordinator.layer.value)

        val refreshesBefore = fixture.input.refreshCount
        // One interval → exactly one refresh (not two — no stacking).
        advanceTimeBy(SessionStreamingController.DEFAULT_POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(
            "idempotent poller — exactly one refresh per interval after restart",
            refreshesBefore + 1,
            fixture.input.refreshCount,
        )
    }

    @Test
    fun `poller skips cycle when no identity is bound`() = runTest {
        val fixture = newFixture(backgroundScope)
        // Do NOT bind identity — poller should skip refresh gracefully.
        fixture.controller.start()
        fixture.bootstrapRunner.enqueue(BootstrapResult.Success(identity))
        fixture.aggregator.setState(GlobalBusyState.Busy)
        // Bind then unbind via beginReconfigure so currentIdentity.value == null.
        fixture.store.bind(
            serverGroupFp = identity.serverGroupFp,
            normalizedWorkdir = identity.normalizedWorkdir,
            endpointFp = identity.endpointFp,
        )
        fixture.controller.bootstrapAsync()
        runCurrent()
        fixture.store.beginReconfigure() // nulls currentIdentity
        fixture.aggregator.setState(GlobalBusyState.AllIdleFresh)
        runCurrent()
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
        runCurrent()
        assertEquals(Layer.L2Idle, fixture.coordinator.layer.value)

        val refreshesBefore = fixture.input.refreshCount
        advanceTimeBy(SessionStreamingController.DEFAULT_POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(
            "no identity → poller skips refresh (no exception, no call)",
            refreshesBefore,
            fixture.input.refreshCount,
        )
    }

    // ── Fixture / fakes ───────────────────────────────────────────────────

    private fun newFixture(scope: kotlinx.coroutines.CoroutineScope): Fixture {
        val input = RecordingStatusInput()
        val coordinator = StreamingLifecycleCoordinator(input, scope)
        val store = ConnectionIdentityStore()
        val shell = NoopShell()
        val snapshotProvider = SessionSnapshotProvider { StatusSnapshot.Empty }
        val bootstrapRunner = ScriptedBootstrapRunner()
        val inForegroundFlow = MutableStateFlow(false)
        val controller = SessionStreamingController(
            coordinator = coordinator,
            statusAggregator = input,
            statusAggregatorInput = input,
            identityStore = store,
            sessionSnapshotProvider = snapshotProvider,
            bootstrapRunner = bootstrapRunner,
            shell = shell,
            strings = strings,
            inForeground = inForegroundFlow,
            scope = scope,
            clock = { 0L },
        )
        return Fixture(controller, coordinator, input, store, bootstrapRunner)
    }

    private class Fixture(
        val controller: SessionStreamingController,
        val coordinator: StreamingLifecycleCoordinator,
        val aggregator: RecordingStatusInput,
        val store: ConnectionIdentityStore,
        val bootstrapRunner: ScriptedBootstrapRunner,
    ) {
        val input: RecordingStatusInput get() = aggregator
    }

    /**
     * Recording [StatusAggregator] + [cn.vectory.ocdroid.service.status.StatusAggregatorInput]:
     * counts refresh calls and exposes [setState] for driving transitions.
     */
    private class RecordingStatusInput : StatusAggregator,
        cn.vectory.ocdroid.service.status.StatusAggregatorInput {
        private val _globalState = MutableStateFlow(GlobalBusyState.AllIdleFresh)
        private val _globalBusy = MutableStateFlow(false)
        private val _statusByKey =
            MutableStateFlow<Map<SessionStatusKey, SessionBusyStatus>>(emptyMap())

        override val globalState: StateFlow<GlobalBusyState> = _globalState.asStateFlow()
        override val globalBusy: StateFlow<Boolean> = _globalBusy.asStateFlow()
        override val statusByKey: StateFlow<Map<SessionStatusKey, SessionBusyStatus>> =
            _statusByKey.asStateFlow()

        /** D1 gate #1: stateAtNow tracks globalState in the fake. */
        override fun stateAtNow(): GlobalBusyState = _globalState.value

        var refreshCount: Int = 0
            private set

        fun setState(state: GlobalBusyState) {
            _globalState.value = state
            _globalBusy.value = state == GlobalBusyState.Busy
        }

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

    private class ScriptedBootstrapRunner : BootstrapRunner {
        private val queue = ArrayDeque<BootstrapResult>()
        fun enqueue(result: BootstrapResult) {
            queue.addLast(result)
        }

        override suspend fun runBootstrap(): BootstrapResult =
            queue.removeFirstOrNull() ?: BootstrapResult.Failed
    }

    private class NoopShell : ServiceShell {
        override fun startForeground(spec: cn.vectory.ocdroid.service.notify.NotificationSpec) = Unit
        override fun updateNotification(spec: cn.vectory.ocdroid.service.notify.NotificationSpec) = Unit
        override fun stopForeground() = Unit
        override fun serviceStopSelf() = Unit
        override fun startPoller() = Unit
        override fun stopPoller() = Unit
        override fun connectSse(identity: ConnectionIdentity) = Unit
        override fun disconnectSse() = Unit
    }
}
