package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.runSuspendCatching
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * §R18 Phase 2-E step 1 → §issue-1 Phase 2a Fix A: resolves the directory
 * header to attach to a question reply/reject for [requestId]. Now `suspend`
 * so it can fetch the parent session from the server when it is missing
 * locally (the schema confirms `question.asked` carries NO directory field,
 * so the fetch is the only way to recover the workdir for a not-yet-local
 * session).
 *
 * Resolution order:
 *  1. The pending question's parent session's directory IF the session is
 *     already in `sessions ∪ directorySessions` with a non-blank directory
 *     (handles cross-workdir routing; no network). Unchanged from Phase 1.
 *  2. Otherwise `GET /session/{sessionId}` (already Skip-Dir), then a CONDITIONAL
 *     CAS: inside `writeSessionList` (which reads the latest state atomically),
 *     re-check the session — if a fresher entry was hydrated by another load/SSE
 *     during the network wait (non-blank dir), keep it and return ITS directory;
 *     otherwise upsert `fetched` into `sessions` (so this + later resolves hit
 *     branch 1) and return `fetched.directory`. Mirrors [openSessionFromDeepLink].
 *  3. fetch fail / null / blank directory → `null`. `null` means "let the
 *     server self-lookup via process.cwd()" — an INTENTIONAL degrade (observable
 *     via DebugLog + Fix C's UiEvent) rather than the old silent wrong-value
 *     bug where `currentWorkdir` was returned even when it mismatched the
 *     question's real workdir. The `settingsManager.currentWorkdir` fallback is
 *     deliberately DROPPED from this function.
 *
 * suspend-safe: the only production callers are
 * [OrchestratorViewModel.replyQuestion] + [OrchestratorViewModel.rejectQuestion],
 * both inside `viewModelScope.launch`.
 */
internal suspend fun AppCore.resolveQuestionDirectory(requestId: String): String? {
    val pending = store.sessionListFlow.value.pendingQuestions.firstOrNull { it.id == requestId }
    val sessionId = pending?.sessionId
    if (sessionId == null) {
        // §Phase1a/2a instrumentation: no pending question → null (no fetch).
        DebugLog.d("Question", "resolveQuestionDirectory req=$requestId sid=null(no pending) branch=3(no-pending) return=null")
        return null
    }
    // §Phase 2 gpter round-3: predicate requires non-blank directory DIRECTLY.
    // A blank-dir entry with the same id in `sessions` must not mask an
    // eligible hydrated entry in `directorySessions` (or vice-versa) — without
    // the `!isNullOrBlank()` in the predicate, firstOrNull would return the
    // blank one (depending on ordering) and the separate post-check would then
    // fall through to an unnecessary fetch. Bundling the eligibility check into
    // the predicate guarantees the first ELIGIBLE entry wins regardless of order.
    val session = (
        store.sessionListFlow.value.sessions +
            store.sessionListFlow.value.directorySessions.values.flatten()
        ).firstOrNull { it.id == sessionId && !it.directory.isNullOrBlank() }
    if (session != null) {
        // §Phase1a instrumentation: branch 1 — eligible parent session found (non-blank dir).
        DebugLog.d(
            "Question",
            "resolveQuestionDirectory req=$requestId sid=$sessionId parentFound=true dir=\"${session.directory}\" branch=1(session.directory) return=\"${session.directory}\""
        )
        return session.directory
    }
    // §issue-1 Fix A: session absent OR directory blank → fetch from server
    // (Skip-Dir; openSessionFromDeepLink is the isomorphic precedent).
    val fetched = runSuspendCatching { repository.getSession(sessionId).getOrNull() }.getOrNull()
    if (fetched == null || fetched.directory.isNullOrBlank()) {
        // §Phase1a/2a instrumentation: fetch fail / null / blank directory → null
        // (NOT currentWorkdir — intentional degrade, server self-looks-up).
        DebugLog.d(
            "Question",
            "resolveQuestionDirectory req=$requestId sid=$sessionId parentFound=${session != null} dir=${session?.directory ?: "null"} branch=3(fetch-fail/null) fetchedDir=${fetched?.directory ?: "null"} return=null"
        )
        return null
    }
    // §Phase 2 gpter fix: CONDITIONAL CAS. The suspend fetch above may have
    // raced with another load/SSE that hydrated this same session during the
    // network wait. writeSessionList reads the LATEST state atomically inside
    // its lambda — re-check there: if a fresher entry now exists (in sessions ∪
    // directorySessions) with a non-blank directory, it is authoritative → keep
    // it and do NOT let the fetched snapshot overwrite it. Only upsert `fetched`
    // when the session is still absent/blank. Either way, return the
    // authoritative directory (fresher entry's if present, else fetched's).
    val fetchedDir = fetched.directory
    var resolved: String? = null
    var casPath = "fetch-hit-cached"
    writeSessionList { st ->
        // §Phase 2 gpter round-3: non-blank predicate (same as branch 1) — a
        // blank-dir duplicate hydrated during the fetch is NOT authoritative.
        val current = (st.sessions + st.directorySessions.values.flatten())
            .firstOrNull { it.id == sessionId && !it.directory.isNullOrBlank() }
        if (current != null) {
            // Fresher authoritative entry hydrated during the fetch — keep it.
            resolved = current.directory
            casPath = "fetch-hit-fresher-kept"
            st // no overwrite
        } else {
            resolved = fetchedDir
            st.copy(sessions = upsertSession(st.sessions, fetched))
        }
    }
    DebugLog.d(
        "Question",
        "resolveQuestionDirectory req=$requestId sid=$sessionId parentFound=${session != null} dir=${session?.directory ?: "null"} branch=2($casPath) fetchedDir=\"${fetchedDir}\" return=\"${resolved}\""
    )
    return resolved
}

