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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §slimapi-client-v1 §G6 (Task 16 round-2): pure eligibility + T15 use-case
 * tests. Transition-reducer pins have been moved to the real-ViewModel
 * harness in [ChatViewModelPartExpandTest] (which drives the production
 * reducer and can detect regressions like automatic terminal→Idle cleanup).
 *
 * This file retains:
 *  - Eligibility scanning tests (C1 gate filtering).
 *  - Batching tests (single call for multiple eligible parts).
 *  - T15 use-case contract tests (already in PartExpandStateTest; the
 *    batch-across-messages test here is the production-UI-relevant variant).
 */
class PartExpandWiringTest {

    // ── factories ────────────────────────────────────────────────────────

    private fun skeleton(partId: String, msgId: String, text: String? = "skeleton") =
        Part(
            id = partId,
            messageId = msgId,
            type = "text",
            text = text,
            hasFull = true,
            omitted = listOf("tool"),
        )

    private fun nonEligible(partId: String, msgId: String) =
        Part(
            id = partId,
            messageId = msgId,
            type = "text",
            text = "regular text",
        )

    private fun msg(id: String, parts: List<Part>) =
        MessageWithParts(Message(id = id, role = "assistant"), parts)

    // ── scanning: eligible detection ──────────────────────────────────────

    @Test
    fun `C1 - scanning identifies eligible parts with hasFull and omitted`() {
        val parts = listOf(
            skeleton("p1", "m1"),
            nonEligible("p2", "m1"),
            Part(id = "p3", messageId = "m2", type = "text", hasFull = true, omitted = null),
            Part(id = "p4", messageId = "m3", type = "text", hasFull = null, omitted = listOf("tool")),
        )

        val eligible = parts.filter { it.hasFull == true && it.omitted != null && it.messageId != null }

        assertEquals("only p1 is eligible", 1, eligible.size)
        assertEquals("p1", eligible[0].id)
    }

    // ── batching: multiple eligible parts → single call ───────────────────

    @Test
    fun `C1 - multiple eligible parts in same message batched into single expandParts call`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        val fullPart1 = Part(id = "p1", messageId = "m1", type = "text", text = "FULL1")
        val fullPart2 = Part(id = "p2", messageId = "m1", type = "text", text = "FULL2")
        coEvery { repo.expandMessagesFullBatch("s1", setOf("m1")) } returns ExpandOutcome.Ok(
            items = listOf(msg("m1", listOf(fullPart1, fullPart2))),
            failedIds = emptyList(),
            usedBatch = true,
        )
        val uc = ExpandPartsUseCase(repo)
        val local = listOf(msg("m1", listOf(skeleton("p1", "m1"), skeleton("p2", "m1"))))
        val allParts = local.flatMap { it.parts }

        val result = uc.expandParts("s1", local, allParts).getOrThrow()

        // Single batch call, NOT two per-part calls.
        coVerify(exactly = 1) { repo.expandMessagesFullBatch("s1", setOf("m1")) }
        assertEquals(PartExpandState.Loaded, result.states[PartKey("m1", "p1")])
        assertEquals(PartExpandState.Loaded, result.states[PartKey("m1", "p2")])
    }

    /**
     * Per-MessageRow: multiple eligible parts in one row → one call.
     * This is the production-UI-relevant batching test (T16-C1).
     */
    @Test
    fun `C1 - per-MessageRow batching - multiple eligible parts produce one usecase call`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.expandMessagesFullBatch("s1", setOf("m1")) } returns ExpandOutcome.Ok(
            items = emptyList(),
            failedIds = listOf("m1"),
            usedBatch = true,
        )
        val uc = ExpandPartsUseCase(repo)
        val parts = listOf(
            skeleton("p1", "m1"),
            skeleton("p2", "m1"),
        )

        uc.expandParts("s1", emptyList(), parts).getOrThrow()

        coVerify(exactly = 1) { repo.expandMessagesFullBatch("s1", setOf("m1")) }
    }

    @Test
    fun `C1 - all parts ineligible results in no repository call`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        val uc = ExpandPartsUseCase(repo)
        val parts = listOf(
            nonEligible("p1", "m1"),
            nonEligible("p2", "m1"),
        )

        val result = uc.expandParts("s1", emptyList(), parts).getOrThrow()

        assertEquals(ExpandPartsOutcome.Empty, result)
        coVerify(exactly = 0) { repo.expandMessagesFullBatch(any(), any()) }
    }
}
