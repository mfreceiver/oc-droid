package cn.vectory.ocdroid.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.ui.theme.AppTextStyles
import cn.vectory.ocdroid.ui.theme.SemanticColors

// ─────────────────────────────────────────────────────────────────────────────
// §B3·P1 UnifiedDiffRenderer — line-level unified-diff renderer.
//
// Replaces the previous DiffBottomSheet "single Text + FontFamily.Monospace
// in a verticalScroll" with a structurally-aware renderer:
//  - parses each line, classifies it (hunk / file-header / added / deleted /
//    context), and applies the project's diff color language;
//  - renders via LazyColumn (row-level composition) so large patches don't
//    blow up layout;
//  - never soft-wraps (decision 9): long code lines scroll horizontally
//    instead of breaking column alignment.
//
// Color semantics (SemanticColors fixed tones + colorScheme.error for red):
//   @@ ... @@        hunk header  → primary fg + faint onSurfaceVariant bg
//   +++ / ---        file header  → primary fg, no bg  (NOT misread as +/- line)
//   +foo             added line   → stateSuccessFg + low-alpha success bg
//   -foo             deleted line → error            + low-alpha error bg
//   ' foo' / 'foo'   context      → onSurface, no bg
//
// Text style is [AppTextStyles.codeBody] (BundledMonoFamily JetBrains Mono,
// 12sp/16lh) — NOT `FontFamily.Monospace`, so the project's bundled mono
// stays consistent with file-path / inline-code rendering (B2·P2 contract).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Renders a unified-diff [patch] string as a vertically-lazy, horizontally-
 * scrollable list of semantically-colored lines.
 *
 * **Parsing**: O(n) split on `'\n'`, cached via `remember(patch)`. Each line
 * is classified by [DiffLineKind] (prefix-based: `@@`, `+++`/`---` (3-char,
 * checked **before** the 1-char `+`/`-` so file headers aren't misread),
 * `+`, `-`, else context).
 *
 * **Rendering**: [LazyColumn] so a 500-line patch only composes visible rows.
 * Each row is a fixed-width [Row] (width = [contentWidth], see below) with
 * the line's background (for +/-/hunk) and a single [Text] with
 * `softWrap = false`.
 *
 * **Row width determinism** (glmer review fix): every row is given an
 * EXPLICIT width — `contentWidth = maxChars × DiffMonoCharWidthDp` — rather
 * than relying on `fillMaxWidth()` inside a LazyColumn (whose cross-axis
 * measure under an unbounded `horizontalScroll` parent is ambiguous and can
 * degrade to a no-op, leaving +/- backgrounds as left-aligned text-width
 * bars instead of full-width GitHub-style bands). The LazyColumn itself is
 * sized with the same `Modifier.width(contentWidth)` so column and rows are
 * identically and deterministically wide. The +/-/hunk row `background`
 * therefore always spans the full row width.
 *
 * **Scroll policy** (decision 9): lines are never soft-wrapped. The whole
 * renderer wraps its [LazyColumn] in one shared `horizontalScroll` so long
 * code lines pan horizontally without desyncing the +/- gutter column. The
 * explicit `contentWidth` (clamped via [DiffMaxMeasureChars]) sizes the
 * horizontal scroll extent so a minified 10k-char line can't OOM layout.
 *
 * **Color source**: added→[SemanticColors.stateSuccessFg], deleted→
 * [androidx.compose.material3.ColorScheme.error], hunk→
 * [androidx.compose.material3.ColorScheme.primary], file-header→primary,
 * context→[androidx.compose.material3.ColorScheme.onSurface]. Backgrounds
 * are the same color at [DiffLineBgAlpha] / [DiffHunkBgAlpha].
 *
 * @param patch raw unified-diff text (may contain `\n`; `\r` is not stripped
 *  because the bundled mono renders it invisibly — if it becomes an issue,
 *  add `.replace("\r", "")` at the parse site).
 * @param modifier outer modifier — caller should constrain height (e.g.
 *  `Modifier.heightIn(max = 480.dp)`) since this composable is vertically
 *  lazy and needs a bounded height to scroll.
 */
@Composable
internal fun UnifiedDiffRenderer(
    patch: String,
    modifier: Modifier = Modifier,
) {
    val lines = remember(patch) { patch.split('\n') }
    // Widest line in monospace chars — clamped so a minified line can't blow
    // up the horizontal-scroll extent (and thus layout memory).
    val maxChars = remember(lines) {
        lines.maxOfOrNull { it.length }
            ?.coerceAtMost(DiffMaxMeasureChars)
            ?: 0
    }
    // §glmer fix: explicit deterministic content width — used for BOTH the
    // LazyColumn and every row so +/- backgrounds span a known, identical
    // width regardless of LazyColumn cross-axis measurement under an
    // unbounded horizontalScroll parent. coerceAtLeast(1) keeps a non-zero
    // width even for an empty / all-blank patch so layout doesn't collapse.
    val contentWidth: Dp = (maxChars.coerceAtLeast(1) * DiffMonoCharWidthDp).dp

    val horizScroll = rememberScrollState()

    // ── Colors resolved once (stateSuccessFg is @Composable theme-aware). ──
    val addedFg = SemanticColors.stateSuccessFg()
    val addedBg = addedFg.copy(alpha = DiffLineBgAlpha)
    val deletedFg = MaterialTheme.colorScheme.error
    val deletedBg = deletedFg.copy(alpha = DiffLineBgAlpha)
    val hunkFg = MaterialTheme.colorScheme.primary
    val hunkBg = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DiffHunkBgAlpha)
    val headerFg = MaterialTheme.colorScheme.primary
    val contextFg = MaterialTheme.colorScheme.onSurface
    val baseStyle = AppTextStyles.codeBody

    Box(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(horizScroll),
    ) {
        // §glmer fix: width(contentWidth) (not widthIn/fillMaxWidth) so the
        // column width is fixed and matches each row's width exactly → no
        // cross-axis measurement ambiguity. horizontalScroll parent reveals
        // the full contentWidth when it exceeds the viewport.
        LazyColumn(
            modifier = Modifier.width(contentWidth),
        ) {
            itemsIndexed(lines, key = { index, _ -> index }) { _, raw ->
                DiffLineRow(
                    raw = raw,
                    rowWidth = contentWidth,
                    addedFg = addedFg,
                    addedBg = addedBg,
                    deletedFg = deletedFg,
                    deletedBg = deletedBg,
                    hunkFg = hunkFg,
                    hunkBg = hunkBg,
                    headerFg = headerFg,
                    contextFg = contextFg,
                    baseStyle = baseStyle,
                )
            }
        }
    }
}

