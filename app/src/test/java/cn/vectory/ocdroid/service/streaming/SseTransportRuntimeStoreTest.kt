package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * M0 focused tests: [SseTransportRuntimeStore] contract criteria M0‑C1…C6.
 *
 * All tests are deterministic — no sleeps, no coroutine dispatchers, no
 * timeouts. Concurrency tests use [CountDownLatch] barriers so threads
 * race through the same critical section.
 */
class SseTransportRuntimeStoreTest {

    private lateinit var store: SseTransportRuntimeStore

    private val identityA = ConnectionIdentity(
        epoch = 1, serverGroupFp = "sgA", normalizedWorkdir = "/wdA", endpointFp = "epA",
    )
    private val identityB = ConnectionIdentity(
        epoch = 2, serverGroupFp = "sgB", normalizedWorkdir = "/wdB", endpointFp = "epB",
    )

    @Before
    fun setUp() {
        store = SseTransportRuntimeStore()
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun beginAttempt(
        identity: ConnectionIdentity = identityA,
    ): TransportAttemptToken {
        return store.beginAttempt(identity)
            ?: error("beginAttempt returned null")
    }

    private fun liveAttempt(
        identity: ConnectionIdentity = identityA,
    ): TransportAttemptToken {
        val token = beginAttempt(identity)
        assertTrue("markLive should succeed", store.markLive(token))
        return token
    }

    private fun droppedLiveAttempt(
        identity: ConnectionIdentity = identityA,
        reason: TransportDropReason = TransportDropReason.SERVICE_DESTROYED,
    ): Pair<TransportAttemptToken, TransportDropTicket> {
        val token = liveAttempt(identity)
        val ticket = store.publishDropped(token, reason)
            ?: error("publishDropped returned null")
        return Pair(token, ticket)
    }

    // ═════════════════════════════════════════════════════════════════════
    // M0‑C1 — attempt/drop IDs are monotonic
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `M0-C1 attempt IDs increase monotonically`() {
        val t1 = liveAttempt()
        store.publishDropped(t1, TransportDropReason.SERVICE_DESTROYED)

        // beginAttempt from Dropped → new monotonic ID
        val t2 = beginAttempt()
        assertTrue("attempt ID ${t2.attemptId} > ${t1.attemptId}", t2.attemptId > t1.attemptId)

        // After stop + new attempt, ID still increases
        store.markStopped(t2)
        val t3 = beginAttempt()
        assertTrue("attempt ID ${t3.attemptId} > ${t2.attemptId}", t3.attemptId > t2.attemptId)
    }

    @Test
    fun `M0-C1 fresh drop IDs increase monotonically`() {
        // First drop — fresh ticket
        val t1 = liveAttempt()
        val drop1 = store.publishDropped(t1, TransportDropReason.SERVICE_DESTROYED)!!

        // Clear to Stopped and start fresh (no recoveryTicket)
        val t1b = beginAttempt()
        store.markStopped(t1b)
        val t2 = liveAttempt()
        val drop2 = store.publishDropped(t2, TransportDropReason.BACKGROUND_RECONNECT_REFUSED)!!

        assertTrue("drop ID ${drop2.dropId} > ${drop1.dropId}", drop2.dropId > drop1.dropId)
    }

    // ═════════════════════════════════════════════════════════════════════
    // M0‑C2 — stale attempt tokens cannot overwrite newer state
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `M0-C2 stale token cannot markLive`() {
        val t1 = liveAttempt()
        store.publishDropped(t1, TransportDropReason.RETRY_EXHAUSTED)
        // State is Dropped — t1 is stale
        assertFalse(store.markLive(t1))
    }

    @Test
    fun `M0-C2 stale token cannot markRetrying`() {
        val t1 = liveAttempt()
        store.publishDropped(t1, TransportDropReason.SERVICE_DESTROYED)
        assertFalse(store.markRetrying(t1))
    }

    @Test
    fun `M0-C2 stale token cannot publishDropped`() {
        val t1 = liveAttempt()
        store.publishDropped(t1, TransportDropReason.SERVICE_DESTROYED)
        assertNull(store.publishDropped(t1, TransportDropReason.SERVICE_DESTROYED))
    }

    @Test
    fun `M0-C2 stale token cannot markStopped`() {
        val t1 = liveAttempt()
        store.publishDropped(t1, TransportDropReason.RETRY_EXHAUSTED)
        assertFalse(store.markStopped(t1))
    }

    @Test
    fun `M0-C2 stale token cannot acknowledgeRecovery`() {
        val (t1, _) = droppedLiveAttempt()
        assertFalse(store.acknowledgeRecovery(t1))
    }

    @Test
    fun `M0-C2 superseded attempt is stale — newer generation owns the state`() {
        // t1 → Dropped → t2 recovery attempt → t2 is the canonical current
        val (t1, _) = droppedLiveAttempt()
        val t2 = beginAttempt()
        store.markLive(t2)

        // t1 is stale even though it was the first attempt for identityA
        assertFalse(store.markLive(t1))
        assertFalse(store.markRetrying(t1))
        assertNull(store.publishDropped(t1, TransportDropReason.SERVICE_DESTROYED))
        assertFalse(store.acknowledgeRecovery(t1))
    }

    // ═════════════════════════════════════════════════════════════════════
    // M0‑C3 — Rejected recovery restores the same drop ticket
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `M0-C3 rejected recovery restores exact ticket`() {
        val (t1, drop1) = droppedLiveAttempt()

        // Recovery attempt captures the ticket
        val t2 = beginAttempt()
        assertSame("recoveryTicket is the original drop ticket", drop1, t2.recoveryTicket)

        // Rejected — publishDropped restores exact ticket
        val drop2 = store.publishDropped(t2, TransportDropReason.BACKGROUND_RECONNECT_REFUSED)
        assertNotNull(drop2)
        assertSame("exact ticket instance restored", drop1, drop2)
        assertEquals(drop1.dropId, drop2!!.dropId)
        assertEquals(drop1.identity, drop2.identity)
        assertEquals(drop1.reason, drop2.reason)
    }

    @Test
    fun `M0-C3 rejected recovery preserves ticket across multiple rejection cycles`() {
        val (t1, drop1) = droppedLiveAttempt()

        // Cycle 1: recover → reject → ticket restored
        val t2 = beginAttempt()
        assertSame(drop1, t2.recoveryTicket)
        assertSame(drop1, store.publishDropped(t2, TransportDropReason.BACKGROUND_RECONNECT_REFUSED))

        // Cycle 2: recover → reject → same ticket restored
        val t3 = beginAttempt()
        assertSame(drop1, t3.recoveryTicket)
        val drop3 = store.publishDropped(t3, TransportDropReason.OWNER_MISSING)
        assertSame("still the same ticket instance after 2nd rejection", drop1, drop3)
    }

    // ═════════════════════════════════════════════════════════════════════
    // M0‑C4 — Live is the only sseConnected=true state;
    //         markLive does NOT clear recovery ticket
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `M0-C4 sseConnected is false for Stopped`() {
        assertFalse(store.sseConnectedFlow.value)
    }

    @Test
    fun `M0-C4 sseConnected is false for Connecting`() {
        beginAttempt()
        assertFalse(store.sseConnectedFlow.value)
    }

    @Test
    fun `M0-C4 sseConnected is true for Live`() {
        liveAttempt()
        assertTrue(store.sseConnectedFlow.value)
    }

    @Test
    fun `M0-C4 sseConnected is false for Retrying`() {
        val t1 = liveAttempt()
        store.markRetrying(t1)
        assertFalse(store.sseConnectedFlow.value)
    }

    @Test
    fun `M0-C4 sseConnected is false for Dropped`() {
        val (_, _) = droppedLiveAttempt()
        assertFalse(store.sseConnectedFlow.value)
    }

    @Test
    fun `M0-C4 sseConnected flips atomically with every state transition`() {
        fun assertProjection() {
            assertEquals(
                "sseConnected must be exactly the projection of state",
                store.state.value is SseTransportState.Live,
                store.sseConnectedFlow.value,
            )
        }

        assertProjection()
        val t1 = beginAttempt()
        assertProjection()
        store.markLive(t1)
        assertProjection()
        store.markRetrying(t1)
        assertProjection()
        store.markLive(t1)
        assertProjection()
        store.publishDropped(t1, TransportDropReason.SERVICE_DESTROYED)
        assertProjection()

        val t2 = beginAttempt()
        assertProjection()
        store.markLive(t2)
        assertProjection()
        store.acknowledgeRecovery(t2)
        assertProjection()
        store.publishDropped(t2, TransportDropReason.RETRY_EXHAUSTED)
        assertProjection()

        val t3 = beginAttempt()
        store.markStopped(t3)
        assertProjection()
    }

    @Test
    fun `M0-C4 markLive retains recovery ticket`() {
        val (t1, drop) = droppedLiveAttempt()

        val t2 = beginAttempt()
        assertSame(drop, t2.recoveryTicket)

        store.markLive(t2)
        // Internal canonical attempt still has recoveryTicket
        assertTrue(store.state.value is SseTransportState.Live)
        val liveState = store.state.value as SseTransportState.Live
        assertSame("recovery ticket retained", drop, liveState.attempt.recoveryTicket)
        assertTrue(store.sseConnectedFlow.value)
    }

    // ═════════════════════════════════════════════════════════════════════
    // M0‑C5 — identity mismatch cannot mutate state
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `M0-C5 beginAttempt with wrong identity while Live`() {
        liveAttempt(identityA)
        assertNull(store.beginAttempt(identityB))
    }

    @Test
    fun `M0-C5 beginAttempt with wrong identity while Dropped`() {
        droppedLiveAttempt(identityA)
        assertNull(store.beginAttempt(identityB))
    }

    @Test
    fun `M0-C5 markLive with wrong identity`() {
        val t1 = beginAttempt(identityA)
        assertFalse(store.markLive(t1.copy(identity = identityB)))
    }

    @Test
    fun `M0-C5 publishDropped with wrong identity`() {
        val t1 = liveAttempt(identityA)
        assertNull(store.publishDropped(t1.copy(identity = identityB),
            TransportDropReason.SERVICE_DESTROYED))
    }

    @Test
    fun `M0-C5 markStopped with wrong identity`() {
        val t1 = liveAttempt(identityA)
        assertFalse(store.markStopped(t1.copy(identity = identityB)))
    }

    @Test
    fun `M0-C5 acknowledgeRecovery with wrong identity`() {
        val (t1, _) = droppedLiveAttempt(identityA)
        val t2 = beginAttempt(identityA)
        store.markLive(t2)
        assertFalse(store.acknowledgeRecovery(t2.copy(identity = identityB)))
    }

    // ═════════════════════════════════════════════════════════════════════
    // M0‑C6 — only acknowledgeRecovery(current Live attempt) clears a
    //         recovery ticket
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `M0-C6 acknowledgeRecovery clears recovery ticket`() {
        val (t1, drop) = droppedLiveAttempt()
        val t2 = beginAttempt()
        store.markLive(t2)
        assertSame(drop, (store.state.value as SseTransportState.Live).attempt.recoveryTicket)

        assertTrue(store.acknowledgeRecovery(t2))

        val liveCanonical = (store.state.value as SseTransportState.Live).attempt
        assertNull("recovery ticket cleared", liveCanonical.recoveryTicket)
        assertTrue(store.sseConnectedFlow.value)
    }

    @Test
    fun `M0-C6 acknowledgeRecovery fails for Connecting`() {
        val (_, drop) = droppedLiveAttempt()
        val attempt = beginAttempt()
        val before = store.state.value

        assertSame(drop, attempt.recoveryTicket)
        assertFalse(store.acknowledgeRecovery(attempt))
        assertEquals(before, store.state.value)
        assertSame(
            "Connecting recovery ticket is unchanged",
            drop,
            (store.state.value as SseTransportState.Connecting).attempt.recoveryTicket,
        )
    }

    @Test
    fun `M0-C6 acknowledgeRecovery fails for Retrying`() {
        val (_, drop) = droppedLiveAttempt()
        val attempt = beginAttempt()
        assertTrue(store.markLive(attempt))
        assertTrue(store.markRetrying(attempt))
        val before = store.state.value

        assertSame(drop, (before as SseTransportState.Retrying).attempt.recoveryTicket)
        assertFalse(store.acknowledgeRecovery(attempt))
        assertEquals(before, store.state.value)
        assertSame(
            "Retrying recovery ticket is unchanged",
            drop,
            (store.state.value as SseTransportState.Retrying).attempt.recoveryTicket,
        )
    }

    @Test
    fun `M0-C6 acknowledgeRecovery fails for Dropped`() {
        val (t1, _) = droppedLiveAttempt()
        assertFalse(store.acknowledgeRecovery(t1))
    }

    @Test
    fun `M0-C6 acknowledgeRecovery fails for stale attempt`() {
        val (t1, _) = droppedLiveAttempt()
        val t2 = beginAttempt()
        store.markLive(t2)
        assertFalse("t1 is stale", store.acknowledgeRecovery(t1))
    }

    @Test
    fun `M0-C6 acknowledgeRecovery fails when no recovery ticket exists`() {
        val t1 = liveAttempt()
        assertNull(t1.recoveryTicket)
        assertFalse(store.acknowledgeRecovery(t1))
    }

    @Test
    fun `M0-C6 boolean projection emits no duplicate true on recovery acknowledgement`() = runBlocking {
        val firstAttempt = liveAttempt()
        store.publishDropped(firstAttempt, TransportDropReason.SERVICE_DESTROYED)
        val emissions = mutableListOf<Boolean>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            store.sseConnectedFlow.toList(emissions)
        }

        yield()
        val recoveryAttempt = beginAttempt()
        yield()
        assertTrue(store.markLive(recoveryAttempt))
        yield()
        assertTrue(store.acknowledgeRecovery(recoveryAttempt))
        yield()
        assertTrue(store.markRetrying(recoveryAttempt))
        yield()
        collector.cancelAndJoin()

        assertEquals("only state truth changes emit", listOf(false, true, false), emissions)
    }

