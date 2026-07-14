package cn.vectory.ocdroid.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.ui.theme.AppTextStyles
import cn.vectory.ocdroid.ui.theme.SemanticColors

// ─────────────────────────────────────────────────────────────────────────────
// §WT4 VcsFileRow — the ONE shared changed-file row primitive for the Changes
// pane. Replaces the two divergent renderers that used to live inline in
// ChangesPane.kt:
//  - SESSION   previously used `FileDiffRow`    (consumed FileDiff)
//  - WORKING_TREE previously used `VcsStatusRow` (consumed VcsStatusEntry)
//
// The two old rows differed in ~12 small ways (dot vs no dot, status text vs
// StatusPill, neutral +/- vs semantic green/red, full path vs parent-dir,
// 48dp vs 56dp height, bodySmall vs codeBody for the supporting line, …).
// VcsFileRow unifies them on the WORKING_TREE treatment, which was the richer
// of the two and the one the design spec ratified.
//
// The two source models (FileDiff / VcsStatusEntry) are NOT merged — callers
// pass the already-extracted common fields (basename / parentDir / status /
// additions / deletions) so this primitive stays model-agnostic. Thin adapter
// expressions at each call site do the extraction.
//
// Structural difference KEPT between the two tabs (do NOT flatten here — the
// caller decides grouping / headers / dividers / caps):
//  - SESSION      renders VcsFileRow directly in a flat list (no grouping,
//                 no headers, no dividers, no cap).
//  - WORKING_TREE renders VcsFileRow inside its existing BranchHeader +
//                 "Changed files · N" + VcsStatusGroup sectioning + inset
//                 HorizontalDivider between rows + 50-row cap.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Unified changed-file row for the Changes pane.
 *
 * Layout (M3 [ListItem]):
 *  - **leadingContent**: NONE. The previous SESSION `FileDiffRow` carried an
 *    8dp status-color dot here; it was redundant with the trailing [StatusPill]
 *    (which already paints the status color) and pushed the text off the
 *    standard 16dp inset. Dropping it aligns SESSION with WORKING_TREE and
 *    gives both tabs the same text inset.
 *  - **headlineContent**: file basename, `bodyMedium`, `onSurface`, 1 line +
 *    ellipsis.
 *  - **supportingContent**: parent directory, `codeBody` (mono) at
 *    `bodySmall.fontSize` (12sp) in `onSurfaceVariant`, 1 line + ellipsis.
 *    (Adopted from WORKING_TREE — the old SESSION row duplicated the full
 *    path here, which already starts with the basename shown above.)
 *  - **trailingContent**: [StatusPill] (omitted when [status] is null/blank)
 *    + `+N` in [SemanticColors.stateSuccessFg] / `−M` in
 *    [MaterialTheme.colorScheme.error] using `codeBody` (mono), each count
 *    hidden when zero.
 *
 * @param basename   file name without any leading directory.
 * @param parentDir  parent directory (or a muted placeholder like "·" for
 *                   repo-root files; callers pick the placeholder so the
 *                   two-line rhythm stays intact — pass empty to collapse).
 * @param status     raw VCS status string ("modified" / "added" / …). Null
 *                   or blank suppresses the [StatusPill]. Matched case-
 *                   insensitively for color/label by the helpers.
 * @param additions  added-line count; hidden when `<= 0`.
 * @param deletions  deleted-line count; hidden when `<= 0`.
 * @param onClick    row click handler (file selection → opens diff sheet).
 * @param modifier   outer modifier (the row fills max width).
 */
@Composable
internal fun VcsFileRow(
    basename: String,
    parentDir: String,
    status: String?,
    additions: Int,
    deletions: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier
            .fillMaxWidth()
            // §WT4 unified height: 56dp. The two old rows disagreed (SESSION
            // used 48dp = Dimens.touchTargetMin, WORKING_TREE used 56dp to
            // give the trailing StatusPill + mono counts breathing room).
            // 56 won — it's the richer row's value and matches the M3 list
            // rhythm used elsewhere in the pane. No 56 token exists in
            // Dimens.kt (touchTargetMin=48, spacing2=8 → 48+8=56 but writing
            // it as `touchTargetMin + spacing2` reads cryptically; the literal
            // is clearest). If a `rowMin`/`listItemMin` token lands later,
            // swap this single literal.
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick),
        headlineContent = {
            Text(
                text = basename,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = parentDir,
                // §WT4: codeBody (BundledMonoFamily 12sp/16lh) at bodySmall
                // fontSize — path column-aligns with the trailing +N/−M
                // counts and the diff body.
                style = AppTextStyles.codeBody.copy(
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            // §WT4: StatusPill carries the status color+label; counts reuse
            // codeBody (mono) for column alignment — green via stateSuccessFg,
            // red via colorScheme.error, omitted when 0.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!status.isNullOrBlank()) {
                    StatusPill(status = status)
                }
                if (additions > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "+$additions",
                        style = AppTextStyles.codeBody,
                        color = SemanticColors.stateSuccessFg(),
                        maxLines = 1,
                    )
                }
                if (deletions > 0) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "−$deletions",
                        style = AppTextStyles.codeBody,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                    )
                }
            }
        },
    )
}
