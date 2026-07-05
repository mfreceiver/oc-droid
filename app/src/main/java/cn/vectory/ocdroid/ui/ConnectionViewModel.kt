package cn.vectory.ocdroid.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * R-17 batch3 → batch3d: Connection-domain ViewModel. Owns the connection
 * slice + the health-probe / SSE / cold-start-reconnect lifecycle.
 *
 * **batch3d**: method bodies physically moved here from [AppCore]. The VM
 * calls its domain controller ([AppCore.connectionCoordinator]) directly —
 * no `core.<method>()` self-bypass. The `testConnectionForm` body (which
 * resolves the password + runs the repository health check) lives here now.
 */
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    internal val core: AppCore,
) : ViewModel() {

    val connectionFlow get() = core.connectionFlow
    val settingsFlow get() = core.settingsFlow
    val hostFlow get() = core.hostFlow

    fun testConnection(force: Boolean = false, retries: Int = 0, onSettled: ((Boolean) -> Unit)? = null) {
        core.connectionCoordinator.testConnection(force = force, retries = retries, onSettled = onSettled)
    }

    fun testConnectionForm(
        baseUrl: String,
        username: String?,
        password: String?,
        allowInsecure: Boolean,
        profileId: String?,
        passwordEdited: Boolean,
        onResult: (success: Boolean, message: String) -> Unit,
    ) {
        // §R-17 batch3d: body moved verbatim from AppCore.
        core.appScope.launch {
            val effectivePassword = resolveTestConnectionPassword(
                password, passwordEdited, profileId,
            ) { core.settingsManager.basicAuthPassword(it) }
            val result = core.repository.checkHealthFor(baseUrl, username, effectivePassword, allowInsecure)
            result
                .onSuccess { health ->
                    if (health.healthy) {
                        val msg = health.version?.let { v -> "连接成功 (v$v)" } ?: "连接成功"
                        onResult(true, msg)
                    } else {
                        onResult(false, "服务器不可用 (healthy=false)")
                    }
                }
                .onFailure { e -> onResult(false, e.message ?: "连接失败") }
        }
    }

    fun coldStartReconnect() = core.connectionCoordinator.coldStartReconnect()

    fun startSSE() = core.connectionCoordinator.startSSE()

    fun cancelSse() { core.connectionCoordinator.cancelSse() }

    fun cancelSseForReconfigure() { core.connectionCoordinator.cancelSseForReconfigure() }

    /** Connection-driven initial data fetch (sessions/agents/providers/...).
     *  Routes through the connection coordinator which owns the fan-out. */
    fun loadInitialData() = core.connectionCoordinator.loadInitialData()
}
