package com.yage.opencode_client.ui

import com.yage.opencode_client.data.model.ComposerImageAttachment
import com.yage.opencode_client.data.model.Message
import com.yage.opencode_client.data.model.Part
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.model.toCacheEntry
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.util.DebugLog
import com.yage.opencode_client.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
        availableCommands = state.availableCommands
    )
    slices.chat.value = ChatState(
        currentSessionId = state.currentSessionId,
        messages = state.messages,
        partsByMessage = state.partsByMessage,
        streamingPartTexts = state.streamingPartTexts,
        streamingReasoningPart = state.streamingReasoningPart,
        olderMessagesCursor = state.olderMessagesCursor,
        hasMoreMessages = state.hasMoreMessages,
        isLoadingMessages = state.isLoadingMessages,
        gapInfo = state.gapInfo,
        staleNotice = state.staleNotice
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
    val next = transform(settingsFlow.value)
    settingsFlow.value = next
    state.value = state.value.copy(
        themeMode = next.themeMode,
        markdownFontSizes = next.markdownFontSizes,
        selectedAgentName = next.selectedAgentName,
        agents = next.agents,
        providers = next.providers,
        availableCommands = next.availableCommands
    )
}

/**
 * Persist a bounded session-metadata cache to [SettingsManager.sessionCache]
 * so the next cold start can reseed [AppState.sessions] instantly (tabs,
 * title, workdir groups). Keeps only entries the user actively cares about:
 * open tabs ([openIds]), the current session ([currentId]) and the current
 * workdir's root sessions ([currentWorkdir]).
 *
 * Fix #5: previously written only inside [launchLoadSessions].onSuccess,
 * which never re-runs on a plain `selectSession` (no message sent). After
 * opening an existing conversation and restarting, the tab vanished because
 * its Session metadata was missing from the cache. This helper is now also
 * called from [MainViewModel.selectSession] and [MainViewModel.sendMessage]
 * so opening/creating a conversation persists its metadata immediately.
 *
 * The filter matches the original inline logic: id in openIds, or id ==
 * currentId, or (root session whose directory == currentWorkdir). Writes via
 * the same plain prefs write as before (`.apply()` — async to disk, sync to
 * memory); callers run on the main/coroutine context exactly like
 * [launchLoadSessions] did, so no extra threading is required.
 */
internal fun persistSessionCache(
    settingsManager: SettingsManager,
    sessions: List<Session>,
    openIds: List<String>,
    currentId: String?,
    currentWorkdir: String?
) {
    val openIdSet = openIds.toSet()
    val cache = sessions
        .asSequence()
        .filter { s ->
            s.id in openIdSet ||
                s.id == currentId ||
                (currentWorkdir != null && s.parentId == null && s.directory == currentWorkdir)
        }
        .map { it.toCacheEntry() }
        .toList()
    settingsManager.sessionCache = cache
}

internal fun launchLoadSessions(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    settingsManager: SettingsManager,
    onSelectSession: (String) -> Unit,
    onLoadSessionStatus: () -> Unit,
    onLoadMessages: (String) -> Unit
, slices: SliceFlows? = null) {

    scope.launch {
        val limit = MainViewModelTimings.sessionPageSize
        state.updateAndSync(slices) {
            it.copy(
                loadedSessionLimit = limit,
                hasMoreSessions = true,
                isLoadingMoreSessions = false,
                isRefreshingSessions = true
            )
        }
        repository.getSessions(limit)
            .onSuccess { sessions ->
                state.updateAndSync(slices) {
                    val mergedSessions = mergeRefreshedSessionsPreservingLocalActivity(
                        sessions,
                        it.sessions,
                        it.currentSessionId,
                        it.openSessionIds.toSet()
                    )
                    it.copy(
                        sessions = mergedSessions,
                        hasMoreSessions = mergedSessions.size >= limit,
                        isLoadingMoreSessions = false,
                        isRefreshingSessions = false
                    )
                }
                // Persist a BOUNDED session-metadata cache so the next cold
                // start can reseed AppState.sessions instantly (tabs/title/
                // workdir groups). Keep only entries the user actively cares
                // about: open tabs, the current session, and the current
                // workdir's root sessions (for the Sessions-screen grouping).
                // Server refresh already replaced same-id entries above via
                // mergeRefreshedSessionsPreservingLocalActivity; cached-only
                // entries (server didn't return them) survive for open tabs.
                run {
                    val postState = state.value
                    persistSessionCache(
                        settingsManager = settingsManager,
                        sessions = postState.sessions,
                        openIds = postState.openSessionIds,
                        currentId = postState.currentSessionId,
                        currentWorkdir = settingsManager.currentWorkdir
                    )
                }
                val currentId = state.value.currentSessionId
                val refreshedSessions = state.value.sessions
                when {
                    // Skip auto-select when the user is mid-draft (a workdir
                    // has been chosen but no session created yet): selecting a
                    // session would discard the draft's repository workdir and
                    // hijack the empty chat page the user is composing into.
                    currentId == null && state.value.draftWorkdir == null && refreshedSessions.isNotEmpty() -> onSelectSession(refreshedSessions.first().id)
                    // currentId is set: keep it. Even when the session is
                    // temporarily absent from the refreshed list (e.g. just
                    // created, or a directory session), tolerate it — reload
                    // its messages but do NOT silently reselect first(). #10.
                    currentId != null -> {
                        onLoadSessionStatus()
                        onLoadMessages(currentId)
                    }
                    else -> {
                        state.updateAndSync(slices) { it.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap()) }
                    }
                }
            }
            .onFailure { error ->
                state.updateAndSync(slices) {
                    it.copy(
                        isLoadingMoreSessions = false,
                        isRefreshingSessions = false,
                        error = "Failed to load sessions: ${errorMessageOrFallback(error, "unknown error")}"
                    )
                }
            }
    }
}

