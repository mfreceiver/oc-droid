package cn.vectory.ocdroid.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * R-17 batch3: singleton container for the 9 AppState slices + nav + uiEvents.
 *
 * HiltViewModels cannot inject each other (ViewModels are not @Inject-able
 * dependencies), so the slices that USED to live in MainViewModel's private
 * fields now live in this @Singleton. Every domain ViewModel injects the same
 * instance and reads/writes its own slice through the per-slice helpers. This
 * preserves the original semantics (single authoritative MutableStateFlow per
 * domain) while letting the VMs stay decoupled.
 *
 * The bundle-style access ([connectionFlow]/[chatFlow]/etc.) preserves the
 * original controller constructor signatures that take a [SliceFlows] bundle
 * — the controllers do not need to change.
 *
 * §R18 Phase 4 (P0-9): write-permission convergence. Every slice
 * `MutableStateFlow` is now `private`. External readers consume the read-only
 * [StateFlow] views ([xxxFlow]); every write funnels through the matching
 * [mutateXxx] helper. No caller can reach the underlying
 * `MutableStateFlow.update` / `.value =` directly, so the per-slice mutator is
 * the single authoritative write entry point. Oracle ruling: YAGNI — in a
 * single-module app a true isolation layer (interface segregation / write-token
 * hand-out) is not worth the indirection; the private + asStateFlow +
 * mutateXxx trio IS the P0-9 terminal state.
 */
@Singleton
class SharedStateStore @Inject constructor() {
    private val _connectionFlow = MutableStateFlow(ConnectionState())
    val connectionFlow: StateFlow<ConnectionState> = _connectionFlow.asStateFlow()
    fun mutateConnection(transform: (ConnectionState) -> ConnectionState) = _connectionFlow.update(transform)

    private val _trafficFlow = MutableStateFlow(TrafficState())
    val trafficFlow: StateFlow<TrafficState> = _trafficFlow.asStateFlow()
    fun mutateTraffic(transform: (TrafficState) -> TrafficState) = _trafficFlow.update(transform)

    private val _composerFlow = MutableStateFlow(ComposerState())
    val composerFlow: StateFlow<ComposerState> = _composerFlow.asStateFlow()
    fun mutateComposer(transform: (ComposerState) -> ComposerState) = _composerFlow.update(transform)

    private val _fileFlow = MutableStateFlow(FileState())
    val fileFlow: StateFlow<FileState> = _fileFlow.asStateFlow()
    fun mutateFile(transform: (FileState) -> FileState) = _fileFlow.update(transform)

    private val _settingsFlow = MutableStateFlow(SettingsState())
    val settingsFlow: StateFlow<SettingsState> = _settingsFlow.asStateFlow()
    fun mutateSettings(transform: (SettingsState) -> SettingsState) = _settingsFlow.update(transform)

    private val _chatFlow = MutableStateFlow(ChatState())
    val chatFlow: StateFlow<ChatState> = _chatFlow.asStateFlow()
    fun mutateChat(transform: (ChatState) -> ChatState) = _chatFlow.update(transform)

    private val _sessionListFlow = MutableStateFlow(SessionListState())
    val sessionListFlow: StateFlow<SessionListState> = _sessionListFlow.asStateFlow()
    fun mutateSessionList(transform: (SessionListState) -> SessionListState) = _sessionListFlow.update(transform)

    private val _unreadFlow = MutableStateFlow(UnreadState())
    val unreadFlow: StateFlow<UnreadState> = _unreadFlow.asStateFlow()
    fun mutateUnread(transform: (UnreadState) -> UnreadState) = _unreadFlow.update(transform)

    private val _hostFlow = MutableStateFlow(HostState())
    val hostFlow: StateFlow<HostState> = _hostFlow.asStateFlow()
    fun mutateHost(transform: (HostState) -> HostState) = _hostFlow.update(transform)

    /** §R18 Phase 3 Wave 1 (C-2): collapsible-card expansion map. Writes via
     *  [mutateExpandedParts] (CAS, atomic); reads via [expandedParts]. */
    private val _expandedParts = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val expandedParts: StateFlow<Map<String, Boolean>> = _expandedParts.asStateFlow()
    fun mutateExpandedParts(transform: (Map<String, Boolean>) -> Map<String, Boolean>) = _expandedParts.update(transform)

    /** Nav slice (not part of [SliceFlows] bundle). Seeded by OrchestratorVM. */
    private val _navFlow = MutableStateFlow(NavState())
    val navFlow: StateFlow<NavState> = _navFlow.asStateFlow()
    fun mutateNav(transform: (NavState) -> NavState) = _navFlow.update(transform)

    /** §history-load-fix: per-session message-list mutation lock shared by the
     *  three load paths (launchLoadMessages / launchLoadMoreMessages /
     *  launchCatchUp) via [SliceFlows.messageLoadCoordinator]. Owned here (the
     *  store is @Singleton) so all callers share one lock map without extra DI
     *  plumbing and without changing this class's no-arg constructor (existing
     *  `SharedStateStore()` test constructions keep working). */
    val messageLoadCoordinator: MessageLoadCoordinator = MessageLoadCoordinator()

    /** Bundle view (preserves the [SliceFlows] data class for controller ctors). */
    val slices: SliceFlows = SliceFlows(this)
}
