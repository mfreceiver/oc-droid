package cn.vectory.ocdroid.ui

/**
 * §R-17 batch3d: Domain orchestration free functions. These are NOT the deleted
 * batch-2 AppState mirror helpers (aggregateFromSlices/syncSlicesFromAppState etc.).
 * They are coroutine-launch helpers called by the domain ViewModels and AppCore
 * orchestration extensions to perform async operations (load/refresh/mutate).
 * Future cleanup (batch3e+): may be inlined into individual VM private methods.
 */

import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun launchLoadMessages(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    sessionId: String,
    resetLimit: Boolean = true,
    settingsManager: SettingsManager? = null,
    onCacheWindow: (sessionId: String, window: CachedSessionWindow) -> Unit = { _, _ -> },
    emit: EventEmitter = EventEmitter { },
    /**
     * R-20 Phase 1 (gpter 复审 final-fix): the serverGroupFp captured AT
     * CALL TIME (when the REST request was initiated). Used to guard the
     * async onSuccess against cross-group same-sessionId collision (plan §0
     * N1: ses_xxxx is a branded string, not UUID — clone/reset server can
     * collide). Default "" → fp guard is a no-op (both sides "" → equal),
     * preserving backward compat for tests/legacy callers.
     */
    expectedServerGroupFp: String = "",
    /**
     * R-20 Phase 1 (gpter 复审 final-fix): provider for the CURRENT host's
     * serverGroupFp, read at onSuccess time. Compared against
     * [expectedServerGroupFp] — a mismatch means the user switched host
     * group during the REST call; the stale response must NOT be written.
     */
    currentServerGroupFp: () -> String = { "" },
) {

    // Coalesce concurrent loads. ADB showed startup triggers message loads from
    // multiple paths (testConnection→loadSessions→onLoadMessages, ON_START
    // catch-up) within ~2.6s — 3 parallel fetches of the same large
    // chunked body that叠加 to OOM. The first load wins; concurrent ones skip.
    // The flag is set synchronously (before launch) to close the check-and-set
    // race window. Periodic reloads after the first completes still go through.
    // §R-17 batch2 step e final: slice-only read.
    if (slices.chat.value.isLoadingMessages) {
        DebugLog.d("Sync", "launchLoadMessages skipped: isLoadingMessages already true")
        return
    }
    slices.mutateChat { c -> c.copy(isLoadingMessages = true) }
    scope.launch {
        // §on-demand: cursor pagination. The first load (resetLimit) captures the
        // X-Next-Cursor for future loadMore; subsequent periodic reloads fetch the
        // latest window only and preserve the cursor so scrolled history stays
        // loadable.
        repository.getMessagesPaged(sessionId, MainViewModelTimings.initialMessagePageSize, before = null)
            .onSuccess { page ->
                DebugLog.d("Sync", "fetched ${page.items.size} messages, newestId=${page.items.lastOrNull()?.info?.id ?: "-"}")
                // §R-17 batch2 step e final: slice-only reads. Single fresh
                // capture reused through the merge-compute cluster below — no
                // writes between here and the slice update near the bottom of
                // this block.
                //
                // R-20 Phase 1 (gpter 复审 final-fix): COMPOUND-KEY guard.
                // The prior guard only compared sessionId — a cross-group
                // same-sessionId collision (plan §0 N1: ses_xxxx branded
                // string, clone/reset server can collide) would let a stale
                // G1 REST response write into G2's chat slice. Adding the fp
                // re-check closes the last downstream TOCTOU: if the user
                // switched host group during the REST call,
                // expectedServerGroupFp != currentServerGroupFp() → drop.
                // Default "" for both → equal → no-op (backward compat).
                if (sessionId == slices.chat.value.currentSessionId &&
                    expectedServerGroupFp == currentServerGroupFp()
                ) {
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
                    //
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
                    //
                    // §R-17 batch2 step e final: capture current chat-domain
                    // fields from the authoritative slice (the sole store).
                    val srcMessages = slices.chat.value.messages
                    val srcParts = slices.chat.value.partsByMessage
                    val srcStreamingTexts = slices.chat.value.streamingPartTexts
                    val srcStreamingReasoning = slices.chat.value.streamingReasoningPart
                    val srcCursor = slices.chat.value.olderMessagesCursor
                    val srcHasMore = slices.chat.value.hasMoreMessages
                    val srcGapMarkers = slices.chat.value.gapMarkers
                    val srcSessionStatuses = slices.sessionList.value.sessionStatuses

                    val fetchedIds = page.items.map { m -> m.info.id }.toHashSet()
                    val oldestFetchedCreated = page.items
                        .mapNotNull { m -> m.info.time?.created }
                        .minOrNull()
                    val fetchedMessages = page.items.map { m -> m.info }
                    val fetchedParts = page.items.associate { m -> m.info.id to m.parts }
                    val olderKept = srcMessages.filter { m ->
                        m.id !in fetchedIds && (oldestFetchedCreated == null ||
                            m.time?.created == null ||
                            m.time.created < oldestFetchedCreated)
                    }
                    val olderKeptIds = olderKept.map { m -> m.id }.toHashSet()
                    val mergedMessages = olderKept + fetchedMessages
                    // keep parts for older-kept messages + add fetched parts
                    var mergedParts = srcParts.filterKeys { id -> id in olderKeptIds } + fetchedParts
                    // §flicker-fix (placeholder survival): during a turn the
                    // REST snapshot often LAGS the SSE stream — the in-flight
                    // part isn't persisted yet, so fetchedParts for the
                    // streaming message can be empty/stale and wipes the
                    // locally-injected placeholder Part (added by
                    // ensurePlaceholderPart on the leading-edge delta). The
                    // streaming guard in ChatMessageList keeps the ROW
                    // (separate fix), but MessageRow iterates an empty parts
                    // list and renders nothing → the content still vanishes
                    // for the ~100ms until the next delta flush re-injects the
                    // placeholder. Re-inject the placeholder for every active
                    // streaming partId whose Part was dropped by the server
                    // snapshot so streaming output stays visible across
                    // reloads. `srcParts` is the pre-merge state that
                    // still holds our injected placeholders; `srcStreamingTexts`
                    // holds the active streaming partIds.
                    // §review (kimo 🟠-3 / momo 🟠-2): compute streamingFinalized
                    // FIRST and gate re-injection on !streamingFinalized. At
                    // finalization (idle) the server snapshot is authoritative —
                    // the part is persisted and present in fetchedParts — so we
                    // must NOT re-inject a placeholder there. Without this gate,
                    // a lagging idle snapshot could re-inject a text=null
                    // placeholder into partsByMessage while the overlay is
                    // simultaneously cleared to emptyMap below → a zombie
                    // placeholder with no overlay text → empty bubble after the
                    // turn ends. During active streaming (!finalized) the overlay
                    // is preserved and re-injection keeps output visible.
                    val streamingFinalized = srcSessionStatuses[sessionId]
                        ?.let { st -> !st.isBusy && !st.isRetry } ?: true
                    val streamingPartIds = srcStreamingTexts.keys
                    if (!streamingFinalized && streamingPartIds.isNotEmpty()) {
                        var reInjected = false
                        val withPlaceholders = mergedParts.toMutableMap()
                        for ((oldMsgId, oldParts) in srcParts) {
                            for (p in oldParts) {
                                // §review (momo 🟠-2/🟠-3): only text/reasoning
                                // parts stream via streamingPartTexts and are
                                // rendered by PartView's TextPart/ReasoningCard.
                                // Re-injecting a tool/patch/file/step-* placeholder
                                // would misroute in PartView (empty tool card,
                                // orphaned streaming text). Match ensurePlaceholderPart's
                                // type guard (SessionSyncCoordinator §681-722).
                                if (p.id in streamingPartIds && (p.isText || p.isReasoning)) {
                                    val merged = withPlaceholders[oldMsgId]
                                    if (merged == null || merged.none { it.id == p.id }) {
                                        withPlaceholders[oldMsgId] =
                                            (merged ?: emptyList()) + p
                                        reInjected = true
                                    }
                                }
                            }
                        }
                        if (reInjected) mergedParts = withPlaceholders
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
                    val newStreamingTexts = if (resetLimit && streamingFinalized) emptyMap<String, String>() else srcStreamingTexts
                    val newStreamingReasoning = if (resetLimit && streamingFinalized) null else srcStreamingReasoning
                    // Only (re)seed the history cursor on a fresh open; a
                    // periodic reload must NOT clobber an existing cursor
                    // (now safe because older history is preserved above).
                    val newCursor = if (resetLimit) page.nextCursor else srcCursor
                    val newHasMore = if (resetLimit) (page.nextCursor != null) else srcHasMore
                    // §Phase1C (gpt-2 S1): a resetLimit=true reload is an
                    // authoritative snapshot replace — any open gap
                    // belonged to the previous window and is now stale
                    // (anchor/boundary may no longer be present). Clear it;
                    // a fresh gap can only re-open via launchCatchUp.
                    val newGapMarkers = if (resetLimit) emptyList() else srcGapMarkers
                    // §model-selection: track the model bound to the
                    // active session by inferring it from the latest
                    // assistant message's resolvedModel. Surfaces in
                    // the chat top-bar context menu.
                    // §model-selection (V1-per-prompt): the per-session stored model is the
                    // intended-next-model and wins over inference — this preserves a pending
                    // user switch across periodic reloads during streaming. Inference from the
                    // latest assistant message is the fallback for sessions first opened on
                    // another client (no local stored choice yet).
                    val newModel = settingsManager?.getModelForSession(currentServerGroupFp(), sessionId)
                        ?: inferCurrentModel(mergedMessages)

                    val beforeMergeSize = srcMessages.size
                    slices.mutateChat { c ->
                        c.copy(
                            messages = mergedMessages,
                            partsByMessage = mergedParts,
                            isLoadingMessages = false,
                            streamingPartTexts = newStreamingTexts,
                            streamingReasoningPart = newStreamingReasoning,
                            olderMessagesCursor = newCursor,
                            hasMoreMessages = newHasMore,
                            gapMarkers = newGapMarkers,
                            currentModel = newModel
                        )
                    }
                    // selectedAgentName lives in the settings slice (cross-slice write).
                    // §bug3-defensive: only sync global selectedAgentName from history when the session
                    // has an explicit per-session agent override; otherwise preserve the user's global
                    // choice so opening an old session does not clobber their picker selection.
                    val perSessionAgent = settingsManager?.getAgentForSession(currentServerGroupFp(), sessionId)
                    if (perSessionAgent != null) {
                        slices.mutateSettings { it.copy(selectedAgentName = perSessionAgent) }
                    }
                    DebugLog.d("Sync", "merged: before=$beforeMergeSize after=${mergedMessages.size}")
                    // §Per-session message cache (write): snapshot the freshly-
                    // merged window so a return trip restores it instantly.
                    // The post-restore fetch (resetLimit=false) will merge any
                    // newer tail non-destructively on next open.
                    onCacheWindow(
                        sessionId,
                        CachedSessionWindow(
                            messages = mergedMessages,
                            partsByMessage = mergedParts,
                            olderMessagesCursor = newCursor,
                            hasMoreMessages = newHasMore
                        )
                    )
                } else {
                    slices.mutateChat { c -> c.copy(isLoadingMessages = false) }
                }
            }
            .onFailure { error ->
                DebugLog.w("Sync", "loadMessages failed: ${errorMessageOrFallback(error, "unknown error")}")
                // §R-17 batch2 step e final: slice-only read.
                if (sessionId == slices.chat.value.currentSessionId) {
                    slices.mutateChat { c -> c.copy(isLoadingMessages = false) }
                    emit.emit(UiEvent.Error(R.string.error_load_messages_failed, listOf(errorMessageOrFallback(error, "unknown error"))))
                } else {
                    slices.mutateChat { c -> c.copy(isLoadingMessages = false) }
                }
            }

        // Best-effort: load session todos after messages (matches iOS behavior).
        // Fails silently in test mocks where the endpoint isn't set up.
        try {
            repository.getSessionTodos(sessionId)
                .onSuccess { todos ->
                    slices.mutateSessionList { sl -> sl.copy(sessionTodos = sl.sessionTodos + (sessionId to todos)) }
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
    slices: SliceFlows,
    resetLimit: Boolean = true,
    onLoadMessages: (String, Boolean) -> Unit
) {
    DebugLog.d("Sync", "loadMessages scheduled: session=$sessionId resetLimit=$resetLimit")
    scope.launch {
        delay(MainViewModelTimings.messageRetryDelayMs)
        // §R-17 batch2 step e final: slice-only read. Captured once after the
        // delay so the guard + log see a consistent snapshot.
        if (sessionId != slices.chat.value.currentSessionId) {
            DebugLog.d("Sync", "loadMessages dropped: session mismatch ($sessionId != ${slices.chat.value.currentSessionId})")
            return@launch
        }
        onLoadMessages(sessionId, resetLimit)
    }
}


internal fun launchLoadMoreMessages(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    sessionId: String,
    /**
     * R-20 Phase 1 (gpter 复审 final-fix): captured fp for compound-key
     * guard. See [launchLoadMessages] doc. Default "" → no-op.
     */
    expectedServerGroupFp: String = "",
    /**
     * R-20 Phase 1 (gpter 复审 final-fix): current fp provider for the
     * onSuccess re-check. See [launchLoadMessages] doc.
     */
    currentServerGroupFp: () -> String = { "" },
    onCacheWindow: (sessionId: String, window: CachedSessionWindow) -> Unit = { _, _ -> },
) {

    // §R-17 batch2 step e final: slice-only reads. Single capture for this
    // synchronous guard cluster.
    if (slices.chat.value.isLoadingMessages) return
    // §on-demand: cursor-based history paging. Fetch one older page via the V1
    // `before` cursor and PREPEND it — no longer re-downloading the latest
    // window with an ever-growing limit (the old O(n²) anti-pattern that caused
    // both cellular blowup and OOM). Stops when there's no next cursor.
    val cursor = slices.chat.value.olderMessagesCursor
    if (cursor == null || !slices.chat.value.hasMoreMessages) return
    // Atomic check-and-set (mirrors launchLoadMessages): set isLoadingMessages
    // synchronously BEFORE launch so a rapid second loadMore (fast scroll /
    // recomposition) can't pass the guard and fire a duplicate concurrent
    // fetch of the same cursor page.
    slices.mutateChat { c -> c.copy(isLoadingMessages = true) }
    scope.launch {
        repository.getMessagesPaged(sessionId, limit = MainViewModelTimings.historyMessagePageSize, before = cursor)
            .onSuccess { page ->
                // §R-17 batch2 step e final: slice-only guard + merge-source
                // capture. Single fresh capture for the synchronous cluster up
                // to the slice update below.
                //
                // R-20 Phase 1 (gpter 复审 final-fix): COMPOUND-KEY guard —
                // same rationale as launchLoadMessages. Cross-group same-
                // sessionId collision must not let a stale older-page response
                // write into the wrong group's chat slice.
                if (sessionId == slices.chat.value.currentSessionId &&
                    expectedServerGroupFp == currentServerGroupFp()
                ) {
                    // Capture current chat-domain values from the slice so we
                    // can compute the post-merge values used by the cache
                    // snapshot below.
                    val srcMessages = slices.chat.value.messages
                    val srcParts = slices.chat.value.partsByMessage
                    val srcCursor = slices.chat.value.olderMessagesCursor
                    val srcHasMore = slices.chat.value.hasMoreMessages

                    val newMessages: List<Message>
                    val newParts: Map<String, List<Part>>
                    val newCursor: String?
                    val newHasMore: Boolean
                    if (page.items.isNotEmpty()) {
                        // De-dup by message id at the seam (the page boundary may
                        // overlap the oldest already-loaded message by one).
                        val existingIds = srcMessages.map { it.id }.toHashSet()
                        val older = page.items.filterNot { it.info.id in existingIds }
                        val olderMessages = older.map { it.info }
                        val olderParts = older.associate { it.info.id to it.parts }
                        newMessages = olderMessages + srcMessages
                        newParts = olderParts + srcParts
                        newCursor = page.nextCursor
                        newHasMore = page.nextCursor != null
                    } else {
                        newMessages = srcMessages
                        newParts = srcParts
                        newCursor = page.nextCursor
                        newHasMore = page.nextCursor != null
                    }
                    val newMessagesFinal = newMessages
                    val newPartsFinal = newParts
                    slices.mutateChat { c ->
                        c.copy(
                            messages = newMessagesFinal,
                            partsByMessage = newPartsFinal,
                            olderMessagesCursor = newCursor,
                            hasMoreMessages = newHasMore,
                            isLoadingMessages = false
                        )
                    }
                    // §Per-session message cache (write): a loadMore result
                    // expands the cached window — without this, switching away
                    // and back would lose the older page the user just paged
                    // in (the post-restore tail fetch only re-merges the latest
                    // 5). Uses the computed values above so the cached window
                    // reflects the prepended older page exactly.
                    onCacheWindow(
                        sessionId,
                        CachedSessionWindow(
                            messages = newMessagesFinal,
                            partsByMessage = newPartsFinal,
                            olderMessagesCursor = newCursor,
                            hasMoreMessages = newHasMore
                        )
                    )
                } else {
                    slices.mutateChat { c -> c.copy(isLoadingMessages = false) }
                }
            }
            .onFailure {
                // §R-17 batch2 step e final: slice-only read.
                if (sessionId == slices.chat.value.currentSessionId) {
                    reportNonFatalIssue("MainViewModel", "Failed to load more messages")
                }
                // Manual paging: no auto-retry/loop. Keep hasMoreMessages so the
                // user can tap "load more" again (transient failures shouldn't
                // permanently disable history). With limit=5 a page is tiny, so
                // hitting the 16 MB response cap is essentially impossible.
                slices.mutateChat { c -> c.copy(isLoadingMessages = false) }
            }
    }
}
