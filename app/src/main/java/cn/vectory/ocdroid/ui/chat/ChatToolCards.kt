package cn.vectory.ocdroid.ui.chat

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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LiveHelp
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import cn.vectory.ocdroid.ui.theme.BundledMonoFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateContentSize
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.ui.theme.AppMotion

// ── Tool cards: ToolCard (general), BasicTool (single-line), ErrorCard ────
// ToolCard is the generic collapsible card for tool parts (icon + name +
// status + expandable input/output/file paths). BasicTool is the borderless
// single-line row for bash/webfetch/websearch/other. ErrorCard is the
// danger-tinted inline error. toolIcon + countDiffLines are shared helpers
// (countDiffLines lives here but is also referenced by ChatPatchCards).

/**
 * Picks the Material icon for a tool based on its name (lowercased, prefix-matched).
 *  - question             → LiveHelp (clarifying question tool)
 *  - webfetch             → Public (web fetch)
 *  - task                 → AccountTree (sub-agent task)
 *  - todowrite/todoread   → Checklist (todo management)
 *  - read/list            → FileOpen (file inspection)
 *  - glob/grep            → Search (file search)
 *  - edit/write/apply_patch/patch → Edit (file mutation)
 *  - bash/terminal/cmd/shell      → Terminal (shell)
 *  - anything else → Build (generic tool)
 *
 * Prefix branches are ordered so more specific tool names win over generic
 * prefixes (e.g. `webfetch` before `read`, `todowrite` before `read`).
 */
internal fun toolIcon(toolName: String?): ImageVector {
    val lower = toolName?.lowercase() ?: return Icons.Default.Build
    return when {
        lower.startsWith("question") -> Icons.AutoMirrored.Filled.LiveHelp
        lower.startsWith("webfetch") -> Icons.Default.Public
        lower.startsWith("task") -> Icons.Default.AccountTree
        lower.startsWith("todowrite") || lower.startsWith("todoread") -> Icons.Default.Checklist
        lower.startsWith("read") || lower.startsWith("list") -> Icons.Default.FileOpen
        lower.startsWith("glob") || lower.startsWith("grep") -> Icons.Default.Search
        lower.startsWith("edit") ||
            lower.startsWith("write") ||
            lower.startsWith("apply_patch") ||
            lower.startsWith("patch") -> Icons.Default.Edit
        lower.startsWith("bash") ||
            lower.startsWith("terminal") ||
            lower.startsWith("cmd") ||
            lower.startsWith("shell") -> Icons.Default.Terminal
        else -> Icons.Default.Build
    }
}

/**
 * Counts added/removed lines in a diff/patch text. Skips diff headers
 * (`+++`/`---`/`@@`/`*** Add File:` / `*** Update File:` lines) so only true
 * content lines are counted. Returns (additions, deletions).
 */
internal fun countDiffLines(text: String): Pair<Int, Int> {
    if (text.isEmpty()) return 0 to 0
    var add = 0
    var del = 0
    for (rawLine in text.split("\n")) {
        if (rawLine.startsWith("+++") || rawLine.startsWith("---")) continue
        if (rawLine.startsWith("@@")) continue
        if (rawLine.startsWith("***")) continue
        when {
            rawLine.startsWith("+") -> add++
            rawLine.startsWith("-") -> del++
        }
    }
    return add to del
}

/**
 * §F2: 统一工具卡展开容器——[surfaceContainer] 填充（与 ReasoningCard/SubAgentCard
 * 同 tonal step，清晰区隔于聊天背景）+ 1dp [outlineVariant] 真边框 + RectangleShape
 * （保持与 reasoning/subagent 同样的全宽 flush 形态，整体卡片家族一致）。折叠态
 * 透明融入聊天流。
 *
 * 应用于 Shell(BasicTool) / Patch / Edit / Write(PatchCard) / Explored(ContextToolGroup)
 * / Question 历史(BasicTool)。**不**用于 ReasoningCard / SubAgentCard（用户要求其保持
 * 既有无框 surfaceContainer 外观）。
 */
@Composable
internal fun ToolCardContainer(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.padding(vertical = 2.dp),
        shape = RectangleShape,
        color = if (expanded) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
        border = if (expanded) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null
    ) {
        content()
    }
}

