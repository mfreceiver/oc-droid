package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.state.AuthorityState
import cn.vectory.ocdroid.data.state.EntryOrigin
import cn.vectory.ocdroid.data.state.Freshness
import cn.vectory.ocdroid.data.state.OptimisticClaim
import cn.vectory.ocdroid.data.state.ScopeKey
import cn.vectory.ocdroid.data.state.SessionEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §P0-B ITEM 4: tests for the pure watchdog function
 * [selectStaleClaimsForReconcile].
 */
class OptimisticClaimWatchdogTest {

    private val scope = ScopeKey(serverGroupFp = "grp", endpointFp = "ep")

    private fun entry(
        status: String = "busy",
        claim: OptimisticClaim? = null,
        scopeKey: ScopeKey = scope,
    ) = SessionEntry(
        status = SessionStatus(type = status),
        serverRound = null,
        optimisticClaim = claim,
        origin = EntryOrigin.OPTIMISTIC,
        freshness = Freshness.Fresh,
        updatedMonotonic = 0L,
        workdir = null,
        scopeKey = scopeKey,
    )

    @Test
    fun `selectStaleClaimsForReconcile returns stale unconfirmed claim`() {
        // Stale claim: claimedAtMonotonic=0, now=6001, timeout=5000 → age=6001 > 5000
        val staleClaim = OptimisticClaim(
            clientSeq = 1L,
            claimedAtMonotonic = 0L,
            serverEchoed = false,
            guardedIdleDrop = false,
        )
        val auth = AuthorityState(bySid = mapOf(
            "sid-stale" to entry(claim = staleClaim),
        ))
        val result = selectStaleClaimsForReconcile(auth, now = 6001L, timeoutMs = 5000L)
        assertEquals("one stale claim returned", 1, result.size)
        assertEquals("sid matches", "sid-stale", result[0].sid)
        assertEquals("scopeKey matches", scope, result[0].scopeKey)
    }

    @Test
    fun `selectStaleClaimsForReconcile does NOT return fresh claims`() {
        // Fresh claim: claimedAtMonotonic=1000, now=6001, timeout=5000 → age=5001 > 5000
        // Wait, 6001 - 1000 = 5001 which IS > 5000, so this is stale!
        // Let me use a truly fresh claim.
        // Fresh claim: claimedAtMonotonic=2000, now=6001, timeout=5000 → age=4001 <= 5000
        val freshClaim = OptimisticClaim(
            clientSeq = 1L,
            claimedAtMonotonic = 2000L,
            serverEchoed = false,
            guardedIdleDrop = false,
        )
        val auth = AuthorityState(bySid = mapOf(
            "sid-fresh" to entry(claim = freshClaim),
        ))
        val result = selectStaleClaimsForReconcile(auth, now = 6001L, timeoutMs = 5000L)
        assertTrue("no stale claims for fresh entry", result.isEmpty())
    }

    @Test
    fun `selectStaleClaimsForReconcile ignores echoed claims regardless of age`() {
        // Echoed claim: very old but server confirmed
        val echoedClaim = OptimisticClaim(
            clientSeq = 1L,
            claimedAtMonotonic = 0L,
            serverEchoed = true,
            guardedIdleDrop = false,
        )
        val auth = AuthorityState(bySid = mapOf(
            "sid-echoed" to entry(claim = echoedClaim),
        ))
        val result = selectStaleClaimsForReconcile(auth, now = 10000L, timeoutMs = 5000L)
        assertTrue("echoed claim ignored", result.isEmpty())
    }

    @Test
    fun `selectStaleClaimsForReconcile skips entries without claim`() {
        val auth = AuthorityState(bySid = mapOf(
            "sid-no-claim" to entry(claim = null),
        ))
        val result = selectStaleClaimsForReconcile(auth, now = 10000L, timeoutMs = 5000L)
        assertTrue("entry without claim ignored", result.isEmpty())
    }

    @Test
    fun `selectStaleClaimsForReconcile boundary - exact timeout does NOT trigger`() {
        val claim = OptimisticClaim(
            clientSeq = 1L,
            claimedAtMonotonic = 0L,
            serverEchoed = false,
            guardedIdleDrop = false,
        )
        val auth = AuthorityState(bySid = mapOf(
            "sid" to entry(claim = claim),
        ))
        // now=5000, timeout=5000 → age=5000, NOT > 5000 → not stale
        val result = selectStaleClaimsForReconcile(auth, now = 5000L, timeoutMs = 5000L)
        assertTrue("exact timeout boundary does not trigger", result.isEmpty())
    }

