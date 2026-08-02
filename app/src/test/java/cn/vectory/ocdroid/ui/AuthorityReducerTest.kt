package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.state.AuthorityOp
import cn.vectory.ocdroid.data.state.AuthorityState
import cn.vectory.ocdroid.data.state.Coverage
import cn.vectory.ocdroid.data.state.EntryOrigin
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // §需求12阶段3: under 需求12 profileId == profile.id always. The scope's
    // profileId dimension MUST equal PROFILE_ID so resolveScopeKey (which now
    // returns profile.id) matches the authority entries keyed by `scope`.
    private val scope = ScopeKey(profileId = PROFILE_ID, endpointFp = "ep")

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
        connectionTimeMs = monotonic,
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
    fun `ApplyEvent OPTIMISTIC sets entry`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        store.dispatch(AppAction.AuthorityEvent(event("A", SessionStatus(type = "busy"), EntryOrigin.OPTIMISTIC)))
        val entry = store.stateFlow.value.authority.bySid["A"]
        assertEquals(EntryOrigin.OPTIMISTIC, entry?.origin)
        assertNotNull("entry present", entry)
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
        store.dispatch(AppAction.HostStatePurged)
        val out = store.stateFlow.value
        assertEquals("authority fully reset", AuthorityState(), out.authority)
        assertTrue("sessionStatuses empty", out.sessionList.sessionStatuses.isEmpty())
    }

    // §需求12阶段3: the former `HostStatePurged same-group preserves
    // authority` test was removed — it asserted the deleted same-group
    // preserve behavior. Under 需求12 every purge fully resets authority.

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
    fun `BLK-2 - stale low-turn slim frame is DROPPED after REST snapshot preserves the baseline`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        // Establish a Tier-1 baseline at incarnation 5, turn 7.
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(5L, 7L), monotonic = 100L),
        ))
        assertEquals(ServerRound(5L, 7L), store.stateFlow.value.authority.bySid["A"]?.serverRound)
        // §Plan-A (P0-C): REST snapshot with null round fields PRESERVES the
        // baseline (lexMax preserves on null R). serverRoundHighWater also preserved.
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(snapshot = mapOf("A" to SessionStatus(type = "busy")),
                     authoritativeNodeIds = setOf("A")),
        ))
        assertEquals("REST preserves the live serverRound baseline (null-round REST)",
            ServerRound(5L, 7L), store.stateFlow.value.authority.bySid["A"]?.serverRound)
        assertEquals("BLK-2 high-water also preserved",
            ServerRound(5L, 7L), store.stateFlow.value.authority.bySid["A"]?.serverRoundHighWater)
        // BLK-2 window: a stale LOW-turn slim digest arrives late (cross-channel
        // reorder). Now DROPPED by the live lex guard (baseline preserved).
        val beforeStale = store.stateFlow.value
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(5L, 4L), monotonic = 300L),
        ))
        assertEquals("stale low-turn frame DROPPED — baseline preserved at (5,7)",
            ServerRound(5L, 7L), store.stateFlow.value.authority.bySid["A"]?.serverRound)
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
        // legacy_update updates updatedAtMs to 200, preserves baseline (5,7)
        val afterLegacy = store.stateFlow.value
        assertEquals(200L, store.stateFlow.value.authority.bySid["A"]?.updatedAtMs)
        // Equal-turn slim frame (5,7) with OLDER monotonic (50 < 200) → live lex guard
        // tie-break DROPS it (cmp==0 && connectionTimeMs < updatedAtMs)
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(5L, 7L), monotonic = 50L),
        ))
        assertSame("equal-turn with older monotonic is same-ref no-op",
            afterLegacy, store.stateFlow.value)
    }

    @Test
    fun `BLK-2 - a fresh higher-turn slim frame is ACCEPTED after the baseline is preserved via REST`() {
        val store = storeWith(listOf(Session(id = "A", directory = "/x")))
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(5L, 7L), monotonic = 100L),
        ))
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(snapshot = mapOf("A" to SessionStatus(type = "busy")),
                     authoritativeNodeIds = setOf("A")),
        ))
        // §Plan-A (P0-C): baseline PRESERVED by null-round REST (not cleared).
        assertEquals("baseline preserved after null-round REST",
            ServerRound(5L, 7L), store.stateFlow.value.authority.bySid["A"]?.serverRound)
        // Fresh higher turn (6,9 > 5,7 via incarnation) → ACCEPTED, high-water advances.
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(6L, 9L), monotonic = 300L),
        ))
        assertEquals("fresh higher turn accepted",
            ServerRound(6L, 9L), store.stateFlow.value.authority.bySid["A"]?.serverRound)
        assertEquals("high-water advanced to the fresh turn",
            ServerRound(6L, 9L), store.stateFlow.value.authority.bySid["A"]?.serverRoundHighWater)
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
        // A fresh frame advances the watermark to (6, 12) — use new incarnation to advance.
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(6L, 12L), monotonic = 300L),
        ))
        // Another null-round REST snapshot — baseline PRESERVED by lexMax (not cleared).
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(snapshot = mapOf("A" to SessionStatus(type = "busy")),
                     authoritativeNodeIds = setOf("A")),
        ))
        assertEquals("baseline preserved after null-round REST",
            ServerRound(6L, 12L), store.stateFlow.value.authority.bySid["A"]?.serverRound)
        assertEquals(ServerRound(6L, 12L),
            store.stateFlow.value.authority.bySid["A"]?.serverRoundHighWater)
        // A stale frame with a lower incarnation (5,9) — caught by B6 guard (inc 5 < 6).
        store.dispatch(AppAction.AuthorityEvent(
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(5L, 9L), monotonic = 400L),
        ))
        assertEquals("stale lower-incarnation frame DROPPED — baseline stays at (6,12)",
            ServerRound(6L, 12L), store.stateFlow.value.authority.bySid["A"]?.serverRound)
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
        // §Plan-A (P0-C): REST snapshot with null round fields PRESERVES the
        // baseline — B's serverRound stays at (6,2).
        store.dispatch(AppAction.AuthorityEvent(
            snapshot(snapshot = mapOf("B" to SessionStatus(type = "busy")),
                     authoritativeNodeIds = setOf("B")),
        ))
        assertEquals("REST preserves baseline at (6,2)", ServerRound(6L, 2L),
            store.stateFlow.value.authority.bySid["B"]?.serverRound)
        assertEquals(ServerRound(6L, 2L),
            store.stateFlow.value.authority.bySid["B"]?.serverRoundHighWater)
        // Stale same-incarnation (6) low turn (1 < 2) — DROPPED by the live lex
        // guard (baseline preserved), not by BLK-2.
        val beforeStale = store.stateFlow.value
        store.dispatch(AppAction.AuthorityEvent(
            event("B", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(6L, 1L), monotonic = 300L),
        ))
        assertEquals("stale low-turn DROPPED — baseline stays at (6,2)",
            ServerRound(6L, 2L), store.stateFlow.value.authority.bySid["B"]?.serverRound)
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
        val diffScope = ScopeKey(profileId = "other-grp", endpointFp = "other-ep")
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
        val diffScope = ScopeKey(profileId = "other-grp", endpointFp = "other-ep")
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
        val diffScope = ScopeKey(profileId = "other-grp", endpointFp = "other-ep")
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
        val diffScope = ScopeKey(profileId = "other-grp", endpointFp = "other-ep")
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
        val diffScope = ScopeKey(profileId = "other-grp", endpointFp = "other-ep")
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
                    origin = EntryOrigin.SSE_LEGACY,
                    updatedAtMs = 50L,
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

    // ── Test ④: B6 incarnation advance resets only the advancing scope's serverRound ──

    @Test
    fun `final-fix-4 incarnation advance resets only the advancing scopes serverRound not other scopes`() {
        val diffScope = ScopeKey(profileId = "other-grp", endpointFp = "other-ep")
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
                    origin = EntryOrigin.SSE_SLIM,
                    updatedAtMs = 100L,
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
        profileId = "grp",
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
        connectionTimeMs = monotonic,
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
        assertNotNull("entry present", entry)
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
            connectionTimeMs = 100L,
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
        val altScope = ScopeKey(profileId = "alt-grp", endpointFp = "alt-ep")
        val state = StoreState.initial().copy(identityEpoch = 5L)
        val op = AuthorityOp.ApplyEvent(
            sid = "s1",
            status = SessionStatus(type = "busy"),
            origin = EntryOrigin.SSE_LEGACY,
            capturedIdentity = capturedIdentity,
            identityEpochAtCapture = 5L,
            scopeKey = altScope, // derived from captured identity, NOT current host
            connectionTimeMs = 100L,
        )
        val result = reduceAuthority(state, op)
        val entry = result.authority.bySid["s1"]
        assertEquals("scopeKey on SessionEntry equals the op's scopeKey (= captured identity's scope)",
            altScope, entry?.scopeKey)
        assertEquals("alt-grp", entry?.scopeKey?.profileId)
        assertEquals("alt-ep", entry?.scopeKey?.endpointFp)
    }

    @Test
    fun `r4 scope-guard - in-flight merge does not migrate out-of-scope entry to current scope`() {
        val diffScope = ScopeKey(profileId = "other-grp", endpointFp = "other-ep")
        val store = storeWith(listOf(Session(id = "inScope", directory = "/w")))
        store.dispatch(AppAction.AuthorityEvent(
            event("inScope", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, workdir = "/w"),
        ))
        // Add out-of-scope entry (busy) via direct state manipulation.
        store.mutateState { s ->
            s.copy(authority = s.authority.copy(
                bySid = s.authority.bySid + ("outSid" to SessionEntry(
                    status = SessionStatus(type = "busy"),
                    serverRound = null,
                    origin = EntryOrigin.SSE_LEGACY,
                    updatedAtMs = 50L, workdir = "/other", scopeKey = diffScope,
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
        // Seed busy via SSE_SLIM with a serverRound.
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(1L, 5L), monotonic = 100L, workdir = "/w"),
        ))
        val before = store.stateFlow.value
        assertTrue("pendingErrorCheck initially empty",
            before.chat.pendingErrorCheck.isEmpty())

        // A stale idle with LOWER serverRound (1,3 < 1,5) → lex guard DROPs it.
        // The op is rejected by the guard, so the early return path fires and
        // pendingErrorCheck MUST NOT be modified.
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(1L, 3L), monotonic = 200L, workdir = "/w"),
        ))
        assertTrue("pendingErrorCheck still empty after lex-guard drop",
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
                connectionTimeMs = 100L + i * 2L,
            )
            val idleOp = AuthorityOp.ApplyEvent(
                sid = sid, status = SessionStatus(type = "idle"),
                origin = EntryOrigin.SSE_LEGACY, scopeKey = scope,
                connectionTimeMs = 101L + i * 2L,
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
        queuedAtMs: Long = 1000L,
        identityEpochAtCapture: Long = 0L,
    ) = AuthorityOp.RetryQueued(
        sid = sid,
        scopeKey = scope,
        attempt = attempt,
        backoffMs = backoffMs,
        queuedAtMs = queuedAtMs,
        identityEpochAtCapture = identityEpochAtCapture,
    )

    private fun retryFired(sid: String, monotonic: Long = 2000L, identityEpochAtCapture: Long = 0L) =
        AuthorityOp.RetryFired(sid = sid, scopeKey = scope,
            monotonic = monotonic, identityEpochAtCapture = identityEpochAtCapture)

    @Test
    fun `RetryQueued enqueues entry with correct attempt backoff and queuedAtMs`() {
        val store = storeWith()
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1", attempt = 1, backoffMs = 200L, queuedAtMs = 1000L)))
        val entry = store.stateFlow.value.authority.retryQueue["s1"]
        assertEquals(RetryEntry(attempt = 1, backoffMs = 200L, queuedAtMs = 1000L), entry)
    }

    @Test
    fun `RetryQueued re-queue with different attempt overwrites the entry`() {
        val store = storeWith()
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1", attempt = 1, backoffMs = 200L, queuedAtMs = 1000L)))
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1", attempt = 2, backoffMs = 400L, queuedAtMs = 2000L)))
        val entry = store.stateFlow.value.authority.retryQueue["s1"]
        assertEquals(RetryEntry(attempt = 2, backoffMs = 400L, queuedAtMs = 2000L), entry)
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
            cur = reduceAuthority(cur, retryQueued("sid-$i", queuedAtMs = i.toLong()))
        }
        assertEquals(256, cur.authority.retryQueue.size)
        // Insert one more (sid-256 at monotonic=256, newer than all). The
        // oldest (sid-0 at monotonic=0) MUST be evicted.
        cur = reduceAuthority(cur, retryQueued("sid-256", queuedAtMs = 256L))
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
            cur = reduceAuthority(cur, retryQueued("sid-$i", queuedAtMs = i.toLong()))
        }
        assertEquals(256, cur.authority.retryQueue.size)
        // Re-queue an EXISTING sid (sid-0) with a fresh attempt. The queue
        // size must NOT grow (overwrite, not insert) → no eviction needed.
        cur = reduceAuthority(cur, retryQueued("sid-0", attempt = 2, queuedAtMs = 9999L))
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
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1", attempt = 1, queuedAtMs = 100L)))
        assertEquals(RetryEntry(1, 200L, 100L), store.retryQueueFlow.value["s1"])

        // Fire → .value reflects the removal.
        store.dispatch(AppAction.AuthorityEvent(retryFired("s1")))
        assertTrue(store.retryQueueFlow.value.isEmpty())

        // Enqueue again, then terminal idle cleans it → .value reflects empty.
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1", attempt = 1, queuedAtMs = 200L)))
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
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1", attempt = 1, queuedAtMs = 100L)))
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
        // Fill 256 entries with queuedAtMs 100..355.
        for (i in 0 until 256) {
            cur = reduceAuthority(cur, retryQueued("sid-$i", queuedAtMs = 100L + i))
        }
        assertEquals(256, cur.authority.retryQueue.size)
        // Insert a NEW sid at queuedAtMs = 50 (OLDER than all 256). The
        // strict cap evicts the oldest — which is now "new-oldest" itself.
        cur = reduceAuthority(cur, retryQueued("new-oldest", queuedAtMs = 50L))
        assertEquals("strict cap at 256 (no transient overflow)", 256, cur.authority.retryQueue.size)
        assertFalse("the new oldest entry is evicted (it WAS the oldest)",
            "new-oldest" in cur.authority.retryQueue)
        assertTrue("the prior oldest (sid-0 at monotonic=100) survives the self-eviction",
            "sid-0" in cur.authority.retryQueue)
        // A normal insert (newest) evicts the oldest (sid-0 now), cap stays 256.
        cur = reduceAuthority(cur, retryQueued("newest", queuedAtMs = 9999L))
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
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1", attempt = 1, queuedAtMs = 200L)))
        assertTrue("RetryQueued accepted for idle session (503 = legitimate retry)",
            "s1" in store.stateFlow.value.authority.retryQueue)

        // Absent bySid (unknown status) → also accepted.
        store.dispatch(AppAction.AuthorityEvent(retryQueued("absent-sid", attempt = 1, queuedAtMs = 200L)))
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
        // Fill 256 entries with queuedAtMs 100..355.
        for (i in 0 until 256) {
            cur = reduceAuthority(cur, retryQueued("sid-$i", queuedAtMs = 100L + i))
        }
        assertEquals(256, cur.authority.retryQueue.size)
        // Insert a NEW sid at queuedAtMs = 50 (oldest). It self-evicts.
        // The result retryQueue equals cur.retryQueue → same-ref no-op.
        val result = reduceAuthority(cur, retryQueued("new-oldest", queuedAtMs = 50L))
        assertSame("self-eviction is same-ref no-op (no spurious transition)",
            cur, result)
        assertFalse("self-evicted entry not present",
            "new-oldest" in result.authority.retryQueue)
        // Re-running the same op on the result → STILL same-ref (idempotent).
        val result2 = reduceAuthority(result, retryQueued("new-oldest", queuedAtMs = 50L))
        assertSame("re-running self-evicting op is idempotent", result, result2)
    }

    @Test
    fun `applyPurge clears retryQueue (rev-ogpt B2)`() {
        // rev-ogpt B2 / §需求12阶段3: a purge (host switch) must clear the
        // retry queue — sids belong to the purged host. Without this, host A's
        // queued sids leak into host B (cross-host attempt counter pollution).
        // (The former `cross-group` qualifier is gone — under 需求12 every
        // purge is unconditional.)
        val store = storeWith()
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1", queuedAtMs = 100L)))
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s2", queuedAtMs = 200L)))
        assertEquals(2, store.stateFlow.value.authority.retryQueue.size)
        store.dispatch(AppAction.AuthorityEvent(
            AuthorityOp.PurgeHost(scopeKey = scope),
        ))
        assertTrue("retryQueue cleared on purge",
            store.stateFlow.value.authority.retryQueue.isEmpty())
    }

    // §需求12阶段3: the former `applyPurge same-group preserves retryQueue`
    // test was removed — the same-group preserve branch + `preserveServerGroup`
    // flag are dead under 需求12 (every purge is unconditional).

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
            AuthorityOp.PurgeHost(scopeKey = scope),
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
    fun `PruneSessions cleans retryQueue for pruned in-scope sids (rev-ogpt B4)`() {
        // rev-ogpt B4: deleted/archived sids leave the retry queue.
        val store = storeWith()
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1", queuedAtMs = 100L)))
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s2", queuedAtMs = 200L)))
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
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1", attempt = 1, queuedAtMs = 100L)))
        assertTrue("s1 queued", "s1" in store.stateFlow.value.authority.retryQueue)
        // Terminal event lands FIRST (cleans the entry).
        store.dispatch(AppAction.AuthorityEvent(
            event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_LEGACY, monotonic = 150L),
        ))
        assertFalse("terminal cleaned the entry", "s1" in store.stateFlow.value.authority.retryQueue)
        // Stale RetryQueued arrives AFTER. No fence → it re-enters (residual).
        store.dispatch(AppAction.AuthorityEvent(retryQueued("s1", attempt = 2, queuedAtMs = 120L)))
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

    // ── U-CQ5: RetryQueued/Fired epoch guard ──────────────────────────────

    @Test
    fun `U-CQ5 RetryQueued with stale identityEpoch is DROPPED`() {
        val store = storeWith()
        // Bump identityEpoch to 1.
        store.mutateHost { it.copy(currentHostProfileId = "new-host") }
        assertEquals("identityEpoch bumped to 1",
            1L, store.stateFlow.value.identityEpoch)

        // RetryQueued with identityEpochAtCapture=0L (stale, predates the bump).
        val before = store.stateFlow.value
        store.dispatch(AppAction.AuthorityEvent(
            retryQueued("s1", identityEpochAtCapture = 0L),
        ))
        assertSame("stale-epoch RetryQueued dropped (no-op)", before, store.stateFlow.value)
        assertFalse("s1 NOT in retryQueue", "s1" in store.stateFlow.value.authority.retryQueue)
    }

    @Test
    fun `U-CQ5 RetryFired with stale identityEpoch is DROPPED`() {
        val store = storeWith()
        // Seed a retry entry.
        store.dispatch(AppAction.AuthorityEvent(
            retryQueued("s1", identityEpochAtCapture = 0L),
        ))
        assertTrue("s1 queued", "s1" in store.stateFlow.value.authority.retryQueue)
        // Bump identityEpoch to 1.
        store.mutateHost { it.copy(currentHostProfileId = "another-host") }
        assertEquals(1L, store.stateFlow.value.identityEpoch)

        // RetryFired with identityEpochAtCapture=0L (stale) → DROP.
        val before = store.stateFlow.value
        store.dispatch(AppAction.AuthorityEvent(
            retryFired("s1", identityEpochAtCapture = 0L),
        ))
        assertSame("stale-epoch RetryFired dropped (no-op)", before, store.stateFlow.value)
        assertTrue("s1 still in retryQueue (fence protected)", "s1" in store.stateFlow.value.authority.retryQueue)
    }

    @Test
    fun `U-CQ5 RetryQueued backward-compat default 0L passes when identityEpoch is 0L`() {
        // Default 0L + state.identityEpoch=0L (initial state) → pass.
        val state = StoreState.initial()
        val result = reduceAuthority(state, retryQueued("s1"))
        assertTrue("s1 queued with default epoch guard", "s1" in result.authority.retryQueue)
    }

    @Test
    fun `U-CQ5 RetryFired backward-compat default 0L passes when identityEpoch is 0L`() {
        // Default 0L + state.identityEpoch=0L → pass.
        val state = StoreState.initial()
        val withQueue = reduceAuthority(state, retryQueued("s1"))
        val result = reduceAuthority(withQueue, retryFired("s1"))
        assertFalse("s1 removed from queue via RetryFired (guard passed)",
            "s1" in result.authority.retryQueue)
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
        // (4) §S3 (batch2-review): serverRoundHighWater monotonic guarantee
        val aHw = auth.bySid["A"]?.serverRoundHighWater
        assertTrue("A high-water monotonic", aHw == null || aHw.turn >= 0L)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // §Plan-A (P0-C) T1-T10 — serverRound through ApplySnapshot
    // ═══════════════════════════════════════════════════════════════════════

    private fun token(requestStartMs: Long = 100L, host: String? = PROFILE_ID) =
        RequestToken(hostProfileId = host, identityEpoch = 0L, requestStartMs = requestStartMs)

    /** T1 — sparse idle. Catches idle-fill fabricating a round, or dropping B. */
    @Test
    fun `T1 sparse idle — idle-filled node gets no round`() {
        val state = StoreState.initial().copy(identityEpoch = 0L)
        val op = AuthorityOp.ApplySnapshot(
            snapshot = mapOf("A" to SessionStatus(type = "busy", turnIncarnation = 1, turn = 1)),
            sidToWorkdir = mapOf("A" to "/x", "B" to "/x"),
            authoritativeNodeIds = setOf("A", "B"),
            registeredWorkdirs = emptySet(),
            coveredWorkdirs = emptySet(),
            unmappedActiveIds = emptySet(),
            partialFailureWorkdirs = emptySet(),
            lastSuccessTimeMs = 1000L,
            scopeKey = scope,
            requestToken = token(requestStartMs = 1000L),
            localBefore = emptyMap(),
        )
        val result = reduceAuthority(state, op)

        val a = result.authority.bySid["A"]!!
        assertEquals("A: busy", "busy", a.status.type)
        assertEquals("A: serverRound (1,1)", ServerRound(1, 1), a.serverRound)
        assertEquals("A: hw (1,1)", ServerRound(1, 1), a.serverRoundHighWater)
        assertEquals("A: origin REST", EntryOrigin.REST, a.origin)
        assertEquals("A: updatedAtMs", 1000L, a.updatedAtMs)

        val b = result.authority.bySid["B"]!!
        assertEquals("B: idle (idle-filled)", "idle", b.status.type)
        assertNull("B: serverRound null (no round for idle-fill)", b.serverRound)
        assertNull("B: hw null", b.serverRoundHighWater)
        assertEquals("B: origin REST", EntryOrigin.REST, b.origin)
        // Projection: A=busy, B=idle
        assertEquals("A:proj busy", SessionStatus(type = "busy"), result.sessionList.sessionStatuses["A"])
        assertEquals("B:proj idle", SessionStatus(type = "idle"), result.sessionList.sessionStatuses["B"])
    }

    /** T2 — retry unified. Catches treating retry as terminal or as idle. */
    @Test
    fun `T2 retry unified — retry status keeps queue entry`() {
        val state = StoreState.initial().copy(
            identityEpoch = 0L,
            authority = AuthorityState(retryQueue = mapOf("A" to RetryEntry(1, 200L, 500L))),
        )
        val op = AuthorityOp.ApplySnapshot(
            snapshot = mapOf("A" to SessionStatus(type = "retry", attempt = 2, turnIncarnation = 1, turn = 4)),
            sidToWorkdir = mapOf("A" to "/x"),
            authoritativeNodeIds = setOf("A"),
            registeredWorkdirs = emptySet(),
            coveredWorkdirs = emptySet(),
            unmappedActiveIds = emptySet(),
            partialFailureWorkdirs = emptySet(),
            lastSuccessTimeMs = 1000L,
            scopeKey = scope,
            requestToken = token(requestStartMs = 1000L),
            localBefore = emptyMap(),
        )
        val result = reduceAuthority(state, op)

        val entry = result.authority.bySid["A"]!!
        assertEquals("retry", entry.status.type)
        assertEquals(ServerRound(1, 4), entry.serverRound)
        assertEquals(ServerRound(1, 4), entry.serverRoundHighWater)
        // retryQueue still contains A (retry is non-terminal)
        assertTrue("retryQueue still contains A (non-terminal)", "A" in result.authority.retryQueue)
    }

    /** T3 — turn merge + strip. Catches storing rounds inside entry.status (projection churn). */
    @Test
    fun `T3 turn merge and strip — round fields not in entry status`() {
        val state = StoreState.initial().copy(identityEpoch = 0L)
        val op = AuthorityOp.ApplySnapshot(
            snapshot = mapOf("A" to SessionStatus(type = "busy", turnIncarnation = 1, turn = 7)),
            sidToWorkdir = mapOf("A" to "/x"),
            authoritativeNodeIds = setOf("A"),
            registeredWorkdirs = emptySet(),
            coveredWorkdirs = emptySet(),
            unmappedActiveIds = emptySet(),
            partialFailureWorkdirs = emptySet(),
            lastSuccessTimeMs = 1000L,
            scopeKey = scope,
            requestToken = token(requestStartMs = 1000L),
            localBefore = emptyMap(),
        )
        val result = reduceAuthority(state, op)
        val entry = result.authority.bySid["A"]!!

        assertEquals(ServerRound(1, 7), entry.serverRound)
        assertEquals(ServerRound(1, 7), entry.serverRoundHighWater)
        // entry.status must have turn/turnIncarnation stripped
        assertEquals("busy", entry.status.type)
        assertNull("status.turnIncarnation stripped", entry.status.turnIncarnation)
        assertNull("status.turn stripped", entry.status.turn)
        // Projection must also be round-free
        assertEquals(SessionStatus(type = "busy"), result.sessionList.sessionStatuses["A"])
    }

    /** T4 — concurrent bump (§3.3撕裂 tolerated). Catches fencing "incoherent" R>effBase. */
    @Test
    fun `T4 concurrent bump — REST idle with higher turn applied (no fence on R greater)`() {
        val prior = SessionEntry(
            status = SessionStatus(type = "busy"),
            serverRound = ServerRound(1, 5),
            origin = EntryOrigin.SSE_SLIM,
            updatedAtMs = 1000L,
            workdir = "/x",
            scopeKey = scope,
            serverRoundHighWater = ServerRound(1, 5),
        )
        val state = StoreState.initial().copy(
            identityEpoch = 0L,
            authority = AuthorityState(bySid = mapOf("A" to prior)),
        )
        val localBefore = mapOf("A" to SessionStatus(type = "busy"))
        val op = AuthorityOp.ApplySnapshot(
            snapshot = mapOf("A" to SessionStatus(type = "idle", turnIncarnation = 1, turn = 6)),
            sidToWorkdir = mapOf("A" to "/x"),
            authoritativeNodeIds = setOf("A"),
            registeredWorkdirs = emptySet(),
            coveredWorkdirs = emptySet(),
            unmappedActiveIds = emptySet(),
            partialFailureWorkdirs = emptySet(),
            lastSuccessTimeMs = 2000L,
            scopeKey = scope,
            requestToken = token(requestStartMs = 2000L),
            localBefore = localBefore,
        )
        val result = reduceAuthority(state, op)
        val entry = result.authority.bySid["A"]!!
        // R (1,6) > effBase (1,5) → applied
        assertEquals("idle applied (R > effBase)", "idle", entry.status.type)
        assertEquals(ServerRound(1, 6), entry.serverRound)
        assertEquals(ServerRound(1, 6), entry.serverRoundHighWater)
        assertEquals(2000L, entry.updatedAtMs)
    }

    /**
     * §review-blocker-#3 (rev-gpt P0-C partial-close): inFlightWin tear-test.
     *
     * Counterexample to the old buggy branch, which always stamped
     * origin=REST + updatedAtMs=requestStartMs even when SSE won in-flight.
     *
     * Setup (REST 1000ms + SSE 2000ms tear):
     *   - REST request captured localBefore["A"] = busy at requestStartMs=1000.
     *   - SSE landed mid-REST at 2000ms: store now has prior.status=idle,
     *     prior.origin=SSE_SLIM, prior.updatedAtMs=2000, prior.round=(1,7).
     *   - REST response returns at 2000ms with idle, round R=(1,7) (same).
     *   - currentProjection["A"] = idle (from prior); localBefore["A"] = busy
     *     → DIFFERS → inFlightWin = TRUE.
     *
     * Without the fix: entry stamps origin=REST, updatedAtMs=1000 (regressed
     * from 2000). A subsequent late same-round SSE at 1500ms then passes the
     * equal-round tie-break (1500 < 1000 is FALSE) and corrupts state.
     *
     * With the fix: prior.origin + prior.updatedAtMs are preserved. A late SSE
     * at 1500ms is correctly fenced (1500 < 2000 is TRUE).
     */
    @Test
    fun `T4-C1 inFlightWin tear — SSE causal metadata preserved (no REST regression)`() {
        // prior reflects the SSE win: idle, SSE origin, updatedAtMs=2000, round (1,7)
        val prior = SessionEntry(
            status = SessionStatus(type = "idle"),
            serverRound = ServerRound(1, 7),
            origin = EntryOrigin.SSE_SLIM,
            updatedAtMs = 2000L,
            workdir = "/x",
            scopeKey = scope,
            serverRoundHighWater = ServerRound(1, 7),
        )
        val state = StoreState.initial().copy(
            identityEpoch = 0L,
            authority = AuthorityState(bySid = mapOf("A" to prior)),
        )
        // localBefore is what REST captured at request start (stale — before SSE landed)
        val localBefore = mapOf("A" to SessionStatus(type = "busy"))
        // REST returns: idle, round (1,7) [same as SSE], requestStartMs=1000
        val op = AuthorityOp.ApplySnapshot(
            snapshot = mapOf("A" to SessionStatus(type = "idle", turnIncarnation = 1, turn = 7)),
            sidToWorkdir = mapOf("A" to "/x"),
            authoritativeNodeIds = setOf("A"),
            registeredWorkdirs = emptySet(),
            coveredWorkdirs = emptySet(),
            unmappedActiveIds = emptySet(),
            partialFailureWorkdirs = emptySet(),
            lastSuccessTimeMs = 2000L,
            scopeKey = scope,
            requestToken = token(requestStartMs = 1000L),
            localBefore = localBefore,
        )
        val result = reduceAuthority(state, op)
        val entry = result.authority.bySid["A"]!!

        // status: idle (both SSE and REST agree)
        assertEquals("idle preserved", "idle", entry.status.type)
        // round: (1,7) — same round, lex-max fold
        assertEquals("round preserved at (1,7)", ServerRound(1, 7), entry.serverRound)
        assertEquals("hw at (1,7)", ServerRound(1, 7), entry.serverRoundHighWater)
        // METADATA PRESERVED (the fix): origin stays SSE_SLIM, NOT regressed to REST
        assertEquals("origin preserved as SSE_SLIM (not regressed to REST)",
            EntryOrigin.SSE_SLIM, entry.origin)
        // METADATA PRESERVED: updatedAtMs stays 2000, NOT regressed to requestStartMs 1000
        assertEquals("updatedAtMs preserved at 2000 (not regressed to REST requestStart 1000)",
            2000L, entry.updatedAtMs)
    }

    /**
     * §review-blocker-#3 follow-up: the late same-round SSE at 1500ms must be
     * fenced because preserved updatedAtMs=2000 > 1500. Demonstrates the fix
     * closes the equal-round tie-break corruption that the old regression
     * (updatedAtMs=1000) would have allowed through.
     *
     * §review-blocker-#6 (P0-C meta-only) revision: the previous version of
     * this test submitted an ApplySnapshot (REST) as "the late frame" — but
     * the corruption vector is a late SSE ApplyEvent hitting applyEvent:247's
     * equal-round tie-break. This rewrite uses ApplyEvent (the real op type
     * for a late SSE frame) seeded from the post-tear state T4-C1 produces
     * (idle@(1,7)@2000, SSE_SLIM origin — the metadata-preservation outcome).
     *
     * With preserved updatedAtMs=2000, a late SSE (1,7) at 1500ms hits
     * applyEvent:247 tie-break `1500 < 2000` → TRUE → fenced → entry stays
     * at the fresher SSE state (reduceAuthority returns same ref).
     */
    @Test
    fun `T4-C2 late same-round SSE fenced after inFlightWin tear (tie-break correctness)`() {
        // Post-tear state (the outcome T4-C1 asserts): idle@(1,7)@2000, SSE_SLIM.
        // Seeded directly so this test is robust to C1's fence-direction details
        // (C1 with prior=busy fences verbatim via :469; the meaningful late-SSE
        // assertion is the applyEvent:247 tie-break against preserved 2000).
        val afterTear = StoreState.initial().copy(
            identityEpoch = 0L,
            authority = AuthorityState(bySid = mapOf("A" to SessionEntry(
                status = SessionStatus(type = "idle"),
                serverRound = ServerRound(1, 7),
                origin = EntryOrigin.SSE_SLIM,
                updatedAtMs = 2000L,
                workdir = "/x",
                scopeKey = scope,
                serverRoundHighWater = ServerRound(1, 7),
            ))),
        )
        // The LATE SSE frame (1,7) at 1500ms — an ApplyEvent, the real op type
        // for an SSE frame. Same round as the live baseline (1,7) → cmp==0
        // → applyEvent:247 equal-round tie-break fires.
        val lateSse = event(
            sid = "A",
            status = SessionStatus(type = "idle"),
            origin = EntryOrigin.SSE_SLIM,
            monotonic = 1500L,  // connectionTimeMs — earlier than the preserved 2000
            serverRound = ServerRound(1, 7),
        )
        val result = reduceAuthority(afterTear, lateSse)
        val entry = result.authority.bySid["A"]!!

        // equal-round tie-break: connectionTimeMs(1500) < prev.updatedAtMs(2000)
        // → TRUE → DROP (applyEvent returns cur, same ref). Entry stays verbatim.
        assertSame("late same-round SSE fenced (no transition, same ref)",
            afterTear, result)
        assertEquals("origin still SSE_SLIM", EntryOrigin.SSE_SLIM, entry.origin)
        assertEquals("updatedAtMs still 2000 (late SSE did not regress)", 2000L, entry.updatedAtMs)
        assertEquals("round still (1,7)", ServerRound(1, 7), entry.serverRound)
    }

    /**
     * §review-blocker-#6 (P0-C meta-only): the actual repro of the new blocker.
     *
     * Trigger: REST in-flight window (requestStartMs=1000) during which an SSE
     * lands that advances ONLY round/time WITHOUT changing the status VALUE
     * (busy→busy at a newer (incarnation,turn) + later connectionTimeMs=2000).
     * Then the REST response returns with a NULL round (R==null — the legitimate
     * null-round REST fallback in OpenCodeRepository:1125-1144, e.g. legacy /
     * unwired-registry / bad-shape snapshot) so the :467-469 lex-fence is skipped.
     *
     * PRE-fix (#3 alone): currentProjection stores round-stripped status, so
     * localBefore["A"]=busy == currentProjection["A"]=busy → inFlightWin=false
     * → else branch stamps updatedAtMs = requestStartMs(1000), REGRESSING the
     * SSE's 2000. A subsequent same-round (1,7) late SSE at 1500 then passes
     * applyEvent:247 (`1500 < 1000` is FALSE) → stale frame accepted → #3
     * blocker resurrected under a meta-only trigger.
     *
     * POST-fix (#6 Plan-B timestamp arm): prior.updatedAtMs(2000) >
     * requestStartMs(1000) → inFlightWin=TRUE → else branch preserves
     * prior.origin/updatedAtMs. The regression is closed; the chained late SSE
     * is correctly fenced.
     *
     * This test CHAINS the meta-only tear into a late SSE (like T4-C2 chains
     * the status-change tear), proving the end-to-end #6 vector is closed.
     */
    @Test
    fun `T4-C3 meta-only in-flight SSE + null-round REST preserves updatedAtMs (no regression)`() {
        // Live baseline: busy at (1,5), origin SSE_SLIM, updatedAtMs=900 (before
        // the REST request started at 1000).
        val baseline = SessionEntry(
            status = SessionStatus(type = "busy"),
            serverRound = ServerRound(1, 5),
            origin = EntryOrigin.SSE_SLIM,
            updatedAtMs = 900L,
            workdir = "/x",
            scopeKey = scope,
            serverRoundHighWater = ServerRound(1, 5),
        )
        val state0 = StoreState.initial().copy(
            identityEpoch = 0L,
            authority = AuthorityState(bySid = mapOf("A" to baseline)),
        )
        // REST request STARTS at 1000ms — captures localBefore["A"]=busy
        // (round-stripped projection; equals the live status).
        // The REST round-trip is in-flight. Meanwhile an SSE lands at 2000ms
        // advancing ONLY round/time: busy→busy at (1,7), connectionTimeMs=2000.
        val sseInFlight = event(
            sid = "A",
            status = SessionStatus(type = "busy"),  // SAME value — meta-only
            origin = EntryOrigin.SSE_SLIM,
            monotonic = 2000L,
            serverRound = ServerRound(1, 7),  // round advanced (1,5)→(1,7)
        )
        val stateAfterSse = reduceAuthority(state0, sseInFlight)
        val entryAfterSse = stateAfterSse.authority.bySid["A"]!!
        // Sanity: the in-flight SSE applied (round advanced, updatedAtMs=2000).
        assertEquals("in-flight SSE advanced round to (1,7)",
            ServerRound(1, 7), entryAfterSse.serverRound)
        assertEquals("in-flight SSE stamped updatedAtMs=2000",
            2000L, entryAfterSse.updatedAtMs)
        assertEquals("in-flight SSE left status busy (meta-only)", "busy", entryAfterSse.status.type)

        // REST response returns NOW with a NULL round (R==null — legacy /
        // bad-shape snapshot), snapshot says busy, requestStartMs=1000.
        // localBefore["A"]=busy == currentProjection["A"]=busy (status arm
        // alone would read "no tear"). The #6 timestamp arm must fire.
        val restOp = AuthorityOp.ApplySnapshot(
            snapshot = mapOf("A" to SessionStatus(type = "busy")),  // no turn fields → R=null
            sidToWorkdir = mapOf("A" to "/x"),
            authoritativeNodeIds = setOf("A"),
            registeredWorkdirs = emptySet(),
            coveredWorkdirs = emptySet(),
            unmappedActiveIds = emptySet(),
            partialFailureWorkdirs = emptySet(),
            lastSuccessTimeMs = 2100L,
            scopeKey = scope,
            requestToken = token(requestStartMs = 1000L),
            localBefore = mapOf("A" to SessionStatus(type = "busy")),  // == currentProjection
        )
        val stateAfterRest = reduceAuthority(stateAfterSse, restOp)
        val entryAfterRest = stateAfterRest.authority.bySid["A"]!!

        // #6 fix: inFlightWin=TRUE via timestamp arm (prior.updatedAtMs=2000 >
        // requestStartMs=1000) → metadata PRESERVED, NOT regressed to 1000.
        assertEquals("origin preserved as SSE_SLIM (not regressed to REST)",
            EntryOrigin.SSE_SLIM, entryAfterRest.origin)
        assertEquals("updatedAtMs preserved at 2000 (not regressed to REST requestStart 1000)",
            2000L, entryAfterRest.updatedAtMs)
        // Round: lexMaxNull(live0=(1,7), R=null) = (1,7) — SSE round survives.
        assertEquals("serverRound preserved at (1,7) (null-R REST does not clear)",
            ServerRound(1, 7), entryAfterRest.serverRound)

        // CHAINED: a late same-round SSE (1,7) at 1500ms now hits applyEvent:247.
        // With preserved updatedAtMs=2000: `1500 < 2000` → TRUE → FENCED.
        val lateSse = event(
            sid = "A",
            status = SessionStatus(type = "idle"),  // tries to flip to idle
            origin = EntryOrigin.SSE_SLIM,
            monotonic = 1500L,
            serverRound = ServerRound(1, 7),
        )
        val stateAfterLate = reduceAuthority(stateAfterRest, lateSse)
        val entryAfterLate = stateAfterLate.authority.bySid["A"]!!

        // Late SSE fenced: entry stays at the preserved SSE state.
        assertEquals("late same-round SSE fenced — status stays busy (not flipped to idle)",
            "busy", entryAfterLate.status.type)
        assertEquals("late SSE did not regress updatedAtMs", 2000L, entryAfterLate.updatedAtMs)
        assertEquals("origin still SSE_SLIM", EntryOrigin.SSE_SLIM, entryAfterLate.origin)
    }

    /**
     * §review-blocker-#7 (P0-C status-merge sync): the actual r3 tear repro.
     *
     * T4-C3 covered the meta-only case where REST and SSE AGREE on status
     * (both busy) → mergeStatusSnapshotInFlight couldn't tear. The blind spot:
     * REST returning a DIFFERING status. Chain:
     *   1. busy@(1,5)@900 (SSE_SLIM)
     *   2. in-flight meta-only SSE: busy→busy@(1,7)@2000 (status value unchanged;
     *      round+time advanced). currentProjection["A"]=busy (round-stripped).
     *   3. REST returns idle (DIFFERENT), null round (R==null legacy fallback),
     *      requestStartMs=1000. localBefore["A"]=busy == currentProjection["A"]=busy.
     *
     * PRE-fix: mergeStatusSnapshotInFlight status-diff is FALSE → keeps REST idle;
     * the :501 timestamp arm fires (2000>1000, R==null) → inFlightWin=TRUE → loop
     * writes status=idle(REST) + origin/updatedAtMs(2000)/round(1,7)(SSE) = TORN
     * entry. Then a late equal-round SSE (1,7)@1500 is fenced by applyEvent:247
     * (1500<2000), freezing REST's wrong idle as "SSE latest".
     *
     * POST-fix: the timestamp arm now also flags "A" in inFlightWinSids, so the
     * merge takes currentProjection["A"]=busy (SSE wins) → status stays busy,
     * single-source SSE entry. The late SSE is still fenced (1500<2000) but the
     * frozen value is the correct busy, not REST's idle.
     */
    @Test
    fun `T4-C4 meta-only SSE + differing-status null-R REST does not tear status from SSE meta`() {
        // 1. Live baseline: busy@(1,5)@900, SSE_SLIM.
        val baseline = SessionEntry(
            status = SessionStatus(type = "busy"),
            serverRound = ServerRound(1, 5),
            origin = EntryOrigin.SSE_SLIM,
            updatedAtMs = 900L,
            workdir = "/x",
            scopeKey = scope,
            serverRoundHighWater = ServerRound(1, 5),
        )
        val state0 = StoreState.initial().copy(
            identityEpoch = 0L,
            authority = AuthorityState(bySid = mapOf("A" to baseline)),
        )
        // 2. In-flight meta-only SSE: busy→busy@(1,7)@2000 (status value unchanged).
        val sseInFlight = event(
            sid = "A",
            status = SessionStatus(type = "busy"),
            origin = EntryOrigin.SSE_SLIM,
            monotonic = 2000L,
            serverRound = ServerRound(1, 7),
        )
        val stateAfterSse = reduceAuthority(state0, sseInFlight)
        val entryAfterSse = stateAfterSse.authority.bySid["A"]!!
        assertEquals("meta-only SSE advanced round to (1,7)",
            ServerRound(1, 7), entryAfterSse.serverRound)
        assertEquals("meta-only SSE stamped updatedAtMs=2000", 2000L, entryAfterSse.updatedAtMs)
        assertEquals("meta-only SSE left status busy", "busy", entryAfterSse.status.type)

        // 3. REST returns IDLE (DIFFERING) with a NULL round, requestStartMs=1000.
        // localBefore["A"]=busy == currentProjection["A"]=busy → status arm alone
        // would read "no tear". The #7 fix must flag "A" in inFlightWinSids via
        // the timestamp arm (prior.updatedAtMs=2000 > requestStartMs=1000, R==null)
        // so the merge takes SSE's busy instead of REST's idle.
        val restOp = AuthorityOp.ApplySnapshot(
            snapshot = mapOf("A" to SessionStatus(type = "idle")),  // no turn fields → R=null
            sidToWorkdir = mapOf("A" to "/x"),
            authoritativeNodeIds = setOf("A"),
            registeredWorkdirs = emptySet(),
            coveredWorkdirs = emptySet(),
            unmappedActiveIds = emptySet(),
            partialFailureWorkdirs = emptySet(),
            lastSuccessTimeMs = 2100L,
            scopeKey = scope,
            requestToken = token(requestStartMs = 1000L),
            localBefore = mapOf("A" to SessionStatus(type = "busy")),  // == currentProjection
        )
        val stateAfterRest = reduceAuthority(stateAfterSse, restOp)
        val entryAfterRest = stateAfterRest.authority.bySid["A"]!!

        // PRIMARY assertion — this is the tear fix. Pre-fix this was REST's idle.
        assertEquals("status stays busy (SSE wins via #7 timestamp arm, not REST idle)",
            "busy", entryAfterRest.status.type)
        // Anti-tear triple — single-source SSE entry, no cross-source mix.
        assertEquals("origin preserved as SSE_SLIM (not REST)",
            EntryOrigin.SSE_SLIM, entryAfterRest.origin)
        assertEquals("updatedAtMs preserved at 2000 (not regressed to REST requestStart 1000)",
            2000L, entryAfterRest.updatedAtMs)
        assertEquals("serverRound preserved at (1,7) (null-R REST does not clear)",
            ServerRound(1, 7), entryAfterRest.serverRound)
        assertEquals("serverRoundHighWater preserved at (1,7)",
            ServerRound(1, 7), entryAfterRest.serverRoundHighWater)

        // CHAINED late equal-round SSE (1,7)@1500 — fenced by applyEvent:247
        // (1500<2000). Entry stays at the correct SSE busy (NOT REST idle).
        val lateSse = event(
            sid = "A",
            status = SessionStatus(type = "idle"),  // tries to flip — must be rejected
            origin = EntryOrigin.SSE_SLIM,
            monotonic = 1500L,
            serverRound = ServerRound(1, 7),
        )
        val stateAfterLate = reduceAuthority(stateAfterRest, lateSse)
        val entryAfterLate = stateAfterLate.authority.bySid["A"]!!
        assertEquals("late same-round SSE fenced — status stays busy",
            "busy", entryAfterLate.status.type)
        assertEquals("late SSE did not regress updatedAtMs", 2000L, entryAfterLate.updatedAtMs)
        assertEquals("origin still SSE_SLIM", EntryOrigin.SSE_SLIM, entryAfterLate.origin)
    }

    /**
     * §review-blocker-#6 negative control: the timestamp arm must NOT fire on a
     * pure REST path (no in-flight SSE) — else it would over-protect and break
     * the normal REST stamp. Entry idle at updatedAtMs=500, REST start 1000,
     * snapshot says busy → assert origin=REST + updatedAtMs=1000 (arm correctly
     * inert because prior.updatedAtMs(500) is NOT > requestStartMs(1000)).
     */
    @Test
    fun `T4-C5 pure REST path — timestamp arm inert, REST stamps normally`() {
        val prior = SessionEntry(
            status = SessionStatus(type = "idle"),
            serverRound = null,
            origin = EntryOrigin.REST,
            updatedAtMs = 500L,
            workdir = "/x",
            scopeKey = scope,
            serverRoundHighWater = null,
        )
        val state = StoreState.initial().copy(
            identityEpoch = 0L,
            authority = AuthorityState(bySid = mapOf("A" to prior)),
        )
        val op = AuthorityOp.ApplySnapshot(
            snapshot = mapOf("A" to SessionStatus(type = "busy")),
            sidToWorkdir = mapOf("A" to "/x"),
            authoritativeNodeIds = setOf("A"),
            registeredWorkdirs = emptySet(),
            coveredWorkdirs = emptySet(),
            unmappedActiveIds = emptySet(),
            partialFailureWorkdirs = emptySet(),
            lastSuccessTimeMs = 1000L,
            scopeKey = scope,
            requestToken = token(requestStartMs = 1000L),
            localBefore = mapOf("A" to SessionStatus(type = "idle")),
        )
        val result = reduceAuthority(state, op)
        val entry = result.authority.bySid["A"]!!
        // Pure REST: status arm reads localBefore(idle) != projection(busy) →
        // inFlightWin=TRUE via the STATUS arm (not the timestamp arm). REST
        // stamps normally because the status changed. The timestamp arm
        // (500 > 1000 = false) is inert — confirmed by updatedAtMs=1000.
        assertEquals("status flipped to busy", "busy", entry.status.type)
        assertEquals("origin REST (normal REST stamp)", EntryOrigin.REST, entry.origin)
        assertEquals("updatedAtMs=1000 (REST requestStartMs, not preserved 500)",
            1000L, entry.updatedAtMs)
    }

    /**
     * §review-blocker-#7 (P0-C status-merge sync) edge case (oracle #1): an id
     * present in currentProjection with a fresh prior but ABSENT from the REST
     * snapshot. Pre-fix it was silently DROPPED (status-diff false → merge never
     * added it → step 6b iterated merged). Post-fix the timestamp arm flags it
     * in inFlightWinSids → merge retains it with SSE status+meta. This is the
     * correct causal outcome (a fresher in-flight SSE update wins over a REST
     * snapshot that omitted the id) and aligns meta-only with the existing
     * status-diff behavior on that same path.
     */
    @Test
    fun `T4-C6 meta-only SSE then null-R REST that OMITS the sid retains SSE entry`() {
        // 1. busy@(1,5)@900 SSE_SLIM.
        val baseline = SessionEntry(
            status = SessionStatus(type = "busy"),
            serverRound = ServerRound(1, 5),
            origin = EntryOrigin.SSE_SLIM,
            updatedAtMs = 900L,
            workdir = "/x",
            scopeKey = scope,
            serverRoundHighWater = ServerRound(1, 5),
        )
        val state0 = StoreState.initial().copy(
            identityEpoch = 0L,
            authority = AuthorityState(bySid = mapOf("A" to baseline)),
        )
        // 2. In-flight meta-only SSE: busy→busy@(1,7)@2000.
        val sseInFlight = event(
            sid = "A",
            status = SessionStatus(type = "busy"),
            origin = EntryOrigin.SSE_SLIM,
            monotonic = 2000L,
            serverRound = ServerRound(1, 7),
        )
        val stateAfterSse = reduceAuthority(state0, sseInFlight)
        // 3. REST snapshot OMITS "A" entirely; requestStartMs=1000 < 2000.
        // Timestamp arm fires (R==null because op.snapshot["A"]==null) → "A" in
        // inFlightWinSids → merge retains currentProjection["A"]=busy.
        val restOp = AuthorityOp.ApplySnapshot(
            snapshot = emptyMap(),  // "A" absent
            sidToWorkdir = mapOf("A" to "/x"),
            authoritativeNodeIds = emptySet(),
            registeredWorkdirs = emptySet(),
            coveredWorkdirs = emptySet(),
            unmappedActiveIds = emptySet(),
            partialFailureWorkdirs = emptySet(),
            lastSuccessTimeMs = 2100L,
            scopeKey = scope,
            requestToken = token(requestStartMs = 1000L),
            localBefore = mapOf("A" to SessionStatus(type = "busy")),
        )
        val stateAfterRest = reduceAuthority(stateAfterSse, restOp)
        val entryAfterRest = stateAfterRest.authority.bySid["A"]
        assertNotNull("REST-absent sid retained via #7 timestamp arm (not dropped)", entryAfterRest)
        assertEquals("retained status is SSE busy", "busy", entryAfterRest!!.status.type)
        assertEquals("retained origin SSE_SLIM", EntryOrigin.SSE_SLIM, entryAfterRest.origin)
        assertEquals("retained updatedAtMs=2000", 2000L, entryAfterRest.updatedAtMs)
        assertEquals("retained serverRound (1,7)", ServerRound(1, 7), entryAfterRest.serverRound)
    }

    /** T5a — bad shape degrade (null round). Catches constructing a round from a half-pair. */
    @Test
    fun `T5a bad shape degrade — half-pair R is null, baseline preserved`() {
        val prior = SessionEntry(
            status = SessionStatus(type = "busy"),
            serverRound = ServerRound(1, 9),
            origin = EntryOrigin.SSE_SLIM,
            updatedAtMs = 500L,
            workdir = "/x",
            scopeKey = scope,
            serverRoundHighWater = ServerRound(1, 9),
        )
        val state = StoreState.initial().copy(
            identityEpoch = 0L,
            authority = AuthorityState(bySid = mapOf("A" to prior)),
        )
        // turnIncarnation=null, turn=3 → pair-rule → R=null
        val op = AuthorityOp.ApplySnapshot(
            snapshot = mapOf("A" to SessionStatus(type = "idle", turn = 3)),
            sidToWorkdir = mapOf("A" to "/x"),
            authoritativeNodeIds = setOf("A"),
            registeredWorkdirs = emptySet(),
            coveredWorkdirs = emptySet(),
            unmappedActiveIds = emptySet(),
            partialFailureWorkdirs = emptySet(),
            lastSuccessTimeMs = 1000L,
            scopeKey = scope,
            requestToken = token(requestStartMs = 1000L),
            localBefore = mapOf("A" to SessionStatus(type = "busy")),
        )
        val result = reduceAuthority(state, op)
        val entry = result.authority.bySid["A"]!!
        assertEquals("idle applied", "idle", entry.status.type)
        // R=null → baseline preserved at (1,9)
        assertEquals("baseline preserved at (1,9)", ServerRound(1, 9), entry.serverRound)
        assertEquals("hw preserved at (1,9)", ServerRound(1, 9), entry.serverRoundHighWater)
    }

    /** T6 — old sidecar 404 / legacy shape. Catches old clear-on-REST behavior. */
    @Test
    fun `T6 old sidecar — null-round REST preserves baseline, stale frame fenced`() {
        val prior = SessionEntry(
            status = SessionStatus(type = "busy"),
            serverRound = ServerRound(1, 5),
            origin = EntryOrigin.SSE_SLIM,
            updatedAtMs = 1000L,
            workdir = "/x",
            scopeKey = scope,
            serverRoundHighWater = ServerRound(1, 5),
        )
        val state = StoreState.initial().copy(
            identityEpoch = 0L,
            authority = AuthorityState(bySid = mapOf("A" to prior)),
        )
        // Null-round REST (no turn fields — old sidecar shape)
        val op = AuthorityOp.ApplySnapshot(
            snapshot = mapOf("A" to SessionStatus(type = "idle")),
            sidToWorkdir = mapOf("A" to "/x"),
            authoritativeNodeIds = setOf("A"),
            registeredWorkdirs = emptySet(),
            coveredWorkdirs = emptySet(),
            unmappedActiveIds = emptySet(),
            partialFailureWorkdirs = emptySet(),
            lastSuccessTimeMs = 2000L,
            scopeKey = scope,
            requestToken = token(requestStartMs = 2000L),
            localBefore = mapOf("A" to SessionStatus(type = "busy")),
        )
        val snapResult = reduceAuthority(state, op)
        val entry = snapResult.authority.bySid["A"]!!
        assertEquals("idle applied", "idle", entry.status.type)
        // serverRound PRESERVED at (1,5) — NOT cleared
        assertEquals("baseline preserved at (1,5)", ServerRound(1, 5), entry.serverRound)
        assertEquals("hw preserved at (1,5)", ServerRound(1, 5), entry.serverRoundHighWater)

        // Follow-up: stale slim frame (1,3) < preserved baseline (1,5) → DROP
        val staleResult = reduceAuthority(snapResult,
            event("A", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(1L, 3L), monotonic = 3000L))
        assertSame("stale frame DROPPED (same ref)", snapResult, staleResult)
    }

    /** T7 — incarnation reset via REST. Catches turn-only comparison misreading (2,0). */
    @Test
    fun `T7 incarnation reset — turn rolled back by restart, lex correctly advances`() {
        val prior = SessionEntry(
            status = SessionStatus(type = "busy"),
            serverRound = ServerRound(1, 9),
            origin = EntryOrigin.SSE_SLIM,
            updatedAtMs = 1000L,
            workdir = "/x",
            scopeKey = scope,
            serverRoundHighWater = ServerRound(1, 9),
        )
        val state = StoreState.initial().copy(
            identityEpoch = 0L,
            authority = AuthorityState(
                bySid = mapOf("A" to prior),
                knownIncarnations = mapOf(scope to 1L),
            ),
        )
        val op = AuthorityOp.ApplySnapshot(
            snapshot = mapOf("A" to SessionStatus(type = "idle", turnIncarnation = 2, turn = 0)),
            sidToWorkdir = mapOf("A" to "/x"),
            authoritativeNodeIds = setOf("A"),
            registeredWorkdirs = emptySet(),
            coveredWorkdirs = emptySet(),
            unmappedActiveIds = emptySet(),
            partialFailureWorkdirs = emptySet(),
            lastSuccessTimeMs = 2000L,
            scopeKey = scope,
            requestToken = token(requestStartMs = 2000L),
            localBefore = mapOf("A" to SessionStatus(type = "busy")),
        )
        val result = reduceAuthority(state, op)
        val entry = result.authority.bySid["A"]!!
        assertEquals("idle applied", "idle", entry.status.type)
        // Incarnation 2 lex-dominates 1, so (2,0) > effBase (1,9)
        assertEquals(ServerRound(2, 0), entry.serverRound)
        assertEquals(ServerRound(2, 0), entry.serverRoundHighWater)
        // knownIncarnations bumped to 2
        assertEquals(2L, result.authority.knownIncarnations[scope])
    }

    /** T8 — incarnation advance via REST. Catches missing snapInc bump. */
    @Test
    fun `T8 incarnation advance via REST — snapInc bump fences stale-inc SSE frame`() {
        val priorA = SessionEntry(
            status = SessionStatus(type = "busy"),
            serverRound = ServerRound(1, 5),
            origin = EntryOrigin.SSE_SLIM,
            updatedAtMs = 1000L,
            workdir = "/x",
            scopeKey = scope,
            serverRoundHighWater = ServerRound(1, 5),
        )
        val state = StoreState.initial().copy(
            identityEpoch = 0L,
            authority = AuthorityState(
                bySid = mapOf("A" to priorA),
                knownIncarnations = mapOf(scope to 1L),
            ),
        )
        val op = AuthorityOp.ApplySnapshot(
            snapshot = mapOf(
                "A" to SessionStatus(type = "busy", turnIncarnation = 2, turn = 3),
                "B" to SessionStatus(type = "idle", turnIncarnation = 2, turn = 0),
            ),
            sidToWorkdir = mapOf("A" to "/x", "B" to "/y"),
            authoritativeNodeIds = setOf("A", "B"),
            registeredWorkdirs = emptySet(),
            coveredWorkdirs = emptySet(),
            unmappedActiveIds = emptySet(),
            partialFailureWorkdirs = emptySet(),
            lastSuccessTimeMs = 2000L,
            scopeKey = scope,
            requestToken = token(requestStartMs = 2000L),
            localBefore = mapOf("A" to SessionStatus(type = "busy")),
        )
        val result = reduceAuthority(state, op)

        // knownIncarnations bumped to 2
        assertEquals(2L, result.authority.knownIncarnations[scope])
        val a = result.authority.bySid["A"]!!
        assertEquals(ServerRound(2, 3), a.serverRound)
        val b = result.authority.bySid["B"]!!
        assertEquals(ServerRound(2, 0), b.serverRound)

        // Follow-up: stale-incarnation (1,9) SSE frame for unknown sid C → DROPPED by B6
        val staleResult = reduceAuthority(result,
            event("C", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
                serverRound = ServerRound(1L, 9L), monotonic = 3000L))
        assertNull("C absent (DROPPED by B6 scope guard)", staleResult.authority.bySid["C"])
    }

    /** T9 — incarnation regression via REST. Catches wholesale drop or apply. */
    @Test
    fun `T9 incarnation regression — stale pre-restart response, prior preserved`() {
        val priorA = SessionEntry(
            status = SessionStatus(type = "busy"),
            serverRound = ServerRound(2, 5),
            origin = EntryOrigin.SSE_SLIM,
            updatedAtMs = 1000L,
            workdir = "/x",
            scopeKey = scope,
            serverRoundHighWater = ServerRound(2, 5),
        )
        val state = StoreState.initial().copy(
            identityEpoch = 0L,
            authority = AuthorityState(
                bySid = mapOf("A" to priorA),
                knownIncarnations = mapOf(scope to 2L),
            ),
        )
        // Stale pre-restart response with incarnation 1 (regression)
        val op = AuthorityOp.ApplySnapshot(
            snapshot = mapOf(
                "A" to SessionStatus(type = "idle", turnIncarnation = 1, turn = 9),
                "D" to SessionStatus(type = "busy", turnIncarnation = 1, turn = 2),
            ),
            sidToWorkdir = mapOf("A" to "/x", "D" to "/z"),
            authoritativeNodeIds = setOf("A", "D"),
            registeredWorkdirs = emptySet(),
            coveredWorkdirs = emptySet(),
            unmappedActiveIds = emptySet(),
            partialFailureWorkdirs = emptySet(),
            lastSuccessTimeMs = 2000L,
            scopeKey = scope,
            requestToken = token(requestStartMs = 2000L),
            localBefore = mapOf("A" to SessionStatus(type = "busy")),
        )
        val result = reduceAuthority(state, op)

        // A: R (1,9) < effBase (2,5) → fenced → prior preserved verbatim
        val a = result.authority.bySid["A"]!!
        assertEquals("A: busy preserved (fenced)", "busy", a.status.type)
        assertEquals("A: baseline (2,5) preserved", ServerRound(2, 5), a.serverRound)
        assertEquals("A: hw (2,5) preserved", ServerRound(2, 5), a.serverRoundHighWater)
        assertEquals("A: updatedAtMs 1000 (verbatim)", 1000L, a.updatedAtMs)

        // D: no prior → best available info
        val d = result.authority.bySid["D"]!!
        assertEquals("D: busy", "busy", d.status.type)
        assertEquals(ServerRound(1, 2), d.serverRound)

        // knownIncarnations stays at 2 (not regressed)
        assertEquals(2L, result.authority.knownIncarnations[scope])
    }

    /** T10 — equal-round tie-break. Catches ignoring the tie-break in either direction. */
    @Test
    fun `T10 equal-round tie-break — newer requestStartMs wins, older preserves`() {
        val prior = SessionEntry(
            status = SessionStatus(type = "busy"),
            serverRound = ServerRound(1, 5),
            origin = EntryOrigin.SSE_SLIM,
            updatedAtMs = 1000L,
            workdir = "/x",
            scopeKey = scope,
            serverRoundHighWater = ServerRound(1, 5),
        )
        val state = StoreState.initial().copy(
            identityEpoch = 0L,
            authority = AuthorityState(bySid = mapOf("A" to prior)),
        )
        val localBefore = mapOf("A" to SessionStatus(type = "busy"))

        // Op-1: newer requestStartMs (2000 ≥ 1000) → applied
        val op1 = AuthorityOp.ApplySnapshot(
            snapshot = mapOf("A" to SessionStatus(type = "idle", turnIncarnation = 1, turn = 5)),
            sidToWorkdir = mapOf("A" to "/x"),
            authoritativeNodeIds = setOf("A"),
            registeredWorkdirs = emptySet(),
            coveredWorkdirs = emptySet(),
            unmappedActiveIds = emptySet(),
            partialFailureWorkdirs = emptySet(),
            lastSuccessTimeMs = 2000L,
            scopeKey = scope,
            requestToken = token(requestStartMs = 2000L),
            localBefore = localBefore,
        )
        val r1 = reduceAuthority(state, op1)
        val a1 = r1.authority.bySid["A"]!!
        assertEquals("idle applied (2000 ≥ 1000)", "idle", a1.status.type)
        assertEquals(ServerRound(1, 5), a1.serverRound)
        assertEquals(2000L, a1.updatedAtMs)

        // Op-2: independent — older requestStartMs (500 < 1000) → preserved verbatim
        // Seed matching coverage so the no-change check passes (same-ref).
        val priorCov = Coverage(
            registeredWorkdirs = emptySet(),
            coveredWorkdirs = emptySet(),
            unmappedActiveIds = emptySet(),
            lastSuccessTimeMs = 500L,
        )
        val state2 = StoreState.initial().copy(
            identityEpoch = 0L,
            authority = AuthorityState(
                bySid = mapOf("A" to prior),
                coverage = mapOf(scope to priorCov),
                knownIncarnations = mapOf(scope to 1L), // match snapInc
            ),
        )
        val op2 = op1.copy(
            requestToken = token(requestStartMs = 500L),
            lastSuccessTimeMs = 500L,
        )
        val r2 = reduceAuthority(state2, op2)
        assertSame("prior preserved verbatim (500 < 1000)", state2, r2)
    }

    /** Local assertNotNull to avoid an extra import line churn. */
    private fun assertNotNull(message: String, actual: Any?) =
        org.junit.Assert.assertNotNull(message, actual)
}
