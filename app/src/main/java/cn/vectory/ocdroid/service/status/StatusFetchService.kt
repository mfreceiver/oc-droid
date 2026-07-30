package cn.vectory.ocdroid.service.status

import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §P0-A Lane 2 (B4-c): the network-fetch seam — extracts the REST/slim bulk
 * status GET that USED to live inside [StatusAggregatorImpl.refresh]. Separates
 * "fetch the raw status map from the server" (THIS class) from "reduce it into
 * authority" (the pure [cn.vectory.ocdroid.ui.reduceAuthority] via
 * `dispatch(AppAction.AuthorityEvent(ApplySnapshot))`) and "project authority
 * into the lifecycle verdict" ([StatusAggregatorImpl]).
 *
 * # Why a separate service
 *
 * Pre-Lane-2 the aggregator both fetched AND mutated its own `Aggregate`. That
 * coupled network I/O to the lifecycle projection and left a second writable
 * source (the aggregator's own `entries`) alongside authority (R3 dual-source).
 * Lane 2 makes the aggregator's READ side a pure derivation over
 * `store.state.authority`; the fetch stays here so the aggregator no longer
 * depends on [OpenCodeRepository] at all.
 *
 * # Contract (verbatim from the legacy [StatusAggregatorImpl.refresh] fold)
 *
 * Returns a [Result] of [StatusFetch]:
 *  - **slim mode** (`repository.usesSlimStatusFanOut`): one
 *    `getSlimapiSessionsStatus(dir)` per registered workdir (the sidecar
 *    requires a single directory per call). Merged map + the set of workdirs
 *    whose per-directory call FAILED. Empty-registered-workdirs (cold-start) →
 *    success-empty (the coverage marker's cold-start guard handles projection).
 *    All-workdirs-failed → `Result.failure` so the caller surfaces Unknown.
 *    Partial/full success → `Result.success(StatusFetch(merged, failed))`.
 *  - **legacy mode**: a single host-global `getSessionStatus()` call;
 *    `failedWorkdirs` is always empty (byte-for-byte the pre-T-R1 fold).
 *
 * The carrier's `failedWorkdirs` lets the caller (the aggregator's `refresh`
 * adapter) mark sessions in failed workdirs so the authority reducer's
 * `applySnapshot` (via `partialFailureWorkdirs`) + the coverage predicate
 * (`coveredWorkdirs = registeredWorkdirs - failedWorkdirs`) independently
 * refuse `AllIdleFresh` on a partial slim failure.
 *
 * Pure-ish: performs network I/O (the ONLY impurity — no state mutation, no
 * clock read, no injected mutable holder). Deterministic given the repository
 * responses.
 */
@Singleton
class StatusFetchService @Inject internal constructor(
    private val repository: OpenCodeRepository,
) {
    /**
     * T-R1 (slimapi R1) 方案A Issue2: carrier for a status-fetch result shared
     * by the slim + legacy branches. Carries the merged status map PLUS the set
     * of workdirs whose per-directory slim fetch FAILED (always empty for
     * legacy, which issues a single host-global call).
     *
     * Moved here (was private inside [StatusAggregatorImpl]) so the fetch
     * service and the aggregator's `refresh` adapter share the same shape.
     */
    data class StatusFetch(
        val statuses: Map<String, SessionStatus>,
        val failedWorkdirs: Set<String>,
    )

    /**
     * Fetch the merged status map for [snapshot]'s registered workdirs.
     *
     * @param snapshot carries `registeredWorkdirs` (the slim fan-out target set
     *  + the coverage predicate source) and `sessionsById` (unused here — the
     *  reducer bins `sessionId → workdir`; the fetch only needs the workdir set).
     */
    suspend fun fetch(snapshot: StatusSnapshot): Result<StatusFetch> =
        if (repository.usesSlimStatusFanOut) {
            withContext(Dispatchers.IO) {
                val merged = mutableMapOf<String, SessionStatus>()
                val succeeded = mutableSetOf<String>()
                val failed = mutableSetOf<String>()
                for (dir in snapshot.registeredWorkdirs) {
                    repository.getSlimapiSessionsStatus(dir)
                        .onSuccess { merged.putAll(it); succeeded.add(dir) }
                        .onFailure { failed.add(dir) }
                }
                when {
                    // No registered workdirs (cold-start): treat as success-empty
                    // (the coverage marker's cold-start guard handles projection).
                    snapshot.registeredWorkdirs.isEmpty() ->
                        Result.success(StatusFetch(merged, failed))
                    // All per-directory calls failed → surface as failure so the
                    // projection marks every known session Unknown (NOT Idle),
                    // matching the legacy failure semantics.
                    succeeded.isEmpty() -> Result.failure(
                        java.io.IOException("slim bulk status failed for all registered workdirs")
                    )
                    // Partial/full success → fold the merged map + carry the
                    // failed-workdir set so the fold marks their sessions Unknown.
                    else -> Result.success(StatusFetch(merged, failed))
                }
            }
        } else {
            // Legacy: single host-global call. failedWorkdirs is always empty,
            // so the reducer's fold is byte-for-byte identical to the pre-T-R1 path.
            withContext(Dispatchers.IO) {
                repository.getSessionStatus().map { StatusFetch(it, emptySet()) }
            }
        }
}
