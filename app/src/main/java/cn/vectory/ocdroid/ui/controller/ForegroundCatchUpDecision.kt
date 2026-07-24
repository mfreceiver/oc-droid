package cn.vectory.ocdroid.ui.controller

/**
 * §defect-A-1A: pure foreground-return decision. Buckets a foreground return
 * by background-gap length AND whether SSE is effectively unavailable, so the
 * catch-up does not depend on a `server.connected` frame that may never arrive
 * when SSE is disabled / terminally disconnected.
 *
 *  - Throttle            : bg < 15s  → rely on the live feed (suppress catch-up)
 *  - CatchUpOnSseConnect : 15s–5min, SSE will deliver soon → let server.connected drive it
 *  - CatchUpNow          : 15s–5min, SSE effectively OFF → fire REST catch-up immediately
 *  - ColdStart           : bg > 5min → global cold-start reload
 */
enum class ForegroundReturnAction { Throttle, CatchUpNow, CatchUpOnSseConnect, ColdStart }

fun decideForegroundReturn(
    bgGapMs: Long,
    sseEffectivelyOff: Boolean,
    minIntervalMs: Long = 15_000L,
    longAbsenceMs: Long = 5 * 60_000L,
): ForegroundReturnAction = when {
    bgGapMs < minIntervalMs -> ForegroundReturnAction.Throttle
    bgGapMs > longAbsenceMs -> ForegroundReturnAction.ColdStart
    sseEffectivelyOff -> ForegroundReturnAction.CatchUpNow
    else -> ForegroundReturnAction.CatchUpOnSseConnect
}
