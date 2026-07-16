package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.FileDiff
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.ui.ScrollCheckpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §Wave5b-Q13: JVM tests for the pure helpers backing the Restore consumer
 * (`lazyColumnKeyList` + `resolveRestoreIndex`). These are lifted out of the
 * @Composable ChatMessageList body so they are JVM-testable without
 * Robolectric — the rest of the scroll-state machine (LazyListState layout,
 * Compose slot reuse, saveable restore) requires an emulator and is out of
 * JVM scope per the task's verification boundary.
 *
 * Coverage matrix:
 *  - anchor present (returns anchor index)
 *  - anchor missing (falls back to clamped fallbackIndex)
 *  - anchor null (uses clamped fallbackIndex)
 *  - fallbackIndex out of bounds (clamped to [0, size-1])
 *  - empty keys (returns null → caller skips the scroll)
 *  - lazyColumnKeyList ordering mirrors the LazyColumn body
 *  - offset always preserved
 */
class ChatMessageContentHelpersTest {

    // ── resolveRestoreIndex ────────────────────────────────────────────────

    @Test
    fun `resolveRestoreIndex returns the anchor's index when the key is present`() {
        val cp = ScrollCheckpoint(anchorKey = "msg-5", fallbackIndex = 99, offset = 12)
        val keys = listOf("msg-1", "msg-2", "msg-3", "msg-4", "msg-5", "msg-6")

        val out = resolveRestoreIndex(cp, keys)

        assertEquals(ResolvedRestore(index = 4, offset = 12), out)
    }

    @Test
    fun `resolveRestoreIndex falls back to fallbackIndex when the anchor is absent`() {
        // The anchor was captured at openSubAgent time but the parent's
        // message list has since changed (SSE appended / metadata marker
        // injected / message prepended) — the key is no longer present.
        val cp = ScrollCheckpoint(anchorKey = "missing", fallbackIndex = 3, offset = 7)
        val keys = listOf("a", "b", "c", "d", "e")

        val out = resolveRestoreIndex(cp, keys)

        assertEquals(ResolvedRestore(index = 3, offset = 7), out)
    }

    @Test
    fun `resolveRestoreIndex uses fallbackIndex when anchorKey is null`() {
        // Capture-time the list had no layout info yet → anchorKey null.
        val cp = ScrollCheckpoint(anchorKey = null, fallbackIndex = 2, offset = 0)
        val keys = listOf("a", "b", "c", "d")

        val out = resolveRestoreIndex(cp, keys)

        assertEquals(ResolvedRestore(index = 2, offset = 0), out)
    }

    @Test
    fun `resolveRestoreIndex clamps fallbackIndex above the upper bound`() {
        // Session had 100 items at capture (fallbackIndex=50); at restore the
        // list shrank to 10 items (e.g. cold reload with smaller window).
        val cp = ScrollCheckpoint(anchorKey = null, fallbackIndex = 50, offset = 0)
        val keys = (0 until 10).map { "k-$it" }

        val out = resolveRestoreIndex(cp, keys)

        assertEquals(
            ResolvedRestore(index = 9, offset = 0),
            out,
        )
    }

    @Test
    fun `resolveRestoreIndex clamps negative fallbackIndex to 0`() {
        // Defensive: a malformed checkpoint with a negative index.
        val cp = ScrollCheckpoint(anchorKey = null, fallbackIndex = -5, offset = 0)
        val keys = listOf("a", "b", "c")

        val out = resolveRestoreIndex(cp, keys)

        assertEquals(ResolvedRestore(index = 0, offset = 0), out)
    }

    @Test
    fun `resolveRestoreIndex returns null for an empty key list`() {
        // The session has no renderable items yet (cold load still in flight
        // OR a legitimately empty session). The caller MUST skip the scroll.
        val cp = ScrollCheckpoint(anchorKey = "msg-1", fallbackIndex = 0, offset = 0)

        val out = resolveRestoreIndex(cp, emptyList())

        assertNull(out)
    }

    @Test
    fun `resolveRestoreIndex always returns the captured offset regardless of which anchor resolved`() {
        // Offset is the per-pixel offset within the resolved item — it does
        // NOT depend on whether the anchor or the fallback won.
        val keys = listOf("a", "b", "c")

        val anchorHit = resolveRestoreIndex(
            ScrollCheckpoint(anchorKey = "b", fallbackIndex = 0, offset = 99),
            keys,
        )
        assertEquals(99, anchorHit?.offset)

        val anchorMiss = resolveRestoreIndex(
            ScrollCheckpoint(anchorKey = "missing", fallbackIndex = 1, offset = 99),
            keys,
        )
        assertEquals(99, anchorMiss?.offset)
    }

    @Test
    fun `resolveRestoreIndex prefers the anchor over the fallback when both would resolve`() {
        // The anchor wins because it survives message-list mutations that
        // shift indices without moving the user's logical position.
        val cp = ScrollCheckpoint(anchorKey = "msg-2", fallbackIndex = 0, offset = 0)
        val keys = listOf("msg-0", "msg-1", "msg-2", "msg-3")

        val out = resolveRestoreIndex(cp, keys)

        assertEquals(2, out?.index)
    }

    // ── lazyColumnKeyList ──────────────────────────────────────────────────

    @Test
    fun `lazyColumnKeyList is empty when there are no items`() {
        val out = lazyColumnKeyList(
            streamingReasoningPart = null,
            sessionDiff = null,
            renderBlocks = emptyList(),
            messages = emptyList(),
            hasMoreMessages = false,
            olderMessagesCursor = null,
        )
        assertEquals(emptyList<String>(), out)
    }

