package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part

/**
 * §Per-session message cache: a snapshot of the loaded window for a single
 * session — the four pieces of message-state that the session-switch flow
 * (inlined in [SessionSwitcher.switchTo]) otherwise wipes to empty on every
 * switch. Restoring from this snapshot on return avoids the visible flicker +
 * history re-fetch that previously hit users on every A→B→A hop.
 *
 * remove-message-persistence Task 6: relocated back to `ui.controller`
 * alongside its sole runtime consumer ([SessionSwitcher]'s in-process LRU).
 * The prior `data.cache.contract` home existed only to break a
 * `ui → data.cache → ui` dependency ring that the SQLite cache owned; with
 * the persistent cache deleted the ring is gone and the type belongs with
 * its consumer. Field types ([Message] / [Part]) are domain model types —
 * no UI coupling.
 */
data class CachedSessionWindow(
    val messages: List<Message>,
    val partsByMessage: Map<String, List<Part>>,
    val olderMessagesCursor: String?,
    val hasMoreMessages: Boolean
)
