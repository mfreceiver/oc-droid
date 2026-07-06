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
 * Round-trip + computed-property coverage for [AgentInfo]. Pure
 * kotlinx.serialization.
 */
class AgentInfoSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // ── Round trip ────────────────────────────────────────────────────────

    @Test
    fun `AgentInfo round trip with all fields`() {
        val agent = AgentInfo(
            name = "Sisyphus (Ultraworker)",
            description = "Build orchestrator",
            mode = "primary",
            hidden = false,
            native = true
        )
        val encoded = json.encodeToString(agent)
        val decoded = json.decodeFromString<AgentInfo>(encoded)
        assertEquals(agent, decoded)
    }

    @Test
    fun `AgentInfo minimal round trip uses defaults`() {
        val agent = AgentInfo(name = "Simple")
        val decoded = json.decodeFromString<AgentInfo>(json.encodeToString(agent))
        assertEquals(agent, decoded)
    }

    @Test
    fun `AgentInfo parses server JSON without optional fields`() {
        val decoded = json.decodeFromString<AgentInfo>("""{"name":"Build"}""")
        assertEquals("Build", decoded.name)
        assertEquals("Build", decoded.id)
        assertNull(decoded.description)
        assertNull(decoded.mode)
        assertNull(decoded.hidden)
        assertNull(decoded.native)
    }

    @Test
    fun `AgentInfo tolerates unknown fields`() {
        val decoded = json.decodeFromString<AgentInfo>(
            """{"name":"X","tools":["a","b"],"builtin":true}"""
        )
        assertEquals("X", decoded.name)
    }

    // ── id ────────────────────────────────────────────────────────────────

    @Test
    fun `AgentInfo id is name`() {
        assertEquals("foo", AgentInfo(name = "foo").id)
    }

    // ── shortName ─────────────────────────────────────────────────────────

    @Test
    fun `AgentInfo shortName extracts portion before parenthesis`() {
        assertEquals("Sisyphus", AgentInfo(name = "Sisyphus (Ultraworker)").shortName)
    }

    @Test
    fun `AgentInfo shortName extracts portion before space when no parenthesis`() {
        assertEquals("Build", AgentInfo(name = "Build Agent").shortName)
    }

    @Test
    fun `AgentInfo shortName returns full name when no parenthesis or space`() {
        assertEquals("Oracle", AgentInfo(name = "Oracle").shortName)
    }

    @Test
    fun `AgentInfo shortName does not split on parenthesis at start`() {
        // parenIndex == 0 → not > 0 → falls through to space check.
        // For "(Leading) Word" the space check at index 9 splits before "Word",
        // so shortName is "(Leading)" — the leading-paren case is NOT preserved
        // wholesale, only the leading-paren *guard* is bypassed.
        assertEquals("(Leading)", AgentInfo(name = "(Leading) Word").shortName)
    }

    @Test
    fun `AgentInfo shortName does not split on space at start`() {
        // spaceIndex == 0 → not > 0 → returns full name.
        assertEquals(" LeadingSpace", AgentInfo(name = " LeadingSpace").shortName)
    }

    // ── isVisible ─────────────────────────────────────────────────────────

    @Test
    fun `AgentInfo isVisible false when hidden true`() {
        assertFalse(AgentInfo(name = "x", hidden = true, mode = "primary").isVisible)
    }

    @Test
    fun `AgentInfo isVisible true when mode null`() {
        assertTrue(AgentInfo(name = "x", mode = null).isVisible)
    }

    @Test
    fun `AgentInfo isVisible true for primary or all modes`() {
        assertTrue(AgentInfo(name = "x", mode = "primary").isVisible)
        assertTrue(AgentInfo(name = "x", mode = "all").isVisible)
    }

    @Test
    fun `AgentInfo isVisible false for non-primary non-all modes`() {
        assertFalse(AgentInfo(name = "x", mode = "subagent").isVisible)
        assertFalse(AgentInfo(name = "x", mode = "secondary").isVisible)
    }

    @Test
    fun `AgentInfo hidden false overrides mode check`() {
        // hidden=false explicitly, mode="subagent" → not visible (mode wins).
        assertFalse(AgentInfo(name = "x", hidden = false, mode = "subagent").isVisible)
        // hidden=false explicitly, mode="primary" → visible.
        assertTrue(AgentInfo(name = "x", hidden = false, mode = "primary").isVisible)
    }
}
