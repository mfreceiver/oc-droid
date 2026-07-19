package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.SlimSessionLastError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §T17 slimapi v1 §6.1: JVM unit tests for [shouldShowSessionErrorIndicator]
 * — the pure resolver backing the non-focus session row's error indicator
 * inside [RecentSessionsDrawer]. The row composable is private; its
 * indicator-visibility contract is exercised through this helper which is
 * the sole input to the `if (showErrorIndicator)` branch.
 *
 * Contract (mirrors the StatusSlot LastError gate):
 *  - sid present in [SessionListState.sessionErrorsById] with a SET entry →
 *    indicator VISIBLE.
 *  - sid absent from the map → indicator HIDDEN (T12 owns the map; absence
 *    means the sidecar cleared the error or never set one — recovered).
 *  - empty map → indicator HIDDEN for any sid (cold-start / no errors).
 *
 * Null-safety is the hard gate (T17-C3): the indicator MUST NOT fire for an
 * absent sid, and MUST NOT crash on any input shape. These tests are the
 * load-bearing proof.
 */
class RecentSessionErrorIndicatorTest {

    private val errorA = SlimSessionLastError(name = "upstream_error", message = "boom")
    private val errorB = SlimSessionLastError(name = "session_not_found", message = null)

    @Test
    fun `indicator shown when session has a SET lastError`() {
        val map = mapOf("sess-a" to errorA, "sess-b" to errorB)
        assertTrue(
            shouldShowSessionErrorIndicator(
                sessionId = "sess-a",
                sessionErrorsById = map,
            ),
        )
        assertTrue(
            shouldShowSessionErrorIndicator(
                sessionId = "sess-b",
                sessionErrorsById = map,
            ),
        )
    }

    @Test
    fun `indicator hidden when session is absent from the map (null-safety gate)`() {
        // T17-C3 hard gate: a sid absent from the map MUST NOT render the
        // indicator. The map holds SET errors only (T12 removes on
        // recovery); absence = recovered / never-set.
        val map = mapOf("sess-a" to errorA)
        assertFalse(
            shouldShowSessionErrorIndicator(
                sessionId = "sess-other",
                sessionErrorsById = map,
            ),
        )
    }

    @Test
    fun `indicator hidden for every sid when map is empty`() {
        // Defensive boundary: cold-start (no sessions errored) — the map
        // is the default `emptyMap()` from [SessionListState]. No row
        // renders an indicator.
        assertFalse(
            shouldShowSessionErrorIndicator(
                sessionId = "sess-a",
                sessionErrorsById = emptyMap(),
            ),
        )
        assertFalse(
            shouldShowSessionErrorIndicator(
                sessionId = "",
                sessionErrorsById = emptyMap(),
            ),
        )
    }

    @Test
    fun `indicator hidden for blank sid even when map has other entries`() {
        // Defensive: a malformed / blank sid (defensive against a future
        // refactor that drops the non-null sid precondition) MUST NOT
        // accidentally match a blank key in the map.
        val map = mapOf("sess-a" to errorA)
        assertFalse(
            shouldShowSessionErrorIndicator(
                sessionId = "",
                sessionErrorsById = map,
            ),
        )
    }

    @Test
    fun `indicator visibility is symmetric with StatusSlot LastError gate (T17-C1 vs T17-C3)`() {
        // Pin the contract parity: the row indicator (T17-C3) and the
        // StatusSlot banner (T17-C1) read the SAME canonical store. A sid
        // that shows the indicator in the drawer MUST also trigger the
        // banner when the user opens that session (the StatusSlot path is
        // covered structurally by StatusSlotPriorityTest's lastError-tier
        // cases). Verified here by routing the same map through BOTH the
        // indicator helper and pick()'s lastError input.
        val map = mapOf("sess-a" to errorA)

        // Drawer row indicator.
        val showsIndicator = shouldShowSessionErrorIndicator("sess-a", map)

        // StatusSlot banner (the lookup ChatScaffold does at the call site).
        val lookedUp = map["sess-a"]
        val priority = StatusSlotPriority.pick(
            permission = null,
            question = null,
            lastError = lookedUp,
            sessionStatus = null,
            isCompacting = false,
            isRunning = false,
            isConnecting = false,
        )

        assertTrue(showsIndicator)
        assertEquals(StatusSlotPriority.LastError, priority)

        // Symmetric absence: when the indicator hides, the banner must also hide.
        val noIndicator = shouldShowSessionErrorIndicator("sess-absent", map)
        val noBannerPriority = StatusSlotPriority.pick(
            permission = null,
            question = null,
            lastError = map["sess-absent"],
            sessionStatus = null,
            isCompacting = false,
            isRunning = false,
            isConnecting = false,
        )
        assertFalse(noIndicator)
        assertEquals(StatusSlotPriority.None, noBannerPriority)
    }
}
