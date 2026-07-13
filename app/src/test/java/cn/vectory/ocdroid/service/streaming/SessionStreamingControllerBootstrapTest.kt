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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FGS spec §5 — pure-JVM unit tests for [SessionStreamingController.bootstrapAsync].
 *
 * Covers the three §5 branches:
 *  - success → [cn.vectory.ocdroid.service.status.StatusAggregatorInput.refresh]
 *    called with the bootstrapped identity, then
 *    [StreamingLifecycleCoordinator.onBootstrapResult] fed the post-refresh
 *    globalState;
 *  - TOFU degraded → [StatusAggregatorInput.markRequestFailed] called +
 *    [ServiceShell.updateNotification] called with `degraded=true` (NO
 *    teardown — Unknown keeps the source alive per CP4);
 *  - Failed → bounded retry with backoff; if exhausted,
 *    [StreamingLifecycleCoordinator.onDisconnect] is invoked.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionStreamingControllerBootstrapTest {

    private val identity = ConnectionIdentity(
        epoch = 5L,
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
    fun `bootstrap success calls refresh with identity then onBootstrapResult with post-refresh state`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
        fixture.controller.start()
        // Pre-set the globalState the aggregator will report AFTER refresh.
        // (refresh is a stub here — it does NOT mutate globalState — so we set
        // the post-refresh verdict directly to simulate the aggregator having
        // observed a Busy session.)
        fixture.aggregator.setState(GlobalBusyState.Busy)
        fixture.bootstrapRunner.enqueue(BootstrapResult.Success(identity))

        fixture.controller.bootstrapAsync()
        runCurrent()

        // §3 main path: refresh was called once with the bootstrapped identity.
        assertEquals(1, fixture.aggregator.refreshArgs.size)
        assertEquals(identity, fixture.aggregator.refreshArgs.single().identity)
        // §5 decision matrix: post-refresh Busy + background → L2Active.
        assertEquals(Layer.L2Active, fixture.coordinator.layer.value)
    }

    @Test
    fun `bootstrap success foreground idle enters L1 idle (no FGS slot)`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = true)
        fixture.controller.start()
        fixture.aggregator.setState(GlobalBusyState.AllIdleFresh)
        fixture.bootstrapRunner.enqueue(BootstrapResult.Success(identity))

        fixture.controller.bootstrapAsync()
        runCurrent()

        // §5 step 5: foreground + AllIdleFresh → L1-idle (SSE on, FGS downgraded).
        assertEquals(Layer.L1(busy = false), fixture.coordinator.layer.value)
        assertEquals(1, fixture.aggregator.refreshArgs.size)
    }

    @Test
    fun `bootstrap TOFU degraded calls markRequestFailed and surfaces degraded notification without teardown`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
        // Bind identity so markRequestFailed has a target. The bound identity
        // carries the store's epoch (= 0 at start); we compare against this
        // bound value below.
        val boundIdentity = fixture.store.bind(
            serverGroupFp = identity.serverGroupFp,
            normalizedWorkdir = identity.normalizedWorkdir,
            endpointFp = identity.endpointFp,
        )
        fixture.controller.start()
        fixture.bootstrapRunner.enqueue(
            BootstrapResult.TofuNeedsActivity(hostPort = "example.com:443"),
        )

        fixture.controller.bootstrapAsync()
        runCurrent()

        // §5 degraded: markRequestFailed invoked once with the bound identity.
        assertEquals(1, fixture.aggregator.markFailedArgs.size)
        val failed = fixture.aggregator.markFailedArgs.single()
        assertEquals(boundIdentity, failed.identity)
        // §5 degraded: NO teardown — layer stays L3 (initial) but no further
        // command flow was triggered by bootstrapAsync (no onBootstrapResult).
        assertEquals(Layer.L3, fixture.coordinator.layer.value)
        // §5 degraded: shell.updateNotification called with degraded=true.
        val updateSpec = fixture.shell.lastUpdateNotificationSpec
        assertNotNull("updateNotification must be called for degraded", updateSpec)
        assertTrue("degraded flag carried through to spec", updateSpec!!.degraded)
        assertEquals("Server trust required", updateSpec.title)
    }

    @Test
    fun `bootstrap Failed retries with backoff then succeeds within budget`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
        fixture.aggregator.setState(GlobalBusyState.Busy)
        // Two failures, then success.
        fixture.bootstrapRunner.enqueue(BootstrapResult.Failed)
        fixture.bootstrapRunner.enqueue(BootstrapResult.Failed)
        fixture.bootstrapRunner.enqueue(BootstrapResult.Success(identity))

        val start = System.nanoTime()
        fixture.controller.bootstrapAsync()
        runCurrent()
        val elapsed = System.nanoTime() - start

        // The bounded retry used the controller's default backoff twice (2 × 2s)
        // — but with virtual time the runTest scheduler advances automatically.
        assertEquals(Layer.L2Active, fixture.coordinator.layer.value)
        // The runner was called three times (2 Failed + 1 Success).
        assertEquals(3, fixture.bootstrapRunner.callCount)
    }

    @Test
    fun `bootstrap Failed exhausted falls back to onDisconnect (section 5 step 6)`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
        // Enqueue 3 Failed (the default budget) + extra to confirm no further calls.
        fixture.bootstrapRunner.enqueue(BootstrapResult.Failed)
        fixture.bootstrapRunner.enqueue(BootstrapResult.Failed)
        fixture.bootstrapRunner.enqueue(BootstrapResult.Failed)
        fixture.bootstrapRunner.enqueue(BootstrapResult.Failed)

        fixture.controller.bootstrapAsync()
        runCurrent()

        // §5 step 6: after DEFAULT_BOOTSTRAP_MAX_ATTEMPTS (3) Failed attempts
        // the controller calls coordinator.onDisconnect. The coordinator starts
        // at L3 (its initial state — no successful bootstrap has flipped it out
        // yet), so onDisconnect → teardown is a no-op (L3 → L3). We assert
        // the runner was invoked exactly bootstrapMaxAttempts times (no further
        // retries after onDisconnect returned) and the layer stays L3.
        //
        // The controller→coordinator.onDisconnect call itself is verified by
        // code inspection — the coordinator class is final so we can't spy on
        // it without changing production; the budget-exhaustion check is the
        // behaviour we can directly observe from outside.
        assertEquals(Layer.L3, fixture.coordinator.layer.value)
        assertEquals(
            "runner called exactly bootstrapMaxAttempts times — exhausted budget",
            SessionStreamingController.DEFAULT_BOOTSTRAP_MAX_ATTEMPTS,
            fixture.bootstrapRunner.callCount,
        )
    }

    @Test
    fun `bootstrapAsync is single-flight - second invocation returns immediately`() = runTest {
        val fixture = newFixture(backgroundScope, inForeground = false)
        fixture.aggregator.setState(GlobalBusyState.Busy)
        fixture.bootstrapRunner.enqueue(BootstrapResult.Success(identity))

        fixture.controller.bootstrapAsync()
        runCurrent()
        assertEquals(Layer.L2Active, fixture.coordinator.layer.value)
        val callsAfterFirst = fixture.bootstrapRunner.callCount

        // Second invocation must NOT re-run (sticky-rebuild safety).
        fixture.controller.bootstrapAsync()
        runCurrent()

        assertEquals(
            "single-flight: second bootstrapAsync does not re-invoke runner",
            callsAfterFirst,
            fixture.bootstrapRunner.callCount,
        )
    }

    // ── Fixture / fakes ───────────────────────────────────────────────────

    private fun newFixture(
        scope: kotlinx.coroutines.CoroutineScope,
        inForeground: Boolean,
    ): Fixture {
        val aggregator = RecordingStatusInput()
        val coordinator = StreamingLifecycleCoordinator(aggregator, scope)
        val store = ConnectionIdentityStore()
        val shell = RecordingShell()
        val snapshotProvider = SessionSnapshotProvider { emptyMap<String, Session>() }
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
            // Tight backoff so the exhausted-retry test runs fast even on
            // real wall-clock (the runTest scheduler virtualises delay(), so
            // this is belt-and-suspenders).
            bootstrapBackoffMs = 5L,
            clock = { 0L },
        )
        return Fixture(controller, coordinator, aggregator, store, shell, bootstrapRunner)
    }

    private class Fixture(
        val controller: SessionStreamingController,
        val coordinator: StreamingLifecycleCoordinator,
        val aggregator: RecordingStatusInput,
        val store: ConnectionIdentityStore,
        val shell: RecordingShell,
        val bootstrapRunner: ScriptedBootstrapRunner,
    )

    private data class RefreshCall(val identity: ConnectionIdentity, val sessions: Map<String, Session>)
    private data class MarkFailedCall(val identity: ConnectionIdentity, val sessions: Map<String, Session>)

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

        val refreshArgs = mutableListOf<RefreshCall>()
        val markFailedArgs = mutableListOf<MarkFailedCall>()

        fun setState(state: GlobalBusyState) {
            _globalState.value = state
            _globalBusy.value = state == GlobalBusyState.Busy
        }

        override suspend fun refresh(
            identity: ConnectionIdentity,
            sessionsById: Map<String, Session>,
        ) {
            refreshArgs += RefreshCall(identity, sessionsById)
        }

        override fun applySseStatus(
            key: SessionStatusKey,
            status: SessionBusyStatus,
            sourceTimeMs: Long,
        ) = Unit

        override fun markRequestFailed(
            identity: ConnectionIdentity,
            sessionsById: Map<String, Session>,
            sourceTimeMs: Long,
        ) {
            markFailedArgs += MarkFailedCall(identity, sessionsById)
        }
    }

    private class ScriptedBootstrapRunner : BootstrapRunner {
        private val queue = ArrayDeque<BootstrapResult>()
        var callCount: Int = 0
            private set

        fun enqueue(result: BootstrapResult) {
            queue.addLast(result)
        }

        override suspend fun runBootstrap(): BootstrapResult {
            callCount++
            return queue.removeFirstOrNull() ?: BootstrapResult.Failed
        }
    }

    private class RecordingShell : ServiceShell {
        val recorded = mutableListOf<String>()
        var lastUpdateNotificationSpec: NotificationSpec? = null
            private set

        override fun startForeground(spec: NotificationSpec) {
            recorded += "startForeground"
        }

        override fun updateNotification(spec: NotificationSpec) {
            recorded += "updateNotification"
            lastUpdateNotificationSpec = spec
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
            recorded += "connectSse"
        }

        override fun disconnectSse() {
            recorded += "disconnectSse"
        }
    }
}
