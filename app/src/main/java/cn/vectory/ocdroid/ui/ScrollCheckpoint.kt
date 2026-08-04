package cn.vectory.ocdroid.ui

import android.os.Parcel
import android.os.Parcelable
import androidx.lifecycle.SavedStateHandle

// ── §Wave5b-Q13: scroll-position state machine ─────────────────────────
//
// (a) Switching into a session usually means "show the latest". The exceptions
//     are父→子 openSubAgent (records the parent's last viewport) and 子→父
//     returnToParent (restores that viewport). Every other switch path
//     (swipe / tab-strip / picker / Files / Sessions / create / fork / close-
//     delete-next / cold-start) lands at Latest.
// (b) The state machine is a SINGLE-SLOT ADT ([pendingScrollRequest]) instead
//     of the pre-Wave5b `pendingJumpToLatest: String?` + scattered bookkeeping.
//     A single slot means there is exactly ONE in-flight scroll intent at a
//     time, which makes priority / clearing-order races impossible (oracle
//     ruling: the prior boolean + ADT pair was mutually exclusive anyway).
// (c) The consumer (ChatMessageContent) clears via compare-and-clear on
//     [PendingScrollRequest.requestId] — a fast A→B→C cascade where A's
//     consumer finishes last cannot accidentally clear C's newer intent.

/**
 * §Wave5b-Q13: behavior to apply when consuming [PendingScrollRequest].
 *
 *  - [Latest]: scroll to item 0 (reverseLayout ⇒ 0 = newest) + arm
 *    `followBottom`. Used by every "explicit switch into a session" path
 *    (swipe, tab-strip, picker, Files, Sessions, create/fork, close-delete-
 *    next, cold-start, Chat-tab reselect, send).
 *  - [Restore]: scroll to the captured [ScrollCheckpoint] and DISarm
 *    `followBottom` unless the resolved anchor happens to land at the bottom.
 *    Used by 子→父 returnToParent (Android Back + breadcrumb). The checkpoint
 *    is captured synchronously by the Compose layer at the openSubAgent call
 *    site (NOT via an async mirror — that cannot guarantee
 *    the last frame before navigation, per oracle).
 */
sealed interface ScrollBehavior {
    data object Latest : ScrollBehavior
    data class Restore(val checkpoint: ScrollCheckpoint) : ScrollBehavior
}

/**
 * §Wave5b-Q13: snapshot of the parent session's LazyListState at the moment
 * the user opened a sub-agent. Captured SYNCHRONOUSLY by the Compose layer
 * (ChatMessageContent's onOpenSubAgent wrapper) — never derived from an
 * async mirror, which oracle ruled cannot be trusted to hold the last
 * pre-navigation frame.
 *
 *  - [anchorKey]: the stable key (message id / "streaming-reasoning" /
 *    "session-diff" / "load-more") of the FIRST VISIBLE item at capture time.
 *    Preferred over the raw index because message prepends / SSE appends /
 *    metadata-marker injection can shift indices without moving the user's
 *    real position. `null` if the LazyListState had no layout info at the
 *    capture moment (list still measuring) — the resolver then falls back to
 *    [fallbackIndex].
 *  - [fallbackIndex]: listState.firstVisibleItemIndex at capture time. Used
 *    only when [anchorKey] is null OR not present in the current LazyColumn
 *    body (clamped to [0, itemCount)).
 *  - [offset]: listState.firstVisibleItemScrollOffset at capture time. Paired
 *    with the resolved index for scrollToItem(index, offset).
 *
 * §chat-list-detail §11 / G6 (B5-C2): [Parcelable] so the checkpoint can be
 * stored in a per-route-entry [androidx.lifecycle.SavedStateHandle] (Bundle-
 * backed). Manual [Parcelable] impl (no kotlin-parcelize plugin) keeps the
 * build config untouched. Round-trip verified by [ScrollCheckpointParcelableTest].
 */
