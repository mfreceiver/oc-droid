package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.LastErrorField
import cn.vectory.ocdroid.data.model.SlimSessionDigest
import cn.vectory.ocdroid.data.model.SlimSessionLastError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cluster A (slim SSE reducer): pure-function unit tests for [reduceSlimDigest]
 * + [SlimSseState]. Drives the reducer with synthetic `session.digest` frames
 * and asserts:
 *
 *  1. **Field-absent semantics**: a digest that omits a field leaves the
 *     local value untouched (§3 debounce — only changed fields emitted).
 *  2. **Fetch decision (§5 A2=A, T6-C5 regression)**: a digest whose
 *     updatedAt is strictly newer than the local-applied+remote max
 *     triggers [SlimFetchMessages] anchored on the PRIOR max bookmark
 *     (so the server returns the boundary message for messageID dedup).
 *     A digest with non-newer updatedAt is a no-op.
 *  3. **Cold path**: first-ever digest for a session with updatedAt set
 *     triggers a fetch anchored on 0L.
 *  4. **Message-id-only path** (defensive): a digest that carries only a
 *     fresh messageId (no updatedAt) triggers a fetch with the prior
 *     bookmark (or 0L) — covers sidecars that omit `updatedAt` on pure
 *     status digests.
 *  5. **State evolution**: archived/deleted/status fields merge correctly
 *     across a sequence of digests.
 *
 *  6. **T6-C1 (split watermark)**: digest updatedAt advances
 *     `remoteUpdatedAt`; `localAppliedUpdatedAt` is UNCHANGED; `dirty=true`.
 *  7. **T6-C3 (non-focus path)**: digest advances `remote*` + sets
 *     `dirty=true`; a second digest on the same session (no reconcile
 *     in between) does NOT clear `dirty`.
 *  8. **T6-C4 (lastError three-state merge)**: Omitted preserves prior;
 *     Cleared nulls; Set replaces.
 *
 * Pure — no IO, no coroutines, no Android deps.
 */
class SlimSseReducerTest {

    private lateinit var state: SlimSseState

    @Before
    fun setUp() {
        state = SlimSseState()
    }

    // ── 1. Field-absent semantics ──────────────────────────────────────────

