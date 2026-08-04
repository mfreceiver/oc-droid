package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.ui.ChatViewModel
import cn.vectory.ocdroid.ui.NavRoute
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.PendingScrollRequest
import cn.vectory.ocdroid.ui.ScrollBehavior
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

// ── §wave2-1-l4: 3 mixed scroll effects extracted from ChatMessageList ───
//
// These effects read NON-scroll state and write through [ScrollController]'s
// exposed MutableState properties (followBottom / navFabVisible /
// pendingRestoreSession). Key tuples are preserved VERBATIM from the
// original ChatMessageList body.

/**
 * §Wave5b-Q13: unified scroll consumer. Observes [chatState.pendingScrollRequest];
 * when its targetSessionId matches the active sessionId, fires exactly once:
 *  - Latest → scrollToItem(0) + followBottom=true + hide NavFab.
 *  - Restore(checkpoint) → resolve anchor via [lazyColumnKeys], scrollToItem,
 *    set followBottom based on resolved position.
 *
 * After completion → dispatches [chatVM.consumeScrollRequest] for compare-
 * and-clear by requestId (a stale consumer's clear is a no-op against a
 * newer id).
 *
 * Key tuple: `sessionId, pendingScrollRequest?.requestId, messages.isEmpty())`
 * — preserved verbatim from the original ChatMessageList body (§Wave5b-Q13).
 */
@Composable
internal fun UnifiedScrollConsumerEffect(
    sessionId: String?,
    pendingScrollRequest: PendingScrollRequest?,
    messages: List<Message>,
    scroll: ScrollController,
    listState: LazyListState,
    lazyColumnKeys: List<String>,
    chatVM: ChatViewModel,
) {
    LaunchedEffect(sessionId, pendingScrollRequest?.requestId, messages.isEmpty()) {
        val req = pendingScrollRequest ?: return@LaunchedEffect
        if (sessionId == null) return@LaunchedEffect
        if (req.targetSessionId != sessionId) return@LaunchedEffect
        if (messages.isEmpty()) return@LaunchedEffect  // wait for the load
        when (val b = req.behavior) {
            is ScrollBehavior.Latest -> {
                snapshotFlow { listState.layoutInfo.totalItemsCount }
                    .first { it > 0 }
                listState.scrollToItem(0)
                scroll.followBottom = true
                scroll.navFabVisible = false
            }
            is ScrollBehavior.Restore -> {
                val cp = b.checkpoint
                // Restore-in-flight guard
                scroll.pendingRestoreSession = sessionId
                scroll.followBottom = false
                val keys = lazyColumnKeys
                val itemCount = keys.size
                if (itemCount > 0) {
                    val resolved = resolveRestoreIndex(
                        checkpoint = cp,
                        currentKeys = keys,
                    )
                    if (resolved != null) {
                        listState.scrollToItem(resolved.index, resolved.offset)
                        if (resolved.index == 0 && resolved.offset <= 24) {
                            scroll.followBottom = true
                        }
                    }
                }
                scroll.pendingRestoreSession = null
            }
        }
        chatVM.consumeScrollRequest(req.requestId)
    }
}

/**
 * §0.8.2 P2.6: Chat reselect → scroll to latest. On each emission of
 * [NavRoute.Chat] from [orchestratorVM.reselectFlow], sets followBottom=true
 * and jumps to item 0.
 *
 * Key tuple: `orchestratorVM` — preserved verbatim.
 */
@Composable
internal fun ReselectScrollEffect(
    orchestratorVM: OrchestratorViewModel,
    scroll: ScrollController,
    listState: LazyListState,
) {
    LaunchedEffect(orchestratorVM) {
        orchestratorVM.reselectFlow
            .filter { it == NavRoute.Chat }
            .collect {
                scroll.followBottom = true
                scroll.navFabVisible = false
                if (listState.layoutInfo.totalItemsCount > 0) {
                    listState.scrollToItem(0)
                }
            }
    }
}

/**
 * §scroll-stable: content-version-driven scroll / auto-follow. Scrolls to
 * bottom on structural changes (session switch, message count change,
 * streaming lifecycle) when [scroll.followBottom] is true.
 *
 * Two guards prevent the restore race:
 *   (1) restore-in-flight — [scroll.pendingRestoreSession] == sessionId
 *   (2) restore-pending — a Restore PendingScrollRequest targeting this
 *       session that hasn't been consumed yet (reads inline from chat state
 *       via the provided [pendingScrollRequest]).
 * These guards were deliberately verified in the original code.
 *
 * Key tuple: `sessionId, messages.size, isStreaming, streamingPartTextKeys,
 * streamingReasoningPart?.id` — preserved verbatim.
 */
@Composable
internal fun ContentVersionScrollEffect(
    sessionId: String?,
    scroll: ScrollController,
    listState: LazyListState,
    messages: List<Message>,
    isStreaming: Boolean,
    streamingPartTextKeys: Set<String>,
    streamingReasoningPart: Part?,
    expandedParts: Map<String, Boolean>,
    pendingScrollRequest: PendingScrollRequest?,
) {
    LaunchedEffect(sessionId, messages.size, isStreaming, streamingPartTextKeys, streamingReasoningPart?.id) {
        // §Wave5b-Q13: skip auto-follow while a Restore is pending or in flight.
        val restoreInFlight = scroll.pendingRestoreSession == sessionId
        val restorePending = pendingScrollRequest != null &&
            pendingScrollRequest.targetSessionId == sessionId &&
            pendingScrollRequest.behavior is ScrollBehavior.Restore
        if (restoreInFlight || restorePending) return@LaunchedEffect

        val streamingReasoningExpanded = streamingReasoningPart?.let { sr ->
            val key = sr.messageId?.let { "$it|${sr.id}" } ?: "streaming|${sr.id}"
            expandedParts[key] == true
        } == true

        if (scroll.followBottom && !streamingReasoningExpanded &&
            (messages.isNotEmpty() || streamingReasoningPart != null)) {
            if (isStreaming) {
                val atBottom = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset <= 24
                if (atBottom) listState.requestScrollToItem(0)
            } else {
                listState.animateScrollToItem(0)
            }
        }
    }
}
