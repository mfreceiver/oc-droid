package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.util.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * §R-17 batch3d: cross-domain orchestration extracted from [AppCore].
 *
 * AppCore is the application-scoped engine that owns the 6 controllers + the
 * shared [SharedStateStore] + the [SharedEffectBus] subscription. The
 * orchestration methods that span 3+ domains (send-message, deep-link open,
 * /clear command, full-stack reset, global cold-start refresh, dispatch
 * helpers) live HERE as `internal` extensions on [AppCore] so [AppCore] itself
 * stays a thin engine (~280 lines: constructor + controllers + write helpers
 * + init + dispatchEffect + cleanup + test hooks).
 *
 * Each extension reaches the controllers / store / settingsManager /
 * repository / appScope directly through [AppCore]'s `internal` surface —
 * never through a sibling VM (AppCore cannot reference HiltViewModels).
 *
 * The dispatch helpers (`loadMessagesForEffect`, `loadSessionsForEffect`,
 * `performGlobalColdStartRefresh`, `catchUpAfterDisconnectOrForeground`) are
 * also `internal` so [AppCore.dispatchEffect] can route the matching
 * [ControllerEffect] branches to them. They call the same free functions
 * (`launchLoadMessages`, `launchLoadSessions`, ...) the matching domain VM
 * uses.
 */

// ════════════════════════════════════════════════════════════════════════════
// Cross-domain orchestration (the ~6 methods that span 3+ domains)
// ════════════════════════════════════════════════════════════════════════════

/** nav → session-list → chat. Used by the notification deep-link path. */
internal fun AppCore.openSessionFromDeepLink(sessionId: String) {
    appScope.launch {
        if (store.sessionListFlow.value.sessions.none { it.id == sessionId }) {
            val fetched = withContext(Dispatchers.IO) {
                runSuspendCatching { repository.getSession(sessionId).getOrNull() }.getOrNull()
            }
            if (fetched != null) {
                writeSessionList { st -> st.copy(sessions = upsertSession(st.sessions, fetched)) }
            }
        }
        selectSessionForEffect(sessionId)
    }
}

/**
 * `/clear` (composer reset + fresh session) and other slash commands.
 * Cross-domain: composer (input clear) + session-list (create) + chat
 * (target session). Routes the slash command to the right primitive.
 */
internal fun AppCore.executeCommand(command: String, arguments: String) {
    val cmd = command.removePrefix("/").trim().lowercase(Locale.getDefault())
    if (cmd.isEmpty()) return
    when (cmd) {
        "clear" -> {
            composerController.setInputText("")
            val workdir = repository.getCurrentDirectory()
                ?: currentSession(store.sessionListFlow.value.sessions, store.chatFlow.value.currentSessionId)?.directory
            if (workdir != null) createSessionInWorkdirForEffect(workdir) else createSessionForEffect()
        }
        else -> {
            val sessionId = store.chatFlow.value.currentSessionId ?: run {
                effectBus.uiEvents.tryEmit(UiEvent.Error("Open or create a session before running /$cmd"))
                return
            }
            composerController.setInputText("")
            appScope.launch {
                repository.executeCommand(sessionId, cmd, arguments)
                    .onFailure { error ->
                        effectBus.uiEvents.tryEmit(UiEvent.Error(errorMessageOrFallback(error, "Command /$cmd failed")))
                    }
            }
        }
    }
}

