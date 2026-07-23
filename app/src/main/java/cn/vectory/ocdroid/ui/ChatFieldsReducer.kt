package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.ui.controller.applyMessageUpdated
import cn.vectory.ocdroid.ui.controller.mergeSlimMessages

/**
 * Wave 2 lane L2: chat-domain [reduce] branch bodies extracted as pure
 * helper functions. Covers T1b conversation-path branches (messages /
 * partsByMessage / streaming / cursor / model / expand states) plus the
 * residual writeChat-bypass + WorkdirDraftStarted cross-clear. Same package
 * as [AppAction] / [StoreState] — zero-import dispatch from [reduce].
 *
 * Each helper is a verbatim lift of the original `when`-arm body (comments +
 * early `return state` guards preserved). Behavior-preserving: no field
 * added / removed / reordered.
 */

internal fun reduceWorkdirDraftStarted(state: StoreState, action: AppAction.WorkdirDraftStarted): StoreState = state.copy(
    // §fix-leak-window (release-gate fix B): full per-session clear via
    // clearSessionData — closes the draft leak window consistently with
    // the cross-host purge (currentModel / cursor / etc. all reset;
    // pre-B2 left them stale). The 3 chrome fields are preserved
    // via .copy() inside clearSessionData.
    chat = state.chat.clearSessionData(),
    sessionList = state.sessionList.copy(
        sessionTodos = emptyMap(),
    ),
    composer = state.composer.copy(
        inputText = "",
        imageAttachments = emptyList(),
        // §1B-FIX (I4): also clear fileReferences on draft-create so a
        // chip from the previous session's draft does not survive the
        // workdir switch.
        fileReferences = emptyList(),
        draftWorkdir = action.workdir,
    ),
)

internal fun reducePartExpansionToggled(state: StoreState, action: AppAction.PartExpansionToggled): StoreState =
    state.copy(expandedParts = state.expandedParts + (action.key to action.expanded))

internal fun reduceExpandedPartsCleared(state: StoreState, action: AppAction.ExpandedPartsCleared): StoreState =
    state.copy(expandedParts = emptyMap())

// ── T1b conversation reduce (1:1 pure-fn / field-set delegates) ────────

internal fun reduceMessageUpdatedApplied(state: StoreState, action: AppAction.MessageUpdatedApplied): StoreState = state.copy(
    chat = state.chat.applyMessageUpdated(action.message).first,
)

internal fun reduceSlimMessagesMerged(state: StoreState, action: AppAction.SlimMessagesMerged): StoreState = state.copy(
    chat = state.chat.mergeSlimMessages(action.items, action.authoritative),
)

internal fun reduceMessagesMerged(state: StoreState, action: AppAction.MessagesMerged): StoreState {
    // §Stage-B §3.10 (grok S3 / bgpt SF-2): on an authoritative load the
    // fetched content is the final view — clear streamOwned entries for
    // any fetched part id (mirror mergeSlimMessages' cleared logic). On
    // a skeleton load (!authoritative), preserve streamOwned so an in-
    // flight token stream keeps its ownership. When streamOwned is empty
    // (non-token-stream users) this is a no-op → byte-for-byte parity.
    val fetchedPartIds = action.partsByMessage.values.flatten().map { it.id }.toSet()
    val newStreamOwned = if (action.authoritative) {
        state.chat.streamOwned.filterKeys { it !in fetchedPartIds }
    } else {
        state.chat.streamOwned
    }
    val newStreamingPartTexts = if (action.authoritative) {
        action.streamingPartTexts.filterKeys { it !in fetchedPartIds }
    } else {
        action.streamingPartTexts
    }
    return state.copy(
        chat = state.chat.copy(
            messages = action.messages.chronological(),
            partsByMessage = action.partsByMessage,
            isLoadingMessages = false,
            streamingPartTexts = newStreamingPartTexts,
            streamingReasoningPart = action.streamingReasoningPart,
            olderMessagesCursor = action.olderMessagesCursor,
            hasMoreMessages = action.hasMoreMessages,
            currentModel = action.currentModel,
            streamOwned = newStreamOwned,
        ),
    )
}

