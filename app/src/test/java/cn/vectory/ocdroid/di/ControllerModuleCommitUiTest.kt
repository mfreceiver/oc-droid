package cn.vectory.ocdroid.di

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.BundleStamp
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.StoreState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * §rev-2 TOCTOU fix: verifies that [SharedStateStore.dispatchAndVerify]
 * correctly returns whether the reducer accepted or rejected an action.
 *
 * Unlike the previous pre-check approach (which cached the verdict before
 * dispatching), [dispatchAndVerify] runs the reducer INSIDE the CAS retry
 * loop — the verdict reflects the truly committed state with no TOCTOU
 * window between check and write.
 *
 * The reducer-level rejection ([acceptsBundle] / [acceptsRouteUpdate]) is
 * already tested in [StageBFreezeProtocolReducerTest]. This test proves
 * that the Boolean return value of [dispatchAndVerify] matches the
 * reducer's actual acceptance — including under concurrent route updates.
 */
class ControllerModuleCommitUiTest {

    /** Sets up a [StoreState] with a known session, route instance, and bundle stamp. */
    private fun setUpStore(
        routeInstance: Long = 5L,
        bundleGeneration: Long = 1L,
        endpointFp: String = "fp-A",
        sessionId: String = "ses-A",
    ): SharedStateStore {
        val store = SharedStateStore()
        // Publish the baseline bundle so the store's live stamp matches.
        store.dispatch(AppAction.BundlePublished(bundleGeneration, endpointFp))
        // Set up a chat with an established session.
        store.mutateState { state ->
            state.copy(
                chatRouteInstance = routeInstance,
                chat = ChatState(
                    currentSessionId = sessionId,
                    messages = listOf(msg("m1")),
                    partsByMessage = mapOf("m1" to listOf(part("p1", "m1"))),
                ),
            )
        }
        return store
    }

    /** Captures store state before an operation for before/after comparison. */
    private fun StoreState.snapshot(): StoreState = copy()

    // ── dispatchAndVerify: matching conditions ──────────────────────────────

    @Test
    fun `dispatchAndVerify with matching bundle and route for SlimFullMessageReconciled returns true`() {
        val store = setUpStore()
        val before = store.stateFlow.value.snapshot()
        // Use a NEW message (m2) not present in the initial state to prove
        // the reducer actually modified the store.
        val action = AppAction.SlimFullMessageReconciled(
            sessionId = "ses-A",
            message = stubMessageWithParts("m2"),
            expectedRouteInstance = 5L,
            bundleStamp = BundleStamp(generation = 1L, endpointFp = "fp-A"),
        )

        val accepted = store.dispatchAndVerify(action)
        val after = store.stateFlow.value

        assertTrue("matching conditions should be accepted", accepted)
        assertNotNull("new message m2 merged after accepted dispatch",
            after.chat.messages.firstOrNull { it.id == "m2" })
        assertTrue("state changed after accepted dispatch", before != after)
    }

    @Test
    fun `dispatchAndVerify with matching conditions for MessageRemovedConfirmed returns true`() {
        val store = setUpStore()
        val before = store.stateFlow.value.snapshot()
        val action = AppAction.MessageRemovedConfirmed(
            sessionId = "ses-A",
            messageId = "m1",
            expectedRouteInstance = 5L,
            bundleStamp = BundleStamp(generation = 1L, endpointFp = "fp-A"),
        )

        val accepted = store.dispatchAndVerify(action)
        val after = store.stateFlow.value

        assertTrue("matching removal should be accepted", accepted)
        assertTrue("message evicted after accepted dispatch",
            after.chat.messages.none { it.id == "m1" })
        assertTrue("state changed after accepted remove dispatch", before != after)
    }

    // ── dispatchAndVerify: stale bundle generation ─────────────────────────

    @Test
    fun `dispatchAndVerify with stale bundle generation for SlimFullMessageReconciled returns false`() {
        val store = setUpStore()
        val before = store.stateFlow.value.snapshot()
        val action = AppAction.SlimFullMessageReconciled(
            sessionId = "ses-A",
            message = stubMessageWithParts("m1"),
            expectedRouteInstance = 5L,
            bundleStamp = BundleStamp(generation = 99L, endpointFp = "fp-A"),
        )

        val accepted = store.dispatchAndVerify(action)

        assertFalse("stale bundle generation should be rejected", accepted)
        assertEquals("state unchanged after rejected dispatch",
            before, store.stateFlow.value)
    }

