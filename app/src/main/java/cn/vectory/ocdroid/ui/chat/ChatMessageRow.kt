package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.repository.OpenCodeRepository

// ── Per-message row + Part dispatcher ────────────────────────────────────
// MessageRow lays out a single turn (column of parts + footer caption +
// inline error). It classifies contiguous tool/patch runs via ToolRenderItem
// and routes them to the appropriate card, falling back to PartView for
// non-tool parts. PartView is the per-Part router (text / reasoning / image /
// file / sub-agent / tool).

/**
 * Classification of a single tool/patch part for ordered rendering within a
 * contiguous tool run. Built in a pure-data phase (no @Composable calls) so a
 * local helper fun can buffer context tools; emitted afterwards in @Composable
 * context. See [MessageRow].
 */
internal sealed class ToolRenderItem {
    data class ContextGroup(val parts: List<Part>) : ToolRenderItem()
    data class SubAgent(val part: Part) : ToolRenderItem()
    data class WritePatch(val part: Part) : ToolRenderItem()
    data class Basic(val part: Part) : ToolRenderItem()
    /** A reasoning part folded into the tool run (carries its streaming text, if any). */
    data class ThinkingPart(val part: Part, val streamingText: String?) : ToolRenderItem()
    /** A folded group of contiguous tool/patch/reasoning items (total parts ≥ 2). */
    data class FoldedToolRun(val items: List<ToolRenderItem>) : ToolRenderItem()
}

