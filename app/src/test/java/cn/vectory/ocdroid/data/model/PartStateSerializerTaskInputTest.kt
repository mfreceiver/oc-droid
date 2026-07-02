package cn.vectory.ocdroid.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [PartStateSerializer]'s handling of the `task` tool's
 * `state.input.{agent,description,prompt}` (§problem-7 / 0.2.0).
 *
 * Context: the `task` (subagent) tool carries its agent name + description in
 * `state.input`, NOT in `state.metadata`. Without surfacing them, SubAgentCard
 * cannot resolve the @agent name and falls back to the flat accent colour.
 * [PartStateSerializer.parsePartState] injects input.agent/description/prompt
 * into the returned `metadata` JsonObject so SubAgentCard can read them via
 * `metadataString("agent")` / `metadataString("description")`.
 *
 * The injection is unconditional (no toolName is available at this layer), so
 * these tests also lock the contract that **non-task tools are unaffected** —
 * their input lacks the agent/description/prompt keys, so no keys are added.
 *
 * Decoding is driven through `PartState.serializer()` exactly as it is when a
 * `Part.state` field is deserialised, so this exercises the real path.
 */
class PartStateSerializerTaskInputTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun decode(stateJson: String): PartState =
        json.decodeFromString(PartState.serializer(), stateJson)

    // ── task tool: agent + description injected ────────────────────────────

    @Test
    fun `task input injects agent and description into metadata`() {
        val state = decode(
            """{"status":"running","input":{"agent":"coder","description":"Refactor the mapper","prompt":"go"}}"""
        )
        assertEquals("coder", state.metadataString("agent"))
        assertEquals("Refactor the mapper", state.metadataString("description"))
    }

    @Test
    fun `task input description falls back to prompt when description absent`() {
        val state = decode(
            """{"status":"running","input":{"agent":"explorer","prompt":"find the bug"}}"""
        )
        assertEquals("explorer", state.metadataString("agent"))
        // description should resolve to the prompt value (double-source fallback)
        assertEquals("find the bug", state.metadataString("description"))
    }

    @Test
    fun `task input injects agent only when no description or prompt`() {
        val state = decode(
            """{"status":"running","input":{"agent":"builder"}}"""
        )
        assertEquals("builder", state.metadataString("agent"))
        assertNull(state.metadataString("description"))
    }

    // ── non-task tools unaffected ──────────────────────────────────────────

    @Test
    fun `non-task input with command is not polluted with agent or description`() {
        val state = decode(
            """{"status":"completed","input":{"command":"ls -la","path":"/tmp"}}"""
        )
        // No agent/description keys in input → injection is a no-op.
        assertNull(state.metadataString("agent"))
        assertNull(state.metadataString("description"))
        // inputSummary still captures command as before.
        assertEquals("ls -la", state.inputSummary)
    }

    @Test
    fun `edit tool input with file_path is unaffected`() {
        val state = decode(
            """{"status":"completed","input":{"file_path":"/a/b.kt","content":"x"}}"""
        )
        assertNull(state.metadataString("agent"))
        assertNull(state.metadataString("description"))
        assertEquals("/a/b.kt", state.pathFromInput)
    }

    // ── pending-state input is a string → skipped, no crash ────────────────

    @Test
    fun `pending-state input as string does not crash and does not inject`() {
        val state = decode(
            """{"status":"running","input":"some pending text"}"""
        )
        assertNull(state.metadataString("agent"))
        assertNull(state.metadataString("description"))
        // inputSummary holds the raw string (existing behaviour).
        assertEquals("some pending text", state.inputSummary)
    }

    // ── pre-existing metadata.description still works (title fallback) ─────

    @Test
    fun `metadata description from element metadata is preserved when input has no description`() {
        val state = decode(
            """{"status":"running","metadata":{"description":"from-metadata"},"input":{"agent":"coder"}}"""
        )
        // input.agent is injected; metadata.description survives untouched.
        assertEquals("coder", state.metadataString("agent"))
        assertEquals("from-metadata", state.metadataString("description"))
    }

    @Test
    fun `input description does not overwrite an explicit metadata description`() {
        // Explicit server `metadata.description` is authoritative and must win
        // over a `description` coincidentally present in `input` — the injection
        // only adds a description key when the metadata block lacks one.
        val state = decode(
            """{"metadata":{"description":"metadata-wins"},"input":{"agent":"coder","description":"input-loses"}}"""
        )
        assertEquals("coder", state.metadataString("agent"))
        assertEquals("metadata-wins", state.metadataString("description"))
    }
}
