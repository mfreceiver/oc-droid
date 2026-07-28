package cn.vectory.ocdroid.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * B-4 HIGH-2: verifies the concurrent-safe per-session bounded revision ledger
 * with terminal tombstone semantics (rev-gpt fix).
 *
 * The [PartRevisionLedger] replaces the raw `ConcurrentHashMap<String, Long>`
 * that was used in the original production hooks. The key differences:
 *  - terminal (done/truncated) frames set a tombstone instead of removing the
 *    entry, so ALL revisions (same, lower, higher, null) after terminal are
 *    rejected — no part resurrection.
 *  - per-session capacity capped at 32 entries; overflow is fail-closed.
 *    The cap is HARD under concurrent distinct keys (per-session mutex).
 *  - `clearSessionRevisions` is the sole mechanism to purge tombstones (at
 *    epoch/lifecycle boundaries).
 *  - nullable `partEventRevision` is now incorporated into the ledger.
 *
 * Each test calls [PartRevisionLedger] methods directly to verify the
 * admission + tombstone + capacity invariants.
 */
class PartRevisionLedgerTest {

    // ── Strict `>` dedup (baseline, same as old behavior) ──────────────────

    @Test
    fun `admit accepts first revision`() {
        val ledger = PartRevisionLedger()
        assertTrue(ledger.admit("s1", "m1", "p1", 5L))
    }

    @Test
    fun `admit rejects equal revision`() {
        val ledger = PartRevisionLedger()
        assertTrue(ledger.admit("s1", "m1", "p1", 5L))
        assertFalse(ledger.admit("s1", "m1", "p1", 5L))
    }

    @Test
    fun `admit rejects lower revision`() {
        val ledger = PartRevisionLedger()
        assertTrue(ledger.admit("s1", "m1", "p1", 10L))
        assertFalse(ledger.admit("s1", "m1", "p1", 8L))
    }

    @Test
    fun `admit accepts higher revision`() {
        val ledger = PartRevisionLedger()
        assertTrue(ledger.admit("s1", "m1", "p1", 5L))
        assertTrue(ledger.admit("s1", "m1", "p1", 6L))
        assertFalse(ledger.admit("s1", "m1", "p1", 5L)) // lower after higher
    }

    @Test
    fun `admit is isolated across part IDs`() {
        val ledger = PartRevisionLedger()
        assertTrue(ledger.admit("s1", "m1", "p1", 5L))
        assertTrue(ledger.admit("s1", "m1", "p2", 5L)) // same revision, different part
        assertFalse(ledger.admit("s1", "m1", "p1", 5L)) // p1 duplicate
        assertTrue(ledger.admit("s1", "m1", "p1", 6L)) // p1 higher
    }

    @Test
    fun `admit is isolated across sessions`() {
        val ledger = PartRevisionLedger()
        assertTrue(ledger.admit("s1", "m1", "p1", 5L))
        assertTrue(ledger.admit("s2", "m1", "p1", 5L)) // same mid/pid, different sid
        assertFalse(ledger.admit("s1", "m1", "p1", 5L)) // s1 duplicate
        assertFalse(ledger.admit("s2", "m1", "p1", 5L)) // s2 duplicate
    }

    // ── Terminal tombstone (A: terminal done → ALL revisions rejected) ────

    @Test
    fun `A terminal done rejects same revision`() {
        val ledger = PartRevisionLedger()
        assertTrue(ledger.admit("s1", "m1", "p1", 5L))
        ledger.markTerminal("s1", "m1", "p1")
        // Same revision after terminal: must be rejected (no resurrection).
        assertFalse("terminal done must reject same revision", ledger.admit("s1", "m1", "p1", 5L))
    }

    @Test
    fun `A terminal done rejects lower revision`() {
        val ledger = PartRevisionLedger()
        assertTrue(ledger.admit("s1", "m1", "p1", 10L))
        ledger.markTerminal("s1", "m1", "p1")
        assertFalse("terminal done must reject lower revision", ledger.admit("s1", "m1", "p1", 8L))
    }

    @Test
    fun `A terminal truncated rejects same revision`() {
        val ledger = PartRevisionLedger()
        assertTrue(ledger.admit("s1", "m1", "p1", 5L))
        ledger.markTerminal("s1", "m1", "p1")
        assertFalse("terminal truncated must reject same revision", ledger.admit("s1", "m1", "p1", 5L))
    }

    @Test
    fun `A terminal blocks strictly higher revision`() {
        val ledger = PartRevisionLedger()
        assertTrue(ledger.admit("s1", "m1", "p1", 5L))
        ledger.markTerminal("s1", "m1", "p1")
        // A strictly higher revision after terminal: must be REJECTED
        // (terminal tombstone prevents ALL revisions until clearSession).
        assertFalse("terminal must block strictly higher revision", ledger.admit("s1", "m1", "p1", 11L))
    }

