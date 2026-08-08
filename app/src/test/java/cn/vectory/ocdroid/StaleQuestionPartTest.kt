package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.PartState
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.ui.isStaleQuestionPart
import cn.vectory.ocdroid.ui.isStaleRunningPart
import cn.vectory.ocdroid.ui.isInterruptedQuestionPart
import cn.vectory.ocdroid.ui.QUESTION_INTERRUPT_GRACE_MS
import cn.vectory.ocdroid.ui.evaluateStaleRunningKeys
import cn.vectory.ocdroid.data.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §stale-question: verifies [isStaleQuestionPart] — the predicate that lets
 * the chat list render interrupted `question` tool parts terminally instead
 * of with a perpetual spinner.
 *
 * A part is stale iff it is a `question` tool, in `"running"` state, AND no
 * live [QuestionRequest] matches it by `tool.messageId + tool.callId`.
 */
class StaleQuestionPartTest {

    private fun part(
        tool: String? = "question",
        state: String = "running",
        messageId: String? = "msg-1",
        callId: String? = "call-1",
        type: String = "tool"
    ) = Part(
        id = "p-1",
        messageId = messageId,
        type = type,
        tool = tool,
        callId = callId,
        state = PartState(displayString = state)
    )

    private fun qRef(
        id: String = "q-1",
        messageId: String = "msg-1",
        callId: String = "call-1"
    ) = QuestionRequest(
        id = id,
        sessionId = "s-1",
        questions = emptyList(),
        tool = QuestionRequest.ToolRef(messageId = messageId, callId = callId)
    )

    @Test
    fun `stale when no matching pending QuestionRequest`() {
        // Running question part, but the pending list is empty → server no
        // longer has the question (in-memory only) → stale.
        val p = part()
        assertTrue(isStaleQuestionPart(p, pending = emptyList()))
    }

    @Test
    fun `live when matched by messageId and callId`() {
        val p = part(messageId = "msg-1", callId = "call-1")
        val pending = listOf(qRef(messageId = "msg-1", callId = "call-1"))
        assertFalse(isStaleQuestionPart(p, pending))
    }

    @Test
    fun `stale when pending has question but messageId differs`() {
        val p = part(messageId = "msg-1", callId = "call-1")
        val pending = listOf(qRef(messageId = "msg-OTHER", callId = "call-1"))
        assertTrue(isStaleQuestionPart(p, pending))
    }

    @Test
    fun `stale when pending has question but callId differs`() {
        val p = part(messageId = "msg-1", callId = "call-1")
        val pending = listOf(qRef(messageId = "msg-1", callId = "call-OTHER"))
        assertTrue(isStaleQuestionPart(p, pending))
    }

    @Test
    fun `stale when pending QuestionRequest has null tool ref`() {
        // A QuestionRequest without a tool ref cannot match any part → stale.
        val p = part()
        val pending = listOf(QuestionRequest(id = "q-1", sessionId = "s-1", questions = emptyList(), tool = null))
        assertTrue(isStaleQuestionPart(p, pending))
    }

    @Test
    fun `not stale when part messageId is null`() {
        // §conservative-null: a part missing its messageId returns false —
        // "unknown → leave the spinner, don't mis-kill". Mis-rendering a
        // possibly-live question as Interrupted is worse than a spinner.
        val p = part(messageId = null)
        assertFalse(isStaleQuestionPart(p, pending = listOf(qRef(messageId = "msg-1", callId = "call-1"))))
    }

    @Test
    fun `not stale when part callId is null`() {
        // §conservative-null: same rationale — unknown → not stale.
        val p = part(callId = null)
        assertFalse(isStaleQuestionPart(p, pending = listOf(qRef(messageId = "msg-1", callId = "call-1"))))
    }

    @Test
    fun `non-question parts are never stale`() {
        // A running bash / webfetch / Task tool part must keep its normal
        // spinner regardless of the pending list — only `question` parts can
        // be stale.
        assertFalse(isStaleQuestionPart(part(tool = "bash"), pending = emptyList()))
        assertFalse(isStaleQuestionPart(part(tool = "webfetch"), pending = emptyList()))
        assertFalse(isStaleQuestionPart(part(tool = "Task"), pending = emptyList())) // case-sensitive on tool name match
    }

    @Test
    fun `question tool name matched case-insensitively`() {
        // `Question` (capital Q) is the canonical opencode spelling; the
        // predicate lowercases before comparing so it still matches.
        val p = part(tool = "Question")
        assertTrue(isStaleQuestionPart(p, pending = emptyList()))
        assertFalse(isStaleQuestionPart(p, pending = listOf(qRef())))
    }

    @Test
    fun `non-running parts are never stale`() {
        // Completed / errored / idle parts already render a terminal state;
        // only "running" can be stale.
        assertFalse(isStaleQuestionPart(part(state = "completed"), pending = emptyList()))
        assertFalse(isStaleQuestionPart(part(state = "success"), pending = emptyList()))
        assertFalse(isStaleQuestionPart(part(state = "error"), pending = emptyList()))
        assertFalse(isStaleQuestionPart(part(state = "idle"), pending = emptyList()))
    }

