package cn.vectory.ocdroid.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.theme.AppMotion

// ── §tool-fold FoldBar: collapsed summary of a folded tool run ────────────
// Renders one row per folded segment: per-category Icon + count (slot-machine
// flip animation), optional running spinner, and a trailing chevron. The whole
// row is clickable to expand (onToggleExpand). Visual family matches the
// existing tool cards (surfaceContainer, RectangleShape, labelSmall).

/** M3 icon for a [ToolCategory] (used by both FoldBar and the summary). */
private fun toolCategoryIcon(cat: ToolCategory): ImageVector = when (cat) {
    ToolCategory.READS -> Icons.Default.Description
    ToolCategory.EDITS -> Icons.Default.Edit
    ToolCategory.SHELL -> Icons.Default.Terminal
    ToolCategory.WEB -> Icons.Default.Public
    ToolCategory.THINKING -> Icons.Default.Lightbulb
    ToolCategory.OTHER -> Icons.AutoMirrored.Filled.HelpOutline
}

/**
 * Collapsed summary bar for a [ToolRenderItem.FoldedToolRun]. Shows one
 * Icon + animated count per non-zero category, in [TOOL_CATEGORY_DISPLAY_ORDER]
 * (READS·EDITS·SHELL·WEB·THINKING·OTHER). A trailing [CircularProgressIndicator]
 * appears when [isRunning] (some part in the segment is still in flight), and a
 * [ChevronRight] signals the row is expandable.
 *
 * The count digits use a slot-machine vertical flip ([F6]): the new digit
 * slides up from the bottom while the old digit exits upward, both eased with
 * [AppMotion.standard] at [AppMotion.DURATION_SMALL].
 *
 * @param counts per-category tally (only count > 0 categories are rendered)
 * @param isRunning whether any part in the segment is still "running"
 * @param onToggleExpand called when the whole row is tapped
 * @param modifier outer modifier (caller passes `widthIn(max = cardMax)`)
 */
@Composable
internal fun ToolCallFoldBar(
    counts: Map<ToolCategory, Int>,
    isRunning: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    // §a11y (gpt-3/opu-1): build a spoken description for screen readers from
    // the counts, using the same plurals as ToolCountSummaryText so the FoldBar
    // announces e.g. "3 reads · 1 edit · 1 shell" — matching the on-screen
    // tally rather than just "Expand".
    val readsCount = counts[ToolCategory.READS] ?: 0
    val editsCount = counts[ToolCategory.EDITS] ?: 0
    val shellCount = counts[ToolCategory.SHELL] ?: 0
    val webCount = counts[ToolCategory.WEB] ?: 0
    val thinkingCount = counts[ToolCategory.THINKING] ?: 0
    val otherCount = counts[ToolCategory.OTHER] ?: 0
    val sReads = pluralStringResource(R.plurals.tool_count_reads, readsCount, readsCount)
    val sEdits = pluralStringResource(R.plurals.tool_count_edits, editsCount, editsCount)
    val sShell = pluralStringResource(R.plurals.tool_count_shell, shellCount, shellCount)
    val sWeb = pluralStringResource(R.plurals.tool_count_web, webCount, webCount)
    val sThinking = pluralStringResource(R.plurals.tool_count_thinking, thinkingCount, thinkingCount)
    val sOther = pluralStringResource(R.plurals.tool_count_other, otherCount, otherCount)
    val a11yDescription = remember(
        sReads, sEdits, sShell, sWeb, sThinking, sOther,
        readsCount, editsCount, shellCount, webCount, thinkingCount, otherCount
    ) {
        listOf(
            readsCount to sReads,
            editsCount to sEdits,
            shellCount to sShell,
            webCount to sWeb,
            thinkingCount to sThinking,
            otherCount to sOther,
        ).filter { it.first > 0 }.joinToString(" · ") { it.second }
    }

    Surface(
        modifier = modifier
            .padding(vertical = 2.dp)
            .clickable { onToggleExpand() }
            .semantics {
                contentDescription = a11yDescription
                role = Role.Button
            },
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Render each non-zero category in the stable display order.
            TOOL_CATEGORY_DISPLAY_ORDER.forEach { cat ->
                val c = counts[cat] ?: 0
                if (c > 0) {
                    Icon(
                        toolCategoryIcon(cat),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    // §tool-fold F6/F10: slot-machine digit flip.
                    AnimatedContent(
                        targetState = c,
                        label = "fold_count_${cat.name}",
                        transitionSpec = {
                            // New digit enters from the bottom (full height
                            // offset), old digit exits toward the top.
                            (slideInVertically(
                                AppMotion.standard(AppMotion.DURATION_SMALL)
                            ) { fullHeight -> fullHeight } togetherWith
                                slideOutVertically(
                                    AppMotion.standard(AppMotion.DURATION_SMALL)
                                ) { fullHeight -> -fullHeight })
                                .using(SizeTransform(clip = false))
                        },
                        modifier = Modifier
                    ) { target ->
                        Text(
                            text = "$target",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }
            if (isRunning) {
                // Segment still in flight — trailing spinner (matches the
                // existing card family's 14dp / strokeWidth 2dp convention).
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
