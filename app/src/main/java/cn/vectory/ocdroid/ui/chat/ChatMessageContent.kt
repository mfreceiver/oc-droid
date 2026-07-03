package cn.vectory.ocdroid.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.isRenderableEmptyMessage
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.GapInfo
import cn.vectory.ocdroid.ui.MainViewModel
import cn.vectory.ocdroid.ui.METADATA_MARKER_ROLES
import cn.vectory.ocdroid.ui.injectMetadataMarkers
import cn.vectory.ocdroid.ui.isStaleQuestionPart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop

// ── Chat message list container ──────────────────────────────────────────
// The top-level scrollable list of chat turns. Owns the per-session scroll
// position LRU cache, auto-follow-bottom, history paging, and the §Phase1C
// gap divider. Per-message rendering is delegated to [MessageRow] (in
// ChatMessageRow.kt); all card composables live in their own sibling files.

@Composable
internal fun ChatMessageList(
    viewModel: MainViewModel,
    onFileClick: (String) -> Unit,
    onTabVisibilityChange: (Boolean) -> Unit = {},
    // §3-scroll-memory: hoisted per-session scroll-position cache + its
    // access-order LRU ledger. Lifted out of this composable (previously
    // local `remember{}` blocks) so the HorizontalPager page slot disposing
    // and recreating ChatMessageList on currentSessionId flip no longer
    // drops the cached positions. Owned by ChatScreen; mutated here.
    savedPositions: SnapshotStateMap<String, Pair<Int, Int>>,
    accessOrder: SnapshotStateList<String>
) {
    // §R-17 Stage 2: subscribe to chatFlow + sessionListFlow directly so SSE
    // streaming deltas (streamingPartTexts mutation) only recompose this list,
    // and typing (composerFlow) / connection / settings changes do NOT. The
    // messages/streaming/parts params were previously List/Map (unstable in
    // Compose) passed from ChatScreen's AppState read, which forced a
    // recomposition on every AppState emission. Reading from the slice Flows
    // here lets the runtime skip this composable when neither slice emits.
    val chatState by viewModel.chatFlow.collectAsStateWithLifecycle()
    val sessionListState by viewModel.sessionListFlow.collectAsStateWithLifecycle()
    val expandedParts by viewModel.expandedParts.collectAsStateWithLifecycle()

    // visibleMessages is a cross-slice derived value: the revert message id
    // lives on the Session (sessionListFlow) but the messages list lives on
    // chatFlow. Recompute only when either input changes (remember key).
    val currentSession = sessionListState.sessions.find { it.id == chatState.currentSessionId }
    val messages: List<Message> = remember(chatState.messages, currentSession?.revert?.messageId) {
        val revertMessageId = currentSession?.revert?.messageId
        val reverted = if (revertMessageId == null) chatState.messages else chatState.messages.filter { it.id < revertMessageId }
        // §s3-markers: keep user/assistant turns + the synthetic metadata
        // marker roles, then interleave markers wherever agent/model
        // changed between consecutive turns.
        val visible = reverted.filter { !it.isToolRole || it.role in METADATA_MARKER_ROLES }
        injectMetadataMarkers(visible)
    }
    val partsByMessage: Map<String, List<Part>> = chatState.partsByMessage
    val streamingPartTexts: Map<String, String> = chatState.streamingPartTexts
    val streamingReasoningPart: Part? = chatState.streamingReasoningPart
    val isLoading: Boolean = chatState.isLoadingMessages
    // §flicker-fix: whether the current session is actively generating. The
    // server creates the assistant message (message.updated insert) BEFORE its
    // first part arrives, so for ~1s partsByMessage[assistantId] is empty and
    // no streaming part references it yet. Using the session busy/retry status
    // (set synchronously at send time, true through idle) as the keep-signal
    // robustly covers that "pre-part window" — unlike streamingPartTexts,
    // which is empty exactly then (the user-input-echo part is filtered by the
    // §user-part-guard in SessionSyncCoordinator, so it no longer leaks into
    // the overlay to fake-keep the row). See ChatMessageList filter §flicker-fix-D.
    val sessionIsRunning = chatState.currentSessionId?.let { id ->
        sessionListState.sessionStatuses[id]?.let { it.isBusy || it.isRetry }
    } == true
    val hasMoreMessages: Boolean = chatState.hasMoreMessages
    val gapInfo: GapInfo? = chatState.gapInfo
    val repository: OpenCodeRepository = viewModel.repository
    val workspaceDirectory: String? = currentSession?.directory
    val onLoadMore: () -> Unit = viewModel::loadMoreMessages
    val onOpenSubAgent: (String) -> Unit = viewModel::openSubAgent
    val onToggleExpand: (String, Boolean) -> Unit = viewModel::togglePartExpand
    val onCloseGap: () -> Unit = viewModel::closeGap

    // §stale-question: compute the set of part ids that are stuck "running"
    // question parts WITHOUT a matching live QuestionRequest — these render
    // in a terminal "Interrupted" state instead of a perpetual spinner.
    // Recomputed only when the message window or the pending-questions list
    // changes. Pure derivation: reads partsByMessage + sessionListState.
    val pendingQuestions = sessionListState.pendingQuestions
    val staleQuestionPartKeys: Set<String> = remember(partsByMessage, pendingQuestions) {
        partsByMessage.values.flatten().mapNotNullTo(HashSet()) { part ->
            if (isStaleQuestionPart(part, pendingQuestions)) part.id else null
        }
    }

    // sessionId 在 remember key 里需要——提前取（下面 savedPositions 等也用）。
    val sessionId = chatState.currentSessionId

    val listState = rememberLazyListState()
    var shouldAutoScroll by remember { mutableStateOf(true) }
    // §3-scroll-memory: per-session scroll-position memory is now HOISTED to
    // ChatScreen (above the HorizontalPager) so the pager disposing this
    // composable on a currentSessionId flip no longer drops the cache. The
    // `savedPositions` map + `accessOrder` LRU ledger are received as params.
    // Before opening a sub-session the user's last scroll offset in the
    // parent is recorded via the mirror effect below; returning to the parent
    // restores it instead of jumping to the latest message.
    //
    // 🟡 Lifecycle constraint (glmer 🟡-6) — RESOLVED by hoisting: the cache
    // used to be bound to ChatMessageList's composition, so navigation away
    // from the chat tab / config change wiped it. It now survives as long as
    // ChatScreen stays in composition. NOT persisted across process death
    // (acceptable: cold start lands on the latest-message view + the
    // server-side message re-fetch seeds a fresh window). Capacity is bounded
    // by MAX_SAVED_SESSIONS (LRU eviction) to avoid unbounded growth.
    //
    // 🟡 True LRU via accessOrder list (kimo 9.4 复审): SnapshotStateMap has
    // **HashMap semantics — its iteration order is NOT insertion order** (per
    // Compose source). A prior version used `savedPositions.keys.first()` to
    // evict the "oldest" entry, but that actually removed an arbitrary (hash-
    // bucket) entry — a pseudo-LRU bug. We now keep a parallel
    // `mutableStateListOf<String>` (`SnapshotStateList`, which IS index-stable
    // and preserves order) as an explicit access-order ledger: every write or
    // restore-read of a sessionId moves its id to the tail; eviction pops from
    // the head. This gives true LRU semantics.
    //
    // pendingRestoreSession is the only piece of scroll state that stays
    // LOCAL to ChatMessageList: it tracks "we owe the user a programmatic
    // scroll-to-saved-position once this session's messages materialise",
    // which is per-composable-instance (a fresh ChatMessageList instance
    // starts with no restore pending; the LaunchedEffect(sessionId) below
    // queues one from the hoisted savedPositions if a saved entry exists).
    var pendingRestoreSession by remember { mutableStateOf<String?>(null) }
    val contentVersion = remember(messages, partsByMessage, streamingPartTexts, streamingReasoningPart, isLoading) {
        messages.size +
            partsByMessage.values.sumOf { it.size } +
            streamingPartTexts.hashCode() +
            (if (streamingReasoningPart != null) 1 else 0) +
            (if (isLoading) 1 else 0)
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 24
        }.collect { atBottom ->
            shouldAutoScroll = atBottom
        }
    }

    // #3 — continuously mirror the current scroll offset against the active
    // session id. There is no "before session change" hook in Compose, so a
    // reactive mirror is the simplest robust way to ensure the latest offset
    // is on file the instant the user navigates into a sub-session.
    //
    // 🔴 Race fix (glmer 🔴-1 + kimo 🔴-1): when `sessionId` changes this
    // LaunchedEffect re-launches, and `snapshotFlow` emits its *current* value
    // on the first collection — which is still the *previous* session's scroll
    // position (the listState hasn't moved yet). Writing that stale position to
    // `savedPositions[newSessionId]` clobbered the new session's true history,
    // so parent→sub→parent returned to the sub-session's position instead of
    // the parent's. Two guards fix this:
    //   (1) `.drop(1)` — skip the first emit on each (re)launch, so the stale
    //       position carried over from the previous session is never recorded
    //       against the new sessionId.
    //   (2) `pendingRestoreSession == sessionId` — skip writes while a restore
    //       is in flight. The contentVersion effect performs a programmatic
    //       `scrollToItem` to apply the saved position; those synthetic scroll
    //       events must NOT be recorded as user positions. The effect clears
    //       `pendingRestoreSession` once restore completes, after which real
    //       user scrolls resume being mirrored. Belt-and-suspenders with (1).
    // The "same-session streaming follow-to-bottom" semantic is preserved —
    // real user scrolls in the active session keep updating the cached offset.
    LaunchedEffect(listState, sessionId) {
        if (sessionId == null) return@LaunchedEffect
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
            .drop(1)
            .collect { pos ->
                // Guard (2): restore-in-flight → don't record programmatic scrolls.
                if (pendingRestoreSession == sessionId) return@collect
                savedPositions[sessionId] = pos
                // 🟡 True LRU: move this id to the tail of the access ledger.
                // remove returns false if absent — harmless; either way add()
                // appends a fresh tail entry (a duplicate would corrupt the
                // invariant, so we always remove-then-add).
                accessOrder.remove(sessionId)
                accessOrder.add(sessionId)
                // Evict from the head (oldest) while over capacity. We iterate
                // on accessOrder (index-stable SnapshotStateList), NOT on
                // savedPositions.keys (HashMap order — see class doc above).
                while (savedPositions.size > MAX_SAVED_SESSIONS && accessOrder.isNotEmpty()) {
                    val oldest = accessOrder.removeAt(0)
                    savedPositions.remove(oldest)
                }
            }
    }

    // ── §顶部 session tab 联动显隐 ────────────────────────────────────────
    // 检测 LazyColumn 滚动方向，把"显示/隐藏顶部 tab strip"的意图通过
    // [onTabVisibilityChange] 回调提升到 ChatScreen（消费方在 ChatTopBar 用
    // AnimatedVisibility 包裹 SessionTabStrip）。
    //
    // **reverseLayout 语义**（见 LazyColumn reverseLayout=true）：
    //   - index 0 = 最新消息，渲染在视觉**底部**。
    //   - index 越大 = 越旧的消息，越靠视觉**顶部**。
    //   - 因此 `firstVisibleItemIndex` **增大** = 用户在"向上滚"（看更旧的消息）。
    //   - `firstVisibleItemIndex` **减小** = 用户在"向下滚"（看更新的消息）。
    //
    // 任务约定：向下滚（看更新）→ 隐藏 tab；向上滚（看更旧）→ 显示 tab。
    //
    // **防抖/误判防护**：
    //   1. `delay(300)` —— 会话切换后给 auto-scroll-to-bottom（contentVersion
    //      effect 里的 `animateScrollToItem(0)`）和 saved-position restore 一段
    //      时间完成，避免它们造成的"大跳"被误判为用户手势方向。这段时间内
    //      不采集方向（tab 保持上次状态，默认显示）。
    //   2. `pendingRestoreSession == sessionId` —— 跳过 saved-position restore
    //      期间的程序化滚动（与现有 savedPositions mirror 的同款 guard）。
    //   3. `|delta| > 3` —— 单帧跨越超过 3 个 item 几乎必然是程序化滚动
    //      （用户手势/fling 每帧至多跨 1-2 个 item），忽略。这是对 1 的双保险。
    LaunchedEffect(listState, sessionId) {
        if (sessionId == null) return@LaunchedEffect
        // 等待会话切换后的程序化滚动（auto-scroll / restore）完成。
        delay(300)
        var prevIndex = listState.firstVisibleItemIndex
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                // Guard: saved-position restore 期间的程序化滚动不计入方向判断。
                if (pendingRestoreSession == sessionId) {
                    prevIndex = index
                    return@collect
                }
                val delta = index - prevIndex
                prevIndex = index
                // Guard: 单帧大跳（auto-scroll 残留 / 极端 fling 边界）。
                val absDelta = if (delta < 0) -delta else delta
                if (absDelta > 3) return@collect
                when {
                    delta > 0 -> onTabVisibilityChange(true)   // 向上滚（看更旧）→ show
                    delta < 0 -> onTabVisibilityChange(false)  // 向下滚（看更新）→ hide
                }
            }
    }

    // #3 — on session enter, decide intent: a "return" (saved position exists)
    // queues a restore and disables follow-bottom; a fresh open enables
    // follow-bottom. Only the synchronous state writes happen here; the actual
    // scroll is deferred to the contentVersion effect so it runs against a
    // populated message list (indices are valid), not the transiently-empty
    // list seen mid-load.
    LaunchedEffect(sessionId) {
        val saved = sessionId?.let { savedPositions[it] }
        if (saved != null) {
            pendingRestoreSession = sessionId
            shouldAutoScroll = false
        } else {
            pendingRestoreSession = null
            shouldAutoScroll = true
        }
    }

    LaunchedEffect(contentVersion) {
        // #3 — apply a queued restore once the target session's messages are
        // loaded, then return early so we don't also fire the follow-bottom
        // scroll for this same contentVersion tick.
        val restoreFor = pendingRestoreSession
        if (restoreFor != null && restoreFor == sessionId && messages.isNotEmpty()) {
            val saved = savedPositions[restoreFor]
            if (saved != null) {
                pendingRestoreSession = null
                shouldAutoScroll = false
                // 🟡 True LRU (kimo 9.4): a restore is also an "access" — move
                // this id to the tail so an actively-revisited session is not
                // evicted by a later sibling-session write.
                accessOrder.remove(restoreFor)
                accessOrder.add(restoreFor)
                // Clamp is handled by LazyListState when the index exceeds the
                // current item count (e.g. if new messages shifted the list).
                listState.scrollToItem(saved.first, saved.second)
                return@LaunchedEffect
            } else {
                // 🟡 Defensive (glmer 🟡-1): the saved entry was evicted by an
                // LRU tick between the session-switch (which set
                // pendingRestoreSession) and this effect firing. Clear the
                // pending flag and fall through to the follow-bottom branch so
                // we don't get stuck with shouldAutoScroll=false at whatever
                // residual index the listState happens to be on.
                pendingRestoreSession = null
            }
        }
        // §symptom3-fix: when the user expands the in-progress streaming
        // reasoning card (to read the chain-of-thought from its beginning),
        // pause the bottom-pinning auto-follow. Otherwise the per-token
        // scrollToItem(0) below re-pins the card's bottom every ~100ms,
        // scrolling the card's header / content-start above the viewport — the
        // "expanded thinking card header is truncated, can't see the real
        // start" symptom. Collapsing the card resumes auto-follow.
        val streamingReasoningExpanded = streamingReasoningPart?.let { sr ->
            // §R-1: same key format as the standalone item + inline card.
            val key = sr.messageId?.let { "$it|${sr.id}" } ?: "streaming|${sr.id}"
            expandedParts[key] == true
        } == true
        if (shouldAutoScroll && !streamingReasoningExpanded &&
            (messages.isNotEmpty() || streamingReasoningPart != null)) {
            // 闪屏修复：流式输出（reasoning 进行中 / streamingPartTexts 非空）时
            // 用瞬时 scrollToItem 代替 animateScrollToItem。
            //
            // 根因：contentVersion 含 streamingPartTexts.hashCode()，每个 token 到达
            // 都重启本 LaunchedEffect → animateScrollToItem(0) 启动的 ~300ms 滚动动画
            // 被下一个 token（M5 coalescing ~100ms）打断重启。动画反复中断 + reasoning
            // 内容增长导致 item 高度漂移，使滚动位置在"到底（完全显示）"与"未到底
            // （收缩视图）"间持续跳变 → 用户看到的"窗口在完全显示和收缩间持续变化"。
            //
            // 瞬时 scrollToItem 无动画可堆叠，每帧精确对齐 item 0 顶部，reasoning 顶部
            // 钉住视口底部，新 token 平滑流入，零抖动。这是流式聊天列表（iMessage /
            // Telegram / WhatsApp）跟随到底部的标准实现。
            //
            // 非流式（新消息到达、初始加载，streamingPartTexts 为空且无 reasoning part）
            // 保留 animateScrollToItem 平滑跟随，体验不受影响。
            if (streamingReasoningPart != null || streamingPartTexts.isNotEmpty()) {
                listState.scrollToItem(0)
            } else {
                listState.animateScrollToItem(0)
            }
        }
    }

    // History paging is manual (not scroll-triggered): on session open the
    // latest 5 messages load; a "load more" button at the top (oldest end)
    // fetches 5 older messages per click. This avoids the auto-loadMore loop
    // that occurred when a scroll/nearTop trigger fired on the short initial
    // page, and matches the product decision that most users rarely need deep
    // history. Messages are not persisted — re-fetched fresh on each open.

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        reverseLayout = true,
        // §4.1 v2 spacing: 16dp between turns (each item is one turn = one
        // MessageRow). Combined with MessageRow's own vertical=4dp padding,
        // adjacent turns sit ~24dp apart visually (4 + 16 + 4). Within a turn,
        // per-part cards carry their own 2-4dp vertical padding.
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
    ) {
        if (streamingReasoningPart != null) {
            val streamingKey = streamingReasoningPart.id
            val streamingText = streamingPartTexts[streamingKey] ?: ""
            // §R-1 (maxer): use the SAME expand-key format as the inline
            // ReasoningCard in MessageRow ("${messageId}|${partId}"), so the
            // user's expand state survives the standalone→inline transition
            // when the turn finalizes (streamingReasoningPart clears → the
            // inline card takes over with the same expandedParts entry).
            // Null-guard: messageId is always set here (the part.updated
            // handler returns early when messageID is absent), but defend
            // against a malformed Part regardless.
            val streamingExpandKey = streamingReasoningPart.messageId
                ?.let { "$it|$streamingKey" } ?: "streaming|$streamingKey"
            item(key = "streaming-reasoning") {
                // §card-width: responsive 2/3 width (capped 480dp) for the
                // standalone streaming reasoning card, matching MessageRow's cap.
                BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    val cardMax = minOf(maxWidth * 2f / 3f, 480.dp)
                    ReasoningCard(
                        text = streamingText,
                        title = streamingReasoningPart.toolReason,
                        isStreaming = true,
                        expandedParts = expandedParts,
                        onToggleExpand = onToggleExpand,
                        expandedKey = streamingExpandKey,
                        modifier = Modifier.widthIn(max = cardMax)
                    )
                }
            }
        }
        // §Phase1C gap divider: when a gap is open, split the reversed message
        // list so the divider renders at the seam between the new tail and the
        // older local history. Messages are oldest-first; reversed for the
        // LazyColumn (newest-first). tailOldestId is the oldest message in the
        // fetched tail window — the divider goes AFTER it in oldest-first order,
        // which means BEFORE it in the reversed (display) order.
        // Render-layer empty-message filter: drop assistant messages that have
        // no renderable content (the "blank timestamp-only bubble" defect).
        // This is display-only — the underlying `messages` list / chat state is
        // untouched. A v0.2.5 streaming placeholder (a Part with text=null) is
        // protected: that Part lives in partsByMessage so partsForMessage is
        // non-empty, and the isStreaming guard covers any transient pre-part
        // window. Does NOT affect the standalone streaming-reasoning item
        // above (separate item{} block keyed "streaming-reasoning").
        val reversedMessages = messages.reversed().filterNot { msg ->
            val msgParts = partsByMessage[msg.id].orEmpty()
            // §flicker-fix-D (root cause confirmed empirically w/ deepseek-v4-flash):
            // the server creates the assistant message (message.updated insert)
            // BEFORE emitting its first part (step-start arrives ~1s later).
            // During that "pre-part window" partsByMessage[msg.id] is empty AND
            // no streaming part references this message yet, so the original
            // guards (own-parts-in-overlay / streamingReasoningPart messageId)
            // are both false → isRenderableEmptyMessage dropped the empty
            // assistant row → LazyColumn item vanished → height collapsed →
            // next state emission re-added it → flicker.
            //
            // Earlier iteration used streamingPartTexts.isNotEmpty() as the
            // keep-signal, but two reviewers (maxer R1, glmer S-1) independently
            // proved that is a no-op in the pre-part window: the §user-part-guard
            // removes the user-input-echo part from the overlay, so the overlay
            // is empty exactly during the window we need to cover. The correct
            // signal is the SESSION busy/retry status (set at send time, true
            // through idle) — it spans the whole turn including the pre-part gap.
            //
            // Safety: while the session is running, keep an empty non-user
            // message — it is necessarily the in-progress assistant turn (old
            // assistant messages always retain their parts across reloads; the
            // placeholder-survival merge in MainViewModelMessageActions preserves
            // partsByMessage for non-streaming messages). Once the turn
            // finalizes the status flips idle → sessionIsRunning=false → a
            // genuinely empty assistant message (e.g. a server error) is dropped
            // again, preserving the original "blank timestamp bubble" defect fix.
            val streamingActive = streamingPartTexts.isNotEmpty() || streamingReasoningPart != null
            val isStreamingMsg = msgParts.any { it.id in streamingPartTexts } ||
                streamingReasoningPart?.messageId == msg.id ||
                (!msg.isUser && msgParts.isEmpty() && sessionIsRunning)
            isRenderableEmptyMessage(
                isUser = msg.isUser,
                partsForMessage = msgParts,
                isStreaming = isStreamingMsg
            )
        }
        val showGap = gapInfo != null && gapInfo.open
        val gapInsertIndex = if (showGap) {
            val idx = reversedMessages.indexOfFirst { it.id == gapInfo!!.tailOldestId }
            if (idx >= 0) idx + 1 else -1
        } else -1

        val beforeGap = if (gapInsertIndex > 0) reversedMessages.subList(0, gapInsertIndex) else reversedMessages
        val afterGap = if (gapInsertIndex > 0) reversedMessages.subList(gapInsertIndex, reversedMessages.size) else emptyList()

        items(beforeGap, key = { it.id }) { message ->
            // §s3-markers: synthetic metadata-marker messages render as
            // inline rows (agent / model chip or compaction divider) instead
            // of a full MessageRow.
            if (message.role in METADATA_MARKER_ROLES) {
                MetadataMarkerRow(
                    role = message.role,
                    label = markerLabelFor(message),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                MessageRow(
                    message = message,
                    parts = partsByMessage[message.id].orEmpty(),
                    streamingPartTexts = streamingPartTexts,
                    streamingReasoningPartId = streamingReasoningPart?.id,
                    repository = repository,
                    workspaceDirectory = workspaceDirectory,
                    onFileClick = onFileClick,
                    onOpenSubAgent = onOpenSubAgent,
                    expandedParts = expandedParts,
                    onToggleExpand = onToggleExpand,
                    staleQuestionPartKeys = staleQuestionPartKeys
                )
            }
        }
        if (gapInsertIndex > 0) {
            item(key = "gap-divider") {
                GapDivider(
                    isLoading = isLoading,
                    onClick = onCloseGap,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        items(afterGap, key = { it.id }) { message ->
            if (message.role in METADATA_MARKER_ROLES) {
                MetadataMarkerRow(
                    role = message.role,
                    label = markerLabelFor(message),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                MessageRow(
                    message = message,
                    parts = partsByMessage[message.id].orEmpty(),
                    streamingPartTexts = streamingPartTexts,
                    streamingReasoningPartId = streamingReasoningPart?.id,
                    repository = repository,
                    workspaceDirectory = workspaceDirectory,
                    onFileClick = onFileClick,
                    onOpenSubAgent = onOpenSubAgent,
                    expandedParts = expandedParts,
                    onToggleExpand = onToggleExpand,
                    staleQuestionPartKeys = staleQuestionPartKeys
                )
            }
        }
        if (messages.isNotEmpty() && hasMoreMessages) {
            item(key = "load-more") {
                // Manual history paging: click to fetch 5 older messages.
                // Spinner while a fetch is in flight; otherwise a tappable label.
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Text(
                            text = "加载更多历史",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable(onClick = onLoadMore)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
        if (!isLoading && messages.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No messages yet. Send a message to start.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

/**
 * §Phase1C gap (断层) divider — a clickable divider rendered at the seam
 * between the local message history and the newly-fetched tail window when
 * messages may have arrived during a disconnect. Quiet Tech styling: thin
 * horizontal rule (borderBase) with centered text chip. Tap loads older
 * messages to close the gap; loading state disables interaction and shows
 * a spinner with "加载中…".
 */
@Composable
private fun GapDivider(
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (isPressed) 0.6f else 1f,
        label = "gap-divider-press"
    )

    Box(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .then(
                if (!isLoading) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left rule
            Surface(
                modifier = Modifier.weight(1f).height(1.dp),
                color = MaterialTheme.colorScheme.outline
            ) {}
            // Center chip
            Surface(
                modifier = Modifier.padding(horizontal = 12.dp),
                shape = RoundedCornerShape(6.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = if (isLoading) "加载中…" else "可能有未加载的消息 · 点击加载",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                    )
                }
            }
            // Right rule
            Surface(
                modifier = Modifier.weight(1f).height(1.dp),
                color = MaterialTheme.colorScheme.outline
            ) {}
        }
    }
}

/**
 * 🟡 (glmer 🟡-6) Maximum number of per-session scroll positions retained in
 * `savedPositions` inside [ChatMessageList]. The cache is keyed by sessionId
 * and lives for the lifetime of the ChatMessageList composable; without a cap
 * a user that opens many sub-sessions would accumulate entries forever. When
 * the cap is exceeded the least-recently-used entry is evicted — true LRU is
 * implemented via the parallel `accessOrder` SnapshotStateList ledger (see
 * `savedPositions` declaration doc for why SnapshotStateMap alone cannot do
 * this). 30 is comfortably above typical agent-task fan-out while bounding
 * memory to a few KB.
 */
private const val MAX_SAVED_SESSIONS = 30

/**
 * §s3-markers: derives the human-readable label text for a synthetic
 * metadata-marker [Message] (role ∈ [cn.vectory.ocdroid.ui.METADATA_MARKER_ROLES]).
 *
 * - agent-switched → the new agent name (message.agent). Falls back to the
 *   empty string if `agent` was not set on the marker (defensive — injector
 *   always populates it).
 * - model-switched → the model's id (message.modelId or message.model?.modelId).
 *   The chat list passes this raw; the [MetadataMarkerRow] chip will format
 *   it for display (and could resolve a friendly name via providers in the
 *   future).
 * - compaction       → the marker's pre-formatted body summary, or a generic
 *   localized fallback.
 */
private fun markerLabelFor(message: Message): String = when (message.role) {
    "agent-switched" -> message.agent.orEmpty()
    "model-switched" -> message.modelId ?: message.model?.modelId.orEmpty()
    "compaction" -> message.modelId.orEmpty()
    else -> ""
}
