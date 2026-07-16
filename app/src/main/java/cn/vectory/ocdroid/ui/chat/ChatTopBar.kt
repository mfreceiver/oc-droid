package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import cn.vectory.ocdroid.ui.theme.Dimens

internal data class ChatTopBarState(
    val sessions: List<Session>,
    val currentSessionId: String?,
    val sessionStatuses: Map<String, SessionStatus>,
    val hasMoreSessions: Boolean,
    val isLoadingMoreSessions: Boolean,
    val isRefreshingSessions: Boolean = false,
    val expandedSessionIds: Set<String> = emptySet(),
    val agents: List<AgentInfo>,
    val currentAgentName: String?,
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
     * ROOT session ids whose tree contains a pending question
     * ([SessionListState.pendingQuestions] rolled up to each question's root
     * via `rootIdOf`). Drives the "?" indicator on each session tab — but only
     * for non-selected tabs (the selected root's question is already surfaced
     * via QuestionCard in the chat list, even when the user is viewing a
     * sub-agent of that root). Projected by the caller via
     * `questionRootIds(pendingQuestions, sessionsById)`.
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
    val onCloseSession: (String) -> Unit = {},
    /**
     * §Wave5b-Q13: dedicated callback for the子→父 breadcrumb tap. The
     * breadcrumb MUST NOT reuse [onSelectSession] — that always dispatches a
     * `Latest` scroll intent (the default selectSession path), which would
     * clobber the parent's Restore checkpoint captured at openSubAgent time.
     * This callback routes through
     * [cn.vectory.ocdroid.ui.SessionViewModel.returnToParent] which reads
     * [cn.vectory.ocdroid.ui.ChatState.parentReturnCheckpoints] and dispatches
     * `Restore(checkpoint)` to land the parent at its prior viewport.
     *
     * If no checkpoint is on file (cold-started into child, host purge
     * cleared the map), returnToParent falls back to Latest — so the
     * breadcrumb still navigates back, just at the newest position.
     */
    val onNavigateParent: () -> Unit = {},
    val onSelectAgent: (String?) -> Unit = {},
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
     * §1B (D.4): formerly opened the [SessionPickerSheet] from the
     * navigation icon. §0.8.2 P2.1: the icon was replaced with a non-
     * clickable workdir initial; this callback is no longer invoked from
     * the top bar (kept on the data class so existing callers / tests
     * keep compiling — the SessionPickerSheet itself is now orphaned).
     */
    val onOpenSessionPicker: () -> Unit = {},
    /**
     * §1B (D.5): formerly opened the conversation overflow menu. §0.8.2
     * P2.3: the overflow DropdownMenu is now co-located with its
     * ContextUsageRing trigger inside this composable (the fix for the
     * top-left popup bug); this remote-trigger callback is no longer
     * invoked. Kept on the data class for back-compat with callers/tests.
     */
    val onOpenOverflow: () -> Unit = {},
    /**
     * §model-selection (V1-per-prompt): switch the current session to the model
     * identified by `(providerId, modelId)`. Wired to
     * [cn.vectory.ocdroid.ui.ComposerViewModel.switchSessionModel], which
     * writes the choice to the TRANSIENT [cn.vectory.ocdroid.ui.ChatState.pendingModel]
     * chat-slice field (T7 rewired; the legacy
     * `SettingsManager.setModelForSession` persist + `currentModel` write was
     * deleted in T8). The chosen model is sent with the NEXT outgoing prompt
     * as its PromptRequest.model (V1-per-prompt, aligned with the official
     * packages/app); there is NO server-side switch call.
     */
    val onSwitchModel: (providerId: String, modelId: String) -> Unit = { _, _ -> },
    /**
     * §0.8.2 P2.3: open the [ContextUsageDialog] (ModalBottomSheet) from
     * the overflow menu's "Context" item. The dialog body is unchanged;
     * only its trigger moved from the remote DropdownMenu (ChatScaffold)
     * to here. The dialog itself owns the "Compress context" button
     * (ContextUsageDialog.onCompact → chatVM.compactSession); there is
     * NO separate "Compress" item in this overflow menu.
     */
    val onOpenContextDialog: () -> Unit = {},
    /**
     * §0.8.2 P2.3: open the Todo dialog ([TodoListPanel] inside an
     * AlertDialog) from the overflow menu's "Todo" item.
     */
    val onOpenTodoDialog: () -> Unit = {},
    /**
     * §0.8.2 P2.3: open the [AgentPickerSheet] from the overflow menu's
     * "Agent" item. Replaces the Composer's Agent AssistChip trigger
     * (the chip Row above the input was deleted in the same phase).
     */
    val onOpenAgentPicker: () -> Unit = {},
    /**
     * §0.8.2 P2.3: open the [ModelPickerSheet] from the overflow menu's
     * "Model" item. Replaces the Composer's Model AssistChip trigger.
     */
    val onOpenModelPicker: () -> Unit = {},
    /**
     * §6 (强制刷新): clear the per-session message-window cache + force a
     * cold-start reconnect (bypassing the 30s throttle, with TOFU freeze
     * + up to 3 retries). Wired in ChatScaffold to emit
     * [cn.vectory.ocdroid.ui.controller.ControllerEffect.ClearSessionWindowCache]
     * then [cn.vectory.ocdroid.ui.controller.ControllerEffect.ColdStartReconnect]
     * via the shared effect bus. User-triggered (context-menu tap) so no
     * extra throttling is applied (§6.4 — the cold-start handler itself
     * FROZENs while a TOFU trust dialog is pending).
     */
    val onForceRefresh: () -> Unit = {},
)

