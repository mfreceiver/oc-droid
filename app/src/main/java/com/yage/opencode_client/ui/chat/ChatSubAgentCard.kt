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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val cleanTitle = remember(rawTitle, description) {
        val strippedTitle = stripSubAgentSuffix(rawTitle)
        // fallback 到 description 时同样要 strip 尾部 marker，否则 marker 会
        // 经 description 字段重新回显到正文行。
        if (strippedTitle.isNotBlank()) strippedTitle
        else stripSubAgentSuffix(description ?: rawTitle)
    }

    val taskXml = remember(part.toolOutput) { parseTaskXml(part.toolOutput) }

    val status = part.stateDisplay
    val isRunning = status == "running" && taskXml?.state?.lowercase() != "completed"
    val isError = status == "error" || taskXml?.state?.lowercase() == "error"

    val canOpen = sessionId != null
    val tagSuffix = sessionId?.let { ".$it" } ?: ""

    val oc = MaterialTheme.opencode
    val statusErrorColor = oc.stateDangerFg

    // Agent tone — name hashed into the unified 16-color agentPalette (方案B).
    // 同一 agent 名永远同色（hash 确定性），随明暗主题切换。无 agent 名时回退
    // 到 accentText。
    val tone = if (subAgentName != null) agentTone(subAgentName, oc) else oc.accentText

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
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Extracts the `xxx` agent name from a sub-agent title/description. 方案B：
 * 无样例容错——按优先级尝试多种常见格式变体，命中第一个非空捕获即返回：
 *   1. "(@xxx subagent)" / "(@xxx Subagent)" / "(@xxx)" —— 带括号，可选
 *      subagent 后缀。可选组 `(?:\s+subagent)?` 匹配零次时即覆盖纯 `(@xxx)`，
 *      匹配一次时覆盖 `(@xxx subagent)`，所以无需再单列纯括号变体（旧
 *      pattern 3 `\(@xxx\)` 永远被 pattern 1 抢先命中，是死代码，已删）。
 *   2. "@xxx subagent" —— 无括号，空格分隔的 subagent 标记
 *
 * 注意：本函数面向服务器返回的 task 元数据 title/description（结构化字段），
 * 不对任意自然语言正文做防误匹配——例如 "ask (@senior-dev) to review" 会命中
 * "senior-dev"，属已知容错行为（方案B）。
 *
 * Returns null when no marker matches.
 */
internal fun parseSubAgentName(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val patterns = listOf(
        // pattern 1: 带括号 + 可选 subagent 后缀。可选组零次匹配即覆盖纯
        // `(@xxx)`，故不再单独列纯括号变体（旧 pattern 3 永远不会执行）。
        Regex("\\(@([A-Za-z0-9_-]+)(?:\\s+subagent)?\\)", RegexOption.IGNORE_CASE),
        // pattern 2: 无括号，空格分隔的 "@xxx subagent"。
        Regex("@([A-Za-z0-9_-]+)\\s+subagent", RegexOption.IGNORE_CASE),
    )
    for (p in patterns) {
        p.find(text)?.groupValues?.getOrNull(1)?.let { if (it.isNotBlank()) return it }
    }
    return null
}

/**
 * Strips the **trailing** sub-agent marker ("(@xxx subagent)" / "@xxx subagent" /
 * "(@xxx)") from a sub-agent title so the body line doesn't redundantly echo
 * what the header's @agentName badge already shows.
 *
 * 只删尾部 suffix：title 格式为 "Description (@xxx subagent)"，marker 恒在
 * 串尾，故用 `$` end-anchor 锚定。正文中间出现的 `(@mention)` 保留不动（旧
 * 实现用全局 replace 会误删中间 mention）。变体集与 [parseSubAgentName]
 * 对齐。返回清理后的 title（marker 是唯一内容时返回空串）；调用方决定是否
 * fallback 到 description。
 */
internal fun stripSubAgentSuffix(text: String?): String {
    if (text.isNullOrBlank()) return ""
    val tailPatterns = listOf(
        // 带括号 + 可选 subagent 后缀，尾部锚定。`(@xxx)` / `(@xxx subagent)`。
        Regex("\\s*\\(@[A-Za-z0-9_-]+(?:\\s+subagent)?\\)\\s*$", RegexOption.IGNORE_CASE),
        // 无括号的 "@xxx subagent"，尾部锚定。
        Regex("\\s*@[A-Za-z0-9_-]+\\s+subagent\\s*$", RegexOption.IGNORE_CASE),
    )
    // 每条 pattern 至多命中尾部一处（`$` 锚定），replaceFirst 即可；fold 让两种
    // 变体都能被尝试剥离。
    return tailPatterns.fold(text) { acc, p -> p.replaceFirst(acc, "") }.trim()
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
