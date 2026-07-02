package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

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
                        // §Bug3 (scroll-yank + history-vanish): UNIFIED selective
                        // merge — ALWAYS preserve already-loaded older pages,
                        // regardless of resetLimit. Previously the resetLimit=true
                        // branch wholesale-replaced messages/partsByMessage with
                        // the fetched page (latest 20), discarding older pages the
                        // user had loaded via loadMore. During streaming,
                        // session.status busy/idle triggers resetLimit=true
                        // reloads, so loaded history vanished and the list shrank
                        // → LazyListState lost its anchor and yanked to bottom.
                        // Now both branches use the same selective merge that the
                        // old resetLimit=false branch already used: keep local
                        // messages whose id is NOT in the fetched set AND whose
                        // created time predates the fetched page's oldest (or
                        // whose created time is unavailable), then prepend them to
                        // the fetched page. `m.id !in fetchedIds` dedups the seam
                        // (loadMoreMessages has its own id-dedup at its seam too).
                        // resetLimit STILL controls the downstream metadata resets
                        // below (olderMessagesCursor, hasMoreMessages, gapInfo,
                        // streaming overlay clearance) — only the merge changed.
                        val olderKept = it.messages.filter { m ->
                            m.id !in fetchedIds && (oldestFetchedCreated == null ||
                                m.time?.created == null ||
                                m.time.created < oldestFetchedCreated)
                        }
                        val olderKeptIds = olderKept.map { m -> m.id }.toHashSet()
                        mergedMessages = olderKept + fetchedMessages
                        // keep parts for older-kept messages + add fetched parts
                        mergedParts = it.partsByMessage.filterKeys { id -> id in olderKeptIds } + fetchedParts
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
                            streamingReasoningPart = when {
                                resetLimit && streamingFinalized -> null
                                else -> it.streamingReasoningPart
                            },
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
                            gapInfo = if (resetLimit) null else it.gapInfo,
                            // §model-selection: track the model bound to the
                            // active session by inferring it from the latest
                            // assistant message's resolvedModel. Surfaces in
                            // the chat top-bar context menu.
                            currentModel = inferCurrentModel(mergedMessages)
                        )
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

