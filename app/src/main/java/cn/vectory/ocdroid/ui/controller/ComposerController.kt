package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.ComposerImageAttachment
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.ComposerState
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * R-16 M2 → R-17 batch3b: owns the composer-domain state mutation logic.
 *
 * **Migration (batch 3b)**: the [ComposerCallbacks] interface was eliminated
 * (rule A — both methods could be served by [SettingsManager] directly). The
 * controller now injects [settingsManager] and writes through it instead of
 * routing the side effect back up to the orchestrator.
 *
 * Moved from MainViewModel:
 *  - setInputText / addImageAttachments / removeImageAttachment
 *  - clearDraftIfActive
 *  - togglePartExpand / clearExpandedParts
 *
 * The MutableStateFlows stay declared in the orchestrator (they are
 * referenced by the R-17 [cn.vectory.ocdroid.ui.SliceFlows] container and the
 * public `composerFlow` / `expandedParts` pass-throughs). This controller
 * receives them by reference and is the single writer — ownership is logical
 * (only this class mutates these flows) rather than physical.
 *
 * The complex send/edit path (sendMessage / dispatchSendMessage /
 * editFromMessage / executeCommand / selectAgent) stays in the orchestrator —
 * each orchestrates across session creation, message loading, and cross-slice
 * state, so pulling them in would just turn every helper they call into a
 * callback.
 *
 * `writeComposer` mirrors the orchestrator's M3 helper: writes the composer
 * slice via `composerFlow.update` so subscribers see a consistent value (RFC
 * R-17 §4 strategy A, §9.2 — no dispatcher batch reliance).
 */
internal class ComposerController(
    private val composerFlow: MutableStateFlow<ComposerState>,
    private val chatFlow: MutableStateFlow<ChatState>,
    private val expandedParts: MutableStateFlow<Map<String, Boolean>>,
    private val settingsManager: SettingsManager,
) {
    /**
     * §R-17 M3→M5→batch2: writes the composer slice only (slice is the
     * authoritative read path). The deprecated AppState mirror write was
     * removed in R-17 batch2 sub-step d (Fixer C).
     */
    private fun writeComposer(transform: (ComposerState) -> ComposerState) {
        composerFlow.update(transform)
    }

    fun setInputText(text: String) {
        writeComposer { it.copy(inputText = text) }
        chatFlow.value.currentSessionId?.let { settingsManager.setDraftText(it, text) }
    }

    fun addImageAttachments(attachments: List<ComposerImageAttachment>) {
        if (attachments.isEmpty()) return
        writeComposer { c -> c.copy(imageAttachments = (c.imageAttachments + attachments).take(4)) }
    }

    fun removeImageAttachment(id: String) {
        writeComposer { c -> c.copy(imageAttachments = c.imageAttachments.filterNot { it.id == id }) }
    }

    /**
     * Drops an in-progress draft (draftWorkdir set + no concrete session yet).
     * Clears the persisted workdir via [settingsManager] so a discarded draft
     * doesn't leave the repository re-scoped to an abandoned project on
     * resume/cold start. No-op when there is no active draft.
     */
    fun clearDraftIfActive() {
        if (composerFlow.value.draftWorkdir == null || chatFlow.value.currentSessionId != null) return
        settingsManager.currentWorkdir = null
        writeComposer {
            it.copy(draftWorkdir = null, inputText = "", imageAttachments = emptyList())
        }
    }

    /**
     * currentValue is the card's *actually displayed* expansion state
     * (expandedParts[key] ?: per-card-default), supplied by the caller. The
     * previous signature read current[key] ?: false here, which mismatched the
     * cards' display default (running tool default=true): the first tap on a
     * default-expanded card wrote true → no visible change (#13/#16). Passing
     * the displayed value makes the toggle always invert what the user sees.
     */
    fun togglePartExpand(key: String, currentValue: Boolean) {
        expandedParts.value = expandedParts.value + (key to !currentValue)
    }

    /** Reset the collapsible-card expansion state (e.g. on session switch). */
    fun clearExpandedParts() {
        expandedParts.value = emptyMap()
    }
}
