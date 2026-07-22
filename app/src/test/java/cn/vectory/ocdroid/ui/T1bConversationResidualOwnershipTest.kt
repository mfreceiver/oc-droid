package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.Session
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1b residual freeze — **RED until impl**. Covers the T1b rev-1/rev-2 gaps:
 * production `mutateChat` bypass write sites on the §2.3 target fields
 * (`messages` / `partsByMessage` / `streamingPartTexts` /
 * `streamingReasoningPart`) that were NOT migrated by the core 15 actions.
 *
 * **Expected action vocabulary** (T1b residual impl MUST add to `AppAction`):
 *
 * ```kotlin
 * // ── A. ChatCleared (3-field clear: currentSessionId + messages + parts) ──
 * // 7+ call sites in SessionListActions / SessionMutationActions /
 * // SessionViewModel all write the SAME 3-field clear. Distinct from
 * // SlimChatContentCleared (which clears messages+parts ONLY, preserves
 * // currentSessionId) and from HostStatePurged (which clears everything
 * // including streaming/cursor/model).
 * data object ChatCleared : AppAction
 * // reduce: state.copy(chat = state.chat.copy(
 * //     currentSessionId = null,
 * //     messages = emptyList(),
 * //     partsByMessage = emptyMap(),
 * // ))
 *
 * // ── B. LastAssistantErrorAttached (SSC:1706-1712 session.error SSE) ─────
 * // Attaches an error to the last assistant message. No-op if no assistant
 * // exists OR the last assistant already has an error.
 * data class LastAssistantErrorAttached(val error: Message.MessageError) : AppAction
 * // reduce: val last = messages.lastOrNull { it.isAssistant }
 * //   if (last == null || last.error != null) state  // no-op
 * //   else state.copy(chat = state.chat.copy(messages = messages.map {
 * //     if (it.id == last.id) it.copy(error = action.error) else it
 * //   }))
 *
 * // ── C. CatchUpMessagesMerged (CatchUpActions:147-154) ───────────────────
 * // 4-field merge: messages + partsByMessage + isLoadingMessages=false +
 * // staleNotice=false. Distinct from MessagesMerged (8 fields incl.
 * // streaming/cursor/model).
 * data class CatchUpMessagesMerged(
 *     val messages: List<Message>,
 *     val partsByMessage: Map<String, List<Part>>,
 * ) : AppAction
 * // reduce: state.copy(chat = state.chat.copy(
 * //     messages = action.messages,
 * //     partsByMessage = action.partsByMessage,
 * //     isLoadingMessages = false,
 * //     staleNotice = false,
 * // ))
 * ```
 *
 * **RED kind**: `compile-error` — the 3 action types above are unresolved
 * references today. The existing T1b core-15 tests should already compile
 * (those actions are implemented); only these residual references RED.
 *
 * **D. HostProfileController reset paths (:1062, :1117)**: NOT a new action.
 * These scattered `mutateChat` blocks are a SUBSET of
 * `HostStatePurged(preserveServerGroupData=false)`'s chat reduction
 * (`clearSessionData()`). The migration path is: replace the scattered
 * chat/sessionList/unread/composer/connection/etc. resets with a SINGLE
 * `dispatch(HostStatePurged(preserveServerGroupData = false))`. The
 * `clearSessionData()` superset (extra clears on revertCutoffs /
 * partExpandStates / deltaBuffer / etc.) is the SAME deliberate improvement
 * documented at AppAction.kt:630-636 (§fix-leak-window). The test below
 * locks that HostStatePurged covers every field :1062/:1117 clears.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class T1bConversationResidualOwnershipTest {

    // ═══════════════════════════════════════════════════════════════════════
    // A. ChatCleared — 3-field clear (currentSessionId + messages + parts)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `dispatch ChatCleared clears currentSessionId messages partsByMessage byte-for-byte equivalent to legacy mutateChat`() = runTest {
        val seed = ChatState(
            currentSessionId = "sess-A",
            messages = listOf(Message(id = "m1", role = "user"), Message(id = "m2", role = "assistant")),
            partsByMessage = mapOf("m2" to listOf(Part(id = "p1", messageId = "m2", type = "text"))),
            // NON-target fields seeded with non-defaults so the scoping
            // assertion below has teeth.
            streamingPartTexts = mapOf("p1" to "streaming-survives"),
            streamingReasoningPart = Part(id = "r1", type = "reasoning"),
            olderMessagesCursor = "cursor-survives",
            hasMoreMessages = true,
            currentModel = Message.ModelInfo("prov", "model"),
        )
        val oldStore = SharedStateStore().apply { mutateChat { seed } }
        val newStore = SharedStateStore().apply { mutateChat { seed } }

        // Old path: the verbatim 3-field clear used at all 7+ call sites.
        oldStore.mutateChat { c ->
            c.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap())
        }
        // New path.
        newStore.dispatch(AppAction.ChatCleared)

        assertEquals(
            "ChatCleared MUST equal legacy 3-field clear (currentSessionId + messages + partsByMessage)",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
    }

    @Test
    fun `ChatCleared preserves streaming overlay cursor and model (scoping — distinct from HostStatePurged)`() = runTest {
        // ChatCleared clears ONLY 3 fields. Streaming / cursor / model MUST
        // survive — this is what distinguishes ChatCleared from
        // HostStatePurged (which clears everything via clearSessionData).
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "sess-A",
                messages = listOf(Message(id = "m1", role = "user")),
                partsByMessage = mapOf("m1" to emptyList()),
                streamingPartTexts = mapOf("p1" to "streaming"),
                streamingReasoningPart = Part(id = "r1", type = "reasoning"),
                olderMessagesCursor = "cursor",
                hasMoreMessages = true,
                currentModel = Message.ModelInfo("p", "m"),
                staleNotice = true,
                pendingAgent = "agent",
            ),
        )
        val store = SharedStateStore().apply { mutateState { prior } }

        store.dispatch(AppAction.ChatCleared)

        val out = store.stateFlow.value.chat
        assertNull("currentSessionId cleared", out.currentSessionId)
        assertTrue("messages cleared", out.messages.isEmpty())
        assertTrue("partsByMessage cleared", out.partsByMessage.isEmpty())
        // NON-target fields MUST survive.
        assertEquals("streamingPartTexts preserved (ChatCleared ≠ HostStatePurged)", mapOf("p1" to "streaming"), out.streamingPartTexts)
        assertNotNull("streamingReasoningPart preserved", out.streamingReasoningPart)
        assertEquals("olderMessagesCursor preserved", "cursor", out.olderMessagesCursor)
        assertTrue("hasMoreMessages preserved", out.hasMoreMessages)
        assertNotNull("currentModel preserved", out.currentModel)
        assertTrue("staleNotice preserved (NOT cleared by ChatCleared)", out.staleNotice)
        assertEquals("pendingAgent preserved", "agent", out.pendingAgent)
    }

    @Test
    fun `dispatch ChatCleared produces exactly one aggregate emission`() = runTest {
        val store = SharedStateStore().apply {
            mutateChat { it.copy(currentSessionId = "sess-A", messages = listOf(Message(id = "m1", role = "user"))) }
        }
        val seen = mutableListOf<StoreState>()
        val job = launch { store.stateFlow.collect { seen += it } }
        advanceUntilIdle()
        assertEquals(1, seen.size)

        store.dispatch(AppAction.ChatCleared)
        advanceUntilIdle()

        assertEquals("exactly one post-dispatch emission", 2, seen.size)
        assertNull(seen.last().chat.currentSessionId)
        job.cancel()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // B. LastAssistantErrorAttached — SSC:1706-1712 session.error SSE branch
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `dispatch LastAssistantErrorAttached attaches error to the last assistant message (byte-for-byte equivalent to SSC 1706-1712)`() = runTest {
        val lastAssistant = Message(id = "m2", role = "assistant")
        val seed = listOf(
            Message(id = "m1", role = "user"),
            lastAssistant,
        )
        val error = Message.MessageError(name = "ToolError", data = null)
        val oldStore = SharedStateStore().apply { mutateChat { it.copy(messages = seed) } }
        val newStore = SharedStateStore().apply { mutateChat { it.copy(messages = seed) } }

        // Old path: replicate SSC:1706-1712 mutateChat verbatim.
        oldStore.mutateChat { c ->
            val la = c.messages.lastOrNull { it.isAssistant }
            if (la == null || la.error != null) c
            else c.copy(messages = c.messages.map { m ->
                if (m.id == la.id) m.copy(error = error) else m
            })
        }
        // New path.
        newStore.dispatch(AppAction.LastAssistantErrorAttached(error))

        assertEquals(
            "LastAssistantErrorAttached MUST equal legacy SSC:1706-1712 (attach to last assistant)",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
        // Sanity: the error landed on the right message.
        val out = newStore.stateFlow.value.chat.messages.last { it.isAssistant }
        assertEquals("error attached to the last assistant message", error, out.error)
    }

    @Test
    fun `LastAssistantErrorAttached is a no-op when no assistant message exists`() = runTest {
        val seed = listOf(Message(id = "m1", role = "user"))
        val prior = SharedStateStore().apply { mutateChat { it.copy(messages = seed) } }.stateFlow.value
        val store = SharedStateStore().apply { mutateChat { it.copy(messages = seed) } }

        store.dispatch(AppAction.LastAssistantErrorAttached(Message.MessageError(name = "x")))

        assertEquals(
            "LastAssistantErrorAttached is a no-op (no assistant in the list → state unchanged)",
            prior,
            store.stateFlow.value,
        )
    }

    @Test
    fun `LastAssistantErrorAttached is a no-op when the last assistant already has an error`() = runTest {
        val existingError = Message.MessageError(name = "ExistingError")
        val seed = listOf(
            Message(id = "m1", role = "user"),
            Message(id = "m2", role = "assistant", error = existingError),
        )
        val prior = SharedStateStore().apply { mutateChat { it.copy(messages = seed) } }.stateFlow.value
        val store = SharedStateStore().apply { mutateChat { it.copy(messages = seed) } }

        store.dispatch(AppAction.LastAssistantErrorAttached(Message.MessageError(name = "NewError")))

        assertEquals(
            "LastAssistantErrorAttached is a no-op (last assistant already has an error → state unchanged)",
            prior,
            store.stateFlow.value,
        )
    }

    @Test
    fun `LastAssistantErrorAttached targets the LAST assistant (not the first) when multiple exist`() = runTest {
        val seed = listOf(
            Message(id = "m1", role = "user"),
            Message(id = "m2", role = "assistant"),
            Message(id = "m3", role = "user"),
            Message(id = "m4", role = "assistant"), // ← LAST assistant
        )
        val error = Message.MessageError(name = "err")
        val store = SharedStateStore().apply { mutateChat { it.copy(messages = seed) } }

        store.dispatch(AppAction.LastAssistantErrorAttached(error))

        val msgs = store.stateFlow.value.chat.messages
        assertNull("first assistant (m2) NOT touched", msgs.first { it.id == "m2" }.error)
        assertEquals("last assistant (m4) got the error", error, msgs.first { it.id == "m4" }.error)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // C. CatchUpMessagesMerged — CatchUpActions:147-154 (4-field merge)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `dispatch CatchUpMessagesMerged is byte-for-byte equivalent to CatchUpActions 147-154 mutateChat`() = runTest {
        val mergedMessages = listOf(Message(id = "m1", role = "user"), Message(id = "m2", role = "assistant"))
        val mergedParts = mapOf("m2" to listOf(Part(id = "p1", messageId = "m2", type = "text")))

        // Seed with NON-DEFAULT values for isLoadingMessages + staleNotice
        // so the clear is observable (the old path forces both to false).
        val oldStore = SharedStateStore().apply {
            mutateChat {
                it.copy(
                    currentSessionId = "sess-A",
                    messages = listOf(Message(id = "old", role = "user")),
                    partsByMessage = emptyMap(),
                    isLoadingMessages = true,
                    staleNotice = true,
                    // Non-target fields seeded with non-defaults for scoping.
                    olderMessagesCursor = "cursor-survives",
                    currentModel = Message.ModelInfo("p", "m"),
                    streamingPartTexts = mapOf("p1" to "streaming-survives"),
                )
            }
        }
        val newStore = SharedStateStore().apply {
            mutateChat { oldStore.stateFlow.value.chat }
        }
        assertEquals(oldStore.stateFlow.value, newStore.stateFlow.value)

        // Old path: replicate CatchUpActions.kt:147-154 mutateChat verbatim.
        oldStore.mutateChat { c ->
            c.copy(
                messages = mergedMessages,
                partsByMessage = mergedParts,
                isLoadingMessages = false,
                staleNotice = false,
            )
        }
        // New path.
        newStore.dispatch(AppAction.CatchUpMessagesMerged(mergedMessages, mergedParts))

        assertEquals(
            "CatchUpMessagesMerged MUST equal legacy CatchUpActions:147-154 (4-field merge)",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
        // Spot-check: isLoadingMessages + staleNotice cleared (the 2 constant
        // clears that distinguish CatchUp from a raw content merge).
        assertFalse("isLoadingMessages cleared by CatchUpMessagesMerged", newStore.stateFlow.value.chat.isLoadingMessages)
        assertFalse("staleNotice cleared by CatchUpMessagesMerged", newStore.stateFlow.value.chat.staleNotice)
    }

    @Test
    fun `CatchUpMessagesMerged does NOT touch streaming cursor or model (field-set scoping)`() = runTest {
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "sess-A",
                messages = listOf(Message(id = "m1", role = "user")),
                streamingPartTexts = mapOf("p1" to "streaming"),
                streamingReasoningPart = Part(id = "r1", type = "reasoning"),
                olderMessagesCursor = "cursor",
                hasMoreMessages = true,
                currentModel = Message.ModelInfo("p", "m"),
                pendingAgent = "agent",
                isLoadingMessages = true,
                staleNotice = true,
            ),
        )
        val store = SharedStateStore().apply { mutateState { prior } }

        store.dispatch(
            AppAction.CatchUpMessagesMerged(
                listOf(Message(id = "new", role = "assistant")),
                emptyMap<String, List<Part>>(),
            )
        )

        val out = store.stateFlow.value.chat
        // Target fields changed.
        assertEquals(listOf(Message(id = "new", role = "assistant")), out.messages)
        assertFalse(out.isLoadingMessages)
        assertFalse(out.staleNotice)
        // NON-target fields MUST survive (distinct from MessagesMerged which
        // DOES write streaming/cursor/model).
        assertEquals("streamingPartTexts preserved", mapOf("p1" to "streaming"), out.streamingPartTexts)
        assertNotNull("streamingReasoningPart preserved", out.streamingReasoningPart)
        assertEquals("olderMessagesCursor preserved", "cursor", out.olderMessagesCursor)
        assertTrue("hasMoreMessages preserved", out.hasMoreMessages)
        assertNotNull("currentModel preserved", out.currentModel)
        assertEquals("pendingAgent preserved", "agent", out.pendingAgent)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // D. HostProfileController reset → HostStatePurged coverage
    // ═══════════════════════════════════════════════════════════════════════
    //
    // NOT a new action. HostProfileController:1062 and :1117 have scattered
    // mutateChat blocks whose field set is a SUBSET of
    // HostStatePurged(preserveServerGroupData=false)'s chat reduction
    // (clearSessionData()). The migration path: replace the scattered chat
    // mutateChat at :1062/:1117 with dispatch(HostStatePurged(false)) for
    // the WHOLE cross-slice reset (chat + sessionList + unread + composer +
    // settings + connection — HostStatePurged already covers all of them).
    //
    // The extra fields clearSessionData() clears (revertCutoffs /
    // partExpandStates / deltaBuffer / fullTextBuffer / pendingFlushPartIds /
    // pendingScrollRequest / parentReturnCheckpoints) are the SAME
    // "deliberate improvement" documented at AppAction.kt:630-636
    // (§fix-leak-window). This test locks that HostStatePurged covers every
    // target field :1062/:1117 clears.

    @Test
    fun `HostStatePurged false covers every chat field that HostProfileController 1062 and 1117 clear`() {
        // Seed a rich chat state with NON-DEFAULT values for every field
        // :1062/:1117 clear. After dispatch(HostStatePurged(false)), every
        // one of those fields MUST be at its cleared default — proving
        // HostStatePurged is a correct superset migration target.
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "sess-A",
                messages = listOf(Message(id = "m1", role = "user")),
                partsByMessage = mapOf("m1" to listOf(Part(id = "p1", messageId = "m1", type = "text"))),
                streamingPartTexts = mapOf("p1" to "streaming"),
                streamingReasoningPart = Part(id = "r1", type = "reasoning"),
                olderMessagesCursor = "cursor",
                hasMoreMessages = true,
                isLoadingMessages = true,
                isLoadingMoreMessages = true,
                staleNotice = true,
                currentModel = Message.ModelInfo("p", "m"),
                pendingAgent = "agent",
                pendingModel = Message.ModelInfo("p", "pending"),
            ),
        )
        val store = SharedStateStore().apply { mutateState { prior } }

        store.dispatch(AppAction.HostStatePurged(preserveServerGroupData = false))

        val out = store.stateFlow.value.chat
        // Every field :1062/:1117 clear is cleared by HostStatePurged.
        assertNull("currentSessionId cleared by HostStatePurged", out.currentSessionId)
        assertTrue("messages cleared by HostStatePurged", out.messages.isEmpty())
        assertTrue("partsByMessage cleared by HostStatePurged", out.partsByMessage.isEmpty())
        assertTrue("streamingPartTexts cleared by HostStatePurged", out.streamingPartTexts.isEmpty())
        assertNull("streamingReasoningPart cleared by HostStatePurged", out.streamingReasoningPart)
        assertNull("olderMessagesCursor cleared by HostStatePurged", out.olderMessagesCursor)
        assertFalse("hasMoreMessages cleared by HostStatePurged", out.hasMoreMessages)
        assertFalse("isLoadingMessages cleared by HostStatePurged", out.isLoadingMessages)
        assertFalse("isLoadingMoreMessages cleared by HostStatePurged", out.isLoadingMoreMessages)
        assertFalse("staleNotice cleared by HostStatePurged", out.staleNotice)
        assertNull("currentModel cleared by HostStatePurged", out.currentModel)
        assertNull("pendingAgent cleared by HostStatePurged", out.pendingAgent)
        assertNull("pendingModel cleared by HostStatePurged", out.pendingModel)
    }

    @Test
    fun `HostStatePurged false also clears the extra fix-leak-window fields NOT touched by HostProfileController 1062 1117`() {
        // The fields :1062/:1117 do NOT clear but clearSessionData() DOES.
        // These are the §fix-leak-window "deliberate improvement" fields.
        // Pinning them so the impl knows HostStatePurged is a STRICT superset
        // (migrating :1062/:1117 to HostStatePurged is an improvement, not a
        // regression — same ruling as AppAction.kt:630-636).
        val prior = StoreState.initial().copy(
            chat = ChatState(
                revertCutoffs = emptyMap(),
                partExpandStates = emptyMap(),
                deltaBuffer = mapOf("p1" to "buf"),
                fullTextBuffer = mapOf("p1" to "full"),
                pendingFlushPartIds = setOf("p1"),
                pendingScrollRequest = PendingScrollRequest(1L, "sess-A", ScrollBehavior.Latest),
                parentReturnCheckpoints = emptyMap(),
            ),
        )
        val store = SharedStateStore().apply { mutateState { prior } }

        store.dispatch(AppAction.HostStatePurged(preserveServerGroupData = false))

        val out = store.stateFlow.value.chat
        assertTrue("revertCutoffs cleared by HostStatePurged (fix-leak-window)", out.revertCutoffs.isEmpty())
        assertTrue("partExpandStates cleared by HostStatePurged (fix-leak-window)", out.partExpandStates.isEmpty())
        assertTrue("deltaBuffer cleared by HostStatePurged (fix-leak-window)", out.deltaBuffer.isEmpty())
        assertTrue("fullTextBuffer cleared by HostStatePurged (fix-leak-window)", out.fullTextBuffer.isEmpty())
        assertTrue("pendingFlushPartIds cleared by HostStatePurged (fix-leak-window)", out.pendingFlushPartIds.isEmpty())
        assertNull("pendingScrollRequest cleared by HostStatePurged (Wave5b-Q13)", out.pendingScrollRequest)
        assertTrue("parentReturnCheckpoints cleared by HostStatePurged (Wave5b-Q13)", out.parentReturnCheckpoints.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════════
    // E. Documentation test — residual write sites inventory (must migrate)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `DOCUMENTATION — residual target-field mutateChat write sites that must migrate to dispatch AppAction`() {
        // This test exists to document the FULL set of residual production
        // write sites on the §2.3 target fields (messages / partsByMessage /
        // streamingPartTexts / streamingReasoningPart) that the T1b core-15
        // migration did NOT cover. Each entry MUST migrate to the action
        // listed. The test body is a no-op assertion (always passes); the
        // VALUE is the structured comment the impl lane (and rev-gpt) can
        // audit against `rg 'mutateChat'`.
        //
        // ┌────────────────────────────────────────────────────────────────────┐
        // │ A. ChatCleared (3-field clear: currentSessionId + messages + parts)│
        // ├────────────────────────────────┬───────────────────────────────────┤
        // │ file                           │ line(s)                           │
        // ├────────────────────────────────┼───────────────────────────────────┤
        // │ SessionListActions.kt          │ 409, 423, 431, 576, 590, 597      │
        // │ SessionMutationActions.kt      │ 232-237 (archive current), 359    │
        // │ SessionViewModel.kt            │ 260-265 (close-all residual)      │
        // └────────────────────────────────┴───────────────────────────────────┘
        //
        // ┌────────────────────────────────────────────────────────────────────┐
        // │ B. LastAssistantErrorAttached (SSC:1706-1712 session.error SSE)    │
        // ├────────────────────────────────┬───────────────────────────────────┤
        // │ SessionSyncCoordinator.kt      │ 1706-1712                         │
        // └────────────────────────────────┴───────────────────────────────────┘
        //
        // ┌────────────────────────────────────────────────────────────────────┐
        // │ C. CatchUpMessagesMerged (CatchUpActions:147-154, 4-field merge)   │
        // ├────────────────────────────────┬───────────────────────────────────┤
        // │ CatchUpActions.kt              │ 147-154                           │
        // └────────────────────────────────┴───────────────────────────────────┘
        //
        // ┌────────────────────────────────────────────────────────────────────┐
        // │ D. HostProfileController reset (→ reuse HostStatePurged(false))    │
        // ├────────────────────────────────┬───────────────────────────────────┤
        // │ HostProfileController.kt       │ 1062-1084 (resetLocalDataAndResync)│
        // │ HostProfileController.kt       │ 1117-1136 (resetLocalStateCore)    │
        // │                                │                                   │
        // │ These scattered mutateChat +    │ Both paths' chat field set is a   │
        // │ mutateSessionList + mutateUnread│ SUBSET of HostStatePurged(false)'s│
        // │ + mutateConnection + ... should │ clearSessionData(). Migrate to a  │
        // │ collapse to ONE dispatch.       │ SINGLE dispatch(HostStatePurged   │
        // │                                │ (preserveServerGroupData=false)).  │
        // └────────────────────────────────┴───────────────────────────────────┘
        //
        // ┌────────────────────────────────────────────────────────────────────┐
        // │ E. EXPLICITLY DEFERRED (non-target / already migrated / out-of-T1b)│
        // ├────────────────────────────────┬───────────────────────────────────┤
        // │ isLoadingMessages /             │ Single-flag writes; not §2.3     │
        // │ isLoadingMoreMessages alone     │ target fields. Defer to a later  │
        // │                                │ T1c flag-action migration.        │
        // ├────────────────────────────────┼───────────────────────────────────┤
        // │ pendingAgent / pendingModel /   │ Per-send TRANSIENT fields; not    │
        // │ staleNotice alone               │ conversation target. Defer.       │
        // ├────────────────────────────────┼───────────────────────────────────┤
        // │ cleanScrollStateForSubtree      │ Scroll fields; not §2.3 target.   │
        // ├────────────────────────────────┼───────────────────────────────────┤
        // │ AppCore VerifyAndHydrate        │ currentSessionId-only write at    │
        // │ entry guard (:591)              │ :392; not a target-field write.   │
        // ├────────────────────────────────┼───────────────────────────────────┤
        // │ ConnectionActions /              │ Non-chat slices; out of T1b      │
        // │ HostProfileController host-slice │ scope.                            │
        // └────────────────────────────────┴───────────────────────────────────┘
        assertTrue(
            "residual write sites documented — see comment block above for the full inventory",
            true,
        )
    }
}
