package cn.vectory.ocdroid.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.AgentInfo
import cn.vectory.ocdroid.data.model.ConfigProvider
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.ProvidersResponse
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.TodoItem
import cn.vectory.ocdroid.ui.ContextUsage
import cn.vectory.ocdroid.ui.ConnectionPhase
import cn.vectory.ocdroid.ui.TunnelActivationState
import cn.vectory.ocdroid.ui.resolveModelDisplayName
import cn.vectory.ocdroid.ui.theme.SemanticColors
import cn.vectory.ocdroid.ui.theme.AppMotion

internal data class ChatTopBarState(
    val sessions: List<Session>,
    val currentSessionId: String?,
    val sessionStatuses: Map<String, SessionStatus>,
    val hasMoreSessions: Boolean,
    val isLoadingMoreSessions: Boolean,
    val isRefreshingSessions: Boolean = false,
    val expandedSessionIds: Set<String> = emptySet(),
    val agents: List<AgentInfo>,
    val selectedAgentName: String?,
    val contextUsage: ContextUsage?,
    val sessionTodos: List<TodoItem> = emptyList(),
    val hostName: String = "",
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val connectionPhase: ConnectionPhase = ConnectionPhase.Idle,
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
    /**
     * §model-selection: providers catalog (GET /config/providers), used to
     * populate the quick-switch model picker dialog from the top-bar context
     * menu. Null while loading / when the server returned nothing.
     */
    val providers: ProvidersResponse? = null,
    /**
     * §model-selection: per-baseUrl disabled-model entries (format
     * `"$providerId/$modelId"`). Models whose key is in this set are hidden
     * from the picker dialog.
     */
    val disabledModels: Set<String> = emptySet(),
    /**
     * §model-selection: the model currently bound to the active session
     * (inferred from the latest assistant message's resolvedModel). Drives
     * the model picker's selected highlight + the context menu entry label.
     */
    val currentModel: Message.ModelInfo? = null,
)

