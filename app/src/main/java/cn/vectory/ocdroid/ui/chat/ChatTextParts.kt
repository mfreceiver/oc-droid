package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import cn.vectory.ocdroid.ui.theme.BundledMonoFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import com.mikepenz.markdown.m3.Markdown
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.theme.LocalMarkdownFontSizes
import cn.vectory.ocdroid.ui.theme.markdownTypography
import cn.vectory.ocdroid.ui.util.DataUriImageTransformer
import cn.vectory.ocdroid.ui.util.HttpImageHolder
import cn.vectory.ocdroid.ui.util.MarkdownImageResolver
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL

// ── Text part rendering: user bubble, assistant markdown, code blocks ─────
// TextPart dispatches between the user's right-aligned neutral bubble and the

/**
 * Render-layer throttle for streaming text — the anti-flicker mechanism.
 *
 * During streaming the incoming [text] (the accumulated full string) changes on
 * every coalesced delta (~100ms) or faster when full-text snapshots bypass
 * data-layer coalescing. Feeding each change straight into the markdown
 * renderer re-parses the *growing, still-incomplete* markdown every time → the
 * parsed structure (and thus the card height) oscillates frame-to-frame (an
 * unclosed code fence makes everything below render as code, then a token
 * closes it and it reflows shorter) → the visible "card collapses then expands"
 * flicker.
 *
 * This pacer decouples render frequency from data frequency. It samples the
 * latest [text] on a fixed [paceMs] cadence into [displayed], which only ever
 * moves **forward** (the accumulated text only grows, so displayed only grows).
 * The markdown renderer therefore re-parses at most ~paceMs, on a monotonically
 * growing prefix — matching the web client's `PacedMarkdown`/`createPacedValue`
 * approach. This is NOT plain-text rendering: the full markdown renderer still
 * runs, just on a throttled, forward-only value.
 *
 * When streaming ends ([isStreaming] = false) the effect snaps [displayed] to
 * the final text in one go.
 *
 * @return the throttled, forward-only string to feed to the markdown renderer.
 */
@Composable
internal fun rememberPacedStreamingText(
    text: String,
    isStreaming: Boolean,
    paceMs: Long = 100L
): String {
    // rememberUpdatedState keeps the latest text reachable from the
    // once-launched pace loop without restarting it on every token.
    val latest by rememberUpdatedState(text)
    var displayed by remember { mutableStateOf(text) }
    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            // Sample-and-commit loop: advance displayed toward the latest
            // accumulated text every paceMs. displayed only grows because
            // `latest` (the accumulated overlay) only grows during a stream.
            //
            // §flicker-guard: strictly forward-only. A `message.part.updated`
            // fullText sync point (REPLACE) can momentarily report a value that
            // is shorter than or divergent from the delta-accumulated text
            // (server race), which would shrink the rendered markdown and
            // oscillate the card height (flicker). Only advance when `latest`
            // actually extends `displayed` (longer AND displayed is a prefix).
            // A divergent/shrunk latest is ignored until finalization snaps to
            // the authoritative final text below.
            while (true) {
                delay(paceMs)
                if (latest.length >= displayed.length && latest.startsWith(displayed)) {
                    displayed = latest
                }
            }
        } else {
            // Finalization: snap to the complete text immediately.
            displayed = latest
        }
    }
    return displayed
}

// assistant's full-width markdown. ResolvedMarkdownText resolves workspace
// image references before rendering. WrappedCodeBlock (+ codeText /
// codeFenceLanguage extensions) renders fenced code with soft-wrap instead of
// mikepenz's horizontal scroll, surfacing the language as a badge.

