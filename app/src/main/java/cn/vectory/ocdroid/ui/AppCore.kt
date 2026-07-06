package cn.vectory.ocdroid.ui

import android.content.Context
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
import dagger.hilt.android.qualifiers.ApplicationContext
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

/**
 * §R18 Phase 2-G: this constant was the hardcoded success-toast text for
 * tunnel activation. The toast now rides `UiEvent.Success(R.string
 * .success_tunnel_activated)` (i18n'd). The constant is retained only as
 * a documentation anchor — production code MUST NOT reference it (it has
 * no runtime value post-i18n). Tests assert on the resId directly.
 */
@Deprecated("Use R.string.success_tunnel_activated via UiEvent.Success(resId).")
const val TUNNEL_SUCCESS_TOAST: String = "隧道激活成功"

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
    /** §R18 Phase 2-G (P0-6): application Context used to resolve
     *  [UiEvent.Error]'s `@StringRes resId` + args to a localized String
     *  before forwarding to [AppLifecycleMonitor.onAppError] (whose
     *  notification body needs a real String). Composable collectors
     *  resolve via [LocalContext] instead; this Context serves only the
     *  app-lifetime notification path that has no Composition available. */
    @ApplicationContext private val appContext: Context,
) {

    /** App-process-lifetime scope (replaces the former viewModelScope). */
    internal val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ── Slice accessors (delegate to SharedStateStore) ──────────────────────
    // §R18 Phase 4 (P0-9): SharedStateStore now owns private MutableStateFlows
    // + public read-only StateFlow views + public mutateXxx write funnels.
    // These accessors re-expose the read views (StateFlow) and the write
    // helpers (delegating to store.mutateXxx) so the 6 VMs / orchestration
    // extensions keep resolving unchanged.
    val connectionFlow: StateFlow<ConnectionState> get() = store.connectionFlow
    val trafficFlow: StateFlow<TrafficState> get() = store.trafficFlow
    val composerFlow: StateFlow<ComposerState> get() = store.composerFlow
    val fileFlow: StateFlow<FileState> get() = store.fileFlow
    val settingsFlow: StateFlow<SettingsState> get() = store.settingsFlow
    val chatFlow: StateFlow<ChatState> get() = store.chatFlow
    val sessionListFlow: StateFlow<SessionListState> get() = store.sessionListFlow
    val unreadFlow: StateFlow<UnreadState> get() = store.unreadFlow
    val hostFlow: StateFlow<HostState> get() = store.hostFlow
    val expandedParts: StateFlow<Map<String, Boolean>> get() = store.expandedParts
    val navFlow: StateFlow<NavState> get() = store.navFlow

    val uiEvents: SharedFlow<UiEvent> get() = effectBus.uiEventsConsumed

    // ── Controllers (constructed once; `internal` so the 6 VMs reach them) ──
    internal val foregroundCatchUpController: ForegroundCatchUpController =
        ForegroundCatchUpController(
            appLifecycleMonitor = appLifecycleMonitor,
            scope = appScope,
            store = store,
            settingsManager = settingsManager,
            effects = effectBus,
        )

    internal val composerController: ComposerController =
        ComposerController(
            store = store,
            settingsManager = settingsManager,
        )

    internal val sessionSwitcher: SessionSwitcher =
        SessionSwitcher(
            store = store,
            settingsManager = settingsManager,
            repository = repository,
            effects = effectBus,
        )

    internal val hostProfileController: HostProfileController =
        HostProfileController(
            scope = appScope,
            slices = store.slices,
            hostProfileStore = hostProfileStore,
            repository = repository,
            settingsManager = settingsManager,
            trafficTracker = trafficTracker,
            effects = effectBus,
        )

    internal val sessionSyncCoordinator: SessionSyncCoordinator =
        SessionSyncCoordinator(
            scope = appScope,
            slices = store.slices,
            settingsManager = settingsManager,
            effects = effectBus,
        )

    internal val connectionCoordinator: ConnectionCoordinator =
        ConnectionCoordinator(
            scope = appScope,
            slices = store.slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effectBus,
            serverCompatProfile = serverCompatProfile,
        )

    // ── Slice write helpers (delegate to SharedStateStore.mutateXxx). ───────
    // Kept `internal` so the 6 VMs / orchestration extensions keep resolving
    // unchanged. The single authoritative writer per slice is now
    // SharedStateStore.mutateXxx; these are thin pass-throughs.
    internal fun writeConnection(transform: (ConnectionState) -> ConnectionState) = store.mutateConnection(transform)
    internal fun writeTraffic(transform: (TrafficState) -> TrafficState) = store.mutateTraffic(transform)
    internal fun writeComposer(transform: (ComposerState) -> ComposerState) = store.mutateComposer(transform)
    internal fun writeFile(transform: (FileState) -> FileState) = store.mutateFile(transform)
    internal fun writeSettings(transform: (SettingsState) -> SettingsState) = store.mutateSettings(transform)
    internal fun writeChat(transform: (ChatState) -> ChatState) = store.mutateChat(transform)
    internal fun writeSessionList(transform: (SessionListState) -> SessionListState) = store.mutateSessionList(transform)
    internal fun writeUnread(transform: (UnreadState) -> UnreadState) = store.mutateUnread(transform)
    internal fun writeHost(transform: (HostState) -> HostState) = store.mutateHost(transform)
    internal fun writeSessionWindow(sessionId: String, window: CachedSessionWindow) {
        sessionSwitcher.writeSessionWindow(sessionId, window)
    }

    init {
        applySavedSettings(repository, settingsManager, hostProfileStore, store.slices)
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            // §R18 Phase 2-G (P0-6): UiEvent.Error now carries a `@StringRes`
            // resId + format args instead of a hardcoded String. Resolve to
            // a localized String here (the only app-lifetime UiEvent consumer
            // without a Composition); the in-app snackbar is rendered by
            // ChatScreen, which resolves via its own LocalContext.
            uiEvents.collect { event ->
                if (event is UiEvent.Error) {
                    val message = appContext.getString(event.resId, *event.args.toTypedArray())
                    appLifecycleMonitor.onAppError(message)
                }
            }
        }
        // R-17 batch3b: subscribe to controller effects BEFORE any external
        // caller can drive a controller. UNDISPATCHED so the collector is
        // registered synchronously here, before the constructor returns.
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            effectBus.effects.collect { effect -> dispatchEffect(effect) }
        }

        // §R18 Phase 2-F: currentSessionId convergence. ChatState
        // (chatFlow.currentSessionId) is the sole runtime source; the
        // SettingsManager is a cold-start seed + a persistence side-effect.
        //
        // Cold-start seed: applySavedSettings already seeded chatFlow above;
        // this is a fallback for when applySavedSettings did not run or left
        // currentSessionId null while a persisted id exists. Runs synchronously
        // (inline) BEFORE the collector below is registered, so the collector
        // sees the seeded value as the starting point (not the pre-seed null).
        val persistedSid = settingsManager.currentSessionId
        if (persistedSid != null && store.chatFlow.value.currentSessionId == null) {
            store.mutateChat { it.copy(currentSessionId = persistedSid) }
        }

        // Persistence side-effect: every non-null change of chatFlow's
        // currentSessionId is written back to SettingsManager so the next cold
        // start can re-seed. `filterNotNull()` prevents the collector from
        // reading the initial null (before the seed lands) and overwriting the
        // seed; it also means null-clearing transitions (host switch / close /
        // delete / archive of the current session) are NOT persisted here —
        // those are handled by the explicit chatFlow.clear + applySavedSettings
        // archived-id filter on the next cold start. `distinctUntilChanged`
        // avoids redundant writes. UNDISPATCHED so the collector is registered
        // before the constructor returns (same rationale as the two collectors
        // above).
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.chatFlow.map { it.currentSessionId }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { id -> settingsManager.currentSessionId = id }
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
            is ControllerEffect.LoadChildSessions -> launchLoadChildSessions(appScope, repository, store.slices, effect.sessionId, TAG)
            is ControllerEffect.LoadSessionStatus -> launchLoadSessionStatus(appScope, repository, store.slices)
            is ControllerEffect.LoadPendingQuestions ->
                // §R18 Phase 3 Wave 3 (P1-9 wire-up): production now uses the
                // multi-workdir fan-out added in Wave 1. The single-workdir
                // `launchLoadPendingQuestions(..., settingsManager.currentWorkdir, ...)`
                // path was dropping pending questions for any background workdir
                // (a `question.asked` SSE event for a non-current workdir was
                // fetched-then-overwritten by the next currentWorkdir poll).
                // The coordinator owns `slices` (directorySessions keys) +
                // `settingsManager` (currentWorkdir), so it computes the
                // workdir set internally — only the repository is passed in.
                sessionSyncCoordinator.loadPendingQuestionsAllWorkdirs(repository)
            is ControllerEffect.ClearDeltaBuffers -> sessionSyncCoordinator.clearDeltaBuffers()

            // ── HostProfileController ──
            is ControllerEffect.CancelSseForReconfigure -> connectionCoordinator.cancelSseForReconfigure()
            is ControllerEffect.StartSse -> connectionCoordinator.startSSE()
            is ControllerEffect.HostProfileSwitched -> applyReloadDisabledModelsForCurrentHost(settingsManager, hostProfileStore, store.slices)
            is ControllerEffect.ColdStartReconnect -> connectionCoordinator.coldStartReconnect()
            is ControllerEffect.ResetLocalDataAndResync -> resetLocalDataAndResync()
            is ControllerEffect.ClearSessionWindowCache -> sessionSwitcher.clearSessionWindowCache()

            // ── ConnectionCoordinator ──
            is ControllerEffect.HostReconfigured -> foregroundCatchUpController.onHostReconfigured()
            is ControllerEffect.LoadSessions -> loadSessionsForEffect()
            is ControllerEffect.LoadAgents -> launchLoadAgents(appScope, repository, store.slices, settingsManager, TAG)
            is ControllerEffect.LoadProviders -> launchLoadProviders(appScope, repository, store.slices, settingsManager, hostProfileStore) { message, error ->
                reportNonFatalIssue(TAG, message, error)
            }
            is ControllerEffect.LoadPendingPermissions -> launchLoadPendingPermissions(appScope, repository, store.slices, TAG)
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
