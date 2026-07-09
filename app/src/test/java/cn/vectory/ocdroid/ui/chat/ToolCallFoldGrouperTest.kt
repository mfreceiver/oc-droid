package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §tool-fold: JVM unit tests for the pure grouping functions in
 * [ToolCallFoldGrouper]. Covers [groupItemsIntoFoldedRuns] (part-based
 * threshold, SubAgent切段), [foldCounts] (category aggregation + order),
 * [categoryCounts] (THINKING / FoldedToolRun sealed coverage), [stableItemId]
 * uniqueness, and [isFoldExpanded].
 */
class ToolCallFoldGrouperTest {

    // ── part factories ────────────────────────────────────────────────────

    private fun readPart(id: String) = Part(id = id, type = "tool", tool = "read")
    private fun grepPart(id: String) = Part(id = id, type = "tool", tool = "grep")
    private fun editPart(id: String) = Part(id = id, type = "patch")
    private fun bashPart(id: String) = Part(id = id, type = "tool", tool = "bash")
    private fun taskPart(id: String) = Part(id = id, type = "tool", tool = "task")
    private fun reasoningPart(id: String) = Part(id = id, type = "reasoning")
    private fun questionPart(id: String, state: String = "running") =
        Part(id = id, type = "tool", tool = "question",
            state = cn.vectory.ocdroid.data.model.PartState(state))
    private fun todoWritePart(id: String) = Part(id = id, type = "tool", tool = "todowrite")
    private fun writePart(id: String) = Part(id = id, type = "tool", tool = "write")
    private fun textPart(id: String) = Part(id = id, type = "text")

    private fun ctxGroup(vararg parts: Part) = ToolRenderItem.ContextGroup(parts.toList())

    // ── groupItemsIntoFoldedRuns ──────────────────────────────────────────

    @Test
    fun `empty input returns empty`() {
        assertEquals(emptyList<ToolRenderItem>(), groupItemsIntoFoldedRuns(emptyList()))
    }

    @Test
    fun `single ContextGroup with 2 parts folds`() {
        // Part-based threshold: 1 ContextGroup with 2 parts → totalParts=2 → fold
        val items = listOf(ctxGroup(readPart("r1"), grepPart("r2")))
        val result = groupItemsIntoFoldedRuns(items)
        assertEquals(1, result.size)
        assertTrue(result[0] is ToolRenderItem.FoldedToolRun)
        val fold = result[0] as ToolRenderItem.FoldedToolRun
        assertEquals(1, fold.items.size)
        assertTrue(fold.items[0] is ToolRenderItem.ContextGroup)
    }

    @Test
    fun `read plus edit folds`() {
        val items = listOf(
            ctxGroup(readPart("r1")),
            ToolRenderItem.WritePatch(editPart("e1"))
        )
        val result = groupItemsIntoFoldedRuns(items)
        assertEquals(1, result.size)
        assertTrue(result[0] is ToolRenderItem.FoldedToolRun)
    }

    @Test
    fun `single read does not fold`() {
        val items = listOf(ctxGroup(readPart("r1")))
        val result = groupItemsIntoFoldedRuns(items)
        assertEquals(1, result.size)
        // Not folded — original ContextGroup passes through
        assertTrue(result[0] is ToolRenderItem.ContextGroup)
    }

    @Test
    fun `AB SubAgent CD yields two FoldedToolRuns plus SubAgent`() {
        val items = listOf<ToolRenderItem>(
            ctxGroup(readPart("a")),
            ToolRenderItem.WritePatch(editPart("b")),
            ToolRenderItem.SubAgent(taskPart("s")),
            ctxGroup(readPart("c")),
            ToolRenderItem.WritePatch(editPart("d"))
        )
        val result = groupItemsIntoFoldedRuns(items)
        assertEquals(3, result.size)
        assertTrue("first segment folded", result[0] is ToolRenderItem.FoldedToolRun)
        assertTrue("subagent standalone", result[1] is ToolRenderItem.SubAgent)
        assertTrue("second segment folded", result[2] is ToolRenderItem.FoldedToolRun)
    }