/**
 * §issue-1 Phase 2a Fix B: the ONE shared workdir-set computation for pending-
 * question fan-out. Used at BOTH fan-out sites so they cannot drift:
 *  (1) [cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator.loadPendingQuestionsAllWorkdirs]
 *      (SSE catch-up), and
 *  (2) [catchUpAfterDisconnectOrForeground]'s `catchUpWorkdirs` (foreground
 *      catch-up, inline-duplicated before this helper).
 *
 * Set = `directorySessions.keys` + `currentWorkdir` + per-fp `recent_workdirs`.
 * `recent_workdirs` is per-serverGroupFp (R-20 Phase 5) — read via
 * [cn.vectory.ocdroid.util.SettingsManager.getRecentWorkdirs] with the live fp,
 * mirroring `ConnectionCoordinator`'s restore fan-out. Without it, a question
 * arriving for a recently-used-but-not-currently-connected workdir is missed
 * during catch-up (`directorySessions` only holds currently-connected ones).
 *
 * Pure free function (no AppCore coupling) so the coordinator can call it
 * directly without an import cycle.
 */
internal fun computeQuestionFanOutWorkdirs(
    directorySessionKeys: Set<String>,
    currentWorkdir: String?,
    recentWorkdirs: List<String>,
): List<String> =
    (directorySessionKeys + listOfNotNull(currentWorkdir) + recentWorkdirs)
        .filter { it.isNotBlank() }
        .distinct()

