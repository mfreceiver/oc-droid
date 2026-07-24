package cn.vectory.ocdroid.ui.controller

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §defect-A-1A: pure-function coverage for [decideForegroundReturn]. Exercises
 * all four branches, the boundary values (15s / 5min), and the rule that
 * `sseEffectivelyOff` ONLY matters inside the medium band (15s–5min) — below
 * the throttle floor the live feed is relied on regardless, and above the long
 * absence ceiling cold-start fires regardless.
 */
class ForegroundCatchUpDecisionTest {

    // ── throttle band (bg < 15s) ────────────────────────────────────────────

    @Test
    fun `under 15s is Throttle regardless of SSE availability`() {
        assertEquals(
            ForegroundReturnAction.Throttle,
            decideForegroundReturn(bgGapMs = 0L, sseEffectivelyOff = false),
        )
        assertEquals(
            "even with SSE off, the live feed wins below the floor",
            ForegroundReturnAction.Throttle,
            decideForegroundReturn(bgGapMs = 14_999L, sseEffectivelyOff = true),
        )
    }

    @Test
    fun `exactly 15s is NOT under the floor - falls through to medium`() {
        // bgGapMs < minIntervalMs is exclusive on the lower bound — 15000 is
        // NOT < 15000, so it lands in the medium tier (SSE on → CatchUpOnSseConnect).
        assertEquals(
            ForegroundReturnAction.CatchUpOnSseConnect,
            decideForegroundReturn(bgGapMs = 15_000L, sseEffectivelyOff = false),
        )
        assertEquals(
            "15s + SSE off → CatchUpNow (medium band)",
            ForegroundReturnAction.CatchUpNow,
            decideForegroundReturn(bgGapMs = 15_000L, sseEffectivelyOff = true),
        )
    }

    // ── medium band (15s ≤ bg ≤ 5min) ───────────────────────────────────────

    @Test
    fun `medium band with SSE on waits for server connected`() {
        assertEquals(
            ForegroundReturnAction.CatchUpOnSseConnect,
            decideForegroundReturn(bgGapMs = 20_000L, sseEffectivelyOff = false),
        )
        assertEquals(
            ForegroundReturnAction.CatchUpOnSseConnect,
            decideForegroundReturn(bgGapMs = 4 * 60_000L, sseEffectivelyOff = false),
        )
    }

    @Test
    fun `medium band with SSE off fires REST catch-up now`() {
        assertEquals(
            ForegroundReturnAction.CatchUpNow,
            decideForegroundReturn(bgGapMs = 20_000L, sseEffectivelyOff = true),
        )
        assertEquals(
            ForegroundReturnAction.CatchUpNow,
            decideForegroundReturn(bgGapMs = 4 * 60_000L, sseEffectivelyOff = true),
        )
    }

    @Test
    fun `exactly 5min is NOT above the ceiling - stays medium`() {
        // 5 * 60_000 = 300_000; bgGapMs > longAbsenceMs is exclusive — 300000
        // is NOT > 300000, so it stays medium (NOT ColdStart).
        assertEquals(
            ForegroundReturnAction.CatchUpOnSseConnect,
            decideForegroundReturn(bgGapMs = 300_000L, sseEffectivelyOff = false),
        )
        assertEquals(
            "5min + SSE off → CatchUpNow (still medium)",
            ForegroundReturnAction.CatchUpNow,
            decideForegroundReturn(bgGapMs = 300_000L, sseEffectivelyOff = true),
        )
    }

    // ── cold-start band (bg > 5min) ─────────────────────────────────────────

    @Test
    fun `over 5min is ColdStart regardless of SSE availability`() {
        assertEquals(
            ForegroundReturnAction.ColdStart,
            decideForegroundReturn(bgGapMs = 300_001L, sseEffectivelyOff = false),
        )
        assertEquals(
            "SSE on/off is irrelevant above the ceiling",
            ForegroundReturnAction.ColdStart,
            decideForegroundReturn(bgGapMs = 300_001L, sseEffectivelyOff = true),
        )
        assertEquals(
            ForegroundReturnAction.ColdStart,
            decideForegroundReturn(bgGapMs = 6 * 60_000L, sseEffectivelyOff = false),
        )
    }

    @Test
    fun `very large gap stays ColdStart - never overflow to medium`() {
        assertEquals(
            ForegroundReturnAction.ColdStart,
            decideForegroundReturn(bgGapMs = Long.MAX_VALUE, sseEffectivelyOff = false),
        )
    }

    // ── sseEffectivelyOff only matters in the medium band ──────────────────

    @Test
    fun `sseEffectivelyOff is irrelevant outside the medium band`() {
        // Throttle band: both should be Throttle.
        assertEquals(
            ForegroundReturnAction.Throttle,
            decideForegroundReturn(bgGapMs = 5_000L, sseEffectivelyOff = false),
        )
        assertEquals(
            ForegroundReturnAction.Throttle,
            decideForegroundReturn(bgGapMs = 5_000L, sseEffectivelyOff = true),
        )
        // ColdStart band: both should be ColdStart.
        assertEquals(
            ForegroundReturnAction.ColdStart,
            decideForegroundReturn(bgGapMs = 10 * 60_000L, sseEffectivelyOff = false),
        )
        assertEquals(
            ForegroundReturnAction.ColdStart,
            decideForegroundReturn(bgGapMs = 10 * 60_000L, sseEffectivelyOff = true),
        )
    }

    // ── custom thresholds ──────────────────────────────────────────────────

    @Test
    fun `custom thresholds are respected`() {
        // minInterval=10s, longAbsence=2min → 15s is medium, 130s is ColdStart.
        assertEquals(
            ForegroundReturnAction.CatchUpOnSseConnect,
            decideForegroundReturn(
                bgGapMs = 15_000L,
                sseEffectivelyOff = false,
                minIntervalMs = 10_000L,
                longAbsenceMs = 120_000L,
            ),
        )
        assertEquals(
            ForegroundReturnAction.ColdStart,
            decideForegroundReturn(
                bgGapMs = 130_000L,
                sseEffectivelyOff = false,
                minIntervalMs = 10_000L,
                longAbsenceMs = 120_000L,
            ),
        )
    }
}
