package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.ui.controller.allSessionsById
import cn.vectory.ocdroid.ui.controller.applyArchiveEviction
import cn.vectory.ocdroid.ui.controller.applyArchivedChatClear
import cn.vectory.ocdroid.ui.controller.cleanScrollStateForSubtree
import cn.vectory.ocdroid.ui.controller.removeSessions
import cn.vectory.ocdroid.ui.controller.subtreeIds

/**
 * Wave 2 lane L2: cross-domain [reduce] branch bodies extracted as pure
 * helper functions. Each branch touches MULTIPLE [StoreState] slices and
 * is lifted VERBATIM as ONE helper with a SINGLE `state.copy(...)` commit
 * at the end — NEVER split into per-slice helpers (lost-update / double-
 * write bug, rev-grok R1 catch). Same package as [AppAction] /
 * [StoreState] — zero-import dispatch from [reduce].
 */

internal fun reduceDraftSessionMaterialized(state: StoreState, action: AppAction.DraftSessionMaterialized): StoreState = state.copy(
    sessionList = state.sessionList.copy(
        sessions = upsertSession(state.sessionList.sessions, action.session),
        openSessionIds = action.openSessionIds,
        // §Q4-strict-sync: a freshly-created session is NOT yet in the
        // server's authoritative listing. Track its id as pending-create
        // so the next mergeRefreshedSessionsPreservingLocalActivity keeps
        // it alive until a REST refresh or SSE session.created confirms it
        // (or the 30 s sweep drops it). Added atomically in the SAME
        // dispatch as the session upsert (no torn intermediate).
        pendingCreateIds = state.sessionList.pendingCreateIds + action.session.id,
        pendingCreatedAt = state.sessionList.pendingCreatedAt + (action.session.id to action.viewedAt),
    ),
    chat = state.chat.copy(
        currentSessionId = action.session.id,
    ),
    unread = state.unread.copy(
        unreadSessions = state.unread.unreadSessions - action.session.id,
        lastViewedTime = state.unread.lastViewedTime + (action.session.id to action.viewedAt),
    ),
    composer = state.composer.copy(
        draftWorkdir = null,
    ),
)

internal fun reduceSessionArchived(state: StoreState, action: AppAction.SessionArchived): StoreState {
    // Apply archive eviction unconditionally (upsert archived + new openIds).
    // Apply the chat CONTENT clear IFF the archived session IS the current
    // one — derived from the snapshot, not carried on the action.
    val isCurrent = state.chat.currentSessionId == action.session.id
    val newSessionList = state.sessionList.applyArchiveEviction(action.session, action.openSessionIds).first
    // §Wave5b-Q13 blocker-2 fix: SCROLL-STATE cleanup runs UNCONDITIONALLY
    // for the archived subtree (chat content remains current-only). For
    // the current-archived case applyArchivedChatClear already wipes both
    // fields, so cleanScrollStateForSubtree is a no-op; for non-current
    // archived ids it cleans a stale pendingScrollRequest (target in
    // subtree) + parentReturnCheckpoints (key in subtree) without
    // touching messages / parts / currentSessionId.
    val newChat = if (isCurrent) state.chat.applyArchivedChatClear().first else state.chat
    // §task5-lifecycle (final-review fix 1): the archived session's unread
    // badge + any pending question bound to it MUST NOT survive the archive.
    // The cleanup is computed over the FULL three-source subtree of the
    // archived id — defensive against a server that only emits the root
    // archive event (descendants that did NOT get their own session.updated
    // event still get cleaned atomically here). Done in the SAME committed
    // state as the archive itself so collectors never observe an "archived
    // but still unread" torn state.
    val archivedId = action.session.id
    val subtree = subtreeIds(
        archivedId,
        state.sessionList.sessions,
        state.sessionList.directorySessions,
        state.sessionList.childSessions,
    )
    val cleanedQuestions = newSessionList.pendingQuestions.filter { it.sessionId !in subtree }
    val newUnread = state.unread.removeSessions(subtree)
    // §Wave5b-Q13 blocker-2: apply UNCONDITIONAL scroll-state cleanup for
    // the archived subtree (no-op for current-archived — applyArchivedChatClear
    // above already wiped both fields; substantive for non-current archived
    // ids that had a stale pendingScrollRequest targeting them or a
    // parentReturnCheckpoints entry keyed by them).
    val newChatCleaned = newChat.cleanScrollStateForSubtree(subtree)
    // §final-gate I-3 (review-final-rev-gpt-20260719081038 §2): prune the
    // archived subtree's entries from sessionErrorsById atomically in the
    // same committed state as the archive. Pre-fix the archived sid's
    // lastError survived forever (T12 only removes entries on explicit
    // `lastError = Cleared`), producing unbounded retention + a stale
    // banner if the user later un-archives or the server reuses the id.
    // The subtree scope mirrors the unread / pendingQuestions cleanup
    // (defensive against a server that only emits the root archive event
    // — descendants that did NOT get their own session.updated still get
    // pruned here). T12's set/remove producer logic is unchanged.
    val cleanedSessionErrors = state.sessionList.sessionErrorsById.filterKeys { it !in subtree }
    return state.copy(
        sessionList = newSessionList.copy(
            pendingQuestions = cleanedQuestions,
            activeSessionIds = newSessionList.activeSessionIds - subtree,
            sessionErrorsById = cleanedSessionErrors,
        ),
        chat = newChatCleaned,
        unread = newUnread,
    )
}

