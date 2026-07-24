package cn.vectory.ocdroid.ui.chat

import androidx.compose.runtime.Stable
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part

// §ui-stream A1: @Stable on the sealed interface is honest — every variant
// is a data class built once in buildRenderBlocks and never mutated in
// place; two .equals() blocks are interchangeable for rendering. This lets
// the Compose runtime skip an itemsIndexed item whose block is value-equal
// to the previous frame (i.e. a NON-streaming row during a token delta),
// once the chat-wide streamingPartTexts Map is no longer captured by the row
// content lambda. See ChatMessageContent.kt §ui-stream for the full data-flow.
@Stable
internal sealed interface RenderBlock {
    val id: String

    /**
     * §ui-stream A1: a single conversation turn rendered via MessageCard.
     *
     * New fields baked here (instead of captured from the chat-wide Map by
     * the row content lambda) so that a token delta only invalidates the
     * streaming message's own block:
     *  - [streamingPartTexts]: the per-block slice of
     *    ChatState.streamingPartTexts (only entries whose key is a part id
     *    in THIS block). MessageCard/MessageRow read this instead of the
     *    chat-wide Map. For a non-streaming row this is an empty map; for
     *    the streaming row it carries the live partial text.
     *  - [isMessageStreaming]: the §omitted-streaming flag previously
     *    computed inline in the itemsIndexed lambda (which read the
     *    chat-wide Map). Computed here via [computeMessageStreaming] so the
     *    lambda no longer captures the Map for this check.
     *
     * renderBlocks as a whole is STILL keyed on the full streamingPartTexts
     * (it bakes the live text for ToolRun/Fold ThinkingParts), so it rebuilds
     * every token — but the rebuild produces value-EQUAL Conversation blocks
     * for non-streaming rows, which @Stable + the removed Map capture lets
     * Compose skip.
     */
    data class Conversation(
        val message: Message,
        val parts: List<Part>,
        val showMessageDecoration: Boolean = false,
        val isDecorationOnly: Boolean = false,
        val streamingPartTexts: Map<String, String> = emptyMap(),
        val isMessageStreaming: Boolean = false,
        override val id: String
    ) : RenderBlock

    data class ToolRun(
        val items: List<MessageToolRenderItem>,
        val firstPartId: String,
        val messageDecorations: List<Message> = emptyList(),
        override val id: String = runKey(firstPartId)
    ) : RenderBlock

    data class Fold(
        val items: List<MessageToolRenderItem>,
        val firstPartId: String,
        val messageDecorations: List<Message> = emptyList(),
        override val id: String = runKey(firstPartId)
    ) : RenderBlock {
        fun asFoldedToolRun(): ToolRenderItem.FoldedToolRun =
            ToolRenderItem.FoldedToolRun(items.map { it.item })
    }
}

/** A classified tool item plus the message context required by its card renderer. */
@Stable
internal data class MessageToolRenderItem(
    val message: Message,
    val item: ToolRenderItem
)

internal fun foldKey(firstPartId: String): String = "fold|$firstPartId"
internal fun runKey(firstPartId: String): String = "run|$firstPartId"

internal fun isCrossMessageFoldExpanded(
    fold: ToolRenderItem.FoldedToolRun,
    expandedParts: Map<String, Boolean>
): Boolean = expandedParts[foldKey(firstPartId(fold))] == true

/**
 * Flattens oldest-first messages into conversation and cross-message fold
 * blocks. Message boundaries do not end a tool run; user turns, assistant
 * prose/non-tool content, sub-agent tasks, and streaming reasoning do.
 *
 * remove-message-persistence Task 4: the non-contiguous gap path (a sealed
 * `Entry` ADT + an interleaved divider `RenderBlock` variant) was deleted —
 * the builder now consumes a flat `List<Message>` directly.
 */
