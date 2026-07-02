package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.TodoItem
import cn.vectory.ocdroid.ui.theme.LocalIsDarkTheme
import cn.vectory.ocdroid.ui.theme.LocalMarkdownFontSizes
import cn.vectory.ocdroid.ui.theme.markdownTypography
import cn.vectory.ocdroid.ui.theme.opencode
import cn.vectory.ocdroid.ui.util.DataUriImageTransformer
import cn.vectory.ocdroid.ui.util.MarkdownImageResolver

// ── Reasoning card + inline todo list ────────────────────────────────────
// ReasoningCard renders the assistant's chain-of-thought (collapsible, quiet
// styling). TodoListInline is the checkbox list shown inside expanded
// non-todowrite tool cards.

@Composable
internal fun ReasoningCard(
    text: String,
    title: String?,
    isStreaming: Boolean = false,
    expandedParts: Map<String, Boolean> = emptyMap(),
    onToggleExpand: (String, Boolean) -> Unit = { _, _ -> },
    expandedKey: String? = null,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val expanded = expandedKey?.let { expandedParts[it] } ?: false

    // §5.1 v3 reasoning card: fully transparent, no icon, no tinted body.
    // Reads as quiet auxiliary context — same visual weight as BasicTool.
    // Outer borderBase provides containment; no layer01 panel.
    val oc = MaterialTheme.opencode
    val isDark = LocalIsDarkTheme.current
    Surface(
        modifier = modifier.padding(vertical = 1.dp),
        shape = RoundedCornerShape(6.dp),
        color = oc.layer03,
        border = BorderStroke(1.dp, oc.borderBase)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .then(
                        // The card is always tappable to expand/collapse,
                        // including while streaming (so the user can watch the
                        // chain-of-thought stream in incrementally). The body
                        // render below is gated on `expanded` independently.
                        if (expandedKey != null)
                            Modifier.clickable { onToggleExpand(expandedKey, expanded) }
                        else Modifier
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    title ?: stringResource(R.string.chat_thinking),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isStreaming) {
                    // Thinking in progress: show an indeterminate loading ring
                    // after the title (mirrors the SubAgent card's running
                    // spinner style — 14.dp, strokeWidth 2.dp). Kept visible in
                    // both collapsed and expanded states so the "still thinking"
                    // affordance is always present while streaming (matches the
                    // shell tool card, which keeps its spinner while running).
                    Spacer(modifier = Modifier.width(6.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.weight(1f))
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                if (expandedKey != null) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Render the expanded reasoning body whenever expanded (including
            // while streaming). The paced-streaming text helper
            // (rememberPacedStreamingText) re-parses markdown on a throttled,
            // forward-only value, so an expanded streaming card updates
            // incrementally without per-token height oscillation. While
            // streaming the card shows the growing chain-of-thought; once
            // finished it shows the complete text.
            if (expanded && text.isNotBlank()) {
                // Pace the streaming text at the render layer (same anti-flicker
                // mechanism as TextPart): re-parse the markdown on a throttled,
                // forward-only value instead of per token, so an expanded
                // streaming reasoning card doesn't oscillate in height.
                val renderText = rememberPacedStreamingText(text, isStreaming)
                val normalizedText = remember(renderText) { MarkdownImageResolver.normalizeStandaloneImageBlocks(renderText) }
                val fontSizes = LocalMarkdownFontSizes.current
                // Reasoning text uses the smaller `reasoning` size (defaults to 12sp)
                // by overriding `body` when rendering, so it visually de-emphasizes
                // chain-of-thought vs the main assistant reply.
                val reasoningFontSizes = fontSizes.copy(body = fontSizes.reasoning)
                // §5.1 v2: transparent folding body — no tinted panel, just the
                // outer border provides containment. Matches BasicTool's flat style.
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Transparent
                ) {
                    SelectionContainer {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                            Markdown(
                                content = normalizedText,
                                typography = markdownTypography(reasoningFontSizes),
                                // §3.1 syntax highlighting in reasoning blocks too.
                                components = markdownComponents(
                                    codeBlock = { WrappedCodeBlock(it) },
                                    codeFence = { WrappedCodeBlock(it) }
                                ),
                                modifier = Modifier.padding(8.dp),
                                imageTransformer = DataUriImageTransformer
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleExpand(expandedKey!!, true) }
                        .padding(top = 2.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.ExpandLess,
                        contentDescription = "Collapse",
                        modifier = Modifier.size(14.dp),
                        tint = oc.faint
                    )
                }
            }
        }
    }
}

/**
 * Inline todo list, extracted from the old ToolCard expanded body so it matches
 * iOS TodoListInlineView. Used for `todowrite`, whose expanded card shows only the
 * todos (input/output hidden).
 */
@Composable
internal fun TodoListInline(
    todos: List<TodoItem>,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    Column(modifier = modifier) {
        todos.forEach { todo ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = todo.isCompleted,
                    onCheckedChange = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = todo.content,
                    style = MaterialTheme.typography.bodySmall.copy(
                        textDecoration = if (todo.isCompleted) TextDecoration.LineThrough else null
                    ),
                    color = if (todo.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (todo.priority != "medium") {
                    Text(text = todo.priority, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}
