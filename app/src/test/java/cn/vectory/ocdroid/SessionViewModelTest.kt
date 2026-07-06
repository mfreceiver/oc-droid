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
class SessionViewModelTest : MainViewModelTestBase() {

    @Test
    fun `createSessionInWorkdir clears currentModel to prevent cross-session leak`() = runTest {
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = SessionViewModel(core)  // primary VM under test
        // Simulate coming from a session whose inferred model lingered in ChatState.
        composerVM.switchSessionModel("zhipuai-coding-plan", "glm-5.2")
        assertEquals(
            Message.ModelInfo("zhipuai-coding-plan", "glm-5.2"),
            chatVM.chatFlow.value.currentModel
        )

        // Enter draft mode for a new project.
        sessionVM.createSessionInWorkdir("/tmp/other-project")
        advanceUntilIdle()

        // §fix-draft-model-leak: currentModel MUST be cleared so the prior session's
        // model is neither shown in the draft picker nor persisted on materialisation
        // when the user sends without explicitly switching.
        assertNull(chatVM.chatFlow.value.currentModel)
        // No session exists → nothing persisted.
        verify(exactly = 0) { settingsManager.setModelForSession(any(), any(), any()) }
    }

    @Test
    fun `createSession and session created SSE keep a single unique session`() = runTest {
        val created = cn.vectory.ocdroid.data.model.Session(
            id = "session-1",
            directory = "/tmp/project",
            title = "New Session"
        )
        coEvery { repository.createSession(any(), any()) } returns Result.success(created)

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = SessionViewModel(core)  // primary VM under test

        sessionVM.createSession()
        advanceUntilIdle()

        handleSse(core,
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

        val sessions = sessionVM.sessionListFlow.value.sessions
        assertEquals(1, sessions.size)
        assertEquals("session-1", sessions.single().id)
        assertEquals("Server Title", sessions.single().title)
    }

    @Test
    fun `createSessionInWorkdir enters draft mode without issuing POST session`() = runTest {
        // Deferred-create: createSessionInWorkdir must NOT call the server.
        // It only sets the repository cwd and enters draftWorkdir state; the
        // session is created lazily by sendMessage on first prompt.

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = SessionViewModel(core)  // primary VM under test

        sessionVM.createSessionInWorkdir("/home/user/myproject")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.createSession(any(), any()) }
        // §R18 Phase 2-E step 2: createSessionInWorkdir no longer calls the
        // repository's global setCurrentDirectory (the API was removed); the
        // workdir is carried forward by composerFlow.draftWorkdir +
        // settingsManager.currentWorkdir.
        assertEquals("/home/user/myproject", composerVM.composerFlow.value.draftWorkdir)
        assertNull(chatVM.chatFlow.value.currentSessionId)
    }

    @Test
    fun `selectSession discards in-progress draft`() = runTest {

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = SessionViewModel(core)  // primary VM under test
        sessionVM.createSessionInWorkdir("/home/user/myproject")
        advanceUntilIdle()
        assertEquals("/home/user/myproject", composerVM.composerFlow.value.draftWorkdir)

        sessionVM.selectSession("session-1")
        advanceUntilIdle()

        assertNull(composerVM.composerFlow.value.draftWorkdir)
    }

    @Test
    fun `createSessionInWorkdir draft surfaces error when first send cannot create session`() = runTest {
        coEvery { repository.createSession(title = null, directory = any()) } returns Result.failure(IllegalStateException("network error"))

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = SessionViewModel(core)  // primary VM under test
        sessionVM.createSessionInWorkdir("/tmp/fail")
        advanceUntilIdle()
        composerVM.setInputText("hi")
        chatVM.sendMessage()
        advanceUntilIdle()

        assertNull(chatVM.chatFlow.value.currentSessionId)
        assertNotNull(core.recentTestErrors.lastOrNull())
        assertTrue(core.recentTestErrors.lastOrNull()!!.contains("network error"))
    }

    @Test
    fun `loadSessions requests current limit and tracks hasMore`() = runTest {
        val sessions = (1..10).map { index ->
            cn.vectory.ocdroid.data.model.Session(id = "session-$index", directory = "/tmp/$index")
        }
        coEvery { repository.getSessions(10) } returns Result.success(sessions)

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = SessionViewModel(core)  // primary VM under test

        sessionVM.loadSessions()
        advanceUntilIdle()

        coVerify { repository.getSessions(10) }
        assertEquals(10, sessionVM.sessionListFlow.value.loadedSessionLimit)
        assertTrue(sessionVM.sessionListFlow.value.hasMoreSessions)
        assertEquals(10, sessionVM.sessionListFlow.value.sessions.size)
        assertFalse(sessionVM.sessionListFlow.value.isRefreshingSessions)
    }

