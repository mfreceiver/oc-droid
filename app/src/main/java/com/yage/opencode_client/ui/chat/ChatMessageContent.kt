package com.yage.opencode_client.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.yage.opencode_client.R
import com.yage.opencode_client.data.model.Message
import com.yage.opencode_client.data.model.Part
import com.yage.opencode_client.data.model.TodoItem
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.theme.LocalMarkdownFontSizes
import com.yage.opencode_client.ui.theme.markdownTypography
import com.yage.opencode_client.ui.theme.markdownTypographyCompact
import com.yage.opencode_client.ui.theme.opencode
import com.yage.opencode_client.ui.util.DataUriImageTransformer
import com.yage.opencode_client.ui.util.HttpImageHolder
import com.yage.opencode_client.ui.util.MarkdownImageResolver
import kotlinx.coroutines.flow.collect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ChatMessageList(
    messages: List<Message>,
    partsByMessage: Map<String, List<Part>>,
    streamingPartTexts: Map<String, String>,
    streamingReasoningPart: Part?,
    isLoading: Boolean,
    hasMoreMessages: Boolean,
    repository: OpenCodeRepository,
    workspaceDirectory: String?,
    onLoadMore: () -> Unit,
    onFileClick: (String) -> Unit,
    onOpenSubAgent: (String) -> Unit,
    expandedParts: Map<String, Boolean>,
    onToggleExpand: (String, Boolean) -> Unit
) {
    val listState = rememberLazyListState()
    var shouldAutoScroll by remember { mutableStateOf(true) }
    val contentVersion = remember(messages, partsByMessage, streamingPartTexts, streamingReasoningPart, isLoading) {
        messages.size +
            partsByMessage.values.sumOf { it.size } +
            streamingPartTexts.hashCode() +
            (if (streamingReasoningPart != null) 1 else 0) +
            (if (isLoading) 1 else 0)
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 24
        }.collect { atBottom ->
            shouldAutoScroll = atBottom
        }
    }

    LaunchedEffect(contentVersion) {
        if (shouldAutoScroll && (messages.isNotEmpty() || streamingReasoningPart != null)) {
            listState.animateScrollToItem(0)
        }
    }

    // History paging is manual (not scroll-triggered): on session open the
    // latest 5 messages load; a "load more" button at the top (oldest end)
    // fetches 5 older messages per click. This avoids the auto-loadMore loop
    // that occurred when a scroll/nearTop trigger fired on the short initial
    // page, and matches the product decision that most users rarely need deep
    // history. Messages are not persisted — re-fetched fresh on each open.

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        reverseLayout = true,
        // §4.1 v2 spacing: 16dp between turns (each item is one turn = one
        // MessageRow). Combined with MessageRow's own vertical=4dp padding,
        // adjacent turns sit ~24dp apart visually (4 + 16 + 4). Within a turn,
        // per-part cards carry their own 2-4dp vertical padding.
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
    ) {
        if (streamingReasoningPart != null) {
            val streamingKey = streamingReasoningPart.id
            val streamingText = streamingPartTexts[streamingKey] ?: ""
            item(key = "streaming-reasoning") {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    ReasoningCard(
                        text = streamingText,
                        title = streamingReasoningPart.toolReason,
                        isStreaming = true,
                        modifier = Modifier.widthIn(max = MAX_CARD_WIDTH)
                    )
                }
            }
        }
        items(messages.reversed(), key = { it.id }) { message ->
            MessageRow(
                message = message,
                parts = partsByMessage[message.id].orEmpty(),
                streamingPartTexts = streamingPartTexts,
                repository = repository,
                workspaceDirectory = workspaceDirectory,
                onFileClick = onFileClick,
                onOpenSubAgent = onOpenSubAgent,
                expandedParts = expandedParts,
                onToggleExpand = onToggleExpand
            )
        }
        if (messages.isNotEmpty() && hasMoreMessages) {
            item(key = "load-more") {
                // Manual history paging: click to fetch 5 older messages.
                // Spinner while a fetch is in flight; otherwise a tappable label.
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Text(
                            text = "加载更多历史",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable(onClick = onLoadMore)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
        if (!isLoading && messages.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No messages yet. Send a message to start.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

/**
 * Classification of a single tool/patch part for ordered rendering within a
 * contiguous tool run. Built in a pure-data phase (no @Composable calls) so a
 * local helper fun can buffer context tools; emitted afterwards in @Composable
 * context. See [MessageRow].
 */
private sealed class ToolRenderItem {
    data class ContextGroup(val parts: List<Part>) : ToolRenderItem()
    data class SubAgent(val part: Part) : ToolRenderItem()
    data class WritePatch(val part: Part) : ToolRenderItem()
    data class Basic(val part: Part) : ToolRenderItem()
}

@Composable
private fun MessageRow(
    message: Message,
    parts: List<Part>,
    streamingPartTexts: Map<String, String>,
    repository: OpenCodeRepository,
    workspaceDirectory: String?,
    onFileClick: (String) -> Unit,
    onOpenSubAgent: (String) -> Unit,
    expandedParts: Map<String, Boolean>,
    onToggleExpand: (String, Boolean) -> Unit
) {
    val isUser = message.isUser

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // No "OpenCode" speaker title — the user's blue left bar vs the
        // assistant's container-less reply already make it clear who's speaking,
        // so an extra blue label is redundant.

        var i = 0
        while (i < parts.size) {
            val part = parts[i]
            val streamingText = streamingPartTexts[part.id]
            val isToolLike = part.isTool || (part.isPatch && part.filePathsForNavigationFiltered.isNotEmpty())
            if (isToolLike) {
                // Buffer a contiguous run of tool/patch parts, then classify and
                // render each according to the opencode-web paradigm:
                //  - todowrite → hidden (todos live in the toolbar panel)
                //  - task → SubAgentCard (the only bordered card)
                //  - context tools (read/glob/grep/list) → ContextToolGroup
                //  - write file ops → PatchCard / MultiFilePatchAccordion
                //  - everything else → BasicTool (borderless single line)
                val run = mutableListOf<Part>()
                var j = i
                while (j < parts.size) {
                    val p = parts[j]
                    if (p.isTool || (p.isPatch && p.filePathsForNavigationFiltered.isNotEmpty())) {
                        run.add(p)
                        j++
                    } else break
                }

                // Phase 1: classify the run into ordered render items, buffering
                // consecutive context tools (read/glob/grep/list) into a single
                // ContextGroup. Pure data ops only — no @Composable calls — so a
                // local helper fun is legal (a @Composable local fun is not).
                val items = mutableListOf<ToolRenderItem>()
                var ctxBuffer = mutableListOf<Part>()
                fun closeContext() {
                    if (ctxBuffer.isNotEmpty()) {
                        items.add(ToolRenderItem.ContextGroup(ctxBuffer.toList()))
                        ctxBuffer = mutableListOf()
                    }
                }
                for (p in run) {
                    when {
                        // Rule 1: todowrite → hidden (todos live in the toolbar panel)
                        ToolCardClassifier.isTodoWriteTool(p) -> { /* no-op */ }
                        // Rule 2: task (sub-agent) → SubAgentCard
                        p.isSubAgentTask -> { closeContext(); items.add(ToolRenderItem.SubAgent(p)) }
                        // Rule 3: context tools → buffer for grouping
                        ToolCardClassifier.isContextTool(p) -> ctxBuffer.add(p)
                        // Rule 6: write file ops → PatchCard / MultiFilePatchAccordion
                        ToolCardClassifier.isWriteFileOperation(p) -> { closeContext(); items.add(ToolRenderItem.WritePatch(p)) }
                        // Rules 4,5,7: bash/webfetch/websearch/other → BasicTool
                        else -> { closeContext(); items.add(ToolRenderItem.Basic(p)) }
                    }
                }
                closeContext()

                // Phase 2: emit each item in @Composable context.
                items.forEach { item ->
                    when (item) {
                        is ToolRenderItem.ContextGroup -> ContextToolGroup(
                            parts = item.parts,
                            expandedParts = expandedParts,
                            onToggleExpand = onToggleExpand,
                            messageId = message.id,
                            modifier = Modifier.widthIn(max = MAX_CARD_WIDTH)
                        )
                        is ToolRenderItem.SubAgent -> SubAgentCard(
                            part = item.part,
                            onOpenSubAgent = onOpenSubAgent,
                            modifier = Modifier.widthIn(max = MAX_CARD_WIDTH)
                        )
                        is ToolRenderItem.WritePatch -> {
                            val writeFiles = item.part.files ?: emptyList()
                            if (writeFiles.size > 1) {
                                MultiFilePatchAccordion(
                                    parts = listOf(item.part),
                                    onFileClick = onFileClick,
                                    modifier = Modifier.widthIn(max = MAX_CARD_WIDTH)
                                )
                            } else {
                                PatchCard(
                                    part = item.part,
                                    onFileClick = onFileClick,
                                    expandedParts = expandedParts,
                                    onToggleExpand = onToggleExpand,
                                    expandedKey = "${message.id}|${item.part.id}",
                                    modifier = Modifier.widthIn(max = MAX_CARD_WIDTH)
                                )
                            }
                        }
                        is ToolRenderItem.Basic -> BasicTool(
                            part = item.part,
                            onFileClick = onFileClick,
                            expandedParts = expandedParts,
                            onToggleExpand = onToggleExpand,
                            expandedKey = "${message.id}|${item.part.id}",
                            modifier = Modifier.widthIn(max = MAX_CARD_WIDTH)
                        )
                    }
                }

                i = j
            } else {
                PartView(
                    part = part,
                    isUser = isUser,
                    streamingTextOverride = streamingText,
                    repository = repository,
                    workspaceDirectory = workspaceDirectory,
                    onFileClick = onFileClick,
                    onOpenSubAgent = onOpenSubAgent,
                    messageId = message.id,
                    expandedParts = expandedParts,
                    onToggleExpand = onToggleExpand,
                    modifier = Modifier.fillMaxWidth()
                )
                i += 1
            }
        }
        // Footer caption — replaces the previous provider/model label.
        //  - Assistant: completion time (hh:mm), falling back to created time.
        //  - User: "<modelId> <created hh:mm>" so the user can see which agent
        //    model handled the turn and when the prompt was sent.
        val timeInfo = message.time
        val footerText = if (isUser) {
            val modelId = message.resolvedModel?.modelId
            val sendTime = timeInfo?.created?.let(::formatHm)
            when {
                modelId != null && sendTime != null -> "$modelId $sendTime"
                modelId != null -> modelId
                sendTime != null -> sendTime
                else -> null
            }
        } else {
            (timeInfo?.completed ?: timeInfo?.created)?.let(::formatHm)
        }
        if (footerText != null) {
            // §4.4 v2 footer: labelSmall + faint color + top=4dp padding. The
            // Column's horizontalAlignment (End for user, Start for assistant)
            // already aligns the footer to the speaker's side.
            Text(
                text = footerText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.opencode.faint,
                modifier = Modifier.padding(end = 4.dp, top = 4.dp)
            )
        }
    }
}

@Composable
private fun PartView(
    part: Part,
    isUser: Boolean,
    streamingTextOverride: String?,
    repository: OpenCodeRepository,
    workspaceDirectory: String?,
    onFileClick: (String) -> Unit,
    onOpenSubAgent: (String) -> Unit,
    messageId: String,
    expandedParts: Map<String, Boolean>,
    onToggleExpand: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val expandKey = "${messageId}|${part.id}"
    when {
        part.isText -> TextPart(
            text = streamingTextOverride ?: part.text ?: "",
            isUser = isUser,
            modifier = modifier,
            repository = repository,
            workspaceDirectory = workspaceDirectory
        )
        part.isReasoning -> ReasoningCard(
            text = streamingTextOverride ?: part.text ?: "",
            title = part.toolReason,
            isStreaming = false,
            expandedParts = expandedParts,
            onToggleExpand = onToggleExpand,
            expandedKey = expandKey,
            modifier = modifier.widthIn(max = MAX_CARD_WIDTH)
        )
        part.isImageAttachment -> ImageFilePart(part, modifier)
        part.isFile -> FileAttachmentPart(part, modifier)
        part.isSubAgentTask -> SubAgentCard(part, onOpenSubAgent, Modifier.widthIn(max = MAX_CARD_WIDTH))
        part.isTool -> ToolCard(
            part = part,
            onFileClick = onFileClick,
            expandedParts = expandedParts,
            onToggleExpand = onToggleExpand,
            expandedKey = expandKey,
            modifier = Modifier.widthIn(max = MAX_CARD_WIDTH)
        )
        // Dead code removed (评审 Stage C #1): the previous
        // `part.isPatch && part.filePathsForNavigationFiltered.isNotEmpty()`
        // branch was unreachable here because [MessageRow]'s `isToolLike`
        // check above already routes every such part into the buffered tool
        // run (which renders PatchCard / MultiFilePatchAccordion from
        // [writeParts]). Keeping this branch would risk silently sending a
        // future reclassification straight into the single-file PatchCard and
        // dropping the MultiFilePatchAccordion path for multi-file patches.
    }
}

@Composable
private fun ImageFilePart(part: Part, modifier: Modifier = Modifier.fillMaxWidth()) {
    // §16.3 (评审 Stage C #6): reuse [DataUriImageTransformer]'s LruCache so
    // the same data-URI is decoded at most once across the chat surface and
    // inline markdown. The transformer already handles memory caching and
    // Compose-side `remember` keyed by URL — keeping a separate decoder here
    // would silently bypass the cache and re-decode on every recomposition.
    val imageData = part.url?.let { DataUriImageTransformer.transform(it) }
    if (imageData == null) {
        FileAttachmentPart(part, modifier)
        return
    }
    Column(modifier = modifier.padding(vertical = 4.dp)) {
        Image(
            painter = imageData.painter,
            contentDescription = part.filename ?: "Attached image",
            modifier = Modifier
                .fillMaxWidth()
                // §5.6 v2: markdown image radius = 4dp.
                .clip(RoundedCornerShape(4.dp)),
            contentScale = ContentScale.FillWidth
        )
        part.filename?.let { filename ->
            Text(
                text = filename,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun FileAttachmentPart(part: Part, modifier: Modifier = Modifier.fillMaxWidth()) {
    // §5.6 v2: layer01 chip at 6dp radius with accentText doc icon.
    val oc = MaterialTheme.opencode
    Surface(
        color = oc.layer01,
        shape = RoundedCornerShape(6.dp),
        modifier = modifier.padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = oc.accentText,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = part.filename ?: part.mime ?: "Attached file",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Formats an epoch-millis timestamp as `HH:mm` (24h, locale-neutral numerics).
 * Used for the per-message footer caption that replaced the model label.
 */
private fun formatHm(epochMs: Long): String = try {
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
} catch (_: Exception) {
    ""
}

/**
 * Compact file card for file-operation tools (patch / edit / write / read). Quiet
 * Tech styling: neutral surface body, single blue accent on the doc icon, monospace
 * basename, chevron to navigate. When the part is a `read` of a *directory* (server
 * reports `<type>directory</type>`), the card switches to a folder icon and tapping
 * opens a bottom sheet listing the already-returned `<entries>` — no API call.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileCard(
    part: Part,
    onFileClick: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    // Prefer an explicit path; fall back to the first navigable path so the card
    // always has a label even when metadata.path is absent (matches iOS priority).
    val displayPath = part.metadata?.path?.takeIf { it.isNotEmpty() }
        ?: part.state?.pathFromInput?.takeIf { it.isNotEmpty() }
        ?: part.filePathsForNavigation.firstOrNull()
    val basename = displayPath?.takeIf { it.isNotEmpty() }
        ?.substringAfterLast("/")?.takeIf { it.isNotEmpty() }
        ?: "file"

    val isDirectoryRead = ToolCardClassifier.isDirectoryRead(part)
    val isReadOnlyFileTool = part.tool?.lowercase()?.let { tool ->
        ToolCardClassifier.readToolPrefixes.any { tool.startsWith(it) }
    } == true
    var showFolderSheet by remember { mutableStateOf(false) }

    // testTag encodes the read/write nature of the card so the semantics tree can
    // distinguish them. A directory listing is always a read. For files, the tool
    // prefix decides: readToolPrefixes -> read, otherwise write/edit/patch -> write.
    // Layers 2-4 (component / integration-UI / LLM-driven) all rely on this; the icon
    // color alone is invisible to the semantics tree.
    val tag = when {
        isDirectoryRead -> "toolcard.folder.$basename"
        isReadOnlyFileTool -> "toolcard.read.$basename"
        else -> "toolcard.write.$basename"
    }
    val iconDescription = when {
        isDirectoryRead -> "Read directory $basename"
        isReadOnlyFileTool -> "Read file $basename"
        else -> "Write file $basename"
    }

    // §5.3 v2 file card: layer01 surface at 6dp with borderBase, accentText icon.
    val oc = MaterialTheme.opencode
    Surface(
        color = oc.layer01,
        border = BorderStroke(1.dp, oc.borderBase),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier
            .padding(vertical = 4.dp)
            .testTag(tag)
            .clickable {
                if (isDirectoryRead) {
                    showFolderSheet = true
                } else {
                    val paths = part.filePathsForNavigation
                    val target = paths.firstOrNull() ?: displayPath
                    if (target != null) onFileClick(target)
                }
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isDirectoryRead) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = iconDescription,
                modifier = Modifier.size(16.dp),
                tint = oc.accentText
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = basename,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }

    if (showFolderSheet) {
        val entries = remember(part.id, part.toolOutput) {
            ToolCardClassifier.parseDirectoryEntries(part.toolOutput)
        }
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showFolderSheet = false },
            sheetState = sheetState,
            modifier = Modifier.testTag("toolcard.folder.sheet.$basename")
        ) {
            FolderContents(folderName = basename, entries = entries)
        }
    }
}

/** Sheet body listing a read directory's contents. Subdirectories sort above files. */
@Composable
private fun FolderContents(
    folderName: String,
    entries: List<ToolCardClassifier.DirectoryEntry>
) {
    val sorted = remember(entries) {
        entries.sortedWith(
            compareByDescending<ToolCardClassifier.DirectoryEntry> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = folderName,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        if (sorted.isEmpty()) {
            Text(
                text = "This directory has no entries.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        } else {
            sorted.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .testTag("toolcard.folder.entry.${entry.name}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(modifier = Modifier.size(8.dp))
    }
}

/**
 * Merged "N tool calls" row for non-file tools. Collapsed by default; expanding
 * reveals each tool's full body (reused ToolCard content). Mirrors iOS's
 * DisclosureGroup-based toolCallsRow.
 */
@Composable
private fun ToolCallsRow(
    parts: List<Part>,
    onFileClick: (String) -> Unit,
    onOpenSubAgent: (String) -> Unit,
    messageId: String,
    expandedParts: Map<String, Boolean>,
    onToggleExpand: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val expandedKey = "${messageId}|${parts.first().id}"
    val expanded = expandedParts[expandedKey] ?: false
    // §5.7 v2 tool-calls row: transparent surface + borderBase + 6dp radius
    // (was surfaceVariant solid + 12dp). Keeps the merged "N tool calls" row
    // visually aligned with the other v2 cards.
    val oc = MaterialTheme.opencode
    Surface(
        modifier = modifier.padding(vertical = 4.dp).testTag("toolcard.toolcalls"),
        shape = RoundedCornerShape(6.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        border = BorderStroke(1.dp, oc.borderBase)
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${parts.size} tool calls",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(onClick = { onToggleExpand(expandedKey, expanded) }) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (expanded) {
                Spacer(modifier = Modifier.size(8.dp))
                parts.forEach { part ->
                    if (part.isSubAgentTask) {
                        SubAgentCard(part, onOpenSubAgent, Modifier.fillMaxWidth())
                    } else {
                        ToolCard(
                            part = part,
                            onFileClick = onFileClick,
                            expandedParts = expandedParts,
                            onToggleExpand = onToggleExpand,
                            expandedKey = "${messageId}|${part.id}",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TextPart(
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
                            codeBlock = highlightedCodeBlock,
                            codeFence = highlightedCodeFence
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
private fun ResolvedMarkdownText(
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
                    codeBlock = highlightedCodeBlock,
                    codeFence = highlightedCodeFence
                ),
                modifier = modifier,
                imageTransformer = DataUriImageTransformer
            )
        }
    }
}

@Composable
private fun ReasoningCard(
    text: String,
    title: String?,
    isStreaming: Boolean = false,
    expandedParts: Map<String, Boolean> = emptyMap(),
    onToggleExpand: (String, Boolean) -> Unit = { _, _ -> },
    expandedKey: String? = null,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val expanded = expandedKey?.let { expandedParts[it] } ?: isStreaming

    // §5.1 v3 reasoning card: fully transparent, no icon, no tinted body.
    // Reads as quiet auxiliary context — same visual weight as BasicTool.
    // Outer borderBase provides containment; no layer01 panel.
    val oc = MaterialTheme.opencode
    Surface(
        modifier = modifier.padding(vertical = 1.dp),
        shape = RoundedCornerShape(6.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        border = BorderStroke(1.dp, oc.borderBase)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title ?: stringResource(R.string.chat_thinking),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                if (!isStreaming && expandedKey != null) {
                    IconButton(onClick = { onToggleExpand(expandedKey, expanded) }) {
                        Icon(
                            if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                            contentDescription = if (expanded) "Collapse" else "Expand",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if ((expanded || isStreaming) && text.isNotBlank()) {
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
                    color = androidx.compose.ui.graphics.Color.Transparent
                ) {
                    SelectionContainer {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                            Markdown(
                                content = normalizedText,
                                typography = markdownTypography(reasoningFontSizes),
                                // §3.1 syntax highlighting in reasoning blocks too.
                                components = markdownComponents(
                                    codeBlock = highlightedCodeBlock,
                                    codeFence = highlightedCodeFence
                                ),
                                modifier = Modifier.padding(8.dp),
                                imageTransformer = DataUriImageTransformer
                            )
                        }
                    }
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
private fun TodoListInline(
    todos: List<TodoItem>,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    Column(modifier = modifier) {
        todos.forEach { todo ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (todo.isCompleted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (todo.isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
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

/**
 * Card for `task` tool parts — sub-agent conversations spawned by the main
 * session. This is the **only** tool card that carries a border and background
 * in the opencode-web paradigm (all other tools render as borderless single
 * lines via [BasicTool] or [ContextToolGroup]).
 *
 * Single-line layout:
 *   [spinner/Warning/—]  @agentName · description  [>]
 *
 * - running → spinner + agent name + description subtitle
 * - done    → agent name + description (no status icon, no CheckCircle)
 * - error   → Warning icon + agent name + description
 *
 * Tapping opens the child session in-place via [onOpenSubAgent]. When the
 * child session ID hasn't been assigned yet (task still running with no
 * metadata.sessionID), the card renders but is not clickable.
 */
@Composable
private fun SubAgentCard(
    part: Part,
    onOpenSubAgent: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val sessionId = part.taskSubSessionId
    val rawTitle = part.state?.title
        ?: part.state?.metadataString("description")
        ?: ""
    val description = part.state?.metadataString("description")?.takeIf { it.isNotEmpty() }

    val subAgentName = remember(rawTitle, description) {
        parseSubAgentName(rawTitle) ?: parseSubAgentName(description)
    }
    val cleanTitle = remember(rawTitle, subAgentName) {
        stripSubAgentSuffix(rawTitle).ifBlank { description ?: rawTitle }
    }

    val taskXml = remember(part.toolOutput) { parseTaskXml(part.toolOutput) }

    val status = part.stateDisplay
    val isRunning = status == "running" && taskXml?.state?.lowercase() != "completed"
    val isError = status == "error" || taskXml?.state?.lowercase() == "error"

    val canOpen = sessionId != null
    val tagSuffix = sessionId?.let { ".$it" } ?: ""

    val oc = MaterialTheme.opencode
    val statusErrorColor = oc.stateDangerFg

    Surface(
        modifier = modifier
            .padding(vertical = 2.dp)
            .testTag("toolcard.subagent$tagSuffix")
            .then(if (canOpen) Modifier.clickable { onOpenSubAgent(sessionId!!) } else Modifier),
        shape = RoundedCornerShape(6.dp),
        color = oc.layer02,
        border = BorderStroke(1.dp, oc.borderBase)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Status icon: spinner while running, warning on error, nothing on done
                when {
                    isRunning -> CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    isError -> Icon(
                        Icons.Default.Warning,
                        contentDescription = "Sub-agent error",
                        modifier = Modifier.size(14.dp),
                        tint = statusErrorColor
                    )
                }
                if (isRunning || isError) Spacer(modifier = Modifier.width(6.dp))

                // Agent name in accent color
                if (subAgentName != null) {
                    Text(
                        text = "@$subAgentName",
                        style = MaterialTheme.typography.labelMedium,
                        color = oc.accentText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Description as subtitle
                val bodyTitle = cleanTitle.ifBlank { taskXml?.taskResult?.takeIf { it.isNotBlank() } }.orEmpty()
                if (bodyTitle.isNotBlank()) {
                    if (subAgentName != null) {
                        Text(
                            text = " · ",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = bodyTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                // Chevron to open sub-agent conversation
                if (canOpen) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "Open sub-agent conversation",
                        modifier = Modifier.size(14.dp),
                        tint = oc.accentText
                    )
                }
            }

            // Waiting indicator when no session ID yet
            if (!isRunning && !isError && sessionId == null) {
                Text(
                    text = "waiting for sub-agent…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/**
 * Extracts the `xxx` from a title shaped like "(@xxx subagent)" or
 * "Description (@xxx subagent)". Returns null when no such marker is present.
 */
private fun parseSubAgentName(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val match = Regex("\\(@([A-Za-z0-9_-]+)\\s*subagent\\)", RegexOption.IGNORE_CASE).find(text)
    return match?.groupValues?.getOrNull(1)
}

/**
 * Strips the trailing "(@xxx subagent)" marker from a sub-agent title so the
 * body line doesn't redundantly echo what the header's @agentName badge already
 * shows. Returns the cleaned title (may be blank if the marker was the only
 * content); the caller decides whether to fall back to the description.
 */
private fun stripSubAgentSuffix(text: String?): String {
    if (text.isNullOrBlank()) return ""
    return Regex("\\s*\\(@[A-Za-z0-9_-]+\\s*subagent\\)", RegexOption.IGNORE_CASE)
        .replace(text, "")
        .trim()
}

/**
 * Width cap applied to every unified collapsible card (reasoning / tool / file
 * edit / sub-agent / merged tool-calls row). Keeps the chat readable on wide
 * screens by left-aligning the agent's "side-channels" instead of stretching
 * them wall-to-wall. Matches iOS's compact card width.
 */
private val MAX_CARD_WIDTH = 280.dp

/**
 * Picks the Material icon for a tool based on its name (lowercased, prefix-matched).
 *  - read/grep/glob/list → FileOpen (file inspection)
 *  - edit/write/patch/apply_patch → Edit (file mutation)
 *  - bash/terminal/cmd → Terminal (shell)
 *  - anything else → Build (generic tool)
 */
private fun toolIcon(toolName: String?): androidx.compose.ui.graphics.vector.ImageVector {
    val lower = toolName?.lowercase() ?: return Icons.Default.Build
    return when {
        lower.startsWith("read") ||
            lower.startsWith("grep") ||
            lower.startsWith("glob") ||
            lower.startsWith("list") ||
            lower.startsWith("todoread") ||
            lower.startsWith("webfetch") -> Icons.Default.FileOpen
        lower.startsWith("edit") ||
            lower.startsWith("write") ||
            lower.startsWith("apply_patch") ||
            lower.startsWith("patch") -> Icons.Default.Edit
        lower.startsWith("bash") ||
            lower.startsWith("terminal") ||
            lower.startsWith("cmd") ||
            lower.startsWith("shell") -> Icons.Default.Terminal
        else -> Icons.Default.Build
    }
}

/**
 * Counts added/removed lines in a diff/patch text. Skips diff headers
 * (`+++`/`---`/`@@`/`*** Add File:` / `*** Update File:` lines) so only true
 * content lines are counted. Returns (additions, deletions).
 */
private fun countDiffLines(text: String): Pair<Int, Int> {
    if (text.isEmpty()) return 0 to 0
    var add = 0
    var del = 0
    for (rawLine in text.split("\n")) {
        if (rawLine.startsWith("+++") || rawLine.startsWith("---")) continue
        if (rawLine.startsWith("@@")) continue
        if (rawLine.startsWith("***")) continue
        when {
            rawLine.startsWith("+") -> add++
            rawLine.startsWith("-") -> del++
        }
    }
    return add to del
}

/**
 * Parsed view of a sub-agent `<task …>` XML block that the server sometimes
 * embeds in a `task` tool's state.output once the child session finishes.
 *  - [id]       → the child task id attribute (informational)
 *  - [state]    → "completed" / "error" / etc.
 *  - [taskResult] → inner `<task_result>…</task_result>` body, trimmed
 *
 * Returns null when the output doesn't contain a `<task` tag (the common case —
 * most sub-agent outputs are plain text or empty).
 */
private data class TaskXmlResult(
    val id: String?,
    val state: String?,
    val taskResult: String?
)

private fun parseTaskXml(output: String?): TaskXmlResult? {
    if (output.isNullOrBlank()) return null
    val taskIdx = output.indexOf("<task")
    if (taskIdx < 0) return null
    val idMatch = Regex("""<task[^>]*\sid="([^"]+)"""").find(output, taskIdx)
    val stateMatch = Regex("""<task[^>]*\sstate="([^"]+)"""").find(output, taskIdx)
    val resultMatch = Regex("""<task_result>([\s\S]*?)</task_result>""").find(output, taskIdx)
    return TaskXmlResult(
        id = idMatch?.groupValues?.getOrNull(1),
        state = stateMatch?.groupValues?.getOrNull(1),
        taskResult = resultMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    )
}

@Composable
private fun ToolCard(
    part: Part,
    onFileClick: (String) -> Unit,
    expandedParts: Map<String, Boolean>,
    onToggleExpand: (String, Boolean) -> Unit,
    expandedKey: String,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val toolName = part.tool ?: ""
    val status = part.stateDisplay
    val reason = part.toolReason
    val filePaths = part.filePathsForNavigationFiltered
    val todos = part.toolTodos
    val isTodoWrite = part.tool == "todowrite"
    val input = part.toolInputSummary
    val output = part.toolOutput

    val isRunning = status == "running"
    val isError = status == "error"
    val expanded = expandedParts[expandedKey] ?: isRunning
    val firstFile = filePaths.firstOrNull()
    val displayName = if (toolName == "apply_patch") "patch" else toolName

    val icon = remember(toolName) { toolIcon(toolName) }

    // §5.2 v2 tool card: transparent surface + borderBase + 6dp radius (was
    // surfaceVariant solid + 12dp). The icon classification (read/edit/bash/
    // generic) is preserved, but the v2 neutral look means titles and icons
    // read as muted; only the status indicator uses stateSuccessFg /
    // stateDangerFg to encode tool outcome.
    val oc = MaterialTheme.opencode

    Surface(
        modifier = modifier.padding(vertical = 2.dp),
        shape = RoundedCornerShape(6.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        border = BorderStroke(1.dp, oc.borderBase)
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = displayName.ifEmpty { reason ?: "tool" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // §5.2 status indicator — spinner while running, stateSuccessFg
                    // check on completion, stateDangerFg warning on error.
                    when {
                        isRunning -> CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        isError -> Icon(
                            Icons.Default.Warning,
                            contentDescription = "Tool error",
                            modifier = Modifier.size(14.dp),
                            tint = oc.stateDangerFg
                        )
                    }
                    if (firstFile != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(onClick = { onFileClick(firstFile) }, modifier = Modifier.size(22.dp)) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = stringResource(R.string.files_show_in_files),
                                modifier = Modifier.size(14.dp),
                                tint = oc.accentText
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                    IconButton(onClick = { onToggleExpand(expandedKey, expanded) }) {
                        Icon(
                            if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                            contentDescription = if (expanded) "Collapse" else "Expand",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (expanded) {
                    if (!reason.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // todowrite shows a compact badge; full list is in the toolbar panel (matches iOS).
                    if (isTodoWrite) {
                        if (todos.isNotEmpty()) {
                            Spacer(modifier = Modifier.size(6.dp))
                            val completed = todos.count { it.isCompleted }
                            Text(
                                "Todo updated · $completed/${todos.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = oc.accentText
                            )
                        }
                    } else {
                        if (todos.isNotEmpty()) {
                            Spacer(modifier = Modifier.size(6.dp))
                            TodoListInline(todos)
                        }
                        if (!input.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(
                                text = input,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!output.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(
                                text = output,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (filePaths.isNotEmpty()) {
                        Spacer(modifier = Modifier.size(6.dp))
                        filePaths.forEach { path ->
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                Text(
                                    text = path,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = oc.accentText,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { onFileClick(path) }, modifier = Modifier.size(22.dp)) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = stringResource(R.string.files_show_in_files),
                                        modifier = Modifier.size(14.dp),
                                        tint = oc.accentText
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Collapsible card for a single file-edit operation (write/edit/patch/apply_patch).
 * Unified skeleton:
 *
 *   [Edit 14dp] basename  +N -M   [▶]
 *   ---------------------------------
 *   full/path/to/file.ts (+ link to open in Files)
 *
 * Diff stats come from the first [Part.FileChange] in `part.files` when the
 * server provided them; otherwise they're counted from the patch text in
 * `state.inputSummary` / `state.output` by scanning for `+` / `-` line prefixes
 * (skipping `+++`/`---`/`@@`/`***` diff headers). When the part spans multiple
 * files, only the primary one is summarized in the header — the rest are still
 * reachable via the OpenInNew buttons in the expanded body.
 */
@Composable
private fun PatchCard(
    part: Part,
    onFileClick: (String) -> Unit,
    expandedParts: Map<String, Boolean>,
    onToggleExpand: (String, Boolean) -> Unit,
    expandedKey: String,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val primaryFile = part.files?.firstOrNull()
    val allPaths = part.filePathsForNavigationFiltered
    val primaryPath = remember(part) {
        primaryFile?.path?.replace("\\", "/")?.trim()
            ?: part.metadata?.path?.takeIf { it.isNotEmpty() }
            ?: part.state?.pathFromInput?.takeIf { it.isNotEmpty() }
            ?: allPaths.firstOrNull()
    }
    val basename = remember(primaryPath) {
        primaryPath?.substringAfterLast("/")?.takeIf { it.isNotEmpty() } ?: "file"
    }

    val (additions, deletions) = remember(part) {
        val fileAdd = primaryFile?.additions
        val fileDel = primaryFile?.deletions
        if (fileAdd != null || fileDel != null) {
            (fileAdd ?: 0) to (fileDel ?: 0)
        } else {
            val patchText = buildString {
                part.state?.inputSummary?.let { append(it); append('\n') }
                part.state?.output?.let { append(it) }
            }
            countDiffLines(patchText)
        }
    }

    val expanded = expandedParts[expandedKey] ?: false

    // §5.4 v3 patch card: fully transparent, diff stats desaturated to
    // onSurfaceVariant (no green/red pop). Same visual weight as BasicTool.
    val oc = MaterialTheme.opencode
    Surface(
        modifier = modifier
            .padding(vertical = 1.dp)
            .testTag("toolcard.patch.$basename"),
        shape = RoundedCornerShape(6.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        border = BorderStroke(1.dp, oc.borderBase)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = basename,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (additions > 0 || deletions > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = buildString {
                            if (additions > 0) append("+$additions")
                            if (additions > 0 && deletions > 0) append(" ")
                            if (deletions > 0) append("-$deletions")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { onToggleExpand(expandedKey, expanded) }) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (expanded) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                    if (allPaths.isNotEmpty()) {
                        allPaths.forEach { path ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = path,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { onFileClick(path) }, modifier = Modifier.size(22.dp)) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = stringResource(R.string.files_show_in_files),
                                        modifier = Modifier.size(14.dp),
                                        tint = oc.accentText
                                    )
                                }
                            }
                        }
                    } else if (primaryPath != null) {
                        Text(
                            text = primaryPath,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ── opencode-web paradigm: borderless single-line tool rows ──────────────

/**
 * Compact single-line tool display for non-file-write, non-context, non-task
 * tools (bash, webfetch, websearch, and everything else). Hairline bordered
 * container (borderBase 1dp, 6dp radius, transparent background) for subtle
 * visual grouping without heavy card weight.
 *
 * Collapsed: `[chevron] [spinner] Title · subtitle`
 * Expanded: indented output in monospace.
 *
 * - bash → "Shell · \<command\>", expandable to `$ <command>` + output
 * - webfetch → "Web Fetch · \<url\>", never expandable
 * - websearch → "Web Search · \<query\>", expandable to output
 * - other → "Toolname · \<reason/inputSummary\>", expandable to output
 */
@Composable
private fun BasicTool(
    part: Part,
    onFileClick: (String) -> Unit,
    expandedParts: Map<String, Boolean>,
    onToggleExpand: (String, Boolean) -> Unit,
    expandedKey: String,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val toolName = part.tool ?: ""
    val lowerTool = toolName.lowercase()
    val status = part.stateDisplay
    val isRunning = status == "running"
    val isError = status == "error"
    val expanded = expandedParts[expandedKey] ?: false

    val oc = MaterialTheme.opencode

    val isBash = lowerTool.startsWith("bash") || lowerTool.startsWith("terminal") ||
        lowerTool.startsWith("cmd") || lowerTool.startsWith("shell")
    val isWebFetch = lowerTool.startsWith("webfetch") || lowerTool.startsWith("web_fetch")
    val isWebSearch = lowerTool.startsWith("websearch") || lowerTool.startsWith("web_search")

    val title: String
    val subtitle: String?
    val canExpand: Boolean

    when {
        isBash -> {
            title = "Shell"
            subtitle = part.toolInputSummary?.take(80)
            canExpand = true
        }
        isWebFetch -> {
            title = "Web Fetch"
            subtitle = part.toolInputSummary
            canExpand = false
        }
        isWebSearch -> {
            title = "Web Search"
            subtitle = part.toolInputSummary
            canExpand = true
        }
        else -> {
            title = toolName.replaceFirstChar { c -> c.uppercase() }
            subtitle = part.toolReason ?: part.toolInputSummary
            canExpand = true
        }
    }

    Surface(
        modifier = modifier.padding(vertical = 1.dp),
        shape = RoundedCornerShape(6.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        border = BorderStroke(1.dp, oc.borderBase)
    ) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .then(
                    if (canExpand) Modifier.clickable { onToggleExpand(expandedKey, expanded) }
                    else Modifier
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (canExpand) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.width(2.dp))
            }
            if (isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            if (subtitle != null) {
                Text(
                    text = " · ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            if (isError) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = "Tool error",
                    modifier = Modifier.size(14.dp),
                    tint = oc.stateDangerFg
                )
            }
        }

        // Expanded content
        if (expanded && canExpand) {
            Column(modifier = Modifier.padding(start = 8.dp, top = 2.dp, end = 8.dp, bottom = 6.dp)) {
                // For bash: show `$ <command>` header
                if (isBash) {
                    val command = part.toolInputSummary
                    if (!command.isNullOrEmpty()) {
                        Text(
                            text = "$ $command",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // Show output
                val output = part.toolOutput
                if (!output.isNullOrEmpty()) {
                    if (isBash) Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = output,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    }
}

// ── opencode-web paradigm: merged context tool group ─────────────────────

/**
 * Collapsed single-line summary for a contiguous run of context tools
 * (read / glob / grep / list). Replaces the old 2-column [FileCard] grid.
 *
 * Collapsed: `[spinner] Exploring · 3 reads, 2 searches` + chevron
 * Expanded: each tool as an indented trigger row (title · subtitle).
 */
@Composable
private fun ContextToolGroup(
    parts: List<Part>,
    expandedParts: Map<String, Boolean>,
    onToggleExpand: (String, Boolean) -> Unit,
    messageId: String,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val expandedKey = "${messageId}|ctx:${parts.first().id}"
    val expanded = expandedParts[expandedKey] ?: false

    val isRunning = parts.any { it.stateDisplay == "running" }

    // Count by category
    val readCount = parts.count { ToolCardClassifier.contextToolCategory(it) == ToolCardClassifier.ContextCategory.READ }
    val searchCount = parts.count { ToolCardClassifier.contextToolCategory(it) == ToolCardClassifier.ContextCategory.SEARCH }
    val listCount = parts.count { ToolCardClassifier.contextToolCategory(it) == ToolCardClassifier.ContextCategory.LIST }

    val countParts = mutableListOf<String>()
    if (readCount > 0) countParts.add("$readCount ${if (readCount == 1) "read" else "reads"}")
    if (searchCount > 0) countParts.add("$searchCount ${if (searchCount == 1) "search" else "searches"}")
    if (listCount > 0) countParts.add("$listCount ${if (listCount == 1) "list" else "lists"}")
    val countText = countParts.joinToString(", ")

    val stateWord = if (isRunning) "Exploring" else "Explored"

    val oc = MaterialTheme.opencode
    Surface(
        modifier = modifier.padding(vertical = 1.dp),
        shape = RoundedCornerShape(6.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        border = BorderStroke(1.dp, oc.borderBase)
    ) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clickable { onToggleExpand(expandedKey, expanded) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = "$stateWord · $countText",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }

        if (expanded) {
            Column(modifier = Modifier.padding(start = 8.dp, top = 2.dp, end = 8.dp, bottom = 4.dp)) {
                for (part in parts) {
                    val toolLabel = contextToolLabel(part)
                    val subtitle = part.toolInputSummary
                        ?: part.state?.pathFromInput
                        ?: part.metadata?.path
                        ?: ""
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$toolLabel · $subtitle",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
    }
}

/** Human-readable label for a context tool in the expanded group view. */
private fun contextToolLabel(part: Part): String {
    val tool = part.tool?.lowercase() ?: ""
    return when {
        tool.startsWith("read") -> "Read"
        tool.startsWith("glob") -> "Glob"
        tool.startsWith("grep") -> "Grep"
        tool.startsWith("list") -> "List"
        else -> tool.replaceFirstChar { c -> c.uppercase() }
    }
}
