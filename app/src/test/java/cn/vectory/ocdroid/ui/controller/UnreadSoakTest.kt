package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §unread-soak: pure unit tests for [evaluateUnread]. Each test drives the
 * evaluator with synthetic inputs + a controlled [now] and asserts on the
 * returned [UnreadSoakResult.newIdleSince] / [UnreadSoakResult.rootsToMarkUnread].
 * No slice, no controller, no clock — just the pure function.
 *
 * Branch coverage mirrors the LOCKED spec matrix:
 *  - rising-edge sets idleSince
 *  - busy (root or descendant) resets the soak
 *  - 10s + not-viewed ⇒ mark + consume (stamp cleared)
 *  - viewed-since-idle ⇒ no mark (consumed)
 *  - current-session ⇒ no mark + clear
 *  - child-busy blocks marking (the "dispatched to child" over-count case)
 *  - descendant-busy (grandchild) blocks
 *  - archived root skipped
 *  - unknown-status descendant treated as NOT idle (blocks soak)
 */
class UnreadSoakTest {

    private val idle = SessionStatus(type = "idle")
    private val busy = SessionStatus(type = "busy")
    private val soak = 10_000L

    // §unread-semantics (F3): `updated` mirrors Session.time.updated — the
    // "new content" signal bumped by every inbound message. Defaults to null
    // (no content) so mark-asserting tests must opt into a timestamp to prove
    // they exercise the new-message branch.
    private fun root(id: String, archived: Boolean = false, updated: Long? = null) =
        Session(id = id, directory = "/x", parentId = null, time = if (archived) Session.TimeInfo(archived = 1L) else Session.TimeInfo(updated = updated))

    private fun child(id: String, parent: String, updated: Long? = null) =
        Session(id = id, directory = "/x", parentId = parent, time = Session.TimeInfo(updated = updated))

    /**
     * Convenience: evaluate over a flat [sessions] list with no directory /
     * child buckets. Most tests only need the top-level sessions store.
     */
    private fun eval(
        sessions: List<Session>,
        sessionStatuses: Map<String, SessionStatus>,
        currentSessionId: String? = null,
        lastViewedTime: Map<String, Long> = emptyMap(),
        idleSince: Map<String, Long> = emptyMap(),
        now: Long = 0L,
        soakMs: Long = soak,
        directorySessions: Map<String, List<Session>> = emptyMap(),
        childSessions: Map<String, List<Session>> = emptyMap(),
        completeRootIds: Set<String> = (sessions + directorySessions.values.flatten())
            .filter { it.parentId == null }
            .mapTo(mutableSetOf()) { it.id },
    ) = evaluateUnread(
        sessions = sessions,
        sessionStatuses = sessionStatuses,
        childSessions = childSessions,
        directorySessions = directorySessions,
        currentSessionId = currentSessionId,
        lastViewedTime = lastViewedTime,
        idleSince = idleSince,
        now = now,
        soakMs = soakMs,
        completeRootIds = completeRootIds,
    )

    // ── rising edge ────────────────────────────────────────────────────────

    @Test
    fun `incomplete root cannot start a soak even when every locally known node is idle`() {
        val r = eval(
            sessions = listOf(root("A")),
            sessionStatuses = mapOf("A" to idle),
            completeRootIds = emptySet(),
            now = 1_000L,
        )

        assertTrue(r.newIdleSince.isEmpty())
        assertTrue(r.rootsToMarkUnread.isEmpty())
    }

    @Test
    fun `rising edge - root all-idle with no prior stamp sets idleSince to now`() {
        val r = eval(
            sessions = listOf(root("A")),
            sessionStatuses = mapOf("A" to idle),
            now = 1000L,
        )
        assertEquals(mapOf("A" to 1000L), r.newIdleSince)
        assertTrue(r.rootsToMarkUnread.isEmpty())
    }

    @Test
    fun `rising edge does not mark until soak elapses`() {
        // First tick: stamp set.
        val first = eval(
            sessions = listOf(root("A")),
            sessionStatuses = mapOf("A" to idle),
            idleSince = emptyMap(),
            now = 1000L,
        )
        assertEquals(mapOf("A" to 1000L), first.newIdleSince)
        assertTrue(first.rootsToMarkUnread.isEmpty())

        // 5s later: still soaking, no mark.
        val second = eval(
            sessions = listOf(root("A")),
            sessionStatuses = mapOf("A" to idle),
            idleSince = first.newIdleSince,
            now = 6000L,
        )
        assertEquals(mapOf("A" to 1000L), second.newIdleSince)
        assertTrue(second.rootsToMarkUnread.isEmpty())
    }

