package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.QuestionInfo
import cn.vectory.ocdroid.data.model.QuestionRequest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §1C-FIX-⑦ / scheme D.1: unit tests for [crossSessionPendingCount]. Pins
 * the Sessions nav-badge filter — the badge counts pending permission +
 * question requests from sessions OTHER than the current session so the
 * current session's items are NOT double-counted (they already render in
 * the Chat StatusSlot, P5-7 session-scope filter).
 */
class CrossSessionPendingCountTest {

    private fun perm(id: String, sessionId: String) =
        PermissionRequest(id = id, sessionId = sessionId)

    private fun question(id: String, sessionId: String) =
        QuestionRequest(
            id = id,
            sessionId = sessionId,
            questions = listOf(QuestionInfo(question = "q", header = "h", options = emptyList())),
        )

    // ── current session filtering (the fix) ─────────────────────────────

    @Test
    fun `counts only OTHER sessions when current session has pending`() {
        // The regression: pendingPermissions.size + pendingQuestions.size
        // counted ALL pending, so the current session's items inflated the
        // badge WHILE the StatusSlot also rendered them → double-count. The
        // filter must exclude the current session.
        val state = SessionListState(
            pendingPermissions = listOf(
                perm("p-cur", "sess-current"),
                perm("p-other", "sess-other"),
            ),
            pendingQuestions = listOf(
                question("q-cur", "sess-current"),
                question("q-other-1", "sess-other-1"),
                question("q-other-2", "sess-other-2"),
            ),
        )
        // 1 other permission + 2 other questions = 3 (current session's
        // 1 permission + 1 question excluded).
        assertEquals(3, crossSessionPendingCount(state, "sess-current"))
    }

    @Test
    fun `badge equals other-session count across many sessions`() {
        // Multi-session mix: current session + several others, each with a
        // mix of permission / question pending. Badge MUST equal the sum of
        // pending items whose sessionId != currentSessionId.
        val state = SessionListState(
            pendingPermissions = listOf(
                perm("p1", "s-a"),
                perm("p2", "s-b"),
                perm("p3", "s-current"),  // excluded
                perm("p4", "s-c"),
            ),
            pendingQuestions = listOf(
                question("q1", "s-current"), // excluded
                question("q2", "s-a"),
                question("q3", "s-d"),
            ),
        )
        // Other-session permissions: p1, p2, p4 = 3
        // Other-session questions: q2, q3 = 2
        assertEquals(5, crossSessionPendingCount(state, "s-current"))
    }

    // ── no filtering needed (no current session overlap) ────────────────

    @Test
    fun `counts all pending when none belong to current session`() {
        val state = SessionListState(
            pendingPermissions = listOf(perm("p1", "s-a"), perm("p2", "s-b")),
            pendingQuestions = listOf(question("q1", "s-a")),
        )
        assertEquals(3, crossSessionPendingCount(state, "s-current"))
    }

    @Test
    fun `zero pending yields zero badge`() {
        assertEquals(0, crossSessionPendingCount(SessionListState(), "s-current"))
    }

    // ── null currentSessionId edge case ─────────────────────────────────

    @Test
    fun `null currentSessionId counts everything`() {
        // No current session → StatusSlot is empty → all pending are
        // genuinely cross-session; nothing to exclude.
        val state = SessionListState(
            pendingPermissions = listOf(perm("p1", "s-a"), perm("p2", "s-b")),
            pendingQuestions = listOf(question("q1", "s-a")),
        )
        assertEquals(3, crossSessionPendingCount(state, null))
    }

    @Test
    fun `null currentSessionId with empty pending yields zero`() {
        assertEquals(0, crossSessionPendingCount(SessionListState(), null))
    }

    // ── all pending belong to current session ───────────────────────────

    @Test
    fun `all pending in current session yields zero badge`() {
        // The StatusSlot handles these; the badge must stay hidden (no other
        // session needs attention).
        val state = SessionListState(
            pendingPermissions = listOf(perm("p1", "s-current")),
            pendingQuestions = listOf(question("q1", "s-current")),
        )
        assertEquals(0, crossSessionPendingCount(state, "s-current"))
    }
}
