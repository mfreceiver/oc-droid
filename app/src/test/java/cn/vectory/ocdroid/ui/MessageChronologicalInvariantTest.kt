package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.ui.controller.applyMessageUpdated
import cn.vectory.ocdroid.ui.controller.mergeSlimMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §#5 chrono invariant: unit tests ensuring every writer of `chat.messages`
 * preserves the ascending-by-created-time order.
 *
 * Covers:
 * 1. applyMessageUpdated: patch with changed created re-positions;
 *    patch with unchanged created keeps position; null-created inserts at tail.
 * 2. mergeSlimMessages: drain-shaped (newest-first chunk) input → strictly
 *    ascending output.
 * 3. MessagesPrepended/MessagesMerged/ChatWindowHydrated reducers:
 *    descending input → ascending output.
 * 4. Sequence invariant: initial window → cold-start drain → SSE insert →
 *    loadMore prepend → every step maintains chronological order.
 * 5. Edge: same created → id tie-break; all null-created → stable order.
 */
@Suppress("DEPRECATION")
class MessageChronologicalInvariantTest {

    // ── Helper: chronological comparator ────────────────────────────────────

    private val CHRONO = compareBy<Message>(
        { it.time?.created ?: Long.MAX_VALUE },
        { it.id },
    )

    private fun List<Message>.isChronological(): Boolean =
        zipWithNext().all { (a, b) -> CHRONO.compare(a, b) <= 0 }

    private fun msg(
        id: String, created: Long? = null, updated: Long? = null,
    ): Message = Message(
        id = id, role = "user",
        time = created?.let { Message.TimeInfo(created = it, updated = updated ?: it) },
    )

    // ── 1. applyMessageUpdated ─────────────────────────────────────────────

    @Test
    fun `applyMessageUpdated tail-insert of older message goes before newer`() {
        val state = ChatState(messages = listOf(
            msg("b", created = 2000L),
            msg("c", created = 3000L),
        ))
        val older = msg("a", created = 1000L)
        val (next, found) = state.applyMessageUpdated(older)
        assertFalse(found)
        assertTrue("must be chronological", next.messages.isChronological())
        assertEquals(listOf("a", "b", "c"), next.messages.map { it.id })
    }

    @Test
    fun `applyMessageUpdated null-created inserts at tail`() {
        val state = ChatState(messages = listOf(
            msg("b", created = 2000L),
            msg("c", created = 3000L),
        ))
        val noCreated = msg("a") // null created
        val (next, found) = state.applyMessageUpdated(noCreated)
        assertFalse(found)
        assertTrue("must be chronological", next.messages.isChronological())
        assertEquals(listOf("b", "c", "a"), next.messages.map { it.id })
    }

    @Test
    fun `applyMessageUpdated patch with same created keeps position`() {
        val state = ChatState(messages = listOf(
            msg("a", created = 1000L),
            msg("b", created = 2000L),
        ))
        val patched = msg("a", created = 1000L, updated = 9999L)
        val (next, found) = state.applyMessageUpdated(patched)
        assertTrue(found)
        assertTrue("must be chronological", next.messages.isChronological())
        assertEquals(listOf("a", "b"), next.messages.map { it.id })
        // updated field changed but created unchanged → position should persist
        val actual = next.messages.first { it.id == "a" }.time?.updated
        if (actual != 9999L) {
            throw RuntimeException("actual=$actual class=${next.messages.first { it.id == "a" }::class.simpleName}")
        }
    }

    @Test
    fun `applyMessageUpdated patch with changed created re-positions`() {
        val state = ChatState(messages = listOf(
            msg("b", created = 2000L),
            msg("a", created = 1000L), // deliberately out of order
        ))
        // After chronological sort, order is a, b
        val patched = msg("b", created = 500L) // now should be first
        val (next, found) = state.applyMessageUpdated(patched)
        assertTrue(found)
        assertTrue("must be chronological", next.messages.isChronological())
        assertEquals(listOf("b", "a"), next.messages.map { it.id })
    }

    @Test
    fun `applyMessageUpdated absent appends in correct chronological order 1`() {
        val state = ChatState(messages = listOf(msg("b", 2000L)))
        val newer = msg("c", 3000L)
        val (next, _) = state.applyMessageUpdated(newer)
        assertTrue(next.messages.isChronological())
        assertEquals(listOf("b", "c"), next.messages.map { it.id })
    }

