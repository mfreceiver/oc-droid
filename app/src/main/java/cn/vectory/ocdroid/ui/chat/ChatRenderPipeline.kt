package cn.vectory.ocdroid.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import cn.vectory.ocdroid.data.model.FileDiff
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part

/**
 * §wave2-1-l4: single-pass pipeline that produces BOTH [renderBlocks] and
 * [lazyColumnKeys] in one [remember] block, eliminating the independent
 * L2 `remember` (lazyColumnKeys) and the M4→L2 cascade window.
 *
 * The keys are derived immediately from [renderBlocks] inside the same
 * [remember] block that builds blocks, so the "consumer gets keys
 * inconsistent with itemsIndexed" bug class becomes structurally impossible.
 *
 * The M3 step (reversedMessages) remains a SEPARATE [remember] with its
 * original narrowed key tuple (`streamingPartTextKeys` set equality), so
 * token-delta-only changes do NOT force reversedMessages to recompute.
 */
@Stable
internal class RenderPipeline(
    val messages: List<Message>,
    val renderBlocks: List<RenderBlock>,
    val lazyColumnKeys: List<String>,
)

/**
 * Merges the M4 (renderBlocks) + L2 (lazyColumnKeys) steps into a single
 * [remember] block. [reversedMessages] is pre-computed by the caller's M3
 * step and passed in as input.
 *
 * Key tuple = M4 keys VERBATIM + extra lazyColumnKeys deps:
 *   `reversedMessages, partsByMessage, streamingPartTexts,
 *    staleQuestionPartKeys, streamingReasoningPart, sessionIsRunning,
 *    sessionDiff, messages, hasMoreMessages, olderMessagesCursor`
 *
 * @param reversedMessages pre-computed reversed message list (M3 output)
 * @param messages the original (unreversed) message list — for the
 *   lazyColumnKeyList's load-more guard
 */
@Composable
internal fun rememberRenderPipeline(
    reversedMessages: List<Message>,
    partsByMessage: Map<String, List<Part>>,
    streamingPartTexts: Map<String, String>,
    staleQuestionPartKeys: Set<String>,
    streamingReasoningPart: Part?,
    sessionIsRunning: Boolean,
    sessionDiff: List<FileDiff>?,
    messages: List<Message>,
    hasMoreMessages: Boolean,
    olderMessagesCursor: String?,
): RenderPipeline = remember(
    // M4 keys — verbatim from current renderBlocks remember:
    reversedMessages, partsByMessage, streamingPartTexts,
    staleQuestionPartKeys, streamingReasoningPart, sessionIsRunning,
    // Extra keys for lazyColumnKeys (not covered by M4 deps):
    sessionDiff, messages, hasMoreMessages, olderMessagesCursor,
) {
    val renderBlocks = buildRenderBlocks(
        messages = reversedMessages.asReversed(),
        partsByMessage = partsByMessage,
        streamingPartTexts = streamingPartTexts,
        staleQuestionPartKeys = staleQuestionPartKeys,
        streamingReasoningPartId = streamingReasoningPart?.id,
        streamingReasoningMessageId = streamingReasoningPart?.messageId,
        sessionIsRunning = sessionIsRunning,
    ).asReversed()

    val lazyColumnKeys = lazyColumnKeyList(
        streamingReasoningPart = streamingReasoningPart,
        sessionDiff = sessionDiff,
        renderBlocks = renderBlocks,
        messages = messages,
        hasMoreMessages = hasMoreMessages,
        olderMessagesCursor = olderMessagesCursor,
    )

    RenderPipeline(
        messages = messages,
        renderBlocks = renderBlocks,
        lazyColumnKeys = lazyColumnKeys,
    )
}
