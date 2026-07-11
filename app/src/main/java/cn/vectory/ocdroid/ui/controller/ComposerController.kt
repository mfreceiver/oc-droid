package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.ComposerImageAttachment
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.ComposerFileReference
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
     *
     * §1B-FIX (I4): also clears [ComposerState.fileReferences] + strips the
     * matching `File: <path>` text lines from `inputText` so a discarded
     * draft does not leave dangling file-reference chips for the next
     * session / draft.
     */
    fun clearDraftIfActive() {
        if (store.composerFlow.value.draftWorkdir == null || store.chatFlow.value.currentSessionId != null) return
        settingsManager.currentWorkdir = null
        writeComposer {
            it.copy(
                draftWorkdir = null,
                inputText = stripAllFileRefLines(it.inputText),
                imageAttachments = emptyList(),
                fileReferences = emptyList(),
            )
        }
    }

    /**
     * §1B-FIX (I4): reset the composer's per-session content (input text +
     * image attachments + file references + draft workdir). Used by
     * session-switch / draft-create / send-success / revert-success paths
     * so file-reference chips do not leak across sessions.
     *
     * Returns the new [ComposerState] so callers can chain further writes
     * (e.g. `clearComposer().copy(draftWorkdir = workdir)` for the
     * createSessionInWorkdir path). The caller is expected to invoke this
     * inside a [SharedStateStore.mutateComposer] lambda for atomic writes.
     */
    fun clearedComposerForSessionSwitch(
        keepDraftWorkdir: Boolean = false,
    ): (ComposerState) -> ComposerState = transform@{ c ->
        val cleared = c.copy(
            inputText = stripAllFileRefLines(c.inputText),
            imageAttachments = emptyList(),
            fileReferences = emptyList(),
        )
        if (keepDraftWorkdir) cleared else cleared.copy(draftWorkdir = null)
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

    // ── File references (Phase 1B / scheme A) ───────────────────────────────

    /**
     * §1B (F.4): attach a file reference to the composer. Renders as a
     * removable chip above the input row; the actual outgoing payload is a
     * `PartInput(type=text)` carrying the literal `File: <path>` text (scheme
     * A — zero protocol change), so we also append a `File: <path>` line to
     * [ComposerState.inputText] when one is not already present for that
     * path. Pure addition — does not touch any other writer.
     *
     * §1B-FIX (I4): the de-dup checks BOTH `c.fileReferences` (the chip
     * list) AND the existing `File: <path>` text lines in `inputText`
     * (which the user may have typed by hand). This prevents duplicate
     * "File: /a/b.kt" lines when the user adds the same reference twice
     * via the chip or types it manually.
     */
    fun addFileReference(path: String) {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return
        writeComposer { c ->
            val alreadyInList = c.fileReferences.any { it.path == trimmed }
            val alreadyInText = inputTextContainsFileRef(c.inputText, trimmed)
            when {
                alreadyInList && alreadyInText -> c
                alreadyInList -> c.copy(inputText = appendFileRefText(c.inputText, trimmed))
                alreadyInText -> c.copy(fileReferences = c.fileReferences + ComposerFileReference(path = trimmed))
                else -> c.copy(
                    fileReferences = c.fileReferences + ComposerFileReference(path = trimmed),
                    inputText = appendFileRefText(c.inputText, trimmed),
                )
            }
        }
        // R-20 Phase 5: per-(fp, sessionId) composite key for draft persistence
        // — mirrors setInputText; the new text (with the appended "File: <path>"
        // line) is what gets stored.
        val fp = hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id }
        store.chatFlow.value.currentSessionId?.let { settingsManager.setDraftText(fp, it, store.composerFlow.value.inputText) }
    }

    /**
     * §1B (F.4): remove a file reference by its stable chip id, and strip the
     * matching `File: <path>` line from [ComposerState.inputText] so the
     * outgoing prompt does not still reference the removed file. No-op when
     * the id is unknown. Pure addition — does not touch any other writer.
     *
     * §1B-FIX (I4): if the user manually typed duplicate `File: <path>`
     * lines, every matching line is stripped (consistent with the
     * chip-removal intent: the user no longer wants this file referenced).
     */
    fun removeFileReference(id: String) {
        var removedPath: String? = null
        writeComposer { c ->
            val target = c.fileReferences.firstOrNull { it.id == id } ?: return@writeComposer c
            removedPath = target.path
            c.copy(
                fileReferences = c.fileReferences.filterNot { it.id == id },
                inputText = stripFileRefText(c.inputText, target.path),
            )
        }
        if (removedPath != null) {
            val fp = hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id }
            store.chatFlow.value.currentSessionId?.let { settingsManager.setDraftText(fp, it, store.composerFlow.value.inputText) }
        }
    }
}

/** §1B (F.4): the literal payload format for a file reference. Appended as
 *  a single new line to the composer text so the server / downstream sees
 *  `File: <path>` exactly once per reference. Newline-prefixed when the
 *  composer is non-empty to avoid smushing into the prior line. */
private fun appendFileRefText(current: String, path: String): String {
    if (current.isEmpty()) return "File: $path"
    if (current.endsWith("\n")) return "${current}File: $path"
    return "${current}\nFile: $path"
}

/** §1B-FIX (I4): strip EVERY line beginning with `File: <path>` (any leading
 *  whitespace, any line terminator) so removing a chip does not leave a
 *  dangling reference in the outgoing prompt text — including any duplicate
 *  lines the user may have typed by hand. */
private fun stripFileRefText(current: String, path: String): String {
    if (current.isEmpty()) return current
    val needle = "File: $path"
    val lines = current.split('\n')
    val kept = lines.filterNot { line ->
        line.trimStart() == needle
    }
    return kept.joinToString("\n")
}

/** §1B-FIX (I4): strip ALL `File: <path>` lines from the text — used by
 *  session-switch / draft-discard paths so the user never sees phantom
 *  file references in the next session's draft (the `fileReferences` list
 *  is cleared at the same time, so this is the belt-and-braces guard). */
private fun stripAllFileRefLines(current: String): String {
    if (current.isEmpty()) return current
    val lines = current.split('\n')
    val kept = lines.filterNot { line ->
        val trimmed = line.trimStart()
        trimmed.startsWith("File: ") || trimmed == "File:"
    }
    return kept.joinToString("\n")
}

/** §1B-FIX (I4): check whether `current` already contains a `File: <path>`
 *  line — used by [addFileReference] for de-dup against hand-typed text. */
private fun inputTextContainsFileRef(current: String, path: String): Boolean {
    if (current.isEmpty()) return false
    val needle = "File: $path"
    return current.split('\n').any { it.trimStart() == needle }
}
