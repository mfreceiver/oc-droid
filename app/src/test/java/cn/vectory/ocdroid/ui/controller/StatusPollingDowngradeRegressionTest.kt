package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.launchLoadSessionStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-R1 (slimapi R1) — STATUS POLLING DOWNGRADE regression contract.
 *
 * Spec: `docs/ocmar/specs/2026-07-22-full-refactor-plan.md` §1.1 T-R1 row + §7.8 R1 row:
 *
 *   停 4s 轮询 `/session/status` + `/api/session/active` → `/slimapi/sessions/status`
 *   cold-start + digest `status` 接力; 断连降级 10–30s. 纯客户端, slimapi 零改动.
 *
 * # What this file freezes
 *
 * The foreground 4s status poll is driven by [UnreadSoakController.ACTIVE_REFRESH_INTERVAL_MS]
 * → `requestStatusRefresh` callback → `ControllerEffect.LoadSessionStatusWithCompletion`
 * → [launchLoadSessionStatus]. [launchLoadSessionStatus] currently issues BOTH legacy
 * endpoints (`/session/status` + `/api/session/active`) UNCONDITIONALLY, with no
 * `repository.isSlimMode` branch. T-R1 requires that in slim mode this REST fan-out
 * is stopped (status flows in via the slim digest `status` relay instead).
 *
 * This file pins three things:
 *  1. **Slim new contract (RED now)** — in slim mode the 4s-poll fan-out must NOT
 *     hit the legacy `/session/status` + `/api/session/active` endpoints.
 *  2. **Legacy characterization (GREEN now)** — the legacy 4s cadence + REST fan-out
 *     MUST stay byte-for-byte, so the impl lane does not over-reach and break legacy.
 *  3. **Slim digest relay characterization (GREEN now)** — the existing
 *     `session.digest` `status` → `sessionStatuses` fold (the slim steady-state
 *     status source T-R1 relies on) MUST keep working.
 *
 * # Design gaps flagged to the impl lane (NOT frozen here — not implementable today)
 *
 * These pieces of the T-R1 contract do NOT exist in the client yet and CANNOT be
 * asserted by a compiling test until the impl lane adds the seam:
 *
 *  - **Bulk `/slimapi/sessions/status` Retrofit method is MISSING** from [OpenCodeApi].
 *    Only the per-session `getSlimapiSessionStatus` (`/slimapi/sessions/{sid}/status`)
 *    exists. `SlimStatusFanOut`'s docstring references a bulk `GET /slimapi/sessions/status`
 *    but [StatusAggregatorImpl.refresh] actually calls legacy `/session/status` — the
 *    bulk slim endpoint is aspirational. The impl lane must add the client Retrofit
 *    method + repository facade for the cold-start bulk fetch.
 *  - **Disconnect / SSE-loss degraded fallback (10–30s) does NOT exist** for the
 *    slim foreground path. No component / cadence const today. The impl lane must
 *    add it (and when it does, add a regression test asserting the cadence is in
 *    [10_000, 30_000] ms).
 *  - **No mode/connectedness seam on [UnreadSoakController]** — so the slim-connected
 *    suppression of the 4s sweep cannot be tested at the controller level without a
 *    production-side seam. The frozen RED tests below target [launchLoadSessionStatus]
 *    instead, which already has access to [OpenCodeRepository.isSlimMode].
 *
 * C3: this file never asserts that slim SSE carries `message.part.*` (slim SSE
 * discards it). The slim status source is the digest relay only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusPollingDowngradeRegressionTest {

    private lateinit var store: SharedStateStore
    private lateinit var slices: SliceFlows
    private lateinit var scope: TestScope

    @Before
    fun setUp() {
        store = SharedStateStore()
        slices = store.slices
        scope = TestScope(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun slimRepository(): OpenCodeRepository = mockk(relaxed = true) {
        // The slim-mode seam ALREADY exists on OpenCodeRepository (val isSlimMode).
        // The impl lane can satisfy the RED tests below by branching on this.
        every { isSlimMode } returns true
        // Stub both legacy endpoints for success so the test never crashes with a
        // null Result if the current code path still calls them (it does). The
        // assertions below use coVerify(exactly = 0) — a stubbed-but-not-called
        // method still satisfies exactly = 0.
        coEvery { getSessionStatus() } returns Result.success(emptyMap())
        coEvery { getActiveSessionIds() } returns Result.success(emptySet())
    }

    private fun legacyRepository(): OpenCodeRepository = mockk(relaxed = true) {
        every { isSlimMode } returns false
        coEvery { getSessionStatus() } returns Result.success(emptyMap())
        coEvery { getActiveSessionIds() } returns Result.success(emptySet())
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Group 1 — SLIM NEW CONTRACT (expected RED against current code)
    //
    // Current [launchLoadSessionStatus] calls BOTH legacy endpoints unconditionally
    // (no isSlimMode branch). These tests assert the NEW contract — slim mode must
    // NOT hit them — and FAIL now because the current code still does the 4s-poll
    // fan-out into `/session/status` + `/api/session/active`.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `slim mode - launchLoadSessionStatus does NOT poll legacy session_status endpoint`() =
        runTest {
            val repository = slimRepository()

            launchLoadSessionStatus(scope, repository, slices)
            advanceUntilIdle()

            // T-R1 contract: in slim mode the 4s-poll REST fan-out that drives
            // GET /session/status MUST be stopped. Status arrives via the slim
            // digest `status` relay (see Group 3) + /slimapi/sessions/status
            // cold-start (design gap — impl lane adds the endpoint).
            //
            // EXPECTED-RED: current code calls getSessionStatus() unconditionally.
            coVerify(exactly = 0) {
                repository.getSessionStatus()
            }
        }

    @Test
    fun `slim mode - launchLoadSessionStatus does NOT poll legacy api_session_active endpoint`() =
        runTest {
            val repository = slimRepository()

            launchLoadSessionStatus(scope, repository, slices)
            advanceUntilIdle()

            // T-R1 contract: in slim mode GET /api/session/active (the "active
            // sessions" fan-out piggybacked onto the 4s status poll) MUST be
            // stopped. The /api/session/active lane exists solely because
            // "active has no SSE" (UnreadSoakController.kt:101) — in slim mode
            // the digest relay covers activity; the legacy active poll is
            // redundant REST traffic T-R1 removes.
            //
            // EXPECTED-RED: current code calls getActiveSessionIds() unconditionally.
            coVerify(exactly = 0) {
                repository.getActiveSessionIds()
            }
        }

    // ═══════════════════════════════════════════════════════════════════════
    // Group 2 — LEGACY CHARACTERIZATION (expected GREEN; locks legacy so the
    // impl lane cannot over-reach and break the legacy 4s poll)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `legacy mode - launchLoadSessionStatus polls session_status endpoint`() = runTest {
        val repository = legacyRepository()

        launchLoadSessionStatus(scope, repository, slices)
        advanceUntilIdle()

        // T-R1 is slim-focused. Legacy MUST keep polling /session/status exactly
        // as today (legacy has no digest relay). This locks the legacy floor.
        coVerify(atLeast = 1) { repository.getSessionStatus() }
    }

    @Test
    fun `legacy mode - launchLoadSessionStatus polls api_session_active endpoint`() = runTest {
        val repository = legacyRepository()

        launchLoadSessionStatus(scope, repository, slices)
        advanceUntilIdle()

        // Legacy MUST keep polling /api/session/active (active has no SSE in legacy).
        coVerify(atLeast = 1) { repository.getActiveSessionIds() }
    }

    @Test
    fun `legacy ACTIVE_REFRESH_INTERVAL_MS remains the 4s foreground cadence`() {
        // T-R1 downgrades slim only. The legacy foreground active-poll cadence
        // constant stays at 4s. If the impl lane changes this number, legacy
        // behavior regresses — this lock catches that.
        assertEquals(
            "legacy foreground active-poll cadence must stay 4s",
            4_000L,
            UnreadSoakController.ACTIVE_REFRESH_INTERVAL_MS,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Group 3 — SLIM DIGEST `status` RELAY CHARACTERIZATION (expected GREEN;
    // locks the slim steady-state status source T-R1 relies on)
    //
    // The relay already exists: SessionSyncCoordinator.handleSessionDigest folds
    // SlimSessionDigest.status → sessionStatuses via applySessionStatus. T-R1's
    // "digest status relay" requirement is satisfied by THIS fold. These tests
    // lock the fold surface so the impl lane keeps it intact while re-routing
    // the foreground REST poll away from legacy endpoints.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `slim digest status relay folds busy status into sessionStatuses`() {
        // Mirror exactly what SessionSyncCoordinator.handleSessionDigest does at
        // SessionSyncCoordinator.kt:2190-2192:
        //   slices.mutateSessionList {
        //       it.applySessionStatus(digest.sessionId, SessionStatus(type = statusType)).first
        //   }
        val sid = "slim-session-1"
        store.mutateSessionList {
            it.copy(sessions = listOf(Session(id = sid, directory = "/x")))
        }

        store.mutateSessionList {
            it.applySessionStatus(sid, SessionStatus(type = "busy")).first
        }

        val folded = store.sessionListFlow.value.sessionStatuses[sid]
        assertNotNull("digest status relay must populate sessionStatuses", folded)
        assertEquals("busy", folded?.type)
    }

    @Test
    fun `slim digest status relay overwrites prior status (event-driven freshness)`() {
        // The relay is the slim steady-state status source: a later digest frame
        // with a new status MUST overwrite the prior value (merge-timing for the
        // sessionStatuses map is last-write-wins at the slice level — the relay
        // is what keeps status fresh WITHOUT periodic REST in slim mode).
        val sid = "slim-session-2"
        store.mutateSessionList {
            it.copy(sessions = listOf(Session(id = sid, directory = "/x")))
        }

        store.mutateSessionList {
            it.applySessionStatus(sid, SessionStatus(type = "busy")).first
        }
        // A later digest frame arrives — session went idle.
        store.mutateSessionList {
            it.applySessionStatus(sid, SessionStatus(type = "idle")).first
        }

        val folded = store.sessionListFlow.value.sessionStatuses[sid]
        assertEquals(
            "relay must reflect the LATEST digest status (event-driven freshness)",
            "idle",
            folded?.type,
        )
    }

    @Test
    fun `slim digest status relay is the non-REST status source - slice write surface exists`() {
        // Sanity-lock the exact pure function the relay depends on. This is the
        // surface T-R1's "digest status relay" requirement maps to. If the impl
        // lane (or a later task) removes/renames it, this fails loudly.
        val sid = "slim-session-3"
        val before = store.sessionListFlow.value.sessionStatuses
        assertTrue("no prior status for sid", sid !in before)

        val (next, effects) = store.sessionListFlow.value
            .applySessionStatus(sid, SessionStatus(type = "retry"))

        assertSame(
            "applySessionStatus produces no side effects (pure fold, like reduce)",
            emptyList<Any>(),
            effects,
        )
        assertEquals(
            "relay writes the digest status into the map",
            "retry",
            next.sessionStatuses[sid]?.type,
        )
    }
}
