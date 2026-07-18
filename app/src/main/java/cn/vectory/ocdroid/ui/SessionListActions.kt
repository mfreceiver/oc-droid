package cn.vectory.ocdroid.ui

/**
 * §R-17 batch3d: Domain orchestration free functions. These are NOT the deleted
 * batch-2 AppState mirror helpers (aggregateFromSlices/syncSlicesFromAppState etc.).
 * They are coroutine-launch helpers called by the domain ViewModels and AppCore
 * orchestration extensions to perform async operations (load/refresh/mutate).
 * Future cleanup (batch3e+): may be inlined into individual VM private methods.
 */

import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.controller.applySessionDiffIfAbsent
import cn.vectory.ocdroid.ui.controller.allSessionsById
import cn.vectory.ocdroid.ui.controller.normalizeAuthoritativeStatusSnapshot
import cn.vectory.ocdroid.ui.controller.loadCompleteSessionTrees
import cn.vectory.ocdroid.ui.controller.rootIdOf
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.RevertCutoff
import cn.vectory.ocdroid.data.model.RevertCutoffState
import cn.vectory.ocdroid.data.model.toCacheEntry
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.WorkdirPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Persist a bounded session-metadata cache to [SettingsManager.sessionCache]
 * so the next cold start can reseed the session-list slice instantly (tabs,
 * title, workdir groups).
 *
 * §Q4-strict-sync: the filter is now ALL non-archived root sessions
 * (`parentId == null && !isArchived`), replacing the legacy
 * `open-ids / current-id / current-workdir-roots` tri-filter. The open/current
 * sessions naturally satisfy `parentId == null` (they are roots the user
 * navigated to), so they are still cached; but so are ALL other root sessions
 * the server returned, giving a fuller cold-start reseed. A cap
 * ([MainViewModelTimings.sessionCacheCap]) prevents ESP bloat for users with
 * many sessions — when exceeded, entries are trimmed by time.updated desc
 * (keep the most recently active).
 *
 * Fix #5: previously written only inside [launchLoadSessions].onSuccess,
 * which never re-runs on a plain `selectSession` (no message sent). After
 * opening an existing conversation and restarting, the tab vanished because
 * its Session metadata was missing from the cache. This helper is now also
 * called from [MainViewModel.selectSession] and [MainViewModel.sendMessage]
 * so opening/creating a conversation persists its metadata immediately.
 */
