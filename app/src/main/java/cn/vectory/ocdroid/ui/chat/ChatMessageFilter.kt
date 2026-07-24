package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.isEffectivelyRenderableEmpty

/**
 * Pure filter predicate that determines whether a message should be hidden
 * (filtered out) from the rendered message list.
 *
 * No Compose or Android dependencies — JVM testable.
 */
internal fun isMessageFilteredOut(
    msg: Message,
    partsByMessage: Map<String, List<Part>>,
    streamingPartTextKeys: Set<String>,
    streamingReasoningPart: Part?,
    sessionIsRunning: Boolean,
): Boolean {
    val msgParts = partsByMessage[msg.id].orEmpty()
    val hasStreamingPart = msgParts.any { it.id in streamingPartTextKeys }
    val isStreamingMsg = hasStreamingPart ||
        streamingReasoningPart?.messageId == msg.id ||
        (!msg.isUser && sessionIsRunning &&
            (msgParts.isEmpty() || msgParts.any { it.isText || it.isReasoning }))
    val renderableEmpty = isEffectivelyRenderableEmpty(msgParts)
    return !msg.isUser && !isStreamingMsg &&
        msg.error?.message.isNullOrBlank() &&
        renderableEmpty
}

/**
 * Pure function that computes the filtered reversed messages list.
 * Delegates to [isMessageFilteredOut] for each message.
 */
internal fun computeFilteredReversedMessages(
    messages: List<Message>,
    partsByMessage: Map<String, List<Part>>,
    streamingPartTextKeys: Set<String>,
    streamingReasoningPart: Part?,
    sessionIsRunning: Boolean,
): List<Message> {
    return messages.reversed().filterNot { msg ->
        isMessageFilteredOut(msg, partsByMessage, streamingPartTextKeys, streamingReasoningPart, sessionIsRunning)
    }
}
