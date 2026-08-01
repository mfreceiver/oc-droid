package cn.vectory.ocdroid.ui

import androidx.annotation.MainThread
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.ComposerImageAttachment
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.WorkdirPaths
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

// ── §unified-nav A5: synchronous materialized-session route adoption ─────────
//
// Item 10-A root cause: materializeDraftSession dispatched DraftSessionMaterialized
// (which set currentSessionId + session-list upsert) but did NOT transition the
// route (no nav.lastRoute = "chat/$sid", no navEpoch bump, no route-instance
// token, no LoadedContent envelope). So the first SEND landed in a chat pane
// that was still showing the bare "chat" / ChatEmptyState surface (the route
// never flipped to chat/{sessionId}), while a "thinking" capsule showed because
// the send was in flight. The fix (A5) adopts the route SYNCHRONOUSLY inside
// the materialize success block via a single aggregate CAS that atomically
// commits {session upsert, currentSessionId, chatRouteInstance, content
// envelope, lastRoute, navEpoch, unread/lastViewed, draftWorkdir=null}. Then
// the post-CAS ordering (A-E) runs the persistence side-effect, the route
// hydration, the captured send, and the title refresh — all using the token
// minted inside the CAS.

/**
 * §unified-nav (A5.1) + §blocker1: the IMMUTABLE send payload captured at
 * send-entry time, BEFORE materializeDraftSession. Do NOT re-read the composer
 * after createSession (the user may have typed more; the route adoption's
 * clearedChat may have wiped inputText). The captured values are what goes on
 * the wire.
 *
 * [agent] / [model] are the RESOLVED values (pendingAgent ?: infer ?: null)
 * at send-click, NOT a re-inference after the route flip.
 *
 * §blocker1: [fileReferences] is captured so the compare-and-clear in
 * [dispatchCapturedSend] can decide whether the chips still match the click-
 * time set (only clear if unchanged; preserve if the user edited during the
 * createSession await).
 */
internal data class CapturedSendPayload(
    val text: String,
    val attachments: List<ComposerImageAttachment>,
    val agent: String?,
    val model: Message.ModelInfo?,
    val fileReferences: List<ComposerFileReference> = emptyList(),
)

/**
 * §unified-nav (A5.3) + §blocker2: the snapshot of the draft surface captured at
 * send-click time, used by [adoptMaterializedSessionRoute]'s CAS to decide
 * whether the user is STILL on the draft surface when createSession returns
 * (the network round-trip is a suspend point; the user may have navigated away
 * mid-create). If [stillOwnsDraftSurface] returns false against the live
 * committed [StoreState], the adoption is list-only (session upsert +
 * pendingCreate, NO route/nav/content transition) — the user left the draft
 * surface, so the route must NOT hijack the new screen.
 *
 * §blocker2: [hostProfileId] is captured from [HostState.currentHostProfileId]
 * (a [StoreState] field that IS updated on host switch — see
 * HostProfileController / ConnectionActions). A host/profile switch during the
 * createSession suspend boundary clears currentSessionId/content/draftWorkdir
 * but does NOT advance [StoreState.chatRouteInstance] or nav identity, so the
 * pre-blocker2 ownership predicate would still pass and the old-host response
 * would write into the WRONG host's state. Adding the host-identity comparison
 * makes a mid-create host switch fail the lease → list-only adoption.
 */
internal data class DraftRouteOrigin(
    val activeDestination: String?,
    val activeDestinationEpoch: Long,
    val requestedRoute: String,
    val requestedNavEpoch: Long,
    val routeInstance: Long,
    val serverGroupFp: String,
    /** §blocker2: the host profile id at send-click; compared inside the CAS. */
    val hostProfileId: String?,
) {
    /**
     * §unified-nav (A5.3) + §blocker2: returns true IFF the live committed
     * [before] still matches the snapshot captured at send-click. A mismatch
     * means the user navigated away mid-create (activeDestination/epoch changed,
     * OR currentSessionId is no longer null — another session was selected, OR
     * the nav identity changed — a route/navEpoch transition, OR the route-
     * instance token advanced — a chat open/close happened, OR the host identity
     * changed — a host/profile switch). In that case the adoption is list-only.
     */
    fun stillOwnsDraftSurface(before: StoreState): Boolean =
        before.nav.activeDestination == activeDestination &&
            before.nav.activeDestinationEpoch == activeDestinationEpoch &&
            before.chat.currentSessionId == null &&
            before.nav.lastRoute == requestedRoute &&
            before.nav.navEpoch == requestedNavEpoch &&
            before.chatRouteInstance == routeInstance &&
            before.host.currentHostProfileId == hostProfileId
}

/**
 * §unified-nav (A5.3): the result of [adoptMaterializedSessionRoute]. [adopted]
 * is true IFF the route/nav/content transition committed atomically. [routeInstance]
 * is the minted token (read from the committed snapshot) when adopted, null
 * otherwise. Callers gate the post-CAS ordering (A-E) on [adopted] / a non-null
 * [routeInstance].
 */
internal data class MaterializedRouteAdoption(
    val sessionId: String,
    val routeInstance: Long?,
    val adopted: Boolean,
)

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
    // §Phase 2 gpter fix: CONDITIONAL re-check after suspend fetch. The
    // suspend fetch above may have raced with another load/SSE that hydrated
    // this same session during the network wait. Re-check the LATEST state:
    // if a fresher entry now exists (in sessions ∪ directorySessions) with a
    // non-blank directory, it is authoritative → keep it and do NOT let the
    // fetched snapshot overwrite it. Only upsert `fetched` when the session
    // is still absent/blank. Either way, return the authoritative directory
    // (fresher entry's if present, else fetched's).
    // T1c: SessionUpserted owns the sessions-only upsert branch.
    val fetchedDir = fetched.directory
    val resolved: String?
    val casPath: String
    val st = store.sessionListFlow.value
    // §Phase 2 gpter round-3: non-blank predicate (same as branch 1) — a
    // blank-dir duplicate hydrated during the fetch is NOT authoritative.
    val current = (st.sessions + st.directorySessions.values.flatten())
        .firstOrNull { it.id == sessionId && !it.directory.isNullOrBlank() }
    if (current != null) {
        // Fresher authoritative entry hydrated during the fetch — keep it.
        resolved = current.directory
        casPath = "fetch-hit-fresher-kept"
    } else {
        resolved = fetchedDir
        casPath = "fetch-hit-cached"
        store.dispatch(AppAction.SessionUpserted(fetched))
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
 * **slimapi v0.2.2 T4 (P2b)**: now runs each entry through
 * [WorkdirPaths.normalizeDirectory] BEFORE `.distinct()` so the client's
 * fan-out count agrees with the server's normalize-dedup (server v0.2.2 strips
 * trailing slash + dedups, preserving root `/`). Without this, `/app` +
 * `/app/` could fan out as 2 distinct `?directory=` entries — pre-server-
 * normalize that is 2 routeTokens; post-server-normalize the server folds
 * them but the envelope still carries the redundant param. Tighten-to-align,
 * non-breaking (server is strictly more lenient).
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
        .map { WorkdirPaths.normalizeDirectory(it) }
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
                // T1c: SessionUpserted owns sessions-only upsert.
                store.dispatch(AppAction.SessionUpserted(fetched))
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
                // §blocker1: existing-session path — clear input immediately
                // (the command is consumed synchronously).
                composerController.setInputText("")
                appScope.launch {
                    repository.executeCommand(existing, cmd, arguments, directory = commandDirectory)
                        .onFailure { error ->
                            effectBus.tryEmitUiEvent(classifyCommandPostError(error, cmd))
                        }
                }
            } else if (store.composerFlow.value.draftWorkdir != null) {
                // §bug2 + §unified-nav A5.1 + §blocker1 + §blocker1-command:
                // materialize draft session, then execute the command on the
                // new session. The command payload (cmd / arguments /
                // commandDirectory) is captured here BEFORE
                // materializeDraftSession. Do NOT clear inputText here —
                // materializeDraftSession clears it on SUCCESSFUL adoption via
                // compare-and-clear (only if the user did NOT type new content
                // during the createSession await); on FAILURE the text survives
                // so the user can retry the command (do not lose it).
                //
                // §blocker1-command: capture the raw command text at click time
                // so the compare-and-clear on success can decide whether the
                // user typed more during the suspend boundary.
                val capturedCommandText = store.composerFlow.value.inputText
                materializeDraftSession(
                    capturedCommandText = capturedCommandText,
                    commandPost = { sid ->
                        repository.executeCommand(sid, cmd, arguments, directory = commandDirectory)
                            .onFailure { error ->
                                effectBus.tryEmitUiEvent(classifyCommandPostError(error, cmd))
                            }
                    }
                )
            } else {
                composerController.setInputText("")
                effectBus.tryEmitUiEvent(UiEvent.Error(R.string.chat_command_no_session, listOf(cmd)))
            }
            return
        }
    }
}

