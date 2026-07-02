package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.ComposerImageAttachment
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal fun launchCreateSession(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    title: String?,
    onSelectSession: (String) -> Unit
, slices: SliceFlows? = null) {

    scope.launch {
        repository.createSession(title)
            .onSuccess { session ->
                state.updateAndSync(slices) { it.copy(sessions = upsertSession(it.sessions, session)) }
                onSelectSession(session.id)
            }
            .onFailure { error ->
                state.updateAndSync(slices) { it.copy(error = "Failed to create session: ${errorMessageOrFallback(error, "unknown error")}") }
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
, slices: SliceFlows? = null) {

    scope.launch {
        repository.forkSession(sessionId, messageId)
            .onSuccess { session ->
                state.updateAndSync(slices) { it.copy(sessions = upsertSession(it.sessions, session)) }
                onSelectSession(session.id)
            }
            .onFailure { error ->
                state.updateAndSync(slices) { it.copy(error = "Failed to fork session: ${errorMessageOrFallback(error, "unknown error")}") }
            }
    }
}

internal fun launchSetSessionArchived(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    settingsManager: SettingsManager,
    sessionId: String,
    archived: Boolean
, slices: SliceFlows? = null) {

    scope.launch {
        val archivedValue = if (archived) System.currentTimeMillis() else -1L
        val ids = sessionSubtreeIds(state.value.sessions, sessionId, parentFirst = !archived)
        for (id in ids) {
            repository.updateSessionArchived(id, archivedValue)
                .onSuccess { updated ->
                    state.updateAndSync(slices) { current ->
                        val newSessions = current.sessions.map { session -> if (session.id == id) updated else session }
                        // Keep directorySessions in sync so an archived
                        // session disappears from the connected-projects
                        // list immediately (refreshDirectorySessions
                        // repopulates this map on expand, but the local
                        // copy must not hold a stale unarchived version).
                        val newDirSessions = current.directorySessions.mapValues { (_, list) ->
                            list.map { session -> if (session.id == id) updated else session }
                        }
                        if (archivedValue > 0) {
                            // Archived: evict the id from the open-tabs list
                            // (browser-tab close equivalent for archive) and
                            // persist via the existing SettingsManager setter.
                            // Mirrors closeSession's currentSessionId fallback:
                            // if the archived session was current, clear it
                            // (and the loaded message window) so the chat view
                            // falls back to the empty state instead of
                            // pointing at a now-archived session.
                            val newOpenIds = current.openSessionIds.filter { it != id }
                            if (newOpenIds != current.openSessionIds) {
                                settingsManager.openSessionIds = newOpenIds
                            }
                            if (current.currentSessionId == id) {
                                settingsManager.currentSessionId = null
                                current.copy(
                                    sessions = newSessions,
                                    directorySessions = newDirSessions,
                                    openSessionIds = newOpenIds,
                                    currentSessionId = null,
                                    messages = emptyList(),
                                    partsByMessage = emptyMap()
                                )
                            } else {
                                current.copy(
                                    sessions = newSessions,
                                    directorySessions = newDirSessions,
                                    openSessionIds = newOpenIds
                                )
                            }
                        } else {
                            current.copy(sessions = newSessions, directorySessions = newDirSessions)
                        }
                    }
                }
                .onFailure { error ->
                    state.updateAndSync(slices) {
                        it.copy(error = "Failed to ${if (archived) "archive" else "restore"} session: ${errorMessageOrFallback(error, "unknown error")}")
                    }
                    return@launch
                }
        }
    }
}

private fun sessionSubtreeIds(sessions: List<cn.vectory.ocdroid.data.model.Session>, rootId: String, parentFirst: Boolean): List<String> {
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
    settingsManager: SettingsManager,
    sessionId: String,
    onSelectSession: (String) -> Unit
, slices: SliceFlows? = null) {

    scope.launch {
        repository.deleteSession(sessionId)
            .onSuccess {
                // Purge the deleted id from both the global sessions list AND
                // directorySessions. If the session was originally surfaced via
                // a connected workdir (createSessionInWorkdir's directory fetch),
                // leaving it in directorySessions would let SessionsScreen's
                // union render it — and re-selecting it would upsert a ghost
                // copy of an already-deleted server session (#10).
                state.updateAndSync(slices) {
                    val newSessions = it.sessions.filter { s -> s.id != sessionId }
                    val newDirSessions = it.directorySessions
                        .mapValues { (_, list) -> list.filter { s -> s.id != sessionId } }
                        .filterValues { it.isNotEmpty() }
                    it.copy(sessions = newSessions, directorySessions = newDirSessions)
                }
                if (state.value.currentSessionId == sessionId) {
                    val newCurrent = state.value.sessions.firstOrNull()?.id
                    if (newCurrent != null) {
                        onSelectSession(newCurrent)
                    } else {
                        // #10: no remaining session — clear the persisted
                        // currentSessionId too, otherwise a stale id survives
                        // in SettingsManager and would be restored on next
                        // launch / host switch, pointing at a deleted session.
                        settingsManager.currentSessionId = null
                        state.updateAndSync(slices) { it.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap()) }
                    }
                }
            }
            .onFailure { error ->
                state.updateAndSync(slices) { it.copy(error = "Failed to delete session: ${errorMessageOrFallback(error, "unknown error")}") }
            }
    }
}

internal fun launchSendMessage(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    composerFlow: MutableStateFlow<ComposerState>,
    sessionId: String,
    text: String,
    attachments: List<ComposerImageAttachment> = emptyList(),
    agent: String,
    model: Message.ModelInfo?,
    onRefreshMessages: (String, Boolean) -> Unit,
    onRefreshSessions: () -> Unit,
    onSuccess: (() -> Unit)? = null,
    onComplete: (() -> Unit)? = null
, slices: SliceFlows? = null) {

    scope.launch {
        repository.sendMessage(sessionId, text, agent, model, attachments = attachments)
            .onSuccess {
                state.updateAndSync(slices) {
                    // §append-safe (glmer MAJOR-1): inputText is cleared
                    // synchronously at dispatch time, so do NOT touch it here —
                    // wiping now would destroy a follow-up the user typed during
                    // the in-flight prompt_async window (the core send-while-
                    // running workflow).
                    it.copy(
                        error = null,
                        sessions = bumpSessionUpdated(it.sessions, sessionId, System.currentTimeMillis()),
                        sessionStatuses = it.sessionStatuses + (sessionId to cn.vectory.ocdroid.data.model.SessionStatus(type = "busy"))
                    )
                }
                onSuccess?.invoke()
                onRefreshSessions()
                // §15.1 (review N6): the post-send 1200ms double-refresh is
                // gone — SSE will deliver `message.updated` (server 1.17.11+
                // emits message.updated, not message.created, for new messages;
                // see the insert-if-absent handler in MainViewModelSyncActions)
                // and a foreground catch-up covers any dropped event. The single
                // immediate reload here is the legacy first-paint path that
                // selectSession/sendMessage use to bypass the debounce.
                onRefreshMessages(sessionId, true)
            }
            .onFailure { error ->
                // §R-17 M3: read composer slice for the restore-decision; error
                // → _state, inputText → composer slice (mirror kept in sync).
                // Restore the failed prompt only if the user has not typed
                // something new since the synchronous dispatch clear.
                val currentInput = composerFlow.value.inputText
                val restored = if (currentInput.isBlank()) text else currentInput
                state.updateAndSync(slices) { s ->
                    s.copy(error = errorMessageOrFallback(error, "Failed to send message"))
                }
                applyComposerSlice(state, composerFlow) { it.copy(inputText = restored) }
            }
        onComplete?.invoke()
    }
}
