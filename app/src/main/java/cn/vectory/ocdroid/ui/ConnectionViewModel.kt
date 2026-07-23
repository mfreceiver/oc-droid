package cn.vectory.ocdroid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.data.repository.http.ClientCertMaterial
import cn.vectory.ocdroid.data.repository.http.SlimapiContract
import cn.vectory.ocdroid.data.repository.http.TofuDecision
import cn.vectory.ocdroid.data.repository.http.buildMutualTlsConfig
import cn.vectory.ocdroid.data.repository.http.hostPortFromUrl
import cn.vectory.ocdroid.ui.controller.ConnectionCoordinator
import cn.vectory.ocdroid.ui.settings.CaStage
import cn.vectory.ocdroid.ui.settings.resolveClientCert
import cn.vectory.ocdroid.ui.settings.toMaterial
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
    /**
     * §slim-reconcile-lane-repo (Phase 3a / Lane-B3-Dialog): the shared
     * [ServerCompatProfile] (Hilt @Singleton — same instance
     * [OpenCodeRepository] writes to from [OpenCodeRepository.checkHealthFor]'s
     * slim branch). Read AFTER [repository.checkHealthFor] returns so the
     * test-connection path can detect slimapi version mismatch (M2 self-check)
     * and feed [ConnectionState.slimapiVersionIncompatible] — closing the
     * fail-closed UX loop (transport fail-close worked but the blocking dialog
     * never fired because the flag was never written from this path).
     */
    private val serverCompatProfile: ServerCompatProfile,
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
        core.serverCompatProfile,
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
        profileId: String?,
        passwordEdited: Boolean,
        slim: Boolean = false,
        onResult: (success: Boolean, message: String) -> Unit,
    ) = testConnectionForm(
        baseUrl, username, password, profileId, passwordEdited,
        // §2.7 默认无 mTLS（兼容既有 6-arg 调用方 / 旧测试）：
        mtlsEnabled = false, stagedP12 = null, hasImportedP12 = false,
        caStage = CaStage.Unchanged, p12Password = null, p12PasswordEdited = false,
        clientCertId = null, slim = slim, onResult = onResult,
    )

    /**
     * §R-17 batch3d: body moved verbatim from AppCore.
     * §R18 Phase 3 Wave 2 (drift #6 / P1-7): user-triggered "test
     * connection" → viewModelScope. The closure captures the onResult
     * callback (caller-supplied, never a VM `::ref`), so binding to
     * viewModelScope cancels the health probe cleanly if the user
     * navigates away mid-check.
     *
     * §2.7: mTLS 测试连接——由 [resolveClientCert] 把 Dialog 透传字段归一为生效
     * 材料（mtlsEnabled 时），构造 [ClientCertMaterial] 传入 [checkHealthFor]。
     * 走 [OpenCodeRepository.checkHealthFor] 的 `resolveProbe` 纯参数解析（不污染
     * 当前 host 的 held mTLS 状态），否则 mTLS host 测试必被 stunnel 拒（gpter#3/
     * glmer I6）。
     *
     * §tofu R2: `allowInsecure` 参数已删——self-signed / unknown-issuer 证书由首次
     * 连接时的 TOFU 信任对话框处理（host:port 经 [hostPortFromUrl] 解析）。
     */
    fun testConnectionForm(
        baseUrl: String,
        username: String?,
        password: String?,
        profileId: String?,
        passwordEdited: Boolean,
        mtlsEnabled: Boolean,
        stagedP12: ByteArray?,
        hasImportedP12: Boolean,
        caStage: CaStage,
        p12Password: String?,
        p12PasswordEdited: Boolean,
        clientCertId: String?,
        slim: Boolean = false,
        onResult: (success: Boolean, message: String) -> Unit,
    ) {
        viewModelScope.launch {
            val effectivePassword = resolveTestConnectionPassword(
                password, passwordEdited, profileId,
            ) { settingsManager.basicAuthPassword(it) }
            // §2.7: 解析 mTLS 客户端证书材料（mtlsEnabled && 有 p12 时）。
            val resolved = resolveClientCert(
                mtlsEnabled = mtlsEnabled,
                stagedP12 = stagedP12,
                hasImportedP12 = hasImportedP12,
                caStage = caStage,
                p12Password = p12Password,
                p12PasswordEdited = p12PasswordEdited,
                oldId = clientCertId,
                loadP12 = { settingsManager.getClientCertP12(it) },
                loadPassword = { settingsManager.getClientCertPassword(it) },
                loadCa = { settingsManager.getClientCertCa(it) },
            )
            // §fix-3 (gpt-2#1): mTLS 开但无证 → fail-fast，不发静默无证探测。
            if (mtlsEnabled && resolved == null) {
                onResult(false, "开启 mTLS 需先导入客户端证书")
                return@launch
            }
            // §fix-3 (max-1 S3): CA Replace 空字节（读流失败/空文件）→ 试构建守卫。
            val clientCert: ClientCertMaterial? = resolved?.toMaterial()
            if (clientCert != null) {
                val buildError = runCatching { buildMutualTlsConfig(clientCert) }.exceptionOrNull()
                if (buildError != null) {
                    onResult(false, "客户端证书无效：${buildError.message}")
                    return@launch
                }
            }
            val result = repository.checkHealthFor(
                baseUrl, username, effectivePassword,
                hostPort = hostPortFromUrl(baseUrl),
                clientCert = clientCert,
                slim = slim,
            )
            // §slim-reconcile-lane-repo (Phase 3a / Lane-B3-Dialog): M2 自检
            // 闭环——checkHealthFor 的 slim 分支已把 sidecar 公告的版本契约喂
            // [serverCompatProfile]（镜像 probeSlimapiHealth T5）。这里读回来：
            // slim 模式 + 服务端给出了 accepted_client_versions + 客户端版本
            // 不在闭区间内 → 写 [ConnectionState.slimapiVersionIncompatible]
            // 让 HostProfilesManagerScreen 弹阻塞 dialog（fail-closed UX）。
            // 否则（兼容 / legacy / 服务端未给出该字段）→ 清 stale flag，避免
            // 上一次不兼容测试残留干扰。
            val scpMin = serverCompatProfile.slimapiAcceptedMin
            val scpMax = serverCompatProfile.slimapiAcceptedMax
            if (slim && scpMin != null && scpMax != null &&
                !serverCompatProfile.isSlimapiClientAccepted()
            ) {
                store.mutateConnection {
                    it.copy(
                        slimapiVersionIncompatible = Triple(
                            SlimapiContract.SLIMAPI_CLIENT_VERSION,
                            scpMin,
                            scpMax,
                        ),
                        isSlimActive = serverCompatProfile.slimConnection,
                    )
                }
                onResult(
                    false,
                    "slimapi 版本不兼容（客户端=${SlimapiContract.SLIMAPI_CLIENT_VERSION}, " +
                        "服务端接受=[$scpMin,$scpMax]）"
                )
                return@launch
            }
            // 清 stale：兼容 / legacy / 服务端未公告 accepted_client_versions。
            store.mutateConnection {
                it.copy(
                    slimapiVersionIncompatible = null,
                    isSlimActive = serverCompatProfile.slimConnection,
                )
            }
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
                resetAt = trafficTracker.resetAt,
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

    /**
     * §tofu R2: feeds the user's trust decision for the currently-pending
     * TOFU capture into the connection coordinator. The coordinator unblocks
     * the [testConnection] retry loop, writes the pin (Accept/Trust) or
     * settles false (Cancel), and either re-probes or terminates.
     */
    fun resolveTofuTrust(decision: TofuDecision) {
        connectionCoordinator.resolveTofuTrust(decision)
    }

    /** Connection-driven initial data fetch (sessions/agents/providers/...).
     *  Routes through the connection coordinator which owns the fan-out. */
    fun loadInitialData() = connectionCoordinator.loadInitialData()
}
