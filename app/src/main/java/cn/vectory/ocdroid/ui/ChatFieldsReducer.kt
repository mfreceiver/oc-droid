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

internal fun reduceMessageUpdatedApplied(state: StoreState, action: AppAction.MessageUpdatedApplied): StoreState {
    if (!state.acceptsRouteUpdate(action.expectedRouteInstance, action.sessionId)) return state
    return state.copy(chat = state.chat.applyMessageUpdated(action.message).first)
        .withRouteContentSynced(
            expectedRouteInstance = action.expectedRouteInstance,
            expectedSessionId = action.sessionId ?: state.chat.currentSessionId,
        )
}

internal fun reduceSlimMessagesMerged(state: StoreState, action: AppAction.SlimMessagesMerged): StoreState {
    if (!state.acceptsRouteUpdate(action.expectedRouteInstance, action.sessionId)) return state
    return state.copy(chat = state.chat.mergeSlimMessages(action.items, action.authoritative))
        .withRouteContentSynced(action.expectedRouteInstance, action.sessionId)
}

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

internal fun reduceMessagesPrepended(state: StoreState, action: AppAction.MessagesPrepended): StoreState {
    if (!state.acceptsRouteUpdate(action.expectedRouteInstance, action.sessionId)) return state
    // This is the legacy loadMore 5-field write.  The route-aware projection is
    // mirrored only when the caller carries a non-zero route token; bare-chat
    // callers remain flat-only for source compatibility.
    return state.copy(
        chat = state.chat.copy(
            messages = action.messages.chronological(),
            partsByMessage = action.partsByMessage,
            olderMessagesCursor = action.olderMessagesCursor,
            hasMoreMessages = action.hasMoreMessages,
            isLoadingMoreMessages = false,
        ),
    ).withRouteContentSynced(action.expectedRouteInstance, action.sessionId)
}

internal fun reduceChatWindowHydrated(state: StoreState, action: AppAction.ChatWindowHydrated): StoreState {
    if (!state.acceptsRouteUpdate(action.expectedRouteInstance, action.sessionId)) return state
    val hydratedMessages = action.messages.chronological()
    val routeSessionId = action.sessionId ?: state.chat.currentSessionId
    val routeContent = if (
        action.expectedRouteInstance > 0L &&
        routeSessionId != null &&
        routeSessionId == state.chat.currentSessionId &&
        action.expectedRouteInstance == state.chatRouteInstance
    ) {
        // Cache hydration is itself a route-owned initial commit. Construct
        // the value object here rather than leaving the parameterized route
        // stuck on the loading surface until the follow-up REST load returns.
        LoadedContent(
            sessionId = routeSessionId,
            messages = hydratedMessages,
            partsByMessage = action.partsByMessage,
            streamingPartTexts = state.chat.streamingPartTexts,
            streamOwned = state.chat.streamOwned,
            streamingReasoningPart = state.chat.streamingReasoningPart,
            olderMessagesCursor = action.olderMessagesCursor,
            hasMoreMessages = action.hasMoreMessages,
            currentModel = state.chat.currentModel,
            routeInstance = action.expectedRouteInstance,
        )
    } else {
        state.chat.content
    }
    return state.copy(
        chat = state.chat.copy(
            messages = hydratedMessages,
            partsByMessage = action.partsByMessage,
            olderMessagesCursor = action.olderMessagesCursor,
            hasMoreMessages = action.hasMoreMessages,
            content = routeContent,
        ),
    ).withRouteContentSynced(action.expectedRouteInstance, action.sessionId)
}

