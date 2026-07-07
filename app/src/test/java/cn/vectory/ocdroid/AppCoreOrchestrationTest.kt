package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.QuestionInfo
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.MessagesPage
import cn.vectory.ocdroid.ui.AppCore
import cn.vectory.ocdroid.ui.ChatViewModel
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.ConnectionViewModel
import cn.vectory.ocdroid.ui.HostViewModel
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.SessionViewModel
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.catchUpAfterDisconnectOrForeground
import cn.vectory.ocdroid.ui.executeCommand
import cn.vectory.ocdroid.ui.loadMessagesForEffect
import cn.vectory.ocdroid.ui.materializeDraftSession
import cn.vectory.ocdroid.ui.openSessionFromDeepLink
import cn.vectory.ocdroid.ui.performGlobalColdStartRefresh
import cn.vectory.ocdroid.ui.resolveQuestionDirectory
import cn.vectory.ocdroid.ui.resetLocalDataAndResync
import cn.vectory.ocdroid.ui.sendMessage
import cn.vectory.ocdroid.util.ThemeMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §R18 Phase 5+: orchestration-layer coverage for the cross-domain methods on
 * [AppCore] / `AppCoreOrchestration.kt` (~840 lines combined). These methods
 * route through multiple controllers + slices + the effectBus; they are the
 * highest-yield uncovered region (gpter Gate-5 BLOCKER).
 *
 * Each test constructs an [AppCore] via [MainViewModelTestBase.createCore]
 * (full controller + slice + effectBus wiring) and drives the `internal`
 * orchestration extensions directly. Slice writes are observed via the AppCore
 * accessors; UiEvents via [AppCore.recentTestErrors]; repository calls via
 * mockk `coVerify`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppCoreOrchestrationTest : MainViewModelTestBase() {

    /**
     * Convenience: construct a fully-wired AppCore + all 6 domain VMs so any
     * orchestration call sees the same controllers/slices the production VMs
     * share. Returns the core; the VMs are constructed for side-effect
     * (they don't add state — they only expose surface).
     */
    private fun wire(): AppCore {
        val core = createCore()
        ChatViewModel(core)
        SessionViewModel(core)
        ConnectionViewModel(core)
        HostViewModel(core)
        ComposerViewModel(core)
        OrchestratorViewModel(core)
        return core
    }

    // ── sendMessage ───────────────────────────────────────────────────────────

    @Test
    fun `sendMessage dispatches the prompt to the existing current session`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())
        coEvery { repository.getSession(any()) } returns Result.success(Session(id = "session-1", directory = "/x"))
        every { settingsManager.openSessionIds } returns emptyList()

        val core = wire()
        core.writeChat { it.copy(currentSessionId = "session-1") }
        core.writeSessionList { it.copy(sessions = listOf(Session(id = "session-1", directory = "/x"))) }
        core.writeComposer { it.copy(inputText = "hello") }

        core.sendMessage()
        advanceUntilIdle()

        coVerify { repository.sendMessage("session-1", "hello", any(), any(), any()) }
        // inputText cleared synchronously by dispatchSendMessage.
        assertEquals("", core.composerFlow.value.inputText)
    }

    @Test
    fun `sendMessage no-ops when input text and attachments are both empty`() = runTest {
        val core = wire()
        core.writeChat { it.copy(currentSessionId = "session-1") }

        core.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.sendMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `sendMessage no-ops when no current session and no draft workdir`() = runTest {
        val core = wire()
        core.writeComposer { it.copy(inputText = "hello") }

        core.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.sendMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `sendMessage skips dispatch when session id is already in sendingSessionIds`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        val core = wire()
        core.writeChat { it.copy(currentSessionId = "session-1") }
        core.writeComposer { it.copy(inputText = "hello", sendingSessionIds = setOf("session-1")) }

        core.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.sendMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `sendMessage in draft mode materialises session then dispatches`() = runTest {
        val created = Session(id = "session-new", directory = "/proj")
        coEvery { repository.createSession(title = null, directory = any()) } returns Result.success(created)
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(created))
        coEvery { repository.getSession(any()) } returns Result.success(created)
        every { settingsManager.openSessionIds } returns emptyList()

        val core = wire()
        core.writeComposer { it.copy(inputText = "hi", draftWorkdir = "/proj") }

        core.sendMessage()
        advanceUntilIdle()

        coVerifyOrder {
            repository.createSession(title = null, directory = any())
            repository.sendMessage("session-new", "hi", any(), any(), any())
        }
        assertEquals("session-new", core.chatFlow.value.currentSessionId)
        assertNull(core.composerFlow.value.draftWorkdir)
    }

    // ── materializeDraftSession ───────────────────────────────────────────────

    @Test
    fun `materializeDraftSession failure restores draftWorkdir and emits error`() = runTest {
        coEvery { repository.createSession(title = null, directory = any()) } returns Result.failure(IllegalStateException("nope"))

        val core = wire()
        core.writeComposer { it.copy(inputText = "hi", draftWorkdir = "/retry") }

        core.materializeDraftSession { }
        advanceUntilIdle()

        assertEquals("/retry", core.composerFlow.value.draftWorkdir)
        assertNotNull(core.recentTestErrors.lastOrNull())
        assertTrue(core.recentTestErrors.lastOrNull()!!.contains("nope"))
    }

    @Test
    fun `materializeDraftSession no-ops when draftWorkdir is null`() = runTest {
        val core = wire()
        var called = false

        core.materializeDraftSession { called = true }
        advanceUntilIdle()

        assertFalse(called)
        coVerify(exactly = 0) { repository.createSession(any(), any()) }
    }

    @Test
    fun `materializeDraftSession copies current model and agent to per-session storage`() = runTest {
        val created = Session(id = "new", directory = "/p")
        coEvery { repository.createSession(title = null, directory = any()) } returns Result.success(created)
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(created))
        coEvery { repository.getSession(any()) } returns Result.success(created)
        every { settingsManager.openSessionIds } returns emptyList()

        val core = wire()
        core.writeChat { it.copy(currentModel = Message.ModelInfo("openai", "gpt-5")) }
        core.writeSettings { it.copy(selectedAgentName = "code") }
        core.writeComposer { it.copy(inputText = "hi", draftWorkdir = "/p") }

        core.materializeDraftSession { }
        advanceUntilIdle()

        verify { settingsManager.setModelForSession(any(), "new", "openai", "gpt-5") }
        verify { settingsManager.setAgentForSession(any(), "new", "code") }
    }

    // ── executeCommand ────────────────────────────────────────────────────────

    @Test
    fun `executeCommand empty command is a no-op`() = runTest {
        val core = wire()

        core.executeCommand(command = "", arguments = "")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.executeCommand(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.createSession(any(), any()) }
    }

    @Test
    fun `executeCommand clear branch with currentWorkdir enters draft mode`() = runTest {
        every { settingsManager.currentWorkdir } returns "/workdir"
        coEvery { repository.getSessionsForDirectory(any()) } returns Result.success(emptyList())

        val core = wire()
        core.writeComposer { it.copy(inputText = "draft text") }

        core.executeCommand(command = "/clear", arguments = "")
        advanceUntilIdle()

        // Composer cleared + draftWorkdir set to currentWorkdir.
        assertEquals("", core.composerFlow.value.inputText)
        assertEquals("/workdir", core.composerFlow.value.draftWorkdir)
        // No session created in /clear (deferred to first send).
        coVerify(exactly = 0) { repository.createSession(any(), any()) }
    }

    @Test
    fun `executeCommand clear branch falls back to current session directory when no workdir`() = runTest {
        every { settingsManager.currentWorkdir } returns null
        val session = Session(id = "session-1", directory = "/from-session")
        coEvery { repository.getSessionsForDirectory(any()) } returns Result.success(emptyList())

        val core = wire()
        core.writeChat { it.copy(currentSessionId = "session-1") }
        core.writeSessionList { it.copy(sessions = listOf(session)) }

        core.executeCommand(command = "/clear", arguments = "")
        advanceUntilIdle()

        // Fell back to session.directory for draftWorkdir.
        assertEquals("/from-session", core.composerFlow.value.draftWorkdir)
        // §note: mockk setter doesn't reflect on getter; we assert the setter
        // was invoked instead of re-reading settingsManager.currentWorkdir.
        verify { settingsManager.currentWorkdir = "/from-session" }
    }

    @Test
    fun `executeCommand clear branch with no workdir and no session creates a fresh session`() = runTest {
        every { settingsManager.currentWorkdir } returns null
        val created = Session(id = "fresh", directory = "/x")
        coEvery { repository.createSession(any(), any()) } returns Result.success(created)

        val core = wire()

        core.executeCommand(command = "/clear", arguments = "")
        advanceUntilIdle()

        // Fresh session created via createSessionForEffect.
        coVerify { repository.createSession(null, any()) }
    }

    @Test
    fun `executeCommand slash command on existing session passes directory from session`() = runTest {
        coEvery { repository.executeCommand(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        val session = Session(id = "session-1", directory = "/proj")
        val core = wire()
        core.writeChat { it.copy(currentSessionId = "session-1") }
        core.writeSessionList { it.copy(sessions = listOf(session)) }
        core.writeComposer { it.copy(inputText = "args") }

        core.executeCommand(command = "/compact", arguments = "extra")
        advanceUntilIdle()

        coVerify {
            repository.executeCommand("session-1", "compact", "extra", any(), directory = "/proj")
        }
        // Composer cleared.
        assertEquals("", core.composerFlow.value.inputText)
    }

    @Test
    fun `executeCommand slash command in draft mode materialises then dispatches`() = runTest {
        val created = Session(id = "session-mat", directory = "/draft")
        coEvery { repository.createSession(title = null, directory = any()) } returns Result.success(created)
        coEvery { repository.executeCommand(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(created))
        coEvery { repository.getSession(any()) } returns Result.success(created)
        every { settingsManager.openSessionIds } returns emptyList()

        val core = wire()
        core.writeComposer { it.copy(inputText = "args", draftWorkdir = "/draft") }

        core.executeCommand(command = "/compact", arguments = "")
        advanceUntilIdle()

        coVerifyOrder {
            repository.createSession(title = null, directory = any())
            repository.executeCommand("session-mat", "compact", "", any(), directory = "/draft")
        }
    }

    @Test
    fun `executeCommand slash command with no session and no draft emits chat_command_no_session`() = runTest {
        val core = wire()
        // §note: installTestUiEventCollector launches its collector on the test
        // dispatcher; we must pump once before any synchronous emit so the
        // collector is subscribed (SharedFlow has replay=0).
        advanceUntilIdle()

        core.executeCommand(command = "/compact", arguments = "")
        advanceUntilIdle()

        // chat_command_no_session error emitted, no repository call.
        coVerify(exactly = 0) { repository.executeCommand(any(), any(), any(), any(), any()) }
        assertTrue(core.recentTestErrors.lastOrNull()!!.contains("/compact"))
    }

    @Test
    fun `executeCommand slash command failure emits error_command_failed`() = runTest {
        coEvery { repository.executeCommand(any(), any(), any(), any(), any()) } returns Result.failure(IllegalStateException("denied"))
        val session = Session(id = "session-1", directory = "/proj")
        val core = wire()
        core.writeChat { it.copy(currentSessionId = "session-1") }
        core.writeSessionList { it.copy(sessions = listOf(session)) }

        core.executeCommand(command = "/compact", arguments = "")
        advanceUntilIdle()

        val err = core.recentTestErrors.lastOrNull()
        assertNotNull(err)
        assertTrue(err!!.contains("/compact"))
        assertTrue(err.contains("denied"))
    }

    @Test
    fun `executeCommand resolves commandDirectory from draftWorkdir when current session is absent`() = runTest {
        // Gate-2 fix: when the currentSession lookup returns null (session
        // not yet in the local list), the draft workdir wins over
        // settingsManager.currentWorkdir as the command directory fallback.
        coEvery { repository.executeCommand(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        every { settingsManager.currentWorkdir } returns "/cwd-A"
        val core = wire()
        core.writeChat { it.copy(currentSessionId = "session-1") }
        // sessions list does NOT contain session-1 → currentSession(...) is null.
        core.writeSessionList { it.copy(sessions = emptyList()) }
        core.writeComposer { it.copy(inputText = "args", draftWorkdir = "/cwd-B") }

        core.executeCommand(command = "/compact", arguments = "args")
        advanceUntilIdle()

        coVerify {
            repository.executeCommand("session-1", "compact", "args", any(), directory = "/cwd-B")
        }
    }

    // ── resolveQuestionDirectory ──────────────────────────────────────────────

    @Test
    fun `resolveQuestionDirectory returns the pending question's parent session directory`() = runTest {
        val session = Session(id = "session-q", directory = "/question-dir")
        val question = QuestionRequest(
            id = "req1",
            sessionId = "session-q",
            questions = listOf(QuestionInfo(question = "q", header = "h", options = emptyList())),
        )
        val core = wire()
        core.writeSessionList {
            it.copy(sessions = listOf(session), pendingQuestions = listOf(question))
        }

        val dir = core.resolveQuestionDirectory("req1")
        assertEquals("/question-dir", dir)
    }

    @Test
    fun `resolveQuestionDirectory resolves from directorySessions when session is a connected-workdir one`() = runTest {
        val session = Session(id = "session-q", directory = "/connected")
        val question = QuestionRequest(
            id = "req1",
            sessionId = "session-q",
            questions = listOf(QuestionInfo(question = "q", header = "h", options = emptyList())),
        )
        val core = wire()
        core.writeSessionList {
            it.copy(
                sessions = emptyList(),
                directorySessions = mapOf("/connected" to listOf(session)),
                pendingQuestions = listOf(question),
            )
        }

        val dir = core.resolveQuestionDirectory("req1")
        assertEquals("/connected", dir)
    }

    @Test
    fun `resolveQuestionDirectory falls back to currentWorkdir when no matching session`() = runTest {
        every { settingsManager.currentWorkdir } returns "/fallback"
        val question = QuestionRequest(
            id = "req1",
            sessionId = "missing",
            questions = listOf(QuestionInfo(question = "q", header = "h", options = emptyList())),
        )
        val core = wire()
        core.writeSessionList { it.copy(pendingQuestions = listOf(question)) }

        val dir = core.resolveQuestionDirectory("req1")
        assertEquals("/fallback", dir)
    }

    @Test
    fun `resolveQuestionDirectory returns null when nothing resolves`() = runTest {
        every { settingsManager.currentWorkdir } returns null
        val core = wire()

        val dir = core.resolveQuestionDirectory("nonexistent")
        assertNull(dir)
    }

    // ── openSessionFromDeepLink ───────────────────────────────────────────────

    @Test
    fun `openSessionFromDeepLink fetches when session is not in local list`() = runTest {
        val fetched = Session(id = "deep-link-1", directory = "/x", title = "From Server")
        coEvery { repository.getSession("deep-link-1") } returns Result.success(fetched)
        val core = wire()

        core.openSessionFromDeepLink("deep-link-1")
        // §note: openSessionFromDeepLink uses withContext(Dispatchers.IO) which
        // escapes the test dispatcher; pump multiple times to flush the IO
        // resumption chain.
        repeat(3) {
            advanceUntilIdle()
            kotlinx.coroutines.delay(50)
        }
        advanceUntilIdle()

        // The deep-link path always issues a GET when the session is not in
        // the local list (the upsert into sessionListFlow happens in the same
        // coroutine, gated only by the IO resumption).
        coVerify { repository.getSession("deep-link-1") }
    }

    @Test
    fun `openSessionFromDeepLink skips fetch when session already in local list`() = runTest {
        val session = Session(id = "local-1", directory = "/x")
        val core = wire()
        core.writeSessionList { it.copy(sessions = listOf(session)) }

        core.openSessionFromDeepLink("local-1")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getSession(any()) }
    }

    @Test
    fun `openSessionFromDeepLink tolerates fetch failure and still selects`() = runTest {
        coEvery { repository.getSession(any()) } returns Result.failure(IllegalStateException("404"))
        val core = wire()

        core.openSessionFromDeepLink("missing")
        advanceUntilIdle()

        // No throw; nothing upserted (nothing to upsert).
        assertTrue(core.sessionListFlow.value.sessions.none { it.id == "missing" })
    }

    // ── resetLocalDataAndResync ───────────────────────────────────────────────

    @Test
    fun `resetLocalDataAndResync wipes settings and clears slices`() = runTest {
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())
        coEvery { repository.checkHealth() } returns Result.success(
            cn.vectory.ocdroid.data.model.HealthResponse(healthy = true, version = "1.0"),
        )
        every { settingsManager.clearAllLocalData() } just runs
        val core = wire()
        core.writeChat {
            it.copy(
                currentSessionId = "stale",
                messages = listOf(Message(id = "m", role = "user")),
            )
        }
        core.writeSessionList {
            it.copy(sessions = listOf(Session(id = "s1", directory = "/x")))
        }

        core.resetLocalDataAndResync()
        advanceUntilIdle()

        verify { settingsManager.clearAllLocalData() }
        assertNull(core.chatFlow.value.currentSessionId)
        assertTrue(core.chatFlow.value.messages.isEmpty())
        // SessionList slice reset to defaults.
        assertTrue(core.sessionListFlow.value.sessions.isEmpty())
    }

    // ── performGlobalColdStartRefresh ─────────────────────────────────────────

    @Test
    fun `performGlobalColdStartRefresh no-ops when a message load is already in flight`() = runTest {
        val core = wire()
        core.writeChat { it.copy(currentSessionId = "s1", isLoadingMessages = true) }

        core.performGlobalColdStartRefresh("s1")
        advanceUntilIdle()

        // No fetch issued (guard short-circuited).
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    @Test
    fun `performGlobalColdStartRefresh clears chat slice and bumps refreshNonce`() = runTest {
        val msgs = listOf(MessageWithParts(info = Message(id = "u1", role = "user")))
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns Result.success(MessagesPage(msgs, null))
        coEvery { repository.getSessionTodos(any()) } returns Result.success(emptyList())
        val core = wire()
        core.writeChat {
            it.copy(
                currentSessionId = "s1",
                messages = listOf(Message(id = "stale", role = "user")),
                gapMarkers = listOf(
                    cn.vectory.ocdroid.ui.chat.GapMarker(
                        gapId = "g1",
                        lowerAnchorMessageId = "a",
                        upperBoundaryMessageId = "b",
                        nextBeforeCursor = "c",
                        fillState = cn.vectory.ocdroid.ui.chat.GapFillState.Idle,
                    )
                ),
                staleNotice = true,
                streamingPartTexts = mapOf("p" to "x"),
            )
        }
        val nonceBefore = core.chatFlow.value.refreshNonce

        core.performGlobalColdStartRefresh("s1")
        advanceUntilIdle()

        assertEquals(nonceBefore + 1, core.chatFlow.value.refreshNonce)
        assertTrue("cold-start refresh clears stale gap markers", core.chatFlow.value.gapMarkers.isEmpty())
        assertFalse(core.chatFlow.value.staleNotice)
        assertTrue(core.chatFlow.value.streamingPartTexts.isEmpty())
    }

    // ── loadMessagesForEffect ─────────────────────────────────────────────────

    @Test
    fun `loadMessagesForEffect routes through launchLoadMessages and emits error on failure`() = runTest {
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns Result.failure(IllegalStateException("500"))
        coEvery { repository.getSessionTodos(any()) } returns Result.success(emptyList())
        val core = wire()
        core.writeChat { it.copy(currentSessionId = "s1") }

        core.loadMessagesForEffect("s1", resetLimit = true)
        advanceUntilIdle()

        assertFalse(core.chatFlow.value.isLoadingMessages)
        assertNotNull(core.recentTestErrors.lastOrNull())
    }

    // ── catchUpAfterDisconnectOrForeground ───────────────────────────────────

    @Test
    fun `catchUpAfterDisconnectOrForeground probes and reloads when newer exists`() = runTest {
        coEvery { repository.probeLatestMessageId(any()) } returns Result.success("server-new")
        val fetched = listOf(MessageWithParts(info = Message(id = "new1", role = "user")))
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns Result.success(MessagesPage(fetched, null))
        val core = wire()
        core.writeChat {
            it.copy(currentSessionId = "s1", messages = listOf(Message(id = "anchor", role = "user")))
        }

        core.catchUpAfterDisconnectOrForeground("s1")
        advanceUntilIdle()

        // Newer message fetched, slice updated.
        assertTrue(core.chatFlow.value.messages.any { it.id == "new1" })
        assertFalse(core.chatFlow.value.isLoadingMessages)
    }

    @Test
    fun `catchUpAfterDisconnectOrForeground fans out pending-questions catch-up across known workdirs`() = runTest {
        // Probe says nothing new → reload skipped; we just verify the workdir
        // fan-out side-effect on foregroundCatchUpController.
        coEvery { repository.probeLatestMessageId(any()) } returns Result.success("anchor")
        coEvery { repository.getPendingQuestions(any()) } returns Result.success(emptyList())
        val core = wire()
        core.writeChat {
            it.copy(currentSessionId = "s1", messages = listOf(Message(id = "anchor", role = "user")))
        }
        core.writeSessionList {
            it.copy(directorySessions = mapOf("/wA" to emptyList(), "/wB" to emptyList()))
        }
        every { settingsManager.currentWorkdir } returns "/wC"

        core.catchUpAfterDisconnectOrForeground("s1")
        advanceUntilIdle()

        // All three workdirs probed for pending questions.
        coVerify(atLeast = 1) { repository.getPendingQuestions("/wA") }
        coVerify(atLeast = 1) { repository.getPendingQuestions("/wB") }
        coVerify(atLeast = 1) { repository.getPendingQuestions("/wC") }
    }

    // ── R-20 Phase 2 复审 #2: launchCatchUp live fp provider ────────────────

    @Test
    fun `fix-2 catchUpAfterDisconnectOrForeground drops onSuccess merge when host switched during probe`() = runTest {
        // gpter 复审 #2 (glm-3 前次 #3 修复的实现错误): the previous code passed
        // currentServerGroupFp = { fp } (captured lambda) into launchCatchUp,
        // which made the onSuccess guard `currentServerGroupFp() !=
        // expectedServerGroupFp` 恒等 (no-op): both sides read the SAME
        // captured snapshot. A host switch during the probe REST was never
        // detected, and the stale fp-A tail was merged into fp-B's slice.
        // After the fix, the call passes the LIVE provider
        // (core.currentServerGroupFp) so currentServerGroupFp() reads the
        // current host's fp each call. A mid-probe host switch makes the guard
        // fire → onSuccess early-returns without merging.
        val originalProfile = cn.vectory.ocdroid.data.model.HostProfile(
            id = "host-A",
            name = "A",
            serverUrl = "http://a",
            serverGroupFp = "fp-A",
        )
        val switchedProfile = cn.vectory.ocdroid.data.model.HostProfile(
            id = "host-B",
            name = "B",
            serverUrl = "http://b",
            serverGroupFp = "fp-B",
        )
        every { hostProfileStore.currentProfile() } returns originalProfile
        coEvery { repository.probeLatestMessageId(any()) } returns Result.success("server-new")
        // The probe-page REST: simulate the host switch DURING the suspend.
        // Before the page returns, flip hostProfileStore so the live
        // core.currentServerGroupFp() provider returns fp-B.
        val tail = listOf(MessageWithParts(info = Message(id = "stale-A", role = "user")))
        coEvery { repository.getMessagesPaged(any(), any(), any()) } answers {
            every { hostProfileStore.currentProfile() } returns switchedProfile
            Result.success(MessagesPage(tail, null))
        }
        val core = wire()
        core.writeChat {
            it.copy(currentSessionId = "s1", messages = listOf(Message(id = "anchor", role = "user")))
        }

        core.catchUpAfterDisconnectOrForeground("s1")
        advanceUntilIdle()

        // The stale fp-A tail MUST NOT be merged into fp-B's slice. The anchor
        // is preserved; "stale-A" is dropped by the now-functional onSuccess
        // guard.
        assertEquals(
            "stale fp-A probe tail must NOT merge into fp-B's slice (guard must fire)",
            listOf("anchor"),
            core.chatFlow.value.messages.map { it.id },
        )
        // Loading flag cleared on the early return.
        assertFalse(core.chatFlow.value.isLoadingMessages)
    }
}
