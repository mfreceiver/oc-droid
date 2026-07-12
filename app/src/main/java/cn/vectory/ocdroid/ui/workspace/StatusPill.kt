package cn.vectory.ocdroid.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.theme.Dimens

// ─────────────────────────────────────────────────────────────────────────────
// §B3·P1 StatusPill — compact status tag for VCS file-status.
//
// Replaces the previous ChangesPane WORKING_TREE trailing "8dp color dot +
// tiny status text" (which read as no structure) with a self-contained pill:
// low-alpha same-color background + semantic foreground text + labelMedium.
//
// Centralized mapping so the pill and any future consumer share one source
// of truth:
//  - color: [vcsStatusColor] (WorkspaceVcsHelpers.kt, pure + unit-tested).
//  - label: [vcsStatusLabel] (below, @Composable — resolves stringResource).
//
// Color semantics (case-insensitive status → SemanticColors fixed tone):
//   added    → addedFile    (green)
//   modified → modifiedFile (orange)
//   deleted  → deletedFile  (red)
//   renamed / unknown / null → untrackedFile (grey-blue)
//
// Background is the same color at [StatusPillBgAlpha] (0.12) so the tag
// stays legible without screaming.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Compact status-tag pill for a VCS file-status.
 *
 * Renders a rounded (extraSmall), low-alpha-same-color-background tag with
 * a [MaterialTheme.typography.labelMedium] status label in the semantic
 * foreground color. Designed as the trailing element of the WORKING_TREE
 * changed-file row (and reusable by any future list that surfaces a
 * file-status).
 *
 * Color + label come from the centralized helpers [vcsStatusColor] /
 * [vcsStatusLabel] — see file header for the mapping table.
 *
 * @param status raw VCS status string (e.g. "modified" / "ADDED" / "renamed").
 * @param modifier outer modifier (the pill sizes itself to its content).
 */
@Composable
internal fun StatusPill(
    status: String,
    modifier: Modifier = Modifier,
) {
    val color = vcsStatusColor(status)
    val label = vcsStatusLabel(status)
    Row(
        modifier = modifier
            .background(
                color = color.copy(alpha = StatusPillBgAlpha),
                shape = MaterialTheme.shapes.extraSmall,
            )
            .padding(
                horizontal = Dimens.spacingCompact,
                vertical = StatusPillVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
        )
    }
}

/**
 * Localized label for a VCS file-status. Known statuses resolve to dedicated
 * string resources; unknown values fall back to the raw server-reported
 * status with its first letter uppercased (so "copied" / "typechange" still
 * surface to the user instead of being hidden behind a generic "Unknown").
 *
 * @Composable because it resolves string resources — call from composition
 * scope only. For pure-callable mapping see [vcsStatusGroup].
 */
@Composable
internal fun vcsStatusLabel(status: String): String = when (status.lowercase()) {
    "added" -> stringResource(R.string.vcs_status_added)
    "modified" -> stringResource(R.string.vcs_status_modified)
    "deleted" -> stringResource(R.string.vcs_status_deleted)
    "renamed" -> stringResource(R.string.vcs_status_renamed)
    else -> status
        .replaceFirstChar { ch -> ch.uppercaseChar().toString() }
        .ifEmpty { stringResource(R.string.vcs_status_unknown) }
}

/**
 * Localized section title for a [VcsStatusGroup] (used by the WORKING_TREE
 * grouping headers).
 */
@Composable
internal fun vcsGroupLabel(group: VcsStatusGroup): String = when (group) {
    VcsStatusGroup.MODIFIED -> stringResource(R.string.vcs_group_modified)
    VcsStatusGroup.ADDED -> stringResource(R.string.vcs_group_added)
    VcsStatusGroup.DELETED -> stringResource(R.string.vcs_group_deleted)
    VcsStatusGroup.RENAMED -> stringResource(R.string.vcs_group_renamed)
    VcsStatusGroup.OTHER -> stringResource(R.string.vcs_group_other)
}

/** Alpha for the pill's same-color background — faint so text stays primary. */
private const val StatusPillBgAlpha: Float = 0.12f

/** Vertical padding inside the pill — tight; horizontal uses [Dimens.spacingCompact]. */
private val StatusPillVerticalPadding: Dp = 3.dp
