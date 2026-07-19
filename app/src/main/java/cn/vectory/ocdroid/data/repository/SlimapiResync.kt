package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.MessageWithParts

/**
 * Task 6 (slimapi v1 §G5 — split watermark): pure reconcile primitives
 * for [SlimSessionState]. These are the **local-applied path** of the
 * split watermark model — the counterpart to the digest reducer's remote
 * path ([reduceSlimDigest] in [SlimSseReducer]).
 *
 * # Boundary between this file and the rest of the slim reconcile stack
 *
 *  - This file owns the **state-derive layer**: pure functions that take
 *    a [SlimSessionState] (+ reconcile inputs) and return a new
 *    [SlimSessionState]. No retrofit, no coroutines, no Android deps —
 *    unit-testable as black boxes.
 *  - The slim REST endpoints (`OpenCodeRepository.getSlimapiMessagesSince`
 *    etc.) + the per-session reconcile loop (probe → fetch → merge →
 *    onReconcileSuccess) are T11's wiring job. T11 calls these pure
 *    functions; T11 does not re-derive the merge math.
 *  - Probe-level decisions (`needsCatchUp(probe, localApplied*)`,
 *    `catchUpSet(focus, localAll, dirty)`) are T7's job and live in
 *    `SlimapiProbe.kt` — they take a [ProbeResult], not a state object.
 *
 * # The five invariants (split watermark)
 *
 *  1. `remote*` is monotonic — only the digest reducer advances it;
 *     this file never touches [SlimSessionState.remoteUpdatedAt] /
 *     [SlimSessionState.remoteMessageId].
 *  2. `localApplied*` is ONLY advanced by [onReconcileSuccess] — never
 *     by the digest reducer, never by [onReconcileFailure] /
 *     [markDeleted] / [clearLocal].
 *  3. `dirty` ratchets to `true` via the digest reducer (when
 *     [needsReconcile] holds after a merge); cleared to `false` ONLY by
 *     [onReconcileSuccess]. [onReconcileFailure] preserves `dirty`.
 *  4. `lastError` three-state merge lives in the digest reducer
 *     ([mergeLastError]); the reconcile path passes it through untouched.
 *  5. The fetch decision (in [reduceSlimDigest]) preserves the
 *     pre-T6 single-watermark behavior via `max(remote, local)`.
 *
 * Each invariant is pinned by an explicit test in [SlimapiResyncTest]
 * (T6-C2 / T6-C6) or [SlimSseReducerTest] (T6-C1 / T6-C3 / T6-C4 / T6-C5).
 */

/**
 * Task 6 (slimapi v1 §3 / §G5): does this session need a REST reconcile
 * pass to bring its local-applied watermark up to the server-signalled
 * remote watermark?
 *
 * Branches (T6-C6 — every branch covered):
 *
 *  - [state.dirty] already `true` → `true`. Sticky: once a session is
 *    marked dirty, it stays dirty until [onReconcileSuccess] clears it.
 *    This is what makes a non-focus digest's `dirty=true` survive until
 *    the next focus / resync pass actually reconciles.
 *  - [SlimSessionState.remoteMessageId] is set AND differs from
 *    [SlimSessionState.localAppliedMessageId] → `true` (server has a
 *    newer / different latest message than what we've applied).
 *  - [SlimSessionState.remoteUpdatedAt] is set AND strictly greater than
 *    [SlimSessionState.localAppliedUpdatedAt] (or local is null) →
 *    `true` (server signalled newer activity than we've applied).
 *  - Otherwise → `false` (aligned — local view matches or exceeds the
 *    remote view).
 *
 * Pure. No IO, no coroutine, no Android dependency.
 */
fun needsReconcile(state: SlimSessionState): Boolean {
    // Sticky dirty — once true, stays true until onReconcileSuccess.
    if (state.dirty) return true

    // Server has a different latest messageID than what we've applied.
    val remoteId = state.remoteMessageId
    if (remoteId != null && remoteId != state.localAppliedMessageId) return true

    // Server signalled newer activity than we've applied locally.
    val remoteTs = state.remoteUpdatedAt
    if (remoteTs != null) {
        val localTs = state.localAppliedUpdatedAt
        if (localTs == null || remoteTs > localTs) return true
    }

    return false
}

