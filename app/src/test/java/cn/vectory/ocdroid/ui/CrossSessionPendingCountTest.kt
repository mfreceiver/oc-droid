package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.QuestionInfo
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §1C-FIX-⑦ / scheme D.1: unit tests for [crossSessionPendingCount]. Pins
 * the Sessions nav-badge filter — the badge counts pending permission +
 * question requests from sessions OTHER than the current session so the
 * current session's items are NOT double-counted (they already render in
 * the Chat StatusSlot, P5-7 session-scope filter).
 *
 * §task6 tree aggregation: question counting is now tree-aware (a question
 * whose session is inside the current ROOT tree counts as "current"; it
 * will surface on the current root's QuestionCard). Permission counting
 * stays precise (sessionId != currentSessionId). The pre-tree tests below
 * place `currentSessionId` inside `state.sessions` so [rootIdOf] can resolve
 * it to a single-node tree — that preserves the original "exclude the
 * current session's own pending" semantics when no descendants are wired.
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

    /** Single-root fixture: currentSessionId resolves to a one-node tree. */
    private fun root(sessionId: String) =
        Session(id = sessionId, directory = "/d", parentId = null)

    // ── current session filtering (the fix) ─────────────────────────────

    @Test
    fun `counts only OTHER sessions when current session has pending`() {
        // The regression: pendingPermissions.size + pendingQuestions.size
        // counted ALL pending, so the current session's items inflated the
        // badge WHILE the StatusSlot also rendered them → double-count. The
        // filter must exclude the current session.
        val state = SessionListState(
            sessions = listOf(root("sess-current")),
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
        // pending items whose sessionId != currentSessionId (permissions)
        // plus questions not in the current tree (current tree = single root).
        val state = SessionListState(
            sessions = listOf(root("s-current")),
            pendingPermissions = listOf(
                perm("p1", "s-a"),
                perm("p2", "s-b"),
                perm("p3", "s-current"),  // excluded (precise)
                perm("p4", "s-c"),
            ),
            pendingQuestions = listOf(
                question("q1", "s-current"), // excluded (in current tree)
                question("q2", "s-a"),
                question("q3", "s-d"),
            ),
        )
        // Other-session permissions: p1, p2, p4 = 3
        // Other-tree questions: q2, q3 = 2
        assertEquals(5, crossSessionPendingCount(state, "s-current"))
    }

    // ── no filtering needed (no current session overlap) ────────────────

    @Test
    fun `counts all pending when none belong to current session`() {
        val state = SessionListState(
            sessions = listOf(root("s-current")),
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
        // No current session → StatusSlot is empty + currentTree is empty
        // → every pending item is genuinely cross-session; nothing to
        // exclude. Permission != null is always true; question !in emptySet
        // is always true — both branches count the full set.
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
            sessions = listOf(root("s-current")),
            pendingPermissions = listOf(perm("p1", "s-current")),
            pendingQuestions = listOf(question("q1", "s-current")),
        )
        assertEquals(0, crossSessionPendingCount(state, "s-current"))
    }

    // ── §task6 tree-aware question counting ─────────────────────────────

    @Test
    fun `question on a descendant of current root does NOT inflate the badge`() {
        // The QuestionCard at the current root now renders the entire root
        // tree's question queue, so a sub-agent's question is "current" from
        // the user's POV — the badge must NOT count it as cross-session.
        val root = Session(id = "root", directory = "/d", parentId = null)
        val child = Session(id = "child", directory = "/d", parentId = "root")
        val state = SessionListState(
            sessions = listOf(root, child),
            pendingQuestions = listOf(
                question("q-root", "root"),
                question("q-child", "child"),
            ),
        )
        // Both questions live inside the "root" tree → badge counts 0 of
        // them. Permissions would still count precisely if any were present.
        assertEquals(0, crossSessionPendingCount(state, "root"))
    }

    @Test
    fun `question on a sibling root tree DOES inflate the badge`() {
        val rootA = Session(id = "A", directory = "/d", parentId = null)
        val childA = Session(id = "C", directory = "/d", parentId = "A")
        val rootB = Session(id = "B", directory = "/d", parentId = null)
        val state = SessionListState(
            sessions = listOf(rootA, childA, rootB),
            pendingQuestions = listOf(
                question("q-in-A", "C"),
                question("q-in-B", "B"),
            ),
        )
        // Current root = A → A's tree {A, C} covers q-in-A; q-in-B is
        // outside → badge = 1.
        assertEquals(1, crossSessionPendingCount(state, "A"))
    }

    @Test
    fun `permission on a descendant stays precise and DOES inflate the badge`() {
        // §task6 invariant: permission must NOT be tree-aggregated. A sub-
        // agent's tool permission is its OWN consent prompt; aggregating it
        // into the current root would hide WHICH session needs the user's
        // approval. So even when the sub-agent lives under the current root,
        // its permission is cross-session for badge purposes (the
        // StatusSlot's permission filter is also precise, not tree-aware —
        // so the badge must surface every other session's permission,
        // including descendants).
        val root = Session(id = "root", directory = "/d", parentId = null)
        val child = Session(id = "child", directory = "/d", parentId = "root")
        val state = SessionListState(
            sessions = listOf(root, child),
            pendingPermissions = listOf(
                perm("p-root", "root"),   // excluded (precise: == current)
                perm("p-child", "child"), // counted (precise: != current)
            ),
        )
        assertEquals(1, crossSessionPendingCount(state, "root"))
    }

    @Test
    fun `unknown currentSessionId is treated as null tree`() {
        // If currentSessionId is set but absent from byId (e.g. a stale id
        // after the session was archived but before currentSessionId
        // cleared), rootIdOf returns null → currentTree empty → every
        // question counts as cross-session. Matches the null edge case.
        val state = SessionListState(
            pendingQuestions = listOf(
                question("q1", "ghost-current"),
                question("q2", "other"),
            ),
        )
        assertEquals(2, crossSessionPendingCount(state, "ghost-current"))
    }
}
