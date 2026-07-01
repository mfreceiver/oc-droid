package com.yage.opencode_client

import android.util.Log
import com.yage.opencode_client.data.model.Message
import com.yage.opencode_client.data.model.MessageWithParts
import com.yage.opencode_client.data.model.Part
import com.yage.opencode_client.data.model.PermissionRequest
import com.yage.opencode_client.data.model.PermissionResponse
import com.yage.opencode_client.data.model.QuestionRequest
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.model.SessionStatus
import com.yage.opencode_client.data.model.SSEEvent
import com.yage.opencode_client.data.model.SSEPayload
import com.yage.opencode_client.data.model.HealthResponse
import com.yage.opencode_client.data.model.HostProfile
import com.yage.opencode_client.data.model.BasicAuthConfig
import com.yage.opencode_client.data.model.ProvidersResponse
import com.yage.opencode_client.data.repository.HostProfileStore
import com.yage.opencode_client.data.repository.MessagesPage
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.di.AppLifecycleMonitor
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.ChatState
import com.yage.opencode_client.ui.ComposerState
import com.yage.opencode_client.ui.ConnectionState
import com.yage.opencode_client.ui.FileState
import com.yage.opencode_client.ui.HostState
import com.yage.opencode_client.ui.MainViewModel
import com.yage.opencode_client.ui.SessionListState
import com.yage.opencode_client.ui.SettingsState
import com.yage.opencode_client.ui.SliceFlows
import com.yage.opencode_client.ui.currentSession
import com.yage.opencode_client.ui.visibleMessages
import com.yage.opencode_client.ui.TrafficState
import com.yage.opencode_client.ui.TunnelActivationState
import com.yage.opencode_client.ui.UnreadState
import com.yage.opencode_client.ui.syncSlicesFromAppState
import com.yage.opencode_client.ui.session.buildSessionTree
import com.yage.opencode_client.util.SettingsManager
import com.yage.opencode_client.util.ThemeMode
import com.yage.opencode_client.util.TrafficTracker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repository: OpenCodeRepository
    private lateinit var settingsManager: SettingsManager
    private lateinit var hostProfileStore: HostProfileStore
    private lateinit var trafficTracker: TrafficTracker
    private lateinit var appLifecycleMonitor: AppLifecycleMonitor

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        repository = mockk(relaxed = true)
        settingsManager = mockk(relaxed = true)
        hostProfileStore = mockk(relaxed = true)
        trafficTracker = mockk(relaxed = true)
        appLifecycleMonitor = mockk(relaxed = true)
        // §15.2: MainViewModel.init subscribes to isInForeground via
        // onEach{}.launchIn(viewModelScope). Hand back a real foreground
        // StateFlow so the init-time subscription does not NPE.
        every { appLifecycleMonitor.isInForeground } returns MutableStateFlow(true)

        val defaultProfile = HostProfile.defaultDirect("http://server.test")
        every { hostProfileStore.currentProfile() } returns defaultProfile
        every { hostProfileStore.profiles() } returns listOf(defaultProfile)

        every { settingsManager.serverUrl } returns "http://server.test"
        every { settingsManager.username } returns null
        every { settingsManager.password } returns null
        every { settingsManager.currentSessionId } returns null
        every { settingsManager.selectedAgentName } returns null
        every { settingsManager.themeMode } returns ThemeMode.SYSTEM

        every { settingsManager.serverUrl = any() } just runs
        every { settingsManager.username = any() } just runs
        every { settingsManager.password = any() } just runs
        every { settingsManager.currentSessionId = any() } just runs
        every { settingsManager.selectedAgentName = any() } just runs
        every { settingsManager.themeMode = any() } just runs

        every { settingsManager.getDraftText(any()) } returns ""
        every { settingsManager.setDraftText(any(), any()) } just runs
        every { settingsManager.getAgentForSession(any()) } returns null
        every { settingsManager.setAgentForSession(any(), any()) } just runs

        every { repository.connectSSE() } returns emptyFlow()
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())
        coEvery { repository.getSessionsForDirectory(any(), any()) } returns Result.success(emptyList())
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        coEvery { repository.getMessages(any(), any()) } returns Result.success(emptyList())
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getPendingPermissions() } returns Result.success(emptyList())
        // §省流 F2/F3 tests call testConnection (healthy) which triggers loadInitialData
        // → these loaders. Relaxed mockk returns Object for generic Result<T> returns
        // (ClassCastException on the .getOrThrow / onSuccess path), so stub explicit
        // empty results. Safe for all other tests (additional relaxed stubs).
        coEvery { repository.getAgents() } returns Result.success(emptyList())
        coEvery { repository.getProviders() } returns Result.success(ProvidersResponse())
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        coEvery { repository.getPendingQuestions() } returns Result.success(emptyList())
    }

    private fun createViewModel(): MainViewModel {
        return MainViewModel(repository, settingsManager, hostProfileStore, trafficTracker, appLifecycleMonitor)
    }

    private fun updateState(viewModel: MainViewModel, transform: (AppState) -> AppState) {
        val field = MainViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(viewModel) as MutableStateFlow<AppState>
        val newState = transform(flow.value)
        flow.value = newState
        // §R-17 M3: production code reads the composer/file/settings SLICES
        // (e.g. dispatchSendMessage reads _composerFlow.value.inputText), not
        // the AppState mirrors. Without syncing the slices here, a test that
        // does `updateState { copy(inputText = "hi") }` followed by
        // `viewModel.sendMessage()` would see an empty slice and the send would
        // no-op. Mirror the new AppState values back into the three slices so
        // the legacy `updateState { copy(<m3 field> = ...) }` calls keep
        // driving production code correctly. (M2's connection/traffic slices
        // don't need this — no production read-site consumes them for a
        // decision the way composer is consumed by dispatchSendMessage.)
        syncAllSlicesFromState(viewModel, newState)
    }

    /**
     * §R-17 Stage 1: mirror every slice from an AppState snapshot. Delegates to
     * the PRODUCTION [syncSlicesFromAppState] so the test's mirror→slice path
     * uses the exact same field mapping as production (no duplicated field
     * lists to drift). The [SliceFlows] is assembled via reflection from
     * MainViewModel's private `_xxxFlow` fields.
     */
    @Suppress("UNCHECKED_CAST")
    private fun syncAllSlicesFromState(viewModel: MainViewModel, state: AppState) {
        fun <T> flow(name: String): MutableStateFlow<T> {
            val f = MainViewModel::class.java.getDeclaredField(name)
            f.isAccessible = true
            return f.get(viewModel) as MutableStateFlow<T>
        }
        val slices = SliceFlows(
            connection = flow("_connectionFlow"),
            traffic = flow("_trafficFlow"),
            composer = flow("_composerFlow"),
            file = flow("_fileFlow"),
            settings = flow("_settingsFlow"),
            chat = flow("_chatFlow"),
            sessionList = flow("_sessionListFlow"),
            unread = flow("_unreadFlow"),
            host = flow("_hostFlow")
        )
        syncSlicesFromAppState(state, slices)
    }

    /**
     * §R-17 M2: write the connection slice directly. Mirrors [updateState] for
     * the now-separated [ConnectionState] flow. Use this (NOT
     * `updateState { copy(isConnected=...) }`) when a test needs to seed
     * connection fields — writing them via `updateState` is a silent no-op
     * because `viewModel.state` overlays `_connectionFlow` on top of `_state`.
     */
    private fun updateConnection(
        viewModel: MainViewModel,
        transform: (ConnectionState) -> ConnectionState
    ) {
        val field = MainViewModel::class.java.getDeclaredField("_connectionFlow")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(viewModel) as MutableStateFlow<ConnectionState>
        flow.value = transform(flow.value)
    }

    /**
     * §R-17 M2: write the traffic slice directly. See [updateConnection] for
     * rationale.
     */
    private fun updateTraffic(
        viewModel: MainViewModel,
        transform: (TrafficState) -> TrafficState
    ) {
        val field = MainViewModel::class.java.getDeclaredField("_trafficFlow")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(viewModel) as MutableStateFlow<TrafficState>
        flow.value = transform(flow.value)
    }

    /**
     * §R-17 M3: write the composer slice directly. See [updateConnection] for
     * rationale. NOTE: writing the slice alone does NOT update the AppState
     * mirror on `_state` — production code always uses `writeComposer` (which
     * mirrors), but this raw helper is for tests that specifically want to
     * drive the slice. For tests that need `state.value.<composer field>` to
     * reflect the change, prefer [updateState] (which writes `_state` and is
     * what the reflection-based assertions read).
     */
    private fun updateComposer(
        viewModel: MainViewModel,
        transform: (ComposerState) -> ComposerState
    ) {
        val field = MainViewModel::class.java.getDeclaredField("_composerFlow")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(viewModel) as MutableStateFlow<ComposerState>
        flow.value = transform(flow.value)
    }

    /** §R-17 M3: write the file slice directly. See [updateComposer]. */
    private fun updateFile(
        viewModel: MainViewModel,
        transform: (FileState) -> FileState
    ) {
        val field = MainViewModel::class.java.getDeclaredField("_fileFlow")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(viewModel) as MutableStateFlow<FileState>
        flow.value = transform(flow.value)
    }

    /** §R-17 M3: write the settings slice directly. See [updateComposer]. */
    private fun updateSettings(
        viewModel: MainViewModel,
        transform: (SettingsState) -> SettingsState
    ) {
        val field = MainViewModel::class.java.getDeclaredField("_settingsFlow")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(viewModel) as MutableStateFlow<SettingsState>
        flow.value = transform(flow.value)
    }

    /** §R-17 M4: write the chat slice directly. See [updateComposer]. */
    private fun updateChat(
        viewModel: MainViewModel,
        transform: (ChatState) -> ChatState
    ) {
        val field = MainViewModel::class.java.getDeclaredField("_chatFlow")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(viewModel) as MutableStateFlow<ChatState>
        flow.value = transform(flow.value)
    }

    /** §R-17 M4: write the session-list slice directly. See [updateComposer]. */
    private fun updateSessionList(
        viewModel: MainViewModel,
        transform: (SessionListState) -> SessionListState
    ) {
        val field = MainViewModel::class.java.getDeclaredField("_sessionListFlow")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(viewModel) as MutableStateFlow<SessionListState>
        flow.value = transform(flow.value)
    }

    /** §R-17 M4: write the unread slice directly. See [updateComposer]. */
    private fun updateUnread(
        viewModel: MainViewModel,
        transform: (UnreadState) -> UnreadState
    ) {
        val field = MainViewModel::class.java.getDeclaredField("_unreadFlow")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(viewModel) as MutableStateFlow<UnreadState>
        flow.value = transform(flow.value)
    }

    /** §R-17 M4: write the host slice directly. See [updateComposer]. */
    private fun updateHost(
        viewModel: MainViewModel,
        transform: (HostState) -> HostState
    ) {
        val field = MainViewModel::class.java.getDeclaredField("_hostFlow")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(viewModel) as MutableStateFlow<HostState>
        flow.value = transform(flow.value)
    }

    private fun handleSse(viewModel: MainViewModel, event: SSEEvent) {
        val method = MainViewModel::class.java.getDeclaredMethod("handleSSEEvent", SSEEvent::class.java)
        method.isAccessible = true
        method.invoke(viewModel, event)
    }

    @Test
    fun `sendMessage success clears input`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSessions(100) } returns Result.success(
            listOf(com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/project"))
        )

        val viewModel = createViewModel()
        viewModel.selectSession("session-1")
        advanceUntilIdle()
        viewModel.setInputText("  hello world  ")
        viewModel.selectAgent("review")

        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify {
            repository.sendMessage(
                "session-1",
                "hello world",
                "review",
                null
            )
        }
        assertEquals("", viewModel.composerFlow.value.inputText)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `sendMessage ignores duplicate sends while request is in flight`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } coAnswers {
            delay(100)
            Result.success(Unit)
        }

        val viewModel = createViewModel()
        viewModel.selectSession("session-1")
        advanceUntilIdle()
        viewModel.setInputText("hello")

        viewModel.sendMessage()
        viewModel.sendMessage()

        advanceUntilIdle()

        coVerify(exactly = 1) { repository.sendMessage(any(), any(), any(), any(), any()) }
        assertFalse(viewModel.composerFlow.value.sendingSessionIds.contains("session-1"))
    }

    @Test
    fun `sendMessage success refreshes sessions`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSessions(10) } returns Result.success(
            listOf(com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/project", title = "Updated"))
        )

        val viewModel = createViewModel()
        viewModel.selectSession("session-1")
        advanceUntilIdle()
        viewModel.setInputText("hello")

        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify(atLeast = 1) { repository.getSessions(10) }
        assertEquals("Updated", viewModel.sessionListFlow.value.sessions.single().title)
    }

    @Test
    fun `sendMessage bumps current session above stale refreshed ordering`() = runTest {
        val current = com.yage.opencode_client.data.model.Session(
            id = "session-1",
            directory = "/tmp/project",
            title = "Current",
            time = com.yage.opencode_client.data.model.Session.TimeInfo(updated = 1_000)
        )
        val previousTop = com.yage.opencode_client.data.model.Session(
            id = "session-2",
            directory = "/tmp/project",
            title = "Previous Top",
            time = com.yage.opencode_client.data.model.Session.TimeInfo(updated = 2_000)
        )
        coEvery { repository.sendMessage(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSessions(10) } returns Result.success(listOf(previousTop, current))

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                sessions = listOf(previousTop, current),
                inputText = "hello"
            )
        }

        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals("session-1", buildSessionTree(viewModel.sessionListFlow.value.sessions).first().session.id)
    }

    @Test
    fun `sendMessage failure keeps input and exposes error`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any()) } returns Result.failure(IllegalStateException("send failed"))

        val viewModel = createViewModel()
        viewModel.selectSession("session-1")
        advanceUntilIdle()
        viewModel.setInputText("hello")

        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals("hello", viewModel.composerFlow.value.inputText)
        assertEquals("send failed", viewModel.state.value.error)
    }

    @Test
    fun `sendMessage still queues prompt when current session is busy`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any()) } returns Result.success(Unit)

        val viewModel = createViewModel()
        viewModel.selectSession("session-1")
        advanceUntilIdle()
        updateState(viewModel) {
            it.copy(
                inputText = "queue this next",
                sessionStatuses = it.sessionStatuses + ("session-1" to SessionStatus(type = "busy"))
            )
        }

        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify {
            repository.sendMessage(
                "session-1",
                "queue this next",
                any(),
                any()
            )
        }
        assertEquals("", viewModel.composerFlow.value.inputText)
    }

    @Test
    fun `sendMessage ignores blank input`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectSession("session-1")
        advanceUntilIdle()
        viewModel.setInputText("   ")

        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.sendMessage(any(), any(), any(), any()) }
        assertEquals("   ", viewModel.composerFlow.value.inputText)
    }

    @Test
    fun `sendMessage ignores request when no session is selected`() = runTest {
        val viewModel = createViewModel()
        viewModel.setInputText("hello")

        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.sendMessage(any(), any(), any(), any()) }
        assertEquals("hello", viewModel.composerFlow.value.inputText)
    }

    @Test
    fun `createSession and session created SSE keep a single unique session`() = runTest {
        val created = com.yage.opencode_client.data.model.Session(
            id = "session-1",
            directory = "/tmp/project",
            title = "New Session"
        )
        coEvery { repository.createSession(any()) } returns Result.success(created)

        val viewModel = createViewModel()

        viewModel.createSession()
        advanceUntilIdle()

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.created",
                    properties = buildJsonObject {
                        put(
                            "session",
                            buildJsonObject {
                                put("id", JsonPrimitive("session-1"))
                                put("directory", JsonPrimitive("/tmp/project"))
                                put("title", JsonPrimitive("Server Title"))
                            }
                        )
                    }
                )
            )
        )

        val sessions = viewModel.sessionListFlow.value.sessions
        assertEquals(1, sessions.size)
        assertEquals("session-1", sessions.single().id)
        assertEquals("Server Title", sessions.single().title)
    }

    @Test
    fun `createSessionInWorkdir enters draft mode without issuing POST session`() = runTest {
        // Deferred-create: createSessionInWorkdir must NOT call the server.
        // It only sets the repository cwd and enters draftWorkdir state; the
        // session is created lazily by sendMessage on first prompt.
        every { repository.setCurrentDirectory(any()) } just runs

        val viewModel = createViewModel()

        viewModel.createSessionInWorkdir("/home/user/myproject")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.createSession(any()) }
        verify { repository.setCurrentDirectory("/home/user/myproject") }
        assertEquals("/home/user/myproject", viewModel.composerFlow.value.draftWorkdir)
        assertNull(viewModel.chatFlow.value.currentSessionId)
    }

    @Test
    fun `sendMessage materialises draft session via POST then dispatches the prompt`() = runTest {
        val created = Session(
            id = "session-1",
            directory = "/home/user/myproject",
            title = null
        )
        coEvery { repository.createSession(title = null) } returns Result.success(created)
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(created))
        every { repository.setCurrentDirectory(any()) } just runs

        val viewModel = createViewModel()
        viewModel.createSessionInWorkdir("/home/user/myproject")
        advanceUntilIdle()

        viewModel.setInputText("hello")
        viewModel.sendMessage()
        advanceUntilIdle()

        coVerifyOrder {
            repository.setCurrentDirectory("/home/user/myproject")
            repository.createSession(title = null)
            repository.sendMessage("session-1", "hello", any(), any(), any())
        }
        assertEquals("session-1", viewModel.chatFlow.value.currentSessionId)
        assertNull(viewModel.composerFlow.value.draftWorkdir)
        assertTrue(viewModel.sessionListFlow.value.openSessionIds.contains("session-1"))
    }

    @Test
    fun `selectSession discards in-progress draft`() = runTest {
        every { repository.setCurrentDirectory(any()) } just runs

        val viewModel = createViewModel()
        viewModel.createSessionInWorkdir("/home/user/myproject")
        advanceUntilIdle()
        assertEquals("/home/user/myproject", viewModel.composerFlow.value.draftWorkdir)

        viewModel.selectSession("session-1")
        advanceUntilIdle()

        assertNull(viewModel.composerFlow.value.draftWorkdir)
    }

    @Test
    fun `createSessionInWorkdir draft surfaces error when first send cannot create session`() = runTest {
        coEvery { repository.createSession(title = null) } returns Result.failure(IllegalStateException("network error"))
        every { repository.setCurrentDirectory(any()) } just runs

        val viewModel = createViewModel()
        viewModel.createSessionInWorkdir("/tmp/fail")
        advanceUntilIdle()
        viewModel.setInputText("hi")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertNull(viewModel.chatFlow.value.currentSessionId)
        assertNotNull(viewModel.state.value.error)
        assertTrue(viewModel.state.value.error!!.contains("network error"))
    }

    @Test
    fun `session updated SSE upserts session without server refresh`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                sessions = listOf(com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/project", title = "Old"))
            )
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.updated",
                    properties = buildJsonObject {
                        put(
                            "session",
                            buildJsonObject {
                                put("id", JsonPrimitive("session-1"))
                                put("directory", JsonPrimitive("/tmp/project"))
                                put("title", JsonPrimitive("SSE Only"))
                            }
                        )
                    }
                )
            )
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getSessions(any()) }
        assertEquals("SSE Only", viewModel.sessionListFlow.value.sessions.single().title)
    }

    @Test
    fun `session updated SSE applies fresh payload title`() = runTest {
        // The server's session.updated event carries the generated title with a fresh
        // timestamp. The handler upserts the payload directly into state.sessions without
        // triggering a full server refresh, so the freshly received title must be visible
        // immediately (not clobbered by any stale concurrent snapshot).
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                sessions = listOf(
                    com.yage.opencode_client.data.model.Session(
                        id = "session-1",
                        directory = "/tmp/project",
                        title = "New session - 1700000000",
                        time = com.yage.opencode_client.data.model.Session.TimeInfo(updated = 1_000)
                    )
                )
            )
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.updated",
                    properties = buildJsonObject {
                        put(
                            "info",
                            buildJsonObject {
                                put("id", JsonPrimitive("session-1"))
                                put("directory", JsonPrimitive("/tmp/project"))
                                put("title", JsonPrimitive("Pythagorean theorem: history, proof, engineering"))
                                put(
                                    "time",
                                    buildJsonObject { put("updated", JsonPrimitive(2_000)) }
                                )
                            }
                        )
                    }
                )
            )
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getSessions(any()) }
        assertEquals(
            "Pythagorean theorem: history, proof, engineering",
            viewModel.sessionListFlow.value.sessions.single { it.id == "session-1" }.title
        )
    }

    @Test
    fun `message created SSE does not refresh sessions for non-current session`() = runTest {
        val session1 = com.yage.opencode_client.data.model.Session(
            id = "session-1",
            directory = "/tmp/project",
            title = "Current",
            time = com.yage.opencode_client.data.model.Session.TimeInfo(updated = 1_000)
        )
        val session2 = com.yage.opencode_client.data.model.Session(
            id = "session-2",
            directory = "/tmp/project",
            title = "New Activity",
            time = com.yage.opencode_client.data.model.Session.TimeInfo(updated = 2_000)
        )

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                sessions = listOf(session1, session2)
            )
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.created",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-2"))
                    }
                )
            )
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getSessions(any()) }
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
        // Order unchanged: no refresh-driven reordering
        assertEquals("session-1", viewModel.sessionListFlow.value.sessions.first().id)
    }

    @Test
    fun `message updated SSE does NOT trigger message reload`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }

        advanceUntilIdle()

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.updated",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                    }
                )
            )
        )
        // §C1 (0.1.13): message.updated no longer triggers a reload at all.
        // The periodic /message?limit=30 loop was the root cause of the OOM.
        // Live text arrives via streamingPartTexts (message.part.updated delta);
        // structural sync happens on message.created (reload) and foreground
        // catch-up.
        advanceTimeBy(2000)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getSessions(any()) }
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    @Test
    fun `message updated SSE patches message info in place and preserves parts`() = runTest {
        // S3: server carries a full { info: Message }. We replace the matching
        // Message in the split store, keeping its parts in partsByMessage.
        val part = Part(id = "part-1", messageId = "m1", sessionId = "session-1", type = "text")
        val original = Message(id = "m1", role = "assistant", agent = "build")
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                messages = listOf(original),
                partsByMessage = mapOf("m1" to listOf(part))
            )
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.updated",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put(
                            "info",
                            buildJsonObject {
                                put("id", JsonPrimitive("m1"))
                                put("sessionID", JsonPrimitive("session-1"))
                                put("role", JsonPrimitive("assistant"))
                                put("agent", JsonPrimitive("code"))
                                put("finish", JsonPrimitive("stop"))
                            }
                        )
                    }
                )
            )
        )
        advanceUntilIdle()

        val messages = viewModel.chatFlow.value.messages
        assertEquals(1, messages.size)
        // message metadata patched...
        assertEquals("m1", messages[0].id)
        assertEquals("code", messages[0].agent)
        assertEquals("stop", messages[0].finish)
        // ...parts preserved in partsByMessage...
        assertEquals(listOf(part), viewModel.chatFlow.value.partsByMessage["m1"])
        // ...and no reload issued.
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    @Test
    fun `message updated SSE for a non-current session is a no-op`() = runTest {
        // Session guard: a message.updated whose sessionID is NOT the current
        // session is a no-op (no patch, no insert, no reload) — it must not
        // touch the current view. (Server 1.17.11+ emits message.updated for
        // new messages; the current-session insert-if-absent path is covered
        // by `message updated SSE inserts a new absent message and patches an existing one`.)
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                messages = listOf(Message(id = "other", role = "assistant"))
            )
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.updated",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-other"))
                        put("info", buildJsonObject {
                            put("id", JsonPrimitive("not-loaded"))
                            put("role", JsonPrimitive("assistant"))
                        })
                    }
                )
            )
        )
        advanceUntilIdle()

        assertEquals(1, viewModel.chatFlow.value.messages.size)
        assertEquals("other", viewModel.chatFlow.value.messages[0].id)
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    @Test
    fun `loadSessions requests current limit and tracks hasMore`() = runTest {
        val sessions = (1..10).map { index ->
            com.yage.opencode_client.data.model.Session(id = "session-$index", directory = "/tmp/$index")
        }
        coEvery { repository.getSessions(10) } returns Result.success(sessions)

        val viewModel = createViewModel()

        viewModel.loadSessions()
        advanceUntilIdle()

        coVerify { repository.getSessions(10) }
        assertEquals(10, viewModel.sessionListFlow.value.loadedSessionLimit)
        assertTrue(viewModel.sessionListFlow.value.hasMoreSessions)
        assertEquals(10, viewModel.sessionListFlow.value.sessions.size)
        assertFalse(viewModel.sessionListFlow.value.isRefreshingSessions)
    }

    @Test
    fun `loadSessions clears isRefreshingSessions after successful fetch`() = runTest {
        val sessions = listOf(
            com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/1")
        )
        coEvery { repository.getSessions(any()) } returns Result.success(sessions)

        val viewModel = createViewModel()

        viewModel.loadSessions()
        advanceUntilIdle()

        assertFalse(viewModel.sessionListFlow.value.isRefreshingSessions)
    }

    @Test
    fun `loadSessions fetches sub_agent sessions created after initial load`() = runTest {
        val initialSessions = listOf(
            com.yage.opencode_client.data.model.Session(id = "parent-1", directory = "/tmp/project")
        )
        coEvery { repository.getSessions(10) } returns Result.success(initialSessions)

        val viewModel = createViewModel()
        viewModel.loadSessions()
        advanceUntilIdle()

        assertEquals(1, viewModel.sessionListFlow.value.sessions.size)
        assertEquals("parent-1", viewModel.sessionListFlow.value.sessions.single().id)

        val refreshedSessions = listOf(
            com.yage.opencode_client.data.model.Session(id = "parent-1", directory = "/tmp/project"),
            com.yage.opencode_client.data.model.Session(
                id = "child-1",
                directory = "/tmp/project",
                parentId = "parent-1"
            )
        )
        coEvery { repository.getSessions(10) } returns Result.success(refreshedSessions)

        viewModel.loadSessions()
        advanceUntilIdle()

        assertEquals(2, viewModel.sessionListFlow.value.sessions.size)
        assertEquals("child-1", viewModel.sessionListFlow.value.sessions.find { it.parentId == "parent-1" }?.id)
        assertFalse(viewModel.sessionListFlow.value.isRefreshingSessions)
    }

    @Test
    fun `loadSessions clears isRefreshingSessions on failure`() = runTest {
        coEvery { repository.getSessions(any()) } returns Result.failure(IllegalStateException("network error"))

        val viewModel = createViewModel()

        viewModel.loadSessions()
        advanceUntilIdle()

        assertFalse(viewModel.sessionListFlow.value.isRefreshingSessions)
        assertEquals("Failed to load sessions: network error", viewModel.state.value.error)
    }

    @Test
    fun `loadMoreSessions requests higher limit and replaces sessions`() = runTest {
        val initial = (1..10).map { index ->
            com.yage.opencode_client.data.model.Session(id = "session-$index", directory = "/tmp/$index")
        }
        val expanded = (1..15).map { index ->
            com.yage.opencode_client.data.model.Session(id = "session-$index", directory = "/tmp/$index")
        }
        coEvery { repository.getSessions(20) } returns Result.success(expanded)

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                sessions = initial,
                loadedSessionLimit = 10,
                hasMoreSessions = true,
                currentSessionId = "session-5"
            )
        }

        viewModel.loadMoreSessions()
        advanceUntilIdle()

        coVerify { repository.getSessions(20) }
        assertEquals(20, viewModel.sessionListFlow.value.loadedSessionLimit)
        assertFalse(viewModel.sessionListFlow.value.hasMoreSessions)
        assertEquals(15, viewModel.sessionListFlow.value.sessions.size)
        assertEquals("session-5", viewModel.chatFlow.value.currentSessionId)
    }

    @Test
    fun `loadMoreSessions ignores duplicate triggers while request is in flight`() = runTest {
        val expanded = (1..15).map { index ->
            com.yage.opencode_client.data.model.Session(id = "session-$index", directory = "/tmp/$index")
        }
        coEvery { repository.getSessions(20) } coAnswers {
            kotlinx.coroutines.delay(100)
            Result.success(expanded)
        }

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                sessions = (1..10).map { index -> com.yage.opencode_client.data.model.Session(id = "session-$index", directory = "/tmp/$index") },
                loadedSessionLimit = 10,
                hasMoreSessions = true
            )
        }

        viewModel.loadMoreSessions()
        viewModel.loadMoreSessions()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getSessions(20) }
        assertEquals(20, viewModel.sessionListFlow.value.loadedSessionLimit)
    }

    // --- #10b: auto-select guards (currentSessionId never silently replaced) ---

    @Test
    fun `loadSessions does not override non-null currentSessionId with first`() = runTest {
        // Scenario: currentSessionId is set but the session is temporarily
        // absent from the refreshed list (e.g. created moments ago, not yet
        // propagated). loadSessions must NOT silently reselect sessions.first().
        val knownSessions = listOf(
            com.yage.opencode_client.data.model.Session(id = "session-A", directory = "/tmp/a"),
            com.yage.opencode_client.data.model.Session(id = "session-B", directory = "/tmp/b")
        )
        coEvery { repository.getSessions(any()) } returns Result.success(knownSessions)

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(currentSessionId = "session-not-in-list")
        }

        viewModel.loadSessions()
        advanceUntilIdle()

        assertEquals(
            "currentSessionId must be preserved, not replaced by first()",
            "session-not-in-list",
            viewModel.chatFlow.value.currentSessionId
        )
        // Messages for the (temporarily-absent) current session are reloaded
        // so the user keeps their context.
        coVerify(atLeast = 1) { repository.getMessagesPaged("session-not-in-list", any(), any()) }
    }

    @Test
    fun `loadMoreSessions does not override non-null currentSessionId with first`() = runTest {
        val initial = (1..10).map { index ->
            com.yage.opencode_client.data.model.Session(id = "session-$index", directory = "/tmp/$index")
        }
        val expanded = (1..15).map { index ->
            com.yage.opencode_client.data.model.Session(id = "session-$index", directory = "/tmp/$index")
        }
        coEvery { repository.getSessions(20) } returns Result.success(expanded)

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                sessions = initial,
                loadedSessionLimit = 10,
                hasMoreSessions = true,
                // currentSessionId is set but the session is NOT in the refreshed
                // list — loadMore must keep it rather than reselecting first().
                currentSessionId = "session-not-in-list"
            )
        }

        viewModel.loadMoreSessions()
        advanceUntilIdle()

        assertEquals(
            "currentSessionId preserved across loadMore even when absent from refresh",
            "session-not-in-list",
            viewModel.chatFlow.value.currentSessionId
        )
    }

    // --- #10a: createSessionInWorkdir populates directorySessions ---

    @Test
    fun `createSessionInWorkdir fetches directory sessions into directorySessions map`() = runTest {
        val workdir = "/home/user/myproject"
        val existing = listOf(
            com.yage.opencode_client.data.model.Session(id = "existing-1", directory = workdir, title = "Prior chat"),
            com.yage.opencode_client.data.model.Session(id = "existing-2", directory = workdir, title = "Another")
        )
        every { repository.setCurrentDirectory(any()) } just runs
        coEvery { repository.getSessionsForDirectory(workdir, any()) } returns Result.success(existing)

        val viewModel = createViewModel()
        viewModel.createSessionInWorkdir(workdir)
        advanceUntilIdle()

        assertEquals(workdir, viewModel.composerFlow.value.draftWorkdir)
        val dirSessions = viewModel.sessionListFlow.value.directorySessions[workdir]
        assertNotNull("directorySessions should contain an entry for the workdir", dirSessions)
        assertEquals(2, dirSessions!!.size)
        assertEquals("existing-1", dirSessions[0].id)
        // The fetched sessions must NOT pollute the global sessions list.
        assertTrue(
            "directory sessions must not be written into state.sessions",
            viewModel.sessionListFlow.value.sessions.none { it.id == "existing-1" }
        )
    }

    @Test
    fun `createSessionInWorkdir directorySessions failure is silently ignored`() = runTest {
        val workdir = "/home/user/broken"
        every { repository.setCurrentDirectory(any()) } just runs
        coEvery { repository.getSessionsForDirectory(workdir, any()) } returns Result.failure(IllegalStateException("boom"))

        val viewModel = createViewModel()
        viewModel.createSessionInWorkdir(workdir)
        advanceUntilIdle()

        assertEquals(workdir, viewModel.composerFlow.value.draftWorkdir)
        assertNull(viewModel.state.value.error)
        assertTrue(viewModel.sessionListFlow.value.directorySessions.isEmpty())
    }

    // --- #13: expandedParts lifecycle ---

    @Test
    fun `togglePartExpand flips the value for a key`() = runTest {
        val viewModel = createViewModel()

        viewModel.togglePartExpand("msg-1|part-1", false)
        assertEquals(true, viewModel.expandedParts.value["msg-1|part-1"])

        viewModel.togglePartExpand("msg-1|part-1", true)
        assertEquals(false, viewModel.expandedParts.value["msg-1|part-1"])
    }

    @Test
    fun `selectSession clears expandedParts`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                sessions = listOf(com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp")),
                inputText = "draft1"
            )
        }
        viewModel.togglePartExpand("msg-1|part-1", false)
        viewModel.togglePartExpand("msg-2|part-2", false)
        assertEquals(2, viewModel.expandedParts.value.size)

        viewModel.selectSession("session-1")
        advanceUntilIdle()

        assertTrue(
            "expandedParts must be cleared on session switch",
            viewModel.expandedParts.value.isEmpty()
        )
    }

    // --- #5: saveHostProfile three-state contract ---

    @Test
    fun `saveHostProfile writes basic auth password when basicAuthEdited is true`() = runTest {
        val profile = HostProfile.defaultDirect("http://server.test").copy(
            id = "profile-1",
            basicAuth = BasicAuthConfig(username = "alice", passwordId = "profile-1")
        )

        val viewModel = createViewModel()
        viewModel.saveHostProfile(
            profile,
            basicAuthPassword = "new-secret",
            basicAuthEdited = true
        )

        verify { settingsManager.setBasicAuthPassword("profile-1", "new-secret") }
    }

    @Test
    fun `saveHostProfile removes basic auth password when edited and blank`() = runTest {
        val profile = HostProfile.defaultDirect("http://server.test").copy(
            id = "profile-1",
            basicAuth = BasicAuthConfig(username = "alice", passwordId = "profile-1")
        )

        val viewModel = createViewModel()
        viewModel.saveHostProfile(
            profile,
            basicAuthPassword = "",
            basicAuthEdited = true
        )

        // blank → setBasicAuthPassword with "" which SettingsManager maps to remove.
        verify { settingsManager.setBasicAuthPassword("profile-1", "") }
    }

    @Test
    fun `saveHostProfile skips basic auth write when basicAuthEdited is false`() = runTest {
        val profile = HostProfile.defaultDirect("http://server.test").copy(
            id = "profile-1",
            basicAuth = BasicAuthConfig(username = "alice", passwordId = "profile-1")
        )

        val viewModel = createViewModel()
        viewModel.saveHostProfile(
            profile,
            basicAuthPassword = "whatever",
            basicAuthEdited = false
        )

        verify(exactly = 0) { settingsManager.setBasicAuthPassword(any(), any()) }
    }

    @Test
    fun `saveHostProfile skips tunnel write when tunnelEdited is false`() = runTest {
        val profile = HostProfile.defaultDirect("http://server.test").copy(
            id = "profile-1",
            tunnelPasswordId = "profile-1"
        )

        val viewModel = createViewModel()
        viewModel.saveHostProfile(
            profile,
            tunnelPassword = "ignored",
            tunnelEdited = false
        )

        verify(exactly = 0) { settingsManager.setTunnelPassword(any(), any()) }
    }

    @Test
    fun `saveHostProfile writes tunnel password when tunnelEdited is true`() = runTest {
        val profile = HostProfile.defaultDirect("http://server.test").copy(
            id = "profile-1",
            tunnelPasswordId = "profile-1"
        )

        val viewModel = createViewModel()
        viewModel.saveHostProfile(
            profile,
            tunnelPassword = "tunnel-secret",
            tunnelEdited = true
        )

        verify { settingsManager.setTunnelPassword("profile-1", "tunnel-secret") }
    }

    @Test
    fun `saveHostProfile clears tunnel password when edited and blank`() = runTest {
        val profile = HostProfile.defaultDirect("http://server.test").copy(
            id = "profile-1",
            tunnelPasswordId = "profile-1"
        )

        val viewModel = createViewModel()
        viewModel.saveHostProfile(
            profile,
            tunnelPassword = "",
            tunnelEdited = true
        )

        // blank → setTunnelPassword with "" which SettingsManager maps to remove.
        verify { settingsManager.setTunnelPassword("profile-1", "") }
    }

    @Test
    fun `archiveSession archives subtree children before parent`() = runTest {
        val parent = Session(id = "parent", directory = "/tmp/project")
        val child = Session(id = "child", directory = "/tmp/project", parentId = "parent")
        coEvery { repository.updateSessionArchived("child", any()) } returns Result.success(
            child.copy(time = Session.TimeInfo(archived = 1_000))
        )
        coEvery { repository.updateSessionArchived("parent", any()) } returns Result.success(
            parent.copy(time = Session.TimeInfo(archived = 1_000))
        )

        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(sessions = listOf(parent, child)) }

        viewModel.archiveSession("parent")
        advanceUntilIdle()

        coVerifyOrder {
            repository.updateSessionArchived("child", any())
            repository.updateSessionArchived("parent", any())
        }
        assertTrue(viewModel.sessionListFlow.value.sessions.all { it.isArchived })
    }

    @Test
    fun `restoreSession restores subtree parent before children`() = runTest {
        val parent = Session(
            id = "parent",
            directory = "/tmp/project",
            time = Session.TimeInfo(archived = 1_000)
        )
        val child = Session(
            id = "child",
            directory = "/tmp/project",
            parentId = "parent",
            time = Session.TimeInfo(archived = 1_000)
        )
        coEvery { repository.updateSessionArchived("parent", -1L) } returns Result.success(
            parent.copy(time = Session.TimeInfo(archived = -1))
        )
        coEvery { repository.updateSessionArchived("child", -1L) } returns Result.success(
            child.copy(time = Session.TimeInfo(archived = -1))
        )

        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(sessions = listOf(parent, child)) }

        viewModel.restoreSession("parent")
        advanceUntilIdle()

        coVerifyOrder {
            repository.updateSessionArchived("parent", -1L)
            repository.updateSessionArchived("child", -1L)
        }
        assertFalse(viewModel.sessionListFlow.value.sessions.any { it.isArchived })
    }

    @Test
    fun `loadMessages updates selected agent from last assistant`() = runTest {
        val messages = listOf(
            MessageWithParts(info = Message(id = "u1", role = "user")),
            MessageWithParts(
                info = Message(
                    id = "a1",
                    role = "assistant",
                    agent = "plan"
                )
            )
        )
        coEvery { repository.getMessagesPaged("session-1", 5, any()) } returns Result.success(MessagesPage(messages, null))

        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }

        viewModel.loadMessages("session-1")
        advanceUntilIdle()

        assertEquals(messages.map { it.info }, viewModel.chatFlow.value.messages)
        assertEquals("plan", viewModel.settingsFlow.value.selectedAgentName)
    }

    // --- §C: visibleMessages hides non-user/assistant (system/tool/environment) ---

    @Test
    fun `visibleMessages filters out system tool and environment roles`() = runTest {
        // §C: only user and assistant messages are shown in the chat transcript.
        // system / tool / environment / etc. are dropped by visibleMessages.
        val mixed = listOf(
            Message(id = "m1", role = "user"),
            Message(id = "m2", role = "system"),
            Message(id = "m3", role = "assistant"),
            Message(id = "m4", role = "tool"),
            Message(id = "m5", role = "environment"),
            Message(id = "m6", role = "whatever-else")
        )

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(currentSessionId = "session-1", messages = mixed)
        }

        // chatState.messages is what the UI renders and is sourced from
        // AppState.visibleMessages (the filtered view), NOT the raw messages field.
        val visible = visibleMessages(viewModel.chatFlow.value.messages, currentSession(viewModel.sessionListFlow.value.sessions, viewModel.chatFlow.value.currentSessionId))
        assertEquals(listOf("m1", "m3"), visible.map { it.id })
        assertTrue(visible.all { it.isUser || it.isAssistant })
        // The raw store is unchanged — system/tool messages still live in state.
        assertEquals(6, viewModel.chatFlow.value.messages.size)
    }

    @Test
    fun `visibleMessages preserves revert-id filtering combined with role filter`() = runTest {
        // §C: the role filter must compose with the existing revert-id filter.
        // Messages with id >= revert.messageId are dropped, AND system/tool
        // messages are dropped too — regardless of which side of the revert
        // cut they fall on.
        val mixed = listOf(
            Message(id = "m1", role = "user"),
            Message(id = "m2", role = "system"),        // filtered by role
            Message(id = "m3", role = "assistant"),
            Message(id = "m4", role = "tool"),          // filtered by role + revert (id >= m4)
            Message(id = "m5", role = "user"),          // filtered by revert only (id >= m4)
            Message(id = "m6", role = "assistant")      // filtered by revert only (id >= m4)
        )
        val session = Session(
            id = "session-1",
            directory = "/tmp/project",
            revert = Session.RevertInfo(messageId = "m4")
        )

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(currentSessionId = "session-1", sessions = listOf(session), messages = mixed)
        }

        val visible = visibleMessages(viewModel.chatFlow.value.messages, currentSession(viewModel.sessionListFlow.value.sessions, viewModel.chatFlow.value.currentSessionId))
        // Revert cut keeps m1, m2, m3 (id < "m4"); role filter then drops m2 (system).
        assertEquals(listOf("m1", "m3"), visible.map { it.id })
    }

    @Test
    fun `visibleMessages without revert still applies role filter`() = runTest {
        // §C sanity: with no revert metadata, only the role filter applies.
        val mixed = listOf(
            Message(id = "m1", role = "system"),
            Message(id = "m2", role = "user"),
            Message(id = "m3", role = "environment")
        )

        val viewModel = createViewModel()
        updateState(viewModel) {
            // No revert on the session → revert filter is a no-op.
            it.copy(
                currentSessionId = "session-1",
                sessions = listOf(Session(id = "session-1", directory = "/tmp")),
                messages = mixed
            )
        }

        val visible = visibleMessages(viewModel.chatFlow.value.messages, currentSession(viewModel.sessionListFlow.value.sessions, viewModel.chatFlow.value.currentSessionId))
        assertEquals(listOf("m2"), visible.map { it.id })
    }

    @Test
    fun `handleSSEEvent appends streaming reasoning delta for current session`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.part.updated",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put(
                            "part",
                            buildJsonObject {
                                put("messageID", JsonPrimitive("message-1"))
                                put("id", JsonPrimitive("part-1"))
                                put("type", JsonPrimitive("reasoning"))
                            }
                        )
                        put("delta", JsonPrimitive("thinking"))
                    }
                )
            )
        )

        assertEquals("thinking", viewModel.chatFlow.value.streamingPartTexts["part-1"])
        assertEquals("part-1", viewModel.chatFlow.value.streamingReasoningPart?.id)
    }

    @Test
    fun `handleSSEEvent message part delta accumulates into streamingPartTexts`() = runTest {
        // message.part.delta is a distinct web event with top-level ids and a
        // field-level delta. S2/S4 accumulate into streamingPartTexts keyed by
        // bare partId (the UI contract after the split-store rekey). It must NOT
        // reload and must ignore non-current sessions.
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }

        fun delta(d: String) = handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.part.delta",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put("messageID", JsonPrimitive("message-1"))
                        put("partID", JsonPrimitive("part-1"))
                        put("field", JsonPrimitive("text"))
                        put("delta", JsonPrimitive(d))
                    }
                )
            )
        )

        delta("Hello")
        // §M5 leading edge: the first delta writes immediately (zero-latency
        // first token). The trailing-coalesce window is now open.
        assertEquals("Hello", viewModel.chatFlow.value.streamingPartTexts["part-1"])

        delta(", world")
        // §M5 trailing coalesce: subsequent deltas buffer within the 100ms
        // window — they do NOT each trigger a state write.
        assertEquals("Hello", viewModel.chatFlow.value.streamingPartTexts["part-1"])

        // Advance the virtual clock past DELTA_COALESCE_MS so the batched flush
        // appends the buffered delta in one state write.
        advanceUntilIdle()

        assertEquals("Hello, world", viewModel.chatFlow.value.streamingPartTexts["part-1"])
        // No reload issued.
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    @Test
    fun `handleSSEEvent message part delta is ignored for other sessions`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.part.delta",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-other"))
                        put("messageID", JsonPrimitive("message-1"))
                        put("partID", JsonPrimitive("part-1"))
                        put("delta", JsonPrimitive("ignored"))
                    }
                )
            )
        )

        assertTrue(viewModel.chatFlow.value.streamingPartTexts.isEmpty())
    }

    @Test
    fun `handleSSEEvent session created prepends parsed session`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(sessions = listOf(com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/old")))
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.created",
                    properties = buildJsonObject {
                        put(
                            "session",
                            buildJsonObject {
                                put("id", JsonPrimitive("session-2"))
                                put("directory", JsonPrimitive("/tmp/project"))
                                put("title", JsonPrimitive("New Session"))
                            }
                        )
                    }
                )
            )
        )

        assertEquals(listOf("session-2", "session-1"), viewModel.sessionListFlow.value.sessions.map { it.id })
    }

    @Test
    fun `handleSSEEvent session updated replaces existing session title`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(sessions = listOf(
                com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/project", title = null)
            ))
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.updated",
                    properties = buildJsonObject {
                        put(
                            "info",
                            buildJsonObject {
                                put("id", JsonPrimitive("session-1"))
                                put("directory", JsonPrimitive("/tmp/project"))
                                put("title", JsonPrimitive("Refactor auth module"))
                            }
                        )
                    }
                )
            )
        )

        val sessions = viewModel.sessionListFlow.value.sessions
        assertEquals(1, sessions.size)
        assertEquals("session-1", sessions[0].id)
        assertEquals("Refactor auth module", sessions[0].title)
    }

    @Test
    fun `handleSSEEvent session updated inserts unknown session`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(sessions = listOf(
                com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/old")
            ))
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.updated",
                    properties = buildJsonObject {
                        put(
                            "session",
                            buildJsonObject {
                                put("id", JsonPrimitive("session-new"))
                                put("directory", JsonPrimitive("/tmp/new"))
                                put("title", JsonPrimitive("New Feature"))
                            }
                        )
                    }
                )
            )
        )

        val sessions = viewModel.sessionListFlow.value.sessions
        assertEquals(2, sessions.size)
        assertEquals("session-new", sessions[0].id)
        assertEquals("New Feature", sessions[0].title)
        assertEquals("session-1", sessions[1].id)
    }

    @Test
    fun `handleSSEEvent missing delta clears streaming state and refreshes messages`() = runTest {
        val messages = listOf(MessageWithParts(info = Message(id = "a2", role = "assistant")))
        coEvery { repository.getMessagesPaged("session-1", 5, any()) } returns Result.success(MessagesPage(messages, null))
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                streamingPartTexts = mapOf("part-1" to "partial"),
                streamingReasoningPart = Part(id = "part-1", messageId = "message-1", sessionId = "session-1", type = "reasoning")
            )
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.part.updated",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put("part", buildJsonObject { put("type", JsonPrimitive("reasoning")) })
                    }
                )
            )
        )
        advanceTimeBy(1000)
        advanceUntilIdle()

        assertTrue(viewModel.chatFlow.value.streamingPartTexts.isEmpty())
        assertNull(viewModel.chatFlow.value.streamingReasoningPart)
        assertEquals(messages.map { it.info }, viewModel.chatFlow.value.messages)
    }

    @Test
    fun `handleSSEEvent ignores message updates when no current session is selected`() = runTest {
        val viewModel = createViewModel()

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.part.updated",
                    properties = buildJsonObject {
                        put("part", buildJsonObject { put("type", JsonPrimitive("reasoning")) })
                        put("delta", JsonPrimitive("ignored"))
                    }
                )
            )
        )
        advanceUntilIdle()

        assertTrue(viewModel.chatFlow.value.streamingPartTexts.isEmpty())
        assertNull(viewModel.chatFlow.value.streamingReasoningPart)
    }

    @Test
    fun `handleSSEEvent idle status finalizes streaming overlay via reload when overlay non-empty`() = runTest {
        // §append-safe finalization (gpter MAJOR): the overlay-clear in
        // launchLoadMessages is gated on !busy, so a reload that ran while busy
        // preserves the overlay. When the CURRENT session then settles to idle
        // with a still-live overlay, session.status idle triggers a resetLimit
        // reload that reconciles against the now-persisted authoritative window
        // and clears the overlay (status is now idle, so the gated clear runs).
        val messages = listOf(MessageWithParts(info = Message(id = "a1", role = "assistant")))
        coEvery { repository.getMessagesPaged("session-1", 5, any()) } returns Result.success(MessagesPage(messages, null))
        val viewModel = createViewModel()
        val streaming = mapOf("part-1" to "partial")
        val reasoning = Part(id = "part-1", messageId = "message-1", sessionId = "session-1", type = "reasoning")
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                streamingPartTexts = streaming,
                streamingReasoningPart = reasoning
            )
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.status",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put(
                            "status",
                            buildJsonObject {
                                put("type", JsonPrimitive("idle"))
                            }
                        )
                    }
                )
            )
        )
        advanceTimeBy(1000)
        advanceUntilIdle()

        // Status badge updated to idle...
        val status = viewModel.sessionListFlow.value.sessionStatuses["session-1"]
        assertNotNull(status)
        assertFalse(status!!.isBusy)
        // ...overlay cleared by the finalization reload...
        assertTrue(viewModel.chatFlow.value.streamingPartTexts.isEmpty())
        assertNull(viewModel.chatFlow.value.streamingReasoningPart)
        // ...and a resetLimit reload was issued.
        coVerify(atLeast = 1) { repository.getMessagesPaged("session-1", any(), any()) }
    }

    @Test
    fun `handleSSEEvent idle status skips reload when streaming overlay already empty`() = runTest {
        // §append-safe finalization: the idle reload is gated on a non-empty
        // overlay to avoid redundant reloads. With no live overlay, idle only
        // updates the status badge (SSE-trust model preserved).
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                streamingPartTexts = emptyMap(),
                streamingReasoningPart = null
            )
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.status",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put(
                            "status",
                            buildJsonObject {
                                put("type", JsonPrimitive("idle"))
                            }
                        )
                    }
                )
            )
        )
        advanceTimeBy(1000)
        advanceUntilIdle()

        val status = viewModel.sessionListFlow.value.sessionStatuses["session-1"]
        assertNotNull(status)
        assertFalse(status!!.isBusy)
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    @Test
    fun `handleSSEEvent permission asked refreshes pending permissions`() = runTest {
        val permissions = listOf(
            PermissionRequest(id = "perm-1", sessionId = "session-1", permission = "file.read")
        )
        coEvery { repository.getPendingPermissions() } returns Result.success(permissions)
        val viewModel = createViewModel()

        handleSse(
            viewModel,
            SSEEvent(payload = SSEPayload(type = "permission.asked"))
        )
        advanceUntilIdle()

        assertEquals(permissions, viewModel.sessionListFlow.value.pendingPermissions)
    }

    @Test
    fun `setInputText with active session saves draft to settings manager`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "s1") }

        viewModel.setInputText("hello")

        verify { settingsManager.setDraftText("s1", "hello") }
    }

    @Test
    fun `selectSession saves old draft and restores new draft from settings manager`() = runTest {
        every { settingsManager.getDraftText("s2") } returns "draft2"

        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "s1", inputText = "draft1") }

        viewModel.selectSession("s2")
        advanceUntilIdle()

        verify { settingsManager.setDraftText("s1", "draft1") }
        verify { settingsManager.getDraftText("s2") }
        assertEquals("draft2", viewModel.composerFlow.value.inputText)
    }

    @Test
    fun `selectAgent with active session saves agent name per session`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "s1") }

        viewModel.selectAgent("oracle")

        verify { settingsManager.setAgentForSession("s1", "oracle") }
    }

    @Test
    fun `sendMessage on success clears draft for current session`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any()) } returns Result.success(Unit)

        val viewModel = createViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setInputText("hello")

        viewModel.sendMessage()
        advanceUntilIdle()

        verify { settingsManager.setDraftText("s1", "") }
    }

    @Test
    fun `abortSession calls repository for current session`() = runTest {
        coEvery { repository.abortSession("session-1") } returns Result.success(Unit)

        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }

        viewModel.abortSession()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.abortSession("session-1") }
    }

    @Test
    fun `deleteSession removes deleted session from state`() = runTest {
        coEvery { repository.deleteSession("session-1") } returns Result.success(Unit)

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                sessions = listOf(
                    com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/one"),
                    com.yage.opencode_client.data.model.Session(id = "session-2", directory = "/tmp/two")
                ),
                currentSessionId = "session-2"
            )
        }

        viewModel.deleteSession("session-1")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.deleteSession("session-1") }
        assertEquals(listOf("session-2"), viewModel.sessionListFlow.value.sessions.map { it.id })
    }

    @Test
    fun `respondPermission calls repository and removes pending permission`() = runTest {
        coEvery {
            repository.respondPermission("session-1", "perm-1", PermissionResponse.ALWAYS)
        } returns Result.success(Unit)

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                pendingPermissions = listOf(
                    PermissionRequest(id = "perm-1", sessionId = "session-1", permission = "file.write"),
                    PermissionRequest(id = "perm-2", sessionId = "session-2", permission = "file.read")
                )
            )
        }

        viewModel.respondPermission("session-1", "perm-1", PermissionResponse.ALWAYS)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            repository.respondPermission("session-1", "perm-1", PermissionResponse.ALWAYS)
        }
        assertEquals(listOf("perm-2"), viewModel.sessionListFlow.value.pendingPermissions.map { it.id })
    }

    @Test
    fun `loadPendingPermissions loads permissions into state`() = runTest {
        val permissions = listOf(
            PermissionRequest(id = "perm-1", sessionId = "session-1", permission = "file.read"),
            PermissionRequest(id = "perm-2", sessionId = "session-2", permission = "command.exec")
        )
        coEvery { repository.getPendingPermissions() } returns Result.success(permissions)

        val viewModel = createViewModel()

        viewModel.loadPendingPermissions()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getPendingPermissions() }
        assertEquals(permissions, viewModel.sessionListFlow.value.pendingPermissions)
    }

    @Test
    fun `replyQuestion calls repository and removes answered question`() = runTest {
        val answers = listOf(listOf("React"), listOf("Custom"))
        coEvery { repository.replyQuestion("question-1", answers) } returns Result.success(Unit)

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                pendingQuestions = listOf(
                    QuestionRequest(
                        id = "question-1",
                        sessionId = "session-1",
                        questions = emptyList()
                    ),
                    QuestionRequest(
                        id = "question-2",
                        sessionId = "session-2",
                        questions = emptyList()
                    )
                )
            )
        }

        viewModel.replyQuestion("question-1", answers)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.replyQuestion("question-1", answers) }
        assertEquals(listOf("question-2"), viewModel.sessionListFlow.value.pendingQuestions.map { it.id })
    }

    @Test
    fun `rejectQuestion calls repository and removes rejected question`() = runTest {
        coEvery { repository.rejectQuestion("question-1") } returns Result.success(Unit)

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                pendingQuestions = listOf(
                    QuestionRequest(
                        id = "question-1",
                        sessionId = "session-1",
                        questions = emptyList()
                    ),
                    QuestionRequest(
                        id = "question-2",
                        sessionId = "session-2",
                        questions = emptyList()
                    )
                )
            )
        }

        viewModel.rejectQuestion("question-1")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.rejectQuestion("question-1") }
        assertEquals(listOf("question-2"), viewModel.sessionListFlow.value.pendingQuestions.map { it.id })
    }

    @Test
    fun `testConnection skips second health check within cooldown`() = runTest {
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = false, version = "1.0"))

        val viewModel = createViewModel()

        viewModel.testConnection()
        viewModel.testConnection()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.checkHealth() }
    }

    // ── §省流 三方评审致命修复 F1/F2/F3 ──────────────────────────────────────

    /**
     * §F1 (3/3 一致确认致命): 持久化 lowTrafficMode=true 后进程重建冷启动必须自动
     * 启动 M1 (LowTrafficPoller)，否则 SSE 被 M3 守卫跳过、M1 又从未 start → 界面静止。
     *
     * 验证：在 createViewModel 前把 settingsManager.lowTrafficMode 桩为 true (模拟
     * 持久化值)，构造后用反射读 LowTrafficPoller.active 必须为 true。
     *
     * cleanup: onCleared() 停止轮询的 while(isActive){delay;doTick} 死循环——它跑在
     * viewModelScope 上 (与 runTest 共享 StandardTestDispatcher)，不停掉会让 runTest
     * 的自动 cleanup 无限推进虚拟时间。
     */
    @Test
    fun `cold start with persisted lowTraffic=true auto-starts the poller (validates F1)`() = runTest {
        every { settingsManager.lowTrafficMode } returns true

        val viewModel = createViewModel()
        try {
            val active = readLowTrafficPollerActive(viewModel)
            assertTrue(
                "lowTrafficPoller must auto-start on cold init when lowTrafficMode is persisted true (F1)",
                active
            )
        } finally {
            stopLowTrafficPoller(viewModel)
        }
    }

    /**
     * §F2 (gpter 致命#2): 运行时开省流必须取消已有 SSE firehose，否则变成双通道
     * (69MB/min)。M3 守卫只防新 startSSE，不会停已在跑的 collector。
     *
     * 验证：先 testConnection (healthy) 让 startSSE 跑起来 → sseJob 非 null；
     * 再 onLowTrafficModeChanged(true) → connectionCoordinator.cancelSse() 必须把
     * sseJob 置 null。
     */
    @Test
    fun `enabling low-traffic mode cancels in-flight SSE feed (validates F2)`() = runTest {
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = true, version = "1.0"))
        // emptyFlow: collector completes immediately but sseJob field still holds the
        // (completed) Job reference until cancelSse nulls it — exactly what we assert.
        every { repository.connectSSE() } returns emptyFlow()

        val viewModel = createViewModel()
        try {
            viewModel.testConnection(force = true)
            advanceUntilIdle()

            assertNotNull("SSE feed should be active after healthy connect", readSseJob(viewModel))

            viewModel.onLowTrafficModeChanged(true)

            assertNull(
                "SSE feed must be cancelled when enabling low-traffic (F2 — no dual-channel firehose)",
                readSseJob(viewModel)
            )
        } finally {
            stopLowTrafficPoller(viewModel)
        }
    }

    /**
     * §F3 (gpter 致命#3): 运行时关省流必须恢复 SSE 实时同步。M3 守卫此时读到
     * lowTrafficMode=false，startSSE 不再被跳过。
     *
     * 验证：lowTrafficMode=true 起步 (init 跳过 SSE)；标记 connected；调
     * onLowTrafficModeChanged(false) → connectionCoordinator.startSSE() 必须被调用
     * (sseJob 非 null + connectSSE 被访问)。
     */
    @Test
    fun `disabling low-traffic mode restores SSE feed when connected (validates F3)`() = runTest {
        // Dynamic lowTrafficMode stub so onLowTrafficModeChanged's setter takes effect
        // (otherwise the M3 guard inside startSSE would keep blocking).
        var lowTraffic = true
        every { settingsManager.lowTrafficMode } answers { lowTraffic }
        every { settingsManager.lowTrafficMode = any<Boolean>() } answers { lowTraffic = firstArg() }
        every { repository.connectSSE() } returns emptyFlow()

        val viewModel = createViewModel()
        try {
            // lowTraffic=true → init starts poller, SSE skipped (M3 guard).
            assertNull("no SSE feed while lowTrafficMode is on", readSseJob(viewModel))
            verify(exactly = 0) { repository.connectSSE() }

            // Mark connected so onLowTrafficModeChanged(false) takes the startSSE branch.
            updateConnection(viewModel) { it.copy(isConnected = true) }

            viewModel.onLowTrafficModeChanged(false)
            advanceUntilIdle()

            assertNotNull(
                "SSE feed must be restored when disabling low-traffic while connected (F3)",
                readSseJob(viewModel)
            )
            verify(atLeast = 1) { repository.connectSSE() }
        } finally {
            stopLowTrafficPoller(viewModel)
        }
    }

    /**
     * §F3 negative branch: 关省流时若未连接，走 forceReconnect 路径 (testConnection
     * force=true)，成功后内部会 startSSE。验证 forceReconnect 被触发 (checkHealth 重新探测)。
     */
    @Test
    fun `disabling low-traffic mode while disconnected triggers forceReconnect (validates F3)`() = runTest {
        var lowTraffic = true
        every { settingsManager.lowTrafficMode } answers { lowTraffic }
        every { settingsManager.lowTrafficMode = any<Boolean>() } answers { lowTraffic = firstArg() }
        every { repository.connectSSE() } returns emptyFlow()
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = true, version = "1.0"))

        val viewModel = createViewModel()
        try {
            // isConnected stays false (default) → onLowTrafficModeChanged(false) takes forceReconnect.
            assertEquals(false, viewModel.state.value.isConnected)

            viewModel.onLowTrafficModeChanged(false)
            advanceUntilIdle()

            // forceReconnect = testConnection(force=true) → checkHealth probed → on success startSSE.
            coVerify(atLeast = 1) { repository.checkHealth() }
            assertNotNull(
                "forceReconnect path must startSSE on healthy reconnect (F3 disconnected branch)",
                readSseJob(viewModel)
            )
        } finally {
            stopLowTrafficPoller(viewModel)
        }
    }

    // ── Reflection helpers for the F1/F2/F3 tests ───────────────────────────
    // (connectionCoordinator / sseJob / lowTrafficPoller.active are all private;
    //  the production code never needs them exposed, so we read via reflection
    //  rather than widening visibility for tests.)

    private fun readSseJob(viewModel: MainViewModel): Job? {
        val coordinatorField = MainViewModel::class.java.getDeclaredField("connectionCoordinator")
        coordinatorField.isAccessible = true
        val coordinator = coordinatorField.get(viewModel)
        val sseJobField = coordinator.javaClass.getDeclaredField("sseJob")
        sseJobField.isAccessible = true
        return sseJobField.get(coordinator) as? Job
    }

    private fun readLowTrafficPollerActive(viewModel: MainViewModel): Boolean {
        val pollerField = MainViewModel::class.java.getDeclaredField("lowTrafficPoller")
        pollerField.isAccessible = true
        val poller = pollerField.get(viewModel)
        val activeField = poller.javaClass.getDeclaredField("active")
        activeField.isAccessible = true
        return activeField.getBoolean(poller)
    }

    /**
     * Stops the [LowTrafficPoller] background loop. The poller's
     * `while(isActive){delay(5000); doTick()}` runs on viewModelScope which shares
     * runTest's StandardTestDispatcher — without stopping it, runTest's auto-cleanup
     * would advance virtual time forever. onCleared() is protected so can't be called
     * directly; stop() is public on the internal class (same module).
     */
    private fun stopLowTrafficPoller(viewModel: MainViewModel) {
        val pollerField = MainViewModel::class.java.getDeclaredField("lowTrafficPoller")
        pollerField.isAccessible = true
        val poller = pollerField.get(viewModel) as com.yage.opencode_client.ui.controller.LowTrafficPoller
        poller.stop()
    }

    @Test
    fun `handleSSEEvent message created refreshes messages for current session`() = runTest {
        val messages = listOf(MessageWithParts(info = Message(id = "m1", role = "assistant")))
        coEvery { repository.getMessagesPaged("session-1", 5, any()) } returns Result.success(MessagesPage(messages, null))
        coEvery { repository.getSessions(100) } returns Result.success(
            listOf(com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/project"))
        )

        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.created",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                    }
                )
            )
        )
        advanceTimeBy(400)
        advanceUntilIdle()

        assertEquals(messages.map { it.info }, viewModel.chatFlow.value.messages)
    }

    @Test
    fun `authoritative reload clears stale streaming overlay so finalized parts are not masked`() = runTest {
        // Regression (gpter S0-S4 BLOCKER): a resetLimit=true reload (e.g.
        // message.created) fetches the authoritative latest window. Any stale
        // streaming overlay for those messages must be cleared atomically,
        // else the partial overlay would mask the finalized part.text in the UI.
        val finalizedPart = Part(id = "p1", messageId = "m1", sessionId = "session-1", type = "text", text = "Hello world!")
        val messages = listOf(MessageWithParts(info = Message(id = "m1", role = "assistant"), parts = listOf(finalizedPart)))
        coEvery { repository.getMessagesPaged("session-1", 5, any()) } returns Result.success(MessagesPage(messages, null))

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                // Stale partial overlay from a just-finished stream:
                streamingPartTexts = mapOf("p1" to "Hello wor")
            )
        }

        // message.created → authoritative reload (resetLimit=true).
        handleSse(
            viewModel,
            SSEEvent(payload = SSEPayload(type = "message.created", properties = buildJsonObject {
                put("sessionID", JsonPrimitive("session-1"))
            }))
        )
        advanceTimeBy(400)
        advanceUntilIdle()

        // Overlay cleared (finalization boundary)...
        assertTrue("streaming overlay must be cleared on authoritative reload",
            viewModel.chatFlow.value.streamingPartTexts.isEmpty())
        assertNull(viewModel.chatFlow.value.streamingReasoningPart)
        // ...and the finalized authoritative part is present.
        assertEquals(listOf(finalizedPart), viewModel.chatFlow.value.partsByMessage["m1"])
    }

    @Test
    fun `handleSSEEvent question asked appends pending question`() = runTest {
        val viewModel = createViewModel()

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "question.asked",
                    properties = buildJsonObject {
                        put("id", JsonPrimitive("question-1"))
                        put("sessionID", JsonPrimitive("session-1"))
                        put(
                            "questions",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("question", JsonPrimitive("What framework do you use?"))
                                        put("header", JsonPrimitive("Framework Choice"))
                                        put(
                                            "options",
                                            buildJsonArray {
                                                add(
                                                    buildJsonObject {
                                                        put("label", JsonPrimitive("React"))
                                                        put("description", JsonPrimitive("Popular UI library"))
                                                    }
                                                )
                                            }
                                        )
                                        put("multiple", JsonPrimitive(false))
                                        put("custom", JsonPrimitive(true))
                                    }
                                )
                            }
                        )
                    }
                )
            )
        )

        assertEquals(listOf("question-1"), viewModel.sessionListFlow.value.pendingQuestions.map { it.id })
        assertEquals("session-1", viewModel.sessionListFlow.value.pendingQuestions.single().sessionId)
    }

    @Test
    fun `handleSSEEvent question rejected removes pending question`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                pendingQuestions = listOf(
                    QuestionRequest(id = "question-1", sessionId = "session-1", questions = emptyList()),
                    QuestionRequest(id = "question-2", sessionId = "session-2", questions = emptyList())
                )
            )
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "question.rejected",
                    properties = buildJsonObject {
                        put("requestID", JsonPrimitive("question-1"))
                    }
                )
            )
        )

        assertEquals(listOf("question-2"), viewModel.sessionListFlow.value.pendingQuestions.map { it.id })
    }

    @Test
    fun `activateTunnelForCurrentHost surfaces error when profile has no tunnelPasswordId`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.activateTunnelForCurrentHost()
        advanceUntilIdle()

        // Does not call the repository, but now surfaces a specific error so the
        // user knows why activation did nothing (previously a silent no-op).
        coVerify(exactly = 0) { repository.activateTunnel(any(), any()) }
        assertTrue(viewModel.connectionFlow.value.tunnelActivationState is TunnelActivationState.Error)
        assertNotNull(viewModel.state.value.error)
    }

    @Test
    fun `activateTunnelForCurrentHost surfaces error when tunnel password is empty`() = runTest {
        val profileWithTunnel = HostProfile.defaultDirect("http://server.test").copy(
            tunnelPasswordId = "profile-1"
        )
        every { hostProfileStore.currentProfile() } returns profileWithTunnel
        every { settingsManager.getTunnelPassword("profile-1") } returns null

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.activateTunnelForCurrentHost()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.activateTunnel(any(), any()) }
        assertTrue(viewModel.connectionFlow.value.tunnelActivationState is TunnelActivationState.Error)
        assertNotNull(viewModel.state.value.error)
    }

    @Test
    fun `activateTunnelForCurrentHost sets Loading then Success on success`() = runTest {
        val profileWithTunnel = HostProfile.defaultDirect("http://server.test").copy(
            tunnelPasswordId = "profile-1"
        )
        every { hostProfileStore.currentProfile() } returns profileWithTunnel
        every { settingsManager.getTunnelPassword("profile-1") } returns "tunnel-secret"
        coEvery { repository.activateTunnel("http://server.test", "tunnel-secret") } returns Result.success(Unit)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.activateTunnelForCurrentHost()
        advanceUntilIdle()

        coVerify { repository.activateTunnel("http://server.test", "tunnel-secret") }
        assertEquals(
            com.yage.opencode_client.ui.TunnelActivationState.Success,
            viewModel.connectionFlow.value.tunnelActivationState
        )
    }

    @Test
    fun `activateTunnelForCurrentHost sets Error on failure`() = runTest {
        val profileWithTunnel = HostProfile.defaultDirect("http://server.test").copy(
            tunnelPasswordId = "profile-1"
        )
        every { hostProfileStore.currentProfile() } returns profileWithTunnel
        every { settingsManager.getTunnelPassword("profile-1") } returns "bad-password"
        coEvery {
            repository.activateTunnel("http://server.test", "bad-password")
        } returns Result.failure(IllegalStateException("Tunnel activation failed 403: Forbidden"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.activateTunnelForCurrentHost()
        advanceUntilIdle()

        val activationState = viewModel.connectionFlow.value.tunnelActivationState
        assertTrue(activationState is com.yage.opencode_client.ui.TunnelActivationState.Error)
        assertTrue((activationState as com.yage.opencode_client.ui.TunnelActivationState.Error).message.contains("403"))
    }

    // --- #10: directory-only session selection upserts into sessions (A) ---

    @Test
    fun `selectSession on directorySessions-only session upserts it and preserves workdir`() = runTest {
        val workdir = "/home/user/project"
        val dirSession = com.yage.opencode_client.data.model.Session(
            id = "dir-only-1", directory = workdir, title = "From directory"
        )
        every { repository.setCurrentDirectory(any()) } just runs

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(directorySessions = mapOf(workdir to listOf(dirSession)))
        }
        // Precondition: the session lives only in directorySessions.
        assertTrue(viewModel.sessionListFlow.value.sessions.none { it.id == "dir-only-1" })

        viewModel.selectSession("dir-only-1")
        advanceUntilIdle()

        // #10: the directory session must be upserted so currentSession resolves
        // (previously it stayed null and the workdir was dropped).
        assertEquals("dir-only-1", viewModel.chatFlow.value.currentSessionId)
        assertNotNull(currentSession(viewModel.sessionListFlow.value.sessions, viewModel.chatFlow.value.currentSessionId))
        assertEquals("dir-only-1", currentSession(viewModel.sessionListFlow.value.sessions, viewModel.chatFlow.value.currentSessionId)?.id)
        // workdir sync must target the directory session's directory, not null.
        verify { repository.setCurrentDirectory(workdir) }
    }

    // --- #14: openSubAgent surfaces error when child unresolvable (B) ---

    @Test
    fun `openSubAgent surfaces error and keeps currentSessionId when child cannot be resolved`() = runTest {
        coEvery { repository.getSession("child-missing") } returns Result.failure(IllegalStateException("404"))

        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "parent-1") }
        val beforeId = viewModel.chatFlow.value.currentSessionId

        viewModel.openSubAgent("child-missing")
        advanceUntilIdle()

        assertEquals(beforeId, viewModel.chatFlow.value.currentSessionId)
        assertNotNull("error channel must be set when child session is unavailable", viewModel.state.value.error)
    }

    // --- #13/#16: toggle collapses a default-expanded card on first tap (C) ---

    @Test
    fun `togglePartExpand collapses a default-expanded card on first tap`() = runTest {
        // Regression: a running tool card displays expanded by default
        // (expanded = expandedParts[key] ?: isRunning = true). The first tap
        // must collapse it, which requires the toggle to invert the displayed
        // value supplied by the caller, not the raw map value (which is absent).
        val viewModel = createViewModel()

        viewModel.togglePartExpand("msg-1|tool-1", currentValue = true)

        assertEquals(false, viewModel.expandedParts.value["msg-1|tool-1"])
    }

    // --- #10: closeSession preserves closed draft, does not pollute next (F) ---

    @Test
    fun `closeSession preserves closed session draft and does not pollute next session draft`() = runTest {
        every { settingsManager.openSessionIds } returns listOf("s1", "s2")
        every { settingsManager.openSessionIds = any() } just runs
        every { settingsManager.getDraftText("s2") } returns "s2draft"
        every { repository.setCurrentDirectory(any()) } just runs

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "s1",
                inputText = "s1-unsent-draft",
                openSessionIds = listOf("s1", "s2")
            )
        }

        viewModel.closeSession("s1")
        advanceUntilIdle()

        // Closed session's draft must be saved under its own id (not lost).
        verify(atLeast = 1) { settingsManager.setDraftText("s1", "s1-unsent-draft") }
        // The next session must NOT inherit the closed session's draft text.
        verify(exactly = 0) { settingsManager.setDraftText("s2", "s1-unsent-draft") }
        // s2 becomes current and its own draft is restored.
        assertEquals("s2", viewModel.chatFlow.value.currentSessionId)
        assertEquals("s2draft", viewModel.composerFlow.value.inputText)
    }

    // --- #10: deleteSession syncs settingsManager when no sessions remain (E) ---

    @Test
    fun `deleteSession clears persisted currentSessionId when no sessions remain`() = runTest {
        coEvery { repository.deleteSession("s1") } returns Result.success(Unit)

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                sessions = listOf(com.yage.opencode_client.data.model.Session(id = "s1", directory = "/tmp")),
                currentSessionId = "s1"
            )
        }

        viewModel.deleteSession("s1")
        advanceUntilIdle()

        assertNull(viewModel.chatFlow.value.currentSessionId)
        verify { settingsManager.currentSessionId = null }
    }

    // --- #10: draft sendMessage double-tap creates session only once (G) ---

    @Test
    fun `sendMessage draft mode creates session only once on rapid double tap`() = runTest {
        val created = com.yage.opencode_client.data.model.Session(id = "s1", directory = "/home/user/proj")
        coEvery { repository.createSession(title = null) } returns Result.success(created)
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(created))
        every { repository.setCurrentDirectory(any()) } just runs

        val viewModel = createViewModel()
        viewModel.createSessionInWorkdir("/home/user/proj")
        advanceUntilIdle()
        viewModel.setInputText("hello")

        // Rapid double-tap with no advanceUntilIdle between the two calls:
        // the second invoke must observe draftWorkdir=null (cleared sync by
        // the first) and early-return instead of issuing a second POST /session.
        viewModel.sendMessage()
        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.createSession(title = null) }
    }

    // --- #10: deleteSession purges directorySessions ghost (Fix 1) ---

    @Test
    fun `deleteSession removes the session from directorySessions so it cannot be re-selected`() = runTest {
        val workdir = "/home/user/project"
        val ghost = com.yage.opencode_client.data.model.Session(
            id = "dir-ghost", directory = workdir, title = "From directory"
        )
        coEvery { repository.deleteSession("dir-ghost") } returns Result.success(Unit)

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(directorySessions = mapOf(workdir to listOf(ghost)))
        }
        // Precondition: the session lives in directorySessions (not in sessions).
        assertTrue(viewModel.sessionListFlow.value.directorySessions[workdir]?.any { it.id == "dir-ghost" } == true)
        assertTrue(viewModel.sessionListFlow.value.sessions.none { it.id == "dir-ghost" })

        viewModel.deleteSession("dir-ghost")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.deleteSession("dir-ghost") }
        // The ghost must be gone from directorySessions so the union UI cannot
        // render / re-select it.
        val remaining = viewModel.sessionListFlow.value.directorySessions[workdir].orEmpty()
        assertTrue("deleted id must be purged from directorySessions", remaining.none { it.id == "dir-ghost" })
    }

    // --- #10: draft createSession failure restores draftWorkdir (Fix 2) ---

    @Test
    fun `sendMessage draft mode restores draftWorkdir when createSession fails so user can retry`() = runTest {
        coEvery { repository.createSession(title = null) } returns Result.failure(IllegalStateException("network error"))
        every { repository.setCurrentDirectory(any()) } just runs

        val viewModel = createViewModel()
        viewModel.createSessionInWorkdir("/home/user/retry-proj")
        advanceUntilIdle()
        viewModel.setInputText("hello")

        viewModel.sendMessage()
        advanceUntilIdle()

        // draft mode restored — composer stays in draft, user can retry
        assertEquals("/home/user/retry-proj", viewModel.composerFlow.value.draftWorkdir)
        assertNull(viewModel.chatFlow.value.currentSessionId)
        // input text preserved for retry
        assertEquals("hello", viewModel.composerFlow.value.inputText)
        assertNotNull(viewModel.state.value.error)
        assertTrue(viewModel.state.value.error!!.contains("network error"))
    }

    // --- §Per-session message cache (LRU) ---

    /**
     * Cache (a): switching A→B→A restores A's messages without re-wiping.
     *
     * Setup: load A with [m_a1, m_a2]. Switch to B. Re-mock A's fetch to
     * return ONLY [m_a1] (simulating the server returning a smaller window
     * this time — the discriminating case). Switch back to A.
     *
     * Without the cache: cold load with resetLimit=true would produce
     * state.messages = [m_a1] only (m_a2 lost).
     *
     * With the cache: state.messages still contains m_a2 because the cached
     * window seeds it and the post-restore resetLimit=false merge keeps it
     * via the §preserveUnfetched branch (m_a2.id ∉ fetchedIds).
     */
    @Test
    fun `session switch A to B to A restores cached messages without re-wiping`() = runTest {
        val sessionA = Session(id = "session-A", directory = "/tmp/a")
        val sessionB = Session(id = "session-B", directory = "/tmp/b")
        val aMessagesInitial = listOf(
            MessageWithParts(info = Message(id = "m_a1", role = "user")),
            MessageWithParts(info = Message(id = "m_a2", role = "assistant"))
        )
        val bMessages = listOf(MessageWithParts(info = Message(id = "m_b1", role = "user")))
        // After A is cached, re-mock A to return only m_a1 — without the
        // cache this would silently lose m_a2.
        val aMessagesRefresh = listOf(MessageWithParts(info = Message(id = "m_a1", role = "user")))

        coEvery { repository.getMessagesPaged("session-A", 5, any()) } returns
            Result.success(MessagesPage(aMessagesInitial, null))
        coEvery { repository.getMessagesPaged("session-B", 5, any()) } returns
            Result.success(MessagesPage(bMessages, null))

        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(sessions = listOf(sessionA, sessionB)) }

        // Open A — populates the cache with [m_a1, m_a2].
        viewModel.selectSession("session-A")
        advanceUntilIdle()
        assertEquals(
            "A initial load",
            listOf("m_a1", "m_a2"),
            viewModel.chatFlow.value.messages.map { it.id }
        )
        assertEquals(1, viewModel.sessionWindowCacheSize())
        assertNotNull(viewModel.peekSessionWindow("session-A"))

        // Switch to B — A's window is captured into the cache on switch-away.
        viewModel.selectSession("session-B")
        advanceUntilIdle()
        assertEquals(
            "B initial load",
            listOf("m_b1"),
            viewModel.chatFlow.value.messages.map { it.id }
        )
        assertEquals(2, viewModel.sessionWindowCacheSize())

        // Re-mock A: now the server "returns" only m_a1. Without the cache
        // this would silently lose m_a2.
        coEvery { repository.getMessagesPaged("session-A", 5, any()) } returns
            Result.success(MessagesPage(aMessagesRefresh, null))

        // Switch back to A — cached [m_a1, m_a2] should be restored and the
        // post-restore merge keeps m_a2 (id ∉ fetchedIds).
        viewModel.selectSession("session-A")
        advanceUntilIdle()

        val finalIds = viewModel.chatFlow.value.messages.map { it.id }
        assertTrue(
            "Cached m_a2 must survive the round-trip (got $finalIds)",
            finalIds.contains("m_a2")
        )
        assertTrue(
            "Fresh m_a1 must be present (got $finalIds)",
            finalIds.contains("m_a1")
        )
    }

    /**
     * Cache (b): a loadMore (older page) result survives a switch round-trip.
     *
     * Setup: open A, get latest 2 + cursor; loadMore to get older 2; switch
     * to B; switch back; verify the older 2 are still in state (the post-
     * restore tail fetch only re-fetches the latest window — without the
     * cache the older page would be gone).
     */
    @Test
    fun `loadMore result survives a switch round-trip via the cache`() = runTest {
        val sessionA = Session(id = "session-A", directory = "/tmp/a")
        val sessionB = Session(id = "session-B", directory = "/tmp/b")
        val aLatest = listOf(
            MessageWithParts(info = Message(id = "m_latest1", role = "user")),
            MessageWithParts(info = Message(id = "m_latest2", role = "assistant"))
        )
        val aOlder = listOf(
            MessageWithParts(info = Message(id = "m_older1", role = "user")),
            MessageWithParts(info = Message(id = "m_older2", role = "assistant"))
        )

        // mockk: when multiple stubs match a call, the LAST registered wins.
        // Register the generic fallback FIRST so the specific stubs below
        // override it (otherwise `any()` would shadow both specific matchers).
        coEvery { repository.getMessagesPaged("session-A", 5, any()) } returns
            Result.success(MessagesPage(aLatest, "cursor-1"))
        // Initial open uses before=null.
        coEvery { repository.getMessagesPaged("session-A", 5, null) } returns
            Result.success(MessagesPage(aLatest, "cursor-1"))
        // loadMore uses before="cursor-1" and returns the older page (no next).
        coEvery { repository.getMessagesPaged("session-A", 5, "cursor-1") } returns
            Result.success(MessagesPage(aOlder, null))
        coEvery { repository.getMessagesPaged("session-B", 5, any()) } returns
            Result.success(MessagesPage(emptyList(), null))

        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(sessions = listOf(sessionA, sessionB)) }

        viewModel.selectSession("session-A")
        advanceUntilIdle()
        assertEquals(listOf("m_latest1", "m_latest2"), viewModel.chatFlow.value.messages.map { it.id })
        assertEquals("cursor-1", viewModel.chatFlow.value.olderMessagesCursor)

        // Load the older page.
        viewModel.loadMoreMessages()
        advanceUntilIdle()
        val afterMore = viewModel.chatFlow.value.messages.map { it.id }
        assertEquals(listOf("m_older1", "m_older2", "m_latest1", "m_latest2"), afterMore)
        assertNull(viewModel.chatFlow.value.olderMessagesCursor)
        assertFalse(viewModel.chatFlow.value.hasMoreMessages)

        // Snapshot the cached window — must mirror the post-loadMore state.
        val cached = viewModel.peekSessionWindow("session-A")
        assertNotNull("session-A must be cached after loadMore", cached)
        assertEquals(4, cached!!.messages.size)
        assertTrue(cached.messages.map { it.id }.contains("m_older1"))
        assertTrue(cached.messages.map { it.id }.contains("m_older2"))

        // Switch away and back. The post-restore fetch returns the latest 2
        // again (matches the `any()` fallback), and the §preserveUnfetched
        // merge keeps the cached older 2.
        viewModel.selectSession("session-B")
        advanceUntilIdle()
        viewModel.selectSession("session-A")
        advanceUntilIdle()

        val finalIds = viewModel.chatFlow.value.messages.map { it.id }
        assertTrue(
            "Older messages must survive the round-trip via cache (got $finalIds)",
            finalIds.contains("m_older1") && finalIds.contains("m_older2")
        )
        assertTrue(
            "Latest messages must still be present (got $finalIds)",
            finalIds.contains("m_latest1") && finalIds.contains("m_latest2")
        )
    }

    /**
     * Cache (c): bounded LRU evicts at capacity (12). Loading a 13th session
     * evicts the least-recently-used (session-1, the first loaded). The most
     * recently loaded (session-13) stays.
     */
    @Test
    fun `session window cache evicts least-recently-used at capacity`() = runTest {
        val viewModel = createViewModel()
        // Pre-populate sessions so selectSession has something to find.
        updateState(viewModel) {
            it.copy(sessions = (1..13).map { i ->
                Session(id = "session-$i", directory = "/tmp/$i")
            })
        }
        // Each session returns one unique message so we can verify eviction.
        for (i in 1..13) {
            coEvery { repository.getMessagesPaged("session-$i", 5, any()) } returns
                Result.success(
                    MessagesPage(
                        listOf(MessageWithParts(info = Message(id = "m-$i", role = "user"))),
                        null
                    )
                )
        }

        // Open sessions 1..13 in order. Each loadMessages seeds the cache.
        for (i in 1..13) {
            updateState(viewModel) { it.copy(currentSessionId = "session-$i") }
            viewModel.loadMessages("session-$i")
            advanceUntilIdle()
        }

        assertEquals(
            "Cache must be capped at SESSION_WINDOW_CACHE_CAPACITY (12)",
            12,
            viewModel.sessionWindowCacheSize()
        )
        assertNull(
            "session-1 (LRU) must be evicted once session-13 lands",
            viewModel.peekSessionWindow("session-1")
        )
        assertNotNull(
            "session-13 (MRU) must be retained",
            viewModel.peekSessionWindow("session-13")
        )
        assertNotNull(
            "session-12 (second-newest) must be retained",
            viewModel.peekSessionWindow("session-12")
        )
    }

    /**
     * Cache (d): switching to an already-cached session promotes it to MRU,
     * so on a subsequent overflow eviction the just-visited session survives
     * over a session loaded more recently but never revisited.
     */
    @Test
    fun `restoring a cached session promotes it to most recently used`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(sessions = (1..13).map { i -> Session(id = "session-$i", directory = "/tmp/$i") })
        }
        for (i in 1..13) {
            coEvery { repository.getMessagesPaged("session-$i", 5, any()) } returns
                Result.success(MessagesPage(emptyList(), null))
        }

        // Load sessions 1..12 — fills the cache exactly to capacity.
        for (i in 1..12) {
            updateState(viewModel) { it.copy(currentSessionId = "session-$i") }
            viewModel.loadMessages("session-$i")
            advanceUntilIdle()
        }
        assertEquals(12, viewModel.sessionWindowCacheSize())
        assertNotNull(viewModel.peekSessionWindow("session-1"))

        // Re-visit session-1 to promote it to MRU.
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }
        viewModel.loadMessages("session-1")
        advanceUntilIdle()

        // Load session-13 — overflows; evicts the new LRU (session-2), NOT
        // session-1 (which is now MRU after the re-visit).
        updateState(viewModel) { it.copy(currentSessionId = "session-13") }
        viewModel.loadMessages("session-13")
        advanceUntilIdle()

        assertEquals(12, viewModel.sessionWindowCacheSize())
        assertNotNull(
            "session-1 (re-visited) must survive — re-visit promoted it to MRU",
            viewModel.peekSessionWindow("session-1")
        )
        assertNull(
            "session-2 (new LRU after session-1 promotion) must be evicted",
            viewModel.peekSessionWindow("session-2")
        )
    }

    /**
     * Cache (e): host switch clears the cache (windows belong to the old host
     * and would be invalid for the new host's sessions).
     */
    @Test
    fun `selectHostProfile clears the per-session message cache`() = runTest {
        val defaultProfile = HostProfile.defaultDirect("http://server.test")
        val otherProfile = HostProfile.defaultDirect("http://other.test").copy(id = "other")
        every { hostProfileStore.select("other") } returns otherProfile
        every { hostProfileStore.currentProfile() } returns otherProfile
        // selectHostProfile calls testConnection(force=true) which calls
        // checkHealth. Make it FAIL so the post-health loadInitialData path
        // (which would invoke further relaxed-mock returns and mis-cast) is
        // skipped. The cache is cleared BEFORE testConnection runs, so this
        // does not affect what we are asserting.
        coEvery { repository.checkHealth() } returns Result.failure(IllegalStateException("offline"))

        coEvery { repository.getMessagesPaged("session-A", 5, any()) } returns
            Result.success(
                MessagesPage(
                    listOf(MessageWithParts(info = Message(id = "m_a1", role = "user"))),
                    null
                )
            )

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-A",
                sessions = listOf(Session(id = "session-A", directory = "/tmp/a"))
            )
        }
        viewModel.loadMessages("session-A")
        advanceUntilIdle()
        assertEquals(1, viewModel.sessionWindowCacheSize())

        viewModel.selectHostProfile("other")
        advanceUntilIdle()

        assertEquals(
            "Host switch must clear the per-session message cache",
            0,
            viewModel.sessionWindowCacheSize()
        )
    }

    // --- §Manual message refresh ---

    @Test
    fun `refreshCurrentSession is a no-op when no session is selected`() = runTest {
        val viewModel = createViewModel()
        // currentSessionId stays null from createViewModel().
        viewModel.refreshCurrentSession()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    @Test
    fun `refreshCurrentSession is a no-op while a load is in flight`() = runTest {
        coEvery { repository.getMessagesPaged("session-A", 5, any()) } coAnswers {
            delay(100)
            Result.success(MessagesPage(emptyList(), null))
        }
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-A") }

        // Kick off a load; do NOT advance time so isLoadingMessages stays true.
        viewModel.loadMessages("session-A")
        // refreshCurrentSession must bail out (coalesce) — only one fetch total.
        viewModel.refreshCurrentSession()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getMessagesPaged("session-A", 5, any()) }
    }

    @Test
    fun `refreshCurrentSession is a global cold-start that resets the current view`() = runTest {
        // §Phase1D: manual refresh now means "distrust live state, start fresh"
        // — it clears the entire in-memory window cache, drops the current
        // session's loaded messages/cursor/gap, and reloads the authoritative
        // latest window. Older scrolled-up history is NOT preserved (the user
        // explicitly asked for a fresh snapshot); other sessions lazy-load on
        // next visit. Pre-seed an older message + cursor + open gap to verify
        // all are wiped.
        val older = Message(id = "m_older", role = "user")
        val fresh = MessageWithParts(info = Message(id = "m_fresh", role = "assistant"))
        coEvery { repository.getMessagesPaged("session-A", 5, any()) } returns
            Result.success(MessagesPage(listOf(fresh), null))

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-A",
                messages = listOf(older),
                olderMessagesCursor = "cursor-1",
                hasMoreMessages = true,
                gapInfo = com.yage.opencode_client.ui.GapInfo(
                    anchorNewestId = "m_older", tailOldestId = "m_older", tailOldestCursor = "c", open = true
                )
            )
        }

        viewModel.refreshCurrentSession()
        advanceUntilIdle()

        val ids = viewModel.chatFlow.value.messages.map { it.id }
        assertFalse("Older message is dropped on cold-start refresh (got $ids)", ids.contains("m_older"))
        assertTrue("Fresh latest window is loaded (got $ids)", ids.contains("m_fresh"))
        // Cursor reseeded from the fresh fetch (nextCursor=null → no more).
        assertNull(viewModel.chatFlow.value.olderMessagesCursor)
        // Gap wiped — a cold-start snapshot has no断层 reference.
        assertNull(viewModel.chatFlow.value.gapInfo)
    }

    @Test
    fun `session status busy for current session triggers reload`() = runTest {
        // §cross-client-sync (server 1.17.11+): when the CURRENT session goes
        // busy, a run just started — possibly because a message was sent from
        // ANOTHER client (e.g. the web UI). The busy-current reload is the
        // belt-and-suspenders that surfaces a cross-client-sent user message
        // even if the corresponding message.updated event missed the local
        // view. The overlay-clear in launchLoadMessages is gated on !busy, so
        // this reload does NOT disrupt any in-flight streaming overlay.
        coEvery { repository.getMessagesPaged("session-1", 5, any()) } returns
            Result.success(MessagesPage(emptyList(), null))
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.status",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put(
                            "status",
                            buildJsonObject {
                                put("type", JsonPrimitive("busy"))
                            }
                        )
                    }
                )
            )
        )
        advanceTimeBy(1000)
        advanceUntilIdle()

        // Status badge updated to busy...
        val status = viewModel.sessionListFlow.value.sessionStatuses["session-1"]
        assertNotNull(status)
        assertTrue(status!!.isBusy)
        // ...and a reload was issued for the current session.
        coVerify(atLeast = 1) { repository.getMessagesPaged("session-1", any(), any()) }

        // (b) A busy transition on a NON-current session only updates the
        // status badge — it must NOT trigger a reload (the user is not viewing
        // that session).
        val viewModel2 = createViewModel()
        updateState(viewModel2) { it.copy(currentSessionId = "session-1") }

        handleSse(
            viewModel2,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.status",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-other"))
                        put(
                            "status",
                            buildJsonObject {
                                put("type", JsonPrimitive("busy"))
                            }
                        )
                    }
                )
            )
        )
        advanceTimeBy(1000)
        advanceUntilIdle()

        val statusOther = viewModel2.sessionListFlow.value.sessionStatuses["session-other"]
        assertNotNull(statusOther)
        assertTrue(statusOther!!.isBusy)
        coVerify(exactly = 0) { repository.getMessagesPaged("session-other", any(), any()) }
    }

    @Test
    fun `message updated SSE inserts a new absent message and patches an existing one`() = runTest {
        // §cross-client-sync (server 1.17.11+): the server emits message.updated
        // (not message.created) for NEW messages. When the message id is ABSENT
        // from the local list, insert it (append — the list is oldest-first and
        // the new message is the newest). When present, patch in place (not
        // duplicated). Mirrors the oc-ref web client's patch-if-found +
        // insert-if-absent handler (server-session.ts:706).
        val existing = Message(id = "m_existing", role = "assistant", agent = "old")
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                messages = listOf(existing)
            )
        }

        // (a) ABSENT message → inserted at the tail (newest end).
        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.updated",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put(
                            "info",
                            buildJsonObject {
                                put("id", JsonPrimitive("msg_new"))
                                put("sessionID", JsonPrimitive("session-1"))
                                put("role", JsonPrimitive("user"))
                            }
                        )
                    }
                )
            )
        )
        advanceUntilIdle()

        val afterInsert = viewModel.chatFlow.value.messages
        assertEquals(2, afterInsert.size)
        assertEquals("m_existing", afterInsert[0].id)
        assertEquals("msg_new", afterInsert[1].id)

        // (b) EXISTING message → patched in place, NOT duplicated.
        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.updated",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put(
                            "info",
                            buildJsonObject {
                                put("id", JsonPrimitive("m_existing"))
                                put("sessionID", JsonPrimitive("session-1"))
                                put("role", JsonPrimitive("assistant"))
                                put("agent", JsonPrimitive("updated"))
                                put("finish", JsonPrimitive("stop"))
                            }
                        )
                    }
                )
            )
        )
        advanceUntilIdle()

        val afterPatch = viewModel.chatFlow.value.messages
        // Still 2 — the existing id was patched in place, not appended again.
        assertEquals(2, afterPatch.size)
        assertEquals("m_existing", afterPatch[0].id)
        assertEquals("updated", afterPatch[0].agent)
        assertEquals("stop", afterPatch[0].finish)
        assertEquals("msg_new", afterPatch[1].id)

        // Patch/insert are pure state updates — no reload issued for either case.
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    @Test
    fun `Stage1 free helper syncs slice - selectSession mirrors chatFlow`() = runTest {
        // §R-17 Stage 1 verification: free helpers (selectSessionState via
        // selectSession) now write AppState through updateAndSync(sliceFlows),
        // so the chatFlow slice must mirror the AppState chat fields. This is
        // the hard prerequisite for consumer migration (Stage 2) — without it
        // a consumer reading chatFlow would see stale values after selectSession.
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(sessions = listOf(Session(id = "s1", directory = "/tmp"))) }
        viewModel.selectSession("s1")
        advanceUntilIdle()
        assertEquals("s1", viewModel.chatFlow.value.currentSessionId)
        assertEquals(
            "chatFlow slice must mirror AppState after free-helper write",
            viewModel.chatFlow.value.currentSessionId,
            viewModel.chatFlow.value.currentSessionId
        )
    }

    @Test
    fun `Stage1 free helper syncs slice - loadSessions mirrors sessionListFlow`() = runTest {
        // §R-17 Stage 1: launchLoadSessions (free helper) writes AppState via
        // updateAndSync; sessionListFlow must mirror the sessions field so a
        // future consumer subscribing to sessionListFlow sees the loaded list.
        coEvery { repository.getSessions(any()) } returns Result.success(
            listOf(Session(id = "loaded-1", directory = "/tmp"))
        )
        val viewModel = createViewModel()
        viewModel.loadSessions()
        advanceUntilIdle()
        assertTrue(
            "sessionListFlow must contain the loaded session",
            viewModel.sessionListFlow.value.sessions.any { it.id == "loaded-1" }
        )
        assertEquals(
            "sessionListFlow.sessions must mirror AppState.sessions",
            viewModel.sessionListFlow.value.sessions.size,
            viewModel.sessionListFlow.value.sessions.size
        )
    }

    @org.junit.After
    fun tearDown() {
        unmockkAll()
    }
}