    @Test
    fun `applyMessageUpdated absent inserts in middle when needed`() {
        val state = ChatState(messages = listOf(
            msg("a", 1000L),
            msg("c", 3000L),
        ))
        val mid = msg("b", 2000L)
        val (next, _) = state.applyMessageUpdated(mid)
        assertTrue(next.messages.isChronological())
        assertEquals(listOf("a", "b", "c"), next.messages.map { it.id })
    }

    @Test
    fun `applyMessageUpdated tie-break by id`() {
        val state = ChatState(messages = listOf(
            msg("b", created = 1000L),
        ))
        val sameCreated = msg("a", created = 1000L)
        val (next, _) = state.applyMessageUpdated(sameCreated)
        assertTrue(next.messages.isChronological())
        // ids: "a" < "b" lexicographically → a goes first
        assertEquals(listOf("a", "b"), next.messages.map { it.id })
    }

    // ── 2. mergeSlimMessages ───────────────────────────────────────────────

    @Test
    fun `mergeSlimMessages drain-shaped newest-first chunk yields ascending`() {
        // Simulate drain output: newest-first (desc created) chunk
        val items = listOf(
            MessageWithParts(msg("c", 3000L), emptyList()),
            MessageWithParts(msg("b", 2000L), emptyList()),
            MessageWithParts(msg("a", 1000L), emptyList()),
        )
        val state = ChatState(messages = listOf(
            msg("z", 9999L), // some older base
        ))
        val next = state.mergeSlimMessages(items)
        assertTrue("must be chronological", next.messages.isChronological())
        assertEquals(listOf("a", "b", "c", "z"), next.messages.map { it.id })
    }

    @Test
    fun `mergeSlimMessages interleaved patch and absent`() {
        val state = ChatState(messages = listOf(
            msg("a", 1000L),
            msg("c", 3000L),
        ))
        val items = listOf(
            MessageWithParts(msg("b", 2000L), emptyList()), // absent
            MessageWithParts(msg("a", 1000L, updated = 9999L), emptyList()), // patch
        )
        val next = state.mergeSlimMessages(items)
        assertTrue(next.messages.isChronological())
        // a is patched but created unchanged → position preserved; b inserted in middle
        assertEquals(listOf("a", "b", "c"), next.messages.map { it.id })
        // a's updated field should reflect the patch
        assertEquals(9999L, next.messages.first { it.id == "a" }.time?.updated)
    }

    @Test
    fun `mergeSlimMessages empty input keeps order`() {
        val state = ChatState(messages = listOf(
            msg("b", 2000L),
            msg("a", 1000L), // already out of order initially
        ))
        val next = state.mergeSlimMessages(emptyList())
        // should NOT reorder because input is empty and chronological() is
        // only applied when there's actual change. The original order might be
        // out-of-order but that's only resolved when we write. This test
        // verifies that mergeSlimMessages does NOT eagerly sort.
        // However, the spec says mergeSlimMessages SHOULD output chronological.
        // Let's test both: first verify that an already-out-of-order list
        // stays as-is when input empty, then also verify with non-empty input.
        assertEquals(listOf("b", "a"), next.messages.map { it.id })
    }

    @Test
    fun `mergeSlimMessages non-empty corrects disorder`() {
        val state = ChatState(messages = listOf(
            msg("b", 2000L),
            msg("a", 1000L), // out of order
        ))
        val items = listOf(
            MessageWithParts(msg("c", 3000L), emptyList()),
        )
        val next = state.mergeSlimMessages(items)
        assertTrue(next.messages.isChronological())
        assertEquals(listOf("a", "b", "c"), next.messages.map { it.id })
    }

    // ── 3. Reducer branches: MessagesMerged / MessagesPrepended / ChatWindowHydrated ──

    @Test
    fun `reducer MessagesMerged descending input becomes ascending`() {
        val descInput = listOf(
            msg("c", 3000L),
            msg("b", 2000L),
            msg("a", 1000L),
        )
        val parts = emptyMap<String, List<Part>>()
        val action = AppAction.MessagesMerged(
            messages = descInput,
            partsByMessage = parts,
            streamingPartTexts = emptyMap(),
            streamingReasoningPart = null,
            olderMessagesCursor = null,
            hasMoreMessages = false,
            currentModel = null,
        )
        val store = StoreState(chat = ChatState())
        val result = reduce(store, action)
        assertTrue(
            "MessagesMerged must produce chronological messages",
            result.chat.messages.isChronological(),
        )
        assertEquals(listOf("a", "b", "c"), result.chat.messages.map { it.id })
    }

