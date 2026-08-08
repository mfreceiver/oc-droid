package cn.vectory.ocdroid.data.model

import kotlinx.serialization.Serializable

/**
 * Wire DTO: a single directory entry returned by `GET /slimapi/directories`.
 *
 *  - [directory]: server-side normalized directory path (e.g. `/app`).
 *    Normalized client-side via [cn.vectory.ocdroid.data.repository.WorkdirPaths.normalize]
 *    before being compared with the connected-workdir set for disabled-item
 *    detection.
 *  - [title]: optional display label (user-provided display name, or null
 *    when unset).
 *  - [lastUpdated]: epoch-ms of the most recent activity in this directory.
 *    **Nullable**: MRU-synthesized entries (when the sidecar is too old to
 *    serve the slimapi directories endpoint) carry null — the caller
 *    ([cn.vectory.ocdroid.data.repository.OpenCodeRepository]) does NOT
 *    fake a timestamp.
 *  - [activeRootSessionCount]: number of non-archived top-level sessions
 *    currently open in this directory.
 *  - [archivedRootSessionCount]: number of archived top-level sessions.
 *  - [archivedOnly]: when true, all sessions in this directory are archived.
 */
@Serializable
data class DirectoryEntry(
    val directory: String,
    val title: String? = null,
    val lastUpdated: Long? = null,
    val activeRootSessionCount: Int,
    val archivedRootSessionCount: Int,
    val archivedOnly: Boolean,
)

/**
 * Wire envelope returned by `GET /slimapi/directories` (oc-slimapi thin route).
 *
 *  - [items]: the list of directories known to the sidecar, each described
 *    by a [DirectoryEntry]. May be empty when no directories are found or
 *    the sidecar's allowlist is empty.
 *  - [discoveryComplete]: `true` when the sidecar has finished scanning its
 *    configured workdirs (authoritative); `false` when the scan is still in
 *    progress (the list may be partial — the caller uses this to drive the
 *    stale/incomplete UI treatment).
 */
@Serializable
data class DirectoriesEnvelope(
    val items: List<DirectoryEntry>,
    val discoveryComplete: Boolean,
)
