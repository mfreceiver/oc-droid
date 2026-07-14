package cn.vectory.ocdroid.service.status

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [StatusAggregatorImpl] (dev-design P0.4 / FGS spec §3 + §3.1, CP4).
 *
 * Focus areas:
 *  - REST success maps host-level statuses to composite [SessionStatusKey]s via
 *    `session.directory` (server-pruned idle entries → known-but-absent = `Idle`).
 *  - REST failure → every known session = `Unknown`, and a fresher prior `Busy`/`Retry`
 *    is preserved by merge timing (Unknown does **not** wrongly clear `globalBusy`,
 *    guarding the idle-grace window).
 *  - Merge timing: a newer SSE status survives a REST snapshot whose `requestStart`
 *    predates it, and vice-versa.
 *  - `globalBusy` is true iff any entry under the current identity's `serverGroupFp` is
 *    `Busy` or `Retry`.
 *  - **CP4 tri-state** ([globalState]): Busy / AllIdleFresh / Unknown semantics.
 *  - **CP4 TTL** (~30s): stale `Idle` entries → Unknown; stale `Busy` stays Busy.
 *  - **CP4 epoch guard**: a REST response whose epoch was bumped mid-request is dropped.
 *  - **CP4 explicit failure** entry: [StatusAggregatorInput.markRequestFailed].
 *
 * The repository is mocked (mockk); the clock is a mutable `var` lambda so each test
 * controls `requestStartMs` and SSE arrival times precisely. The
 * [ConnectionIdentityStore] is real (it is a plain atomic holder — no Android deps).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusAggregatorImplTest {

    private val fp = "host-group-A"

    private fun identity(epoch: Long = 1L, groupFp: String = fp): ConnectionIdentity =
        ConnectionIdentity(
            epoch = epoch,
            serverGroupFp = groupFp,
            normalizedWorkdir = "/work",
            endpointFp = "endpoint-A",
        )

    private fun session(id: String, directory: String): Session =
        Session(id = id, directory = directory)

    private fun key(sessionId: String, workdir: String, groupFp: String = fp): SessionStatusKey =
        SessionStatusKey(serverGroupFp = groupFp, workdir = workdir, sessionId = sessionId)

    private fun newAggregator(
        repository: OpenCodeRepository,
        identityStore: ConnectionIdentityStore = ConnectionIdentityStore().also { it.bind(fp, "/work", "endpoint-A") },
        clock: () -> Long = { 0L },
        scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined),
    ): StatusAggregatorImpl = StatusAggregatorImpl(repository, identityStore, scope, clock)

    /**
     * Helper: build a snapshot with the same workdir the [identity] uses,
     * registered as a covered workdir (so the all-idle coverage predicate
     * can pass). Tests that want to probe registered-workdir coverage
     * explicitly construct their own [StatusSnapshot].
     */
    private fun snapshot(
        sessions: Map<String, Session>,
        registeredWorkdirs: Set<String> = sessions.values.map { it.directory }.toSet(),
    ): StatusSnapshot = StatusSnapshot(sessions, registeredWorkdirs)

    // ── (1) REST success: host statuses → composite keys via session.directory ──────────

    @Test
    fun `REST success maps returned active sessions to Busy and known-but-absent to Idle`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSessionStatus() } returns Result.success(
            mapOf(
                "s1" to SessionStatus(type = "busy"),
                "s2" to SessionStatus(type = "retry"),
            )
        )
        val sessionsById = mapOf(
            "s1" to session("s1", "/work-a"),
            "s2" to session("s2", "/work-b"),
            "s3" to session("s3", "/work-a"), // known-but-absent → Idle
        )
        val aggregator = newAggregator(repo, clock = { 100L })

        aggregator.refresh(identity(), snapshot(sessionsById))

        val statuses = aggregator.statusByKey.value
        assertEquals(SessionBusyStatus.Busy, statuses[key("s1", "/work-a")])
        assertEquals(SessionBusyStatus.Retry, statuses[key("s2", "/work-b")])
        assertEquals(SessionBusyStatus.Idle, statuses[key("s3", "/work-a")])
        assertEquals(3, statuses.size)
        assertTrue(aggregator.globalBusy.value)
    }

    @Test
    fun `D1 gate #5 - REST success with unmapped active id forces Busy (NOT ignored)`() = runTest {
        // D1 gate #5: a sessionId returned by /session/status that is NOT in
        // sessionsById is positively known active → contributes Busy. Pre-D1
        // this test asserted the ghost was ignored + global idle; D1 flips
        // the verdict to Busy (FGS spec §3 «returned-but-unmapped active IDs
        // force Busy»).
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSessionStatus() } returns Result.success(
            mapOf("ghost" to SessionStatus(type = "busy"))
        )
        val aggregator = newAggregator(repo, clock = { 100L })

        aggregator.refresh(
            identity(),
            snapshot(
                sessions = mapOf("s1" to session("s1", "/work")),
                registeredWorkdirs = setOf("/work"),
            ),
        )

        val statuses = aggregator.statusByKey.value
        assertFalse(
            "ghost id must not materialise a key (no workdir known)",
            statuses.any { it.key.sessionId == "ghost" },
        )
        assertEquals(SessionBusyStatus.Idle, statuses[key("s1", "/work")])
        // D1 gate #5: the unmapped active 'ghost' forces Busy.
        assertEquals(GlobalBusyState.Busy, aggregator.globalState.value)
        assertTrue(aggregator.globalBusy.value)
    }

    // ── (2) REST failure → Unknown; idle-grace guard ────────────────────────────────────

    @Test
    fun `REST failure labels every known session Unknown and globalState is Unknown`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSessionStatus() } returns Result.failure(java.io.IOException("boom"))
        val sessionsById = mapOf(
            "s1" to session("s1", "/work-a"),
            "s2" to session("s2", "/work-b"),
        )
        val aggregator = newAggregator(repo, clock = { 100L })

        aggregator.refresh(identity(), snapshot(sessionsById))

        val statuses = aggregator.statusByKey.value
        // Critical idle-grace guard: status is Unknown, NOT Idle. The lifecycle coordinator
        // distinguishes "fetch failed" (Unknown) from "authoritatively idle" (Idle) and must
        // not enter the idle grace window while any Unknown entry is present (FGS spec §3).
        assertEquals(SessionBusyStatus.Unknown, statuses[key("s1", "/work-a")])
        assertEquals(SessionBusyStatus.Unknown, statuses[key("s2", "/work-b")])
        assertFalse(aggregator.globalBusy.value)
        assertEquals(GlobalBusyState.Unknown, aggregator.globalState.value)
    }

    @Test
    fun `REST failure does not wrongly clear globalBusy when a fresher SSE Busy exists`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        var now = 0L
        val aggregator = newAggregator(repo, clock = { now })

        // SSE delivers Busy for s1 at t=150.
        aggregator.applySseStatus(key("s1", "/work"), SessionBusyStatus.Busy, sourceTimeMs = 150L)
        assertTrue(aggregator.globalBusy.value)

        // REST starts at t=100 (BEFORE the SSE update) and fails.
        coEvery { repo.getSessionStatus() } returns Result.failure(java.io.IOException("boom"))
        now = 100L
        aggregator.refresh(identity(), snapshot(mapOf("s1" to session("s1", "/work"))))

        // Merge timing (FGS spec §3.1): requestStartMs(100) < sourceTimeMs(150) → SSE Busy
        // survives the failed snapshot. globalBusy stays true — the failed REST did NOT
        // wrongly clear it, so the idle-grace window cannot engage on a failure.
        assertEquals(SessionBusyStatus.Busy, aggregator.statusByKey.value[key("s1", "/work")])
        assertTrue(aggregator.globalBusy.value)
        assertEquals(GlobalBusyState.Busy, aggregator.globalState.value)
    }

    // ── (3) Merge timing: SSE vs REST ordering ─────────────────────────────────────────

    @Test
    fun `merge timing - newer SSE status survives a REST snapshot whose requestStart predates it`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        // requestStartMs is captured from the clock at the very start of refresh. We model
        // "REST started at t=100, SSE Busy landed at t=150 while REST was in flight, REST
        // response (s1 absent = Idle) returns" — so requestStartMs(100) < sourceTimeMs(150)
        // and the SSE Busy MUST survive (FGS spec §3.1).
        val aggregator = newAggregator(repo, clock = { 100L })

        // SSE delivers Busy at t=150 (while REST is conceptually in flight).
        aggregator.applySseStatus(key("s1", "/work"), SessionBusyStatus.Busy, sourceTimeMs = 150L)

        coEvery { repo.getSessionStatus() } returns Result.success(emptyMap())
        aggregator.refresh(identity(), snapshot(mapOf("s1" to session("s1", "/work"))))

        assertEquals(SessionBusyStatus.Busy, aggregator.statusByKey.value[key("s1", "/work")])
        assertTrue(aggregator.globalBusy.value)
    }

    @Test
    fun `M7 concurrent SSE Busy during suspended REST idle preserves merge timing`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Result<Map<String, SessionStatus>>>()
        coEvery { repo.getSessionStatus() } coAnswers {
            entered.complete(Unit)
            response.await()
        }
        val aggregator = newAggregator(repo, clock = { 100L }, scope = backgroundScope)
        val refresh = async {
            aggregator.refresh(identity(), snapshot(mapOf("s1" to session("s1", "/work"))))
        }
        entered.await()

        aggregator.applySseStatus(key("s1", "/work"), SessionBusyStatus.Busy, 150L)
        response.complete(Result.success(emptyMap()))
        refresh.await()

        assertEquals(SessionBusyStatus.Busy, aggregator.statusByKey.value[key("s1", "/work")])
        assertEquals(GlobalBusyState.Busy, aggregator.stateAtNow())
    }

    @Test
    fun `M7 stateAtNow and statusByKey derive from one committed aggregate`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSessionStatus() } returns Result.success(emptyMap())
        val aggregator = newAggregator(repo, clock = { 100L })
        aggregator.refresh(identity(), snapshot(mapOf("s1" to session("s1", "/work"))))
        aggregator.applySseStatus(key("s1", "/work"), SessionBusyStatus.Retry, 101L)

        assertEquals(SessionBusyStatus.Retry, aggregator.statusByKey.value[key("s1", "/work")])
        assertEquals(GlobalBusyState.Busy, aggregator.stateAtNow())
        assertEquals(GlobalBusyState.Busy, aggregator.globalState.value)
    }

    @Test
    fun `M7 concurrent REST completions commit entries and coverage as one aggregate`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        val first = CompletableDeferred<Result<Map<String, SessionStatus>>>()
        val second = CompletableDeferred<Result<Map<String, SessionStatus>>>()
        val calls = AtomicInteger()
        coEvery { repo.getSessionStatus() } coAnswers {
            if (calls.getAndIncrement() == 0) first.await() else second.await()
        }
        val ticks = AtomicInteger(100)
        val aggregator = newAggregator(repo, clock = { ticks.getAndIncrement().toLong() }, scope = backgroundScope)
        val a = async {
            aggregator.refresh(
                identity(groupFp = "group-a"),
                snapshot(mapOf("a" to session("a", "/a")), setOf("/a", "/zero-a")),
            )
        }
        val b = async {
            aggregator.refresh(
                identity(groupFp = "group-b"),
                snapshot(mapOf("b" to session("b", "/b")), setOf("/b", "/zero-b")),
            )
        }
        while (calls.get() < 2) kotlinx.coroutines.yield()
        second.complete(Result.success(emptyMap()))
        b.await()
        first.complete(Result.success(mapOf("a" to SessionStatus(type = "busy"))))
        a.await()

        assertEquals(SessionBusyStatus.Busy, aggregator.statusByKey.value[key("a", "/a", "group-a")])
        assertEquals(GlobalBusyState.Busy, aggregator.stateAtNow())
        assertEquals(GlobalBusyState.Busy, aggregator.globalState.value)
    }

    @Test
    fun `merge timing - REST snapshot whose requestStart is newer overwrites the older SSE status`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        var now = 0L
        val aggregator = newAggregator(repo, clock = { now })

        // SSE delivers Busy at t=100.
        aggregator.applySseStatus(key("s1", "/work"), SessionBusyStatus.Busy, sourceTimeMs = 100L)

        // REST starts at t=200 (AFTER the SSE frame) and returns s1 absent (= Idle).
        // requestStartMs=200 >= sourceTimeMs=100 → REST Idle overwrites the older SSE Busy.
        now = 200L
        coEvery { repo.getSessionStatus() } returns Result.success(emptyMap())
        aggregator.refresh(identity(), snapshot(mapOf("s1" to session("s1", "/work"))))

        assertEquals(SessionBusyStatus.Idle, aggregator.statusByKey.value[key("s1", "/work")])
        assertFalse(aggregator.globalBusy.value)
    }

    @Test
    fun `merge timing - older SSE frame is dropped`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        var now = 100L
        val aggregator = newAggregator(repo, clock = { now })

        coEvery { repo.getSessionStatus() } returns Result.success(mapOf("s1" to SessionStatus(type = "busy")))
        aggregator.refresh(identity(), snapshot(mapOf("s1" to session("s1", "/work"))))
        assertEquals(SessionBusyStatus.Busy, aggregator.statusByKey.value[key("s1", "/work")])

        // A stale SSE frame (older source time) must NOT overwrite the fresher REST entry.
        aggregator.applySseStatus(key("s1", "/work"), SessionBusyStatus.Idle, sourceTimeMs = 50L)
        assertEquals(SessionBusyStatus.Busy, aggregator.statusByKey.value[key("s1", "/work")])
        assertTrue(aggregator.globalBusy.value)
    }

    // ── (4) globalBusy: identity scoping + Busy/Retry projection ───────────────────────

    @Test
    fun `globalBusy true iff any Busy or Retry entry exists under the current identity`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSessionStatus() } returns Result.success(
            mapOf("s1" to SessionStatus(type = "busy"))
        )
        val aggregator = newAggregator(repo, clock = { 100L })

        aggregator.refresh(identity(), snapshot(mapOf("s1" to session("s1", "/work"))))
        assertTrue(aggregator.globalBusy.value)

        // SSE flips s1 to Idle → globalBusy must fall.
        aggregator.applySseStatus(key("s1", "/work"), SessionBusyStatus.Idle, sourceTimeMs = 200L)
        assertFalse(aggregator.globalBusy.value)

        // SSE flips s2 (different workdir) to Retry → globalBusy must rise again.
        aggregator.applySseStatus(key("s2", "/other"), SessionBusyStatus.Retry, sourceTimeMs = 300L)
        assertTrue(aggregator.globalBusy.value)
    }

    @Test
    fun `globalBusy ignores stale entries from a different serverGroupFp`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSessionStatus() } returns Result.success(
            mapOf("s1" to SessionStatus(type = "busy"))
        )
        val aggregator = newAggregator(repo, clock = { 100L })

        // First identity observes s1 busy.
        aggregator.refresh(identity(groupFp = fp), snapshot(mapOf("s1" to session("s1", "/work"))))
        assertTrue(aggregator.globalBusy.value)

        // Host switch: refresh under a new serverGroupFp with no active sessions. globalBusy
        // must scope to the new identity and ignore the stale entry from the old group.
        coEvery { repo.getSessionStatus() } returns Result.success(emptyMap())
        aggregator.refresh(identity(groupFp = "host-group-B"), snapshot(mapOf("s1" to session("s1", "/work"))))
        assertFalse(aggregator.globalBusy.value)

        // The stale group-A Busy entry is still in statusByKey (not purged here — Lane C
        // owns stale-host purge per spec §2 step 4) but does NOT affect globalBusy.
        val statuses = aggregator.statusByKey.value
        assertEquals(2, statuses.size)
        assertTrue(statuses.any { it.key.serverGroupFp == fp && it.value == SessionBusyStatus.Busy })
    }

    // ── (5) CP4 tri-state globalState ──────────────────────────────────────────────────

    @Test
    fun `globalState is Unknown before any refresh on an empty aggregator`() {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        val aggregator = newAggregator(repo)
        assertEquals(GlobalBusyState.Unknown, aggregator.globalState.value)
    }

    @Test
    fun `globalState is Busy when any session is Busy or Retry`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSessionStatus() } returns Result.success(
            mapOf("s1" to SessionStatus(type = "busy"))
        )
        val aggregator = newAggregator(repo, clock = { 100L })

        aggregator.refresh(identity(), snapshot(mapOf("s1" to session("s1", "/work"))))
        assertEquals(GlobalBusyState.Busy, aggregator.globalState.value)
    }

    @Test
    fun `globalState is AllIdleFresh when all sessions are fresh Idle`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSessionStatus() } returns Result.success(emptyMap())
        val aggregator = newAggregator(repo, clock = { 100L })

        aggregator.refresh(
            identity(),
            snapshot(
                mapOf(
                    "s1" to session("s1", "/work-a"),
                    "s2" to session("s2", "/work-b"),
                ),
            ),
        )
        assertEquals(GlobalBusyState.AllIdleFresh, aggregator.globalState.value)
    }

    @Test
    fun `globalState is Unknown after a failure (NOT AllIdleFresh)`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSessionStatus() } returns Result.failure(java.io.IOException("boom"))
        val aggregator = newAggregator(repo, clock = { 100L })

        aggregator.refresh(identity(), snapshot(mapOf("s1" to session("s1", "/work"))))
        assertEquals(GlobalBusyState.Unknown, aggregator.globalState.value)
    }

    // ── (6) CP4 TTL: stale entries fall back to Unknown (for idle) ─────────────────────

    @Test
    fun `TTL - fresh Idle within 30s is AllIdleFresh`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSessionStatus() } returns Result.success(emptyMap())
        var now = 1_000L
        val aggregator = newAggregator(repo, clock = { now })

        aggregator.refresh(identity(), snapshot(mapOf("s1" to session("s1", "/work"))))
        assertEquals(GlobalBusyState.AllIdleFresh, aggregator.globalState.value)

        // Within the TTL window: still AllIdleFresh.
        now = 1_000L + StatusAggregatorImpl.STATUS_TTL_MS - 1
        // Force recompute by re-applying the same SSE status (a no-op write that triggers recompute).
        aggregator.applySseStatus(key("s1", "/work"), SessionBusyStatus.Idle, sourceTimeMs = 1_000L)
        assertEquals(GlobalBusyState.AllIdleFresh, aggregator.globalState.value)
    }

    @Test
    fun `TTL - Idle entry older than 30s flips globalState to Unknown (not authoritative idle)`() = runTest {
        // D1 (gate #1): pre-D1 this test masked the passive-TTL bug by
        // FORCING a recompute via an SSE write after time advanced — so the
        // bug (no autonomous expiry) was invisible. D1 removes the forcing
        // write and asserts the autonomous expiry: advancing wall-clock past
        // STATUS_TTL_MS with NO write at all MUST flip globalState to Unknown
        // via the scheduled freshnessJob.
        var now = 1_000L
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSessionStatus() } returns Result.success(emptyMap())
        val aggregator = StatusAggregatorImpl(
            repo,
            ConnectionIdentityStore().also { it.bind(fp, "/work", "endpoint-A") },
            backgroundScope,
            clock = { now },
        )

        aggregator.refresh(identity(), snapshot(mapOf("s1" to session("s1", "/work"))))
        assertEquals(GlobalBusyState.AllIdleFresh, aggregator.globalState.value)

        // Cross the TTL boundary with NO subsequent write — the freshnessJob
        // must autonomously fire at sourceTime + TTL + 1.
        now = 1_000L + StatusAggregatorImpl.STATUS_TTL_MS + 1
        advanceTimeBy(StatusAggregatorImpl.STATUS_TTL_MS + 2)
        runCurrent()

        assertEquals(
            "D1 gate #1: stale Idle autonomously expires to Unknown (no forcing write)",
            GlobalBusyState.Unknown,
            aggregator.globalState.value,
        )
    }

    @Test
    fun `D1 gate #1 - fresh REST idle autonomously expires to Unknown without any write`() = runTest {
        // The canonical D1 acceptance test: a fresh REST idle at t=0 must
        // flip to Unknown purely via wall-clock advance, with no map mutation
        // after t=0.
        var now = 0L
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSessionStatus() } returns Result.success(emptyMap())
        val aggregator = StatusAggregatorImpl(
            repo,
            ConnectionIdentityStore().also { it.bind(fp, "/work", "endpoint-A") },
            backgroundScope,
            clock = { now },
        )

        aggregator.refresh(identity(), snapshot(mapOf("s1" to session("s1", "/work"))))
        assertEquals(GlobalBusyState.AllIdleFresh, aggregator.globalState.value)

        now = StatusAggregatorImpl.STATUS_TTL_MS + 1
        advanceTimeBy(StatusAggregatorImpl.STATUS_TTL_MS + 2)
        runCurrent()

        assertEquals(GlobalBusyState.Unknown, aggregator.globalState.value)
    }

    @Test
    fun `D1 gate #1 - stateAtNow reads time-correct state independent of globalState cache`() = runTest {
        // The stateAtNow contract: it MUST reflect the time-correct verdict
        // even before the passive freshnessJob recompute has landed.
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        var now = 1_000L
        val aggregator = newAggregator(repo, clock = { now })
        coEvery { repo.getSessionStatus() } returns Result.success(emptyMap())

        aggregator.refresh(identity(), snapshot(mapOf("s1" to session("s1", "/work"))))
        assertEquals(GlobalBusyState.AllIdleFresh, aggregator.globalState.value)
        assertEquals(GlobalBusyState.AllIdleFresh, aggregator.stateAtNow())

        // Cross the TTL boundary WITHOUT advancing the dispatcher (so
        // globalState.value is still cached at AllIdleFresh). stateAtNow
        // reads time-correct verdict = Unknown.
        now = 1_000L + StatusAggregatorImpl.STATUS_TTL_MS + 1
        assertEquals(
            "globalState cache lags wall clock at the instant of the call",
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
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSessionStatus() } returns Result.success(
            mapOf("s1" to SessionStatus(type = "busy"))
        )
        var now = 1_000L
        val aggregator = newAggregator(repo, clock = { now })

        aggregator.refresh(identity(), snapshot(mapOf("s1" to session("s1", "/work"))))
        assertEquals(GlobalBusyState.Busy, aggregator.globalState.value)

        // Way past the TTL — a stale Busy stays Busy. The alternative (treating a stale Busy
        // as Unknown) is the SAME keep-alive verdict anyway, but Busy is the more accurate
        // label and never silently clears keep-alive on a possibly-still-busy session.
        now = 1_000L + StatusAggregatorImpl.STATUS_TTL_MS * 10
        aggregator.applySseStatus(key("s1", "/work"), SessionBusyStatus.Busy, sourceTimeMs = 1_000L)
        assertEquals(GlobalBusyState.Busy, aggregator.globalState.value)
    }

    // ── (7) CP4 REST epoch guard ───────────────────────────────────────────────────────

    @Test
    fun `epoch guard - REST response after an epoch bump is dropped`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        val store = ConnectionIdentityStore()
        store.bind(fp, "/work", "endpoint-A")
        val originalEpoch = store.currentEpoch()
        // Capture requestStartEpoch BEFORE the bump; the response returns AFTER.
        coEvery { repo.getSessionStatus() } answers {
            // Simulate a reconfigure landing WHILE the REST call is in flight.
            store.beginReconfigure()
            store.bind(fp, "/work", "endpoint-A") // new epoch, same fp
            Result.success(mapOf("s1" to SessionStatus(type = "busy")))
        }
        val aggregator = StatusAggregatorImpl(
            repo, store,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined),
            clock = { 100L },
        )

        aggregator.refresh(identity(epoch = originalEpoch), snapshot(mapOf("s1" to session("s1", "/work"))))

        // The stale-epoch response MUST be dropped — the (would-be) Busy entry never lands.
        assertTrue(aggregator.statusByKey.value.isEmpty())
        assertEquals(GlobalBusyState.Unknown, aggregator.globalState.value)
        assertFalse(aggregator.globalBusy.value)
    }

    @Test
    fun `epoch guard - REST response with unchanged epoch commits normally`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSessionStatus() } returns Result.success(
            mapOf("s1" to SessionStatus(type = "busy"))
        )
        val aggregator = newAggregator(repo, clock = { 100L })

        aggregator.refresh(identity(), snapshot(mapOf("s1" to session("s1", "/work"))))

        assertEquals(SessionBusyStatus.Busy, aggregator.statusByKey.value[key("s1", "/work")])
        assertEquals(GlobalBusyState.Busy, aggregator.globalState.value)
    }

    // ── (8) CP4 explicit markRequestFailed ─────────────────────────────────────────────

    @Test
    fun `markRequestFailed labels every known session Unknown without going through refresh`() {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        val aggregator = newAggregator(repo, clock = { 100L })

        aggregator.markRequestFailed(
            identity(),
            snapshot(
                mapOf(
                    "s1" to session("s1", "/work-a"),
                    "s2" to session("s2", "/work-b"),
                ),
            ),
            sourceTimeMs = 100L,
        )

        val statuses = aggregator.statusByKey.value
        assertEquals(SessionBusyStatus.Unknown, statuses[key("s1", "/work-a")])
        assertEquals(SessionBusyStatus.Unknown, statuses[key("s2", "/work-b")])
        assertEquals(GlobalBusyState.Unknown, aggregator.globalState.value)
    }

    @Test
    fun `markRequestFailed preserves a fresher prior Busy via merge timing`() {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        val aggregator = newAggregator(repo, clock = { 0L })

        // SSE delivers Busy at t=200.
        aggregator.applySseStatus(key("s1", "/work"), SessionBusyStatus.Busy, sourceTimeMs = 200L)

        // markRequestFailed at t=100 (older) — must NOT clobber the fresher Busy.
        aggregator.markRequestFailed(
            identity(),
            snapshot(mapOf("s1" to session("s1", "/work"))),
            sourceTimeMs = 100L,
        )

        assertEquals(SessionBusyStatus.Busy, aggregator.statusByKey.value[key("s1", "/work")])
        assertEquals(GlobalBusyState.Busy, aggregator.globalState.value)
    }

    // ── (9) D1 gate #5: unmapped-active→Busy + registered-workdir coverage ────

    @Test
    fun `D1 gate #5 - ghost busy plus known idle session forces global Busy`() = runTest {
        // Response contains ghost=busy + s1 absent (→ Idle), s1 is mapped.
        // globalState must be Busy (never AllIdleFresh) — the ghost is
        // positively known active even though it has no workdir mapping.
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSessionStatus() } returns Result.success(
            mapOf("ghost" to SessionStatus(type = "busy"))
        )
        val aggregator = newAggregator(repo, clock = { 100L })

        aggregator.refresh(
            identity(),
            snapshot(
                sessions = mapOf("s1" to session("s1", "/work")),
                registeredWorkdirs = setOf("/work"),
            ),
        )

        assertEquals(GlobalBusyState.Busy, aggregator.globalState.value)
        assertTrue(aggregator.globalBusy.value)
    }

    @Test
    fun `D1 gate #5 - all active ids map plus all workdirs covered is correct Busy`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSessionStatus() } returns Result.success(
            mapOf(
                "s1" to SessionStatus(type = "busy"),
                "s2" to SessionStatus(type = "retry"),
            )
        )
        val aggregator = newAggregator(repo, clock = { 100L })

        aggregator.refresh(
            identity(),
            snapshot(
                sessions = mapOf(
                    "s1" to session("s1", "/work-a"),
                    "s2" to session("s2", "/work-b"),
                ),
                registeredWorkdirs = setOf("/work-a", "/work-b"),
            ),
        )

        assertEquals(GlobalBusyState.Busy, aggregator.globalState.value)
    }

    @Test
    fun `M6 host-global success covers registered workdir without sessions`() = runTest {
        // The endpoint is host-global: a successful snapshot covers every
        // registered workdir, including /work-b with zero known sessions.
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSessionStatus() } returns Result.success(emptyMap())
        val aggregator = newAggregator(repo, clock = { 100L })

        aggregator.refresh(
            identity(),
            snapshot(
                sessions = mapOf("s1" to session("s1", "/work-a")),
                registeredWorkdirs = setOf("/work-a", "/work-b"),
            ),
        )

        assertEquals(
            "host-global snapshot marker covers zero-session /work-b",
            GlobalBusyState.AllIdleFresh,
            aggregator.globalState.value,
        )
    }

    @Test
    fun `D1 gate #5 - fresh successful empty host snapshot with no workdirs is authoritative idle`() = runTest {
        // A successful REST snapshot returning empty AND zero registered
        // workdirs (genuinely idle host) is AllIdleFresh — backed by the
        // fresh coverage marker, NOT vacuous uninitialized state.
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSessionStatus() } returns Result.success(emptyMap())
        val aggregator = newAggregator(repo, clock = { 100L })

        aggregator.refresh(
            identity(),
            snapshot(sessions = emptyMap(), registeredWorkdirs = emptySet()),
        )

        assertEquals(GlobalBusyState.AllIdleFresh, aggregator.globalState.value)
    }

    @Test
    fun `M6 empty host-global coverage marker expires to Unknown`() = runTest {
        var now = 0L
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSessionStatus() } returns Result.success(emptyMap())
        val aggregator = newAggregator(repo, clock = { now }, scope = backgroundScope)
        aggregator.refresh(identity(), snapshot(emptyMap(), setOf("/zero-session")))
        assertEquals(GlobalBusyState.AllIdleFresh, aggregator.globalState.value)

        now = StatusAggregatorImpl.STATUS_TTL_MS + 1
        advanceTimeBy(StatusAggregatorImpl.STATUS_TTL_MS + 2)
        runCurrent()

        assertEquals(GlobalBusyState.Unknown, aggregator.globalState.value)
        assertEquals(GlobalBusyState.Unknown, aggregator.stateAtNow())
    }

    @Test
    fun `D1 gate #5 - cold-start empty aggregator is Unknown (not vacuous idle)`() {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        val aggregator = newAggregator(repo)
        // Never refreshed → cold-start guard refuses AllIdleFresh.
        assertEquals(GlobalBusyState.Unknown, aggregator.globalState.value)
        assertEquals(GlobalBusyState.Unknown, aggregator.stateAtNow())
    }
}