@Composable
internal fun MessageRow(
    message: Message,
    parts: List<Part>,
    streamingPartTexts: Map<String, String>,
    repository: OpenCodeRepository,
    workspaceDirectory: String?,
    onFileClick: (String) -> Unit,
    onOpenSubAgent: (String) -> Unit,
    expandedParts: Map<String, Boolean>,
    onToggleExpand: (String, Boolean) -> Unit,
    // §stale-question: part ids whose question tool part is stuck "running"
    // without a matching live QuestionRequest — rendered terminally instead
    // of with a perpetual spinner. Empty by default so legacy callers keep
    // compiling.
    staleQuestionPartKeys: Set<String> = emptySet(),
    // Suppress historical reasoning parts whose id matches the active
    // streaming-reasoning part (the standalone streaming item in
    // ChatMessageList already renders it). Null when no stream is active.
    streamingReasoningPartId: String? = null
) {
    val isUser = message.isUser
    // §issue-4: task completion messages arrive as user-role but should
    // render as assistant (left-aligned, time-only footer, no model info).
    val isTaskCompletionMsg = isUser && parts.any { p ->
        p.isText && (p.text ?: "").contains("<task") &&
        parseTaskXml(p.text)?.state?.let {
            it.equals("completed", ignoreCase = true) ||
            it.equals("error", ignoreCase = true)
        } == true
    }

    // §card-width (v0.2.12): responsive card width = 2/3 of the available row
    // width, capped at 480dp for tablets. Replaces the old fixed 220dp cap.
    // Wrapped in BoxWithConstraints so all cards below read `cardMax`.
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        val cardMax = minOf(maxWidth * 2f / 3f, 480.dp)
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (isUser && !isTaskCompletionMsg) Alignment.End else Alignment.Start
        ) {
        // No "OpenCode" speaker title — the user's blue left bar vs the
        // assistant's container-less reply already make it clear who's speaking,
        // so an extra blue label is redundant.

        var i = 0
        while (i < parts.size) {
            val part = parts[i]
            // Skip historical reasoning parts that are currently rendered as
            // the standalone streaming-reasoning item in ChatMessageList.
            if (part.isReasoning && streamingReasoningPartId != null && part.id == streamingReasoningPartId) {
                i += 1
                continue
            }
            val streamingText = streamingPartTexts[part.id]
            // §kimo-B4: isToolLike 入口不再要求 patch 带可导航路径——无路径/无扩展名
            // (Makefile 等) 或服务端未填路径的 patch 也要进入 run，由 PatchCard 的
            // 内容回退兜底（避免展开为空）。与 ToolCardClassifier.isWriteFileOperation 放宽一致。
            val isToolLike = part.isTool || part.isPatch || part.isReasoning
            if (isToolLike) {
                // §tool-fold refactor: run collection (F1 guard) and Phase 1
                // classification are now pure functions in ToolCallFoldGrouper.kt
                // (collectToolRun / classifyToolRun) so they are JVM-testable.
                // The outer streaming-reasoning guard (L96-101) stays in
                // MessageRow; collectToolRun handles parts inside the run.
                val (run, nextIndex) = collectToolRun(parts, i, streamingReasoningPartId)
                val items = classifyToolRun(run, streamingPartTexts, staleQuestionPartKeys)

                // §tool-fold F12: group contiguous items into FoldedToolRuns
                // (part-based threshold ≥ 2, SubAgent切段). Memoized on items
                // content + streamingReasoningPartId so a recomposition that
                // rebuilds the identical items list doesn't recompute grouping.
                val groupedItems = remember(items, streamingReasoningPartId) {
                    groupItemsIntoFoldedRuns(items)
                }

                // §tool-fold F4/F11: hide the ToolCountSummary while any fold
                // segment is collapsed (avoids double-counting with the
                // FoldBar). shouldShowToolSummary also suppresses a lone single
                // ThinkingPart (keeps baseline — pure single-reasoning messages
                // show no tally).
                val hasActiveFold = computeHasActiveFold(groupedItems, expandedParts, message.id)
                if (items.isNotEmpty() && !hasActiveFold && shouldShowToolSummary(items)) {
                    ToolCountSummary(
                        items = items,
                        modifier = Modifier.widthIn(max = cardMax)
                    )
                }

                // Phase 2: emit each (possibly grouped) item in @Composable
                // context. §tool-fold F2/F9.
                groupedItems.forEach { item ->
                    // §tool-fold F9: stable key prevents sub-item reshuffle
                    // jitter when a fold expands/collapses.
                    key(stableItemId(item, message.id)) {
                        when (item) {
                            is ToolRenderItem.FoldedToolRun -> {
                                val foldKey = "${message.id}|fold|${firstPartId(item)}"
                                if (expandedParts[foldKey] == true) {
                                    // Expanded: render each sub-item natively
                                    // (each with its own stable key).
                                    item.items.forEach { sub ->
                                        key(stableItemId(sub, message.id)) {
                                            renderToolItem(
                                                item = sub,
                                                message = message,
                                                expandedParts = expandedParts,
                                                onToggleExpand = onToggleExpand,
                                                onFileClick = onFileClick,
                                                onOpenSubAgent = onOpenSubAgent,
                                                staleQuestionPartKeys = staleQuestionPartKeys,
                                                cardMax = cardMax
                                            )
                                        }
                                    }
                                } else {
                                    ToolCallFoldBar(
                                        counts = item.foldCounts(),
                                        isRunning = foldIsRunning(item),
                                        onToggleExpand = { onToggleExpand(foldKey, false) },
                                        modifier = Modifier.widthIn(max = cardMax)
                                    )
                                }
                            }
                            is ToolRenderItem.ThinkingPart -> ReasoningCard(
                                text = item.streamingText ?: item.part.text ?: "",
                                title = item.part.toolReason,
                                isStreaming = false,
                                expandedParts = expandedParts,
                                onToggleExpand = onToggleExpand,
                                expandedKey = "${message.id}|${item.part.id}",
                                modifier = Modifier.widthIn(max = cardMax)
                            )
                            is ToolRenderItem.ContextGroup,
                            is ToolRenderItem.SubAgent,
                            is ToolRenderItem.WritePatch,
                            is ToolRenderItem.Basic -> renderToolItem(
                                item = item,
                                message = message,
                                expandedParts = expandedParts,
                                onToggleExpand = onToggleExpand,
                                onFileClick = onFileClick,
                                onOpenSubAgent = onOpenSubAgent,
                                staleQuestionPartKeys = staleQuestionPartKeys,
                                cardMax = cardMax
                            )
                        }
                    }
                }

                i = nextIndex
            } else {
                PartView(
                    part = part,
                    isUser = isUser,
                    streamingTextOverride = streamingText,
                    repository = repository,
                    workspaceDirectory = workspaceDirectory,
                    onFileClick = onFileClick,
                    onOpenSubAgent = onOpenSubAgent,
                    messageId = message.id,
                    expandedParts = expandedParts,
                    onToggleExpand = onToggleExpand,
                    cardMax = cardMax,
                    modifier = Modifier.fillMaxWidth()
                )
                i += 1
            }
        }
        // Footer caption — replaces the previous provider/model label.
        //  - Assistant: completion time (hh:mm), falling back to created time.
        //  - User: "<agent>·<modelId> <created hh:mm>" so the user can see which
        //    agent + model handled the turn and when the prompt was sent. The
        //    middot joins agent and model only when both are present; each is
        //    omitted individually when the server did not echo it.
        val timeInfo = message.time
        val footerText = if (isUser && !isTaskCompletionMsg) {
            val agentName = message.agent
            val modelId = message.resolvedModel?.modelId
            val sendTime = timeInfo?.created?.let(::formatHm)
            val amParts = listOfNotNull(agentName, modelId).joinToString("·")
            when {
                amParts.isNotEmpty() && sendTime != null -> "$amParts $sendTime"
                amParts.isNotEmpty() -> amParts
                sendTime != null -> sendTime
                else -> null
            }
        } else {
            (timeInfo?.completed ?: timeInfo?.created)?.let(::formatHm)
        }
        if (footerText != null) {
            // §4.4 v2 footer: labelSmall + faint color + top=4dp padding. The
            // Column's horizontalAlignment (End for user, Start for assistant)
            // already aligns the footer to the speaker's side.
            Text(
                text = footerText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp, top = 4.dp)
            )
        }
        // Inline error card — visible when the message carries an error payload.
        message.error?.message?.let { err ->
            if (err.isNotBlank()) {
                ErrorCard(
                    text = err,
                    modifier = Modifier.widthIn(max = cardMax)
                )
            }
        }
        } // Column
        } // BoxWithConstraints
}

