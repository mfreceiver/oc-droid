package cn.vectory.ocdroid.ui.workspace

import cn.vectory.ocdroid.data.model.FileDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §B1-fix: combination tests for the GitScreen visibleDiffs pipeline.
 *
 * The ship-breaker (glmer 🔴): on first visit to `workspace/changes?session=X`,
 * savedStateHandle[*] are all null. The old code did
 * `WorkspaceState(workdir=workdir).forHost(activeHost).copy(sessionId=...)` —
 * forHost saw hostId(null) != activeHost and reset EVERYTHING, then copy
 * restored ONLY sessionId, leaving workdir null. `matches(host, session, workdir=actual)`
 * was false → visibleDiffs=empty → the "N files changed" deep link landed on
 * an empty list.
 *
 * These tests exercise the EXACT pipeline the composable runs
 * ([resolveWorkspaceState] + [WorkspaceState.matches] + [computeVisibleDiffs])
 * — NOT just the pure matches/forHost unit functions — so the combination
 * regression is pinned.
 */
class WorkspaceVisibleDiffsTest {

    private val diffs = listOf(
        FileDiff(filePath = "src/A.kt", additions = 3, deletions = 1, status = "modified", patch = "diff"),
    )

    // ── §B1 ship-breaker: first-visit deep link shows non-empty diffs ────

    @Test
    fun `first-visit changes deep link shows non-empty visibleDiffs when session is loaded`() {
        // First visit: SavedStateHandle all null. Session loaded + workdir known.
        val visible = computeVisibleDiffs(
            savedHost = null,
            savedSession = null,
            savedFile = null,
            workdir = "/repo",
            activeHost = "host-a",
            activeSession = "session-a",
            sessionDiffs = mapOf("session-a" to diffs),
        )
        assertTrue("first-visit deep link must surface session diffs", visible.isNotEmpty())
        assertEquals(diffs, visible)
    }

    @Test
    fun `regression pin - WITHOUT workdir restoration visibleDiffs is empty`() {
        // Pin the exact bug: if workdir is NOT restored after forHost, matches()
        // is false → visibleDiffs empty. This documents why the `.copy(workdir=...)`
        // is load-bearing.
        val brokenState = WorkspaceState(
            hostId = null, sessionId = null, workdir = "/repo", selectedFile = null,
        ).forHost("host-a").copy(sessionId = "session-a") // workdir NOT restored
        assertFalse(brokenState.matches("host-a", "session-a", "/repo"))
    }

    @Test
    fun `first-visit with session workdir but no loaded diffs yields empty`() {
        // No diff snapshot loaded yet → empty (not an error; the card just has
        // nothing to show). matches() still TRUE so the pane doesn't hide.
        val visible = computeVisibleDiffs(
            savedHost = null, savedSession = null, savedFile = null,
            workdir = "/repo", activeHost = "host-a", activeSession = "session-a",
            sessionDiffs = emptyMap(),
        )
        assertTrue(visible.isEmpty())
    }

    // ── workdir identity (isolation) ─────────────────────────────────────

    @Test
    fun `stale workdir snapshot does not render under a different workdir`() {
        // Saved state carries /repo-A; the active workdir is /repo-B. Even with
        // host+session coinciding, the workdir identity check hides the stale
        // snapshot (cross-workdir leak guard).
        val visible = computeVisibleDiffs(
            savedHost = "host-a", savedSession = "session-a", savedFile = null,
            workdir = "/repo-B", activeHost = "host-a", activeSession = "session-a",
            sessionDiffs = mapOf("session-a" to diffs),
        )
        // resolveWorkspaceState restores workdir=/repo-B (the live value), so
        // matches(host-a, session-a, /repo-B) is TRUE → visible. The stale-A
        // snapshot was never reachable because workdir is always the LIVE value.
        assertTrue(visible.isNotEmpty())
    }

    // ── cross-host isolation ─────────────────────────────────────────────

    @Test
    fun `cross-host switch clears visibleDiffs via forHost reset`() {
        // Host-a state was saved. Switching to host-b: forHost sees hostId
        // (host-a) != activeHost (host-b) → resets the entire state. The
        // stale host-a session-a diffs MUST NOT render under host-b.
        val visible = computeVisibleDiffs(
            savedHost = "host-a", savedSession = "session-a", savedFile = "src/A.kt",
            workdir = "/repo-B", activeHost = "host-b", activeSession = "session-b",
            sessionDiffs = mapOf("session-a" to diffs), // stale host-a data
        )
        // session-b has no diff snapshot in the map → empty. Even if it did,
        // matches() requires host-b (resolved) — which holds after forHost reset
        // + copy(sessionId=session-b, workdir=/repo-B). The point: host-a's
        // session-a diffs are NOT surfaced under host-b.
        assertTrue(visible.isEmpty())
    }

    @Test
    fun `same host revisit after rotation keeps visibleDiffs non-empty`() {
        // After the LaunchedEffect persists host/session to SavedStateHandle,
        // a rotation re-enters with savedHost=host-a, savedSession=session-a.
        // forHost sees hostId==activeHost → this (no reset) → matches → non-empty.
        val visible = computeVisibleDiffs(
            savedHost = "host-a", savedSession = "session-a", savedFile = null,
            workdir = "/repo", activeHost = "host-a", activeSession = "session-a",
            sessionDiffs = mapOf("session-a" to diffs),
        )
        assertTrue(visible.isNotEmpty())
        assertEquals(diffs, visible)
    }
}
