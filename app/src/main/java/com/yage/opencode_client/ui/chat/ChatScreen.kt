package com.yage.opencode_client.ui.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.yage.opencode_client.R
import com.yage.opencode_client.data.model.MessageWithParts
import com.yage.opencode_client.data.model.Part
import com.yage.opencode_client.data.model.SessionStatus
import com.yage.opencode_client.ui.files.FilesScreen
import com.yage.opencode_client.ui.files.FilesViewModel
import com.yage.opencode_client.ui.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: MainViewModel,
    onNavigateToSettings: () -> Unit = {},
    showSettingsButton: Boolean = true
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showFileBrowser by remember { mutableStateOf(false) }
    // #13: per-part expand/collapse state lives in its own StateFlow (not in
    // AppState) so toggling a card doesn't recompose the whole ChatScreen.
    val expandedParts by viewModel.expandedParts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        scope.launch {
            viewModel.addImageAttachments(loadImageAttachments(context, uris))
        }
    }

    // Cache last non-null contextUsage so the ring stays visible during streaming
    var cachedContextUsage by remember { mutableStateOf(state.contextUsage) }
    state.contextUsage?.let { cachedContextUsage = it }
    val currentSessionIsRunning = state.currentSessionStatus?.let { it.isBusy || it.isRetry } == true ||
        state.currentSessionId?.let { it in state.sendingSessionIds } == true
    val currentActivity = remember(
        state.currentSessionId,
        state.currentSessionStatus,
        state.visibleMessages,
        state.streamingReasoningPart,
        state.streamingPartTexts,
    ) {
        currentSessionActivity(
            sessionId = state.currentSessionId,
            status = state.currentSessionStatus,
            messages = state.visibleMessages,
            streamingReasoningPart = state.streamingReasoningPart,
            streamingPartTexts = state.streamingPartTexts,
        )
    }

    // Resolve openSessionIds to actual Session objects for the top-bar dropdown.
    val openSessions = remember(state.sessions, state.openSessionIds) {
        state.openSessionIds
            .mapNotNull { id -> state.sessions.find { it.id == id } }
            .filter { it.parentId == null }
    }

    // #14: edge-swipe / system-back returns to the parent session when viewing a
    // child. Registered here (deeper than PhoneLayout's pager-level BackHandler
    // from #12) so it wins dispatch priority while a child session is open.
    // lastParent caches the most recent non-null parentId so the back callback
    // still resolves the right target even if state recomposes mid-press.
    val parent = state.currentSession?.parentId
    var lastParent by remember { mutableStateOf<String?>(null) }
    if (parent != null) lastParent = parent
    BackHandler(enabled = parent != null) { lastParent?.let { viewModel.selectSession(it) } }

    // Status-bar inset is handled by ChatTopBar's M3 TopAppBar (its default
    // TopAppBarDefaults.windowInsets consumes the status bar inset), so this
    // Column must NOT also apply statusBarsPadding() — that would double-pad.
    // If ChatTopBar ever reverts to not handling the inset, re-add
    // .statusBarsPadding() here.
    Column(modifier = Modifier.fillMaxSize()) {
        ChatTopBar(
            state = ChatTopBarState(
                sessions = state.sessions,
                currentSessionId = state.currentSessionId,
                sessionStatuses = state.sessionStatuses,
                hasMoreSessions = state.hasMoreSessions,
                isLoadingMoreSessions = state.isLoadingMoreSessions,
                isRefreshingSessions = state.isRefreshingSessions,
                expandedSessionIds = state.expandedSessionIds,
                agents = state.visibleAgents,
                selectedAgentName = state.selectedAgentName,
                contextUsage = cachedContextUsage,
                sessionTodos = state.sessionTodos[state.currentSessionId ?: ""] ?: emptyList(),
                showSettingsButton = showSettingsButton,
                hostName = state.currentHostProfile?.name ?: "No Host",
                isConnected = state.isConnected,
                isConnecting = state.isConnecting,
                connectionPhase = state.connectionPhase,
                hostProfiles = state.hostProfiles,
                currentHostProfileId = state.currentHostProfileId,
                tunnelActivationState = state.tunnelActivationState,
                showTunnelAuth = (state.currentHostProfile?.tunnelPasswordId != null),
                openSessions = openSessions,
                unreadSessions = state.unreadSessions,
                draftWorkdir = state.draftWorkdir,
                parentSessionId = state.currentSession?.parentId,
                parentSessionTitle = state.currentSession?.parentId?.let { pid ->
                    state.sessions.firstOrNull { it.id == pid }?.displayName
                }
            ),
            actions = ChatTopBarActions(
                onSelectSession = viewModel::selectSession,
                onCloseSession = viewModel::closeSession,
                onSelectAgent = viewModel::selectAgent,
                onNavigateToSettings = onNavigateToSettings,
                onRefresh = { viewModel.refreshCurrentHost() },
                onSelectHost = { viewModel.selectHostProfile(it) },
                onActivateTunnel = { viewModel.activateTunnelForCurrentHost() },
                onNavigateToFiles = {
                    state.currentSession?.directory?.let { dir ->
                        viewModel.showFileInFiles(dir, "chat")
                    }
                    showFileBrowser = true
                }
            )
        )

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
            val isDraft = state.draftWorkdir != null && state.currentSessionId == null
            if (state.currentSessionId == null && !isDraft) {
                ChatEmptyState(
                    isConnected = state.isConnected,
                    onConnect = { viewModel.testConnection() }
                )
            } else if (state.currentSessionId != null) {
                ChatMessageList(
                    messages = state.visibleMessages,
                    streamingPartTexts = state.streamingPartTexts,
                    streamingReasoningPart = state.streamingReasoningPart,
                    isLoading = state.isLoadingMessages,
                    messageLimit = 30,
                    repository = viewModel.repository,
                    workspaceDirectory = state.currentSession?.directory,
                    onLoadMore = { viewModel.loadMoreMessages() },
                    onFileClick = { path ->
                        viewModel.showFileInFiles(path, "chat")
                        showFileBrowser = true
                    },
                    onOpenSubAgent = viewModel::openSubAgent,
                    expandedParts = expandedParts,
                    onToggleExpand = viewModel::togglePartExpand
                )
            }

            state.error?.let { error ->
                // Toast/snackbar rendered at the TOP of the chat area so tunnel
                // activation feedback (and any other error) surfaces in the upper
                // region of the interface instead of being buried at the bottom.
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
                    action = {
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text(stringResource(R.string.common_dismiss))
                        }
                    }
                ) {
                    Text(error)
                }
            }
        }

        // Input bar is enabled whenever there is either a concrete session OR
        // a draft workdir (so the user can type the first message that will
        // materialise the session).
        if (state.currentSessionId != null || state.draftWorkdir != null) {
            ChatInputBar(
                text = state.inputText,
                isBusy = currentSessionIsRunning,
                agentActivityText = currentActivity?.text,
                agentStartedAtMillis = currentActivity?.startedAtMillis,
                imageAttachments = state.imageAttachments,
                onTextChange = viewModel::setInputText,
                onSend = { viewModel.sendMessage() },
                onAddImages = { imagePickerLauncher.launch("image/*") },
                onRemoveImage = viewModel::removeImageAttachment,
                onAbort = { viewModel.abortSession() },
                availableCommands = state.availableCommands,
                onExecuteCommand = { command, arguments ->
                    viewModel.executeCommand(command, arguments)
                }
            )
        }
            }
        }

        state.pendingPermissions.firstOrNull()?.let { permission ->
            ChatPermissionCard(
                permission = permission,
                onRespond = { response ->
                    viewModel.respondPermission(permission.sessionId, permission.id, response)
                }
            )
        }

        state.pendingQuestions
            .filter { it.sessionId == state.currentSessionId }
            .firstOrNull()
            ?.let { question ->
                QuestionCardView(
                    question = question,
                    onReply = { answers, onError -> viewModel.replyQuestion(question.id, answers, onError) },
                    onReject = { viewModel.rejectQuestion(question.id) }
                )
            }
    }

    // File browser overlay — replaces the former Files tab. Opened via the
    // folder icon in the session tab strip, or by tapping a file path in chat.
    if (showFileBrowser) {
        val filesViewModel: FilesViewModel = hiltViewModel()
        Box(modifier = Modifier.fillMaxSize()) {
            FilesScreen(
                viewModel = filesViewModel,
                pathToShow = state.filePathToShowInFiles,
                sessionDirectory = state.currentSession?.directory,
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

private data class CurrentSessionActivity(
    val text: String,
    val startedAtMillis: Long?,
)

private fun currentSessionActivity(
    sessionId: String?,
    status: SessionStatus?,
    messages: List<MessageWithParts>,
    streamingReasoningPart: Part?,
    streamingPartTexts: Map<String, String>,
): CurrentSessionActivity? {
    val sid = sessionId ?: return null
    val startedAt = messages.lastOrNull { it.info.sessionId == sid && it.info.isUser }?.info?.time?.created
    val text = bestSessionActivityText(sid, status, messages, streamingReasoningPart, streamingPartTexts)
    return CurrentSessionActivity(text = text, startedAtMillis = startedAt)
}

private fun bestSessionActivityText(
    sessionId: String,
    status: SessionStatus?,
    messages: List<MessageWithParts>,
    streamingReasoningPart: Part?,
    streamingPartTexts: Map<String, String>,
): String {
    status?.message?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

    messages.asReversed().forEach { message ->
        if (message.info.sessionId != sessionId) return@forEach
        message.parts.asReversed().firstOrNull { it.isTool && it.stateDisplay == "running" }
            ?.let { part -> formatStatusFromPart(part)?.let { return it } }
    }

    if (streamingReasoningPart?.sessionId == sessionId) {
        val key = "${streamingReasoningPart.messageId}:${streamingReasoningPart.id}"
        return formatThinkingFromReasoningText(streamingPartTexts[key].orEmpty())
    }

    messages.asReversed().forEach { message ->
        if (message.info.sessionId != sessionId) return@forEach
        message.parts.asReversed().firstOrNull()?.let { part ->
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
