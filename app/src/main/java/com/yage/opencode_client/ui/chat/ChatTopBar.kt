package com.yage.opencode_client.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.List
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.model.SessionStatus
import com.yage.opencode_client.data.model.TodoItem
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.session.SessionList
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
    val availableModels: List<AppState.ModelOption>,
    val selectedModelIndex: Int,
    val contextUsage: AppState.ContextUsage?,
    val sessionTodos: List<TodoItem> = emptyList(),
    val showSettingsButton: Boolean = true,
    val showNewSessionInTopBar: Boolean = true,
    val showSessionListInTopBar: Boolean = true
)

internal data class ChatTopBarActions(
    val onSelectSession: (String) -> Unit,
    val onCreateSession: () -> Unit,
    val onDeleteSession: (String) -> Unit,
    val onLoadMoreSessions: () -> Unit,
    val onRefreshSessions: () -> Unit = {},
    val onToggleSessionExpanded: (String) -> Unit = {},
    val onSelectModel: (Int) -> Unit,
    val onNavigateToSettings: () -> Unit = {},
    val onRenameSession: (String) -> Unit = {}
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTopBar(
    state: ChatTopBarState,
    actions: ChatTopBarActions,
    modifier: Modifier = Modifier
) {
    val currentSession = state.sessions.find { it.id == state.currentSessionId }
    var showSessionSheet by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showTodoDialog by remember { mutableStateOf(false) }
    var showContextDialog by remember { mutableStateOf(false) }

    LaunchedEffect(showSessionSheet) {
        if (showSessionSheet) actions.onRefreshSessions()
    }

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
            val titleText = currentSession?.title
                ?: currentSession?.directory?.split("/")?.lastOrNull()
                ?: "OpenCode"
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.showSessionListInTopBar) {
                        IconButton(
                            onClick = { showSessionSheet = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.List,
                                contentDescription = "Sessions",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    IconButton(
                        onClick = { showRenameDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Rename session",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (state.showNewSessionInTopBar) {
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = actions.onCreateSession,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "New session",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                }

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(modifier = Modifier.weight(1f, fill = false)) {
                        Surface(
                            onClick = { showModelMenu = true },
                            shape = RoundedCornerShape(50),
                            color = Color.Transparent,
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = state.availableModels.getOrNull(state.selectedModelIndex)?.shortName ?: "Model",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1
                                )
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Switch LLM model",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = showModelMenu,
                            onDismissRequest = { showModelMenu = false }
                        ) {
                            if (state.availableModels.isEmpty()) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "No models",
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    },
                                    onClick = { }
                                )
                            }
                            state.availableModels.forEachIndexed { index, model ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            model.displayName,
                                            color = if (index == state.selectedModelIndex)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    onClick = {
                                        actions.onSelectModel(index)
                                        showModelMenu = false
                                    }
                                )
                            }
                        }
                    }

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
                                contentDescription = if (todoBadge.isEmpty()) "Todo" else "Todo $todoBadge",
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
                                contentDescription = "Settings",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

    if (showSessionSheet) {
        ModalBottomSheet(onDismissRequest = { showSessionSheet = false }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ChatUiTuning.sessionSheetHeight)
            ) {
                SessionList(
                    sessions = state.sessions,
                    currentSessionId = state.currentSessionId,
                    sessionStatuses = state.sessionStatuses,
                    hasMoreSessions = state.hasMoreSessions,
                    isLoadingMoreSessions = state.isLoadingMoreSessions,
                    isRefreshingSessions = state.isRefreshingSessions,
                    expandedSessionIds = state.expandedSessionIds,
                    onSelectSession = {
                        actions.onSelectSession(it)
                        showSessionSheet = false
                    },
                    onCreateSession = {
                        actions.onCreateSession()
                        showSessionSheet = false
                    },
                    onDeleteSession = {
                        actions.onDeleteSession(it)
                        showSessionSheet = false
                    },
                    onLoadMoreSessions = actions.onLoadMoreSessions,
                    onRefreshSessions = actions.onRefreshSessions,
                    onToggleSessionExpanded = actions.onToggleSessionExpanded,
                    onOpenSettings = null
                )
            }
        }
    }

    if (showRenameDialog) {
        var renameText by remember(currentSession?.id) {
            mutableStateOf(
                currentSession?.title
                    ?: currentSession?.directory?.split("/")?.lastOrNull()
                    ?: ""
            )
        }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Session") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Session title") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameText.isNotBlank()) {
                            actions.onRenameSession(renameText.trim())
                        }
                        showRenameDialog = false
                    }
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showTodoDialog) {
        AlertDialog(
            onDismissRequest = { showTodoDialog = false },
            title = { Text("Todo") },
            text = {
                TodoListPanel(
                    todos = state.sessionTodos,
                    modifier = Modifier.heightIn(max = 400.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = { showTodoDialog = false }) {
                    Text("Done")
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
}

@Composable
private fun ContextUsageDialog(
    usage: AppState.ContextUsage?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Context") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (usage == null) {
                    Text(
                        "No usage data",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    ContextUsageSection("Model") {
                        ContextUsageRow("Provider", usage.providerId ?: "Unknown")
                        ContextUsageRow("Model", usage.modelId ?: "Unknown")
                        ContextUsageRow("Context limit", formatCount(usage.contextLimit))
                    }
                    ContextUsageSection("Tokens") {
                        ContextUsageRow("Total", formatCount(usage.totalTokens))
                        ContextUsageRow("Input", formatOptionalCount(usage.inputTokens))
                        ContextUsageRow("Output", formatOptionalCount(usage.outputTokens))
                        ContextUsageRow("Reasoning", formatOptionalCount(usage.reasoningTokens))
                        ContextUsageRow("Cached read", formatOptionalCount(usage.cachedReadTokens))
                        ContextUsageRow("Cached write", formatOptionalCount(usage.cachedWriteTokens))
                    }
                    ContextUsageSection("Cost") {
                        ContextUsageRow("Cost", usage.cost?.let { "$" + String.format(Locale.US, "%.4f", it) } ?: "No cost data")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
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
                if (isConnected) "Select or create a session" else "Connect to server",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (!isConnected) {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text("Connect")
                }
            }
        }
    }
}
