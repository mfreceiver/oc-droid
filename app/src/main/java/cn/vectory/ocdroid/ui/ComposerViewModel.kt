package cn.vectory.ocdroid.ui

import androidx.lifecycle.ViewModel
import cn.vectory.ocdroid.data.model.ComposerImageAttachment
import cn.vectory.ocdroid.data.model.Message
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
 */
@HiltViewModel
class ComposerViewModel @Inject constructor(
    internal val core: AppCore,
) : ViewModel() {

    val composerFlow get() = core.composerFlow
    val settingsFlow get() = core.settingsFlow
    val chatFlow get() = core.chatFlow

    fun setInputText(text: String) {
        core.composerController.setInputText(text)
    }

    fun addImageAttachments(attachments: List<ComposerImageAttachment>) {
        core.composerController.addImageAttachments(attachments)
    }

    fun removeImageAttachment(id: String) {
        core.composerController.removeImageAttachment(id)
    }

    fun clearDraftIfActive() {
        core.composerController.clearDraftIfActive()
    }

    fun selectAgent(agentName: String) {
        // §R-17 batch3d: body moved verbatim from AppCore.
        core.settingsManager.selectedAgentName = agentName
        core.writeSettings { it.copy(selectedAgentName = agentName) }
        core.store.chatFlow.value.currentSessionId?.let { core.settingsManager.setAgentForSession(it, agentName) }
    }

    fun toggleModelDisabled(providerId: String, modelId: String) {
        // §R-17 batch3d: body moved verbatim from AppCore.
        val baseUrl = core.hostProfileStore.currentProfile().serverUrl
        val key = "$providerId/$modelId"
        val currentlyDisabled = key in core.store.settingsFlow.value.disabledModels
        core.settingsManager.setModelDisabled(baseUrl, providerId, modelId, disabled = !currentlyDisabled)
        core.writeSettings {
            it.copy(disabledModels = if (currentlyDisabled) it.disabledModels - key else it.disabledModels + key)
        }
    }

    fun switchSessionModel(providerId: String, modelId: String) {
        // §R-17 batch3d: body moved verbatim from AppCore.
        val sessionId = core.store.chatFlow.value.currentSessionId
        val model = Message.ModelInfo(providerId = providerId, modelId = modelId)
        sessionId?.let { core.settingsManager.setModelForSession(it, providerId, modelId) }
        core.writeChat { it.copy(currentModel = model) }
    }

    fun reloadDisabledModelsForCurrentHost() {
        // §R-17 batch3d: body extracted as applyReloadDisabledModelsForCurrentHost
        // so both this VM and AppCore's effect-dispatch handler share the impl.
        applyReloadDisabledModelsForCurrentHost(
            settingsManager = core.settingsManager,
            hostProfileStore = core.hostProfileStore,
            slices = core.store.slices,
        )
    }

    fun togglePartExpand(key: String, currentValue: Boolean) {
        core.composerController.togglePartExpand(key, currentValue)
    }
}
