package com.yage.opencode_client.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.yage.opencode_client.R
import com.yage.opencode_client.data.model.Part
import com.yage.opencode_client.ui.theme.LocalMarkdownFontSizes
import com.yage.opencode_client.ui.theme.markdownTypography
import com.yage.opencode_client.ui.theme.opencode
import com.yage.opencode_client.ui.util.DataUriImageTransformer

// ── Sub-agent card + completed-task card + task XML parsing ──────────────
// SubAgentCard renders a `task` tool part (the only bordered tool card in the
// opencode-web paradigm) with per-agent tone coloring. CompletedTaskCard
// renders the `<task>` XML blocks the server injects as synthetic user prompts
// when a background subagent finishes. parseTaskXml / TaskXmlResult /
// parseSubAgentName / stripSubAgentSuffix are the pure-data helpers shared by
// both cards and by PartView's task-detection branch.

/**
 * Card for `task` tool parts — sub-agent conversations spawned by the main
 * session. This is the **only** tool card that carries a border and background
 * in the opencode-web paradigm (all other tools render as borderless single
 * lines via [BasicTool] or [ContextToolGroup]).
 *
 * Single-line layout:
 *   [spinner/Warning/—]  @agentName · description  [>]
 *
 * - running → spinner + agent name + description subtitle
 * - done    → agent name + description (no status icon, no CheckCircle)
 * - error   → Warning icon + agent name + description
 *
 * Tapping opens the child session in-place via [onOpenSubAgent]. When the
 * child session ID hasn't been assigned yet (task still running with no
 * metadata.sessionID), the card renders but is not clickable.
 */
