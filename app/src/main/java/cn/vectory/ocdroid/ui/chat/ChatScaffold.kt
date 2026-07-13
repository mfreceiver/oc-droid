// ChatScaffold.kt — Phase 1B chat shell. Replaces the chrome of ChatScreen
// (TopAppBar second-row SessionTabStrip, the HorizontalPager session switcher,
// and the alert-dialog popups for agent/model pickers) with the new M3-native
// surface (D.2/D.5): single TopAppBar + session-history icon + context chip,
// ModalBottomSheet SessionPicker (D.4), Agent/Model AssistChips (D.3).
//
// PARITY (mandatory): ChatScaffold preserves the existing slice reads and
// effects from ChatScreen verbatim. It only changes the chrome — the message
// list, streaming overlay, gap-paging, scroll anchoring, draft lifecycle,
// metadata-marker injection, and unread clearing are delegated to
// ChatMessageList without re-implementing any of it. New state slice fields
// (ComposerState.fileReferences) are additive only.

package cn.vectory.ocdroid.ui.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.ChatViewModel
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.ConnectionViewModel
import cn.vectory.ocdroid.ui.HostViewModel
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.SessionViewModel
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.computeContextUsage
import cn.vectory.ocdroid.ui.currentHostProfile
import cn.vectory.ocdroid.ui.currentSession
import cn.vectory.ocdroid.ui.currentSessionStatus
import cn.vectory.ocdroid.ui.controller.allSessionsById
import cn.vectory.ocdroid.ui.controller.questionRootIds
import cn.vectory.ocdroid.ui.controller.questionsInTree
import cn.vectory.ocdroid.ui.controller.rootIdOf
import cn.vectory.ocdroid.ui.resolveMessage
import cn.vectory.ocdroid.ui.showTimed
import cn.vectory.ocdroid.ui.visibleMessages
import cn.vectory.ocdroid.ui.settings.TofuTrustDialog
import cn.vectory.ocdroid.ui.theme.AppBottomSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * §1B: Phase 1B chat shell. Wraps the existing message-list / streaming /
 * gap-paging / draft / question / permission surfaces behind the new
 * M3-native chrome. Reads the same six slice flows as the old [ChatScreen]
 * (chatFlow / sessionListFlow / composerFlow / connectionFlow / settingsFlow
 * / hostFlow + unreadFlow + trafficFlow) so behaviour stays equivalent.
 *
 * @param onNavigateToSettings forwards to the new AppShell — the overflow
 *        menu's "System settings" entry.
 * @param onNavigateToSessions is no longer used directly (the session-history
 *        icon opens a ModalBottomSheet instead); kept on the signature so the
 *        AppShell wiring remains unchanged. Reserved for Phase 2.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScaffold(
    chatVM: ChatViewModel,
    composerVM: ComposerViewModel,
    connectionVM: ConnectionViewModel,
    sessionVM: SessionViewModel,
    hostVM: HostViewModel,
    orchestratorVM: OrchestratorViewModel,
    onNavigateToSettings: () -> Unit = {},
    /**
     * §new2 (2026-07-13): forwarded to [ChatEmptyState] so the "all tabs
     * closed" empty-state can deep-link the user to the Sessions screen
     * (one-tap recover from "I closed every tab"). The AppShell wiring
     * already passes `orchestratorVM.setLastRoute(NavRoute.Sessions)` —
     * previously this parameter was unused (the SessionPickerSheet was the
     * only session-switching surface); it now has a second consumer.
     */
    onNavigateToSessions: () -> Unit = {},
    /**
     * Opens the Chat-stack file preview at the current session's workdir,
     * locating the specific tapped file path when present. ChatScaffold
     * derives workdir from the current session and passes BOTH down — the
     * previous fix-6 shape dropped the path and passed only the session
     * directory, which broke file-path navigation (FilesPane.pathToShow was
     * always null).
     */
    onOpenChatFilePreview: (workdir: String?, path: String?) -> Unit = { _, _ -> },
    onOpenGitChanges: (String) -> Unit = {},
) {
    // §PARITY (verbatim from ChatScreen): all six slice reads survive the
    // chrome swap. The streaming / settings / host / unread / traffic reads
    // feed the top-bar / status overlay / model picker / agent picker /
    // session-picker sheet / snackbar host — every consumer is downstream of
    // these flows exactly as before.
    val connection by chatVM.connectionFlow.collectAsStateWithLifecycle()
    val traffic by connectionVM.trafficFlow.collectAsStateWithLifecycle()
    val composer by composerVM.composerFlow.collectAsStateWithLifecycle()
    val settings by orchestratorVM.settingsFlow.collectAsStateWithLifecycle()
    val chat by chatVM.chatFlow.collectAsStateWithLifecycle()
    val sessionList by chatVM.sessionListFlow.collectAsStateWithLifecycle()
    val unread by chatVM.unreadFlow.collectAsStateWithLifecycle()
    val host by orchestratorVM.hostFlow.collectAsStateWithLifecycle()

    // ── Chrome-only state (new for Phase 1B) ─────────────────────────────
    // §0.8.2 P2: showContextSelector / showOverflow are
    // GONE — the nav-icon workdir initial is non-clickable (P2.1), the
    // ContextCueChip + server-status IconButton are removed (P2.2), and the
    // overflow DropdownMenu is co-located with its ContextUsageRing trigger
    // inside ChatTopBar (P2.3 — the fix for the top-left popup bug). The
    // AgentPickerSheet + ModelPickerSheet triggers also moved into the
    // overflow menu (the Composer's Agent/Model chips were deleted in P2.5);
    // their sheet state lives HERE (ChatScaffold) because ChatTopBar only
    // fires the open-callback, and the sheets need slice reads (settingsFlow
    // for agents/providers; chatFlow for currentModel) that ChatScaffold
    // already subscribes to. The picker composables themselves stay in
    // Composer.kt (now `internal` so this file can call them).
    var showAgentPicker by rememberSaveable { mutableStateOf(false) }
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var showSessionPicker by rememberSaveable { mutableStateOf(false) }
    var errorDetail by remember { mutableStateOf<String?>(null) }
    var pendingExit by remember { mutableStateOf(false) }
    // §1B-FIX (I6): dialog-state for the parity overflow entries (Todo +
    // Context-usage). Owned here in ChatScaffold so the conversation
    // overflow menu can open them. The dialogs themselves render the
    // same body the old ChatTopBar used (TodoListPanel / ContextUsageDialog
    // from this package).
    var showTodoDialog by remember { mutableStateOf(false) }
    var showContextDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Image picker (Photos Add-menu entry). Phase 1B ships only Photos;
    // "Reference workspace file" and "Commands" are Phase 2/2b.
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        scope.launch {
            composerVM.addImageAttachments(loadImageAttachments(context, uris))
        }
    }
    val onAddImages: () -> Unit = remember(imagePicker) {
        { imagePicker.launch("image/*") }
    }

    // §PARITY: cross-slice derived views moved verbatim from ChatScreen —
    // currentSession, currentSessionStatus, cachedContextUsage, isRunning,
    // currentActivity, matchingQuestions, questionCardHeightDp — none are
    // re-implemented; they read the same slices and the same pure helpers
    // (`currentSession` / `currentSessionStatus` / `computeContextUsage` /
    // `visibleMessages` / `currentSessionActivity`).
    val curSession = currentSession(sessionList.sessions, chat.currentSessionId)
    // A file-path tap passes the actual tapped path through to the Chat-stack
    // preview. The prior route dropped that path, so the preview could not
    // locate the selected file. AppShell receives both route fields.
    val onChatFileClick: (String) -> Unit = remember(curSession, onOpenChatFilePreview) {
        { path -> onOpenChatFilePreview(curSession?.directory, path) }
    }
    val curCutoff = chat.currentSessionId?.let(chat.revertCutoffs::get)
    val curRevertMessageId = if (curSession != null) curSession.revert?.messageId else curCutoff?.messageId
    val curSessionStatus = currentSessionStatus(sessionList.sessionStatuses, chat.currentSessionId)
    val computedContextUsage = computeContextUsage(chat.messages, settings.providers)
    var cachedContextUsage by remember { mutableStateOf(computedContextUsage) }
    computedContextUsage?.let { cachedContextUsage = it }
    val currentSessionIsRunning = curSessionStatus?.let { it.isBusy || it.isRetry } == true ||
        chat.currentSessionId?.let { it in composer.sendingSessionIds } == true
    // §phase2-parity: narrow projection of composerFlow for the
    // canEditAndRerun destructive gate. Derived ONCE here (ChatScaffold
    // already subscribes to composerFlow + chatFlow + sessionListFlow) and
    // passed as a boolean to ChatMessageList. This keeps ChatMessageList
    // OFF composerFlow — every keystroke mutates composerFlow (input text),
    // so a direct subscription there would recompose the whole message
    // list on every key, breaking the §1B/1C "typing does not recompose
    // the list" parity contract. The other gate inputs (busy / retry /
    // streamingPartTexts / streamingReasoningPart) stay inside
    // ChatMessageList (read from its own chatFlow + sessionListFlow
    // subscriptions, which fire on legitimate message/stream events, not
    // keystrokes).
    val isCurrentSessionSending = chat.currentSessionId?.let { it in composer.sendingSessionIds } == true
    val currentActivity = remember(
        chat.currentSessionId,
        curSessionStatus,
        curRevertMessageId,
        curCutoff,
        chat.messages,
        chat.partsByMessage,
        chat.streamingReasoningPart,
        chat.streamingPartTexts,
    ) {
        currentSessionActivity(
            sessionId = chat.currentSessionId,
            status = curSessionStatus,
            messages = visibleMessages(
                chat.messages,
                curSession,
                curCutoff
            ),
            partsByMessage = chat.partsByMessage,
            streamingReasoningPart = chat.streamingReasoningPart,
            streamingPartTexts = chat.streamingPartTexts,
        )
    }
    val sessionsById = remember(
        sessionList.sessions,
        sessionList.directorySessions,
        sessionList.childSessions,
    ) {
        allSessionsById(
            sessionList.sessions,
            sessionList.directorySessions,
            sessionList.childSessions,
        )
    }
    val matchingQuestions = remember(
        sessionList.pendingQuestions,
        chat.currentSessionId,
        sessionsById,
    ) {
        val root = chat.currentSessionId?.let { rootIdOf(it, sessionsById) }
        if (root != null) questionsInTree(root, sessionList.pendingQuestions, sessionsById)
        else emptyList()
    }
    val pendingQuestion = matchingQuestions.firstOrNull()
    // §1C: permission session-scope filter (P5-7) — same rule the question
    // filter already applies. The pre-filtered permission is the input
    // to StatusSlot, which guarantees the slot is fed only this session's
    // pending permission (cross-session pending items become a Sessions
    // nav-bar badge in Phase 1A / scheme D.1).
    val pendingPermission = remember(sessionList.pendingPermissions, chat.currentSessionId) {
        sessionList.pendingPermissions.firstOrNull { it.sessionId == chat.currentSessionId }
    }
    // §1C: compacting + retry card height tracking is no longer needed —
    // the single status slot is anchored at the top of the chat area (not
    // bottom-overlaid), so it no longer occludes the snackbar host. The
    // old animateDpAsState(questionCardHeightDp) — used to push the
    // snackbar up over a bottom-anchored question card — is removed.

    // §PARITY: parent-session BackHandler (#14) + root-session exit-confirm
    // (Feature B) + UiEvent error/success snackbar collection (R-17 batch2) +
    // stale-notice snackbar + compacting auto-clear (Phase 0) are all moved
    // verbatim — the chrome swap does not affect any of these.
    val parent = curSession?.parentId
    var lastParent by remember { mutableStateOf<String?>(null) }
    if (parent != null) lastParent = parent
    BackHandler(enabled = parent != null) {
        lastParent?.let { sessionVM.selectSession(it) }
    }

    val exitConfirmMessage = stringResource(R.string.chat_exit_confirm)
    val errorMessage = stringResource(R.string.chat_error_occurred)
    val errorActionLabel = stringResource(R.string.chat_view)
    val staleNoticeMessage = stringResource(R.string.chat_stale_notice)
    val staleNoticeActionLabel = stringResource(R.string.common_refresh)

    BackHandler(enabled = parent == null && !pendingExit) {
        pendingExit = true
        scope.launch {
            snackbarHostState.showTimed(
                message = exitConfirmMessage,
                durationMillis = 1_000L
            )
        }
    }
    LaunchedEffect(pendingExit) {
        if (pendingExit) {
            delay(1_000L)
            pendingExit = false
        }
    }

    // §PARITY: UiEvent error / success / info / debug collection. Slice-
    // only reads, SharedFlow is one-shot. Verbatim from ChatScreen.
    LaunchedEffect(Unit) {
        orchestratorVM.uiEvents.collect { event ->
            val message = event.resolveMessage(context)
            when (event) {
                is UiEvent.Error -> {
                    snackbarHostState.showTimed(
                        message = errorMessage,
                        durationMillis = 3_000L,
                        actionLabel = errorActionLabel,
                        onAction = { errorDetail = message }
                    )
                }
                is UiEvent.Success -> {
                    snackbarHostState.showTimed(
                        message = message,
                        durationMillis = 2_500L
                    )
                }
                is UiEvent.Info -> {
                    snackbarHostState.showTimed(
                        message = message,
                        durationMillis = 2_500L
                    )
                }
                is UiEvent.Debug -> Unit
            }
        }
    }
    LaunchedEffect(chat.staleNotice, chat.currentSessionId) {
        if (chat.staleNotice && chat.currentSessionId != null) {
            snackbarHostState.showTimed(
                message = staleNoticeMessage,
                actionLabel = staleNoticeActionLabel,
                onAction = { chatVM.refreshCurrentSession() }
            )
        }
    }
    LaunchedEffect(currentSessionIsRunning, chat.isCompacting) {
        if (chat.isCompacting && !currentSessionIsRunning) {
            if (System.currentTimeMillis() - chat.compactStartedAt > 3000) {
                chatVM.clearCompacting()
            }
        }
    }

    // §PARITY: hoisted per-session scroll-position cache. Previously inside
    // ChatMessageList; the new ChatScaffold keeps it here (above the
    // single ChatMessageList call) so the composition-lifetime disposal risk
    // stays solved. The cache + LRU are passed to ChatMessageList; mutations
    // stay inside ChatMessageList exactly as before.
    val savedPositions = remember { mutableStateMapOf<String, Pair<Int, Int>>() }
    val accessOrder = remember { mutableStateListOf<String>() }
    LaunchedEffect(sessionList.openSessionIds) {
        val open = sessionList.openSessionIds.toSet()
        val stale = savedPositions.keys - open
        if (stale.isNotEmpty()) {
            stale.forEach { id ->
                savedPositions.remove(id)
                accessOrder.remove(id)
            }
        }
    }
    val refreshNonce = chat.refreshNonce
    LaunchedEffect(refreshNonce) {
        if (refreshNonce > 0L) {
            savedPositions.clear()
            accessOrder.clear()
        }
    }

    // §1B / §nav-redesign: derive ChatTopBarState inside a remembered
    // derivedStateOf so the TopAppBar recomposes only when its slice inputs
    // change. Slice reads match ChatTopBarState (see ChatTopBar.kt). The
    // second-row SessionTabStrip was RESTORED under ChatTopBar by the nav
    // redesign (quick switch between open root sessions); session switching is
    // via the strip AND the SessionPickerSheet (title tap = all/search/archive).
    val curHostProfile = currentHostProfile(host.hostProfiles, host.currentHostProfileId)
    // §Nit: hoist the localised "No host" fallback outside the
    // derivedStateOf lambda (Compose forbids @Composable invocations
    // inside non-composable lambdas like derivedStateOf).
    val noHostFallback = stringResource(R.string.chat_no_host_fallback)
    val topBarState by remember(noHostFallback) {
        derivedStateOf {
            val curHostProfile = currentHostProfile(host.hostProfiles, host.currentHostProfileId)
            val curSession = currentSession(sessionList.sessions, chat.currentSessionId)
            // §nav-redesign (2026-07-13): populate openSessions for the restored
            // SessionTabStrip (second row under ChatTopBar). openSessionIds is
            // root-only + capped at 8 by the session domain, but we defend at
            // the consumer too: a stale/legacy child id persisted in
            // openSessionIds must never render as a top-level tab. Associate-by
            // once for O(1) resolution (8 ids × ~500 sessions otherwise O(n×m)).
            val sessionsById = allSessionsById(
                sessionList.sessions,
                sessionList.directorySessions,
                sessionList.childSessions,
            )
            val openSessions = sessionList.openSessionIds
                .mapNotNull { sessionsById[it] }
                .filter { it.parentId == null && !it.isArchived }
            ChatTopBarState(
                sessions = sessionList.sessions,
                currentSessionId = chat.currentSessionId,
                sessionStatuses = sessionList.sessionStatuses,
                hasMoreSessions = sessionList.hasMoreSessions,
                isLoadingMoreSessions = sessionList.isLoadingMoreSessions,
                isRefreshingSessions = sessionList.isRefreshingSessions,
                expandedSessionIds = sessionList.expandedSessionIds,
                agents = settings.agents.filter { it.isVisible },
                selectedAgentName = settings.selectedAgentName,
                contextUsage = cachedContextUsage,
                sessionTodos = sessionList.sessionTodos[chat.currentSessionId ?: ""] ?: emptyList(),
                hostName = curHostProfile?.name ?: noHostFallback,
                isConnected = connection.isConnected,
                isConnecting = connection.isConnecting,
                connectionPhase = connection.connectionPhase,
                hostProfiles = host.hostProfiles,
                currentHostProfileId = host.currentHostProfileId,
                tunnelActivationState = connection.tunnelActivationState,
                showTunnelAuth = (curHostProfile?.tunnelPasswordId != null),
                unreadSessions = unread.unreadSessions,
                questionSessionIds = questionRootIds(sessionList.pendingQuestions, sessionsById),
                draftWorkdir = composer.draftWorkdir,
                parentSessionId = curSession?.parentId,
                parentSessionTitle = curSession?.parentId?.let { pid ->
                    sessionList.sessions.firstOrNull { it.id == pid }?.displayName
                },
                trafficSent = traffic.trafficSent,
                trafficReceived = traffic.trafficReceived,
                serverVersion = connection.serverVersion,
                providers = settings.providers,
                disabledModels = settings.disabledModels,
                currentModel = chat.currentModel,
                // §nav-redesign: consumed by the restored SessionTabStrip
                // (rendered as the TopAppBar's second row, directly after
                // ChatTopBar in the column below). Empty list short-circuits
                // inside SessionTabStrip (PrimaryScrollableTabRow guard).
                openSessions = openSessions,
            )
        }
    }
    val topBarActions = remember(
        sessionVM,
        chatVM,
    ) {
        // §0.8.2 P2.3: the new overflow menu (Context / Todo / Agent / Model)
        // lives inside ChatTopBar; its open-callbacks fire the local
        // sheet/dialog state hoisted in this composable. The pickers' slice
        // reads (agents / providers / currentModel / disabled models) are
        // sourced below from the already-subscribed settings + chat slices,
        // so opening a sheet does not trigger a fresh subscription. The
        // AgentPickerSheet / ModelPickerSheet composables stay defined in
        // Composer.kt (now `internal`).
        //
        // §dead-onCompact-cleanup: the standalone "Compress" overflow item
        // was removed; compaction is triggered via the ContextUsageDialog's
        // own "Compress context" button (see showContextDialog below).
        //
        // §nav-redesign (2026-07-13): onCloseSession wired to
        // sessionVM::closeSession so the per-tab close-X in the restored
        // SessionTabStrip can dismiss an open tab (closes the browser-tab,
        // NOT archive — the conversation stays in the Sessions history).
        ChatTopBarActions(
            onSelectSession = sessionVM::selectSession,
            onCloseSession = sessionVM::closeSession,
            onOpenContextDialog = { showContextDialog = true },
            onOpenTodoDialog = { showTodoDialog = true },
            onOpenAgentPicker = { showAgentPicker = true },
            onOpenModelPicker = { showModelPicker = true },
        )
    }

    // §new1 (2026-07-13): HorizontalPager restoration (0.7.6 parity). The
    // pager page set is the SAME `openSessions` list the (restored)
    // SessionTabStrip renders above — root-only, capped at 8 by the session
    // domain. Pager is engaged only when ≥2 root sessions are open AND the
    // current session is itself a root (no parent); otherwise the single
    // ChatMessageList path below renders directly (matches v0.7.6's
    // "sub-agent or single-root fallback"). When openSessions is empty the
    // ChatEmptyState branch (with the new Sessions deep-link, §new2) handles
    // the surface.
    val openSessions = topBarState.openSessions
    val pagerEngaged = openSessions.size >= 2 &&
        topBarState.parentSessionId == null &&
        chat.currentSessionId != null
    val initialPage = remember(openSessions, chat.currentSessionId) {
        if (chat.currentSessionId != null) {
            openSessions.indexOfFirst { it.id == chat.currentSessionId }
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
    LaunchedEffect(chat.currentSessionId, openSessions) {
        if (!pagerEngaged) return@LaunchedEffect
        val idx = openSessions.indexOfFirst { it.id == chat.currentSessionId }
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
    val currentSessionIdLatest by rememberUpdatedState(chat.currentSessionId)
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

    Column(modifier = Modifier.fillMaxSize()) {
        ChatTopBar(
            state = topBarState,
            actions = topBarActions,
            // §1B: tabVisible is now decorative (no second-row strip is
            // rendered); ChatMessageList still calls onTabVisibilityChange
            // but the callback is a no-op stub.
            tabVisible = true,
            onTabVisibilityChange = { /* no second-row strip in 1B */ },
            onTitleClick = { showSessionPicker = true },
        )

        // §nav-redesign (2026-07-13): SessionTabStrip is restored as the
        // TopAppBar's second row — the persistent horizontal "open sessions"
        // browser-tab strip. Coexists with the SessionPickerSheet (opened by
        // tapping the title above) — the strip is the always-visible quick
        // switcher, the sheet is the full list / search / archive surface.
        // currentWorkdir tints the selected tab's accent bar with the
        // current session's directory hash (draftWorkdir fallback so a brand-
        // new draft tab still gets a stable colour).
        // §task6-grandchild (final-review fix 4): resolve the FULL root of the
        // current session via rootIdOf (not just the direct parent) and feed
        // it to SessionTabStrip's parentSessionId slot. resolveEffectiveSelectedId
        // alone only walks one level (direct parent), so a grandchild current
        // session (currentSessionId=grandchild, parentSessionId=child, neither
        // in the root-only openSessions list) returned null → no tab was
        // selected → the root tab's "?" marker was NOT suppressed even though
        // its question was already surfaced in the chat's QuestionCard. By
        // passing the full root id here, the root tab reads as selected for
        // any descendant depth. topBarState.parentSessionId (the DIRECT parent)
        // stays untouched — ChatTopBar's breadcrumb still navigates one level
        // up, not all the way to root.
        val currentRootId = chat.currentSessionId?.let { rootIdOf(it, sessionsById) }
        SessionTabStrip(
            openSessions = topBarState.openSessions,
            currentSessionId = topBarState.currentSessionId,
            parentSessionId = currentRootId,
            currentWorkdir = curSession?.directory ?: composer.draftWorkdir,
            unreadSessions = topBarState.unreadSessions,
            questionSessionIds = topBarState.questionSessionIds,
            actions = topBarActions,
        )

        // §PARITY: wide-screen card wrap mirrors ChatScreen 10/§B3. Phase 1B
        // keeps the wrapping Surface so the chat area looks identical on
        // Medium/Expanded.
        val isWide = LocalWindowSizeClass.current
            ?.let { it.widthSizeClass != WindowWidthSizeClass.Compact }
            ?: (LocalConfiguration.current.screenWidthDp >= 600)
        val cardShape = if (isWide) MaterialTheme.shapes.large else RectangleShape
        androidx.compose.material3.Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (isWide) Modifier.padding(8.dp) else Modifier),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = cardShape,
            shadowElevation = if (isWide) 2.dp else 0.dp,
            tonalElevation = if (isWide) 1.dp else 0.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    // §new1 (2026-07-13): HorizontalPager restored for the
                    // multi-root-session case (page set = topBarState.openSessions,
                    // identical to the SessionTabStrip above). Single root,
                    // sub-agent (parent != null), and "no session" cases bypass
                    // the pager:
                    //   - ≥2 root sessions + root current → HorizontalPager
                    //     (each page composes a ChatMessageList only when it is
                    //      the current page; adjacent pages render an empty Box
                    //      — no v0.7.6 CircularProgressIndicator guard per spec,
                    //      no perf hit from pre-rendering neighbours' message
                    //      lists. `key = { session.id }` preserves scroll across
                    //      page-slot reuse on tab reorder.)
                    //   - exactly 1 open session, or parent != null sub-agent
                    //     → single ChatMessageList (v0.7.6 fallback parity)
                    //   - no currentSessionId + not draft → ChatEmptyState
                    //     (with the new "no tab" Sessions deep-link, §new2)
                    //   - draft (no session yet but workdir chosen) → blank
                    //     (composer is the foreground surface)
                    //
                    // §streaming-parity: ChatMessageList subscribes to chatFlow
                    // and reads `chatState.currentSessionId` for ALL its slicing
                    // (messages / parts / streaming / scroll memory). Wrapping it
                    // in HorizontalPager does NOT change that subscription — the
                    // active page's ChatMessageList still drives off the same
                    // chatFlow emission, so streaming / SSE token deltas /
                    // scroll-anchoring behave identically to the pre-pager
                    // single-list path. Non-current pages simply don't compose
                    // a ChatMessageList (empty Box), so they cost nothing.
                    val isDraft = composer.draftWorkdir != null && chat.currentSessionId == null
                    if (chat.currentSessionId != null && pagerEngaged) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            // §flicker-fix: key each page composable by session
                            // id so the pager does NOT reuse a page slot for a
                            // different session on reorder. Without this, a
                            // close-X that removes session at index 0 would
                            // otherwise have the next-page slot keep the prior
                            // session's LazyListState scroll offset for one frame.
                            key = { page -> openSessions.getOrNull(page)?.id ?: "page-$page" },
                        ) { page ->
                            val session = openSessions.getOrNull(page)
                            if (session != null && session.id == chat.currentSessionId) {
                                // §1C: wire the message-row overflow callbacks.
                                // The three callbacks are the ONLY non-default
                                // destructives in the chat surface — every one
                                // routes to an existing domain method:
                                //   Copy      → system clipboard
                                //   Edit&rerun→ chatVM.editFromMessage
                                //   Fork      → sessionVM.forkSession(id, id)
                                ChatMessageList(
                                    chatVM = chatVM,
                                    composerVM = composerVM,
                                    sessionVM = sessionVM,
                                    orchestratorVM = orchestratorVM,
                                    onFileClick = onChatFileClick,
                                    onOpenChanges = onOpenGitChanges,
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
                                            sessionId = chat.currentSessionId!!,
                                            messageId = messageId,
                                        )
                                    },
                                    isCurrentSessionSending = isCurrentSessionSending,
                                )
                            } else {
                                // §new1: non-current page — render nothing.
                                // Per spec, no v0.7.6 CircularProgressIndicator
                                // guard is required (we do NOT pre-render
                                // adjacent sessions' message lists; ChatMessageList
                                // is composed only for the page matching
                                // chat.currentSessionId). The empty Box costs
                                // effectively zero and the next settle flips
                                // this branch to the real ChatMessageList via
                                // the pager→selectSession sync above.
                                Box(modifier = Modifier.fillMaxSize())
                            }
                        }
                    } else if (chat.currentSessionId != null) {
                        // §new1: single root, sub-agent, or pager not engaged —
                        // render the message list directly (v0.7.6 fallback
                        // parity). The conditions inside ChatMessageList are
                        // unchanged from the pre-pager path; this is the SAME
                        // composable the multi-page branch above renders per
                        // page, so streaming / scroll / parity contracts hold.
                        ChatMessageList(
                            chatVM = chatVM,
                            composerVM = composerVM,
                            sessionVM = sessionVM,
                            orchestratorVM = orchestratorVM,
                            onFileClick = onChatFileClick,
                            onOpenChanges = onOpenGitChanges,
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
                                    sessionId = chat.currentSessionId!!,
                                    messageId = messageId,
                                )
                            },
                            isCurrentSessionSending = isCurrentSessionSending,
                        )
                    } else if (!isDraft) {
                        ChatEmptyState(
                            isConnected = connection.isConnected,
                            isConnecting = connection.isConnecting,
                            connectionPhase = connection.connectionPhase,
                            hostName = curHostProfile?.name
                                ?: curHostProfile?.serverUrl
                                    ?.substringAfter("://")
                                    ?.substringBefore("/")
                                    ?: stringResource(R.string.chat_server_fallback),
                            onConnect = { connectionVM.testConnection() },
                            // §new2: when the user closed every tab
                            // (currentSessionId == null, no draft), the empty
                            // state's "connected + idle" branch offers a one-
                            // tap deep-link to the Sessions screen.
                            onNavigateToSessions = onNavigateToSessions,
                        )
                    }

                    // §1C: the single status slot (C.3 / D.2.1). Replaces the
                    // five competing overlays (thinking / retry / connecting /
                    // question / permission) that used to stack on top of
                    // the chat area. Only ONE of (Permission, Question,
                    // Retry, Compacting, Running, Connecting) renders at any
                    // time — see [StatusSlotPriority.pick] for the binding
                    // rule and the priority enum. The pending permission is
                    // pre-filtered to chat.currentSessionId (P5-7) at the
                    // call site; the slot does not re-apply the filter.
                    //
                    // §1C-FIX-①: the caller MUST NOT pre-filter the inputs
                    // beyond the P5-7 session-scope rule. The previous
                    // `curSessionStatus?.takeIf { !chat.isCompacting }` and
                    // the `curSessionStatus?.isRetry != true` filter on
                    // currentActivityText were over-eager: they hid a Retry
                    // status from `pick()` whenever Compacting was also
                    // true, breaking the C.3 priority (Retry > Compacting).
                    // pick() is now the SOLE decision point — we hand it
                    // the canonical inputs and let it return the winning
                    // class. sessionStatus flows in whole (including
                    // isRetry even during compaction) and the activity
                    // text is the raw value (Retry > Running means the
                    // text is ignored when Retry wins, exactly what the
                    // scheme specifies).
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                    StatusSlot(
                        permission = pendingPermission,
                        question = pendingQuestion,
                        sessionStatus = curSessionStatus,
                        isCompacting = chat.isCompacting,
                        currentActivityText = if (currentSessionIsRunning && currentActivity != null) {
                            currentActivity.text
                        } else {
                            null
                        },
                        // §1C-FIX-⑤: pass the startedAt values that the
                        // Compacting + Running branches need for the
                        // elapsed timer. The values are non-null when
                        // the corresponding state is active (see
                        // ChatActivityHelpers for how currentActivity
                        // sources startedAtMillis from the latest user
                        // message's time.created).
                        currentActivityStartedAtMillis = currentActivity?.startedAtMillis,
                        compactStartedAt = chat.compactStartedAt,
                        isConnecting = connection.isConnecting && !connection.isConnected,
                        // §1C-FIX-⑧: scheme E.4 metadata. Sourced from
                        // the canonical slices: host (host.hostProfiles
                        // + currentHostProfileId), workdir (current
                        // session's directory or composer draft), session
                        // (current session's displayName), tool
                        // (permission.tool), target (permission.metadata
                        // .filepath — the most common target shape;
                        // could be extended in future for command-line
                        // targets).
                        permissionMetadata = ChatPermissionMetadata(
                            hostName = curHostProfile?.name,
                            workdirBasename = (curSession?.directory
                                ?: composer.draftWorkdir)
                                ?.split('/')
                                ?.filter { it.isNotEmpty() }
                                ?.lastOrNull(),
                            sessionName = curSession?.displayName,
                            // The "tool name" the user cares about is
                            // the permission string itself (e.g.
                            // "bash" / "edit" / "webfetch"). The
                            // [PermissionRequest.tool] field is a
                            // ToolRef (messageId / callId reference)
                            // and is NOT a human-readable tool name.
                            toolName = pendingPermission?.permission,
                            target = pendingPermission?.metadata?.filepath,
                        ),
                        onRespondPermission = { response ->
                            pendingPermission?.let { p ->
                                orchestratorVM.respondPermission(p.sessionId, p.id, response)
                            }
                        },
                        onReplyQuestion = { questionId, answers, onError ->
                            orchestratorVM.replyQuestion(questionId, answers, onError)
                        },
                        onRejectQuestion = { questionId, onError ->
                            orchestratorVM.rejectQuestion(questionId, onError)
                        },
                        questionQueuePosition = pendingQuestion?.let { q ->
                            matchingQuestions.indexOfFirst { it.id == q.id } + 1
                        } ?: 1,
                        questionQueueTotal = matchingQuestions.size,
                        onAbort = chatVM::abortSession,
                    )
                }
            }
        }

        // §PARITY: ChatInputBar moved to the outer Column (below the chat
        // Surface) so it is not wrapped by the chat card's background /
        // rounding / elevation. Composer.kt replaces ChatInputBar.kt and
        // subscribes to the same slices (composerFlow + settingsFlow) and
        // routes through the same domain methods.
        if (chat.currentSessionId != null || composer.draftWorkdir != null) {
            // §1B-FIX (I5): Composer no longer takes connectionVM / hostVM —
            // it does not render any chrome that needs them (the surface
            // stays on composerFlow + settingsFlow + a narrow
            // currentModelFlow projection). Removing the dead injections
            // enforces "Composer must NOT subscribe to unrelated slices".
            Composer(
                chatVM = chatVM,
                composerVM = composerVM,
                orchestratorVM = orchestratorVM,
                isBusy = currentSessionIsRunning || chat.isCompacting,
                questionPending = pendingQuestion != null,
                onAddImages = onAddImages,
            )
        }

        // §1C: the bottom-anchored ChatPermissionCard that used to live
        // here is REMOVED — the single StatusSlot (above the chat Surface)
        // now renders the permission card for the current session. Cross-
        // session pending items surface as a Sessions nav-bar badge (Phase
        // 1A / scheme D.1) and never appear in the chat area. The bottom
        // space that this Column would have occupied is now empty (the
        // message Surface above uses weight(1f), so it claims the full
        // vertical space and the Composer sits flush below).
    }

    // ── Phase 1B sheets / overflows / dialogs (new) ──────────────────────

    // §0.8.2 P2.3: AgentPickerSheet. Trigger moved from the Composer's Agent
    // AssistChip (deleted in P2.5) to the overflow menu's "Agent" item. The
    // sheet body composable stays defined in Composer.kt (now `internal`).
    // Slice reads: agents + selectedAgentName come from the already-
    // subscribed settings slice (filtered to visible agents — same filter
    // the old chip Row used). onPick routes to composerVM.selectAgent (the
    // identical domain path the Composer's sheet used).
    if (showAgentPicker) {
        AgentPickerSheet(
            agents = settings.agents.filter { it.isVisible },
            selectedAgentName = settings.selectedAgentName,
            onPick = { name -> composerVM.selectAgent(name); showAgentPicker = false },
            onDismiss = { showAgentPicker = false },
        )
    }

    // §0.8.2 P2.3: ModelPickerSheet. Trigger moved from the Composer's Model
    // AssistChip (deleted in P2.5) to the overflow menu's "Model" item. The
    // sheet body composable stays defined in Composer.kt (now `internal`).
    // Slice reads: providers + disabledModels come from the settings slice;
    // currentModel comes from the chat slice (cachedContextUsage is sourced
    // from the same slice). onSwitch routes to composerVM.switchSessionModel
    // (V1-per-prompt, identical domain path the Composer's sheet used).
    if (showModelPicker) {
        ModelPickerSheet(
            providers = settings.providers,
            disabledModels = settings.disabledModels,
            currentModel = chat.currentModel,
            onSwitch = { providerId, modelId ->
                composerVM.switchSessionModel(providerId, modelId)
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
        )
    }

    if (showSessionPicker) {
        SessionPickerSheet(
            sessions = sessionList.sessions,
            sessionStatuses = sessionList.sessionStatuses,
            currentSessionId = chat.currentSessionId,
            unreadSessions = unread.unreadSessions,
            questionSessionIds = questionRootIds(sessionList.pendingQuestions, sessionsById),
            onSelect = { sessionId ->
                sessionVM.selectSession(sessionId)
                showSessionPicker = false
            },
            onArchive = sessionVM::archiveSession,
            onUnarchive = sessionVM::restoreSession,
            onNewSession = {
                sessionVM.createSession()
                showSessionPicker = false
            },
            onDismiss = { showSessionPicker = false },
        )
    }

    // §1B-FIX (I6): the three parity dialogs (Todo / Context-usage) live
    // here in ChatScaffold so the overflow can open them. The dialog body
    // is unchanged from the old ChatTopBar.kt — only the owner moved.
    // §0.8.2 P2.3: the open-triggers now fire from ChatTopBar's overflow
    // menu (onOpenTodoDialog / onOpenContextDialog) instead of the remote
    // ConversationOverflowMenu.
    if (showTodoDialog) {
        // §B4-P3C: Todo sheet 迁移到 AppBottomSheet（与 Context/Agent/Model 统一）。
        // - title 经 recipe 自动 titleLarge + padding(24,8)（原手写 titleMedium 升级）。
        // - skipPartiallyExpanded 默认 true（recipe 固化点①），与其它三个 sheet 一致；
        //   Todo 原是现网唯一半展的 sheet，现按本批决策统一全展。
        // - 容器色 surfaceContainerLow 由 recipe 统一（用户点 5：底色统一）。
        // - 高度：自然高度 + heightIn(max ≈ 屏 0.75) 封顶超长列表；不用 weight(1f)。
        val todoSheetMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.75f).dp
        AppBottomSheet(
            onDismissRequest = { showTodoDialog = false },
            title = stringResource(R.string.chat_todo),
        ) {
            TodoListPanel(
                todos = sessionList.sessionTodos[chat.currentSessionId ?: ""] ?: emptyList(),
                modifier = Modifier.heightIn(max = todoSheetMaxHeight),
            )
        }
    }

    if (showContextDialog) {
        ContextUsageDialog(
            usage = cachedContextUsage,
            onDismiss = { showContextDialog = false },
            onCompact = {
                showContextDialog = false
                chatVM.compactSession()
            },
        )
    }

    // (Snackbar host is rendered inside the chat Surface; UiEvent collection
    // is already wired above.)

    // §PARITY: error-detail dialog. Unchanged from ChatScreen.
    errorDetail?.let { detail ->
        AlertDialog(
            onDismissRequest = { errorDetail = null },
            title = { Text(stringResource(R.string.chat_error_details)) },
            text = {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(text = detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { errorDetail = null }) {
                    Text(stringResource(R.string.common_done))
                }
            }
        )
    }

    // §PARITY: TOFU trust dialog. Unchanged from ChatScreen.
    connection.pendingTofuCapture?.let { capture ->
        TofuTrustDialog(
            capture = capture,
            onDecision = { decision -> connectionVM.resolveTofuTrust(decision) }
        )
    }
}

// §0.8.2 P2.3: the standalone `ConversationOverflowMenu` composable that
// used to live here is REMOVED. The overflow DropdownMenu is now co-located
// with its ContextUsageRing trigger inside ChatTopBar (the fix for the
// top-left popup bug — the prior remote menu had no tight Box anchor).
// §dead-onCompact-cleanup: the 4 items (Context / Todo / Agent / Model) are
// composed inline in `ContextMenuCluster` (ChatTopBar.kt). Compaction is
// triggered via the ContextUsageDialog's own "Compress context" button, NOT
// a standalone overflow item. Archive was dropped (the destructive
// affordance moves to the Sessions screen long-press).
