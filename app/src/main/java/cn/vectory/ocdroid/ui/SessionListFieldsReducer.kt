package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.state.AuthorityOp
import cn.vectory.ocdroid.data.state.ScopeKey
import cn.vectory.ocdroid.ui.controller.subtreeIds

/**
 * Wave 2 lane L2: session-list-domain [reduce] branch bodies extracted as
 * pure helper functions. Covers T1c sessionList-only branches (sessions /
 * pending* / sessionStatuses / etc.). Same package as [AppAction] /
 * [StoreState] — zero-import dispatch from [reduce].
 *
 * §B4: open-tabs-list / OpenTabsChanged(removed) removed (list-detail D9).
 * §P0-A rev-gpt #8: archive/delete/purge route authority + sessionStatuses
 * through [reduceAuthority] (the SOLE writer of sessionStatuses). These
 * reducers layer their OTHER sessionList fields on top WITHOUT touching
 * sessionStatuses directly.
 */

// ── T1c sessionList ownership reduce ───────────────────────────────────

internal fun reduceSessionUpserted(state: StoreState, action: AppAction.SessionUpserted): StoreState = state.copy(
    sessionList = state.sessionList.copy(
        sessions = upsertSession(state.sessionList.sessions, action.session),
    ),
)

internal fun reduceSessionCreatedLocal(state: StoreState, action: AppAction.SessionCreatedLocal): StoreState = state.copy(
    sessionList = state.sessionList.copy(
        sessions = upsertSession(state.sessionList.sessions, action.session),
        pendingCreateIds = state.sessionList.pendingCreateIds + action.session.id,
        pendingCreatedAt = state.sessionList.pendingCreatedAt + (action.session.id to action.registeredAt),
    ),
)

internal fun reduceSessionArchivedLocal(state: StoreState, action: AppAction.SessionArchivedLocal): StoreState {
    val id = action.session.id
    // §P0-A rev-gpt #7 (subtree prune): archive can be a subtree operation —
    // prune the WHOLE subtree (root + all descendants) from authority.bySid,
    // not just the root id. Aligns with impl-E/F subtree cleanup.
    val subtree = subtreeIds(
        id,
        state.sessionList.sessions,
        state.sessionList.directorySessions,
        state.sessionList.childSessions,
    )
    // §P0-A rev-gpt #8 (sole writer): route the authority prune through
    // reduceAuthority — it is the SOLE writer of sessionStatuses (the
    // projection comes from authority, not a direct write). Layer the other
    // sessionList fields on top WITHOUT touching sessionStatuses.
    // §P0-A r2: derive the real authority scope from StoreState (host profile
    // serverGroupFp + liveEndpointFp), not empty ScopeKey("","") — the prune
    // boundary must reflect the active connection identity.
    val withAuth = reduceAuthority(
        state,
        AuthorityOp.PruneSessions(
            subtree,
            state.resolveScopeKey(),
        ),
    )
    return withAuth.copy(
        sessionList = withAuth.sessionList.copy(
            sessions = state.sessionList.sessions.map {
                if (it.id == id) action.session else it
            },
            directorySessions = state.sessionList.directorySessions.mapValues { (_, list) ->
                list.map { if (it.id == id) action.session else it }
            },
            childSessions = state.sessionList.childSessions.mapValues { (_, list) ->
                list.map { if (it.id == id) action.session else it }
            },
            // sessionStatuses NOT set here — comes from withAuth (reduceAuthority).
            pendingQuestions = action.pendingQuestions,
            activeSessionIds = state.sessionList.activeSessionIds - action.activeSessionIdsToRemove,
            sessionErrorsById = state.sessionList.sessionErrorsById.filterKeys { it != id },
            abortPendingSessionIds = withAuth.sessionList.abortPendingSessionIds.filterKeys { it !in action.activeSessionIdsToRemove },
        ),
        chat = state.chat.copy(
            pendingErrorReattach = state.chat.pendingErrorReattach.filterKeys { it != id },
            pendingErrorCheck = state.chat.pendingErrorCheck - id,
        ),
    )
}

