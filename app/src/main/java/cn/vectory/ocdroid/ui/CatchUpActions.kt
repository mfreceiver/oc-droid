package cn.vectory.ocdroid.ui

/**
 * §R-17 batch3d: Domain orchestration free functions. These are NOT the deleted
 * batch-2 AppState mirror helpers (aggregateFromSlices/syncSlicesFromAppState etc.).
 * They are coroutine-launch helpers called by the domain ViewModels and AppCore
 * orchestration extensions to perform async operations (load/refresh/mutate).
 * Future cleanup (batch3e+): may be inlined into individual VM private methods.
 */

import cn.vectory.ocdroid.ui.controller.CachedSessionWindow
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Catch-up after a reconnect (server.connected, not the first connect) or a
 * medium foreground return. Cheapest-first:
 *
 * 1. G6 gate ([shouldProbeCatchUp]): if the SSE feed is reliably covering this
 *    session's workdir AND the session was cold-snapshotted, skip the REST
 *    probe entirely (the live feed delivers updates). Defaults
 *    (sseCurrentWorkdir=null, sessionsEverColdSnapshotted=emptySet) ⇒ not
 *    covered ⇒ always probe (preserves the legacy behaviour for callers that
 *    do not wire G6 inputs).
 * 2. Cheap probe: if the server's newest id == the local newest id, nothing
 *    arrived during the outage → skip the reload (the big traffic saving).
 * 3. Probe the newest [MainViewModelTimings.catchUpProbePageSize] (=5) and
 *    merge the fetched window into the slice via [mergeProbeIntoSlice]
 *    (resetLimit=false semantics). The non-contiguous gap mechanism was
 *    removed in remove-message-persistence Task 4 — a probe page that misses
 *    the local anchor simply merges (the bigger history is recoverable via
 *    the manual "load more" pager, not via an automatic backfill).
 *
 * On any successful catch-up the [onColdSnapshot] callback fires so the caller
 * (SessionSyncCoordinator) can add the session to `sessionsEverColdSnapshotted`
 * — the G6 baseline for future probe gating.
 *
 * Does NOT touch `olderMessagesCursor`/`hasMoreMessages` (resetLimit=false).
 * No-op when a load is already in flight (coalesced via `isLoadingMessages`).
 */
