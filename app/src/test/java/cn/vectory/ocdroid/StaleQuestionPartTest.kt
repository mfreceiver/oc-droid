package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.PartState
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.ui.isStaleQuestionPart
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
    fun `stale when part has null messageId`() {
        // No QuestionRequest can match a part with a null messageId.
        val p = part(messageId = null)
        assertTrue(isStaleQuestionPart(p, pending = listOf(qRef(messageId = "msg-1", callId = "call-1"))))
    }

    @Test
    fun `stale when part has null callId`() {
        val p = part(callId = null)
        assertTrue(isStaleQuestionPart(p, pending = listOf(qRef(messageId = "msg-1", callId = "call-1"))))
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
}
