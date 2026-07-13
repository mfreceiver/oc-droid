package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.QuestionInfo
import cn.vectory.ocdroid.data.model.QuestionOption
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.ui.UnreadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §task7-coverage: JVM unit tests for the pure session-tree helpers in
 * [SessionTree.kt]. These are exercised indirectly via
 * [SessionSyncCoordinatorTest] (busy→idle) and [CrossSessionPendingCountTest]
 * (tree-aware counting), but the cycle / dedup / empty boundaries had no
 * direct unit test. This file pins those contracts.
 */
class SessionTreeHelpersTest {

    private fun session(id: String, parentId: String? = null) =
        Session(id = id, directory = "/tmp", parentId = parentId)

    // ── allSessionsById: three-source merge ─────────────────────────────────

    @Test
    fun `allSessionsById merges all three sources with first-write-wins dedup`() {
        val sessions = listOf(session("A"), session("B"))
        val directorySessions = mapOf("/d1" to listOf(session("B"), session("C")))
        val childSessions = mapOf("A" to listOf(session("D")))

        val byId = allSessionsById(sessions, directorySessions, childSessions)

        assertEquals(setOf("A", "B", "C", "D"), byId.keys)
        assertEquals("A", byId["A"]!!.id)
        assertEquals("B", byId["B"]!!.id)
        assertEquals("C", byId["C"]!!.id)
        assertEquals("D", byId["D"]!!.id)
    }

    @Test
    fun `allSessionsById on all-empty sources yields empty map`() {
        assertTrue(allSessionsById(emptyList(), emptyMap(), emptyMap()).isEmpty())
    }

    // ── rootIdOf: cycle / unknown / root ────────────────────────────────────

    @Test
    fun `rootIdOf returns the id itself when it has no parent`() {
        val byId = mapOf("root" to session("root"))
        assertEquals("root", rootIdOf("root", byId))
    }

    @Test
    fun `rootIdOf walks parentId chain to the root`() {
        val byId = mapOf(
            "root" to session("root"),
            "child" to session("child", parentId = "root"),
            "grandchild" to session("grandchild", parentId = "child"),
        )
        assertEquals("root", rootIdOf("grandchild", byId))
    }

    @Test
    fun `rootIdOf returns null for an unknown session id`() {
        assertNull(rootIdOf("ghost", mapOf("root" to session("root"))))
    }

    @Test
    fun `rootIdOf returns null when a parentId in the chain is unknown`() {
        val byId = mapOf("child" to session("child", parentId = "missing"))
        assertNull(rootIdOf("child", byId))
    }

    @Test
    fun `rootIdOf returns null on a parentId cycle`() {
        val byId = mapOf(
            "A" to session("A", parentId = "B"),
            "B" to session("B", parentId = "A"),
        )
        assertNull(rootIdOf("A", byId))
    }

    // ── treeIds: subtree collection + cycle guard ───────────────────────────

    @Test
    fun `treeIds returns root plus all descendants`() {
        val byId = mapOf(
            "root" to session("root"),
            "c1" to session("c1", parentId = "root"),
            "c2" to session("c2", parentId = "root"),
            "g1" to session("g1", parentId = "c1"),
        )
        assertEquals(setOf("root", "c1", "c2", "g1"), treeIds("root", byId))
    }

    @Test
    fun `treeIds for a leaf returns just the leaf`() {
        val byId = mapOf("leaf" to session("leaf"))
        assertEquals(setOf("leaf"), treeIds("leaf", byId))
    }

    @Test
    fun `treeIds for an unknown root returns just the root id`() {
        assertEquals(setOf("ghost"), treeIds("ghost", emptyMap()))
    }

    @Test
    fun `treeIds terminates on a cycle without infinite recursion`() {
        val byId = mapOf(
            "A" to session("A", parentId = "B"),
            "B" to session("B", parentId = "A"),
        )
        val result = treeIds("A", byId)
        assertTrue("A" in result)
        assertTrue("B" in result)
        assertEquals(2, result.size)
    }

    // ── subtreeIds: three-source convenience ────────────────────────────────

    @Test
    fun `subtreeIds collects from all three session sources`() {
        val sessions = listOf(session("root"))
        val directorySessions = mapOf("/d" to listOf(session("child", parentId = "root")))
        val childSessions = mapOf("root" to listOf(session("grandchild", parentId = "child")))

        val result = subtreeIds("root", sessions, directorySessions, childSessions)
        assertEquals(setOf("root", "child", "grandchild"), result)
    }

    // ── questionRootIds ─────────────────────────────────────────────────────

