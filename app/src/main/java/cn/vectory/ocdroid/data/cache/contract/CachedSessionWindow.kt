package cn.vectory.ocdroid.data.cache.contract

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part

/**
 * §Per-session message cache: a snapshot of the loaded window for a single
 * session — the four pieces of message-state that the session-switch flow
 * (inlined in `SessionSwitcher.switchTo()`) otherwise wipes to empty on every
 * switch. Restoring from this snapshot on return avoids the visible flicker +
 * history re-fetch that previously hit users on every A→B→A hop.
 *
 * Moved here from `ui` (Phase 4 architecture: break the
 * `ui → data.cache → ui` dependency ring). Its field types ([Message] /
 * [Part]) are domain model types, so this data-carrier has no UI coupling —
 * the cache layer persists + returns these windows without reaching into the
 * UI layer.
 */
data class CachedSessionWindow(
    val messages: List<Message>,
    val partsByMessage: Map<String, List<Part>>,
    val olderMessagesCursor: String?,
    val hasMoreMessages: Boolean
)
