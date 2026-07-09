package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.Part

// ── §tool-fold: pure grouping logic for contiguous tool/patch/reasoning runs ─
//
// All functions here are pure (no @Composable, no side effects) so they are
// unit-testable on the JVM without Compose/Robolectric. The pipeline is:
//   collectToolRun → classifyToolRun → groupItemsIntoFoldedRuns → computeHasActiveFold
// [collectToolRun] owns the F1 streaming-reasoning guard (the v1 regression
// root cause); extracting it here gives it automated JVM coverage.

/**
 * Collects a contiguous run of tool-like parts starting at [startIndex].
 *
 * A part is tool-like when `p.isTool || p.isPatch || p.isReasoning`. The run
 * breaks at the first part that is NOT tool-like, or — critically for **F1**
 * (the v1 regression root cause) — at the streaming-reasoning part
 * ([streamingReasoningPartId]). The streaming part is excluded from the run
 * AND breaks it (so a `[tool_A, reasoning(streaming), tool_B]` sequence yields
 * `run=[tool_A]`, `nextIndex` pointing at the streaming reasoning, not
 * `[tool_A, tool_B]`).
 *
 * @return `(run, nextIndex)` where nextIndex is the index of the first part
 *         that broke the run (or `parts.size` if the run consumed the rest).
 */
internal fun collectToolRun(
    parts: List<Part>,
    startIndex: Int,
    streamingReasoningPartId: String?
): Pair<List<Part>, Int> {
    val run = mutableListOf<Part>()
    var j = startIndex
    while (j < parts.size) {
        val p = parts[j]
        // §tool-fold F1: inner buffer guard — excludes the streaming-reasoning
        // part (double insurance with MessageRow's outer guard) so it is never
        // duplicated inside a run AND breaks the run.
        if ((p.isTool || p.isPatch || p.isReasoning) && p.id != streamingReasoningPartId) {
            run.add(p)
            j++
        } else break
    }
    return run to j
}

/**
 * Phase 1 classification: turns a flat list of tool-like [Part]s into ordered
 * [ToolRenderItem]s, buffering consecutive context tools (read/glob/grep/list)
 * into a single [ToolRenderItem.ContextGroup]. Pure data ops only (no
 * @Composable calls) — extracted verbatim from MessageRow so it is JVM-testable.
 *
 * Rules (in priority order):
 *  - question (running/pending, not stale) → hidden
 *  - todowrite → hidden
 *  - task (sub-agent) → SubAgent (closes context buffer first)
 *  - reasoning → ThinkingPart (carries streamingText from streamingPartTexts)
 *  - context tool → buffer for ContextGroup
 *  - write/edit/patch → WritePatch
 *  - everything else → Basic
 */
internal fun classifyToolRun(
    run: List<Part>,
    streamingPartTexts: Map<String, String>,
    staleQuestionPartKeys: Set<String>
): List<ToolRenderItem> {
    val items = mutableListOf<ToolRenderItem>()
    var ctxBuffer = mutableListOf<Part>()
    fun closeContext() {
        if (ctxBuffer.isNotEmpty()) {
            items.add(ToolRenderItem.ContextGroup(ctxBuffer.toList()))
            ctxBuffer = mutableListOf()
        }
    }
    for (p in run) {
        when {
            // §P1 (question UI parity with web): hide running/pending question
            // tool parts. STALE question parts are NOT hidden — they fall
            // through to BasicTool's "Interrupted" rendering.
            p.isTool && p.tool?.lowercase() == "question" &&
                (p.stateDisplay == "running" || p.stateDisplay == "pending") &&
                p.id !in staleQuestionPartKeys -> { /* no-op: hidden */ }
            // Rule 1: todowrite → hidden (todos live in the toolbar panel)
            ToolCardClassifier.isTodoWriteTool(p) -> { /* no-op */ }
            // Rule 2: task (sub-agent) → SubAgentCard
            p.isSubAgentTask -> { closeContext(); items.add(ToolRenderItem.SubAgent(p)) }
            // §tool-fold F5: reasoning → ThinkingPart with streaming text
            p.isReasoning -> { closeContext(); items.add(ToolRenderItem.ThinkingPart(p, streamingPartTexts[p.id])) }
            // Rule 3: context tools → buffer for grouping
            ToolCardClassifier.isContextTool(p) -> ctxBuffer.add(p)
            // Rule 6: write file ops → PatchCard / MultiFilePatchAccordion
            ToolCardClassifier.isWriteFileOperation(p) -> { closeContext(); items.add(ToolRenderItem.WritePatch(p)) }
            // Rules 4,5,7: bash/webfetch/websearch/other → BasicTool
            else -> { closeContext(); items.add(ToolRenderItem.Basic(p)) }
        }
    }
    closeContext()
    return items
}

