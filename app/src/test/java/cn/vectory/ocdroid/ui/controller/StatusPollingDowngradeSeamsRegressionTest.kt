package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.service.streaming.ProcessStatusPoller
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-R1 round-2 — STATUS POLLING DOWNGRADE *new-seam* regression locks.
 *
 * Round-1 (`StatusPollingDowngradeRegressionTest.kt`, FROZEN — do not modify)
 * locked the slim-mode *negatives* (foreground status load must NOT hit the
 * legacy `/session/status` + `/api/session/active` endpoints) plus the legacy
 * floor + digest relay characterization. Round-1 flagged 3 design gaps.
 *
 * The round-1 impl lane CLOSED those gaps by adding:
 *  1. Bulk Retrofit `OpenCodeApi.getSlimapiSessionsStatus(directory)` (`/slimapi/sessions/status`).
 *  2. Repo facade `OpenCodeRepository.getSlimapiSessionsStatus(directory)`.
 *  3. Slim foreground cold-start `launchLoadSessionStatusSlim` (branched in
 *     `launchLoadSessionStatus` on `repository.isSlimMode`).
 *  4. Disconnect fallback: `StatusAggregatorImpl.refresh()` slim branch routes
 *     per registered workdir through the slim bulk endpoint (covered in
 *     `StatusAggregatorImplTest`); cadence = `ProcessStatusPoller.DEFAULT_INTERVAL_MS`.
 *
 * This file locks the *positives* of those seams — the behaviour round-1 could
 * only describe as gaps. These are REGRESSION tests: GREEN against the current
 * impl, and must stay GREEN so future changes cannot silently regress the
 * slim cold-start routing or the disconnect-fallback cadence band.
 *
 * C3: nothing here expects slim SSE to carry `message.part.*`. The slim status
 * source locked here is the REST cold-start bulk endpoint only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusPollingDowngradeSeamsRegressionTest {

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
    }

    private fun seedSessions(vararg sessions: Session) {
        store.mutateSessionList {
            it.copy(
                sessions = sessions.toList(),
                completeRootIds = sessions.filter { s -> s.parentId == null }
                    .mapTo(mutableSetOf()) { s -> s.id },
            )
        }
    }

    private fun session(id: String, directory: String): Session =
        Session(id = id, directory = directory)

    // ═══════════════════════════════════════════════════════════════════════
    // Slim foreground cold-start — `launchLoadSessionStatus` slim branch
    // (`launchLoadSessionStatusSlim`). Round-1 locked that legacy endpoints are
    // NOT hit; these lock the POSITIVE routing + fold behaviour.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `slim cold-start calls bulk slim endpoint and avoids both legacy endpoints`() = runTest {
        val repository = slimRepository()
        seedSessions(session("s1", "/work-a"))
        coEvery { repository.getSlimapiSessionsStatus(any()) } returns Result.success(
            mapOf("s1" to SessionStatus(type = "busy"))
        )
        // Defensive stubs — the assertions prove these are NOT called.
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        coEvery { repository.getActiveSessionIds() } returns Result.success(emptySet())

        launchLoadSessionStatus(scope, repository, slices)
        advanceUntilIdle()

        // POSITIVE: slim routes through the bulk slim endpoint.
        coVerify(atLeast = 1) { repository.getSlimapiSessionsStatus(any()) }
        // NEGATIVE (re-locks round-1 contract at the seam): legacy endpoints untouched.
        coVerify(exactly = 0) { repository.getSessionStatus() }
        coVerify(exactly = 0) { repository.getActiveSessionIds() }
    }

    @Test
    fun `slim cold-start folds per-directory bulk results into sessionStatuses`() = runTest {
        // Two directories → the sidecar requires one bulk call per directory;
        // the slim helper merges the per-directory maps. s3 is authoritative but
        // absent from both responses → normalize fills it as idle (mirrors the
        // legacy `/session/status` omits-idle semantics).
        val repository = slimRepository()
        seedSessions(
            session("s1", "/work-a"),
            session("s2", "/work-b"),
            session("s3", "/work-a"),
        )
        coEvery { repository.getSlimapiSessionsStatus("/work-a") } returns Result.success(
            mapOf("s1" to SessionStatus(type = "busy"))
        )
        coEvery { repository.getSlimapiSessionsStatus("/work-b") } returns Result.success(
            mapOf("s2" to SessionStatus(type = "retry"))
        )

        launchLoadSessionStatus(scope, repository, slices)
        advanceUntilIdle()

        val statuses = store.sessionListFlow.value.sessionStatuses
        assertEquals("s1 folded from /work-a bulk result", "busy", statuses["s1"]?.type)
        assertEquals("s2 folded from /work-b bulk result", "retry", statuses["s2"]?.type)
        assertEquals(
            "s3 authoritative-but-absent normalizes to idle",
            "idle",
            statuses["s3"]?.type,
        )
    }

    @Test
    fun `slim cold-start with no known directories is a no-op success and skips the bulk endpoint`() =
        runTest {
            // Before the session list loads there are no directories to query.
            // The slim helper must short-circuit to a success without issuing
            // any HTTP (the digest relay + later sweeps cover status once
            // sessions arrive).
            val repository = slimRepository()
            // No sessions seeded → authoritative map empty → directories empty.
            coEvery { repository.getSlimapiSessionsStatus(any()) } returns Result.success(emptyMap())
            val completions = mutableListOf<Boolean>()

            launchLoadSessionStatus(scope, repository, slices, completions::add)
            advanceUntilIdle()

            coVerify(exactly = 0) { repository.getSlimapiSessionsStatus(any()) }
            assertEquals("no-dir slim load completes success", listOf(true), completions)
            assertTrue(
                "sessionStatuses untouched on no-dir slim load",
                store.sessionListFlow.value.sessionStatuses.isEmpty(),
            )
        }

    @Test
    fun `slim cold-start tolerates per-directory failure and folds the successful directories`() =
        runTest {
            // One directory fetch fails, the other succeeds → the slim helper
            // must NOT poison the whole sweep; it folds whatever succeeded
            // (the digest relay is the steady-state source, so partial success
            // is acceptable, total failure is handled by completion(false)).
            val repository = slimRepository()
            seedSessions(
                session("s1", "/work-a"),
                session("s2", "/work-b"),
            )
            coEvery { repository.getSlimapiSessionsStatus("/work-a") } returns Result.success(
                mapOf("s1" to SessionStatus(type = "busy"))
            )
            coEvery { repository.getSlimapiSessionsStatus("/work-b") } returns Result.failure(
                java.io.IOException("upstream unavailable")
            )

            launchLoadSessionStatus(scope, repository, slices)
            advanceUntilIdle()

            val statuses = store.sessionListFlow.value.sessionStatuses
            assertEquals(
                "successful /work-a result folded despite /work-b failure",
                "busy",
                statuses["s1"]?.type,
            )
        }

    // ═══════════════════════════════════════════════════════════════════════
    // Disconnect fallback cadence band. The slim L2Idle/L3 degraded fallback
    // is driven by ProcessStatusPoller at DEFAULT_INTERVAL_MS; T-R1 requires
    // the fallback cadence to sit in the 10–30s band (≥10s so it is a real
    // downgrade from the legacy 4s poll; ≤30s so it stays responsive).
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `T-R1 disconnect fallback cadence is in the 10s-30s band`() {
        val cadence = ProcessStatusPoller.DEFAULT_INTERVAL_MS
        assertTrue(
            "fallback cadence must be >= 10s (a real downgrade from legacy 4s): got $cadence",
            cadence >= 10_000L,
        )
        assertTrue(
            "fallback cadence must be <= 30s (stay responsive): got $cadence",
            cadence <= 30_000L,
        )
    }
}