internal fun launchLoadMoreSessions(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    onSelectSession: (String) -> Unit
, slices: SliceFlows? = null) {

    var nextLimit = 0
    var shouldLaunch = false
    state.updateAndSync(slices) { current ->
        if (!current.hasMoreSessions || current.isLoadingMoreSessions) {
            current
        } else {
            nextLimit = nextSessionFetchLimit(current.loadedSessionLimit)
            shouldLaunch = true
            current.copy(isLoadingMoreSessions = true)
        }
    }
    if (!shouldLaunch) return
    scope.launch {
        repository.getSessions(nextLimit)
            .onSuccess { sessions ->
                if (state.value.loadedSessionLimit > nextLimit) {
                    state.updateAndSync(slices) { it.copy(isLoadingMoreSessions = false) }
                    return@onSuccess
                }
                state.updateAndSync(slices) {
                    val mergedSessions = mergeRefreshedSessionsPreservingLocalActivity(
                        sessions,
                        it.sessions,
                        it.currentSessionId,
                        it.openSessionIds.toSet()
                    )
                    it.copy(
                        sessions = mergedSessions,
                        loadedSessionLimit = nextLimit,
                        hasMoreSessions = mergedSessions.size >= nextLimit,
                        isLoadingMoreSessions = false
                    )
                }
                val currentId = state.value.currentSessionId
                val refreshedSessions = state.value.sessions
                when {
                    // Mirror loadSessions' draft guard for consistency: never
                    // auto-select first() while the user is mid-draft. This
                    // branch is currently dead (loadMore is only triggered by
                    // user scroll, not initial load), but the guard keeps the
                    // two paths symmetric if the trigger ever changes.
                    currentId == null && state.value.draftWorkdir == null && refreshedSessions.isNotEmpty() -> onSelectSession(refreshedSessions.first().id)
                    // A non-null currentId is never silently replaced by
                    // refreshedSessions.first(): tolerate the session even when
                    // it is temporarily absent from the refreshed list. #10.
                    currentId != null -> Unit
                    else -> state.updateAndSync(slices) { it.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap()) }
                }
            }
            .onFailure { error ->
                state.updateAndSync(slices) {
                    it.copy(
                        isLoadingMoreSessions = false,
                        error = "Failed to load more sessions: ${errorMessageOrFallback(error, "unknown error")}"
                    )
                }
            }
    }
}

internal fun launchLoadSessionStatus(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>
, slices: SliceFlows? = null) {

    scope.launch {
        repository.getSessionStatus()
            .onSuccess { statuses ->
                state.updateAndSync(slices) { it.copy(sessionStatuses = statuses) }
            }
            .onFailure { error ->
                reportNonFatalIssue("MainViewModel", "Failed to load session status", error)
            }
    }
}

internal fun selectSessionState(
    state: MutableStateFlow<AppState>,
    settingsManager: SettingsManager,
    sessionId: String,
    composerFlow: MutableStateFlow<ComposerState>
, slices: SliceFlows? = null) {

    val oldSessionId = state.value.currentSessionId
    // §R-17 M3: read composer slice directly (authoritative; mirror is kept in
    // sync by applyComposerSlice but reading the slice avoids the @Deprecated
    // mirror warning inside the VM-side helpers).
    val currentInputText = composerFlow.value.inputText
    if (oldSessionId != null) {
        settingsManager.setDraftText(oldSessionId, currentInputText)
    }

    settingsManager.currentSessionId = sessionId
    val restoredDraft = settingsManager.getDraftText(sessionId)
        state.updateAndSync(slices) {
            // §15.2 (review B2): clear streaming buffers on session switch so a
            // stale partial from the previous session cannot bleed into the new
            // one and re-combine with fresh deltas (key collision on
            // `${messageId}:${partId}` produced ghost/fragmented text).
            // §Phase1C: also clear any open gap (断层) — it belonged to the
            // previous session's tail and has no meaning in the new one.
            // §Phase1E (glm-1 🟡-2): clear staleNotice too — opening a session
            // is itself a fresh load, so the "long absence" banner is moot.
            // §R-17 M3: inputText moved to applyComposerSlice below.
            it.copy(
                currentSessionId = sessionId,
                messages = emptyList(),
                partsByMessage = emptyMap(),
                streamingPartTexts = emptyMap(),
                streamingReasoningPart = null,
                gapInfo = null,
                staleNotice = false
            )
        }
        // §R-17 M3: restore the selected session's draft into the composer slice.
        applyComposerSlice(state, composerFlow) { it.copy(inputText = restoredDraft) }
}

/**
 * Sync the repository's workdir directory context to the selected session's
 * directory. Looks up the session in the current state's session list so that
 * subsequent directory-scoped requests (file ops, prompt, session creation)
 * target the correct workdir. No-op when the session is not yet loaded.
 */
internal fun syncCurrentDirectoryForSession(
    state: MutableStateFlow<AppState>,
    repository: OpenCodeRepository,
    sessionId: String
) {
    val directory = state.value.sessions.firstOrNull { it.id == sessionId }?.directory
    repository.setCurrentDirectory(directory)
}

