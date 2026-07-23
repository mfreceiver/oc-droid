package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SlimapiPermissionEntry
import cn.vectory.ocdroid.data.model.SlimapiQuestionEntry

/**
 * Cluster A: per-call cold-start / resync snapshot returned by
 * [OpenCodeRepository.coldStartSlimSync]. The four pieces are independent
 * (one failing does NOT poison the others).
 *
 * # T11 round-2 (oracle D2 — typed null/empty outcomes)
 *
 * Each metadata piece (`sessions` / `questions` / `permissions`) is
 * **nullable** to distinguish:
 *
 *  - `null` — fetch FAILED (transport / HTTP / decode). Caller MUST keep
 *    the prior list (cannot tell "server returned empty authoritative
 *    list" from "we couldn't reach the server").
 *  - `emptyList()` — fetch SUCCEEDED and returned no entries. Caller
 *    SHOULD replace the prior list with empty (the server authoritative
 *    view is "nothing here").
 *  - non-empty list — fetch succeeded; replace prior list.
 *
 * Pre-T11-round-2 all three pieces were non-nullable `List<...>` with
 * `emptyList()` collapsing both "failed" and "succeeded-empty" — making
 * it impossible for [SessionSyncCoordinator.applySlimColdStartSnapshot]
 * to know whether to replace the local cache with empty (clearing stale
 * rows) or preserve it (server unreachable). The nullable shape fixes
 * this and is required for production-live resync (oracle D2).
 *
 * `messages` keeps its existing nullable semantics: `null` = no
 * `openSessionId` was supplied (no fetch attempted); `emptyList()` =
 * fetched OK; etc. This is unchanged.
 */
data class SlimColdStartSnapshot(
    /** Null = fetch failed (keep prior). Empty = authoritative-empty (replace). */
    val sessions: List<Session>?,
    /** I-2: typed aggregation outcome replaces nullable list. */
    val questions: SlimAggregationOutcome<SlimapiQuestionEntry>,
    /** I-2: typed aggregation outcome replaces nullable list. */
    val permissions: SlimAggregationOutcome<SlimapiPermissionEntry>,
    /** Null iff no openSessionId was requested; empty list iff fetched OK. */
    val messages: List<MessageWithParts>?,
    /** rev-F: three-header discovery meta absent on pre-rev-F sidecars. */
    val complete: Boolean? = null,
    val discoveryDirectories: Int? = null,
    val discoveryReady: Boolean? = null,
)
