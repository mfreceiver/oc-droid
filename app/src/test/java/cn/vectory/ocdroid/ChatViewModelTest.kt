package cn.vectory.ocdroid

import android.util.Log
import cn.vectory.ocdroid.data.model.BasicAuthConfig
import cn.vectory.ocdroid.data.model.HealthResponse
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.PermissionResponse
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.ProvidersResponse
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.data.repository.MessagesPage
import cn.vectory.ocdroid.ui.AppCore
import cn.vectory.ocdroid.ui.ChatViewModel
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.ConnectionViewModel
import cn.vectory.ocdroid.ui.HostViewModel
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.SessionViewModel
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.TunnelActivationState
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.currentSession
import cn.vectory.ocdroid.ui.session.buildSessionTree
import cn.vectory.ocdroid.ui.visibleMessages
import cn.vectory.ocdroid.util.ThemeMode
import app.cash.turbine.test as turbineTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §R-17 batch3e: domain tests split out of the former [MainViewModelTest].
 *
 * Each test constructs an [AppCore] (via [MainViewModelTestBase.createCore])
 * and wraps it in ALL 6 domain VMs. Since the VMs share the same AppCore
 * singleton, slice reads return the same flows regardless of which VM is
 * asked. Tests call VM-owned methods via the matching VM variable
 * (`chatVM.sendMessage()`, `sessionVM.selectSession(...)`, etc.) — no legacy
 * `AppCore` shim extensions remain.
 *
 * §R18 Phase 4 (P2-3): state setup writes slices directly through the
 * AppCore `writeXxx { it.copy(...) }` helpers (former `updateState {}`
 * AppState shim removed). UiEvent Error/Success assertions read the
 * test-only [AppCore.recentTestErrors] / [AppCore.recentTestSuccesses]
 * ring buffers populated by [MainViewModelTestBase.createCore]. AppCore
 * internals that have no VM equivalent (`handleSSEEvent`, `store`,
 * `peekSessionWindow`) are reached through `core` directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest : MainViewModelTestBase() {

    @Test
    fun `sendMessage success clears input`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSessions(100) } returns Result.success(
            listOf(cn.vectory.ocdroid.data.model.Session(id = "session-1", directory = "/tmp/project"))
        )

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        sessionVM.selectSession("session-1")
        advanceUntilIdle()
        composerVM.setInputText("  hello world  ")
        composerVM.selectAgent("review")

        chatVM.sendMessage()
        advanceUntilIdle()

        coVerify {
            repository.sendMessage(
                "session-1",
                "hello world",
                "review",
                null
            )
        }
        assertEquals("", composerVM.composerFlow.value.inputText)
        assertNull(core.recentTestErrors.lastOrNull())
    }

    @Test
    fun `sendMessage attaches per-session stored model to prompt`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSessions(100) } returns Result.success(
            listOf(Session(id = "session-1", directory = "/tmp/project"))
        )
        // Per-session stored model. Registered AFTER setUp's global
        // `getModelForSession(any()) returns null`; MockK resolves the last
        // matching stub, so the precise-sessionId stub wins (same override
        // pattern as getMessagesPaged("session-1", ...) at line ~1444).
        every { settingsManager.getModelForSession(any(), "session-1") } returns
            Message.ModelInfo("openai", "gpt-5")

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        sessionVM.selectSession("session-1")
        advanceUntilIdle()
        composerVM.setInputText("hi")

        chatVM.sendMessage()
        advanceUntilIdle()

        coVerify {
            repository.sendMessage(
                "session-1",
                "hi",
                any(),
                Message.ModelInfo("openai", "gpt-5"),
                any()
            )
        }
    }

    @Test
    fun `loadMessages stored per-session model wins over inference`() = runTest {
        val messages = listOf(
            MessageWithParts(info = Message(id = "u1", role = "user")),
            MessageWithParts(
                info = Message(
                    id = "a1",
                    role = "assistant",
                    // Inferred candidate (the fallback path): inferCurrentModel
                    // would return this if no stored value were present.
                    model = Message.ModelInfo("openai", "gpt-5")
                )
            )
        )
        coEvery { repository.getMessagesPaged("session-1", any(), any()) } returns
            Result.success(MessagesPage(messages, null))
        // Stored per-session model — MUST win over the assistant-message inference.
        every { settingsManager.getModelForSession(any(), "session-1") } returns
            Message.ModelInfo("anthropic", "claude")

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "session-1") }

        chatVM.loadMessages("session-1")
        advanceUntilIdle()

        assertEquals(
            Message.ModelInfo("anthropic", "claude"),
            chatVM.chatFlow.value.currentModel
        )
    }

    @Test
    fun `sendMessage ignores duplicate sends while request is in flight`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } coAnswers {
            delay(100)
            Result.success(Unit)
        }

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        sessionVM.selectSession("session-1")
        advanceUntilIdle()
        composerVM.setInputText("hello")

        chatVM.sendMessage()
        chatVM.sendMessage()

        advanceUntilIdle()

        coVerify(exactly = 1) { repository.sendMessage(any(), any(), any(), any(), any()) }
        assertFalse(composerVM.composerFlow.value.sendingSessionIds.contains("session-1"))
    }

    @Test
    fun `sendMessage success refreshes sessions`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSessions(10) } returns Result.success(
            listOf(cn.vectory.ocdroid.data.model.Session(id = "session-1", directory = "/tmp/project", title = "Updated"))
        )

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        sessionVM.selectSession("session-1")
        advanceUntilIdle()
        composerVM.setInputText("hello")

        chatVM.sendMessage()
        advanceUntilIdle()

        coVerify(atLeast = 1) { repository.getSessions(10) }
        assertEquals("Updated", sessionVM.sessionListFlow.value.sessions.single().title)
    }

    @Test
    fun `sendMessage bumps current session above stale refreshed ordering`() = runTest {
        val current = cn.vectory.ocdroid.data.model.Session(
            id = "session-1",
            directory = "/tmp/project",
            title = "Current",
            time = cn.vectory.ocdroid.data.model.Session.TimeInfo(updated = 1_000)
        )
        val previousTop = cn.vectory.ocdroid.data.model.Session(
            id = "session-2",
            directory = "/tmp/project",
            title = "Previous Top",
            time = cn.vectory.ocdroid.data.model.Session.TimeInfo(updated = 2_000)
        )
        coEvery { repository.sendMessage(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSessions(10) } returns Result.success(listOf(previousTop, current))

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "session-1") }
        core.writeSessionList { it.copy(sessions = listOf(previousTop, current)) }
        core.writeComposer { it.copy(inputText = "hello") }

        chatVM.sendMessage()
        advanceUntilIdle()

        assertEquals("session-1", buildSessionTree(sessionVM.sessionListFlow.value.sessions).first().session.id)
    }

    @Test
    fun `sendMessage failure keeps input and exposes error`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any()) } returns Result.failure(IllegalStateException("send failed"))

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        sessionVM.selectSession("session-1")
        advanceUntilIdle()
        composerVM.setInputText("hello")

        chatVM.sendMessage()
        advanceUntilIdle()

        assertEquals("hello", composerVM.composerFlow.value.inputText)
        // §R18 Phase 2-G: the message format is now "Failed to send message: <err>";
        // assert on the dynamic portion (preserves the original semantic — the
        // exception message is surfaced to the user via the UiEvent).
        assertTrue(core.recentTestErrors.lastOrNull()!!.contains("send failed"))
    }

    @Test
    fun `sendMessage still queues prompt when current session is busy`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any()) } returns Result.success(Unit)

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        sessionVM.selectSession("session-1")
        advanceUntilIdle()
        core.writeComposer { it.copy(inputText = "queue this next") }
        core.writeSessionList {
            it.copy(sessionStatuses = it.sessionStatuses + ("session-1" to SessionStatus(type = "busy")))
        }

        chatVM.sendMessage()
        advanceUntilIdle()

        coVerify {
            repository.sendMessage(
                "session-1",
                "queue this next",
                any(),
                any()
            )
        }
        assertEquals("", composerVM.composerFlow.value.inputText)
    }

    @Test
    fun `sendMessage ignores blank input`() = runTest {
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        sessionVM.selectSession("session-1")
        advanceUntilIdle()
        composerVM.setInputText("   ")

        chatVM.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.sendMessage(any(), any(), any(), any()) }
        assertEquals("   ", composerVM.composerFlow.value.inputText)
    }

    @Test
    fun `sendMessage ignores request when no session is selected`() = runTest {
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        composerVM.setInputText("hello")

        chatVM.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.sendMessage(any(), any(), any(), any()) }
        assertEquals("hello", composerVM.composerFlow.value.inputText)
    }

    @Test
    fun `sendMessage materialises draft session via POST then dispatches the prompt`() = runTest {
        val created = Session(
            id = "session-1",
            directory = "/home/user/myproject",
            title = null
        )
        coEvery { repository.createSession(title = null, directory = any()) } returns Result.success(created)
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(created))
        // scheduleTitleRefreshAfterFirstMessage fires 5s after the new session's
        // first message (title fallback); mock the GET so it doesn't blow up.
        coEvery { repository.getSession(any()) } returns Result.success(created)

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        sessionVM.createSessionInWorkdir("/home/user/myproject")
        advanceUntilIdle()

        composerVM.setInputText("hello")
        chatVM.sendMessage()
        advanceUntilIdle()

        coVerifyOrder {
            // §R18 Phase 2-E step 2: createSessionInWorkdir no longer calls
            // repository.setCurrentDirectory (removed); the workdir is carried
            // by composer.draftWorkdir → sendMessage → materializeDraftSession.
            repository.createSession(title = null, directory = any())
            repository.sendMessage("session-1", "hello", any(), any(), any())
        }
        assertEquals("session-1", chatVM.chatFlow.value.currentSessionId)
        assertNull(composerVM.composerFlow.value.draftWorkdir)
        assertTrue(sessionVM.sessionListFlow.value.openSessionIds.contains("session-1"))
    }

    @Test
    fun `message created SSE does not refresh sessions for non-current session`() = runTest {
        val session1 = cn.vectory.ocdroid.data.model.Session(
            id = "session-1",
            directory = "/tmp/project",
            title = "Current",
            time = cn.vectory.ocdroid.data.model.Session.TimeInfo(updated = 1_000)
        )
        val session2 = cn.vectory.ocdroid.data.model.Session(
            id = "session-2",
            directory = "/tmp/project",
            title = "New Activity",
            time = cn.vectory.ocdroid.data.model.Session.TimeInfo(updated = 2_000)
        )

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "session-1") }
        core.writeSessionList { it.copy(sessions = listOf(session1, session2)) }

        handleSse(core,
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
        assertEquals("session-1", sessionVM.sessionListFlow.value.sessions.first().id)
    }

    @Test
    fun `message updated SSE does NOT trigger message reload`() = runTest {
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "session-1") }

        advanceUntilIdle()

        handleSse(core,
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
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        core.writeChat {
            it.copy(
                currentSessionId = "session-1",
                messages = listOf(original),
                partsByMessage = mapOf("m1" to listOf(part))
            )
        }

        handleSse(core,
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

        val messages = chatVM.chatFlow.value.messages
        assertEquals(1, messages.size)
        // message metadata patched...
        assertEquals("m1", messages[0].id)
        assertEquals("code", messages[0].agent)
        assertEquals("stop", messages[0].finish)
        // ...parts preserved in partsByMessage...
        assertEquals(listOf(part), chatVM.chatFlow.value.partsByMessage["m1"])
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
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        core.writeChat {
            it.copy(
                currentSessionId = "session-1",
                messages = listOf(Message(id = "other", role = "assistant"))
            )
        }

        handleSse(core,
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

        assertEquals(1, chatVM.chatFlow.value.messages.size)
        assertEquals("other", chatVM.chatFlow.value.messages[0].id)
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    @Test
    fun `togglePartExpand flips the value for a key`() = runTest {
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test

        chatVM.togglePartExpand("msg-1|part-1", false)
        assertEquals(true, chatVM.expandedParts.value["msg-1|part-1"])

        chatVM.togglePartExpand("msg-1|part-1", true)
        assertEquals(false, chatVM.expandedParts.value["msg-1|part-1"])
    }

    @Test
    fun `selectSession clears expandedParts`() = runTest {
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "session-1") }
        core.writeSessionList {
            it.copy(sessions = listOf(cn.vectory.ocdroid.data.model.Session(id = "session-1", directory = "/tmp")))
        }
        core.writeComposer { it.copy(inputText = "draft1") }
        chatVM.togglePartExpand("msg-1|part-1", false)
        chatVM.togglePartExpand("msg-2|part-2", false)
        assertEquals(2, chatVM.expandedParts.value.size)

        sessionVM.selectSession("session-1")
        advanceUntilIdle()

        assertTrue(
            "expandedParts must be cleared on session switch",
            chatVM.expandedParts.value.isEmpty()
        )
    }

    @Test
    fun `loadMessages syncs selected agent from per-session override`() = runTest {
        // §bug3-defensive: the global selectedAgentName must be synced from the
        // per-session agent override (when one exists), NOT from history-inference.
        val messages = listOf(
            MessageWithParts(info = Message(id = "u1", role = "user")),
            MessageWithParts(
                info = Message(
                    id = "a1",
                    role = "assistant",
                    agent = "build"
                )
            )
        )
        coEvery { repository.getMessagesPaged("session-1", any(), any()) } returns Result.success(MessagesPage(messages, null))

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "session-1") }
        // Explicit per-session override — must win over the history-inferred "build".
        every { settingsManager.getAgentForSession(any(), "session-1") } returns "plan"

        chatVM.loadMessages("session-1")
        advanceUntilIdle()

        assertEquals(messages.map { it.info }, chatVM.chatFlow.value.messages)
        assertEquals("plan", orchestratorVM.settingsFlow.value.selectedAgentName)
    }

    @Test
    fun `loadMessages preserves global selected agent when no per-session override`() = runTest {
        // §bug3-defensive: when a session has NO explicit per-session agent override,
        // loadMessages must NOT clobber the user's global selectedAgentName with a
        // value inferred from the last assistant message in history.
        val messages = listOf(
            MessageWithParts(info = Message(id = "u1", role = "user")),
            MessageWithParts(
                info = Message(
                    id = "a1",
                    role = "assistant",
                    agent = "build"
                )
            )
        )
        coEvery { repository.getMessagesPaged("session-2", any(), any()) } returns Result.success(MessagesPage(messages, null))
        // No per-session override (default mock returns null).

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        core.writeChat { it.copy(currentSessionId = "session-2") }
        core.writeSettings { it.copy(selectedAgentName = "plan") }

        chatVM.loadMessages("session-2")
        advanceUntilIdle()

        // Global choice preserved — NOT overwritten with the inferred "build".
        assertEquals("plan", orchestratorVM.settingsFlow.value.selectedAgentName)
    }

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

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "session-1", messages = mixed) }

        // chatState.messages is what the UI renders and is sourced from
        // AppState.visibleMessages (the filtered view), NOT the raw messages field.
        val visible = visibleMessages(chatVM.chatFlow.value.messages, currentSession(sessionVM.sessionListFlow.value.sessions, chatVM.chatFlow.value.currentSessionId))
        assertEquals(listOf("m1", "m3"), visible.map { it.id })
        assertTrue(visible.all { it.isUser || it.isAssistant })
        // The raw store is unchanged — system/tool messages still live in state.
        assertEquals(6, chatVM.chatFlow.value.messages.size)
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

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "session-1", messages = mixed) }
        core.writeSessionList { it.copy(sessions = listOf(session)) }

        val visible = visibleMessages(chatVM.chatFlow.value.messages, currentSession(sessionVM.sessionListFlow.value.sessions, chatVM.chatFlow.value.currentSessionId))
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

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        core.writeChat {
            // No revert on the session → revert filter is a no-op.
            it.copy(
                currentSessionId = "session-1",
                messages = mixed
            )
        }
        core.writeSessionList {
            it.copy(sessions = listOf(Session(id = "session-1", directory = "/tmp")))
        }

        val visible = visibleMessages(chatVM.chatFlow.value.messages, currentSession(sessionVM.sessionListFlow.value.sessions, chatVM.chatFlow.value.currentSessionId))
        assertEquals(listOf("m2"), visible.map { it.id })
    }

    @Test
    fun `resetLimit=false reload preserves streamingReasoningPart during active stream`() = runTest {
        // 闪屏修复 v2（fixer）：mid-stream reload 不再清除 streamingReasoningPart
        // （旧 reasoningPromotedToHistory 分支已移除）；双重渲染由 MessageRow 的
        // streamingReasoningPartId 过滤解决，而非清除独立 streaming-reasoning item。
        // 因此 reset=false reload 后 streamingReasoningPart 保持不变。
        val reasoningPart = Part(id = "part-1", messageId = "a2", sessionId = "session-1", type = "reasoning", text = "old-persisted")
        val messages = listOf(MessageWithParts(info = Message(id = "a2", role = "assistant"), parts = listOf(reasoningPart)))
        coEvery { repository.getMessagesPaged("session-1", any(), any()) } returns Result.success(MessagesPage(messages, null))
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        val seededTexts = mapOf("part-1" to "streaming-latest")
        val seededReasoning = Part(id = "part-1", messageId = "a2", sessionId = "session-1", type = "reasoning")
        core.writeChat {
            it.copy(
                currentSessionId = "session-1",
                streamingPartTexts = seededTexts,
                streamingReasoningPart = seededReasoning
            )
        }

        chatVM.loadMessages("session-1", resetLimit = false)
        advanceTimeBy(1000)
        advanceUntilIdle()

        assertEquals(seededReasoning, chatVM.chatFlow.value.streamingReasoningPart)
        assertEquals(seededTexts, chatVM.chatFlow.value.streamingPartTexts)
    }

    @Test
    fun `resetLimit=true busy reload preserves streamingReasoningPart during active stream`() = runTest {
        // 闪屏修复 v2（fixer）：reset=true + busy 时 overlay 保留（streamingFinalized=false），
        // streamingReasoningPart 也不再清除 —— 双重渲染由 UI 层 streamingReasoningPartId
        // 过滤解决。
        val reasoningPart = Part(id = "part-1", messageId = "a2", sessionId = "session-1", type = "reasoning", text = "old")
        val messages = listOf(MessageWithParts(info = Message(id = "a2", role = "assistant"), parts = listOf(reasoningPart)))
        coEvery { repository.getMessagesPaged("session-1", any(), any()) } returns Result.success(MessagesPage(messages, null))
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        val seededTexts = mapOf("part-1" to "streaming-latest")
        val seededReasoning = Part(id = "part-1", messageId = "a2", sessionId = "session-1", type = "reasoning")
        core.writeChat {
            it.copy(
                currentSessionId = "session-1",
                streamingPartTexts = seededTexts,
                streamingReasoningPart = seededReasoning
            )
        }
        core.writeSessionList {
            it.copy(sessionStatuses = mapOf("session-1" to SessionStatus(type = "busy")))
        }

        chatVM.loadMessages("session-1", resetLimit = true)
        advanceTimeBy(1000)
        advanceUntilIdle()

        // busy → overlay preserved → streamingReasoningPart stays non-null (no mid-stream removal).
        assertEquals(seededReasoning, chatVM.chatFlow.value.streamingReasoningPart)
        assertEquals(seededTexts, chatVM.chatFlow.value.streamingPartTexts)
    }

    @Test
    fun `sendMessage on success clears draft for current session`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any()) } returns Result.success(Unit)

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        sessionVM.selectSession("s1")
        advanceUntilIdle()
        composerVM.setInputText("hello")

        chatVM.sendMessage()
        advanceUntilIdle()

        verify { settingsManager.setDraftText(any(), "s1", "") }
    }

    @Test
    fun `abortSession calls repository for current session`() = runTest {
        coEvery { repository.abortSession("session-1") } returns Result.success(Unit)

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "session-1") }

        chatVM.abortSession()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.abortSession("session-1") }
    }

    @Test
    fun `authoritative reload clears stale streaming overlay so finalized parts are not masked`() = runTest {
        // Regression (gpter S0-S4 BLOCKER): a resetLimit=true reload (e.g.
        // message.created) fetches the authoritative latest window. Any stale
        // streaming overlay for those messages must be cleared atomically,
        // else the partial overlay would mask the finalized part.text in the UI.
        val finalizedPart = Part(id = "p1", messageId = "m1", sessionId = "session-1", type = "text", text = "Hello world!")
        val messages = listOf(MessageWithParts(info = Message(id = "m1", role = "assistant"), parts = listOf(finalizedPart)))
        coEvery { repository.getMessagesPaged("session-1", any(), any()) } returns Result.success(MessagesPage(messages, null))

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        core.writeChat {
            it.copy(
                currentSessionId = "session-1",
                // Stale partial overlay from a just-finished stream:
                streamingPartTexts = mapOf("p1" to "Hello wor")
            )
        }

        // message.created → authoritative reload (resetLimit=true).
        handleSse(core,
            SSEEvent(payload = SSEPayload(type = "message.created", properties = buildJsonObject {
                put("sessionID", JsonPrimitive("session-1"))
            }))
        )
        advanceTimeBy(400)
        advanceUntilIdle()

        // Overlay cleared (finalization boundary)...
        assertTrue("streaming overlay must be cleared on authoritative reload",
            chatVM.chatFlow.value.streamingPartTexts.isEmpty())
        assertNull(chatVM.chatFlow.value.streamingReasoningPart)
        // ...and the finalized authoritative part is present.
        assertEquals(listOf(finalizedPart), chatVM.chatFlow.value.partsByMessage["m1"])
    }

    @Test
    fun `togglePartExpand collapses a default-expanded card on first tap`() = runTest {
        // Regression: a running tool card displays expanded by default
        // (expanded = expandedParts[key] ?: isRunning = true). The first tap
        // must collapse it, which requires the toggle to invert the displayed
        // value supplied by the caller, not the raw map value (which is absent).
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test

        chatVM.togglePartExpand("msg-1|tool-1", currentValue = true)

        assertEquals(false, chatVM.expandedParts.value["msg-1|tool-1"])
    }

    @Test
    fun `sendMessage draft mode creates session only once on rapid double tap`() = runTest {
        val created = cn.vectory.ocdroid.data.model.Session(id = "s1", directory = "/home/user/proj")
        coEvery { repository.createSession(title = null, directory = any()) } returns Result.success(created)
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(created))
        coEvery { repository.getSession(any()) } returns Result.success(created)

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        sessionVM.createSessionInWorkdir("/home/user/proj")
        advanceUntilIdle()
        composerVM.setInputText("hello")

        // Rapid double-tap with no advanceUntilIdle between the two calls:
        // the second invoke must observe draftWorkdir=null (cleared sync by
        // the first) and early-return instead of issuing a second POST /session.
        chatVM.sendMessage()
        chatVM.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.createSession(title = null, directory = any()) }
    }

    @Test
    fun `sendMessage draft mode restores draftWorkdir when createSession fails so user can retry`() = runTest {
        coEvery { repository.createSession(title = null, directory = any()) } returns Result.failure(IllegalStateException("network error"))

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        sessionVM.createSessionInWorkdir("/home/user/retry-proj")
        advanceUntilIdle()
        composerVM.setInputText("hello")

        chatVM.sendMessage()
        advanceUntilIdle()

        // draft mode restored — composer stays in draft, user can retry
        assertEquals("/home/user/retry-proj", composerVM.composerFlow.value.draftWorkdir)
        assertNull(chatVM.chatFlow.value.currentSessionId)
        // input text preserved for retry
        assertEquals("hello", composerVM.composerFlow.value.inputText)
        assertNotNull(core.recentTestErrors.lastOrNull())
        assertTrue(core.recentTestErrors.lastOrNull()!!.contains("network error"))
    }

    @Test
    fun `session switch A to B to A restores cached messages without re-wiping`() = runTest {
        val sessionA = Session(id = "session-A", directory = "/tmp/a", time = Session.TimeInfo(created = 1_700_000L))
        val sessionB = Session(id = "session-B", directory = "/tmp/b", time = Session.TimeInfo(created = 1_700_100L))
        val aMessagesInitial = listOf(
            MessageWithParts(info = Message(id = "m_a1", role = "user")),
            MessageWithParts(info = Message(id = "m_a2", role = "assistant"))
        )
        val bMessages = listOf(MessageWithParts(info = Message(id = "m_b1", role = "user")))
        // After A is cached, re-mock A to return only m_a1 — without the
        // cache this would silently lose m_a2.
        val aMessagesRefresh = listOf(MessageWithParts(info = Message(id = "m_a1", role = "user")))

        coEvery { repository.getMessagesPaged("session-A", any(), any()) } returns
            Result.success(MessagesPage(aMessagesInitial, null))
        coEvery { repository.getMessagesPaged("session-B", any(), any()) } returns
            Result.success(MessagesPage(bMessages, null))

        val core = createCore()
        // R-20 Phase 1: switchTo now emits VerifyAndHydrate instead of
        // synchronously seeding from the memory LRU. The handler queries the
        // persistent cache (cacheRepository.verifyAndLoad). The default
        // relaxed mock returns UnknownColdStart → cold-start REST (which would
        // re-fetch only m_a1 and lose m_a2). Install an in-memory persistent
        // cache so putSessionWindow writes + verifyAndLoad reads round-trip
        // the cached window — this is the Phase 1 equivalent of the old LRU
        // memory cache that the test was originally asserting on.
        installInMemoryPersistentCache(core)
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        core.writeSessionList { it.copy(sessions = listOf(sessionA, sessionB)) }

        // Open A — populates the cache with [m_a1, m_a2].
        sessionVM.selectSession("session-A")
        advanceUntilIdle()
        assertEquals(
            "A initial load",
            listOf("m_a1", "m_a2"),
            chatVM.chatFlow.value.messages.map { it.id }
        )

        // Switch to B — A's window is captured into the cache on switch-away.
        sessionVM.selectSession("session-B")
        advanceUntilIdle()
        assertEquals(
            "B initial load",
            listOf("m_b1"),
            chatVM.chatFlow.value.messages.map { it.id }
        )

        // Re-mock A: now the server "returns" only m_a1. Without the cache
        // this would silently lose m_a2.
        coEvery { repository.getMessagesPaged("session-A", any(), any()) } returns
            Result.success(MessagesPage(aMessagesRefresh, null))

        // Switch back to A — cached [m_a1, m_a2] should be restored and the
        // post-restore merge keeps m_a2 (id ∉ fetchedIds).
        sessionVM.selectSession("session-A")
        advanceUntilIdle()

        val finalIds = chatVM.chatFlow.value.messages.map { it.id }
        assertTrue(
            "Cached m_a2 must survive the round-trip (got $finalIds)",
            finalIds.contains("m_a2")
        )
        assertTrue(
            "Fresh m_a1 must be present (got $finalIds)",
            finalIds.contains("m_a1")
        )
    }

    @Test
    fun `loadMore result survives a switch round-trip via the cache`() = runTest {
        val sessionA = Session(id = "session-A", directory = "/tmp/a", time = Session.TimeInfo(created = 1_700_000L))
        val sessionB = Session(id = "session-B", directory = "/tmp/b", time = Session.TimeInfo(created = 1_700_100L))
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
        coEvery { repository.getMessagesPaged("session-A", any(), any()) } returns
            Result.success(MessagesPage(aLatest, "cursor-1"))
        // Initial open uses before=null.
        coEvery { repository.getMessagesPaged("session-A", any(), null) } returns
            Result.success(MessagesPage(aLatest, "cursor-1"))
        // loadMore uses before="cursor-1" and returns the older page (no next).
        coEvery { repository.getMessagesPaged("session-A", any(), "cursor-1") } returns
            Result.success(MessagesPage(aOlder, null))
        coEvery { repository.getMessagesPaged("session-B", any(), any()) } returns
            Result.success(MessagesPage(emptyList(), null))

        val core = createCore()
        // R-20 Phase 1: install an in-memory persistent cache so the
        // VerifyAndHydrate handler round-trips cached windows (see the
        // session-switch A→B→A test above for the rationale).
        installInMemoryPersistentCache(core)
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        core.writeSessionList { it.copy(sessions = listOf(sessionA, sessionB)) }

        sessionVM.selectSession("session-A")
        advanceUntilIdle()
        assertEquals(listOf("m_latest1", "m_latest2"), chatVM.chatFlow.value.messages.map { it.id })
        assertEquals("cursor-1", chatVM.chatFlow.value.olderMessagesCursor)

        // Load the older page.
        chatVM.loadMoreMessages()
        advanceUntilIdle()
        val afterMore = chatVM.chatFlow.value.messages.map { it.id }
        assertEquals(listOf("m_older1", "m_older2", "m_latest1", "m_latest2"), afterMore)
        assertNull(chatVM.chatFlow.value.olderMessagesCursor)
        assertFalse(chatVM.chatFlow.value.hasMoreMessages)

        // Switch away and back. The post-restore fetch returns the latest 2
        // again (matches the `any()` fallback), and the §preserveUnfetched
        // merge keeps the cached older 2.
        sessionVM.selectSession("session-B")
        advanceUntilIdle()
        sessionVM.selectSession("session-A")
        advanceUntilIdle()

        val finalIds = chatVM.chatFlow.value.messages.map { it.id }
        assertTrue(
            "Older messages must survive the round-trip via cache (got $finalIds)",
            finalIds.contains("m_older1") && finalIds.contains("m_older2")
        )
        assertTrue(
            "Latest messages must still be present (got $finalIds)",
            finalIds.contains("m_latest1") && finalIds.contains("m_latest2")
        )
    }

    @Test
    fun `session window cache evicts least-recently-used at capacity`() = runTest {
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        // Pre-populate sessions so selectSession has something to find.
        core.writeSessionList {
            it.copy(sessions = (1..13).map { i ->
                Session(id = "session-$i", directory = "/tmp/$i")
            })
        }
        // Each session returns one unique message so we can verify eviction.
        for (i in 1..13) {
            coEvery { repository.getMessagesPaged("session-$i", any(), any()) } returns
                Result.success(
                    MessagesPage(
                        listOf(MessageWithParts(info = Message(id = "m-$i", role = "user"))),
                        null
                    )
                )
        }

        // Open sessions 1..13 in order. Each loadMessages seeds the cache.
        for (i in 1..13) {
            core.writeChat { it.copy(currentSessionId = "session-$i") }
            chatVM.loadMessages("session-$i")
            advanceUntilIdle()
        }

        assertEquals(
            "Cache must be capped at SESSION_WINDOW_CACHE_CAPACITY (12)",
            12,
            core.sessionWindowCacheSize()
        )
        assertNull(
            "session-1 (LRU) must be evicted once session-13 lands",
            core.peekSessionWindow("session-1")
        )
        assertNotNull(
            "session-13 (MRU) must be retained",
            core.peekSessionWindow("session-13")
        )
        assertNotNull(
            "session-12 (second-newest) must be retained",
            core.peekSessionWindow("session-12")
        )
    }

    @Test
    fun `restoring a cached session promotes it to most recently used`() = runTest {
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        core.writeSessionList {
            it.copy(sessions = (1..13).map { i -> Session(id = "session-$i", directory = "/tmp/$i") })
        }
        for (i in 1..13) {
            coEvery { repository.getMessagesPaged("session-$i", any(), any()) } returns
                Result.success(MessagesPage(emptyList(), null))
        }

        // Load sessions 1..12 — fills the cache exactly to capacity.
        for (i in 1..12) {
            core.writeChat { it.copy(currentSessionId = "session-$i") }
            chatVM.loadMessages("session-$i")
            advanceUntilIdle()
        }
        assertEquals(12, core.sessionWindowCacheSize())
        assertNotNull(core.peekSessionWindow("session-1"))

        // Re-visit session-1 to promote it to MRU.
        core.writeChat { it.copy(currentSessionId = "session-1") }
        chatVM.loadMessages("session-1")
        advanceUntilIdle()

        // Load session-13 — overflows; evicts the new LRU (session-2), NOT
        // session-1 (which is now MRU after the re-visit).
        core.writeChat { it.copy(currentSessionId = "session-13") }
        chatVM.loadMessages("session-13")
        advanceUntilIdle()

        assertEquals(12, core.sessionWindowCacheSize())
        assertNotNull(
            "session-1 (re-visited) must survive — re-visit promoted it to MRU",
            core.peekSessionWindow("session-1")
        )
        assertNull(
            "session-2 (new LRU after session-1 promotion) must be evicted",
            core.peekSessionWindow("session-2")
        )
    }

    @Test
    fun `refreshCurrentSession is a no-op when no session is selected`() = runTest {
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        // currentSessionId stays null from createViewModel().
        chatVM.refreshCurrentSession()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    @Test
    fun `refreshCurrentSession is a no-op while a load is in flight`() = runTest {
        coEvery { repository.getMessagesPaged("session-A", any(), any()) } coAnswers {
            delay(100)
            Result.success(MessagesPage(emptyList(), null))
        }
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "session-A") }

        // Kick off a load; do NOT advance time so isLoadingMessages stays true.
        chatVM.loadMessages("session-A")
        // refreshCurrentSession must bail out (coalesce) — only one fetch total.
        chatVM.refreshCurrentSession()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getMessagesPaged("session-A", any(), any()) }
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
        coEvery { repository.getMessagesPaged("session-A", any(), any()) } returns
            Result.success(MessagesPage(listOf(fresh), null))
        // §4-A: refreshCurrentSession now also forces a testConnection probe
        // (so a stale red badge recovers). Mock checkHealth as a FAILURE so the
        // probe's failure path runs (writes error+disconnected, which this test
        // ignores) WITHOUT cascading into loadInitialData/startSSE (which would
        // hit unmocked repository calls). The message-reload assertions below
        // are about the cold-start refresh, independent of the probe outcome.
        coEvery { repository.checkHealth() } returns
            Result.failure(IllegalStateException("offline"))

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        core.writeChat {
            it.copy(
                currentSessionId = "session-A",
                messages = listOf(older),
                olderMessagesCursor = "cursor-1",
                hasMoreMessages = true,
                gapMarkers = listOf(
                    cn.vectory.ocdroid.ui.chat.GapMarker(
                        gapId = "g1",
                        lowerAnchorMessageId = "m_older",
                        upperBoundaryMessageId = "m_older",
                        nextBeforeCursor = "c",
                        fillState = cn.vectory.ocdroid.ui.chat.GapFillState.Idle,
                    )
                )
            )
        }

        chatVM.refreshCurrentSession()
        advanceUntilIdle()

        val ids = chatVM.chatFlow.value.messages.map { it.id }
        assertFalse("Older message is dropped on cold-start refresh (got $ids)", ids.contains("m_older"))
        assertTrue("Fresh latest window is loaded (got $ids)", ids.contains("m_fresh"))
        // Cursor reseeded from the fresh fetch (nextCursor=null → no more).
        assertNull(chatVM.chatFlow.value.olderMessagesCursor)
        // Gap wiped — a cold-start snapshot has no断层 reference.
        assertTrue("cold-start refresh wipes gap markers", chatVM.chatFlow.value.gapMarkers.isEmpty())
        // §3 (glm-1 🔴 regression guard): refreshNonce MUST survive the
        // performGlobalColdStartRefresh → writeChat chain. Before the
        // wholesale ChatState rebuild fix, refreshNonce reset to 0, so
        // ChatScreen's clear-on-refresh signal never fired. Asserting > 0 here locks the fix in.
        assertTrue(
            "refreshNonce incremented by performGlobalColdStartRefresh (got ${chatVM.chatFlow.value.refreshNonce})",
            chatVM.chatFlow.value.refreshNonce > 0L
        )
    }

    @Test
    fun `refreshCurrentSession surfaces 已刷新 success toast when health and messages both succeed`() = runTest {
        // §1-addendum + §4-A (glm-1 coverage gap): the success path —
        // checkHealth healthy AND message reload settled cleanly — must post
        // successMessage="已刷新". The prior test only covered the failure
        // branch (checkHealth mocked offline), leaving onSettled(true) →
        // successMessage entirely untested.
        val fresh = MessageWithParts(info = Message(id = "m_fresh", role = "assistant"))
        coEvery { repository.getMessagesPaged("session-A", any(), any()) } returns
            Result.success(MessagesPage(listOf(fresh), null))
        // Healthy probe → onSettled(true). loadInitialData sub-callers are
        // stubbed in setUp (getAgents/getProviders/getCommands/etc.).
        coEvery { repository.checkHealth() } returns
            Result.success(HealthResponse(healthy = true, version = "1.0"))

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "session-A") }

        chatVM.refreshCurrentSession()
        advanceUntilIdle()

        // Both health AND messages succeeded → "Refreshed" toast posted.
        // §R18 Phase 2-G: production emits UiEvent.Success(R.string.success_refreshed);
        // the test-only resolver (TestUiEventCollector.testStringFor) renders the
        // default-locale English text "Refreshed".
        assertEquals("Refreshed", core.recentTestSuccesses.lastOrNull())
        // Badge recovered (the §4-A motivation).
        assertTrue("badge recovered to connected", connectionVM.connectionFlow.value.isConnected)
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
        coEvery { repository.getMessagesPaged("session-1", any(), any()) } returns
            Result.success(MessagesPage(emptyList(), null))
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "session-1") }

        handleSse(core,
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
        val status = sessionVM.sessionListFlow.value.sessionStatuses["session-1"]
        assertNotNull(status)
        assertTrue(status!!.isBusy)
        // ...and a reload was issued for the current session.
        coVerify(atLeast = 1) { repository.getMessagesPaged("session-1", any(), any()) }

        // (b) A busy transition on a NON-current session only updates the
        // status badge — it must NOT trigger a reload (the user is not viewing
        // that session).
        val core2 = createCore()
        val chatVM2 = cn.vectory.ocdroid.ui.ChatViewModel(core2)
        val sessionVM2 = cn.vectory.ocdroid.ui.SessionViewModel(core2)
        val connectionVM2 = cn.vectory.ocdroid.ui.ConnectionViewModel(core2)
        val hostVM2 = cn.vectory.ocdroid.ui.HostViewModel(core2)
        val composerVM2 = cn.vectory.ocdroid.ui.ComposerViewModel(core2)
        val orchestratorVM2 = cn.vectory.ocdroid.ui.OrchestratorViewModel(core2)
        val viewModel2 = ChatViewModel(core2)
        core2.writeChat { it.copy(currentSessionId = "session-1") }

        handleSse(core2,
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

        val statusOther = sessionVM2.sessionListFlow.value.sessionStatuses["session-other"]
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
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ChatViewModel(core)  // primary VM under test
        core.writeChat {
            it.copy(
                currentSessionId = "session-1",
                messages = listOf(existing)
            )
        }

        // (a) ABSENT message → inserted at the tail (newest end).
        handleSse(core,
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

        val afterInsert = chatVM.chatFlow.value.messages
        assertEquals(2, afterInsert.size)
        assertEquals("m_existing", afterInsert[0].id)
        assertEquals("msg_new", afterInsert[1].id)

        // (b) EXISTING message → patched in place, NOT duplicated.
        handleSse(core,
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

        val afterPatch = chatVM.chatFlow.value.messages
        // Still 2 — the existing id was patched in place, not appended again.
        assertEquals(2, afterPatch.size)
        assertEquals("m_existing", afterPatch[0].id)
        assertEquals("updated", afterPatch[0].agent)
        assertEquals("stop", afterPatch[0].finish)
        assertEquals("msg_new", afterPatch[1].id)

        // Patch/insert are pure state updates — no reload issued for either case.
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

}
