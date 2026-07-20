package cn.vectory.ocdroid.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 7 (slimapi v1 §3 — probe / catch-up) + Task 1 (slimapi v0.2.2
 * tuple tie-break): pure-function unit tests for the reconcile
 * primitives co-located with [ProbeResult] in [SlimapiProbe.kt]:
 *
 *  - [needsCatchUp] — T7-C1 every branch, in the EXACT §3 order
 *    (post-T1 — branches 4+5 collapsed into one [compareWatermark] call):
 *    1. `probe.ok == false`              → `false`
 *    2. `probe.empty` / `messageID==null` → `false`
 *    3. `localAppliedId == null`          → `true`
 *    4. T1 tuple compare `(probe.updatedAt, probe.messageID)` >
 *       `(localAppliedTs, localAppliedId)` lexicographically → `true`
 *    5. else (aligned / stale)            → `false`
 *  - [catchUpSet] — T7-C2 union `focus ∪ localAll ∪ dirty`.
 *
 * Pure — no IO, no Android deps, no DI.
 *
 * ## Why these tests pin the §3 order
 *
 * The §3 doc *deliberately* returns `false` on probe failure so the
 * caller keeps the session in `dirty` for the next resync pass (instead
 * of falsely claiming "aligned"). The branch order also matters: the
 * `messageID == null` short-circuit must precede the `localAppliedId`
 * check, otherwise a defensive null-messageID probe against a
 * never-applied session would wrongly return `true`.
 *
 * ## T1 tuple tie-break inversion
 *
 * Pre-T1 branch 4 (`probe.messageID != localAppliedId`) was an OR-of-two
 * with branch 5 (`probe.updatedAt > localAppliedTs`): ANY id difference
 * → true. Under T1, the two collapse into a single tuple compare via
 * [compareWatermark]. The discriminating cases (equal ts ± id compare)
 * now INVERT: equal ts + smaller probe id → false (stale-digest safe
 * harbor), equal ts + larger probe id → true. See
 * `needsCatchUp_tupleEqualTsLargerId_returnsTrue` /
 * `needsCatchUp_tupleEqualTsSmallerId_returnsFalse`.
 */
class SlimapiProbeTest {

    // -----------------------------------------------------------------
    // needsCatchUp — T7-C1 branches
    // -----------------------------------------------------------------

    /** Branch 1: probe.ok == false → false (regardless of anything else). */
    @Test
    fun needsCatchUp_probeFailed_returnsFalse() {
        val probe = ProbeResult(ok = false, httpStatus = 500)
        assertFalse(needsCatchUp(probe, localAppliedId = null, localAppliedTs = null))
    }

    /** Branch 1 (network-fail flavour): ok=false, httpStatus=null. */
    @Test
    fun needsCatchUp_networkFailure_returnsFalse() {
        val probe = ProbeResult(ok = false, httpStatus = null)
        assertFalse(needsCatchUp(probe, localAppliedId = "any", localAppliedTs = 100L))
    }

    /** Branch 2a: probe.empty == true → false. */
    @Test
    fun needsCatchUp_probeEmpty_returnsFalse() {
        val probe = ProbeResult(ok = true, empty = true, messageID = null, updatedAt = null)
        assertFalse(needsCatchUp(probe, localAppliedId = null, localAppliedTs = null))
    }

    /**
     * Branch 2b: probe.ok and not flagged empty, but messageID==null
     * (defensive) → false. Pins the `messageID == null` short-circuit
     * ahead of the `localAppliedId` check.
     */
    @Test
    fun needsCatchUp_probeOkButMessageIdNull_returnsFalse() {
        val probe = ProbeResult(ok = true, empty = false, messageID = null, updatedAt = null)
        assertFalse(needsCatchUp(probe, localAppliedId = null, localAppliedTs = null))
    }

    /** Branch 3: localAppliedId == null (never applied) → true. */
    @Test
    fun needsCatchUp_neverApplied_returnsTrue() {
        val probe = ProbeResult(ok = true, empty = false, messageID = "m1", updatedAt = 10L)
        assertTrue(needsCatchUp(probe, localAppliedId = null, localAppliedTs = null))
    }

    /**
     * Branch 4 (T1 tuple — ts dominates): probe.updatedAt strictly newer
     * than localAppliedTs (any ids) → true. Pre-T1 this was the standalone
     * "updatedAt > localAppliedTs" branch; under T1 it's the ts-dominates
     * arm of the single [compareWatermark] call.
     */
    @Test
    fun needsCatchUp_serverNewerUpdatedAt_returnsTrue() {
        val probe = ProbeResult(ok = true, empty = false, messageID = "m1", updatedAt = 200L)
        assertTrue(needsCatchUp(probe, localAppliedId = "m1", localAppliedTs = 100L))
    }

    /**
     * Branch 4 (T1 tuple — ts dominates, no local ts): probe.updatedAt
     * set but localAppliedTs == null → tuple compare tsA != null, tsB ==
     * null → positive → true (§3 "localTs 不存在 → return true" preserved).
     */
    @Test
    fun needsCatchUp_serverHasUpdatedAtButNoLocalTs_returnsTrue() {
        val probe = ProbeResult(ok = true, empty = false, messageID = "m1", updatedAt = 200L)
        assertTrue(needsCatchUp(probe, localAppliedId = "m1", localAppliedTs = null))
    }

