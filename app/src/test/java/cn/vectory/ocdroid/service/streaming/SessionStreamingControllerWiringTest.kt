package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.lifecycle.Layer
import cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator
import cn.vectory.ocdroid.service.notify.NotificationSpec
import cn.vectory.ocdroid.service.notify.NotificationStrings
import cn.vectory.ocdroid.service.status.GlobalBusyState
import cn.vectory.ocdroid.service.status.SessionBusyStatus
import cn.vectory.ocdroid.service.status.SessionStatusKey
import cn.vectory.ocdroid.service.status.StatusAggregator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FGS spec §4.1 / §16-U1 — pure-JVM wiring tests for
 * [SessionStreamingController.onServiceTimeout] +
 * [SessionStreamingController.requestUserClose].
 *
 * Both forwarders exist so the Service (`SessionStreamingService.onTimeout`
 * + the `onStartCommand` ACTION_CLOSE_BACKGROUND branch) is a single-line
 * pass-through and the wiring is verifiable end-to-end on a pure JVM
 * (no Robolectric) via the existing recording-shell / fake-aggregator
 * fixture pattern from [SessionStreamingControllerDispatchTest].
 *
 * What these tests verify (the §4.4 teardown sequence is the same as for
 * `coordinator.requestUserClose()` / `coordinator.onTimeout()` — both routes
 * funnel into [StreamingLifecycleCoordinator.teardown]):
 *  - calling `controller.onServiceTimeout()` / `controller.requestUserClose()`
 *    on an L2Active shell drives the §4.4 teardown command sequence
 *    (`stopForeground` BEFORE `serviceStopSelf`, etc.) and lands the
 *    coordinator in L3;
 *  - on an already-L3 controller, both forwarders are a no-op (no extra
 *    commands emitted).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionStreamingControllerWiringTest {

    private val identity = ConnectionIdentity(
        epoch = 9L,
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
    fun `onServiceTimeout drives teardown from L2Active to L3 with section 4_4 ordering`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
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
        assertEquals(Layer.L2Active, fixture.coordinator.layer.value)
        fixture.shell.recorded.clear()

        // Act: §4.1 dataSync platform timeout.
        fixture.controller.onServiceTimeout()
        runCurrent()

        // L3 teardown sequence arrived on the shell in §4.4 order.
        assertEquals(Layer.L3, fixture.coordinator.layer.value)
        val stopFgIdx = fixture.shell.recorded.indexOf("stopForeground")
        val stopSelfIdx = fixture.shell.recorded.indexOf("serviceStopSelf")
        assertTrue("stopForeground recorded", stopFgIdx >= 0)
        assertTrue("serviceStopSelf recorded", stopSelfIdx >= 0)
        assertTrue(
            "§4.4: stopForeground (idx=$stopFgIdx) BEFORE serviceStopSelf (idx=$stopSelfIdx)",
            stopFgIdx < stopSelfIdx,
        )
    }

    @Test
    fun `requestUserClose drives teardown from L2Active to L3 with section 4_4 ordering (section 16-U1)`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
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
        assertEquals(Layer.L2Active, fixture.coordinator.layer.value)
        fixture.shell.recorded.clear()

        // Act: §16-U1 user-explicit close.
        fixture.controller.requestUserClose()
        runCurrent()

        // Same teardown path as onTimeout — both reach coordinator.teardown().
        assertEquals(Layer.L3, fixture.coordinator.layer.value)
        val stopFgIdx = fixture.shell.recorded.indexOf("stopForeground")
        val stopSelfIdx = fixture.shell.recorded.indexOf("serviceStopSelf")
        assertTrue("stopForeground recorded", stopFgIdx >= 0)
        assertTrue("serviceStopSelf recorded", stopSelfIdx >= 0)
        assertTrue(
            "§4.4: stopForeground (idx=$stopFgIdx) BEFORE serviceStopSelf (idx=$stopSelfIdx)",
            stopFgIdx < stopSelfIdx,
        )
    }

    @Test
    fun `onServiceTimeout is a no-op when controller is already at L3`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
        fixture.controller.start()
        // No bootstrap → coordinator stays at its initial L3.
        assertEquals(Layer.L3, fixture.coordinator.layer.value)
        fixture.shell.recorded.clear()

        fixture.controller.onServiceTimeout()
        runCurrent()

        // No commands emitted on an already-torn-down machine.
        assertEquals(
            "onServiceTimeout on L3 → no shell side-effects",
            0,
            fixture.shell.recorded.size,
        )
        assertEquals(Layer.L3, fixture.coordinator.layer.value)
    }

    @Test
    fun `requestUserClose is a no-op when controller is already at L3`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
        fixture.controller.start()
        assertEquals(Layer.L3, fixture.coordinator.layer.value)
        fixture.shell.recorded.clear()

        fixture.controller.requestUserClose()
        runCurrent()

        assertEquals(
            "requestUserClose on L3 → no shell side-effects",
            0,
            fixture.shell.recorded.size,
        )
        assertEquals(Layer.L3, fixture.coordinator.layer.value)
    }

    // ── Fixture / fakes ───────────────────────────────────────────────────

    private fun newFixture(
        scope: kotlinx.coroutines.CoroutineScope,
        inForeground: Boolean,
    ): Fixture {
        val aggregator = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(aggregator, scope)
        val store = ConnectionIdentityStore()
        val shell = RecordingShell()
        val snapshotProvider = SessionSnapshotProvider { cn.vectory.ocdroid.service.status.StatusSnapshot.Empty }
        val bootstrapRunner = ScriptedBootstrapRunner()
        val inForegroundFlow = MutableStateFlow(inForeground)
        val controller = SessionStreamingController(
            coordinator = coordinator,
            statusAggregator = aggregator,
            statusAggregatorInput = aggregator,
            identityStore = store,
            sessionSnapshotProvider = snapshotProvider,
            bootstrapRunner = bootstrapRunner,
            shell = shell,
            strings = strings,
            inForeground = inForegroundFlow,
            scope = scope,
            clock = { 0L },
        )
        return Fixture(controller, coordinator, aggregator, store, shell, bootstrapRunner)
    }

    private class Fixture(
        val controller: SessionStreamingController,
        val coordinator: StreamingLifecycleCoordinator,
        val aggregator: FakeStatusAggregator,
        val store: ConnectionIdentityStore,
        val shell: RecordingShell,
        val bootstrapRunner: ScriptedBootstrapRunner,
    )

    internal open class FakeStatusAggregator : StatusAggregator,
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

        fun setState(state: GlobalBusyState) {
            _globalState.value = state
            _globalBusy.value = state == GlobalBusyState.Busy
        }

        override suspend fun refresh(
            identity: ConnectionIdentity,
            snapshot: cn.vectory.ocdroid.service.status.StatusSnapshot,
        ) = Unit

        override fun applySseStatus(
            key: SessionStatusKey,
            status: SessionBusyStatus,
            sourceTimeMs: Long,
        ) = Unit

        override fun markRequestFailed(
            identity: ConnectionIdentity,
            snapshot: cn.vectory.ocdroid.service.status.StatusSnapshot,
            sourceTimeMs: Long,
        ) = Unit
    }

    private class RecordingShell : ServiceShell {
        val recorded = mutableListOf<String>()

        override fun startForeground(spec: NotificationSpec) { recorded += "startForeground" }
        override fun updateNotification(spec: NotificationSpec) { recorded += "updateNotification" }
        override fun stopForeground() { recorded += "stopForeground" }
        override fun serviceStopSelf() { recorded += "serviceStopSelf" }
        override fun startPoller() { recorded += "startPoller" }
        override fun stopPoller() { recorded += "stopPoller" }
        override fun connectSse(identity: ConnectionIdentity) { recorded += "connectSse" }
        override fun disconnectSse() { recorded += "disconnectSse" }
    }

    private class ScriptedBootstrapRunner : BootstrapRunner {
        private val queue = ArrayDeque<BootstrapResult>()
        fun enqueue(result: BootstrapResult) { queue.addLast(result) }
        override suspend fun runBootstrap(): BootstrapResult =
            queue.removeFirstOrNull() ?: BootstrapResult.Failed
    }
}
