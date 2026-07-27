package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.MessageWithParts

/**
 * §11.1 fix-10 P1-1: result of [mergeSlimMessageSetWithConflict] — carries
 * the merged message list AND a [hasConflict] flag set when at least one
 * same-tuple-different-parts divergence was detected. The caller MUST
 * keep `dirty = true` when [hasConflict] is true (the authoritative parts
 * are stale; a later full/cursor reconcile is needed).
 */
internal data class SlimMergeResult(
    val messages: List<MessageWithParts>,
    val hasConflict: Boolean,
)

/**
 * §11.4 (slim message reliability joint plan): window-style merge of an
 * incoming message set onto the current authoritative set. Returns a
 * [SlimMergeResult] carrying the merged list + a conflict flag.
 *
 * See [mergeSlimMessageSet] for the full contract (this is the
 * conflict-signal-returning variant; [mergeSlimMessageSet] is a thin
 * wrapper that discards the flag for backward compat with existing
 * callers / tests).
 */
internal fun mergeSlimMessageSetWithConflict(
    authoritative: List<MessageWithParts>,
    incoming: List<MessageWithParts>,
    complete: Boolean,
): SlimMergeResult {
    if (!complete) return SlimMergeResult(authoritative, hasConflict = false)

    val merged = LinkedHashMap<String, MessageWithParts>()
    authoritative.forEach { merged[it.info.id] = it }
    var hasConflict = false
    incoming.forEach { message ->
        val previous = merged[message.info.id]
        val (result, conflict) = mergeSameMessageWithConflict(previous, message)
        merged[message.info.id] = result
        if (conflict) hasConflict = true
    }

    val sorted = merged.values.sortedWith(
        compareBy<MessageWithParts>(
            { it.info.time?.created ?: Long.MAX_VALUE },
            { it.info.time?.updated ?: Long.MAX_VALUE },
            { it.info.id },
        ),
    )
    return SlimMergeResult(sorted, hasConflict)
}

/**
 * Backward-compat wrapper: returns only the merged list (discards the
 * conflict flag). Existing callers and tests use this; new callers that
 * need the conflict signal use [mergeSlimMessageSetWithConflict].
 */
internal fun mergeSlimMessageSet(
    authoritative: List<MessageWithParts>,
    incoming: List<MessageWithParts>,
    complete: Boolean,
): List<MessageWithParts> = mergeSlimMessageSetWithConflict(authoritative, incoming, complete).messages

/**
 * §11.4: per-id merge — newer tuple replaces, older tuple ignored,
 * equal-tuple-with-different-parts keeps the authoritative entry (no
 * silent overwrite; no part-level authoritative merge in phase A).
 *
 * §11.1 fix-10 P1-1: returns [Pair]`(result, hasConflict)`. When
 * `hasConflict` is true, the caller MUST keep `dirty = true` — the
 * authoritative parts diverge from the incoming parts at the same tuple,
 * and a later full/cursor reconcile is needed to resolve the divergence.
 *
 * Pure.
 */
private fun mergeSameMessageWithConflict(
    previous: MessageWithParts?,
    incoming: MessageWithParts,
): Pair<MessageWithParts, Boolean> {
    if (previous == null) return incoming to false

    val order = compareWatermark(
        incoming.info.time?.updated, incoming.info.id,
        previous.info.time?.updated, previous.info.id,
    )

    return when {
        order > 0 -> incoming to false
        order < 0 -> previous to false
        incoming == previous -> previous to false
        else -> previous to true
        // §11.1 fix-10 P1-1: same tuple but parts differ → hasConflict=true.
        // Keep the authoritative entry; the caller keeps dirty=true so a
        // later reconcile re-fetches to resolve the divergence. The wire
        // model has no part revision, so a part-level authoritative merge
        // is deferred to phase B.
    }
}
