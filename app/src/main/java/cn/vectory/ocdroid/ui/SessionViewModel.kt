package cn.vectory.ocdroid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.di.UiApplicationScope
import cn.vectory.ocdroid.ui.controller.ComposerController
import cn.vectory.ocdroid.ui.controller.ConnectionCoordinator
import cn.vectory.ocdroid.ui.controller.SessionSwitcher
import cn.vectory.ocdroid.util.SettingsManager
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
            settingsManager.setDraftText(sessionId, store.composerFlow.value.inputText)
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
            store.mutateComposer { it.copy(inputText = "") }
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
        // §R18 Phase 2-F: chatFlow.currentSessionId (cleared in the writeChat
        // below) is the sole runtime source; the AppCore collector drops null,
        // so no manual SettingsManager write.
        store.mutateChat {
            it.copy(
                currentSessionId = null,
                messages = emptyList(),
                partsByMessage = emptyMap(),
                streamingPartTexts = emptyMap(),
                streamingReasoningPart = null,
            )
        }
        store.mutateSessionList { it.copy(sessionTodos = emptyMap()) }
        store.mutateChat { it.copy(currentModel = null) }
        store.mutateComposer {
            it.copy(inputText = "", imageAttachments = emptyList(), draftWorkdir = workdir)
        }
        settingsManager.currentWorkdir = workdir
        settingsManager.addRecentWorkdir(workdir)
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
        launchSetSessionArchived(
            appScope, repository, store.slices, settingsManager, sessionId, archived = true,
            EventEmitter { event -> effectBus.tryEmitUiEvent(event) }
        )
    }

    fun restoreSession(sessionId: String) {
        launchSetSessionArchived(
            appScope, repository, store.slices, settingsManager, sessionId, archived = false,
            EventEmitter { event -> effectBus.tryEmitUiEvent(event) }
        )
    }

    fun deleteSession(sessionId: String) {
        launchDeleteSession(
            appScope, repository, store.slices, settingsManager, sessionId, ::selectSession,
            EventEmitter { event -> effectBus.tryEmitUiEvent(event) }
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
        launchLoadSessions(
            scope = appScope,
            repository = repository,
            slices = store.slices,
            settingsManager = settingsManager,
            onSelectSession = ::selectSession,
            onLoadSessionStatus = { launchLoadSessionStatus(appScope, repository, store.slices) },
            onLoadMessages = { sessionId -> launchLoadMessagesForEffect(sessionId) },
            emit = EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
        )
    }

    /** §R-19 P2-5: extracted from the former AppCore.loadMessagesForEffect so
     *  [loadSessions]'s onLoadMessages callback stays self-contained. Mirrors
     *  the AppCoreOrchestration.kt helper's body (which is preserved verbatim
     *  for AppCore's own dispatch helpers). */
    private fun launchLoadMessagesForEffect(sessionId: String) {
        launchLoadMessages(
            scope = appScope,
            repository = repository,
            slices = store.slices,
            sessionId = sessionId,
            resetLimit = true,
            settingsManager = settingsManager,
            onCacheWindow = sessionSwitcher::writeSessionWindow,
            emit = EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
        )
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
