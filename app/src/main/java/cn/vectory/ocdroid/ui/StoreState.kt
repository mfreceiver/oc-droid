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
    /**
     * §breathing-indicator (item ①): OBSERVABLE SSE-transport-up signal. `true`
     * iff the live [cn.vectory.ocdroid.service.streaming.ServiceSseConnectionOwner]
     * collector has proven transport delivery with at least one valid current-
     * identity frame (readiness) AND has not since torn down (intentional
     * disconnect / reconfigure supersession / terminal exhaustion / service
     * destruction).
     *
     * This is a SEPARATE axis from [ConnectionState.isConnected]:
     *  - `isConnected` reflects HEALTH-SETTLE (ConnectionHealthProbe writes
     *    it on the committed REST baseline) and has ILLEGAL phase combos
     *    (AppStateSlices kdoc) — it MUST NOT be overloaded with transport-up
     *    semantics.
     *  - `isSseConnected` reflects TRANSPORT delivery (a frame reached the
     *    owner) and flips independently: it goes false during the inter-retry
     *    gap + on every closing path even while `isConnected` may still read
     *    true (a transient SSE outage is not yet a health failure).
     *
     * Process-lifetime: written ONLY by the owner via a generation-checked
     * helper (stale collectors cannot flip it). Stored on the aggregate (not a
     * new slice) so it SURVIVES service recreation — the owner is service-
     * instance-scoped + nullable, but the store is `@Singleton`, so the UI
     * observes the same field across a `SessionStreamingService` teardown +
     * rebuild.
     */
    val isSseConnected: Boolean = false,
    /**
     * §breathing-indicator (item ①, TOCTOU fix): the transport generation that
     * last wrote [isSseConnected]. The CAS in
     * [SharedStateStore.mutateSseConnected] accepts a write ONLY IF its
     * candidate generation is `>=` this stamp (monotonic — newest generation
     * wins). This collapses the owner's check-then-write into a SINGLE atomic
     * CAS, closing the TOCTOU window where a stale collector could pass the
     * generation check and then commit `isSseConnected=true` AFTER a concurrent
     * disconnect/reconfigure bumped the transport generation.
     *
     * Generation-transition teardowns (reconfigure supersession / disconnect /
     * shutdown) stamp the BUMPED (new) generation, so a stale collector
     * carrying the OLD generation is rejected even if it races the teardown.
     * Same-generation teardowns (outage / exhaustion) stamp the collector's own
     * generation so a recovered frame (same gen) can re-assert true.
     */
    val sseConnectedGeneration: Long = 0L,
) {
    companion object {
        /** Factory matching the pre-B1 per-slice `MutableStateFlow(XxxState())`
         *  initial values — every slice starts at its data-class default. */
        fun initial(): StoreState = StoreState()
    }
}
