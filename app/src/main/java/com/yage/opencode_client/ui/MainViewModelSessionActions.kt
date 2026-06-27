package com.yage.opencode_client.ui

import com.yage.opencode_client.data.model.ComposerImageAttachment
import com.yage.opencode_client.data.model.Message
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun launchLoadSessions(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    settingsManager: SettingsManager,
    onSelectSession: (String) -> Unit,
    onLoadSessionStatus: () -> Unit,
    onLoadMessages: (String) -> Unit
) {
    scope.launch {
        val limit = MainViewModelTimings.sessionPageSize
        state.update {
            it.copy(
                loadedSessionLimit = limit,
                hasMoreSessions = true,
                isLoadingMoreSessions = false,
                isRefreshingSessions = true
            )
        }
        repository.getSessions(limit)
            .onSuccess { sessions ->
                state.update {
                    val mergedSessions = mergeRefreshedSessionsPreservingLocalActivity(sessions, it.sessions)
                    it.copy(
                        sessions = mergedSessions,
                        hasMoreSessions = mergedSessions.size >= limit,
                        isLoadingMoreSessions = false,
                        isRefreshingSessions = false
                    )
                }
                // Clean stale session IDs from openSessionIds after sessions are loaded
                val loadedSessionIds = state.value.sessions.map { s -> s.id }.toSet()
                val cleanedOpen = settingsManager.openSessionIds.filter { it in loadedSessionIds }
                if (cleanedOpen.size != settingsManager.openSessionIds.size) {
                    settingsManager.openSessionIds = cleanedOpen
                    state.update { it.copy(openSessionIds = cleanedOpen) }
                }
                val currentId = state.value.currentSessionId
                val refreshedSessions = state.value.sessions
                val hasCurrentSession = currentId != null && refreshedSessions.any { it.id == currentId }
                when {
                    // Skip auto-select when the user is mid-draft (a workdir
                    // has been chosen but no session created yet): selecting a
                    // session would discard the draft's repository workdir and
                    // hijack the empty chat page the user is composing into.
                    currentId == null && state.value.draftWorkdir == null && refreshedSessions.isNotEmpty() -> onSelectSession(refreshedSessions.first().id)
                    hasCurrentSession -> {
                        onLoadSessionStatus()
                        onLoadMessages(currentId!!)
                    }
                    state.value.draftWorkdir == null && refreshedSessions.isNotEmpty() -> {
                        onSelectSession(refreshedSessions.first().id)
                    }
                    else -> {
                        state.update { it.copy(currentSessionId = null, messages = emptyList()) }
                    }
                }
            }
            .onFailure { error ->
                state.update {
                    it.copy(
                        isLoadingMoreSessions = false,
                        isRefreshingSessions = false,
                        error = "Failed to load sessions: ${errorMessageOrFallback(error, "unknown error")}"
                    )
                }
            }
    }
}

internal fun launchLoadMoreSessions(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    onSelectSession: (String) -> Unit
) {
    var nextLimit = 0
    var shouldLaunch = false
    state.update { current ->
        if (!current.hasMoreSessions || current.isLoadingMoreSessions) {
            current
        } else {
            nextLimit = nextSessionFetchLimit(current.loadedSessionLimit)
            shouldLaunch = true
            current.copy(isLoadingMoreSessions = true)
        }
    }
    if (!shouldLaunch) return
    scope.launch {
        repository.getSessions(nextLimit)
            .onSuccess { sessions ->
                if (state.value.loadedSessionLimit > nextLimit) {
                    state.update { it.copy(isLoadingMoreSessions = false) }
                    return@onSuccess
                }
                state.update {
                    val mergedSessions = mergeRefreshedSessionsPreservingLocalActivity(sessions, it.sessions)
                    it.copy(
                        sessions = mergedSessions,
                        loadedSessionLimit = nextLimit,
                        hasMoreSessions = mergedSessions.size >= nextLimit,
                        isLoadingMoreSessions = false
                    )
                }
                val currentId = state.value.currentSessionId
                val refreshedSessions = state.value.sessions
                val hasCurrentSession = currentId != null && refreshedSessions.any { it.id == currentId }
                when {
                    currentId == null && refreshedSessions.isNotEmpty() -> onSelectSession(refreshedSessions.first().id)
                    hasCurrentSession -> Unit
                    refreshedSessions.isNotEmpty() -> onSelectSession(refreshedSessions.first().id)
                    else -> state.update { it.copy(currentSessionId = null, messages = emptyList()) }
                }
            }
            .onFailure { error ->
                state.update {
                    it.copy(
                        isLoadingMoreSessions = false,
                        error = "Failed to load more sessions: ${errorMessageOrFallback(error, "unknown error")}"
                    )
                }
            }
    }
}