internal fun reduceHostStatePurged(state: StoreState, action: AppAction.HostStatePurged): StoreState {
    // §slice-only-preserve: ChatState carries three fields NOT mirrored
    // to AppState (isCompacting / compactStartedAt / refreshNonce — see
    // HostProfileController.kt:475-479). [clearSessionData] uses .copy()
    // on the existing ChatState so they are preserved; only the per-
    // session chat fields are reset.
    val (newChat, newSessionList, newUnread) = if (!action.preserveServerGroupData) {
        // Cross-group (异组 switch / delete active host): full purge of
        // per-server + per-profile state.
        // §fix-leak-window (release-gate fix B): clearSessionData also
        // resets currentModel / olderMessagesCursor / hasMoreMessages /
        // staleNotice / revertCutoffs / SSE-coalesce buffers, AND sessionList pendingPermissions/pendingQuestions are
        // cleared — pre-B2 left all of these stale (verified via
        // `git show e190cce^:.../HostProfileController.kt` purgePerHostState
        // + `git show e190cce^:.../AppCoreOrchestration.kt`
        // createSessionInWorkdirForEffect — neither cleared them). This is
        // a deliberate IMPROVEMENT, not a missed regression.
        Triple(
            state.chat.clearSessionData(),
            state.sessionList.copy(
                sessions = emptyList(),
                directorySessions = emptyMap(),
                openSessionIds = emptyList(),
                sessionStatuses = emptyMap(),
                activeSessionIds = emptySet(),
                sessionTodos = emptyMap(),
                sessionDiffs = emptyMap(),
                // §gpter-residual: cross-group purge must also drop cached
                // child trees and completeness proofs — a root-id collision
                // across hosts would otherwise let a stale proof skip new-host
                // hydration. Bump the epoch so any in-flight child load
                // captured before the switch is dropped fail-closed instead
                // of committing the prior host's children here.
                childSessions = emptyMap(),
                completeRootIds = emptySet(),
                completenessEpoch = state.sessionList.completenessEpoch + 1L,
                // §fix-leak-window (fix B): pending permission / question
                // requests belong to the prior host's sessions — must NOT
                // survive a cross-group switch.
                pendingPermissions = emptyList(),
                pendingQuestions = emptyList(),
                // §Q4-strict-sync: cross-group purge must drop pending-
                // create ids — they reference the prior host's sessions
                // and would ghost into the new host's list.
                pendingCreateIds = emptySet(),
                pendingCreatedAt = emptyMap(),
                // §final-gate I-3 (review-final-rev-gpt-20260719081038 §2):
                // cross-group purge must drop the entire sessionErrorsById
                // map — entries reference the prior host's sessions and a
                // root-id collision on the new host would let T17 render
                // the prior host's banner. Mirrors the cross-group reset
                // of sessionStatuses / pendingPermissions / pendingQuestions
                // above (T12's set/remove logic is unchanged — this is a
                // lifecycle cleanup, not a producer-path change).
                sessionErrorsById = emptyMap(),
                // I-2 v2 §3.3: cross-group purge MUST reset the
                // aggregation signals — they reference the prior host's
                // aggregation state and a stale "FAILED" would otherwise
                // surface on the new host. Defaults to COMPLETE (no signal).
                // Tied to I-3's sessionErrorsById cleanup (same lifecycle).
                questionAggregationSignal = SlimAggregationSignal(),
                permissionAggregationSignal = SlimAggregationSignal(),
                // §fix-close-all-residual: re-arm the cold-start
                // auto-select for the new host — its first load should
                // land the user on a session just like a fresh launch.
                hasCompletedInitialLoad = false,
            ),
            state.unread.copy(
                unreadSessions = emptySet(),
                lastViewedTime = emptyMap(),
                // §unread-soak: clear the soak map on cross-group purge so
                // a stale idleSince entry from the prior host cannot later
                // fire an unread badge for a session that no longer exists.
                idleSince = emptyMap(),
            ),
        )
    } else {
        // Same-group switch: per-server data (sessions / unread /
        // directorySessions / statuses / todos / diffs) preserved. Only
        // the streaming overlay is cleared (a stale delta from the old
        // profile's in-flight turn must NOT bleed into the new profile's
        // view).
        //
        // §chat-ux-batch T7 review-fix (I2): a pending agent/model pick
        // belongs to the PRIOR profile's next send — it must NOT survive
        // a host/profile transition even within the same server group.
        // Pre-fix this branch preserved pendingAgent/pendingModel
        // untouched, so a pick from profile A leaked into profile B's
        // first send (violates T7's no-cross-transition-carry contract).
        // Mirrors the cross-group branch's clearSessionData() reset of
        // these two transient fields.
        Triple(
            state.chat.copy(
                streamingPartTexts = emptyMap(),
                streamOwned = emptyMap(),
                streamingReasoningPart = null,
                pendingAgent = null,
                pendingModel = null,
                // §Wave5b-Q13: a host/profile transition invalidates any
                // pending scroll intent + the parent-return backstack
                // even within the same server group. The scroll slot
                // references a session the user is navigating away from
                // (or that may be re-laid-out differently on the new
                // profile); the backstack is per-session navigation
                // context that has no carry across profiles. Mirrors the
                // cross-group branch's clearSessionData() reset.
                pendingScrollRequest = null,
                parentReturnCheckpoints = emptyMap(),
            ),
            // §Q4-strict-sync: clear pendingCreateIds even on same-group
            // switch (the spec mandates "host switch → clear pending"). A
            // pending id from profile A is meaningless on profile B even
            // within the same server group; clearing is safer (no ghost)
            // and the ids re-populate naturally from the next REST refresh.
            state.sessionList.copy(
                pendingCreateIds = emptySet(),
                pendingCreatedAt = emptyMap(),
                activeSessionIds = emptySet(),
            ),
            state.unread,
        )
    }
    return state.copy(
        chat = newChat,
        sessionList = newSessionList,
        unread = newUnread,
        // Per-profile UX — ALWAYS reset regardless of group.
        composer = state.composer.copy(draftWorkdir = null),
        settings = state.settings.copy(availableCommands = emptyList()),
        connection = state.connection.copy(serverVersion = null),
    )
}

