package cn.vectory.ocdroid.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Session(
    val id: String,
    val slug: String? = null,
    @SerialName("projectID") val projectId: String? = null,
    val directory: String,
    @SerialName("parentID") val parentId: String? = null,
    val title: String? = null,
    val version: String? = null,
    val time: TimeInfo? = null,
    val share: ShareInfo? = null,
    val summary: SummaryInfo? = null,
    val revert: RevertInfo? = null,
    // §Q2: server `/session` carries `agent` + nested `model` (id /
    // providerID / variant) used by the chat top-bar to surface the
    // session's effective agent/model ahead of any transcript inference.
    // Both nullable with defaults so the cache round-trip
    // (SessionCacheEntry→Session via toSession()) compiles unchanged —
    // cached entries omit these (server refresh backfills, matching
    // slug/projectId/share/version).
    val agent: String? = null,
    val model: ModelInfo? = null,
) {
    /** Display name for UI: title, or last path segment of directory, or id */
    val displayName: String
        get() = title ?: directory.split("/").filter { it.isNotEmpty() }.lastOrNull() ?: id

    val isArchived: Boolean get() = (time?.archived ?: 0L) > 0L

    /**
     * §Q2: server-side model descriptor on a session. Field names mirror the
     * server JSON exactly (note `providerID` capitalisation). All nullable +
     * defaulted so a partial server payload or a cache-reinflated Session
     * (which leaves this null) deserialises without MissingFieldException.
     */
    @Serializable
    data class ModelInfo(
        val id: String? = null,
        @SerialName("providerID") val providerId: String? = null,
        val variant: String? = null,
    )

    @Serializable
    data class TimeInfo(
        val created: Long? = null,
        val updated: Long? = null,
        val archived: Long? = null
    )

    @Serializable
    data class ShareInfo(
        val url: String? = null
    )

    @Serializable
    data class SummaryInfo(
        val additions: Int? = null,
        val deletions: Int? = null,
        val files: Int? = null
    )

    @Serializable
    data class RevertInfo(
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String? = null,
        val snapshot: String? = null,
        val diff: String? = null
    )
}

data class RevertCutoff(
    val sessionId: String,
    val messageId: String,
    val state: RevertCutoffState
)

sealed interface RevertCutoffState {
    data class Resolved(val createdAtEpochMs: Long) : RevertCutoffState
    data object PendingFetch : RevertCutoffState
    data object NoTimestamp : RevertCutoffState
    data object Failed : RevertCutoffState
}

@Serializable
data class SessionStatus(
    val type: String,
    val attempt: Int? = null,
    val message: String? = null,
    val next: Long? = null
) {
    val isIdle: Boolean get() = type == "idle"
    val isBusy: Boolean get() = type == "busy"
    val isRetry: Boolean get() = type == "retry"
}

/**
 * Minimal persisted projection of a [Session] used to seed the UI on cold
 * start before the server list has loaded. Carries only the fields required
 * to render tabs, the title, workdir grouping and MRU sorting — everything
 * else (slug, projectId, share, version) is left to server refresh. A revert
 * target and its resolved timestamp are retained because the transcript gate
 * must remain fail-closed before a server refresh completes.
 *
 * See [toCacheEntry]/[toSession] for the lossy mapping (non-cached Session
 * fields default to null).
 */
@Serializable
data class SessionCacheEntry(
    val id: String,
    val title: String? = null,
    val directory: String,
    val parentId: String? = null,
    val timeCreated: Long? = null,
    val timeUpdated: Long? = null,
    val timeArchived: Long? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val files: Int? = null,
    val revertMessageId: String? = null,
    val revertCreatedAtEpochMs: Long? = null
)

/** Project a [Session] down to its cached metadata. */
internal fun Session.toCacheEntry(): SessionCacheEntry = SessionCacheEntry(
    id = id,
    title = title,
    directory = directory,
    parentId = parentId,
    timeCreated = time?.created,
    timeUpdated = time?.updated,
    timeArchived = time?.archived,
    additions = summary?.additions,
    deletions = summary?.deletions,
    files = summary?.files,
    revertMessageId = revert?.messageId
)

/**
 * Reinflate a [Session] from a cached entry. Non-cached fields (slug,
 * projectId, share, version) default to null so the rendered card
 * shows the cached title/directory/time/summary correctly; a subsequent
 * server refresh replaces it with authoritative data.
 */
internal fun SessionCacheEntry.toSession(): Session = Session(
    id = id,
    directory = directory,
    parentId = parentId,
    title = title,
    time = if (timeCreated != null || timeUpdated != null || timeArchived != null) {
        Session.TimeInfo(created = timeCreated, updated = timeUpdated, archived = timeArchived)
    } else {
        null
    },
    summary = if (additions != null || deletions != null || files != null) {
        Session.SummaryInfo(additions = additions, deletions = deletions, files = files)
    } else {
        null
    },
    revert = revertMessageId?.let { Session.RevertInfo(messageId = it) }
)
