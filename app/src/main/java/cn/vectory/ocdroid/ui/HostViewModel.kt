package cn.vectory.ocdroid.ui

import androidx.lifecycle.ViewModel
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.ui.controller.HostProfileController
import cn.vectory.ocdroid.ui.settings.ClientCertEditIntent
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.certSubjectOrNull
import cn.vectory.ocdroid.util.loadClientP12OrNull
import cn.vectory.ocdroid.util.parseCaCertOrNull
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
    private val settingsManager: SettingsManager,
) : ViewModel() {

    /**
     * §R-19 P2-5 test-only convenience constructor — see
     * [SettingsViewModel.secondary constructor] rationale. Forwards the same
     * deps the production Hilt binding uses.
     */
    internal constructor(core: AppCore) : this(core.store, core.hostProfileController, core.settingsManager)

    val hostFlow get() = store.hostFlow
    val connectionFlow get() = store.connectionFlow
    val settingsFlow get() = store.settingsFlow

    fun saveHostProfile(
        profile: HostProfile,
        basicAuthPassword: String = "",
        basicAuthEdited: Boolean = false,
        tunnelPassword: String = "",
        tunnelEdited: Boolean = false,
        // §2.7 fix-3 (gpt-2#3): 显式 mTLS 编辑意图，默认 Unchanged（不动既有证书）。
        // Dialog 路径构造 Update / Disable；其它调用方默认 Unchanged 不破坏。
        clientCertEdit: ClientCertEditIntent = ClientCertEditIntent.Unchanged,
    ) {
        hostProfileController.saveHostProfile(
            profile, basicAuthPassword, basicAuthEdited, tunnelPassword, tunnelEdited,
            clientCertEdit = clientCertEdit,
        )
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

    /**
     * §item8 (cgpt#6 + grok#2): 编辑对话框据此判断「是否已存私有 CA」——
     * `initial.clientCertId != null` 只能证明有客户端证书，不等于有 CA
     * （CA 是 client_cert_ca_<id> 独立槽）。本查询直接读 ESP 的 CA 槽，
     * 供 Dialog 的 CA placeholder 提示 + 「移除 CA」按钮可见性使用。
     * 读 ESP 是内存 Map 查询（首次加载后缓存），Dialog 打开时同步调用安全。
     */
    fun hasStoredCa(clientCertId: String?): Boolean =
        clientCertId?.let { settingsManager.getClientCertCa(it) != null } ?: false

    /**
     * §mtls-clipboard: 重入时已存 CA 的 (subject, sizeBytes) 摘要，供编辑对话框的
     * CA 导入槽渲染 Imported 态（修「write-only 字段重入显示空」）。读 ESP 的 CA 槽
     * → parseCaCertOrNull 取 subject；解析失败回退 "CA"。纯展示，null ⇒ 槽位 Empty。
     */
    fun caSummary(clientCertId: String?): Pair<String, Int>? =
        clientCertId?.let { id ->
            settingsManager.getClientCertCa(id)?.let { bytes ->
                parseCaCertOrNull(bytes)?.let { cert ->
                    (certSubjectOrNull(cert) ?: "CA") to bytes.size
                }
            }
        }

    /**
     * §mtls-clipboard: 重入时已存客户端 p12 的 (leaf-subject, sizeBytes) 摘要，供编辑
     * 对话框的客户端证书导入槽渲染 Imported 态。读 ESP 的 p12 + 口令 →
     * loadClientP12OrNull 取首个 key-entry 的叶子证书 subject；口令缺失时按空口令试
     * （新模型为无口令 p12）。解析失败 → null（槽位 Empty）。纯展示。
     */
    fun clientCertSummary(clientCertId: String?): Pair<String, Int>? =
        clientCertId?.let { id ->
            settingsManager.getClientCertP12(id)?.let { p12 ->
                val pw = settingsManager.getClientCertPassword(id) ?: ""
                loadClientP12OrNull(p12, pw.toCharArray())?.let { ks ->
                    val leaf = ks.aliases().asSequence()
                        .firstOrNull { ks.isKeyEntry(it) }
                        ?.let { a -> ks.getCertificate(a) as? java.security.cert.X509Certificate }
                    (leaf?.let { certSubjectOrNull(it) } ?: "client") to p12.size
                }
            }
        }

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
