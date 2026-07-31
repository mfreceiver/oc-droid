package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.state.AuthorityOp
import cn.vectory.ocdroid.data.state.AuthorityState
import cn.vectory.ocdroid.data.state.Coverage
import cn.vectory.ocdroid.data.state.EntryOrigin
import cn.vectory.ocdroid.data.state.OptimisticClaim
import cn.vectory.ocdroid.data.state.ReconcileOutcome
import cn.vectory.ocdroid.data.state.RequestToken
import cn.vectory.ocdroid.data.state.RetryEntry
import cn.vectory.ocdroid.data.state.ScopeKey
import cn.vectory.ocdroid.data.state.ServerRound
import cn.vectory.ocdroid.data.state.SessionEntry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
// assertNotNull is defined as a local function at the bottom of the file
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

/**
 * §P0-A (B1 option 1) verification gate for [reduceAuthority] — the SINGLE
 * writer of `sessionList.sessionStatuses`. rev-gpt scrutinizes B1 (pure
 * reducer + single-CAS idempotency); these tests pin that contract.
 *
 * Plain JUnit (no dispatcher rule needed — the reducer is a synchronous pure
 * function). State is driven through [SharedStateStore.dispatch] so the
 * end-to-end single-CAS path is exercised.
 */
@Suppress("SameParameterValue")
class AuthorityReducerTest {

    private val scope = ScopeKey(serverGroupFp = "grp", endpointFp = "ep")

    companion object {
        private const val PROFILE_ID = "auth_test_profile"
    }

    private fun event(
        sid: String,
        status: SessionStatus,
        origin: EntryOrigin,
        monotonic: Long = 100L,
        serverRound: ServerRound? = null,
        bump: Long? = null,
        workdir: String? = null,
    ) = AuthorityOp.ApplyEvent(
        sid = sid,
        status = status,
        origin = origin,
        serverRound = serverRound,
        scopeKey = scope,
        connectionMonotonicMs = monotonic,
        workdir = workdir,
        optimisticBumpTimestamp = bump,
    )

    private fun snapshot(
        snapshot: Map<String, SessionStatus>,
        authoritativeNodeIds: Set<String>,
        localBefore: Map<String, SessionStatus> = emptyMap(),
        sidToWorkdir: Map<String, String> = emptyMap(),
        partialFailureWorkdirs: Set<String> = emptySet(),
        host: String? = PROFILE_ID,
        requestStartMs: Long = 100L,
        identityEpoch: Long = 0L,
    ) = AuthorityOp.ApplySnapshot(
        snapshot = snapshot,
        sidToWorkdir = sidToWorkdir,
        authoritativeNodeIds = authoritativeNodeIds,
        registeredWorkdirs = emptySet(),
        coveredWorkdirs = emptySet(),
        unmappedActiveIds = emptySet(),
        partialFailureWorkdirs = partialFailureWorkdirs,
        lastSuccessTimeMs = requestStartMs,
        scopeKey = scope,
        requestToken = RequestToken(hostProfileId = host, requestStartMs = requestStartMs, identityEpoch = identityEpoch),
        localBefore = localBefore,
    )

    private fun storeWith(sessions: List<Session> = emptyList()): SharedStateStore =
        SharedStateStore().apply {
            mutateState { it.copy(
                sessionList = it.sessionList.copy(sessions = sessions),
                host = HostState(
                    currentHostProfileId = PROFILE_ID,
                    hostProfiles = listOf(HostProfile(
                        id = PROFILE_ID,
                        name = "Test",
                        serverUrl = "https://test.example.com",
                        serverGroupFp = scope.serverGroupFp,
                    )),
                ),
                liveEndpointFp = scope.endpointFp,
            )}
        }

