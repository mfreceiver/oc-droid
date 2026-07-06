package cn.vectory.ocdroid.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R18 Phase 5++ coverage: the [CommandHints] data class (legacy hints shape,
 * kept for schema completeness). Coverage gap before this file: 0/2 classes,
 * 0/3 methods, 0/34 branches, 0/6 lines, 0/211 instructions.
 *
 * The class itself is only constructed by the JSON decoder under the legacy
 * server shape; today's server emits hints as a JSON ARRAY of strings (handled
 * by [CommandInfo.hintsAsStringList]). The data class is retained so a future
 * typed view can derive from it without re-introducing a serialization
 * failure. Construction-only coverage suffices for kover line coverage of the
 * synthetic accessors.
 */
class CommandHintsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    @Test
    fun `CommandHints default constructor populates every field with null`() {
        val h = CommandHints()
        assertNull(h.actions)
        assertNull(h.arguments)
        assertNull(h.locations)
        assertNull(h.enabled)
    }

    @Test
    fun `CommandHints full constructor round-trips`() {
        val arg = buildJsonObject { put("name", JsonPrimitive("input")) }
        val h = CommandHints(
            actions = mapOf("primary" to "Run"),
            arguments = listOf(arg),
            locations = listOf("session", "global"),
            enabled = true,
        )
        assertEquals(mapOf("primary" to "Run"), h.actions)
        assertEquals(1, h.arguments!!.size)
        assertEquals("input", h.arguments!!.first()["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("session", "global"), h.locations)
        assertEquals(true, h.enabled)
    }

    @Test
    fun `CommandHints decodes from a JSON object payload`() {
        val payload = """
            {"actions":{"primary":"Run"},"arguments":[{"name":"input"}],
             "locations":["session"],"enabled":true}
        """.trimIndent()
        val h = json.decodeFromString(CommandHints.serializer(), payload)
        assertEquals(mapOf("primary" to "Run"), h.actions)
        assertEquals(1, h.arguments!!.size)
        assertEquals(listOf("session"), h.locations)
        assertEquals(true, h.enabled)
    }

    @Test
    fun `CommandHints decodes partial payload with nulls for missing keys`() {
        val payload = """{"enabled":false}"""
        val h = json.decodeFromString(CommandHints.serializer(), payload)
        assertNull(h.actions)
        assertNull(h.arguments)
        assertNull(h.locations)
        assertEquals(false, h.enabled)
    }

    @Test
    fun `CommandHints equals hashCode copy componentN`() {
        val h1 = CommandHints(
            actions = mapOf("primary" to "Run"),
            arguments = null,
            locations = listOf("session"),
            enabled = true,
        )
        val h2 = h1.copy()
        assertEquals(h1, h2)
        assertEquals(h1.hashCode(), h2.hashCode())
        assertEquals(mapOf("primary" to "Run"), h1.component1())
        assertNull(h1.component2())
        assertEquals(listOf("session"), h1.component3())
        assertEquals(true, h1.component4())
        val h3 = h1.copy(enabled = false)
        assertFalse(h3.enabled!!)
        assertTrue(h1.toString().contains("CommandHints"))
    }
}
