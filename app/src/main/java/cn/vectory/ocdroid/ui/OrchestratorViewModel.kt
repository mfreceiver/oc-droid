package cn.vectory.ocdroid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 *  - **Nav** ([setLastNavPage]) — the persisted top-level destination.
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

    fun setLastNavPage(page: Int) {
        val clamped = page.coerceIn(0, 2)
        if (core.store.navFlow.value.lastNavPage == clamped) return
        core.settingsManager.lastNavPage = clamped
        val route = NavRoute.fromLegacyPage(clamped)
        core.settingsManager.lastRoute = route.route
        core.store.mutateNav { it.copy(lastRoute = route.route, lastNavPage = clamped) }
    }

    fun setLastRoute(route: NavRoute) {
        val state = core.store.navFlow.value
        if (state.lastRoute == route.route && state.lastNavPage == route.legacyPage) return
        core.settingsManager.lastRoute = route.route
        core.store.mutateNav { it.copy(lastRoute = route.route, lastNavPage = route.legacyPage) }
    }

    /**
     * §B3-C2: force-navigate to Sessions (home hub), bypassing [setLastRoute]'s
     * idempotent guard. [setLastRoute] short-circuits when [NavState.lastRoute]
     * already equals the target — but Files/Git entry does NOT update
     * [NavState.lastRoute] (it stays `"sessions"` per AppShell design), so a
     * no-id/malformed notification fallback calling [setLastRoute] would be a
     * no-op, leaving the user on Files/Git instead of navigating home.
     *
     * This method ALWAYS writes the Sessions route via [mutateNav] AND bumps
     * [NavState.navEpoch] (+1), guaranteeing the [DerivedStateFlow] emits a
     * new [NavState] instance even when every other field is structurally equal
     * to the current value. The AppShell synchronizer (`LaunchedEffect(navState.lastRoute, navState.navEpoch)`)
     * then re-fires and navigates the NavController to Sessions unconditionally.
     *
     * [setLastRoute] does NOT touch [navEpoch] — its short-circuit guard is
     * intentional for the Chat/Settings clean-exit path where the synchronizer
     * already fires because [lastRoute] actually changed. Only the force-path
     * bumps this counter.
     */
    internal fun forceNavigateToSessions() {
        core.settingsManager.lastRoute = NavRoute.Sessions.route
        core.store.mutateNav {
            it.copy(
                lastRoute = NavRoute.Sessions.route,
                lastNavPage = NavRoute.Sessions.legacyPage,
                navEpoch = it.navEpoch + 1L, // Always changes → StateFlow emits
            )
        }
    }

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
     * entries (Files / MainActivity / picker / drawer) STAY on [setLastRoute].
     */
    fun navigateToChat(sessionId: String?) {
        val sid = sessionId ?: return // chat/new path deferred.
        val route = "chat/$sid"
        // Persist the parameterized route (safe — cold start ignores it per §5 P3).
        core.settingsManager.lastRoute = route
        // 1. Atomic CAS: mint token + write navState.lastRoute + clear content.
        core.store.mutateState {
            val next = it.chatRouteInstance + 1L
            it.copy(
                chatRouteInstance = next,
                nav = it.nav.copy(lastRoute = route, lastNavPage = NavRoute.Chat.legacyPage),
                chat = it.chat.copy(content = null),
            )
        }
        // 2. Route-aware open: bypasses same-session guard, does full session
        //    housekeeping, emits VerifyAndHydrate(expectedRouteInstance=T).
        val mintedToken = core.store.stateFlow.value.chatRouteInstance
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

    /**
     * §chat-list-detail §11 / G6 (B5 BLOCK-fix): return to an EXISTING parent
     * chat route via pop-based restoration. Used by [SessionViewModel.returnToParent]
     * when the user navigates子→父.
     *
     * # Why a separate API (not navigateToChat)
     *
     * `navigateToChat(parentId)` would push a NEW `chat/{parentId}` entry on
     * top of the child, producing `[Sessions, parent(old handle), child,
     * parent(NEW handle)]`. The NEW parent entry's SavedStateHandle is fresh —
     * the openSubAgent checkpoint (stored on the OLD parent entry's handle) is
     * unreachable, so Restore never fires. The OLD parent entry is also
     * stranded on the back-stack, leaking.
     *
     * This method's VM-side effects MIRROR navigateToChat (mint a new
     * route-instance token, write navState.lastRoute, clear content, call
     * openForRoute → SessionSelected dispatch flips currentSessionId +
     * VerifyAndHydrate re-hydrates the parent's session window from the LRU
     * cache). The DIFFERENCE is at the NavController level: AppShell's
     * synchronizer detects that `previousBackStackEntry.sessionId == parentId`
     * and executes `popBackStack()` instead of `navigate()`. The pop
     * re-activates the EXISTING parent NavBackStackEntry, preserving its
     * SavedStateHandle (and the checkpoint) — so the parent's ChatScaffold
     * LaunchedEffect reads + consumes the checkpoint → Restore fires.
     *
     * # Why mint a new token if we're "returning"
     *
     * The current chatState.content was CLEARED when openSubAgent pushed the
     * child (`navigateToChat(childId)` cleared it). A new token + openForRoute
     * triggers VerifyAndHydrate, which peeks the LRU for the parent's cached
     * window and re-hydrates synchronously (no REST round-trip in the common
     * case). Without this, ChatDetailSlice would render Loading forever
     * (no path populates content).
     *
     * # Bump navEpoch (in addition to lastRoute)
     *
     * The synchronizer observes `(lastRoute, navEpoch)`. lastRoute IS
     * structurally changing (chat/child → chat/parent) so the synchronizer
     * would fire on lastRoute alone; navEpoch++ is defensive (covers any
     * future same-route return path).
     */
    fun returnToExistingChat(parentId: String) {
        val route = "chat/$parentId"
        core.settingsManager.lastRoute = route
        // Atomic CAS: mint token + write navState.lastRoute + bump navEpoch +
        // clear content (mirror navigateToChat; the load pipeline repopulates
        // content via VerifyAndHydrate → LRU peek or REST fetch).
        core.store.mutateState {
            val next = it.chatRouteInstance + 1L
            it.copy(
                chatRouteInstance = next,
                nav = it.nav.copy(
                    lastRoute = route,
                    lastNavPage = NavRoute.Chat.legacyPage,
                    navEpoch = it.nav.navEpoch + 1L,
                ),
                chat = it.chat.copy(content = null),
            )
        }
        val mintedToken = core.store.stateFlow.value.chatRouteInstance
        // Same route-aware open as navigateToChat. The pop semantics are
        // decided at the NavController level (AppShell synchronizer's
        // previousBackStackEntry check) — not the VM.
        core.sessionSwitcher.openForRoute(parentId, mintedToken)
    }

    /** Emits a same-tab selection without mutating persisted navigation state. */
    fun emitReselect(route: NavRoute) {
        _reselectFlow.tryEmit(route)
    }

    // ── Permission / Question responses (orchestrator-domain) ───────────────

    fun respondPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        // §Phase3b slim-branch: when the permission arrived via slim SSE /
        // /slimapi/permissions the sidecar re-injects the directory from
        // this HMAC token; legacy respondPermission relies on a global
        // currentDirectory header which has no correct value on the slim
        // cross-directory aggregation surface. Null = legacy single-dir path.
        routeToken: String? = null,
    ) {
        // §R18 Phase 3 Wave 3 (drift #6 / P1-7): user-triggered ephemeral
        // permission response → viewModelScope. Closure captures the repo
        // call + a slice transform (never a VM `::ref`), so viewModelScope
        // cancels cleanly on navigation-away.
        viewModelScope.launch {
            val result = if (routeToken != null) {
                core.repository.respondSlimapiPermission(sessionId, permissionId, response, routeToken)
            } else {
                core.repository.respondPermission(sessionId, permissionId, response)
            }
            result
                .onSuccess {
                    core.writeSessionList { it.copy(pendingPermissions = it.pendingPermissions.filter { p -> p.id != permissionId }) }
                }
                .onFailure { error ->
                    core.effectBus.tryEmitUiEvent(UiEvent.Error(R.string.error_respond_permission_failed, listOf(errorMessageOrFallback(error, "unknown error"))))
                }
        }
    }

    fun replyQuestion(
        requestId: String,
        answers: List<List<String>>,
        // §Phase3b slim-branch: see [respondPermission]'s routeToken doc.
        // Slim path skips resolveQuestionDirectory — the sidecar derives
        // the directory from the token, so the legacy directory resolution
        // is both unnecessary and (on the aggregation surface) wrong.
        routeToken: String? = null,
        onError: () -> Unit = {},
    ) {
        // §R18 Phase 3 Wave 3 (drift #6 / P1-7): same viewModelScope rationale
        // as respondPermission.
        viewModelScope.launch {
            val result = if (routeToken != null) {
                DebugLog.d("Question", "replyQuestion slim req=$requestId token=$routeToken")
                core.repository.replySlimapiQuestion(requestId, answers, routeToken)
            } else {
                // §R18 Phase 2-E step 1: explicit directory now required by the
                // API. Resolve from the question's parent session if possible
                // (handles cross-workdir routing); fall back to the persisted
                // workdir. Was the global currentDirectory before — currentWorkdir
                // was always its source on the main path.
                val directory = core.resolveQuestionDirectory(requestId)
                // §Phase1a instrumentation (Issue 1): the directory actually sent on the reply.
                DebugLog.d("Question", "replyQuestion req=$requestId dir=${directory ?: "null"}")
                core.repository.replyQuestion(requestId, answers, directory)
            }
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

    fun rejectQuestion(
        requestId: String,
        // §Phase3b slim-branch: see [respondPermission]'s routeToken doc.
        routeToken: String? = null,
        onError: () -> Unit = {},
    ) {
        // §R18 Phase 3 Wave 3 (drift #6 / P1-7): same viewModelScope rationale
        // as respondPermission.
        viewModelScope.launch {
            val result = if (routeToken != null) {
                DebugLog.d("Question", "rejectQuestion slim req=$requestId token=$routeToken")
                core.repository.rejectSlimapiQuestion(requestId, routeToken)
            } else {
                val directory = core.resolveQuestionDirectory(requestId)
                // §Phase1a instrumentation (Issue 1): the directory actually sent on the reject.
                DebugLog.d("Question", "rejectQuestion req=$requestId dir=${directory ?: "null"}")
                core.repository.rejectQuestion(requestId, directory)
            }
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
                    // §issue-1 Fix C / Phase 2 gate 🔴1: reject failure surfaces via the
                    // card's onError callback → in-card errorText, symmetric with
                    // replyQuestion (which also only calls onError, no global Snackbar).
                    // The Phase 2a VM-level UiEvent.Error was removed: the card is always
                    // visible for the current session's question (it owns isRejecting +
                    // errorText), so the Snackbar was a redundant double-display.
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