internal fun reduceSessionSelected(state: StoreState, action: AppAction.SessionSelected): StoreState = state.copy(
    // SessionSwitcher.kt:417-472 field set in ONE dispatch.
    // Route-aware selection advances the incarnation in the same aggregate
    // transition as the legacy chat clear.  Legacy callers pass null, so the
    // historical SessionSelected state shape remains byte-for-byte unchanged.
    chatRouteInstance = action.routeInstance?.let { maxOf(state.chatRouteInstance, it) }
        ?: state.chatRouteInstance,
    chat = state.chat.copy(
        currentSessionId = action.sessionId,
        pendingScrollRequest = action.pendingScrollRequest,
        content = null,
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
        // §isCompacting-leak-fix (2026-07-26): reset compaction flags on
        // session switch. Without this, isCompacting=true from session A
        // leaks to session B → composer disabled ("busy"), phantom
        // compacting indicator shown on a session that isn't compacting.
        isCompacting = false,
        compactStartedAt = 0L,
        // §B4 rev-gpt round3 CRITICAL: clear coalesce buffers on EVERY
        // SessionSelected — including same-session route re-entry (navigateToChat
        // → openForRoute → performSwitch ALWAYS dispatches SessionSelected, no
        // same-session guard). Without this, a stale flush Job (still pending on
        // the coordinator because SessionSwitcher only dispatches
        // ClearDeltaBuffers on a real session-id change) would read the NEW route
        // token from slices.routeInstanceFor(sid) at flush time and dispatch
        // CoalesceFlushedForPart(newToken, sid) — the reducer's acceptsRouteUpdate
        // accepts (token matches the new incarnation), and the buffer (still
        // holding the prior incarnation's delta/fullText) would be applied,
        // corrupting the new incarnation's content. Clearing here at the
        // authoritative reducer guarantees the late flush finds empty buffers
        // (flushCoalesceBufferForPart's `bufferedDelta.isNullOrEmpty()` branch is
        // a verified no-op). Same-session re-entry must NOT inherit prior buffers.
        deltaBuffer = emptyMap(),
        fullTextBuffer = emptyMap(),
        pendingFlushPartIds = emptySet(),
    ),
)

internal fun reduceSlimChatContentCleared(state: StoreState, action: AppAction.SlimChatContentCleared): StoreState = state.copy(
    // ClearLocal: messages + partsByMessage ONLY (streaming preserved).
    chat = state.chat.copy(
        messages = emptyList(),
        partsByMessage = emptyMap(),
    ),
)

internal fun reduceSlimChatContentClearedForRoute(
    state: StoreState,
    action: AppAction.SlimChatContentClearedForRoute,
): StoreState {
    if (!state.acceptsRouteUpdate(action.expectedRouteInstance, action.sessionId)) return state
    return state.copy(
        chat = state.chat.copy(
            messages = emptyList(),
            partsByMessage = emptyMap(),
        ),
    ).withRouteContentSynced(action.expectedRouteInstance, action.sessionId)
}

// ── T1b residual reduce ────────────────────────────────────────────────

internal fun reduceChatCleared(state: StoreState, action: AppAction.ChatCleared): StoreState = state.copy(
    // 3-field clear only — streaming / cursor / model / staleNotice survive.
    chat = state.chat.copy(
        currentSessionId = null,
        content = null,
        messages = emptyList(),
        partsByMessage = emptyMap(),
    ),
)

internal fun reduceLastAssistantErrorAttached(state: StoreState, action: AppAction.LastAssistantErrorAttached): StoreState {
    if (!state.acceptsRouteUpdate(action.expectedRouteInstance, action.sessionId)) return state
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
        ).withRouteContentSynced(action.expectedRouteInstance, action.sessionId)
    }
}

internal fun reduceCatchUpMessagesMerged(state: StoreState, action: AppAction.CatchUpMessagesMerged): StoreState {
    if (!state.acceptsRouteUpdate(action.expectedRouteInstance, action.sessionId)) return state
    return state.copy(
        chat = state.chat.copy(
            // CatchUpActions:147-154 4-field merge (not MessagesMerged's 8).
            messages = action.messages.chronological(),
            partsByMessage = action.partsByMessage,
            isLoadingMessages = false,
            staleNotice = false,
        ),
    ).withRouteContentSynced(action.expectedRouteInstance, action.sessionId)
}

// ── T1b writeChat-bypass reduce ────────────────────────────────────────

