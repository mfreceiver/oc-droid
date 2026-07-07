package cn.vectory.ocdroid.data.cache

import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * R-20 Phase 3 (plan §3 + v4 freegpt 建议2): owns the daily cache-maintenance
 * pipeline for one server group:
 *
 *  1. **24h dedup** — `SettingsManager.lastSweepEpoch_<fp>` (epoch day) skips
 *     the whole pipeline if today's sweep already ran. A forced re-sweep on
 *     the same day is a no-op (the dedup is intentional — daily sweep is
 *     idempotent; a reconnect within 24h must not re-enumerate + re-evict).
 *  2. **LRU / age eviction** — delegates to [CacheRepository.applyEvictionPolicy]
 *     (LRU-50 per fp + 30d newestCachedAt + 7d lastVerifiedAt). Independent
 *     of the alive set — runs unconditionally on every sweep.
 *  3. **Alive-set enumeration** — [enumerateCompleteAliveSet] is INTERNAL to
 *     this coordinator (plan v4 freegpt 建议2: caller does NOT pass an alive
 *     set; this coordinator owns completeness). Strategy:
 *     - per-workdir `getSessionsForDirectory(workdir, roots=true)` for every
 *       workdir already represented in this fp's cache (the authoritative
 *       source — a workdir's root sessions include those outside the global
 *       first-page limit);
 *     - global `getSessions(limit)` as a catch-all supplement (sessions whose
 *       workdir is not yet cached);
 *     - filter out archived (plan §3 G2 — archived sessions are NOT alive);
 *     - any failure OR a response that hits the enumeration limit (likely
 *       truncated) → `complete = false` → degrade to mark-only.
 *  4. **Orphan sweep OR mark-only** — based on [AliveCompleteness]:
 *     - Complete → [CacheRepository.sweepOrphansWithCompleteAliveSet]
 *       (cached sessions absent from the alive set are evicted + cascaded;
 *       survivors get their `lastVerifiedAt` refreshed).
 *     - Incomplete → [CacheRepository.markSeenAliveOnly] (only confirmed-
 *       alive sessions get `lastVerifiedAt` refreshed; NOTHING is deleted —
 *       the partial alive set may have missed legitimately-alive sessions,
 *       so deletion would risk data loss).
 *
 * **The "no Int.MAX_VALUE" invariant** (plan §3 N4): the enumeration limit is
 * [COMPLETE_ENUMERATION_LIMIT] (10_000) — large enough to cover any real
 * server's session count, small enough to not trigger OS/server-side response
 * caps. A server with >10k sessions is treated as truncated (completeness =
 * false → mark-only) rather than risk a multi-megabyte response.
 *
 * Wired into [cn.vectory.ocdroid.ui.controller.ConnectionCoordinator.testConnection]
 * healthy branch — fire-and-forget on every successful connect.
 */
