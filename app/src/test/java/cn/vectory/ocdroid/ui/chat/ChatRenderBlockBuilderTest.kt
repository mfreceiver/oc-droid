package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.PartState
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRenderBlockBuilderTest {
    private fun message(id: String, role: String = "assistant") = Message(id = id, role = role)
    private fun erroredMessage(id: String) = Message(
        id = id,
        role = "assistant",
        error = Message.MessageError(data = buildJsonObject { put("message", JsonPrimitive("failed")) })
    )
    private fun tool(id: String, messageId: String, state: String = "completed") =
        Part(id = id, messageId = messageId, type = "tool", tool = "bash", state = PartState(state))
    private fun todo(id: String, messageId: String) =
        Part(id = id, messageId = messageId, type = "tool", tool = "todowrite", state = PartState("completed"))
    private fun text(id: String, messageId: String) = Part(id = id, messageId = messageId, type = "text", text = id)
    private fun reasoning(id: String, messageId: String) = Part(id = id, messageId = messageId, type = "reasoning")
    private fun task(id: String, messageId: String) = Part(id = id, messageId = messageId, type = "tool", tool = "task")
    private fun contextTool(id: String, messageId: String) =
        Part(id = id, messageId = messageId, type = "tool", tool = "read", state = PartState("completed"))
    private fun patch(id: String, messageId: String) = Part(id = id, messageId = messageId, type = "patch")
    private fun question(id: String, messageId: String) =
        Part(id = id, messageId = messageId, type = "tool", tool = "question", state = PartState("running"))
    // §fold-fix: real opencode tool calls are wrapped in [step-start, …, step-finish]
    // part sequences. These markers are metadata, not conversation content.
    private fun stepStart(id: String, messageId: String) =
        Part(id = id, messageId = messageId, type = "step-start")
    private fun stepFinish(id: String, messageId: String) =
        Part(id = id, messageId = messageId, type = "step-finish")

    private fun build(
        messages: List<Message>,
        parts: Map<String, List<Part>>,
        streamingReasoningPartId: String? = null,
        staleQuestionPartKeys: Set<String> = emptySet(),
        sessionIsRunning: Boolean = false
    ) = buildRenderBlocks(
        messages = messages,
        partsByMessage = parts,
        streamingPartTexts = emptyMap(),
        staleQuestionPartKeys = staleQuestionPartKeys,
        streamingReasoningPartId = streamingReasoningPartId,
        sessionIsRunning = sessionIsRunning
    )

    @Test
    fun `tools in adjacent messages form one cross-message fold`() {
        val m1 = message("m1")
        val m2 = message("m2")
        val blocks = build(
            listOf(m1, m2),
            mapOf("m1" to listOf(tool("a", "m1")), "m2" to listOf(tool("b", "m2")))
        )

        assertEquals(1, blocks.size)
        val fold = blocks.single() as RenderBlock.Fold
        assertEquals("run|a", fold.id)
        assertEquals(listOf("a", "b"), fold.items.map { firstPartId(it.item) })
        assertEquals(listOf("m1", "m2"), fold.messageDecorations.map { it.id })
    }

    @Test
    fun `single tool renders directly instead of as a fold`() {
        val m = erroredMessage("m")

        val block = build(listOf(m), mapOf("m" to listOf(tool("a", "m")))).single()

        val direct = block as RenderBlock.ToolRun
        assertEquals(listOf("a"), direct.items.map { firstPartId(it.item) })
        assertEquals(listOf("m"), direct.messageDecorations.map { it.id })
    }

    @Test
    fun `user message breaks cross-message folds`() {
        val m1 = message("m1")
        val user = message("u", "user")
        val m2 = message("m2")
        val blocks = build(
            listOf(m1, user, m2),
            mapOf(
                "m1" to listOf(tool("a", "m1")),
                "u" to listOf(text("u-text", "u")),
                "m2" to listOf(tool("b", "m2"))
            )
        )

        assertTrue(blocks[0] is RenderBlock.ToolRun)
        assertTrue(blocks[1] is RenderBlock.Conversation)
        assertTrue(blocks[2] is RenderBlock.ToolRun)
    }

    @Test
    fun `assistant prose splits a mixed message at part level`() {
        val m = message("m")
        val blocks = build(
            listOf(m),
            mapOf("m" to listOf(tool("a", "m"), text("prose", "m"), tool("b", "m")))
        )

        assertEquals(3, blocks.size)
        assertEquals("run|a", blocks[0].id)
        assertEquals(listOf("prose"), (blocks[1] as RenderBlock.Conversation).parts.map { it.id })
        assertEquals("run|b", blocks[2].id)
        assertFalse((blocks[1] as RenderBlock.Conversation).showMessageDecoration)
        assertEquals(listOf("m"), (blocks[2] as RenderBlock.ToolRun).messageDecorations.map { it.id })
    }

    @Test
    fun `sub-agent is a conversation block and breaks folds`() {
        val m = message("m")
        val blocks = build(
            listOf(m),
            mapOf("m" to listOf(tool("a", "m"), task("task", "m"), tool("b", "m")))
        )

        assertEquals(3, blocks.size)
        assertEquals(listOf("task"), (blocks[1] as RenderBlock.Conversation).parts.map { it.id })
    }

    @Test
    fun `direct tool run keeps top-level id when it becomes a fold`() {
        val m = message("m")
        val before = build(listOf(m), mapOf("m" to listOf(tool("a", "m"))))
        val after = build(listOf(m), mapOf("m" to listOf(tool("a", "m"), tool("b", "m"))))

        assertEquals("run|a", before.single().id)
        assertEquals(before.single().id, after.single().id)
    }

    @Test
    fun `streaming reasoning is excluded without joining tools across it`() {
        val m = message("m")
        val blocks = build(
            listOf(m),
            mapOf("m" to listOf(tool("a", "m"), reasoning("live", "m"), tool("b", "m"))),
            streamingReasoningPartId = "live"
        )

        assertEquals(listOf("run|a", "run|b"), blocks.map { it.id })
        assertFalse(blocks.flatMap { (it as RenderBlock.ToolRun).items }.any { firstPartId(it.item) == "live" })
        assertEquals(listOf("m"), (blocks.last() as RenderBlock.ToolRun).messageDecorations.map { it.id })
    }

    @Test
    fun `fold is collapsed by default and running state spans all parts`() {
        val m = message("m")
        val blocks = build(
            listOf(m),
            mapOf("m" to listOf(tool("a", "m"), tool("b", "m", "running")))
        )
        val fold = (blocks.single() as RenderBlock.Fold).asFoldedToolRun()

        assertFalse(isCrossMessageFoldExpanded(fold, emptyMap()))
        assertTrue(isCrossMessageFoldExpanded(fold, mapOf("fold|a" to true)))
        assertTrue(foldIsRunning(fold))
    }

    @Test
    fun `mixed message assigns footer and error to exactly one final block`() {
        val m = message("m")
        val blocks = build(
            listOf(m),
            mapOf("m" to listOf(text("before", "m"), tool("a", "m"), text("after", "m")))
        )

        assertEquals(3, blocks.size)
        assertEquals(1, blocks.count { it.decorates("m") })
        assertTrue((blocks.last() as RenderBlock.Conversation).showMessageDecoration)
    }

    @Test
    fun `hidden-only message still gets one decoration block`() {
        val m = erroredMessage("m")
        val blocks = build(
            listOf(m),
            mapOf("m" to listOf(reasoning("live", "m"))),
            streamingReasoningPartId = "live"
        )

        val block = blocks.single() as RenderBlock.Conversation
        assertTrue(block.parts.isEmpty())
        assertTrue(block.showMessageDecoration)
    }

    @Test
    fun `hidden-only streaming reasoning decoration owner does not render in-flight loading`() {
        val m = message("m")
        val block = build(
            listOf(m),
            mapOf("m" to listOf(reasoning("live", "m"))),
            streamingReasoningPartId = "live"
        ).single() as RenderBlock.Conversation

        assertTrue(block.showMessageDecoration)
        assertFalse(shouldRenderInFlightEmpty(block, sessionIsRunning = true))
    }

    @Test
    fun `hidden classified-empty tool is an ordered seam between visible tool runs`() {
        val before = message("before")
        val hidden = message("hidden")
        val after = message("after")

        val blocks = build(
            messages = listOf(before, hidden, after),
            parts = mapOf(
                "before" to listOf(tool("a", "before")),
                "hidden" to listOf(todo("todo", "hidden")),
                "after" to listOf(tool("b", "after"))
            )
        )

        assertEquals(listOf("run|a", "conversation|hidden|decoration", "run|b"), blocks.map { it.id })
        assertTrue(blocks[0] is RenderBlock.ToolRun)
        assertTrue(blocks[1] is RenderBlock.Conversation)
        assertTrue(blocks[2] is RenderBlock.ToolRun)
        assertEquals(1, blocks.count { it.decorates("hidden") })
    }

    @Test
    fun `metadata marker with empty parts produces one empty conversation block`() {
        val marker = message("marker", role = "agent-switched")

        val markerBlock = build(
            messages = listOf(marker),
            parts = emptyMap()
        ).single() as RenderBlock.Conversation

        assertEquals("conversation|marker|empty", markerBlock.id)
        assertTrue(markerBlock.parts.isEmpty())
        assertTrue(markerBlock.showMessageDecoration)
        assertFalse(markerBlock.isDecorationOnly)
    }

    @Test
    fun `assistant with empty parts produces one empty conversation block`() {
        val emptyAssistant = message("empty-assistant")

        val emptyAssistantBlock = build(
            messages = listOf(emptyAssistant),
            parts = emptyMap()
        ).single() as RenderBlock.Conversation

        assertEquals("conversation|empty-assistant|empty", emptyAssistantBlock.id)
        assertTrue(emptyAssistantBlock.parts.isEmpty())
        assertTrue(emptyAssistantBlock.showMessageDecoration)
        assertFalse(emptyAssistantBlock.isDecorationOnly)
    }

    @Test
    fun `metadata marker role forces conversation branch and seams adjacent tools`() {
        val before = message("before-marker")
        val marker = message("marker-with-parts", role = "agent-switched")
        val after = message("after-marker")

        val blocks = build(
            messages = listOf(before, marker, after),
            parts = mapOf(
                before.id to listOf(tool("before-tool", before.id)),
                marker.id to listOf(text("marker-label", marker.id)),
                after.id to listOf(tool("after-tool", after.id))
            )
        )

        assertEquals(
            listOf("run|before-tool", "conversation|marker-with-parts|empty", "run|after-tool"),
            blocks.map { it.id }
        )
        val markerBlock = blocks[1] as RenderBlock.Conversation
        assertEquals(listOf("marker-label"), markerBlock.parts.map { it.id })
        assertTrue(markerBlock.showMessageDecoration)
    }

    @Test
    fun `builder branch matrix preserves empty context patch and reasoning behavior`() {
        assertTrue(build(messages = emptyList(), parts = emptyMap()).isEmpty())

        val contextMessage = message("context")
        val contextFold = build(
            messages = listOf(contextMessage),
            parts = mapOf(contextMessage.id to listOf(
                contextTool("read-a", contextMessage.id),
                contextTool("read-b", contextMessage.id)
            ))
        ).single() as RenderBlock.Fold
        assertEquals("run|read-a", contextFold.id)
        assertEquals(2, (contextFold.items.single().item as ToolRenderItem.ContextGroup).parts.size)

        val patchMessage = message("patch")
        val patchRun = build(
            messages = listOf(patchMessage),
            parts = mapOf(patchMessage.id to listOf(patch("patch-a", patchMessage.id)))
        ).single() as RenderBlock.ToolRun
        assertTrue(patchRun.items.single().item is ToolRenderItem.WritePatch)

        val reasoningMessage = message("reasoning")
        val reasoningRun = build(
            messages = listOf(reasoningMessage),
            parts = mapOf(reasoningMessage.id to listOf(reasoning("thought", reasoningMessage.id)))
        ).single() as RenderBlock.ToolRun
        assertTrue(reasoningRun.items.single().item is ToolRenderItem.ThinkingPart)
    }

    @Test
    fun `builder distinguishes live hidden and stale visible questions`() {
        val liveMessage = message("live-question")
        val liveBlock = build(
            messages = listOf(liveMessage),
            parts = mapOf(liveMessage.id to listOf(question("question-live", liveMessage.id)))
        ).single() as RenderBlock.Conversation
        assertTrue(liveBlock.isDecorationOnly)
        assertTrue(liveBlock.showMessageDecoration)

        val staleMessage = message("stale-question")
        val staleBlock = build(
            messages = listOf(staleMessage),
            parts = mapOf(staleMessage.id to listOf(question("question-stale", staleMessage.id))),
            staleQuestionPartKeys = setOf("question-stale")
        ).single() as RenderBlock.ToolRun
        assertTrue(staleBlock.items.single().item is ToolRenderItem.Basic)
    }

    @Test
    fun `empty loading decision covers ordinary and excluded conversation states`() {
        fun conversation(message: Message, parts: List<Part> = emptyList()) = RenderBlock.Conversation(
            message = message,
            parts = parts,
            id = "conversation|${message.id}|test"
        )

        assertTrue(shouldRenderInFlightEmpty(conversation(message("assistant")), sessionIsRunning = true))
        assertFalse(shouldRenderInFlightEmpty(conversation(message("stopped")), sessionIsRunning = false))
        assertFalse(shouldRenderInFlightEmpty(conversation(message("user", "user")), sessionIsRunning = true))
        assertFalse(shouldRenderInFlightEmpty(
            conversation(message("with-parts"), listOf(text("visible", "with-parts"))),
            sessionIsRunning = true
        ))
        assertFalse(shouldRenderInFlightEmpty(conversation(erroredMessage("error")), sessionIsRunning = true))
    }

    @Test
    fun `running empty assistant shell does not sever a pending cross-message tool run`() {
        val before = message("before")
        val shell1 = message("shell-1")
        val shell2 = message("shell-2")
        val after = message("after")

        val blocks = build(
            messages = listOf(before, shell1, shell2, after),
            parts = mapOf(
                before.id to listOf(tool("a", before.id)),
                after.id to listOf(tool("b", after.id))
            ),
            sessionIsRunning = true
        )

        val fold = blocks.single { it is RenderBlock.Fold } as RenderBlock.Fold
        assertEquals(listOf("a", "b"), fold.items.map { firstPartId(it.item) })
        val shellBlocks = blocks.filterIsInstance<RenderBlock.Conversation>()
        assertEquals(listOf("conversation|shell-1|empty", "conversation|shell-2|empty"), shellBlocks.map { it.id })
        assertTrue(shellBlocks.all { it.showMessageDecoration })
        assertTrue(shellBlocks.none { it.isDecorationOnly })
    }

    @Test
    fun `render block top padding is compact except for user turn starts`() {
        val assistant = RenderBlock.Conversation(
            message = message("assistant"),
            parts = emptyList(),
            id = "assistant"
        )
        val user = RenderBlock.Conversation(
            message = message("user", role = "user"),
            parts = emptyList(),
            id = "user"
        )
        val toolRun = RenderBlock.ToolRun(
            items = listOf(MessageToolRenderItem(message("tool-message"), ToolRenderItem.Basic(tool("tool", "tool-message")))),
            firstPartId = "tool"
        )

        assertEquals(0, renderBlockTopPaddingDp(user, index = 0))
        assertEquals(16, renderBlockTopPaddingDp(user, index = 1))
        assertEquals(4, renderBlockTopPaddingDp(assistant, index = 1))
        assertEquals(4, renderBlockTopPaddingDp(toolRun, index = 1))
    }

    @Test
    fun `empty shell seam matrix preserves user metadata and idle boundaries`() {
        fun ids(role: String, running: Boolean): List<String> {
            val before = message("before-$role-$running")
            val empty = message("empty-$role-$running", role)
            val after = message("after-$role-$running")
            return build(
                messages = listOf(before, empty, after),
                parts = mapOf(
                    before.id to listOf(tool("a-$role-$running", before.id)),
                    after.id to listOf(tool("b-$role-$running", after.id))
                ),
                sessionIsRunning = running
            ).map { it.id }
        }

        assertEquals(
            listOf("run|a-assistant-false", "conversation|empty-assistant-false|empty", "run|b-assistant-false"),
            ids(role = "assistant", running = false)
        )
        assertEquals(
            listOf("run|a-user-true", "conversation|empty-user-true|empty", "run|b-user-true"),
            ids(role = "user", running = true)
        )
        assertEquals(
            listOf("run|a-user-false", "conversation|empty-user-false|empty", "run|b-user-false"),
            ids(role = "user", running = false)
        )
        assertEquals(
            listOf("run|a-agent-switched-true", "conversation|empty-agent-switched-true|empty", "run|b-agent-switched-true"),
            ids(role = "agent-switched", running = true)
        )
    }

    @Test
    fun `streaming reasoning id on a non-reasoning part does not sever tools around it`() {
        // buildRenderBlocks line 264 guards the standalone-streaming seam with
        // `part.id == streamingReasoningPartId && part.isReasoning`. A part whose
        // id collides with the streaming id but is NOT reasoning must NOT be
        // treated as a live-thinking seam (it falls through to prose/tools).
        val m = message("m")
        val blocks = build(
            listOf(m),
            mapOf("m" to listOf(tool("a", "m"), text("live", "m"), tool("b", "m"))),
            streamingReasoningPartId = "live"
        )

        // Without the `&& part.isReasoning` guard the text part would be dropped
        // as a hidden seam; with it, the prose survives and splits the run.
        assertEquals(listOf("run|a", "conversation|m|live", "run|b"), blocks.map { it.id })
    }

    // ── §fold-fix #3 (step-marker root cause) ──────────────────────────────────
    // Each real opencode tool call is wrapped in [step-start, …, step-finish].
    // `step-start`/`step-finish` (Part.type == "step-start"/"step-finish") are
    // NOT tool/patch/reasoning, so pre-fix they hit the prose `else` branch,
    // were pushed into conversationParts, and triggered
    // flushConversation()->flushFold() between EVERY tool — severing the
    // cross-message fold. The fix adds `else if (part.isStepStart ||
    // part.isStepFinish) -> index++` so they are skipped. Every test below is
    // RED without that branch and GREEN with it.

    @Test
    fun `step markers do not sever a read tool and patch within one message`() {
        val m = message("m")
        val blocks = build(
            listOf(m),
            mapOf("m" to listOf(
                stepStart("ss", "m"),
                contextTool("read", "m"),
                stepFinish("sf", "m"),
                patch("p", "m")
            ))
        )

        // The read tool and the patch land in ONE Fold (partCount 2) instead of
        // being severed into two single-tool ToolRuns by the step markers.
        val fold = blocks.single() as RenderBlock.Fold
        assertEquals(listOf("read", "p"), fold.items.map { firstPartId(it.item) })
        assertTrue(blocks.none { it is RenderBlock.Conversation })
    }

    @Test
    fun `real opencode step sequence keeps a cross-message fold intact across messages`() {
        // Per-message shape captured in the emulator DebugLog:
        // [step-start, reasoning, text, tool/bash, step-finish]. The trailing
        // step-finish must NOT flush the pending fold at message end, so m1's
        // bash survives the m1→m2 boundary and folds with m2's first tool-like
        // part (its leading reasoning). (Text remains a real conversation seam
        // — it severs the in-message run, which is intended builder semantics.)
        val m1 = message("m1")
        val m2 = message("m2")
        val blocks = build(
            listOf(m1, m2),
            mapOf(
                "m1" to listOf(
                    stepStart("ss1", "m1"), reasoning("r1", "m1"), text("t1", "m1"),
                    tool("b1", "m1"), stepFinish("sf1", "m1")
                ),
                "m2" to listOf(
                    stepStart("ss2", "m2"), reasoning("r2", "m2"), text("t2", "m2"),
                    tool("b2", "m2"), stepFinish("sf2", "m2")
                )
            )
        )

        val fold = blocks.single { it is RenderBlock.Fold } as RenderBlock.Fold
        // m1's bash (b1) carried across the boundary because step-finish no
        // longer flushes; it folds with m2's leading reasoning (r2).
        assertEquals(listOf("b1", "r2"), fold.items.map { firstPartId(it.item) })
        assertEquals(setOf("m1", "m2"), fold.items.map { it.message.id }.toSet())
        // No step marker survives as standalone conversation content.
        assertTrue(blocks.none {
            it is RenderBlock.Conversation && it.parts.any { p -> p.isStepStart || p.isStepFinish }
        })
    }

    @Test
    fun `step markers between consecutive single-tool turns fold into one cross-message fold`() {
        // Minimal real step sequence per message: [step-start, tool/bash, step-finish].
        // Here the two bash tools across messages DO fold into one cross-message
        // Fold (partCount 2) — the literal "two tools, one fold" property that
        // the step-marker skip guarantees when no prose intervenes.
        val m1 = message("m1")
        val m2 = message("m2")
        val blocks = build(
            listOf(m1, m2),
            mapOf(
                "m1" to listOf(stepStart("ss1", "m1"), tool("b1", "m1"), stepFinish("sf1", "m1")),
                "m2" to listOf(stepStart("ss2", "m2"), tool("b2", "m2"), stepFinish("sf2", "m2"))
            )
        )

        val fold = blocks.single { it is RenderBlock.Fold } as RenderBlock.Fold
        assertEquals(listOf("b1", "b2"), fold.items.map { firstPartId(it.item) })
        assertEquals(listOf("m1", "m2"), fold.items.map { it.message.id })
        assertTrue(blocks.none { it is RenderBlock.Conversation })
    }

    @Test
    fun `step start and finish parts are skipped and never become conversation content`() {
        val m = message("m")
        val blocks = build(
            listOf(m),
            mapOf("m" to listOf(
                stepStart("ss", "m"), tool("b", "m"), stepFinish("sf", "m")
            ))
        )

        // The tool survives (here a single bash → ToolRun); the step markers are
        // dropped entirely and never appear inside any Conversation block.
        val toolRun = blocks.single() as RenderBlock.ToolRun
        assertEquals(listOf("b"), toolRun.items.map { firstPartId(it.item) })
        assertTrue(blocks.none { it is RenderBlock.Conversation })
    }

    private fun RenderBlock.decorates(messageId: String): Boolean = when (this) {
        is RenderBlock.Conversation -> message.id == messageId && showMessageDecoration
        is RenderBlock.ToolRun -> messageDecorations.any { it.id == messageId }
        is RenderBlock.Fold -> messageDecorations.any { it.id == messageId }
    }
}
