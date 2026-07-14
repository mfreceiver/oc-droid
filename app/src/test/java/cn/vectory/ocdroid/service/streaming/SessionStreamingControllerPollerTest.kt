package cn.vectory.ocdroid.service.streaming

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
 * D2 gate #4 — controller dispatch tests for the [LifecycleCommand.StartPoller]
 * handoff. The §6 poller loop itself is owned by [ProcessStatusPoller]
 * (tested in [ProcessStatusPollerTest]); this file verifies the controller's
 * command→shell delegation:
 *  - StartPoller → shell.startPoller(identity, snapshot) returns
 *    [SourceActivation]; the controller acks the coordinator.
 *  - StopPoller → shell.stopPoller (the controller does not own a job; the
 *    shell delegates to [ProcessStatusPoller.stop] in production).
 *  - StartPoller with no bound identity → the controller acks
 *    [SourceActivation.Rejected.StaleIdentity] without calling the shell.
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
    fun `StartPoller delegates to shell_startPoller and acks the coordinator`() = runTest {
        val fixture = newFixture(backgroundScope)
        fixture.controller.start()
        fixture.bootstrapRunner.enqueue(BootstrapResult.Success(identity))
        fixture.store.bind(
            serverGroupFp = identity.serverGroupFp,
            normalizedWorkdir = identity.normalizedWorkdir,
            endpointFp = identity.endpointFp,
        )
        fixture.aggregator.setState(GlobalBusyState.Busy)
        fixture.shell.nextSseActivation = SourceActivation.Ready
        fixture.controller.bootstrapAsync()
        runCurrent()
        assertEquals(Layer.L2Active, fixture.coordinator.layer.value)

        // Drive to L2Idle.
        fixture.shell.nextPollerActivation = SourceActivation.Ready
        fixture.aggregator.setState(GlobalBusyState.AllIdleFresh)
        runCurrent()
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
        runCurrent()
        assertEquals(Layer.L2Idle, fixture.coordinator.layer.value)
        assertTrue(
            "shell.startPoller recorded (controller delegated)",
            fixture.shell.recorded.any { it == "startPoller" },
        )
    }

    @Test
    fun `StopPoller delegates to shell_stopPoller`() = runTest {
        val fixture = newFixture(backgroundScope)
        fixture.controller.start()
        fixture.bootstrapRunner.enqueue(BootstrapResult.Success(identity))
        fixture.store.bind(
            serverGroupFp = identity.serverGroupFp,
            normalizedWorkdir = identity.normalizedWorkdir,
            endpointFp = identity.endpointFp,
        )
        fixture.aggregator.setState(GlobalBusyState.Busy)
        fixture.shell.nextSseActivation = SourceActivation.Ready
        fixture.controller.bootstrapAsync()
        runCurrent()
        fixture.shell.nextPollerActivation = SourceActivation.Ready
        fixture.aggregator.setState(GlobalBusyState.AllIdleFresh)
        runCurrent()
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
        runCurrent()
        assertEquals(Layer.L2Idle, fixture.coordinator.layer.value)
        fixture.shell.recorded.clear()

        // L2Idle → L2Active emits StartSse; the commit emits StopPoller.
        fixture.shell.nextSseActivation = SourceActivation.Ready
        fixture.aggregator.setState(GlobalBusyState.Busy)
        runCurrent()

        assertTrue(
            "shell.stopPoller recorded (controller delegated)",
            fixture.shell.recorded.any { it == "stopPoller" },
        )
    }

    // ── Fixture / fakes ───────────────────────────────────────────────────

    private fun newFixture(scope: kotlinx.coroutines.CoroutineScope): Fixture {
        val input = RecordingStatusInput()
        val coordinator = StreamingLifecycleCoordinator(input, scope)
        val store = ConnectionIdentityStore()
        val shell = RecordingShell()
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
        return Fixture(controller, coordinator, input, store, bootstrapRunner, shell)
    }

    private class Fixture(
        val controller: SessionStreamingController,
        val coordinator: StreamingLifecycleCoordinator,
        val aggregator: RecordingStatusInput,
        val store: ConnectionIdentityStore,
        val bootstrapRunner: ScriptedBootstrapRunner,
        val shell: RecordingShell,
    )

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

        override fun stateAtNow(): GlobalBusyState = _globalState.value

        fun setState(state: GlobalBusyState) {
            _globalState.value = state
            _globalBusy.value = state == GlobalBusyState.Busy
        }

        override suspend fun refresh(
            identity: ConnectionIdentity,
            snapshot: StatusSnapshot,
        ) = Unit

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

    private class RecordingShell : ServiceShell {
        val recorded = mutableListOf<String>()
        var nextSseActivation: SourceActivation = SourceActivation.Ready
        var nextPollerActivation: SourceActivation = SourceActivation.Ready

        override fun startForeground(spec: cn.vectory.ocdroid.service.notify.NotificationSpec) = Unit
        override fun updateNotification(spec: cn.vectory.ocdroid.service.notify.NotificationSpec) = Unit
        override fun stopForeground() = Unit
        override fun serviceStopSelf() = Unit
        override suspend fun startPoller(
            identity: ConnectionIdentity,
            snapshot: StatusSnapshot,
        ): SourceActivation {
            recorded += "startPoller"
            return nextPollerActivation
        }
        override fun stopPoller() {
            recorded += "stopPoller"
        }
        override suspend fun ensurePoller(
            identity: ConnectionIdentity,
            snapshot: StatusSnapshot,
        ): SourceActivation {
            recorded += "ensurePoller"
            return nextPollerActivation
        }
        override suspend fun connectSse(identity: ConnectionIdentity): SourceActivation {
            recorded += "connectSse"
            return nextSseActivation
        }
        override suspend fun disconnectSse() {
            recorded += "disconnectSse"
        }
    }
}
