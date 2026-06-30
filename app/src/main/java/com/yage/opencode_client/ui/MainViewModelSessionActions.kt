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
) {
    scope.launch {
        val limit = MainViewModelTimings.sessionPageSize
        state.update {
            it.copy(
                loadedSessionLimit = limit,
                hasMoreSessions = true,
                isLoadingMoreSessions = false,
                isRefreshingSessions = true
            )
        }
        repository.getSessions(limit)
            .onSuccess { sessions ->
                state.update {
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
                        state.update { it.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap()) }
                    }
                }
            }
            .onFailure { error ->
                state.update {
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
) {
    var nextLimit = 0
    var shouldLaunch = false
    state.update { current ->
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
                    state.update { it.copy(isLoadingMoreSessions = false) }
                    return@onSuccess
                }
                state.update {
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
                    else -> state.update { it.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap()) }
                }
            }
            .onFailure { error ->
                state.update {
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
) {
    scope.launch {
        repository.getSessionStatus()
            .onSuccess { statuses ->
                state.update { it.copy(sessionStatuses = statuses) }
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
) {
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
        state.update {
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
) {
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
    state.update { it.copy(isLoadingMessages = true) }
    scope.launch {
        // §on-demand: cursor pagination. The first load (resetLimit) captures the
        // X-Next-Cursor for future loadMore; subsequent periodic reloads fetch the
        // latest window only and preserve the cursor so scrolled history stays
        // loadable.
        repository.getMessagesPaged(sessionId, 5, before = null)
            .onSuccess { page ->
                DebugLog.d("Sync", "fetched ${page.items.size} messages, newestId=${page.items.lastOrNull()?.info?.id ?: "-"}")
                if (sessionId == state.value.currentSessionId) {
                    val lastAssistant = page.items.lastOrNull { it.info.isAssistant }
                    val inferredAgentName = lastAssistant?.info?.agent
                    val agentName = settingsManager?.getAgentForSession(sessionId) ?: inferredAgentName
                    val beforeMergeSize = state.value.messages.size
                    state.update {
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
                    state.update { it.copy(isLoadingMessages = false) }
                }
            }
            .onFailure { error ->
                DebugLog.w("Sync", "loadMessages failed: ${errorMessageOrFallback(error, "unknown error")}")
                if (sessionId == state.value.currentSessionId) {
                    state.update {
                        it.copy(
                            isLoadingMessages = false,
                            error = "Failed to load messages: ${errorMessageOrFallback(error, "unknown error")}"
                        )
                    }
                } else {
                    state.update { it.copy(isLoadingMessages = false) }
                }
            }

        // Best-effort: load session todos after messages (matches iOS behavior).
        // Fails silently in test mocks where the endpoint isn't set up.
        try {
            repository.getSessionTodos(sessionId)
                .onSuccess { todos ->
                    state.update { it.copy(sessionTodos = it.sessionTodos + (sessionId to todos)) }
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
 * 3. Otherwise fetch the latest-5 and merge (resetLimit=false semantics: keep
 *    older history + cursor + streaming overlay), then run gap detection:
 *    if the anchor (pre-reload newest) is NOT in the fetched window, messages
 *    arrived that fall outside the tail → set [GapInfo] so the UI shows a
 *    tappable divider. Also clears `staleNotice` (a successful catch-up means
 *    we're current again).
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
) {
    // §Phase1B (gpt-2 S3 / glm-1 🟡-1): synchronous check-and-set (mirrors
    // launchLoadMessages) to close the race where two concurrent catch-up
    // triggers each pass the guard before either sets the flag, firing two
    // probes / tail reloads. Reset on every exit path (skip / success / fail).
    if (state.value.isLoadingMessages) return
    state.update { it.copy(isLoadingMessages = true) }
    scope.launch {
        // Order-independent newest id (messages is oldest-first per ora-2).
        val anchorNewestId = state.value.messages
            .maxByOrNull { it.time?.created ?: -1L }?.id
        val serverNewestId = repository.probeLatestMessageId(sessionId).getOrNull()

        // No newer message on the server → skip the 5-message reload entirely.
        // Preserve any already-open gap (it is still unresolved).
        if (anchorNewestId != null && serverNewestId != null && anchorNewestId == serverNewestId) {
            state.update { it.copy(isLoadingMessages = false) }
            return@launch
        }

        repository.getMessagesPaged(sessionId, 5, before = null)
            .onSuccess { page ->
                if (sessionId != state.value.currentSessionId) {
                    state.update { it.copy(isLoadingMessages = false) }
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

                // §Phase1C gap detection: anchor (pre-reload newest) not in the
                // fetched window → messages arrived outside the 5-msg tail.
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

                state.update {
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
            }
            .onFailure {
                if (sessionId == state.value.currentSessionId) {
                    reportNonFatalIssue("MainViewModel", "Catch-up tail reload failed")
                }
                state.update { it.copy(isLoadingMessages = false) }
            }
    }
}

/**
 * §Phase1C user-triggered gap closure (loadMore-style). Pages OLDER from the
 * gap's [GapInfo.tailOldestCursor] until the [GapInfo.anchorNewestId] reappears
 * (gap closed → clear gapInfo) or the cursor is exhausted (give up, mark closed).
 * Uses a SEPARATE cursor chain from [launchLoadMoreMessages] so the two paging
 * anchors (gap boundary vs loaded-oldest) never pollute each other. Closure
 * check runs on the RAW page (before dedup) since `before=` is inclusive and the
 * anchor may sit at the page boundary.
 */
internal fun launchCloseGap(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    onCacheWindow: (sessionId: String, window: CachedSessionWindow) -> Unit = { _, _ -> }
) {
    val gap = state.value.gapInfo ?: return
    if (!gap.open) return
    if (state.value.isLoadingMessages) return
    val cursor = gap.tailOldestCursor
    if (cursor == null) {
        // No more history to page — can't bridge; stop showing the divider.
        state.update { it.copy(gapInfo = gap.copy(open = false)) }
        return
    }
    state.update { it.copy(isLoadingMessages = true) }
    scope.launch {
        repository.getMessagesPaged(sessionId, limit = 5, before = cursor)
            .onSuccess { page ->
                if (sessionId != state.value.currentSessionId) {
                    state.update { it.copy(isLoadingMessages = false) }
                    return@onSuccess
                }
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
                state.update {
                    val insertIdx = it.messages.indexOfFirst { m -> m.id == gap.tailOldestId }
                    val mergedMessages = if (insertIdx >= 0) {
                        it.messages.subList(0, insertIdx) + bridgedMessages + it.messages.subList(insertIdx, it.messages.size)
                    } else {
                        bridgedMessages + it.messages
                    }
                    it.copy(
                        messages = mergedMessages,
                        partsByMessage = bridgedParts + it.partsByMessage,
                        isLoadingMessages = false,
                        gapInfo = if (closed) null else gap.copy(
                            tailOldestId = newTailOldestId ?: gap.tailOldestId,
                            tailOldestCursor = page.nextCursor
                        )
                    )
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
            }
            .onFailure {
                if (sessionId == state.value.currentSessionId) {
                    reportNonFatalIssue("MainViewModel", "Gap closure fetch failed")
                }
                state.update { it.copy(isLoadingMessages = false) }
            }
    }
}

internal fun launchLoadMoreMessages(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    onCacheWindow: (sessionId: String, window: CachedSessionWindow) -> Unit = { _, _ -> }
) {
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
    state.update { it.copy(isLoadingMessages = true) }
    scope.launch {
        repository.getMessagesPaged(sessionId, limit = 5, before = cursor)
            .onSuccess { page ->
                if (sessionId == state.value.currentSessionId) {
                    if (page.items.isNotEmpty()) {
                        // De-dup by message id at the seam (the page boundary may
                        // overlap the oldest already-loaded message by one).
                        val existingIds = state.value.messages.map { it.id }.toHashSet()
                        val older = page.items.filterNot { it.info.id in existingIds }
                        val olderMessages = older.map { it.info }
                        val olderParts = older.associate { it.info.id to it.parts }
                        state.update {
                            it.copy(
                                messages = olderMessages + it.messages,
                                partsByMessage = olderParts + it.partsByMessage,
                                olderMessagesCursor = page.nextCursor,
                                hasMoreMessages = page.nextCursor != null,
                                isLoadingMessages = false
                            )
                        }
                    } else {
                        state.update {
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
                    state.update { it.copy(isLoadingMessages = false) }
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
                state.update { it.copy(isLoadingMessages = false) }
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
) {
    scope.launch {
        repository.createSession(title)
            .onSuccess { session ->
                state.update { it.copy(sessions = upsertSession(it.sessions, session)) }
                onSelectSession(session.id)
            }
            .onFailure { error ->
                state.update { it.copy(error = "Failed to create session: ${errorMessageOrFallback(error, "unknown error")}") }
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
) {
    scope.launch {
        repository.forkSession(sessionId, messageId)
            .onSuccess { session ->
                state.update { it.copy(sessions = upsertSession(it.sessions, session)) }
                onSelectSession(session.id)
            }
            .onFailure { error ->
                state.update { it.copy(error = "Failed to fork session: ${errorMessageOrFallback(error, "unknown error")}") }
            }
    }
}

internal fun launchSetSessionArchived(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    archived: Boolean
) {
    scope.launch {
        val archivedValue = if (archived) System.currentTimeMillis() else -1L
        val ids = sessionSubtreeIds(state.value.sessions, sessionId, parentFirst = !archived)
        for (id in ids) {
            repository.updateSessionArchived(id, archivedValue)
                .onSuccess { updated ->
                    state.update { current ->
                        current.copy(sessions = current.sessions.map { session -> if (session.id == id) updated else session })
                    }
                }
                .onFailure { error ->
                    state.update {
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
) {
    scope.launch {
        repository.deleteSession(sessionId)
            .onSuccess {
                // Purge the deleted id from both the global sessions list AND
                // directorySessions. If the session was originally surfaced via
                // a connected workdir (createSessionInWorkdir's directory fetch),
                // leaving it in directorySessions would let SessionsScreen's
                // union render it — and re-selecting it would upsert a ghost
                // copy of an already-deleted server session (#10).
                state.update {
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
                        state.update { it.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap()) }
                    }
                }
            }
            .onFailure { error ->
                state.update { it.copy(error = "Failed to delete session: ${errorMessageOrFallback(error, "unknown error")}") }
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
) {
    scope.launch {
        repository.sendMessage(sessionId, text, agent, model, attachments = attachments)
            .onSuccess {
                state.update {
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
                state.update { s ->
                    s.copy(error = errorMessageOrFallback(error, "Failed to send message"))
                }
                applyComposerSlice(state, composerFlow) { it.copy(inputText = restored) }
            }
        onComplete?.invoke()
    }
}
