package cn.vectory.ocdroid.ui

/**
 * §R-17 batch3d: Domain orchestration free functions. These are NOT the deleted
 * batch-2 AppState mirror helpers (aggregateFromSlices/syncSlicesFromAppState etc.).
 * They are coroutine-launch helpers called by the domain ViewModels and AppCore
 * orchestration extensions to perform async operations (load/refresh/mutate).
 * Future cleanup (batch3e+): may be inlined into individual VM private methods.
 */

import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.controller.CachedSessionWindow
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.repository.MessagesPage
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.ui.StreamOwnedState
import cn.vectory.ocdroid.ui.chat.PartExpandState
import cn.vectory.ocdroid.ui.chat.PartKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

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
    expectedProfileId: String = "",
    /**
     * R-20 Phase 1 (gpter 复审 final-fix): provider for the CURRENT host's
     * serverGroupFp, read at onSuccess time. Compared against
     * [expectedProfileId] — a mismatch means the user switched host
     * group during the REST call; the stale response must NOT be written.
     */
    currentProfileId: () -> String = { "" },
    /**
     * §empty-window-fix: when `true`, the slim fetch is UNANCHORED — calls
     * [OpenCodeRepository.getMessagesPagedUnanchored] (forces `since=0L`,
     * bypassing the cached slim watermark) instead of the anchored
     * [OpenCodeRepository.getMessagesPaged]. Used ONLY by the VerifyAndHydrate
     * cold-load path (resident-but-empty CachedSessionWindow OR genuine cache
     * miss) so a stale watermark that already covers the server's latest
     * message cannot return an empty `/since` response and preserve the empty
     * UI window (root cause of the "session opens to 暂无消息 but send
     * populates it" bug). Default `false` → all other callers (periodic
     * resetLimit=false refresh, loadMore, catch-up, send's onRefreshMessages)
     * keep the anchored `/since` behavior.
     *
     * The merge / §preserveUnfetched / §Bug3 selective-merge / cursor-seeding
     * logic below is IDENTICAL for both branches — both methods return the
     * same [cn.vectory.ocdroid.data.repository.MessagesPage] shape.
     */
    forceInitialWindow: Boolean = false,
    /**
     * §chat-list-detail §7.2 B0.5-rework: the route-instance token captured at
     * load-START (navigateToChat → openForRoute → VerifyAndHydrate → HERE).
     * Threaded UNCHANGED across the REST suspension. Guards the ENTIRE
     * completion transaction:
     *  - content commit (ChatContentLoaded vs MessagesMerged dispatch)
     *  - isLoadingMessages clear (finally block)
     *  - error emission (onFailure)
     *  - cache-window write
     *  - auto-expand launch
     * A stale A→B→A load (same sessionId, older token) is rejected BEFORE any
     * side effect fires — it cannot clear a newer load's isLoadingMessages,
     * emit a stale error, poison the cache, or overwrite newer content.
     *
     * `0L` = legacy (switchTo path) → dispatches MessagesMerged (no token guard).
     * `> 0L` = route-aware (openForRoute path) → dispatches ChatContentLoaded
     * (the reducer CAS-validates expectedRouteInstance == chatRouteInstance).
     */
    expectedRouteInstance: Long = 0L,
    /**
     * §11.1 fix-9 P0-7 + fix-10 P1-2 (sse-sync-degradation-remediation.md
     * P0-2): SSE liveness predicate — invoked at retry-decision time to
     * decide whether a non-stale first-fetch IOException should retry once.
     * Defaults to `{ true }` (treat SSE as live — no retry, preserves
     * legacy behavior for tests/legacy callers). When the predicate reports
     * SSE-off (`isSseLive() == false`) AND the failure is an IOException
     * (NOT CancellationException) AND `resetLimit == true` (cold-load),
     * the retry loop fires ONE extra `getMessagesPagedUnanchored` call after
     * a short delay. CancellationException propagates verbatim through the
     * delay (handled by the outer try/catch). Production callers
     * (AppCoreOrchestration, ChatViewModel) wire this to
     * `{ store.slices.sseConnected }`.
     */
    isSseLive: () -> Boolean = { true },
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
        // §history-load-fix round-1: try/catch/finally so a non-cancellation
        // exception inside the merge/lock can't escape and cancel the scope
        // (opuser 🟠-2 / kimo 🟡-5); the finally clears isLoadingMessages on any
        // exit (session-guarded — gpter 🟠).
        try {
        // §on-demand: cursor pagination. The first load (resetLimit) captures the
        // X-Next-Cursor for future loadMore; subsequent periodic reloads fetch the
        // latest window only and preserve the cursor so scrolled history stays
        // loadable.
        //
        // §empty-window-fix: forceInitialWindow=true (ONLY the VerifyAndHydrate
        // cold-load branch sets it) routes through getMessagesPagedUnanchored
        // so the slim fetch is UNANCHORED (since=0L) — bypassing a stale slim
        // watermark that would return an empty /since response and preserve an
        // empty UI window. Both methods return the same MessagesPage shape, so
        // the merge / cursor-seeding logic below is shared verbatim.
        var pageResult = if (forceInitialWindow) {
            repository.getMessagesPagedUnanchored(sessionId, MainViewModelTimings.initialMessagePageSize, before = null)
        } else {
            repository.getMessagesPaged(sessionId, MainViewModelTimings.initialMessagePageSize, before = null)
        }
        // §11.1 fix-9 P0-7 (sse-sync-degradation-remediation.md P0-2):
        // SSE-off first-fetch retry. When the SSE transport is NOT live
        // (SseDisabled / terminal exhaustion / never-connected) AND the
        // failure is NOT a stale-token case (already exhausted above) AND
        // this is a cold-load (resetLimit == true), retry ONCE via the
        // unanchored path. Rationale: under SSE-off the digest relay is
        // not delivering updates, so the only way to surface messages is
        // REST; a transient transport failure (503 / network blip) should
        // not leave the user staring at a blank screen. The retry uses
        // getMessagesPagedUnanchored to force a fresh authoritative load
        // (slim mode: drain+commit; legacy mode: unanchored since=0).
        // CancellationException propagates through `delay` verbatim
        // (R-14) — the outer try/catch re-throws it.
        // §11.1 fix-10 P1-2: narrow the retry condition to IOException
        // ONLY (not arbitrary exceptions like programming errors / cast
        // failures). CancellationException MUST propagate verbatim —
        // check it first and re-throw. The stale-token and staging-only
        // exclusions remain (those are handled by their own retry loops).
        DebugLog.d("Sync", "loadMessages P0-7 retry check: isFailure=${pageResult.isFailure} resetLimit=$resetLimit sseLive=${isSseLive()} cause=${pageResult.exceptionOrNull()?.let { it::class.simpleName }}")
        val retryCause = pageResult.exceptionOrNull()
        if (retryCause is kotlin.coroutines.cancellation.CancellationException) {
            throw retryCause
        }
        if (pageResult.isFailure &&
            resetLimit &&
            retryCause is java.io.IOException &&
            !isSseLive()
        ) {
            DebugLog.d("Sync", "loadMessages SSE-off first-fetch failure (cause=${pageResult.exceptionOrNull()?.let { it::class.simpleName }}), retrying once via unanchored in 500ms")
            kotlinx.coroutines.delay(500)
            pageResult = repository.getMessagesPagedUnanchored(
                sessionId,
                MainViewModelTimings.initialMessagePageSize,
                before = null,
            )
        }
        pageResult
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
                // expectedProfileId != currentProfileId() → drop.
                // Default "" for both → equal → no-op (backward compat).
                // §history-load-fix: serialize the read-compute-write of the chat
                // slice per-session so a concurrent launchLoadMoreMessages prepend
                // cannot tear the list / lose an update (the decoupled flag removed
                // the implicit mutual exclusion the shared isLoadingMessages flag
                // used to provide). The fetch ran OUTSIDE the lock (concurrent with
                // any background reload); only the slice mutation is serialized.
                // Mirrors launchLoadMoreMessages / launchCatchUp. Re-validates the
                // compound key INSIDE the lock in case the user switched
                // session/host while waiting.
                slices.messageLoadCoordinator.withSessionLock(sessionId) {
                    // §chat-list-detail §7.2 B0.5-rework: TRIPLE guard —
                    // session + fp + route-instance token. A stale A→B→A load
                    // (same sessionId, older token) is rejected BEFORE any side
                    // effect fires (no content write, no isLoadingMessages clear,
                    // no error, no cache, no auto-expand). This is the oracle's
                    // PRIMARY RISK mitigation: the token guards the ENTIRE
                    // completion transaction, not just the content CAS.
                    val routeTokenValid = expectedRouteInstance == 0L ||
                        expectedRouteInstance == slices.store.stateFlow.value.chatRouteInstance
                    if (sessionId == slices.chat.value.currentSessionId &&
                        expectedProfileId == currentProfileId() &&
                        routeTokenValid
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
                        // below (olderMessagesCursor, hasMoreMessages,
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
                        val srcSessionStatuses = slices.sessionList.value.sessionStatuses

                        val fetchedIds = page.items.map { m -> m.info.id }.toHashSet()
                        val oldestFetchedCreated = page.items
                            .mapNotNull { m -> m.info.time?.created }
                            .minOrNull()
                        val newestFetchedCreated = page.items
                            .mapNotNull { m -> m.info.time?.created }
                            .maxOrNull()
                        val fetchedMessages = page.items.map { m -> m.info }
                        val fetchedParts = page.items.associate { m -> m.info.id to m.parts }
                        // §Q10 three-way merge: olderKept holds history OLDER than
                        // the fetched window (loadMore continuity); newerKept holds
                        // locally-injected messages NEWER than the fetched window
                        // — the user msg + assistant shell the SSE stream inserts
                        // WHILE the REST GET is in flight. Those live messages are
                        // neither in fetchedIds nor satisfy `< oldestFetchedCreated`,
                        // so the old `olderKept + fetchedMessages` two-way merge
                        // silently dropped them → the just-sent message vanished
                        // from the bottom (reverseLayout tail) until the next
                        // reload. newerKept is its own bucket so it does NOT pollute
                        // historyAlreadyPaged (which must stay older-only).
                        val olderKept = if (forceInitialWindow) {
                            emptyList()
                        } else {
                            srcMessages.filter { m ->
                                m.id !in fetchedIds && oldestFetchedCreated != null &&
                                    m.time?.created != null && m.time.created < oldestFetchedCreated
                            }
                        }
                        val olderKeptIds = olderKept.map { m -> m.id }.toHashSet()
                        // newerKept: not-fetched AND not-already-older (id check via
                        // HashSet O(1), NOT `m !in olderKept` which is O(n²) data
                        // equality) AND (null-created OR newest==null OR created>=newest).
                        // null-created (optimistic/local insert with no timestamp yet) lands
                        // at the newest end (reverseLayout bottom), matching the just-arrived
                        // SSE message semantic — it must NOT be classed older (which would
                        // shove it to the history front).
                        // §newerKept-force-window-fix (2026-07-26): previously
                        // `if (forceInitialWindow) emptyList()` — this unconditionally
                        // discarded SSE-delivered messages (user echo, assistant parts)
                        // that arrived WHILE the initial REST GET was in flight. For a
                        // new conversation, the REST GET returns empty (no history yet),
                        // and the SSE messages that arrived during the flight were
                        // silently dropped → "first message doesn't render until re-enter".
                        //
                        // forceInitialWindow=true means "fetch UNANCHORED (since=0L)" —
                        // it controls the FETCH URL, not the merge semantics. The
                        // olderKept clearing above (line 226-232) is correct (a fresh
                        // window discards stale history), but newerKept MUST always be
                        // preserved: those are live SSE messages that the REST snapshot
                        // cannot contain. See exp-1 trace for the full timeline.
                        val newerKept = srcMessages.filter { m ->
                            m.id !in fetchedIds && m.id !in olderKeptIds &&
                                (m.time?.created == null || newestFetchedCreated == null ||
                                    m.time.created >= newestFetchedCreated)
                        }
                        val newerKeptIds = newerKept.map { m -> m.id }.toHashSet()
                        val keptIds = olderKeptIds + newerKeptIds
                        // olderKept (history tail) + fetchedMessages (server-authoritative
                        // page order) + newerKept (newest = reverseLayout bottom).
                        // distinctBy guards the seams against any id overlap.
                        val mergedMessages = (olderKept + fetchedMessages + newerKept).distinctBy { it.id }
                        // keep parts for ALL kept messages (older + newer) + add fetched
                        // parts (fetchedParts authoritative, overrides same-id locals).
                        var mergedParts = srcParts.filterKeys { id -> id in keptIds } + fetchedParts
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
                        // §Q10 overlay guard: the append-safe gate above only
                        // checks session busy state. Add an id-based guard so a
                        // resetLimit reload does NOT clear the live overlay while
                        // its owning message is still only in newerKept (i.e. not
                        // yet present in fetchedIds) — e.g. finalization landed
                        // during the REST flight so the finalized message is
                        // absent from the page. Only clear when EVERY overlay
                        // owner is already fetched (or there is no overlay). This
                        // keeps the streamed text/reasoning visible until a later
                        // reload actually sees the owning message server-side.
                        val overlayOwnerMsgIds = srcStreamingTexts.keys.mapNotNull { pid ->
                            srcParts.values.flatten().firstOrNull { it.id == pid }?.messageId
                        }.toSet()
                        val overlayFinalized =
                            overlayOwnerMsgIds.isEmpty() || overlayOwnerMsgIds.all { it in fetchedIds }
                        val reasoningOwnerMsgId = srcStreamingReasoning?.let { r ->
                            srcParts.values.flatten().firstOrNull { it.id == r.id }?.messageId
                        }
                        val reasoningFinalized =
                            reasoningOwnerMsgId == null || reasoningOwnerMsgId in fetchedIds
                        val srcStreamOwned = slices.chat.value.streamOwned
                        val ownedStreamingKeys = srcStreamOwned.filterValues { it == StreamOwnedState.STREAMING }.keys
                        val legacyWouldClear = resetLimit && streamingFinalized && overlayFinalized
                        val authoritative = legacyWouldClear && ownedStreamingKeys.isEmpty()
                        val newStreamingTexts = when {
                            authoritative -> emptyMap()
                            legacyWouldClear -> srcStreamingTexts.filterKeys { it in ownedStreamingKeys }
                            else -> srcStreamingTexts
                        }
                        val newStreamingReasoning =
                            if (resetLimit && streamingFinalized && reasoningFinalized && ownedStreamingKeys.isEmpty()) null
                            else srcStreamingReasoning
                        // Only (re)seed the history cursor on a fresh open; a
                        // periodic reload must NOT clobber an existing cursor
                        // (now safe because older history is preserved above).
                        // §F3-rebuild: 但缓存水合后 srcCursor==null（toWindow 重建为 null），
                        // 此时即便 resetLimit=false 也必须用 page.nextCursor 建立 cursor/hasMore，
                        // 否则"加载更多"按钮永不出现（从死按钮矫枉过正成无按钮）。一旦 cursor
                        // 已建立，后续 periodic reload 仍保留它（不改写）。
                        val cursorUnseeded = srcCursor == null
                        // §history-load-fix round-1 (gpter 🔴 / glmer 🟡-1): a
                        // resetLimit=true reload must NOT regress an already-
                        // advanced history cursor. If the user has already paged
                        // in older history (srcCursor seeded AND olderKept
                        // present), preserve the existing cursor — overwriting it
                        // with the fresh latest-window nextCursor would point the
                        // next "load more" back at an already-loaded page
                        // (redundant clicks, self-healing but defeats the fix's
                        // purpose). Fresh open (srcCursor null) still seeds from
                        // page.nextCursor; resetLimit with no older history still
                        // refreshes. (kimo judged loadMore's own cursor correct
                        // because it uses its own response cursor — this guard
                        // closes the loadMessages(resetLimit=true) overwrite path
                        // that gpter/glmer flagged.)
                        val historyAlreadyPaged = !cursorUnseeded && olderKept.isNotEmpty()
                        val newCursor = if ((resetLimit || cursorUnseeded) && !historyAlreadyPaged) page.nextCursor else srcCursor
                        val newHasMore = if ((resetLimit || cursorUnseeded) && !historyAlreadyPaged) (page.nextCursor != null) else srcHasMore
                        // §model-selection: track the model bound to the
                        // active session by inferring it from the latest
                        // assistant message's resolvedModel. Surfaces in
                        // the chat top-bar context menu + the compact request.
                        //
                        // §chat-ux-batch T8 (B3): the per-session stored model
                        // override (legacy SettingsManager.getModelForSession)
                        // was deleted; currentModel is now sourced purely from
                        // inference at load. The picker feedback path runs
                        // through TRANSIENT pendingModel (T7 contract), so this
                        // field is the load-time + compact-time mirror only.
                        val newModel = inferCurrentModel(mergedMessages)

                        val beforeMergeSize = srcMessages.size
                        // §chat-list-detail §7.2 B0.5-rework: route-aware path
                        // dispatches ChatContentLoaded (the reducer CAS-validates
                        // expectedRouteInstance AND atomically commits BOTH
                        // LoadedContent + the flat mirror). Legacy path
                        // (expectedRouteInstance == 0) dispatches MessagesMerged
                        // (flat fields only — the bare-chat path).
                        if (expectedRouteInstance > 0L) {
                            slices.store.dispatch(
                                AppAction.ChatContentLoaded(
                                    sessionId = sessionId,
                                    expectedRouteInstance = expectedRouteInstance,
                                    messages = mergedMessages,
                                    partsByMessage = mergedParts,
                                    streamingPartTexts = newStreamingTexts,
                                    streamingReasoningPart = newStreamingReasoning,
                                    olderMessagesCursor = newCursor,
                                    hasMoreMessages = newHasMore,
                                    currentModel = newModel,
                                    authoritative = authoritative,
                                )
                            )
                        } else {
                            // T1b: full 8-field merge in ONE dispatch (no torn intermediate).
                            // isLoadingMessages=false is unconditional inside the reducer.
                            slices.store.dispatch(
                                AppAction.MessagesMerged(
                                    messages = mergedMessages,
                                    partsByMessage = mergedParts,
                                    streamingPartTexts = newStreamingTexts,
                                    streamingReasoningPart = newStreamingReasoning,
                                    olderMessagesCursor = newCursor,
                                    hasMoreMessages = newHasMore,
                                    currentModel = newModel,
                                    authoritative = authoritative,
                                )
                            )
                        }
                        // §defect-B-2B: auto-expand omitted tool output for the
                        // freshly loaded messages (bounded to the most-recent
                        // window, streaming-guarded, single-flight per load).
                        // Launches into the same scope; its own CAS guards
                        // (session+fp+streaming+isLoading) no-op if the session
                        // switched / went streaming before the launch resumes.
                        // §B4 round-2 (rev-gpt MAJOR): thread expectedRouteInstance
                        // so a stale A→B→A incarnation's auto-expand cannot
                        // pollute the newer incarnation's partExpandStates (the
                        // token guard complements the existing session+fp CAS).
                        launchAutoExpandOmittedParts(
                            scope = scope,
                            repository = repository,
                            store = slices.store,
                            sessionId = sessionId,
                            currentProfileId = currentProfileId,
                            expectedRouteInstance = expectedRouteInstance,
                        )
                        // §chat-ux-batch T8 (B3): the legacy global←per-session
                        // selectedAgentName backfill (sync the settings slice
                        // from SettingsManager.getAgentForSession) was deleted
                        // here. T7 rewired agent selection to the TRANSIENT
                        // pendingAgent chat-slice field; no settings-slice
                        // mirror is needed.
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
                        // §history-load-fix round-2 (gpter 🟠 / glm-2 🟡-2): stale
                        // (compound-key mismatch) — NO-OP. The session-guarded
                        // finally below clears isLoadingMessages; clearing here
                        // would clobber a new session's flag (a switch may have
                        // started its own load). A stale response must not touch
                        // the now-current session's state.
                    }
                }
            }
            .onFailure { error ->
                DebugLog.w("Sync", "loadMessages failed: ${errorMessageOrFallback(error, "unknown error")}")
                // §history-load-fix round-2 (gpter 🟠): flag clear deferred to the
                // session-guarded finally. Only surface the user-facing error when
                // this is still the current session (a stale failure belongs to a
                // session the user already left).
                // §chat-list-detail §7.2 B0.5-rework: ALSO check the route token —
                // a stale A→B→A failure must NOT emit an error for the newer load.
                val tokenValid = expectedRouteInstance == 0L ||
                    expectedRouteInstance == slices.store.stateFlow.value.chatRouteInstance
                if (sessionId == slices.chat.value.currentSessionId && tokenValid) {
                    emit.emit(UiEvent.Error(R.string.error_load_messages_failed, listOf(errorMessageOrFallback(error, "unknown error"))))
                }
            }

        // Best-effort: load session todos after messages (matches iOS behavior).
        // Fails silently in test mocks where the endpoint isn't set up.
        // §B4 round-2 (rev-gpt MAJOR): guard the async todos dispatch with
        // the route-token freshness CAS — a stale A→B→A incarnation must NOT
        // pollute the newer incarnation's sessionTodos map (the prior token
        // guard only covered the content commit / error emit / flag clear /
        // cache write / auto-expand launch; the todos dispatch was unguarded,
        // so a stale completion could clobber the newer incarnation's todos).
        try {
            repository.getSessionTodos(sessionId)
                .onSuccess { todos ->
                    val todosTokenValid = expectedRouteInstance == 0L ||
                        expectedRouteInstance == slices.store.stateFlow.value.chatRouteInstance
                    if (todosTokenValid) {
                        slices.mutateSessionList { sl -> sl.copy(sessionTodos = sl.sessionTodos + (sessionId to todos)) }
                    }
                }
        } catch (e: Exception) {
            // R-14: never swallow structured concurrency cancellation — re-throw
            // so the parent coroutine scope (viewModelScope) tears down correctly
            // when the ViewModel is cleared mid-load. Other failures stay silent
            // (todos are progressive enhancement, see comment above).
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
        }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            DebugLog.e("Sync", "loadMessages unexpected error", e)
        } finally {
            // §history-load-fix round-1 backstop (opuser 🟠-2 / kimo 🟡-5):
            // guarantee isLoadingMessages is cleared on ANY exit (incl. an
            // exception inside the merge/lock so a transient failure can't
            // leave it stuck and block all future loads), session-guarded so a
            // stale response doesn't clobber a new session's flag. Idempotent —
            // the success/else/failure branches above already clear it normally.
            //
            // §chat-list-detail §7.2 B0.5-rework: ALSO guard on the route token.
            // A stale A→B→A load (same sessionId, older token) must NOT clear
            // the newer load's isLoadingMessages — doing so would un-block the
            // loading indicator while the newer load is still in flight,
            // confusing the user. The token check ensures only the CURRENT
            // incarnation's load can clear the flag.
            val tokenValid = expectedRouteInstance == 0L ||
                expectedRouteInstance == slices.store.stateFlow.value.chatRouteInstance
            if (sessionId == slices.chat.value.currentSessionId &&
                tokenValid &&
                slices.chat.value.isLoadingMessages
            ) {
                slices.mutateChat { c -> c.copy(isLoadingMessages = false) }
            }
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
    expectedProfileId: String = "",
    /**
     * R-20 Phase 1 (gpter 复审 final-fix): current fp provider for the
     * onSuccess re-check. See [launchLoadMessages] doc.
     */
    currentProfileId: () -> String = { "" },
    onCacheWindow: (sessionId: String, window: CachedSessionWindow) -> Unit = { _, _ -> },
    expectedRouteInstance: Long = 0L,
) {

    // §history-load-fix: OWN flag (isLoadingMoreMessages) — decoupled from the
    // background-reload flag (isLoadingMessages) so a background reload /
    // catch-up in flight NO LONGER silently drops the user's "load more" click
    // (the 0.6.0 "加载历史对话需要多次点击" regression: all three load paths
    // shared isLoadingMessages as a guard, so a catch-up holding it ~500ms
    // swallowed the click). Self-reentry (rapid double-click / fast scroll)
    // still coalesces on this own flag.
    if (slices.chat.value.isLoadingMoreMessages) return
    // §on-demand: cursor-based history paging. Fetch one older page via the
    // `before` cursor and PREPEND it — no longer re-downloading the latest
    // window with an ever-growing limit (the old O(n²) anti-pattern that caused
    // both cellular blowup and OOM). Stops when there's no next cursor.
    //
    // §B2 rev-gpt #5 / BLOCK fix: the route-aware path
    // (expectedRouteInstance != 0L) sources cursor/hasMore from the route-
    // owned LoadedContent (the authoritative surface for chat/{sessionId});
    // the legacy bare-chat overload (expectedRouteInstance == 0L) keeps
    // reading flat state. A route-aware call REQUIRES a matching slot
    // (sessionId + routeInstance validated) — NO Elvis fallback to flat: if
    // the slot is absent / mismatched, or its cursor is null / hasMore is
    // false, the call aborts cleanly BEFORE the loading-flag flip (no fetch,
    // no stuck flag). Read once before the flag flip so the gate sees a
    // consistent snapshot.
    val isRouteAware = expectedRouteInstance != 0L
    val routeContentBeforeLock = if (isRouteAware) {
        slices.chat.value.content?.takeIf {
            it.sessionId == sessionId && it.routeInstance == expectedRouteInstance
        }
    } else null
    if (isRouteAware && routeContentBeforeLock == null) return
    val cursor = if (isRouteAware) routeContentBeforeLock!!.olderMessagesCursor
        else slices.chat.value.olderMessagesCursor
    val hasMore = if (isRouteAware) routeContentBeforeLock!!.hasMoreMessages
        else slices.chat.value.hasMoreMessages
    if (cursor == null || !hasMore) return
    // Atomic check-and-set (mirrors launchLoadMessages): set the flag
    // synchronously BEFORE launch so a rapid second loadMore (fast scroll /
    // recomposition) can't pass the guard and fire a duplicate concurrent
    // fetch of the same cursor page.
    slices.mutateChat { c -> c.copy(isLoadingMoreMessages = true) }
    scope.launch {
        // §history-load-fix round-1: try/catch/finally so a non-cancellation
        // exception inside the merge/lock can't escape and cancel the scope
        // (opuser 🟠-2 / kimo 🟡-5), and the loading flag is ALWAYS cleared on
        // exit via a session-guarded finally (gpter 🟠 — don't clobber a new
        // session's flag).
        try {
        repository.getMessagesPaged(sessionId, limit = MainViewModelTimings.historyMessagePageSize, before = cursor)
            .onSuccess { page ->
                // §history-load-fix: serialize the list mutation per-session so a
                // concurrent launchLoadMessages full-window replace cannot tear
                // the list or lose this prepend (the decoupled flag removed the
                // implicit mutual exclusion the shared flag used to provide).
                // The fetch ran OUTSIDE the lock (concurrent with any background
                // reload); only the read-compute-write of the slice is
                // serialized. Re-validate the compound key INSIDE the lock in
                // case the user switched session/host while waiting.
                slices.messageLoadCoordinator.withSessionLock(sessionId) {
                    // R-20 Phase 1 (gpter 复审 final-fix): COMPOUND-KEY guard —
                    // same rationale as launchLoadMessages. Cross-group same-
                    // sessionId collision must not let a stale older-page response
                    // write into the wrong group's chat slice.
                    val routeTokenValid = expectedRouteInstance == 0L ||
                        expectedRouteInstance == slices.store.stateFlow.value.chatRouteInstance
                    if (sessionId == slices.chat.value.currentSessionId &&
                        expectedProfileId == currentProfileId() &&
                        routeTokenValid
                    ) {
                        // Capture current chat-domain values from the slice so we
                        // can compute the post-merge values used by the cache
                        // snapshot below.
                        //
                        // §B2 rev-gpt #5 / BLOCK fix: route-aware load-more
                        // sources its merge baseline from the route-owned
                        // LoadedContent (authoritative for chat/{sessionId});
                        // legacy bare-chat (token=0) keeps flat reads. NO Elvis
                        // fallback to flat on the route-aware path: if the slot
                        // vanished between the pre-lock gate and here (concurrent
                        // route transition), abort the merge entirely (stale —
                        // nothing route-owned to extend; the flag is cleared by
                        // the session-guarded finally below). Re-read INSIDE the
                        // lock so a concurrent route transition is reflected.
                        val liveChat = slices.chat.value
                        val routeContent = if (expectedRouteInstance != 0L) {
                            liveChat.content?.takeIf {
                                it.sessionId == sessionId &&
                                    it.routeInstance == expectedRouteInstance
                            }
                        } else null
                        if (expectedRouteInstance != 0L && routeContent == null) {
                            // Slot vanished mid-fetch — abort the merge. The
                            // finally backstop clears the flag (session-guarded).
                            return@withSessionLock
                        }
                        val srcMessages = if (expectedRouteInstance != 0L) routeContent!!.messages
                            else liveChat.messages
                        val srcParts = if (expectedRouteInstance != 0L) routeContent!!.partsByMessage
                            else liveChat.partsByMessage
                        val srcCursor = if (expectedRouteInstance != 0L) routeContent!!.olderMessagesCursor
                            else liveChat.olderMessagesCursor
                        val srcHasMore = if (expectedRouteInstance != 0L) routeContent!!.hasMoreMessages
                            else liveChat.hasMoreMessages

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
                        // T1b: loadMore prepend; isLoadingMoreMessages=false is
                        // unconditional inside the reducer.
                        slices.store.dispatch(
                            AppAction.MessagesPrepended(
                                messages = newMessagesFinal,
                                partsByMessage = newPartsFinal,
                                olderMessagesCursor = newCursor,
                                hasMoreMessages = newHasMore,
                                expectedRouteInstance = expectedRouteInstance,
                                sessionId = sessionId,
                            )
                        )
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
                        // §history-load-fix round-1 (gpter 🟠): stale (session/host
                        // switched while this loadMore was in flight) — NO-OP.
                        // SessionSwitcher already reset the flag on the switch; the
                        // now-current session may have its own loadMore in flight
                        // whose flag we must not clobber. The finally backstop is
                        // session-guarded too.
                    }
                }
            }
            .onFailure {
                // §R-17 batch2 step e final: slice-only read.
                if (sessionId == slices.chat.value.currentSessionId) {
                    reportNonFatalIssue("MainViewModel", "Failed to load more messages")
                }
                // §history-load-fix round-1: flag clear moved to the finally
                // backstop (session-guarded). Manual paging: no auto-retry/loop;
                // keep hasMoreMessages so the user can tap "load more" again
                // (transient failures shouldn't permanently disable history).
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e // never swallow structured-concurrency cancellation
        } catch (e: Throwable) {
            DebugLog.e("Sync", "loadMore unexpected error", e)
        } finally {
            // §history-load-fix round-1 backstop (opuser 🟠-2 / kimo 🟡-5 / gpter 🟠):
            // guarantee the flag is cleared on ANY exit (incl. an exception inside
            // the merge/lock so a transient failure can't leave it stuck and block
            // all future loads), and ONLY when this session is still current (don't
            // clobber a new session's flag). Idempotent — the success branch above
            // already cleared it on the normal path.
            val routeTokenValid = expectedRouteInstance == 0L ||
                expectedRouteInstance == slices.store.stateFlow.value.chatRouteInstance
            if (sessionId == slices.chat.value.currentSessionId &&
                routeTokenValid &&
                slices.chat.value.isLoadingMoreMessages
            ) {
                slices.mutateChat { c -> c.copy(isLoadingMoreMessages = false) }
            }
        }
    }
}

/**
 * Legacy positional-call compatibility.  The original helper exposed the
 * cache callback as its fifth parameter, so existing callers such as the
 * focused MessageActions tests can continue to pass a trailing lambda while
 * the route-aware overload above keeps its token as an optional named value.
 */
internal fun launchLoadMoreMessages(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    sessionId: String,
    onCacheWindow: (sessionId: String, window: CachedSessionWindow) -> Unit,
) = launchLoadMoreMessages(
    scope = scope,
    repository = repository,
    slices = slices,
    sessionId = sessionId,
    expectedProfileId = "",
    currentProfileId = { "" },
    onCacheWindow = onCacheWindow,
    expectedRouteInstance = 0L,
)

// ─────────────────────────────────────────────────────────────────────────────
// §4.3 reloadSkeletonPage — lite-v2-dev 核心同步路径
//
// 新的 skeleton reload 协调器：digest / done / resync / idle 等触发点 →
// 拉 sidecar skeleton 单页（无 token / 无 watermark / 无 reconfigure 协议）→
// 权威窗口 merge 进 chat slice。替代旧的 sync engine / full reconciler
// + cold-start snapshot 系统（见 plan §4.1 整文件退役清单）。
//
// 实现严格照抄 plan §4.3.6 完整伪代码（v2.7-final），适配到 MessageActions.kt
// 顶层函数语境：状态 + 行为收敛进 SkeletonReloadCoordinator 类，构造期注入
// scope / repository / slices / currentProfileId。锁序、原子提交事务、
// 身份校验、历史守卫、空页早退、补集分区、deadMsgIds 黑名单、watchdog 退避
// 等细节全部保留——见伪代码注释（被原样保留以便后续 review 比对）。
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────

// ════════════════════════════════════════════════════════════════════════════
// L3 (slimapi-v2 §C1/C2/R1): unified throttled skeleton-reload scheduler.
// See docs/specs/l3-reload-scheduler-design.md (implementation contract).
// ════════════════════════════════════════════════════════════════════════════

/**
 * C2 marker target tuple. `Tuple = (updatedAt, messageId)` from a content-
 * bearing digest. Only [isComplete] tuples may advance the marker. Per C2,
 * tuple equality is NEVER used to suppress a reload (the marker is bookkeeping
 * for reconcile correctness only); the sole rate control is the scheduler.
 */
internal data class Tuple(
    val updatedAt: Long?,
    val messageId: String?,
) {
    val isComplete: Boolean
        get() = updatedAt != null && updatedAt >= 0L && !messageId.isNullOrBlank()
}

/** Reload priority. FORCE_RECONCILE (limit=200) outranks DIGEST (limit=50). */
internal enum class Priority(val limit: Int, val rank: Int) {
    FORCE_RECONCILE(limit = 200, rank = 1),
    DIGEST(limit = 50, rank = 0),
}

internal fun maxPriority(a: Priority, b: Priority): Priority =
    if (a.rank >= b.rank) a else b

/**
 * The reason a reload was requested. Drives [contentBearing] (empty page → R1
 * bounded retry) and [confirmsAuthoritativeEmpty] (empty page → consume dirty).
 * Note: `limit=200` alone does NOT confirm authoritative empty (§H gotcha #1).
 */
internal enum class ReloadReason(
    val isExternalSignal: Boolean,
    val contentBearing: Boolean = false,
    val confirmsAuthoritativeEmpty: Boolean = false,
) {
    DIGEST(isExternalSignal = true, contentBearing = true),
    DIGEST_MALFORMED(isExternalSignal = true, contentBearing = true),
    REQUEST_RELOAD(isExternalSignal = true),
    TOKEN_STREAM_DONE(isExternalSignal = true),
    TOKEN_PART_REMOVED(isExternalSignal = true),
    SERVER_RECONNECT(isExternalSignal = true),
    TRANSPORT_RESET(isExternalSignal = true),
    FORCE_RECONCILE_AUTHORITATIVE_EMPTY(isExternalSignal = true, confirmsAuthoritativeEmpty = true),
    NETWORK_RETRY(isExternalSignal = false),
    EMPTY_PAGE_RETRY(isExternalSignal = false, contentBearing = true),
}

/** Snapshot of the transport generation + identity captured at submit/launch. */
data class TransportSnapshot(val generation: Long, val identity: ConnectionIdentity?)

/** Read-only snapshot of scheduler state for deterministic tests. */
internal data class SchedulerSnapshot(
    val dirty: Boolean,
    val inFlight: Boolean,
    val timerActive: Boolean,
    val priority: Priority,
    val retryAttempt: Int,
    val demandVersion: Long,
    val queuedRequiresContent: Boolean,
    val queuedReasons: Set<ReloadReason>,
    val marker: Tuple?,
)

/** Per-(transportGeneration, sessionId) reload state. Owned by exactly one
 *  immutable [ownerGeneration]; keyed by [ReloadKey] so a host switch creates a
 *  fresh slot and an old in-flight completion cannot mutate the new slot. */
private data class ReloadKey(val generation: Long, val sessionId: String)

/**
 * Per-(generation, sessionId, routeInstance) locally-injected marker key.
 * Includes [routeInstance] as the session-incarnation discriminator: two
 * route incarnations within the same (generation, sid) are isolated, so
 * [onSessionClosed] can clean up only the closed incarnation's markers
 * without affecting a reopen of the same sid at the new routeInstance.
 * See §rev-gpt blocker #2 (round-4: session-incarnation isolation).
 */
private data class IncarnationKey(
    val generation: Long,
    val sessionId: String,
    val routeInstance: Long,
)

private class ReloadState(val ownerGeneration: Long) {
    var dirty: Boolean = false
    var target: Tuple? = null
    var inFlight: Boolean = false
    var timerJob: Job? = null
    var nextAllowedAt: Long = 0L
    var queuedPriority: Priority = Priority.DIGEST
    var queuedReasons: Set<ReloadReason> = emptySet()
    /** OR-aggregated across submits: a queued FORCE must NOT erase a digest's
     *  content requirement (else an empty result would wrongly clear dirty). */
    var queuedRequiresContent: Boolean = false
    var retryAttempt: Int = 0
    /** Monotonic counter bumped on every [SkeletonReloadCoordinator.submit].
     *  Captured into [LaunchTicket.demandVersion]; a completion may clear
     *  `dirty` only if no newer demand arrived since launch (demandVersion
     *  unchanged) — else the newer demand is retained. */
    var demandVersion: Long = 0L
    /** True once bounded retries (2/4/8/16s) are exhausted for the current
     *  dirty work — blocks auto-relaunch (nudge/timer) until a NEW external
     *  signal resets it. Prevents both infinite retry loops AND the last
     *  scheduled retry being wrongly blocked. */
    var boundedRetriesExhausted: Boolean = false
    var lastSuccessfullyReloadedTarget: Tuple? = null // C2 marker
}

/** Immutable launch permit — everything the in-flight job needs, captured once. */
private data class LaunchTicket(
    val key: ReloadKey,
    val ownerState: ReloadState,
    val target: Tuple?,
    val priority: Priority,
    val reasons: Set<ReloadReason>,
    val requiresContent: Boolean,
    val connectionIdentity: ConnectionIdentity?,
    val bundleStamp: BundleStamp?,
    val routeInstance: Long,
    val demandVersion: Long,
)

/** Outcome of an attempted reload, driving marker / dirty / retry decisions. */
private enum class ReloadOutcome {
    CommittedNonEmpty, Empty, Uncommitted, CasRejected, Failed, GuardRejected, Cancelled, Detached
}

/**
 * L3 unified skeleton-reload scheduler (replaces the v2.7 epoch/watchdog
 * coordinator). Public method seams [requestReload] / [onDigestChange] are kept
 * as thin wrappers; ALL sources funnel through [submit].
 *
 * # Core invariants (design §Decision)
 * 1. Only [submit] creates/updates reload demand.
 * 2. Only private [launchReloadLocked] may issue HTTP.
 * 3. One state is owned by exactly one immutable transport generation.
 * 4. A completion may mutate/commit ONLY if it still owns its (gen,sid) slot.
 * 5. `dirty` is consumed at launch; restored on failure/empty/CAS-reject; a
 *    concurrent submit during in-flight re-sets it and an old completion can
 *    never clear it.
 * 6. The marker advances only on CommittedNonEmpty + a complete request tuple.
 * 7. The trailing timer targets the EARLIEST nextAllowedAt — a later digest
 *    mutates queued work but never moves the timer later (anti-starvation).
 *
 * # Locking
 * Single private monitor [stateLock] guards all scheduler state (no coroutine
 * Mutex: submit / background-cancel / generation-detach must be synchronous and
 * no protected op suspends). Lock order: messageLoadCoordinator session mutex →
 * stateLock → non-suspending store commit. NEVER await HTTP/delay/join under
 * stateLock.
 */
class SkeletonReloadCoordinator(
    private val scope: CoroutineScope,
    private val repository: OpenCodeRepository,
    private val slices: SliceFlows,
    private val foreground: StateFlow<Boolean> = MutableStateFlow(true),
    private val currentTransport: () -> TransportSnapshot? = { null },
    private val currentBundleStamp: () -> BundleStamp? = { null },
    private val monotonicNowMs: () -> Long = { System.currentTimeMillis() },
    private val busyMinIntervalMs: Long = 2_000L,
    private val retryDelaysMs: LongArray = longArrayOf(2_000L, 4_000L, 8_000L, 16_000L),
) {
    private val stateLock = Any()

    private val states = mutableMapOf<ReloadKey, ReloadState>()
    private val reloadJobs = mutableMapOf<ReloadState, Job>()
    /** Per-(generation, sessionId, routeInstance) locally-injected markers.
     *  Keyed by [IncarnationKey] so [onSessionClosed] can atomically remove
     *  only the closed incarnation's entries — a new route incarnation of the
     *  same (generation, sid) survives cleanup. */
    private val locallyInjected = ConcurrentHashMap<IncarnationKey, MutableSet<String>>()

    init {
        // Route/current-session observer (§H gotcha #7): a route switch is NOT a
        // session deletion. Cancel timers for no-longer-current sessions (retain
        // dirty) and nudge the now-current session's retained state.
        scope.launch {
            slices.chat
                .map { it.currentSessionId }
                .distinctUntilChanged()
                .collect { current ->
                    val toStart = mutableListOf<Job>()
                    synchronized(stateLock) {
                        val gen = currentTransport()?.generation ?: 0L
                        for ((key, state) in states) {
                            if (key.generation == gen && key.sessionId != current) {
                                state.timerJob?.takeIf { it.isActive }?.cancel()
                                state.timerJob = null
                            }
                        }
                        current?.let { nudged(states[ReloadKey(gen, it)], toStart) }
                    }
                    startJobs(toStart)
                }
        }
        // Foreground observer: background → cancel trailing timers (in-flight
        // allowed to complete, but its completion schedules nothing); foreground
        // restored → resume retained dirty work. (StateFlow is already distinct.)
        scope.launch {
            foreground.collect { fg ->
                if (fg) {
                    val toStart = mutableListOf<Job>()
                    synchronized(stateLock) {
                        for ((_, state) in states) nudged(state, toStart)
                    }
                    startJobs(toStart)
                } else {
                    cancelForBackground()
                }
            }
        }
    }

    /** Start jobs OUTSIDE [stateLock]: a LAZY coroutine may run its body inline
     *  on Main.immediate, and the body (runReload → commitReload) acquires the
     *  session mutex then re-enters stateLock — inverting the declared lock
     *  order (session mutex → stateLock). Must hold NO lock when called. */
    private fun startJobs(jobs: List<Job>) {
        jobs.forEach { runCatching { it.start() } }
    }

    /** Must hold [stateLock]. */
    private fun nudged(state: ReloadState?, toStart: MutableList<Job>) {
        if (state == null) return
        if (state.dirty && !state.inFlight && state.timerJob?.isActive != true) {
            scheduleTrailingLocked(state, toStart)
        }
    }

    // ── Public seams (thin wrappers; route through submit) ──────────────────

    /** Compatibility wrapper for non-digest callers (limit>=200 → FORCE). */
    fun requestReload(sessionId: String, limit: Int = 50) {
        submit(
            sessionId, tuple = null,
            priority = if (limit >= 200) Priority.FORCE_RECONCILE else Priority.DIGEST,
            reason = ReloadReason.REQUEST_RELOAD,
        )
    }

    /**
     * L3 (blocker #2): nudges the current session's retained dirty state.
     * Call when identity or bundle stamp becomes available after a period
     * of null (cold start birth, reconfigure bind complete, bundle publish)
     * so a temporarily-gated dirty submit can proceed. No-op when the
     * current session has no dirty or in-flight state.
     */
    fun nudgeCurrentSession() {
        val toStart = mutableListOf<Job>()
        synchronized(stateLock) {
            val sid = slices.chat.value.currentSessionId ?: return
            val gen = currentTransport()?.generation ?: return
            val state = states[ReloadKey(gen, sid)] ?: return
            nudged(state, toStart)
        }
        startJobs(toStart)
    }

    /** Digest entry. A digest with no extracted tuple defaults to malformed
     *  (still content-bearing → empty page retries per R1). */
    internal fun onDigestChange(sessionId: String, tuple: Tuple = Tuple(null, null)) {
        submit(
            sessionId, tuple, Priority.DIGEST,
            if (tuple.isComplete) ReloadReason.DIGEST else ReloadReason.DIGEST_MALFORMED,
        )
    }

    // ── Unified entry ───────────────────────────────────────────────────────

    /**
     * The ONLY way to create/update reload demand. Does NOT itself reject
     * background / non-current sessions — those submits still mark the state
     * dirty; only the launch gate (foreground/route/identity) suppresses HTTP.
     */
    internal fun submit(sid: String, tuple: Tuple?, priority: Priority, reason: ReloadReason) {
        val transport = currentTransport()
        val gen = transport?.generation ?: 0L
        val toStart = mutableListOf<Job>()
        synchronized(stateLock) {
            if (transport != null) detachMismatchedGenerationsLocked(gen)
            val key = ReloadKey(gen, sid)
            val state = states.getOrPut(key) { ReloadState(ownerGeneration = gen) }
            state.dirty = true
            state.demandVersion += 1L
            state.queuedPriority = maxPriority(state.queuedPriority, priority)
            state.queuedReasons = state.queuedReasons + reason
            // OR-aggregate: a FORCE replacing a DIGEST must not erase the
            // digest's content requirement (§H gotcha #6).
            state.queuedRequiresContent = state.queuedRequiresContent || reason.contentBearing
            // Only content-bearing signals own the marker target; a tuple=null
            // FORCE/token callback must not overwrite a fresher digest target.
            if (reason.contentBearing) state.target = tuple
            // A new external signal resets the bounded-retry budget.
            if (reason.isExternalSignal) {
                state.retryAttempt = 0
                state.boundedRetriesExhausted = false
            }
            scheduleTrailingLocked(state, toStart)
        }
        startJobs(toStart)
    }

    // ── Generation fence ────────────────────────────────────────────────────

    /**
     * Atomically detach all states whose generation != [newGeneration] and
     * cancel their trailing timers. Does NOT cancel in-flight HTTP — its
     * completion is fenced by [stillOwnsLocked] + [commitCasStillValidLocked].
     * Production reconfigure should call this synchronously after
     * [ConnectionIdentityStore.beginReconfigure].
     */
    internal fun detachGeneration(newGeneration: Long) {
        val staleKeys: List<ReloadKey>
        val timers = synchronized(stateLock) {
            staleKeys = states.keys.filter { it.generation != newGeneration }.toList()
            staleKeys.map { key ->
                val st = states.remove(key) ?: return@map null
                val tj = st.timerJob
                st.timerJob = null
                tj
            }
        }
        timers.forEach { it?.cancel() }
        // Clean up locallyInjected for stale generations (generation-scoped).
        locallyInjected.keys.removeAll { incKey ->
            staleKeys.any { it.generation == incKey.generation && it.sessionId == incKey.sessionId }
        }
    }

    /** Must hold [stateLock]. Eagerly drop stale-generation slots + timers. */
    private fun detachMismatchedGenerationsLocked(newGeneration: Long) {
        val stale = states.entries.filter { it.key.generation != newGeneration }
        for (e in stale) {
            e.value.timerJob?.takeIf { it.isActive }?.cancel()
            e.value.timerJob = null
            states.remove(e.key)
            locallyInjected.keys.removeAll { incKey ->
                incKey.generation == e.key.generation && incKey.sessionId == e.key.sessionId
            }
        }
    }

    // ── Scheduling (trailing guarantee + anti-starvation) ───────────────────

    /**
     * Must hold [stateLock]. Schedule the next launch for [state] at its
     * [ReloadState.nextAllowedAt] deadline. NEVER cancels/re-arms an existing
     * active timer (a later digest mutates queued work but must not move the
     * deadline later — otherwise dense digests perpetually debounce and starve,
     * the original 100ms-debounce bug). NEVER issues HTTP itself. Created jobs
     * are appended to [toStart] (the caller starts them OUTSIDE the lock).
     */
    private fun scheduleTrailingLocked(state: ReloadState, toStart: MutableList<Job>) {
        if (!state.dirty || state.inFlight) return
        if (!foreground.value) return
        // Bounded retries exhausted for this dirty work: stop auto-relaunching
        // (a NEW external submit resets the flag + retryAttempt).
        if (state.boundedRetriesExhausted) return
        val sid = keyForStateLocked(state)?.sessionId ?: return
        val now = monotonicNowMs()
        val dueAt = state.nextAllowedAt
        if (now >= dueAt) {
            launchReloadLocked(sid, state, toStart)
            return
        }
        // Keep the earliest valid deadline; do NOT re-arm on each digest.
        if (state.timerJob?.isActive == true) return
        val owner = state
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay((dueAt - monotonicNowMs()).coerceAtLeast(0L))
                val inner = mutableListOf<Job>()
                synchronized(stateLock) {
                    if (!stillOwnsLocked(sid, owner)) return@synchronized
                    if (owner.timerJob === coroutineContext[Job]) owner.timerJob = null
                    scheduleTrailingLocked(owner, inner)
                }
                startJobs(inner)
            } catch (_: CancellationException) {
                // timer cancelled (background / detach / session close)
            }
        }
        state.timerJob = job
        toStart.add(job) // started by the caller, OUTSIDE stateLock
    }

    /**
     * Must hold [stateLock]. Capture an immutable [LaunchTicket], consume dirty,
     * reset the queued fields, and (when busy) push [ReloadState.nextAllowedAt]
     * out by the rate cap. The in-flight job is appended to [toStart] (started
     * outside the lock). Requires a non-null identity AND bundle when transport
     * tracking is active — otherwise retain dirty (a nudge re-schedules when the
     * signals become available).
     */
    private fun launchReloadLocked(sid: String, state: ReloadState, toStart: MutableList<Job>) {
        if (state.inFlight || !state.dirty) return
        val transport = currentTransport()
        if (transport != null && transport.generation != state.ownerGeneration) return
        if (!foreground.value) return
        if (slices.chat.value.currentSessionId != sid) return
        val route = slices.routeInstanceFor(sid)
        if (route == 0L) return
        val identity = transport?.identity
        val bundle = currentBundleStamp()
        // #2a: when transport tracking is active, require BOTH a valid identity
        // and a bundle stamp — else no CAS guard exists and we must not fire.
        if (transport != null && (identity == null || bundle == null)) return
        val ticket = LaunchTicket(
            key = ReloadKey(state.ownerGeneration, sid),
            ownerState = state,
            target = state.target,
            priority = state.queuedPriority,
            reasons = state.queuedReasons,
            requiresContent = state.queuedRequiresContent,
            connectionIdentity = identity,
            bundleStamp = bundle,
            routeInstance = route,
            demandVersion = state.demandVersion,
        )
        state.inFlight = true
        state.dirty = false // consumed by this launch; a concurrent submit re-sets
        state.queuedPriority = Priority.DIGEST
        state.queuedReasons = emptySet()
        state.queuedRequiresContent = false
        if (isBusy(sid)) {
            state.nextAllowedAt = maxOf(state.nextAllowedAt, monotonicNowMs() + busyMinIntervalMs)
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { runReload(ticket) }
        reloadJobs[state] = job
        toStart.add(job) // started by the caller, OUTSIDE stateLock
    }

    /** Re-check every CAS dimension at the moment of sending the HTTP request. */
    private fun preHttpGuard(ticket: LaunchTicket): Boolean {
        if (!foreground.value) return false
        if (slices.chat.value.currentSessionId != ticket.key.sessionId) return false
        if (slices.routeInstanceFor(ticket.key.sessionId) != ticket.routeInstance) return false
        val liveTransport = currentTransport()
        // Legacy mode (no identity captured at launch): skip transport CAS.
        if (ticket.connectionIdentity != null) {
            if (liveTransport == null) return false
            if (liveTransport.generation != ticket.ownerState.ownerGeneration) return false
            if (liveTransport.identity != ticket.connectionIdentity) return false
        }
        if (ticket.bundleStamp != null) {
            val liveBundle = currentBundleStamp() ?: return false
            if (liveBundle != ticket.bundleStamp) return false
        }
        return true
    }

    private suspend fun runReload(ticket: LaunchTicket) {
        val outcome = try {
            if (!preHttpGuard(ticket)) ReloadOutcome.GuardRejected
            else {
                val page = repository.getSlimapiMessagesSkeleton(
                    ticket.key.sessionId, ticket.priority.limit, null,
                )
                // #4a (blocker-4a): re-check cancellation AFTER the HTTP call and
                // BEFORE any commit decision. A cooperative repository call would
                // throw CancellationException mid-IO, but an uncontended
                // [MessageLoadCoordinator] session [Mutex] resolves via tryLock and
                // never re-checks cancellation, so a cancel requested during the
                // HTTP call could otherwise slip through [commitReload] and commit a
                // stale result. ensureActive() is a pure cancellation check (no
                // wall-clock / scheduling assumption): if the job was cancelled, it
                // throws synchronously here → the catch(CancellationException) branch
                // restores dirty demand and re-throws; the stale page can never
                // reach [commitReload]. Empty results are likewise fenced (a stale
                // empty must not consume dirty demand that a newer reload owns).
                currentCoroutineContext().ensureActive()
                if (page.items.isEmpty()) ReloadOutcome.Empty
                else commitReload(ticket, page)
            }
        } catch (ce: CancellationException) {
            // #4a: owned-cancellation must restore the consumed dirty demand.
            withContext(NonCancellable) { onReloadComplete(ticket, ReloadOutcome.Cancelled) }
            throw ce
        } catch (e: Exception) {
            ReloadOutcome.Failed
        }
        onReloadComplete(ticket, outcome)
    }

    /**
     * Merge under session mutex → stateLock; return the commit verdict. The
     * #2b commit-time live guard re-verifies generation/identity/bundle under
     * the lock immediately before merge — correctness does NOT depend on eager
     * detach (a stale in-flight whose old slot is still present is rejected).
     */
    private suspend fun commitReload(ticket: LaunchTicket, page: MessagesPage): ReloadOutcome =
        slices.messageLoadCoordinator.withSessionLock(ticket.key.sessionId) {
            synchronized(stateLock) {
                val sid = ticket.key.sessionId
                val owner = ticket.ownerState
                when {
                    !stillOwnsLocked(sid, owner) -> ReloadOutcome.Detached
                    !commitCasStillValidLocked(ticket) -> ReloadOutcome.CasRejected
                    mergeSkeletonIntoChatSlice(ticket, page) -> ReloadOutcome.CommittedNonEmpty
                    else -> ReloadOutcome.Uncommitted
                }
            }
        }

    /** Must hold [stateLock]. Live CAS re-verification at commit time. Legacy
     *  mode (no identity captured) skips the transport CAS. */
    private fun commitCasStillValidLocked(ticket: LaunchTicket): Boolean {
        val ticketIdentity = ticket.connectionIdentity ?: return true // legacy
        val liveTransport = currentTransport() ?: return false
        if (liveTransport.generation != ticket.ownerState.ownerGeneration) return false
        if (liveTransport.identity != ticketIdentity) return false
        val ticketBundle = ticket.bundleStamp ?: return true
        val liveBundle = currentBundleStamp() ?: return false
        return liveBundle == ticketBundle
    }

    private fun onReloadComplete(ticket: LaunchTicket, outcome: ReloadOutcome) {
        val toStart = mutableListOf<Job>()
        synchronized(stateLock) {
            val sid = ticket.key.sessionId
            val owner = ticket.ownerState
            if (!stillOwnsLocked(sid, owner)) {
                reloadJobs.remove(owner)
                return
            }
            owner.inFlight = false
            reloadJobs.remove(owner)
            // #4b: only clear dirty on success if NO newer demand arrived since
            // launch — else the newer demand is retained + trailing scheduled.
            val newerDemand = owner.demandVersion != ticket.demandVersion
            when (outcome) {
                ReloadOutcome.CommittedNonEmpty -> {
                    if (ticket.target?.isComplete == true) {
                        owner.lastSuccessfullyReloadedTarget = ticket.target
                    }
                    owner.retryAttempt = 0
                    owner.boundedRetriesExhausted = false
                    if (!newerDemand) owner.dirty = false
                    scheduleTrailingLocked(owner, toStart)
                }
                ReloadOutcome.Empty -> when {
                    // R1: content-bearing empty → restore ticket demand + retry.
                    ticket.requiresContent &&
                        ticket.reasons.none { it.confirmsAuthoritativeEmpty } -> {
                        restoreTicketAsDirtyLocked(owner, ticket)
                        scheduleBoundedRetryLocked(owner, ReloadReason.EMPTY_PAGE_RETRY, toStart)
                    }
                    // Authoritative-empty (separately confirmed) → consume dirty
                    // (only if no newer demand arrived and the ticket carries
                    // NO content-bearing demand — a merged authoritative-empty +
                    // content digest must NOT lose the content demand).
                    ticket.reasons.any { it.confirmsAuthoritativeEmpty } -> {
                        val ticketHasContentDemand = ticket.requiresContent ||
                            ticket.reasons.any { it.contentBearing }
                        if (ticketHasContentDemand) {
                            // §rev-gpt fix (blocker #4): mixed authoritative-empty +
                            // content demand. Restore the full ticket demand (content-
                            // bearing reasons/target survive) and schedule a bounded
                            // EMPTY_PAGE_RETRY — only the authoritative-empty probe
                            // portion is consumed; the content-demand side retains dirty
                            // and retries for eventual content (R1 zero-loss).
                            restoreTicketAsDirtyLocked(owner, ticket)
                            scheduleBoundedRetryLocked(owner, ReloadReason.EMPTY_PAGE_RETRY, toStart)
                        } else {
                            // Pure authoritative-empty: consume dirty
                            // (unless newer demand arrived during flight).
                            if (!newerDemand) owner.dirty = false
                            owner.retryAttempt = 0
                            owner.boundedRetriesExhausted = false
                            scheduleTrailingLocked(owner, toStart)
                        }
                    }
                    // §rev-gpt fix (blocker #3): Non-content probe empty → consume
                    // only this ticket's work. Do NOT restore this ticket's own
                    // demand (that would re-queue the same non-content probe,
                    // causing an infinite empty-page loop). If NEWER demand arrived
                    // during flight (a concurrent content-bearing submit), its
                    // dirty is preserved; otherwise dirty is cleared.
                    else -> {
                        if (!newerDemand) owner.dirty = false
                        scheduleTrailingLocked(owner, toStart)
                    }
                }
                // #3: every non-success outcome restores the FULL ticket demand
                // (priority / reasons / requiresContent) before the retry — a
                // FORCE that fails retries as limit=200, a content digest that
                // fails retries as content-bearing (R1 zero-loss preserved).
                ReloadOutcome.Uncommitted -> {
                    restoreTicketAsDirtyLocked(owner, ticket)
                    scheduleBoundedRetryLocked(owner, ReloadReason.EMPTY_PAGE_RETRY, toStart)
                }
                ReloadOutcome.CasRejected -> {
                    restoreTicketAsDirtyLocked(owner, ticket)
                    scheduleBoundedRetryLocked(owner, ReloadReason.EMPTY_PAGE_RETRY, toStart)
                }
                ReloadOutcome.Failed -> {
                    restoreTicketAsDirtyLocked(owner, ticket)
                    scheduleBoundedRetryLocked(owner, ReloadReason.NETWORK_RETRY, toStart)
                }
                // Guard rejected (background/route/identity/bundle moved): restore
                // the full demand, do NOT consume retry budget, do NOT schedule
                // (a foreground/route/identity collector nudges later).
                ReloadOutcome.GuardRejected -> restoreTicketAsDirtyLocked(owner, ticket)
                // #4a: owned-cancellation restores the consumed dirty demand.
                ReloadOutcome.Cancelled -> restoreTicketAsDirtyLocked(owner, ticket)
                ReloadOutcome.Detached -> return
            }
        }
        startJobs(toStart)
    }

    /**
     * Must hold [stateLock]. Restore a non-completed ticket's demand onto
     * [state] so a retry carries the ORIGINAL priority / content requirement
     * (not a downgraded DIGEST). Unions with any demand submitted during
     * in-flight (a concurrent higher-priority submit is preserved). Does NOT
     * touch [ReloadState.target] (it is never cleared at launch, so a fresher
     * concurrent content target is preserved).
     */
    private fun restoreTicketAsDirtyLocked(state: ReloadState, ticket: LaunchTicket) {
        state.dirty = true
        state.queuedPriority = maxPriority(state.queuedPriority, ticket.priority)
        state.queuedReasons = state.queuedReasons + ticket.reasons
        state.queuedRequiresContent = state.queuedRequiresContent || ticket.requiresContent
    }

    /** Must hold [stateLock]. R1 / network bounded backoff (2/4/8/16s, then stop).
     *  `dirty` is ALWAYS retained (the caller already restored it) so an
     *  exhausted state stays dirty for the next external signal. */
    private fun scheduleBoundedRetryLocked(
        state: ReloadState, reason: ReloadReason, toStart: MutableList<Job>,
    ) {
        state.queuedReasons = state.queuedReasons + reason
        state.queuedRequiresContent = state.queuedRequiresContent || reason.contentBearing
        if (state.retryAttempt >= retryDelaysMs.size) {
            // Exhausted: retain dirty, stop auto-retry.
            state.boundedRetriesExhausted = true
            return
        }
        state.boundedRetriesExhausted = false
        val delayMs = retryDelaysMs[state.retryAttempt]
        state.retryAttempt += 1
        state.nextAllowedAt = maxOf(state.nextAllowedAt, monotonicNowMs() + delayMs)
        scheduleTrailingLocked(state, toStart)
    }

    /** Background transition: cancel trailing timers; in-flight completes but
     *  its completion schedules no trailing (foreground.value==false gate). */
    internal fun cancelForBackground() {
        synchronized(stateLock) {
            for ((_, state) in states) {
                state.timerJob?.takeIf { it.isActive }?.cancel()
                state.timerJob = null
            }
        }
    }

    /** True iff [owner] still occupies its (generation, sid) slot — ABA fence. */
    private fun stillOwnsLocked(sid: String, owner: ReloadState): Boolean =
        states[ReloadKey(owner.ownerGeneration, sid)] === owner

    private fun keyForStateLocked(state: ReloadState): ReloadKey? =
        states.entries.firstOrNull { it.value === state }?.key

    private fun isBusy(sid: String): Boolean =
        slices.sessionList.value.sessionStatuses[sid]
            ?.let { it.isBusy || it.isRetry } ?: false

    /** Test-only: true iff the calling thread currently holds [stateLock].
     *  Used by the #1 regression test to prove a LAZY job body does not begin
     *  executing while the lock is held (Main.immediate inline-run hazard). */
    internal fun stateLockHeldForTest(): Boolean = Thread.holdsLock(stateLock)

    /**
     * Test-only: cancels the in-flight reload Job for [(generation, sid)] WITHOUT
     * removing the state slot — so the [Cancelled] outcome runs with the state
     * still owned. This exercises the genuine owned-cancellation path (not
     * detached-no-op). No-op if no in-flight job is found.
     */
    internal fun cancelInFlightForTest(generation: Long, sid: String) {
        val job = synchronized(stateLock) {
            val key = ReloadKey(generation, sid)
            val st = states[key]
            if (st == null || !st.inFlight) null
            else reloadJobs[st]
        }
        job?.cancel(CancellationException("test-owned-cancellation"))
    }

    /**
     * Confirmed session deletion / lifecycle dispose: detach the slot and
     * cancel+join its in-flight job (prevents a detached completion from
     * dispatching). Lock under [stateLock] for the detach, join outside.
     */
    suspend fun onSessionClosed(sessionId: String) {
        val removalKeys = mutableListOf<IncarnationKey>()
        val jobs = synchronized(stateLock) {
            val matched = states.entries
                .filter { it.key.sessionId == sessionId }
                .map { it.value }
            val gen = matched.firstOrNull()?.ownerGeneration
            matched.flatMap { st ->
                val key = ReloadKey(st.ownerGeneration, sessionId)
                states.remove(key)
                val timerJob = st.timerJob
                st.timerJob = null
                val reloadJob = reloadJobs.remove(st)
                listOfNotNull(timerJob, reloadJob)
            }.also {
                // Collect locallyInjected entries matching the closed
                // (generation, sessionId) — all route incarnations of this
                // session are closed.
                if (gen != null) {
                    removalKeys.addAll(locallyInjected.keys.filter {
                        it.generation == gen && it.sessionId == sessionId
                    })
                }
            }
        }
        jobs.forEach { runCatching { it.cancelAndJoin() } }
        // Only remove the closed incarnation's markers. A new incarnation
        // (different routeInstance) that registered markers during
        // cancelAndJoin survives because its IncarnationKey differs.
        removalKeys.forEach { locallyInjected.remove(it) }
    }

    // ── Authoritative-window merge (algorithm preserved; commit verdict added) ──
    //
    // Caller holds session mutex + stateLock (see commitReload). Returns whether
    // the reducer accepted (dispatchAndVerify). Empty page handled by the caller
    // (never reaches here). locallyInjected clear moved AFTER verified commit
    // (§B: a rejected route/host commit must not destroy the local-injection
    // guard).

    @Suppress("UNUSED_PARAMETER")
    private fun mergeSkeletonIntoChatSlice(ticket: LaunchTicket, page: MessagesPage): Boolean {
        val sessionId = ticket.key.sessionId
        // Single chat snapshot; all src* read from this one to stay self-consistent.
        val chat = slices.chat.value
        val srcMessages = chat.messages
        val srcParts = chat.partsByMessage
        val srcStreamingTexts = chat.streamingPartTexts
        val srcStreamingReasoning = chat.streamingReasoningPart
        val srcStreamOwned = chat.streamOwned
        val srcCursor = chat.olderMessagesCursor
        val srcHasMore = chat.hasMoreMessages

        // Defensive ascending sort (N ≤ 200); concatenation order depends on it.
        val fetched = page.items.map { it.info }
            .sortedWith(compareBy({ it.time?.created ?: Long.MAX_VALUE }, { it.id }))
        val fetchedParts = page.items.associate { it.info.id to it.parts }
        val fetchedIds = fetched.mapTo(HashSet()) { it.id }

        // NOTE: locallyInjected clear moved to AFTER the verified commit below.
        // Use generation-keyed lookup (§rev-gpt blocker #5).
        val incarnationKey = IncarnationKey(ticket.key.generation, sessionId, ticket.routeInstance)
        val injectedBeforeClear: Set<String> = locallyInjected[incarnationKey] ?: emptySet()

        val fetchedCreated = fetched.mapNotNull { it.time?.created }
        val oldestFetched = fetchedCreated.minOrNull()
        val newestFetched = fetchedCreated.maxOrNull()

        // ── Deletion detection (containment method) ──
        fun isServerDeleted(m: Message): Boolean {
            if (m.id in fetchedIds) return false
            if (m.id in injectedBeforeClear) return false
            val created = m.time?.created ?: return false
            val oldest = oldestFetched ?: return false
            if (newestFetched != null && created >= newestFetched) return false
            return created > oldest
        }

        // ── Complement-partition merge (survivors are never lost) ──
        val survivors = srcMessages.filterNot(::isServerDeleted)
        val notFetched = survivors.filter { it.id !in fetchedIds }
        val olderKept = notFetched.filter { m ->
            val c = m.time?.created
            c != null && oldestFetched != null && c <= oldestFetched
        }
        val olderKeptIds = olderKept.mapTo(HashSet()) { it.id }
        val newerKept = notFetched.filter { it.id !in olderKeptIds }
        val keptIds = HashSet(olderKeptIds).apply { addAll(newerKept.map { it.id }) }

        val mergedMessages = (olderKept + fetched + newerKept).distinctBy { it.id }

        // ── Parts merge: in-place replace, preserving expanded content ──
        val expandedKeys = chat.partExpandStates
            .filterValues { it is PartExpandState.Loaded }.keys

        val mergedPartsMut = HashMap<String, List<Part>>(srcParts.filterKeys { it in keptIds })
        for ((msgId, fetchedPartList) in fetchedParts) {
            val localById = srcParts[msgId]?.associateBy { it.id }
            mergedPartsMut[msgId] = if (localById == null) fetchedPartList else fetchedPartList.map { fp ->
                val lp = localById[fp.id]
                if (lp != null && PartKey(msgId, fp.id) in expandedKeys &&
                    fp.isTruncatedMarker() && !lp.isTruncatedMarker()
                ) lp else fp
            }
        }

        val liveIds = mergedMessages.mapTo(HashSet()) { it.id }
        mergedPartsMut.keys.retainAll(liveIds)
        var mergedParts: Map<String, List<Part>> = mergedPartsMut

        // ── Historical Guard 1: §flicker-fix (placeholder survival) ──
        val srcSessionStatuses = slices.sessionList.value.sessionStatuses
        val streamingFinalized = srcSessionStatuses[sessionId]
            ?.let { st -> !st.isBusy && !st.isRetry } ?: true
        val streamingPartIds = srcStreamingTexts.keys
        if (!streamingFinalized && streamingPartIds.isNotEmpty()) {
            val withPlaceholders = mergedParts.toMutableMap()
            for ((oldMsgId, oldParts) in srcParts) {
                if (oldMsgId !in liveIds) continue
                for (p in oldParts) {
                    if (p.id in streamingPartIds && (p.isText || p.isReasoning)) {
                        val merged = withPlaceholders[oldMsgId]
                        if (merged == null || merged.none { it.id == p.id }) {
                            withPlaceholders[oldMsgId] = (merged ?: emptyList()) + p
                        }
                    }
                }
            }
            mergedParts = withPlaceholders
        }

        // ── Historical Guard 2: §append-safe + §Q10 overlay guard ──
        val partOwnerIndex = srcParts.entries
            .flatMap { (mid, ps) -> ps.map { it.id to mid } }.toMap()

        val overlayOwnerMsgIds = srcStreamingTexts.keys.mapNotNull { pid ->
            partOwnerIndex[pid]
        }.toSet()
        val overlayFinalized = overlayOwnerMsgIds.isEmpty() ||
            overlayOwnerMsgIds.all { it in fetchedIds }

        val reasoningOwnerMsgId = srcStreamingReasoning?.let { r -> partOwnerIndex[r.id] }
        val reasoningFinalized = reasoningOwnerMsgId == null || reasoningOwnerMsgId in fetchedIds

        val ownedStreamingKeys = srcStreamOwned
            .filterValues { it == StreamOwnedState.STREAMING }.keys
        val legacyWouldClear = streamingFinalized && overlayFinalized
        val authoritative = legacyWouldClear && ownedStreamingKeys.isEmpty()
        val newStreamingTexts = when {
            authoritative -> emptyMap()
            legacyWouldClear -> srcStreamingTexts.filterKeys { it in ownedStreamingKeys }
            else -> srcStreamingTexts
        }
        val newStreamingReasoning =
            if (streamingFinalized && reasoningFinalized && ownedStreamingKeys.isEmpty()) null
            else srcStreamingReasoning

        val srcIds = srcMessages.mapTo(HashSet()) { it.id }
        val deadMsgIds = srcIds - liveIds
        val deadPartIds = deadMsgIds.flatMapTo(HashSet()) { mid ->
            srcParts[mid].orEmpty().map { it.id }
        }
        val prunedStreamingTexts = newStreamingTexts.filterKeys { it !in deadPartIds }
        val prunedReasoning = newStreamingReasoning?.takeUnless { r ->
            partOwnerIndex[r.id]?.let { it in deadMsgIds } == true
        }

        val cursorUnseeded = srcCursor == null
        val historyAlreadyPaged = !cursorUnseeded && olderKept.isNotEmpty()
        val newCursor = if (cursorUnseeded && !historyAlreadyPaged) page.nextCursor else srcCursor
        val newHasMore = if (cursorUnseeded && !historyAlreadyPaged) (page.nextCursor != null) else srcHasMore

        // §D: dispatchAndVerify returns whether the reducer actually committed
        // (route/session/bundle CAS). The marker advances only on a true commit.
        val committed = slices.store.dispatchAndVerify(
            AppAction.ChatContentLoaded(
                sessionId = sessionId,
                expectedRouteInstance = ticket.routeInstance, // captured value
                messages = mergedMessages,
                partsByMessage = mergedParts,
                streamingPartTexts = prunedStreamingTexts,
                streamingReasoningPart = prunedReasoning,
                olderMessagesCursor = newCursor,
                hasMoreMessages = newHasMore,
                currentModel = inferCurrentModel(mergedMessages),
                authoritative = authoritative,
                bundleStamp = ticket.bundleStamp,
            )
        )
        // §B: clear locallyInjected ONLY after a verified commit, so a rejected
        // (stale route/host) commit does not destroy the local-injection guard.
        if (committed) {
            locallyInjected[incarnationKey]?.removeAll(fetchedIds)
        }
        return committed
    }

    // ── Part truncation marker (mirrors ExpandedPartsReconcile.kt:65-67) ──
    private fun Part.isTruncatedMarker(): Boolean = hasFull == true && omitted != null

    /** Test-only scheduler state snapshot (deterministic assertions). */
    internal fun schedulerSnapshotForTest(sid: String, generation: Long): SchedulerSnapshot? {
        synchronized(stateLock) {
            val s = states[ReloadKey(generation, sid)] ?: return null
            return SchedulerSnapshot(
                dirty = s.dirty,
                inFlight = s.inFlight,
                timerActive = s.timerJob?.isActive == true,
                priority = s.queuedPriority,
                retryAttempt = s.retryAttempt,
                demandVersion = s.demandVersion,
                queuedRequiresContent = s.queuedRequiresContent,
                queuedReasons = s.queuedReasons,
                marker = s.lastSuccessfullyReloadedTarget,
            )
        }
    }

    // ── Local-injection marker (synchronous; eliminates registration race) ──
    // Order contract (MANDATORY): callers must markLocallyInjected BEFORE
    // publishing the slice update (see historical kdoc).
    fun markLocallyInjected(sessionId: String, messageId: String) {
        val gen = currentTransport()?.generation ?: 0L
        val route = slices.routeInstanceFor(sessionId)
        locallyInjected.computeIfAbsent(IncarnationKey(gen, sessionId, route)) { ConcurrentHashMap.newKeySet() }.add(messageId)
    }
}
