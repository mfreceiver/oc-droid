package cn.vectory.ocdroid.ui

/**
 * §R-17 batch3d: Domain orchestration free functions. These are NOT the deleted
 * batch-2 AppState mirror helpers (aggregateFromSlices/syncSlicesFromAppState etc.).
 * They are coroutine-launch helpers called by the domain ViewModels and AppCore
 * orchestration extensions to perform async operations (load/refresh/mutate).
 * Future cleanup (batch3e+): may be inlined into individual VM private methods.
 */

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.toCacheEntry
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Persist a bounded session-metadata cache to [SettingsManager.sessionCache]
 * so the next cold start can reseed the session-list slice instantly (tabs,
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
    slices: SliceFlows,
    settingsManager: SettingsManager,
    onSelectSession: (String) -> Unit,
    onLoadSessionStatus: () -> Unit,
    onLoadMessages: (String) -> Unit,
    emit: EventEmitter = EventEmitter { }
) {

    scope.launch {
        val limit = MainViewModelTimings.sessionPageSize
        slices.sessionList.update {
            it.copy(
                loadedSessionLimit = limit,
                hasMoreSessions = true,
                isLoadingMoreSessions = false,
                isRefreshingSessions = true
            )
        }
        repository.getSessions(limit)
            .onSuccess { sessions ->
                // Capture cross-slice reads BEFORE the sessionList update:
                // mergeRefreshedSessionsPreservingLocalActivity needs
                // currentSessionId (chat slice) and openSessionIds (sessionList).
                // §R-17 batch2 step e final: slice-only reads (slices are the
                // sole authoritative store). Single capture for this
                // synchronous pre-write cluster.
                val currentSessionId = slices.chat.value.currentSessionId
                val currentSessions = slices.sessionList.value.sessions
                val currentOpenIds = slices.sessionList.value.openSessionIds
                val mergedSessions = mergeRefreshedSessionsPreservingLocalActivity(
                    sessions,
                    currentSessions,
                    currentSessionId,
                    currentOpenIds.toSet()
                )
                val newHasMore = mergedSessions.size >= limit
                slices.sessionList.update {
                    it.copy(
                        sessions = mergedSessions,
                        hasMoreSessions = newHasMore,
                        isLoadingMoreSessions = false,
                        isRefreshingSessions = false
                    )
                }
                // Persist a BOUNDED session-metadata cache so the next cold
                // start can reseed the session-list slice instantly (tabs/title/
                // workdir groups). Keep only entries the user actively cares
                // about: open tabs, the current session, and the current
                // workdir's root sessions (for the Sessions-screen grouping).
                // Server refresh already replaced same-id entries above via
                // mergeRefreshedSessionsPreservingLocalActivity; cached-only
                // entries (server didn't return them) survive for open tabs.
                persistSessionCache(
                    settingsManager = settingsManager,
                    sessions = mergedSessions,
                    openIds = currentOpenIds,
                    currentId = currentSessionId,
                    currentWorkdir = settingsManager.currentWorkdir
                )
                val refreshedSessions = mergedSessions
                when {
                    // Skip auto-select when the user is mid-draft (a workdir
                    // has been chosen but no session created yet): selecting a
                    // session would discard the draft's repository workdir and
                    // hijack the empty chat page the user is composing into.
                    currentSessionId == null && slices.composer.value.draftWorkdir == null && refreshedSessions.isNotEmpty() -> onSelectSession(refreshedSessions.first().id)
                    // currentId is set: keep it. Even when the session is
                    // temporarily absent from the refreshed list (e.g. just
                    // created, or a directory session), tolerate it — reload
                    // its messages but do NOT silently reselect first(). #10.
                    currentSessionId != null -> {
                        onLoadSessionStatus()
                        onLoadMessages(currentSessionId)
                    }
                    else -> {
                        slices.chat.update { c -> c.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap()) }
                    }
                }
            }
            .onFailure { error ->
                slices.sessionList.update {
                    it.copy(
                        isLoadingMoreSessions = false,
                        isRefreshingSessions = false
                    )
                }
                emit.emit(UiEvent.Error("Failed to load sessions: ${errorMessageOrFallback(error, "unknown error")}"))
            }
    }
}

