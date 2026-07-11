package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.AgentInfo
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
     * item 4: session IDs that have a pending question (question.asked with no
     * reply yet). Drives the "?" indicator on each session tab — but only for
     * non-current sessions (the current session's question is already surfaced
     * via QuestionCard in the chat list). Projected from
     * [SessionListState.pendingQuestions] by the caller (sessionId set).
     */
    val questionSessionIds: Set<String> = emptySet(),
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
     * §1B (D.4): open the [SessionPickerSheet] from the navigation icon
     * (left of the title). Replaces the old onNavigateToSessions / nav-icon
     * flow (which routed to the legacy Sessions destination); session
     * switching is now a sheet instead of a separate page.
     */
    val onOpenSessionPicker: () -> Unit = {},
    /**
     * §1B (D.5): open the conversation overflow menu (rename / archive
     * actions) from the actions cluster's trailing IconButton.
     */
    val onOpenOverflow: () -> Unit = {},
    val onOpenContextSelector: () -> Unit = {},
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
 * title from pushing the actions cluster. §1B: the second-row session tab
 * strip was removed in Phase 1B; the title slot now carries the session
 * title alone (the workdir moved into the [ContextSelectorSheet] entry
 * point — Phase 1B renders the chip echo via a context-cue in the actions
 * cluster).
 */
