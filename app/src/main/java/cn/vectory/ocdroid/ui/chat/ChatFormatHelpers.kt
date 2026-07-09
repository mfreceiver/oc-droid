package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.api.CommandInfo
import cn.vectory.ocdroid.data.model.Message
import java.util.Locale

/**
 * §R-19 Sprint 2 #7(b): pure, JVM-testable helpers extracted from
 * `@Composable`-heavy chat UI files whose kover coverage excluded them (see
 * [PickerProviderFilter] for the same pattern). This file groups the small
 * leftover helpers that did not form their own cohesive domain file:
 *
 *   - [handleComposerSend]   (from ChatInputBar.kt) — pure dispatch decision
 *     between server `/command` execution and a normal prompt send.
 *   - [formatCount] / [formatOptionalCount] (from ChatContextUsageDialog.kt)
 *     — locale-stable thousands-grouped Int formatting for the context-usage
 *     bottom sheet.
 *   - [markerLabelFor]       (from ChatMessageContent.kt) — selects the raw
 *     label text for an agent-switched / model-switched / compaction marker
 *     row.
 *
 * None of these capture Composable state, view-models, or context.
 */

/**
 * Routes a composer "send" tap: if the trimmed text starts with `/` AND the
 * first token matches a known [availableCommands] entry AND [allowCommand] is
 * true, dispatch the command via [onExecuteCommand]; otherwise fall through to
 * [onSendMessage] (the trimmed text is appended as a normal prompt).
 *
 * `allowCommand` is `!isBusy`: while a run is in flight we must never execute
 * server commands mid-run (an untested path that could e.g. switch sessions
 * under a live run). Mirrors the official client, which sends unconditionally
 * and lets the server absorb the prompt.
 */
internal fun handleComposerSend(
    text: String,
    availableCommands: List<CommandInfo>,
    allowCommand: Boolean,
    onSendMessage: () -> Unit,
    onExecuteCommand: (command: String, arguments: String) -> Unit,
    onCompact: () -> Unit
) {
    val trimmed = text.trim()
    if (allowCommand && trimmed.startsWith("/")) {
        val withoutSlash = trimmed.removePrefix("/")
        val cmdName = withoutSlash.substringBefore(' ').lowercase()
        // §compact-fix: /compact is a client-side built-in. opencode 1.17.x no
        // longer recognizes it as a prompt / custom command — compaction runs
        // via POST /session/{id}/summarize (ChatViewModel.compactSession →
        // summarizeSession). "compact" IS in availableCommands (for autocomplete
        // via ConnectionCoordinator.localCommands), so without this interception
        // it routes to onExecuteCommand → POST /command → the server rejects it
        // ("Command not found: compact"). Intercept BEFORE the custom-command
        // lookup. (compact takes no args; any trailing text is ignored.)
        if (cmdName == "compact") {
            onCompact()
            return
        }
        val args = withoutSlash.substringAfter(' ', "").trim()
        val known = availableCommands.any { it.name.equals(cmdName, ignoreCase = true) }
        if (known) {
            onExecuteCommand(cmdName, args)
            return
        }
    }
    onSendMessage()
}

/**
 * Formats an [Int] count with US-locale thousands grouping (e.g.
 * `1234567` → `"1,234,567"`). US locale is pinned so the format is stable
 * across the device locale — the context-usage sheet is the only surface
 * that renders raw token counts and a JVM test can assert the literal
 * expected string.
 */
internal fun formatCount(value: Int): String = String.format(Locale.US, "%,d", value)

/**
 * Formats a nullable [Int] count, returning `"-"` for null. The
 * context-usage API marks several token buckets optional (input/output/
 * reasoning/cached-read/cached-write); a missing bucket renders as a dash
 * rather than `"0"` (which would imply "measured and zero").
 */
internal fun formatOptionalCount(value: Int?): String = value?.let(::formatCount) ?: "-"

/**
 * Selects the raw label text for a metadata marker row (the small chip shown
 * above a message that records a session-lifecycle transition):
 *
 * - agent-switched → the agent id (message.agent).
 * - model-switched → the model's id (message.modelId or
 *   message.model?.modelId). The chat list passes this raw; the
 *   [MetadataMarkerRow] chip will format it for display (and could resolve a
 *   friendly name via providers in the future).
 * - compaction       → the marker's pre-formatted body summary, or a generic
 *   localized fallback.
 * - anything else    → "" (no chip rendered).
 */
internal fun markerLabelFor(message: Message): String = when (message.role) {
    "agent-switched" -> message.agent.orEmpty()
    "model-switched" -> message.modelId ?: message.model?.modelId.orEmpty()
    "compaction" -> message.modelId.orEmpty()
    else -> ""
}