internal fun launchLoadMessages(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    resetLimit: Boolean = true,
    settingsManager: SettingsManager? = null,
    onCacheWindow: (sessionId: String, window: CachedSessionWindow) -> Unit = { _, _ -> },
    // §R-17 M3: optional settings slice (see launchCatchUp). Defaults null so
    // legacy test callers keep compiling; production passes _settingsFlow.
    settingsFlow: MutableStateFlow<SettingsState>? = null
, slices: SliceFlows? = null) {

    // Coalesce concurrent loads. ADB showed startup triggers message loads from
    // multiple paths (testConnection→loadSessions→onLoadMessages, ON_START
    // catch-up) within ~2.6s — 3 parallel fetches of the same large
    // chunked body that叠加 to OOM. The first load wins; concurrent ones skip.
    // The flag is set synchronously (before launch) to close the check-and-set
    // race window. Periodic reloads after the first completes still go through.
    if (state.value.isLoadingMessages) {
        DebugLog.d("Sync", "launchLoadMessages skipped: isLoadingMessages already true")
        return
    }
    state.updateAndSync(slices) { it.copy(isLoadingMessages = true) }
    scope.launch {
        // §on-demand: cursor pagination. The first load (resetLimit) captures the
        // X-Next-Cursor for future loadMore; subsequent periodic reloads fetch the
        // latest window only and preserve the cursor so scrolled history stays
        // loadable.
        repository.getMessagesPaged(sessionId, MainViewModelTimings.initialMessagePageSize, before = null)
            .onSuccess { page ->
                DebugLog.d("Sync", "fetched ${page.items.size} messages, newestId=${page.items.lastOrNull()?.info?.id ?: "-"}")
                if (sessionId == state.value.currentSessionId) {
                    val lastAssistant = page.items.lastOrNull { it.info.isAssistant }
                    val inferredAgentName = lastAssistant?.info?.agent
                    val agentName = settingsManager?.getAgentForSession(sessionId) ?: inferredAgentName
                    val beforeMergeSize = state.value.messages.size
                    state.updateAndSync(slices) {
                        // §preserveUnfetched (mirrors opencode-web reconcileFetched):
                        // a periodic reload (resetLimit=false) fetches the latest
                        // window but must NOT erase already-loaded older history
                        // pages. When the fetched page is incomplete (nextCursor !=
                        // null, i.e. more history exists), keep every local message
                        // whose id is not in the fetched page AND whose created time
                        // predates the fetched page's oldest — exactly the older
                        // pages the user scrolled up to load. Without this the
                        // periodic reload replaced `messages` wholesale while keeping
                        // the old cursor, causing the 🔴 history-断层 the reviewers
                        // flagged. Falls back to "keep all not-in-fetched" when
                        // created times are unavailable. S4 split-store: parts for
                        // kept-older messages must be preserved alongside their
                        // messages (partsByMessage mirrors the merge).
                        val fetchedIds = page.items.map { m -> m.info.id }.toHashSet()
                        val oldestFetchedCreated = page.items
                            .mapNotNull { m -> m.info.time?.created }
                            .minOrNull()
                        val fetchedMessages = page.items.map { m -> m.info }
                        val fetchedParts = page.items.associate { m -> m.info.id to m.parts }
                        val mergedMessages: List<Message>
                        val mergedParts: Map<String, List<Part>>
                        if (resetLimit) {
                            mergedMessages = fetchedMessages
                            mergedParts = fetchedParts
                        } else {
                            val olderKept = it.messages.filter { m ->
                                m.id !in fetchedIds && (oldestFetchedCreated == null ||
                                    m.time?.created == null ||
                                    m.time.created < oldestFetchedCreated)
                            }
                            val olderKeptIds = olderKept.map { m -> m.id }.toHashSet()
                            mergedMessages = olderKept + fetchedMessages
                            // keep parts for older-kept messages + add fetched parts
                            mergedParts = it.partsByMessage.filterKeys { id -> id in olderKeptIds } + fetchedParts
                        }
                        // §append-safe (gpter BLOCKER): only drop the live
                        // streaming overlay when the session is NOT actively
                        // running. A resetLimit=true reload triggered while a
                        // turn is still streaming — e.g. an append-send's post-
                        // send refresh, or the appended user message's
                        // `message.updated` (server 1.17.11+ emits message.updated,
                        // not message.created, for new messages) — must NOT
                        // erase the in-flight assistant text: the fetched window
                        // may not yet hold the finalized part.text, so
                        // streamingPartTexts is the source of truth until the
                        // run settles. Once status flips to idle the next
                        // resetLimit reload finalizes as before (preserving the
                        // S1 finalization-boundary model). Unknown status →
                        // finalize/clear (legacy behaviour).
                        val streamingFinalized = it.sessionStatuses[sessionId]
                            ?.let { st -> !st.isBusy && !st.isRetry } ?: true
                        it.copy(
                            messages = mergedMessages,
                            partsByMessage = mergedParts,
                            isLoadingMessages = false,
                            selectedAgentName = agentName ?: it.selectedAgentName,
                            streamingPartTexts = if (resetLimit && streamingFinalized) emptyMap() else it.streamingPartTexts,
                            streamingReasoningPart = if (resetLimit && streamingFinalized) null else it.streamingReasoningPart,
                            // Only (re)seed the history cursor on a fresh open; a
                            // periodic reload must NOT clobber an existing cursor
                            // (now safe because older history is preserved above).
                            olderMessagesCursor = if (resetLimit) page.nextCursor else it.olderMessagesCursor,
                            hasMoreMessages = if (resetLimit) (page.nextCursor != null) else it.hasMoreMessages,
                            // §Phase1C (gpt-2 S1): a resetLimit=true reload is an
                            // authoritative snapshot replace — any open gap
                            // belonged to the previous window and is now stale
                            // (anchor/tailOldest may no longer be present). Clear
                            // it; a fresh gap can only re-open via launchCatchUp.
                            gapInfo = if (resetLimit) null else it.gapInfo
                        )
                    }
                    // §R-17 M3: also sync the settings slice so direct-subscription
                    // consumers see the same agent. No-op when settingsFlow is null
                    // (legacy test path); the AppState mirror above is always written.
                    if (agentName != null && settingsFlow != null) {
                        settingsFlow.value = settingsFlow.value.copy(selectedAgentName = agentName)
                    }
                    DebugLog.d("Sync", "merged: before=$beforeMergeSize after=${state.value.messages.size}")
                    // §Per-session message cache (write): snapshot the freshly-
                    // merged window so a return trip restores it instantly.
                    // The post-restore fetch (resetLimit=false) will merge any
                    // newer tail non-destructively on next open. Reads
                    // state.value synchronously after the update above so the
                    // snapshot matches the just-written state exactly.
                    val postUpdate = state.value
                    onCacheWindow(
                        sessionId,
                        CachedSessionWindow(
                            messages = postUpdate.messages,
                            partsByMessage = postUpdate.partsByMessage,
                            olderMessagesCursor = postUpdate.olderMessagesCursor,
                            hasMoreMessages = postUpdate.hasMoreMessages
                        )
                    )
                } else {
                    state.updateAndSync(slices) { it.copy(isLoadingMessages = false) }
                }
            }
            .onFailure { error ->
                DebugLog.w("Sync", "loadMessages failed: ${errorMessageOrFallback(error, "unknown error")}")
                if (sessionId == state.value.currentSessionId) {
                    state.updateAndSync(slices) {
                        it.copy(
                            isLoadingMessages = false,
                            error = "Failed to load messages: ${errorMessageOrFallback(error, "unknown error")}"
                        )
                    }
                } else {
                    state.updateAndSync(slices) { it.copy(isLoadingMessages = false) }
                }
            }

        // Best-effort: load session todos after messages (matches iOS behavior).
        // Fails silently in test mocks where the endpoint isn't set up.
        try {
            repository.getSessionTodos(sessionId)
                .onSuccess { todos ->
                    state.updateAndSync(slices) { it.copy(sessionTodos = it.sessionTodos + (sessionId to todos)) }
                }
        } catch (e: Exception) {
            // R-14: never swallow structured concurrency cancellation — re-throw
            // so the parent coroutine scope (viewModelScope) tears down correctly
            // when the ViewModel is cleared mid-load. Other failures stay silent
            // (todos are progressive enhancement, see comment above).
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
        }
    }
}

