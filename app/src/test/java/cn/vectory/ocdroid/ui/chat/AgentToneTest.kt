package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.ui.theme.SemanticColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToneTest {

    @Test
    fun `known agent 'ask' returns its mapped color`() {
        // 已知 agent 不再走固定色，统一 hash → SemanticColors.agentPalette(16)。
        val result = agentTone("ask")
        assertTrue(
            "Expected result from agentPalette but got $result",
            SemanticColors.agentPalette.contains(result)
        )
    }

    @Test
    fun `known agent 'build' returns its mapped color`() {
        val result = agentTone("build")
        assertTrue(
            "Expected result from agentPalette but got $result",
            SemanticColors.agentPalette.contains(result)
        )
    }

    @Test
    fun `known agent 'docs' returns its mapped color`() {
        val result = agentTone("docs")
        assertTrue(
            "Expected result from agentPalette but got $result",
            SemanticColors.agentPalette.contains(result)
        )
    }

    @Test
    fun `known agent 'plan' returns its mapped color`() {
        val result = agentTone("plan")
        assertTrue(
            "Expected result from agentPalette but got $result",
            SemanticColors.agentPalette.contains(result)
        )
    }

    @Test
    fun `known agents are case-insensitive`() {
        assertEquals(agentTone("ask"), agentTone("ASK"))
        assertEquals(agentTone("ask"), agentTone("Ask"))
        assertEquals(agentTone("build"), agentTone("BUILD"))
        assertEquals(agentTone("docs"), agentTone("DOCS"))
        assertEquals(agentTone("plan"), agentTone("Plan"))
    }

    @Test
    fun `custom name longer than 6 chars returns valid palette color`() {
        val result = agentTone("research")
        assertNotNull(result)
        assertTrue(
            "Expected result from agentPalette but got $result",
            SemanticColors.agentPalette.contains(result)
        )
    }

    @Test
    fun `another custom name does not crash`() {
        val result = agentTone("my-custom-agent-xyz")
        assertNotNull(result)
        assertTrue(
            "Expected result from agentPalette but got $result",
            SemanticColors.agentPalette.contains(result)
        )
    }

    @Test
    fun `determinism - same name always returns same color`() {
        val name = "code-reviewer"
        val first = agentTone(name)
        repeat(100) {
            assertEquals("Mismatch on iteration $it", first, agentTone(name))
        }
    }

    @Test
    fun `different names can produce different colors`() {
        // At least two distinct names should produce different palette colors
        // (probability of collision for 16-palette, 50 names is negligible)
        val results = (0 until 50).map { agentTone("agent-$it") }.toSet()
        assertTrue("Expected at least 2 distinct colors, got ${results.size}", results.size >= 2)
    }

    // ── workdirTone（统一 16 色 hash，与 agentTone 共用 agentPalette）─────────

    @Test
    fun `workdirTone returns palette color and is deterministic`() {
        val dir = "/home/user/code/opencode-android"
        val first = workdirTone(dir)
        assertTrue(
            "Expected result from agentPalette but got $first",
            SemanticColors.agentPalette.contains(first)
        )
        // 同 directory 必须同色（hash 确定性）
        repeat(50) {
            assertEquals("workdirTone must be deterministic on iteration $it", first, workdirTone(dir))
        }
    }
}