internal fun buildRenderBlocks(
    messages: List<Message>,
    partsByMessage: Map<String, List<Part>>,
    streamingPartTexts: Map<String, String>,
    staleQuestionPartKeys: Set<String>,
    streamingReasoningPartId: String?,
    // §ui-stream A1: the message id of the active streaming reasoning part,
    // so [computeMessageStreaming] can mirror the original
    // `streamingReasoningPart?.messageId == message.id` check without the
    // builder needing the whole Part. Null when no reasoning stream is active.
    streamingReasoningMessageId: String? = null,
    sessionIsRunning: Boolean
): List<RenderBlock> {
    val blocks = mutableListOf<RenderBlock>()
    val pendingFold = mutableListOf<MessageToolRenderItem>()
    var pendingFirstPartId: String? = null

    // §ui-stream A1: central Conversation-block factory. Bakes the per-block
    // streaming-text slice + isMessageStreaming so the itemsIndexed row
    // content lambda never needs to capture the chat-wide streamingPartTexts
    // Map. showMessageDecoration is assigned later by the mapIndexed pass
    // (via .copy), so it keeps its default false here.
    fun conversation(
        message: Message,
        parts: List<Part>,
        id: String,
        isDecorationOnly: Boolean = false
    ): RenderBlock.Conversation {
        val blockStreamingTexts: Map<String, String> = if (streamingPartTexts.isEmpty() || parts.isEmpty()) {
            emptyMap()
        } else {
            buildMap {
                parts.forEach { p -> streamingPartTexts[p.id]?.let { put(p.id, it) } }
            }
        }
        return RenderBlock.Conversation(
            message = message,
            parts = parts,
            isDecorationOnly = isDecorationOnly,
            streamingPartTexts = blockStreamingTexts,
            isMessageStreaming = computeMessageStreaming(
                parts = parts,
                streamingPartTexts = streamingPartTexts,
                streamingReasoningMessageId = streamingReasoningMessageId,
                message = message,
                sessionIsRunning = sessionIsRunning
            ),
            id = id
        )
    }

    fun flushFold() {
        val anchor = pendingFirstPartId
        if (anchor != null && pendingFold.isNotEmpty()) {
            val snapshot = pendingFold.toList()
            val partCount = snapshot.sumOf { contextual ->
                when (val item = contextual.item) {
                    is ToolRenderItem.ContextGroup -> item.parts.size
                    is ToolRenderItem.FoldedToolRun -> item.items.size
                    else -> 1
                }
            }
            blocks += if (partCount >= 2) {
                RenderBlock.Fold(snapshot, anchor)
            } else {
                RenderBlock.ToolRun(snapshot, anchor)
            }
        }
        pendingFold.clear()
        pendingFirstPartId = null
    }

    fun appendToolRun(message: Message, run: List<Part>) {
        if (run.isEmpty()) return
        val classified = classifyToolRun(run, streamingPartTexts, staleQuestionPartKeys)
            .filterNot { it is ToolRenderItem.SubAgent }
        if (classified.isEmpty()) return
        if (pendingFirstPartId == null) pendingFirstPartId = firstPartId(classified.first())
        classified.forEach { pendingFold += MessageToolRenderItem(message, it) }
    }

    for (message in messages) {
        val parts = partsByMessage[message.id].orEmpty()

        // Metadata markers are always hard seams.
        if (message.role in cn.vectory.ocdroid.ui.METADATA_MARKER_ROLES) {
            flushFold()
            blocks += conversation(
                message = message,
                parts = parts,
                id = "conversation|${message.id}|empty"
            )
            continue
        }

        // A running assistant shell may later resolve to a tool call.
        // Keep its loading placeholder without severing an already
        // pending cross-message tool run. Idle empty messages and user
        // turns remain real seams.
        if (parts.isEmpty()) {
            if (message.isUser || !sessionIsRunning) flushFold()
            blocks += conversation(
                message = message,
                parts = parts,
                id = "conversation|${message.id}|empty"
            )
            continue
        }

        var conversationParts = mutableListOf<Part>()
        fun flushConversation() {
            if (conversationParts.isEmpty()) return
            flushFold()
            val snapshot = conversationParts.toList()
            blocks += conversation(
                message = message,
                parts = snapshot,
                id = "conversation|${message.id}|${snapshot.first().id}"
            )
            conversationParts = mutableListOf()
        }

        if (message.isUser) {
            flushFold()
            blocks += conversation(
                message = message,
                parts = parts.filterNot { it.id == streamingReasoningPartId },
                id = "conversation|${message.id}|user"
            )
            continue
        }

        var index = 0
        while (index < parts.size) {
            val part = parts[index]
            if (part.id == streamingReasoningPartId && part.isReasoning) {
                flushConversation()
                flushFold() // live-thinking is standalone and is a hard seam
                index++
            } else if (part.isSubAgentTask) {
                flushConversation()
                flushFold()
                blocks += conversation(
                    message = message,
                    parts = listOf(part),
                    id = "conversation|${message.id}|${part.id}"
                )
                index++
            } else if (part.isTool || part.isPatch || part.isReasoning) {
                flushConversation()
                // Reuse the canonical collector, but cap it at the first
                // task because D3.1 promotes sub-agents from an item
                // inside a tool run to a conversation-level hard break.
                val taskBoundary = parts.indexOfFirstFrom(index) { it.isSubAgentTask }
                val collectionEnd = if (taskBoundary < 0) parts.size else taskBoundary
                val (run, relativeNextIndex) = collectToolRun(
                    parts = parts.subList(0, collectionEnd),
                    startIndex = index,
                    streamingReasoningPartId = streamingReasoningPartId
                )
                appendToolRun(message, run)
                index = relativeNextIndex
            } else if (part.isStepStart || part.isStepFinish) {
                // §fold-fix: step markers are metadata, not conversation content.
                // Skipping them prevents flushConversation()->flushFold() from
                // severing the cross-message tool fold between consecutive tools.
                index++
            } else {
                conversationParts += part
                index++
            }
        }
        flushConversation()

        // A live reasoning part may be intentionally hidden because it
        // is rendered by the standalone streaming card. Preserve a
        // message-owned block so its footer/error still has an owner.
        val represented = blocks.any { block -> block.containsMessage(message.id) } ||
            pendingFold.any { it.message.id == message.id }
        if (!represented) {
            // A classified-empty tool message still owns visible
            // message-level decoration. Keep that owner in chronological
            // order and prevent tool runs on either side from folding
            // across the visual seam.
            flushFold()
            blocks += conversation(
                message = message,
                parts = emptyList(),
                isDecorationOnly = true,
                id = "conversation|${message.id}|decoration"
            )
        }
    }
    flushFold()

    // Footer/error is message-level UI. Assign it to only the final block that
    // represents each message, even when one message was split around tools or
    // when several tool-only messages share a cross-message fold.
    val decorationOwners = mutableMapOf<Int, MutableList<Message>>()
    messages.forEach { message ->
        val owner = blocks.indexOfLast { it.containsMessage(message.id) }
        if (owner >= 0) decorationOwners.getOrPut(owner) { mutableListOf() } += message
    }
    return blocks.mapIndexed { index, block ->
        val decorations = decorationOwners[index].orEmpty()
        when (block) {
            is RenderBlock.Conversation -> block.copy(
                showMessageDecoration = decorations.any { it.id == block.message.id }
            )
            is RenderBlock.ToolRun -> block.copy(messageDecorations = decorations)
            is RenderBlock.Fold -> block.copy(messageDecorations = decorations)
        }
    }
}

