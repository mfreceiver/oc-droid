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
import com.yage.opencode_client.data.repository.HostProfileStore
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.MainViewModel
import com.yage.opencode_client.ui.session.buildSessionTree
import com.yage.opencode_client.util.SettingsManager
import com.yage.opencode_client.util.ThemeMode
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
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        coEvery { repository.getMessages(any(), any()) } returns Result.success(emptyList())
        coEvery { repository.getPendingPermissions() } returns Result.success(emptyList())
    }

    private fun createViewModel(): MainViewModel {
        return MainViewModel(repository, settingsManager, hostProfileStore)
    }

    private fun updateState(viewModel: MainViewModel, transform: (AppState) -> AppState) {
        val field = MainViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(viewModel) as MutableStateFlow<AppState>
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
        assertEquals("", viewModel.state.value.inputText)
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
        assertFalse(viewModel.state.value.sendingSessionIds.contains("session-1"))
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
        assertEquals("Updated", viewModel.state.value.sessions.single().title)
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

        assertEquals("session-1", buildSessionTree(viewModel.state.value.sessions).first().session.id)
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

        assertEquals("hello", viewModel.state.value.inputText)
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
        assertEquals("", viewModel.state.value.inputText)
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
        assertEquals("   ", viewModel.state.value.inputText)
    }

    @Test
    fun `sendMessage ignores request when no session is selected`() = runTest {
        val viewModel = createViewModel()
        viewModel.setInputText("hello")

        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.sendMessage(any(), any(), any(), any()) }
        assertEquals("hello", viewModel.state.value.inputText)
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

        val sessions = viewModel.state.value.sessions
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
        assertEquals("/home/user/myproject", viewModel.state.value.draftWorkdir)
        assertNull(viewModel.state.value.currentSessionId)
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
        assertEquals("session-1", viewModel.state.value.currentSessionId)
        assertNull(viewModel.state.value.draftWorkdir)
        assertTrue(viewModel.state.value.openSessionIds.contains("session-1"))
    }

    @Test
    fun `selectSession discards in-progress draft`() = runTest {
        every { repository.setCurrentDirectory(any()) } just runs

        val viewModel = createViewModel()
        viewModel.createSessionInWorkdir("/home/user/myproject")
        advanceUntilIdle()
        assertEquals("/home/user/myproject", viewModel.state.value.draftWorkdir)

        viewModel.selectSession("session-1")
        advanceUntilIdle()

        assertNull(viewModel.state.value.draftWorkdir)
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

        assertNull(viewModel.state.value.currentSessionId)
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
        assertEquals("SSE Only", viewModel.state.value.sessions.single().title)
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
            viewModel.state.value.sessions.single { it.id == "session-1" }.title
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
        coVerify(exactly = 0) { repository.getMessages(any(), any()) }
        // Order unchanged: no refresh-driven reordering
        assertEquals("session-1", viewModel.state.value.sessions.first().id)
    }

    @Test
    fun `message updated SSE refreshes current messages only`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }

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
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getSessions(any()) }
        coVerify { repository.getMessages("session-1", 30) }
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
        assertEquals(10, viewModel.state.value.loadedSessionLimit)
        assertTrue(viewModel.state.value.hasMoreSessions)
        assertEquals(10, viewModel.state.value.sessions.size)
        assertFalse(viewModel.state.value.isRefreshingSessions)
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

        assertFalse(viewModel.state.value.isRefreshingSessions)
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

        assertEquals(1, viewModel.state.value.sessions.size)
        assertEquals("parent-1", viewModel.state.value.sessions.single().id)

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

        assertEquals(2, viewModel.state.value.sessions.size)
        assertEquals("child-1", viewModel.state.value.sessions.find { it.parentId == "parent-1" }?.id)
        assertFalse(viewModel.state.value.isRefreshingSessions)
    }

    @Test
    fun `loadSessions clears isRefreshingSessions on failure`() = runTest {
        coEvery { repository.getSessions(any()) } returns Result.failure(IllegalStateException("network error"))

        val viewModel = createViewModel()

        viewModel.loadSessions()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isRefreshingSessions)
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
        assertEquals(20, viewModel.state.value.loadedSessionLimit)
        assertFalse(viewModel.state.value.hasMoreSessions)
        assertEquals(15, viewModel.state.value.sessions.size)
        assertEquals("session-5", viewModel.state.value.currentSessionId)
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
        assertEquals(20, viewModel.state.value.loadedSessionLimit)
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
        assertTrue(viewModel.state.value.sessions.all { it.isArchived })
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
        assertFalse(viewModel.state.value.sessions.any { it.isArchived })
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
        coEvery { repository.getMessages("session-1", 30) } returns Result.success(messages)

        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }

        viewModel.loadMessages("session-1")
        advanceUntilIdle()

        assertEquals(messages, viewModel.state.value.messages)
        assertEquals("plan", viewModel.state.value.selectedAgentName)
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

        assertEquals("thinking", viewModel.state.value.streamingPartTexts["message-1:part-1"])
        assertEquals("part-1", viewModel.state.value.streamingReasoningPart?.id)
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

        assertEquals(listOf("session-2", "session-1"), viewModel.state.value.sessions.map { it.id })
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

        val sessions = viewModel.state.value.sessions
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

        val sessions = viewModel.state.value.sessions
        assertEquals(2, sessions.size)
        assertEquals("session-new", sessions[0].id)
        assertEquals("New Feature", sessions[0].title)
        assertEquals("session-1", sessions[1].id)
    }

    @Test
    fun `handleSSEEvent missing delta clears streaming state and refreshes messages`() = runTest {
        val messages = listOf(MessageWithParts(info = Message(id = "a2", role = "assistant")))
        coEvery { repository.getMessages("session-1", 30) } returns Result.success(messages)
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                streamingPartTexts = mapOf("message-1:part-1" to "partial"),
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

        assertTrue(viewModel.state.value.streamingPartTexts.isEmpty())
        assertNull(viewModel.state.value.streamingReasoningPart)
        assertEquals(messages, viewModel.state.value.messages)
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

        assertTrue(viewModel.state.value.streamingPartTexts.isEmpty())
        assertNull(viewModel.state.value.streamingReasoningPart)
    }

    @Test
    fun `handleSSEEvent idle status clears streaming state and refreshes messages`() = runTest {
        val messages = listOf(MessageWithParts(info = Message(id = "a1", role = "assistant")))
        coEvery { repository.getMessages("session-1", 30) } returns Result.success(messages)
        coEvery { repository.getSessions(100) } returns Result.success(
            listOf(com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/project"))
        )
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                streamingPartTexts = mapOf("message-1:part-1" to "partial"),
                streamingReasoningPart = Part(id = "part-1", messageId = "message-1", sessionId = "session-1", type = "reasoning")
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

        assertTrue(viewModel.state.value.streamingPartTexts.isEmpty())
        assertNull(viewModel.state.value.streamingReasoningPart)
        assertEquals(messages, viewModel.state.value.messages)
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

        assertEquals(permissions, viewModel.state.value.pendingPermissions)
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
        assertEquals("draft2", viewModel.state.value.inputText)
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
        assertEquals(listOf("session-2"), viewModel.state.value.sessions.map { it.id })
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
        assertEquals(listOf("perm-2"), viewModel.state.value.pendingPermissions.map { it.id })
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
        assertEquals(permissions, viewModel.state.value.pendingPermissions)
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
        assertEquals(listOf("question-2"), viewModel.state.value.pendingQuestions.map { it.id })
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
        assertEquals(listOf("question-2"), viewModel.state.value.pendingQuestions.map { it.id })
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

    @Test
    fun `handleSSEEvent message created refreshes messages for current session`() = runTest {
        val messages = listOf(MessageWithParts(info = Message(id = "m1", role = "assistant")))
        coEvery { repository.getMessages("session-1", 30) } returns Result.success(messages)
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

        assertEquals(messages, viewModel.state.value.messages)
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

        assertEquals(listOf("question-1"), viewModel.state.value.pendingQuestions.map { it.id })
        assertEquals("session-1", viewModel.state.value.pendingQuestions.single().sessionId)
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

        assertEquals(listOf("question-2"), viewModel.state.value.pendingQuestions.map { it.id })
    }

    @Test
    fun `activateTunnelForCurrentHost no-ops when profile has no tunnelPasswordId`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val initialState = viewModel.state.value
        viewModel.activateTunnelForCurrentHost()
        advanceUntilIdle()

        assertEquals(initialState, viewModel.state.value)
        coVerify(exactly = 0) { repository.activateTunnel(any(), any()) }
    }

    @Test
    fun `activateTunnelForCurrentHost no-ops when tunnel password is empty`() = runTest {
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
            viewModel.state.value.tunnelActivationState
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

        val activationState = viewModel.state.value.tunnelActivationState
        assertTrue(activationState is com.yage.opencode_client.ui.TunnelActivationState.Error)
        assertTrue((activationState as com.yage.opencode_client.ui.TunnelActivationState.Error).message.contains("403"))
    }

    @org.junit.After
    fun tearDown() {
        unmockkAll()
    }
}
