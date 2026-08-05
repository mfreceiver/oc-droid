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
import cn.vectory.ocdroid.service.status.StatusAggregator
import cn.vectory.ocdroid.service.status.StatusAggregatorInput
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
 * The runner returns null → [ProcessStatusPoller.runSlimFanOut] skips the
 * summary sink → zero calls to [OpenCodeRepository.getSlimapiSessionStatusOutcome].
 * Status data flow stays fully covered by bulk runRefresh; stale cleanup is
 * driven independently by /since 404 MarkDeleted + session.deleted SSE.
 *
 * # Gate open (flag = true) — future-probe documentation
 * When the flag is true, the runner proceeds to [SlimStatusFanOut.checkSlimSessionsStatuses]
 * which issues per-sid GETs. This case documents the re-enable behavior and
 * guards against accidentally inverting the gate.
 *
 * Both cases exercise the runner lambda directly through the poller's
 * [ProcessStatusPoller.startAndAwaitFirstPoll] path so the full trigger chain
 * (immediate runSlimFanOut → runner → sink / no-sink) is covered.
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
        val input = FanOutGateRecordingInput(GlobalBusyState.AllIdleFresh)
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

        // mirrors StreamingModule.provideProcessStatusPoller slimFanOutRunner gate
        val runner: suspend (ConnectionIdentity, StatusSnapshot) -> StatusFanOutSummary? =
            runner@{ _, snap ->
                if (!profile.slimPerSessionStatusEndpointAvailable) return@runner null
                fanOut.checkSlimSessionsStatuses(
                    sids = snap.sessionsById.keys,
                    knownSessionIds = snap.sessionsById.keys,
                )
            }

        var sinkCount = 0
        val poller = ProcessStatusPoller(
            scope = appScope,
            statusAggregatorInput = input,
            snapshotProvider = snapshotProvider,
            identityStore = store,
            statusAggregator = input,
            clock = { 0L },
            slimFanOutRunner = runner,
            slimFanOutSummarySink = { sinkCount++ },
        )

        poller.startAndAwaitFirstPoll(identity, snapshot)
        runCurrent(appScope)

        // Gate is closed: runner returned null, sink never invoked.
        assertEquals("summary sink NOT invoked when flag is false", 0, sinkCount)
        coVerify(exactly = 0) { repo.getSlimapiSessionStatusOutcome(any()) }
    }

    @Test
    fun `gate allows per-session calls when slimPerSessionStatusEndpointAvailable is true`() = runTest {
        val appScope = TestScope(UnconfinedTestDispatcher())
        val input = FanOutGateRecordingInput(GlobalBusyState.AllIdleFresh)
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

        // mirrors StreamingModule.provideProcessStatusPoller slimFanOutRunner gate
        val runner: suspend (ConnectionIdentity, StatusSnapshot) -> StatusFanOutSummary? =
            runner@{ _, snap ->
                if (!profile.slimPerSessionStatusEndpointAvailable) return@runner null
                fanOut.checkSlimSessionsStatuses(
                    sids = snap.sessionsById.keys,
                    knownSessionIds = snap.sessionsById.keys,
                )
            }

        val summaries = mutableListOf<StatusFanOutSummary>()
        val poller = ProcessStatusPoller(
            scope = appScope,
            statusAggregatorInput = input,
            snapshotProvider = snapshotProvider,
            identityStore = store,
            statusAggregator = input,
            clock = { 0L },
            slimFanOutRunner = runner,
            slimFanOutSummarySink = { summaries.add(it) },
        )

        poller.startAndAwaitFirstPoll(identity, snapshot)
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

    /**
     * Minimal fake [StatusAggregator] / [StatusAggregatorInput] for the
     * slim fan-out gate tests. Records refresh calls without side effects
     * (no completion gate — the sweep runs unblocked). Mirrors the pattern
     * from [SlimFanOutPollerWiringTest.RecordingStatusInput] and
     * [ProcessStatusPollerTest.RecordingStatusInput].
     */
    private class FanOutGateRecordingInput(initial: GlobalBusyState) : StatusAggregator,
        StatusAggregatorInput {
        private val _globalState = MutableStateFlow(initial)
        override val globalState: StateFlow<GlobalBusyState> = _globalState.asStateFlow()
        override val globalBusy: StateFlow<Boolean> =
            MutableStateFlow(initial == GlobalBusyState.Busy).asStateFlow()
        override val statusByKey:
            StateFlow<Map<SessionStatusKey, SessionBusyStatus>> =
            MutableStateFlow<Map<SessionStatusKey, SessionBusyStatus>>(emptyMap()).asStateFlow()
        override fun stateAtNow(): GlobalBusyState = _globalState.value

        override suspend fun refresh(
            identity: ConnectionIdentity,
            snapshot: StatusSnapshot,
        ) {
            // No-op: the sweep runs unblocked.
        }

        override fun applySseStatus(
            key: SessionStatusKey,
            status: SessionBusyStatus,
            sourceTimeMs: Long,
        ) = Unit

        override fun markRequestFailed(
            identity: ConnectionIdentity,
            snapshot: StatusSnapshot,
            sourceTimeMs: Long,
        ) = Unit
    }
}