    @Test
    fun `selectStaleClaimsForReconcile boundary - just over timeout triggers`() {
        val claim = OptimisticClaim(
            clientSeq = 1L,
            claimedAtMonotonic = 0L,
            serverEchoed = false,
            guardedIdleDrop = false,
        )
        val auth = AuthorityState(bySid = mapOf(
            "sid" to entry(claim = claim),
        ))
        // now=5001, timeout=5000 → age=5001 > 5000 → stale
        val result = selectStaleClaimsForReconcile(auth, now = 5001L, timeoutMs = 5000L)
        assertEquals("just over timeout triggers", 1, result.size)
    }

    @Test
    fun `selectStaleClaimsForReconcile mixes stale and fresh returns only stale`() {
        val staleClaim = OptimisticClaim(
            clientSeq = 1L, claimedAtMonotonic = 0L,
            serverEchoed = false, guardedIdleDrop = false,
        )
        val freshClaim = OptimisticClaim(
            clientSeq = 2L, claimedAtMonotonic = 2000L,
            serverEchoed = false, guardedIdleDrop = false,
        )
        val echoedClaim = OptimisticClaim(
            clientSeq = 3L, claimedAtMonotonic = 0L,
            serverEchoed = true, guardedIdleDrop = false,
        )
        val auth = AuthorityState(bySid = mapOf(
            "stale" to entry(claim = staleClaim),
            "fresh" to entry(claim = freshClaim),
            "echoed" to entry(claim = echoedClaim),
            "no-claim" to entry(claim = null),
        ))
        val result = selectStaleClaimsForReconcile(auth, now = 6001L, timeoutMs = 5000L)
        assertEquals("only stale claim returned", 1, result.size)
        assertEquals("correct sid", "stale", result[0].sid)
    }

    // ── §P0-B Part 2: extra SLA coverage (a), (b), (e) ──

    @Test
    fun `P0-B watchdog SLA boundary - 4999 not stale 5001 is stale`() {
        val claim = OptimisticClaim(
            clientSeq = 1L,
            claimedAtMonotonic = 0L,
            serverEchoed = false,
            guardedIdleDrop = false,
        )
        val auth = AuthorityState(bySid = mapOf(
            "A" to entry(claim = claim),
        ))
        // age = 4999, NOT > 5000 → not stale
        val before = selectStaleClaimsForReconcile(auth, now = 4_999L, timeoutMs = 5_000L)
        assertTrue("age=4999 < timeout=5000: not stale", before.isEmpty())

        // age = 5001, > 5000 → stale, with correct sid/scopeKey/clientSeq
        val after = selectStaleClaimsForReconcile(auth, now = 5_001L, timeoutMs = 5_000L)
        assertEquals("age=5001 > timeout=5000: 1 stale", 1, after.size)
        assertEquals("sid matches", "A", after[0].sid)
        assertEquals("scopeKey matches", scope, after[0].scopeKey)
        assertEquals("clientSeq matches", 1L, after[0].clientSeq)
    }

    @Test
    fun `P0-B watchdog SLA reconcileConfirmed claim never selected regardless of age`() {
        val claim = OptimisticClaim(
            clientSeq = 1L,
            claimedAtMonotonic = 0L,
            serverEchoed = false,
            reconcileConfirmed = true, // confirmed by delayed reconcile GET
            guardedIdleDrop = false,
        )
        val auth = AuthorityState(bySid = mapOf(
            "A" to entry(claim = claim),
        ))
        val result = selectStaleClaimsForReconcile(auth, now = 999_999L, timeoutMs = 5_000L)
        assertTrue("reconcileConfirmed claim never selected even at extreme age", result.isEmpty())
    }

    @Test
    fun `P0-B watchdog SLA default timeout boundary - 5000 not stale 5001 stale`() {
        val claim = OptimisticClaim(
            clientSeq = 1L,
            claimedAtMonotonic = 0L,
            serverEchoed = false,
            guardedIdleDrop = false,
        )
        val auth = AuthorityState(bySid = mapOf(
            "A" to entry(claim = claim),
        ))
        // No explicit timeoutMs → relies on default OPTIMISTIC_CONFIRM_TIMEOUT_MS = 5000
        val before = selectStaleClaimsForReconcile(auth, now = 5_000L)
        assertTrue("default timeout: now=5000, age=5000 NOT > 5000 → not stale", before.isEmpty())

        val after = selectStaleClaimsForReconcile(auth, now = 5_001L)
        assertEquals("default timeout: now=5001, age=5001 > 5000 → stale", 1, after.size)
        assertEquals("stale claim sid", "A", after[0].sid)
    }
}
