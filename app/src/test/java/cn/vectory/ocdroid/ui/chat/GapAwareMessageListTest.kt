package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-20 Phase 2: unit tests for the non-contiguous message model
 * ([withGaps] + [Entry] / [GapMarker]).
 */
class GapAwareMessageListTest {

    private fun m(id: String, created: Long) = Message(id = id, role = "user", time = Message.TimeInfo(created = created))

    private fun gap(gapId: String, lower: String, upper: String) = GapMarker(
        gapId = gapId,
        lowerAnchorMessageId = lower,
        upperBoundaryMessageId = upper,
        nextBeforeCursor = "c-$gapId",
        fillState = GapFillState.Idle,
    )

    @Test
    fun `withGaps with no gaps returns only Message entries in input order`() {
        val msgs = listOf(m("a", 1), m("b", 2), m("c", 3))
        val entries = msgs.withGaps(emptyList())
        assertEquals(3, entries.size)
        assertTrue(entries.all { it is Entry.Message })
        assertEquals(listOf("a", "b", "c"), entries.map { (it as Entry.Message).message.id })
    }

    @Test
    fun `withGaps inserts a single gap before its upperBoundary message`() {
        // messages oldest-first: [a, b, c, d]; gap between b (lower/older) and c (upper/newer).
        val msgs = listOf(m("a", 1), m("b", 2), m("c", 3), m("d", 4))
        val gaps = listOf(gap("g1", lower = "b", upper = "c"))
        val entries = msgs.withGaps(gaps)
        // Expected order: a, b, [gap g1], c, d
        assertEquals(5, entries.size)
        val kinds = entries.map { entryKind(it) }
        assertEquals(listOf("a", "b", "gap:g1", "c", "d"), kinds)
    }

    @Test
    fun `withGaps inserts multiple gaps sorted by upperBoundary time ascending`() {
        // Two gaps whose input order is reversed relative to their boundary
        // times; withGaps must place them by upperBoundary time ascending.
        val msgs = listOf(m("a", 1), m("b", 2), m("c", 3), m("d", 4), m("e", 5))
        // g2 boundary = d (time 4); g1 boundary = b (time 2). Input order g2,g1.
        val gaps = listOf(gap("g2", lower = "c", upper = "d"), gap("g1", lower = "a", upper = "b"))
        val entries = msgs.withGaps(gaps)
        // Expected: a, [g1], b, c, [g2], d, e
        assertEquals(7, entries.size)
        assertEquals(listOf("a", "gap:g1", "b", "c", "gap:g2", "d", "e"), entries.map { entryKind(it) })
    }

    @Test
    fun `withGaps drops a gap whose upperBoundary is not in the messages`() {
        val msgs = listOf(m("a", 1), m("b", 2))
        val gaps = listOf(gap("gStale", lower = "x", upper = "missing"))
        val entries = msgs.withGaps(gaps)
        // Stale gap dropped; only the two messages remain.
        assertEquals(2, entries.size)
        assertTrue(entries.all { it is Entry.Message })
    }

    @Test
    fun `GapMarker toEntry projects gapId and fillState only`() {
        val g = GapMarker("g1", "lower", "upper", "cursor", GapFillState.Filling)
        val e = g.toEntry()
        assertTrue(e is Entry.GapMarker)
        assertEquals("g1", (e as Entry.GapMarker).gapId)
        assertEquals(GapFillState.Filling, e.fillState)
    }

    @Test
    fun `GapFillState has the four documented states`() {
        assertEquals(4, GapFillState.values().size)
        assertEquals(GapFillState.Idle, GapFillState.valueOf("Idle"))
        assertEquals(GapFillState.Filling, GapFillState.valueOf("Filling"))
        assertEquals(GapFillState.Exhausted, GapFillState.valueOf("Exhausted"))
        assertEquals(GapFillState.Error, GapFillState.valueOf("Error"))
    }

    private fun entryKind(e: Entry): String = when (e) {
        is Entry.Message -> e.message.id
        is Entry.GapMarker -> "gap:${e.gapId}"
    }
}
