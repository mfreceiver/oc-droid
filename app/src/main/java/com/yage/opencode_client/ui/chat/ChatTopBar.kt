package com.yage.opencode_client.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
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
import com.yage.opencode_client.ui.theme.opencode
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
     * Session IDs with unread activity (an out-of-band message arrived
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
    val parentSessionTitle: String? = null,
    val trafficSent: Long = 0L,
    val trafficReceived: Long = 0L,
    /**
     * OpenCode server version (populated on connect). Shown on each host row
     * in [ServerManagementDialog] in place of the server URL.
     */
    val serverVersion: String? = null,
)

internal data class ChatTopBarActions(
    val onSelectSession: (String) -> Unit,
    val onCloseSession: (String) -> Unit,
    val onSelectAgent: (String) -> Unit,
    val onNavigateToSettings: () -> Unit = {},
    val onSelectHost: (String) -> Unit = {},
    val onActivateTunnel: () -> Unit = {},
    /**
     * §Manual message refresh for the current session (NON-destructive
     * tail fetch — keeps scrolled-up history + cursor + streaming overlay).
     * Wired to the ServerManagementDialog's "Refresh" button; the former
     * host-health probe affordance is repurposed to refresh messages.
     */
    val onRefreshMessages: () -> Unit = {},
    /**
     * Refresh cumulative traffic counters (sent/received bytes) before the
     * server popup renders them — otherwise the dialog shows stale `0 B`
     * until the user visits Settings. Triggered when the popup opens.
     */
    val onRefreshTrafficStats: () -> Unit = {},
    /**
     * Primary Sessions-page entry point. Rendered as the [TopAppBar]
     * `navigationIcon` (left of the title) — a list affordance. Defaults to a
     * no-op so existing call sites keep compiling; the phone layout wires a
     * real callback. (The former trailing "+" on the session tab strip has
     * been removed in favour of this entry point.)
     */
    val onNavigateToSessions: () -> Unit = {},
)

/**
 * Max width for the title-slot content (current session title, §8 breadcrumb,
 * or draft workdir basename). The M3 [TopAppBar] title slot is not a
 * [RowScope], so [Modifier.weight] has no effect there; this cap keeps the
 * title from pushing the actions cluster. Session switching lives in the
 * persistent second-row tab strip (§17, [SessionTabStrip]).
 */
