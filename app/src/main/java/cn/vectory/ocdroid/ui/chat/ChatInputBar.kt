package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.api.CommandInfo
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.ui.MainViewModel
import cn.vectory.ocdroid.ui.theme.StopRed
import cn.vectory.ocdroid.ui.theme.opencode
import kotlinx.coroutines.delay

@Composable
internal fun ChatInputBar(
    viewModel: MainViewModel,
    isBusy: Boolean,
    agentActivityText: String?,
    agentStartedAtMillis: Long?,
    onAddImages: () -> Unit,
    // §#4: when a pending system question is active and the hoisted answers
    // satisfy its requirements, the primary send button submits the question
    // (via onSubmitQuestion) instead of dispatching a normal prompt. This
    // routes the user's instinctive tap on the bottom button into
    // viewModel.replyQuestion, mirroring QuestionCardView's own Submit.
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
    val composerState by viewModel.composerFlow.collectAsStateWithLifecycle()
    val settingsState by viewModel.settingsFlow.collectAsStateWithLifecycle()
    val text = composerState.inputText
    val imageAttachments = composerState.imageAttachments
    val availableCommands = settingsState.availableCommands
    val onTextChange = viewModel::setInputText
    val onSend = viewModel::sendMessage
    val onRemoveImage = viewModel::removeImageAttachment
    val onAbort = viewModel::abortSession
    val onExecuteCommand = viewModel::executeCommand

    // §#4: canSend also covers the pending-question path — when a system
    // question is open and its hoisted answers are valid, the primary button
    // is enabled so the user can submit it from the bottom bar.
    // 🟠-2: the question branch is suppressed while a submit is in flight
    // (questionSubmitting) so the button greys out and re-taps don't queue.
    val canSend = text.isNotBlank() ||
        imageAttachments.isNotEmpty() ||
        (pendingQuestion != null && questionAnswersValid && !questionSubmitting)
    val composerStatus = if (isBusy) agentActivityText ?: stringResource(R.string.chat_agent_running) else null

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

    val oc = MaterialTheme.opencode
    // §9: the primary button is SEND whenever there is something to send —
    // including while the agent is running, so the user can append/steer a
    // running turn (matches the official web/TUI behaviour: the server's
    // `prompt_async` persists the message and the active run loop absorbs it
    // on its next iteration). Only when the agent is busy AND the composer is
    // empty does the button become STOP (one-tap abort); the status-row menu
    // remains a second stop entry point while typing.
    val canStop = isBusy && !canSend
    // §3b: paper-plane Send icon (more idiomatic than ArrowUpward). Stop state
    // keeps the square Stop icon.
    val sendIcon = if (canStop) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send
    val sendContentDescription = if (canStop) stringResource(R.string.chat_interrupt_agent)
    else stringResource(R.string.chat_send)
    // §6: stop-tap guardrail — the primary button flips to STOP while the
    // agent is busy and the composer is empty. Such a tap is easy to misfire
    // (thumb reaches for send, composer was just cleared), so route it through
    // a confirm dialog instead of aborting immediately. The explicit menu item
    // in QuietComposerStatus stays a direct abort (that is an intentional
    // menu action, no second confirmation needed).
    var showStopConfirm by rememberSaveable { mutableStateOf(false) }

    // §9: composer card — rounded 10, surface (bg-base), 2dp elevation.
    Surface(
        modifier = Modifier.fillMaxWidth().imePadding(),
        color = oc.layer02,
        shape = RoundedCornerShape(10.dp),
        shadowElevation = 2.dp
    ) {
        // Horizontal 16 inset lives on the Column so the status row, command
        // panel, attachment strip, and editor all share one consistent side
        // inset (the v2 composer spec's horizontal=16).
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (composerStatus != null) {
                QuietComposerStatus(
                    status = composerStatus,
                    isBusy = isBusy,
                    startedAtMillis = if (isBusy) agentStartedAtMillis else null,
                    onAbort = onAbort,
                )
            }

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

                // §9: primary send / stop — 28×28, rounded 6, bg-contrast bottom,
                // white icon; disabled at 0.5 alpha.
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
                    containerColor = oc.bgContrast,
                    contentColor = Color.White,
                    dimWhenDisabled = true,
                    icon = sendIcon,
                    contentDescription = sendContentDescription
                )
            }

            // §6: stop-confirmation dialog. Only the primary STOP tap is gated
            // (it is a one-tap abort that is easy to misfire); the explicit
            // menu item in QuietComposerStatus still aborts directly.
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

/**
 * Routes the send-tap: a `/`-prefixed text matching a known command is
 * dispatched via [onExecuteCommand] (and the typed text is parsed into
 * command name + argument string); anything else falls through to a normal
 * [onSendMessage].
 *
 * [allowCommand] gates command execution: while the agent is running we only
 * ever append the (possibly `/`-prefixed) text as a normal prompt, never
 * executing server commands mid-run (an untested path that could e.g. switch
 * sessions under a live run). Mirrors the official client, which sends
 * unconditionally and lets the server absorb the prompt.
 */
private fun handleComposerSend(
    text: String,
    availableCommands: List<CommandInfo>,
    allowCommand: Boolean,
    onSendMessage: () -> Unit,
    onExecuteCommand: (command: String, arguments: String) -> Unit
) {
    val trimmed = text.trim()
    if (allowCommand && trimmed.startsWith("/")) {
        val withoutSlash = trimmed.removePrefix("/")
        val cmdName = withoutSlash.substringBefore(' ').lowercase()
        val args = withoutSlash.substringAfter(' ', "").trim()
        val known = availableCommands.any { it.name.equals(cmdName, ignoreCase = true) }
        if (known) {
            onExecuteCommand(cmdName, args)
            return
        }
    }
    onSendMessage()
}

@Composable
private fun CommandSuggestionsPanel(
    commands: List<CommandInfo>,
    onPick: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        shape = RoundedCornerShape(12.dp),
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
                        fontFamily = FontFamily.Monospace,
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
private fun QuietComposerStatus(
    status: String,
    isBusy: Boolean,
    startedAtMillis: Long?,
    onAbort: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var nowMillis by remember(startedAtMillis) { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(isBusy, startedAtMillis) {
        while (isBusy && startedAtMillis != null) {
            nowMillis = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isBusy) {
            Icon(
                Icons.Default.Circle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(8.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        if (isBusy && startedAtMillis != null) {
            Text(
                text = formatElapsed(nowMillis - startedAtMillis),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        if (isBusy) {
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.MoreHoriz,
                        contentDescription = stringResource(R.string.chat_interrupt_agent),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_interrupt_agent)) },
                        leadingIcon = { Icon(Icons.Default.Stop, contentDescription = null, tint = StopRed) },
                        onClick = {
                            menuExpanded = false
                            onAbort()
                        }
                    )
                }
            }
        }
    }
}

private fun formatElapsed(elapsedMillis: Long): String {
    val seconds = (elapsedMillis.coerceAtLeast(0L) / 1_000L).toInt()
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun ChatPrimaryActionButton(
    onClick: () -> Unit,
    enabled: Boolean,
    containerColor: Color,
    contentColor: Color,
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
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(containerColor.copy(alpha = effectiveAlpha)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = contentColor.copy(alpha = effectiveAlpha),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