    @Test
    fun `A SubAgent B yields none folded`() {
        val items = listOf<ToolRenderItem>(
            ctxGroup(readPart("a")),
            ToolRenderItem.SubAgent(taskPart("s")),
            ctxGroup(readPart("b"))
        )
        val result = groupItemsIntoFoldedRuns(items)
        assertEquals(3, result.size)
        // Each side has only 1 part → not folded
        assertFalse(result[0] is ToolRenderItem.FoldedToolRun)
        assertTrue(result[1] is ToolRenderItem.SubAgent)
        assertFalse(result[2] is ToolRenderItem.FoldedToolRun)
    }

    @Test
    fun `consecutive SubAgents stay independent`() {
        val items = listOf<ToolRenderItem>(
            ToolRenderItem.SubAgent(taskPart("s1")),
            ToolRenderItem.SubAgent(taskPart("s2"))
        )
        val result = groupItemsIntoFoldedRuns(items)
        assertEquals(2, result.size)
        assertTrue(result[0] is ToolRenderItem.SubAgent)
        assertTrue(result[1] is ToolRenderItem.SubAgent)
    }

    @Test
    fun `ThinkingPart contributes to fold threshold`() {
        // 1 reasoning + 1 read = totalParts 2 → fold
        val items = listOf<ToolRenderItem>(
            ToolRenderItem.ThinkingPart(reasoningPart("t1"), null),
            ctxGroup(readPart("r1"))
        )
        val result = groupItemsIntoFoldedRuns(items)
        assertEquals(1, result.size)
        assertTrue(result[0] is ToolRenderItem.FoldedToolRun)
    }

    @Test
    fun `single ThinkingPart does not fold`() {
        val items = listOf<ToolRenderItem>(
            ToolRenderItem.ThinkingPart(reasoningPart("t1"), null)
        )
        val result = groupItemsIntoFoldedRuns(items)
        assertEquals(1, result.size)
        assertFalse(result[0] is ToolRenderItem.FoldedToolRun)
    }

    // ── foldCounts ────────────────────────────────────────────────────────

    @Test
    fun `foldCounts aggregates categories correctly`() {
        // ContextGroup(read, read, grep)=3 READS + WritePatch=1 EDIT
        // + ThinkingPart=1 THINKING + Basic(bash)=1 SHELL
        val fold = ToolRenderItem.FoldedToolRun(listOf(
            ctxGroup(readPart("r1"), readPart("r2"), grepPart("g1")),
            ToolRenderItem.WritePatch(editPart("e1")),
            ToolRenderItem.ThinkingPart(reasoningPart("t1"), null),
            ToolRenderItem.Basic(bashPart("b1"))
        ))
        val counts = fold.foldCounts()
        assertEquals(3, counts[ToolCategory.READS])
        assertEquals(1, counts[ToolCategory.EDITS])
        assertEquals(1, counts[ToolCategory.THINKING])
        assertEquals(1, counts[ToolCategory.SHELL])
    }

    @Test
    fun `foldCounts follows TOOL_CATEGORY_DISPLAY_ORDER`() {
        // Verify that iterating TOOL_CATEGORY_DISPLAY_ORDER picks up counts in
        // the canonical order (READS before EDITS before SHELL before WEB
        // before THINKING before OTHER).
        val fold = ToolRenderItem.FoldedToolRun(listOf(
            ToolRenderItem.Basic(bashPart("b1")),   // SHELL
            ctxGroup(readPart("r1")),               // READS
            ToolRenderItem.WritePatch(editPart("e1")) // EDITS
        ))
        val counts = fold.foldCounts()
        val presentInOrder = TOOL_CATEGORY_DISPLAY_ORDER.filter { (counts[it] ?: 0) > 0 }
        assertEquals(
            listOf(ToolCategory.READS, ToolCategory.EDITS, ToolCategory.SHELL),
            presentInOrder
        )
    }

    // ── categoryCounts sealed coverage ────────────────────────────────────

    @Test
    fun `ThinkingPart categoryCounts returns THINKING`() {
        val item = ToolRenderItem.ThinkingPart(reasoningPart("t1"), null)
        assertEquals(mapOf(ToolCategory.THINKING to 1), item.categoryCounts())
    }

