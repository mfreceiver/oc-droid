package cn.vectory.ocdroid.data.cache

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * R-20 Phase 0 → Phase 1: DAO for the cache DB.
 *
 * Phase 0 scope: upsert / read / delete by compound key.
 * Phase 1 additions:
 *  - [cachedCreatedAt] / [touchLastVerifiedAt] / [sessionsInGroup] —
 *    support `verifyAndLoad` (single-transaction fingerprint verify + hydrate)
 *    and `evictGroup`.
 *
 * The eviction SQL (LRU-50 per serverGroupFp + 30d/7d age filters + orphan
 * sweep) lands in Phase 3 — this interface intentionally does NOT expose
 * eviction yet.
 *
 * All queries filter by `server_group_fp` first to keep cross-server rows
 * isolated (plan §0 复合键控).
 */
@Dao
interface CacheDao {
    // ───────────────────── upserts ───────────────────────────────────────

    @Upsert
    suspend fun upsertSession(entity: CachedSessionEntity)

    @Upsert
    suspend fun upsertMessages(messages: List<CachedMessageEntity>)

    // ───────────────────── queries ───────────────────────────────────────

    @Query("SELECT * FROM cached_session WHERE server_group_fp = :fp AND session_id = :sid")
    suspend fun session(fp: String, sid: String): CachedSessionEntity?

    /**
     * R-20 Phase 1: returns ONLY the createdAt fingerprint column. Used by
     * [CacheRepository.verifyAndLoad] / [CacheRepository.verifyFingerprint]
     * so the verify path does not pull the whole row when it only needs to
     * compare the createdAt timestamp.
     */
    @Query("SELECT created_at FROM cached_session WHERE server_group_fp = :fp AND session_id = :sid")
    suspend fun cachedCreatedAt(fp: String, sid: String): Long?

    @Query("SELECT * FROM cached_message WHERE server_group_fp = :fp AND session_id = :sid ORDER BY time ASC")
    suspend fun messages(fp: String, sid: String): List<CachedMessageEntity>

    /**
     * R-20 Phase 1: all cached sessions in [fp]. Used by evictGroup + Phase 3
     * sweep. Returned createdAt column lets callers do an additional
     * fingerprint check if needed (Phase 1 evictGroup does not need it).
     */
    @Query("SELECT * FROM cached_session WHERE server_group_fp = :fp")
    suspend fun sessionsInGroup(fp: String): List<CachedSessionEntity>

    // ───────────────────── updates ───────────────────────────────────────

    /**
     * R-20 Phase 1: refreshes [CachedSessionEntity.lastVerifiedAt] to [now]
     * for one row. Called from inside [CacheRepository.verifyAndLoad]'s
     * single @Transaction once the createdAt fingerprint has matched — a
     * successful verify counts as "the server has confirmed this session
     * exists" (plan §0 lastVerifiedAt semantics: drives the 7d abandon rule
     * in Phase 3).
     */
    @Query("UPDATE cached_session SET last_verified_at = :now WHERE server_group_fp = :fp AND session_id = :sid")
    suspend fun touchLastVerifiedAt(fp: String, sid: String, now: Long)

    /**
     * R-20 Phase 1: refreshes [CachedSessionEntity.newestCachedAt] to [now]
     * iff [now] is newer than the current value. Used by `putSessionWindow`
     * when writing a window whose newest message post-dates the previously
     * cached one (plan §0 newestCachedAt = "newest cached message's time",
     * NOT "user last opened").
     */
    @Query("UPDATE cached_session SET newest_cached_at = MAX(newest_cached_at, :now) WHERE server_group_fp = :fp AND session_id = :sid")
    suspend fun bumpNewestCachedAt(fp: String, sid: String, now: Long)

    // ───────────────────── delete (session-scoped) ───────────────────────

    @Query("DELETE FROM cached_session WHERE server_group_fp = :fp AND session_id = :sid")
    suspend fun deleteSession(fp: String, sid: String)

