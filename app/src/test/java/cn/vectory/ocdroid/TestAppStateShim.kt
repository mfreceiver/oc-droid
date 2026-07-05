package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.api.CommandInfo
import cn.vectory.ocdroid.data.model.AgentInfo
import cn.vectory.ocdroid.data.model.ComposerImageAttachment
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.TodoItem
import cn.vectory.ocdroid.ui.AppCore
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.ComposerState
import cn.vectory.ocdroid.ui.ConnectionState
import cn.vectory.ocdroid.ui.FileState
import cn.vectory.ocdroid.ui.GapInfo
import cn.vectory.ocdroid.ui.HostState
import cn.vectory.ocdroid.ui.MainViewModelTimings
import cn.vectory.ocdroid.ui.NavState
import cn.vectory.ocdroid.ui.SessionListState
import cn.vectory.ocdroid.ui.SettingsState
import cn.vectory.ocdroid.ui.TunnelActivationState
import cn.vectory.ocdroid.ui.TrafficState
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.UnreadState
import cn.vectory.ocdroid.util.MarkdownFontSizes
import cn.vectory.ocdroid.util.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * §R-17 batch3e: TEST-ONLY AppState shim. The production [AppState] class was
 * removed in R-17 batch3d/batch3e when slices became the sole authoritative
 * store. This data class mirrors the historical field set so the 6 per-domain
 * ViewModel test files (split out of the former MainViewModelTest) can keep
 * using the `updateState(viewModel) { it.copy(...) }` setup idiom without
 * rewriting every state-seed call.
 *
 * **How it works**: tests call [updateState] which (1) aggregates the current
 * slice values into an [AppState], (2) applies the test's `copy()` transform,
 * and (3) writes the resulting fields back to the slices. Reads via
 * [AppCoreTestStateShim.state] perform the same aggregation on demand so
 * `viewModel.state.value.X` keeps resolving.
 *
 * This shim MUST stay in the test source set — it must never leak into
 * production (production has no AppState).
 */
data class AppState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val serverVersion: String? = null,
    val sessions: List<Session> = emptyList(),
    val loadedSessionLimit: Int = MainViewModelTimings.sessionPageSize,
    val hasMoreSessions: Boolean = true,
    val isLoadingMoreSessions: Boolean = false,
    val isRefreshingSessions: Boolean = false,
    val expandedSessionIds: Set<String> = emptySet(),
    val currentSessionId: String? = null,
    val lastNavPage: Int = 0,
    val sessionStatuses: Map<String, SessionStatus> = emptyMap(),
    val messages: List<Message> = emptyList(),
    val partsByMessage: Map<String, List<Part>> = emptyMap(),
    val olderMessagesCursor: String? = null,
    val hasMoreMessages: Boolean = true,
    val isLoadingMessages: Boolean = false,
    val messagesLoadFailed: Boolean = false,
    val currentModel: Message.ModelInfo? = null,
    val agents: List<AgentInfo> = emptyList(),
    val selectedAgentName: String = "build",
    val providers: cn.vectory.ocdroid.data.model.ProvidersResponse? = null,
    val disabledModels: Set<String> = emptySet(),
    val uiFontScale: Float = 1f,
    val uiContentScale: Float = 1f,
    val pendingPermissions: List<PermissionRequest> = emptyList(),
    val pendingQuestions: List<QuestionRequest> = emptyList(),
    val inputText: String = "",
    val error: String? = null,
    val successMessage: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val markdownFontSizes: MarkdownFontSizes = MarkdownFontSizes(),
    val filePathToShowInFiles: String? = null,
    val filePreviewOriginRoute: String? = null,
    val fileBrowserOpen: Boolean = false,
    val fileBrowserWorkdir: String? = null,
    val streamingPartTexts: Map<String, String> = emptyMap(),
    val streamingReasoningPart: Part? = null,
    val sessionTodos: Map<String, List<TodoItem>> = emptyMap(),
    val sendingSessionIds: Set<String> = emptySet(),
    val imageAttachments: List<ComposerImageAttachment> = emptyList(),
    val hostProfiles: List<HostProfile> = emptyList(),
    val currentHostProfileId: String? = null,
    val connectionPhase: String? = null,
    val tunnelActivationState: TunnelActivationState = TunnelActivationState.Idle,
    val childSessions: Map<String, List<Session>> = emptyMap(),
    val directorySessions: Map<String, List<Session>> = emptyMap(),
    val openSessionIds: List<String> = emptyList(),
    val draftWorkdir: String? = null,
    val lastViewedTime: Map<String, Long> = emptyMap(),
    val unreadSessions: Set<String> = emptySet(),
    val tempClearedUnread: Set<String> = emptySet(),
    val availableCommands: List<CommandInfo> = emptyList(),
    val trafficSent: Long = 0L,
    val trafficReceived: Long = 0L,
    val gapInfo: GapInfo? = null,
    val staleNotice: Boolean = false,
    val isCompacting: Boolean = false,
    val compactStartedAt: Long = 0L,
    val refreshNonce: Long = 0L,
)

