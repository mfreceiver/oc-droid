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
data class ComposerState(
    val inputText: String = "",
    val imageAttachments: List<ComposerImageAttachment> = emptyList(),
    val sendingSessionIds: Set<String> = emptySet(),
    val draftWorkdir: String? = null,
)
