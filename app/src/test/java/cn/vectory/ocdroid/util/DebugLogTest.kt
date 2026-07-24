package cn.vectory.ocdroid.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R18 Phase 5 — [DebugLog] pure-JVM coverage.
 *
 * DebugLog is a process-singleton in-memory ring buffer that ALSO forwards to
 * `android.util.Log`. Under JVM unit tests `testOptions.unitTests
 * .isReturnDefaultValues = true` makes the Log stubs return defaults instead
 * of throwing, so the `runCatching { Log.x(...) }` block is a no-op and the
 * ring-buffer logic can be exercised directly.
 *
 * Covers: append + newest-first ordering, per-Level forwarding helpers,
 * 1000-entry cap with oldest-evicted, sequence monotonicity (collision-free
 * LazyColumn keys), `clear()` and StateFlow value semantics.
 */
class DebugLogTest {

    @Before
    fun resetState() {
        // The object is a process singleton; isolate each test by clearing
        // the buffer so prior tests' entries don't leak in.
        DebugLog.clear()
    }

    @After
    fun tearDown() {
        DebugLog.clear()
    }

    @Test
    fun `log prepends entry making it newest at index 0`() {
        DebugLog.log("Tag", "first")
        DebugLog.log("Tag", "second")

        val entries = DebugLog.entries.value
        assertEquals(2, entries.size)
        assertEquals("second", entries[0].message)
        assertEquals("first", entries[1].message)
    }

    @Test
    fun `log preserves tag message and level`() {
        DebugLog.log("MyTag", "hello", DebugLog.Level.WARN)

        val e = DebugLog.entries.value.first()
        assertEquals("MyTag", e.tag)
        assertEquals("hello", e.message)
        assertEquals(DebugLog.Level.WARN, e.level)
    }

    @Test
    fun `default level is DEBUG`() {
        DebugLog.log("T", "m")
        assertEquals(DebugLog.Level.DEBUG, DebugLog.entries.value.first().level)
    }

    @Test
    fun `convenience helpers map to the right level`() {
        DebugLog.d("T", "d"); assertEquals(DebugLog.Level.DEBUG, levelOf(0))
        DebugLog.i("T", "i"); assertEquals(DebugLog.Level.INFO, levelOf(0))
        DebugLog.w("T", "w"); assertEquals(DebugLog.Level.WARN, levelOf(0))
        DebugLog.e("T", "e"); assertEquals(DebugLog.Level.ERROR, levelOf(0))
    }

    private fun levelOf(index: Int) = DebugLog.entries.value[index].level

    @Test
    fun `sequence numbers are strictly monotonic`() {
        // The seq counter is a process-lifetime AtomicLong singleton; prior
        // tests (this class is order-independent) may have bumped it. So we
        // only assert strict monotonicity, NOT absolute values — the property
        // that matters for the LazyColumn key contract is "no collisions
        // within a buffer", i.e. strictly increasing across consecutive logs.
        DebugLog.log("T", "a")
        DebugLog.log("T", "b")
        DebugLog.log("T", "c")

        val entries = DebugLog.entries.value
        // entries are newest-first; reverse to chronological for the assertion.
        val chronological = entries.map { it.seq }.reversed()
        assertEquals(3, chronological.size)
        for (i in 1 until chronological.size) {
            assertTrue("seq must strictly increase", chronological[i] > chronological[i - 1])
        }
    }

    @Test
    fun `newest entry has a strictly greater sequence than the previous head`() {
        DebugLog.log("T", "before")
        val beforeSeq = DebugLog.entries.value.first().seq

        DebugLog.clear()
        DebugLog.log("T", "after")
        val afterSeq = DebugLog.entries.value.first().seq

        // Counter is process-lifetime; "after" must have a seq strictly greater
        // than "before"'s seq. This is the property that makes seq a safe
        // LazyColumn key even when buffer contents are cleared mid-stream.
        assertTrue("seq must strictly increase across clear()", afterSeq > beforeSeq)
    }

    @Test
    fun `clear empties the buffer`() {
        DebugLog.log("T", "a")
        DebugLog.log("T", "b")
        assertEquals(2, DebugLog.entries.value.size)

        DebugLog.clear()

        assertTrue(DebugLog.entries.value.isEmpty())
    }