/**
 * Whether any [ToolRenderItem.FoldedToolRun] in [groupedItems] is currently
 * collapsed (not expanded). When true, the per-message [ToolCountSummary] is
 * hidden to avoid double-counting with the FoldBar ([F4]/[F11]). Mixed-state
 * semantics: one message can have multiple fold segments (separated by
 * SubAgent/text); if ANY segment is collapsed → true.
 */
internal fun computeHasActiveFold(
    groupedItems: List<ToolRenderItem>,
    expandedParts: Map<String, Boolean>,
    messageId: String
): Boolean =
    groupedItems.any {
        it is ToolRenderItem.FoldedToolRun &&
            !isFoldExpanded(it, expandedParts, messageId)
    }

/**
 * Groups a flat list of classified [ToolRenderItem]s into [ToolRenderItem.FoldedToolRun]
 * segments. A segment is folded when its total part count is ≥ 2 (part-based
 * threshold [F3], not item count — so a single ContextGroup of 2 reads folds).
 * [ToolRenderItem.SubAgent] breaks the current segment (it renders as its own
 * bordered card and would double-count), and is never itself folded.
 *
 * Boundary cases (see ToolCallFoldGrouperTest):
 *  - empty input → empty output
 *  - single ContextGroup(parts.size=2) → FoldedToolRun (part-based threshold key case)
 *  - read + edit → FoldedToolRun
 *  - single read → unchanged (totalParts=1 < 2)
 *  - [A, B, SubAgent, C, D] → FoldedToolRun([A,B]), SubAgent, FoldedToolRun([C,D])
 *  - [A, SubAgent, B] → A, SubAgent, B (none folded)
 */
internal fun groupItemsIntoFoldedRuns(items: List<ToolRenderItem>): List<ToolRenderItem> {
    val result = mutableListOf<ToolRenderItem>()
    var current = mutableListOf<ToolRenderItem>()

    fun partCount(it: ToolRenderItem): Int = when (it) {
        is ToolRenderItem.ContextGroup -> it.parts.size
        is ToolRenderItem.ThinkingPart,
        is ToolRenderItem.WritePatch,
        is ToolRenderItem.Basic -> 1
        is ToolRenderItem.SubAgent -> 0          // breaks segment, not counted
        is ToolRenderItem.FoldedToolRun -> 0      // never appears in input (defensive)
    }

    fun flush() {
        // [F3] threshold by total part count within the segment, not item count
        // → read+grep (1 ContextGroup, 2 parts) also folds.
        val totalParts = current.sumOf { partCount(it) }
        if (totalParts >= 2) {
            result.add(ToolRenderItem.FoldedToolRun(current.toList()))
        } else {
            result.addAll(current)
        }
        current = mutableListOf()
    }

    for (item in items) {
        if (item is ToolRenderItem.SubAgent) {
            // SubAgent breaks the segment; flush then emit standalone.
            flush()
            result.add(item)
        } else {
            current.add(item)
        }
    }
    flush()
    return result
}

/**
 * Aggregated per-category counts for a [ToolRenderItem.FoldedToolRun], summed
 * across all its sub-items via [categoryCounts]. The returned map only contains
 * categories with count > 0, in arbitrary key order (callers iterate via
 * [TOOL_CATEGORY_DISPLAY_ORDER] for a stable display sequence).
 */
