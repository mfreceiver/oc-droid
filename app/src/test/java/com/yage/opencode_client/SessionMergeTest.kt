package com.yage.opencode_client

import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.ui.mergeRefreshedSessionsPreservingLocalActivity
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
    fun `preserve appends local-only current session even when absent from refreshed`() {
        // #10b: the currently-open session must survive a global refresh that
        // does not return it (e.g. a freshly-created session not yet listed,
        // or a directory session the user selected from a connected workdir).
        val refreshed = listOf(
            Session(id = "s1", directory = "/tmp/project", title = "Refreshed")
        )
        val local = listOf(
            Session(id = "s2", directory = "/tmp/project", title = "Current (local-only)")
        )

        val merged = mergeRefreshedSessionsPreservingLocalActivity(
            refreshed, local,
            currentSessionId = "s2",
            openSessionIds = emptySet()
        )

        val byId = merged.associateBy { it.id }
        assertTrue("current session must be preserved", byId.containsKey("s2"))
        assertEquals("Current (local-only)", byId["s2"]?.title)
        assertTrue("refreshed session also retained", byId.containsKey("s1"))
    }

    @Test
    fun `preserve appends local-only open-tab sessions`() {
        // #10b: sessions in the open-tabs list (browser-tab style) are also
        // preserved across refresh, not just the single current session.
        val refreshed = listOf(
            Session(id = "s1", directory = "/tmp/project", title = "Refreshed")
        )
        val local = listOf(
            Session(id = "s2", directory = "/tmp/project", title = "Open tab 1"),
            Session(id = "s3", directory = "/tmp/project", title = "Open tab 2")
        )

        val merged = mergeRefreshedSessionsPreservingLocalActivity(
            refreshed, local,
            currentSessionId = null,
            openSessionIds = setOf("s2", "s3")
        )

        val ids = merged.map { it.id }.toSet()
        assertTrue("s2 in openSessionIds preserved", "s2" in ids)
        assertTrue("s3 in openSessionIds preserved", "s3" in ids)
    }

    @Test
    fun `preserve does NOT retain local sessions that are neither current nor open`() {
        // Sessions the user is not actively using are allowed to drop on refresh,
        // so stale / closed-tab sessions do not accumulate forever.
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
}
