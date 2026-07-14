package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.RecordingStreamingServiceLauncher
import cn.vectory.ocdroid.data.api.CommandInfo
import cn.vectory.ocdroid.data.model.HealthResponse
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.ComposerState
import cn.vectory.ocdroid.ui.ConnectionPhase
import cn.vectory.ocdroid.ui.ConnectionState
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.FileState
import cn.vectory.ocdroid.ui.HostState
import cn.vectory.ocdroid.ui.SessionListState
import cn.vectory.ocdroid.ui.SettingsState
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.TrafficState
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.UnreadState
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * R-16 M4: independent unit test for [ConnectionCoordinator].
 *
 * Zero reflection — the coordinator is driven entirely through its public API
 * (testConnection / coldStartReconnect / loadInitialData / startSSE / cancelSse /
 * cancelSseForReconfigure) and asserted via the
 * [RecordingConnectionCoordinatorCallbacks] spy + direct StateFlow reads. The
 * 30s health-check throttle is deterministically driven via the injected
 * [clock]. Heavy service dependencies (OpenCodeRepository / SettingsManager) are
 * mockk stubs; AppState + SliceFlows + connectionFlow + settingsFlow are real so
 * the coordinator's writes are observable. Follows the
 * [HostProfileControllerTest] / [ForegroundCatchUpControllerTest] pattern.
 *
 * These cases are the behaviour-equivalence proof that the connect state machine
 * (30s throttle, exponential-backoff retry, healthy/unhealthy/failure branches,
 * initial-data fan-out, SSE lifecycle) is byte-for-byte preserved from the
 * pre-extraction MainViewModel.testConnection.
 */
