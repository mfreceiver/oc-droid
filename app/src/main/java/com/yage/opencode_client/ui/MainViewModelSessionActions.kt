package com.yage.opencode_client.ui

import com.yage.opencode_client.data.model.ComposerImageAttachment
import com.yage.opencode_client.data.model.Message
import com.yage.opencode_client.data.model.Part
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
                    val mergedSessions = mergeRefreshedSessionsPreservingLocalActivity(
                        sessions,
                        it.sessions,
                        it.currentSessionId,
                        it.openSessionIds.toSet()
                    )
                    it.copy(
                        sessions = mergedSessions,
                        hasMoreSessions = mergedSessions.size >= limit,
                        isLoadingMoreSessions = false,
                        isRefreshingSessions = false
                    )
                }
                // Clean stale session IDs from openSessionIds after sessions are loaded.
                // Q6: only clean when the FULL session list is loaded
                // (!hasMoreSessions). getSessions() returns a single page; cleaning
                // against a partial page would wipe tabs pointing to older sessions
                // beyond the first page — making recent tabs vanish on every restart
                // and forcing the user to reopen them from the Sessions page.
                val loadedSessionIds = state.value.sessions.map { s -> s.id }.toSet()
                val prevOpen = settingsManager.openSessionIds
                val cleanedOpen = prevOpen.filter { it in loadedSessionIds }
                if (cleanedOpen.size != prevOpen.size && !state.value.hasMoreSessions) {
                    settingsManager.openSessionIds = cleanedOpen
                    state.update { it.copy(openSessionIds = cleanedOpen) }
                }
                val currentId = state.value.currentSessionId
                val refreshedSessions = state.value.sessions
                when {
                    // Skip auto-select when the user is mid-draft (a workdir
                    // has been chosen but no session created yet): selecting a
                    // session would discard the draft's repository workdir and
                    // hijack the empty chat page the user is composing into.
                    currentId == null && state.value.draftWorkdir == null && refreshedSessions.isNotEmpty() -> onSelectSession(refreshedSessions.first().id)
                    // currentId is set: keep it. Even when the session is
                    // temporarily absent from the refreshed list (e.g. just
                    // created, or a directory session), tolerate it — reload
                    // its messages but do NOT silently reselect first(). #10.
                    currentId != null -> {
                        onLoadSessionStatus()
                        onLoadMessages(currentId)
                    }
                    else -> {
                        state.update { it.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap()) }
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
                    val mergedSessions = mergeRefreshedSessionsPreservingLocalActivity(
                        sessions,
                        it.sessions,
                        it.currentSessionId,
                        it.openSessionIds.toSet()
                    )
                    it.copy(
                        sessions = mergedSessions,
                        loadedSessionLimit = nextLimit,
                        hasMoreSessions = mergedSessions.size >= nextLimit,
                        isLoadingMoreSessions = false
                    )
                }
                val currentId = state.value.currentSessionId
                val refreshedSessions = state.value.sessions
                when {
                    // Mirror loadSessions' draft guard for consistency: never
                    // auto-select first() while the user is mid-draft. This
                    // branch is currently dead (loadMore is only triggered by
                    // user scroll, not initial load), but the guard keeps the
                    // two paths symmetric if the trigger ever changes.
                    currentId == null && state.value.draftWorkdir == null && refreshedSessions.isNotEmpty() -> onSelectSession(refreshedSessions.first().id)
                    // A non-null currentId is never silently replaced by
                    // refreshedSessions.first(): tolerate the session even when
                    // it is temporarily absent from the refreshed list. #10.
                    currentId != null -> Unit
                    else -> state.update { it.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap()) }
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
        // §15.2 (review B2): clear streaming buffers on session switch so a
        // stale partial from the previous session cannot bleed into the new
        // one and re-combine with fresh deltas (key collision on
        // `${messageId}:${partId}` produced ghost/fragmented text).
        it.copy(
            currentSessionId = sessionId,
            messages = emptyList(),
            partsByMessage = emptyMap(),
            inputText = restoredDraft,
            streamingPartTexts = emptyMap(),
            streamingReasoningPart = null
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
    // Coalesce concurrent loads. ADB showed startup triggers message loads from
    // multiple paths (testConnection→loadSessions→onLoadMessages, ON_START
    // catch-up) within ~2.6s — 3 parallel fetches of the same large
    // chunked body that叠加 to OOM. The first load wins; concurrent ones skip.
    // The flag is set synchronously (before launch) to close the check-and-set
    // race window. Periodic reloads after the first completes still go through.
    if (state.value.isLoadingMessages) return
    state.update { it.copy(isLoadingMessages = true) }
    scope.launch {
        // §on-demand: cursor pagination. The first load (resetLimit) captures the
        // X-Next-Cursor for future loadMore; subsequent periodic reloads fetch the
        // latest window only and preserve the cursor so scrolled history stays
        // loadable.
        repository.getMessagesPaged(sessionId, 5, before = null)
            .onSuccess { page ->
                if (sessionId == state.value.currentSessionId) {
                    val lastAssistant = page.items.lastOrNull { it.info.isAssistant }
                    val inferredAgentName = lastAssistant?.info?.agent
                    val agentName = settingsManager?.getAgentForSession(sessionId) ?: inferredAgentName
                    state.update {
                        // §preserveUnfetched (mirrors opencode-web reconcileFetched):
                        // a periodic reload (resetLimit=false) fetches the latest
                        // window but must NOT erase already-loaded older history
                        // pages. When the fetched page is incomplete (nextCursor !=
                        // null, i.e. more history exists), keep every local message
                        // whose id is not in the fetched page AND whose created time
                        // predates the fetched page's oldest — exactly the older
                        // pages the user scrolled up to load. Without this the
                        // periodic reload replaced `messages` wholesale while keeping
                        // the old cursor, causing the 🔴 history-断层 the reviewers
                        // flagged. Falls back to "keep all not-in-fetched" when
                        // created times are unavailable. S4 split-store: parts for
                        // kept-older messages must be preserved alongside their
                        // messages (partsByMessage mirrors the merge).
                        val fetchedIds = page.items.map { m -> m.info.id }.toHashSet()
                        val oldestFetchedCreated = page.items
                            .mapNotNull { m -> m.info.time?.created }
                            .minOrNull()
                        val fetchedMessages = page.items.map { m -> m.info }
                        val fetchedParts = page.items.associate { m -> m.info.id to m.parts }
                        val mergedMessages: List<Message>
                        val mergedParts: Map<String, List<Part>>
                        if (resetLimit) {
                            mergedMessages = fetchedMessages
                            mergedParts = fetchedParts
                        } else {
                            val olderKept = it.messages.filter { m ->
                                m.id !in fetchedIds && (oldestFetchedCreated == null ||
                                    (m.time?.created ?: Long.MAX_VALUE) < oldestFetchedCreated)
                            }
                            val olderKeptIds = olderKept.map { m -> m.id }.toHashSet()
                            mergedMessages = olderKept + fetchedMessages
                            // keep parts for older-kept messages + add fetched parts
                            mergedParts = it.partsByMessage.filterKeys { id -> id in olderKeptIds } + fetchedParts
                        }
                        it.copy(
                            messages = mergedMessages,
                            partsByMessage = mergedParts,
                            isLoadingMessages = false,
                            selectedAgentName = agentName ?: it.selectedAgentName,
                            // §finalization-boundary (gpter BLOCKER): a resetLimit=true
                            // reload fetches the AUTHORITATIVE latest window, so any
                            // streaming overlay for those messages is now superseded.
                            // Clear it so a stale partial (streamingPartTexts[partId])
                            // cannot mask the finalized part.text in the UI. This is the
                            // finalization boundary now that S1 removed the idle clear.
                            // (resetLimit=false periodic reloads preserve the overlay
                            // since they only fetch the latest window without finalizing
                            // an in-flight turn.) Safe across callers: session-open and
                            // foreground already wipe before load (redundant, harmless);
                            // message.created / send first-paint are the intended clears.
                            streamingPartTexts = if (resetLimit) emptyMap() else it.streamingPartTexts,
                            streamingReasoningPart = if (resetLimit) null else it.streamingReasoningPart,
                            // Only (re)seed the history cursor on a fresh open; a
                            // periodic reload must NOT clobber an existing cursor
                            // (now safe because older history is preserved above).
                            olderMessagesCursor = if (resetLimit) page.nextCursor else it.olderMessagesCursor,
                            hasMoreMessages = if (resetLimit) (page.nextCursor != null) else it.hasMoreMessages
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
    // §on-demand: cursor-based history paging. Fetch one older page via the V1
    // `before` cursor and PREPEND it — no longer re-downloading the latest
    // window with an ever-growing limit (the old O(n²) anti-pattern that caused
    // both cellular blowup and OOM). Stops when there's no next cursor.
    val cursor = state.value.olderMessagesCursor
    if (cursor == null || !state.value.hasMoreMessages) return
    // Atomic check-and-set (mirrors launchLoadMessages): set isLoadingMessages
    // synchronously BEFORE launch so a rapid second loadMore (fast scroll /
    // recomposition) can't pass the guard and fire a duplicate concurrent
    // fetch of the same cursor page.
    state.update { it.copy(isLoadingMessages = true) }
    scope.launch {
        repository.getMessagesPaged(sessionId, limit = 5, before = cursor)
            .onSuccess { page ->
                if (sessionId == state.value.currentSessionId) {
                    if (page.items.isNotEmpty()) {
                        // De-dup by message id at the seam (the page boundary may
                        // overlap the oldest already-loaded message by one).
                        val existingIds = state.value.messages.map { it.id }.toHashSet()
                        val older = page.items.filterNot { it.info.id in existingIds }
                        val olderMessages = older.map { it.info }
                        val olderParts = older.associate { it.info.id to it.parts }
                        state.update {
                            it.copy(
                                messages = olderMessages + it.messages,
                                partsByMessage = olderParts + it.partsByMessage,
                                olderMessagesCursor = page.nextCursor,
                                hasMoreMessages = page.nextCursor != null,
                                isLoadingMessages = false
                            )
                        }
                    } else {
                        state.update {
                            it.copy(
                                olderMessagesCursor = page.nextCursor,
                                hasMoreMessages = page.nextCursor != null,
                                isLoadingMessages = false
                            )
                        }
                    }
                } else {
                    state.update { it.copy(isLoadingMessages = false) }
                }
            }
            .onFailure {
                if (sessionId == state.value.currentSessionId) {
                    reportNonFatalIssue("MainViewModel", "Failed to load more messages")
                }
                // Manual paging: no auto-retry/loop. Keep hasMoreMessages so the
                // user can tap "load more" again (transient failures shouldn't
                // permanently disable history). With limit=5 a page is tiny, so
                // hitting the 16 MB response cap is essentially impossible.
                state.update { it.copy(isLoadingMessages = false) }
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
    settingsManager: SettingsManager,
    sessionId: String,
    onSelectSession: (String) -> Unit
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
                state.update {
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
                        state.update { it.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap()) }
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
                // §15.1 (review N6): the post-send 1200ms double-refresh is
                // gone — SSE will deliver `message.created` / `message.updated`
                // and a foreground catch-up covers any dropped event. The single
                // immediate reload here is the legacy first-paint path that
                // selectSession/sendMessage use to bypass the debounce.
                onRefreshMessages(sessionId, true)
            }
            .onFailure { error ->
                state.update { it.copy(error = errorMessageOrFallback(error, "Failed to send message")) }
            }
        onComplete?.invoke()
    }
}
