package cn.vectory.ocdroid.ui.chat

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * §0.6.2 ora-2 streaming markdown rewrite — pure helpers (JVM-testable, kover-covered).
 *
 * Co-located with the `@Composable`-heavy [StreamingMarkdownRender.kt] (which is
 * excluded from kover like [ChatTextParts.kt]). The block decomposition + counter
 * logic lives HERE so it is covered by JVM unit tests (see
 * [StreamingMarkdownHelpersTest]) and feeds the 0-shrink androidTest gate.
 *
 * ## ora-2 three mechanisms
 * (i) **block-level decomposition**: only the growing tail re-parses; completed
 *     blocks render independently with a GLOBALLY-stable key (cache reuse),
 *     eliminating mikepenz's "re-parse the whole growing prefix" pathology
 *     (measured 151 height-shrinks/turn).
 * (ii) **tail also renders as Markdown** (not plain Text): the tail Prose unit
 *      goes through the same [Markdown] renderer as completed blocks → inline
 *      formatting (bold / list / link / table) is visible DURING streaming and
 *      the "plain-Text → Markdown" boundary shrink is eliminated (the root
 *      cause of the 57-shrink boundary-aware attempt).
 * (iii) **HeightAnchor**: visible height = max(H_natural(t), H_anchor(t-1)),
 *       non-decreasing → 0 visible-shrink. The anchor is shared across the
 *       streaming → completed transition by stableKey ([HeightAnchorRegistry])
 *       → seamless finalization.
 *
 * ## gpter #2 — globally-stable key
 * 0.6.1 used `seg:$idx` / `tail-block:$localOffset`, so a block's key CHANGED
 * when it promoted from tail to a completed segment → composition cache miss →
 * re-render flicker. The fix: every unit's key is
 * `block:$globalStartOffset:$kind`, which is invariant for a given block
 * regardless of whether it is currently the tail or a completed block. When the
 * tail promotes (a new block starts after it), its key is unchanged → cache hit.
 */

/**
 * One renderable chunk of the streaming markdown text.
 *
 * @property globalStartOffset The unit's start offset in the FULL source text.
 *  Stable across frames for a completed block (its content never moves), so
 *  [stableKey] (derived from it) is stable → Compose `key(...)` cache reuse.
 * @property kind `"prose"` or `"code"`. Together with [globalStartOffset] this
 *  forms a unique, stable identity for the unit across frames.
 */
internal sealed class StreamingRenderUnit {
    abstract val globalStartOffset: Int
    abstract val kind: String

    /**
     * `block:$globalStartOffset:$kind` — globally stable across frames and
     * across the tail→completed promotion (gpter #2 fix). Used as the Compose
     * `key(...)` so a block that was the tail keeps its composition slot when
     * it becomes a completed block (no re-create flicker).
     */
    val stableKey: Any get() = "block:$globalStartOffset:$kind"

    /** A prose / markdown run (paragraphs, headings, lists, tables, quotes...). */
    data class Prose(
        val raw: String,
        override val globalStartOffset: Int
    ) : StreamingRenderUnit() {
        override val kind: String = "prose"
    }

    /** A fenced / indented code block (completed or still-growing open fence). */
    data class Code(
        val code: String,
        val language: String,
        override val globalStartOffset: Int
    ) : StreamingRenderUnit() {
        override val kind: String = "code"
    }
}

/**
 * Splits the streaming markdown [text] into an ordered list of [StreamingRenderUnit]s
 * via a full intellij-markdown parse, then groups consecutive non-code top-level
 * blocks into a single [StreamingRenderUnit.Prose] run (so multi-paragraph prose
 * keeps mikepenz's internal block spacing in one [Markdown] pass) and emits each
 * `CODE_FENCE` / `CODE_BLOCK` as its own [StreamingRenderUnit.Code].
 *
 * The LAST unit in the result is the "tail" — the still-growing block. Only it
 * re-parses each frame; every earlier unit is a completed block whose content +
 * [StreamingRenderUnit.stableKey] are invariant → Compose caches its composition.
 *
 * intellij-markdown trailing-EOL / whitespace handling: consecutive prose blocks
 * are merged by spanning `[first.start, last.end]` (so inter-paragraph blank
 * lines are preserved in the run's substring), and any trailing text after the
 * last block (partial line the parser didn't attach) is appended to the last
 * prose unit so nothing is silently dropped mid-stream.
 *
 * @param text the full accumulated streaming markdown string (only grows).
 */
