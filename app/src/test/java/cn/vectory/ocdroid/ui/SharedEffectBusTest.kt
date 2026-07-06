package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.ui.controller.ControllerEffect
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §R18 Phase 3 Wave 1: dedicated unit test for the new [SharedEffectBus] API
 * (emitEffect / tryEmitEffect / emitUiEvent / tryEmitUiEvent /
 * droppedEffectCount). The buffer-overflow split (SUSPEND for effects, non-
 * blocking for uiEvents) and the dropped-effect counter are the contracts
 * the rest of the controller migration relies on.
 *
 * §subscriber-first: SharedFlow with replay=0 only delivers emissions that
 * happen WHILE a subscriber is active. The collectors here launch with
 * [CoroutineStart.UNDISPATCHED] on an [UnconfinedTestDispatcher] so the
 * collector registers BEFORE the test body's tryEmit/emit call.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SharedEffectBusTest {

    @Before
    fun setUp() {
        // DebugLog.w forwards to android.util.Log.w on a real device; the JVM
        // unit test harness has no android.util.Log implementation, so stub
        // the static. tryEmitEffect's failure path calls DebugLog.w.
        mockkStatic(android.util.Log::class)
        io.mockk.every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        io.mockk.every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        io.mockk.every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        io.mockk.every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        io.mockk.every { android.util.Log.e(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun newScope(): TestScope = TestScope(UnconfinedTestDispatcher())

    @Test
    fun `tryEmitEffect delivers to effectsConsumed collector`() {
        val bus = SharedEffectBus()
        val scope = newScope()
        val collected = mutableListOf<ControllerEffect>()
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { bus.effectsConsumed.toList(collected) }

        assertTrue(bus.tryEmitEffect(ControllerEffect.ForceReconnect))
        job.cancel()

        assertEquals(listOf(ControllerEffect.ForceReconnect), collected)
        assertEquals("nothing dropped on a successful emit", 0L, bus.droppedEffectCount())
    }

    @Test
    fun `emitEffect suspends and delivers to collector`() {
        val bus = SharedEffectBus()
        val scope = newScope()
        val collected = mutableListOf<ControllerEffect>()
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { bus.effectsConsumed.toList(collected) }

        // emitEffect is suspend — run it on the same unconfined scope so the
        // collector (already registered UNDISPATCHED) drains it synchronously.
        var returned = false
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            bus.emitEffect(ControllerEffect.ColdStartReconnect)
            returned = true
        }
        assertTrue("emitEffect returned", returned)
        job.cancel()

        assertEquals(listOf(ControllerEffect.ColdStartReconnect), collected)
    }

    @Test
    fun `droppedEffectCount starts at zero and stays zero while the collector keeps up`() {
        // §P1-8: droppedEffectCount is the diagnostic counter the rest of the
        // controller migration can poll to detect producer back-pressure.
        // Verify the baseline (zero) and that successful emits don't bump it.
        // (The actual overflow threshold depends on SharedFlow's per-subscriber
        // slot accounting + BufferOverflow.SUSPEND; we don't stress it here —
        // the controllers use tryEmitEffect for its log+counter side effect
        // when a drop ever happens, not as a flow-control mechanism.)
        val bus = SharedEffectBus()
        val scope = newScope()
        val collected = mutableListOf<ControllerEffect>()
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { bus.effectsConsumed.toList(collected) }

        assertEquals(0L, bus.droppedEffectCount())
        repeat(50) { assertTrue(bus.tryEmitEffect(ControllerEffect.ColdStartReconnect)) }
        assertEquals("no drops while the collector keeps up", 0L, bus.droppedEffectCount())
        assertEquals(50, collected.size)
        job.cancel()
        scope.cancel()
    }

    @Test
    fun `tryEmitUiEvent delivers via uiEventsConsumed`() {
        val bus = SharedEffectBus()
        val scope = newScope()
        val collected = mutableListOf<UiEvent>()
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { bus.uiEventsConsumed.toList(collected) }

        assertTrue(bus.tryEmitUiEvent(UiEvent.Debug("hello")))
        job.cancel()

        assertEquals(1, collected.size)
        assertEquals("hello", (collected.single() as UiEvent.Debug).message)
    }

    @Test
    fun `effects and effectsConsumed stay aliased after the SharedFlow migration`() {
        // §back-compat: tests + AppCore read effectsConsumed; the new spec
        // collapses effects + effectsConsumed onto the same SharedFlow. Both
        // should observe the same emission.
        val bus = SharedEffectBus()
        val scope = newScope()
        val fromEffects = mutableListOf<ControllerEffect>()
        val fromConsumed = mutableListOf<ControllerEffect>()
        val jobA = scope.launch(start = CoroutineStart.UNDISPATCHED) { bus.effects.toList(fromEffects) }
        val jobB = scope.launch(start = CoroutineStart.UNDISPATCHED) { bus.effectsConsumed.toList(fromConsumed) }

        bus.tryEmitEffect(ControllerEffect.ForceReconnect)
        jobA.cancel()
        jobB.cancel()

        assertEquals(fromEffects, fromConsumed)
    }
}
