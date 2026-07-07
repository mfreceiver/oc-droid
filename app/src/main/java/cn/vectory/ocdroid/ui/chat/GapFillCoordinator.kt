package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.cache.CacheRepository
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.CachedSessionWindow
import cn.vectory.ocdroid.ui.MainViewModelTimings
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
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
    /**
     * §fix-#3 (gpter #3): provider for the current host's serverGroupFp. The
     * fill loop's slice merges (refreshGapMarkers / snapshotWindow /
     * mergeOlderStep) MUST NOT land in a group the user has since switched
     * away from — a cross-group same-sessionId collision (plan §0 N1) would
     * otherwise pollute the new group's chat slice. Same provider every
     * controller uses (ControllerModule.provideCurrentServerGroupFp).
     */
    @Named("currentServerGroupFp") private val currentServerGroupFp: () -> String = { "" },
) {
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Open a fresh gap for a [BackfillAlgorithm.GapDetection.GapExists] verdict
     * and immediately run the 50-step fill loop. Called by
     * [cn.vectory.ocdroid.ui.launchCatchUp] on the probe path (the caller wraps
     * this in its own `scope.launch` so the fill runs fire-and-forget).
     *
     * [fetched5] are the already-fetched newest 5 (the newer slice ABOVE the
     * gap) — they are merged into the chat slice here so the user sees the new
     * tail immediately, with the gap divider rendered below them.
     */
    suspend fun openAndFill(
        slices: SliceFlows,
        serverGroupFp: String,
        sessionId: String,
        detection: GapDetection.GapExists,
        fetched5: List<Message>,
        fetched5Parts: Map<String, List<Part>>,
        onCacheWindow: (String, CachedSessionWindow) -> Unit,
        onColdSnapshot: (String) -> Unit,
    ) {
        val caughtUp = sessionLocks.computeIfAbsent(sessionId) { Mutex() }.withLock {
            runFill(
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
        // §dser I-2 (gpter review-fix #3 handoff): only fire onColdSnapshot
        // on the normal-completion path (the fill loop actually ran to a
        // resolved / exhausted / no-more-cursor outcome). Early returns
        // (session/fp switch, gap already resolved concurrently) return false
        // and MUST NOT mark the SSE-coverage baseline — the session is not
        // actually caught up, and marking it would defeat the G6 shouldProbe
        // gate for a future reconnect.
        if (caughtUp) onColdSnapshot(sessionId)
    }

    /**
     * Resume / trigger the 50-step fill for an EXISTING gap (manual tap on a
     * divider, or a retry after an Error state). Reads the gap's stored cursor
     * from the cache and pages backward. No-op if the gap no longer exists
     * (resolved by a concurrent fill) or is already Exhausted. The caller wraps
     * this in its own `scope.launch`.
     *
     * §dser I-4: an Exhausted gap MUST NOT be retried — the server already
     * returned a null cursor before reaching the anchor (history ended below
     * the gap), so another fetch would page the same null cursor and waste a
     * request. The UI's Exhausted divider is non-tappable; this guard defends
     * against any future caller that invokes fillSingleGap on an Exhausted
     * marker. Error remains retriable (transient network failure).
     */
    suspend fun fillSingleGap(
        slices: SliceFlows,
        serverGroupFp: String,
        sessionId: String,
        gapId: String,
        onCacheWindow: (String, CachedSessionWindow) -> Unit,
    ) {
        // dser I-4: bail out before acquiring the lock if the gap is Exhausted.
        val existing = cacheRepository.gapsOf(serverGroupFp, sessionId).firstOrNull { it.gapId == gapId }
        if (existing?.fillState == GapFillState.Exhausted) return
        sessionLocks.computeIfAbsent(sessionId) { Mutex() }.withLock {
            runFillForExistingGap(
                slices = slices,
                serverGroupFp = serverGroupFp,
                sessionId = sessionId,
                gapId = gapId,
                onCacheWindow = onCacheWindow,
            )
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
     *
     * §fix-#3 (gpter #3): the entry + per-step guards now re-check the
     * compound key `(serverGroupFp, sessionId)` — a cross-group same-sessionId
     * collision (plan §0 N1) would otherwise pollute the new group's slice
     * after a host switch mid-fill.
     *
     * §fix-#3-b (gpter 复审 #3): each SUSPEND point inside the loop
     * (openGap / getMessagesPaged / appendOlderSlice) is followed by a fresh
     * stillCurrent re-check before any subsequent slice write. The pre-existing
     * per-step guard at the top of the loop only covered the entry to that
     * iteration; suspend points INSIDE the body could land a switch and let
     * the stale merge through.
     *
     * §fix-#2 (gpter #2 / dser B-1): a gap opened with `nextBeforeCursor=null`
     * (rare: a freshly-detected gap whose probe already returned a null cursor)
     * MUST transition to Exhausted — without this guard the `while (cursor !=
     * null)` body never runs, setGapState(Filling) is the last write, and the
     * marker is stuck in Filling forever (non-tappable, no progress).
     *
     * §fix-#4 (gpter 复审 #4): every switch-away return path AFTER
     * setGapState(Filling) resets the marker to Idle. Without this the cache
     * row stays Filling and the UI's switch-back renders a permanent spinner
     * (the divider is non-tappable in Filling, blocking retry). The entry
     * guard + the post-openGap guard run BEFORE Filling is set, so they do
     * not need the reset; the per-step + post-suspend guards inside the loop
     * do. dser I-4's Exhausted guard at fillSingleGap's entry is untouched.
     *
     * §dser I-2: returns true iff the fill ran to a normal-completion outcome
     * (resolved / exhausted / cursor-null at entry) — used by [openAndFill] to
     * gate [onColdSnapshot] on actual catch-up. Returns false on every early
     * return (session/fp switch, gap already resolved, anchor exhausted at
     * entry).
     */
     private suspend fun runFill(
         slices: SliceFlows,
         serverGroupFp: String,
         sessionId: String,
         openGap: OpenGapRequest?,
         onCacheWindow: (String, CachedSessionWindow) -> Unit,
         existingGapId: String? = null,
     ): Boolean {
         // §fix-#3: compound-key guard (fp + sessionId) at entry.
         if (!stillCurrent(slices, serverGroupFp, sessionId)) return false

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
             // §fix-#3-b (gpter 复审 #3): re-check after the openGap suspend.
             // openGap creates the marker in Idle (no Filling→Idle reset needed
             // here — Filling is set further below at setGapState(Filling); the
             // §fix-#4 reset only applies to guards AFTER that transition).
             if (!stillCurrent(slices, serverGroupFp, sessionId)) return false
             // ── Step 0b: merge the prefetched newer slice (the newest-5) ────
             mergeNewerSlice(slices, sessionId, openGap.prefetchedNewer, openGap.prefetchedNewerParts)
         } else {
             gapId = existingGapId
         }
         if (gapId == null) return false

         // Re-load the gap row to get its cursor (openGap stored the probe cursor;
         // an existing gap carries its last-advanced cursor).
         var gap = cacheRepository.gapsOf(serverGroupFp, sessionId).firstOrNull { it.gapId == gapId }
         if (gap == null) return false // already resolved concurrently
         if (gap.fillState == GapFillState.Exhausted) return true // nothing more to page → caught up

         cacheRepository.setGapState(gapId, GapFillState.Filling)
         refreshGapMarkers(slices, serverGroupFp, sessionId)

         var cursor: String? = gap.nextBeforeCursor
         // §fix-#2 (gpter #2 / dser B-1): empty cursor at entry — the gap was
         // opened with nextBeforeCursor=null (history already exhausted at probe
         // time) OR a resumed gap whose cursor was somehow cleared. Mark
         // Exhausted + refresh markers + snapshot the window so the cache +
         // slice converge (without this the marker is stuck Filling forever).
          if (cursor == null) {
              cacheRepository.setGapState(gapId, GapFillState.Exhausted)
              refreshGapMarkers(slices, serverGroupFp, sessionId)
              snapshotWindow(slices, serverGroupFp, sessionId, onCacheWindow)
              return true // exhausted-at-entry counts as caught up
          }
         while (cursor != null) {
             // §fix-#3: re-check the compound key each step (a switch can land
             // mid-loop between two suspend points — openGap / appendOlderSlice
             // / gapsOf).
             // §fix-#4: reset Filling → Idle on switch-away so the cache marker
             // is not stuck (the UI shows a permanent spinner on switch-back
             // otherwise — the Filling divider is non-tappable, blocking retry).
             if (!stillCurrent(slices, serverGroupFp, sessionId)) {
                 cacheRepository.setGapState(gapId, GapFillState.Idle)
                 return false
             }
              val page = repository.getMessagesPaged(
                  sessionId, MainViewModelTimings.gapFillMessagePageSize, before = cursor
              ).getOrElse {
                  DebugLog.e(TAG, "gap fill step failed for session=$sessionId gap=$gapId", it)
                  cacheRepository.setGapState(gapId, GapFillState.Error)
                  refreshGapMarkers(slices, serverGroupFp, sessionId)
                  snapshotWindow(slices, serverGroupFp, sessionId, onCacheWindow)
                  return false
              }

             // §fix-#3-b (gpter 复审 #3): re-check after the getMessagesPaged
             // suspend. The REST fetch can take hundreds of ms; a host/session
             // switch during it would otherwise let the stale page's merge land
             // in the new view.
             // §fix-#4: reset Filling → Idle before switch-away return.
             if (!stillCurrent(slices, serverGroupFp, sessionId)) {
                 cacheRepository.setGapState(gapId, GapFillState.Idle)
                 return false
             }

             val older = page.items.map { it.info }
             val olderParts = page.items.associate { it.info.id to it.parts }
             // appendOlderSlice is the single-transaction resolve / advance /
             // exhaust primitive. It also resolves cross-gap overlaps.
             cacheRepository.appendOlderSlice(gapId, older, olderParts, page.nextCursor)

             // §fix-#3-b (gpter 复审 #3): re-check after the appendOlderSlice
             // suspend (the cache transaction). The slice merge below must NOT
             // land in a view the user has since switched away from.
             // §fix-#4: reset Filling → Idle before switch-away return.
             if (!stillCurrent(slices, serverGroupFp, sessionId)) {
                 cacheRepository.setGapState(gapId, GapFillState.Idle)
                 return false
             }

             // Merge the older step into the chat slice (insert by time, below
             // the gap's upper boundary) so the list grows downward as fill lands.
             mergeOlderStep(slices, sessionId, older, olderParts)

             val anchor = slices.chat.value.messages.firstOrNull { it.id == gap!!.lowerAnchorMessageId }
             val resolved = BackfillAlgorithm.stepCoversAnchor(older, anchor)
              // Refresh the slice markers from the cache (now-accurate state).
              val stillOpen = refreshGapMarkers(slices, serverGroupFp, sessionId)
              snapshotWindow(slices, serverGroupFp, sessionId, onCacheWindow)
              if (resolved) return true
              if (page.nextCursor == null) return true // exhausted (appendOlderSlice set Exhausted)

              gap = cacheRepository.gapsOf(serverGroupFp, sessionId).firstOrNull { it.gapId == gapId }
              if (gap == null) return true // resolved by overlap → caught up
              cursor = gap.nextBeforeCursor
          }
          snapshotWindow(slices, serverGroupFp, sessionId, onCacheWindow)
          return true
      }

    /**
     * §fix-#3 helper: returns true iff `(serverGroupFp, sessionId)` still
     * identifies the user's current chat view. The fill loop checks this at
     * entry + each step so a host switch or session switch mid-fill drops the
     * stale write instead of polluting the new view.
     */
    private fun stillCurrent(slices: SliceFlows, serverGroupFp: String, sessionId: String): Boolean {
        val chat = slices.chat.value
        // Compare fp first (cheap String compare); sessionId second. Both must
        // match — a cross-group same-sessionId collision (plan §0 N1: ses_xxxx
        // branded string, clone/reset server can collide) needs the fp leg to
        // catch the host switch.
        return serverGroupFp == currentServerGroupFp() && sessionId == chat.currentSessionId
    }

    private suspend fun runFillForExistingGap(
        slices: SliceFlows,
        serverGroupFp: String,
        sessionId: String,
        gapId: String,
        onCacheWindow: (String, CachedSessionWindow) -> Unit,
    ) {
        // Clear a stale Error state so the loop may run again on a manual retry
        // (the user explicitly tapped → give the cursor another go). Exhausted
        // is NOT cleared here — fillSingleGap's I-4 guard already bailed out
        // before acquiring the lock, but a defensive Idle reset keeps a stale
        // Idle/Filling (e.g. process restart mid-Filling) retriable.
        cacheRepository.setGapState(gapId, GapFillState.Idle)
        // Manual retry path — the Boolean outcome is unused (no onColdSnapshot
        // gating; only openAndFill's catch-up path marks the SSE baseline).
        runFill(
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
     *
     * §fix-#3-b (gpter 复审 #3): the [CacheRepository.gapsOf] call is suspend;
     * a host/session switch during it would otherwise let fp-A's markers
     * mutate fp-B's slice. After the gapsOf returns, re-check stillCurrent
     * before mutateChat — on switch, return the cache answer (so callers see
     * the gap state) WITHOUT writing the stale markers into the new view.
     */
    private suspend fun refreshGapMarkers(
        slices: SliceFlows,
        serverGroupFp: String,
        sessionId: String,
    ): Boolean {
        val gaps = cacheRepository.gapsOf(serverGroupFp, sessionId)
        if (!stillCurrent(slices, serverGroupFp, sessionId)) return gaps.isNotEmpty()
        slices.mutateChat { it.copy(gapMarkers = gaps) }
        return gaps.isNotEmpty()
    }

    /**
     * Snapshot the current chat window for the cache hook + memory LRU.
     *
     * §fp-guard (R-20 Phase 3 前置, gpter 复审 COND-PASS): the prior guard
     * only checked `currentSessionId != sessionId`. A cross-group same-
     * sessionId collision (plan §0 N1 — `ses_xxxx` is a branded string,
     * clone/reset server can collide) would let a fill targeting fp-A write
     * its chat window back through the cache hook into fp-B's LRU slot +
     * persistent cache. The fix adds the fp leg: if the snapshot's
     * `serverGroupFp` no longer matches `currentServerGroupFp()` (the user
     * switched hosts mid-fill), drop the snapshot — same drop-on-stale rule
     * [stillCurrent] uses for the slice merges.
     */
    private fun snapshotWindow(
        slices: SliceFlows,
        serverGroupFp: String,
        sessionId: String,
        onCacheWindow: (String, CachedSessionWindow) -> Unit,
    ) {
        if (serverGroupFp != currentServerGroupFp()) return
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
