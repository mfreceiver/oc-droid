package cn.vectory.ocdroid.ui

import kotlinx.coroutines.flow.StateFlow

/**
 * §Per-session message cache: `CachedSessionWindow` lives in
 * `ui.controller` (relocated back from `data.cache.contract` in
 * remove-message-persistence Task 6 — the SQLite cache that owned the
 * data-layer dependency ring is gone). See
 * [cn.vectory.ocdroid.ui.controller.CachedSessionWindow].
 *
 * remove-message-persistence Task 4: the non-contiguous gap mechanism
 * (the chat-slice gap field, the gap-fill coordinator, the gap-detection
 * algorithm, the gap-aware rendering pipeline) was deleted. Catch-up now
 * always merges the fetched window (no divider / backfill); manual "load
 * more" paging covers older history. See
 * `docs/features/persistent-chat-cache-plan.md` for the original plan
 * (now superseded by the persistence-removal sequence).
 */

/**
 * §R-17 batch2 → §R18 Phase 4 (P0-9): bundle view over the nine domain slices.
 * Passed to Actions free functions and controllers.
 *
 * Originally a `data class` holding the nine `MutableStateFlow`s directly so
 * free helpers could `.update { }` them. P0-9 write convergence moved every
 * `MutableStateFlow` behind [SharedStateStore]'s private field + public
 * [SharedStateStore.mutateXxx] helper; this bundle now exposes the matching
 * read-only [StateFlow] views + per-slice [mutateXxx] write funnels that
 * delegate to the store. Callers that used `slices.mutateChat { ... }` now
 * use `slices.mutateChat { ... }`; reads (`slices.chat.value`) are unchanged.
 *
 * `internal` constructor pins creation to [SharedStateStore] so the bundle
 * cannot be assembled against foreign flows.
 *
 * §R-17 batch2 step e final: all writes via the per-slice `mutateXxx` helpers
 * (CAS) MUST run on Dispatchers.Main.immediate (caller convention) to
 * preserve cross-slice consistency within a single frame.
 */
class SliceFlows internal constructor(internal val store: SharedStateStore) {
    val connection: StateFlow<ConnectionState> get() = store.connectionFlow
    val traffic: StateFlow<TrafficState> get() = store.trafficFlow
    val composer: StateFlow<ComposerState> get() = store.composerFlow
    val file: StateFlow<FileState> get() = store.fileFlow
    val settings: StateFlow<SettingsState> get() = store.settingsFlow
    val chat: StateFlow<ChatState> get() = store.chatFlow

    /** §history-load-fix: per-session message-mutation lock (see
     *  [SharedStateStore.messageLoadCoordinator]). */
    val messageLoadCoordinator: MessageLoadCoordinator get() = store.messageLoadCoordinator
    val sessionList: StateFlow<SessionListState> get() = store.sessionListFlow
    val unread: StateFlow<UnreadState> get() = store.unreadFlow
    val host: StateFlow<HostState> get() = store.hostFlow

    /**
     * §breathing-indicator / P0-1 liveness: the SSE-transport-up signal
     * (StoreState.isSseConnected). True iff the live
     * ServiceSseConnectionOwner collector has proven transport delivery with
     * at least one valid current-identity frame AND has not since torn down.
     * This is the TRANSPORT-DELIVERY axis — independent of connectionPhase
     * (health-settle): it goes false during the inter-retry gap + on every
     * closing path even while phase may read Reconnecting/ReconnectingAttempt.
     * The StatusPollOrchestrator SWEEP short-circuit uses this to decide
     * whether the digest relay is effectively delivering status (→ no-op) or
     * the sweep must fall through to REST. Read synchronously off the
     * aggregate (lag-free), safe to call on the sweep entry path before any
     * epoch bump.
     */
    val sseConnected: Boolean get() = store.stateFlow.value.isSseConnected

    /**
     * Captures the minted token for the currently active parameterized chat
     * route. A bare `chat` selection deliberately returns `0L`: zero is the
     * legacy compatibility scope, never a reusable route identity.
     */
    internal fun routeInstanceFor(sessionId: String): Long {
        val snapshot = store.stateFlow.value
        return if (
            // Chat detail ids are URL-safe branded ids, so the route string
            // comparison avoids reparsing/decode work on every token frame.
            snapshot.nav.lastRoute == "chat/$sessionId" &&
            snapshot.chat.currentSessionId == sessionId
        ) {
            snapshot.chatRouteInstance
        } else {
            0L
        }
    }

    fun mutateConnection(transform: (ConnectionState) -> ConnectionState) = store.mutateConnection(transform)
    fun mutateTraffic(transform: (TrafficState) -> TrafficState) = store.mutateTraffic(transform)
    fun mutateComposer(transform: (ComposerState) -> ComposerState) = store.mutateComposer(transform)
    fun mutateFile(transform: (FileState) -> FileState) = store.mutateFile(transform)
    fun mutateSettings(transform: (SettingsState) -> SettingsState) = store.mutateSettings(transform)
    fun mutateChat(transform: (ChatState) -> ChatState) = store.mutateChat(transform)
    fun mutateSessionList(transform: (SessionListState) -> SessionListState) = store.mutateSessionList(transform)
    fun mutateUnread(transform: (UnreadState) -> UnreadState) = store.mutateUnread(transform)
    fun mutateHost(transform: (HostState) -> HostState) = store.mutateHost(transform)
}