// ── Code/prose split for flicker-free streaming with code formatting ─────
// Splits a growing streaming markdown string into ordered segments of PROSE
// and CODE. PROSE renders as plain Text; CODE renders as a formatted code
// block (CodeBlockSurface, the same renderer WrappedCodeBlock uses). Both are
// height-monotonic while growing:
//   - plain Text on a growing string only ever adds lines (never shrinks);
//   - a code block is monospace Text with softWrap — adding lines only ever
//     adds height (never shrinks), EVEN while the fence is still open.
// So the Column of segments has a monotonically-growing total height → 0
// height-shrinks → 0 flicker, while completed (and in-progress) code keeps its
// code-block formatting. Prose loses inline markdown (**bold**, lists, links)
// during streaming and snaps to full markdown on completion — an acceptable
// trade-off since code formatting is the high-value part.
//
// Why not render prose as markdown too: the mikepenz Markdown renderer produces
// pathologically unstable height when re-parsing ANY growing text during rapid
// recomposition (measured: 151 height-shrinks/turn, peak 8213px for ~3500
// chars; boundary-aware "complete-blocks-as-markdown" still gave 57 shrinks /
// 11624px). Code blocks escape this because they render as plain monospace
// Text (no markdown block-structure reflow). This hybrid keeps the valuable
// formatting (code) and drops the unstable part (prose markdown) during stream.
internal sealed class StreamSegment {
    data class Prose(val raw: String) : StreamSegment()
    data class Code(val code: String, val language: String) : StreamSegment()
}

/** Returns `" ``` "` / `"~~~"` if the line opens/closes a fence, else null. */
private fun fenceMarkerOf(line: String): String? {
    val t = line.trimStart()
    return when {
        t.startsWith("```") -> "```"
        t.startsWith("~~~") -> "~~~"
        else -> null
    }
}

private fun splitCodeAndProse(text: String): List<StreamSegment> {
    if (text.isEmpty()) return emptyList()
    val segments = mutableListOf<StreamSegment>()
    val lines = text.split("\n")
    val proseBuf = StringBuilder()
    // fenceMarker != null ⇒ currently inside a code fence of that marker char.
    var fenceMarker: String? = null
    var lang = ""
    val codeBuf = StringBuilder()
    fun flushProse() {
        if (proseBuf.isNotEmpty()) {
            segments.add(StreamSegment.Prose(proseBuf.toString()))
            proseBuf.clear()
        }
    }
    for (line in lines) {
        val marker = fenceMarkerOf(line)
        if (fenceMarker == null) {
            if (marker != null) {
                // A fence opens: freeze the prose accumulated so far, enter code.
                flushProse()
                fenceMarker = marker
                lang = line.substring(marker.length).trim()
                    .substringBefore(' ').removePrefix("{.").removeSuffix("}")
                codeBuf.clear()
            } else {
                proseBuf.append(line).append('\n')
            }
        } else {
            // Inside a fence. A line whose marker matches the opening marker's
            // char closes the fence; anything else is code content.
            if (marker != null && marker[0] == fenceMarker!![0]) {
                segments.add(StreamSegment.Code(codeBuf.toString().trimEnd('\n'), lang))
                fenceMarker = null
                lang = ""
                codeBuf.clear()
            } else {
                codeBuf.append(line).append('\n')
            }
        }
    }
    // End of text: an OPEN fence (not yet closed) is still emitted as a Code
    // segment — a growing code block is height-monotonic, so rendering it
    // formatted mid-stream is stable (no prose↔code seam reflow).
    flushProse()
    if (fenceMarker != null && codeBuf.isNotEmpty()) {
        segments.add(StreamSegment.Code(codeBuf.toString().trimEnd('\n'), lang))
    }
    return segments
}

