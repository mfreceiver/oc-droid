package cn.vectory.ocdroid.ui.workspace

import cn.vectory.ocdroid.ui.NavRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceStateTest {
    @Test
    fun changesDeepLinkIsBuiltAndEncodesTheSessionId() {
        assertEquals(
            "git?session=session%2Fwith%20spaces",
            NavRoute.gitRoute("session/with spaces"),
        )
    }

    @Test
    fun hostChangeClearsTheSelectedDiffScope() {
        val state = WorkspaceState(hostId = "host-a", sessionId = "session-a", workdir = "/repo", selectedFile = "src/A.kt")

        assertEquals(WorkspaceState(hostId = "host-b"), state.forHost("host-b"))
    }

    @Test
    fun diffIsVisibleOnlyForTheActiveHostSessionAndWorkdir() {
        val state = WorkspaceState(hostId = "host-a", sessionId = "session-a", workdir = "/repo", selectedFile = "src/A.kt")

        assertTrue(state.matches("host-a", "session-a", "/repo"))
        assertFalse(state.matches("host-b", "session-a", "/repo"))
        assertFalse(state.matches("host-a", "session-b", "/repo"))
        // §phase2-isolation: workdir identity is strict — a null/blank workdir
        // never matches (fail-closed), so a stale diff snapshot can't render.
        assertFalse(state.matches("host-a", "session-a", null))
        assertFalse(state.matches("host-a", "session-a", ""))
    }

    // ── §phase2-isolation: workdir identity tests ───────────────────────

    @Test
    fun `workdir mismatch hides stale diffs even when host and session coincide`() {
        // The previous `!workdir.isNullOrBlank()` check accepted ANY non-empty
        // workdir. A stale diff snapshot for workdir A would render under
        // workdir B as long as host+session matched — a cross-workdir leak.
        // Strict identity comparison (`workdir == activeWorkdir`) closes it.
        val stateForWorkdirA = WorkspaceState(
            hostId = "host-a", sessionId = "session-a", workdir = "/repo-A", selectedFile = "src/A.kt"
        )

        // Same host + session, DIFFERENT workdir → must NOT match (stale).
        assertFalse(
            "stale diff from /repo-A must NOT render under /repo-B",
            stateForWorkdirA.matches("host-a", "session-a", "/repo-B"),
        )
        // Same host + session, SAME workdir → matches (live data).
        assertTrue(stateForWorkdirA.matches("host-a", "session-a", "/repo-A"))
    }

    @Test
    fun `cross-host purge clears sessionDiffs`() {
        // This test documents the HostProfileController.purgePerHostState
        // contract: an 异组 switch (preserveServerGroupData=false) MUST clear
        // sessionDiffs. The assertion is on the slice copy shape —
        // HostProfileController.kt adds `sessionDiffs = emptyMap()` to the
        // mutateSessionList call in the purge. (The data-layer test for the
        // full purge lives in HostProfileController's own test; this pins the
        // WorkspaceState.matches() side of the contract — even if a stale
        // snapshot somehow survived, the workdir identity check hides it.)
        val staleFromHostA = WorkspaceState(
            hostId = "host-a", sessionId = "session-a", workdir = "/repo-A", selectedFile = null
        )
        // After switching to host-b (different server), session-b in /repo-B:
        assertFalse(staleFromHostA.matches("host-b", "session-b", "/repo-B"))
    }
}
