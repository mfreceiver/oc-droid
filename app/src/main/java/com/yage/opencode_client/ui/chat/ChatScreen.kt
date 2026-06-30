package com.yage.opencode_client.ui.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.yage.opencode_client.R
import com.yage.opencode_client.data.model.Message
import com.yage.opencode_client.data.model.Part
import com.yage.opencode_client.data.model.SessionStatus
import com.yage.opencode_client.ui.files.FilesScreen
import com.yage.opencode_client.ui.files.FilesViewModel
import com.yage.opencode_client.ui.MainViewModel
import com.yage.opencode_client.ui.TUNNEL_SUCCESS_TOAST
import com.yage.opencode_client.ui.TunnelActivationState
import com.yage.opencode_client.ui.computeContextUsage
import com.yage.opencode_client.ui.currentHostProfile
import com.yage.opencode_client.ui.currentSession
import com.yage.opencode_client.ui.currentSessionStatus
import com.yage.opencode_client.ui.visibleMessages
import com.yage.opencode_client.ui.common.AppToast
import com.yage.opencode_client.ui.common.ToastSeverity
import com.yage.opencode_client.ui.theme.opencode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: MainViewModel,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToSessions: () -> Unit = {}
) {
    // §R-17 M5: AppState no longer carries the per-domain mirror fields.
    // ChatScreen reads the authoritative slice flows directly. `appState` still
    // carries the cross-domain `error` / `lastNavPage` that remain on AppState.
    val appState by viewModel.state.collectAsStateWithLifecycle()
    val connection by viewModel.connectionFlow.collectAsStateWithLifecycle()
    val traffic by viewModel.trafficFlow.collectAsStateWithLifecycle()
    val composer by viewModel.composerFlow.collectAsStateWithLifecycle()
    val file by viewModel.fileFlow.collectAsStateWithLifecycle()
    val settings by viewModel.settingsFlow.collectAsStateWithLifecycle()
    val chat by viewModel.chatFlow.collectAsStateWithLifecycle()
    val sessionList by viewModel.sessionListFlow.collectAsStateWithLifecycle()
    val unread by viewModel.unreadFlow.collectAsStateWithLifecycle()
    val host by viewModel.hostFlow.collectAsStateWithLifecycle()
    var showFileBrowser by remember { mutableStateOf(false) }
    // §R-17 Stage 2: expandedParts collect moved INTO ChatMessageList (it now
    // subscribes to viewModel.expandedParts directly), so ChatScreen no longer
    // needs to observe it — toggling a card recomposes only ChatMessageList.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        scope.launch {
            viewModel.addImageAttachments(loadImageAttachments(context, uris))
        }
    }

    // §R-17 Stage 2: stable lambda references so ChatMessageList / ChatInputBar
    // (which now collect their own slices) can be SKIPPED by the Compose runtime
    // when their slice inputs are unchanged. Without this, a fresh lambda
    // identity on every ChatScreen recomposition would force both children to
    // recompose regardless of slice state. showFileBrowser is a
    // remember{mutableStateOf} delegate whose setter is referentially stable,
    // so capturing it inside remember(viewModel) is safe.
    val onChatFileClick: (String) -> Unit = remember(viewModel) {
        { path -> viewModel.showFileInFiles(path, "chat"); showFileBrowser = true }
    }
    val onAddImages: () -> Unit = remember(imagePickerLauncher) {
        { imagePickerLauncher.launch("image/*") }
    }

    // Cross-slice derived views (R-17 M5: moved out of the AppState getters).
    val curSession = currentSession(sessionList.sessions, chat.currentSessionId)
    val curSessionStatus = currentSessionStatus(sessionList.sessionStatuses, chat.currentSessionId)
    // Cache last non-null contextUsage so the ring stays visible during streaming
    val computedContextUsage = computeContextUsage(chat.messages, settings.providers)
    var cachedContextUsage by remember { mutableStateOf(computedContextUsage) }
    computedContextUsage?.let { cachedContextUsage = it }
    val currentSessionIsRunning = curSessionStatus?.let { it.isBusy || it.isRetry } == true ||
        chat.currentSessionId?.let { it in composer.sendingSessionIds } == true
    val currentActivity = remember(
        chat.currentSessionId,
        curSessionStatus,
        chat.messages,
        chat.partsByMessage,
        chat.streamingReasoningPart,
        chat.streamingPartTexts,
    ) {
        currentSessionActivity(
            sessionId = chat.currentSessionId,
            status = curSessionStatus,
            messages = visibleMessages(chat.messages, curSession),
            partsByMessage = chat.partsByMessage,
            streamingReasoningPart = chat.streamingReasoningPart,
            streamingPartTexts = chat.streamingPartTexts,
        )
    }


    // #14: edge-swipe / system-back returns to the parent session when viewing a
    // child. Registered here (deeper than PhoneLayout's pager-level BackHandler
    // from #12) so it wins dispatch priority while a child session is open.
    // lastParent caches the most recent non-null parentId so the back callback
    // still resolves the right target even if state recomposes mid-press.
    val parent = curSession?.parentId
    var lastParent by remember { mutableStateOf<String?>(null) }
    if (parent != null) lastParent = parent
    BackHandler(enabled = parent != null) { lastParent?.let { viewModel.selectSession(it) } }

    // Status-bar inset is handled by ChatTopBar's M3 TopAppBar (its default
    // TopAppBarDefaults.windowInsets consumes the status bar inset), so this
    // Column must NOT also apply statusBarsPadding() — that would double-pad.
    // If ChatTopBar ever reverts to not handling the inset, re-add
    // .statusBarsPadding() here.
    // R-17 M1: derive ChatTopBarState inside a remembered derivedStateOf.
    // The lambda only reads the AppState slices that ChatTopBar actually
    // consumes (sessions / currentSessionId / statuses / agents / cached
    // contextUsage / connection / host profiles / tunnel / tabs / traffic /
    // serverVersion). It deliberately does NOT read the high-frequency SSE
    // fields (streamingPartTexts / visibleMessages / partsByMessage /
    // streamingReasoningPart / inputText). An SSE token delta mutates only
    // those fields, which makes the State<AppState> emit a new value and
    // forces this block to re-evaluate, but the resulting ChatTopBarState is
    // structurally equal to the previous one (data-class equals) — so
    // derivedStateOf suppresses the change and ChatTopBar is skipped.
    val curHostProfile = currentHostProfile(host.hostProfiles, host.currentHostProfileId)
    val topBarState by remember {
        derivedStateOf {
            // Resolve openSessionIds to actual Session objects for the
            // top-bar tab strip. Filtered to root sessions (parentId == null)
            // so sub-agents never duplicate the title-slot breadcrumb.
            val resolvedOpenSessions = sessionList.openSessionIds
                .mapNotNull { id -> sessionList.sessions.find { it.id == id } }
                .filter { it.parentId == null }
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
                hostName = curHostProfile?.name ?: "No Host",
                isConnected = connection.isConnected,
                isConnecting = connection.isConnecting,
                connectionPhase = connection.connectionPhase,
                hostProfiles = host.hostProfiles,
                currentHostProfileId = host.currentHostProfileId,
                tunnelActivationState = connection.tunnelActivationState,
                showTunnelAuth = (curHostProfile?.tunnelPasswordId != null),
                openSessions = resolvedOpenSessions,
                unreadSessions = unread.unreadSessions,
                draftWorkdir = composer.draftWorkdir,
                parentSessionId = curSession?.parentId,
                parentSessionTitle = curSession?.parentId?.let { pid ->
                    sessionList.sessions.firstOrNull { it.id == pid }?.displayName
                },
                trafficSent = traffic.trafficSent,
                trafficReceived = traffic.trafficReceived,
                serverVersion = connection.serverVersion
            )
        }
    }

    // R-17 M1 R1: wrap ChatTopBarActions in remember so the actions instance
    // (and its lambda / method-reference fields) stays referentially stable
    // across recompositions. Without this the actions argument was a fresh
    // instance on every recomposition and defeated the topBarState
    // derivedStateOf — ChatTopBar recomposed regardless of whether topBarState
    // had changed. Keys: viewModel is a @Stable hilt single-instance VM (stable
    // for the screen's lifetime); onNavigateToSettings / onNavigateToSessions
    // are nav lambdas sourced from the parent, listed as keys so a new parent
    // identity re-builds the actions bundle. The VM method references and the
    // wrapping lambdas only capture viewModel (itself stable), so they do not
    // need to be keys.
    val topBarActions = remember(viewModel, onNavigateToSettings, onNavigateToSessions) {
        ChatTopBarActions(
            onSelectSession = viewModel::selectSession,
            onCloseSession = viewModel::closeSession,
            onSelectAgent = viewModel::selectAgent,
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToSessions = onNavigateToSessions,
            onRefreshMessages = { viewModel.refreshCurrentSession() },
            onRefreshTrafficStats = viewModel::refreshTrafficStats,
            onSelectHost = { viewModel.selectHostProfile(it) },
            onActivateTunnel = { viewModel.activateTunnelForCurrentHost() },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ChatTopBar(
            state = topBarState,
            actions = topBarActions
        )

        // Thin separator between the top bar (session tabs) and the chat area.
        // On phone the chat-area Surface has 0dp shadow elevation, so without
        // this divider the boundary reads as ambiguous.
        HorizontalDivider(color = MaterialTheme.opencode.borderBase)

        // In draft mode (no session yet but a workdir has been chosen), the
        // chat area is intentionally empty — the user is mid-composition and
        // the session will materialise on first send. We do not render the
        // "select or create session" empty state in that case.
        // §10: on wide screens (≥600dp) wrap the conversation area (messages +
        // composer) in a v2-style card — rounded 10, surface (bg-base), 2dp
        // elevation, 8dp outer padding. On phone (<600dp) the chat stays
        // full-bleed for maximum display area (RectangleShape + transparent +
        // 0 elevation ⇒ an invisible, non-clipping wrapper). The TopBar stays
        // outside the card either way (a full-width top bar is more natural on
        // mobile and avoids double-rounded corners at the top).
        val isWide = LocalConfiguration.current.screenWidthDp >= 600
        val cardShape = if (isWide) RoundedCornerShape(10.dp) else RectangleShape
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (isWide) Modifier.padding(8.dp) else Modifier),
            color = MaterialTheme.colorScheme.surface,
            shape = cardShape,
            shadowElevation = if (isWide) 2.dp else 0.dp,
            tonalElevation = if (isWide) 1.dp else 0.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
            val isDraft = composer.draftWorkdir != null && chat.currentSessionId == null
            if (chat.currentSessionId == null && !isDraft) {
                ChatEmptyState(
                    isConnected = connection.isConnected,
                    isConnecting = connection.isConnecting,
                    connectionPhase = connection.connectionPhase,
                    hostName = curHostProfile?.name
                        ?: curHostProfile?.serverUrl
                            ?.substringAfter("://")
                            ?.substringBefore("/")
                            ?: "server",
                    onConnect = { viewModel.testConnection() }
                )
            } else if (chat.currentSessionId != null) {
                ChatMessageList(
                    viewModel = viewModel,
                    onFileClick = onChatFileClick
                )
            }

            // §Phase1E stale notice banner — shown after a long (>5min)
            // background absence. Positioned at TopCenter alongside the error
            // Snackbar but rendered first so the error (if any) stacks below.
            if (chat.staleNotice && chat.currentSessionId != null) {
                StaleNoticeBanner(
                    onReload = { viewModel.refreshCurrentSession() },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp, start = 16.dp, end = 16.dp)
                )
            }

            appState.error?.let { error ->
                // Toast rendered at the TOP of the chat area so tunnel
                // activation feedback (and any other error) surfaces in the upper
                // region of the interface instead of being buried at the bottom.
                // Severity follows the message identity, not tunnelActivationState
                // (which is sticky and would poison subsequent errors as "Success").
                val severity =
                    if (error == TUNNEL_SUCCESS_TOAST) ToastSeverity.Success
                    else ToastSeverity.Error
                AppToast(
                    message = error,
                    severity = severity,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
                    onDismiss = { viewModel.clearError() }
                )
            }
        }

        // Input bar is enabled whenever there is either a concrete session OR
        // a draft workdir (so the user can type the first message that will
        // materialise the session).
        if (chat.currentSessionId != null || composer.draftWorkdir != null) {
            ChatInputBar(
                viewModel = viewModel,
                isBusy = currentSessionIsRunning,
                agentActivityText = currentActivity?.text,
                agentStartedAtMillis = currentActivity?.startedAtMillis,
                onAddImages = onAddImages
            )
        }
            }
        }

        sessionList.pendingPermissions.firstOrNull()?.let { permission ->
            ChatPermissionCard(
                permission = permission,
                onRespond = { response ->
                    viewModel.respondPermission(permission.sessionId, permission.id, response)
                }
            )
        }

        sessionList.pendingQuestions
            .filter { it.sessionId == chat.currentSessionId }
            .firstOrNull()
            ?.let { question ->
                QuestionCardView(
                    question = question,
                    onReply = { answers, onError -> viewModel.replyQuestion(question.id, answers, onError) },
                    onReject = { viewModel.rejectQuestion(question.id) }
                )
            }
    }

    // File browser overlay — opened by tapping a file path in chat (preview).
    // (Project-level browsing lives on the Sessions screen now.) The Box is
    // given an opaque surface background so the chat content underneath does
    // not bleed through (FilesScreen's own root is transparent by design —
    // opaqueness is the host's responsibility).
    if (showFileBrowser) {
        val filesViewModel: FilesViewModel = hiltViewModel()
        // System back closes the in-chat file preview instead of exiting the
        // app (the parent-session BackHandler at L104 is gated on parent!=null,
        // and PhoneLayout's is disabled on the Chat destination, so without
        // this a root-session file preview would back-exit the app).
        BackHandler { showFileBrowser = false }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            FilesScreen(
                viewModel = filesViewModel,
                pathToShow = file.filePathToShowInFiles,
                sessionDirectory = curSession?.directory,
                onCloseFile = {
                    viewModel.clearFileToShow()
                    showFileBrowser = false
                },
                onFileClick = { path ->
                    viewModel.showFileInFiles(path, "chat")
                }
            )
        }
    }
}

