package cn.vectory.ocdroid.service.lifecycle

import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.status.GlobalBusyState
import cn.vectory.ocdroid.service.status.SessionBusyStatus
import cn.vectory.ocdroid.service.status.SessionStatusKey
import cn.vectory.ocdroid.service.status.StatusAggregator
import cn.vectory.ocdroid.service.streaming.SourceActivation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 / dev-design P0.3 — unit tests for [StreamingLifecycleCoordinator],
 * the FGS §4 L1/L2/L3 state machine.
 *
 * **D2 gate #4 acknowledgeable handoff**: [LifecycleCommand.StartSse] /
 * [LifecycleCommand.StartPoller] now carry a handoff token; the transition's
 * commit phase (StopX + layer flip) runs ONLY after the test drives an
 * [SourceActivation] back via [StreamingLifecycleCoordinator.onActivationAck].
 * Tests that need the full transition extract the token from the emitted
 * StartX command + call onActivationAck; tests that only care about the
 * activation being emitted (not the commit) can leave the deferred pending.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StreamingLifecycleCoordinatorTest {

    private val identity = ConnectionIdentity(
        epoch = 7L,
        serverGroupFp = "group-fp",
        normalizedWorkdir = "/work/dir",
        endpointFp = "endpoint-fp",
    )

    /**
     * Drives the ack for the most-recently-emitted StartSse / StartPoller
     * command in [commands] (highest handoff token wins — both command kinds
     * share the same monotonic token counter, so this picks the LATEST
     * handoff regardless of source kind). The D2 handoff commit phase runs
     * in [runCurrent] after the ack.
     */
    private suspend fun driveLastHandoffAck(
        coordinator: StreamingLifecycleCoordinator,
        commands: MutableList<LifecycleCommand>,
        activation: SourceActivation,
    ) {
        val sseTokens = commands.filterIsInstance<LifecycleCommand.StartSse>().map { it.handoffToken }
        val pollerTokens = commands.filterIsInstance<LifecycleCommand.StartPoller>().map { it.handoffToken }
        val token = (sseTokens + pollerTokens).maxOrNull()
            ?: error("no pending StartSse/StartPoller to ack")
        coordinator.onActivationAck(token, activation)
    }

    @Test
    fun `L1 idle to busy elevates foreground (section 4_2)`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(true)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        // bootstrap: L3 → L1(idle). Emits StartSse + (commit) StopPoller.
        coordinator.onBootstrapResult(identity, GlobalBusyState.AllIdleFresh)
        runCurrent()
        // Drive the SSE handoff ack to complete the L1-idle commit.
        driveLastHandoffAck(coordinator, commands, SourceActivation.Ready)
        runCurrent()
        assertEquals(Layer.L1(busy = false), coordinator.layer.value)
        commands.clear()

        // §4.2: foreground + busy arrival → immediate FGS promotion (legal while foreground).
        status.setState(GlobalBusyState.Busy)
        runCurrent()

        assertEquals(Layer.L1(busy = true), coordinator.layer.value)
        assertEquals(listOf(LifecycleCommand.StartForeground), commands)
    }

    @Test
    fun `L1 busy to idle downgrades foreground keeping SSE alive (section 4_1)`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(true)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        status.setState(GlobalBusyState.Busy)
        coordinator.onBootstrapResult(identity, GlobalBusyState.Busy)
        runCurrent()
        driveLastHandoffAck(coordinator, commands, SourceActivation.Ready)
        runCurrent()
        assertEquals(Layer.L1(busy = true), coordinator.layer.value)
        commands.clear()

        status.setState(GlobalBusyState.AllIdleFresh)
        runCurrent()

        assertEquals(Layer.L1(busy = false), coordinator.layer.value)
        // §4.1 L1-idle: normal Service (no FGS slot), SSE still alive.
        assertEquals(listOf(LifecycleCommand.StopForeground), commands)
    }

    @Test
    fun `bootstrap background plus busy enters L2Active after SSE ack (section 5 step 4)`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        status.setState(GlobalBusyState.Busy)
        coordinator.onBootstrapResult(identity, GlobalBusyState.Busy)
        runCurrent()
        // Pre-ack: only StartSse emitted (commit waits for ack).
        assertEquals(Layer.L3, coordinator.layer.value)
        val startSse = commands.filterIsInstance<LifecycleCommand.StartSse>().single()
        assertEquals(identity, startSse.identity)

        // Drive Ready → commit StopPoller + L2Active.
        driveLastHandoffAck(coordinator, commands, SourceActivation.Ready)
        runCurrent()

        assertEquals(Layer.L2Active, coordinator.layer.value)
        // §4.4: SSE (new source) started before the L3 poller is retired.
        assertEquals(
            listOf(startSse, LifecycleCommand.StopPoller),
            commands,
        )
    }

    @Test
    fun `bootstrap background idle enters L3 teardown (section 5 step 5)`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        coordinator.onBootstrapResult(identity, GlobalBusyState.AllIdleFresh)
        runCurrent()

        assertEquals(Layer.L3, coordinator.layer.value)
        // §5 step 5: background idle → stopForeground + stopSelf (no handoff).
        assertEquals(
            listOf(LifecycleCommand.StopForeground, LifecycleCommand.StopSelf),
            commands,
        )
    }

    @Test
    fun `L2Active all-idle waits 45s then transitions to L2Idle after poller ack (section 4_4)`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        status.setState(GlobalBusyState.Busy)
        coordinator.onBootstrapResult(identity, GlobalBusyState.Busy)
        runCurrent()
        driveLastHandoffAck(coordinator, commands, SourceActivation.Ready)
        runCurrent()
        assertEquals(Layer.L2Active, coordinator.layer.value)
        commands.clear()

        // All-idle arm: debounce starts.
        status.setState(GlobalBusyState.AllIdleFresh)
        runCurrent()
        assertEquals("debounce armed — layer stays L2Active", Layer.L2Active, coordinator.layer.value)
        assertTrue("no commands emitted during debounce arm", commands.isEmpty())

        // Just under the window: still L2Active.
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS - 1)
        runCurrent()
        assertEquals(Layer.L2Active, coordinator.layer.value)
        assertTrue(commands.isEmpty())

        // Cross the 45s threshold.
        advanceTimeBy(1)
        runCurrent()

        // Pre-ack: StartPoller emitted, layer still L2Active (commit waits).
        assertEquals(Layer.L2Active, coordinator.layer.value)
        val startPoller = commands.filterIsInstance<LifecycleCommand.StartPoller>().single()

        // Drive Ready(AllIdleFresh) → commit StopSse + L2Idle.
        driveLastHandoffAck(
            coordinator,
            commands,
            SourceActivation.Ready,
        )
        runCurrent()

        assertEquals(Layer.L2Idle, coordinator.layer.value)
        // §4.4 handoff: StartPoller (new source) BEFORE StopSse (old) — no no-source middle state.
        assertEquals(
            listOf(startPoller, LifecycleCommand.StopSse),
            commands,
        )
    }

    @Test
    fun `D2 gate #4 - L2Active to L2Idle final poll reports Busy - no StopSse, stop the new poller`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        status.setState(GlobalBusyState.Busy)
        coordinator.onBootstrapResult(identity, GlobalBusyState.Busy)
        runCurrent()
        driveLastHandoffAck(coordinator, commands, SourceActivation.Ready)
        runCurrent()
        status.setState(GlobalBusyState.AllIdleFresh)
        runCurrent()
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
        runCurrent()
        val pollerToken = commands.filterIsInstance<LifecycleCommand.StartPoller>().single().handoffToken
        commands.clear() // clear after debounce fires (StartPoller already emitted)

        // Final poll reports Busy (the host got busy during the 45s debounce).
        // D4-B M3: the verdict is read from statusAggregator.stateAtNow() at
        // commit, so set the aggregator state to Busy BEFORE the ack.
        status.setState(GlobalBusyState.Busy)
        coordinator.onActivationAck(pollerToken, SourceActivation.Ready)
        runCurrent()

        // Stay L2Active; StopPoller emitted (the new poller is cancelled);
        // NO StopSse (SSE was never closed).
        assertEquals(Layer.L2Active, coordinator.layer.value)
        assertEquals(
            "StopPoller emitted (cancel the new poller); no StopSse",
            listOf(LifecycleCommand.StopPoller),
            commands,
        )
        assertFalse(
            "StopSse MUST NOT be emitted when the final poll reports Busy",
            commands.any { it == LifecycleCommand.StopSse },
        )
    }

    @Test
    fun `D2 gate #4 - L2Active to L2Idle final poll reports Unknown - no StopSse`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        status.setState(GlobalBusyState.Busy)
        coordinator.onBootstrapResult(identity, GlobalBusyState.Busy)
        runCurrent()
        driveLastHandoffAck(coordinator, commands, SourceActivation.Ready)
        runCurrent()
        status.setState(GlobalBusyState.AllIdleFresh)
        runCurrent()
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
        runCurrent()
        val pollerToken = commands.filterIsInstance<LifecycleCommand.StartPoller>().single().handoffToken
        commands.clear()

        // D4-B M3: the verdict is read from statusAggregator.stateAtNow() at
        // commit. Set the aggregator state to Unknown to simulate a failed/
        // stale final poll (so StopSse is NOT emitted — stay L2Active).
        status.setState(GlobalBusyState.Unknown)
        coordinator.onActivationAck(pollerToken, SourceActivation.Ready)
        runCurrent()

        // Stay L2Active; StopPoller emitted; NO StopSse.
        assertEquals(Layer.L2Active, coordinator.layer.value)
        assertEquals(listOf(LifecycleCommand.StopPoller), commands)
    }

    @Test
    fun `L2Active blip of idle then busy cancels debounce and stays L2Active`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        status.setState(GlobalBusyState.Busy)
        coordinator.onBootstrapResult(identity, GlobalBusyState.Busy)
        runCurrent()
        driveLastHandoffAck(coordinator, commands, SourceActivation.Ready)
        runCurrent()
        commands.clear()

        // Idle arm then busy return inside the 45s window.
        status.setState(GlobalBusyState.AllIdleFresh)
        runCurrent()
        status.setState(GlobalBusyState.Busy)
        runCurrent()
        assertEquals(Layer.L2Active, coordinator.layer.value)

        // Advance well past 45s — debounce was cancelled, no L2Idle transition.
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS + 5_000)
        runCurrent()
        assertEquals("debounce cancelled — stays L2Active", Layer.L2Active, coordinator.layer.value)
    }

    @Test
    fun `L2Idle finds busy returns to L2Active after SSE ack (groker-R1)`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        status.setState(GlobalBusyState.Busy)
        coordinator.onBootstrapResult(identity, GlobalBusyState.Busy)
        runCurrent()
        driveLastHandoffAck(coordinator, commands, SourceActivation.Ready)
        runCurrent()
        status.setState(GlobalBusyState.AllIdleFresh)
        runCurrent()
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
        runCurrent()
        driveLastHandoffAck(
            coordinator,
            commands,
            SourceActivation.Ready,
        )
        runCurrent()
        assertEquals(Layer.L2Idle, coordinator.layer.value)
        commands.clear()

        // groker-R1: poller finds busy → in-place L2Idle → L2Active (after ack).
        status.setState(GlobalBusyState.Busy)
        runCurrent()
        // Pre-ack: StartSse emitted, layer still L2Idle.
        assertEquals(Layer.L2Idle, coordinator.layer.value)
        val startSse = commands.filterIsInstance<LifecycleCommand.StartSse>().single()

        driveLastHandoffAck(coordinator, commands, SourceActivation.Ready)
        runCurrent()

        assertEquals(Layer.L2Active, coordinator.layer.value)
        // §4.4: StartSse (new source) BEFORE StopPoller (old). No StartForeground (in-place).
        assertEquals(
            listOf(startSse, LifecycleCommand.StopPoller),
            commands,
        )
    }

    @Test
    fun `D2 gate #4 - TOFU or stale StartSse Rejected - poller stays running, stay L2Idle`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        status.setState(GlobalBusyState.Busy)
        coordinator.onBootstrapResult(identity, GlobalBusyState.Busy)
        runCurrent()
        driveLastHandoffAck(coordinator, commands, SourceActivation.Ready)
        runCurrent()
        status.setState(GlobalBusyState.AllIdleFresh)
        runCurrent()
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
        runCurrent()
        driveLastHandoffAck(
            coordinator,
            commands,
            SourceActivation.Ready,
        )
        runCurrent()
        assertEquals(Layer.L2Idle, coordinator.layer.value)
        commands.clear()

        // L2Idle → L2Active attempted; the SSE activation is Rejected (TOFU/stale).
        status.setState(GlobalBusyState.Busy)
        runCurrent()
        val startSse = commands.filterIsInstance<LifecycleCommand.StartSse>().single()
        driveLastHandoffAck(coordinator, commands, SourceActivation.Rejected.TofuPending)
        runCurrent()

        // Stay L2Idle. No StopPoller (poller stays running). No layer change.
        assertEquals(Layer.L2Idle, coordinator.layer.value)
        assertFalse(
            "StopPoller MUST NOT be emitted when SSE activation is Rejected",
            commands.any { it == LifecycleCommand.StopPoller },
        )
        // The StartSse was emitted (it's the activation request); its
        // Rejected ack means the collector couldn't establish a baseline.
        // The controller-side test (ServiceSseConnectionOwnerTest) verifies
        // the collector did NOT actually start (TOFU freeze / stale drop).
        assertEquals(startSse, commands.single())
    }

    @Test
    fun `L1 busy to background stays foreground held entering L2Active (section 4_2)`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(true)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        status.setState(GlobalBusyState.Busy)
        coordinator.onBootstrapResult(identity, GlobalBusyState.Busy)
        runCurrent()
        driveLastHandoffAck(coordinator, commands, SourceActivation.Ready)
        runCurrent()
        assertEquals(Layer.L1(busy = true), coordinator.layer.value)
        commands.clear()

        // Background transition while busy: FGS already held (§4.2), no new commands.
        inForeground.value = false
        runCurrent()

        assertEquals(Layer.L2Active, coordinator.layer.value)
        assertTrue("no source switch — SSE stays on", commands.isEmpty())
    }

    @Test
    fun `L1 idle to background enters L3 teardown (section 4_2 no background promotion)`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(true)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        coordinator.onBootstrapResult(identity, GlobalBusyState.AllIdleFresh)
        runCurrent()
        driveLastHandoffAck(coordinator, commands, SourceActivation.Ready)
        runCurrent()
        assertEquals(Layer.L1(busy = false), coordinator.layer.value)
        commands.clear()

        // §4.2: foreground idle → background = NO FGS promotion → L3 (poller handoff).
        inForeground.value = false
        runCurrent()
        // Pre-ack: StartPoller emitted, layer still L1-idle.
        assertEquals(Layer.L1(busy = false), coordinator.layer.value)
        val startPoller = commands.filterIsInstance<LifecycleCommand.StartPoller>().single()

        // Drive Ready → commit StopSse + StopForeground + StopSelf + L3.
        driveLastHandoffAck(coordinator, commands, SourceActivation.Ready)
        runCurrent()

        assertEquals(Layer.L3, coordinator.layer.value)
        // §4.4 teardown ordering: StartPoller (new) before StopSse, then
        // StopForeground + StopSelf.
        assertEquals(
            listOf(
                startPoller,
                LifecycleCommand.StopSse,
                LifecycleCommand.StopForeground,
                LifecycleCommand.StopSelf,
            ),
            commands,
        )
    }

    @Test
    fun `user close from L2Active tears down to L3 after poller ack (section 16-U1)`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        status.setState(GlobalBusyState.Busy)
        coordinator.onBootstrapResult(identity, GlobalBusyState.Busy)
        runCurrent()
        driveLastHandoffAck(coordinator, commands, SourceActivation.Ready)
        runCurrent()
        assertEquals(Layer.L2Active, coordinator.layer.value)
        commands.clear()

        coordinator.requestUserClose()
        runCurrent()
        // Pre-ack: StartPoller emitted, layer still L2Active.
        assertEquals(Layer.L2Active, coordinator.layer.value)
        val startPoller = commands.filterIsInstance<LifecycleCommand.StartPoller>().single()

        driveLastHandoffAck(coordinator, commands, SourceActivation.Ready)
        runCurrent()

        assertEquals(Layer.L3, coordinator.layer.value)
        // §4.4 teardown: StartPoller (new source) before StopSse, then StopForeground + StopSelf.
        assertEquals(
            listOf(
                startPoller,
                LifecycleCommand.StopSse,
                LifecycleCommand.StopForeground,
                LifecycleCommand.StopSelf,
            ),
            commands,
        )
    }

    @Test
    fun `onTimeout from L2Idle tears down keeping poller only (section 4_1)`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        status.setState(GlobalBusyState.Busy)
        coordinator.onBootstrapResult(identity, GlobalBusyState.Busy)
        runCurrent()
        driveLastHandoffAck(coordinator, commands, SourceActivation.Ready)
        runCurrent()
        status.setState(GlobalBusyState.AllIdleFresh)
        runCurrent()
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
        runCurrent()
        driveLastHandoffAck(
            coordinator,
            commands,
            SourceActivation.Ready,
        )
        runCurrent()
        assertEquals(Layer.L2Idle, coordinator.layer.value)
        commands.clear()

        // dataSync onTimeout — L2Idle already has poller on + SSE off.
        coordinator.onTimeout()
        runCurrent()

        assertEquals(Layer.L3, coordinator.layer.value)
        // No source switch needed (poller already on, SSE off) — just stopForeground + stopSelf.
        assertEquals(
            listOf(LifecycleCommand.StopForeground, LifecycleCommand.StopSelf),
            commands,
        )
    }

    @Test
    fun `L3 ignores input changes - no automatic recovery (section 4_1)`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        coordinator.onBootstrapResult(identity, GlobalBusyState.AllIdleFresh)
        runCurrent()
        assertEquals(Layer.L3, coordinator.layer.value)
        commands.clear()

        // Busyness / foreground flips must NOT auto-recover from L3.
        status.setState(GlobalBusyState.Busy)
        inForeground.value = true
        runCurrent()

        assertEquals(Layer.L3, coordinator.layer.value)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `handoff ordering - L2Active to L2Idle starts poller before closing SSE (section 4_4)`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        status.setState(GlobalBusyState.Busy)
        coordinator.onBootstrapResult(identity, GlobalBusyState.Busy)
        runCurrent()
        driveLastHandoffAck(coordinator, commands, SourceActivation.Ready)
        runCurrent()
        commands.clear()

        status.setState(GlobalBusyState.AllIdleFresh)
        runCurrent()
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
        runCurrent()

        // Pre-ack: StartPoller emitted; StopSse waits for ack.
        val pollerIdx = commands.indexOfFirst { it is LifecycleCommand.StartPoller }
        assertTrue("StartPoller must be emitted at debounce fire", pollerIdx >= 0)
        assertFalse(
            "StopSse MUST NOT be emitted before the poller ack",
            commands.any { it == LifecycleCommand.StopSse },
        )

        driveLastHandoffAck(
            coordinator,
            commands,
            SourceActivation.Ready,
        )
        runCurrent()

        // Post-ack: StopSse emitted AFTER StartPoller.
        val stopSseIdx = commands.indexOf(LifecycleCommand.StopSse)
        assertTrue("StopSse must be emitted after ack", stopSseIdx >= 0)
        assertTrue(
            "StartPoller (idx=$pollerIdx) must precede StopSse (idx=$stopSseIdx) — no no-source gap",
            pollerIdx < stopSseIdx,
        )
    }

    @Test
    fun `handoff ordering - L2Idle to L2Active starts SSE before stopping poller (section 4_4)`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        status.setState(GlobalBusyState.Busy)
        coordinator.onBootstrapResult(identity, GlobalBusyState.Busy)
        runCurrent()
        driveLastHandoffAck(coordinator, commands, SourceActivation.Ready)
        runCurrent()
        status.setState(GlobalBusyState.AllIdleFresh)
        runCurrent()
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
        runCurrent()
        driveLastHandoffAck(
            coordinator,
            commands,
            SourceActivation.Ready,
        )
        runCurrent()
        assertEquals(Layer.L2Idle, coordinator.layer.value)
        commands.clear()

        status.setState(GlobalBusyState.Busy)
        runCurrent()
        // The L2Idle → L2Active handoff's StartSse should now be in commands.
        assertTrue(
            "L2Idle-exit StartSse must be emitted after Busy arrival; commands=$commands",
            commands.any { it is LifecycleCommand.StartSse },
        )
        driveLastHandoffAck(coordinator, commands, SourceActivation.Ready)
        runCurrent()

        // §4.4: StartSse (new source) BEFORE StopPoller (old source).
        val startSseIdx = commands.indexOfFirst { it is LifecycleCommand.StartSse }
        val stopPollerIdx = commands.indexOf(LifecycleCommand.StopPoller)
        assertTrue("StartSse must be emitted", startSseIdx >= 0)
        assertTrue("StopPoller must be emitted", stopPollerIdx >= 0)
        assertTrue(
            "StartSse (idx=$startSseIdx) must precede StopPoller (idx=$stopPollerIdx)",
            startSseIdx < stopPollerIdx,
        )
    }

    @Test
    fun `D2 gate #4 - concurrent timeout during SSE activation invalidates handoff - no stale commit`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        status.setState(GlobalBusyState.Busy)
        coordinator.onBootstrapResult(identity, GlobalBusyState.Busy)
        runCurrent()
        driveLastHandoffAck(coordinator, commands, SourceActivation.Ready)
        runCurrent()
        status.setState(GlobalBusyState.AllIdleFresh)
        runCurrent()
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
        runCurrent()
        driveLastHandoffAck(
            coordinator,
            commands,
            SourceActivation.Ready,
        )
        runCurrent()
        assertEquals(Layer.L2Idle, coordinator.layer.value)
        commands.clear()

        // Start L2Idle → L2Active handoff (SSE activation). Then BEFORE the
        // ack arrives, a timeout tears down (the L2Idle path: synchronous
        // teardown, no handoff).
        status.setState(GlobalBusyState.Busy)
        runCurrent()
        // StartSse was emitted; layer is still L2Idle (commit waits for ack).
        val startSseBeforeTimeout = commands.filterIsInstance<LifecycleCommand.StartSse>().single()
        assertEquals(Layer.L2Idle, coordinator.layer.value)

        // Concurrent timeout — teardown arrives mid-handoff. The teardown
        // sees L2Idle (poller already on, SSE off) and runs the synchronous
        // path: cancelPendingHandoff emits StopSse + cancels the deferred,
        // then StopForeground + StopSelf + L3.
        coordinator.onTimeout()
        runCurrent()

        assertEquals(Layer.L3, coordinator.layer.value)
        // The pending SSE handoff was cancelled — its commit will bail when
        // the (already-cancelled) deferred throws.
        val stopSseCount = commands.count { it == LifecycleCommand.StopSse }
        assertTrue(
            "cancelPendingHandoff emitted StopSse (cancel the in-flight SSE activation)",
            stopSseCount >= 1,
        )
        assertTrue(
            "teardown emitted StopForeground + StopSelf",
            commands.any { it == LifecycleCommand.StopForeground } &&
                commands.any { it == LifecycleCommand.StopSelf },
        )

        // Late ack arrives for the superseded handoff — must NOT re-flip layer.
        // The deferred was cancelled, so onActivationAck drops it.
        coordinator.onActivationAck(
            startSseBeforeTimeout.handoffToken,
            SourceActivation.Ready,
        )
        runCurrent()

        assertEquals(
            "concurrent timeout invalidated the handoff — no stale commit to L2Active",
            Layer.L3,
            coordinator.layer.value,
        )
    }

    @Test
    fun `D2 gate #4 - foreground change during bootstrap activation supersedes its token`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands += it } }
        coordinator.start(inForeground)
        status.setState(GlobalBusyState.Busy)
        coordinator.onBootstrapResult(identity, GlobalBusyState.Busy)
        runCurrent()
        val oldStart = commands.filterIsInstance<LifecycleCommand.StartSse>().single()

        inForeground.value = true
        runCurrent()
        val starts = commands.filterIsInstance<LifecycleCommand.StartSse>()
        assertEquals(2, starts.size)
        assertTrue(starts[1].handoffToken > oldStart.handoffToken)

        coordinator.onActivationAck(oldStart.handoffToken, SourceActivation.Ready)
        runCurrent()
        assertEquals("stale foreground token cannot commit", Layer.L3, coordinator.layer.value)

        coordinator.onActivationAck(starts[1].handoffToken, SourceActivation.Ready)
        runCurrent()
        assertEquals(Layer.L1(busy = true), coordinator.layer.value)
    }

    @Test
    fun `D2 gate #4 - user close during bootstrap activation invalidates token and tears down`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands += it } }
        coordinator.start(inForeground)
        status.setState(GlobalBusyState.Busy)
        coordinator.onBootstrapResult(identity, GlobalBusyState.Busy)
        runCurrent()
        val start = commands.filterIsInstance<LifecycleCommand.StartSse>().single()

        coordinator.requestUserClose()
        runCurrent()
        assertEquals(Layer.L3, coordinator.layer.value)
        assertTrue(commands.contains(LifecycleCommand.StopForeground))
        assertTrue(commands.contains(LifecycleCommand.StopSelf))

        coordinator.onActivationAck(start.handoffToken, SourceActivation.Ready)
        runCurrent()
        assertEquals("late close-invalidated ack cannot commit", Layer.L3, coordinator.layer.value)
        assertFalse(commands.contains(LifecycleCommand.StopPoller))
    }

    // ── CP4: Unknown state must NOT arm the 45s idle debounce ──────────────

    @Test
    fun `CP4 - Unknown does NOT arm the 45s debounce (keeps source alive)`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        status.setState(GlobalBusyState.Busy)
        coordinator.onBootstrapResult(identity, GlobalBusyState.Busy)
        runCurrent()
        driveLastHandoffAck(coordinator, commands, SourceActivation.Ready)
        runCurrent()
        assertEquals(Layer.L2Active, coordinator.layer.value)
        commands.clear()

        // Status fetch failed (or stale snapshot) → Unknown.
        status.setState(GlobalBusyState.Unknown)
        runCurrent()

        assertEquals(Layer.L2Active, coordinator.layer.value)
        assertTrue("Unknown must emit no source-switch commands", commands.isEmpty())

        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS + 5_000)
        runCurrent()
        assertEquals(
            "Unknown must NOT enter idle grace — stays L2Active even past the debounce window",
            Layer.L2Active,
            coordinator.layer.value,
        )
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `CP4 - bootstrap background Unknown keeps source alive after ack (treated like Busy)`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        status.setState(GlobalBusyState.Unknown)
        coordinator.onBootstrapResult(identity, GlobalBusyState.Unknown)
        runCurrent()
        driveLastHandoffAck(coordinator, commands, SourceActivation.Ready)
        runCurrent()

        // Unknown + background → L2Active (NOT L3 teardown). The bootstrap refuses to
        // authoritatively label the host idle until a fresh successful snapshot arrives.
        assertEquals(Layer.L2Active, coordinator.layer.value)
        val startSse = commands.filterIsInstance<LifecycleCommand.StartSse>().single()
        // D4-B M3: Unknown at commit keeps the supplemental process poller
        // running — NO StopPoller is emitted (status authority is not yet
        // definitive). The SSE transport still commits + the layer flips.
        assertEquals(
            listOf(startSse),
            commands,
        )
    }

    @Test
    fun `CP4 - bootstrap foreground Unknown enters L1 busy (FGS pre-warmed, source alive)`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(true)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        status.setState(GlobalBusyState.Unknown)
        coordinator.onBootstrapResult(identity, GlobalBusyState.Unknown)
        runCurrent()
        driveLastHandoffAck(coordinator, commands, SourceActivation.Ready)
        runCurrent()

        // Unknown + foreground → L1(busy=true) (FGS pre-warmed while a fresh snapshot
        // is awaited — never teardown / downgrade on uncertainty).
        assertEquals(Layer.L1(busy = true), coordinator.layer.value)
    }

    @Test
    fun `CP4 - L2Idle then Unknown re-establishes SSE after ack (restore source on uncertainty)`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        status.setState(GlobalBusyState.Busy)
        coordinator.onBootstrapResult(identity, GlobalBusyState.Busy)
        runCurrent()
        driveLastHandoffAck(coordinator, commands, SourceActivation.Ready)
        runCurrent()
        status.setState(GlobalBusyState.AllIdleFresh)
        runCurrent()
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
        runCurrent()
        driveLastHandoffAck(
            coordinator,
            commands,
            SourceActivation.Ready,
        )
        runCurrent()
        assertEquals(Layer.L2Idle, coordinator.layer.value)
        commands.clear()

        // Poller fires while status is Unknown (failure / stale) → restore SSE in-place
        // (do NOT trust an uncertain verdict to keep the host in idle).
        status.setState(GlobalBusyState.Unknown)
        runCurrent()
        driveLastHandoffAck(coordinator, commands, SourceActivation.Ready)
        runCurrent()

        assertEquals(Layer.L2Active, coordinator.layer.value)
        val startSse = commands.filterIsInstance<LifecycleCommand.StartSse>().single()
        // D4-B M3: Unknown at commit keeps the supplemental poller running —
        // NO StopPoller (status authority not yet definitive).
        assertEquals(
            listOf(startSse),
            commands,
        )
    }

    // ── Fakes ───────────────────────────────────────────────────────────────

    /**
     * In-memory [StatusAggregator] for tests — `globalState` is a writable
     * [MutableStateFlow] so the test drives state transitions directly.
     */
    private class FakeStatusAggregator : StatusAggregator {
        private val _globalState = MutableStateFlow(GlobalBusyState.AllIdleFresh)
        override val globalState: StateFlow<GlobalBusyState> = _globalState.asStateFlow()
        override val globalBusy: StateFlow<Boolean> = MutableStateFlow(false)
        override val statusByKey: StateFlow<Map<SessionStatusKey, SessionBusyStatus>> =
            MutableStateFlow(emptyMap())

        override fun stateAtNow(): GlobalBusyState = _globalState.value

        fun setState(state: GlobalBusyState) {
            _globalState.value = state
        }
    }
}

