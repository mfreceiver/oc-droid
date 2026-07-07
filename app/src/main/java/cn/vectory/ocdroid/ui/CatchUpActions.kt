package cn.vectory.ocdroid.ui

/**
 * §R-17 batch3d: Domain orchestration free functions. These are NOT the deleted
 * batch-2 AppState mirror helpers (aggregateFromSlices/syncSlicesFromAppState etc.).
 * They are coroutine-launch helpers called by the domain ViewModels and AppCore
 * orchestration extensions to perform async operations (load/refresh/mutate).
 * Future cleanup (batch3e+): may be inlined into individual VM private methods.
 */

import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.chat.BackfillAlgorithm
import cn.vectory.ocdroid.ui.chat.GapDetection
import cn.vectory.ocdroid.ui.chat.GapFillCoordinator
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * R-20 Phase 2 (plan §3): catch-up after a reconnect (server.connected, not the
 * first connect) or a medium foreground return. Cheapest-first:
 *
 * 1. G6 gate ([BackfillAlgorithm.shouldProbe]): if the SSE feed is reliably
 *    covering this session's workdir AND the session was cold-snapshotted, skip
 *    the REST probe entirely (the live feed delivers updates). Defaults
 *    (sseCurrentWorkdir=null, sessionsEverColdSnapshotted=emptySet) ⇒ not
 *    covered ⇒ always probe (preserves the legacy behaviour for callers that
 *    do not wire G6 inputs).
 * 2. Cheap probe: if the server's newest id == the local newest id, nothing
 *    arrived during the outage → skip the reload (the big traffic saving).
 * 3. Probe the newest [MainViewModelTimings.gapProbeMessagePageSize] (=5) and
 *    feed them to [BackfillAlgorithm.detectGap]. The sentinel off-by-one
 *    boundary now sits at exactly-4-new (anchor in the 5th slot → contiguous)
 *    vs exactly-5-new (anchor absent → gap) — the Phase-2 generalisation of the
 *    legacy "fetch 4 = 3 display + 1 sentinel" (preserved by the regression tests).
 * 4. [GapDetection.NoGap] → merge the fetched window (resetLimit=false semantics)
 *    + clear gapMarkers.
 * 5. [GapDetection.GapExists] → delegate to [GapFillCoordinator.openAndFill]
 *    (opens the marker, merges the fetched newest-5, runs the 50-step backward
 *    fill loop). With no coordinator wired (legacy/test path) the fetched
 *    window is still merged; the gap is not tracked.
 *
 * On any successful catch-up the [onColdSnapshot] callback fires so the caller
 * (SessionSyncCoordinator) can add the session to `sessionsEverColdSnapshotted`
 * — the G6 baseline for future probe gating.
 *
 * Does NOT touch `olderMessagesCursor`/`hasMoreMessages` (resetLimit=false).
 * No-op when a load is already in flight (coalesced via `isLoadingMessages`).
 *
 * The legacy single-gap `launchCloseGap` is **removed** (plan §3 N5 / glmer B1);
 * the 50-step backward fill that used to live there is now owned by
 * [GapFillCoordinator] (session-level Mutex + per-gap cursor + cross-gap overlap
 * resolution — none of which the legacy free function could express).
 */