/** composer → chat → session creation. The full send-while-in-draft path. */
@MainThread
internal fun AppCore.sendMessage() {
    val draftWorkdir = store.composerFlow.value.draftWorkdir
    val existingSessionId = store.chatFlow.value.currentSessionId
    val text = store.composerFlow.value.inputText.trim()
    val attachments = store.composerFlow.value.imageAttachments
    if (text.isEmpty() && attachments.isEmpty()) return

    if (draftWorkdir != null && existingSessionId == null) {
        // §unified-nav A5.1: capture the IMMUTABLE send payload BEFORE
        // materializeDraftSession. Do NOT re-read the composer after
        // createSession (the user may have typed; the route adoption may
        // have cleared inputText). The captured values go on the wire.
        val chatState = store.chatFlow.value
        val visibleAgents = store.settingsFlow.value.agents
            .filter { it.isVisible }
            .map { it.name }
            .toSet()
        val payload = CapturedSendPayload(
            text = text,
            attachments = attachments.toList(),
            agent = chatState.pendingAgent ?: inferCurrentAgent(chatState.messages, visibleAgents),
            model = chatState.pendingModel ?: inferCurrentModel(chatState.messages, visibleAgents),
            // §blocker1: capture fileReferences for compare-and-clear.
            fileReferences = store.composerFlow.value.fileReferences.toList(),
        )
        materializeDraftSession(capturedPayload = payload)
        return
    }

    val sessionId = existingSessionId ?: return
    if (store.composerFlow.value.sendingSessionIds.contains(sessionId)) return
    dispatchSendMessage(sessionId)
}

/**
 * §bug2 → §unified-nav A5: Shared draft-session materialization. Detects draft
 * mode (composer has a draftWorkdir but no current session yet), captures the
 * [DraftRouteOrigin] snapshot, clears the draft, creates a new session, then
 * SYNCHRONOUSLY adopts the route via [adoptMaterializedSessionRoute] (the
 * aggregate CAS that fixes item 10-A), runs the post-CAS ordering (A-E), and
 * finally dispatches the captured send payload OR the command.
 *
 * Exactly ONE of [capturedPayload] / [commandPost] is non-null:
 *  - [capturedPayload] (send path from [sendMessage]): step D runs
 *    [dispatchCapturedSend].
 *  - [commandPost] (command path from [executeCommand]): step D runs the
 *    suspend command callback on appScope.
 *
 * [capturedCommandText] is the raw composer inputText at command-click time
 * (command path only). On adoption SUCCESS, the composer inputText is cleared
 * ONLY if its current value still equals [capturedCommandText] (compare-and-
 * clear — the user may have typed new content during the createSession suspend
 * boundary). If the user typed more, the newer content is PRESERVED.
 *
 * # Adoption success (routeInstance != null) — post-CAS strict ordering:
 *  A. settingsManager.lastRoute = "chat/$sid"   (persistence side-effect)
 *  B. persistSessionCache(...)
 *  C. startMaterializedRouteHydration(adoption)  (DIRECT call, NOT effect bus)
 *  D. dispatchCapturedSend(sid, payload, token)  OR  commandPost(sid)
 *  E. scheduleTitleRefreshAfterFirstMessage(sid)
 *
 * # Adoption failed (user navigated away mid-create):
 *  - do NOT set route/nav/content (the CAS was list-only);
 *  - still POST the captured payload as a background send for the new sid
 *    (do NOT hijack the current UI);
 *  - draftWorkdir is NOT restored (the user left the draft surface — that is
 *    WHY adoption failed).
 *
 * # createSession failure:
 *  - emit UiEvent.Error;
 *  - restore draftWorkdir WITH ownership guard (only if still on the draft
 *    surface — otherwise the user navigated away and the draft is gone).
 */
@MainThread
internal fun AppCore.materializeDraftSession(
    capturedPayload: CapturedSendPayload? = null,
    capturedCommandText: String? = null,
    commandPost: (suspend (sessionId: String) -> Unit)? = null,
) {
    val draftWorkdir = store.composerFlow.value.draftWorkdir ?: return
    // §unified-nav A5.3: capture the draft-surface origin snapshot BEFORE
    // clearing draftWorkdir. The CAS inside adoptMaterializedSessionRoute
    // compares this against the live committed state to decide adoption vs
    // list-only.
    val origin = captureDraftRouteOrigin()
    writeComposer { it.copy(draftWorkdir = null) }
    appScope.launch {
        repository.createSession(title = null, directory = draftWorkdir)   // §R18 Final 终审 fix (gpter): route to the draft workdir
            .onSuccess { session ->
                val now = System.currentTimeMillis()
                // §unified-nav A5.3: the aggregate adoption CAS. Commits
                // {session upsert, currentSessionId, chatRouteInstance token,
                // LoadedContent envelope, lastRoute, navEpoch, unread/lastViewed,
                // draftWorkdir=null} in ONE atomic mutateStateAndGet — NEVER
                // split into multiple writes (splitting reproduces item 10-A's
                // intermediate state where the route flipped but content was
                // stale/empty). On adoption failure (user navigated away) it
                // is list-only (session upsert + pendingCreate, NO route/nav/
                // content).
                val adoption = adoptMaterializedSessionRoute(session, now, origin)
                if (adoption.adopted) {
                    val token = adoption.routeInstance!!
                    // A. persistence side-effect.
                    settingsManager.lastRoute = "chat/${session.id}"
                    // B. persist the session cache.
                    persistSessionCache(
                        settingsManager = settingsManager,
                        sessions = store.sessionListFlow.value.sessions,
                        currentId = session.id,
                        currentWorkdir = settingsManager.currentWorkdir,
                        revertCutoffs = store.chatFlow.value.revertCutoffs,
                    )
                    // C. DIRECT route hydration (NOT effect bus, NOT
                    //    VerifyAndHydrate). Pre-creates the empty
                    //    LoadedContent(sid, token) envelope in the CAS above;
                    //    the first real message arrives via SSE message.updated
                    //    and lands in this envelope. loadMessagesForEffect will
                    //    dispatch ChatContentLoaded(expectedRouteInstance=token)
                    //    which the CAS-guarded reducer accepts.
                    startMaterializedRouteHydration(adoption)
                    // D. dispatch the captured payload (send) OR the command.
                    if (capturedPayload != null) {
                        dispatchCapturedSend(session.id, capturedPayload, token)
                    } else if (commandPost != null) {
                        // §blocker1-command compare-and-clear: createSession is a
                        // SUSPEND boundary — the user may have typed NEW content
                        // into the composer during the await. Only clear the
                        // command text if its CURRENT value still equals the
                        // CAPTURED command text (trimmed-equals, same
                        // normalization as the send path). If the user typed
                        // more (current != captured), PRESERVE the current
                        // content — the command itself still uses the captured
                        // cmd/arguments (the click-time intent); only the CLEAR
                        // is conditional. Mirrors [dispatchCapturedSend]'s
                        // compare-and-clear for the send path.
                        val commandTextMatches = capturedCommandText != null &&
                            store.composerFlow.value.inputText.trim() == capturedCommandText.trim()
                        if (commandTextMatches) {
                            composerController.setInputText("")
                        }
                        appScope.launch { commandPost(session.id) }
                    }
                    // E. schedule the title refresh (bounded retry, item 10-B).
                    scheduleTitleRefreshAfterFirstMessage(session.id)
                } else {
                    // §unified-nav A5.4 + §blocker2: adoption failed (user
                    // navigated away OR host switched mid-create). Do NOT set
                    // route/nav/content (the CAS was list-only).
                    //
                    // §blocker2 host-switch gate: if the host identity changed
                    // during createSession, do NOT send the payload to the WRONG
                    // host's repository. The session was still created (list-only
                    // upsert) but the send would cross-host-pollute. Drop it +
                    // surface a brief error so the user knows.
                    // §blocker2 host-switch gate: check BOTH the store's
                    // hostProfileId (the field the CAS compares — updated on
                    // selectHostProfile / connect) AND the fp provider. Either
                    // changing means the host identity changed mid-create → do
                    // NOT send to the wrong host.
                    val hostChanged = store.hostFlow.value.currentHostProfileId != origin.hostProfileId ||
                        currentServerGroupFp() != origin.serverGroupFp
                    if (hostChanged) {
                        DebugLog.w(
                            "Materialize",
                            "adoption failed (host switched mid-create): sid=${session.id} send DROPPED (would cross-host)",
                        )
                        effectBus.tryEmitUiEvent(
                            UiEvent.Error(R.string.error_create_session_in_workdir_failed, listOf(draftWorkdir, "host switched")),
                        )
                    } else if (capturedPayload != null) {
                        // Same host, user navigated away: POST the captured
                        // payload as a background send for the new sid (do NOT
                        // hijack the current UI — the user is elsewhere).
                        DebugLog.i(
                            "Materialize",
                            "adoption failed (user navigated away): sid=${session.id} still background-sending",
                        )
                        launchBackgroundSendForMaterializeFailure(session.id, capturedPayload)
                    } else if (commandPost != null) {
                        DebugLog.i(
                            "Materialize",
                            "adoption failed (user navigated away): sid=${session.id} still executing command",
                        )
                        appScope.launch { commandPost(session.id) }
                    }
                }
            }
            .onFailure { error ->
                // §unified-nav A5.4: restore draftWorkdir WITH ownership guard
                // — only if the user is STILL on the draft surface (origin
                // still owns the live state). If the user navigated away, the
                // draft is gone and restoring would re-enter draft mode on the
                // wrong screen.
                if (origin.stillOwnsDraftSurface(store.stateFlow.value)) {
                    writeComposer { it.copy(draftWorkdir = draftWorkdir) }
                }
                effectBus.tryEmitUiEvent(UiEvent.Error(R.string.error_create_session_in_workdir_failed, listOf(draftWorkdir, error.message ?: "unknown error")))
            }
    }
}

