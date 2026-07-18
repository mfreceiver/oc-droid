package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.SlimSessionDigest
import cn.vectory.ocdroid.util.DebugLog

/**
 * Cluster A (slim SSE reducer): per-session view of the latest known state
 * derived from `session.digest` frames. Stored under
 * [OpenCodeRepository.slimSseState] so the reducer is pure (state in →
 * state out + decision) and unit-testable in isolation.
 *
 * Field semantics mirror [SlimSessionDigest] absent-field handling: a null
 * field means "no information from the last digest" (the sidecar only emits
 * changed fields, §3). The reducer merges present fields onto the prior
 * state; absent fields preserve the prior value.
 */
data class SlimSessionState(
    val sessionId: String,
    val directory: String? = null,
    val status: String? = null,
    val messageId: String? = null,
    /**
     * The largest `updatedAt` this client has observed for the session.
     * Anchors the next `/slimapi/messages/{sid}/since/{ts}` fetch (§5 A2=A).
     * Server returns `time.updated >= ts` so the boundary message is included
     * for messageID dedup.
     */
    val updatedAt: Long? = null,
    val archived: Long? = null,
    val deleted: Boolean = false,
)

/**
 * Cluster A: per-host accumulator of [SlimSessionState]. Held by
 * [OpenCodeRepository] as private state; reads/writes are @Synchronized so
 * the reducer (called from the SSE collector thread) and the message-fetch
 * caller (called from the lifecycle coordinator's Main thread) cannot race.
 *
 * Cleared by [OpenCodeRepository.configure] on a host switch — the per-host
 * bookmarks belong to the previous server and MUST NOT leak to the new one
 * (a stale updatedAt would skip real boundary messages on the new server).
 */
class SlimSseState {
    private val sessions = mutableMapOf<String, SlimSessionState>()

    @Synchronized
    fun get(sessionId: String): SlimSessionState? = sessions[sessionId]

    @Synchronized
    fun put(sessionId: String, state: SlimSessionState) {
        sessions[sessionId] = state
    }

    @Synchronized
    fun all(): Map<String, SlimSessionState> = sessions.toMap()

    @Synchronized
    fun clear() = sessions.clear()

    /**
     * Updates the bookmark for [sessionId] to the max of (prior, [updatedAt])
     * after a successful `/since/` fetch — guards against a fetch returning
     * an OLDER tail than what a later digest already advanced us to.
     */
    @Synchronized
    fun bumpUpdatedAt(sessionId: String, updatedAt: Long) {
        val prev = sessions[sessionId]
        val priorMax = prev?.updatedAt ?: Long.MIN_VALUE
        if (updatedAt > priorMax) {
            sessions[sessionId] = (prev ?: SlimSessionState(sessionId))
                .copy(updatedAt = updatedAt)
        }
    }
}

/**
 * Cluster A: fetch decision emitted by [reduceSlimDigest] when a digest
 * indicates newer message activity than the local bookmark. The caller
 * GETs `/slimapi/messages/{sessionId}/since/{since}` and feeds the result
 * back into [SlimSseState.bumpUpdatedAt] with the largest `time.updated`
 * observed in the returned skeletons.
 *
 * [since] is the LOCAL prior `updatedAt` (NOT the incoming digest's
 * updatedAt) — per §5, the server returns `time.updated >= ts` so the
 * boundary message is included and the caller can dedup by messageID.
 * `0L` is used when the client has no prior bookmark for the session
 * (cold path).
 */
data class SlimFetchMessages(
    val sessionId: String,
    val since: Long,
)

