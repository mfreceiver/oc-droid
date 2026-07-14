package cn.vectory.ocdroid.ui

import androidx.lifecycle.ViewModel
import cn.vectory.ocdroid.data.model.ComposerImageAttachment
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.ui.controller.ComposerController
import cn.vectory.ocdroid.util.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * R-17 batch3 → batch3d: Composer-domain ViewModel. Owns the composer slice
 * + input text / image attachments / draft / model + agent selection / model
 * enable/disable.
 *
 * **batch3d**: method bodies physically moved here from [AppCore]. The
 * per-session model/agent persistence + the disabled-model set live here now
 * (they read/write the settings + composer slices directly through the shared
 * store). No `core.<method>()` self-bypass.
 *
 * §R-19 Sprint 3 P2-5: this VM no longer injects [AppCore]. Its precise
 * dependency surface is the composer / settings / chat slices
 * ([SharedStateStore]) + [ComposerController] (the composer-domain controller
 * that owns expandedParts + the draftWorkdir guard) + [SettingsManager]
 * (per-session model/agent persistence) + [HostProfileStore] (the per-host
 * baseUrl the disabled-model set is keyed by). The VM cannot reach any
 * other slice/controller.
 */
@HiltViewModel
class ComposerViewModel @Inject constructor(
    private val store: SharedStateStore,
    private val composerController: ComposerController,
    private val settingsManager: SettingsManager,
    private val hostProfileStore: HostProfileStore,
) : ViewModel() {

    /**
     * §R-19 P2-5 test-only convenience constructor — see
     * [SettingsViewModel.secondary constructor] rationale. Forwards the same
     * deps the production Hilt binding uses.
     */
    internal constructor(core: AppCore) : this(
        core.store,
        core.composerController,
        core.settingsManager,
        core.hostProfileStore,
    )

    val composerFlow get() = store.composerFlow
    val settingsFlow get() = store.settingsFlow
    val chatFlow get() = store.chatFlow

    fun setInputText(text: String) {
        composerController.setInputText(text)
    }

    fun addImageAttachments(attachments: List<ComposerImageAttachment>) {
        composerController.addImageAttachments(attachments)
    }

    fun removeImageAttachment(id: String) {
        composerController.removeImageAttachment(id)
    }

    fun clearDraftIfActive() {
        composerController.clearDraftIfActive()
    }

    fun selectAgent(agentName: String?) {
        // §chat-ux-batch T7 (B2) + T8 (B3): per-session sticky via the
        // TRANSIENT pending value. Resolution at send:
        // `agent = pendingAgent ?: infer ?: null` (see AppCoreOrchestration
        // .dispatchSendMessage). agentName=null means "clear the pending
        // pick → fall back to inference / server default".
        //
        // The legacy writes to `settingsManager.selectedAgentName` /
        // `setAgentForSession` were DELETED in T8 (T7 stopped consuming
        // them; the symbols are gone). `mutateChat` is the sole write
        // surface for the per-send agent authority.
        store.mutateChat { it.copy(pendingAgent = agentName) }
    }

    fun clearSessionModel() {
        // §chat-ux-batch T7 (B2): the ModelPickerSheet's new top "默认" item
        // clears the transient pendingModel so the next send falls back to
        // inference / server default (parallel to `selectAgent(null)` on the
        // agent side; `switchSessionModel` keeps its non-null signature for
        // concrete provider+model picks).
        store.mutateChat { it.copy(pendingModel = null) }
    }

    fun toggleModelDisabled(providerId: String, modelId: String) {
        // §R-17 batch3d: body moved verbatim from AppCore.
        // R-20 Phase 5: disabled-model set is now keyed by serverGroupFp (was
        // baseUrl) — two profiles reaching the same URL but in different
        // groups no longer clobber each other.
        val fp = hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id }
        val key = "$providerId/$modelId"
        val currentlyDisabled = key in store.settingsFlow.value.disabledModels
        settingsManager.setModelDisabled(fp, providerId, modelId, disabled = !currentlyDisabled)
        store.mutateSettings {
            it.copy(disabledModels = if (currentlyDisabled) it.disabledModels - key else it.disabledModels + key)
        }
    }

    fun setProviderModelsEnabled(providerId: String, enabled: Boolean) {
        // §provider-bulk-toggle: 一键启用/禁用某 provider 下全部 model。
        // 复用 setDisabledModels 批量写入（一次 IO），避免 N 次增量写。
        // 语义：enabled=true → 移除该 provider 所有 model 的 disabled 条目（全启用）；
        //       enabled=false → 添加该 provider 所有 model 的 disabled 条目（全禁用）。
        val fp = hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id }
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

    fun switchSessionModel(providerId: String, modelId: String) {
        // §chat-ux-batch T7 (B2) + T8 (B3): per-session sticky via the
        // TRANSIENT pending value. Resolution at send:
        // `model = pendingModel ?: infer ?: null` (see AppCoreOrchestration
        // .dispatchSendMessage). To clear the pending pick → "默认",
        // use [clearSessionModel].
        //
        // The legacy writes to `currentModel` / `setModelForSession` were
        // DELETED in T8 (T7 stopped consuming them; the symbols are gone).
        // `mutateChat` is the sole write surface for the per-send model
        // authority. (Note: the `currentModel` FIELD on ChatState is retained
        // because ChatViewModel.compactSession still reads it; it is sourced
        // from inferCurrentModel at load, not from the picker.)
        val model = Message.ModelInfo(providerId = providerId, modelId = modelId)
        store.mutateChat { it.copy(pendingModel = model) }
    }

    fun reloadDisabledModelsForCurrentHost() {
        // §R-17 batch3d: body extracted as applyReloadDisabledModelsForCurrentHost
        // so both this VM and AppCore's effect-dispatch handler share the impl.
        applyReloadDisabledModelsForCurrentHost(
            settingsManager = settingsManager,
            hostProfileStore = hostProfileStore,
            slices = store.slices,
        )
    }

    fun togglePartExpand(key: String, currentValue: Boolean) {
        composerController.togglePartExpand(key, currentValue)
    }

    // ── File references (Phase 1B / scheme A) ───────────────────────────────

    /** §1B (F.4): add a file reference; mirrors [ComposerController.addFileReference]. */
    fun addFileReference(path: String) {
        composerController.addFileReference(path)
    }

    /** §1B (F.4): remove a file reference by its stable id; mirrors
     *  [ComposerController.removeFileReference]. */
    fun removeFileReference(id: String) {
        composerController.removeFileReference(id)
    }
}