/**
 * §unified-nav A5.3: capture the [DraftRouteOrigin] snapshot from the live
 * committed state. Called at send-entry (BEFORE clearing draftWorkdir) so the
 * CAS can later detect whether the user navigated away mid-create.
 */
private fun AppCore.captureDraftRouteOrigin(): DraftRouteOrigin {
    val nav = store.navFlow.value
    return DraftRouteOrigin(
        activeDestination = nav.activeDestination,
        activeDestinationEpoch = nav.activeDestinationEpoch,
        requestedRoute = nav.lastRoute,
        requestedNavEpoch = nav.navEpoch,
        routeInstance = store.stateFlow.value.chatRouteInstance,
        serverGroupFp = currentServerGroupFp(),
        // §blocker2: capture the host profile id so the CAS can detect a
        // mid-create host switch (host.currentHostProfileId changes on
        // selectHostProfile / connect).
        hostProfileId = store.hostFlow.value.currentHostProfileId,
    )
}

/**
 * §unified-nav A5.3: the aggregate adoption CAS. Runs inside the materialize
 * success block (BEFORE the send/command). Mints the route-instance token
 * INSIDE the transform, commits the route/nav/content/currentSessionId/unread
 * atomically, and returns the [MaterializedRouteAdoption] verdict. On adoption
 * failure (user navigated away) it is list-only.
 *
 * Main-thread contract: called from appScope (Dispatchers.Main.immediate) after
 * the createSession suspend point. The mutateStateAndGet CAS is main-thread
 * serial.
 */
@MainThread
internal fun AppCore.adoptMaterializedSessionRoute(
    session: Session,
    viewedAt: Long,
    origin: DraftRouteOrigin,
): MaterializedRouteAdoption {
    val route = "chat/${session.id}"
    val committed = store.mutateStateAndGet { before ->
        if (!origin.stillOwnsDraftSurface(before)) {
            // User navigated away mid-create: upsert session + pendingCreate
            // ONLY — do NOT set currentSessionId / nav / content. The send is
            // a background op; the route must NOT hijack the new screen.
            materializeListOnly(before, session, viewedAt)
        } else {
            val token = before.chatRouteInstance + 1L
            val materialized = reduceDraftSessionMaterialized(
                before,
                AppAction.DraftSessionMaterialized(session, viewedAt),
            )
            // §7.2: clear the loaded-chat payload (the prior session's content
            // must NOT survive the route flip), then weld the new session id +
            // the fresh token onto the LoadedContent envelope. IMPORTANT: do
            // NOT fabricate an optimistic user Message — there is no client
            // temp-ID reconciliation; the empty LoadedContent(sid, token)
            // envelope is pre-created so the first real message (SSE
            // message.updated) lands in it.
            val clearedChat = materialized.chat.clearLoadedChatPayload()
            materialized.copy(
                chatRouteInstance = token,
                chat = clearedChat.copy(
                    currentSessionId = session.id,
                    content = LoadedContent(sessionId = session.id, routeInstance = token),
                ),
                nav = materialized.nav.copy(
                    lastRoute = route,
                    navEpoch = materialized.nav.navEpoch + 1L,
                ),
            )
        }
    }
    val adopted = committed.nav.lastRoute == route &&
        committed.chat.currentSessionId == session.id &&
        committed.chat.content?.sessionId == session.id &&
        committed.chat.content?.routeInstance == committed.chatRouteInstance
    return MaterializedRouteAdoption(
        sessionId = session.id,
        routeInstance = if (adopted) committed.chatRouteInstance else null,
        adopted = adopted,
    )
}

/**
 * §unified-nav A5.3: list-only upsert (adoption-failure path). Upserts the
 * session + pendingCreate tracking but does NOT set currentSessionId / nav /
 * content. The session appears in the list for a later manual open.
 */
private fun materializeListOnly(before: StoreState, session: Session, viewedAt: Long): StoreState = before.copy(
    sessionList = before.sessionList.copy(
        sessions = upsertSession(before.sessionList.sessions, session),
        pendingCreateIds = before.sessionList.pendingCreateIds + session.id,
        pendingCreatedAt = before.sessionList.pendingCreatedAt + (session.id to viewedAt),
    ),
)

/**
 * §unified-nav A5.4-C: DIRECT route hydration call (NOT effect bus, NOT
 * VerifyAndHydrate). The CAS already pre-created the empty
 * LoadedContent(sid, token) envelope; this launches the message load which
 * dispatches ChatContentLoaded(expectedRouteInstance=token). The §7.2 CAS in
 * [reduceChatContentLoaded] accepts IFF the live token + currentSessionId
 * match — both were set in the adoption CAS — so the loaded content lands in
 * the envelope. forceInitialWindow=true bypasses any stale slim watermark.
 */
@MainThread
internal fun AppCore.startMaterializedRouteHydration(adoption: MaterializedRouteAdoption) {
    val token = adoption.routeInstance ?: return
    loadMessagesForEffect(
        sessionId = adoption.sessionId,
        resetLimit = true,
        forceInitialWindow = true,
        expectedRouteInstance = token,
    )
}

/**
 * §unified-nav (A5.4-D) + §blocker1: dispatch the captured send payload using
 * the token minted in the adoption CAS (NOT a fresh store.slices.routeInstanceFor(sid)
 * read). The post-send refresh guard uses the captured token: only refreshes if
 * the route still owns this session under the token (routeInstanceFor(sid)==token).
 *
 * §blocker1 compare-and-clear: createSession is a SUSPEND boundary — the user
 * can keep typing in Composer during the await (there is no materialize-in-
 * flight disable). On success, the composer is cleared ONLY for fields whose
 * CURRENT value still equals the CAPTURED payload value. If the user typed more
 * (current != captured), the user's newer content is PRESERVED — the captured
 * payload is still what gets POSTed (the click-time intent), but the user's
 * in-progress edit must survive for their next send. The persisted draft is
 * only cleared when the text field was actually cleared.
 */
@MainThread
internal fun AppCore.dispatchCapturedSend(
    sessionId: String,
    payload: CapturedSendPayload,
    expectedRouteInstance: Long,
) {
    if (payload.text.isEmpty() && payload.attachments.isEmpty()) return
    if (store.composerFlow.value.sendingSessionIds.contains(sessionId)) return

    writeComposer { state -> state.copy(sendingSessionIds = state.sendingSessionIds + sessionId) }

    // §blocker1 compare-and-clear: only clear a field if its CURRENT value
    // still equals the captured payload value. The captured payload is still
    // what gets POSTed (correct — the send is the click-time intent). Only the
    // CLEAR is conditional so the user's newer text/attachments survive.
    val currentComposer = store.composerFlow.value
    val textMatches = currentComposer.inputText.trim() == payload.text
    val attachmentsMatch = currentComposer.imageAttachments == payload.attachments
    val fileRefsMatch = currentComposer.fileReferences == payload.fileReferences
    writeComposer { c ->
        c.copy(
            inputText = if (textMatches) "" else c.inputText,
            imageAttachments = if (attachmentsMatch) emptyList() else c.imageAttachments,
            fileReferences = if (fileRefsMatch) emptyList() else c.fileReferences,
        )
    }
    // Only persist the draft clear if the text was actually cleared (otherwise
    // the user's newer text is still in the composer and the normal draft
    // debounce will persist it).
    if (textMatches) {
        settingsManager.setDraftText(currentServerGroupFp(), sessionId, "")
        settingsManager.flushDraftText()
    }

    // §Wave5b-Q13: snap the message list to the newest message on send.
    sessionSwitcher.requestLatestScroll(sessionId)

    val currentSession = currentSession(store.sessionListFlow.value.sessions, store.chatFlow.value.currentSessionId)
    if (currentSession?.isArchived == true) {
        appScope.launch {
            repository.updateSessionArchived(sessionId, -1L)
                .onSuccess { updated ->
                    store.dispatch(AppAction.SessionUpserted(updated))
                    launchCapturedSend(sessionId, payload, expectedRouteInstance)
                }
                .onFailure { error ->
                    effectBus.tryEmitUiEvent(UiEvent.Error(R.string.error_restore_session_failed, listOf(errorMessageOrFallback(error, "unknown error"))))
                    writeComposer { c -> c.copy(sendingSessionIds = c.sendingSessionIds - sessionId) }
                }
        }
        return
    }
    launchCapturedSend(sessionId, payload, expectedRouteInstance)
}

/**
 * §unified-nav (A5.4-D) + §blocker1: the captured-payload launchSendMessage call. Uses the
 * captured text / attachments / agent / model (NOT a composer re-read). The
 * onRefreshMessages guard uses the captured [expectedRouteInstance]: only
 * refreshes if routeInstanceFor(sid) still equals the token (the route still
 * owns this session); a route flip (user navigated away) drops the refresh.
 */