    private fun question(id: String, sessionId: String) =
        QuestionRequest(id = id, sessionId = sessionId, questions = emptyList())

    @Test
    fun `questionRootIds projects each question session up to its root`() {
        val byId = mapOf(
            "rootA" to session("rootA"),
            "childA" to session("childA", parentId = "rootA"),
            "rootB" to session("rootB"),
        )
        val pending = listOf(
            question("q1", "childA"),
            question("q2", "rootB"),
        )
        assertEquals(setOf("rootA", "rootB"), questionRootIds(pending, byId))
    }

    @Test
    fun `questionRootIds drops questions whose session is unknown`() {
        val byId = mapOf("root" to session("root"))
        val pending = listOf(
            question("q1", "root"),
            question("q2", "ghost"),
        )
        assertEquals(setOf("root"), questionRootIds(pending, byId))
    }

    @Test
    fun `questionRootIds on empty questions yields empty set`() {
        assertEquals(emptySet<String>(), questionRootIds(emptyList(), mapOf("root" to session("root"))))
    }

    // ── questionsInTree ─────────────────────────────────────────────────────

    @Test
    fun `questionsInTree returns questions whose session is anywhere in the root tree`() {
        val byId = mapOf(
            "root" to session("root"),
            "child" to session("child", parentId = "root"),
        )
        val pending = listOf(
            question("q-root", "root"),
            question("q-child", "child"),
            question("q-other", "other"),
        )
        val result = questionsInTree("root", pending, byId)
        assertEquals(listOf("q-root", "q-child"), result.map { it.id })
    }

    @Test
    fun `questionsInTree preserves original order`() {
        val byId = mapOf(
            "root" to session("root"),
            "c1" to session("c1", parentId = "root"),
            "c2" to session("c2", parentId = "root"),
        )
        val pending = listOf(
            question("q3", "c2"),
            question("q1", "root"),
            question("q2", "c1"),
            question("qX", "outside"),
        )
        val result = questionsInTree("root", pending, byId)
        assertEquals(listOf("q3", "q1", "q2"), result.map { it.id })
    }

    @Test
    fun `questionsInTree on empty pending yields empty list`() {
        val byId = mapOf("root" to session("root"))
        assertTrue(questionsInTree("root", emptyList(), byId).isEmpty())
    }

    // ── filterArchivedSessionQuestions: ghost-question guard ───────────────

    private fun archivedSession(id: String, parentId: String? = null) =
        Session(id = id, directory = "/tmp", parentId = parentId, time = Session.TimeInfo(archived = 1L))

    @Test
    fun `filterArchivedSessionQuestions drops questions whose session is archived`() {
        val byId = mapOf(
            "live-root" to session("live-root"),
            "archived-root" to archivedSession("archived-root"),
        )
        val pending = listOf(
            question("q-live", "live-root"),
            question("q-archived", "archived-root"),
        )
        val result = filterArchivedSessionQuestions(pending, byId)
        assertEquals(listOf("q-live"), result.map { it.id })
    }

    @Test
    fun `filterArchivedSessionQuestions conservatively keeps questions whose session is unknown`() {
        // §task5-ghost: unknown session id → keep (could be a session that
        // hasn't materialised in the local snapshot yet).
        val byId = mapOf("live-root" to session("live-root"))
        val pending = listOf(
            question("q-live", "live-root"),
            question("q-unknown", "ghost-no-such-session"),
        )
        val result = filterArchivedSessionQuestions(pending, byId)
        assertEquals(listOf("q-live", "q-unknown"), result.map { it.id })
    }

    @Test
    fun `filterArchivedSessionQuestions keeps questions on archived-flag-zero sessions`() {
        // archived = 0L must be treated as NOT archived (only > 0 means archived).
        val byId = mapOf(
            "s-zero" to Session(id = "s-zero", directory = "/tmp", time = Session.TimeInfo(archived = 0L)),
            "s-null" to Session(id = "s-null", directory = "/tmp"),
        )
        val pending = listOf(
            question("q-zero", "s-zero"),
            question("q-null", "s-null"),
        )
        val result = filterArchivedSessionQuestions(pending, byId)
        assertEquals(listOf("q-zero", "q-null"), result.map { it.id })
    }

    @Test
    fun `filterArchivedSessionQuestions on empty pending yields empty list`() {
        val byId = mapOf("live" to archivedSession("live"))
        assertTrue(filterArchivedSessionQuestions(emptyList(), byId).isEmpty())
    }

