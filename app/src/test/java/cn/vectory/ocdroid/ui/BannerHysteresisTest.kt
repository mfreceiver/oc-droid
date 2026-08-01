package cn.vectory.ocdroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BannerHysteresisTest {

    private val cfg = BannerHysteresisConfig(
        showGraceMs = 5_000L,
        minDisplayMs = 3_000L,
        recoverHideDelayMs = 5_000L,
    )

    private fun input(cat: BannerCategory, authReason: String? = null): BannerCategoryInput =
        BannerCategoryInput(category = cat, authReason = authReason)

    private val outageCat: BannerCategory = BannerCategory.REST_OUTAGE
    private val authCat: BannerCategory = BannerCategory.AUTH_FAILURE
    private val stalledCat: BannerCategory = BannerCategory.SSE_STALLED
    private val outageInput get() = input(outageCat)
    private val authInput get() = input(authCat, authReason = "cert expired")
    private val stalledInput get() = input(stalledCat)

    private fun assertHidden(state: BannerHysteresisState) {
        assertTrue("expected Hidden visibility", state.visibility is BannerVisibility.Hidden)
        assertTrue("expected Hidden phase", state.phase is BannerHysteresisPhase.Hidden)
    }

    private fun assertShowing(
        state: BannerHysteresisState,
        expectedCat: BannerCategory,
        expectedAuthReason: String? = null,
    ) {
        val vis = state.visibility as? BannerVisibility.Showing
            ?: throw AssertionError("expected Showing visibility but got ${state.visibility}")
        assertEquals(expectedCat, vis.category)
        assertEquals(expectedAuthReason, vis.authReason)
        val phase = state.phase as? BannerHysteresisPhase.Showing
            ?: throw AssertionError("expected Showing phase but got ${state.phase}")
        assertEquals(expectedCat, phase.category)
        assertEquals(expectedAuthReason, phase.authReason)
    }

    private fun assertPendingShow(state: BannerHysteresisState, expectedCat: BannerCategory) {
        assertTrue("expected Hidden visibility but got ${state.visibility}",
            state.visibility is BannerVisibility.Hidden)
        val phase = state.phase as? BannerHysteresisPhase.PendingShow
            ?: throw AssertionError("expected PendingShow phase but got ${state.phase}")
        assertEquals(expectedCat, phase.category)
    }

    private fun assertPendingHide(state: BannerHysteresisState, expectedCat: BannerCategory) {
        assertTrue("expected Showing visibility during PendingHide but got ${state.visibility}",
            state.visibility is BannerVisibility.Showing)
        val phase = state.phase as? BannerHysteresisPhase.PendingHide
            ?: throw AssertionError("expected PendingHide phase but got ${state.phase}")
        assertEquals(expectedCat, phase.category)
    }

    // ── Transition tests (adapted to BannerCategoryInput) ──────────────

    @Test
    fun `Hidden plus worthy becomes PendingShow not visible`() {
        val result = bannerHysteresisReducer(
            prev = BannerHysteresisState(),
            input = outageInput,
            now = 1_000L,
            config = cfg,
        )
        assertPendingShow(result, outageCat)
    }

    @Test
    fun `PendingShow plus worthy and grace elapsed becomes Showing`() {
        val prev = BannerHysteresisState(
            visibility = BannerVisibility.Hidden,
            phase = BannerHysteresisPhase.PendingShow(outageCat, null, 1_000L),
        )
        val result = bannerHysteresisReducer(prev, outageInput, now = 6_000L, config = cfg)
        assertShowing(result, outageCat)
    }

    @Test
    fun `PendingShow plus worthy but grace not yet elapsed stays PendingShow`() {
        val prev = BannerHysteresisState(
            visibility = BannerVisibility.Hidden,
            phase = BannerHysteresisPhase.PendingShow(outageCat, null, 1_000L),
        )
        val result = bannerHysteresisReducer(prev, outageInput, now = 3_000L, config = cfg)
        assertPendingShow(result, outageCat)
    }

    @Test
    fun `PendingShow plus not worthy becomes Hidden never shown`() {
        val prev = BannerHysteresisState(
            visibility = BannerVisibility.Hidden,
            phase = BannerHysteresisPhase.PendingShow(outageCat, null, 1_000L),
        )
        val result = bannerHysteresisReducer(prev, input = null, now = 3_000L, config = cfg)
        assertHidden(result)
    }

    @Test
    fun `Showing plus worthy stays Showing`() {
        val prev = BannerHysteresisState(
            visibility = BannerVisibility.Showing(outageCat, null, 1_000L),
            phase = BannerHysteresisPhase.Showing(outageCat, null, 1_000L),
        )
        val result = bannerHysteresisReducer(prev, outageInput, now = 10_000L, config = cfg)
        assertShowing(result, outageCat)
    }

    @Test
    fun `Showing plus not worthy and minDisplay met becomes PendingHide`() {
        val prev = BannerHysteresisState(
            visibility = BannerVisibility.Showing(outageCat, null, 1_000L),
            phase = BannerHysteresisPhase.Showing(outageCat, null, 1_000L),
        )
        val result = bannerHysteresisReducer(prev, input = null, now = 4_000L, config = cfg)
        assertPendingHide(result, outageCat)
    }

    @Test
    fun `Showing plus not worthy and minDisplay not met stays Showing`() {
        val prev = BannerHysteresisState(
            visibility = BannerVisibility.Showing(outageCat, null, 1_000L),
            phase = BannerHysteresisPhase.Showing(outageCat, null, 1_000L),
        )
        val result = bannerHysteresisReducer(prev, input = null, now = 2_000L, config = cfg)
        assertShowing(result, outageCat)
    }

    @Test
    fun `PendingHide plus worthy becomes Showing re-show anti-flap`() {
        val prev = BannerHysteresisState(
            visibility = BannerVisibility.Showing(outageCat, null, 1_000L),
            phase = BannerHysteresisPhase.PendingHide(outageCat, null, 6_000L, 1_000L),
        )
        val result = bannerHysteresisReducer(prev, stalledInput, now = 8_000L, config = cfg)
        assertShowing(result, stalledCat)
        val showingPhase = result.phase as BannerHysteresisPhase.Showing
        assertEquals(1_000L, showingPhase.sinceMs)
    }

    @Test
    fun `PendingHide plus not worthy and recoverDelay elapsed becomes Hidden`() {
        val prev = BannerHysteresisState(
            visibility = BannerVisibility.Showing(outageCat, null, 1_000L),
            phase = BannerHysteresisPhase.PendingHide(outageCat, null, 6_000L, 1_000L),
        )
        val result = bannerHysteresisReducer(prev, input = null, now = 11_000L, config = cfg)
        assertHidden(result)
    }

    @Test
    fun `PendingHide plus not worthy and recoverDelay not yet elapsed stays PendingHide`() {
        val prev = BannerHysteresisState(
            visibility = BannerVisibility.Showing(outageCat, null, 1_000L),
            phase = BannerHysteresisPhase.PendingHide(outageCat, null, 6_000L, 1_000L),
        )
        val result = bannerHysteresisReducer(prev, input = null, now = 8_000L, config = cfg)
        assertPendingHide(result, outageCat)
    }

    @Test
    fun `Showing with category change updates displayed category without flicker`() {
        val prev = BannerHysteresisState(
            visibility = BannerVisibility.Showing(outageCat, null, 1_000L),
            phase = BannerHysteresisPhase.Showing(outageCat, null, 1_000L),
        )
        val result = bannerHysteresisReducer(prev, authInput, now = 10_000L, config = cfg)
        assertShowing(result, authCat, expectedAuthReason = "cert expired")
        val showingPhase = result.phase as BannerHysteresisPhase.Showing
        assertEquals(1_000L, showingPhase.sinceMs)
        assertEquals("cert expired", showingPhase.authReason)
    }

    @Test
    fun `PendingShow with category change updates stored category`() {
        val prev = BannerHysteresisState(
            visibility = BannerVisibility.Hidden,
            phase = BannerHysteresisPhase.PendingShow(outageCat, null, 1_000L),
        )
        val result = bannerHysteresisReducer(prev, authInput, now = 3_000L, config = cfg)
        assertPendingShow(result, authCat)
    }

    @Test
    fun `Hidden plus not worthy stays Hidden`() {
        val result = bannerHysteresisReducer(
            prev = BannerHysteresisState(),
            input = null,
            now = 1_000L,
            config = cfg,
        )
        assertHidden(result)
    }

    @Test
    fun `full lifecycle transient blip never shows`() {
        var state = BannerHysteresisState()
        state = bannerHysteresisReducer(state, outageInput, now = 1_000L, config = cfg)
        assertPendingShow(state, outageCat)
        state = bannerHysteresisReducer(state, outageInput, now = 3_000L, config = cfg)
        assertPendingShow(state, outageCat)
        state = bannerHysteresisReducer(state, input = null, now = 3_000L, config = cfg)
        assertHidden(state)
    }

    @Test
    fun `full lifecycle sustained disconnect shows then recovers with minDisplay`() {
        var state = BannerHysteresisState()
        state = bannerHysteresisReducer(state, outageInput, now = 0L, config = cfg)
        state = bannerHysteresisReducer(state, outageInput, now = 5_000L, config = cfg)
        assertShowing(state, outageCat)
        state = bannerHysteresisReducer(state, outageInput, now = 10_000L, config = cfg)
        assertShowing(state, outageCat)
        state = bannerHysteresisReducer(state, input = null, now = 11_000L, config = cfg)
        assertPendingHide(state, outageCat)
        state = bannerHysteresisReducer(state, input = null, now = 12_000L, config = cfg)
        assertPendingHide(state, outageCat)
        state = bannerHysteresisReducer(state, outageInput, now = 12_000L, config = cfg)
        assertShowing(state, outageCat)
        state = bannerHysteresisReducer(state, outageInput, now = 20_000L, config = cfg)
        assertShowing(state, outageCat)
        state = bannerHysteresisReducer(state, input = null, now = 25_000L, config = cfg)
        assertPendingHide(state, outageCat)
        state = bannerHysteresisReducer(state, input = null, now = 31_000L, config = cfg)
        assertHidden(state)
    }

    @Test
    fun `minDisplay not met prevents premature hide`() {
        var state = BannerHysteresisState()
        state = bannerHysteresisReducer(state, outageInput, now = 0L, config = cfg)
        state = bannerHysteresisReducer(state, outageInput, now = 5_000L, config = cfg)
        assertShowing(state, outageCat)
        state = bannerHysteresisReducer(state, input = null, now = 6_000L, config = cfg)
        assertShowing(state, outageCat)
        state = bannerHysteresisReducer(state, input = null, now = 9_000L, config = cfg)
        assertPendingHide(state, outageCat)
    }

    // ── §C3 authReason tests ──────────────────────────────────────────

    @Test
    fun `authReason is preserved through PendingShow showing PendingHide cycle`() {
        var state = bannerHysteresisReducer(BannerHysteresisState(), authInput, now = 0L, config = cfg)
        val ps = state.phase as BannerHysteresisPhase.PendingShow
        assertEquals("cert expired", ps.authReason)
        state = bannerHysteresisReducer(state, authInput, now = 6_000L, config = cfg)
        assertShowing(state, authCat, expectedAuthReason = "cert expired")
        state = bannerHysteresisReducer(state, input = null, now = 10_000L, config = cfg)
        val ph = state.phase as BannerHysteresisPhase.PendingHide
        assertEquals("cert expired", ph.authReason)
        val vis = state.visibility as BannerVisibility.Showing
        assertEquals("cert expired", vis.authReason)
    }

    @Test
    fun `authReason changes when category flips REST_OUTAGE to AUTH_FAILURE`() {
        var state = bannerHysteresisReducer(BannerHysteresisState(), outageInput, now = 0L, config = cfg)
        state = bannerHysteresisReducer(state, outageInput, now = 6_000L, config = cfg)
        assertShowing(state, outageCat, expectedAuthReason = null)
        state = bannerHysteresisReducer(state, authInput, now = 10_000L, config = cfg)
        assertShowing(state, authCat, expectedAuthReason = "cert expired")
    }

    @Test
    fun `AUTH_FAILURE payload carries the auth reason into visibility`() {
        val state = bannerHysteresisReducer(BannerHysteresisState(), authInput, now = 0L, config = cfg)
        val ps = state.phase as BannerHysteresisPhase.PendingShow
        assertEquals("cert expired", ps.authReason)
        val state2 = bannerHysteresisReducer(state, authInput, now = 6_000L, config = cfg)
        val showing = state2.visibility as BannerVisibility.Showing
        assertEquals(BannerCategory.AUTH_FAILURE, showing.category)
        assertEquals("cert expired", showing.authReason)
    }

    // ── §C1 computeHysteresisDeadlineMs tests ─────────────────────────

    @Test
    fun `deadline is at atMs plus grace for PendingShow`() {
        val state = BannerHysteresisState(
            visibility = BannerVisibility.Hidden,
            phase = BannerHysteresisPhase.PendingShow(outageCat, null, 1_000L),
        )
        val deadline = computeHysteresisDeadlineMs(state, now = 500L, config = cfg)
        assertNotNull(deadline)
        assertEquals(6_000L, deadline)
    }

    @Test
    fun `deadline is at atMs plus recoverDelay for PendingHide`() {
        val state = BannerHysteresisState(
            visibility = BannerVisibility.Showing(outageCat, null, 0L),
            phase = BannerHysteresisPhase.PendingHide(outageCat, null, 10_000L, 0L),
        )
        val deadline = computeHysteresisDeadlineMs(state, now = 10_000L, config = cfg)
        assertNotNull(deadline)
        assertEquals(15_000L, deadline)
    }

    @Test
    fun `deadline is at sinceMs plus minDisplay for Showing`() {
        // §b4-rev2 🔴1: Showing MUST schedule a re-evaluation at sinceMs+minDisplayMs.
        // Without it, a recovery landing inside the min-display window leaves the banner
        // stuck in Showing forever (no category event fires while healthy). The deadline
        // is the min-display expiry that drives Showing→PendingHide once it elapses.
        val state = BannerHysteresisState(
            visibility = BannerVisibility.Showing(outageCat, null, 1_000L),
            phase = BannerHysteresisPhase.Showing(outageCat, null, 1_000L),
        )
        val deadline = computeHysteresisDeadlineMs(state, now = 500L, config = cfg)
        assertNotNull(deadline)
        // sinceMs(1_000) + minDisplayMs(3_000) = 4_000
        assertEquals(4_000L, deadline)
    }

    @Test
    fun `deadline is null for Hidden`() {
        assertNull(computeHysteresisDeadlineMs(BannerHysteresisState(), now = 0L, config = cfg))
    }

    // ── §C3 payload coherence tests ───────────────────────────────────

    @Test
    fun `PendingHide preserves original category and authReason when feedback becomes Live`() {
        var state = bannerHysteresisReducer(BannerHysteresisState(), authInput, now = 0L, config = cfg)
        state = bannerHysteresisReducer(state, authInput, now = 6_000L, config = cfg)
        assertShowing(state, authCat, expectedAuthReason = "cert expired")
        state = bannerHysteresisReducer(state, input = null, now = 10_000L, config = cfg)
        val vis = state.visibility as BannerVisibility.Showing
        assertEquals(BannerCategory.AUTH_FAILURE, vis.category)
        assertEquals("cert expired", vis.authReason)
        assertEquals(6_000L, vis.sinceMs)
    }

    @Test
    fun `PendingHide after REST_OUTAGE preserves category when feedback becomes Live`() {
        var state = bannerHysteresisReducer(BannerHysteresisState(), outageInput, now = 0L, config = cfg)
        state = bannerHysteresisReducer(state, outageInput, now = 6_000L, config = cfg)
        assertShowing(state, outageCat, expectedAuthReason = null)
        state = bannerHysteresisReducer(state, input = null, now = 10_000L, config = cfg)
        val vis = state.visibility as BannerVisibility.Showing
        assertEquals(BannerCategory.REST_OUTAGE, vis.category)
        assertNull(vis.authReason)
        assertEquals(6_000L, vis.sinceMs)
    }
}