@Suppress("DEPRECATION")
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConnectionCoordinatorTest {

    // §R18 Phase 4 (P0-9): the controller takes the [SliceFlows] bundle only
    // (no separate connectionFlow / settingsFlow). Keep read-only StateFlow
    // views of those two slices so the existing assertions keep their
    // `.value.X` shape.
    private lateinit var connectionFlow: kotlinx.coroutines.flow.StateFlow<ConnectionState>
    private lateinit var settingsFlow: kotlinx.coroutines.flow.StateFlow<SettingsState>
    private lateinit var slices: SliceFlows
    private lateinit var repository: OpenCodeRepository
    private lateinit var settingsManager: SettingsManager
    private lateinit var effects: SharedEffectBus
    private lateinit var collectedEffects: MutableList<ControllerEffect>
    private lateinit var scope: TestScope
    private var now: Long = 0L
    private lateinit var coordinator: ConnectionCoordinator
    /** CP1 (notify Phase-0): the single connection-identity store. */
    private lateinit var identityStore: cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
    /** CP2 (notify Phase-0): the shared TOFU bootstrap coordinator. */
    private lateinit var bootstrapCoordinator: cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator
    /**
     * CP9 (notify Phase-0 switchover): the recording launcher. Tests assert
     * on its callCount instead of `repository.connectSSE` invocations after
     * the switchover (CC's startSSE now calls the launcher).
     */
    private lateinit var launcher: RecordingStreamingServiceLauncher
    /** §R-17 batch2 / §batch 3b: captures UiEvents emitted on effects.uiEvents. */
    private val recordedEvents = mutableListOf<UiEvent>()

    @Before
    fun setUp() {
        // launchSseCollection's catch block calls Log.e; loadPendingQuestions /
        // loadPendingPermissions inline paths now call Log.w on failure.
        mockkStatic(Log::class)
        io.mockk.every { Log.w(any<String>(), any<String>()) } returns 0
        val stateStore = cn.vectory.ocdroid.ui.SharedStateStore()
        slices = stateStore.slices
        connectionFlow = stateStore.connectionFlow
        settingsFlow = stateStore.settingsFlow
        repository = mockk(relaxed = true)
        settingsManager = mockk(relaxed = true)
        effects = SharedEffectBus()
        collectedEffects = mutableListOf()
        scope = TestScope(UnconfinedTestDispatcher())
        recordedEvents.clear()
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { effects.effectsConsumed.toList(collectedEffects) }
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { effects.uiEventsConsumed.toList(recordedEvents) }
        // Baseline clock well past the 30s throttle window from epoch 0 so the
        // first probe always proceeds (mirrors production's wall-clock ms).
        now = 100_000L
        identityStore = cn.vectory.ocdroid.service.identity.ConnectionIdentityStore()
        bootstrapCoordinator = cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator()
        launcher = RecordingStreamingServiceLauncher()
        coordinator = ConnectionCoordinator(
            scope = scope,
            slices = slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effects,
            serverCompatProfile = cn.vectory.ocdroid.data.repository.ServerCompatProfile(),
            clock = { now },
            identityStore = identityStore,
            // CP2: delegate TOFU state to the shared coordinator so the
            // delegation test can assert on bootstrapCoordinator.tofuState.
            bootstrapCoordinator = bootstrapCoordinator,
            // CP9: CC's startSSE now calls the launcher (atomic ownership
            // switch). cancelSse / cancelSseForReconfigure route through
            // streamingLifecycleCoordinator (null here — they are no-ops in
            // this test core; the cancelSseForReconfigure test asserts on
            // the HostReconfigured epoch bump which still fires).
            streamingServiceLauncher = launcher,
            streamingLifecycleCoordinator = null,
        )
        // Defaults: no directory fetch on loadInitialData (so the directory
        // coroutine — whose relaxed Result<List<Session>> mock would otherwise
        // throw and cancel the scope's non-supervisor Job, tearing down sibling
        // coroutines like startSSE's SSE collector) is not launched. Tests that
        // exercise the directory path re-stub both explicitly.
        every { settingsManager.currentWorkdir } returns null
        coEvery { repository.getSessionsForDirectory(any()) } returns Result.success(emptyList())
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun runPending() {
        scope.testScheduler.advanceUntilIdle()
    }

    // ── testConnection: 30s health-check throttle ──────────────────────────

    @Test
    fun `testConnection skips a second probe within the 30s throttle window`() {
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = false, version = "1.0"))

        coordinator.testConnection()
        coordinator.testConnection() // same clock tick → within 30s
        runPending()

        coVerify(exactly = 1) { repository.checkHealth() }
    }

    @Test
    fun `testConnection force bypasses the 30s throttle`() {
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = false, version = "1.0"))

        coordinator.testConnection()
        coordinator.testConnection(force = true) // bypass
        runPending()

        coVerify(exactly = 2) { repository.checkHealth() }
    }

    @Test
    fun `testConnection outside the 30s window probes again`() {
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = false, version = "1.0"))

        coordinator.testConnection()
        now += 30_001 // past the window
        coordinator.testConnection()
        runPending()

        coVerify(exactly = 2) { repository.checkHealth() }
    }

    @Test
    fun `testConnection no longer reconfigures the repository (callers pre-configure)`() {
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = true, version = "1.0"))

        coordinator.testConnection()
        runPending()

        // configureRepositoryForCurrentProfile must NOT fire from testConnection —
        // its reconfigure chain (cancelSseForReconfigure -> onHostReconfigured)
        // reset ForegroundCatchUpController.sseHasConnectedOnce and swallowed the
        // 15s-5min foreground gap catch-up. Callers configure the repo themselves.
        // §batch 3b: the method was removed entirely; assert no repository
        // reconfigure happened.
        verify(exactly = 0) { repository.configure(any(), any(), any(), any()) }
    }

    // ── testConnection: healthy / unhealthy / failure branches ──────────────

    @Test
    fun `testConnection on healthy sets connected phase and fans out initial data plus SSE`() {
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = true, version = "9.9"))
        coEvery { repository.getCommands() } returns Result.success(emptyList())

        coordinator.testConnection()
        runPending()

        assertTrue(connectionFlow.value.isConnected)
        assertFalse(connectionFlow.value.isConnecting)
        assertEquals(ConnectionPhase.Connected, connectionFlow.value.connectionPhase)
        assertEquals("9.9", connectionFlow.value.serverVersion)
        // Mirrors on AppState too.
        assertTrue(slices.connection.value.isConnected)
        assertEquals("9.9", slices.connection.value.serverVersion)
        // §batch 3b: every loader fans out via [ControllerEffect]s.
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.LoadSessions>().size)
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.LoadAgents>().size)
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.LoadProviders>().size)
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.LoadPendingQuestions>().size)
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.LoadPendingPermissions>().size)
        coVerify { repository.getCommands() }
        // CP9 §F27: SSE is now started via the launcher (atomic ownership
        // switch). The launcher is invoked EXACTLY ONCE on a healthy
        // connect; repository.connectSSE is invoked ZERO times (CC no
        // longer owns the collector).
        assertEquals(
            "launcher.ensureStarted invoked exactly once on healthy connect",
            1,
            launcher.callCount,
        )
        verify(exactly = 0) { repository.connectSSE(any()) }
    }

    @Test
    fun `testConnection unhealthy surfaces server version when present but stays connecting-retrying`() {
        // healthy=false, one shot (retries=0): the version is surfaced, then the
        // failure branch marks disconnected.
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = false, version = "1.2.3"))

        coordinator.testConnection()
        runPending()

        assertEquals("1.2.3", connectionFlow.value.serverVersion)
        assertFalse(connectionFlow.value.isConnected)
        assertFalse(connectionFlow.value.isConnecting)
        assertEquals(ConnectionPhase.Disconnected, connectionFlow.value.connectionPhase)
    }

    @Test
    fun `testConnection on failure surfaces the error message and disconnected phase`() {
        coEvery { repository.checkHealth() } returns Result.failure(IOException("network down"))

        coordinator.testConnection()
        runPending()

        assertFalse(connectionFlow.value.isConnected)
        assertEquals(ConnectionPhase.Disconnected, connectionFlow.value.connectionPhase)
        val errorEvent = recordedEvents.filterIsInstance<UiEvent.Error>().single()
        // §R18 Phase 2-G: UiEvent.Error now carries @StringRes resId + args.
        // Connection failure → R.string.error_connection_failed with the
        // resolved exception message as the single arg.
        assertEquals(R.string.error_connection_failed, errorEvent.resId)
        assertTrue(errorEvent.args.single().toString().contains("network down"))
    }

    @Test
    fun `testConnection unhealthy with retries exhausts the backoff loop then disconnects`() {
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = false, version = "1.0"))

        coordinator.testConnection(force = true, retries = 3) // 4 attempts total
        runPending()

        coVerify(exactly = 4) { repository.checkHealth() }
        assertEquals(ConnectionPhase.Disconnected, connectionFlow.value.connectionPhase)
        assertFalse(connectionFlow.value.isConnected)
    }

    @Test
    fun `testConnection on healthy clears the prior error`() {
        // §R-17 batch2: error is now a one-shot UiEvent — there's no persistent
        // error field to clear. This test stays as a regression guard: a healthy
        // connect must NOT emit a fresh UiEvent.Error (it used to clear the
        // legacy `state.error` field here).
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = true, version = "1.0"))
        coEvery { repository.getCommands() } returns Result.success(emptyList())

        coordinator.testConnection()
        runPending()

        assertTrue("healthy connect emits no error event", recordedEvents.filterIsInstance<UiEvent.Error>().isEmpty())
    }

    // ── R-20 Phase 3: daily cache sweep hook on healthy connect ─────────────

    @Test
    fun `testConnection on healthy fires the daily cache sweep against the current fp`() {
        // R-20 Phase 3 (plan §3): a healthy connect triggers fire-and-forget
        // CacheMaintenanceCoordinator.dailySweepIfNeeded(currentServerGroupFp()).
        // The coordinator does its own 24h dedup; we only verify the hook is
        // wired (the call fires + passes the current fp).
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = true, version = "1.0"))
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        val cacheMaintenance = mockk<cn.vectory.ocdroid.data.cache.CacheMaintenanceCoordinator>(relaxed = true)
        val sweepCoordinator = ConnectionCoordinator(
            scope = scope,
            slices = slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effects,
            serverCompatProfile = cn.vectory.ocdroid.data.repository.ServerCompatProfile(),
            cacheMaintenanceCoordinator = cacheMaintenance,
            currentServerGroupFp = { "fp-test" },
            clock = { now },
            streamingServiceLauncher = RecordingStreamingServiceLauncher(),
        )

        sweepCoordinator.testConnection()
        runPending()

        coVerify { cacheMaintenance.dailySweepIfNeeded("fp-test") }
    }

    @Test
    fun `testConnection on healthy does not crash when cacheMaintenanceCoordinator is null`() {
        // Pre-Phase-3 construction (or test paths) leave
        // cacheMaintenanceCoordinator null. The hook must be a no-op rather
        // than NPE — the connection itself must succeed.
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = true, version = "1.0"))
        coEvery { repository.getCommands() } returns Result.success(emptyList())

        coordinator.testConnection() // null cacheMaintenanceCoordinator
        runPending()

        assertTrue(connectionFlow.value.isConnected)
    }

    @Test
    fun `testConnection on healthy swallows daily-sweep failures`() {
        // The sweep is fire-and-forget — a failure (e.g. DB locked) MUST NOT
        // propagate up and break the connection's healthy state.
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = true, version = "1.0"))
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        val cacheMaintenance = mockk<cn.vectory.ocdroid.data.cache.CacheMaintenanceCoordinator>()
        coEvery { cacheMaintenance.dailySweepIfNeeded(any()) } throws java.io.IOException("db locked")
        val sweepCoordinator = ConnectionCoordinator(
            scope = scope,
            slices = slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effects,
            serverCompatProfile = cn.vectory.ocdroid.data.repository.ServerCompatProfile(),
            cacheMaintenanceCoordinator = cacheMaintenance,
            currentServerGroupFp = { "fp-test" },
            clock = { now },
            streamingServiceLauncher = RecordingStreamingServiceLauncher(),
        )

        sweepCoordinator.testConnection()
        runPending()

        assertTrue(
            "sweep failure does not break the healthy connect",
            connectionFlow.value.isConnected,
        )
        // The connection did NOT surface the sweep failure as a user-facing error.
        assertTrue(
            "sweep failure swallowed (no UiEvent.Error surfaced)",
            recordedEvents.filterIsInstance<UiEvent.Error>().isEmpty(),
        )
    }

    // ── coldStartReconnect ─────────────────────────────────────────────────

    @Test
    fun `coldStartReconnect forces a probe with 3 retries`() {
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = false, version = "1.0"))

        coordinator.coldStartReconnect()
        runPending()

        // force=true bypasses throttle; retries=3 → 4 attempts.
        coVerify(exactly = 4) { repository.checkHealth() }
    }

    // ── loadInitialData ────────────────────────────────────────────────────

    @Test
    fun `loadInitialData fans out to every loader and fetches slash commands`() {
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        every { settingsManager.currentWorkdir } returns null

        coordinator.loadInitialData()
        runPending()

        // §batch 3b: every loader is now a [ControllerEffect]; loadCommands
        // stays inline.
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.LoadSessions>().size)
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.LoadAgents>().size)
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.LoadProviders>().size)
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.LoadPendingQuestions>().size)
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.LoadPendingPermissions>().size)
        coVerify { repository.getCommands() }
    }

    @Test
    fun `loadInitialData merges server commands with the client-side local commands`() {
        coEvery { repository.getCommands() } returns Result.success(
            listOf(CommandInfo(name = "init", description = "server init"))
        )
        every { settingsManager.currentWorkdir } returns null

        coordinator.loadInitialData()
        runPending()

        val names = settingsFlow.value.availableCommands.map { it.name }.toSet()
        // Server command present.
        assertTrue("init" in names)
        // Client-side local commands appended (server did not publish them).
        assertTrue("clear" in names)
        assertTrue("compact" in names)
        assertTrue("undo" in names)
        assertTrue("redo" in names)
        // Mirrored onto AppState.
        assertEquals(settingsFlow.value.availableCommands, slices.settings.value.availableCommands)
    }

    @Test
    fun `loadCommands falls back to local commands when the server fetch fails`() {
        coEvery { repository.getCommands() } returns Result.failure(IOException("no /command endpoint"))
        every { settingsManager.currentWorkdir } returns null

        coordinator.loadInitialData()
        runPending()

        val names = settingsFlow.value.availableCommands.map { it.name }.toSet()
        assertEquals(setOf("clear", "compact", "undo", "redo"), names)
    }

    @Test
    fun `mergeCommands gives server commands precedence over local commands with the same name`() {
        coEvery { repository.getCommands() } returns Result.success(
            listOf(CommandInfo(name = "clear", description = "server-authoritative"))
        )
        every { settingsManager.currentWorkdir } returns null

        coordinator.loadInitialData()
        runPending()

        val clear = settingsFlow.value.availableCommands.single { it.name == "clear" }
        assertEquals("server-authoritative", clear.description)
        // Only one /clear entry (no duplicate from the local list).
        assertEquals(1, settingsFlow.value.availableCommands.count { it.name == "clear" })
    }

    @Test
    fun `loadInitialData refreshes directorySessions for the restored workdir`() {
        every { settingsManager.currentWorkdir } returns "/persisted/proj"
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        val dirSessions = listOf(Session(id = "s-dir", directory = "/persisted/proj"))
        coEvery { repository.getSessionsForDirectory("/persisted/proj") } returns Result.success(dirSessions)

        coordinator.loadInitialData()
        runPending()

        assertEquals(dirSessions, slices.sessionList.value.directorySessions["/persisted/proj"])
    }

    @Test
    fun `loadInitialData refreshes directorySessions for every recent workdir`() {
        // §recent-workdirs: cold start restores ALL connected projects, not
        // just currentWorkdir — otherwise a non-current workdir whose sessions
        // fall outside the getSessions(limit) first page vanishes after restart.
        every { settingsManager.currentWorkdir } returns "/current"
        every { settingsManager.getRecentWorkdirs(any()) } returns listOf("/current", "/other")
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        val currentSessions = listOf(Session(id = "s-cur", directory = "/current"))
        val otherSessions = listOf(Session(id = "s-oth", directory = "/other"))
        coEvery { repository.getSessionsForDirectory("/current") } returns Result.success(currentSessions)
        coEvery { repository.getSessionsForDirectory("/other") } returns Result.success(otherSessions)

        coordinator.loadInitialData()
        runPending()

        assertEquals(currentSessions, slices.sessionList.value.directorySessions["/current"])
        assertEquals(otherSessions, slices.sessionList.value.directorySessions["/other"])
        // currentWorkdir 是 /current（也在 recentWorkdirs 内）；distinct 去重后
        // 只拉两次（/current + /other），不会对 /current 重复拉取。
        coVerify(exactly = 1) { repository.getSessionsForDirectory("/current") }
        coVerify(exactly = 1) { repository.getSessionsForDirectory("/other") }
    }

    @Test
    fun `loadInitialData skips the directory fetch when no workdir is restored`() {
        every { settingsManager.currentWorkdir } returns null
        every { settingsManager.getRecentWorkdirs(any()) } returns emptyList()
        coEvery { repository.getCommands() } returns Result.success(emptyList())

        coordinator.loadInitialData()
        runPending()

        coVerify(exactly = 0) { repository.getSessionsForDirectory(any()) }
    }

    // ── §generation-guard: cross-host in-flight pollution ─────────────────

    @Test
    fun `loadInitialData drops directory fetches that return after a reconfigure`() {
        // §generation-guard: a host switch (cancelSseForReconfigure) between
        // dispatch and return must not let the PREVIOUS host's sessions
        // pollute the NEW host's directorySessions. The fan-out here (up to 8
        // launches) amplifies the in-flight window, so the generation check
        // gates every onSuccess write.
        // CP1: bind an identity first so loadInitialData has a non-null
        // capture; cancelSseForReconfigure → beginReconfigure invalidates it.
        identityStore.bind("test-fp", "/proj", "test-endpoint")
        every { settingsManager.currentWorkdir } returns "/proj"
        every { settingsManager.getRecentWorkdirs(any()) } returns listOf("/proj")
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        // Suspend the fetch so we can interleave a reconfigure before it returns.
        val pending = CompletableDeferred<Result<List<Session>>>()
        coEvery { repository.getSessionsForDirectory("/proj") } coAnswers { pending.await() }

        coordinator.loadInitialData()
        runPending() // launch now suspended on pending.await()

        // The D3 barrier is now the sole epoch owner; simulate its first step.
        identityStore.beginReconfigure()

        // The stale-host result arrives AFTER the reconfigure.
        pending.complete(Result.success(listOf(Session(id = "stale", directory = "/proj"))))
        runPending()

        // Stale result dropped: /proj must NOT appear in directorySessions.
        assertFalse(
            "stale-host directory result must be dropped after reconfigure",
            slices.sessionList.value.directorySessions.containsKey("/proj")
        )
    }

    @Test
    fun `loadInitialData keeps directory fetches that return without a reconfigure`() {
        // Positive control for the generation guard: identical setup but NO
        // reconfigure interleaved → the result IS written.
        every { settingsManager.currentWorkdir } returns "/proj"
        every { settingsManager.getRecentWorkdirs(any()) } returns listOf("/proj")
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        val sessions = listOf(Session(id = "s1", directory = "/proj"))
        coEvery { repository.getSessionsForDirectory("/proj") } returns Result.success(sessions)

        coordinator.loadInitialData()
        runPending()

        assertEquals(sessions, slices.sessionList.value.directorySessions["/proj"])
    }

    // ── §R-19 fix Blocker 1 / SSE lifecycle: REMOVED in CP9 ─────────────────
    //
    // CP9 (notify Phase-0 switchover): the CC-owned collector tests moved to
    // ServiceSseConnectionOwnerTest. CC no longer owns sseJob / launchSse-
    // Collection; startSSE delegates to StreamingServiceLauncher, and the
    // stale-identity drop / event-level failure / collection-level failure /
    // prior-job replacement / cancel-stops-forwarding / workdir retarget /
    // capture-time identity / liveness recovery behaviors are now property
    // of the Service-owned collector.

    // ── SSE lifecycle (cancelSseForReconfigure still lives here) ───────────

    @Test
    fun `cancelSseForReconfigure does not duplicate barrier epoch or effect`() {
        val epochBefore = identityStore.currentEpoch()
        coordinator.cancelSseForReconfigure()
        runPending()
        assertEquals(epochBefore, identityStore.currentEpoch())
        assertTrue(collectedEffects.none { it is ControllerEffect.HostReconfigured })
    }

    @Test
    fun `cancelSseForReconfigure is neutral with no active feed`() {
        coordinator.cancelSseForReconfigure()
        runPending()
        assertTrue(collectedEffects.none { it is ControllerEffect.HostReconfigured })
    }

    // ── loadInitialData (§best-effort: directory onFailure is non-fatal) ───

    @Test
    fun `loadInitialData directory fetch failure logs non-fatal issue without surfacing error or writing stale data`() {
        // §best-effort restore: a failed workdir simply stays absent from
        // directorySessions — the global getSessions list + user-initiated
        // refreshDirectorySessions are the fallbacks. reportNonFatalIssue
        // (Log.w) fires for diagnosability WITHOUT a user-facing error toast.
        // This covers the previously-uncovered onFailure branch (lines 661-664).
        every { settingsManager.currentWorkdir } returns "/proj"
        every { settingsManager.getRecentWorkdirs(any()) } returns listOf("/proj")
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        coEvery { repository.getSessionsForDirectory("/proj") } returns
            Result.failure(IOException("dir fetch boom"))

        coordinator.loadInitialData()
        runPending()

        // Failed workdir stays absent from directorySessions.
        assertFalse(
            "failed directory must not appear in directorySessions",
            slices.sessionList.value.directorySessions.containsKey("/proj")
        )
        // Non-fatal: no user-facing error toast for a directory fetch failure.
        assertTrue(
            "no UiEvent.Error for directory fetch failure",
            recordedEvents.filterIsInstance<UiEvent.Error>().isEmpty()
        )
        // Other initial-data loaders still fanned out.
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.LoadSessions>().size)
    }

    @Test
    fun `loadInitialData directory fetch failure after reconfigure is silently dropped`() {
        // §generation-guard × onFailure: a directory fetch that fails AFTER a
        // host switch must not even log — the error belongs to the stale host.
        // CP1: bind an identity first so the guard is active.
        identityStore.bind("test-fp", "/proj", "test-endpoint")
        every { settingsManager.currentWorkdir } returns "/proj"
        every { settingsManager.getRecentWorkdirs(any()) } returns listOf("/proj")
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        val pending = CompletableDeferred<Result<List<Session>>>()
        coEvery { repository.getSessionsForDirectory("/proj") } coAnswers { pending.await() }

        coordinator.loadInitialData()
        runPending() // launch suspended on pending.await()

        // Host switch bumps generation.
        coordinator.cancelSseForReconfigure()

        // Stale-host failure arrives AFTER the reconfigure.
        pending.complete(Result.failure(IOException("stale-host dir boom")))
        runPending()

        // Silently dropped: no directorySessions entry, no error toast.
        assertFalse(slices.sessionList.value.directorySessions.containsKey("/proj"))
        assertTrue(recordedEvents.filterIsInstance<UiEvent.Error>().isEmpty())
    }

    // ── cancelSse (no-op coverage; CC no longer owns a job) ────────────────

    @Test
    fun `cancelSse is a safe no-op without a streaming lifecycle coordinator`() {
        // CP9: cancelSse now routes through streamingLifecycleCoordinator?.onDisconnect().
        // In this test core coordinator is null — the call MUST be a no-op
        // (null-safe delegate). The repository MUST NOT be touched.
        coordinator.cancelSse()
        verify(exactly = 0) { repository.connectSSE(any()) }
        // The launcher is also not touched (cancelSse does not start anything).
        assertEquals(0, launcher.callCount)
    }

    // ── §R-19 #2 single-SSE product decision: ownership moved to Service ───
    //
    // CP9: the SSE-bound-to-currentWorkdir tests + the workdir-retarget +
    // reconnect-consistency tests moved to ServiceSseConnectionOwnerTest
    // (the workdir is now derived from the StartSse command's identity, not
    // from a fresh settingsManager.currentWorkdir read at collection start).
    // The directory-fan-out catch-up precondition (single-SSE on B restores
    // A's directory) is still exercised via the loadInitialData tests below.

    @Test
    fun `single SSE catch-up fan-out precondition - loadInitialData restores every recent workdir`() {
        // §catch-up precondition: SSE is bound to currentWorkdir B (CP9: the
        // Service binds the identity's workdir), but A is a BACKGROUND workdir
        // (in recentWorkdirs but not currentWorkdir). The single-SSE model
        // relies on loadInitialData's fan-out restoring directorySessions[/A]
        // so SessionSyncCoordinator.loadPendingQuestionsAllWorkdirs (which
        // reads directorySessions.keys) can still poll A's pending questions.
        every { settingsManager.currentWorkdir } returns "/B"
        every { settingsManager.getRecentWorkdirs(any()) } returns listOf("/A", "/B")
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        val aSessions = listOf(Session(id = "s-a", directory = "/A"))
        val bSessions = listOf(Session(id = "s-b", directory = "/B"))
        coEvery { repository.getSessionsForDirectory("/A") } returns Result.success(aSessions)
        coEvery { repository.getSessionsForDirectory("/B") } returns Result.success(bSessions)

        coordinator.loadInitialData()
        runPending()

        assertEquals(aSessions, slices.sessionList.value.directorySessions["/A"])
        assertEquals(bSessions, slices.sessionList.value.directorySessions["/B"])
    }

    // ── CP1 (notify Phase-0): identity-guarded directory-fetch ─────────────

    /**
     * CP1 spec test: stale directory-fetch response (old epoch) is dropped via
     * the same epoch check. Explicitly asserts the identityStore-based guard
     * (the SSE-side stale-identity drop moved to ServiceSseConnectionOwnerTest).
     */
    @Test
    fun `CP1 stale directory-fetch response is dropped via identity epoch check`() {
        identityStore.bind("test-fp", "/proj", "test-endpoint")
        every { settingsManager.currentWorkdir } returns "/proj"
        every { settingsManager.getRecentWorkdirs(any()) } returns listOf("/proj")
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        val pending = CompletableDeferred<Result<List<Session>>>()
        coEvery { repository.getSessionsForDirectory("/proj") } coAnswers { pending.await() }

        coordinator.loadInitialData()
        runPending()

        // beginReconfigure invalidates the identity → epoch bump.
        identityStore.beginReconfigure()

        // Stale-host result arrives.
        pending.complete(Result.success(listOf(Session(id = "stale", directory = "/proj"))))
        runPending()

        assertFalse(
            "stale-identity directory result dropped",
            slices.sessionList.value.directorySessions.containsKey("/proj"),
        )
    }

    // ── CP2 (notify Phase-0): CC delegates TOFU state to bootstrap coordinator ──

    /**
     * CP2 spec test: when CC enters the TOFU-pending path via its public
     * [ConnectionCoordinator.resolveTofuTrust] surface, the shared
     * [ConnectionBootstrapCoordinator.tofuState] reflects the transition.
     * This proves the delegation — CC has NO private TOFU state of its own
     * (the old `pendingTofuHostPort` / `pendingTofuDecision` fields are gone).
     *
     * We exercise the delegation directly: setPendingTofu through the shared
     * coordinator (which is what CC does internally on the SSL/cert-failure
     * path), then resolveTofuTrust through CC's public wrapper, and observe
     * the state on the shared coordinator.
     */
    @Test
    fun `CP2 CC delegates TOFU state to bootstrap coordinator`() {
        // Initially Idle.
        assertEquals(
            cn.vectory.ocdroid.service.bootstrap.TofuState.Idle,
            bootstrapCoordinator.tofuState.value,
        )

        // CC's internal TOFU path sets pending via the shared coordinator.
        bootstrapCoordinator.setPendingTofu("example.com:443")
        bootstrapCoordinator.setTofuDecision(
            kotlinx.coroutines.CompletableDeferred<cn.vectory.ocdroid.data.repository.http.TofuDecision>()
        )
        assertEquals(
            cn.vectory.ocdroid.service.bootstrap.TofuState.TrustPending("example.com:443"),
            bootstrapCoordinator.tofuState.value,
        )

        // CC's public resolveTofuTrust delegates to the shared coordinator.
        coordinator.resolveTofuTrust(
            cn.vectory.ocdroid.data.repository.http.TofuDecision.Cancel
        )
        // resolveTofuTrust completes the deferred but does NOT clear the
        // pending state (mirrors CC's original semantics — the retry loop's
        // finally block calls clearPendingTofu). The state is still
        // TrustPending until clearPendingTofu runs.
        assertEquals(
            "resolveTofuTrust does not clear tofuState (the retry loop finally does)",
            cn.vectory.ocdroid.service.bootstrap.TofuState.TrustPending("example.com:443"),
            bootstrapCoordinator.tofuState.value,
        )

        // The retry loop's finally block clears the state.
        bootstrapCoordinator.clearPendingTofu()
        identityStore.bind("test-fp", "/proj", "test-endpoint")
        assertEquals(
            cn.vectory.ocdroid.service.bootstrap.TofuState.Idle,
            bootstrapCoordinator.tofuState.value,
        )
    }

    /**
     * CP2 / CP9 spec test: the freeze semantics are preserved through
     * delegation — while TOFU is pending, testConnection / coldStartReconnect
     * / startSSE short-circuit (the guards read through
     * `tofu.pendingTofuHostPort()` which returns non-null for TrustPending).
     * CP9: startSSE now delegates to the launcher; the freeze assertion is
     * "launcher NOT invoked while frozen" + "launcher invoked once after clear".
     */
    @Test
    fun `CP2 freeze semantics preserved - startSSE short-circuits while TOFU pending`() {
        // Enter TOFU-pending via the shared coordinator (CC's internal path).
        bootstrapCoordinator.setPendingTofu("example.com:443")

        // startSSE must FROZEN — no launcher call.
        coordinator.startSSE()
        runPending()

        assertEquals(
            "launcher NOT invoked while TOFU pending",
            0,
            launcher.callCount,
        )

        // Resolve + clear → unfreezes.
        bootstrapCoordinator.clearPendingTofu()
        identityStore.bind("test-fp", "/proj", "test-endpoint")

        coordinator.startSSE()
        runPending()

        assertEquals(
            "launcher invoked once after TOFU cleared",
            1,
            launcher.callCount,
        )
    }

    /**
     * CP2 / CP9 spec test: CC has NO private TOFU fields (grep-verifiable:
     * the old `pendingTofuHostPort` / `pendingTofuDecision` private vars are
     * gone). This test exercises the public surface end-to-end to prove no
     * duplicate state — the shared coordinator is the single source.
     */
    @Test
    fun `CP2 CC has no private TOFU state - single source in bootstrap coordinator`() {
        // If CC held a PRIVATE copy of the TOFU state (the old fields), this
        // test would fail: setting state via the shared coordinator would
        // not be visible to CC's guards, and vice versa. The fact that CC's
        // startSSE guard reads the shared coordinator's state proves no
        // private duplicate exists.
        bootstrapCoordinator.setPendingTofu("frozen.example.com:443")
        assertEquals(
            "frozen.example.com:443",
            bootstrapCoordinator.pendingTofuHostPort(),
        )

        // CC's internal guard (via delegation) sees the same state.
        coordinator.startSSE() // should freeze — no launcher call.
        runPending()
        assertEquals(0, launcher.callCount)

        // Clearing via the shared coordinator unfreezes CC.
        bootstrapCoordinator.clearPendingTofu()
        identityStore.bind("test-fp", "/proj", "test-endpoint")
        coordinator.startSSE()
        runPending()
        assertEquals(1, launcher.callCount)
    }

    @Test
    fun `D3 refused ownership settles false once and never publishes Connected`() {
        val engine = mockk<cn.vectory.ocdroid.service.streaming.ConnectionBootstrapEngine>()
        val identity = identityStore.bind("group", "/work", "endpoint")
        coEvery { engine.bootstrap() } returns
            cn.vectory.ocdroid.service.streaming.ConnectionBootstrapOutcome.Success(
                identity,
                HealthResponse(true, "3.0"),
            )
        launcher.nextResult = false
        val d3 = ConnectionCoordinator(
            scope = scope,
            slices = slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effects,
            serverCompatProfile = cn.vectory.ocdroid.data.repository.ServerCompatProfile(),
            clock = { now },
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            streamingServiceLauncher = launcher,
            connectionBootstrapEngine = engine,
        )
        val settled = mutableListOf<Boolean>()

        d3.testConnection(force = true, onSettled = settled::add)
        runPending()

        assertEquals(listOf(false), settled)
        assertFalse(connectionFlow.value.isConnected)
        assertFalse(connectionFlow.value.connectionPhase is ConnectionPhase.Connected)
    }

    @Test
    fun `D3 exact accepted identity is the only healthy settlement`() {
        val engine = mockk<cn.vectory.ocdroid.service.streaming.ConnectionBootstrapEngine>()
        val identity = identityStore.bind("group", "/work", "endpoint")
        coEvery { engine.bootstrap() } returns
            cn.vectory.ocdroid.service.streaming.ConnectionBootstrapOutcome.Success(
                identity,
                HealthResponse(true, "3.0"),
            )
        val d3 = ConnectionCoordinator(
            scope = scope,
            slices = slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effects,
            serverCompatProfile = cn.vectory.ocdroid.data.repository.ServerCompatProfile(),
            clock = { now },
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            streamingServiceLauncher = launcher,
            connectionBootstrapEngine = engine,
        )
        val settled = mutableListOf<Boolean>()

        d3.testConnection(force = true, onSettled = settled::add)
        runPending()

        assertEquals(listOf(true), settled)
        assertTrue(connectionFlow.value.isConnected)
        assertEquals(listOf(identity), launcher.requestedIdentities.takeLast(1))
    }

    // ── RecordingConnectionCoordinatorCallbacks (removed in batch 3b) ──────
    // The handwritten spy was replaced by direct filtering on
    // [collectedEffects] (plus the [forwardedSseEvents] helper for SSE event
    // assertions) and repository coVerify for the inlined loaders. See the
    // class kdoc at the top for the new pattern.
}