    @Test
    fun `A terminal on one part does not affect other parts`() {
        val ledger = PartRevisionLedger()
        assertTrue(ledger.admit("s1", "m1", "p1", 5L))
        assertTrue(ledger.admit("s1", "m1", "p2", 5L))
        ledger.markTerminal("s1", "m1", "p1")
        // p1 terminal — reject
        assertFalse(ledger.admit("s1", "m1", "p1", 5L))
        // p2 should be unaffected
        assertFalse(ledger.admit("s1", "m1", "p2", 5L)) // still a dup
        assertTrue(ledger.admit("s1", "m1", "p2", 6L))  // higher revision accepted
    }

    @Test
    fun `A terminal alone does not remove entry`() {
        val ledger = PartRevisionLedger()
        assertTrue(ledger.admit("s1", "m1", "p1", 5L))
        ledger.markTerminal("s1", "m1", "p1")
        // After markTerminal, clearSession is required to reuse the slot.
        // Verify the entry still exists (test internal observation).
        assertTrue("entry must exist after markTerminal", ledger.isTerminal("s1", "m1", "p1") == true)
    }

    // ── Null revision semantics ───────────────────────────────────────────

    @Test
    fun `null revision accepted for existing non-terminal key`() {
        val ledger = PartRevisionLedger()
        assertTrue(ledger.admit("s1", "m1", "p1", 5L))
        // Null after non-null active key: fail-open compatible.
        assertTrue("null for active key must be accepted", ledger.admit("s1", "m1", "p1", null))
        // Repeated null: still accepted.
        assertTrue("repeated null for active key must be accepted", ledger.admit("s1", "m1", "p1", null))
    }

    @Test
    fun `null revision accepted for new key within cap`() {
        val ledger = PartRevisionLedger()
        // New null key should be accepted (takes a bounded slot).
        assertTrue("new null key must be accepted", ledger.admit("s1", "m1", "p1", null))
    }

    @Test
    fun `null revision accepted for existing null entry with non-null upgrade`() {
        val ledger = PartRevisionLedger()
        assertTrue(ledger.admit("s1", "m1", "p1", null))
        // First non-null upgrades the watermark.
        assertTrue("non-null after null must upgrade", ledger.admit("s1", "m1", "p1", 5L))
        // Now normal dedup applies.
        assertFalse("same revision after upgrade must be rejected", ledger.admit("s1", "m1", "p1", 5L))
        assertTrue("higher revision after upgrade must be accepted", ledger.admit("s1", "m1", "p1", 6L))
    }

    @Test
    fun `null revision rejected for terminal key`() {
        val ledger = PartRevisionLedger()
        assertTrue(ledger.admit("s1", "m1", "p1", 5L))
        ledger.markTerminal("s1", "m1", "p1")
        assertFalse("null after terminal must be rejected", ledger.admit("s1", "m1", "p1", null))
    }

    @Test
    fun `null revision counts toward capacity`() {
        val ledger = PartRevisionLedger(maxEntriesPerSession = 2)
        assertTrue(ledger.admit("s1", "m1", "p1", null)) // null takes a slot
        assertTrue(ledger.admit("s1", "m1", "p2", 1L))   // second slot
        assertFalse(ledger.admit("s1", "m1", "p3", 1L))  // cap full → reject
    }

    @Test
    fun `null revision at cap is rejected for new key`() {
        val ledger = PartRevisionLedger(maxEntriesPerSession = 2)
        assertTrue(ledger.admit("s1", "m1", "p1", 1L))
        assertTrue(ledger.admit("s1", "m2", "p2", 1L))
        // At cap, new null key must be rejected.
        assertFalse("new null key at cap must be rejected", ledger.admit("s1", "m3", "p3", null))
    }

    // ── Capacity limiting (B: 33+ parts cap + clearSession recovery) ────────

    @Test
    fun `B ledger accepts up to max parts per session`() {
        val ledger = PartRevisionLedger(maxEntriesPerSession = 4)
        assertTrue(ledger.admit("s1", "m1", "p1", 1L))
        assertTrue(ledger.admit("s1", "m2", "p2", 1L))
        assertTrue(ledger.admit("s1", "m3", "p3", 1L))
        assertTrue(ledger.admit("s1", "m4", "p4", 1L))
        // 5th distinct key in the same session: should be rejected.
        assertFalse("5th distinct key must be rejected at cap=4", ledger.admit("s1", "m5", "p5", 1L))
    }

