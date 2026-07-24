package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageFilterTest {

    private val emptyPartsMap: Map<String, List<Part>> = emptyMap()
    private val emptyStreamingKeys: Set<String> = emptySet()

    @Test
    fun `user messages are never filtered out`() {
        val userMsg = Message(
            id = "u1",
            role = "user",
        )
        assertFalse(
            isMessageFilteredOut(
                msg = userMsg,
                partsByMessage = emptyPartsMap,
                streamingPartTextKeys = emptyStreamingKeys,
                streamingReasoningPart = null,
                sessionIsRunning = false,
            )
        )
    }

    @Test
    fun `nonUser nonStreaming empty message without error is filtered out`() {
        val assistantMsg = Message(
            id = "a1",
            role = "assistant",
        )
        assertTrue(
            isMessageFilteredOut(
                msg = assistantMsg,
                partsByMessage = emptyPartsMap,
                streamingPartTextKeys = emptyStreamingKeys,
                streamingReasoningPart = null,
                sessionIsRunning = false,
            )
        )
    }

    @Test
    fun `nonUser message with error is NOT filtered out`() {
        val assistantMsg = Message(
            id = "a2",
            role = "assistant",
            error = Message.MessageError(data = buildJsonObject { put("message", JsonPrimitive("some error")) }),
        )
        assertFalse(
            isMessageFilteredOut(
                msg = assistantMsg,
                partsByMessage = emptyPartsMap,
                streamingPartTextKeys = emptyStreamingKeys,
                streamingReasoningPart = null,
                sessionIsRunning = false,
            )
        )
    }

    @Test
    fun `nonUser streaming message with parts is NOT filtered out`() {
        val msgId = "a3"
        val part = Part(
            id = "p1",
            messageId = msgId,
            type = "text",
            text = "hello",
        )
        val partsMap = mapOf(msgId to listOf(part))
        val assistantMsg = Message(
            id = msgId,
            role = "assistant",
        )
        assertFalse(
            isMessageFilteredOut(
                msg = assistantMsg,
                partsByMessage = partsMap,
                streamingPartTextKeys = setOf("p1"),
                streamingReasoningPart = null,
                sessionIsRunning = true,
            )
        )
    }

    @Test
    fun `computeFilteredReversedMessages reverses and filters`() {
        val msgs = listOf(
            Message(id = "u1", role = "user"),
            Message(id = "a1", role = "assistant"),
            Message(id = "u2", role = "user"),
            Message(id = "a2", role = "assistant", error = Message.MessageError(data = buildJsonObject { put("message", JsonPrimitive("err")) })),
        )
        val result = computeFilteredReversedMessages(
            messages = msgs,
            partsByMessage = emptyPartsMap,
            streamingPartTextKeys = emptyStreamingKeys,
            streamingReasoningPart = null,
            sessionIsRunning = false,
        )
        // reversed: a2, u2, a1, u1
        // filtered: a1 (no parts, no error, non-user) -> removed
        // so result: a2, u2, u1
        assertTrue(result.size == 3)
        assertTrue(result[0].id == "a2")
        assertTrue(result[1].id == "u2")
        assertTrue(result[2].id == "u1")
    }
}