@Singleton
class CacheMaintenanceCoordinator @Inject constructor(
    private val cache: CacheRepository,
    private val repo: OpenCodeRepository,
    private val settings: SettingsManager
) {
    /**
     * Run the daily sweep for [serverGroupFp] if it has not already run today
     * (epoch-day dedup). Idempotent within a calendar day.
     *
     * Returns a [DailySweepReport] describing the outcome. A dedup-skipped
     * sweep returns an Incomplete report with zero counts (the caller does
     * not need to distinguish "skipped" from "incomplete enumeration" — both
     * mean "no eviction this round").
     */
    suspend fun dailySweepIfNeeded(serverGroupFp: String): DailySweepReport {
        if (serverGroupFp.isBlank()) {
            // Defensive: an empty fp should never reach here (the
            // currentServerGroupFp provider normalizes blank → id), but a
            // blank key would corrupt the SettingsManager key namespace.
            return DailySweepReport(
                serverGroupFp = serverGroupFp,
                completeness = AliveCompleteness.Incomplete,
                verifiedAliveCount = 0,
                evictedSessionIds = emptyList(),
                suspiciousSessionIds = emptyList()
            )
        }

        // ── 24h dedup ───────────────────────────────────────────────────────
        val todayEpochDay = System.currentTimeMillis() / MILLIS_PER_DAY
        val lastSweepDay = settings.getLastSweepEpochDay(serverGroupFp)
        if (lastSweepDay == todayEpochDay) {
            // Already swept today — skip. The daily sweep is idempotent; a
            // reconnect within 24h must not re-enumerate + re-evict.
            return DailySweepReport(
                serverGroupFp = serverGroupFp,
                completeness = AliveCompleteness.Incomplete,
                verifiedAliveCount = 0,
                evictedSessionIds = emptyList(),
                suspiciousSessionIds = emptyList()
            )
        }

        // ── Step 1: LRU / age eviction (independent of alive set) ───────────
        // Runs unconditionally on every sweep. The age rules (30d / 7d) are
        // passive — they trim the cache based on cached-row timestamps alone,
        // no server round-trip needed.
        runCatching { cache.applyEvictionPolicy(serverGroupFp) }
            .onFailure {
                DebugLog.e(
                    TAG,
                    "applyEvictionPolicy failed for fp=$serverGroupFp (continuing to alive sweep)",
                    it
                )
            }

        // ── Step 2: enumerate the alive set + sweep or mark ─────────────────
        val alive = enumerateCompleteAliveSet(serverGroupFp)
        val evictedIds: List<String> = if (alive.complete) {
            // Complete: cached sessions absent from the alive set are orphans
            // (deleted/archived server-side) — evict them.
            runCatching {
                cache.sweepOrphansWithCompleteAliveSet(serverGroupFp, alive.sessionIds).orphanIds
            }.onFailure {
                DebugLog.e(TAG, "sweepOrphansWithCompleteAliveSet failed for fp=$serverGroupFp", it)
            }.getOrDefault(emptyList())
        } else {
            // Incomplete: only mark the sessions we DID confirm; do NOT delete
            // (the partial set may have missed legitimately-alive sessions).
            runCatching { cache.markSeenAliveOnly(serverGroupFp, alive.sessionIds) }
                .onFailure {
                    DebugLog.e(TAG, "markSeenAliveOnly failed for fp=$serverGroupFp", it)
                }
            emptyList()
        }

        // ── Step 3: stamp the sweep day so the next 24h dedup hits ──────────
        // (stamped AFTER the work succeeds — a failed sweep retries next connect.)
        runCatching { settings.setLastSweepEpochDay(serverGroupFp, todayEpochDay) }
            .onFailure { DebugLog.e(TAG, "setLastSweepEpochDay failed for fp=$serverGroupFp", it) }

        return DailySweepReport(
            serverGroupFp = serverGroupFp,
            completeness = if (alive.complete) AliveCompleteness.Complete else AliveCompleteness.Incomplete,
            verifiedAliveCount = alive.sessionIds.size,
            evictedSessionIds = evictedIds,
            // mark-only path does not identify suspicious orphans (the
            // partial alive set cannot distinguish "deleted" from "not yet
            // enumerated"). The next COMPLETE sweep will name them.
            suspiciousSessionIds = emptyList()
        )
    }

    /**
     * INTERNAL: enumerate the complete alive-set for [serverGroupFp] across
     * every available source. Returns the union of:
     *  - per-workdir `getSessionsForDirectory(workdir, roots=true)` for every
     *    workdir already represented in this fp's cache;
     *  - global `getSessions(limit=COMPLETE_ENUMERATION_LIMIT)` as a catch-all.
     *
     * Archived sessions are EXCLUDED from the alive set (plan §3 G2 — they
     * are dead from the cache's perspective even though they still exist
     * server-side).
     *
     * Completeness is set to `false` if any source fails OR returns a response
     * whose size equals the enumeration limit (a strong truncation signal).
     * The caller degrades to mark-only on incomplete.
     */
    private suspend fun enumerateCompleteAliveSet(serverGroupFp: String): AliveSet {
        val sessionIds = LinkedHashSet<String>()
        var complete = true

        // ── Source 1: per-workdir roots for every cached workdir ────────────
        // This is the authoritative path — root sessions of a workdir include
        // those outside the global first-page limit. A workdir that's been
        // connected before has at least one cached session row, so we fan out
        // across all of them.
        val cachedWorkdirs = runCatching { cache.cachedWorkdirsInGroup(serverGroupFp) }
            .onFailure {
                DebugLog.e(TAG, "cachedWorkdirsInGroup failed for fp=$serverGroupFp", it)
                complete = false
            }.getOrDefault(emptyList())

        for (workdir in cachedWorkdirs) {
            repo.getSessionsForDirectory(workdir, limit = COMPLETE_ENUMERATION_LIMIT)
                .onSuccess { sessions ->
                    if (sessions.size >= COMPLETE_ENUMERATION_LIMIT) {
                        // Likely truncated — the server has more sessions for
                        // this workdir than the limit allows. Treat as
                        // incomplete so we degrade to mark-only (deletion
                        // against a partial set would risk data loss).
                        complete = false
                    }
                    sessions.filterNot { it.isArchived }.forEach { sessionIds.add(it.id) }
                }
                .onFailure {
                    DebugLog.w(TAG, "getSessionsForDirectory failed for fp=$serverGroupFp workdir=$workdir: ${it.message}")
                    complete = false
                }
        }

        // ── Source 2: global getSessions (catch-all) ───────────────────────
        // Sessions whose workdir is NOT yet cached (the user connected,
        // switched workdir before any message landed in the cache, etc.) are
        // not covered by the per-workdir fan-out. The global list supplements.
        repo.getSessions(limit = COMPLETE_ENUMERATION_LIMIT)
            .onSuccess { sessions ->
                if (sessions.size >= COMPLETE_ENUMERATION_LIMIT) {
                    complete = false
                }
                sessions.filterNot { it.isArchived }.forEach { sessionIds.add(it.id) }
            }
            .onFailure {
                DebugLog.w(TAG, "getSessions failed for fp=$serverGroupFp: ${it.message}")
                complete = false
            }

        return AliveSet(complete = complete, sessionIds = sessionIds)
    }

    private data class AliveSet(
        val complete: Boolean,
        val sessionIds: Set<String>
    )

    private companion object {
        private const val TAG = "CacheMaintenance"

        /**
         * The bound on a single enumeration request. Plan §3 N4 forbids
         * `Int.MAX_VALUE` (risk of triggering OS / server-side response caps
         * on a server with many sessions). 10_000 is large enough to cover
         * any real opencode-server session count by orders of magnitude, and
         * small enough to stay well under the 32MB response-size guard.
         */
        private const val COMPLETE_ENUMERATION_LIMIT = 10_000

        /** Milliseconds per day (24h). Used for the epoch-day dedup. */
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}
