package cn.vectory.ocdroid.ui

/**
 * §R-17 batch3d: Domain orchestration free functions. These are NOT the deleted
 * batch-2 AppState mirror helpers (aggregateFromSlices/syncSlicesFromAppState etc.).
 * They are coroutine-launch helpers called by the domain ViewModels and AppCore
 * orchestration extensions to perform async operations (load/refresh/mutate).
 * Future cleanup (batch3e+): may be inlined into individual VM private methods.
 */

import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.cache.CacheRepository
import cn.vectory.ocdroid.data.cache.FingerprintResult
import cn.vectory.ocdroid.ui.controller.applySessionDiffIfAbsent
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.toCacheEntry
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.util.DebugLog
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
    emit: EventEmitter = EventEmitter { },
    /**
     * R-20 Phase 1 (C7): persistent cache for the currentSessionId fingerprint
     * self-consistency check. Null = caller has not been migrated yet; the
     * verify is skipped (preserves the legacy behavior for unmigrated callers).
     */
    cacheRepository: CacheRepository? = null,
    expectedServerGroupFp: String? = null,
    /**
     * R-20 Phase 1 (C7): provider for the current host's serverGroupFp. Used
     * to key the verifyFingerprint call. Null = caller has not been migrated.
     */
    currentServerGroupFp: (() -> String)? = null,
    // §grouping-rewrite Round-2 #5: hostProfileStore parameter removed — it
    // was wired in by R-20 Phase 5 for the cross-group merge that item 1 of
    // this rewrite deleted (attemptCrossGroupMerge was the sole consumer
    // inside this function body). Both call sites (SessionViewModel,
    // AppCoreOrchestration) and one test updated to drop the now-unused arg.
) {

    scope.launch {
        fun staleHostAfterSuspend(): Boolean = expectedServerGroupFp != null &&
            currentServerGroupFp != null &&
            expectedServerGroupFp != currentServerGroupFp()

        val limit = MainViewModelTimings.sessionPageSize
        slices.mutateSessionList {
            it.copy(
                loadedSessionLimit = limit,
                hasMoreSessions = true,
                isLoadingMoreSessions = false,
                isRefreshingSessions = true
            )
        }
        repository.getSessions(limit)
            .onSuccess { sessions ->
                if (staleHostAfterSuspend()) {
                    slices.mutateSessionList {
                        it.copy(isLoadingMoreSessions = false, isRefreshingSessions = false)
                    }
                    return@onSuccess
                }
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
                if (staleHostAfterSuspend()) {
                    return@onSuccess
                }
                if (staleHostAfterSuspend()) return@onSuccess
                val refreshedSessions = mergedSessions
                var currentSessionVerifyResult: FingerprintResult? = null
                if (currentSessionId != null && cacheRepository != null && currentServerGroupFp != null) {
                    val targetSession = refreshedSessions.firstOrNull { it.id == currentSessionId }
                    val createdAt = targetSession?.time?.created
                    val fp = currentServerGroupFp()
                    // Suspend before writing the refreshed slice; if the host
                    // changes while this verify is in flight, the post-suspend
                    // guard below drops the stale REST result entirely.
                    currentSessionVerifyResult = runCatching {
                        cacheRepository.verifyFingerprint(fp, currentSessionId, createdAt)
                    }.getOrNull()
                    if (staleHostAfterSuspend()) return@onSuccess
                }
                slices.mutateSessionList {
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
                if (staleHostAfterSuspend()) return@onSuccess
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
                        // R-20 Phase 1 (C7, plan §3 矩阵 "session list 到达"
                        // 行): verify the currentSessionId's cache fingerprint
                        // against the freshly-fetched server copy. This is a
                        // CACHE SELF-CONSISTENCY check (defends against DB
                        // corruption / fp drift / a stale cached window whose
                        // session was recreated with a different createdAt).
                        // The full cross-connection MERGE is Phase 5's job
                        // (gap-aware); here we only reconcile the one
                        // currentSessionId. MismatchEvicted → drop
                        // currentSessionId (the cached window is stale; the
                        // next switchTo / loadMessages will cold-start it).
                        // Verified / UnknownColdStart → no-op.
                        if (cacheRepository != null && currentServerGroupFp != null) {
                            if (currentSessionVerifyResult is FingerprintResult.MismatchEvicted) {
                                DebugLog.i(
                                    "SessionListActions",
                                    "loadSessions: currentSessionId=$currentSessionId fingerprint mismatch → clearing currentSessionId (cold-start fallback)"
                                )
                                slices.mutateChat { c -> c.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap()) }
                                // After clearing, fall through to the
                                // first-select path so the user lands on a
                                // valid session instead of the empty state
                                // (mirrors the cold-start UX).
                                if (slices.composer.value.draftWorkdir == null && refreshedSessions.isNotEmpty()) {
                                    onSelectSession(refreshedSessions.first().id)
                                }
                            } else {
                                onLoadSessionStatus()
                                onLoadMessages(currentSessionId)
                            }
                        } else {
                            onLoadSessionStatus()
                            onLoadMessages(currentSessionId)
                        }
                    }
                    else -> {
                        slices.mutateChat { c -> c.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap()) }
                    }
                }
            }
            .onFailure { error ->
                slices.mutateSessionList {
                    it.copy(
                        isLoadingMoreSessions = false,
                        isRefreshingSessions = false
                    )
                }
                emit.emit(UiEvent.Error(R.string.error_load_sessions_failed, listOf(errorMessageOrFallback(error, "unknown error"))))
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
        slices.mutateSessionList { sl -> sl.copy(isLoadingMoreSessions = true) }
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
                    slices.mutateSessionList { sl -> sl.copy(isLoadingMoreSessions = false) }
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
                slices.mutateSessionList {
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
                    else -> slices.mutateChat { c -> c.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap()) }
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

internal fun launchLoadSessionStatus(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows
) {

    scope.launch {
        repository.getSessionStatus()
            .onSuccess { statuses ->
                slices.mutateSessionList { sl ->
                    // §item6: /session/status 是全局权威快照, 只含 active(busy/retry) —
                    // idle 已被 server delete (opencode session/status.ts: data.delete on
                    // idle). 整体替换正确清除 server 已 idle(快照缺失)的 stale 本地 busy.
                    // 切勿改 merge(+statuses): 会永久保留旧 busy 致"已 idle 仍判 busy 显 Stop".
                    sl.copy(sessionStatuses = statuses)
                }
            }
            .onFailure { error ->
                reportNonFatalIssue("MainViewModel", "Failed to load session status", error)
            }
    }
}

/**
 * §issue-1(1): 打开会话时拉取该会话的文件变更快照（GET /session/{id}/diff，
 * 已带 X-Opencode-Skip-Dir，无需 directory）。结果写入 SessionListState.sessionDiffs，
 * 驱动聊天内 SessionDiffCard。SSE session.diff 会随后增量覆盖，故失败仅记录不报错。
 * 与 [launchLoadSessionStatus] 同构（per-call scope + slice mutation）。 */
internal fun launchLoadSessionDiff(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    sessionId: String
) {
    scope.launch {
        repository.getSessionDiff(sessionId)
            .onSuccess { diffs ->
                // §glmer-S1 / §maxer-复审：REST 仅在 SSE 尚未覆盖时写入——避免 REST 在途
                //  期间 SSE 推送了更新快照后被旧的 REST 结果覆盖（stale-overwrite）。SSE
                //  session.diff 是权威源；REST 只是乐观预取。抽成 applySessionDiffIfAbsent
                //  纯函数以便单测，镜像上游 web client 的 diff() 守卫。
                slices.mutateSessionList { it.applySessionDiffIfAbsent(sessionId, diffs).first }
            }
            .onFailure { error ->
                reportNonFatalIssue("MainViewModel", "Failed to load session diff", error)
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
                    slices.mutateSessionList { it.copy(childSessions = it.childSessions + (sessionId to children)) }
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
    directory: String?,
    tag: String,
) {
    scope.launch {
        repository.getPendingQuestions(directory)
            .onSuccess { questions ->
                slices.mutateSessionList { currentState ->
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
                slices.mutateSessionList { it.copy(pendingPermissions = permissions) }
            }
            .onFailure { error -> android.util.Log.w(tag, "Failed to load permissions: ${error.message}") }
    }
}

/**
 * §R-17 batch3d: free-function extraction of the former AppCore.loadAgents body.
 * Reconciles the current selectedAgentName against the freshly-fetched list
 * (§agent-default: falls back to null = server default if the selected agent
 * is no longer offered, or when the user never chose one). Called by AppCore's
 * effect-dispatch handler.
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
                // §agent-default: 仅当用户显式选过且该 agent 仍在服务端列表中才保留；
                // 否则置 null（让服务端用其默认 agent），不再强制回退 "build"。
                val validAgent = if (currentAgent != null && agents.any { it.name == currentAgent }) currentAgent else null
                slices.mutateSettings { it.copy(agents = agents, selectedAgentName = validAgent) }
                if (validAgent != currentAgent) settingsManager.selectedAgentName = validAgent
            }
            .onFailure { error -> reportNonFatalIssue(tag, "Failed to load agents", error) }
    }
}

// §grouping-rewrite Round-2 #5: `directoriesMatchOrIntersect(...)` was here
// (Phase 5 G1 condition b helper) — sole caller was `attemptCrossGroupMerge`,
// which item 1 of this rewrite deleted. Removed as dead code.