@Composable
internal fun TextPart(
    text: String,
    isUser: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    repository: OpenCodeRepository? = null,
    workspaceDirectory: String? = null,
    isStreaming: Boolean = false
) {
    // §empty-msg defensive: a Part whose final text is blank renders nothing.
    // Covers (1) a placeholder text Part carried into a persisted session with
    // text=null/"" — the message-level [isEffectivelyRenderableEmpty] catches
    // the all-parts-blank case, but a single blank text Part inside a multi-
    // part message would otherwise leave a blank padded Surface/Box; (2) the
    // brief streaming window where the override is "" (placeholder part
    // inserted, no delta yet). The moment the first token arrives text
    // becomes non-blank and this composable re-renders normally. Both the
    // user-bubble and assistant-markdown branches are guarded.
    if (text.isBlank()) return
    if (isUser) {
        // §4.2 v2 user bubble: right-aligned, layer02 background, 10dp radius,
        // padding 8x12, max ~82% of row width. No blue tint, no left bar — the
        // muted neutral surface + right alignment is enough to distinguish the
        // user's side from the assistant's full-width markdown. BoxWithConstraints
        // gives us the precise 0.82 multiplier (short prompts stay natural-width
        // via widthIn rather than being stretched to 82%).
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            val maxBubble = maxWidth * 0.82f
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .widthIn(max = maxBubble)
            ) {
                SelectionContainer {
                    Text(
                        text = text,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    } else {
        // §body-flicker-fix (v0.2.12): boundary-aware streaming. See
        // splitStableTail(). The mikepenz Markdown renderer produces
        // pathologically unstable height when re-parsing a GROWING incomplete
        // streaming prefix (measured: 151 height-shrinks/turn, peak 8213px for
        // ~3500 chars — same root cause as the reasoning card's 5000px). The
        // render-layer pacer only reduces re-parse FREQUENCY, not the per-parse
        // instability. Fix: during streaming, render COMPLETE leading blocks
        // (paragraphs closed, fences balanced) as markdown — whose height only
        // grows when a new block closes — plus the trailing INCOMPLETE block as
        // plain text — whose height grows monotonically without reflow. Total
        // height never shrinks → 0 flicker, while ~all completed content keeps
        // formatting. On completion, snap to full markdown (with image
        // resolution when a repository is available). Bypassing
        // ResolvedMarkdownText during streaming also eliminates the per-step
        // resolveImages churn (measured 164 redundant runs/turn).
        val renderText = rememberPacedStreamingText(text, isStreaming)
        val fontSizes = LocalMarkdownFontSizes.current
        val innerModifier = modifier.padding(12.dp)
        Box(modifier = Modifier.fillMaxWidth()) {
            if (isStreaming) {
                // §body-flicker-fix hybrid: split into prose + code segments.
                // Prose → plain Text, code → CodeBlockSurface. Both are
                // height-monotonic while growing → 0 flicker, code keeps
                // formatting. See splitCodeAndProse().
                val segments = remember(renderText) { splitCodeAndProse(renderText) }
                SelectionContainer {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                        Column(modifier = innerModifier) {
                            segments.forEach { seg ->
                                when (seg) {
                                    is StreamSegment.Prose -> Text(
                                        text = seg.raw,
                                        // Reuse the markdown body TextStyle (fontFamily +
                                        // fontSize + lineHeight) so the completion snap to
                                        // formatted markdown is height-neutral — avoids the
                                        // bodyMedium-vs-markdown lineHeight mismatch
                                        // (21sp vs body*1.4) that caused a small snap-shrink
                                        // at finalization (maxer 🟡-2).
                                        style = markdownTypography(fontSizes).text,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    is StreamSegment.Code -> CodeBlockSurface(
                                        code = seg.code,
                                        language = seg.language
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Completed: full markdown with image resolution (one-shot).
                if (repository != null) {
                    ResolvedMarkdownText(
                        text = renderText,
                        repository = repository,
                        workspaceDirectory = workspaceDirectory,
                        modifier = innerModifier
                    )
                } else {
                    val normalizedText = remember(renderText) { MarkdownImageResolver.normalizeStandaloneImageBlocks(renderText) }
                    SelectionContainer {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                            Markdown(
                                content = normalizedText,
                                typography = markdownTypography(fontSizes),
                                components = markdownComponents(
                                    codeBlock = { WrappedCodeBlock(it) },
                                    codeFence = { WrappedCodeBlock(it) },
                                    table = { WrappedTable(it) }
                                ),
                                modifier = innerModifier,
                                imageTransformer = DataUriImageTransformer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ResolvedMarkdownText(
    text: String,
    repository: OpenCodeRepository,
    workspaceDirectory: String?,
    modifier: Modifier = Modifier
) {
    var resolvedText by remember(workspaceDirectory) { mutableStateOf<String?>(null) }
    val normalizedText = remember(text) { MarkdownImageResolver.normalizeStandaloneImageBlocks(text) }

    LaunchedEffect(normalizedText, workspaceDirectory, repository) {
        resolvedText = MarkdownImageResolver.resolveImages(
            text = normalizedText,
            workspaceDirectory = workspaceDirectory,
            fetchContent = { path -> repository.getFileContent(path).getOrThrow() }
        )
        val finalText = resolvedText ?: normalizedText
        val httpsUrls = """!\[[^\]]*\]\((https?://[^)]+)\)""".toRegex().findAll(finalText).map { it.groupValues[1] }.toList().distinct()
        for (url in httpsUrls) {
            HttpImageHolder.prefetch(url)
        }
    }

    val fontSizes = LocalMarkdownFontSizes.current
    SelectionContainer {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Markdown(
                content = resolvedText ?: normalizedText,
                typography = markdownTypography(fontSizes),
                // §3.1 syntax highlighting via mikepenz code module + dev.snipme:highlights.
                components = markdownComponents(
                    codeBlock = { WrappedCodeBlock(it) },
                    codeFence = { WrappedCodeBlock(it) },
                    table = { WrappedTable(it) }
                ),
                modifier = modifier,
                imageTransformer = DataUriImageTransformer
            )
        }
    }
}

/**
 * Extracts the raw code text from a markdown code-block / code-fence
 * [MarkdownComponentModel], stripping the surrounding ``` / ~~~ fence lines
 * (with their optional language tag) when present. For indented code blocks
 * (no fence) the span text is returned as-is. Used by [WrappedCodeBlock] (#9)
 * which renders code with wrapping instead of the library's horizontal scroll.
 */
private fun MarkdownComponentModel.codeText(): String {
    val raw = content.subSequence(node.startOffset, node.endOffset).toString()
    val lines = raw.split("\n")
    if (lines.size >= 2) {
        val first = lines.first().trimStart()
        val last = lines.last().trimStart()
        val isFenceStart = first.startsWith("```") || first.startsWith("~~~")
        val isFenceEnd = last.startsWith("```") || last.startsWith("~~~")
        if (isFenceStart && isFenceEnd) {
            return lines.subList(1, lines.size - 1).joinToString("\n")
        }
    }
    return raw
}

/**
 * Extracts the optional language tag from a fenced code block
 * [MarkdownComponentModel] (e.g. `kotlin` from ``` ```kotlin ```). Returns the
 * empty string for indented code blocks (no fence) or fences that omit the
 * language. Used by [WrappedCodeBlock] (#9 option 2) to render a language
 * badge in the top-right corner of the card — recovering a hint of the
 * metadata that mikepenz's `MarkdownHighlightedCodeFence` would have surfaced
 * via syntax highlighting.
 */
private fun MarkdownComponentModel.codeFenceLanguage(): String {
    val raw = content.subSequence(node.startOffset, node.endOffset).toString()
    val first = raw.substringBefore('\n', "").trimStart()
    val isFenceStart = first.startsWith("```") || first.startsWith("~~~")
    if (!isFenceStart) return ""
    // Strip the fence marker (3+ chars of ` or ~), then trim whitespace/quotes.
    val markerEnd = first.takeWhile { it == '`' || it == '~' }.length
    // 🟡 Pandoc/attribute-style fence beautify (glmer 🟡-2): pandoc emits
    // ` ```{.kotlin} ` instead of ` ```kotlin `. Strip the `{.` prefix and `}`
    // suffix so the badge renders `kotlin` rather than the raw `{.kotlin}`.
    return first.substring(markerEnd)
        .trim()
        .substringBefore(' ')
        .removePrefix("{.")
        .removeSuffix("}")
        .ifBlank { "" }
}

/**
 * #9 — code block / code fence renderer that wraps long lines instead of
 * scrolling horizontally. Replaces mikepenz's [highlightedCodeBlock] /
 * [highlightedCodeFence], whose inner [Text] is wrapped in a
 * `Modifier.horizontalScroll` + non-wrapping layout that hides wide content.
 *
 * Trade-off (per spec): we lose syntax highlighting (dev.snipme highlights) in
 * exchange for full content visibility — long code lines soft-wrap onto the
 * next line instead of being clipped or pushed off-screen. The muted layer02
 * background + rectangular tonal surface matches the Quiet Tech card styling used by
 * the other tool cards. Content stays selectable.
 *
 * Mix-approach investigation (#9 step 1): mikepenz's highlighted renderers
 * `MarkdownHighlightedCodeFence` / `MarkdownHighlightedCodeBlock` /
 * `MarkdownHighlightedCode` only accept `(content, node/language, style,
 * Highlights.Builder, showLineNumbers, Composer, ints)` — there is **no
 * `Modifier` parameter** on any public signature, and the horizontal scroll is
 * baked into the internal `MarkdownCodeBackground` layout. So it is impossible
 * to keep syntax highlighting while overriding the scroll behaviour without
 * forking the library. Per spec we therefore fall back to option 2: keep the
 * wrapping renderer but surface the fence's language tag as a small badge in
 * the top-right corner (see [codeFenceLanguage]), recovering a hint of the
 * metadata that the highlighted variant would have communicated visually.
 */
@Composable
internal fun WrappedCodeBlock(model: MarkdownComponentModel) {
    val code = remember(model) { model.codeText() }
    val language = remember(model) { model.codeFenceLanguage() }
    CodeBlockSurface(code = code, language = language)
}

/**
 * The reusable code-block renderer shared by [WrappedCodeBlock] (completed
 * markdown) and the streaming code segments (see [splitCodeAndProse]). A
 * muted layer02 surface + rectangular tonal surface, monospace Text with softWrap so
 * long lines wrap instead of horizontally scrolling/clipping, and an optional
 * language badge in the top-right corner. Content stays selectable. See
 * [WrappedCodeBlock] for the syntax-highlighting trade-off rationale (#9).
 */
@Composable
internal fun CodeBlockSurface(code: String, language: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            SelectionContainer {
                Text(
                    text = code,
                    // top padding is enlarged when the badge is present so the
                    // first code line does not slide under the overlay label.
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 8.dp,
                            end = 8.dp,
                            top = if (language.isNotBlank()) 18.dp else 8.dp,
                            bottom = 8.dp
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = BundledMonoFamily,
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = true,
                    overflow = TextOverflow.Visible
                )
            }
            if (language.isNotBlank()) {
                // Badge sits outside SelectionContainer so it is NOT part of
                // the copied code text — selecting & copying the block yields
                // the raw code without any "kotlin" prefix contamination.
                Text(
                    text = language,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = BundledMonoFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * #table-align — markdown TABLE renderer that WRAPS cell text (no ellipsis) AND
 * keeps every column aligned across rows.
 *
 * Background: mikepenz's default [MarkdownTable] aligns columns by giving every
 * cell `weight(1f)` (equal-width columns stretched wall-to-wall) but truncates
 * cell text at one line (`maxLines = 1, Ellipsis`). An earlier version of this
 * component kept wrapping but used per-cell `widthIn(max = …)` with NO weight.
 * Because every table row was an independent [androidx.compose.foundation.layout.Row]
 * measured separately, each cell took its own content's intrinsic width and the
 * SAME logical column ended up with different widths in different rows →
 * misaligned columns (regression introduced by the "表格换行" change).
 *
 * Fix: the whole table is laid out through a single custom [Layout]
 * ([TableGrid]). It measures every cell once at the per-column cap, derives a
 * SHARED width per column (the widest cell in that column, capped), then
 * re-measures every cell at exactly that shared width. Wrapping is therefore
 * consistent and every column lines up vertically. Columns use a fixed shared
 * width (not `weight`); when the capped column total is LESS than the available
 * width, all columns are scaled up proportionally to fill the screen (remainder
 * onto the last column) — so a few-column table stretches wall-to-wall instead
 * of sitting at a narrow natural width. When the capped column total EXCEEDS
 * the available width the whole table scrolls horizontally (each column keeps
 * its shared cap, cells still wrap).
 *
 * Cell inline content (bold/`code`/links) still flows through mikepenz's
 * [MarkdownTableBasicText], so only the LAYOUT + line policy changed.
 */
@Composable
internal fun WrappedTable(model: MarkdownComponentModel) {
    val node = model.node
    val content = model.content
    val style = model.typography.table
    val cellPadding = LocalMarkdownDimens.current.tableCellPadding
    val header = remember(node) { node.findChildOfType(HEADER) }
    val bodyRows = remember(node) { node.children.filter { it.type == ROW } }
    val columnsCount = remember(node, header, bodyRows) {
        val headerCols = header?.children?.count { it.type == CELL } ?: 0
        val bodyCols = bodyRows.maxOfOrNull { it.children.count { c -> c.type == CELL } } ?: 0
        maxOf(headerCols, bodyCols)
    }
    if (columnsCount == 0) return

    // Per-column max width cap: cells wrap up to this; a column never exceeds it.
    val maxColumnWidth = 160.dp

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        // Horizontal-scroll only when the capped columns genuinely overflow the
        // available width; otherwise the columns are scaled up proportionally
        // inside TableGrid to fill the available width (see measurePolicy).
        val useScroll = maxColumnWidth * columnsCount > maxWidth
        Column(
            modifier = if (useScroll) {
                Modifier.horizontalScroll(rememberScrollState())
            } else {
                Modifier.fillMaxWidth()
            }
        ) {
            TableGrid(
                headerNode = header,
                bodyRows = bodyRows,
                columnsCount = columnsCount,
                maxColumnWidth = maxColumnWidth,
                cellPadding = cellPadding,
                style = style,
                dividerColor = MaterialTheme.colorScheme.outline,
                content = content
            )
        }
    }
}

/**
 * Single custom [Layout] that gives every column ONE shared width across the
 * header row + all body rows. Children are emitted row-major (header cells,
 * then each body row's cells) followed by an optional header divider.
 *
 * Column-width resolution uses [androidx.compose.ui.layout.Measurable.maxIntrinsicWidth]
 * (NOT measure()) for the intrinsic probe, then measures each cell exactly ONCE
 * at its column's shared width. This avoids Compose's hard rule that a single
 * Measurable may only be measured once per layout pass — intrinsic queries are
 * explicitly exempt (the runtime error message points at this exact approach).
 *
 * Column width = min(cell.maxIntrinsicWidth, cap). For cells shorter than the
 * cap this matches the natural content width; for overflowing cells the column
 * pins at the cap so long cells soft-wrap while columns stay aligned.
 */
@Composable
private fun TableGrid(
    headerNode: org.intellij.markdown.ast.ASTNode?,
    bodyRows: List<org.intellij.markdown.ast.ASTNode>,
    columnsCount: Int,
    maxColumnWidth: Dp,
    cellPadding: Dp,
    style: TextStyle,
    dividerColor: Color,
    content: String
) {
    val density = LocalDensity.current
    val capPx = with(density) { maxColumnWidth.roundToPx() }
    val dividerThicknessPx = with(density) { 1.dp.roundToPx() }

    // Flat (rowIndex, colIndex, isHeader, cellNode) list, row-major.
    val cells: List<TableEntry> = remember(headerNode, bodyRows, columnsCount) {
        buildList {
            headerNode?.children?.filter { it.type == CELL }
                ?.forEachIndexed { c, cell ->
                    if (c < columnsCount) add(TableEntry(rowIndex = 0, colIndex = c, isHeader = true, cell))
                }
            bodyRows.forEachIndexed { r, row ->
                row.children.filter { it.type == CELL }.forEachIndexed { c, cell ->
                    if (c < columnsCount) add(TableEntry(rowIndex = r + 1, colIndex = c, isHeader = false, cell))
                }
            }
        }
    }
    val hasDivider = headerNode != null
    val rowCount = remember(cells) { (cells.maxOfOrNull { it.rowIndex } ?: 0) + 1 }

    Layout(
        content = {
            cells.forEach { entry ->
                Column(modifier = Modifier.padding(cellPadding)) {
                    // WRAP instead of ellipsize: maxLines = MAX + overflow = Visible.
                    MarkdownTableBasicText(
                        content = content,
                        cell = entry.cell,
                        style = if (entry.isHeader) style.copy(fontWeight = FontWeight.Bold) else style,
                        maxLines = Int.MAX_VALUE,
                        overflow = TextOverflow.Visible
                    )
                }
            }
            if (hasDivider) {
                HorizontalDivider(color = dividerColor)
            }
        },
        measurePolicy = { measurables, constraints ->
            val totalCells = cells.size
            // Last measurable is the divider when present.
            val dividerMeasurable = if (hasDivider) measurables.getOrNull(totalCells) else null
            val cellMeasurables = if (hasDivider && dividerMeasurable != null) {
                measurables.subList(0, totalCells)
            } else {
                measurables
            }

            // Pass 1: query intrinsic widths. maxIntrinsicWidth() is NOT a
            // measure() call — it returns the unwrapped content width (incl.
            // the cellPadding modifier chain) without consuming the Measurable,
            // so this never trips the "measured multiple times" invariant.
            val colWidths = IntArray(columnsCount)
            for (i in 0 until totalCells) {
                val intrinsic = cellMeasurables[i].maxIntrinsicWidth(Constraints.Infinity)
                val w = minOf(intrinsic, capPx)
                val c = cells[i].colIndex
                if (w > colWidths[c]) colWidths[c] = w
            }

            // §table-fill-width: when the table's natural width is less than the
            // available width (bounded, non-scrolling case), scale all columns up
            // proportionally so the table fills the screen instead of sitting
            // left-aligned at its narrow natural width. Rounding remainder goes
            // onto the last column so the total exactly matches the available width.
            if (constraints.hasBoundedWidth && constraints.maxWidth != Constraints.Infinity) {
                val naturalTotal = colWidths.sum()
                val available = constraints.maxWidth
                if (naturalTotal > 0 && naturalTotal < available) {
                    var assigned = 0
                    val scaled = IntArray(columnsCount)
                    for (c in 0 until columnsCount) {
                        scaled[c] = (colWidths[c].toLong() * available / naturalTotal).toInt().coerceAtLeast(0)
                        assigned += scaled[c]
                    }
                    if (columnsCount > 0) {
                        scaled[columnsCount - 1] += (available - assigned)
                    }
                    for (c in 0 until columnsCount) colWidths[c] = scaled[c].coerceAtLeast(0)
                }
            }

            // Pass 2: single measure per cell at the shared column width so
            // wrapping (and thus row height) reflects the aligned column.
            val placeables = Array(totalCells) { i ->
                val w = colWidths[cells[i].colIndex]
                cellMeasurables[i].measure(
                    Constraints(minWidth = w, maxWidth = w, minHeight = 0, maxHeight = Constraints.Infinity)
                )
            }

            // Row height = tallest cell in that row.
            val rowHeights = IntArray(rowCount)
            for (i in 0 until totalCells) {
                val r = cells[i].rowIndex
                if (placeables[i].height > rowHeights[r]) rowHeights[r] = placeables[i].height
            }

            val tableWidth = colWidths.sum()
            val tableHeight = rowHeights.sum() + if (hasDivider) dividerThicknessPx else 0
            val boundedWidth = if (constraints.hasBoundedWidth) {
                tableWidth.coerceAtMost(constraints.maxWidth)
            } else {
                tableWidth
            }

            // Divider fills the table width.
            val dividerPlaceable = dividerMeasurable?.measure(
                Constraints(
                    minWidth = tableWidth,
                    maxWidth = tableWidth,
                    minHeight = 0,
                    maxHeight = Constraints.Infinity
                )
            )

            layout(boundedWidth, tableHeight) {
                // Column x-offsets (prefix sum).
                val colX = IntArray(columnsCount)
                var acc = 0
                for (c in 0 until columnsCount) {
                    colX[c] = acc
                    acc += colWidths[c]
                }
                // Row y-offsets (prefix sum), inserting the divider after the header.
                val rowY = IntArray(rowCount)
                var accY = 0
                for (r in 0 until rowCount) {
                    rowY[r] = accY
                    accY += rowHeights[r]
                    if (hasDivider && r == 0) {
                        dividerPlaceable?.placeRelative(0, accY)
                        accY += dividerThicknessPx
                    }
                }
                for (i in 0 until totalCells) {
                    val e = cells[i]
                    placeables[i].placeRelative(colX[e.colIndex], rowY[e.rowIndex])
                }
            }
        }
    )
}

private data class TableEntry(
    val rowIndex: Int,
    val colIndex: Int,
    val isHeader: Boolean,
    val cell: org.intellij.markdown.ast.ASTNode
)
