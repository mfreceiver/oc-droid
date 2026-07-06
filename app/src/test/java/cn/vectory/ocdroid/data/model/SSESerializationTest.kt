package cn.vectory.ocdroid.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round-trip + accessor coverage for [SSEEvent] and [SSEPayload]. Pure
 * kotlinx.serialization.
 */
class SSESerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    // ── SSEEvent ──────────────────────────────────────────────────────────

    @Test
    fun `SSEEvent round trip with directory and payload`() {
        val event = SSEEvent(
            directory = "/home/user/project",
            payload = SSEPayload(type = "session.created")
        )
        val encoded = json.encodeToString(event)
        val decoded = json.decodeFromString<SSEEvent>(encoded)
        assertEquals(event, decoded)
    }

    @Test
    fun `SSEEvent minimal defaults directory to null`() {
        val decoded = json.decodeFromString<SSEEvent>(
            """{"payload":{"type":"x"}}"""
        )
        assertNull(decoded.directory)
        assertEquals("x", decoded.payload.type)
    }

    @Test
    fun `SSEEvent parses server JSON`() {
        val decoded = json.decodeFromString<SSEEvent>(
            """{"directory":"/p","payload":{"type":"message.updated","properties":{"id":"m1"}}}"""
        )
        assertEquals("/p", decoded.directory)
        assertEquals("message.updated", decoded.payload.type)
        assertEquals("m1", decoded.payload.getString("id"))
    }

    // ── SSEPayload.getString / getJsonObject / getAs ──────────────────────

    @Test
    fun `SSEPayload getString returns null when properties null`() {
        val payload = SSEPayload(type = "x")
        assertNull(payload.getString("anything"))
        assertNull(payload.getJsonObject("anything"))
    }

    @Test
    fun `SSEPayload getString returns null for missing key`() {
        val payload = SSEPayload(
            type = "x",
            properties = buildJsonObject { put("a", "b") }
        )
        assertNull(payload.getString("missing"))
    }

    @Test
    fun `SSEPayload getString returns content of JsonPrimitive`() {
        val payload = SSEPayload(
            type = "x",
            properties = buildJsonObject { put("k", JsonPrimitive("v")) }
        )
        assertEquals("v", payload.getString("k"))
    }

    @Test
    fun `SSEPayload getString returns null when value is not a primitive`() {
        val payload = SSEPayload(
            type = "x",
            properties = buildJsonObject { put("obj", buildJsonObject { put("inner", "v") }) }
        )
        assertNull(payload.getString("obj"))
    }

    @Test
    fun `SSEPayload getJsonObject returns object value`() {
        val inner = buildJsonObject { put("k", "v") }
        val payload = SSEPayload(
            type = "x",
            properties = buildJsonObject { put("data", inner) }
        )
        val result = payload.getJsonObject("data")
        assertNotNull(result)
        assertEquals("v", (result!!["k"] as JsonPrimitive).content)
    }

    @Test
    fun `SSEPayload getJsonObject returns null for non-object value`() {
        val payload = SSEPayload(
            type = "x",
            properties = buildJsonObject { put("k", "string") }
        )
        assertNull(payload.getJsonObject("k"))
    }

    @Test
    fun `SSEPayload getAs parses a sub-object via supplied parser`() {
        // Simulate parsing a typed object out of properties via getAs<T>.
        val payload = SSEPayload(
            type = "x",
            properties = buildJsonObject {
                put("entry", buildJsonObject {
                    put("path", "/a/b")
                    put("type", "modified")
                })
            }
        )
        val parsed: Pair<String, String>? = payload.getAs("entry") { obj ->
            val path = (obj["path"] as? JsonPrimitive)?.content
            val type = (obj["type"] as? JsonPrimitive)?.content
            if (path != null && type != null) path to type else null
        }
        assertNotNull(parsed)
        assertEquals("/a/b", parsed!!.first)
        assertEquals("modified", parsed.second)
    }

    @Test
    fun `SSEPayload getAs returns null when parser returns null`() {
        val payload = SSEPayload(
            type = "x",
            properties = buildJsonObject { put("entry", buildJsonObject {}) }
        )
        val parsed: String? = payload.getAs("entry") { _ -> null }
        assertNull(parsed)
    }

    @Test
    fun `SSEPayload getAs returns null when key missing`() {
        val payload = SSEPayload(type = "x")
        val parsed: String? = payload.getAs("missing") { _ -> "should-not-call" }
        assertNull(parsed)
    }

    // ── SSEPayload round trip with properties ─────────────────────────────

    @Test
    fun `SSEPayload round trip preserves properties object`() {
        val payload = SSEPayload(
            type = "x",
            properties = buildJsonObject {
                put("id", "abc")
                put("count", JsonPrimitive(42))
            }
        )
        val encoded = json.encodeToString(payload)
        val decoded = json.decodeFromString<SSEPayload>(encoded)
        assertEquals(payload.type, decoded.type)
        assertEquals("abc", decoded.getString("id"))
    }
}
