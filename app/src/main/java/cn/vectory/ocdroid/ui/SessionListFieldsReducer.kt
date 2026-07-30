package cn.vectory.ocdroid.ui

/**
 * Wave 2 lane L2: session-list-domain [reduce] branch bodies extracted as
 * pure helper functions. Covers T1c sessionList-only branches (sessions /
 * pending* / sessionStatuses / etc.). Same package as [AppAction] /
 * [StoreState] — zero-import dispatch from [reduce].
 *
 * §B4: open-tabs-list / OpenTabsChanged(removed) removed (list-detail D9).
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
    // §B5 / §4c.2: prune the archived session's id from authority.bySid so its
    // status (and thus the sessionStatuses projection) cannot survive the
    // archive. No-op while authority is empty (additive); consistent with the
    // sessions/childSessions cleanup below once status flows through authority.
    val prunedAuth = if (id in state.authority.bySid) {
        state.authority.copy(bySid = state.authority.bySid - id)
    } else {
        state.authority
    }
    val nextSessionStatuses = if (prunedAuth === state.authority) {
        state.sessionList.sessionStatuses
    } else {
        projectSessionStatuses(prunedAuth)
    }
    return state.copy(
        authority = prunedAuth,
        sessionList = state.sessionList.copy(
            sessions = state.sessionList.sessions.map {
                if (it.id == id) action.session else it
            },
            directorySessions = state.sessionList.directorySessions.mapValues { (_, list) ->
                list.map { if (it.id == id) action.session else it }
            },
            childSessions = state.sessionList.childSessions.mapValues { (_, list) ->
                list.map { if (it.id == id) action.session else it }
            },
            sessionStatuses = nextSessionStatuses,
            pendingQuestions = action.pendingQuestions,
            activeSessionIds = state.sessionList.activeSessionIds - action.activeSessionIdsToRemove,
            // §P0-E(a) R9 fix: archive must also clear the archived session's
            // error banner (mirroring reduceSessionDeletedLocal's cleanup).
            sessionErrorsById = state.sessionList.sessionErrorsById.filterKeys { it != id },
            // §P0-F 阻断2: 对齐 caller 携带的 subtree（activeSessionIdsToRemove =
            // subtree when archiving）；SSE SessionArchived / SessionDeletedLocal 同模式。
            abortPendingSessionIds = state.sessionList.abortPendingSessionIds.filterKeys { it !in action.activeSessionIdsToRemove },
        ),
        // §P0-E(b)/(c): clean pending maps for the archived session (cross-slice cleanup).
        chat = state.chat.copy(
            pendingErrorReattach = state.chat.pendingErrorReattach.filterKeys { it != id },
            pendingErrorCheck = state.chat.pendingErrorCheck - id,
        ),
    )
}

internal fun reduceSessionDeletedLocal(state: StoreState, action: AppAction.SessionDeletedLocal): StoreState {
    val ids = action.removedIds
    // §B5 / §4c.2: prune deleted ids from authority.bySid (and recompute the
    // projection so sessionStatuses stays consistent in the same CAS). No-op
    // while authority is empty; correct once status flows through authority.
    val prunedAuth = if (ids.any { it in state.authority.bySid }) {
        state.authority.copy(bySid = state.authority.bySid.filterKeys { it !in ids })
    } else {
        state.authority
    }
    val nextSessionStatuses = if (prunedAuth === state.authority) {
        state.sessionList.sessionStatuses
    } else {
        projectSessionStatuses(prunedAuth)
    }
    return state.copy(
        authority = prunedAuth,
        sessionList = state.sessionList.copy(
            sessions = state.sessionList.sessions.filter { it.id !in ids },
            directorySessions = state.sessionList.directorySessions
                .mapValues { (_, list) -> list.filter { it.id !in ids } }
                .filterValues { it.isNotEmpty() },
            sessionStatuses = nextSessionStatuses,
            pendingQuestions = state.sessionList.pendingQuestions.filter { it.sessionId !in ids },
            activeSessionIds = state.sessionList.activeSessionIds - ids,
            sessionErrorsById = state.sessionList.sessionErrorsById.filterKeys { it !in ids },
            // §P0-F 阻断5: local delete must drop abort-pending for removed sessions.
            abortPendingSessionIds = state.sessionList.abortPendingSessionIds.filterKeys { it !in ids },
        ),
        // §P0-E(b)/(c): clean pending maps for deleted sessions (cross-slice cleanup).
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