internal fun launchCatchUp(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    sessionId: String,
    settingsManager: SettingsManager? = null,
    onCacheWindow: (sessionId: String, window: CachedSessionWindow) -> Unit = { _, _ -> },
    /**
     * R-20 Phase 2: the gap-fill coordinator. When non-null, a detected gap is
     * delegated to [GapFillCoordinator.openAndFill] (open + 50-step fill). When
     * null (legacy/test path), a detected gap degrades to a plain merge of the
     * fetched window (no marker tracked). Default null preserves backward
     * compat for callers that have not been wired to the coordinator yet.
     */
    gapFillCoordinator: GapFillCoordinator? = null,
    /**
     * R-20 Phase 2: provider for the current host's serverGroupFp. Used as the
     * cache compound key when delegating to the coordinator. Default { "" }.
     */
    currentServerGroupFp: () -> String = { "" },
    /** R-20 Phase 2 (G6): the SSE job's current workdir, or null if no feed. */
    sseCurrentWorkdir: String? = null,
    /** R-20 Phase 2 (G6): sessions with an established cold-snapshot baseline. */
    sessionsEverColdSnapshotted: Set<String> = emptySet(),
    /** R-20 Phase 2 (G6): fired on a successful catch-up to mark the baseline. */
    onColdSnapshot: (String) -> Unit = {},
) {

    // §Phase1B (gpt-2 S3 / glm-1 🟡-1): synchronous check-and-set to close the
    // race where two concurrent catch-up triggers each pass the guard before
    // either sets the flag.
    if (slices.chat.value.isLoadingMessages) return

    // ── R-20 Phase 2 (G6): SSE-coverage gate ───────────────────────────────
    val currentWorkdir = settingsManager?.currentWorkdir ?: ""
    if (!BackfillAlgorithm.shouldProbe(
            sessionId = sessionId,
            currentWorkdir = currentWorkdir,
            sessionsEverColdSnapshotted = sessionsEverColdSnapshotted,
            sseCurrentWorkdir = sseCurrentWorkdir
        )
    ) {
        // SSE is reliably delivering updates for this session's workdir AND a
        // cold snapshot established the baseline → skip the REST probe.
        return
    }

    slices.mutateChat { c -> c.copy(isLoadingMessages = true) }
    scope.launch {
        // §R-17 batch2 step e final: slice-only read.
        // Order-independent newest message (messages is oldest-first per ora-2).
        val anchor = slices.chat.value.messages.maxByOrNull { it.time?.created ?: -1L }
        val serverNewestId = repository.probeLatestMessageId(sessionId).getOrNull()

        // No newer message on the server → skip the probe-page reload entirely.
        // Preserve any already-open gaps (still unresolved).
        if (anchor != null && serverNewestId != null && anchor.id == serverNewestId) {
            slices.mutateChat { c -> c.copy(isLoadingMessages = false) }
            onColdSnapshot(sessionId)
            return@launch
        }

        repository.getMessagesPaged(sessionId, MainViewModelTimings.gapProbeMessagePageSize, before = null)
            .onSuccess { page ->
                if (sessionId != slices.chat.value.currentSessionId) {
                    slices.mutateChat { c -> c.copy(isLoadingMessages = false) }
                    return@onSuccess
                }
                val fetched = page.items.map { it.info }
                val fetchedParts = page.items.associate { it.info.id to it.parts }
                val detection = BackfillAlgorithm.detectGap(anchor, fetched, page.nextCursor)

                when (detection) {
                    is GapDetection.NoGap -> {
                        // Contiguous (anchor in the probe window) OR the probe
                        // short-paged (history exhausted within 5) → merge the
                        // fetched window directly, clear any stale markers.
                        val merged = mergeProbeIntoSlice(slices, fetched, fetchedParts)
                        slices.mutateChat { c ->
                            c.copy(
                                messages = merged.first,
                                partsByMessage = merged.second,
                                isLoadingMessages = false,
                                gapMarkers = emptyList(),
                                staleNotice = false
                            )
                        }
                        syncAgentFromPage(slices, sessionId, page, settingsManager)
                        onCacheWindow(
                            sessionId,
                            snapshotCurrentWindow(slices, sessionId)
                        )
                        onColdSnapshot(sessionId)
                    }
                    is GapDetection.GapExists -> {
                        val coordinator = gapFillCoordinator
                        if (coordinator != null) {
                            // Delegate open + 50-step fill. The coordinator
                            // merges the fetched newest-5 itself, so we only
                            // clear the catch-up loading flag here (the gap's
                            // own Filling state is the per-marker indicator).
                            slices.mutateChat { c -> c.copy(isLoadingMessages = false, staleNotice = false) }
                            syncAgentFromPage(slices, sessionId, page, settingsManager)
                            coordinator.openAndFill(
                                scope = scope,
                                slices = slices,
                                serverGroupFp = currentServerGroupFp(),
                                sessionId = sessionId,
                                detection = detection,
                                fetched5 = fetched,
                                fetched5Parts = fetchedParts,
                                onCacheWindow = onCacheWindow,
                                onColdSnapshot = onColdSnapshot,
                            )
                        } else {
                            // No coordinator wired (legacy/test path): merge the
                            // fetched window; the gap is not tracked.
                            val merged = mergeProbeIntoSlice(slices, fetched, fetchedParts)
                            slices.mutateChat { c ->
                                c.copy(
                                    messages = merged.first,
                                    partsByMessage = merged.second,
                                    isLoadingMessages = false,
                                    gapMarkers = emptyList(),
                                    staleNotice = false
                                )
                            }
                            syncAgentFromPage(slices, sessionId, page, settingsManager)
                            onCacheWindow(
                                sessionId,
                                snapshotCurrentWindow(slices, sessionId)
                            )
                            onColdSnapshot(sessionId)
                        }
                    }
                }
            }
            .onFailure {
                DebugLog.w("Sync", "catch-up probe-page failed: ${it.message}")
                if (sessionId == slices.chat.value.currentSessionId) {
                    reportNonFatalIssue("MainViewModel", "Catch-up tail reload failed")
                }
                slices.mutateChat { c -> c.copy(isLoadingMessages = false) }
            }
    }
}

