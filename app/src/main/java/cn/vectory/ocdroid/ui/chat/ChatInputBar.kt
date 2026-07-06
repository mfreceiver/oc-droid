package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import cn.vectory.ocdroid.ui.theme.BundledMonoFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.api.CommandInfo
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.ui.ChatViewModel
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.OrchestratorViewModel

@Composable
internal fun ChatInputBar(
    chatVM: ChatViewModel,
    composerVM: ComposerViewModel,
    orchestratorVM: OrchestratorViewModel,
    isBusy: Boolean,
    onAddImages: () -> Unit,
    // §#4: when a pending system question is active and the hoisted answers
    // satisfy its requirements, the primary send button submits the question
    // (via onSubmitQuestion) instead of dispatching a normal prompt. This
    // routes the user's instinctive tap on the bottom button into
    // orchestratorVM.replyQuestion, mirroring QuestionCardView's own Submit.
    pendingQuestion: QuestionRequest? = null,
    questionAnswersValid: Boolean = false,
    // 🟠-2: while a question submit is in flight (bottom-bar path), the
    // primary button is disabled for the question branch to prevent
    // double-submits on slow networks — mirrors the in-card Submit's isSending
    // guard. A typed prompt / image attachments can still be sent normally.
    questionSubmitting: Boolean = false,
    onSubmitQuestion: () -> Unit = {}
) {
    // §R-17 Stage 2: subscribe to composerFlow + settingsFlow directly so
    // typing (inputText mutation) only recomposes ChatInputBar, and streaming
    // / session-list / connection changes do NOT recompose it. Previously these
    // arrived as @Composable parameters (text: String was stable, but
    // imageAttachments: List / availableCommands: List were unstable → Compose
    // could not skip ChatInputBar on unrelated AppState changes). Reading them
    // from the slice Flow here lets the Compose runtime skip this composable
    // whenever neither slice emits.
    val composerState by composerVM.composerFlow.collectAsStateWithLifecycle()
    val settingsState by composerVM.settingsFlow.collectAsStateWithLifecycle()
    val text = composerState.inputText
    val imageAttachments = composerState.imageAttachments
    val availableCommands = settingsState.availableCommands
    val onTextChange = composerVM::setInputText
    val onSend = chatVM::sendMessage
    val onRemoveImage = composerVM::removeImageAttachment
    val onAbort = chatVM::abortSession
    val onExecuteCommand = orchestratorVM::executeCommand

    // §#4: canSend also covers the pending-question path — when a system
    // question is open and its hoisted answers are valid, the primary button
    // is enabled so the user can submit it from the bottom bar.
    // 🟠-2: the question branch is suppressed while a submit is in flight
    // (questionSubmitting) so the button greys out and re-taps don't queue.
    val canSend = text.isNotBlank() ||
        imageAttachments.isNotEmpty() ||
        (pendingQuestion != null && questionAnswersValid && !questionSubmitting)

    // --- Slash-command autocomplete state ---
    // The composer offers command suggestions when the user types a leading
    // "/" with no space yet (i.e. still in the command-name token). Matching
    // is prefix-based against the merged local+server command list. Tapping a
    // suggestion fills the input with "/<name> " so the user can append
    // arguments; pressing send dispatches the command (rather than treating
    // it as a normal chat message).
    val isCommandInput = text.startsWith("/")
    val commandNameToken = if (isCommandInput) {
        text.removePrefix("/").substringBefore(' ').lowercase()
    } else ""
    val stillTypingCommand = isCommandInput && !text.contains(' ')
    val matchingCommands = remember(text, availableCommands) {
        if (!stillTypingCommand) emptyList()
        else availableCommands.filter { info ->
            val n = info.name.lowercase()
            // Suggest names that extend what the user typed (exclude the exact
            // match — once they have fully typed a name, the suggestion is
            // noise). Empty token (just "/") lists everything.
            n.startsWith(commandNameToken) && n != commandNameToken
        }
    }

    // §9: the primary button is SEND whenever there is something to send —
    // including while the agent is running, so the user can append/steer a
    // running turn (matches the official web/TUI behaviour: the server's
    // `prompt_async` persists the message and the active run loop absorbs it
    // on its next iteration). Only when the agent is busy AND the composer is
    // empty does the button become STOP (one-tap abort).
    val canStop = isBusy && !canSend
    // §3b: paper-plane Send icon (more idiomatic than ArrowUpward). Stop state
    // keeps the square Stop icon.
    val sendIcon = if (canStop) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send
    val sendContentDescription = if (canStop) stringResource(R.string.chat_interrupt_agent)
    else stringResource(R.string.chat_send)
    // §6: stop-tap guardrail — the primary button flips to STOP while the
    // agent is busy and the composer is empty. Such a tap is easy to misfire
    // (thumb reaches for send, composer was just cleared), so route it through
    // a confirm dialog instead of aborting immediately.
    var showStopConfirm by rememberSaveable { mutableStateOf(false) }

    // §9: composer card — 直角（RectangleShape），用户要求清除圆角。
    Surface(
        modifier = Modifier.fillMaxWidth().imePadding(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RectangleShape,
        shadowElevation = 2.dp
    ) {
        // Horizontal 16 inset lives on the Column so the command panel,
        // attachment strip, and editor all share one consistent side inset
        // (the v2 composer spec's horizontal=16).
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            // Command suggestions panel — rendered above the input row (the
            // input sits at the bottom of the screen, so this visual ordering
            // keeps the suggestions visible without a popup that would open
            // off-screen below).
            if (matchingCommands.isNotEmpty()) {
                CommandSuggestionsPanel(
                    commands = matchingCommands,
                    onPick = { name -> onTextChange("/$name ") }
                )
            }

            if (imageAttachments.isNotEmpty()) {
                ImageAttachmentStrip(
                    attachments = imageAttachments,
                    onRemoveImage = onRemoveImage,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            // Editor row — §9: the card's shape/elevation come from the outer
            // Surface; this Row only lays out the field + buttons (no clip /
            // background). Field minHeight is 24dp (single line); maxLines = 3
            // lets it grow up to 3 lines.
            // §3a: layout is now [+] [input weight(1f)] [send] — the attachment
            // "+" sits to the LEFT of the field (external), matching the
            // standard mobile composer affordance and freeing the right edge
            // for the single primary action.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // §9: attachment "+" — ghost (transparent bg), opens the image
                // picker. R-12 (WCAG 2.5.5): the visual icon stays at 18dp but
                // the touch target is enlarged to 48dp via an outer clickable
                // Box, so the affordance is comfortably tappable. The icon is
                // centered so the visual density of the composer is unchanged.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(onClick = onAddImages),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    contentAlignment = Alignment.TopStart
                ) {
                    if (text.isEmpty()) {
                        Text(
                            stringResource(R.string.chat_type_message),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 24.dp, max = 120.dp),
                        textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 3
                    )
                }

                // §9: primary send / stop. R-12 (WCAG 2.5.5): the outer Box is
                // a 48dp touch target (the highest-frequency button in the app),
                // while the inner icon preserves the compact visual density.
                // §user-req: 去底色——不再有 containerColor/.background，按钮
                // 仅渲染 onSurfaceVariant 图标（disabled 时 0.5 alpha）。
                // §#4: when a system question is pending and its hoisted answers
                // are valid, the button submits the question instead of a normal
                // send/stop. Question submit takes priority over a typed prompt
                // (the question is the active modal interaction).
                // 🟠-2: skip the question branch entirely while a submit is in
                // flight (button is also disabled via canSend, but this guard
                // makes the click a guaranteed no-op even if text/images keep
                // canSend true during the in-flight window).
                ChatPrimaryActionButton(
                    onClick = {
                        if (pendingQuestion != null && questionAnswersValid && !questionSubmitting) {
                            onSubmitQuestion()
                        } else if (canStop) {
                            showStopConfirm = true
                        } else {
                            handleComposerSend(
                                text = text,
                                availableCommands = availableCommands,
                                allowCommand = !isBusy,
                                onSendMessage = onSend,
                                onExecuteCommand = onExecuteCommand
                            )
                        }
                    },
                    enabled = canStop || canSend,
                    dimWhenDisabled = true,
                    icon = sendIcon,
                    contentDescription = sendContentDescription
                )
            }

            // §6: stop-confirmation dialog. Only the primary STOP tap is gated
            // (it is a one-tap abort that is easy to misfire).
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
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showStopConfirm = false }) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
                )
            }
        }
    }
}