    @Test
    fun `digest with only status updates status and leaves other fields untouched`() {
        // Seed: full state.
        reduceSlimDigest(
            state,
            SlimSessionDigest(
                sessionId = "s1",
                directory = "/workdir",
                status = "idle",
                messageId = "m1",
                updatedAt = 100L,
            )
        )
        // New digest: only status changed.
        val decision = reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", status = "busy")
        )
        val merged = state.get("s1")!!
        assertEquals("busy", merged.status)
        // Untouched:
        assertEquals("/workdir", merged.directory)
        assertEquals("m1", merged.remoteMessageId)
        assertEquals(100L, merged.remoteUpdatedAt)
        // Same updatedAt → no fetch.
        assertNull(decision)
    }

    @Test
    fun `absent fields are NOT reset to null`() {
        reduceSlimDigest(
            state,
            SlimSessionDigest(
                sessionId = "s1",
                directory = "/a",
                status = "idle",
                messageId = "m1",
                updatedAt = 5L,
                archived = 99L,
                deleted = false,
            )
        )
        // A subsequent digest carrying NOTHING but sessionId is a no-op
        // (defensive: sidecar shouldn't emit such a frame, but be tolerant).
        reduceSlimDigest(state, SlimSessionDigest(sessionId = "s1"))
        val merged = state.get("s1")!!
        assertEquals("/a", merged.directory)
        assertEquals("idle", merged.status)
        assertEquals("m1", merged.remoteMessageId)
        assertEquals(5L, merged.remoteUpdatedAt)
        assertEquals(99L, merged.archived)
        assertFalse(merged.deleted)
    }

    // ── 2. Fetch decision — strictly newer updatedAt (T6-C5 regression) ────

    @Test
    fun `fetch fires when updatedAt strictly newer than prior bookmark`() {
        // §11.1 fix-9 P1-1: the reducer's fetch TRIGGER reads the digest's
        // incoming (updatedAt, messageId) directly — it does NOT depend on
        // the stored remote* tuple. So a partial digest (updatedAt only,
        // no messageId) still fires the trigger. The STORED remote* tuple,
        // however, is NOT advanced from a partial digest (P1-1 — no half
        // tuples); it stays (null, null) until a full-tuple digest arrives.
        reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", updatedAt = 100L)
        )
        val decision = reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", updatedAt = 200L)
        )
        assertNotNull("strictly newer updatedAt must trigger a fetch", decision)
        // Anchor is localAppliedUpdatedAt (rev-gpt Critical fix). No REST
        // reconcile has happened in this test → localAppliedUpdatedAt is null
        // → since=0L (cold path). Previously this asserted 100L (the digest-
        // observed remote), which could skip messages when no reconcile had
        // actually applied them.
        assertEquals(0L, decision!!.since)
        assertEquals("s1", decision.sessionId)
        // P1-1: stored remote* stays (null, null) — partial digest does
        // NOT seed a half tuple. The next FULL-tuple digest (200L, m-X)
        // would advance remote* to that tuple.
        assertNull(
            "P1-1: partial digest does not advance remoteUpdatedAt (no half tuple)",
            state.get("s1")!!.remoteUpdatedAt,
        )
        assertNull(
            "P1-1: partial digest does not advance remoteMessageId",
            state.get("s1")!!.remoteMessageId,
        )
    }

    @Test
    fun `fetch does NOT fire when updatedAt equals prior`() {
        // §11.1 fix-9 P1-1: use full-tuple digests so the stored remote*
        // tuple advances — partial digests no longer seed the stored
        // tuple (P1-1 strict), so the debounce against equal-partial
        // would not hold. The full-tuple version preserves the
        // regression intent: equal (ts, id) re-emit is idempotent.
        reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", updatedAt = 100L, messageId = "m1")
        )
        val decision = reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", updatedAt = 100L, messageId = "m1")
        )
        assertNull("equal (ts, id) is a no-op (debounced re-emit)", decision)
    }

    @Test
    fun `fetch does NOT fire when updatedAt older than prior`() {
        // §11.1 fix-9 P1-1: stored remote* tuple stays (null, null) for
        // partial digests, but the trigger logic uses the digest's
        // incoming updatedAt directly (not the stored tuple). Two
        // consecutive partial digests at 200 then 100: the trigger
        // fires for the first (incoming.updatedAt=200 vs prior=null,
        // null → tuple compare says > 0) but does NOT fire for the
        // second (incoming.updatedAt=100 vs priorRemote=null,
        // priorLocal=null → still >0... hmm, this is a behavior change).
        //
        // Actually under P1-1: each partial digest against (null,null)
        // prior fires the trigger (compareWatermark(incoming, null,
        // null, null) returns 1 because incoming has ts). So both fire.
        // The test name "fetch does NOT fire when updatedAt older than
        // prior" no longer holds under P1-1 for the cold-start path
        // (where prior is null). Skip — the regression coverage for
        // "stale digest against an ESTABLISHED watermark does not
        // regress" is in `T6-C1 digest advances remoteUpdatedAt` and
        // the tuple-max tests below (which use legal tuples).
        reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", updatedAt = 100L, messageId = "m1"),
        )
        val decision = reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", updatedAt = 50L, messageId = "m0"),
        )
        assertNull("stale (older) full-tuple digest is a no-op", decision)
        // Remote bookmark is NOT regressed (monotonic).
        assertEquals(100L, state.get("s1")!!.remoteUpdatedAt)
        assertEquals("m1", state.get("s1")!!.remoteMessageId)
    }

    // ── 2b. T1 tuple trigger — equal-ts tie path direct coverage ──────────
    //   The fetch TRIGGER was scalar `digest.updatedAt > max(priorRemote,
    //   priorLocal)` pre-T1; T1 collapses it to a single compareWatermark
    //   tuple compare. The equal-ts + larger-id advance case is exercised
    //   directly at SlimapiResyncTest (onReconcileSuccess / needsReconcile
    //   pair-advance), but the reducer's OWN fetch-trigger predicate was
    //   only indirectly covered via the shared pure helper. This direct
    //   test locks the correctness-critical equal-ts tie path at the
    //   reducer site so a future "revert trigger to scalar >" cannot
    //   silently re-disable the inverted tie-break at the reducer layer.

    @Test
    fun `T1 reduceSlimDigest fires when updatedAt equal but messageId larger`() {
        // Seed: prior remote AND local-applied BOTH anchored at (100, "m1")
        // — fully aligned (no spurious reconcile, no fetch on the seed).
        state.put(
            "s1",
            SlimSessionState(
                sessionId = "s1",
                remoteUpdatedAt = 100L,
                remoteMessageId = "m1",
                localAppliedUpdatedAt = 100L,
                localAppliedMessageId = "m1",
                dirty = false,
            )
        )
        // Incoming digest: SAME updatedAt (100) BUT a strictly larger id
        // ("m2"). Under T1 tuple semantics compareWatermark((100, "m2"),
        // (100, "m1")) > 0 ⇒ trigger fires on BOTH the remote-prior and
        // local-prior compares. Pre-T1 (scalar updatedAt > max(...)) this
        // would have been a no-op (100 > 100 is false) → debounce-drop a
        // genuine within-ms newer message.
        val decision = reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", updatedAt = 100L, messageId = "m2")
        )
        assertNotNull(
            "equal ts + larger id MUST fire the fetch trigger under T1 tuple semantics",
            decision,
        )
        // Anchor is localAppliedUpdatedAt (rev-gpt Critical fix) — 100 here.
        assertEquals(
            "anchor must be localAppliedUpdatedAt=100 (NOT max(remote, local))",
            100L,
            decision!!.since,
        )
        // remote* advanced in state via §11.1 fix-8 P1-5 lexicographic
        // tuple-max: incoming (100, "m2") strictly exceeds prior (100, "m1")
        // (equal ts + larger id) → adopt. remoteUpdatedAt stays 100; the
        // WHOLE tuple is replaced (no half-tuple manufacturing).
        val merged = state.get("s1")!!
        assertEquals(100L, merged.remoteUpdatedAt)
        assertEquals("m2", merged.remoteMessageId)
    }

    // ── §11.1 fix-8 P1-5: remote tuple-max contract ──────────────────────

    @Test
    fun `§11_1 P1-5 mergeRemoteTuple refuses to form half tuple from partial digest against legal prior`() {
        // Prior legal (100, "m1"). Incoming (null, "m2") — partial digest.
        // P1-5: keep prior; do NOT produce (100, "m2") (half tuple from
        // last-write-wins on id).
        val (ts, id) = mergeRemoteTuple(
            priorTs = 100L, priorId = "m1",
            incomingTs = null, incomingId = "m2",
        )
        assertEquals(100L, ts)
        assertEquals("m1", id)
    }

    @Test
    fun `§11_1 P1-5 mergeRemoteTuple refuses to form half tuple from ts-only digest against legal prior`() {
        // Prior legal (100, "m1"). Incoming (200, null) — ts-only digest.
        // P1-5: keep prior; do NOT produce (200, "m1") (half tuple from
        // monotonic-max on ts).
        val (ts, id) = mergeRemoteTuple(
            priorTs = 100L, priorId = "m1",
            incomingTs = 200L, incomingId = null,
        )
        assertEquals(100L, ts)
        assertEquals("m1", id)
    }

    @Test
    fun `§11_1 P1-5 mergeRemoteTuple adopts legal incoming against null prior`() {
        // Prior (null, null). Incoming (200, "m2"). Adopt.
        val (ts, id) = mergeRemoteTuple(
            priorTs = null, priorId = null,
            incomingTs = 200L, incomingId = "m2",
        )
        assertEquals(200L, ts)
        assertEquals("m2", id)
    }

    @Test
    fun `§11_1 P1_5 mergeRemoteTuple tuple-max rejects incoming with older ts`() {
        // Prior legal (200, "m1"). Incoming (100, "m2") — older ts.
        // Tuple-max: keep prior (200 > 100).
        val (ts, id) = mergeRemoteTuple(
            priorTs = 200L, priorId = "m1",
            incomingTs = 100L, incomingId = "m2",
        )
        assertEquals(200L, ts)
        assertEquals("m1", id)
    }

    @Test
    fun `§11_1 P1-5 mergeRemoteTuple tuple-max rejects incoming with equal ts smaller id`() {
        // Prior legal (100, "m2"). Incoming (100, "m1") — equal ts, smaller id.
        val (ts, id) = mergeRemoteTuple(
            priorTs = 100L, priorId = "m2",
            incomingTs = 100L, incomingId = "m1",
        )
        assertEquals(100L, ts)
        assertEquals("m2", id)
    }

    @Test
    fun `§11_1 P1-1 mergeRemoteTuple refuses to seed half tuple from partial digest against null prior`() {
        // §11.1 fix-9 P1-1: prior (null, null) + incoming partial
        // (100, null) → return (null, null). The prior fix-8 implementation
        // did field-level monotonic-max here, producing the half tuple
        // (100, null). P1-1 removes that — the reducer's stored tuple is
        // STRICTLY (null, null) or (ts, id). The trigger logic reads the
        // digest's incoming fields directly, so the fetch still fires for
        // a partial digest (see `firesOnTupleTrigger` /
        // `firesOnMessageIdOnly` in [reduceSlimDigest]).
        val (ts1, id1) = mergeRemoteTuple(
            priorTs = null, priorId = null,
            incomingTs = 100L, incomingId = null,
        )
        assertNull("P1-1: ts must be null (no half tuple)", ts1)
        assertNull("P1-1: id must be null (no half tuple)", id1)

        // Symmetric: (null, m1) incoming against (null, null) prior.
        val (ts2, id2) = mergeRemoteTuple(
            priorTs = null, priorId = null,
            incomingTs = null, incomingId = "m1",
        )
        assertNull("P1-1: ts must be null (no half tuple)", ts2)
        assertNull("P1-1: id must be null (no half tuple)", id2)

        // Subsequent full-tuple digest completes the pair via the
        // incoming-legal branch.
        val (ts3, id3) = mergeRemoteTuple(
            priorTs = null, priorId = null,
            incomingTs = 200L, incomingId = "m2",
        )
        assertEquals(200L, ts3)
        assertEquals("m2", id3)
    }

    @Test
    fun `T1 reduceSlimDigest does NOT fire when updatedAt equal and messageId smaller or equal`() {
        // Symmetric discrimination: equal ts + smaller-or-equal id MUST NOT
        // fire (stale-digest safe harbor / idempotent re-emit). Pinned
        // alongside the larger-id fires case so the tie direction cannot
        // silently invert.
        state.put(
            "s1",
            SlimSessionState(
                sessionId = "s1",
                remoteUpdatedAt = 100L,
                remoteMessageId = "m2",
                localAppliedUpdatedAt = 100L,
                localAppliedMessageId = "m2",
                dirty = false,
            )
        )
        // (a) Smaller id at equal ts — stale re-emit.
        val smaller = reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", updatedAt = 100L, messageId = "m1")
        )
        assertNull(
            "equal ts + smaller id MUST NOT fire (stale-digest safe harbor)",
            smaller,
        )
        // (b) Equal id at equal ts — idempotent re-emit / debounce.
        val equal = reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", updatedAt = 100L, messageId = "m2")
        )
        assertNull(
            "equal ts + equal id MUST NOT fire (idempotent re-emit)",
            equal,
        )
    }

    @Test
    fun `T1 reduceSlimDigest does NOT fire when updatedAt older even if messageId larger`() {
        // ts dominates: an older ts with a larger id is still a stale
        // digest (tsA < tsB ⇒ compareWatermark returns negative regardless
        // of ids). Pinned so the tuple order (ts-first, id-tiebreak) cannot
        // be confused with id-first ordering.
        state.put(
            "s1",
            SlimSessionState(
                sessionId = "s1",
                remoteUpdatedAt = 200L,
                remoteMessageId = "m1",
                localAppliedUpdatedAt = 200L,
                localAppliedMessageId = "m1",
                dirty = false,
            )
        )
        val decision = reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", updatedAt = 100L, messageId = "m9")
        )
        assertNull(
            "older ts MUST NOT fire regardless of id (ts dominates tuple order)",
            decision,
        )
    }

    // ── 3. Cold path ───────────────────────────────────────────────────────

    @Test
    fun `first-ever digest with updatedAt triggers fetch anchored on zero`() {
        // §11.1 fix-9 P1-1: the fetch TRIGGER fires on a partial digest
        // (updatedAt only) because it reads the digest's incoming fields
        // directly. The STORED remote* tuple, however, stays (null, null)
        // — partial digests do not seed a half tuple. The next full-tuple
        // digest advances remote*.
        val decision = reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "cold", updatedAt = 42L)
        )
        assertNotNull(decision)
        assertEquals(0L, decision!!.since)
        assertEquals("cold", decision.sessionId)
        assertNull(
            "P1-1: partial digest does not advance remoteUpdatedAt",
            state.get("cold")!!.remoteUpdatedAt,
        )
        assertNull(
            "P1-1: partial digest does not advance remoteMessageId",
            state.get("cold")!!.remoteMessageId,
        )
    }

    @Test
    fun `first-ever digest without updatedAt does NOT fetch`() {
        val decision = reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "cold", status = "idle")
        )
        assertNull(decision)
        // Still seeds state.
        assertNotNull(state.get("cold"))
        assertEquals("idle", state.get("cold")!!.status)
    }

    // ── 4. Message-id-only path (defensive) ────────────────────────────────

    @Test
    fun `messageId change without updatedAt triggers fetch anchored on localApplied`() {
        reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", messageId = "m1", updatedAt = 100L)
        )
        val decision = reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", messageId = "m2")
        )
        assertNotNull("fresh messageId alone must trigger fetch", decision)
        // Anchor is localAppliedUpdatedAt (rev-gpt Critical fix) — null here
        // because no REST reconcile has happened → 0L. Asserting 100L (the
        // remote-observed) would re-introduce the Critical bug.
        assertEquals(0L, decision!!.since)
        // §11.1 fix-8 P1-5: the messageID-only digest does NOT advance the
        // remote tuple. The reducer only accepts STRICTLY GREATER LEGAL
        // tuples; a partial (null, m2) incoming against the legal prior
        // (100, "m1") keeps the prior pair — never manufactures a half
        // tuple. The fetch trigger fires (m2 != priorRemoteMessageId);
        // on REST reconcile success, localApplied* advances to m2 via
        // onReconcileSuccess, which suppresses further re-fires.
        val merged = state.get("s1")!!
        assertEquals(
            "P1-5: partial digest does not regress remoteUpdatedAt",
            100L,
            merged.remoteUpdatedAt,
        )
        assertEquals(
            "P1-5: partial digest does not advance remoteMessageId (no half tuple)",
            "m1",
            merged.remoteMessageId,
        )
    }

    @Test
    fun `same messageId without updatedAt does NOT fetch`() {
        reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", messageId = "m1", updatedAt = 100L)
        )
        val decision = reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", messageId = "m1")
        )
        assertNull(decision)
    }

    // ── 4b. T6-C5 Critical — fetch ANCHOR is localApplied only, NOT max ────
    //   The TRIGGER (whether to emit) is still debounce-on-remote, but the
    //   `since` value MUST be `priorLocalAppliedUpdatedAt ?: 0L`. Using
    //   `max(remote, local)` was the Critical bug: a prior failed reconcile
    //   (localApplied < remote, dirty=true) + a fresh digest advancing remote
    //   would emit `since=remote` and skip the (localApplied, remote] message
    //   range that was never fetched/applied.

    @Test
    fun `T6-C5 CRITICAL fetch anchor is localAppliedUpdatedAt when localApplied is set`() {
        // localApplied has caught up to remote — both 100. New digest at 200
        // triggers a fetch anchored on localApplied=100 (the actually-applied
        // boundary). Server returns time.updated >= 100 for messageID dedup.
        state.put(
            "s1",
            SlimSessionState(
                sessionId = "s1",
                remoteUpdatedAt = 100L,
                remoteMessageId = "m1",
                localAppliedUpdatedAt = 100L,
                localAppliedMessageId = "m1",
                dirty = false,
            )
        )
        val decision = reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", updatedAt = 200L, messageId = "m2")
        )
        assertNotNull(decision)
        assertEquals(
            "anchor must be localAppliedUpdatedAt when set (100), not max(remote, local)=100",
            100L,
            decision!!.since,
        )
    }

    @Test
    fun `T6-C5 CRITICAL fetch anchor is localApplied NOT max when reconcile failed`() {
        // 🔴 Reviewer's counter-example (rev-gpt Critical):
        //   localApplied = 100 (last successfully-applied fetch)
        //   remote       = 200 (a digest at 200 arrived + a fetch was
        //                       attempted but FAILED → dirty=true,
        //                       localApplied stayed at 100)
        //   new digest   = 300 (fresh remote advance)
        //
        // Correct behavior: trigger fires (300 > max(200, 100) = 200) AND
        // since = 100 (localApplied-only anchor). The fetch must cover the
        // (100, 300] range so the messages in (100, 200] that the failed
        // reconcile never fetched are recovered.
        //
        // Old buggy behavior (max anchor): since = max(200, 100) = 200 →
        // SKIPS the (100, 200] range. This test FAILS under the old code
        // (assertion: since == 100; old code produced 200).
        state.put(
            "s1",
            SlimSessionState(
                sessionId = "s1",
                remoteUpdatedAt = 200L,
                remoteMessageId = "m-remote-200",
                localAppliedUpdatedAt = 100L,
                localAppliedMessageId = "m-local-100",
                dirty = true,  // reconcile for the 200-digest failed
            )
        )
        val decision = reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", updatedAt = 300L, messageId = "m-300")
        )
        assertNotNull("trigger must still fire (300 > max(200, 100) = 200)", decision)
        assertEquals(
            "CRITICAL: since must be localApplied=100, NOT max(remote=200, local=100)=200; " +
                "old max-anchor code would emit 200 and skip the (100, 200] range",
            100L,
            decision!!.since,
        )
        // remote advanced to 300; localApplied UNCHANGED at 100.
        assertEquals(300L, state.get("s1")!!.remoteUpdatedAt)
        assertEquals(100L, state.get("s1")!!.localAppliedUpdatedAt)
        assertTrue("dirty stays true (no onReconcileSuccess in the reducer)", state.get("s1")!!.dirty)
    }

    @Test
    fun `T6-C5 fetch anchor is 0 when localApplied null but remote set`() {
        // Cold-path-with-prior-digest: observed a digest at 100 but no REST
        // reconcile has happened. localApplied is null → since=0L (fetch
        // everything; the remote-observed 100 is NOT proof we've applied
        // anything). This is the conservative correct behavior — under the
        // OLD max-anchor code this would have emitted since=100, skipping
        // the [0, 100) range that the digest alone never fetched.
        state.put(
            "s1",
            SlimSessionState(
                sessionId = "s1",
                remoteUpdatedAt = 100L,
                // localAppliedUpdatedAt null
            )
        )
        val decision = reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", updatedAt = 200L)
        )
        assertNotNull(decision)
        assertEquals(
            "localApplied null → since=0L (remote-observed ts is NOT a fetch boundary)",
            0L,
            decision!!.since,
        )
    }

    // ── 5. State evolution: archived / deleted / status ────────────────────

    @Test
    fun `archived flag flows into state when emitted`() {
        reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", archived = 1234567890L)
        )
        val merged = state.get("s1")!!
        assertEquals(1234567890L, merged.archived)
    }

    @Test
    fun `T6-M1 archived is monotonic-max — older digest does NOT regress`() {
        // Permanent archive timestamp (info.time.archived, §3). A session,
        // once archived, doesn't un-archive; a stale / out-of-order digest
        // MUST NOT regress the marker. mergeArchivedMonotonic is the same
        // shape as mergeUpdatedAtMonotonic.
        state.put(
            "s1",
            SlimSessionState(sessionId = "s1", archived = 1_000_000L)
        )
        reduceSlimDigest(
            state,
            // Older archived value — sidecar debounce re-emit or out-of-order.
            SlimSessionDigest(sessionId = "s1", archived = 500_000L)
        )
        assertEquals(
            "archived must NOT regress to older digest value (monotonic-max contract)",
            1_000_000L,
            state.get("s1")!!.archived,
        )
        // Forward still works.
        reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", archived = 2_000_000L)
        )
        assertEquals(2_000_000L, state.get("s1")!!.archived)
    }

    @Test
    fun `mergeArchivedMonotonic preserves null when both null`() {
        assertNull(mergeArchivedMonotonic(null, null))
    }

    @Test
    fun `mergeArchivedMonotonic takes max on conflicting`() {
        assertEquals(100L, mergeArchivedMonotonic(100L, 50L))
        assertEquals(100L, mergeArchivedMonotonic(50L, 100L))
        assertEquals(100L, mergeArchivedMonotonic(100L, 100L))
        assertEquals(42L, mergeArchivedMonotonic(null, 42L))
        assertEquals(42L, mergeArchivedMonotonic(42L, null))
    }

    @Test
    fun `deleted flag flows into state when emitted`() {
        reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", deleted = true)
        )
        assertTrue(state.get("s1")!!.deleted)
    }

    @Test
    fun `multiple sessions accumulate independently`() {
        // §11.1 fix-9 P1-1: use full-tuple digests so remote* advances
        // (partial digests no longer seed half tuples — see
        // `first-ever digest with updatedAt triggers fetch anchored on zero`).
        reduceSlimDigest(state, SlimSessionDigest(sessionId = "a", updatedAt = 10L, messageId = "a1"))
        reduceSlimDigest(state, SlimSessionDigest(sessionId = "b", updatedAt = 20L, messageId = "b1"))
        reduceSlimDigest(state, SlimSessionDigest(sessionId = "a", updatedAt = 30L, messageId = "a2"))

        assertEquals(30L, state.get("a")!!.remoteUpdatedAt)
        assertEquals("a2", state.get("a")!!.remoteMessageId)
        assertEquals(20L, state.get("b")!!.remoteUpdatedAt)
        assertEquals("b1", state.get("b")!!.remoteMessageId)
        assertEquals(2, state.all().size)
    }

    @Test
    fun `bumpUpdatedAt only advances remoteUpdatedAt forward`() {
        // T6 split: bumpUpdatedAt now advances the REMOTE watermark (the
        // local-applied bump is onReconcileSuccess's job, in SlimapiResync).
        state.put("s1", SlimSessionState(sessionId = "s1", remoteUpdatedAt = 100L))
        state.bumpUpdatedAt("s1", 50L)  // older
        assertEquals(100L, state.get("s1")!!.remoteUpdatedAt)
        state.bumpUpdatedAt("s1", 150L)  // newer
        assertEquals(150L, state.get("s1")!!.remoteUpdatedAt)
    }

    @Test
    fun `bumpUpdatedAt on unknown session seeds entry with remoteUpdatedAt`() {
        state.bumpUpdatedAt("fresh", 99L)
        assertEquals(99L, state.get("fresh")!!.remoteUpdatedAt)
        // localApplied is NOT touched by bumpUpdatedAt (invariant #2).
        assertNull(state.get("fresh")!!.localAppliedUpdatedAt)
        assertNull(state.get("fresh")!!.localAppliedMessageId)
    }

    @Test
    fun `bumpUpdatedAt never touches localApplied watermark`() {
        // T6 invariant #2: localApplied* is advanced ONLY by onReconcileSuccess.
        // bumpUpdatedAt (the remote-path bump) must not corrupt it.
        state.put(
            "s1",
            SlimSessionState(
                sessionId = "s1",
                remoteUpdatedAt = 100L,
                localAppliedUpdatedAt = 80L,
                localAppliedMessageId = "m-local",
            )
        )
        state.bumpUpdatedAt("s1", 200L)
        val out = state.get("s1")!!
        assertEquals(200L, out.remoteUpdatedAt)
        assertEquals(80L, out.localAppliedUpdatedAt)
        assertEquals("m-local", out.localAppliedMessageId)
    }

    @Test
    fun `clear resets all bookmarks`() {
        reduceSlimDigest(state, SlimSessionDigest(sessionId = "a", updatedAt = 10L))
        reduceSlimDigest(state, SlimSessionDigest(sessionId = "b", updatedAt = 20L))
        state.clear()
        assertTrue(state.all().isEmpty())
        assertNull(state.get("a"))
    }

    // ── 6. T6-C1 — split watermark: digest advances remote*, leaves
    //         localApplied* alone, sets dirty ────────────────────────────────

    @Test
    fun `T6-C1 digest advances remoteUpdatedAt and leaves localAppliedUpdatedAt unchanged`() {
        // Seed: session with both watermarks set, aligned (dirty=false).
        state.put(
            "s1",
            SlimSessionState(
                sessionId = "s1",
                remoteUpdatedAt = 100L,
                remoteMessageId = "m1",
                localAppliedUpdatedAt = 100L,
                localAppliedMessageId = "m1",
                dirty = false,
            )
        )
        // New digest signals newer activity.
        reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", updatedAt = 200L, messageId = "m2")
        )
        val merged = state.get("s1")!!
        // remote advanced.
        assertEquals(200L, merged.remoteUpdatedAt)
        assertEquals("m2", merged.remoteMessageId)
        // localApplied UNCHANGED (invariant #2).
        assertEquals(100L, merged.localAppliedUpdatedAt)
        assertEquals("m1", merged.localAppliedMessageId)
        // dirty=true (needsReconcile: remote moved past local).
        assertTrue("dirty must be set after remote advances past local", merged.dirty)
    }

    @Test
    fun `T6-C1 aligned digest does not set dirty`() {
        // localApplied already matches remote → no gap → no dirty.
        state.put(
            "s1",
            SlimSessionState(
                sessionId = "s1",
                remoteUpdatedAt = 100L,
                remoteMessageId = "m1",
                localAppliedUpdatedAt = 100L,
                localAppliedMessageId = "m1",
                dirty = false,
            )
        )
        // Re-emit the same updatedAt (debounce — no advance).
        reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", updatedAt = 100L, messageId = "m1")
        )
        val merged = state.get("s1")!!
        assertFalse("aligned re-emit must not set dirty", merged.dirty)
    }

    // ── 7. T6-C3 — non-focus digest advances remote*, sets dirty, NEVER
    //         clears dirty (no reconcile on non-focus) ───────────────────────

    @Test
    fun `T6-C3 non-focus digest sets dirty and does not clear it on subsequent digests`() {
        // §11.1 fix-9 P1-1: use full-tuple digests so remote* advances.
        // (Partial digests no longer seed remoteUpdatedAt alone — see P1-1.)
        // First digest: sets dirty (remote > localApplied null).
        reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", updatedAt = 100L, messageId = "m1")
        )
        assertTrue("first digest sets dirty", state.get("s1")!!.dirty)

        // Second digest on same session (no onReconcileSuccess in between —
        // non-focus path). Per §3 the wiring does NOT clear dirty here;
        // the reducer also never clears dirty.
        reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", updatedAt = 100L, messageId = "m1")  // debounce re-emit
        )
        assertTrue(
            "non-focus path: dirty stays true after debounce re-emit",
            state.get("s1")!!.dirty
        )

        // Third digest advancing remote further — dirty still true.
        reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", updatedAt = 150L, messageId = "m2")
        )
        val merged = state.get("s1")!!
        assertEquals(150L, merged.remoteUpdatedAt)
        assertEquals("m2", merged.remoteMessageId)
        assertTrue(
            "non-focus path: dirty still true after further remote advance",
            merged.dirty
        )
    }

    // ── 8. T6-C4 — lastError three-state merge (Omitted / Cleared / Set) ────

    @Test
    fun `T6-C4 lastError Omitted preserves prior banner`() {
        // Seed an active banner.
        val initial = SlimSessionLastError(name = "UpstreamTimeout", message = "old")
        state.put(
            "s1",
            SlimSessionState(sessionId = "s1", lastError = initial)
        )
        // Digest with lastError absent (default LastErrorField.Omitted per T1).
        reduceSlimDigest(state, SlimSessionDigest(sessionId = "s1", status = "busy"))
        val merged = state.get("s1")!!
        assertEquals(
            "Omitted field must preserve prior banner",
            initial,
            merged.lastError
        )
    }

    @Test
    fun `T6-C4 lastError Cleared nulls prior banner`() {
        val initial = SlimSessionLastError(name = "UpstreamTimeout", message = "old")
        state.put(
            "s1",
            SlimSessionState(sessionId = "s1", lastError = initial)
        )
        // Force the three-state field to Cleared via the explicit sealed type.
        reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1").copy(lastError = LastErrorField.Cleared)
        )
        val merged = state.get("s1")!!
        assertNull(
            "Cleared field must null the prior banner (sidecar signals recovery)",
            merged.lastError
        )
    }

    @Test
    fun `T6-C4 lastError Set replaces prior banner`() {
        val initial = SlimSessionLastError(name = "UpstreamTimeout", message = "old")
        state.put(
            "s1",
            SlimSessionState(sessionId = "s1", lastError = initial)
        )
        val fresh = SlimSessionLastError(name = "UpstreamHttp5xx", message = "new")
        reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1").copy(lastError = LastErrorField.Set(fresh))
        )
        val merged = state.get("s1")!!
        assertEquals(
            "Set field must replace the prior banner",
            fresh,
            merged.lastError
        )
    }

    @Test
    fun `T6-C4 lastError Cleared distinct from Omitted`() {
        // The whole point of the three-state encoding: ensure collapsing
        // Cleared into Omitted would break this test (the prior banner
        // would survive when it shouldn't).
        val initial = SlimSessionLastError(name = "UpstreamTimeout")
        state.put(
            "s1",
            SlimSessionState(sessionId = "s1", lastError = initial)
        )
        // Omitted: preserves.
        reduceSlimDigest(state, SlimSessionDigest(sessionId = "s1", status = "x"))
        assertEquals(initial, state.get("s1")!!.lastError)
        // Cleared: nulls.
        reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1").copy(lastError = LastErrorField.Cleared)
        )
        assertNull(state.get("s1")!!.lastError)
    }

    // ── Monotonic helpers (regression for the original watermark invariant) ──

    @Test
    fun `mergeUpdatedAtMonotonic preserves null when both null`() {
        assertNull(mergeUpdatedAtMonotonic(null, null))
    }

    @Test
    fun `mergeUpdatedAtMonotonic adopts incoming when prior null`() {
        assertEquals(42L, mergeUpdatedAtMonotonic(null, 42L))
    }

    @Test
    fun `mergeUpdatedAtMonotonic preserves prior when incoming null`() {
        assertEquals(42L, mergeUpdatedAtMonotonic(42L, null))
    }

    @Test
    fun `mergeUpdatedAtMonotonic takes max on conflicting`() {
        assertEquals(100L, mergeUpdatedAtMonotonic(100L, 50L))
        assertEquals(100L, mergeUpdatedAtMonotonic(50L, 100L))
        assertEquals(100L, mergeUpdatedAtMonotonic(100L, 100L))
    }

    @Test
    fun `mergeLastError Omitted preserves prior`() {
        val prior = SlimSessionLastError(name = "X")
        assertEquals(prior, mergeLastError(prior, LastErrorField.Omitted))
        assertEquals(null, mergeLastError(null, LastErrorField.Omitted))
    }

    @Test
    fun `mergeLastError Cleared nulls prior`() {
        val prior = SlimSessionLastError(name = "X")
        assertNull(mergeLastError(prior, LastErrorField.Cleared))
        assertNull(mergeLastError(null, LastErrorField.Cleared))
    }

    @Test
    fun `mergeLastError Set takes the new value`() {
        val prior = SlimSessionLastError(name = "X")
        val fresh = SlimSessionLastError(name = "Y")
        assertEquals(fresh, mergeLastError(prior, LastErrorField.Set(fresh)))
        assertEquals(fresh, mergeLastError(null, LastErrorField.Set(fresh)))
    }
}