/**
 * Cluster A: pure digest reducer. Merges [digest] onto the prior
 * [SlimSessionState] (absent fields preserved) and decides whether to
 * emit a [SlimFetchMessages] (messageID / updatedAt strictly newer than
 * the local bookmark).
 *
 * **Decision logic** (§5 A2=A):
 *  - `digest.updatedAt != null` AND
 *    (prior.updatedAt == null OR digest.updatedAt > prior.updatedAt)
 *    → fetch with `since = prior.updatedAt ?: 0L`.
 *  - `digest.updatedAt == null` BUT `digest.messageId != null` AND
 *    `prior.messageId != digest.messageId` (server emitted a fresh id
 *    without a timestamp — defensive branch covering sidecars that omit
 *    `updatedAt` on pure-status digests) → fetch with the prior bookmark
 *    (or 0L if none).
 *  - Otherwise → null (no fetch).
 *
 * The reducer mutates [state] in place (it is the accumulator). Callers
 * that need a snapshot should call [SlimSseState.all] separately.
 *
 * Pure modulo the [state] mutation (synchronized). No IO, no coroutine
 * launches, no slice reads. Unit-testable as a black box.
 */
fun reduceSlimDigest(
    state: SlimSseState,
    digest: SlimSessionDigest,
): SlimFetchMessages? {
    val sessionId = digest.sessionId
    val prev = state.get(sessionId) ?: SlimSessionState(sessionId)
    val priorUpdatedAt = prev.updatedAt
    val priorMessageId = prev.messageId

    // Merge: absent fields preserve prior values (§3 debounce — only changed
    // fields are emitted). updatedAt is MONOTONIC: a stale digest arriving
    // out-of-order (e.g. SSE re-delivery, sidecar debounce re-emit) MUST NOT
    // regress the local bookmark — doing so would skip boundary messages on
    // the next `/slimapi/messages/{sid}/since/{ts}` fetch (§5 A2=A).
    // Other fields (directory / status / messageId / archived / deleted) are
    // last-write-wins — only updatedAt carries the watermark invariant.
    val merged = prev.copy(
        directory = digest.directory ?: prev.directory,
        status = digest.status ?: prev.status,
        messageId = digest.messageId ?: prev.messageId,
        updatedAt = mergeUpdatedAtMonotonic(prev.updatedAt, digest.updatedAt),
        archived = digest.archived ?: prev.archived,
        deleted = digest.deleted ?: prev.deleted,
    )
    state.put(sessionId, merged)

    // Fetch decision: updatedAt strictly newer than prior → fetch.
    val incomingUpdatedAt = digest.updatedAt
    val fetchSince: Long? = when {
        incomingUpdatedAt != null &&
            (priorUpdatedAt == null || incomingUpdatedAt > priorUpdatedAt) ->
            priorUpdatedAt ?: 0L
        // Defensive: messageId changed without an updatedAt ts.
        incomingUpdatedAt == null &&
            digest.messageId != null &&
            digest.messageId != priorMessageId ->
            priorUpdatedAt ?: 0L
        else -> null
    }

    return fetchSince?.let {
        DebugLog.d(
            "SlimSseReducer",
            "digest fetch decision sid=$sessionId since=$it " +
                "(prior=$priorUpdatedAt incoming=$incomingUpdatedAt)"
        )
        SlimFetchMessages(sessionId = sessionId, since = it)
    }
}

/**
 * Cluster A: monotonic merge for the [SlimSessionState.updatedAt] watermark.
 *
 *  - If [incoming] is null → preserve [prior] (no information; §3 debounce).
 *  - If [prior] is null → adopt [incoming] (first observation).
 *  - Otherwise → max(prior, incoming). A strictly-older incoming digest
 *    (SSE re-delivery, sidecar debounce re-emit, out-of-order frame) MUST
 *    NOT regress the bookmark, or the next `/slimapi/messages/{sid}/since/{ts}`
 *    fetch would skip boundary messages (§5 A2=A).
 *
 * Hoisted top-level so [SlimSseReducerTest] can pin the watermark invariant
 * without going through the full reducer.
 */
internal fun mergeUpdatedAtMonotonic(prior: Long?, incoming: Long?): Long? =
    when {
        incoming == null -> prior
        prior == null -> incoming
        else -> maxOf(prior, incoming)
    }
