package com.yage.opencode_client.ui

import com.yage.opencode_client.data.model.Message
import com.yage.opencode_client.data.model.Part
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
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

        repository.getMessagesPaged(sessionId, MainViewModelTimings.catchUpMessagePageSize, before = null)
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

