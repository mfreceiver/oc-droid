package com.yage.opencode_client.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.data.model.Message
import com.yage.opencode_client.data.model.Part
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.theme.opencode

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
    agentColorAssignments: MutableMap<String, Color>
) {
    val isUser = message.isUser

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // No "OpenCode" speaker title — the user's blue left bar vs the
        // assistant's container-less reply already make it clear who's speaking,
        // so an extra blue label is redundant.

        var i = 0
        while (i < parts.size) {
            val part = parts[i]
            val streamingText = streamingPartTexts[part.id]
            val isToolLike = part.isTool || (part.isPatch && part.filePathsForNavigationFiltered.isNotEmpty())
            if (isToolLike) {
                // Buffer a contiguous run of tool/patch parts, then classify and
                // render each according to the opencode-web paradigm:
                //  - todowrite → hidden (todos live in the toolbar panel)
                //  - task → SubAgentCard (the only bordered card)
                //  - context tools (read/glob/grep/list) → ContextToolGroup
                //  - write file ops → PatchCard / MultiFilePatchAccordion
                //  - everything else → BasicTool (borderless single line)
                val run = mutableListOf<Part>()
                var j = i
                while (j < parts.size) {
                    val p = parts[j]
                    if (p.isTool || (p.isPatch && p.filePathsForNavigationFiltered.isNotEmpty())) {
                        run.add(p)
                        j++
                    } else break
                }

                // Phase 1: classify the run into ordered render items, buffering
                // consecutive context tools (read/glob/grep/list) into a single
                // ContextGroup. Pure data ops only — no @Composable calls — so a
                // local helper fun is legal (a @Composable local fun is not).
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
                        // Rule 1: todowrite → hidden (todos live in the toolbar panel)
                        ToolCardClassifier.isTodoWriteTool(p) -> { /* no-op */ }
                        // Rule 2: task (sub-agent) → SubAgentCard
                        p.isSubAgentTask -> { closeContext(); items.add(ToolRenderItem.SubAgent(p)) }
                        // Rule 3: context tools → buffer for grouping
                        ToolCardClassifier.isContextTool(p) -> ctxBuffer.add(p)
                        // Rule 6: write file ops → PatchCard / MultiFilePatchAccordion
                        ToolCardClassifier.isWriteFileOperation(p) -> { closeContext(); items.add(ToolRenderItem.WritePatch(p)) }
                        // Rules 4,5,7: bash/webfetch/websearch/other → BasicTool
                        else -> { closeContext(); items.add(ToolRenderItem.Basic(p)) }
                    }
                }
                closeContext()

                // Phase 2: emit each item in @Composable context.
                items.forEach { item ->
                    when (item) {
                        is ToolRenderItem.ContextGroup -> ContextToolGroup(
                            parts = item.parts,
                            expandedParts = expandedParts,
                            onToggleExpand = onToggleExpand,
                            messageId = message.id,
                            modifier = Modifier.widthIn(max = MAX_CARD_WIDTH)
                        )
                        is ToolRenderItem.SubAgent -> SubAgentCard(
                            part = item.part,
                            onOpenSubAgent = onOpenSubAgent,
                            agentColorAssignments = agentColorAssignments,
                            modifier = Modifier.widthIn(max = MAX_CARD_WIDTH)
                        )
                        is ToolRenderItem.WritePatch -> {
                            val writeFiles = item.part.files ?: emptyList()
                            if (writeFiles.size > 1) {
                                MultiFilePatchAccordion(
                                    parts = listOf(item.part),
                                    onFileClick = onFileClick,
                                    modifier = Modifier.widthIn(max = MAX_CARD_WIDTH)
                                )
                            } else {
                                PatchCard(
                                    part = item.part,
                                    onFileClick = onFileClick,
                                    expandedParts = expandedParts,
                                    onToggleExpand = onToggleExpand,
                                    expandedKey = "${message.id}|${item.part.id}",
                                    modifier = Modifier.widthIn(max = MAX_CARD_WIDTH)
                                )
                            }
                        }
                        is ToolRenderItem.Basic -> BasicTool(
                            part = item.part,
                            onFileClick = onFileClick,
                            expandedParts = expandedParts,
                            onToggleExpand = onToggleExpand,
                            expandedKey = "${message.id}|${item.part.id}",
                            modifier = Modifier.widthIn(max = MAX_CARD_WIDTH)
                        )
                    }
                }

                i = j
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
                    agentColorAssignments = agentColorAssignments,
                    modifier = Modifier.fillMaxWidth()
                )
                i += 1
            }
        }
        // Footer caption — replaces the previous provider/model label.
        //  - Assistant: completion time (hh:mm), falling back to created time.
        //  - User: "<modelId> <created hh:mm>" so the user can see which agent
        //    model handled the turn and when the prompt was sent.
        val timeInfo = message.time
        val footerText = if (isUser) {
            val modelId = message.resolvedModel?.modelId
            val sendTime = timeInfo?.created?.let(::formatHm)
            when {
                modelId != null && sendTime != null -> "$modelId $sendTime"
                modelId != null -> modelId
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
                color = MaterialTheme.opencode.faint,
                modifier = Modifier.padding(end = 4.dp, top = 4.dp)
            )
        }
        // Inline error card — visible when the message carries an error payload.
        message.error?.message?.let { err ->
            if (err.isNotBlank()) {
                ErrorCard(
                    text = err,
                    modifier = Modifier.widthIn(max = MAX_CARD_WIDTH)
                )
            }
        }
    }
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
    agentColorAssignments: MutableMap<String, Color>,
    modifier: Modifier = Modifier.fillMaxWidth()
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
                    messageId = messageId,
                    partId = part.id,
                    expandedParts = expandedParts,
                    onToggleExpand = onToggleExpand,
                    modifier = Modifier.widthIn(max = MAX_CARD_WIDTH)
                )
            } else if (isUser || !textContent.contains("<task_result>", ignoreCase = true)) {
                TextPart(
                    text = textContent,
                    isUser = isUser,
                    modifier = modifier,
                    repository = repository,
                    workspaceDirectory = workspaceDirectory
                )
            }
        }
        part.isReasoning -> ReasoningCard(
            text = streamingTextOverride ?: part.text ?: "",
            title = part.toolReason,
            isStreaming = false,
            expandedParts = expandedParts,
            onToggleExpand = onToggleExpand,
            expandedKey = expandKey,
            modifier = Modifier.widthIn(max = MAX_CARD_WIDTH)
        )
        part.isImageAttachment -> ImageFilePart(part, modifier)
        part.isFile -> FileAttachmentPart(part, modifier)
        part.isSubAgentTask -> SubAgentCard(
            part = part,
            onOpenSubAgent = onOpenSubAgent,
            agentColorAssignments = agentColorAssignments,
            modifier = Modifier.widthIn(max = MAX_CARD_WIDTH)
        )
        part.isTool -> ToolCard(
            part = part,
            onFileClick = onFileClick,
            expandedParts = expandedParts,
            onToggleExpand = onToggleExpand,
            expandedKey = expandKey,
            modifier = Modifier.widthIn(max = MAX_CARD_WIDTH)
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