// ── D1 (gate #1) integrated lifecycle scenarios ─────────────────────────

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StreamingLifecycleCoordinatorStateAtNowTest {

    private val identity = ConnectionIdentity(
        epoch = 7L,
        serverGroupFp = "group-fp",
        normalizedWorkdir = "/work/dir",
        endpointFp = "endpoint-fp",
    )

    @Test
    fun `D1 gate #1 - debounce expiry reads stateAtNow - Unknown at expiry cancels L2Idle transition`() = runTest {
        val status = DivergingFakeStatusAggregator(
            initialGlobalState = GlobalBusyState.AllIdleFresh,
            stateAtNowValue = GlobalBusyState.Unknown,
        )
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        status.setGlobalState(GlobalBusyState.Busy)
        coordinator.onBootstrapResult(identity, GlobalBusyState.Busy)
        runCurrent()
        // Drive the bootstrap SSE ack first.
        val bootstrapToken = commands.filterIsInstance<LifecycleCommand.StartSse>().last().handoffToken
        coordinator.onActivationAck(bootstrapToken, SourceActivation.Ready)
        runCurrent()
        assertEquals(Layer.L2Active, coordinator.layer.value)
        commands.clear()

        // Aggregate reports AllIdleFresh (cache) — arms the 45s debounce.
        // D4-B M3: the bootstrap committed with Unknown (stateAtNow) so a
        // supplemental poller was kept; the definitive AllIdleFresh verdict
        // now retires it (StopPoller emitted) — this is correct M3 behavior.
        status.setGlobalState(GlobalBusyState.AllIdleFresh)
        runCurrent()
        assertEquals(Layer.L2Active, coordinator.layer.value)
        assertTrue(
            "supplemental poller retired on definitive AllIdleFresh",
            commands.filterIsInstance<LifecycleCommand.StopPoller>().size == 1,
        )
        commands.clear()
        assertTrue("debounce armed — no further commands yet", commands.isEmpty())

        // Cross the 45s threshold. The debounce fires; it reads stateAtNow()
        // which returns Unknown → no L2Idle transition, no StartPoller.
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
        runCurrent()

        assertEquals(
            "D1 gate #1: Unknown at debounce expiry MUST NOT enter L2Idle",
            Layer.L2Active,
            coordinator.layer.value,
        )
        assertTrue(
            "no StartPoller emitted on stale-idle verdict",
            commands.none { it is LifecycleCommand.StartPoller },
        )
    }

    private class DivergingFakeStatusAggregator(
        initialGlobalState: GlobalBusyState,
        private var stateAtNowValue: GlobalBusyState,
    ) : StatusAggregator {
        private val _globalState = MutableStateFlow(initialGlobalState)
        override val globalState: StateFlow<GlobalBusyState> = _globalState.asStateFlow()
        override val globalBusy: StateFlow<Boolean> = MutableStateFlow(false)
        override val statusByKey: StateFlow<Map<SessionStatusKey, SessionBusyStatus>> =
            MutableStateFlow(emptyMap())

        override fun stateAtNow(): GlobalBusyState = stateAtNowValue

        fun setGlobalState(state: GlobalBusyState) {
            _globalState.value = state
        }
    }
}
