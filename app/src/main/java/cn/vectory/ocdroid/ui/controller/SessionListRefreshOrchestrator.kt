package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.RevertCutoff
import cn.vectory.ocdroid.data.model.RevertCutoffState
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.toCacheEntry
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.EventEmitter
import cn.vectory.ocdroid.ui.MainViewModelTimings
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.errorMessageOrFallback
import cn.vectory.ocdroid.ui.mergeRefreshedSessionsPreservingLocalActivity
import cn.vectory.ocdroid.ui.nextSessionFetchLimit
import cn.vectory.ocdroid.ui.reportNonFatalIssue
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.WorkdirPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * §single-flight/epoch for the session-LIST load (launchLoadSessions).
 * Owns the sole [sessionListLoadEpoch] so concurrent requests are fenced
 * (see FIX-D / task 1). All public methods are per-invocation — receive
 * repository, slices, settings, callbacks, etc. via parameters; no fields
 * except stable infra (cacheWriter, clockMs).
 */
internal class SessionListRefreshOrchestrator(
    private val cacheWriter: SessionMetadataCacheWriter,
    private val clockMs: () -> Long,
) {
    /** FIX-D (gpter #2, review-blocker): single-flight epoch. Intentionally separate from P7's StatusPollOrchestrator status epoch. */
    private val sessionListLoadEpoch = AtomicLong(0)

    // ── Full refresh (launchLoadSessions) ──────────────────────────────────

    internal fun launchLoadSessions(
        scope: CoroutineScope,
        repository: OpenCodeRepository,
        slices: SliceFlows,
        settingsManager: SettingsManager,
        onSelectSession: (String) -> Unit,
        onLoadSessionStatus: () -> Unit,
        onLoadMessages: (String) -> Unit,
        emit: EventEmitter,
        expectedServerGroupFp: String? = null,
        currentServerGroupFp: (() -> String)? = null,
        onArchivedSessionsDetected: ((mergedSessions: List<Session>, newOpenIds: List<String>, hasMoreSessions: Boolean, confirmedServerIds: Set<String>, sweepNow: Long) -> Unit)? = null,
    ) {
        scope.launch {
            fun staleHostAfterSuspend(): Boolean = expectedServerGroupFp != null &&
                currentServerGroupFp != null &&
                expectedServerGroupFp != currentServerGroupFp()

            // FIX-D (gpter #2): capture this request's epoch.
            val myEpoch = sessionListLoadEpoch.incrementAndGet()

            val limit = MainViewModelTimings.sessionFullLoadLimit
            slices.mutateSessionList {
                it.copy(
                    loadedSessionLimit = limit,
                    hasMoreSessions = true,
                    isLoadingMoreSessions = false,
                    isRefreshingSessions = true,
                )
            }
            repository.getSessions(limit)
                .onSuccess { sessions ->
                    if (myEpoch != sessionListLoadEpoch.get()) {
                        DebugLog.d("Sync", "launchLoadSessions: epoch $myEpoch superseded, discarding stale snapshot")
                        return@onSuccess
                    }
                    if (staleHostAfterSuspend()) {
                        return@onSuccess
                    }
                    val currentSessionId = slices.chat.value.currentSessionId
                    val currentSessionList = slices.sessionList.value
                    val currentSessions = currentSessionList.sessions
                    val currentOpenIds = currentSessionList.openSessionIds
                    val isInitialColdStart = !currentSessionList.hasCompletedInitialLoad
                    val currentPendingCreateIds = currentSessionList.pendingCreateIds
                    val currentPendingCreatedAt = currentSessionList.pendingCreatedAt
                    val mergedSessions = mergeRefreshedSessionsPreservingLocalActivity(
                        sessions,
                        currentSessions,
                        currentSessionId,
                        currentOpenIds.toSet(),
                        pendingCreateIds = currentPendingCreateIds,
                    )
                    val newHasMore = false
                    if (staleHostAfterSuspend()) {
                        return@onSuccess
                    }
                    if (staleHostAfterSuspend()) return@onSuccess
                    val refreshedSessions = mergedSessions
                    val sweepNow = clockMs()
                    val serverIds = sessions.mapTo(mutableSetOf()) { it.id }
                    val (sweptPendingCreateIds, sweptPendingCreatedAt) = sweepPendingCreateIds(
                        currentPendingCreateIds = currentPendingCreateIds,
                        currentPendingCreatedAt = currentPendingCreatedAt,
                        serverIds = serverIds,
                        nowMs = sweepNow,
                    )
                    val archivedIds = mergedSessions
                        .filter { it.isArchived }
                        .map { it.id }
                        .toSet()
                    val newOpenIds = currentOpenIds.filter { it !in archivedIds }
                    val currentIsArchived = currentSessionId != null && currentSessionId in archivedIds
                    val anyArchivedOpen = archivedIds.isNotEmpty() &&
                        (currentIsArchived || currentOpenIds.any { it in archivedIds })
                    if (anyArchivedOpen && onArchivedSessionsDetected != null) {
                        onArchivedSessionsDetected?.invoke(mergedSessions, newOpenIds, newHasMore, serverIds, sweepNow)
                        cacheWriter.persistSessionCache(
                            settingsManager = settingsManager,
                            sessions = mergedSessions,
                            openIds = newOpenIds,
                            currentId = if (currentIsArchived) null else currentSessionId,
                            currentWorkdir = settingsManager.currentWorkdir,
                            revertCutoffs = slices.chat.value.revertCutoffs,
                        )
                        return@onSuccess
                    }
                    slices.store.dispatch(
                        AppAction.SessionsRefreshedLocal(
                            sessions = mergedSessions,
                            hasMoreSessions = newHasMore,
                            pendingCreateIds = sweptPendingCreateIds,
                            pendingCreatedAt = sweptPendingCreatedAt,
                        )
                    )
                    cacheWriter.persistSessionCache(
                        settingsManager = settingsManager,
                        sessions = mergedSessions,
                        openIds = newOpenIds,
                        currentId = currentSessionId,
                        currentWorkdir = settingsManager.currentWorkdir,
                        revertCutoffs = slices.chat.value.revertCutoffs,
                    )
                    val discoveryFp = currentServerGroupFp?.invoke()
                    if (!discoveryFp.isNullOrEmpty()) {
                        val knownWorkdirs = settingsManager
                            .getRecentWorkdirs(discoveryFp)
                            .map { WorkdirPaths.normalize(it) }
                            .toSet()
                        val currentWorkdirNorm = settingsManager.currentWorkdir
                            ?.let { WorkdirPaths.normalize(it) }
                        mergedSessions
                            .mapNotNull { it.directory.takeIf { d -> d.isNotBlank() } }
                            .map { WorkdirPaths.normalize(it) to it }
                            .distinctBy { it.first }
                            .filter { (norm, _) ->
                                norm.isNotEmpty() &&
                                    norm !in knownWorkdirs &&
                                    norm != currentWorkdirNorm
                            }
                            .forEach { (_, rawWorkdir) ->
                                settingsManager.addRecentWorkdir(discoveryFp, rawWorkdir)
                                scope.launch {
                                    repository.getSessionsForDirectory(rawWorkdir)
                                        .onSuccess { dirSessions ->
                                            if (staleHostAfterSuspend()) return@launch
                                            if (currentServerGroupFp?.invoke() != discoveryFp) return@launch
                                            slices.mutateSessionList { slice ->
                                                slice.copy(
                                                    directorySessions = slice.directorySessions + (rawWorkdir to dirSessions)
                                                )
                                            }
                                        }
                                        .onFailure { /* best-effort */ }
                                }
                            }
                    }
                    if (staleHostAfterSuspend()) return@onSuccess
                    when (val decision = decideAutoSelectSession(
                        currentSessionId = currentSessionId,
                        draftWorkdir = slices.composer.value.draftWorkdir,
                        isInitialColdStart = isInitialColdStart,
                        currentOpenIds = currentOpenIds,
                        refreshedSessions = refreshedSessions,
                    )) {
                        is AutoSelectDecision.ClearChat,
                        is AutoSelectDecision.ClearChatResidual -> {
                            slices.store.dispatch(AppAction.ChatCleared)
                        }
                        is AutoSelectDecision.SelectRestored -> {
                            onSelectSession(decision.sessionId)
                        }
                        is AutoSelectDecision.KeepCurrent -> {
                            onLoadSessionStatus()
                            onLoadMessages(decision.sessionId)
                        }
                    }
                }
                .onFailure { error ->
                    if (myEpoch != sessionListLoadEpoch.get() || staleHostAfterSuspend()) {
                        DebugLog.d("Sync", "launchLoadSessions: stale failure (epoch/host changed), discarding")
                        return@onFailure
                    }
                    slices.mutateSessionList {
                        it.copy(
                            isLoadingMoreSessions = false,
                            isRefreshingSessions = false,
                        )
                    }
                    emit.emit(UiEvent.Error(R.string.error_load_sessions_failed, listOf(errorMessageOrFallback(error, "unknown error"))))
                }
        }
    }

    // ── Load more (launchLoadMoreSessions) ──────────────────────────────

    internal fun launchLoadMoreSessions(
        scope: CoroutineScope,
        repository: OpenCodeRepository,
        slices: SliceFlows,
        onSelectSession: (String) -> Unit,
        emit: EventEmitter,
    ) {
        var nextLimit = 0
        var shouldLaunch = false
        val currentHasMore = slices.sessionList.value.hasMoreSessions
        val currentIsLoadingMore = slices.sessionList.value.isLoadingMoreSessions
        val currentLoadedLimit = slices.sessionList.value.loadedSessionLimit
        if (!currentHasMore || currentIsLoadingMore) {
            // No-op
        } else {
            nextLimit = nextSessionFetchLimit(currentLoadedLimit)
            shouldLaunch = true
            slices.mutateSessionList { sl -> sl.copy(isLoadingMoreSessions = true) }
        }
        if (!shouldLaunch) return
        scope.launch {
            repository.getSessions(nextLimit)
                .onSuccess { sessions ->
                    val loadedLimit = slices.sessionList.value.loadedSessionLimit
                    if (loadedLimit > nextLimit) {
                        slices.mutateSessionList { sl -> sl.copy(isLoadingMoreSessions = false) }
                        return@onSuccess
                    }
                    val currentSessionId = slices.chat.value.currentSessionId
                    val currentSessionList = slices.sessionList.value
                    val currentSessions = currentSessionList.sessions
                    val currentOpenIds = currentSessionList.openSessionIds
                    val isInitialColdStart = !currentSessionList.hasCompletedInitialLoad
                    val currentPendingCreateIds = currentSessionList.pendingCreateIds
                    val currentPendingCreatedAt = currentSessionList.pendingCreatedAt
                    val mergedSessions = mergeRefreshedSessionsPreservingLocalActivity(
                        sessions,
                        currentSessions,
                        currentSessionId,
                        currentOpenIds.toSet(),
                        pendingCreateIds = currentPendingCreateIds,
                    )
                    val sweepNow = clockMs()
                    val serverIds = sessions.mapTo(mutableSetOf()) { it.id }
                    val (sweptPendingCreateIds, sweptPendingCreatedAt) = sweepPendingCreateIds(
                        currentPendingCreateIds = currentPendingCreateIds,
                        currentPendingCreatedAt = currentPendingCreatedAt,
                        serverIds = serverIds,
                        nowMs = sweepNow,
                    )
                    val newHasMore = mergedSessions.size >= nextLimit
                    slices.store.dispatch(
                        AppAction.SessionsPageAppended(
                            sessions = mergedSessions,
                            loadedSessionLimit = nextLimit,
                            hasMoreSessions = newHasMore,
                            pendingCreateIds = sweptPendingCreateIds,
                            pendingCreatedAt = sweptPendingCreatedAt,
                        )
                    )
                    val currentId = currentSessionId
                    val refreshedSessions = mergedSessions
                    when (val decision = decideAutoSelectSession(
                        currentSessionId = currentId,
                        draftWorkdir = slices.composer.value.draftWorkdir,
                        isInitialColdStart = isInitialColdStart,
                        currentOpenIds = currentOpenIds,
                        refreshedSessions = refreshedSessions,
                    )) {
                        is AutoSelectDecision.ClearChat,
                        is AutoSelectDecision.ClearChatResidual -> {
                            slices.store.dispatch(AppAction.ChatCleared)
                        }
                        is AutoSelectDecision.SelectRestored -> {
                            onSelectSession(decision.sessionId)
                        }
                        is AutoSelectDecision.KeepCurrent -> {
                            Unit
                        }
                    }
                }
                .onFailure { error ->
                    slices.mutateSessionList {
                        it.copy(
                            isLoadingMoreSessions = false
                        )
                    }
                    emit.emit(UiEvent.Error(R.string.error_load_more_sessions_failed, listOf(errorMessageOrFallback(error, "unknown error"))))
                }
        }
    }

    // ── Session diff (launchLoadSessionDiff) ────────────────────────────

    internal fun launchLoadSessionDiff(
        scope: CoroutineScope,
        repository: OpenCodeRepository,
        slices: SliceFlows,
        sessionId: String,
    ) {
        scope.launch {
            repository.getSessionDiff(sessionId)
                .onSuccess { diffs ->
                    slices.mutateSessionList { it.applySessionDiffIfAbsent(sessionId, diffs).first }
                }
                .onFailure { error ->
                    reportNonFatalIssue("MainViewModel", "Failed to load session diff", error)
                }
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    /**
     * Sweep pending-create IDs: remove any that the server just confirmed (serverIds),
     * and also drop any whose local creation timestamp is too old (beyond timeoutMs).
     * Returns the surviving IDs + their filtered createdAt map.
     */
    private fun sweepPendingCreateIds(
        currentPendingCreateIds: Set<String>,
        currentPendingCreatedAt: Map<String, Long>,
        serverIds: Set<String>,
        nowMs: Long,
        timeoutMs: Long = MainViewModelTimings.pendingCreateTimeoutMs,
    ): Pair<Set<String>, Map<String, Long>> {
        val sweptIds = currentPendingCreateIds
            .minus(serverIds)
            .filter { pendingId ->
                val registeredAt = currentPendingCreatedAt[pendingId]
                registeredAt != null &&
                    nowMs - registeredAt <= timeoutMs
            }
            .toSet()
        val sweptCreatedAt = currentPendingCreatedAt
            .filterKeys { it in sweptIds }
        return sweptIds to sweptCreatedAt
    }

    /**
     * Decision helper for auto-select logic. Returns a sealed outcome describing
     * which auto-select policy branch to follow. The caller maps each outcome to
     * the appropriate action.
     *
     * Never invents [sessions.first()]; restore-only from openSessionIds on cold
     * start. The draftWorkdir gate prevents hijacking the draft UI.
     */
    private fun decideAutoSelectSession(
        currentSessionId: String?,
        draftWorkdir: String?,
        isInitialColdStart: Boolean,
        currentOpenIds: List<String>,
        refreshedSessions: List<Session>,
    ): AutoSelectDecision {
        return when {
            currentSessionId == null && draftWorkdir == null && currentOpenIds.isEmpty() ->
                AutoSelectDecision.ClearChat
            currentSessionId == null && draftWorkdir == null && isInitialColdStart && currentOpenIds.isNotEmpty() -> {
                val liveById = refreshedSessions
                    .filter { !it.isArchived }
                    .associateBy { it.id }
                val candidateId = currentOpenIds.asReversed()
                    .firstOrNull { it in liveById }
                if (candidateId != null) AutoSelectDecision.SelectRestored(candidateId)
                else AutoSelectDecision.ClearChat
            }
            currentSessionId == null && draftWorkdir == null ->
                AutoSelectDecision.ClearChatResidual
            currentSessionId != null ->
                AutoSelectDecision.KeepCurrent(currentSessionId)
            else ->
                AutoSelectDecision.ClearChatResidual
        }
    }

    private sealed class AutoSelectDecision {
        data object ClearChat : AutoSelectDecision()
        data class SelectRestored(val sessionId: String) : AutoSelectDecision()
        data class KeepCurrent(val sessionId: String) : AutoSelectDecision()
        data object ClearChatResidual : AutoSelectDecision()
    }
}

/**
 * Stateless metadata-cache writer. Contains the ONLY cache projection algorithm.
 * Both the F8 facade and [SessionListRefreshOrchestrator] use the same instance.
 */
internal class SessionMetadataCacheWriter {
    @Suppress("UNUSED_PARAMETER")
    internal fun persistSessionCache(
        settingsManager: SettingsManager,
        sessions: List<Session>,
        openIds: List<String>,
        currentId: String?,
        currentWorkdir: String?,
        revertCutoffs: Map<String, RevertCutoff>,
    ) {
        // §Q4-strict-sync: openIds / currentId / currentWorkdir retained in the
        // signature for call-site stability; the filter is now ALL root sessions.
        val cache = sessions
            .asSequence()
            .filter { s -> s.parentId == null && !s.isArchived }
            .map { session ->
                session.toCacheEntry().copy(
                    revertCreatedAtEpochMs = revertCutoffs[session.id]
                        ?.takeIf { it.messageId == session.revert?.messageId }
                        ?.let { (it.state as? RevertCutoffState.Resolved)?.createdAtEpochMs }
                )
            }
            .sortedByDescending { it.timeUpdated ?: 0L }
            .take(MainViewModelTimings.sessionCacheCap)
            .toList()
        settingsManager.sessionCache = cache
    }
}
