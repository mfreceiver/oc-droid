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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
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
import cn.vectory.ocdroid.ui.theme.BundledMonoFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.ui.theme.opencode
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
 * Default state: collapsed on first compose; per-file rows hidden until
 * expanded.
 *
 * The header carries the aggregate `+N -M` diff stat (summed across files) and
 * acts as the single expand/collapse toggle. Per-file rows show basename +
 * per-file `+N -M`; when the header is expanded each row also shows the full
 * path with an "open in files" affordance (no per-file secondary collapse).
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

    // Single header-level expand toggle (no per-file collapse anymore).
    // rememberSaveable so the state survives config change. Defaults to
    // collapsed so the chat stays compact; the header carries the aggregate
    // diff stat and is tapped to reveal per-file rows.
    //
    // Keyed on the first Part's id so a reordering / replacement of the patch
    // list (different leading Part) does not inherit a stale expanded state from
    // the previous composition slot — the saveable registry otherwise keys by
    // call-site identity alone.
    var expanded by rememberSaveable(parts.firstOrNull()?.id) { mutableStateOf(false) }

    // Fold-state tool label (PatchCard parity): prefix the header with the
    // actual tool name so multi-file cards also identify the operation.
    val toolLabel = remember(parts.firstOrNull()?.tool) {
        (parts.firstOrNull()?.tool ?: "patch").replaceFirstChar { it.uppercase() }
    }

    Surface(
        modifier = modifier
            .padding(vertical = 2.dp)
            .testTag("toolcard.multi_patch.${files.size}"),
        shape = RoundedCornerShape(6.dp),
        color = oc.layer02,
        border = BorderStroke(1.dp, oc.borderBase)
    ) {
        Column {
            // Header: "N files" + aggregate +N -M + expand/collapse-all toggle.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .clickable { expanded = !expanded },
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
                    text = "${files.size} files",
                    style = MaterialTheme.typography.labelSmall,
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
                        fontFamily = BundledMonoFamily
                    )
                }
                if (totalDel > 0) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "-$totalDel",
                        style = MaterialTheme.typography.labelSmall,
                        color = oc.stateDangerFg,
                        fontFamily = BundledMonoFamily
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                    contentDescription = if (expanded) "Collapse all" else "Expand all",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Per-file rows. Rendered only when the header is expanded so the
            // collapsed card stays a single header line (count + aggregate
            // diff stat); tapping the header reveals basename + full path rows.
            if (expanded) {
                files.forEach { fc ->
                    val path = fc.path
                    val basename = path.substringAfterLast("/").ifEmpty { path }
                    val fileAdd = fc.additions ?: 0
                    val fileDel = fc.deletions ?: 0
                    val isDelete = fc.status?.lowercase() == "delete"

                    Column(modifier = Modifier.padding(horizontal = 10.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .testTag("toolcard.multi_patch.row.$basename"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Indent so the basename aligns with where the per-file
                            // chevron used to sit (16.dp icon + 4.dp spacer = 20.dp).
                            Spacer(modifier = Modifier.width(20.dp))
                            Text(
                                text = basename,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isDelete) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface,
                                fontFamily = BundledMonoFamily,
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
                                        fontFamily = BundledMonoFamily
                                    )
                                }
                                if (fileDel > 0) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "-$fileDel",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = oc.stateDangerFg,
                                        fontFamily = BundledMonoFamily
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, top = 2.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = BundledMonoFamily,
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

                // 底部回收条（与 BasicTool/ContextToolGroup 统一样式）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = false }
                        .padding(top = 2.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.ExpandLess,
                        contentDescription = "Collapse",
                        tint = oc.faint,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}
