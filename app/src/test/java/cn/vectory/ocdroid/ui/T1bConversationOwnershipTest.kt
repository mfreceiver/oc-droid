package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.ui.controller.applyMessageUpdated
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
 * T1b freeze — **RED until impl**. Step-3 `partsByMessage`+`messages`
 * (conversation) ownership migration (full-refactor-plan §2.3 ownership
 * table rows 1-2 + §3.2 reducer skeleton + §4-T1 acceptance).
 *
 * Migrates the conversation-path production write sites that today call
 * `slices.mutateChat { c -> c.copy(messages = ..., partsByMessage = ...) }`
 * to `dispatch(AppAction.*)`.
 *
 * **Expected action vocabulary** (T1b impl MUST add to `AppAction`):
 *
 * ```kotlin
 * // ── Single-message updates ──────────────────────────────────────────────
 * // SSC:1270 applyMessageUpdated (patch-if-found / insert-if-absent).
 * // Reducer: state.copy(chat = state.chat.applyMessageUpdated(action.message).first)
 * data class MessageUpdatedApplied(val message: Message) : AppAction
 *
 * // ── Slim reconcile merges ───────────────────────────────────────────────
 * // SSC:3307 mergeSlimMessagesIntoChat. Reducer replays the merge loop:
 * //   for each item: patch-or-append message + replace partsByMessage[id].
 * //   payload: items: List<MessageWithParts>
 * data class SlimMessagesMerged(val items: List<MessageWithParts>) : AppAction
 *
 * // SSC:2790 ClearLocal arm. Clears messages + partsByMessage only.
 * //   (Does NOT clear streaming overlay / cursor / model — the old path
 * //   only touched those two fields.)
 * //   payload: none (the sessionId guard is at the call site)
 * data object SlimChatContentCleared : AppAction
 *
 * // ── MessageActions load merges ──────────────────────────────────────────
 * // MessageActions.kt:351 launchLoadMessages success — the FULL field-set
 * // merge (messages + partsByMessage + isLoadingMessages +
 * // streamingPartTexts + streamingReasoningPart + olderMessagesCursor +
 * // hasMoreMessages + currentModel). All 8 fields MUST be in ONE dispatch
 * // to avoid a torn intermediate (a partial write would leave, e.g.,
 * // isLoadingMessages=true with the new messages already landed).
 * data class MessagesMerged(
 *     val messages: List<Message>,
 *     val partsByMessage: Map<String, List<Part>>,
 *     val streamingPartTexts: Map<String, String>,
 *     val streamingReasoningPart: Part?,
 *     val olderMessagesCursor: String?,
 *     val hasMoreMessages: Boolean,
 *     val currentModel: Message.ModelInfo?,
 * ) : AppAction
 *
 * // MessageActions.kt:552 loadMore success — prepend older messages.
 * //   Fields: messages + partsByMessage + olderMessagesCursor +
 * //   hasMoreMessages + isLoadingMoreMessages=false.
 * data class MessagesPrepended(
 *     val messages: List<Message>,
 *     val partsByMessage: Map<String, List<Part>>,
 *     val olderMessagesCursor: String?,
 *     val hasMoreMessages: Boolean,
 * ) : AppAction
 *
 * // ── AppCore VerifyAndHydrate cached-window inject ───────────────────────
 * // AppCore.kt:631-638 — injects a CachedSessionWindow's 4 fields into chat.
 * //   Fields: messages + partsByMessage + olderMessagesCursor + hasMoreMessages.
 * data class ChatWindowHydrated(
 *     val messages: List<Message>,
 *     val partsByMessage: Map<String, List<Part>>,
 *     val olderMessagesCursor: String?,
 *     val hasMoreMessages: Boolean,
 * ) : AppAction
 *
 * // ── SessionSwitcher.switchTo compound chat clear ────────────────────────
 * // SessionSwitcher.kt:417-472 — the full per-session reset on switch.
 * //   Constant resets: messages=[] + partsByMessage={} +
 * //   streamingPartTexts={} + streamingReasoningPart=null +
 * //   partExpandStates={} + staleNotice=false + olderMessagesCursor=null
 * //   + hasMoreMessages=false + isLoadingMessages=false +
 * //   isLoadingMoreMessages=false + currentModel=null + pendingAgent=null
 * //   + pendingModel=null.
 * //   Variable: currentSessionId=sessionId + pendingScrollRequest=action.scroll.
 * data class SessionSelected(
 *     val sessionId: String,
 *     val pendingScrollRequest: PendingScrollRequest,
 * ) : AppAction
 * ```
 *
 * **RED kind**: `compile-error` — the action types above are unresolved
 * references today. Once impl adds them + the reduce branches, every test
 * goes GREEN unchanged.
 *
 * **Field-set completeness (global principle 5)**: `MessagesMerged` and
 * `SessionSelected` write MULTIPLE chat fields in ONE dispatch. The reducer
 * MUST cover the EXACT field set the original `mutateChat { c.copy(...) }`
 * wrote — no more, no less. A missing field creates a torn intermediate
 * (e.g. `isLoadingMessages=true` with new messages already landed); an extra
 * field is a semantic drift.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class T1bConversationOwnershipTest {

    // ── C1. MessageUpdatedApplied — patch-in-place + insert-if-absent ──────

    @Test
    fun `dispatch MessageUpdatedApplied patches an existing message in place (byte-for-byte equivalent to applyMessageUpdated)`() = runTest {
        val existing = Message(id = "m1", role = "assistant")
        val updated = existing.copy(cost = 0.42)
        val (oldStore, newStore) = seededPair(messages = listOf(existing, Message(id = "m2", role = "user")))

        oldStore.mutateChat { c -> c.applyMessageUpdated(updated).first }
        newStore.dispatch(AppAction.MessageUpdatedApplied(updated))

        assertEquals(
            "MessageUpdatedApplied (patch-in-place) MUST equal legacy applyMessageUpdated",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
        // Sanity: the patch landed (cost updated, list shape unchanged).
        assertEquals(0.42, newStore.stateFlow.value.chat.messages.first { it.id == "m1" }.cost)
        assertEquals(listOf("m1", "m2"), newStore.stateFlow.value.chat.messages.map { it.id })
    }

    @Test
    fun `dispatch MessageUpdatedApplied inserts when the message is absent (byte-for-byte equivalent)`() = runTest {
        val existing = Message(id = "m1", role = "user")
        val newMsg = Message(id = "m-new", role = "assistant")
        val (oldStore, newStore) = seededPair(messages = listOf(existing))

        oldStore.mutateChat { c -> c.applyMessageUpdated(newMsg).first }
        newStore.dispatch(AppAction.MessageUpdatedApplied(newMsg))

        assertEquals(
            "MessageUpdatedApplied (insert-if-absent) MUST equal legacy applyMessageUpdated (appends at tail)",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
        assertEquals(
            "new message appended at the tail (oldest-first)",
            listOf("m1", "m-new"),
            newStore.stateFlow.value.chat.messages.map { it.id },
        )
    }

    // ── C2. SlimMessagesMerged — slim reconcile merge loop ─────────────────

    @Test
    fun `dispatch SlimMessagesMerged is byte-for-byte equivalent to mergeSlimMessagesIntoChat`() = runTest {
        // mergeSlimMessagesIntoChat (SSC:3307) is private; replicate its exact
        // loop in the old-path mutateChat so the equivalence is structural.
        val existingMsg = Message(id = "m1", role = "user")
        val items = listOf(
            MessageWithParts(
                info = existingMsg.copy(cost = 1.0), // patch existing
                parts = listOf(Part(id = "p1", messageId = "m1", sessionId = "sess-A", type = "text", text = "updated")),
            ),
            MessageWithParts(
                info = Message(id = "m-new", role = "assistant"), // insert new
                parts = listOf(Part(id = "p2", messageId = "m-new", sessionId = "sess-A", type = "text", text = "new")),
            ),
        )
        val (oldStore, newStore) = seededPair(messages = listOf(existingMsg))

        // Old path: replicate mergeSlimMessagesIntoChat's loop verbatim.
        oldStore.mutateChat { chat ->
            var messages = chat.messages
            var partsByMessage = chat.partsByMessage
            for (item in items) {
                val updated = item.info
                if (updated.id.isEmpty()) continue
                var found = false
                messages = messages.map { if (it.id == updated.id) { found = true; updated } else it }
                if (!found) messages = messages + updated
                if (item.parts.isNotEmpty()) {
                    partsByMessage = partsByMessage + (updated.id to item.parts)
                }
            }
            chat.copy(messages = messages, partsByMessage = partsByMessage)
        }
        // New path: one dispatch.
        newStore.dispatch(AppAction.SlimMessagesMerged(items))

        assertEquals(
            "SlimMessagesMerged MUST equal legacy mergeSlimMessagesIntoChat (patch + insert + parts replace)",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
    }

    // ── C3. MessagesMerged — MessageActions:351 full field-set merge ───────
    //
    // CRITICAL field-set test: the reducer MUST write EXACTLY these 8 fields
    // in ONE dispatch (no torn intermediate). The payload mirrors the
    // MessageActions.kt:351 `slices.mutateChat { c.copy(...) }` block.

    @Test
    fun `dispatch MessagesMerged writes the full 8-field set byte-for-byte equivalent to MessageActions load merge`() = runTest {
        // Seed: rich prior state with NON-DEFAULT values for every field the
        // merge touches (so a partial write would be observable).
        val seedMsg = Message(id = "old", role = "user")
        val newMessages = listOf(Message(id = "m1", role = "assistant", cost = 0.5))
        val newParts = mapOf("m1" to listOf(Part(id = "p1", messageId = "m1", type = "text", text = "hi")))
        val newStreaming = mapOf("p1" to "streaming-text")
        val newReasoning: Part? = Part(id = "r1", type = "reasoning")
        val newCursor = "next-cursor"
        val newHasMore = true
        val newModel = Message.ModelInfo(providerId = "prov", modelId = "model-x")

        val oldStore = SharedStateStore().apply {
            mutateChat {
                it.copy(
                    currentSessionId = "sess-A",
                    messages = listOf(seedMsg),
                    partsByMessage = emptyMap(),
                    isLoadingMessages = true,
                    streamingPartTexts = emptyMap(),
                    streamingReasoningPart = null,
                    olderMessagesCursor = null,
                    hasMoreMessages = false,
                    currentModel = null,
                )
            }
        }
        val newStore = SharedStateStore().apply {
            mutateChat { oldStore.chatFlow.value }
        }
        assertEquals(oldStore.stateFlow.value, newStore.stateFlow.value)

        // Old path: replicate MessageActions.kt:351 mutateChat block verbatim.
        oldStore.mutateChat { c ->
            c.copy(
                messages = newMessages,
                partsByMessage = newParts,
                isLoadingMessages = false,
                streamingPartTexts = newStreaming,
                streamingReasoningPart = newReasoning,
                olderMessagesCursor = newCursor,
                hasMoreMessages = newHasMore,
                currentModel = newModel,
            )
        }
        // New path: one dispatch.
        newStore.dispatch(
            AppAction.MessagesMerged(
                messages = newMessages,
                partsByMessage = newParts,
                streamingPartTexts = newStreaming,
                streamingReasoningPart = newReasoning,
                olderMessagesCursor = newCursor,
                hasMoreMessages = newHasMore,
                currentModel = newModel,
            )
        )

        assertEquals(
            "MessagesMerged MUST write the full 8-field set in ONE dispatch (no torn intermediate) — " +
                "byte-for-byte equivalent to MessageActions:351",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
        // Spot-check every field landed (guards against a no-op reducer).
        val out = newStore.stateFlow.value.chat
        assertEquals(newMessages, out.messages)
        assertEquals(newParts, out.partsByMessage)
        assertFalse(out.isLoadingMessages)
        assertEquals(newStreaming, out.streamingPartTexts)
        assertEquals(newReasoning, out.streamingReasoningPart)
        assertEquals(newCursor, out.olderMessagesCursor)
        assertTrue(out.hasMoreMessages)
        assertEquals(newModel, out.currentModel)
    }

    @Test
    fun `MessagesMerged does NOT touch currentSessionId or other non-merge fields (field-set scoping)`() = runTest {
        // The MessageActions:351 merge writes 8 fields; it MUST NOT also clear
        // currentSessionId / pendingAgent / partExpandStates / etc. — those
        // are owned by OTHER actions. A reducer that "helpfully" resets extra
        // fields would drift from the legacy semantics.
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "sess-A",
                pendingAgent = "some-agent",
                pendingModel = Message.ModelInfo("p", "m"),
                partExpandStates = emptyMap(),
                revertCutoffs = emptyMap(),
                olderMessagesCursor = "old-cursor",
                hasMoreMessages = false,
                currentModel = null,
            ),
        )
        val store = SharedStateStore().apply { mutateState { prior } }

        store.dispatch(
            AppAction.MessagesMerged(
                messages = listOf(Message(id = "m1", role = "assistant")),
                partsByMessage = emptyMap<String, List<Part>>(),
                streamingPartTexts = emptyMap<String, String>(),
                streamingReasoningPart = null,
                olderMessagesCursor = "new-cursor",
                hasMoreMessages = true,
                currentModel = null,
            )
        )

        val out = store.stateFlow.value.chat
        assertEquals("currentSessionId untouched by MessagesMerged", "sess-A", out.currentSessionId)
        assertEquals("pendingAgent untouched by MessagesMerged", "some-agent", out.pendingAgent)
        assertEquals("pendingModel untouched by MessagesMerged", prior.chat.pendingModel, out.pendingModel)
    }

    // ── C4. MessagesPrepended — MessageActions:552 loadMore ────────────────

    @Test
    fun `dispatch MessagesPrepended is byte-for-byte equivalent to MessageActions loadMore merge`() = runTest {
        val olderMsg = Message(id = "old-1", role = "user")
        val currentMsg = Message(id = "m1", role = "assistant")
        val prependedMessages = listOf(olderMsg, currentMsg)
        val prependedParts = mapOf("old-1" to listOf(Part(id = "p0", messageId = "old-1", type = "text", text = "old")))
        val newCursor = "page-2-cursor"
        val newHasMore = true

        val (oldStore, newStore) = seededPair(messages = listOf(currentMsg), isLoadingMore = true)

        // Old path: replicate MessageActions.kt:552 mutateChat block verbatim.
        oldStore.mutateChat { c ->
            c.copy(
                messages = prependedMessages,
                partsByMessage = prependedParts,
                olderMessagesCursor = newCursor,
                hasMoreMessages = newHasMore,
                isLoadingMoreMessages = false,
            )
        }
        // New path.
        newStore.dispatch(
            AppAction.MessagesPrepended(
                messages = prependedMessages,
                partsByMessage = prependedParts,
                olderMessagesCursor = newCursor,
                hasMoreMessages = newHasMore,
            )
        )

        assertEquals(
            "MessagesPrepended MUST equal legacy MessageActions:552 loadMore merge (5-field set + isLoadingMore cleared)",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
        // isLoadingMoreMessages MUST be cleared by the reducer (it's part of
        // the legacy mutateChat block — the action carries no flag for it;
        // the reducer unconditionally clears it, matching the old path).
        assertFalse(
            "isLoadingMoreMessages cleared by MessagesPrepended (matches legacy copy block)",
            newStore.stateFlow.value.chat.isLoadingMoreMessages,
        )
    }

    // ── C5. ChatWindowHydrated — AppCore cached-window inject ──────────────

    @Test
    fun `dispatch ChatWindowHydrated is byte-for-byte equivalent to AppCore VerifyAndHydrate cached window inject`() = runTest {
        // AppCore.kt:631-638 — injects CachedSessionWindow's 4 fields into chat.
        val hydratedMessages = listOf(Message(id = "m1", role = "user"), Message(id = "m2", role = "assistant"))
        val hydratedParts = mapOf(
            "m2" to listOf(Part(id = "p1", messageId = "m2", type = "text", text = "cached-body")),
        )
        val hydratedCursor = "restored-cursor"
        val hydratedHasMore = true

        val (oldStore, newStore) = seededPair()

        // Old path: replicate AppCore.kt:631-638 mutateChat block verbatim.
        oldStore.mutateChat { c ->
            c.copy(
                messages = hydratedMessages,
                partsByMessage = hydratedParts,
                olderMessagesCursor = hydratedCursor,
                hasMoreMessages = hydratedHasMore,
            )
        }
        // New path.
        newStore.dispatch(
            AppAction.ChatWindowHydrated(
                messages = hydratedMessages,
                partsByMessage = hydratedParts,
                olderMessagesCursor = hydratedCursor,
                hasMoreMessages = hydratedHasMore,
            )
        )

        assertEquals(
            "ChatWindowHydrated MUST equal legacy AppCore VerifyAndHydrate cached window inject (4-field set)",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
    }

    // ── C6. SessionSelected — SessionSwitcher.switchTo compound chat clear ──
    //
    // The biggest compound write. The reducer MUST reset the FULL set of
    // per-session chat fields (15+ constant resets + 2 variable writes).

    @Test
    fun `dispatch SessionSelected is byte-for-byte equivalent to SessionSwitcher switchTo chat mutateChat`() = runTest {
        // SessionSwitcher.kt:417-472 — the full per-session reset.
        val sessionId = "sess-B"
        val scrollRequest = PendingScrollRequest(
            requestId = 42L,
            targetSessionId = sessionId,
            behavior = ScrollBehavior.Latest,
        )

        // Seed: rich prior state so the clear is observable on every field.
        val seedChat = ChatState(
            currentSessionId = "sess-A",
            messages = listOf(Message(id = "m1", role = "user")),
            partsByMessage = mapOf("m1" to listOf(Part(id = "p1", messageId = "m1", type = "text"))),
            streamingPartTexts = mapOf("p1" to "streaming"),
            streamingReasoningPart = Part(id = "r1", type = "reasoning"),
            partExpandStates = emptyMap(),
            staleNotice = true,
            olderMessagesCursor = "old-cursor",
            hasMoreMessages = true,
            isLoadingMessages = true,
            isLoadingMoreMessages = true,
            currentModel = Message.ModelInfo("p", "m"),
            pendingAgent = "agent-A",
            pendingModel = Message.ModelInfo("p", "pending-m"),
            pendingScrollRequest = null,
        )
        val oldStore = SharedStateStore().apply { mutateChat { seedChat } }
        val newStore = SharedStateStore().apply { mutateChat { seedChat } }
        assertEquals(oldStore.stateFlow.value, newStore.stateFlow.value)

        // Old path: replicate SessionSwitcher.kt:417-472 mutateChat verbatim
        // (the chat-slice half only — composer is a separate mutateComposer
        // and stays out of this action's scope).
        oldStore.mutateChat { c ->
            c.copy(
                currentSessionId = sessionId,
                pendingScrollRequest = scrollRequest,
                messages = emptyList(),
                partsByMessage = emptyMap(),
                streamingPartTexts = emptyMap(),
                streamingReasoningPart = null,
                partExpandStates = emptyMap(),
                staleNotice = false,
                olderMessagesCursor = null,
                hasMoreMessages = false,
                isLoadingMessages = false,
                isLoadingMoreMessages = false,
                currentModel = null,
                pendingAgent = null,
                pendingModel = null,
            )
        }
        // New path.
        newStore.dispatch(AppAction.SessionSelected(sessionId, scrollRequest))

        assertEquals(
            "SessionSelected MUST equal legacy SessionSwitcher switchTo chat mutateChat " +
                "(full per-session reset: 15 field writes in ONE dispatch, no torn intermediate)",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
        // Spot-check the variable + constant writes landed.
        val out = newStore.stateFlow.value.chat
        assertEquals("currentSessionId flipped to the new session", sessionId, out.currentSessionId)
        assertEquals("pendingScrollRequest set atomically with the session flip", scrollRequest, out.pendingScrollRequest)
        assertTrue("messages cleared", out.messages.isEmpty())
        assertNull("streamingReasoningPart cleared", out.streamingReasoningPart)
        assertNull("currentModel cleared", out.currentModel)
        assertNull("pendingAgent cleared", out.pendingAgent)
    }

    @Test
    fun `dispatch SessionSelected produces exactly one aggregate emission`() = runTest {
        val store = SharedStateStore().apply {
            mutateChat {
                it.copy(currentSessionId = "sess-A", messages = listOf(Message(id = "m1", role = "user")))
            }
        }
        val seen = mutableListOf<StoreState>()
        val job = launch { store.stateFlow.collect { seen += it } }
        advanceUntilIdle()
        assertEquals(1, seen.size)

        store.dispatch(
            AppAction.SessionSelected("sess-B", PendingScrollRequest(1L, "sess-B", ScrollBehavior.Latest))
        )
        advanceUntilIdle()

        assertEquals(
            "SessionSelected produces exactly ONE emission (the 15-field compound write is atomic)",
            2,
            seen.size,
        )
        // No torn intermediate: currentSessionId and pendingScrollRequest
        // land in the SAME committed state (the §Wave5b-Q13 consistency pair).
        val final = seen.last().chat
        assertEquals("sess-B", final.currentSessionId)
        assertNotNull(final.pendingScrollRequest)
        assertTrue("messages cleared in the SAME state as the session flip", final.messages.isEmpty())
        job.cancel()
    }

    // ── C7. SlimChatContentCleared — ClearLocal arm ────────────────────────

    @Test
    fun `dispatch SlimChatContentCleared is byte-for-byte equivalent to ClearLocal chat clear`() = runTest {
        // SSC:2790-2799 — ReconcileResult.ClearLocal clears messages +
        // partsByMessage only (NOT streaming overlay / cursor / model —
        // those survive; ClearLocal is a content-only wipe).
        val (oldStore, newStore) = seededPair(
            messages = listOf(Message(id = "m1", role = "user")),
            parts = mapOf("m1" to listOf(Part(id = "p1", messageId = "m1", type = "text"))),
            streaming = mapOf("p1" to "streaming-survives"),
        )

        // Old path: replicate SSC:2794-2799 mutateChat verbatim.
        oldStore.mutateChat { it.copy(messages = emptyList(), partsByMessage = emptyMap()) }
        // New path.
        newStore.dispatch(AppAction.SlimChatContentCleared)

        assertEquals(
            "SlimChatContentCleared MUST equal legacy ClearLocal (messages + partsByMessage cleared, streaming preserved)",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
        // Sanity: streaming overlay survives (ClearLocal ≠ full chat clear).
        assertEquals(
            "streamingPartTexts survives ClearLocal (content-only wipe)",
            mapOf("p1" to "streaming-survives"),
            newStore.stateFlow.value.chat.streamingPartTexts,
        )
    }

    // ── D. Scoping — conversation actions don't touch non-conversation slices

    @Test
    fun `conversation actions are scoped to chat — sessionList and expandedParts untouched`() = runTest {
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(sessions = listOf(Session(id = "sess-A", directory = "/p"))),
            expandedParts = mapOf("fold:k1" to true),
            chat = ChatState(currentSessionId = "sess-A", messages = listOf(Message(id = "m1", role = "user"))),
        )
        val store = SharedStateStore().apply { mutateState { prior } }

        store.dispatch(AppAction.MessageUpdatedApplied(Message(id = "m2", role = "assistant")))

        val out = store.stateFlow.value
        assertEquals("sessionList untouched by MessageUpdatedApplied", prior.sessionList, out.sessionList)
        assertEquals("expandedParts untouched by MessageUpdatedApplied", prior.expandedParts, out.expandedParts)
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun seededPair(
        messages: List<Message> = listOf(Message(id = "m1", role = "assistant")),
        parts: Map<String, List<Part>> = mapOf("m1" to emptyList()),
        streaming: Map<String, String> = emptyMap(),
        isLoadingMore: Boolean = false,
    ): Pair<SharedStateStore, SharedStateStore> {
        fun build() = SharedStateStore().apply {
            mutateChat {
                it.copy(
                    currentSessionId = "sess-A",
                    messages = messages,
                    partsByMessage = parts,
                    streamingPartTexts = streaming,
                    isLoadingMoreMessages = isLoadingMore,
                )
            }
        }
        val old = build()
        val new = build()
        assertEquals(old.stateFlow.value, new.stateFlow.value)
        return old to new
    }
}