private val TITLE_SLOT_MAX_WIDTH = 340.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTopBar(
    state: ChatTopBarState,
    actions: ChatTopBarActions,
    modifier: Modifier = Modifier
) {
    val currentSession = state.sessions.find { it.id == state.currentSessionId }
    var showContextMenu by remember { mutableStateOf(false) }
    var showTodoDialog by remember { mutableStateOf(false) }
    var showContextDialog by remember { mutableStateOf(false) }
    var showAgentDialog by remember { mutableStateOf(false) }
    var showServerDialog by remember { mutableStateOf(false) }

    // Refresh traffic stats when the server popup opens so the dialog shows
    // live sent/received bytes instead of stale `0 B` (refresh is otherwise
    // only triggered from SettingsScreen).
    LaunchedEffect(showServerDialog) {
        if (showServerDialog) actions.onRefreshTrafficStats()
    }

    // M3 TopAppBar replaces the former custom Surface+Row. The title slot
    // carries the session dropdown (or parent-back / draft affordance); the
    // actions slot carries the merged context menu, settings, and the server
    // status dot. windowInsets is the TopAppBar default so the bar self-handles
    // the status-bar inset (the caller-side statusBarsPadding is dropped by
    // another channel — see RFC 0.1.3 §2-3).
    // §17: a persistent session tab strip is rendered directly below the
    // TopAppBar as a second row. The TopAppBar keeps status-bar inset
    // handling via its default windowInsets; the outer Column carries the
    // caller-supplied modifier.
    Column(modifier = modifier) {
    TopAppBar(
        windowInsets = TopAppBarDefaults.windowInsets,
        navigationIcon = {
            // Primary Sessions entry point — list affordance at the LEFT of
            // the title. Replaces the former trailing "+" on the session tab
            // strip as the canonical way to reach the Sessions destination.
            IconButton(onClick = actions.onNavigateToSessions) {
                Icon(
                    Icons.AutoMirrored.Filled.List,
                    contentDescription = stringResource(R.string.chat_action_sessions),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        title = {
            when {
                state.parentSessionId != null -> {
                    // §8: sub-agent breadcrumb "[parent] / [current]". Only the
                    // parent segment is clickable (navigates back to the parent
                    // session); the current segment is plain. Single-level only
                    // — matches v2 which does not render a full ancestry chain
                    // (Session.parentId may be multi-level, but the UI shows one).
                    val parentTitle = state.parentSessionTitle
                        ?: stringResource(R.string.chat_parent_session)
                    val currentTitle = currentSession?.displayName.orEmpty()
                    val oc = MaterialTheme.opencode
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.widthIn(max = TITLE_SLOT_MAX_WIDTH)
                    ) {
                        Text(
                            text = truncateTitle(parentTitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .clickable {
                                    state.parentSessionId?.let(actions.onSelectSession)
                                }
                        )
                        Text(
                            text = " / ",
                            style = MaterialTheme.typography.labelMedium,
                            color = oc.faint
                        )
                        Text(
                            text = currentTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                state.draftWorkdir != null -> {
                    // Draft mode: no session yet. Show the workdir basename in
                    // place of a session title; the dropdown is hidden (there
                    // is no session to switch to until the first send).
                    val draftBasename = state.draftWorkdir.split("/")
                        .filter { it.isNotEmpty() }.lastOrNull()
                        ?: state.draftWorkdir
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.widthIn(max = TITLE_SLOT_MAX_WIDTH)
                    ) {
                        Text(
                            text = draftBasename,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                else -> {
                    // §17: the dropdown session switcher moved to the persistent
                    // tab strip rendered as the TopAppBar's second row. The title
                    // slot now shows only the current session title.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.widthIn(max = TITLE_SLOT_MAX_WIDTH)
                    ) {
                        Text(
                            text = currentSession?.displayName ?: "—",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        actions = {
            // --- Merged context menu: context / todo / agent in one dropdown.
            // Trigger icon is the ContextUsageRing so the live usage state stays
            // visible at a glance (#8).
            ContextMenuButton(
                usage = state.contextUsage,
                todos = state.sessionTodos,
                selectedAgentName = state.selectedAgentName,
                expanded = showContextMenu,
                onToggleExpand = { showContextMenu = !showContextMenu },
                onContextClick = {
                    showContextMenu = false
                    showContextDialog = true
                },
                onTodoClick = {
                    showContextMenu = false
                    showTodoDialog = true
                },
                onAgentClick = {
                    showContextMenu = false
                    showAgentDialog = true
                }
            )

            // Server status indicator: an IconButton (Icons.Default.Dns)
            // tinted to reflect the live connection state — the same colour
            // logic the former status dot used. Tapping opens the existing
            // ServerManagementDialog (host picker + refresh + tunnel + the
            // "System Settings" navigation entry).
            val serverIconTint = when {
                state.isConnecting -> Color(0xFFFFA500)
                state.isConnected -> Color(0xFF4CAF50)
                state.connectionPhase == null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                else -> Color(0xFFF44336)
            }
            IconButton(onClick = { showServerDialog = true }) {
                Icon(
                    Icons.Default.Dns,
                    contentDescription = stringResource(R.string.chat_action_server),
                    tint = serverIconTint
                )
            }
        }
    )

        // §17: persistent horizontal session tab strip — the TopAppBar's
        // second row. Always visible (incl. when viewing a sub-agent); per
        // §17.2 the openSessions list is already filtered to root sessions
        // (parentId == null) by ChatScreen, so it never duplicates the
        // sub-agent currently shown in the title-slot breadcrumb.
        SessionTabStrip(state = state, actions = actions)
    }

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

    // Agent picker is a standalone AlertDialog (not a nested DropdownMenu) to
    // avoid M3 nested-popup focus/dismiss conflicts (#8).
    if (showAgentDialog) {
        AlertDialog(
            onDismissRequest = { showAgentDialog = false },
            title = { Text(stringResource(R.string.chat_switch_agent)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    state.agents.forEach { agent ->
                        val isSelected = agent.name == state.selectedAgentName
                        Surface(
                            onClick = {
                                actions.onSelectAgent(agent.name)
                                showAgentDialog = false
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                            else Color.Transparent,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = agent.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAgentDialog = false }) {
                    Text(stringResource(R.string.common_done))
                }
            }
        )
    }

    if (showServerDialog) {
        ServerManagementDialog(
            hostProfiles = state.hostProfiles,
            currentHostProfileId = state.currentHostProfileId,
            tunnelActivationState = state.tunnelActivationState,
            showTunnelAuth = state.showTunnelAuth,
            trafficSent = state.trafficSent,
            trafficReceived = state.trafficReceived,
            serverVersion = state.serverVersion,
            onSelectHost = { profileId ->
                actions.onSelectHost(profileId)
                showServerDialog = false
            },
            onRefresh = { actions.onRefreshMessages() },
            onActivateTunnel = { actions.onActivateTunnel() },
            onNavigateToSettings = {
                showServerDialog = false
                actions.onNavigateToSettings()
            },
            onDismiss = { showServerDialog = false }
        )
    }
}

private const val SESSION_TITLE_MAX_CHARS = 15

private fun truncateTitle(value: String): String =
    if (value.length <= SESSION_TITLE_MAX_CHARS) value
    else value.take(SESSION_TITLE_MAX_CHARS - 1) + "…"

/**
 * §17: persistent horizontal session tab strip rendered as the TopAppBar's
 * second row. Replaces the former title-slot dropdown switcher. Each tab shows
 * the (truncated) session title, an unread dot when the session received an
 * out-of-band message, and a close-X; the active session is highlighted with
 * the v2 accent color.
 *
 * Per §17.2 the `openSessions` list is already filtered to root sessions
 * (parentId == null) by ChatScreen, so sub-agent sessions never appear here —
 * the tab strip and the title-slot breadcrumb (§8) coexist without conflict.
 *
 * The former trailing "+" affordance (which opened the Sessions page) has been
 * removed: Sessions is now reached via the [TopAppBar]'s `navigationIcon`
 * (left of the title).
 */
@Composable
private fun SessionTabStrip(
    state: ChatTopBarState,
    actions: ChatTopBarActions,
    modifier: Modifier = Modifier
) {
    val oc = MaterialTheme.opencode
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(state.openSessions, key = { it.id }) { session ->
            SessionTab(
                title = session.displayName,
                isSelected = session.id == state.currentSessionId,
                isUnread = session.id in state.unreadSessions,
                accentColor = oc.accentText,
                onSelect = { actions.onSelectSession(session.id) },
                onClose = { actions.onCloseSession(session.id) }
            )
        }
    }
}

/**
 * A single session tab. The whole row is tappable (selects the session); the
 * trailing X is a nested click target that closes the session. The active tab
 * uses the v2 accent text color + SemiBold weight in lieu of a background fill
 * (keeps the strip visually quiet, matching v2's understated tab treatment).
 */
@Composable
private fun SessionTab(
    title: String,
    isSelected: Boolean,
    isUnread: Boolean,
    accentColor: Color,
    onSelect: () -> Unit,
    onClose: () -> Unit
) {
    val textColor = if (isSelected) accentColor
    else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        // Unread dot: rendered in the accent color so it reads as "activity"
        // rather than an error. Cleared by the VM when the session is opened.
        if (isUnread) {
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(6.dp)
                    .background(color = accentColor, shape = CircleShape)
            )
        }
        Text(
            text = truncateTitle(title),
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(4.dp))
        // Close affordance: a tight 20dp click target. Compose's clickable
        // consumes the pointer event, so tapping the X fires onClose without
        // also triggering the row's select clickable (standard nested-clickable
        // behavior, same as a list row with a trailing IconButton).
        Box(
            modifier = Modifier
                .size(20.dp)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.common_close),
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Single anchor + dropdown that folds the former Agent TextButton, Todo icon,
 * and Context ring into one menu (#8). The anchor reuses [ContextUsageRing] so
 * the live context pressure is always visible; tapping it opens a three-row
 * dropdown whose items delegate to the existing dialogs.
 */
@Composable
private fun ContextMenuButton(
    usage: AppState.ContextUsage?,
    todos: List<TodoItem>,
    selectedAgentName: String,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onContextClick: () -> Unit,
    onTodoClick: () -> Unit,
    onAgentClick: () -> Unit
) {
    Box {
        // The ring is the trigger; the previous standalone onClick (which opened
        // the context dialog directly) is removed — the dropdown is the anchor.
        Surface(
            onClick = onToggleExpand,
            shape = RoundedCornerShape(50),
            color = Color.Transparent
        ) {
            ContextUsageRing(usage = usage)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { if (expanded) onToggleExpand() }
        ) {
            // 1. Context — "{pct}% {total}/{limit}", compact counts.
            DropdownMenuItem(
                text = {
                    Text(
                        if (usage != null) {
                            val pct = (usage.percentage * 100).toInt()
                            "$pct% ${usage.totalTokens / 1000}/${usage.contextLimit / 1000}"
                        } else {
                            stringResource(R.string.chat_context)
                        }
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.DonutLarge,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = onContextClick
            )
            // 2. Todo — "{completed}/{total}", always shown (including 0/0).
            DropdownMenuItem(
                text = {
                    val completed = todos.count { it.isCompleted }
                    val total = todos.size
                    Text("$completed/$total")
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Checklist,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = onTodoClick
            )
            // 3. Agent — selected agent name; opens the standalone picker dialog.
            DropdownMenuItem(
                text = { Text(selectedAgentName) },
                leadingIcon = {
                    Icon(
                        Icons.Default.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = onAgentClick
            )
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
                        ContextUsageRow(stringResource(R.string.chat_context_cost), usage.cost?.let { "¥" + String.format(Locale.US, "%.4f", it) } ?: stringResource(R.string.chat_context_no_cost))
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
    trafficSent: Long,
    trafficReceived: Long,
    serverVersion: String?,
    onSelectHost: (String) -> Unit,
    onRefresh: () -> Unit,
    onActivateTunnel: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val oc = MaterialTheme.opencode
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.server_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // --- Host profiles ---
                if (hostProfiles.isEmpty()) {
                    Text(
                        stringResource(R.string.server_dialog_no_hosts),
                        style = MaterialTheme.typography.bodyMedium,
                        color = oc.faint
                    )
                } else {
                    hostProfiles.forEach { profile ->
                        val isSelected = profile.id == currentHostProfileId
                        if (isSelected) {
                            // Current host: non-clickable display only
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = oc.layer02,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = profile.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = oc.accentText
                                        )
                                        serverVersion?.let { version ->
                                            Text(
                                                text = "v$version",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = oc.faint
                                            )
                                        }
                                    }
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = oc.stateSuccessFg
                                    )
                                }
                            }
                        } else {
                            // Other hosts: tappable to switch
                            Surface(
                                onClick = { onSelectHost(profile.id) },
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Transparent,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = profile.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    // Version is shown ONLY on the selected (current/connected)
                                    // host row — it's a single global value for the connected
                                    // server, so rendering it under non-current profiles would
                                    // be misleading (would show the connected host's version
                                    // for hosts we haven't probed).
                                }
                            }
                        }
                    }
                }

                // --- Traffic statistics ---
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "↑ ${formatTrafficBytes(trafficSent)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = oc.faint
                    )
                    Text(
                        text = "↓ ${formatTrafficBytes(trafficReceived)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = oc.faint
                    )
                }

                // --- Action icon row: Settings / Refresh / Tunnel ---
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.server_dialog_system_settings),
                            tint = oc.faint
                        )
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.chat_action_refresh_messages),
                            tint = oc.faint
                        )
                    }
                    if (showTunnelAuth) {
                        val isActivating = tunnelActivationState is TunnelActivationState.Loading
                        IconButton(
                            onClick = onActivateTunnel,
                            enabled = !isActivating
                        ) {
                            if (isActivating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = oc.faint
                                )
                            } else {
                                Icon(
                                    Icons.Default.VpnKey,
                                    contentDescription = stringResource(R.string.server_dialog_activate_tunnel),
                                    tint = oc.faint
                                )
                            }
                        }
                    }
                }
            }
        },
        // No confirm or dismiss buttons — tap scrim to dismiss
        confirmButton = {}
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

/**
 * Format byte counts for the traffic stats display: < 1 KiB → bytes,
 * < 1 MiB → KB, < 1 GiB → MB, otherwise GB. Locale.US enforces ASCII output.
 */
private fun formatTrafficBytes(bytes: Long): String {
    val unit = 1024L
    if (bytes < unit) return "$bytes B"
    val kb = bytes.toDouble() / unit
    if (kb < unit) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / unit
    if (mb < unit) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / unit
    return String.format(Locale.US, "%.2f GB", gb)
}

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
    onConnect: () -> Unit,
    isConnecting: Boolean = false,
    connectionPhase: String? = null,
    hostName: String = ""
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (isConnecting) {
                // Cold-start reconnect in flight: show a spinner + phase text
                // instead of the bare connect button. The button stays reserved
                // for the truly-disconnected case below.
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    strokeWidth = 4.dp
                )
                Spacer(modifier = Modifier.height(16.dp))
                val phase = connectionPhase?.takeIf { it.isNotBlank() && it != "connecting" }
                Text(
                    text = if (phase != null) {
                        "正在重连 $hostName… / $phase"
                    } else {
                        "正在重连 $hostName…"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
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
}