    @Test
    fun `FoldedToolRun categoryCounts returns empty`() {
        val fold = ToolRenderItem.FoldedToolRun(listOf(
            ctxGroup(readPart("r1")),
            ToolRenderItem.WritePatch(editPart("e1"))
        ))
        assertTrue(fold.categoryCounts().isEmpty())
    }

    // ── stableItemId ──────────────────────────────────────────────────────

    @Test
    fun `stableItemId unique within a message`() {
        val msgId = "msg1"
        val items = listOf<ToolRenderItem>(
            ctxGroup(readPart("r1"), grepPart("r2")),
            ToolRenderItem.SubAgent(taskPart("sa")),
            ToolRenderItem.WritePatch(editPart("e1")),
            ToolRenderItem.Basic(bashPart("b1")),
            ToolRenderItem.ThinkingPart(reasoningPart("t1"), null)
        )
        val ids = items.map { stableItemId(it, msgId) }
        assertEquals("all ids unique", ids.size, ids.toSet().size)
    }

    @Test
    fun `stableItemId FoldedToolRun does not collide with sub-item keys`() {
        val msgId = "msg1"
        val sub = ctxGroup(readPart("r1"))
        val fold = ToolRenderItem.FoldedToolRun(listOf(sub))
        val foldId = stableItemId(fold, msgId)
        val subId = stableItemId(sub, msgId)
        assertFalse("fold and sub keys must differ", foldId == subId)
        // Fold key uses the |fold| prefix; sub uses |ctx|
        assertTrue(foldId.contains("|fold|"))
        assertTrue(subId.contains("|ctx|"))
    }

    // ── isFoldExpanded ────────────────────────────────────────────────────

    @Test
    fun `isFoldExpanded false by default`() {
        val fold = ToolRenderItem.FoldedToolRun(listOf(ctxGroup(readPart("r1"), readPart("r2"))))
        assertFalse(isFoldExpanded(fold, emptyMap(), "msg1"))
    }

    @Test
    fun `isFoldExpanded true when fold key present`() {
        val fold = ToolRenderItem.FoldedToolRun(listOf(ctxGroup(readPart("r1"), readPart("r2"))))
        val foldKey = "msg1|fold|r1"
        assertTrue(isFoldExpanded(fold, mapOf(foldKey to true), "msg1"))
    }

    @Test
    fun `isFoldExpanded false when fold key explicitly false`() {
        val fold = ToolRenderItem.FoldedToolRun(listOf(ctxGroup(readPart("r1"), readPart("r2"))))
        val foldKey = "msg1|fold|r1"
        assertFalse(isFoldExpanded(fold, mapOf(foldKey to false), "msg1"))
    }

    // ── firstPartId ───────────────────────────────────────────────────────