    @Test
    fun `B cap does not affect existing keys`() {
        val ledger = PartRevisionLedger(maxEntriesPerSession = 2)
        assertTrue(ledger.admit("s1", "m1", "p1", 1L))
        assertTrue(ledger.admit("s1", "m2", "p2", 1L))
        // Existing keys still work: higher revision for existing key.
        assertTrue(ledger.admit("s1", "m1", "p1", 2L))
        // But new key rejected.
        assertFalse(ledger.admit("s1", "m3", "p3", 1L))
    }

    @Test
    fun `B cap at 32 default`() {
        val ledger = PartRevisionLedger() // default MAX_REVISIONS_PER_SESSION = 32
        // Admit 32 distinct (mid,pid) pairs.
        for (i in 1..32) {
            assertTrue("entry $i must be accepted", ledger.admit("s1", "m$i", "p$i", 1L))
        }
        // 33rd distinct key must be rejected.
        assertFalse("33rd distinct key must be rejected at cap=32", ledger.admit("s1", "m33", "p33", 1L))
    }

    @Test
    fun `B cap per-session is isolated across sessions`() {
        val ledger = PartRevisionLedger(maxEntriesPerSession = 2)
        assertTrue(ledger.admit("s1", "m1", "p1", 1L))
        assertTrue(ledger.admit("s1", "m2", "p2", 1L))
        assertFalse("s1 must be at cap", ledger.admit("s1", "m3", "p3", 1L))

        // Different session should still accept.
        assertTrue("s2 must have its own capacity", ledger.admit("s2", "m1", "p1", 1L))
        assertTrue("s2 second entry must be accepted", ledger.admit("s2", "m2", "p2", 1L))
        assertFalse("s2 must also cap at 2", ledger.admit("s2", "m3", "p3", 1L))
    }

    @Test
    fun `B clearSession restores capacity`() {
        val ledger = PartRevisionLedger(maxEntriesPerSession = 2)
        assertTrue(ledger.admit("s1", "m1", "p1", 1L))
        assertTrue(ledger.admit("s1", "m2", "p2", 1L))
        assertFalse("s1 at cap", ledger.admit("s1", "m3", "p3", 1L))

        ledger.clearSession("s1")
        assertEquals("session entry count must be 0 after clear", 0, ledger.sessionEntryCount("s1"))
        assertTrue("after clearSession, new entries must be accepted", ledger.admit("s1", "m3", "p3", 1L))
    }

    @Test
    fun `B cap full + markTerminal absent does not exceed cap`() {
        // BUG: markTerminal for an absent key must NOT create an entry,
        // otherwise a full (cap=32) ledger grows to 33.
        val cap = 32
        val ledger = PartRevisionLedger(maxEntriesPerSession = cap)
        // Fill ledger to exactly cap.
        for (i in 1..cap) {
            assertTrue("entry $i must be accepted", ledger.admit("s1", "m$i", "p$i", 1L))
        }
        assertEquals(cap, ledger.sessionEntryCount("s1"))

        // markTerminal for a key that was NEVER admitted — must be no-op.
        ledger.markTerminal("s1", "m_absent", "p_absent")
        assertEquals(
            "markTerminal on absent key must NOT increase size",
            cap,
            ledger.sessionEntryCount("s1"),
        )
        // Cap must still be enforced.
        assertFalse("cap must still reject new keys after absent markTerminal",
            ledger.admit("s1", "m_overflow", "p_overflow", 1L))
    }

    @Test
    fun `B terminal entries count toward capacity`() {
        val ledger = PartRevisionLedger(maxEntriesPerSession = 2)
        assertTrue(ledger.admit("s1", "m1", "p1", 1L))
        assertTrue(ledger.admit("s1", "m2", "p2", 1L))
        ledger.markTerminal("s1", "m1", "p1")
        // Terminal entries count toward the cap, so a 3rd distinct key is still rejected.
        assertFalse("terminal entries count toward cap", ledger.admit("s1", "m3", "p3", 1L))
    }

    // ── Remove / removeMessage (onMessagePartRemoved / onMessageRemoved) ─────

    @Test
    fun `remove frees capacity`() {
        // Cap=3 so both "m3,p3" and re-admitted "m1,p1" fit.
        val ledger = PartRevisionLedger(maxEntriesPerSession = 3)
        assertTrue(ledger.admit("s1", "m1", "p1", 1L))
        assertTrue(ledger.admit("s1", "m2", "p2", 1L))
        ledger.remove("s1", "m1", "p1")
        assertEquals("after remove, count must be 1", 1, ledger.sessionEntryCount("s1"))
        // Now a new key should be accepted.
        assertTrue("remove must free capacity", ledger.admit("s1", "m3", "p3", 1L))
        // The removed key's revision can come back (still within cap=3).
        assertTrue("removed key can be re-admitted", ledger.admit("s1", "m1", "p1", 1L))
    }