    @Test
    fun `non-tool parts are never stale`() {
        // text / reasoning / file parts do not go through BasicTool's running
        // branch; the predicate must return false so unrelated rendering is
        // untouched.
        assertFalse(isStaleQuestionPart(part(type = "text"), pending = emptyList()))
        assertFalse(isStaleQuestionPart(part(type = "reasoning"), pending = emptyList()))
    }

    @Test
    fun `matches against one of several pending QuestionRequests`() {
        // Multiple questions can be live simultaneously; the predicate matches
        // if ANY pending entry references the part's (messageId, callId).
        val p = part(messageId = "msg-2", callId = "call-2")
        val pending = listOf(
            qRef(id = "q-1", messageId = "msg-1", callId = "call-1"),
            qRef(id = "q-2", messageId = "msg-2", callId = "call-2"),
            qRef(id = "q-3", messageId = "msg-3", callId = "call-3")
        )
        assertFalse(isStaleQuestionPart(p, pending))
    }

    @Test
    fun `thin placeholder is stale after session becomes idle`() {
        val p = Part(
            id = "thin_placeholder_msg-1",
            messageId = "msg-1",
            type = "text",
            text = "[内容已折叠，点开查看]",
        )
        assertTrue(
            isStaleRunningPart(
                p, pending = emptyList(), sessionStatus = SessionStatus("idle"),
                nowEpochMs = 100_000L, runningSinceEpochMs = null,
            )
        )
    }

    @Test
    fun `thin placeholder stays live while session is busy`() {
        val p = Part(id = "thin_placeholder_msg-1", type = "text")
        assertFalse(
            isStaleRunningPart(
                p, pending = emptyList(), sessionStatus = SessionStatus("busy"),
                nowEpochMs = 100_000L, runningSinceEpochMs = null,
            )
        )
    }

    // ── §need-8: isInterruptedQuestionPart gate tests ───────────────────────

    @Test
    fun `interrupted session idle plus grace elapsed plus no match returns true`() {
        val p = part()
        val now = 100_000L
        // runningSince = now - 6000 > grace (5000) → grace elapsed
        assertTrue(
            isInterruptedQuestionPart(
                part = p, pending = emptyList(),
                sessionStatus = SessionStatus("idle"),
                nowEpochMs = now, runningSinceEpochMs = now - 6_000,
            )
        )
    }

    @Test
    fun `not interrupted session busy plus grace elapsed plus no match returns false`() {
        val p = part()
        val now = 100_000L
        // Even though grace elapsed, session is busy → not interrupted
        assertFalse(
            isInterruptedQuestionPart(
                part = p, pending = emptyList(),
                sessionStatus = SessionStatus("busy"),
                nowEpochMs = now, runningSinceEpochMs = now - 6_000,
            )
        )
    }

    @Test
    fun `not interrupted session null plus grace elapsed plus no match returns false`() {
        val p = part()
        val now = 100_000L
        // SessionStatus is null → conservative: don't judge
        assertFalse(
            isInterruptedQuestionPart(
                part = p, pending = emptyList(),
                sessionStatus = null,
                nowEpochMs = now, runningSinceEpochMs = now - 6_000,
            )
        )
    }

    @Test
    fun `not interrupted session idle plus grace not yet elapsed returns false`() {
        val p = part()
        val now = 100_000L
        // runningSince = now - 1000 < grace (5000) → grace NOT elapsed
        assertFalse(
            isInterruptedQuestionPart(
                part = p, pending = emptyList(),
                sessionStatus = SessionStatus("idle"),
                nowEpochMs = now, runningSinceEpochMs = now - 1_000,
            )
        )
    }

    @Test
    fun `not interrupted matching pending exists returns false`() {
        val p = part(messageId = "msg-1", callId = "call-1")
        val pending = listOf(qRef(messageId = "msg-1", callId = "call-1"))
        // isStaleQuestionPart returns false first → isInterruptedQuestionPart returns false
        assertFalse(
            isInterruptedQuestionPart(
                part = p, pending = pending,
                sessionStatus = SessionStatus("idle"),
                nowEpochMs = 100_000L, runningSinceEpochMs = 100_000L - 6_000,
            )
        )
    }

    @Test
    fun `interrupted runningSince null with session idle returns true`() {
        val p = part()
        val now = 100_000L
        // runningSince = null → treat as grace already elapsed → interrupted
        assertTrue(
            isInterruptedQuestionPart(
                part = p, pending = emptyList(),
                sessionStatus = SessionStatus("idle"),
                nowEpochMs = now, runningSinceEpochMs = null,
            )
        )
    }

    @Test
    fun `not interrupted retry session plus grace elapsed returns false`() {
        val p = part()
        val now = 100_000L
        assertFalse(
            isInterruptedQuestionPart(
                part = p, pending = emptyList(),
                sessionStatus = SessionStatus("retry"),
                nowEpochMs = now, runningSinceEpochMs = now - 6_000,
            )
        )
    }