internal fun buildStreamingRenderUnits(text: String): List<StreamingRenderUnit> {
    if (text.isBlank()) return emptyList()

    // §no-new-dep: GFMFlavourDescriptor + MarkdownParser come transitively via
    // mikepenz (org.jetbrains:markdown). A fresh parser per call is cheap (the
    // flavour is stateless); the parse itself is the per-frame cost that ora-2
    // (i) avoids re-paying for completed blocks by caching their composition.
    val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text)
    val children = tree.children
    if (children.isEmpty()) {
        // No structured blocks (e.g. text is only whitespace tokens) — render
        // the whole thing as one prose tail so it still shows up.
        return listOf(StreamingRenderUnit.Prose(text, 0))
    }

    val units = mutableListOf<StreamingRenderUnit>()

    // Prose-run accumulation: a run spans [runStart, runEnd] over the source
    // text, capturing everything between the first and last consecutive
    // non-code block (including inter-paragraph blank lines).
    var runStart = -1
    var runEnd = -1
    fun flushProseRun() {
        if (runStart >= 0 && runEnd >= runStart) {
            units.add(StreamingRenderUnit.Prose(text.substring(runStart, runEnd), runStart))
        }
        runStart = -1
        runEnd = -1
    }

    for (child in children) {
        val type = child.type
        val start = child.startOffset
        val end = child.endOffset
        if (type === MarkdownElementTypes.CODE_FENCE || type === MarkdownElementTypes.CODE_BLOCK) {
            // A code block terminates any in-progress prose run, then emits as
            // its own Code unit with a globally-stable key.
            flushProseRun()
            // §gpter-r3 #2: pass the AST block type so streamingCodeBody does NOT
            // infer fence-vs-indented from the text appearance (an indented
            // CODE_BLOCK whose content happens to start with ``` would otherwise
            // be mis-read as a fence opener and lose its first line).
            val isFence = type === MarkdownElementTypes.CODE_FENCE
            val code = streamingCodeBody(text, start, end, isFence = isFence)
            // §gpter-r4 #3: only fenced blocks carry a language tag (from the
            // opener's info string). An indented CODE_BLOCK has no language —
            // codeFenceLanguage() is a text heuristic that would otherwise
            // spuriously badge an indented block whose content starts with
            // "```lang".
            val language = if (isFence) codeFenceLanguage(text, start, end) else ""
            units.add(StreamingRenderUnit.Code(code, language, start))
        } else {
            // Prose-like block (PARAGRAPH / ATX_# / ORDERED_LIST / UNORDERED_LIST /
            // BLOCK_QUOTE / HTML_BLOCK / LINK_DEFINITION / SETEXT_# / HORIZONTAL_RULE /
            // TABLE ...). Extend the current prose run; consecutive prose blocks
            // merge into one Markdown render pass.
            if (runStart < 0) runStart = start
            runEnd = end
        }
    }
    flushProseRun()

    // §trailing-remainder: intellij-markdown may leave trailing whitespace / a
    // partial line unattached to any top-level block (e.g. a lone half-typed
    // fence opener ``` with no content yet). Append it to the last prose unit
    // (or create one) so streaming never drops content the user has received.
    val lastChildEnd = children.last().endOffset
    if (lastChildEnd < text.length) {
        val remainder = text.substring(lastChildEnd)
        if (remainder.isNotEmpty()) {
            val lastUnit = units.lastOrNull()
            if (lastUnit is StreamingRenderUnit.Prose) {
                units[units.lastIndex] = lastUnit.copy(raw = lastUnit.raw + remainder)
            } else {
                units.add(StreamingRenderUnit.Prose(remainder, lastChildEnd))
            }
        }
    }
    return units
}

/**
 * Extracts the raw code body from a `CODE_FENCE` / `CODE_BLOCK` span.
 *
 * For a fenced block ([isFence] = true): strips the opening fence line
 * (``` / ~~~ + optional language) and, when present, the closing fence line —
 * handling BOTH a closed fence (completed block) and an OPEN fence (the
 * still-growing streaming tail). The closer check is CommonMark-correct: same
 * fence char as the opener, length >= opener, and at most 3 leading spaces
 * (a 4+-space-indented fence-like line is CODE content, not a closer).
 *
 * For an indented block ([isFence] = false, `CODE_BLOCK`): returned verbatim —
 * there is no fence opener/closer to strip, and the content is taken as-is
 * (gpter-r3 #2: never treat an indented block's fence-looking line as an
 * opener/closer, which would drop real content).
 *
 * @param isFence true for `CODE_FENCE` (```/~~~ blocks), false for `CODE_BLOCK`
 *  (indented). Drives whether fence-opener/closer stripping is applied.
 */