/**
 * Aggregate every slice on [AppCore] into a snapshot [AppState]. Tests that
 * historically read `viewModel.state.value.X` keep working — they get a fresh
 * aggregate on each `.value` read.
 *
 * **error / successMessage**: the historical AppState had two one-shot fields
 * that production migrated to [cn.vectory.ocdroid.ui.UiEvent] emissions on the
 * SharedEffectBus. The aggregate pulls the most recent emitted Error / Success
 * event out of [AppCore.recentTestErrors] / [AppCore.recentTestSuccesses] (a
 * test-only ring buffer populated by the collector installed in
 * [MainViewModelTestBase.setUp]). Tests that previously asserted
 * `state.value.error` keep working without rewriting to Turbine.
 */
fun AppCore.aggregateState(): AppState {
    val conn = connectionFlow.value
    val traffic = trafficFlow.value
    val composer = composerFlow.value
    val file = fileFlow.value
    val settings = settingsFlow.value
    val chat = chatFlow.value
    val sessionList = sessionListFlow.value
    val unread = unreadFlow.value
    val host = hostFlow.value
    val nav = navFlow.value
    return AppState(
        isConnected = conn.isConnected,
        isConnecting = conn.isConnecting,
        serverVersion = conn.serverVersion,
        sessions = sessionList.sessions,
        loadedSessionLimit = sessionList.loadedSessionLimit,
        hasMoreSessions = sessionList.hasMoreSessions,
        isLoadingMoreSessions = sessionList.isLoadingMoreSessions,
        isRefreshingSessions = sessionList.isRefreshingSessions,
        expandedSessionIds = sessionList.expandedSessionIds,
        currentSessionId = chat.currentSessionId,
        lastNavPage = nav.lastNavPage,
        sessionStatuses = sessionList.sessionStatuses,
        messages = chat.messages,
        partsByMessage = chat.partsByMessage,
        olderMessagesCursor = chat.olderMessagesCursor,
        hasMoreMessages = chat.hasMoreMessages,
        isLoadingMessages = chat.isLoadingMessages,
        currentModel = chat.currentModel,
        agents = settings.agents,
        selectedAgentName = settings.selectedAgentName,
        providers = settings.providers,
        disabledModels = settings.disabledModels,
        uiFontScale = settings.uiFontScale,
        uiContentScale = settings.uiContentScale,
        pendingPermissions = sessionList.pendingPermissions,
        pendingQuestions = sessionList.pendingQuestions,
        inputText = composer.inputText,
        themeMode = settings.themeMode,
        markdownFontSizes = settings.markdownFontSizes,
        filePathToShowInFiles = file.filePathToShowInFiles,
        filePreviewOriginRoute = file.filePreviewOriginRoute,
        fileBrowserOpen = file.fileBrowserOpen,
        fileBrowserWorkdir = file.fileBrowserWorkdir,
        streamingPartTexts = chat.streamingPartTexts,
        streamingReasoningPart = chat.streamingReasoningPart,
        sessionTodos = sessionList.sessionTodos,
        sendingSessionIds = composer.sendingSessionIds,
        imageAttachments = composer.imageAttachments,
        hostProfiles = host.hostProfiles,
        currentHostProfileId = host.currentHostProfileId,
        connectionPhase = conn.connectionPhase,
        tunnelActivationState = conn.tunnelActivationState,
        childSessions = sessionList.childSessions,
        directorySessions = sessionList.directorySessions,
        openSessionIds = sessionList.openSessionIds,
        draftWorkdir = composer.draftWorkdir,
        lastViewedTime = unread.lastViewedTime,
        unreadSessions = unread.unreadSessions,
        tempClearedUnread = unread.tempClearedUnread,
        availableCommands = settings.availableCommands,
        trafficSent = traffic.trafficSent,
        trafficReceived = traffic.trafficReceived,
        gapInfo = chat.gapInfo,
        staleNotice = chat.staleNotice,
        isCompacting = chat.isCompacting,
        compactStartedAt = chat.compactStartedAt,
        refreshNonce = chat.refreshNonce,
        // §R-17 batch3e: pull the most-recent one-shot UiEvent out of the
        // test-only ring buffer so historical `state.value.error` /
        // `state.value.successMessage` assertions keep working.
        error = recentTestErrors.lastOrNull(),
        successMessage = recentTestSuccesses.lastOrNull(),
    )
}

