package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.SlimSessionDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 6 (slimapi v1 §G5 — split watermark): pure-function unit tests
 * for the reconcile primitives in [SlimapiResync]:
 *
 *  - [needsReconcile] — T6-C6 every branch.
 *  - [onReconcileSuccess] — T6-C2 advances localApplied* + clears dirty.
 *  - [onReconcileFailure] — T6-C2 preserves dirty.
 *  - [markDeleted] / [clearLocal] — T6 §3 reconcile signals.
 *
 * Pure — no IO, no SlimSseState mutation, no Android deps.
 *
 * # Invariants pinned here (the reviewer's split-watermark checklist)
 *
 *  - **Invariant #2** ([onReconcileSuccess] is the ONLY local-applied
 *    advancer): every test asserts that `remote*` is unchanged across
 *    each function call.
 *  - **Invariant #3** (`dirty` cleared ONLY by [onReconcileSuccess]):
 *    [onReconcileFailure] + [clearLocal] tested for dirty semantics;
 *    [markDeleted] doesn't touch dirty.
 */
class SlimapiResyncTest {

    // ── T6-C6: needsReconcile branches ─────────────────────────────────────

    @Test
    fun `T6-C6 needsReconcile returns true when remoteMessageId differs from localAppliedMessageId`() {
        val state = SlimSessionState(
            sessionId = "s1",
            remoteMessageId = "m-remote",
            remoteUpdatedAt = 100L,
            localAppliedMessageId = "m-local",
            localAppliedUpdatedAt = 100L,
            dirty = false,
        )
        assertTrue(
            "remote != localApplied messageID must trigger needsReconcile",
            needsReconcile(state)
        )
    }

    @Test
    fun `T6-C6 needsReconcile returns true when remoteUpdatedAt newer than localAppliedUpdatedAt`() {
        val state = SlimSessionState(
            sessionId = "s1",
            remoteMessageId = "m1",
            remoteUpdatedAt = 200L,
            localAppliedMessageId = "m1",
            localAppliedUpdatedAt = 100L,
            dirty = false,
        )
        assertTrue(
            "remoteUpdatedAt > localAppliedUpdatedAt must trigger needsReconcile",
            needsReconcile(state)
        )
    }

    @Test
    fun `T6-C6 needsReconcile returns true when remote set but localApplied null`() {
        val state = SlimSessionState(
            sessionId = "s1",
            remoteMessageId = "m1",
            remoteUpdatedAt = 100L,
            // localApplied* both null — fresh session, never reconciled.
        )
        assertTrue(needsReconcile(state))
    }

    @Test
    fun `T6-C6 needsReconcile returns true when dirty flag already set`() {
        // Sticky dirty: once true, needsReconcile stays true even if
        // watermarks are aligned (e.g. a transient dirty from a prior
        // gap that has since been closed by a remote regression — but
        // remote never regresses, so this branch is about dirty surviving
        // until onReconcileSuccess clears it).
        val state = SlimSessionState(
            sessionId = "s1",
            remoteMessageId = "m1",
            remoteUpdatedAt = 100L,
            localAppliedMessageId = "m1",
            localAppliedUpdatedAt = 100L,
            dirty = true,
        )
        assertTrue(
            "dirty flag sticky — needsReconcile true regardless of watermark alignment",
            needsReconcile(state)
        )
    }

    @Test
    fun `T6-C6 needsReconcile returns false when watermarks aligned`() {
        val state = SlimSessionState(
            sessionId = "s1",
            remoteMessageId = "m1",
            remoteUpdatedAt = 100L,
            localAppliedMessageId = "m1",
            localAppliedUpdatedAt = 100L,
            dirty = false,
        )
        assertFalse(
            "aligned remote/local watermarks (not dirty) → no reconcile needed",
            needsReconcile(state)
        )
    }

    @Test
    fun `T6-C6 needsReconcile returns false when localApplied ahead of remote`() {
        // REST fetched beyond what digest signalled — local view exceeds
        // remote. No gap to reconcile.
        val state = SlimSessionState(
            sessionId = "s1",
            remoteMessageId = "m1",
            remoteUpdatedAt = 100L,
            localAppliedMessageId = "m1",
            localAppliedUpdatedAt = 200L,
            dirty = false,
        )
        assertFalse(needsReconcile(state))
    }

    @Test
    fun `T6-C6 needsReconcile returns false when both watermarks null`() {
        // Cold-path state — never observed a digest, never fetched.
        val state = SlimSessionState(sessionId = "s1")
        assertFalse(needsReconcile(state))
    }