    @Test
    fun `removeMessage clears all parts for a message`() {
        val ledger = PartRevisionLedger(maxEntriesPerSession = 3)
        assertTrue(ledger.admit("s1", "m1", "p1", 1L))
        assertTrue(ledger.admit("s1", "m1", "p2", 1L))
        assertTrue(ledger.admit("s1", "m2", "p1", 1L))
        ledger.removeMessage("s1", "m1")
        // Both m1 parts should be removable → frame accepted.
        assertTrue("m1 p1 after removeMessage", ledger.admit("s1", "m1", "p1", 1L))
        assertTrue("m1 p2 after removeMessage", ledger.admit("s1", "m1", "p2", 1L))
        // m2 should be unaffected
        assertFalse("m2 p1 must still be a duplicate", ledger.admit("s1", "m2", "p1", 1L))
    }

    // ── ClearSessionRevisions (C: lifecycle boundary) ───────────────────────

    @Test
    fun `C clearSession clears all sessions`() {
        val ledger = PartRevisionLedger()
        assertTrue(ledger.admit("s1", "m1", "p1", 1L))
        assertTrue(ledger.admit("s1", "m2", "p2", 1L))
        ledger.clearSession("s1")
        // Both old keys should be accepted again.
        assertTrue(ledger.admit("s1", "m1", "p1", 1L))
        assertTrue(ledger.admit("s1", "m2", "p2", 1L))
    }

    @Test
    fun `C clearSession does not affect other sessions`() {
        val ledger = PartRevisionLedger()
        assertTrue(ledger.admit("s1", "m1", "p1", 1L))
        assertTrue(ledger.admit("s2", "m1", "p1", 1L))
        ledger.clearSession("s1")
        // s1 can re-admit
        assertTrue(ledger.admit("s1", "m1", "p1", 1L))
        // s2 still has dup protection
        assertFalse(ledger.admit("s2", "m1", "p1", 1L))
    }

    @Test
    fun `C clearSession clears terminal tombstones`() {
        val ledger = PartRevisionLedger()
        assertTrue(ledger.admit("s1", "m1", "p1", 5L))
        ledger.markTerminal("s1", "m1", "p1")
        // After terminal, any revision is rejected.
        assertFalse(ledger.admit("s1", "m1", "p1", 5L))
        // After clearSession, the tombstone is gone → revision 5 is acceptable again.
        ledger.clearSession("s1")
        assertTrue("clearSession must allow re-admit after terminal", ledger.admit("s1", "m1", "p1", 5L))
    }

    @Test
    fun `C clearSession terminal higher revision allowed after clear`() {
        val ledger = PartRevisionLedger()
        assertTrue(ledger.admit("s1", "m1", "p1", 10L))
        ledger.markTerminal("s1", "m1", "p1")
        assertFalse("higher revision after terminal must be rejected", ledger.admit("s1", "m1", "p1", 11L))

        ledger.clearSession("s1")
        // After clear, all revisions are fresh.
        assertTrue("clearSession must allow any revision after terminal", ledger.admit("s1", "m1", "p1", 5L))
    }

    // ── Concurrent safety ─────────────────────────────────────────────────

    @Test
    fun `concurrent admit for same key accepts exactly once`() {
        val ledger = PartRevisionLedger()
        val n = 50
        val accepted = AtomicInteger(0)
        val barrier = CountDownLatch(1)
        val ready = CountDownLatch(n)
        val threads = (1..n).map {
            Thread {
                ready.countDown()
                barrier.await()
                if (ledger.admit("s1", "m1", "p1", 5L)) {
                    accepted.incrementAndGet()
                }
            }
        }
        threads.forEach { it.start() }
        ready.await()
        barrier.countDown()
        threads.forEach { it.join(5000) }
        assertEquals(1, accepted.get())
    }

    @Test
    fun `concurrent admit for distinct keys all accepted within cap`() {
        val ledger = PartRevisionLedger(maxEntriesPerSession = 16)
        val accepted = AtomicInteger(0)
        val barrier = CountDownLatch(1)
        val ready = CountDownLatch(8)
        val threads = (1..8).map { i ->
            Thread {
                ready.countDown()
                barrier.await()
                if (ledger.admit("s1", "m$i", "p$i", 5L)) {
                    accepted.incrementAndGet()
                }
            }
        }
        threads.forEach { it.start() }
        ready.await()
        barrier.countDown()
        threads.forEach { it.join(5000) }
        // All 8 distinct keys within cap should be accepted.
        assertEquals("all 8 distinct keys within cap must be accepted", 8, accepted.get())
    }

