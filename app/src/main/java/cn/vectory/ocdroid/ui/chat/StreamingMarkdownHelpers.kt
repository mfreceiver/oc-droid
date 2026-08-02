package cn.vectory.ocdroid.ui.chat

/**
 * batch3 / req-2 streaming markdown helpers.
 *
 * **ora-2 → batch3 req-2**: streaming-branch now renders as plain Text
 * directly in [StreamingMarkdownRender] — the per-frame IntelliJ Markdown
 * parser and block-level decomposition are REMOVED as dead code.
 *
 * What remains:
 * - [HeightShrinkCounter] — 0-shrink counting for DebugHeightAnchor (preserved).
 *   The HeightAnchor mechanism (defined in [StreamingMarkdownRender.kt]) is
 *   unchanged: it still provides 0-shrink anchoring and cross-streaming→completed
 *   maxHeight inheritance via [HeightAnchorRegistry].
 *
 * The companion test file [StreamingMarkdownHelpersTest] now covers only
 * [HeightShrinkCounter] + [HeightAnchorRegistry] (JVM unit tests);
 * the 0-shrink androidTest [StreamingMarkdownZeroShrinkTest] remains unchanged.
 */

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
