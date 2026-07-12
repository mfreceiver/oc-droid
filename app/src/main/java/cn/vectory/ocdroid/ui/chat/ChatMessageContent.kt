package cn.vectory.ocdroid.ui.chat

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.cache.contract.GapFillState
import cn.vectory.ocdroid.data.cache.contract.GapMarker
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.isEffectivelyRenderableEmpty
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.ChatViewModel
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.SessionViewModel
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.NavRoute
import cn.vectory.ocdroid.ui.METADATA_MARKER_ROLES
import cn.vectory.ocdroid.ui.currentSessionStatus
import cn.vectory.ocdroid.ui.filterBeforeRevert
import cn.vectory.ocdroid.ui.injectMetadataMarkers
import cn.vectory.ocdroid.ui.isStaleQuestionPart
import cn.vectory.ocdroid.ui.theme.AppTextStyles
import cn.vectory.ocdroid.util.FLICKER_TAG
import cn.vectory.ocdroid.util.STREAMING_FLICKER_DEBUG
import cn.vectory.ocdroid.util.flickerFilterOutCount
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

// ── Chat message list container ──────────────────────────────────────────
// The top-level scrollable list of chat turns. Owns the per-session scroll
// position LRU cache, auto-follow-bottom, history paging, and the §Phase1C
// gap divider. Per-message rendering is delegated to [MessageRow] (in
// ChatMessageRow.kt); all card composables live in their own sibling files.

