package cn.vectory.ocdroid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.annotation.MainThread
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.PermissionResponse
import cn.vectory.ocdroid.util.DebugLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * R-17 batch3 → batch3d → §R18 Phase 3 Wave 3 (P2-6): Orchestrator-domain
 * ViewModel. After the Wave 3 split this VM owns ONLY:
 *
 *  - **Nav** ([setLastRoute] / [requestNavigate]) — the persisted top-level destination.
 *  - **File browser / file-to-show** ([showFileInFiles] / [clearFileToShow]
 *    / [browseFilesInWorkdir] / [closeFileBrowser]).
 *  - **Permission / Question responses** ([respondPermission] /
 *    [replyQuestion] / [rejectQuestion]).
 *  - **Genuinely cross-domain entry points** surfaced via
 *    [openSessionFromDeepLink] / [resetLocalDataAndResync] /
 *    [executeCommand] / [coldStartReconnect] / [configureServer] (these
 *    orchestrate across 3+ domains and live in [AppCore]).
 *
 * Settings writes (theme / markdown font sizes / UI scale) moved to
 * [SettingsViewModel]; traffic writes (refresh / reset counters) moved to
 * [ConnectionViewModel] (traffic is connectivity-shaped state). The read
 * accessors below ([settingsFlow], [trafficFlow], [hostFlow],
 * [connectionFlow], [fileFlow], [navFlow], [uiEvents]) stay — they are
 * zero-cost delegates to the same [SharedStateStore] flows the new owners
 * expose, retained so existing tests + the composables that legitimately
 * read multi-domain state off this VM (e.g. ChatScreen's settingsFlow
 * subscription for the agent list) keep resolving.
 *
 * §R18 Phase 3 Wave 3 (drift #6 / P1-7): [respondPermission] /
 * [replyQuestion] / [rejectQuestion] now launch on [viewModelScope] instead
 * of [AppCore.appScope]. These are user-interaction-triggered ephemeral
 * operations; binding them to the VM scope lets them cancel cleanly on
 * navigation-away / VM clear, and the closure captures repository / slice
 * transforms (never VM `::ref`s) so the P1-7 self-capture hazard does not
 * apply.
 */
@HiltViewModel
class OrchestratorViewModel @Inject constructor(
    internal val core: AppCore,
) : ViewModel() {

    val navFlow get() = core.navFlow
    val fileFlow get() = core.fileFlow
    val settingsFlow get() = core.settingsFlow
    val trafficFlow get() = core.trafficFlow
    val uiEvents get() = core.uiEvents
    val hostFlow get() = core.hostFlow
    val connectionFlow get() = core.connectionFlow

    private val _reselectFlow = MutableSharedFlow<NavRoute>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Screens subscribe to reselectFlow filtered to their route to run per-tab
     * resets; emitted alongside popBackStack on same-tab tap.
     */
    val reselectFlow: SharedFlow<NavRoute> = _reselectFlow.asSharedFlow()

    /**
     * §chat-list-detail §7.2 B0.5: the chat-route incarnation counter. The
     * chat/{id} render composable collects this to apply the P6 freshness
     * CAS at render time (`content.routeInstance == chatRouteInstance`).
     */
    val chatRouteInstanceFlow get() = core.store.chatRouteInstanceFlow

    // ── Nav ─────────────────────────────────────────────────────────────────

    /**
     * §unified-nav (A1): the PASSIVE mirror setter. Writes [NavState.lastRoute]
     * ONLY — NEVER touches [NavState.navEpoch]. This is called SOLELY by the
     * AppShell destination observer (A3) to reconcile the mirror when the
     * NavController's committed back-stack entry diverges from the mirror (e.g.
     * system BACK popping chat→sessions while the mirror still reads "chat/…"
     * — item 6). Because it does NOT bump [navEpoch], it cannot feed the nav
     * synchronizer back into a loop (the synchronizer observes navEpoch; a
     * passive mirror write leaves it unchanged → the synchronizer's
     * `alreadyThere` guard no-ops).
     *
     * The short-circuit guard (`state.lastRoute == route.route → return`) is
     * retained: a redundant passive write (the mirror already matches) is a
     * no-op, avoiding a pointless CAS + StateFlow emission.
     *
     * F2: the `settingsManager.lastRoute` persistence write was a redundant
     * mirror of the in-memory [NavState.lastRoute]; deleted (F2).
     */
    fun setLastRoute(route: NavRoute) {
        val state = core.store.navFlow.value
        // Authority is lastRoute only; §unified-nav: passive mirror — NO navEpoch bump here.
        if (state.lastRoute == route.route) return
        core.store.mutateNav { it.copy(lastRoute = route.route) }
    }

    /**
     * §unified-nav (A1): the EXPLICIT nav-command setter. Every call is an
     * explicit navigation intent from a user/external action (new-draft → Chat,
     * server popup → Settings, Chat → Settings, Files switch → Chat,
     * backToHome → Sessions, etc.). It ALWAYS bumps [NavState.navEpoch] (+1L)
     * so the nav synchronizer re-fires EVEN when [lastRoute] is structurally
     * identical to the current value (item 6: new session → BACK → "+" again →
     * mirror already "chat" but the NavController is on Sessions → without the
     * epoch bump the synchronizer's `alreadyThere` guard no-ops and nothing
     * happens; item 8: server popup → Settings when the mirror already reads
     * "settings" from a stale state). There is NO short-circuit: every call is
     * an explicit intent and always re-fires the synchronizer.
     *
     * Main-thread contract: nav commands originate from Compose callbacks
     * (Dispatchers.Main.immediate) or Activity onNewIntent (Main). The
     * mutateNav CAS is main-thread safe + serial. F2: the redundant
     * settingsManager.lastRoute persistence write was deleted.
     */
    @MainThread
    fun requestNavigate(route: NavRoute) {
        core.store.mutateNav {
            it.copy(
                lastRoute = route.route,
                // Always bump → synchronizer re-fires unconditionally.
                navEpoch = it.navEpoch + 1L,
            )
        }
    }

    /**
     * §unified-nav (A5.2): PASSIVE setter for the runtime-only resolved actual
     * destination. Writes [NavState.activeDestination] + bumps
     * [NavState.activeDestinationEpoch]. Does NOT touch [lastRoute] /
     * [navEpoch], so it NEVER fires the nav synchronizer (the synchronizer
     * observes lastRoute + navEpoch only). Called by the AppShell destination
     * listener (A3) on every committed back-stack entry change.
     *
     * The [destination] string is the resolved literal (e.g. `"chat/ses_X"`,
     * `"sessions"`, `"settings"`, `"files/…"`). The epoch bump is monotonic so
     * a captured [DraftRouteOrigin] can detect that the user navigated away
     * even when the new destination string happens to match the captured one.
     */
    @MainThread
    internal fun setActiveDestination(destination: String) {
        core.store.mutateNav {
            it.copy(
                activeDestination = destination,
                activeDestinationEpoch = it.activeDestinationEpoch + 1L,
            )
        }
    }

    /**
     * §unified-nav (A1 optional cleanup): force-navigate to Sessions is now
     * delegated to [requestNavigate] (Sessions) so there is ONE explicit nav-
     * command API. Behavior is identical to the prior inline impl: writes
     * [NavState.lastRoute] = Sessions + bumps [NavState.navEpoch]
     * (requestNavigate always bumps). F2: the redundant
     * settingsManager.lastRoute persistence write was deleted (see
     * [setLastRoute] / [requestNavigate]). Used by the MainActivity deep-link
     * fail-safe (malformed session id → Sessions) where [setLastRoute] would
     * short-circuit when the mirror already reads "sessions" (which it does
     * on Files/Git — those destinations do not update navState).
     */
    internal fun forceNavigateToSessions() = requestNavigate(NavRoute.Sessions)

    /**
     * §chat-list-detail §8.2: explicit chat/{id} navigation API — the
     * eventual replacement for `setLastRoute(NavRoute.Chat)`. Mints a
     * non-reusable §7.2 route-instance token (the freshness CAS anchor on
     * [StoreState.chatRouteInstance]) and stages the parameterized route
     * transition on [NavState] atomically (one [mutateState] CAS — the
     * route write + token bump commit as a single aggregate state, so no
     * collector ever observes a torn "route flipped but token stale"
     * intermediate that a stale content load could race).
     *
     * §8.2 semantics:
     *  - [sessionId] non-null → route = `"chat/$id"` (the parameterized
     *    conversation destination D1). The id is a branded `ses_` string
     *    (URL-safe — no encoding needed; [parseRoute] accepts both raw and
     *    decoded forms regardless).
     *  - [sessionId] null → route = `"chat/new"` (the explicit new-
     *    conversation draft destination D4). B0 does NOT carry a workdir
     *    query here; the §8.2 `navigateToNewConversation(workdir)` variant
     *    lands in a later batch alongside the NewConversation route wiring.
     *
     * B0.5→B1 evolution: now the UNIFIED nav API for the Sessions→tap→chat
     * entry. The body:
     *  1. Mints the route-instance token INSIDE the mutateState transform
     *     (B0's atomic in-transform mint — two concurrent calls can never
     *     collide) AND writes navState.lastRoute = "chat/$sid" in the SAME
     *     atomic CAS (the route + token commit as a single aggregate state).
     *  2. Delegates session housekeeping to [SessionSwitcher.openForRoute]
     *     (bypasses the same-session no-op guard — route navigation ALWAYS
     *     re-enters; the token is the freshness contract). openForRoute does
     *     the FULL housekeeping (draft save/restore, SessionSelected dispatch,
     *     VerifyAndHydrate emission with the token, unread update,
     *     child/status effects).
     *
     * §12 B1: the B0.5 `chatNavEvents` SharedFlow workaround is REMOVED.
     * navState.lastRoute is now the sole nav mechanism (AppShell's unified
     * synchronizer observes it and navigates the NavController directly,
     * handling parameterized routes correctly — the old mirror's mis-routing
     * via `fromRouteKey` is gone). The persisted settingsManager.lastRoute is
     * safe to write: cold start does NOT restore it (NavState defaults to
     * Sessions.route per §5 P3 — see [NavState] kdoc).
     *
     * The token (step 1) is read back synchronously after mutateState and
     * passed into openForRoute → VerifyAndHydrate → launchLoadMessages →
     * ChatContentLoaded, guarding the ENTIRE completion transaction (§7.2).
     *
     * [sessionId] == null (chat/new) is deferred to a later batch. Other
     * entries use [requestNavigate] (the explicit nav-command setter): Files
     * / new-draft → Chat / server popup → Settings. MainActivity deeplinks
     * use [navigateToChat] (session-scoped). The passive [setLastRoute] is now
     * used ONLY by the AppShell destination observer (A3 mirror reconciliation).
     *
     * §unified-nav (A4): the token is now minted INSIDE the mutateStateAndGet
     * transform and read from the RETURNED committed snapshot (closes the
     * token-capture race where two concurrent calls read the same post-image
     * value). See the body comment for details.
     */
    @MainThread
    fun navigateToChat(sessionId: String?) {
        val sid = sessionId ?: return // chat/new path deferred.
        val route = "chat/$sid"
        // F2: the settingsManager.lastRoute persistence write was a redundant
        // mirror (cold start ignores it per §5 P3); deleted in F2.
        // §unified-nav (A4 token-capture race): mint the token INSIDE the
        // mutateStateAndGet transform and read it back from the RETURNED
        // committed snapshot. The prior code did `mutateState { ... mint T ... }`
        // then a SEPARATE `stateFlow.value.chatRouteInstance` re-read — two
        // concurrent navigateToChat calls could both mint T from the same
        // pre-image and then both read the SAME post-image value (the second
        // CAS won, so both saw its token), producing a duplicated token. Reading
        // the token from the committed snapshot returned by updateAndGet
        // guarantees each call sees ITS OWN committed token (the CAS retry loop
        // serializes concurrent writers; each returned snapshot reflects that
        // writer's mint). Atomic: route + token + content-clear in ONE CAS.
        //
        // §navEpoch: bump alongside lastRoute so re-selecting a previously-
        // visited session (after backToHome) re-fires the synchronizer even
        // when lastRoute is structurally unchanged.
        //
        // Main-thread contract: navigateToChat originates from Compose
        // callbacks / Activity onNewIntent (Dispatchers.Main.immediate). The
        // mutateStateAndGet CAS + the subsequent openForRoute are both main-
        // thread serial; no concurrent writer can interleave.
        val committed = core.store.mutateStateAndGet {
            val next = it.chatRouteInstance + 1L
            it.copy(
                chatRouteInstance = next,
                nav = it.nav.copy(
                    lastRoute = route,
                    navEpoch = it.nav.navEpoch + 1L,
                ),
                chat = it.chat.copy(content = null),
            )
        }
        // 2. Route-aware open: bypasses same-session guard, does full session
        //    housekeeping, emits VerifyAndHydrate(expectedRouteInstance=T).
        val mintedToken = committed.chatRouteInstance
        core.sessionSwitcher.openForRoute(sid, mintedToken)
    }

    /**
     * §B2 rev-gpt #2: invalidate the active chat/{sessionId} detail route on
     * leave-to-home. Dispatches [AppAction.CloseDetail], which advances
     * [StoreState.chatRouteInstance] (+1L, never reuses a token) and clears
     * the route-owned [LoadedContent] + flat payload via
     * [reduceCloseDetail]/[clearLoadedChatPayload]. A stale route-aware
     * completion (ChatContentLoaded / PartDeltaReceived / MessagesPrepended)
     * carrying the prior token is then rejected by the existing §7.2
     * freshness CAS in [reduceChatContentLoaded] / [acceptsRouteUpdate].
     *
     * Caller gate: AppShell.backToHome invokes this ONLY when the current
     * destination is the parameterized `chat/{sessionId}` route — the legacy
     * bare-chat path (`NavRoute.Chat`) is untouched so its flat messages
     * survive a leave-and-return. Idempotent and harmless when no route
     * content is active (clears empty payload, advances the monotonic token).
     */
    fun closeDetail() {
        core.store.dispatch(AppAction.CloseDetail)
    }

    // §unified-nav (2026-07-26): returnToExistingChat was merged into
    // navigateToChat. Both had identical VM-side logic (mint token +
    // lastRoute + navEpoch++ + clear content + openForRoute). The push-
    // vs-pop decision is made by AppShell's synchronizer (popRestore
    // check: previousBackStackEntry.sessionId == targetSid), not the VM.
    // All former returnToExistingChat callers now use navigateToChat.

    /** Emits a same-tab selection without mutating persisted navigation state. */
    fun emitReselect(route: NavRoute) {
        _reselectFlow.tryEmit(route)
    }

    // ── Permission / Question responses (orchestrator-domain) ───────────────

    /**
     * F4a: fork collapsed — both modes (slim/legacy) hit the same
     * [core.repository.respondPermission] endpoint. [routeToken] param KEPT
     * for call-site stability (ChatScaffold.kt passes `p.routeToken`); it is
     * a client-side provenance signal only — upstream spec §7:231 deleted it
     * from the wire.
     */
    fun respondPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        /** F4a: client-side provenance signal only; upstream spec §7:231
         *  deleted it from the wire; both modes hit the same endpoint. */
        routeToken: String? = null,
    ) {
        // §R18 Phase 3 Wave 3 (drift #6 / P1-7): user-triggered ephemeral
        // permission response → viewModelScope. Closure captures the repo
        // call + a slice transform (never a VM `::ref`), so viewModelScope
        // cancels cleanly on navigation-away.
        viewModelScope.launch {
            val result = core.repository.respondPermission(sessionId, permissionId, response)
            result
                .onSuccess {
                    core.writeSessionList { it.copy(pendingPermissions = it.pendingPermissions.filter { p -> p.id != permissionId }) }
                }
                .onFailure { error ->
                    core.effectBus.tryEmitUiEvent(UiEvent.Error(R.string.error_respond_permission_failed, listOf(errorMessageOrFallback(error, "unknown error"))))
                }
        }
    }

    /**
     * F4a: fork shrinks to directory resolution — slim reads the directory
     * from the pending entry; legacy calls [core.resolveQuestionDirectory].
     * Both modes hit the same [core.repository.replyQuestion] endpoint.
     * [routeToken] param KEPT for call-site stability.
     */
    fun replyQuestion(
        requestId: String,
        answers: List<List<String>>,
        /** F4a: client-side provenance signal only; see [respondPermission]. */
        routeToken: String? = null,
        onError: () -> Unit = {},
    ) {
        // §R18 Phase 3 Wave 3 (drift #6 / P1-7): same viewModelScope rationale
        // as respondPermission.
        viewModelScope.launch {
            // Directory resolution: slim reads from the pending entry's directory;
            // legacy resolves via AppCore's question-directory resolve.
            val directory = if (routeToken != null) {
                core.sessionListFlow.value.pendingQuestions
                    .firstOrNull { it.id == requestId }?.directory
            } else {
                core.resolveQuestionDirectory(requestId)
            }
            DebugLog.d("Question", "replyQuestion req=$requestId dir=${directory ?: "null"}")
            val result = core.repository.replyQuestion(requestId, answers, directory)
            result
                .onSuccess {
                    DebugLog.d("Question", "replyQuestion OK req=$requestId")
                    core.writeSessionList { currentState ->
                        currentState.copy(pendingQuestions = currentState.pendingQuestions.filter { it.id != requestId })
                    }
                }
                .onFailure { error ->
                    DebugLog.w("Question", "replyQuestion FAIL req=$requestId err=${error.message}")
                    android.util.Log.w(TAG, "Failed to reply question: ${error.message}")
                    onError()
                }
        }
    }

    /**
     * F4a: same collapse as [replyQuestion] — directory resolution only;
     * single [core.repository.rejectQuestion] call.
     */
    fun rejectQuestion(
        requestId: String,
        /** F4a: client-side provenance signal only; see [respondPermission]. */
        routeToken: String? = null,
        onError: () -> Unit = {},
    ) {
        // §R18 Phase 3 Wave 3 (drift #6 / P1-7): same viewModelScope rationale.
        viewModelScope.launch {
            val directory = if (routeToken != null) {
                core.sessionListFlow.value.pendingQuestions
                    .firstOrNull { it.id == requestId }?.directory
            } else {
                core.resolveQuestionDirectory(requestId)
            }
            DebugLog.d("Question", "rejectQuestion req=$requestId dir=${directory ?: "null"}")
            val result = core.repository.rejectQuestion(requestId, directory)
            result
                .onSuccess {
                    DebugLog.d("Question", "rejectQuestion OK req=$requestId")
                    core.writeSessionList { currentState ->
                        currentState.copy(pendingQuestions = currentState.pendingQuestions.filter { it.id != requestId })
                    }
                }
                .onFailure { error ->
                    DebugLog.w("Question", "rejectQuestion FAIL req=$requestId err=${error.message}")
                    android.util.Log.w(TAG, "Failed to reject question: ${error.message}")
                    onError()
                }
        }
    }

    // ── File browser / file-to-show ─────────────────────────────────────────

    fun showFileInFiles(path: String, originRoute: String? = null) {
        core.writeFile { it.copy(filePathToShowInFiles = path, filePreviewOriginRoute = originRoute) }
    }

    fun clearFileToShow() {
        core.writeFile { it.copy(filePathToShowInFiles = null, filePreviewOriginRoute = null) }
    }

    fun browseFilesInWorkdir(workdir: String) {
        // §R18-P0-1 stop-bleed: do NOT mutate the global current directory here.
        // File-tree routing uses Skip-Dir + explicit @Header(directory) (see
        // OpenCodeApi.browseFileTree), so the browser does not need the global
        // dir. Only SSE/question/command routing depends on the global dir, and
        // those must keep pointing at the session dir — overriding it here
        // polluted their routing for the whole browse session. Phase 2 will
        // remove the global state entirely.
        core.writeFile {
            it.copy(
                filePathToShowInFiles = null,
                filePreviewOriginRoute = "sessions",
                fileBrowserOpen = true,
                fileBrowserWorkdir = workdir,
            )
        }
    }

    fun closeFileBrowser() {
        // §R18-P0-1: no global-dir restore needed (browseFilesInWorkdir no
        // longer saves/overrides it).
        core.writeFile {
            it.copy(fileBrowserOpen = false, fileBrowserWorkdir = null, filePathToShowInFiles = null, filePreviewOriginRoute = null)
        }
    }

    /** Clears any in-progress composer draft (composer-domain). Routed through
     *  [ComposerController] which owns the draftWorkdir guard. */
    fun clearDraftIfActive() {
        core.composerController.clearDraftIfActive()
    }

    // ── Cross-domain entry points (orchestrated by AppCore) ─────────────────

    fun openSessionFromDeepLink(sessionId: String) = core.openSessionFromDeepLink(sessionId)

    fun coldStartReconnect() = core.connectionCoordinator.coldStartReconnect()

    fun resetLocalDataAndResync() = core.resetLocalDataAndResync()

    fun executeCommand(command: String, arguments: String) = core.executeCommand(command, arguments)

    fun configureServer(url: String, username: String? = null, password: String? = null) =
        core.hostProfileController.configureServer(url, username, password)

    // ── browse state (private to this VM instance) ──────────────────────────
    // §R-17 batch3d: moved from AppCore private fields. §R18-P0-1 stop-bleed
    // removed the save/restore-global-directory mechanism that lived here
    // (browseSavedDirectory + browseActive) — the file browser no longer
    // touches the global current directory (see browseFilesInWorkdir). The
    // browse session's transient UI state lives entirely in fileFlow
    // (fileBrowserOpen / fileBrowserWorkdir).

    private companion object {
        private const val TAG = "OrchestratorViewModel"
    }
}
