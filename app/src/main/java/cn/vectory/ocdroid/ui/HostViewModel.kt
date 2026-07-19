package cn.vectory.ocdroid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.ui.controller.HostProfileController
import cn.vectory.ocdroid.ui.settings.ClientCertEditIntent
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.certSubjectOrNull
import cn.vectory.ocdroid.util.loadClientP12OrNull
import cn.vectory.ocdroid.util.parseCaCertOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * C-D3 rev-3 round-7 (review I5-R7): the lifecycle state of an active
 * [HostViewModel.saveHostProfile] transaction. Owned by [HostViewModel] so
 * the save outlives the screen (a reconfigure transaction, once begun,
 * MUST complete — abandoning it mid-flight leaves the host
 * half-reconfigured). The screen observes via [HostViewModel.saveState] and
 * guards completion by [Done.profileId] (a stale completion from a dismissed
 * editor must not close a newer editor showing a different profile).
 *
 * Lifecycle:
 *  - [Idle] — no save in flight; initial state.
 *  - [Saving] — `viewModelScope.launch` running the boundary transaction;
 *    double-submit is ignored (single-flight — see [HostViewModel.saveHostProfile]).
 *  - [Done] — the launch finished (success OR failure). The screen handles
 *    it (close on success + profileId match, error text on failure) and
 *    calls [HostViewModel.consumeSaveState] to return to [Idle]. A later
 *    retry is accepted because [Saving] is no longer current.
 */
sealed interface HostProfileSaveState {
    /** No active save. */
    object Idle : HostProfileSaveState

    /**
     * A save transaction is running in `viewModelScope`. [profileId] is the
     * profile being saved — the screen's `LaunchedEffect(saveState)` uses
     * it on completion to gate the close (only close if still editing the
     * SAME profile).
     */
    data class Saving(val profileId: String) : HostProfileSaveState