internal fun launchLoadSessionStatus(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>
) {
    scope.launch {
        repository.getSessionStatus()
            .onSuccess { statuses ->
                state.update { it.copy(sessionStatuses = statuses) }
            }
            .onFailure { error ->
                reportNonFatalIssue("MainViewModel", "Failed to load session status", error)
            }
    }
}

internal fun selectSessionState(
    state: MutableStateFlow<AppState>,
    settingsManager: SettingsManager,
    sessionId: String
) {
    val oldSessionId = state.value.currentSessionId
    val currentInputText = state.value.inputText
    if (oldSessionId != null) {
        settingsManager.setDraftText(oldSessionId, currentInputText)
    }

    settingsManager.currentSessionId = sessionId
    val restoredDraft = settingsManager.getDraftText(sessionId)
    state.update {
        it.copy(
            currentSessionId = sessionId,
            messages = emptyList(),
            messageLimit = 30,
            inputText = restoredDraft
        )
    }
}

/**
 * Sync the repository's workdir directory context to the selected session's
 * directory. Looks up the session in the current state's session list so that
 * subsequent directory-scoped requests (file ops, prompt, session creation)
 * target the correct workdir. No-op when the session is not yet loaded.
 */
internal fun syncCurrentDirectoryForSession(
    state: MutableStateFlow<AppState>,
    repository: OpenCodeRepository,
    sessionId: String
) {
    val directory = state.value.sessions.firstOrNull { it.id == sessionId }?.directory
    repository.setCurrentDirectory(directory)
}

internal fun launchLoadMessages(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    resetLimit: Boolean = true,
    settingsManager: SettingsManager? = null
) {
    scope.launch {
        state.update { it.copy(isLoadingMessages = true) }
        val limit = if (resetLimit) 30 else state.value.messageLimit
        repository.getMessages(sessionId, limit)
            .onSuccess { messages ->
                if (sessionId == state.value.currentSessionId) {
                    val lastAssistant = messages.lastOrNull { it.info.isAssistant }
                    val inferredAgentName = lastAssistant?.info?.agent
                    val agentName = settingsManager?.getAgentForSession(sessionId) ?: inferredAgentName
                    state.update {
                        it.copy(
                            messages = messages,
                            messageLimit = limit,
                            isLoadingMessages = false,
                            selectedAgentName = agentName ?: it.selectedAgentName
                        )
                    }
                } else {
                    state.update { it.copy(isLoadingMessages = false) }
                }
            }
            .onFailure { error ->
                if (sessionId == state.value.currentSessionId) {
                    state.update {
                        it.copy(
                            isLoadingMessages = false,
                            error = "Failed to load messages: ${errorMessageOrFallback(error, "unknown error")}"
                        )
                    }
                } else {
                    state.update { it.copy(isLoadingMessages = false) }
                }
            }

        // Best-effort: load session todos after messages (matches iOS behavior).
        // Fails silently in test mocks where the endpoint isn't set up.
        try {
            repository.getSessionTodos(sessionId)
                .onSuccess { todos ->
                    state.update { it.copy(sessionTodos = it.sessionTodos + (sessionId to todos)) }
                }
        } catch (_: Exception) {}
    }
}

internal fun launchLoadMessagesWithRetry(
    scope: CoroutineScope,
    sessionId: String,
    state: MutableStateFlow<AppState>,
    resetLimit: Boolean = true,
    onLoadMessages: (String, Boolean) -> Unit
) {
    scope.launch {
        delay(MainViewModelTimings.messageRetryDelayMs)
        if (sessionId == state.value.currentSessionId) {
            onLoadMessages(sessionId, resetLimit)
        }
    }
}

internal fun launchLoadMoreMessages(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String
) {
    if (state.value.isLoadingMessages) return
    val newLimit = state.value.messageLimit + 30
    scope.launch {
        state.update { it.copy(isLoadingMessages = true) }
        repository.getMessages(sessionId, newLimit)
            .onSuccess { messages ->
                if (sessionId == state.value.currentSessionId) {
                    state.update {
                        it.copy(
                            messages = messages,
                            messageLimit = newLimit,
                            isLoadingMessages = false
                        )
                    }
                } else {
                    state.update { it.copy(isLoadingMessages = false) }
                }
            }
            .onFailure {
                if (sessionId == state.value.currentSessionId) {
                    reportNonFatalIssue("MainViewModel", "Failed to load more messages")
                    state.update { it.copy(isLoadingMessages = false) }
                }
            }
    }
}

