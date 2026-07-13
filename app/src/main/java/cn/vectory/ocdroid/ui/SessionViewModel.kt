package cn.vectory.ocdroid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.cache.CacheRepository
import cn.vectory.ocdroid.data.cache.contract.CachedSessionWindow
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.di.UiApplicationScope
import cn.vectory.ocdroid.ui.controller.ComposerController
import cn.vectory.ocdroid.ui.controller.ConnectionCoordinator
import cn.vectory.ocdroid.ui.controller.SessionSwitcher
import cn.vectory.ocdroid.ui.controller.allSessionsById
import cn.vectory.ocdroid.ui.controller.removeSessions
import cn.vectory.ocdroid.util.DebugLog
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
    /** R-20 Phase 1: persistent cache mirror — needed so this VM's
     *  loadSessions → onLoadMessages path persists newly-fetched windows
     *  to the encrypted cache, parallel to AppCore.loadMessagesForEffect. */
    private val cacheRepository: CacheRepository,
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
        core.cacheRepository,
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
     * §WT2-taskB (Q6 locked): set the "enter from Sessions page → jump to
     * latest" intent for [sessionId]. SessionsScreen.onSessionClick calls
     * this BEFORE [selectSession] so the matching switchTo keeps the intent
     * (SessionSwitcher clears it when the incoming id does not match),
     * and ChatMessageList consumes it exactly once the session's messages
     * have loaded (scrollToItem(0) + followBottom=true + clear). Swipe,
     * tab-strip tap, and SessionPickerSheet paths do NOT call this — their
     * saveable scroll-position restore is preserved unchanged.
     */
    fun requestJumpToLatest(sessionId: String) {
        store.dispatch(AppAction.PendingJumpToLatestSet(sessionId))
    }

    fun openSubAgent(childSessionId: String) {
        val parentId = store.chatFlow.value.currentSessionId
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

    fun closeSession(sessionId: String) {
        val isCurrent = store.chatFlow.value.currentSessionId == sessionId
        if (isCurrent) {
            // glm-3 🟡#1: single-read fp.
            val fp = hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id }
            settingsManager.setDraftText(fp, sessionId, store.composerFlow.value.inputText)
        }
        val updated = settingsManager.openSessionIds.filter { it != sessionId }
        settingsManager.openSessionIds = updated
        val nextId = updated.firstOrNull()
        store.mutateSessionList { it.copy(openSessionIds = updated) }
        store.mutateUnread { it.copy(unreadSessions = it.unreadSessions - sessionId) }
        if (isCurrent && nextId == null) {
            // §R18 Phase 2-F: chatFlow is the sole runtime source; the
            // chat.update below clears currentSessionId. The AppCore collector
            // drops null, so no manual SettingsManager write.
            store.mutateChat { it.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap()) }
        }
        if (isCurrent && nextId == null) {
            // §1B-FIX (I4): closing the last session must also clear
            // fileReferences + imageAttachments — the chips must not leak
            // to the empty state.
            store.mutateComposer { it.copy(inputText = "", imageAttachments = emptyList(), fileReferences = emptyList()) }
        }
        if (isCurrent && nextId != null) {
            selectSession(nextId)
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
        val workdir = workdir.trim()
        if (workdir.isBlank()) return
        // §R18 Phase 3 Wave 2 (drift #6): user-triggered refresh →
        // viewModelScope (ephemeral; cancel on VM clear, re-fetchable on return).
        viewModelScope.launch {
            repository.getSessionsForDirectory(workdir)
                .onSuccess { sessions ->
                    store.mutateSessionList { it.copy(directorySessions = it.directorySessions + (workdir to sessions)) }
                }
        }
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

    /**
     * §R-19 P2-5: was `core.loadSessionsForEffect()` (a 1-line wrapper around
     * [launchLoadSessions] that lived in AppCoreOrchestration.kt). Inlined
     * here so this VM does not need an AppCore reference just for that one
     * orchestration helper. The callbacks mirror the wrapper's: select-on-
     * load + cascade to loadSessionStatus + loadMessages for the current
     * session. Behaviour preserved verbatim.
     */
    fun loadSessions() {
        val expectedFp = hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id }
        launchLoadSessions(
            scope = appScope,
            repository = repository,
            slices = store.slices,
            settingsManager = settingsManager,
            onSelectSession = ::selectSession,
            onLoadSessionStatus = { launchLoadSessionStatus(appScope, repository, store.slices) },
            onLoadMessages = { sessionId -> launchLoadMessagesForEffect(sessionId) },
            emit = EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
            // R-20 Phase 1 (C7): mirror AppCore.loadSessionsForEffect's wiring
            // so the currentSessionId fingerprint verify runs on this VM's
            // loadSessions path too. AppCore holds the cacheRepository
            // singleton; this VM holds its own (Hilt-bound same instance).
            cacheRepository = cacheRepository,
            expectedServerGroupFp = expectedFp,
            currentServerGroupFp = { hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id } },
            // §grouping-rewrite Round-2 #5: the hostProfileStore arg that R-20
            // Phase 5 wired here is removed — its sole consumer inside
            // launchLoadSessions (attemptCrossGroupMerge) was deleted by item 1
            // of this rewrite.
        )
    }

    /** §R-19 P2-5: extracted from the former AppCore.loadMessagesForEffect so
     *  [loadSessions]'s onLoadMessages callback stays self-contained. Mirrors
     *  the AppCoreOrchestration.kt helper's body (which is preserved verbatim
     *  for AppCore's own dispatch helpers).
     *
     *  R-20 Phase 1: onCacheWindow mirrors the in-memory LRU write to the
     *  persistent encrypted cache. fp captured at this call (current host)
     *  so a profile switch mid-flight cannot re-key a write to the wrong
     *  group. Mirrors [AppCore.makeCacheHook]; the body is duplicated rather
     *  than injected because the §R-19 P2-5 design rule keeps AppCore out of
     *  this VM's constructor (precise injection only).
     *
     *  §review-fix #2 (gpter #2): memory LRU write uses the CAPTURED fp (was
     *  re-reading currentServerGroupFp via SessionSwitcher — now passes fp
     *  explicitly). §review-fix #3 (gpter #3): session metadata lookup
     *  includes directorySessions. */
     private fun launchLoadMessagesForEffect(sessionId: String) {
         val fp = hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id }
         val cacheHook: (String, CachedSessionWindow) -> Unit = { sid, window ->
             sessionSwitcher.writeSessionWindow(fp, sid, window)
             // §review-fix #3: include directorySessions so directory-only
             // sessions get their real createdAt/workdir cached.
             val sessionList = store.sessionListFlow.value
             val session = (sessionList.sessions + sessionList.directorySessions.values.flatten())
                 .firstOrNull { it.id == sid }
             val createdAt = session?.time?.created
             val workdir = session?.directory ?: settingsManager.currentWorkdir ?: ""
             appScope.launch {
                 runCatching { cacheRepository.putSessionWindow(fp, sid, createdAt, workdir, window) }
                     .onFailure { DebugLog.e(TAG, "cache write failed for fp=$fp sid=$sid", it) }
             }
         }
         launchLoadMessages(
             scope = appScope,
             repository = repository,
             slices = store.slices,
             sessionId = sessionId,
             resetLimit = true,
             settingsManager = settingsManager,
             onCacheWindow = cacheHook,
             emit = EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
             // gpter 复审 final-fix: compound-key guard.
             expectedServerGroupFp = fp,
             currentServerGroupFp = { hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id } },
         )
     }

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