internal fun launchLoadMessagesWithRetry(
    scope: CoroutineScope,
    sessionId: String,
    state: MutableStateFlow<AppState>,
    resetLimit: Boolean = true,
    onLoadMessages: (String, Boolean) -> Unit
) {
    DebugLog.d("Sync", "loadMessages scheduled: session=$sessionId resetLimit=$resetLimit")
    scope.launch {
        delay(MainViewModelTimings.messageRetryDelayMs)
        if (sessionId != state.value.currentSessionId) {
            DebugLog.d("Sync", "loadMessages dropped: session mismatch ($sessionId != ${state.value.currentSessionId})")
            return@launch
        }
        onLoadMessages(sessionId, resetLimit)
    }
}

/**
 * §Phase1B/1C catch-up: runs after a reconnect (server.connected, not the first
 * connect) or a medium foreground return (15s–5min). Cheapest-first:
 *
 * 1. Record the pre-reload local newest message id by `time.created` (NOT list
 *    position — `messages` is oldest-first, so we use max-by-created which is
 *    order-independent and robust).
 * 2. Probe the server's newest id (limit=1). If it equals the local newest,
 *    nothing arrived during the outage → skip the reload entirely (the big
 *    traffic saving). Any pre-existing open gap is preserved as-is.
 * 3. Otherwise fetch the latest-4 (sentinel) and merge (resetLimit=false
 *    semantics: keep older history + cursor + streaming overlay), then run
     *    gap detection. M2 / gpter 致命#4 (sentinel off-by-one): reload
 *    pulls 4 (3 display + 1 sentinel). If the anchor (pre-reload newest) is
 *    anywhere in the fetched window — INCLUDING the 4th sentinel slot — the
 *    tail is contiguous → no gap. This makes the "exactly N new" boundary
 *    correct: when precisely 3 new messages arrived the anchor lands at the
 *    sentinel (oldest) position and is still detected, avoiding a false gap.
 *    If the anchor is NOT in the fetched window → messages arrived outside
 *    the tail → set [GapInfo] so the UI shows a tappable divider. Also
 *    clears `staleNotice` (a successful catch-up means we're current again).
 *
 * Does NOT touch `olderMessagesCursor`/`hasMoreMessages` (resetLimit=false).
 * No-op when a load is already in flight (coalesced with [launchLoadMessages]
 * via the shared `isLoadingMessages` guard).
 */