internal fun reduceBulkSessionsRefreshed(state: StoreState, action: AppAction.BulkSessionsRefreshed): StoreState {
    // FIX-A/C (archive-sync, review-blocker): atomic bulk-refresh commit.
    // Writes the merged list + pruned openIds + load flags in ONE step.
    //
    // gro-2 Blocker 1 (round-2): subtree / unread / pendingQuestions
    // cleanup now runs UNCONDITIONALLY over ALL archived ids (not just
    // the current session) — mirroring SessionArchived's unconditional
    // subtree cleanup. Previously the else-branch (non-current archived)
    // skipped cleanup entirely, leaking stale unread badges +
    // pendingQuestions for non-current archived open tabs (inflating
    // crossSessionPendingCount). The CHAT-CLEAR remains current-only:
    // only the archived CURRENT session's chat is wiped (non-current
    // archived ids have no active chat window to clear).
    val archivedIds = action.sessions
        .filter { it.isArchived }
        .map { it.id }
        .toSet()
    val currentId = state.chat.currentSessionId
    val isCurrentArchived = currentId != null && currentId in archivedIds
    // §Q4-strict-sync: confirmation is based only on ids from the raw REST
    // response, never action.sessions (which is the merged list and may
    // contain preserved pending-local sessions). The caller also captures
    // sweepNow so this pure reducer can apply the independent registration-
    // timestamp timeout atomically with the merged sessions write.
    val remainingPendingIds = state.sessionList.pendingCreateIds
        .minus(action.confirmedServerIds)
        .filterTo(mutableSetOf()) { pendingId ->
            val registeredAt = state.sessionList.pendingCreatedAt[pendingId]
            registeredAt != null &&
                action.sweepNow - registeredAt <= MainViewModelTimings.pendingCreateTimeoutMs
        }
    val newSessionList = state.sessionList.copy(
        sessions = action.sessions,
        openSessionIds = action.openSessionIds,
        hasMoreSessions = action.hasMoreSessions,
        isLoadingMoreSessions = false,
        isRefreshingSessions = false,
        pendingCreateIds = remainingPendingIds,
        pendingCreatedAt = state.sessionList.pendingCreatedAt.filterKeys { it in remainingPendingIds },
        // A bulk refresh is authoritative for structure (archive-sync),
        // so any cached completeness proof may be stale if SSE dropped
        // events. Discard proofs and bump the epoch so in-flight hydration
        // is dropped fail-closed; the next tick re-hydrates fresh trees.
        completeRootIds = emptySet(),
        completenessEpoch = state.sessionList.completenessEpoch + 1L,
        activeSessionIds = state.sessionList.activeSessionIds.intersect(
            allSessionsById(
                action.sessions,
                state.sessionList.directorySessions,
                state.sessionList.childSessions,
            ).keys
        ),
        // T1c gap fix: bulk refresh (incl. archive-sync early-return path)
        // is a completed initial load — set the flag atomically so the
        // separate mutateSessionList patch at SessionListActions is gone.
        hasCompletedInitialLoad = true,
    )
    // Compute the subtree UNION over ALL archived ids. Each archived root
    // may have descendants that did NOT get their own archive event —
    // defensive subtree cleanup (mirrors SessionArchived's logic).
    val allArchivedSubtree = archivedIds.flatMap { archivedId ->
        subtreeIds(
            archivedId,
            action.sessions,
            newSessionList.directorySessions,
            newSessionList.childSessions,
        )
    }.toSet()
    val cleanedQuestions = newSessionList.pendingQuestions
        .filter { it.sessionId !in allArchivedSubtree }
    val newUnread = state.unread.removeSessions(allArchivedSubtree)
    // Chat-clear is CURRENT-ONLY (non-current archived ids have no active
    // chat window). applyArchivedChatClear also wipes pendingScrollRequest
    // + parentReturnCheckpoints (FIX-B / §Wave5b-Q13).
    val newChat = if (isCurrentArchived) {
        state.chat.applyArchivedChatClear().first
    } else {
        state.chat
    }
    // §Wave5b-Q13 blocker-2: UNCONDITIONAL scroll-state cleanup for the
    // archived subtree union. For current-archived the prior
    // applyArchivedChatClear already wiped both fields (no-op here); for
    // NON-current archived ids this drops a stale pendingScrollRequest
    // (target in subtree) + parentReturnCheckpoints entries (key in
    // subtree) without touching chat content.
    val newChatCleaned = newChat.cleanScrollStateForSubtree(allArchivedSubtree)
    return state.copy(
        sessionList = newSessionList.copy(pendingQuestions = cleanedQuestions),
        chat = newChatCleaned,
        unread = newUnread,
    )
}
