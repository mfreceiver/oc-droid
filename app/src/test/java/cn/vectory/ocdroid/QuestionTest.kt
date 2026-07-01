package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.ui.parseQuestionAskedEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QuestionTest {

    private fun makeEvent(jsonStr: String): SSEEvent {
        val properties = Json.parseToJsonElement(jsonStr).jsonObject
        return SSEEvent(payload = SSEPayload(type = "question.asked", properties = properties))
    }

    @Test
    fun `test parseQuestionAskedEvent with valid JSON`() {
        val event = makeEvent("""
            {
                "id": "test-question-1",
                "sessionID": "test-session",
                "questions": [
                    {
                        "question": "What framework do you use?",
                        "header": "Framework Choice",
                        "options": [
                            {
                                "label": "React",
                                "description": "Popular UI library"
                            },
                            {
                                "label": "Vue",
                                "description": "Progressive framework"
                            }
                        ],
                        "multiple": false,
                        "custom": true
                    }
                ]
            }
        """.trimIndent())

        val result = parseQuestionAskedEvent(event)

        assertNotNull(result)
        assertEquals("test-question-1", result!!.id)
        assertEquals("test-session", result.sessionId)
        assertEquals(1, result.questions.size)
        assertEquals("What framework do you use?", result.questions[0].question)
        assertEquals("Framework Choice", result.questions[0].header)
        assertEquals(2, result.questions[0].options.size)
        assertEquals("React", result.questions[0].options[0].label)
    }

    @Test
    fun `test parseQuestionAskedEvent with empty questions`() {
        val event = makeEvent("""
            {
                "id": "test-question-2",
                "sessionID": "test-session",
                "questions": []
            }
        """.trimIndent())

        val result = parseQuestionAskedEvent(event)

        assertNotNull(result)
        assertEquals(0, result!!.questions.size)
    }

    @Test
    fun `test parseQuestionAskedEvent with allowMultiple flag`() {
        val event = makeEvent("""
            {
                "id": "test-question-3",
                "sessionID": "test-session",
                "questions": [
                    {
                        "question": "Select all that apply",
                        "header": "Multi-select",
                        "options": [
                            { "label": "A", "description": "Option A" },
                            { "label": "B", "description": "Option B" }
                        ],
                        "multiple": true,
                        "custom": false
                    }
                ]
            }
        """.trimIndent())

        val result = parseQuestionAskedEvent(event)

        assertNotNull(result)
        assertEquals(true, result!!.questions[0].allowMultiple)
        assertEquals(false, result.questions[0].allowCustom)
    }

    @Test
    fun `test parseQuestionAskedEvent missing properties returns null`() {
        val event = SSEEvent(payload = SSEPayload(type = "question.asked", properties = null))
        val result = parseQuestionAskedEvent(event)
        assertNull(result)
    }

    @Test
    fun `test parseQuestionAskedEvent with invalid JSON structure returns null`() {
        // Missing required "id" field — should fail deserialization
        val event = makeEvent("""{ "sessionID": "s", "questions": [] }""")
        val result = parseQuestionAskedEvent(event)
        // id is required (non-nullable), so this should return null
        assertNull(result)
    }
}
