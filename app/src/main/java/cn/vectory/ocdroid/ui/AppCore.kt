package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.*
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.di.AppLifecycleMonitor
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.TrafficTracker
import cn.vectory.ocdroid.ui.controller.ComposerController
import cn.vectory.ocdroid.ui.controller.ConnectionCoordinator
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import cn.vectory.ocdroid.ui.controller.ForegroundCatchUpController
import cn.vectory.ocdroid.ui.controller.HostProfileController
import cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator
import cn.vectory.ocdroid.ui.controller.SessionSwitcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Success message shown on successful tunnel activation. HostProfileController
 *  emits this as a [UiEvent.Success] (NOT an Error) so ChatScreen's
 *  success-snackbar branch renders it as a positive toast. The sticky
 *  `tunnelActivationState` (Success) separately drives the ServerManagementDialog
 *  indicator. Kept as a named constant so the success-toast text and the
 *  dialog state stay decoupled. */
const val TUNNEL_SUCCESS_TOAST = "隧道激活成功"

/**
 * R-17 batch3 → batch3d: application-scoped engine that owns the 6 controllers
 * + the cross-domain orchestration logic (only ~6 methods that span 3+ domains).
 *
 * **batch3d redesign**: the 6 domain ViewModels ([ChatViewModel],
 * [SessionViewModel], [ConnectionViewModel], [HostViewModel],
 * [ComposerViewModel], [OrchestratorViewModel]) now PHYSICALLY OWN their
 * domain method bodies (moved here). Each VM reaches its domain controller +
 * the shared [store] + the [effectBus] directly — no `core.<method>()`
 * self-bypass. AppCore retains only:
 *
 *  - constructor (builds the 6 controllers + the app-lifetime [appScope])
 *  - [init] (loads saved settings + subscribes to the [effectBus] and
 *    dispatches each [ControllerEffect] to the matching helper below)
 *  - [dispatchEffect] (the effect-bus → controller/method router)
 *  - the ~6 genuinely cross-domain orchestration methods:
 *    [sendMessage] (composer→chat→session creation),
 *    [openSessionFromDeepLink] (nav→session→chat),
 *    [executeCommand] (/clear → composer+session),
 *    [resetLocalDataAndResync] (full-stack reset).
 *  - private/internal dispatch helpers (one per [ControllerEffect] branch —
 *    each calls the same controller / free function the matching VM method
 *    uses; AppCore cannot reference the VMs because Hilt ViewModels are not
 *    @Inject-able dependencies).
 *  - [cleanup] (ProcessLifecycleOwner teardown).
 *
 * The slice public read accessors (`chatFlow`, `sessionListFlow`, ...) stay
 * so legacy subscribers (composables mid-migration, tests, [uiEvents]) keep
 * resolving; writes flow through the [store] / [writeXxx] helpers which are
 * `internal` so the VMs share the same authoritative slice.
 *
 * The 6 HiltViewModels inject this class and expose ONLY their domain surface
 * to composables. Composables inject those VMs (NOT this class).
 */
