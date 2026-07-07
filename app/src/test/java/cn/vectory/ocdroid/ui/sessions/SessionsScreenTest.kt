package cn.vectory.ocdroid.ui.sessions

import cn.vectory.ocdroid.data.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §grouping-rewrite Round-3 C3: unit tests for [buildWorkdirGroups] — the pure
 * derivation of the "Connected projects" workdir groups for SessionsScreen.
 *
 * Extracted from the composable specifically so the C3 gating logic
 * (recent_workdirs membership is the source of truth for visibility) could be
 * unit-tested. Pre-C3 the derivation lived inline in `derivedStateOf`; the
 * disconnect dialog promised "removed from the list" unconditionally but a
 * workdir with live sessions stayed visible because live-session grouping ran
 * unconditionally and recent_workdirs only added 0-live fallback entries.
 *
 * The C3 fix: visible set = recentWorkdirs ∪ {draftWorkdir}; live sessions
 * for INVISIBLE workdirs are filtered out. These tests pin that contract.
 */
class SessionsScreenTest {

    // ─────────── C3: disconnect hides a workdir EVEN WITH live sessions ────

    @Test
    fun `C3 disconnect — workdir with live sessions is hidden when absent from recentWorkdirs`() {
        // Two workdirs, both with live (parentId==null, non-archived) sessions.
        // Both are in recentWorkdirs initially → both visible.
        val sessionA1 = session(id = "a1", dir = "/proj-a", updated = 200L)
        val sessionA2 = session(id = "a2", dir = "/proj-a", updated = 100L)
        val sessionB1 = session(id = "b1", dir = "/proj-b", updated = 150L)
        val allSessions = listOf(sessionA1, sessionA2, sessionB1)

        // Initial state: both workdirs are "connected" → both visible, each
        // with its live session(s) attached.
        val beforeDisconnect = buildWorkdirGroups(
            allSessions = allSessions,
            recentWorkdirs = listOf("/proj-a", "/proj-b"),
            draftWorkdir = null,
        )
        assertEquals(2, beforeDisconnect.size)
        val groupA = beforeDisconnect.first { it.first == "/proj-a" }
        assertEquals(listOf(sessionA1, sessionA2), groupA.second) // sorted by updated desc
        val groupB = beforeDisconnect.first { it.first == "/proj-b" }
        assertEquals(listOf(sessionB1), groupB.second)

        // Disconnect /proj-a: removeRecentWorkdir evicts it from the persistent
        // list, but allSessions STILL CONTAINS its live sessions (the server
        // list refresh hasn't happened yet — this is the common race the C3
        // fix targets). Pre-C3, /proj-a would stay visible (sessions anchored
        // it in groupsByKey); post-C3, the recent_workdirs membership gate
        // drops it.
        val afterDisconnect = buildWorkdirGroups(
            allSessions = allSessions, // unchanged — live sessions still here
            recentWorkdirs = listOf("/proj-b"), // /proj-a removed
            draftWorkdir = null,
        )
        assertEquals(
            "disconnect hid /proj-a even though its sessions are still in allSessions",
            listOf("/proj-b"),
            afterDisconnect.map { it.first },
        )
        // The live sessions for /proj-a must NOT silently re-introduce it.
        assertFalse(
            "/proj-a must not appear after disconnect",
            afterDisconnect.any { it.first == "/proj-a" },
        )
        // /proj-b's group is unchanged.
        assertEquals(listOf(sessionB1), afterDisconnect.single().second)
    }

    @Test
    fun `C3 — workdir with live sessions stays visible while still in recentWorkdirs`() {
        // Sanity counterpart to the disconnect test: as long as the workdir is
        // in recentWorkdirs, its live sessions are surfaced (the gate does not
        // accidentally hide legitimately-connected workdirs).
        val sessionA = session(id = "a", dir = "/proj-a", updated = 100L)
        val result = buildWorkdirGroups(
            allSessions = listOf(sessionA),
            recentWorkdirs = listOf("/proj-a"),
            draftWorkdir = null,
        )
        assertEquals(1, result.size)
        assertEquals("/proj-a", result.single().first)
        assertEquals(listOf(sessionA), result.single().second)
    }

    // ─────────── draftWorkdir preservation ─────────────────────────────────

    @Test
    fun `draftWorkdir stays visible even when not in recentWorkdirs`() {
        // The "Connect new project" draft must stay visible while the user is
        // mid-typing — even though it is not yet in recentWorkdirs (it only
        // lands there once a session is actually created).
        val draft = "/home/me/new-project"
        val result = buildWorkdirGroups(
            allSessions = emptyList(),
            recentWorkdirs = listOf("/some-other-proj"),
            draftWorkdir = draft,
        )
        assertTrue(
            "draftWorkdir must appear in the output even when not in recentWorkdirs",
            result.any { it.first == draft },
        )
        // The draft entry carries an empty session list (placeholder row).
        val draftGroup = result.first { it.first == draft }
        assertTrue("draft group has no live sessions yet", draftGroup.second.isEmpty())
        // recentWorkdirs entry is still present.
        assertTrue(
            "recentWorkdirs entry is also visible",
            result.any { it.first == "/some-other-proj" },
        )
    }

