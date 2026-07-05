package cn.vectory.ocdroid.ui

import androidx.lifecycle.ViewModel
import cn.vectory.ocdroid.data.model.PermissionResponse
import cn.vectory.ocdroid.util.MarkdownFontSizes
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * R-17 batch3 → batch3d: Orchestrator-domain ViewModel. Owns the nav /
 * file / settings UI preferences / traffic slices + the permission / question
 * response surface + the project file-browser affordances.
 *
 * **batch3d**: method bodies physically moved here from [AppCore]. The
 * settings persistence + slice writes + permission/question repository calls
 * live here now. No `core.<method>()` self-bypass.
 *
 * Genuinely cross-domain entry points (deep-link, full local reset, /clear
 * command) stay in [AppCore] (they orchestrate across 3+ domains) and are
 * surfaced via [openSessionFromDeepLink] / [resetLocalDataAndResync] /
 * [executeCommand].
 *
 * ForegroundCatchUpController still lives in [AppCore] (its effects span 4
 * domains and route through the shared [SharedEffectBus]).
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

    // ── Nav + UI preferences ────────────────────────────────────────────────

    fun setLastNavPage(page: Int) {
        // §R-17 batch3d: body moved verbatim from AppCore.
        val clamped = page.coerceIn(0, 2)
        if (core.store.navFlow.value.lastNavPage == clamped) return
        core.settingsManager.lastNavPage = clamped
        core.store.navFlow.update { it.copy(lastNavPage = clamped) }
    }

    fun setThemeMode(mode: ThemeMode) {
        core.settingsManager.themeMode = mode
        core.writeSettings { it.copy(themeMode = mode) }
    }

    fun setMarkdownFontSizes(sizes: MarkdownFontSizes) {
        core.settingsManager.markdownFontSizes = sizes
        core.writeSettings { it.copy(markdownFontSizes = sizes) }
    }

    fun setUiFontScale(scale: Float) {
        val clamped = scale.coerceIn(SettingsManager.UI_SCALE_MIN, SettingsManager.UI_SCALE_MAX)
        core.settingsManager.uiFontScale = clamped
        core.writeSettings { it.copy(uiFontScale = clamped) }
    }

    fun setUiContentScale(scale: Float) {
        val clamped = scale.coerceIn(SettingsManager.UI_SCALE_MIN, SettingsManager.UI_SCALE_MAX)
        core.settingsManager.uiContentScale = clamped
        core.writeSettings { it.copy(uiContentScale = clamped) }
    }

    // ── Permission / Question responses (orchestrator-domain) ───────────────

    fun respondPermission(sessionId: String, permissionId: String, response: PermissionResponse) {
        core.appScope.launch {
            core.repository.respondPermission(sessionId, permissionId, response)
                .onSuccess {
                    core.writeSessionList { it.copy(pendingPermissions = it.pendingPermissions.filter { p -> p.id != permissionId }) }
                }
                .onFailure { error ->
                    core.effectBus.uiEvents.tryEmit(UiEvent.Error(errorMessageOrFallback(error, "Failed to respond to permission")))
                }
        }
    }

    fun replyQuestion(requestId: String, answers: List<List<String>>, onError: () -> Unit = {}) {
        core.appScope.launch {
            core.repository.replyQuestion(requestId, answers)
                .onSuccess {
                    core.writeSessionList { currentState ->
                        currentState.copy(pendingQuestions = currentState.pendingQuestions.filter { it.id != requestId })
                    }
                }
                .onFailure { error ->
                    android.util.Log.w(TAG, "Failed to reply question: ${error.message}")
                    onError()
                }
        }
    }

    fun rejectQuestion(requestId: String) {
        core.appScope.launch {
            core.repository.rejectQuestion(requestId)
                .onSuccess {
                    core.writeSessionList { currentState ->
                        currentState.copy(pendingQuestions = currentState.pendingQuestions.filter { it.id != requestId })
                    }
                }
                .onFailure { error -> android.util.Log.w(TAG, "Failed to reject question: ${error.message}") }
        }
    }

    // ── Traffic ─────────────────────────────────────────────────────────────

    fun refreshTrafficStats() {
        core.writeTraffic {
            it.copy(trafficSent = core.trafficTracker.totalBytesSent, trafficReceived = core.trafficTracker.totalBytesReceived)
        }
    }

    fun resetTrafficStats() {
        core.trafficTracker.reset()
        refreshTrafficStats()
    }

    // ── File browser / file-to-show ─────────────────────────────────────────

    fun showFileInFiles(path: String, originRoute: String? = null) {
        core.writeFile { it.copy(filePathToShowInFiles = path, filePreviewOriginRoute = originRoute) }
    }

    fun clearFileToShow() {
        core.writeFile { it.copy(filePathToShowInFiles = null, filePreviewOriginRoute = null) }
    }

    fun browseFilesInWorkdir(workdir: String) {
        if (!browseActive) {
            browseSavedDirectory = core.repository.getCurrentDirectory()
            browseActive = true
        }
        core.repository.setCurrentDirectory(workdir)
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
        if (browseActive) {
            core.repository.setCurrentDirectory(browseSavedDirectory)
            browseSavedDirectory = null
            browseActive = false
        }
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

    fun configureServer(url: String, username: String? = null, password: String? = null) {
        core.hostProfileController.configureServer(url, username, password)
    }

    // ── browse state (private to this VM instance) ──────────────────────────
    // §R-17 batch3d: moved from AppCore private fields. The two flags track
    // whether the file browser is overriding the repository's working
    // directory so closeFileBrowser() can restore it. Per-VM (not per-process)
    // is correct: the browse session is a UI-driven transient tied to the
    // OrchestratorVM that opened it.
    private var browseSavedDirectory: String? = null
    private var browseActive: Boolean = false

    private companion object {
        private const val TAG = "OrchestratorViewModel"
    }
}
