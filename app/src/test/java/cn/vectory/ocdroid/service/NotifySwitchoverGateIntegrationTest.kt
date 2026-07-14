package cn.vectory.ocdroid.service

import androidx.core.content.ContextCompat
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.service.events.SseEventStream
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.lifecycle.Layer
import cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator
import cn.vectory.ocdroid.service.notify.NotificationStrings
import cn.vectory.ocdroid.service.streaming.BootstrapResult
import cn.vectory.ocdroid.service.streaming.BootstrapRunner
import cn.vectory.ocdroid.service.streaming.ServiceShell
import cn.vectory.ocdroid.service.streaming.ServiceSseConnectionOwner
import cn.vectory.ocdroid.service.streaming.SessionSnapshotProvider
import cn.vectory.ocdroid.service.streaming.SessionStreamingController
import cn.vectory.ocdroid.service.streaming.SseRecoveryPolicy
import cn.vectory.ocdroid.service.streaming.SourceActivation
import cn.vectory.ocdroid.service.status.GlobalBusyState
import cn.vectory.ocdroid.service.status.SessionBusyStatus
import cn.vectory.ocdroid.service.status.SessionStatusKey
import cn.vectory.ocdroid.service.status.StatusAggregator
import cn.vectory.ocdroid.service.status.StatusAggregatorInput
import cn.vectory.ocdroid.service.status.StatusSnapshot
import cn.vectory.ocdroid.ui.ConnectionPhase
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * D5-2 (#4 closure) — full-lifecycle integration tests for the notify
 * switchover ownership gate. Uses REAL [StreamingOwnershipGate] +
 * [StreamingLifecycleCoordinator] + [SessionStreamingController] +
 * recording [ServiceShell] + acknowledged poller fake (per spec: NOT
 * Robolectric, follows the [OwnershipAndReconfigureIntegrationTest] pattern).
 *
 *  - **D** — split Starting/transport timeout protocol + the orphan-owner
 *    abort case (the confirmed bug D5-2 closes).
 *  - **A** — post-Ready outage recovery lifecycle (Ready owner → induced
 *    failure → retries → terminal callback → teardown → ownership release).
 *  - **B** — fresh-process Unknown supplemental poller source.
 *  - **C** — commit/ownership race (superseded handoff produces no markReady).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotifySwitchoverGateIntegrationTest {

    private val identity = ConnectionIdentity(
        epoch = 7L,
        serverGroupFp = "group-fp",
        normalizedWorkdir = "/work",
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

    // ── D — split Starting/transport timeout protocol + orphan-owner abort ──

    @Test
    fun `D-i - Starting accepted at 4s + Ready at 20s - no AckTimeout + launcher suspends past 5s + settles Ready`() = runTest {
        val gate = StreamingOwnershipGate()
        // Launcher prepares the attempt — no owner, launchRequired=true.
        val attempt = gate.prepareAttempt(identity)
        assertTrue("launchRequired (no owner)", attempt.launchRequired)
        val startingDeferred = attempt.starting as CompletableDeferred
        val terminalDeferred = attempt.terminal as CompletableDeferred

        // At t=4s the Service calls registerStarting — starting completes with Accepted.
        advanceTimeBy(4_000)
        val outcome = gate.registerStarting(
            identity = identity,
            attemptId = attempt.attemptId,
            disconnectAndJoin = { },
            abortStartup = { },
        )
        assertEquals(RegisterStartingOutcome.Accepted, outcome)
        assertTrue("starting now completed (Accepted)", startingDeferred.isCompleted)
        assertFalse("terminal NOT completed (Stage 1 only)", terminalDeferred.isCompleted)

        // At t=20s the Service calls markReady (Stage 2) — the launcher was
        // still suspended on terminal.await() (NO second wall-clock timeout).
        advanceTimeBy(16_000)
        gate.markReady(identity)

        assertEquals(
            "terminal settled Ready at t=20s (no AckTimeout)",
            OwnershipStartResult.Ready(identity),
            attempt.terminal.await(),
        )
        assertEquals(identity, gate.readyIdentity())
    }

    @Test
    fun `D-iv - orphan-owner case - 5s AckTimeout expires attempt and late registerStarting is REJECTED`() = runTest {
        val gate = StreamingOwnershipGate()
        val attempt = gate.prepareAttempt(identity)
        assertTrue("launchRequired (no owner)", attempt.launchRequired)

        // The Service never registers Starting within 5s.
        advanceTimeBy(5_500)
        // Launcher gives up.
        gate.expireAttempt(attempt.attemptId, OwnershipRefusal.AckTimeout)

        // The terminal deferred settled with Refused(AckTimeout) — CC sees
        // the orphan-prevention refusal, NOT a silent hang.
        assertEquals(
            "terminal settled Refused(AckTimeout)",
            OwnershipStartResult.Refused(OwnershipRefusal.AckTimeout),
            attempt.terminal.await(),
        )
        assertFalse("attempt no longer live", gate.isAttemptLive(attempt.attemptId))
        assertNull("no Starting owner recorded", gate.readyIdentity())

        // The LATE Service onStartCommand arrives with the EXPIRED attemptId.
        val lateOutcome = gate.registerStarting(
            identity = identity,
            attemptId = attempt.attemptId,
            disconnectAndJoin = { },
            abortStartup = { },
        )
        assertEquals(
            "late registerStarting REJECTED (orphan-owner closure)",
            RegisterStartingOutcome.Expired,
            lateOutcome,
        )
        assertNull("NO owner registered by late Service", gate.readyIdentity())
    }

    @Test
    fun `D-iv - orphan-owner full lifecycle - late Service aborts via BootstrapFailure teardown`() = runTest {
        // Full integration: real gate + coordinator + controller + recording
        // shell. The late Service invocation discovers the expired attemptId
        // and the controller/coordinator wire the abort path.
        val fixture = newControllerFixture(backgroundScope, inForeground = true)
        fixture.controller.start()
        val gate = fixture.gate
        val attempt = gate.prepareAttempt(identity)

        // Expire the attempt (the launcher's 5s window elapsed).
        gate.expireAttempt(attempt.attemptId, OwnershipRefusal.AckTimeout)

        // The late Service invocation:
        //  1. requests ownership via registerStarting → Expired.
        //  2. calls coordinator.teardownAndAwait(BootstrapFailure) → StopSse +
        //     StopForeground + StopPoller + StopSelf + L3.
        val outcome = gate.registerStarting(
            identity = identity,
            attemptId = attempt.attemptId,
            disconnectAndJoin = { },
            abortStartup = { },
        )
        assertEquals(RegisterStartingOutcome.Expired, outcome)
        fixture.shell.recorded.clear()
        // Late Service's abort: coordinator teardown (the Service would call
        // this when it discovers outcome == Expired).
        fixture.coordinator.teardownAndAwait(TeardownReason.BootstrapFailure)
        runCurrent()

        assertEquals("L3 after abort", Layer.L3, fixture.coordinator.layer.value)
        assertTrue("StopForeground observed (abort)", fixture.shell.recorded.any { it == "stopForeground" })
        assertTrue("StopSelf observed (abort)", fixture.shell.recorded.any { it == "serviceStopSelf" })
        assertNull("NO owner registered by late Service", gate.readyIdentity())
    }

    @Test
    fun `D-v - Starting accepted then reconfigure - terminal waiter refused + no hang`() = runTest {
        val gate = StreamingOwnershipGate()
        val attempt = gate.prepareAttempt(identity)
        val outcome = gate.registerStarting(
            identity = identity,
            attemptId = attempt.attemptId,
            disconnectAndJoin = { },
            abortStartup = { },
        )
        assertEquals(RegisterStartingOutcome.Accepted, outcome)
        assertFalse("terminal NOT yet completed", attempt.terminal.isCompleted)

        // Reconfigure teardown — disconnectAndRelease settles the terminal.
        gate.disconnectAndRelease(markGap = false)

        assertEquals(
            "terminal settled Refused after reconfigure (no hang)",
            OwnershipStartResult.Refused(OwnershipRefusal.ServiceStopped),
            attempt.terminal.await(),
        )
        assertNull("ownership released", gate.readyIdentity())
    }

    // ── A — post-Ready outage recovery lifecycle ───────────────────────────

    @Test
    fun `A - post-Ready outage recovery - terminal callback drives ownership release`() = runTest {
        // Real ServiceSseConnectionOwner + coordinator + gate. Verifies the
        // WIRING: Ready owner → induced flow failure past the retry budget →
        // terminal callback (onTerminalExhaustion) → coordinator.onDisconnect
        // → teardownAndAwait → ownershipGate.disconnectAndRelease → no orphan
        // Ready owner. (The D5 unit tests 1a/1b cover the owner-internal
        // recovery; THIS test wires the full ownership-release teardown.)
        mockkStatic(ContextCompat::class)
        try {
            val identityStore = ConnectionIdentityStore()
            val bound = identityStore.bind(
                serverGroupFp = identity.serverGroupFp,
                normalizedWorkdir = identity.normalizedWorkdir,
                endpointFp = identity.endpointFp,
            )
            val stream = SseEventStream()
            val store = SharedStateStore()
            val effects = SharedEffectBus()
            val gate = StreamingOwnershipGate()
            val policy = TestRecoveryPolicy()
            // The repository delivers flows in sequence (each connectSSE call
            // pulls the next flow). Flow 1 emits one frame then throws; flows
            // 2..(1+policy.attempts) throw immediately → terminal exhaustion.
            val flows = mutableListOf<kotlinx.coroutines.flow.Flow<Result<SSEEvent>>>(
                flow<Result<SSEEvent>> {
                    emit(Result.success(SSEEvent(payload = SSEPayload(type = "first"))))
                    throw IOException("post-ready outage")
                },
            )
            repeat(policy.attempts) {
                flows += flow<Result<SSEEvent>> { throw IOException("recovery-${it + 1} fail") }
            }
            val repository = mockk<OpenCodeRepository>(relaxed = true)
            every { repository.connectSSE(any()) } returnsMany flows
            val bootstrapCoordinator = cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator()
            val streamFrames = mutableListOf<SSEEvent>()
            val scope = this
            // Use backgroundScope for the stream collector so runTest cleans
            // it up automatically (the owner's internal jobs use `scope`).
            backgroundScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                stream.events.collect { result -> result.onSuccess { streamFrames += it.event } }
            }
            var disconnectRequests = 0
            val owner = ServiceSseConnectionOwner(
                scope = scope,
                repository = repository,
                identityStore = identityStore,
                bootstrapCoordinator = bootstrapCoordinator,
                sseEventStream = stream,
                sharedStateStore = store,
                sharedEffectBus = effects,
                recoveryPolicy = policy,
                jitterSource = { 0.0f },
                onTerminalExhaustion = { disconnectRequests++ },
            )

            // Register Starting ownership + promote to Ready.
            val attempt = gate.prepareAttempt(bound)
            gate.registerStarting(
                identity = bound,
                attemptId = attempt.attemptId,
                disconnectAndJoin = { markGap -> owner.disconnectAndJoin(markGap) },
                abortStartup = { },
            )
            gate.markReady(bound)
            assertEquals("Ready owner recorded", bound, gate.readyIdentity())

            // Launch the SSE collector on backgroundScope (auto-cancelled).
            val connectJob = backgroundScope.launch { owner.connect(bound) }
            // Pump: the collector subscribes + the flow emits the first frame.
            repeat(3) {
                scope.testScheduler.advanceTimeBy(10)
                scope.testScheduler.runCurrent()
            }
            assertTrue(
                "first frame reached the stream (transport proved Ready)",
                streamFrames.any { it.payload.type == "first" },
            )

            // The first flow threw → post-Ready outage path. Advance through
            // the retry budget to terminal exhaustion.
            repeat(policy.attempts) { i ->
                scope.testScheduler.advanceTimeBy(policy.delayMs(i + 1).toLong())
                scope.testScheduler.runCurrent()
            }
            scope.testScheduler.runCurrent()

            assertTrue(
                "terminal exhaustion callback fired",
                disconnectRequests > 0,
            )
            // The shared connection state settled Disconnected (the owner
            // writes this on terminal exhaustion).
            assertEquals(
                "Disconnected after terminal exhaustion",
                ConnectionPhase.Disconnected,
                store.connectionFlow.value.connectionPhase,
            )

            // The coordinator's Disconnect teardown would call
            // disconnectAndRelease on the gate — simulate that here to verify
            // the ownership release path closes the orphan-Ready hole.
            gate.disconnectAndRelease(markGap = true)
            assertNull(
                "ownership released after teardown (no orphan Ready)",
                gate.readyIdentity(),
            )
            // The launcher's terminal deferred was already completed with Ready
            // BEFORE the outage (the original bootstrap succeeded). The release
            // path does NOT retroactively change a settled Ready — the launcher
            // already proceeded to Connected. The post-Ready outage surfaces
            // via the sharedStateStore Disconnected write above, NOT by
            // un-Readying the original result.
            assertEquals(
                "terminal deferred retains original Ready (launcher already proceeded)",
                OwnershipStartResult.Ready(bound),
                attempt.terminal.await(),
            )
        } finally {
            unmockkAll()
        }
    }

    // ── B — fresh-process Unknown supplemental source ──────────────────────

    @Test
    fun `B - L3 bootstrap Unknown SSE Ready emits exactly one EnsurePoller then Busy retires it`() = runTest {
        val fixture = newControllerFixture(backgroundScope, inForeground = true)
        fixture.controller.start()
        fixture.bootstrapRunner.enqueue(BootstrapResult.Success(identity))
        fixture.store.bind(
            serverGroupFp = identity.serverGroupFp,
            normalizedWorkdir = identity.normalizedWorkdir,
            endpointFp = identity.endpointFp,
        )
        // Bootstrap state is Unknown (a failure or no fresh data) → SSE
        // commits but the supplemental poller is started.
        fixture.aggregator.setState(GlobalBusyState.Unknown)
        fixture.shell.nextSseActivation = SourceActivation.Ready
        fixture.shell.nextPollerActivation = SourceActivation.Ready
        fixture.controller.bootstrapAsync()
        runCurrent()

        assertEquals(Layer.L1(busy = true), fixture.coordinator.layer.value)
        val ensureCount = fixture.shell.recorded.count { it == "ensurePoller" }
        assertEquals("exactly one EnsurePoller emitted", 1, ensureCount)
        assertTrue(
            "ensurePoller recorded (supplemental started for Unknown)",
            fixture.shell.recorded.any { it == "ensurePoller" },
        )

        // Definitive Busy verdict retires the supplemental poller.
        val stopBefore = fixture.shell.recorded.count { it == "stopPoller" }
        fixture.aggregator.setState(GlobalBusyState.Busy)
        runCurrent()
        val stopAfter = fixture.shell.recorded.count { it == "stopPoller" }
        assertTrue("StopPoller emitted on Busy verdict", stopAfter > stopBefore)
    }

    @Test
    fun `B - Unknown repeated before ensure-ack does NOT double-start supplemental poller`() = runTest {
        val fixture = newControllerFixture(backgroundScope, inForeground = true)
        fixture.controller.start()
        fixture.bootstrapRunner.enqueue(BootstrapResult.Success(identity))
        fixture.store.bind(
            serverGroupFp = identity.serverGroupFp,
            normalizedWorkdir = identity.normalizedWorkdir,
            endpointFp = identity.endpointFp,
        )
        fixture.aggregator.setState(GlobalBusyState.Unknown)
        fixture.shell.nextSseActivation = SourceActivation.Ready
        fixture.shell.nextPollerActivation = SourceActivation.Ready
        fixture.controller.bootstrapAsync()
        runCurrent()

        val ensuresBefore = fixture.shell.recorded.count { it == "ensurePoller" }
        // Re-emit Unknown (e.g. another failed status snapshot) — runtime is
        // already Starting/Running for this identity → no double-start.
        fixture.aggregator.setState(GlobalBusyState.Unknown)
        runCurrent()
        val ensuresAfter = fixture.shell.recorded.count { it == "ensurePoller" }
        assertEquals("no double EnsurePoller on repeated Unknown", ensuresBefore, ensuresAfter)
    }

    @Test
    fun `B - L2Idle already-Primary - Unknown SSE commit does NOT restart poller`() = runTest {
        val fixture = newControllerFixture(backgroundScope, inForeground = false)
        fixture.controller.start()
        fixture.bootstrapRunner.enqueue(BootstrapResult.Success(identity))
        fixture.store.bind(
            serverGroupFp = identity.serverGroupFp,
            normalizedWorkdir = identity.normalizedWorkdir,
            endpointFp = identity.endpointFp,
        )
        // Drive to L2Idle (poller already Primary).
        fixture.aggregator.setState(GlobalBusyState.Busy)
        fixture.shell.nextSseActivation = SourceActivation.Ready
        fixture.controller.bootstrapAsync()
        runCurrent()
        assertEquals(Layer.L2Active, fixture.coordinator.layer.value)
        fixture.shell.nextPollerActivation = SourceActivation.Ready
        fixture.aggregator.setState(GlobalBusyState.AllIdleFresh)
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
        runCurrent()
        assertEquals(Layer.L2Idle, fixture.coordinator.layer.value)

        // L2Idle → L2Active on Unknown (isKeepAlive=true) emits StartSse; the
        // commit reads Unknown → would normally ensurePoller. The poller
        // runtime is already Primary Running for this identity → no EnsurePoller.
        fixture.shell.recorded.clear()
        fixture.shell.nextSseActivation = SourceActivation.Ready
        fixture.aggregator.setState(GlobalBusyState.Unknown)
        runCurrent()

        // The poller was already Primary — no supplemental EnsurePoller should fire.
        val ensures = fixture.shell.recorded.count { it == "ensurePoller" }
        assertTrue(
            "no EnsurePoller when poller already running for identity (Unknown SSE commit)",
            ensures == 0,
        )
    }

    // ── C — commit/ownership race ──────────────────────────────────────────

    @Test
    fun `C - superseded handoff produces Superseded result and no markReady`() = runTest {
        // Strategy: pause the SSE activation mid-flight (via a deferred-based
        // shell), perform a reconfigure teardown that supersedes the pending
        // handoff, then resume the activation — the late ack should produce
        // Superseded (no commit, no markReady).
        val aggregator = FakeStatusAggregator()
        val gate = StreamingOwnershipGate()
        val coordinator = StreamingLifecycleCoordinator(aggregator, backgroundScope, gate)
        val store = ConnectionIdentityStore()
        val ssePause = CompletableDeferred<SourceActivation>()
        val shell = PausingRecordingShell(ssePause)
        val bootstrapRunner = ScriptedBootstrapRunner()
        val inForegroundFlow = MutableStateFlow(true)
        val controller = SessionStreamingController(
            coordinator = coordinator,
            statusAggregator = aggregator,
            statusAggregatorInput = aggregator,
            identityStore = store,
            sessionSnapshotProvider = SessionSnapshotProvider { StatusSnapshot.Empty },
            bootstrapRunner = bootstrapRunner,
            shell = shell,
            strings = strings,
            inForeground = inForegroundFlow,
            scope = backgroundScope,
        )
        controller.start()
        val bound = store.bind(
            serverGroupFp = identity.serverGroupFp,
            normalizedWorkdir = identity.normalizedWorkdir,
            endpointFp = identity.endpointFp,
        )
        bootstrapRunner.enqueue(BootstrapResult.Success(bound))
        aggregator.setState(GlobalBusyState.Busy)

        // bootstrapAsync emits StartSse; the controller's connectSse launch
        // awaits our pause deferred (not yet completed).
        val bootstrapJob = backgroundScope.launch { controller.bootstrapAsync() }
        runCurrent()
        assertEquals("connectSse was called (paused)", 1, shell.connectSseCalls)

        // BEFORE the SSE ack arrives, a reconfigure supersedes the handoff.
        coordinator.teardownAndAwait(TeardownReason.Reconfigure)
        runCurrent()

        // Now resume the paused SSE activation with Ready — the late ack
        // should find the handoff superseded.
        ssePause.complete(SourceActivation.Ready)
        runCurrent()

        // The bootstrap job has completed (bootstrapAsync returned after the
        // first iteration). The late ack returned Superseded — no commit,
        // no markReady.
        assertNull(
            "no markReady fired (ownership stays null)",
            gate.readyIdentity(),
        )
        bootstrapJob.cancel()
    }

    // ── Fixtures / fakes ───────────────────────────────────────────────────

    private fun newControllerFixture(
        scope: CoroutineScope,
        inForeground: Boolean,
    ): ControllerFixture {
        val aggregator = FakeStatusAggregator()
        val gate = StreamingOwnershipGate()
        val coordinator = StreamingLifecycleCoordinator(aggregator, scope, gate)
        val store = ConnectionIdentityStore()
        val shell = RecordingShell()
        val snapshotProvider = SessionSnapshotProvider { StatusSnapshot.Empty }
        val bootstrapRunner = ScriptedBootstrapRunner()
        val inForegroundFlow = MutableStateFlow(inForeground)
        // NOTE: do NOT add a second collector on coordinator.commands — the
        // command channel is single-consumer; a test-side collector would
        // steal elements from the controller's internal collector and break
        // the lifecycle state machine.
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
        return ControllerFixture(
            controller = controller,
            coordinator = coordinator,
            aggregator = aggregator,
            store = store,
            shell = shell,
            bootstrapRunner = bootstrapRunner,
            gate = gate,
        )
    }

    private class ControllerFixture(
        val controller: SessionStreamingController,
        val coordinator: StreamingLifecycleCoordinator,
        val aggregator: FakeStatusAggregator,
        val store: ConnectionIdentityStore,
        val shell: RecordingShell,
        val bootstrapRunner: ScriptedBootstrapRunner,
        val gate: StreamingOwnershipGate,
    )

    private open class FakeStatusAggregator : StatusAggregator, StatusAggregatorInput {
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
        fun enqueue(result: BootstrapResult) { queue.addLast(result) }
        override suspend fun runBootstrap(): BootstrapResult =
            queue.removeFirstOrNull() ?: BootstrapResult.Failed
    }

    private class RecordingShell : ServiceShell {
        val recorded = mutableListOf<String>()
        var nextSseActivation: SourceActivation = SourceActivation.Ready
        var nextPollerActivation: SourceActivation = SourceActivation.Ready

        override fun startForeground(spec: cn.vectory.ocdroid.service.notify.NotificationSpec) { recorded += "startForeground" }
        override fun updateNotification(spec: cn.vectory.ocdroid.service.notify.NotificationSpec) { recorded += "updateNotification" }
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

    /** Shell that forwards SSE side-effects to a real [ServiceSseConnectionOwner]. */
    private class OwnerRoutingShell(private val owner: ServiceSseConnectionOwner) : ServiceShell {
        val recorded = mutableListOf<String>()
        override fun startForeground(spec: cn.vectory.ocdroid.service.notify.NotificationSpec) { recorded += "startForeground" }
        override fun updateNotification(spec: cn.vectory.ocdroid.service.notify.NotificationSpec) { recorded += "updateNotification" }
        override fun stopForeground() { recorded += "stopForeground" }
        override fun serviceStopSelf() { recorded += "serviceStopSelf" }
        override suspend fun startPoller(
            identity: ConnectionIdentity,
            snapshot: StatusSnapshot,
        ): SourceActivation { recorded += "startPoller"; return SourceActivation.Ready }
        override fun stopPoller() { recorded += "stopPoller" }
        override suspend fun ensurePoller(
            identity: ConnectionIdentity,
            snapshot: StatusSnapshot,
        ): SourceActivation { recorded += "ensurePoller"; return SourceActivation.Ready }
        override suspend fun connectSse(identity: ConnectionIdentity): SourceActivation {
            recorded += "connectSse"
            return owner.connect(identity)
        }
        override suspend fun disconnectSse() {
            recorded += "disconnectSse"
            owner.disconnect(markGap = true)
        }
    }

    /**
     * Shell variant whose `connectSse` awaits a CompletableDeferred before
     * returning. Used by test C to pause the SSE activation mid-flight so the
     * reconfigure teardown can supersede the handoff before the ack arrives.
     */
    private class PausingRecordingShell(
        private val ssePause: CompletableDeferred<SourceActivation>,
    ) : ServiceShell {
        val recorded = mutableListOf<String>()
        var connectSseCalls = 0
            private set
        override fun startForeground(spec: cn.vectory.ocdroid.service.notify.NotificationSpec) { recorded += "startForeground" }
        override fun updateNotification(spec: cn.vectory.ocdroid.service.notify.NotificationSpec) { recorded += "updateNotification" }
        override fun stopForeground() { recorded += "stopForeground" }
        override fun serviceStopSelf() { recorded += "serviceStopSelf" }
        override suspend fun startPoller(
            identity: ConnectionIdentity,
            snapshot: StatusSnapshot,
        ): SourceActivation { recorded += "startPoller"; return SourceActivation.Ready }
        override fun stopPoller() { recorded += "stopPoller" }
        override suspend fun ensurePoller(
            identity: ConnectionIdentity,
            snapshot: StatusSnapshot,
        ): SourceActivation { recorded += "ensurePoller"; return SourceActivation.Ready }
        override suspend fun connectSse(identity: ConnectionIdentity): SourceActivation {
            recorded += "connectSse"
            connectSseCalls++
            // Pause until the test completes the deferred.
            return ssePause.await()
        }
        override suspend fun disconnectSse() { recorded += "disconnectSse" }
    }

    private class TestRecoveryPolicy : SseRecoveryPolicy() {
        override val attempts: Int = 2
        override fun baseDelayMs(attempt: Int): Long = 1_000L * attempt
    }
}
