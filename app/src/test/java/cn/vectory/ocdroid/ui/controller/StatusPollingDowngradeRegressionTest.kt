package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.ConnectionPhase
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
import org.junit.Assert.assertNull
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

    /** §P0-A test helper: seed a prior session status through the authority
     *  reducer (the SOLE writer now), so authority.bySid is populated exactly
     *  as production would after an SSE/REST status event. */
    private fun seedStatusAuthorityEvent(
        sid: String,
        status: SessionStatus,
    ): cn.vectory.ocdroid.ui.AppAction = cn.vectory.ocdroid.ui.AppAction.AuthorityEvent(
        cn.vectory.ocdroid.data.state.AuthorityOp.ApplyEvent(
            sid = sid,
            status = status,
            origin = cn.vectory.ocdroid.data.state.EntryOrigin.SSE_LEGACY,
            scopeKey = cn.vectory.ocdroid.data.state.ScopeKey(serverGroupFp = "", endpointFp = ""),
            connectionMonotonicMs = 0L,
        ),
    )

    private fun slimRepository(): OpenCodeRepository = mockk(relaxed = true) {
        every { usesSlimStatusFanOut } returns true
        // Defensive stubs for ALL three status endpoints. The @Ignore sweep
        // tests below assert these are NEVER called; a stubbed-but-not-called
        // method still satisfies coVerify(exactly = 0).
        coEvery { getSessionStatus() } returns Result.success(emptyMap())
        coEvery { getActiveSessionIds() } returns Result.success(emptySet())
        coEvery { getSlimapiSessionsStatus(any()) } returns Result.success(emptyMap())
    }

    private fun legacyRepository(): OpenCodeRepository = mockk(relaxed = true) {
        every { usesSlimStatusFanOut } returns false
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
        // Slim connected = transport delivering (isSseConnected=true). The
        // transport-grounded predicate short-circuits the sweep.
        setSseConnected(true)

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
        // Slim connected = transport delivering (isSseConnected=true).
        setSseConnected(true)

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
        // Slim connected = transport delivering (isSseConnected=true).
        setSseConnected(true)

        launchLoadSessionStatus(scope, repository, slices)
        advanceUntilIdle()

        // 方案A point 1 (Issue1 core): slim connected sweep must NOT issue
        // periodic slim bulk /slimapi/sessions/status. Status arrives via
        // digest relay (steady-state) + cold-start one-time bulk (point 2).
        //
        // Transport-grounded predicate: isSseConnected=true → sweep no-op.
        coVerify(exactly = 0) { repository.getSlimapiSessionsStatus(any()) }
    }

    @Test
    fun `slim connected sweep repeated invocations trigger ZERO total status REST`() = runTest {
        val repository = slimRepository()
        seedSessions(session("s1", "/work-a"))
        // Slim connected = transport delivering (isSseConnected=true).
        setSseConnected(true)

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
        // Transport-grounded predicate: isSseConnected=true → every sweep no-op.
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
        //
        // The sweep short-circuit requires the transport to report delivery
        // (isSseConnected=true) under the transport-grounded predicate — this
        // is the "slim connected" steady-state where the sweep is a no-op.
        val repository = slimRepository()
        val gate = CompletableDeferred<Unit>()
        var slimBulkCalls = 0
        coEvery { repository.getSlimapiSessionsStatus(any()) } coAnswers {
            slimBulkCalls++
            gate.await()
            Result.success(mapOf("s1" to SessionStatus(type = "idle")))
        }
        seedSessions(session("s1", "/work-a"))
        setSseConnected(true)

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

    // ═══════════════════════════════════════════════════════════════════════
    // Group 5 — SLIM SSE-OFF / TERMINAL-DEGRADED SWEEP: REST FAN-OUT FALLBACK
    // (冻结方案 P0-1)
    //
    // The slim SWEEP short-circuit (Group 1) is correct ONLY when SSE is
    // effectively ON — the slim digest `status` relay
    // (SessionSyncCoordinator.handleSessionDigest → applySessionStatus) is the
    // steady-state status source, so a periodic sweep would be pure waste.
    //
    // When SSE is effectively OFF there is NO digest relay to keep status
    // fresh, so the foreground SWEEP MUST route through the EXISTING slim REST
    // status fan-out (launchLoadSessionStatusSlim: one
    // getSlimapiSessionsStatus per registered workdir). This mirrors the
    // established `sseEffectivelyOff` seam (ControllerModule /
    // ForegroundCatchUpController) and the §sse-rest-fallback design: REST is
    // the fallback transport whenever SSE cannot deliver.
    //
    // "SSE effectively OFF" uses the SAME predicate as
    // ControllerModule.sseEffectivelyOff:
    //   - ConnectionPhase.SseDisabled  (debug toggle / REST-only mode)
    //   - ConnectionPhase.Disconnected (terminal: retries exhausted / net lost)
    // Transient phases (Idle/Connecting/Connected/Reconnecting/...) keep the
    // no-op (SSE will deliver soon), so the Group 1 + Group 4 contracts stay
    // byte-for-byte preserved (the epoch-order landmine is untouched because
    // the no-op still runs BEFORE statusLoadEpoch.incrementAndGet()).
    // ═══════════════════════════════════════════════════════════════════════

    private fun setConnectionPhase(phase: ConnectionPhase) {
        store.mutateConnection { it.copy(connectionPhase = phase) }
    }

    /**
     * Sets the transport-delivery axis ([StoreState.isSseConnected]) via the
     * monotonic CAS write [SharedStateStore.mutateSseConnected]. Bumps the
     * generation so the CAS always wins (test setup — monotonic by construction).
     */
    private fun setSseConnected(connected: Boolean) {
        val gen = store.sseConnectedGeneration + 1L
        store.mutateSseConnected(connected, gen)
    }

    @Test
    fun `slim SSE-disabled sweep fans out via slim bulk REST, not a no-op (P0-1)`() = runTest {
        val repository = slimRepository()
        seedSessions(session("s1", "/work-a"), session("s2", "/work-b"))
        setConnectionPhase(ConnectionPhase.SseDisabled)

        launchLoadSessionStatus(scope, repository, slices) // default SWEEP
        advanceUntilIdle()

        // P0-1: SSE effectively OFF (SseDisabled) → no digest relay → the sweep
        // MUST fan out through the slim REST path (one bulk per workdir).
        coVerify(exactly = 1) { repository.getSlimapiSessionsStatus("/work-a") }
        coVerify(exactly = 1) { repository.getSlimapiSessionsStatus("/work-b") }
    }

    @Test
    fun `slim terminal-disconnected sweep fans out via slim bulk REST, not a no-op (P0-1)`() = runTest {
        val repository = slimRepository()
        seedSessions(session("s1", "/work-a"))
        setConnectionPhase(ConnectionPhase.Disconnected)

        launchLoadSessionStatus(scope, repository, slices) // default SWEEP
        advanceUntilIdle()

        // P0-1: terminal-degraded (Disconnected, retries exhausted) → same as
        // SseDisabled: the digest relay is down, REST must carry status.
        coVerify(exactly = 1) { repository.getSlimapiSessionsStatus("/work-a") }
    }

    @Test
    fun `slim SSE-off sweep does NOT touch legacy status endpoints (P0-1 boundary)`() = runTest {
        val repository = slimRepository()
        seedSessions(session("s1", "/work-a"))
        setConnectionPhase(ConnectionPhase.SseDisabled)

        launchLoadSessionStatus(scope, repository, slices) // default SWEEP
        advanceUntilIdle()

        // P0-1 boundary: the SSE-off fallback uses the SLIM bulk fan-out, NOT
        // the legacy /session/status + /api/session/active pair (T-R1 slim
        // contract holds even on the degraded path).
        coVerify(exactly = 0) { repository.getSessionStatus() }
        coVerify(exactly = 0) { repository.getActiveSessionIds() }
    }

    @Test
    fun `slim SSE-on sweep stays a zero-REST no-op - P0-1 does not regress the healthy path`() = runTest {
        val repository = slimRepository()
        seedSessions(session("s1", "/work-a"))
        setConnectionPhase(ConnectionPhase.Connected)
        // Transport-grounded predicate: phase=Connected alone is NOT enough —
        // the transport must report delivery (isSseConnected=true) for the
        // sweep to short-circuit. This mirrors the healthy steady-state where
        // the digest relay is actively delivering status frames.
        setSseConnected(true)

        launchLoadSessionStatus(scope, repository, slices) // default SWEEP
        advanceUntilIdle()

        // P0-1 carve-out regression guard: with SSE transport delivering
        // (isSseConnected=true) the digest relay owns steady-state status, so
        // the sweep MUST stay a zero-REST no-op (方案A point 1 / Group 1
        // contract — preserved verbatim).
        coVerify(exactly = 0) { repository.getSlimapiSessionsStatus(any()) }
        coVerify(exactly = 0) { repository.getSessionStatus() }
        coVerify(exactly = 0) { repository.getActiveSessionIds() }
    }

    @Test
    fun `slim SSE-off sweep result is folded into sessionStatuses (P0-1 functional)`() = runTest {
        val repository = slimRepository()
        seedSessions(session("s1", "/work-a"))
        setConnectionPhase(ConnectionPhase.SseDisabled)
        coEvery { repository.getSlimapiSessionsStatus("/work-a") } returns
            Result.success(mapOf("s1" to SessionStatus(type = "busy")))

        launchLoadSessionStatus(scope, repository, slices) // default SWEEP
        advanceUntilIdle()

        // P0-1 functional correctness: the REST fan-out is not merely invoked —
        // its snapshot is merged into sessionStatuses so status stays fresh
        // while SSE is down (the entire point of the REST fallback).
        val folded = slices.sessionList.value.sessionStatuses["s1"]
        assertNotNull("SSE-off sweep must fold the slim bulk snapshot", folded)
        assertEquals("busy", folded?.type)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Group 6 — P2-11 transport-grounded liveness regression suite
    //
    // The rev-ogpt P2-11 finding: the prior sseDigestRelayEffective predicate
    // inferred transport health from ConnectionPhase alone. Transient phases
    // (Reconnecting / ReconnectingAttempt) were treated as "SSE will deliver
    // soon" → sweep short-circuited → status froze while the transport was
    // actually down (isSseConnected=false). The fix grounds the predicate on
    // the transport-delivery axis (StoreState.isSseConnected) — the ONLY legal
    // short-circuit is when the transport itself reports delivery.
    //
    // This group locks the transport-grounded contract across the full phase ×
    // isSseConnected matrix. No phase alone may short-circuit the sweep; only
    // isSseConnected=true may.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `P2-11 - Reconnecting + isSseConnected=false → REST fallback, no short-circuit`() = runTest {
        val repository = slimRepository()
        seedSessions(session("s1", "/work-a"))
        setConnectionPhase(ConnectionPhase.Reconnecting)
        // Transport is down during the reconnect gap — isSseConnected=false.
        setSseConnected(false)

        launchLoadSessionStatus(scope, repository, slices) // default SWEEP
        advanceUntilIdle()

        // P2-11 core: the reconnect gap MUST NOT short-circuit. The sweep
        // falls through to the slim REST fan-out so status stays fresh.
        coVerify(exactly = 1) { repository.getSlimapiSessionsStatus("/work-a") }
        coVerify(exactly = 0) { repository.getSessionStatus() }
        coVerify(exactly = 0) { repository.getActiveSessionIds() }
    }

    @Test
    fun `P2-11 - ReconnectingAttempt + isSseConnected=false → REST fallback, no short-circuit`() = runTest {
        val repository = slimRepository()
        seedSessions(session("s1", "/work-a"))
        setConnectionPhase(ConnectionPhase.ReconnectingAttempt(attempt = 2, maxAttempts = 5))
        // Transport is down during the retry gap.
        setSseConnected(false)

        launchLoadSessionStatus(scope, repository, slices) // default SWEEP
        advanceUntilIdle()

        // P2-11 core: same contract as Reconnecting — the retry gap MUST NOT
        // short-circuit. REST carries status until the transport recovers.
        coVerify(exactly = 1) { repository.getSlimapiSessionsStatus("/work-a") }
        coVerify(exactly = 0) { repository.getSessionStatus() }
        coVerify(exactly = 0) { repository.getActiveSessionIds() }
    }

    @Test
    fun `P2-11 - Connected + isSseConnected=true → sweep no-op (transport delivering)`() = runTest {
        val repository = slimRepository()
        seedSessions(session("s1", "/work-a"))
        setConnectionPhase(ConnectionPhase.Connected)
        // Transport is actively delivering frames — the ONLY legal short-circuit.
        setSseConnected(true)

        launchLoadSessionStatus(scope, repository, slices) // default SWEEP
        advanceUntilIdle()

        // Transport delivering → digest relay is the steady-state source →
        // the sweep is a zero-REST no-op (方案A point 1).
        coVerify(exactly = 0) { repository.getSlimapiSessionsStatus(any()) }
        coVerify(exactly = 0) { repository.getSessionStatus() }
        coVerify(exactly = 0) { repository.getActiveSessionIds() }
    }

    @Test
    fun `P2-11 - Connected + isSseConnected=false → REST fallback (transport down despite healthy phase)`() = runTest {
        val repository = slimRepository()
        seedSessions(session("s1", "/work-a"))
        setConnectionPhase(ConnectionPhase.Connected)
        // Transport is down even though phase reads Connected (transient SSE
        // outage not yet a health failure — StoreState.kt:52-72 two-axis
        // semantics). The predicate MUST NOT trust phase alone.
        setSseConnected(false)

        launchLoadSessionStatus(scope, repository, slices) // default SWEEP
        advanceUntilIdle()

        // Phase=Connected alone is NOT enough — the transport-grounded
        // predicate falls through to REST so status cannot freeze.
        coVerify(exactly = 1) { repository.getSlimapiSessionsStatus("/work-a") }
        coVerify(exactly = 0) { repository.getSessionStatus() }
        coVerify(exactly = 0) { repository.getActiveSessionIds() }
    }

    @Test
    fun `P2-11 - SseDisabled + isSseConnected=false → REST fallback (terminal, debug toggle)`() = runTest {
        val repository = slimRepository()
        seedSessions(session("s1", "/work-a"))
        setConnectionPhase(ConnectionPhase.SseDisabled)
        // SseDisabled teardown stamps isSseConnected=false.
        setSseConnected(false)

        launchLoadSessionStatus(scope, repository, slices) // default SWEEP
        advanceUntilIdle()

        // Terminal SseDisabled → no digest relay → REST fallback.
        coVerify(exactly = 1) { repository.getSlimapiSessionsStatus("/work-a") }
        coVerify(exactly = 0) { repository.getSessionStatus() }
        coVerify(exactly = 0) { repository.getActiveSessionIds() }
    }

    @Test
    fun `P2-11 - Disconnected + isSseConnected=false → REST fallback (terminal, retries exhausted)`() = runTest {
        val repository = slimRepository()
        seedSessions(session("s1", "/work-a"))
        setConnectionPhase(ConnectionPhase.Disconnected)
        // Disconnected teardown stamps isSseConnected=false.
        setSseConnected(false)

        launchLoadSessionStatus(scope, repository, slices) // default SWEEP
        advanceUntilIdle()

        // Terminal Disconnected → no digest relay → REST fallback.
        coVerify(exactly = 1) { repository.getSlimapiSessionsStatus("/work-a") }
        coVerify(exactly = 0) { repository.getSessionStatus() }
        coVerify(exactly = 0) { repository.getActiveSessionIds() }
    }

    @Test
    fun `P2-11 - Connecting + isSseConnected=false → REST fallback (transport not yet up)`() = runTest {
        val repository = slimRepository()
        seedSessions(session("s1", "/work-a"))
        setConnectionPhase(ConnectionPhase.Connecting)
        // First probe in flight — transport has not delivered yet.
        setSseConnected(false)

        launchLoadSessionStatus(scope, repository, slices) // default SWEEP
        advanceUntilIdle()

        // Transport not yet up → no digest relay → REST fallback. The
        // transport-grounded predicate does not assume "Connecting will
        // succeed soon".
        coVerify(exactly = 1) { repository.getSlimapiSessionsStatus("/work-a") }
        coVerify(exactly = 0) { repository.getSessionStatus() }
        coVerify(exactly = 0) { repository.getActiveSessionIds() }
    }

    @Test
    fun `P2-11 - AwaitingTofuTrust + isSseConnected=false → REST fallback (retry loop suspended)`() = runTest {
        val repository = slimRepository()
        seedSessions(session("s1", "/work-a"))
        setConnectionPhase(ConnectionPhase.AwaitingTofuTrust)
        // TOFU dialog overlays — retry loop suspended, transport down.
        setSseConnected(false)

        launchLoadSessionStatus(scope, repository, slices) // default SWEEP
        advanceUntilIdle()

        // Transport down + retry loop suspended → no digest relay → REST
        // fallback. The predicate does not trust the transient phase.
        coVerify(exactly = 1) { repository.getSlimapiSessionsStatus("/work-a") }
        coVerify(exactly = 0) { repository.getSessionStatus() }
        coVerify(exactly = 0) { repository.getActiveSessionIds() }
    }

    @Test
    fun `P2-11 - isSseConnected=true + SseDisabled phase → REST fallback (defensive phase guard)`() = runTest {
        // Defensive edge: a transient isSseConnected=true read during SseDisabled
        // teardown. The explicit phase guard rejects the short-circuit even if
        // the transport axis momentarily reads true — terminal phases are
        // always OFF. (In practice SseDisabled teardown stamps isSseConnected=false,
        // so this case is defensive; the phase guard documents intent.)
        val repository = slimRepository()
        seedSessions(session("s1", "/work-a"))
        setConnectionPhase(ConnectionPhase.SseDisabled)
        // Force isSseConnected=true to exercise the defensive phase guard.
        setSseConnected(true)

        launchLoadSessionStatus(scope, repository, slices) // default SWEEP
        advanceUntilIdle()

        // Phase=SseDisabled is terminal → the phase guard rejects the short-
        // circuit even with isSseConnected=true. REST fallback.
        coVerify(exactly = 1) { repository.getSlimapiSessionsStatus("/work-a") }
        coVerify(exactly = 0) { repository.getSessionStatus() }
        coVerify(exactly = 0) { repository.getActiveSessionIds() }
    }

    @Test
    fun `P2-11 - isSseConnected=true + Disconnected phase → REST fallback (defensive phase guard)`() = runTest {
        // Mirror of the SseDisabled defensive case above — Disconnected is
        // terminal, so the phase guard rejects the short-circuit even if the
        // transport axis momentarily reads true during teardown.
        val repository = slimRepository()
        seedSessions(session("s1", "/work-a"))
        setConnectionPhase(ConnectionPhase.Disconnected)
        setSseConnected(true)

        launchLoadSessionStatus(scope, repository, slices) // default SWEEP
        advanceUntilIdle()

        // Phase=Disconnected is terminal → REST fallback.
        coVerify(exactly = 1) { repository.getSlimapiSessionsStatus("/work-a") }
        coVerify(exactly = 0) { repository.getSessionStatus() }
        coVerify(exactly = 0) { repository.getActiveSessionIds() }
    }

    @Test
    fun `P2-11 - Reconnecting + isSseConnected=true → sweep no-op (transport recovered mid-gap)`() = runTest {
        // Edge: transport recovered (isSseConnected=true) while phase still
        // reads Reconnecting (phase lags transport). The transport-grounded
        // predicate short-circuits — the digest relay is delivering again.
        val repository = slimRepository()
        seedSessions(session("s1", "/work-a"))
        setConnectionPhase(ConnectionPhase.Reconnecting)
        setSseConnected(true)

        launchLoadSessionStatus(scope, repository, slices) // default SWEEP
        advanceUntilIdle()

        // Transport delivering → digest relay active → sweep no-op. The
        // transport axis is authoritative, not the lagging phase.
        coVerify(exactly = 0) { repository.getSlimapiSessionsStatus(any()) }
        coVerify(exactly = 0) { repository.getSessionStatus() }
        coVerify(exactly = 0) { repository.getActiveSessionIds() }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Group 7 — fix-11a P0-1 PARTIAL-FAILURE PRESERVATION (rev-ogpt)
    //
    // The slim COLD-START bulk path fans out one getSlimapiSessionsStatus per
    // registered workdir. fix-10 closed the ALL-failed gap (preserve prior +
    // complete(false)). fix-11a closes the PARTIAL-failure gap: when some
    // directories succeed and some fail, the failed directories' sessions
    // MUST preserve their prior sessionStatuses — they MUST NOT be
    // authoritative-normalized to idle (that would mask the transport
    // failure as "all idle", violating OpenCodeRepository
    // .getSlimapiSessionsStatus failure semantics "keep prior snapshot"
    // at OpenCodeRepository.kt:1580-1582). Only sessions whose directory
    // fetch SUCCEEDED may be normalized.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `P0-1 partial success partial failure preserves failed-directory prior BUSY status`() = runTest {
        // rev-ogpt P0-1 core regression: two directories A (success) + B
        // (failure). Sessions of the failed directory B MUST retain their
        // prior BUSY status — they MUST NOT be downgraded to idle just
        // because another directory's fetch happened to succeed.
        val repository = slimRepository()
        seedSessions(
            session("s-a", "/work-a"),
            session("s-b", "/work-b"),
        )
        // Prior state: s-b is BUSY (upstream mid-task). s-a is unknown.
        // §P0-A: status is now the authority projection — seed via authority so
        // the failed-dir preservation (which reads authority.bySid) sees it.
        store.dispatch(seedStatusAuthorityEvent("s-b", SessionStatus(type = "busy")))
        // /work-a fetch succeeds; /work-b fetch FAILS (transport error).
        coEvery { repository.getSlimapiSessionsStatus("/work-a") } returns
            Result.success(mapOf("s-a" to SessionStatus(type = "idle")))
        coEvery { repository.getSlimapiSessionsStatus("/work-b") } returns
            Result.failure(java.io.IOException("transport error /work-b"))

        launchLoadSessionStatus(
            scope,
            repository,
            slices,
            trigger = SessionStatusLoadTrigger.COLD_START,
        )
        advanceUntilIdle()

        // P0-1 fix-11a core: s-b's prior BUSY status MUST be preserved — the
        // failed /work-b fetch MUST NOT downgrade it to idle.
        val sBStatus = slices.sessionList.value.sessionStatuses["s-b"]
        assertNotNull("failed-directory session status must not be dropped", sBStatus)
        assertEquals(
            "failed-directory prior BUSY must be preserved (not downgraded to idle)",
            "busy",
            sBStatus?.type,
        )
        // Sanity: the successful /work-a fetch DID land (s-a folded to idle).
        assertEquals(
            "successful-directory session is normalized from REST snapshot",
            "idle",
            slices.sessionList.value.sessionStatuses["s-a"]?.type,
        )
    }

    @Test
    fun `P0-1 partial success partial failure preserves failed-directory prior RETRY status`() = runTest {
        // Variant: prior status is RETRY (not busy). The preservation
        // contract is the same — any non-idle prior status MUST survive a
        // directory fetch failure (the prior snapshot is preserved verbatim,
        // not just for "busy").
        val repository = slimRepository()
        seedSessions(
            session("s-a", "/work-a"),
            session("s-b", "/work-b"),
        )
        // §P0-A: status is now the authority projection — seed via authority.
        store.dispatch(seedStatusAuthorityEvent("s-b", SessionStatus(type = "retry")))
        coEvery { repository.getSlimapiSessionsStatus("/work-a") } returns
            Result.success(mapOf("s-a" to SessionStatus(type = "idle")))
        coEvery { repository.getSlimapiSessionsStatus("/work-b") } returns
            Result.failure(java.io.IOException("transport error /work-b"))

        launchLoadSessionStatus(
            scope,
            repository,
            slices,
            trigger = SessionStatusLoadTrigger.COLD_START,
        )
        advanceUntilIdle()

        val sBStatus = slices.sessionList.value.sessionStatuses["s-b"]
        assertNotNull("failed-directory session status must not be dropped", sBStatus)
        assertEquals(
            "failed-directory prior RETRY must be preserved (not downgraded to idle)",
            "retry",
            sBStatus?.type,
        )
    }

    @Test
    fun `P0-1 all directories fail preserves prior snapshot and completes false`() = runTest {
        // fix-10 contract (must NOT regress under fix-11a): ALL directories
        // failing MUST preserve the prior snapshot and signal complete(false)
        // so the caller can retry / fall back.
        val repository = slimRepository()
        seedSessions(
            session("s-a", "/work-a"),
            session("s-b", "/work-b"),
        )
        store.mutateSessionList {
            it.copy(sessionStatuses = mapOf(
                "s-a" to SessionStatus(type = "busy"),
                "s-b" to SessionStatus(type = "retry"),
            ))
        }
        coEvery { repository.getSlimapiSessionsStatus(any()) } returns
            Result.failure(java.io.IOException("total transport outage"))

        var completion: Boolean? = null
        launchLoadSessionStatus(
            scope,
            repository,
            slices,
            trigger = SessionStatusLoadTrigger.COLD_START,
        ) { success -> completion = success }
        advanceUntilIdle()

        // complete(false): the caller can retry / fall back.
        assertEquals("all-failed MUST signal complete(false)", false, completion)
        // Prior snapshot preserved verbatim — neither downgraded nor dropped.
        val statuses = slices.sessionList.value.sessionStatuses
        assertEquals("prior s-a BUSY preserved", "busy", statuses["s-a"]?.type)
        assertEquals("prior s-b RETRY preserved", "retry", statuses["s-b"]?.type)
    }

    @Test
    fun `P0-1 all directories succeed normalizes idle for authoritative sessions absent from REST snapshot`() = runTest {
        // fix-11a non-regression: the all-success path MUST still
        // authoritative-normalize — sessions absent from the REST snapshot
        // (idle upstream) get explicit idle. This is the unchanged normal
        // path; fix-11a's partial-failure carve-out MUST NOT weaken it.
        val repository = slimRepository()
        seedSessions(
            session("s-a", "/work-a"),
            session("s-b", "/work-b"),
        )
        // REST snapshots: only s-a returned as busy; s-b absent upstream
        // (= idle per /session/status semantics — idle entries are omitted).
        coEvery { repository.getSlimapiSessionsStatus("/work-a") } returns
            Result.success(mapOf("s-a" to SessionStatus(type = "busy")))
        coEvery { repository.getSlimapiSessionsStatus("/work-b") } returns
            Result.success(emptyMap())

        launchLoadSessionStatus(
            scope,
            repository,
            slices,
            trigger = SessionStatusLoadTrigger.COLD_START,
        )
        advanceUntilIdle()

        val statuses = slices.sessionList.value.sessionStatuses
        // s-a: busy from REST snapshot.
        assertEquals(
            "successful REST entry folded verbatim",
            "busy",
            statuses["s-a"]?.type,
        )
        // s-b: absent from REST snapshot → authoritative-normalized to idle
        // (the unchanged normalize-to-idle behavior for SUCCESSFUL dirs).
        assertEquals(
            "absent REST entry normalized to idle on the all-success path",
            "idle",
            statuses["s-b"]?.type,
        )
    }

    @Test
    fun `P2-2 failed directory session with no prior entry is not fabricated as idle`() = runTest {
        // rev-ogpt P2-2 boundary: a failed directory's session that has
        // NO prior entry in current.sessionStatuses MUST NOT appear in
        // the restSnapshot. There is nothing to preserve (no prior
        // status), and fabricating an idle entry would mislabel an
        // unknown state as authoritative-idle. The failed-directory
        // carve-out preserves PRIOR entries verbatim; it does NOT
        // fabricate new ones.
        //
        // This complements the Group 7 P0-1 tests:
        //  - P0-1 asserts: prior BUSY/RETRY for a failed-directory
        //    session is preserved (not downgraded to idle).
        //  - P2-2 asserts (THIS test): a failed-directory session with
        //    NO prior entry is dropped (not fabricated as idle).
        //
        // Both behaviors are correct: "preserve prior" naturally implies
        // "nothing to preserve → nothing fabricated". P2-2 pins the
        // boundary so a future normalization change cannot regress to
        // "absent + failed directory → idle" (which would mask the
        // transport failure as a fabricated idle, the same shape of bug
        // as P0-1 but for the no-prior case).
        val repository = slimRepository()
        seedSessions(
            session("s-a", "/work-a"),
            session("s-b-1", "/work-b"),
            session("s-b-2", "/work-b"),
        )
        // Prior state: ONLY s-a has a status. NEITHER s-b-1 nor s-b-2
        // has a prior entry — the failed directory's sessions are
        // entirely unseen.
        store.mutateSessionList {
            it.copy(sessionStatuses = mapOf("s-a" to SessionStatus(type = "busy")))
        }
        // /work-a succeeds (s-a returned as idle upstream); /work-b
        // FAILS (transport error — s-b-1 / s-b-2 statuses are unknown).
        coEvery { repository.getSlimapiSessionsStatus("/work-a") } returns
            Result.success(mapOf("s-a" to SessionStatus(type = "idle")))
        coEvery { repository.getSlimapiSessionsStatus("/work-b") } returns
            Result.failure(java.io.IOException("transport error /work-b"))

        launchLoadSessionStatus(
            scope,
            repository,
            slices,
            trigger = SessionStatusLoadTrigger.COLD_START,
        )
        advanceUntilIdle()

        val statuses = slices.sessionList.value.sessionStatuses
        // P2-2 core: s-b-1 and s-b-2 had NO prior entry; the failed
        // /work-b fetch MUST NOT fabricate idle entries for them. They
        // remain absent from sessionStatuses (no entry to preserve, no
        // entry fabricated).
        assertNull(
            "failed-directory session with no prior entry MUST NOT be fabricated as idle " +
                "(s-b-1 should be absent from sessionStatuses)",
            statuses["s-b-1"],
        )
        assertNull(
            "failed-directory session with no prior entry MUST NOT be fabricated as idle " +
                "(s-b-2 should be absent from sessionStatuses)",
            statuses["s-b-2"],
        )
        // Sanity: the successful /work-a fetch DID land — s-a was
        // updated from prior BUSY → REST idle (proves the test exercised
        // the success path, not a global short-circuit).
        assertEquals(
            "successful-directory session is updated from REST snapshot (proves the success path ran)",
            "idle",
            statuses["s-a"]?.type,
        )
        // Sanity: no normalization touched the failed-directory sessions
        // — the restSnapshot for /work-b was entirely skipped (no entry
        // fabricated, no entry overwritten, no entry dropped).
        assertEquals(
            "only the successful-directory session has a status entry; " +
                "failed-directory sessions with no prior are NOT fabricated",
            setOf("s-a"),
            statuses.keys,
        )
    }
}
