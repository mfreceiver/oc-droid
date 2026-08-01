package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import cn.vectory.ocdroid.ui.theme.LocalAppFontFamily
import cn.vectory.ocdroid.ui.theme.LocalMarkdownFontSizes

// ── batch3 / req-2 streaming markdown renderer ─────────────────────────────
// This file is the @Composable-heavy counterpart to [StreamingMarkdownHelpers.kt]
// (pure logic). It is excluded from kover coverage (same pattern as
// [ChatTextParts.kt]); the pure [HeightShrinkCounter] lives in the helpers file
// and IS covered.
//
// **Batch3 req-2 (streaming plain-text)**: the streaming-branch (isStreaming=true)
// now renders as PLAIN TEXT (correctness priority — avoid per-frame IntelliJ AST
// rebuild and mikepenz Markdown re-parse). The completed-branch Markdown rendering
// in [ChatTextParts.kt] remains unchanged — one full re-parse at finalization.
// [HeightAnchor] / [HeightAnchorRegistry] are preserved for 0-shrink + cross-
// streaming→completed anchor inheritance.
//
// Public surface (all `internal`):
//   • [HeightAnchor]            — production 0-shrink anchor (SubcomposeLayout).
//   • [DebugHeightAnchor]       — + [HeightShrinkCounter] for androidTest.
//   • [HeightAnchorRegistry]    — cross-call-site maxHeight sharing by (stableKey, width).
//   • [StreamingMarkdownRender] — HeightAnchor + plain Text (prod streaming branch).
//   • [DebugStreamingMarkdownRender] — DebugHeightAnchor + plain Text (tests).

/**
 * Cross-call-site maxHeight registry, keyed by the WIDTH-AWARE composite
 * `(stableKey, width)` (T2 / chat-ux-batch branch G).
 *
 * **Why a registry, not `remember(stableKey)`**: [TextPart]'s streaming branch
 * (inside [StreamingMarkdownRender]) and its completed branch (inside a bare
 * [HeightAnchor]) sit at DIFFERENT positions in the composition tree (the
 * `if (isStreaming)` fork). Compose `remember` is position-scoped, so a state
 * remembered in one branch is NOT seen by the other → the anchor would reset
 * when the part finalizes, breaking the seamless streaming→completed transition.
 * The registry decouples the maxHeight from composition position: both branches
 * read/write the same entry by stableKey, so the completed-state anchor inherits
 * the streaming-state maxHeight → no height drop on finalization (ora-2 (iii)).
 *
 * **T2 width-aware key (变宽流式留白 fix)**: the key is now `(stableKey, width)`,
 * NOT the bare stableKey. Previously, when the container width changed (window
 * resize / split-screen / rotation), the recorded maxHeight for a stableKey was
 * measured at the OLD width and would leak into the NEW width — on widen this
 * pinned the visible height above the new natural height, leaving empty bottom
 * space. By making width part of the key, different widths get INDEPENDENT
 * anchors: a width change simply creates a fresh entry at the new width's
 * natural height, and the old (different-width) entry cannot pollute it. No
 * manual `lastWidth` reset is needed — the key composite handles it.
 *
 * **gpter #1 correctness**: every [update] receives the NATURAL height measured
 * by [HeightAnchor]'s SubcomposeLayout (at `maxHeight = Infinity`), NOT a
 * `heightIn(min)`-clamped height. The 0.6.1 `onSizeChanged`-based registry
 * stored clamped heights → stale-width maxHeight polluted new widths after
 * rotation. The SubcomposeLayout measurement is width-correct, and with the
 * width-aware key the registry is automatically width-isolated.
 *
 * **Bounded (glmer#1 / kimo#2)**: the registry is an access-order LRU capped at
 * [MAX_ENTRIES]. A long session's part count × width changes is unbounded, so
 * without a cap the map would grow linearly until process death. Cap = 256 is
 * far above the number of parts Compose composes in one frame (a lazy column
 * materializes only the visible window, typically <50 parts), and currently-
 * composing parts at the current width are the MOST-recently-accessed entries
 * → never evicted while visible. Eviction only hits parts scrolled far off-
 * screen (or at stale widths); when such a part scrolls back it simply re-
 * anchors from a fresh natural-height frame (targetHeight can only be ≥ the
 * current natural height), so the user sees no 0-shrink violation.
 *
 * **0-shrink within the same width**: [update] only RAISES the stored height
 * (`max(current, naturalHeight)`, never decreases) → same `(stableKey, width)`
 * queries are non-decreasing across frames → 0 visible height-shrink.
 */
