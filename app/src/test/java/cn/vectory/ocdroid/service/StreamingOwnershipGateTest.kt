package cn.vectory.ocdroid.service

import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L2 §7: tests for the simplified [StreamingOwnershipGate] — single-slot
 * identity lease.
 *
 * Scenarios G1–G5 per oracle §7:
 *  - G1: claim + release cycle.
 *  - G2: claim same identity returns a fresh token.
 *  - G3: claim different identity returns null.
 *  - G4: releaseNow with non-matching token identity is a no-op.
 *  - G5: readyIdentity and disconnectAndRelease.
 */
class StreamingOwnershipGateTest {

    private fun identity(workdir: String = "/proj"): ConnectionIdentity =
        ConnectionIdentity(epoch = 1L, profileId = "fp", normalizedWorkdir = workdir, endpointFp = "ep")

    // ── G1: claim + release cycle ──────────────────────────────────────────

    @Test
    fun `G1 - claim returns a token and releaseNow clears the lease`() {
        val gate = StreamingOwnershipGate()
        val id = identity()

        assertNull("no lease before claim", gate.readyIdentity())

        val token = gate.claim(id)
        assertNotNull("claim returns token", token)
        assertEquals("leased identity matches", id, gate.readyIdentity())

        gate.releaseNow(token!!)
        assertNull("lease released after releaseNow", gate.readyIdentity())
    }

    // ── G1b: claim returns a token and suspended release clears the lease ───

    @Test
    fun `G1b - suspended release clears the lease`() = runTest {
        val gate = StreamingOwnershipGate()
        val id = identity()
        val token = gate.claim(id)
        assertNotNull("claim returns token", token)
        assertEquals("leased", id, gate.readyIdentity())

        gate.release(token!!)
        assertNull("lease released after suspended release", gate.readyIdentity())
    }

    // ── G2: claim same identity returns a fresh token ───────────────────────

    @Test
    fun `G2 - claim same identity returns a token and keeps the lease`() {
        val gate = StreamingOwnershipGate()
        val id = identity()
        val token1 = gate.claim(id)
        assertNotNull("first claim token", token1)

        // Claim same identity again.
        val token2 = gate.claim(id)
        assertNotNull("second claim same identity returns token", token2)
        assertTrue("second token has different leaseId", token2!!.leaseId != token1!!.leaseId)
        assertEquals("identity still held", id, gate.readyIdentity())

        // Releasing with either token clears the lease.
        gate.releaseNow(token2)
        assertNull("lease released after second token release", gate.readyIdentity())
    }

    // ── G3: claim different identity returns null ───────────────────────────

    @Test
    fun `G3 - claim different identity returns null`() {
        val gate = StreamingOwnershipGate()
        val idA = identity("/a")
        val idB = identity("/b")

        val tokenA = gate.claim(idA)
        assertNotNull("first claim succeeds", tokenA)
        assertEquals("identity A holds the lease", idA, gate.readyIdentity())

        val tokenB = gate.claim(idB)
        assertNull("claim different identity returns null", tokenB)
        assertEquals("identity A still holds the lease", idA, gate.readyIdentity())
    }

    // ── G4: releaseNow with non-matching identity is no-op ──────────────────

    @Test
    fun `G4 - releaseNow with non-matching token identity is a no-op`() {
        val gate = StreamingOwnershipGate()
        val idA = identity("/a")
        val idB = identity("/b")

        val tokenA = gate.claim(idA)
        assertNotNull("claim succeeds", tokenA)
        assertEquals("identity A holds the lease", idA, gate.readyIdentity())

        // Attempt to release with a LeaseToken for identity B. Since A holds
        // the lease, this should be a no-op.
        gate.releaseNow(LeaseToken(leaseId = 999L, identity = idB))
        assertEquals("lease still held by A after mismatched release", idA, gate.readyIdentity())

        // Correct release with identity A's token succeeds.
        gate.releaseNow(tokenA!!)
        assertNull("lease released with matching token", gate.readyIdentity())
    }

    // ── G5: readyIdentity and disconnectAndRelease ──────────────────────────

    @Test
    fun `G5a - readyIdentity returns current identity`() {
        val gate = StreamingOwnershipGate()
        assertNull("readyIdentity null when no lease", gate.readyIdentity())

        val id = identity()
        gate.claim(id)
        assertEquals("readyIdentity returns leased identity", id, gate.readyIdentity())
    }

    @Test
    fun `G5b - disconnectAndRelease clears the lease`() = runTest {
        val gate = StreamingOwnershipGate()
        val id = identity()
        gate.claim(id)
        assertEquals("lease held before disconnectAndRelease", id, gate.readyIdentity())

        gate.disconnectAndRelease(markGap = true)
        assertNull("lease cleared after disconnectAndRelease", gate.readyIdentity())
    }

    @Test
    fun `G5c - disconnectAndRelease with markGap false also clears`() = runTest {
        val gate = StreamingOwnershipGate()
        val id = identity()
        gate.claim(id)
        assertEquals("lease held", id, gate.readyIdentity())

        gate.disconnectAndRelease(markGap = false)
        assertNull("lease cleared after disconnectAndRelease(markGap=false)", gate.readyIdentity())
    }

    @Test
    fun `G5d - releaseNow with ConnectionIdentity overload releases`() {
        val gate = StreamingOwnershipGate()
        val id = identity()
        gate.claim(id)
        assertEquals("lease held", id, gate.readyIdentity())

        gate.releaseNow(id)
        assertNull("lease cleared after releaseNow(identity)", gate.readyIdentity())
    }

    @Test
    fun `G5e - releaseNow with wrong identity overload is a no-op`() {
        val gate = StreamingOwnershipGate()
        val idA = identity("/a")
        val idB = identity("/b")
        gate.claim(idA)

        gate.releaseNow(idB)
        assertEquals("lease still held by A", idA, gate.readyIdentity())
    }
}
