package cn.vectory.ocdroid.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip + serializer-variant coverage for [TodoItem]. The custom
 * [TodoItemSerializer] handles three server-side variants of the completion
 * state (`status` string, `completed` boolean, `isCompleted` boolean), plus
 * defaulting rules for content / priority / id. Pure kotlinx.serialization.
 */
class TodoItemSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    // ── Direct construction + isCompleted property ────────────────────────

    @Test
    fun `TodoItem isCompleted for completed or cancelled status`() {
        assertTrue(TodoItem(content = "a", status = "completed", priority = "high", id = "1").isCompleted)
        assertTrue(TodoItem(content = "a", status = "cancelled", priority = "high", id = "2").isCompleted)
        assertFalse(TodoItem(content = "a", status = "pending", priority = "high", id = "3").isCompleted)
        assertFalse(TodoItem(content = "a", status = "in_progress", priority = "high", id = "4").isCompleted)
    }

    // ── Round trip ────────────────────────────────────────────────────────

    @Test
    fun `TodoItem serialize then deserialize preserves fields`() {
        val todo = TodoItem(content = "Task", status = "completed", priority = "high", id = "abc")
        val encoded = json.encodeToString(todo)
        // Serializer always writes content/status/priority/id.
        assertTrue(encoded.contains("\"content\":\"Task\""))
        assertTrue(encoded.contains("\"status\":\"completed\""))
        assertTrue(encoded.contains("\"priority\":\"high\""))
        assertTrue(encoded.contains("\"id\":\"abc\""))
        val decoded = json.decodeFromString<TodoItem>(encoded)
        assertEquals(todo, decoded)
    }

    // ── status string variant ─────────────────────────────────────────────

    @Test
    fun `TodoItem decodes status string and trims content`() {
        val decoded = json.decodeFromString<TodoItem>(
            """{"content":"  Task  ","status":"completed","priority":"high","id":"x"}"""
        )
        assertEquals("Task", decoded.content)
        assertEquals("completed", decoded.status)
        assertEquals("high", decoded.priority)
        assertEquals("x", decoded.id)
    }

    @Test
    fun `TodoItem decodes cancelled and pending status strings`() {
        val cancelled = json.decodeFromString<TodoItem>("{" + "\"content\":\"a\",\"status\":\"cancelled\"}")
        assertEquals("cancelled", cancelled.status)
        val pending = json.decodeFromString<TodoItem>("{" + "\"content\":\"a\",\"status\":\"pending\"}")
        assertEquals("pending", pending.status)
    }

    // ── completed boolean variant ─────────────────────────────────────────

    @Test
    fun `TodoItem decodes completed true as completed`() {
        val decoded = json.decodeFromString<TodoItem>("{" + "\"content\":\"a\",\"completed\":true}")
        assertEquals("completed", decoded.status)
        assertTrue(decoded.isCompleted)
    }

    @Test
    fun `TodoItem decodes completed false as pending`() {
        val decoded = json.decodeFromString<TodoItem>("{" + "\"content\":\"a\",\"completed\":false}")
        assertEquals("pending", decoded.status)
        assertFalse(decoded.isCompleted)
    }

    // ── isCompleted boolean variant ───────────────────────────────────────

    @Test
    fun `TodoItem decodes isCompleted true as completed`() {
        val decoded = json.decodeFromString<TodoItem>("{" + "\"content\":\"a\",\"isCompleted\":true}")
        assertEquals("completed", decoded.status)
    }

    @Test
    fun `TodoItem decodes isCompleted false as pending`() {
        val decoded = json.decodeFromString<TodoItem>("{" + "\"content\":\"a\",\"isCompleted\":false}")
        assertEquals("pending", decoded.status)
    }

    // ── Defaults ──────────────────────────────────────────────────────────

    @Test
    fun `TodoItem defaults content to Untitled todo when missing or blank`() {
        val noContent = json.decodeFromString<TodoItem>("{" + "\"status\":\"pending\"}")
        assertEquals("Untitled todo", noContent.content)
        val blankContent = json.decodeFromString<TodoItem>("{" + "\"content\":\"   \",\"status\":\"pending\"}")
        assertEquals("Untitled todo", blankContent.content)
    }

    @Test
    fun `TodoItem defaults priority to medium when missing or blank`() {
        val noPriority = json.decodeFromString<TodoItem>("{" + "\"content\":\"a\",\"status\":\"pending\"}")
        assertEquals("medium", noPriority.priority)
        val blankPriority = json.decodeFromString<TodoItem>(
            """{"content":"a","status":"pending","priority":"   "}"""
        )
        assertEquals("medium", blankPriority.priority)
    }

    @Test
    fun `TodoItem defaults status to pending when no completion signal`() {
        val decoded = json.decodeFromString<TodoItem>("{" + "\"content\":\"a\"}")
        assertEquals("pending", decoded.status)
    }

    @Test
    fun `TodoItem generates UUID id when missing or blank`() {
        val noId = json.decodeFromString<TodoItem>("{" + "\"content\":\"a\",\"status\":\"pending\"}")
        assertFalse("id should not be blank", noId.id.isBlank())
        // UUID shape: 8-4-4-4-12.
        assertTrue("id should look like a UUID", noId.id.matches(Regex("^[0-9a-f-]{36}$")))

        val blankId = json.decodeFromString<TodoItem>(
            """{"content":"a","status":"pending","id":"   "}"""
        )
        assertNotEquals("   ", blankId.id)
        assertTrue(blankId.id.matches(Regex("^[0-9a-f-]{36}$")))

        // Provided id is preserved verbatim (no UUID rewrite).
        val withId = json.decodeFromString<TodoItem>(
            """{"content":"a","status":"pending","id":"custom-1"}"""
        )
        assertEquals("custom-1", withId.id)
    }

    @Test
    fun `TodoItem prefers explicit status string over completed boolean`() {
        val decoded = json.decodeFromString<TodoItem>(
            """{"content":"a","status":"cancelled","completed":true}"""
        )
        assertEquals("cancelled", decoded.status)
    }
}