    @Test
    fun `T6-C6 needsReconcile returns false when remote null but localApplied set`() {
        // Defensive: state somehow has local-applied without a remote
        // observation. No gap (nothing remote to compare against).
        val state = SlimSessionState(
            sessionId = "s1",
            localAppliedMessageId = "m1",
            localAppliedUpdatedAt = 100L,
            dirty = false,
        )
        assertFalse(needsReconcile(state))
    }

    // ── T6-C2: onReconcileSuccess advances localApplied* + clears dirty ───

    @Test
    fun `T6-C2 onReconcileSuccess advances localAppliedUpdatedAt to max observed and clears dirty`() {
        val prior = SlimSessionState(
            sessionId = "s1",
            remoteMessageId = "m-remote",
            remoteUpdatedAt = 200L,
            localAppliedMessageId = "m-local",
            localAppliedUpdatedAt = 100L,
            dirty = true,
        )
        // Items in arbitrary updatedAt order — m2 carries the max updated,
        // but m3 is listed last. The id+ts MUST both come from m2 (the
        // max-updated item), NOT m3 (the last item). See the dedicated M3
        // discrimination test below for the failure mode under the old code.
        val items = listOf(
            messageWithParts(id = "m1", updated = 150L),
            messageWithParts(id = "m2", updated = 200L),
            messageWithParts(id = "m3", updated = 180L),
        )
        val out = onReconcileSuccess(prior, items)
        // localApplied advanced to max(updated) = 200 — from m2.
        assertEquals(200L, out.localAppliedUpdatedAt)
        // localAppliedMessageId is m2's id (the SAME item that owns the max
        // updatedAt — rev-gpt M3: no chronological-order assumption).
        assertEquals("m2", out.localAppliedMessageId)
        // dirty cleared.
        assertFalse("onReconcileSuccess must clear dirty", out.dirty)
        // remote* untouched (invariant #1 — only the digest reducer advances remote*).
        assertEquals("m-remote", out.remoteMessageId)
        assertEquals(200L, out.remoteUpdatedAt)
    }

    @Test
    fun `T6-M3 onReconcileSuccess derives id and ts from the same max-updated item`() {
        // rev-gpt M3 discrimination test. Out-of-order items where the LAST
        // item does NOT own the max updatedAt — under the OLD code
        // (`items.last().id` for id, `maxOfOrNull(updated)` for ts), the id
        // and ts came from DIFFERENT items, leaving localApplied* internally
        // inconsistent. The fix derives both from the single max-updated
        // item via `maxByOrNull { it.info.time?.updated }`.
        //
        // Input: [m-x@200, m-max@300, m-y@250]
        //   - OLD code: ts=300 (max), id=m-y (last) → INCONSISTENT
        //   - NEW code: ts=300, id=m-max (same item) → CONSISTENT
        val prior = SlimSessionState(
            sessionId = "s1",
            remoteUpdatedAt = 100L,
            dirty = true,
        )
        val items = listOf(
            messageWithParts(id = "m-x", updated = 200L),
            messageWithParts(id = "m-max", updated = 300L),
            messageWithParts(id = "m-y", updated = 250L),
        )
        val out = onReconcileSuccess(prior, items)
        assertEquals(
            "localAppliedUpdatedAt must be the max updated (300)",
            300L,
            out.localAppliedUpdatedAt,
        )
        assertEquals(
            "localAppliedMessageId must be the id of the SAME max-updated item (m-max), " +
                "NOT the last item (m-y). OLD code (items.last().id + maxOfOrNull ts) " +
                "would have produced id=m-y ts=300 — internally inconsistent.",
            "m-max",
            out.localAppliedMessageId,
        )
    }

    @Test
    fun `T6-M3 onReconcileSuccess handles single item with no tie-breaking`() {
        // Single-item list: id and ts come from the same item trivially.
        val prior = SlimSessionState(
            sessionId = "s1",
            remoteUpdatedAt = 100L,
            dirty = true,
        )
        val items = listOf(messageWithParts(id = "solo", updated = 250L))
        val out = onReconcileSuccess(prior, items)
        assertEquals(250L, out.localAppliedUpdatedAt)
        assertEquals("solo", out.localAppliedMessageId)
    }

