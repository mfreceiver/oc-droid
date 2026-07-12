package cn.vectory.ocdroid.ui

/**
 * §A5-3 Phase B1: the SINGLE authoritative composite aggregate backing every
 * slice in [SharedStateStore]. Replaces the previous N private
 * `MutableStateFlow<XxxState>` fields (9 domain slices + expandedParts + nav)
 * with one `MutableStateFlow<StoreState>`. Per-slice reads/writes now project
 * over this single source-of-truth so the cross-slice atomicity foundation
 * (one committed state per dispatcher tick) that B2 will exploit is in place.
 *
 * Field set + types mirror the pre-B1 per-slice `MutableStateFlow`s 1:1 so
 * [StoreState.initial] reproduces the exact starting values each slice had as
 * its own flow (`ConnectionState()` / `TrafficState()` / … / `emptyMap()` /
 * `NavState()`).
 *
 * Behavior-preservation contract (the B1 gate): every per-slice projection
 * ([SharedStateStore.connectionFlow] / [SharedStateStore.chatFlow] / etc.) is
 * LAG-FREE — `DerivedStateFlow.value` reads synchronously from this aggregate,
 * so a post-mutation read on the same dispatcher tick observes the new value
 * exactly as it did when each slice had its own `MutableStateFlow`. Collectors
 * see distinct selector results only. No existing test should need to change.
 *
 * NOTE: this is purely a store-internal mechanical refactor. It does NOT
 * introduce `AppAction` / a reducer / migrate any multi-write site — that is
 * B2. B1 only collapses the storage + adds lag-free projections so B2 has a
 * single committed aggregate to atomically transition.
 */
data class StoreState(
    val connection: ConnectionState = ConnectionState(),
    val traffic: TrafficState = TrafficState(),
    val composer: ComposerState = ComposerState(),
    val file: FileState = FileState(),
    val settings: SettingsState = SettingsState(),
    val chat: ChatState = ChatState(),
    val sessionList: SessionListState = SessionListState(),
    val unread: UnreadState = UnreadState(),
    val host: HostState = HostState(),
    /** §A5-3 B1: collapsible-card expansion map (formerly its own
     *  `MutableStateFlow<Map<String, Boolean>>`). */
    val expandedParts: Map<String, Boolean> = emptyMap(),
    /** §A5-3 B1: nav slice (formerly its own `MutableStateFlow<NavState>`).
     *  Seeded by OrchestratorVM. */
    val nav: NavState = NavState(),
) {
    companion object {
        /** Factory matching the pre-B1 per-slice `MutableStateFlow(XxxState())`
         *  initial values — every slice starts at its data-class default. */
        fun initial(): StoreState = StoreState()
    }
}
