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
 * with terminal tombstone semantics and LRU eviction (rev-gpt fix).
 *
 * The [PartRevisionLedger] replaces the raw `ConcurrentHashMap<String, Long>`
 * that was used in the original production hooks. The key differences:
 *  - terminal (done/truncated) frames set a tombstone instead of removing the
 *    entry, so ALL revisions (same, lower, higher, null) after terminal are
 *    rejected — no part resurrection.
 *  - per-session capacity capped at 32 entries; when at capacity, the
 *    least-recently-used terminal entry is evicted (LRU by recency order).
 *    Active entries are NEVER evicted. If all entries are active, overflow
 *    is fail-closed.
 *  - `clearSessionRevisions` clears all tombstones; LRU eviction may also
 *    silently remove terminal entries under capacity pressure.
 *  - nullable `partEventRevision` is now incorporated into the ledger.
 *
 * Each test calls [PartRevisionLedger] methods directly to verify the
 * admission + tombstone + capacity + LRU invariants.
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
    fun `B LRU evicts oldest terminal when at capacity`() {
        // With LRU eviction: cap=2, m1 terminal, m2 active.
        // 3rd distinct key (m3) evicts oldest terminal (m1) and is admitted.
        val ledger = PartRevisionLedger(maxEntriesPerSession = 2)
        assertTrue(ledger.admit("s1", "m1", "p1", 1L))
        assertTrue(ledger.admit("s1", "m2", "p2", 1L))
        ledger.markTerminal("s1", "m1", "p1")
        // m3 MUST be accepted — evicts oldest terminal (m1)
        assertTrue("m3 must be accepted via LRU eviction of terminal m1",
            ledger.admit("s1", "m3", "p3", 1L))
        assertEquals("size must stay at cap=2", 2, ledger.sessionEntryCount("s1"))
        // m1 was evicted — the composite key no longer exists
        assertNull("evicted m1 must have no entry", ledger.isTerminal("s1", "m1", "p1"))
        // m2 (active) must still be intact
        assertTrue("active m2 must still accept higher revision",
            ledger.admit("s1", "m2", "p2", 2L))
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

    @Test
    fun `B LRU evicts oldest terminal not active entries`() {
        val ledger = PartRevisionLedger(maxEntriesPerSession = 3)
        assertTrue(ledger.admit("s1", "m1", "p1", 1L)) // active
        assertTrue(ledger.admit("s1", "m2", "p2", 1L)) // terminal
        assertTrue(ledger.admit("s1", "m3", "p3", 1L)) // active
        ledger.markTerminal("s1", "m2", "p2")

        // 4th key — evicts oldest terminal (m2), admits m4
        assertTrue("m4 must evict terminal m2 and be admitted",
            ledger.admit("s1", "m4", "p4", 1L))
        assertEquals(3, ledger.sessionEntryCount("s1"))

        // Active entries (m1, m3) must still reject duplicates
        assertFalse("active m1 duplicate still rejected",
            ledger.admit("s1", "m1", "p1", 1L))
        assertTrue("active m1 can still accept higher revision",
            ledger.admit("s1", "m1", "p1", 2L))
        assertFalse("active m3 duplicate still rejected",
            ledger.admit("s1", "m3", "p3", 1L))
        assertTrue("active m3 can still accept higher revision",
            ledger.admit("s1", "m3", "p3", 2L))

        // m2 was evicted (terminal tombstone removed from ledger)
        assertNull("evicted m2 entry must be absent", ledger.isTerminal("s1", "m2", "p2"))
    }

    @Test
    fun `B cap at 32 default with LRU accepts 33rd key when terminals exist`() {
        val ledger = PartRevisionLedger() // default MAX_REVISIONS_PER_SESSION = 32
        for (i in 1..32) {
            assertTrue("entry $i must be accepted", ledger.admit("s1", "m$i", "p$i", 1L))
        }
        // Mark first 16 as terminal (oldest entries)
        for (i in 1..16) {
            ledger.markTerminal("s1", "m$i", "p$i")
        }
        // 33rd distinct key must be accepted via LRU eviction of oldest terminal (m1)
        assertTrue("33rd key must be accepted via LRU eviction of oldest terminal",
            ledger.admit("s1", "m33", "p33", 1L))
        assertEquals(32, ledger.sessionEntryCount("s1"))
        // m1 was evicted (no longer in ledger)
        assertNull("evicted m1 entry must be absent",
            ledger.isTerminal("s1", "m1", "p1"))
        // Other terminals still protected (m2 is now oldest, still present)
        assertTrue("m2 terminal still present",
            ledger.isTerminal("s1", "m2", "p2") == true)
    }

    @Test
    fun `B LRU evicts oldest terminal among multiple terminals`() {
        val ledger = PartRevisionLedger(maxEntriesPerSession = 3)
        assertTrue(ledger.admit("s1", "old", "old", 1L)) // first → oldest
        assertTrue(ledger.admit("s1", "mid", "mid", 1L))
        assertTrue(ledger.admit("s1", "new", "new", 1L)) // third → newest
        ledger.markTerminal("s1", "old", "old")
        ledger.markTerminal("s1", "mid", "mid")
        ledger.markTerminal("s1", "new", "new")

        // 4th key — evicts oldest terminal ("old")
        assertTrue("new key evicts oldest terminal ('old')",
            ledger.admit("s1", "extra", "extra", 1L))
        assertEquals(3, ledger.sessionEntryCount("s1"))
        assertNull("'old' was evicted", ledger.isTerminal("s1", "old", "old"))

        // 'mid' and 'new' still present as terminal
        assertEquals(true, ledger.isTerminal("s1", "mid", "mid"))
        assertEquals(true, ledger.isTerminal("s1", "new", "new"))
    }

    @Test
    fun `B LRU no-op when cap full with no terminals to evict`() {
        // When all entries are non-terminal (active), new key is rejected.
        val ledger = PartRevisionLedger(maxEntriesPerSession = 2)
        assertTrue(ledger.admit("s1", "m1", "p1", 1L))
        assertTrue(ledger.admit("s1", "m2", "p2", 1L))
        // Both active, no terminals → 3rd rejected
        assertFalse("no terminals to evict — 3rd key must be rejected",
            ledger.admit("s1", "m3", "p3", 1L))
    }

    @Test
    fun `B LRU preserves terminal semantics for non-evicted entries`() {
        // Entries that survive LRU eviction still have their terminal protection.
        val ledger = PartRevisionLedger(maxEntriesPerSession = 2)
        assertTrue(ledger.admit("s1", "m1", "p1", 1L))
        assertTrue(ledger.admit("s1", "m2", "p2", 1L))
        ledger.markTerminal("s1", "m1", "p1")
        ledger.markTerminal("s1", "m2", "p2")

        // 3rd key evicts oldest terminal (m1)
        assertTrue("m3 evicts m1", ledger.admit("s1", "m3", "p3", 1L))
        // m2 is still present and terminal — must reject higher revision
        assertFalse("surviving terminal m2 must reject higher revision",
            ledger.admit("s1", "m2", "p2", 2L))
    }

    @Test
    fun `B LRU distinguishes FIFO from recency`() {
        // With FIFO (insertionOrder immutable), "old" inserted first and
        // marked terminal last would be evicted first (not LRU).
        // With LRU (lastUsedOrder updated on every touch), "old" has the
        // HIGHEST touch count (touched most recently via late markTerminal),
        // so "mid" (touched earlier, now stale) is evicted first.
        val ledger = PartRevisionLedger(maxEntriesPerSession = 3)
        // "old" inserted first (touchOrder=0)
        assertTrue(ledger.admit("s1", "old", "old", 1L))
        // "mid" inserted second (touchOrder=1)
        assertTrue(ledger.admit("s1", "mid", "mid", 1L))
        // "new" inserted third (touchOrder=2)
        assertTrue(ledger.admit("s1", "new", "new", 1L))

        // Touch "new" frequently — update revision (touchOrder=3)
        assertTrue(ledger.admit("s1", "new", "new", 2L))
        // Touch "new" again (touchOrder=4)
        assertTrue(ledger.admit("s1", "new", "new", 3L))

        // Mark "new" terminal (touchOrder=5)
        ledger.markTerminal("s1", "new", "new")
        // Mark "mid" terminal (touchOrder=6) — mid untouched since admit
        ledger.markTerminal("s1", "mid", "mid")
        // Mark "old" terminal last (touchOrder=7) — old has the HIGHEST order
        ledger.markTerminal("s1", "old", "old")

        // 4th key — must evict LRU terminal (lowest lastUsedOrder).
        // Recency order: new(5) > old(7) > … wait, that's wrong.
        // Let me trace:
        //   old: admit(0) → markTerminal(7) → lastUsed=7
        //   mid: admit(1) → markTerminal(6) → lastUsed=6
        //   new: admit(2) → admit(3) → admit(4) → markTerminal(5) → lastUsed=5
        //
        // So new=5, mid=6, old=7. LRU evicts new (lowest=5).
        //
        // With FIFO immutable insertionOrder:
        //   old=0, mid=1, new=2 → evicts old (FIFO oldest).
        //
        // This test passes only with recency-based LRU.
        assertTrue("m4 evicts 'new' (LRU: lowest lastUsedOrder=5)",
            ledger.admit("s1", "m4", "p4", 1L))
        assertEquals(3, ledger.sessionEntryCount("s1"))

        // "new" was evicted (lowest lastUsedOrder among terminals)
        assertNull("'new' must be evicted (LRU: least recently used)",
            ledger.isTerminal("s1", "new", "new"))

        // "old" and "mid" survive
        assertEquals(true, ledger.isTerminal("s1", "old", "old"))
        assertEquals(true, ledger.isTerminal("s1", "mid", "mid"))
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
    fun `high-concurrency distinct keys exactly cap with all threads exited`() {
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
        threads.forEach { t ->
            t.join(10000)
            assertFalse(
                "thread must exit within timeout, still alive",
                t.isAlive,
            )
        }

        val acceptedCount = accepted.get()
        val size = ledger.sessionEntryCount("s1")

        // With synchronized per-session admission, exactly cap entries
        // succeed (the first cap threads to acquire the lock).
        assertEquals(
            "accepted must be exactly cap ($cap) under synchronized admission; got $acceptedCount",
            cap,
            acceptedCount,
        )
        assertEquals(
            "ledger size must equal cap ($cap); got $size",
            cap,
            size,
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
        threads.forEach { t ->
            t.join(10000)
            assertFalse(
                "thread must exit within timeout, still alive",
                t.isAlive,
            )
        }

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
        threads.forEach { t ->
            t.join(10000)
            assertFalse(
                "thread must exit within timeout, still alive",
                t.isAlive,
            )
        }

        val a1 = acceptedS1.get()
        val a2 = acceptedS2.get()
        assertTrue("s1 accepted ($a1) must be <= cap ($cap)", a1 <= cap)
        assertTrue("s2 accepted ($a2) must be <= cap ($cap)", a2 <= cap)
        assertTrue("s1 ledger size must be <= cap", ledger.sessionEntryCount("s1") <= cap)
        assertTrue("s2 ledger size must be <= cap", ledger.sessionEntryCount("s2") <= cap)
    }
}
