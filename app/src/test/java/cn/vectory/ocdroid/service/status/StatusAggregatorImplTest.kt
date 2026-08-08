package cn.vectory.ocdroid.service.status

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.state.AuthorityOp
import cn.vectory.ocdroid.data.state.EntryOrigin
import cn.vectory.ocdroid.data.state.RequestToken
import cn.vectory.ocdroid.data.state.ScopeKey
import cn.vectory.ocdroid.data.state.scopeKeyOf
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.SharedStateStore
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [StatusAggregatorImpl] (dev-design P0.4 / FGS spec §3 + §3.1, CP4).
 *
 * ## F1 rewrite (archdebt follow-up)
 *
 * The `StatusAggregatorInput` interface + adapters (`refresh`/`applySseStatus`/
 * `markRequestFailed`) were **retired**. Tests that previously drove state
 * through those adapters now seed authority directly via `store.dispatch`
 * (`AuthorityOp.ApplySnapshot` / `ApplyEvent` / `MarkSourceFailed`).
 * The read-side derivation (`globalState` / `globalBusy` / `statusByKey` /
 * `stateAtNow`) is UNCHANGED.
 *
 * Focus areas:
 *  - REST success maps host-level statuses to composite [SessionStatusKey]s via
 *    `session.directory` (server-pruned idle entries → known-but-absent = `Idle`).
 *  - REST failure → every known session = `Unknown`, and a fresher prior `Busy`/`Retry`
 *    is preserved by merge timing (Unknown does **not** wrongly clear `globalBusy`,
 *    guarding the idle-grace window).
 *  - Merge timing: a newer SSE status survives a REST snapshot whose `requestStart`
 *    predates it, and vice-versa.
 *  - `globalBusy` is true iff any entry under the current identity's `profileId` is
 *    `Busy` or `Retry`.
 *  - **CP4 tri-state** ([globalState]): Busy / AllIdleFresh / Unknown semantics.
 *  - **CP4 TTL** (~30s): stale `Idle` entries → Unknown; stale `Busy` stays Busy.
 *  - **CP4 explicit failure** entry: [AuthorityOp.MarkSourceFailed].
 *
 * The clock is a mutable `var` lambda so each test controls source times precisely.
 * The [ConnectionIdentityStore] is real (it is a plain atomic holder — no Android deps).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusAggregatorImplTest {

    private val fp = "host-group-A"

    private fun identity(epoch: Long = 1L, groupFp: String = fp): ConnectionIdentity =
        ConnectionIdentity(
            epoch = epoch,
            profileId = groupFp,
            normalizedWorkdir = "/work",
            endpointFp = "endpoint-A",
        )

    private fun session(id: String, directory: String): Session =
        Session(id = id, directory = directory)

    private fun key(sessionId: String, workdir: String, groupFp: String = fp): SessionStatusKey =
        SessionStatusKey(profileId = groupFp, workdir = workdir, sessionId = sessionId)

    // ── F1: seed helpers ──────────────────────────────────────────────────────

    /**
     * Build a [AuthorityOp.ApplySnapshot] matching the op the deleted
     * [StatusAggregatorInput.refresh] adapter produced on success.
     */
    private fun buildApplySnapshotOp(
        store: SharedStateStore,
        statuses: Map<String, SessionStatus>,
        sessions: Map<String, Session>,
        requestStartMs: Long,
        scopeKey: ScopeKey = scopeKeyOf(fp, "endpoint-A"),
        unmappedActiveIds: Set<String> = emptySet(),
        coveredWorkdirs: Set<String> = sessions.values.map { it.directory }.toSet(),
        lastSuccessTimeMs: Long = requestStartMs,
    ): AuthorityOp.ApplySnapshot {
        val authorityAtStart = store.stateFlow.value.authority
        val localBefore = authorityAtStart.bySid
            .filterValues { it.updatedAtMs <= requestStartMs }
            .mapValues { it.value.status }
        val token = RequestToken(
            hostProfileId = fp,
            requestStartMs = requestStartMs,
            identityEpoch = store.stateFlow.value.identityEpoch,
        )
        return cn.vectory.ocdroid.ui.buildAuthorityApplySnapshot(
            snapshot = statuses,
            authoritativeSessions = sessions,
            authoritativeNodeIds = sessions.keys,
            coveredWorkdirs = coveredWorkdirs,
            partialFailureWorkdirs = emptySet(),
            unmappedActiveIds = unmappedActiveIds,
            lastSuccessTimeMs = lastSuccessTimeMs,
            scopeKey = scopeKey,
            requestToken = token,
            localBefore = localBefore,
        )
    }

    /**
     * Seed aggregator with a REST-like successful snapshot via authority dispatch.
     */
    private fun seedSnapshot(
        aggregator: StatusAggregatorImpl,
        store: SharedStateStore,
        statuses: Map<String, SessionStatus>,
        sessions: Map<String, Session>,
        requestStartMs: Long = 100L,
        unmappedActiveIds: Set<String> = emptySet(),
        coveredWorkdirs: Set<String> = sessions.values.map { it.directory }.toSet(),
    ) {
        val op = buildApplySnapshotOp(
            store = store,
            statuses = statuses,
            sessions = sessions,
            requestStartMs = requestStartMs,
            unmappedActiveIds = unmappedActiveIds,
            coveredWorkdirs = coveredWorkdirs,
        )
        store.dispatch(AppAction.AuthorityEvent(op))
        aggregator.publishFromState(store.stateFlow.value)
    }

    /**
     * Seed aggregator with an SSE-like single status update (ApplyEvent).
     */
    private fun seedApplyEvent(
        aggregator: StatusAggregatorImpl,
        store: SharedStateStore,
        sid: String,
        status: SessionBusyStatus,
        sourceTimeMs: Long,
        workdir: String = "/work",
    ) {
        val statusStr = when (status) {
            SessionBusyStatus.Busy -> "busy"
            SessionBusyStatus.Retry -> "retry"
            SessionBusyStatus.Idle -> "idle"
            SessionBusyStatus.Unknown, SessionBusyStatus.Fresh ->
                error("Cannot seed $status via ApplyEvent")
        }
        val op = AuthorityOp.ApplyEvent(
            sid = sid,
            status = SessionStatus(type = statusStr),
            origin = EntryOrigin.SSE_SLIM,
            scopeKey = scopeKeyOf(fp, "endpoint-A"),
            connectionTimeMs = sourceTimeMs,
            workdir = workdir,
        )
        store.dispatch(AppAction.AuthorityEvent(op))
        aggregator.publishFromState(store.stateFlow.value)
    }

    /**
     * Seed aggregator with a failure (MarkSourceFailed).
     */
    private fun seedMarkSourceFailed(
        aggregator: StatusAggregatorImpl,
        store: SharedStateStore,
        registeredWorkdirs: Set<String>,
        sourceTimeMs: Long = 100L,
    ) {
        val token = RequestToken(
            hostProfileId = fp,
            requestStartMs = sourceTimeMs,
            identityEpoch = store.stateFlow.value.identityEpoch,
        )
        val op = AuthorityOp.MarkSourceFailed(
            scopeKey = scopeKeyOf(fp, "endpoint-A"),
            requestToken = token,
            monotonic = sourceTimeMs,
            registeredWorkdirs = registeredWorkdirs,
        )
        store.dispatch(AppAction.AuthorityEvent(op))
        aggregator.publishFromState(store.stateFlow.value)
    }

    // ── Fixture ─────────────────────────────────────────────────────────────

    /**
     * Build a fresh aggregator + store pair with no pre-seeded authority state.
     */
    private fun newAggregator(
        identityStore: ConnectionIdentityStore = ConnectionIdentityStore().also { it.bind(fp, "/work", "endpoint-A") },
        clock: () -> Long = { 0L },
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    ): Pair<StatusAggregatorImpl, SharedStateStore> {
        val store = SharedStateStore()
        return StatusAggregatorImpl(identityStore, store, scope, clock) to store
    }

    // ── (1) REST success: host statuses → composite keys via session.directory ─

    @Test
    fun `REST success maps returned active sessions to Busy and known-but-absent to Idle`() = runTest {
        val (aggregator, store) = newAggregator(clock = { 100L })
        val sessions = mapOf(
            "s1" to session("s1", "/work-a"),
            "s2" to session("s2", "/work-b"),
            "s3" to session("s3", "/work-a"),
        )

        seedSnapshot(
            aggregator, store,
            statuses = mapOf(
                "s1" to SessionStatus(type = "busy"),
                "s2" to SessionStatus(type = "retry"),
            ),
            sessions = sessions,
            requestStartMs = 100L,
        )

        val statuses = aggregator.statusByKey.value
        assertEquals(SessionBusyStatus.Busy, statuses[key("s1", "/work-a")])
        assertEquals(SessionBusyStatus.Retry, statuses[key("s2", "/work-b")])
        assertEquals(SessionBusyStatus.Idle, statuses[key("s3", "/work-a")])
        assertEquals(3, statuses.size)
        assertTrue(aggregator.globalBusy.value)
    }

    @Test
    fun `D1 gate #5 - REST success with unmapped active id forces Busy (NOT ignored)`() = runTest {
        val (aggregator, store) = newAggregator(clock = { 100L })

        seedSnapshot(
            aggregator, store,
            statuses = mapOf("ghost" to SessionStatus(type = "busy")),
            sessions = mapOf("s1" to session("s1", "/work")),
            requestStartMs = 100L,
            unmappedActiveIds = setOf("ghost"),
            coveredWorkdirs = setOf("/work"),
        )

        val statuses = aggregator.statusByKey.value
        val ghostEntry = statuses[key("ghost", "")]
        assertNotNull(
            "ghost id materialises a key with empty workdir (direct authority path)",
            ghostEntry,
        )
        assertEquals(SessionBusyStatus.Busy, ghostEntry)
        assertEquals(SessionBusyStatus.Idle, statuses[key("s1", "/work")])
        assertEquals(GlobalBusyState.Busy, aggregator.globalState.value)
        assertTrue(aggregator.globalBusy.value)
    }

    // ── (2) REST failure → Unknown; idle-grace guard ────────────────────────

    @Test
    fun `REST failure labels every known session Unknown and globalState is Unknown`() = runTest {
        val (aggregator, store) = newAggregator(clock = { 100L })

        seedMarkSourceFailed(
            aggregator, store,
            registeredWorkdirs = setOf("/work-a", "/work-b"),
            sourceTimeMs = 100L,
        )

        val statuses = aggregator.statusByKey.value
        assertTrue("statusByKey is empty after failure", statuses.isEmpty())
        assertFalse(aggregator.globalBusy.value)
        assertEquals(GlobalBusyState.Unknown, aggregator.globalState.value)
    }

    @Test
    fun `REST failure does not wrongly clear globalBusy when a fresher SSE Busy exists`() = runTest {
        var now = 0L
        val (aggregator, store) = newAggregator(clock = { now })

        // SSE delivers Busy for s1 at t=150.
        seedApplyEvent(aggregator, store, "s1", SessionBusyStatus.Busy, sourceTimeMs = 150L)
        assertTrue(aggregator.globalBusy.value)

        // Failure at t=100 (BEFORE the SSE update).
        now = 100L
        seedMarkSourceFailed(
            aggregator, store,
            registeredWorkdirs = setOf("/work"),
            sourceTimeMs = 100L,
        )

        assertEquals(SessionBusyStatus.Busy, aggregator.statusByKey.value[key("s1", "/work")])
        assertTrue(aggregator.globalBusy.value)
        assertEquals(GlobalBusyState.Busy, aggregator.globalState.value)
    }

    // ── (3) Merge timing: SSE vs REST ordering ──────────────────────────────

    @Test
    fun `merge timing - newer SSE status survives a REST snapshot whose requestStart predates it`() =
        runTest {
            val (aggregator, store) = newAggregator(clock = { 100L })

            // SSE delivers Busy at t=150.
            seedApplyEvent(aggregator, store, "s1", SessionBusyStatus.Busy, sourceTimeMs = 150L)

            // REST snapshot at requestStartMs=100 (older) returns empty.
            seedSnapshot(
                aggregator, store,
                statuses = emptyMap(),
                sessions = mapOf("s1" to session("s1", "/work")),
                requestStartMs = 100L,
            )

            assertEquals(SessionBusyStatus.Busy, aggregator.statusByKey.value[key("s1", "/work")])
            assertTrue(aggregator.globalBusy.value)
        }

    @Test
    fun `M7 concurrent SSE Busy during suspended REST idle preserves merge timing`() = runTest {
        val (aggregator, store) = newAggregator(clock = { 100L }, scope = backgroundScope)

        seedSnapshot(
            aggregator, store,
            statuses = emptyMap(),
            sessions = mapOf("s1" to session("s1", "/work")),
            requestStartMs = 100L,
        )
        seedApplyEvent(aggregator, store, "s1", SessionBusyStatus.Busy, sourceTimeMs = 150L)

        // Re-apply the same REST snapshot — SSE Busy survives.
        seedSnapshot(
            aggregator, store,
            statuses = emptyMap(),
            sessions = mapOf("s1" to session("s1", "/work")),
            requestStartMs = 100L,
        )

        assertEquals(SessionBusyStatus.Busy, aggregator.statusByKey.value[key("s1", "/work")])
        assertEquals(GlobalBusyState.Busy, aggregator.stateAtNow())
    }

    @Test
    fun `M7 stateAtNow and statusByKey derive from one committed aggregate`() = runTest {
        val (aggregator, store) = newAggregator(clock = { 100L })

        seedSnapshot(
            aggregator, store,
            statuses = emptyMap(),
            sessions = mapOf("s1" to session("s1", "/work")),
            requestStartMs = 100L,
        )
        seedApplyEvent(aggregator, store, "s1", SessionBusyStatus.Retry, sourceTimeMs = 101L)

        assertEquals(SessionBusyStatus.Retry, aggregator.statusByKey.value[key("s1", "/work")])
        assertEquals(GlobalBusyState.Busy, aggregator.stateAtNow())
        assertEquals(GlobalBusyState.Busy, aggregator.globalState.value)
    }

    @Test
    fun `merge timing - REST snapshot whose requestStart is newer overwrites the older SSE status`() =
        runTest {
            var now = 0L
            val (aggregator, store) = newAggregator(clock = { now })

            seedApplyEvent(aggregator, store, "s1", SessionBusyStatus.Busy, sourceTimeMs = 100L)

            now = 200L
            seedSnapshot(
                aggregator, store,
                statuses = emptyMap(),
                sessions = mapOf("s1" to session("s1", "/work")),
                requestStartMs = 200L,
            )

            assertEquals(SessionBusyStatus.Idle, aggregator.statusByKey.value[key("s1", "/work")])
            assertFalse(aggregator.globalBusy.value)
        }

    @Test
    fun `ApplyEvent with older wall-clock timestamp is still applied (no ApplyEvent wall-clock fence)`() = runTest {
        var now = 100L
        val (aggregator, store) = newAggregator(clock = { now })

        seedSnapshot(
            aggregator, store,
            statuses = mapOf("s1" to SessionStatus(type = "busy")),
            sessions = mapOf("s1" to session("s1", "/work")),
            requestStartMs = 100L,
        )
        assertEquals(SessionBusyStatus.Busy, aggregator.statusByKey.value[key("s1", "/work")])

        // ApplyEvent does NOT have a wall-clock merge timing check (it was in the removed
        // StatusAggregatorInput adapter). The older timestamp IS applied — the wall-clock
        // regression protection is one-directional: ApplySnapshot (newer requestStartMs
        // does NOT overwrite a prior ApplyEvent whose updatedAtMs is newer).
        seedApplyEvent(aggregator, store, "s1", SessionBusyStatus.Idle, sourceTimeMs = 50L)
        assertEquals(SessionBusyStatus.Idle, aggregator.statusByKey.value[key("s1", "/work")])
        assertFalse(aggregator.globalBusy.value)
    }

    // ── (4) globalBusy: Busy/Retry projection ───────────────────────────────

    @Test
    fun `globalBusy true iff any Busy or Retry entry exists under the current identity`() = runTest {
        val (aggregator, store) = newAggregator(clock = { 100L })

        seedSnapshot(
            aggregator, store,
            statuses = mapOf("s1" to SessionStatus(type = "busy")),
            sessions = mapOf("s1" to session("s1", "/work")),
            requestStartMs = 100L,
        )
        assertTrue(aggregator.globalBusy.value)

        seedApplyEvent(aggregator, store, "s1", SessionBusyStatus.Idle, sourceTimeMs = 200L)
        assertFalse(aggregator.globalBusy.value)

        seedApplyEvent(aggregator, store, "s2", SessionBusyStatus.Retry, sourceTimeMs = 300L, workdir = "/other")
        assertTrue(aggregator.globalBusy.value)
    }

    // ── (5) CP4 tri-state globalState ───────────────────────────────────────

    @Test
    fun `globalState is Unknown before any refresh on an empty aggregator`() {
        val (aggregator, _) = newAggregator()
        assertEquals(GlobalBusyState.Unknown, aggregator.globalState.value)
    }

    @Test
    fun `globalState is Busy when any session is Busy or Retry`() = runTest {
        val (aggregator, store) = newAggregator(clock = { 100L })

        seedSnapshot(
            aggregator, store,
            statuses = mapOf("s1" to SessionStatus(type = "busy")),
            sessions = mapOf("s1" to session("s1", "/work")),
            requestStartMs = 100L,
        )
        assertEquals(GlobalBusyState.Busy, aggregator.globalState.value)
    }

    @Test
    fun `globalState is AllIdleFresh when all sessions are fresh Idle`() = runTest {
        val (aggregator, store) = newAggregator(clock = { 100L })

        seedSnapshot(
            aggregator, store,
            statuses = emptyMap(),
            sessions = mapOf(
                "s1" to session("s1", "/work-a"),
                "s2" to session("s2", "/work-b"),
            ),
            requestStartMs = 100L,
        )
        assertEquals(GlobalBusyState.AllIdleFresh, aggregator.globalState.value)
    }

    @Test
    fun `globalState is Unknown after a failure (NOT AllIdleFresh)`() = runTest {
        val (aggregator, store) = newAggregator(clock = { 100L })

        seedMarkSourceFailed(
            aggregator, store,
            registeredWorkdirs = setOf("/work-a"),
            sourceTimeMs = 100L,
        )
        assertEquals(GlobalBusyState.Unknown, aggregator.globalState.value)
    }

    // ── (6) CP4 TTL: stale entries fall back to Unknown (for idle) ───────────

    @Test
    fun `TTL - fresh Idle within 30s is AllIdleFresh`() = runTest {
        var now = 1_000L
        val (aggregator, store) = newAggregator(clock = { now })

        seedSnapshot(
            aggregator, store,
            statuses = emptyMap(),
            sessions = mapOf("s1" to session("s1", "/work")),
            requestStartMs = 1_000L,
        )
        assertEquals(GlobalBusyState.AllIdleFresh, aggregator.globalState.value)

        now = 1_000L + StatusAggregatorImpl.STATUS_TTL_MS - 1
        seedApplyEvent(aggregator, store, "s1", SessionBusyStatus.Idle, sourceTimeMs = 1_000L)
        assertEquals(GlobalBusyState.AllIdleFresh, aggregator.globalState.value)
    }

    @Test
    fun `TTL - Idle entry older than 30s flips globalState to Unknown (not authoritative idle)`() =
        runTest {
            var now = 1_000L
            val identityStore = ConnectionIdentityStore().also { it.bind(fp, "/work", "endpoint-A") }
            val store = SharedStateStore()
            val aggregator = StatusAggregatorImpl(
                identityStore, store, backgroundScope, clock = { now },
            )

            val sessions = mapOf("s1" to session("s1", "/work"))
            val op = buildApplySnapshotOp(
                store = store,
                statuses = emptyMap(),
                sessions = sessions,
                requestStartMs = 1_000L,
            )
            store.dispatch(AppAction.AuthorityEvent(op))
            aggregator.publishFromState(store.stateFlow.value)
            assertEquals(GlobalBusyState.AllIdleFresh, aggregator.globalState.value)

            now = 1_000L + StatusAggregatorImpl.STATUS_TTL_MS + 1
            advanceTimeBy(StatusAggregatorImpl.STATUS_TTL_MS + 2)
            runCurrent()

            assertEquals(
                "D1 gate #1: stale Idle autonomously expires to Unknown",
                GlobalBusyState.Unknown,
                aggregator.globalState.value,
            )
        }

    @Test
    fun `D1 gate #1 - fresh REST idle autonomously expires to Unknown without any write`() = runTest {
        var now = 0L
        val identityStore = ConnectionIdentityStore().also { it.bind(fp, "/work", "endpoint-A") }
        val store = SharedStateStore()
        val aggregator = StatusAggregatorImpl(
            identityStore, store, backgroundScope, clock = { now },
        )

        val sessions = mapOf("s1" to session("s1", "/work"))
        val op = buildApplySnapshotOp(store, emptyMap(), sessions, requestStartMs = 0L)
        store.dispatch(AppAction.AuthorityEvent(op))
        aggregator.publishFromState(store.stateFlow.value)
        assertEquals(GlobalBusyState.AllIdleFresh, aggregator.globalState.value)

        now = StatusAggregatorImpl.STATUS_TTL_MS + 1
        advanceTimeBy(StatusAggregatorImpl.STATUS_TTL_MS + 2)
        runCurrent()

        assertEquals(GlobalBusyState.Unknown, aggregator.globalState.value)
    }

    @Test
    fun `D1 gate #1 - stateAtNow reads time-correct state independent of globalState cache`() = runTest {
        var now = 1_000L
        val (aggregator, store) = newAggregator(clock = { now })

        seedSnapshot(
            aggregator, store,
            statuses = emptyMap(),
            sessions = mapOf("s1" to session("s1", "/work")),
            requestStartMs = 1_000L,
        )
        assertEquals(GlobalBusyState.AllIdleFresh, aggregator.globalState.value)
        assertEquals(GlobalBusyState.AllIdleFresh, aggregator.stateAtNow())

        now = 1_000L + StatusAggregatorImpl.STATUS_TTL_MS + 1
        assertEquals(
            "globalState cache lags wall clock",
            GlobalBusyState.AllIdleFresh,
            aggregator.globalState.value,
        )
        assertEquals(
            "stateAtNow reads time-correct verdict = Unknown",
            GlobalBusyState.Unknown,
            aggregator.stateAtNow(),
        )
    }

    @Test
    fun `TTL - stale Busy entry stays Busy (conservative - never silently drop keep-alive)`() = runTest {
        var now = 1_000L
        val (aggregator, store) = newAggregator(clock = { now })

        seedSnapshot(
            aggregator, store,
            statuses = mapOf("s1" to SessionStatus(type = "busy")),
            sessions = mapOf("s1" to session("s1", "/work")),
            requestStartMs = 1_000L,
        )
        assertEquals(GlobalBusyState.Busy, aggregator.globalState.value)

        now = 1_000L + StatusAggregatorImpl.STATUS_TTL_MS * 10
        seedApplyEvent(aggregator, store, "s1", SessionBusyStatus.Busy, sourceTimeMs = 1_000L)
        assertEquals(GlobalBusyState.Busy, aggregator.globalState.value)
    }

    // ── (7) CP4 explicit failure (MarkSourceFailed) ──────────────────────────

    @Test
    fun `markSourceFailed labels every known session Unknown`() {
        val (aggregator, store) = newAggregator(clock = { 100L })

        seedMarkSourceFailed(
            aggregator, store,
            registeredWorkdirs = setOf("/work-a", "/work-b"),
            sourceTimeMs = 100L,
        )

        val statuses = aggregator.statusByKey.value
        assertTrue("statusByKey is empty after MarkSourceFailed", statuses.isEmpty())
        assertEquals(GlobalBusyState.Unknown, aggregator.globalState.value)
    }

    @Test
    fun `markSourceFailed preserves a fresher prior Busy via merge timing`() {
        val (aggregator, store) = newAggregator(clock = { 0L })

        seedApplyEvent(aggregator, store, "s1", SessionBusyStatus.Busy, sourceTimeMs = 200L)

        seedMarkSourceFailed(
            aggregator, store,
            registeredWorkdirs = setOf("/work"),
            sourceTimeMs = 100L,
        )

        assertEquals(SessionBusyStatus.Busy, aggregator.statusByKey.value[key("s1", "/work")])
        assertEquals(GlobalBusyState.Busy, aggregator.globalState.value)
    }

    // ── (8) D1 gate #5: unmapped-active→Busy + registered-workdir coverage ──

    @Test
    fun `D1 gate #5 - ghost busy plus known idle session forces global Busy`() = runTest {
        val (aggregator, store) = newAggregator(clock = { 100L })

        seedSnapshot(
            aggregator, store,
            statuses = mapOf("ghost" to SessionStatus(type = "busy")),
            sessions = mapOf("s1" to session("s1", "/work")),
            requestStartMs = 100L,
            unmappedActiveIds = setOf("ghost"),
            coveredWorkdirs = setOf("/work"),
        )

        assertEquals(GlobalBusyState.Busy, aggregator.globalState.value)
        assertTrue(aggregator.globalBusy.value)
    }

    @Test
    fun `D1 gate #5 - all active ids map plus all workdirs covered is correct Busy`() = runTest {
        val (aggregator, store) = newAggregator(clock = { 100L })

        seedSnapshot(
            aggregator, store,
            statuses = mapOf(
                "s1" to SessionStatus(type = "busy"),
                "s2" to SessionStatus(type = "retry"),
            ),
            sessions = mapOf(
                "s1" to session("s1", "/work-a"),
                "s2" to session("s2", "/work-b"),
            ),
            requestStartMs = 100L,
            coveredWorkdirs = setOf("/work-a", "/work-b"),
        )

        assertEquals(GlobalBusyState.Busy, aggregator.globalState.value)
    }

    @Test
    fun `M6 host-global success covers registered workdir without sessions`() = runTest {
        val (aggregator, store) = newAggregator(clock = { 100L })

        seedSnapshot(
            aggregator, store,
            statuses = emptyMap(),
            sessions = mapOf("s1" to session("s1", "/work-a")),
            requestStartMs = 100L,
            coveredWorkdirs = setOf("/work-a", "/work-b"),
        )

        assertEquals(
            "host-global snapshot marker covers zero-session /work-b",
            GlobalBusyState.AllIdleFresh,
            aggregator.globalState.value,
        )
    }

    @Test
    fun `D1 gate #5 - fresh successful empty host snapshot with no workdirs is authoritative idle`() =
        runTest {
            val (aggregator, store) = newAggregator(clock = { 100L })

            seedSnapshot(
                aggregator, store,
                statuses = emptyMap(),
                sessions = emptyMap(),
                requestStartMs = 100L,
                coveredWorkdirs = emptySet(),
            )

            assertEquals(GlobalBusyState.AllIdleFresh, aggregator.globalState.value)
        }

    @Test
    fun `M6 empty host-global coverage marker expires to Unknown`() = runTest {
        var now = 0L
        val (aggregator, store) = newAggregator(clock = { now }, scope = backgroundScope)

        seedSnapshot(
            aggregator, store,
            statuses = emptyMap(),
            sessions = emptyMap(),
            requestStartMs = 0L,
            coveredWorkdirs = setOf("/zero-session"),
        )
        assertEquals(GlobalBusyState.AllIdleFresh, aggregator.globalState.value)

        now = StatusAggregatorImpl.STATUS_TTL_MS + 1
        advanceTimeBy(StatusAggregatorImpl.STATUS_TTL_MS + 2)
        runCurrent()

        assertEquals(GlobalBusyState.Unknown, aggregator.globalState.value)
        assertEquals(GlobalBusyState.Unknown, aggregator.stateAtNow())
    }

    @Test
    fun `D1 gate #5 - cold-start empty aggregator is Unknown (not vacuous idle)`() {
        val (aggregator, _) = newAggregator()
        assertEquals(GlobalBusyState.Unknown, aggregator.globalState.value)
        assertEquals(GlobalBusyState.Unknown, aggregator.stateAtNow())
    }

    // ── §P0-A rev-gpt #4: version-monotone publish ──────────────────────────

    @Test
    fun `rev-gpt #4 - two rapid dispatches produce the final verdict (monotone no-regression)`() = runTest {
        val (aggregator, store) = newAggregator(clock = { 100L })

        seedSnapshot(
            aggregator, store,
            statuses = emptyMap(),
            sessions = mapOf("s1" to session("s1", "/work")),
            requestStartMs = 100L,
        )
        assertEquals(GlobalBusyState.AllIdleFresh, aggregator.globalState.value)

        seedApplyEvent(aggregator, store, "s1", SessionBusyStatus.Busy, sourceTimeMs = 200L)
        assertEquals(GlobalBusyState.Busy, aggregator.globalState.value)
    }

    // ── U-PUBLISH: publishFromState synchronized + maxPublishedRevision ────

    @Test
    fun `U-PUBLISH - concurrent publish serializes with high-rev verdict visible`() = runTest {
        val identityStore = ConnectionIdentityStore().also { it.bind(fp, "/work", "endpoint-A") }
        val store = SharedStateStore()
        val aggregator = StatusAggregatorImpl(
            identityStore, store,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            clock = { 100L },
        )

        val latch = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val scopeKey = scopeKeyOf(fp, "endpoint-A")

        val t1 = thread {
            latch.await()
            try {
                val op = AuthorityOp.ApplyEvent(
                    sid = "s1", status = SessionStatus(type = "busy"),
                    origin = EntryOrigin.SSE_SLIM, scopeKey = scopeKey,
                    connectionTimeMs = 100L, workdir = "/work",
                )
                store.dispatch(AppAction.AuthorityEvent(op))
                aggregator.publishFromState(store.stateFlow.value)
            } catch (e: Throwable) { errors.add(e) }
        }

        val t2 = thread {
            latch.await()
            try {
                val op = AuthorityOp.ApplyEvent(
                    sid = "s2", status = SessionStatus(type = "idle"),
                    origin = EntryOrigin.SSE_SLIM, scopeKey = scopeKey,
                    connectionTimeMs = 200L, workdir = "/work",
                )
                store.dispatch(AppAction.AuthorityEvent(op))
                aggregator.publishFromState(store.stateFlow.value)
            } catch (e: Throwable) { errors.add(e) }
        }

        latch.countDown()
        t1.join()
        t2.join()

        assertTrue("no exceptions under concurrent publish", errors.isEmpty())
        assertNotNull("globalState defined after concurrent publish", aggregator.globalState.value)
    }

    @Test
    fun `U-PUBLISH - same revision re-publish is allowed (not suppressed)`() = runTest {
        val identityStore = ConnectionIdentityStore().also { it.bind(fp, "/work", "endpoint-A") }
        val store = SharedStateStore()
        var clock = 0L
        val aggregator = StatusAggregatorImpl(
            identityStore, store,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            clock = { clock },
        )

        val state = store.stateFlow.value
        val rev = state.authorityRevision
        aggregator.publishFromState(state)

        clock = 100_000L
        aggregator.publishFromState(state)
        assertEquals(
            "same-rev re-publish entered publishLocked block",
            rev, store.stateFlow.value.authorityRevision,
        )
        assertNotNull("globalState still accessible", aggregator.globalState.value)
    }

    @Test
    fun `U-PUBLISH - low revision publish is suppressed by maxPublishedRevision`() = runTest {
        val identityStore = ConnectionIdentityStore().also { it.bind(fp, "/work", "endpoint-A") }
        val store = SharedStateStore()
        var clock = 0L
        val aggregator = StatusAggregatorImpl(
            identityStore, store,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            clock = { clock },
        )

        val stateLow = store.stateFlow.value
        val busyOp = AuthorityOp.ApplyEvent(
            sid = "s1", status = SessionStatus(type = "busy"),
            origin = EntryOrigin.SSE_SLIM, scopeKey = scopeKeyOf(fp, "endpoint-A"),
            connectionTimeMs = 100L, workdir = "/work",
        )
        store.dispatch(AppAction.AuthorityEvent(busyOp))
        val stateHigh = store.stateFlow.value
        assertTrue(
            "high-rev has higher authorityRevision",
            stateHigh.authorityRevision > stateLow.authorityRevision,
        )

        aggregator.publishFromState(stateHigh)
        val highRevVerdict = aggregator.globalState.value
        aggregator.publishFromState(stateLow)

        assertEquals("low-rev publish suppressed", highRevVerdict, aggregator.globalState.value)
    }

    @Test
    fun `scope derivation is consistent between bound identity and per-call identity`() = runTest {
        val (aggregator, store) = newAggregator()
        seedSnapshot(
            aggregator, store,
            statuses = emptyMap(), sessions = emptyMap(), requestStartMs = 100L,
        )
        assertTrue(
            "after empty success, statusByKey should be empty",
            aggregator.statusByKey.value.isEmpty(),
        )
    }

    @Test
    fun `markSourceFailed does NOT crash on stale identity (no epoch guard)`() {
        val identityStore = ConnectionIdentityStore()
            .also { it.bind(fp, "/work", "endpoint-A") }
        val (aggregator, store) = newAggregator(identityStore = identityStore, clock = { 100L })

        seedMarkSourceFailed(
            aggregator, store,
            registeredWorkdirs = setOf("/work"),
            sourceTimeMs = 100L,
        )
        assertEquals(
            "globalState is Unknown after failure",
            GlobalBusyState.Unknown,
            aggregator.globalState.value,
        )
    }
}