/**
 * Max width for the title-slot content (current session title, §8 breadcrumb,
 * or draft workdir basename). The M3 [TopAppBar] title slot is not a
 * [RowScope], so [Modifier.weight] has no effect there; this cap keeps the
 * title from pushing the actions cluster.
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
    onTitleClick: () -> Unit = {},
    /**
     * §home-hub T4: phone (<600dp) ArrowBack affordance in the
     * `navigationIcon` slot. Defaults to `{}` so existing call sites
     * compile; T7 (ChatScaffold → AppShell nav wiring) supplies the real
     * pop-to-home navigation. Only invoked on phone form factors
     * (`!isWide`); tablet renders the [onOpenDrawer] Menu button instead.
     */
    onBackToHome: () -> Unit = {},
    /**
     * §home-hub T4: tablet (≥600dp) Menu affordance in the
     * `navigationIcon` slot — opens the [RecentSessionsDrawer] wired by
     * ChatScaffold's `ModalNavigationDrawer`. Defaults to `{}`; ChatScaffold
     * overrides it to open its owned `drawerState`. Only invoked on tablet
     * form factors (`isWide`); phone renders the [onBackToHome] ArrowBack
     * instead.
     */
    onOpenDrawer: () -> Unit = {},
    /**
     * §context-compact: optional callback formerly wired through to the
     * ServerManagementDialog's "压缩" button. §0.8.2 P2 removed that dialog
     * from this composable; the param is retained (suppressed) so the
     * ChatScaffold call site continues to compile. The live compact trigger
     * is the [ContextUsageDialog]'s "Compress context" button
     * (ChatScaffold passes chatVM.compactSession to it); there is no
     * standalone "Compress" item in the overflow menu.
     */
    @Suppress("UNUSED_PARAMETER") onContextCompact: (() -> Unit)? = null,
) {
    // §home-hub T4: responsive top-left affordance. Mirrors the `isWide`
    // pattern at ChatScaffold.kt (§B3 wide-screen card wrap): prefer the M3
    // WindowSizeClass provided by [LocalWindowSizeClass] (ChatScreen.kt:26,
    // computed once in MainActivity via calculateWindowSizeClass); fall back
    // to a screenWidthDp ≥ 600 check when no provider is present (previews /
    // unit tests). Phone (Compact) → ArrowBack → [onBackToHome]; tablet
    // (Medium/Expanded) → Menu → [onOpenDrawer].
    val isWide = LocalWindowSizeClass.current
        ?.let { it.widthSizeClass != WindowWidthSizeClass.Compact }
        ?: (LocalConfiguration.current.screenWidthDp >= 600)
    val currentSession = state.sessions.find { it.id == state.currentSessionId }
    // §new2 (2026-07-13): cache the package's versionName once per composition
    // ( PackageManager.getPackageInfo is a binder call; wrap in remember so a
    // recomposition does NOT re-query ). Used by the title slot's "no current
    // session" branch to render `"${app_name} v${versionName}"`. The version
    // string from gradle may carry a git-hash suffix (e.g. `0.9.2-fb2fdff`) —
    // we display it verbatim. Falls back to null when PackageManager throws
    // (test harness / stripped package) so the title degrades to app_name only.
    val context = LocalContext.current
    val versionName by remember(context) {
        mutableStateOf(
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull()
        )
    }
    // §0.8.2 P2.3: model display name for the overflow menu's Model item.
    val currentModelName = resolveModelDisplayName(state.currentModel, state.providers)
    // §0.8.2 P2.3: overflow menu expanded state. Owned HERE so the
    // DropdownMenu can be co-located with its ContextUsageRing trigger
    // inside a single tightly-wrapping Box — the sibling relationship
    // inside that Box is what makes the popup anchor below the trigger.
    // The prior remote DropdownMenu (composed at ChatScaffold with no
    // tight Box parent) anchored to the window's top-left corner — the
    // bug this phase fixes.
    var overflowExpanded by remember { mutableStateOf(false) }

    TopAppBar(
        modifier = modifier,
        windowInsets = TopAppBarDefaults.windowInsets,
        // §home-hub T4: top-left navigation icon branches on width.
        //   phone (<600dp): ArrowBack → onBackToHome (pop back to Home).
        //   tablet (≥600dp): Menu     → onOpenDrawer (open RecentSessionsDrawer).
        // Both icons use Dimens.iconStd (24dp) — IconButton content tier —
        // with contentDescription from the T4 string resources for
        // accessibility (TalkBack announces the affordance's destination,
        // not "button").
        navigationIcon = {
            if (isWide) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = stringResource(R.string.chat_open_sessions_drawer),
                        modifier = Modifier.size(Dimens.iconStd),
                    )
                }
            } else {
                IconButton(onClick = onBackToHome) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.chat_back_to_home),
                        modifier = Modifier.size(Dimens.iconStd),
                    )
                }
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
                                    // §Wave5b-Q13: route through the DEDICATED
                                    // onNavigateParent callback, NOT
                                    // onSelectSession. The latter always lands
                                    // Latest (clobbering the parent's Restore
                                    // checkpoint captured at openSubAgent);
                                    // onNavigateParent routes through
                                    // SessionViewModel.returnToParent which
                                    // dispatches Restore(checkpoint) so the
                                    // parent re-opens at the user's prior
                                    // viewport.
                                    actions.onNavigateParent()
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
                        // §0.8.2 P2: title slot is the session display name
                        // only. The workdir moved into the nav-icon initial
                        // (P2.1); the actions slot now carries only the
                        // overflow cluster (P2.3 — ContextUsageRing trigger
                        // + DropdownMenu with 5 items).
                        Text(
                            text = currentSession.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(onClick = onTitleClick),
                        )
                    } else {
                        // §new2 (2026-07-13): all chat tabs closed
                        // (currentSessionId == null, no parent, no draft) —
                        // show "app_name vX.Y" so the top bar carries useful
                        // identity instead of a bare app_name placeholder.
                        // versionName is remembered above (one binder call per
                        // composition); the format mirrors the Cold-start /
                        // About surfaces (`versionName` may include a git-hash
                        // suffix like `0.9.2-fb2fdff`, displayed verbatim).
                        val appName = stringResource(R.string.app_name)
                        val titleText = if (versionName != null) {
                            "$appName v$versionName"
                        } else {
                            appName
                        }
                        Text(
                            text = titleText,
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
            // §new2 (2026-07-13): hide the ContextUsageRing trigger + its
            // overflow menu only when there is NEITHER a currentSession NOR
            // a draft (no parent either — same condition as the title branch
            // above). The cluster is session-scoped (context usage / todos /
            // agent / model), BUT §Q1 (2026-07-16): in DRAFT mode (no session
            // yet, but composer.draftWorkdir != null) the Agent / Model
            // pickers + force-refresh are still meaningful — pendingAgent /
            // pendingModel are session-independent chat-slice transients and
            // effectiveAgent / effectiveModel resolve correctly without a
            // session (pickers hoisted in ChatScaffold). So show the cluster
            // whenever a session OR a draft is active. The slot stays empty
            // — no placeholder — so the title can take the full width.
            if (currentSession != null || state.draftWorkdir != null) {
                ContextMenuCluster(
                    usage = state.contextUsage,
                    todos = state.sessionTodos,
                    currentAgentName = state.currentAgentName,
                    currentModelName = currentModelName,
                    enableSessionScopedItems = currentSession != null,
                    expanded = overflowExpanded,
                    onToggleExpand = { overflowExpanded = !overflowExpanded },
                    onDismiss = { overflowExpanded = false },
                    onOpenContext = {
                        overflowExpanded = false
                        actions.onOpenContextDialog()
                    },
                    onOpenTodo = {
                        overflowExpanded = false
                        actions.onOpenTodoDialog()
                    },
                    onOpenAgent = {
                        overflowExpanded = false
                        actions.onOpenAgentPicker()
                    },
                    onOpenModel = {
                        overflowExpanded = false
                        actions.onOpenModelPicker()
                    },
                    onForceRefresh = {
                        overflowExpanded = false
                        actions.onForceRefresh()
                    },
                )
            }
        }
    )
}