private fun AppCore.launchCapturedSend(
    sessionId: String,
    payload: CapturedSendPayload,
    expectedRouteInstance: Long,
) {
    launchSendMessage(
        scope = appScope,
        repository = repository,
        slices = store.slices,
        sessionId = sessionId,
        text = payload.text,
        attachments = payload.attachments,
        agent = payload.agent,
        model = payload.model,
        // §unified-nav A5.4-D: post-send refresh guard uses the CAPTURED token.
        // Only refresh if routeInstanceFor(sid)==token (the route still owns
        // this session); otherwise the user navigated away and the refresh
        // would clobber the new screen's content.
        onRefreshMessages = { sid, reset ->
            val liveToken = store.slices.routeInstanceFor(sid)
            if (liveToken == expectedRouteInstance) {
                loadMessagesForEffect(sid, reset, expectedRouteInstance = expectedRouteInstance)
            }
        },
        onSuccess = {
            // §blocker1: composer clear + draft persist were handled by the
            // compare-and-clear in dispatchCapturedSend. onSuccess is a no-op
            // for the composer (the user's newer text, if any, was already
            // preserved). The sendingSessionIds clear is in onComplete below.
        },
        onComplete = {
            writeComposer { state -> state.copy(sendingSessionIds = state.sendingSessionIds - sessionId) }
        },
        emit = EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
    )
    // §chat-ux-batch T7 (B2): clear the transient pending picks AFTER the send
    // launches — the picks were consumed; the next send starts fresh.
    store.mutateChat { it.copy(pendingAgent = null, pendingModel = null) }
}

/**
 * §unified-nav A5.4: adoption-failure background send. The user navigated away
 * mid-create; the captured payload is still POSTed for the new sid so the
 * session is not lost, but the UI is NOT hijacked (the user is on a different
 * screen). Uses the captured payload verbatim; no composer mutation.
 */
