package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.SessionStatusLoadTrigger
import cn.vectory.ocdroid.ui.launchLoadSessionStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
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
 * T-R1 (slimapi R1) — STATUS POLLING DOWNGRADE regression contract (方案A).
 *
 * Spec: `docs/ocmar/specs/2026-07-22-full-refactor-plan.md` §1.1 T-R1 row
 * + §7.8 R1 row.
 * Contract authority: `docs/ocmar/reports/2026-07-22-refactor-progress-handoff.md`
 * §2 (T-R1 语义 = 方案A) + §6.2 (A contract points 1/3/6).
 *
 * # 方案A contract frozen here
 *
 *  **Point 1 — slim connected 4s sweep = ZERO periodic status REST.**
 *  [UnreadSoakController.ACTIVE_REFRESH_INTERVAL_MS] (=4000) drives the
 *  foreground sweep → `requestStatusRefresh` callback →
 *  [launchLoadSessionStatus]. In slim connected mode this sweep MUST NOT
 *  issue ANY status REST — neither legacy `/session/status` +
 *  `/api/session/active` NOR periodic slim bulk `/slimapi/sessions/status`.
 *  Status arrives via the slim digest `status` relay (steady-state, point 3)
 *  + the cold-start one-time bulk (point 2, frozen in
 *  `StatusPollingDowngradeSeamsRegressionTest`).
 *
 *  **Point 3 — slim digest `status` relay (GREEN, existing, unchanged).**
 *  The slim steady-state status source is `SessionSyncCoordinator
 *  .handleSessionDigest` → `applySessionStatus` folding `session.digest`
 *  `.status` into `sessionStatuses`. This is what keeps status fresh
 *  WITHOUT periodic REST in slim mode.
 *
 *  **Point 6 — legacy byte-for-byte unchanged (GREEN characterization).**
 *  Legacy mode keeps the 4s cadence + REST fan-out unchanged. The impl
 *  lane cannot over-reach and break legacy.
 *
 * # 方案A implementation status (impl lane: A-impl done)
 *
 * The A-impl rework added a `trigger` parameter to [launchLoadSessionStatus]:
 * `SWEEP` (default, the 4s foreground sweep) is a zero-REST no-op in slim
 * connected mode (returns before the epoch bump); `COLD_START`
 * (app/session/host-connect init) routes through the slim bulk helper. The
 * slim sweep tests (Group 1) assert ZERO slim bulk calls and are now GREEN.
 * Group 4 locks the cold-start seam (exactly one bulk per workdir) + the
 * epoch-order landmine (a sweep interleaved with an in-flight cold-start
 * MUST NOT bump the epoch and drop the cold-start result).
 *
 * # C3 compliance
 *
 * Nothing here touches `message.part.*` — the slim status source locked
 * here is REST endpoints + digest relay only.
 *
 * # Spec ambiguity flagged (not frozen — no assertion)
 *
 * `activeSessionIds` slim intersection semantics: the slim sweep preserves
 * the prior snapshot intersected against the authoritative tree, never
 * refreshing from server. If digest loss / host reconnect leaves stale
 * active ids, they persist until the session archives. This needs spec
 * confirmation (handoff §2). Not asserted here — the sweep becomes a
 * no-op for status REST in 方案A, so activeSessionIds is not touched by
 * the sweep at all; the digest relay's responsibility (if any) for
 * activeSessionIds is a separate concern.
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
        every { isSlimMode } returns true
        // Defensive stubs for ALL three status endpoints. The @Ignore sweep
        // tests below assert these are NEVER called; a stubbed-but-not-called
        // method still satisfies coVerify(exactly = 0).
        coEvery { getSessionStatus() } returns Result.success(emptyMap())
        coEvery { getActiveSessionIds() } returns Result.success(emptySet())
        coEvery { getSlimapiSessionsStatus(any()) } returns Result.success(emptyMap())
    }

    private fun legacyRepository(): OpenCodeRepository = mockk(relaxed = true) {
        every { isSlimMode } returns false
        coEvery { getSessionStatus() } returns Result.success(emptyMap())
        coEvery { getActiveSessionIds() } returns Result.success(emptySet())
    }

    private fun seedSessions(vararg sessions: Session) {
        store.mutateSessionList {
            it.copy(sessions = sessions.toList())
        }
    }

    private fun session(id: String, directory: String): Session =
        Session(id = id, directory = directory)

    // ═══════════════════════════════════════════════════════════════════════
    // Group 1 — SLIM CONNECTED SWEEP: ZERO PERIODIC STATUS REST (方案A point 1)
    //
    // [launchLoadSessionStatus] is the foreground sweep entry (driven by
    // [UnreadSoakController.ACTIVE_REFRESH_INTERVAL_MS]=4000 →
    // requestStatusRefresh callback). In slim connected mode 方案A requires
    // this sweep to issue ZERO status REST of any kind:
    //   - NO legacy /session/status         (GREEN — impl already branches)
    //   - NO legacy /api/session/active      (GREEN — impl already branches)
    //   - NO periodic slim bulk /slimapi/sessions/status
    //                                        (RED — Issue1: impl still calls)
    //
    // Sessions are SEEDED so the slim helper has directories to query —
    // without seeding the slim helper short-circuits on empty directories
    // and the test would pass trivially without exercising the REST path.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `slim connected sweep does NOT poll legacy session_status endpoint`() = runTest {
        val repository = slimRepository()
        seedSessions(session("s1", "/work-a"))

        launchLoadSessionStatus(scope, repository, slices)
        advanceUntilIdle()

        // 方案A point 1: slim connected sweep must not hit legacy /session/status.
        // GREEN: current impl branches to launchLoadSessionStatusSlim which never
        // calls legacy endpoints.
        coVerify(exactly = 0) { repository.getSessionStatus() }
    }

    @Test
    fun `slim connected sweep does NOT poll legacy api_session_active endpoint`() = runTest {
        val repository = slimRepository()
        seedSessions(session("s1", "/work-a"))

        launchLoadSessionStatus(scope, repository, slices)
        advanceUntilIdle()

        // 方案A point 1: slim connected sweep must not hit legacy /api/session/active.
        // GREEN: current impl branches to launchLoadSessionStatusSlim which never
        // calls legacy endpoints.
        coVerify(exactly = 0) { repository.getActiveSessionIds() }
    }

    @Test
    fun `slim connected sweep does NOT poll slim bulk sessions_status endpoint`() = runTest {
        val repository = slimRepository()
        seedSessions(
            session("s1", "/work-a"),
            session("s2", "/work-b"),
        )

        launchLoadSessionStatus(scope, repository, slices)
        advanceUntilIdle()

        // 方案A point 1 (Issue1 core): slim connected sweep must NOT issue
        // periodic slim bulk /slimapi/sessions/status. Status arrives via
        // digest relay (steady-state) + cold-start one-time bulk (point 2).
        //
        // RED against current impl: launchLoadSessionStatusSlim calls
        // getSlimapiSessionsStatus per workdir on every sweep (the "periodic
        // correction" comment). The A-impl rework must make the sweep a
        // no-op for status REST in slim connected mode.
        coVerify(exactly = 0) { repository.getSlimapiSessionsStatus(any()) }
    }

    @Test
    fun `slim connected sweep repeated invocations trigger ZERO total status REST`() = runTest {
        val repository = slimRepository()
        seedSessions(session("s1", "/work-a"))

        // Simulate N foreground sweeps (each is one launchLoadSessionStatus call).
        repeat(5) {
            launchLoadSessionStatus(scope, repository, slices)
            advanceUntilIdle()
        }

        // 方案A point 1 call-count boundary: N sweeps in slim connected mode
        // must trigger ZERO status REST of ANY kind — no legacy, no slim bulk.
        // The rev-gpt 🟠 flagged this call-count/interval boundary; this test
        // pins it: the sweep is a complete no-op for status REST.
        //
        // RED against current impl: each sweep calls getSlimapiSessionsStatus
        // once per workdir → 5 calls total.
        coVerify(exactly = 0) { repository.getSessionStatus() }
        coVerify(exactly = 0) { repository.getActiveSessionIds() }
        coVerify(exactly = 0) { repository.getSlimapiSessionsStatus(any()) }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Group 2 — LEGACY CHARACTERIZATION (方案A point 6: byte-for-byte unchanged)
    //
    // T-R1 downgrades slim only. Legacy MUST keep polling /session/status +
    // /api/session/active exactly as today (legacy has no digest relay).
    // These lock the legacy floor so the impl lane cannot over-reach.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `legacy mode - launchLoadSessionStatus polls session_status endpoint`() = runTest {
        val repository = legacyRepository()

        launchLoadSessionStatus(scope, repository, slices)
        advanceUntilIdle()

        // Legacy MUST keep polling /session/status (no digest relay).
        coVerify(atLeast = 1) { repository.getSessionStatus() }
    }

    @Test
    fun `legacy mode - launchLoadSessionStatus polls api_session_active endpoint`() = runTest {
        val repository = legacyRepository()

        launchLoadSessionStatus(scope, repository, slices)
        advanceUntilIdle()

        // Legacy MUST keep polling /api/session/active (active has no SSE).
        coVerify(atLeast = 1) { repository.getActiveSessionIds() }
    }

    @Test
    fun `legacy ACTIVE_REFRESH_INTERVAL_MS remains the 4s foreground cadence`() {
        // 方案A point 6: T-R1 downgrades slim only. The legacy foreground
        // active-poll cadence constant stays at 4s.
        assertEquals(
            "legacy foreground active-poll cadence must stay 4s",
            4_000L,
            UnreadSoakController.ACTIVE_REFRESH_INTERVAL_MS,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Group 3 — SLIM DIGEST `status` RELAY CHARACTERIZATION (方案A point 3)
    //
    // The slim steady-state status source: SessionSyncCoordinator
    // .handleSessionDigest folds SlimSessionDigest.status → sessionStatuses
    // via applySessionStatus. T-R1's "digest status relay" requirement is
    // satisfied by THIS fold. These tests lock the fold surface so the impl
    // lane keeps it intact while making the sweep zero-REST.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `slim digest status relay folds busy status into sessionStatuses`() {
        // Mirror SessionSyncCoordinator.handleSessionDigest:
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
        // The relay is the slim steady-state status source: a later digest
        // frame with a new status MUST overwrite the prior value (last-write-
        // wins at the slice level — the relay keeps status fresh WITHOUT
        // periodic REST in slim mode).
        val sid = "slim-session-2"
        store.mutateSessionList {
            it.copy(sessions = listOf(Session(id = sid, directory = "/x")))
        }

        store.mutateSessionList {
            it.applySessionStatus(sid, SessionStatus(type = "busy")).first
        }
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
        // Sanity-lock the exact pure function the relay depends on. This is
        // the surface T-R1's "digest status relay" maps to.
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

    // ═══════════════════════════════════════════════════════════════════════
    // Group 4 — SLIM COLD-START: ONE BULK PER WORKDIR + EPOCH-ORDER SEAM
    // (方案A point 2; fills the StatusPollingDowngradeSeamsRegressionTest
    // design gap documented in that file's header.)
    //
    // 方案A point 2: the cold-start entry (trigger=COLD_START) issues exactly
    // ONE bulk GET /slimapi/sessions/status per registered workdir. This is
    // distinct from the SWEEP no-op (Group 1). The epoch-order test locks the
    // 🔴 A-2 landmine: a SWEEP no-op interleaved with an in-flight cold-start
    // MUST NOT bump statusLoadEpoch (the cold-start result must survive).
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `slim cold-start issues exactly one slim bulk call per registered workdir`() = runTest {
        val repository = slimRepository()
        seedSessions(
            session("s1", "/work-a"),
            session("s2", "/work-b"),
        )

        launchLoadSessionStatus(
            scope,
            repository,
            slices,
            trigger = SessionStatusLoadTrigger.COLD_START,
        )
        advanceUntilIdle()

        // 方案A point 2: cold-start issues ONE bulk per workdir (2 workdirs → 2 calls).
        // No legacy endpoints touched.
        coVerify(exactly = 1) { repository.getSlimapiSessionsStatus("/work-a") }
        coVerify(exactly = 1) { repository.getSlimapiSessionsStatus("/work-b") }
        coVerify(exactly = 0) { repository.getSessionStatus() }
        coVerify(exactly = 0) { repository.getActiveSessionIds() }
    }

    @Test
    fun `slim cold-start bulk result survives an interleaved sweep no-op - epoch order guard`() = runTest {
        // 🔴 A-2 EPOCH-ORDER landmine guard: the slim SWEEP short-circuit MUST
        // NOT bump statusLoadEpoch. If it did, a 4s sweep interleaved with an
        // in-flight cold-start bulk would make the cold-start's epoch guard
        // (myEpoch != statusLoadEpoch.get() inside launchLoadSessionStatusSlim)
        // discard its result — the cold-start snapshot would be silently lost.
        val repository = slimRepository()
        val gate = CompletableDeferred<Unit>()
        var slimBulkCalls = 0
        coEvery { repository.getSlimapiSessionsStatus(any()) } coAnswers {
            slimBulkCalls++
            gate.await()
            Result.success(mapOf("s1" to SessionStatus(type = "idle")))
        }
        seedSessions(session("s1", "/work-a"))

        // COLD_START enters the slim bulk path and suspends on the gate.
        launchLoadSessionStatus(
            scope,
            repository,
            slices,
            trigger = SessionStatusLoadTrigger.COLD_START,
        )
        advanceUntilIdle()
        assertEquals("cold-start issued exactly one slim bulk call", 1, slimBulkCalls)

        // While cold-start is suspended, fire two sweeps. 方案A: each SWEEP is a
        // no-op for status REST and MUST NOT bump statusLoadEpoch. If a sweep
        // bumped the epoch, the cold-start's result would be dropped below.
        repeat(2) {
            launchLoadSessionStatus(scope, repository, slices) // default SWEEP
        }
        advanceUntilIdle()
        assertEquals("sweeps issued zero additional slim bulk calls", 1, slimBulkCalls)

        // Release the cold-start bulk.
        gate.complete(Unit)
        advanceUntilIdle()

        // The cold-start result landed (epoch guard passed): s1 status folded.
        val folded = slices.sessionList.value.sessionStatuses["s1"]
        assertNotNull("cold-start bulk result survived the interleaved sweep no-op", folded)
        assertEquals("idle", folded?.type)
    }
}
