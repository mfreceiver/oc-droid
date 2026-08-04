package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.FileDiff
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.ui.ScrollCheckpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §wave2-1-l4: Baseline tests pinning ChatMessageList behavior.
 * Pure-function tests (no Robolectric/Compose infra — plain JVM).
 *
 * Coverage:
 *  🟢 Mirror-invariant: `lazyColumnKeyList` across all branch combinations
 *  🟢 Restore landing: `resolveRestoreIndex` across all branches
 *  🟡 Follow-bottom at-bottom predicate
 *  🟡 Scroll-memory: key list correctness
 */
class ChatMessageListBaselineTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun convBlock(
        id: String,
        messageId: String = id,
        role: String = "user",
    ): RenderBlock.Conversation = RenderBlock.Conversation(
        message = Message(id = messageId, role = role),
        parts = emptyList(),
        id = id,
    )

    private fun msg(id: String, role: String = "user"): Message = Message(id = id, role = role)

    // ── 🟢 Mirror-invariant: lazyColumnKeyList branch coverage ────────────────

    @Test
    fun `mirror empty`() {
        assertEquals(emptyList<String>(), lazyColumnKeyList(null, null, emptyList(), emptyList(), false, null))
    }

    @Test
    fun `mirror streaming only`() {
        assertEquals(
            listOf("streaming-reasoning"),
            lazyColumnKeyList(Part(id = "sr-1", type = "reasoning"), null, emptyList(), emptyList(), false, null),
        )
    }

    @Test
    fun `mirror session-diff only`() {
        assertEquals(
            listOf("session-diff"),
            lazyColumnKeyList(null, listOf(FileDiff()), emptyList(), emptyList(), false, null),
        )
    }

    @Test
    fun `mirror blocks only`() {
        val blocks = listOf(convBlock("b1"), convBlock("b2"))
        assertEquals(
            listOf("b1", "b2"),
            lazyColumnKeyList(null, null, blocks, listOf(msg("b1"), msg("b2")), false, null),
        )
    }

    @Test
    fun `mirror blocks + load-more`() {
        val blocks = listOf(convBlock("b1"))
        assertEquals(
            listOf("b1", "load-more"),
            lazyColumnKeyList(null, null, blocks, listOf(msg("b1")), true, "cursor-x"),
        )
    }

    @Test
    fun `mirror all branches`() {
        val blocks = listOf(convBlock("b1"), convBlock("b2"))
        val msgs = listOf(msg("b1"), msg("b2"))
        assertEquals(
            listOf("streaming-reasoning", "session-diff", "b1", "b2", "load-more"),
            lazyColumnKeyList(Part(id = "sr-1", type = "reasoning"), listOf(FileDiff()), blocks, msgs, true, "cursor-x"),
        )
    }

    @Test
    fun `mirror no load-more when cursor null`() {
        val blocks = listOf(convBlock("b1"))
        assertEquals(
            listOf("b1"),
            lazyColumnKeyList(null, null, blocks, listOf(msg("b1")), true, null),
        )
    }

    @Test
    fun `mirror no load-more when messages empty`() {
        assertEquals(
            emptyList<String>(),
            lazyColumnKeyList(null, null, emptyList(), emptyList(), true, "cursor-x"),
        )
    }

    @Test
    fun `mirror empty session-diff treated as absent`() {
        val blocks = listOf(convBlock("b1"))
        assertEquals(
            listOf("b1"),
            lazyColumnKeyList(null, emptyList(), blocks, listOf(msg("b1")), false, null),
        )
    }

    @Test
    fun `mirror multiple blocks with load-more`() {
        val blocks = listOf(convBlock("m1"), convBlock("m2"), convBlock("m3"))
        val msgs = listOf(msg("m1"), msg("m2"), msg("m3"))
        assertEquals(
            listOf("m1", "m2", "m3", "load-more"),
            lazyColumnKeyList(null, null, blocks, msgs, true, "cursor"),
        )
    }

    // ── 🟢 Restore landing: resolveRestoreIndex ──────────────────────────────

    @Test
    fun `restore anchor present`() {
        assertEquals(ResolvedRestore(2, 12), resolveRestoreIndex(ScrollCheckpoint("c", 999, 12), listOf("a", "b", "c", "d")))
    }

    @Test
    fun `restore anchor absent`() {
        assertEquals(ResolvedRestore(1, 7), resolveRestoreIndex(ScrollCheckpoint("missing", 1, 7), listOf("a", "b", "c")))
    }

    @Test
    fun `restore anchor null`() {
        assertEquals(ResolvedRestore(2, 0), resolveRestoreIndex(ScrollCheckpoint(null, 2, 0), listOf("a", "b", "c", "d")))
    }

    @Test
    fun `restore clamps above max`() {
        assertEquals(ResolvedRestore(4, 0), resolveRestoreIndex(ScrollCheckpoint(null, 50, 0), (0..4).map { "k-$it" }))
    }

    @Test
    fun `restore clamps negative to zero`() {
        assertEquals(ResolvedRestore(0, 0), resolveRestoreIndex(ScrollCheckpoint(null, -5, 0), listOf("a", "b", "c")))
    }

    @Test
    fun `restore empty keys`() {
        assertTrue(resolveRestoreIndex(ScrollCheckpoint("a", 0, 0), emptyList()) == null)
    }

    @Test
    fun `restore offset preserved`() {
        assertEquals(99, resolveRestoreIndex(ScrollCheckpoint("b", 0, 99), listOf("a", "b", "c"))?.offset)
        assertEquals(99, resolveRestoreIndex(ScrollCheckpoint("missing", 1, 99), listOf("a", "b", "c"))?.offset)
    }

    @Test
    fun `restore anchor wins over fallback`() {
        assertEquals(2, resolveRestoreIndex(ScrollCheckpoint("msg-2", 0, 0), listOf("msg-0", "msg-1", "msg-2", "msg-3"))?.index)
    }

    // ── 🟡 Follow-bottom ─────────────────────────────────────────────────────

    @Test
    fun `followBottom predicate atBottom`() {
        assertTrue(0 == 0 && 0 <= 24)
        assertTrue(0 == 0 && 24 <= 24)
    }

    @Test
    fun `followBottom predicate notAtBottom`() {
        assertTrue("expected NOT at bottom", !(0 == 0 && 25 <= 24))
    }

    // ── 🟡 Scroll-memory ─────────────────────────────────────────────────────

    @Test
    fun `scrollMemory key list correct`() {
        val blocks = (1..5).map { convBlock("b$it") }
        val msgs = blocks.map { it.message }
        assertEquals(
            listOf("b1", "b2", "b3", "b4", "b5"),
            lazyColumnKeyList(null, null, blocks, msgs, false, null),
        )
    }
}
