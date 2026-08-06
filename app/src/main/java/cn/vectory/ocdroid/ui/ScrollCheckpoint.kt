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
    /**
     * §scroll-guard-fix: the session id of the PARENT that captured this
     * checkpoint at openSubAgent time. Used by [consumeAnySubAgentCheckpoint]
     * as the direction guard: a checkpoint is consumed ONLY when the current
     * session == capturedFromSessionId (the user returned to the parent that
     * wrote it). Nullable for backward-compat with checkpoints persisted
     * before this field existed (treated as "unknown origin" → never consumed,
     * degrading to Latest — same as the pre-fix broken return-to-parent, so
     * no new regression).
     */
    val capturedFromSessionId: String? = null,
) : Parcelable {
    constructor(parcel: Parcel) : this(
        anchorKey = parcel.readString(),
        fallbackIndex = parcel.readInt(),
        offset = parcel.readInt(),
        capturedFromSessionId = parcel.readString(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(anchorKey)
        parcel.writeInt(fallbackIndex)
        parcel.writeInt(offset)
        parcel.writeString(capturedFromSessionId)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ScrollCheckpoint> {
        override fun createFromParcel(parcel: Parcel): ScrollCheckpoint = ScrollCheckpoint(parcel)
        override fun newArray(size: Int): Array<ScrollCheckpoint?> = arrayOfNulls(size)
    }
}

/**
 * §chat-list-detail §11 / G6 (B5) + §scroll-guard-fix: SavedStateHandle key
 * under which the PARENT's scroll checkpoint is persisted at openSubAgent time.
 *
 * §scroll-guard-fix: chat→chat navigation uses `launchSingleTop`, which in
 * Navigation 2.8.x IN-PLACE REPLACES the single chat back-stack slot (entry
 * object is new, but id/ViewModelStore/SavedStateHandle bloodline is
 * inherited). So parent and child do NOT each get their own NavBackStackEntry
 * — they SHARE one slot and one SavedStateHandle. The stack is always
 * [Sessions, <one chat entry>] for chat→chat transitions (no separate child
 * entry is ever pushed). Nested sub-agents (P→C→G) therefore accumulate
 * multiple `subAgentCheckpoint:*` keys on this SAME shared handle.
 *
 * Direction (enter-child vs return-to-parent vs jump-to-unrelated) is
 * disambiguated at consume time by the checkpoint's
 * [ScrollCheckpoint.capturedFromSessionId] vs the current chromeSessionId —
 * see [consumeAnySubAgentCheckpoint]. The key's childId is now only for
 * uniqueness (one key per child), NOT for direction.
 *
 * Keyed by childId (the route being navigated INTO) for uniqueness.
 */
internal const val SUB_AGENT_CHECKPOINT_KEY_PREFIX: String = "subAgentCheckpoint:"

/** §chat-list-detail §11 / G6 (B5): build the SavedStateHandle key for a
 *  parent-side checkpoint keyed by the child route id. */
internal fun checkpointKeyForChild(childSessionId: String): String =
    SUB_AGENT_CHECKPOINT_KEY_PREFIX + childSessionId

/**
 * §chat-list-detail §11 / G6 (B5) + §scroll-guard-fix: consume-once helper
 * with a DIRECTION GUARD keyed by [ScrollCheckpoint.capturedFromSessionId].
 *
 * Because chat→chat navigation uses `launchSingleTop` (Navigation 2.8.x
 * IN-PLACE REPLACES the single chat back-stack slot — entry object is new but
 * id/ViewModelStore/SavedStateHandle bloodline is inherited), parent and
 * child SHARE one SavedStateHandle. Nested sub-agents (P→C→G) accumulate
 * multiple `subAgentCheckpoint:*` keys on this SAME handle. So enter-child,
 * return-to-parent, enter-deeper-nested-child, and jump-to-unrelated-session
 * ALL re-fire the consumer LaunchedEffect and all see the same key set.
 *
 * Direction is disambiguated by comparing each checkpoint's
 * `capturedFromSessionId` (the parent that CAPTURED it) against
 * [currentSessionId]:
 *
 *  - capturedFrom == [currentSessionId] ⇒ RETURN-TO-PARENT. The user is back
 *    on the session that wrote this checkpoint → consume-once + Restore.
 *  - capturedFrom != [currentSessionId] (or null) ⇒ NOT ours. This is either
 *    enter-child, enter-deeper-nested-child, or a jump to an unrelated
 *    session. LEAVE the key in the handle — its own return-to-capturing-
 *    parent path still needs it. openForRoute's Latest stands.
 *
 * This precisely handles every shared-handle scenario:
 *  - P→C enter-child:        `:C{from:P}`,  current=C → P≠C, skip ✓
 *  - C→P return-parent:      `:C{from:P}`,  current=P → P==P, consume ✓
 *  - P→C→G enter-G:          `:C{from:P}`,`:G{from:C}`, current=G → both≠G, skip both ✓
 *  - G→C return (nested):    current=C → `:G{from:C}` consume, `:C{from:P}` skip ✓
 *  - C→D jump-unrelated:     `:C{from:P}`,  current=D → P≠D, skip (no wrong Restore) ✓
 *
 * Single-shot contract (§11): the caller treats a non-null return as the
 * single scroll intent. A parent has one outgoing openSubAgent at a time, so
 * at most one checkpoint matches capturedFrom==current per return; a
 * degenerate multi-match consumes all matches but only the first is Restore.
 */
internal fun consumeAnySubAgentCheckpoint(
    handle: SavedStateHandle,
    currentSessionId: String,
): ScrollCheckpoint? {
    val keys = handle.keys().filter { it.startsWith(SUB_AGENT_CHECKPOINT_KEY_PREFIX) }
    if (keys.isEmpty()) return null
    // §scroll-guard-fix: consume ONLY the checkpoint whose capturing-parent ==
    // currentSessionId (return-to-parent). All others (enter-child / nested /
    // unrelated-jump) are LEFT for their own return path.
    var first: ScrollCheckpoint? = null
    for (k in keys) {
        val cp = handle.get<ScrollCheckpoint>(k) ?: continue
        if (cp.capturedFromSessionId == currentSessionId) {
            handle.remove<ScrollCheckpoint>(k)
            if (first == null) first = cp
        }
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