@Composable
internal fun ToolCard(
    part: Part,
    onFileClick: (String) -> Unit,
    expandedParts: Map<String, Boolean>,
    onToggleExpand: (String, Boolean) -> Unit,
    expandedKey: String,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val toolName = part.tool ?: ""
    val status = part.stateDisplay
    val reason = part.toolReason
    val filePaths = part.filePathsForNavigationFiltered
    val todos = part.toolTodos
    val isTodoWrite = part.tool == "todowrite"
    val input = part.toolInputSummary
    val output = part.toolOutput

    val isRunning = status == "running"
    val isError = status == "error"
    val expanded = expandedParts[expandedKey] ?: isRunning
    val firstFile = filePaths.firstOrNull()
    val displayName = if (toolName == "apply_patch") "patch" else toolName

    val icon = remember(toolName) { toolIcon(toolName) }

    // §5.2 v2 tool card: transparent surface + borderBase + 6dp radius (was
    // surfaceVariant solid + 12dp). The icon classification (read/edit/bash/
    // generic) is preserved, but the v2 neutral look means titles and icons
    // read as muted; only the status indicator uses stateSuccessFg /
    // stateDangerFg to encode tool outcome.

    Surface(
        modifier = modifier.padding(vertical = 2.dp),
        shape = RectangleShape,
        color = if (expanded) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp).then(if (isRunning) Modifier else Modifier.animateContentSize(AppMotion.expandSizeSpec))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = displayName.ifEmpty { reason ?: "tool" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // §5.2 status indicator — spinner while running, stateSuccessFg
                    // check on completion, stateDangerFg warning on error.
                    when {
                        isRunning -> CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        isError -> Icon(
                            Icons.Default.Warning,
                            contentDescription = "Tool error",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    if (firstFile != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(onClick = { onFileClick(firstFile) }, modifier = Modifier.size(22.dp)) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = stringResource(R.string.files_show_in_files),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                    IconButton(onClick = { onToggleExpand(expandedKey, expanded) }) {
                        Icon(
                            if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                            contentDescription = if (expanded) "Collapse" else "Expand",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (expanded) {
                    if (!reason.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // todowrite shows a compact badge; full list is in the toolbar panel (matches iOS).
                    if (isTodoWrite) {
                        if (todos.isNotEmpty()) {
                            Spacer(modifier = Modifier.size(6.dp))
                            val completed = todos.count { it.isCompleted }
                            Text(
                                "Todo updated · $completed/${todos.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        if (todos.isNotEmpty()) {
                            Spacer(modifier = Modifier.size(6.dp))
                            TodoListInline(todos)
                        }
                        if (!input.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(
                                text = input,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = BundledMonoFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!output.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(
                                text = output,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = BundledMonoFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (filePaths.isNotEmpty()) {
                        Spacer(modifier = Modifier.size(6.dp))
                        filePaths.forEach { path ->
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                Text(
                                    text = path.wrappablePath(),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = BundledMonoFamily,
                                    color = MaterialTheme.colorScheme.primary,
                                    softWrap = true,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { onFileClick(path) }, modifier = Modifier.size(22.dp)) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = stringResource(R.string.files_show_in_files),
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── opencode-web paradigm: borderless single-line tool rows ──────────────

/**
 * Compact single-line tool display for non-file-write, non-context, non-task
 * tools (bash, webfetch, websearch, and everything else). Hairline bordered
 * container (borderBase 1dp, 6dp radius, transparent background) for subtle
 * visual grouping without heavy card weight.
 *
 * Collapsed: `[chevron] [spinner] Title · subtitle`
 * Expanded: indented output in monospace.
 *
 * - bash → "Shell · \<command\>", expandable to `$ <command>` + output
 * - webfetch → "Web Fetch · \<url\>", never expandable
 * - websearch → "Web Search · \<query\>", expandable to output
 * - other → "Toolname · \<reason/inputSummary\>", expandable to output
 */
@Composable
internal fun BasicTool(
    part: Part,
    onFileClick: (String) -> Unit,
    expandedParts: Map<String, Boolean>,
    onToggleExpand: (String, Boolean) -> Unit,
    expandedKey: String,
    // §stale-question: when true, override the "running" branch with a
    // terminal "Interrupted" presentation (no spinner, muted icon, stale
    // title). Set by the caller when the part is a question tool part stuck
    // "running" with no matching live QuestionRequest.
    isStale: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val toolName = part.tool ?: ""
    val lowerTool = toolName.lowercase()
    val status = part.stateDisplay
    val isRunning = status == "running" && !isStale
    val isError = status == "error"
    val expanded = expandedParts[expandedKey] ?: false


    val isBash = lowerTool.startsWith("bash") || lowerTool.startsWith("terminal") ||
        lowerTool.startsWith("cmd") || lowerTool.startsWith("shell")
    val isWebFetch = lowerTool.startsWith("webfetch") || lowerTool.startsWith("web_fetch")
    val isWebSearch = lowerTool.startsWith("websearch") || lowerTool.startsWith("web_search")

    val title: String
    val subtitle: String?
    val canExpand: Boolean

    when {
        // §stale-question: terminal "Interrupted" presentation overrides the
        // default title for interrupted question tool parts (no spinner; muted
        // icon + stale title are rendered below).
        isStale -> {
            title = stringResource(R.string.chat_question_stale)
            subtitle = stringResource(R.string.chat_question_stale_hint).takeIf { it.isNotEmpty() }
            canExpand = false
        }
        isBash -> {
            title = "Shell"
            subtitle = part.toolInputSummary?.take(80)
            canExpand = true
        }
        isWebFetch -> {
            title = "Web Fetch"
            subtitle = part.toolInputSummary
            canExpand = false
        }
        isWebSearch -> {
            title = "Web Search"
            subtitle = part.toolInputSummary
            canExpand = true
        }
        else -> {
            title = toolName.replaceFirstChar { c -> c.uppercase() }
            subtitle = part.toolReason ?: part.toolInputSummary
            canExpand = true
        }
    }

    // §F2: 统一工具卡容器（surfaceContainer + 1dp 边框），取代原裸 Surface。
    val showContainer = expanded && canExpand
    ToolCardContainer(
        expanded = showContainer,
        modifier = modifier
    ) {
        Column(modifier = Modifier.then(if (isRunning) Modifier else Modifier.animateContentSize(AppMotion.expandSizeSpec))) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .then(
                        if (canExpand) Modifier.clickable { onToggleExpand(expandedKey, expanded) }
                        else Modifier
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    toolIcon(toolName),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    // §stale-question: muted outline tint marks the part as
                    // interrupted (overrides the default onSurfaceVariant so
                    // the icon reads as "ended", not "in progress").
                    tint = if (isStale) MaterialTheme.colorScheme.outline
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                if (subtitle != null) {
                    Text(
                        text = " · ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                if (isError) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "Tool error",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                if (canExpand) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
    
            // Expanded content
            if (expanded && canExpand) {
                Column(modifier = Modifier.padding(start = 8.dp, top = 2.dp, end = 8.dp, bottom = 6.dp)) {
                    // For bash: show `$ <command>` header
                    if (isBash) {
                        val command = part.toolInputSummary
                        if (!command.isNullOrEmpty()) {
                            Text(
                                text = "$ $command",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = BundledMonoFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        // §gpter-B2: 非 bash（含 Question 历史）展开也显示 inputSummary
                        // （问题/输入文本），否则 Question 历史只能看到回答、看不到问题。
                        val input = part.toolInputSummary
                        if (!input.isNullOrEmpty()) {
                            Text(
                                text = input,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    // Show output
                    val output = part.toolOutput
                    if (!output.isNullOrEmpty()) {
                        if (isBash) Spacer(modifier = Modifier.size(4.dp))
                        Text(
                            text = output,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = BundledMonoFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleExpand(expandedKey, true) }
                            .padding(top = 4.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.ExpandLess,
                            contentDescription = "Collapse",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ── Inline error card ─────────────────────────────────────────────────

/**
 * Compact error card with a danger-tinted border. Follows the same transparent
 * shell pattern as other tool cards, but the border uses [MaterialTheme.colorScheme.error] to
 * signal a problem. The text is selectable for easy copy-paste.
 */
@Composable
internal fun ErrorCard(
    text: String,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    Surface(
        modifier = modifier.padding(vertical = 2.dp),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.width(6.dp))
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    softWrap = true
                )
            }
        }
    }
}
