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
            currentSessionId = null,
            openSessionIds = emptySet()
        )

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
            currentSessionId = null,
            openSessionIds = emptySet()
        )

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
            currentSessionId = null,
            openSessionIds = emptySet()
        )

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
            currentSessionId = null,
            openSessionIds = emptySet()
        )

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
            currentSessionId = null,
            openSessionIds = emptySet()
        )

        assertEquals("Server", merged.single().title)
    }

    @Test
    fun `preserve appends local-only pending-create session even when absent from refreshed`() {
        // §Q4-strict-sync: the preserve pass now keys on pendingCreateIds
        // (not currentSessionId / openSessionIds). A freshly-created session
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
            openSessionIds = emptySet(),
            pendingCreateIds = setOf("s2"),
        )

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
            currentSessionId = null,
            openSessionIds = emptySet()
        )

        assertEquals(listOf("s1"), merged.map { it.id })
    }

    @Test
    fun `preserve drops a current-or-open session when it is NOT in pendingCreateIds`() {
        // §Q4-strict-sync (strict ghost removal): the defining behavior change.
        // A session that WAS current/open locally but is NOT in the server's
        // refreshed list AND is NOT pending-create is now DROPPED. Pre-Q4 the
        // currentSessionId / openSessionIds check kept it alive indefinitely
        // (ghost). Now only pendingCreateIds can preserve it.
        val refreshed = listOf(
            Session(id = "s1", directory = "/tmp/project", title = "Refreshed")
        )
        val local = listOf(
            Session(id = "s2", directory = "/tmp/project", title = "Current but not pending (should drop)")
        )

        val merged = mergeRefreshedSessionsPreservingLocalActivity(
            refreshed, local,
            currentSessionId = "s2",
            openSessionIds = setOf("s2"),
            pendingCreateIds = emptySet(),
        )

        // s2 is current AND open, but NOT pending-create → dropped (strict-sync).
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
            openSessionIds = emptySet(),
            pendingCreateIds = setOf("s2", "s3"),
        )

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
            currentSessionId = null,
            openSessionIds = emptySet()
        )

        assertEquals(revertX, merged.single().revert)
    }
}
