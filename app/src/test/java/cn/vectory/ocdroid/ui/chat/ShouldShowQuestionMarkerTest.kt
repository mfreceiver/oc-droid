package cn.vectory.ocdroid.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [shouldShowQuestionMarker] — the pure resolver backing
 * the session tab's pending-question ("?") marker. Extracted from the
 * [SessionTabStrip]'s private `SessionTab` composable so the suppression
 * contract (the marker is NOT shown on the currently selected root tab, even
 * when the user is viewing one of its sub-agents) is unit-testable.
 *
 * Contract: the "?" renders iff the tab is NOT selected AND its session id is
 * in the root-aggregated [questionSessionIds] set.
 */
class ShouldShowQuestionMarkerTest {

    private val questionSessionIds = setOf("root-a", "root-c")

    // ── baseline: non-selected tabs ────────────────────────────────────────

    @Test
    fun `marker shown when non-selected tab is in question set`() {
        assertTrue(
            shouldShowQuestionMarker(
                isSelected = false,
                questionSessionIds = questionSessionIds,
                sessionId = "root-a",
            )
        )
    }

    @Test
    fun `marker hidden when non-selected tab is not in question set`() {
        assertFalse(
            shouldShowQuestionMarker(
                isSelected = false,
                questionSessionIds = questionSessionIds,
                sessionId = "root-b",
            )
        )
    }

    // ── the regression: child-current must not inflate the parent root tab ─

    @Test
    fun `marker hidden on selected root tab even when root is in question set`() {
        // Reproduces Fix 1: currentSessionId is a child of root-a. The tab
        // strip resolves effectiveSelectedId to root-a (isSelected=true), and
        // root-a is in questionSessionIds (the child has a pending question
        // that aggregated up). The OLD check `id != currentSessionId` would
        // pass here (root-a != child-id) and wrongly render "?"; the NEW
        // check suppresses via isSelected because the question is already in
        // the QuestionCard of the visible child chat.
        assertFalse(
            shouldShowQuestionMarker(
                isSelected = true,
                questionSessionIds = questionSessionIds,
                sessionId = "root-a",
            )
        )
    }

    @Test
    fun `marker hidden on selected root tab when root is not in question set`() {
        // isSelected alone is sufficient to suppress; the question set
        // membership is irrelevant for the selected tab.
        assertFalse(
            shouldShowQuestionMarker(
                isSelected = true,
                questionSessionIds = questionSessionIds,
                sessionId = "root-c",
            )
        )
    }

    // ── empty / boundary ───────────────────────────────────────────────────

    @Test
    fun `marker hidden for everyone when question set is empty`() {
        assertFalse(
            shouldShowQuestionMarker(
                isSelected = false,
                questionSessionIds = emptySet(),
                sessionId = "root-a",
            )
        )
    }

    @Test
    fun `isSelected suppression takes priority over membership`() {
        // Selected + in-set: still suppressed (priority of isSelected).
        assertFalse(
            shouldShowQuestionMarker(
                isSelected = true,
                questionSessionIds = setOf("only"),
                sessionId = "only",
            )
        )
    }
}
