// Composer.kt — Phase 1B composer. Replaces the old `ChatInputBar.kt` with
// the new M3-native surface (D.3): Agent/Model AssistChips above the input
// row, an Add-menu ModalBottomSheet (Photos only in Phase 1B; "Reference
// file" and "Commands" are stubs / Phase 2), and a file-reference
// chip strip driven by the new additive `ComposerState.fileReferences` slice
// field (F.4). Slash-command autocomplete continues to work inline via the
// existing `CommandSuggestionsPanel`. Send/Stop is a 48dp M3 IconButton.
//
// PARITY (mandatory): Composer subscribes to the same `composerFlow` +
// `settingsFlow` slices the old `ChatInputBar` read, and dispatches through
// the same domain methods (`composerVM.setInputText` / `addImageAttachments` /
// `removeImageAttachment` / `selectAgent` / `switchSessionModel`; `chatVM
// .sendMessage` / `abortSession` / `compactSession`; `orchestratorVM
// .executeCommand`). No new behavior is invented — only the chrome changes.

package cn.vectory.ocdroid.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.AgentInfo
import cn.vectory.ocdroid.data.model.ProvidersResponse
import cn.vectory.ocdroid.ui.ChatViewModel
import cn.vectory.ocdroid.ui.ComposerFileReference
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.theme.AppBottomSheet
import cn.vectory.ocdroid.ui.theme.AppSectionHeader
import cn.vectory.ocdroid.ui.theme.Dimens
import cn.vectory.ocdroid.ui.theme.PickerTrailingCheck
import kotlinx.coroutines.launch

