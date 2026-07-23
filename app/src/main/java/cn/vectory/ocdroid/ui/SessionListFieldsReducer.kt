package cn.vectory.ocdroid.ui

/**
 * Wave 2 lane L2: session-list-domain [reduce] branch bodies extracted as
 * pure helper functions. Covers T1c sessionList-only branches (sessions /
 * openSessionIds / pending* / sessionStatuses / etc.). Same package as
 * [AppAction] / [StoreState] — zero-import dispatch from [reduce].
 *
 * Each helper is a verbatim lift of the original `when`-arm body (comments +
 * early `return state` guards preserved). Behavior-preserving: no field
 * added / removed / reordered.
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

internal fun reduceOpenSessionIdsChanged(state: StoreState, action: AppAction.OpenSessionIdsChanged): StoreState = state.copy(
    sessionList = state.sessionList.copy(
        openSessionIds = action.openSessionIds,
    ),
)

internal fun reduceSessionArchivedLocal(state: StoreState, action: AppAction.SessionArchivedLocal): StoreState {
    val id = action.session.id
    return state.copy(
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
            openSessionIds = action.openSessionIds,
            pendingQuestions = action.pendingQuestions,
            activeSessionIds = state.sessionList.activeSessionIds - action.activeSessionIdsToRemove,
        ),
    )
}

internal fun reduceSessionDeletedLocal(state: StoreState, action: AppAction.SessionDeletedLocal): StoreState {
    val ids = action.removedIds
    return state.copy(
        sessionList = state.sessionList.copy(
            sessions = state.sessionList.sessions.filter { it.id !in ids },
            directorySessions = state.sessionList.directorySessions
                .mapValues { (_, list) -> list.filter { it.id !in ids } }
                .filterValues { it.isNotEmpty() },
            pendingQuestions = state.sessionList.pendingQuestions.filter { it.sessionId !in ids },
            activeSessionIds = state.sessionList.activeSessionIds - ids,
            sessionErrorsById = state.sessionList.sessionErrorsById.filterKeys { it !in ids },
        ),
    )
}

internal fun reduceSessionStatusPatched(state: StoreState, action: AppAction.SessionStatusPatched): StoreState = state.copy(
    sessionList = state.sessionList.copy(
        sessions = bumpSessionUpdated(
            state.sessionList.sessions,
            action.sessionId,
            action.updatedTimestamp,
        ),
        sessionStatuses = state.sessionList.sessionStatuses + (action.sessionId to action.status),
    ),
)

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
        state.copy(
            sessionList = state.sessionList.copy(
                childSessions = state.sessionList.childSessions + action.childSessionsDelta,
                completeRootIds = state.sessionList.completeRootIds + action.completeRootIdsDelta,
                sessionStatuses = action.sessionStatuses,
            ),
        )
    }
}
