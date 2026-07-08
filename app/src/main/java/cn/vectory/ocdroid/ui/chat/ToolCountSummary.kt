package cn.vectory.ocdroid.ui.chat

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R

// ── Per-message tool count summary ────────────────────────────────────────
// Quiet one-line tally of the classified tool runs in a message (e.g.
// "3 edits · 2 reads · 1 shell"). Rendered above the detailed tool cards in
// [MessageRow]. Splits into a public guard composable + a private content
// composable so the @Composable call graph is stable whether or not the
// message has any classified runs (an early return before a block of
// @Composable calls would otherwise unbalance the slot table across
// recompositions when counts flips empty↔non-empty).

/**
 * Quiet one-line summary of the classified tool runs in a message — e.g.
 * "3 edits · 2 reads · 1 shell". Renders nothing when the message has no
 * classified tool runs (pure-text/assistant-prose, or a run consisting solely
 * of hidden/sub-agent items). Each count animates via [animateIntAsState] so a
 * streaming message's tally updates smoothly.
 *
 * Categories appear in the stable order EDITS · READS · SHELL · WEB · OTHER,
 * joined by " · "; absent categories are omitted. Styled as a faint
 * [labelSmall] line so it reads as a quiet tally rather than a loud card.
 *
 * Counts are summed from [ToolRenderItem.categoryCounts]: a [ToolRenderItem.ContextGroup]
 * contributes one READ per part it contains, so a read+grep+list group counts as 3 reads.
 */
@Composable
internal fun ToolCountSummary(
    items: List<ToolRenderItem>,
    modifier: Modifier = Modifier
) {
    val counts = remember(items) {
        items.flatMap { it.categoryCounts().entries }
            .groupingBy { it.key }
            .fold(0) { acc, e -> acc + e.value }
    }
    if (counts.isNotEmpty()) {
        ToolCountSummaryText(counts, modifier)
    }
}

@Composable
private fun ToolCountSummaryText(
    counts: Map<ToolCategory, Int>,
    modifier: Modifier
) {
    // Read animated counts up front so the joined string rebuilds each frame a
    // number is in flight. Targets default to 0 for absent categories, which
    // the join below filters out.
    val edits by animateIntAsState(
        targetValue = counts[ToolCategory.EDITS] ?: 0,
        label = "tool_count_edits"
    )
    val reads by animateIntAsState(
        targetValue = counts[ToolCategory.READS] ?: 0,
        label = "tool_count_reads"
    )
    val shell by animateIntAsState(
        targetValue = counts[ToolCategory.SHELL] ?: 0,
        label = "tool_count_shell"
    )
    val web by animateIntAsState(
        targetValue = counts[ToolCategory.WEB] ?: 0,
        label = "tool_count_web"
    )
    val other by animateIntAsState(
        targetValue = counts[ToolCategory.OTHER] ?: 0,
        label = "tool_count_other"
    )

    // Each plural string already embeds its count (e.g. "3 edits"), so the
    // join below just concatenates the resolved strings.
    val sEdits = pluralStringResource(R.plurals.tool_count_edits, edits, edits)
    val sReads = pluralStringResource(R.plurals.tool_count_reads, reads, reads)
    val sShell = pluralStringResource(R.plurals.tool_count_shell, shell, shell)
    val sWeb = pluralStringResource(R.plurals.tool_count_web, web, web)
    val sOther = pluralStringResource(R.plurals.tool_count_other, other, other)

    val text = remember(
        edits, reads, shell, web, other,
        sEdits, sReads, sShell, sWeb, sOther
    ) {
        listOf(
            edits to sEdits,
            reads to sReads,
            shell to sShell,
            web to sWeb,
            other to sOther,
        ).filter { it.first > 0 }
            .joinToString(" · ") { it.second }
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 2.dp)
    )
}
