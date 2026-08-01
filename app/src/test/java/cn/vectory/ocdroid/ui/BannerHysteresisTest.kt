package cn.vectory.ocdroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §sse-feedback-ux (§1.3): table-driven tests for the banner hysteresis state
 * machine. The reducer is pure (takes [now] as a parameter) so every transition
 * is exercised with a controlled clock.
 *
 * State machine under test:
 * ```
 * Hidden        + worthy          → PendingShow (not visible)
 * PendingShow   + worthy & grace  → Showing
 * PendingShow   + !worthy         → Hidden (grace recovery)
 * Showing       + worthy          → Showing (stay)
 * Showing       + !worthy & min   → PendingHide
 * Showing       + !worthy & !min  → Showing (min-display hold)
 * PendingHide   + worthy          → Showing (re-show, anti-flap)
 * PendingHide   + !worthy & delay → Hidden
 * PendingHide   + !worthy & !delay→ PendingHide
 * Showing       + category change → Showing (new category, no flicker)
 * ```
 */
class BannerHysteresisTest {

    private val cfg = BannerHysteresisConfig(
        showGraceMs = 5_000L,
        minDisplayMs = 3_000L,
        recoverHideDelayMs = 5_000L,
    )

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Shorthand: a non-null category for "worthy" transitions. */
    private val outageCat: BannerCategory = BannerCategory.REST_OUTAGE
    private val authCat: BannerCategory = BannerCategory.AUTH_FAILURE
    private val stalledCat: BannerCategory = BannerCategory.SSE_STALLED

    /** Assert the state is Hidden (both visibility and phase). */
    private fun assertHidden(state: BannerHysteresisState) {
        assertTrue("expected Hidden visibility", state.visibility is BannerVisibility.Hidden)
        assertTrue("expected Hidden phase", state.phase is BannerHysteresisPhase.Hidden)
    }

    /** Assert the state is Showing with the given category. */
    private fun assertShowing(state: BannerHysteresisState, expectedCat: BannerCategory) {
        val vis = state.visibility as? BannerVisibility.Showing
            ?: throw AssertionError("expected Showing visibility but got ${state.visibility}")
        assertEquals(expectedCat, vis.category)
        val phase = state.phase as? BannerHysteresisPhase.Showing
            ?: throw AssertionError("expected Showing phase but got ${state.phase}")
        assertEquals(expectedCat, phase.category)
    }

    /** Assert the state is PendingShow with the given category. */
    private fun assertPendingShow(state: BannerHysteresisState, expectedCat: BannerCategory) {
        assertTrue("expected Hidden visibility but got ${state.visibility}",
            state.visibility is BannerVisibility.Hidden)
        val phase = state.phase as? BannerHysteresisPhase.PendingShow
            ?: throw AssertionError("expected PendingShow phase but got ${state.phase}")
        assertEquals(expectedCat, phase.category)
    }

    /** Assert the state is PendingHide with the given category. */
    private fun assertPendingHide(state: BannerHysteresisState, expectedCat: BannerCategory) {
        assertTrue("expected Showing visibility during PendingHide but got ${state.visibility}",
            state.visibility is BannerVisibility.Showing)
        val phase = state.phase as? BannerHysteresisPhase.PendingHide
            ?: throw AssertionError("expected PendingHide phase but got ${state.phase}")
        assertEquals(expectedCat, phase.category)
    }

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    fun `Hidden plus worthy becomes PendingShow not visible`() {
        val result = bannerHysteresisReducer(
            prev = BannerHysteresisState(),
            category = outageCat,
            now = 1_000L,
            config = cfg,
        )
        assertPendingShow(result, outageCat)
    }

    @Test
    fun `PendingShow plus worthy and grace elapsed becomes Showing`() {
        val prev = BannerHysteresisState(
            visibility = BannerVisibility.Hidden,
            phase = BannerHysteresisPhase.PendingShow(category = outageCat, atMs = 1_000L),
        )
        // now = 1_000 + 5_000 = 6_000 → grace just elapsed
        val result = bannerHysteresisReducer(prev, outageCat, now = 6_000L, config = cfg)
        assertShowing(result, outageCat)
    }

    @Test
    fun `PendingShow plus worthy but grace not yet elapsed stays PendingShow`() {
        val prev = BannerHysteresisState(
            visibility = BannerVisibility.Hidden,
            phase = BannerHysteresisPhase.PendingShow(category = outageCat, atMs = 1_000L),
        )
        // now = 1_000 + 2_000 = 3_000 → grace not elapsed (need 6_000)
        val result = bannerHysteresisReducer(prev, outageCat, now = 3_000L, config = cfg)
        assertPendingShow(result, outageCat)
    }