    @Query("DELETE FROM cached_message WHERE server_group_fp = :fp AND session_id = :sid")
    suspend fun deleteSessionMessages(fp: String, sid: String)

    // ───────────────────── delete (group-scoped) ─────────────────────────
    // Phase 1 `evictGroup(fp)` routes here.

    @Query("DELETE FROM cached_session WHERE server_group_fp = :fp")
    suspend fun deleteGroupSessions(fp: String)

    @Query("DELETE FROM cached_message WHERE server_group_fp = :fp")
    suspend fun deleteGroupMessages(fp: String)

    // ───────────────────── delete (everything) ───────────────────────────
    // Phase 1 `clearAll()` (manual nuke button only) routes here.

    @Query("DELETE FROM cached_session")
    suspend fun clearAllSessions()

    @Query("DELETE FROM cached_message")
    suspend fun clearAllMessages()

    // ───────────── R-20 Phase 3: triple eviction (per serverGroupFp) ──────
    // Plan §3 Phase 3: LRU-50 (per fp) + 30d (newestCachedAt) + 7d
    // (lastVerifiedAt). All cascades run BEFORE the cached_session DELETE so
    // the per-table subqueries see the un-evicted session set (no FK; the
    // cascade is manual). The Repository wraps each policy in a single
    // db.withTransaction so the three statements commit atomically.

    /**
     * R-20 Phase 3: deletes the per-group LRU overflow (sessions whose
     * `newestCachedAt` ranks below the top [limit] for this group). The
     * matching [evictLruExcessMessages] / [GapMarkerDao.evictLruExcessGaps]
     * cascades run in the same transaction (Repository-orchestrated).
     *
     * The subquery is self-contained on `cached_session`: it computes the
     * top-[limit] by `newestCachedAt DESC` for THIS fp, then the outer
     * DELETE removes everything else. Both queries reference the un-modified
     * `cached_session` state because the cascade (messages/gaps) runs first
     * inside the wrapping transaction.
     */
    @Query(
        """
        DELETE FROM cached_session
        WHERE server_group_fp = :fp
          AND session_id NOT IN (
            SELECT session_id FROM cached_session
            WHERE server_group_fp = :fp
            ORDER BY newest_cached_at DESC
            LIMIT :limit
          )
        """
    )
    suspend fun evictLruExcessSessions(fp: String, limit: Int)

    @Query(
        """
        DELETE FROM cached_message
        WHERE server_group_fp = :fp
          AND session_id NOT IN (
            SELECT session_id FROM cached_session
            WHERE server_group_fp = :fp
            ORDER BY newest_cached_at DESC
            LIMIT :limit
          )
        """
    )
    suspend fun evictLruExcessMessages(fp: String, limit: Int)

    /** R-20 Phase 3: 30d age eviction on `newestCachedAt`. */
    @Query("DELETE FROM cached_session WHERE server_group_fp = :fp AND newest_cached_at < :cutoff")
    suspend fun evictByNewestCachedAtSessions(fp: String, cutoff: Long)

    @Query(
        """
        DELETE FROM cached_message
        WHERE server_group_fp = :fp
          AND session_id IN (
            SELECT session_id FROM cached_session
            WHERE server_group_fp = :fp AND newest_cached_at < :cutoff
          )
        """
    )
    suspend fun evictByNewestCachedAtMessages(fp: String, cutoff: Long)

    /** R-20 Phase 3: 7d abandon eviction on `lastVerifiedAt`. */
    @Query("DELETE FROM cached_session WHERE server_group_fp = :fp AND last_verified_at < :cutoff")
    suspend fun evictByLastVerifiedAtSessions(fp: String, cutoff: Long)

    @Query(
        """
        DELETE FROM cached_message
        WHERE server_group_fp = :fp
          AND session_id IN (
            SELECT session_id FROM cached_session
            WHERE server_group_fp = :fp AND last_verified_at < :cutoff
          )
        """
    )
    suspend fun evictByLastVerifiedAtMessages(fp: String, cutoff: Long)