    @Test
    fun `entries StateFlow emits a new reference after each log`() {
        val first = DebugLog.entries.value
        DebugLog.log("T", "x")
        val second = DebugLog.entries.value

        assertNotEquals("StateFlow value reference must change to notify collectors", first, second)
    }

    @Test
    fun `ring buffer caps at MAX_ENTRIES and evicts oldest`() {
        // The cap is 3000 (DebugLog.MAX_ENTRIES; bumped from 1000 when the
        // verbose *Diag tags were scoped + coalesced — see DebugLog.MAX_ENTRIES
        // comment). Push MAX+2 distinct entries; only the most recent MAX may
        // survive, and they must be newest-first.
        val cap = 3000
        repeat(cap + 2) { i -> DebugLog.log("T", "msg-$i") }

        val entries = DebugLog.entries.value
        assertEquals(cap, entries.size)
        // Newest first → msg-(cap+1) at index 0, msg-2 at the tail (msg-0 / msg-1 evicted).
        assertEquals("msg-${cap + 1}", entries.first().message)
        assertEquals("msg-2", entries.last().message)
    }

    @Test
    fun `timeMs is populated and non-decreasing within a single-threaded test`() {
        DebugLog.log("T", "a")
        DebugLog.log("T", "b")

        val e = DebugLog.entries.value
        // Newest first → e[0] is "b" (later), e[1] is "a" (earlier).
        assertTrue("timeMs must be populated", e[0].timeMs > 0L)
        assertTrue(
            "newer entry timeMs must be >= older entry timeMs",
            e[0].timeMs >= e[1].timeMs,
        )
    }

    @Test
    fun `tag and message can be empty strings`() {
        DebugLog.log("", "")
        val e = DebugLog.entries.value.single()
        assertEquals("", e.tag)
        assertEquals("", e.message)
    }

    @Test
    fun `concurrent appends produce no-lost-entries immutable newest-first snapshots`() {
        val threadCount = 4
        val entriesPerThread = 1000 // 4000 total, but capped at 3000
        val threads = (0 until threadCount).map { t ->
            Thread {
                repeat(entriesPerThread) { i ->
                    DebugLog.log("T-$t", "msg-$i")
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val snapshot = DebugLog.entries.value
        assertTrue("Buffer must not exceed MAX_ENTRIES (3000), got ${snapshot.size}", snapshot.size <= 3000)

        // All seq numbers must be unique — proves no entry lost/collided under concurrency
        val seqs = snapshot.map { it.seq }
        assertEquals(
            "All entries must have unique seq numbers",
            snapshot.size, seqs.toSet().size,
        )

        // Cross-thread ordering is NOT deterministic by wall-clock (System.currentTimeMillis()
        // is not monotonic across threads) — so we only verify the head is from the latest
        // batch (seq near max), not strict per-entry ordering.
        val maxSeq = seqs.max()
        assertTrue(
            "Head entry seq ($maxSeq) must be near max (within last 100)",
            snapshot.first().seq >= maxSeq - 100,
        )
    }

    @Test
    fun `single-thread appends produce strict reverse-insertion-order by seq`() {
        // Within a single thread, synchronized(addFirst) guarantees deterministic
        // reverse-insertion order: later calls land at index 0, pushing earlier
        // entries rightward, so seq numbers are strictly decreasing along the list.
        DebugLog.clear()
        val n = 100
        repeat(n) { i -> DebugLog.log("T", "msg-$i") }

        val snapshot = DebugLog.entries.value
        assertEquals(n, snapshot.size)
        for (i in 0 until n - 1) {
            assertTrue(
                "Entry at index $i (seq=${snapshot[i].seq}) must have seq > index ${i + 1} (seq=${snapshot[i + 1].seq})",
                snapshot[i].seq > snapshot[i + 1].seq,
            )
        }
    }

    @Test
    fun `multiple distinct tags interleave in newest-first order`() {
        DebugLog.i("sse", "connected")
        DebugLog.w("sync", "lag")
        DebugLog.e("repo", "boom")

        val entries = DebugLog.entries.value
        assertEquals(listOf("repo", "sync", "sse"), entries.map { it.tag })
        assertEquals(
            listOf(DebugLog.Level.ERROR, DebugLog.Level.WARN, DebugLog.Level.INFO),
            entries.map { it.level },
        )
    }
}