    // ═════════════════════════════════════════════════════════════════════
    // Post‑ack lifecycle — canonical attempt rule
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `post-ack markRetrying does not resurrect cleared ticket`() {
        val (t1, drop) = droppedLiveAttempt()
        val t2 = beginAttempt()
        store.markLive(t2)
        store.acknowledgeRecovery(t2) // ticket cleared in canonical attempt

        // markRetrying uses the canonical attempt (no recoveryTicket)
        assertTrue(store.markRetrying(t2))
        val retryingAttempt = (store.state.value as SseTransportState.Retrying).attempt
        assertNull("retrying canonical attempt has no ticket", retryingAttempt.recoveryTicket)
    }

    @Test
    fun `post-ack markLive preserves cleared ticket`() {
        val (t1, drop) = droppedLiveAttempt()
        val t2 = beginAttempt()
        store.markLive(t2)
        store.acknowledgeRecovery(t2)
        // markLive from Retrying with same handle
        store.markRetrying(t2)
        assertTrue(store.markLive(t2))

        val liveAttempt = (store.state.value as SseTransportState.Live).attempt
        assertNull("ticket stays cleared after markLive", liveAttempt.recoveryTicket)
    }

    @Test
    fun `post-ack publishDropped creates fresh ticket`() {
        val (t1, originalDrop) = droppedLiveAttempt()
        val t2 = beginAttempt()
        store.markLive(t2)
        store.acknowledgeRecovery(t2) // ticket cleared

        // publishDropped uses canonical attempt's recoveryTicket = null → fresh ticket
        val freshDrop = store.publishDropped(t2, TransportDropReason.RETRY_EXHAUSTED)
        assertNotNull(freshDrop)
        assertNotSame("not the original ticket", originalDrop, freshDrop)
        assertTrue("fresh dropId ${freshDrop!!.dropId} != original ${originalDrop.dropId}",
            freshDrop.dropId != originalDrop.dropId)
        assertEquals(TransportDropReason.RETRY_EXHAUSTED, freshDrop.reason)
    }