private fun AppCore.launchBackgroundSendForMaterializeFailure(
    sessionId: String,
    payload: CapturedSendPayload,
) {
    launchSendMessage(
        scope = appScope,
        repository = repository,
        slices = store.slices,
        sessionId = sessionId,
        text = payload.text,
        attachments = payload.attachments,
        agent = payload.agent,
        model = payload.model,
        // Background send: no refresh (the user is not viewing this session).
        onRefreshMessages = { _, _ -> },
        onSuccess = { },
        onComplete = { },
        emit = EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
    )
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

/**
 * §unified-nav B (item 10-B): bounded retry loop for the LLM-generated title
 * after a new session's first message. The server generates the title
 * asynchronously in prompt-loop step 1; the title may not be ready by the
 * first fetch, so this retries up to [TITLE_RETRY_MAX_ATTEMPTS] times with
 * [MainViewModelTimings.titleRefreshDelayMs] between each (~5s initial, ~5s
 * between, max ~6 attempts / ~35s window).
 *
 * Success condition: `refreshed.title?.isNotBlank() == true`. CRITICAL: a
 * null/blank title snapshot is NEVER written back (it would overwrite a newer
 * SSE/REST title that arrived during the retry window). On success, ONLY the
 * `title` field is merged into the existing session object(s) — NOT a whole-
 * object `upsertSession` replace (which would clobber newer state).
 *
 * Updates BOTH [SessionListState.sessions] AND every entry of that id in
 * [SessionListState.directorySessions] so the new title surfaces in the
 * sessions list (home hub) AND the chat top bar (which resolves the session
 * from the union store).
 *
 * Virtual-time friendly: uses `delay()` which the test dispatcher (via
 * [MainDispatcherRule]) drives with virtual time — no real sleeps.
 */
private fun AppCore.scheduleTitleRefreshAfterFirstMessage(sessionId: String) {
    appScope.launch {
        var attempts = 0
        while (attempts < TITLE_RETRY_MAX_ATTEMPTS) {
            delay(MainViewModelTimings.titleRefreshDelayMs)
            attempts++
            val refreshed = runSuspendCatching { repository.getSession(sessionId).getOrNull() }.getOrNull()
            val title = refreshed?.title
            if (!title.isNullOrBlank()) {
                // SUCCESS: merge ONLY the title field into the existing session
                // object(s). Do NOT whole-object replace (would overwrite newer
                // SSE/REST state). Do NOT write null/blank.
                writeSessionList { state ->
                    state.copy(
                        sessions = state.sessions.map { if (it.id == sessionId) it.copy(title = title) else it },
                        directorySessions = state.directorySessions.mapValues { (_, list) ->
                            list.map { if (it.id == sessionId) it.copy(title = title) else it }
                        },
                    )
                }
                return@launch
            }
            // null/blank title → retry (do NOT write back null/blank).
        }
        // Deadline reached: stop. The next REST refresh / SSE session.updated
        // will land the title when the server eventually generates it.
    }
}

/** §unified-nav B: max fetch attempts for the title retry loop. */
private const val TITLE_RETRY_MAX_ATTEMPTS = 6

private fun AppCore.dispatchSendMessage(sessionId: String) {
    val composer = store.composerFlow.value
    if (composer.sendingSessionIds.contains(sessionId)) return
    val text = composer.inputText.trim()
    val attachments = composer.imageAttachments
    if (text.isEmpty() && attachments.isEmpty()) return

    // §streaming-state-sync-diag (runtime-gated): snapshot the lifecycle layer
    // (L1/L2Active = SSE live; L2Idle/L3 = SSE off) + the current status +
    // sending set AT SEND-DECISION TIME, so we can confirm whether SSE was
    // actually live when the user hit send. Gated on the runtime verbose-diag
    // toggle (default OFF) so release builds can opt in WITHOUT a reinstall.
    if (cn.vectory.ocdroid.util.DebugLog.verboseDiagEnabled) {
        DebugLog.i(
            "LayerDiag",
            "dispatchSendMessage sid=$sessionId layer=${connectionCoordinator.diagLayer} " +
                "status=${store.sessionListFlow.value.sessionStatuses[sessionId]?.type} " +
                "sending=${store.composerFlow.value.sendingSessionIds}",
        )
    }

    writeComposer { state -> state.copy(sendingSessionIds = state.sendingSessionIds + sessionId) }
    // §streaming-state-sync-diag: optimistic sendingSessionIds set at send time.
    DebugLog.i("SendDiag", "optimistic sendingSessionIds set sid=$sessionId")
    settingsManager.setDraftText(currentServerGroupFp(), sessionId, "")
    // §C1: flush the draft clear so it is durable BEFORE the send launches —
    // a crash / background right after Send must not leave the now-empty
    // composer's cleared draft unwritten (the debounce could otherwise keep
    // the prior text pending and restore it on resume).
    settingsManager.flushDraftText()
    // §1B-FIX (I4): clear inputText, imageAttachments AND fileReferences
    // when the user hits Send — chips must not leak to the next prompt.
    // The `text` + `attachments` locals above already captured the values
    // that go on the wire, so this is safe to clear immediately.
    writeComposer { it.copy(inputText = "", imageAttachments = emptyList(), fileReferences = emptyList()) }

    // §Wave5b-Q13: snap the message list to the newest message when the user
    // sends. Replaces the pre-Wave5b `PendingJumpToLatestSet(sessionId)`
    // dispatch with the unified ScrollRequested(Latest) — same intent, same
    // single consumer, same compare-and-clear semantics. Uses the
    // SessionSwitcher.requestLatestScroll helper which BYPASSES switchTo's
    // same-session no-op guard (the user is sending from the CURRENT session;
    // switchTo would early-return and NOT generate a fresh Latest intent).
    // Placed here (after the early-return guards + composer clear, BEFORE
    // the archived/direct branches reach launchSendMessage) so it fires
    // exactly once on the common send path.
    sessionSwitcher.requestLatestScroll(sessionId)

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
            // §streaming-send-ux-fix: NO onRefreshSessions here — the post-send
            // full-list refresh was the root cause of the "no live UI feedback"
            // bug on normal sends. See launchSendMessage.onSuccess comment for
            // the two coupled failure modes (status clobber + swallowed delayed
            // reload). The slash path (executeCommand) never had this call and
            // relied on SSE + the targeted message reload — same shape now.
            onSuccess = {
                settingsManager.setDraftText(currentServerGroupFp(), sessionId, "")
                // §C1: flush the durable clear now that the send succeeded —
                // the composer is confirmed empty for this session and any
                // pending debounced clear must land on disk immediately.
                settingsManager.flushDraftText()
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
                    // T1c: SessionUpserted owns sessions write (unarchive-before-send).
                    store.dispatch(AppAction.SessionUpserted(updated))
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
        loadMessagesForEffect(
            sessionId = sid,
            resetLimit = reset,
            expectedRouteInstance = store.slices.routeInstanceFor(sid),
        )
    }
}

// ════════════════════════════════════════════════════════════════════════════
// Dispatch helpers (one per ControllerEffect branch + the cross-domain
// callers). Each calls the same primitive the matching VM method uses.
// ════════════════════════════════════════════════════════════════════════════

/**
 * §sse-rest-fallback (TODO 3): how long SSE must stay terminally
 * [ConnectionPhase.Disconnected] before the AUTOMATIC cold-start
 * ([performGlobalColdStartRefresh] with defaults) upgrades to a clear+
 * UNANCHORED fetch. Picked at the upper end of the health-probe retry cadence:
 * [cn.vectory.ocdroid.ui.controller.ConnectionHealthProbe] retries with
 * exponential backoff (≤30s); a disconnect lasting well past a few retry
 * cycles is a REAL outage, not a transient blip. 90s balances "self-heal a
 * real outage promptly" against "don't white-flash + drop loadMore history on
 * every brief network hiccup" (the latter keeps the cheap anchored path).
 */
internal const val SSE_DISCONNECT_UNANCHORED_THRESHOLD_MS = 90_000L

/**
 * §sse-rest-fallback (TODO 3): pure predicate — should the AUTOMATIC cold-start
 * upgrade to an UNANCHORED (since=0L) fetch? True IFF the connection phase is
 * terminally [ConnectionPhase.Disconnected] AND [ConnectionState.disconnectedSince]
 * is at least [SSE_DISCONNECT_UNANCHORED_THRESHOLD_MS] in the past. Pure (takes
 * `now`) so it is unit-testable with a controlled clock. NOT triggered when SSE
 * is healthy (Connected / Connecting / Reconnecting / Idle) or freshly
 * disconnected (< threshold) — the normal anchored catch-up / three-way merge
 * is preserved, so SSE-up cold-starts never degenerate into a clear.
 */
internal fun shouldAutoUnanchorOnColdStart(
    phase: ConnectionPhase,
    disconnectedSince: Long?,
    now: Long,
    thresholdMs: Long = SSE_DISCONNECT_UNANCHORED_THRESHOLD_MS,
): Boolean = phase is ConnectionPhase.Disconnected &&
    disconnectedSince != null &&
    (now - disconnectedSince) >= thresholdMs

// ════════════════════════════════════════════════════════════════════════════
// §sse-feedback-ux (P2-1): user-facing SSE / live-updates status, derived
// PURELY from the authoritative connection slice. The REST-fallback mechanism
// above (predicate + stamping + auto-unanchor) keeps the DATA correct when SSE
// is down, but the USER had no in-chat signal that live updates were paused or
// how long the outage had lasted — only a tiny home-page status dot and a
// transient staleNotice snackbar fired on the foreground-catch-up path. This
// projection closes that gap: it is a READ-ONLY derivation (NO new writable
// truth, NO mutation of the connection slice or the authority reducer) consumed
// by the in-chat [cn.vectory.ocdroid.ui.chat.SseDisconnectBanner] so a
// sustained terminal disconnect (or a debug REST-only mode) is surfaced
// persistently with an elapsed-time label + a one-tap Refresh that reuses the
// existing REST-fallback recovery path ([ChatViewModel.refreshCurrentSession]).
// ════════════════════════════════════════════════════════════════════════════

/**
 * §sse-feedback-ux: cadence at which the in-chat disconnect banner refreshes
 * its elapsed-time label while a disconnect is visible. Coarse on purpose —
 * the label is "Nm / Nh ago", so sub-minute precision is noise. Cheap enough
 * that the ticker (gated to WhileSubscribed in the ViewModel) is a non-issue.
 */
internal const val SSE_FEEDBACK_TICK_MS = 30_000L

/**
 * §sse-feedback-ux: user-facing SSE / live-updates status. A pure projection
 * of ([ConnectionPhase] + [ConnectionState.disconnectedSince] + the SSE
 * transport-delivery signal) — NOT a writable slice. The banner surfaces
 * [Disconnected] / [Disabled] persistently; the other variants are carried so
 * the derivation is exhaustive and available to future read sites (e.g. an
 * empty-state label) without a second pass.
 *
 *  - [Live]              — health Connected AND the SSE transport has proven
 *                          delivery (the breathing dot is green/blue). Live
 *                          updates are flowing.
 *  - [WaitingForStream]  — health Connected but the SSE transport has NOT yet
 *                          delivered a frame (the "stall" case: HTTP is up,
 *                          SSE is not). Kept distinct so a future surface can
 *                          distinguish "connected, waiting" from a hard down.
 *  - [Connecting]        — a connect probe is in flight.
 *  - [Reconnecting]      — host-switch / retry-loop reconnect signal; carries
 *                          the attempt counter when the phase provides one.
 *  - [AwaitingTofuTrust] — SSL/cert decision pending (the TOFU dialog overlays;
 *                          the banner stays silent — [showBanner] is false).
 *  - [Disconnected]      — terminal disconnect (retries exhausted / one-shot
 *                          failure). Carries [sinceMs] (the stamped transition
 *                          time) AND [now] (the derivation clock) so the
 *                          banner can render an elapsed-time label AND so the
 *                          emitted value varies each tick (the ViewModel ticker
 *                          re-derives every [SSE_FEEDBACK_TICK_MS]; because
 *                          [now] changes, distinctUntilChanged passes the tick
 *                          through ONLY while a disconnect is shown — when
 *                          healthy the equal [Live]/[Idle] emissions are
 *                          dropped, so there is zero churn on the happy path).
 *  - [Disabled]          — the debug `sse_disabled` flag is ON (REST-only by
 *                          user choice); surfaced so the banner explains
 *                          "live updates are off" instead of looking broken.
 *  - [Idle]              — no connection activity (initial / clean reset).
 */
/**
 * §sse-feedback-ux (§1.1): semantic category for the in-chat banner. Pure
 * derivation — what to show, separate from when to show it (handled by
 * [BannerHysteresisState] / [bannerHysteresisReducer]).
 *
 * Priority: AUTH_FAILURE (more specific/actionable) wins over REST_OUTAGE when
 * [ConnectionState.mtlsDegradedError] is non-null.
 */
internal enum class BannerCategory { REST_OUTAGE, AUTH_FAILURE, SSE_STALLED, USER_DISABLED }

sealed interface SseConnectionFeedback {
    data object Live : SseConnectionFeedback
    data object WaitingForStream : SseConnectionFeedback
    data object Connecting : SseConnectionFeedback
    data class Reconnecting(val attempt: Int?, val maxAttempts: Int?) : SseConnectionFeedback
    data object AwaitingTofuTrust : SseConnectionFeedback
    data class Disconnected(val sinceMs: Long, val now: Long) : SseConnectionFeedback
    data object Disabled : SseConnectionFeedback
    data object Idle : SseConnectionFeedback
}

/**
 * §sse-feedback-ux: pure derivation of [SseConnectionFeedback] from the
 * authoritative connection inputs. Pure (all inputs are params, no store /
 * no clock side-effect) so it is unit-testable with a controlled clock and
 * exhaustively covers every [ConnectionPhase] variant (no `else` — the
 * compiler forces an update when a new phase is added, mirroring
 * [ConnectionPhase.displayTextForEmptyState]).
 *
 * The single source of truth for [ConnectionState.disconnectedSince] is the
 * auto-stamper in [SharedStateStore.mutateConnection]
 * ([stampDisconnectedSince]); this function only READS it. When the stamper
 * has not yet run (e.g. a test that sets Disconnected directly without a
 * timestamp), it falls back to [now] so the elapsed label is "just now"
 * rather than crashing on null arithmetic.
 */
internal fun deriveSseConnectionFeedback(
    phase: ConnectionPhase,
    disconnectedSince: Long?,
    sseConnected: Boolean,
    now: Long,
    /** §1.1: mTLS cert/credential degradation — null = no auth issue.
     *  Read by [SseConnectionFeedback.bannerCategory] for AUTH_FAILURE / REST_OUTAGE
     *  disambiguation. Kept as a param so this function stays PURE. */
    mtlsDegradedError: String? = null,
): SseConnectionFeedback = when (phase) {
    ConnectionPhase.Idle -> SseConnectionFeedback.Idle
    ConnectionPhase.Connecting -> SseConnectionFeedback.Connecting
    ConnectionPhase.Connected -> if (sseConnected) SseConnectionFeedback.Live else SseConnectionFeedback.WaitingForStream
    ConnectionPhase.Reconnecting -> SseConnectionFeedback.Reconnecting(attempt = null, maxAttempts = null)
    is ConnectionPhase.ReconnectingAttempt -> SseConnectionFeedback.Reconnecting(attempt = phase.attempt, maxAttempts = phase.maxAttempts)
    ConnectionPhase.AwaitingTofuTrust -> SseConnectionFeedback.AwaitingTofuTrust
    ConnectionPhase.Disconnected ->
        SseConnectionFeedback.Disconnected(sinceMs = disconnectedSince ?: now, now = now)
    ConnectionPhase.SseDisabled -> SseConnectionFeedback.Disabled
}

// ════════════════════════════════════════════════════════════════════════════
// §sse-feedback-ux (§1.3): Banner hysteresis — grace / min-display / recover-hide
// anti-flash reducer. Separates "WHAT to show" (pure [bannerCategory]) from
// "WHEN to show" (stateful hysteresis with a controllable clock).
// ════════════════════════════════════════════════════════════════════════════

/**
 * §1.3: Tunable timing parameters for the banner hysteresis reducer.
 * These are ANTI-FLASH constants, NOT data-recovery thresholds (do NOT
 * reuse [SSE_DISCONNECT_UNANCHORED_THRESHOLD_MS]).
 *
 * @param showGraceMs       disconnect must sustain this long before showing
 *                          (transient blip → never shown). Default 5s.
 * @param minDisplayMs      once shown, must remain visible at least this long
 *                          (anti one-frame flash). Default 3s.
 * @param recoverHideDelayMs after recovery, delay this long before hiding
 *                          (anti flapping on intermittent connectivity).
 *                          Default 5s.
 */
internal data class BannerHysteresisConfig(
    val showGraceMs: Long = 5_000L,
    val minDisplayMs: Long = 3_000L,
    val recoverHideDelayMs: Long = 5_000L,
)

/**
 * §C3: Input to the hysteresis reducer — carries both the semantic category
 * and the captured auth reason (mtlsDegradedError) at the moment the category
 * was established. The reducer preserves this payload through transitions
 * so the displayed info is always a coherent snapshot.
 */
internal data class BannerCategoryInput(
    val category: BannerCategory,
    val authReason: String?,
)

/**
 * §1.3: UI-facing visibility — what the banner composable reads.
 * [Hidden] = render nothing; [Showing] = render with the given category.
 *
 * §C3: [Showing] carries [authReason] as part of a coherent payload.
 */
internal sealed class BannerVisibility {
    data object Hidden : BannerVisibility()
    data class Showing(
        val category: BannerCategory,
        /** §C3: captured auth reason, non-null only for AUTH_FAILURE. */
        val authReason: String?,
        val sinceMs: Long,
    ) : BannerVisibility()
}

/**
 * §1.3: Internal phase of the hysteresis state machine. Tracks intermediate
 * states (PendingShow/PendingHide) that are invisible to the UI but carry
 * timing information for the reducer transitions.
 *
 * §C3: All phases that carry a category now also carry [authReason] for a
 * coherent displayed payload (no torn-read between visibility and feedback).
 */
internal sealed class BannerHysteresisPhase {
    data object Hidden : BannerHysteresisPhase()
    data class PendingShow(
        val category: BannerCategory,
        val authReason: String?,
        val atMs: Long,
    ) : BannerHysteresisPhase()
    data class Showing(
        val category: BannerCategory,
        val authReason: String?,
        val sinceMs: Long,
    ) : BannerHysteresisPhase()
    data class PendingHide(
        val category: BannerCategory,
        val authReason: String?,
        val atMs: Long,
        val sinceMs: Long,
    ) : BannerHysteresisPhase()
}

/**
 * §1.3: Combined state of the hysteresis reducer. [visibility] is what the UI
 * checks; [phase] is the internal machine state that drives transitions.
 */
internal data class BannerHysteresisState(
    val visibility: BannerVisibility = BannerVisibility.Hidden,
    internal val phase: BannerHysteresisPhase = BannerHysteresisPhase.Hidden,
)

/**
 * §1.3: Pure hysteresis state-machine reducer. Drives the "when to show"
 * logic with grace / min-display / recover-hide delay, independent of the
 * "what category" logic ([bannerCategory]).
 *
 * State machine transitions (implemented exactly — this is the anti-flash contract):
 * The `authReason` field is pure payload — it is carried through transitions
 * but NEVER influences the transition logic (which only checks null-ness of
 * [input]):
 *
 * ```
 * Hidden        + input!=null          → PendingShow(now)                    // grace starts; NOT visible
 * PendingShow   + input!=null & now≥at+grace → Showing(now)                 // grace elapses → visible
 * PendingShow   + input==null          → Hidden                              // recovered within grace: never shown
 * PendingShow   + input!=null & now<at+grace → PendingShow(at)              // stay in grace, update payload
 * Showing       + input!=null          → Showing(since)                      // stay; update payload
 * Showing       + input==null & now≥since+minDisplay → PendingHide(now)      // min-display met, start hide delay
 * Showing       + input==null & now<since+minDisplay → Showing               // min-display NOT met: keep showing
 * PendingHide   + input!=null          → Showing(since)                      // re-show (anti flap)
 * PendingHide   + input==null & now≥at+recoverDelay → Hidden
 * PendingHide   + input==null & now<at+recoverDelay → PendingHide
 * ```
 *
 * §C3: [input] carries both the semantic [BannerCategory] and the
 * [BannerCategoryInput.authReason] captured at the moment the category was
 * established. The reducer preserves `authReason` through all transitions
 * so the displayed payload is always a coherent snapshot (no torn reads
 * between visibility and the underlying feedback).
 *
 * Inject a controllable [now] clock for testability. Pure — no side effects.
 *
 * @param prev    previous state.
 * @param input   current banner category input (null = not banner-worthy right now).
 * @param now     current wall-clock ms (injected for testability).
 * @param config  timing parameters.
 */
internal fun bannerHysteresisReducer(
    prev: BannerHysteresisState,
    input: BannerCategoryInput?,
    now: Long,
    config: BannerHysteresisConfig = BannerHysteresisConfig(),
): BannerHysteresisState {
    val nextPhase: BannerHysteresisPhase = when (val p = prev.phase) {
        is BannerHysteresisPhase.Hidden -> {
            if (input != null) {
                BannerHysteresisPhase.PendingShow(
                    category = input.category,
                    authReason = input.authReason,
                    atMs = now,
                )
            } else {
                BannerHysteresisPhase.Hidden
            }
        }

        is BannerHysteresisPhase.PendingShow -> {
            if (input == null) {
                // Recovered within grace — never shown
                BannerHysteresisPhase.Hidden
            } else if (now >= p.atMs + config.showGraceMs) {
                // Grace elapsed → promote to Showing
                BannerHysteresisPhase.Showing(
                    category = input.category,
                    authReason = input.authReason,
                    sinceMs = now,
                )
            } else {
                // Still within grace period — stay PendingShow, update payload
                BannerHysteresisPhase.PendingShow(
                    category = input.category,
                    authReason = input.authReason,
                    atMs = p.atMs,
                )
            }
        }

        is BannerHysteresisPhase.Showing -> {
            if (input != null) {
                // Stay showing; update payload if changed (REST_OUTAGE↔AUTH_FAILURE)
                BannerHysteresisPhase.Showing(
                    category = input.category,
                    authReason = input.authReason,
                    sinceMs = p.sinceMs,
                )
            } else if (now >= p.sinceMs + config.minDisplayMs) {
                // Min-display met → start hide delay
                BannerHysteresisPhase.PendingHide(
                    category = p.category,
                    authReason = p.authReason,
                    atMs = now,
                    sinceMs = p.sinceMs,
                )
            } else {
                // Min-display NOT met — keep showing
                BannerHysteresisPhase.Showing(
                    category = p.category,
                    authReason = p.authReason,
                    sinceMs = p.sinceMs,
                )
            }
        }

        is BannerHysteresisPhase.PendingHide -> {
            if (input != null) {
                // Recovered during hide delay — re-show (anti flap), preserve original sinceMs
                BannerHysteresisPhase.Showing(
                    category = input.category,
                    authReason = input.authReason,
                    sinceMs = p.sinceMs,
                )
            } else if (now >= p.atMs + config.recoverHideDelayMs) {
                // Hide delay elapsed → fully hidden
                BannerHysteresisPhase.Hidden
            } else {
                // Still within hide delay — wait
                BannerHysteresisPhase.PendingHide(
                    category = p.category,
                    authReason = p.authReason,
                    atMs = p.atMs,
                    sinceMs = p.sinceMs,
                )
            }
        }
    }

    // Derive UI-facing visibility from the phase
    val nextVisibility: BannerVisibility = when (nextPhase) {
        is BannerHysteresisPhase.Hidden -> BannerVisibility.Hidden
        is BannerHysteresisPhase.PendingShow -> BannerVisibility.Hidden
        is BannerHysteresisPhase.Showing ->
            BannerVisibility.Showing(
                category = nextPhase.category,
                authReason = nextPhase.authReason,
                sinceMs = nextPhase.sinceMs,
            )
        is BannerHysteresisPhase.PendingHide ->
            // Still visible during hide delay (anti-flap)
            BannerVisibility.Showing(
                category = nextPhase.category,
                authReason = nextPhase.authReason,
                sinceMs = nextPhase.sinceMs,
            )
    }

    return BannerHysteresisState(visibility = nextVisibility, phase = nextPhase)
}

/**
 * §C1: Computes the next wall-clock deadline at which the hysteresis state
 * machine needs to re-evaluate. Returns null when no pending deadline exists
 * (Showing and Hidden have no fixed timeouts — they wait for external events).
 *
 * Used by [BannerHysteresisOwner] to schedule a focused delay at the exact
 * deadline, replacing the old 30s coarse ticker for hysteresis timing.
 */
internal fun computeHysteresisDeadlineMs(
    state: BannerHysteresisState,
    now: Long,
    config: BannerHysteresisConfig = BannerHysteresisConfig(),
): Long? {
    return when (val p = state.phase) {
        is BannerHysteresisPhase.PendingShow -> p.atMs + config.showGraceMs
        is BannerHysteresisPhase.PendingHide -> p.atMs + config.recoverHideDelayMs
        is BannerHysteresisPhase.Showing -> null
        is BannerHysteresisPhase.Hidden -> null
    }
}

/**
 * §sse-feedback-ux (§1.1): should the in-chat banner EVER be considered for
 * this feedback? True for terminal disconnect / SSE stall / user-disabled —
 * the transient / healthy / decision-pending variants stay silent so the
 * banner never cries wolf on a brief network blip or while the TOFU dialog
 * is handling a cert decision.
 *
 * NOTE: this only determines WHETHER the feedback is "banner-worthy" at all.
 * The actual VISIBILITY (debounce / grace / min-display / recover-hide) is
 * governed by [BannerHysteresisState] / [bannerHysteresisReducer]. The
 * auth-vs-outage distinction (REST_OUTAGE vs AUTH_FAILURE) is decided by
 * [bannerCategory], NOT by showBanner.
 *
 * §1.1 漏报 fix: [WaitingForStream] now returns true — previously silent.
 */
internal val SseConnectionFeedback.showBanner: Boolean
    get() = this is SseConnectionFeedback.Disconnected ||
        this is SseConnectionFeedback.Disabled ||
        this is SseConnectionFeedback.WaitingForStream

/**
 * §sse-feedback-ux (§1.1): what semantic category does this feedback represent?
 * Pure function of [SseConnectionFeedback] + the mTLS-auth signal. Returns
 * null when the feedback is NOT banner-worthy (Live / Connecting / Reconnecting
 * / AwaitingTofuTrust / Idle) — the caller interprets null as "no banner".
 *
 * Priority rule: AUTH_FAILURE wins over REST_OUTAGE when
 * [mtlsDegradedError] is non-null (more actionable root cause).
 *
 * §1.1 漏报 fix: [WaitingForStream] → [BannerCategory.SSE_STALLED].
 */
internal fun SseConnectionFeedback.bannerCategory(
    mtlsDegradedError: String?,
): BannerCategory? = when (this) {
    is SseConnectionFeedback.Disabled -> BannerCategory.USER_DISABLED
    is SseConnectionFeedback.WaitingForStream -> BannerCategory.SSE_STALLED
    is SseConnectionFeedback.Disconnected ->
        if (mtlsDegradedError != null) BannerCategory.AUTH_FAILURE else BannerCategory.REST_OUTAGE
    // Live / Connecting / Reconnecting / ReconnectingAttempt / AwaitingTofuTrust / Idle → no banner
    is SseConnectionFeedback.Live -> null
    is SseConnectionFeedback.Connecting -> null
    is SseConnectionFeedback.Reconnecting -> null
    is SseConnectionFeedback.AwaitingTofuTrust -> null
    is SseConnectionFeedback.Idle -> null
}

/**
 * §sse-feedback-ux: elapsed ms since the terminal disconnect was stamped, or
 * null when the feed is not in a banner-worthy disconnect. Computed from the
 * derivation clock ([Disconnected.now]) captured at emit time so the label is
 * consistent with the value the banner received (not a fresh re-read that
 * could drift past the last tick). Coerced to ≥0 so a clock skew can never
 * render a negative duration.
 */
internal fun SseConnectionFeedback.disconnectDurationMs(): Long? =
    (this as? SseConnectionFeedback.Disconnected)?.let { (it.now - it.sinceMs).coerceAtLeast(0L) }

/**
 * Returns `true` iff the clear+reload actually ran; `false` iff the isLoading
 * guard suppressed it (when [explicit], an Info feedback was emitted on suppress).
 */
internal fun AppCore.performGlobalColdStartRefresh(
    currentId: String,
    forceInitialWindow: Boolean = false,
    explicit: Boolean = false,
): Boolean {
    // §history-load-fix: guard against BOTH load flags — a user loadMore in
    // flight (isLoadingMoreMessages) must also block a cold-start reset (which
    // would wipe the list mid-prepend). Previously only isLoadingMessages was
    // checked, so a cold-start refresh could clobber an in-flight loadMore.
    //
    // §force-refresh-guard (SSE-disconnect REST fallback): for an EXPLICIT
    // user force-refresh we must NOT silently swallow the tap when a load is
    // already in flight. ColdStartChatReset does NOT clear isLoadingMessages
    // (see AppAction.ColdStartChatReset docblock), so bypassing this guard
    // here would wipe the chat slice while launchLoadMessages' OWN coalescing
    // guard (MessageActions §R-17 batch2) skips the refill → an empty window
    // with no fresh fetch to repopulate it (worse than status quo). Instead
    // surface a feedback event so the user knows the refresh is queued; the
    // in-flight load (or a repeated tap once it settles) delivers fresh data.
    // The AUTOMATIC cold-start path keeps the silent no-op (explicit=false).
    // Returns false so callers (performForceRefresh / refreshCurrentSession)
    // can skip their cascading side-steps when nothing was actually refreshed.
    if (store.chatFlow.value.isLoadingMessages || store.chatFlow.value.isLoadingMoreMessages) {
        if (explicit) {
            effectBus.tryEmitUiEvent(UiEvent.Info(R.string.info_refresh_in_progress))
        }
        return false
    }
    // Capture the route incarnation before the reset clears LoadedContent. A
    // route-owned force refresh must commit its replacement through the same
    // token; legacy callers intentionally retain the 0L flat-only scope.
    val expectedRouteInstance = store.slices.routeInstanceFor(currentId)
    sessionSwitcher.clearSessionWindowCache()
    // refreshNonce is NOT a §2.3 target field — leave as a separate writeChat
    // (minimal blast radius; not folded into ColdStartChatReset).
    writeChat { it.copy(refreshNonce = it.refreshNonce + 1) }
    // T1b writeChat-bypass: 8-field cold-start chat reset via dispatch.
    store.dispatch(AppAction.ColdStartChatReset)
    // §sse-rest-fallback: forceInitialWindow=true (explicit force-refresh /
    // staleNotice recovery) routes the slim fetch UNANCHORED
    // (getMessagesPagedUnanchored → since=0L), bypassing a stale slim watermark
    // that — after an SSE outage — would make the anchored /since return an
    // empty delta and leave the just-cleared window EMPTY. The fetch then bumps
    // the bookmark via bumpSlimBookmarkFromItems so later /since calls anchor on
    // the fresh high-water mark.
    //
    // §sse-auto-unanchor (TODO 3): the AUTOMATIC cold-start (defaults, e.g. the
    // GlobalColdStartRefresh effect emitted by ForegroundCatchUpController on a
    // long foreground absence) ALSO upgrades to UNANCHORED when SSE has been
    // Disconnected past [SSE_DISCONNECT_UNANCHORED_THRESHOLD_MS] — a real
    // outage self-heals without a manual refresh. Gated so a brief blip (or a
    // healthy SSE) keeps the cheap anchored catch-up / three-way merge: no
    // white flash, no lost loadMore history on every cold-start.
    val conn = store.connectionFlow.value
    val autoUnanchor = !forceInitialWindow &&
        shouldAutoUnanchorOnColdStart(
            conn.connectionPhase,
            conn.disconnectedSince,
            System.currentTimeMillis(),
        )
    loadMessagesForEffect(
        currentId,
        resetLimit = true,
        forceInitialWindow = forceInitialWindow || autoUnanchor,
        expectedRouteInstance = expectedRouteInstance,
    )
    return true
}

/**
 * §sse-rest-fallback: the user-triggered FORCE refresh (ChatTopBar "Force
 * refresh"). The SSE feed is a push OPTIMIZATION; when it is down the app
 * must still pull the latest content over REST. This is the explicit,
 * full-reset recovery path — strictly stronger than the automatic cold-start
 * ([performGlobalColdStartRefresh] with defaults):
 *
 *  ① clearSessionWindowCache — drop the in-memory LRU window (SessionSwitcher).
 *  ② ColdStartChatReset       — wipe the current session's messages/parts/
 *                               cursor/streaming (8-field reset via dispatch).
 *  ③ loadMessagesForEffect(   — full UNANCHORED re-fetch (forceInitialWindow=
 *      resetLimit=true,         true → getMessagesPagedUnanchored → since=0L),
 *      forceInitialWindow=true) bypassing any stale slim watermark; the fetch
 *                               bumps the bookmark so later /since is correct.
 *  ④ testConnection(force=true)— re-probe the connection (the user asked for a
 *                               refresh; SSE may be stale — verify transport).
 *  ⑤ LoadSessions             — resync the session list metadata (titles /
 *                               updated_at) over REST.
 *
 * Steps ①②③ reuse [performGlobalColdStartRefresh] (explicit=true so the
 * isLoading guard surfaces feedback instead of silently swallowing the tap;
 * forceInitialWindow=true so the clear is followed by a real re-fetch).
 *
 * # Suppressed-refresh behavior (TODO 4)
 *
 * When the isLoading guard suppresses ①②③ (a load is in flight + an Info
 * feedback was emitted), steps ④⑤ are SKIPPED — running a connection probe +
 * session-list reload for a refresh that did not happen would be a misleading
 * partial action. The user retries once the in-flight load settles. This is
 * gated on [performGlobalColdStartRefresh]'s Boolean return.
 *
 * # Cold-start layering (review C3)
 *
 * This clear+unanchored reset is EXPENSIVE (white flash, lost loadMore
 * history, a full REST round-trip) and is reserved for the EXPLICIT
 * force-refresh. The AUTOMATIC cold-start keeps the cheaper anchored catch-up
 * / three-way merge — it does NOT clear+unanchored on every session open. The
 * AUTOMATIC path DOES self-heal to unanchored on a SUSTAINED SSE outage (see
 * [shouldAutoUnanchorOnColdStart] + [performGlobalColdStartRefresh]); the
 * per-session-open VerifyAndHydrate path keeps its existing forceInitialWindow
 * behavior unchanged.
 */
internal fun AppCore.performForceRefresh(sessionId: String) {
    // ①②③ reuse the cold-start clear+reload primitive, but force an UNANCHORED
    // re-fetch (bypasses a stale slim watermark) and surface feedback if a
    // load is already in flight instead of silently swallowing the tap.
    val refreshed = performGlobalColdStartRefresh(
        currentId = sessionId,
        forceInitialWindow = true,
        explicit = true,
    )
    if (!refreshed) {
        // §force-refresh-guard (TODO 4): ①②③ were suppressed (a load is in
        // flight; an Info feedback was emitted). Do NOT cascade ④ testConnection
        // / ⑤ LoadSessions — those would be a misleading partial action (probe
        // + session-list reload with no actual message refresh). The user
        // retries once the in-flight load settles.
        return
    }
    // ④ re-probe the connection — the user explicitly asked for a refresh, so
    // verify transport health even if SSE looks connected (it may be stale).
    connectionCoordinator.testConnection(force = true)
    // ⑤ resync the session list metadata (titles / updated_at) over REST.
    effectBus.tryEmitEffect(ControllerEffect.LoadSessions)
}

internal fun AppCore.catchUpAfterDisconnectOrForeground(sessionId: String) {
    // capture fp once (glm-3 🟡#1 single-read) for the cache hook + the G6
    // current-workdir input.
    val fp = hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id }
    // G6 inputs: SSE coverage baseline + the live SSE workdir (drives shouldProbeCatchUp).
    val sseSnap = sessionSyncCoordinator.sseSyncStateSnapshot()
    // §P0-3 (SSE-liveness wiring): gate the coverage short-circuit on REAL SSE
    // transport liveness ([StoreState.isSseConnected]), NOT REST-health
    // [ConnectionState.isConnected]. The two are a SEPARATE axis:
    //  - `isConnected` reflects HEALTH-SETTLE (ConnectionHealthProbe writes the
    //    committed REST baseline) — it can read true during a transient SSE
    //    outage (inter-retry gap) because no health failure has occurred yet.
    //  - `isSseConnected` reflects TRANSPORT delivery (a frame reached the
    //    owner) — it goes false the moment the live feed tears down / gaps.
    // Gating on `isConnected` made the coverage short-circuit believe the SSE
    // feed was covering this workdir while it was actually NOT delivering → the
    // REST probe was skipped → updates that arrived during the outage were
    // missed. The SSE feed is attached to the current workdir when transport-
    // live; null when down (no live frame → never SSE-covered).
    val sseWorkdir = if (store.sseConnectedFlow.value) settingsManager.currentWorkdir else null
    launchCatchUp(
        scope = appScope,
        repository = repository,
        slices = store.slices,
        sessionId = sessionId,
        settingsManager = settingsManager,
        onCacheWindow = makeCacheHook(fp),
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
        expectedRouteInstance = store.slices.routeInstanceFor(sessionId),
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
 * §token-stream-open-gate: the shared predicate for whether to open the
 * per-session token stream when loading messages. Returns `true` IFF:
 *  - the sidecar advertises `features.tokenStream == true`
 *    ([tokenStreamEnabled], populated from
 *    [cn.vectory.ocdroid.data.repository.ServerCompatProfile.slimapiTokenStreamEnabled]
 *    by every successful health probe), AND
 *  - the loaded session IS the foreground session ([currentSessionId] is the
 *    ChatState's `currentSessionId` — the tab the user is viewing).
 *
 * This is the B-1 "open session mid-generation → live tokens" gate. Extracted
 * from inline duplication at [AppCore.loadMessagesForEffect] (the main busy-
 * open path) + [cn.vectory.ocdroid.ui.ChatViewModel.loadMessages] (the side-
 * door path). Both sites redirect here so the truth table is testable in one
 * place ([cn.vectory.ocdroid.ui.TokenStreamWiringTest]).
 *
 * NOT used for [cn.vectory.ocdroid.ui.controller.ConnectionCoordinator]'s
 * `resetDegraded` gate (that gates a DIFFERENT action — re-arming the
 * capability-degrade state — and does not check the foreground-session
 * condition).
 */
internal fun shouldOpenTokenStream(
    tokenStreamEnabled: Boolean,
    currentSessionId: String?,
    targetSessionId: String,
): Boolean = tokenStreamEnabled && currentSessionId == targetSessionId

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
internal fun AppCore.loadMessagesForEffect(sessionId: String, resetLimit: Boolean, forceInitialWindow: Boolean = false, expectedRouteInstance: Long = 0L) {
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
        // §empty-window-fix: forwarded ONLY by the VerifyAndHydrate cold-load
        // branch (resident-but-empty window OR genuine cache miss) so the slim
        // fetch bypasses a stale watermark. All other callers keep the default
        // (false → anchored /since).
        forceInitialWindow = forceInitialWindow,
        // §chat-list-detail §7.2 B0.5-rework: the route-instance token threaded
        // from navigateToChat → openForRoute → VerifyAndHydrate → HERE →
        // launchLoadMessages. 0L = legacy (MessagesMerged); > 0L = route-aware
        // (ChatContentLoaded with CAS). Guards the ENTIRE completion txn.
        expectedRouteInstance = expectedRouteInstance,
        // §11.1 fix-9 P0-7: SSE liveness predicate — when SSE transport is
        // NOT delivering (SseDisabled / terminal exhaustion), a first-fetch
        // failure (non-stale IOException) retries once via unanchored REST
        // so the user doesn't stare at a silent blank under SSE-off /
        // degraded transport. Reads the live transport axis
        // (StoreState.isSseConnected) via SliceFlows.
        isSseLive = { store.slices.sseConnected },
    )
    // §Stage-D2 §5.8 B-1 busy-open: the SHARED load entry for all production
    // message loads (session switch via SessionSwitcher/VerifyAndHydrate,
    // cold-start, force-refresh via ChatScaffold LoadMessages effect, SSC
    // ReloadSession, etc. — all route through ControllerEffect.LoadMessages →
    // dispatchSessionEffect → HERE). The gate is [shouldOpenTokenStream] —
    // the shared predicate (also used by ChatViewModel's side-door). The
    // coordinator's open() is debounced + max-1 (opening this session closes
    // any prior); if the session is NOT actually streaming, the stream sits
    // idle and is closed on background / session-switch. Capability gate:
    // slimapiTokenStreamEnabled == false → zero-regression (existing behavior).
    if (shouldOpenTokenStream(
            serverCompatProfile.tokenStreamEnabled,
            store.slices.chat.value.currentSessionId,
            sessionId,
        )
    ) {
        tokenStreamCoordinator.open(sessionId, settingsManager.currentWorkdir, source = "effect-load")
    }
}

/** §R-17 batch3d: dispatch helper for the LoadSessions / RefreshSessions effects. */
internal fun AppCore.loadSessionsForEffect() {
    launchLoadSessions(
        scope = appScope,
        repository = repository,
        slices = store.slices,
        settingsManager = settingsManager,
        onSelectSession = { selectSessionForEffect(it) },
        onLoadSessionStatus = { launchLoadSessionStatus(appScope, repository, store.slices, trigger = SessionStatusLoadTrigger.COLD_START) },
        onLoadMessages = { sessionId ->
            loadMessagesForEffect(
                sessionId = sessionId,
                resetLimit = true,
                expectedRouteInstance = store.slices.routeInstanceFor(sessionId),
            )
        },
        emit = EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
        // remove-message-persistence Task 6: the prior
        // `cacheRepository = cacheRepository` argument (R-20 Phase 1 C7
        // currentSessionId fingerprint self-check) was deleted together
        // with the CacheRepository surface.
        expectedServerGroupFp = currentServerGroupFp(),
        currentServerGroupFp = currentServerGroupFp,
        // §grouping-rewrite Round-2 #5: the hostProfileStore arg that R-20
        // Phase 5 wired here (for cross-group merge of LAN + tunnel same-server
        // profiles) is removed — attemptCrossGroupMerge was deleted by item 1
        // of this rewrite.
        // WT6 (archive-sync) + §B4: if the merged refresh discovers archived
        // sessions, dispatch a SINGLE atomic BulkSessionsRefreshed that writes
        // the merged list and (if current is archived) clears chat. No
        // open-tabs-list prune. Route pop when route id is archived is done
        // here after the dispatch.
        onArchivedSessionsDetected = { merged, hasMore, confirmedServerIds, sweepNow ->
            dispatchBulkArchivedSessions(merged, hasMore, confirmedServerIds, sweepNow)
        },
    )
}

/**
 * WT6 (archive-sync) + §B4: bulk-refresh archive path. Dispatches a SINGLE
 * [AppAction.BulkSessionsRefreshed] that atomically:
 *  1. Writes the merged session list.
 *  2. IFF the current session is among the archived, clears chat
 *     ([applyArchivedChatClear]) + scroll-state / unread / questions subtree.
 *
 * §B4: no open-tabs-list prune. After dispatch, if the active chat/{id}
 * route (or residual current) was archived → force popToSessions +
 * CloseDetail. Side effects OUTSIDE the dispatch: EvictSession for the
 * archived current's cache window. [persistSessionCache] stays in
 * [launchLoadSessions].
 */
private fun AppCore.dispatchBulkArchivedSessions(
    mergedSessions: List<Session>,
    hasMoreSessions: Boolean,
    confirmedServerIds: Set<String>,
    sweepNow: Long,
) {
    val previousCurrentId = store.chatFlow.value.currentSessionId
    val routeId = routeChatSessionId(store.navFlow.value.lastRoute)
    val archivedIds = mergedSessions
        .filter { it.isArchived }
        .map { it.id }
        .toSet()
    val currentWasArchived = previousCurrentId != null && previousCurrentId in archivedIds
    val routeWasArchived = routeId != null && routeId in archivedIds
    store.dispatch(
        AppAction.BulkSessionsRefreshed(
            sessions = mergedSessions,
            hasMoreSessions = hasMoreSessions,
            confirmedServerIds = confirmedServerIds,
            sweepNow = sweepNow,
        )
    )
    // §B4 / §10 REST refresh: if route id was archived → popToSessions.
    // (Reducer already cleared chat when current was archived; CloseDetail
    // advances the route-instance token so stale loads drop.)
    if (routeWasArchived || currentWasArchived) {
        store.dispatch(AppAction.CloseDetail)
        settingsManager.currentSessionId = null
        settingsManager.lastRoute = NavRoute.Sessions.route
        store.mutateNav {
            it.copy(
                lastRoute = NavRoute.Sessions.route,
                navEpoch = it.navEpoch + 1L,
            )
        }
    }
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
