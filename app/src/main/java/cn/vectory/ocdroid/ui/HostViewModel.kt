package cn.vectory.ocdroid.ui

import androidx.lifecycle.ViewModel
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.ui.controller.HostProfileController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * R-17 batch3 → batch3d: Host-profile-domain ViewModel. Owns the host slice
 * + Host Profile CRUD + repository reconfiguration + tunnel activation.
 *
 * **batch3d**: method bodies physically moved here from [AppCore]. The VM
 * calls its domain controller ([HostProfileController]) directly — no
 * `core.<method>()` self-bypass.
 *
 * `resetLocalDataAndResync` is a CROSS-DOMAIN reset (it touches session list,
 * chat, host, connection, and the session-window cache). The orchestration
 * is owned by [HostProfileController.resetLocalDataAndResync] itself (it
 * emits the cross-domain [ControllerEffect]s on the effect bus, which
 * [AppCore.dispatchEffect] routes to the matching controllers). The VM
 * therefore calls the host controller directly — no `core.<method>()` shell
 * needed.
 *
 * §R-19 Sprint 3 P2-5: this VM no longer injects [AppCore]. Its precise
 * dependency surface is the host / connection / settings slices
 * ([SharedStateStore]) + [HostProfileController]. The VM cannot reach any
 * other slice/controller — it has no compile-time handle to them.
 */
@HiltViewModel
class HostViewModel @Inject constructor(
    private val store: SharedStateStore,
    private val hostProfileController: HostProfileController,
) : ViewModel() {

    /**
     * §R-19 P2-5 test-only convenience constructor — see
     * [SettingsViewModel.secondary constructor] rationale. Forwards the same
     * deps the production Hilt binding uses.
     */
    internal constructor(core: AppCore) : this(core.store, core.hostProfileController)

    val hostFlow get() = store.hostFlow
    val connectionFlow get() = store.connectionFlow
    val settingsFlow get() = store.settingsFlow

    fun saveHostProfile(
        profile: HostProfile,
        basicAuthPassword: String = "",
        basicAuthEdited: Boolean = false,
        tunnelPassword: String = "",
        tunnelEdited: Boolean = false,
    ) {
        hostProfileController.saveHostProfile(profile, basicAuthPassword, basicAuthEdited, tunnelPassword, tunnelEdited)
    }

    fun selectHostProfile(profileId: String) {
        // §R-17 batch3d: routes through the host controller; the cross-domain
        // fan-out (SSE cancel/restart, session-list purge, session-window
        // cache clear, cold-start reconnect) flows back through the effect bus
        // and is dispatched by AppCore. No `core.selectHostProfile()` bypass.
        hostProfileController.selectHostProfile(profileId)
    }

    fun duplicateHostProfile(profileId: String) {
        hostProfileController.duplicateHostProfile(profileId)
    }

    fun deleteHostProfile(profileId: String) {
        hostProfileController.deleteHostProfile(profileId)
    }

    fun importHostProfile(payload: String): Result<HostProfile> =
        hostProfileController.importHostProfile(payload)

    fun exportHostProfile(profile: HostProfile): String =
        hostProfileController.exportHostProfile(profile)

    fun getHostProfiles(): List<HostProfile> = hostProfileController.getHostProfiles()

    fun currentHostProfile(): HostProfile = hostProfileController.currentHostProfile()

    fun configureServer(url: String, username: String? = null, password: String? = null) {
        hostProfileController.configureServer(url, username, password)
    }

    fun getSavedConnectionSettings(): ConnectionFormSettings =
        hostProfileController.getSavedConnectionSettings()

    fun activateTunnelForCurrentHost() {
        hostProfileController.activateTunnelForCurrentHost()
    }

    /**
     * Cross-domain full-stack reset. R-19 P2-5: the [AppCore] extension
     * `AppCore.resetLocalDataAndResync()` was a 1-line delegation to
     * [HostProfileController.resetLocalDataAndResync] (see
     * AppCoreOrchestration.kt); the controller owns the cross-domain
     * fan-out itself (it mutates the host slice + emits
     * [ControllerEffect]s for SSE teardown / session-window-cache clear /
     * cold-start reconnect, dispatched by AppCore's effect-bus collector).
     * Calling the controller directly preserves the exact same behaviour
     * without forcing this VM to inject [AppCore].
     */
    fun resetLocalDataAndResync() = hostProfileController.resetLocalDataAndResync()
}