    @Test
    fun `lazyColumnKeyList prepends streaming-reasoning and session-diff and appends load-more`() {
        // The order MUST mirror the LazyColumn body so the anchor resolves
        // the same way scrollToItem sees it.
        val blockIds = listOf("block-1", "block-2", "block-3")
        val blocks = blockIds.map { id ->
            RenderBlock.Conversation(
                message = Message(id = id, role = "user"),
                parts = emptyList(),
                id = id,
            )
        }

        val out = lazyColumnKeyList(
            streamingReasoningPart = Part(id = "sr-1", type = "reasoning"),
            sessionDiff = listOf(FileDiff()),
            renderBlocks = blocks,
            messages = listOf(Message(id = "m1", role = "user")),
            hasMoreMessages = true,
            olderMessagesCursor = "cursor-1",
        )

        assertEquals(
            listOf("streaming-reasoning", "session-diff", "block-1", "block-2", "block-3", "load-more"),
            out,
        )
    }

    @Test
    fun `lazyColumnKeyList omits load-more when messages is empty`() {
        val out = lazyColumnKeyList(
            streamingReasoningPart = null,
            sessionDiff = null,
            renderBlocks = emptyList(),
            messages = emptyList(),
            hasMoreMessages = true,
            olderMessagesCursor = "cursor",
        )
        // No items → empty list.
        assertEquals(emptyList<String>(), out)
    }

    @Test
    fun `lazyColumnKeyList omits load-more when hasMoreMessages is false`() {
        val blocks = listOf(
            RenderBlock.Conversation(
                message = Message(id = "m1", role = "user"),
                parts = emptyList(),
                id = "m1",
            ),
        )
        val out = lazyColumnKeyList(
            streamingReasoningPart = null,
            sessionDiff = null,
            renderBlocks = blocks,
            messages = listOf(Message(id = "m1", role = "user")),
            hasMoreMessages = false,
            olderMessagesCursor = "cursor",
        )
        assertEquals(listOf("m1"), out)
    }

    @Test
    fun `lazyColumnKeyList omits load-more when olderMessagesCursor is null`() {
        val blocks = listOf(
            RenderBlock.Conversation(
                message = Message(id = "m1", role = "user"),
                parts = emptyList(),
                id = "m1",
            ),
        )
        val out = lazyColumnKeyList(
            streamingReasoningPart = null,
            sessionDiff = null,
            renderBlocks = blocks,
            messages = listOf(Message(id = "m1", role = "user")),
            hasMoreMessages = true,
            olderMessagesCursor = null,
        )
        assertEquals(listOf("m1"), out)
    }

    @Test
    fun `lazyColumnKeyList omits session-diff when null or empty`() {
        val blocks = listOf(
            RenderBlock.Conversation(
                message = Message(id = "m1", role = "user"),
                parts = emptyList(),
                id = "m1",
            ),
        )
        // null
        val outNull = lazyColumnKeyList(
            streamingReasoningPart = null,
            sessionDiff = null,
            renderBlocks = blocks,
            messages = listOf(Message(id = "m1", role = "user")),
            hasMoreMessages = false,
            olderMessagesCursor = null,
        )
        assertEquals(listOf("m1"), outNull)

        // empty list
        val outEmpty = lazyColumnKeyList(
            streamingReasoningPart = null,
            sessionDiff = emptyList(),
            renderBlocks = blocks,
            messages = listOf(Message(id = "m1", role = "user")),
            hasMoreMessages = false,
            olderMessagesCursor = null,
        )
        assertEquals(listOf("m1"), outEmpty)
    }

    // ── end-to-end: capture-via-keys-then-restore roundtrip ────────────────

    @Test
    fun `roundtrip - capture anchor from keys list, resolve back to same index`() {
        // Simulates: user is viewing msg-3 (index 2 in 0-indexed visible list,
        // but with streaming-reasoning + session-diff above, it's index 4 in
        // the LazyColumn). Captures checkpoint, then resolves back.
        val keys = lazyColumnKeyList(
            streamingReasoningPart = Part(id = "sr", type = "reasoning"),
            sessionDiff = listOf(FileDiff()),
            renderBlocks = listOf(
                RenderBlock.Conversation(Message(id = "m1", role = "user"), emptyList(), id = "m1"),
                RenderBlock.Conversation(Message(id = "m2", role = "user"), emptyList(), id = "m2"),
                RenderBlock.Conversation(Message(id = "m3", role = "user"), emptyList(), id = "m3"),
            ),
            messages = listOf(
                Message(id = "m1", role = "user"),
                Message(id = "m2", role = "user"),
                Message(id = "m3", role = "user"),
            ),
            hasMoreMessages = false,
            olderMessagesCursor = null,
        )
        // Capture: index 4 in the LazyColumn is "m3" (after streaming-reasoning + session-diff + m1 + m2).
        assertEquals("streaming-reasoning", keys[0])
        assertEquals("session-diff", keys[1])
        assertEquals("m1", keys[2])
        assertEquals("m2", keys[3])
        assertEquals("m3", keys[4])

        val captured = ScrollCheckpoint(anchorKey = "m3", fallbackIndex = 4, offset = 50)

        val resolved = resolveRestoreIndex(captured, keys)

        assertEquals(ResolvedRestore(index = 4, offset = 50), resolved)
    }
}
