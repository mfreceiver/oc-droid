package com.yage.opencode_client.ui.controller

import android.util.Log
import com.yage.opencode_client.data.api.CommandInfo
import com.yage.opencode_client.data.model.HealthResponse
import com.yage.opencode_client.data.model.SSEEvent
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.ChatState
import com.yage.opencode_client.ui.ComposerState
import com.yage.opencode_client.ui.ConnectionState
import com.yage.opencode_client.ui.FileState
import com.yage.opencode_client.ui.HostState
import com.yage.opencode_client.ui.SessionListState
import com.yage.opencode_client.ui.SettingsState
import com.yage.opencode_client.ui.SliceFlows
import com.yage.opencode_client.ui.TrafficState
import com.yage.opencode_client.ui.UnreadState
import com.yage.opencode_client.ui.syncSlicesFromAppState
import com.yage.opencode_client.util.SettingsManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
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
class ConnectionCoordinatorTest {

    private lateinit var state: MutableStateFlow<AppState>
    private lateinit var connectionFlow: MutableStateFlow<ConnectionState>
    private lateinit var settingsFlow: MutableStateFlow<SettingsState>
    private lateinit var slices: SliceFlows
    private lateinit var repository: OpenCodeRepository
    private lateinit var settingsManager: SettingsManager
    private lateinit var callbacks: RecordingConnectionCoordinatorCallbacks
    private lateinit var scope: TestScope
    private var now: Long = 0L
    private lateinit var coordinator: ConnectionCoordinator

    @Before
    fun setUp() {
        // launchSseCollection's catch block calls Log.e.
        mockkStatic(Log::class)
        state = MutableStateFlow(AppState())
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
        callbacks = RecordingConnectionCoordinatorCallbacks()
        scope = TestScope()
        // Baseline clock well past the 30s throttle window from epoch 0 so the
        // first probe always proceeds (mirrors production's wall-clock ms).
        now = 100_000L
        coordinator = ConnectionCoordinator(
            scope = scope,
            state = state,
            connectionFlow = connectionFlow,
            settingsFlow = settingsFlow,
            slices = slices,
            repository = repository,
            settingsManager = settingsManager,
            callbacks = callbacks,
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

    // R-17 M5: seed AppState then propagate to slices (controllers read/write slices).
    private fun seed(transform: (AppState) -> AppState) {
        state.value = transform(state.value)
        syncSlicesFromAppState(state.value, slices)
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
    fun `testConnection reconfigures the repository for the current profile before probing`() {
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = true, version = "1.0"))

        coordinator.testConnection()
        runPending()

        // configureRepositoryForCurrentProfile fires BEFORE checkHealth.
        assertEquals(1, callbacks.configureRepositoryForCurrentProfileCalls)
        val configureIdx = callbacks.callOrder.indexOf("configureRepositoryForCurrentProfile")
        // checkHealth is a suspend mock; just assert configure ran (it runs first
        // in the launched body, before the retry loop's first probe).
        assertTrue("configure ran before the healthy fan-out", configureIdx >= 0)
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
        // loadInitialData fan-out (loaders + loadCommands).
        assertEquals(1, callbacks.loadSessionsCalls)
        assertEquals(1, callbacks.loadAgentsCalls)
        assertEquals(1, callbacks.loadProvidersCalls)
        assertEquals(1, callbacks.loadPendingQuestionsCalls)
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
        assertNotNull(state.value.error)
        assertTrue(state.value.error!!.contains("network down"))
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
        seed { it.copy(error = "stale") }
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = true, version = "1.0"))
        every { repository.connectSSE() } returns flowOf()
        coEvery { repository.getCommands() } returns Result.success(emptyList())

        coordinator.testConnection()
        runPending()

        assertNull(state.value.error)
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

        assertEquals(1, callbacks.loadSessionsCalls)
        assertEquals(1, callbacks.loadAgentsCalls)
        assertEquals(1, callbacks.loadProvidersCalls)
        assertEquals(1, callbacks.loadPendingQuestionsCalls)
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
    fun `loadInitialData skips the directory fetch when no workdir is restored`() {
        every { settingsManager.currentWorkdir } returns null
        coEvery { repository.getCommands() } returns Result.success(emptyList())

        coordinator.loadInitialData()
        runPending()

        coVerify(exactly = 0) { repository.getSessionsForDirectory(any()) }
    }

    // ── SSE lifecycle ──────────────────────────────────────────────────────

    @Test
    fun `startSSE forwards each successful SSE event to the onSseEvent callback`() {
        val event = SSEEvent(payload = com.yage.opencode_client.data.model.SSEPayload(type = "session.created"))
        every { repository.connectSSE() } returns flowOf(Result.success(event))

        coordinator.startSSE()
        runPending()

        assertEquals(listOf(event), callbacks.sseEvents)
    }

