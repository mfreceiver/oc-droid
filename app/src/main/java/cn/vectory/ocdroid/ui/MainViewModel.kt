package cn.vectory.ocdroid.ui

import android.os.Looper
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.ocdroid.data.api.CommandInfo
import cn.vectory.ocdroid.data.model.*
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.di.AppLifecycleMonitor
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.ThemeMode
import cn.vectory.ocdroid.util.TrafficTracker
import cn.vectory.ocdroid.util.MarkdownFontSizes
import cn.vectory.ocdroid.util.runSuspendCatching
import cn.vectory.ocdroid.ui.controller.ComposerCallbacks
import cn.vectory.ocdroid.ui.controller.ComposerController
import cn.vectory.ocdroid.ui.controller.ConnectionCoordinator
import cn.vectory.ocdroid.ui.controller.ConnectionCoordinatorCallbacks
import cn.vectory.ocdroid.ui.controller.ForegroundCatchUpCallbacks
import cn.vectory.ocdroid.ui.controller.ForegroundCatchUpController
import cn.vectory.ocdroid.ui.controller.HostProfileCallbacks
import cn.vectory.ocdroid.ui.controller.HostProfileController
import cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator
import cn.vectory.ocdroid.ui.controller.SessionSyncCoordinatorCallbacks
import cn.vectory.ocdroid.ui.controller.SessionSwitcher
import cn.vectory.ocdroid.ui.controller.SessionSwitcherCallbacks
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

/** Success message shown on successful tunnel activation. HostProfileController
 *  writes this into `AppState.successMessage` (NOT `error`) so ChatScreen's
 *  success-snackbar branch renders it as a positive toast. The sticky
 *  `tunnelActivationState` (Success) separately drives the ServerManagementDialog
 *  indicator. Kept as a named constant so the success-toast text and the
 *  dialog state stay decoupled. */
const val TUNNEL_SUCCESS_TOAST = "隧道激活成功"