    /**
     * Branch 4 (T1 tuple — INVERTED tie-break, larger id on equal ts):
     * probe.updatedAt == localAppliedTs AND probe.messageID >
     * localAppliedId lexicographically → true. Pre-T1 branch 4
     * (`messageID != localAppliedId`) would also have returned true here
     * (id differs), but for a different reason. Under T1 the rule is the
     * single tuple compare; this test pins the inverted tie-break: equal
     * ts + larger id advances (probe is strictly newer in tuple order).
     */
    @Test
    fun needsCatchUp_tupleEqualTsLargerId_returnsTrue() {
        val probe = ProbeResult(ok = true, empty = false, messageID = "m2", updatedAt = 100L)
        assertTrue(
            "T1 tuple: equal ts + larger probe id → catch-up needed",
            needsCatchUp(probe, localAppliedId = "m1", localAppliedTs = 100L)
        )
    }

    /**
     * Branch 5 (T1 tuple — INVERTED tie-break, smaller id on equal ts):
     * probe.updatedAt == localAppliedTs AND probe.messageID <
     * localAppliedId lexicographically → FALSE (stale probe / out-of-
     * order re-emit; the monotonic id contract guarantees a stale probe
     * carries a strictly-smaller id). Pre-T1 branch 4 would have returned
     * TRUE here (id differs), causing spurious catch-up. T1 collapses to
     * the tuple compare which correctly returns false.
     */
    @Test
    fun needsCatchUp_tupleEqualTsSmallerId_returnsFalse() {
        val probe = ProbeResult(ok = true, empty = false, messageID = "m1", updatedAt = 100L)
        assertFalse(
            "T1 tuple: equal ts + smaller probe id → stale, no catch-up",
            needsCatchUp(probe, localAppliedId = "m2", localAppliedTs = 100L)
        )
    }

    /**
     * Branch 5 (T1 tuple — ts dominates negative): probe.updatedAt strictly
     * OLDER than localAppliedTs (any ids) → false (local is ahead; no
     * catch-up). Pre-T1 the standalone id-differs branch would have
     * returned TRUE here for any id difference — T1 fixes the spurious
     * catch-up. Pins the ts-dominates direction.
     */
    @Test
    fun needsCatchUp_serverOlderTs_returnsFalse_evenWithDivergentId() {
        val probe = ProbeResult(ok = true, empty = false, messageID = "m-server", updatedAt = 10L)
        assertFalse(
            "T1 tuple: probe ts < localAppliedTs → local ahead, no catch-up " +
                "(pre-T1 id-differs branch would have spuriously returned true)",
            needsCatchUp(probe, localAppliedId = "m-local", localAppliedTs = 100L)
        )
    }

    /** Branch 5 (else, aligned): same id, equal ts → false. */
    @Test
    fun needsCatchUp_alignedSameTs_returnsFalse() {
        val probe = ProbeResult(ok = true, empty = false, messageID = "m1", updatedAt = 100L)
        assertFalse(needsCatchUp(probe, localAppliedId = "m1", localAppliedTs = 100L))
    }

    /** Branch 5 (else, aligned): same id, server older → false (no catch-up). */
    @Test
    fun needsCatchUp_serverOlder_returnsFalse() {
        val probe = ProbeResult(ok = true, empty = false, messageID = "m1", updatedAt = 50L)
        assertFalse(needsCatchUp(probe, localAppliedId = "m1", localAppliedTs = 100L))
    }

    /**
     * Branch 5 (else, aligned): same id, probe.updatedAt == null
     * (server returned no updatedAt) → false. We can't prove the server
     * is newer, so we treat it as aligned.
     */
    @Test
    fun needsCatchUp_alignedServerUpdatedAtNull_returnsFalse() {
        val probe = ProbeResult(ok = true, empty = false, messageID = "m1", updatedAt = null)
        assertFalse(needsCatchUp(probe, localAppliedId = "m1", localAppliedTs = 100L))
    }

    // -----------------------------------------------------------------
    // catchUpSet — T7-C2 union
    // -----------------------------------------------------------------

    /** T7-C2 happy path: focus ∪ localAll ∪ dirty with overlaps deduped. */
    @Test
    fun catchUpSet_unionsAllThreeSourcesAndDedupes() {
        val result = catchUpSet(
            focus = "focus-1",
            localAll = listOf("focus-1", "local-1", "local-2"),
            dirty = listOf("local-2", "dirty-1"),
        )
        assertEquals(setOf("focus-1", "local-1", "local-2", "dirty-1"), result)
    }

    /** focus == null: just localAll ∪ dirty. */
    @Test
    fun catchUpSet_nullFocusOmitted() {
        val result = catchUpSet(
            focus = null,
            localAll = listOf("a", "b"),
            dirty = listOf("b", "c"),
        )
        assertEquals(setOf("a", "b", "c"), result)
    }

    /** All sources non-empty but disjoint: plain union. */
    @Test
    fun catchUpSet_disjointSources() {
        val result = catchUpSet(
            focus = "f",
            localAll = listOf("l"),
            dirty = listOf("d"),
        )
        assertEquals(setOf("f", "l", "d"), result)
    }

    /** All sources empty / null → empty set (resync is a no-op). */
    @Test
    fun catchUpSet_allEmpty_returnsEmpty() {
        val result = catchUpSet(focus = null, localAll = emptyList(), dirty = emptySet())
        assertTrue(result.isEmpty())
    }

    /** Inputs can be Sets or Lists; output is always a Set. */
    @Test
    fun catchUpSet_acceptsSetAndListInputs() {
        val result = catchUpSet(
            focus = "f",
            localAll = setOf("a"),
            dirty = listOf("a", "b"),
        )
        assertEquals(setOf("f", "a", "b"), result)
    }
}
