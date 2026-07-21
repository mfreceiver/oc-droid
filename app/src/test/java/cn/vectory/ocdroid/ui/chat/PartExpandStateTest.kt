package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.repository.ExpandOutcome
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §slimapi-client-impl-v1 §G6 (Task 15) — unit tests for the new L4
 * expand state slice ([PartExpandState] + [PartKey] + [ExpandPartsUseCase]).
 *
 * # Coverage
 *
 *  - **T15-C1** — sealed-hierarchy transitions on [PartExpandState]
 *    (Idle / Loading / Loaded / Failed(code)) + [PartKey] equality by
 *    `(messageId, partId)`.
 *  - **T15-C2** — [ExpandPartsUseCase] behaviour:
 *    * filters to `hasFull==true && omitted!=null && messageId!=null`,
 *    * Ok outcome → merge + per-part Loaded/Failed,
 *    * residual rule (T3 design note) — ids dropped by repo-internal
 *      413-halve / 20-truncate (NEITHER in items NOR failedIds) surface
 *      as Failed so the UI does not hang in Loading,
 *    * Failed outcome → all targets Failed(code),
 *    * SessionMissing outcome → all targets Failed(null) + no merge.
 *
 * MockK + `runTest` matches the SlimStatusFanOutTest pattern; the repo
 * is the only collaborator (T3 `expandMessagesFullBatch` consume-only —
 * the merge T8 pure-function is invoked in-process so no mock for it).
 */
class PartExpandStateTest {

    // ── part / message factories ─────────────────────────────────────────

    /** Skeleton part eligible for G6 expand (`hasFull && omitted`). */
    private fun skeleton(partId: String, msgId: String, text: String? = "skeleton") =
        Part(
            id = partId,
            messageId = msgId,
            type = "text",
            text = text,
            hasFull = true,
            omitted = listOf("tool"),
        )

    private fun msg(id: String, parts: List<Part>) =
        MessageWithParts(Message(id = id, role = "assistant"), parts)

    // ── T15-C1: state type + transitions ─────────────────────────────────

    @Test
    fun `C1 - four PartExpandState variants are pairwise distinct`() {
        // Exhaustive distinguishability — the UI's `when` matches each
        // branch separately, so collapsing any two would break the render.
        val idle: PartExpandState = PartExpandState.Idle
        val loading: PartExpandState = PartExpandState.Loading
        val loaded: PartExpandState = PartExpandState.Loaded
        val failed: PartExpandState = PartExpandState.Failed(code = "x")
        val set = setOf(idle, loading, loaded, failed)
        assertEquals("expected 4 distinct variants", 4, set.size)
    }

    @Test
    fun `C1 - Loaded and Idle and Loading are singletons (data object equality)`() {
        assertEquals(PartExpandState.Idle, PartExpandState.Idle)
        assertEquals(PartExpandState.Loading, PartExpandState.Loading)
        assertEquals(PartExpandState.Loaded, PartExpandState.Loaded)
    }

    @Test
    fun `C1 - Failed carries optional machine-readable code`() {
        val withCode = PartExpandState.Failed(code = "response_too_large")
        val transport = PartExpandState.Failed(code = null)
        assertEquals("response_too_large", withCode.code)
        assertNull(transport.code)
        assertNotEquals(withCode, transport)
        // Same code → equal (data-class equality).
        assertEquals(withCode, PartExpandState.Failed(code = "response_too_large"))
    }

    @Test
    fun `C1 - PartKey equality is by (messageId, partId)`() {
        val k1 = PartKey(messageId = "m1", partId = "p1")
        val k2 = PartKey(messageId = "m1", partId = "p1")
        val diffPart = PartKey(messageId = "m1", partId = "p2")
        val diffMsg = PartKey(messageId = "m2", partId = "p1")
        assertEquals(k1, k2)
        assertEquals(k1.hashCode(), k2.hashCode())
        assertNotEquals(k1, diffPart)
        assertNotEquals(k1, diffMsg)
    }