internal data class ChatTopBarActions(
    val onSelectSession: (String) -> Unit,
    val onCloseSession: (String) -> Unit,
    val onSelectAgent: (String?) -> Unit,
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
    /**
     * §model-selection (V1-per-prompt): switch the current session to the model
     * identified by `(providerId, modelId)`. Wired to
     * [cn.vectory.ocdroid.ui.MainViewModel.switchSessionModel], which persists
     * the choice LOCALLY via [cn.vectory.ocdroid.util.SettingsManager.setModelForSession]
     * and updates [cn.vectory.ocdroid.ui.ChatState.currentModel] for immediate picker feedback. The
     * chosen model is sent with the NEXT outgoing prompt as its
     * PromptRequest.model (V1-per-prompt, aligned with the official packages/app);
     * there is NO server-side switch call.
     */
    val onSwitchModel: (providerId: String, modelId: String) -> Unit = { _, _ -> },
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
    tabVisible: Boolean = true,
    /**
     * §context-compact: optional callback wired through to the context-usage
     * dialog's "压缩" button. Null by default so existing call sites (and
     * ChatScreen.kt) keep compiling unchanged.
     */
    onContextCompact: (() -> Unit)? = null
) {
    val currentSession = state.sessions.find { it.id == state.currentSessionId }
    var showContextMenu by remember { mutableStateOf(false) }
    var showTodoDialog by remember { mutableStateOf(false) }
    var showContextDialog by remember { mutableStateOf(false) }
    var showAgentDialog by remember { mutableStateOf(false) }
    var showServerDialog by remember { mutableStateOf(false) }
    // §model-selection: model quick-switch dialog opened from the merged
    // context menu's "Model" entry.
    var showModelDialog by remember { mutableStateOf(false) }
    // §model-selection: friendly name shown on the context menu's Model entry.
    val currentModelName = resolveModelDisplayName(state.currentModel, state.providers)

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
                // Primary Sessions entry point — "hamburger" list affordance at
                // the LEFT of the title. (Icons.Filled.Menu rather than the
                // AutoMirrored variant: the three-bar hamburger is visually
                // symmetric so RTL mirroring is moot, and AutoMirrored.Menu is
                // not available in this Compose version.)
                // Replaces the former trailing "+" on the session tab strip as
                // the canonical way to reach the Sessions destination.
                IconButton(onClick = actions.onNavigateToSessions) {
                    Icon(
                        Icons.Filled.Menu,
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        if (currentSession != null) {
                            // §17: the dropdown session switcher moved to the
                            // persistent tab strip rendered as the TopAppBar's
                            // second row. The title slot shows the current session
                            // title with a subtitle (#10) carrying the last segment
                            // of the session's working directory, so the user can
                            // tell which project the session belongs to at a glance.
                            Column(modifier = Modifier.widthIn(max = TITLE_SLOT_MAX_WIDTH)) {
                                Text(
                                    text = currentSession.displayName,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                // #10: subtitle = basename of currentSession.directory.
                                currentSession.directory
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
                        } else {
                            // §F1: 所有会话 tab 已关闭、无当前会话时，顶栏显示
                            // 「OC Droid v<version>」，取代原先的 "—" 占位。
                            Text(
                                text = stringResource(
                                    R.string.chat_title_app_version,
                                    stringResource(R.string.app_name),
                                    cn.vectory.ocdroid.BuildConfig.VERSION_NAME
                                ),
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
                    currentModelName = currentModelName,
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
                    },
                    onModelClick = {
                        showContextMenu = false
                        showModelDialog = true
                    }
                )
    
                // Server status indicator: an IconButton (Icons.Default.Dns)
                // that is always grey (onSurfaceVariant); a small coloured dot
                // (BadgedBox + Box) at the icon's top-end corner reflects the
                // live connection state. Tapping opens the existing
                // ServerManagementDialog (host picker + refresh + tunnel + the
                // "System Settings" navigation entry).
                // R-24: the status colours are sourced from SemanticColors
                // state-* constants (Phase 2：固定语义常量) + M3 colorScheme.error,
                // so the badge adapts to dark mode.
                // none (not connected AND connectionPhase is Idle) → no badge
                // at all. §R18 Phase 2-I: legacy `state.connectionPhase == null`
                // maps to `is ConnectionPhase.Idle` now that the field is a
                // non-null sealed type with [ConnectionPhase.Idle] as the new
                // "absent" sentinel.
                val badgeColor = when {
                    state.isConnected -> SemanticColors.stateSuccessFg()
                    state.isConnecting -> SemanticColors.stateInfoFg()
                    state.connectionPhase is ConnectionPhase.Idle -> null
                    else -> MaterialTheme.colorScheme.error
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
                        Box {
                            serverIcon()
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 2.dp, y = (-4).dp)
                                    .size(8.dp)
                                    .background(badgeColor, CircleShape)
                            )
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
            enter = AppMotion.tabStripEnter(),
            exit = AppMotion.tabStripExit()
        ) {
            SessionTabStrip(
                openSessions = state.openSessions,
                currentSessionId = state.currentSessionId,
                parentSessionId = state.parentSessionId,
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
            onDismiss = { showContextDialog = false },
            // §context-compact: dismiss the dialog first, then fire the compact
            // callback. `let` keeps onCompact null-safe — when no callback is
            // wired (default), ContextUsageDialog hides the button.
            onCompact = onContextCompact?.let {
                {
                    showContextDialog = false
                    it()
                }
            }
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
                    // §agent-default: 顶部"默认"项——清除显式选择，让服务端用其默认 agent。
                    val defaultLabel = stringResource(R.string.agent_default_label)
                    val defaultSelected = state.selectedAgentName == null
                    Surface(
                        onClick = {
                            actions.onSelectAgent(null)
                            showAgentDialog = false
                        },
                        shape = RectangleShape,
                        color = if (defaultSelected) MaterialTheme.colorScheme.surfaceVariant
                        else Color.Transparent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = defaultLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (defaultSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (defaultSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
                        )
                    }
                    state.agents.forEach { agent ->
                        val isSelected = agent.name == state.selectedAgentName
                        Surface(
                            onClick = {
                                actions.onSelectAgent(agent.name)
                                showAgentDialog = false
                            },
                            shape = RectangleShape,
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

    // §model-selection: model quick-switch picker. Lists every enabled model
    // from the providers catalog (disabled entries are hidden), highlights
    // the current selection, and dispatches a switch via onSwitchModel.
    if (showModelDialog) {
        ModelPickerDialog(
            providers = state.providers,
            disabledModels = state.disabledModels,
            currentModel = state.currentModel,
            onSwitchModel = { providerId, modelId ->
                actions.onSwitchModel(providerId, modelId)
                showModelDialog = false
            },
            onDismiss = { showModelDialog = false }
        )
    }
}

/**
 * §R18 Phase 5+ Gate-5 fix: `visiblePickerProviders` was extracted into its
 * own file (`PickerProviderFilter.kt`). It is a pure JVM-testable helper that
 * has unit tests (VisiblePickerProvidersTest); keeping it co-located here would
 * have hidden its coverage because ChatTopBarKt is excluded from kover (the
 * rest of this file is @Composable UI requiring ComposeTestRule/androidTest).
 * The Composable below calls `visiblePickerProviders(...)` directly (same
 * package, no import needed).
 */

/**
 * §model-selection: standalone AlertDialog that lists every enabled model in
 * the providers catalog grouped by provider. Disabled entries (per the
 * per-baseUrl SettingsManager set) are hidden, and providers with no remaining
 * enabled model are omitted entirely (see [visiblePickerProviders]). Selecting
 * a row fires [onSwitchModel] and dismisses.
 */
@Composable
private fun ModelPickerDialog(
    providers: ProvidersResponse?,
    disabledModels: Set<String>,
    currentModel: Message.ModelInfo?,
    onSwitchModel: (providerId: String, modelId: String) -> Unit,
    onDismiss: () -> Unit
) {
    val catalog = visiblePickerProviders(providers, disabledModels)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_model_picker_title)) },
        text = {
            if (catalog.isEmpty()) {
                Text(
                    stringResource(R.string.chat_model_picker_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@AlertDialog
            }
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                catalog.forEach { provider ->
                    Text(
                        provider.name?.takeIf { it.isNotEmpty() } ?: provider.id,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                    provider.models.forEach { (modelId, model) ->
                        val key = "${provider.id}/$modelId"
                        if (key in disabledModels) return@forEach
                        val isSelected = currentModel != null &&
                            currentModel.providerId == provider.id &&
                            (currentModel.modelId == modelId || currentModel.modelId == model.id)
                        Surface(
                            onClick = { onSwitchModel(provider.id, modelId) },
                            shape = RectangleShape,
                            color = if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                            else Color.Transparent,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = model.name ?: modelId,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
                            )
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
