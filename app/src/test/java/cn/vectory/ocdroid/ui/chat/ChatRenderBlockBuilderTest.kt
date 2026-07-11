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

    private fun build(
        entries: List<Entry>,
        parts: Map<String, List<Part>>,
        streamingReasoningPartId: String? = null,
        staleQuestionPartKeys: Set<String> = emptySet()
    ) = buildRenderBlocks(
        entries = entries,
        partsByMessage = parts,
        streamingPartTexts = emptyMap(),
        staleQuestionPartKeys = staleQuestionPartKeys,
        streamingReasoningPartId = streamingReasoningPartId
    )

    @Test
    fun `tools in adjacent messages form one cross-message fold`() {
        val m1 = message("m1")
        val m2 = message("m2")
        val blocks = build(
            listOf(Entry.Message(m1), Entry.Message(m2)),
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

        val block = build(listOf(Entry.Message(m)), mapOf("m" to listOf(tool("a", "m")))).single()

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
            listOf(Entry.Message(m1), Entry.Message(user), Entry.Message(m2)),
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
            listOf(Entry.Message(m)),
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
            listOf(Entry.Message(m)),
            mapOf("m" to listOf(tool("a", "m"), task("task", "m"), tool("b", "m")))
        )

        assertEquals(3, blocks.size)
        assertEquals(listOf("task"), (blocks[1] as RenderBlock.Conversation).parts.map { it.id })
    }

    @Test
    fun `direct tool run keeps top-level id when it becomes a fold`() {
        val m = message("m")
        val before = build(listOf(Entry.Message(m)), mapOf("m" to listOf(tool("a", "m"))))
        val after = build(listOf(Entry.Message(m)), mapOf("m" to listOf(tool("a", "m"), tool("b", "m"))))

        assertEquals("run|a", before.single().id)
        assertEquals(before.single().id, after.single().id)
    }

    @Test
    fun `streaming reasoning is excluded without joining tools across it`() {
        val m = message("m")
        val blocks = build(
            listOf(Entry.Message(m)),
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
            listOf(Entry.Message(m)),
            mapOf("m" to listOf(tool("a", "m"), tool("b", "m", "running")))
        )
        val fold = (blocks.single() as RenderBlock.Fold).asFoldedToolRun()

        assertFalse(isCrossMessageFoldExpanded(fold, emptyMap()))
        assertTrue(isCrossMessageFoldExpanded(fold, mapOf("fold|a" to true)))
        assertTrue(foldIsRunning(fold))
    }

    @Test
    fun `gap marker stays interleaved and breaks a fold`() {
        val m1 = message("m1")
        val m2 = message("m2")
        val blocks = build(
            listOf(
                Entry.Message(m1),
                Entry.GapMarker("g", GapFillState.Idle),
                Entry.Message(m2)
            ),
            mapOf("m1" to listOf(tool("a", "m1")), "m2" to listOf(tool("b", "m2")))
        )

        assertEquals(listOf("run|a", "gap-g", "run|b"), blocks.map { it.id })
        assertTrue(blocks[1] is RenderBlock.Gap)
    }

    @Test
    fun `mixed message assigns footer and error to exactly one final block`() {
        val m = message("m")
        val blocks = build(
            listOf(Entry.Message(m)),
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
            listOf(Entry.Message(m)),
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
            listOf(Entry.Message(m)),
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
            entries = listOf(Entry.Message(before), Entry.Message(hidden), Entry.Message(after)),
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
            entries = listOf(Entry.Message(marker)),
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
            entries = listOf(Entry.Message(emptyAssistant)),
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
            entries = listOf(Entry.Message(before), Entry.Message(marker), Entry.Message(after)),
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
        assertTrue(build(entries = emptyList(), parts = emptyMap()).isEmpty())

        val contextMessage = message("context")
        val contextFold = build(
            entries = listOf(Entry.Message(contextMessage)),
            parts = mapOf(contextMessage.id to listOf(
                contextTool("read-a", contextMessage.id),
                contextTool("read-b", contextMessage.id)
            ))
        ).single() as RenderBlock.Fold
        assertEquals("run|read-a", contextFold.id)
        assertEquals(2, (contextFold.items.single().item as ToolRenderItem.ContextGroup).parts.size)

        val patchMessage = message("patch")
        val patchRun = build(
            entries = listOf(Entry.Message(patchMessage)),
            parts = mapOf(patchMessage.id to listOf(patch("patch-a", patchMessage.id)))
        ).single() as RenderBlock.ToolRun
        assertTrue(patchRun.items.single().item is ToolRenderItem.WritePatch)

        val reasoningMessage = message("reasoning")
        val reasoningRun = build(
            entries = listOf(Entry.Message(reasoningMessage)),
            parts = mapOf(reasoningMessage.id to listOf(reasoning("thought", reasoningMessage.id)))
        ).single() as RenderBlock.ToolRun
        assertTrue(reasoningRun.items.single().item is ToolRenderItem.ThinkingPart)
    }

    @Test
    fun `builder distinguishes live hidden and stale visible questions`() {
        val liveMessage = message("live-question")
        val liveBlock = build(
            entries = listOf(Entry.Message(liveMessage)),
            parts = mapOf(liveMessage.id to listOf(question("question-live", liveMessage.id)))
        ).single() as RenderBlock.Conversation
        assertTrue(liveBlock.isDecorationOnly)
        assertTrue(liveBlock.showMessageDecoration)

        val staleMessage = message("stale-question")
        val staleBlock = build(
            entries = listOf(Entry.Message(staleMessage)),
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

    private fun RenderBlock.decorates(messageId: String): Boolean = when (this) {
        is RenderBlock.Conversation -> message.id == messageId && showMessageDecoration
        is RenderBlock.ToolRun -> messageDecorations.any { it.id == messageId }
        is RenderBlock.Fold -> messageDecorations.any { it.id == messageId }
        is RenderBlock.Gap -> false
    }
}