@Composable
internal fun SubAgentCard(
    part: Part,
    onOpenSubAgent: (String) -> Unit,
    agentColorAssignments: MutableMap<String, Color>,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val sessionId = part.taskSubSessionId
    val rawTitle = part.state?.title
        ?: part.state?.metadataString("description")
        ?: ""
    val description = part.state?.metadataString("description")?.takeIf { it.isNotEmpty() }

    val subAgentName = remember(rawTitle, description) {
        parseSubAgentName(rawTitle) ?: parseSubAgentName(description)
    }
    val cleanTitle = remember(rawTitle, subAgentName) {
        stripSubAgentSuffix(rawTitle).ifBlank { description ?: rawTitle }
    }

    val taskXml = remember(part.toolOutput) { parseTaskXml(part.toolOutput) }

    val status = part.stateDisplay
    val isRunning = status == "running" && taskXml?.state?.lowercase() != "completed"
    val isError = status == "error" || taskXml?.state?.lowercase() == "error"

    val canOpen = sessionId != null
    val tagSuffix = sessionId?.let { ".$it" } ?: ""

    val oc = MaterialTheme.opencode
    val statusErrorColor = oc.stateDangerFg

    // D4: agent tone — known agents keep their fixed tone; unknown agents get a
    // maximin session-stable assignment (farthest from occupied colors) written
    // back into the shared session map so the same agent name always renders the
    // same color and adjacent agents don't collide. tone == accentText when no
    // agent name.
    //
    // `agentTone(...)` is read-only and safe in composition; only the write-back
    // into the session map is deferred to SideEffect to avoid writing state
    // during composition (gpter/glmer D4 阻断项: composition-phase state write).
    val tone = if (subAgentName != null) agentTone(subAgentName, oc, agentColorAssignments) else oc.accentText
    SideEffect {
        if (subAgentName != null) {
            val key = subAgentName.trim().lowercase()
            if (key !in oc.agentTones && key !in agentColorAssignments) {
                agentColorAssignments[key] = tone
            }
        }
    }

    Surface(
        modifier = modifier
            .padding(vertical = 1.dp)
            .testTag("toolcard.subagent$tagSuffix")
            .then(if (canOpen) Modifier.clickable { onOpenSubAgent(sessionId!!) } else Modifier),
        shape = RoundedCornerShape(6.dp),
        color = oc.layer02,
        border = BorderStroke(1.dp, oc.borderBase)
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Leading icon — mirrors web's task icon
                Icon(
                    Icons.Default.AccountTree,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = tone
                )
                Spacer(modifier = Modifier.width(4.dp))
                // Status icon: spinner while running, warning on error, nothing on done
                when {
                    isRunning -> CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = tone,
                        strokeWidth = 2.dp
                    )
                    isError -> Icon(
                        Icons.Default.Warning,
                        contentDescription = "Sub-agent error",
                        modifier = Modifier.size(14.dp),
                        tint = statusErrorColor
                    )
                }
                if (isRunning || isError) Spacer(modifier = Modifier.width(4.dp))

                // Agent name — colored by agentTone (same hue as the leading
                // icon) and bolded so the @mention reads as a strong label.
                if (subAgentName != null) {
                    Text(
                        text = "@$subAgentName",
                        style = MaterialTheme.typography.labelSmall,
                        color = tone,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Description as subtitle
                val bodyTitle = cleanTitle.ifBlank { taskXml?.taskResult?.takeIf { it.isNotBlank() } }.orEmpty()
                if (bodyTitle.isNotBlank()) {
                    if (subAgentName != null) {
                        Text(
                            text = " · ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = bodyTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    // Empty card: show a minimal placeholder so the card isn't a blank frame
                    if (isRunning && subAgentName == null) {
                        Text(
                            text = "Sub-agent starting…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                // Chevron to open sub-agent conversation
                if (canOpen) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "Open sub-agent conversation",
                        modifier = Modifier.size(14.dp),
                        tint = oc.accentText
                    )
                }
            }
        }
    }
}

/**
 * Extracts the `xxx` from a title shaped like "(@xxx subagent)" or
 * "Description (@xxx subagent)". Returns null when no such marker is present.
 */
internal fun parseSubAgentName(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val match = Regex("\\(@([A-Za-z0-9_-]+)\\s*subagent\\)", RegexOption.IGNORE_CASE).find(text)
    return match?.groupValues?.getOrNull(1)
}

/**
 * Strips the trailing "(@xxx subagent)" marker from a sub-agent title so the
 * body line doesn't redundantly echo what the header's @agentName badge already
 * shows. Returns the cleaned title (may be blank if the marker was the only
 * content); the caller decides whether to fall back to the description.
 */
internal fun stripSubAgentSuffix(text: String?): String {
    if (text.isNullOrBlank()) return ""
    return Regex("\\s*\\(@[A-Za-z0-9_-]+\\s*subagent\\)", RegexOption.IGNORE_CASE)
        .replace(text, "")
        .trim()
}

/**
 * Parsed view of a sub-agent `<task …>` XML block that the server sometimes
 * embeds in a `task` tool's state.output once the child session finishes.
 *  - [id]       → the child task id attribute (informational)
 *  - [state]    → "completed" / "error" / etc.
 *  - [taskResult] → inner `<task_result>…</task_result>` body, trimmed
 *
 * Returns null when the output doesn't contain a `<task` tag (the common case —
 * most sub-agent outputs are plain text or empty).
 */
internal data class TaskXmlResult(
    val id: String?,
    val state: String?,
    val taskResult: String?
)

internal fun parseTaskXml(output: String?): TaskXmlResult? {
    if (output.isNullOrBlank()) return null
    val taskIdx = output.indexOf("<task")
    if (taskIdx < 0) return null
    val idMatch = Regex("""<task[^>]*\sid="([^"]+)"""").find(output, taskIdx)
    val stateMatch = Regex("""<task[^>]*\sstate="([^"]+)"""").find(output, taskIdx)
    val resultMatch = Regex("""<task_result>([\s\S]*?)</task_result>""").find(output, taskIdx)
    return TaskXmlResult(
        id = idMatch?.groupValues?.getOrNull(1),
        state = stateMatch?.groupValues?.getOrNull(1),
        taskResult = resultMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    )
}

/**
 * Compact card for background subagent task completion blocks that arrive as
 * user-role text messages (server injects `<task>` XML via ops.prompt).
 * Collapsed by default: icon + "Task completed" / "Task failed" + chevron.
 * Tapping expands to show the task_result / task_error body.
 */
@Composable
internal fun CompletedTaskCard(
    taskResult: TaskXmlResult,
    messageId: String,
    partId: String,
    expandedParts: Map<String, Boolean>,
    onToggleExpand: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val expandedKey = "task|${messageId}|${partId}"
    val expanded = expandedParts[expandedKey] ?: false
    val isError = taskResult.state.equals("error", ignoreCase = true)

    val oc = MaterialTheme.opencode
    Surface(
        modifier = modifier.padding(vertical = 1.dp),
        shape = RoundedCornerShape(6.dp),
        color = oc.layer02,
        border = BorderStroke(1.dp, oc.borderBase)
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand(expandedKey, expanded) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isError) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (isError) oc.stateDangerFg else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(if (isError) R.string.task_failed else R.string.task_completed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                val bodyText = taskResult.taskResult ?: ""
                if (bodyText.isNotBlank()) {
                    val fontSizes = LocalMarkdownFontSizes.current
                    Spacer(modifier = Modifier.size(4.dp))
                    Markdown(
                        content = bodyText,
                        typography = markdownTypography(fontSizes),
                        components = markdownComponents(
                            codeBlock = { WrappedCodeBlock(it) },
                            codeFence = { WrappedCodeBlock(it) }
                        ),
                        imageTransformer = DataUriImageTransformer
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleExpand(expandedKey, true) }
                        .padding(top = 2.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.ExpandLess,
                        contentDescription = "Collapse",
                        modifier = Modifier.size(14.dp),
                        tint = oc.faint
                    )
                }
            }
        }
    }
}