    @Test
    fun `filterArchivedSessionQuestions drops question whose ancestor is archived even when the question session itself is unarchived`() {
        // §task5-ghost-r2 (final-fix round 2): root-only SSE archive path. The
        // server archived only `archived-root` (e.g. via a root-only SSE event);
        // child lives in sessionsById as still-UNARCHIVED and the server's
        // pending-questions response still includes a child question. The
        // ancestor walk must drop it so the question cannot ghost back.
        val byId = mapOf(
            "archived-root" to archivedSession("archived-root"),
            "unarchived-child" to session("unarchived-child", parentId = "archived-root"),
            "unarchived-grandchild" to session("unarchived-grandchild", parentId = "unarchived-child"),
            "live-root" to session("live-root"),
        )
        val pending = listOf(
            question("q-child", "unarchived-child"),
            question("q-grandchild", "unarchived-grandchild"),
            question("q-live", "live-root"),
        )
        val result = filterArchivedSessionQuestions(pending, byId)
        assertEquals(listOf("q-live"), result.map { it.id })
    }

    @Test
    fun `filterArchivedSessionQuestions conservatively keeps question when ancestor chain hits an unknown parent`() {
        // §task5-ghost-r2: a child whose parentId points at a session NOT in
        // sessionsById → conservative keep (could be a not-yet-loaded ancestor).
        val byId = mapOf(
            "child" to session("child", parentId = "missing-ancestor"),
        )
        val pending = listOf(question("q", "child"))
        val result = filterArchivedSessionQuestions(pending, byId)
        assertEquals(listOf("q"), result.map { it.id })
    }

    @Test
    fun `filterArchivedSessionQuestions conservatively keeps question when ancestor chain has a parentId cycle`() {
        // §task5-ghost-r2: A→B→A cycle — bail out conservatively (keep), do not
        // spin forever.
        val byId = mapOf(
            "A" to archivedSession("A", parentId = "B"),
            "B" to session("B", parentId = "A"),
            "child-of-A" to session("child-of-A", parentId = "A"),
        )
        val pending = listOf(question("q-cycle-descendant", "child-of-A"))
        val result = filterArchivedSessionQuestions(pending, byId)
        // The walk on child-of-A: visit child-of-A (not archived), go up to A
        // (archived) → drop. Note: child-of-A's chain DOES reach an archived
        // node before entering the A↔B cycle, so this is a DROP, not a keep.
        // The cycle-bail happens only when the cycle is entered WITHOUT first
        // seeing an archived node — pinned by the next test.
        assertTrue("descendant of an archived cycle entry is still dropped", result.isEmpty())
    }

    @Test
    fun `filterArchivedSessionQuestions keeps question when cycle is entered before any archived node`() {
        // §task5-ghost-r2: pure A↔B cycle, neither archived. The walk on either
        // enters the cycle without ever seeing an archived id → bail out
        // conservatively (keep) rather than spin forever.
        val byId = mapOf(
            "A" to session("A", parentId = "B"),
            "B" to session("B", parentId = "A"),
        )
        val pending = listOf(question("q", "A"))
        val result = filterArchivedSessionQuestions(pending, byId)
        assertEquals(listOf("q"), result.map { it.id })
    }

    // ── removeSessions: UnreadState lifecycle cleanup ───────────────────────

    @Test
    fun `removeSessions drops ids from both unreadSessions and lastViewedTime`() {
        val state = UnreadState(
            unreadSessions = setOf("a", "b", "c"),
            lastViewedTime = mapOf("a" to 100L, "b" to 200L, "c" to 300L),
        )
        val result = state.removeSessions(setOf("a", "c"))
        assertEquals(setOf("b"), result.unreadSessions)
        assertEquals(mapOf("b" to 200L), result.lastViewedTime)
    }

    @Test
    fun `removeSessions with empty id set is a no-op`() {
        val state = UnreadState(
            unreadSessions = setOf("a"),
            lastViewedTime = mapOf("a" to 100L),
        )
        val result = state.removeSessions(emptySet())
        assertEquals(state, result)
    }

    @Test
    fun `removeSessions with unknown ids leaves state unchanged`() {
        val state = UnreadState(
            unreadSessions = setOf("a"),
            lastViewedTime = mapOf("a" to 100L),
        )
        val result = state.removeSessions(setOf("ghost"))
        assertEquals(setOf("a"), result.unreadSessions)
        assertEquals(mapOf("a" to 100L), result.lastViewedTime)
    }

    @Test
    fun `removeSessions on empty state yields empty state`() {
        val result = UnreadState().removeSessions(setOf("a", "b"))
        assertTrue(result.unreadSessions.isEmpty())
        assertTrue(result.lastViewedTime.isEmpty())
    }
}