    // ── soak completion + mark + consume ───────────────────────────────────

    @Test
    fun `soak complete - 10s plus not viewed marks and consumes stamp`() {
        val r = eval(
            sessions = listOf(root("A", updated = 2_000L)),
            sessionStatuses = mapOf("A" to idle),
            // stamp 10s ago, never viewed.
            idleSince = mapOf("A" to 1000L),
            lastViewedTime = emptyMap(),
            now = 11_000L,
        )
        assertTrue("A must be marked unread", "A" in r.rootsToMarkUnread)
        assertEquals("cycle stamp remains until a busy reset", 1000L, r.newIdleSince["A"])
        assertTrue("completion is stamped viewed to consume the cycle", "A" in r.rootsToStampViewed)
    }

    @Test
    fun `soak complete - exactly 10s boundary marks`() {
        val r = eval(
            sessions = listOf(root("A", updated = 2_000L)),
            sessionStatuses = mapOf("A" to idle),
            idleSince = mapOf("A" to 1000L),
            now = 11_000L, // now - stamp == soak exactly
        )
        assertTrue("A must be marked at exact soak boundary", "A" in r.rootsToMarkUnread)
    }

    @Test
    fun `soak complete - just under 10s does not mark`() {
        val r = eval(
            sessions = listOf(root("A")),
            sessionStatuses = mapOf("A" to idle),
            idleSince = mapOf("A" to 1000L),
            now = 10_999L, // 1ms short
        )
        assertTrue(r.rootsToMarkUnread.isEmpty())
        assertEquals(mapOf("A" to 1000L), r.newIdleSince)
    }

    // ── viewed-since-idle ──────────────────────────────────────────────────

    @Test
    fun `viewed since idle - lastViewedTime at or after stamp does not mark`() {
        val r = eval(
            sessions = listOf(root("A")),
            sessionStatuses = mapOf("A" to idle),
            idleSince = mapOf("A" to 1000L),
            // viewed AFTER the soak started.
            lastViewedTime = mapOf("A" to 1500L),
            now = 11_000L,
        )
        assertFalse("viewed-since-idle must not mark", "A" in r.rootsToMarkUnread)
        assertEquals("cycle remains consumed until a busy reset", 1000L, r.newIdleSince["A"])
    }

    @Test
    fun `viewed exactly at stamp boundary does not mark`() {
        val r = eval(
            sessions = listOf(root("A")),
            sessionStatuses = mapOf("A" to idle),
            idleSince = mapOf("A" to 1000L),
            // viewed == stamp (boundary: viewed < stamp is the mark condition).
            lastViewedTime = mapOf("A" to 1000L),
            now = 11_000L,
        )
        assertFalse("viewed == stamp must not mark (< is strict)", "A" in r.rootsToMarkUnread)
    }

    @Test
    fun `viewed before idle - lastViewedTime before stamp still marks`() {
        val r = eval(
            sessions = listOf(root("A", updated = 800L)),
            sessionStatuses = mapOf("A" to idle),
            idleSince = mapOf("A" to 1000L),
            // viewed BEFORE the soak started (stale view); a message arrived
            // since the view (updated 800 > viewed 500).
            lastViewedTime = mapOf("A" to 500L),
            now = 11_000L,
        )
        assertTrue("viewed-before-idle must mark", "A" in r.rootsToMarkUnread)
    }

    // ── busy resets ────────────────────────────────────────────────────────

    @Test
    fun `root busy resets the soak stamp`() {
        val r = eval(
            sessions = listOf(root("A")),
            sessionStatuses = mapOf("A" to busy),
            idleSince = mapOf("A" to 1000L),
            now = 5000L,
        )
        assertNull("busy root must clear its stamp", r.newIdleSince["A"])
        assertTrue(r.rootsToMarkUnread.isEmpty())
    }

    @Test
    fun `re-busy after partial soak clears stamp so a fresh soak restarts`() {
        // soak 5s in, then root goes busy → stamp cleared.
        val afterBusy = eval(
            sessions = listOf(root("A")),
            sessionStatuses = mapOf("A" to busy),
            idleSince = mapOf("A" to 1000L),
            now = 6000L,
        )
        assertNull(afterBusy.newIdleSince["A"])

        // root goes idle again at 7000 → fresh stamp.
        val freshIdle = eval(
            sessions = listOf(root("A")),
            sessionStatuses = mapOf("A" to idle),
            idleSince = afterBusy.newIdleSince,
            now = 7000L,
        )
        assertEquals(mapOf("A" to 7000L), freshIdle.newIdleSince)
    }

