package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.SessionRepository
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.data.repository.StatusOutcome
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.status.GlobalBusyState
import cn.vectory.ocdroid.service.status.SessionBusyStatus
import cn.vectory.ocdroid.service.status.SessionStatusKey
import cn.vectory.ocdroid.service.status.SlimStatusFanOut
import cn.vectory.ocdroid.service.status.StatusFanOutSummary
import cn.vectory.ocdroid.service.status.StatusSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * P0 (B-slim-storm-fix): verifies the per-session status fan-out gate
 * ([ServerCompatProfile.slimPerSessionStatusEndpointAvailable]) short-circuits
 * the runner when [lite-v2-dev] delegates per-session queries to the bulk
 * endpoint.
 *
 * # Gate predicate (flag = false)
 * The runner returns null → [SlimFanOutRetryScheduler.runSlimFanOut] skips the
 * summary sink → zero calls to [OpenCodeRepository.getSlimapiSessionStatusOutcome].
 * Status data flow stays fully covered by bulk runRefresh; stale cleanup is
 * driven independently by /since 404 MarkDeleted + session.deleted SSE.
 *
 * # Gate open (flag = true) — future-probe documentation
 * When the flag is true, the runner proceeds to [SlimStatusFanOut.checkSlimSessionsStatuses]
 * which issues per-sid GETs. This case documents the re-enable behavior and
 * guards against accidentally inverting the gate.
 *
 * Both cases exercise the runner lambda directly through the scheduler's
 * [SlimFanOutRetryScheduler.requestSlimFanOutRetry] path so the full trigger chain
 * (retry → runSlimFanOut → runner → sink / no-sink) is covered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SlimFanOutRunnerGateTest {

    private val identity = ConnectionIdentity(
        epoch = 1L,
        profileId = "group-fp",
        normalizedWorkdir = "/work/dir",
        endpointFp = "endpoint-fp",
    )

    @Test
    fun `gate blocks per-session calls when slimPerSessionStatusEndpointAvailable is false`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val store = ConnectionIdentityStore()
        bindIdentity(store)

        val repo = mockk<SessionRepository>(relaxed = true)
        // Stub the sids the runner will see so relaxed-mock doesn't cause issues.
        coEvery { repo.getSlimapiSessionStatusOutcome("sid-a") } returns
            StatusOutcome.Success("sid-a", SessionStatus(type = "idle"))
        val fanOut = SlimStatusFanOut(repo)

        val profile = ServerCompatProfile()
        // Default is already false, but set explicitly for readability.
        profile.slimPerSessionStatusEndpointAvailable = false

        val snapshot = StatusSnapshot(
            sessionsById = mapOf("sid-a" to Session(id = "sid-a", directory = "/p")),
            registeredWorkdirs = setOf("/p"),
        )
        val snapshotProvider = SessionSnapshotProvider { snapshot }

        // mirrors StreamingModule.provideSlimFanOutRetryScheduler slimFanOutRunner gate
        val runner: suspend (ConnectionIdentity, StatusSnapshot) -> StatusFanOutSummary? =
            runner@{ _, snap ->
                if (!profile.slimPerSessionStatusEndpointAvailable) return@runner null
                fanOut.checkSlimSessionsStatuses(
                    sids = snap.sessionsById.keys,
                    knownSessionIds = snap.sessionsById.keys,
                )
            }

        var sinkCount = 0
        val scheduler = SlimFanOutRetryScheduler(
            scope = appScope,
            snapshotProvider = snapshotProvider,
            identityStore = store,
            slimFanOutRunner = runner,
            slimFanOutSummarySink = { sinkCount++ },
        )

        // Drive via requestSlimFanOutRetry instead of the removed loop path.
        scheduler.requestSlimFanOutRetry(0L)
        runCurrent(appScope)

        // Gate is closed: runner returned null, sink never invoked.
        assertEquals("summary sink NOT invoked when flag is false", 0, sinkCount)
        coVerify(exactly = 0) { repo.getSlimapiSessionStatusOutcome(any()) }
    }

    @Test
    fun `gate allows per-session calls when slimPerSessionStatusEndpointAvailable is true`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val store = ConnectionIdentityStore()
        bindIdentity(store)

        val repo = mockk<SessionRepository>(relaxed = true)
        coEvery { repo.getSlimapiSessionStatusOutcome("sid-a") } returns
            StatusOutcome.Success("sid-a", SessionStatus(type = "idle"))
        val fanOut = SlimStatusFanOut(repo)

        val profile = ServerCompatProfile()
        // Open the gate: simulate future per-session endpoint availability.
        profile.slimPerSessionStatusEndpointAvailable = true

        val snapshot = StatusSnapshot(
            sessionsById = mapOf("sid-a" to Session(id = "sid-a", directory = "/p")),
            registeredWorkdirs = setOf("/p"),
        )
        val snapshotProvider = SessionSnapshotProvider { snapshot }

        // mirrors StreamingModule.provideSlimFanOutRetryScheduler slimFanOutRunner gate
        val runner: suspend (ConnectionIdentity, StatusSnapshot) -> StatusFanOutSummary? =
            runner@{ _, snap ->
                if (!profile.slimPerSessionStatusEndpointAvailable) return@runner null
                fanOut.checkSlimSessionsStatuses(
                    sids = snap.sessionsById.keys,
                    knownSessionIds = snap.sessionsById.keys,
                )
            }

        val summaries = mutableListOf<StatusFanOutSummary>()
        val scheduler = SlimFanOutRetryScheduler(
            scope = appScope,
            snapshotProvider = snapshotProvider,
            identityStore = store,
            slimFanOutRunner = runner,
            slimFanOutSummarySink = { summaries.add(it) },
        )

        // Drive via requestSlimFanOutRetry instead of the removed loop path.
        scheduler.requestSlimFanOutRetry(0L)
        runCurrent(appScope)

        // Gate is open: runner proceeded to fanOut, sink was invoked.
        assertEquals(
            "summary sink invoked when flag is true",
            1,
            summaries.size,
        )
        val summary = summaries.single()
        assertNotNull("perSid contains sid-a", summary.perSid["sid-a"])
        assertEquals("sid-a returned Success(idle)", 0, summary.retryableCount)
        coVerify(atLeast = 1) { repo.getSlimapiSessionStatusOutcome("sid-a") }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun runCurrent(scope: TestScope) = scope.testScheduler.runCurrent()

    private fun bindIdentity(store: ConnectionIdentityStore) {
        store.beginReconfigure()
        store.bind(identity.profileId, identity.normalizedWorkdir, identity.endpointFp)
    }
}
