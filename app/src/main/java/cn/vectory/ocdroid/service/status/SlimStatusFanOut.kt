package cn.vectory.ocdroid.service.status

import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.StatusOutcome
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.runSuspendCatching
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * §slimapi-client-impl-v1 §G2 (Task 13) — slim on-demand per-session status
 * fan-out use-case (L3 layer, slim-mode ONLY).
 *
 * Differs from [StatusAggregatorImpl.refresh] (the host-wide bulk L3 path):
 * the bulk path issues ONE `GET /slimapi/sessions/status` (host-wide) and
 * folds every returned id into the global busy projection. This use-case
 * is invoked on-demand for a SUBSET of session ids, issuing one per-session
 * `GET /slimapi/sessions/{sid}/status` (T4) per sid concurrently and folding
 * the outcomes into a [StatusFanOutSummary] that the coordinator routes to
 * concrete actions:
 *
 *  - `missingSids` (404 `session_not_found` OR fake-idle cross-check) →
 *    the coordinator emits delete-session effects (the session is gone
 *    upstream; T13-C3 / contract §6 G2: 404 = clear local).
 *  - `retryableCount > 0` (503 / transport fault) → the coordinator asks
 *    the poller to schedule a bounded backoff before the next sweep
 *    (T13-C4).
 *
 * # T13-C6 (legacy bulk untouched)
 *
 * This use-case is invoked ONLY from slim on-demand paths. The legacy
 * non-slim path (`!repository.usesSlimStatusFanOut`) never reaches this use-case;
 * [StatusAggregatorImpl.refresh] stays byte-for-byte unchanged.
 *
 * # Fake-idle cross-check (T13-C5 + round-2 M1)
 *
 * The contract §6 G2 warns: the sidecar's status map can lag a
 * freshly-deleted session, so a 200+`idle` is suspect when the sid is
 * no longer in the local session snapshot. The fan-out cross-checks each
 * `Success(idle)` against the caller-supplied `knownSessionIds` snapshot;
 * an idle for a sid NOT in that snapshot is reclassified as missing (NOT
 * trusted as idle). Busy / retry successes are NOT subject to the
 * cross-check (the sidecar cannot fabricate a busy for a deleted session
 * — busy requires a live upstream turn).
 *
 * **Absent vs empty snapshot (round-2 M1):** the parameter is nullable.
 *  - `null` (absent) — DISABLES the cross-check (the caller has no
 *    snapshot to cross-check against; trust idle as-is).
 *  - non-null (incl. empty) — AUTHORITATIVE the cross-check. An EMPTY
 *    collection means "the server's session list IS empty" → every idle
 *    sid is fake → all reclassify as missing. A non-empty collection is
 *    the normal "session list known" case.
 *
 * # Concurrency
 *
 * §3 performance hint ("可加客户端并发上限（如 4）"): the per-sid fetches
 * run under a [Semaphore]`(4)` so a 50-session sweep does not stampede
 * the sidecar. Mirrors the expand-batch fallback cap + T11's resync
 * semaphore.
 *
 * # CE discipline
 *
 * Each per-sid fetch is wrapped in [runSuspendCatching]; an unexpected
 * throw (T4's [getSlimapiSessionStatusOutcome] is already
 * exception-tolerant — IOException → Retry, SerializationException →
 * UpstreamWarn — so a thrown exception here is defensive only) collapses
 * to a [StatusOutcome.Retry]`(null)` entry (counted in
 * [StatusFanOutSummary.retryableCount]). `coroutineScope` propagates
 * CancellationException correctly; a scope cancel mid-sweep terminates
 * cleanly (per-sid async jobs are cancelled, no partial state mutation
 * lands — the summary is only constructed after `awaitAll`).
 *
 * Construction: plain Kotlin class (no Hilt — wired by the slim
 * integration layer; the existing slim use-cases like
 * [cn.vectory.ocdroid.data.repository.SlimapiMessageMerge] follow the
 * same pattern).
 *
 * @param repository the slim-mode repository (T4's
 *   [getSlimapiSessionStatusOutcome] is consume-only — unchanged by T13).
 * @param concurrency bounds per-sweep concurrency (default 4 per §3
 *   perf-hint). Test-only override; production stays at 4.
 */
