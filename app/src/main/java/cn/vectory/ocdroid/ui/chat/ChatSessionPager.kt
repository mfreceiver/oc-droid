package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.ui.ChatViewModel
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.SessionViewModel
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * §L5a (UI god-file split): the multi-root-session [HorizontalPager] + its
 * bidirectional currentSessionId ↔ page sync, extracted verbatim from
 * ChatScaffold (no behaviour change). The pager setup block that lived inline
 * in ChatScaffold (initialPage / pagerState / both LaunchedEffects / the
 * rememberUpdatedState pair / the settledPage race guard) and BOTH
 * [ChatMessageList] render call sites (the in-pager one + the single-root /
 * sub-agent fallback one) move INTO this composable. ChatScaffold decides
 * whether to call THIS composable OR render [ChatEmptyState] (a different
 * concern — connection-empty surface) — see the call site there.
 *
 * §why-this-extracts-cleanly (oracle): nothing outside the [711-826] setup
 * block and the [931-1018] render branch in the old ChatScaffold reads
 * `pagerState` / `pagerEngaged` / `initialPage`. The setup block was only
 * textually separated from its render consumer; both halves move together
 * into this composable, so the two-way sync semantics are unchanged.
 *
 * §state-passing (oracle / hoisted containers):
 *  - [openSessions] is the SAME `topBarState.openSessions` value ChatScaffold
 *    already derives; passed as a plain `List<Session>` value. The
 *    `rememberUpdatedState(openSessions)` INSIDE this composable keeps the
 *    long-lived `settledPage` collector reading the freshest list without
 *    restarting.
 *  - [savedPositions] / [accessOrder] are the hoisted shared scroll-cache
 *    containers ChatScaffold owns (and prunes against openSessionIds /
 *    refreshNonce). They are the ONLY non-plain-value params besides the
 *    VMs + callbacks — documented exception (the cache is shared with the
 *    future cross-session restore consumer; see the WRITE-ONLY note in
 *    ChatScaffold). Mutations stay inside [ChatMessageList] exactly as before.
 *  - [currentSessionId] / [parentSessionId] are plain nullable values
 *    (no State leakage).
 *
 * §belt-and-braces: the internal `if (currentSessionId != null &&
 * pagerEngaged)` branch keeps its `currentSessionId != null` half even though
 * ChatScaffold gates the call site on the same condition — kept verbatim per
 * spec (a separate simplify pass may fold it later).
 *
 * @param openSessions root-only open-session list (= topBarState.openSessions).
 * @param currentSessionId chat.currentSessionId (nullable; null ⇒ caller
 *   falls back to [ChatEmptyState] and never calls this composable).
 * @param parentSessionId topBarState.parentSessionId — when non-null the
 *   current session is a sub-agent and the pager is NOT engaged (sub-agents
 *   bypass the pager; v0.7.6 fallback parity).
 * @param isCurrentSessionSending pre-derived boolean (chat.currentSessionId ∈
 *   composer.sendingSessionIds) — passed in so [ChatMessageList] stays OFF
 *   composerFlow (every keystroke mutates composerFlow; a direct subscription
 *   there would recompose the whole message list on every key).
 * @param onFileClick onChatFileClick (workdir-resolved file-preview route).
 * @param onOpenChanges onOpenGitChanges.
 */