internal fun launchCatchUp(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    settingsManager: SettingsManager? = null,
    onCacheWindow: (sessionId: String, window: CachedSessionWindow) -> Unit = { _, _ -> },
    // §R-17 M3: optional settings slice for direct-subscription consumers.
    // Defaults to null so legacy test callers (CatchUpGapTest) keep compiling;
    // when null, selectedAgentName is written only to the AppState mirror
    // (production callers pass _settingsFlow so the slice stays in sync).
    settingsFlow: MutableStateFlow<SettingsState>? = null
, slices: SliceFlows? = null) {

    // §Phase1B (gpt-2 S3 / glm-1 🟡-1): synchronous check-and-set (mirrors
    // launchLoadMessages) to close the race where two concurrent catch-up
    // triggers each pass the guard before either sets the flag, firing two
    // probes / tail reloads. Reset on every exit path (skip / success / fail).
    if (state.value.isLoadingMessages) return
    state.updateAndSync(slices) { it.copy(isLoadingMessages = true) }
    scope.launch {
        // Order-independent newest id (messages is oldest-first per ora-2).
        val anchorNewestId = state.value.messages
            .maxByOrNull { it.time?.created ?: -1L }?.id
        val serverNewestId = repository.probeLatestMessageId(sessionId).getOrNull()

        // No newer message on the server → skip the 5-message reload entirely.
        // Preserve any already-open gap (it is still unresolved).
        if (anchorNewestId != null && serverNewestId != null && anchorNewestId == serverNewestId) {
            state.updateAndSync(slices) { it.copy(isLoadingMessages = false) }
            return@launch
        }

        repository.getMessagesPaged(sessionId, 4, before = null)
            .onSuccess { page ->
                if (sessionId != state.value.currentSessionId) {
                    state.updateAndSync(slices) { it.copy(isLoadingMessages = false) }
                    return@onSuccess
                }
                // §preserveUnfetched merge (resetLimit=false): keep older loaded
                // pages whose id is not in the fetched window and whose created
                // time predates the fetched window's oldest. Mirrors
                // launchLoadMessages; kept inline+commented to stay in sync.
                val fetchedIds = page.items.map { m -> m.info.id }.toHashSet()
                val oldestFetchedCreated = page.items
                    .mapNotNull { m -> m.info.time?.created }
                    .minOrNull()
                val fetchedMessages = page.items.map { m -> m.info }
                val fetchedParts = page.items.associate { m -> m.info.id to m.parts }
                val olderKept = state.value.messages.filter { m ->
                    m.id !in fetchedIds && (oldestFetchedCreated == null ||
                        m.time?.created == null ||
                        m.time.created < oldestFetchedCreated)
                }
                val olderKeptIds = olderKept.map { m -> m.id }.toHashSet()
                val mergedMessages = olderKept + fetchedMessages
                val mergedParts = state.value.partsByMessage.filterKeys { id -> id in olderKeptIds } + fetchedParts

                // M2 / gpter 致命#4 (sentinel gap detection): the anchor
                // (pre-reload newest) ANYWHERE in the fetched 4-window — including
                // the 4th sentinel slot — means the tail is contiguous → no gap.
                // Fetching 4 (not 3) ensures the "exactly 3 new" boundary lands
                // the anchor at the sentinel (oldest) position, still detected.
                val tailOldestId = page.items
                    .minByOrNull { it.info.time?.created ?: Long.MAX_VALUE }?.info?.id
                val newGap = if (anchorNewestId != null && tailOldestId != null && anchorNewestId !in fetchedIds) {
                    GapInfo(
                        anchorNewestId = anchorNewestId,
                        tailOldestId = tailOldestId,
                        // Cursor pages OLDER from the tail's oldest — the closure direction.
                        tailOldestCursor = page.nextCursor,
                        open = true
                    )
                } else {
                    null
                }

                val lastAssistant = page.items.lastOrNull { it.info.isAssistant }
                val inferredAgentName = lastAssistant?.info?.agent
                val agentName = settingsManager?.getAgentForSession(sessionId) ?: inferredAgentName

                state.updateAndSync(slices) {
                    it.copy(
                        messages = mergedMessages,
                        partsByMessage = mergedParts,
                        isLoadingMessages = false,
                        selectedAgentName = agentName ?: it.selectedAgentName,
                        // resetLimit=false: preserve streaming overlay + history cursor.
                        olderMessagesCursor = it.olderMessagesCursor,
                        hasMoreMessages = it.hasMoreMessages,
                        gapInfo = newGap,
                        staleNotice = false
                    )
                }
                // §R-17 M3: also sync the settings slice so direct-subscription
                // consumers see the same agent. No-op when settingsFlow is null
                // (legacy test path); the AppState mirror above is always written.
                if (agentName != null && settingsFlow != null) {
                    settingsFlow.value = settingsFlow.value.copy(selectedAgentName = agentName)
                }
                val postUpdate = state.value
                onCacheWindow(
                    sessionId,
                    CachedSessionWindow(
                        messages = postUpdate.messages,
                        partsByMessage = postUpdate.partsByMessage,
                        olderMessagesCursor = postUpdate.olderMessagesCursor,
                        hasMoreMessages = postUpdate.hasMoreMessages
                    )
                )
                // §F4 (gpter 致命#4 / 设计 §1.3/§1.4): catchUp 发现 gap 后自动启动
                // closeGap (step=3, maxSteps=5)。此时 isLoadingMessages 已在上一行
                // state update 中被置 false，launchCloseGap 的 isLoading 守卫可正常
                // 通过；它内部会重新置 isLoading=true 并 launch 独立协程走自动闭合循环。
                // newGap==null (无 gap 或 anchor 在窗口内) 时不触发，避免无谓 fetch。
                if (newGap != null) {
                    launchCloseGap(
                        scope = scope,
                        repository = repository,
                        state = state,
                        sessionId = sessionId,
                        onCacheWindow = onCacheWindow,
                        slices = slices
                    )
                }
            }
            .onFailure {
                if (sessionId == state.value.currentSessionId) {
                    reportNonFatalIssue("MainViewModel", "Catch-up tail reload failed")
                }
                state.updateAndSync(slices) { it.copy(isLoadingMessages = false) }
            }
    }
}