    @Test
    fun `dispatchAndVerify with stale bundle generation for MessageRemovedConfirmed returns false`() {
        val store = setUpStore()
        val before = store.stateFlow.value.snapshot()
        val action = AppAction.MessageRemovedConfirmed(
            sessionId = "ses-A",
            messageId = "m1",
            expectedRouteInstance = 5L,
            bundleStamp = BundleStamp(generation = 99L, endpointFp = "fp-A"),
        )

        val accepted = store.dispatchAndVerify(action)

        assertFalse("stale bundle generation should be rejected", accepted)
        assertEquals("state unchanged after rejected dispatch",
            before, store.stateFlow.value)
    }

    // ── dispatchAndVerify: stale bundle endpointFp ─────────────────────────

    @Test
    fun `dispatchAndVerify with stale bundle endpointFp returns false`() {
        val store = setUpStore()
        val before = store.stateFlow.value.snapshot()
        val action = AppAction.SlimFullMessageReconciled(
            sessionId = "ses-A",
            message = stubMessageWithParts("m1"),
            expectedRouteInstance = 5L,
            bundleStamp = BundleStamp(generation = 1L, endpointFp = "fp-X"),
        )

        val accepted = store.dispatchAndVerify(action)

        assertFalse("stale bundle endpointFp should be rejected", accepted)
        assertEquals("state unchanged after rejected dispatch",
            before, store.stateFlow.value)
    }

    // ── dispatchAndVerify: stale route instance ────────────────────────────

    @Test
    fun `dispatchAndVerify with stale route instance returns false`() {
        val store = setUpStore(routeInstance = 5L)
        val before = store.stateFlow.value.snapshot()
        val action = AppAction.SlimFullMessageReconciled(
            sessionId = "ses-A",
            message = stubMessageWithParts("m1"),
            expectedRouteInstance = 999L,
            bundleStamp = BundleStamp(generation = 1L, endpointFp = "fp-A"),
        )

        val accepted = store.dispatchAndVerify(action)

        assertFalse("stale route instance should be rejected", accepted)
        assertEquals("state unchanged after rejected dispatch",
            before, store.stateFlow.value)
    }

    @Test
    fun `dispatchAndVerify with stale route instance for removal returns false`() {
        val store = setUpStore(routeInstance = 5L)
        val before = store.stateFlow.value.snapshot()
        val action = AppAction.MessageRemovedConfirmed(
            sessionId = "ses-A",
            messageId = "m1",
            expectedRouteInstance = 999L,
            bundleStamp = BundleStamp(generation = 1L, endpointFp = "fp-A"),
        )

        val accepted = store.dispatchAndVerify(action)

        assertFalse("stale route instance should be rejected", accepted)
        assertEquals("state unchanged after rejected dispatch",
            before, store.stateFlow.value)
    }

    // ── dispatchAndVerify: mismatched sessionId ────────────────────────────

    @Test
    fun `dispatchAndVerify with mismatched sessionId returns false`() {
        val store = setUpStore()
        val before = store.stateFlow.value.snapshot()
        val action = AppAction.SlimFullMessageReconciled(
            sessionId = "ses-OTHER",
            message = stubMessageWithParts("m1"),
            expectedRouteInstance = 5L,
            bundleStamp = BundleStamp(generation = 1L, endpointFp = "fp-A"),
        )

        val accepted = store.dispatchAndVerify(action)

        assertFalse("mismatched sessionId should be rejected", accepted)
        assertEquals("state unchanged after rejected dispatch",
            before, store.stateFlow.value)
    }

    // ── dispatchAndVerify: route=0 ────────────────────────────────────────