internal fun launchLoadMoreSessions(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    onSelectSession: (String) -> Unit,
    emit: EventEmitter = EventEmitter { }
) {

    var nextLimit = 0
    var shouldLaunch = false
    // §R-17 batch2 step e final: slice-only reads. Single capture for this
    // synchronous pre-launch guard cluster.
    val currentHasMore = slices.sessionList.value.hasMoreSessions
    val currentIsLoadingMore = slices.sessionList.value.isLoadingMoreSessions
    val currentLoadedLimit = slices.sessionList.value.loadedSessionLimit
    if (!currentHasMore || currentIsLoadingMore) {
        // No-op (mirrors the legacy check-and-set return-current branch).
    } else {
        nextLimit = nextSessionFetchLimit(currentLoadedLimit)
        shouldLaunch = true
        slices.sessionList.update { sl -> sl.copy(isLoadingMoreSessions = true) }
    }
    if (!shouldLaunch) return
    scope.launch {
        repository.getSessions(nextLimit)
            .onSuccess { sessions ->
                // §R-17 batch2 step e final: slice-only reads. Single capture
                // for this cluster (if loadedLimit > nextLimit we write+return;
                // otherwise no write before the merge reads below).
                val loadedLimit = slices.sessionList.value.loadedSessionLimit
                if (loadedLimit > nextLimit) {
                    slices.sessionList.update { sl -> sl.copy(isLoadingMoreSessions = false) }
                    return@onSuccess
                }
                val currentSessionId = slices.chat.value.currentSessionId
                val currentSessions = slices.sessionList.value.sessions
                val currentOpenIds = slices.sessionList.value.openSessionIds
                val mergedSessions = mergeRefreshedSessionsPreservingLocalActivity(
                    sessions,
                    currentSessions,
                    currentSessionId,
                    currentOpenIds.toSet()
                )
                val newHasMore = mergedSessions.size >= nextLimit
                slices.sessionList.update {
                    it.copy(
                        sessions = mergedSessions,
                        loadedSessionLimit = nextLimit,
                        hasMoreSessions = newHasMore,
                        isLoadingMoreSessions = false
                    )
                }
                val currentId = currentSessionId
                val refreshedSessions = mergedSessions
                when {
                    // Mirror loadSessions' draft guard for consistency: never
                    // auto-select first() while the user is mid-draft. This
                    // branch is currently dead (loadMore is only triggered by
                    // user scroll, not initial load), but the guard keeps the
                    // two paths symmetric if the trigger ever changes.
                    currentId == null && slices.composer.value.draftWorkdir == null && refreshedSessions.isNotEmpty() -> onSelectSession(refreshedSessions.first().id)
                    // A non-null currentId is never silently replaced by
                    // refreshedSessions.first(): tolerate the session even when
                    // it is temporarily absent from the refreshed list. #10.
                    currentId != null -> Unit
                    else -> slices.chat.update { c -> c.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap()) }
                }
            }
            .onFailure { error ->
                slices.sessionList.update {
                    it.copy(
                        isLoadingMoreSessions = false
                    )
                }
                emit.emit(UiEvent.Error("Failed to load more sessions: ${errorMessageOrFallback(error, "unknown error")}"))
            }
    }
}

internal fun launchLoadSessionStatus(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows
) {

    scope.launch {
        repository.getSessionStatus()
            .onSuccess { statuses ->
                slices.sessionList.update { sl -> sl.copy(sessionStatuses = statuses) }
            }
            .onFailure { error ->
                reportNonFatalIssue("MainViewModel", "Failed to load session status", error)
            }
    }
}

/**
 * §R-17 batch3d: free-function extraction of the former AppCore.loadChildSessions
 * body. Both [SessionViewModel.loadChildSessions] and AppCore's effect-dispatch
 * handler call this so the body lives once (the VM is not a delegate shell —
 * it calls this domain helper directly, never `core.<method>()`).
 */
internal fun launchLoadChildSessions(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    sessionId: String,
    tag: String,
) {
    scope.launch {
        try {
            repository.getChildren(sessionId)
                .onSuccess { children ->
                    slices.sessionList.update { it.copy(childSessions = it.childSessions + (sessionId to children)) }
                }
                .onFailure { error ->
                    reportNonFatalIssue(tag, "Failed to load child sessions for $sessionId", error)
                }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            cn.vectory.ocdroid.util.DebugLog.w(tag, "loadChildSessions suppressed: ${e.message}")
        }
    }
}

/**
 * §R-17 batch3d: free-function extraction of the former AppCore.loadPendingQuestions
 * body. Merges the freshly-fetched [questions] with any locally-held ones the
 * server didn't return (matches the original semantics: byGet wins, existing
 * fills gaps). Called by [SessionViewModel.loadPendingQuestions] and by
 * AppCore's effect-dispatch handler.
 */
internal fun launchLoadPendingQuestions(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    tag: String,
) {
    scope.launch {
        repository.getPendingQuestions()
            .onSuccess { questions ->
                slices.sessionList.update { currentState ->
                    val byGet = questions.associateBy { it.id }
                    val existing = currentState.pendingQuestions.associateBy { it.id }
                    val merged = (byGet + existing.filterKeys { it !in byGet }).values.toList()
                    currentState.copy(pendingQuestions = merged)
                }
            }
            .onFailure { error -> android.util.Log.w(tag, "Failed to load questions: ${error.message}") }
    }
}

/**
 * §R-17 batch3d: free-function extraction of the former AppCore.loadPendingPermissions
 * body. Called by [SessionViewModel.loadPendingPermissions] and by AppCore's
 * effect-dispatch handler.
 */
internal fun launchLoadPendingPermissions(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    tag: String,
) {
    scope.launch {
        repository.getPendingPermissions()
            .onSuccess { permissions ->
                slices.sessionList.update { it.copy(pendingPermissions = permissions) }
            }
            .onFailure { error -> android.util.Log.w(tag, "Failed to load permissions: ${error.message}") }
    }
}

/**
 * §R-17 batch3d: free-function extraction of the former AppCore.loadAgents body.
 * Reconciles the current selectedAgentName against the freshly-fetched list
 * (falls back to "build" if the selected agent is no longer offered). Called
 * by AppCore's effect-dispatch handler.
 */
internal fun launchLoadAgents(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    settingsManager: cn.vectory.ocdroid.util.SettingsManager,
    tag: String,
) {
    scope.launch {
        repository.getAgents()
            .onSuccess { agents ->
                val currentAgent = slices.settings.value.selectedAgentName
                val validAgent = if (agents.none { it.name == currentAgent }) "build" else currentAgent
                slices.settings.update { it.copy(agents = agents, selectedAgentName = validAgent) }
                if (validAgent != currentAgent) settingsManager.selectedAgentName = validAgent
            }
            .onFailure { error -> reportNonFatalIssue(tag, "Failed to load agents", error) }
    }
}
