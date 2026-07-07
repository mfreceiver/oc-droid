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

    fun selectAgent(agentName: String) {
        // §R-17 batch3d: body moved verbatim from AppCore.
        settingsManager.selectedAgentName = agentName
        store.mutateSettings { it.copy(selectedAgentName = agentName) }
        // R-20 Phase 5: per-(fp, sessionId) composite key — the agent override
        // is scoped to the current host group + the active session.
        val fp = hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id }
        store.chatFlow.value.currentSessionId?.let { settingsManager.setAgentForSession(fp, it, agentName) }
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

    fun switchSessionModel(providerId: String, modelId: String) {
        // §R-17 batch3d: body moved verbatim from AppCore.
        // R-20 Phase 5: per-(fp, sessionId) composite key.
        val fp = hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id }
        val sessionId = store.chatFlow.value.currentSessionId
        val model = Message.ModelInfo(providerId = providerId, modelId = modelId)
        sessionId?.let { settingsManager.setModelForSession(fp, it, providerId, modelId) }
        store.mutateChat { it.copy(currentModel = model) }
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
}