@Composable
internal fun ChatSessionPager(
    openSessions: List<Session>,
    currentSessionId: String?,
    parentSessionId: String?,
    isCurrentSessionSending: Boolean,
    savedPositions: SnapshotStateMap<String, Pair<Int, Int>>,
    accessOrder: SnapshotStateList<String>,
    onFileClick: (String) -> Unit,
    onOpenChanges: (String) -> Unit,
    chatVM: ChatViewModel,
    composerVM: ComposerViewModel,
    sessionVM: SessionViewModel,
    orchestratorVM: OrchestratorViewModel,
) {
    // §L5a: obtained internally — do NOT pass context (no other consumer of
    // LocalContext inside the pager tree). Used by both ChatMessageList
    // call sites' onCopyMessage clipboard write.
    val context = LocalContext.current

    // §new1 (2026-07-13): HorizontalPager restoration (0.7.6 parity). The
    // pager page set is the SAME `openSessions` list the SessionTabStrip
    // renders above — root-only, capped at 8 by the session domain. Pager is
    // engaged only when ≥2 root sessions are open AND the current session is
    // itself a root (no parent); otherwise the single ChatMessageList path
    // below renders directly (matches v0.7.6's "sub-agent or single-root
    // fallback").
    val pagerEngaged = openSessions.size >= 2 &&
        parentSessionId == null &&
        currentSessionId != null
    val initialPage = remember(openSessions, currentSessionId) {
        if (currentSessionId != null) {
            openSessions.indexOfFirst { it.id == currentSessionId }
                .coerceAtLeast(0)
        } else 0
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { openSessions.size.coerceAtLeast(1) },
    )

    // §new1: bidirectional sync — currentSessionId → pager.
    // When the session changes externally (tab tap, Sessions screen,
    // SessionPickerSheet, back gesture), scroll the pager to match without
    // animation. Skipped when the pager is not engaged (single-session /
    // sub-agent fallback) or the new id is not in openSessions (e.g. a
    // sub-agent selection while still rendered through the fallback path).
    LaunchedEffect(currentSessionId, openSessions) {
        if (!pagerEngaged) return@LaunchedEffect
        val idx = openSessions.indexOfFirst { it.id == currentSessionId }
        if (idx in 0 until openSessions.size && idx != pagerState.currentPage) {
            pagerState.scrollToPage(idx)
        }
    }
    // §new1: bidirectional sync — pager → currentSessionId.
    // When the user swipes and the pager settles on a new page, select that
    // session. Guarded to skip: (a) the initial emit (currentSessionId
    // already matches the page), (b) pages that no longer exist
    // (openSessions shrank mid-swipe), and (c) draft mode (currentSessionId
    // == null — let the user finish composing before the pager steals
    // focus). rememberUpdatedState lets this long-lived collector read the
    // freshest openSessions / currentSessionId without restarting on every
    // list mutation (a restart would re-emit settledPage and oscillate tabs
    // during the close transition — v0.7.6 hit that as the "flicker-fix").
    //
    // §edge-noop: per spec, first/last page do NOT wrap and do NOT jump to
    // a parent — there is no edge-swipe special-casing here (settledPage is
    // only emitted on a real settle within bounds).
    val openSessionsLatest by rememberUpdatedState(openSessions)
    val currentSessionIdLatest by rememberUpdatedState(currentSessionId)
    LaunchedEffect(pagerState, pagerEngaged) {
        if (!pagerEngaged) return@LaunchedEffect
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val sessions = openSessionsLatest
                // §review-fix (groker M1): close/reorder race guard. On a
                // tab close, openSessions shrinks and closeSession() drives
                // its own next-selection. If we react to a settle here while
                // cur is no longer in the list (or page is now out of bounds),
                // we'd race closeSession and land on the "shifted-up" tab.
                // So only switch when cur is STILL open, the target id differs,
                // and the page is valid against the freshest list.
                val session = sessions.getOrNull(page) ?: return@collect
                val cur = currentSessionIdLatest
                val curStillOpen = cur != null && sessions.any { it.id == cur }
                if (curStillOpen && session.id != cur &&
                    page == pagerState.currentPage && page < sessions.size
                ) {
                    sessionVM.selectSession(session.id)
                }
            }
    }

    // §new1 (2026-07-13): HorizontalPager restored for the
    // multi-root-session case (page set = openSessions, identical to the
    // SessionTabStrip above). Single root, sub-agent (parent != null), and
    // "no session" cases bypass the pager:
    //   - ≥2 root sessions + root current → HorizontalPager
    //     (each page composes a ChatMessageList only when it is the current
    //      page; adjacent pages render an empty Box — no v0.7.6
    //      CircularProgressIndicator guard per spec, no perf hit from
    //      pre-rendering neighbours' message lists. `key = { session.id }`
    //      preserves scroll across page-slot reuse on tab reorder.)
    //   - exactly 1 open session, or parent != null sub-agent
    //     → single ChatMessageList (v0.7.6 fallback parity)
    //
    // §streaming-parity: ChatMessageList subscribes to chatFlow and reads
    // `chatState.currentSessionId` for ALL its slicing (messages / parts /
    // streaming / scroll memory). Wrapping it in HorizontalPager does NOT
    // change that subscription — the active page's ChatMessageList still
    // drives off the same chatFlow emission, so streaming / SSE token
    // deltas / scroll-anchoring behave identically to the pre-pager
    // single-list path. Non-current pages simply don't compose a
    // ChatMessageList (empty Box), so they cost nothing.
    //
    // §L5a belt-and-braces: the `currentSessionId != null` half of the first
    // branch is kept verbatim — ChatScaffold already gates the call site on
    // the same condition, so it is always true here. A separate simplify
    // pass may fold it.
    if (currentSessionId != null && pagerEngaged) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // §flicker-fix: key each page composable by session id so the
            // pager does NOT reuse a page slot for a different session on
            // reorder. Without this, a close-X that removes session at
            // index 0 would otherwise have the next-page slot keep the
            // prior session's LazyListState scroll offset for one frame.
            key = { page -> openSessions.getOrNull(page)?.id ?: "page-$page" },
        ) { page ->
            val session = openSessions.getOrNull(page)
            if (session != null && session.id == currentSessionId) {
                // §1C: wire the message-row overflow callbacks.
                // The three callbacks are the ONLY non-default destructives
                // in the chat surface — every one routes to an existing
                // domain method:
                //   Copy      → system clipboard
                //   Edit&rerun→ chatVM.editFromMessage
                //   Fork      → sessionVM.forkSession(id, id)
                //
                // §L5a: `currentSessionId!!` is safe — the outer guard
                // guarantees `currentSessionId != null`.
                ChatMessageList(
                    chatVM = chatVM,
                    composerVM = composerVM,
                    sessionVM = sessionVM,
                    orchestratorVM = orchestratorVM,
                    onFileClick = onFileClick,
                    onOpenChanges = onOpenChanges,
                    onTabVisibilityChange = { /* no second-row strip */ },
                    savedPositions = savedPositions,
                    accessOrder = accessOrder,
                    onCopyMessage = { _, text ->
                        copyToSystemClipboard(context, text)
                    },
                    onEditAndRerun = { messageId ->
                        chatVM.editFromMessage(messageId)
                    },
                    onFork = { messageId ->
                        sessionVM.forkSession(
                            sessionId = currentSessionId!!,
                            messageId = messageId,
                        )
                    },
                    isCurrentSessionSending = isCurrentSessionSending,
                )
            } else {
                // §new1: non-current page — render nothing.
                // Per spec, no v0.7.6 CircularProgressIndicator guard is
                // required (we do NOT pre-render adjacent sessions' message
                // lists; ChatMessageList is composed only for the page
                // matching currentSessionId). The empty Box costs
                // effectively zero and the next settle flips this branch to
                // the real ChatMessageList via the pager→selectSession sync
                // above.
                Box(modifier = Modifier.fillMaxSize())
            }
        }
    } else if (currentSessionId != null) {
        // §new1: single root, sub-agent, or pager not engaged — render the
        // message list directly (v0.7.6 fallback parity). The conditions
        // inside ChatMessageList are unchanged from the pre-pager path;
        // this is the SAME composable the multi-page branch above renders
        // per page, so streaming / scroll / parity contracts hold.
        //
        // §L5a (no-dedup): this is the SECOND of the two ChatMessageList
        // call sites. Kept verbatim alongside the in-pager one — they are
        // not deduplicated on purpose (separate simplify pass).
        ChatMessageList(
            chatVM = chatVM,
            composerVM = composerVM,
            sessionVM = sessionVM,
            orchestratorVM = orchestratorVM,
            onFileClick = onFileClick,
            onOpenChanges = onOpenChanges,
            onTabVisibilityChange = { /* no second-row strip */ },
            savedPositions = savedPositions,
            accessOrder = accessOrder,
            onCopyMessage = { _, text ->
                copyToSystemClipboard(context, text)
            },
            onEditAndRerun = { messageId ->
                chatVM.editFromMessage(messageId)
            },
            onFork = { messageId ->
                sessionVM.forkSession(
                    sessionId = currentSessionId!!,
                    messageId = messageId,
                )
            },
            isCurrentSessionSending = isCurrentSessionSending,
        )
    }
}
