package com.yage.opencode_client.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yage.opencode_client.R
import com.yage.opencode_client.data.model.AgentInfo
import com.yage.opencode_client.data.model.HostProfile
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.model.SessionStatus
import com.yage.opencode_client.data.model.TodoItem
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.TunnelActivationState
import com.yage.opencode_client.ui.theme.BrandGold
import java.util.Locale

internal data class ChatTopBarState(
    val sessions: List<Session>,
    val currentSessionId: String?,
    val sessionStatuses: Map<String, SessionStatus>,
    val hasMoreSessions: Boolean,
    val isLoadingMoreSessions: Boolean,
    val isRefreshingSessions: Boolean = false,
    val expandedSessionIds: Set<String> = emptySet(),
    val agents: List<AgentInfo>,
    val selectedAgentName: String,
    val contextUsage: AppState.ContextUsage?,
    val sessionTodos: List<TodoItem> = emptyList(),
    val showSettingsButton: Boolean = true,
    val hostName: String = "",
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val connectionPhase: String? = null,
    val hostProfiles: List<HostProfile> = emptyList(),
    val currentHostProfileId: String? = null,
    val tunnelActivationState: TunnelActivationState = TunnelActivationState.Idle,
    val showTunnelAuth: Boolean = false,
    /**
     * "Open" sessions (browser-tab list) rendered in the second-row dropdown.
     * Pre-resolved from [AppState.openSessionIds] by the caller.
     */
    val openSessions: List<Session> = emptyList(),
    /**
     * Session IDs with unread activity (an out-of-band message.created arrived
     * while the session was not the current one). Drives a small dot badge on
     * each row in the open-sessions dropdown. Projected from
     * [AppState.unreadSessions] by the caller.
     */
    val unreadSessions: Set<String> = emptySet(),
    /**
     * When non-null, the user is in draft (deferred-create) mode for this
     * workdir. The bar shows the workdir basename in place of a session title
     * and the dropdown is hidden (there is no session yet).
     */
    val draftWorkdir: String? = null,
    /**
     * When non-null, the current session is a sub-agent (child) and tapping the
     * back affordance should navigate to this parent session. Renders a small
     * "← <parentName>" affordance in place of the session dropdown.
     */
    val parentSessionId: String? = null,
    val parentSessionTitle: String? = null
)