// §R-19 Sprint 2 #7(b): handleComposerSend was lifted verbatim into the
// top-level pure-functions file ChatFormatHelpers.kt (same package) so it can
// be covered by JVM unit tests (this file is excluded from kover coverage as
// a @Composable-heavy UI file — see PickerProviderFilter.kt for the same
// extraction pattern).

@Composable
private fun CommandSuggestionsPanel(
    commands: List<CommandInfo>,
    onPick: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        // Cap the visible list so a long server command catalog cannot shove
        // the input off-screen on small devices.
        LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
            items(commands, key = { it.name }) { cmd ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(cmd.name) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "/${cmd.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = BundledMonoFamily,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    cmd.description?.takeIf { it.isNotBlank() }?.let { desc ->
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatPrimaryActionButton(
    onClick: () -> Unit,
    enabled: Boolean,
    dimWhenDisabled: Boolean,
    icon: ImageVector,
    contentDescription: String
) {
    // §9: 28×28 visual (rounded 6, disabled alpha 0.5). The 1dp shadow was
    // removed (#5): Compose shadow rendered a light/white edge halo on light
    // themes that read as a semi-transparent white background behind the icon.
    // R-12 (WCAG 2.5.5): the outer Box is a 48dp touch target (the highest-
    // frequency button in the app), while the inner Box preserves the 28dp
    // styled visual so the composer's compact density is unchanged.
    val effectiveAlpha = if (!enabled && dimWhenDisabled) 0.5f else 1f
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .semantics {
                this.contentDescription = contentDescription
                this.role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = effectiveAlpha),
            modifier = Modifier.size(22.dp)
        )
    }
}