/** nav → session-list → chat. Used by the notification deep-link path. */
internal fun AppCore.openSessionFromDeepLink(sessionId: String) {
    appScope.launch {
        if (store.sessionListFlow.value.sessions.none { it.id == sessionId }) {
            // §fix-flake: NO withContext(Dispatchers.IO) here.
            // repository.getSession is a suspend Retrofit call — OkHttp already
            // offloads the actual network IO off the calling thread, so wrapping
            // it in withContext(IO) was redundant AND broke test determinism:
            // it escaped the StandardTestDispatcher so advanceUntilIdle() could
            // not drive the fetch, racing coVerify under full-suite IO-pool
            // contention. Running on appScope's dispatcher (Main in prod, the
            // test dispatcher in tests) keeps the whole coroutine on one
            // dispatcher and is production-semantics-neutral (the only
            // surrounding work is runSuspendCatching{}.getOrNull()).
            val fetched = runSuspendCatching { repository.getSession(sessionId).getOrNull() }.getOrNull()
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
                            // §grouping-rewrite item 4: command POST runs on
                            // a 300 s-read-timeout client (OkHttpClientFactory.
                            // commandClient). A read-side SocketTimeoutException
                            // means even that window expired waiting for the
                            // server's ACK — but SSE (its own client, read
                            // timeout 0) is still delivering results, so that
                            // case is non-fatal: emit a neutral Info snackbar
                            // and let SSE update the UI.
                            //
                            // §grouping-rewrite Round-2 D2: but a CONNECT-side
                            // SocketTimeoutException (server unreachable / DNS /
                            // TLS handshake) means the POST never reached the
                            // server → SSE will not deliver → must surface as a
                            // real Error. OkHttp's exception message distinguishes
                            // the two ("connect timeout" / "failed to connect" vs
                            // "timeout" / "read timeout"); we case-insensitively
                            // sniff for "connect". All non-timeout failures stay
                            // Error.
                            effectBus.tryEmitUiEvent(classifyCommandPostError(error, cmd))
                        }
                }
            } else if (store.composerFlow.value.draftWorkdir != null) {
                // §bug2: materialize draft session, then execute the command on the new session
                materializeDraftSession { sessionId ->
                    appScope.launch {
                        repository.executeCommand(sessionId, cmd, arguments, directory = commandDirectory)
                            .onFailure { error ->
                                // §grouping-rewrite item 4 + Round-2 D2: see
                                // comment in the non-draft branch above — same
                                // connect-vs-read timeout classification.
                                effectBus.tryEmitUiEvent(classifyCommandPostError(error, cmd))
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
                // §A5-3 Phase B2: the success-path writeSessionList + writeChat
                // + writeUnread + writeComposer sequence is collapsed into ONE
                // atomic dispatch — sessionList (upsert + openSessionIds),
                // chat.currentSessionId, unread (drop + lastViewedTime), and
                // composer.draftWorkdir clear all land as a SINGLE committed
                // aggregate state (no torn intermediates for stateFlow
                // collectors). The reducer is pure (see [AppAction]); the
                // settings/persist/side-effect calls below stay OUTSIDE the
                // dispatch (they are not state).
                store.dispatch(
                    AppAction.DraftSessionMaterialized(
                        session = session,
                        openSessionIds = openIds,
                        viewedAt = now,
                    )
                )
                // §R18 Phase 2-F: chatFlow.currentSessionId (set in writeChat
                // above) is the sole runtime source; the AppCore init collector
                // persists session.id to SettingsManager. No manual write here.
                //
                // §chat-ux-batch T8 (B3): the legacy per-session agent/model
                // copy from chatFlow.currentModel / settingsFlow.selectedAgentName
                // to SettingsManager.set{Model,Agent}ForSession was deleted
                // here. T7 rewired both picks to TRANSIENT pendingModel /
                // pendingAgent (consumed at dispatchSendMessage); no
                // persistence is needed for the carry.
                persistSessionCache(
                    settingsManager = settingsManager,
                    sessions = store.sessionListFlow.value.sessions,
                    openIds = store.sessionListFlow.value.openSessionIds,
                    currentId = session.id,
                    currentWorkdir = settingsManager.currentWorkdir,
                    revertCutoffs = store.chatFlow.value.revertCutoffs,
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

/**
 * §grouping-rewrite Round-2 D2 (+ Round-3 N1): classify a `/command` POST
 * failure for the UI. Two flavours of [java.net.SocketTimeoutException] come
 * out of OkHttp, and only ONE is non-fatal:
 *
 *  - **read-side timeout** (POST accepted, server slow to ACK within the
 *    commandClient's 300 s read-timeout) → SSE (its own client, read timeout
 *    0) is still delivering results → emit [UiEvent.Info] with
 *    `command_submitted_processing` and let SSE update the UI.
 *  - **connect-side timeout** (server unreachable / DNS / TLS handshake did
 *    not complete within the connect-timeout) → the POST never reached the
 *    server → SSE cannot deliver → must surface as [UiEvent.Error] with
 *    `error_command_failed`.
 *
 * OkHttp's exception message distinguishes the two (typical phrases: "connect
 * timeout", "failed to connect"). We case-insensitively sniff for "connect"
 * (which also covers "failed to connect"). All non-SocketTimeoutException
 * failures (HTTP 4xx/5xx, IOException, etc.) stay [UiEvent.Error].
 *
 * §grouping-rewrite Round-3 N1: visibility changed `private` → `internal` so
 * [cn.vectory.ocdroid.AppCoreOrchestrationTest] can pin the three branches
 * (read-timeout → Info; connect-timeout → Error; non-timeout → Error).
 */
internal fun classifyCommandPostError(error: Throwable, cmd: String): UiEvent {
    if (error is java.net.SocketTimeoutException) {
        val msg = error.message?.lowercase().orEmpty()
        val isConnectSide = "connect" in msg || "failed to connect" in msg
        if (!isConnectSide) {
            // read-timeout → non-fatal, SSE will carry the result.
            return UiEvent.Info(R.string.command_submitted_processing)
        }
    }
    return UiEvent.Error(
        R.string.error_command_failed,
        listOf(cmd, errorMessageOrFallback(error, "unknown error"))
    )
}

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
    settingsManager.setDraftText(currentServerGroupFp(), sessionId, "")
    // §1B-FIX (I4): clear inputText, imageAttachments AND fileReferences
    // when the user hits Send — chips must not leak to the next prompt.
    // The `text` + `attachments` locals above already captured the values
    // that go on the wire, so this is safe to clear immediately.
    writeComposer { it.copy(inputText = "", imageAttachments = emptyList(), fileReferences = emptyList()) }

    // §chat-ux-batch T3: snap the message list to the newest message when the
    // user sends — mirrors SessionViewModel.requestJumpToLatest (same store
    // dispatch + same AppAction.PendingJumpToLatestSet). Placed here (after the
    // early-return guards + composer clear, BEFORE the archived/direct branches
    // reach launchSendMessage) so it fires exactly once on the common send
    // path. The consumer at ChatMessageContent.kt:565-576 then performs
    // scrollToItem(0) + followBottom=true and clears the intent. sessionId is a
    // non-null String parameter, no guard needed.
    store.dispatch(AppAction.PendingJumpToLatestSet(sessionId))

    val currentSession = currentSession(store.sessionListFlow.value.sessions, store.chatFlow.value.currentSessionId)

    fun dispatchSend() {
        // §chat-ux-batch T7 (B2): per-session sticky resolution.
        //   agent = pendingAgent ?: inferCurrentAgent(msgs, visible) ?: null
        //   model = pendingModel ?: inferCurrentModel(msgs, visible) ?: null
        // `pending*` is the user's just-picked value THIS turn (transient;
        // cleared below after the send launches). `infer*` derives from the
        // session's transcript, SKIPPING hidden internal agents (compaction /
        // title) via the visible-agents filter. null on both arms lets the
        // server apply its own default (server-side `prompt.ts:646` is the
        // source of truth and honors an explicit model when provided).
        //
        // CRITICAL: the visible set MUST filter by `isVisible` — opencode's
        // `/agent` list includes hidden internal agents whose transcript
        // presence would otherwise be inferred as "the current agent",
        // defeating T6's skip.
        val chatState = store.chatFlow.value
        val visibleAgents = store.settingsFlow.value.agents
            .filter { it.isVisible }
            .map { it.name }
            .toSet()
        val agent: String? = chatState.pendingAgent
            ?: inferCurrentAgent(chatState.messages, visibleAgents)
        val model: Message.ModelInfo? = chatState.pendingModel
            ?: inferCurrentModel(chatState.messages, visibleAgents)
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
                settingsManager.setDraftText(currentServerGroupFp(), sessionId, "")
                // §1B-FIX (I4): onSuccess is a no-op for fileReferences
                // because line 412-413 already cleared inputText +
                // imageAttachments + fileReferences when the user hit
                // Send. (This is the safety net for any edge case where
                // the orchestrator's `text` local was empty / attachments
                // empty and the early-return at line 408 did NOT fire —
                // in that case the state was never cleared and this is
                // where we do it.)
                writeComposer { it.copy(inputText = "", imageAttachments = emptyList(), fileReferences = emptyList()) }
            },
            onComplete = {
                writeComposer { state -> state.copy(sendingSessionIds = state.sendingSessionIds - sessionId) }
            },
            emit = EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
        )
        // §chat-ux-batch T7 (B2): clear the transient pending picks AFTER the
        // send launches — the picks were consumed; the next send starts fresh
        // (pending=null → falls back to inference, which will reflect the just-
        // sent agent/model via the new user/assistant message once SSE lands).
        // This is the "per-session sticky via pending" core invariant: a
        // pending pick lives for exactly one send.
        store.mutateChat { it.copy(pendingAgent = null, pendingModel = null) }
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
                    if (restored != currentInput) settingsManager.setDraftText(currentServerGroupFp(), sessionId, restored)
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
    // §history-load-fix: guard against BOTH load flags — a user loadMore in
    // flight (isLoadingMoreMessages) must also block a cold-start reset (which
    // would wipe the list mid-prepend). Previously only isLoadingMessages was
    // checked, so a cold-start refresh could clobber an in-flight loadMore.
    if (store.chatFlow.value.isLoadingMessages || store.chatFlow.value.isLoadingMoreMessages) return
    sessionSwitcher.clearSessionWindowCache()
    writeChat { it.copy(refreshNonce = it.refreshNonce + 1) }
    writeChat {
        it.copy(
            streamingPartTexts = emptyMap(),
            streamingReasoningPart = null,
            gapMarkers = emptyList(),
            staleNotice = false,
            messages = emptyList(),
            partsByMessage = emptyMap(),
            olderMessagesCursor = null,
            // §F3-load-more: cold-start reset 时 hasMore 与 cursor 一致。
            hasMoreMessages = false,
            // §history-load-fix: cold-start reset also clears the user loadMore
            // flag (parallel to the guard above + the list/cursor wipe).
            isLoadingMoreMessages = false,
        )
    }
    loadMessagesForEffect(currentId, resetLimit = true)
}

internal fun AppCore.catchUpAfterDisconnectOrForeground(sessionId: String) {
    // R-20 Phase 2: capture fp once (glm-3 🟡#1 single-read) for the cache hook
    // + the coordinator's openGap compound key + the G6 current-workdir input.
    val fp = hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id }
    // G6 inputs: SSE coverage baseline + the live SSE workdir (drives shouldProbe).
    val sseSnap = sessionSyncCoordinator.sseSyncStateSnapshot()
    val sseWorkdir = store.connectionFlow.value.isConnected.let { connected ->
        // The SSE feed is attached to the current workdir when connected; null
        // when disconnected (no live feed → never SSE-covered).
        if (connected) settingsManager.currentWorkdir else null
    }
    launchCatchUp(
        scope = appScope,
        repository = repository,
        slices = store.slices,
        sessionId = sessionId,
        settingsManager = settingsManager,
        onCacheWindow = makeCacheHook(fp),
        // R-20 Phase 2: delegate gap open + 50-step fill to the coordinator.
        gapFillCoordinator = gapFillCoordinator,
        // §fix-#2 (gpter 复审 #2 — glm-3 前次 #3 修复的实现错误): pass the
        // LIVE fp provider (the injected @Named("currentServerGroupFp")
        // reference on AppCore), NOT `{ fp }`. The previous `{ fp }` captured
        // the same snapshot as `expectedServerGroupFp = fp` below → the onSuccess
        // guard `currentServerGroupFp() != expectedServerGroupFp` was恒等
        // (no-op): a host switch during the probe REST was never detected, and
        // the stale response was merged into the new group's slice. With the
        // live provider, currentServerGroupFp() reads the current host's fp
        // each call, so a mid-probe host switch makes the guard fire.
        currentServerGroupFp = currentServerGroupFp,
        // §fix-#3 (gpter #3): the fp captured AT CALL TIME (initiation
        // snapshot). The onSuccess guard compares this vs the live
        // currentServerGroupFp() — a mismatch means the user switched host
        // group during the probe; the stale response must NOT be merged.
        expectedServerGroupFp = fp,
        sseCurrentWorkdir = sseWorkdir,
        sessionsEverColdSnapshotted = sseSnap.sessionsEverColdSnapshotted,
        onColdSnapshot = { sid -> sessionSyncCoordinator.markSessionColdSnapshotted(sid) },
    )
    // §R18 Phase 3 Wave 3 (P1-9 wire-up): fan-out pending-questions catch-up
    // across EVERY known workdir, not just currentWorkdir. Without this, a
    // question arriving for a background workdir during the SSE outage window
    // is lost: the catch-up ran only against currentWorkdir.
    // §issue-1 Phase 2a Fix B: now uses the shared [computeQuestionFanOutWorkdirs]
    // helper (with per-fp recent_workdirs) so this site cannot drift from
    // SessionSyncCoordinator.loadPendingQuestionsAllWorkdirs (site 1).
    val catchUpWorkdirs = computeQuestionFanOutWorkdirs(
        directorySessionKeys = store.sessionListFlow.value.directorySessions.keys,
        currentWorkdir = settingsManager.currentWorkdir,
        recentWorkdirs = settingsManager.getRecentWorkdirs(currentServerGroupFp()),
    )
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
 *
 * R-20 Phase 1: the onCacheWindow hook now routes through [AppCore.makeCacheHook]
 * so each window-write is mirrored to the persistent encrypted cache. The
 * fp is captured AT THIS CALL (current host) so a profile switch mid-flight
 * cannot re-key a write to the wrong group (plan §3 closure-capture rule).
 *
 * gpter 复审 final-fix: passes the compound-key guard params
 * ([AppCore.currentServerGroupFp] captured + provider) so the REST onSuccess
 * re-checks the fp after the async fetch. The VerifyAndHydrate handler
 * calls this AFTER its own 二次 guard confirmed fp match, so
 * `currentServerGroupFp()` here equals `effect.serverGroupFp`.
 */
internal fun AppCore.loadMessagesForEffect(sessionId: String, resetLimit: Boolean) {
    launchLoadMessages(
        scope = appScope,
        repository = repository,
        slices = store.slices,
        sessionId = sessionId,
        resetLimit = resetLimit,
        settingsManager = settingsManager,
        onCacheWindow = makeCacheHook(currentServerGroupFp()),
        emit = EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
        // gpter 复审 final-fix: compound-key guard (captured fp + provider).
        expectedServerGroupFp = currentServerGroupFp(),
        currentServerGroupFp = currentServerGroupFp,
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
        // R-20 Phase 1 (C7): verify currentSessionId's cache fingerprint when
        // the session list arrives. MismatchEvicted → drop currentSessionId
        // (cold-start fallback). See launchLoadSessions doc.
        cacheRepository = cacheRepository,
        expectedServerGroupFp = currentServerGroupFp(),
        currentServerGroupFp = currentServerGroupFp,
        // §grouping-rewrite Round-2 #5: the hostProfileStore arg that R-20
        // Phase 5 wired here (for cross-group merge of LAN + tunnel same-server
        // profiles) is removed — attemptCrossGroupMerge was deleted by item 1
        // of this rewrite.
        // WT6 (archive-sync, gap-3) + FIX-A/C (review-blocker): if the merged
        // refresh result contains ANY archived session that was open (in
        // openSessionIds) or was the current session, the callback dispatches
        // a SINGLE atomic [AppAction.BulkSessionsRefreshed] that writes the
        // merged list AND prunes ALL archived openIds (FIX-A — not just
        // current) AND (if current is archived) clears chat + unread/questions
        // subtree cleanup + emits [ControllerEffect.EvictSession]. One
        // committed aggregate state — no torn intermediate.
        onArchivedSessionsDetected = { merged, newOpenIds, hasMore ->
            dispatchBulkArchivedSessions(merged, newOpenIds, hasMore)
        },
    )
}

/**
 * WT6 (archive-sync, gap-3) + FIX-A/C (review-blocker): mirrors the SSE
 * archive handler in [cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator]
 * (the `session.updated` isArchived branch) for the BULK-refresh path. When
 * the merged refresh result discovers archived sessions, this dispatches a
 * SINGLE [AppAction.BulkSessionsRefreshed] that atomically:
 *  1. Writes the merged session list (sessions).
 *  2. Prunes [SessionListState.openSessionIds] of EVERY archived id (FIX-A —
 *     not just the current session; the SSE path does this per-session).
 *  3. IFF the current session is among the archived, clears chat
 *     ([applyArchivedChatClear] → currentSessionId/messages/partsByMessage/
 *     pendingJumpToLatest per FIX-B) + unread/questions subtree cleanup.
 *
 * The reducer derives the "clear chat" decision from the snapshot
 * (chat.currentSessionId in archivedIds), so the action carries pure data
 * only. ONE committed aggregate state — no torn intermediate (the prior
 * two-step mutateSessionList → onCurrentSessionArchived produced an observable
 * emission where sessions[current].isArchived == true AND
 * chat.currentSessionId == current coexisted; FIX-C eliminates this).
 *
 * Side effects OUTSIDE the dispatch (not state): persisting
 * [SettingsManager.openSessionIds] + emitting [ControllerEffect.EvictSession]
 * for the archived current session's cache window (mirrors the SSE path's
 * R-20 Phase 1 eviction). The [persistSessionCache] call is in
 * [launchLoadSessions] (it uses the caller's local variables, not the slice).
 */
private fun AppCore.dispatchBulkArchivedSessions(
    mergedSessions: List<Session>,
    newOpenIds: List<String>,
    hasMoreSessions: Boolean,
) {
    val currentOpenIds = store.sessionListFlow.value.openSessionIds
    // Capture the PREVIOUS currentSessionId before the dispatch clears it
    // (used to emit EvictSession for the archived current's cache window).
    val previousCurrentId = store.chatFlow.value.currentSessionId
    val archivedIds = mergedSessions
        .filter { it.isArchived }
        .map { it.id }
        .toSet()
    val currentWasArchived = previousCurrentId != null && previousCurrentId in archivedIds
    // FIX-A: persist ALL archived ids pruned from openSessionIds (not just
    // current — the SSE path prunes any archived id; the bulk path previously
    // only handled the current session, leaving non-current archived open
    // tabs as ghosts capping the 8-tab budget).
    if (newOpenIds != currentOpenIds) {
        settingsManager.openSessionIds = newOpenIds
    }
    // FIX-C: single atomic dispatch. The reducer writes the merged list +
    // pruned openIds + load flags, and IFF the current session is archived,
    // clears chat + unread/questions subtree. No torn intermediate.
    store.dispatch(
        AppAction.BulkSessionsRefreshed(
            sessions = mergedSessions,
            openSessionIds = newOpenIds,
            hasMoreSessions = hasMoreSessions,
        )
    )
    // R-20 Phase 1 cache hygiene (mirrors the SSE archive path's EvictSession
    // emit): drop the archived CURRENT session's window from the memory LRU +
    // persistent cache. Non-current archived ids don't have an active chat
    // window to evict (openIds prune alone is sufficient per FIX-A spec). The
    // fp is read live so a mid-flight host switch cannot re-key the eviction.
    if (currentWasArchived && previousCurrentId != null) {
        effectBus.tryEmitEffect(
            ControllerEffect.EvictSession(currentServerGroupFp(), previousCurrentId)
        )
    }
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
    // §R18 Phase 2-F: chatFlow.currentSessionId (cleared by the dispatch
    // below) is the sole runtime source; the AppCore collector drops null so
    // no manual SettingsManager write here.
    // §A5-3 Phase B2: the pre-B2 sequence — writeChat(clear chat + streaming),
    // writeSessionList(clear sessionTodos), writeChat(clear currentModel),
    // writeComposer(clear inputText + attachments + fileReferences, set
    // draftWorkdir) — is collapsed into ONE atomic dispatch. The reducer
    // ([AppAction.WorkdirDraftStarted]) folds currentModel clear INTO the
    // same single chat .copy() (the pre-B2 site did it as a SEPARATE
    // writeChat — same final state, just scattered). ONE committed aggregate
    // state → no torn intermediates for stateFlow collectors.
    store.dispatch(AppAction.WorkdirDraftStarted(workdir = workdir))
    settingsManager.currentWorkdir = workdir
    settingsManager.addRecentWorkdir(currentServerGroupFp(), workdir)
    appScope.launch {
        repository.getSessionsForDirectory(workdir)
            .onSuccess { sessions ->
                writeSessionList { it.copy(directorySessions = it.directorySessions + (workdir to sessions)) }
            }
    }
}
