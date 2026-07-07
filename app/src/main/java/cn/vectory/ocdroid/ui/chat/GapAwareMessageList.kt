package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.Message as MessageModel

/**
 * R-20 Phase 2 (plan §1 / §3): non-contiguous message model — a session's
 * loaded view is a list of [Entry]s where each slice of contiguous messages is
 * separated by an explicit [Entry.GapMarker]. This **replaces** the legacy
 * single-gap `ChatState.gapInfo: GapInfo?` (plan §3 N5 / glmer B1) so a session
 * may carry ≥1 independent gap, each paged backward at its own cursor.
 *
 * The list is rendered oldest-first; a [GapMarker] sits between the OLDER
 * slice (below) and the NEWER slice (above) it represents, exactly where the
 * history is missing. Multiple markers are sorted by their upperBoundary
 * message time ascending (see [withGaps]) so the dividers always land at the
 * correct seam regardless of insertion order.
 *
 * **Two types named "GapMarker"** (deliberate, plan §2):
 *  - [GapMarker] (top-level, this file) — the DOMAIN record carrying the full
 *    gap state (gapId + boundaries + cursor + fillState). This is what
 *    [cn.vectory.ocdroid.data.cache.CacheRepository.gapsOf] returns and what
 *    the [GapFillCoordinator] reads / mutates. It is the authoritative shape.
 *  - [Entry.GapMarker] (nested) — the slim UI projection (gapId + fillState
 *    only) embedded in the rendered [Entry] list. Use [GapMarker.toEntry] to
 *    project; the cursor/boundaries never reach the UI layer.
 */
enum class GapFillState {
    /** No fill in flight; the user can tap to page the next 50-message step. */
    Idle,
    /** A 50-step backward fill is currently running for this gap. */
    Filling,
    /** The server returned a null cursor before the anchor was reached — the
     *  gap cannot be bridged (history below the gap is gone). UI shows a
     *  non-tappable "无法补齐" marker. */
    Exhausted,
    /** The last fill step errored; the user can retry. */
    Error;
}

/**
 * The authoritative gap record. Mirrors the persisted
 * [cn.vectory.ocdroid.data.cache.GapMarkerEntity] (minus timestamps) and is
 * the return type of [cn.vectory.ocdroid.data.cache.CacheRepository.gapsOf].
 *
 * @param gapId synthetic UUID (generated at openGap time). Stable across the
 *   gap's lifecycle.
 * @param lowerAnchorMessageId the newest message id of the OLDER slice — the
 *   fill resolution target. A backward step containing this id resolves the gap.
 * @param upperBoundaryMessageId the oldest message id of the NEWER slice — the
 *   visual seam + the boundary that advances toward the anchor as fill lands.
 * @param nextBeforeCursor the server `before` cursor for the NEXT older step.
 *   `null` ⇒ history exhausted (state = Exhausted). Client never synthesizes
 *   this — it is the raw `X-Next-Cursor` / `Link rel=next` response-header value.
 * @param fillState drives both the UI divider label and the coordinator resume.
 */
data class GapMarker(
    val gapId: String,
    val lowerAnchorMessageId: String,
    val upperBoundaryMessageId: String,
    val nextBeforeCursor: String?,
    val fillState: GapFillState
) {
    /** Project to the slim UI entry used inside a rendered [Entry] list. */
    fun toEntry(): Entry.GapMarker = Entry.GapMarker(gapId = gapId, fillState = fillState)
}

/**
 * One element of the rendered chat list. Either a real [Message] or a
 * tappable [GapMarker] divider. Sealed so the Compose renderer is exhaustive.
 */
sealed interface Entry {
    /** A loaded message row. Wraps [cn.vectory.ocdroid.data.model.Message]. */
    data class Message(val message: MessageModel) : Entry

    /**
     * A gap divider between two message slices. Carries only [gapId] +
     * [fillState] — the cursor/boundaries live on the domain [GapMarker] and
     * are never needed at the UI layer (a tap routes [gapId] back to the
     * [GapFillCoordinator] which owns the full record).
     */
    data class GapMarker(val gapId: String, val fillState: GapFillState) : Entry
}

/**
 * Compose the non-contiguous [Entry] list from a flat oldest-first
 * [messages] list + a set of [gaps]. Each gap's divider is inserted immediately
 * BEFORE its [GapMarker.upperBoundaryMessageId] (i.e. between the older slice
 * and the newer slice's oldest message) — matching the seam semantics of the
 * legacy single-gap divider, generalised to ≥1 gap.
 *
 * Gaps are inserted in **upperBoundary message-time ascending** order (plan §3
 * invariant) so the dividers always land at the correct position regardless of
 * the input gap ordering; ties break by upperBoundary id for determinism.
 *
 * Gaps whose [GapMarker.upperBoundaryMessageId] is not present in [messages]
 * are dropped (the boundary message must be loaded for the divider to anchor
 * against — a defensive guard against a stale marker whose newer slice was
 * evicted).
 */
fun List<MessageModel>.withGaps(gaps: List<GapMarker>): List<Entry> {
    if (gaps.isEmpty()) return map { Entry.Message(it) }
    val indexById = mapIndexed { idx, m -> m.id to idx }.toMap()
    // Sort by upperBoundary message time ascending; resolve time via the
    // loaded message (the boundary message is by construction present).
    val sorted = gaps.sortedWith(
        compareBy(
            { indexById[it.upperBoundaryMessageId]?.let { idx -> this[idx].time?.created } ?: Long.MAX_VALUE },
            { it.upperBoundaryMessageId }
        )
    )
    val out = ArrayList<Entry>(size + sorted.size)
    // Cursor walks the oldest-first message list; emit each gap right before
    // its upperBoundary message index.
    var cursor = 0
    for (gap in sorted) {
        val boundaryIdx = indexById[gap.upperBoundaryMessageId] ?: continue
        if (boundaryIdx < cursor) continue // overlapping/duplicate boundary; skip
        while (cursor < boundaryIdx) {
            out.add(Entry.Message(this[cursor]))
            cursor++
        }
        out.add(gap.toEntry())
    }
    while (cursor < size) {
        out.add(Entry.Message(this[cursor]))
        cursor++
    }
    return out
}
