package cn.vectory.ocdroid

import cn.vectory.ocdroid.ui.chat.ReconcileTriggerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §P0 rev-3: Coverage matrix for [ReconcileTriggerState] — the deterministic
 * state machine that drives the reconcile decision in
 * [cn.vectory.ocdroid.ui.chat.ChatScaffold].
 *
 * The rev-3 state machine fixes a behaviour regression from rev-2: session
 * changes now produce a reconcile decision **directly** from
 * [ReconcileTriggerState.onSessionChange] (without waiting for the next
 * [Lifecycle.Event.ON_RESUME]), so a foreground session switch (app already
 * RESUMED) immediately reconciles.
 *
 * Two independent trigger paths:
 *   1. **Session change** — [onSessionChange] returns `(shouldReconcile, next)`
 *      directly; called from LaunchedEffect. Reconciles iff the new session
 *      differs from [lastReconciledSessionId].
 *   2. **Genuine pause→resume** — [onPause] / [onResume]; called from
 *      LifecycleEventEffect. [onResume] reconciles iff [wasPaused] is `true`.
 *
 * The invariants (verified below):
 *  ① First composition → reconcile 1× (via [onSessionChange]).
 *  ② Same session, no pause → no reconcile (via [onSessionChange]).
 *  ③ Same session, after genuine pause → reconcile 1× (via [onResume]).
 *  ④ RESUMED session switch → reconcile 1× (via [onSessionChange]).
 *  ⑤ Rapid session switches → each 1× (via [onSessionChange]).
 *  ⑥ null session → no reconcile.
 *  ⑦ Pause alone → no reconcile; flag set for next [onResume].
 *  ⑧ Session switch then pause→resume → each triggers once.
 *
 * No test manually calls [onResume] after [onSessionChange] without a
 * corresponding [onPause] — that sequence does not occur in production
 * (a foreground session switch while RESUMED produces no ON_RESUME event).
 */
class QuestionReconcileTriggerTest {

    // ── ① First composition ──────────────────────────────────────────────

    @Test
    fun `first composition reconciles via session change`() {
        val state = ReconcileTriggerState() // lastReconciledSessionId = null, wasPaused = false
        val (should, next) = state.onSessionChange("session-a")
        assertTrue("first composition must reconcile", should)
        assertEquals("session-a", next.lastReconciledSessionId)
        assertFalse("wasPaused stays false", next.wasPaused)
    }

    @Test
    fun `first composition catch-up ON_RESUME does not double reconcile`() {
        // Simulate first composition: LaunchedEffect handled session-a;
        // the catch-up ON_RESUME fires (wasPaused is still false).
        val state = ReconcileTriggerState(
            lastReconciledSessionId = "session-a",
            wasPaused = false,
        )
        val (should, next) = state.onResume("session-a")
        assertFalse("catch-up ON_RESUME must NOT reconcile", should)
        assertFalse(next.wasPaused)
        assertEquals("session-a", next.lastReconciledSessionId)
    }

    // ── ② Same session, no pause ─────────────────────────────────────────

    @Test
    fun `same session without pause does not reconcile`() {
        val state = ReconcileTriggerState(
            lastReconciledSessionId = "session-a",
            wasPaused = false,
        )
        val (should, next) = state.onSessionChange("session-a")
        assertFalse("same session no pause must NOT reconcile", should)
        assertEquals("session-a", next.lastReconciledSessionId)
        assertFalse("wasPaused stays false", next.wasPaused)
    }

    // ── ③ Genuine pause → resume ─────────────────────────────────────────

    @Test
    fun `pause then resume on same session reconciles`() {
        val state = ReconcileTriggerState(
            lastReconciledSessionId = "session-a",
            wasPaused = false,
        )
        val paused = state.onPause()
        assertTrue("onPause sets wasPaused", paused.wasPaused)

        val (should, next) = paused.onResume("session-a")
        assertTrue("pause→resume must reconcile", should)
        assertFalse("wasPaused consumed", next.wasPaused)
    }

    @Test
    fun `pause then resume multiple times reconciles each time`() {
        var state = ReconcileTriggerState(
            lastReconciledSessionId = "session-a",
            wasPaused = false,
        )
        // First pause → resume cycle
        state = state.onPause()
        var (should1, next1) = state.onResume("session-a")
        assertTrue("first pause→resume must reconcile", should1)
        state = next1

        // Second pause → resume cycle
        state = state.onPause()
        val (should2, _) = state.onResume("session-a")
        assertTrue("second pause→resume must reconcile", should2)
    }