    @Test
    fun `pre-ack publishDropped restores original ticket`() {
        val (t1, originalDrop) = droppedLiveAttempt()
        val t2 = beginAttempt()
        store.markLive(t2) // canonical still has recoveryTicket

        // publishDropped before ack → restores original ticket
        val drop2 = store.publishDropped(t2, TransportDropReason.BACKGROUND_RECONNECT_REFUSED)
        assertNotNull(drop2)
        assertSame("original ticket restored", originalDrop, drop2)
    }

    // ═════════════════════════════════════════════════════════════════════
    // M1A rollbackAttempt — recovery-rejected / attempt-rollback (I4 + I6)
    //
    // The owner's transport-timeout / background-refusal-pre-readiness /
    // same-identity-supersession paths route through rollbackAttempt so a
    // recovery attempt preserves its original Dropped ticket (I4), while a
    // fresh attempt (no recovery ticket) rolls back to Stopped (I6).
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `rollbackAttempt on a recovery attempt restores the same Dropped ticket (Dropped-begin-refuse-Dropped)`() {
        // The canonical recovery-rejected sequence (design §4.3):
        //   Dropped(ticket=7)
        //   → beginAttempt → Connecting(recoveryTicket=7)
        //   → rollbackAttempt (refusal/timeout)
        //   → Dropped(ticket=7)  [SAME ticket — demand preserved, I4]
        val (t1, originalDrop) = droppedLiveAttempt(
            reason = TransportDropReason.BACKGROUND_RECONNECT_REFUSED,
        )
        val originalDropId = originalDrop.dropId

        // Recovery attempt captures the SAME ticket.
        val recovery = beginAttempt()
        assertSame("recovery attempt captured the original ticket", originalDrop, recovery.recoveryTicket)

        // Refusal: rollbackAttempt restores the EXACT same ticket (I4).
        assertTrue("rollbackAttempt succeeds for the canonical recovery attempt", store.rollbackAttempt(recovery))

        val stateAfter = store.state.value
        assertTrue("state is Dropped after recovery rollback", stateAfter is SseTransportState.Dropped)
        val restoredTicket = (stateAfter as SseTransportState.Dropped).ticket
        assertSame("restored ticket is the EXACT same instance", originalDrop, restoredTicket)
        assertEquals("same dropId", originalDropId, restoredTicket.dropId)
        assertEquals("same identity", originalDrop.identity, restoredTicket.identity)
        assertEquals("same reason", originalDrop.reason, restoredTicket.reason)

        // Demand is observable: a supervisor re-querying the drop ticket sees
        // the SAME id (no new demand, no cleared demand).
        assertEquals(originalDropId, store.currentDropTicket(identityA)?.dropId)
    }

