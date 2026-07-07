package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.cache.CacheRepository
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.CachedSessionWindow
import cn.vectory.ocdroid.ui.MainViewModelTimings
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * R-20 Phase 2 (plan §3): the gap-fill state machine. Drives the backward
 * `before`-cursor paging that bridges a detected gap: probe(5)→detect lives in
 * [cn.vectory.ocdroid.ui.launchCatchUp] + [BackfillAlgorithm]; this coordinator
 * owns the 50-step fill loop once a gap is open.
 *
 * **Session-level Mutex** (plan §3 N3 / freegpt B6/8 / v3 maxer 坑3): all gap
 * fills for ONE session are serialised via [sessionLocks]. This is wider than a
 * per-gapId lock because a single backward step can resolve a *neighbouring*
 * gap (cross-gap overlap — the step's oldest message lands on another gap's
 * lowerAnchor). Serialising at the session level keeps the marker snapshot +
 * the appendOlderSlice transaction consistent across overlapping gaps. A
 * GLOBAL single mutex is explicitly forbidden — it would block parallel fills
 * for different sessions, multiplying tail latency by the session count.
 *
 * The lock map is keyed by `sessionId` (NOT `(fp, sessionId)`): sessionIds are
 * already namespaced at the cache layer by `serverGroupFp`, and the lock only
 * serialises in-session fill coroutines within one host context — a cross-host
 * same-sessionId collision cannot run concurrently here because the coordinator
 * is a single instance bound to the current host.
 *
 * The coordinator does NOT take over the UI's `isLoadingMessages` guard — that
 * stays orthogonal (it gates the message-list load spinner; the fill loop has
 * its own per-gap `GapFillState.Filling` marker rendered independently).
 */