/** Write the [AppState] fields back to the per-slice MutableStateFlows. */
fun AppCore.syncSlicesFromAppState(state: AppState) {
    store.connectionFlow.value = ConnectionState(
        isConnected = state.isConnected,
        isConnecting = state.isConnecting,
        serverVersion = state.serverVersion,
        connectionPhase = state.connectionPhase,
        tunnelActivationState = state.tunnelActivationState,
    )
    store.trafficFlow.value = TrafficState(
        trafficSent = state.trafficSent,
        trafficReceived = state.trafficReceived,
    )
    store.composerFlow.value = ComposerState(
        inputText = state.inputText,
        imageAttachments = state.imageAttachments,
        sendingSessionIds = state.sendingSessionIds,
        draftWorkdir = state.draftWorkdir,
    )
    store.fileFlow.value = FileState(
        filePathToShowInFiles = state.filePathToShowInFiles,
        filePreviewOriginRoute = state.filePreviewOriginRoute,
        fileBrowserOpen = state.fileBrowserOpen,
        fileBrowserWorkdir = state.fileBrowserWorkdir,
    )
    store.settingsFlow.value = SettingsState(
        themeMode = state.themeMode,
        markdownFontSizes = state.markdownFontSizes,
        selectedAgentName = state.selectedAgentName,
        agents = state.agents,
        providers = state.providers,
        availableCommands = state.availableCommands,
        disabledModels = state.disabledModels,
        uiFontScale = state.uiFontScale,
        uiContentScale = state.uiContentScale,
    )
    store.chatFlow.value = ChatState(
        currentSessionId = state.currentSessionId,
        messages = state.messages,
        partsByMessage = state.partsByMessage,
        streamingPartTexts = state.streamingPartTexts,
        streamingReasoningPart = state.streamingReasoningPart,
        olderMessagesCursor = state.olderMessagesCursor,
        hasMoreMessages = state.hasMoreMessages,
        isLoadingMessages = state.isLoadingMessages,
        gapInfo = state.gapInfo,
        staleNotice = state.staleNotice,
        currentModel = state.currentModel,
        isCompacting = state.isCompacting,
        compactStartedAt = state.compactStartedAt,
        refreshNonce = state.refreshNonce,
    )
    store.sessionListFlow.value = SessionListState(
        sessions = state.sessions,
        sessionStatuses = state.sessionStatuses,
        expandedSessionIds = state.expandedSessionIds,
        loadedSessionLimit = state.loadedSessionLimit,
        hasMoreSessions = state.hasMoreSessions,
        isLoadingMoreSessions = state.isLoadingMoreSessions,
        isRefreshingSessions = state.isRefreshingSessions,
        pendingPermissions = state.pendingPermissions,
        pendingQuestions = state.pendingQuestions,
        childSessions = state.childSessions,
        directorySessions = state.directorySessions,
        openSessionIds = state.openSessionIds,
        sessionTodos = state.sessionTodos,
    )
    store.unreadFlow.value = UnreadState(
        unreadSessions = state.unreadSessions,
        tempClearedUnread = state.tempClearedUnread,
        lastViewedTime = state.lastViewedTime,
    )
    store.hostFlow.value = HostState(
        hostProfiles = state.hostProfiles,
        currentHostProfileId = state.currentHostProfileId,
    )
    store.navFlow.value = NavState(lastNavPage = state.lastNavPage)
}

