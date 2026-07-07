package cn.vectory.ocdroid.data.cache

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

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

/**
 * R-20 Phase 2 (plan §1 / §3): a history gap marker for the non-contiguous
 * message model (slice + gap marker, ≥1 gap per session).
 *
 * The cache stores a session's messages as one or more contiguous SLICES; a
 * [GapMarkerEntity] records that a slice boundary exists between
 * [lowerAnchorMessageId] (the newest message of the OLDER slice — the fill
 * resolution target) and [upperBoundaryMessageId] (the oldest message of the
 * NEWER slice — the visual seam). The gap is paged backward (server `before`
 * cursor) from [nextBeforeCursor] until [lowerAnchorMessageId] reappears in a
 * step → the gap resolves and the row is deleted.
 *
 * Primary key is the synthetic [gapId] (UUID generated at openGap time) so a
 * single session may carry multiple independent gaps, each with its own cursor
 * + state. The compound index `(server_group_fp, session_id)` scopes queries
 * to one session under one host group (plan §0 复合键控 — ses_xxxx is a branded
 * string, not a UUID, so the server-group fp namespaces it).
 *
 * [state] stores [cn.vectory.ocdroid.ui.chat.GapFillState].name — the four
 * states (idle / filling / exhausted / error) drive both the UI divider label
 * and the coordinator's resume decisions. Stored as TEXT rather than an enum
 * so a future state addition does not require a migration (forward-compatible
 * decode: unknown → idle).
 *
 * Two timestamps mirror the CachedSession pattern: [createdAt] is the openGap
 * time; [updatedAt] bumps on every appendOlderSlice / setGapState so stale
 * long-open gaps can be aged out (Phase 3).
 *
 * @param lowerAnchorMessageId the newest message id of the OLDER slice — the
 *   fill target. `stepCoversAnchor` returns true once a backward step contains
 *   this id, at which point the gap resolves (the slices are contiguous).
 * @param upperBoundaryMessageId the oldest message id of the NEWER slice — the
 *   visual seam where the UI renders the gap divider. Advances toward the
 *   anchor as each appendOlderSlice lands an older slice above it.
 * @param nextBeforeCursor the server `before` cursor to page the NEXT older
 *   step from. `null` means history is exhausted below this gap (state →
 *   exhausted). The cursor is the raw server response-header value
 *   (`base64url({id,time_ms})`) — the client NEVER synthesizes it.
 * @param state [cn.vectory.ocdroid.ui.chat.GapFillState].name.
 */
@Entity(
    tableName = "gap_marker",
    primaryKeys = ["gap_id"],
    indices = [Index("server_group_fp", "session_id")]
)
data class GapMarkerEntity(
    @ColumnInfo(name = "gap_id") val gapId: String,
    @ColumnInfo(name = "server_group_fp") val serverGroupFp: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "lower_anchor_message_id") val lowerAnchorMessageId: String,
    @ColumnInfo(name = "upper_boundary_message_id") val upperBoundaryMessageId: String,
    @ColumnInfo(name = "next_before_cursor") val nextBeforeCursor: String?,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