internal object HeightAnchorRegistry {
    internal const val MAX_ENTRIES = 256

    // §glmer-1/kimo-2: access-order LRU (third ctor arg = true) so both update()
    // and anchorFor() (get) promote the entry; removeEldestEntry evicts the
    // least-recently-used (stableKey,width) past the cap.
    //
    // §T2: the key is `Pair<Any, Int>` = (stableKey, width). Pair's hashCode is
    // stable (delegates to the components' hashCodes), so it works directly as
    // a LinkedHashMap key — no custom wrapper needed.
    private val maxHeightByKey: MutableMap<Pair<Any, Int>, Int> =
        object : LinkedHashMap<Pair<Any, Int>, Int>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Any, Int>, Int>): Boolean =
                size > MAX_ENTRIES
        }

    /**
     * Raises the stored maxHeight for [key] to `max(current, naturalHeight)`.
     * Monotonic: never decreases (0-shrink invariant within the same width).
     *
     * @param key `(stableKey, width)` — different widths are independent.
     * @param naturalHeight the NATURAL content height measured at [key]'s width.
     */
    fun update(key: Pair<Any, Int>, naturalHeight: Int) {
        val current = maxHeightByKey[key] ?: 0
        if (naturalHeight > current) {
            maxHeightByKey[key] = naturalHeight
        }
    }

    /**
     * The current maxHeight anchor for [key] (0 if none / never updated).
     * `key = (stableKey, width)` — different widths return independent anchors.
     */
    fun anchorFor(key: Pair<Any, Int>): Int = maxHeightByKey[key] ?: 0

    /**
     * Clears the entry for [key]. With the width-aware composite key, a width
     * change creates a fresh entry automatically → this is rarely needed in
     * production, but kept for explicit test/diagnostic cleanup.
     */
    fun reset(key: Pair<Any, Int>) {
        maxHeightByKey.remove(key)
    }
}

// ── HeightAnchor (production, SubcomposeLayout) ───────────────────────────

/**
 * Pins the visible height of [content] to `max(H_natural(t), anchor(t-1))` so it
 * is non-decreasing across frames → 0 visible height-shrink (ora-2 (iii)).
 *
 * **gpter #1 fix — SubcomposeLayout, not onSizeChanged**: the production anchor
 * measures [content]'s NATURAL height by subcomposing it and measuring at
 * `maxHeight = Constraints.Infinity` (unbounded), then reporting
 * `layout(w, max(natural, anchor))`. This is the crux of the width-correct
 * anchoring: `onSizeChanged` (0.6.1) observed the height AFTER `heightIn(min)`
 * clamping, so the registry stored a clamped height; on rotation the stale
 * (old-width) clamped maxHeight polluted the new width. SubcomposeLayout
 * measures the true natural height at the current width → the registry always
 * sees a width-correct natural height.
 *
 * **T2 width-aware key (变宽流式留白 fix)**: the registry is keyed on
 * `(effectiveKey, width)`, so the anchor is INTRINSICALLY width-correct —
 * different widths get independent maxHeight entries. No `lastWidth` remember
 * or manual reset is needed: a width change creates a fresh entry at the new
 * width's natural height. On widen, the new width's anchor starts from 0 (its
 * own first frame) instead of inheriting the old width's stale maxHeight → the
 * visible height re-measures down to the true natural height → no empty bottom
 * space. On the FIRST frame at any given width, `anchor=0` so targetHeight =
 * natural (no spurious growth). Within the SAME width, the anchor is monotonic
 * non-decreasing (ora-2 (iii) 0-shrink).
 *
 * **Cross-branch sharing**: the maxHeight lives in [HeightAnchorRegistry] keyed
 * by `(stableKey, width)`, so the completed-state [HeightAnchor] (different
 * composition position, SAME width) inherits the streaming-state anchor →
 * seamless finalization.
 *
 * @param stableKey identity shared between the streaming and completed render
 *  of the SAME part (`"$messageId|$partId"`). Must be non-null for cross-branch
 *  anchor inheritance; null falls back to a per-instance key (local-only anchor).
 * @param content the markdown content whose natural height is to be anchored.
 */
