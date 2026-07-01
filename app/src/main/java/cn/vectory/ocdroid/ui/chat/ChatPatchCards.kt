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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.ui.theme.opencode

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
    val allPaths = part.filePathsForNavigationFiltered
    val primaryPath = remember(part) {
        primaryFile?.path?.replace("\\", "/")?.trim()
            ?: part.metadata?.path?.takeIf { it.isNotEmpty() }
            ?: part.state?.pathFromInput?.takeIf { it.isNotEmpty() }
            ?: allPaths.firstOrNull()
    }
    val basename = remember(primaryPath) {
        primaryPath?.substringAfterLast("/")?.takeIf { it.isNotEmpty() } ?: "file"
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

    val expanded = expandedParts[expandedKey] ?: false

    // §5.4 v3 patch card: fully transparent, diff stats desaturated to
    // onSurfaceVariant (no green/red pop). Same visual weight as BasicTool.
    val oc = MaterialTheme.opencode
    Surface(
        modifier = modifier
            .padding(vertical = 1.dp)
            .testTag("toolcard.patch.$basename"),
        shape = RoundedCornerShape(6.dp),
        color = oc.layer02,
        border = BorderStroke(1.dp, oc.borderBase)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
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
                    text = basename,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (additions > 0 || deletions > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = buildString {
                            if (additions > 0) append("+$additions")
                            if (additions > 0 && deletions > 0) append(" ")
                            if (deletions > 0) append("-$deletions")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
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
                                    fontFamily = FontFamily.Monospace,
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
                                        tint = oc.accentText
                                    )
                                }
                            }
                        }
                    } else if (primaryPath != null) {
                        Text(
                            text = primaryPath.wrappablePath(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
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

    val oc = MaterialTheme.opencode
    Surface(
        modifier = modifier.padding(vertical = 1.dp),
        shape = RoundedCornerShape(6.dp),
        color = oc.layer02,
        border = BorderStroke(1.dp, oc.borderBase)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
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
                        val subtitle = part.toolInputSummary
                            ?: part.state?.pathFromInput
                            ?: part.metadata?.path
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
                            tint = oc.faint
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