class SlimStatusFanOut(
    private val repository: OpenCodeRepository,
    private val concurrency: Int = DEFAULT_CONCURRENCY,
) {

    /**
     * Query the per-session status endpoint for each sid concurrently and
     * fold the outcomes into a [StatusFanOutSummary].
     *
     * @param sids the session ids to query. De-duplicated (order preserved
     *   in the returned [StatusFanOutSummary.perSid] map for deterministic
     *   test output; duplicates collapse to a single GET).
     * @param knownSessionIds the caller's snapshot of currently-known
     *   session ids; used by the fake-idle cross-check (T13-C5). Nullable
     *   to distinguish the round-2 M1 absent-vs-empty cases:
     *    - `null` (absent) — DISABLES the cross-check (the caller has no
     *      snapshot; trust idle as-is). Default for legacy callers that
     *      do not have a snapshot handy.
     *    - non-null (incl. empty) — AUTHORITATIVE cross-check. An EMPTY
     *      collection means "the server's session list IS empty" → every
     *      `Success(idle)` is fake → all reclassify as missing. A non-empty
     *      collection is the normal "session list known" case (idle for a
     *      sid NOT in the collection → missing).
     */
    suspend fun checkSlimSessionsStatuses(
        sids: Collection<String>,
        knownSessionIds: Collection<String>? = null,
    ): StatusFanOutSummary = coroutineScope {
        if (sids.isEmpty()) return@coroutineScope StatusFanOutSummary.Empty
        // LinkedHashSet de-dupes while preserving input order so the
        // returned perSid map is deterministic for tests.
        val distinct = LinkedHashSet(sids).toList()
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        val outcomes: List<Pair<String, StatusOutcome>> = distinct.map { sid ->
            async {
                semaphore.withPermit { sid to fetchOutcome(sid) }
            }
        }.awaitAll()
        val perSid: Map<String, StatusOutcome> = outcomes.toMap()
        foldStatusOutcomes(perSid, knownSessionIds)
    }

    /**
     * Per-sid fetch with [runSuspendCatching] belt-and-suspenders. T4's
     * [OpenCodeRepository.getSlimapiSessionStatusOutcome] is already
     * exception-tolerant (every IOException / SerializationException /
     * unexpected Throwable is caught + collapsed to a [StatusOutcome]
     * variant), so a thrown exception here is truly unexpected. Defensive
     * collapse to [StatusOutcome.Retry]`(sid, null)` keeps the sweep
     * running instead of failing the whole batch — matches the T4
     * transport-failure arm's semantics (transient → retryable).
     *
     * Cancellation is re-thrown by [runSuspendCatching] (it only catches
     * the non-CE family), so a scope cancel propagates cleanly.
     */
    private suspend fun fetchOutcome(sid: String): StatusOutcome =
        runSuspendCatching {
            repository.getSlimapiSessionStatusOutcome(sid)
        }.getOrElse { e ->
            DebugLog.w(TAG, "getSlimapiSessionStatusOutcome sid=$sid threw unexpectedly: ${e.message}")
            StatusOutcome.Retry(sid, null)
        }

    companion object {
        private const val TAG = "SlimStatusFanOut"

        /**
         * §3 perf-hint: per-sweep concurrency bound for the per-sid GETs.
         * Mirrors the expand-batch fallback cap + T11's resync semaphore.
         */
        const val DEFAULT_CONCURRENCY = 4
    }
}

/**
 * T13 — summary of a slim on-demand status fan-out sweep, produced by
 * [SlimStatusFanOut.checkSlimSessionsStatuses] (or pure-folded from a
 * known `Map<String, StatusOutcome>` via [foldStatusOutcomes]).
 *
 * The coordinator's [cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator.applySlimStatusFanOutSummary]
 * consumes these three fields to route the sweep's effects:
 *
 *  - [perSid] — per-session outcome (the raw T4 verdict). Used for
 *    logging / diagnostics + the caller's status-badge fold.
 *  - [retryableCount] — number of sids whose outcome is
 *    [StatusOutcome.Retry] (transient sidecar/upstream/transport fault).
 *    When > 0 the coordinator asks the poller to schedule a bounded
 *    backoff before the next sweep (T13-C4).
 *  - [missingSids] — sids whose session is gone upstream. Two paths land
 *    here: direct 404 (`SessionMissing`) AND fake-idle cross-check
 *    (T13-C5: `Success(idle)` for a sid NOT in the known-ids snapshot).
 *    The coordinator emits a delete-session effect per sid (T13-C3).
 *
 * Deliberately a tiny data class (no methods) so the [foldStatusOutcomes]
 * pure helper stays the single unit-testable surface.
 */