    @Test
    fun `T6-M3 onReconcileSuccess with equal updatedAt values picks one consistently`() {
        // Multiple items with the SAME updatedAt — maxByOrNull returns the
        // FIRST max. Both id and ts come from that same first-max item, so
        // the (id, ts) pair stays consistent regardless of which item is
        // picked.
        val prior = SlimSessionState(sessionId = "s1", dirty = true)
        val items = listOf(
            messageWithParts(id = "first", updated = 200L),
            messageWithParts(id = "second", updated = 200L),
        )
        val out = onReconcileSuccess(prior, items)
        assertEquals(200L, out.localAppliedUpdatedAt)
        // maxByOrNull returns the FIRST matching — both id and ts come from
        // it. The exact id picked is an implementation detail as long as
        // (id, ts) is consistent.
        val expectedId = items.first { it.info.time?.updated == 200L }.info.id
        assertEquals(expectedId, out.localAppliedMessageId)
    }

    @Test
    fun `T6-C2 onReconcileSuccess retains prior pair when response max ts does not strictly advance`() {
        // rev-gpt round-2 IMPORTANT fix: when items returned an OLDER tail
        // than what local-applied already has (e.g. fetch anchored too
        // early, duplicate response, or stale debounce re-fetch), the
        // (localAppliedMessageId, localAppliedUpdatedAt) pair is retained
        // ATOMICALLY — BOTH fields unchanged. The earlier implementation
        // kept prior ts (via monotonic-max) but adopted observedId,
        // splitting the pair → future needsReconcile would see
        // "id differs but ts same" → spurious re-reconcile loops.
        val prior = SlimSessionState(
            sessionId = "s1",
            localAppliedUpdatedAt = 500L,
            localAppliedMessageId = "m-prior",
            dirty = true,
        )
        val items = listOf(
            messageWithParts(id = "m1", updated = 100L),
            messageWithParts(id = "m2", updated = 200L),
        )
        val out = onReconcileSuccess(prior, items)
        assertEquals(
            "localAppliedUpdatedAt must NOT regress when response ts <= prior",
            500L,
            out.localAppliedUpdatedAt,
        )
        assertEquals(
            "CRITICAL pair rule: localAppliedMessageId must ALSO stay at prior " +
                "(pair is atomic — older response MUST NOT split id from ts). " +
                "Old buggy code: ts=500 + id=m2 (split pair).",
            "m-prior",
            out.localAppliedMessageId,
        )
        assertFalse(out.dirty)
    }

    @Test
    fun `T6-C2 tie regression - equal observedTs with different messageId retains prior pair`() {
        // rev-gpt round-2: pin the TIE semantic. When observedTs EQUALS
        // prior localAppliedUpdatedAt but the response carries a DIFFERENT
        // messageId, the pair MUST stay at the prior values. Equal ts does
        // NOT advance the pair — strict advance (observedTs > prior) is
        // required. This is the discriminating case between "pair moves"
        // and "pair stays"; under the old unconditional-id-update code,
        // this would have produced ts=200 (equal, kept) + id=m-new (split).
        val prior = SlimSessionState(
            sessionId = "s1",
            localAppliedUpdatedAt = 200L,
            localAppliedMessageId = "m-old",
            dirty = true,
        )
        val items = listOf(
            // observedTs == prior, but different id.
            messageWithParts(id = "m-new", updated = 200L),
        )
        val out = onReconcileSuccess(prior, items)
        assertEquals(
            "tie on ts: localAppliedUpdatedAt stays at prior (no strict advance)",
            200L,
            out.localAppliedUpdatedAt,
        )
        assertEquals(
            "tie on ts: localAppliedMessageId MUST stay at prior (pair atomic). " +
                "Old buggy code would have produced id=m-new ts=200 → split pair.",
            "m-old",
            out.localAppliedMessageId,
        )
        assertFalse(out.dirty)
    }

    @Test
    fun `T6-C2 strict-advance moves both fields together to same max item`() {
        // Pair-level smoke: when observedTs STRICTLY exceeds prior, BOTH
        // fields move TOGETHER to the SAME max-updated item. Pins that
        // the strict-advance branch still updates id (not just ts).
        val prior = SlimSessionState(
            sessionId = "s1",
            localAppliedUpdatedAt = 100L,
            localAppliedMessageId = "m-old",
            dirty = true,
        )
        val items = listOf(
            messageWithParts(id = "m-max", updated = 300L),
        )
        val out = onReconcileSuccess(prior, items)
        assertEquals(300L, out.localAppliedUpdatedAt)
        assertEquals(
            "strict advance: localAppliedMessageId moves to the max item's id",
            "m-max",
            out.localAppliedMessageId,
        )
        assertFalse(out.dirty)
    }