@Composable
internal fun PartView(
    part: Part,
    isUser: Boolean,
    streamingTextOverride: String?,
    repository: OpenCodeRepository,
    workspaceDirectory: String?,
    onFileClick: (String) -> Unit,
    onOpenSubAgent: (String) -> Unit,
    messageId: String,
    expandedParts: Map<String, Boolean>,
    onToggleExpand: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    // §card-width: responsive card max width (2/3 screen, capped 480dp) from
    // the caller's BoxWithConstraints. Defaults to the legacy fixed cap so
    // unscoped callers (tests/preview) still compile.
    cardMax: Dp = MAX_CARD_WIDTH
) {
    val expandKey = "${messageId}|${part.id}"
    when {
        part.isText -> {
            val textContent = streamingTextOverride ?: part.text ?: ""
            // Detect background subagent task completion blocks injected as
            // user-role text messages by the server (ops.prompt with <task> XML).
            // Restricted to user messages: the server only injects these as
            // synthetic user prompts; assistant task output arrives via tool
            // parts (handled by SubAgentCard). Matching is case-SENSITIVE to
            // stay consistent with parseTaskXml (the server always emits
            // lowercase <task>), so an assistant discussing the literal tag is
            // never swallowed.
            val taskXml = if (isUser && textContent.contains("<task")) {
                parseTaskXml(textContent)
            } else null
            if (taskXml != null && taskXml.state != null &&
                (taskXml.state.equals("completed", ignoreCase = true) ||
                    taskXml.state.equals("error", ignoreCase = true))
            ) {
                CompletedTaskCard(
                    taskResult = taskXml,
                    expandedParts = expandedParts,
                    onToggleExpand = onToggleExpand,
                    expandedKey = "task|$expandKey",
                    modifier = Modifier.widthIn(max = cardMax)
                )
            } else if (isUser || !textContent.contains("<task_result>")) {
                TextPart(
                    text = textContent,
                    isUser = isUser,
                    modifier = modifier,
                    repository = repository,
                    workspaceDirectory = workspaceDirectory,
                    isStreaming = streamingTextOverride != null,
                    // §0.6.2 ora-2: stable identity shared between TextPart's
                    // streaming and completed branches so HeightAnchorRegistry
                    // carries the maxHeight across finalization → seamless.
                    stableKey = "$messageId|${part.id}"
                )
            }
        }
        // §tool-fold F7: the `part.isReasoning -> ReasoningCard(...)` branch
        // that lived here is dead code — MessageRow's `isToolLike` now includes
        // `isReasoning`, so every reasoning part (except the streaming one
        // guarded at L92-95) is routed into the buffered tool run and rendered
        // as a ThinkingPart. Removed to avoid silently resurrecting the old
        // PartView path if a future reclassification lands here.
        part.isImageAttachment -> ImageFilePart(part, modifier)
        part.isFile -> FileAttachmentPart(part, modifier)
        part.isSubAgentTask -> SubAgentCard(
            part = part,
            onOpenSubAgent = onOpenSubAgent,
            modifier = Modifier.widthIn(max = cardMax)
        )
        part.isTool -> ToolCard(
            part = part,
            onFileClick = onFileClick,
            expandedParts = expandedParts,
            onToggleExpand = onToggleExpand,
            expandedKey = expandKey,
            modifier = Modifier.widthIn(max = cardMax)
        )
        // Dead code removed (评审 Stage C #1): the previous
        // `part.isPatch && part.filePathsForNavigationFiltered.isNotEmpty()`
        // branch was unreachable here because [MessageRow]'s `isToolLike`
        // check above already routes every such part into the buffered tool
        // run (which renders PatchCard / MultiFilePatchAccordion from
        // [writeParts]). Keeping this branch would risk silently sending a
        // future reclassification straight into the single-file PatchCard and
        // dropping the MultiFilePatchAccordion path for multi-file patches.
    }
}

