package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.FileDiff
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.ui.ScrollCheckpoint
import cn.vectory.ocdroid.ui.theme.Dimens

// ── §Wave5b-Q13: pure helpers for the Restore consumer (lifted out of the
//     @Composable body so they are JVM-testable without Robolectric). ──────

/**
 * §Wave5b-Q13: builds the LazyColumn body's key list IN ORDER, mirroring the
 * branches of the LazyColumn above. The Restore consumer uses this to resolve
 * a captured [ScrollCheckpoint.anchorKey] → LazyColumn index (so
 * `scrollToItem(idx, offset)` lands on the same logical message the user was
 * viewing when they opened the sub-agent).
 *
 * The order MUST match the LazyColumn body's declaration order exactly:
 *  1. "streaming-reasoning" (if streamingReasoningPart != null)
 *  2. "session-diff" (if sessionDiff non-empty)
 *  3. renderBlocks.map { it.id } (in itemsIndexed order)
 *  4. "load-more" (if messages non-empty + hasMoreMessages + cursor present)
 *
 * Any future reordering of the LazyColumn body MUST be mirrored here.
 */
internal fun lazyColumnKeyList(
    streamingReasoningPart: Part?,
    sessionDiff: List<FileDiff>?,
    renderBlocks: List<RenderBlock>,
    messages: List<Message>,
    hasMoreMessages: Boolean,
    olderMessagesCursor: String?,
): List<String> = buildList {
    if (streamingReasoningPart != null) add("streaming-reasoning")
    if (!sessionDiff.isNullOrEmpty()) add("session-diff")
    addAll(renderBlocks.map { it.id })
    if (messages.isNotEmpty() && hasMoreMessages && olderMessagesCursor != null) add("load-more")
}

/**
 * §Wave5b-Q13: the index + offset to pass to `listState.scrollToItem(idx,
 * offset)` for a Restore. Pure function — JVM-testable without a real
 * LazyListState.
 *
 * Resolution order:
 *  1. If [checkpoint.anchorKey] is non-null AND present in [currentKeys] →
 *     use that key's index, paired with [checkpoint.offset]. (Anchor wins
 *     because it survives message prepends / SSE appends / metadata-marker
 *     injection that shift indices without moving the user's logical
 *     position.)
 *  2. Otherwise → clamp [checkpoint.fallbackIndex] to
 *     `[0, currentKeys.size - 1]`, paired with [checkpoint.offset].
 *  3. If [currentKeys] is empty → returns `null` (the caller skips the
 *     scroll; the session has no renderable items yet).
 *
 * The offset is ALWAYS [checkpoint.offset] — the per-pixel offset within the
 * resolved item is independent of which item is resolved (it is the
 * pixel offset of the item's top edge from the viewport's top edge at
 * capture time, and the same pixel offset applies at restore).
 */
internal data class ResolvedRestore(val index: Int, val offset: Int)

internal fun resolveRestoreIndex(
    checkpoint: ScrollCheckpoint,
    currentKeys: List<String>,
): ResolvedRestore? {
    if (currentKeys.isEmpty()) return null
    val anchorIdx = checkpoint.anchorKey?.let { key -> currentKeys.indexOf(key).takeIf { it >= 0 } }
    val resolvedIndex = (anchorIdx ?: checkpoint.fallbackIndex)
        .coerceIn(0, currentKeys.size - 1)
    return ResolvedRestore(index = resolvedIndex, offset = checkpoint.offset)
}

internal fun shouldRenderInFlightEmpty(
    block: RenderBlock.Conversation,
    sessionIsRunning: Boolean
): Boolean = !block.message.isUser && block.parts.isEmpty() && sessionIsRunning &&
    block.message.error?.message.isNullOrBlank() && !block.isDecorationOnly

/**
 * §empty-msg: lightweight inline loading row rendered for an assistant message
 * shell that has arrived (message.updated) but whose first part has not —
 * `partsByMessage[id]` is empty and the session is still busy. Replaces the
 * bare timestamp bubble the prior logic rendered (which looked like an empty
 * reply). NOT rendered for completed messages whose parts are all blank —
 * those are filtered out of [ChatMessageList]'s `reversedMessages` entirely
 * by [isEffectivelyRenderableEmpty]. Padding mirrors MessageRow's
 * horizontal=16dp / vertical=4dp so the loading row paces with surrounding
 * turns; "生成中…" uses labelSmall + onSurfaceVariant for a quiet affordance.
 */
@Composable
internal fun InFlightEmptyLoading(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.spacing4, vertical = Dimens.spacing1),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(Dimens.iconXs),
            strokeWidth = Dimens.hairline * 2,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(Dimens.spacing2))
        Text(
            text = stringResource(R.string.chat_generating),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