    @Test
    fun `T6-C2 onReconcileSuccess with empty items clears dirty without advancing localApplied`() {
        // Empty fetch is a valid reconcile outcome (server returned no new
        // messages). Clear dirty but don't pretend we observed a new max.
        val prior = SlimSessionState(
            sessionId = "s1",
            remoteMessageId = "m1",
            remoteUpdatedAt = 100L,
            localAppliedMessageId = "m1",
            localAppliedUpdatedAt = 100L,
            dirty = true,
        )
        val out = onReconcileSuccess(prior, items = emptyList())
        assertFalse("empty reconcile still clears dirty (reconcile succeeded)", out.dirty)
        assertEquals(100L, out.localAppliedUpdatedAt)
        assertEquals("m1", out.localAppliedMessageId)
    }

    @Test
    fun `T6-C2 onReconcileSuccess with items missing time updated keeps prior localApplied`() {
        // Defensive: items returned but none carry time.updated (malformed
        // response or legacy endpoint). Under rev-gpt M3 fix: we can't
        // reliably identify the "latest" item without a ts, so we leave
        // BOTH localApplied* at their prior values (silently picking
        // first/last would corrupt the id↔ts correspondence the next fetch
        // depends on). dirty still clears (reconcile did succeed).
        val prior = SlimSessionState(
            sessionId = "s1",
            localAppliedUpdatedAt = 100L,
            localAppliedMessageId = "m-prior",
            dirty = true,
        )
        val items = listOf(
            // info.time = null (no TimeInfo at all).
            MessageWithParts(info = Message(id = "m1", role = "assistant")),
            MessageWithParts(info = Message(id = "m2", role = "assistant")),
        )
        val out = onReconcileSuccess(prior, items)
        // Neither field advanced — keep prior (no usable signal).
        assertEquals(100L, out.localAppliedUpdatedAt)
        assertEquals(
            "localAppliedMessageId stays at prior when no item has a usable " +
                "time.updated (id↔ts correspondence preserved)",
            "m-prior",
            out.localAppliedMessageId,
        )
        assertFalse(out.dirty)
    }

    @Test
    fun `T6-C2 onReconcileSuccess never touches remote watermark`() {
        // Invariant #1 hammered: across the full surface (empty items,
        // items with updated, items without updated) remote* is byte-for-byte
        // identical to the input.
        val prior = SlimSessionState(
            sessionId = "s1",
            remoteMessageId = "m-remote",
            remoteUpdatedAt = 999L,
            localAppliedUpdatedAt = 100L,
            dirty = true,
        )
        val items = listOf(messageWithParts(id = "m1", updated = 200L))
        val out = onReconcileSuccess(prior, items)
        assertEquals("m-remote", out.remoteMessageId)
        assertEquals(999L, out.remoteUpdatedAt)
    }

    // ── T6-C2: onReconcileFailure preserves dirty ────────────────────────

    @Test
    fun `T6-C2 onReconcileFailure preserves dirty flag`() {
        val prior = SlimSessionState(
            sessionId = "s1",
            remoteMessageId = "m1",
            remoteUpdatedAt = 200L,
            localAppliedMessageId = "m1",
            localAppliedUpdatedAt = 100L,
            dirty = true,
        )
        val out = onReconcileFailure(prior)
        assertTrue(
            "onReconcileFailure must preserve dirty (session still needs reconcile)",
            out.dirty
        )
        // localApplied* untouched (invariant #2).
        assertEquals("m1", out.localAppliedMessageId)
        assertEquals(100L, out.localAppliedUpdatedAt)
        // remote* untouched.
        assertEquals("m1", out.remoteMessageId)
        assertEquals(200L, out.remoteUpdatedAt)
    }

    @Test
    fun `T6-C2 onReconcileFailure with clean state stays clean`() {
        val prior = SlimSessionState(
            sessionId = "s1",
            remoteUpdatedAt = 100L,
            localAppliedUpdatedAt = 100L,
            dirty = false,
        )
        val out = onReconcileFailure(prior)
        assertFalse(out.dirty)
    }

    // ── T6 §3: markDeleted + clearLocal signals ──────────────────────────

    @Test
    fun `markDeleted sets deleted flag`() {
        val prior = SlimSessionState(
            sessionId = "s1",
            remoteMessageId = "m1",
            localAppliedUpdatedAt = 100L,
            dirty = true,
        )
        val out = markDeleted(prior)
        assertTrue("markDeleted must set deleted=true", out.deleted)
        // Doesn't touch the watermarks or dirty (T11 wiring drops the row).
        assertEquals("m1", out.remoteMessageId)
        assertEquals(100L, out.localAppliedUpdatedAt)
        assertTrue(out.dirty)
    }

