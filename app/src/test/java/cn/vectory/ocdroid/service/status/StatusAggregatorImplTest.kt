package cn.vectory.ocdroid.service.status

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
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
    ): StatusAggregatorImpl = StatusAggregatorImpl(repository, identityStore, clock)

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

        aggregator.refresh(identity(), sessionsById)

        val statuses = aggregator.statusByKey.value
        assertEquals(SessionBusyStatus.Busy, statuses[key("s1", "/work-a")])
        assertEquals(SessionBusyStatus.Retry, statuses[key("s2", "/work-b")])
        assertEquals(SessionBusyStatus.Idle, statuses[key("s3", "/work-a")])
        assertEquals(3, statuses.size)
        assertTrue(aggregator.globalBusy.value)
    }

    @Test
    fun `REST success ignores server-returned ids not present in sessionsById`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSessionStatus() } returns Result.success(
            mapOf("ghost" to SessionStatus(type = "busy"))
        )
        val aggregator = newAggregator(repo, clock = { 100L })

        aggregator.refresh(identity(), sessionsById = mapOf("s1" to session("s1", "/work")))

        val statuses = aggregator.statusByKey.value
        assertFalse("ghost id must not materialise a key (no workdir known)", statuses.any { it.key.sessionId == "ghost" })
        assertEquals(SessionBusyStatus.Idle, statuses[key("s1", "/work")])
        assertFalse(aggregator.globalBusy.value)
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

        aggregator.refresh(identity(), sessionsById)

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
        aggregator.refresh(identity(), sessionsById = mapOf("s1" to session("s1", "/work")))

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
        aggregator.refresh(identity(), sessionsById = mapOf("s1" to session("s1", "/work")))

        assertEquals(SessionBusyStatus.Busy, aggregator.statusByKey.value[key("s1", "/work")])
        assertTrue(aggregator.globalBusy.value)
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
        aggregator.refresh(identity(), sessionsById = mapOf("s1" to session("s1", "/work")))

        assertEquals(SessionBusyStatus.Idle, aggregator.statusByKey.value[key("s1", "/work")])
        assertFalse(aggregator.globalBusy.value)
    }

    @Test
    fun `merge timing - older SSE frame is dropped`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        var now = 100L
        val aggregator = newAggregator(repo, clock = { now })

        coEvery { repo.getSessionStatus() } returns Result.success(mapOf("s1" to SessionStatus(type = "busy")))
        aggregator.refresh(identity(), sessionsById = mapOf("s1" to session("s1", "/work")))
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

        aggregator.refresh(identity(), sessionsById = mapOf("s1" to session("s1", "/work")))
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
        aggregator.refresh(identity(groupFp = fp), sessionsById = mapOf("s1" to session("s1", "/work")))
        assertTrue(aggregator.globalBusy.value)

        // Host switch: refresh under a new serverGroupFp with no active sessions. globalBusy
        // must scope to the new identity and ignore the stale entry from the old group.
        coEvery { repo.getSessionStatus() } returns Result.success(emptyMap())
        aggregator.refresh(identity(groupFp = "host-group-B"), sessionsById = mapOf("s1" to session("s1", "/work")))
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

        aggregator.refresh(identity(), sessionsById = mapOf("s1" to session("s1", "/work")))
        assertEquals(GlobalBusyState.Busy, aggregator.globalState.value)
    }

    @Test
    fun `globalState is AllIdleFresh when all sessions are fresh Idle`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSessionStatus() } returns Result.success(emptyMap())
        val aggregator = newAggregator(repo, clock = { 100L })

        aggregator.refresh(
            identity(),
            sessionsById = mapOf(
                "s1" to session("s1", "/work-a"),
                "s2" to session("s2", "/work-b"),
            ),
        )
        assertEquals(GlobalBusyState.AllIdleFresh, aggregator.globalState.value)
    }

    @Test
    fun `globalState is Unknown after a failure (NOT AllIdleFresh)`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSessionStatus() } returns Result.failure(java.io.IOException("boom"))
        val aggregator = newAggregator(repo, clock = { 100L })

        aggregator.refresh(identity(), sessionsById = mapOf("s1" to session("s1", "/work")))
        assertEquals(GlobalBusyState.Unknown, aggregator.globalState.value)
    }

    // ── (6) CP4 TTL: stale entries fall back to Unknown (for idle) ─────────────────────

    @Test
    fun `TTL - fresh Idle within 30s is AllIdleFresh`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSessionStatus() } returns Result.success(emptyMap())
        var now = 1_000L
        val aggregator = newAggregator(repo, clock = { now })

        aggregator.refresh(identity(), sessionsById = mapOf("s1" to session("s1", "/work")))
        assertEquals(GlobalBusyState.AllIdleFresh, aggregator.globalState.value)

        // Within the TTL window: still AllIdleFresh.
        now = 1_000L + StatusAggregatorImpl.STATUS_TTL_MS - 1
        // Force recompute by re-applying the same SSE status (a no-op write that triggers recompute).
        aggregator.applySseStatus(key("s1", "/work"), SessionBusyStatus.Idle, sourceTimeMs = 1_000L)
        assertEquals(GlobalBusyState.AllIdleFresh, aggregator.globalState.value)
    }

    @Test
    fun `TTL - Idle entry older than 30s flips globalState to Unknown (not authoritative idle)`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSessionStatus() } returns Result.success(emptyMap())
        var now = 1_000L
        val aggregator = newAggregator(repo, clock = { now })

        aggregator.refresh(identity(), sessionsById = mapOf("s1" to session("s1", "/work")))
        assertEquals(GlobalBusyState.AllIdleFresh, aggregator.globalState.value)

        // Cross the TTL boundary: the stale Idle entry is no longer authoritative — globalState
        // MUST fall back to Unknown (do NOT enter idle grace on stale data, FGS spec §3).
        now = 1_000L + StatusAggregatorImpl.STATUS_TTL_MS + 1
        aggregator.applySseStatus(key("s1", "/work"), SessionBusyStatus.Idle, sourceTimeMs = 1_000L)
        assertEquals(GlobalBusyState.Unknown, aggregator.globalState.value)
    }

    @Test
    fun `TTL - stale Busy entry stays Busy (conservative - never silently drop keep-alive)`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSessionStatus() } returns Result.success(
            mapOf("s1" to SessionStatus(type = "busy"))
        )
        var now = 1_000L
        val aggregator = newAggregator(repo, clock = { now })

        aggregator.refresh(identity(), sessionsById = mapOf("s1" to session("s1", "/work")))
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
        val aggregator = StatusAggregatorImpl(repo, store, clock = { 100L })

        aggregator.refresh(identity(epoch = originalEpoch), sessionsById = mapOf("s1" to session("s1", "/work")))

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

        aggregator.refresh(identity(), sessionsById = mapOf("s1" to session("s1", "/work")))

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
            sessionsById = mapOf(
                "s1" to session("s1", "/work-a"),
                "s2" to session("s2", "/work-b"),
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
            sessionsById = mapOf("s1" to session("s1", "/work")),
            sourceTimeMs = 100L,
        )

        assertEquals(SessionBusyStatus.Busy, aggregator.statusByKey.value[key("s1", "/work")])
        assertEquals(GlobalBusyState.Busy, aggregator.globalState.value)
    }
}
