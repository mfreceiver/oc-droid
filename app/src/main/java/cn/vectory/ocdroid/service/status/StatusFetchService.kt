package cn.vectory.ocdroid.service.status

import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.ConnectionRepository
import cn.vectory.ocdroid.data.repository.SessionRepository
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
 * # Contract (slim P2 — single global call)
 *
 * Returns a [Result] of [StatusFetch]:
 *  - **slim mode** (`connectionRepository.usesSlimStatusFanOut`): ONE
 *    `getSlimapiSessionsStatus(dir)` call via [SlimStatusFetchCache] (the
 *    upstream `directory` is a no-op — every call returns the host-wide
 *    global map). If empty registered workdirs (cold-start) → success-empty.
 *    Failure → `Result.failure` (all-workdirs-failed semantics).
 *    `failedWorkdirs` is always `emptySet()` because a single global call
 *    either succeeds for ALL registered workdirs or fails for ALL — there
 *    is no per-directory partial failure.
 *  - **legacy mode**: a single host-global `getSessionStatus()` call;
 *    `failedWorkdirs` is always empty (byte-for-byte the pre-T-R1 fold).
 *
 * The carrier's `failedWorkdirs` field is kept for structural compatibility
 * with the downstream caller; on the slim path it is always empty.
 *
 * Pure-ish: performs network I/O (the ONLY impurity — no state mutation, no
 * clock read, no injected mutable holder). Deterministic given the repository
 * responses.
 */
@Singleton
class StatusFetchService @Inject internal constructor(
    private val sessionRepository: SessionRepository,
    private val connectionRepository: ConnectionRepository,
    private val slimStatusFetchCache: SlimStatusFetchCache,
) {
    /**
     * T-R1 (slimapi R1) 方案A Issue2: carrier for a status-fetch result shared
     * by the slim + legacy branches. Carries the merged status map PLUS the set
     * of workdirs whose per-directory slim fetch FAILED (always empty for
     * legacy, which issues a single host-global call; always empty for slim P2
     * which also issues a single global call — no per-directory partial failure).
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
     * @param cacheKey the host/identity scope key shared across both background
     *  callers so they hit the same [SlimStatusFetchCache] slot. The caller
     *  supplies the active [hostProfileId]; a host switch produces a different
     *  key → automatic cache miss. Defaults to [DEFAULT_CACHE_KEY] when the
     *  host profile is null (e.g. cold-start before connect).
     */
    suspend fun fetch(
        snapshot: StatusSnapshot,
        cacheKey: String = DEFAULT_CACHE_KEY,
    ): Result<StatusFetch> =
        if (connectionRepository.usesSlimStatusFanOut) {
            withContext(Dispatchers.IO) {
                // Slim P2: the upstream `directory` is a no-op — every call
                // returns the SAME host-wide global map. ONE call via the
                // shared background cache covers ALL registered workdirs.
                if (snapshot.registeredWorkdirs.isEmpty()) {
                    // No registered workdirs (cold-start): treat as success-empty
                    // (the coverage marker's cold-start guard handles projection).
                    Result.success(StatusFetch(emptyMap(), emptySet()))
                } else {
                    val dir = snapshot.registeredWorkdirs.first()
                    // .map transforms success → StatusFetch; failure propagates
                    // naturally as Result.failure, matching the legacy
                    // all-workdirs-failed contract.
                    slimStatusFetchCache.fetchGlobal(dir, cacheKey)
                        .map { StatusFetch(it, emptySet()) }
                }
            }
        } else {
            // Legacy: single host-global call. failedWorkdirs is always empty,
            // so the reducer's fold is byte-for-byte identical to the pre-T-R1 path.
            withContext(Dispatchers.IO) {
                sessionRepository.getSessionStatus().map { StatusFetch(it, emptySet()) }
            }
        }

    companion object {
        /**
         * Shared fallback cacheKey when hostProfileId is null (cold-start before
         * connect). The background caller ([StatusAggregatorImpl.refresh] via
         * [StatusFetchService]) uses this value so the cache slot is stable
         * even when no host is connected yet.
         */
        const val DEFAULT_CACHE_KEY = "global"
    }
}