internal fun reduceColdStartChatReset(state: StoreState, action: AppAction.ColdStartChatReset): StoreState = state.copy(
    // AppCoreOrchestration:577-590 legacy reset (1:1 for the historical fields)
    // + §B4 rev-gpt round3 CRITICAL: also clear the SSE coalesce buffers.
    // The legacy 8-field write intentionally preserves currentSessionId /
    // currentModel / pendingAgent / isLoadingMessages (chrome fields); only its
    // historical fields are owned here. The coalesce buffers are NOT chrome —
    // they represent in-flight streaming state for the prior incarnation. After
    // cold-start the route has advanced, so a stale flush Job carrying the new
    // route token (read dynamically in flushDeltaBuffer) must find empty
    // buffers here, otherwise the prior incarnation's delta/fullText would
    // resurrect as the new incarnation's content (same hazard as SessionSelected).
    chat = state.chat.copy(
        content = null,
        streamingPartTexts = emptyMap(),
        streamingReasoningPart = null,
        staleNotice = false,
        messages = emptyList(),
        partsByMessage = emptyMap(),
        olderMessagesCursor = null,
        hasMoreMessages = false,
        isLoadingMoreMessages = false,
        // §B4 rev-gpt round3 CRITICAL: clear coalesce buffers (see header).
        deltaBuffer = emptyMap(),
        fullTextBuffer = emptyMap(),
        pendingFlushPartIds = emptySet(),
    ),
)

internal fun reduceExpandedPartsContentCommitted(state: StoreState, action: AppAction.ExpandedPartsContentCommitted): StoreState {
    if (!state.acceptsRouteUpdate(action.expectedRouteInstance, action.expectedSessionId)) return state
    return state.copy(
    // Strategy 2: pure reconcile against latest chat (CAS via state.update).
    // Session guard + merge live inside reconcileExpandedPartsContent.
    chat = state.chat.reconcileExpandedPartsContent(
        outcome = action.outcome,
        local = action.local,
        expectedSessionId = action.expectedSessionId,
    ),
    ).withRouteContentSynced(action.expectedRouteInstance, action.expectedSessionId)
}

// ── B-P0-2: evict a single message confirmed deleted by R2 /full reconcile ─

@Suppress("DEPRECATION")
internal fun reduceMessageRemovedFromFull(state: StoreState, action: AppAction.MessageRemovedFromFull): StoreState {
    // B-P0-2 (MAJOR 4): evict the message from messages + partsByMessage by
    // messageId. The per-message watermark was already removed by
    // the in-memory slim state under the slim commit token guard.
    // sessionId is informational — eviction is by messageId.
    //
    // §Stage-B C5 (CRITICAL) — M5 cleanup backport: the legacy call site
    // (ControllerModule.onMessageGone) is migrated to [MessageRemovedConfirmed]
    // by a parallel lane; until then this reducer retains source compat
    // AND applies the same overlay cleanup the new reducer does, so the
    // legacy dispatch path cannot leave ghost text from the removed
    // message's parts. The new route-token / bundle-stamp guard is NOT
    // retrofitted here (the legacy action carries neither field); the
    // freeze-protocol guard lives in [reduceMessageRemovedConfirmed].
    val msgId = action.messageId
    val partIds = state.chat.partsByMessage[msgId].orEmpty().map { it.id }.toSet()
    return state.copy(
        chat = state.chat.evictMessageAndPartOverlay(msgId, partIds),
    )
}

/**
 * §Stage-B C5 (CRITICAL): `/full` 200 Reconciled single-message merge —
 * non-authoritative (preserves STREAMING token-stream-owned parts).
 *
 * The reducer threads [AppAction.SlimFullMessageReconciled.expectedRouteInstance]
 * + [AppAction.SlimFullMessageReconciled.bundleStamp] (captured at the
 * request trigger) into the §7.2 freshness CAS + bundle CAS so a stale
 * dispatch whose route has advanced (or whose bundle has rotated) is
 * dropped. The flat + [LoadedContent] projections are updated in the
 * same reducer pass via [withRouteContentSynced].
 */