    @Test
    fun `loadSessions clears isRefreshingSessions after successful fetch`() = runTest {
        val sessions = listOf(
            cn.vectory.ocdroid.data.model.Session(id = "session-1", directory = "/tmp/1")
        )
        coEvery { repository.getSessions(any()) } returns Result.success(sessions)

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = SessionViewModel(core)  // primary VM under test

        sessionVM.loadSessions()
        advanceUntilIdle()

        assertFalse(sessionVM.sessionListFlow.value.isRefreshingSessions)
    }

    @Test
    fun `loadSessions fetches sub_agent sessions created after initial load`() = runTest {
        val initialSessions = listOf(
            cn.vectory.ocdroid.data.model.Session(id = "parent-1", directory = "/tmp/project")
        )
        coEvery { repository.getSessions(10) } returns Result.success(initialSessions)

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = SessionViewModel(core)  // primary VM under test
        sessionVM.loadSessions()
        advanceUntilIdle()

        assertEquals(1, sessionVM.sessionListFlow.value.sessions.size)
        assertEquals("parent-1", sessionVM.sessionListFlow.value.sessions.single().id)

        val refreshedSessions = listOf(
            cn.vectory.ocdroid.data.model.Session(id = "parent-1", directory = "/tmp/project"),
            cn.vectory.ocdroid.data.model.Session(
                id = "child-1",
                directory = "/tmp/project",
                parentId = "parent-1"
            )
        )
        coEvery { repository.getSessions(10) } returns Result.success(refreshedSessions)

        sessionVM.loadSessions()
        advanceUntilIdle()

        assertEquals(2, sessionVM.sessionListFlow.value.sessions.size)
        assertEquals("child-1", sessionVM.sessionListFlow.value.sessions.find { it.parentId == "parent-1" }?.id)
        assertFalse(sessionVM.sessionListFlow.value.isRefreshingSessions)
    }

    @Test
    fun `loadSessions clears isRefreshingSessions on failure`() = runTest {
        coEvery { repository.getSessions(any()) } returns Result.failure(IllegalStateException("network error"))

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = SessionViewModel(core)  // primary VM under test

        sessionVM.loadSessions()
        advanceUntilIdle()

        assertFalse(sessionVM.sessionListFlow.value.isRefreshingSessions)
        assertEquals("Failed to load sessions: network error", core.recentTestErrors.lastOrNull())
    }

    @Test
    fun `loadMoreSessions requests higher limit and replaces sessions`() = runTest {
        val initial = (1..10).map { index ->
            cn.vectory.ocdroid.data.model.Session(id = "session-$index", directory = "/tmp/$index")
        }
        val expanded = (1..15).map { index ->
            cn.vectory.ocdroid.data.model.Session(id = "session-$index", directory = "/tmp/$index")
        }
        coEvery { repository.getSessions(20) } returns Result.success(expanded)

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = SessionViewModel(core)  // primary VM under test
        core.writeSessionList {
            it.copy(
                sessions = initial,
                loadedSessionLimit = 10,
                hasMoreSessions = true
            )
        }
        core.writeChat { it.copy(currentSessionId = "session-5") }

        sessionVM.loadMoreSessions()
        advanceUntilIdle()

        coVerify { repository.getSessions(20) }
        assertEquals(20, sessionVM.sessionListFlow.value.loadedSessionLimit)
        assertFalse(sessionVM.sessionListFlow.value.hasMoreSessions)
        assertEquals(15, sessionVM.sessionListFlow.value.sessions.size)
        assertEquals("session-5", chatVM.chatFlow.value.currentSessionId)
    }