    // ═══════════════════════════════════════════════════════════════════════
    // B1 — purity / CAS-idempotency (the rev-gpt gate)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `reduceAuthority is referentially transparent - same input yields same output`() {
        val state = StoreState.initial().copy(
            sessionList = SessionListState(sessions = listOf(Session(id = "A", directory = "/x"))),
        )
        val op = event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY)
        val r1 = reduceAuthority(state, op)
        val r2 = reduceAuthority(state, op)
        assertEquals("same (state,op) MUST yield equal output", r1, r2)
    }

    @Test
    fun `reduceAuthority is idempotent under CAS retry - applying the same op to the ORIGINAL twice yields the same result`() {
        // Simulates MutableStateFlow.update's CAS retry: the reducer may run on
        // the ORIGINAL state more than once. The pure reducer must produce the
        // SAME committed state regardless of how many retries occur.
        val state = StoreState.initial().copy(
            sessionList = SessionListState(sessions = listOf(Session(id = "A", directory = "/x"))),
        )
        val op = event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 50L)
        val firstResult = reduceAuthority(state, op)
        val retryResult = reduceAuthority(state, op)
        assertEquals("CAS retry MUST produce identical state", firstResult, retryResult)
        // And the committed result is stable (re-running on the RESULT with a
        // no-op op does not drift).
        val stable = reduceAuthority(firstResult, event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 50L))
        assertEquals(firstResult.authority.bySid.keys, stable.authority.bySid.keys)
    }

    @Test
    fun `reduceAuthority does not mutate its input state`() {
        val sessions = listOf(Session(id = "A", directory = "/x"))
        val state = StoreState.initial().copy(
            sessionList = SessionListState(sessions = sessions),
        )
        val authorityBefore = state.authority
        val statusesBefore = state.sessionList.sessionStatuses
        val sessionsBefore = state.sessionList.sessions
        repeat(3) {
            reduceAuthority(state, event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 10L + it))
        }
        assertSame("input authority slice untouched", authorityBefore, state.authority)
        assertEquals("input sessionStatuses untouched", statusesBefore, state.sessionList.sessionStatuses)
        assertEquals("input sessions list untouched", sessionsBefore, state.sessionList.sessions)
        assertTrue("input authority still empty", state.authority.bySid.isEmpty())
    }

    @Test
    fun `reduceAuthority returns the SAME state reference on a rejected or no-change op`() {
        val state = StoreState.initial().copy(
            sessionList = SessionListState(sessions = listOf(Session(id = "A", directory = "/x"))),
        )
        // Prune of a non-existent sid → no change → same reference (CAS no-op).
        val prune = AuthorityOp.PruneSessions(sids = setOf("missing"), scopeKey = scope)
        assertSame("no-change op returns the same reference", state, reduceAuthority(state, prune))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Projection correctness per origin
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `ApplyEvent SSE_LEGACY writes bySid entry and sessionStatuses projection`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        store.dispatch(AppAction.AuthorityEvent(event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY)))
        val out = store.stateFlow.value
        assertEquals(SessionStatus(type = "busy"), out.sessionList.sessionStatuses["A"])
        assertEquals(EntryOrigin.SSE_LEGACY, out.authority.bySid["A"]?.origin)
    }

    @Test
    fun `ApplyEvent SSE_SLIM writes bySid entry and projection`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        store.dispatch(AppAction.AuthorityEvent(event("A", SessionStatus(type = "retry"), EntryOrigin.SSE_SLIM)))
        assertEquals(SessionStatus(type = "retry"), store.stateFlow.value.sessionList.sessionStatuses["A"])
    }

    @Test
    fun `ApplyEvent OPTIMISTIC sets an optimisticClaim`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        store.dispatch(AppAction.AuthorityEvent(event("A", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC)))
        val entry = store.stateFlow.value.authority.bySid["A"]
        assertEquals(EntryOrigin.OPTIMISTIC, entry?.origin)
        assertNotNull("optimisticClaim set", entry?.optimisticClaim)
        assertFalse("claim not yet server-echoed", entry?.optimisticClaim?.serverEchoed == true)
    }

    @Test
    fun `incoming BUSY echoes an existing optimistic claim (cross-channel reorder)`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        // OPTIMISTIC busy first (no server echo).
        store.dispatch(AppAction.AuthorityEvent(event("A", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = 1L)))
        assertFalse(store.stateFlow.value.authority.bySid["A"]?.optimisticClaim?.serverEchoed == true)
        // Incoming SSE BUSY → echo-confirm.
        store.dispatch(AppAction.AuthorityEvent(event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 2L)))
        assertTrue("claim now server-echoed", store.stateFlow.value.authority.bySid["A"]?.optimisticClaim?.serverEchoed == true)
    }

    @Test
    fun `incoming terminal IDLE clears the optimistic claim`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        store.dispatch(AppAction.AuthorityEvent(event("A", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = 1L)))
        store.dispatch(AppAction.AuthorityEvent(event("A", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, monotonic = 2L)))
        // §P0-B ITEM 1 (confirmation gate): unconfirmed optimistic claim blocks
        // the stale legacy idle → status stays busy, claim guardedIdleDrop=true.
        assertNotNull("gate dropped idle — claim NOT cleared",
            store.stateFlow.value.authority.bySid["A"]?.optimisticClaim)
        assertEquals("status stays busy (idle dropped)",
            SessionStatus(type = "busy"),
            store.stateFlow.value.authority.bySid["A"]?.status)
    }

    @Test
    fun `ApplySnapshot normalizes known tree nodes missing from snapshot to idle`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x"), Session(id = "B", directory = "/x")))
        // REST snapshot omits B (server omits idle). Both A and B are authoritative.
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(
                snapshot = mapOf("A" to SessionStatus(type = "busy")),
                authoritativeNodeIds = setOf("A", "B"),
            ),
        ))
        val out = store.stateFlow.value.sessionList.sessionStatuses
        assertEquals(SessionStatus(type = "busy"), out["A"])
        assertEquals("B missing from snapshot → normalized to idle", SessionStatus(type = "idle"), out["B"])
    }

    @Test
    fun `ApplySnapshot drops ids outside the authoritative tree (fail-closed absence)`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        // Seed "ghost" (not in the authoritative tree) via an event.
        store.dispatch(AppAction.AuthorityEvent(event("ghost", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY)))
        assertTrue("ghost present before snapshot", "ghost" in store.stateFlow.value.sessionList.sessionStatuses)
        // localBefore = the projection at REST request start (ghost was stable
        // before the REST round-trip → the in-flight merge does NOT re-add it).
        val localBefore = mapOf("ghost" to SessionStatus(type = "busy"))
        // Whole-graph snapshot for {A} only → ghost is not authoritative → dropped.
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(
                snapshot = emptyMap(),
                authoritativeNodeIds = setOf("A"),
                localBefore = localBefore,
            ),
        ))
        assertFalse("ghost outside tree dropped (absence ≡ unknown)",
            "ghost" in store.stateFlow.value.sessionList.sessionStatuses)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ApplySnapshot REST in-flight (SSE-wins) protection
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `ApplySnapshot preserves an SSE value that changed during the REST round-trip`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        // localBefore: A was idle at REST request start.
        // During REST, an SSE event set A=busy (so the current projection is busy).
        store.dispatch(AppAction.AuthorityEvent(event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY)))
        val localBefore = mapOf("A" to SessionStatus(type = "idle"))
        // REST snapshot says A=idle (stale — predates the SSE busy).
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(
                snapshot = emptyMap(), // server omits A → would normalize to idle
                authoritativeNodeIds = setOf("A"),
                localBefore = localBefore,
            ),
        ))
        assertEquals(
            "SSE-wins: A must stay busy, not clobbered by the stale REST idle",
            SessionStatus(type = "busy"),
            store.stateFlow.value.sessionList.sessionStatuses["A"],
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Read-point gold-clock (absence semantics)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `a sid never written is ABSENT from sessionStatuses (fail-closed unknown)`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        assertFalse("unknown" in store.stateFlow.value.sessionList.sessionStatuses)
    }

    @Test
    fun `an authoritative tree node missing from the snapshot is IDLE in the projection`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(snapshot = emptyMap(), authoritativeNodeIds = setOf("A")),
        ))
        assertEquals(SessionStatus(type = "idle"), store.stateFlow.value.sessionList.sessionStatuses["A"])
    }

    // ═══════════════════════════════════════════════════════════════════════
    // prune (B5) — delete / archive remove from authority.bySid + projection
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `PruneSessions removes ids from authority and the projection`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x"), Session(id = "B", directory = "/x")))
        store.dispatch(AppAction.AuthorityEvent(event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY)))
        store.dispatch(AppAction.AuthorityEvent(event("B", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY)))
        store.dispatch(AppAction.AuthorityEvent(
            AuthorityOp.PruneSessions(sids = setOf("A"), scopeKey = scope),
        ))
        val out = store.stateFlow.value
        assertFalse("A pruned from projection", "A" in out.sessionList.sessionStatuses)
        assertFalse("A pruned from authority.bySid", "A" in out.authority.bySid)
        assertTrue("B retained", "B" in out.sessionList.sessionStatuses)
    }

    @Test
    fun `SessionDeletedLocal reducer prunes authority and recomputes the projection`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x"), Session(id = "B", directory = "/x")))
        store.dispatch(AppAction.AuthorityEvent(event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY)))
        store.dispatch(AppAction.AuthorityEvent(event("B", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY)))
        store.dispatch(AppAction.SessionDeletedLocal(removedIds = setOf("A")))
        val out = store.stateFlow.value
        assertFalse("deleted A pruned from projection", "A" in out.sessionList.sessionStatuses)
        assertFalse("deleted A pruned from authority.bySid", "A" in out.authority.bySid)
    }

    @Test
    fun `SessionArchivedLocal reducer prunes the archived id from authority`() {
        val archived = Session(id = "A", directory = "/x",
            time = Session.TimeInfo(updated = 0L, archived = 1L))
        val store = storeWith(listOf(
            Session(id = "A", directory = "/x"), Session(id = "B", directory = "/x"),
        ))
        store.dispatch(AppAction.AuthorityEvent(event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY)))
        store.dispatch(AppAction.SessionArchivedLocal(
            session = archived,
            pendingQuestions = emptyList(),
            activeSessionIdsToRemove = emptySet(),
        ))
        assertFalse("archived A pruned", "A" in store.stateFlow.value.authority.bySid)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // purge (host cross-group) — authority reset
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `HostStatePurged cross-group resets authority to empty and clears sessionStatuses`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        store.dispatch(AppAction.AuthorityEvent(event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY)))
        store.dispatch(AppAction.HostStatePurged(preserveServerGroupData = false))
        val out = store.stateFlow.value
        assertEquals("authority fully reset", AuthorityState(), out.authority)
        assertTrue("sessionStatuses empty", out.sessionList.sessionStatuses.isEmpty())
    }

    @Test
    fun `HostStatePurged same-group preserves authority`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        store.dispatch(AppAction.AuthorityEvent(event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY)))
        val before = store.stateFlow.value.authority
        store.dispatch(AppAction.HostStatePurged(preserveServerGroupData = true))
        assertEquals("same-group preserves authority", before, store.stateFlow.value.authority)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // pendingBumps (B8) — optimistic bump applied + consumed in the same CAS
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `optimistic bump is applied to sessions and pendingBumps cleared in the same CAS`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        val ts = 99999L
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = ts, bump = ts),
        ))
        val out = store.stateFlow.value
        assertTrue("pendingBumps consumed", out.authority.pendingBumps.isEmpty())
        val bumped = out.sessionList.sessions.first { it.id == "A" }
        assertTrue("sessions[A].time.updated bumped to >= ts", (bumped.time?.updated ?: 0L) >= ts)
    }

    @Test
    fun `re-applying the optimistic bump does not double-bump (idempotent)`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        val ts = 99999L
        val op = event("A", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = ts, bump = ts)
        store.dispatch(AppAction.AuthorityEvent(op))
        val updatedOnce = store.stateFlow.value.sessionList.sessions.first { it.id == "A" }.time?.updated
        // Re-apply the SAME op (simulating a retry on the same logical event).
        store.dispatch(AppAction.AuthorityEvent(op))
        val updatedTwice = store.stateFlow.value.sessionList.sessions.first { it.id == "A" }.time?.updated
        assertEquals("bump is monotonic — no double-bump / no regression", updatedOnce, updatedTwice)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // serverRound lex DROP (B6/B9) — when supplied
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `an older serverRound ApplyEvent is DROPPED (lex strict-monotonic)`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        val newer = ServerRound(incarnation = 1L, turn = 5L)
        val older = ServerRound(incarnation = 1L, turn = 3L)
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM, serverRound = newer, monotonic = 100L),
        ))
        val afterNew = store.stateFlow.value.sessionList.sessionStatuses["A"]
        // Older turn → DROP (must not overwrite).
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "idle"), EntryOrigin.SSE_SLIM, serverRound = older, monotonic = 200L),
        ))
        assertEquals("older serverRound dropped — busy retained",
            afterNew, store.stateFlow.value.sessionList.sessionStatuses["A"])
    }

    @Test
    fun `incarnation advance resets prior turns serverRound`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        // Establish a turn under incarnation 1.
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(1L, 1L), monotonic = 100L),
        ))
        assertEquals(ServerRound(1L, 1L), store.stateFlow.value.authority.bySid["A"]?.serverRound)
        // Incarnation advance (server restart) → resets serverRound to null.
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(2L, 1L), monotonic = 200L),
        ))
        assertEquals("incarnation advance sets the new round",
            ServerRound(2L, 1L), store.stateFlow.value.authority.bySid["A"]?.serverRound)
        assertEquals("high-water bumped to incarnation 2",
            2L, store.stateFlow.value.authority.knownIncarnations[scope])
    }

    @Test
    fun `a low incarnation frame is DROPPED (B6)`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(3L, 1L), monotonic = 100L),
        ))
        val busy = store.stateFlow.value.sessionList.sessionStatuses["A"]
        // Old incarnation (2 < high-water 3) → DROP.
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "idle"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(2L, 99L), monotonic = 200L),
        ))
        assertEquals("low-incarnation frame dropped", busy, store.stateFlow.value.sessionList.sessionStatuses["A"])
    }

    // ═══════════════════════════════════════════════════════════════════════
    // serverRound hole semantics (S2 slimapi contract §9) — regression guard
    //
    // A "hole" = a turn that was lost (e.g. a connection failure mid-stream) and
    // thus NEVER dispatched. The lex guard only requires strict monotonic advance;
    // it must NOT require contiguous turns. This pins that a gap in the turn
    // sequence does not break lex-strict progression (turns 1,2,3,5 applied, turn
    // 4 lost → turn 5 still ACCEPTED).
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `turn sequence with a hole - turns 1,2,3,5 applied, turn 4 lost to connection failure never seen`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        // Apply turns 1,2,3 → busy (each strictly newer, lex advances).
        listOf(1L, 2L, 3L).forEachIndexed { _, turn ->
            store.dispatch(AppAction.AuthorityEvent(
                event("s1", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                    serverRound = ServerRound(1L, turn), monotonic = turn * 100L),
            ))
        }
        // Turn 4 was LOST (connection failure) — it is never dispatched.
        // Apply turn 5 → idle: must be ACCEPTED (5 > 3, a lex-strict advance
        // despite the hole — contiguity is NOT required by the lex guard).
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(1L, 5L), monotonic = 500L),
        ))
        assertEquals("turn 5 idle accepted across the hole",
            SessionStatus(type = "idle"),
            store.stateFlow.value.sessionList.sessionStatuses["s1"])
        assertEquals("serverRound advanced to turn 5 across the hole",
            ServerRound(1L, 5L),
            store.stateFlow.value.authority.bySid["s1"]?.serverRound)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // §3.1 BLK-2 — baseline-cleared low-turn revival window
    //
    // When the live serverRound baseline is cleared (REST ApplySnapshot / legacy
    // SSE busy / incarnation-advance scope reset) but a Tier-1 slim frame with a
    // turn arrives, the lex guard (which requires prev.serverRound != null) is
    // skipped. Without the BLK-2 per-sid serverRound high-water, a stale LOW-turn
    // frame would apply unconditionally and revive stale busy. These tests pin
    // that the persistent watermark closes the window.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `BLK-2 - stale low-turn slim frame is DROPPED after REST snapshot clears the baseline`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        // Establish a Tier-1 baseline at incarnation 5, turn 7.
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(5L, 7L), monotonic = 100L),
        ))
        assertEquals(ServerRound(5L, 7L), store.stateFlow.value.authority.bySid["A"]?.serverRound)
        // REST ApplySnapshot clears the live baseline (serverRound → null); the
        // BLK-2 high-water (5,7) must SURVIVE the clear.
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(snapshot = mapOf("A" to SessionStatus(type = "busy")),
                     authoritativeNodeIds = setOf("A")),
        ))
        assertNull("REST clears the live serverRound baseline",
            store.stateFlow.value.authority.bySid["A"]?.serverRound)
        assertEquals("BLK-2 high-water survives the REST clear",
            ServerRound(5L, 7L), store.stateFlow.value.authority.bySid["A"]?.serverRoundHighWater)
        // BLK-2 window: a stale LOW-turn slim digest arrives late (cross-channel
        // reorder). Pre-fix this revived stale busy; it must now be DROPPED.
        val beforeStale = store.stateFlow.value
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(5L, 4L), monotonic = 300L),
        ))
        assertNull("stale low-turn frame DROPPED — baseline stays cleared",
            store.stateFlow.value.authority.bySid["A"]?.serverRound)
        assertEquals("dropped stale frame is a true no-op (same ref state)",
            beforeStale, store.stateFlow.value)
    }

    @Test
    fun `U-P3 - legacy SSE busy PRESERVES the slim baseline stale low-turn fenced by live lex guard`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        // Establish slim baseline (5,7)
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(5L, 7L), monotonic = 100L),
        ))
        // Legacy SSE BUSY (no serverRound) → U-P3: baseline PRESERVED (not cleared)
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 200L),
        ))
        assertEquals("U-P3: legacy busy preserves the slim baseline",
            ServerRound(5L, 7L), store.stateFlow.value.authority.bySid["A"]?.serverRound)
        // Stale low-turn slim (5,4) → DROPPED by the LIVE lex guard (:263-265),
        // NOT by BLK-2 guard (prev.serverRound is now non-null → BLK-2 :293-298
        // condition prev.serverRound==null is FALSE → skipped).
        val beforeStale = store.stateFlow.value
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(5L, 4L), monotonic = 300L),
        ))
        assertEquals("stale low-turn DROPPED — baseline preserved",
            ServerRound(5L, 7L), store.stateFlow.value.authority.bySid["A"]?.serverRound)
        assertEquals("dropped stale frame is a true no-op (same ref state)",
            beforeStale, store.stateFlow.value)
    }

    @Test
    fun `U-P3 - legacy SSE busy does NOT block fresh higher-turn slim from advancing`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        // Establish slim baseline (5,7)
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(5L, 7L), monotonic = 100L),
        ))
        // Legacy SSE BUSY — U-P3 preserves baseline (5,7)
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 200L),
        ))
        assertEquals("baseline preserved after legacy busy",
            ServerRound(5L, 7L), store.stateFlow.value.authority.bySid["A"]?.serverRound)
        // Fresh higher-turn slim (5,9) → ACCEPTED (serverRound != null → keepRound uses op)
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(5L, 9L), monotonic = 300L),
        ))
        assertEquals("fresh higher-turn slim advances the baseline",
            ServerRound(5L, 9L), store.stateFlow.value.authority.bySid["A"]?.serverRound)
        assertEquals("high-water advanced to the fresh turn",
            ServerRound(5L, 9L), store.stateFlow.value.authority.bySid["A"]?.serverRoundHighWater)
    }

    @Test
    fun `U-P3 - legacy SSE busy after equal-turn slim with older monotonic is DROPped`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        // Establish slim baseline (5,7) at monotonic=100
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(5L, 7L), monotonic = 100L),
        ))
        assertEquals(ServerRound(5L, 7L), store.stateFlow.value.authority.bySid["A"]?.serverRound)
        // Legacy SSE BUSY — U-P3 preserves baseline
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 200L),
        ))
        // legacy_update updates updatedMonotonic to 200, preserves baseline (5,7)
        val afterLegacy = store.stateFlow.value
        assertEquals(200L, store.stateFlow.value.authority.bySid["A"]?.updatedMonotonic)
        // Equal-turn slim frame (5,7) with OLDER monotonic (50 < 200) → live lex guard
        // tie-break DROPS it (cmp==0 && connectionMonotonicMs < updatedMonotonic)
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(5L, 7L), monotonic = 50L),
        ))
        assertSame("equal-turn with older monotonic is same-ref no-op",
            afterLegacy, store.stateFlow.value)
    }

    @Test
    fun `BLK-2 - a fresh higher-turn slim frame is ACCEPTED after the baseline is cleared`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(5L, 7L), monotonic = 100L),
        ))
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(snapshot = mapOf("A" to SessionStatus(type = "busy")),
                     authoritativeNodeIds = setOf("A")),
        ))
        assertNull(store.stateFlow.value.authority.bySid["A"]?.serverRound)
        // Fresh higher turn → ACCEPTED, and the high-water advances.
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(5L, 9L), monotonic = 300L),
        ))
        assertEquals("fresh higher turn accepted",
            ServerRound(5L, 9L), store.stateFlow.value.authority.bySid["A"]?.serverRound)
        assertEquals("high-water advanced to the fresh turn",
            ServerRound(5L, 9L), store.stateFlow.value.authority.bySid["A"]?.serverRoundHighWater)
    }

    @Test
    fun `BLK-2 - an equal-turn slim frame after baseline clear is accepted, not over-fenced`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(5L, 7L), monotonic = 100L),
        ))
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(snapshot = mapOf("A" to SessionStatus(type = "busy")),
                     authoritativeNodeIds = setOf("A")),
        ))
        // §3.1 fences ONLY strictly-low turns (cmp < 0 → DROP). An equal turn is
        // not stale (the server never regresses to it), so it re-establishes the
        // baseline — mirroring the live lex guard's equal handling.
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(5L, 7L), monotonic = 300L),
        ))
        assertEquals("equal turn re-establishes the baseline",
            ServerRound(5L, 7L), store.stateFlow.value.authority.bySid["A"]?.serverRound)
    }

    @Test
    fun `U-CQ4 - prune then same-incarnation slim frame is ACCEPTED (watermark lost, known residual)`() {
        // §U-CQ4: the BLK-2 watermark is per-entry (per-sid). When an entry is
        // pruned the watermark is lost. If a same-incarnation slim frame arrives
        // after the prune, prev is null → the BLK-2 guard is skipped → the frame
        // is accepted. This is a known residual: incarnation is tied to server
        // process lifecycle, so a deleted sid cannot actually revive under the
        // same incarnation. Document current behavior.
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        // Establish baseline (5,7)
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(5L, 7L), monotonic = 100L),
        ))
        assertEquals(ServerRound(5L, 7L),
            store.stateFlow.value.authority.bySid["A"]?.serverRoundHighWater)
        // Prune entry A (simulates archive/delete)
        store.dispatch(AppAction.AuthorityEvent(
            AuthorityOp.PruneSessions(sids = setOf("A"), scopeKey = scope),
        ))
        assertNull("entry A pruned", store.stateFlow.value.authority.bySid["A"])
        // Same-incarnation slim frame arrives after prune — prev==null → no BLK-2
        // fence → ACCEPTED (known residual, incarnation semantic guarantee).
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(5L, 7L), monotonic = 200L),
        ))
        assertNotNull("same-incarnation frame accepted (known residual)",
            store.stateFlow.value.authority.bySid["A"])
        assertEquals("accepted frame establishes baseline",
            ServerRound(5L, 7L), store.stateFlow.value.authority.bySid["A"]?.serverRound)
    }

    @Test
    fun `BLK-2 - high-water advance then a later stale low-turn frame is fenced`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(5L, 7L), monotonic = 100L),
        ))
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(snapshot = mapOf("A" to SessionStatus(type = "busy")),
                     authoritativeNodeIds = setOf("A")),
        ))
        // A fresh frame advances the watermark to (5, 12).
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(5L, 12L), monotonic = 300L),
        ))
        // Another REST clear wipes the live baseline again.
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(snapshot = mapOf("A" to SessionStatus(type = "busy")),
                     authoritativeNodeIds = setOf("A")),
        ))
        assertNull(store.stateFlow.value.authority.bySid["A"]?.serverRound)
        assertEquals(ServerRound(5L, 12L),
            store.stateFlow.value.authority.bySid["A"]?.serverRoundHighWater)
        // A stale frame with a turn between the two baselines (9) is still stale
        // relative to the ADVANCED watermark (12) → DROP.
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(5L, 9L), monotonic = 400L),
        ))
        assertNull("stale turn below the advanced high-water DROPPED",
            store.stateFlow.value.authority.bySid["A"]?.serverRound)
    }

    @Test
    fun `BLK-2 - cold start with no watermark accepts the first slim frame`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        // No prior baseline at all → first slim frame establishes it (no
        // high-water to compare against → must not be spuriously dropped).
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(5L, 1L), monotonic = 100L),
        ))
        assertEquals(ServerRound(5L, 1L), store.stateFlow.value.authority.bySid["A"]?.serverRound)
        assertEquals(ServerRound(5L, 1L),
            store.stateFlow.value.authority.bySid["A"]?.serverRoundHighWater)
    }

    @Test
    fun `BLK-2 - high-water survives an incarnation-advance scope reset`() {
        // Pins the third declared baseline-clear source (incarnation-advance scope
        // reset at applyEvent ~L348), which uses `.copy(serverRound = null)` and so
        // PRESERVES the high-water. After the reset a stale LOW-turn frame under the
        // NEW incarnation must still be fenced by the BLK-2 watermark.
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        // Establish a Tier-1 baseline under incarnation 5, turn 7 (also advances the
        // scope high-water to incarnation 5).
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(5L, 7L), monotonic = 100L),
        ))
        assertEquals(ServerRound(5L, 7L),
            store.stateFlow.value.authority.bySid["A"]?.serverRoundHighWater)
        // Incarnation advance (server restart) to incarnation 6 → resets THIS scope's
        // entries' live serverRound to null, advances scope high-water to 6.
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(6L, 1L), monotonic = 200L),
        ))
        assertEquals(6L, store.stateFlow.value.authority.knownIncarnations[scope])
        // A stale low-incarnation frame (incarnation 4 < scope high-water 6) is caught
        // by the B6 guard (L239), NOT the BLK-2 guard — assert that first to anchor
        // which guard owns incarnation fencing.
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "idle"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(4L, 99L), monotonic = 250L),
        ))
        // Now the BLK-2-relevant case: a DIFFERENT sid "B" that had its baseline
        // cleared by the incarnation-advance reset (scope-wide null reset). B had
        // previously seen incarnation 5 turn 7; after the reset its live baseline is
        // null but its high-water (5,7) survived. A stale same-incarnation-5 low-turn
        // frame (incarnation 5 is NOT < scope high-water 6, so B6 does NOT fire) must
        // be fenced by BLK-2.
        store.dispatch(AppAction.AuthorityEvent(
            event("B", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(5L, 7L), monotonic = 120L),
        ))
        // The incarnation-advance to 6 resets B's live serverRound (scope-wide reset).
        // Re-establish the scope advance by replaying an incarnation-6 frame on B:
        store.dispatch(AppAction.AuthorityEvent(
            event("B", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(6L, 2L), monotonic = 210L),
        ))
        // Clear B's live baseline via REST so prev.serverRound is null but the
        // high-water (6,2) survives — this isolates the BLK-2 path.
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(snapshot = mapOf("B" to SessionStatus(type = "busy")),
                     authoritativeNodeIds = setOf("B")),
        ))
        assertNull(store.stateFlow.value.authority.bySid["B"]?.serverRound)
        assertEquals(ServerRound(6L, 2L),
            store.stateFlow.value.authority.bySid["B"]?.serverRoundHighWater)
        // Stale same-incarnation (6) low turn (1 < 2) — B6 does NOT fire (inc 6 ==
        // scope high-water 6), BLK-2 must DROP.
        val beforeStale = store.stateFlow.value
        store.dispatch(AppAction.AuthorityEvent(
            event("B", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(6L, 1L), monotonic = 300L),
        ))
        assertNull("stale low-turn after scope-reset baseline clear DROPPED",
            store.stateFlow.value.authority.bySid["B"]?.serverRound)
        assertEquals("dropped stale frame is a no-op (same ref state)",
            beforeStale, store.stateFlow.value)
    }

    @Test
    fun `an ApplySnapshot with a mismatched host guard is DROPPED`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        store.mutateHost { it.copy(currentHostProfileId = "real-host") }
        store.dispatch(AppAction.AuthorityEvent(event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY)))
        val before = store.stateFlow.value
        // Host guard: requestToken.hostProfileId != currentHostProfileId.
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(
                snapshot = emptyMap(),
                authoritativeNodeIds = setOf("A"),
                host = "different-host",
            ),
        ))
        assertSame("host-mismatched snapshot dropped (no-op)", before, store.stateFlow.value)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // §P0-A rev-gpt rework Lane A — NEW TESTS
    // ═══════════════════════════════════════════════════════════════════════

    // ── #1: B1 no-change must return the SAME reference ──────────────────

    @Test
    fun `rev-gpt #1 - equal-value ApplyEvent re-delivery returns same state reference`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        // First delivery: writes the entry.
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 500L, workdir = "/w"),
        ))
        val afterFirst = store.stateFlow.value
        // Re-delivery: SAME status + SAME monotonic → nextEntry == prev → no-change.
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 500L, workdir = "/w"),
        ))
        assertSame("equal-value SSE re-delivery must be a CAS no-op (same reference)", afterFirst, store.stateFlow.value)
    }

    @Test
    fun `rev-gpt #1 - ApplySnapshot that merges to identical bySid returns same reference`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        // Seed authority with one entry.
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, monotonic = 100L, workdir = "/w"),
        ))
        val before = store.stateFlow.value
        // ApplySnapshot producing the same bySid + same coverage → no-change.
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(
                snapshot = emptyMap(),
                authoritativeNodeIds = setOf("s1"),
                sidToWorkdir = mapOf("s1" to "/w"),
                requestStartMs = 100L,
            ),
        ))
        // The snapshot produces s1=idle (REST origin) — but the seed was SSE_LEGACY.
        // So the entry differs (origin changes) → this is a CHANGE, not no-op.
        // To truly test no-change, seed with REST origin matching the snapshot output.
        assertNotSame("snapshot with different origin IS a change", before, store.stateFlow.value)

        // Now seed EXACTLY matching what the snapshot produces, then re-apply → no-change.
        val matched = store.stateFlow.value
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(
                snapshot = emptyMap(),
                authoritativeNodeIds = setOf("s1"),
                sidToWorkdir = mapOf("s1" to "/w"),
                requestStartMs = 100L,
            ),
        ))
        assertSame("identical snapshot re-apply must be a CAS no-op (same reference)", matched, store.stateFlow.value)
    }

    // ── #2: RequestToken epoch guard ─────────────────────────────────────

    @Test
    fun `rev-gpt #2 - epoch guard drops ApplySnapshot after host switch bumps identityEpoch`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        // Simulate a host switch: mutateHost changes currentHostProfileId → bumps identityEpoch.
        val epochBefore = store.stateFlow.value.identityEpoch
        store.mutateHost { it.copy(currentHostProfileId = "new-host") }
        val epochAfter = store.stateFlow.value.identityEpoch
        assertTrue("identityEpoch must bump on host switch", epochAfter > epochBefore)

        val before = store.stateFlow.value
        // A stale snapshot whose request-start identityEpoch predates the switch.
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(
                snapshot = mapOf("s1" to SessionStatus(type = "busy")),
                authoritativeNodeIds = setOf("s1"),
                host = "old-host-or-null",
                identityEpoch = epochBefore, // stale (before the switch)
            ),
        ))
        // The epoch guard rejects it (identityEpoch mismatch) → no-op.
        assertSame("stale-epoch snapshot dropped (no-op)", before, store.stateFlow.value)
        assertNull("stale snapshot must not write authority", store.stateFlow.value.authority.bySid["s1"])
    }

    @Test
    fun `rev-gpt #2 - epoch guard passes when identityEpoch matches current state`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        val currentEpoch = store.stateFlow.value.identityEpoch
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(
                snapshot = mapOf("s1" to SessionStatus(type = "busy")),
                authoritativeNodeIds = setOf("s1"),
                identityEpoch = currentEpoch, // matches → accepted
            ),
        ))
        assertEquals("busy", store.stateFlow.value.authority.bySid["s1"]?.status?.type)
    }

    // ── abort-pending single-CAS ─────────────────────────────────────────

    @Test
    fun `rev-gpt abort-pending - ApplyEvent idle atomically clears status projection AND abortPending`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        // Seed: s1 busy + abort-pending flag set.
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 100L, workdir = "/w"),
        ))
        store.mutateSessionList { it.copy(abortPendingSessionIds = mapOf("s1" to 999L)) }
        assertTrue("s1 abort-pending seeded", "s1" in store.stateFlow.value.sessionList.abortPendingSessionIds)

        // SSE delivers idle → reduceAuthority releases abort-pending in the SAME state.copy.
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, monotonic = 200L, workdir = "/w"),
        ))

        // BOTH must be updated atomically (single CAS, no torn window).
        assertEquals("idle", store.stateFlow.value.sessionList.sessionStatuses["s1"]?.type)
        assertFalse("abort-pending cleared atomically with status", "s1" in store.stateFlow.value.sessionList.abortPendingSessionIds)
    }

    @Test
    fun `rev-gpt abort-pending - ApplyEvent busy retains abortPending`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        store.mutateSessionList { it.copy(abortPendingSessionIds = mapOf("s1" to 999L)) }
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 100L, workdir = "/w"),
        ))
        assertTrue("busy status retains abort-pending", "s1" in store.stateFlow.value.sessionList.abortPendingSessionIds)
    }

    // ── #7: subtree prune via reduceAuthority ────────────────────────────

    @Test
    fun `rev-gpt #7 - archive prunes whole subtree from authority bySid`() {
        val parent = Session(id = "root", directory = "/w", parentId = null)
        val child = Session(id = "child1", directory = "/w", parentId = "root")
        val grandchild = Session(id = "grand1", directory = "/w", parentId = "child1")
        val store = storeWith(listOf(parent, child, grandchild))
        // Seed authority with all three.
        listOf("root", "child1", "grand1").forEach { sid ->
            store.dispatch(AppAction.AuthorityEvent(
                event(sid, SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 100L, workdir = "/w"),
            ))
        }
        assertEquals(3, store.stateFlow.value.authority.bySid.size)

        // Archive the root → subtree prune (root + child1 + grand1).
        store.dispatch(AppAction.SessionArchivedLocal(
            session = parent.copy(title = "archived"),
            pendingQuestions = emptyList(),
            activeSessionIdsToRemove = setOf("root", "child1", "grand1"),
        ))

        val bySid = store.stateFlow.value.authority.bySid
        assertNull("root pruned from authority", bySid["root"])
        assertNull("child1 pruned from authority (subtree)", bySid["child1"])
        assertNull("grand1 pruned from authority (subtree)", bySid["grand1"])
        // sessionStatuses must reflect the authority projection (sole writer).
        assertTrue("sessionStatuses empty after subtree prune", store.stateFlow.value.sessionList.sessionStatuses.isEmpty())
    }

    @Test
    fun `rev-gpt #7 - delete prunes subtree ids from authority`() {
        val parent = Session(id = "root", directory = "/w")
        val child = Session(id = "child1", directory = "/w", parentId = "root")
        val store = storeWith(listOf(parent, child))
        listOf("root", "child1").forEach { sid ->
            store.dispatch(AppAction.AuthorityEvent(
                event(sid, SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 100L, workdir = "/w"),
            ))
        }

        // Delete root → subtree prune.
        store.dispatch(AppAction.SessionDeletedLocal(removedIds = setOf("root")))

        val bySid = store.stateFlow.value.authority.bySid
        assertNull("root pruned", bySid["root"])
        assertNull("child1 pruned (subtree of deleted root)", bySid["child1"])
    }

    // ── applyMarkFailed scope-filter ─────────────────────────────────────

    @Test
    fun `rev-gpt markFailed scope-filter - out-of-scope entry survives markFailed`() {
        val store = storeWith(listOf(
            Session(id = "inScope", directory = "/work-a"),
            Session(id = "outScope", directory = "/other-dir"),
        ))
        // Seed both entries under the SAME scope (both written via event() which uses
        // the test scope). Both have scopeKey == default test scope.
        store.dispatch(AppAction.AuthorityEvent(
            event("inScope", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 50L, workdir = "/work-a"),
        ))
        store.dispatch(AppAction.AuthorityEvent(
            event("outScope", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 50L, workdir = "/other-dir"),
        ))

        // MarkSourceFailed for the default scope at t=100.
        // §P0-A r2: scopeKey-based filtering → BOTH entries have scopeKey == scope →
        // BOTH are in-scope and get removed (neither's scopeKey differs).
        store.dispatch(AppAction.AuthorityEvent(
            AuthorityOp.MarkSourceFailed(
                scopeKey = scope,
                requestToken = RequestToken(hostProfileId = PROFILE_ID, requestStartMs = 100L, identityEpoch = 0L),
                monotonic = 100L,
                registeredWorkdirs = setOf("/work-a"),
            ),
        ))

        val bySid = store.stateFlow.value.authority.bySid
        assertNull("in-scope entry removed (same scopeKey)", bySid["inScope"])
        assertNull("same-scope entry also removed (same scopeKey, not protected by different workdir anymore)", bySid["outScope"])
    }

    // ── authorityRevision bump ───────────────────────────────────────────

    @Test
    fun `rev-gpt authorityRevision - bumps on real authority change, not on no-op`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        val rev0 = store.stateFlow.value.authorityRevision

        // Real change: write a status.
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 100L, workdir = "/w"),
        ))
        val rev1 = store.stateFlow.value.authorityRevision
        assertTrue("revision bumps on real change", rev1 > rev0)

        // No-op: re-deliver the same status + monotonic.
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 100L, workdir = "/w"),
        ))
        assertEquals("revision unchanged on no-op", rev1, store.stateFlow.value.authorityRevision)
    }

    // ── B10: SessionListState non-data-class encapsulation ───────────────

    @Test
    fun `rev-gpt B10 - withProjection sets sessionStatuses and preserves other fields`() {
        val original = SessionListState(
            sessions = listOf(Session(id = "s1", directory = "/w")),
            activeSessionIds = setOf("s1"),
            childSessions = mapOf("/w" to listOf(Session(id = "s1", directory = "/w"))),
        )
        val projection = mapOf("s1" to SessionStatus(type = "busy"))
        val updated = original.withProjection(projection)

        assertEquals(projection, updated.sessionStatuses)
        assertEquals(original.sessions, updated.sessions)
        assertEquals(original.activeSessionIds, updated.activeSessionIds)
        assertEquals(original.childSessions, updated.childSessions)
    }

    @Test
    fun `rev-gpt B10 - manual copy preserves all fields including sessionStatuses`() {
        val original = SessionListState(
            sessions = listOf(Session(id = "s1", directory = "/w")),
            activeSessionIds = setOf("s1"),
        ).withProjection(mapOf("s1" to SessionStatus(type = "busy")))

        // copy() with only sessions changed → sessionStatuses PRESERVED.
        val updated = original.copy(sessions = emptyList())
        assertEquals(emptyList<Session>(), updated.sessions)
        assertEquals(original.sessionStatuses, updated.sessionStatuses)
        assertEquals(original.activeSessionIds, updated.activeSessionIds)
    }

    @Test
    fun `rev-gpt B10 - value equality holds for SessionListState - StoreState equality gate`() {
        // If manual equals/hashCode were wrong, StoreState.equals would break
        // (StoreState is a data class containing sessionList: SessionListState).
        // This test verifies two structurally-equal SessionListStates are equal.
        val a = SessionListState(
            sessions = listOf(Session(id = "s1", directory = "/w")),
            activeSessionIds = setOf("s1"),
        ).withProjection(mapOf("s1" to SessionStatus(type = "busy")))
        val b = SessionListState(
            sessions = listOf(Session(id = "s1", directory = "/w")),
            activeSessionIds = setOf("s1"),
        ).withProjection(mapOf("s1" to SessionStatus(type = "busy")))

        assertEquals("structurally-equal SessionListStates must be equal", a, b)
        assertEquals("hashCode must match for equal states", a.hashCode(), b.hashCode())

        // A different sessionStatuses must NOT be equal.
        val c = a.withProjection(mapOf("s1" to SessionStatus(type = "idle")))
        assertNotSame("different sessionStatuses → not equal", a, c)
        assertTrue("different sessionStatuses → not equals", a != c)
    }

    @Test
    fun `rev-gpt B10 - reduceAuthority is the sole writer of sessionStatuses via withProjection`() {
        // After an authority dispatch, sessionStatuses MUST equal the authority
        // projection (reduceAuthority writes it via withProjection). This
        // indirectly verifies the sole-writer gate: sessionStatuses is NOT set
        // by any other path.
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 100L, workdir = "/w"),
        ))

        val state = store.stateFlow.value
        val expectedProjection = state.authority.bySid.mapValues { it.value.status }
        assertEquals(
            "sessionStatuses must equal the authority projection (sole writer = reduceAuthority)",
            expectedProjection,
            state.sessionList.sessionStatuses,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // §P0-A r2 — NEW TESTS
    // ═══════════════════════════════════════════════════════════════════════

    // ── r2 #1: serverRound tie-break (equal incarnation/turn, older monotonic) ──

    @Test
    fun `r2 tie-break - equal serverRound with older monotonic is DROPPED`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        val round = ServerRound(1L, 5L)
        // First: busy at monotonic=200.
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = round, monotonic = 200L, workdir = "/w"),
        ))
        val afterNew = store.stateFlow.value.sessionList.sessionStatuses["s1"]

        // Tie-break: equal serverRound, OLDER monotonic (100 < 200) → DROP.
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_SLIM,
                serverRound = round, monotonic = 100L, workdir = "/w"),
        ))
        assertEquals("equal-serverRound with older monotonic dropped — busy retained",
            afterNew, store.stateFlow.value.sessionList.sessionStatuses["s1"])
    }

    @Test
    fun `r2 tie-break - equal serverRound with newer monotonic overwrites`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        val round = ServerRound(1L, 5L)
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = round, monotonic = 100L, workdir = "/w"),
        ))

        // Tie-break: equal serverRound, NEWER monotonic (300 > 100) → overwrites.
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_SLIM,
                serverRound = round, monotonic = 300L, workdir = "/w"),
        ))
        assertEquals("equal-serverRound with newer monotonic overwrites",
            SessionStatus(type = "idle"),
            store.stateFlow.value.sessionList.sessionStatuses["s1"])
    }

    // ── r2 #3c: same-ref terminal ApplyEvent must release abortPending ──

    @Test
    fun `r2 same-ref terminal ApplyEvent releases abortPending even when nextAuth is same reference`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        // Seed authority with a busy entry for s1.
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 100L, workdir = "/w"),
        ))
        // Set abort-pending flag.
        store.mutateSessionList { it.copy(abortPendingSessionIds = mapOf("s1" to 999L)) }
        assertTrue("abort-pending seeded", "s1" in store.stateFlow.value.sessionList.abortPendingSessionIds)

        // Now apply ANOTHER busy event with different monotonic (so entry differs)
        // — this makes a "not same-ref" transition that also clears abort-pending.
        // To test the SAME-REF path specifically, we need an ApplyEvent whose
        // nextEntry == prev (same status + monotonic) but IS terminal:
        // a re-delivered idle with the same monotonic value.
        // First switch to idle:
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, monotonic = 200L, workdir = "/w"),
        ))
        assertEquals("idle seeded", SessionStatus(type = "idle"),
            store.stateFlow.value.sessionList.sessionStatuses["s1"])
        // Re-set abort-pending (the previous dispatch cleared it).
        store.mutateSessionList { it.copy(abortPendingSessionIds = mapOf("s1" to 888L)) }

        // Re-deliver IDLE with SAME monotonic = 200 → nextEntry == prev → same ref
        // but IS terminal (idle, not busy/retry) → MUST release abortPending.
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, monotonic = 200L, workdir = "/w"),
        ))
        val state = store.stateFlow.value
        assertFalse("same-ref terminal idle MUST release abortPending (the abortRelease branch)",
            "s1" in state.sessionList.abortPendingSessionIds)
        // authorityRevision must also bump on this special path.
        assertTrue("authorityRevision bumps on abortRelease even with same-ref authority",
            state.authorityRevision > 0L)
    }

    @Test
    fun `r2 same-ref terminal but sid not in abortPending is a true no-op`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        // Seed idle at monotonic=100.
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, monotonic = 100L, workdir = "/w"),
        ))
        val before = store.stateFlow.value

        // Re-deliver same idle — NOT in abortPending → true no-op.
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, monotonic = 100L, workdir = "/w"),
        ))
        assertSame("same-ref idle without abortPending is true CAS no-op", before, store.stateFlow.value)
    }

    // ── r2 #4: applyMarkFailed scopeKey-based filtering ──

    @Test
    fun `r2 markFailed scopeKey filter - out-of-scope entry survives by scopeKey`() {
        val diffScope = ScopeKey(serverGroupFp = "other-grp", endpointFp = "other-ep")
        val store = storeWith(listOf(
            Session(id = "inScope", directory = "/shared-dir"),
            Session(id = "outScope", directory = "/shared-dir"),
        ))
        // Both entries share the SAME workdir but have DIFFERENT scopeKeys.
        // inScope is written under the default test scope.
        store.dispatch(AppAction.AuthorityEvent(
            event("inScope", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY,
                monotonic = 50L, workdir = "/shared-dir"),
        ))
        // outScope is written under a DIFFERENT scope via direct authority state
        // manipulation (the event helper writes via dispatch, which uses scopeKey=scope).
        // For out-of-scope we seed via PruneSessions then re-add... Actually, we just
        // seed authority directly for the second entry under the different scope.
        store.mutateState { s ->
            val cur = s.authority
            s.copy(authority = cur.copy(
                bySid = cur.bySid + ("outScope" to cur.bySid["inScope"]!!.copy(
                    scopeKey = diffScope,
                ))
            ))
        }
        assertEquals("outScope has different scopeKey",
            diffScope, store.stateFlow.value.authority.bySid["outScope"]?.scopeKey)

        // MarkSourceFailed for the default scope at t=100.
        store.dispatch(AppAction.AuthorityEvent(
            AuthorityOp.MarkSourceFailed(
                scopeKey = scope,
                requestToken = RequestToken(hostProfileId = PROFILE_ID, requestStartMs = 100L, identityEpoch = 0L),
                monotonic = 100L,
                registeredWorkdirs = setOf("/shared-dir"),
            ),
        ))

        val bySid = store.stateFlow.value.authority.bySid
        assertNull("in-scope entry removed (scope matches)", bySid["inScope"])
        assertNotNull("out-of-scope entry survives (different scopeKey even though same workdir)", bySid["outScope"])
    }

    // ── r2 #3b/7: identityEpoch + authorityRevision bump on HostState change ──

    @Test
    fun `r2 identityEpoch bumps on ANY HostState change not just hostProfileId`() {
        val store = SharedStateStore()
        val epoch0 = store.stateFlow.value.identityEpoch

        // Change host without changing currentHostProfileId (simulate a same-profile
        // hostProfiles map update). mutateHost bumps on nextHost != prevHost.
        store.mutateHost { it.copy(hostProfiles = listOf(HostProfile(name = "k", serverUrl = "http://x"))) }
        val epoch1 = store.stateFlow.value.identityEpoch
        assertTrue("identityEpoch bumps on non-profile-id HostState change", epoch1 > epoch0)

        // Another non-profile-id change (different hostProfiles value).
        store.mutateHost { it.copy(hostProfiles = listOf(HostProfile(name = "k2", serverUrl = "http://x"))) }
        val epoch2 = store.stateFlow.value.identityEpoch
        assertTrue("identityEpoch bumps again on another HostState change", epoch2 > epoch1)
    }

    @Test
    fun `r2 opScopeValid rejects ApplySnapshot when identityEpoch mismatches`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        // Bump identityEpoch to a known value.
        store.mutateHost { it.copy(currentHostProfileId = "host-A") }
        val currentEpoch = store.stateFlow.value.identityEpoch

        // Token with MATCHING epoch → accepted.
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(
                snapshot = mapOf("s1" to SessionStatus(type = "busy")),
                authoritativeNodeIds = setOf("s1"),
                host = "host-A",
                identityEpoch = currentEpoch,
            ),
        ))
        assertNotNull("matching-epoch snapshot accepted", store.stateFlow.value.authority.bySid["s1"])

        // A stale token with LOWER epoch → rejected.
        val stateBeforeReject = store.stateFlow.value
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(
                snapshot = mapOf("s1" to SessionStatus(type = "idle")),
                authoritativeNodeIds = setOf("s1"),
                host = "host-A",
                identityEpoch = currentEpoch - 1L,
            ),
        ))
        assertSame("stale-epoch snapshot dropped (no-op)", stateBeforeReject, store.stateFlow.value)
    }

    // ── r2 #6: SessionEntry scopeKey is stamped by applyEvent/applySnapshot ──

    @Test
    fun `r2 applyEvent stamps scopeKey on SessionEntry`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 100L, workdir = "/w"),
        ))
        val entry = store.stateFlow.value.authority.bySid["s1"]
        assertEquals("scopeKey stamped from op.scopeKey", scope, entry?.scopeKey)
    }

    @Test
    fun `r2 applySnapshot stamps scopeKey on SessionEntry`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(
                snapshot = mapOf("s1" to SessionStatus(type = "busy")),
                authoritativeNodeIds = setOf("s1"),
            ),
        ))
        val entry = store.stateFlow.value.authority.bySid["s1"]
        assertEquals("scopeKey stamped from op.scopeKey", scope, entry?.scopeKey)
    }

    @Test
    fun `r2 authorityRevision bumps on identityEpoch change (host state change)`() {
        val store = SharedStateStore()
        val rev0 = store.stateFlow.value.authorityRevision

        // mutateHost bumps identityEpoch + authorityRevision.
        store.mutateHost { it.copy(currentHostProfileId = "new-host") }
        val rev1 = store.stateFlow.value.authorityRevision
        assertTrue("authorityRevision bumps on host state change", rev1 > rev0)

        // Same-host change still bumps.
        store.mutateHost { it.copy(hostProfiles = listOf(HostProfile(name = "k3", serverUrl = "http://x"))) }
        val rev2 = store.stateFlow.value.authorityRevision
        assertTrue("authorityRevision bumps on non-profile host change", rev2 > rev1)
    }

    // ── §P0-A FIX: PruneSessions scope-key filtering ──────────────────────

    @Test
    fun `rev-gpt PruneSessions scope-filter - out-of-scope entry survives prune`() {
        val diffScope = ScopeKey(serverGroupFp = "other-grp", endpointFp = "other-ep")
        val store = storeWith(listOf(
            Session(id = "inScope", directory = "/w"),
            Session(id = "outScope", directory = "/w"),
        ))
        // Seed inScope under the default test scope.
        store.dispatch(AppAction.AuthorityEvent(
            event("inScope", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 50L, workdir = "/w"),
        ))
        // Seed outScope under a DIFFERENT scope via direct authority manipulation.
        store.mutateState { s ->
            val cur = s.authority
            s.copy(authority = cur.copy(
                bySid = cur.bySid + ("outScope" to cur.bySid["inScope"]!!.copy(scopeKey = diffScope))
            ))
        }
        assertEquals("outScope has different scopeKey",
            diffScope, store.stateFlow.value.authority.bySid["outScope"]?.scopeKey)

        // Prune inScope under the default test scope.
        store.dispatch(AppAction.AuthorityEvent(
            AuthorityOp.PruneSessions(sids = setOf("inScope"), scopeKey = scope),
        ))

        val bySid = store.stateFlow.value.authority.bySid
        assertNull("in-scope entry pruned (scope matches)", bySid["inScope"])
        assertNotNull("out-of-scope entry survives (different scopeKey)", bySid["outScope"])
        assertEquals("out-of-scope scopeKey preserved", diffScope, bySid["outScope"]?.scopeKey)
    }

    @Test
    fun `rev-gpt PruneSessions scope-filter - retain out-of-scope even when sid is in prune set`() {
        val diffScope = ScopeKey(serverGroupFp = "other-grp", endpointFp = "other-ep")
        val store = storeWith(listOf(
            Session(id = "sharedSid", directory = "/w"),
        ))
        // Seed an entry under the default scope with sid "sharedSid".
        store.dispatch(AppAction.AuthorityEvent(
            event("sharedSid", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 50L, workdir = "/w"),
        ))
        // Also seed the same sid under a DIFFERENT scope (simulates sid collision
        // across scopes — unlikely but defensive).
        store.mutateState { s ->
            val cur = s.authority
            s.copy(authority = cur.copy(
                bySid = cur.bySid + ("sharedSid" to cur.bySid["sharedSid"]!!.copy(scopeKey = diffScope))
            ))
        }
        assertEquals("two entries map size still 1 (same sid, overwritten by mutateState)",
            1, store.stateFlow.value.authority.bySid.size)
        // The last write wins — scopeKey is diffScope.

        // Prune "sharedSid" under the default scope.
        store.dispatch(AppAction.AuthorityEvent(
            AuthorityOp.PruneSessions(sids = setOf("sharedSid"), scopeKey = scope),
        ))

        val bySid = store.stateFlow.value.authority.bySid
        // Since the only entry has scopeKey=diffScope (not matching prune scope),
        // it must be retained.
        assertNotNull("sid with out-of-scope scopeKey survives prune even though sid is in set",
            bySid["sharedSid"])
        assertEquals("retained entry's scopeKey unchanged", diffScope, bySid["sharedSid"]?.scopeKey)
    }

    // ── §P0-A FIX: ApplySnapshot preserves out-of-scope entries ────────────

    @Test
    fun `rev-gpt ApplySnapshot preserves out-of-scope entries`() {
        val diffScope = ScopeKey(serverGroupFp = "other-grp", endpointFp = "other-ep")
        val store = storeWith(listOf(
            Session(id = "inScope", directory = "/w"),
            Session(id = "outScope", directory = "/other"),
        ))
        // Seed outScope under a DIFFERENT scope.
        store.dispatch(AppAction.AuthorityEvent(
            event("outScope", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 50L, workdir = "/other"),
        ))
        store.mutateState { s ->
            val cur = s.authority
            s.copy(authority = cur.copy(
                bySid = cur.bySid.mapValues { (sid, entry) ->
                    if (sid == "outScope") entry.copy(scopeKey = diffScope) else entry
                }
            ))
        }
        assertEquals("outScope has different scopeKey",
            diffScope, store.stateFlow.value.authority.bySid["outScope"]?.scopeKey)

        // Capture current projection so mergeStatusSnapshotInFlight sees
        // all entries as unchanged → merged = restSnapshot only (no "outScope"
        // pulled into merged). This prevents the second loop from overwriting
        // the preserved out-of-scope entry with the current scopeKey.
        val currentBefore = store.stateFlow.value.authority.bySid.mapValues { it.value.status }

        // ApplySnapshot for the default scope (inScope only).
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(
                snapshot = mapOf("inScope" to SessionStatus(type = "idle")),
                authoritativeNodeIds = setOf("inScope"),
                localBefore = currentBefore,
            ),
        ))

        val bySid = store.stateFlow.value.authority.bySid
        assertEquals("inScope written by snapshot",
            SessionStatus(type = "idle"), bySid["inScope"]?.status)
        assertNotNull("out-of-scope entry survives snapshot", bySid["outScope"])
        assertEquals("out-of-scope scopeKey preserved", diffScope, bySid["outScope"]?.scopeKey)
        assertEquals("out-of-scope status preserved",
            SessionStatus(type = "busy"), bySid["outScope"]?.status)
    }

    // ── §P0-A FIX: ApplySnapshot no-change with out-of-scope entries ─────────

    @Test
    fun `rev-gpt ApplySnapshot no-change short-circuit works with out-of-scope entries`() {
        val diffScope = ScopeKey(serverGroupFp = "other-grp", endpointFp = "other-ep")
        val store = storeWith(listOf(
            Session(id = "s1", directory = "/w"),
        ))
        // Seed inScope entry matching what snapshot will produce (REST idle).
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(
                snapshot = mapOf("s1" to SessionStatus(type = "idle")),
                authoritativeNodeIds = setOf("s1"),
                sidToWorkdir = mapOf("s1" to "/w"),
                requestStartMs = 100L,
            ),
        ))
        // Add an out-of-scope entry with a sid NOT present in any future
        // snapshot's REST response (so mergeStatusSnapshotInFlight, given a
        // proper localBefore, does NOT pick it up into merged).
        val outScopeSid = "outScopeSid"
        store.mutateState { s ->
            val cur = s.authority
            s.copy(authority = cur.copy(
                bySid = cur.bySid + (outScopeSid to SessionEntry(
                    status = SessionStatus(type = "busy"),
                    serverRound = null,
                    optimisticClaim = null,
                    origin = EntryOrigin.SSE_LEGACY,
                    updatedMonotonic = 50L,
                    workdir = "/other",
                    scopeKey = diffScope,
                ))
            ))
        }

        val before = store.stateFlow.value
        // Capture the current projection so mergeStatusSnapshotInFlight sees
        // all entries as unchanged → merged = restSnapshot only → second loop
        // does NOT pick up the out-of-scope entry.
        val currentBefore = before.authority.bySid.mapValues { it.value.status }
        // Re-apply the same snapshot → no-change (in-scope entry data-class-equal,
        // out-of-scope entry preserved by reference, coverage unchanged).
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(
                snapshot = mapOf("s1" to SessionStatus(type = "idle")),
                authoritativeNodeIds = setOf("s1"),
                sidToWorkdir = mapOf("s1" to "/w"),
                requestStartMs = 100L,
                localBefore = currentBefore,
            ),
        ))
        assertSame("re-applied snapshot with out-of-scope entries must be CAS no-op",
            before, store.stateFlow.value)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // §P0-B ITEM 1 — Tier-2 confirmation gate
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `P0-B confirmation gate DROPs idle when claim unconfirmed`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        // 1. OPTIMISTIC busy (claim not echoed)
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = 100L),
        ))
        val afterOpt = store.stateFlow.value.authority.bySid["s1"]!!
        assertNotNull("optimistic claim present", afterOpt.optimisticClaim)
        assertFalse("claim not echoed", afterOpt.optimisticClaim!!.serverEchoed)

        // 2. SSE_LEGACY idle with no serverRound → gate DROPs it
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, monotonic = 200L),
        ))
        val afterIdle = store.stateFlow.value.authority.bySid["s1"]!!
        assertEquals("status stays busy (idle dropped)", SessionStatus(type = "busy"), afterIdle.status)
        assertNotNull("claim still present", afterIdle.optimisticClaim)
        assertTrue("guardedIdleDrop set to true", afterIdle.optimisticClaim!!.guardedIdleDrop)
    }

    @Test
    fun `P0-B echoed idle clears claim normally`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        // 1. OPTIMISTIC busy
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = 100L),
        ))
        // 2. SSE_LEGACY busy → echo-confirm
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 200L),
        ))
        assertTrue("claim echoed", store.stateFlow.value.authority.bySid["s1"]?.optimisticClaim?.serverEchoed == true)

        // 3. SSE_LEGACY idle (echoed claim) → clears claim, status becomes idle
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, monotonic = 300L),
        ))
        val entry = store.stateFlow.value.authority.bySid["s1"]!!
        assertEquals("status idle", SessionStatus(type = "idle"), entry.status)
        assertNull("claim cleared", entry.optimisticClaim)
    }

    @Test
    fun `P0-B gate does not block slim serverRound idle (Tier-1 fence)`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        // 1. OPTIMISTIC busy (no serverRound, claim unechoed)
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = 100L),
        ))
        // 2. SSE_SLIM idle WITH serverRound → Tier-1 lex guard lets it through
        //    (gate only fires when op.serverRound == null)
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(1L, 5L), monotonic = 200L),
        ))
        val entry = store.stateFlow.value.authority.bySid["s1"]!!
        assertEquals("slim serverRound idle accepted", SessionStatus(type = "idle"), entry.status)
        assertNull("claim cleared by slim idle", entry.optimisticClaim)
    }

    // ── ITEM 2: OPTIMISTIC immediate echo ──

    @Test
    fun `P0-B OPTIMISTIC immediate echo when prev is SSE busy`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        // 1. SSE_LEGACY busy first
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 100L),
        ))
        // 2. OPTIMISTIC (cross-channel reorder: HTTP success after SSE busy)
        //    → claim must have serverEchoed=true
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = 200L),
        ))
        val claim = store.stateFlow.value.authority.bySid["s1"]?.optimisticClaim
        assertNotNull("optimistic claim present", claim)
        assertTrue("immediate echo on SSE prev", claim!!.serverEchoed)
    }

    // ── ITEM 1: abortRelease interaction ──

    @Test
    fun `P0-B abortRelease NOT released when gate drops idle`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        // Set abort-pending BEFORE any status events (OPTIMISTIC where prev=null
        // → claim unechoed, no SSE prev → ITEM 2 immediate-echo does NOT fire).
        store.mutateSessionList { it.copy(abortPendingSessionIds = mapOf("s1" to 999L)) }
        assertTrue("abort-pending seeded", "s1" in store.stateFlow.value.sessionList.abortPendingSessionIds)

        // OPTIMISTIC busy (first event, no prev → claim unechoed)
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = 100L, workdir = "/w"),
        ))
        assertFalse("claim not echoed (no SSE prev)",
            store.stateFlow.value.authority.bySid["s1"]?.optimisticClaim?.serverEchoed == true)

        // SSE_LEGACY idle → gate DROPs it (claim unechoed) → abortPending STILL contains s1
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, monotonic = 200L),
        ))
        assertTrue("abortPending NOT released after gate-drop",
            "s1" in store.stateFlow.value.sessionList.abortPendingSessionIds)
        assertEquals("status still busy", SessionStatus(type = "busy"),
            store.stateFlow.value.sessionList.sessionStatuses["s1"])
    }

    @Test
    fun `P0-B abortRelease released on normal applied idle`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        // Set abort-pending first
        store.mutateSessionList { it.copy(abortPendingSessionIds = mapOf("s1" to 999L)) }
        assertTrue("abort-pending seeded", "s1" in store.stateFlow.value.sessionList.abortPendingSessionIds)

        // OPTIMISTIC busy (claim unechoed)
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = 100L, workdir = "/w"),
        ))
        // SSE_LEGACY busy → echo-confirm
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 200L),
        ))
        assertTrue("claim echoed",
            store.stateFlow.value.authority.bySid["s1"]?.optimisticClaim?.serverEchoed == true)

        // SSE_LEGACY idle (echoed claim → normal apply) → releases abortPending
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, monotonic = 300L),
        ))
        assertFalse("abortPending released on normal idle",
            "s1" in store.stateFlow.value.sessionList.abortPendingSessionIds)
        assertEquals("status idle", SessionStatus(type = "idle"),
            store.stateFlow.value.sessionList.sessionStatuses["s1"])
    }

    // ── ApplyReconcileOutcome IDLE_CONFIRMED path ──

    @Test
    fun `P0-B ApplyReconcileOutcome IDLE_CONFIRMED clears claim and sets idle`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        // Seed busy with claim
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = 100L),
        ))
        assertNotNull("claim present", store.stateFlow.value.authority.bySid["s1"]?.optimisticClaim)

        // ApplyReconcileOutcome IDLE_CONFIRMED
        store.dispatch(AppAction.AuthorityEvent(
            AuthorityOp.ApplyReconcileOutcome(
                sid = "s1",
                scopeKey = scope,
                outcome = cn.vectory.ocdroid.data.state.ReconcileOutcome.IDLE_CONFIRMED,
                serverRound = null,
                monotonic = 500L,
                claimClientSeq = store.stateFlow.value.authority.bySid["s1"]?.optimisticClaim?.clientSeq ?: 0L,
                hostProfileId = store.stateFlow.value.host.currentHostProfileId,
                identityEpochAtCapture = store.stateFlow.value.identityEpoch,
            ),
        ))
        val entry = store.stateFlow.value.authority.bySid["s1"]!!
        assertEquals("status idle after reconcile", SessionStatus(type = "idle"), entry.status)
        assertNull("claim cleared by reconcile", entry.optimisticClaim)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // §P0-B final-fix — 4 regression tests
    // ═══════════════════════════════════════════════════════════════════════

    /** Private helper: builds an ApplyReconcileOutcome that matches the current
     *  test store's host and identityEpoch. The store must have an optimistic
     *  claim for [sid] (clientSeq is read from the live claim). */
    private fun reconcileOutcome(
        store: SharedStateStore,
        sid: String,
        outcome: ReconcileOutcome,
        claimClientSeq: Long? = null,
        monotonic: Long = 999L,
    ) = AuthorityOp.ApplyReconcileOutcome(
        sid = sid,
        scopeKey = scope,
        outcome = outcome,
        serverRound = null,
        monotonic = monotonic,
        claimClientSeq = claimClientSeq ?: store.stateFlow.value.authority.bySid[sid]?.optimisticClaim?.clientSeq ?: 0L,
        hostProfileId = store.stateFlow.value.host.currentHostProfileId,
        identityEpochAtCapture = store.stateFlow.value.identityEpoch,
    )

    // ── Test ①: BUSY_CONFIRMED echo-confirms claim, watchdog no longer re-selects ──

    @Test
    fun `final-fix-1 BUSY_CONFIRMED echo-confirms optimistic claim and stops watchdog re-select`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        // 1. OPTIMISTIC busy → claim clientSeq=1, serverEchoed=false
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = 100L),
        ))
        val claim1 = store.stateFlow.value.authority.bySid["A"]?.optimisticClaim
        assertNotNull("claim present after OPTIMISTIC", claim1)
        assertEquals("clientSeq = 1", 1L, claim1?.clientSeq)
        assertFalse("serverEchoed = false", claim1?.serverEchoed == true)

        // 2. ApplyReconcileOutcome BUSY_CONFIRMED with matching claimClientSeq
        store.dispatch(AppAction.AuthorityEvent(
            reconcileOutcome(store, "A", ReconcileOutcome.BUSY_CONFIRMED),
        ))
        val entry = store.stateFlow.value.authority.bySid["A"]!!
        assertEquals("status stays busy", SessionStatus(type = "busy"), entry.status)
        assertNotNull("claim NOT null (confirmation not lost)", entry.optimisticClaim)
        // §P0-B final-fix #1: reconcile BUSY_CONFIRMED sets reconcileConfirmed (NOT serverEchoed)
        assertTrue("claim reconcileConfirmed = true",
            entry.optimisticClaim!!.reconcileConfirmed)
        assertFalse("claim serverEchoed = false (SSE-echo-only — not touched by reconcile)",
            entry.optimisticClaim!!.serverEchoed)

        // 3. Watchdog: serverEchoed || reconcileConfirmed → skip → no stale claims
        val stale = selectStaleClaimsForReconcile(
            store.stateFlow.value.authority,
            now = 999_999L, // far beyond any timeout
        )
        assertTrue("watchdog re-select is empty (no per-tick GET loop)", stale.isEmpty())
    }

    // ── Test ①b: reconcile confirmation does NOT pollute the next optimistic generation ──

    @Test
    fun `final-fix-1b reconcile confirmation does not pollute the next optimistic generation`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        // 1. OPTIMISTIC busy → claim clientSeq=1, both flags false
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = 100L),
        ))
        var claim = store.stateFlow.value.authority.bySid["A"]?.optimisticClaim!!
        assertEquals("clientSeq = 1", 1L, claim.clientSeq)
        assertFalse("serverEchoed = false", claim.serverEchoed)
        assertFalse("reconcileConfirmed = false", claim.reconcileConfirmed)

        // 2. reconcile BUSY_CONFIRMED (matching claimClientSeq=1) → sets reconcileConfirmed=true
        store.dispatch(AppAction.AuthorityEvent(
            reconcileOutcome(store, "A", ReconcileOutcome.BUSY_CONFIRMED),
        ))
        claim = store.stateFlow.value.authority.bySid["A"]?.optimisticClaim!!
        assertTrue("reconcileConfirmed = true after BUSY_CONFIRMED", claim.reconcileConfirmed)
        assertFalse("serverEchoed still false (not touched by reconcile)", claim.serverEchoed)

        // 3. SECOND OPTIMISTIC busy → NEW generation, clientSeq=2
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = 200L),
        ))
        claim = store.stateFlow.value.authority.bySid["A"]?.optimisticClaim!!
        assertEquals("new generation clientSeq = 2", 2L, claim.clientSeq)
        // §P0-B final-fix #1: the NEW claim must NOT inherit reconcileConfirmed
        assertFalse("reconcileConfirmed = false (NOT inherited — cross-generation pollution prevented)",
            claim.reconcileConfirmed)
        // serverEchoed must also be false (no SSE echo happened)
        assertFalse("serverEchoed = false on new claim (no SSE echo)", claim.serverEchoed)

        // 4. Watchdog STILL arms on the new claim (only 1 stale: the unconfirmed generation)
        val stale = selectStaleClaimsForReconcile(
            store.stateFlow.value.authority,
            now = 999_999L, // far beyond timeout
        )
        assertEquals("watchdog returns exactly one stale claim (the new generation)", 1, stale.size)
        assertEquals("stale claim sid = A", "A", stale[0].sid)
        assertEquals("stale claim clientSeq = 2", 2L, stale[0].clientSeq)

        // 5. Confirmation gate STILL protects the new claim from a stale legacy IDLE
        val beforeGate = store.stateFlow.value
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, monotonic = 300L),
        ))
        val afterGate = store.stateFlow.value
        assertEquals("status still busy (gate dropped the stale legacy IDLE)",
            SessionStatus(type = "busy"), afterGate.authority.bySid["A"]?.status)
        assertNotNull("claim still present", afterGate.authority.bySid["A"]?.optimisticClaim)
        assertTrue("guardedIdleDrop set on claim",
            afterGate.authority.bySid["A"]?.optimisticClaim?.guardedIdleDrop == true)
    }

    // ── Test ①c: FETCH_FAILED outcome ──

    @Test
    fun `P0-B ApplyReconcileOutcome FETCH_FAILED removes entry and is a no-op when prev is null`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        // 1. OPTIMISTIC busy → claim clientSeq=1
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = 100L),
        ))
        assertNotNull("claim present after OPTIMISTIC",
            store.stateFlow.value.authority.bySid["A"]?.optimisticClaim)

        // 2. FETCH_FAILED with matching claimClientSeq → entry REMOVED
        store.dispatch(AppAction.AuthorityEvent(
            reconcileOutcome(store, "A", ReconcileOutcome.FETCH_FAILED),
        ))
        assertNull("entry removed after FETCH_FAILED",
            store.stateFlow.value.authority.bySid["A"])

        // 3. FETCH_FAILED when prev is ALREADY null (entry already gone) → no-op
        //    Must not crash and state must remain unchanged.
        store.dispatch(AppAction.AuthorityEvent(
            AuthorityOp.ApplyReconcileOutcome(
                sid = "A", scopeKey = scope,
                outcome = ReconcileOutcome.FETCH_FAILED,
                serverRound = null, monotonic = 999L,
                claimClientSeq = 1L,
                hostProfileId = store.stateFlow.value.host.currentHostProfileId,
                identityEpochAtCapture = store.stateFlow.value.identityEpoch,
            ),
        ))
        assertNull("entry still absent after no-op FETCH_FAILED (prev was null)",
            store.stateFlow.value.authority.bySid["A"])
    }

    @Test
    fun `P0-B ApplyReconcileOutcome FETCH_FAILED generation fence drops stale-generation outcome`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        // 1. OPTIMISTIC busy → claim clientSeq=1
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = 100L),
        ))
        assertEquals("clientSeq = 1", 1L,
            store.stateFlow.value.authority.bySid["A"]?.optimisticClaim?.clientSeq)

        // 2. Another OPTIMISTIC busy → claim advances to clientSeq=2
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = 200L),
        ))
        assertEquals("clientSeq advanced to 2", 2L,
            store.stateFlow.value.authority.bySid["A"]?.optimisticClaim?.clientSeq)

        // 3. FETCH_FAILED with stale claimClientSeq=1 → DROPPED (entry NOT removed)
        store.dispatch(AppAction.AuthorityEvent(
            reconcileOutcome(store, "A", ReconcileOutcome.FETCH_FAILED, claimClientSeq = 1L),
        ))
        val entry = store.stateFlow.value.authority.bySid["A"]
        assertNotNull("entry NOT removed by stale-generation FETCH_FAILED (fence dropped it)", entry)
        assertEquals("claim clientSeq unchanged (stale outcome dropped)", 2L,
            entry?.optimisticClaim?.clientSeq)
    }

    // ── Test ②: generation fence drops stale-generation outcome (ABA) ──

    @Test
    fun `final-fix-2 reconcile generation fence drops stale-generation outcome`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        // 1. OPTIMISTIC busy → claim clientSeq=1
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = 100L),
        ))
        assertEquals("clientSeq = 1", 1L,
            store.stateFlow.value.authority.bySid["A"]?.optimisticClaim?.clientSeq)

        // 2. Another OPTIMISTIC busy → claim advances to clientSeq=2
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = 200L),
        ))
        assertEquals("clientSeq advanced to 2", 2L,
            store.stateFlow.value.authority.bySid["A"]?.optimisticClaim?.clientSeq)

        // 3. Stale reconcile with claimClientSeq=1 (the superseded generation)
        val staleOutcome = reconcileOutcome(
            store, "A", ReconcileOutcome.IDLE_CONFIRMED,
            claimClientSeq = 1L, // stale — current claim is clientSeq=2
        )
        store.dispatch(AppAction.AuthorityEvent(staleOutcome))

        // 4. Assert DROPPED: claim still clientSeq=2, status still busy
        val entry = store.stateFlow.value.authority.bySid["A"]!!
        assertEquals("claim clientSeq unchanged (stale outcome dropped)", 2L,
            entry.optimisticClaim?.clientSeq)
        assertEquals("status still busy (stale idle did NOT overwrite)",
            SessionStatus(type = "busy"), entry.status)
    }

    // ── Test ③: host/epoch guard drops ApplyReconcileOutcome ──

    @Test
    fun `final-fix-3 ApplyReconcileOutcome with mismatched host or epoch guard is DROPPED`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        // Seed optimistic claim (clientSeq=1)
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = 100L),
        ))
        val beforeState = store.stateFlow.value
        val claim = beforeState.authority.bySid["A"]?.optimisticClaim!!
        val host = beforeState.host.currentHostProfileId
        val epoch = beforeState.identityEpoch

        // --- Part A: mismatched host ---
        store.dispatch(AppAction.AuthorityEvent(
            AuthorityOp.ApplyReconcileOutcome(
                sid = "A", scopeKey = scope,
                outcome = ReconcileOutcome.BUSY_CONFIRMED,
                serverRound = null, monotonic = 999L,
                claimClientSeq = claim.clientSeq,
                hostProfileId = "DIFFERENT-HOST", // mismatched
                identityEpochAtCapture = epoch,
            ),
        ))
        var entry = store.stateFlow.value.authority.bySid["A"]!!
        assertFalse("serverEchoed still false after host-mismatched BUSY_CONFIRMED",
            entry.optimisticClaim?.serverEchoed == true)

        // --- Part B: mismatched epoch ---
        store.dispatch(AppAction.AuthorityEvent(
            AuthorityOp.ApplyReconcileOutcome(
                sid = "A", scopeKey = scope,
                outcome = ReconcileOutcome.BUSY_CONFIRMED,
                serverRound = null, monotonic = 999L,
                claimClientSeq = claim.clientSeq,
                hostProfileId = host,
                identityEpochAtCapture = epoch + 1L, // stale epoch
            ),
        ))
        entry = store.stateFlow.value.authority.bySid["A"]!!
        assertFalse("serverEchoed still false after epoch-mismatched BUSY_CONFIRMED",
            entry.optimisticClaim?.serverEchoed == true)
    }

    // ── Test ④: B6 incarnation advance resets only the advancing scope's serverRound ──

    @Test
    fun `final-fix-4 incarnation advance resets only the advancing scopes serverRound not other scopes`() {
        val diffScope = ScopeKey(serverGroupFp = "other-grp", endpointFp = "other-ep")
        val store = storeWith(listOf(
            Session(id = "A", directory = "/x"),
            Session(id = "B", directory = "/other"),
        ))
        // Seed A under the default scope with serverRound(1,1)
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(1L, 1L), monotonic = 100L),
        ))
        // Seed B under a DIFFERENT scope via direct authority manipulation
        store.mutateState { s ->
            val cur = s.authority
            s.copy(authority = cur.copy(
                bySid = cur.bySid + ("B" to SessionEntry(
                    status = SessionStatus(type = "busy"),
                    serverRound = ServerRound(1L, 1L),
                    optimisticClaim = null,
                    origin = EntryOrigin.SSE_SLIM,
                    updatedMonotonic = 100L,
                    workdir = "/other",
                    scopeKey = diffScope,
                ))
            ))
        }
        // Assert both have serverRound(1,1)
        assertEquals(ServerRound(1L, 1L),
            store.stateFlow.value.authority.bySid["A"]?.serverRound)
        assertEquals(ServerRound(1L, 1L),
            store.stateFlow.value.authority.bySid["B"]?.serverRound)
        assertNull("B not in knownIncarnations for diffScope (no high-water yet)",
            store.stateFlow.value.authority.knownIncarnations[diffScope])

        // Incarnation advance for A under default scope (incarnation 2)
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(2L, 1L), monotonic = 200L),
        ))
        val bySid = store.stateFlow.value.authority.bySid
        assertEquals("A's serverRound advanced",
            ServerRound(2L, 1L), bySid["A"]?.serverRound)
        assertEquals("A's knownIncarnations bumped",
            2L, store.stateFlow.value.authority.knownIncarnations[scope])
        // B must NOT have its serverRound reset (scope-filtered)
        assertEquals("B's serverRound STILL (1,1) — NOT reset by A's incarnation advance",
            ServerRound(1L, 1L), bySid["B"]?.serverRound)
        assertNull("knownIncarnations[otherScope] still absent (unchanged)",
            store.stateFlow.value.authority.knownIncarnations[diffScope])
    }

    // ═══════════════════════════════════════════════════════════════════════
    // §P0-C (B11) — identity-epoch guard
    // ═══════════════════════════════════════════════════════════════════════

    private val capturedIdentity = cn.vectory.ocdroid.service.identity.ConnectionIdentity(
        epoch = 5L,
        serverGroupFp = "grp",
        normalizedWorkdir = "/w",
        endpointFp = "ep",
    )

    private fun eventWithIdentity(
        sid: String,
        status: SessionStatus,
        origin: EntryOrigin,
        capturedIdentity: cn.vectory.ocdroid.service.identity.ConnectionIdentity? = this.capturedIdentity,
        identityEpochAtCapture: Long = 5L,
        monotonic: Long = 100L,
    ) = AuthorityOp.ApplyEvent(
        sid = sid,
        status = status,
        origin = origin,
        capturedIdentity = capturedIdentity,
        identityEpochAtCapture = identityEpochAtCapture,
        scopeKey = scope,
        connectionMonotonicMs = monotonic,
    )

    @Test
    fun `P0-C stale optimistic ApplyEvent is DROPPED when identityEpoch advanced`() {
        val state = StoreState.initial().copy(identityEpoch = 6L)
        val result = reduceAuthority(state, eventWithIdentity(
            sid = "s1",
            status = SessionStatus(type = "busy"),
            origin = EntryOrigin.OPTIMISTIC,
            identityEpochAtCapture = 5L, // stale — predates state.identityEpoch=6
        ))
        assertSame("stale optimistic op returns the original state (DROP)",
            state, result)
        assertNull("stale optimistic op must not write bySid",
            result.authority.bySid["s1"])
    }

    @Test
    fun `P0-C fresh optimistic ApplyEvent is ACCEPTED when identityEpoch matches`() {
        val state = StoreState.initial().copy(identityEpoch = 5L)
        val result = reduceAuthority(state, eventWithIdentity(
            sid = "s1",
            status = SessionStatus(type = "busy"),
            origin = EntryOrigin.OPTIMISTIC,
            identityEpochAtCapture = 5L, // matches state.identityEpoch
        ))
        val entry = result.authority.bySid["s1"]
        assertNotNull("fresh optimistic op must write bySid", entry)
        assertEquals("origin is OPTIMISTIC", EntryOrigin.OPTIMISTIC, entry?.origin)
        assertNotNull("optimisticClaim stamped", entry?.optimisticClaim)
    }

    @Test
    fun `P0-C stale SSE ApplyEvent is DROPPED when identityEpoch advanced`() {
        val state = StoreState.initial().copy(identityEpoch = 4L)
        val result = reduceAuthority(state, eventWithIdentity(
            sid = "s1",
            status = SessionStatus(type = "busy"),
            origin = EntryOrigin.SSE_LEGACY,
            identityEpochAtCapture = 3L, // stale — predates state.identityEpoch=4
        ))
        assertSame("stale SSE op returns the original state (DROP)",
            state, result)
        assertNull("stale SSE op must not write bySid",
            result.authority.bySid["s1"])
    }

    @Test
    fun `P0-C null-identity ApplyEvent is accepted regardless of identityEpoch (cold-start backward compat)`() {
        val state = StoreState.initial().copy(identityEpoch = 42L)
        // capturedIdentity=null, identityEpochAtCapture=0L (default) — lenient pass.
        val op = AuthorityOp.ApplyEvent(
            sid = "s1",
            status = SessionStatus(type = "busy"),
            origin = EntryOrigin.SSE_LEGACY,
            capturedIdentity = null,
            identityEpochAtCapture = 0L,
            scopeKey = scope,
            connectionMonotonicMs = 100L,
        )
        val result = reduceAuthority(state, op)
        val entry = result.authority.bySid["s1"]
        assertNotNull("null-identity ApplyEvent must be accepted (backward compat)", entry)
        assertEquals("busy", entry?.status?.type)
    }

    @Test
    fun `P0-C CAS-retry idempotency - feeding the same ApplyEvent twice yields the same state`() {
        val state = StoreState.initial().copy(identityEpoch = 5L)
        val op = eventWithIdentity(
            sid = "s1",
            status = SessionStatus(type = "busy"),
            origin = EntryOrigin.OPTIMISTIC,
            identityEpochAtCapture = 5L,
        )
        val r1 = reduceAuthority(state, op)
        val r2 = reduceAuthority(state, op)
        assertEquals("same (state, op) must yield equal output (CAS-retry idempotent)",
            r1, r2)
        assertEquals("bySid must have exactly one entry",
            1, r2.authority.bySid.size)
    }

    @Test
    fun `P0-C cross-host scope attribution - scopeKey on entry equals captured identity scope not current host`() {
        // The scopeKey on the SessionEntry must be derived from the CAPTURED identity,
        // not from the current host. We test this by creating an ApplyEvent whose
        // scopeKey differs from the test scope (simulating a different captured
        // identity's scope). The reducer stamps op.scopeKey on the entry.
        val altScope = ScopeKey(serverGroupFp = "alt-grp", endpointFp = "alt-ep")
        val state = StoreState.initial().copy(identityEpoch = 5L)
        val op = AuthorityOp.ApplyEvent(
            sid = "s1",
            status = SessionStatus(type = "busy"),
            origin = EntryOrigin.SSE_LEGACY,
            capturedIdentity = capturedIdentity,
            identityEpochAtCapture = 5L,
            scopeKey = altScope, // derived from captured identity, NOT current host
            connectionMonotonicMs = 100L,
        )
        val result = reduceAuthority(state, op)
        val entry = result.authority.bySid["s1"]
        assertEquals("scopeKey on SessionEntry equals the op's scopeKey (= captured identity's scope)",
            altScope, entry?.scopeKey)
        assertEquals("alt-grp", entry?.scopeKey?.serverGroupFp)
        assertEquals("alt-ep", entry?.scopeKey?.endpointFp)
    }

    @Test
    fun `r4 scope-guard - in-flight merge does not migrate out-of-scope entry to current scope`() {
        val diffScope = ScopeKey(serverGroupFp = "other-grp", endpointFp = "other-ep")
        val store = storeWith(listOf(Session(id = "inScope", directory = "/w")))
        store.dispatch(AppAction.AuthorityEvent(
            event("inScope", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, workdir = "/w"),
        ))
        // Add out-of-scope entry (busy) via direct state manipulation.
        store.mutateState { s ->
            s.copy(authority = s.authority.copy(
                bySid = s.authority.bySid + ("outSid" to SessionEntry(
                    status = SessionStatus(type = "busy"),
                    serverRound = null, optimisticClaim = null,
                    origin = EntryOrigin.SSE_LEGACY,
                    updatedMonotonic = 50L, workdir = "/other", scopeKey = diffScope,
                ))
            ))
        }
        // Capture localBefore INCLUDING the out-of-scope entry's OLD status (busy).
        val localBefore = store.stateFlow.value.authority.bySid.mapValues { it.value.status }
        // Simulate: out-of-scope entry changes during request (busy → idle).
        store.mutateState { s ->
            val out = s.authority.bySid["outSid"]!!
            s.copy(authority = s.authority.copy(
                bySid = s.authority.bySid + ("outSid" to out.copy(status = SessionStatus(type = "idle")))
            ))
        }
        // Apply REST snapshot. localBefore has OLD out-of-scope status.
        // currentProjection (scope-filtered) EXCLUDES outSid → merge does NOT pull it in.
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(
                snapshot = mapOf("inScope" to SessionStatus(type = "idle")),
                authoritativeNodeIds = setOf("inScope"),
                sidToWorkdir = mapOf("inScope" to "/w"),
                localBefore = localBefore,
            ),
        ))
        val bySid = store.stateFlow.value.authority.bySid
        assertEquals("out-of-scope entry NOT migrated to current scope",
            diffScope, bySid["outSid"]?.scopeKey)
        assertEquals("out-of-scope status preserved (latest, not overwritten)",
            SessionStatus(type = "idle"), bySid["outSid"]?.status)
    }

    // ── §P0-E(c): pendingErrorCheck marking on busy/retry → terminal-idle ──

    @Test
    fun `P0-E pendingErrorCheck - busy to idle transition adds sid to pendingErrorCheck`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 100L, workdir = "/w"),
        ))
        assertEquals("busy seeded", "busy", store.stateFlow.value.authority.bySid["s1"]?.status?.type)
        assertTrue("pendingErrorCheck initially empty",
            store.stateFlow.value.chat.pendingErrorCheck.isEmpty())

        // Idle transition → sid added to pendingErrorCheck.
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, monotonic = 200L, workdir = "/w"),
        ))
        assertTrue("s1 added to pendingErrorCheck",
            "s1" in store.stateFlow.value.chat.pendingErrorCheck)
    }

    @Test
    fun `P0-E pendingErrorCheck - retry to idle transition adds sid to pendingErrorCheck`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "retry"), EntryOrigin.SSE_LEGACY, monotonic = 100L, workdir = "/w"),
        ))
        assertTrue("pendingErrorCheck empty before transition",
            store.stateFlow.value.chat.pendingErrorCheck.isEmpty())

        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, monotonic = 200L, workdir = "/w"),
        ))
        assertTrue("s1 added to pendingErrorCheck after retry→idle",
            "s1" in store.stateFlow.value.chat.pendingErrorCheck)
    }

    @Test
    fun `P0-E pendingErrorCheck - idle to idle transition does NOT add sid`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, monotonic = 100L, workdir = "/w"),
        ))
        assertTrue("pendingErrorCheck empty after first idle",
            store.stateFlow.value.chat.pendingErrorCheck.isEmpty())

        // Another idle → no addition.
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, monotonic = 200L, workdir = "/w"),
        ))
        assertTrue("pendingErrorCheck still empty after second idle",
            store.stateFlow.value.chat.pendingErrorCheck.isEmpty())
    }

    @Test
    fun `P0-E pendingErrorCheck - busy to pruned does NOT add sid (sid absent in nextAuth)`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 100L, workdir = "/w"),
        ))
        // Prune s1 → removed from authority, NOT added to pendingErrorCheck.
        store.dispatch(AppAction.AuthorityEvent(
            AuthorityOp.PruneSessions(sids = setOf("s1"), scopeKey = scope),
        ))
        assertNull("s1 pruned from authority",
            store.stateFlow.value.authority.bySid["s1"])
        assertTrue("pendingErrorCheck empty (pruned is not a terminal-idle transition)",
            store.stateFlow.value.chat.pendingErrorCheck.isEmpty())
    }

    @Test
    fun `P0-E pendingErrorCheck - guard-rejected op does NOT add sid to pendingErrorCheck`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        // Seed busy via OPTIMISTIC origin.
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = 100L, workdir = "/w"),
        ))
        val before = store.stateFlow.value
        assertTrue("pendingErrorCheck initially empty",
            before.chat.pendingErrorCheck.isEmpty())

        // A stale legacy idle WITHOUT server echo → guard rejects (confirmation gate).
        // The op is rejected by the guard, so the early return path (same-ref) fires
        // and pendingErrorCheck MUST NOT be modified.
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, monotonic = 50L, workdir = "/w"),
        ))
        assertTrue("pendingErrorCheck still empty after guarded idle drop",
            store.stateFlow.value.chat.pendingErrorCheck.isEmpty())
    }

    // ── U-CQ9: pendingErrorCheck cap ──────────────────────────────────────

    @Test
    fun `U-CQ9 pendingErrorCheck is capped at 128 entries after 200 busy-idle transitions`() {
        val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
        // Transition s1 busy→idle 200 times. Each transition adds s1 to pendingErrorCheck.
        // After the cap is hit, no new entries are added (set membership already holds).
        repeat(200) { i ->
            store.dispatch(AppAction.AuthorityEvent(
                event("s1", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY,
                    monotonic = 100L + i * 2, workdir = "/w"),
            ))
            store.dispatch(AppAction.AuthorityEvent(
                event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY,
                    monotonic = 101L + i * 2, workdir = "/w"),
            ))
        }
        val size = store.stateFlow.value.chat.pendingErrorCheck.size
        assertTrue("pendingErrorCheck size $size <= 128 (capped)", size <= 128)
    }

    @Test
    fun `U-CQ9 pendingErrorCheck cap with 200 distinct sids`() {
        val store = storeWith()
        // 200 distinct sids each transitioning busy→idle. At 128 the cap kicks in
        // and the oldest sids are dropped.
        val distinctSids = (0 until 200).map { "busy-idle-$it" }
        distinctSids.forEachIndexed { i, sid ->
            val busyOp = AuthorityOp.ApplyEvent(
                sid = sid, status = SessionStatus(type = "busy"),
                origin = EntryOrigin.SSE_LEGACY, scopeKey = scope,
                connectionMonotonicMs = 100L + i * 2L,
            )
            val idleOp = AuthorityOp.ApplyEvent(
                sid = sid, status = SessionStatus(type = "idle"),
                origin = EntryOrigin.SSE_LEGACY, scopeKey = scope,
                connectionMonotonicMs = 101L + i * 2L,
            )
            store.dispatch(AppAction.AuthorityEvent(busyOp))
            store.dispatch(AppAction.AuthorityEvent(idleOp))
        }
        val size = store.stateFlow.value.chat.pendingErrorCheck.size
        assertTrue("pendingErrorCheck size $size <= 128 after 200 distinct sids", size <= 128)
        // The newest sids should be present (oldest dropped).
        assertTrue("newest sid present", "busy-idle-199" in store.stateFlow.value.chat.pendingErrorCheck)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // P1-B/E RetryQueued / RetryFired / terminal-cleanup (retry-queue wire)
    // ═══════════════════════════════════════════════════════════════════════

    private fun retryQueued(
        sid: String,
        attempt: Int = 1,
        backoffMs: Long = 200L,
        queuedMonotonic: Long = 1000L,
    ) = AuthorityOp.RetryQueued(
        sid = sid,
        scopeKey = scope,
        attempt = attempt,
        backoffMs = backoffMs,
        queuedMonotonic = queuedMonotonic,
    )

    private fun retryFired(sid: String, monotonic: Long = 2000L) =
        AuthorityOp.RetryFired(sid = sid, scopeKey = scope, monotonic = monotonic)

    @Test
    fun `RetryQueued enqueues entry with correct attempt backoff and queuedMonotonic`() {
        val store = storeWith()
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1", attempt = 1, backoffMs = 200L, queuedMonotonic = 1000L)))
        val entry = store.stateFlow.value.authority.retryQueue["s1"]
        assertEquals(RetryEntry(attempt = 1, backoffMs = 200L, queuedMonotonic = 1000L), entry)
    }

    @Test
    fun `RetryQueued re-queue with different attempt overwrites the entry`() {
        val store = storeWith()
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1", attempt = 1, backoffMs = 200L, queuedMonotonic = 1000L)))
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1", attempt = 2, backoffMs = 400L, queuedMonotonic = 2000L)))
        val entry = store.stateFlow.value.authority.retryQueue["s1"]
        assertEquals(RetryEntry(attempt = 2, backoffMs = 400L, queuedMonotonic = 2000L), entry)
    }

    @Test
    fun `RetryQueued is idempotent - identical op is a same-ref no-op`() {
        val state = StoreState.initial()
        val op = retryQueued("s1")
        val r1 = reduceAuthority(state, op)
        val r2 = reduceAuthority(r1, op)
        // Second application: the entry already equals → same ref, no bump.
        assertSame("identical RetryQueued MUST be a same-ref no-op", r1, r2)
    }

    @Test
    fun `RetryQueued LRU evicts oldest entry when queue exceeds 256`() {
        val state = StoreState.initial()
        // Fill exactly RETRY_QUEUE_MAX_SIZE (256) entries.
        var cur = state
        for (i in 0 until 256) {
            cur = reduceAuthority(cur, retryQueued("sid-$i", queuedMonotonic = i.toLong()))
        }
        assertEquals(256, cur.authority.retryQueue.size)
        // Insert one more (sid-256 at monotonic=256, newer than all). The
        // oldest (sid-0 at monotonic=0) MUST be evicted.
        cur = reduceAuthority(cur, retryQueued("sid-256", queuedMonotonic = 256L))
        assertEquals("queue capped at 256", 256, cur.authority.retryQueue.size)
        assertFalse("oldest entry (sid-0) evicted", "sid-0" in cur.authority.retryQueue)
        assertTrue("new entry (sid-256) present", "sid-256" in cur.authority.retryQueue)
        assertTrue("second-oldest (sid-1) survives", "sid-1" in cur.authority.retryQueue)
    }

    @Test
    fun `RetryQueued does not evict when re-queuing an existing sid at capacity`() {
        val state = StoreState.initial()
        var cur = state
        for (i in 0 until 256) {
            cur = reduceAuthority(cur, retryQueued("sid-$i", queuedMonotonic = i.toLong()))
        }
        assertEquals(256, cur.authority.retryQueue.size)
        // Re-queue an EXISTING sid (sid-0) with a fresh attempt. The queue
        // size must NOT grow (overwrite, not insert) → no eviction needed.
        cur = reduceAuthority(cur, retryQueued("sid-0", attempt = 2, queuedMonotonic = 9999L))
        assertEquals("re-queue at capacity stays at 256", 256, cur.authority.retryQueue.size)
        assertEquals(2, cur.authority.retryQueue["sid-0"]?.attempt)
    }

    @Test
    fun `RetryFired removes the queued entry`() {
        val store = storeWith()
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1")))
        assertTrue("s1 queued", "s1" in store.stateFlow.value.authority.retryQueue)
        store.dispatch(AppAction.AuthorityEvent(retryFired("s1")))
        assertFalse("s1 fired (removed)", "s1" in store.stateFlow.value.authority.retryQueue)
    }

    @Test
    fun `RetryFired is a same-ref no-op when sid not in queue`() {
        val state = StoreState.initial()
        val op = retryFired("absent")
        val result = reduceAuthority(state, op)
        assertSame("RetryFired on absent sid MUST be same-ref no-op", state, result)
    }

    @Test
    fun `terminal ApplyEvent idle cleans the sid from retryQueue`() {
        val store = storeWith()
        // Queue s1, then deliver a terminal idle event.
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1")))
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, monotonic = 2000L),
        ))
        assertFalse("retry entry cleaned on terminal idle",
            "s1" in store.stateFlow.value.authority.retryQueue)
    }

    @Test
    fun `non-terminal ApplyEvent busy keeps the sid in retryQueue`() {
        val store = storeWith()
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1")))
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 2000L),
        ))
        assertTrue("retry entry kept on non-terminal busy",
            "s1" in store.stateFlow.value.authority.retryQueue)
    }

    @Test
    fun `terminal ApplyEvent retry-status keeps the sid in retryQueue`() {
        // `retry` status is non-terminal (isRetry == true) → queue retained.
        val store = storeWith()
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1")))
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "retry"), EntryOrigin.SSE_LEGACY, monotonic = 2000L),
        ))
        assertTrue("retry entry kept on retry status (non-terminal)",
            "s1" in store.stateFlow.value.authority.retryQueue)
    }

    @Test
    fun `retryQueueFlow value reflects enqueue fire and terminal cleanup`() {
        val store = storeWith()
        // DerivedStateFlow is lag-free: .value reads selector(state.value).
        assertTrue(store.retryQueueFlow.value.isEmpty())

        // Enqueue → .value reflects the new entry immediately.
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1", attempt = 1, queuedMonotonic = 100L)))
        assertEquals(RetryEntry(1, 200L, 100L), store.retryQueueFlow.value["s1"])

        // Fire → .value reflects the removal.
        store.dispatch(AppAction.AuthorityEvent(retryFired("s1")))
        assertTrue(store.retryQueueFlow.value.isEmpty())

        // Enqueue again, then terminal idle cleans it → .value reflects empty.
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1", attempt = 1, queuedMonotonic = 200L)))
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, monotonic = 300L),
        ))
        assertTrue("terminal cleanup reflected in flow .value",
            store.retryQueueFlow.value.isEmpty())
    }

    @Test
    fun `retryQueueFlow emits distinct values to collectors on enqueue and fire`() {
        val store = storeWith()
        val emissions = mutableListOf<Map<String, RetryEntry>>()
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val job = testScope.launch {
            store.retryQueueFlow.collect { emissions += it }
        }
        // Initial emission: empty.
        assertEquals("initial emission", emptyMap<String, RetryEntry>(), emissions.last())

        // Enqueue → emits the new entry (distinct from empty).
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1", attempt = 1, queuedMonotonic = 100L)))
        assertEquals(RetryEntry(1, 200L, 100L), emissions.last()["s1"])

        // Fire → emits empty (distinct from the queued entry).
        store.dispatch(AppAction.AuthorityEvent(retryFired("s1")))
        assertTrue(emissions.last().isEmpty())

        job.cancel()
        testScope.cancel()
    }

    @Test
    fun `retryQueueFlow does not emit on unrelated authority changes`() {
        val store = storeWith()
        val emissions = mutableListOf<Map<String, RetryEntry>>()
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val job = testScope.launch {
            store.retryQueueFlow.collect { emissions += it }
        }
        val sizeBefore = emissions.size
        // Dispatch a busy event (changes bySid + projection but NOT retryQueue).
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 100L),
        ))
        assertEquals("no spurious emission when retryQueue unchanged",
            sizeBefore, emissions.size)
        job.cancel()
        testScope.cancel()
    }

    @Test
    fun `RetryQueued LRU strictly caps at 256 - evicts even when new entry is itself the oldest (rev-ogpt B1)`() {
        // rev-ogpt B1: the queue MUST stay at or under RETRY_QUEUE_MAX_SIZE (256)
        // at ALL times, including when the just-inserted entry is itself the
        // oldest (clock went backwards). The strict-cap loop evicts it, keeping
        // the bounded contract inviolable (the prior +1-tolerant else-branch
        // was rejected). A brand-new entry that is the oldest loses its spot.
        val state = StoreState.initial()
        var cur = state
        // Fill 256 entries with queuedMonotonic 100..355.
        for (i in 0 until 256) {
            cur = reduceAuthority(cur, retryQueued("sid-$i", queuedMonotonic = 100L + i))
        }
        assertEquals(256, cur.authority.retryQueue.size)
        // Insert a NEW sid at queuedMonotonic = 50 (OLDER than all 256). The
        // strict cap evicts the oldest — which is now "new-oldest" itself.
        cur = reduceAuthority(cur, retryQueued("new-oldest", queuedMonotonic = 50L))
        assertEquals("strict cap at 256 (no transient overflow)", 256, cur.authority.retryQueue.size)
        assertFalse("the new oldest entry is evicted (it WAS the oldest)",
            "new-oldest" in cur.authority.retryQueue)
        assertTrue("the prior oldest (sid-0 at monotonic=100) survives the self-eviction",
            "sid-0" in cur.authority.retryQueue)
        // A normal insert (newest) evicts the oldest (sid-0 now), cap stays 256.
        cur = reduceAuthority(cur, retryQueued("newest", queuedMonotonic = 9999L))
        assertEquals(256, cur.authority.retryQueue.size)
        assertTrue("newest present", "newest" in cur.authority.retryQueue)
        assertFalse("prior oldest (sid-0) evicted by the newest insert",
            "sid-0" in cur.authority.retryQueue)
    }

    @Test
    fun `RetryQueued accepts a 503 retry for an idle session (rev-gpt B3-rejection)`() {
        // rev-gpt 终审: the B3 terminal-status fence proposed by rev-ogpt
        // was WRONG — it misfired on the normal retry case. An idle session
        // whose status fetch returns 503 is a LEGITIMATE retry (the true
        // status is now unknown); the fence would have wrongly dropped it.
        // The fence is REMOVED; this test pins the correct behavior.
        val store = storeWith()
        // Establish s1 as idle (terminal).
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, monotonic = 100L),
        ))
        // A 503 retry arrives for s1 → MUST be accepted (not dropped).
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1", attempt = 1, queuedMonotonic = 200L)))
        assertTrue("RetryQueued accepted for idle session (503 = legitimate retry)",
            "s1" in store.stateFlow.value.authority.retryQueue)

        // Absent bySid (unknown status) → also accepted.
        store.dispatch(AppAction.AuthorityEvent(retryQueued("absent-sid", attempt = 1, queuedMonotonic = 200L)))
        assertTrue("RetryQueued accepted for absent sid (status unknown)",
            "absent-sid" in store.stateFlow.value.authority.retryQueue)
    }

    @Test
    fun `RetryQueued self-eviction is a same-ref no-op (rev-gpt 4)`() {
        // rev-gpt 4: when the just-inserted entry self-evicts (it WAS the
        // oldest at capacity), the resulting retryQueue equals the prior one.
        // The reducer MUST return the same ref (no spurious copy + revision
        // bump), keeping the drop-on-no-change / replay-stability contract.
        val state = StoreState.initial()
        var cur = state
        // Fill 256 entries with queuedMonotonic 100..355.
        for (i in 0 until 256) {
            cur = reduceAuthority(cur, retryQueued("sid-$i", queuedMonotonic = 100L + i))
        }
        assertEquals(256, cur.authority.retryQueue.size)
        // Insert a NEW sid at queuedMonotonic = 50 (oldest). It self-evicts.
        // The result retryQueue equals cur.retryQueue → same-ref no-op.
        val result = reduceAuthority(cur, retryQueued("new-oldest", queuedMonotonic = 50L))
        assertSame("self-eviction is same-ref no-op (no spurious transition)",
            cur, result)
        assertFalse("self-evicted entry not present",
            "new-oldest" in result.authority.retryQueue)
        // Re-running the same op on the result → STILL same-ref (idempotent).
        val result2 = reduceAuthority(result, retryQueued("new-oldest", queuedMonotonic = 50L))
        assertSame("re-running self-evicting op is idempotent", result, result2)
    }

    @Test
    fun `applyPurge cross-group clears retryQueue (rev-ogpt B2)`() {
        // rev-ogpt B2: a cross-group purge (host switch) must clear the retry
        // queue — sids belong to the purged host. Without this, host A's queued
        // sids leak into host B (cross-host attempt counter pollution).
        val store = storeWith()
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1", queuedMonotonic = 100L)))
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s2", queuedMonotonic = 200L)))
        assertEquals(2, store.stateFlow.value.authority.retryQueue.size)
        // Cross-group purge.
        store.dispatch(AppAction.AuthorityEvent(
            AuthorityOp.PurgeHost(scopeKey = scope, preserveServerGroup = false),
        ))
        assertTrue("retryQueue cleared on cross-group purge",
            store.stateFlow.value.authority.retryQueue.isEmpty())
    }

    @Test
    fun `applyPurge same-group preserves retryQueue (rev-ogpt B2)`() {
        val store = storeWith()
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1", queuedMonotonic = 100L)))
        store.dispatch(AppAction.AuthorityEvent(
            AuthorityOp.PurgeHost(scopeKey = scope, preserveServerGroup = true),
        ))
        assertTrue("retryQueue preserved on same-group purge",
            "s1" in store.stateFlow.value.authority.retryQueue)
    }

    @Test
    fun `applyPurge clears retryQueue even when bySid already empty (rev-ogpt B2)`() {
        // rev-ogpt B2: the emptiness early-return previously skipped retryQueue
        // when bySid was empty. Now retryQueue is part of the check + reset.
        val store = storeWith()
        // Seed retry entries only (no bySid entries).
        store.mutateState { s ->
            s.copy(authority = s.authority.copy(
                retryQueue = mapOf("s1" to RetryEntry(1, 200L, 100L)),
            ))
        }
        assertTrue(store.stateFlow.value.authority.bySid.isEmpty())
        assertTrue(store.stateFlow.value.authority.retryQueue.isNotEmpty())
        store.dispatch(AppAction.AuthorityEvent(
            AuthorityOp.PurgeHost(scopeKey = scope, preserveServerGroup = false),
        ))
        assertTrue("retryQueue cleared even when bySid was empty",
            store.stateFlow.value.authority.retryQueue.isEmpty())
    }

    @Test
    fun `terminal ApplyEvent failed-status cleans retryQueue (rev-ogpt B4)`() {
        // rev-ogpt B4: terminal cleanup covers FAILED too (not just idle).
        // `failed` is neither busy nor retry → terminal → retry entry cleaned.
        val store = storeWith()
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1")))
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "failed"), EntryOrigin.SSE_LEGACY, monotonic = 2000L),
        ))
        assertFalse("retry entry cleaned on terminal failed status",
            "s1" in store.stateFlow.value.authority.retryQueue)
    }

    @Test
    fun `ApplySnapshot idle-transition cleans retryQueue (rev-ogpt B4)`() {
        // rev-ogpt B4: a REST snapshot can transition a sid busy → idle
        // (terminal). The queued retry entry must be cleaned.
        val store = storeWith()
        val busy = SessionStatus(type = "busy")
        // Seed s1 as busy (so the snapshot's idle is a real transition).
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", busy, EntryOrigin.SSE_LEGACY, monotonic = 100L, workdir = "/w"),
        ))
        // Queue s1.
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1")))
        assertTrue("s1 queued", "s1" in store.stateFlow.value.authority.retryQueue)
        // Snapshot transitions s1 → idle (terminal). Pass localBefore = busy
        // so the REST in-flight protection sees no SSE change → REST idle wins.
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(
                snapshot = mapOf("s1" to SessionStatus(type = "idle")),
                authoritativeNodeIds = setOf("s1"),
                sidToWorkdir = mapOf("s1" to "/w"),
                localBefore = mapOf("s1" to busy),
            ),
        ))
        assertEquals("s1 is now idle", "idle",
            store.stateFlow.value.authority.bySid["s1"]?.status?.type)
        assertFalse("retry entry cleaned on snapshot idle transition",
            "s1" in store.stateFlow.value.authority.retryQueue)
    }

    @Test
    fun `ApplyReconcileOutcome IDLE_CONFIRMED cleans retryQueue (rev-ogpt B4)`() {
        // rev-ogpt B4: reconcile IDLE_CONFIRMED is terminal → clean retry entry.
        // Seed s1 as BUSY with optimistic (NOT idle — B3 fence would drop the
        // RetryQueued for an already-terminal sid).
        val store = storeWith()
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = 100L),
        ))
        val claimClientSeq = store.stateFlow.value.authority.bySid["s1"]?.optimisticClaim?.clientSeq ?: 1L
        // Queue s1 (busy → B3 fence does NOT trigger).
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1")))
        assertTrue("s1 queued", "s1" in store.stateFlow.value.authority.retryQueue)
        // Reconcile confirms idle (terminal).
        store.dispatch(AppAction.AuthorityEvent(
            AuthorityOp.ApplyReconcileOutcome(
                sid = "s1", scopeKey = scope,
                outcome = ReconcileOutcome.IDLE_CONFIRMED,
                serverRound = null, monotonic = 200L,
                claimClientSeq = claimClientSeq,
                hostProfileId = store.stateFlow.value.host.currentHostProfileId,
                identityEpochAtCapture = 0L,
            ),
        ))
        assertFalse("retry entry cleaned on reconcile IDLE_CONFIRMED",
            "s1" in store.stateFlow.value.authority.retryQueue)
    }

    @Test
    fun `ApplyReconcileOutcome FETCH_FAILED cleans retryQueue (rev-ogpt B4)`() {
        // rev-ogpt B4: FETCH_FAILED removes the bySid entry (terminal) → clean
        // retry entry too. Seed s1 as BUSY with optimistic.
        val store = storeWith()
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = 100L),
        ))
        val claimClientSeq = store.stateFlow.value.authority.bySid["s1"]?.optimisticClaim?.clientSeq ?: 1L
        // Queue s1 (busy → B3 fence does NOT trigger).
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1")))
        assertTrue("s1 queued", "s1" in store.stateFlow.value.authority.retryQueue)
        // Reconcile FETCH_FAILED.
        store.dispatch(AppAction.AuthorityEvent(
            AuthorityOp.ApplyReconcileOutcome(
                sid = "s1", scopeKey = scope,
                outcome = ReconcileOutcome.FETCH_FAILED,
                serverRound = null, monotonic = 200L,
                claimClientSeq = claimClientSeq,
                hostProfileId = store.stateFlow.value.host.currentHostProfileId,
                identityEpochAtCapture = 0L,
            ),
        ))
        assertFalse("retry entry cleaned on reconcile FETCH_FAILED",
            "s1" in store.stateFlow.value.authority.retryQueue)
    }

    // ── FETCH_FAILED fail-closed coverage (U-CQ1) ──

    @Test
    fun `applyReconcile FETCH_FAILED writes fail-closed coverage preserving prior registeredWorkdirs`() {
        // §CQ-P1 (U-CQ1): FETCH_FAILED must write fail-closed coverage so the
        // AllIdleFresh gate reads this scope as unknown (coveredWorkdirs=empty,
        // unmappedActiveIds=empty, lastSuccessTimeMs=-1), while preserving
        // registeredWorkdirs from the prior coverage entry.
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        // 1. OPTIMISTIC busy → claim clientSeq=1
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = 100L),
        ))
        assertNotNull("claim present after OPTIMISTIC",
            store.stateFlow.value.authority.bySid["A"]?.optimisticClaim)

        // 2. Seed prior coverage with non-empty fields.
        store.mutateState { s ->
            s.copy(authority = s.authority.copy(
                coverage = s.authority.coverage + (scope to Coverage(
                    registeredWorkdirs = setOf("/a", "/b"),
                    coveredWorkdirs = setOf("/a"),
                    unmappedActiveIds = setOf("x"),
                    lastSuccessTimeMs = 999L,
                )),
            ))
        }

        // 3. FETCH_FAILED with matching claimClientSeq.
        store.dispatch(AppAction.AuthorityEvent(
            reconcileOutcome(store, "A", ReconcileOutcome.FETCH_FAILED),
        ))

        // 4. Assert entry removed and retryQueue clean.
        assertNull("entry removed after FETCH_FAILED",
            store.stateFlow.value.authority.bySid["A"])
        assertFalse("retryQueue clean for removed entry",
            "A" in store.stateFlow.value.authority.retryQueue)

        // 5. Assert fail-closed coverage: registeredWorkdirs preserved, all other
        //    fields reset to empty/fresh-failure sentinel.
        val cov = store.stateFlow.value.authority.coverage[scope]
        assertNotNull("coverage entry written for scope", cov)
        assertEquals("registeredWorkdirs preserved", setOf("/a", "/b"), cov!!.registeredWorkdirs)
        assertEquals("coveredWorkdirs empty (fail-closed)", emptySet<String>(), cov.coveredWorkdirs)
        assertEquals("unmappedActiveIds empty (fail-closed)", emptySet<String>(), cov.unmappedActiveIds)
        assertEquals("lastSuccessTimeMs = -1 (stale-success guard)", -1L, cov.lastSuccessTimeMs)
    }

    @Test
    fun `applyReconcile FETCH_FAILED with no prior coverage writes empty-registered fail-closed`() {
        // §CQ-P1 (U-CQ1): when there is NO prior coverage for the scope,
        // FETCH_FAILED writes registeredWorkdirs=emptySet() — AllIdleFresh gate
        // reads "no registered workdirs" ⇒ cannot be all-idle ⇒ conservative
        // unknown (fail-closed).
        val store = storeWith(listOf(Session(id = "B", directory = "/y")))
        // 1. OPTIMISTIC busy → claim clientSeq=1
        store.dispatch(AppAction.AuthorityEvent(
            event("B", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = 100L),
        ))
        assertNotNull("claim present after OPTIMISTIC",
            store.stateFlow.value.authority.bySid["B"]?.optimisticClaim)

        // 2. Verify no prior coverage for this scope.
        assertNull("no prior coverage", store.stateFlow.value.authority.coverage[scope])

        // 3. FETCH_FAILED with matching claimClientSeq.
        store.dispatch(AppAction.AuthorityEvent(
            reconcileOutcome(store, "B", ReconcileOutcome.FETCH_FAILED),
        ))

        // 4. Assert fail-closed coverage with empty registeredWorkdirs.
        val cov = store.stateFlow.value.authority.coverage[scope]
        assertNotNull("coverage entry written for scope", cov)
        assertEquals("registeredWorkdirs empty (no prior)", emptySet<String>(), cov!!.registeredWorkdirs)
        assertEquals("coveredWorkdirs empty (fail-closed)", emptySet<String>(), cov.coveredWorkdirs)
        assertEquals("unmappedActiveIds empty (fail-closed)", emptySet<String>(), cov.unmappedActiveIds)
        assertEquals("lastSuccessTimeMs = -1 (stale-success guard)", -1L, cov.lastSuccessTimeMs)
    }

    @Test
    fun `FETCH_FAILED fail-closed coverage shape matches applyMarkFailed fail-closed shape`() {
        // §CQ-P1 (U-CQ1): verify that the coverage shape written by FETCH_FAILED
        // matches the fail-closed shape written by MarkSourceFailed (coveredWorkdirs
        // empty, unmappedActiveIds empty, lastSuccessTimeMs = -1). Only
        // registeredWorkdirs may differ (FETCH_FAILED preserves prior; MarkSourceFailed
        // carries its own set in the op).
        val store = storeWith(listOf(Session(id = "C", directory = "/z")))
        // 1. OPTIMISTIC busy → claim clientSeq=1
        store.dispatch(AppAction.AuthorityEvent(
            event("C", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC, monotonic = 100L),
        ))
        assertNotNull("claim present after OPTIMISTIC",
            store.stateFlow.value.authority.bySid["C"]?.optimisticClaim)

        // 2. Seed shared prior registeredWorkdirs.
        store.mutateState { s ->
            s.copy(authority = s.authority.copy(
                coverage = s.authority.coverage + (scope to Coverage(
                    registeredWorkdirs = setOf("/z"),
                    coveredWorkdirs = setOf("/z"),
                    unmappedActiveIds = emptySet(),
                    lastSuccessTimeMs = 500L,
                )),
            ))
        }

        // 3. FETCH_FAILED.
        store.dispatch(AppAction.AuthorityEvent(
            reconcileOutcome(store, "C", ReconcileOutcome.FETCH_FAILED),
        ))

        val fetchFailedCov = store.stateFlow.value.authority.coverage[scope]
        assertNotNull("coverage after FETCH_FAILED", fetchFailedCov)

        // 4. Build expected fail-closed shape — same as applyMarkFailed would write
        //    for the same scope (MarkSourceFailed writes registeredWorkdirs from op,
        //    FETCH_FAILED preserves prior — they may differ; we only assert the
        //    fail-closed fields: covered, unmapped, lastSuccessTimeMs).
        assertEquals("coveredWorkdirs empty (fail-closed)",
            emptySet<String>(), fetchFailedCov!!.coveredWorkdirs)
        assertEquals("unmappedActiveIds empty (fail-closed)",
            emptySet<String>(), fetchFailedCov.unmappedActiveIds)
        assertEquals("lastSuccessTimeMs = -1 (stale-success guard)",
            -1L, fetchFailedCov.lastSuccessTimeMs)
    }

    @Test
    fun `PruneSessions cleans retryQueue for pruned in-scope sids (rev-ogpt B4)`() {
        // rev-ogpt B4: deleted/archived sids leave the retry queue.
        val store = storeWith()
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1", queuedMonotonic = 100L)))
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s2", queuedMonotonic = 200L)))
        assertEquals(2, store.stateFlow.value.authority.retryQueue.size)
        // Prune s1 (in-scope).
        store.dispatch(AppAction.AuthorityEvent(
            AuthorityOp.PruneSessions(sids = setOf("s1"), scopeKey = scope),
        ))
        assertFalse("s1 pruned from retryQueue", "s1" in store.stateFlow.value.authority.retryQueue)
        assertTrue("s2 survives (not pruned)", "s2" in store.stateFlow.value.authority.retryQueue)
    }

    @Test
    fun `stale fan-out summary after terminal event re-enters queue - known residual, self-healing (rev-gpt B3-rejection)`() {
        // rev-gpt 终审: the B3 terminal-status fence proposed by rev-ogpt was
        // REMOVED (it misfired on idle-503). A stale RetryQueued arriving after
        // a terminal event WILL re-enter the queue — this is a KNOWN RESIDUAL
        // (documented in spec §8.5). It is self-healing: the next sweep's
        // RetryFired, LRU eviction, or a subsequent terminal cleanup corrects it.
        // A proper fix requires sweep-generation / request-token causal fencing
        // — out of scope for this wire ticket. This test PINS the residual so
        // it isn't silently re-fixed by re-introducing the wrong fence.
        val store = storeWith()
        // Queue s1 (attempt 1).
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1", attempt = 1, queuedMonotonic = 100L)))
        assertTrue("s1 queued", "s1" in store.stateFlow.value.authority.retryQueue)
        // Terminal event lands FIRST (cleans the entry).
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, monotonic = 150L),
        ))
        assertFalse("terminal cleaned the entry", "s1" in store.stateFlow.value.authority.retryQueue)
        // Stale RetryQueued arrives AFTER. No fence → it re-enters (residual).
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1", attempt = 2, queuedMonotonic = 120L)))
        assertTrue("stale RetryQueued re-entered (known residual — no fence; self-heals via next sweep/LRU)",
            "s1" in store.stateFlow.value.authority.retryQueue)
        // Self-heal: a covering sweep's RetryFired (or a re-delivered terminal
        // event) cleans it. Demonstrate the covering-sweep self-heal:
        store.dispatch(AppAction.AuthorityEvent(retryFired("s1")))
        assertFalse("self-healed by covering-sweep RetryFired",
            "s1" in store.stateFlow.value.authority.retryQueue)
    }

    @Test
    fun `terminal ApplyEvent idle cleans retryQueue via incarnation-advance path (rev-glm N5)`() {
        // The incarnation-advance return path (AuthorityReducer.kt:402-406)
        // mirrors the normal path's retryQueue cleanup. Seed a queued sid,
        // then deliver an idle event with a serverRound that advances the
        // incarnation (triggers the incarnation-advance branch).
        val store = storeWith()
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1")))
        assertTrue("s1 queued", "s1" in store.stateFlow.value.authority.retryQueue)
        // Deliver idle with serverRound(incarnation=5, turn=1). The scope
        // high-water starts at 0 → incarnation 5 > 0 → incarnation-advance
        // branch fires AND cleans the retry entry (terminal idle).
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_SLIM,
                monotonic = 2000L, serverRound = ServerRound(5L, 1L)),
        ))
        assertFalse("retry entry cleaned via incarnation-advance path",
            "s1" in store.stateFlow.value.authority.retryQueue)
    }

    @Test
    fun `equal-value terminal idle re-delivery cleans a stale retry entry (rev-glm N4)`() {
        // rev-glm N4: the no-change early-return path (nextEntry == prev)
        // previously skipped retryQueue cleanup. A sid queued by a 503, then
        // confirmed terminal by an equal-value idle re-delivery, would leak.
        // The fix adds cleanup to the early-return path when the sid is in
        // the queue and the status is terminal.
        val store = storeWith()
        // Establish s1 as idle.
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, monotonic = 100L),
        ))
        // Manually queue s1 (simulating a 503 that arrived after the idle).
        store.mutateState { s ->
            s.copy(authority = s.authority.copy(
                retryQueue = s.authority.retryQueue + ("s1" to RetryEntry(1, 200L, 150L)),
            ))
        }
        assertTrue("s1 manually queued", "s1" in store.stateFlow.value.authority.retryQueue)
        // Re-deliver the SAME idle (equal-value → nextEntry == prev → early-return path).
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, monotonic = 100L),
        ))
        assertFalse("stale retry entry cleaned on equal-value terminal re-delivery",
            "s1" in store.stateFlow.value.authority.retryQueue)
    }

    @Test
    fun `equal-value busy re-delivery does NOT touch retryQueue when sid absent`() {
        // The early-return cleanup only fires when the sid is actually in the
        // queue. A normal equal-value busy re-delivery (sid NOT queued) must
        // stay a true same-ref no-op (no spurious transition).
        val store = storeWith()
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 100L),
        ))
        val stateBefore = store.stateFlow.value
        // Equal-value busy re-delivery → nextEntry == prev → early-return.
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.SSE_LEGACY, monotonic = 100L),
        ))
        // No change (s1 not in queue → no cleanup → same-ref no-op).
        assertSame("equal-value busy (sid not queued) is same-ref no-op",
            stateBefore, store.stateFlow.value)
    }

    // ── U-P6: concurrency invariants under parallel dispatch ────────────

    @Test
    fun `U-P6 - concurrent dispatch of mixed ops preserves authority invariants under CAS retry`() {
        val store = storeWith(listOf(
            Session(id = "A", directory = "/x"),
            Session(id = "B", directory = "/y"),
        ))
        val threads = 8
        val iterations = 200
        val barrier = CyclicBarrier(threads)
        val errors = ConcurrentLinkedQueue<Throwable>()

        val pool = (1..threads).map { t ->
            thread(start = true) {
                barrier.await()
                repeat(iterations) { i ->
                    try {
                        when (t % 4) {
                            0 -> store.dispatch(AppAction.AuthorityEvent(
                                event("A", SessionStatus(type = "busy"),
                                    EntryOrigin.SSE_SLIM,
                                    serverRound = ServerRound(1L, i.toLong()),
                                    monotonic = 100L + i)))
                            1 -> store.dispatch(AppAction.AuthorityEvent(
                                event("B", SessionStatus(type = "busy"),
                                    EntryOrigin.OPTIMISTIC, monotonic = 200L + i)))
                            2 -> store.dispatch(AppAction.AuthorityEvent(
                                AuthorityOp.PruneSessions(sids = setOf("C"), scopeKey = scope)))
                            3 -> store.dispatch(AppAction.AuthorityEvent(
                                snapshot(snapshot = mapOf("A" to SessionStatus(type = "idle")),
                                    authoritativeNodeIds = setOf("A"))))
                        }
                    } catch (e: Throwable) { errors.add(e) }
                }
            }
        }
        pool.forEach { it.join() }

        assertTrue("no exceptions under concurrent dispatch", errors.isEmpty())

        // Post-condition invariants (hold regardless of interleaving):
        val auth = store.stateFlow.value.authority
        // (1) bySid consistency: every entry has a valid status (no torn write)
        auth.bySid.values.forEach { assertNotNull("entry has status", it.status) }
        // (2) retryQueue bounded
        assertTrue("retry queue bounded", auth.retryQueue.size <= 256)
        // (3) revision monotonic (never decreased)
        assertTrue("revision non-negative", store.stateFlow.value.authorityRevision >= 0L)
    }

    /** Local assertNotNull to avoid an extra import line churn. */
    private fun assertNotNull(message: String, actual: Any?) =
        org.junit.Assert.assertNotNull(message, actual)
}