    @Test
    fun `rollbackAttempt on a recovery attempt preserves ticket across repeated refusal cycles`() {
        val (t1, originalDrop) = droppedLiveAttempt()

        // Cycle 1: recover → rollback → Dropped(original)
        val r1 = beginAttempt()
        assertSame(originalDrop, r1.recoveryTicket)
        assertTrue(store.rollbackAttempt(r1))

        // Cycle 2: recover → rollback → Dropped(original)
        val r2 = beginAttempt()
        assertSame("2nd recovery captured the same ticket", originalDrop, r2.recoveryTicket)
        assertTrue(store.rollbackAttempt(r2))
        assertSame(
            "still the same ticket after two refusal cycles",
            originalDrop,
            (store.state.value as SseTransportState.Dropped).ticket,
        )
    }

    @Test
    fun `rollbackAttempt on a fresh attempt (no recovery ticket) publishes Stopped, never Dropped (I6)`() {
        // A fresh Connecting attempt that never carried a recovery ticket
        // (never was a recovery) rolls back to Stopped — never a spurious
        // Dropped (I6: a fresh failed attempt carries no drop demand).
        val fresh = beginAttempt()
        assertNull("fresh attempt has no recovery ticket", fresh.recoveryTicket)

        assertTrue(store.rollbackAttempt(fresh))
        assertTrue("fresh rollback → Stopped", store.state.value is SseTransportState.Stopped)
        // No drop ticket observable for this identity.
        assertNull("no spurious Dropped demand for a fresh rollback", store.currentDropTicket(identityA))
    }

