package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.MessageWithParts

/**
 * Task 1 (slimapi v0.2.2 §G5 tie-break): lexicographic compare of two
 * watermark pairs `(ts, id)` — the single shared helper for the slim-mode
 * SSE reconcile watermark decisions.
 *
 *  - ts compares first; null ts = oldest (the session has never observed
 *    a server-signalled timestamp for this watermark).
 *  - when ts is equal on both sides AND non-null, id compares
 *    lexicographically; null id = oldest (defensive: a watermark pair
 *    where ts is set but id never got populated is older than any pair
 *    carrying the same ts + a concrete id).
 *
 * # Why the id tie-break is safe — messageID monotonicity
 *
 * The id tie-break correctness DEPENDS on opencode `messageID` being
 * lexicographically strictly monotonic by creation. Confirmed from
 * `packages/opencode/src/id/id.ts`: ascending, format
 * `msg_<12 hex (timestamp*4096 + counter)><14 random base62>` — the
 * 12-hex prefix is strictly increasing across creations (the counter
 * auto-increments within the same millisecond), so the full id is
 * lexicographically monotonic by creation **including within the same
 * millisecond**. This is why we can tie-break `(ts, id)` without any
 * monotonicity-agnostic fallback — YAGNI.
 *
 * Returns:
 *  - `<0` if A is older than B,
 *  - `0`  if A and B are equal,
 *  - `>0` if A is newer than B.
 *
 * Used at 4 sites (T1-C2/C3/C4): [onReconcileSuccess], [needsReconcile]
 * (this file), [needsCatchUp] (`SlimapiProbe.kt`), and the
 * `reduceSlimDigest` fetch trigger (`SlimSseReducer.kt`).
 *
 * Pure — no IO, no Android deps.
 */
internal fun compareWatermark(tsA: Long?, idA: String?, tsB: Long?, idB: String?): Int {
    if (tsA == null && tsB == null) return 0
    if (tsA == null) return -1
    if (tsB == null) return 1
    if (tsA != tsB) return tsA.compareTo(tsB)
    if (idA == null && idB == null) return 0
    if (idA == null) return -1
    if (idB == null) return 1
    return idA.compareTo(idB)
}

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
 * Branches:
 *
 *  - [state.dirty] already `true` → `true`. Sticky: once a session is
 *    marked dirty, it stays dirty until [onReconcileSuccess] clears it.
 *    This is what makes a non-focus digest's `dirty=true` survive until
 *    the next focus / resync pass actually reconciles.
 *  - Otherwise the decision reduces to a single tuple compare (T1,
 *    slimapi v0.2.2 §G5): `true` iff
 *    `compareWatermark(remoteUpdatedAt, remoteMessageId,
 *                      localAppliedUpdatedAt, localAppliedMessageId) > 0`
 *    — i.e. the server-signalled `(remoteUpdatedAt, remoteMessageId)`
 *    tuple is STRICTLY greater than the applied
 *    `(localAppliedUpdatedAt, localAppliedMessageId)` tuple in
 *    lexicographic order (ts first; equal ts ⇒ id tie-break).
 *  - `false` otherwise — the applied tuple already matches or exceeds
 *    the remote observation (e.g. equal ts + smaller remote id from an
 *    out-of-order / stale digest → no spurious reconcile).
 *
 * Pre-T1 this was an OR-of-two scalar predicate (`remoteId != localId
 * || remoteTs > localTs`); T1 collapses it into the single
 * [compareWatermark] call above. DO NOT "restore" the OR-of-two — it
 * would re-emit `true` for equal-ts + smaller-id stale digests and
 * reintroduce spurious reconcile loops. The id tie-break is safe under
 * opencode messageID lexicographic strict-monotonicity — see
 * [compareWatermark] kdoc.
 *
 * Pure. No IO, no coroutine, no Android dependency.
 */