    @Test
    fun `PendingShow plus not worthy becomes Hidden never shown`() {
        val prev = BannerHysteresisState(
            visibility = BannerVisibility.Hidden,
            phase = BannerHysteresisPhase.PendingShow(category = outageCat, atMs = 1_000L),
        )
        val result = bannerHysteresisReducer(prev, category = null, now = 3_000L, config = cfg)
        assertHidden(result)
    }

    @Test
    fun `Showing plus worthy stays Showing`() {
        val prev = BannerHysteresisState(
            visibility = BannerVisibility.Showing(category = outageCat, sinceMs = 1_000L),
            phase = BannerHysteresisPhase.Showing(category = outageCat, sinceMs = 1_000L),
        )
        val result = bannerHysteresisReducer(prev, outageCat, now = 10_000L, config = cfg)
        assertShowing(result, outageCat)
    }

    @Test
    fun `Showing plus not worthy and minDisplay met becomes PendingHide`() {
        val prev = BannerHysteresisState(
            visibility = BannerVisibility.Showing(category = outageCat, sinceMs = 1_000L),
            phase = BannerHysteresisPhase.Showing(category = outageCat, sinceMs = 1_000L),
        )
        // now = 1_000 + 3_000 = 4_000 → minDisplay just met
        val result = bannerHysteresisReducer(prev, category = null, now = 4_000L, config = cfg)
        assertPendingHide(result, outageCat)
    }

    @Test
    fun `Showing plus not worthy and minDisplay not met stays Showing`() {
        val prev = BannerHysteresisState(
            visibility = BannerVisibility.Showing(category = outageCat, sinceMs = 1_000L),
            phase = BannerHysteresisPhase.Showing(category = outageCat, sinceMs = 1_000L),
        )
        // now = 1_000 + 1_000 = 2_000 → minDisplay not met (need 4_000)
        val result = bannerHysteresisReducer(prev, category = null, now = 2_000L, config = cfg)
        assertShowing(result, outageCat)
    }

    @Test
    fun `PendingHide plus worthy becomes Showing re-show anti-flap`() {
        val prev = BannerHysteresisState(
            visibility = BannerVisibility.Showing(category = outageCat, sinceMs = 1_000L),
            phase = BannerHysteresisPhase.PendingHide(
                category = outageCat, atMs = 6_000L, sinceMs = 1_000L,
            ),
        )
        val result = bannerHysteresisReducer(prev, stalledCat, now = 8_000L, config = cfg)
        // Re-shows with the NEW category (stalledCat), preserving original sinceMs
        assertShowing(result, stalledCat)
        // sinceMs should be the ORIGINAL sinceMs from prev (1_000L), preserved through PendingHide
        val showingPhase = result.phase as BannerHysteresisPhase.Showing
        assertEquals(1_000L, showingPhase.sinceMs)
    }

    @Test
    fun `PendingHide plus not worthy and recoverDelay elapsed becomes Hidden`() {
        val prev = BannerHysteresisState(
            visibility = BannerVisibility.Showing(category = outageCat, sinceMs = 1_000L),
            phase = BannerHysteresisPhase.PendingHide(
                category = outageCat, atMs = 6_000L, sinceMs = 1_000L,
            ),
        )
        // now = 6_000 + 5_000 = 11_000 → recoverDelay just elapsed
        val result = bannerHysteresisReducer(prev, category = null, now = 11_000L, config = cfg)
        assertHidden(result)
    }

    @Test
    fun `PendingHide plus not worthy and recoverDelay not yet elapsed stays PendingHide`() {
        val prev = BannerHysteresisState(
            visibility = BannerVisibility.Showing(category = outageCat, sinceMs = 1_000L),
            phase = BannerHysteresisPhase.PendingHide(
                category = outageCat, atMs = 6_000L, sinceMs = 1_000L,
            ),
        )
        // now = 6_000 + 2_000 = 8_000 → recoverDelay not elapsed (need 11_000)
        val result = bannerHysteresisReducer(prev, category = null, now = 8_000L, config = cfg)
        assertPendingHide(result, outageCat)
    }

