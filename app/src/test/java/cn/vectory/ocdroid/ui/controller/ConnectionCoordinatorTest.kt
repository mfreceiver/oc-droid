package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.data.api.CommandInfo
import cn.vectory.ocdroid.data.model.HealthResponse
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.ComposerState
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
import kotlinx.coroutines.flow.MutableStateFlow
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

    private lateinit var connectionFlow: MutableStateFlow<ConnectionState>
    private lateinit var settingsFlow: MutableStateFlow<SettingsState>
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
        connectionFlow = MutableStateFlow(ConnectionState())
        settingsFlow = MutableStateFlow(SettingsState())
        slices = SliceFlows(
            connection = connectionFlow,
            traffic = MutableStateFlow(TrafficState()),
            composer = MutableStateFlow(ComposerState()),
            file = MutableStateFlow(FileState()),
            settings = settingsFlow,
            chat = MutableStateFlow(ChatState()),
            sessionList = MutableStateFlow(SessionListState()),
            unread = MutableStateFlow(UnreadState()),
            host = MutableStateFlow(HostState())
        )
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
            connectionFlow = connectionFlow,
            settingsFlow = settingsFlow,
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
        every { repository.connectSSE() } returns flowOf()
        coEvery { repository.getCommands() } returns Result.success(emptyList())

        coordinator.testConnection()
        runPending()

        assertTrue(connectionFlow.value.isConnected)
        assertFalse(connectionFlow.value.isConnecting)
        assertEquals("connected", connectionFlow.value.connectionPhase)
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
        verify { repository.connectSSE() }
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
        assertEquals("disconnected", connectionFlow.value.connectionPhase)
    }

    @Test
    fun `testConnection on failure surfaces the error message and disconnected phase`() {
        coEvery { repository.checkHealth() } returns Result.failure(IOException("network down"))

        coordinator.testConnection()
        runPending()

        assertFalse(connectionFlow.value.isConnected)
        assertEquals("disconnected", connectionFlow.value.connectionPhase)
        val errorEvent = recordedEvents.filterIsInstance<UiEvent.Error>().single()
        assertTrue(errorEvent.message.contains("network down"))
    }

    @Test
    fun `testConnection unhealthy with retries exhausts the backoff loop then disconnects`() {
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = false, version = "1.0"))

        coordinator.testConnection(force = true, retries = 3) // 4 attempts total
        runPending()

        coVerify(exactly = 4) { repository.checkHealth() }
        assertEquals("disconnected", connectionFlow.value.connectionPhase)
        assertFalse(connectionFlow.value.isConnected)
    }

    @Test
    fun `testConnection on healthy clears the prior error`() {
        // §R-17 batch2: error is now a one-shot UiEvent — there's no persistent
        // error field to clear. This test stays as a regression guard: a healthy
        // connect must NOT emit a fresh UiEvent.Error (it used to clear the
        // legacy `state.error` field here).
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = true, version = "1.0"))
        every { repository.connectSSE() } returns flowOf()
        coEvery { repository.getCommands() } returns Result.success(emptyList())

        coordinator.testConnection()
        runPending()

        assertTrue("healthy connect emits no error event", recordedEvents.filterIsInstance<UiEvent.Error>().isEmpty())
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
        every { repository.connectSSE() } returns flowOf()
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
        every { settingsManager.recentWorkdirs } returns listOf("/current", "/other")
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
        every { settingsManager.recentWorkdirs } returns emptyList()
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
        every { settingsManager.recentWorkdirs } returns listOf("/proj")
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
        every { settingsManager.recentWorkdirs } returns listOf("/proj")
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        val sessions = listOf(Session(id = "s1", directory = "/proj"))
        coEvery { repository.getSessionsForDirectory("/proj") } returns Result.success(sessions)

        coordinator.loadInitialData()
        runPending()

        assertEquals(sessions, slices.sessionList.value.directorySessions["/proj"])
    }

    // ── SSE lifecycle ──────────────────────────────────────────────────────

    /** §batch 3b helper: extracts every SSE event the coordinator forwarded as
     *  a [ControllerEffect.OnSseEvent]. */
    private fun forwardedSseEvents(): List<SSEEvent> =
        collectedEffects.filterIsInstance<ControllerEffect.OnSseEvent>().map { it.event }

    @Test
    fun `startSSE forwards each successful SSE event to the onSseEvent callback`() {
        val event = SSEEvent(payload = cn.vectory.ocdroid.data.model.SSEPayload(type = "session.created"))
        every { repository.connectSSE() } returns flowOf(Result.success(event))

        coordinator.startSSE()
        runPending()

        assertEquals(listOf(event), forwardedSseEvents())
    }

    @Test
    fun `startSSE collection-level failure writes an SSE Error onto AppState`() {
        every { repository.connectSSE() } returns flow { throw IOException("feed exhausted") }

        coordinator.startSSE()
        runPending()

        val errorEvent = recordedEvents.filterIsInstance<UiEvent.Error>().single()
        assertTrue(errorEvent.message.contains("SSE Error"))
    }

    @Test
    fun `startSSE event-level failure writes an SSE Error onto AppState`() {
        every { repository.connectSSE() } returns flowOf(Result.failure(IOException("bad frame")))

        coordinator.startSSE()
        runPending()

        val errorEvent = recordedEvents.filterIsInstance<UiEvent.Error>().single()
        assertTrue(errorEvent.message.contains("SSE Error"))
    }

    @Test
    fun `startSSE cancels any prior in-flight SSE Job before launching a new collector`() {
        // Two distinct hot feeds so we can tell the two collectors apart. The
        // first collector must be cancelled when the second startSSE runs, so
        // events emitted on flow #1 AFTER the second start no longer forward.
        val flow1 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        val flow2 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE() } returnsMany listOf(flow1.asSharedFlow(), flow2.asSharedFlow())
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
        verify(atLeast = 2) { repository.connectSSE() }
    }

    @Test
    fun `cancelSse stops forwarding events from the live feed`() {
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE() } returns feed.asSharedFlow()
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
        every { repository.connectSSE() } returns feed.asSharedFlow()
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

    // ── RecordingConnectionCoordinatorCallbacks (removed in batch 3b) ──────
    // The handwritten spy was replaced by direct filtering on
    // [collectedEffects] (plus the [forwardedSseEvents] helper for SSE event
    // assertions) and repository coVerify for the inlined loaders. See the
    // class kdoc at the top for the new pattern.
}