/**
 * Default gap-closure step (messages per page) and the auto-closure
 * budget cap. Auto-closure pages `step` messages at a time from the gap's
 * [GapInfo.tailOldestCursor]; after [GAP_CLOSE_MAX_STEPS] pages without
 * reaching the anchor it stops and leaves the gap hint (open [GapInfo]) for a
 * manual tap, which re-enters [launchCloseGap] with a fresh budget.
 */
internal const val GAP_CLOSE_STEP_DEFAULT = 3
internal const val GAP_CLOSE_MAX_STEPS = 5

/**
 * §Phase1C gap closure (loadMore-style).
 *
 * Pages OLDER from the gap's [GapInfo.tailOldestCursor] in steps of [step]
 * messages until the [GapInfo.anchorNewestId] reappears (gap closed → clear
 * gapInfo) or one of the stop conditions hits:
 *  - cursor exhausted before the anchor → can't bridge → mark [GapInfo.open]
 *    = false (hide divider).
 *  - [maxSteps] pages fetched without finding the anchor → budget exhausted →
 *    STOP auto-closure and leave [GapInfo] open so the UI keeps showing the
 *    tappable divider; a manual tap re-enters this function with a fresh
 *    budget (§1.4 "预算重置").
 *
 * Uses a SEPARATE cursor chain from [launchLoadMoreMessages] so the two paging
 * anchors (gap boundary vs loaded-oldest) never pollute each other. Closure
 * check runs on the RAW page (before dedup) since `before=` is inclusive and
 * the anchor may sit at the page boundary. State is written progressively
 * after each step so the divider follows the shrinking gap.
 */
internal fun launchCloseGap(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    onCacheWindow: (sessionId: String, window: CachedSessionWindow) -> Unit = { _, _ -> }
, slices: SliceFlows? = null,
    // §M2: parameterized step (default 3) replaces the old hardcoded
    // limit=5. SSE-mode callers that omit it get step=3 (acceptable per design
    // §1.4); callers pass step=3 explicitly.
    step: Int = GAP_CLOSE_STEP_DEFAULT,
    // §M2: auto-closure budget cap. Default GAP_CLOSE_MAX_STEPS (=5).
    // Pass 1 to force the legacy single-step-per-call behaviour.
    maxSteps: Int = GAP_CLOSE_MAX_STEPS
) {

    val gap0 = state.value.gapInfo ?: return
    if (!gap0.open) return
    if (state.value.isLoadingMessages) return
    if (step <= 0 || maxSteps <= 0) return // nothing to do; leave gap for manual
    if (gap0.tailOldestCursor == null) {
        // No more history to page — can't bridge; stop showing the divider.
        state.updateAndSync(slices) { it.copy(gapInfo = gap0.copy(open = false)) }
        return
    }
    state.updateAndSync(slices) { it.copy(isLoadingMessages = true) }
    scope.launch {
        var stepsTaken = 0
        var gap = gap0
        var cursor: String? = gap0.tailOldestCursor
        while (true) {
            if (sessionId != state.value.currentSessionId) break
            val c = cursor
            if (c == null) {
                // History exhausted mid-walk without reaching the anchor →
                // can't bridge; stop showing the divider.
                state.updateAndSync(slices) { it.copy(gapInfo = it.gapInfo?.copy(open = false)) }
                break
            }
            val page = repository.getMessagesPaged(sessionId, limit = step, before = c)
                .getOrElse {
                    if (sessionId == state.value.currentSessionId) {
                        reportNonFatalIssue("MainViewModel", "Gap closure fetch failed")
                    }
                    state.updateAndSync(slices) { it.copy(isLoadingMessages = false) }
                    return@launch
                }
            stepsTaken += 1
            // Closure check BEFORE dedup (raw page — `before=` is inclusive).
            val closed = page.items.any { it.info.id == gap.anchorNewestId }
            val existingIds = state.value.messages.map { it.id }.toHashSet()
            val bridged = page.items.filterNot { it.info.id in existingIds }
            val bridgedMessages = bridged.map { it.info }
            val bridgedParts = bridged.associate { it.info.id to it.parts }
            // §Phase1C (gpt-2 B2): the bridged page sits BETWEEN the anchor
            // (older local history) and the tail's oldest — NOT at the list
            // head. Insert it right before the tailOldestId position so the
            // ascending-time order is preserved. The gap's lower boundary
            // (tailOldestId) advances to the OLDEST id just loaded (the new
            // seam between filled-gap and any still-missing range).
            val newTailOldestId = bridged.minByOrNull { it.info.time?.created ?: Long.MAX_VALUE }?.info?.id
            state.updateAndSync(slices) {
                val insertIdx = it.messages.indexOfFirst { m -> m.id == gap.tailOldestId }
                val mergedMessages = if (insertIdx >= 0) {
                    it.messages.subList(0, insertIdx) + bridgedMessages + it.messages.subList(insertIdx, it.messages.size)
                } else {
                    bridgedMessages + it.messages
                }
                it.copy(
                    messages = mergedMessages,
                    partsByMessage = bridgedParts + it.partsByMessage,
                    gapInfo = if (closed) null else gap.copy(
                        tailOldestId = newTailOldestId ?: gap.tailOldestId,
                        tailOldestCursor = page.nextCursor
                    )
                )
            }
            if (closed) break
            // Prepare the next iteration. Re-read the just-written gap so the
            // divider advances; stop if state was unexpectedly cleared.
            gap = state.value.gapInfo ?: break
            cursor = page.nextCursor
            // §M2 budget cap: stop auto-closure after maxSteps and leave
            // the gap hint open for a manual tap (fresh budget on re-entry).
            if (stepsTaken >= maxSteps) break
        }
        state.updateAndSync(slices) { it.copy(isLoadingMessages = false) }
        val postUpdate = state.value
        onCacheWindow(
            sessionId,
            CachedSessionWindow(
                messages = postUpdate.messages,
                partsByMessage = postUpdate.partsByMessage,
                olderMessagesCursor = postUpdate.olderMessagesCursor,
                hasMoreMessages = postUpdate.hasMoreMessages
            )
        )
    }
}