data class StatusFanOutSummary(
    val perSid: Map<String, StatusOutcome>,
    val retryableCount: Int,
    val missingSids: List<String>,
) {
    companion object {
        val Empty: StatusFanOutSummary = StatusFanOutSummary(emptyMap(), 0, emptyList())
    }
}

/**
 * T13 — pure fold: derive a [StatusFanOutSummary] from a per-sid outcome
 * map + the caller's known-ids snapshot. Extracted as a top-level
 * internal helper so unit tests cover the branch table + fake-idle
 * cross-check WITHOUT spinning coroutines (mirrors the
 * [cn.vectory.ocdroid.data.repository.mapStatusOutcome] purity discipline).
 *
 * # Branch table
 *
 * | [StatusOutcome]            | [StatusFanOutSummary.retryableCount] | [StatusFanOutSummary.missingSids] |
 * | ---                        | ---                                  | ---                               |
 * | [StatusOutcome.Success] busy/retry | no                          | no (busy/retry speak truth)       |
 * | [StatusOutcome.Success] idle + sid in known-ids | no              | no (legitimate idle)              |
 * | [StatusOutcome.Success] idle + sid NOT in known-ids | no           | **yes** (T13-C5 fake-idle)        |
 * | [StatusOutcome.SessionMissing] | no                               | **yes** (direct 404)              |
 * | [StatusOutcome.Retry]      | **yes**                              | no                                 |
 * | [StatusOutcome.DirectoryError] / [StatusOutcome.UpstreamWarn] | no | no (caller-alerted; keep local) |
 *
 * @param outcomes the per-sid outcome map (typically from
 *   [SlimStatusFanOut.checkSlimSessionsStatuses]; tests construct directly).
 * @param knownSessionIds the caller's snapshot of currently-known session
 *   ids. **Nullable per round-2 M1:**
 *    - `null` (absent) — DISABLES the fake-idle cross-check (the caller
 *      has no snapshot to cross-check against; trust idle as-is).
 *    - non-null (incl. empty) — AUTHORITATIVE the cross-check. EMPTY =
 *      "server's session list IS empty" → every idle is fake → missing.
 *      Non-empty = the normal "session list known" case (idle for a sid
 *      NOT in the collection → missing).
 */
internal fun foldStatusOutcomes(
    outcomes: Map<String, StatusOutcome>,
    knownSessionIds: Collection<String>?,
): StatusFanOutSummary {
    val missing = mutableListOf<String>()
    var retryable = 0
    // Round-2 M1: distinguish absent (null) from empty (authoritative-empty).
    // null → cross-check DISABLED; non-null (incl. empty) → cross-check ACTIVE.
    val crossCheckActive = knownSessionIds != null
    val knownSet: Set<String> = knownSessionIds?.toSet() ?: emptySet()
    for ((sid, outcome) in outcomes) {
        when (outcome) {
            is StatusOutcome.SessionMissing -> {
                // T13-C2 / T13-C3: 404 = session gone upstream → delete.
                missing.add(sid)
            }
            is StatusOutcome.Retry -> {
                // T13-C2 / T13-C4: 503 / transport → coordinator requests
                // poller backoff.
                retryable++
            }
            is StatusOutcome.Success -> {
                // T13-C2: 200 + busy/retry → caller stores status. NOT
                // subject to the cross-check (busy/retry require a live
                // upstream turn — the sidecar cannot fabricate them for a
                // deleted session).
                //
                // T13-C5 fake-idle cross-check (round-2 M1):
                //  - crossCheckActive == false (absent snapshot) → trust
                //    idle as-is (no false-positive deletes).
                //  - crossCheckActive == true (incl. empty) → idle for a
                //    sid NOT in knownSet → missing. EMPTY knownSet → ALL
                //    idle sids reclassify (the server's session list IS
                //    empty per the caller's authoritative snapshot).
                if (outcome.status.isIdle && crossCheckActive && sid !in knownSet) {
                    missing.add(sid)
                }
            }
            is StatusOutcome.DirectoryError,
            is StatusOutcome.UpstreamWarn -> {
                // Caller-alerted; keep local. No retryable/missing effect.
            }
        }
    }
    return StatusFanOutSummary(outcomes, retryable, missing)
}