/**
 * Test-only `updateState` helper. Mirrors the historical MainViewModel
 * `.updateState { copy(...) }` API: aggregates slices into an [AppState],
 * applies [transform], then writes the result back to the slices.
 *
 * **error / successMessage handling**: these are read-only one-shot fields
 * surfaced via [aggregateState] from the test-only UiEvent ring buffers
 * ([recentTestErrors] / [recentTestSuccesses]). Setting them via
 * `updateState { copy(error = ...) }` is silently ignored — production code
 * emits UiEvents via the SharedEffectBus, never writes them to slices.
 */
fun AppCore.updateState(transform: (AppState) -> AppState) {
    val next = transform(aggregateState())
    syncSlicesFromAppState(next)
}

/**
 * Test-only `state` shim. Returns a [StateFlow] snapshot of the aggregate
 * [AppState]. Tests that historically read `viewModel.state.value.X` keep
 * working — they get a fresh aggregate on each `.value` read.
 */
val AppCore.state: StateFlow<AppState>
    get() = MutableStateFlow(aggregateState()).asStateFlow()

// ── UiEvent ring buffers (test-only) ───────────────────────────────────────
//
// §R-17 batch3e: SharedFlow has no replay, so once an Error/Success event has
// been emitted it's gone — historical `state.value.error` assertions would
// always see null. We side-channel the events into per-AppCore ring buffers
// so [aggregateState] can surface the most-recent Error/Success as the
// AppState `error` / `successMessage` fields. Production NEVER sees these
// buffers (test-only `internal` extensions on AppCore; no production code
// reads them).

private val appCoreErrorBuffer = mutableMapOf<AppCore, MutableList<String>>()
private val appCoreSuccessBuffer = mutableMapOf<AppCore, MutableList<String>>()
private val appCoreCollectorJobs = mutableMapOf<AppCore, kotlinx.coroutines.Job>()

/** Most-recent-last list of Error event messages emitted on [uiEvents]. */
internal val AppCore.recentTestErrors: List<String>
    get() = synchronized(appCoreErrorBuffer) { appCoreErrorBuffer[this]?.toList() ?: emptyList() }

/** Most-recent-last list of Success event messages emitted on [uiEvents]. */
internal val AppCore.recentTestSuccesses: List<String>
    get() = synchronized(appCoreSuccessBuffer) { appCoreSuccessBuffer[this]?.toList() ?: emptyList() }

/**
 * Install a coroutine collector on [uiEvents] that records every Error /
 * Success event into the per-AppCore ring buffers. Idempotent — multiple
 * calls on the same AppCore share one collector. The collector is launched
 * on [scope] (typically the runTest scope's Main dispatcher).
 *
 * Tests do NOT call this directly — [MainViewModelTestBase.createCore]
 * wires it up automatically when each test constructs an [AppCore].
 */
internal fun AppCore.installTestUiEventCollector(
    scope: kotlinx.coroutines.CoroutineScope,
) {
    synchronized(appCoreCollectorJobs) {
        if (appCoreCollectorJobs.containsKey(this)) return
    }
    synchronized(appCoreErrorBuffer) { appCoreErrorBuffer.getOrPut(this) { mutableListOf() } }
    synchronized(appCoreSuccessBuffer) { appCoreSuccessBuffer.getOrPut(this) { mutableListOf() } }
    val job = scope.launch {
        uiEvents.collect { event ->
            when (event) {
                is UiEvent.Error -> synchronized(appCoreErrorBuffer) {
                    appCoreErrorBuffer.getOrPut(this@installTestUiEventCollector) { mutableListOf() }.add(event.message)
                }
                is UiEvent.Success -> synchronized(appCoreSuccessBuffer) {
                    appCoreSuccessBuffer.getOrPut(this@installTestUiEventCollector) { mutableListOf() }.add(event.message)
                }
            }
        }
    }
    synchronized(appCoreCollectorJobs) { appCoreCollectorJobs[this] = job }
}

/** Drop the per-AppCore collector state. Called from @After cleanup. */
internal fun AppCore.uninstallTestUiEventCollector() {
    synchronized(appCoreCollectorJobs) {
        appCoreCollectorJobs.remove(this)?.cancel()
    }
    synchronized(appCoreErrorBuffer) { appCoreErrorBuffer.remove(this) }
    synchronized(appCoreSuccessBuffer) { appCoreSuccessBuffer.remove(this) }
}