    @Test
    fun `rollbackAttempt on a Live attempt without recovery ticket publishes Stopped`() {
        val live = liveAttempt()
        assertNull(live.recoveryTicket)

        assertTrue(store.rollbackAttempt(live))
        assertTrue(store.state.value is SseTransportState.Stopped)
    }

    @Test
    fun `rollbackAttempt on a Live RECOVERY attempt (not yet acknowledged) restores the ticket`() {
        // A recovery attempt that reached Live but was NOT acknowledged (ticket
        // still attached) — a timeout/supersession must restore the ticket.
        val (_, originalDrop) = droppedLiveAttempt()
        val recovery = beginAttempt()
        assertSame(originalDrop, recovery.recoveryTicket)
        assertTrue(store.markLive(recovery)) // Live, ticket still attached

        assertTrue(store.rollbackAttempt(recovery))
        val dropped = store.state.value as SseTransportState.Dropped
        assertSame("ticket restored from an un-acknowledged Live recovery", originalDrop, dropped.ticket)
    }

    @Test
    fun `rollbackAttempt rejects a stale token (superseded attempt) - no state change`() {
        val t1 = liveAttempt()
        // Supersede: a new attempt becomes canonical.
        store.markStopped(t1)
        val t2 = beginAttempt()

        // t1 is now stale.
        assertFalse("stale token rollback rejected", store.rollbackAttempt(t1))
        assertEquals("state unchanged (still t2's Connecting)", t2.attemptId,
            (store.state.value as SseTransportState.Connecting).attempt.attemptId)
    }

