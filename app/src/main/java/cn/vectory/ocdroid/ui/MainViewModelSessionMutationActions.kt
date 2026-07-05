package cn.vectory.ocdroid.ui

/**
 * §R-17 batch3d: Domain orchestration free functions. These are NOT the deleted
 * batch-2 AppState mirror helpers (aggregateFromSlices/syncSlicesFromAppState etc.).
 * They are coroutine-launch helpers called by the domain ViewModels and AppCore
 * orchestration extensions to perform async operations (load/refresh/mutate).
 * Future cleanup (batch3e+): may be inlined into individual VM private methods.
 */

import cn.vectory.ocdroid.data.model.ComposerImageAttachment
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun launchCreateSession(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    title: String?,
    onSelectSession: (String) -> Unit,
    emit: EventEmitter = EventEmitter { }
) {

    scope.launch {
        repository.createSession(title)
            .onSuccess { session ->
                slices.sessionList.update { sl -> sl.copy(sessions = upsertSession(sl.sessions, session)) }
                onSelectSession(session.id)
            }
            .onFailure { error ->
                emit.emit(UiEvent.Error("Failed to create session: ${errorMessageOrFallback(error, "unknown error")}"))
            }
    }
}

internal fun launchForkSession(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    sessionId: String,
    messageId: String?,
    onSelectSession: (String) -> Unit,
    emit: EventEmitter = EventEmitter { }
) {

    scope.launch {
        repository.forkSession(sessionId, messageId)
            .onSuccess { session ->
                slices.sessionList.update { sl -> sl.copy(sessions = upsertSession(sl.sessions, session)) }
                onSelectSession(session.id)
            }
            .onFailure { error ->
                emit.emit(UiEvent.Error("Failed to fork session: ${errorMessageOrFallback(error, "unknown error")}"))
            }
    }
}

