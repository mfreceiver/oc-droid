package cn.vectory.ocdroid.ui

import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * §R-17 Stage 1 (consumer-migration enabler): container holding all nine slice
 * `MutableStateFlow`s so that free helpers (`launch*` / `handle*`) can
 * synchronise EVERY slice after writing the AppState mirror — without each
 * helper taking nine separate flow params. Built once in MainViewModel and
 * passed (as `slices`) to every free helper that writes AppState.
 */
internal data class SliceFlows(
    val connection: MutableStateFlow<ConnectionState>,
    val traffic: MutableStateFlow<TrafficState>,
    val composer: MutableStateFlow<ComposerState>,
    val file: MutableStateFlow<FileState>,
    val settings: MutableStateFlow<SettingsState>,
    val chat: MutableStateFlow<ChatState>,
    val sessionList: MutableStateFlow<SessionListState>,
    val unread: MutableStateFlow<UnreadState>,
    val host: MutableStateFlow<HostState>
)

/**
 * §R-17 Stage 1: mirror every slice from an AppState snapshot. Extracted from
 * MainViewModel.updateState's inline sync so free helpers can call it after
 * `state.updateAndSync(slices) { ... }`. `MutableStateFlow`'s distinctUntilChanged means only
 * slices whose fields actually changed emit — so calling this on every write
 * still gives per-slice isolation once consumers subscribe to a single slice.
 */
@Suppress("DEPRECATION")
internal fun syncSlicesFromAppState(state: AppState, slices: SliceFlows) {
    check(Looper.myLooper() === Looper.getMainLooper()) { "syncSlicesFromAppState must be called on the main thread" }
    slices.connection.value = ConnectionState(
        isConnected = state.isConnected,
        isConnecting = state.isConnecting,
        serverVersion = state.serverVersion,
        connectionPhase = state.connectionPhase,
        tunnelActivationState = state.tunnelActivationState
    )
    slices.traffic.value = TrafficState(
        trafficSent = state.trafficSent,
        trafficReceived = state.trafficReceived
    )
    slices.composer.value = ComposerState(
        inputText = state.inputText,
        imageAttachments = state.imageAttachments,
        sendingSessionIds = state.sendingSessionIds,
        draftWorkdir = state.draftWorkdir
    )
    slices.file.value = FileState(
        filePathToShowInFiles = state.filePathToShowInFiles,
        filePreviewOriginRoute = state.filePreviewOriginRoute,
        fileBrowserOpen = state.fileBrowserOpen,
        fileBrowserWorkdir = state.fileBrowserWorkdir
    )
    slices.settings.value = SettingsState(
        themeMode = state.themeMode,
        markdownFontSizes = state.markdownFontSizes,
        selectedAgentName = state.selectedAgentName,
        agents = state.agents,
        providers = state.providers,
        availableCommands = state.availableCommands,
        disabledModels = state.disabledModels,
        uiFontScale = state.uiFontScale,
        uiContentScale = state.uiContentScale
    )
    // §slice-only-preserve (glm-1 🔴 / gpt-1): ChatState carries three fields
    // that are NOT mirrored to AppState (isCompacting, compactStartedAt,
    // refreshNonce) because they're write-only-to-slice by design. Rebuilding
    // ChatState wholesale here via `ChatState(...)` reset those to their
    // defaults on every updateState call — clobbering performGlobalColdStartRefresh's
    // refreshNonce bump (the §3 scroll-clear signal never reached ChatScreen)
    // and latently breaking isCompacting (any SSE updateState during compaction
    // would flip the capsule off). Use .copy() so only AppState-represented
    // fields are overwritten; slice-only fields keep their current value.
    slices.chat.value = slices.chat.value.copy(
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
        currentModel = state.currentModel
    )
    slices.sessionList.value = SessionListState(
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
        sessionTodos = state.sessionTodos
    )
    slices.unread.value = UnreadState(
        unreadSessions = state.unreadSessions,
        tempClearedUnread = state.tempClearedUnread,
        lastViewedTime = state.lastViewedTime
    )
    slices.host.value = HostState(
        hostProfiles = state.hostProfiles,
        currentHostProfileId = state.currentHostProfileId
    )
}

