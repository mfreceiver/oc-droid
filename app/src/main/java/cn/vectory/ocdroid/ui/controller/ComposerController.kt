package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.ComposerImageAttachment
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.ComposerState
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.util.SettingsManager

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
 *
 * R-20 Phase 5: [hostProfileStore] injected so draft writes can derive the
 * current serverGroupFp (composite `(fp, sessionId)` key — see
 * [SettingsManager.setDraftText]).
 */
class ComposerController(
    private val store: SharedStateStore,
    private val settingsManager: SettingsManager,
    private val hostProfileStore: HostProfileStore,
) {
    /**
     * §R-17 M3→M5→batch2: writes the composer slice only (slice is the
     * authoritative read path). The deprecated AppState mirror write was
     * removed in R-17 batch2 sub-step d (Fixer C).
     *
     * §R18 Phase 4 (P0-9): funnels through [SharedStateStore.mutateComposer].
     */
    private fun writeComposer(transform: (ComposerState) -> ComposerState) {
        store.mutateComposer(transform)
    }

    fun setInputText(text: String) {
        writeComposer { it.copy(inputText = text) }
        // R-20 Phase 5: per-(fp, sessionId) composite key — the draft is
        // scoped to the current host group + the active session.
        val fp = hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id }
        store.chatFlow.value.currentSessionId?.let { settingsManager.setDraftText(fp, it, text) }
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
        if (store.composerFlow.value.draftWorkdir == null || store.chatFlow.value.currentSessionId != null) return
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
        // §R18 Phase 3 Wave 1 (C-2): .value = → .update { } (CAS, atomic).
        // §R18 Phase 4 (P0-9): write via SharedStateStore.mutateExpandedParts.
        store.mutateExpandedParts { it + (key to !currentValue) }
    }

    /** Reset the collapsible-card expansion state (e.g. on session switch). */
    fun clearExpandedParts() {
        // §R18 Phase 3 Wave 1 (C-2): .value = → .update { } (CAS, atomic).
        // §R18 Phase 4 (P0-9): write via SharedStateStore.mutateExpandedParts.
        store.mutateExpandedParts { emptyMap() }
    }
}
