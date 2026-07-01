package com.yage.opencode_client.ui.controller

import android.os.Looper
import com.yage.opencode_client.data.model.ComposerImageAttachment
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.ChatState
import com.yage.opencode_client.ui.ComposerState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * R-16 M2: callbacks the [ComposerController] invokes back into MainViewModel
 * for SettingsManager side effects (draft persistence, workdir clearance).
 * Defined as an interface rather than direct injection so the controller never
 * holds a reference to MainViewModel or SettingsManager — avoiding the
 * circular dependency flagged in R-16 §7.3.
 *
 * Follows the [ForegroundCatchUpCallbacks] pattern from M1.
 */
interface ComposerCallbacks {
    /** Persists the draft text for [sessionId] to SettingsManager. */
    fun saveDraft(sessionId: String, text: String)

    /** Clears the persisted workdir (SettingsManager.currentWorkdir = null). */
    fun clearPersistedWorkdir()
}

/**
 * R-16 M2: owns the composer-domain state mutation logic.
 *
 * Moved from MainViewModel:
 *  - setInputText / addImageAttachments / removeImageAttachment
 *  - clearDraftIfActive
 *  - togglePartExpand / clearExpandedParts
 *
 * The MutableStateFlows stay declared in MainViewModel (they are referenced
 * by the R-17 [SliceFlows] container and the public `composerFlow` /
 * `expandedParts` pass-throughs). This controller receives them by reference
 * and is the single writer — ownership is logical (only this class mutates
 * these flows) rather than physical.
 *
 * The complex send/edit path (sendMessage / dispatchSendMessage /
 * editFromMessage / executeCommand / selectAgent) stays in MainViewModel —
 * each orchestrates across session creation, message loading, and
 * cross-slice state, so pulling them in would just turn every helper they
 * call into a callback. A follow-up can migrate them once a SessionSwitcher
 * owns the session-list slice.
 *
 * `writeComposer` mirrors MainViewModel's M3 helper: writes the slice AND
 * the deprecated AppState fields in one synchronous call so `state.value`
 * stays consistent (RFC R-17 §4 strategy A, §9.2 — no dispatcher batch
 * reliance).
 */
internal class ComposerController(
    private val state: MutableStateFlow<AppState>,
    private val composerFlow: MutableStateFlow<ComposerState>,
    private val chatFlow: MutableStateFlow<ChatState>,
    private val expandedParts: MutableStateFlow<Map<String, Boolean>>,
    private val callbacks: ComposerCallbacks
) {
    /**
     * §R-17 M3→M5: write the composer slice AND mirror it onto AppState. The
     * slice is the authoritative read path; the mirror write is retained so the
     * free helpers that read `state.value.draftWorkdir` see consistent values.
     */
    @Suppress("DEPRECATION")
    private fun writeComposer(transform: (ComposerState) -> ComposerState) {
        check(Looper.myLooper() === Looper.getMainLooper()) { "writeComposer must be called on the main thread" }
        val next = transform(composerFlow.value)
        composerFlow.value = next
        state.value = state.value.copy(
            inputText = next.inputText,
            imageAttachments = next.imageAttachments,
            sendingSessionIds = next.sendingSessionIds,
            draftWorkdir = next.draftWorkdir
        )
    }

    fun setInputText(text: String) {
        writeComposer { it.copy(inputText = text) }
        chatFlow.value.currentSessionId?.let { callbacks.saveDraft(it, text) }
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
     * Clears the persisted workdir via callback so a discarded draft doesn't
     * leave the repository re-scoped to an abandoned project on resume/cold
     * start. No-op when there is no active draft.
     */
    fun clearDraftIfActive() {
        if (composerFlow.value.draftWorkdir == null || chatFlow.value.currentSessionId != null) return
        callbacks.clearPersistedWorkdir()
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