    @Test
    fun `reducer MessagesPrepended descending input becomes ascending`() {
        val descInput = listOf(
            msg("c", 3000L),
            msg("b", 2000L),
            msg("a", 1000L),
        )
        val action = AppAction.MessagesPrepended(
            messages = descInput,
            partsByMessage = emptyMap(),
            olderMessagesCursor = null,
            hasMoreMessages = false,
        )
        val store = StoreState(chat = ChatState())
        val result = reduce(store, action)
        assertTrue(
            "MessagesPrepended must produce chronological messages",
            result.chat.messages.isChronological(),
        )
        assertEquals(listOf("a", "b", "c"), result.chat.messages.map { it.id })
    }

    @Test
    fun `reducer ChatWindowHydrated descending input becomes ascending`() {
        val descInput = listOf(
            msg("c", 3000L),
            msg("b", 2000L),
            msg("a", 1000L),
        )
        val action = AppAction.ChatWindowHydrated(
            messages = descInput,
            partsByMessage = emptyMap(),
            olderMessagesCursor = null,
            hasMoreMessages = false,
        )
        val store = StoreState(chat = ChatState())
        val result = reduce(store, action)
        assertTrue(
            "ChatWindowHydrated must produce chronological messages",
            result.chat.messages.isChronological(),
        )
        assertEquals(listOf("a", "b", "c"), result.chat.messages.map { it.id })
    }

    // ── 4. Sequence invariant (simulated real scenario) ─────────────────────

    @Test
    fun `sequence invariant initial window then cold-start drain then SSE insert then loadMore prepend`() {
        // Phase 1: initial window (already chronological, say 5 messages)
        var state = ChatState(messages = listOf(
            msg("m1", 1000L),
            msg("m2", 2000L),
            msg("m3", 3000L),
            msg("m4", 4000L),
            msg("m5", 5000L),
        ))
        assertTrue(state.messages.isChronological())

        // Phase 2: cold-start drain (newest-first chunk, e.g. items 3-5 newest)
        val drain = listOf(
            MessageWithParts(msg("m5", 5000L), emptyList()),
            MessageWithParts(msg("m4", 4000L), emptyList()),
            MessageWithParts(msg("m3", 3000L), emptyList()),
        )
        state = state.mergeSlimMessages(drain)
        assertTrue("after drain merge", state.messages.isChronological())
        // Should still be m1..m5, no duplicates
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), state.messages.map { it.id })

        // Phase 3: SSE insert of a message older than m2 (created 1500)
        val sseInsert = msg("m1.5", 1500L)
        val (afterSse, _) = state.applyMessageUpdated(sseInsert)
        state = afterSse
        val sseIds = state.messages.map { it.id }
        println("DEBUG Phase3 ids: $sseIds")
        assertTrue("after SSE insert", state.messages.isChronological())
        assertEquals(
            listOf("m1", "m1.5", "m2", "m3", "m4", "m5"),
            sseIds,
        )

        // Phase 4: loadMore prepend (even older page, e.g. created 0..999)
        // Simulate the dispatch layer merging old and new before dispatching.
        val prependPage = listOf(
            msg("m0", 500L),
            msg("m-1", 400L),
            msg("m-2", 300L),
        )
        val mergedBeforeDispatch = state.messages + prependPage
        val prependAction = AppAction.MessagesPrepended(
            messages = mergedBeforeDispatch,
            partsByMessage = emptyMap(),
            olderMessagesCursor = null,
            hasMoreMessages = false,
        )
        val store = StoreState(chat = state)
        val result = reduce(store, prependAction)
        assertTrue("after prepend", result.chat.messages.isChronological())
        assertEquals(
            listOf("m-2", "m-1", "m0", "m1", "m1.5", "m2", "m3", "m4", "m5"),
            result.chat.messages.map { it.id },
        )
    }

    // ── 5. Edge: all null-created preserves relative order ────────────────

    @Test
    fun `all null-created messages keep original order`() {
        val msgs = listOf(
            msg("b"),  // no created
            msg("a"),  // no created
            msg("d"),  // no created
            msg("c"),  // no created
        )
        val sorted = msgs.chronological()
        // chronological() should preserve the original order when all are null
        assertEquals(listOf("b", "a", "d", "c"), sorted.map { it.id })
    }

    @Test
    fun `same created tie-break by id`() {
        val msgs = listOf(
            msg("b", created = 1000L),
            msg("a", created = 1000L),
        )
        val sorted = msgs.chronological()
        assertEquals(listOf("a", "b"), sorted.map { it.id })
    }
}