internal fun ToolRenderItem.FoldedToolRun.foldCounts(): Map<ToolCategory, Int> =
    items.flatMap { it.categoryCounts().entries }
        .groupingBy { it.key }
        .fold(0) { acc, e -> acc + e.value }

/**
 * Whether this fold segment is currently expanded. The fold key follows the
 * convention `"${messageId}|fold|${firstPartId}"` and is looked up in the
 * shared [expandedParts] map (same map used by all expandable cards).
 */
internal fun isFoldExpanded(
    fold: ToolRenderItem.FoldedToolRun,
    expandedParts: Map<String, Boolean>,
    messageId: String
): Boolean {
    val foldKey = "${messageId}|fold|${firstPartId(fold)}"
    return expandedParts[foldKey] == true
}

/**
 * The first part id within a [ToolRenderItem] — used as the stable anchor for
 * fold keys and stable item ids. For a [ToolRenderItem.FoldedToolRun] it is the
 * first part id of the first sub-item; for leaf items it is the part's own id.
 * New parts appended to the end of a run do not change this id (stable).
 */
internal fun firstPartId(item: ToolRenderItem): String = when (item) {
    is ToolRenderItem.FoldedToolRun -> {
        require(item.items.isNotEmpty()) { "FoldedToolRun must contain at least one item" }
        firstPartId(item.items.first())
    }
    is ToolRenderItem.ContextGroup -> {
        require(item.parts.isNotEmpty()) { "ContextGroup must contain at least one part" }
        item.parts.first().id
    }
    is ToolRenderItem.WritePatch -> item.part.id
    is ToolRenderItem.Basic -> item.part.id
    is ToolRenderItem.ThinkingPart -> item.part.id
    is ToolRenderItem.SubAgent -> item.part.id
}

/**
 * A stable string identity for a [ToolRenderItem] within a message, used as a
 * Compose `key(...)` to prevent sub-item reshuffle jitter when a fold expands
 * or collapses ([F9]). The prefix namespace (`fold|` / `ctx|` / bare part id)
 * never collides with existing `expandedKey` prefixes used by individual cards.
 *
 *  - FoldedToolRun → `"$msgId|fold|${firstPartId}"`
 *  - ContextGroup → `"$msgId|ctx|${parts.first().id}"`
 *  - other leaf items → `"$msgId|${part.id}"`
 */
internal fun stableItemId(item: ToolRenderItem, messageId: String): String = when (item) {
    is ToolRenderItem.FoldedToolRun -> "${messageId}|fold|${firstPartId(item)}"
    is ToolRenderItem.ContextGroup -> {
        require(item.parts.isNotEmpty()) { "ContextGroup must contain at least one part" }
        "${messageId}|ctx|${item.parts.first().id}"
    }
    is ToolRenderItem.WritePatch -> "${messageId}|${item.part.id}"
    is ToolRenderItem.Basic -> "${messageId}|${item.part.id}"
    is ToolRenderItem.ThinkingPart -> "${messageId}|${item.part.id}"
    is ToolRenderItem.SubAgent -> "${messageId}|${item.part.id}"
}

/**
 * Whether any part within a [ToolRenderItem.FoldedToolRun] is currently in the
 * "running" state. Drives the optional trailing spinner on [ToolCallFoldBar]
 * so the user can see the segment is still in flight.
 */
internal fun foldIsRunning(fold: ToolRenderItem.FoldedToolRun): Boolean =
    fold.items.any { item ->
        when (item) {
            is ToolRenderItem.ContextGroup -> item.parts.any { it.stateDisplay == "running" }
            is ToolRenderItem.WritePatch -> item.part.stateDisplay == "running"
            is ToolRenderItem.Basic -> item.part.stateDisplay == "running"
            is ToolRenderItem.ThinkingPart -> item.part.stateDisplay == "running"
            is ToolRenderItem.SubAgent -> item.part.stateDisplay == "running"
            is ToolRenderItem.FoldedToolRun -> foldIsRunning(item) // nested (defensive)
        }
    }
