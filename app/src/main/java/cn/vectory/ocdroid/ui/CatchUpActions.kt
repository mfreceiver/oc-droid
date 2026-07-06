package cn.vectory.ocdroid.ui

/**
 * §R-17 batch3d: Domain orchestration free functions. These are NOT the deleted
 * batch-2 AppState mirror helpers (aggregateFromSlices/syncSlicesFromAppState etc.).
 * They are coroutine-launch helpers called by the domain ViewModels and AppCore
 * orchestration extensions to perform async operations (load/refresh/mutate).
 * Future cleanup (batch3e+): may be inlined into individual VM private methods.
 */

import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
 *
 * §R-17 batch2 step e final: slices are the sole authoritative store; the
 * `state` mirror + null-slices fallback were removed.
 */
internal fun launchCatchUp(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    sessionId: String,
    settingsManager: SettingsManager? = null,
    onCacheWindow: (sessionId: String, window: CachedSessionWindow) -> Unit = { _, _ -> }
) {

    // §Phase1B (gpt-2 S3 / glm-1 🟡-1): synchronous check-and-set (mirrors
    // launchLoadMessages) to close the race where two concurrent catch-up
    // triggers each pass the guard before either sets the flag, firing two
    // probes / tail reloads. Reset on every exit path (skip / success / fail).
    // §R-17 batch2 step e final: slice-only read.
    if (slices.chat.value.isLoadingMessages) return
    slices.mutateChat { c -> c.copy(isLoadingMessages = true) }
    scope.launch {
        // §R-17 batch2 step e final: slice-only read.
        // Order-independent newest id (messages is oldest-first per ora-2).
        val anchorNewestId = slices.chat.value.messages
            .maxByOrNull { it.time?.created ?: -1L }?.id
        val serverNewestId = repository.probeLatestMessageId(sessionId).getOrNull()

        // No newer message on the server → skip the 5-message reload entirely.
        // Preserve any already-open gap (it is still unresolved).
        if (anchorNewestId != null && serverNewestId != null && anchorNewestId == serverNewestId) {
            slices.mutateChat { c -> c.copy(isLoadingMessages = false) }
            return@launch
        }

        repository.getMessagesPaged(sessionId, MainViewModelTimings.catchUpMessagePageSize, before = null)
            .onSuccess { page ->
                // §R-17 batch2 step e final: slice-only reads throughout this
                // synchronous onSuccess block (no writes between here and the
                // slice update below).
                if (sessionId != slices.chat.value.currentSessionId) {
                    slices.mutateChat { c -> c.copy(isLoadingMessages = false) }
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
                val srcMessages = slices.chat.value.messages
                val srcParts = slices.chat.value.partsByMessage
                val olderKept = srcMessages.filter { m ->
                    m.id !in fetchedIds && (oldestFetchedCreated == null ||
                        m.time?.created == null ||
                        m.time.created < oldestFetchedCreated)
                }
                val olderKeptIds = olderKept.map { m -> m.id }.toHashSet()
                val mergedMessages = olderKept + fetchedMessages
                val mergedParts = srcParts.filterKeys { id -> id in olderKeptIds } + fetchedParts

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

                // Capture preserved fields (resetLimit=false) for the cache snapshot.
                val currentCursor = slices.chat.value.olderMessagesCursor
                val currentHasMore = slices.chat.value.hasMoreMessages

                slices.mutateChat { c ->
                    c.copy(
                        messages = mergedMessages,
                        partsByMessage = mergedParts,
                        isLoadingMessages = false,
                        // resetLimit=false: preserve streaming overlay + history cursor
                        // (omit from copy → slice retains current value).
                        gapInfo = newGap,
                        staleNotice = false
                    )
                }
                // selectedAgentName lives in the settings slice (cross-slice write).
                slices.mutateSettings { it.copy(selectedAgentName = agentName ?: it.selectedAgentName) }
                onCacheWindow(
                    sessionId,
                    CachedSessionWindow(
                        messages = mergedMessages,
                        partsByMessage = mergedParts,
                        olderMessagesCursor = currentCursor,
                        hasMoreMessages = currentHasMore
                    )
                )
                // §F4 (gpter 致命#4 / 设计 §1.3/§1.4): catchUp 发现 gap 後自動啟動
                // closeGap (step=3, maxSteps=5)。此時 isLoadingMessages 已在上一行
                // state update 中被置 false，launchCloseGap 的 isLoading 守衛可正常
                // 通過；它內部會重新置 isLoading=true 並 launch 獨立協程走自動閉合循環。
                // newGap==null (無 gap 或 anchor 在窗口內) 時不觸發，避免無謂 fetch。
                if (newGap != null) {
                    launchCloseGap(
                        scope = scope,
                        repository = repository,
                        slices = slices,
                        sessionId = sessionId,
                        onCacheWindow = onCacheWindow,
                    )
                }
            }
            .onFailure {
                // §R-17 batch2 step e final: slice-only read.
                if (sessionId == slices.chat.value.currentSessionId) {
                    reportNonFatalIssue("MainViewModel", "Catch-up tail reload failed")
                }
                slices.mutateChat { c -> c.copy(isLoadingMessages = false) }
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
internal const val GAP_CLOSE_STEP_DEFAULT = MainViewModelTimings.gapCloseMessagePageSize
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
 *
 * §R-17 batch2 step e final: slices are the sole authoritative store.
 */
internal fun launchCloseGap(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    sessionId: String,
    onCacheWindow: (sessionId: String, window: CachedSessionWindow) -> Unit = { _, _ -> },
    // §M2: parameterized step (default 3) replaces the old hardcoded
    // limit=5. SSE-mode callers that omit it get step=3 (acceptable per design
    // §1.4); callers pass step=3 explicitly.
    step: Int = GAP_CLOSE_STEP_DEFAULT,
    // §M2: auto-closure budget cap. Default GAP_CLOSE_MAX_STEPS (=5).
    // Pass 1 to force the legacy single-step-per-call behaviour.
    maxSteps: Int = GAP_CLOSE_MAX_STEPS
) {

    // §R-17 batch2 step e final: slice-only reads. Captured once; this block
    // is synchronous up to the scope.launch below.
    val gap0 = slices.chat.value.gapInfo ?: return
    if (!gap0.open) return
    if (slices.chat.value.isLoadingMessages) return
    if (step <= 0 || maxSteps <= 0) return // nothing to do; leave gap for manual
    if (gap0.tailOldestCursor == null) {
        // No more history to page — can't bridge; stop showing the divider.
        slices.mutateChat { c -> c.copy(gapInfo = gap0.copy(open = false)) }
        return
    }
    slices.mutateChat { c -> c.copy(isLoadingMessages = true) }
    scope.launch {
        var stepsTaken = 0
        var gap = gap0
        var cursor: String? = gap0.tailOldestCursor
        while (true) {
            // §R-17 batch2 step e final: fresh capture each iteration (the
            // prior iteration's slice write may have updated the slice).
            val currentSessionId = slices.chat.value.currentSessionId
            if (sessionId != currentSessionId) break
            val c = cursor
            if (c == null) {
                // History exhausted mid-walk without reaching the anchor →
                // can't bridge; stop showing the divider.
                slices.mutateChat { ch -> ch.copy(gapInfo = ch.gapInfo?.copy(open = false)) }
                break
            }
            val page = repository.getMessagesPaged(sessionId, limit = step, before = c)
                .getOrElse {
                    if (sessionId == currentSessionId) {
                        reportNonFatalIssue("MainViewModel", "Gap closure fetch failed")
                    }
                    slices.mutateChat { ch -> ch.copy(isLoadingMessages = false) }
                    return@launch
                }
            stepsTaken += 1
            // Closure check BEFORE dedup (raw page — `before=` is inclusive).
            val closed = page.items.any { it.info.id == gap.anchorNewestId }
            // §R-17 batch2 step e final: fresh capture after the suspend
            // (slice may have changed during the fetch).
            val currentMessages = slices.chat.value.messages
            val existingIds = currentMessages.map { it.id }.toHashSet()
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
            val insertIdx = currentMessages.indexOfFirst { m -> m.id == gap.tailOldestId }
            val mergedMessages = if (insertIdx >= 0) {
                currentMessages.subList(0, insertIdx) + bridgedMessages + currentMessages.subList(insertIdx, currentMessages.size)
            } else {
                bridgedMessages + currentMessages
            }
            val currentParts = slices.chat.value.partsByMessage
            val newGap = if (closed) null else gap.copy(
                tailOldestId = newTailOldestId ?: gap.tailOldestId,
                tailOldestCursor = page.nextCursor
            )
            slices.mutateChat { ch ->
                ch.copy(
                    messages = mergedMessages,
                    partsByMessage = bridgedParts + currentParts,
                    gapInfo = newGap
                )
            }
            if (closed) break
            // Prepare the next iteration. Re-read the just-written gap so the
            // divider advances; stop if state was unexpectedly cleared.
            gap = slices.chat.value.gapInfo ?: break
            cursor = page.nextCursor
            // §M2 budget cap: stop auto-closure after maxSteps and leave
            // the gap hint open for a manual tap (fresh budget on re-entry).
            if (stepsTaken >= maxSteps) break
        }
        slices.mutateChat { c -> c.copy(isLoadingMessages = false) }
        // §R-17 batch2 step e final: slice-only reads for the cache snapshot.
        val postMessages = slices.chat.value.messages
        val postParts = slices.chat.value.partsByMessage
        val postCursor = slices.chat.value.olderMessagesCursor
        val postHasMore = slices.chat.value.hasMoreMessages
        onCacheWindow(
            sessionId,
            CachedSessionWindow(
                messages = postMessages,
                partsByMessage = postParts,
                olderMessagesCursor = postCursor,
                hasMoreMessages = postHasMore
            )
        )
    }
}
