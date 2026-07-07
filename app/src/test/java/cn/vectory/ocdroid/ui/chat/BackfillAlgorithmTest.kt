package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-20 Phase 2: table-driven unit tests for [BackfillAlgorithm] — the pure
 * gap-detection / fill-resolution / G6-probe-gate invariant surface.
 */
class BackfillAlgorithmTest {

    private fun m(id: String, created: Long) = Message(id = id, role = "user", time = Message.TimeInfo(created = created))

    // ── anchorOf ─────────────────────────────────────────────────────────────

    @Test
    fun `anchorOf returns the message 3-from-newest`() {
        // cached oldest-first; dropLast(3) keeps the first 3, last = the 3rd.
        val cached = listOf(m("a", 1), m("b", 2), m("c", 3), m("d", 4), m("e", 5), m("f", 6))
        val anchor = BackfillAlgorithm.anchorOf(cached)
        assertEquals("c", anchor?.id)
    }

    @Test
    fun `anchorOf returns null when cache has fewer than 4 messages`() {
        assertNull(BackfillAlgorithm.anchorOf(emptyList()))
        assertNull(BackfillAlgorithm.anchorOf(listOf(m("a", 1))))
        assertNull(BackfillAlgorithm.anchorOf(listOf(m("a", 1), m("b", 2), m("c", 3))))
        // Exactly 4 → dropLast(3) keeps 1 → last = the 1st.
        assertEquals("a", BackfillAlgorithm.anchorOf(listOf(m("a", 1), m("b", 2), m("c", 3), m("d", 4)))?.id)
    }

    // ── detectGap (4 cases) ──────────────────────────────────────────────────

    @Test
    fun `detectGap case 1 - null anchor returns NoGap`() {
        val fetched = (1..5).map { m("n$it", it.toLong()) }
        val d = BackfillAlgorithm.detectGap(null, fetched, "cursor")
        assertEquals(GapDetection.NoGap, d)
    }

    @Test
    fun `detectGap case 2 - anchor in fetched window returns NoGap (sentinel slot absorbed)`() {
        // anchor at the oldest (5th) slot of the probe → contiguous.
        val anchor = m("A", 1)
        val fetched = listOf(m("A", 1), m("n1", 2), m("n2", 3), m("n3", 4), m("n4", 5))
        val d = BackfillAlgorithm.detectGap(anchor, fetched, "cursor")
        assertEquals(GapDetection.NoGap, d)
    }

    @Test
    fun `detectGap case 3 - short probe page without anchor returns NoGap (history exhausted, merge)`() {
        // Only 4 messages returned (history ended) + anchor absent → NoGap
        // (the "gap ≤ probe" direct-merge case).
        val anchor = m("A", 1)
        val fetched = listOf(m("n1", 2), m("n2", 3), m("n3", 4), m("n4", 5)) // size 4 < PROBE_SIZE 5
        val d = BackfillAlgorithm.detectGap(anchor, fetched, null)
        assertEquals(GapDetection.NoGap, d)
    }

    @Test
    fun `detectGap case 4 - full probe page without anchor returns GapExists`() {
        val anchor = m("A", 1)
        val fetched = (1..5).map { m("n$it", (it + 1).toLong()) }
        val d = BackfillAlgorithm.detectGap(anchor, fetched, "tail-cursor")
        assertTrue(d is GapDetection.GapExists)
        val g = d as GapDetection.GapExists
        assertEquals("A", g.lowerAnchorMessageId)
        // upperBoundary = oldest in fetched = n1.
        assertEquals("n1", g.upperBoundaryMessageId)
        assertEquals("tail-cursor", g.initialNextBeforeCursor)
    }

    @Test
    fun `detectGap GapExists carries empty cursor when probe returned null cursor`() {
        // Full page + missing anchor + null cursor → still GapExists (the gap's
        // first fill step will immediately exhaust). Cursor carried as "".
        val anchor = m("A", 1)
        val fetched = (1..5).map { m("n$it", (it + 1).toLong()) }
        val d = BackfillAlgorithm.detectGap(anchor, fetched, null)
        assertTrue(d is GapDetection.GapExists)
        assertEquals("", (d as GapDetection.GapExists).initialNextBeforeCursor)
    }

    // ── stepCoversAnchor ─────────────────────────────────────────────────────

    @Test
    fun `stepCoversAnchor true when step contains anchor id`() {
        val anchor = m("A", 1)
        val step = listOf(m("B", 2), m("A", 1), m("C", 3))
        assertTrue(BackfillAlgorithm.stepCoversAnchor(step, anchor))
    }

    @Test
    fun `stepCoversAnchor false when step lacks anchor id`() {
        val anchor = m("A", 1)
        val step = listOf(m("B", 2), m("C", 3))
        assertFalse(BackfillAlgorithm.stepCoversAnchor(step, anchor))
    }

    @Test
    fun `stepCoversAnchor false when anchor is null`() {
        assertFalse(BackfillAlgorithm.stepCoversAnchor(listOf(m("A", 1)), null))
    }

    @Test
    fun `stepCoversAnchor false on empty step`() {
        assertFalse(BackfillAlgorithm.stepCoversAnchor(emptyList(), m("A", 1)))
    }

    // ── shouldProbe (G6) ─────────────────────────────────────────────────────

    @Test
    fun `shouldProbe true when SSE workdir differs from session workdir`() {
        // Live SSE feed but for a different workdir → not covered → probe.
        assertTrue(
            BackfillAlgorithm.shouldProbe(
                sessionId = "s1",
                currentWorkdir = "/repo-a",
                sessionsEverColdSnapshotted = setOf("s1"),
                sseCurrentWorkdir = "/repo-b",
            )
        )
    }

    @Test
    fun `shouldProbe true when workdir matches but session never cold-snapshotted`() {
        assertTrue(
            BackfillAlgorithm.shouldProbe(
                sessionId = "s1",
                currentWorkdir = "/repo",
                sessionsEverColdSnapshotted = emptySet(),
                sseCurrentWorkdir = "/repo",
            )
        )
    }

    @Test
    fun `shouldProbe true when SSE workdir is null (no live feed)`() {
        assertTrue(
            BackfillAlgorithm.shouldProbe(
                sessionId = "s1",
                currentWorkdir = "/repo",
                sessionsEverColdSnapshotted = setOf("s1"),
                sseCurrentWorkdir = null,
            )
        )
    }

    @Test
    fun `shouldProbe false when SSE covers the workdir AND session was cold-snapshotted`() {
        assertFalse(
            BackfillAlgorithm.shouldProbe(
                sessionId = "s1",
                currentWorkdir = "/repo",
                sessionsEverColdSnapshotted = setOf("s1"),
                sseCurrentWorkdir = "/repo",
            )
        )
    }

    @Test
    fun `PROBE_SIZE is 5`() {
        assertEquals(5, BackfillAlgorithm.PROBE_SIZE)
    }
}