/**
 * §R-17 M5: build a transient [AppState] snapshot from the authoritative slice
 * values, carrying over the persisted [AppState.error] / [AppState.lastNavPage]
 * from [seed]. Used by [updateAndSync] so the legacy `(AppState) -> AppState`
 * transforms keep working against slice-derived values without each call site
 * needing a per-slice rewrite. Mirrors [MainViewModel.aggregateFromSlices].
 */
@Suppress("DEPRECATION")
internal fun aggregateFromSlices(slices: SliceFlows, seed: AppState): AppState = seed.copy(
    isConnected = slices.connection.value.isConnected,
    isConnecting = slices.connection.value.isConnecting,
    serverVersion = slices.connection.value.serverVersion,
    connectionPhase = slices.connection.value.connectionPhase,
    tunnelActivationState = slices.connection.value.tunnelActivationState,
    trafficSent = slices.traffic.value.trafficSent,
    trafficReceived = slices.traffic.value.trafficReceived,
    inputText = slices.composer.value.inputText,
    imageAttachments = slices.composer.value.imageAttachments,
    sendingSessionIds = slices.composer.value.sendingSessionIds,
    draftWorkdir = slices.composer.value.draftWorkdir,
    filePathToShowInFiles = slices.file.value.filePathToShowInFiles,
    filePreviewOriginRoute = slices.file.value.filePreviewOriginRoute,
    fileBrowserOpen = slices.file.value.fileBrowserOpen,
    fileBrowserWorkdir = slices.file.value.fileBrowserWorkdir,
    themeMode = slices.settings.value.themeMode,
    markdownFontSizes = slices.settings.value.markdownFontSizes,
    selectedAgentName = slices.settings.value.selectedAgentName,
    agents = slices.settings.value.agents,
    providers = slices.settings.value.providers,
    availableCommands = slices.settings.value.availableCommands,
    disabledModels = slices.settings.value.disabledModels,
    uiFontScale = slices.settings.value.uiFontScale,
    uiContentScale = slices.settings.value.uiContentScale,
    currentSessionId = slices.chat.value.currentSessionId,
    messages = slices.chat.value.messages,
    partsByMessage = slices.chat.value.partsByMessage,
    streamingPartTexts = slices.chat.value.streamingPartTexts,
    streamingReasoningPart = slices.chat.value.streamingReasoningPart,
    olderMessagesCursor = slices.chat.value.olderMessagesCursor,
    hasMoreMessages = slices.chat.value.hasMoreMessages,
    isLoadingMessages = slices.chat.value.isLoadingMessages,
    gapInfo = slices.chat.value.gapInfo,
    staleNotice = slices.chat.value.staleNotice,
    currentModel = slices.chat.value.currentModel,
    sessions = slices.sessionList.value.sessions,
    sessionStatuses = slices.sessionList.value.sessionStatuses,
    expandedSessionIds = slices.sessionList.value.expandedSessionIds,
    loadedSessionLimit = slices.sessionList.value.loadedSessionLimit,
    hasMoreSessions = slices.sessionList.value.hasMoreSessions,
    isLoadingMoreSessions = slices.sessionList.value.isLoadingMoreSessions,
    isRefreshingSessions = slices.sessionList.value.isRefreshingSessions,
    pendingPermissions = slices.sessionList.value.pendingPermissions,
    pendingQuestions = slices.sessionList.value.pendingQuestions,
    childSessions = slices.sessionList.value.childSessions,
    directorySessions = slices.sessionList.value.directorySessions,
    openSessionIds = slices.sessionList.value.openSessionIds,
    sessionTodos = slices.sessionList.value.sessionTodos,
    unreadSessions = slices.unread.value.unreadSessions,
    tempClearedUnread = slices.unread.value.tempClearedUnread,
    lastViewedTime = slices.unread.value.lastViewedTime,
    hostProfiles = slices.host.value.hostProfiles,
    currentHostProfileId = slices.host.value.currentHostProfileId
)