    /**
     * HARD-CAP PROOF: high-concurrency with many more distinct keys than
     * capacity. Both `accepted` and `size` must never exceed cap.
     */
    @Test
    fun `high-concurrency distinct keys never exceed cap`() {
        val cap = 32
        val ledger = PartRevisionLedger(maxEntriesPerSession = cap)
        val nKeys = 100 // many more than cap
        val accepted = AtomicInteger(0)
        val barrier = CountDownLatch(1)
        val ready = CountDownLatch(nKeys)
        val threads = (0 until nKeys).map { i ->
            Thread {
                ready.countDown()
                barrier.await()
                if (ledger.admit("s1", "k$i", "k$i", i.toLong())) {
                    accepted.incrementAndGet()
                }
            }
        }
        threads.forEach { it.start() }
        ready.await()
        barrier.countDown()
        threads.forEach { it.join(10000) }

        val acceptedCount = accepted.get()
        val size = ledger.sessionEntryCount("s1")
        assertTrue(
            "accepted ($acceptedCount) must be <= cap ($cap); got $acceptedCount",
            acceptedCount <= cap,
        )
        assertTrue(
            "ledger size ($size) must be <= cap ($cap); got $size",
            size <= cap,
        )
    }

    /**
     * STRESS PROOF: concurrent admit + remove + markTerminal with more keys
     * than capacity. Ledger size must never exceed cap. This specifically
     * targets the bug where markTerminal on an absent key creates an entry,
     * but also verifies that concurrent remove/admit races don't leak entries.
     */
    @Test
    fun `concurrent admit remove markTerminal never exceeds cap`() {
        val cap = 16
        val ledger = PartRevisionLedger(maxEntriesPerSession = cap)
        val nOps = 200
        val barrier = CountDownLatch(1)
        val ready = CountDownLatch(nOps)
        val threads = (0 until nOps).map { i ->
            val key = i % 30 // cycle through 30 keys, more than cap
            Thread {
                ready.countDown()
                barrier.await()
                when (i % 5) {
                    0, 1 -> ledger.admit("s1", "k$key", "k$key", i.toLong())
                    2 -> ledger.markTerminal("s1", "k$key", "k$key")
                    3 -> ledger.remove("s1", "k$key", "k$key")
                    4 -> { // admit then immediately markTerminal
                        ledger.admit("s1", "k$key", "k$key", i.toLong())
                        ledger.markTerminal("s1", "k$key", "k$key")
                    }
                }
            }
        }
        threads.forEach { it.start() }
        ready.await()
        barrier.countDown()
        threads.forEach { it.join(10000) }

        val size = ledger.sessionEntryCount("s1")
        assertTrue(
            "ledger size ($size) must be <= cap ($cap) after concurrent stress",
            size <= cap,
        )
    }

    /** Concurrent distinct keys for two sessions — verify they don't interfere. */
    @Test
    fun `high-concurrency distinct keys across sessions`() {
        val cap = 16
        val ledger = PartRevisionLedger(maxEntriesPerSession = cap)
        val nKeys = 50 // more than cap each
        val acceptedS1 = AtomicInteger(0)
        val acceptedS2 = AtomicInteger(0)
        val barrier = CountDownLatch(1)
        val ready = CountDownLatch(nKeys * 2)
        val threads = (0 until nKeys).flatMap { i ->
            listOf(
                Thread {
                    ready.countDown()
                    barrier.await()
                    if (ledger.admit("s1", "k$i", "k$i", i.toLong())) {
                        acceptedS1.incrementAndGet()
                    }
                },
                Thread {
                    ready.countDown()
                    barrier.await()
                    if (ledger.admit("s2", "k$i", "k$i", i.toLong())) {
                        acceptedS2.incrementAndGet()
                    }
                },
            )
        }
        threads.forEach { it.start() }
        ready.await()
        barrier.countDown()
        threads.forEach { it.join(10000) }

        val a1 = acceptedS1.get()
        val a2 = acceptedS2.get()
        assertTrue("s1 accepted ($a1) must be <= cap ($cap)", a1 <= cap)
        assertTrue("s2 accepted ($a2) must be <= cap ($cap)", a2 <= cap)
        assertTrue("s1 ledger size must be <= cap", ledger.sessionEntryCount("s1") <= cap)
        assertTrue("s2 ledger size must be <= cap", ledger.sessionEntryCount("s2") <= cap)
    }
}