internal fun launchLoadProviders(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    onNonFatalError: (String, Throwable?) -> Unit
) {
    scope.launch {
        repository.getProviders()
            .onSuccess { providers ->
                state.update { it.copy(providers = providers) }
            }
            .onFailure { error ->
                onNonFatalError("Failed to load providers", error)
            }
    }
}

internal fun launchCreateSession(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    title: String?,
    onSelectSession: (String) -> Unit
) {
    scope.launch {
        repository.createSession(title)
            .onSuccess { session ->
                state.update { it.copy(sessions = upsertSession(it.sessions, session)) }
                onSelectSession(session.id)
            }
            .onFailure { error ->
                state.update { it.copy(error = "Failed to create session: ${errorMessageOrFallback(error, "unknown error")}") }
            }
    }
}

internal fun launchForkSession(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    messageId: String?,
    onSelectSession: (String) -> Unit
) {
    scope.launch {
        repository.forkSession(sessionId, messageId)
            .onSuccess { session ->
                state.update { it.copy(sessions = upsertSession(it.sessions, session)) }
                onSelectSession(session.id)
            }
            .onFailure { error ->
                state.update { it.copy(error = "Failed to fork session: ${errorMessageOrFallback(error, "unknown error")}") }
            }
    }
}

internal fun launchSetSessionArchived(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    archived: Boolean
) {
    scope.launch {
        val archivedValue = if (archived) System.currentTimeMillis() else -1L
        val ids = sessionSubtreeIds(state.value.sessions, sessionId, parentFirst = !archived)
        for (id in ids) {
            repository.updateSessionArchived(id, archivedValue)
                .onSuccess { updated ->
                    state.update { current ->
                        current.copy(sessions = current.sessions.map { session -> if (session.id == id) updated else session })
                    }
                }
                .onFailure { error ->
                    state.update {
                        it.copy(error = "Failed to ${if (archived) "archive" else "restore"} session: ${errorMessageOrFallback(error, "unknown error")}")
                    }
                    return@launch
                }
        }
    }
}

private fun sessionSubtreeIds(sessions: List<com.yage.opencode_client.data.model.Session>, rootId: String, parentFirst: Boolean): List<String> {
    val childrenByParent = sessions.groupBy { it.parentId }
    fun collect(id: String): List<String> {
        val children = childrenByParent[id].orEmpty().flatMap { collect(it.id) }
        return if (parentFirst) listOf(id) + children else children + id
    }
    return collect(rootId)
}

internal fun launchDeleteSession(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    onSelectSession: (String) -> Unit
) {
    scope.launch {
        repository.deleteSession(sessionId)
            .onSuccess {
                val newSessions = state.value.sessions.filter { it.id != sessionId }
                state.update { it.copy(sessions = newSessions) }
                if (state.value.currentSessionId == sessionId) {
                    val newCurrent = newSessions.firstOrNull()?.id
                    if (newCurrent != null) {
                        onSelectSession(newCurrent)
                    } else {
                        state.update { it.copy(currentSessionId = null, messages = emptyList()) }
                    }
                }
            }
            .onFailure { error ->
                state.update { it.copy(error = "Failed to delete session: ${errorMessageOrFallback(error, "unknown error")}") }
            }
    }
}

internal fun launchSendMessage(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    text: String,
    attachments: List<ComposerImageAttachment> = emptyList(),
    agent: String,
    model: Message.ModelInfo?,
    onRefreshMessages: (String, Boolean) -> Unit,
    onRefreshSessions: () -> Unit,
    onSuccess: (() -> Unit)? = null,
    onComplete: (() -> Unit)? = null
) {
    scope.launch {
        repository.sendMessage(sessionId, text, agent, model, attachments = attachments)
            .onSuccess {
                state.update {
                    it.copy(
                        inputText = "",
                        error = null,
                        sessions = bumpSessionUpdated(it.sessions, sessionId, System.currentTimeMillis()),
                        sessionStatuses = it.sessionStatuses + (sessionId to com.yage.opencode_client.data.model.SessionStatus(type = "busy"))
                    )
                }
                onSuccess?.invoke()
                onRefreshSessions()
                onRefreshMessages(sessionId, true)
                launch {
                    delay(MainViewModelTimings.messageRefreshDelayMs)
                    onRefreshSessions()
                    onRefreshMessages(sessionId, false)
                }
            }
            .onFailure { error ->
                state.update { it.copy(error = errorMessageOrFallback(error, "Failed to send message")) }
            }
        onComplete?.invoke()
    }
}
