package cn.vectory.ocdroid.service.status

import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.SessionRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SlimApi P2: shared background status-fetch cache. Dedups the two 30s
 * background loops (ProcessStatusPoller via StatusFetchService, and
 * BackgroundUnreadPoller) that both issue a single global
 * `getSlimapiSessionsStatus(directory)` call at each 30s boundary.
 *
 * # Why a cache
 *
 * Upstream `directory` is a no-op — every call returns the SAME global
 * `Map<SID, StatusInfo>`. The two background loops fire at aligned 30s
 * boundaries (both started on foreground→background; both 30s intervals),
 * so the second caller at each boundary can reuse the first caller's
 * result (still within TTL) instead of re-fetching the identical global
 * map. 2 background calls/30s → 1.
 *
 * # Scope (foreground bypasses)
 *
 * ONLY the two background loops route through here. The foreground
 * [cn.vectory.ocdroid.ui.controller.StatusPollOrchestrator] (the SSE-down
 * 4s sweep) calls `repository.getSlimapiSessionsStatus` DIRECTLY — it is
 * the fresh-fallback path and must not be served a cached entry.
 *
 * # TTL + single-flight
 *
 * TTL = [TTL_MS] (10s) — comfortably exceeds the sub-second scheduling
 * jitter between the two aligned 30s callers, so the second caller reliably
 * hits; well under the 30s poll interval, so a served entry is at most
 * ~10s old (far fresher than the 30s poll cadence). A [Mutex] serializes
 * concurrent callers so the second one awaits the first's in-flight fetch
 * then double-checks the cache (classic double-checked-cache). Contention
 * is negligible: exactly 2 callers every 30s. The mutex IS held across the
 * network call — acceptable here because (a) only 2 callers exist, (b)
 * neither holds other locks while waiting, (c) it guarantees correctness
 * over complexity. If a third high-frequency caller is ever added, switch
 * to a CompletableDeferred in-flight tracker.
 *
 * # Identity safety
 *
 * The cache does NOT track identity/host itself. Callers pass a
 * [cacheKey] (hostProfileId) so a host switch naturally produces a
 * different key (old entry unreachable, expires by TTL). Additionally,
 * BOTH callers have their OWN identity guards (ProcessStatusPoller:
 * `identityStore.isCurrent`; BackgroundUnreadPoller: `identityValid`) that
 * drop a result whose identity moved mid-fetch — so a stale cached entry
 * served across a host switch is rejected by the caller's guard. Defense
 * in depth.
 */
@Singleton
class SlimStatusFetchCache @Inject internal constructor(
    private val sessionRepository: SessionRepository,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()

    @Volatile
    private var entry: CacheEntry? = null

    private data class CacheEntry(
        val cacheKey: String,
        val result: Result<Map<String, SessionStatus>>,
        val timestampMs: Long,
    )

    /**
     * Fetch the global slim status map, returning a cached entry when a
     * same-[cacheKey] fetch completed within [TTL_MS].
     *
     * @param directory passed through to [SessionRepository.getSlimapiSessionsStatus]
     *  (upstream no-op; any single directory returns the global map).
     * @param cacheKey the host/identity scope key (hostProfileId, or a
     *  stable fallback when null). Same key → cacheable; different key →
     *  treated as a miss (host switch).
     */
    suspend fun fetchGlobal(
        directory: String,
        cacheKey: String,
    ): Result<Map<String, SessionStatus>> {
        val now = clock()
        val cached = entry
        if (cached != null && cached.cacheKey == cacheKey && now - cached.timestampMs < TTL_MS) {
            return cached.result
        }
        return mutex.withLock {
            // Double-check inside the lock: another caller may have just
            // completed the fetch while we waited for the mutex.
            val afterLock = clock()
            val recached = entry
            if (recached != null && recached.cacheKey == cacheKey &&
                afterLock - recached.timestampMs < TTL_MS
            ) {
                return@withLock recached.result
            }
            val fresh = sessionRepository.getSlimapiSessionsStatus(directory)
            entry = CacheEntry(cacheKey, fresh, clock())
            fresh
        }
    }

    companion object {
        /**
         * Cache TTL. 10s — coalesces the two 30s-aligned background callers
         * (sub-second jitter) while staying well under the 30s poll cadence.
         */
        const val TTL_MS = 10_000L
    }
}
