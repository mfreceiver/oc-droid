package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.R
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

/**
 * §R18 Phase 2-E step 1: resolves the directory header to attach to a
 * question reply/reject for [requestId]. Three-level fallback:
 *  1. The pending question's parent session's directory (handles cross-workdir
 *     routing — a question may belong to a session whose workdir differs from
 *     the currently-selected one).
 *  2. The persisted current workdir (matches the pre-Phase-2-E behavior,
 *     where the interceptor injected the global currentDirectory).
 *  3. null — server falls back to its own process.cwd() (last resort; should
 *     not happen in normal use).
 *
 * Defined here (next to executeCommand) because it touches sessionList +
 * directorySessions + settingsManager — three domains AppCore already owns.
 */
internal fun AppCore.resolveQuestionDirectory(requestId: String): String? {
    val pending = store.sessionListFlow.value.pendingQuestions.firstOrNull { it.id == requestId }
    val sessionId = pending?.sessionId
    if (sessionId != null) {
        val session = (
            store.sessionListFlow.value.sessions +
                store.sessionListFlow.value.directorySessions.values.flatten()
            ).firstOrNull { it.id == sessionId }
        if (session != null && !session.directory.isNullOrBlank()) return session.directory
    }
    return settingsManager.currentWorkdir
}

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
            val workdir = settingsManager.currentWorkdir
                ?: currentSession(store.sessionListFlow.value.sessions, store.chatFlow.value.currentSessionId)?.directory
            if (workdir != null) createSessionInWorkdirForEffect(workdir) else createSessionForEffect()
        }
        else -> {
            val existing = store.chatFlow.value.currentSessionId
            composerController.setInputText("")
            // §R18 Phase 2-E step 1: resolve the directory for the slash
            // command's session explicitly. Was repository.getCurrentDirectory()
            // (the global currentDirectory); now derived from the current
            // session's directory, then the draft workdir (when the user is in
            // draft mode targeting a different workdir than currentWorkdir), then
            // the persisted workdir as final fallback. (maxer Gate-2: draft-mode
            // fallback must prefer draftWorkdir over currentWorkdir, otherwise a
            // /compact typed while drafting in workdir B but currentWorkdir=A
            // routes the command to A.)
            val commandDirectory = (
                currentSession(store.sessionListFlow.value.sessions, existing)?.directory
                    ?: store.composerFlow.value.draftWorkdir
                    ?: settingsManager.currentWorkdir
                )
            if (existing != null) {
                appScope.launch {
                    repository.executeCommand(existing, cmd, arguments, directory = commandDirectory)
                        .onFailure { error ->
                            effectBus.tryEmitUiEvent(UiEvent.Error(R.string.error_command_failed, listOf(cmd, errorMessageOrFallback(error, "unknown error"))))
                        }
                }
            } else if (store.composerFlow.value.draftWorkdir != null) {
                // §bug2: materialize draft session, then execute the command on the new session
                materializeDraftSession { sessionId ->
                    appScope.launch {
                        repository.executeCommand(sessionId, cmd, arguments, directory = commandDirectory)
                            .onFailure { error ->
                                effectBus.tryEmitUiEvent(UiEvent.Error(R.string.error_command_failed, listOf(cmd, errorMessageOrFallback(error, "unknown error"))))
                            }
                    }
                }
            } else {
                effectBus.tryEmitUiEvent(UiEvent.Error(R.string.chat_command_no_session, listOf(cmd)))
            }
            return
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
        materializeDraftSession { sessionId -> dispatchSendMessage(sessionId) }
        return
    }

    val sessionId = existingSessionId ?: return
    if (store.composerFlow.value.sendingSessionIds.contains(sessionId)) return
    dispatchSendMessage(sessionId)
}

/**
 * §bug2: Shared draft-session materialization. Detects draft mode (composer
 * has a draftWorkdir but no current session yet), clears the draft, creates a
 * new session, wires it into the session-list / chat / unread slices, copies
 * the current model + agent selections to per-session storage, schedules a
 * title refresh, then invokes [onSessionReady] with the new session id. On
 * failure: emits UiEvent.Error and restores the composer draftWorkdir —
 * callers do not need their own failure path. Used by both [sendMessage] and
 * [executeCommand] so the first /cmd in a draft session no longer errors with
 * "Open or create a session before running /cmd".
 */
