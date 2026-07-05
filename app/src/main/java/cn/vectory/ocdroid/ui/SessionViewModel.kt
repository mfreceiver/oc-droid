package cn.vectory.ocdroid.ui

import androidx.lifecycle.ViewModel
import cn.vectory.ocdroid.util.runSuspendCatching
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * R-17 batch3 → batch3d: Session-list-domain ViewModel. Owns the sessionList +
 * unread slices + the session CRUD / select / open-tab / child-session /
 * pending-question / pending-permission management.
 *
 * **batch3d**: method bodies physically moved here from [AppCore]. The VM
 * calls its domain controller ([AppCore.sessionSwitcher]) and the
 * [MainViewModelSessionListActions] / [MainViewModelSessionMutationActions]
 * free functions directly — no `core.<method>()` self-bypass.
 *
 * State reads/writes flow through the shared [SharedStateStore] slices.
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    internal val core: AppCore,
) : ViewModel() {

    val sessionListFlow get() = core.sessionListFlow
    val unreadFlow get() = core.unreadFlow
    val chatFlow get() = core.chatFlow
    val composerFlow get() = core.composerFlow

    // ── Session-domain methods (bodies moved from AppCore) ──────────────────

    fun selectSession(sessionId: String) {
        core.sessionSwitcher.switchTo(sessionId)
    }

    fun openSubAgent(childSessionId: String) {
        val parentId = core.store.chatFlow.value.currentSessionId
        core.appScope.launch {
            val child = core.store.sessionListFlow.value.sessions.firstOrNull { it.id == childSessionId }
                ?: parentId?.let { pid -> core.store.sessionListFlow.value.childSessions[pid]?.find { it.id == childSessionId } }
                ?: core.store.sessionListFlow.value.childSessions.values.flatten().firstOrNull { it.id == childSessionId }
                ?: runSuspendCatching { core.repository.getSession(childSessionId).getOrNull() }.getOrNull()
            if (child != null) {
                core.writeSessionList { state -> state.copy(sessions = upsertSession(state.sessions, child)) }
                selectSession(childSessionId)
            } else {
                core.effectBus.uiEvents.tryEmit(UiEvent.Error("子任务会话不可用"))
            }
        }
    }

    fun closeSession(sessionId: String) {
        val isCurrent = core.store.chatFlow.value.currentSessionId == sessionId
        if (isCurrent) {
            core.settingsManager.setDraftText(sessionId, core.store.composerFlow.value.inputText)
        }
        val updated = core.settingsManager.openSessionIds.filter { it != sessionId }
        core.settingsManager.openSessionIds = updated
        val nextId = updated.firstOrNull()
        core.writeSessionList { it.copy(openSessionIds = updated) }
        core.writeUnread { it.copy(unreadSessions = it.unreadSessions - sessionId) }
        if (isCurrent && nextId == null) {
            core.settingsManager.currentSessionId = null
            core.writeChat { it.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap()) }
        }
        if (isCurrent && nextId == null) {
            core.writeComposer { it.copy(inputText = "") }
        }
        if (isCurrent && nextId != null) {
            selectSession(nextId)
        }
    }

    fun loadMoreSessions() {
        launchLoadMoreSessions(
            scope = core.appScope,
            repository = core.repository,
            slices = core.store.slices,
            onSelectSession = ::selectSession,
            emit = EventEmitter { event -> core.effectBus.uiEvents.tryEmit(event) },
        )
    }

    fun createSession(title: String? = null) {
        launchCreateSession(
            core.appScope, core.repository, core.store.slices, title, ::selectSession,
            EventEmitter { event -> core.effectBus.uiEvents.tryEmit(event) }
        )
    }

    fun createSessionInWorkdir(workdir: String) {
        val workdir = workdir.trim()
        core.repository.setCurrentDirectory(workdir)
        core.settingsManager.currentSessionId = null
        core.writeChat {
            it.copy(
                currentSessionId = null,
                messages = emptyList(),
                partsByMessage = emptyMap(),
                streamingPartTexts = emptyMap(),
                streamingReasoningPart = null,
            )
        }
        core.writeSessionList { it.copy(sessionTodos = emptyMap()) }
        core.writeChat { it.copy(currentModel = null) }
        core.writeComposer {
            it.copy(inputText = "", imageAttachments = emptyList(), draftWorkdir = workdir)
        }
        core.settingsManager.currentWorkdir = workdir
        core.settingsManager.addRecentWorkdir(workdir)
        core.appScope.launch {
            core.repository.getSessionsForDirectory(workdir)
                .onSuccess { sessions ->
                    core.writeSessionList { it.copy(directorySessions = it.directorySessions + (workdir to sessions)) }
                }
        }
    }

    fun forkSession(sessionId: String, messageId: String?) {
        launchForkSession(
            core.appScope, core.repository, core.store.slices, sessionId, messageId, ::selectSession,
            EventEmitter { event -> core.effectBus.uiEvents.tryEmit(event) }
        )
    }

    fun archiveSession(sessionId: String) {
        launchSetSessionArchived(
            core.appScope, core.repository, core.store.slices, core.settingsManager, sessionId, archived = true,
            EventEmitter { event -> core.effectBus.uiEvents.tryEmit(event) }
        )
    }

    fun restoreSession(sessionId: String) {
        launchSetSessionArchived(
            core.appScope, core.repository, core.store.slices, core.settingsManager, sessionId, archived = false,
            EventEmitter { event -> core.effectBus.uiEvents.tryEmit(event) }
        )
    }

    fun deleteSession(sessionId: String) {
        launchDeleteSession(
            core.appScope, core.repository, core.store.slices, core.settingsManager, sessionId, ::selectSession,
            EventEmitter { event -> core.effectBus.uiEvents.tryEmit(event) }
        )
    }

    fun refreshDirectorySessions(workdir: String) {
        val workdir = workdir.trim()
        if (workdir.isBlank()) return
        core.appScope.launch {
            core.repository.getSessionsForDirectory(workdir)
                .onSuccess { sessions ->
                    core.writeSessionList { it.copy(directorySessions = it.directorySessions + (workdir to sessions)) }
                }
        }
    }

    fun toggleSessionExpanded(sessionId: String) {
        core.writeSessionList { s ->
            val next = if (s.expandedSessionIds.contains(sessionId)) s.expandedSessionIds - sessionId
                       else s.expandedSessionIds + sessionId
            s.copy(expandedSessionIds = next)
        }
    }

    fun loadChildSessions(sessionId: String) {
        // §R-17 batch3d: body extracted as launchLoadChildSessions so both this
        // VM and AppCore's effect-dispatch handler share the same impl.
        launchLoadChildSessions(
            scope = core.appScope,
            repository = core.repository,
            slices = core.store.slices,
            sessionId = sessionId,
            tag = TAG,
        )
    }

    fun loadPendingQuestions() {
        launchLoadPendingQuestions(
            scope = core.appScope,
            repository = core.repository,
            slices = core.store.slices,
            tag = TAG,
        )
    }

    fun loadPendingPermissions() {
        launchLoadPendingPermissions(
            scope = core.appScope,
            repository = core.repository,
            slices = core.store.slices,
            tag = TAG,
        )
    }

    /** §R-17 batch3e: delegates to AppCore.loadSessionsForEffect (was a legacy
     *  shim). Used by session-list tests + the slice-sync smoke tests. */
    fun loadSessions() {
        core.loadSessionsForEffect()
    }

    /** §R-17 batch3e: loadInitialData lives on [ConnectionViewModel] for
     *  production, but session-list tests that verify the workdir-restore
     *  fan-out exercise it through this VM because they assert on
     *  sessionListFlow afterwards. */
    fun loadInitialData() {
        core.connectionCoordinator.loadInitialData()
    }

    /** Clears any in-progress composer draft (composer-domain). Routed through
     *  [ComposerController] which owns the draftWorkdir guard. */
    fun clearDraftIfActive() {
        core.composerController.clearDraftIfActive()
    }

    private companion object {
        private const val TAG = "SessionViewModel"
    }
}