/**
 * Phase 1B composer (D.3). Mirrors the old [ChatInputBar] signature + body
 * shape, then layers:
 *  - Agent + Model [AssistChip]s above the input row (D.3 / P4-1).
 *  - A file-reference [InputChip] strip driven by [ComposerState.fileReferences]
 *    (F.4, additive slice field). Tapping the × on a chip calls
 *    [ComposerViewModel.removeFileReference] which removes the chip AND
 *    strips the matching `File: <path>` line from `inputText`.
 *  - An Add-menu [ModalBottomSheet] (D.3). Phase 1B ships only Photos; the
 *    other two rows are stubs.
 *  - Agent / Model picker [ModalBottomSheet]s opened from the chips. Body
 *    content re-uses the existing AlertDialog content (no Search yet —
 *    Phase 2 G.2 step 1).
 *  - 48dp M3 [IconButton] for `+` / Send / Stop (48dp touch target — the
 *    old 28dp visual is dropped; the 48dp Box wrapper stayed but the new
 *    M3 IconButton gets it natively).
 *
 * §1B-FIX (I5): the Composer subscribes only to the low-frequency slices
 * it actually renders (composerFlow / settingsFlow + a narrow
 * currentModelFlow projection). It does NOT subscribe to the high-
 * frequency chatFlow or any other unrelated slice (connection / host /
 * sessionList) — those would force a recompose on every SSE token delta
 * (the streamingPartTexts field mutates ~10×/sec during a model run),
 * which would defeat the R-17 Stage 2 Compose-skipping contract. The
 * narrow currentModelFlow is a `map { it.currentModel
 * }.distinctUntilChanged()` projection so its emissions are limited to
 * actual model changes (rare).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Composer(
    chatVM: ChatViewModel,
    composerVM: ComposerViewModel,
    orchestratorVM: OrchestratorViewModel,
    isBusy: Boolean,
    questionPending: Boolean,
    onAddImages: () -> Unit,
) {
    // §PARITY (R-17 Stage 2): composer subscribes to composerFlow +
    // settingsFlow directly so keystrokes only recompose the composer.
    // The old ChatInputBar.kt used the exact same pair (composerFlow +
    // settingsFlow) — this is the parity boundary, do NOT widen it.
    val composerState by composerVM.composerFlow.collectAsStateWithLifecycle()
    val settingsState by composerVM.settingsFlow.collectAsStateWithLifecycle()
    // §0.8.2 P2.5: the narrow currentModelFlow projection + the chip-
    // related locals (agents / currentAgentName / providers /
    // disabledModels / currentModelName) are GONE — the Agent/Model chip
    // Row that consumed them was deleted (the selectors moved to the top-
    // bar overflow menu, P2.3). The picker sheets are now triggered from
    // ChatScaffold and source their own slice reads there. Removing the
    // chatVM.currentModelFlow subscription restores the §1B-FIX (I5)
    // parity boundary ("Composer must NOT subscribe to unrelated slices")
    // — the projection existed solely to feed the chips.
    val text = composerState.inputText
    val imageAttachments = composerState.imageAttachments
    val fileReferences = composerState.fileReferences
    val availableCommands = settingsState.availableCommands

    val onTextChange = composerVM::setInputText
    val onSend = chatVM::sendMessage
    val onRemoveImage = composerVM::removeImageAttachment
    val onAbort = chatVM::abortSession
    val onExecuteCommand = orchestratorVM::executeCommand

    // §PARITY: same command-suggestion logic as ChatInputBar.kt:99-113.
    val isCommandInput = text.startsWith("/")
    val commandNameToken = if (isCommandInput) {
        text.removePrefix("/").substringBefore(' ').lowercase()
    } else ""
    val stillTypingCommand = isCommandInput && !text.contains(' ')
    val matchingCommands = remember(text, availableCommands) {
        if (!stillTypingCommand) emptyList()
        else availableCommands.filter { info ->
            val n = info.name.lowercase()
            n.startsWith(commandNameToken) && n != commandNameToken
        }
    }

    val canSend = (text.isNotBlank() || imageAttachments.isNotEmpty()) && !questionPending
    val canStop = isBusy && !canSend
    val sendIcon = if (canStop) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send
    val sendContentDescription =
        if (canStop) stringResource(R.string.chat_interrupt_agent)
        else stringResource(R.string.chat_send)

    // §1B: state lives in the composer (the pickers are opened from the
    // chips rendered here). `rememberSaveable` keeps the sheet state across
    // rotation / process restore (P5-6 Sheet rotation risk).
    // §0.8.2 P2.5: showAgentPicker / showModelPicker are GONE from here —
    // the chip Row that triggered them was deleted (the selectors moved
    // into the top-bar overflow menu, P2.3). The picker sheet composables
    // are now triggered from ChatScaffold (where the overflow menu's open-
    // callbacks fire). Only the Add-menu + stop-confirm state remain here.
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var showStopConfirm by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxWidth().imePadding(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RectangleShape,
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            // §PARITY: command suggestions panel — verbatim from ChatInputBar.
            if (matchingCommands.isNotEmpty()) {
                CommandSuggestionsPanel(
                    commands = matchingCommands,
                    onPick = { name -> onTextChange("/$name ") },
                )
            }

            if (imageAttachments.isNotEmpty()) {
                ImageAttachmentStrip(
                    attachments = imageAttachments,
                    onRemoveImage = onRemoveImage,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            // §1B (F.4): file-reference chip strip. Reads the additive
            // ComposerState.fileReferences field; renders one InputChip per
            // reference. Removing a chip calls removeFileReference which
            // also strips the matching `File: <path>` line from inputText
            // (see ComposerController.removeFileReference).
            if (fileReferences.isNotEmpty()) {
                FileReferenceChipStrip(
                    references = fileReferences,
                    onRemove = { id -> composerVM.removeFileReference(id) },
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            // §0.8.2 P2.5: the Agent + Model AssistChip Row that used to
            // live here is REMOVED. The two selectors moved into the top-
            // bar overflow menu (P2.3 — Agent / Model items); the picker
            // sheets (AgentPickerSheet / ModelPickerSheet) are now triggered
            // from ChatScaffold (where the menu's open-callbacks fire). The
            // sheet composables themselves stay defined below in this file
            // (now `internal` so ChatScaffold can call them).

            // §PARITY: editor row — [+] [input weight=1f] [send/stop].
            // The Add button is now an M3 IconButton (48dp) opening the
            // ModalBottomSheet. The send/stop button is also an M3
            // IconButton (48dp). Both replace the old Box+clickable
            // wrappers.
            // §0.8.2 P2.5: vertical padding tightened from 8dp to
            // Dimens.spacing1 (4dp) — the user-reported "input box top/
            // bottom space is too large" came from the now-removed chip
            // Row's top padding (6dp) plus this Row's vertical=8dp. With
            // the chips gone, 4dp keeps the editor readable without the
            // airy gap.
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.spacing1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { showAdd = true },
                    enabled = !questionPending,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.chat_add_menu_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                            .copy(alpha = if (questionPending) 0.5f else 1f),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = stringResource(
                                if (questionPending) R.string.chat_input_disabled_question
                                else R.string.chat_type_message
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                .copy(alpha = if (questionPending) 0.35f else 0.6f),
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp, max = 120.dp),
                        enabled = !questionPending,
                        textStyle = LocalTextStyle.current.copy(
                            color = MaterialTheme.colorScheme.onSurface
                                .copy(alpha = if (questionPending) 0.5f else 1f),
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 3,
                    )
                }
                IconButton(
                    onClick = {
                        if (canStop) {
                            showStopConfirm = true
                        } else {
                            handleComposerSend(
                                text = text,
                                availableCommands = availableCommands,
                                allowCommand = !isBusy,
                                onSendMessage = onSend,
                                onExecuteCommand = onExecuteCommand,
                                onCompact = chatVM::compactSession,
                            )
                        }
                    },
                    enabled = (canStop || canSend) && !questionPending,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        sendIcon,
                        contentDescription = sendContentDescription,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                            .copy(
                                alpha = if ((canStop || canSend) && !questionPending) 1f else 0.5f,
                            ),
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddMenuSheet(
            onDismiss = { showAdd = false },
            onPhotos = {
                showAdd = false
                onAddImages()
            },
            // §1B: "Reference file" is a Phase 2 entry. Rendered
            // as a disabled row so the user sees the menu shape that will
            // land in Phase 2.
            onFileRef = {
                showAdd = false
                scope.launch {
                    // No-op in Phase 1B — wired in Phase 2 via
                    // orchestratorVM.requestPickFileForComposer → Files
                    // round-trip.
                }
            },
            onCommands = {
                showAdd = false
                composerVM.setInputText("/")
            },
        )
    }

    // §0.8.2 P2.5: the AgentPickerSheet / ModelPickerSheet invocations that
    // used to live here are REMOVED — the chip Row that opened them was
    // deleted (selectors moved to the top-bar overflow menu, P2.3). The
    // sheet composables stay defined below (now `internal`) and are
    // invoked from ChatScaffold.kt.

    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text(stringResource(R.string.chat_stop_confirm_title)) },
            text = { Text(stringResource(R.string.chat_stop_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onAbort()
                    showStopConfirm = false
                }) {
                    Text(
                        stringResource(R.string.chat_stop),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileReferenceChipStrip(
    references: List<ComposerFileReference>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Phase 1B: simple horizontal row of InputChips. LazyColumn is overkill
    // — file references per draft are bounded by the workspace tree depth
    // (typical: < 10). When the count grows, swap to LazyRow.
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        references.forEach { ref ->
            val baseName = ref.path.split("/").filter { it.isNotEmpty() }.lastOrNull() ?: ref.path
            // §phase3 48dp audit (plan §5 task 4): the file-ref chip's
            // trailingIcon used to carry its own `.size(16.dp).clickable`
            // — a 16dp touch target far below the 48dp minimum. The whole
            // chip is now the removal tap target (chip width >> 48dp, the
            // M3 idiom for removable InputChip rows). The × icon stays as a
            // pure visual affordance (no separate clickable on the icon).
            InputChip(
                selected = false,
                onClick = { onRemove(ref.id) },
                label = { Text(baseName, maxLines = 1) },
                leadingIcon = {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                trailingIcon = {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.chat_remove_file_ref),
                    )
                },
                colors = InputChipDefaults.inputChipColors(
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMenuSheet(
    onDismiss: () -> Unit,
    onPhotos: () -> Unit,
    onFileRef: () -> Unit,
    onCommands: () -> Unit,
) {
    // §WT1: 迁移到 AppBottomSheet（容器色 / titleLarge / sheetState / 底部
    // inset 由 recipe 统一）。原手写 titleMedium 标题行删除（recipe 的 title
    // 槽接管）。底部 16dp padding 也删除（recipe 已统一加底部 Spacer，避免
    // 双重留白，见 SheetRecipe.kt §inset-note）。
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.chat_add_menu_title),
    ) {
        androidx.compose.material3.ListItem(
            headlineContent = { Text(stringResource(R.string.chat_add_photos)) },
            supportingContent = { Text(stringResource(R.string.chat_add_photos_desc)) },
            leadingContent = {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
            },
            modifier = Modifier.clickable(onClick = onPhotos),
        )
        androidx.compose.material3.ListItem(
            headlineContent = { Text(stringResource(R.string.chat_add_file_ref)) },
            supportingContent = { Text(stringResource(R.string.chat_add_file_ref_desc)) },
            leadingContent = {
                Icon(Icons.Default.AttachFile, contentDescription = null)
            },
            // Phase 1B: row is visible but disabled to communicate the
            // upcoming surface; Phase 2 will wire
            // orchestratorVM.requestPickFileForComposer → workspace/files.
            modifier = Modifier.clickable(enabled = false, onClick = onFileRef),
        )
        androidx.compose.material3.ListItem(
            headlineContent = { Text(stringResource(R.string.chat_add_commands)) },
            supportingContent = { Text(stringResource(R.string.chat_add_commands_desc)) },
            leadingContent = {
                Icon(Icons.Default.Terminal, contentDescription = null)
            },
            modifier = Modifier.clickable(onClick = onCommands),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentPickerSheet(
    agents: List<AgentInfo>,
    currentAgentName: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // §B4·P3-B: 迁移到 AppBottomSheet（容器色 / titleLarge / sheetState /
    // 底部 inset 由 recipe 统一）。Agent 视为单一 section，无 provider 分组。
    // 选中态：headline primary 色 + trailing 槽固定 18dp Check（替代 SmartToy，
    // 与 ModelPickerSheet 统一为 Filled Check）。
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.chat_switch_agent),
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            // 默认 agent（选中 = null）。
            item(key = "__default__") {
                val isSelected = currentAgentName == null
                androidx.compose.material3.ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.agent_default_label),
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    trailingContent = { PickerTrailingCheck(isSelected) },
                    modifier = Modifier.clickable { onPick(null) },
                )
            }
            // §B4: 稳定 key 让选中态切换不丢动画；trailing 槽位恒渲染避免文本跳动。
            items(items = agents, key = { it.name }) { agent ->
                val isSelected = agent.name == currentAgentName
                androidx.compose.material3.ListItem(
                    headlineContent = {
                        Text(
                            text = agent.name,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    trailingContent = { PickerTrailingCheck(isSelected) },
                    modifier = Modifier.clickable { onPick(agent.name) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    providers: ProvidersResponse?,
    disabledModels: Set<String>,
    currentModel: cn.vectory.ocdroid.data.model.Message.ModelInfo?,
    onSwitch: (providerId: String, modelId: String) -> Unit,
    onDismiss: () -> Unit,
    /**
     * §chat-ux-batch T7 (B2): "默认" pick — clears the transient pendingModel
     * so the next send falls back to inference / server default. Mirrors the
     * AgentPickerSheet's `__default__` item (which routes to `onPick(null)`).
     * The caller (ChatScaffold) wires this to [cn.vectory.ocdroid.ui.ComposerViewModel.clearSessionModel].
     */
    onClear: () -> Unit = {},
) {
    // §B4·P3-B: 迁移到 AppBottomSheet（容器色 / titleLarge / sheetState /
    // 底部 inset 由 recipe 统一）。provider header 用 labelLarge + 顶 8dp/
    // 底 4dp（与 Agent 标题节奏对称）；trailing 槽统一 Filled Check（替代
    // Outlined Memory）。
    val catalog = visiblePickerProviders(providers, disabledModels)
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.chat_model_picker_title),
    ) {
        // §chat-ux-batch T7 review-fix (I1): the "默认" row is rendered
        // UNCONDITIONALLY so the user can always clear a pending model /
        // pick server default — even when the provider catalog is empty
        // (or unusable). Mirrors AgentPickerSheet's structure (line ~475),
        // where `__default__` lives at the head of an always-present
        // LazyColumn and the empty-catalog state is just a list-item
        // message UNDER it. Pre-fix the 默认 row sat inside the `else`
        // (non-empty) branch, so an empty catalog rendered ONLY the empty
        // message — T7-C4 requires 默认 to always be available + highlighted
        // when the effective model is null.
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            // §chat-ux-batch T7 (B2): top "默认" item — selected when the
            // effective model (pending ?: infer ?: null) is null. Routes
            // to onClear (ComposerViewModel.clearSessionModel), which
            // resets pendingModel so the next send falls back to inference
            // / server default. Mirrors the AgentPickerSheet's `__default__`
            // row at line ~477.
            item(key = "__default__") {
                val isSelected = currentModel == null
                androidx.compose.material3.ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.agent_default_label),
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    trailingContent = { PickerTrailingCheck(isSelected) },
                    modifier = Modifier.clickable { onClear() },
                )
            }
            if (catalog.isEmpty()) {
                // Empty catalog: show the message UNDER the always-present
                // 默认 row so the user can still clear / pick server default.
                item(key = "__empty__") {
                    Text(
                        text = stringResource(R.string.chat_model_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = Dimens.spacing6,
                            vertical = Dimens.spacing3,
                        ),
                    )
                }
            } else {
                catalog.forEach { provider ->
                    val matchingModels = provider.models.entries
                        .map { (modelId, model) -> modelId to model }
                        .filter { (modelId, _) ->
                            "${provider.id}/$modelId" !in disabledModels
                        }
                    if (matchingModels.isEmpty()) return@forEach
                    // §WT1 provider header：用 AppSectionHeader（titleSmall 14sp/Med
                    // + onSurfaceVariant + 16dp/8dp padding），替代原手写 labelLarge
                    // + 硬编码 padding(start=24,end=24,top=8,bottom=4)——与 ui-style-spec
                    // §2 的 section header 原语对齐，跨 picker 统一。
                    item(key = "header_${provider.id}") {
                        AppSectionHeader(
                            text = provider.name?.takeIf { it.isNotEmpty() } ?: provider.id,
                        )
                    }
                    items(
                        items = matchingModels,
                        key = { (modelId, _) -> "${provider.id}/$modelId" },
                    ) { (modelId, model) ->
                        val isSelected = currentModel != null &&
                            currentModel.providerId == provider.id &&
                            (currentModel.modelId == modelId || currentModel.modelId == model.id)
                        androidx.compose.material3.ListItem(
                            headlineContent = {
                                Text(
                                    text = model.name ?: modelId,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            trailingContent = { PickerTrailingCheck(selected = isSelected) },
                            modifier = Modifier.clickable { onSwitch(provider.id, modelId) },
                        )
                    }
                }
            }
        }
    }
}