private val TITLE_SLOT_MAX_WIDTH = 340.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTopBar(
    state: ChatTopBarState,
    actions: ChatTopBarActions,
    modifier: Modifier = Modifier,
    // §1B: tabVisible is no longer wired (no second-row strip). Kept on the
    // signature so the old call sites continue compiling during the
    // chrome swap; the new ChatScaffold passes true unconditionally.
    @Suppress("UNUSED_PARAMETER") tabVisible: Boolean = true,
    @Suppress("UNUSED_PARAMETER") onTabVisibilityChange: (Boolean) -> Unit = {},
    /**
     * §context-compact: optional callback wired through to the context-usage
     * dialog's "压缩" button. Null by default so existing call sites (and
     * ChatScreen.kt) keep compiling unchanged.
     */
    onContextCompact: (() -> Unit)? = null
) {
    val currentSession = state.sessions.find { it.id == state.currentSessionId }
    // §1B-FIX (I6): the Todo / Context-usage dialogs moved to
    // ChatScaffold.kt (their `showTodoDialog` / `showContextDialog`
    // state lives in the new overflow menu there). The local state
    // + dialog-rendering blocks for those two are gone; only the
    // server-management dialog stays in this file because its entry
    // point (the Dns IconButton) is still in the TopAppBar.
    var showServerDialog by remember { mutableStateOf(false) }
    // §model-selection: friendly name shown in the context chip.
    val currentModelName = resolveModelDisplayName(state.currentModel, state.providers)

    // Refresh traffic stats when the server popup opens so the dialog shows
    // live sent/received bytes instead of stale `0 B` (refresh is otherwise
    // only triggered from SettingsScreen).
    LaunchedEffect(showServerDialog) {
        if (showServerDialog) actions.onRefreshTrafficStats()
    }

    TopAppBar(
        modifier = modifier,
        windowInsets = TopAppBarDefaults.windowInsets,
        navigationIcon = {
            // §1B (D.4): the session-history IconButton. The icon is the
            // hamburger Menu (visually symmetric, RTL-mirroring moot).
            // Tapping opens the SessionPickerSheet — replaces the old
            // "open the Sessions page" nav flow.
            IconButton(onClick = actions.onOpenSessionPicker) {
                Icon(
                    Icons.Filled.Menu,
                    contentDescription = stringResource(R.string.chat_action_sessions),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    // place of a session title.
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
                        // §1B: title slot is the session display name only;
                        // the workdir moved out of the subtitle and into the
                        // context cue in the actions cluster (the context-chip
                        // pattern from D.5 — §B1-fix⑥: the chip is now wired
                        // to onOpenContextSelector, opening the Phase 2
                        // ContextSelectorSheet; the actions cluster also carries
                        // the server-status dot + the conversation overflow).
                        // The session title stays exactly as the old
                        // subtitle-less title.
                        Text(
                            text = currentSession.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        // §F1: 所有会话 tab 已关闭、无当前会话时，顶栏显示
                        // 应用名占位（不再附带版本号）。
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        actions = {
            // §1B (D.5): context cue — an AssistChip carrying the host name
            // + workdir basename. onClick = onOpenContextSelector opens the
            // ContextSelectorSheet (Phase 2 G.2 surface). Renders "Host →
            // Workdir" at a glance so users see the active scope without
            // opening the sheet.
            ContextCueChip(
                hostName = state.hostName,
                workdir = currentSession?.directory ?: state.draftWorkdir,
                onClick = actions.onOpenContextSelector,
            )

            // Server status indicator: an IconButton (Icons.Default.Dns)
            // that is always grey (onSurfaceVariant); a small coloured dot
            // at the icon's top-end corner reflects the live connection
            // state. Tapping opens the existing ServerManagementDialog
            // (host picker + refresh + tunnel + the "System Settings"
            // navigation entry). Kept from the old ChatTopBar — the
            // connection UX is unchanged in 1B.
            val badgeColor = when {
                state.isConnected -> SemanticColors.stateSuccessFg()
                state.isConnecting -> SemanticColors.stateInfoFg()
                state.connectionPhase is ConnectionPhase.Idle -> null
                else -> null
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

            // §1B (D.5): conversation overflow. Tap → DropdownMenu with
            // Archive (the only Phase 1B entry; Rename + Copy link are
            // Phase 1C follow-ups — message-row overflow is the canonical
            // edit / fork / revert surface).
            IconButton(onClick = actions.onOpenOverflow) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.chat_action_overflow),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    )

    // §1B-FIX (I6): the Todo / Context-usage AlertDialogs that used to
    // be gated by the local `showTodoDialog` / `showContextDialog`
    // state moved to ChatScaffold.kt — the new overflow menu opens
    // them there, owning the local state alongside the dialog body.
    // Only the server-management dialog stays in this file (its entry
    // point is the Dns IconButton in the TopAppBar).

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

/**
 * §1B (D.5): the context cue chip rendered in the actions cluster. Echoes
 * the current "Host → Workdir" pair at a glance. onClick is wired to
 * [ChatTopBarActions.onOpenContextSelector], which opens the
 * ContextSelectorSheet (Phase 2 G.2 surface). The long paths ellipsize via
 * TextOverflow.Ellipsis. The chip is M3 AssistChip (48dp touch target) and
 * the leading Dns icon matches the old DNS server popover affordance —
 * same family, different surface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextCueChip(hostName: String, workdir: String?, onClick: () -> Unit) {
    val workdirBase = workdir?.split("/")?.filter { it.isNotEmpty() }?.lastOrNull() ?: "—"
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = "$hostName → $workdirBase",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            Icon(
                Icons.Default.Dns,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            labelColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/**
 * §R18 Phase 5+ Gate-5 fix: `visiblePickerProviders` was extracted into its
 * own file (`PickerProviderFilter.kt`). It is a pure JVM-testable helper that
 * has unit tests (VisiblePickerProvidersTest); keeping it co-located here would
 * have hidden its coverage because ChatTopBarKt is excluded from kover (the
 * rest of this file is @Composable UI requiring ComposeTestRule/androidTest).
 * The new ModelPickerSheet in Composer.kt calls `visiblePickerProviders(...)`
 * directly (same package, no import needed).
 *
 * §1B: the old AlertDialog-wrapped ModelPickerDialog was folded into
 * Composer.kt's ModelPickerSheet. Kept the §R18 documentation comment so
 * future readers can find the migration trail; the dialog itself is gone.
 */
