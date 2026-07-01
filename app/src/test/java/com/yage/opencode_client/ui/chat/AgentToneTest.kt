package com.yage.opencode_client.ui.chat

import androidx.compose.ui.graphics.Color
import com.yage.opencode_client.ui.theme.LightOpencodeColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    // ── D4 三参贪心变体 agentTone(name, oc, assigned) ──────────────────
    // 覆盖 maximin 抗撞色实现（gpter/glmer D4 阻断项修复）。oc 参数用
    // LightOpencodeColors（与顶层 agentTone(name) 单参重载同源）。

    @Test
    fun `greedy - known agent short-circuits and does not write assigned`() {
        val assigned = mutableMapOf<String, Color>()
        val result = agentTone("ask", LightOpencodeColors, assigned)
        assertEquals(LightOpencodeColors.agentTones["ask"], result)
        // 已知 agent 走固定色短路，绝不污染传入的 assigned map
        assertTrue("assigned must remain empty for known agent, got $assigned", assigned.isEmpty())
    }

    @Test
    fun `greedy - same session reuses first assignment`() {
        val oc = LightOpencodeColors
        val assigned = mutableMapOf<String, Color>()
        val first = agentTone("custom-1", oc, assigned)
        // 模拟会话内调用方写回（生产代码在 SideEffect 里做）
        assigned["custom-1"] = first
        val second = agentTone("custom-1", oc, assigned)
        assertEquals("same agent name must reuse first assignment", first, second)
    }

    @Test
    fun `greedy - maximin keeps 5 distinct agents pairwise distinct`() {
        val oc = LightOpencodeColors
        val assigned = mutableMapOf<String, Color>()
        val names = listOf("alpha-agent", "beta-bot", "gamma-helper", "delta-runner", "epsilon-worker")
        val results = names.map { name ->
            val c = agentTone(name, oc, assigned)
            // 每次把上一次结果写入 map 后再调下一个（模拟会话累积）
            assigned[name.trim().lowercase()] = c
            assertTrue("result must come from palette, got $c", c in oc.colorPalette)
            c
        }
        // 5 个不同未知 agent 名应两两不同（maximin + 12 色调色板足够区分）
        val distinct = results.toSet()
        assertEquals(
            "expected 5 pairwise-distinct colors, got ${results.size - distinct.size + 1} unique",
            names.size,
            distinct.size
        )
    }

    @Test
    fun `greedy - result avoids palette slot neighbouring known agent tone`() {
        val oc = LightOpencodeColors
        // palette[0] = #5B9BD5（钢蓝），是离 ask #6CB4EE 最近的 palette 槽。
        // 未知 agent 首次分配时，occupied = agentTones 全集（4 个），maximin 应避开
        // palette[0]——它到 ask 的距离最小，在 maximin 下必然被淘汰。
        val nearestToAsk = oc.colorPalette[0]
        val result = agentTone("unknown-1", oc, emptyMap())
        assertNotEquals(
            "result should not be palette[0] ($nearestToAsk), the slot neighbouring ask",
            nearestToAsk,
            result
        )
        // 仍然来自 palette
        assertTrue("result must come from palette, got $result", result in oc.colorPalette)
    }

    @Test
    fun `greedy - case-insensitive reuse via lower-case key`() {
        val oc = LightOpencodeColors
        val expected = Color(0xFF123456) // 任意 sentinel 色
        // assigned 用 lower-case key 写入，"Custom-1" 应命中复用
        val assigned = mapOf("custom-1" to expected)
        val result = agentTone("Custom-1", oc, assigned)
        assertEquals("mixed-case name should reuse lower-case-keyed assignment", expected, result)
    }
}