    @Test
    fun `rollbackAttempt rejects a foreign identity - no state change`() {
        val t1 = beginAttempt(identityA)
        assertFalse("foreign identity rollback rejected",
            store.rollbackAttempt(t1.copy(identity = identityB)))
        assertTrue("state unchanged", store.state.value is SseTransportState.Connecting)
    }

    @Test
    fun `rollbackAttempt on a Dropped state (already terminal) rejects - no double-restore`() {
        val (_, originalDrop) = droppedLiveAttempt()
        // State is already Dropped. A recovery attempt that already published
        // Dropped, then a late rollback on the same (now-stale) token must be
        // a no-op (no state change, no spurious ticket churn).
        val recovery = beginAttempt()
        store.publishDropped(recovery, TransportDropReason.RETRY_EXHAUSTED)
        assertTrue(store.state.value is SseTransportState.Dropped)

        assertFalse("rollback on already-Dropped (stale token) rejected", store.rollbackAttempt(recovery))
    }

    // ═════════════════════════════════════════════════════════════════════
    // Linearization — concurrent race tests
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `concurrent beginAttempt — only one succeeds`() {
        val nThreads = 8
        val readyLatch = CountDownLatch(nThreads)
        val startLatch = CountDownLatch(1)
        val nonNullCount = AtomicInteger(0)
        val uncaught = ConcurrentLinkedQueue<Throwable>()
        val threads = List(nThreads) {
            Thread {
                try {
                    readyLatch.countDown()
                    startLatch.await()
                    val result = store.beginAttempt(identityA)
                    if (result != null) nonNullCount.incrementAndGet()
                } catch (e: InterruptedException) {
                    uncaught.add(e)
                }
            }
        }

        threads.forEach { it.start() }
        readyLatch.await()
        startLatch.countDown()
        threads.forEach { it.join() }

        assertTrue("no uncaught exceptions: $uncaught", uncaught.isEmpty())
        assertEquals("exactly one beginAttempt succeeds", 1, nonNullCount.get())
    }

    @Test
    fun `concurrent beginAttempt with different identities are independent`() {
        val nPerIdentity = 4
        val latch = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<Triple<String, TransportAttemptToken?, Throwable?>>()
        val threads = mutableListOf<Thread>()

        for (i in 0 until nPerIdentity) {
            threads.add(Thread {
                latch.await()
                val id = ConnectionIdentity(100L + i, "sg$i", "/wd$i", "ep$i")
                try {
                    results.add(Triple("A-$i", store.beginAttempt(id), null))
                } catch (e: Throwable) {
                    results.add(Triple("A-$i", null, e))
                }
            })
        }

        threads.forEach { it.start() }
        latch.countDown()
        threads.forEach { it.join() }

        // Exactly one of the different identities should have succeeded
        val nonNull = results.filter { it.second != null }
        assertEquals("exactly one identity owns the runtime", 1, nonNull.size)
    }

    @Test
    fun `linearized stop — concurrent operations after stop all fail`() {
        val t1 = liveAttempt()
        // Stop the runtime
        assertTrue(store.markStopped(t1))

        val latch = CountDownLatch(1)
        val outcomes = ConcurrentLinkedQueue<String>()
        val threads = listOf(
            Thread {
                latch.await()
                outcomes.add("markLive:${store.markLive(t1)}")
            },
            Thread {
                latch.await()
                outcomes.add("markRetrying:${store.markRetrying(t1)}")
            },
            Thread {
                latch.await()
                outcomes.add("drop:${store.publishDropped(t1, TransportDropReason.SERVICE_DESTROYED)}")
            },
            Thread {
                latch.await()
                outcomes.add("ack:${store.acknowledgeRecovery(t1)}")
            },
        )

        threads.forEach { it.start() }
        latch.countDown()
        threads.forEach { it.join() }

        // All should be false/null
        outcomes.forEach { outcome ->
            val value = outcome.substringAfter(':')
            assertTrue(
                "after stop every mutation on stale token must be false/null, got $outcome",
                value == "false" || value == "null",
            )
        }
    }