internal fun reduceSlimFullMessageReconciled(
    state: StoreState,
    action: AppAction.SlimFullMessageReconciled,
): StoreState {
    if (!state.acceptsRouteUpdate(action.expectedRouteInstance, action.sessionId)) return state
    return state.copy(
        chat = state.chat.mergeSlimMessages(
            items = listOf(action.message),
            authoritative = false,
        ),
    ).withRouteContentSynced(action.expectedRouteInstance, action.sessionId)
}

/**
 * §Stage-B C5 (CRITICAL): source-agnostic removal confirmation (R2 `/full`
 * HTTP 404 OR token stream `message.removed`).
 *
 * Freeze protocol: an async removal MUST NOT write route-owned transcript
 * state when there is no active route. A dispatch with
 * `expectedRouteInstance == 0L` is therefore a no-op for the chat
 * transcript (the watermark/repository cleanup lives outside this
 * reducer — it does not need a chat-route token). The non-zero path
 * CAS-rejects stale incarnations and then evicts the message from BOTH
 * the flat projection AND [LoadedContent] (the dual-projection invariant)
 * while clearing every streaming-overlay entry owned by the message's
 * parts so a late straggler frame cannot resurrect ghost text.
 */
internal fun reduceMessageRemovedConfirmed(
    state: StoreState,
    action: AppAction.MessageRemovedConfirmed,
): StoreState {
    // Freeze protocol §C5: no active route → no transcript write.
    if (action.expectedRouteInstance == 0L) return state
    if (!state.acceptsRouteUpdate(action.expectedRouteInstance, action.sessionId)) return state
    val msgId = action.messageId
    // Collect the message's part IDs from BOTH projections — the flat map
    // is the legacy authority, but LoadedContent may carry an equivalent
    // view that contributed the same part IDs. Either source's ids must
    // be cleared from the streaming overlay.
    val flatPartIds = state.chat.partsByMessage[msgId].orEmpty().map { it.id }
    val loadedPartIds = state.chat.content?.partsByMessage?.get(msgId).orEmpty().map { it.id }
    val partIds = (flatPartIds + loadedPartIds).toSet()
    return state.copy(
        chat = state.chat.evictMessageAndPartOverlay(msgId, partIds),
    ).withRouteContentSynced(action.expectedRouteInstance, action.sessionId)
}

/**
 * Shared pure helper: evict message [msgId] from `messages` +
 * `partsByMessage` AND clear every streaming-overlay entry owned by
 * [partIds] (streamOwned / streamingPartTexts / deltaBuffer /
 * fullTextBuffer / pendingFlushPartIds / matching streamingReasoningPart).
 * Used by both [reduceMessageRemovedFromFull] (legacy) and
 * [reduceMessageRemovedConfirmed] (route-aware) so the overlay-cleanup
 * contract is identical across the two dispatch shapes. Pure.
 */
private fun ChatState.evictMessageAndPartOverlay(
    msgId: String,
    partIds: Set<String>,
): ChatState = copy(
    messages = messages.filterNot { it.id == msgId },
    partsByMessage = partsByMessage - msgId,
    streamOwned = if (partIds.isEmpty()) streamOwned else streamOwned.filterKeys { it !in partIds },
    streamingPartTexts = if (partIds.isEmpty()) streamingPartTexts else streamingPartTexts.filterKeys { it !in partIds },
    deltaBuffer = if (partIds.isEmpty()) deltaBuffer else deltaBuffer.filterKeys { it !in partIds },
    fullTextBuffer = if (partIds.isEmpty()) fullTextBuffer else fullTextBuffer.filterKeys { it !in partIds },
    pendingFlushPartIds = if (partIds.isEmpty()) pendingFlushPartIds else pendingFlushPartIds - partIds,
    streamingReasoningPart = streamingReasoningPart?.takeUnless { it.id in partIds },
)