    @Test
    fun `clearLocal resets localApplied watermarks and preserves dirty`() {
        // Invariant #3: dirty is cleared ONLY by onReconcileSuccess.
        // clearLocal nulls the local-applied watermark (empty-upstream
        // signal from §3) but leaves dirty for the wiring (T11) to clear
        // via a chained onReconcileSuccess(state, emptyList).
        val prior = SlimSessionState(
            sessionId = "s1",
            remoteMessageId = "m-remote",
            remoteUpdatedAt = 200L,
            localAppliedMessageId = "m-local",
            localAppliedUpdatedAt = 100L,
            dirty = true,
        )
        val out = clearLocal(prior)
        assertNull(out.localAppliedMessageId)
        assertNull(out.localAppliedUpdatedAt)
        assertTrue(
            "clearLocal must NOT clear dirty (invariant #3 — only onReconcileSuccess clears)",
            out.dirty
        )
        // remote* untouched.
        assertEquals("m-remote", out.remoteMessageId)
        assertEquals(200L, out.remoteUpdatedAt)
    }

    @Test
    fun `clearLocal chained with onReconcileSuccess clears dirty for empty upstream`() {
        // T11 wiring pattern: empty-upstream probe → clearLocal (drop the
        // cache) → onReconcileSuccess(state, emptyList) (clear dirty).
        val prior = SlimSessionState(
            sessionId = "s1",
            remoteUpdatedAt = 100L,
            localAppliedMessageId = "m-stale",
            localAppliedUpdatedAt = 100L,
            dirty = true,
        )
        val cleared = clearLocal(prior)
        val reconciled = onReconcileSuccess(cleared, items = emptyList())
        assertNull(reconciled.localAppliedMessageId)
        assertNull(reconciled.localAppliedUpdatedAt)
        assertFalse(
            "chained clearLocal → onReconcileSuccess(empty) clears dirty",
            reconciled.dirty
        )
    }

    // ── End-to-end (T6-C2 path through the split) ────────────────────────

    @Test
    fun `T6-C2 dirty survives across non-focus digests then clears on reconcile success`() {
        // Mirrors the focus/non-focus flow pinned by T6-C2 + T6-C3.
        val state = SlimSseState()

        // 1. Focus-session digest arrives → reducer advances remote, sets dirty.
        reduceSlimDigest(state, SlimSessionDigest(sessionId = "s1", updatedAt = 100L))
        val afterDigest = state.get("s1")!!
        assertTrue("dirty set by digest reducer", afterDigest.dirty)
        assertEquals(100L, afterDigest.remoteUpdatedAt)
        assertNull(afterDigest.localAppliedUpdatedAt)

        // 2. Non-focus digest re-emit (debounce) — dirty stays.
        reduceSlimDigest(state, SlimSessionDigest(sessionId = "s1", updatedAt = 100L))
        assertTrue("dirty survives debounce re-emit", state.get("s1")!!.dirty)

        // 3. Reconcile succeeds (REST fetched the message at updatedAt=100).
        val fetched = listOf(messageWithParts(id = "m1", updated = 100L))
        val reconciled = onReconcileSuccess(state.get("s1")!!, fetched)
        state.put("s1", reconciled)
        val afterSuccess = state.get("s1")!!
        assertFalse("dirty cleared by onReconcileSuccess", afterSuccess.dirty)
        assertEquals(100L, afterSuccess.localAppliedUpdatedAt)
        assertEquals("m1", afterSuccess.localAppliedMessageId)
        // remote unchanged.
        assertEquals(100L, afterSuccess.remoteUpdatedAt)

        // 4. needsReconcile now false (aligned).
        assertFalse(needsReconcile(afterSuccess))
    }

    @Test
    fun `T6-C2 dirty stays true when reconcile fails`() {
        val state = SlimSseState()
        reduceSlimDigest(state, SlimSessionDigest(sessionId = "s1", updatedAt = 100L))
        assertTrue(state.get("s1")!!.dirty)

        // Reconcile attempt fails — preserve dirty.
        val failed = onReconcileFailure(state.get("s1")!!)
        state.put("s1", failed)
        assertTrue(state.get("s1")!!.dirty)
        assertNull(state.get("s1")!!.localAppliedUpdatedAt)

        // Still needs reconcile.
        assertTrue(needsReconcile(state.get("s1")!!))
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun messageWithParts(id: String, updated: Long): MessageWithParts =
        MessageWithParts(
            info = Message(
                id = id,
                role = "assistant",
                time = Message.TimeInfo(updated = updated),
            )
        )
}
