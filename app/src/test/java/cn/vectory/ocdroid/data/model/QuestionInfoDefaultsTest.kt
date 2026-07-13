package cn.vectory.ocdroid.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §task7-coverage: JVM unit tests for [QuestionInfo]'s nullable-field getters
 * ([allowMultiple] / [allowCustom]) and [QuestionRequest]'s serialization
 * with an optional [QuestionRequest.ToolRef]. These branches were never
 * exercised — every prior test used defaults (non-null custom/multiple,
 * null tool). Driving them with explicit nulls / non-null tool covers the
 * serializer + getter fallback branches.
 *
 * Directly related to the unread-lifecycle work: [QuestionInfo] is the model
 * behind `pendingQuestions`, central to Task 6's question-tree aggregation.
 */
class QuestionInfoDefaultsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `allowCustom returns true when custom is null`() {
        val info = QuestionInfo(
            question = "q", header = "h", options = emptyList(),
            custom = null,
        )
        assertTrue(info.allowCustom)
    }

    @Test
    fun `allowCustom returns the explicit value when non-null`() {
        val info = QuestionInfo(
            question = "q", header = "h", options = emptyList(),
            custom = false,
        )
        assertFalse(info.allowCustom)
    }

    @Test
    fun `allowMultiple returns false when multiple is null`() {
        val info = QuestionInfo(
            question = "q", header = "h", options = emptyList(),
            multiple = null,
        )
        assertFalse(info.allowMultiple)
    }

    @Test
    fun `allowMultiple returns the explicit value when non-null`() {
        val info = QuestionInfo(
            question = "q", header = "h", options = emptyList(),
            multiple = true,
        )
        assertTrue(info.allowMultiple)
    }

    @Test
    fun `QuestionRequest with non-null tool round-trips through serialization`() {
        val request = QuestionRequest(
            id = "q1",
            sessionId = "s1",
            questions = emptyList(),
            tool = QuestionRequest.ToolRef(messageId = "m1", callId = "c1"),
        )
        val encoded = json.encodeToString(QuestionRequest.serializer(), request)
        val decoded = json.decodeFromString(QuestionRequest.serializer(), encoded)
        assertEquals(request, decoded)
        assertEquals("m1", decoded.tool!!.messageId)
        assertEquals("c1", decoded.tool!!.callId)
    }

    @Test
    fun `QuestionInfo with null optional fields serializes without crashing`() {
        val info = QuestionInfo(
            question = "q", header = "h", options = emptyList(),
            multiple = null, custom = null,
        )
        val encoded = json.encodeToString(QuestionInfo.serializer(), info)
        val decoded = json.decodeFromString(QuestionInfo.serializer(), encoded)
        assertEquals(info, decoded)
    }
}

