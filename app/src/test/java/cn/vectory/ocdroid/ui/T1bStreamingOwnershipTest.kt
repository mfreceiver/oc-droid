package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.ui.controller.applyPartCreatedPlaceholder
import cn.vectory.ocdroid.ui.controller.applyPartDeltaLeadingEdge
import cn.vectory.ocdroid.ui.controller.applyPartFullTextLeadingEdge
import cn.vectory.ocdroid.ui.controller.appendDeltaBuffer
import cn.vectory.ocdroid.ui.controller.clearAllCoalesceBuffers
import cn.vectory.ocdroid.ui.controller.clearCoalesceBufferForPart
import cn.vectory.ocdroid.ui.controller.flushCoalesceBufferForPart
import cn.vectory.ocdroid.ui.controller.markFlushPending
import cn.vectory.ocdroid.ui.controller.replaceFullTextBuffer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1b freeze — **RED until impl**. Step-2 `streamingPartTexts`-family
 * ownership migration (full-refactor-plan §2.3 ownership table row 1 +
 * §3.2 reducer skeleton + §4-T1 acceptance).
 *
 * Migrates the streaming-path production write sites in
 * `SessionSyncCoordinator.dispatchSseEvent` that today call
 * `slices.mutateChat { c -> c.applyPartXxx(...).first.markFlushPending(key).first }`
 * to `dispatch(AppAction.*)`.
 *
 * **Expected action vocabulary** (T1b impl MUST add to `AppAction`):
 *
 * ```kotlin
 * // ── Two-phase placeholder window ────────────────────────────────────────
 * // Phase 1 (SSC:1362 — applyPartCreatedPlaceholder). Writes partsByMessage
 * // placeholder + streamingReasoningPart; does NOT write streamingPartTexts.
 * data class PartPlaceholderEnsured(
 *     val partType: String,   // "text" | "reasoning"
 *     val partId: String,     // pId
 *     val messageId: String,  // msgId
 *     val sessionId: String,  // deltaEvent.sessionId
 * ) : AppAction
 *
 * // Phase 2 leading edge — fullText (SSC:1397-1406). Writes streamingPartTexts
 * // (REPLACE) + streamingReasoningPart + partsByMessage placeholder + pendingFlushPartIds.
 * data class PartFullTextReceived(
 *     val partId: String, val fullText: String, val partType: String,
 *     val messageId: String, val sessionId: String,
 * ) : AppAction
 *
 * // Phase 2 leading edge — delta (SSC:1539-1547 message.part.delta handler).
 * // Writes streamingPartTexts (APPEND) + streamingReasoningPart + partsByMessage
 * // placeholder + pendingFlushPartIds.
 * data class PartDeltaReceived(
 *     val partId: String, val delta: String, val partType: String,
 *     val messageId: String, val sessionId: String,
 * ) : AppAction
 *
 * // ── Trailing coalesce buffers ───────────────────────────────────────────
 * // SSC:1421 (replaceFullTextBuffer). REPLACE semantics.
 * data class FullTextBuffered(val partId: String, val text: String) : AppAction
 *
 * // SSC:1459 / :1552 (appendDeltaBuffer). APPEND semantics.
 * data class DeltaBuffered(val partId: String, val delta: String) : AppAction
 *
 * // ── Flush / clear ───────────────────────────────────────────────────────
 * // SSC:1850 (flushCoalesceBufferForPart). Flushes buffered delta/fullText
 * // into streamingPartTexts, then clears the 3 coalesce entries for partId.
 * data class CoalesceFlushedForPart(val partId: String) : AppAction
 *
 * // SSC:1864 (clearCoalesceBufferForPart). Drops partId's buffers WITHOUT flushing.
 * data class CoalesceClearedForPart(val partId: String) : AppAction
 *
 * // SSC:1877 (clearAllCoalesceBuffers — clearDeltaBuffers). Drops ALL parts'
 * // buffers. Does NOT clear streamingPartTexts/streamingReasoningPart.
 * data object CoalesceBuffersCleared : AppAction
 * ```
 *
 * The reducer MUST delegate to the SAME pure functions the legacy
 * `mutateChat` path uses (the imports above). This is a mechanical 1:1
 * migration — no semantic change, no two-phase merge.
 *
 * **RED kind**: `compile-error` — the action types above are unresolved
 * references today. Once impl adds them + the reduce branches, every test
 * goes GREEN unchanged.
 *
 * **Hard constraint (§9-3 two-phase window)**: a streaming SSE event for a
 * new part dispatches TWO actions in sequence — `PartPlaceholderEnsured`
 * then `PartFullTextReceived` / `PartDeltaReceived`. These MUST stay as two
 * separate dispatches (two emissions); merging them into one action would
 * change the observable intermediate state and break the flicker-fix
 * contract (SSC:1365-1379 §streaming-flicker-diagnosis Top1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class T1bStreamingOwnershipTest {

    // ── A. Reduce equivalence (old mutateChat path ≡ new dispatch path) ─────
    //
    // For each streaming action, seed two stores identically; apply the legacy
    // pure-transform path on one and the new dispatch path on the other;
    // assert byte-for-byte StoreState equality.

    @Test
    fun `dispatch PartPlaceholderEnsured is byte-for-byte equivalent to applyPartCreatedPlaceholder`() = runTest {
        val (oldStore, newStore) = seededPair()

        oldStore.mutateChat { c ->
            c.applyPartCreatedPlaceholder("text", "p1", "m1", "sess-A").first
        }
        newStore.dispatch(AppAction.PartPlaceholderEnsured("text", "p1", "m1", "sess-A"))

        assertEquals(
            "PartPlaceholderEnsured MUST equal legacy applyPartCreatedPlaceholder",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
    }

    @Test
    fun `dispatch PartFullTextReceived is byte-for-byte equivalent to applyPartFullTextLeadingEdge plus markFlushPending`() = runTest {
        val (oldStore, newStore) = seededPair()

        oldStore.mutateChat { c ->
            c.applyPartFullTextLeadingEdge("p1", "hello", "text", "p1", "m1", "sess-A").first
                .markFlushPending("p1").first
        }
        newStore.dispatch(AppAction.PartFullTextReceived("p1", "hello", "text", "m1", "sess-A"))

        assertEquals(
            "PartFullTextReceived MUST equal legacy applyPartFullTextLeadingEdge + markFlushPending",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
    }

    @Test
    fun `dispatch PartDeltaReceived is byte-for-byte equivalent to applyPartDeltaLeadingEdge plus markFlushPending`() = runTest {
        val (oldStore, newStore) = seededPair()

        oldStore.mutateChat { c ->
            c.applyPartDeltaLeadingEdge("p1", "delta-chunk", "text", "m1", "sess-A").first
                .markFlushPending("p1").first
        }
        newStore.dispatch(AppAction.PartDeltaReceived("p1", "delta-chunk", "text", "m1", "sess-A"))

        assertEquals(
            "PartDeltaReceived MUST equal legacy applyPartDeltaLeadingEdge + markFlushPending",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
    }

    @Test
    fun `dispatch FullTextBuffered is byte-for-byte equivalent to replaceFullTextBuffer`() = runTest {
        val (oldStore, newStore) = seededPair(streaming = mapOf("p1" to "first"))

        oldStore.mutateChat { c -> c.replaceFullTextBuffer("p1", "replaced-full").first }
        newStore.dispatch(AppAction.FullTextBuffered("p1", "replaced-full"))

        assertEquals(
            "FullTextBuffered MUST equal legacy replaceFullTextBuffer (REPLACE semantics)",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
    }

    @Test
    fun `dispatch DeltaBuffered is byte-for-byte equivalent to appendDeltaBuffer`() = runTest {
        val (oldStore, newStore) = seededPair()

        // Two trailing deltas to exercise the APPEND accumulation.
        oldStore.mutateChat { c -> c.appendDeltaBuffer("p1", "chunk-1").first }
        newStore.dispatch(AppAction.DeltaBuffered("p1", "chunk-1"))
        assertEquals(oldStore.stateFlow.value, newStore.stateFlow.value)

        oldStore.mutateChat { c -> c.appendDeltaBuffer("p1", "chunk-2").first }
        newStore.dispatch(AppAction.DeltaBuffered("p1", "chunk-2"))

        assertEquals(
            "DeltaBuffered MUST equal legacy appendDeltaBuffer (APPEND accumulation across multiple calls)",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
    }

    @Test
    fun `dispatch CoalesceFlushedForPart is byte-for-byte equivalent to flushCoalesceBufferForPart`() = runTest {
        // Seed: streamingPartTexts has an entry + deltaBuffer has buffered text.
        // The flush APPENDS the buffered delta into streamingPartTexts and
        // clears all 3 coalesce entries.
        val (oldStore, newStore) = seededPair(
            streaming = mapOf("p1" to "leading-text"),
            deltaBuf = mapOf("p1" to "buffered-tail"),
        )

        oldStore.mutateChat { c -> c.flushCoalesceBufferForPart("p1").first }
        newStore.dispatch(AppAction.CoalesceFlushedForPart("p1"))

        assertEquals(
            "CoalesceFlushedForPart MUST equal legacy flushCoalesceBufferForPart",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
        // Sanity: the flush actually ran (streaming text accumulated + buffers cleared).
        assertEquals(
            "streamingPartTexts accumulated the buffered tail",
            "leading-textbuffered-tail",
            newStore.stateFlow.value.chat.streamingPartTexts["p1"],
        )
        assertFalse("deltaBuffer cleared for p1", newStore.stateFlow.value.chat.deltaBuffer.containsKey("p1"))
    }

    @Test
    fun `dispatch CoalesceFlushedForPart with buffered fullText wins over buffered delta (REPLACE path)`() = runTest {
        // §Site1-coalesce: a buffered fullText wins (REPLACE) over buffered delta (APPEND).
        val (oldStore, newStore) = seededPair(
            streaming = mapOf("p1" to "leading"),
            deltaBuf = mapOf("p1" to "buffered-delta"),
            fullTextBuf = mapOf("p1" to "buffered-fulltext"),
        )

        oldStore.mutateChat { c -> c.flushCoalesceBufferForPart("p1").first }
        newStore.dispatch(AppAction.CoalesceFlushedForPart("p1"))

        assertEquals(
            "CoalesceFlushedForPart REPLACE path (fullText wins over delta) MUST match legacy",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
        assertEquals(
            "the fullText REPLACES the streaming overlay (not appended)",
            "buffered-fulltext",
            newStore.stateFlow.value.chat.streamingPartTexts["p1"],
        )
    }

    @Test
    fun `dispatch CoalesceClearedForPart is byte-for-byte equivalent to clearCoalesceBufferForPart`() = runTest {
        val (oldStore, newStore) = seededPair(
            streaming = mapOf("p1" to "text"),
            deltaBuf = mapOf("p1" to "buffered"),
            pending = setOf("p1"),
        )

        oldStore.mutateChat { c -> c.clearCoalesceBufferForPart("p1").first }
        newStore.dispatch(AppAction.CoalesceClearedForPart("p1"))

        assertEquals(
            "CoalesceClearedForPart MUST equal legacy clearCoalesceBufferForPart (drops buffers, preserves overlay)",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
        // Sanity: overlay preserved (clear ≠ flush — streaming text NOT consumed).
        assertEquals(
            "streamingPartTexts preserved (CoalesceCleared drops buffers only, NOT the overlay)",
            "text",
            newStore.stateFlow.value.chat.streamingPartTexts["p1"],
        )
    }

    @Test
    fun `dispatch CoalesceBuffersCleared is byte-for-byte equivalent to clearAllCoalesceBuffers`() = runTest {
        val (oldStore, newStore) = seededPair(
            streaming = mapOf("p1" to "text-1", "p2" to "text-2"),
            deltaBuf = mapOf("p1" to "buf-1"),
            fullTextBuf = mapOf("p2" to "buf-2"),
            pending = setOf("p1", "p2"),
        )

        oldStore.mutateChat { c -> c.clearAllCoalesceBuffers().first }
        newStore.dispatch(AppAction.CoalesceBuffersCleared)

        assertEquals(
            "CoalesceBuffersCleared MUST equal legacy clearAllCoalesceBuffers",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
        // Sanity: overlay preserved (clearDeltaBuffers clears coalesce state only).
        assertEquals(
            "streamingPartTexts preserved (clearDeltaBuffers ≠ overlay wipe)",
            mapOf("p1" to "text-1", "p2" to "text-2"),
            newStore.stateFlow.value.chat.streamingPartTexts,
        )
        assertTrue("all delta buffers cleared", newStore.stateFlow.value.chat.deltaBuffer.isEmpty())
        assertTrue("all fullText buffers cleared", newStore.stateFlow.value.chat.fullTextBuffer.isEmpty())
        assertTrue("all pending-flush ids cleared", newStore.stateFlow.value.chat.pendingFlushPartIds.isEmpty())
    }

    // ── B. Two-phase placeholder window (hard constraint — §9-3) ────────────
    //
    // A streaming SSE event for a NEW part dispatches TWO actions in sequence:
    //   1. PartPlaceholderEnsured (phase 1 — injects the typed Part into
    //      partsByMessage + sets streamingReasoningPart; streamingPartTexts
    //      is NOT yet populated).
    //   2. PartDeltaReceived / PartFullTextReceived (phase 2 — populates
    //      streamingPartTexts + markFlushPending).
    //
    // These MUST stay as TWO separate dispatches (two stateFlow emissions).
    // Merging them into one action would collapse the observable intermediate
    // state and break the §streaming-flicker-diagnosis (Top1) contract.

    @Test
    fun `two-phase placeholder dispatches TWO actions producing TWO emissions with the correct intermediate state`() = runTest {
        val store = SharedStateStore()
        // Seed: assistant message exists, no parts, no streaming overlay.
        store.mutateChat {
            it.copy(currentSessionId = "sess-A", messages = listOf(Message(id = "m1", role = "assistant")))
        }

        val seen = mutableListOf<StoreState>()
        val job = launch { store.stateFlow.collect { seen += it } }
        advanceUntilIdle()
        assertEquals("initial state emitted once", 1, seen.size)

        // Phase 1: placeholder ensured (SSC:1362 — the first mutateChat).
        store.dispatch(AppAction.PartPlaceholderEnsured("text", "p1", "m1", "sess-A"))
        advanceUntilIdle()

        assertEquals(
            "phase 1 produced exactly ONE new emission (two-phase window — NOT merged into phase 2)",
            2,
            seen.size,
        )
        val intermediate = seen[1]
        // The intermediate state has the placeholder Part in partsByMessage
        // but streamingPartTexts is NOT yet populated (the flicker-fix
        // intermediate state — §streaming-flicker-diagnosis Top1).
        assertNotNull(
            "phase 1 intermediate: placeholder Part injected into partsByMessage",
            intermediate.chat.partsByMessage["m1"]?.any { it.id == "p1" },
        )
        assertFalse(
            "phase 1 intermediate: streamingPartTexts NOT yet populated (two-phase window open)",
            intermediate.chat.streamingPartTexts.containsKey("p1"),
        )

        // Phase 2: leading-edge delta (SSC:1539 — the second mutateChat).
        store.dispatch(AppAction.PartDeltaReceived("p1", "hello", "text", "m1", "sess-A"))
        advanceUntilIdle()

        assertEquals(
            "phase 2 produced exactly ONE more emission (total: initial + phase1 + phase2 = 3)",
            3,
            seen.size,
        )
        val final = seen[2]
        assertEquals(
            "phase 2 final: streamingPartTexts now populated (two-phase window closed)",
            "hello",
            final.chat.streamingPartTexts["p1"],
        )
        // The placeholder Part survived into the final state.
        assertNotNull(
            "phase 2 final: placeholder Part still in partsByMessage",
            final.chat.partsByMessage["m1"]?.any { it.id == "p1" },
        )
        assertTrue(
            "phase 2 final: partId marked as flush-pending",
            final.chat.pendingFlushPartIds.contains("p1"),
        )
        job.cancel()
    }

    @Test
    fun `two-phase placeholder with fullText leading edge also produces TWO emissions`() = runTest {
        // The fullText variant (SSC:1397) is the same two-phase pattern —
        // phase 1 placeholder + phase 2 fullText leading edge. Pinning the
        // emission count for BOTH variants so the impl cannot accidentally
        // merge one but not the other.
        val store = SharedStateStore()
        store.mutateChat {
            it.copy(currentSessionId = "sess-A", messages = listOf(Message(id = "m1", role = "assistant")))
        }

        val seen = mutableListOf<StoreState>()
        val job = launch { store.stateFlow.collect { seen += it } }
        advanceUntilIdle()

        store.dispatch(AppAction.PartPlaceholderEnsured("reasoning", "p1", "m1", "sess-A"))
        advanceUntilIdle()
        store.dispatch(AppAction.PartFullTextReceived("p1", "thinking...", "reasoning", "m1", "sess-A"))
        advanceUntilIdle()

        assertEquals(
            "fullText two-phase: initial + placeholder + fullText = 3 emissions (NOT merged)",
            3,
            seen.size,
        )
        // streamingReasoningPart set (reasoning type triggers it).
        assertNotNull(
            "streamingReasoningPart set for reasoning type",
            seen.last().chat.streamingReasoningPart,
        )
        job.cancel()
    }

    // ── D. Scoping — streaming actions don't touch non-streaming slices ────

    @Test
    fun `streaming actions are scoped to chat streaming fields — sessions and expandedParts untouched`() = runTest {
        val prior = StoreState.initial().copy(
            // Seed non-streaming slices with non-default values.
            sessionList = SessionListState(sessions = listOf(Session(id = "sess-A", directory = "/p"))),
            expandedParts = mapOf("fold:m1:p1" to true),
            // Seed chat non-streaming fields too.
            chat = ChatState(
                currentSessionId = "sess-A",
                messages = listOf(Message(id = "m1", role = "assistant")),
                partsByMessage = mapOf("m1" to emptyList()),
            ),
        )
        val store = SharedStateStore().apply { mutateState { prior } }

        // Apply a PartDeltaReceived (the highest-touch streaming action —
        // writes streamingPartTexts + partsByMessage + streamingReasoningPart
        // + pendingFlushPartIds).
        store.dispatch(AppAction.PartDeltaReceived("p1", "delta", "text", "m1", "sess-A"))

        val out = store.stateFlow.value
        // Non-chat slices untouched.
        assertEquals("sessionList untouched by streaming action", prior.sessionList, out.sessionList)
        assertEquals("expandedParts untouched by streaming action", prior.expandedParts, out.expandedParts)
        assertEquals("composer untouched by streaming action", prior.composer, out.composer)
        // Non-streaming chat fields untouched.
        assertEquals("currentSessionId untouched by streaming action", prior.chat.currentSessionId, out.chat.currentSessionId)
        assertEquals("messages untouched by streaming action", prior.chat.messages, out.chat.messages)
        assertEquals("olderMessagesCursor untouched by streaming action", prior.chat.olderMessagesCursor, out.chat.olderMessagesCursor)
        assertEquals("currentModel untouched by streaming action", prior.chat.currentModel, out.chat.currentModel)
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * Builds a pair of identically-seeded [SharedStateStore]s for old-vs-new
     * path equivalence testing. Seeds a current-session assistant message +
     * optional streaming/coalesce state.
     */
    private fun seededPair(
        streaming: Map<String, String> = emptyMap(),
        deltaBuf: Map<String, String> = emptyMap(),
        fullTextBuf: Map<String, String> = emptyMap(),
        pending: Set<String> = emptySet(),
    ): Pair<SharedStateStore, SharedStateStore> {
        fun build() = SharedStateStore().apply {
            mutateChat {
                it.copy(
                    currentSessionId = "sess-A",
                    messages = listOf(Message(id = "m1", role = "assistant")),
                    partsByMessage = mapOf("m1" to emptyList()),
                    streamingPartTexts = streaming,
                    deltaBuffer = deltaBuf,
                    fullTextBuffer = fullTextBuf,
                    pendingFlushPartIds = pending,
                )
            }
        }
        val old = build()
        val new = build()
        // Sanity: both seeds start byte-for-byte equal.
        assertEquals(old.stateFlow.value, new.stateFlow.value)
        return old to new
    }
}
