// ChatOverlayHost.kt — T4 extract: parity overlays hoisted out of ChatScaffold.
// Pure mechanical extraction, zero behavioral/visual change. All overlays
// remain on their mandated A/B/C layers per docs/ui-style-spec.md, using
// shared primitives (AppBottomSheet / AlertDialog family).
//
// State hoisting: ChatScaffold owns show* booleans and passes them (value
// + callback) down. VM/derived values are passed as parameters — each overlay
// only receives what it actually reads.
package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.AgentInfo
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.ProvidersResponse
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.TodoItem
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.ConnectionViewModel
import cn.vectory.ocdroid.ui.ContextUsage
import cn.vectory.ocdroid.ui.ChatViewModel
import cn.vectory.ocdroid.ui.SessionViewModel
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.http.TofuDecision
import cn.vectory.ocdroid.ui.settings.TofuTrustDialog
import cn.vectory.ocdroid.ui.theme.AppBottomSheet
import cn.vectory.ocdroid.util.workdirBasename

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatOverlayHost(
    // ── Hoisted state booleans ──────────────────────────────────────────────
    showAgentPicker: Boolean,
    showModelPicker: Boolean,
    showSessionPicker: Boolean,
    showTodoDialog: Boolean,
    showContextDialog: Boolean,
    pendingWorkdirPick: Boolean,
    errorDetail: String?,
    // ── Callbacks (onDismiss / onConfirm) ───────────────────────────────
    onDismissAgentPicker: () -> Unit,
    onPickAgent: (name: String?) -> Unit,
    onDismissModelPicker: () -> Unit,
    onSwitchModel: (providerId: String, modelId: String) -> Unit,
    onClearModel: () -> Unit,
    onDismissSessionPicker: () -> Unit,
    onSelectSession: (sessionId: String) -> Unit,
    onNewSession: () -> Unit,
    onDismissTodo: () -> Unit,
    onDismissContext: () -> Unit,
    onCompactContext: () -> Unit,
    onDismissWorkdirPick: () -> Unit,
    onPickWorkdir: (workdir: String) -> Unit,
    onDismissError: () -> Unit,
    onTofuDecision: (decision: TofuDecision) -> Unit,
    // ── Derived slice values (what each overlay reads) ──────────────────
    agents: List<AgentInfo>,
    currentAgentName: String?,
    providers: ProvidersResponse?,
    disabledModels: Set<String>,
    currentModel: Message.ModelInfo?,
    sessions: List<Session>,
    sessionStatuses: Map<String, SessionStatus>,
    activeSessionIds: Set<String>,
    currentSessionId: String?,
    unreadSessions: Set<String>,
    todos: List<TodoItem>,
    cachedContextUsage: ContextUsage?,
    recentWorkdirs: List<String>,
    pendingTofuCapture: OpenCodeRepository.TofuCaptureResult?,
    questionSessionIds: Set<String>,
    // ── ViewModels (for action methods) ──────────────────────────────────
    composerVM: ComposerViewModel,
    sessionVM: SessionViewModel,
    chatVM: ChatViewModel,
    connectionVM: ConnectionViewModel,
) {
    // ── AgentPickerSheet ─────────────────────────────────────────────────
    // §0.8.2 P2.3: AgentPickerSheet. Trigger moved from the Composer's Agent
    // AssistChip (deleted in P2.5) to the overflow menu's "Agent" item. The
    // sheet body composable stays defined in Composer.kt (now `internal`).
    // Slice reads: agents + currentAgentName come from the already-
    // subscribed settings slice (filtered to visible agents — same filter
    // the old chip Row used). onPick routes to composerVM.selectAgent (the
    // identical domain path the Composer's sheet used).
    if (showAgentPicker) {
        AgentPickerSheet(
            agents = agents,
            // §chat-ux-batch T7 (B2) + T8 (B3): selection reads `pending ?: infer ?: null`
            // so the picker highlights the value the NEXT send will actually
            // use. The legacy `SettingsState.selectedAgentName` field is gone
            // (T8 deleted it); the parameter is renamed to `currentAgentName`.
            currentAgentName = currentAgentName,
            onPick = { name -> onPickAgent(name) },
            onDismiss = { onDismissAgentPicker() },
        )
    }

    // ── ModelPickerSheet ─────────────────────────────────────────────────
    // §0.8.2 P2.3: ModelPickerSheet. Trigger moved from the Composer's Model
    // AssistChip (deleted in P2.5) to the overflow menu's "Model" item. The
    // sheet body composable stays defined in Composer.kt (now `internal`).
    // Slice reads: providers + disabledModels come from the settings slice;
    // currentModel comes from the chat slice (cachedContextUsage is sourced
    // from the same slice). onSwitch routes to composerVM.switchSessionModel
    // (V1-per-prompt, identical domain path the Composer's sheet used).
    if (showModelPicker) {
        ModelPickerSheet(
            providers = providers,
            disabledModels = disabledModels,
            // §chat-ux-batch T7 (B2): selection reads `pending ?: infer ?: null`
            // so the picker highlights the value the NEXT send will actually
            // use, and the new "默认" item highlights when that is null.
            // ChatState.currentModel is intentionally RETAINED (T8-C2) as the
            // load/compact mirror consumed by ChatViewModel.compactSession() —
            // NOT deleted.
            currentModel = currentModel,
            onSwitch = { providerId, modelId -> onSwitchModel(providerId, modelId) },
            // §chat-ux-batch T7 (B2): the top "默认" item — clears the
            // transient pendingModel so the next send falls back to inference
            // / server default (mirrors AgentPickerSheet's `onPick(null)`).
            onClear = { onClearModel() },
            onDismiss = { onDismissModelPicker() },
        )
    }

    // ── SessionPickerSheet ───────────────────────────────────────────────
    if (showSessionPicker) {
        SessionPickerSheet(
            sessions = sessions,
            sessionStatuses = sessionStatuses,
            activeSessionIds = activeSessionIds,
            currentSessionId = currentSessionId,
            unreadSessions = unreadSessions,
            questionSessionIds = questionSessionIds,
            onSelect = { sessionId -> onSelectSession(sessionId) },
            onNewSession = { onNewSession() },
            onDismiss = { onDismissSessionPicker() },
        )
    }

    // ── TodoListPanel (AppBottomSheet) ────────────────────────────────
    // §1B-FIX (I6): the three parity dialogs (Todo / Context-usage) live
    // here in ChatScaffold so the overflow can open them. The dialog body
    // is unchanged from the old ChatTopBar.kt — only the owner moved.
    // §0.8.2 P2.3: the open-triggers now fire from ChatTopBar's overflow
    // menu (onOpenTodoDialog / onOpenContextDialog) instead of the remote
    // ConversationOverflowMenu.
    if (showTodoDialog) {
        // §B4-P3C: Todo sheet 迁移到 AppBottomSheet（与 Context/Agent/Model 统一）。
        // - title 经 recipe 自动 titleLarge + padding(24,8)（原手写 titleMedium 升级）。
        // - skipPartiallyExpanded 默认 true（recipe 固化点①），与其它三个 sheet 一致；
        //   Todo 原是现网唯一半展的 sheet，现按本批决策统一全展。
        // - 容器色 surfaceContainerLow 由 recipe 统一（用户点 5：底色统一）。
        // - 高度：自然高度 + heightIn(max ≈ 屏 0.75) 封顶超长列表；不用 weight(1f)。
        val todoSheetMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.75f).dp
        AppBottomSheet(
            onDismissRequest = { onDismissTodo() },
            title = stringResource(R.string.chat_todo),
        ) {
            TodoListPanel(
                todos = todos,
                modifier = Modifier.heightIn(max = todoSheetMaxHeight),
            )
        }
    }

    // ── ContextUsageDialog ──────────────────────────────────────────────
    if (showContextDialog) {
        ContextUsageDialog(
            usage = cachedContextUsage,
            onDismiss = { onDismissContext() },
            onCompact = { onCompactContext() },
        )
    }

    // ── Workdir picker (AppBottomSheet) ──────────────────────────────
    // §drawer-new-session: project picker when ≥2 workdirs connected (mirrors
    // SessionsScreen's pendingWorkdirPick sheet). Selection starts a draft there.
    // Wrapped in a scrollable, height-capped Column so up to ~30 workdirs stay
    // reachable (mirrors the TodoListPanel heightIn(max) precedent).
    if (pendingWorkdirPick) {
        val pickerMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
        AppBottomSheet(
            onDismissRequest = { onDismissWorkdirPick() },
            title = stringResource(R.string.sessions_pick_workdir_title),
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = pickerMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                recentWorkdirs.forEach { workdir ->
                    val basename = workdir.workdirBasename() ?: workdir
                    ListItem(
                        headlineContent = { Text(basename) },
                        modifier = Modifier.clickable {
                            onPickWorkdir(workdir)
                        },
                    )
                }
            }
        }
    }

    // ── Error detail dialog (AlertDialog) ─────────────────────────────
    errorDetail?.let { detail ->
        AlertDialog(
            onDismissRequest = { onDismissError() },
            title = { Text(stringResource(R.string.chat_error_details)) },
            text = {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(text = detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onDismissError() }) {
                    Text(stringResource(R.string.common_done))
                }
            }
        )
    }

    // ── TofuTrustDialog ────────────────────────────────────────────────
    pendingTofuCapture?.let { capture ->
        TofuTrustDialog(
            capture = capture,
            onDecision = { decision -> onTofuDecision(decision) },
        )
    }
}