/**
 * Diff-line classification — order matters: the 3-char file-header prefix
 * (`+++` / `---`) is checked **before** the 1-char `+`/`-` so file headers
 * aren't misread as added/deleted lines.
 *
 * `internal` (not `private`) so [DiffLineKind.from] is unit-tested by
 * [UnifiedDiffRendererTest] — this prefix-ordering is the most regression-
 * prone logic in the renderer (a flip reclassifies every `+++`/`---` header
 * as an added/deleted line and breaks the whole diff colorization).
 */
internal enum class DiffLineKind {
    HUNK, FILE_HEADER, ADDED, DELETED, CONTEXT;

    internal companion object {
        fun from(line: String): DiffLineKind = when {
            line.startsWith("@@") -> HUNK
            line.startsWith("+++") || line.startsWith("---") -> FILE_HEADER
            line.startsWith("+") -> ADDED
            line.startsWith("-") -> DELETED
            else -> CONTEXT
        }
    }
}

@Composable
private fun DiffLineRow(
    raw: String,
    rowWidth: Dp,
    addedFg: androidx.compose.ui.graphics.Color,
    addedBg: androidx.compose.ui.graphics.Color,
    deletedFg: androidx.compose.ui.graphics.Color,
    deletedBg: androidx.compose.ui.graphics.Color,
    hunkFg: androidx.compose.ui.graphics.Color,
    hunkBg: androidx.compose.ui.graphics.Color,
    headerFg: androidx.compose.ui.graphics.Color,
    contextFg: androidx.compose.ui.graphics.Color,
    baseStyle: androidx.compose.ui.text.TextStyle,
) {
    val kind = DiffLineKind.from(raw)
    val fg = when (kind) {
        DiffLineKind.HUNK -> hunkFg
        DiffLineKind.FILE_HEADER -> headerFg
        DiffLineKind.ADDED -> addedFg
        DiffLineKind.DELETED -> deletedFg
        DiffLineKind.CONTEXT -> contextFg
    }
    val bg = when (kind) {
        DiffLineKind.HUNK -> hunkBg
        DiffLineKind.ADDED -> addedBg
        DiffLineKind.DELETED -> deletedBg
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    // §glmer fix: explicit width(rowWidth) (not fillMaxWidth) so the
    // background deterministically spans the full content width — identical
    // for every row, regardless of LazyColumn cross-axis measurement.
    Row(
        modifier = Modifier
            .width(rowWidth)
            .background(bg)
            .padding(horizontal = DiffRowHorizontalPadding, vertical = DiffRowVerticalPadding),
    ) {
        Text(
            text = raw,
            style = baseStyle.copy(color = fg),
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

/** Alpha for added/deleted line backgrounds — readable diff tint (decision: 0.08–0.12). */
private const val DiffLineBgAlpha: Float = 0.10f

/** Alpha for the hunk-header background — fainter than line tints. */
private const val DiffHunkBgAlpha: Float = 0.06f

/** Approximate advance (dp) of one JetBrains-Mono glyph at 12sp. Used only to
 *  size the horizontal-scroll floor; exact value is non-critical. */
private const val DiffMonoCharWidthDp: Int = 7

/** Cap on the measured max line length — guards against a minified /
 *  binary-ish 10k-char line OOMing layout via a huge `widthIn` floor. */
private const val DiffMaxMeasureChars: Int = 160

private val DiffRowHorizontalPadding: Dp = 12.dp
private val DiffRowVerticalPadding: Dp = 0.dp
