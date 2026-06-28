package com.yage.opencode_client.data.model

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
    val revert: RevertInfo? = null
) {
    /** Display name for UI: title, or last path segment of directory, or id */
    val displayName: String
        get() = title ?: directory.split("/").filter { it.isNotEmpty() }.lastOrNull() ?: id

    val isArchived: Boolean get() = (time?.archived ?: 0L) > 0L

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
 * else (slug, projectId, share, revert, version) is left to server refresh.
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
    val files: Int? = null
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
    files = summary?.files
)

/**
 * Reinflate a [Session] from a cached entry. Non-cached fields (slug,
 * projectId, share, revert, version) default to null so the rendered card
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
    }
)
