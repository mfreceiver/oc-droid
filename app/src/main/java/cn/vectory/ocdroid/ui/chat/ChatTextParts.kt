package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.theme.LocalMarkdownFontSizes
import cn.vectory.ocdroid.ui.theme.markdownTypography
import cn.vectory.ocdroid.ui.theme.opencode
import cn.vectory.ocdroid.ui.util.DataUriImageTransformer
import cn.vectory.ocdroid.ui.util.HttpImageHolder
import cn.vectory.ocdroid.ui.util.MarkdownImageResolver

// ── Text part rendering: user bubble, assistant markdown, code blocks ─────
// TextPart dispatches between the user's right-aligned neutral bubble and the
// assistant's full-width markdown. ResolvedMarkdownText resolves workspace
// image references before rendering. WrappedCodeBlock (+ codeText /
// codeFenceLanguage extensions) renders fenced code with soft-wrap instead of
// mikepenz's horizontal scroll, surfacing the language as a badge.

@Composable
internal fun TextPart(
    text: String,
    isUser: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    repository: OpenCodeRepository? = null,
    workspaceDirectory: String? = null
) {
    if (isUser) {
        // §4.2 v2 user bubble: right-aligned, layer02 background, 10dp radius,
        // padding 8x12, max ~82% of row width. No blue tint, no left bar — the
        // muted neutral surface + right alignment is enough to distinguish the
        // user's side from the assistant's full-width markdown. BoxWithConstraints
        // gives us the precise 0.82 multiplier (short prompts stay natural-width
        // via widthIn rather than being stretched to 82%).
        val oc = MaterialTheme.opencode
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            val maxBubble = maxWidth * 0.82f
            Surface(
                color = oc.layer02,
                shape = RoundedCornerShape(10.dp),
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
        val innerModifier = modifier.padding(12.dp)
        if (repository != null) {
            ResolvedMarkdownText(
                text = text,
                repository = repository,
                workspaceDirectory = workspaceDirectory,
                modifier = innerModifier
            )
        } else {
            val normalizedText = remember(text) { MarkdownImageResolver.normalizeStandaloneImageBlocks(text) }
            val fontSizes = LocalMarkdownFontSizes.current
            SelectionContainer {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    Markdown(
                        content = normalizedText,
                        typography = markdownTypography(fontSizes),
                        components = markdownComponents(
                            codeBlock = { WrappedCodeBlock(it) },
                            codeFence = { WrappedCodeBlock(it) }
                        ),
                        modifier = innerModifier,
                        imageTransformer = DataUriImageTransformer
                    )
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
    var resolvedText by remember(text, workspaceDirectory) { mutableStateOf<String?>(null) }
    val normalizedText = remember(text) { MarkdownImageResolver.normalizeStandaloneImageBlocks(text) }

    LaunchedEffect(normalizedText, workspaceDirectory, repository) {
        resolvedText = null
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
                    codeFence = { WrappedCodeBlock(it) }
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
 * background + borderBase outline matches the Quiet Tech card styling used by
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
    val oc = MaterialTheme.opencode
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = oc.layer02,
        border = BorderStroke(1.dp, oc.borderBase)
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
                    fontFamily = FontFamily.Monospace,
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
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
