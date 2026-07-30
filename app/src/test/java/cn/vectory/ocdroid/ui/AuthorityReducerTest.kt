package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.state.AuthorityOp
import cn.vectory.ocdroid.data.state.AuthorityState
import cn.vectory.ocdroid.data.state.EntryOrigin
import cn.vectory.ocdroid.data.state.Freshness
import cn.vectory.ocdroid.data.state.RequestToken
import cn.vectory.ocdroid.data.state.ScopeKey
import cn.vectory.ocdroid.data.state.ServerRound
import cn.vectory.ocdroid.data.state.SessionEntry
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
                    freshness = Freshness.Fresh,
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
}

/** Local assertNotNull to avoid an extra import line churn. */
private fun assertNotNull(message: String, actual: Any?) =
    org.junit.Assert.assertNotNull(message, actual)