    // ── current session ────────────────────────────────────────────────────

    @Test
    fun `current session is never marked and clears its stamp`() {
        val r = eval(
            sessions = listOf(root("A")),
            sessionStatuses = mapOf("A" to idle),
            currentSessionId = "A",
            // Pretend a stamp existed; current must clear it.
            idleSince = mapOf("A" to 1000L),
            now = 11_000L,
        )
        assertFalse("current root must not be marked", "A" in r.rootsToMarkUnread)
        assertEquals("current root retains the cycle stamp", 1000L, r.newIdleSince["A"])
        assertTrue("watching the current root consumes the cycle", "A" in r.rootsToStampViewed)
    }

    @Test
    fun `current session with a busy descendant still clears the root stamp`() {
        val r = eval(
            sessions = listOf(root("A"), child("C", "A")),
            sessionStatuses = mapOf("A" to idle, "C" to busy),
            currentSessionId = "A",
            idleSince = mapOf("A" to 1000L),
            now = 11_000L,
        )
        // isCurrent takes precedence over the !allIdle branch — both clear, no mark.
        assertFalse("A" in r.rootsToMarkUnread)
        assertNull(r.newIdleSince["A"])
    }

    @Test
    fun `viewing a child suppresses its root at the completion boundary`() {
        val r = eval(
            sessions = listOf(root("A"), child("C", "A")),
            sessionStatuses = mapOf("A" to idle, "C" to idle),
            currentSessionId = "C",
            idleSince = mapOf("A" to 1000L),
            now = 11_000L,
        )

        assertFalse("the open child's root must not be marked", "A" in r.rootsToMarkUnread)
        assertTrue("the open child's root is stamped viewed", "A" in r.rootsToStampViewed)
    }

    // ── child / descendant busy blocks ─────────────────────────────────────

    @Test
    fun `child busy blocks root soak - the dispatched-to-child over-count case`() {
        // Root idle, child busy: subtree not all-idle → reset, never mark.
        val r = eval(
            sessions = listOf(root("A"), child("C", "A")),
            sessionStatuses = mapOf("A" to idle, "C" to busy),
            idleSince = mapOf("A" to 1000L),
            now = 11_000L,
        )
        assertFalse("child busy must block root mark", "A" in r.rootsToMarkUnread)
        assertNull("child busy must reset root stamp", r.newIdleSince["A"])
    }

    @Test
    fun `grandchild busy blocks root soak`() {
        // Root idle, child idle, grandchild busy.
        val r = eval(
            sessions = listOf(root("A"), child("C", "A"), child("G", "C")),
            sessionStatuses = mapOf("A" to idle, "C" to idle, "G" to busy),
            idleSince = mapOf("A" to 1000L),
            now = 11_000L,
        )
        assertFalse("grandchild busy must block root mark", "A" in r.rootsToMarkUnread)
        assertNull(r.newIdleSince["A"])
    }

    @Test
    fun `full subtree idle including descendants crosses soak and marks`() {
        val r = eval(
            sessions = listOf(root("A", updated = 2_000L), child("C", "A"), child("G", "C")),
            sessionStatuses = mapOf("A" to idle, "C" to idle, "G" to idle),
            idleSince = mapOf("A" to 1000L),
            now = 11_000L,
        )
        assertTrue("all-idle subtree must mark root", "A" in r.rootsToMarkUnread)
    }

    // ── unknown status ─────────────────────────────────────────────────────

    @Test
    fun `unknown descendant status treated as not idle blocks soak`() {
        // Root idle, child present in tree but ABSENT from sessionStatuses →
        // unknown ⇒ NOT idle ⇒ blocks.
        val r = eval(
            sessions = listOf(root("A"), child("C", "A")),
            sessionStatuses = mapOf("A" to idle), // C unknown
            idleSince = mapOf("A" to 1000L),
            now = 11_000L,
        )
        assertFalse("unknown-status child must block mark", "A" in r.rootsToMarkUnread)
        assertNull(r.newIdleSince["A"])
    }

    @Test
    fun `unknown root status does not start soak`() {
        val r = eval(
            sessions = listOf(root("A")),
            sessionStatuses = emptyMap(), // root unknown
            now = 1000L,
        )
        assertTrue(r.newIdleSince.isEmpty())
        assertTrue(r.rootsToMarkUnread.isEmpty())
    }

    // ── archived / multi-root ──────────────────────────────────────────────