/**
 * Task 6 (slimapi v1 §3 / §G5): produce the post-reconcile state after
 * a successful REST fetch + merge of [items] into the local message
 * cache.
 *
 * Advances the **local-applied** watermark ([SlimSessionState.localAppliedUpdatedAt]
 * + [SlimSessionState.localAppliedMessageId]) to the max observed in
 * [items] and clears [SlimSessionState.dirty] (the local view is now
 * caught up — invariant #3).
 *
 * The remote watermark is **left untouched** (invariant #1 — only the
 * digest reducer advances `remote*`). If the items observed a higher
 * `time.updated` than the prior remote observation, the next digest
 * will ratchet remote forward; the local-applied watermark is what
 * anchors the next `/since/{ts}` fetch either way.
 *
 *  - [items]: the freshly-fetched message skeletons (typically the
 *    return of `/slimapi/messages/{sid}/since/{ts}`). Empty list is
 *    valid (the fetch returned nothing new) — clears `dirty` without
 *    advancing local-applied (no new info to apply, but reconcile
 *    succeeded so the dirty flag is no longer needed).
 *
 * # Pair-level atomic update (rev-gpt round-2 IMPORTANT fix)
 *
 * Both [SlimSessionState.localAppliedUpdatedAt] AND
 * [SlimSessionState.localAppliedMessageId] are derived from the SAME
 * item — the one with the max `info.time.updated` — so the two fields
 * stay internally consistent regardless of items ordering (rev-gpt M3:
 * the sidecar's chronological-order assumption isn't contracted).
 *
 * The pair is ATOMIC: it always refers to a SINGLE applied item (the
 * one with the max `updatedAt` we've successfully reconciled). It moves
 * TOGETHER iff the observed max ts STRICTLY advances the prior
 * [SlimSessionState.localAppliedUpdatedAt] (observed > prior, or prior
 * is null). When the response's max ts is `<= prior` (older/equal tail —
 * e.g. fetch anchored too early, duplicate response, or stale debounce
 * re-fetch), BOTH fields retain their prior values. Splitting the pair
 * (e.g. keeping prior ts but adopting the response's id) would make
 * future [needsReconcile] checks see "id differs but ts same" and
 * trigger spurious re-reconcile loops.
 *
 * Defensive: items with no usable `time.updated` (> 0L) — e.g. legacy
 * endpoints or malformed skeletons — also leave BOTH fields at their
 * prior values (we can't reliably identify the "latest" item without a
 * ts). `dirty` clears either way (reconcile did succeed).
 *
 * Pure — no IO, no SlimSseState mutation, no Android dependency. The
 * caller (T11 wiring) is responsible for putting the returned state
 * back into the [SlimSseState] accumulator.
 */