private fun streamingCodeBody(
    content: CharSequence,
    start: Int,
    end: Int,
    isFence: Boolean
): String {
    val span = content.subSequence(start, end).toString()
    // §gpter-r3 #2: indented code blocks have no fence structure — return verbatim
    // so a fence-looking content line is never mis-stripped as opener/closer.
    if (!isFence) return span.trimEnd('\n')

    val lines = span.split("\n")
    if (lines.isEmpty()) return ""
    val first = lines.first().trimStart()
    // §gpter-r2: capture the opener's fence char + run length so the closer
    // check below is CommonMark-correct (same char, length >= opener). A 4-
    // backtick opener is NOT closed by a 3-backtick line, and a backtick opener
    // is NOT closed by a tilde line — both are valid CODE content, not closers.
    val openerFenceChar: Char? = when {
        first.startsWith("```") -> '`'
        first.startsWith("~~~") -> '~'
        else -> null
    }
    val openerFenceLen = if (openerFenceChar != null) {
        first.takeWhile { it == openerFenceChar }.length
    } else 0
    val body: List<String> = if (openerFenceChar != null) {
        if (lines.size >= 2) lines.subList(1, lines.size) else emptyList()
    } else {
        // Fenced block whose opener line didn't parse as a fence (parser edge) —
        // return all lines as the body.
        lines
    }
    // §gpter-r3 #1: closer must have at most 3 leading spaces (CommonMark); a
    // 4+-space-indented fence-like line is CODE content. Plus §gpter-r2: same
    // fence char as opener + length >= opener. intellij's CODE_FENCE endOffset
    // authoritatively delimits closed fences; this guards open-fence + edges.
    if (body.isNotEmpty() && openerFenceChar != null) {
        val lastLine = body.last()
        val lastLeadingSpaces = lastLine.takeWhile { it == ' ' }.length
        val lastCore = lastLine.trim()
        val isCloser = lastLeadingSpaces <= 3 &&
            lastCore.length >= openerFenceLen &&
            lastCore.all { it == openerFenceChar }
        if (isCloser) {
            return body.subList(0, body.lastIndex).joinToString("\n").trimEnd('\n')
        }
    }
    return body.joinToString("\n").trimEnd('\n')
}

/**
 * Records the sequence of reported (visible, post-anchor) layout sizes and
 * counts how many times the height DECREASED frame-to-frame — a "shrink".
 *
 * A correct [HeightAnchor] / [DebugHeightAnchor] produces `shrinkCount == 0`
 * because the visible height is pinned to `max(natural(t), anchor(t-1))`, which
 * is non-decreasing. Only the width-CHANGE frame (rotation / split-screen) is
 * EXCLUDED: its visible height is a different-width baseline. A subsequent
 * same-width shrink IS counted — the [HeightAnchorRegistry] rebuilds the anchor
 * at the new width so `max(natural, anchor)` is non-decreasing thereafter, making
 * any later drop a real anchor defect.
 * [widthResetCount] tracks how many width changes occurred (for test clarity).
 *
 * This is a pure value tracker — the [DebugHeightAnchor] feeds it
 * `(width, targetHeight)` each layout pass; the 0-shrink androidTest reads
 * [shrinkCount] after driving a growing-text sequence.
 */
internal class HeightShrinkCounter {
    private var lastHeight: Int = -1
    private var lastWidth: Int = -1
    var shrinkCount: Int = 0
        private set
    var widthResetCount: Int = 0
        private set

    /**
     * @param width the layout width for this frame (constraints.maxWidth).
     * @param height the reported (visible, post-anchor) height for this frame.
     */
    fun record(width: Int, height: Int) {
        val widthChanged = lastWidth != -1 && lastWidth != width
        // §gpter-1: only the width-CHANGE frame is skipped (its height is a
        // different-width baseline, not comparable). After the reset the
        // [HeightAnchorRegistry] rebuilds the anchor at the new width, so
        // `max(natural(t), anchor)` is non-decreasing from then on — any later
        // same-width drop is a real anchor defect, so it IS counted.
        if (!widthChanged && lastHeight != -1 && height < lastHeight) {
            shrinkCount++
        }
        if (widthChanged) {
            widthResetCount++
        }
        lastHeight = height
        lastWidth = width
    }

    /** Resets all counters (a fresh counter is also fine to construct). */
    fun reset() {
        lastHeight = -1
        lastWidth = -1
        shrinkCount = 0
        widthResetCount = 0
    }
}
