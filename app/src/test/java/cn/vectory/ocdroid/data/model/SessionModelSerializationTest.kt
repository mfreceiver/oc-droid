package cn.vectory.ocdroid.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip + computed-property coverage for [Session] (and nested types),
 * [SessionStatus], [SessionCacheEntry] and the [toCacheEntry]/[toSession]
 * lossy mappers. Pure kotlinx.serialization.
 */
class SessionModelSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // ── Session round trip ────────────────────────────────────────────────

    @Test
    fun `Session round trip with all optional fields set`() {
        val session = Session(
            id = "s1",
            slug = "my-slug",
            projectId = "p1",
            directory = "/home/user/project",
            parentId = "p0",
            title = "Test",
            version = "1.0",
            time = Session.TimeInfo(created = 1L, updated = 2L, archived = 3L),
            share = Session.ShareInfo(url = "https://share.example/s1"),
            summary = Session.SummaryInfo(additions = 10, deletions = 5, files = 3),
            revert = Session.RevertInfo(messageId = "m1", partId = "pt1", snapshot = "snap", diff = "diff")
        )
        val encoded = json.encodeToString(session)
        val decoded = json.decodeFromString<Session>(encoded)
        assertEquals(session, decoded)
    }

    @Test
    fun `Session minimal round trip uses defaults`() {
        val session = Session(id = "s1", directory = "/x")
        val encoded = json.encodeToString(session)
        val decoded = json.decodeFromString<Session>(encoded)
        assertEquals(session, decoded)
        assertNull(decoded.slug)
        assertNull(decoded.parentId)
        assertNull(decoded.title)
    }

    @Test
    fun `Session decodes camelCase serial names`() {
        val decoded = json.decodeFromString<Session>(
            """{"id":"s","projectID":"p","parentID":"p0","directory":"/x"}"""
        )
        assertEquals("p", decoded.projectId)
        assertEquals("p0", decoded.parentId)
    }

    @Test
    fun `Session nested TimeInfo round trip`() {
        val t = Session.TimeInfo(created = 1L, updated = 2L, archived = 3L)
        val decoded = json.decodeFromString<Session.TimeInfo>(json.encodeToString(t))
        assertEquals(t, decoded)
    }

    @Test
    fun `Session nested ShareInfo round trip`() {
        val s = Session.ShareInfo(url = "u")
        val decoded = json.decodeFromString<Session.ShareInfo>(json.encodeToString(s))
        assertEquals(s, decoded)
    }

    @Test
    fun `Session nested SummaryInfo round trip`() {
        val s = Session.SummaryInfo(additions = 1, deletions = 2, files = 3)
        val decoded = json.decodeFromString<Session.SummaryInfo>(json.encodeToString(s))
        assertEquals(s, decoded)
    }

    @Test
    fun `Session nested RevertInfo round trip with SerialName mappings`() {
        val r = Session.RevertInfo(messageId = "m1", partId = "pt1", snapshot = "snap", diff = "diff")
        val encoded = json.encodeToString(r)
        // @SerialName("messageID") / @SerialName("partID").
        assertTrue(encoded.contains("\"messageID\":\"m1\""))
        assertTrue(encoded.contains("\"partID\":\"pt1\""))
        val decoded = json.decodeFromString<Session.RevertInfo>(encoded)
        assertEquals(r, decoded)
    }

    // ── Session.displayName / isArchived ──────────────────────────────────

    @Test
    fun `Session displayName prefers title then directory last segment then id`() {
        assertEquals("My", Session(id = "s1", directory = "/a/b", title = "My").displayName)
        assertEquals("project", Session(id = "s1", directory = "/home/user/project").displayName)
        assertEquals("x", Session(id = "s1", directory = "x").displayName)
        assertEquals("s1", Session(id = "s1", directory = "").displayName)
        // directory with trailing slashes is filtered out → falls back to id.
        assertEquals("s1", Session(id = "s1", directory = "////").displayName)
    }

    @Test
    fun `Session isArchived reflects time archived`() {
        assertFalse(Session(id = "s1", directory = "/x").isArchived)
        assertFalse(Session(id = "s1", directory = "/x", time = Session.TimeInfo(archived = 0L)).isArchived)
        assertTrue(Session(id = "s1", directory = "/x", time = Session.TimeInfo(archived = 1000L)).isArchived)
    }

    // ── SessionStatus ─────────────────────────────────────────────────────

    @Test
    fun `SessionStatus round trip preserves fields`() {
        val status = SessionStatus(type = "retry", attempt = 2, message = "Retrying", next = 1234L)
        val encoded = json.encodeToString(status)
        val decoded = json.decodeFromString<SessionStatus>(encoded)
        assertEquals(status, decoded)
    }

    @Test
    fun `SessionStatus type checks`() {
        assertTrue(SessionStatus(type = "idle").isIdle)
        assertFalse(SessionStatus(type = "idle").isBusy)

        assertTrue(SessionStatus(type = "busy").isBusy)
        assertFalse(SessionStatus(type = "busy").isIdle)

        assertTrue(SessionStatus(type = "retry", attempt = 1).isRetry)
        assertFalse(SessionStatus(type = "retry").isIdle)
        assertFalse(SessionStatus(type = "retry").isBusy)

        // Anything else → none of them.
        val other = SessionStatus(type = "weird")
        assertFalse(other.isIdle)
        assertFalse(other.isBusy)
        assertFalse(other.isRetry)
    }

    @Test
    fun `SessionStatus minimal defaults`() {
        val decoded = json.decodeFromString<SessionStatus>("{" + "\"type\":\"idle\"}")
        assertNull(decoded.attempt)
        assertNull(decoded.message)
        assertNull(decoded.next)
    }

    // ── SessionCacheEntry ─────────────────────────────────────────────────

    @Test
    fun `SessionCacheEntry round trip with all fields`() {
        val entry = SessionCacheEntry(
            id = "s1", title = "T", directory = "/x", parentId = "p0",
            timeCreated = 1L, timeUpdated = 2L, timeArchived = 3L,
            additions = 10, deletions = 5, files = 2
        )
        val encoded = json.encodeToString(entry)
        val decoded = json.decodeFromString<SessionCacheEntry>(encoded)
        assertEquals(entry, decoded)
    }

    @Test
    fun `SessionCacheEntry minimal defaults`() {
        val decoded = json.decodeFromString<SessionCacheEntry>(
            """{"id":"s1","directory":"/x"}"""
        )
        assertEquals("s1", decoded.id)
        assertEquals("/x", decoded.directory)
        assertNull(decoded.title)
        assertNull(decoded.parentId)
        assertNull(decoded.timeCreated)
        assertNull(decoded.additions)
    }

    // ── toCacheEntry / toSession lossy round trip ─────────────────────────

    @Test
    fun `Session toCacheEntry projects fields`() {
        val session = Session(
            id = "s1",
            directory = "/x",
            parentId = "p0",
            title = "T",
            time = Session.TimeInfo(created = 1L, updated = 2L, archived = 3L),
            summary = Session.SummaryInfo(additions = 10, deletions = 5, files = 2)
        )
        val entry = session.toCacheEntry()
        assertEquals("s1", entry.id)
        assertEquals("T", entry.title)
        assertEquals("/x", entry.directory)
        assertEquals("p0", entry.parentId)
        assertEquals(1L, entry.timeCreated)
        assertEquals(2L, entry.timeUpdated)
        assertEquals(3L, entry.timeArchived)
        assertEquals(10, entry.additions)
        assertEquals(5, entry.deletions)
        assertEquals(2, entry.files)
    }

    @Test
    fun `SessionCacheEntry toSession reconstructs time and summary when present`() {
        val entry = SessionCacheEntry(
            id = "s1", directory = "/x", title = "T", parentId = "p0",
            timeCreated = 1L, timeUpdated = 2L, timeArchived = 3L,
            additions = 10, deletions = 5, files = 2
        )
        val session = entry.toSession()
        assertEquals("s1", session.id)
        assertEquals("/x", session.directory)
        assertEquals("T", session.title)
        assertEquals("p0", session.parentId)
        assertNotNull(session.time)
        assertEquals(1L, session.time?.created)
        assertEquals(2L, session.time?.updated)
        assertEquals(3L, session.time?.archived)
        assertNotNull(session.summary)
        assertEquals(10, session.summary?.additions)
        assertEquals(5, session.summary?.deletions)
        assertEquals(2, session.summary?.files)
        // Non-cached fields default to null.
        assertNull(session.slug)
        assertNull(session.projectId)
        assertNull(session.share)
        assertNull(session.revert)
        assertNull(session.version)
    }

    @Test
    fun `SessionCacheEntry toSession yields null time and summary when all absent`() {
        val entry = SessionCacheEntry(id = "s1", directory = "/x")
        val session = entry.toSession()
        assertNull(session.time)
        assertNull(session.summary)
    }

    @Test
    fun `toCacheEntry then toSession round trip is lossless for cached fields`() {
        val session = Session(
            id = "s1",
            directory = "/x",
            parentId = "p0",
            title = "T",
            time = Session.TimeInfo(created = 1L, updated = 2L),
            summary = Session.SummaryInfo(additions = 1)
        )
        val restored = session.toCacheEntry().toSession()
        assertEquals(session.id, restored.id)
        assertEquals(session.directory, restored.directory)
        assertEquals(session.parentId, restored.parentId)
        assertEquals(session.title, restored.title)
        assertEquals(session.time?.created, restored.time?.created)
        assertEquals(session.time?.updated, restored.time?.updated)
        assertEquals(session.summary?.additions, restored.summary?.additions)
    }
}