    @Test
    fun `concurrent ack and drop — legal final ordering`() {
        // Establish a recovery attempt in Live state
        val (t1, originalDrop) = droppedLiveAttempt()
        val t2 = beginAttempt()
        store.markLive(t2) // canonical has recoveryTicket

        val readyLatch = CountDownLatch(2)
        val startLatch = CountDownLatch(1)
        val ackWon = AtomicInteger(-1)  // -1=not run, 0=false, 1=true
        val dropTicket = arrayOfNulls<TransportDropTicket>(1)

        val ackThread = Thread {
            try {
                readyLatch.countDown()
                startLatch.await()
                if (store.acknowledgeRecovery(t2)) ackWon.set(1) else ackWon.set(0)
            } catch (_: InterruptedException) { }
        }
        val dropThread = Thread {
            try {
                readyLatch.countDown()
                startLatch.await()
                dropTicket[0] = store.publishDropped(t2, TransportDropReason.SERVICE_DESTROYED)
            } catch (_: InterruptedException) { }
        }

        ackThread.start()
        dropThread.start()
        readyLatch.await()
        startLatch.countDown()
        ackThread.join()
        dropThread.join()

        val didAck = ackWon.get() == 1
        val didDrop = dropTicket[0] != null

        // Both may win (linearization: ack → drop). At least one must win.
        // Both may lose only if the handle is stale (shouldn't happen here).
        assertTrue("at least one succeeds", didAck || didDrop)

        val finalState = store.state.value
        if (didDrop) {
            // drop happened last (possibly after ack)
            assertTrue("final state is Dropped", finalState is SseTransportState.Dropped)
            if (didAck) {
                // Linearization: ack → drop. Ordering ack cleared ticket, then
                // drop created a fresh ticket (not the original).
                assertTrue("ticket from fresh drop has new dropId",
                    (finalState as SseTransportState.Dropped).ticket.dropId != originalDrop.dropId)
            } else {
                // drop happened first → dropped with restored original ticket
                assertTrue("ticket restored",
                    (finalState as SseTransportState.Dropped).ticket.dropId == originalDrop.dropId)
            }
        } else {
            // only ack won
            assertTrue("final state is Live", finalState is SseTransportState.Live)
            assertNull("recovery ticket cleared",
                (finalState as SseTransportState.Live).attempt.recoveryTicket)
        }
    }

    @Test
    fun `concurrent stop and live cannot resurrect after stop`() {
        val attempt = beginAttempt()
        val readyLatch = CountDownLatch(2)
        val startLatch = CountDownLatch(1)
        val stopResult = AtomicInteger(-1)
        val liveResult = AtomicInteger(-1)

        val stopThread = Thread {
            readyLatch.countDown()
            startLatch.await()
            stopResult.set(if (store.markStopped(attempt)) 1 else 0)
        }
        val liveThread = Thread {
            readyLatch.countDown()
            startLatch.await()
            liveResult.set(if (store.markLive(attempt)) 1 else 0)
        }

        stopThread.start()
        liveThread.start()
        readyLatch.await()
        startLatch.countDown()
        stopThread.join()
        liveThread.join()

        assertTrue("stop must linearize successfully", stopResult.get() == 1)
        assertTrue("a linearized stop cannot be resurrected", store.state.value is SseTransportState.Stopped)
        assertFalse(store.sseConnectedFlow.value)
        if (liveResult.get() == 1) {
            assertTrue("stop linearized after live", stopResult.get() == 1)
        }
    }

    @Test
    fun `concurrent stop and drop — legal final ordering`() {
        val t1 = liveAttempt()
        val readyLatch = CountDownLatch(2)
        val startLatch = CountDownLatch(1)
        val stopWon = AtomicInteger(-1)
        val dropResult = arrayOfNulls<Any?>(1)

        val stopThread = Thread {
            try {
                readyLatch.countDown()
                startLatch.await()
                if (store.markStopped(t1)) stopWon.set(1) else stopWon.set(0)
            } catch (_: InterruptedException) { }
        }
        val dropThread = Thread {
            try {
                readyLatch.countDown()
                startLatch.await()
                dropResult[0] = store.publishDropped(t1, TransportDropReason.SERVICE_DESTROYED)
            } catch (_: InterruptedException) { }
        }

        stopThread.start()
        dropThread.start()
        readyLatch.await()
        startLatch.countDown()
        stopThread.join()
        dropThread.join()

        val didStop = stopWon.get() == 1
        val didDrop = dropResult[0] != null

        // Stop and drop are mutually exclusive (stop→Stopped, drop→Dropped).
        // Exactly one must succeed.
        assertTrue("stop and drop are mutually exclusive", didStop xor didDrop)
    }

