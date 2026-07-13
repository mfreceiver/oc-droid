package cn.vectory.ocdroid.service.lifecycle

import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.status.SessionBusyStatus
import cn.vectory.ocdroid.service.status.SessionStatusKey
import cn.vectory.ocdroid.service.status.StatusAggregator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 / dev-design P0.3 — unit tests for [StreamingLifecycleCoordinator],
 * the FGS §4 L1/L2/L3 state machine.
 *
 * Covers the highest-value state-machine invariants (FGS spec §4.1 / §4.2 /
 * §4.4 / §16-U1):
 *  - L1-idle → L1-busy foreground elevation (§4.2);
 *  - bootstrap background+busy → L2Active (§5 step 4);
 *  - L2Active all-idle + 45s debounce → L2Idle (SSE off + poller on, §4.4);
 *  - L2Idle poller finds busy → L2Active IN-PLACE (groker-R1, no new
 *    startForegroundService);
 *  - user-close / timeout → L3 teardown (§16-U1 / §4.1);
 *  - §4.4 handoff ordering — new source active BEFORE closing old source
 *    (no "no data source" middle state).
 *
 * Uses a fakeable [StatusAggregator] (the bridge depends on the INTERFACE,
 * never the impl) and the `runTest` virtual clock to advance the 45s idle
 * debounce without wall-clock latency.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StreamingLifecycleCoordinatorTest {

    private val identity = ConnectionIdentity(
        epoch = 7L,
        serverGroupFp = "group-fp",
        normalizedWorkdir = "/work/dir",
        endpointFp = "endpoint-fp",
    )

    @Test
    fun `L1 idle to busy elevates foreground (section 4_2)`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(true)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        coordinator.onBootstrapResult(identity, busy = false)
        runCurrent()
        // bootstrap: L3 → L1(idle). Commands: StartSse, StopPoller, StopForeground.
        assertEquals(Layer.L1(busy = false), coordinator.layer.value)
        commands.clear()

        // §4.2: foreground + busy arrival → immediate FGS promotion (legal while foreground).
        status.setBusy(true)
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
        status.setBusy(true)
        coordinator.onBootstrapResult(identity, busy = true)
        runCurrent()
        assertEquals(Layer.L1(busy = true), coordinator.layer.value)
        commands.clear()

        status.setBusy(false)
        runCurrent()

        assertEquals(Layer.L1(busy = false), coordinator.layer.value)
        // §4.1 L1-idle: normal Service (no FGS slot), SSE still alive.
        assertEquals(listOf(LifecycleCommand.StopForeground), commands)
    }

    @Test
    fun `bootstrap background plus busy enters L2Active directly (section 5 step 4)`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        status.setBusy(true)
        coordinator.onBootstrapResult(identity, busy = true)
        runCurrent()

        assertEquals(Layer.L2Active, coordinator.layer.value)
        // §4.4: SSE (new source) started before the L3 poller is retired.
        assertEquals(
            listOf(LifecycleCommand.StartSse(identity), LifecycleCommand.StopPoller),
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
        coordinator.onBootstrapResult(identity, busy = false)
        runCurrent()

        assertEquals(Layer.L3, coordinator.layer.value)
        // §5 step 5: background idle → stopForeground + stopSelf.
        assertEquals(
            listOf(LifecycleCommand.StopForeground, LifecycleCommand.StopSelf),
            commands,
        )
    }

    @Test
    fun `L2Active all-idle waits 45s then transitions to L2Idle with poller on SSE off (section 4_4)`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        status.setBusy(true)
        coordinator.onBootstrapResult(identity, busy = true)
        runCurrent()
        assertEquals(Layer.L2Active, coordinator.layer.value)
        commands.clear()

        // All-idle arm: debounce starts.
        status.setBusy(false)
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

        assertEquals(Layer.L2Idle, coordinator.layer.value)
        // §4.4 handoff: StartPoller (new source) BEFORE StopSse (old) — no no-source middle state.
        assertEquals(
            listOf(LifecycleCommand.StartPoller, LifecycleCommand.StopSse),
            commands,
        )
    }

    @Test
    fun `L2Active blip of idle then busy cancels debounce and stays L2Active`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        status.setBusy(true)
        coordinator.onBootstrapResult(identity, busy = true)
        runCurrent()
        commands.clear()

        // Idle arm then busy return inside the 45s window.
        status.setBusy(false)
        runCurrent()
        status.setBusy(true)
        runCurrent()
        assertEquals(Layer.L2Active, coordinator.layer.value)

        // Advance well past 45s — debounce was cancelled, no L2Idle transition.
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS + 5_000)
        runCurrent()
        assertEquals("debounce cancelled — stays L2Active", Layer.L2Active, coordinator.layer.value)
    }

    @Test
    fun `L2Idle finds busy returns to L2Active in place without startForegroundService (groker-R1)`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        status.setBusy(true)
        coordinator.onBootstrapResult(identity, busy = true)
        runCurrent()
        status.setBusy(false)
        runCurrent()
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
        runCurrent()
        assertEquals(Layer.L2Idle, coordinator.layer.value)
        commands.clear()

        // groker-R1: poller finds busy → IN-PLACE L2Idle → L2Active.
        status.setBusy(true)
        runCurrent()

        assertEquals(Layer.L2Active, coordinator.layer.value)
        // §4.4: StartSse (new source) BEFORE StopPoller (old). No StartForeground (in-place).
        assertEquals(
            listOf(LifecycleCommand.StartSse(identity), LifecycleCommand.StopPoller),
            commands,
        )
    }

    @Test
    fun `L1 busy to background stays foreground held entering L2Active (section 4_2)`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(true)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        status.setBusy(true)
        coordinator.onBootstrapResult(identity, busy = true)
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
        coordinator.onBootstrapResult(identity, busy = false)
        runCurrent()
        assertEquals(Layer.L1(busy = false), coordinator.layer.value)
        commands.clear()

        // §4.2: foreground idle → background = NO FGS promotion → L3.
        inForeground.value = false
        runCurrent()

        assertEquals(Layer.L3, coordinator.layer.value)
        // §4.4 teardown ordering: StartPoller (new) before StopSse (old).
        assertEquals(
            listOf(
                LifecycleCommand.StartPoller,
                LifecycleCommand.StopSse,
                LifecycleCommand.StopForeground,
                LifecycleCommand.StopSelf,
            ),
            commands,
        )
    }

    @Test
    fun `user close from L2Active tears down to L3 (section 16-U1)`() = runTest {
        val status = FakeStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands.add(it) } }

        coordinator.start(inForeground)
        status.setBusy(true)
        coordinator.onBootstrapResult(identity, busy = true)
        runCurrent()
        assertEquals(Layer.L2Active, coordinator.layer.value)
        commands.clear()

        coordinator.requestUserClose()
        runCurrent()

        assertEquals(Layer.L3, coordinator.layer.value)
        // §4.4 teardown: StartPoller (new source) before StopSse, then StopForeground + StopSelf.
        assertEquals(
            listOf(
                LifecycleCommand.StartPoller,
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
        status.setBusy(true)
        coordinator.onBootstrapResult(identity, busy = true)
        runCurrent()
        status.setBusy(false)
        runCurrent()
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
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
        coordinator.onBootstrapResult(identity, busy = false)
        runCurrent()
        assertEquals(Layer.L3, coordinator.layer.value)
        commands.clear()

        // Busyness / foreground flips must NOT auto-recover from L3.
        status.setBusy(true)
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
        status.setBusy(true)
        coordinator.onBootstrapResult(identity, busy = true)
        runCurrent()
        commands.clear()

        status.setBusy(false)
        runCurrent()
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
        runCurrent()

        // §4.4: StartPoller (new source active) BEFORE StopSse (old source closed).
        val pollerIdx = commands.indexOf(LifecycleCommand.StartPoller)
        val stopSseIdx = commands.indexOf(LifecycleCommand.StopSse)
        assertTrue("StartPoller must be emitted", pollerIdx >= 0)
        assertTrue("StopSse must be emitted", stopSseIdx >= 0)
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
        status.setBusy(true)
        coordinator.onBootstrapResult(identity, busy = true)
        runCurrent()
        status.setBusy(false)
        runCurrent()
        advanceTimeBy(StreamingLifecycleCoordinator.IDLE_DEBOUNCE_MS)
        runCurrent()
        commands.clear()

        status.setBusy(true)
        runCurrent()

        // §4.4: StartSse (new source) BEFORE StopPoller (old source).
        val startSseIdx = commands.indexOf(LifecycleCommand.StartSse(identity))
        val stopPollerIdx = commands.indexOf(LifecycleCommand.StopPoller)
        assertTrue("StartSse must be emitted", startSseIdx >= 0)
        assertTrue("StopPoller must be emitted", stopPollerIdx >= 0)
        assertTrue(
            "StartSse (idx=$startSseIdx) must precede StopPoller (idx=$stopPollerIdx)",
            startSseIdx < stopPollerIdx,
        )
    }

    // ── Fakes ───────────────────────────────────────────────────────────────

    /**
     * In-memory [StatusAggregator] for tests — `globalBusy` is a writable
     * [MutableStateFlow] so the test drives busy transitions directly.
     * `statusByKey` is unused by the coordinator (it only reads globalBusy).
     */
    private class FakeStatusAggregator : StatusAggregator {
        private val _globalBusy = MutableStateFlow(false)
        override val globalBusy: StateFlow<Boolean> = _globalBusy.asStateFlow()

        override val statusByKey: StateFlow<Map<SessionStatusKey, SessionBusyStatus>> =
            MutableStateFlow(emptyMap())

        fun setBusy(value: Boolean) {
            _globalBusy.value = value
        }
    }
}
