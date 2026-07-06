package cn.vectory.ocdroid.ui.chat

/**
 * §R-19 Sprint 2 #7(b): pure helpers that split a streaming/markdown text into
 * render-stable segments and extract code-block bodies / language tags.
 * Extracted verbatim from `ChatTextParts.kt`, where co-locating these
 * JVM-testable helpers inside a `@Composable`-heavy file hid them from kover
 * unit-test coverage (ChatTextPartsKt is excluded from the coverage report —
 * see [PickerProviderFilter] for the same pattern).
 *
 * `fenceMarkerOf` / `splitCodeAndProse` are simple String-in / List-out.
 *
 * `codeText` / `codeFenceLanguage` were previously `private fun
 * MarkdownComponentModel.codeText()` / `.codeFenceLanguage()` extensions —
 * the only fields they read off the model are `content` (a CharSequence) and
 * `node.startOffset` / `node.endOffset` (Ints). They are lifted here as plain
 * top-level functions taking those three primitives, so they can be tested
 * without constructing a mikepenz `MarkdownComponentModel` + intellij-markdown
 * `ASTNode` (neither of which has a trivial public constructor).
 *
 * Behaviour is unchanged: the call site in `WrappedCodeBlock` now reads
 * `codeText(model.content, model.node.startOffset, model.node.endOffset)`.
 */

/**
 * Returns `"```"` / `"~~~"` if the line opens/closes a fence, else null.
 */
internal fun fenceMarkerOf(line: String): String? {
    val t = line.trimStart()
    return when {
        t.startsWith("```") -> "```"
        t.startsWith("~~~") -> "~~~"
        else -> null
    }
}

internal fun splitCodeAndProse(text: String): List<StreamSegment> {
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

/**
 * Extracts the raw code text from a markdown code-block / code-fence span
 * (delimited by [startOffset] / [endOffset] over [content]), stripping the
 * surrounding ``` / ~~~ fence lines (with their optional language tag) when
 * present. For indented code blocks (no fence) the span text is returned
 * as-is. Used by [WrappedCodeBlock] (#9) which renders code with wrapping
 * instead of the library's horizontal scroll.
 */
internal fun codeText(
    content: CharSequence,
    startOffset: Int,
    endOffset: Int
): String {
    val raw = content.subSequence(startOffset, endOffset).toString()
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
 * Extracts the optional language tag from a fenced code-block span (delimited
 * by [startOffset] / [endOffset] over [content]) — e.g. `kotlin` from
 * ` ```kotlin `. Returns the empty string for indented code blocks (no fence)
 * or fences that omit the language. Used by [WrappedCodeBlock] (#9 option 2)
 * to render a language badge in the top-right corner of the card — recovering
 * a hint of the metadata that mikepenz's `MarkdownHighlightedCodeFence`
 * would have surfaced via syntax highlighting.
 */
internal fun codeFenceLanguage(
    content: CharSequence,
    startOffset: Int,
    endOffset: Int
): String {
    val raw = content.subSequence(startOffset, endOffset).toString()
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
