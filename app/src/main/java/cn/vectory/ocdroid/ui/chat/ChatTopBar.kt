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
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
    val onCloseSession: (String) -> Unit = {},
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
     * [cn.vectory.ocdroid.ui.MainViewModel.switchSessionModel], which persists
     * the choice LOCALLY via [cn.vectory.ocdroid.util.SettingsManager.setModelForSession]
     * and updates [cn.vectory.ocdroid.ui.ChatState.currentModel] for immediate picker feedback. The
     * chosen model is sent with the NEXT outgoing prompt as its
     * PromptRequest.model (V1-per-prompt, aligned with the official packages/app);
     * there is NO server-side switch call.
     */
    val onSwitchModel: (providerId: String, modelId: String) -> Unit = { _, _ -> },
    /**
     * §0.8.2 P2.3: trigger context compaction for the current session
     * (chatVM.compactSession). Surfaced as the FIRST overflow menu item
     * (text-only, no leading icon — distinct from the four icon-led
     * entries below it).
     */
    val onCompact: () -> Unit = {},
    /**
     * §0.8.2 P2.3: open the [ContextUsageDialog] (ModalBottomSheet) from
     * the overflow menu's "Context" item. The dialog body is unchanged;
     * only its trigger moved from the remote DropdownMenu (ChatScaffold)
     * to here.
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
    /**
     * §context-compact: optional callback formerly wired through to the
     * ServerManagementDialog's "压缩" button. §0.8.2 P2 removed that dialog
     * from this composable; the param is retained (suppressed) so the
     * ChatScaffold call site continues to compile. The live compact trigger
     * is [ChatTopBarActions.onCompact] (fired from the overflow menu's
     * "Compress context" item).
     */
    @Suppress("UNUSED_PARAMETER") onContextCompact: (() -> Unit)? = null,
) {
    val currentSession = state.sessions.find { it.id == state.currentSessionId }
    // §0.8.2 P2.1: hash-color workdir initial for the nav-icon slot.
    // NON-CLICKABLE — purely a scope cue. Sourced from the same place the
    // title reads (currentSession.directory, falling back to draftWorkdir).
    // basename = substringAfterLast('/').ifBlank { directory }; initial =
    // the uppercase first character. Renders a neutral placeholder when no
    // workdir is set (no session + no draft).
    val directory: String? = currentSession?.directory ?: state.draftWorkdir
    val workdirBasename: String? = directory
        ?.substringAfterLast('/')
        ?.ifBlank { directory }
    val workdirInitial: String? = workdirBasename
        ?.firstOrNull()
        ?.uppercaseChar()
        ?.toString()
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
        navigationIcon = {
            // §0.8.2 P2.1: hash-color UPPERCASE workdir initial. Replaces the
            // hamburger Menu IconButton (which opened SessionPickerSheet).
            // The picker is no longer reachable from the top bar; this
            // affordance is non-clickable. Sized at Dimens.iconLg (28dp),
            // centered inside the navigationIcon slot's natural padding.
            WorkdirInitial(
                initial = workdirInitial,
                directory = directory,
                modifier = Modifier.size(Dimens.iconLg),
            )
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
            // §0.8.2 P2.3: the overflow cluster. The ContextUsageRing is the
            // trigger; the DropdownMenu is its sibling inside the same Box
            // — that sibling relationship is what anchors the popup below
            // the trigger (the prior remote DropdownMenu at ChatScaffold
            // popped to the top-left because it had no tight Box anchor).
            // The 5 items (top→bottom): Compress context (text-only),
            // Context, Todo, Agent, Model. Archive was removed.
            ContextMenuCluster(
                usage = state.contextUsage,
                todos = state.sessionTodos,
                selectedAgentName = state.selectedAgentName,
                currentModelName = currentModelName,
                expanded = overflowExpanded,
                onToggleExpand = { overflowExpanded = !overflowExpanded },
                onDismiss = { overflowExpanded = false },
                onCompact = {
                    overflowExpanded = false
                    actions.onCompact()
                },
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
            )
        }
    )
}

/**
 * §0.8.2 P2.1: the non-clickable hash-color workdir initial rendered in the
 * navigationIcon slot. Reads the FIRST character of the workdir basename
 * (uppercase) and tints it with [workdirTone] so the user has an at-a-glance
 * scope cue. When [initial] is null (no session + no draft) the slot renders
 * a transparent placeholder so the title alignment stays stable.
 *
 * Sized via [Dimens.iconLg] (28dp) per the spec — large enough to legibly
 * carry a single glyph, small enough to leave the title slot's breathing
 * room intact. NON-CLICKABLE: no onClick, no ripple, no combinedClickable.
 */
@Composable
private fun WorkdirInitial(
    initial: String?,
    directory: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (initial != null && directory != null) {
            Text(
                text = initial,
                style = MaterialTheme.typography.titleMedium,
                color = workdirTone(directory),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        // else: render nothing (transparent placeholder — the Box reserves
        // the slot so the title alignment is stable across sessions with
        // and without a workdir).
    }
}

/**
 * §0.8.2 P2.3: the trigger + DropdownMenu cluster rendered in the actions
 * slot. Mirrors the v0.7.6 `ContextMenuButton` pattern
 * (ChatSessionTabStrip.kt:458): a 44dp transparent Surface wraps the live
 * [ContextUsageRing] (WCAG 2.5.5 touch target while keeping the ring visual
 * at its tuned size); the [DropdownMenu] is a sibling inside the same Box
 * so it anchors below the trigger.
 *
 * Menu items (top→bottom), per the P2 spec:
 *   1. Compress context — TEXT ONLY (no leading icon). → onCompact.
 *   2. Context          — Icons.Default.DonutLarge. Text = "{pct}%
 *                         {total/1000}/{limit/1000}" or the label
 *                         "Context" when usage is null. → onOpenContext.
 *   3. Todo             — Icons.Default.Checklist. Text = "{completed}/
 *                         {total}" (always shown incl. 0/0). → onOpenTodo.
 *   4. Agent            — Icons.Default.SmartToy. Text = selected agent
 *                         name (or "Default"). → onOpenAgent.
 *   5. Model            — Icons.Outlined.Memory. Text = current model
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
    selectedAgentName: String?,
    currentModelName: String,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onDismiss: () -> Unit,
    onCompact: () -> Unit,
    onOpenContext: () -> Unit,
    onOpenTodo: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenModel: () -> Unit,
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
            // 1. Compress context — TEXT ONLY (no leading icon).
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_compact_session)) },
                onClick = onCompact,
            )
            // 2. Context — "{pct}% {totalTokens/1000}/{contextLimit/1000}"
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
                onClick = onOpenContext,
            )
            // 3. Todo — "{completed}/{total}", always shown (including 0/0).
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
                onClick = onOpenTodo,
            )
            // 4. Agent — selected agent name (or "Default").
            DropdownMenuItem(
                text = {
                    Text(selectedAgentName ?: stringResource(R.string.agent_default_label))
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
            // 5. Model — current model display name (or "Select model").
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