    @Test
    fun `Showing with category change updates displayed category without flicker`() {
        val prev = BannerHysteresisState(
            visibility = BannerVisibility.Showing(category = outageCat, sinceMs = 1_000L),
            phase = BannerHysteresisPhase.Showing(category = outageCat, sinceMs = 1_000L),
        )
        // Category changes from REST_OUTAGE to AUTH_FAILURE while still worthy
        val result = bannerHysteresisReducer(prev, authCat, now = 10_000L, config = cfg)
        assertShowing(result, authCat)
        // sinceMs should be preserved from the original Showing
        val showingPhase = result.phase as BannerHysteresisPhase.Showing
        assertEquals(1_000L, showingPhase.sinceMs)
    }

    @Test
    fun `PendingShow with category change updates stored category`() {
        val prev = BannerHysteresisState(
            visibility = BannerVisibility.Hidden,
            phase = BannerHysteresisPhase.PendingShow(category = outageCat, atMs = 1_000L),
        )
        // Category changes from REST_OUTAGE to AUTH_FAILURE while still in grace
        val result = bannerHysteresisReducer(prev, authCat, now = 3_000L, config = cfg)
        assertPendingShow(result, authCat)
    }

    @Test
    fun `Hidden plus not worthy stays Hidden`() {
        val result = bannerHysteresisReducer(
            prev = BannerHysteresisState(),
            category = null,
            now = 1_000L,
            config = cfg,
        )
        assertHidden(result)
    }

    @Test
    fun `full lifecycle transient blip never shows`() {
        // A transient blip: worthy for 2s (< 5s grace), then recovers
        var state = BannerHysteresisState()
        state = bannerHysteresisReducer(state, outageCat, now = 1_000L, config = cfg)
        assertPendingShow(state, outageCat)

        state = bannerHysteresisReducer(state, outageCat, now = 3_000L, config = cfg)
        assertPendingShow(state, outageCat) // still in grace

        // Recovers!
        state = bannerHysteresisReducer(state, category = null, now = 3_000L, config = cfg)
        assertHidden(state) // never shown
    }

    @Test
    fun `full lifecycle sustained disconnect shows then recovers with minDisplay`() {
        var state = BannerHysteresisState()

        // Disconnect at t=0
        state = bannerHysteresisReducer(state, outageCat, now = 0L, config = cfg)
        // Grace elapsed at t=5
        state = bannerHysteresisReducer(state, outageCat, now = 5_000L, config = cfg)
        assertShowing(state, outageCat)

        // Still disconnected at t=10
        state = bannerHysteresisReducer(state, outageCat, now = 10_000L, config = cfg)
        assertShowing(state, outageCat)

        // Recovers at t=11: minDisplay (3s) met since showing at t=5
        state = bannerHysteresisReducer(state, category = null, now = 11_000L, config = cfg)
        assertPendingHide(state, outageCat)

        // Recovered for 1s more → still PendingHide (need 5s delay)
        state = bannerHysteresisReducer(state, category = null, now = 12_000L, config = cfg)
        assertPendingHide(state, outageCat)

        // But then disconnects again during hide delay
        state = bannerHysteresisReducer(state, outageCat, now = 12_000L, config = cfg)
        assertShowing(state, outageCat) // re-show (anti-flap)

        // Fully recovered, hide delay elapsed
        state = bannerHysteresisReducer(state, outageCat, now = 20_000L, config = cfg)
        assertShowing(state, outageCat)

        state = bannerHysteresisReducer(state, category = null, now = 25_000L, config = cfg)
        assertPendingHide(state, outageCat)

        state = bannerHysteresisReducer(state, category = null, now = 31_000L, config = cfg)
        assertHidden(state)
    }

    @Test
    fun `minDisplay not met prevents premature hide`() {
        var state = BannerHysteresisState()
        // Grace passes
        state = bannerHysteresisReducer(state, outageCat, now = 0L, config = cfg)
        state = bannerHysteresisReducer(state, outageCat, now = 5_000L, config = cfg)
        assertShowing(state, outageCat)

        // Recovers INSTANTLY (within minDisplay window)
        state = bannerHysteresisReducer(state, category = null, now = 6_000L, config = cfg)
        // sinceMs = 5_000, now = 6_000 → 1s since showing (need 3s min)
        assertShowing(state, outageCat) // min-display holds

        // Wait past minDisplay then recover
        state = bannerHysteresisReducer(state, category = null, now = 9_000L, config = cfg)
        // sinceMs = 5_000, now = 9_000 → 4s (≥3s) → PendingHide
        assertPendingHide(state, outageCat)
    }
}