@Singleton
class GapFillCoordinator @Inject constructor(
    private val repository: OpenCodeRepository,
    private val cacheRepository: CacheRepository,
) {
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Open a fresh gap for a [BackfillAlgorithm.GapDetection.GapExists] verdict
     * and immediately run the 50-step fill loop. Called by
     * [cn.vectory.ocdroid.ui.launchCatchUp] on the probe path.
     *
     * [fetched5] are the already-fetched newest 5 (the newer slice ABOVE the
     * gap) — they are merged into the chat slice here so the user sees the new
     * tail immediately, with the gap divider rendered below them.
     */
    fun openAndFill(
        scope: CoroutineScope,
        slices: SliceFlows,
        serverGroupFp: String,
        sessionId: String,
        detection: GapDetection.GapExists,
        fetched5: List<Message>,
        fetched5Parts: Map<String, List<Part>>,
        onCacheWindow: (String, CachedSessionWindow) -> Unit,
        onColdSnapshot: (String) -> Unit,
    ) {
        scope.launch {
            sessionLocks.computeIfAbsent(sessionId) { Mutex() }.withLock {
                runFill(
                    scope = scope,
                    slices = slices,
                    serverGroupFp = serverGroupFp,
                    sessionId = sessionId,
                    // Step 0: open the gap, then merge the fetched newest-5
                    // (the newer slice) + run the backward fill loop.
                    openGap = OpenGapRequest(
                        lowerAnchorMessageId = detection.lowerAnchorMessageId,
                        upperBoundaryMessageId = detection.upperBoundaryMessageId,
                        initialNextBeforeCursor = detection.initialNextBeforeCursor,
                        prefetchedNewer = fetched5,
                        prefetchedNewerParts = fetched5Parts,
                    ),
                    onCacheWindow = onCacheWindow,
                )
            }
            // A successful catch-up cold-snapshot establishes the SSE-coverage
            // baseline (G6) regardless of whether a gap was opened or merged.
            onColdSnapshot(sessionId)
        }
    }

    /**
     * Resume / trigger the 50-step fill for an EXISTING gap (manual tap on a
     * divider, or a retry after an Error state). Reads the gap's stored cursor
     * from the cache and pages backward. No-op if the gap no longer exists
     * (resolved by a concurrent fill) or is already Exhausted.
     */
    fun fillSingleGap(
        scope: CoroutineScope,
        slices: SliceFlows,
        serverGroupFp: String,
        sessionId: String,
        gapId: String,
        onCacheWindow: (String, CachedSessionWindow) -> Unit,
    ) {
        scope.launch {
            sessionLocks.computeIfAbsent(sessionId) { Mutex() }.withLock {
                runFillForExistingGap(
                    scope = scope,
                    slices = slices,
                    serverGroupFp = serverGroupFp,
                    sessionId = sessionId,
                    gapId = gapId,
                    onCacheWindow = onCacheWindow,
                )
            }
        }
    }

    private data class OpenGapRequest(
        val lowerAnchorMessageId: String,
        val upperBoundaryMessageId: String,
        val initialNextBeforeCursor: String,
        val prefetchedNewer: List<Message>,
        val prefetchedNewerParts: Map<String, List<Part>>,
    )

    /**
     * Shared backward-fill loop. After optionally opening the gap + merging any
     * prefetched newer slice, pages [MainViewModelTimings.gapFillMessagePageSize]
     * (=50) messages at a time via the server `before` cursor, calling
     * [CacheRepository.appendOlderSlice] (single-transaction resolve / advance /
     * exhaust) each step, until:
     *  - a step covers the anchor → gap resolved (marker removed);
     *  - the cursor is null → history exhausted (marker → Exhausted);
     *  - a fetch fails → marker → Error (user can retry via fillSingleGap).
     *
     * After each step the chat slice's `messages` (merge) + `gapMarkers` (mirror
     * cache state) are refreshed; [onCacheWindow] snapshots the window so the
     * persistent cache + memory LRU stay in sync.
     */
    private suspend fun runFill(
        scope: CoroutineScope,
        slices: SliceFlows,
        serverGroupFp: String,
        sessionId: String,
        openGap: OpenGapRequest?,
        onCacheWindow: (String, CachedSessionWindow) -> Unit,
        existingGapId: String? = null,
    ) {
        if (sessionId != slices.chat.value.currentSessionId) return

        // ── Step 0a: open the gap (if requested) ────────────────────────────
        val gapId: String?
        if (openGap != null) {
            gapId = cacheRepository.openGap(
                serverGroupFp = serverGroupFp,
                sessionId = sessionId,
                lowerAnchorMessageId = openGap.lowerAnchorMessageId,
                upperBoundaryMessageId = openGap.upperBoundaryMessageId,
                initialNextBeforeCursor = openGap.initialNextBeforeCursor,
            )
            // ── Step 0b: merge the prefetched newer slice (the newest-5) ────
            mergeNewerSlice(slices, sessionId, openGap.prefetchedNewer, openGap.prefetchedNewerParts)
        } else {
            gapId = existingGapId
        }
        if (gapId == null) return

        // Re-load the gap row to get its cursor (openGap stored the probe cursor;
        // an existing gap carries its last-advanced cursor).
        var gap = cacheRepository.gapsOf(serverGroupFp, sessionId).firstOrNull { it.gapId == gapId }
        if (gap == null) return // already resolved concurrently
        if (gap.fillState == GapFillState.Exhausted) return // nothing more to page

        cacheRepository.setGapState(gapId, GapFillState.Filling)
        refreshGapMarkers(slices, serverGroupFp, sessionId)

        var cursor: String? = gap.nextBeforeCursor
        while (cursor != null) {
            if (sessionId != slices.chat.value.currentSessionId) return
            val page = runCatching {
                repository.getMessagesPaged(sessionId, MainViewModelTimings.gapFillMessagePageSize, before = cursor)
            }.getOrElse {
                DebugLog.e(TAG, "gap fill step failed for session=$sessionId gap=$gapId", it)
                cacheRepository.setGapState(gapId, GapFillState.Error)
                refreshGapMarkers(slices, serverGroupFp, sessionId)
                snapshotWindow(slices, sessionId, onCacheWindow)
                return
            }

            val older = page.items.map { it.info }
            val olderParts = page.items.associate { it.info.id to it.parts }
            // appendOlderSlice is the single-transaction resolve / advance /
            // exhaust primitive. It also resolves cross-gap overlaps.
            cacheRepository.appendOlderSlice(gapId, older, olderParts, page.nextCursor)

            // Merge the older step into the chat slice (insert by time, below
            // the gap's upper boundary) so the list grows downward as fill lands.
            mergeOlderStep(slices, sessionId, older, olderParts)

            val anchor = slices.chat.value.messages.firstOrNull { it.id == gap!!.lowerAnchorMessageId }
            val resolved = BackfillAlgorithm.stepCoversAnchor(older, anchor)
            // Refresh the slice markers from the cache (now-accurate state).
            val stillOpen = refreshGapMarkers(slices, serverGroupFp, sessionId)
            snapshotWindow(slices, sessionId, onCacheWindow)
            if (resolved) return
            if (page.nextCursor == null) return // exhausted (appendOlderSlice set Exhausted)

            gap = cacheRepository.gapsOf(serverGroupFp, sessionId).firstOrNull { it.gapId == gapId }
            if (gap == null) return // resolved by overlap
            cursor = gap.nextBeforeCursor
        }
        snapshotWindow(slices, sessionId, onCacheWindow)
    }

    private suspend fun runFillForExistingGap(
        scope: CoroutineScope,
        slices: SliceFlows,
        serverGroupFp: String,
        sessionId: String,
        gapId: String,
        onCacheWindow: (String, CachedSessionWindow) -> Unit,
    ) {
        // Clear a stale Exhausted/Error state so the loop may run again on a
        // manual retry (the user explicitly tapped → give the cursor another go).
        cacheRepository.setGapState(gapId, GapFillState.Idle)
        runFill(
            scope = scope,
            slices = slices,
            serverGroupFp = serverGroupFp,
            sessionId = sessionId,
            openGap = null,
            onCacheWindow = onCacheWindow,
            existingGapId = gapId,
        )
    }

    // ── slice merge helpers ────────────────────────────────────────────────

    /** Merge the newest-5 (the newer slice above the gap) into the chat slice. */
    private fun mergeNewerSlice(
        slices: SliceFlows,
        sessionId: String,
        newer: List<Message>,
        newerParts: Map<String, List<Part>>,
    ) {
        if (newer.isEmpty()) return
        slices.mutateChat { c ->
            val existingIds = c.messages.map { it.id }.toHashSet()
            val toAdd = newer.filterNot { it.id in existingIds }
            if (toAdd.isEmpty()) return@mutateChat c
            // Merge oldest-first by time.created (messages is oldest-first).
            val merged = (c.messages + toAdd).sortedBy { it.time?.created ?: Long.MAX_VALUE }
            c.copy(
                messages = merged,
                partsByMessage = c.partsByMessage + newerParts.filterKeys { it !in existingIds },
            )
        }
    }

    /**
     * Merge one backward-fill step into the chat slice, inserting the older
     * messages at the correct ascending-time position (dedup by id). Mirrors
     * the legacy launchCloseGap insertion: the step sits BETWEEN the anchor
     * (older local history) and the gap's upper boundary.
     */
    private fun mergeOlderStep(
        slices: SliceFlows,
        sessionId: String,
        older: List<Message>,
        olderParts: Map<String, List<Part>>,
    ) {
        if (older.isEmpty()) return
        slices.mutateChat { c ->
            val existingIds = c.messages.map { it.id }.toHashSet()
            val toAdd = older.filterNot { it.id in existingIds }
            if (toAdd.isEmpty()) return@mutateChat c
            val merged = (c.messages + toAdd).sortedBy { it.time?.created ?: Long.MAX_VALUE }
            c.copy(
                messages = merged,
                partsByMessage = c.partsByMessage + olderParts.filterKeys { it !in existingIds },
            )
        }
    }

    /**
     * Re-read the session's open gaps from the cache and mirror them into the
     * chat slice's `gapMarkers`. Returns true iff any gap remains open.
     */
    private suspend fun refreshGapMarkers(
        slices: SliceFlows,
        serverGroupFp: String,
        sessionId: String,
    ): Boolean {
        val gaps = cacheRepository.gapsOf(serverGroupFp, sessionId)
        slices.mutateChat { it.copy(gapMarkers = gaps) }
        return gaps.isNotEmpty()
    }

    /** Snapshot the current chat window for the cache hook + memory LRU. */
    private fun snapshotWindow(
        slices: SliceFlows,
        sessionId: String,
        onCacheWindow: (String, CachedSessionWindow) -> Unit,
    ) {
        val c = slices.chat.value
        if (c.currentSessionId != sessionId) return
        onCacheWindow(
            sessionId,
            CachedSessionWindow(
                messages = c.messages,
                partsByMessage = c.partsByMessage,
                olderMessagesCursor = c.olderMessagesCursor,
                hasMoreMessages = c.hasMoreMessages,
            )
        )
    }

    private companion object {
        private const val TAG = "GapFillCoordinator"
    }
}
