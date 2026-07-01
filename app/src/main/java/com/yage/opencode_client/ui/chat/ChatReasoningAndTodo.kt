package com.yage.opencode_client.ui.chat

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
import com.yage.opencode_client.R
import com.yage.opencode_client.data.model.TodoItem
import com.yage.opencode_client.ui.theme.LocalIsDarkTheme
import com.yage.opencode_client.ui.theme.LocalMarkdownFontSizes
import com.yage.opencode_client.ui.theme.markdownTypography
import com.yage.opencode_client.ui.theme.opencode
import com.yage.opencode_client.ui.util.DataUriImageTransformer
import com.yage.opencode_client.ui.util.MarkdownImageResolver

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
                if (isStreaming && !expanded && text.isNotBlank()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
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
            if (expanded && text.isNotBlank()) {
                val normalizedText = remember(text) { MarkdownImageResolver.normalizeStandaloneImageBlocks(text) }
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