    @Test
    fun `C1 - transition sequence Idle - Loading - Loaded holds no carryover state`() {
        // Mirrors how T16 (chat wiring) drives the slice per tap:
        //   Idle (initial) → Loading (set on tap, before invoking usecase)
        //   → Loaded | Failed (set from the usecase's outcome map).
        var state: PartExpandState = PartExpandState.Idle
        state = PartExpandState.Loading
        state = PartExpandState.Loaded
        assertEquals(PartExpandState.Loaded, state)
    }

    @Test
    fun `C1 - transition sequence Idle - Loading - Failed(code) preserves code`() {
        var state: PartExpandState = PartExpandState.Idle
        state = PartExpandState.Loading
        state = PartExpandState.Failed(code = "upstream_unavailable")
        assertEquals(PartExpandState.Failed(code = "upstream_unavailable"), state)
    }

    // ── T15-C2: usecase filter + flow ────────────────────────────────────

    @Test
    fun `C2 - empty parts list does not invoke repo, returns Empty`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        val uc = ExpandPartsUseCase(repo)

        val result = uc.expandParts(sessionId = "s1", local = emptyList(), parts = emptyList()).getOrThrow()

        assertEquals(ExpandPartsOutcome.Empty, result)
        assertNull(result.mergedLocal)
        assertTrue(result.states.isEmpty())
        coVerify(exactly = 0) { repo.expandMessagesFullBatch(any(), any()) }
    }

    @Test
    fun `C2 - parts without hasFull=true or with null omitted are filtered out`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        val uc = ExpandPartsUseCase(repo)

        val parts = listOf(
            // omitted=null → not eligible.
            Part(id = "p1", messageId = "m1", type = "text", hasFull = true, omitted = null),
            // hasFull=null → not eligible.
            Part(id = "p2", messageId = "m1", type = "text", hasFull = null, omitted = listOf("tool")),
            // hasFull=false → not eligible.
            Part(id = "p3", messageId = "m1", type = "text", hasFull = false, omitted = listOf("tool")),
            // messageId=null → not eligible (G6 batch endpoint keys on message ids).
            Part(id = "p4", messageId = null, type = "text", hasFull = true, omitted = listOf("tool")),
        )

        val result = uc.expandParts("s1", emptyList(), parts).getOrThrow()

        assertEquals(ExpandPartsOutcome.Empty, result)
        coVerify(exactly = 0) { repo.expandMessagesFullBatch(any(), any()) }
    }

    @Test
    fun `C2 - Ok with full message sets Loaded for present part and merges local`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        val fullPart = Part(id = "p1", messageId = "m1", type = "text", text = "FULL TEXT")
        coEvery { repo.expandMessagesFullBatch("s1", any()) } returns ExpandOutcome.Ok(
            items = listOf(msg("m1", listOf(fullPart))),
            failedIds = emptyList(),
            usedBatch = true,
        )
        val uc = ExpandPartsUseCase(repo)
        val localPart = skeleton("p1", "m1", text = "skeleton")
        val local = listOf(msg("m1", listOf(localPart)))

        val result = uc.expandParts("s1", local, listOf(localPart)).getOrThrow()

        // Per-part state: Loaded (the part id was present in the fetched message).
        assertEquals(PartExpandState.Loaded, result.states[PartKey("m1", "p1")])
        // Merge: T8's null-safe (messageId, partId) match replaces local's text.
        assertEquals("FULL TEXT", result.mergedLocal!!.single().parts.single().text)
    }

    @Test
    fun `C2 - Ok with message present but part id absent surfaces as Failed`() = runTest {
        // Anomalous server response: the message came back, but the specific
        // part id we asked to expand is missing. Safe-default to Failed so
        // the UI's affordance offers a retry instead of silently staying
        // on a skeleton that the merge could not replace.
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.expandMessagesFullBatch("s1", any()) } returns ExpandOutcome.Ok(
            items = listOf(msg("m1", listOf(Part(id = "other-part", messageId = "m1", type = "text")))),
            failedIds = emptyList(),
            usedBatch = true,
        )
        val uc = ExpandPartsUseCase(repo)
        // Round-3: provide local owner so this exercises Branch A's
        // "fetched message present, target partId absent" path (not
        // Branch 0 orphan).
        val localPart = skeleton("p1", "m1")
        val local = listOf(msg("m1", listOf(localPart)))

        val result = uc.expandParts("s1", local, listOf(localPart)).getOrThrow()

        assertEquals(PartExpandState.Failed(code = null), result.states[PartKey("m1", "p1")])
    }

    /**
     * M2 (round-2 fix) — regression guard for the Loaded-judgment fix.
     *
     * The old Branch A check `fetchedParts.any { it.id == part.id }`
     * marked this case Loaded (partId matches) even though T8's
     * `mergeFullBatchIntoLocal` would NOT replace the local part,
     * because T8's replacement condition also requires the fetched
     * part's NORMALIZED messageId to match the owner (the
     * `takeIf { it.normMsg(owner) == lp.normMsg(owner) }` guard in
     * `SlimapiMessageMerge.kt:103-111`). A fetched part that carries
     * `messageId = "m-X"` (≠ owner `m1`, ≠ null) fails T8's guard, so
     * T8 keeps the local skeleton — and T15 must surface Failed to
     * avoid a "state=Loaded, cache=skeleton" inconsistency.
     *
     * Discriminates: this test FAILS under the old partId-only check
     * (which would have produced `Loaded`) and PASSES under the new
     * triple-match judgment.
     */
    @Test
    fun `C2 - Ok with same partId but mismatched messageId is NOT Loaded (T8 match condition)`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        // Fetched message's info.id == "m1" (so the message-level lookup
        // succeeds), and it contains a part with the SAME id ("p1") BUT
        // a mismatched messageId ("m-X" instead of "m1" / null).
        val fetchedPartMismatch = Part(
            id = "p1",
            messageId = "m-X", // ← NOT the owner's id; T8 will not replace
            type = "text",
            text = "full text from wrong message",
        )
        coEvery { repo.expandMessagesFullBatch("s1", any()) } returns ExpandOutcome.Ok(
            items = listOf(msg("m1", listOf(fetchedPartMismatch))),
            failedIds = emptyList(),
            usedBatch = true,
        )
        val uc = ExpandPartsUseCase(repo)
        // Round-3: provide a local owner so the messageId-mismatch check
        // is actually exercised. With empty local, the new owner-resolution
        // would mark this an orphan → Failed for the wrong reason; this
        // local pinning ensures Failed comes from the T8 normMsg guard.
        val localPart = skeleton("p1", "m1", text = "skeleton")
        val local = listOf(msg("m1", listOf(localPart)))

        val result = uc.expandParts("s1", local, listOf(localPart)).getOrThrow()

        // NOT Loaded — T8's normMsg guard rejects the fetched part, so
        // T15's Loaded judgment must match (defensive: don't assume the
        // server always sets part.messageId == owner.info.id).
        assertEquals(
            "mismatched-messageId fetched part must NOT be Loaded (matches T8)",
            PartExpandState.Failed(code = null),
            result.states[PartKey("m1", "p1")],
        )
    }

    /**
     * M2 (round-2 fix) — positive counterpart: fetched part whose
     * messageId is NULL still matches T8's normMsg guard (null falls
     * back to the owner's info.id), so it IS Loaded. Pin this so the
     * new triple-match does not over-tighten and break the null case.
     */
    @Test
    fun `C2 - Ok with null fetched messageId matches T8 normMsg fallback and is Loaded`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        val fetchedPartNullMsg = Part(
            id = "p1",
            messageId = null, // ← T8's normMsg(owner) collapses null → owner
            type = "text",
            text = "full text",
        )
        coEvery { repo.expandMessagesFullBatch("s1", any()) } returns ExpandOutcome.Ok(
            items = listOf(msg("m1", listOf(fetchedPartNullMsg))),
            failedIds = emptyList(),
            usedBatch = true,
        )
        val uc = ExpandPartsUseCase(repo)
        // Round-3: provide a local owner so the normMsg-fallback Loaded
        // path is actually exercised (empty local would mark orphan → Failed).
        val localPart = skeleton("p1", "m1", text = "skeleton")
        val local = listOf(msg("m1", listOf(localPart)))

        val result = uc.expandParts("s1", local, listOf(localPart)).getOrThrow()

        assertEquals(
            "null messageId falls back to owner — T8 replaces, T15 marks Loaded",
            PartExpandState.Loaded,
            result.states[PartKey("m1", "p1")],
        )
    }

    /**
     * M1 (round-3 fix) — regression guard for the owner-source fix.
     *
     * T8's `mergeFullBatchIntoLocal` iterates the LOCAL list by
     * `lm.info.id` — a part's owner is the `MessageWithParts` whose
     * `parts` list contains it, NOT the part's own `messageId` wire
     * field. When the wire field is stale/mismatched (here:
     * local `lm.info.id = "msg-A"` but `part.messageId = "msg-B"`),
     * T8 looks up `fullByMsg["msg-A"]` → not found → keeps skeleton.
     *
     * Round-2 used `part.messageId!!` as owner, so it would have
     * looked up `itemByMsg["msg-B"]` → FOUND (the test fixture
     * supplies a fetched item with `info.id = "msg-B"` and a
     * matching part) → marked Loaded despite T8 keeping skeleton.
     *
     * Discriminates: FAILS under round-2 owner source (`Loaded`),
     * PASSES under round-3 owner source (`Failed(null)` — residual,
     * since owner `msg-A` is in neither `items` nor `failedIds`).
     */
    @Test
    fun `C2 - owner is local lm info id, not part messageId wire field (round-3 T8 alignment)`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        // Fetched item with info.id == part's WIRE messageId ("msg-B"),
        // containing a fully-matching part. Under round-2 owner source
        // (part.messageId!!) this would mark Loaded. Under round-3
        // (local lm.info.id) the lookup misses → residual → Failed.
        val fetchedPart = Part(id = "p1", messageId = "msg-B", type = "text", text = "full")
        coEvery { repo.expandMessagesFullBatch("s1", any()) } returns ExpandOutcome.Ok(
            items = listOf(msg("msg-B", listOf(fetchedPart))),
            failedIds = emptyList(),
            usedBatch = true,
        )
        val uc = ExpandPartsUseCase(repo)
        // Local owner is msg-A; the part's wire messageId field claims
        // msg-B (stale / mismatched). T8 iterates local by lm.info.id
        // and would not find a fullByMsg["msg-A"] entry → keep skeleton.
        val wireMismatchPart = Part(
            id = "p1",
            messageId = "msg-B", // ← stale wire field; actual owner is msg-A
            type = "text",
            text = "skeleton",
            hasFull = true,
            omitted = listOf("tool"),
        )
        val local = listOf(MessageWithParts(Message(id = "msg-A", role = "assistant"), listOf(wireMismatchPart)))

        val result = uc.expandParts("s1", local, listOf(wireMismatchPart)).getOrThrow()

        assertEquals(
            "owner must be local lm.info.id (msg-A), not part.messageId wire field (msg-B)",
            PartExpandState.Failed(code = null),
            // PartKey still sources from the wire field per design — T16
            // identifies state by what the UI affordance was clicked on.
            result.states[PartKey("msg-B", "p1")],
        )
    }

    /**
     * M1 (round-3 fix) — orphan part defensive coverage.
     *
     * A target part whose id is NOT present in any local message's
     * `parts` list AND whose wire `messageId` doesn't match any local
     * `info.id` cannot be attributed to a local owner. T8 would never
     * see such a part (it's not in any `lm.parts`), so the
     * T8-equivalent merge is undefined. Surface as Failed defensively
     * rather than speculatively marking Loaded.
     */
    @Test
    fun `C2 - orphan part (no local owner) surfaces as Failed defensively`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        val fetchedPart = Part(id = "p1", messageId = "m1", type = "text", text = "full")
        coEvery { repo.expandMessagesFullBatch("s1", any()) } returns ExpandOutcome.Ok(
            items = listOf(msg("m1", listOf(fetchedPart))),
            failedIds = emptyList(),
            usedBatch = true,
        )
        val uc = ExpandPartsUseCase(repo)
        // local is a DIFFERENT message (m2); the target part (p1, m1)
        // has no owner in local. Without the orphan guard, round-3's
        // owner resolution would return null and the `when` would NPE.
        val local = listOf(msg("m2", listOf(Part(id = "p2", messageId = "m2", type = "text"))))

        val result = uc.expandParts("s1", local, listOf(skeleton("p1", "m1"))).getOrThrow()

        assertEquals(
            "orphan part with no local owner must surface as Failed (defensive)",
            PartExpandState.Failed(code = null),
            result.states[PartKey("m1", "p1")],
        )
    }

    @Test
    fun `C2 - Ok with messageId in failedIds sets Failed(null)`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.expandMessagesFullBatch("s1", any()) } returns ExpandOutcome.Ok(
            items = emptyList(),
            failedIds = listOf("m1"),
            usedBatch = true,
        )
        val uc = ExpandPartsUseCase(repo)
        // Round-3: provide local owner so this exercises Branch B's
        // `ownerMsgId in failedMsgIds` path (not Branch 0 orphan).
        val localPart = skeleton("p1", "m1")
        val local = listOf(msg("m1", listOf(localPart)))

        val result = uc.expandParts("s1", local, listOf(localPart)).getOrThrow()

        assertEquals(PartExpandState.Failed(code = null), result.states[PartKey("m1", "p1")])
        // No items → merge is a no-op; mergedLocal is the input list (T8's
        // pure function returns `local` unchanged when there are no items).
        // Either way the cache stays as-is.
        assertEquals(1, result.mergedLocal!!.size)
    }

    @Test
    fun `C2 - residual rule (T3 design note) marks dropped ids as Failed`() = runTest {
        // CRITICAL regression guard: `expandMessagesFullBatch`'s internal
        // 413-halve + >20-truncate can return an outcome where the
        // back-half of requested ids appear in NEITHER `items` NOR
        // `failedIds`. Without this rule, the UI would stay in Loading
        // for those parts forever.
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.expandMessagesFullBatch("s1", any()) } returns ExpandOutcome.Ok(
            // m1 resolved (items); m2 failed (failedIds); m3 silently dropped (residual).
            items = listOf(msg("m1", listOf(Part(id = "p1", messageId = "m1", type = "text", text = "full")))),
            failedIds = listOf("m2"),
            usedBatch = true,
        )
        val uc = ExpandPartsUseCase(repo)
        // Round-3: provide local owners so each target's terminal state
        // is decided by the residual rule (Branch C), not by orphan
        // short-circuit (Branch 0).
        val local = listOf(
            msg("m1", listOf(Part(id = "p1", messageId = "m1", type = "text", text = "skel",
                hasFull = true, omitted = listOf("tool")))),
            msg("m2", listOf(Part(id = "p2", messageId = "m2", type = "text", text = "skel",
                hasFull = true, omitted = listOf("tool")))),
            msg("m3", listOf(Part(id = "p3", messageId = "m3", type = "text", text = "skel",
                hasFull = true, omitted = listOf("tool")))),
        )
        val parts = local.flatMap { it.parts }

        val result = uc.expandParts("s1", local, parts).getOrThrow()

        assertEquals(PartExpandState.Loaded, result.states[PartKey("m1", "p1")])
        assertEquals(PartExpandState.Failed(code = null), result.states[PartKey("m2", "p2")])
        // Residual: m3 was requested but never resolved. This is the
        // contract-critical assertion — T15's reason for existing.
        assertEquals(PartExpandState.Failed(code = null), result.states[PartKey("m3", "p3")])
    }

    @Test
    fun `C2 - Failed outcome sets all targets to Failed(code) with no merge`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.expandMessagesFullBatch("s1", any()) } returns ExpandOutcome.Failed(
            sessionId = "s1",
            code = "upstream_unavailable",
        )
        val uc = ExpandPartsUseCase(repo)
        val parts = listOf(skeleton("p1", "m1"), skeleton("p2", "m2"))

        val result = uc.expandParts("s1", emptyList(), parts).getOrThrow()

        assertEquals(
            PartExpandState.Failed(code = "upstream_unavailable"),
            result.states[PartKey("m1", "p1")],
        )
        assertEquals(
            PartExpandState.Failed(code = "upstream_unavailable"),
            result.states[PartKey("m2", "p2")],
        )
        assertNull(result.mergedLocal)
    }

    @Test
    fun `C2 - Failed outcome with null code (transport failure) propagates null`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.expandMessagesFullBatch("s1", any()) } returns ExpandOutcome.Failed(
            sessionId = "s1",
            code = null,
        )
        val uc = ExpandPartsUseCase(repo)

        val result = uc.expandParts("s1", emptyList(), listOf(skeleton("p1", "m1"))).getOrThrow()

        assertEquals(PartExpandState.Failed(code = null), result.states[PartKey("m1", "p1")])
    }

    @Test
    fun `C2 - SessionMissing outcome sets all targets to Failed(null) with no merge`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.expandMessagesFullBatch("s1", any()) } returns ExpandOutcome.SessionMissing("s1")
        val uc = ExpandPartsUseCase(repo)
        val parts = listOf(skeleton("p1", "m1"), skeleton("p2", "m2"))

        val result = uc.expandParts("s1", emptyList(), parts).getOrThrow()

        // The coordinator (T16+) clears the local cache separately; the
        // per-part state is just so the UI does not stay in Loading.
        assertEquals(PartExpandState.Failed(code = null), result.states[PartKey("m1", "p1")])
        assertEquals(PartExpandState.Failed(code = null), result.states[PartKey("m2", "p2")])
        assertNull(result.mergedLocal)
    }

    /**
     * Rev F (CLIENT_CHANGES): thin placeholder parts are Loaded when
     * the owner message was fetched, regardless of per-part-id match.
     */
    @Test
    fun `C2 - placeholder part with hasFull=true and omitted is Loaded when owner fetched`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        // Full message has real parts with different ids than the placeholder.
        val realPart = Part(id = "prt_real", messageId = "m1", type = "text", text = "real content")
        coEvery { repo.expandMessagesFullBatch("s1", any()) } returns ExpandOutcome.Ok(
            items = listOf(msg("m1", listOf(realPart))),
            failedIds = emptyList(),
            usedBatch = true,
        )
        val uc = ExpandPartsUseCase(repo)
        // Local part is a thin placeholder.
        val placeholderPart = Part(
            id = "thin_placeholder_m1",
            messageId = "m1",
            type = "text",
            text = "skeleton",
            hasFull = true,
            omitted = listOf("tool"),
        )
        val local = listOf(msg("m1", listOf(placeholderPart)))

        val result = uc.expandParts("s1", local, listOf(placeholderPart)).getOrThrow()

        // Must be Loaded despite placeholder id not matching any fetched part.
        assertEquals(
            "thin placeholder must be Loaded when owner message is fetched",
            PartExpandState.Loaded,
            result.states[PartKey("m1", "thin_placeholder_m1")],
        )
        // Merge must have whole-replaced the parts list.
        assertEquals(listOf(realPart), result.mergedLocal!!.single().parts)
    }

    @Test
    fun `C2 - placeholder part with owner not fetched is Failed`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.expandMessagesFullBatch("s1", any()) } returns ExpandOutcome.Ok(
            items = emptyList(),
            failedIds = listOf("m1"),
            usedBatch = true,
        )
        val uc = ExpandPartsUseCase(repo)
        val placeholderPart = Part(
            id = "thin_placeholder_m1",
            messageId = "m1",
            type = "text",
            text = "skeleton",
            hasFull = true,
            omitted = listOf("tool"),
        )
        val local = listOf(msg("m1", listOf(placeholderPart)))

        val result = uc.expandParts("s1", local, listOf(placeholderPart)).getOrThrow()

        assertEquals(
            PartExpandState.Failed(code = null),
            result.states[PartKey("m1", "thin_placeholder_m1")],
        )
    }

    @Test
    fun `C2 - requests exactly the deduped set of message ids for eligible parts`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.expandMessagesFullBatch(any(), any()) } returns ExpandOutcome.Ok(
            items = emptyList(),
            failedIds = listOf("m1", "m2"),
            usedBatch = true,
        )
        val uc = ExpandPartsUseCase(repo)
        // Two parts of the same message + one part of another message →
        // deduped request = {"m1", "m2"} (NOT ["m1", "m1", "m2"]).
        val parts = listOf(
            skeleton("p1", "m1"),
            skeleton("p2", "m1"),
            skeleton("p3", "m2"),
        )

        uc.expandParts("s1", emptyList(), parts).getOrThrow()

        coVerify(exactly = 1) { repo.expandMessagesFullBatch("s1", setOf("m1", "m2")) }
    }
}
