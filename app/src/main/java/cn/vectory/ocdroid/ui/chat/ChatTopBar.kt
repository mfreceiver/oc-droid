package cn.vectory.ocdroid.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.AgentInfo
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.TodoItem
import cn.vectory.ocdroid.ui.ContextUsage
import cn.vectory.ocdroid.ui.TunnelActivationState
import cn.vectory.ocdroid.ui.theme.opencode

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
    val contextUsage: ContextUsage?,
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
    modifier: Modifier = Modifier,
    // §顶部 tab 联动显隐：由 ChatMessageList 的滚动方向检测提升而来。
    // true = 显示 SessionTabStrip；false = 滑出 + 折叠该行。
    tabVisible: Boolean = true
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
                // Primary Sessions entry point — chat-bubble-outline affordance
                // at the LEFT of the title. Replaces the former trailing "+" on
                // the session tab strip as the canonical way to reach the
                // Sessions destination.
                IconButton(onClick = actions.onNavigateToSessions) {
                    Icon(
                        Icons.Filled.ChatBubbleOutline,
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
                        // slot shows the current session title with a subtitle (#10)
                        // carrying the last segment of the session's working
                        // directory, so the user can tell which project the session
                        // belongs to at a glance.
                        Column(modifier = Modifier.widthIn(max = TITLE_SLOT_MAX_WIDTH)) {
                            Text(
                                text = currentSession?.displayName ?: "—",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            // #10: subtitle = basename of currentSession.directory.
                            // Hidden when there is no session (the "—" placeholder
                            // case) or when the basename is empty.
                            currentSession?.directory
                                ?.substringAfterLast("/")
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { workdir ->
                                    Text(
                                        text = workdir,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
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
                // that is always grey (onSurfaceVariant); a small coloured dot
                // (BadgedBox + Box) at the icon's top-end corner reflects the
                // live connection state. Tapping opens the existing
                // ServerManagementDialog (host picker + refresh + tunnel + the
                // "System Settings" navigation entry).
                // R-24: the status colours are sourced from LocalOpencodeColors
                // state-* tokens (which have explicit light + dark values), so
                // the badge adapts to dark mode and matches the v2 palette.
                // none (not connected AND no connectionPhase) → no badge at all.
                val oc = MaterialTheme.opencode
                val badgeColor = when {
                    state.isConnected -> oc.stateSuccessFg
                    state.isConnecting -> oc.stateInfoFg
                    state.connectionPhase == null -> null
                    else -> oc.stateDangerFg
                }
                IconButton(onClick = { showServerDialog = true }) {
                    val serverIcon: @Composable () -> Unit = {
                        Icon(
                            Icons.Default.Dns,
                            contentDescription = stringResource(R.string.chat_action_server),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (badgeColor != null) {
                        BadgedBox(
                            badge = {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .background(badgeColor, CircleShape)
                                )
                            }
                        ) {
                            serverIcon()
                        }
                    } else {
                        serverIcon()
                    }
                }
            }
        )

        // §17: persistent horizontal session tab strip — the TopAppBar's
        // second row. Always visible (incl. when viewing a sub-agent); per
        // §17.2 the openSessions list is already filtered to root sessions
        // (parentId == null) by ChatScreen, so it never duplicates the
        // sub-agent currently shown in the title-slot breadcrumb.
        // R-17 M1: pass only the slices SessionTabStrip reads (instead of the
        // whole ChatTopBarState) so an unrelated state change — e.g.
        // contextUsage / traffic / connection — does not invalidate this
        // composable. Compose skipping keeps the PrimaryScrollableTabRow intact as long as
        // openSessions / currentSessionId / unreadSessions are structurally
        // equal to the previous call.
        //
        // §顶部 tab 联动显隐：用 AnimatedVisibility 包裹整行。`tabVisible` 由
        // ChatMessageList 的滚动方向检测驱动（向下滚隐藏 / 向上滚显示）。
        // 动画组合：fade + height expand/shrink（让下方内容平滑填充空隙）
        // + vertical slide（向上滑出/从顶部滑入，符合顶部 bar 的方向感）。
        // 时长 ~200ms（覆盖默认 300ms，更跟手）。
        AnimatedVisibility(
            visible = tabVisible,
            enter = fadeIn(animationSpec = tween(200)) +
                expandVertically(animationSpec = tween(200)) +
                slideInVertically(
                    animationSpec = tween(200),
                    initialOffsetY = { fullHeight -> -fullHeight }
                ),
            exit = fadeOut(animationSpec = tween(200)) +
                shrinkVertically(animationSpec = tween(200)) +
                slideOutVertically(
                    animationSpec = tween(200),
                    targetOffsetY = { fullHeight -> -fullHeight }
                )
        ) {
            SessionTabStrip(
                openSessions = state.openSessions,
                currentSessionId = state.currentSessionId,
                currentWorkdir = currentSession?.directory,
                unreadSessions = state.unreadSessions,
                actions = actions
            )
        }
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
