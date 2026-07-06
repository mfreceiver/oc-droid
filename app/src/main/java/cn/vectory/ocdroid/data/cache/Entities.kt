package cn.vectory.ocdroid.data.cache

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * R-20 Phase 0: cached session metadata.
 *
 * Compound primary key `(serverGroupFp, sessionId)` — NOT a bare `sessionId`.
 * `sessionId` is a server-branded string (`ses_xxxx`) that is **not** a UUID:
 * the same id can appear across distinct server-identities (clone, reset, etc.).
 * `serverGroupFp` (from [cn.vectory.ocdroid.data.model.HostProfile]) namespaces
 * each session to the host-group it was first cached under, so cross-server
 * collisions cannot merge two unrelated sessions into the same row (plan §0
 * "缓存复合键控").
 *
 * Two timestamps (plan §0 时间戳定义):
 *  - [newestCachedAt]: the time of the newest cached MESSAGE for this session
 *    (content freshness — drives LRU-50 + 30d age rules). NOT "user last
 *    opened"; simply opening a session does NOT refresh it.
 *  - [lastVerifiedAt]: the last time the server confirmed this session is
 *    neither deleted nor archived (drives 7d server-abandon rule + orphan
 *    sweep).
 *
 * Phase 0 stores only schema + CRUD; the LRU-50 / 30d / 7d eviction SQL lands
 * in Phase 3.
 *
 * @param createdAt server-side `Session.time.created` epoch-ms, NULLABLE —
 *   server can return null (momo 实测). The fingerprint verify path treats
 *   `createdAt == null` as UnknownColdStart (no evict, no hydrate), so we
 *   cannot make this non-null without lying about the wire format.
 * @param workdir the workdir (project directory) the session was opened in —
 *   needed so Phase 5 can group cached sessions under the same workdir even
 *   when the user has multiple接入点 (entry points) into the same server.
 */
@Entity(
    tableName = "cached_session",
    primaryKeys = ["server_group_fp", "session_id"]
)
data class CachedSessionEntity(
    @ColumnInfo(name = "server_group_fp") val serverGroupFp: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long?,
    @ColumnInfo(name = "newest_cached_at") val newestCachedAt: Long,
    @ColumnInfo(name = "last_verified_at") val lastVerifiedAt: Long,
    @ColumnInfo(name = "workdir") val workdir: String
)

/**
 * R-20 Phase 0: cached message row.
 *
 * Compound primary key `(serverGroupFp, sessionId, messageId)` — same
 * rationale as [CachedSessionEntity] plus `messageId` uniqueness within a
 * session.
 *
 * [parts] is a JSON blob (no separate CachedPartEntity table — dser/glmer
 * consensus simplification in plan §1). The blob is the JSON-serialized form
 * of `List<Part>` (see [CacheJson]); the cache layer treats it as an opaque
 * string in Phase 0, Phase 1 does the (de)serialization.
 *
 * @param time the message time epoch-ms used as the ASC sort key (oldest
 *   first) when reading back a slice. Matches
 *   [cn.vectory.ocdroid.data.model.Message.TimeInfo.created] (falling back to
 *   completed); caller decides the source — the cache just orders by it.
 * @param role the message role string ("user" / "assistant" / "system" /
 *   "tool" / "environment" / ...).
 */
@Entity(
    tableName = "cached_message",
    primaryKeys = ["server_group_fp", "session_id", "message_id"]
)
data class CachedMessageEntity(
    @ColumnInfo(name = "server_group_fp") val serverGroupFp: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "time") val time: Long,
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "parts") val parts: String
)