internal fun launchSetSessionArchived(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    settingsManager: SettingsManager,
    sessionId: String,
    archived: Boolean,
    emit: EventEmitter = EventEmitter { }
) {

    scope.launch {
        val archivedValue = if (archived) System.currentTimeMillis() else -1L
        // §R-17 batch2 step e final: slice-only reads (slices are the sole
        // authoritative store).
        val ids = sessionSubtreeIds(slices.sessionList.value.sessions, sessionId, parentFirst = !archived)
        for (id in ids) {
            repository.updateSessionArchived(id, archivedValue)
                .onSuccess { updated ->
                    // §R-17 batch2 step e final: fresh capture after the suspend;
                    // used for all reads in this synchronous onSuccess block.
                    val currentSessions = slices.sessionList.value.sessions
                    val currentDirSessions = slices.sessionList.value.directorySessions
                    val currentOpenIds = slices.sessionList.value.openSessionIds
                    val currentCurrentId = slices.chat.value.currentSessionId

                    val newSessions = currentSessions.map { session -> if (session.id == id) updated else session }
                    // Keep directorySessions in sync so an archived session disappears
                    // from the connected-projects list immediately (refreshDirectorySessions
                    // repopulates this map on expand, but the local copy must not hold a
                    // stale unarchived version).
                    val newDirSessions = currentDirSessions.mapValues { (_, list) ->
                        list.map { session -> if (session.id == id) updated else session }
                    }
                    val isArchive = archivedValue > 0
                    // Archived: evict the id from the open-tabs list (browser-tab close
                    // equivalent for archive) and persist via the existing SettingsManager
                    // setter. Mirrors closeSession's currentSessionId fallback: if the
                    // archived session was current, clear it (and the loaded message window)
                    // so the chat view falls back to the empty state instead of pointing
                    // at a now-archived session.
                    val newOpenIds = if (isArchive) currentOpenIds.filter { it != id } else currentOpenIds
                    if (isArchive && newOpenIds != currentOpenIds) {
                        settingsManager.openSessionIds = newOpenIds
                    }
                    val clearCurrent = isArchive && currentCurrentId == id
                    if (clearCurrent) {
                        settingsManager.currentSessionId = null
                    }

                    slices.sessionList.update {
                        it.copy(
                            sessions = newSessions,
                            directorySessions = newDirSessions,
                            openSessionIds = newOpenIds
                        )
                    }
                    if (clearCurrent) {
                        // Cross-slice: currentSessionId/messages/partsByMessage are
                        // chat-slice fields; the rest above are sessionList.
                        slices.chat.update {
                            it.copy(
                                currentSessionId = null,
                                messages = emptyList(),
                                partsByMessage = emptyMap()
                            )
                        }
                    }
                }
                .onFailure { error ->
                    emit.emit(UiEvent.Error("Failed to ${if (archived) "archive" else "restore"} session: ${errorMessageOrFallback(error, "unknown error")}"))
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
    slices: SliceFlows,
    settingsManager: SettingsManager,
    sessionId: String,
    onSelectSession: (String) -> Unit,
    emit: EventEmitter = EventEmitter { }
) {

    scope.launch {
        repository.deleteSession(sessionId)
            .onSuccess {
                // Purge the deleted id from both the global sessions list AND
                // directorySessions. If the session was originally surfaced via
                // a connected workdir (createSessionInWorkdir's directory fetch),
                // leaving it in directorySessions would let SessionsScreen's
                // union render it — and re-selecting it would upsert a ghost
                // copy of an already-deleted server session (#10).
                // §R-17 batch2 step e final: slice-only reads.
                val currentSessions = slices.sessionList.value.sessions
                val currentDirSessions = slices.sessionList.value.directorySessions
                val newSessions = currentSessions.filter { s -> s.id != sessionId }
                val newDirSessions = currentDirSessions
                    .mapValues { (_, list) -> list.filter { s -> s.id != sessionId } }
                    .filterValues { it.isNotEmpty() }
                slices.sessionList.update { sl -> sl.copy(sessions = newSessions, directorySessions = newDirSessions) }
                val currentId = slices.chat.value.currentSessionId
                if (currentId == sessionId) {
                    val newCurrent = newSessions.firstOrNull()?.id
                    if (newCurrent != null) {
                        onSelectSession(newCurrent)
                    } else {
                        // #10: no remaining session — clear the persisted
                        // currentSessionId too, otherwise a stale id survives
                        // in SettingsManager and would be restored on next
                        // launch / host switch, pointing at a deleted session.
                        settingsManager.currentSessionId = null
                        slices.chat.update { c -> c.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap()) }
                    }
                }
            }
            .onFailure { error ->
                emit.emit(UiEvent.Error("Failed to delete session: ${errorMessageOrFallback(error, "unknown error")}"))
            }
    }
}

internal fun launchSendMessage(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    composerFlow: MutableStateFlow<ComposerState>,
    slices: SliceFlows,
    sessionId: String,
    text: String,
    attachments: List<ComposerImageAttachment> = emptyList(),
    agent: String,
    model: Message.ModelInfo?,
    onRefreshMessages: (String, Boolean) -> Unit,
    onRefreshSessions: () -> Unit,
    onSuccess: (() -> Unit)? = null,
    onComplete: (() -> Unit)? = null,
    emit: EventEmitter = EventEmitter { }
) {

    scope.launch {
        repository.sendMessage(sessionId, text, agent, model, attachments = attachments)
            .onSuccess {
                // §R-17 batch2 step e final: slice-only reads.
                val currentSessions = slices.sessionList.value.sessions
                val currentStatuses = slices.sessionList.value.sessionStatuses
                // §append-safe (glmer MAJOR-1): inputText is cleared
                // synchronously at dispatch time, so do NOT touch it here —
                // wiping now would destroy a follow-up the user typed during
                // the in-flight prompt_async window (the core send-while-
                // running workflow).
                val newSessions = bumpSessionUpdated(currentSessions, sessionId, System.currentTimeMillis())
                val newStatuses = currentStatuses + (sessionId to cn.vectory.ocdroid.data.model.SessionStatus(type = "busy"))
                slices.sessionList.update { sl -> sl.copy(sessions = newSessions, sessionStatuses = newStatuses) }
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
                // → UiEvent, inputText → composer slice.
                // Restore the failed prompt only if the user has not typed
                // something new since the synchronous dispatch clear.
                val currentInput = composerFlow.value.inputText
                val restored = if (currentInput.isBlank()) text else currentInput
                emit.emit(UiEvent.Error(errorMessageOrFallback(error, "Failed to send message")))
                composerFlow.update { it.copy(inputText = restored) }
            }
        onComplete?.invoke()
    }
}
