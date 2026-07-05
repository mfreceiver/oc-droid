package cn.vectory.ocdroid.ui

import androidx.lifecycle.ViewModel
import cn.vectory.ocdroid.data.model.HostProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * R-17 batch3 → batch3d: Host-profile-domain ViewModel. Owns the host slice
 * + Host Profile CRUD + repository reconfiguration + tunnel activation.
 *
 * **batch3d**: method bodies physically moved here from [AppCore]. The VM
 * calls its domain controller ([AppCore.hostProfileController]) directly —
 * no `core.<method>()` self-bypass.
 *
 * `resetLocalDataAndResync` is a CROSS-DOMAIN reset (it touches session list,
 * chat, host, connection, and the session-window cache). The orchestration
 * stays in [AppCore] and is surfaced via [resetLocalDataAndResync] so the
 * composable does not need to inject AppCore.
 */
@HiltViewModel
class HostViewModel @Inject constructor(
    internal val core: AppCore,
) : ViewModel() {

    val hostFlow get() = core.hostFlow
    val connectionFlow get() = core.connectionFlow
    val settingsFlow get() = core.settingsFlow

    fun saveHostProfile(
        profile: HostProfile,
        basicAuthPassword: String = "",
        basicAuthEdited: Boolean = false,
        tunnelPassword: String = "",
        tunnelEdited: Boolean = false,
    ) {
        core.hostProfileController.saveHostProfile(profile, basicAuthPassword, basicAuthEdited, tunnelPassword, tunnelEdited)
    }

    fun selectHostProfile(profileId: String) {
        // §R-17 batch3d: routes through the host controller; the cross-domain
        // fan-out (SSE cancel/restart, session-list purge, session-window
        // cache clear, cold-start reconnect) flows back through the effect bus
        // and is dispatched by AppCore. No `core.selectHostProfile()` bypass.
        core.hostProfileController.selectHostProfile(profileId)
    }

    fun duplicateHostProfile(profileId: String) {
        core.hostProfileController.duplicateHostProfile(profileId)
    }

    fun deleteHostProfile(profileId: String) {
        core.hostProfileController.deleteHostProfile(profileId)
    }

    fun importHostProfile(payload: String): Result<HostProfile> =
        core.hostProfileController.importHostProfile(payload)

    fun exportHostProfile(profile: HostProfile): String =
        core.hostProfileController.exportHostProfile(profile)

    fun getHostProfiles(): List<HostProfile> = core.hostProfileController.getHostProfiles()

    fun currentHostProfile(): HostProfile = core.hostProfileController.currentHostProfile()

    fun configureServer(url: String, username: String? = null, password: String? = null) {
        core.hostProfileController.configureServer(url, username, password)
    }

    fun getSavedConnectionSettings(): ConnectionFormSettings =
        core.hostProfileController.getSavedConnectionSettings()

    fun activateTunnelForCurrentHost() {
        core.hostProfileController.activateTunnelForCurrentHost()
    }

    /** Cross-domain full-stack reset — orchestrated by [AppCore]. */
    fun resetLocalDataAndResync() = core.resetLocalDataAndResync()
}
