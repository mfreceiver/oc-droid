package com.yage.opencode_client.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yage.opencode_client.R
import com.yage.opencode_client.data.model.MessageWithParts
import com.yage.opencode_client.data.model.Part
import com.yage.opencode_client.data.model.SessionStatus
import com.yage.opencode_client.ui.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: MainViewModel,
    onNavigateToFiles: (String) -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    showSettingsButton: Boolean = true,
    showSessionListInTopBar: Boolean = true
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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

    // statusBarsPadding() keeps the ChatTopBar below the status bar (the phone
    // Scaffold uses contentWindowInsets = 0). It consumes the inset, so nested
    // TopAppBars in the tablet layout (already padded at the Row level) see 0
    // and never double-pad.
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
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
                showSessionListInTopBar = showSessionListInTopBar,
                hostName = state.currentHostProfile?.name ?: "No Host",
                isConnected = state.isConnected,
                isConnecting = state.isConnecting,
                connectionPhase = state.connectionPhase,
                hostProfiles = state.hostProfiles,
                currentHostProfileId = state.currentHostProfileId,
                tunnelActivationState = state.tunnelActivationState,
                showTunnelAuth = (state.currentHostProfile?.tunnelPasswordId != null),
                parentSessionId = state.currentSession?.parentId,
                parentSessionTitle = state.currentSession?.parentId?.let { pid ->
                    state.sessions.firstOrNull { it.id == pid }?.displayName
                }
            ),
            actions = ChatTopBarActions(
                onSelectSession = viewModel::selectSession,
                onCreateSession = viewModel::createSession,
                onDeleteSession = viewModel::deleteSession,
                onArchiveSession = viewModel::archiveSession,
                onRestoreSession = viewModel::restoreSession,
                onLoadMoreSessions = viewModel::loadMoreSessions,
                onRefreshSessions = viewModel::loadSessions,
                onToggleSessionExpanded = viewModel::toggleSessionExpanded,
                onSelectAgent = viewModel::selectAgent,
                onNavigateToSettings = onNavigateToSettings,
                onRefresh = { viewModel.refreshCurrentHost() },
                onSelectHost = { viewModel.selectHostProfile(it) },
                onActivateTunnel = { viewModel.activateTunnelForCurrentHost() }
            )
        )

        // MRU recent-sessions tag strip. Sub-agents (parentId != null) are
        // excluded — they are only reachable from within a parent conversation.
        // Reads from AppState.recentSessionIds (mirrored from SettingsManager)
        // so long-press removal (which updates AppState) triggers recomposition.
        val recentSessions = remember(state.sessions, state.currentSessionId, state.recentSessionIds) {
            state.recentSessionIds
                .mapNotNull { id -> state.sessions.find { it.id == id } }
                .filter { it.parentId == null }
                .take(8)
        }
        if (recentSessions.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(recentSessions) { session ->
                    val isCurrent = session.id == state.currentSessionId
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = if (isCurrent) 2.dp else 0.dp,
                        modifier = Modifier.combinedClickable(
                            onClick = { viewModel.selectSession(session.id) },
                            onLongClick = { viewModel.removeFromRecent(session.id) }
                        )
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                            Text(
                                text = session.directory.split("/").lastOrNull() ?: session.directory,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = session.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (state.currentSessionId == null) {
                ChatEmptyState(
                    isConnected = state.isConnected,
                    onConnect = { viewModel.testConnection() }
                )
            } else {
                ChatMessageList(
                    messages = state.visibleMessages,
                    streamingPartTexts = state.streamingPartTexts,
                    streamingReasoningPart = state.streamingReasoningPart,
                    isLoading = state.isLoadingMessages,
                    messageLimit = state.messageLimit,
                    repository = viewModel.repository,
                    workspaceDirectory = state.currentSession?.directory,
                    onLoadMore = { viewModel.loadMoreMessages() },
                    onFileClick = onNavigateToFiles,
                    onOpenSubAgent = viewModel::openSubAgent
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

        if (state.currentSessionId != null) {
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
            )
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
