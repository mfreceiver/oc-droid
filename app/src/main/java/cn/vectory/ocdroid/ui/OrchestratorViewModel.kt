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

    // ── Nav ─────────────────────────────────────────────────────────────────

    fun setLastNavPage(page: Int) {
        val clamped = page.coerceIn(0, 2)
        if (core.store.navFlow.value.lastNavPage == clamped) return
        core.settingsManager.lastNavPage = clamped
        val route = NavRoute.fromLegacyPage(clamped)
        core.settingsManager.lastRoute = route.route
        core.store.mutateNav { it.copy(lastRoute = route.route, lastNavPage = clamped) }
    }

    /** Called by AppShell (the sole shell) after composition; getter performs old-int migration. */
    fun restoreLastRoute() = setLastRoute(NavRoute.fromRouteKey(core.settingsManager.lastRoute))

    fun setLastRoute(route: NavRoute) {
        val state = core.store.navFlow.value
        if (state.lastRoute == route.route && state.lastNavPage == route.legacyPage) return
        core.settingsManager.lastRoute = route.route
        core.store.mutateNav { it.copy(lastRoute = route.route, lastNavPage = route.legacyPage) }
    }

    /** Emits a same-tab selection without mutating persisted navigation state. */
    fun emitReselect(route: NavRoute) {
        _reselectFlow.tryEmit(route)
    }

    // ── Permission / Question responses (orchestrator-domain) ───────────────

    fun respondPermission(sessionId: String, permissionId: String, response: PermissionResponse) {
        // §R18 Phase 3 Wave 3 (drift #6 / P1-7): user-triggered ephemeral
        // permission response → viewModelScope. Closure captures the repo
        // call + a slice transform (never a VM `::ref`), so viewModelScope
        // cancels cleanly on navigation-away.
        viewModelScope.launch {
            core.repository.respondPermission(sessionId, permissionId, response)
                .onSuccess {
                    core.writeSessionList { it.copy(pendingPermissions = it.pendingPermissions.filter { p -> p.id != permissionId }) }
                }
                .onFailure { error ->
                    core.effectBus.tryEmitUiEvent(UiEvent.Error(R.string.error_respond_permission_failed, listOf(errorMessageOrFallback(error, "unknown error"))))
                }
        }
    }

    fun replyQuestion(requestId: String, answers: List<List<String>>, onError: () -> Unit = {}) {
        // §R18 Phase 3 Wave 3 (drift #6 / P1-7): same viewModelScope rationale
        // as respondPermission.
        viewModelScope.launch {
            // §R18 Phase 2-E step 1: explicit directory now required by the
            // API. Resolve from the question's parent session if possible
            // (handles cross-workdir routing); fall back to the persisted
            // workdir. Was the global currentDirectory before — currentWorkdir
            // was always its source on the main path.
            val directory = core.resolveQuestionDirectory(requestId)
            // §Phase1a instrumentation (Issue 1): the directory actually sent on the reply.
            DebugLog.d("Question", "replyQuestion req=$requestId dir=${directory ?: "null"}")
            core.repository.replyQuestion(requestId, answers, directory)
                .onSuccess {
                    DebugLog.d("Question", "replyQuestion OK req=$requestId")
                    core.writeSessionList { currentState ->
                        currentState.copy(pendingQuestions = currentState.pendingQuestions.filter { it.id != requestId })
                    }
                }
                .onFailure { error ->
                    DebugLog.w("Question", "replyQuestion FAIL req=$requestId dir=${directory ?: "null"} err=${error.message}")
                    android.util.Log.w(TAG, "Failed to reply question: ${error.message}")
                    onError()
                }
        }
    }

    fun rejectQuestion(requestId: String, onError: () -> Unit = {}) {
        // §R18 Phase 3 Wave 3 (drift #6 / P1-7): same viewModelScope rationale
        // as respondPermission.
        viewModelScope.launch {
            val directory = core.resolveQuestionDirectory(requestId)
            // §Phase1a instrumentation (Issue 1): the directory actually sent on the reject.
            DebugLog.d("Question", "rejectQuestion req=$requestId dir=${directory ?: "null"}")
            core.repository.rejectQuestion(requestId, directory)
                .onSuccess {
                    DebugLog.d("Question", "rejectQuestion OK req=$requestId")
                    core.writeSessionList { currentState ->
                        currentState.copy(pendingQuestions = currentState.pendingQuestions.filter { it.id != requestId })
                    }
                }
                .onFailure { error ->
                    DebugLog.w("Question", "rejectQuestion FAIL req=$requestId dir=${directory ?: "null"} err=${error.message}")
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
