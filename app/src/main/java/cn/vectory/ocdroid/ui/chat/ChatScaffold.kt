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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import cn.vectory.ocdroid.ui.resolveMessage
import cn.vectory.ocdroid.ui.showTimed
import cn.vectory.ocdroid.ui.visibleMessages
import cn.vectory.ocdroid.ui.settings.TofuTrustDialog
import kotlinx.coroutines.delay
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
    /**
     * §round-B ② (D.5): the active host's recent workdirs. Fed into
     * [ContextSelectorSheet] (replaces the previous
     * `sessionList.sessions.map { it.directory }` source — recent_workdirs
     * is the single source of truth for "connected" and includes workdirs
     * the user has connected to but not yet opened a session in).
     */
    recentWorkdirs: List<String> = emptyList(),
    /** §round-B ② (D.5): "Manage hosts" entry handler — routes to
     *  Settings → Hosts (ServerManagement parity). */
    onManageHosts: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onNavigateToSessions: () -> Unit = {},
    /**
     * §phase2-unbreak: open Workspace Files at the current session's workdir,
     * locating the specific tapped file path when present. ChatScaffold
     * derives workdir from the current session and passes BOTH down — the
     * previous fix-6 shape dropped the path and passed only the session
     * directory, which broke file-path navigation (FilesPane.pathToShow was
     * always null).
     */
    onOpenWorkspaceFiles: (workdir: String?, path: String?) -> Unit = { _, _ -> },
    onOpenWorkspaceChanges: (String) -> Unit = {},
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
    var showSessionPicker by rememberSaveable { mutableStateOf(false) }
    var showContextSelector by rememberSaveable { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
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
    // §phase2-unbreak: file-path tap passes the ACTUAL tapped path through to
    // the Workspace Files route — the previous fix-6 code discarded the path
    // (`{ onOpenWorkspaceFiles(curSession?.directory.orEmpty()) }`) which
    // broke FilesPane.pathToShow (always null) so a tapped file path could
    // never be located. Now workdir comes from the current session and the
    // tapped path is forwarded verbatim. AppShell (the sole shell; the
    // legacy PhoneLayout was removed in the redesign) builds the route /
    // overlay with both fields.
    val onChatFileClick: (String) -> Unit = remember(curSession, onOpenWorkspaceFiles) {
        { path -> onOpenWorkspaceFiles(curSession?.directory, path) }
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
    val matchingQuestions = remember(sessionList.pendingQuestions, chat.currentSessionId) {
        sessionList.pendingQuestions.filter { it.sessionId == chat.currentSessionId }
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
                        durationMillis = 10_000L,
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

    // §1B: derive ChatTopBarState inside a remembered derivedStateOf so the
    // new TopAppBar recomposes only when its slice inputs change. Slice reads
    // match the old ChatTopBarState (see ChatTopBar.kt) — additive only, the
    // second-row `SessionTabStrip` rendering was removed from the new
    // TopAppBar (session switching is now via the sheet).
    val curHostProfile = currentHostProfile(host.hostProfiles, host.currentHostProfileId)
    // §Nit: hoist the localised "No host" fallback outside the
    // derivedStateOf lambda (Compose forbids @Composable invocations
    // inside non-composable lambdas like derivedStateOf).
    val noHostFallback = stringResource(R.string.chat_no_host_fallback)
    val topBarState by remember(noHostFallback) {
        derivedStateOf {
            val curHostProfile = currentHostProfile(host.hostProfiles, host.currentHostProfileId)
            val curSession = currentSession(sessionList.sessions, chat.currentSessionId)
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
                questionSessionIds = sessionList.pendingQuestions.map { it.sessionId }.toSet(),
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
                currentModel = chat.currentModel
            )
        }
    }
    val topBarActions = remember(
        sessionVM,
        composerVM,
        connectionVM,
        onNavigateToSettings,
    ) {
        ChatTopBarActions(
            onSelectSession = sessionVM::selectSession,
            onCloseSession = sessionVM::closeSession,
            onSelectAgent = composerVM::selectAgent,
            onNavigateToSettings = onNavigateToSettings,
            onOpenSessionPicker = { showSessionPicker = true },
            onOpenContextSelector = { showContextSelector = true },
            onOpenOverflow = { showOverflow = true },
            onRefreshMessages = { chatVM.refreshCurrentSession() },
            onRefreshTrafficStats = connectionVM::refreshTrafficStats,
            onSelectHost = { hostVM.selectHostProfile(it) },
            onActivateTunnel = { hostVM.activateTunnelForCurrentHost() },
            onSwitchModel = { providerId, modelId -> composerVM.switchSessionModel(providerId, modelId) },
        )
    }
    val onContextCompact = remember(chatVM) { { chatVM.compactSession() } }

    Column(modifier = Modifier.fillMaxSize()) {
        ChatTopBar(
            state = topBarState,
            actions = topBarActions,
            // §1B: tabVisible is now decorative (no second-row strip is
            // rendered); ChatMessageList still calls onTabVisibilityChange
            // but the callback is a no-op stub.
            tabVisible = true,
            onTabVisibilityChange = { /* no second-row strip in 1B */ },
            onContextCompact = onContextCompact,
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
                    // §1B: the HorizontalPager is REMOVED. The single
                    // ChatMessageList is rendered directly — session switching
                    // now goes through SessionPickerSheet (P5-3). The pager's
                    // adjacent-session guard (CircularProgressIndicator) is
                    // gone with the pager: a fresh selectSession call into
                    // ChatMessageList does not need a "loading neighbour"
                    // affordance because the new view no longer pre-renders
                    // adjacent sessions.
                    val isDraft = composer.draftWorkdir != null && chat.currentSessionId == null
                    if (chat.currentSessionId != null) {
                        // §1C: wire the message-row overflow callbacks. The
                        // three callbacks are the ONLY non-default
                        // destructives in the chat surface — every one
                        // routes to an existing domain method:
                        //   Copy      → system clipboard (default; the
                        //                message-card helper does the
                        //                ClipData work)
                        //   Edit & rerun → chatVM.editFromMessage
                        //                (= Phase 0 RevertConversation
                        //                use case; never bypassed)
                        //   Fork      → sessionVM.forkSession(id, id)
                        ChatMessageList(
                            chatVM = chatVM,
                            composerVM = composerVM,
                            sessionVM = sessionVM,
                            onFileClick = onChatFileClick,
                            onOpenChanges = onOpenWorkspaceChanges,
                            onTabVisibilityChange = { /* no second-row strip */ },
                            savedPositions = savedPositions,
                            accessOrder = accessOrder,
                            onCopyMessage = { _, text ->
                                copyToSystemClipboard(context, text)
                            },
                            onEditAndRerun = { messageId ->
                                // §1C-DESTRUCTIVE-GATE: this callback
                                // only fires AFTER the user has confirmed
                                // the destructive action in MessageCard's
                                // own AlertDialog. MessageCard.kt's class
                                // doc spells out the gate. The single
                                // entry into the destructive use case is
                                // ChatViewModel.editFromMessage — never
                                // re-implemented here.
                                chatVM.editFromMessage(messageId)
                            },
                            onFork = { messageId ->
                                sessionVM.forkSession(
                                    sessionId = chat.currentSessionId!!,
                                    messageId = messageId,
                                )
                            },
                            // §phase2-parity: pass the narrow sending bool
                            // (derived once above from composerFlow) so
                            // ChatMessageList does NOT subscribe to
                            // composerFlow. Typing no longer recomposes the
                            // message list.
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
                            onConnect = { connectionVM.testConnection() }
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

    if (showSessionPicker) {
        // §1B: the ModalBottomSheet session picker (D.4). Receives the same
        // sessionList shape the old SessionTabStrip used, plus the unread /
        // pending-question set for the per-row indicators. Selection goes
        // through `sessionVM.selectSession` (same domain path the old
        // SessionTabStrip used). Archive / unarchive is wired through
        // `sessionVM.archiveSession` / `restoreSession` (P4-4 — replaces the
        // old long-press gesture with an explicit overflow menu).
        SessionPickerSheet(
            sessions = sessionList.sessions,
            sessionStatuses = sessionList.sessionStatuses,
            currentSessionId = chat.currentSessionId,
            unreadSessions = unread.unreadSessions,
            questionSessionIds = sessionList.pendingQuestions.map { it.sessionId }.toSet(),
            onSelect = { id ->
                showSessionPicker = false
                sessionVM.selectSession(id)
            },
            onArchive = { id ->
                showSessionPicker = false
                sessionVM.archiveSession(id)
            },
            onUnarchive = { id ->
                showSessionPicker = false
                sessionVM.restoreSession(id)
            },
            // §1B-FIX (B2): the "New session" FAB used to no-op whenever
            // `composer.draftWorkdir == null` (the normal state — only draft
            // mode sets the field). Fall back to the persisted
            // `settingsManager.currentWorkdir` so the FAB always creates a
            // session in some workdir, matching the legacy ChatInputBar /
            // "new session" affordance. If neither is set we still no-op
            // (nothing to materialize against).
            onNewSession = {
                showSessionPicker = false
                val targetWorkdir = composer.draftWorkdir
                    ?: orchestratorVM.core.settingsManager.currentWorkdir
                targetWorkdir?.let { sessionVM.createSessionInWorkdir(it) }
            },
            onDismiss = { showSessionPicker = false }
        )
    }

    if (showContextSelector) {
        ContextSelectorSheet(
            profiles = host.hostProfiles,
            currentProfileId = host.currentHostProfileId,
            recentWorkdirs = recentWorkdirs,
            currentWorkdir = curSession?.directory ?: composer.draftWorkdir,
            onSelectHost = { id ->
                showContextSelector = false
                hostVM.selectHostProfile(id)
            },
            onSelectWorkdir = { directory ->
                showContextSelector = false
                // §round-B ② (scheme D.5): explicit PRESERVE_CURRENT vs
                // MATERIALIZE_DRAFT decision — the previous shell picked
                // the FIRST session in the directory (arbitrary sibling),
                // which broke session scoping by silently swapping the
                // current conversation. The pure helper centralizes the
                // rule (also unit-tested).
                when (resolveWorkdirSelection(curSession?.directory, directory)) {
                    WorkdirAction.PRESERVE_CURRENT -> Unit // already scoped — no-op
                    WorkdirAction.MATERIALIZE_DRAFT -> sessionVM.createSessionInWorkdir(directory)
                }
            },
            onManageHosts = {
                showContextSelector = false
                onNavigateToSettings()
                onManageHosts()
            },
            onDismiss = { showContextSelector = false },
        )
    }

    if (showOverflow) {
        // §1B-FIX (I6): the conversation overflow restores the three
        // parity entry points that the old ChatTopBar had via the
        // ContextMenuButton (folded out in Phase 1B) — Compact / Todo /
        // Context-usage. All three wire to existing handlers / dialogs
        // (no new business logic): Compact → chatVM.compactSession, Todo →
        // the existing TodoListPanel via ChatTopBar's showTodoDialog,
        // Context-usage → the existing ContextUsageDialog. The
        // destructive "Archive" action stays at the bottom of the
        // overflow (P4-4 — destructive actions last, in error colour).
        ConversationOverflowMenu(
            expanded = showOverflow,
            onDismiss = { showOverflow = false },
            onOpenTodo = {
                showOverflow = false
                showTodoDialog = true
            },
            onOpenContext = {
                showOverflow = false
                showContextDialog = true
            },
            onCompact = {
                showOverflow = false
                onContextCompact()
            },
            onArchive = {
                showOverflow = false
                curSession?.id?.let(sessionVM::archiveSession)
            },
        )
    }

    // §1B-FIX (I6): the three parity dialogs (Todo / Context-usage) live
    // here in ChatScaffold so the overflow can open them. The dialog body
    // is unchanged from the old ChatTopBar.kt — only the owner moved.
    if (showTodoDialog) {
        AlertDialog(
            onDismissRequest = { showTodoDialog = false },
            title = { Text(stringResource(R.string.chat_todo)) },
            text = {
                TodoListPanel(
                    todos = sessionList.sessionTodos[chat.currentSessionId ?: ""] ?: emptyList(),
                    modifier = Modifier.heightIn(max = 400.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = { showTodoDialog = false }) {
                    Text(stringResource(R.string.common_done))
                }
            }
        )
    }

    if (showContextDialog) {
        ContextUsageDialog(
            usage = cachedContextUsage,
            onDismiss = { showContextDialog = false },
            onCompact = {
                showContextDialog = false
                onContextCompact()
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

// Phase 1B (D.5): the conversation overflow menu surfaced from the new
// TopAppBar. §1B-FIX (I6): the three parity entry points (Compact /
// Todo / Context-usage) are restored here — each wires to an existing
// handler / dialog the old ChatTopBar had. "Archive" stays at the
// bottom in error colour (P4-4 — destructive actions last). The
// destructive operations never live alongside the agent / model pickers
// in the same menu (those live in the composer's chips), so the
// conversation-level overflow stays focused on session-wide actions.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpenTodo: () -> Unit,
    onOpenContext: () -> Unit,
    onCompact: () -> Unit,
    onArchive: () -> Unit,
) {
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_compact_session)) },
            leadingIcon = {
                Icon(
                    Icons.Default.Compress,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = onCompact,
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_todo)) },
            leadingIcon = {
                Icon(
                    Icons.Default.Checklist,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = onOpenTodo,
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_context)) },
            leadingIcon = {
                Icon(
                    Icons.Default.DonutLarge,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = onOpenContext,
        )
        androidx.compose.material3.DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.sessions_archive),
                    color = MaterialTheme.colorScheme.error
                )
            },
            onClick = onArchive,
        )
    }
}