data class ScrollCheckpoint(
    val anchorKey: String?,
    val fallbackIndex: Int,
    val offset: Int,
) : Parcelable {
    constructor(parcel: Parcel) : this(
        anchorKey = parcel.readString(),
        fallbackIndex = parcel.readInt(),
        offset = parcel.readInt(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(anchorKey)
        parcel.writeInt(fallbackIndex)
        parcel.writeInt(offset)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ScrollCheckpoint> {
        override fun createFromParcel(parcel: Parcel): ScrollCheckpoint = ScrollCheckpoint(parcel)
        override fun newArray(size: Int): Array<ScrollCheckpoint?> = arrayOfNulls(size)
    }
}

/**
 * §chat-list-detail §11 / G6 (B5): per-route-entry SavedStateHandle key under
 * which the PARENT's scroll checkpoint is persisted at openSubAgent time.
 * The handle belongs to the parent route entry; when the user pops back to
 * parent, the parent's [ChatScaffold] LaunchedEffect reads this key, consumes
 * it (single-shot), and replays the checkpoint as a Restore scroll intent.
 *
 * Keyed by childId (the route being navigated INTO) so a parent that has
 * multiple in-flight children (rare; only possible via deep-link fan-in) can
 * distinguish them. The single-slot pattern (one openSubAgent → one
 * checkpoint → one pop) means in practice there is at most one entry per
 * parent handle.
 *
 * The consume side iterates all keys with this prefix and consumes the
 * (single) match — see [consumeAnySubAgentCheckpoint].
 */
internal const val SUB_AGENT_CHECKPOINT_KEY_PREFIX: String = "subAgentCheckpoint:"

/** §chat-list-detail §11 / G6 (B5): build the SavedStateHandle key for a
 *  parent-side checkpoint keyed by the child route id. */
internal fun checkpointKeyForChild(childSessionId: String): String =
    SUB_AGENT_CHECKPOINT_KEY_PREFIX + childSessionId

/**
 * §chat-list-detail §11 / G6 (B5): consume-once helper. Pulls the first
 * `subAgentCheckpoint:*` entry out of [handle] (if any) and removes it so a
 * subsequent re-entry (config change / duplicate nav / fast pop) cannot
 * double-fire Restore. Returns null when no checkpoint is present (the
 * Latest-by-default case).
 *
 * Single-shot contract (§11): the caller MUST treat a non-null return as the
 * single scroll intent — re-dispatching Latest from openForRoute's default is
 * the no-checkpoint path.
 */
internal fun consumeAnySubAgentCheckpoint(handle: SavedStateHandle): ScrollCheckpoint? {
    val keys = handle.keys().filter { it.startsWith(SUB_AGENT_CHECKPOINT_KEY_PREFIX) }
    if (keys.isEmpty()) return null
    // §11: there is at most ONE in-flight child per parent in normal flow.
    // Iterate the (single) match; if a degenerate case ever produces more
    // than one, consume them all but only the first becomes a Restore intent
    // (the others are silently dropped — never applied — preserving the
    // "single scroll intent" contract).
    var first: ScrollCheckpoint? = null
    for (k in keys) {
        val cp = handle.remove<ScrollCheckpoint>(k)
        if (first == null) first = cp
    }
    return first
}

/**
 * §Wave5b-Q13: the single-slot "next scroll intent to consume" intent.
 * Written by [SessionSwitcher.switchTo] in the SAME mutateChat that flips
 * currentSessionId (so the consumer always sees a consistent pair). Read and
 * compare-and-cleared by [cn.vectory.ocdroid.ui.chat.ChatMessageList]'s
 * LaunchedEffect.
 *
 *  - [requestId]: a monotonic id (System.nanoTime). The consumer compares
 *    this against the live slot's id when clearing; a mismatch means a newer
 *    request has superseded this one (fast A→B→C cascade where A's consumer
 *    finishes last) and the clear is a no-op.
 *  - [targetSessionId]: the session the consumer must be on to fire. When
 *    chatState.currentSessionId != targetSessionId, the consumer skips
 *    (e.g. a Restore intent for parent fired while still on child during a
 *    brief race — wait until the session switch lands).
 *  - [behavior]: Latest or Restore(checkpoint).
 */
data class PendingScrollRequest(
    val requestId: Long,
    val targetSessionId: String,
    val behavior: ScrollBehavior,
)