// ── §tool-fold helpers ────────────────────────────────────────────────────

/**
 * §tool-fold 轻微项④: Renders a single non-fold [ToolRenderItem] (ContextGroup /
 * SubAgent / WritePatch / Basic / ThinkingPart) with the enclosing message's
 * context. Extracted from the old inline Phase 2 `when` body so a
 * [ToolRenderItem.FoldedToolRun]'s expanded sub-items can reuse the exact same
 * render path without duplicating the card wiring. [FoldedToolRun] itself is a
 * defensive no-op (it is handled by the caller, never passed here in practice).
 */
@Composable
private fun renderToolItem(
    item: ToolRenderItem,
    message: Message,
    expandedParts: Map<String, Boolean>,
    onToggleExpand: (String, Boolean) -> Unit,
    onFileClick: (String) -> Unit,
    onOpenSubAgent: (String) -> Unit,
    staleQuestionPartKeys: Set<String>,
    cardMax: Dp
) {
    when (item) {
        is ToolRenderItem.ContextGroup -> ContextToolGroup(
            parts = item.parts,
            expandedParts = expandedParts,
            onToggleExpand = onToggleExpand,
            messageId = message.id,
            modifier = Modifier.widthIn(max = cardMax)
        )
        is ToolRenderItem.SubAgent -> SubAgentCard(
            part = item.part,
            onOpenSubAgent = onOpenSubAgent,
            modifier = Modifier.widthIn(max = cardMax)
        )
        is ToolRenderItem.WritePatch -> {
            val writeFiles = item.part.files ?: emptyList()
            if (writeFiles.size > 1) {
                MultiFilePatchAccordion(
                    parts = listOf(item.part),
                    onFileClick = onFileClick,
                    modifier = Modifier.widthIn(max = cardMax)
                )
            } else {
                PatchCard(
                    part = item.part,
                    onFileClick = onFileClick,
                    expandedParts = expandedParts,
                    onToggleExpand = onToggleExpand,
                    expandedKey = "${message.id}|${item.part.id}",
                    modifier = Modifier.widthIn(max = cardMax)
                )
            }
        }
        is ToolRenderItem.Basic -> BasicTool(
            part = item.part,
            onFileClick = onFileClick,
            expandedParts = expandedParts,
            onToggleExpand = onToggleExpand,
            expandedKey = "${message.id}|${item.part.id}",
            isStale = item.part.id in staleQuestionPartKeys,
            modifier = Modifier.widthIn(max = cardMax)
        )
        is ToolRenderItem.ThinkingPart -> ReasoningCard(
            text = item.streamingText ?: item.part.text ?: "",
            title = item.part.toolReason,
            isStreaming = false,
            expandedParts = expandedParts,
            onToggleExpand = onToggleExpand,
            expandedKey = "${message.id}|${item.part.id}",
            modifier = Modifier.widthIn(max = cardMax)
        )
        is ToolRenderItem.FoldedToolRun -> { /* defensive no-op — handled by caller */ }
    }
}

/**
 * §tool-fold 轻微项②: Whether the per-message [ToolCountSummary] should show.
 * Suppresses the tally for a lone single reasoning part (keeps baseline
 * behavior — a pure single-thinking message renders no count line). Shows the
 * tally when there is any non-THINKING category, or when there are ≥ 2
 * thinking parts.
 */
internal fun shouldShowToolSummary(items: List<ToolRenderItem>): Boolean {
    val entries = items.flatMap { it.categoryCounts().entries }
    return entries.any { it.key != ToolCategory.THINKING } ||
        entries.count { it.key == ToolCategory.THINKING } >= 2
}