internal data class ChatTopBarActions(
    val onSelectSession: (String) -> Unit,
    val onCloseSession: (String) -> Unit,
    val onSelectAgent: (String) -> Unit,
    val onNavigateToSettings: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onSelectHost: (String) -> Unit = {},
    val onActivateTunnel: () -> Unit = {}
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTopBar(
    state: ChatTopBarState,
    actions: ChatTopBarActions,
    modifier: Modifier = Modifier
) {
    val currentSession = state.sessions.find { it.id == state.currentSessionId }
    var showAgentMenu by remember { mutableStateOf(false) }
    var showSessionMenu by remember { mutableStateOf(false) }
    var showTodoDialog by remember { mutableStateOf(false) }
    var showContextDialog by remember { mutableStateOf(false) }
    var showServerDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // --- First row: title + right-side action cluster (TopAppBar-style) ---
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.nav_chat),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Agent dropdown (Material 3 TextButton)
                    Box {
                        TextButton(
                            onClick = { showAgentMenu = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = state.selectedAgentName,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.chat_switch_agent),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        DropdownMenu(
                            expanded = showAgentMenu,
                            onDismissRequest = { showAgentMenu = false }
                        ) {
                            state.agents.forEach { agent ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            agent.name,
                                            color = if (agent.name == state.selectedAgentName)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    onClick = {
                                        actions.onSelectAgent(agent.name)
                                        showAgentMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // Todo badge
                    val todoList = state.sessionTodos
                    val todoBadge = if (todoList.isNotEmpty()) {
                        "${todoList.count { it.isCompleted }}/${todoList.size}"
                    } else ""
                    Surface(
                        onClick = { showTodoDialog = true },
                        shape = RoundedCornerShape(50),
                        color = Color.Transparent
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                Icons.Default.Checklist,
                                contentDescription = if (todoBadge.isEmpty()) stringResource(R.string.chat_todo) else "${stringResource(R.string.chat_todo)} $todoBadge",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (todoBadge.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    todoBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Context usage ring
                    Surface(
                        onClick = { showContextDialog = true },
                        shape = RoundedCornerShape(50),
                        color = Color.Transparent
                    ) {
                        ContextUsageRing(usage = state.contextUsage)
                    }

                    if (state.showSettingsButton) {
                        IconButton(
                            onClick = actions.onNavigateToSettings,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(R.string.nav_settings),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Server status indicator: dot only (no text). Slightly
                    // larger than the previous glyph so it remains tappable.
                    val dotColor = when {
                        state.isConnecting -> Color(0xFFFFA500)
                        state.isConnected -> Color(0xFF4CAF50)
                        state.connectionPhase == null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        else -> Color(0xFFF44336)
                    }
                    Box(
                        modifier = Modifier
                            .clickable { showServerDialog = true }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .size(12.dp)
                            .background(color = dotColor, shape = CircleShape)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // --- Second row: session dropdown / sub-agent back affordance ---
            if (state.parentSessionId != null) {
                Surface(
                    onClick = { actions.onSelectSession(state.parentSessionId) },
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Transparent
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.chat_back_to_parent_session),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = state.parentSessionTitle ?: stringResource(R.string.chat_parent_session),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else if (state.draftWorkdir != null) {
                // Draft mode: no session yet, show workdir basename only.
                val draftBasename = state.draftWorkdir.split("/").filter { it.isNotEmpty() }.lastOrNull()
                    ?: state.draftWorkdir
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = draftBasename,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                SessionDropdownRow(
                    currentSession = currentSession,
                    openSessions = state.openSessions,
                    currentSessionId = state.currentSessionId,
                    unreadSessions = state.unreadSessions,
                    expanded = showSessionMenu,
                    onToggleExpand = { showSessionMenu = !showSessionMenu },
                    onSelectSession = { id ->
                        actions.onSelectSession(id)
                        showSessionMenu = false
                    },
                    onCloseSession = actions.onCloseSession
                )
            }
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

    if (showTodoDialog) {
        AlertDialog(
            onDismissRequest = { showTodoDialog = false },
            title = { Text(stringResource(R.string.chat_todo)) },
            text = {
                TodoListPanel(
                    todos = state.sessionTodos,
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
            usage = state.contextUsage,
            onDismiss = { showContextDialog = false }
        )
    }

    if (showServerDialog) {
        ServerManagementDialog(
            hostProfiles = state.hostProfiles,
            currentHostProfileId = state.currentHostProfileId,
            tunnelActivationState = state.tunnelActivationState,
            showTunnelAuth = state.showTunnelAuth,
            onSelectHost = { profileId ->
                actions.onSelectHost(profileId)
                showServerDialog = false
            },
            onRefresh = { actions.onRefresh() },
            onActivateTunnel = { actions.onActivateTunnel() },
            onDismiss = { showServerDialog = false }
        )
    }
}

private const val SESSION_TITLE_MAX_CHARS = 15

private fun truncateTitle(value: String): String =
    if (value.length <= SESSION_TITLE_MAX_CHARS) value
    else value.take(SESSION_TITLE_MAX_CHARS - 1) + "…"

private fun workdirBasename(directory: String): String =
    directory.split("/").filter { it.isNotEmpty() }.lastOrNull() ?: directory

@Composable
private fun SessionDropdownRow(
    currentSession: Session?,
    openSessions: List<Session>,
    currentSessionId: String?,
    unreadSessions: Set<String>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onSelectSession: (String) -> Unit,
    onCloseSession: (String) -> Unit
) {
    val currentTitle = currentSession?.displayName ?: "—"
    val currentWorkdir = currentSession?.directory
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand)
                .padding(vertical = 2.dp)
        ) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = truncateTitle(currentTitle),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (currentWorkdir != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "(${workdirBasename(currentWorkdir)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { if (expanded) onToggleExpand() }
        ) {
            if (openSessions.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.chat_select_or_create_session),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    enabled = false,
                    onClick = {}
                )
            } else {
                openSessions.forEach { session ->
                    val isSelected = session.id == currentSessionId
                    Surface(
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        else Color.Transparent,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectSession(session.id)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = truncateTitle(session.displayName),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = workdirBasename(session.directory),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            // Unread badge: a small primary-colored dot when this
                            // session has received a new (message.created) event
                            // since the user last viewed it. Cleared on open.
                            if (session.id in unreadSessions) {
                                Box(
                                    modifier = Modifier
                                        .padding(end = 4.dp)
                                        .size(8.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = CircleShape
                                        )
                                )
                            }
                            IconButton(
                                onClick = { onCloseSession(session.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.common_close),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextUsageDialog(
    usage: AppState.ContextUsage?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_context)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (usage == null) {
                    Text(
                        stringResource(R.string.chat_no_usage_data),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    ContextUsageSection(stringResource(R.string.chat_context_model_section)) {
                        ContextUsageRow(stringResource(R.string.chat_context_provider), usage.providerId ?: stringResource(R.string.chat_context_unknown))
                        ContextUsageRow(stringResource(R.string.chat_context_model), usage.modelId ?: stringResource(R.string.chat_context_unknown))
                        ContextUsageRow(stringResource(R.string.chat_context_limit), formatCount(usage.contextLimit))
                    }
                    ContextUsageSection(stringResource(R.string.chat_context_tokens)) {
                        ContextUsageRow(stringResource(R.string.chat_context_total), formatCount(usage.totalTokens))
                        ContextUsageRow(stringResource(R.string.chat_context_input), formatOptionalCount(usage.inputTokens))
                        ContextUsageRow(stringResource(R.string.chat_context_output), formatOptionalCount(usage.outputTokens))
                        ContextUsageRow(stringResource(R.string.chat_context_reasoning), formatOptionalCount(usage.reasoningTokens))
                        ContextUsageRow(stringResource(R.string.chat_context_cached_read), formatOptionalCount(usage.cachedReadTokens))
                        ContextUsageRow(stringResource(R.string.chat_context_cached_write), formatOptionalCount(usage.cachedWriteTokens))
                    }
                    ContextUsageSection(stringResource(R.string.chat_context_cost)) {
                        ContextUsageRow(stringResource(R.string.chat_context_cost), usage.cost?.let { "$" + String.format(Locale.US, "%.4f", it) } ?: stringResource(R.string.chat_context_no_cost))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_done))
            }
        }
    )
}

@Composable
private fun ServerManagementDialog(
    hostProfiles: List<HostProfile>,
    currentHostProfileId: String?,
    tunnelActivationState: TunnelActivationState,
    showTunnelAuth: Boolean,
    onSelectHost: (String) -> Unit,
    onRefresh: () -> Unit,
    onActivateTunnel: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.server_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (hostProfiles.isEmpty()) {
                    Text(
                        stringResource(R.string.server_dialog_no_hosts),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    hostProfiles.forEach { profile ->
                        val isSelected = profile.id == currentHostProfileId
                        Surface(
                            onClick = { onSelectHost(profile.id) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = profile.serverUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        // Bottom button row: Refresh + Activate Tunnel (secondary actions) share the
        // dialog's action bar with the Done confirm button, matching Material 3 dialog
        // conventions. dismissButton renders on the left, confirmButton on the right.
        dismissButton = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(onClick = onRefresh) {
                    Text(stringResource(R.string.server_dialog_refresh))
                }
                if (showTunnelAuth) {
                    val isActivating = tunnelActivationState is TunnelActivationState.Loading
                    TextButton(
                        onClick = onActivateTunnel,
                        enabled = !isActivating
                    ) {
                        if (isActivating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(stringResource(R.string.server_dialog_activate_tunnel))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_done))
            }
        }
    )
}

@Composable
private fun ContextUsageSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        content()
    }
}

@Composable
private fun ContextUsageRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

private fun formatCount(value: Int): String = String.format(Locale.US, "%,d", value)

private fun formatOptionalCount(value: Int?): String = value?.let(::formatCount) ?: "-"

@Composable
internal fun ContextUsageRing(usage: AppState.ContextUsage?) {
    val ringColor = when {
        usage == null -> MaterialTheme.colorScheme.onSurfaceVariant
        usage.percentage >= 0.9f -> MaterialTheme.colorScheme.error
        usage.percentage >= 0.7f -> BrandGold
        else -> MaterialTheme.colorScheme.primary
    }
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
        alpha = if (usage == null) 0.55f else 0.25f
    )

    Box(
        modifier = Modifier.size(ChatUiTuning.contextRingOuterSize),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = { 1f },
            modifier = Modifier.size(ChatUiTuning.contextRingInnerSize),
            color = trackColor,
            strokeWidth = 3.dp
        )
        if (usage != null) {
            CircularProgressIndicator(
                progress = { usage.percentage },
                modifier = Modifier.size(ChatUiTuning.contextRingInnerSize),
                color = ringColor,
                strokeWidth = 3.dp
            )
        }
    }
}

@Composable
internal fun ChatEmptyState(
    isConnected: Boolean,
    onConnect: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                if (isConnected) stringResource(R.string.chat_select_or_create_session) else stringResource(R.string.chat_connect_to_server),
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (!isConnected) {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text(stringResource(R.string.chat_connect))
                }
            }
        }
    }
}
