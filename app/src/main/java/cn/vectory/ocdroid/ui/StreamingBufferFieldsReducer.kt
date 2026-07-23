package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.ui.controller.appendDeltaBuffer
import cn.vectory.ocdroid.ui.controller.applyPartCreatedPlaceholder
import cn.vectory.ocdroid.ui.controller.applyPartDeltaLeadingEdge
import cn.vectory.ocdroid.ui.controller.applyPartFullTextLeadingEdge
import cn.vectory.ocdroid.ui.controller.clearAllCoalesceBuffers
import cn.vectory.ocdroid.ui.controller.clearCoalesceBufferForPart
import cn.vectory.ocdroid.ui.controller.clearTokenStreamState
import cn.vectory.ocdroid.ui.controller.flushCoalesceBufferForPart
import cn.vectory.ocdroid.ui.controller.markFlushPending
import cn.vectory.ocdroid.ui.controller.replaceFullTextBuffer

/**
 * Wave 2 lane L2: streaming-buffer-domain [reduce] branch bodies extracted as
 * pure helper functions. Covers the T1b streaming-path ownership family
 * (streamingPartTexts / coalesce buffers / placeholders) AND the token-stream
 * bridge actions (ClearTokenStreamState / TokenStreamPartUpdated). Same
 * package as [AppAction] / [StoreState] — zero-import dispatch from [reduce].
 *
 * Each helper is a verbatim lift of the original `when`-arm body (comments
 * preserved). Behavior-preserving: no field added / removed / reordered.
 */

// ── T1b streaming reduce (1:1 pure-fn delegates) ───────────────────────

internal fun reducePartPlaceholderEnsured(state: StoreState, action: AppAction.PartPlaceholderEnsured): StoreState = state.copy(
    chat = state.chat.applyPartCreatedPlaceholder(
        action.partType, action.partId, action.messageId, action.sessionId,
    ).first,
)

internal fun reducePartFullTextReceived(state: StoreState, action: AppAction.PartFullTextReceived): StoreState = state.copy(
    // pId = partId (same key used by the SSC leading-edge call site).
    chat = state.chat.applyPartFullTextLeadingEdge(
        partId = action.partId,
        fullText = action.fullText,
        partType = action.partType,
        pId = action.partId,
        msgId = action.messageId,
        sessionId = action.sessionId,
    ).first.markFlushPending(action.partId).first,
)

internal fun reducePartDeltaReceived(state: StoreState, action: AppAction.PartDeltaReceived): StoreState = state.copy(
    // 5-arg overload (knownType + msgId + sessionId); NOT the 6-arg
    // part.updated variant. Matches SSC:1539 + the T1b freeze test.
    chat = state.chat.applyPartDeltaLeadingEdge(
        partId = action.partId,
        delta = action.delta,
        knownType = action.partType,
        msgId = action.messageId,
        sessionId = action.sessionId,
    ).first.markFlushPending(action.partId).first,
)

internal fun reduceFullTextBuffered(state: StoreState, action: AppAction.FullTextBuffered): StoreState = state.copy(
    chat = state.chat.replaceFullTextBuffer(action.partId, action.text).first,
)

internal fun reduceDeltaBuffered(state: StoreState, action: AppAction.DeltaBuffered): StoreState = state.copy(
    chat = state.chat.appendDeltaBuffer(action.partId, action.delta).first,
)

internal fun reduceCoalesceFlushedForPart(state: StoreState, action: AppAction.CoalesceFlushedForPart): StoreState = state.copy(
    chat = state.chat.flushCoalesceBufferForPart(action.partId).first,
)

internal fun reduceCoalesceClearedForPart(state: StoreState, action: AppAction.CoalesceClearedForPart): StoreState = state.copy(
    chat = state.chat.clearCoalesceBufferForPart(action.partId).first,
)

internal fun reduceCoalesceBuffersCleared(state: StoreState, action: AppAction.CoalesceBuffersCleared): StoreState = state.copy(
    chat = state.chat.clearAllCoalesceBuffers().first,
)

internal fun reduceClearTokenStreamState(state: StoreState, action: AppAction.ClearTokenStreamState): StoreState = state.copy(
    chat = state.chat.clearTokenStreamState(action.partIds),
)

internal fun reduceTokenStreamPartUpdated(state: StoreState, action: AppAction.TokenStreamPartUpdated): StoreState = state.copy(
    // §Stage-D1 §3.8 bridge: REPLACE streamingPartTexts[partId] + streamOwned[partId].
    // The reducer already accumulated the joined buffer text; mirror it into the
    // chat slice so the legacy single-owner guard (SharedConversationSseHandler)
    // + Stage-A ClearTokenStreamState see the live overlay. This is the write-side
    // counterpart to hasActiveTokenStreamOwner() / clearTokenStreamState().
    chat = state.chat.copy(
        streamingPartTexts = state.chat.streamingPartTexts + (action.partId to action.text),
        streamOwned = state.chat.streamOwned + (action.partId to action.state),
    ),
)