    @Test
    fun `loadMoreSessions ignores duplicate triggers while request is in flight`() = runTest {
        val expanded = (1..15).map { index ->
            cn.vectory.ocdroid.data.model.Session(id = "session-$index", directory = "/tmp/$index")
        }
        coEvery { repository.getSessions(20) } coAnswers {
            kotlinx.coroutines.delay(100)
            Result.success(expanded)
        }

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = SessionViewModel(core)  // primary VM under test
        core.writeSessionList {
            it.copy(
                sessions = (1..10).map { index -> cn.vectory.ocdroid.data.model.Session(id = "session-$index", directory = "/tmp/$index") },
                loadedSessionLimit = 10,
                hasMoreSessions = true
            )
        }

        sessionVM.loadMoreSessions()
        sessionVM.loadMoreSessions()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getSessions(20) }
        assertEquals(20, sessionVM.sessionListFlow.value.loadedSessionLimit)
    }

    @Test
    fun `loadSessions does not override non-null currentSessionId with first`() = runTest {
        // Scenario: currentSessionId is set but the session is temporarily
        // absent from the refreshed list (e.g. created moments ago, not yet
        // propagated). loadSessions must NOT silently reselect sessions.first().
        val knownSessions = listOf(
            cn.vectory.ocdroid.data.model.Session(id = "session-A", directory = "/tmp/a"),
            cn.vectory.ocdroid.data.model.Session(id = "session-B", directory = "/tmp/b")
        )
        coEvery { repository.getSessions(any()) } returns Result.success(knownSessions)

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = SessionViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "session-not-in-list") }

        sessionVM.loadSessions()
        advanceUntilIdle()

        assertEquals(
            "currentSessionId must be preserved, not replaced by first()",
            "session-not-in-list",
            chatVM.chatFlow.value.currentSessionId
        )
        // Messages for the (temporarily-absent) current session are reloaded
        // so the user keeps their context.
        coVerify(atLeast = 1) { repository.getMessagesPaged("session-not-in-list", any(), any()) }
    }

    @Test
    fun `loadMoreSessions does not override non-null currentSessionId with first`() = runTest {
        val initial = (1..10).map { index ->
            cn.vectory.ocdroid.data.model.Session(id = "session-$index", directory = "/tmp/$index")
        }
        val expanded = (1..15).map { index ->
            cn.vectory.ocdroid.data.model.Session(id = "session-$index", directory = "/tmp/$index")
        }
        coEvery { repository.getSessions(20) } returns Result.success(expanded)

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = SessionViewModel(core)  // primary VM under test
        core.writeSessionList {
            it.copy(
                sessions = initial,
                loadedSessionLimit = 10,
                hasMoreSessions = true
            )
        }
        // currentSessionId is set but the session is NOT in the refreshed
        // list — loadMore must keep it rather than reselecting first().
        core.writeChat { it.copy(currentSessionId = "session-not-in-list") }

        sessionVM.loadMoreSessions()
        advanceUntilIdle()

        assertEquals(
            "currentSessionId preserved across loadMore even when absent from refresh",
            "session-not-in-list",
            chatVM.chatFlow.value.currentSessionId
        )
    }

    @Test
    fun `createSessionInWorkdir fetches directory sessions into directorySessions map`() = runTest {
        val workdir = "/home/user/myproject"
        val existing = listOf(
            cn.vectory.ocdroid.data.model.Session(id = "existing-1", directory = workdir, title = "Prior chat"),
            cn.vectory.ocdroid.data.model.Session(id = "existing-2", directory = workdir, title = "Another")
        )
        coEvery { repository.getSessionsForDirectory(workdir, any()) } returns Result.success(existing)

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = SessionViewModel(core)  // primary VM under test
        sessionVM.createSessionInWorkdir(workdir)
        advanceUntilIdle()

        assertEquals(workdir, composerVM.composerFlow.value.draftWorkdir)
        // §recent-workdirs: connecting a project records it so cold-start
        // loadInitialData can restore its directory sessions after restart.
        verify { settingsManager.addRecentWorkdir(workdir) }
        val dirSessions = sessionVM.sessionListFlow.value.directorySessions[workdir]
        assertNotNull("directorySessions should contain an entry for the workdir", dirSessions)
        assertEquals(2, dirSessions!!.size)
        assertEquals("existing-1", dirSessions[0].id)
        // The fetched sessions must NOT pollute the global sessions list.
        assertTrue(
            "directory sessions must not be written into state.sessions",
            sessionVM.sessionListFlow.value.sessions.none { it.id == "existing-1" }
        )
    }

    @Test
    fun `createSessionInWorkdir directorySessions failure is silently ignored`() = runTest {
        val workdir = "/home/user/broken"
        coEvery { repository.getSessionsForDirectory(workdir, any()) } returns Result.failure(IllegalStateException("boom"))

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = SessionViewModel(core)  // primary VM under test
        sessionVM.createSessionInWorkdir(workdir)
        advanceUntilIdle()

        assertEquals(workdir, composerVM.composerFlow.value.draftWorkdir)
        assertNull(core.recentTestErrors.lastOrNull())
        assertTrue(sessionVM.sessionListFlow.value.directorySessions.isEmpty())
    }

    @Test
    fun `createSessionInWorkdir trims workdir so all downstream keys stay consistent`() = runTest {
        // §key-consistency: the entry-point trim ensures currentWorkdir,
        // recentWorkdirs, directorySessions map key, and getSessionsForDirectory
        // all share ONE key. Without it, a whitespace-padded path would split
        // into two directorySessions entries (raw here vs trimmed in the
        // cold-start restore path that reads recentWorkdirs).
        val raw = "  /home/user/myproject  "
        val trimmed = "/home/user/myproject"
        coEvery { repository.getSessionsForDirectory(trimmed, any()) } returns Result.success(emptyList())

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = SessionViewModel(core)  // primary VM under test
        sessionVM.createSessionInWorkdir(raw)
        advanceUntilIdle()

        verify { settingsManager.currentWorkdir = trimmed }
        verify { settingsManager.addRecentWorkdir(trimmed) }
        coVerify { repository.getSessionsForDirectory(trimmed, any()) }
        // §R18 Phase 2-E step 2: the repository.setCurrentDirectory verify
        // was removed (the API is gone); the workdir is now carried by
        // settingsManager (verified above).
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

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = SessionViewModel(core)  // primary VM under test
        core.writeSessionList { it.copy(sessions = listOf(parent, child)) }

        sessionVM.archiveSession("parent")
        advanceUntilIdle()

        coVerifyOrder {
            repository.updateSessionArchived("child", any())
            repository.updateSessionArchived("parent", any())
        }
        assertTrue(sessionVM.sessionListFlow.value.sessions.all { it.isArchived })
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

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = SessionViewModel(core)  // primary VM under test
        core.writeSessionList { it.copy(sessions = listOf(parent, child)) }

        sessionVM.restoreSession("parent")
        advanceUntilIdle()

        coVerifyOrder {
            repository.updateSessionArchived("parent", -1L)
            repository.updateSessionArchived("child", -1L)
        }
        assertFalse(sessionVM.sessionListFlow.value.sessions.any { it.isArchived })
    }

    @Test
    fun `selectSession saves old draft and restores new draft from settings manager`() = runTest {
        every { settingsManager.getDraftText("s2") } returns "draft2"

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = SessionViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "s1") }
        core.writeComposer { it.copy(inputText = "draft1") }

        sessionVM.selectSession("s2")
        advanceUntilIdle()

        verify { settingsManager.setDraftText("s1", "draft1") }
        verify { settingsManager.getDraftText("s2") }
        assertEquals("draft2", composerVM.composerFlow.value.inputText)
    }

    @Test
    fun `deleteSession removes deleted session from state`() = runTest {
        coEvery { repository.deleteSession("session-1") } returns Result.success(Unit)

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = SessionViewModel(core)  // primary VM under test
        core.writeSessionList {
            it.copy(
                sessions = listOf(
                    cn.vectory.ocdroid.data.model.Session(id = "session-1", directory = "/tmp/one"),
                    cn.vectory.ocdroid.data.model.Session(id = "session-2", directory = "/tmp/two")
                )
            )
        }
        core.writeChat { it.copy(currentSessionId = "session-2") }

        sessionVM.deleteSession("session-1")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.deleteSession("session-1") }
        assertEquals(listOf("session-2"), sessionVM.sessionListFlow.value.sessions.map { it.id })
    }

    @Test
    fun `selectSession on directorySessions-only session upserts it and preserves workdir`() = runTest {
        val workdir = "/home/user/project"
        val dirSession = cn.vectory.ocdroid.data.model.Session(
            id = "dir-only-1", directory = workdir, title = "From directory"
        )

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = SessionViewModel(core)  // primary VM under test
        core.writeSessionList {
            it.copy(directorySessions = mapOf(workdir to listOf(dirSession)))
        }
        // Precondition: the session lives only in directorySessions.
        assertTrue(sessionVM.sessionListFlow.value.sessions.none { it.id == "dir-only-1" })

        sessionVM.selectSession("dir-only-1")
        advanceUntilIdle()

        // #10: the directory session must be upserted so currentSession resolves
        // (previously it stayed null and the workdir was dropped).
        assertEquals("dir-only-1", chatVM.chatFlow.value.currentSessionId)
        assertNotNull(currentSession(sessionVM.sessionListFlow.value.sessions, chatVM.chatFlow.value.currentSessionId))
        assertEquals("dir-only-1", currentSession(sessionVM.sessionListFlow.value.sessions, chatVM.chatFlow.value.currentSessionId)?.id)
        // §R18 Phase 2-E step 2: switchTo no longer calls repository's global
        // setCurrentDirectory; directory routing now uses the session's
        // directory field directly at each callsite.
    }

    @Test
    fun `openSubAgent surfaces error and keeps currentSessionId when child cannot be resolved`() = runTest {
        coEvery { repository.getSession("child-missing") } returns Result.failure(IllegalStateException("404"))

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = SessionViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "parent-1") }
        val beforeId = chatVM.chatFlow.value.currentSessionId

        sessionVM.openSubAgent("child-missing")
        advanceUntilIdle()

        assertEquals(beforeId, chatVM.chatFlow.value.currentSessionId)
        assertNotNull("error channel must be set when child session is unavailable", core.recentTestErrors.lastOrNull())
    }

    @Test
    fun `closeSession preserves closed session draft and does not pollute next session draft`() = runTest {
        every { settingsManager.openSessionIds } returns listOf("s1", "s2")
        every { settingsManager.openSessionIds = any() } just runs
        every { settingsManager.getDraftText("s2") } returns "s2draft"

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = SessionViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "s1") }
        core.writeComposer { it.copy(inputText = "s1-unsent-draft") }
        core.writeSessionList { it.copy(openSessionIds = listOf("s1", "s2")) }

        sessionVM.closeSession("s1")
        advanceUntilIdle()

        // Closed session's draft must be saved under its own id (not lost).
        verify(atLeast = 1) { settingsManager.setDraftText("s1", "s1-unsent-draft") }
        // The next session must NOT inherit the closed session's draft text.
        verify(exactly = 0) { settingsManager.setDraftText("s2", "s1-unsent-draft") }
        // s2 becomes current and its own draft is restored.
        assertEquals("s2", chatVM.chatFlow.value.currentSessionId)
        assertEquals("s2draft", composerVM.composerFlow.value.inputText)
    }

    @Test
    fun `deleteSession clears persisted currentSessionId when no sessions remain`() = runTest {
        coEvery { repository.deleteSession("s1") } returns Result.success(Unit)

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = SessionViewModel(core)  // primary VM under test
        core.writeSessionList {
            it.copy(
                sessions = listOf(cn.vectory.ocdroid.data.model.Session(id = "s1", directory = "/tmp"))
            )
        }
        core.writeChat { it.copy(currentSessionId = "s1") }

        sessionVM.deleteSession("s1")
        advanceUntilIdle()

        assertNull(chatVM.chatFlow.value.currentSessionId)
        // §R18 Phase 2-F: SettingsManager is no longer written directly here —
        // chatFlow.currentSessionId (asserted null above) is the runtime source;
        // the AppCore collector persists non-null changes only.
    }

    @Test
    fun `deleteSession removes the session from directorySessions so it cannot be re-selected`() = runTest {
        val workdir = "/home/user/project"
        val ghost = cn.vectory.ocdroid.data.model.Session(
            id = "dir-ghost", directory = workdir, title = "From directory"
        )
        coEvery { repository.deleteSession("dir-ghost") } returns Result.success(Unit)

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = SessionViewModel(core)  // primary VM under test
        core.writeSessionList {
            it.copy(directorySessions = mapOf(workdir to listOf(ghost)))
        }
        // Precondition: the session lives in directorySessions (not in sessions).
        assertTrue(sessionVM.sessionListFlow.value.directorySessions[workdir]?.any { it.id == "dir-ghost" } == true)
        assertTrue(sessionVM.sessionListFlow.value.sessions.none { it.id == "dir-ghost" })

        sessionVM.deleteSession("dir-ghost")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.deleteSession("dir-ghost") }
        // The ghost must be gone from directorySessions so the union UI cannot
        // render / re-select it.
        val remaining = sessionVM.sessionListFlow.value.directorySessions[workdir].orEmpty()
        assertTrue("deleted id must be purged from directorySessions", remaining.none { it.id == "dir-ghost" })
    }

}
