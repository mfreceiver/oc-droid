package cn.vectory.ocdroid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.controller.ConnectionCoordinator
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.TrafficTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * R-17 batch3 → batch3d: Connection-domain ViewModel. Owns the connection
 * slice + the health-probe / SSE / cold-start-reconnect lifecycle.
 *
 * **batch3d**: method bodies physically moved here from [AppCore]. The VM
 * calls its domain controller ([ConnectionCoordinator]) directly — no
 * `core.<method>()` self-bypass. The `testConnectionForm` body (which
 * resolves the password + runs the repository health check) lives here now.
 *
 * §R18 Phase 3 Wave 3 (P2-6): the traffic role (refresh / reset counters)
 * moved here from [OrchestratorViewModel]. Traffic is connectivity-shaped
 * state (bytes sent / received over the wire) so it groups naturally with
 * the connection slice; consolidating it here drops one more responsibility
 * from the overloaded [OrchestratorViewModel]. Pure relocation — zero
 * behaviour change.
 *
 * §R-19 Sprint 3 P2-5: this VM no longer injects [AppCore]. Its precise
 * dependency surface is the connection / settings / host / traffic slices
 * ([SharedStateStore]) + [ConnectionCoordinator] + [SettingsManager] (the
 * basicAuthPassword helper used by testConnectionForm) +
 * [OpenCodeRepository] (the health probe) + [TrafficTracker] (the running
 * byte counter snapshotted into [trafficFlow]). The VM cannot reach any
 * other slice/controller.
 */
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val store: SharedStateStore,
    private val connectionCoordinator: ConnectionCoordinator,
    private val settingsManager: SettingsManager,
    private val repository: OpenCodeRepository,
    private val trafficTracker: TrafficTracker,
) : ViewModel() {

    /**
     * §R-19 P2-5 test-only convenience constructor — see
     * [SettingsViewModel.secondary constructor] rationale. Forwards the same
     * deps the production Hilt binding uses.
     */
    internal constructor(core: AppCore) : this(
        core.store,
        core.connectionCoordinator,
        core.settingsManager,
        core.repository,
        core.trafficTracker,
    )

    val connectionFlow get() = store.connectionFlow
    val settingsFlow get() = store.settingsFlow
    val hostFlow get() = store.hostFlow

    /** §P2-6: traffic slice read accessor (moved from [OrchestratorViewModel]).
     *  Same authoritative slice every other VM exposes (all delegate to
     *  [SharedStateStore]); kept here so SettingsScreen + ChatScreen read
     *  traffic off the connection VM. */
    val trafficFlow get() = store.trafficFlow

    fun testConnection(force: Boolean = false, retries: Int = 0, onSettled: ((Boolean) -> Unit)? = null) {
        connectionCoordinator.testConnection(force = force, retries = retries, onSettled = onSettled)
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
        // §R18 Phase 3 Wave 2 (drift #6 / P1-7): user-triggered "test
        // connection" → viewModelScope. The closure captures the onResult
        // callback (caller-supplied, never a VM `::ref`), so binding to
        // viewModelScope cancels the health probe cleanly if the user
        // navigates away mid-check.
        viewModelScope.launch {
            val effectivePassword = resolveTestConnectionPassword(
                password, passwordEdited, profileId,
            ) { settingsManager.basicAuthPassword(it) }
            val result = repository.checkHealthFor(baseUrl, username, effectivePassword, allowInsecure)
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

    fun coldStartReconnect() = connectionCoordinator.coldStartReconnect()

    // ── Traffic (§P2-6: moved from OrchestratorViewModel) ───────────────────

    /** Snapshots the latest tracker totals into [trafficFlow] so the UI
     *  displays the current cumulative byte counts. The tracker keeps
     *  accumulating regardless; this just syncs the snapshot for display. */
    fun refreshTrafficStats() {
        store.mutateTraffic {
            it.copy(
                trafficSent = trafficTracker.totalBytesSent,
                trafficReceived = trafficTracker.totalBytesReceived,
            )
        }
    }

    /** Zeroes the tracker's running totals then snapshots them into
     *  [trafficFlow] so the displayed counters reset to 0. */
    fun resetTrafficStats() {
        trafficTracker.reset()
        refreshTrafficStats()
    }

    fun startSSE() = connectionCoordinator.startSSE()

    fun cancelSse() { connectionCoordinator.cancelSse() }

    fun cancelSseForReconfigure() { connectionCoordinator.cancelSseForReconfigure() }

    /** Connection-driven initial data fetch (sessions/agents/providers/...).
     *  Routes through the connection coordinator which owns the fan-out. */
    fun loadInitialData() = connectionCoordinator.loadInitialData()
}