@Composable
internal fun ChatMessageList(
    chatVM: ChatViewModel,
    composerVM: ComposerViewModel,
    sessionVM: SessionViewModel,
    /**
     * §0.8.2 P2.6: orchestratorVM is needed inside ChatMessageList to
     * collect [OrchestratorViewModel.reselectFlow] (filtered to
     * [NavRoute.Chat]). On each emission the list scrolls to the latest
     * (the Q4 contract's scroll-to-latest half — the pop-to-root-session
     * half is shell-level, owned by AppShell's back stack). Threaded from
     * ChatScaffold (which already has orchestratorVM).
     */
    orchestratorVM: OrchestratorViewModel,
    onFileClick: (String) -> Unit,
    onOpenChanges: (String) -> Unit = {},
    onTabVisibilityChange: (Boolean) -> Unit = {},
    // §3-scroll-memory: hoisted per-session scroll-position cache + its
    // access-order LRU ledger. Lifted out of this composable (previously
    // local `remember{}` blocks) so the HorizontalPager page slot disposing
    // and recreating ChatMessageList on currentSessionId flip no longer
    // drops the cached positions. Owned by ChatScreen; mutated here.
    savedPositions: SnapshotStateMap<String, Pair<Int, Int>>,
    accessOrder: SnapshotStateList<String>,
    // §1C: per-message destructive-action callbacks (Copy / Edit & rerun /
    // Fork). Edit & rerun is the Phase 0 RevertConversation use case's
    // single entry point — must be confirmed by the dialog INSIDE
    // MessageCard before the callback fires. All three callbacks are
    // non-null at call sites (ChatScaffold always supplies them) so we
    // pass them as required parameters rather than optional defaults —
    // the silent no-op default for destructive actions is the kind of
    // bug the §destructive-gate contract explicitly rejects.
    onCopyMessage: (messageId: String, text: String) -> Unit = { _, _ -> },
    onEditAndRerun: (messageId: String) -> Unit = {},
    onFork: (messageId: String) -> Unit = {},
    /**
     * §phase2-parity: narrow projection of composerFlow for the
     * canEditAndRerun destructive gate. ChatScaffold derives this ONCE from
     * its already-subscribed composer slice (`composer.sendingSessionIds
     * .contains(currentSessionId)`) and passes the boolean down. This keeps
     * ChatMessageList OFF composerFlow entirely — every keystroke mutates
     * composerFlow (input text), so a direct subscription here would
     * recompose the whole message list on every key. The §1B/1C parity
     * contract ("typing does not recompose the message list") is restored.
     * The other gate inputs (busy / retry / streamingPartTexts /
     * streamingReasoningPart) are read from chatFlow + sessionListFlow
     * inside this composable — those subscriptions fire on legitimate
     * message/stream events, not keystrokes.
     */
    isCurrentSessionSending: Boolean = false,
) {
    // §R-17 Stage 2: subscribe to chatFlow + sessionListFlow directly so SSE
    // streaming deltas (streamingPartTexts mutation) only recompose this list,
    // and typing (composerFlow) / connection / settings changes do NOT. The
    // messages/streaming/parts params were previously List/Map (unstable in
    // Compose) passed from ChatScreen's AppState read, which forced a
    // recomposition on every AppState emission. Reading from the slice Flows
    // here lets the runtime skip this composable when neither slice emits.
    //
    // §phase2-parity: composerFlow is deliberately NOT subscribed here. The
    // single boolean the gate needs (`isCurrentSessionSending`) is derived
    // once in ChatScaffold and passed as a narrow param. A composerFlow
    // subscription would recompose this list on every keystroke (input text
    // mutation), breaking the §1B/1C "typing does not recompose the list"
    // parity contract.
    val chatState by chatVM.chatFlow.collectAsStateWithLifecycle()
    val sessionListState by chatVM.sessionListFlow.collectAsStateWithLifecycle()
    val expandedParts by chatVM.expandedParts.collectAsStateWithLifecycle()

    // visibleMessages is a cross-slice derived value: the revert message id
    // lives on the Session (sessionListFlow) but the messages list lives on
    // chatFlow. Recompute only when either input changes (remember key).
    val currentSession = sessionListState.sessions.find { it.id == chatState.currentSessionId }
    val currentCutoff = chatState.currentSessionId?.let(chatState.revertCutoffs::get)
    val revertMessageId = if (currentSession != null) currentSession.revert?.messageId else currentCutoff?.messageId
    LaunchedEffect(chatState.currentSessionId, revertMessageId, currentSession?.revert?.messageId) {
        // Start one bounded resolve for a newly-needed cutoff. Failed is terminal
        // until an explicit retry; never spin on every ChatState emission.
        if (revertMessageId != null && currentCutoff?.state !is cn.vectory.ocdroid.data.model.RevertCutoffState.Failed) {
            chatVM.retryRevertCutoff()
        }
    }
    val messages: List<Message> = remember(chatState.messages, revertMessageId, currentCutoff) {
        val reverted = chatState.messages.filterBeforeRevert(revertMessageId, currentCutoff)
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
    // §history-load-fix: the load-more button's spinner binds to THIS flag (the
    // user-initiated loadMore indicator), NOT [isLoading] (the background reload
    // / catch-up indicator). A background reload in flight must NOT flip the
    // load-more button to a spinner — the user can still click it (loadMore now
    // uses its own flag + a session mutex), and only a user-triggered loadMore
    // shows the spinner. Fixes the 0.6.0 "加载历史对话需要多次点击" symptom where the
    // button was untappable during a background reload.
    val isLoadingMore: Boolean = chatState.isLoadingMoreMessages
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
    // §1C: derived once for the canEditAndRerun gate inside the message
    // rendering. Reading the SessionStatus directly avoids going
    // through currentSessionStatus() (which is the same value but
    // allocated per-message) and keeps the per-row gate logic
    // explicit.
    val currentSessionStatus = chatState.currentSessionId?.let { sid ->
        sessionListState.sessionStatuses[sid]
    }
    val hasMoreMessages: Boolean = chatState.hasMoreMessages
    // §F3-load-more: 同时取 cursor——渲染门加 cursor 守卫，任何 cursor 缺失/不一致
    // 都不显示"加载更多"按钮（避免按钮显示但点击因 cursor=null 无反应）。
    val olderMessagesCursor: String? = chatState.olderMessagesCursor
    // R-20 Phase 2: gap markers drive the non-contiguous dividers (replaces the
    // legacy single ChatState.gapInfo). Rendered via messages.withGaps below.
    val gapMarkers: List<GapMarker> = chatState.gapMarkers
    val repository: OpenCodeRepository = chatVM.repository
    val workspaceDirectory: String? = currentSession?.directory
    val onLoadMore: () -> Unit = chatVM::loadMoreMessages
    val onOpenSubAgent: (String) -> Unit = sessionVM::openSubAgent
    val onToggleExpand: (String, Boolean) -> Unit = composerVM::togglePartExpand
    // R-20 Phase 2: per-gap fill trigger (replaces the no-arg onCloseGap). The
    // tapped marker's gapId is routed back to GapFillCoordinator.fillSingleGap.
    val onFillGap: (String) -> Unit = chatVM::fillGap

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
    // §issue-1(1): 当前会话的文件变更快照（来自 SessionListState.sessionDiffs），
    // 驱动聊天内 SessionDiffCard。非空时在 timeline 底部渲染一张可展开卡片。
    val sessionDiff = sessionId?.let { sessionListState.sessionDiffs[it] }
    // §issue-1(1): 打开会话时按需拉取 diff（视图层数据，解耦消息加载路径）。
    // LaunchedEffect(sessionId) 仅在切换会话时运行一次；SSE session.diff 负责后续增量。
    LaunchedEffect(sessionId) {
        if (!sessionId.isNullOrBlank()) sessionVM.loadSessionDiff(sessionId)
    }

    // §flicker-fix (Issue 1): key the LazyListState by sessionId so a fresh
    // state is created on session change. Without the key, the pager reusing
    // a slot for a different session kept the old scroll offset for one frame.
    // Additive to the hoisted per-session LRU: SaveableStateHolder owned by the
    // Chat NavBackStackEntry restores the exact viewport after chat/preview pops.
    val listState = rememberSaveable(sessionId, saver = LazyListState.Saver) { LazyListState() }
    // §B1: followBottom is per-session saveable so it survives Chat→preview→back.
    // A REAL sessionId change re-runs the initializer (default true); a re-entry
    // with the SAME sessionId (e.g. returning from a file preview) restores the
    // saved value (possibly false = the user was reading history). Previously a
    // plain `remember`, which recreated true on every re-entry → returning from
    // a preview yanked a history-reading user back to latest, defeating the
    // saveable LazyListState above. The reselect path + NavFab onJump still
    // explicitly set followBottom=true on a deliberate "go to latest" intent.
    var followBottom by rememberSaveable(sessionId) { mutableStateOf(true) }
    // §navfab-redesign: 单键"跳到最新"按钮的可见性。仅在用户"向新滑动"（从历史
    // 往最新方向滚）时浮现；按一次、到底部、或静置 3s 后隐藏（见下方各 effect）。
    var navFabVisible by remember { mutableStateOf(false) }
    var navFabTick by remember { mutableIntStateOf(0) }
    // §navfab-guard: NavFab 跳到最新动画进行中标志。置位期间方向检测器跳过——避免
    // NavFab 自己的 animateScrollToItem(0)（每帧 delta<0）被误判为"用户向新滑动"
    // 而重新点亮按钮（按下后闪烁/重现）。按下时 onJump 置位、动画 finally 清零。
    var navJumping by remember { mutableStateOf(false) }
    // §3-scroll-memory: per-session scroll-position memory is now HOISTED to
    // ChatScreen (above the HorizontalPager) so the pager disposing this
    // composable on a currentSessionId flip no longer drops the cache. The
    // `savedPositions` map + `accessOrder` LRU ledger are received as params.
    // The mirror effect below continuously records the user's scroll offset
    // against the active sessionId. NOTE (§B1): the restore CONSUMER was
    // removed — savedPositions/accessOrder are currently WRITE-ONLY (kept for
    // the upcoming cross-session restore; cleanup is out of scope here). The
    // saveable LazyListState + saveable followBottom above now carry the
    // preview-return position-preservation contract instead.
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
    // pendingRestoreSession is a legacy guard flag retained because the mirror
    // + direction-detector effects below still key their programmatic-scroll
    // suppression on it. With the restore consumer gone it is effectively
    // always null; the writes/reads here are no-ops but kept to avoid
    // disturbing those guards (cleanup is out of scope for §B1).
    var pendingRestoreSession by remember { mutableStateOf<String?>(null) }
    // This intentionally excludes streaming text values. A token delta changes the
    // overlay map about every 100ms, but it is not a navigation event; using it as
    // a scroll-effect key created a scroll/layout feedback loop.
    val isStreaming = sessionIsRunning || streamingReasoningPart != null || streamingPartTexts.isNotEmpty()

    // §Q4: The former snapshotFlow atBottom tracker that continuously set
    // followBottom is REMOVED. It caused a feedback latch: programmatic
    // scrollToItem(0) → index 0 → snapshotFlow emits atBottom=true →
    // followBottom=true → next contentVersion tick auto-scrolls again.
    // followBottom is updated by the unified bottom-position tracker below
    // (canScrollBackward + index + offset) and the direction detector (tab
    // visibility). Latch-safe via atExactBottom guard.

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
                // §navfab-guard: NavFab 跳转动画期间忽略——避免程序化滚动被误判为手势。
                if (navJumping) {
                    prevIndex = index
                    return@collect
                }
                // Guard: saved-position restore 期间的程序化滚动不计入方向判断。
                if (pendingRestoreSession == sessionId) {
                    prevIndex = index
                    return@collect
                }
                // §glmer-1: skip when list is empty (slow load race — avoid
                // false direction from initial -1→0 index jump).
                if (listState.layoutInfo.totalItemsCount == 0) {
                    prevIndex = index
                    return@collect
                }
                val delta = index - prevIndex
                prevIndex = index
                // Guard: 单帧大跳（auto-scroll 残留 / 极端 fling 边界）。
                val absDelta = if (delta < 0) -delta else delta
                if (absDelta > 3) return@collect
                when {
                    delta > 0 -> {
                        onTabVisibilityChange(true)   // 向上滚（看更旧）→ show
                        followBottom = false           // §Q4: user scrolled away from bottom
                        // §navfab-redesign: 向旧滑动 → 隐藏"跳到最新"（仅向新滑动时浮现）。
                        navFabVisible = false
                    }
                    delta < 0 -> {
                        onTabVisibilityChange(false)   // 向下滚（看更新）→ hide
                        // §Q4: scrolled back to bottom.
                        if (index == 0) {
                            followBottom = true
                            // §navfab-redesign: 到达底部 → 隐藏"跳到最新"按钮（已无目标）。
                            navFabVisible = false
                        } else {
                            // §navfab-redesign: 向新滑动但未到底 → 浮现"跳到最新"按钮。
                            navFabVisible = true
                            navFabTick++
                        }
                    }
                }
            }
    }

    // §Q4-scroll-track: unified bottom-position tracker. Watches
    // canScrollBackward + firstVisibleItemIndex + scrollOffset together so
    // ANY position change triggers an update. Uses atExactBottom guard to
    // distinguish "user genuinely at bottom" from "content grew above but
    // user hasn't moved" (maxer S-1 fix).
    //
    // followBottom = if (canBack) atExactBottom else true:
    //   canBack=false → true (absolute bottom)
    //   canBack=true + atExactBottom → true (at bottom despite content above)
    //   canBack=true + !atExactBottom → false (user scrolled away)
    LaunchedEffect(listState, sessionId) {
        if (sessionId == null) return@LaunchedEffect
        delay(300)
        snapshotFlow {
            Triple(
                listState.canScrollBackward,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
        }
            .drop(1)
            .collect { (canBack, index, offset) ->
                if (pendingRestoreSession == sessionId) return@collect
                if (listState.layoutInfo.totalItemsCount == 0) return@collect
                val atExactBottom = index == 0 && offset <= 24
                followBottom = if (canBack) atExactBottom else true
            }
    }

    // §navfab-redesign: "跳到最新"按钮的可见性由方向检测器驱动（向新滑动时浮现、
    // 到底部隐藏，见上）。此处只保留 3s 静置自动隐藏（navFabTick 在浮现/交互时 ++）。
    LaunchedEffect(navFabTick) {
        if (navFabVisible) {
            delay(3000)
            navFabVisible = false
        }
    }

    // §B1: on session enter we NO LONGER force followBottom=true here. With
    // followBottom now rememberSaveable(sessionId), the per-session default is
    // owned by the saver: a fresh sessionId re-runs the initializer (true), and
    // a re-entry with the SAME sessionId (preview return) restores the saved
    // value (possibly false). Forcing followBottom=true on every composition
    // restart would defeat the saveable LazyListState by yanking a history-
    // reading user back to latest on every preview return. Only the OTHER
    // synchronous resets remain here — they are plain `remember` state, already
    // fresh on re-entry, so re-stating them is harmless. The actual auto-follow
    // scroll is deferred to the contentVersion effect (gated on followBottom).
    LaunchedEffect(sessionId) {
        pendingRestoreSession = null
        // §navfab-redesign: 会话切换隐藏"跳到最新"按钮（新会话从默认跟底状态开始）。
        navFabVisible = false
        // §navfab-guard (gpter 🟡): 防御性重置程序化滚动守卫——兜底任何未预见的
        // onJumpDone 未配对路径，避免 navJumping 卡 true 让方向检测器跨会话失效。
        navJumping = false
    }

    // §0.8.2 P2.6: Chat reselect → scroll to latest. The bottom nav's Chat
    // tab emits NavRoute.Chat on `orchestratorVM.reselectFlow` when the user
    // taps Chat while already on Chat. The Q4 contract for Chat reselect =
    // "close preview + pop to root session + scroll latest". Closing
    // preview + pop-to-root-session are nav-level (AppShell owns the back
    // stack via popBackStack — the shell handles those halves elsewhere);
    // THIS composable only does the SCROLL-to-latest half. On each emission
    // we set followBottom=true (so subsequent content-version ticks stick
    // to the bottom) and jump the list to item 0 (the latest, since the
    // LazyColumn is reverseLayout). Use instantaneous scrollToItem (not
    // animateScrollToItem) so the reselect feedback is immediate — the
    // animateScroll path is reserved for content-version driven follow.
    LaunchedEffect(orchestratorVM) {
        orchestratorVM.reselectFlow
            .filter { it == NavRoute.Chat }
            .collect {
                followBottom = true
                navFabVisible = false
                if (listState.layoutInfo.totalItemsCount > 0) {
                    listState.scrollToItem(0)
                }
            }
    }

    // Scroll only for stable structural events: session/message-count changes,
    // streaming lifecycle transitions, or a newly introduced streaming part.
    // `streamingPartTexts.keys` has set equality, so text-only token deltas do
    // not restart this effect. In reverse layout, a growing index-0 item remains
    // bottom-anchored; requesting another scroll for every height change is both
    // unnecessary and the source of the previous flicker.
    LaunchedEffect(sessionId, messages.size, isStreaming, streamingPartTexts.keys, streamingReasoningPart?.id) {
        val streamingReasoningExpanded = streamingReasoningPart?.let { sr ->
            val key = sr.messageId?.let { "$it|${sr.id}" } ?: "streaming|${sr.id}"
            expandedParts[key] == true
        } == true
        if (followBottom && !streamingReasoningExpanded &&
            (messages.isNotEmpty() || streamingReasoningPart != null)) {
            if (isStreaming) {
                // Do not pull a history-reading user back when a new stream part
                // arrives. requestScrollToItem schedules a one-shot measure-pass
                // reposition rather than launching an animated scroll per token.
                val atBottom = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset <= 24
                if (atBottom) listState.requestScrollToItem(0)
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

    // 消息结构计算提到 LazyColumn body 之外（gap 分割需要 Entry 列表）。
    //
    // remember 覆盖 messages/partsByMessage/streamingReasoningPart/sessionIsRunning
    // 不变的情况；但 streamingPartTexts 在 SSE 流式时每 ~100ms 变化（SessionSync
    // 每次 emit 新 Map），仍会触发 reversedEntries 重建。这是预期的——重建是 O(n)
    // 单遍 filterNot，100 条消息下 ~10-20μs，远低于 LazyColumn item 渲染成本，可
    // 忽略（评审 maxer/gpter 实测确认）。
    // R-20 Phase 2: build the non-contiguous Entry list (Message + GapMarker)
    // from the filtered oldest-first [messages] + the session's open
    // [gapMarkers], then reverse + filter for the reverseLayout display. A gap
    // divider is interleaved at each marker's upperBoundary seam (≥1 gap). The
    // same streaming/empty filter that applies to plain messages is applied to
    // Message entries here; GapMarker entries are always kept (a divider whose
    // boundary message got filtered is dropped by withGaps upstream).
    val reversedEntries = remember(messages, gapMarkers, partsByMessage, streamingPartTexts, streamingReasoningPart, sessionIsRunning) {
        val entries = messages.withGaps(gapMarkers)
        entries.reversed().filterNot { entry ->
            (entry is Entry.Message) && run {
                val msg = entry.message
                val msgParts = partsByMessage[msg.id].orEmpty()
                // §streaming-flicker-diagnosis §3.1 confirm experiment: when the
                // debug gate is on, ALSO treat a session-running message that
                // carries a text Part as streaming — this covers the placeholder
                // window (text=null Part in partsByMessage but partId not yet in
                // streamingPartTexts). If flicker vanishes with this on, Top1 is
                // confirmed. sessionIsRunning + isText are both in scope here.
                // Disabled (no-op) when STREAMING_FLICKER_DEBUG=false.
                val isStreamingMsg = msgParts.any { it.id in streamingPartTexts } ||
                    streamingReasoningPart?.messageId == msg.id ||
                    (!msg.isUser && msgParts.isEmpty() && sessionIsRunning) ||
                    (STREAMING_FLICKER_DEBUG && sessionIsRunning && msgParts.any { it.isText })
                // §empty-msg / §error-feedback: same filter as the legacy
                // reversedMessages (kept verbatim so rendering is byte-identical
                // for the non-gap path).
                val renderableEmpty = isEffectivelyRenderableEmpty(msgParts)
                val filteredOut = !msg.isUser && !isStreamingMsg &&
                    msg.error?.message.isNullOrBlank() &&
                    renderableEmpty
                // §streaming-flicker-diagnosis (Top1): a non-user, non-streaming
                // message dropped here is the exact blank-frame path — the
                // placeholder intermediate state makes isStreamingMsg=false AND
                // renderableEmpty=true. If this fires ~1Hz during streaming, the
                // Top1 root cause is confirmed. filterOutCount is the cumulative
                // tally (AtomicLong); pair with the SessionSyncCoordinator
                // "placeholder created" / "first delta staged" logs to time the
                // two-phase window.
                if (filteredOut && STREAMING_FLICKER_DEBUG) {
                    val count = flickerFilterOutCount.incrementAndGet()
                    Log.w(
                        FLICKER_TAG,
                        "FILTERED OUT msgId=${msg.id} isStreamingMsg=$isStreamingMsg " +
                            "renderableEmpty=$renderableEmpty " +
                            "hasError=${!msg.error?.message.isNullOrBlank()} filterOutCount=$count"
                    )
                }
                filteredOut
            }
        }
    }
    // Build folds in chronological order so the anchor is always the earliest
    // part, then reverse the resulting blocks for reverseLayout's item order.
    val renderBlocks = remember(
        reversedEntries,
        partsByMessage,
        streamingPartTexts,
        staleQuestionPartKeys,
        streamingReasoningPart,
        sessionIsRunning
    ) {
        buildRenderBlocks(
            entries = reversedEntries.asReversed(),
            partsByMessage = partsByMessage,
            streamingPartTexts = streamingPartTexts,
            staleQuestionPartKeys = staleQuestionPartKeys,
            streamingReasoningPartId = streamingReasoningPart?.id,
            sessionIsRunning = sessionIsRunning
        ).asReversed()
    }

    // §Phase8-nav: Box 包裹 LazyColumn + 导航 FAB overlay（右侧中下）。
    Box(modifier = Modifier.fillMaxSize()) {
        // §watermark-B5: 大字号水平水印（去旋转），stamped BEHIND the message
        // list — replaces the old top-bar workdir-initial icon (see ChatTopBar)
        // AND the prior 45°-tilted displaySmall(24sp) watermark. Renders the
        // workdir basename (last path segment) as a single bold HORIZONTAL
        // line, centered, tinted with [workdirTone] so each project carries
        // its identity hue. The very low alpha keeps it discernible but never
        // competitive with message text in either light or dark theme.
        // Purely decorative — NON-INTERACTIVE (no clickable / pointerInput /
        // hover). Listed as the FIRST child so it sits beneath the LazyColumn
        // in z-order.
        //
        // §watermark-autosize: 字号在 **48sp → 32sp** 区间自适应缩放，单行
        // 不换行、不溢出（换行会大面积遮挡消息列表）。算法：
        //   1. [BoxWithConstraints] 取容器 **88%** 宽（留边距，不全宽——全宽
        //      水印会贴到屏幕边缘，视觉拥挤）。
        //   2. 文本长度按 CJK 双宽估（`char.code > 0x2E80` 计 2，覆盖 CJK
        //      Radicals / CJK Unified / Hiragana / Katakana / Hangul 等双宽
        //      区块；Latin / 数字 / 标点计 1）。
        //   3. 字号 = 可用宽(px) / (长度 × 字宽因子 0.62)，clamp 到 [32.sp,
        //      48.sp]。因子 0.62 取 ExtraBold 平均字宽上界（实测 ExtraBold
        //      Latin 字宽 ≈ 0.55–0.60 em），取上界偏保守以保证单行不溢出。
        //      极端超长项目名（>32 单宽字符）触发 TextOverflow.Ellipsis 兜底
        //      而非换行/溢出。
        // alpha **0.07f**（较旧 0.05f 略升）：水平版可视面积较 45° 旋转版
        // 小（旧版对角线拉长视感、字宽更突出），适度提亮维持同等存在感而不
        // 干扰阅读。style 走 [AppTextStyles.watermark]（48sp/ExtraBold/56lh），
        // 调用方按上策略 `.copy(fontSize = …)` 覆盖字号；color/fontFamily 不
        // 写死（前者由调用方覆盖为 workdirTone + alpha，后者继承平台字体）。
        // `workspaceDirectory` 派生自当前 session.directory（见上声明）；
        // null/blank 时不渲染。
        workspaceDirectory?.let { dir ->
            val workdirBasename = dir.substringAfterLast('/').ifBlank { dir }
            if (workdirBasename.isNotBlank()) {
                BoxWithConstraints(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.88f)
                ) {
                    val density = LocalDensity.current
                    val availWidthPx = with(density) { maxWidth.toPx() }
                    // CJK 双宽启发式：code > 0x2E80 覆盖 CJK Radicals / CJK
                    // Unified / Hiragana / Katakana / Hangul 等双宽字符。
                    val textLen = workdirBasename.sumOf { if (it.code > 0x2E80) 2 else 1 }
                        .coerceAtLeast(1)
                    val rawSizePx = availWidthPx / (textLen * 0.62f)
                    // 在 px 域 clamp 到 [32.sp, 48.sp] 对应的 px 区间，再转回 sp。
                    // 避开 TextUnit 上 coerceIn 的解析陷阱（直接 .toSp().coerceIn
                    // 在某些 Kotlin/Compose 版本下候选不解析）。
                    val minPx = with(density) { 32.sp.toPx() }
                    val maxPx = with(density) { 48.sp.toPx() }
                    val fontSize = with(density) {
                        rawSizePx.coerceIn(minPx, maxPx).toSp()
                    }
                    Text(
                        text = workdirBasename,
                        modifier = Modifier.align(Alignment.Center),
                        color = workdirTone(dir).copy(alpha = 0.07f),
                        style = AppTextStyles.watermark.copy(fontSize = fontSize),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        reverseLayout = true,
        verticalArrangement = Arrangement.Top,
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
        // §issue-1(1): 会话文件变更卡片。reverseLayout 下 item 顺序靠前 = 视觉靠下，
        // 故放在消息块之前 → 渲染在对话底部（最新内容之后）。仅当本会话有 diff 时出现。
        // sessionId 传入用于 rememberSaveable key 维度（防跨会话串读，maxer B1）。
        // §review-3: sessionDiff 派生自 sessionId?.let{...}（见上声明），故其非空时
        // sessionId 必非空——原 && sessionId != null 恒真（编译器告警）。去掉后
        // sessionId: String? 无法仅凭 sessionDiff 非空 smart-cast，故用 ?.let 把非空
        // 性线程化（行为不变，不用 !!）。
        if (!sessionDiff.isNullOrEmpty()) {
            sessionId?.let { diffSessionId ->
                item(key = "session-diff") {
                    SessionDiffCard(
                        sessionId = diffSessionId,
                        diffs = sessionDiff,
                        onOpenChanges = onOpenChanges,
                    )
                }
            }
        }
        // §Phase8-nav: renderBlocks 已提到 LazyColumn 外；每个跨消息 fold 是一个
        // LazyColumn item，key 由最早 part id 锚定。
        // （供消息导航 FAB 复用）。R-20 Phase 2：gap divider 由 Entry.GapMarker 渲染，
        // 一个 items() 块统一处理消息行 + 分割线（替代旧的 beforeGap/gap-divider/afterGap
        // 三段式，支持 ≥1 gap）。
        itemsIndexed(renderBlocks, key = { _, block -> block.id }) { index, block ->
            Box(modifier = Modifier.fillMaxWidth().padding(top = renderBlockTopPaddingDp(block, index).dp)) {
            when (block) {
                is RenderBlock.Conversation -> {
                    val message = block.message
                    // §s3-markers: synthetic metadata-marker messages render as
                    // inline rows (agent / model chip or compaction divider).
                    if (message.role in METADATA_MARKER_ROLES) {
                        MetadataMarkerRow(
                            role = message.role,
                            label = markerLabelFor(message),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        // §empty-msg: in-flight empty assistant shell render a
                        // lightweight loading row; full MessageRow otherwise.
                        val msgParts = block.parts
                        val isInFlightEmpty = shouldRenderInFlightEmpty(block, sessionIsRunning)
                        if (isInFlightEmpty) {
                            InFlightEmptyLoading()
                        } else {
                            // §1C: wrap MessageRow in MessageCard so the
                            // per-row overflow menu (Copy / Edit & rerun /
                            // Fork) + the destructive confirmation dialog
                            // have a mounting point. The card body is the
                            // existing MessageRow (verbatim — no part-level
                            // rendering change), and the menu's destructive
                            // action is gated on its own confirmation dialog
                            // (see MessageCard.kt's class doc for the
                            // destructive-gate contract).
                            //
                            // §1C-FIX-②/④: build the gate-input
                            // snapshot from the canonical slices and
                            // run the PURE [canEditAndRerun] predicate.
                            // The predicate is the SOLE authority for
                            // the enablement boolean — the test in
                            // [MessageCardDestructiveGateTest] calls
                            // it directly (no duplication of logic).
                            //
                            // Conditions (mirroring
                            // RevertConversation.execute:23-26, the
                            // use case's own intercept set):
                            //   - isUser                  : assistant /
                            //                              system rows
                            //                              are not valid
                            //                              revert pivots
                            //                              (use case
                            //                              rejects them)
                            //   - !sessionIsBusy         : SessionStatus
                            //                              isBusy (server
                            //                              producing)
                            //   - !sessionIsRetry        : SessionStatus
                            //                              isRetry (failed
                            //                              run, backoff)
                            //   - !isSending             : composerFlow
                            //                              sendingSessionIds
                            //                              contains
                            //                              currentSessionId
                            //                              (send-ACK
                            //                              window).
                            //                              §phase2-parity:
                            //                              derived ONCE in
                            //                              ChatScaffold (off
                            //                              composerFlow) and
                            //                              passed as the narrow
                            //                              isCurrentSessionSending
                            //                              param — this list no
                            //                              longer subscribes to
                            //                              composerFlow, so
                            //                              typing does not
                            //                              recompose it.
                            //   - !hasStreamingText      : chatFlow
                            //                              streamingPartTexts
                            //                              non-empty
                            //                              (SSE text
                            //                              deltas in
                            //                              flight)
                            //   - !hasStreamingReasoning : chatFlow
                            //                              streamingReasoningPart
                            //                              != null
                            val isEditAndRerunEnabled = canEditAndRerun(
                                DestructiveGateInputs(
                                    isUser = message.isUser,
                                    sessionIsBusy = currentSessionStatus?.isBusy == true,
                                    sessionIsRetry = currentSessionStatus?.isRetry == true,
                                    isSending = isCurrentSessionSending,
                                    hasStreamingText = streamingPartTexts.isNotEmpty(),
                                    hasStreamingReasoning = streamingReasoningPart != null,
                                )
                            )
                            MessageCard(
                                message = message,
                                parts = msgParts,
                                streamingPartTexts = streamingPartTexts,
                                streamingReasoningPartId = streamingReasoningPart?.id,
                                repository = repository,
                                workspaceDirectory = workspaceDirectory,
                                onFileClick = onFileClick,
                                onOpenSubAgent = onOpenSubAgent,
                                expandedParts = expandedParts,
                                onToggleExpand = onToggleExpand,
                                staleQuestionPartKeys = staleQuestionPartKeys,
                                showMessageDecoration = block.showMessageDecoration,
                                // Copy + Fork are always offered (Fork is
                                // non-destructive). Edit & rerun is gated
                                // by canEditAndRerun above. Future gates
                                // (offline / permission) can land on
                                // canCopy / canFork without churning this
                                // signature.
                                canCopy = true,
                                canEditAndRerun = isEditAndRerunEnabled,
                                canFork = true,
                                onCopy = { text -> onCopyMessage(message.id, text) },
                                onEditAndRerun = onEditAndRerun,
                                onFork = onFork,
                            )
                        }
                    }
                }
                is RenderBlock.ToolRun -> {
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        val cardMax = minOf(maxWidth * 2f / 3f, 480.dp)
                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                            block.items.forEach { contextual ->
                                androidx.compose.runtime.key(
                                    "${contextual.message.id}|${stableItemId(contextual.item, contextual.message.id)}"
                                ) {
                                    renderToolItem(
                                        item = contextual.item,
                                        message = contextual.message,
                                        expandedParts = expandedParts,
                                        onToggleExpand = onToggleExpand,
                                        onFileClick = onFileClick,
                                        onOpenSubAgent = onOpenSubAgent,
                                        staleQuestionPartKeys = staleQuestionPartKeys,
                                        cardMax = cardMax
                                    )
                                }
                            }
                            block.messageDecorations.forEach { message ->
                                MessageDecoration(message = message, cardMax = cardMax)
                            }
                        }
                    }
                }
                is RenderBlock.Fold -> {
                    val folded = block.asFoldedToolRun()
                    val foldKey = foldKey(block.firstPartId)
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        val cardMax = minOf(maxWidth * 2f / 3f, 480.dp)
                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                            if (isCrossMessageFoldExpanded(folded, expandedParts)) {
                                block.items.forEach { contextual ->
                                    androidx.compose.runtime.key(
                                        "${contextual.message.id}|${stableItemId(contextual.item, contextual.message.id)}"
                                    ) {
                                        renderToolItem(
                                            item = contextual.item,
                                            message = contextual.message,
                                            expandedParts = expandedParts,
                                            onToggleExpand = onToggleExpand,
                                            onFileClick = onFileClick,
                                            onOpenSubAgent = onOpenSubAgent,
                                            staleQuestionPartKeys = staleQuestionPartKeys,
                                            cardMax = cardMax
                                        )
                                    }
                                }
                            } else {
                                ToolCallFoldBar(
                                    counts = folded.foldCounts(),
                                    isRunning = foldIsRunning(folded),
                                    onToggleExpand = { onToggleExpand(foldKey, false) },
                                    modifier = Modifier.widthIn(max = cardMax)
                                )
                            }
                            FoldMessageDecoration(messages = block.messageDecorations, cardMax = cardMax)
                        }
                    }
                }
                is RenderBlock.Gap -> {
                    // R-20 Phase 2: the gap divider. Tapping routes the gap's
                    // id back to GapFillCoordinator.fillSingleGap (50-step
                    // backward fill). Exhausted gaps render a non-tappable
                    // "无法补齐" hint (Filling shows the spinner).
                    GapDivider(
                        isLoading = block.marker.fillState == GapFillState.Filling,
                        exhausted = block.marker.fillState == GapFillState.Exhausted,
                        onClick = { onFillGap(block.marker.gapId) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            }
        }
        // §F3-load-more: 渲染门加 olderMessagesCursor 守卫——hasMore 与 cursor 必须
        // 同时满足才显示按钮，杜绝 hasMore=true ∧ cursor=null 的死按钮状态。
        if (messages.isNotEmpty() && hasMoreMessages && olderMessagesCursor != null) {
            item(key = "load-more") {
                // Manual history paging: click to fetch 5 older messages.
                // Spinner while a fetch is in flight; otherwise a tappable label.
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoadingMore) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Text(
                            text = stringResource(R.string.chat_load_more_history),
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
        // §cold-load: 首次加载期间显示 spinner + "加载中…"
        if (isLoading && messages.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.chat_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        // §empty: 加载完成但无消息
        if (!isLoading && messages.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.chat_no_messages),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
        // §navfab-redesign: 单键"跳到最新"FAB（右侧下；键盘打开时自隐）。可见性
        // 由方向检测器驱动（向新滑动浮现、到底部/3s/按下后隐藏）。onJump 按下即
        // 同步隐藏（navFabVisible=false）+ 置 navJumping 守卫 + followBottom=true；
        // onJumpDone 在动画 finally 清除守卫。
        ChatMessageNavFab(
            listState = listState,
            visible = navFabVisible,
            onJump = {
                navFabVisible = false
                navJumping = true
                followBottom = true
            },
            onJumpDone = { navJumping = false },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp),
        )
    }
}

internal fun shouldRenderInFlightEmpty(
    block: RenderBlock.Conversation,
    sessionIsRunning: Boolean
): Boolean = !block.message.isUser && block.parts.isEmpty() && sessionIsRunning &&
    block.message.error?.message.isNullOrBlank() && !block.isDecorationOnly

/**
 * §empty-msg: lightweight inline loading row rendered for an assistant message
 * shell that has arrived (message.updated) but whose first part has not —
 * `partsByMessage[id]` is empty and the session is still busy. Replaces the
 * bare timestamp bubble the prior logic rendered (which looked like an empty
 * reply). NOT rendered for completed messages whose parts are all blank —
 * those are filtered out of [ChatMessageList]'s `reversedMessages` entirely
 * by [isEffectivelyRenderableEmpty]. Padding mirrors MessageRow's
 * horizontal=16dp / vertical=4dp so the loading row paces with surrounding
 * turns; "生成中…" uses labelSmall + onSurfaceVariant for a quiet affordance.
 */
@Composable
private fun InFlightEmptyLoading(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.chat_generating),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
    modifier: Modifier = Modifier,
    /** R-20 Phase 2: when true the gap is Exhausted (history ended below it,
     *  cannot be bridged). The chip renders a non-tappable "无法补齐"-style
     *  hint instead of the tappable load hint. */
    exhausted: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (isPressed) 0.6f else 1f,
        label = "gap-divider-press"
    )
    // Tappable only when idle (not loading, not exhausted).
    val tappable = !isLoading && !exhausted

    Box(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .then(
                if (tappable) {
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
                shape = MaterialTheme.shapes.small,
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
                        text = when {
                            isLoading -> stringResource(R.string.chat_loading)
                            exhausted -> stringResource(R.string.chat_gap_divider_exhausted)
                            else -> stringResource(R.string.chat_gap_divider_hint)
                        },
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

// §R-19 Sprint 2 #7(b): markerLabelFor was lifted verbatim into the top-level
// pure-functions file ChatFormatHelpers.kt (same package) so it can be covered
// by JVM unit tests (this file is excluded from kover coverage as a
// @Composable-heavy UI file — see PickerProviderFilter.kt for the same
// extraction pattern).
