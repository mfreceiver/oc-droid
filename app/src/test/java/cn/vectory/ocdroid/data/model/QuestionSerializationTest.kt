package cn.vectory.ocdroid.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip + computed-property coverage for [QuestionOption], [QuestionInfo]
 * and [QuestionRequest] (+ nested [QuestionRequest.ToolRef]). Pure
 * kotlinx.serialization.
 */
class QuestionSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // ── QuestionOption ────────────────────────────────────────────────────

    @Test
    fun `QuestionOption round trip`() {
        val option = QuestionOption(label = "Yes", description = "Confirm")
        val encoded = json.encodeToString(option)
        val decoded = json.decodeFromString<QuestionOption>(encoded)
        assertEquals(option, decoded)
    }

    @Test
    fun `QuestionOption parses server JSON`() {
        val decoded = json.decodeFromString<QuestionOption>(
            """{"label":"Refactor","description":"Apply changes"}"""
        )
        assertEquals("Refactor", decoded.label)
        assertEquals("Apply changes", decoded.description)
    }

    // ── QuestionInfo ──────────────────────────────────────────────────────

    @Test
    fun `QuestionInfo round trip with options`() {
        val info = QuestionInfo(
            question = "Which?",
            header = "Choice",
            options = listOf(
                QuestionOption("A", "first"),
                QuestionOption("B", "second")
            ),
            multiple = true,
            custom = false
        )
        val encoded = json.encodeToString(info)
        val decoded = json.decodeFromString<QuestionInfo>(encoded)
        assertEquals(info.question, decoded.question)
        assertEquals(info.header, decoded.header)
        assertEquals(2, decoded.options.size)
        assertEquals("A", decoded.options[0].label)
    }

    @Test
    fun `QuestionInfo allowMultiple false by default and when set`() {
        // default multiple=null, custom=null → allowMultiple=false, allowCustom=true
        val def = QuestionInfo(question = "q", header = "h", options = emptyList())
        assertFalse(def.allowMultiple)
        assertTrue(def.allowCustom)
        // explicit multiple=true / custom=false
        val explicit = QuestionInfo(question = "q", header = "h", options = emptyList(), multiple = true, custom = false)
        assertTrue(explicit.allowMultiple)
        assertFalse(explicit.allowCustom)
    }

    @Test
    fun `QuestionInfo parses server JSON without multiple or custom`() {
        // The data class declares `multiple: Boolean? = false` and `custom: Boolean? = true`
        // — non-null defaults. So when JSON omits these keys, kotlinx.serialization
        // fills in the defaults (false / true) rather than null. The nullable type
        // is only surfaced when the server explicitly sends `"multiple": null`.
        val decoded = json.decodeFromString<QuestionInfo>(
            """{"question":"Pick one","header":"H","options":[{"label":"A","description":"d"}]}"""
        )
        assertEquals("Pick one", decoded.question)
        assertEquals(1, decoded.options.size)
        assertEquals(false, decoded.multiple)
        assertEquals(true, decoded.custom)
        assertFalse(decoded.allowMultiple)
        assertTrue(decoded.allowCustom)
    }

    @Test
    fun `QuestionInfo parses server JSON with explicit null multiple`() {
        val decoded = json.decodeFromString<QuestionInfo>(
            """{"question":"Pick one","header":"H","options":[],"multiple":null}"""
        )
        // Explicit null is preserved (not coerced to default), so allowMultiple=false.
        assertNull(decoded.multiple)
        assertFalse(decoded.allowMultiple)
    }

    // ── QuestionRequest ───────────────────────────────────────────────────

    @Test
    fun `QuestionRequest round trip with tool`() {
        val req = QuestionRequest(
            id = "q1",
            sessionId = "s1",
            questions = listOf(QuestionInfo("q", "h", emptyList())),
            tool = QuestionRequest.ToolRef(messageId = "m1", callId = "c1")
        )
        val encoded = json.encodeToString(req)
        assertTrue(encoded.contains("\"sessionID\":\"s1\""))
        assertTrue(encoded.contains("\"messageID\":\"m1\""))
        assertTrue(encoded.contains("\"callID\":\"c1\""))
        val decoded = json.decodeFromString<QuestionRequest>(encoded)
        assertEquals(req, decoded)
    }

    @Test
    fun `QuestionRequest minimal defaults tool to null`() {
        val decoded = json.decodeFromString<QuestionRequest>(
            """{"id":"q1","sessionID":"s1","questions":[]}"""
        )
        assertEquals("q1", decoded.id)
        assertEquals("s1", decoded.sessionId)
        assertEquals(0, decoded.questions.size)
        assertNull(decoded.tool)
        assertNull("routeToken defaults null for legacy payloads", decoded.routeToken)
    }

    @Test
    fun `QuestionRequest decodes routeToken from slim payload`() {
        val decoded = json.decodeFromString<QuestionRequest>(
            """{"id":"q1","sessionID":"s1","questions":[],"routeToken":"rt-1"}"""
        )
        assertEquals("rt-1", decoded.routeToken)
    }

    @Test
    fun `QuestionRequest decodes from server JSON shape`() {
        val decoded = json.decodeFromString<QuestionRequest>(
            """
            {"id":"q1","sessionID":"s1","questions":[
              {"question":"Which file?","header":"Pick","options":[
                {"label":"a.kt","description":""},
                {"label":"b.kt","description":""}
              ]}
            ]}
            """.trimIndent()
        )
        assertEquals(1, decoded.questions.size)
        assertEquals("Which file?", decoded.questions[0].question)
        assertEquals(2, decoded.questions[0].options.size)
    }
}