internal fun reduceSessionDeletedLocal(state: StoreState, action: AppAction.SessionDeletedLocal): StoreState {
    val ids = action.removedIds
    // §P0-A rev-gpt #7 (subtree prune): delete can remove subtrees — for each
    // removed id, compute its subtree and union all subtree ids. This catches
    // descendants that might still have authority entries even if the parent
    // was the only id in removedIds.
    val allSubtreeIds = ids.flatMap { rootId ->
        subtreeIds(
            rootId,
            state.sessionList.sessions,
            state.sessionList.directorySessions,
            state.sessionList.childSessions,
        )
    }.toSet()
    // §P0-A rev-gpt #8 (sole writer): route through reduceAuthority — it prunes
    // authority.bySid + recomputes sessionStatuses (the SOLE writer).
    // §P0-A r2: derive the real authority scope from StoreState (host profile
    // serverGroupFp + liveEndpointFp), not empty ScopeKey("","") — the prune
    // boundary must reflect the active connection identity.
    val withAuth = reduceAuthority(
        state,
        AuthorityOp.PruneSessions(
            allSubtreeIds,
            state.resolveScopeKey(),
        ),
    )
    return withAuth.copy(
        sessionList = withAuth.sessionList.copy(
            sessions = state.sessionList.sessions.filter { it.id !in ids },
            directorySessions = state.sessionList.directorySessions
                .mapValues { (_, list) -> list.filter { it.id !in ids } }
                .filterValues { it.isNotEmpty() },
            // §slim-storm P2: purge the child-tree map + completeness so an EvictSession
            // (or any subtree delete) actually shrinks the snapshot the status poller
            // iterates — without this the storm never self-terminates. Mirrors the
            // directorySessions treatment (drop removed parents as keys, drop removed
            // children, drop now-empty lists).
            childSessions = state.sessionList.childSessions
                .filterKeys { it !in ids }
                .mapValues { (_, list) -> list.filter { it.id !in ids } }
                .filterValues { it.isNotEmpty() },
            completeRootIds = state.sessionList.completeRootIds - ids,
            // sessionStatuses NOT set here — comes from withAuth (reduceAuthority).
            // §slim-storm P2: withAuth runs reduceAuthority which prunes authority.bySid
            // via AuthorityOp.PruneSessions(allSubtreeIds, ...), so sessionStatuses is
            // already correctly purged for all subtree ids. No explicit filter needed.
            pendingQuestions = state.sessionList.pendingQuestions.filter { it.sessionId !in ids },
            activeSessionIds = state.sessionList.activeSessionIds - ids,
            sessionErrorsById = state.sessionList.sessionErrorsById.filterKeys { it !in ids },
            abortPendingSessionIds = withAuth.sessionList.abortPendingSessionIds.filterKeys { it !in ids },
        ),
        chat = state.chat.copy(
            pendingErrorReattach = state.chat.pendingErrorReattach.filterKeys { it !in ids },
            pendingErrorCheck = state.chat.pendingErrorCheck - ids,
        ),
    )
}

internal fun reduceSessionsRefreshedLocal(state: StoreState, action: AppAction.SessionsRefreshedLocal): StoreState = state.copy(
    sessionList = state.sessionList.copy(
        sessions = action.sessions,
        hasMoreSessions = action.hasMoreSessions,
        isLoadingMoreSessions = false,
        isRefreshingSessions = false,
        pendingCreateIds = action.pendingCreateIds,
        pendingCreatedAt = action.pendingCreatedAt,
        completeRootIds = emptySet(),
        completenessEpoch = state.sessionList.completenessEpoch + 1L,
        hasCompletedInitialLoad = true,
    ),
)

internal fun reduceSessionsPageAppended(state: StoreState, action: AppAction.SessionsPageAppended): StoreState = state.copy(
    sessionList = state.sessionList.copy(
        sessions = action.sessions,
        loadedSessionLimit = action.loadedSessionLimit,
        hasMoreSessions = action.hasMoreSessions,
        isLoadingMoreSessions = false,
        pendingCreateIds = action.pendingCreateIds,
        pendingCreatedAt = action.pendingCreatedAt,
        completeRootIds = emptySet(),
        completenessEpoch = state.sessionList.completenessEpoch + 1L,
    ),
)

internal fun reduceSessionTreeHydrated(state: StoreState, action: AppAction.SessionTreeHydrated): StoreState {
    return if (state.sessionList.completenessEpoch != action.epochAtStart) {
        state // stale hydration → full no-op
    } else {
        // §4a.2: apply the authority op (status) via the PURE reducer in the SAME
        // state.copy as the tree delta (single CAS, M6/B1 atomic). reduceAuthority
        // is pure → idempotent under CAS retry. null op → status untouched.
        val withStatus = if (action.statusOp != null) {
            reduceAuthority(state, action.statusOp)
        } else {
            state
        }
        withStatus.copy(
            sessionList = withStatus.sessionList.copy(
                childSessions = withStatus.sessionList.childSessions + action.childSessionsDelta,
                completeRootIds = withStatus.sessionList.completeRootIds + action.completeRootIdsDelta,
            ),
        )
    }
}