    @Test
    fun `draftWorkdir that normalizes onto an existing recent entry does not duplicate`() {
        // If the user types a draft whose normalized key already matches a
        // recent entry (e.g. "/proj-a" vs "proj-a"), the two collapse to one
        // group — no duplicate row.
        val result = buildWorkdirGroups(
            allSessions = emptyList(),
            recentWorkdirs = listOf("/proj-a"),
            draftWorkdir = "proj-a/", // trailing slash — normalizes to "proj-a"
        )
        assertEquals(
            "draft + recent normalize together → one group, not two",
            1,
            result.size,
        )
    }

    // ─────────── preserved pre-C3 behaviour ────────────────────────────────

    @Test
    fun `0-live recent workdir still appears as a placeholder group`() {
        // Visible workdir with zero live sessions stays in the output so the
        // workdir_empty_placeholder row can render. Pre-C3 this was the only
        // path recent_workdirs handled; C3 preserves it.
        val result = buildWorkdirGroups(
            allSessions = listOf(session(id = "other", dir = "/other-proj")),
            recentWorkdirs = listOf("/proj-a"),
            draftWorkdir = null,
        )
        assertTrue(
            "/proj-a appears even though no session in allSessions targets it",
            result.any { it.first == "/proj-a" },
        )
        val projAGroup = result.first { it.first == "/proj-a" }
        assertTrue("0-live workdir has an empty session list", projAGroup.second.isEmpty())
    }

    @Test
    fun `sub-agent and archived sessions do not anchor a group`() {
        // parentId != null (sub-agent) → skipped, even if its workdir is in
        // recentWorkdirs (the sub-agent's directory may legitimately match a
        // connected project's directory; it must NOT inflate that group).
        // Archived sessions → also skipped (matches the pre-C3 filter).
        val parent = session(id = "p", dir = "/proj-a", updated = 100L)
        val subAgent = session(id = "sub", dir = "/proj-a", parentId = "p", updated = 200L)
        val archived = session(
            id = "arch", dir = "/proj-a",
            archived = 1_700_000_000_000L,
            updated = 300L,
        )
        val result = buildWorkdirGroups(
            allSessions = listOf(parent, subAgent, archived),
            recentWorkdirs = listOf("/proj-a"),
            draftWorkdir = null,
        )
        val groupA = result.single { it.first == "/proj-a" }
        assertEquals(
            "only the non-archived root parent session is attached",
            listOf(parent),
            groupA.second,
        )
    }

    @Test
    fun `sub-agent session whose workdir is NOT in recentWorkdirs stays hidden`() {
        // Defensive: a sub-agent pointing at a non-connected workdir must not
        // surface that workdir. (The parentId filter already drops it, but
        // this guards against a future regression where the parentId check is
        // reordered after the visibility gate.)
        val subAgent = session(id = "sub", dir = "/ghost", parentId = "p", updated = 100L)
        val result = buildWorkdirGroups(
            allSessions = listOf(subAgent),
            recentWorkdirs = emptyList(),
            draftWorkdir = null,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `normalization collapses trailing slash variants`() {
        // Phone-created "/proj-a" vs web-created "/proj-a/" must group together
        // (Fix #6b — preserved by the C3 refactor).
        val s1 = session(id = "s1", dir = "/proj-a", updated = 100L)
        val s2 = session(id = "s2", dir = "/proj-a/", updated = 200L)
        val result = buildWorkdirGroups(
            allSessions = listOf(s1, s2),
            recentWorkdirs = listOf("/proj-a"),
            draftWorkdir = null,
        )
        assertEquals(
            "trailing-slash variants collapse into one group",
            1,
            result.size,
        )
        assertEquals(2, result.single().second.size)
    }

    @Test
    fun `output is sorted by normalized directory for stable ordering`() {
        // Multiple visible workdirs — output ordering matches normalized key
        // (alphabetical), not recentWorkdirs order, so the list does not jump
        // when MRU reorders recentWorkdirs.
        val result = buildWorkdirGroups(
            allSessions = emptyList(),
            recentWorkdirs = listOf("/zeta", "/alpha", "/mike"),
            draftWorkdir = null,
        )
        assertEquals(
            listOf("/alpha", "/mike", "/zeta"),
            result.map { it.first },
        )
    }

    @Test
    fun `display dir prefers absolute leading-slash form`() {
        // When sessions supply both "proj-a" and "/proj-a" variants, the
        // display dir prefers the absolute (leading-/) form for VM calls.
        val s1 = session(id = "s1", dir = "proj-a", updated = 100L)
        val s2 = session(id = "s2", dir = "/proj-a", updated = 200L)
        val result = buildWorkdirGroups(
            allSessions = listOf(s1, s2),
            recentWorkdirs = listOf("/proj-a"),
            draftWorkdir = null,
        )
        assertEquals("/proj-a", result.single().first)
    }

    @Test
    fun `empty recent + null draft + empty sessions yields empty list`() {
        assertTrue(
            buildWorkdirGroups(
                allSessions = emptyList(),
                recentWorkdirs = emptyList(),
                draftWorkdir = null,
            ).isEmpty(),
        )
    }

    // ─────────── fixtures ──────────────────────────────────────────────────

    private fun session(
        id: String,
        dir: String,
        parentId: String? = null,
        updated: Long? = null,
        archived: Long? = null,
    ): Session = Session(
        id = id,
        directory = dir,
        parentId = parentId,
        time = Session.TimeInfo(
            created = updated,
            updated = updated,
            archived = archived,
        ),
    )
}