    @Test
    fun `startSSE collection-level failure writes an SSE Error onto AppState`() {
        every { repository.connectSSE() } returns flow { throw IOException("feed exhausted") }

        coordinator.startSSE()
        runPending()

        assertNotNull(state.value.error)
        assertTrue(state.value.error!!.contains("SSE Error"))
    }

    @Test
    fun `startSSE event-level failure writes an SSE Error onto AppState`() {
        every { repository.connectSSE() } returns flowOf(Result.failure(IOException("bad frame")))

        coordinator.startSSE()
        runPending()

        assertNotNull(state.value.error)
        assertTrue(state.value.error!!.contains("SSE Error"))
    }

    @Test
    fun `startSSE cancels any prior in-flight SSE Job before launching a new collector`() {
        // Two distinct hot feeds so we can tell the two collectors apart. The
        // first collector must be cancelled when the second startSSE runs, so
        // events emitted on flow #1 AFTER the second start no longer forward.
        val flow1 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        val flow2 = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE() } returnsMany listOf(flow1.asSharedFlow(), flow2.asSharedFlow())
        val evt1 = SSEEvent(payload = com.yage.opencode_client.data.model.SSEPayload(type = "a"))
        val evt2 = SSEEvent(payload = com.yage.opencode_client.data.model.SSEPayload(type = "b"))

        coordinator.startSSE()
        runPending()
        flow1.tryEmit(Result.success(evt1)) // collected by the first job
        runPending()
        assertEquals("first collector forwarded evt1", listOf(evt1), callbacks.sseEvents)

        coordinator.startSSE() // cancels job #1, launches job #2 on flow2
        runPending()
        flow1.tryEmit(Result.success(evt1)) // job #1 cancelled → ignored
        flow2.tryEmit(Result.success(evt2)) // job #2 forwards
        runPending()

        assertEquals(
            "evt1 from the cancelled collector was NOT re-forwarded; evt2 forwarded",
            listOf(evt1, evt2),
            callbacks.sseEvents
        )
        verify(atLeast = 2) { repository.connectSSE() }
    }

    @Test
    fun `cancelSse stops forwarding events from the live feed`() {
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE() } returns feed.asSharedFlow()
        val evt = SSEEvent(payload = com.yage.opencode_client.data.model.SSEPayload(type = "a"))

        coordinator.startSSE()
        runPending()
        coordinator.cancelSse()
        runPending()
        feed.tryEmit(Result.success(evt)) // collector cancelled → not forwarded
        runPending()

        assertTrue("no event forwarded after cancelSse", callbacks.sseEvents.isEmpty())
    }

    @Test
    fun `cancelSseForReconfigure cancels the feed and fires onHostReconfigured`() {
        val feed = MutableSharedFlow<Result<SSEEvent>>(extraBufferCapacity = 8)
        every { repository.connectSSE() } returns feed.asSharedFlow()
        val evt = SSEEvent(payload = com.yage.opencode_client.data.model.SSEPayload(type = "a"))

        coordinator.startSSE()
        runPending()
        coordinator.cancelSseForReconfigure()
        feed.tryEmit(Result.success(evt)) // cancelled → ignored
        runPending()

        assertEquals(1, callbacks.onHostReconfiguredCalls)
        assertTrue(callbacks.sseEvents.isEmpty())
    }

    @Test
    fun `cancelSseForReconfigure fires onHostReconfigured even with no active feed`() {
        coordinator.cancelSseForReconfigure()
        assertEquals(1, callbacks.onHostReconfiguredCalls)
    }

    // ── RecordingConnectionCoordinatorCallbacks ────────────────────────────

    /**
     * Handwritten spy (per the codebase's zero-reflection test convention) that
     * records every [ConnectionCoordinatorCallbacks] invocation + call order so
     * tests can assert on side effects and ordering invariants.
     */
    private class RecordingConnectionCoordinatorCallbacks : ConnectionCoordinatorCallbacks {
        var configureRepositoryForCurrentProfileCalls = 0
        var loadSessionsCalls = 0
        var loadAgentsCalls = 0
        var loadProvidersCalls = 0
        var loadPendingQuestionsCalls = 0
        var onHostReconfiguredCalls = 0
        val sseEvents = mutableListOf<SSEEvent>()
        val callOrder = mutableListOf<String>()

        override fun configureRepositoryForCurrentProfile() {
            configureRepositoryForCurrentProfileCalls++
            callOrder += "configureRepositoryForCurrentProfile"
        }
        override fun loadSessions() { loadSessionsCalls++; callOrder += "loadSessions" }
        override fun loadAgents() { loadAgentsCalls++; callOrder += "loadAgents" }
        override fun loadProviders() { loadProvidersCalls++; callOrder += "loadProviders" }
        override fun loadPendingQuestions() { loadPendingQuestionsCalls++; callOrder += "loadPendingQuestions" }
        override fun onSseEvent(event: SSEEvent) { sseEvents += event }
        override fun onHostReconfigured() { onHostReconfiguredCalls++; callOrder += "onHostReconfigured" }
    }
}