internal fun AppCore.materializeDraftSession(onSessionReady: (String) -> Unit) {
    val draftWorkdir = store.composerFlow.value.draftWorkdir ?: return
    writeComposer { it.copy(draftWorkdir = null) }
    appScope.launch {
        repository.createSession(title = null, directory = draftWorkdir)   // §R18 Final 终审 fix (gpter): route to the draft workdir
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
                // §R18 Phase 2-F: chatFlow.currentSessionId (set in writeChat
                // above) is the sole runtime source; the AppCore init collector
                // persists session.id to SettingsManager. No manual write here.
                store.chatFlow.value.currentModel?.let { model ->
                    settingsManager.setModelForSession(session.id, model.providerId, model.modelId)
                }
                // §bug3-defensive: persist the agent too so the next dispatchSendMessage
                // picks up the per-session agent (mirrors the model copy above; previously
                // the agent was not copied here, causing a one-message lag when switching
                // agents in draft mode).
                store.settingsFlow.value.selectedAgentName?.let { agent ->
                    settingsManager.setAgentForSession(session.id, agent)
                }
                persistSessionCache(
                    settingsManager = settingsManager,
                    sessions = store.sessionListFlow.value.sessions,
                    openIds = store.sessionListFlow.value.openSessionIds,
                    currentId = session.id,
                    currentWorkdir = settingsManager.currentWorkdir,
                )
                scheduleTitleRefreshAfterFirstMessage(session.id)
                onSessionReady(session.id)
            }
            .onFailure { error ->
                writeComposer { it.copy(draftWorkdir = draftWorkdir) }
                effectBus.tryEmitUiEvent(UiEvent.Error(R.string.error_create_session_in_workdir_failed, listOf(draftWorkdir, error.message ?: "unknown error")))
            }
    }
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
            emit = EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
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
                    effectBus.tryEmitUiEvent(UiEvent.Error(R.string.error_restore_session_failed, listOf(errorMessageOrFallback(error, "unknown error"))))
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
    // §R18 Phase 3 Wave 3 (P1-9 wire-up): fan-out pending-questions catch-up
    // across EVERY known workdir (in-memory directorySessions keys +
    // currentWorkdir), not just currentWorkdir. Without this, a question
    // arriving for a background workdir during the SSE outage window is lost:
    // the catch-up ran only against currentWorkdir. Mirrors the workdir-set
    // computation in SessionSyncCoordinator.loadPendingQuestionsAllWorkdirs
    // (duplicated inline because ForegroundCatchUpController does NOT own
    // SliceFlows — the workdir list is supplied by the caller per the Wave 1
    // signature contract).
    val catchUpWorkdirs = (
        store.sessionListFlow.value.directorySessions.keys +
            listOfNotNull(settingsManager.currentWorkdir)
        )
        .filter { it.isNotBlank() }
        .distinct()
    foregroundCatchUpController.catchUpPendingQuestionsAllWorkdirs(
        repository = repository,
        workdirs = catchUpWorkdirs,
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
        emit = EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
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
        emit = EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
    )
}

private fun AppCore.selectSessionForEffect(sessionId: String) {
    sessionSwitcher.switchTo(sessionId)
}

private fun AppCore.createSessionForEffect(title: String? = null) {
    launchCreateSession(appScope, repository, store.slices, title, { selectSessionForEffect(it) }, EventEmitter { effectBus.tryEmitUiEvent(it) }, directory = settingsManager.currentWorkdir)   // §R18 Final 终审 fix (gpter)
}

private fun AppCore.createSessionInWorkdirForEffect(workdir: String) {
    val workdir = workdir.trim()
    // §R18 Phase 2-E step 2: the repository.setCurrentDirectory call was
    // removed; downstream directory-scoped calls (SSE / /question / /command)
    // now take an explicit `directory` parameter, and the workdir is carried
    // forward by settingsManager.currentWorkdir + composer.draftWorkdir below.
    // §R18 Phase 2-F: chatFlow.currentSessionId (cleared in writeChat below) is
    // the sole runtime source; the AppCore collector drops null so no manual
    // SettingsManager write here.
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