@Singleton
@OptIn(FlowPreview::class)
class AppCore @Inject constructor(
    internal val store: SharedStateStore,
    internal val repository: OpenCodeRepository,
    internal val settingsManager: SettingsManager,
    internal val hostProfileStore: HostProfileStore,
    internal val trafficTracker: TrafficTracker,
    private val appLifecycleMonitor: AppLifecycleMonitor,
    private val serverCompatProfile: cn.vectory.ocdroid.data.repository.ServerCompatProfile,
    /** R-17 batch3: shared bus for cross-VM effect dispatch. Controllers emit
     *  [ControllerEffect]s on [SharedEffectBus.effects]; this class collects
     *  them in its [init] block and dispatches. UiEvents ride
     *  [SharedEffectBus.uiEvents]. */
    internal val effectBus: SharedEffectBus,
) {

    /** App-process-lifetime scope (replaces the former viewModelScope). */
    internal val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ── Slice accessors (delegate to SharedStateStore) ──────────────────────
    private val _connectionFlow get() = store.connectionFlow
    private val _trafficFlow get() = store.trafficFlow
    private val _composerFlow get() = store.composerFlow
    private val _fileFlow get() = store.fileFlow
    private val _settingsFlow get() = store.settingsFlow
    private val _chatFlow get() = store.chatFlow
    private val _sessionListFlow get() = store.sessionListFlow
    private val _unreadFlow get() = store.unreadFlow
    private val _hostFlow get() = store.hostFlow
    private val _expandedParts get() = store.expandedParts
    private val _navFlow get() = store.navFlow
    private val sliceFlows get() = store.slices

    val connectionFlow: StateFlow<ConnectionState> get() = _connectionFlow.asStateFlow()
    val trafficFlow: StateFlow<TrafficState> get() = _trafficFlow.asStateFlow()
    val composerFlow: StateFlow<ComposerState> get() = _composerFlow.asStateFlow()
    val fileFlow: StateFlow<FileState> get() = _fileFlow.asStateFlow()
    val settingsFlow: StateFlow<SettingsState> get() = _settingsFlow.asStateFlow()
    val chatFlow: StateFlow<ChatState> get() = _chatFlow.asStateFlow()
    val sessionListFlow: StateFlow<SessionListState> get() = _sessionListFlow.asStateFlow()
    val unreadFlow: StateFlow<UnreadState> get() = _unreadFlow.asStateFlow()
    val hostFlow: StateFlow<HostState> get() = _hostFlow.asStateFlow()
    val expandedParts: StateFlow<Map<String, Boolean>> get() = _expandedParts.asStateFlow()
    val navFlow: StateFlow<NavState> get() = _navFlow.asStateFlow()

    val uiEvents: SharedFlow<UiEvent> get() = effectBus.uiEventsConsumed

    // ── Controllers (constructed once; `internal` so the 6 VMs reach them) ──
    internal val foregroundCatchUpController: ForegroundCatchUpController =
        ForegroundCatchUpController(
            appLifecycleMonitor = appLifecycleMonitor,
            scope = appScope,
            chatFlow = _chatFlow,
            composerFlow = _composerFlow,
            settingsManager = settingsManager,
            effects = effectBus,
        )

    internal val composerController: ComposerController =
        ComposerController(
            composerFlow = _composerFlow,
            chatFlow = _chatFlow,
            expandedParts = _expandedParts,
            settingsManager = settingsManager,
        )

    internal val sessionSwitcher: SessionSwitcher =
        SessionSwitcher(
            composerFlow = _composerFlow,
            expandedParts = _expandedParts,
            slices = sliceFlows,
            settingsManager = settingsManager,
            repository = repository,
            effects = effectBus,
        )

    internal val hostProfileController: HostProfileController =
        HostProfileController(
            scope = appScope,
            slices = sliceFlows,
            hostProfileStore = hostProfileStore,
            repository = repository,
            settingsManager = settingsManager,
            trafficTracker = trafficTracker,
            effects = effectBus,
        )

    internal val sessionSyncCoordinator: SessionSyncCoordinator =
        SessionSyncCoordinator(
            scope = appScope,
            slices = sliceFlows,
            settingsManager = settingsManager,
            effects = effectBus,
        )

    internal val connectionCoordinator: ConnectionCoordinator =
        ConnectionCoordinator(
            scope = appScope,
            connectionFlow = _connectionFlow,
            settingsFlow = _settingsFlow,
            slices = sliceFlows,
            repository = repository,
            settingsManager = settingsManager,
            effects = effectBus,
            serverCompatProfile = serverCompatProfile,
        )

    // ── Slice write helpers (`internal` so the 6 VMs share the same store) ──
    internal fun writeConnection(transform: (ConnectionState) -> ConnectionState) = _connectionFlow.update(transform)
    internal fun writeTraffic(transform: (TrafficState) -> TrafficState) = _trafficFlow.update(transform)
    internal fun writeComposer(transform: (ComposerState) -> ComposerState) = _composerFlow.update(transform)
    internal fun writeFile(transform: (FileState) -> FileState) = _fileFlow.update(transform)
    internal fun writeSettings(transform: (SettingsState) -> SettingsState) = _settingsFlow.update(transform)
    internal fun writeChat(transform: (ChatState) -> ChatState) = _chatFlow.update(transform)
    internal fun writeSessionList(transform: (SessionListState) -> SessionListState) = _sessionListFlow.update(transform)
    internal fun writeUnread(transform: (UnreadState) -> UnreadState) = _unreadFlow.update(transform)
    internal fun writeHost(transform: (HostState) -> HostState) = _hostFlow.update(transform)
    internal fun writeSessionWindow(sessionId: String, window: CachedSessionWindow) {
        sessionSwitcher.writeSessionWindow(sessionId, window)
    }

    init {
        applySavedSettings(repository, settingsManager, hostProfileStore, _connectionFlow, _settingsFlow, sliceFlows)
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            uiEvents.collect { event ->
                if (event is UiEvent.Error) appLifecycleMonitor.onAppError(event.message)
            }
        }
        // R-17 batch3b: subscribe to controller effects BEFORE any external
        // caller can drive a controller. UNDISPATCHED so the collector is
        // registered synchronously here, before the constructor returns.
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            effectBus.effects.collect { effect -> dispatchEffect(effect) }
        }
    }

    /**
     * R-17 batch3b → batch3d: routes a single [ControllerEffect] emitted by any
     * of the 6 controllers to the matching helper here. Each branch is a thin
     * call to the same controller / free function the corresponding VM method
     * uses (AppCore cannot call the VMs directly because Hilt ViewModels are
     * not @Inject-able).
     */
    private fun dispatchEffect(effect: ControllerEffect) {
        when (effect) {
            // ── ForegroundCatchUpController ──
            is ControllerEffect.ForceReconnect -> connectionCoordinator.testConnection(force = true)
            is ControllerEffect.GlobalColdStartRefresh -> performGlobalColdStartRefresh(currentId = effect.sessionId)
            is ControllerEffect.CancelSse -> {
                connectionCoordinator.cancelSse()
                sessionSyncCoordinator.clearDeltaBuffers()
            }
            is ControllerEffect.CatchUpAfterDisconnect ->
                catchUpAfterDisconnectOrForeground(effect.sessionId)

            // ── SessionSwitcher ──
            is ControllerEffect.LoadMessages -> loadMessagesForEffect(effect.sessionId, effect.resetLimit)
            is ControllerEffect.LoadChildSessions -> launchLoadChildSessions(appScope, repository, sliceFlows, effect.sessionId, TAG)
            is ControllerEffect.LoadSessionStatus -> launchLoadSessionStatus(appScope, repository, sliceFlows)
            is ControllerEffect.LoadPendingQuestions -> launchLoadPendingQuestions(appScope, repository, sliceFlows, TAG)
            is ControllerEffect.ClearDeltaBuffers -> sessionSyncCoordinator.clearDeltaBuffers()

            // ── HostProfileController ──
            is ControllerEffect.CancelSseForReconfigure -> connectionCoordinator.cancelSseForReconfigure()
            is ControllerEffect.StartSse -> connectionCoordinator.startSSE()
            is ControllerEffect.HostProfileSwitched -> applyReloadDisabledModelsForCurrentHost(settingsManager, hostProfileStore, sliceFlows)
            is ControllerEffect.ColdStartReconnect -> connectionCoordinator.coldStartReconnect()
            is ControllerEffect.ResetLocalDataAndResync -> resetLocalDataAndResync()
            is ControllerEffect.ClearSessionWindowCache -> sessionSwitcher.clearSessionWindowCache()

            // ── ConnectionCoordinator ──
            is ControllerEffect.HostReconfigured -> foregroundCatchUpController.onHostReconfigured()
            is ControllerEffect.LoadSessions -> loadSessionsForEffect()
            is ControllerEffect.LoadAgents -> launchLoadAgents(appScope, repository, sliceFlows, settingsManager, TAG)
            is ControllerEffect.LoadProviders -> launchLoadProviders(appScope, repository, _settingsFlow, settingsManager, hostProfileStore) { message, error ->
                reportNonFatalIssue(TAG, message, error)
            }
            is ControllerEffect.LoadPendingPermissions -> launchLoadPendingPermissions(appScope, repository, sliceFlows, TAG)
            is ControllerEffect.OnSseEvent -> sessionSyncCoordinator.handleEvent(effect.event)

            // ── SessionSyncCoordinator ──
            is ControllerEffect.ServerConnected -> foregroundCatchUpController.onServerConnected()
            is ControllerEffect.RefreshSessions -> loadSessionsForEffect()
        }
    }


    // ── Test hooks (kept `internal` so MainViewModelTest's reflection-free
    //  call sites keep resolving; production never calls these directly). ────

    /**
     * Test hook: routes a single SSE event to [sessionSyncCoordinator.handleEvent].
     * Production code goes through the SSE collection coroutine inside
     * [ConnectionCoordinator] (which emits [ControllerEffect.OnSseEvent] for
     * each event, dispatched back to [sessionSyncCoordinator] by
     * [dispatchEffect]).
     */
    internal fun handleSSEEvent(event: SSEEvent) {
        sessionSyncCoordinator.handleEvent(event)
    }

    internal fun sessionWindowCacheSize(): Int = sessionSwitcher.sessionWindowCacheSize()
    internal fun peekSessionWindow(sessionId: String): CachedSessionWindow? = sessionSwitcher.peekSessionWindow(sessionId)


    /**
     * Teardown — cancels SSE, drops delta buffers, and cancels [appScope].
     * Invoked from [OpenCodeApp.onTerminate] (best-effort; the framework does
     * not guarantee onTerminate is called, but OS process death reclaims all
     * resources regardless). This hook exists for future multi-Activity or
     * explicit-reset scenarios where AppCore state must be manually cleared.
     */
    fun cleanup() {
        sessionSyncCoordinator.clearDeltaBuffers()
        connectionCoordinator.cancelSse()
        appScope.cancel()
    }

    private companion object {
        private const val TAG = "AppCore"
    }
}