/** composer → chat → session creation. The full send-while-in-draft path. */
internal fun AppCore.sendMessage() {
    val draftWorkdir = store.composerFlow.value.draftWorkdir
    val existingSessionId = store.chatFlow.value.currentSessionId
    val text = store.composerFlow.value.inputText.trim()
    val attachments = store.composerFlow.value.imageAttachments
    if (text.isEmpty() && attachments.isEmpty()) return

    if (draftWorkdir != null && existingSessionId == null) {
        writeComposer { it.copy(draftWorkdir = null) }
        appScope.launch {
            repository.createSession(title = null)
                .onSuccess { session ->
                    val openIds = (listOf(session.id) + settingsManager.openSessionIds).distinct().take(8)
                    settingsManager.openSessionIds = openIds
                    val now = System.currentTimeMillis()
                    writeSessionList { state ->
                        state.copy(sessions = upsertSession(state.sessions, session), openSessionIds = openIds)
                    }
                    writeChat { it.copy(currentSessionId = session.id) }
                    writeUnread { it.copy(unreadSessions = it.unreadSessions - session.id, lastViewedTime = it.lastViewedTime + (session.id to now)) }
                    writeComposer { it.copy(draftWorkdir = null) }
                    settingsManager.currentSessionId = session.id
                    store.chatFlow.value.currentModel?.let { model ->
                        settingsManager.setModelForSession(session.id, model.providerId, model.modelId)
                    }
                    persistSessionCache(
                        settingsManager = settingsManager,
                        sessions = store.sessionListFlow.value.sessions,
                        openIds = store.sessionListFlow.value.openSessionIds,
                        currentId = session.id,
                        currentWorkdir = settingsManager.currentWorkdir,
                    )
                    dispatchSendMessage(session.id)
                    scheduleTitleRefreshAfterFirstMessage(session.id)
                }
                .onFailure { error ->
                    writeComposer { it.copy(draftWorkdir = draftWorkdir) }
                    effectBus.uiEvents.tryEmit(UiEvent.Error("Failed to create session in $draftWorkdir: ${error.message ?: "unknown error"}"))
                }
        }
        return
    }

    val sessionId = existingSessionId ?: return
    if (store.composerFlow.value.sendingSessionIds.contains(sessionId)) return
    dispatchSendMessage(sessionId)
}

/** Full-stack local reset (host → connection → session purge → chat clear). */
internal fun AppCore.resetLocalDataAndResync() { hostProfileController.resetLocalDataAndResync() }

// ── sendMessage helpers (private to this file) ─────────────────────────────

private fun AppCore.scheduleTitleRefreshAfterFirstMessage(sessionId: String) {
    appScope.launch {
        delay(MainViewModelTimings.titleRefreshDelayMs)
        repository.getSession(sessionId)
            .onSuccess { refreshed ->
                writeSessionList { state ->
                    state.copy(
                        sessions = upsertSession(state.sessions, refreshed),
                        directorySessions = state.directorySessions.mapValues { (_, list) ->
                            list.map { if (it.id == sessionId) refreshed else it }
                        },
                    )
                }
            }
            .onFailure { }
    }
}

private fun AppCore.dispatchSendMessage(sessionId: String) {
    val composer = store.composerFlow.value
    if (composer.sendingSessionIds.contains(sessionId)) return
    val text = composer.inputText.trim()
    val attachments = composer.imageAttachments
    if (text.isEmpty() && attachments.isEmpty()) return

    writeComposer { state -> state.copy(sendingSessionIds = state.sendingSessionIds + sessionId) }
    settingsManager.setDraftText(sessionId, "")
    writeComposer { it.copy(inputText = "") }

    val currentSession = currentSession(store.sessionListFlow.value.sessions, store.chatFlow.value.currentSessionId)

    fun dispatchSend() {
        val agent = settingsManager.getAgentForSession(sessionId) ?: store.settingsFlow.value.selectedAgentName
        val model: Message.ModelInfo? = settingsManager.getModelForSession(sessionId) ?: store.chatFlow.value.currentModel
        launchSendMessage(
            scope = appScope,
            repository = repository,
            composerFlow = store.composerFlow,
            slices = store.slices,
            sessionId = sessionId,
            text = text,
            attachments = attachments,
            agent = agent,
            model = model,
            onRefreshMessages = { sid, reset -> loadMessagesWithRetry(sid, reset) },
            onRefreshSessions = { loadSessionsForEffect() },
            onSuccess = {
                settingsManager.setDraftText(sessionId, "")
                writeComposer { it.copy(imageAttachments = emptyList()) }
            },
            onComplete = {
                writeComposer { state -> state.copy(sendingSessionIds = state.sendingSessionIds - sessionId) }
            },
            emit = EventEmitter { event -> effectBus.uiEvents.tryEmit(event) },
        )
    }

    if (currentSession?.isArchived == true) {
        appScope.launch {
            repository.updateSessionArchived(sessionId, -1L)
                .onSuccess { updated ->
                    writeSessionList { state ->
                        state.copy(sessions = state.sessions.map { session -> if (session.id == sessionId) updated else session })
                    }
                    dispatchSend()
                }
                .onFailure { error ->
                    val currentInput = store.composerFlow.value.inputText
                    val restored = if (currentInput.isBlank()) text else currentInput
                    if (restored != currentInput) settingsManager.setDraftText(sessionId, restored)
                    effectBus.uiEvents.tryEmit(UiEvent.Error("Failed to restore session: ${errorMessageOrFallback(error, "unknown error")}"))
                    writeComposer { c ->
                        c.copy(sendingSessionIds = c.sendingSessionIds - sessionId, inputText = restored)
                    }
                }
        }
        return
    }
    dispatchSend()
}

