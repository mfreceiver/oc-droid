package cn.vectory.ocdroid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.di.UiApplicationScope
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.controller.ComposerController
import cn.vectory.ocdroid.ui.controller.ConnectionCoordinator
import cn.vectory.ocdroid.ui.controller.SessionSwitcher
import cn.vectory.ocdroid.ui.controller.allSessionsById
import cn.vectory.ocdroid.ui.controller.removeSessions
import cn.vectory.ocdroid.ui.controller.rootIdOf
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.WorkdirPaths
import cn.vectory.ocdroid.util.runSuspendCatching
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * R-17 batch3 → batch3d: Session-list-domain ViewModel. Owns the sessionList +
 * unread slices + the session CRUD / select / open-tab / child-session /
 * pending-question / pending-permission management.
 *
 * **batch3d**: method bodies physically moved here from [AppCore]. The VM
 * calls its domain controller ([SessionSwitcher]) and the
 * [SessionListActions] / [SessionMutationActions]
 * free functions directly — no `core.<method>()` self-bypass.
 *
 * State reads/writes flow through the shared [SharedStateStore] slices.
 *
 * §R-19 Sprint 3 P2-5: this VM no longer injects [AppCore]. Its precise
 * dependency surface is the sessionList / unread / chat / composer slices
 * ([SharedStateStore]) + [SessionSwitcher] (its domain controller) +
 * [ComposerController] + [ConnectionCoordinator] (cross-domain sibling
 * controllers it routes to for clearDraftIfActive / loadInitialData) +
 * [OpenCodeRepository] + [SettingsManager] + [SharedEffectBus] +
 * [@UiApplicationScope CoroutineScope] (the app-lifetime scope the launchXxx
 * free functions run on). The VM cannot reach any other slice/controller.
 *
 * The former `core.loadSessionsForEffect()` call (which was a thin wrapper
 * around [launchLoadSessions]) is inlined here so this VM does not need an
 * AppCore reference just for that one orchestration helper.
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val store: SharedStateStore,
    private val sessionSwitcher: SessionSwitcher,
    private val composerController: ComposerController,
    private val connectionCoordinator: ConnectionCoordinator,
    private val repository: OpenCodeRepository,
    private val settingsManager: SettingsManager,
    private val effectBus: SharedEffectBus,
    /** R-20 Phase 1: serverGroupFp source for the cache mirror hook. */
    private val hostProfileStore: HostProfileStore,
    @UiApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    /**
     * §R-19 P2-5 test-only convenience constructor — see
     * [SettingsViewModel.secondary constructor] rationale. Forwards the same
     * deps the production Hilt binding uses.
     */
    internal constructor(core: AppCore) : this(
        core.store,
        core.sessionSwitcher,
        core.composerController,
        core.connectionCoordinator,
        core.repository,
        core.settingsManager,
        core.effectBus,
        core.hostProfileStore,
        core.appScope,
    )

    val sessionListFlow get() = store.sessionListFlow
    val unreadFlow get() = store.unreadFlow
    val chatFlow get() = store.chatFlow
    val composerFlow get() = store.composerFlow

    // ── Session-domain methods (bodies moved from AppCore) ──────────────────

    fun selectSession(sessionId: String) {
        sessionSwitcher.switchTo(sessionId)
    }

    /**
     * §Wave5b-Q13: same-session "snap to latest" intent for the send path +
     * Chat-tab reselect path. Delegates to
     * [cn.vectory.ocdroid.ui.controller.SessionSwitcher.requestLatestScroll],
     * which deliberately bypasses [switchTo]'s same-session no-op guard.
     *
     * Replaces the pre-Wave5b `requestJumpToLatest` (which was an unrelated
     * mechanism that only set a flag consumed by a separate LaunchedEffect;
     * the new design unifies both into [PendingScrollRequest]).
     */
    fun requestLatestScroll(sessionId: String) {
        sessionSwitcher.requestLatestScroll(sessionId)
    }

    /**
     * §Wave5b-Q13 + §chat-list-detail §11 / G6 (B5): open a sub-agent session.
     *
     * **B5 redesign**: the parent's scroll checkpoint is no longer stored in
     * a global ChatState per-child checkpoint map. Instead it lives on the
     * parent route entry's [androidx.lifecycle.SavedStateHandle]. The
     * checkpoint write happens INSIDE [onNavigateToChild] (the success
     * callback) — NOT before this method is invoked. This avoids leaving a
     * stale checkpoint when the child fetch fails (B5 BLOCK-fix rev-gpt
     * MAJOR 1).
     *
     * The navigation itself is delegated to [onNavigateToChild] (typically
     * `routeSavedStateHandle.set(checkpointKeyForChild(id), cp);
     * orchestratorVM.navigateToChat(id)`). The checkpoint + navigation only
     * fire when the child fetch resolves successfully AND the parent route is
     * still the active route-instance (route-instance CAS guard below).
     *
     * §B5 BLOCK-fix (rev-gpt MAJOR 1): capture the parent's route id +
     * route-instance token at call time; re-validate BOTH after the async
     * fetch completes. If the user navigated away mid-fetch (e.g., tapped
     * another sub-agent, system-back, host purge), the route id or token will
     * have changed and the callback MUST NOT fire — otherwise we'd push a
     * child route onto a stack whose top is no longer the parent, leaking
     * both the checkpoint and the route.
     *
     * §R18 Phase 3 Wave 2 (drift #6 / P1-7): user-triggered open-sub-agent
     * → viewModelScope. The launch body's `if (!isActive) return@launch`
     * guards bail out before touching captured state if the VM is cleared
     * mid-fetch.
     *
     * @param childSessionId the sub-agent session id (resolved by the caller
     *   from the sub-agent card's session reference).
     * @param checkpoint the parent's scroll viewport captured synchronously
     *   by the Compose layer (ChatMessageContent's listState live-read).
     *   Carried through to [onNavigateToChild] so the callback can write it
     *   to the parent's SavedStateHandle in the SAME atomic step as the nav.
     * @param onNavigateToChild callback invoked with `(resolvedChildId,
     *   checkpoint)` on the success path. Implementations write the
     *   checkpoint to the parent route entry's SavedStateHandle and trigger
     *   route-aware navigation to the child.
     */
    fun openSubAgent(
        childSessionId: String,
        checkpoint: ScrollCheckpoint,
        onNavigateToChild: (resolvedChildId: String, checkpoint: ScrollCheckpoint) -> Unit,
    ) {
        // §B5 BLOCK-fix MINOR: prefer the route id (the §B2 authority) over
        // the lagging flat currentSessionId. The route flip commits before
        // SessionSelected flips currentSessionId, so a bare currentSessionId
        // read can target the PRIOR session during the transition window.
        val parentId = routeChatSessionId(store.navFlow.value.lastRoute)
            ?: store.chatFlow.value.currentSessionId
            ?: return
        // §B5 BLOCK-fix MAJOR 1: capture the parent's route-instance token at
        // call time. After the async fetch resolves, re-validate that the
        // route is STILL the same parent with the SAME token. If the user
        // navigated away mid-fetch, the token will differ and we silently
        // drop the openSubAgent (no callback fire, no checkpoint write).
        val capturedRouteInstance = store.slices.routeInstanceFor(parentId)
        viewModelScope.launch {
            if (!isActive) return@launch
            val child = store.sessionListFlow.value.sessions.firstOrNull { it.id == childSessionId }
                ?: parentId.let { pid -> store.sessionListFlow.value.childSessions[pid]?.find { it.id == childSessionId } }
                ?: store.sessionListFlow.value.childSessions.values.flatten().firstOrNull { it.id == childSessionId }
                ?: runSuspendCatching { repository.getSession(childSessionId).getOrNull() }.getOrNull()
            if (!isActive) return@launch
            // §B5 BLOCK-fix MAJOR 1: re-validate the parent route AFTER the
            // async fetch. Two failure modes:
            //  - routeChatSessionId(nav.lastRoute) != parentId → user navigated
            //    to a different route (system back, drawer tap, host purge).
            //  - routeInstanceFor(parentId) != capturedRouteInstance → the
            //    parent was re-entered via a fresh navigateToChat (token
            //    advanced); the prior openSubAgent is stale.
            // In either case the callback MUST NOT fire — the checkpoint
            // write + nav would land on the wrong route entry.
            val stillOnParent = routeChatSessionId(store.navFlow.value.lastRoute) == parentId
            val tokenUnchanged = store.slices.routeInstanceFor(parentId) == capturedRouteInstance
            if (!stillOnParent || !tokenUnchanged) return@launch
            if (child != null) {
                // T1c: SessionUpserted owns sessions-only upsert. Done BEFORE
                // the callback so the caller's navigateToChat(childId) →
                // openForRoute's sessions+directorySessions lookup succeeds.
                store.dispatch(AppAction.SessionUpserted(child))
                // §11 sequence step 3-4: write checkpoint + route-aware nav +
                // hydrate. §scroll-guard-fix: the child does NOT get its own
                // NavBackStackEntry — chat→chat singleTop in-place replaces
                // the shared slot. The checkpoint (stamped with
                // capturedFromSessionId=parentId by the caller) lives on the
                // shared handle and is consumed when the user returns to the
                // parent (see consumeAnySubAgentCheckpoint).
                onNavigateToChild(childSessionId, checkpoint)
            } else {
                // §B5 BLOCK-fix MAJOR 1: fetch failed → no checkpoint write,
                // no nav. Caller's captured checkpoint is dropped (it was
                // never persisted to the handle).
                effectBus.tryEmitUiEvent(UiEvent.Error(R.string.error_child_session_unavailable))
            }
        }
    }

    /**
     * §Wave5b-Q13 + §chat-list-detail §11 / G6 (B5): navigate from a child
     * session back to its parent via pop-based restoration.
     *
     * **B5 BLOCK-fix**: this method invokes [onReturnToExisting] (typically
     * `orchestratorVM.navigateToChat(pid)`). AppShell's synchronizer detects
     * the pop-restore case (target == previousBackStackEntry.sessionId) and
     * executes `popBackStack()` instead of `navigate()` — restoring the
     * EXISTING parent NavBackStackEntry (and its SavedStateHandle, which
     * carries the openSubAgent checkpoint). The parent's ChatScaffold LaunchedEffect then
     * reads + consumes the checkpoint → Restore fires.
     *
     * The prior B5 implementation called `navigateToChat(parentId)`, which
     * PUSHED a new parent entry on top of the child, producing
     * `[Sessions, parent(old handle), child, parent(NEW handle)]`. The NEW
     * parent's fresh handle had no checkpoint → Restore never fired.
     *
     * The single-scroll-intent contract (§11) is preserved: Restore vs
     * Latest is decided in exactly one place (the parent's LaunchedEffect),
     * based on checkpoint presence, consumed exactly once.
     *
     * §B5 BLOCK-fix MINOR: prefer `routeChatSessionId(nav.lastRoute)` over
     * the flat `currentSessionId` for the current id resolution (the route is
     * the §B2 authority). Falls back to flat currentSessionId on the legacy
     * bare-chat path.
     *
     * Returns true iff the navigation was dispatched; false on no-current /
     * no-parent / no-resolve (the call site's `parent != null` UI gate makes
     * these rare in production, but the guards stay defensive).
     */
    fun returnToParent(onReturnToExisting: (String) -> Unit): Boolean {
        // §B5 BLOCK-fix MINOR: route id first (§B2 authority), flat
        // currentSessionId as legacy fallback.
        val currentId = routeChatSessionId(store.navFlow.value.lastRoute)
            ?: store.chatFlow.value.currentSessionId
            ?: return false
        val sl = store.sessionListFlow.value
        val sessionsById = allSessionsById(sl.sessions, sl.directorySessions, sl.childSessions)
        val cur = sessionsById[currentId] ?: return false
        val parentId = cur.parentId ?: return false
        // §B5 BLOCK-fix: pop-based return — navigateToChat writes
        // navState.lastRoute; AppShell's synchronizer decides pop vs push
        // based on previousBackStackEntry.sessionId. The pop-restore path
        // preserves the parent's SavedStateHandle + checkpoint.
        onReturnToExisting(parentId)
        return true
    }

    /**
     * §B4 / §10 close (return to list): route-driven leave of the detail pane.
     * No open-tabs-list filter / sibling-tab switch — list-detail has a single
     * detail. Close the CURRENT session → flush draft → ChatCleared + CloseDetail
     * → force pop to Sessions (unless mid-composition draft). Close a NON-current
     * session is a no-op (the tab-strip concept is gone — only the active route's
     * leave triggers chat clear + pop-to-Sessions).
     *
     * [sessionId] is retained for the tab-strip / top-bar close-X call sites
     * (B6 still hosts the strip UI); the close is route-scoped, not tab-list-
     * scoped.
     */
    fun closeSession(sessionId: String) {
        val curId = store.chatFlow.value.currentSessionId
        val routeId = routeChatSessionId(store.navFlow.value.lastRoute)
        val isCurrent = curId == sessionId || routeId == sessionId
        // §B4 / §10: non-current close is a no-op — list-detail has a single
        // detail pane. Only the active route's leave triggers chat clear +
        // pop-to-Sessions.
        if (!isCurrent) return
        if (curId != null) {
            val fp = hostProfileStore.currentProfile().id
            settingsManager.setDraftText(fp, curId, store.composerFlow.value.inputText)
            settingsManager.flushDraftText()
        }
        store.mutateUnread { it.copy(unreadSessions = it.unreadSessions - sessionId) }

        val hasDraft = store.composerFlow.value.draftWorkdir != null
        // Clear chat content / current pointer — we are leaving the active tree.
        store.dispatch(AppAction.ChatCleared)
        store.dispatch(AppAction.CloseDetail)
        settingsManager.currentSessionId = null
        if (!hasDraft) {
            // §1B-FIX (I4): chips must not leak onto the empty / home surface.
            store.mutateComposer {
                it.copy(
                    inputText = "",
                    imageAttachments = emptyList(),
                    fileReferences = emptyList(),
                )
            }
            // §B4 / §10: force popToSessions (navEpoch bump covers Files/Git
            // equal-value trap — same as OrchestratorViewModel.forceNavigateToSessions).
            forceNavigateToSessionsInternal()
        }
    }

    /**
     * §B4: shared pop-to-Sessions transition used by close / delete-current /
     * archive-current / host-switch. Mirrors
     * [OrchestratorViewModel.forceNavigateToSessions] without depending on
     * that VM (SessionViewModel must stay AppCore-free).
     */
    private fun forceNavigateToSessionsInternal() {
        settingsManager.lastRoute = NavRoute.Sessions.route
        store.mutateNav {
            it.copy(
                lastRoute = NavRoute.Sessions.route,
                navEpoch = it.navEpoch + 1L,
            )
        }
    }

    fun loadMoreSessions() {
        launchLoadMoreSessions(
            scope = appScope,
            repository = repository,
            slices = store.slices,
            onSelectSession = ::selectSession,
            emit = EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
        )
    }

    fun createSession(title: String? = null) {
        launchCreateSession(
            appScope, repository, store.slices, title, ::selectSession,
            EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
            directory = settingsManager.currentWorkdir   // §R18 Final 终审 fix (gpter)
        )
    }

    fun createSessionInWorkdir(workdir: String) {
        val workdir = workdir.trim()
        // §R18 Phase 2-E step 2: the repository.setCurrentDirectory call was
        // removed; downstream directory-scoped calls (SSE / /question /
        // /command) now take an explicit `directory` parameter, and the
        // composer / settingsManager state below carries the workdir forward
        // (draftWorkdir + settingsManager.currentWorkdir).
        // §R18 Phase 2-F: chatFlow.currentSessionId (cleared by the dispatch
        // below) is the sole runtime source; the AppCore collector drops null,
        // so no manual SettingsManager write.
        // §A5-3 Phase B2 (release-gate fix A): the pre-B2 sequence — mutateChat
        // (clear chat + streaming), mutateSessionList (clear sessionTodos),
        // mutateChat (clear currentModel), mutateComposer (clear inputText +
        // attachments + fileReferences, set draftWorkdir) — is collapsed into
        // ONE atomic dispatch, mirroring the effect path
        // (AppCoreOrchestration.createSessionInWorkdirForEffect). The reducer
        // ([AppAction.WorkdirDraftStarted]) performs exactly these writes
        // (field parity verified against the 4 mutateXxx calls above) in a
        // single committed aggregate state → no torn intermediates for
        // stateFlow collectors. (groker #1: the recon missed this 5th site.)
        store.dispatch(AppAction.WorkdirDraftStarted(workdir = workdir))
        settingsManager.currentWorkdir = workdir
        // glm-3 🟡#1: single-read fp.
        val fp = hostProfileStore.currentProfile().id
        settingsManager.addRecentWorkdir(fp, workdir)
        // §R18 Phase 3 Wave 2 (drift #6): ephemeral directory-session prefetch
        // → viewModelScope. If the user navigates away mid-fetch the partial
        // directorySessions write is acceptable (refreshDirectorySessions
        // re-fetches on next open); cancellation on VM clear is safe.
        viewModelScope.launch {
            repository.getSessionsForDirectory(workdir)
                .onSuccess { sessions ->
                    store.mutateSessionList { it.copy(directorySessions = it.directorySessions + (workdir to sessions)) }
                }
        }
    }

    fun forkSession(sessionId: String, messageId: String?) {
        // §B2 rev-gpt: subagent 只读——禁止 fork 子会话
        val sl = store.sessionListFlow.value
        val sessionsById = allSessionsById(sl.sessions, sl.directorySessions, sl.childSessions)
        val targetSession = sessionId.let { sessionsById[it] }
        if (targetSession?.parentId != null) return
        launchForkSession(
            appScope, repository, store.slices, sessionId, messageId, ::selectSession,
            EventEmitter { event -> effectBus.tryEmitUiEvent(event) }
        )
    }

    fun archiveSession(sessionId: String) {
        // glm-3 🟡#1: single-read fp (was inline lambda double-read currentProfile).
        val fp = hostProfileStore.currentProfile().id
        launchSetSessionArchived(
            appScope, repository, store.slices, settingsManager, sessionId, archived = true,
            EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
            // R-20 Phase 1 (C3): emit EvictSession per archived subtree id so
            // the cache (memory + persistent) is cleared for dismissed sessions.
            currentProfileId = { fp },
            emitEffect = { effect -> effectBus.tryEmitEffect(effect) },
        )
    }

    fun restoreSession(sessionId: String) {
        // glm-3 🟡#1: single-read fp.
        val fp = hostProfileStore.currentProfile().id
        launchSetSessionArchived(
            appScope, repository, store.slices, settingsManager, sessionId, archived = false,
            EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
            // C3: restore does not emit EvictSession (gated on isArchive inside
            // launchSetSessionArchived); pass the providers anyway for symmetry
            // so a future restore-also-evicts change is a one-liner.
            currentProfileId = { fp },
            emitEffect = { effect -> effectBus.tryEmitEffect(effect) },
        )
    }

    /**
     * T4 (chat-ux-batch): rename a session via the long-press → rename dialog.
     * Delegates to [launchRenameSession] on [viewModelScope] (user-triggered,
     * ephemeral mutation — mirrors `createSessionInWorkdir` / `refreshDirectory-
     * Sessions` scope choice, NOT `archiveSession`'s `appScope`: a rename
     * racing with VM clear is safe to cancel; the title is not load-bearing
     * for any other in-flight orchestration).
     *
     * Empty [title] is forwarded as the empty string; the server clears the
     * session's title and [Session.displayName] falls back to the project
     * folder name on the next slice update.
     */
    fun renameSession(sessionId: String, title: String) {
        launchRenameSession(
            scope = viewModelScope,
            repository = repository,
            slices = store.slices,
            sessionId = sessionId,
            title = title,
            emit = EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
        )
    }

    fun deleteSession(sessionId: String) {
        // glm-3 🟡#1: single-read fp.
        val fp = hostProfileStore.currentProfile().id
        launchDeleteSession(
            appScope, repository, store.slices, settingsManager, sessionId, ::selectSession,
            EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
            // R-20 Phase 1 (C3): emit EvictSession on delete so the cache is
            // cleared for the removed session (privacy + storage hygiene).
            currentProfileId = { fp },
            emitEffect = { effect -> effectBus.tryEmitEffect(effect) },
        )
    }

    fun refreshDirectorySessions(workdir: String) {
        // §fix-connect-prefetch (9.5 gate, decision 1b): delegate to the
        // connectionCoordinator's GUARDED refresh so both the connect path
        // (SessionsScreen directory-picker onSelect) and the expand path
        // (HomeWorkdirRow onToggleExpand) drop stale-host results on a mid-flight
        // host/profile switch — previously this wrote directorySessions
        // unconditionally (a pre-existing race the connect prefetch would widen).
        connectionCoordinator.refreshDirectorySessions(workdir)
    }

    /**
     * §task5-lifecycle (final-review fix 3): drop unread badges for every
     * session bound to [workdir]. Coordinated by
     * [cn.vectory.ocdroid.ui.files.FilesScreen] alongside
     * [SettingsViewModel.disconnectWorkdir]; SettingsVM does not own the
     * sessionList slice. The id set is derived from the THREE-source session
     * union (sessions + directorySessions + childSessions) filtered by
     * `directory == workdir`, so a session that lives only in the global
     * `sessions` list (e.g. directorySessions prefetch not yet complete) is
     * still cleared — pre-fix this path only read `directorySessions[workdir]`
     * and would leak unread for sessions missing from that single bucket.
     *
     * §task5-lifecycle-r2 (final-fix round 2): the workdir match goes through
     * [WorkdirPaths.normalize] — the same key the disconnect pipeline
     * (removeRecentWorkdir / evictWorkdirInGroup / buildWorkdirGroups) uses to
     * decide "same workdir". Pre-fix this used the raw `directory == workdir`
     * string compare, so `/proj-a` vs `proj-a/` vs ` proj-a ` were treated as
     * DIFFERENT workdirs and disconnect cleared the project + cache but not
     * the unread badges.
     */
    internal fun clearUnreadForWorkdir(workdir: String) {
        val key = WorkdirPaths.normalize(workdir)
        if (key.isEmpty()) return
        val sl = store.sessionListFlow.value
        val ids = allSessionsById(sl.sessions, sl.directorySessions, sl.childSessions)
            .values
            .filter { WorkdirPaths.normalize(it.directory) == key }
            .map { it.id }
            .toSet()
        if (ids.isNotEmpty()) {
            store.mutateUnread { it.removeSessions(ids) }
        }
    }

    fun toggleSessionExpanded(sessionId: String) {
        store.mutateSessionList { s ->
            val next = if (s.expandedSessionIds.contains(sessionId)) s.expandedSessionIds - sessionId
                       else s.expandedSessionIds + sessionId
            s.copy(expandedSessionIds = next)
        }
    }

    fun loadChildSessions(sessionId: String) {
        // §R-17 batch3d: body extracted as launchLoadChildSessions so both this
        // VM and AppCore's effect-dispatch handler share the same impl.
        launchLoadChildSessions(
            scope = appScope,
            repository = repository,
            slices = store.slices,
            sessionId = sessionId,
            tag = TAG,
        )
    }

    fun loadPendingQuestions() {
        launchLoadPendingQuestions(
            scope = appScope,
            repository = repository,
            slices = store.slices,
            // §R18 Phase 2-E step 1: explicit workdir now required (was the
            // global currentDirectory before; behavior preserved via the
            // settingsManager fallback the global was seeded from).
            directory = settingsManager.currentWorkdir,
            tag = TAG,
        )
    }

    fun loadPendingPermissions() {
        launchLoadPendingPermissions(
            scope = appScope,
            repository = repository,
            slices = store.slices,
            effects = effectBus,
            tag = TAG,
        )
    }

    // §R-19 P2-5 legacy entry removed: `loadSessions()` had no production
    //  caller (only tests) and bypassed `onArchivedSessionsDetected`, unlike
    //  [AppCore.loadSessionsForEffect] which is now the sole session-list
    //  refresh entry (and carries archive-detection). Tests that exercised
    //  this path now call `core.loadSessionsForEffect()` directly. The
    //  private `launchLoadMessagesForEffect` helper was reachable only from
    //  the deleted entry and is removed with it.
    /**
     * §issue-1(1): 拉取指定会话的文件变更快照（GET /session/{id}/diff）。由聊天视图
     * 打开会话时按需触发（见 ChatMessageList 的 LaunchedEffect），刻意解耦消息加载
     * 路径——diff 是视图层数据，不必随每次 message reload 触发。SSE session.diff 会
     * 随后增量覆盖，故此处仅做乐观预取。 */
    fun loadSessionDiff(sessionId: String) {
        launchLoadSessionDiff(appScope, repository, store.slices, sessionId)
    }

    /** §R-17 batch3e: loadInitialData lives on [ConnectionViewModel] for
     *  production, but session-list tests that verify the workdir-restore
     *  fan-out exercise it through this VM because they assert on
     *  sessionListFlow afterwards. */
    fun loadInitialData() {
        connectionCoordinator.loadInitialData()
    }

    /** Clears any in-progress composer draft (composer-domain). Routed through
     *  [ComposerController] which owns the draftWorkdir guard. */
    fun clearDraftIfActive() {
        composerController.clearDraftIfActive()
    }

    private companion object {
        private const val TAG = "SessionViewModel"
    }
}