    // ═════════════════════════════════════════════════════════════════════
    // Edge cases and transition sanity
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `initial state is Stopped`() {
        assertTrue(store.state.value is SseTransportState.Stopped)
        assertFalse(store.sseConnectedFlow.value)
    }

    @Test
    fun `beginAttempt on same identity while already active returns null`() {
        beginAttempt(identityA)
        assertNull(store.beginAttempt(identityA))
    }

    @Test
    fun `markStopped from Connecting`() {
        val t1 = beginAttempt()
        assertTrue(store.markStopped(t1))
        assertTrue(store.state.value is SseTransportState.Stopped)
    }

    @Test
    fun `markStopped from Live`() {
        val t1 = liveAttempt()
        assertTrue(store.markStopped(t1))
        assertTrue(store.state.value is SseTransportState.Stopped)
        assertFalse(store.sseConnectedFlow.value)
    }

    @Test
    fun `markRetrying from Live`() {
        val t1 = liveAttempt()
        assertTrue(store.markRetrying(t1))
        assertTrue(store.state.value is SseTransportState.Retrying)
        assertFalse(store.sseConnectedFlow.value)
    }

    @Test
    fun `markRetrying fails from Connecting`() {
        assertFalse(store.markRetrying(beginAttempt()))
    }

    @Test
    fun `publishDropped creates fresh ticket when canonical has no recoveryTicket`() {
        val t1 = liveAttempt()
        assertNull(t1.recoveryTicket)

        val drop = store.publishDropped(t1, TransportDropReason.SERVICE_DESTROYED)
        assertNotNull(drop)
        assertTrue("drop ID >= 1", drop!!.dropId >= 1)
        assertEquals(identityA, drop.identity)
        assertEquals(TransportDropReason.SERVICE_DESTROYED, drop.reason)
    }

    @Test
    fun `currentAttempt returns null for Stopped`() {
        assertNull(store.currentAttempt(identityA))
    }

    @Test
    fun `currentAttempt returns canonical attempt for Live`() {
        val t1 = liveAttempt()
        val current = store.currentAttempt(identityA)
        assertNotNull(current)
        assertEquals(t1.attemptId, current!!.attemptId)
    }

    @Test
    fun `currentAttempt returns null for wrong identity`() {
        liveAttempt(identityA)
        assertNull(store.currentAttempt(identityB))
    }

    @Test
    fun `currentAttempt returns null for Dropped`() {
        val (t1, _) = droppedLiveAttempt()
        assertNull(store.currentAttempt(identityA))
    }

    @Test
    fun `currentDropTicket returns ticket for Dropped`() {
        val (_, drop) = droppedLiveAttempt()
        val ticket = store.currentDropTicket(identityA)
        assertNotNull(ticket)
        assertEquals(drop.dropId, ticket!!.dropId)
    }

    @Test
    fun `currentDropTicket returns null for wrong identity`() {
        droppedLiveAttempt(identityA)
        assertNull(store.currentDropTicket(identityB))
    }

    @Test
    fun `currentDropTicket returns null for non-Dropped states`() {
        assertNull(store.currentDropTicket(identityA)) // Stopped
        beginAttempt()
        assertNull(store.currentDropTicket(identityA)) // Connecting
    }

    @Test
    fun `markStopped is idempotent after first stop`() {
        val t1 = liveAttempt()
        assertTrue(store.markStopped(t1))
        assertFalse("second markStopped on same token fails", store.markStopped(t1))
    }

    @Test
    fun `can begin new attempt after stop with different identity`() {
        val t1 = liveAttempt(identityA)
        store.markStopped(t1)

        // identityB can now begin
        val t2 = store.beginAttempt(identityB)
        assertNotNull(t2)
        assertEquals(identityB, t2!!.identity)
    }
}
