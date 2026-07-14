package cn.vectory.ocdroid.service

import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.lifecycle.Layer
import cn.vectory.ocdroid.service.lifecycle.LifecycleCommand
import cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator
import cn.vectory.ocdroid.service.streaming.SourceActivation
import cn.vectory.ocdroid.service.status.GlobalBusyState
import cn.vectory.ocdroid.service.status.SessionBusyStatus
import cn.vectory.ocdroid.service.status.SessionStatusKey
import cn.vectory.ocdroid.service.status.StatusAggregator
import cn.vectory.ocdroid.ui.ConnectionPhase
import cn.vectory.ocdroid.ui.SharedStateStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D4-B (gate M3 / B1 / M4) — integration tests for the transport-readiness +
 * two-stage Starting→Ready ownership + reconfigure no-source barrier +
 * mandatory rollback. Uses the REAL [StreamingOwnershipGate] +
 * [StreamingLifecycleCoordinator] + a recording SSE-owner callback so the
 * ownership / teardown contracts are exercised end-to-end (NOT trivial fakes).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OwnershipAndReconfigureIntegrationTest {

    private val identity = ConnectionIdentity(2L, "group", "/work", "endpoint")

    // ── B1: two-stage Starting→Ready ownership ──────────────────────────────

    @Test
    fun `B1 - Starting does not satisfy the launcher waiter then markReady does`() = runTest {
        val gate = StreamingOwnershipGate()
        val waiter = gate.prepare(identity)
        assertFalse("waiter not yet completed", waiter.isCompleted)

        // Stage 1: registerStarting does NOT complete the waiter.
        val registered = gate.registerStarting(
            identity,
            disconnectAndJoin = { },
            abortStartup = { },
        )
        assertTrue("Starting recorded", registered)
        assertFalse("waiter STILL not completed after Starting", waiter.isCompleted)
        assertNull("readyIdentity is null while only Starting", gate.readyIdentity())

        // Stage 2: markReady completes the waiter with Ready.
        gate.markReady(identity)
        assertEquals(
            "waiter completed with Ready after markReady",
            OwnershipStartResult.Ready(identity),
            waiter.await(),
        )
        assertEquals(identity, gate.readyIdentity())
    }

    @Test
    fun `B1 - failStarting completes waiter with Refused and releases Starting ownership`() = runTest {
        val gate = StreamingOwnershipGate()
        val waiter = gate.prepare(identity)
        var abortCalled = false
        var disconnectCalled = false
        gate.registerStarting(
            identity,
            disconnectAndJoin = { disconnectCalled = true },
            abortStartup = { abortCalled = true },
        )

        val extracted = gate.failStarting(identity, OwnershipRefusal.BootstrapFailed)
        assertNotNull("Starting owner extracted for rollback", extracted)
        extracted!!.abortStartup()
        extracted.disconnectAndJoin(false)

        assertEquals(
            "waiter refused after failStarting",
            OwnershipStartResult.Refused(OwnershipRefusal.BootstrapFailed),
            waiter.await(),
        )
        assertNull("Starting ownership released", gate.readyIdentity())
        assertTrue("abortStartup invoked", abortCalled)
        assertTrue("disconnectAndJoin invoked", disconnectCalled)
    }

    @Test
    fun `B1 - no late Ready resurrects ownership after failStarting`() = runTest {
        val gate = StreamingOwnershipGate()
        gate.registerStarting(identity, disconnectAndJoin = { }, abortStartup = { })
        gate.failStarting(identity, OwnershipRefusal.BootstrapFailed)

        // A late markReady (e.g. a stray SSE frame) finds no Starting owner → no-op.
        gate.markReady(identity)
        assertNull("no ownership resurrected by late markReady", gate.readyIdentity())
    }

    // ── B1: reconfigure teardown observes + releases a Starting owner ───────

    @Test
    fun `B1 - Starting visible to reconfigure teardown (disconnectAndRelease joins it)`() = runTest {
        val gate = StreamingOwnershipGate()
        var disconnectJoined = false
        gate.registerStarting(
            identity,
            disconnectAndJoin = { disconnectJoined = true },
            abortStartup = { },
        )
        // Reconfigure path calls disconnectAndRelease (any owner state).
        gate.disconnectAndRelease(markGap = false)
        assertTrue("Starting owner's disconnect callback joined", disconnectJoined)
        assertNull("ownership released after reconfigure", gate.readyIdentity())
    }

    // ── M4: reconfigure is an intentional no-source barrier ─────────────────

    @Test
    fun `M4 - reconfigure teardown emits NO StartPoller and forces L3 + StopSse`() = runTest {
        val status = RecordingStatusAggregator()
        val store = ConnectionIdentityStore()
        store.bind("group", "/work", "endpoint")
        val boundIdentity = store.currentIdentity.value!!
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands += it } }
        coordinator.start(inForeground)
        // Drive bootstrap to L2Active (SSE on).
        status.setState(GlobalBusyState.Busy)
        coordinator.onBootstrapResult(boundIdentity, GlobalBusyState.Busy)
        runCurrent()
        val bootstrapSse = commands.filterIsInstance<LifecycleCommand.StartSse>().single()
        coordinator.onActivationAck(bootstrapSse.handoffToken, SourceActivation.Ready)
        runCurrent()
        assertEquals(Layer.L2Active, coordinator.layer.value)
        commands.clear()

        // Reconfigure teardown: dedicated no-source path.
        coordinator.teardownAndAwait(TeardownReason.Reconfigure)
        runCurrent()

        assertEquals("L3 after reconfigure", Layer.L3, coordinator.layer.value)
        assertFalse(
            "M4: NO StartPoller emitted (no replacement poller for invalid identity)",
            commands.any { it is LifecycleCommand.StartPoller },
        )
        assertTrue("StopSse emitted", commands.contains(LifecycleCommand.StopSse))
        assertTrue("StopPoller emitted (terminate old-identity poller)", commands.contains(LifecycleCommand.StopPoller))
        assertTrue("StopForeground emitted", commands.contains(LifecycleCommand.StopForeground))
        assertTrue("StopSelf emitted", commands.contains(LifecycleCommand.StopSelf))
    }

    // ── B1: BootstrapFailure forces terminal cleanup even from L3 ───────────

    @Test
    fun `B1 - BootstrapFailure teardown forces StopSse + StopForeground + StopSelf even from L3`() = runTest {
        val status = RecordingStatusAggregator()
        val coordinator = StreamingLifecycleCoordinator(status, backgroundScope)
        val inForeground = MutableStateFlow(false)
        val commands = mutableListOf<LifecycleCommand>()
        backgroundScope.launch { coordinator.commands.collect { commands += it } }
        coordinator.start(inForeground)
        // Layer starts at L3 (no bootstrap). BootstrapFailure must STILL emit
        // the full terminal cleanup (the L3 short-circuit is invalid here).
        coordinator.teardownAndAwait(TeardownReason.BootstrapFailure)
        runCurrent()

        assertEquals(Layer.L3, coordinator.layer.value)
        assertTrue("StopSse emitted from L3 on BootstrapFailure", commands.contains(LifecycleCommand.StopSse))
        assertTrue("StopForeground emitted", commands.contains(LifecycleCommand.StopForeground))
        assertTrue("StopSelf emitted", commands.contains(LifecycleCommand.StopSelf))
    }

    // ── D4-B: releaseNow is non-suspend + removes owner ────────────────────

    @Test
    fun `D4-B - releaseNow synchronously clears ownership (no runBlocking)`() {
        val gate = StreamingOwnershipGate()
        gate.registerStarting(identity, disconnectAndJoin = { }, abortStartup = { })
        gate.releaseNow(identity)
        assertNull("ownership cleared synchronously", gate.readyIdentity())
    }

    @Test
    fun `D4-B - failStarting with null identity refuses all Starting owners`() = runTest {
        val gate = StreamingOwnershipGate()
        val waiter = gate.prepare(identity)
        gate.registerStarting(identity, disconnectAndJoin = { }, abortStartup = { })
        // null identity = "fail whatever is Starting" (e.g. process-level cancel).
        val extracted = gate.failStarting(null, OwnershipRefusal.BootstrapFailed)
        assertNotNull(extracted)
        assertEquals(
            OwnershipStartResult.Refused(OwnershipRefusal.BootstrapFailed),
            waiter.await(),
        )
    }

    // ── Fakes ───────────────────────────────────────────────────────────────

    private class RecordingStatusAggregator : StatusAggregator {
        private val _globalState = MutableStateFlow(GlobalBusyState.AllIdleFresh)
        override val globalState: StateFlow<GlobalBusyState> = _globalState.asStateFlow()
        override val globalBusy: StateFlow<Boolean> = MutableStateFlow(false)
        override val statusByKey: StateFlow<Map<SessionStatusKey, SessionBusyStatus>> =
            MutableStateFlow(emptyMap())
        override fun stateAtNow(): GlobalBusyState = _globalState.value
        fun setState(state: GlobalBusyState) { _globalState.value = state }
    }
}