@Composable
internal fun HeightAnchor(
    stableKey: Any?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // §effective-key: a null stableKey gets a per-instance identity so the
    // registry still works (just without cross-branch sharing). Normal callers
    // (TextPart) always pass messageId|partId.
    val effectiveKey: Any = stableKey ?: remember { Any() }

    SubcomposeLayout(modifier = modifier) { constraints ->
        val width = constraints.maxWidth

        // Measure content at the incoming width but UNBOUNDED height → the true
        // natural height (not a parent-imposed heightIn clamp). This is the
        // measurement that onSizeChanged could NOT give us in 0.6.1.
        val unbounded = Constraints(
            minWidth = constraints.minWidth,
            maxWidth = constraints.maxWidth,
            minHeight = 0,
            maxHeight = Constraints.Infinity
        )
        val measurables = subcompose(0, content)
        val placeable = measurables.firstOrNull()?.measure(unbounded)
        val naturalHeight = placeable?.height ?: 0
        val naturalWidth = placeable?.width ?: 0

        // §T2 width-aware composite key: the registry keys on (stableKey, width)
        // so different widths get independent anchors. No lastWidth / reset —
        // a width change simply creates a new entry at the new width's natural
        // height (ora-2 (iii) 0-shrink holds WITHIN the same width; across
        // widths the anchor is freshly rebuilt → no stale leak).
        val compositeKey = effectiveKey to width
        HeightAnchorRegistry.update(compositeKey, naturalHeight)
        val anchor = HeightAnchorRegistry.anchorFor(compositeKey)
        // Visible height = max(natural, anchor) → non-decreasing → 0 shrink.
        val targetHeight = maxOf(naturalHeight, anchor)
        // §fill-width: report the available width when bounded so the chat card
        // spans the message column (matches the pre-ora-2 Box(fillMaxWidth)
        // behavior); fall back to natural width when unbounded.
        val reportWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else naturalWidth

        layout(reportWidth, targetHeight) {
            // Top-align: content sits at (0,0); any reserved anchor space is
            // empty padding at the bottom (where new content will grow into).
            // The card Surface (outer) fills targetHeight, so the reserved
            // space reads as slightly larger bottom padding for a frame or two
            // — strictly better than the height-collapse flicker it prevents.
            placeable?.placeRelative(0, 0)
        }
    }
}

/**
 * Debug variant of [HeightAnchor] that also feeds every reported (visible)
 * size into [counter] so the 0-shrink androidTest can assert
 * `counter.shrinkCount == 0` over a driven growing-text sequence. Identical
 * anchoring logic to [HeightAnchor] (gpter #1: unify prod & debug).
 */
@Composable
internal fun DebugHeightAnchor(
    stableKey: Any?,
    counter: HeightShrinkCounter,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val effectiveKey: Any = stableKey ?: remember { Any() }

    SubcomposeLayout(modifier = modifier) { constraints ->
        val width = constraints.maxWidth

        val unbounded = Constraints(
            minWidth = constraints.minWidth,
            maxWidth = constraints.maxWidth,
            minHeight = 0,
            maxHeight = Constraints.Infinity
        )
        val measurables = subcompose(0, content)
        val placeable = measurables.firstOrNull()?.measure(unbounded)
        val naturalHeight = placeable?.height ?: 0
        val naturalWidth = placeable?.width ?: 0

        // §T2: same width-aware composite key as [HeightAnchor].
        val compositeKey = effectiveKey to width
        HeightAnchorRegistry.update(compositeKey, naturalHeight)
        val anchor = HeightAnchorRegistry.anchorFor(compositeKey)
        val targetHeight = maxOf(naturalHeight, anchor)
        val reportWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else naturalWidth

        // §gpter-3: record the post-anchor VISIBLE size. With a correct anchor
        // this is non-decreasing → shrinkCount stays 0 across the whole stream.
        counter.record(width, targetHeight)

        layout(reportWidth, targetHeight) {
            placeable?.placeRelative(0, 0)
        }
    }
}

