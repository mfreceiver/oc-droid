package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.service.streaming.ProcessStatusPoller
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-R1 (slimapi R1) — STATUS POLLING DOWNGRADE *seam-boundary* regression
 * locks (方案A).
 *
 * Spec: `docs/ocmar/specs/2026-07-22-full-refactor-plan.md` §1.1 T-R1 +
 * §7.8 R1.
 * Contract: `docs/ocmar/reports/2026-07-22-refactor-progress-handoff.md`
 * §2 (方案A) + §6.2 (points 2 + 4).
 *
 * # What this file freezes
 *
 *  **Point 4 — disconnect fallback cadence band (GREEN).**
 *  On slim SSE loss, `ProcessStatusPoller` (≤30s) drives status refresh
 *  through slim endpoints (`getSlimapiSessionsStatus` / `SlimStatusFanOut`),
 *  NOT legacy `/session/status`. The cadence must sit in [10s, 30s] — ≥10s
 *  so it is a real downgrade from the legacy 4s sweep; ≤30s so it stays
 *  responsive. The disconnect-fallback endpoint routing is covered in
 *  `StatusAggregatorImplTest` (slim refresh branch).
 *
 * # What this file does NOT freeze (filled elsewhere)
 *
 *  **Point 2 — cold-start one-time bulk (filled by impl lane).**
 *  方案A requires slim cold-start (app/session/host-connect init) to issue
 *  ONE bulk `GET /slimapi/sessions/status` per workdir. The A-impl rework
 *  added a `trigger` parameter to [launchLoadSessionStatus]:
 *    (a) the sweep path (trigger=SWEEP) is a no-op for status REST in slim
 *        connected mode (frozen in `StatusPollingDowngradeRegressionTest`
 *        Group 1);
 *    (b) the cold-start entry (trigger=COLD_START) issues exactly ONE bulk
 *        per workdir (frozen in `StatusPollingDowngradeRegressionTest`
 *        Group 4, including the epoch-order landmine guard).
 *
 *  **Spec ambiguity — cold-start trigger point.** The exact lifecycle
 *  event that fires cold-start (app start / session list load / host
 *  connect / SSE ready) is NOT specified concretely enough to freeze.
 *  The handoff §6.2 says "app/session/host-connect start" — the impl
 *  lane should confirm the trigger point and document it. Flagged for
 *  spec confirmation; no assertion frozen.
 *
 * # C3 compliance
 *
 * Nothing here touches `message.part.*`. The slim status sources are REST
 * endpoints (disconnect fallback) + the cold-start bulk (design gap).
 *
 * # Round-2 history
 *
 * The prior round-2 freeze (7ec36cb) locked B-semantics "cold-start" tests
 * that asserted `launchLoadSessionStatus` DOES call slim bulk from the
 * sweep. Those are removed — 方案A rejects periodic bulk from the sweep.
 * The disconnect cadence test is retained (GREEN, unchanged).
 */
class StatusPollingDowngradeSeamsRegressionTest {

    // ═══════════════════════════════════════════════════════════════════════
    // Disconnect fallback cadence band (方案A point 4)
    //
    // On slim SSE loss, ProcessStatusPoller drives the degraded fallback
    // at DEFAULT_INTERVAL_MS. T-R1 requires the fallback cadence to sit in
    // the 10–30s band: ≥10s so it is a real downgrade from the legacy 4s
    // sweep; ≤30s so it stays responsive.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `T-R1 disconnect fallback cadence is in the 10s-30s band`() {
        val cadence = ProcessStatusPoller.DEFAULT_INTERVAL_MS
        assertTrue(
            "fallback cadence must be >= 10s (a real downgrade from legacy 4s): got $cadence",
            cadence >= 10_000L,
        )
        assertTrue(
            "fallback cadence must be <= 30s (stay responsive): got $cadence",
            cadence <= 30_000L,
        )
    }

    @Test
    fun `T-R1 disconnect fallback cadence is a real downgrade from the 4s foreground sweep`() {
        // 方案A point 4: the disconnect fallback cadence MUST be strictly
        // greater than the legacy 4s sweep cadence. This confirms the fallback
        // is a genuine downgrade (not the same cadence re-branded). If the
        // impl lane accidentally sets the fallback to 4s, this catches it.
        val cadence = ProcessStatusPoller.DEFAULT_INTERVAL_MS
        assertTrue(
            "fallback cadence must be > 4s sweep (real downgrade): got $cadence",
            cadence > UnreadSoakController.ACTIVE_REFRESH_INTERVAL_MS,
        )
    }
}
