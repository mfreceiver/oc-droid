package cn.vectory.ocdroid.service.streaming

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FGS spec §4.4 / §5 / §6 — pure-JVM dispatch tests for
 * [SessionStreamingController] + the real [StreamingLifecycleCoordinator].
 *
 * Verifies the §4.4 unified handoff ordering reaches the [ServiceShell] in
 * the exact order the coordinator emits (new source active BEFORE closing
 * old), via a recording fake shell — no Robolectric. D2 gate #4: the shell's
 * [ServiceShell.connectSse] / [ServiceShell.startPoller] are suspend
 * returning [SourceActivation]; the fake scripts a canned activation so the
 * coordinator's commit phase runs.
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
    fun `bootstrap background busy emits connectSse then stopPoller in order`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
        fixture.start()
        // bootstrap background + busy → coordinator emits StartSse; the
        // shell's suspend connectSse returns Ready(Busy); the commit emits
        // StopPoller.
        fixture.aggregator.setState(GlobalBusyState.Busy)
        fixture.bootstrapSucceed(identity)
        fixture.shell.nextSseActivation = SourceActivation.Ready
        fixture.controller.bootstrapAsync()
        runCurrent()

        assertEquals(Layer.L2Active, fixture.coordinator.layer.value)
        // §4.4 ordering: connectSse (new source) BEFORE stopPoller (old retired).
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
        fixture.aggregator.setState(GlobalBusyState.Busy)
        fixture.bootstrapSucceed(identity)
        fixture.shell.nextSseActivation = SourceActivation.Ready
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

        // Pre-ack: only startPoller recorded; poller activation pending.
        val startPollerIdxPreAck = fixture.shell.recorded.indexOf("startPoller")
        assertTrue("startPoller recorded at debounce fire", startPollerIdxPreAck >= 0)
        // The fake shell's startPoller returns nextPollerActivation; the
        // default is Ready(AllIdleFresh), so the commit fires synchronously.

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
    fun `D2 gate #4 - poller reports Ready BEFORE disconnectSse (final poll AllIdleFresh)`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
        fixture.start()
        fixture.aggregator.setState(GlobalBusyState.Busy)
        fixture.bootstrapSucceed(identity)
        fixture.shell.nextSseActivation = SourceActivation.Ready
        fixture.controller.bootstrapAsync()
        runCurrent()
        // Drive to L2Idle: the poller activation returns Ready(AllIdleFresh).
        fixture.shell.nextPollerActivation = SourceActivation.Ready
        fixture.aggregator.setState(GlobalBusyState.AllIdleFresh)
        runCurrent()
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
        runCurrent()

        // The poller activation (startPoller) recorded BEFORE disconnectSse.
        val startPollerIdx = fixture.shell.recorded.indexOf("startPoller")
        val disconnectSseIdx = fixture.shell.recorded.indexOf("disconnectSse")
        assertTrue("startPoller recorded", startPollerIdx >= 0)
        assertTrue("disconnectSse recorded", disconnectSseIdx >= 0)
        assertTrue(
            "poller Ready BEFORE disconnectSse (§4.4 + D2 gate #4)",
            startPollerIdx < disconnectSseIdx,
        )
    }

    @Test
    fun `D2 gate #4 - L2Active to L2Idle final poll Busy - NO disconnectSse`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
        fixture.start()
        fixture.aggregator.setState(GlobalBusyState.Busy)
        fixture.bootstrapSucceed(identity)
        fixture.shell.nextSseActivation = SourceActivation.Ready
        fixture.controller.bootstrapAsync()
        runCurrent()
        fixture.shell.recorded.clear()

        // The poller's immediate first poll returns Busy — stay L2Active.
        // D4-B M3: the commit reads stateAtNow() AFTER the poller's first-
        // poll refresh. globalState stays AllIdleFresh (so the debounce
        // fires); nextRefreshState=Busy simulates the poll discovering Busy
        // (the shell's startPoller calls refresh → updates the aggregator
        // → the commit reads Busy → StopPoller + stay L2Active).
        fixture.shell.nextPollerActivation = SourceActivation.Ready
        fixture.aggregator.nextRefreshState = GlobalBusyState.Busy
        fixture.aggregator.setState(GlobalBusyState.AllIdleFresh)
        runCurrent()
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
        runCurrent()

        assertEquals(Layer.L2Active, fixture.coordinator.layer.value)
        assertTrue(
            "startPoller recorded (the activation started)",
            fixture.shell.recorded.any { it == "startPoller" },
        )
        assertTrue(
            "stopPoller recorded (cancel the non-idle new poller)",
            fixture.shell.recorded.any { it == "stopPoller" },
        )
        assertFalse(
            "disconnectSse MUST NOT be recorded when the final poll reports Busy",
            fixture.shell.recorded.any { it == "disconnectSse" },
        )
    }

    @Test
    fun `L2Idle finds busy emits connectSse then stopPoller in place (groker-R1)`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
        fixture.start()
        fixture.aggregator.setState(GlobalBusyState.Busy)
        fixture.bootstrapSucceed(identity)
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

        // groker-R1: poller finds busy → in-place L2Idle → L2Active.
        fixture.shell.nextSseActivation = SourceActivation.Ready
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
    fun `D2 gate #4 - SSE Rejected leaves poller running - no stopPoller`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
        fixture.start()
        fixture.aggregator.setState(GlobalBusyState.Busy)
        fixture.bootstrapSucceed(identity)
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

        // L2Idle → L2Active attempt; the SSE activation is Rejected (TOFU/stale).
        fixture.shell.nextSseActivation = SourceActivation.Rejected.TofuPending
        fixture.aggregator.setState(GlobalBusyState.Busy)
        runCurrent()

        assertEquals(
            "Rejected SSE activation leaves layer in L2Idle",
            Layer.L2Idle,
            fixture.coordinator.layer.value,
        )
        assertTrue(
            "connectSse recorded (the activation request was dispatched)",
            fixture.shell.recorded.any { it.startsWith("connectSse") },
        )
        assertFalse(
            "stopPoller MUST NOT be recorded when SSE activation is Rejected",
            fixture.shell.recorded.any { it == "stopPoller" },
        )
    }

    @Test
    fun `L3 teardown emits stopForeground then serviceStopSelf`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
        fixture.start()
        fixture.aggregator.setState(GlobalBusyState.Busy)
        fixture.bootstrapSucceed(identity)
        fixture.shell.nextSseActivation = SourceActivation.Ready
        fixture.controller.bootstrapAsync()
        runCurrent()
        assertEquals(Layer.L2Active, fixture.coordinator.layer.value)
        fixture.shell.recorded.clear()

        // teardown from L2Active: StartPoller (handoff) → ack → StopSse +
        // StopForeground + StopSelf.
        fixture.shell.nextPollerActivation = SourceActivation.Ready
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
        fixture.aggregator.setStatusByKey(
            mapOf(
                SessionStatusKey("group-fp", "/work/dir", "s1") to SessionBusyStatus.Busy,
                SessionStatusKey("group-fp", "/work/dir", "s2") to SessionBusyStatus.Retry,
            ),
        )
        fixture.bootstrapSucceed(identity)
        fixture.shell.nextSseActivation = SourceActivation.Ready
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
        val shell = RecordingShell(onPollRefresh = { id, snap -> aggregator.refresh(id, snap) })
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
            store.bind(
                serverGroupFp = id.serverGroupFp,
                normalizedWorkdir = id.normalizedWorkdir,
                endpointFp = id.endpointFp,
            )
        }
    }

    /**
     * Combined fake [StatusAggregator] + [cn.vectory.ocdroid.service.status.StatusAggregatorInput].
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

        /**
         * D4-B M3: optional override for [stateAtNow] — when non-null, returned
         * instead of [_globalState.value]. Tests that need the debounce to fire
         * on AllIdleFresh (globalState) while the poller's final-snapshot
         * commit reads a DIFFERENT verdict (Busy/Unknown) set this before the
         * commit. Mirrors the production split between the cached globalState
         * and the time-correct stateAtNow.
         */
        var stateAtNowOverride: GlobalBusyState? = null

        /**
         * D4-B M3: when non-null, [refresh] pushes this into [_globalState]
         * (simulating the production poller's first-poll status refresh that
         * updates the aggregator BEFORE the handoff commit reads
         * [stateAtNow]). The dispatch test's fake shell invokes refresh from
         * [RecordingShell.startPoller] to mirror production.
         */
        var nextRefreshState: GlobalBusyState? = null

        override fun stateAtNow(): GlobalBusyState = stateAtNowOverride ?: _globalState.value

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
            nextRefreshState?.let { setState(it) }
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
            val failed = snapshot.sessionsById.values.associate {
                SessionStatusKey(identity.serverGroupFp, it.directory, it.id) to SessionBusyStatus.Unknown
            }
            _statusByKey.value = failed
            _globalState.value = GlobalBusyState.Unknown
            _globalBusy.value = false
        }
    }

    /**
     * D2: recording shell with suspend connectSse/startPoller. Each call
     * records + returns the scripted [nextSseActivation] / [nextPollerActivation]
     * (default: Ready with the current aggregator state).
     */
    private class RecordingShell(
        /**
         * D4-B M3: invoked from [startPoller] to simulate the production
         * poller's first-poll status refresh (which updates the aggregator
         * BEFORE the handoff commit reads stateAtNow). The fixture wires this
         * to the fake aggregator's refresh.
         */
        private val onPollRefresh: (suspend (ConnectionIdentity, StatusSnapshot) -> Unit)? = null,
    ) : ServiceShell {
        val recorded = mutableListOf<String>()
        var lastStartForegroundSpec: NotificationSpec? = null
            private set

        var nextSseActivation: SourceActivation = SourceActivation.Ready
        var nextPollerActivation: SourceActivation = SourceActivation.Ready

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

        override suspend fun startPoller(
            identity: ConnectionIdentity,
            snapshot: StatusSnapshot,
        ): SourceActivation {
            recorded += "startPoller"
            // D4-B M3: simulate the production first-poll refresh so the
            // handoff commit reads the post-poll aggregator state.
            onPollRefresh?.invoke(identity, snapshot)
            return nextPollerActivation
        }

        override fun stopPoller() {
            recorded += "stopPoller"
        }

        override suspend fun ensurePoller(
            identity: ConnectionIdentity,
            snapshot: StatusSnapshot,
        ): SourceActivation {
            recorded += "ensurePoller(epoch=${identity.epoch})"
            onPollRefresh?.invoke(identity, snapshot)
            return nextPollerActivation
        }

        override suspend fun connectSse(identity: ConnectionIdentity): SourceActivation {
            recorded += "connectSse(epoch=${identity.epoch})"
            return nextSseActivation
        }

        override suspend fun disconnectSse() {
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
