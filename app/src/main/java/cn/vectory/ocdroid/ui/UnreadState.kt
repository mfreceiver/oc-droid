package cn.vectory.ocdroid.ui

/**
 * §R-17 M4: unread-domain state slice (RFC §2.7). Authoritative storage lives
 * in [MainViewModel._unreadFlow]. Drives the unread badge; depends on session
 * status + chat currentSessionId + foreground (cross-domain, see RFC §4).
 *
 * §unread-soak: the single [unreadSessions] set is the source of truth for
 * ALL unread indicators (bottom badge, Sessions cards, picker dots, tab strip,
 * files card). Its POPULATION logic changed — a root now becomes unread ONLY
 * when (a) the root AND ALL its descendants are idle, (b) that all-idle state
 * has persisted ≥[UNREAD_SOAK_MS], (c) the root is not the currently-open
 * session, AND (d) the user has not viewed the root since it went idle. UIs
 * keep reading [unreadSessions] as before; only the producer changed (the
 * [cn.vectory.ocdroid.ui.controller.UnreadSoakController] sweep + the pure
 * [cn.vectory.ocdroid.ui.controller.evaluateUnread] evaluator).
 */
data class UnreadState(
    val unreadSessions: Set<String> = emptySet(),
    /**
     * §incremental-unread: baseline epoch-ms captured at process boot; evaluator
     * treats content older than this as already-read (incremental semantics —
     * only track new messages within THIS run). Client-clock domain (same as
     * [lastViewedTime]). 0L default = tests/preview opt-in — the real value is
     * stamped once at process start by
     * [cn.vectory.ocdroid.ui.controller.UnreadSoakController]'s init block
     * (kept out of [StoreState.initial] so it stays `StoreState()` and
     * ownership/byte-equality tests are not perturbed).
     */
    val bootTimestamp: Long = 0L,
    val lastViewedTime: Map<String, Long> = emptyMap(),
    /**
     * §unread-soak: rootId → epoch-ms when it first entered the all-idle state
     * in the current soak cycle. Drives [evaluateUnread]'s ≥[UNREAD_SOAK_MS]
     * soak gate. Cleared on busy (root or any descendant) → resets the soak;
     * retained after completion/current viewing as edge memory, and cleared
     * by the next busy/unknown transition (then re-soaks on busy→idle).
     * Set/cleared by [cn.vectory.ocdroid.ui.controller.UnreadSoakController]
     * via [cn.vectory.ocdroid.ui.controller.evaluateUnread].
     */
    val idleSince: Map<String, Long> = emptyMap()
)