    @Test
    fun `dispatchAndVerify with route 0 for SlimFullMessageReconciled is accepted`() {
        val store = setUpStore()
        val before = store.stateFlow.value.snapshot()
        // route=0 passes acceptsRouteUpdate (legacy token=0 returns true
        // unconditionally). The reducer creates a new state via
        // copy(chat = ...).withRouteContentSynced(0, sid) — withRouteContentSynced
        // is a no-op for route=0. So the message IS merged (state changes).
        //
        // In production, the caller short-circuits route=0
        // and NEVER calls dispatchSlimFullReconciled — but if it did, this
        // action would be accepted (legacy compatibility path).
        val action = AppAction.SlimFullMessageReconciled(
            sessionId = "ses-A",
            message = stubMessageWithParts("m2"),
            expectedRouteInstance = 0L,
            bundleStamp = BundleStamp(generation = 1L, endpointFp = "fp-A"),
        )

        val accepted = store.dispatchAndVerify(action)

        assertTrue("route=0 for SlimFullMessageReconciled is accepted (legacy path)", accepted)
        assertTrue("state changed after route=0 dispatch", before != store.stateFlow.value)
    }

    @Test
    fun `dispatchAndVerify with route 0 for MessageRemovedConfirmed returns false`() {
        val store = setUpStore()
        val before = store.stateFlow.value.snapshot()
        val action = AppAction.MessageRemovedConfirmed(
            sessionId = "ses-A",
            messageId = "m1",
            expectedRouteInstance = 0L,
            bundleStamp = BundleStamp(generation = 1L, endpointFp = "fp-A"),
        )

        val accepted = store.dispatchAndVerify(action)

        // reduceMessageRemovedConfirmed explicitly returns state unchanged
        // for route=0 (freeze protocol §C5: no active route → no transcript write).
        assertFalse("route=0 for MessageRemovedConfirmed should be rejected", accepted)
        assertEquals("state unchanged after route=0 dispatch",
            before, store.stateFlow.value)
    }

    // ── dispatchAndVerify: sequential TOCTOU simulation ────────────────────

    @Test
    fun `dispatchAndVerify returns false when route advances before CAS commit`() {
        // Sequential simulation: the route is advanced BEFORE dispatchAndVerify
        // is called. dispatchAndVerify runs the reducer inside the CAS, the
        // reducer detects the stale expectedRouteInstance and returns state
        // unchanged → accepted=false.
        val store = setUpStore(routeInstance = 5L)
        val action = AppAction.SlimFullMessageReconciled(
            sessionId = "ses-A",
            message = stubMessageWithParts("m2"),
            expectedRouteInstance = 5L,  // captured at request trigger
            bundleStamp = BundleStamp(generation = 1L, endpointFp = "fp-A"),
        )

        // Simulate concurrent route advance (e.g., another chat selected)
        store.mutateState { it.copy(chatRouteInstance = 6L) }
        val before = store.stateFlow.value.snapshot()

        val accepted = store.dispatchAndVerify(action)

        assertFalse("route advanced before dispatchAndVerify should be rejected", accepted)
        assertEquals("state unchanged after rejected concurrent-route dispatch",
            before, store.stateFlow.value)
    }

    @Test
    fun `dispatchAndVerify returns false when bundle advances before CAS commit`() {
        val store = setUpStore(bundleGeneration = 1L, endpointFp = "fp-A")
        val action = AppAction.SlimFullMessageReconciled(
            sessionId = "ses-A",
            message = stubMessageWithParts("m2"),
            expectedRouteInstance = 5L,
            bundleStamp = BundleStamp(generation = 1L, endpointFp = "fp-A"),
        )

        // Simulate concurrent bundle advance (host reconfigure)
        store.dispatch(AppAction.BundlePublished(2L, "fp-A"))
        val before = store.stateFlow.value.snapshot()

        val accepted = store.dispatchAndVerify(action)

        assertFalse("bundle advanced before dispatchAndVerify should be rejected", accepted)
        assertEquals("state unchanged after rejected concurrent-bundle dispatch",
            before, store.stateFlow.value)
    }

    // ── dispatchAndVerify: concurrent TOCTOU scenarios ─────────────────────

