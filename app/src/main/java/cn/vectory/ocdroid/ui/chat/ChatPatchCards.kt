package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import cn.vectory.ocdroid.ui.theme.BundledMonoFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateContentSize
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.ui.theme.SemanticColors
import cn.vectory.ocdroid.ui.theme.AppMotion

// ── Patch card + merged context tool group ───────────────────────────────
// PatchCard is the collapsible diff-stats card for a single file-edit part
// (write/edit/patch/apply_patch). ContextToolGroup is the merged single-line
// summary for a contiguous run of read/glob/grep/list context tools, with
// contextToolLabel producing the per-tool human-readable label.

/**
 * Collapsible card for a single file-edit operation (write/edit/patch/apply_patch).
 * Unified skeleton:
 *
 *   [Edit 14dp] basename  +N -M   [▶]
 *   ---------------------------------
 *   full/path/to/file.ts (+ link to open in Files)
 *
 * Diff stats come from the first [Part.FileChange] in `part.files` when the
 * server provided them; otherwise they're counted from the patch text in
 * `state.inputSummary` / `state.output` by scanning for `+` / `-` line prefixes
 * (skipping `+++`/`---`/`@@`/`***` diff headers). When the part spans multiple
 * files, only the primary one is summarized in the header — the rest are still
 * reachable via the OpenInNew buttons in the expanded body.
 */
