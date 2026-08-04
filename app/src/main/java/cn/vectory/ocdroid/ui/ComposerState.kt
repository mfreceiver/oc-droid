package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.ComposerImageAttachment

/**
 * §R-17 batch2: composer-domain state slice. Authoritative storage; writes
 * via _composerFlow.update. Field set strictly follows RFC R-17 §2.5.
 *
 * This is the highest-frequency slice (`inputText` mutates on every keystroke)
 * and the primary reason the slice exists: consumers subscribe to
 * `composerFlow` directly, so keystrokes no longer recompose ChatTopBar.
 *
 * Write atomicity (RFC §4 strategy A): same model as [ConnectionState] —
 * every mutation goes through a single `writeComposer { ... }`. No dispatcher
 * batch reliance (RFC §9.2).
 */
/**
 * §1B (F.4): a single file reference attached to the composer — renders as a
 * removable `InputChip` above the input row and serialises downstream as a
 * `PartInput(type=text)` carrying the literal `File: <path>` payload (scheme
 * A — zero protocol change). `id` is a stable key so chip-removal can find
 * its way back to the right list entry even when paths repeat.
 */
data class ComposerFileReference(
    val path: String,
    val id: String = java.util.UUID.randomUUID().toString()
)

data class ComposerState(
    val inputText: String = "",
    val imageAttachments: List<ComposerImageAttachment> = emptyList(),
    val sendingSessionIds: Set<String> = emptySet(),
    val draftWorkdir: String? = null,
    /**
     * §1B (F.4): file references attached to the composer (Phase 1B renders
     * the chip strip; the writer-side add/remove lives on
     * [cn.vectory.ocdroid.ui.controller.ComposerController]; the Add-menu
     * "Reference file" entry is still a Phase 2 deliverable so the
     * list stays empty in normal flow until then). Additive — no existing
     * writer reads it yet.
     */
    val fileReferences: List<ComposerFileReference> = emptyList()
)
