package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.routeChatSessionId
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * §single-flight/epoch for the session-LIST load (launchLoadSessions).
 * Owns the sole [sessionListLoadEpoch] so concurrent requests are fenced
 * (see FIX-D / task 1). All public methods are per-invocation — receive
 * repository, slices, settings, callbacks, etc. via parameters; no fields
 * except stable infra (cacheWriter, clockMs).
 *
 * §Wave2.3 nit#2 — DI deferral note (verified, NOT migrated):
 * The `repository` param is typed [OpenCodeRepository] (concrete), but the method set
 * actually touched here is interface-clean — `getSessions` / `getSessionsForDirectory`
 * (SessionRepository) + `getSessionDiff` (FileVcsRepository), i.e. a dual (Session +
 * FileVcs) seam would suffice. Migration is DEFERRED: these are per-call params threaded
 * in by RefreshOrchestrator (which itself holds OCR concrete and is transitively blocked
 * by the slim-token shim via launchLoadMessages / launchCatchUp). Narrowing the param type
 * here would force a dual-param split at every caller now, before the caller chain is
 * unblocked. Revisit after B3 (slim-token retirement) — see oracle wave2.3 §3.4.
 */
internal class SessionListRefreshOrchestrator(
    private val cacheWriter: SessionMetadataCacheWriter,
    private val clockMs: () -> Long,
) {
    /** FIX-D (gpter #2, review-blocker): single-flight epoch. Intentionally separate from P7's StatusPollOrchestrator status epoch. */
    private val sessionListLoadEpoch = AtomicLong(0)

    /**
     * §需求10 C3 (round-4, oracle-driven cancel-and-replace): the in-flight
     * heavyweight refresh job. A new caller CANCELS this (cancel-and-replace:
     * newer intent wins; the older coroutine dies BEFORE any write because
     * cancellation is delivered to the suspend Retrofit call and onSuccess/
     * onFailure do not run). See [SharedStateStore.sessionListLoadInFlight]
     * KDoc for the authoritative concurrency-model invariant set.
     *
     * NOT nulled in the finally: `cancel()` on a completed job is a no-op,
     * `isListLoadInFlight()` checks `isActive`, and nulling here would clobber
     * a newer job's reference (old finally wiping new ref) for no benefit.
     */
    private var inFlightLoad: Job? = null

    /** Whether a session-list full refresh is currently in-flight. */
    internal fun isListLoadInFlight(): Boolean = inFlightLoad?.isActive == true

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
        expectedProfileId: String? = null,
        currentProfileId: (() -> String)? = null,
        onArchivedSessionsDetected: ((mergedSessions: List<Session>, hasMoreSessions: Boolean, confirmedServerIds: Set<String>, sweepNow: Long) -> Unit)? = null,
    ) {
        // §需求10 C3 (round-4, oracle cancel-and-replace): newer intent supersedes
        // the older in-flight request. Bump the epoch at ENTRY (synchronously) so the
        // in-flight job's completion checks see the epoch moved and self-discard even
        // before its cancellation is observed; then CANCEL the in-flight job so it dies
        // BEFORE any write (cancellation delivered to suspend Retrofit → onSuccess/
        // onFailure never run → no cache persist / archive callback / ClearChat).
        // This is original FIX-D (newer-wins via epoch) + "cancel the loser so it stops
        // consuming the network" — net code DELETION vs the prior CAS-gate + trailing-
        // rerun design, which structurally dissolves all 3 rev-4 defects (no loser →
        // no stale-closure rerun; flag set inside body → no acquire-before-launch leak;
        // poller out of the gate → no lost-pending).
        val myEpoch = sessionListLoadEpoch.incrementAndGet()
        inFlightLoad?.cancel()
        inFlightLoad = scope.launch {
            try {
                // Flag set INSIDE the body: a scope cancellation between entry and
                // body-launch can't leak the flag (the flag is never set if the body
                // never runs).
                slices.store.sessionListLoadInFlight = true

                fun staleHostAfterSuspend(): Boolean = expectedProfileId != null &&
                    currentProfileId != null &&
                    expectedProfileId != currentProfileId()

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
                    val currentPendingCreateIds = currentSessionList.pendingCreateIds
                    val currentPendingCreatedAt = currentSessionList.pendingCreatedAt
                    // §B4: open-tabs-list gone — merge only needs pendingCreateIds
                    // for local-activity preserve (currentSessionId retained for
                    // call-site compatibility; unused in preserve filter).
                    val mergedSessions = mergeRefreshedSessionsPreservingLocalActivity(
                        sessions,
                        currentSessions,
                        currentSessionId,
                        pendingCreateIds = currentPendingCreateIds,
                    )
                    val newHasMore = false
                    if (staleHostAfterSuspend()) {
                        return@onSuccess
                    }
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
                    // §B4 / §10 REST refresh: do NOT change route / auto-select.
                    // Check BOTH currentSessionId AND route id — a session can be
                    // the active chat/{id} detail without currentSessionId being
                    // set yet (nav-flip → SessionSelected window).
                    val routeId = routeChatSessionId(slices.store.stateFlow.value.nav.lastRoute)
                    val routeIsArchived = routeId != null && routeId in archivedIds
                    val currentIsArchived = (currentSessionId != null && currentSessionId in archivedIds) ||
                        routeIsArchived
                    val anyArchived = archivedIds.isNotEmpty()
                    if (anyArchived && onArchivedSessionsDetected != null) {
                        onArchivedSessionsDetected?.invoke(mergedSessions, newHasMore, serverIds, sweepNow)
                        // P0-D: unconditional cold-start status reconcile — previously the
                        // archive early-return skipped status entirely (R7). onArchivedSessionsDetected
                        // synchronously dispatches BulkSessionsRefreshed (commits the merged tree),
                        // so launchLoadSessionStatus reads the committed session IDs.
                        onLoadSessionStatus()
                        cacheWriter.persistSessionCache(
                            settingsManager = settingsManager,
                            sessions = mergedSessions,
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
                        currentId = currentSessionId,
                        currentWorkdir = settingsManager.currentWorkdir,
                        revertCutoffs = slices.chat.value.revertCutoffs,
                    )
                    val discoveryFp = currentProfileId?.invoke()
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
                                            if (currentProfileId?.invoke() != discoveryFp) return@launch
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
                    // P0-D: cold-start status reconcile is UNCONDITIONAL across every decision
                    // path (KeepCurrent / ClearChat / NoOp). The tree was committed by the
                    // SessionsRefreshedLocal dispatch above, so launchLoadSessionStatus reads
                    // the committed session IDs. Exactly-once per successful refresh.
                    onLoadSessionStatus()
                    // §B4 / §10 REST refresh: no auto-select from open-tabs-list.
                    // Keep current if still live; clear residual current pointing
                    // at a missing/archived session. Never invent sessions.first()
                    // and never navigate (route is caller's job).
                    when (val decision = decideRefreshCurrentSession(
                        currentSessionId = currentSessionId,
                        draftWorkdir = slices.composer.value.draftWorkdir,
                        refreshedSessions = refreshedSessions,
                    )) {
                        is RefreshCurrentDecision.ClearChat -> {
                            slices.store.dispatch(AppAction.ChatCleared)
                        }
                        is RefreshCurrentDecision.KeepCurrent -> {
                            onLoadMessages(decision.sessionId)
                        }
                        is RefreshCurrentDecision.NoOp -> Unit
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
            } finally {
                // §需求10 C3 (round-4, oracle cancel-and-replace): clear the flag
                // ONLY if this job still holds the current epoch — a superseded
                // (cancelled) job must NOT wipe the newer job's flag. inFlightLoad
                // is intentionally NOT nulled (see its KDoc): cancel() on a
                // completed job is a no-op, isListLoadInFlight() checks isActive,
                // and nulling here would clobber a newer job's reference.
                if (sessionListLoadEpoch.get() == myEpoch) {
                    slices.store.sessionListLoadInFlight = false
                }
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
                    val currentPendingCreateIds = currentSessionList.pendingCreateIds
                    val currentPendingCreatedAt = currentSessionList.pendingCreatedAt
                    val mergedSessions = mergeRefreshedSessionsPreservingLocalActivity(
                        sessions,
                        currentSessions,
                        currentSessionId,
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
                    // §B4: page-append never auto-selects / navigates.
                    when (
                        decideRefreshCurrentSession(
                            currentSessionId = currentSessionId,
                            draftWorkdir = slices.composer.value.draftWorkdir,
                            refreshedSessions = mergedSessions,
                        )
                    ) {
                        is RefreshCurrentDecision.ClearChat -> {
                            slices.store.dispatch(AppAction.ChatCleared)
                        }
                        is RefreshCurrentDecision.KeepCurrent,
                        is RefreshCurrentDecision.NoOp -> Unit
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
     * §B4 / §10 REST refresh: decide whether the live [currentSessionId] still
     * belongs on the detail pane after a sessions refresh. Never invents
     * sessions.first(); never restores from open-tabs-list (removed). Draft
     * gate prevents hijacking mid-composition.
     *
     * - KeepCurrent: id still live (non-archived) in refreshed list, OR id is
     *   temporarily absent (not yet propagated / not archived) → reload status/
     *   messages. §9.3: an in-session refresh must not clobber a valid current
     *   unless it is confirmed archived.
     * - ClearChat: residual current points at a CONFIRMED archived session (and
     *   no draft), OR no sessions at all with no current (empty state).
     * - NoOp: null current with non-empty session list and no draft — leave chat
     *   alone (e.g. mid-draft or a refresh that returned sessions but no current
     *   was ever set).
     */
    private fun decideRefreshCurrentSession(
        currentSessionId: String?,
        draftWorkdir: String?,
        refreshedSessions: List<Session>,
    ): RefreshCurrentDecision {
        if (currentSessionId == null) {
            // No current session. If the refresh returned no sessions at all,
            // clear any residual chat (empty state). Otherwise leave chat alone.
            return if (refreshedSessions.isEmpty()) {
                RefreshCurrentDecision.ClearChat
            } else {
                RefreshCurrentDecision.NoOp
            }
        }
        if (draftWorkdir != null) return RefreshCurrentDecision.NoOp
        val archived = refreshedSessions.firstOrNull { it.id == currentSessionId && it.isArchived }
        return if (archived != null) {
            RefreshCurrentDecision.ClearChat
        } else {
            // Session is either live in the list, or temporarily absent (not yet
            // propagated). In both cases keep current + reload messages (§9.3).
            RefreshCurrentDecision.KeepCurrent(currentSessionId)
        }
    }

    private sealed class RefreshCurrentDecision {
        data object ClearChat : RefreshCurrentDecision()
        data class KeepCurrent(val sessionId: String) : RefreshCurrentDecision()
        data object NoOp : RefreshCurrentDecision()
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
        currentId: String? = null,
        currentWorkdir: String? = null,
        revertCutoffs: Map<String, RevertCutoff>,
    ) {
        // §B4: openIds removed. currentId / currentWorkdir retained for
        // call-site stability; the filter is ALL root non-archived sessions.
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