internal fun renderBlockTopPaddingDp(block: RenderBlock, index: Int): Int = when {
    index == 0 -> 0
    block is RenderBlock.Conversation && block.message.isUser -> 16
    else -> 4
}

/**
 * Terminal part display states: parts in these states are considered complete
 * (not in-flight). Used by [computeMessageStreaming] to filter out completed
 * tool/patch parts from the streaming check.
 *
 * §ui-stream A1: moved verbatim from ChatMessageContent.kt — the
 * isMessageStreaming computation (formerly inline in the itemsIndexed row
 * lambda, reading the chat-wide streamingPartTexts Map) is now baked into
 * RenderBlock.Conversation by [buildRenderBlocks] via this pure helper, so the
 * row lambda no longer captures the Map for this check.
 */
internal val TERMINAL_PART_STATES = setOf("completed", "done", "error")

/**
 * §ui-stream A1: PURE replication of the per-message streaming flag that was
 * formerly computed inline inside the itemsIndexed row content lambda in
 * ChatMessageContent.kt (lines ~1059-1066). Extracted here so
 * [buildRenderBlocks] can bake it into RenderBlock.Conversation.isMessageStreaming,
 * removing the chat-wide streamingPartTexts Map capture from the row lambda.
 *
 * The logic is byte-identical to the inline version it replaces:
 *  - any of this block's parts is actively streaming (id in streamingPartTexts), OR
 *  - the active streaming reasoning part belongs to this message, OR
 *  - this is a non-user message in a running session whose parts are empty OR
 *    contain a text/reasoning part OR a non-terminal tool/patch part.
 *
 * Drives the §omitted-streaming affordance (OmittedContentCard shows a
 * non-clickable "生成中…" skeleton while true). Pure (no Compose/state/time) so
 * it is JVM-testable without a harness.
 */
internal fun computeMessageStreaming(
    parts: List<Part>,
    streamingPartTexts: Map<String, String>,
    streamingReasoningMessageId: String?,
    message: Message,
    sessionIsRunning: Boolean
): Boolean {
    val hasActiveToolOrPatch = parts.any { p ->
        (p.isTool || p.isPatch) && p.stateDisplay?.lowercase() !in TERMINAL_PART_STATES
    }
    return parts.any { it.id in streamingPartTexts } ||
        streamingReasoningMessageId == message.id ||
        (!message.isUser && sessionIsRunning &&
            (parts.isEmpty() || parts.any { it.isText || it.isReasoning } ||
                hasActiveToolOrPatch))
}

private fun RenderBlock.containsMessage(messageId: String): Boolean = when (this) {
    is RenderBlock.Conversation -> message.id == messageId
    is RenderBlock.ToolRun -> items.any { it.message.id == messageId }
    is RenderBlock.Fold -> items.any { it.message.id == messageId }
}

private inline fun <T> List<T>.indexOfFirstFrom(startIndex: Int, predicate: (T) -> Boolean): Int {
    for (index in startIndex until size) if (predicate(this[index])) return index
    return -1
}