@HiltViewModel
@OptIn(FlowPreview::class)
class MainViewModel @Inject constructor(
    internal val repository: OpenCodeRepository,
    internal val settingsManager: SettingsManager,
    private val hostProfileStore: HostProfileStore,
    internal val trafficTracker: TrafficTracker,
    private val appLifecycleMonitor: AppLifecycleMonitor,
    private val serverCompatProfile: cn.vectory.ocdroid.data.repository.ServerCompatProfile
) : ViewModel(), ForegroundCatchUpCallbacks, ComposerCallbacks, SessionSwitcherCallbacks, HostProfileCallbacks, ConnectionCoordinatorCallbacks, SessionSyncCoordinatorCallbacks {

    // §R-17 M2: _state keeps carrying every AppState field (the connection/
    // traffic ones are deprecated mirrors). The authoritative storage for
    // the connection/traffic domains lives in _connectionFlow / _trafficFlow
    // (independent MutableStateFlows so M4 consumers can subscribe to them
    // directly and skip the AppState-level recompositions). `state` stays a
    // synchronous view over `_state` (NOT a combine()/stateIn chain) so that
    // every `_state` / slice write is immediately observable on
    // `state.value` — this preserves the synchronous read-after-write
    // semantics the existing test-suite (and a few non-coroutine call sites)
    // rely on. combine()+stateIn() would propagate changes asynchronously
    // (only advanced on the test dispatcher) and broke ~60 tests that read
    // `state.value` right after `updateState { ... }`. The slices are kept
    // in sync with the mirrors via the [writeConnection] / [writeTraffic]
    // helpers, which mutate both atomically from the caller's thread.
    private val _state = MutableStateFlow(AppState())

    /** §R-17 M2: connection-domain slice (RFC §2.1). Authoritative storage. */
    private val _connectionFlow = MutableStateFlow(ConnectionState())
    /** §R-17 M2: traffic-domain slice (RFC §2.9). Authoritative storage. */
    private val _trafficFlow = MutableStateFlow(TrafficState())
    /** §R-17 M3: composer-domain slice (RFC §2.5). Authoritative storage. */
    private val _composerFlow = MutableStateFlow(ComposerState())
    /**
     * Expansion state for collapsible card types (streaming parts, reasoning,
     * tool calls, sub-agent task cards, patch/context cards). Kept in a dedicated StateFlow
     * (NOT inside [AppState]) so toggling a card only recomposes the
     * subscribers of this flow (the individual cards), not the whole
     * ChatScreen. Key format: `"${messageId}|${partKey}"` (constructed by
     * the card layer; this VM does not parse keys).
     *
     * Cleared on session switch via [clearExpandedParts] (called from
     * [selectSession]) so switching conversations resets all cards to their
     * default collapsed state. loadMore / loadMessages deliberately do NOT
     * clear it — the user's in-progress expand state must survive history
     * pagination.
     *
     * Moved up from its previous position (was after _state builder) so it is
     * initialized before [composerController] which receives it by reference
     * (R-16 M2).
     */
    private val _expandedParts = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val expandedParts: StateFlow<Map<String, Boolean>> = _expandedParts.asStateFlow()
    /** §R-17 M3: file-browser slice (RFC §2.6). Authoritative storage. */
    private val _fileFlow = MutableStateFlow(FileState())
    /** §R-17 M3: settings slice (RFC §2.4, minus cross-domain error). Authoritative storage. */
    private val _settingsFlow = MutableStateFlow(SettingsState())
    /** §R-17 M4: chat slice (RFC §2.2). Authoritative storage. */
    private val _chatFlow = MutableStateFlow(ChatState())
    /** §R-17 M4: session-list slice (RFC §2.3). Authoritative storage. */
    private val _sessionListFlow = MutableStateFlow(SessionListState())
    /** §R-17 M4: unread slice (RFC §2.7). Authoritative storage. */
    private val _unreadFlow = MutableStateFlow(UnreadState())
    /** §R-17 M4: host slice (RFC §2.8). Authoritative storage. */
    private val _hostFlow = MutableStateFlow(HostState())

    /** Read-only connection slice for direct subscription (M4 consumers). */
    val connectionFlow: StateFlow<ConnectionState> = _connectionFlow.asStateFlow()
    /** Read-only traffic slice for direct subscription (M4 consumers). */
    val trafficFlow: StateFlow<TrafficState> = _trafficFlow.asStateFlow()
    /** Read-only composer slice for direct subscription (M4 consumers). */
    val composerFlow: StateFlow<ComposerState> = _composerFlow.asStateFlow()
    /** Read-only file slice for direct subscription (M4 consumers). */
    val fileFlow: StateFlow<FileState> = _fileFlow.asStateFlow()
    /** Read-only settings slice for direct subscription (M4 consumers). */
    val settingsFlow: StateFlow<SettingsState> = _settingsFlow.asStateFlow()
    /** Read-only chat slice for direct subscription (M4 consumers). */
    val chatFlow: StateFlow<ChatState> = _chatFlow.asStateFlow()
    /** Read-only session-list slice for direct subscription (M4 consumers). */
    val sessionListFlow: StateFlow<SessionListState> = _sessionListFlow.asStateFlow()
    /** Read-only unread slice for direct subscription (M4 consumers). */
    val unreadFlow: StateFlow<UnreadState> = _unreadFlow.asStateFlow()
    /** Read-only host slice for direct subscription (M4 consumers). */
    val hostFlow: StateFlow<HostState> = _hostFlow.asStateFlow()

    /**
     * §R-17 Stage 1: bundle of all nine slice flows, passed to free helpers
     * (`launch*` / `handle*` / `applySavedSettings`) so their `updateAndSync`
     * calls keep every slice in sync with the AppState mirror. Built once;
     * the underlying MutableStateFlows are the same `_xxxFlow` fields above.
     */
    private val sliceFlows: SliceFlows = SliceFlows(
        connection = _connectionFlow,
        traffic = _trafficFlow,
        composer = _composerFlow,
        file = _fileFlow,
        settings = _settingsFlow,
        chat = _chatFlow,
        sessionList = _sessionListFlow,
        unread = _unreadFlow,
        host = _hostFlow
    )

    /**
     * R-16 M1: foreground/background three-tier catch-up state machine, extracted
     * from MainViewModel. Owns the 5 previously-@Volatile fields + the tier
     * thresholds; side effects (testConnection / cold-start reload / SSE cancel /
     * draft discard / catch-up probe) flow back through [ForegroundCatchUpCallbacks]
     * which MainViewModel implements below — so the controller never holds a
     * MainViewModel reference (R-16 §7.3 circular-dependency avoidance).
     */
    private val foregroundCatchUpController: ForegroundCatchUpController =
        ForegroundCatchUpController(
            appLifecycleMonitor = appLifecycleMonitor,
            scope = viewModelScope,
            callbacks = this,
        )

    /**
     * R-16 M2: composer-domain state mutation controller. Owns the write path
     * for inputText / imageAttachments / draftWorkdir on [_composerFlow], and
     * for collapsible-card expansion state on [_expandedParts]. Side effects
     * (SettingsManager draft/workdir persistence) flow through
     * [ComposerCallbacks] which MainViewModel implements below.
     */
    private val composerController: ComposerController =
        ComposerController(
            state = _state,
            composerFlow = _composerFlow,
            chatFlow = _chatFlow,
            expandedParts = _expandedParts,
            callbacks = this
        )

    /**
     * R-16 M2b: session-switching state machine controller. Owns the
     * [selectSession] flow (8 steps) + the per-session message-window LRU
     * cache. Side effects (SettingsManager draft/openSessionIds persistence,
     * repository directory sync / message / status / child-session loads,
     * sessionCache persistence) flow through [SessionSwitcherCallbacks] which
     * MainViewModel implements below.
     */
    private val sessionSwitcher: SessionSwitcher =
        SessionSwitcher(
            state = _state,
            composerFlow = _composerFlow,
            expandedParts = _expandedParts,
            slices = sliceFlows,
            callbacks = this
        )

    /**
     * R-16 M3: Host Profile CRUD + repository reconfiguration + tunnel
     * activation + full local-data reset controller. Owns all host-profile
     * management logic (selectHostProfile / deleteHostProfile / saveHostProfile
     * / configureServer / configureRepositoryForProfile /
     * activateTunnelForCurrentHost / resetLocalDataAndResync). Side effects
     * (SSE cancel/reconnect, testConnection, sessionWindowCache clear,
     * trafficTracker reset) flow through [HostProfileCallbacks] which
     * MainViewModel implements below.
     */
    private val hostProfileController: HostProfileController =
        HostProfileController(
            scope = viewModelScope,
            state = _state,
            slices = sliceFlows,
            hostProfileStore = hostProfileStore,
            repository = repository,
            settingsManager = settingsManager,
            callbacks = this
        )

    /**
     * R-16 M4: server connection lifecycle controller. Owns the SSE feed Job +
     * the 30s health-check throttle + the testConnection/coldStartReconnect
     * state machine + initial-data load orchestration + startSSE/cancelSse.
     * Side effects (repository reconfigure, initial-data loaders, SSE event
     * dispatch → SessionSyncCoordinator, catch-up reset) flow through
     * [ConnectionCoordinatorCallbacks] which MainViewModel implements below.
     */
    private val connectionCoordinator: ConnectionCoordinator =
        ConnectionCoordinator(
            scope = viewModelScope,
            state = _state,
            connectionFlow = _connectionFlow,
            settingsFlow = _settingsFlow,
            slices = sliceFlows,
            repository = repository,
            settingsManager = settingsManager,
            callbacks = this,
            serverCompatProfile = serverCompatProfile
        )

    /**
     * R-16 M4: SSE event → AppState fold controller. Owns the handleSSEEvent
     * dispatch (server.connected catch-up trigger + the message/session/status/
     * part/permission/question/todo fold). Side effects (authoritative reload,
     * permission refresh, catch-up probe, non-fatal logging) flow through
     * [SessionSyncCoordinatorCallbacks] which MainViewModel implements below.
     */
    private val sessionSyncCoordinator: SessionSyncCoordinator =
        SessionSyncCoordinator(
            scope = viewModelScope,
            state = _state,
            slices = sliceFlows,
            settingsManager = settingsManager,
            callbacks = this
        )

    val state: StateFlow<AppState> = _state.asStateFlow()

    /**
     * §R-17 M2→M5: write the connection slice AND mirror it onto AppState.
     * The slice is the authoritative read path (all consumers read slices); the
     * mirror write is retained so the free helpers in the MainViewModel*Actions
     * files that still read `state.value.<field>` (and CatchUpGapTest) see consistent
     * values without a stale window. A follow-up that migrates those reads to
     * slices can drop the mirror write.
     *
     * §R-17 M5.1 (kimo 🟠#2) THREAD-SAFETY: the slice-write + AppState-mirror-write
     * is only consistent because every call site runs on `Dispatchers.Main.immediate`
     * (single-threaded). The same constraint applies to every `writeXxx` helper
     * (writeTraffic/writeComposer/writeFile/writeSettings/writeChat/writeSessionList/
     * writeUnread/writeHost — "See [writeConnection]") and to [updateState] /
     * `updateAndSync`. MUST be invoked on `Dispatchers.Main.immediate`; a
     * background-thread call would let a concurrent writer interleave between the
     * slice write and the mirror write, breaking slice↔mirror consistency.
     */
    @Suppress("DEPRECATION")
    private fun writeConnection(transform: (ConnectionState) -> ConnectionState) {
        check(Looper.myLooper() === Looper.getMainLooper()) { "writeConnection must be called on the main thread" }
        val next = transform(_connectionFlow.value)
        _connectionFlow.value = next
        _state.value = _state.value.copy(
            isConnected = next.isConnected,
            isConnecting = next.isConnecting,
            serverVersion = next.serverVersion,
            connectionPhase = next.connectionPhase,
            tunnelActivationState = next.tunnelActivationState
        )
    }

    /** §R-17 M2→M5: write the traffic slice + mirror. See [writeConnection]. */
    @Suppress("DEPRECATION")
    private fun writeTraffic(transform: (TrafficState) -> TrafficState) {
        check(Looper.myLooper() === Looper.getMainLooper()) { "writeTraffic must be called on the main thread" }
        val next = transform(_trafficFlow.value)
        _trafficFlow.value = next
        _state.value = _state.value.copy(trafficSent = next.trafficSent, trafficReceived = next.trafficReceived)
    }

    /** §R-17 M3→M5: write the composer slice + mirror. See [writeConnection]. */
    @Suppress("DEPRECATION")
    private fun writeComposer(transform: (ComposerState) -> ComposerState) {
        check(Looper.myLooper() === Looper.getMainLooper()) { "writeComposer must be called on the main thread" }
        val next = transform(_composerFlow.value)
        _composerFlow.value = next
        _state.value = _state.value.copy(
            inputText = next.inputText,
            imageAttachments = next.imageAttachments,
            sendingSessionIds = next.sendingSessionIds,
            draftWorkdir = next.draftWorkdir
        )
    }

    /** §R-17 M3→M5: write the file slice + mirror. See [writeConnection]. */
    @Suppress("DEPRECATION")
    private fun writeFile(transform: (FileState) -> FileState) {
        check(Looper.myLooper() === Looper.getMainLooper()) { "writeFile must be called on the main thread" }
        val next = transform(_fileFlow.value)
        _fileFlow.value = next
        _state.value = _state.value.copy(
            filePathToShowInFiles = next.filePathToShowInFiles,
            filePreviewOriginRoute = next.filePreviewOriginRoute,
            fileBrowserOpen = next.fileBrowserOpen,
            fileBrowserWorkdir = next.fileBrowserWorkdir
        )
    }

    /** §R-17 M3→M5: write the settings slice + mirror. `error` stays on AppState. */
    @Suppress("DEPRECATION")
    private fun writeSettings(transform: (SettingsState) -> SettingsState) {
        check(Looper.myLooper() === Looper.getMainLooper()) { "writeSettings must be called on the main thread" }
        val next = transform(_settingsFlow.value)
        _settingsFlow.value = next
        _state.value = _state.value.copy(
            themeMode = next.themeMode,
            markdownFontSizes = next.markdownFontSizes,
            selectedAgentName = next.selectedAgentName,
            agents = next.agents,
            providers = next.providers,
            availableCommands = next.availableCommands,
            disabledModels = next.disabledModels,
            // §ui-scale (glm-1 🟠): mirror to keep AppState↔slice symmetry with
            // applySettingsSlice (which already mirrors these). No current
            // consumer reads AppState.uiFontScale, but the asymmetry was a
            // latent trap for M4 consumers.
            uiFontScale = next.uiFontScale,
            uiContentScale = next.uiContentScale
        )
    }

    /** §R-17 M4→M5: write the chat slice + mirror. See [writeConnection]. */
    @Suppress("DEPRECATION")
    private fun writeChat(transform: (ChatState) -> ChatState) {
        check(Looper.myLooper() === Looper.getMainLooper()) { "writeChat must be called on the main thread" }
        val next = transform(_chatFlow.value)
        _chatFlow.value = next
        _state.value = _state.value.copy(
            currentSessionId = next.currentSessionId,
            messages = next.messages,
            partsByMessage = next.partsByMessage,
            streamingPartTexts = next.streamingPartTexts,
            streamingReasoningPart = next.streamingReasoningPart,
            olderMessagesCursor = next.olderMessagesCursor,
            hasMoreMessages = next.hasMoreMessages,
            isLoadingMessages = next.isLoadingMessages,
            gapInfo = next.gapInfo,
            staleNotice = next.staleNotice,
            currentModel = next.currentModel
        )
    }

    /** §R-17 M4→M5: write the session-list slice + mirror. See [writeConnection]. */
    @Suppress("DEPRECATION")
    private fun writeSessionList(transform: (SessionListState) -> SessionListState) {
        check(Looper.myLooper() === Looper.getMainLooper()) { "writeSessionList must be called on the main thread" }
        val next = transform(_sessionListFlow.value)
        _sessionListFlow.value = next
        _state.value = _state.value.copy(
            sessions = next.sessions,
            sessionStatuses = next.sessionStatuses,
            expandedSessionIds = next.expandedSessionIds,
            loadedSessionLimit = next.loadedSessionLimit,
            hasMoreSessions = next.hasMoreSessions,
            isLoadingMoreSessions = next.isLoadingMoreSessions,
            isRefreshingSessions = next.isRefreshingSessions,
            pendingPermissions = next.pendingPermissions,
            pendingQuestions = next.pendingQuestions,
            childSessions = next.childSessions,
            directorySessions = next.directorySessions,
            openSessionIds = next.openSessionIds,
            sessionTodos = next.sessionTodos
        )
    }

    /** §R-17 M4→M5: write the unread slice + mirror. See [writeConnection]. */
    @Suppress("DEPRECATION")
    private fun writeUnread(transform: (UnreadState) -> UnreadState) {
        check(Looper.myLooper() === Looper.getMainLooper()) { "writeUnread must be called on the main thread" }
        val next = transform(_unreadFlow.value)
        _unreadFlow.value = next
        _state.value = _state.value.copy(
            unreadSessions = next.unreadSessions,
            tempClearedUnread = next.tempClearedUnread,
            lastViewedTime = next.lastViewedTime
        )
    }

    /** §R-17 M4→M5: write the host slice + mirror. See [writeConnection]. */
    @Suppress("DEPRECATION")
    private fun writeHost(transform: (HostState) -> HostState) {
        check(Looper.myLooper() === Looper.getMainLooper()) { "writeHost must be called on the main thread" }
        val next = transform(_hostFlow.value)
        _hostFlow.value = next
        _state.value = _state.value.copy(
            hostProfiles = next.hostProfiles,
            currentHostProfileId = next.currentHostProfileId
        )
    }

    /**
     * §R-17 M4→M5: unified state write that propagates to EVERY slice.
     *
     * Catch-all for the many mixed-domain update sites
     * (chat/session/unread/host and the cross-domain `error`). Builds a
     * TRANSIENT [AppState] aggregate from the authoritative slice values
     * (see [aggregateFromSlices]), applies the legacy `{ it.copy(...) }`
     * transform against it, then writes the result back to `_state` AND
     * re-syncs every slice from it via [syncSlicesFromAppState]. `_state` thus
     * stays a synchronised mirror of the slices (retained because the free
     * helpers in the MainViewModel*Actions files still read `state.value.<field>`,
     * and CatchUpGapTest's null-slices path — see M5.2 tech debt).
     *
     * Isolation still works because `MutableStateFlow` only emits on
     * `!equals` — a slice whose fields did not change is reassigned an
     * structurally-equal value and its subscribers are NOT notified. So a
     * `updateState { it.copy(isLoadingMessages = true) }` only emits on
     * `_chatFlow` (and `_state`); `_sessionListFlow`/`_connectionFlow`/etc.
     * reassigned equal values stay silent. This is what lets the legacy
     * `updateState` sites drive the slices without per-site split-up, while
     * still giving M4 consumers that subscribe to a single slice the same
     * isolation they would get from per-slice writeXxx calls.
     *
     * §R-17 M5.1 (kimo 🟠#2) THREAD-SAFETY: the aggregate→transform→write→sync
     * sequence is NOT atomic. It is only safe because every call site runs on
     * `Dispatchers.Main.immediate` (single-threaded). Calling this (or
     * [writeConnection]/[writeComposer]/.../`updateAndSync`) from a background
     * thread would let a concurrent writer interleave between the aggregate
     * read and the slice/mirror write, breaking slice↔mirror consistency. MUST
     * be invoked on `Dispatchers.Main.immediate`.
     */
    @Suppress("DEPRECATION")
    private fun updateState(transform: (AppState) -> AppState) {
        check(Looper.myLooper() === Looper.getMainLooper()) { "updateState must be called on the main thread" }
        val before = aggregateFromSlices(_state.value)
        val after = transform(before)
        _state.value = after
        syncSlicesFromAppState(after, sliceFlows)
    }

    /**
     * §R-17 M5→M5.1: builds a transient [AppState] snapshot from the authoritative
     * slice values, carrying over the persisted [AppState.error] /
     * [AppState.lastNavPage] from [seed]. Used by [updateState] so the legacy
     * `(AppState) -> AppState` transforms keep compiling/working against the
     * slice-derived values without each call site needing a per-slice rewrite.
     *
     * §R-17 M5.1 (glmer 🟠#2): delegates to the single field list in the
     * top-level [cn.vectory.ocdroid.ui.aggregateFromSlices] (the same one
     * the free helpers' `updateAndSync` uses), so the slice→AppState mapping
     * lives in exactly one place instead of being hand-synced here.
     */
    private fun aggregateFromSlices(seed: AppState): AppState =
        aggregateFromSlices(sliceFlows, seed)

    // R-16 M2: delegated to [composerController].
    fun togglePartExpand(key: String, currentValue: Boolean) {
        composerController.togglePartExpand(key, currentValue)
    }

    // R-16 M2: delegated to [composerController].
    internal fun clearExpandedParts() {
        composerController.clearExpandedParts()
    }

    // R-16 M2b: sessionWindowCache + all cache helpers (captureCurrentSessionWindow,
    // writeSessionWindow, peekSessionWindow, clearSessionWindowCache,
    // sessionWindowCacheSize) moved to [sessionSwitcher]. The thin wrappers
    // below delegate to the controller so existing callers (loadMessages,
    // loadMoreMessages, catchUp, closeGap via onCacheWindow; host switch /
    // delete / reset via clearSessionWindowCache; tests via peekSessionWindow /
    // sessionWindowCacheSize) keep working unchanged.

    /** Test-only visibility into the cache size (for assertions). */
    internal fun sessionWindowCacheSize(): Int = sessionSwitcher.sessionWindowCacheSize()

    /**
     * Test-only visibility: returns the cached window for [sessionId] if any.
     * Delegates to [sessionSwitcher] — TRUE read-only (does NOT promote to MRU).
     */
    internal fun peekSessionWindow(sessionId: String): CachedSessionWindow? =
        sessionSwitcher.peekSessionWindow(sessionId)

    /**
     * Writes [window] for [sessionId] into the LRU cache. Called from
     * launchLoadMessages / launchLoadMoreMessages via the `onCacheWindow` callback.
     */
    private fun writeSessionWindow(sessionId: String, window: CachedSessionWindow) {
        sessionSwitcher.writeSessionWindow(sessionId, window)
    }

    /** Drops the entire cache. Called on host switch / host delete / reset. */
    override fun clearSessionWindowCache() {
        sessionSwitcher.clearSessionWindowCache()
    }

    // §R-16 M4: sseJob + lastHealthCheckTime moved to [connectionCoordinator].
    // The SSE feed Job and the 30s health-check throttle anchor are now owned
    // by the connection lifecycle controller; MainViewModel delegates via
    // connectionCoordinator.startSSE / cancelSse / cancelSseForReconfigure /
    // testConnection / coldStartReconnect.

    // §R-16 M1: the five @Volatile foreground catch-up fields
    // (hasObservedForegroundState / lastLoadAtMs / sseHasConnectedOnce /
    // backgroundedAtMs / suppressNextConnectCatchUp) and the
    // onForegroundChanged three-tier state machine moved to
    // [foregroundCatchUpController]. MainViewModel now delegates via
    // controller.onForegroundChanged / onServerConnected / onHostReconfigured
    // and implements [ForegroundCatchUpCallbacks] for the side effects.

    init {
        loadSettings()
        // §15.2: foreground/background hook now lives in
        // [foregroundCatchUpController] (it subscribes to
        // appLifecycleMonitor.isInForeground in its own init, launched into
        // viewModelScope). §18.1 error-notification mirror stays here.

        // §18.1: mirror state.error into the notification module so it can
        // surface a system notification when we are in the background.
        // distinctUntilChanged() so we only fire on transitions; the monitor
        // applies its own "skip while in foreground" rule. UNDISPATCHED for
        // the same reason as the refresh trigger — we want the subscription
        // established before init returns.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            _state
                .map { it.error }
                .distinctUntilChanged()
                .collect { appLifecycleMonitor.onAppError(it) }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // R-16 M1: ForegroundCatchUpCallbacks implementation.
    // The three-tier state machine itself lives in [foregroundCatchUpController];
    // these are the side-effect hooks it calls back into. Each maps 1:1 to the
    // private helper the inlined onForegroundChanged used to call directly.
    // ──────────────────────────────────────────────────────────────────────

    override fun forceReconnect() = connectionCoordinator.testConnection(force = true)

    override fun globalColdStartRefresh(currentId: String) = performGlobalColdStartRefresh(currentId = currentId)

    override fun setStaleNotice() {
        updateState { it.copy(staleNotice = true) }
    }

    override fun clearDraft() = clearDraftIfActive()

    // R-16 M4: SSE feed cancellation delegated to [connectionCoordinator]
    // (the sseJob now lives there). M5 跟进 (§4.2): also drop pending delta
    // buffers — a stopped SSE feed won't deliver the part.updated that would
    // have flushed them, so leaving them buffered risks a stale flush on the
    // next connect. clearDeltaBuffers is idempotent.
    override fun cancelSse() {
        connectionCoordinator.cancelSse()
        sessionSyncCoordinator.clearDeltaBuffers()
    }

    override fun catchUpAfterDisconnect(sessionId: String) =
        catchUpAfterDisconnectOrForeground(sessionId)

    override fun currentSessionId(): String? = _chatFlow.value.currentSessionId

    // ──────────────────────────────────────────────────────────────────────
    // R-16 M2: ComposerCallbacks implementation.
    // The composer state mutation logic lives in [composerController]; these
    // are the side-effect hooks it calls back into for SettingsManager writes.
    // ──────────────────────────────────────────────────────────────────────

    override fun saveDraft(sessionId: String, text: String) {
        settingsManager.setDraftText(sessionId, text)
    }

    override fun clearPersistedWorkdir() {
        // §recent-workdirs: deliberately does NOT clear recentWorkdirs. Draft
        // discard (clearDraftIfActive) only abandons the in-progress, unsent
        // draft — the workdir itself remains a project the user has shown
        // interest in, so it stays in recentWorkdirs and is re-fetched on the
        // next cold start (bounded by MAX_RECENT_WORKDIRS MRU eviction). This
        // is intentional: "discard draft" ≠ "forget this project". If a user
        // wants a project gone they disconnect it from the Sessions screen.
        settingsManager.currentWorkdir = null
    }

    // ──────────────────────────────────────────────────────────────────────
    // R-16 M2b: SessionSwitcherCallbacks implementation.
    // The session-switch flow lives in [sessionSwitcher.switchTo]; these are
    // the side-effect hooks it calls back into for SettingsManager / repository
    // / persistence operations. Each maps 1:1 to the original inline call in
    // the pre-extraction selectSession.
    // ──────────────────────────────────────────────────────────────────────

    // Note: saveDraft(sessionId, text) is shared with ComposerCallbacks —
    // both interfaces declare the same method, so a single override satisfies both.

    override fun getDraft(sessionId: String): String = settingsManager.getDraftText(sessionId)

    override fun setCurrentSessionId(sessionId: String?) {
        settingsManager.currentSessionId = sessionId
    }

    override fun setOpenSessionIds(ids: List<String>) {
        settingsManager.openSessionIds = ids
    }

    override fun persistSessionCache(sessions: List<Session>, openIds: List<String>, currentId: String?) {
        persistSessionCache(
            settingsManager = settingsManager,
            sessions = sessions,
            openIds = openIds,
            currentId = currentId,
            currentWorkdir = settingsManager.currentWorkdir
        )
    }

    override fun syncCurrentDirectory(directory: String?) {
        repository.setCurrentDirectory(directory)
    }

    // M5 跟进 (§4.2): SessionSwitcher calls this when LEAVING the outgoing
    // session so its pending delta flush jobs can't write into the new
    // session's state. Delegates to SessionSyncCoordinator.clearDeltaBuffers
    // (idempotent: cancels all flushJobs + clears deltaBuffer).
    override fun onClearDeltaBuffers() = sessionSyncCoordinator.clearDeltaBuffers()

    // Note: loadChildSessions, loadMessages, and loadSessionStatus are
    // implemented as override methods on the existing MainViewModel functions
    // (see their declarations below) — adding `override` to the existing
    // method satisfies the interface without creating a separate delegation.

    /**
     * §Stage D (gpter 阻塞 #1): tear down any in-flight SSE feed BEFORE the
     * repository is reconfigured for a host / profile switch. Without this,
     * the SSE job bound to the PREVIOUS host keeps delivering events into
     * AppState while the new host's health probe is still in flight — those
     * stale events (session/status/message/permission/question) would pollute
     * the freshly-cleared state for the new profile.
     *
     * R-16 M4: delegated to [connectionCoordinator] (cancels the SSE feed +
     * resets the catch-up state machine via the coordinator's callbacks, which
     * route back to foregroundCatchUpController.onHostReconfigured).
     */
    override fun cancelSseForReconfigure() = connectionCoordinator.cancelSseForReconfigure()

    // ──────────────────────────────────────────────────────────────────────
    // R-16 M3: HostProfileCallbacks implementation.
    // The host-profile management logic lives in [hostProfileController];
    // these are the orchestration hooks it calls back into for SSE lifecycle,
    // connection testing, and cross-controller coordination.
    //
    // Several of these (cancelSseForReconfigure, startSse, coldStartReconnect,
    // loadInitialData, clearSessionWindowCache) were previously private/internal
    // methods on MainViewModel and are now `override` (public) to satisfy the
    // interface. forceReconnect is shared with ForegroundCatchUpCallbacks.
    // ──────────────────────────────────────────────────────────────────────

    // Note: forceReconnect() is shared with ForegroundCatchUpCallbacks —
    // a single override satisfies both interfaces.

    override fun resetTrafficTracker() {
        trafficTracker.reset()
    }

    // ──────────────────────────────────────────────────────────────────────
    // R-16 M4: ConnectionCoordinatorCallbacks implementation.
    // The connection lifecycle (testConnection / coldStartReconnect / SSE
    // start-stop / initial-data orchestration) lives in [connectionCoordinator];
    // these are the orchestration hooks it calls back into for repository
    // reconfiguration, the initial-data loaders it does NOT own, SSE event
    // dispatch → SessionSyncCoordinator, and the catch-up state-machine reset.
    //
    // loadSessions / loadAgents / loadProviders / loadPendingQuestions satisfy
    // the interface via their own `override` declarations (the existing
    // loader implementations — MainViewModel still owns them). forceReconnect
    // / cancelSse / cancelSseForReconfigure are shared with the Foreground /
    // HostProfile callback interfaces (single override each).
    // ──────────────────────────────────────────────────────────────────────

    override fun configureRepositoryForCurrentProfile() {
        hostProfileController.configureRepositoryForProfile(hostProfileStore.currentProfile())
    }

    override fun onSseEvent(event: SSEEvent) {
        sessionSyncCoordinator.handleEvent(event)
    }

    override fun onHostReconfigured() {
        foregroundCatchUpController.onHostReconfigured()
    }

    // ──────────────────────────────────────────────────────────────────────
    // R-16 M4: SessionSyncCoordinatorCallbacks implementation.
    // The SSE event → AppState fold lives in [sessionSyncCoordinator]; these
    // are the side-effect hooks it calls back into for the foreground catch-up
    // trigger (server.connected), authoritative message reloads, session-list
    // refresh, permission refresh, and non-fatal payload logging.
    // ──────────────────────────────────────────────────────────────────────

    override fun onServerConnected() {
        foregroundCatchUpController.onServerConnected()
    }

    override fun onRefreshMessages(sessionId: String, resetLimit: Boolean) {
        loadMessagesWithRetry(sessionId, resetLimit)
    }

    override fun onRefreshSessions() {
        loadSessions()
    }

    override fun onLoadPendingPermissions() {
        loadPendingPermissions()
    }

    override fun onNonFatalIssue(message: String) {
        reportNonFatalIssue(TAG, message)
    }

    /**
     * §18.1 deep-link entry point invoked by [cn.vectory.ocdroid.MainActivity]
     * when a notification tap carries [MainActivity.EXTRA_SESSION_ID]. If the
     * session is already cached locally we just [selectSession]; otherwise we
     * try a direct `getSession` fetch (works for sub-agent sessions too) and
     * upsert before selecting. Failures are surfaced via [AppState.error]
     * rather than silently dropping the navigation.
     */
    fun openSessionFromDeepLink(sessionId: String) {
        viewModelScope.launch {
            if (_sessionListFlow.value.sessions.none { it.id == sessionId }) {
                val fetched = withContext(Dispatchers.IO) {
                    runSuspendCatching { repository.getSession(sessionId).getOrNull() }.getOrNull()
                }
                if (fetched != null) {
                    updateState { st ->
                        st.copy(sessions = upsertSession(st.sessions, fetched))
                    }
                }
            }
            selectSession(sessionId)
        }
    }

    private fun loadSettings() {
        applySavedSettings(repository, settingsManager, hostProfileStore, _state, _connectionFlow, _settingsFlow, sliceFlows)
    }

    /** Q6: persist the phone pager page the user navigated to, so cold start lands there. */
    fun setLastNavPage(page: Int) {
        val clamped = page.coerceIn(0, 2)
        if (_state.value.lastNavPage == clamped) return
        settingsManager.lastNavPage = clamped
        updateState { it.copy(lastNavPage = clamped) }
    }

    // R-16 M3: delegated to [hostProfileController].
    fun configureServer(url: String, username: String? = null, password: String? = null) {
        hostProfileController.configureServer(url, username, password)
    }

    fun getHostProfiles(): List<HostProfile> = hostProfileController.getHostProfiles()

    fun currentHostProfile(): HostProfile = hostProfileController.currentHostProfile()

    fun saveHostProfile(
        profile: HostProfile,
        basicAuthPassword: String = "",
        basicAuthEdited: Boolean = false,
        tunnelPassword: String = "",
        tunnelEdited: Boolean = false
    ) {
        hostProfileController.saveHostProfile(profile, basicAuthPassword, basicAuthEdited, tunnelPassword, tunnelEdited)
    }

    fun selectHostProfile(profileId: String) {
        hostProfileController.selectHostProfile(profileId)
        // §model-selection: reload the per-baseUrl disabled-model set for the
        // newly-selected host (it is scoped to the host URL).
        reloadDisabledModelsForCurrentHost()
    }

    fun duplicateHostProfile(profileId: String) {
        hostProfileController.duplicateHostProfile(profileId)
    }

    fun deleteHostProfile(profileId: String) {
        hostProfileController.deleteHostProfile(profileId)
    }

    fun importHostProfile(payload: String): Result<HostProfile> =
        hostProfileController.importHostProfile(payload)

    fun exportHostProfile(profile: HostProfile): String =
        hostProfileController.exportHostProfile(profile)

    fun getSavedConnectionSettings(): ConnectionFormSettings =
        hostProfileController.getSavedConnectionSettings()

    // R-16 M4: delegated to [connectionCoordinator]. The full state machine
    // (30s throttle, exponential-backoff retry, health probe, on-success
    // loadInitialData + startSSE, on-failure error surface) now lives there.
    // §success-channel: onSettled is forwarded so refreshCurrentSession can
    // surface a "已刷新" success toast when the user-initiated probe confirms
    // the server is reachable.
    fun testConnection(force: Boolean = false, retries: Int = 0, onSettled: ((Boolean) -> Unit)? = null) {
        connectionCoordinator.testConnection(force = force, retries = retries, onSettled = onSettled)
    }

    /**
     * §user-req: 测试表单中的连接信息（不保存、不切换 host、不激活隧道）。
     * 用 checkHealthFor 直接探测，返回成功/失败消息。
     *
     * 与 [testConnection] 的区别：[testConnection] 是面向当前激活 host 的完整
     * 连接状态机（成功后 startSSE / loadInitialData，失败后写 AppState.error）；
     * 而本函数只做一次性健康探测，结果通过 [onResult] 回调返回给调用方
     * （HostProfileEditorDialog 的"测试连接"按钮），不触碰任何全局状态。
     *
     * HealthResponse.version 可空（旧服务器可能不返回），回调消息做了 null 安全。
     */
    /**
     * §fix-401: 表单"测试连接"探测。密码为 write-only（不回填表单），故编辑已有
     * host 时表单密码为空——此时回退到已保存的密码（按 profileId 从加密存储读出），
     * 使"改了 URL 但没重输密码"的场景也能正确带 Basic Auth 测试。
     *
     * @param profileId 正在编辑的 host profile id（新建时传 null）。仅当表单 password
     *  为空时用它回退查已保存密码。
     */
    fun testConnectionForm(
        baseUrl: String,
        username: String?,
        password: String?,
        allowInsecure: Boolean,
        profileId: String?,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        viewModelScope.launch {
            // §fix-401: 表单密码为空 + 在编辑已有 profile → 回退已保存密码。
            val effectivePassword = password
                ?: profileId?.let { settingsManager.basicAuthPassword(it) }
            val result = repository.checkHealthFor(baseUrl, username, effectivePassword, allowInsecure)
            result
                .onSuccess { health ->
                    if (health.healthy) {
                        val msg = health.version?.let { v -> "连接成功 (v$v)" } ?: "连接成功"
                        onResult(true, msg)
                    } else {
                        onResult(false, "服务器不可用 (healthy=false)")
                    }
                }
                .onFailure { e -> onResult(false, e.message ?: "连接失败") }
        }
    }

    /**
     * Cold-start entry point: force a connection check with up to 3 retries
     * (exponential backoff 1s/2s/4s) so a slow-to-wake server (common when
     * the OpenCode server itself is bootstrapping) still comes up instead of
     * stranding the user on the disconnected empty state. Used exclusively
     * from [cn.vectory.ocdroid.MainActivity]'s cold-start LaunchedEffect.
     */
    override fun coldStartReconnect() = connectionCoordinator.coldStartReconnect()

    // R-16 M4: delegated to [connectionCoordinator] (loadCommands / localCommands
    // / mergeCommands moved there too; initial-data loaders route back through
    // ConnectionCoordinatorCallbacks which MainViewModel implements below).
    override fun loadInitialData() = connectionCoordinator.loadInitialData()

    /**
     * Routes a `/command arguments` invocation. Local commands that have
     * client-side semantics are handled here:
     *   - `/clear`            -> start a new session in the current workdir.
     *
     * All other commands (including /compact, /undo, /redo when the server
     * did not publish them, plus every server-defined command such as /init,
     * /review) are forwarded to POST /session/{id}/command. The current
     * workdir is injected by the OkHttp interceptor.
     *
     * [arguments] is the raw text the user typed after the command name. It is
     * packed into the request as a single "text" argument for the server.
     */
    fun executeCommand(command: String, arguments: String) {
        val cmd = command.removePrefix("/").trim().lowercase(Locale.getDefault())
        if (cmd.isEmpty()) return
        when (cmd) {
            "clear" -> {
                setInputText("")
                // Start a new session in the current workdir (or fall back to
                // a host-default createSession when no workdir is bound).
                val workdir = repository.getCurrentDirectory()
                    ?: currentSession(_sessionListFlow.value.sessions, _chatFlow.value.currentSessionId)?.directory
                if (workdir != null) {
                    createSessionInWorkdir(workdir)
                } else {
                    createSession()
                }
            }
            else -> {
                val sessionId = _chatFlow.value.currentSessionId ?: run {
                    updateState {
                        it.copy(error = "Open or create a session before running /$cmd")
                    }
                    return
                }
                setInputText("")
                viewModelScope.launch {
                    // §fix(command-400): server 1.17.11 wants `arguments` as the
                    // raw string, not a {"text": ...} object. Pass it directly.
                    repository.executeCommand(sessionId, cmd, arguments)
                        .onFailure { error ->
                            updateState {
                                it.copy(error = errorMessageOrFallback(error, "Command /$cmd failed"))
                            }
                        }
                }
            }
        }
    }

    /**
     * §context-compact: triggers server-side context compaction for the current
     * session via POST /session/{id}/summarize. Reads the current session ID
     * and current model from app state; the server uses the model to generate
     * the summary of older messages. The compaction itself runs async on the
     * server and the resulting message/part SSE events drive the message
     * reload automatically — no manual reload is needed here.
     *
     * Surfaces failures (no session, no model, HTTP error) via the same
     * [AppState.error] channel as [executeCommand].
     */
    fun compactSession() {
        // §compact: guard against duplicate compaction.
        if (_chatFlow.value.isCompacting) return
        val sessionId = _chatFlow.value.currentSessionId ?: run {
            updateState { it.copy(error = "Open or create a session before compacting") }
            return
        }
        val model = _chatFlow.value.currentModel ?: run {
            updateState { it.copy(error = "No model info available for compaction") }
            return
        }
        writeChat { it.copy(isCompacting = true, compactStartedAt = System.currentTimeMillis()) }
        viewModelScope.launch {
            repository.summarizeSession(sessionId, model)
                .onFailure { error ->
                    writeChat { it.copy(isCompacting = false, compactStartedAt = 0L) }
                    updateState {
                        it.copy(error = errorMessageOrFallback(error, "Compact failed"))
                    }
                }
        }
    }

    /** §compact: clears the compacting flag. Called by ChatScreen when the
     * session transitions from busy → idle (compaction complete). */
    fun clearCompacting() {
        if (_chatFlow.value.isCompacting) {
            writeChat { it.copy(isCompacting = false, compactStartedAt = 0L) }
        }
    }

    // R-16 M4: now also satisfies ConnectionCoordinatorCallbacks.loadSessions.
    override fun loadSessions() {
        launchLoadSessions(
            scope = viewModelScope,
            repository = repository,
            state = _state,
            settingsManager = settingsManager,
            onSelectSession = ::selectSession,
            onLoadSessionStatus = ::loadSessionStatus,
            onLoadMessages = { sessionId -> loadMessages(sessionId, resetLimit = true) },
            slices = sliceFlows,
        )
    }

    fun loadMoreSessions() {
        launchLoadMoreSessions(
            scope = viewModelScope,
            repository = repository,
            state = _state,
            onSelectSession = ::selectSession,
            slices = sliceFlows,
        )
    }

    override fun loadSessionStatus() {
        launchLoadSessionStatus(
            scope = viewModelScope,
            repository = repository,
            state = _state,
            slices = sliceFlows,
        )
    }

    /**
     * R-16 M2b: the full 8-step session-switch flow now lives in
     * [sessionSwitcher.switchTo]. This is a thin public wrapper that preserves
     * the existing API for all callers (loadSessions callback, openSubAgent,
     * openSessionFromDeepLink, sendMessage draft creation, tests, etc.).
     */
    fun selectSession(sessionId: String) {
        sessionSwitcher.switchTo(sessionId)
    }

    /**
     * Fetches the sub-agent (child) sessions for [sessionId] and stores them in
     * [AppState.childSessions]. Best-effort: failures (older servers without the
     * `children` endpoint, test mocks) are swallowed silently — the sub-agent
     * cards simply fall back to the in-stream tool state.
     */
    override fun loadChildSessions(sessionId: String) {
        viewModelScope.launch {
            try {
                repository.getChildren(sessionId)
                    .onSuccess { children ->
                        updateState { it.copy(childSessions = it.childSessions + (sessionId to children)) }
                    }
                    .onFailure { error ->
                        reportNonFatalIssue(TAG, "Failed to load child sessions for $sessionId", error)
                    }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Swallow: child sessions are a progressive-enhancement feature.
            }
        }
    }

    /**
     * Opens a sub-agent conversation in-place: resolves the child session from
     * any available cache (sessions list, parent's [AppState.childSessions] or
     * a single-shot API fetch), upserts it into the sessions list (so workdir
     * sync + session lookup work), then selects it.
     *
     * Caller passes the child session ID obtained from a `task` part's metadata.
     * Without the upsert, [selectSession] would set `currentSession` to null
     * (the session is not in the cached list), which breaks the parent-back
     * affordance and lets [selectSession]'s openSessionIds branch mistake the
     * unknown session for a root session.
     */
    fun openSubAgent(childSessionId: String) {
        val parentId = _chatFlow.value.currentSessionId
        viewModelScope.launch {
            // Try every local cache first; fall back to a single GET so we
            // always have the child session in `state.sessions` before select.
            val child = _sessionListFlow.value.sessions.firstOrNull { it.id == childSessionId }
                ?: parentId?.let { pid -> _sessionListFlow.value.childSessions[pid]?.find { it.id == childSessionId } }
                ?: _sessionListFlow.value.childSessions.values.flatten().firstOrNull { it.id == childSessionId }
                ?: runSuspendCatching { repository.getSession(childSessionId).getOrNull() }.getOrNull()
            // #14: only upsert + select when the child actually resolved.
            // Previously a null child still triggered selectSession(childSessionId),
            // which would set currentSessionId to a non-existent session and
            // leave currentSession null. Surface the failure via the error
            // channel instead (same mechanism as testConnection failures).
            if (child != null) {
                updateState { state -> state.copy(sessions = upsertSession(state.sessions, child)) }
                selectSession(childSessionId)
            } else {
                updateState { it.copy(error = "子任务会话不可用") }
            }
        }
    }

    /**
     * Remove a session from the "open" tabs list (browser-tab close). Only
     * removes it from [AppState.openSessionIds] / [SettingsManager.openSessionIds];
     * the session itself is not deleted from the server. If the closed session
     * was the currently selected one, the next open session (if any) is
     * selected automatically, otherwise the chat view returns to the empty
     * state and the persisted [currentSessionId] is cleared.
     */
    fun closeSession(sessionId: String) {
        val isCurrent = _chatFlow.value.currentSessionId == sessionId
        // Preserve the draft of the session being closed. The session-switch flow
        // would otherwise mis-target the save once currentSessionId has moved
        // (it reads oldSessionId AFTER we've changed it), writing the closed
        // session's draft into the next session (#10). Save it explicitly here
        // so selectSession(nextId) only restores the next session's own draft.
        if (isCurrent) {
            settingsManager.setDraftText(sessionId, _composerFlow.value.inputText)
        }
        val updated = settingsManager.openSessionIds.filter { it != sessionId }
        settingsManager.openSessionIds = updated
        val nextId = updated.firstOrNull()
        updateState { currentState ->
            val next = currentState.copy(
                openSessionIds = updated,
                unreadSessions = currentState.unreadSessions - sessionId
            )
            if (isCurrent && nextId == null) {
                settingsManager.currentSessionId = null
                next.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap())
            } else {
                // Keep currentSessionId pointing at the closed session for now;
                // selectSession(nextId) below performs the switch (and saves the
                // closed session's draft via the explicit setDraftText above).
                next
            }
        }
        // §R-17 M3 (RFC §4 strategy A): when the last open session is closed,
        // clear the composer inputText via the composer slice. Runs only in the
        // same `isCurrent && nextId == null` branch above; intermediate state
        // legal (inputText briefly retains its value until this line).
        if (isCurrent && nextId == null) {
            writeComposer { it.copy(inputText = "") }
        }
        if (isCurrent && nextId != null) {
            selectSession(nextId)
        }
    }

    override fun loadMessages(sessionId: String, resetLimit: Boolean) {
        launchLoadMessages(
            scope = viewModelScope,
            repository = repository,
            state = _state,
            settingsFlow = _settingsFlow,
            sessionId = sessionId,
            resetLimit = resetLimit,
            settingsManager = settingsManager,
            onCacheWindow = ::writeSessionWindow,
            slices = sliceFlows,
        )
    }

    /**
     * Convenience overload preserving the pre-M2b single-arg call convention
     * (`loadMessages(sessionId)`). Kotlin does not allow default parameter
     * values on override methods, so this thin wrapper supplies the default.
     */
    fun loadMessages(sessionId: String) = loadMessages(sessionId, resetLimit = true)

    /** Load messages with delay when triggered by SSE/send (server may need time to persist). */
    private fun loadMessagesWithRetry(sessionId: String, resetLimit: Boolean = true) {
        launchLoadMessagesWithRetry(viewModelScope, sessionId, _state, resetLimit, ::loadMessages)
    }

    fun loadMoreMessages() {
        val sessionId = _chatFlow.value.currentSessionId ?: return
        launchLoadMoreMessages(
            scope = viewModelScope,
            repository = repository,
            state = _state,
            sessionId = sessionId,
            onCacheWindow = ::writeSessionWindow,
            slices = sliceFlows,
        )
    }

    /**
     * §Phase1D manual refresh = GLOBAL cold-start + lazy reload.
     *
     * Drops the entire in-memory message window cache ([sessionWindowCache]),
     * clears streaming overlays + any open gap, then freshly loads the CURRENT
     * session's latest-5 (authoritative snapshot). Other sessions are NOT
     * re-fetched now — they lazy-load via the existing [selectSession]
     * cache-miss path when the user next opens them. This is the user's escape
     * hatch when they distrust the live SSE state: a deliberate "forget
     * everything, start fresh" that only costs traffic for sessions actually
     * revisited.
     *
     * §4-A + §1-addendum: also force a [testConnection] so a stale red badge
     * recovers (the original cold-start path doesn't run on refresh, leaving
     * `isConnected` stuck on disconnected even though the server is reachable).
     * On success the [onSettled] callback writes a one-shot successMessage so
     * the user sees a "已刷新" snackbar — positive feedback that the refresh
     * actually worked (not just silently reloaded).
     */
    fun refreshCurrentSession() {
        val sessionId = _chatFlow.value.currentSessionId ?: return
        if (_chatFlow.value.isLoadingMessages) return
        performGlobalColdStartRefresh(currentId = sessionId)
        testConnection(force = true, onSettled = { ok ->
            // §1-addendum (gpt-1 race fix): only surface "已刷新" if health
            // succeeded AND the concurrent message reload has also settled
            // cleanly (no in-flight load, no error). testConnection's
            // onSettled(true) fires on health-OK independent of
            // performGlobalColdStartRefresh's async loadMessages — without
            // this gate, a server that's healthy but whose message fetch
            // failed would show "已刷新" alongside "Failed to load messages",
            // which is misleading ("已刷新" implies the session view is current).
            if (ok && !_chatFlow.value.isLoadingMessages && _state.value.error == null) {
                updateState { it.copy(successMessage = "已刷新") }
            }
        })
    }

    /**
     * Shared global cold-start body used by both [refreshCurrentSession] and
     * the long-absence foreground tier. Clears all per-session message state
     * (cache + current view + gap + streaming) then cold-loads [currentId].
     */
    private fun performGlobalColdStartRefresh(currentId: String) {
        // §Phase1E (glm-1 🟠-2): bail if a message load (e.g. an in-flight
        // loadMore) is running. Without this guard the clear+loadMessages
        // below would wipe messages, then loadMessages would no-op on the
        // shared isLoadingMessages guard, leaving an empty list. The caller
        // (refresh button or >5min tier) is user/initiated and idempotent —
        // skipping here is safe; the banner/action remains for a retry.
        if (_chatFlow.value.isLoadingMessages) return
        clearSessionWindowCache()
        // §3-scroll-memory: bump the refresh nonce so ChatScreen clears its
        // hoisted per-session scroll-position cache. Manual refresh = full
        // forget (per product decision: the cached position is no longer
        // meaningful after a window reset).
        writeChat { it.copy(refreshNonce = it.refreshNonce + 1) }
        updateState {
            it.copy(
                streamingPartTexts = emptyMap(),
                streamingReasoningPart = null,
                gapInfo = null,
                staleNotice = false,
                messages = emptyList(),
                partsByMessage = emptyMap(),
                olderMessagesCursor = null,
                hasMoreMessages = true
            )
        }
        loadMessages(currentId, resetLimit = true)
    }

    /**
     * §Phase1B/1C catch-up wrapper: probe the server's newest message id and
     * only reload the 5-message tail if it changed; on reload, detect whether
     * a gap (断层) opened and surface it via [AppState.gapInfo]. Used on SSE
     * reconnect (not the first connect) and the 15s–5min foreground tier.
     */
    private fun catchUpAfterDisconnectOrForeground(sessionId: String) {
        launchCatchUp(
            scope = viewModelScope,
            repository = repository,
            state = _state,
            settingsFlow = _settingsFlow,
            sessionId = sessionId,
            settingsManager = settingsManager,
            onCacheWindow = ::writeSessionWindow,
            slices = sliceFlows,
        )
    }

    /**
     * §Phase1C user-triggered gap closure: pages older from the gap boundary
     * until the pre-reload newest id reappears (gap bridged) or history is
     * exhausted. Bound to the gap divider's tap action.
     */
    fun closeGap() {
        val sessionId = _chatFlow.value.currentSessionId ?: return
        launchCloseGap(
            scope = viewModelScope,
            repository = repository,
            state = _state,
            sessionId = sessionId,
            onCacheWindow = ::writeSessionWindow,
            slices = sliceFlows,
        )
    }

    // R-16 M4: now satisfies ConnectionCoordinatorCallbacks.loadAgents.
    override fun loadAgents() {
        viewModelScope.launch {
            repository.getAgents()
                .onSuccess { agents ->
                    val currentAgent = _settingsFlow.value.selectedAgentName
                    val validAgent = if (agents.none { it.name == currentAgent }) {
                        "build"
                    } else {
                        currentAgent
                    }
                    writeSettings { it.copy(agents = agents, selectedAgentName = validAgent) }
                    if (validAgent != currentAgent) {
                        settingsManager.selectedAgentName = validAgent
                    }
                }
                .onFailure { error ->
                    reportNonFatalIssue(TAG, "Failed to load agents", error)
                }
        }
    }

    // R-16 M4: now satisfies ConnectionCoordinatorCallbacks.loadProviders.
    override fun loadProviders() {
        launchLoadProviders(viewModelScope, repository, _state, _settingsFlow) { message, error ->
            reportNonFatalIssue(TAG, message, error)
        }
    }

    fun createSession(title: String? = null) {
        launchCreateSession(viewModelScope, repository, _state, title, ::selectSession, sliceFlows)
    }

    /**
     * Deferred ("draft") session creation: sets the repository's working
     * directory and enters a draft state ([AppState.draftWorkdir]) without
     * issuing POST /session. The session is created lazily by [sendMessage]
     * on the first user prompt. If the user navigates away (selects another
     * session or switches host), the draft is discarded.
     */
    fun createSessionInWorkdir(workdir: String) {
        // §key-consistency: normalize once at the entry so every downstream
        // store — repository.setCurrentDirectory, currentWorkdir, recentWorkdirs,
        // directorySessions map key, getSessionsForDirectory parameter — shares
        // the identical key. Without this, a workdir with surrounding whitespace
        // would split into two directorySessions keys (raw here vs trimmed in
        // addRecentWorkdir / cold-start restore).
        val workdir = workdir.trim()
        repository.setCurrentDirectory(workdir)
        // Clear any previously selected session: draft mode shows an empty
        // chat page until the first message materialises the session.
        settingsManager.currentSessionId = null
        updateState {
            it.copy(
                currentSessionId = null,
                messages = emptyList(),
                partsByMessage = emptyMap(),
                sessionTodos = emptyMap(),
                streamingPartTexts = emptyMap(),
                streamingReasoningPart = null
            )
        }
        // §fix-draft-model-leak: 清掉上一会话遗留的 currentModel。否则从会话 A
        // （currentModel=由 A 的 assistant 推断）进入草稿、不切模型直接发时，草稿
        // 物化路径会把 A 的推断模型持久化到新会话 B（glmer 评审非阻塞项）。清空后，
        // 草稿态 currentModel=null：选择器显示默认、首条发送 model=null 走服务端默认、
        // 物化时 currentModel?.let 不持久化、loadMessages 从首条 assistant 正常推断。
        writeChat { it.copy(currentModel = null) }
        // §R-17 M3 (RFC §4 strategy A): composer slice — enter draft mode
        // (draftWorkdir=workdir) and reset input/attachments. Intermediate state
        // legal (chat fields cleared above; composer flipped below).
        writeComposer {
            it.copy(
                inputText = "",
                imageAttachments = emptyList(),
                draftWorkdir = workdir
            )
        }
        // Persist the connected workdir so a restart re-scopes the repository
        // to this project (currentDirectory is otherwise in-memory only).
        settingsManager.currentWorkdir = workdir
        // §recent-workdirs: remember this workdir so cold-start loadInitialData
        // re-fetches its directory sessions even after the user later switches
        // to a different project. currentWorkdir is a single value; without
        // this, every non-current project whose sessions fall outside the
        // global getSessions(limit) first page vanishes after restart.
        settingsManager.addRecentWorkdir(workdir)
        // Best-effort: fetch the existing root sessions for this workdir (#10)
        // so the user can discover / resume prior conversations in the project
        // they just connected. Stored in [AppState.directorySessions] (a map
        // keyed by workdir), separate from the global sessions list, so the
        // periodic global getSessions refresh does not discard it. Failures
        // (older servers, transient network) are silently ignored.
        viewModelScope.launch {
            repository.getSessionsForDirectory(workdir)
                .onSuccess { sessions ->
                    updateState { it.copy(directorySessions = it.directorySessions + (workdir to sessions)) }
                }
        }
    }

    /**
     * Discard an in-progress draft session (deferred-create mode entered via
     * [createSessionInWorkdir]) whenever the user leaves the Chat screen or
     * the app goes to background. No-op when there is no active draft, so it
     * is safe to call from any navigation / lifecycle hook without disturbing
     * real (already-created) sessions.
     *
     * The guard mirrors [sendMessage]'s draft branch precondition:
     * `draftWorkdir != null && currentSessionId == null`. Once the draft has
     * materialised into a real session (first send), `draftWorkdir` is null
     * and this call does nothing.
     */
    // R-16 M2: delegated to [composerController].
    fun clearDraftIfActive() {
        composerController.clearDraftIfActive()
    }

    fun forkSession(sessionId: String, messageId: String?) {
        launchForkSession(viewModelScope, repository, _state, sessionId, messageId, ::selectSession, sliceFlows)
    }

    fun archiveSession(sessionId: String) {
        launchSetSessionArchived(viewModelScope, repository, _state, settingsManager, sessionId, archived = true, sliceFlows)
    }

    fun restoreSession(sessionId: String) {
        launchSetSessionArchived(viewModelScope, repository, _state, settingsManager, sessionId, archived = false, sliceFlows)
    }

    fun deleteSession(sessionId: String) {
        launchDeleteSession(viewModelScope, repository, _state, settingsManager, sessionId, ::selectSession, sliceFlows)
    }

    fun sendMessage() {
        val draftWorkdir = _composerFlow.value.draftWorkdir
        val existingSessionId = _chatFlow.value.currentSessionId
        val text = _composerFlow.value.inputText.trim()
        val attachments = _composerFlow.value.imageAttachments
        if (text.isEmpty() && attachments.isEmpty()) return

        // Draft mode: lazily create the session on the first send. The draft
        // is tied to the workdir set by createSessionInWorkdir; we materialise
        // the session via POST /session, switch currentSessionId to it, clear
        // the draft, prepend to openSessionIds, then dispatch the message.
        if (draftWorkdir != null && existingSessionId == null) {
            // #10: synchronously clear draftWorkdir BEFORE launching the
            // network call so a double-tap (race) cannot enter this branch a
            // second time and create duplicate sessions. The local draftWorkdir
            // captured above is used inside the coroutine. A second invoke now
            // reads draftWorkdir=null → existingSessionId=null → early return.
            writeComposer { it.copy(draftWorkdir = null) }
            viewModelScope.launch {
                repository.createSession(title = null)
                    .onSuccess { session ->
                        updateState { state ->
                            val openIds = (listOf(session.id) + settingsManager.openSessionIds).distinct().take(8)
                            settingsManager.openSessionIds = openIds
                            val now = System.currentTimeMillis()
                            state.copy(
                                sessions = upsertSession(state.sessions, session),
                                currentSessionId = session.id,
                                openSessionIds = openIds,
                                unreadSessions = state.unreadSessions - session.id,
                                lastViewedTime = state.lastViewedTime + (session.id to now)
                            )
                        }
                        // §R-17 M3: draftWorkdir mirror was cleared above; keep
                        // the slice consistent (no-op semantically, but avoids
                        // slice/mirror drift if the slice was touched elsewhere).
                        writeComposer { it.copy(draftWorkdir = null) }
                        settingsManager.currentSessionId = session.id
                        // §fix-draft-model-switch: 用户可能在草稿态（currentSessionId
                        // 为 null）就切了模型，此时 switchSessionModel 只能更新 in-memory
                        // currentModel。草稿物化为真实 session 后，把该选择补持久化到新
                        // session id，使 dispatchSendMessage 经 getModelForSession 读到它、
                        // 且后续 loadMessages 不回退到推断。
                        _chatFlow.value.currentModel?.let { model ->
                            settingsManager.setModelForSession(session.id, model.providerId, model.modelId)
                        }
                        // Fix #5: persist the freshly-created session's metadata
                        // into sessionCache so its tab survives restart (mirrors
                        // selectSession). The upsert above already added `session`
                        // to state.sessions; loadSessions will later refresh the
                        // cache authoritatively, but this guarantees the tab's
                        // metadata is durable immediately after creation.
                        val postState = _state.value
                        persistSessionCache(
                            settingsManager = settingsManager,
                            sessions = postState.sessions,
                            openIds = postState.openSessionIds,
                            currentId = session.id,
                            currentWorkdir = settingsManager.currentWorkdir
                        )
                        dispatchSendMessage(session.id)
                        // 主动拉取标题兜底：服务端在首条消息后异步生成标题
                        // (SessionPrompt.ensureTitle)，正常经 SSE session.updated
                        // 下发，但 SSE 解析失败时会丢失。
                        // 故延迟 5s 后主动 GET 一次该会话，取回生成的标题。
                        scheduleTitleRefreshAfterFirstMessage(session.id)
                    }
                    .onFailure { error ->
                        // §R-17 M3: draftWorkdir → writeComposer, error → _state.
                        // Restore draft mode so the user can retry. draftWorkdir
                        // was synchronously cleared before launch to guard
                        // against double-tap; on failure we put it back (the
                        // captured local above) so the composer stays in draft
                        // mode and the untouched inputText remains editable. A
                        // retry is a fresh invoke that re-clears + re-launches.
                        writeComposer { it.copy(draftWorkdir = draftWorkdir) }
                        updateState {
                            it.copy(
                                error = "Failed to create session in $draftWorkdir: ${error.message ?: "unknown error"}"
                            )
                        }
                    }
            }
            return
        }

        val sessionId = existingSessionId ?: return
        if (_composerFlow.value.sendingSessionIds.contains(sessionId)) return
        dispatchSendMessage(sessionId)
    }

    /**
     * One-shot best-effort refresh of a freshly-created session's metadata
     * (notably the auto-generated title) [titleRefreshDelayMs] after its first
     * message. The server generates the title asynchronously (SessionPrompt.
     * ensureTitle, prompt-loop step 1); it normally arrives via the
     * `session.updated` SSE event, but that path can fail (e.g. if the
     * `session.updated` frame was missed or parse-failed). This HTTP pull is
     * a robust fallback that works regardless of SSE. No-op on failure.
     */
    private fun scheduleTitleRefreshAfterFirstMessage(sessionId: String) {
        viewModelScope.launch {
            delay(MainViewModelTimings.titleRefreshDelayMs)
            repository.getSession(sessionId)
                .onSuccess { refreshed ->
                    updateState { state ->
                        state.copy(
                            sessions = upsertSession(state.sessions, refreshed),
                            directorySessions = state.directorySessions.mapValues { (_, list) ->
                                list.map { if (it.id == sessionId) refreshed else it }
                            }
                        )
                    }
                }
                // Silent on failure — this is best-effort; the next list
                // refresh will pick up the title authoritatively.
                .onFailure { }
        }
    }

    private fun dispatchSendMessage(sessionId: String) {
        // §R-17 M3: read composer slice directly (authoritative; the AppState
        // mirror is kept in sync by writeComposer but reading the slice avoids
        // the @Deprecated mirror warning inside the VM).
        val composer = _composerFlow.value
        if (composer.sendingSessionIds.contains(sessionId)) return
        val text = composer.inputText.trim()
        val attachments = composer.imageAttachments
        if (text.isEmpty() && attachments.isEmpty()) return

        writeComposer { state -> state.copy(sendingSessionIds = state.sendingSessionIds + sessionId) }

        // §append-safe (glmer MAJOR-1): clear the composer synchronously on
        // dispatch so a follow-up typed during the in-flight prompt_async
        // window is not wiped by a late onSuccess. The captured `text` is
        // restored on failure (in launchSendMessage) only if the user has not
        // typed something new in the meantime.
        settingsManager.setDraftText(sessionId, "")
        writeComposer { it.copy(inputText = "") }

        val currentSession = currentSession(_sessionListFlow.value.sessions, _chatFlow.value.currentSessionId)

        fun dispatchSend() {
            // §model-selection (V1-per-prompt, aligned with official packages/app):
            // agent is per-session — prefer the session-bound choice, fall back to the
            // global default. model prefers the per-session stored intended-next-model
            // (indexed by sessionId, so switch-tab-safe) and falls back to the in-memory
            // ChatState.currentModel (display-only / inference). Reading these INSIDE
            // dispatchSend() (not at the top of dispatchSendMessage) so the archived-
            // session async-unarchive path also reads the freshest values. Both are
            // attached to THIS prompt's request body, NOT set via a server-side switch
            // endpoint.
            val agent = settingsManager.getAgentForSession(sessionId)
                ?: _settingsFlow.value.selectedAgentName
            val model: Message.ModelInfo? = settingsManager.getModelForSession(sessionId)
                ?: _chatFlow.value.currentModel
            launchSendMessage(
                scope = viewModelScope,
                repository = repository,
                state = _state,
                composerFlow = _composerFlow,
                sessionId = sessionId,
                text = text,
                attachments = attachments,
                agent = agent,
                model = model,
                onRefreshMessages = ::loadMessagesWithRetry,
                onRefreshSessions = ::loadSessions,
                onSuccess = {
                    settingsManager.setDraftText(sessionId, "")
                    writeComposer { it.copy(imageAttachments = emptyList()) }
                },
                onComplete = {
                    writeComposer { state -> state.copy(sendingSessionIds = state.sendingSessionIds - sessionId) }
                },
                slices = sliceFlows,
            )
        }

        if (currentSession?.isArchived == true) {
            viewModelScope.launch {
                repository.updateSessionArchived(sessionId, -1L)
                    .onSuccess { updated ->
                        updateState { state ->
                            state.copy(sessions = state.sessions.map { session -> if (session.id == sessionId) updated else session })
                        }
                        dispatchSend()
                    }
                    .onFailure { error ->
                        // §append-safe (gpter MAJOR): the composer was cleared
                        // synchronously at dispatch and sendingSessionIds was
                        // locked. If the pre-send unarchive fails, NO send is
                        // dispatched, so mirror the send-failure cleanup: unlock,
                        // restore the captured prompt (only if the user hasn't
                        // typed something new), and restore the draft — otherwise
                        // the session would be permanently send-locked and the
                        // text lost.
                        // §R-17 M3: read composer slice for the restore-decision;
                        // dispatch error → _state, composer fields → writeComposer.
                        val currentInput = _composerFlow.value.inputText
                        val restored = if (currentInput.isBlank()) text else currentInput
                        if (restored != currentInput) settingsManager.setDraftText(sessionId, restored)
                        updateState { s ->
                            s.copy(
                                error = "Failed to restore session: ${errorMessageOrFallback(error, "unknown error")}"
                            )
                        }
                        writeComposer { c ->
                            c.copy(
                                sendingSessionIds = c.sendingSessionIds - sessionId,
                                inputText = restored
                            )
                        }
                    }
            }
            return
        }

        dispatchSend()
    }

    fun abortSession() {
        val sessionId = _chatFlow.value.currentSessionId ?: return
        viewModelScope.launch {
            repository.abortSession(sessionId)
                .onFailure { error ->
                    updateState { it.copy(error = errorMessageOrFallback(error, "Failed to abort session")) }
                }
        }
    }

    // R-16 M2: delegated to [composerController].
    fun setInputText(text: String) {
        composerController.setInputText(text)
    }

    // R-16 M2: delegated to [composerController].
    fun addImageAttachments(attachments: List<ComposerImageAttachment>) {
        composerController.addImageAttachments(attachments)
    }

    // R-16 M2: delegated to [composerController].
    fun removeImageAttachment(id: String) {
        composerController.removeImageAttachment(id)
    }

    fun editFromMessage(messageId: String) {
        val sessionId = _chatFlow.value.currentSessionId ?: return
        val message = _chatFlow.value.messages.firstOrNull { it.id == messageId && it.isUser } ?: return
        val draft = (_chatFlow.value.partsByMessage[messageId] ?: emptyList()).firstOrNull { it.isText }?.text?.trim().orEmpty()
        if (draft.isBlank()) return

        viewModelScope.launch {
            repository.revertSession(sessionId, messageId)
                .onSuccess { updatedSession ->
                    // §R-17 M3: sessions/error → _state; inputText/imageAttachments
                    // → writeComposer. Intermediate state legal (sessions updated,
                    // composer not yet reset).
                    updateState { state ->
                        state.copy(
                            sessions = state.sessions.map { session -> if (session.id == sessionId) updatedSession else session },
                            error = null
                        )
                    }
                    writeComposer { c ->
                        c.copy(
                            inputText = draft,
                            imageAttachments = emptyList()
                        )
                    }
                    settingsManager.setDraftText(sessionId, draft)
                    loadMessages(sessionId, resetLimit = true)
                    loadSessions()
                }
                .onFailure { error ->
                    updateState { it.copy(error = "Failed to edit message: ${errorMessageOrFallback(error, "unknown error")}") }
                }
        }
    }

    fun selectAgent(agentName: String) {
        settingsManager.selectedAgentName = agentName
        writeSettings { it.copy(selectedAgentName = agentName) }
        _chatFlow.value.currentSessionId?.let { settingsManager.setAgentForSession(it, agentName) }
    }

    /**
     * §model-selection: toggles a model's "disabled" flag for the current
     * host. Disabled models are hidden from the chat quick-switch picker but
     * remain listed in Settings → Model management. Persisted per-baseUrl via
     * [SettingsManager.setModelDisabled].
     */
    fun toggleModelDisabled(providerId: String, modelId: String) {
        val baseUrl = hostProfileStore.currentProfile().serverUrl
        val key = "$providerId/$modelId"
        val currentlyDisabled = key in _settingsFlow.value.disabledModels
        settingsManager.setModelDisabled(baseUrl, providerId, modelId, disabled = !currentlyDisabled)
        writeSettings {
            it.copy(
                disabledModels = if (currentlyDisabled) it.disabledModels - key else it.disabledModels + key
            )
        }
    }

    /**
     * §model-selection: reloads the per-baseUrl disabled-model set from
     * [SettingsManager] using the current host profile's URL. Called on
     * cold start (via `applySavedSettings`) and on host switch so the picker
     * reflects the active server's saved selection.
     */
    fun reloadDisabledModelsForCurrentHost() {
        val baseUrl = hostProfileStore.currentProfile().serverUrl
        val set = settingsManager.getDisabledModels(baseUrl)
        writeSettings { it.copy(disabledModels = set) }
    }

    /**
     * §model-selection (V1-per-prompt): records the user's model choice for the
     * current session as a LOCAL per-session preference (persisted via
     * [SettingsManager.setModelForSession]) and updates the in-memory
     * [ChatState.currentModel] so the picker reflects it immediately. The chosen
     * model is attached to the next outgoing prompt's PromptRequest.model by
     * [dispatchSendMessage]; there is NO server-side switch call (the previous
     * V2 `POST /api/session/{id}/model` path was removed to align with the
     * official packages/app V1-per-prompt model).
     */
    fun switchSessionModel(providerId: String, modelId: String) {
        val sessionId = _chatFlow.value.currentSessionId
        val model = Message.ModelInfo(providerId = providerId, modelId = modelId)
        // §fix-draft-model-switch: draft 模式下（首条消息发送前 currentSessionId
        // 仍为 null，但 UI 已显示模型选择器）也要更新 in-memory currentModel——
        // 这样选择器立即反映新选择，且下一条消息的 dispatchSend() 兜底
        // (getModelForSession ?: currentModel) 能用到它。仅当真实 session 存在时
        // 才持久化到 SettingsManager；草稿在 sendMessage 物化时会补持久化。
        sessionId?.let { settingsManager.setModelForSession(it, providerId, modelId) }
        writeChat { it.copy(currentModel = model) }
    }

    fun toggleSessionExpanded(sessionId: String) {
        updateState { state ->
            val next = if (state.expandedSessionIds.contains(sessionId)) {
                state.expandedSessionIds - sessionId
            } else {
                state.expandedSessionIds + sessionId
            }
            state.copy(expandedSessionIds = next)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        settingsManager.themeMode = mode
        writeSettings { it.copy(themeMode = mode) }
    }

    fun setMarkdownFontSizes(sizes: MarkdownFontSizes) {
        settingsManager.markdownFontSizes = sizes
        writeSettings { it.copy(markdownFontSizes = sizes) }
    }

    /**
     * §ui-scale: persist + push the font-only scale factor (multiplies
     * LocalDensity.fontScale; text resizes, layout/padding/icon sizes stay
     * fixed). Clamped to [SettingsManager.UI_SCALE_MIN]–[MAX] at the
     * SettingsManager layer. Flows reactively to OpenCodeTheme via
     * MainActivity's settingsFlow subscription.
     */
    fun setUiFontScale(scale: Float) {
        // §ui-scale (gpt-1): clamp defensively at the write site too (not just
        // in SettingsManager) so settingsFlow never transiently holds an
        // out-of-range value if a future caller bypasses the slider bounds.
        val clamped = scale.coerceIn(SettingsManager.UI_SCALE_MIN, SettingsManager.UI_SCALE_MAX)
        settingsManager.uiFontScale = clamped
        writeSettings { it.copy(uiFontScale = clamped) }
    }

    /**
     * §ui-scale: persist + push the content scale factor (multiplies
     * LocalDensity.density → dp dimensions AND sp text scale together — true
     * "zoom everything"). Same persistence/reactivity path as
     * [setUiFontScale].
     */
    fun setUiContentScale(scale: Float) {
        val clamped = scale.coerceIn(SettingsManager.UI_SCALE_MIN, SettingsManager.UI_SCALE_MAX)
        settingsManager.uiContentScale = clamped
        writeSettings { it.copy(uiContentScale = clamped) }
    }

    fun respondPermission(sessionId: String, permissionId: String, response: PermissionResponse) {
        viewModelScope.launch {
            repository.respondPermission(sessionId, permissionId, response)
                .onSuccess {
                    updateState { it.copy(
                        pendingPermissions = it.pendingPermissions.filter { p -> p.id != permissionId }
                    )}
                }
                .onFailure { error ->
                    updateState { it.copy(error = errorMessageOrFallback(error, "Failed to respond to permission")) }
                }
        }
    }

    override fun loadPendingPermissions() {
        viewModelScope.launch {
            repository.getPendingPermissions()
                .onSuccess { permissions ->
                    updateState { it.copy(pendingPermissions = permissions) }
                }
                .onFailure { error ->
                    Log.w(TAG, "Failed to load permissions: ${error.message}")
                }
        }
    }

    // R-16 M4: now also satisfies ConnectionCoordinatorCallbacks.loadPendingQuestions
    // §stale-question: and SessionSwitcherCallbacks.loadPendingQuestions.
    override fun loadPendingQuestions() {
        viewModelScope.launch {
            repository.getPendingQuestions()
                .onSuccess { questions ->
                    // §P3 (question overwrite race): merge by id instead of full
                    // replace. GET /question routes to one InstanceState; if the
                    // request lands on the wrong directory instance (or runs in a
                    // narrow timing window) it can return [] / a partial list while
                    // a live question was just delivered via the SSE
                    // question.asked event. A wholesale replace here would wipe
                    // that live question and blank the card (Bug 2 symptom).
                    // Overlapping ids take the GET version; ids only present
                    // locally (SSE-delivered, not yet in GET) are preserved.
                    // Removal stays handled by the SSE question.replied/rejected
                    // handlers and the local reply/reject success paths.
                    updateState { currentState ->
                        val byGet = questions.associateBy { it.id }
                        val existing = currentState.pendingQuestions.associateBy { it.id }
                        val merged = (byGet + existing.filterKeys { it !in byGet }).values.toList()
                        currentState.copy(pendingQuestions = merged)
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "Failed to load questions: ${error.message}")
                }
        }
    }

    fun replyQuestion(requestId: String, answers: List<List<String>>, onError: () -> Unit = {}) {
        viewModelScope.launch {
            repository.replyQuestion(requestId, answers)
                .onSuccess {
                    updateState { currentState ->
                        currentState.copy(pendingQuestions = currentState.pendingQuestions.filter { it.id != requestId })
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "Failed to reply question: ${error.message}")
                    onError()
                }
        }
    }

    fun rejectQuestion(requestId: String) {
        viewModelScope.launch {
            repository.rejectQuestion(requestId)
                .onSuccess {
                    updateState { currentState ->
                        currentState.copy(pendingQuestions = currentState.pendingQuestions.filter { it.id != requestId })
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "Failed to reject question: ${error.message}")
                }
        }
    }

    fun clearError() {
        updateState { it.copy(error = null) }
    }

    /**
     * §success-channel: clear the one-shot success message after the consumer
     * (ChatScreen snackbar) has shown it. Mirrors [clearError]'s consume-on-read
     * lifecycle so a stale success message never re-fires on recomposition.
     */
    fun clearSuccessMessage() {
        updateState { it.copy(successMessage = null) }
    }

    /**
     * Mirrors the latest [TrafficTracker] totals into [_trafficFlow] so the
     * Settings page (and any other consumer) renders current figures. Called
     * on-demand (e.g. when Settings opens) rather than on every request, to
     * avoid global recomposition per network call — the counter itself
     * accumulates continuously in the background.
     *
     * §R-17 M2: writes the dedicated [_trafficFlow] slice instead of AppState.
     */
    fun refreshTrafficStats() {
        writeTraffic {
            it.copy(
                trafficSent = trafficTracker.totalBytesSent,
                trafficReceived = trafficTracker.totalBytesReceived
            )
        }
    }

    /** Zeros the cumulative traffic counters (in-memory + persisted). */
    fun resetTrafficStats() {
        trafficTracker.reset()
        refreshTrafficStats()
    }

    /**
     * Hard reset of ALL local data, then reconnect + re-fetch from the server.
     *
     * Wipes everything persisted by [SettingsManager] EXCEPT server connection
     * info (server URL, basic-auth username/password, host profiles, current
     * host id) and tunnel passwords — see [SettingsManager.clearAllLocalData]
     * for the exact preservation contract. Then resets the in-memory
     * [AppState] to a clean slate, preserving only the host-profile list +
     * current id so the UI stays in a "reconnecting" state against the SAME
     * server (no silent host switch), tears down in-flight SSE, and finally
     * reconnects via [coldStartReconnect] which re-runs [loadInitialData] on a
     * healthy connection.
     *
     * After this: theme/font revert to defaults, open tabs / session cache /
     * drafts / current session+workdir are cleared, traffic counters are zeroed.
     * The connected-project directory sessions are NOT re-fetched here
     * (currentWorkdir is now null) — the user re-connects projects via the
     * Sessions screen; the global server session list still loads fresh.
     */
    // R-16 M3: delegated to [hostProfileController].
    fun resetLocalDataAndResync() {
        hostProfileController.resetLocalDataAndResync()
    }

    // R-16 M3: delegated to [hostProfileController].
    fun activateTunnelForCurrentHost() {
        hostProfileController.activateTunnelForCurrentHost()
    }

    fun showFileInFiles(path: String, originRoute: String? = null) {
        writeFile { it.copy(filePathToShowInFiles = path, filePreviewOriginRoute = originRoute) }
    }

    fun clearFileToShow() {
        writeFile { it.copy(filePathToShowInFiles = null, filePreviewOriginRoute = null) }
    }

    /**
     * Bug3: open the file browser for a specific connected project (workdir)
     * from the Sessions screen. Scopes the repository to [workdir] (so
     * getFileTree/getFileContent list that project) WITHOUT the side effects of
     * [createSessionInWorkdir] (no currentSessionId/draft/currentWorkdir reset),
     * then seeds the preview path. The Sessions screen toggles its own overlay.
     *
     * Directory coupling (gpter review): the file-content API has no per-call
     * directory variant, so the browse must use the GLOBAL repository
     * currentDirectory. To avoid desyncing an open Chat session (project A) when
     * browsing project B, we SAVE the pre-browse directory here and RESTORE it
     * in [restoreDirectoryAfterBrowse] when the overlay closes. The Sessions
     * file overlay is full-screen, so Chat is not interactable during the browse
     * (no concurrent access); on close the global directory is restored to A.
     */
    private var browseSavedDirectory: String? = null
    private var browseActive: Boolean = false

    fun browseFilesInWorkdir(workdir: String) {
        if (!browseActive) {
            browseSavedDirectory = repository.getCurrentDirectory()
            browseActive = true
        }
        repository.setCurrentDirectory(workdir)
        writeFile {
            it.copy(
                // Fix #4: null here so FilesViewModel.syncPathToShow(null, ...)
                // calls closePreview() and the normal loadFiles("") flow (scoped
                // by the X-Opencode-Directory header set above) renders the
                // clickable FileBrowserPane. Setting it to `workdir` misrouted
                // the directory through the file-preview pipeline and rendered a
                // plain-text directory dump instead of the clickable list.
                filePathToShowInFiles = null,
                filePreviewOriginRoute = "sessions",
                fileBrowserOpen = true,
                fileBrowserWorkdir = workdir
            )
        }
    }

    /** Close the project file browser: restore the global currentDirectory to
     *  its pre-browse value and hide the overlay. Called from PhoneLayout's
     *  overlay close (onCloseFile / BackHandler). */
    fun closeFileBrowser() {
        if (browseActive) {
            repository.setCurrentDirectory(browseSavedDirectory)
            browseSavedDirectory = null
            browseActive = false
        }
        writeFile {
            it.copy(
                fileBrowserOpen = false,
                fileBrowserWorkdir = null,
                filePathToShowInFiles = null,
                filePreviewOriginRoute = null
            )
        }
    }

    /**
     * On-demand re-fetch of a connected project's directory-scoped sessions.
     *
     * [loadInitialData] only refreshes [AppState.directorySessions] for the
     * *current* workdir, so a non-current connected project's session list goes
     * stale. Combined with missed `session.created` SSE events while the phone
     * was backgrounded, a conversation created on the web would not appear
     * under its project until a full reconnect. The Sessions screen calls this
     * when the user EXPANDS a workdir group, so the freshest server list is
     * rendered. Fire-and-forget: on success it REPLACES the workdir's entry in
     * [AppState.directorySessions] (same semantics as loadInitialData's
     * directory fetch); on failure it silently keeps the existing list
     * (onSuccess only) — acceptable for a user-initiated refresh.
     */
    fun refreshDirectorySessions(workdir: String) {
        val workdir = workdir.trim()
        if (workdir.isBlank()) return
        viewModelScope.launch {
            repository.getSessionsForDirectory(workdir)
                .onSuccess { sessions ->
                    updateState { it.copy(directorySessions = it.directorySessions + (workdir to sessions)) }
                }
        }
    }

    // R-16 M4: delegated to [connectionCoordinator] (the sseJob + the SSE
    // collection coroutine now live there; events route to SessionSyncCoordinator
    // via ConnectionCoordinatorCallbacks.onSseEvent).
    override fun startSSE() = connectionCoordinator.startSSE()

    /**
     * R-16 M4: delegated to [sessionSyncCoordinator]. Kept as a private method
     * (NOT removed) so the reflection-based test helper `handleSse(...)` and
     * any direct call sites keep resolving — the SSE event fold (server.connected
     * catch-up trigger + the message/session/status/part/permission/question/todo
     * dispatch) now lives in the coordinator.
     */
    private fun handleSSEEvent(event: SSEEvent) {
        sessionSyncCoordinator.handleEvent(event)
    }

    override fun onCleared() {
        // M5 跟进 (§4.2): drop pending delta buffers before the scope is
        // cancelled, so no flush job can fire post-teardown. (onCleared calls
        // connectionCoordinator.cancelSse() directly rather than the cancelSse()
        // override, so clearDeltaBuffers is added explicitly here.)
        sessionSyncCoordinator.clearDeltaBuffers()
        connectionCoordinator.cancelSse()
        super.onCleared()
    }

    private companion object {
        private const val TAG = "MainViewModel"

        // R-16 M1: FOREGROUND_RELOAD_MIN_INTERVAL_MS (15s) and
        // LONG_ABSENCE_THRESHOLD_MS (5min) moved to
        // ForegroundCatchUpController.companion.

        // R-16 M2b: SESSION_WINDOW_CACHE_CAPACITY moved to
        // SessionSwitcher.companion (the cache now lives there).
    }
}
