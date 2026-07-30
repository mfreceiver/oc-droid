package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.state.AuthorityOp
import cn.vectory.ocdroid.data.state.AuthorityState
import cn.vectory.ocdroid.data.state.EntryOrigin
import cn.vectory.ocdroid.data.state.RequestToken
import cn.vectory.ocdroid.data.state.ScopeKey
import cn.vectory.ocdroid.data.state.ServerRound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

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
        host: String? = null,
        epoch: Long = 0L,
        requestStartMs: Long = 100L,
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
        requestToken = RequestToken(hostProfileId = host, epoch = epoch, requestStartMs = requestStartMs),
        localBefore = localBefore,
    )

    private fun storeWith(sessions: List<Session> = emptyList()): SharedStateStore =
        SharedStateStore().apply {
            mutateState { it.copy(sessionList = it.sessionList.copy(sessions = sessions)) }
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
        assertNull("terminal idle clears the claim", store.stateFlow.value.authority.bySid["A"]?.optimisticClaim)
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
        val store = SharedStateStore().apply {
            mutateState {
                it.copy(sessionList = it.sessionList.copy(sessions = listOf(
                    Session(id = "A", directory = "/x"), Session(id = "B", directory = "/x"))))
            }
        }
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
}

/** Local assertNotNull to avoid an extra import line churn. */
private fun assertNotNull(message: String, actual: Any?) =
    org.junit.Assert.assertNotNull(message, actual)