// ── Entry composables ─────────────────────────────────────────────────────
// §batch3 req-2: streaming-branch renders as plain Text directly in
// [StreamingMarkdownRender] / [DebugStreamingMarkdownRender] (correctness
// first — no per-frame IntelliJ parser / mikepenz Markdown re-parse). The
// former ora-2 block decomposition + per-frame AST machinery is removed as
// dead code; only [HeightAnchor] / [HeightShrinkCounter] /
// [HeightAnchorRegistry] remain (0-shrink + cross-streaming→completed anchor
// inheritance).

/**
 * Production streaming-markdown entry point (batch3 req-2 plain-text variant):
 * [HeightAnchor] (0-shrink) wrapping a plain [Text] composable with the same
 * font/line-height as the completed-state Markdown body (14sp ×1.4), so the
 * streaming→completed snap has NO font/line-height jump — only formatting
 * appears.
 *
 * No IntelliJ Markdown parser or mikepenz Markdown renderer is called during
 * streaming — correctness priority. The completed branch in [TextPart] runs
 * the full Markdown renderer once at finalization.
 *
 * Used by [TextPart]'s streaming branch. The same [stableKey] MUST also wrap the
 * completed-state render ([HeightAnchor] in [TextPart]'s else-branch) so
 * [HeightAnchorRegistry] carries the maxHeight across the streaming→completed
 * transition → seamless finalization (no height drop on finalization).
 *
 * @param stableKey `"$messageId|$partId"` — shared with the completed branch.
 * @param modifier the inner padding modifier (applied to the Text), e.g. 12.dp.
 */
@Composable
internal fun StreamingMarkdownRender(
    text: String,
    stableKey: Any?,
    modifier: Modifier = Modifier
) {
    val fontSizes = LocalMarkdownFontSizes.current
    val plainTextStyle = TextStyle(
        fontFamily = LocalAppFontFamily.current,
        fontSize = fontSizes.body.sp,
        lineHeight = (fontSizes.body * 1.4f).sp
    )
    HeightAnchor(
        stableKey = stableKey,
        modifier = Modifier.fillMaxWidth()
    ) {
        SelectionContainer {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                Text(
                    text = text,
                    style = plainTextStyle,
                    softWrap = true,
                    modifier = modifier
                )
            }
        }
    }
}

/**
 * Debug streaming-markdown entry point (batch3 req-2 plain-text variant):
 * identical to [StreamingMarkdownRender] but uses [DebugHeightAnchor] so the
 * test can read [HeightShrinkCounter.shrinkCount] after driving a growing-text
 * (and optional width-change) sequence.
 */
@Composable
internal fun DebugStreamingMarkdownRender(
    text: String,
    stableKey: Any?,
    counter: HeightShrinkCounter,
    modifier: Modifier = Modifier
) {
    val fontSizes = LocalMarkdownFontSizes.current
    val plainTextStyle = TextStyle(
        fontFamily = LocalAppFontFamily.current,
        fontSize = fontSizes.body.sp,
        lineHeight = (fontSizes.body * 1.4f).sp
    )
    DebugHeightAnchor(
        stableKey = stableKey,
        counter = counter,
        modifier = Modifier.fillMaxWidth()
    ) {
        SelectionContainer {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                Text(
                    text = text,
                    style = plainTextStyle,
                    softWrap = true,
                    modifier = modifier
                )
            }
        }
    }
}
