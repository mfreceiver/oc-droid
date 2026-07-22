package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.ui.chat.ExpandPartsOutcome
import cn.vectory.ocdroid.ui.chat.PartExpandState
import cn.vectory.ocdroid.ui.chat.PartKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1b writeChat-bypass ownership tests (incl. ExpandedParts CAS must-fix).
 *
 * Covers the last two production `writeChat` (= `mutateChat` alias) bypass
 * write sites on §2.3 target fields. Scan audit must check BOTH
 * `mutateChat` AND `writeChat` (`AppCore.writeChat` → `store.mutateChat`).
 *
 * **Action vocabulary**:
 *
 * ```kotlin
 * // A. ColdStartChatReset — 8-field cold-start reset (unchanged)
 * data object ColdStartChatReset : AppAction
 *
 * // B. ExpandedPartsContentCommitted — Strategy 2 (CAS must-fix)
 * // Carries raw ExpandPartsOutcome + local; reduce runs pure
 * // ChatState.reconcileExpandedPartsContent against the LATEST chat
 * // inside state.update CAS (restores writeChat merge-on-current semantics).
 * data class ExpandedPartsContentCommitted(
 *     val outcome: ExpandPartsOutcome,
 *     val local: List<MessageWithParts>,
 *     val expectedSessionId: String,
 * ) : AppAction
 * // reduce: state.copy(chat = state.chat.reconcileExpandedPartsContent(
 * //     action.outcome, action.local, action.expectedSessionId))
 * ```
 *
 * **Guards**: session inside pure reconcile; fp stays at ChatViewModel
 * call site (reducer purity).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class T1bWriteChatBypassOwnershipTest {

    // ═══════════════════════════════════════════════════════════════════════
    // A. ColdStartChatReset — 8-field reset (AppCoreOrchestration:577-590)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `dispatch ColdStartChatReset is byte-for-byte equivalent to performGlobalColdStartRefresh writeChat`() = runTest {
        // Seed with NON-DEFAULT values for every field the reset clears so
        // the clear is observable (an all-default seed would not distinguish
        // a real clear from a no-op).
        val seed = ChatState(
            currentSessionId = "sess-A",
            messages = listOf(Message(id = "m1", role = "user")),
            partsByMessage = mapOf("m1" to listOf(Part(id = "p1", messageId = "m1", type = "text"))),
            streamingPartTexts = mapOf("p1" to "streaming"),
            streamingReasoningPart = Part(id = "r1", type = "reasoning"),
            staleNotice = true,
            olderMessagesCursor = "old-cursor",
            hasMoreMessages = true,
            isLoadingMoreMessages = true,
            // Non-target fields seeded with non-defaults for scoping.
            currentModel = Message.ModelInfo("prov", "model"),
            pendingAgent = "agent",
            isLoadingMessages = true,
        )
        val oldStore = SharedStateStore().apply { mutateChat { seed } }
        val newStore = SharedStateStore().apply { mutateChat { seed } }

        // Old path: replicate AppCoreOrchestration.kt:577-590 writeChat verbatim.
        oldStore.mutateChat { c ->
            c.copy(
                streamingPartTexts = emptyMap(),
                streamingReasoningPart = null,
                staleNotice = false,
                messages = emptyList(),
                partsByMessage = emptyMap(),
                olderMessagesCursor = null,
                hasMoreMessages = false,
                isLoadingMoreMessages = false,
            )
        }
        // New path.
        newStore.dispatch(AppAction.ColdStartChatReset)

        assertEquals(
            "ColdStartChatReset MUST equal legacy AppCoreOrchestration:577-590 writeChat (8-field reset)",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
    }

    @Test
    fun `ColdStartChatReset preserves currentSessionId currentModel pendingAgent and isLoadingMessages (scoping)`() {
        // ColdStartChatReset clears EXACTLY 8 fields. The NON-target fields
        // MUST survive — this distinguishes it from HostStatePurged (which
        // clears currentSessionId + currentModel + pendingAgent via
        // clearSessionData) and from ChatCleared (which clears currentSessionId).
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "sess-A",
                messages = listOf(Message(id = "m1", role = "user")),
                streamingPartTexts = mapOf("p1" to "streaming"),
                staleNotice = true,
                olderMessagesCursor = "cursor",
                hasMoreMessages = true,
                isLoadingMoreMessages = true,
                // Non-target fields.
                currentModel = Message.ModelInfo("p", "m"),
                pendingAgent = "agent",
                pendingModel = Message.ModelInfo("p", "pm"),
                isLoadingMessages = true,
                partExpandStates = mapOf(PartKey("m1", "p1") to PartExpandState.Idle),
            ),
        )
        val store = SharedStateStore().apply { mutateState { prior } }

        store.dispatch(AppAction.ColdStartChatReset)

        val out = store.stateFlow.value.chat
        // Target fields cleared.
        assertTrue("streamingPartTexts cleared", out.streamingPartTexts.isEmpty())
        assertNull("streamingReasoningPart cleared", out.streamingReasoningPart)
        assertFalse("staleNotice cleared", out.staleNotice)
        assertTrue("messages cleared", out.messages.isEmpty())
        assertTrue("partsByMessage cleared", out.partsByMessage.isEmpty())
        assertNull("olderMessagesCursor cleared", out.olderMessagesCursor)
        assertFalse("hasMoreMessages cleared", out.hasMoreMessages)
        assertFalse("isLoadingMoreMessages cleared", out.isLoadingMoreMessages)
        // Non-target fields MUST survive.
        assertEquals("currentSessionId preserved (ColdStartChatReset ≠ ChatCleared/HostStatePurged)", "sess-A", out.currentSessionId)
        assertNotNull("currentModel preserved", out.currentModel)
        assertEquals("pendingAgent preserved", "agent", out.pendingAgent)
        assertNotNull("pendingModel preserved", out.pendingModel)
        assertTrue("isLoadingMessages preserved (NOT cleared by ColdStartChatReset)", out.isLoadingMessages)
        assertTrue("partExpandStates preserved", out.partExpandStates.isNotEmpty())
    }

    @Test
    fun `dispatch ColdStartChatReset produces exactly one aggregate emission`() = runTest {
        val store = SharedStateStore().apply {
            mutateChat {
                it.copy(
                    currentSessionId = "sess-A",
                    messages = listOf(Message(id = "m1", role = "user")),
                    streamingPartTexts = mapOf("p1" to "streaming"),
                )
            }
        }
        val seen = mutableListOf<StoreState>()
        val job = launch { store.stateFlow.collect { seen += it } }
        advanceUntilIdle()
        assertEquals(1, seen.size)

        store.dispatch(AppAction.ColdStartChatReset)
        advanceUntilIdle()

        assertEquals("exactly one post-dispatch emission", 2, seen.size)
        assertTrue("messages cleared in the single emission", seen.last().chat.messages.isEmpty())
        assertTrue("streamingPartTexts cleared in the single emission", seen.last().chat.streamingPartTexts.isEmpty())
        job.cancel()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // B. ExpandedPartsContentCommitted — Strategy 2 (CAS via pure reconcile)
    // ═══════════════════════════════════════════════════════════════════════

    private val expandSessionId = "sess-A"
    private val skeletonPart = Part(
        id = "p1",
        messageId = "m1",
        type = "text",
        text = null,
        hasFull = true,
        omitted = listOf("text"),
    )
    private val expandedPart = Part(
        id = "p1",
        messageId = "m1",
        type = "text",
        text = "expanded-full-text",
        hasFull = false,
        omitted = null,
    )
    private val m1Info = Message(id = "m1", role = "assistant", sessionId = expandSessionId)
    private val expandLocal = listOf(MessageWithParts(info = m1Info, parts = listOf(skeletonPart)))
    private val expandOutcome = ExpandPartsOutcome(
        fetchedItems = listOf(MessageWithParts(info = m1Info, parts = listOf(expandedPart))),
        mergedLocal = null,
        states = mapOf(PartKey("m1", "p1") to PartExpandState.Loaded),
    )

    @Test
    fun `dispatch ExpandedPartsContentCommitted is byte-for-byte equivalent to pure reconcileExpandedPartsContent`() = runTest {
        // Strategy 2: dispatch(outcome, local, sid) MUST equal
        // mutateChat { it.reconcileExpandedPartsContent(outcome, local, sid) }
        // — both run the same pure merge against the chat under CAS.
        val seedChat = ChatState(
            currentSessionId = expandSessionId,
            messages = listOf(m1Info),
            partsByMessage = mapOf("m1" to listOf(skeletonPart)),
            partExpandStates = mapOf(PartKey("m1", "p1") to PartExpandState.Loading),
        )
        val oldStore = SharedStateStore().apply { mutateChat { seedChat } }
        val newStore = SharedStateStore().apply { mutateChat { seedChat } }

        oldStore.mutateChat {
            it.reconcileExpandedPartsContent(expandOutcome, expandLocal, expandSessionId)
        }
        newStore.dispatch(
            AppAction.ExpandedPartsContentCommitted(
                outcome = expandOutcome,
                local = expandLocal,
                expectedSessionId = expandSessionId,
            )
        )

        assertEquals(
            "ExpandedPartsContentCommitted MUST equal pure reconcileExpandedPartsContent " +
                "(Loading + fetched full part → Loaded + parts updated)",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
        val out = newStore.stateFlow.value.chat
        assertEquals("expanded text landed", "expanded-full-text", out.partsByMessage["m1"]?.first()?.text)
        assertEquals(PartExpandState.Loaded, out.partExpandStates[PartKey("m1", "p1")])
    }

    @Test
    fun `ExpandedPartsContentCommitted is a no-op when expectedSessionId does not match currentSessionId`() = runTest {
        val seed = ChatState(
            currentSessionId = "sess-A",
            partsByMessage = mapOf("m1" to listOf(skeletonPart)),
            partExpandStates = mapOf(PartKey("m1", "p1") to PartExpandState.Loading),
        )
        val prior = SharedStateStore().apply { mutateChat { seed } }.stateFlow.value
        val store = SharedStateStore().apply { mutateChat { seed } }

        store.dispatch(
            AppAction.ExpandedPartsContentCommitted(
                outcome = expandOutcome,
                local = expandLocal,
                expectedSessionId = "sess-OTHER",
            )
        )

        assertEquals(
            "session guard in reconcileExpandedPartsContent drops the write",
            prior,
            store.stateFlow.value,
        )
    }

    @Test
    fun `ExpandedPartsContentCommitted is scoped to partsByMessage and partExpandStates — no other field touched`() {
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = expandSessionId,
                messages = listOf(m1Info),
                partsByMessage = mapOf("m1" to listOf(skeletonPart)),
                partExpandStates = mapOf(PartKey("m1", "p1") to PartExpandState.Loading),
                streamingPartTexts = mapOf("p1" to "streaming"),
                olderMessagesCursor = "cursor",
                currentModel = Message.ModelInfo("p", "m"),
            ),
        )
        val store = SharedStateStore().apply { mutateState { prior } }

        store.dispatch(
            AppAction.ExpandedPartsContentCommitted(
                outcome = expandOutcome,
                local = expandLocal,
                expectedSessionId = expandSessionId,
            )
        )

        val out = store.stateFlow.value.chat
        assertEquals("partsByMessage updated", "expanded-full-text", out.partsByMessage["m1"]?.first()?.text)
        assertEquals("partExpandStates updated", PartExpandState.Loaded, out.partExpandStates[PartKey("m1", "p1")])
        assertEquals("currentSessionId untouched", expandSessionId, out.currentSessionId)
        assertEquals("messages untouched", prior.chat.messages, out.messages)
        assertEquals("streamingPartTexts untouched", prior.chat.streamingPartTexts, out.streamingPartTexts)
        assertEquals("olderMessagesCursor untouched", "cursor", out.olderMessagesCursor)
        assertNotNull("currentModel untouched", out.currentModel)
    }

    @Test
    fun `ExpandedPartsContentCommitted CAS preserves concurrent SSE parts for unrelated owners`() = runTest {
        // Critical CAS proof: merge against LATEST chat, not a stale snapshot.
        // seed m1 skeleton Loading + m2 "sse-live"; expand only m1; m2 MUST survive.
        // Strategy 1 wholesale-replace from a stale snapshot would drop m2.
        val sseLivePart = Part(
            id = "p2",
            messageId = "m2",
            type = "text",
            text = "sse-live",
        )
        val store = SharedStateStore().apply {
            mutateChat {
                it.copy(
                    currentSessionId = expandSessionId,
                    messages = listOf(m1Info, Message(id = "m2", role = "assistant", sessionId = expandSessionId)),
                    partsByMessage = mapOf(
                        "m1" to listOf(skeletonPart),
                        "m2" to listOf(sseLivePart),
                    ),
                    partExpandStates = mapOf(PartKey("m1", "p1") to PartExpandState.Loading),
                )
            }
        }

        store.dispatch(
            AppAction.ExpandedPartsContentCommitted(
                outcome = expandOutcome,
                local = expandLocal,
                expectedSessionId = expandSessionId,
            )
        )

        val out = store.stateFlow.value.chat
        assertEquals(
            "m1 expanded by reconcile",
            "expanded-full-text",
            out.partsByMessage["m1"]?.first()?.text,
        )
        assertEquals(
            "m2 concurrent SSE parts MUST survive (CAS merge, not stale wholesale replace)",
            "sse-live",
            out.partsByMessage["m2"]?.first()?.text,
        )
        assertEquals(PartExpandState.Loaded, out.partExpandStates[PartKey("m1", "p1")])
    }

    // ═══════════════════════════════════════════════════════════════════════
    // C. Documentation — writeChat bypass write-site inventory
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `DOCUMENTATION — writeChat bypass write sites on target fields (scan must include writeChat, not just mutateChat)`() {
        // AppCore.writeChat is store.mutateChat. Audit: rg 'mutateChat|writeChat'.
        //
        // A. ColdStartChatReset — AppCoreOrchestration performGlobalColdStartRefresh
        //    (8-field reset). refreshNonce++ stays separate writeChat (non-target).
        // B. ExpandedPartsContentCommitted — Strategy 2: ChatViewModel dispatches
        //    outcome+local; reduce runs reconcileExpandedPartsContent on latest chat.
        // Deferred: ChatViewModel Loading/Failed writeChat (partExpandStates-only).
        assertTrue(
            "writeChat bypass sites documented — see comment block above for the full inventory",
            true,
        )
    }
}