    /**
     * The save launch finished. [result] is the [Result] from
     * [HostProfileController.saveHostProfile] — `isSuccess` → close dialog;
     * `isFailure` → surface error, keep dialog open. The screen MUST
     * [HostViewModel.consumeSaveState] after handling so a later retry is
     * accepted (state returns to [Idle]).
     */
    data class Done(val profileId: String, val result: Result<Unit>) : HostProfileSaveState
}

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

    /**
     * C-D3 rev-3 round-7 (review I5-R7): the save transaction's lifecycle
     * state. Owned by THIS VM (viewModelScope) so the save outlives the
     * screen — a reconfigure transaction, once begun (identity invalidated
     * + slim ticket started + barrier mutex acquired), MUST complete;
     * abandoning it mid-flight leaves the host half-reconfigured. The
     * screen-level scope was removed; this VM scope is the new owner.
     */
    private val _saveState = MutableStateFlow<HostProfileSaveState>(HostProfileSaveState.Idle)
    val saveState: StateFlow<HostProfileSaveState> = _saveState.asStateFlow()

    /**
     * C-D3 rev-3 round-7 (review I5-R7): non-suspend save launcher. Owns
     * execution in `viewModelScope` (Main dispatcher by default + survives
     * screen navigation). The screen's Save button calls this directly; the
     * result is observed via [saveState] + [HostProfileSaveState.Done].
     *
     * **Single-flight**: while [HostProfileSaveState.Saving] is current, a
     * new call is IGNORED — a reconfigure transaction must finish once
     * begun, so we do NOT cancel/restart the in-flight job. After
     * [HostProfileSaveState.Done] (success or failure), the launch is no
     * longer active so retry works (the screen calls [consumeSaveState]
     * after handling Done to return to [HostProfileSaveState.Idle]).
     *
     * The controller's [HostProfileController.saveHostProfile] is suspend +
     * `Result<Unit>`; [runSuspendCatching][cn.vectory.ocdroid.util.runSuspendCatching]
     * rethrows `CancellationException` so viewModelScope cancellation (e.g.
     * VM clear) propagates cleanly.
     */
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
        // Single-flight: do NOT cancel/restart an in-flight save. A reconfigure
        // transaction must finish once begun; the second call is silently
        // ignored. After Done, the job is no longer active → retry accepted.
        if (_saveState.value is HostProfileSaveState.Saving) return
        _saveState.value = HostProfileSaveState.Saving(profile.id)
        viewModelScope.launch {
            val result = hostProfileController.saveHostProfile(
                profile, basicAuthPassword, basicAuthEdited, tunnelPassword, tunnelEdited,
                clientCertEdit = clientCertEdit,
            )
            _saveState.value = HostProfileSaveState.Done(profile.id, result)
        }
    }

    /**
     * C-D3 rev-3 round-7: the screen calls this after handling
     * [HostProfileSaveState.Done] so the state returns to
     * [HostProfileSaveState.Idle] (a later retry is accepted). Idempotent —
     * calling while Idle / Saving is a no-op.
     */
    fun consumeSaveState() {
        // M1 (post-release polish): honor the KDoc idempotence contract — only
        // Done is consumable. Calling while Saving would clobber the in-flight
        // reconfigure transaction (orphaning it half-done); calling while Idle
        // is already a no-op. Guard makes impl match the documented behavior.
        if (_saveState.value !is HostProfileSaveState.Done) return
        _saveState.value = HostProfileSaveState.Idle
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
     * §14: toggle a single model's disabled flag for the current host profile.
     *
     * Mirrors [ComposerViewModel.toggleModelDisabled] — writes the pref via
     * [SettingsManager.setModelDisabled] AND mirrors the change into the
     * settings slice via [SharedStateStore.mutateSettings]. The previous
     * HostProfilesManagerScreen path called `settingsManager.setModelDisabled`
     * directly, which only touched encrypted prefs; the UI reads
     * `settingsFlow.disabledModels` (reactive), so the Switch never reflected
     * the toggle. Routing through the VM keeps both stores in sync.
     *
     * §14.3: the screen's old `currentFp ?: return@ModelManagementSection` is
     * gone — fp is resolved here from [currentHostProfile] (non-null, same
     * `serverGroupFp.ifBlank { id }` resolution as ComposerViewModel), so the
     * silent no-op on an unresolved profile is eliminated.
     */
    fun toggleModelDisabled(providerId: String, modelId: String) {
        val profile = currentHostProfile()
        val fp = profile.serverGroupFp.ifBlank { profile.id }
        val key = "$providerId/$modelId"
        val currentlyDisabled = key in store.settingsFlow.value.disabledModels
        settingsManager.setModelDisabled(fp, providerId, modelId, disabled = !currentlyDisabled)
        store.mutateSettings {
            it.copy(
                disabledModels = if (currentlyDisabled) it.disabledModels - key else it.disabledModels + key
            )
        }
    }

    /**
     * §14: bulk enable/disable every model under [providerId] for the current
     * host profile. Mirrors [ComposerViewModel.setProviderModelsEnabled] — a
     * single [SettingsManager.setDisabledModels] write plus settingsFlow sync
     * (one IO instead of N incremental writes). Same fp-resolution / §14.3 fix
     * as [toggleModelDisabled].
     */
    fun setProviderModelsEnabled(providerId: String, enabled: Boolean) {
        val profile = currentHostProfile()
        val fp = profile.serverGroupFp.ifBlank { profile.id }
        val providers = store.settingsFlow.value.providers?.providers.orEmpty()
        val provider = providers.firstOrNull { it.id == providerId } ?: return
        val current = store.settingsFlow.value.disabledModels.toMutableSet()
        provider.models.keys.forEach { mid ->
            val key = "$providerId/$mid"
            if (enabled) current.remove(key) else current.add(key)
        }
        settingsManager.setDisabledModels(fp, current)
        store.mutateSettings { it.copy(disabledModels = current) }
    }

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

    fun configureServer(url: String, username: String? = null, password: String? = null) =
        hostProfileController.configureServer(url, username, password)

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