/**
 * §R-17 Stage 1→M5: applies a legacy `(AppState) -> AppState` transform against
 * a TRANSIENT aggregate built from the authoritative slices, then pushes the
 * result back to the slices via [syncSlicesFromAppState]. The full `after`
 * AppState is also written back to the flow so `_state` stays a synchronised
 * mirror of the slices (the free helpers here still read `state.value.<field>`,
 * and CatchUpGapTest uses the null-slices path — see M5.2 tech debt). `slices`
 * is nullable so legacy callers that invoke helpers without a SliceFlows
 * instance keep compiling (the null path just applies the transform to the
 * persisted AppState).
 *
 * §R-17 M5.1 (kimo 🟠#2) THREAD-SAFETY: the aggregate→transform→write→sync
 * sequence is NOT atomic. It is only safe because every call site runs on
 * `Dispatchers.Main.immediate` (single-threaded). Calling this from a
 * background thread would let a concurrent writer interleave between the
 * aggregate read and the slice/mirror write, breaking slice↔mirror
 * consistency. MUST be invoked on `Dispatchers.Main.immediate`.
 */
@Suppress("DEPRECATION")
internal fun MutableStateFlow<AppState>.updateAndSync(
    slices: SliceFlows?,
    transform: (AppState) -> AppState
) {
    check(Looper.myLooper() === Looper.getMainLooper()) { "updateAndSync must be called on the main thread" }
    if (slices == null) {
        value = transform(value)
        return
    }
    val before = aggregateFromSlices(slices, value)
    val after = transform(before)
    // §R-17 M5: slices authoritative (aggregate built from them). State kept in
    // sync so free helpers that read `state.value.<field>` keep working.
    value = after
    syncSlicesFromAppState(after, slices)
}

/**
 * §R-17 M3: top-level mirror writers for the composer/settings slices. These
 * duplicate the field lists of [MainViewModel.writeComposer] /
 * [MainViewModel.writeSettings] because the `launch*` helpers in this file
 * are free functions that receive `state: MutableStateFlow<AppState>` (plus
 * the slice flow) and cannot call the VM's private `writeXxx` members. Using
 * these helpers keeps the deprecated AppState mirror fields in sync with the
 * authoritative slice flows from every write site — a hard requirement so M4
 * can flip consumers to the slices without drift surfacing. M4 (AppState
 * retirement) removes both these helpers and the mirrors together.
 *
 * Semantics mirror [MainViewModel.writeConnection] (M2): write the slice
 * first, then overwrite the mirror fields on `state` synchronously — no
 * dispatcher batch reliance (RFC §4 strategy A, §9.2).
 */
@Suppress("DEPRECATION")
internal fun applyComposerSlice(
    state: MutableStateFlow<AppState>,
    composerFlow: MutableStateFlow<ComposerState>,
    transform: (ComposerState) -> ComposerState
) {
    check(Looper.myLooper() === Looper.getMainLooper()) { "applyComposerSlice must be called on the main thread" }
    val next = transform(composerFlow.value)
    composerFlow.value = next
    state.value = state.value.copy(
        inputText = next.inputText,
        imageAttachments = next.imageAttachments,
        sendingSessionIds = next.sendingSessionIds,
        draftWorkdir = next.draftWorkdir
    )
}

@Suppress("DEPRECATION")
internal fun applySettingsSlice(
    state: MutableStateFlow<AppState>,
    settingsFlow: MutableStateFlow<SettingsState>,
    transform: (SettingsState) -> SettingsState
) {
    check(Looper.myLooper() === Looper.getMainLooper()) { "applySettingsSlice must be called on the main thread" }
    val next = transform(settingsFlow.value)
    settingsFlow.value = next
    state.value = state.value.copy(
        themeMode = next.themeMode,
        markdownFontSizes = next.markdownFontSizes,
        selectedAgentName = next.selectedAgentName,
        agents = next.agents,
        providers = next.providers,
        availableCommands = next.availableCommands,
        disabledModels = next.disabledModels,
        uiFontScale = next.uiFontScale,
        uiContentScale = next.uiContentScale
    )
}
