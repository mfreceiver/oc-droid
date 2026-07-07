package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.api.CommandInfo
import cn.vectory.ocdroid.data.model.HealthResponse
import cn.vectory.ocdroid.data.model.SSEEvent
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        coordinator = ConnectionCoordinator(
            scope = scope,
            slices = slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effects,
            serverCompatProfile = cn.vectory.ocdroid.data.repository.ServerCompatProfile(),
            clock = { now }
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
        every { repository.connectSSE(any()) } returns flowOf()
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
        // SSE feed started.
        verify { repository.connectSSE(any()) }
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
        every { repository.connectSSE(any()) } returns flowOf()
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
        every { repository.connectSSE(any()) } returns flowOf()
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
        every { repository.connectSSE(any()) } returns flowOf()
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
        every { repository.connectSSE(any()) } returns flowOf()
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
        every { repository.connectSSE(any()) } returns flowOf()
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
        every { settingsManager.currentWorkdir } returns "/proj"
        every { settingsManager.getRecentWorkdirs(any()) } returns listOf("/proj")
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        // Suspend the fetch so we can interleave a reconfigure before it returns.
        val pending = CompletableDeferred<Result<List<Session>>>()
        coEvery { repository.getSessionsForDirectory("/proj") } coAnswers { pending.await() }

        coordinator.loadInitialData()
        runPending() // launch now suspended on pending.await()

        // Host/profile switch → cancelSseForReconfigure bumps generation.
        coordinator.cancelSseForReconfigure()

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

    // ── §R-19 fix Blocker 1: SSE stale-host event drop at forwarding layer ──

    @Test
    fun `launchSseCollection drops events from a stale-host job after reconfigure`() {
        // §R-19 Sprint 1 Lane A (P1-10) fix Blocker 1: the production-grade
        // stale-host guard lives HERE (in ConnectionCoordinator.launchSseCollection),
        // not in SessionSyncCoordinator. The generation is captured at SSE job
        // start and re-checked per collected event; a host reconfigure that
        // lands mid-collection causes every subsequent event from THIS job to
        // be dropped at the forwarding layer (never becomes an OnSseEvent).
        //
        // Without this check a late server.connected from the previous host's
        // cancelled SSE job would arrive at SessionSyncCoordinator.handleEvent,
        // be stamped with the CURRENT generation (mismatch undetectable at
        // consume time), and pollute the new host's cold-start semantics.
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed.asSharedFlow()

        coordinator.startSSE()
        runPending()

        // Event #1: forwarded normally (gen 0 == captured sseGenAtStart 0).
        val evt1 = SSEEvent(payload = cn.vectory.ocdroid.data.model.SSEPayload(type = "evt1"))
        feed.tryEmit(Result.success(evt1))
        runPending()
        assertEquals("evt1 forwarded pre-reconfigure", listOf(evt1), forwardedSseEvents())

        // Host reconfigure: bumps directoryFetchGeneration to 1.
        coordinator.cancelSseForReconfigure()
        // NOTE: cancelSseForReconfigure also calls sseJob?.cancel(), so the
        // collector is being torn down. But the race window we cover is the
        // frame already buffered in `feed` (or mid-flight in the collector)
        // before cancellation propagates — the generation check at collect time
        // is the safety net.

        // Stale event #2 from the OLD job: must NOT be forwarded.
        val staleEvt = SSEEvent(payload = cn.vectory.ocdroid.data.model.SSEPayload(type = "stale"))
        feed.tryEmit(Result.success(staleEvt))
        runPending()

        // Only evt1 was forwarded; staleEvt was dropped by the per-event
        // generation check.
        assertEquals(
            "stale-host event dropped at CC forwarding layer",
            listOf(evt1),
            forwardedSseEvents()
        )
    }

    @Test
    fun `launchSseCollection forwards events normally when no reconfigure intervenes`() {
        // Positive control for the Blocker 1 generation guard: identical setup
        // but NO reconfigure interleaved → every event forwards.
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed.asSharedFlow()

        coordinator.startSSE()
        runPending()

        val evt1 = SSEEvent(payload = cn.vectory.ocdroid.data.model.SSEPayload(type = "a"))
        val evt2 = SSEEvent(payload = cn.vectory.ocdroid.data.model.SSEPayload(type = "b"))
        feed.tryEmit(Result.success(evt1))
        feed.tryEmit(Result.success(evt2))
        runPending()

        assertEquals(
            "both events forwarded when no reconfigure intervened",
            listOf(evt1, evt2),
            forwardedSseEvents()
        )
    }

    // ── SSE lifecycle ──────────────────────────────────────────────────────

    /** §batch 3b helper: extracts every SSE event the coordinator forwarded as
     *  a [ControllerEffect.OnSseEvent]. */
    private fun forwardedSseEvents(): List<SSEEvent> =
        collectedEffects.filterIsInstance<ControllerEffect.OnSseEvent>().map { it.event }

    @Test
    fun `startSSE forwards each successful SSE event to the onSseEvent callback`() {
        val event = SSEEvent(payload = cn.vectory.ocdroid.data.model.SSEPayload(type = "session.created"))
        every { repository.connectSSE(any()) } returns flowOf(Result.success(event))

        coordinator.startSSE()
        runPending()

        assertEquals(listOf(event), forwardedSseEvents())
    }

    @Test
    fun `startSSE collection-level failure writes an SSE Error onto AppState`() {
        every { repository.connectSSE(any()) } returns flow { throw IOException("feed exhausted") }

        coordinator.startSSE()
        runPending()

        val errorEvent = recordedEvents.filterIsInstance<UiEvent.Error>().single()
        assertEquals(R.string.error_sse_failed, errorEvent.resId)
    }

    @Test
    fun `startSSE event-level failure writes an SSE Error onto AppState`() {
        every { repository.connectSSE(any()) } returns flowOf(Result.failure(IOException("bad frame")))

        coordinator.startSSE()
        runPending()

        val errorEvent = recordedEvents.filterIsInstance<UiEvent.Error>().single()
        assertEquals(R.string.error_sse_failed, errorEvent.resId)
    }

    @Test
    fun `startSSE cancels any prior in-flight SSE Job before launching a new collector`() {
        // Two distinct hot feeds so we can tell the two collectors apart. The
        // first collector must be cancelled when the second startSSE runs, so
        // events emitted on flow #1 AFTER the second start no longer forward.
        val flow1 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        val flow2 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returnsMany listOf(flow1.asSharedFlow(), flow2.asSharedFlow())
        val evt1 = SSEEvent(payload = cn.vectory.ocdroid.data.model.SSEPayload(type = "a"))
        val evt2 = SSEEvent(payload = cn.vectory.ocdroid.data.model.SSEPayload(type = "b"))

        coordinator.startSSE()
        runPending()
        flow1.tryEmit(Result.success(evt1)) // collected by the first job
        runPending()
        assertEquals("first collector forwarded evt1", listOf(evt1), forwardedSseEvents())

        coordinator.startSSE() // cancels job #1, launches job #2 on flow2
        runPending()
        flow1.tryEmit(Result.success(evt1)) // job #1 cancelled → ignored
        flow2.tryEmit(Result.success(evt2)) // job #2 forwards
        runPending()

        assertEquals(
            "evt1 from the cancelled collector was NOT re-forwarded; evt2 forwarded",
            listOf(evt1, evt2),
            forwardedSseEvents()
        )
        verify(atLeast = 2) { repository.connectSSE(any()) }
    }

    @Test
    fun `cancelSse stops forwarding events from the live feed`() {
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed.asSharedFlow()
        val evt = SSEEvent(payload = cn.vectory.ocdroid.data.model.SSEPayload(type = "a"))

        coordinator.startSSE()
        runPending()
        coordinator.cancelSse()
        runPending()
        feed.tryEmit(Result.success(evt)) // collector cancelled → not forwarded
        runPending()

        assertTrue("no event forwarded after cancelSse", forwardedSseEvents().isEmpty())
    }

    @Test
    fun `cancelSseForReconfigure cancels the feed and fires onHostReconfigured`() {
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE(any()) } returns feed.asSharedFlow()
        val evt = SSEEvent(payload = cn.vectory.ocdroid.data.model.SSEPayload(type = "a"))

        coordinator.startSSE()
        runPending()
        coordinator.cancelSseForReconfigure()
        feed.tryEmit(Result.success(evt)) // cancelled → ignored
        runPending()

        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.HostReconfigured>().size)
        assertTrue(forwardedSseEvents().isEmpty())
    }

    @Test
    fun `cancelSseForReconfigure fires onHostReconfigured even with no active feed`() {
        coordinator.cancelSseForReconfigure()
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.HostReconfigured>().size)
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

    // ── cancelSse (null-branch coverage) ───────────────────────────────────

    @Test
    fun `cancelSse without an active SSE feed is a safe no-op`() {
        // No prior startSSE → sseJob is null. cancelSse must not throw (the
        // null-conditional `sseJob?.cancel()` skip is the coverage target for
        // the previously partially-covered line 805).
        coordinator.cancelSse()

        // No SSE feed was ever started.
        verify(exactly = 0) { repository.connectSSE(any()) }
        // A subsequent startSSE still works after the no-op cancel.
        every { repository.connectSSE(any()) } returns flowOf()
        coordinator.startSSE()
        runPending()
        verify(exactly = 1) { repository.connectSSE(any()) }
    }

    // ── §R-19 #2: single-SSE + catch-up boundary cases ─────────────────────
    //
    // The app runs exactly ONE SSE connection (bound to currentWorkdir). These
    // cases pin the contract that multi-workdir correctness is recovered by
    // catch-up (loadPendingQuestionsAllWorkdirs reads directorySessions.keys,
    // which loadInitialData's fan-out populates for every recent workdir). See
    // ConnectionCoordinator.startSSE KDoc for the full rationale.

    @Test
    fun `startSSE retargets to the new currentWorkdir after a workdir switch A to B`() {
        // §single-sse retarget: when the user switches project A → B, the next
        // startSSE must connect B's SSE. The prior A-bound collector is
        // cancelled by the sseJob?.cancel() in startSSE; currentWorkdir is
        // read fresh inside launchSseCollection (no cached value drifts).
        every { settingsManager.currentWorkdir } returns "/A"
        val flowA = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE("/A") } returns flowA.asSharedFlow()

        coordinator.startSSE()
        runPending()

        // A's feed is now collecting; emit one event to prove liveness.
        val evtA = SSEEvent(payload = cn.vectory.ocdroid.data.model.SSEPayload(type = "a"))
        flowA.tryEmit(Result.success(evtA))
        runPending()
        assertEquals("A's feed forwarded before switch", listOf(evtA), forwardedSseEvents())

        // User switches project A → B (SessionSwitcher updates currentWorkdir;
        // the host-switch / session-switch path then calls startSSE again).
        every { settingsManager.currentWorkdir } returns "/B"
        val flowB = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE("/B") } returns flowB.asSharedFlow()

        coordinator.startSSE()
        runPending()

        // A's collector is cancelled: events on flowA no longer forward.
        flowA.tryEmit(Result.success(evtA))
        // B's collector is live: evtB forwards.
        val evtB = SSEEvent(payload = cn.vectory.ocdroid.data.model.SSEPayload(type = "b"))
        flowB.tryEmit(Result.success(evtB))
        runPending()

        // Exactly one connect per workdir; the A feed did not double-connect.
        verify(exactly = 1) { repository.connectSSE("/A") }
        verify(exactly = 1) { repository.connectSSE("/B") }
        assertEquals(
            "no extra evtA re-forwarded after retarget; evtB forwarded once",
            listOf(evtA, evtB),
            forwardedSseEvents()
        )
    }

    @Test
    fun `single SSE on currentWorkdir B still restores background workdir A directory for catch-up fan-out`() {
        // §catch-up precondition: SSE is bound to currentWorkdir B, but A is a
        // BACKGROUND workdir (in recentWorkdirs but not currentWorkdir). The
        // single-SSE model relies on loadInitialData's fan-out restoring
        // directorySessions[/A] so SessionSyncCoordinator.
        // loadPendingQuestionsAllWorkdirs (which reads directorySessions.keys)
        // can still poll A's pending questions. This test pins the precondition
        // — the SessionSyncCoordinator side is exercised in its own test file.
        every { settingsManager.currentWorkdir } returns "/B"
        every { settingsManager.getRecentWorkdirs(any()) } returns listOf("/A", "/B")
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        val aSessions = listOf(Session(id = "s-a", directory = "/A"))
        val bSessions = listOf(Session(id = "s-b", directory = "/B"))
        coEvery { repository.getSessionsForDirectory("/A") } returns Result.success(aSessions)
        coEvery { repository.getSessionsForDirectory("/B") } returns Result.success(bSessions)

        // SSE only ever binds currentWorkdir (B) — never the background A.
        every { repository.connectSSE(any()) } returns flowOf()
        coordinator.startSSE()
        runPending()
        verify(exactly = 1) { repository.connectSSE("/B") }
        verify(exactly = 0) { repository.connectSSE("/A") }

        // loadInitialData fans out across recentWorkdirs + currentWorkdir →
        // BOTH directories are restored. directorySessions[/A] being populated
        // is the catch-up precondition for the background workdir's questions.
        coordinator.loadInitialData()
        runPending()

        assertEquals(aSessions, slices.sessionList.value.directorySessions["/A"])
        assertEquals(bSessions, slices.sessionList.value.directorySessions["/B"])
    }

    @Test
    fun `startSSE after cancelSse reads currentWorkdir fresh and does not drift to the prior value`() {
        // §reconnect consistency: the foreground ON_STOP → ON_START path calls
        // cancelSse then startSSE on app return. If the user switched workdir
        // while backgrounded (currentWorkdir flipped /A → /B between cancel
        // and restart), the reconnect must bind B — never a stale /A captured
        // at the prior connect. launchSseCollection reads settingsManager.
        // currentWorkdir at collection start, so the property holds by
        // construction; this test pins it as a regression guard.
        every { settingsManager.currentWorkdir } returns "/A"
        every { repository.connectSSE("/A") } returns flowOf()
        coordinator.startSSE()
        runPending()
        verify(exactly = 1) { repository.connectSSE("/A") }

        // App backgrounded (cancelSse), user switches workdir while bg,
        // app foregrounded (startSSE again).
        coordinator.cancelSse()
        every { settingsManager.currentWorkdir } returns "/B"
        every { repository.connectSSE("/B") } returns flowOf()
        coordinator.startSSE()
        runPending()

        // Reconnect bound the latest currentWorkdir (B); no drift back to /A.
        verify(exactly = 1) { repository.connectSSE("/A") }
        verify(exactly = 1) { repository.connectSSE("/B") }
        // Total connectSSE calls: 2 (one per connect cycle). No phantom third
        // collector from a stale captured workdir.
        verify(exactly = 2) { repository.connectSSE(any()) }
    }

    // ── RecordingConnectionCoordinatorCallbacks (removed in batch 3b) ──────
    // The handwritten spy was replaced by direct filtering on
    // [collectedEffects] (plus the [forwardedSseEvents] helper for SSE event
    // assertions) and repository coVerify for the inlined loaders. See the
    // class kdoc at the top for the new pattern.
}
