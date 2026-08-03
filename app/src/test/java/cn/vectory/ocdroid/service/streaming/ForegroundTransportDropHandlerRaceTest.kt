package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.service.StreamingOwnershipGate
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * §review-blocker-#2 G8: deterministic teardown-vs-drop race test.
 *
 * Pins that the atomic (gate-release + store-markStopped) block under the
 * drop-handler monitor and the fenced [ForegroundTransportDropHandler]
 * fence produce exactly ONE verdict per race iteration — never both, never
 * neither, and the final runtime state always agrees with the winner:
 *   - Stopped iff disconnect won
 *   - Dropped iff drop won
 *   - gate.currentToken() == null always
 *
 * Runs on real threads (this is a monitor test, not virtual time). Mirrors
 * `StreamingOwnershipGateTest.G2` style.
 */
class ForegroundTransportDropHandlerRaceTest {

    @Test
    fun `G8 - teardown vs drop race produces exactly one verdict and truths always agree`() {
        val iterations = 200
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(iterations) { i ->
                val store = SseTransportRuntimeStore()
                val gate = StreamingOwnershipGate()
                val handler = ForegroundTransportDropHandler(store, gate)

                // Identity + attempt set up the canonical Live state and a held lease.
                val identity = ConnectionIdentity(
                    epoch = 0L,
                    profileId = "p",
                    normalizedWorkdir = "/w",
                    endpointFp = "ep-$i",
                )
                val attempt = store.beginAttempt(identity)
                    ?: error("beginAttempt returned null in iteration $i")
                assertTrue("markLive in iteration $i", store.markLive(attempt))
                val leaseToken = gate.claim(attempt) { }
                    ?: error("gate.claim returned null in iteration $i")

                // Both threads start together on this latch.
                val startLatch = CountDownLatch(2)
                val stoppedWon = AtomicBoolean(false)
                val dropWon = AtomicBoolean(false)
                val doneLatch = CountDownLatch(2)

                // T1: simulates the fixed disconnectLocked atomic block —
                // releaseNow + markStopped under synchronized(handler).
                executor.execute {
                    startLatch.countDown()
                    try {
                        startLatch.await()
                    } catch (_: InterruptedException) {
                        return@execute
                    }
                    synchronized(handler) {
                        if (gate.releaseNow(leaseToken)) {
                            stoppedWon.set(store.markStopped(attempt))
                        }
                    }
                    doneLatch.countDown()
                }

                // T2: the fenced drop handler invocation.
                executor.execute {
                    startLatch.countDown()
                    try {
                        startLatch.await()
                    } catch (_: InterruptedException) {
                        return@execute
                    }
                    dropWon.set(
                        handler.onUnexpectedDropIfCurrent(attempt, TransportDropReason.RETRY_EXHAUSTED)
                    )
                    doneLatch.countDown()
                }

                assertTrue("iteration $i: both threads finished",
                    doneLatch.await(10, TimeUnit.SECONDS))

                // Exactly one verdict — XOR.
                val stopped = stoppedWon.get()
                val dropped = dropWon.get()
                assertFalse("iteration $i: never both win (stopped=$stopped, dropped=$dropped)",
                    stopped && dropped)
                assertTrue("iteration $i: at least one wins (stopped=$stopped, dropped=$dropped)",
                    stopped || dropped)

                // Final state agrees with the winner.
                val finalState = store.state.value
                if (stopped) {
                    assertEquals("iteration $i: Stopped iff stoppedWon",
                        SseTransportState.Stopped, finalState)
                } else {
                    assertTrue("iteration $i: Dropped iff dropWon",
                        finalState is SseTransportState.Dropped)
                    val ticket = (finalState as SseTransportState.Dropped).ticket
                    assertEquals("iteration $i: Dropped ticket carries the attempt identity",
                        identity, ticket.identity)
                }

                // Gate is always empty after one of the verdicts released it.
                assertNull("iteration $i: gate empty after verdict", gate.currentToken())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * G8 follow-up: when disconnect's atomic block runs (release + markStopped
     * under the monitor), a drop arriving AFTER is correctly fenced out — it
     * returns false and publishes no ticket. Sequential (non-racy) degenerate
     * of G8, useful for failure diagnosis.
     */
    @Test
    fun `G8b - disconnect-then-drop sequential, drop fenced, no spurious ticket`() {
        val store = SseTransportRuntimeStore()
        val gate = StreamingOwnershipGate()
        val handler = ForegroundTransportDropHandler(store, gate)

        val identity = ConnectionIdentity(epoch = 0L, profileId = "p", normalizedWorkdir = "/w", endpointFp = "ep-seq")
        val attempt = store.beginAttempt(identity)!!
        store.markLive(attempt)
        val token = gate.claim(attempt) { }!!

        // T1 wins the whole atomic block first.
        synchronized(handler) {
            gate.releaseNow(token)
            val stopped = store.markStopped(attempt)
            assertTrue("markStopped succeeds when canonical attempt matches", stopped)
        }

        // T2 arrives after: fence sees currentAttempt != attempt (Stopped
        // cleared it) → returns false, no publishDropped.
        val dropped = handler.onUnexpectedDropIfCurrent(attempt, TransportDropReason.RETRY_EXHAUSTED)
        assertFalse("drop fenced out after Stopped commit", dropped)
        assertEquals("runtime stays Stopped", SseTransportState.Stopped, store.state.value)
        assertNull("no recovery ticket", store.currentDropTicket(identity))
        assertNull("gate empty", gate.currentToken())
    }

    /**
     * G8 follow-up: the reverse sequential — drop wins first, then
     * disconnect's markStopped is rejected (Dropped already cleared the
     * canonical attempt). Lease is released by the handler, not the
     * disconnect. Pins the drop-wins boundary of G8.
     */
    @Test
    fun `G8c - drop-then-disconnect sequential, markStopped no-op, Dropped kept`() {
        val store = SseTransportRuntimeStore()
        val gate = StreamingOwnershipGate()
        val handler = ForegroundTransportDropHandler(store, gate)

        val identity = ConnectionIdentity(epoch = 0L, profileId = "p", normalizedWorkdir = "/w", endpointFp = "ep-rev")
        val attempt = store.beginAttempt(identity)!!
        store.markLive(attempt)
        val token = gate.claim(attempt) { }!!

        // T2 (drop) wins first.
        val dropped = handler.onUnexpectedDropIfCurrent(attempt, TransportDropReason.RETRY_EXHAUSTED)
        assertTrue("drop published Dropped", dropped)
        assertTrue("runtime is Dropped", store.state.value is SseTransportState.Dropped)
        val ticket1 = (store.state.value as SseTransportState.Dropped).ticket
        assertNull("drop released the gate lease", gate.currentToken())

        // T1 (disconnect) arrives after: releaseNow fails (gate already empty),
        // markStopped fails (Dropped cleared canonical attempt).
        val released = synchronized(handler) {
            gate.releaseNow(token) // already released by handler → false
        }
        assertFalse("releaseNow no-op (already empty)", released)
        val stopped = store.markStopped(attempt)
        assertFalse("markStopped rejected (canonical already Dropped)", stopped)

        // State unchanged: still Dropped with the same ticket.
        assertTrue("runtime still Dropped", store.state.value is SseTransportState.Dropped)
        val ticketAfter = (store.state.value as SseTransportState.Dropped).ticket
        assertEquals("Dropped ticket stable (I4)", ticket1, ticketAfter)
    }
}
