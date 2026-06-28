package com.yage.opencode_client.ui.chat

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToneTest {

    @Test
    fun `known agent 'ask' returns its mapped color`() {
        assertEquals(agentTones["ask"], agentTone("ask"))
    }

    @Test
    fun `known agent 'build' returns its mapped color`() {
        assertEquals(agentTones["build"], agentTone("build"))
    }

    @Test
    fun `known agent 'docs' returns its mapped color`() {
        assertEquals(agentTones["docs"], agentTone("docs"))
    }

    @Test
    fun `known agent 'plan' returns its mapped color`() {
        assertEquals(agentTones["plan"], agentTone("plan"))
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
            "Expected result from colorPalette but got $result",
            colorPalette.contains(result)
        )
    }

    @Test
    fun `another custom name does not crash`() {
        val result = agentTone("my-custom-agent-xyz")
        assertNotNull(result)
        assertTrue(
            "Expected result from colorPalette but got $result",
            colorPalette.contains(result)
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
        // (probability of collision for 12-palette, 100 names is negligible)
        val results = (0 until 50).map { agentTone("agent-$it") }.toSet()
        assertTrue("Expected at least 2 distinct colors, got ${results.size}", results.size >= 2)
    }
}