internal fun launchCatchUp(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    sessionId: String,
    settingsManager: SettingsManager? = null,
    onCacheWindow: (sessionId: String, window: CachedSessionWindow) -> Unit = { _, _ -> },
    /**
     * §fix-#3 (gpter #3): provider for the current host's serverGroupFp. Used
     * as the cache compound key. Default { "" }.
     */
    currentServerGroupFp: () -> String = { "" },
    /**
     * §fix-#3 (gpter #3): the serverGroupFp captured AT CALL TIME (when the
     * probe REST request was initiated). Compared against
     * [currentServerGroupFp] at onSuccess — a mismatch means the user switched
     * host group during the probe; the stale response must NOT be merged.
     * Defaults to the same value as `currentServerGroupFp()` so legacy callers
     * that wire only the provider get fp-guard = no-op (both sides equal).
     */
    expectedServerGroupFp: String = currentServerGroupFp(),
    /** G6: the SSE job's current workdir, or null if no feed. */
    sseCurrentWorkdir: String? = null,
    /** G6: sessions with an established cold-snapshot baseline. */
    sessionsEverColdSnapshotted: Set<String> = emptySet(),
    /** G6: fired on a successful catch-up to mark the baseline. */
    onColdSnapshot: (String) -> Unit = {},
) {

    // §Phase1B (gpt-2 S3 / glm-1 🟡-1): synchronous check-and-set to close the
    // race where two concurrent catch-up triggers each pass the guard before
    // either sets the flag.
    if (slices.chat.value.isLoadingMessages) return

    // ── G6: SSE-coverage gate ──────────────────────────────────────────────
    val currentWorkdir = settingsManager?.currentWorkdir ?: ""
    if (!shouldProbeCatchUp(
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
        // §history-load-fix round-1: try/catch/finally so a non-cancellation
        // exception inside the lock/merge can't escape and cancel the scope
        // (opuser 🟠-2 / kimo 🟡-5); the finally clears isLoadingMessages on any
        // exit (session-guarded — gpter 🟠).
        try {
        // §R-17 batch2 step e final: slice-only read.
        // Order-independent newest message (messages is oldest-first per ora-2).
        val anchor = slices.chat.value.messages.maxByOrNull { it.time?.created ?: -1L }
        val serverNewestId = repository.probeLatestMessageId(sessionId).getOrNull()

        // No newer message on the server → skip the probe-page reload entirely.
        if (anchor != null && serverNewestId != null && anchor.id == serverNewestId) {
            // §history-load-fix round-2 (gpter 🟠): flag clear deferred to the
            // session-guarded finally (return@launch runs it). probeLatestMessageId
            // suspended — the session may have switched under us — so only mark
            // the cold-snapshot baseline when this is still the current session.
            if (sessionId == slices.chat.value.currentSessionId) {
                onColdSnapshot(sessionId)
            }
            return@launch
        }

        repository.getMessagesPaged(sessionId, MainViewModelTimings.catchUpProbePageSize, before = null)
            .onSuccess { page ->
                // §history-load-fix: serialize the slice mutation per-session so a
                // concurrent launchLoadMessages full-window replace or a
                // launchLoadMoreMessages prepend cannot tear the list / lose the
                // merge (the three load paths now share one session mutex via
                // MessageLoadCoordinator). The probe + page fetch ran OUTSIDE the
                // lock; only the read-compute-write of the chat slice is
                // serialized. Re-validates the compound key INSIDE the lock.
                slices.messageLoadCoordinator.withSessionLock(sessionId) {
                    // §fix-#3 (gpter #3): compound-key guard (fp + sessionId).
                    // The probe REST call suspends; the user may switch session OR
                    // host group during it. A cross-group same-sessionId collision
                    // (plan §0 N1) would otherwise let the stale probe write into
                    // the new group's chat slice. Both legs must match — fp first,
                    // sessionId second.
                    if (sessionId != slices.chat.value.currentSessionId ||
                        currentServerGroupFp() != expectedServerGroupFp
                    ) {
                        // §history-load-fix round-2 (gpter 🟠): stale probe
                        // (compound-key mismatch) — NO-OP. The session-guarded
                        // finally clears isLoadingMessages; clearing here would
                        // clobber a new session's flag.
                    } else {
                        // remove-message-persistence Task 4: the non-contiguous
                        // gap mechanism was deleted. The probe window is always
                        // merged directly (resetLimit=false semantics) — manual
                        // "load more" paging covers any older history.
                        val fetched = page.items.map { it.info }
                        val fetchedParts = page.items.associate { it.info.id to it.parts }
                        val merged = mergeProbeIntoSlice(slices, fetched, fetchedParts)
                        // T1b residual: 4-field catch-up merge (not MessagesMerged).
                        slices.store.dispatch(
                            AppAction.CatchUpMessagesMerged(
                                messages = merged.first,
                                partsByMessage = merged.second,
                            )
                        )
                        // §chat-ux-batch T8 (B3): the legacy
                        // syncAgentFromPage call was deleted here (T7
                        // rewired agent selection to transient
                        // pendingAgent; no global-reseed needed).
                        onCacheWindow(
                            sessionId,
                            snapshotCurrentWindow(slices, sessionId)
                        )
                        onColdSnapshot(sessionId)
                    }
                }
            }
            .onFailure {
                DebugLog.w("Sync", "catch-up probe-page failed: ${it.message}")
                if (sessionId == slices.chat.value.currentSessionId) {
                    reportNonFatalIssue("MainViewModel", "Catch-up tail reload failed")
                }
                // §history-load-fix round-2 (gpter 🟠): flag clear deferred to
                // the session-guarded finally.
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            DebugLog.e("Sync", "catch-up unexpected error", e)
        } finally {
            // §history-load-fix round-1 backstop (opuser 🟠-2 / kimo 🟡-5):
            // guarantee isLoadingMessages cleared on ANY exit (an exception
            // inside the lock/merge can't leave it stuck and block all future
            // loads), session-guarded so a stale response doesn't clobber a new
            // session's flag. Idempotent — the branches above already clear it
            // on the normal paths.
            if (sessionId == slices.chat.value.currentSessionId &&
                slices.chat.value.isLoadingMessages
            ) {
                slices.mutateChat { c -> c.copy(isLoadingMessages = false) }
            }
        }
    }
}

/**
 * G6 / SSE-coverage gate (was the `shouldProbe` member of a since-deleted
 * gap-detection algorithm object; only this semaphore survived the
 * remove-message-persistence Task 4 deletion of the non-contiguous gap
 * mechanism because it gates REST probes, independent of gap state).
 *
 * A session is "covered" (no probe) iff BOTH hold:
 *  - the current SSE job is attached to [currentWorkdir] (i.e.
 *   [sseCurrentWorkdir] != null AND == [currentWorkdir] — the feed is live
 *   for this session's workdir), AND
 *  - [sessionId] ∈ [sessionsEverColdSnapshotted] — a cold snapshot has
 *    already established the session's baseline (so SSE deltas since are
 *    trusted; a never-snapshotted session may have pre-SSE history the
 *    feed never delivered).
 *
 * Returns true (probe) when NOT covered.
 */
private fun shouldProbeCatchUp(
    sessionId: String,
    currentWorkdir: String,
    sessionsEverColdSnapshotted: Set<String>,
    sseCurrentWorkdir: String?
): Boolean {
    val sseCoversThisWorkdir =
        sseCurrentWorkdir != null && sseCurrentWorkdir == currentWorkdir
    val wasColdSnapshotted = sessionId in sessionsEverColdSnapshotted
    val covered = sseCoversThisWorkdir && wasColdSnapshotted
    return !covered
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

// §chat-ux-batch T8 (B3): `syncAgentFromPage(...)` was deleted here. T7
// rewired agent selection to the TRANSIENT `pendingAgent` chat-slice field;
// the legacy "sync global selectedAgentName from per-session override on
// catch-up" path is no longer reachable (the field it wrote was deleted, and
// SettingsManager.getAgentForSession was removed).

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