// ── catch-up merge helpers (extracted from the legacy inline merge) ─────────

/** resetLimit=false selective merge of the fetched probe window into the slice. */
private fun mergeProbeIntoSlice(
    slices: SliceFlows,
    fetched: List<cn.vectory.ocdroid.data.model.Message>,
    fetchedParts: Map<String, List<cn.vectory.ocdroid.data.model.Part>>,
): Pair<List<cn.vectory.ocdroid.data.model.Message>, Map<String, List<cn.vectory.ocdroid.data.model.Part>>> {
    val srcMessages = slices.chat.value.messages
    val srcParts = slices.chat.value.partsByMessage
    val fetchedIds = fetched.map { it.id }.toHashSet()
    val oldestFetchedCreated = fetched.mapNotNull { it.time?.created }.minOrNull()
    val olderKept = srcMessages.filter { m ->
        m.id !in fetchedIds && (oldestFetchedCreated == null ||
            m.time?.created == null ||
            m.time!!.created < oldestFetchedCreated)
    }
    val olderKeptIds = olderKept.map { it.id }.toHashSet()
    val mergedMessages = olderKept + fetched
    val mergedParts = srcParts.filterKeys { id -> id in olderKeptIds } + fetchedParts
    return mergedMessages to mergedParts
}

/** Mirror of the legacy agent-name sync from the last assistant in the page. */
private fun syncAgentFromPage(
    slices: SliceFlows,
    @Suppress("UNUSED_PARAMETER") sessionId: String,
    page: cn.vectory.ocdroid.data.repository.MessagesPage,
    settingsManager: SettingsManager?,
) {
    val lastAssistant = page.items.lastOrNull { it.info.isAssistant }
    val inferredAgentName = lastAssistant?.info?.agent
    val agentName = settingsManager?.getAgentForSession(sessionId) ?: inferredAgentName
    slices.mutateSettings { it.copy(selectedAgentName = agentName ?: it.selectedAgentName) }
}

/** Snapshot the current chat slice into a cache window (for onCacheWindow). */
private fun snapshotCurrentWindow(slices: SliceFlows, sessionId: String): CachedSessionWindow {
    val c = slices.chat.value
    val messages = if (c.currentSessionId == sessionId) c.messages else emptyList()
    val parts = if (c.currentSessionId == sessionId) c.partsByMessage else emptyMap()
    return CachedSessionWindow(
        messages = messages,
        partsByMessage = parts,
        olderMessagesCursor = c.olderMessagesCursor,
        hasMoreMessages = c.hasMoreMessages,
    )
}