    @Test
    fun `archived root is skipped entirely`() {
        val r = eval(
            sessions = listOf(root("A", archived = true), root("B")),
            sessionStatuses = mapOf("A" to idle, "B" to idle),
            idleSince = mapOf("A" to 1000L), // stale stamp for archived root
            now = 11_000L,
        )
        assertFalse("archived root must not be marked", "A" in r.rootsToMarkUnread)
        assertNull("archived root stamp pruned", r.newIdleSince["A"])
        // B starts its soak normally.
        assertTrue("B" in r.newIdleSince)
    }

    @Test
    fun `multiple roots evaluated independently`() {
        val r = eval(
            sessions = listOf(root("A", updated = 2_000L), root("B"), root("C")),
            sessionStatuses = mapOf("A" to idle, "B" to idle, "C" to busy),
            // A past soak threshold (with new content); B mid-soak; C busy.
            idleSince = mapOf("A" to 1000L, "B" to 9000L),
            now = 11_000L,
        )
        assertTrue("A crosses threshold", "A" in r.rootsToMarkUnread)
        assertFalse("B still soaking", "B" in r.rootsToMarkUnread)
        assertEquals(mapOf("A" to 1000L, "B" to 9000L), r.newIdleSince)
    }

    @Test
    fun `roots sourced from directorySessions too`() {
        val r = eval(
            sessions = emptyList(),
            directorySessions = mapOf("/x" to listOf(root("A"))),
            sessionStatuses = mapOf("A" to idle),
            now = 1000L,
        )
        assertEquals(mapOf("A" to 1000L), r.newIdleSince)
    }

    // ── pruning ────────────────────────────────────────────────────────────

    @Test
    fun `orphaned idleSince entry for missing root is pruned`() {
        val r = eval(
            sessions = listOf(root("A")),
            sessionStatuses = mapOf("A" to idle),
            // "ghost" is not in any session store → pruned.
            idleSince = mapOf("A" to 1000L, "ghost" to 500L),
            now = 11_000L,
        )
        assertNull("ghost stamp pruned", r.newIdleSince["ghost"])
    }

    // ── §unread-semantics (F3): new-message gating ────────────────────────

    @Test
    fun `F3 - soak complete with NO new message does not mark`() {
        // Pure idle settling, no content ever (updated == null). The F3
        // regression: this MUST NOT badge — the old contract would have marked.
        val r = eval(
            sessions = listOf(root("A")), // updated defaults to null
            sessionStatuses = mapOf("A" to idle),
            idleSince = mapOf("A" to 1000L),
            lastViewedTime = emptyMap(),
            now = 11_000L,
        )
        assertFalse("no new message must not mark", "A" in r.rootsToMarkUnread)
        assertEquals("stamp retained as edge memory", 1000L, r.newIdleSince["A"])
        assertFalse("nothing consumed", "A" in r.rootsToStampViewed)
    }

    @Test
    fun `F3 - soak complete with new message marks`() {
        val r = eval(
            sessions = listOf(root("A", updated = 2_000L)),
            sessionStatuses = mapOf("A" to idle),
            idleSince = mapOf("A" to 1000L),
            lastViewedTime = emptyMap(),
            now = 11_000L,
        )
        assertTrue("new message + soak complete must mark", "A" in r.rootsToMarkUnread)
    }

    @Test
    fun `F3 - message older than last view does not mark`() {
        // User viewed at 500; the only message landed at 300 (before the view).
        // No UNVIEWED new content ⇒ no mark, even though soak completed.
        val r = eval(
            sessions = listOf(root("A", updated = 300L)),
            sessionStatuses = mapOf("A" to idle),
            idleSince = mapOf("A" to 1000L),
            lastViewedTime = mapOf("A" to 500L),
            now = 11_000L,
        )
        assertFalse("message older than last view must not mark", "A" in r.rootsToMarkUnread)
    }

    @Test
    fun `F3 - message exactly at last view does not mark`() {
        // updated == viewed (boundary): no strictly-newer content ⇒ no mark.
        val r = eval(
            sessions = listOf(root("A", updated = 500L)),
            sessionStatuses = mapOf("A" to idle),
            idleSince = mapOf("A" to 1000L),
            lastViewedTime = mapOf("A" to 500L),
            now = 11_000L,
        )
        assertFalse("updated == viewed must not mark (> is strict)", "A" in r.rootsToMarkUnread)
    }

    @Test
    fun `F3 - new message on a child marks the root`() {
        // Root itself has no updated content, but a descendant received a
        // message newer than the root's last view ⇒ subtree scan marks root.
        val r = eval(
            sessions = listOf(root("A"), child("C", "A", updated = 2_000L)),
            sessionStatuses = mapOf("A" to idle, "C" to idle),
            idleSince = mapOf("A" to 1000L),
            lastViewedTime = mapOf("A" to 500L),
            now = 11_000L,
        )
        assertTrue("child's new message must mark the root", "A" in r.rootsToMarkUnread)
    }