@Suppress("UNUSED_PARAMETER")
internal fun persistSessionCache(
    settingsManager: SettingsManager,
    sessions: List<Session>,
    openIds: List<String>,
    currentId: String?,
    currentWorkdir: String?,
    revertCutoffs: Map<String, RevertCutoff>
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
     * FIX-D (gpter #2): provider for the current host's serverGroupFp.
     * Used to key the stale-host re-check after the REST suspend.
     */
    expectedServerGroupFp: String? = null,
    /**
     * FIX-D (gpter #2): provider for the current host's serverGroupFp.
     * Used to detect a host switch during the in-flight REST fetch.
     */
    currentServerGroupFp: (() -> String)? = null,
    // §grouping-rewrite Round-2 #5: hostProfileStore parameter removed — it
    // was wired in by R-20 Phase 5 for the cross-group merge that item 1 of
    // this rewrite deleted (attemptCrossGroupMerge was the sole consumer
    // inside this function body). Both call sites (SessionViewModel,
    // AppCoreOrchestration) and one test updated to drop the now-unused arg.
    /**
     * WT6 (archive-sync, gap-3): invoked when the merged refresh result flips
     * the [currentSessionId] session to `isArchived == true` (the canonical
     * cross-device-archive-during-SSE-gap case Task 1's reconnect refresh now
     * surfaces). The caller mirrors the SSE archive eviction path
     * (AppAction.SessionArchived → applyArchiveEviction + applyArchivedChatClear)
     * so the chat does not linger on a session the render filters now hide.
     * Null = caller has no dispatch surface (legacy / test) → the merged list is
     * still written; only the chat-clear is skipped.
     *
     * FIX-A/C (review-blocker): RENAMED + REWIDENED from
     * `onCurrentSessionArchived((Session) -> Unit)`. The callback now fires
     * whenever ANY session in the merged result is archived AND was in the
     * openIds or was the current session — not just the current session. It
     * carries the full merged list + the pruned openIds so the caller can
     * dispatch a SINGLE atomic [AppAction.BulkSessionsRefreshed] that writes
     * the list AND prunes openIds AND (if current is archived) clears chat,
     * eliminating the torn intermediate the prior two-step produced. The
     * callback is invoked BEFORE any mutateSessionList so no collector ever
     * observes the "sessions[current].isArchived == true AND
     * chat.currentSessionId == current" combo.
     * [confirmedServerIds] always comes from the raw REST response; it must not
     * be derived from [mergedSessions], which can contain pending-local ids.
     */
    onArchivedSessionsDetected: ((mergedSessions: List<Session>, newOpenIds: List<String>, hasMoreSessions: Boolean, confirmedServerIds: Set<String>, sweepNow: Long) -> Unit)? = null,
) {

    scope.launch {
        fun staleHostAfterSuspend(): Boolean = expectedServerGroupFp != null &&
            currentServerGroupFp != null &&
            expectedServerGroupFp != currentServerGroupFp()

        // FIX-D (gpter #2): capture this request's epoch. If a newer
        // launchLoadSessions call is issued while this REST is in flight,
        // the post-suspend check will detect the supersession and drop the
        // stale result before any write/persist/archive-callback.
        val myEpoch = sessionListLoadEpoch.incrementAndGet()

        val limit = MainViewModelTimings.sessionFullLoadLimit
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
                // FIX-D (gpter #2): if a newer load was issued while this REST
                // was in flight, drop the stale result entirely — it must NOT
                // update sessions/openSessionIds/cache NOR trigger archive
                // side-effects (which are now destructive per FIX-A/C).
                // §epoch-no-op (task 1): a stale result is fully no-op — it
                // no longer clears isLoadingMoreSessions / isRefreshingSessions
                // (the newer in-flight request owns those flags; writing here
                // could transiently flip a still-loading UI to idle before the
                // newer request resets it). Just log + return.
                if (myEpoch != sessionListLoadEpoch.get()) {
                    DebugLog.d("Sync", "launchLoadSessions: epoch $myEpoch superseded, discarding stale snapshot")
                    return@onSuccess
                }
                if (staleHostAfterSuspend()) {
                    // §epoch-no-op (task 1): stale-host ⇒ fully no-op (same
                    // rationale as the epoch-stale branch above).
                    return@onSuccess
                }
                // Capture cross-slice reads BEFORE the sessionList update:
                // mergeRefreshedSessionsPreservingLocalActivity needs
                // currentSessionId (chat slice) and openSessionIds (sessionList).
                // §R-17 batch2 step e final: slice-only reads (slices are the
                // sole authoritative store). Single capture for this
                // synchronous pre-write cluster.
                val currentSessionId = slices.chat.value.currentSessionId
                val currentSessionList = slices.sessionList.value
                val currentSessions = currentSessionList.sessions
                val currentOpenIds = currentSessionList.openSessionIds
                // §fix-close-all-residual: capture the pre-load cold-start flag.
                // The auto-select below must fire ONLY on the first load (true
                // cold start), not on every refresh — otherwise closing all
                // tabs (currentSessionId → null) gets overridden on the next
                // refresh and the first server session (often a stale error
                // session) is resurrected as a tab-less residual chat.
                val isInitialColdStart = !currentSessionList.hasCompletedInitialLoad
                // §Q4-strict-sync: capture pendingCreateIds for the merge
                // (preserve local-only ids that are still pending-create).
                val currentPendingCreateIds = currentSessionList.pendingCreateIds
                val currentPendingCreatedAt = currentSessionList.pendingCreatedAt
                val mergedSessions = mergeRefreshedSessionsPreservingLocalActivity(
                    sessions,
                    currentSessions,
                    currentSessionId,
                    currentOpenIds.toSet(),
                    pendingCreateIds = currentPendingCreateIds,
                )
                // Nav redesign: the initial load is a fixed full-page snapshot
                // (sessionFullLoadLimit) with no load-more UI, so hasMore is
                // hard-false after this load regardless of the returned size—
                // other consumers (TopBar/Picker) must not advertise a next page
                // that the Sessions tab will never trigger. 500 is an accepted
                // "effectively all" product cap (per-workdir directorySessions
                // fan-out covers connected projects beyond the global page).
                val newHasMore = false
                if (staleHostAfterSuspend()) {
                    return@onSuccess
                }
                if (staleHostAfterSuspend()) return@onSuccess
                val refreshedSessions = mergedSessions
                // §Q4-strict-sync: compute the NEW pendingCreateIds set.
                //   - Remove any id that surfaced in the authoritative refresh
                //     (confirmed by the server → no longer pending).
                //   - Sweep any remaining pending id whose LOCAL creation
                //     timestamp is older than pendingCreateTimeoutMs (trust
                //     the server — if it has not propagated by now, drop it).
                // The sweep needs `now` (captured here, outside the reducer)
                // and the independent local registration timestamp. Missing
                // timestamps fail closed and are swept.
                val sweepNow = System.currentTimeMillis()
                val serverIds = sessions.mapTo(mutableSetOf()) { it.id }
                val sweptPendingCreateIds = currentPendingCreateIds
                    .minus(serverIds)
                    .filter { pendingId ->
                        val registeredAt = currentPendingCreatedAt[pendingId]
                        registeredAt != null &&
                            sweepNow - registeredAt <= MainViewModelTimings.pendingCreateTimeoutMs
                    }
                    .toSet()
                val sweptPendingCreatedAt = currentPendingCreatedAt
                    .filterKeys { it in sweptPendingCreateIds }
                // remove-message-persistence Task 6: the prior
                // `currentSessionVerifyResult = cacheRepository.verifyFingerprint`
                // suspend + epoch/host re-check block was deleted together
                // with the CacheRepository surface. The currentSessionId is
                // consumed directly below (memory LRU is the sole cache; the
                // server is the source of truth for cross-restart drift).
                // ── FIX-A/C (review-blocker): atomic bulk-archive commit ─────
                // Detect ALL archived sessions in the merged result that are
                // currently OPEN (in openSessionIds) or are the current chat
                // session. If any are found AND the caller wired the callback,
                // dispatch a SINGLE BulkSessionsRefreshed action that writes
                // the merged list + prunes openIds + (if current is archived)
                // clears chat — all in one committed aggregate state. This
                // replaces the prior two-step (mutateSessionList THEN separate
                // dispatch) which produced a torn "sessions[current].isArchived
                // == true AND chat.currentSessionId == current" intermediate.
                // The callback is invoked BEFORE the mutateSessionList so no
                // collector can observe the combo.
                val archivedIds = mergedSessions
                    .filter { it.isArchived }
                    .map { it.id }
                    .toSet()
                val newOpenIds = currentOpenIds.filter { it !in archivedIds }
                val currentIsArchived = currentSessionId != null && currentSessionId in archivedIds
                val anyArchivedOpen = archivedIds.isNotEmpty() &&
                    (currentIsArchived || currentOpenIds.any { it in archivedIds })
                if (anyArchivedOpen && onArchivedSessionsDetected != null) {
                    // FIX-C: single atomic dispatch via the callback — writes
                    // the list AND prunes openIds AND clears chat in ONE step.
                    onArchivedSessionsDetected?.invoke(mergedSessions, newOpenIds, newHasMore, serverIds, sweepNow)
                    // Persist the PRUNED cache (FIX-A: archived ids no longer
                    // in openSessionIds; currentId null if archived so the
                    // cache filter drops it from the "current" slot too).
                    persistSessionCache(
                        settingsManager = settingsManager,
                        sessions = mergedSessions,
                        openIds = newOpenIds,
                        currentId = if (currentIsArchived) null else currentSessionId,
                        currentWorkdir = settingsManager.currentWorkdir,
                        revertCutoffs = slices.chat.value.revertCutoffs
                    )
                    // Skip the auto-select / load logic below — the dispatch
                    // atomically cleared chat if needed; loading messages for
                    // the just-archived id is wasteful + racy vs the reducer's
                    // clear. Non-archive path runs for sessions that survived.
                    //
                    // §fix-archive-early-return-flag (oracle review): the
                    // early-return below skips the mutateSessionList that sets
                    // hasCompletedInitialLoad=true. A first load that cleaned
                    // archived-open tabs MUST still count as "initial load
                    // completed", otherwise a later close-all-tabs would see
                    // the flag still false and re-fire the cold-start
                    // auto-select. The BulkSessionsRefreshed reducer dispatched
                    // by the callback writes sessions/openIds but does NOT set
                    // this flag, so set it explicitly here.
                    slices.mutateSessionList { it.copy(hasCompletedInitialLoad = true) }
                    return@onSuccess
                }
                slices.mutateSessionList {
                    it.copy(
                        sessions = mergedSessions,
                        hasMoreSessions = newHasMore,
                        isLoadingMoreSessions = false,
                        isRefreshingSessions = false,
                        // §Q4-strict-sync: update pendingCreateIds in the
                        // SAME committed state as the sessions list (atomic).
                        pendingCreateIds = sweptPendingCreateIds,
                        pendingCreatedAt = sweptPendingCreatedAt,
                        // §gpter-important: a full REST list replace is
                        // authoritative for structure. If SSE dropped events,
                        // stale completeness proofs could persist against a
                        // structurally-changed tree. Discard them and bump the
                        // epoch so in-flight hydrations drop (fail-closed).
                        completeRootIds = emptySet(),
                        completenessEpoch = it.completenessEpoch + 1L,
                        // §fix-close-all-residual: this load has completed —
                        // subsequent refreshes must NOT re-fire the cold-start
                        // auto-select (see isInitialColdStart capture above).
                        hasCompletedInitialLoad = true,
                    )
                }
                // Persist a BOUNDED session-metadata cache so the next cold
                // start can reseed the session-list slice instantly (tabs/title/
                // workdir groups). §Q4-strict-sync: now caches ALL non-archived
                // root sessions (was: open/current/currentWorkdir only), capped
                // at sessionCacheCap by time.updated desc.
                persistSessionCache(
                    settingsManager = settingsManager,
                    sessions = mergedSessions,
                    openIds = newOpenIds,
                    currentId = currentSessionId,
                    currentWorkdir = settingsManager.currentWorkdir,
                    revertCutoffs = slices.chat.value.revertCutoffs
                )
                // §Q4-strict-sync (#9 workdir auto-discovery): scan the refreshed
                // sessions' directories and add any NEW workdir (not already in
                // the current serverGroupFp's recentWorkdirs) to recentWorkdirs
                // + fan-out getSessionsForDirectory. This keeps recentWorkdirs
                // in sync with the server's actual project distribution even
                // when the user never explicitly connected a workdir via the
                // draft flow (e.g. sessions created via another client). The
                // fan-out is best-effort (failures swallowed) and identity-
                // guarded (staleHostAfterSuspend drops cross-host results).
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
                // (FIX-A/C: the archived-current + non-current-open-id archive
                // detection + atomic callback now lives ABOVE the normal
                // mutateSessionList, before any write — see
                // [anyArchivedOpen] / [onArchivedSessionsDetected]. This
                // branch is reached only when no archives were detected OR
                // the caller has no callback wired.)
                when {
                    // Skip auto-select when the user is mid-draft (a workdir
                    // has been chosen but no session created yet): selecting a
                    // session would discard the draft's repository workdir and
                    // hijack the empty chat page the user is composing into.
                    //
                    // gro-2 Blocker 2b: select the first NON-archived session.
                    // If the server returns an archived session first (e.g.
                    // after a bulk-archive nulled currentSessionId), we must
                    // NOT resurrect it as current. If ALL candidates are
                    // archived (or the list is empty), fall through to the
                    // chat-clear — never select an archived session.
                    //
                    // §fix-close-all-residual: the auto-select is ALSO gated on
                    // [isInitialColdStart] — it must only land the user on a
                    // session during true cold start. Once the first load has
                    // completed, a null currentSessionId means the user
                    // deliberately closed every tab (or otherwise emptied the
                    // chat), and the empty state is the correct outcome —
                    // silently re-selecting the first server session would
                    // resurrect a tab-less residual chat.
                    currentSessionId == null &&
                        slices.composer.value.draftWorkdir == null &&
                        isInitialColdStart -> {
                        val candidate = refreshedSessions.firstOrNull { !it.isArchived }
                        if (candidate != null) {
                            onSelectSession(candidate.id)
                        } else {
                            slices.mutateChat { c -> c.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap()) }
                        }
                    }
                    // §fix-close-all-residual: null currentSessionId on a NON-
                    // initial refresh (user closed all tabs) — keep the empty
                    // state instead of auto-selecting. currentSessionId stays
                    // null; ChatScaffold renders the empty-state hint.
                    currentSessionId == null &&
                        slices.composer.value.draftWorkdir == null -> {
                        slices.mutateChat { c -> c.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap()) }
                    }
                    // currentId is set: keep it. Even when the session is
                    // temporarily absent from the refreshed list (e.g. just
                    // created, or a directory session), tolerate it — reload
                    // its messages but do NOT silently reselect first(). #10.
                    //
                    // remove-message-persistence Task 6: the prior
                    // `cacheRepository.verifyFingerprint` currentSessionId
                    // self-consistency check (R-20 Phase 1 C7) was deleted
                    // together with the CacheRepository surface — the
                    // MismatchEvicted → clear + first-select branch is gone.
                    // The currentSessionId is kept as-is; VerifyAndHydrate's
                    // in-memory peek handles cold-start hydration.
                    currentSessionId != null -> {
                        onLoadSessionStatus()
                        onLoadMessages(currentSessionId)
                    }
                    else -> {
                        slices.mutateChat { c -> c.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap()) }
                    }
                }
            }
            .onFailure { error ->
                // §epoch-no-op (task 1): a stale FAILURE is fully no-op too —
                // if a newer launchLoadSessions superseded this one (epoch
                // bumped) or the host switched under us, clearing the loading
                // flags + emitting an error here would (a) transiently flip a
                // still-loading newer request to idle and (b) surface a stale
                // error toast for a request the user no longer cares about.
                // The newer request owns the loading flags; drop before any
                // write/emit.
                if (myEpoch != sessionListLoadEpoch.get() || staleHostAfterSuspend()) {
                    DebugLog.d("Sync", "launchLoadSessions: stale failure (epoch/host changed), discarding")
                    return@onFailure
                }
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
                val currentSessionList = slices.sessionList.value
                val currentSessions = currentSessionList.sessions
                val currentOpenIds = currentSessionList.openSessionIds
                // §fix-close-all-residual: mirror the launchLoadSessions cold-
                // start gate (see there). loadMore is user-scroll-triggered so
                // this auto-select is effectively dead post-fix (initial load
                // already completed before the user can scroll), but gate it
                // symmetrically so a future trigger change can't resurrect the
                // close-all-tabs residual via this path.
                val isInitialColdStart = !currentSessionList.hasCompletedInitialLoad
                // §Q4-strict-sync: capture pendingCreateIds for the merge + sweep.
                val currentPendingCreateIds = currentSessionList.pendingCreateIds
                val currentPendingCreatedAt = currentSessionList.pendingCreatedAt
                val mergedSessions = mergeRefreshedSessionsPreservingLocalActivity(
                    sessions,
                    currentSessions,
                    currentSessionId,
                    currentOpenIds.toSet(),
                    pendingCreateIds = currentPendingCreateIds,
                )
                // §Q4-strict-sync: sweep pendingCreateIds (remove confirmed +
                // drop timed-out) — mirrors launchLoadSessions.
                val sweepNow = System.currentTimeMillis()
                val serverIds = sessions.mapTo(mutableSetOf()) { it.id }
                val sweptPendingCreateIds = currentPendingCreateIds
                    .minus(serverIds)
                    .filter { pendingId ->
                        val registeredAt = currentPendingCreatedAt[pendingId]
                        registeredAt != null &&
                            sweepNow - registeredAt <= MainViewModelTimings.pendingCreateTimeoutMs
                    }
                    .toSet()
                val sweptPendingCreatedAt = currentPendingCreatedAt
                    .filterKeys { it in sweptPendingCreateIds }
                val newHasMore = mergedSessions.size >= nextLimit
                slices.mutateSessionList {
                    it.copy(
                        sessions = mergedSessions,
                        loadedSessionLimit = nextLimit,
                        hasMoreSessions = newHasMore,
                        isLoadingMoreSessions = false,
                        // §Q4-strict-sync: update pendingCreateIds atomically
                        // with the sessions list.
                        pendingCreateIds = sweptPendingCreateIds,
                        pendingCreatedAt = sweptPendingCreatedAt,
                        // §gpter-important: REST pagination is also a structural
                        // catch-up — merged sessions may carry changed parentId
                        // / archived state that SSE dropped. Discard cached
                        // completeness proofs and bump the epoch so in-flight
                        // hydrations drop (fail-closed).
                        completeRootIds = emptySet(),
                        completenessEpoch = it.completenessEpoch + 1L,
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
                    //
                    // §fix-close-all-residual: also gated on isInitialColdStart
                    // — a non-initial load must not resurrect a session after
                    // the user closed every tab.
                    currentId == null &&
                        slices.composer.value.draftWorkdir == null &&
                        refreshedSessions.isNotEmpty() &&
                        isInitialColdStart -> onSelectSession(refreshedSessions.first().id)
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

// §single-flight/epoch (groker🟡 v0.7.5): 并发触发(重连 + switchTo + loadSessions)时,
// 每次发起递增 epoch; REST 返回时若 epoch 已被更新请求超越则丢弃本结果——避免后完成者
// 把先完成者的 REST 写入误判为"SSE 在途变化"而保留(并发粘 busy 边角)。
private val statusLoadEpoch = java.util.concurrent.atomic.AtomicLong(0)

// FIX-D (gpter #2, review-blocker): single-flight epoch for the session-LIST
// load (launchLoadSessions). Mirrors [statusLoadEpoch]'s pattern: concurrent
// calls (reconnect + foreground catch-up + manual refresh) each increment at
// launch; a stale response whose epoch has been superseded is dropped BEFORE
// any write/persist/archive-callback — critical now that the archive callback
// (FIX-A/C) is destructive (prunes openIds + clears chat). Without this, a
// slow stale response could trigger the destructive eviction AFTER a newer
// response already updated the state.
//
// Threading: all launchLoadSessions calls run on the same scope (Main.immediate,
// serial). The epoch check at the top of onSuccess is sufficient — the writes
// (mutateSessionList / persist / callback) happen synchronously after that
// check with no suspension in between (remove-message-persistence Task 6
// removed the prior post-verifyFingerprint re-check), so no TOCTOU is
// possible on this dispatcher.
private val sessionListLoadEpoch = java.util.concurrent.atomic.AtomicLong(0)

internal fun launchLoadSessionStatus(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    onComplete: (Boolean) -> Unit = {},
) {
    val myEpoch = statusLoadEpoch.incrementAndGet()
    val hostAtRequestStart = slices.host.value.currentHostProfileId
    scope.launch {
        var completionCalled = false
        fun complete(success: Boolean) {
            if (!completionCalled) {
                completionCalled = true
                onComplete(success)
            }
        }
        try {
            // §sse-rest-race: REST 发起前快照本地 status, onSuccess 时识别"REST 在途期间
            // 被 SSE 更新过的 session"——旧 REST 快照不得覆盖较新的 SSE 值。
            val localBefore = slices.sessionList.value.sessionStatuses
            val statusResult = repository.getSessionStatus()
            val activeResult = repository.getActiveSessionIds()
            val statuses = statusResult.getOrNull()
            var applied = false
            slices.mutateSessionList { sl ->
                // StateFlow.update may retry this transform after a CAS
                // collision. Report the result of the final attempt only.
                applied = false
                // The status epoch and host identity jointly fence both REST
                // responses. A host switch explicitly clears activeSessionIds;
                // an old-host response must never repopulate that snapshot.
                if (myEpoch != statusLoadEpoch.get() ||
                    slices.host.value.currentHostProfileId != hostAtRequestStart
                ) {
                    DebugLog.d("Sync", "launchLoadSessionStatus: stale epoch/host, discarding snapshot")
                    return@mutateSessionList sl
                }
                val authoritativeIds = allSessionsById(
                    sl.sessions,
                    sl.directorySessions,
                    sl.childSessions,
                ).keys
                val nextStatuses = if (statuses != null) {
                    val normalized = normalizeAuthoritativeStatusSnapshot(statuses, authoritativeIds)
                    mergeStatusSnapshot(localBefore, sl.sessionStatuses, normalized)
                } else {
                    sl.sessionStatuses
                }
                // Fail-closed: a failed active fetch retains the previous
                // snapshot. Both branches intersect the current tree so a
                // deleted/archived session cannot remain active forever.
                val nextActiveIds = activeResult.getOrNull()
                    ?.intersect(authoritativeIds)
                    ?: sl.activeSessionIds.intersect(authoritativeIds)
                applied = true
                sl.copy(
                    sessionStatuses = nextStatuses,
                    activeSessionIds = nextActiveIds,
                )
            }
            statusResult
                .onSuccess {
                // §unread-soak: the REST status snapshot NO LONGER marks unread
                // on the "busy→absent" edge. The [UnreadSoakController] sweep +
                // pure [evaluateUnread] evaluator own the marking now — they
                // consume the freshly-merged sessionStatuses (below) on the
                // next foreground tick. The epoch-guarded merge still runs so
                // the evaluator sees authoritative idle/busy state.
                complete(applied)
            }
                .onFailure { error ->
                reportNonFatalIssue("MainViewModel", "Failed to load session status", error)
                complete(false)
            }
            activeResult.onFailure { error ->
                DebugLog.w("Sync", "Failed to load active sessions; retaining prior snapshot: ${error.message}")
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            complete(false)
            throw cancellation
        } catch (_: Throwable) {
            complete(false)
        }
    }
}

/**
 * §sse-rest-race 纯函数 (groker🟡 v0.7.5): 合并 REST 权威快照与本地状态。
 * - REST 快照整体替换: 清除 server 已 idle(快照缺失)的 stale busy (opencode status.ts:
 *   idle 时 data.delete, /session/status 只含 active)。
 * - 保护 REST 在途期间被 SSE 更新的 session: localAfter[id] != localBefore[id] → 保留
 *   SSE 新值, 避免慢 REST 旧快照覆盖较新 idle/busy。
 * 抽为纯函数便于表驱动矩阵单测。
 */
internal fun mergeStatusSnapshot(
    localBefore: Map<String, SessionStatus>,
    localAfter: Map<String, SessionStatus>,
    restSnapshot: Map<String, SessionStatus>
): Map<String, SessionStatus> {
    val result = restSnapshot.toMutableMap()
    for ((id, after) in localAfter) {
        if (localBefore[id] != after) result[id] = after
    }
    return result
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
            val before = slices.sessionList.value
            val byId = allSessionsById(before.sessions, before.directorySessions, before.childSessions)
            val rootId = rootIdOf(sessionId, byId) ?: sessionId
            // The effect can race the root list load. The children endpoint is
            // still addressable by id, so hydrate from a minimal placeholder;
            // metadata will be supplied by the normal session-list refresh.
            val root = byId[rootId] ?: Session(id = rootId, directory = "")
            // §gpter-blocker: capture the completeness epoch BEFORE hydration
            // starts. If an invalidation (SSE session.created/updated or a REST
            // structural replace) bumps the epoch mid-flight, the commit below
            // drops the result (fail-closed) so a stale snapshot can never
            // re-certify a root whose tree was invalidated.
            val epochAtStart = before.completenessEpoch
            val hydration = loadCompleteSessionTrees(repository, listOf(root))
            if (rootId in hydration.completeRootIds) {
                val statusBefore = slices.sessionList.value.sessionStatuses
                val statusSnapshot = repository.getSessionStatus().getOrNull()
                slices.mutateSessionList {
                    // §gpter-blocker: the tree was invalidated mid-flight —
                    // drop the stale result. The root stays incomplete so the
                    // next tick re-hydrates against the fresh tree.
                    if (it.completenessEpoch != epochAtStart) return@mutateSessionList it
                    val nextChildren = it.childSessions + hydration.childrenByParent
                    val nextStatuses = if (statusSnapshot != null) {
                        val authoritativeIds = allSessionsById(it.sessions, it.directorySessions, nextChildren).keys
                        val normalizedStatuses = normalizeAuthoritativeStatusSnapshot(statusSnapshot, authoritativeIds)
                        mergeStatusSnapshot(statusBefore, it.sessionStatuses, normalizedStatuses)
                    } else it.sessionStatuses
                    it.copy(
                        childSessions = nextChildren,
                        completeRootIds = it.completeRootIds + rootId,
                        sessionStatuses = nextStatuses,
                    )
                }
            } else {
                reportNonFatalIssue(tag, "Failed to load complete child tree for $rootId")
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
 *
 * §chat-ux-batch T8 (B3): the legacy selectedAgentName reconciliation (drop
 * the global pick if the agent is no longer offered by the server, else
 * keep + persist) was deleted here. T7 rewired agent selection to the
 * TRANSIENT pendingAgent chat-slice field, so the global pick no longer
 * exists; loadAgents now just writes the freshly-fetched list to the
 * settings slice. Called by AppCore's effect-dispatch handler.
 */
internal fun launchLoadAgents(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    tag: String,
) {
    scope.launch {
        repository.getAgents()
            .onSuccess { agents ->
                slices.mutateSettings { it.copy(agents = agents) }
            }
            .onFailure { error -> reportNonFatalIssue(tag, "Failed to load agents", error) }
    }
}

// §grouping-rewrite Round-2 #5: `directoriesMatchOrIntersect(...)` was here
// (Phase 5 G1 condition b helper) — sole caller was `attemptCrossGroupMerge`,
// which item 1 of this rewrite deleted. Removed as dead code.