    // ── ④ RESUMED session switch (THE KEY FIX) ───────────────────────────

    @Test
    fun `resumed session switch reconciles immediately`() {
        // App is RESUMED, user switches from session-a to session-b.
        // NO onResume call — this is the rev-3 fix.
        val state = ReconcileTriggerState(
            lastReconciledSessionId = "session-a",
            wasPaused = false,
        )
        val (should, next) = state.onSessionChange("session-b")
        assertTrue("session switch while RESUMED must reconcile immediately", should)
        assertEquals("session-b", next.lastReconciledSessionId)
        assertFalse("wasPaused stays false (no lifecycle event)", next.wasPaused)
    }

    // ── ⑤ Rapid session switches ─────────────────────────────────────────

    @Test
    fun `rapid session switches each trigger reconcile`() {
        var state = ReconcileTriggerState()

        // A enters composition
        val (shouldA, nextA) = state.onSessionChange("session-a")
        assertTrue("session-a must reconcile", shouldA)
        state = nextA

        // Immediately switch to B (no onResume between)
        val (shouldB, nextB) = state.onSessionChange("session-b")
        assertTrue("session-b must reconcile", shouldB)
        assertEquals("session-b", nextB.lastReconciledSessionId)
        assertFalse("wasPaused stays false", nextB.wasPaused)
    }

    // ── ⑥ null session ───────────────────────────────────────────────────

    @Test
    fun `null session via onResume never triggers reconcile`() {
        val state = ReconcileTriggerState(wasPaused = true)
        val (should, next) = state.onResume(null)
        assertFalse("null session must not reconcile", should)
        // State unchanged
        assertEquals(state, next)
    }

    @Test
    fun `null session after pause does not reconcile`() {
        val state = ReconcileTriggerState(
            lastReconciledSessionId = "session-a",
            wasPaused = true,
        )
        val (should, _) = state.onResume(null)
        assertFalse("null session should not reconcile even after pause", should)
    }

    // ── ⑦ Pause alone ────────────────────────────────────────────────────

    @Test
    fun `pause alone does not change reconciled session id`() {
        val state = ReconcileTriggerState(
            lastReconciledSessionId = "session-a",
            wasPaused = false,
        )
        val paused = state.onPause()
        assertEquals("session-a", paused.lastReconciledSessionId)
        assertTrue(paused.wasPaused)
    }

    @Test
    fun `pause alone does not reconcile`() {
        val state = ReconcileTriggerState(
            lastReconciledSessionId = "session-a",
            wasPaused = false,
        )
        val paused = state.onPause()
        // No onResume was called — no reconcile should have fired.
        // This is a state-only mutation; the decision comes at ON_RESUME.
        assertTrue("wasPaused must be set", paused.wasPaused)
    }

    // ── ⑧ Session switch then pause→resume ───────────────────────────────

    @Test
    fun `session switch followed by pause-resume each triggers once`() {
        var state = ReconcileTriggerState(
            lastReconciledSessionId = "session-a",
            wasPaused = false,
        )

        // Switch to B while RESUMED → immediate reconcile
        val (shouldSwitch, nextSwitch) = state.onSessionChange("session-b")
        assertTrue("session switch must reconcile", shouldSwitch)
        state = nextSwitch
        assertFalse("after switch, wasPaused stays false", state.wasPaused)

        // Now pause → resume
        state = state.onPause()
        val (shouldResume, nextResume) = state.onResume("session-b")
        assertTrue("pause→resume must reconcile", shouldResume)
        assertFalse("wasPaused consumed", nextResume.wasPaused)
    }

    // ── ⑨ onSessionChange does not set wasPaused ─────────────────────────

    @Test
    fun `onSessionChange preserves wasPaused`() {
        val state = ReconcileTriggerState(
            lastReconciledSessionId = "session-a",
            wasPaused = true, // a prior pause happened
        )
        val (should, next) = state.onSessionChange("session-b")
        assertTrue("new session must reconcile", should)
        // wasPaused must be preserved — ON_PAUSE set it, ON_RESUME consumes it
        assertTrue("wasPaused must be preserved across onSessionChange", next.wasPaused)
    }
}
