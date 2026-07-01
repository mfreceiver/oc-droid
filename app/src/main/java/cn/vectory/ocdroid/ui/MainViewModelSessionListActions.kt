package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.toCacheEntry
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Persist a bounded session-metadata cache to [SettingsManager.sessionCache]
 * so the next cold start can reseed [AppState.sessions] instantly (tabs,
 * title, workdir groups). Keeps only entries the user actively cares about:
 * open tabs ([openIds]), the current session ([currentId]) and the current
 * workdir's root sessions ([currentWorkdir]).
 *
 * Fix #5: previously written only inside [launchLoadSessions].onSuccess,
 * which never re-runs on a plain `selectSession` (no message sent). After
 * opening an existing conversation and restarting, the tab vanished because
 * its Session metadata was missing from the cache. This helper is now also
 * called from [MainViewModel.selectSession] and [MainViewModel.sendMessage]
 * so opening/creating a conversation persists its metadata immediately.
 *
 * The filter matches the original inline logic: id in openIds, or id ==
 * currentId, or (root session whose directory == currentWorkdir). Writes via
 * the same plain prefs write as before (`.apply()` — async to disk, sync to
 * memory); callers run on the main/coroutine context exactly like
 * [launchLoadSessions] did, so no extra threading is required.
 */
internal fun persistSessionCache(
    settingsManager: SettingsManager,
    sessions: List<Session>,
    openIds: List<String>,
    currentId: String?,
    currentWorkdir: String?
) {
    val openIdSet = openIds.toSet()
    val cache = sessions
        .asSequence()
        .filter { s ->
            s.id in openIdSet ||
                s.id == currentId ||
                (currentWorkdir != null && s.parentId == null && s.directory == currentWorkdir)
        }
        .map { it.toCacheEntry() }
        .toList()
    settingsManager.sessionCache = cache
}

internal fun launchLoadSessions(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    settingsManager: SettingsManager,
    onSelectSession: (String) -> Unit,
    onLoadSessionStatus: () -> Unit,
    onLoadMessages: (String) -> Unit
, slices: SliceFlows? = null) {

    scope.launch {
        val limit = MainViewModelTimings.sessionPageSize
        state.updateAndSync(slices) {
            it.copy(
                loadedSessionLimit = limit,
                hasMoreSessions = true,
                isLoadingMoreSessions = false,
                isRefreshingSessions = true
            )
        }
        repository.getSessions(limit)
            .onSuccess { sessions ->
                state.updateAndSync(slices) {
                    val mergedSessions = mergeRefreshedSessionsPreservingLocalActivity(
                        sessions,
                        it.sessions,
                        it.currentSessionId,
                        it.openSessionIds.toSet()
                    )
                    it.copy(
                        sessions = mergedSessions,
                        hasMoreSessions = mergedSessions.size >= limit,
                        isLoadingMoreSessions = false,
                        isRefreshingSessions = false
                    )
                }
                // Persist a BOUNDED session-metadata cache so the next cold
                // start can reseed AppState.sessions instantly (tabs/title/
                // workdir groups). Keep only entries the user actively cares
                // about: open tabs, the current session, and the current
                // workdir's root sessions (for the Sessions-screen grouping).
                // Server refresh already replaced same-id entries above via
                // mergeRefreshedSessionsPreservingLocalActivity; cached-only
                // entries (server didn't return them) survive for open tabs.
                run {
                    val postState = state.value
                    persistSessionCache(
                        settingsManager = settingsManager,
                        sessions = postState.sessions,
                        openIds = postState.openSessionIds,
                        currentId = postState.currentSessionId,
                        currentWorkdir = settingsManager.currentWorkdir
                    )
                }
                val currentId = state.value.currentSessionId
                val refreshedSessions = state.value.sessions
                when {
                    // Skip auto-select when the user is mid-draft (a workdir
                    // has been chosen but no session created yet): selecting a
                    // session would discard the draft's repository workdir and
                    // hijack the empty chat page the user is composing into.
                    currentId == null && state.value.draftWorkdir == null && refreshedSessions.isNotEmpty() -> onSelectSession(refreshedSessions.first().id)
                    // currentId is set: keep it. Even when the session is
                    // temporarily absent from the refreshed list (e.g. just
                    // created, or a directory session), tolerate it — reload
                    // its messages but do NOT silently reselect first(). #10.
                    currentId != null -> {
                        onLoadSessionStatus()
                        onLoadMessages(currentId)
                    }
                    else -> {
                        state.updateAndSync(slices) { it.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap()) }
                    }
                }
            }
            .onFailure { error ->
                state.updateAndSync(slices) {
                    it.copy(
                        isLoadingMoreSessions = false,
                        isRefreshingSessions = false,
                        error = "Failed to load sessions: ${errorMessageOrFallback(error, "unknown error")}"
                    )
                }
            }
    }
}

internal fun launchLoadMoreSessions(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    onSelectSession: (String) -> Unit
, slices: SliceFlows? = null) {

    var nextLimit = 0
    var shouldLaunch = false
    state.updateAndSync(slices) { current ->
        if (!current.hasMoreSessions || current.isLoadingMoreSessions) {
            current
        } else {
            nextLimit = nextSessionFetchLimit(current.loadedSessionLimit)
            shouldLaunch = true
            current.copy(isLoadingMoreSessions = true)
        }
    }
    if (!shouldLaunch) return
    scope.launch {
        repository.getSessions(nextLimit)
            .onSuccess { sessions ->
                if (state.value.loadedSessionLimit > nextLimit) {
                    state.updateAndSync(slices) { it.copy(isLoadingMoreSessions = false) }
                    return@onSuccess
                }
                state.updateAndSync(slices) {
                    val mergedSessions = mergeRefreshedSessionsPreservingLocalActivity(
                        sessions,
                        it.sessions,
                        it.currentSessionId,
                        it.openSessionIds.toSet()
                    )
                    it.copy(
                        sessions = mergedSessions,
                        loadedSessionLimit = nextLimit,
                        hasMoreSessions = mergedSessions.size >= nextLimit,
                        isLoadingMoreSessions = false
                    )
                }
                val currentId = state.value.currentSessionId
                val refreshedSessions = state.value.sessions
                when {
                    // Mirror loadSessions' draft guard for consistency: never
                    // auto-select first() while the user is mid-draft. This
                    // branch is currently dead (loadMore is only triggered by
                    // user scroll, not initial load), but the guard keeps the
                    // two paths symmetric if the trigger ever changes.
                    currentId == null && state.value.draftWorkdir == null && refreshedSessions.isNotEmpty() -> onSelectSession(refreshedSessions.first().id)
                    // A non-null currentId is never silently replaced by
                    // refreshedSessions.first(): tolerate the session even when
                    // it is temporarily absent from the refreshed list. #10.
                    currentId != null -> Unit
                    else -> state.updateAndSync(slices) { it.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap()) }
                }
            }
            .onFailure { error ->
                state.updateAndSync(slices) {
                    it.copy(
                        isLoadingMoreSessions = false,
                        error = "Failed to load more sessions: ${errorMessageOrFallback(error, "unknown error")}"
                    )
                }
            }
    }
}

internal fun launchLoadSessionStatus(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>
, slices: SliceFlows? = null) {

    scope.launch {
        repository.getSessionStatus()
            .onSuccess { statuses ->
                state.updateAndSync(slices) { it.copy(sessionStatuses = statuses) }
            }
            .onFailure { error ->
                reportNonFatalIssue("MainViewModel", "Failed to load session status", error)
            }
    }
}

