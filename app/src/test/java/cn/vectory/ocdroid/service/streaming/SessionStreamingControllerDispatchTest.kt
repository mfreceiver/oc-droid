package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.lifecycle.Layer
import cn.vectory.ocdroid.service.lifecycle.LifecycleCommand
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FGS spec §4.4 / §5 / §6 — pure-JVM dispatch tests for
 * [SessionStreamingController] + the real [StreamingLifecycleCoordinator].
 *
 * Verifies the §4.4 unified handoff ordering reaches the [ServiceShell] in
 * the exact order the coordinator emits (new source active BEFORE closing
 * old), via a recording fake shell — no Robolectric.
 *
 * The shell sees the controller's dispatch surface, which is exactly the
 * lifecycle command stream translated:
 *  - StartForeground → shell.startForeground(spec);
 *  - StopForeground  → shell.stopForeground();
 *  - StopSelf        → shell.serviceStopSelf();
 *  - StartSse(id)    → shell.connectSse(id);
 *  - StopSse         → shell.disconnectSse();
 *  - StartPoller     → shell.startPoller()  (controller owns the job);
 *  - StopPoller      → shell.stopPoller().
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionStreamingControllerDispatchTest {

    private val identity = ConnectionIdentity(
        epoch = 7L,
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
    fun `bootstrap background busy emits StartSse then StopPoller in order`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
        fixture.start()
        // bootstrap background + busy → coordinator emits StartSse + StopPoller.
        fixture.bootstrapSucceed(identity)
        fixture.aggregator.setState(GlobalBusyState.Busy)
        fixture.controller.bootstrapAsync()
        runCurrent()

        assertEquals(Layer.L2Active, fixture.coordinator.layer.value)
        // §4.4 ordering: StartSse (new source) BEFORE StopPoller (old retired).
        val startSseIdx = fixture.shell.recorded.indexOfFirst { it.startsWith("connectSse") }
        val stopPollerIdx = fixture.shell.recorded.indexOf("stopPoller")
        assertTrue("connectSse must be recorded", startSseIdx >= 0)
        assertTrue("stopPoller must be recorded", stopPollerIdx >= 0)
        assertTrue(
            "connectSse (idx=$startSseIdx) must precede stopPoller (idx=$stopPollerIdx)",
            startSseIdx < stopPollerIdx,
        )
    }

    @Test
    fun `L2Active to L2Idle debounce emits startPoller then disconnectSse in order (section 4_4)`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
        fixture.start()
        fixture.bootstrapSucceed(identity)
        fixture.aggregator.setState(GlobalBusyState.Busy)
        fixture.controller.bootstrapAsync()
        runCurrent()
        assertEquals(Layer.L2Active, fixture.coordinator.layer.value)
        fixture.shell.recorded.clear()

        // All-idle → debounce arms.
        fixture.aggregator.setState(GlobalBusyState.AllIdleFresh)
        runCurrent()
        // Cross the 45s threshold.
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
        runCurrent()

        assertEquals(Layer.L2Idle, fixture.coordinator.layer.value)
        val startPollerIdx = fixture.shell.recorded.indexOf("startPoller")
        val disconnectSseIdx = fixture.shell.recorded.indexOf("disconnectSse")
        assertTrue("startPoller recorded", startPollerIdx >= 0)
        assertTrue("disconnectSse recorded", disconnectSseIdx >= 0)
        assertTrue(
            "§4.4: startPoller (idx=$startPollerIdx) BEFORE disconnectSse (idx=$disconnectSseIdx)",
            startPollerIdx < disconnectSseIdx,
        )
    }

    @Test
    fun `L2Idle finds busy emits connectSse then stopPoller in place (groker-R1)`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
        fixture.start()
        fixture.bootstrapSucceed(identity)
        fixture.aggregator.setState(GlobalBusyState.Busy)
        fixture.controller.bootstrapAsync()
        runCurrent()
        fixture.aggregator.setState(GlobalBusyState.AllIdleFresh)
        runCurrent()
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
        runCurrent()
        assertEquals(Layer.L2Idle, fixture.coordinator.layer.value)
        fixture.shell.recorded.clear()

        // groker-R1: poller finds busy → in-place L2Idle → L2Active.
        fixture.aggregator.setState(GlobalBusyState.Busy)
        runCurrent()

        assertEquals(Layer.L2Active, fixture.coordinator.layer.value)
        val connectSseIdx = fixture.shell.recorded.indexOfFirst { it.startsWith("connectSse") }
        val stopPollerIdx = fixture.shell.recorded.indexOf("stopPoller")
        assertTrue(connectSseIdx >= 0)
        assertTrue(stopPollerIdx >= 0)
        assertTrue(
            "§4.4: connectSse (idx=$connectSseIdx) BEFORE stopPoller (idx=$stopPollerIdx)",
            connectSseIdx < stopPollerIdx,
        )
    }

    @Test
    fun `L3 teardown emits stopForeground then serviceStopSelf`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
        fixture.start()
        fixture.bootstrapSucceed(identity)
        fixture.aggregator.setState(GlobalBusyState.Busy)
        fixture.controller.bootstrapAsync()
        runCurrent()
        assertEquals(Layer.L2Active, fixture.coordinator.layer.value)
        fixture.shell.recorded.clear()

        fixture.coordinator.requestUserClose()
        runCurrent()

        assertEquals(Layer.L3, fixture.coordinator.layer.value)
        val stopFgIdx = fixture.shell.recorded.indexOf("stopForeground")
        val stopSelfIdx = fixture.shell.recorded.indexOf("serviceStopSelf")
        assertTrue("stopForeground recorded", stopFgIdx >= 0)
        assertTrue("serviceStopSelf recorded", stopSelfIdx >= 0)
        assertTrue(
            "stopForeground (idx=$stopFgIdx) BEFORE serviceStopSelf (idx=$stopSelfIdx)",
            stopFgIdx < stopSelfIdx,
        )
    }

    @Test
    fun `StartForeground command carries NotificationSpec derived from layer and busy count`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = true)
        fixture.start()
        // Pre-set the status map so currentBusyCount() = 2 when StartForeground fires.
        fixture.aggregator.setStatusByKey(
            mapOf(
                SessionStatusKey("group-fp", "/work/dir", "s1") to SessionBusyStatus.Busy,
                SessionStatusKey("group-fp", "/work/dir", "s2") to SessionBusyStatus.Retry,
            ),
        )
        // bootstrap foreground + idle → L1-idle (no StartForeground yet).
        fixture.bootstrapSucceed(identity)
        fixture.aggregator.setState(GlobalBusyState.AllIdleFresh)
        fixture.controller.bootstrapAsync()
        runCurrent()
        assertEquals(Layer.L1(busy = false), fixture.coordinator.layer.value)
        fixture.shell.recorded.clear()

        // Busy arrival → L1-idle → L1-busy emits StartForeground.
        fixture.aggregator.setState(GlobalBusyState.Busy)
        runCurrent()

        assertEquals(Layer.L1(busy = true), fixture.coordinator.layer.value)
        val fgSpec = fixture.shell.lastStartForegroundSpec
        assertTrue("startForeground must have been called", fgSpec != null)
        // 2 busy entries → plural copy.
        assertEquals("2 tasks running", fgSpec!!.content)
        assertTrue("busy ongoing", fgSpec.ongoing)
        assertTrue("chronometer on busy", fgSpec.showChronometer)
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
    ) {
        fun start() = controller.start()
        fun bootstrapSucceed(id: ConnectionIdentity) {
            bootstrapRunner.enqueue(BootstrapResult.Success(id))
            // Make the identity visible to the controller's poller / markFailed paths.
            // ConnectionIdentityStore.bind requires the three fingerprints; we just
            // bind via reflection-free API using the same values as `id`.
            store.bind(
                serverGroupFp = id.serverGroupFp,
                normalizedWorkdir = id.normalizedWorkdir,
                endpointFp = id.endpointFp,
            )
        }
    }

    /**
     * Combined fake [StatusAggregator] + [cn.vectory.ocdroid.service.status.StatusAggregatorInput].
     * The dispatch tests treat the input as a no-op (refresh / markRequestFailed
     * just clear/set state); the bootstrap tests subclass to verify the input
     * calls.
     */
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

        /** D1 gate #1: stateAtNow tracks globalState (no separate clock domain in the fake). */
        override fun stateAtNow(): GlobalBusyState = _globalState.value

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
            // No-op for dispatch tests; bootstrap tests override.
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
        ) {
            // Surface failure as Unknown entries so globalState reflects keep-alive.
            val failed = snapshot.sessionsById.values.associate {
                SessionStatusKey(identity.serverGroupFp, it.directory, it.id) to SessionBusyStatus.Unknown
            }
            _statusByKey.value = failed
            _globalState.value = GlobalBusyState.Unknown
            _globalBusy.value = false
        }
    }

    private class RecordingShell : ServiceShell {
        val recorded = mutableListOf<String>()
        var lastStartForegroundSpec: NotificationSpec? = null
            private set

        override fun startForeground(spec: NotificationSpec) {
            recorded += "startForeground"
            lastStartForegroundSpec = spec
        }

        override fun updateNotification(spec: NotificationSpec) {
            recorded += "updateNotification"
        }

        override fun stopForeground() {
            recorded += "stopForeground"
        }

        override fun serviceStopSelf() {
            recorded += "serviceStopSelf"
        }

        override fun startPoller() {
            recorded += "startPoller"
        }

        override fun stopPoller() {
            recorded += "stopPoller"
        }

        override fun connectSse(identity: ConnectionIdentity) {
            recorded += "connectSse(epoch=${identity.epoch})"
        }

        override fun disconnectSse() {
            recorded += "disconnectSse"
        }
    }

    private class ScriptedBootstrapRunner : BootstrapRunner {
        private val queue = ArrayDeque<BootstrapResult>()
        fun enqueue(result: BootstrapResult) {
            queue.addLast(result)
        }

        override suspend fun runBootstrap(): BootstrapResult =
            queue.removeFirstOrNull() ?: BootstrapResult.Failed
    }
}
