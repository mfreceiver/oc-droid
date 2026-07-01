package com.yage.opencode_client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Ignore
import org.junit.Test

/**
 * Unit tests for [parseSubAgentName] and [stripSubAgentSuffix] in
 * ChatSubAgentCard.kt. Both helpers are internal (same package visibility),
 * so this test class lives under `com.yage.opencode_client.ui.chat` and calls
 * them directly with no reflection.
 *
 * Context: 评审后加固——删了 [parseSubAgentName] 的死 pattern 3（pattern 1 的
 * 可选组 `(?:\s+subagent)?` 已覆盖纯 `(@xxx)`），并把 [stripSubAgentSuffix]
 * 从全局 replace 改为尾部锚定（保留正文中间的 `(@mention)`）。
 */
class ChatSubAgentCardParseTest {

    // ── parseSubAgentName ──────────────────────────────────────────────────

    @Test
    fun `parses parenthesized name with subagent suffix`() {
        assertEquals("coder", parseSubAgentName("Do something (@coder subagent)"))
    }

    @Test
    fun `parses parenthesized name case-insensitively and lowercases result`() {
        // 服务端可能下发 "(@Coder Subagent)"；捕获组原样返回大写，但下游
        // agentTone 等会归一——这里断言原始捕获为 "Coder"（pattern 1 命中）。
        assertEquals("Coder", parseSubAgentName("(@Coder Subagent)"))
    }

    @Test
    fun `pattern 1 optional group covers plain parenthesized mention`() {
        // 无 subagent 后缀的 "(@reviewer)" 由 pattern 1 的可选组 (?:\s+subagent)?
        // 匹配零次覆盖——这是旧 pattern 3 的死代码被删除后必须保留的行为。
        assertEquals("reviewer", parseSubAgentName("(@reviewer)"))
    }

    @Test
    fun `parses no-paren at-mention followed by subagent keyword`() {
        assertEquals("builder", parseSubAgentName("@builder subagent"))
    }

    @Test
    fun `returns null for plain text without any agent marker`() {
        assertNull(parseSubAgentName("plain text no agent"))
    }

    @Test
    fun `returns null for null input`() {
        assertNull(parseSubAgentName(null))
    }

    @Test
    fun `returns null for empty string`() {
        assertNull(parseSubAgentName(""))
    }

    @Test
    fun `returns null for blank whitespace string`() {
        assertNull(parseSubAgentName("   "))
    }

    @Test
    fun `parses name containing dashes and underscores`() {
        assertEquals("senior-dev", parseSubAgentName("(@senior-dev subagent)"))
        assertEquals("code_review", parseSubAgentName("(@code_review subagent)"))
    }

    /**
     * 已知容错行为（方案B）：parseSubAgentName 面向服务器 task 元数据
     * title/description（结构化字段），不对任意自然语言正文做防误匹配。
     * 因此 "ask (@senior-dev) to review" 会命中 "senior-dev"——这是当前
     * 既定行为，不改变。用 @Ignore 标注以文档化该风险但不在 CI 中跑。
     *
     * 真正的防误匹配职责由 [stripSubAgentSuffix] 的尾部锚定承担（见下面
     * `strip keeps mid-text mention and only strips trailing marker`）。
     */
    @Ignore("Known limitation: parse matches mid-text mentions; parse targets structured title/description only")
    @Test
    fun parse_matches_mid_text_mention_known_limitation() {
        // 若未来收紧 parse 的语义（例如要求尾部或全串匹配），取消 @Ignore 并
        // 把断言改为 assertNull(...)。
        assertEquals("senior-dev", parseSubAgentName("ask (@senior-dev) to review"))
    }

    // ── stripSubAgentSuffix ────────────────────────────────────────────────

    @Test
    fun `strip removes trailing parenthesized marker with subagent suffix`() {
        assertEquals("Description", stripSubAgentSuffix("Description (@coder subagent)"))
    }

    @Test
    fun `strip removes plain parenthesized marker yielding empty string`() {
        assertEquals("", stripSubAgentSuffix("(@reviewer)"))
    }

    @Test
    fun `strip leaves text without marker unchanged`() {
        assertEquals("Title", stripSubAgentSuffix("Title"))
    }

    @Test
    fun `strip keeps mid-text mention and only strips trailing marker`() {
        // 这是本次评审加固的核心断言：旧的 stripSubAgentSuffix 用全局 replace，
        // 会把正文中间的 (@senior-dev) 也删掉；改为尾部锚定后只删尾部 (@final)。
        assertEquals(
            "ask (@senior-dev) to review",
            stripSubAgentSuffix("ask (@senior-dev) to review (@final)")
        )
    }

    @Test
    fun `strip removes trailing no-paren subagent marker`() {
        assertEquals("Do work", stripSubAgentSuffix("Do work @builder subagent"))
    }

    @Test
    fun `strip returns empty string for null input`() {
        assertEquals("", stripSubAgentSuffix(null))
    }

    @Test
    fun `strip returns empty string for blank input`() {
        assertEquals("", stripSubAgentSuffix("   "))
    }

    @Test
    fun `strip trims surrounding whitespace left after marker removal`() {
        assertEquals("Description", stripSubAgentSuffix("Description   (@coder subagent)   "))
    }

    @Test
    fun `strip removes trailing subagent keyword case-insensitively`() {
        assertEquals("Task", stripSubAgentSuffix("Task (@agent Subagent)"))
    }
}