/**
 * §0.8.2 P2.3: the trigger + DropdownMenu cluster rendered in the actions
 * slot. Mirrors the v0.7.6 `ContextMenuButton` pattern
 * (ChatSessionTabStrip.kt:458): a 44dp transparent Surface wraps the live
 * [ContextUsageRing] (WCAG 2.5.5 touch target while keeping the ring visual
 * at its tuned size); the [DropdownMenu] is a sibling inside the same Box
 * so it anchors below the trigger.
 *
 * §dead-onCompact-cleanup: menu items (top→bottom), per the P2 spec — there
 * is NO standalone "Compress" item; compaction is triggered from the
 * ContextUsageDialog's own "Compress context" button (opened by the Context
 * item below):
 *   1. Context          — Icons.Default.DonutLarge. Text = "{pct}%
 *                         {total/1000}/{limit/1000}" or the label
 *                         "Context" when usage is null. → onOpenContext.
 *   2. Todo             — Icons.Default.Checklist. Text = "{completed}/
 *                         {total}" (always shown incl. 0/0). → onOpenTodo.
 *   3. Agent            — Icons.Default.SmartToy. Text = selected agent
 *                         name (or "Default"). → onOpenAgent.
 *   4. Model            — Icons.Outlined.Memory. Text = current model
 *                         display name (or "Select model"). → onOpenModel.
 *
 * Archive was REMOVED (the prior remote menu's last item).
 *
 * Leading-icon size = [Dimens.iconSm] (18dp, the M3 DropdownMenuItem default
 * tier); the workdir initial in the nav-icon slot uses [Dimens.iconLg] (28dp).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextMenuCluster(
    usage: ContextUsage?,
    todos: List<TodoItem>,
    currentAgentName: String?,
    currentModelName: String,
    /**
     * §Q1 (2026-07-16): when false (draft / new-session mode — no current
     * session yet), the session-scoped items (Context / Todo / Force Refresh)
     * render DISABLED (greyed, non-clickable) because they have no session to
     * act on — and Force Refresh would otherwise fire a global cache-clear +
     * cold-start reconnect with no sid (a meaningless global side effect).
     * Agent / Model stay enabled regardless: they edit session-independent
     * chat-slice transients (pendingAgent / pendingModel) and are exactly the
     * controls Q1 restores for the new-session flow. Computed by the caller
     * (`currentSession != null`) so no UI judgment is hard-coded here.
     */
    enableSessionScopedItems: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onDismiss: () -> Unit,
    onOpenContext: () -> Unit,
    onOpenTodo: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenModel: () -> Unit,
    /**
     * §6 (强制刷新): clear session-window cache + cold-start reconnect.
     */
    onForceRefresh: () -> Unit,
) {
    Box {
        // §v0.7-pattern (ChatSessionTabStrip.kt:458): the ring is the
        // trigger; enlarge the click target to 44dp (WCAG 2.5.5 AAA) while
        // keeping the ring visual at its tuned 28dp size (ChatUiTuning).
        // The ring is centered inside the larger transparent Surface so the
        // visual density of the actions cluster is unchanged.
        Surface(
            onClick = onToggleExpand,
            shape = RoundedCornerShape(50),
            color = Color.Transparent,
            modifier = Modifier.size(44.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                ContextUsageRing(usage = usage)
            }
        }
        // The DropdownMenu MUST be a sibling of the trigger inside this Box
        // — that's what makes it anchor below the trigger (a remote menu
        // with no tight Box parent anchors to the window's top-left).
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { if (expanded) onDismiss() },
        ) {
            // 1. Context — "{pct}% {totalTokens/1000}/{contextLimit/1000}"
            //    or the label "Context" when usage is null.
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
                        modifier = Modifier.size(Dimens.iconSm),
                    )
                },
                enabled = enableSessionScopedItems,
                onClick = onOpenContext,
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
                        modifier = Modifier.size(Dimens.iconSm),
                    )
                },
                enabled = enableSessionScopedItems,
                onClick = onOpenTodo,
            )
            // 3. Agent — selected agent name (or "Default").
            DropdownMenuItem(
                text = {
                    Text(currentAgentName ?: stringResource(R.string.agent_default_label))
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(Dimens.iconSm),
                    )
                },
                onClick = onOpenAgent,
            )
            // 4. Model — current model display name (or "Select model").
            DropdownMenuItem(
                text = {
                    Text(
                        currentModelName.ifEmpty { stringResource(R.string.chat_model_picker_title) }
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Memory,
                        contentDescription = null,
                        modifier = Modifier.size(Dimens.iconSm),
                    )
                },
                onClick = onOpenModel,
            )
            // 5. §6 (强制刷新): clear session-window cache + cold-start
            //    reconnect. User-triggered destructive-ish recovery action
            //    (NOT the §3 non-destructive tail refresh) — drops the
            //    memory LRU window cache across all sessions then forces a
            //    health-check reconnect with TOFU freeze + retries.
            DropdownMenuItem(
                text = {
                    Text(stringResource(R.string.chat_force_refresh))
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(Dimens.iconSm),
                    )
                },
                enabled = enableSessionScopedItems,
                onClick = onForceRefresh,
            )
        }
    }
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
 * §0.8.2 P2: the host chip / server-status IconButton / ServerManagementDialog
 * were removed from this file. The ServerManagementDialog composable file
 * (ChatServerManagementDialog.kt) is intentionally retained but no longer
 * invoked from the top bar. The ContextCueChip composable was deleted
 * entirely (it had no other consumer).
 */