    /**
     * Concurrent TOCTOU: two threads race — one runs [dispatchAndVerify]
     * while another advances the route instance. Due to CAS retry inside
     * [MutableStateFlow.update], the reducer on the retry sees the new
     * route and rejects. With 5000 iterations and a busy-advancing thread,
     * at least one retry should encounter a stale route.
     *
     * This is inherently probabilistic; the sequential tests
     * ([dispatchAndVerify returns false when route advances before CAS commit])
     * provide the deterministic proof. This test proves the mechanism works
     * under true concurrent execution.
     */
    @Test
    fun `concurrent TOCTOU dispatchAndVerify returns false on route change`() {
        val store = setUpStore(routeInstance = 5L)
        val action = AppAction.SlimFullMessageReconciled(
            sessionId = "ses-A",
            message = stubMessageWithParts("m2"),
            expectedRouteInstance = 5L,
            bundleStamp = BundleStamp(generation = 1L, endpointFp = "fp-A"),
        )
        val stopFlag = AtomicBoolean(false)
        val results = ConcurrentLinkedQueue<Boolean>()
        var threadError: Throwable? = null

        // Thread 1: continuously advance the route instance as fast as possible.
        val advancer = thread(name = "route-advancer") {
            var i = 6L
            while (!stopFlag.get()) {
                store.mutateState { it.copy(chatRouteInstance = i) }
                i++
            }
        }

        // Thread 2: call dispatchAndVerify in a tight loop. The advancer's
        // concurrent mutations cause CAS retries inside dispatchAndVerify;
        // on at least one retry the reducer should see an advanced route and
        // reject → return false.
        thread(name = "dispatch-thread") {
            try {
                repeat(5000) {
                    results.add(store.dispatchAndVerify(action))
                }
            } catch (e: Throwable) {
                threadError = e
            } finally {
                stopFlag.set(true)
            }
        }.join()

        stopFlag.set(true)
        advancer.join(1000)

        assertNull("no thread errors", threadError)
        val anySawStale = results.any { !it }
        assertTrue(
            "at least one dispatchAndVerify returned false under concurrent route change " +
                "(results: ${results.count { it } } true, ${results.count { !it } } false)",
            anySawStale,
        )
    }

    /**
     * Concurrent TOCTOU: same pattern as above, but the concurrent mutation
     * advances the bundle generation instead of the route.
     */
    @Test
    fun `concurrent TOCTOU dispatchAndVerify returns false on bundle change`() {
        val store = setUpStore(bundleGeneration = 1L, endpointFp = "fp-A")
        val action = AppAction.SlimFullMessageReconciled(
            sessionId = "ses-A",
            message = stubMessageWithParts("m2"),
            expectedRouteInstance = 5L,
            bundleStamp = BundleStamp(generation = 1L, endpointFp = "fp-A"),
        )
        val stopFlag = AtomicBoolean(false)
        val results = ConcurrentLinkedQueue<Boolean>()
        var threadError: Throwable? = null

        // Thread 1: continuously advance bundle generation.
        val advancer = thread(name = "bundle-advancer") {
            var gen = 2L
            while (!stopFlag.get()) {
                store.dispatch(AppAction.BundlePublished(gen, "fp-A"))
                gen++
            }
        }

        // Thread 2: dispatchAndVerify in a tight loop.
        thread(name = "dispatch-thread") {
            try {
                repeat(5000) {
                    results.add(store.dispatchAndVerify(action))
                }
            } catch (e: Throwable) {
                threadError = e
            } finally {
                stopFlag.set(true)
            }
        }.join()

        stopFlag.set(true)
        advancer.join(1000)

        assertNull("no thread errors", threadError)
        val anySawStale = results.any { !it }
        assertTrue(
            "at least one dispatchAndVerify returned false under concurrent bundle change " +
                "(results: ${results.count { it } } true, ${results.count { !it } } false)",
            anySawStale,
        )
    }

    // ── dispatchAndVerify: deterministic CAS retry (sticky-variable regression) ─────

