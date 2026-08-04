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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.isEffectivelyRenderableEmpty
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.ChatViewModel
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.LoadedContent
import cn.vectory.ocdroid.ui.SessionViewModel
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.NavRoute
import cn.vectory.ocdroid.ui.METADATA_MARKER_ROLES
import cn.vectory.ocdroid.ui.ScrollBehavior
import cn.vectory.ocdroid.ui.currentSessionStatus
import cn.vectory.ocdroid.ui.filterBeforeRevert
import cn.vectory.ocdroid.ui.injectMetadataMarkers
import cn.vectory.ocdroid.ui.isStaleQuestionPart
import cn.vectory.ocdroid.ui.isInterruptedQuestionPart
import cn.vectory.ocdroid.ui.isStaleRunningPart
import cn.vectory.ocdroid.ui.theme.AppTextStyles
import cn.vectory.ocdroid.ui.theme.CardWidthScope
import cn.vectory.ocdroid.ui.theme.Dimens
import cn.vectory.ocdroid.util.FLICKER_TAG
import cn.vectory.ocdroid.util.STREAMING_FLICKER_DEBUG
import cn.vectory.ocdroid.util.flickerFilterOutCount
import cn.vectory.ocdroid.util.workdirBasename
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

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
    // §3-scroll-memory: hoisted per-session scroll-position cache + its
    // access-order LRU ledger. Lifted out of this composable (previously
    // local `remember{}` blocks) so the HorizontalPager page slot disposing
    // and recreating ChatMessageList on currentSessionId flip no longer
    // drops the cached positions. Owned by ChatScaffold; mutated here.
    //
    // §review-D (gpter #3) — WRITE-ONLY / coverage scope: the restore
    // CONSUMER was removed (§B1), so this map + ledger are currently WRITE-
    // ONLY (the mirror effect below records offsets; nothing reads them
    // back). They are retained for a future cross-session restore consumer.
    // The ACTUAL scroll-preservation guarantees today are carried by
    // `rememberSaveable(sessionId, LazyListState.Saver)` + saveable
    // followBottom below, which reliably preserve scroll for:
    //   (a) Sessions-page entry → pendingScrollRequest forces a jump-to-latest
    //       (NOT a restore — see the LaunchedEffect below).
    //   (b) HorizontalPager swipe + SessionTabStrip tap for ROOT sessions in
    //       the pager page set (stable pager `key = session.id` keeps each
    //       page's SaveableStateHolder slot — and thus its saveable
    //       LazyListState — alive across page-slot reuse). §B6: both pager
    //       and tab strip deleted; this comment kept as historical context.
    //   (c) Chat→file-preview→back re-entry with the SAME sessionId (the
    //       Chat NavBackStackEntry's SaveableStateHolder restores the prior
    //       viewport).
    // Best-effort / NOT reliably covered: sheet-select of a non-paged
    // session, root↔sub-agent switches (sub-agents bypass the pager),
    // post-fork re-entry, and any programmatic select outside the current
    // pager page set — those transitions re-create the saveable state from
    // the default initializer (no cross-session restore consumer exists).
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
    /** Route-aware payload. When present, transcript data comes only from it. */
    routeSessionId: String? = null,
    routeContent: LoadedContent? = null,
    /**
     * §chat-list-detail §11 / G6 (B5): the post-capture callback for opening
     * a sub-agent. ChatMessageList's local `onOpenSubAgent` lambda captures
     * the live [LazyListState] viewport synchronously (oracle ruling — the
     * async mirror cannot be trusted), builds a [ScrollCheckpoint], then
     * delegates to this callback. The callback (owned by ChatScaffold) writes
     * the checkpoint to the parent route entry's SavedStateHandle and triggers
     * route-aware navigation to the child.
     *
     * The split keeps the synchronous capture local (the listState lives
     * here) while the per-entry storage + navigation wiring lives at the
     * ChatScaffold level (where SavedStateHandle + orchestratorVM are
     * available).
     */
    onOpenSubAgentNavigate: (childSessionId: String, checkpoint: cn.vectory.ocdroid.ui.ScrollCheckpoint) -> Unit = { _, _ -> },
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
    val effectiveSessionId = routeSessionId ?: chatState.currentSessionId
    val currentSession = (sessionListState.sessions + sessionListState.directorySessions.values.flatten() + sessionListState.childSessions.values.flatten())
        .firstOrNull { it.id == effectiveSessionId }
    val currentCutoff = effectiveSessionId?.let(chatState.revertCutoffs::get)
    val revertMessageId = if (currentSession != null) currentSession.revert?.messageId else currentCutoff?.messageId
    LaunchedEffect(chatState.currentSessionId, revertMessageId, currentSession?.revert?.messageId) {
        // Start one bounded resolve for a newly-needed cutoff. Failed is terminal
        // until an explicit retry; never spin on every ChatState emission.
        if (revertMessageId != null && currentCutoff?.state !is cn.vectory.ocdroid.data.model.RevertCutoffState.Failed) {
            chatVM.retryRevertCutoff()
        }
    }
    // A parameterized route has one transcript authority.  Do not use Elvis
    // fallbacks here: nullable route-owned fields (reasoning part and cursor)
    // must be allowed to be null without resurrecting stale flat values.
    val loadedMessages = if (routeContent != null) routeContent.messages else chatState.messages
    val messages: List<Message> = remember(loadedMessages, revertMessageId, currentCutoff) {
        val reverted = loadedMessages.filterBeforeRevert(revertMessageId, currentCutoff)
        // §s3-markers: keep user/assistant turns + the synthetic metadata
        // marker roles, then interleave markers wherever agent/model
        // changed between consecutive turns.
        val visible = reverted.filter { !it.isToolRole || it.role in METADATA_MARKER_ROLES }
        injectMetadataMarkers(visible)
    }
    val partsByMessage: Map<String, List<Part>> =
        if (routeContent != null) routeContent.partsByMessage else chatState.partsByMessage
    val streamingPartTexts: Map<String, String> =
        if (routeContent != null) routeContent.streamingPartTexts else chatState.streamingPartTexts
    val streamingReasoningPart: Part? =
        if (routeContent != null) routeContent.streamingReasoningPart else chatState.streamingReasoningPart
    // §ui-stream A1: hoist the chat-wide "is any text streaming" boolean OUT
    // of the itemsIndexed row content lambda. The canEditAndRerun destructive
    // gate (in the Conversation branch below) previously read
    // `streamingPartTexts.isNotEmpty()` inline, which captured the whole
    // (unstable, fresh-reference-every-token) Map into the row lambda and
    // invalidated EVERY visible row per token. A Boolean is stable: during
    // active streaming it stays `true` across token deltas (no invalidation),
    // flipping only at stream start/stop (one recomposition each).
    val chatHasStreamingText: Boolean = streamingPartTexts.isNotEmpty()
    // §slimapi-client-v1 §G6 (Task 16): per-part expand state for the
    // "展开省略内容" affordance. Read from the same chat slice as
    // streamingPartTexts; passed through to MessageCard → MessageRow.
    val partExpandStates: Map<PartKey, PartExpandState> = chatState.partExpandStates
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
    val sessionIsRunning = effectiveSessionId?.let { id ->
        sessionListState.sessionStatuses[id]?.let { it.isBusy || it.isRetry }
    } == true
    // §1C: derived once for the canEditAndRerun gate inside the message
    // rendering. Reading the SessionStatus directly avoids going
    // through currentSessionStatus() (which is the same value but
    // allocated per-message) and keeps the per-row gate logic
    // explicit.
    val currentSessionStatus = effectiveSessionId?.let { sid ->
        sessionListState.sessionStatuses[sid]
    }
    val hasMoreMessages: Boolean =
        if (routeContent != null) routeContent.hasMoreMessages else chatState.hasMoreMessages
    // §F3-load-more: 同时取 cursor——渲染门加 cursor 守卫，任何 cursor 缺失/不一致
    // 都不显示"加载更多"按钮（避免按钮显示但点击因 cursor=null 无反应）。
    val olderMessagesCursor: String? =
        if (routeContent != null) routeContent.olderMessagesCursor else chatState.olderMessagesCursor
    val repository: OpenCodeRepository = chatVM.repository
    val workspaceDirectory: String? = currentSession?.directory
    val onLoadMore: () -> Unit = chatVM::loadMoreMessages
    // §Wave5b-Q13: onOpenSubAgent is declared AFTER [listState] below — it
    // captures listState synchronously to build the parent's ScrollCheckpoint
    // at click time. The forward-reference is illegal in Kotlin, so the val
    // moved (see declaration ~30 lines down).
    val onToggleExpand: (String, Boolean) -> Unit = composerVM::togglePartExpand

    // §stale-question: compute the set of part ids that are stuck "running"
    // question parts WITHOUT a matching live QuestionRequest — these render
    // in a terminal "Interrupted" state instead of a perpetual spinner.
    // §need-8 (rev-2 fix): unified entry isStaleRunningPart — handles both:
    //   - thin-placeholder → session idle 即 stale（isStaleRunningPart 直判）；
    //   - question part → 三重 AND（isStaleQuestionPart ∧ idle ∧ grace elapsed）。
    // Part 无时间戳，question 的 running-since 在 UI 层记录；thin-placeholder
    // 不需宽限故不记录。Map 每轮用 retainAll 清理（消失的 part / 不再候选的 part），
    // 防止条目残留导致永久 tick。Tick 仅当 session idle 且有未过期候选时启动。
    val pendingQuestions = sessionListState.pendingQuestions
    val questionRunningSince = remember { mutableStateMapOf<String, Long>() }
    val questionGraceTick = remember { mutableIntStateOf(0) }
    val staleQuestionPartKeys: Set<String> =
        remember(partsByMessage, pendingQuestions, currentSessionStatus, questionGraceTick.intValue) {
            val now = System.currentTimeMillis()
            // 1. 收集当前 live 的 question 候选（running + 无 pending 匹配）
            val liveQuestionCandidates = HashSet<String>()
            val keys = HashSet<String>()
            for (part in partsByMessage.values.flatten()) {
                if (isStaleQuestionPart(part, pendingQuestions)) {
                    liveQuestionCandidates.add(part.id)
                    questionRunningSince.putIfAbsent(part.id, now)
                }
                // 统一入口 isStaleRunningPart：
                //   thin-placeholder → session idle 即 stale（无宽限）；
                //   question → 三重 AND（stale ∧ idle ∧ grace elapsed）。
                if (isStaleRunningPart(
                        part, pendingQuestions, currentSessionStatus,
                        now, questionRunningSince[part.id]
                    )) {
                    keys.add(part.id)
                }
            }
            // 2. 清理 map：仅保留当前 live 的 question 候选。
            //    消除两类泄漏：
            //   (a) part 消失（会话切换/消息删除）→ 已不在 partsByMessage；
            //   (b) 不再候选（已匹配 pending / 离开 running）。
            //    防止条目残留 → hasPendingGraceCandidates 永远为 true → 永久 tick
            questionRunningSince.keys.retainAll(liveQuestionCandidates)
            keys
        }
    // Periodic tick: 仅当 session 可中断（idle）且存在"已记录但尚未过期"的候选时启动。
    // 非 idle（busy/retry/null）永不中断 → 无需 tick。
    val sessionCanInterrupt = currentSessionStatus?.isIdle == true
    val hasPendingGraceCandidates = sessionCanInterrupt &&
        questionRunningSince.isNotEmpty() &&
        staleQuestionPartKeys.size < questionRunningSince.size
    LaunchedEffect(hasPendingGraceCandidates) {
        while (isActive && hasPendingGraceCandidates) {
            delay(1_000)
            questionGraceTick.intValue++
        }
    }

    // sessionId 在 remember key 里需要——提前取（下面 savedPositions 等也用）。
    val sessionId = effectiveSessionId
    // §issue-1(1): 当前会话的文件变更快照（来自 SessionListState.sessionDiffs），
    // 驱动聊天内 SessionDiffCard。非空时在 timeline 底部渲染一张可展开卡片。
    val sessionDiff = sessionId?.let { sessionListState.sessionDiffs[it] }
    // §issue-1(1): 打开会话时按需拉取 diff（视图层数据，解耦消息加载路径）。
    // LaunchedEffect(sessionId) 仅在切换会话时运行一次；SSE session.diff 负责后续增量。
    LaunchedEffect(sessionId) {
        if (!sessionId.isNullOrBlank()) sessionVM.loadSessionDiff(sessionId)
    }

    // §flicker-fix (Issue 1) + §wave1-c r2: the per-session LazyListState
    // (rememberSaveable(sessionId, LazyListState.Saver)) + followBottom
    // (saveable) + NavFab visibility / navJump guard / restore-in-flight guard
    // + the FIVE pure scroll side-effects (position mirror / direction detect /
    // bottom-track / navFab auto-hide / session-enter reset) now live in
    // ScrollManager.kt (rememberScrollController). listState is aliased here
    // for read-only use by the LazyColumn + the mixed effects below; the
    // mutable scroll flags are accessed via `scroll.*`. The saveable slots are
    // unchanged (same Saver / initializer / sessionId key) so Chat→preview→back
    // and session re-entry scroll memory is byte-identical to before.
    val scroll = rememberScrollController(
        sessionId = sessionId,
        savedPositions = savedPositions,
        accessOrder = accessOrder,
    )
    val listState = scroll.listState
    // §Wave5b-Q13: capture the PARENT's scroll checkpoint SYNCHRONOUSLY at the
    // click site (oracle ruling — the async savedPositions mirror cannot be
    // trusted). Delegates storage + navigation to the ChatScaffold-owned
    // [onOpenSubAgentNavigate] callback.
    val onOpenSubAgent: (String) -> Unit = { childSessionId ->
        onOpenSubAgentNavigate(childSessionId, scroll.captureCheckpoint())
    }

    // This intentionally excludes streaming text values. A token delta changes the
    // overlay map about every 100ms, but it is not a navigation event; using it as
    // a scroll-effect key created a scroll/layout feedback loop.
    val isStreaming = sessionIsRunning || streamingReasoningPart != null || streamingPartTexts.isNotEmpty()


    // §Wave5b-Q13: the unified scroll consumer is declared AFTER
    // [renderBlocks] below — it needs [lazyColumnKeys] which derives from
    // renderBlocks. See the LaunchedEffect keyed on
    // `pendingScrollRequest?.requestId` further down.

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
                scroll.followBottom = true
                scroll.navFabVisible = false
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
        // §Wave5b-Q13: skip auto-follow while a Restore is pending or in
        // flight — otherwise the programmatic scroll to the restored history
        // position would be immediately yanked back to the bottom by this
        // effect's animateScrollToItem(0). Two guards:
        //   (1) restore-in-flight (pendingRestoreSession == sessionId) — set
        //       synchronously by the Restore consumer while it scrolls.
        //   (2) restore-pending — a Restore PendingScrollRequest targeting
        //       this session that has not yet been consumed (waiting for the
        //       message load). The content-version effect would otherwise
        //       fire FIRST (it has fewer keys) and snap to bottom before the
        //       Restore consumer even runs. Read inline from chatState (the
        //       val pendingScrollRequest is declared later in the body —
        //       inline read avoids a forward reference).
        val liveReq = chatState.pendingScrollRequest
        val restoreInFlight = scroll.pendingRestoreSession == sessionId
        val restorePending = liveReq != null &&
            liveReq.targetSessionId == sessionId &&
            liveReq.behavior is ScrollBehavior.Restore
        if (restoreInFlight || restorePending) return@LaunchedEffect
        val streamingReasoningExpanded = streamingReasoningPart?.let { sr ->
            val key = sr.messageId?.let { "$it|${sr.id}" } ?: "streaming|${sr.id}"
            expandedParts[key] == true
        } == true
        if (scroll.followBottom && !streamingReasoningExpanded &&
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

    // 消息结构计算提到 LazyColumn body 之外。
    //
    // §ui-stream A3: the remember key for reversedMessages is narrowed from
    // the full `streamingPartTexts` Map to `streamingPartTexts.keys`. The body
    // below only checks MEMBERSHIP (`it.id in streamingPartTexts`), never
    // values — verified by reading every branch. A token delta changes the
    // Map's VALUES ~every 100ms but not its keys, so this membership-only
    // derivation is now stable across token deltas: reversedMessages (and the
    // Message instances it holds) keep the SAME references while a part is
    // streaming, which is what lets the downstream renderBlocks rebuild produce
    // value-equal Conversation blocks for non-streaming rows (see the
    // @Stable RenderBlock + the per-block baked streaming text in
    // ChatRenderBlockBuilder.kt). Membership changes (a part starts/stops
    // streaming) still recompute, preserving the §flicker-fix placeholder-
    // window behavior exactly. Mirrors the correct pattern already used by the
    // LaunchedEffect at the contentVersion scroll key (~L595).
    //
    // remove-message-persistence Task 4: the non-contiguous gap path
    // (a sealed Entry ADT with interleaved divider variants) was removed.
    // The render list now iterates `messages` directly (reverse + filter
    // for reverseLayout display).
    val reversedMessages = remember(messages, partsByMessage, streamingPartTexts.keys, streamingReasoningPart, sessionIsRunning) {
        computeFilteredReversedMessages(
            messages = messages,
            partsByMessage = partsByMessage,
            streamingPartTextKeys = streamingPartTexts.keys,
            streamingReasoningPart = streamingReasoningPart,
            sessionIsRunning = sessionIsRunning,
        ).also { filtered ->
            if (STREAMING_FLICKER_DEBUG) {
                // Log each filtered-out message for flicker diagnosis.
                messages.reversed().forEach { msg ->
                    if (msg !in filtered) {
                        val count = flickerFilterOutCount.incrementAndGet()
                        Log.w(
                            FLICKER_TAG,
                            "FILTERED OUT msgId=${msg.id} filterOutCount=$count"
                        )
                    }
                }
            }
        }
    }
    // Build folds in chronological order so the anchor is always the earliest
    // part, then reverse the resulting blocks for reverseLayout's item order.
    val renderBlocks = remember(
        reversedMessages,
        partsByMessage,
        streamingPartTexts,
        staleQuestionPartKeys,
        streamingReasoningPart,
        sessionIsRunning
    ) {
        buildRenderBlocks(
            messages = reversedMessages.asReversed(),
            partsByMessage = partsByMessage,
            streamingPartTexts = streamingPartTexts,
            staleQuestionPartKeys = staleQuestionPartKeys,
            streamingReasoningPartId = streamingReasoningPart?.id,
            // §ui-stream A1: the active streaming reasoning part's message id,
            // so buildRenderBlocks can bake isMessageStreaming into each
            // Conversation block (computeMessageStreaming) without the row
            // lambda re-reading the chat-wide Map for this check.
            streamingReasoningMessageId = streamingReasoningPart?.messageId,
            sessionIsRunning = sessionIsRunning
        ).asReversed()
    }

    // §Wave5b-Q13: the FULL key list of the LazyColumn body, in declaration
    // order. Used by the Restore consumer below to resolve an anchor key →
    // LazyColumn index. Mirrors the body's branches exactly (any reordering
    // of items() above MUST be mirrored here or Restore's index resolution
    // will drift). Keys: "streaming-reasoning" → "session-diff" →
    // renderBlocks[*].id → "load-more".
    val lazyColumnKeys: List<String> = remember(
        renderBlocks,
        streamingReasoningPart,
        sessionDiff,
        messages,
        hasMoreMessages,
        olderMessagesCursor,
    ) {
        lazyColumnKeyList(
            streamingReasoningPart = streamingReasoningPart,
            sessionDiff = sessionDiff,
            renderBlocks = renderBlocks,
            messages = messages,
            hasMoreMessages = hasMoreMessages,
            olderMessagesCursor = olderMessagesCursor,
        )
    }

    // §Wave5b-Q13: unified scroll consumer. Observes
    // [ChatState.pendingScrollRequest]; when its targetSessionId matches the
    // active sessionId, fires exactly once:
    //  - **Latest** → scrollToItem(0) (reverseLayout ⇒ 0 = newest) +
    //    followBottom=true + hide NavFab.
    //  - **Restore(checkpoint)** → resolve the anchor against
    //    [lazyColumnKeys] (or clamp the fallback index), scrollToItem(idx,
    //    offset), set followBottom based on whether the resolved position is
    //    at the bottom.
    // After completion → dispatch [chatVM.consumeScrollRequest] for compare-
    // and-clear by requestId (a stale consumer's clear is a no-op against a
    // newer id).
    //
    // `messages.isEmpty()` key: waits for the session's first message load
    // before firing (otherwise scrollToItem(0) on an empty list is a no-op
    // AND the consumer would clear the intent prematurely). For an EMPTY new
    // session (legitimately no messages), the consumer never fires — that is
    // correct (no scroll to perform).
    //
    // **Same-session reselect contract**: switchTo is a no-op when
    // currentSessionId == incoming id, so no NEW PendingScrollRequest is
    // generated for a same-session tap. The pre-existing slot (if any) is
    // preserved; if it was already consumed, it stays cleared. This is the
    // oracle's "同 session 普通 select = no-op (不 reload、不产生新 intent)".
    val pendingScrollRequest = chatState.pendingScrollRequest
    LaunchedEffect(sessionId, pendingScrollRequest?.requestId, messages.isEmpty()) {
        val req = pendingScrollRequest ?: return@LaunchedEffect
        if (sessionId == null) return@LaunchedEffect
        if (req.targetSessionId != sessionId) return@LaunchedEffect
        if (messages.isEmpty()) return@LaunchedEffect  // wait for the load
        when (val b = req.behavior) {
            is ScrollBehavior.Latest -> {
                // §Q13-swipe-fix: wait for the list to actually lay out content
                // before forcing item 0. On horizontal swipe the page's
                // ChatMessageList is freshly (re)composed and its
                // rememberSaveable LazyListState restores the PRIOR viewport
                // (mid-history) + followBottom=false; a bare scrollToItem(0)
                // fired around the first measure races that restore and loses.
                // Gating on totalItemsCount > 0 guarantees we scroll AFTER the
                // list has measured, so item 0 (newest) reliably wins.
                snapshotFlow { listState.layoutInfo.totalItemsCount }
                    .first { it > 0 }
                listState.scrollToItem(0)
                scroll.followBottom = true
                scroll.navFabVisible = false
            }
            is ScrollBehavior.Restore -> {
                val cp = b.checkpoint
                // Restore-in-flight guard: skip the savedPositions mirror +
                // direction detector + content-version auto-follow while we
                // programmatic-scroll to the restored position.
                scroll.pendingRestoreSession = sessionId
                scroll.followBottom = false
                val keys = lazyColumnKeys
                val itemCount = keys.size
                if (itemCount > 0) {
                    // itemCount > 0 ⇒ resolveRestoreIndex returns non-null
                    // (the null branch only fires when keys is empty).
                    val resolved = resolveRestoreIndex(
                        checkpoint = cp,
                        currentKeys = keys,
                    )
                    if (resolved != null) {
                        listState.scrollToItem(resolved.index, resolved.offset)
                        // If the restored position happens to land at the visual
                        // bottom (reverseLayout index 0 + small offset), arm
                        // followBottom so subsequent streaming sticks; otherwise
                        // stay disarmed (user is reading history).
                        if (resolved.index == 0 && resolved.offset <= 24) {
                            scroll.followBottom = true
                        }
                    }
                }
                scroll.pendingRestoreSession = null
            }
        }
        // Compare-and-clear by requestId (a newer request supersedes this
        // one → clear is a no-op; this consumer's intent survives).
        chatVM.consumeScrollRequest(req.requestId)
    }

    // §Phase8-nav: Box 包裹 LazyColumn + 导航 FAB overlay（右侧中下）。
    //
    // §WT2-taskA (Q9 locked): the OUTER container is the single source of
    // the conversation area's 8dp top/bottom breathing space. The previous
    // `LazyColumn.contentPadding = PaddingValues(vertical = Dimens.spacing2)` moved
    // INSIDE the list (scrollable padding) is removed in favour of an
    // explicit `Modifier.padding(vertical = Dimens.spacing2)` HERE so the conversation
    // REGION as a whole carries the 8dp T/B margin (½ × the 16dp L/R row
    // padding). Result: T/B = 8dp (outer), L/R = 16dp (per-row horizontal
    // padding in MessageRow / ToolRun / Fold / load-more /
    // empty / loading rows — all carry `padding(horizontal = Dimens.spacing4, …)`).
    // first/last-item spacing is unchanged: previously (list-pad 8dp +
    // row-pad 4dp = 12dp) ↔ now (outer 8dp + row-pad 4dp = 12dp).
    // reverseLayout=true semantics + streaming auto-follow are untouched.
    Box(modifier = Modifier.fillMaxSize().padding(vertical = Dimens.spacing2)) {
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
            val workdirBasename = dir.workdirBasename() ?: dir
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
        // §WT2-taskA: horizontal-only (effectively zero) — the 8dp T/B is
        // now on the outer Box (see root container doc above). Rows carry
        // their own `padding(horizontal = Dimens.spacing4, …)`, so no list-level
        // horizontal padding is needed either.
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
    ) {
        if (streamingReasoningPart != null) {
            // §wave1-c r2: the standalone streaming-reasoning card presentation
            // is extracted to StreamingOverlay.kt (StreamingReasoningOverlay).
            // The expand-key ("${messageId}|${partId}", §R-1) + card-width logic
            // move with it; only the streaming-text lookup stays here (it shares
            // the streamingPartTexts map with the render-block pipeline above).
            val streamingKey = streamingReasoningPart.id
            val streamingText = streamingPartTexts[streamingKey] ?: ""
            item(key = "streaming-reasoning") {
                StreamingReasoningOverlay(
                    streamingReasoningPart = streamingReasoningPart,
                    streamingText = streamingText,
                    expandedParts = expandedParts,
                    onToggleExpand = onToggleExpand,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = Dimens.spacing4, vertical = Dimens.spacing1),
                )
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
        // （供消息导航 FAB 复用）。remove-message-persistence Task 4: the gap
        // divider branch (RenderBlock.Gap) was deleted; the items() block now
        // handles message rows only.
        itemsIndexed(
            renderBlocks,
            key = { _, block -> block.id },
            // §ui-stream A2: contentType lets LazyColumn reuse an item's
            // composition slot ONLY with another item of the same variant
            // (Conversation / ToolRun / Fold). Without it, a slot that
            // previously held (say) a compact Conversation row could be
            // reused for a multi-item ToolRun on a structural change,
            // forcing a full re-inflation of a different layout. The
            // KClass is a stable, hashable Any — the three variants never
            // collide. key stays block.id (stable per-part anchor).
            contentType = { _, block -> block::class },
        ) { index, block ->
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
                                    // §ui-stream A1: read the hoisted chat-wide
                                    // boolean (a stable capture) instead of
                                    // streamingPartTexts.isNotEmpty() inline,
                                    // so the row lambda no longer captures the
                                    // whole (unstable, fresh-every-token) Map.
                                    hasStreamingText = chatHasStreamingText,
                                    hasStreamingReasoning = streamingReasoningPart != null,
                                )
                            )
                            // §ui-stream A1: the §omitted-streaming flag is now
                            // BAKED into the block by buildRenderBlocks
                            // (computeMessageStreaming) so this row lambda does
                            // not read streamingPartTexts for this check. The
                            // computation is byte-identical to the former inline
                            // version (parts-membership in streamingPartTexts OR
                            // streaming-reasoning owns this message OR non-user
                            // running session with empty/text/reasoning/non-
                            // terminal-tool parts) — see computeMessageStreaming.
                            val isMessageStreaming = block.isMessageStreaming
                            MessageCard(
                                message = message,
                                parts = msgParts,
                                // §ui-stream A1: pass the per-BLOCK streaming-
                                // text slice baked into the Conversation block
                                // (only entries for THIS block's parts), NOT the
                                // chat-wide Map. A token delta now changes only
                                // the streaming message's own block value; non-
                                // streaming rows pass an unchanged empty map
                                // sourced from a @Stable block (no Map capture
                                // invalidates the row lambda).
                                streamingPartTexts = block.streamingPartTexts,
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
                                // §slimapi-client-v1 §G6 (Task 16): threaded through
                                // to MessageRow for the "展开省略内容" affordance.
                                partExpandStates = partExpandStates,
                                onExpandParts = { parts ->
                                    val sid = sessionId
                                    if (sid != null) chatVM.expandParts(sid, parts)
                                },
                                // §omitted-streaming: disable expand affordance while
                                // the message is actively streaming.
                                isMessageStreaming = isMessageStreaming,
                            )
                        }
                    }
                }
                is RenderBlock.ToolRun -> {
                    CardWidthScope(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spacing4, vertical = Dimens.spacing1)
                    ) { cardMax ->
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
                    CardWidthScope(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spacing4, vertical = Dimens.spacing1)
                    ) { cardMax ->
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
                    modifier = Modifier.fillMaxWidth().padding(Dimens.spacing4),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoadingMore) {
                        CircularProgressIndicator(modifier = Modifier.size(Dimens.iconStd))
                    } else {
                        Text(
                            text = stringResource(R.string.chat_load_more_history),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable(onClick = onLoadMore)
                                .padding(horizontal = Dimens.spacing4, vertical = Dimens.spacing2)
                        )
                    }
                }
            }
        }
        // §cold-load: 首次加载期间显示 spinner + "加载中…"
        if (isLoading && messages.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(Dimens.spacing7),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(Dimens.iconXl),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(Dimens.spacing3))
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
                    modifier = Modifier.fillMaxWidth().padding(Dimens.spacing7),
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
            visible = scroll.navFabVisible,
            onJump = {
                scroll.navFabVisible = false
                scroll.navJumping = true
                scroll.followBottom = true
            },
            onJumpDone = { scroll.navJumping = false },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = Dimens.spacing4, bottom = Dimens.spacing4),
        )
    }
}