internal fun reduceMessagesPrepended(state: StoreState, action: AppAction.MessagesPrepended): StoreState = state.copy(
    chat = state.chat.copy(
        messages = action.messages.chronological(),
        partsByMessage = action.partsByMessage,
        olderMessagesCursor = action.olderMessagesCursor,
        hasMoreMessages = action.hasMoreMessages,
        isLoadingMoreMessages = false,
    ),
)

internal fun reduceChatWindowHydrated(state: StoreState, action: AppAction.ChatWindowHydrated): StoreState = state.copy(
    chat = state.chat.copy(
        messages = action.messages.chronological(),
        partsByMessage = action.partsByMessage,
        olderMessagesCursor = action.olderMessagesCursor,
        hasMoreMessages = action.hasMoreMessages,
    ),
)

internal fun reduceSessionSelected(state: StoreState, action: AppAction.SessionSelected): StoreState = state.copy(
    // SessionSwitcher.kt:417-472 field set (15 writes) in ONE dispatch.
    chat = state.chat.copy(
        currentSessionId = action.sessionId,
        pendingScrollRequest = action.pendingScrollRequest,
        messages = emptyList(),
        partsByMessage = emptyMap(),
        streamingPartTexts = emptyMap(),
        streamOwned = emptyMap(),
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
    ),
)

internal fun reduceSlimChatContentCleared(state: StoreState, action: AppAction.SlimChatContentCleared): StoreState = state.copy(
    // ClearLocal: messages + partsByMessage ONLY (streaming preserved).
    chat = state.chat.copy(
        messages = emptyList(),
        partsByMessage = emptyMap(),
    ),
)

// ── T1b residual reduce ────────────────────────────────────────────────

internal fun reduceChatCleared(state: StoreState, action: AppAction.ChatCleared): StoreState = state.copy(
    // 3-field clear only — streaming / cursor / model / staleNotice survive.
    chat = state.chat.copy(
        currentSessionId = null,
        messages = emptyList(),
        partsByMessage = emptyMap(),
    ),
)

internal fun reduceLastAssistantErrorAttached(state: StoreState, action: AppAction.LastAssistantErrorAttached): StoreState {
    // SSC:1706-1712 1:1 — attach to last assistant; no-op if absent or
    // already has an error.
    val last = state.chat.messages.lastOrNull { it.isAssistant }
    return if (last == null || last.error != null) {
        state
    } else {
        state.copy(
            chat = state.chat.copy(
                messages = state.chat.messages.map { m ->
                    if (m.id == last.id) m.copy(error = action.error) else m
                },
            ),
        )
    }
}

internal fun reduceCatchUpMessagesMerged(state: StoreState, action: AppAction.CatchUpMessagesMerged): StoreState = state.copy(
    // CatchUpActions:147-154 4-field merge (not MessagesMerged's 8).
    chat = state.chat.copy(
        messages = action.messages.chronological(),
        partsByMessage = action.partsByMessage,
        isLoadingMessages = false,
        staleNotice = false,
    ),
)

// ── T1b writeChat-bypass reduce ────────────────────────────────────────

internal fun reduceColdStartChatReset(state: StoreState, action: AppAction.ColdStartChatReset): StoreState = state.copy(
    // AppCoreOrchestration:577-590 8-field reset (1:1).
    chat = state.chat.copy(
        streamingPartTexts = emptyMap(),
        streamOwned = emptyMap(),
        streamingReasoningPart = null,
        staleNotice = false,
        messages = emptyList(),
        partsByMessage = emptyMap(),
        olderMessagesCursor = null,
        hasMoreMessages = false,
        isLoadingMoreMessages = false,
    ),
)

internal fun reduceExpandedPartsContentCommitted(state: StoreState, action: AppAction.ExpandedPartsContentCommitted): StoreState = state.copy(
    // Strategy 2: pure reconcile against latest chat (CAS via state.update).
    // Session guard + merge live inside reconcileExpandedPartsContent.
    chat = state.chat.reconcileExpandedPartsContent(
        outcome = action.outcome,
        local = action.local,
        expectedSessionId = action.expectedSessionId,
    ),
)