internal fun launchLoadMoreMessages(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    onCacheWindow: (sessionId: String, window: CachedSessionWindow) -> Unit = { _, _ -> }
, slices: SliceFlows? = null) {

    if (state.value.isLoadingMessages) return
    // §on-demand: cursor-based history paging. Fetch one older page via the V1
    // `before` cursor and PREPEND it — no longer re-downloading the latest
    // window with an ever-growing limit (the old O(n²) anti-pattern that caused
    // both cellular blowup and OOM). Stops when there's no next cursor.
    val cursor = state.value.olderMessagesCursor
    if (cursor == null || !state.value.hasMoreMessages) return
    // Atomic check-and-set (mirrors launchLoadMessages): set isLoadingMessages
    // synchronously BEFORE launch so a rapid second loadMore (fast scroll /
    // recomposition) can't pass the guard and fire a duplicate concurrent
    // fetch of the same cursor page.
    state.updateAndSync(slices) { it.copy(isLoadingMessages = true) }
    scope.launch {
        repository.getMessagesPaged(sessionId, limit = MainViewModelTimings.historyMessagePageSize, before = cursor)
            .onSuccess { page ->
                if (sessionId == state.value.currentSessionId) {
                    if (page.items.isNotEmpty()) {
                        // De-dup by message id at the seam (the page boundary may
                        // overlap the oldest already-loaded message by one).
                        val existingIds = state.value.messages.map { it.id }.toHashSet()
                        val older = page.items.filterNot { it.info.id in existingIds }
                        val olderMessages = older.map { it.info }
                        val olderParts = older.associate { it.info.id to it.parts }
                        state.updateAndSync(slices) {
                            it.copy(
                                messages = olderMessages + it.messages,
                                partsByMessage = olderParts + it.partsByMessage,
                                olderMessagesCursor = page.nextCursor,
                                hasMoreMessages = page.nextCursor != null,
                                isLoadingMessages = false
                            )
                        }
                    } else {
                        state.updateAndSync(slices) {
                            it.copy(
                                olderMessagesCursor = page.nextCursor,
                                hasMoreMessages = page.nextCursor != null,
                                isLoadingMessages = false
                            )
                        }
                    }
                    // §Per-session message cache (write): a loadMore result
                    // expands the cached window — without this, switching away
                    // and back would lose the older page the user just paged
                    // in (the post-restore tail fetch only re-merges the latest
                    // 5). Snapshot state.value synchronously after the update
                    // above so the cached window reflects the prepended older
                    // page exactly. (Both the empty-page and non-empty branches
                    // update the cursor/hasMore, so we always re-snapshot.)
                    val postMore = state.value
                    onCacheWindow(
                        sessionId,
                        CachedSessionWindow(
                            messages = postMore.messages,
                            partsByMessage = postMore.partsByMessage,
                            olderMessagesCursor = postMore.olderMessagesCursor,
                            hasMoreMessages = postMore.hasMoreMessages
                        )
                    )
                } else {
                    state.updateAndSync(slices) { it.copy(isLoadingMessages = false) }
                }
            }
            .onFailure {
                if (sessionId == state.value.currentSessionId) {
                    reportNonFatalIssue("MainViewModel", "Failed to load more messages")
                }
                // Manual paging: no auto-retry/loop. Keep hasMoreMessages so the
                // user can tap "load more" again (transient failures shouldn't
                // permanently disable history). With limit=5 a page is tiny, so
                // hitting the 16 MB response cap is essentially impossible.
                state.updateAndSync(slices) { it.copy(isLoadingMessages = false) }
            }
    }
}

internal fun launchLoadProviders(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    settingsFlow: MutableStateFlow<SettingsState>,
    onNonFatalError: (String, Throwable?) -> Unit
) {
    scope.launch {
        repository.getProviders()
            .onSuccess { providers ->
                // §R-17 M3: providers lives on the settings slice.
                applySettingsSlice(state, settingsFlow) { it.copy(providers = providers) }
            }
            .onFailure { error ->
                onNonFatalError("Failed to load providers", error)
            }
    }
}

internal fun launchCreateSession(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    title: String?,
    onSelectSession: (String) -> Unit
, slices: SliceFlows? = null) {

    scope.launch {
        repository.createSession(title)
            .onSuccess { session ->
                state.updateAndSync(slices) { it.copy(sessions = upsertSession(it.sessions, session)) }
                onSelectSession(session.id)
            }
            .onFailure { error ->
                state.updateAndSync(slices) { it.copy(error = "Failed to create session: ${errorMessageOrFallback(error, "unknown error")}") }
            }
    }
}

internal fun launchForkSession(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    messageId: String?,
    onSelectSession: (String) -> Unit
, slices: SliceFlows? = null) {

    scope.launch {
        repository.forkSession(sessionId, messageId)
            .onSuccess { session ->
                state.updateAndSync(slices) { it.copy(sessions = upsertSession(it.sessions, session)) }
                onSelectSession(session.id)
            }
            .onFailure { error ->
                state.updateAndSync(slices) { it.copy(error = "Failed to fork session: ${errorMessageOrFallback(error, "unknown error")}") }
            }
    }
}

