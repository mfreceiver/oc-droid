package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.ui.theme.CardWidthScope
import cn.vectory.ocdroid.ui.theme.Dimens

// ── Streaming overlay surface ───────────────────────────────────────────
// Extracted from [ChatMessageList] (§wave1-c r2 god-object split). Owns the
// STANDALONE streaming-reasoning card that floats at the visual top of the
// reverseLayout list while a reasoning part streams, before the finalized
// turn's inline ReasoningCard takes over.
//
// Scope note (why this file is thin): the BULK of streaming presentation
// logic — per-block streaming-text baking (computeMessageStreaming), the
// render-block pipeline, the flicker-fix filtering — already lives in
// ChatRenderBlockBuilder.kt / ChatMessageFilter.kt (extracted in earlier
// waves). The only standalone "overlay" presentation left in ChatMessageList
// was this single LazyColumn item, which is self-contained (no scroll/message
// state) and therefore safe to lift out without touching any remember key.

/**
 * The standalone streaming-reasoning card. Renders while
 * [streamingReasoningPart] is non-null (a reasoning part is actively
 * streaming); clears when the turn finalizes and the inline card in
 * MessageRow takes over with the SAME expand key (so expand state survives
 * the standalone→inline handoff).
 *
 * @param streamingReasoningPart the actively-streaming reasoning part
 * @param streamingText the accumulated streaming text for this part
 *   (`streamingPartTexts[streamingReasoningPart.id] ?: ""`)
 * @param expandedParts the chat-wide expand-state map (shared with the inline
 *   ReasoningCard so the §R-1 handoff preserves expand state)
 * @param onToggleExpand toggle callback (composerVM::togglePartExpand)
 */
@Composable
internal fun StreamingReasoningOverlay(
    streamingReasoningPart: Part,
    streamingText: String,
    expandedParts: Map<String, Boolean>,
    onToggleExpand: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val streamingKey = streamingReasoningPart.id
    // §R-1 (maxer): use the SAME expand-key format as the inline ReasoningCard
    // in MessageRow ("${messageId}|${partId}"), so the user's expand state
    // survives the standalone→inline transition when the turn finalizes.
    // Null-guard: messageId is always set here (the part.updated handler
    // returns early when messageID is absent), but defend against malformed.
    val streamingExpandKey = streamingReasoningPart.messageId
        ?.let { "$it|$streamingKey" } ?: "streaming|$streamingKey"
    // §card-width: responsive 2/3 width (capped 480dp), matching MessageRow.
    CardWidthScope(modifier = modifier) { cardMax ->
        ReasoningCard(
            text = streamingText,
            title = streamingReasoningPart.toolReason,
            isStreaming = true,
            expandedParts = expandedParts,
            onToggleExpand = onToggleExpand,
            expandedKey = streamingExpandKey,
            modifier = Modifier.widthIn(max = cardMax)
        )
    }
}
