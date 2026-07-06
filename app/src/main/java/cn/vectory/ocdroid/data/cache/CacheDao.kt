package cn.vectory.ocdroid.data.cache

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * R-20 Phase 0: minimal DAO for the cache DB.
 *
 * Phase 0 scope: upsert / read / delete by compound key. The eviction SQL
 * (LRU-50 per serverGroupFp + 30d/7d age filters + orphan sweep) lands in
 * Phase 3 — this interface intentionally does NOT expose eviction yet.
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

    @Query("SELECT * FROM cached_message WHERE server_group_fp = :fp AND session_id = :sid ORDER BY time ASC")
    suspend fun messages(fp: String, sid: String): List<CachedMessageEntity>

    // ───────────────────── delete (session-scoped) ───────────────────────

    @Query("DELETE FROM cached_session WHERE server_group_fp = :fp AND session_id = :sid")
    suspend fun deleteSession(fp: String, sid: String)

    @Query("DELETE FROM cached_message WHERE server_group_fp = :fp AND session_id = :sid")
    suspend fun deleteSessionMessages(fp: String, sid: String)

    // ───────────────────── delete (group-scoped) ─────────────────────────
    // Phase 1 `evictGroup(fp)` will route here; exposed in Phase 0 so the
    // group-scoped contract is testable from the start.

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
