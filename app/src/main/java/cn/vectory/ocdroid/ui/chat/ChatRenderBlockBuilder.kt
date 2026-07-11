package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part

/** One stable LazyColumn item in the flattened chat timeline. */
internal sealed interface RenderBlock {
    val id: String

    data class Conversation(
        val message: Message,
        val parts: List<Part>,
        val showMessageDecoration: Boolean = false,
        val isDecorationOnly: Boolean = false,
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

    data class Gap(
        val marker: Entry.GapMarker,
        override val id: String = "gap-${marker.gapId}"
    ) : RenderBlock
}

/** A classified tool item plus the message context required by its card renderer. */
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
 * Flattens oldest-first message/gap entries into conversation and cross-message
 * fold blocks. Message boundaries do not end a tool run; user turns, assistant
 * prose/non-tool content, sub-agent tasks, streaming reasoning, and gaps do.
 */
internal fun buildRenderBlocks(
    entries: List<Entry>,
    partsByMessage: Map<String, List<Part>>,
    streamingPartTexts: Map<String, String>,
    staleQuestionPartKeys: Set<String>,
    streamingReasoningPartId: String?
): List<RenderBlock> {
    val blocks = mutableListOf<RenderBlock>()
    val pendingFold = mutableListOf<MessageToolRenderItem>()
    var pendingFirstPartId: String? = null

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

    for (entry in entries) {
        when (entry) {
            is Entry.GapMarker -> {
                flushFold()
                blocks += RenderBlock.Gap(entry)
            }
            is Entry.Message -> {
                val message = entry.message
                val parts = partsByMessage[message.id].orEmpty()

                // Metadata markers and in-flight empty shells remain ordinary
                // conversation items even though they carry no parts.
                if (message.role in cn.vectory.ocdroid.ui.METADATA_MARKER_ROLES || parts.isEmpty()) {
                    flushFold()
                    blocks += RenderBlock.Conversation(
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
                    blocks += RenderBlock.Conversation(
                        message = message,
                        parts = snapshot,
                        id = "conversation|${message.id}|${snapshot.first().id}"
                    )
                    conversationParts = mutableListOf()
                }

                if (message.isUser) {
                    flushFold()
                    blocks += RenderBlock.Conversation(
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
                        blocks += RenderBlock.Conversation(
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
                    blocks += RenderBlock.Conversation(
                        message = message,
                        parts = emptyList(),
                        isDecorationOnly = true,
                        id = "conversation|${message.id}|decoration"
                    )
                }
            }
        }
    }
    flushFold()

    // Footer/error is message-level UI. Assign it to only the final block that
    // represents each message, even when one message was split around tools or
    // when several tool-only messages share a cross-message fold.
    val decorationOwners = mutableMapOf<Int, MutableList<Message>>()
    entries.filterIsInstance<Entry.Message>().forEach { entry ->
        val owner = blocks.indexOfLast { it.containsMessage(entry.message.id) }
        if (owner >= 0) decorationOwners.getOrPut(owner) { mutableListOf() } += entry.message
    }
    return blocks.mapIndexed { index, block ->
        val decorations = decorationOwners[index].orEmpty()
        when (block) {
            is RenderBlock.Conversation -> block.copy(
                showMessageDecoration = decorations.any { it.id == block.message.id }
            )
            is RenderBlock.ToolRun -> block.copy(messageDecorations = decorations)
            is RenderBlock.Fold -> block.copy(messageDecorations = decorations)
            is RenderBlock.Gap -> block
        }
    }
}

private fun RenderBlock.containsMessage(messageId: String): Boolean = when (this) {
    is RenderBlock.Conversation -> message.id == messageId
    is RenderBlock.ToolRun -> items.any { it.message.id == messageId }
    is RenderBlock.Fold -> items.any { it.message.id == messageId }
    is RenderBlock.Gap -> false
}

private inline fun <T> List<T>.indexOfFirstFrom(startIndex: Int, predicate: (T) -> Boolean): Int {
    for (index in startIndex until size) if (predicate(this[index])) return index
    return -1
}
