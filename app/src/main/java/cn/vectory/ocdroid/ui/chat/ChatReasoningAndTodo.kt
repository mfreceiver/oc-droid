package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.animateContentSize
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.TodoItem
import cn.vectory.ocdroid.ui.theme.LocalIsDarkTheme
import cn.vectory.ocdroid.ui.theme.LocalMarkdownFontSizes
import cn.vectory.ocdroid.ui.theme.markdownTypography
import cn.vectory.ocdroid.ui.util.DataUriImageTransformer
import cn.vectory.ocdroid.ui.util.MarkdownImageResolver
import cn.vectory.ocdroid.ui.theme.AppMotion

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
    val isDark = LocalIsDarkTheme.current
    Surface(
        modifier = modifier.padding(vertical = 2.dp),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.then(if (isStreaming) Modifier else Modifier.animateContentSize(AppMotion.expandSizeSpec))) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
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
            // while streaming). While streaming the body shows the growing
            // chain-of-thought as PLAIN TEXT (the markdown renderer oscillates
            // pathologically on incomplete streaming prefixes — see the
            // §streaming-body comment below); once finished it renders the
            // complete text as formatted markdown.
            if (expanded && text.isNotBlank()) {
                // §streaming-body: while streaming, render the paced text as
                // PLAIN TEXT (the markdown renderer produces pathologically
                // unstable height when re-parsing growing incomplete streaming
                // text → flicker; confirmed via diagnostics: 67 height-shrinks/
                // turn, ~5000px↔168px oscillation, all eliminated by plain text).
                // Full markdown renders once, stably, on completion.
                val renderText = rememberPacedStreamingText(text, isStreaming)
                val fontSizes = LocalMarkdownFontSizes.current
                val scrollState = rememberScrollState()
                // §auto-follow (per review, mirrors ChatMessageList's pattern):
                // (a) use instant scrollTo during streaming — animateScrollTo is
                //     cancelled every ~100ms paced advance and never completes;
                // (b) pause auto-follow when the user scrolls up to read earlier
                //     reasoning, so the next paced advance doesn't yank them back.
                var userScrolledAway by remember { mutableStateOf(false) }
                LaunchedEffect(scrollState) {
                    snapshotFlow { scrollState.isScrollInProgress to scrollState.value }
                        .collect { (inProgress, value) ->
                            if (inProgress && value < scrollState.maxValue - 24) userScrolledAway = true
                        }
                }
                LaunchedEffect(isStreaming) { if (!isStreaming) userScrolledAway = false }
                LaunchedEffect(renderText, isStreaming) {
                    if (isStreaming && !userScrolledAway) scrollState.scrollTo(scrollState.maxValue)
                }
                // §5.1 v2: transparent folding body — no tinted panel, just the
                // outer border provides containment. Matches BasicTool's flat style.
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    shape = RectangleShape,
                    color = Color.Transparent
                ) {
                    // Cap the body height + scroll so long streaming reasoning
                    // never overflows the screen (the "内容外溢" symptom) WHILE
                    // streaming. On completion both the cap and the internal
                    // scroll are dropped so a long finished chain-of-thought
                    // shows at its full natural height per the user's decision
                    // (it rides the parent chat LazyColumn's scroll instead).
                    SelectionContainer {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                            // §scroll-safety: verticalScroll may only be applied
                            // together with a finite heightIn cap. While streaming
                            // we cap at 280.dp so long chain-of-thought scrolls
                            // internally without overflowing the screen. Once
                            // finished we DROP both the cap and the scroll — the
                            // body renders at its full natural height and rides
                            // the parent chat LazyColumn's scroll. Applying
                            // verticalScroll without the cap (the previous
                            // `heightIn(max = Dp.Unspecified)` branch) yields an
                            // unbounded scrollable that only happens to not crash
                            // because the LazyColumn item viewport bounds it; any
                            // reuse outside a bounded parent would trigger
                            // "Vertically scrollable component was measured with
                            // an infinity maximum height constraints". The
                            // auto-scroll effect below is streaming-gated, and
                            // the remaining scrollState observer (which only
                            // records user-scroll-away intent) is harmless once
                            // the scroll modifier is removed, so dropping the
                            // modifier when finished is safe.
                            Box(
                                modifier = Modifier
                                    .then(
                                        if (isStreaming) Modifier
                                            .heightIn(max = 280.dp)
                                            .verticalScroll(scrollState)
                                        else Modifier
                                    )
                                    .padding(8.dp)
                            ) {
                                if (isStreaming) {
                                    // Plain text at the reasoning font size so the
                                    // completion snap to markdown is size-neutral.
                                    Text(
                                        text = renderText,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = fontSizes.reasoning.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    val normalizedText = remember(renderText) { MarkdownImageResolver.normalizeStandaloneImageBlocks(renderText) }
                                    Markdown(
                                        content = normalizedText,
                                        typography = markdownTypography(fontSizes.copy(body = fontSizes.reasoning)),
                                        // §3.1 syntax highlighting in reasoning blocks too.
                                        components = markdownComponents(
                                            codeBlock = { WrappedCodeBlock(it) },
                                            codeFence = { WrappedCodeBlock(it) },
                                            table = { WrappedTable(it) }
                                        ),
                                        imageTransformer = DataUriImageTransformer
                                    )
                                }
                            }
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
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
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