    @Test
    fun `firstPartId of FoldedToolRun is first sub-item first part`() {
        val fold = ToolRenderItem.FoldedToolRun(listOf(
            ctxGroup(readPart("r1"), readPart("r2")),
            ToolRenderItem.WritePatch(editPart("e1"))
        ))
        assertEquals("r1", firstPartId(fold))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `firstPartId empty FoldedToolRun throws`() {
        firstPartId(ToolRenderItem.FoldedToolRun(emptyList()))
    }

    // ── foldIsRunning ─────────────────────────────────────────────────────

    @Test
    fun `foldIsRunning false when no part running`() {
        val fold = ToolRenderItem.FoldedToolRun(listOf(
            ToolRenderItem.Basic(bashPart("b1")),
            ctxGroup(readPart("r1"))
        ))
        assertFalse(foldIsRunning(fold))
    }

    @Test
    fun `foldIsRunning true when a Basic part is running`() {
        val running = bashPart("b1").copy(state = cn.vectory.ocdroid.data.model.PartState("running"))
        val fold = ToolRenderItem.FoldedToolRun(listOf(
            ToolRenderItem.Basic(running),
            ctxGroup(readPart("r1"))
        ))
        assertTrue(foldIsRunning(fold))
    }

    // ── collectToolRun (F1 streaming guard — v1 regression root cause) ─────

    @Test
    fun `collectToolRun F1 streaming reasoning breaks the run`() {
        // [tool_A, reasoning(streaming), tool_B] → run=[tool_A], nextIndex=1
        val toolA = readPart("a")
        val streaming = reasoningPart("stream")
        val toolB = readPart("b")
        val (run, nextIndex) = collectToolRun(listOf(toolA, streaming, toolB), 0, "stream")
        assertEquals(listOf(toolA), run)
        assertEquals(1, nextIndex) // points at the streaming reasoning
    }

    @Test
    fun `collectToolRun collects contiguous tool-like parts`() {
        val toolA = readPart("a")
        val toolB = bashPart("b")
        val (run, nextIndex) = collectToolRun(listOf(toolA, toolB), 0, null)
        assertEquals(listOf(toolA, toolB), run)
        assertEquals(2, nextIndex)
    }

    @Test
    fun `collectToolRun breaks at non-tool-like part`() {
        val toolA = readPart("a")
        val text = textPart("t")
        val (run, nextIndex) = collectToolRun(listOf(toolA, text), 0, null)
        assertEquals(listOf(toolA), run)
        assertEquals(1, nextIndex)
    }

    @Test
    fun `collectToolRun empty list returns empty run`() {
        val (run, nextIndex) = collectToolRun(emptyList(), 0, null)
        assertTrue(run.isEmpty())
        assertEquals(0, nextIndex)
    }

    @Test
    fun `collectToolRun startIndex out of bounds returns empty run`() {
        val (run, nextIndex) = collectToolRun(listOf(readPart("a")), 5, null)
        assertTrue(run.isEmpty())
        assertEquals(5, nextIndex)
    }

    @Test
    fun `collectToolRun null streamingReasoningPartId collects reasoning`() {
        // When no stream is active, reasoning parts ARE collected (not excluded)
        val tool = readPart("a")
        val reasoning = reasoningPart("r")
        val (run, _) = collectToolRun(listOf(tool, reasoning), 0, null)
        assertEquals(listOf(tool, reasoning), run)
    }

    // ── classifyToolRun ───────────────────────────────────────────────────

    @Test
    fun `classifyToolRun hides running question that is not stale`() {
        val items = classifyToolRun(listOf(questionPart("q1", "running")), emptyMap(), emptySet())
        assertTrue("running question should be hidden", items.isEmpty())
    }

    @Test
    fun `classifyToolRun does not hide stale question`() {
        // Stale question falls through to Basic
        val items = classifyToolRun(listOf(questionPart("q1", "running")), emptyMap(), setOf("q1"))
        assertEquals(1, items.size)
        assertTrue(items[0] is ToolRenderItem.Basic)
    }

    @Test
    fun `classifyToolRun hides todowrite`() {
        val items = classifyToolRun(listOf(todoWritePart("t1")), emptyMap(), emptySet())
        assertTrue(items.isEmpty())
    }

    @Test
    fun `classifyToolRun classifies task as SubAgent`() {
        val items = classifyToolRun(listOf(taskPart("s1")), emptyMap(), emptySet())
        assertEquals(1, items.size)
        assertTrue(items[0] is ToolRenderItem.SubAgent)
    }

    @Test
    fun `classifyToolRun groups read plus grep into ContextGroup`() {
        val items = classifyToolRun(listOf(readPart("r1"), grepPart("g1")), emptyMap(), emptySet())
        assertEquals(1, items.size)
        val ctx = items[0] as ToolRenderItem.ContextGroup
        assertEquals(2, ctx.parts.size)
    }

    @Test
    fun `classifyToolRun classifies write as WritePatch`() {
        val items = classifyToolRun(listOf(writePart("w1")), emptyMap(), emptySet())
        assertEquals(1, items.size)
        assertTrue(items[0] is ToolRenderItem.WritePatch)
    }

    @Test
    fun `classifyToolRun classifies reasoning as ThinkingPart with streamingText`() {
        val texts = mapOf("r1" to "in-flight reasoning")
        val items = classifyToolRun(listOf(reasoningPart("r1")), texts, emptySet())
        assertEquals(1, items.size)
        val thinking = items[0] as ToolRenderItem.ThinkingPart
        assertEquals("in-flight reasoning", thinking.streamingText)
    }

    @Test
    fun `classifyToolRun classifies bash as Basic`() {
        val items = classifyToolRun(listOf(bashPart("b1")), emptyMap(), emptySet())
        assertEquals(1, items.size)
        assertTrue(items[0] is ToolRenderItem.Basic)
    }

    @Test
    fun `classifyToolRun preserves order across categories`() {
        val items = classifyToolRun(
            listOf(readPart("r1"), bashPart("b1"), reasoningPart("t1"), editPart("e1")),
            emptyMap(), emptySet()
        )
        assertEquals(4, items.size)
        assertTrue(items[0] is ToolRenderItem.ContextGroup)
        assertTrue(items[1] is ToolRenderItem.Basic)
        assertTrue(items[2] is ToolRenderItem.ThinkingPart)
        assertTrue(items[3] is ToolRenderItem.WritePatch)
    }

    // ── computeHasActiveFold ──────────────────────────────────────────────

    @Test
    fun `computeHasActiveFold false when no folds`() {
        val grouped = listOf<ToolRenderItem>(
            ctxGroup(readPart("r1")),
            ToolRenderItem.Basic(bashPart("b1"))
        )
        assertFalse(computeHasActiveFold(grouped, emptyMap(), "msg1"))
    }

    @Test
    fun `computeHasActiveFold true when fold is collapsed`() {
        val grouped = listOf<ToolRenderItem>(
            ToolRenderItem.FoldedToolRun(listOf(ctxGroup(readPart("r1")), ctxGroup(readPart("r2"))))
        )
        // foldKey = "msg1|fold|r1", not in expandedParts → collapsed
        assertTrue(computeHasActiveFold(grouped, emptyMap(), "msg1"))
    }

    @Test
    fun `computeHasActiveFold false when all folds expanded`() {
        val grouped = listOf<ToolRenderItem>(
            ToolRenderItem.FoldedToolRun(listOf(ctxGroup(readPart("r1")), ctxGroup(readPart("r2"))))
        )
        assertTrue(computeHasActiveFold(grouped, emptyMap(), "msg1"))
        assertFalse(computeHasActiveFold(grouped, mapOf("msg1|fold|r1" to true), "msg1"))
    }

    @Test
    fun `computeHasActiveFold true in mixed state partial expansion`() {
        // Two fold segments: first expanded, second collapsed → true
        val grouped = listOf<ToolRenderItem>(
            ToolRenderItem.FoldedToolRun(listOf(ctxGroup(readPart("r1")), ctxGroup(readPart("r2")))),
            ToolRenderItem.SubAgent(taskPart("s1")),
            ToolRenderItem.FoldedToolRun(listOf(ctxGroup(readPart("c1")), ctxGroup(readPart("c2"))))
        )
        // First fold (r1) expanded, second fold (c1) collapsed
        val expanded = mapOf("msg1|fold|r1" to true)
        assertTrue(computeHasActiveFold(grouped, expanded, "msg1"))
    }

    // ── shouldShowToolSummary ─────────────────────────────────────────────

    @Test
    fun `shouldShowToolSummary false for single ThinkingPart`() {
        assertFalse(shouldShowToolSummary(listOf(
            ToolRenderItem.ThinkingPart(reasoningPart("t1"), null)
        )))
    }

    @Test
    fun `shouldShowToolSummary true for two ThinkingParts`() {
        assertTrue(shouldShowToolSummary(listOf(
            ToolRenderItem.ThinkingPart(reasoningPart("t1"), null),
            ToolRenderItem.ThinkingPart(reasoningPart("t2"), null)
        )))
    }

    @Test
    fun `shouldShowToolSummary true when contains READS`() {
        assertTrue(shouldShowToolSummary(listOf(
            ToolRenderItem.ThinkingPart(reasoningPart("t1"), null),
            ctxGroup(readPart("r1"))
        )))
    }

    // ── stableItemId ContextGroup require guard ───────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `stableItemId empty ContextGroup throws`() {
        stableItemId(ToolRenderItem.ContextGroup(emptyList()), "msg1")
    }
}