internal fun launchSetSessionArchived(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    archived: Boolean
, slices: SliceFlows? = null) {

    scope.launch {
        val archivedValue = if (archived) System.currentTimeMillis() else -1L
        val ids = sessionSubtreeIds(state.value.sessions, sessionId, parentFirst = !archived)
        for (id in ids) {
            repository.updateSessionArchived(id, archivedValue)
                .onSuccess { updated ->
                    state.updateAndSync(slices) { current ->
                        current.copy(
                            sessions = current.sessions.map { session -> if (session.id == id) updated else session },
                            // Keep directorySessions in sync so an archived
                            // session disappears from the connected-projects
                            // list immediately (refreshDirectorySessions
                            // repopulates this map on expand, but the local
                            // copy must not hold a stale unarchived version).
                            directorySessions = current.directorySessions.mapValues { (_, list) ->
                                list.map { session -> if (session.id == id) updated else session }
                            }
                        )
                    }
                }
                .onFailure { error ->
                    state.updateAndSync(slices) {
                        it.copy(error = "Failed to ${if (archived) "archive" else "restore"} session: ${errorMessageOrFallback(error, "unknown error")}")
                    }
                    return@launch
                }
        }
    }
}

private fun sessionSubtreeIds(sessions: List<com.yage.opencode_client.data.model.Session>, rootId: String, parentFirst: Boolean): List<String> {
    val childrenByParent = sessions.groupBy { it.parentId }
    fun collect(id: String): List<String> {
        val children = childrenByParent[id].orEmpty().flatMap { collect(it.id) }
        return if (parentFirst) listOf(id) + children else children + id
    }
    return collect(rootId)
}

internal fun launchDeleteSession(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    settingsManager: SettingsManager,
    sessionId: String,
    onSelectSession: (String) -> Unit
, slices: SliceFlows? = null) {

    scope.launch {
        repository.deleteSession(sessionId)
            .onSuccess {
                // Purge the deleted id from both the global sessions list AND
                // directorySessions. If the session was originally surfaced via
                // a connected workdir (createSessionInWorkdir's directory fetch),
                // leaving it in directorySessions would let SessionsScreen's
                // union render it — and re-selecting it would upsert a ghost
                // copy of an already-deleted server session (#10).
                state.updateAndSync(slices) {
                    val newSessions = it.sessions.filter { s -> s.id != sessionId }
                    val newDirSessions = it.directorySessions
                        .mapValues { (_, list) -> list.filter { s -> s.id != sessionId } }
                        .filterValues { it.isNotEmpty() }
                    it.copy(sessions = newSessions, directorySessions = newDirSessions)
                }
                if (state.value.currentSessionId == sessionId) {
                    val newCurrent = state.value.sessions.firstOrNull()?.id
                    if (newCurrent != null) {
                        onSelectSession(newCurrent)
                    } else {
                        // #10: no remaining session — clear the persisted
                        // currentSessionId too, otherwise a stale id survives
                        // in SettingsManager and would be restored on next
                        // launch / host switch, pointing at a deleted session.
                        settingsManager.currentSessionId = null
                        state.updateAndSync(slices) { it.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap()) }
                    }
                }
            }
            .onFailure { error ->
                state.updateAndSync(slices) { it.copy(error = "Failed to delete session: ${errorMessageOrFallback(error, "unknown error")}") }
            }
    }
}

internal fun launchSendMessage(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    composerFlow: MutableStateFlow<ComposerState>,
    sessionId: String,
    text: String,
    attachments: List<ComposerImageAttachment> = emptyList(),
    agent: String,
    model: Message.ModelInfo?,
    onRefreshMessages: (String, Boolean) -> Unit,
    onRefreshSessions: () -> Unit,
    onSuccess: (() -> Unit)? = null,
    onComplete: (() -> Unit)? = null
, slices: SliceFlows? = null) {

    scope.launch {
        repository.sendMessage(sessionId, text, agent, model, attachments = attachments)
            .onSuccess {
                state.updateAndSync(slices) {
                    // §append-safe (glmer MAJOR-1): inputText is cleared
                    // synchronously at dispatch time, so do NOT touch it here —
                    // wiping now would destroy a follow-up the user typed during
                    // the in-flight prompt_async window (the core send-while-
                    // running workflow).
                    it.copy(
                        error = null,
                        sessions = bumpSessionUpdated(it.sessions, sessionId, System.currentTimeMillis()),
                        sessionStatuses = it.sessionStatuses + (sessionId to com.yage.opencode_client.data.model.SessionStatus(type = "busy"))
                    )
                }
                onSuccess?.invoke()
                onRefreshSessions()
                // §15.1 (review N6): the post-send 1200ms double-refresh is
                // gone — SSE will deliver `message.updated` (server 1.17.11+
                // emits message.updated, not message.created, for new messages;
                // see the insert-if-absent handler in MainViewModelSyncActions)
                // and a foreground catch-up covers any dropped event. The single
                // immediate reload here is the legacy first-paint path that
                // selectSession/sendMessage use to bypass the debounce.
                onRefreshMessages(sessionId, true)
            }
            .onFailure { error ->
                // §R-17 M3: read composer slice for the restore-decision; error
                // → _state, inputText → composer slice (mirror kept in sync).
                // Restore the failed prompt only if the user has not typed
                // something new since the synchronous dispatch clear.
                val currentInput = composerFlow.value.inputText
                val restored = if (currentInput.isBlank()) text else currentInput
                state.updateAndSync(slices) { s ->
                    s.copy(error = errorMessageOrFallback(error, "Failed to send message"))
                }
                applyComposerSlice(state, composerFlow) { it.copy(inputText = restored) }
            }
        onComplete?.invoke()
    }
}
