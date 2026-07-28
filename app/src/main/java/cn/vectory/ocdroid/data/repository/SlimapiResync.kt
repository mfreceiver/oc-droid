@file:Suppress("unused")

package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.MessageWithParts

// ════════════════════════════════════════════════════════════════════════════
// lite-v2-dev (plan §4.1): SlimSessionState + SlimSseStateMachine + SlimSyncEngine
// have been RETIRED. The slim reconcile path now routes through
// SkeletonReloadCoordinator. The SlimSessionState-typed helpers that used to
// live here (`canAdvanceLocalAppliedTuple`, `needsReconcile`, `onReconcileSuccess`,
// `onReconcileFailure`, `markDeleted`, `clearLocal`) have been removed.
//
// The two pure functions below are preserved because they have live main-source
// callers (`compareWatermark` is used by SlimapiProbe / SlimMessageSetMerge;
// `maxMessageTuple` is a pure utility kept for symmetry + unit-test coverage).
// They take primitives / models, NOT SlimSessionState, so they survive the
// retirement unchanged.
// ════════════════════════════════════════════════════════════════════════════

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
 * Used at: [needsCatchUp] (`SlimapiProbe.kt`) and the slim message-set
 * merge (`SlimMessageSetMerge.kt`).
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
 * §11.3 (slim message reliability joint plan): compute the tuple-max
 * `(time.updated, info.id)` over [items], restricted to messages that
 * carry BOTH a usable `time.updated` (`> 0L`) AND a non-blank `info.id`.
 *
 * Returns the winning `(Long, String)` pair, or `null` if no eligible
 * message exists in [items]. The return type is declared
 * `Pair<Long?, String?>?` so it is directly assignable to nullable
 * watermark fields; in practice, whenever a non-null Pair is returned
 * BOTH components are non-null (the eligibility filter guarantees it).
 *
 *  - Only messages with `info.time.updated > 0L && info.id.isNotBlank()`
 *    participate in watermark selection.
 *  - When the collection has no eligible message, this returns `null`
 *    (i.e. "do not advance") — it NEVER returns `(null, id)` or
 *    `(0L, id)`. `updated <= 0L` is NOT a completeness proof.
 *  - This helper does NOT add a `msg_` prefix allowlist and does NOT
 *    assume any message id format — it compares ids purely
 *    lexicographically (delegating to [compareWatermark] in the callers).
 *  - The tuple is compared in lexicographic order (ts first; equal ts ⇒
 *    id tie-break), mirroring [compareWatermark]. Among equal-ts items
 *    the largest id wins (deterministic in id space).
 *
 * Pure — no IO, no state mutation, no Android dependency.
 */
internal fun maxMessageTuple(
    items: List<MessageWithParts>,
): Pair<Long?, String?>? {
    val item = items
        .asSequence()
        .filter {
            (it.info.time?.updated ?: 0L) > 0L &&
                it.info.id.isNotBlank()
        }
        .maxWithOrNull(
            compareBy<MessageWithParts>(
                { it.info.time!!.updated!! },
                { it.info.id },
            ),
        )
        ?: return null

    return item.info.time!!.updated!! to item.info.id
}