    /**
     * Deterministic test for the rev-2 sticky-variable bug fix.
     *
     * The bug: the old [SharedStateStore.dispatchAndVerify] used a mutable
     * `accepted` flag set inside the [MutableStateFlow.update] lambda. If the
     * first CAS iteration's reducer accepted but the CAS failed (concurrent
     * write), then the second iteration's reducer rejected and the CAS
     * succeeded, the old code would return `true` (sticky from the first
     * iteration) instead of `false`.
     *
     * The fix runs the CAS loop directly and returns `next !== current` from
     * the ONE iteration whose CAS actually committed — discarding the verdict
     * of every failed CAS iteration.
     *
     * This test uses [ControlledCasStateFlow] to deterministically inject the
     * exact sequence:
     *
     *   Iteration 1: reduce accepts (new ref) → CAS fails (controlled → false)
     *   Iteration 2: reduce rejects (same ref) → CAS succeeds
     *
     * Expected: `false` (reducer rejected on the committed iteration).
     */
    @Test
    fun `dispatchAndVerify returns false when first CAS fails and reducer accepts then rejects`() {
        val controlledFlow = ControlledCasStateFlow(StoreState.initial())
        // Build baseline state via the controlled flow (same as setUpStore but
        // with the custom flow already in place).
        controlledFlow.value = controlledFlow.value.copy(
            chatRouteInstance = 5L,
            chat = ChatState(
                currentSessionId = "ses-A",
                messages = listOf(msg("m1")),
                partsByMessage = mapOf("m1" to listOf(part("p1", "m1"))),
            ),
            liveBundleGeneration = 1L,
            liveEndpointFp = "fp-A",
        )
        val store = SharedStateStore(controlledFlow)
        val before = store.stateFlow.value.snapshot()
        val action = AppAction.SlimFullMessageReconciled(
            sessionId = "ses-A",
            message = stubMessageWithParts("m2"),
            expectedRouteInstance = 5L,
            bundleStamp = BundleStamp(generation = 1L, endpointFp = "fp-A"),
        )

        val accepted = store.dispatchAndVerify(action)

        assertFalse(
            "dispatchAndVerify must return false when the committed CAS iteration rejected",
            accepted,
        )
        assertEquals(
            "state must be unchanged after rejected dispatch",
            before, store.stateFlow.value,
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun msg(id: String): Message = Message(
        id = id,
        role = "assistant",
        time = Message.TimeInfo(created = 1000L, updated = 1000L),
    )

    private fun part(id: String, msgId: String): Part = Part(
        id = id,
        messageId = msgId,
        sessionId = "ses-A",
        type = "text",
    )

    private fun stubMessageWithParts(id: String): MessageWithParts = MessageWithParts(
        info = msg(id),
        parts = listOf(part("p1", id)),
    )
}

/**
 * A [MutableStateFlow] wrapper that controls [compareAndSet] behavior for
 * deterministic testing of CAS retry sequences.
 *
 * On the first [failCount] calls to [compareAndSet], this flow:
 * 1. Returns `false` (simulating a concurrent write that won the CAS).
 * 2. Mutates its internal value (by bumping [StoreState.chatRouteInstance])
 *    so the caller's next read sees a different state — simulating the exact
 *    sequence that triggered the old sticky-`accepted` bug.
 *
 * On subsequent calls, it delegates to the real [MutableStateFlow].
 *
 * @param failCount number of initial CAS calls to fail (default: 1).
 */
private class ControlledCasStateFlow(
    initial: StoreState,
    private val failCount: Int = 1,
) : MutableStateFlow<StoreState> {
    private val inner: MutableStateFlow<StoreState> = MutableStateFlow(initial)
    private var remainingFails: Int = failCount

    override var value: StoreState
        get() = inner.value
        set(v) { inner.value = v }

    override fun compareAndSet(expect: StoreState, update: StoreState): Boolean {
        return if (remainingFails > 0) {
            remainingFails--
            // Mutate inner value so the retry loop sees a different state on re-read,
            // simulating a concurrent writer having won the CAS between read and write.
            inner.value = inner.value.copy(chatRouteInstance = inner.value.chatRouteInstance + 10000L)
            false
        } else {
            inner.compareAndSet(expect, update)
        }
    }

    override val replayCache: List<StoreState> get() = inner.replayCache
    override val subscriptionCount: StateFlow<Int> get() = inner.subscriptionCount

    override suspend fun collect(collector: FlowCollector<StoreState>): Nothing {
        inner.collect(collector)
    }

    override suspend fun emit(value: StoreState) { inner.emit(value) }
    override fun tryEmit(value: StoreState): Boolean = inner.tryEmit(value)
    override fun resetReplayCache() { inner.resetReplayCache() }
}
