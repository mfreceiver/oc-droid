package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.ui.mergeRefreshedSessionsPreservingLocalActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMergeTest {

    @Test
    fun `fresher server data wins title and time`() {
        val refreshed = listOf(
            Session(
                id = "s1",
                directory = "/tmp/project",
                title = "Server Authoritative",
                time = Session.TimeInfo(updated = 2_000)
            )
        )
        val local = listOf(
            Session(
                id = "s1",
                directory = "/tmp/project",
                title = "Local Old",
                time = Session.TimeInfo(updated = 1_000)
            )
        )

        val merged = mergeRefreshedSessionsPreservingLocalActivity(
            refreshed, local,
            currentSessionId = null)

        assertEquals("Server Authoritative", merged.single().title)
        assertEquals(2_000L, merged.single().time?.updated)
    }

    @Test
    fun `local activity bump is preserved when server snapshot is older`() {
        // Models the real send-message flow: the local session was just bumped (newer time)
        // but the full refresh returned a snapshot that predates the bump.
        val refreshed = listOf(
            Session(
                id = "s1",
                directory = "/tmp/project",
                title = "Server Title",
                time = Session.TimeInfo(updated = 1_000)
            )
        )
        val local = listOf(
            Session(
                id = "s1",
                directory = "/tmp/project",
                title = "Server Title",
                time = Session.TimeInfo(updated = 5_000)
            )
        )

        val merged = mergeRefreshedSessionsPreservingLocalActivity(
            refreshed, local,
            currentSessionId = null)

        // Local newer time is preserved (original protection: keeps ordering/activity).
        assertEquals(5_000L, merged.single().time?.updated)
    }

    @Test
    fun `freshly upserted SSE title survives a stale concurrent refresh`() {
        // Reproduces the title-refresh bug: a session.updated SSE upserted the generated title
        // into local state (with a fresh time), then a concurrently-issued full refresh returns
        // a stale snapshot that still has the placeholder title and an older time. The merge must
        // keep the server-authoritative title we already received via SSE.
        val refreshed = listOf(
            Session(
                id = "s1",
                directory = "/tmp/project",
                title = "New session - 1700000000",
                time = Session.TimeInfo(updated = 1_000)
            )
        )
        val local = listOf(
            Session(
                id = "s1",
                directory = "/tmp/project",
                title = "Pythagorean theorem: history, proof, engineering",
                time = Session.TimeInfo(updated = 2_000)
            )
        )

        val merged = mergeRefreshedSessionsPreservingLocalActivity(
            refreshed, local,
            currentSessionId = null)

        assertEquals(
            "Pythagorean theorem: history, proof, engineering",
            merged.single().title
        )
        // The local (newer) activity timestamp is still respected.
        assertEquals(2_000L, merged.single().time?.updated)
    }

    @Test
    fun `null server time treated as older so local title is preserved`() {
        val refreshed = listOf(
            Session(id = "s1", directory = "/tmp/project", title = "Placeholder", time = null)
        )
        val local = listOf(
            Session(
                id = "s1",
                directory = "/tmp/project",
                title = "Real Title",
                time = Session.TimeInfo(updated = 2_000)
            )
        )

        val merged = mergeRefreshedSessionsPreservingLocalActivity(
            refreshed, local,
            currentSessionId = null)

        assertEquals("Real Title", merged.single().title)
        assertEquals(2_000L, merged.single().time?.updated)
    }

    @Test
    fun `no local activity signal keeps server title`() {
        // When neither side carries a time signal, the server snapshot remains authoritative.
        val refreshed = listOf(
            Session(id = "s1", directory = "/tmp/project", title = "Server", time = null)
        )
        val local = listOf(
            Session(id = "s1", directory = "/tmp/project", title = "Local", time = null)
        )

        val merged = mergeRefreshedSessionsPreservingLocalActivity(
            refreshed, local,
            currentSessionId = null)

        assertEquals("Server", merged.single().title)
    }

    @Test
    fun `preserve appends local-only pending-create session even when absent from refreshed`() {
        // §Q4-strict-sync: the preserve pass now keys on pendingCreateIds
        // (not currentSessionId / open-tabs-list). A freshly-created session
        // whose id is pending-create survives a refresh that has not yet
        // propagated it to the listing.
        val refreshed = listOf(
            Session(id = "s1", directory = "/tmp/project", title = "Refreshed")
        )
        val local = listOf(
            Session(id = "s2", directory = "/tmp/project", title = "Pending-create (local-only)")
        )

        val merged = mergeRefreshedSessionsPreservingLocalActivity(
            refreshed, local,
            currentSessionId = "s2",
            pendingCreateIds = setOf("s2"))

        val byId = merged.associateBy { it.id }
        assertTrue("pending-create session must be preserved", byId.containsKey("s2"))
        assertEquals("Pending-create (local-only)", byId["s2"]?.title)
        assertTrue("refreshed session also retained", byId.containsKey("s1"))
    }

    @Test
    fun `preserve does NOT retain local sessions that are neither current nor open`() {
        // §Q4-strict-sync: with pendingCreateIds empty (default), a local-only
        // session that is neither in the refreshed set NOR pending-create is
        // dropped on refresh (strict-sync: no ghost retention).
        val refreshed = listOf(
            Session(id = "s1", directory = "/tmp/project", title = "Refreshed")
        )
        val local = listOf(
            Session(id = "s2", directory = "/tmp/project", title = "Stale (should drop)")
        )

        val merged = mergeRefreshedSessionsPreservingLocalActivity(
            refreshed, local,
            currentSessionId = null)

        assertEquals(listOf("s1"), merged.map { it.id })
    }

    @Test
    fun `non-current session absent from refreshed is dropped`() {
        // §Q4-strict-sync (strict ghost removal): a session that is NEITHER
        // the currently-viewed session NOR in pendingCreateIds is DROPPED on
        // refresh. Only currentSessionId and pendingCreateIds survive.
        // This test guards against ghost accumulation: a stale local session
        // that is not being viewed and is not pending-create must not linger.
        val refreshed = listOf(
            Session(id = "s1", directory = "/tmp/project", title = "Refreshed")
        )
        val local = listOf(
            Session(id = "s2", directory = "/tmp/project", title = "Not current, not pending (should drop)")
        )

        val merged = mergeRefreshedSessionsPreservingLocalActivity(
            refreshed, local,
            currentSessionId = "s3",  // s2 is NOT the current session
            pendingCreateIds = emptySet())

        // s2 is neither current nor pending-create → dropped (strict-sync).
        assertEquals(listOf("s1"), merged.map { it.id })
    }

    @Test
    fun `preserve appends multiple pending-create sessions`() {
        // §Q4-strict-sync: multiple pending-create ids are all preserved.
        val refreshed = listOf(
            Session(id = "s1", directory = "/tmp/project", title = "Refreshed")
        )
        val local = listOf(
            Session(id = "s2", directory = "/tmp/project", title = "Pending 1"),
            Session(id = "s3", directory = "/tmp/project", title = "Pending 2")
        )

        val merged = mergeRefreshedSessionsPreservingLocalActivity(
            refreshed, local,
            currentSessionId = null,
            pendingCreateIds = setOf("s2", "s3"))

        val ids = merged.map { it.id }.toSet()
        assertTrue("s2 in pendingCreateIds preserved", "s2" in ids)
        assertTrue("s3 in pendingCreateIds preserved", "s3" in ids)
    }

    @Test
    fun `stale refresh that omits revert does not clear a newer local revert`() {
        // §revert-cutoff (A3-1): after a local revert the session carries revert=X with a
        // fresh time.updated. A concurrently-issued (stale) full refresh predates the revert
        // and omits the revert field. The merge must keep the local revert so the chat
        // selector stays fail-closed and does NOT release the full post-revert window.
        val revertX = Session.RevertInfo(messageId = "m-revert")
        val refreshed = listOf(
            Session(
                id = "s1",
                directory = "/tmp/project",
                title = "Server Title",
                time = Session.TimeInfo(updated = 1_000),
                revert = null // stale snapshot omits the active revert
            )
        )
        val local = listOf(
            Session(
                id = "s1",
                directory = "/tmp/project",
                title = "Server Title",
                time = Session.TimeInfo(updated = 5_000),
                revert = revertX
            )
        )

        val merged = mergeRefreshedSessionsPreservingLocalActivity(
            refreshed, local,
            currentSessionId = null)

        assertEquals(revertX, merged.single().revert)
    }

    @Test
    fun `current child session is preserved when absent from refreshed`() {
        // §currentSessionId-backstop: a viewed child session (parentId != null)
        // absent from the server's refreshed roots list MUST survive the merge.
        // Without currentSessionId in the preserve filter this child is dropped
        // and the title lookup falls back to home ("ocdroid v...").
        val refreshed = emptyList<Session>() // server's flat roots list
        val local = listOf(
            Session(
                id = "c1",
                directory = "/tmp/project",
                parentId = "p1",
                title = "Real Subagent Title",
                time = Session.TimeInfo(updated = 1_000)
            )
        )
        val merged = mergeRefreshedSessionsPreservingLocalActivity(
            refreshed, local,
            currentSessionId = "c1",
            pendingCreateIds = emptySet()
        )
        val byId = merged.associateBy { it.id }
        assertTrue("current child session c1 must be preserved", byId.containsKey("c1"))
        assertEquals("Real Subagent Title", byId["c1"]?.title)
    }

    @Test
    fun `current session preserved even alongside pending-create`() {
        // Both preserve paths active in the same call: a pending-create session
        // AND the currently-viewed session (which is NOT pending-create) both
        // survive when absent from refreshed.
        val refreshed = emptyList<Session>()
        val local = listOf(
            Session(id = "p1", directory = "/tmp/project", title = "Pending Create", time = Session.TimeInfo(updated = 1_000)),
            Session(id = "c1", directory = "/tmp/project", parentId = "p1", title = "Current View", time = Session.TimeInfo(updated = 2_000))
        )
        val merged = mergeRefreshedSessionsPreservingLocalActivity(
            refreshed, local,
            currentSessionId = "c1",
            pendingCreateIds = setOf("p1")
        )
        val byId = merged.associateBy { it.id }
        assertTrue("pending-create p1 preserved", byId.containsKey("p1"))
        assertEquals("Pending Create", byId["p1"]?.title)
        assertTrue("current session c1 preserved", byId.containsKey("c1"))
        assertEquals("Current View", byId["c1"]?.title)
    }

    @Test
    fun `current session NOT duplicated when also in refreshed`() {
        // When the currently-viewed session IS in the refreshed set, the base
        // pass adds it (fresher-wins) and the preserve pass must NOT double it.
        // The guard `it.id !in refreshedIds` prevents the duplicate.
        val refreshed = listOf(
            Session(
                id = "s1",
                directory = "/tmp/project",
                title = "Remote Server",
                time = Session.TimeInfo(updated = 2_000)
            )
        )
        val local = listOf(
            Session(
                id = "s1",
                directory = "/tmp/project",
                title = "Local Old",
                time = Session.TimeInfo(updated = 1_000)
            )
        )
        val merged = mergeRefreshedSessionsPreservingLocalActivity(
            refreshed, local,
            currentSessionId = "s1",
            pendingCreateIds = emptySet()
        )
        assertEquals("exactly one s1 in result", 1, merged.count { it.id == "s1" })
        // Base pass wins (remote has newer time) — title is server's.
        assertEquals("Remote Server", merged.single { it.id == "s1" }.title)
    }
}
