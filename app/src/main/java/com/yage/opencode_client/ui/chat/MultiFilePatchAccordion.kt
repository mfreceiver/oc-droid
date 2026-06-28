package com.yage.opencode_client.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.R
import com.yage.opencode_client.data.model.Part
import com.yage.opencode_client.ui.theme.opencode
import androidx.compose.ui.res.stringResource

/**
 * §6 (R-E) Multi-file `apply_patch` accordion.
 *
 * **Key fact (max B1)**: `apply_patch` is **one Part** with an embedded
 * `Part.files: List<FileChange>` field — not multiple Parts. When a single
 * write/patch Part carries more than one file, render this accordion so every
 * file gets its own row instead of being hidden inside one PatchCard's
 * expanded body.
 *
 * Default-expand policy: every non-delete file is expanded on first compose.
 * `status == null` is treated as non-delete (review fix — null != "delete").
 * Delete files start collapsed to keep the focus on additions/updates.
 *
 * The header carries the aggregate `+N -M` diff stat (summed across files).
 * Per-file rows show basename + per-file `+N -M` and expand to show the full
 * path with an "open in files" affordance.
 *
 * No `callId` cross-Part matching is performed — the caller has already
 * aggregated write Parts, and per §6 the unit of aggregation is the single
 * apply_patch Part's `files` field.
 *
 * @param parts Typically `listOf(singleApplyPatchPart)`. Accepts a list for
 *   forward-compat with future cross-Part aggregation (R-E notes this is the
 *   per-message scope; §7 cross-message turn diff is out of scope this round).
 */
@Composable
internal fun MultiFilePatchAccordion(
    parts: List<Part>,
    onFileClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val oc = MaterialTheme.opencode

    // Flatten files across the (typically single) Part. remember on `parts` so
    // re-composition with the same parts identity doesn't re-allocate.
    val files = remember(parts) { parts.flatMap { it.files ?: emptyList() } }
    if (files.isEmpty()) return

    // Default-expanded set: every non-delete file's path. status==null counts
    // as non-delete (review fix — a null status is the common case for writes
    // and edits, and treating it as delete would collapse everything by default).
    // rememberSaveable so the expand state survives config change without being
    // reset to the default-expand policy each time.
    var expandedPaths by rememberSaveable(files) {
        mutableStateOf(
            files.filter { it.status?.lowercase() != "delete" }.map { it.path }.toSet()
        )
    }

    // Aggregate diff totals for the header. Per-file additions/deletions come
    // from FileChange when the server provided them; missing values count as 0.
    val (totalAdd, totalDel) = remember(files) {
        var a = 0
        var d = 0
        for (fc in files) {
            a += fc.additions ?: 0
            d += fc.deletions ?: 0
        }
        a to d
    }
    val allExpanded = expandedPaths.size == files.size

    Surface(
        modifier = modifier
            .padding(vertical = 2.dp)
            .testTag("toolcard.multi_patch.${files.size}"),
        shape = RoundedCornerShape(6.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        border = BorderStroke(1.dp, oc.borderBase)
    ) {
        Column {
            // Header: "N files" + aggregate +N -M + expand/collapse-all toggle.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${files.size} files",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (totalAdd > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "+$totalAdd",
                        style = MaterialTheme.typography.labelSmall,
                        color = oc.stateSuccessFg,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (totalDel > 0) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "-$totalDel",
                        style = MaterialTheme.typography.labelSmall,
                        color = oc.stateDangerFg,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    expandedPaths = if (allExpanded) emptySet() else files.map { it.path }.toSet()
                }) {
                    Icon(
                        if (allExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                        contentDescription = if (allExpanded) "Collapse all" else "Expand all",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Per-file rows.
            files.forEach { fc ->
                val path = fc.path
                val basename = path.substringAfterLast("/").ifEmpty { path }
                val isExpanded = path in expandedPaths
                val fileAdd = fc.additions ?: 0
                val fileDel = fc.deletions ?: 0
                val isDelete = fc.status?.lowercase() == "delete"

                Column(modifier = Modifier.padding(horizontal = 10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .testTag("toolcard.multi_patch.row.$basename")
                            .clickable {
                                expandedPaths = if (isExpanded) {
                                    expandedPaths - path
                                } else {
                                    expandedPaths + path
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = basename,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isDelete) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (isDelete) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "deleted",
                                style = MaterialTheme.typography.labelSmall,
                                color = oc.stateDangerFg
                            )
                        } else {
                            if (fileAdd > 0) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "+$fileAdd",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = oc.stateSuccessFg,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (fileDel > 0) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "-$fileDel",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = oc.stateDangerFg,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = isExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, top = 2.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { onFileClick(path) },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = stringResource(R.string.files_show_in_files),
                                    modifier = Modifier.size(14.dp),
                                    tint = oc.accentText
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