    /**
     * R-20 Phase 3: the set of distinct workdirs cached for [fp]. Used by the
     * [CacheMaintenanceCoordinator] alive-set enumeration to fan out per-
     * workdir `getSessionsForDirectory` roots queries (the authoritative
     * source for "is this session still on the server" — the global
     * `getSessions(limit)` first page can miss workdir-local sessions).
     */
    @Query("SELECT DISTINCT workdir FROM cached_session WHERE server_group_fp = :fp AND workdir != ''")
    suspend fun workdirsInGroup(fp: String): List<String>
}

/**
 * R-20 Phase 2 (plan §1 / §3): DAO for [GapMarkerEntity] — the per-gap cursor
 * + state operations backing the non-contiguous message model.
 *
 * Every query is scoped by `(server_group_fp, session_id)` (the compound
 * session key) or by the synthetic [GapMarkerEntity.gapId]. The single-transaction
 * invariants (appendOlderSlice resolving the gap atomically; resolveGap
 * deleting the marker + cascading overlap resolution) are orchestrated in
 * [CacheRepositoryImpl] via [androidx.room.withTransaction] blocks that call
 * these primitives — the DAO itself stays free of cross-table logic.
 */
@Dao
interface GapMarkerDao {
    @Upsert
    suspend fun upsertGap(entity: GapMarkerEntity)

    @Query("SELECT * FROM gap_marker WHERE gap_id = :gapId")
    suspend fun gap(gapId: String): GapMarkerEntity?

    @Query("SELECT * FROM gap_marker WHERE server_group_fp = :fp AND session_id = :sid")
    suspend fun gaps(fp: String, sid: String): List<GapMarkerEntity>

    @Query("UPDATE gap_marker SET state = :state, updated_at = :now WHERE gap_id = :gapId")
    suspend fun setState(gapId: String, state: String, now: Long)

    @Query("UPDATE gap_marker SET upper_boundary_message_id = :upperBoundary, next_before_cursor = :cursor, state = :state, updated_at = :now WHERE gap_id = :gapId")
    suspend fun advanceBoundary(
        gapId: String,
        upperBoundary: String,
        cursor: String?,
        state: String,
        now: Long
    )

    @Query("DELETE FROM gap_marker WHERE gap_id = :gapId")
    suspend fun deleteGap(gapId: String)

    @Query("DELETE FROM gap_marker WHERE server_group_fp = :fp AND session_id = :sid")
    suspend fun deleteSessionGaps(fp: String, sid: String)

    @Query("DELETE FROM gap_marker WHERE server_group_fp = :fp")
    suspend fun deleteGroupGaps(fp: String)

    @Query("DELETE FROM gap_marker")
    suspend fun clearAllGaps()

    // ───────────── R-20 Phase 3: triple eviction cascades ─────────────────
    // Mirror [CacheDao]'s LRU/30d/7d session deletions so a single
    // db.withTransaction in the Repository evicts session + messages + gaps
    // atomically. The subqueries reference the un-evicted cached_session
    // state (they run BEFORE the session DELETE).

    @Query(
        """
        DELETE FROM gap_marker
        WHERE server_group_fp = :fp
          AND session_id NOT IN (
            SELECT session_id FROM cached_session
            WHERE server_group_fp = :fp
            ORDER BY newest_cached_at DESC
            LIMIT :limit
          )
        """
    )
    suspend fun evictLruExcessGaps(fp: String, limit: Int)

    @Query(
        """
        DELETE FROM gap_marker
        WHERE server_group_fp = :fp
          AND session_id IN (
            SELECT session_id FROM cached_session
            WHERE server_group_fp = :fp AND newest_cached_at < :cutoff
          )
        """
    )
    suspend fun evictByNewestCachedAtGaps(fp: String, cutoff: Long)

    @Query(
        """
        DELETE FROM gap_marker
        WHERE server_group_fp = :fp
          AND session_id IN (
            SELECT session_id FROM cached_session
            WHERE server_group_fp = :fp AND last_verified_at < :cutoff
          )
        """
    )
    suspend fun evictByLastVerifiedAtGaps(fp: String, cutoff: Long)
}