/**
 * §Phase1E stale notice banner — a top-aligned informational bar shown after
 * the app returns from a long (>5min) background absence. Quiet Tech styling:
 * neutral surfaceVariant background, thin borderBase outline, inline text
 * button for the reload action. The banner is transient — tapping the action
 * triggers a full cold-start refresh that clears [AppState.staleNotice].
 */
@Composable
private fun StaleNoticeBanner(
    onReload: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "长时间未查看，仅显示最新内容。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onReload) {
                Text(
                    text = "重新加载全部会话",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private data class CurrentSessionActivity(
    val text: String,
    val startedAtMillis: Long?,
)

private fun currentSessionActivity(
    sessionId: String?,
    status: SessionStatus?,
    messages: List<Message>,
    partsByMessage: Map<String, List<Part>>,
    streamingReasoningPart: Part?,
    streamingPartTexts: Map<String, String>,
): CurrentSessionActivity? {
    val sid = sessionId ?: return null
    val startedAt = messages.lastOrNull { it.sessionId == sid && it.isUser }?.time?.created
    val text = bestSessionActivityText(sid, status, messages, partsByMessage, streamingReasoningPart, streamingPartTexts)
    return CurrentSessionActivity(text = text, startedAtMillis = startedAt)
}

private fun bestSessionActivityText(
    sessionId: String,
    status: SessionStatus?,
    messages: List<Message>,
    partsByMessage: Map<String, List<Part>>,
    streamingReasoningPart: Part?,
    streamingPartTexts: Map<String, String>,
): String {
    status?.message?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

    messages.asReversed().forEach { message ->
        if (message.sessionId != sessionId) return@forEach
        (partsByMessage[message.id] ?: emptyList()).asReversed()
            .firstOrNull { it.isTool && it.stateDisplay == "running" }
            ?.let { part -> formatStatusFromPart(part)?.let { return it } }
    }

    if (streamingReasoningPart?.sessionId == sessionId) {
        val key = streamingReasoningPart.id
        return formatThinkingFromReasoningText(streamingPartTexts[key].orEmpty())
    }

    messages.asReversed().forEach { message ->
        if (message.sessionId != sessionId) return@forEach
        (partsByMessage[message.id] ?: emptyList()).asReversed().firstOrNull()?.let { part ->
            formatStatusFromPart(part)?.let { return it }
        }
    }

    return status?.takeIf { it.isRetry }?.let { "Retrying" } ?: "Thinking"
}

private fun formatStatusFromPart(part: Part): String? {
    if (part.isTool) {
        val base = when (part.tool) {
            "task" -> "Delegating"
            "todowrite", "todoread" -> "Planning"
            "read" -> "Gathering context"
            "list", "grep", "glob" -> "Searching codebase"
            "webfetch" -> "Searching web"
            "edit", "write" -> "Making edits"
            "bash" -> "Running commands"
            else -> null
        }
        val topic = (part.toolReason ?: part.toolInputSummary)?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            base != null && topic != null -> "$base - $topic"
            base != null -> base
            else -> null
        }
    }

    if (part.isReasoning) return formatThinkingFromReasoningText(part.text.orEmpty())
    if (part.isText) return "Gathering thoughts"
    return null
}

private fun formatThinkingFromReasoningText(text: String): String {
    val topic = Regex("^\\*\\*([^*]+)\\*\\*").find(text.trim())?.groupValues?.getOrNull(1)?.trim()
    return if (!topic.isNullOrEmpty()) "Thinking - $topic" else "Thinking"
}