    @Test
    fun `F3 - new message on a grandchild marks the root`() {
        val r = eval(
            sessions = listOf(
                root("A"),
                child("C", "A"),
                child("G", "C", updated = 2_000L),
            ),
            sessionStatuses = mapOf("A" to idle, "C" to idle, "G" to idle),
            idleSince = mapOf("A" to 1000L),
            lastViewedTime = mapOf("A" to 500L),
            now = 11_000L,
        )
        assertTrue("grandchild's new message must mark the root", "A" in r.rootsToMarkUnread)
    }

    @Test
    fun `F3 - never-viewed empty root does not mark`() {
        // Never opened AND no content at all ⇒ nothing unread to surface.
        val r = eval(
            sessions = listOf(root("A")), // updated null, never viewed
            sessionStatuses = mapOf("A" to idle),
            idleSince = mapOf("A" to 1000L),
            lastViewedTime = emptyMap(),
            now = 11_000L,
        )
        assertFalse("empty never-viewed root must not mark", "A" in r.rootsToMarkUnread)
    }

    @Test
    fun `F3 - never-viewed root with content marks`() {
        // Never opened but the subtree HAS content ⇒ baseline 0 ⇒ badges once.
        val r = eval(
            sessions = listOf(root("A", updated = 2_000L)),
            sessionStatuses = mapOf("A" to idle),
            idleSince = mapOf("A" to 1000L),
            lastViewedTime = emptyMap(),
            now = 11_000L,
        )
        assertTrue("never-viewed root with content must mark", "A" in r.rootsToMarkUnread)
    }

    @Test
    fun `F3 - new message arriving after a prior no-content soak then marks`() {
        // Tick 1: settled idle, no content ⇒ no mark, stamp retained.
        val first = eval(
            sessions = listOf(root("A")),
            sessionStatuses = mapOf("A" to idle),
            idleSince = mapOf("A" to 1000L),
            lastViewedTime = emptyMap(),
            now = 11_000L,
        )
        assertFalse("no-content soak must not mark", "A" in first.rootsToMarkUnread)
        // Tick 2: a message lands (updated bumped) ⇒ now marks.
        val second = eval(
            sessions = listOf(root("A", updated = 12_000L)),
            sessionStatuses = mapOf("A" to idle),
            idleSince = first.newIdleSince,
            lastViewedTime = emptyMap(),
            now = 25_000L,
        )
        assertTrue("message arriving after a no-content soak must mark", "A" in second.rootsToMarkUnread)
    }

    // ── determinism ────────────────────────────────────────────────────────

    @Test
    fun `deterministic - same inputs yield same outputs`() {
        val sessions = listOf(root("A"), child("C", "A"))
        val statuses = mapOf("A" to idle, "C" to idle)
        val r1 = eval(sessions, statuses, idleSince = mapOf("A" to 1000L), now = 11_000L)
        val r2 = eval(sessions, statuses, idleSince = mapOf("A" to 1000L), now = 11_000L)
        assertEquals(r1, r2)
    }

    @Test
    fun `completed all-idle cycle does not mark again without an intervening busy state`() {
        val completed = eval(
            sessions = listOf(root("A", updated = 2_000L)),
            sessionStatuses = mapOf("A" to idle),
            idleSince = mapOf("A" to 1000L),
            now = 11_000L,
        )
        val repeated = eval(
            sessions = listOf(root("A", updated = 2_000L)),
            sessionStatuses = mapOf("A" to idle),
            idleSince = completed.newIdleSince,
            lastViewedTime = mapOf("A" to 11_000L),
            now = 30_000L,
        )

        assertTrue("first threshold crossing marks", "A" in completed.rootsToMarkUnread)
        assertFalse("continuous idle must not create a second mark", "A" in repeated.rootsToMarkUnread)
        assertEquals(mapOf("A" to 1000L), repeated.newIdleSince)
    }

    @Test
    fun `custom soakMs overrides the default`() {
        val r = eval(
            sessions = listOf(root("A", updated = 2_000L)),
            sessionStatuses = mapOf("A" to idle),
            idleSince = mapOf("A" to 1000L),
            now = 3500L,
            soakMs = 2000L, // 2s soak
        )
        assertTrue("custom 2s soak crossed at 2.5s", "A" in r.rootsToMarkUnread)
    }
}
