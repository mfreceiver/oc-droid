package cn.vectory.ocdroid.ui

/**
 * Wave 2 lane L2: scroll-domain [reduce] branch bodies extracted as pure
 * helper functions. Same package as [AppAction] / [StoreState] — zero-import
 * dispatch from [reduce].
 *
 * Each helper is a verbatim lift of the original `when`-arm body (comments +
 * early `return state` guards preserved). Behavior-preserving: no field
 * added / removed / reordered.
 */

internal fun reduceScrollRequested(state: StoreState, action: AppAction.ScrollRequested): StoreState = state.copy(
    // §Wave5b-Q13: single-slot overwrite. A newer request ALWAYS supersedes
    // any prior one — there is no priority / merge logic. The consumer
    // observes the latest committed slot only (single dispatch = single
    // emission).
    chat = state.chat.copy(
        pendingScrollRequest = PendingScrollRequest(
            requestId = action.requestId,
            targetSessionId = action.targetSessionId,
            behavior = action.behavior,
        ),
    ),
)

internal fun reduceScrollConsumed(state: StoreState, action: AppAction.ScrollConsumed): StoreState {
    // §Wave5b-Q13: COMPARE-AND-CLEAR. Only the CURRENT intent (matching
    // requestId) is clearable. A stale consumer's clear (e.g. A's consumer
    // finishing after B's intent is already live) is a silent no-op so it
    // cannot wipe the newer intent.
    val live = state.chat.pendingScrollRequest
    return if (live != null && live.requestId == action.requestId) {
        state.copy(chat = state.chat.copy(pendingScrollRequest = null))
    } else {
        state
    }
}

internal fun reduceParentCheckpointStored(state: StoreState, action: AppAction.ParentCheckpointStored): StoreState = state.copy(
    // §Wave5b-Q13: append the (childId → checkpoint) entry. Preserves any
    // other entries (a user can be deep in child-of-child-of-child
    // navigation; each openSubAgent stores its own parent's checkpoint).
    chat = state.chat.copy(
        parentReturnCheckpoints = state.chat.parentReturnCheckpoints + (action.childId to action.checkpoint),
    ),
)

internal fun reduceParentCheckpointConsumed(state: StoreState, action: AppAction.ParentCheckpointConsumed): StoreState = state.copy(
    // §Wave5b-Q13: remove only the matching childId key. No-op if absent
    // (double-consume / cleared by a host purge between store + consume).
    chat = state.chat.copy(
        parentReturnCheckpoints = state.chat.parentReturnCheckpoints - action.childId,
    ),
)
