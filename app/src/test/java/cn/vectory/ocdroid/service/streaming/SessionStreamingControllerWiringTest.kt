package cn.vectory.ocdroid.service.streaming

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
import cn.vectory.ocdroid.service.status.StatusSnapshot
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
 * [SessionStreamingController.handleUserClose] (D2 gate #8).
 *
 * Both forwarders exist so the Service (`SessionStreamingService.onTimeout`
 * + the `onStartCommand` ACTION_CLOSE_BACKGROUND branch) is a single-line
 * pass-through. D2 gate #4: the teardown handoff (StartPoller) requires an
 * activation ack; the test scripts the ack via the recording shell's
 * [RecordingShell.nextPollerActivation].
 *
 * D2 gate #8 ([handleUserClose]): the request variant carries an
 * [UserCloseRequest] with the parsed identity from the PendingIntent; the
 * handler revalidates against the current identity before teardown.
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
        fixture.shell.nextSseActivation = SourceActivation.Ready
        fixture.controller.bootstrapAsync()
        runCurrent()
        assertEquals(Layer.L2Active, fixture.coordinator.layer.value)
        fixture.shell.recorded.clear()

        // Act: §4.1 dataSync platform timeout. The teardown needs the poller
        // activation ack to commit; the shell returns nextPollerActivation.
        fixture.shell.nextPollerActivation = SourceActivation.Ready
        fixture.controller.onServiceTimeout()
        runCurrent()

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
        val bound = fixture.store.bind(
            serverGroupFp = identity.serverGroupFp,
            normalizedWorkdir = identity.normalizedWorkdir,
            endpointFp = identity.endpointFp,
        )
        fixture.aggregator.setState(GlobalBusyState.Busy)
        fixture.shell.nextSseActivation = SourceActivation.Ready
        fixture.controller.bootstrapAsync()
        runCurrent()
        assertEquals(Layer.L2Active, fixture.coordinator.layer.value)
        fixture.shell.recorded.clear()

        fixture.shell.nextPollerActivation = SourceActivation.Ready
        fixture.controller.requestUserClose(UserCloseRequest(expectedIdentity = bound))
        runCurrent()

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
        assertEquals(Layer.L3, fixture.coordinator.layer.value)
        fixture.shell.recorded.clear()

        fixture.controller.onServiceTimeout()
        runCurrent()

        assertEquals(
            "onServiceTimeout on L3 → no shell side-effects",
            0,
            fixture.shell.recorded.size,
        )
        assertEquals(Layer.L3, fixture.coordinator.layer.value)
    }

    @Test
    fun `no-identity close dismisses degraded shell even when coordinator is L3`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
        fixture.controller.start()
        assertEquals(Layer.L3, fixture.coordinator.layer.value)
        fixture.shell.recorded.clear()

        fixture.controller.requestUserClose(UserCloseRequest(expectedIdentity = null))
        runCurrent()

        assertEquals(listOf("stopForeground", "serviceStopSelf"), fixture.shell.recorded)
        assertEquals(Layer.L3, fixture.coordinator.layer.value)
    }

    // ── D2 gate #8 — handleUserClose identity / status recheck ────────────

    @Test
    fun `D2 gate #8 - current identity plus Busy refresh - teardown still occurs`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
        fixture.controller.start()
        fixture.bootstrapRunner.enqueue(BootstrapResult.Success(identity))
        val bound = fixture.store.bind(
            serverGroupFp = identity.serverGroupFp,
            normalizedWorkdir = identity.normalizedWorkdir,
            endpointFp = identity.endpointFp,
        )
        fixture.aggregator.setState(GlobalBusyState.Busy)
        fixture.shell.nextSseActivation = SourceActivation.Ready
        fixture.controller.bootstrapAsync()
        runCurrent()
        assertEquals(Layer.L2Active, fixture.coordinator.layer.value)
        fixture.shell.recorded.clear()
        fixture.shell.nextPollerActivation = SourceActivation.Ready

        // Close request with the current identity; refresh reports Busy.
        // Teardown MUST still occur (Busy is NOT a veto for an explicit close).
        fixture.controller.handleUserClose(UserCloseRequest(expectedIdentity = bound))
        runCurrent()

        assertEquals(Layer.L3, fixture.coordinator.layer.value)
        assertTrue(
            "serviceStopSelf recorded (Busy does not veto)",
            fixture.shell.recorded.any { it == "serviceStopSelf" },
        )
    }

    @Test
    fun `D2 gate #8 - current identity plus idle - teardown occurs`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
        fixture.controller.start()
        fixture.bootstrapRunner.enqueue(BootstrapResult.Success(identity))
        val bound = fixture.store.bind(
            serverGroupFp = identity.serverGroupFp,
            normalizedWorkdir = identity.normalizedWorkdir,
            endpointFp = identity.endpointFp,
        )
        fixture.aggregator.setState(GlobalBusyState.Busy)
        fixture.shell.nextSseActivation = SourceActivation.Ready
        fixture.controller.bootstrapAsync()
        runCurrent()
        assertEquals(Layer.L2Active, fixture.coordinator.layer.value)
        fixture.shell.recorded.clear()
        fixture.shell.nextPollerActivation = SourceActivation.Ready
        fixture.aggregator.setState(GlobalBusyState.AllIdleFresh)

        fixture.controller.handleUserClose(UserCloseRequest(expectedIdentity = bound))
        runCurrent()

        assertTrue(
            "teardown occurred on current+idle",
            fixture.shell.recorded.any { it == "serviceStopSelf" },
        )
    }

    @Test
    fun `D2 gate #8 - status failure plus unchanged identity - teardown still occurs`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
        fixture.controller.start()
        fixture.bootstrapRunner.enqueue(BootstrapResult.Success(identity))
        val bound = fixture.store.bind(
            serverGroupFp = identity.serverGroupFp,
            normalizedWorkdir = identity.normalizedWorkdir,
            endpointFp = identity.endpointFp,
        )
        fixture.aggregator.setState(GlobalBusyState.Busy)
        fixture.shell.nextSseActivation = SourceActivation.Ready
        fixture.controller.bootstrapAsync()
        runCurrent()
        fixture.shell.recorded.clear()
        fixture.shell.nextPollerActivation = SourceActivation.Ready

        // Mark refresh to produce Unknown; identity unchanged.
        fixture.aggregator.refreshReturnsUnknown = true
        fixture.controller.handleUserClose(UserCloseRequest(expectedIdentity = bound))
        runCurrent()

        assertTrue(
            "teardown occurred on Unknown + stable identity (close must not appear broken)",
            fixture.shell.recorded.any { it == "serviceStopSelf" },
        )
    }

    @Test
    fun `D2 gate #8 - epoch changes mid-refresh - stale action does NOT tear down`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
        fixture.controller.start()
        fixture.bootstrapRunner.enqueue(BootstrapResult.Success(identity))
        val bound = fixture.store.bind(
            serverGroupFp = identity.serverGroupFp,
            normalizedWorkdir = identity.normalizedWorkdir,
            endpointFp = identity.endpointFp,
        )
        fixture.aggregator.setState(GlobalBusyState.Busy)
        fixture.shell.nextSseActivation = SourceActivation.Ready
        fixture.controller.bootstrapAsync()
        runCurrent()
        fixture.shell.recorded.clear()

        // The refresh callback bumps the epoch (simulating a reconfigure
        // arriving during the suspend refresh).
        fixture.aggregator.onRefresh = {
            fixture.store.beginReconfigure()
        }
        fixture.controller.handleUserClose(UserCloseRequest(expectedIdentity = bound))
        runCurrent()

        assertEquals(
            "stale-at-mid-refresh action did NOT tear down",
            Layer.L2Active,
            fixture.coordinator.layer.value,
        )
        assertTrue(
            "no teardown side-effects",
            fixture.shell.recorded.none { it == "serviceStopSelf" },
        )
    }

    @Test
    fun `D2 gate #8 - stale-at-entry (expected identity not current) - no query, no teardown`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
        fixture.controller.start()
        fixture.bootstrapRunner.enqueue(BootstrapResult.Success(identity))
        val bound = fixture.store.bind(
            serverGroupFp = identity.serverGroupFp,
            normalizedWorkdir = identity.normalizedWorkdir,
            endpointFp = identity.endpointFp,
        )
        fixture.aggregator.setState(GlobalBusyState.Busy)
        fixture.shell.nextSseActivation = SourceActivation.Ready
        fixture.controller.bootstrapAsync()
        runCurrent()
        fixture.shell.recorded.clear()

        // Stale-at-entry: the bound identity is no longer current.
        fixture.store.beginReconfigure()
        val refreshesBeforeClose = fixture.aggregator.refreshCount
        fixture.aggregator.onRefresh = {
            error("refresh MUST NOT be called for a stale-at-entry action")
        }
        fixture.controller.handleUserClose(UserCloseRequest(expectedIdentity = bound))
        runCurrent()

        assertEquals(
            "stale-at-entry action did NOT change the layer (still L2Active)",
            Layer.L2Active,
            fixture.coordinator.layer.value,
        )
        assertEquals(
            "no shell side-effects",
            0,
            fixture.shell.recorded.size,
        )
        assertEquals(refreshesBeforeClose, fixture.aggregator.refreshCount)
    }

    @Test
    fun `D2 gate #8 - degraded placeholder no identity - closes directly`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
        fixture.controller.start()
        // No bootstrap → no identity bound.
        // Close request with expectedIdentity = null (the degraded placeholder).
        fixture.shell.nextPollerActivation = SourceActivation.Ready
        fixture.controller.handleUserClose(UserCloseRequest(expectedIdentity = null))
        runCurrent()

        assertEquals(
            listOf("stopForeground", "serviceStopSelf"),
            fixture.shell.recorded,
        )
        assertEquals(0, fixture.aggregator.refreshCount)
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
        val snapshotProvider = SessionSnapshotProvider { StatusSnapshot.Empty }
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

        override fun stateAtNow(): GlobalBusyState = _globalState.value

        var refreshCount: Int = 0
            private set
        var refreshReturnsUnknown: Boolean = false
        var onRefresh: () -> Unit = {}

        fun setState(state: GlobalBusyState) {
            _globalState.value = state
            _globalBusy.value = state == GlobalBusyState.Busy
        }

        fun setStatusByKey(map: Map<SessionStatusKey, SessionBusyStatus>) {
            _statusByKey.value = map
        }

        override suspend fun refresh(
            identity: ConnectionIdentity,
            snapshot: StatusSnapshot,
        ) {
            refreshCount++
            if (refreshReturnsUnknown) {
                _globalState.value = GlobalBusyState.Unknown
            }
            onRefresh()
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

    private class RecordingShell : ServiceShell {
        val recorded = mutableListOf<String>()
        var nextSseActivation: SourceActivation = SourceActivation.Ready
        var nextPollerActivation: SourceActivation = SourceActivation.Ready

        override fun startForeground(spec: NotificationSpec) { recorded += "startForeground" }
        override fun updateNotification(spec: NotificationSpec) { recorded += "updateNotification" }
        override fun stopForeground() { recorded += "stopForeground" }
        override fun serviceStopSelf() { recorded += "serviceStopSelf" }
        override suspend fun startPoller(
            identity: ConnectionIdentity,
            snapshot: StatusSnapshot,
        ): SourceActivation {
            recorded += "startPoller"
            return nextPollerActivation
        }
        override fun stopPoller() { recorded += "stopPoller" }
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
        override suspend fun disconnectSse() { recorded += "disconnectSse" }
    }

    private class ScriptedBootstrapRunner : BootstrapRunner {
        private val queue = ArrayDeque<BootstrapResult>()
        fun enqueue(result: BootstrapResult) { queue.addLast(result) }
        override suspend fun runBootstrap(): BootstrapResult =
            queue.removeFirstOrNull() ?: BootstrapResult.Failed
    }
}