fun needsReconcile(state: SlimSessionState): Boolean {
    // Sticky dirty — once true, stays true until onReconcileSuccess.
    if (state.dirty) return true

    // T1 tuple compare: (remoteTs, remoteId) STRICTLY greater than
    // (localTs, localId) in lexicographic tuple order → server is ahead of
    // what we've applied. Pre-T1 this was an OR-of-two (`remoteId !=
    // localId || remoteTs > localTs`); T1 collapses it into one tuple
    // compare via [compareWatermark] — see its kdoc for why the id tie-
    // break is safe (opencode messageID is lexicographically strictly
    // monotonic by creation). This also means a stale-digest whose
    // (remoteTs, remoteId) tuple is NOT strictly greater (e.g. equal ts +
    // smaller id from an out-of-order re-emit) correctly returns false —
    // no spurious reconcile.
    if (compareWatermark(
            state.remoteUpdatedAt, state.remoteMessageId,
            state.localAppliedUpdatedAt, state.localAppliedMessageId,
        ) > 0
    ) {
        return true
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
 * one with the tuple-max `(updatedAt, id)` we've successfully
 * reconciled). The pair advances TOGETHER iff
 * `compareWatermark(observedTs, observedId, priorTs, priorId) > 0`
 * (T1, slimapi v0.2.2 §G5) — i.e. the observed tuple is STRICTLY
 * greater than the prior tuple in lexicographic order (ts first; equal
 * ts ⇒ id tie-break). This subsumes the pre-T1 "strict ts advance"
 * rule AND adds the equal-ts + larger-id advance case. When the
 * observed tuple is `<= prior` (older/equal tail — e.g. fetch anchored
 * too early, duplicate response, stale debounce re-fetch, or equal ts +
 * smaller-or-equal id), BOTH fields retain their prior values.
 * Splitting the pair (e.g. keeping prior ts but adopting the response's
 * id) would make future [needsReconcile] checks see a regressed id and
 * trigger spurious re-reconcile loops.
 *
 * The equal-ts + larger-id advance is safe under the opencode messageID
 * monotonic invariant: ids are lexicographically strictly monotonic by
 * creation (see [compareWatermark] kdoc), so a strictly-larger id at
 * the same ts necessarily identifies a strictly-later creation and is
 * the genuine tuple-max observation.
 *
 * Defensive: items with no usable `time.updated` (> 0L) — e.g. legacy
 * endpoints or malformed skeletons — also leave BOTH fields at their
 * prior values (we can't reliably identify the "latest" item without a
 * ts). Items whose `info.id` is blank are likewise excluded from
 * watermark selection (see valid-id anchor below). `dirty` clears
 * either way (reconcile did succeed).
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
    // T1 (slimapi v0.2.2): selection is now `maxWithOrNull(compareBy(
    // updated, id))` — when MULTIPLE items share the max updatedAt, the
    // LARGEST id wins (lexicographic tie-break, mirroring [compareWatermark]).
    // Pre-T1 used `maxByOrNull { updated }` (returns FIRST max-by-ts);
    // T1 makes the selection deterministic in id space so the chosen
    // (ts, id) pair is the tuple-max observed — which is what the new
    // tuple strict-advance predicate below measures against. Safe under
    // the opencode messageID-strictly-monotonic-by-creation invariant
    // (see [compareWatermark] kdoc).
    //
    // # Valid-id watermark anchor (rev-opus M-4 / §5.4)
    //
    // The stored watermark pair MUST always correspond to a real message
    // that carried a non-blank id. Candidates are therefore filtered to
    // items with BOTH a usable `time.updated` (> 0L) AND a non-blank
    // `info.id` before the tuple-max selection. Without this guard a
    // malformed high-ts item whose id is blank (or was coalesced via
    // `?: ""` for compareBy) could satisfy the ts-first advance check
    // while the id branch fell back to prior (`observedId ?: prior`),
    // producing the split pair `(newTs, priorId)` — a tuple that never
    // matched any real message and would corrupt subsequent
    // [compareWatermark] "unseen" decisions. Blank-id high-ts items are
    // therefore ignored for watermark purposes (they remain eligible for
    // the caller's merge/cache path); the next poll re-fetches them
    // idempotently, and a later valid-id item with a greater tuple
    // advances the watermark normally. Under normal opencode /
    // slimapi data `info.id` is always non-blank, so this filter is a
    // no-op on the healthy path.
    //
    // Defensive: items with no usable `time.updated` (> 0L) — e.g. legacy
    // endpoints or malformed skeletons — leave BOTH localApplied* fields
    // at their prior values (we can't reliably identify the "latest" item
    // without a ts, and silently picking first/last would corrupt the id
    // ↔ ts correspondence the next fetch depends on). dirty still clears
    // (reconcile did succeed — we just got no usable signal).
    val latest = items
        .filter { (it.info.time?.updated ?: 0L) > 0L && it.info.id.isNotBlank() }
        .maxWithOrNull(compareBy({ it.info.time!!.updated!! }, { it.info.id }))
    val observedTs: Long? = latest?.info?.time?.updated
    val observedId: String? = latest?.info?.id

    // T1 tuple strict-advance predicate (replaces the pre-T1 scalar
    // `observedTs > prior` rule): the pair advances iff the observed
    // (ts, id) is STRICTLY GREATER than the prior (ts, id) in
    // lexicographic tuple order — [compareWatermark] > 0. This means:
    //   - observedTs > priorTs (any ids) → advance (ts dominates).
    //   - observedTs == priorTs AND observedId > priorId → advance
    //     (the inverted tie-break — equal ts + larger id now moves the
    //     pair, undoing the pre-T1 "equal ts never advances" rule).
    //   - observedTs == priorTs AND observedId == priorId → no advance
    //     (idempotent — duplicate / debounce re-emit).
    //   - observedTs == priorTs AND observedId < priorId → no advance
    //     (stale-digest safe harbor — out-of-order re-emit carries a
    //     strictly-smaller id under the monotonic-id invariant).
    //   - observedTs < priorTs → no advance (older tail).
    //
    // Pair integrity is enforced by construction:
    //  1. Selection only considers items with non-blank ids (valid-id
    //     anchor above), so `observedId` is never blank when `latest`
    //     is non-null.
    //  2. Both fields advance TOGETHER from that same `latest` item iff
    //     the observed tuple > prior tuple; otherwise BOTH stay at prior.
    // Splitting the pair (e.g. new ts + prior id, or prior ts + new id)
    // is therefore impossible — the atomic-pair rule plus the valid-id
    // filter close both the pre-T6 "adopt observedId unconditionally"
    // hole and the blank-id high-ts hole.
    //
    // rev-gpt round-2 IMPORTANT fix (preserved): the older pre-T6 code
    // kept prior ts (via monotonic-max) but adopted observedId
    // unconditionally, splitting the pair → future needsReconcile saw
    // "id differs but ts same" → spurious re-reconcile loops. The atomic
    // pair rule (now expressed via the tuple compare) closes that hole.
    val priorLocalAppliedUpdatedAt = state.localAppliedUpdatedAt
    val priorLocalAppliedMessageId = state.localAppliedMessageId
    val advances = observedTs != null &&
        observedId != null &&
        compareWatermark(
            observedTs, observedId,
            priorLocalAppliedUpdatedAt, priorLocalAppliedMessageId,
        ) > 0
    val newLocalAppliedUpdatedAt = if (advances) {
        observedTs!!
    } else {
        priorLocalAppliedUpdatedAt
    }
    val newLocalAppliedMessageId = if (advances) {
        // Valid-id filter guarantees a non-blank observedId when advances.
        observedId!!
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
