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
 * Wire deserialization coverage for [DirectoryEntry] and [DirectoriesEnvelope].
 * Pure kotlinx.serialization; no dependency on the project's Json config.
 */
class DirectoriesModelsSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // ── DirectoryEntry ────────────────────────────────────────────────────

    @Test
    fun `DirectoryEntry round trip with all fields`() {
        val entry = DirectoryEntry(
            directory = "/app",
            title = "My Project",
            lastUpdated = 1_725_000_000_000L,
            activeRootSessionCount = 3,
            archivedRootSessionCount = 1,
            archivedOnly = false,
        )
        val encoded = json.encodeToString(entry)
        val decoded = json.decodeFromString<DirectoryEntry>(encoded)
        assertEquals(entry, decoded)
    }

    @Test
    fun `DirectoryEntry parses with title null`() {
        // title absent (explicitNulls=false → null)
        val decoded = json.decodeFromString<DirectoryEntry>(
            """{"directory":"/app","activeRootSessionCount":2,"archivedRootSessionCount":0,"archivedOnly":false}"""
        )
        assertEquals("/app", decoded.directory)
        assertNull(decoded.title)
        assertNull(decoded.lastUpdated)
        assertEquals(2, decoded.activeRootSessionCount)
        assertEquals(0, decoded.archivedRootSessionCount)
        assertFalse(decoded.archivedOnly)
    }

    @Test
    fun `DirectoryEntry parses with title and lastUpdated explicitly null`() {
        val decoded = json.decodeFromString<DirectoryEntry>(
            """{"directory":"/data","title":null,"lastUpdated":null,"activeRootSessionCount":0,"archivedRootSessionCount":5,"archivedOnly":true}"""
        )
        assertEquals("/data", decoded.directory)
        assertNull(decoded.title)
        assertNull(decoded.lastUpdated)
        assertEquals(0, decoded.activeRootSessionCount)
        assertEquals(5, decoded.archivedRootSessionCount)
        assertTrue(decoded.archivedOnly)
    }

    @Test
    fun `DirectoryEntry tolerates unknown fields`() {
        val decoded = json.decodeFromString<DirectoryEntry>(
            """{"directory":"/test","activeRootSessionCount":1,"archivedRootSessionCount":0,"archivedOnly":false,"extra":"ignored"}"""
        )
        assertEquals("/test", decoded.directory)
    }

    // ── DirectoriesEnvelope ───────────────────────────────────────────────

    @Test
    fun `DirectoriesEnvelope round trip`() {
        val envelope = DirectoriesEnvelope(
            items = listOf(
                DirectoryEntry("/a", lastUpdated = 1_000L, activeRootSessionCount = 1, archivedRootSessionCount = 0, archivedOnly = false),
                DirectoryEntry("/b", activeRootSessionCount = 0, archivedRootSessionCount = 2, archivedOnly = true),
            ),
            discoveryComplete = true,
        )
        val encoded = json.encodeToString(envelope)
        val decoded = json.decodeFromString<DirectoriesEnvelope>(encoded)
        assertEquals(envelope, decoded)
    }

    @Test
    fun `DirectoriesEnvelope discoveryComplete true`() {
        val decoded = json.decodeFromString<DirectoriesEnvelope>(
            """{"items":[{"directory":"/p1","activeRootSessionCount":1,"archivedRootSessionCount":0,"archivedOnly":false}],"discoveryComplete":true}"""
        )
        assertEquals(1, decoded.items.size)
        assertEquals("/p1", decoded.items[0].directory)
        assertTrue(decoded.discoveryComplete)
    }

    @Test
    fun `DirectoriesEnvelope discoveryComplete false`() {
        val decoded = json.decodeFromString<DirectoriesEnvelope>(
            """{"items":[],"discoveryComplete":false}"""
        )
        assertEquals(0, decoded.items.size)
        assertFalse(decoded.discoveryComplete)
    }

    @Test
    fun `DirectoriesEnvelope empty items list`() {
        val decoded = json.decodeFromString<DirectoriesEnvelope>(
            """{"items":[],"discoveryComplete":true}"""
        )
        assertTrue(decoded.items.isEmpty())
        assertTrue(decoded.discoveryComplete)
    }

    @Test
    fun `DirectoriesEnvelope tolerates unknown fields`() {
        val decoded = json.decodeFromString<DirectoriesEnvelope>(
            """{"items":[],"discoveryComplete":false,"unknown":"ignored"}"""
        )
        assertTrue(decoded.items.isEmpty())
        assertFalse(decoded.discoveryComplete)
    }
}
