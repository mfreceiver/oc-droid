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
}