@Composable
internal fun PatchCard(
    part: Part,
    onFileClick: (String) -> Unit,
    expandedParts: Map<String, Boolean>,
    onToggleExpand: (String, Boolean) -> Unit,
    expandedKey: String,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val primaryFile = part.files?.firstOrNull()
    // §F2-fix: 用未过滤的 filePathsForNavigation（而非 ...Filtered）——后者要求最后一段
    // 含 "."，会把无扩展名文件（Makefile/Dockerfile/README）丢弃，导致 Write 展开体为空。
    val allPaths = part.filePathsForNavigation
    val primaryPath = remember(part) {
        primaryFile?.path?.replace("\\", "/")?.trim()
            ?: part.metadata?.path?.takeIf { it.isNotEmpty() }
            ?: part.state?.pathFromInput?.takeIf { it.isNotEmpty() }
            ?: allPaths.firstOrNull()
    }
    val basename = remember(primaryPath) {
        primaryPath?.substringAfterLast("/")?.takeIf { it.isNotEmpty() } ?: "file"
    }
    // Fold-state tool label: show the actual tool name (Edit/Write/Apply_patch/
    // Patch) so the card identifies the operation, not just the file. First-
    // letter capitalization mirrors BasicTool's `else` branch; "apply_patch"
    // stays "Apply_patch" (only the leading char is uppercased).
    val toolLabel = remember(part.tool) {
        (part.tool ?: "patch").replaceFirstChar { it.uppercase() }
    }

    val (additions, deletions) = remember(part) {
        val fileAdd = primaryFile?.additions
        val fileDel = primaryFile?.deletions
        if (fileAdd != null || fileDel != null) {
            (fileAdd ?: 0) to (fileDel ?: 0)
        } else {
            val patchText = buildString {
                part.state?.inputSummary?.let { append(it); append('\n') }
                part.state?.output?.let { append(it) }
            }
            countDiffLines(patchText)
        }
    }

    val isRunning = part.stateDisplay == "running"
    val expanded = expandedParts[expandedKey] ?: false

    // Diff stats colored green/red — parity with MultiFilePatchAccordion
    // header (stateSuccessFg / stateDangerFg). Prior §5.4 v3 desaturated
    // them to onSurfaceVariant; updated per user request for consistency.
    // §F2: 统一工具卡容器（surfaceContainer + 1dp 边框）。
    ToolCardContainer(
        expanded = expanded,
        modifier = modifier.testTag("toolcard.patch.$basename")
    ) {
        Column(modifier = Modifier.then(if (isRunning) Modifier else Modifier.animateContentSize(AppMotion.expandSizeSpec))) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .clickable { onToggleExpand(expandedKey, expanded) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    toolIcon(part.tool),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = toolLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(
                    text = " · ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = basename,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (additions > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "+$additions",
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.stateSuccessFg(),
                        fontFamily = BundledMonoFamily
                    )
                }
                if (deletions > 0) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "-$deletions",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = BundledMonoFamily
                    )
                }
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                    if (allPaths.isNotEmpty()) {
                        // #8 (glmer 🟠-1) — apply `wrappablePath()` + softWrap so
                        // long file paths wrap at path separators instead of
                        // being clipped, matching ToolCard (~line 1737) and
                        // ContextToolGroup (~line 2179). PatchCard is NOT inside
                        // any SelectionContainer (verified — same as ToolCard),
                        // so the inserted ZWSPs do not contaminate clipboard
                        // copies; ZWSP-in-selection trade-off is moot here.
                        allPaths.forEach { path ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = path.wrappablePath(),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = BundledMonoFamily,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    softWrap = true,
                                    overflow = TextOverflow.Visible,
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
                    } else if (primaryPath != null) {
                        Text(
                            text = primaryPath.wrappablePath(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = BundledMonoFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            softWrap = true,
                            overflow = TextOverflow.Visible
                        )
                    } else {
                        // §F2-fix: 路径全缺（服务端未填充 files/metadata/state.pathFromInput）
                        // 时，展示实际写入内容摘要，确保展开永远有内容、可看出改了什么。
                        val body = remember(part) {
                            buildString {
                                part.state?.inputSummary?.let { append(it); append('\n') }
                                part.state?.output?.let { append(it) }
                            }.trim().take(600)
                        }
                        if (body.isNotEmpty()) {
                            Text(
                                text = body,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = BundledMonoFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                softWrap = true,
                                overflow = TextOverflow.Visible
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── opencode-web paradigm: merged context tool group ─────────────────────

/**
 * Collapsed single-line summary for a contiguous run of context tools
 * (read / glob / grep / list). Replaces the old 2-column [FileCard] grid.
 *
 * Collapsed: `[spinner] Exploring · 3 reads, 2 searches` + chevron
 * Expanded: each tool as an indented trigger row (title · subtitle).
 */
@Composable
internal fun ContextToolGroup(
    parts: List<Part>,
    expandedParts: Map<String, Boolean>,
    onToggleExpand: (String, Boolean) -> Unit,
    messageId: String,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val expandedKey = "${messageId}|ctx:${parts.first().id}"
    val expanded = expandedParts[expandedKey] ?: false

    val isRunning = parts.any { it.stateDisplay == "running" }

    // Count by category
    val readCount = parts.count { ToolCardClassifier.contextToolCategory(it) == ToolCardClassifier.ContextCategory.READ }
    val searchCount = parts.count { ToolCardClassifier.contextToolCategory(it) == ToolCardClassifier.ContextCategory.SEARCH }
    val listCount = parts.count { ToolCardClassifier.contextToolCategory(it) == ToolCardClassifier.ContextCategory.LIST }

    val countParts = mutableListOf<String>()
    if (readCount > 0) countParts.add("$readCount ${if (readCount == 1) "read" else "reads"}")
    if (searchCount > 0) countParts.add("$searchCount ${if (searchCount == 1) "search" else "searches"}")
    if (listCount > 0) countParts.add("$listCount ${if (listCount == 1) "list" else "lists"}")
    val countText = countParts.joinToString(", ")

    val stateWord = if (isRunning) "Exploring" else "Explored"

    // §F2: 统一工具卡容器（surfaceContainer + 1dp 边框）。
    ToolCardContainer(expanded = expanded, modifier = modifier) {
        Column(modifier = Modifier.then(if (isRunning) Modifier else Modifier.animateContentSize(AppMotion.expandSizeSpec))) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .clickable { onToggleExpand(expandedKey, expanded) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    toolIcon(parts.first().tool),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = "$stateWord · $countText",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
    
            if (expanded) {
                Column(modifier = Modifier.padding(start = 8.dp, top = 2.dp, end = 8.dp, bottom = 4.dp)) {
                    for (part in parts) {
                        val toolLabel = contextToolLabel(part)
                        // §F2-fix: 路径/输入摘要全缺时，回退到输出首行（读了什么的第一行），
                        // 避免 Explored 展开后只剩 "Read · " 空内容。
                        val subtitle = part.toolInputSummary
                            ?: part.state?.pathFromInput
                            ?: part.metadata?.path
                            ?: part.state?.output?.lineSequence()?.firstOrNull()?.take(80)
                            ?: ""
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$toolLabel · ${subtitle.wrappablePath()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                softWrap = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** Human-readable label for a context tool in the expanded group view. */
internal fun contextToolLabel(part: Part): String {
    val tool = part.tool?.lowercase() ?: ""
    return when {
        tool.startsWith("read") -> "Read"
        tool.startsWith("glob") -> "Glob"
        tool.startsWith("grep") -> "Grep"
        tool.startsWith("list") -> "List"
        else -> tool.replaceFirstChar { c -> c.uppercase() }
    }
}
