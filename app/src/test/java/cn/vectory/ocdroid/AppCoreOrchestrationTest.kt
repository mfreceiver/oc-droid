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
import cn.vectory.ocdroid.ui.classifyCommandPostError
import cn.vectory.ocdroid.ui.computeQuestionFanOutWorkdirs
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
        // §issue-1 Fix A (branch-1 control): local session + non-blank dir MUST
        // NOT trigger a server fetch (the fetch only runs on a miss).
        coVerify(exactly = 0) { repository.getSession(any()) }
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
        // §issue-1 Fix A (branch-1 control): directorySessions hit MUST NOT fetch.
        coVerify(exactly = 0) { repository.getSession(any()) }
    }

    // §issue-1 Phase 2a Fix A: fetch-FAIL → null (NOT currentWorkdir). Repurposed
    // from the Phase 1b currentWorkdir-fallback characterization (that behavior
    // is gone — currentWorkdir is no longer a fallback in resolveQuestionDirectory).
    @Test
    fun `resolveQuestionDirectory returns null when parent session fetch fails`() = runTest {
        // Session absent locally → fetch → fetch FAILS → null. currentWorkdir is
        // set but MUST NOT be used (the old silent wrong-value bug).
        every { settingsManager.currentWorkdir } returns "/workdir-Y"
        coEvery { repository.getSession("missing") } returns Result.failure(java.io.IOException("404"))
        val question = QuestionRequest(
            id = "req1",
            sessionId = "missing",
            questions = listOf(QuestionInfo(question = "q", header = "h", options = emptyList())),
        )
        val core = wire()
        core.writeSessionList {
            it.copy(sessions = emptyList(), directorySessions = emptyMap(), pendingQuestions = listOf(question))
        }

        val dir = core.resolveQuestionDirectory("req1")
        assertNull(dir)
        coVerify(exactly = 1) { repository.getSession("missing") }
    }

    @Test
    fun `resolveQuestionDirectory returns null when nothing resolves`() = runTest {
        every { settingsManager.currentWorkdir } returns null
        val core = wire()

        val dir = core.resolveQuestionDirectory("nonexistent")
        assertNull(dir)
        // §issue-1 Fix A: no pending question → no fetch (sessionId is null).
        coVerify(exactly = 0) { repository.getSession(any()) }
    }

    // ── §issue-1 Phase 2a Fix A: resolveQuestionDirectory fetch+CAS contract ──
    //
    // Phase 1b characterized the BUG (currentWorkdir fallback → wrong value).
    // Fix A changes the contract: a local miss now GETs /session/{id}, CAS-
    // upserts the fetched session into `sessions`, and returns fetched.directory;
    // a fetch failure returns null (NOT currentWorkdir).

    // §issue-1 Phase 2a Fix A: absent locally → fetch-hit → fetched dir + CAS-cache.
    @Test
    fun `resolveQuestionDirectory fetches and CAS-caches parent session directory when session is absent from local state`() = runTest {
        // A1: pending question for session-S, but session-S is NOT in `sessions`
        // AND NOT in `directorySessions`. currentWorkdir is set to a DIFFERENT
        // value to prove it is no longer used as the fallback.
        every { settingsManager.currentWorkdir } returns "/workdir-Y"
        coEvery { repository.getSession("session-S") } returns Result.success(
            Session(id = "session-S", directory = "/real-dir"),
        )
        val question = QuestionRequest(
            id = "req1",
            sessionId = "session-S",
            questions = listOf(QuestionInfo(question = "q", header = "h", options = emptyList())),
        )
        val core = wire()
        core.writeSessionList {
            it.copy(sessions = emptyList(), directorySessions = emptyMap(), pendingQuestions = listOf(question))
        }

        val dir = core.resolveQuestionDirectory("req1")
        // Returns the FETCHED directory, not currentWorkdir.
        assertEquals("/real-dir", dir)
        coVerify(exactly = 1) { repository.getSession("session-S") }
        // CAS-upserted into sessions so a later resolve hits branch 1 (no fetch).
        val cached = core.sessionListFlow.value.sessions.firstOrNull { it.id == "session-S" }
        assertNotNull("fetched session must be CAS-cached into sessions", cached)
        assertEquals("/real-dir", cached!!.directory)
    }

    // §issue-1 Phase 2a Fix A: session local but directory blank → fetch-hit →
    // fetched dir + CAS-cache (the blank-dir session is REPLACED).
    @Test
    fun `resolveQuestionDirectory fetches real directory when parent session directory is blank`() = runTest {
        // A2: session-S IS in `sessions`, but its `directory` is blank ("").
        // The production guard `!session.directory.isNullOrBlank()` fails on ""
        // → falls through to the fetch path. Same fetch+CAS as A1.
        every { settingsManager.currentWorkdir } returns "/workdir-Y"
        coEvery { repository.getSession("session-S") } returns Result.success(
            Session(id = "session-S", directory = "/real-dir"),
        )
        val session = Session(id = "session-S", directory = "")
        val question = QuestionRequest(
            id = "req1",
            sessionId = "session-S",
            questions = listOf(QuestionInfo(question = "q", header = "h", options = emptyList())),
        )
        val core = wire()
        core.writeSessionList {
            it.copy(sessions = listOf(session), pendingQuestions = listOf(question))
        }

        val dir = core.resolveQuestionDirectory("req1")
        assertEquals("/real-dir", dir)
        coVerify(exactly = 1) { repository.getSession("session-S") }
        // CAS-upsert REPLACES the blank-dir session with the fetched real-dir one.
        val cached = core.sessionListFlow.value.sessions.firstOrNull { it.id == "session-S" }
        assertNotNull(cached)
        assertEquals("/real-dir", cached!!.directory)
        assertEquals(1, core.sessionListFlow.value.sessions.size)
    }
    // A3 (control: session in sessions with non-blank directory → returns it,
    // branch 1) is already covered by `resolveQuestionDirectory returns the
    // pending question's parent session directory` above — skipped per task.

    // §issue-1 Phase 2 gpter fix: CONDITIONAL CAS — the fetched snapshot must NOT
    // overwrite a session that a concurrent load/SSE hydrated during the suspend
    // fetch. Before this fix the upsert was unconditional and clobbered the
    // fresher entry; this test simulates the race deterministically.
    @Test
    fun `resolveQuestionDirectory does not overwrite a fresher session hydrated during the fetch`() = runTest {
        // Race simulation: getSession's answers block writes a FRESHER session
        // (dir=/fresher) into the store BEFORE returning a STALE fetched snapshot
        // (dir=/stale-fetched). The conditional CAS lambda must observe the
        // fresher entry inside writeSessionList, keep it, and return /fresher —
        // NOT /stale-fetched (which would clobber the fresher entry pre-fix).
        val question = QuestionRequest(
            id = "req1",
            sessionId = "session-S",
            questions = listOf(QuestionInfo(question = "q", header = "h", options = emptyList())),
        )
        val core = wire()
        core.writeSessionList {
            it.copy(sessions = emptyList(), directorySessions = emptyMap(), pendingQuestions = listOf(question))
        }
        coEvery { repository.getSession("session-S") } answers {
            // Simulate a concurrent hydration landing during the network wait.
            core.writeSessionList { st ->
                st.copy(sessions = listOf(Session(id = "session-S", directory = "/fresher")))
            }
            Result.success(Session(id = "session-S", directory = "/stale-fetched"))
        }

        val dir = core.resolveQuestionDirectory("req1")

        // Returns the FRESHER entry's directory, not the stale fetched snapshot.
        assertEquals("/fresher", dir)
        // The fresher entry was NOT overwritten by the stale fetch.
        val cached = core.sessionListFlow.value.sessions.firstOrNull { it.id == "session-S" }
        assertNotNull(cached)
        assertEquals("/fresher", cached!!.directory)
    }

    // §issue-1 Phase 2: non-blank lookup wins over blank duplicate (gpter round-3).
    // If `sessions` holds session-S with a BLANK directory AND `directorySessions`
    // holds the SAME id with a hydrated non-blank directory, the lookup MUST find
    // the eligible (non-blank) entry — a blank-dir duplicate must not mask it and
    // force an unnecessary fetch. Before round-3 the predicate was id-only, so
    // firstOrNull returned the blank entry (sessions iterates first) and the
    // separate post-check fell through to a fetch.
    @Test
    fun `resolveQuestionDirectory finds the non-blank duplicate and skips fetch when a blank-dir session masks a hydrated one`() = runTest {
        val session = Session(id = "session-S", directory = "") // blank, in sessions
        val hydrated = Session(id = "session-S", directory = "/hydrated") // same id, non-blank, in directorySessions
        val question = QuestionRequest(
            id = "req1",
            sessionId = "session-S",
            questions = listOf(QuestionInfo(question = "q", header = "h", options = emptyList())),
        )
        val core = wire()
        core.writeSessionList {
            it.copy(
                sessions = listOf(session),
                directorySessions = mapOf("/hydrated" to listOf(hydrated)),
                pendingQuestions = listOf(question),
            )
        }

        val dir = core.resolveQuestionDirectory("req1")

        // The eligible /hydrated entry wins — NOT the blank duplicate, and no fetch.
        assertEquals("/hydrated", dir)
        coVerify(exactly = 0) { repository.getSession(any()) }
    }

    // ── openSessionFromDeepLink ───────────────────────────────────────────────

    @Test
    fun `openSessionFromDeepLink fetches when session is not in local list`() = runTest {
        val fetched = Session(id = "deep-link-1", directory = "/x", title = "From Server")
        coEvery { repository.getSession("deep-link-1") } returns Result.success(fetched)
        val core = wire()

        core.openSessionFromDeepLink("deep-link-1")
        // §fix-flake: openSessionFromDeepLink used to wrap repository.getSession
        // in withContext(Dispatchers.IO), which escaped the StandardTestDispatcher
        // — advanceUntilIdle() could not drive the fetch, so this verify raced
        // (intermittently failed under full-suite IO-pool contention). The
        // production code no longer hops to Dispatchers.IO (Retrofit already
        // offloads the network IO), so the whole coroutine now runs on the test
        // dispatcher and a single advanceUntilIdle() deterministically completes
        // the fetch + upsert.
        advanceUntilIdle()

        // The deep-link path always issues a GET when the session is not in
        // the local list, then upserts the fetched session into sessionListFlow.
        coVerify { repository.getSession("deep-link-1") }
        // Stronger guard: the fetched session is actually materialised in the
        // local list — proves the fetch coroutine ran to completion on the test
        // dispatcher (not just that getSession was invoked). Regression guard
        // against re-introducing a dispatcher escape that would make this flake.
        val upserted = core.sessionListFlow.value.sessions.firstOrNull { it.id == "deep-link-1" }
        assertEquals(fetched, upserted)
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

    // §issue-1 Phase 2a Fix B: fan-out site (2) now INCLUDES per-fp recent_workdirs
    // (flipped green from the Phase 1b characterization that asserted their absence).
    @Test
    fun `catchUpAfterDisconnectOrForeground fans out pending-questions catch-up across known workdirs plus recent_workdirs`() = runTest {
        // Probe says nothing new → reload skipped; we just verify the workdir
        // fan-out side-effect on foregroundCatchUpController (computed via the
        // shared computeQuestionFanOutWorkdirs helper, same as site 1).
        coEvery { repository.probeLatestMessageId(any()) } returns Result.success("anchor")
        // §issue-1 Fix B: recent_workdirs (per-fp) now part of the catch-up fan-out.
        every { settingsManager.getRecentWorkdirs(any()) } returns listOf("/recent-1")
        // Capture the EXACT workdir set passed to
        // foregroundCatchUpController.catchUpPendingQuestionsAllWorkdirs (site 2).
        val queriedDirs = mutableListOf<String>()
        coEvery { repository.getPendingQuestions(any()) } answers {
            queriedDirs += firstArg<String>()
            Result.success(emptyList())
        }
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

        // §issue-1 Fix B: exact workdir SET = directorySessions.keys + currentWorkdir
        // + recent_workdirs (per-fp). A question on a recently-used-but-disconnected
        // workdir (/recent-1) is now caught up — no longer missed.
        assertEquals(
            "catch-up fan-out set must be directorySessions.keys + currentWorkdir + recent_workdirs",
            setOf("/wA", "/wB", "/wC", "/recent-1"),
            queriedDirs.toSet(),
        )
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

    // ─────────── §grouping-rewrite Round-3 N1: classifyCommandPostError ────

    @Test
    fun `classifyCommandPostError — read-timeout SocketTimeoutException is non-fatal Info`() {
        // §grouping-rewrite Round-2 D2 + Round-3 N1: a READ-side timeout
        // (POST accepted, slow ACK) is non-fatal — SSE carries the result.
        // OkHttp's exception message for this branch is "Read timed out" (or
        // similar) — NO "connect" / "failed to connect" phrase.
        val error = java.net.SocketTimeoutException("Read timed out")

        val event = classifyCommandPostError(error, cmd = "compact")

        assertTrue("read-timeout should yield UiEvent.Info, got $event", event is UiEvent.Info)
        assertEquals(
            R.string.command_submitted_processing,
            (event as UiEvent.Info).resId,
        )
    }

    @Test
    fun `classifyCommandPostError — connect-side SocketTimeoutException is fatal Error`() {
        // §grouping-rewrite Round-2 D2 + Round-3 N1: a CONNECT-side timeout
        // (server unreachable / DNS / TLS) means the POST never reached the
        // server → SSE cannot deliver → must surface as a real Error.
        // OkHttp's exception message for this branch contains "failed to
        // connect" (the case-insensitive "connect" sniff catches it).
        val error = java.net.SocketTimeoutException("failed to connect to /1.2.3.4:443")

        val event = classifyCommandPostError(error, cmd = "compact")

        assertTrue("connect-timeout should yield UiEvent.Error, got $event", event is UiEvent.Error)
        val err = event as UiEvent.Error
        assertEquals(R.string.error_command_failed, err.resId)
        // Format args: [cmd, fallbackMessage]. cmd is first.
        assertEquals("compact", err.args.first())
    }

    @Test
    fun `classifyCommandPostError — plain IOException (non-timeout) is fatal Error`() {
        // §grouping-rewrite Round-2 D2 + Round-3 N1: anything that is NOT a
        // SocketTimeoutException (HTTP 4xx/5xx, IOException, etc.) stays a
        // real Error — the read-vs-connect distinction only applies to
        // SocketTimeoutException.
        val error = java.io.IOException("boom")

        val event = classifyCommandPostError(error, cmd = "compact")

        assertTrue("non-timeout IOException should yield UiEvent.Error, got $event", event is UiEvent.Error)
        val err = event as UiEvent.Error
        assertEquals(R.string.error_command_failed, err.resId)
        assertEquals("compact", err.args.first())
        // The fallback message propagates as the 2nd format arg.
        assertTrue(
            "error message should propagate via the fallback, got args=${err.args}",
            err.args.any { it.toString().contains("boom") },
        )
    }

    @Test
    fun `classifyCommandPostError — SocketTimeoutException with null message defaults to non-fatal Info`() {
        // §grouping-rewrite Round-4 N1-r3: pin the unsafe-direction default
        // for a SocketTimeoutException with no message. The "connect" sniff
        // does `error.message?.lowercase().orEmpty()` → "" → does NOT contain
        // "connect" → falls through to the read-side path → Info (non-fatal).
        //
        // This is the unsafe-direction choice documented in
        // classifyCommandPostError's KDoc: when we cannot tell connect-side
        // from read-side apart (no message to sniff), assume read (non-fatal)
        // because SSE will surface a real failure if the POST truly never
        // reached the server. OkHttp always carries a message in practice
        // (so this is a defensive pin against future sniff changes that would
        // accidentally flip the default), but the contract is load-bearing:
        // a false-positive Error on an actually-non-fatal timeout would lie
        // to the user about a command that is still running server-side.
        val error = java.net.SocketTimeoutException(null as String?)

        val event = classifyCommandPostError(error, cmd = "compact")

        assertTrue(
            "null-message SocketTimeoutException should default to non-fatal Info, got $event",
            event is UiEvent.Info,
        )
        assertEquals(
            R.string.command_submitted_processing,
            (event as UiEvent.Info).resId,
        )
    }

    // ── §issue-1 Phase 2a Fix B / Phase 2 gate 🟡3: computeQuestionFanOutWorkdirs ──
    //
    // Pure helper (no AppCore wiring) — direct unit tests for the workdir-set
    // computation shared by BOTH pending-question fan-out sites. Pins dedup,
    // blank filtering, null-currentWorkdir, and order/distinct correctness so a
    // future refactor of the helper cannot silently drop a source.

    @Test
    fun `computeQuestionFanOutWorkdirs dedupes a workdir present in all three sources to a single entry`() {
        // /dup appears in directorySessions.keys AND currentWorkdir AND
        // recent_workdirs → appears exactly once in the output.
        val result = computeQuestionFanOutWorkdirs(
            directorySessionKeys = setOf("/dup", "/a"),
            currentWorkdir = "/dup",
            recentWorkdirs = listOf("/dup", "/b"),
        )
        assertEquals(listOf("/dup", "/a", "/b"), result)
        assertEquals("no duplicate entries", 3, result.toSet().size)
    }

    @Test
    fun `computeQuestionFanOutWorkdirs filters blank and empty entries from every source`() {
        // Blank/empty strings in any of the three inputs are excluded (the
        // server rejects blank directories; currentWorkdir can be "" before a
        // host is configured).
        val result = computeQuestionFanOutWorkdirs(
            directorySessionKeys = setOf("/ok", "", "   "),
            currentWorkdir = "",
            recentWorkdirs = listOf("/recent", "", "  "),
        )
        assertEquals(listOf("/ok", "/recent"), result)
    }

    @Test
    fun `computeQuestionFanOutWorkdirs handles null currentWorkdir without crashing`() {
        // null currentWorkdir is dropped by listOfNotNull — no NPE, no phantom
        // "null" string entry.
        val result = computeQuestionFanOutWorkdirs(
            directorySessionKeys = setOf("/a"),
            currentWorkdir = null,
            recentWorkdirs = listOf("/b"),
        )
        assertEquals(listOf("/a", "/b"), result)
    }

    @Test
    fun `computeQuestionFanOutWorkdirs returns empty list when every source is empty or blank`() {
        // Edge: nothing to fan out → loadPendingQuestionsAllWorkdirs early-returns.
        val result = computeQuestionFanOutWorkdirs(
            directorySessionKeys = emptySet(),
            currentWorkdir = null,
            recentWorkdirs = emptyList(),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `computeQuestionFanOutWorkdirs preserves first-seen order and drops later duplicates`() {
        // distinct() keeps the FIRST occurrence. Order = directorySessions.keys
        // (insertion order, LinkedHashSet) then currentWorkdir then recent_workdirs.
        // A workdir repeated across sources collapses to its earliest position.
        val result = computeQuestionFanOutWorkdirs(
            directorySessionKeys = setOf("/x"),
            currentWorkdir = "/x", // dup of a key
            recentWorkdirs = listOf("/x", "/y"), // /x dup again, /y new
        )
        assertEquals(listOf("/x", "/y"), result)
    }
}