private fun AppCore.loadMessagesWithRetry(sessionId: String, resetLimit: Boolean = true) {
    launchLoadMessagesWithRetry(appScope, sessionId, store.slices, resetLimit) { sid, reset ->
        loadMessagesForEffect(sid, reset)
    }
}

// ════════════════════════════════════════════════════════════════════════════
// Dispatch helpers (one per ControllerEffect branch + the cross-domain
// callers). Each calls the same primitive the matching VM method uses.
// ════════════════════════════════════════════════════════════════════════════

internal fun AppCore.performGlobalColdStartRefresh(currentId: String) {
    if (store.chatFlow.value.isLoadingMessages) return
    sessionSwitcher.clearSessionWindowCache()
    writeChat { it.copy(refreshNonce = it.refreshNonce + 1) }
    writeChat {
        it.copy(
            streamingPartTexts = emptyMap(),
            streamingReasoningPart = null,
            gapInfo = null,
            staleNotice = false,
            messages = emptyList(),
            partsByMessage = emptyMap(),
            olderMessagesCursor = null,
            hasMoreMessages = true,
        )
    }
    loadMessagesForEffect(currentId, resetLimit = true)
}

internal fun AppCore.catchUpAfterDisconnectOrForeground(sessionId: String) {
    launchCatchUp(
        scope = appScope,
        repository = repository,
        slices = store.slices,
        sessionId = sessionId,
        settingsManager = settingsManager,
        onCacheWindow = ::writeSessionWindow,
    )
}

/**
 * §R-17 batch3d: dispatch helper. Routes a message-window load through the
 * shared [launchLoadMessages] free function — same impl as
 * [ChatViewModel.loadMessages], callable from [AppCore.dispatchEffect] +
 * [performGlobalColdStartRefresh] + [loadMessagesWithRetry] (AppCore cannot
 * reference [ChatViewModel]).
 */
internal fun AppCore.loadMessagesForEffect(sessionId: String, resetLimit: Boolean) {
    launchLoadMessages(
        scope = appScope,
        repository = repository,
        slices = store.slices,
        sessionId = sessionId,
        resetLimit = resetLimit,
        settingsManager = settingsManager,
        onCacheWindow = ::writeSessionWindow,
        settingsFlow = store.settingsFlow,
        emit = EventEmitter { event -> effectBus.uiEvents.tryEmit(event) },
    )
}

/** §R-17 batch3d: dispatch helper for the LoadSessions / RefreshSessions effects. */
internal fun AppCore.loadSessionsForEffect() {
    launchLoadSessions(
        scope = appScope,
        repository = repository,
        slices = store.slices,
        settingsManager = settingsManager,
        onSelectSession = { selectSessionForEffect(it) },
        onLoadSessionStatus = { launchLoadSessionStatus(appScope, repository, store.slices) },
        onLoadMessages = { sessionId -> loadMessagesForEffect(sessionId, resetLimit = true) },
        emit = EventEmitter { event -> effectBus.uiEvents.tryEmit(event) },
    )
}

private fun AppCore.selectSessionForEffect(sessionId: String) {
    sessionSwitcher.switchTo(sessionId)
}

private fun AppCore.createSessionForEffect(title: String? = null) {
    launchCreateSession(appScope, repository, store.slices, title, { selectSessionForEffect(it) }, EventEmitter { effectBus.uiEvents.tryEmit(it) })
}

private fun AppCore.createSessionInWorkdirForEffect(workdir: String) {
    val workdir = workdir.trim()
    repository.setCurrentDirectory(workdir)
    settingsManager.currentSessionId = null
    writeChat {
        it.copy(
            currentSessionId = null,
            messages = emptyList(),
            partsByMessage = emptyMap(),
            streamingPartTexts = emptyMap(),
            streamingReasoningPart = null,
        )
    }
    writeSessionList { it.copy(sessionTodos = emptyMap()) }
    writeChat { it.copy(currentModel = null) }
    writeComposer {
        it.copy(inputText = "", imageAttachments = emptyList(), draftWorkdir = workdir)
    }
    settingsManager.currentWorkdir = workdir
    settingsManager.addRecentWorkdir(workdir)
    appScope.launch {
        repository.getSessionsForDirectory(workdir)
            .onSuccess { sessions ->
                writeSessionList { it.copy(directorySessions = it.directorySessions + (workdir to sessions)) }
            }
    }
}
