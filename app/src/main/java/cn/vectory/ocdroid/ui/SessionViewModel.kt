package cn.vectory.ocdroid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.di.UiApplicationScope
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.ScrollBehavior
import cn.vectory.ocdroid.ui.ScrollCheckpoint
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
     * §Wave5b-Q13: open a sub-agent session. Captures the PARENT's scroll
     * checkpoint (passed in synchronously by the Compose layer — see
     * ChatMessageList's onOpenSubAgent wrapper) into
     * [ChatState.parentReturnCheckpoints] under the child id key, then
     * selects the child with the default Latest behavior.
     *
     * The checkpoint MUST be supplied by the caller (Compose layer), NOT
     * derived inside the VM: the VM has no listState handle and the async
     * savedPositions mirror cannot guarantee the last pre-navigation frame
     * (oracle ruling). The synchronous capture at the click site is the
     * authoritative source.
     *
     * Sequence:
     *  1. dispatch [AppAction.ParentCheckpointStored] — adds the entry.
     *  2. (existing sub-agent load + upsert + selectSession) — selectSession
     *     → switchTo(childId, Latest) writes the child's pendingScrollRequest.
     *
     * If the child cannot be resolved (rare), the checkpoint is still stored
     * (cheap; harmless if unused; cleared on host purge / archive).
     */
    fun openSubAgent(childSessionId: String, parentCheckpoint: ScrollCheckpoint) {
        val parentId = store.chatFlow.value.currentSessionId
        // §Wave5b-Q13: store the parent checkpoint BEFORE the sub-agent load
        // so it is on file by the time selectSession flips currentSessionId.
        // Synchronous dispatch — no coroutine hop, the entry is committed
        // before the launch below runs.
        store.dispatch(AppAction.ParentCheckpointStored(childSessionId, parentCheckpoint))
        // §R18 Phase 3 Wave 2 (drift #6 / P1-7): user-triggered open-sub-agent
        // → viewModelScope. Closure captures `this@SessionViewModel` (via the
        // selectSession call below) — viewModelScope keeps it alive exactly as
        // long as the VM.
        // §R-19 #9: P1-7 closure-self-ref guard added — if the VM is cleared
        // before the repository getSession call resolves, bail out before
        // touching the captured selectSession / slice writes. Without the
        // guard, viewModelScope cancellation would throw CancellationException
        // out of the launch body (which is fine), but the closure would still
        // hold a strong ref to the cleared VM until GC; the explicit guard
        // makes the no-op intent obvious + defensive against any future
        // restructuring that moves the body off viewModelScope.
        viewModelScope.launch {
            if (!isActive) return@launch
            val child = store.sessionListFlow.value.sessions.firstOrNull { it.id == childSessionId }
                ?: parentId?.let { pid -> store.sessionListFlow.value.childSessions[pid]?.find { it.id == childSessionId } }
                ?: store.sessionListFlow.value.childSessions.values.flatten().firstOrNull { it.id == childSessionId }
                ?: runSuspendCatching { repository.getSession(childSessionId).getOrNull() }.getOrNull()
            if (!isActive) return@launch
            if (child != null) {
                store.mutateSessionList { state -> state.copy(sessions = upsertSession(state.sessions, child)) }
                selectSession(childSessionId)
            } else {
                effectBus.tryEmitUiEvent(UiEvent.Error(R.string.error_child_session_unavailable))
            }
        }
    }

    /**
     * §Wave5b-Q13: navigate from a child session back to its parent, restoring
     * the parent's scroll position captured at the corresponding openSubAgent
     * call.
     *
     * Sequence:
     *  1. Read `parentReturnCheckpoints[currentSessionId]` (the child's id).
     *  2. If an entry exists → dispatch [AppAction.ParentCheckpointConsumed]
     *     (removes the entry) AND switchTo(parentId, Restore(checkpoint)).
     *  3. If NO entry exists (e.g. cold-started into a child, or the entry
     *     was cleared by a host purge) → fallback to switchTo(parentId,
     *     Latest) so the user still gets back to the parent (just at the
     *     newest message instead of a remembered position).
     *  4. If there is no parent (parentId == null) → no-op (the user is on
     *     a root session; returnToParent is not callable from the UI in that
     *     case anyway — BackHandler is gated on `parent != null`).
     *
     * parentId resolution: looks up the current session in the union store
     * (sessions + directorySessions + childSessions) so a sub-agent that
     * lives only in childSessions is still resolved.
     */
    fun returnToParent() {
        val currentId = store.chatFlow.value.currentSessionId ?: return
        val sl = store.sessionListFlow.value
        val sessionsById = allSessionsById(sl.sessions, sl.directorySessions, sl.childSessions)
        val cur = sessionsById[currentId] ?: return
        val parentId = cur.parentId ?: return
        val stored = store.chatFlow.value.parentReturnCheckpoints[currentId]
        if (stored != null) {
            store.dispatch(AppAction.ParentCheckpointConsumed(currentId))
            sessionSwitcher.switchTo(parentId, ScrollBehavior.Restore(stored))
        } else {
            // Fallback: no checkpoint on file (cold-start into child, host
            // purge cleared the map, etc.). Still navigate to the parent —
            // just at the latest position rather than a remembered one.
            sessionSwitcher.switchTo(parentId, ScrollBehavior.Latest)
        }
    }

    fun closeSession(sessionId: String) {
        // §fix-close-subagent: the close-X only renders on the selected tab,
        // and the tab strip's effectiveSelectedId falls back to the ROOT when
        // the current session is a sub-agent (child). So the user CAN close a
        // root tab while currentSessionId points at one of its descendants.
        // The pre-fix `isCurrent = currentSessionId == sessionId` check missed
        // that case (childId != rootId → isCurrent=false) and left
        // currentSessionId pointing at a now-orphaned child, so the chat body
        // kept rendering after every tab was closed. `closingCurrentTree`
        // treats "closing the root of the current session's tree" the same as
        // "closing the current session itself".
        //
        // rootIdOf fails closed (returns null on unknown id / parentId cycle /
        // cold-start where the child isn't in childSessions yet): in that case
        // isDescendant=false and we fall back to the strict isCurrent check
        // (preserving the original behaviour for the unresolvable edge).
        val curId = store.chatFlow.value.currentSessionId
        val isCurrent = curId == sessionId
        val isDescendant = if (curId != null && !isCurrent) {
            val sl = store.sessionListFlow.value
            val sessionsById = allSessionsById(sl.sessions, sl.directorySessions, sl.childSessions)
            rootIdOf(curId, sessionsById) == sessionId
        } else {
            false
        }
        val closingCurrentTree = isCurrent || isDescendant
        if (closingCurrentTree && curId != null) {
            // glm-3 🟡#1: single-read fp.
            val fp = hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id }
            // §fix-close-subagent: persist the draft under the ACTUAL current
            // session id (curId), not the closed tab's root id. The user was
            // editing in the child's context, so the draft belongs to curId;
            // keying it on the root would lose / mis-restore it.
            settingsManager.setDraftText(fp, curId, store.composerFlow.value.inputText)
        }
        // §fix-close-all-slice-source: openSessionIds AUTHORITATIVE source is
        // the session-list slice (same as SessionSwitcher.append). Pre-fix
        // this filtered settingsManager.openSessionIds (disk), which could
        // diverge from the runtime tab strip and leave a ghost nextId (or
        // skip clearing) after the last visible tab was closed.
        val updated = store.sessionListFlow.value.openSessionIds.filter { it != sessionId }
        settingsManager.openSessionIds = updated
        val nextId = updated.lastOrNull()
        store.mutateSessionList { it.copy(openSessionIds = updated) }
        store.mutateUnread { it.copy(unreadSessions = it.unreadSessions - sessionId) }
        if (updated.isEmpty()) {
            // §fix-close-all-empty-tabs-home: ANY path that empties open tabs
            // (close last current, close last non-current while current was
            // already null, close last residual open id) must honor empty-tabs
            // intent — not only `closingCurrentTree`. Matches ChatScaffold's
            // empty openSessionIds → home rule (draftWorkdir is the sole
            // stay-on-Chat exception for mid-composition).
            val hasDraft = store.composerFlow.value.draftWorkdir != null
            // Defensive: no open tab may leave a residual currentSessionId
            // (orphan current after non-current last-close, or race residue).
            if (store.chatFlow.value.currentSessionId != null) {
                store.mutateChat {
                    it.copy(
                        currentSessionId = null,
                        messages = emptyList(),
                        partsByMessage = emptyMap(),
                    )
                }
                // §fix-close-all-residual: ALSO clear the PERSISTED
                // currentSessionId synchronously (belt-and-suspenders with the
                // AppCore null-persistence collector).
                settingsManager.currentSessionId = null
            }
            if (!hasDraft) {
                // §1B-FIX (I4): chips must not leak onto the empty / home
                // surface when leaving Chat entirely.
                store.mutateComposer {
                    it.copy(
                        inputText = "",
                        imageAttachments = emptyList(),
                        fileReferences = emptyList(),
                    )
                }
                // Domain half of leave-Chat: AppShell's
                // LaunchedEffect(requestedRoute) hops to Sessions. Compose
                // ChatScaffold snapshotFlow empty-tabs → onBackToHome remains
                // belt-and-suspenders (also handles popBackStack).
                val sessionsRoute = NavRoute.Sessions
                settingsManager.lastRoute = sessionsRoute.route
                store.mutateNav {
                    it.copy(
                        lastRoute = sessionsRoute.route,
                        lastNavPage = sessionsRoute.legacyPage,
                    )
                }
            }
        } else if (closingCurrentTree) {
            selectSession(nextId!!)
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
        val fp = hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id }
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
        launchForkSession(
            appScope, repository, store.slices, sessionId, messageId, ::selectSession,
            EventEmitter { event -> effectBus.tryEmitUiEvent(event) }
        )
    }

    fun archiveSession(sessionId: String) {
        // glm-3 🟡#1: single-read fp (was inline lambda double-read currentProfile).
        val fp = hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id }
        launchSetSessionArchived(
            appScope, repository, store.slices, settingsManager, sessionId, archived = true,
            EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
            // R-20 Phase 1 (C3): emit EvictSession per archived subtree id so
            // the cache (memory + persistent) is cleared for dismissed sessions.
            currentServerGroupFp = { fp },
            emitEffect = { effect -> effectBus.tryEmitEffect(effect) },
        )
    }

    fun restoreSession(sessionId: String) {
        // glm-3 🟡#1: single-read fp.
        val fp = hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id }
        launchSetSessionArchived(
            appScope, repository, store.slices, settingsManager, sessionId, archived = false,
            EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
            // C3: restore does not emit EvictSession (gated on isArchive inside
            // launchSetSessionArchived); pass the providers anyway for symmetry
            // so a future restore-also-evicts change is a one-liner.
            currentServerGroupFp = { fp },
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
        val fp = hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id }
        launchDeleteSession(
            appScope, repository, store.slices, settingsManager, sessionId, ::selectSession,
            EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
            // R-20 Phase 1 (C3): emit EvictSession on delete so the cache is
            // cleared for the removed session (privacy + storage hygiene).
            currentServerGroupFp = { fp },
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
