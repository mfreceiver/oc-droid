// Composer.kt — composer surface (D.3): a [+] IconButton that directly opens
// the image picker, and slash-command autocomplete inline via
// `CommandSuggestionsPanel`. Send/Stop is a 48dp M3 IconButton.
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
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
import cn.vectory.ocdroid.ui.ChatViewModel
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.theme.Dimens
import cn.vectory.ocdroid.util.workdirBasename

/**
 * Phase 1B composer (D.3). Mirrors the old [ChatInputBar] signature + body
 * shape, then layers:
 *  - A [+] IconButton (D.3) that directly opens the image picker.
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
    /** §P0-F: abort POST 当前在途（abortPendingSessionIds 含本会话）→ 显「停止中」禁二次 abort。 */
    isAborting: Boolean,
    questionPending: Boolean,
    /** §B: subagent 会话为只读——输入框和发送按钮禁用，stop 按钮保留。 */
    isSubagent: Boolean = false,
    onAddImages: () -> Unit,
    // §B2 rev-gpt MAJOR 2: the abort target is caller-supplied so the
    // parameterized chat/{sessionId} route can pass its route identity
    // (chromeSessionId) — Composer itself does NOT subscribe to chatFlow
    // (the §1B-FIX I5 parity boundary), so it cannot resolve the route-
    // owned id. Legacy bare-chat callers pass a lambda that resolves flat
    // currentSessionId.
    onAbort: () -> Unit,
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
    val availableCommands = settingsState.availableCommands

    val onTextChange = composerVM::setInputText
    val onSend = chatVM::sendMessage
    val onRemoveImage = composerVM::removeImageAttachment
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

    val canSend = (text.isNotBlank() || imageAttachments.isNotEmpty()) && !questionPending && !isSubagent
    val canStop = isBusy && !canSend
    // §P0-F/R6: abort 在途时按钮整体禁用（既禁二次 abort，也避免 abort 窗口内误发新消息）。
    val stopping = isAborting
    val sendIcon = when {
        stopping -> Icons.Default.Stop
        canStop -> Icons.Default.Stop
        else -> Icons.AutoMirrored.Filled.Send
    }
    val sendContentDescription = when {
        stopping -> stringResource(R.string.chat_aborting)
        canStop -> stringResource(R.string.chat_interrupt_agent)
        else -> stringResource(R.string.chat_send)
    }

    // §1B: state lives in the composer (the pickers are opened from the
    // chips rendered here). `rememberSaveable` keeps the sheet state across
    // rotation / process restore (P5-6 Sheet rotation risk).
    // §0.8.2 P2.5: showAgentPicker / showModelPicker are GONE from here —
    // the chip Row that triggered them was deleted (the selectors moved
    // into the top-bar overflow menu, P2.3). The picker sheet composables
    // are now triggered from ChatScaffold (where the overflow menu's open-
    // callbacks fire). Only the stop-confirm state remains here.
    var showStopConfirm by rememberSaveable { mutableStateOf(false) }

    // §IME-OWNER (sole): NO .imePadding() here — AppShell's NavHost is the
    // unique IME padding owner (see AppShell.kt §IME-OWNER). Adding padding
    // here would re-create the two-owner ambiguity that rev-2 flagged. The
    // composer sits at the bottom of ChatScaffold's Column; the NavHost's
    // imePadding shrinks the entire route content uniformly, placing the
    // composer flush against the IME top. If you add an overlay / drawer /
    // consumeWindowInsets below NavHost level, route IME through the NavHost
    // — do NOT add another imePadding here.
    Surface(
        modifier = Modifier.fillMaxWidth(),
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

            // §0.8.2 P2.5: the Agent + Model AssistChip Row that used to
            // live here is REMOVED. The two selectors moved into the top-
            // bar overflow menu (P2.3 — Agent / Model items); the picker
            // sheets (AgentPickerSheet / ModelPickerSheet) are now triggered
            // from ChatScaffold (where the menu's open-callbacks fire). The
            // sheet composables themselves stay defined below in this file
            // (now `internal` so ChatScaffold can call them).

            // §PARITY: editor row — [+] [input weight=1f] [send/stop].
            // The [+] button is an M3 IconButton (48dp) that directly opens
            // the image picker. The send/stop button is also an M3
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
                    onClick = onAddImages,
                    enabled = !questionPending && !isSubagent,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.chat_add_image),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                            .copy(alpha = if (questionPending || isSubagent) 0.5f else 1f),
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
                                when {
                                    isSubagent -> R.string.chat_input_disabled_subagent
                                    questionPending -> R.string.chat_input_disabled_question
                                    else -> R.string.chat_type_message
                                }
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                .copy(alpha = if (questionPending) 0.35f else 0.6f),
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 24.dp)
                            // §ctrl-enter-send (2026-07-26): Ctrl+Enter (or
                            // Cmd+Enter on Mac Bluetooth keyboards) sends the
                            // message — same path as the send IconButton.
                            // onPreviewKeyEvent fires BEFORE the TextField's
                            // own key processing, so returning true consumes
                            // the event (no stray newline is inserted).
                            // Plain Enter remains a newline (multi-line input
                            // preserved). Guarded by `canSend && !questionPending`
                            // — identical to the send button's enabled state
                            // (Ctrl+Enter does NOT trigger the stop/abort path;
                            // that requires the stop confirm dialog).
                            .onPreviewKeyEvent { event ->
                                val native = event.nativeKeyEvent
                                if (event.type == KeyEventType.KeyDown &&
                                    event.key == Key.Enter &&
                                    (native.isCtrlPressed || native.isMetaPressed) &&
                                    canSend && !questionPending && !stopping
                                ) {
                                    handleComposerSend(
                                        text = text,
                                        availableCommands = availableCommands,
                                        allowCommand = !isBusy,
                                        onSendMessage = onSend,
                                        onExecuteCommand = onExecuteCommand,
                                        onCompact = chatVM::compactSession,
                                    )
                                    true
                                } else {
                                    false
                                }
                            },
                        enabled = !questionPending && !isSubagent,
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
                        if (stopping) {
                            // no-op: abort 在途，禁二次 abort
                        } else if (canStop) {
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
                    enabled = !stopping && (canStop || canSend) && !questionPending,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        sendIcon,
                        contentDescription = sendContentDescription,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                            .copy(
                                alpha = if (!stopping && (canStop || canSend) && !questionPending) 1f else 0.5f,
                            ),
                    )
                }
            }
        }
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