    @Test
    fun `interrupted at exact grace boundary returns true`() {
        val p = part()
        val now = 10_000L
        // now - runningSince == 5000 == QUESTION_INTERRUPT_GRACE_MS
        // Implementation uses `< graceMs` → 5000 < 5000 is false → grace elapsed → interrupted
        assertTrue(
            isInterruptedQuestionPart(
                part = p, pending = emptyList(),
                sessionStatus = SessionStatus("idle"),
                nowEpochMs = now, runningSinceEpochMs = 5_000L,
            )
        )
    }

    // ── §fix-thin-flicker: thin_placeholder grace 门控测试 ───────────────────

    @Test
    fun `thin placeholder grace not yet elapsed stays live`() {
        val p = Part(id = "thin_placeholder_msg-1", type = "text")
        val now = 100_000L
        // runningSince = now - 1000 < grace (5000) → grace 未过 → 不中断（显示加载条）
        assertFalse(
            isStaleRunningPart(
                p, pending = emptyList(), sessionStatus = SessionStatus("idle"),
                nowEpochMs = now, runningSinceEpochMs = now - 1_000,
            )
        )
    }

    @Test
    fun `thin placeholder grace elapsed shows interrupted`() {
        val p = Part(id = "thin_placeholder_msg-1", type = "text")
        val now = 100_000L
        // runningSince = now - 6000 > grace (5000) → grace 已过 → 中断
        assertTrue(
            isStaleRunningPart(
                p, pending = emptyList(), sessionStatus = SessionStatus("idle"),
                nowEpochMs = now, runningSinceEpochMs = now - 6_000,
            )
        )
    }

    // ── §fix-thin-flicker: evaluateStaleRunningKeys 时序场景测试 ─────────────
    // 模拟 ChatMessageList 跨 recompose 序列：共享一个 runningSince map，逐次
    // 调用 evaluateStaleRunningKeys，断言 seed/prune/judge 在 Bug C idle 抖动
    // 与真实中断下的行为。这是纯函数 isStaleRunningPart 测不到的集成语义。

    private val thinInterruptId = "thin_placeholder_msg-1"

    @Test
    fun `evaluateStaleRunningKeys - busy streaming then idle flap does not interrupt`() {
        val parts = listOf(Part(id = thinInterruptId, type = "text"))
        val rs = mutableMapOf<String, Long>()

        // T1: busy + placeholder 出现 → 不播种、不中断
        var keys = evaluateStaleRunningKeys(parts, emptyList(), SessionStatus("busy"), now = 0L, runningSince = rs)
        assertTrue("T1 busy: no interrupt", keys.isEmpty())
        assertTrue("T1 busy: not seeded", rs.isEmpty())

        // T2: idle 抖动 (now=30s) → 播种 30s、不中断（grace 内）
        keys = evaluateStaleRunningKeys(parts, emptyList(), SessionStatus("idle"), now = 30_000L, runningSince = rs)
        assertTrue("T2 idle flap: no interrupt", keys.isEmpty())
        assertEquals("T2 idle flap: seeded at 30s", 30_000L, rs[thinInterruptId])

        // T3: 回 busy → 种子被 prune
        keys = evaluateStaleRunningKeys(parts, emptyList(), SessionStatus("busy"), now = 30_300L, runningSince = rs)
        assertTrue("T3 busy: no interrupt", keys.isEmpty())
        assertTrue("T3 busy: seed pruned", rs.isEmpty())

        // T4: 再次 idle (now=40s) → 重新播种 40s、不中断（多次抖动重置 grace）
        keys = evaluateStaleRunningKeys(parts, emptyList(), SessionStatus("idle"), now = 40_000L, runningSince = rs)
        assertTrue("T4 second flap: no interrupt", keys.isEmpty())
        assertEquals("T4 second flap: re-seeded at 40s", 40_000L, rs[thinInterruptId])

        // T5: idle 持续 6s (now=46s) → 判中断（连续 idle ≥ grace）
        keys = evaluateStaleRunningKeys(parts, emptyList(), SessionStatus("idle"), now = 46_000L, runningSince = rs)
        assertTrue("T5 sustained idle: interrupted", keys.contains(thinInterruptId))
    }

    @Test
    fun `evaluateStaleRunningKeys - cold-start stuck placeholder interrupts after grace`() {
        // T6: 冷启动加载到 stuck placeholder（session 已 idle）→ 播种 +6s → 中断
        val parts = listOf(Part(id = thinInterruptId, type = "text"))
        val rs = mutableMapOf<String, Long>()
        var keys = evaluateStaleRunningKeys(parts, emptyList(), SessionStatus("idle"), now = 100_000L, runningSince = rs)
        assertTrue("T6 cold start t0: no interrupt", keys.isEmpty())
        keys = evaluateStaleRunningKeys(parts, emptyList(), SessionStatus("idle"), now = 106_000L, runningSince = rs)
        assertTrue("T6 cold start +6s: interrupted", keys.contains(thinInterruptId))
    }
}
