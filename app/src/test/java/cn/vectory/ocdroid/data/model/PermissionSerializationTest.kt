package cn.vectory.ocdroid.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip + computed-property coverage for [PermissionRequest] (+ nested
 * [PermissionRequest.Metadata], [PermissionRequest.ToolRef]) and the
 * [PermissionResponse] enum. Pure kotlinx.serialization.
 */
class PermissionSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // ── PermissionResponse enum ───────────────────────────────────────────

    @Test
    fun `PermissionResponse values map to their wire strings`() {
        assertEquals("once", PermissionResponse.ONCE.value)
        assertEquals("always", PermissionResponse.ALWAYS.value)
        assertEquals("reject", PermissionResponse.REJECT.value)
    }

    @Test
    fun `PermissionResponse enum round trips via name`() {
        // Round trip via @Serializable enum, no custom SerialName.
        val encoded = json.encodeToString(PermissionResponse.ALWAYS)
        val decoded = json.decodeFromString<PermissionResponse>(encoded)
        assertEquals(PermissionResponse.ALWAYS, decoded)
    }

    // ── PermissionRequest round trip ──────────────────────────────────────

    @Test
    fun `PermissionRequest round trip with all optional fields`() {
        val req = PermissionRequest(
            id = "p1",
            sessionId = "s1",
            permission = "edit",
            patterns = listOf("**/*.kt"),
            metadata = PermissionRequest.Metadata(filepath = "/x/a.kt", parentDir = "/x"),
            always = listOf("edit"),
            tool = PermissionRequest.ToolRef(messageId = "m1", callId = "c1")
        )
        val encoded = json.encodeToString(req)
        assertTrue(encoded.contains("\"sessionID\":\"s1\""))
        assertTrue(encoded.contains("\"parentDir\":\"/x\""))
        assertTrue(encoded.contains("\"messageID\":\"m1\""))
        assertTrue(encoded.contains("\"callID\":\"c1\""))
        val decoded = json.decodeFromString<PermissionRequest>(encoded)
        assertEquals(req, decoded)
    }

    @Test
    fun `PermissionRequest minimal defaults`() {
        val decoded = json.decodeFromString<PermissionRequest>(
            """{"id":"p1","sessionID":"s1"}"""
        )
        assertEquals("p1", decoded.id)
        assertEquals("s1", decoded.sessionId)
        assertNull(decoded.permission)
        assertNull(decoded.patterns)
        assertNull(decoded.metadata)
        assertNull(decoded.always)
        assertNull(decoded.tool)
    }

    @Test
    fun `PermissionRequest decodes server JSON with metadata and tool`() {
        val decoded = json.decodeFromString<PermissionRequest>(
            """
            {"id":"p2","sessionID":"s2","permission":"bash",
             "patterns":["*.sh"],
             "metadata":{"filepath":"/tmp/x.sh","parentDir":"/tmp"},
             "always":["bash"],
             "tool":{"messageID":"m9","callID":"c9"}}
            """.trimIndent()
        )
        assertEquals("bash", decoded.permission)
        assertEquals(listOf("*.sh"), decoded.patterns)
        assertEquals("/tmp/x.sh", decoded.metadata?.filepath)
        assertEquals("/tmp", decoded.metadata?.parentDir)
        assertEquals(listOf("bash"), decoded.always)
        assertEquals("m9", decoded.tool?.messageId)
        assertEquals("c9", decoded.tool?.callId)
    }

    @Test
    fun `PermissionRequest tolerates unknown JSON fields`() {
        val decoded = json.decodeFromString<PermissionRequest>(
            """{"id":"p","sessionID":"s","unknownKey":42,"nested":{"a":1}}"""
        )
        assertEquals("p", decoded.id)
    }

    // ── Nested Metadata and ToolRef ───────────────────────────────────────

    @Test
    fun `PermissionRequest Metadata round trip with SerialName for parentDir`() {
        val meta = PermissionRequest.Metadata(filepath = "/a/b", parentDir = "/a")
        val encoded = json.encodeToString(meta)
        assertTrue(encoded.contains("\"parentDir\":\"/a\""))
        val decoded = json.decodeFromString<PermissionRequest.Metadata>(encoded)
        assertEquals(meta, decoded)
    }

    @Test
    fun `PermissionRequest Metadata minimal defaults null`() {
        val decoded = json.decodeFromString<PermissionRequest.Metadata>("{}")
        assertNull(decoded.filepath)
        assertNull(decoded.parentDir)
    }

    @Test
    fun `PermissionRequest ToolRef minimal defaults null`() {
        val decoded = json.decodeFromString<PermissionRequest.ToolRef>("{}")
        assertNull(decoded.messageId)
        assertNull(decoded.callId)
    }
}
