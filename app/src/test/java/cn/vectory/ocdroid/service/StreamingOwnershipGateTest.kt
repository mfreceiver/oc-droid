package cn.vectory.ocdroid.service

import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.streaming.TransportAttemptToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.joinAll
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * L2 §7: tests for the [StreamingOwnershipGate] — single-slot ABA-safe
 * identity lease arbiter.
 *
 * Scenarios G1–G5 per oracle §7:
 *  - G1: ABA — same-identity reconnect, late old-token release rejected.
 *  - G2: max-1 invariant under concurrent competing claims.
 *  - G3: disconnectAndRelease extracts + invokes teardown exactly once.
 *  - G4: transfer precondition rules.
 *  - G5: different-identity claim blocked while held; succeeds after release.
 */
class StreamingOwnershipGateTest {

    private fun id(epoch: Long = 1, workdir: String = "/proj"): ConnectionIdentity =
        ConnectionIdentity(epoch = epoch, profileId = "fp", normalizedWorkdir = workdir, endpointFp = "ep")

    private fun tok(attemptId: Long, identity: ConnectionIdentity): TransportAttemptToken =
        TransportAttemptToken(attemptId = attemptId, identity = identity, recoveryTicket = null)

    // G1 — MANDATORY ABA: same-identity reconnect, late release of the old token.
    @Test
    fun `G1 - same-identity reconnect late release of old token rejected, new lease unaffected`() {
        val gate = StreamingOwnershipGate()
        val identity = id(epoch = 1)
        val attemptA = tok(1, identity)
        val attemptB = tok(2, identity)
        val tokenA = gate.claim(attemptA) {}!!
        // Reconnect: takeover claim (same identity, strictly newer attemptId).
        val tokenB = gate.claim(attemptB) {}!!
        assertTrue(gate.isCurrent(tokenB))
        // The dying old connection's late release MUST NOT release the new lease.
        assertFalse("late release(oldToken) MUST be rejected (ABA guard)", gate.releaseNow(tokenA))
        assertTrue("new lease still current after late old release", gate.isCurrent(tokenB))
        assertEquals(tokenB, gate.currentToken())
    }

    // G2 — max-1 invariant under concurrent competing claims.
    @Test
    fun `G2 - concurrent claims exactly one primary, different identities`() = runTest {
        val gate = StreamingOwnershipGate()
        val winners = ConcurrentLinkedQueue<LeaseToken>()
        val jobs = (1..32).map { n ->
            launch(Dispatchers.Default) {
                val identity = id(epoch = n.toLong(), workdir = "/p$n")   // distinct identities
                gate.claim(tok(n.toLong(), identity)) {}?.let { winners += it }
            }
        }
        jobs.joinAll()
        assertEquals("exactly one claim granted (max-1 invariant)", 1, winners.size)
        assertEquals(winners.single(), gate.currentToken())
    }

    // G3 — disconnectAndRelease extracts + invokes teardown exactly once; second call no-ops.
    @Test
    fun `G3 - disconnectAndRelease teardown invoked once, re-entrant call no-op`() = runTest {
        val gate = StreamingOwnershipGate()
        var teardownCalls = 0
        gate.claim(tok(1, id(1))) { teardownCalls++ }
        gate.disconnectAndRelease(markGap = true)
        gate.disconnectAndRelease(markGap = true)
        assertEquals(1, teardownCalls)
        assertNull(gate.currentToken())
    }

    // G4 — transfer precondition: stale holder cannot transfer; current holder can.
    @Test
    fun `G4 - transfer requires current holdership, same identity, newer attemptId`() {
        val gate = StreamingOwnershipGate()
        val identity = id(1)
        val a = tok(1, identity); val b = tok(2, identity); val c = tok(3, identity)
        val tokenA = gate.claim(a) {}!!
        assertNull("non-holder transfer rejected", gate.transfer(LeaseToken(999, identity), b) {})
        assertNull("non-increasing attemptId rejected", gate.transfer(tokenA, a) {})
        val tokenB = gate.transfer(tokenA, b) {}!!
        assertTrue(gate.isCurrent(tokenB))
        assertNull("stale oldToken after transfer rejected", gate.transfer(tokenA, c) {})
    }

    // G5 — different-identity claim rejected while lease live; succeeds after release.
    @Test
    fun `G5 - competing identity reject while held, grant after release`() {
        val gate = StreamingOwnershipGate()
        val tokenA = gate.claim(tok(1, id(1, "/a"))) {}!!
        assertNull("different-identity claim rejected while held", gate.claim(tok(2, id(2, "/b"))) {})
        assertTrue(gate.releaseNow(tokenA))
        assertNotNull("claim succeeds after release", gate.claim(tok(3, id(2, "/b"))) {})
    }
}