fun onReconcileSuccess(
    state: SlimSessionState,
    items: List<MessageWithParts>,
): SlimSessionState {
    if (items.isEmpty()) {
        // Reconcile succeeded but produced no new info. Clear dirty
        // (invariant #3) without advancing local-applied (no new max
        // observed). remote* untouched.
        return state.copy(dirty = false)
    }
    // Derive BOTH the messageID and updatedAt from the SAME item — the
    // one with the max `info.time.updated`. The earlier implementation
    // used `maxOfOrNull(updated)` for ts + `items.last().id` for id,
    // which assumed strict chronological ordering from the sidecar.
    // Per rev-gpt M3: that assumption isn't contracted; pinning id + ts
    // to the same latest item makes localApplied* internally consistent
    // regardless of items order.
    //
    // Defensive: items with no usable `time.updated` (> 0L) — e.g. legacy
    // endpoints or malformed skeletons — leave BOTH localApplied* fields
    // at their prior values (we can't reliably identify the "latest" item
    // without a ts, and silently picking first/last would corrupt the id
    // ↔ ts correspondence the next fetch depends on). dirty still clears
    // (reconcile did succeed — we just got no usable signal).
    val latest = items
        .filter { (it.info.time?.updated ?: 0L) > 0L }
        .maxByOrNull { it.info.time!!.updated!! }
    val observedTs: Long? = latest?.info?.time?.updated
    val observedId: String? = latest?.info?.id

    // Pair-level update rule (rev-gpt round-2 IMPORTANT fix):
    // The pair (localAppliedMessageId, localAppliedUpdatedAt) is ATOMIC —
    // it always refers to a SINGLE applied item (the one with the max
    // updatedAt we've successfully reconciled). The pair moves TOGETHER
    // iff observedTs STRICTLY advances the prior localAppliedUpdatedAt
    // (observedTs > prior, or prior is null). When the response's max
    // ts is <= prior (older/equal tail — e.g. the fetch was anchored too
    // early and got stale items, or a duplicate of what we already have),
    // BOTH fields stay at the prior values. Splitting the pair (keeping
    // prior ts but adopting observedId) would make future needsReconcile
    // checks see "id differs but ts same" → spurious re-reconcile loops.
    val priorLocalAppliedUpdatedAt = state.localAppliedUpdatedAt
    val priorLocalAppliedMessageId = state.localAppliedMessageId
    val strictAdvance = observedTs != null &&
        (priorLocalAppliedUpdatedAt == null || observedTs > priorLocalAppliedUpdatedAt)
    val newLocalAppliedUpdatedAt = if (strictAdvance) {
        observedTs!!
    } else {
        priorLocalAppliedUpdatedAt
    }
    val newLocalAppliedMessageId = if (strictAdvance) {
        observedId ?: priorLocalAppliedMessageId
    } else {
        priorLocalAppliedMessageId
    }
    return state.copy(
        localAppliedUpdatedAt = newLocalAppliedUpdatedAt,
        localAppliedMessageId = newLocalAppliedMessageId,
        dirty = false,
    )
}

/**
 * Task 6 (slimapi v1 §3 / §G5): produce the post-reconcile state after
 * a FAILED REST reconcile attempt (transport error, 5xx, timeout, etc.).
 *
 * Preserves [SlimSessionState.dirty] (the session still needs reconcile —
 * we just couldn't do it this pass) and does NOT advance the local-applied
 * watermark (invariant #2 — only [onReconcileSuccess] does that). The
 * caller is expected to retry on the next focus / resync pass.
 *
 * Pure — no IO.
 */
fun onReconcileFailure(state: SlimSessionState): SlimSessionState = state

/**
 * Task 6 (slimapi v1 §3 / §G2-§G5): mark the session as deleted upstream
 * (typically the reconcile probe returned HTTP 404 `session_not_found`,
 * or the digest frame carried `deleted: true`). Sets
 * [SlimSessionState.deleted] to `true` so the wiring layer (T11) removes
 * the row from the session list.
 *
 * Does NOT clear `dirty` or local-applied — those are irrelevant once
 * the session is marked deleted (T11 drops the entry from the
 * [SlimSseState] accumulator entirely on the wiring pass).
 *
 * Pure — no IO.
 */
fun markDeleted(state: SlimSessionState): SlimSessionState =
    state.copy(deleted = true)

/**
 * Task 6 (slimapi v1 §3): signal that the local message cache for this
 * session should be cleared (typically the reconcile probe returned an
 * EMPTY array — the session exists upstream but has no messages; if the
 * local cache has messages for it, they're stale and should be dropped).
 *
 * Resets the **local-applied** watermark to null (we have nothing
 * applied locally anymore) but leaves `remote*` untouched (the probe
 * confirmed the session exists; the remote observation is still valid).
 *
 * Does **NOT** clear [SlimSessionState.dirty] — per invariant #3, dirty
 * is cleared ONLY by [onReconcileSuccess]. The T11 wiring chains the
 * two: `onReconcileSuccess(clearLocal(state), emptyList)` to clear dirty
 * after the local cache is dropped (mirrors plan line 279
 * "empty+本地有→clearLocalMessages+清 dirty" — the dirty clear is a
 * separate step from the cache clear).
 *
 * Pure — no IO. The wiring layer (T11) interprets the null
 * local-applied watermark + this signal to drop the local message
 * cache; the [SlimSessionState] itself survives so subsequent digests
 * have a state to merge onto.
 */
fun clearLocal(state: SlimSessionState): SlimSessionState = state.copy(
    localAppliedMessageId = null,
    localAppliedUpdatedAt = null,
)
