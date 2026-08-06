package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.AgentInfo
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.QuestionInfo
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.MessagesPage
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.AppCore
import cn.vectory.ocdroid.ui.ChatViewModel
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.ConnectionPhase
import cn.vectory.ocdroid.ui.ConnectionState
import cn.vectory.ocdroid.ui.ConnectionViewModel
import cn.vectory.ocdroid.ui.HostViewModel
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.SessionViewModel
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.catchUpAfterDisconnectOrForeground
import cn.vectory.ocdroid.ui.classifyCommandPostError
import cn.vectory.ocdroid.ui.computeQuestionFanOutWorkdirs
import cn.vectory.ocdroid.ui.loadMessagesForEffect
import cn.vectory.ocdroid.ui.materializeDraftSession
import cn.vectory.ocdroid.ui.resolveQuestionDirectory
import cn.vectory.ocdroid.ui.shouldAutoUnanchorOnColdStart
import cn.vectory.ocdroid.ui.SSE_DISCONNECT_UNANCHORED_THRESHOLD_MS
import cn.vectory.ocdroid.ui.BannerHysteresisOwner
import cn.vectory.ocdroid.ui.BannerHysteresisState
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import cn.vectory.ocdroid.ui.stampDisconnectedSince
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
import org.junit.Assert.assertNotEquals
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
        ChatViewModel(core, mockk<BannerHysteresisOwner>(relaxed = true) { every { state } returns MutableStateFlow(BannerHysteresisState()) })
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

    // ── §chat-ux-batch T7 (B2): dispatchSend resolution 3-state ───────────────
    //
    // The send path resolves agent/model per-send via the transient
    // `pending` value, falling back to transcript inference, falling back to
    // null. These three tests pin each arm of the resolution chain so a
    // future refactor cannot silently re-introduce global/cross-session
    // carry. The visible-set filter (`agents.filter { it.isVisible }`) is
    // exercised via the agents list: a hidden agent in the transcript MUST
    // NOT be inferred (T6 contract).

    @Test
    fun `dispatchSend uses pendingAgent when set, then clears pending after send`() = runTest {
        // State (a): pendingAgent set → the sent agent == pendingAgent.
        // No settingsManager.setAgentForSession / selectedAgentName reads.
        // §chat-ux-batch T7 review-fix (M1): also pins pending-MODEL-hit —
        // sent model == pendingModel (4th positional arg), so a future
        // refactor cannot regress model resolution while the agent-only
        // verify stays green.
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())
        coEvery { repository.getSession(any()) } returns Result.success(Session(id = "session-1", directory = "/x"))

        val core = wire()
        core.writeChat {
            it.copy(
                currentSessionId = "session-1",
                pendingAgent = "my-pending-agent",
                pendingModel = Message.ModelInfo("openai", "gpt-5"))
        }
        core.writeSettings { it.copy(agents = listOf(AgentInfo(name = "my-pending-agent"))) }
        core.writeSessionList { it.copy(sessions = listOf(Session(id = "session-1", directory = "/x"))) }
        core.writeComposer { it.copy(inputText = "hi") }

        core.sendMessage()
        advanceUntilIdle()

        coVerify {
            repository.sendMessage(
                eq("session-1"),
                eq("hi"),
                eq("my-pending-agent"),
                eq(Message.ModelInfo("openai", "gpt-5")),
                any())
        }
        // Pending cleared after send (transient).
        assertNull(core.chatFlow.value.pendingAgent)
        assertNull(core.chatFlow.value.pendingModel)
    }

    @Test
    fun `dispatchSend falls back to inferred agent from a visible user message when pending is null`() = runTest {
        // State (b): no pending → infer from latest visible user message's
        // `agent` field. A user message with agent="visible-bot" + that bot
        // present in the visible-agents set → inference yields "visible-bot".
        // §chat-ux-batch T7 review-fix (M1): also pins inferred-MODEL-fallback
        // — the latest assistant message's resolvedModel (agent=null → always
        // eligible) is sent on the wire when pendingModel is null.
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())
        coEvery { repository.getSession(any()) } returns Result.success(Session(id = "session-1", directory = "/x"))

        val core = wire()
        core.writeChat {
            it.copy(
                currentSessionId = "session-1",
                messages = listOf(
                    Message(id = "u1", role = "user", agent = "visible-bot"),
                    // §M1: assistant turn carrying a resolved model — the
                    // agent is null so inferCurrentModel's
                    // `agent == null || agent in visibleAgents` predicate
                    // always admits it; resolvedModel = (anthropic, claude-3).
                    Message(
                        id = "a1",
                        role = "assistant",
                        providerId = "anthropic",
                        modelId = "claude-3")))
        }
        core.writeSettings { it.copy(agents = listOf(AgentInfo(name = "visible-bot"))) }
        core.writeSessionList { it.copy(sessions = listOf(Session(id = "session-1", directory = "/x"))) }
        core.writeComposer { it.copy(inputText = "hi") }

        core.sendMessage()
        advanceUntilIdle()

        // Sent agent was inferred from the transcript (pending was null);
        // sent model was inferred from the assistant message's resolvedModel.
        coVerify {
            repository.sendMessage(
                eq("session-1"),
                eq("hi"),
                eq("visible-bot"),
                eq(Message.ModelInfo("anthropic", "claude-3")),
                any())
        }
    }

    @Test
    fun `dispatchSend skips hidden agents during inference and sends null when no visible agent matches`() = runTest {
        // State (c): no pending + no inferable → sent null. The user message
        // carries agent="compaction" (a hidden internal agent), which MUST be
        // skipped by the visible-set filter → inference yields null → sent
        // null (server applies its default).
        // §chat-ux-batch T7 review-fix (M1): also pins null-MODEL resolution
        // under hidden-agent filtering — the assistant message's agent
        // ("compaction") is NOT in visibleAgents, so inferCurrentModel skips
        // it too; sent model == null alongside the sent agent == null.
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())
        coEvery { repository.getSession(any()) } returns Result.success(Session(id = "session-1", directory = "/x"))

        val core = wire()
        core.writeChat {
            it.copy(
                currentSessionId = "session-1",
                // Hidden internal agent in the transcript — MUST be skipped
                // by BOTH inferCurrentAgent (user msg) AND inferCurrentModel
                // (assistant msg with the same hidden agent).
                messages = listOf(
                    Message(id = "u1", role = "user", agent = "compaction"),
                    Message(
                        id = "a1",
                        role = "assistant",
                        agent = "compaction",
                        providerId = "openai",
                        modelId = "gpt-4")))
        }
        // The visible catalog does NOT contain "compaction" → it is filtered out.
        core.writeSettings { it.copy(agents = listOf(AgentInfo(name = "compaction", hidden = true))) }
        core.writeSessionList { it.copy(sessions = listOf(Session(id = "session-1", directory = "/x"))) }
        core.writeComposer { it.copy(inputText = "hi") }

        core.sendMessage()
        advanceUntilIdle()

        // Sent agent == null AND sent model == null — no pending, no visible
        // inferable; server applies its default on both arms.
        coVerify {
            repository.sendMessage(
                eq("session-1"),
                eq("hi"),
                isNull(),
                isNull(),
                any())
        }
    }

    @Test
    fun `sendMessage clears fileReferences along with inputText and imageAttachments (I4)`() = runTest {
        // §1B-FIX (I4): after Send, the fileReference chip set must be
        // wiped — otherwise a chip from the just-sent prompt would
        // survive into the next message and re-inject the `File: <path>`
        // text into the user's next draft.
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())
        coEvery { repository.getSession(any()) } returns Result.success(Session(id = "session-1", directory = "/x"))

        val core = wire()
        core.writeChat { it.copy(currentSessionId = "session-1") }
        core.writeSessionList { it.copy(sessions = listOf(Session(id = "session-1", directory = "/x"))) }
        core.writeComposer {
            it.copy(
                inputText = "User text\nFile: /a/b.kt",
                fileReferences = listOf(
                    cn.vectory.ocdroid.ui.ComposerFileReference(path = "/a/b.kt")
                ))
        }

        core.sendMessage()
        advanceUntilIdle()

        // inputText + fileReferences are cleared together.
        assertEquals("", core.composerFlow.value.inputText)
        assertTrue(core.composerFlow.value.fileReferences.isEmpty())
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

    // §chat-ux-batch T8 (B3): the former test
    // `materializeDraftSession copies current model and agent to per-session storage`
    // was DELETED here. It verified the legacy per-session copy from
    // chatFlow.currentModel / settingsFlow.selectedAgentName to
    // SettingsManager.set{Model,Agent}ForSession — both that copy block and
    // the destination setters were deleted in T8 (T7 rewired both picks to
    // TRANSIENT pendingModel / pendingAgent, no persistence needed for carry).

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
            questions = listOf(QuestionInfo(question = "q", header = "h", options = emptyList())))
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
            questions = listOf(QuestionInfo(question = "q", header = "h", options = emptyList())))
        val core = wire()
        core.writeSessionList {
            it.copy(
                sessions = emptyList(),
                directorySessions = mapOf("/connected" to listOf(session)),
                pendingQuestions = listOf(question))
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
            questions = listOf(QuestionInfo(question = "q", header = "h", options = emptyList())))
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
            Session(id = "session-S", directory = "/real-dir"))
        val question = QuestionRequest(
            id = "req1",
            sessionId = "session-S",
            questions = listOf(QuestionInfo(question = "q", header = "h", options = emptyList())))
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
            Session(id = "session-S", directory = "/real-dir"))
        val session = Session(id = "session-S", directory = "")
        val question = QuestionRequest(
            id = "req1",
            sessionId = "session-S",
            questions = listOf(QuestionInfo(question = "q", header = "h", options = emptyList())))
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
            questions = listOf(QuestionInfo(question = "q", header = "h", options = emptyList())))
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
            questions = listOf(QuestionInfo(question = "q", header = "h", options = emptyList())))
        val core = wire()
        core.writeSessionList {
            it.copy(
                sessions = listOf(session),
                directorySessions = mapOf("/hydrated" to listOf(hydrated)),
                pendingQuestions = listOf(question))
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
            cn.vectory.ocdroid.data.model.HealthResponse(healthy = true, version = "1.0"))
        every { settingsManager.clearAllLocalData() } just runs
        val core = wire()
        core.writeChat {
            it.copy(
                currentSessionId = "stale",
                messages = listOf(Message(id = "m", role = "user")))
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
                staleNotice = true,
                streamingPartTexts = mapOf("p" to "x"))
        }
        val nonceBefore = core.chatFlow.value.refreshNonce

        core.performGlobalColdStartRefresh("s1")
        advanceUntilIdle()

        assertEquals(nonceBefore + 1, core.chatFlow.value.refreshNonce)
        assertFalse(core.chatFlow.value.staleNotice)
        assertTrue(core.chatFlow.value.streamingPartTexts.isEmpty())
    }

    // ── §sse-rest-fallback (force-refresh REST 兜底) ───────────────────────────

    @Test
    fun `force-refresh clears the window and re-fetches UNANCHORED so it stays non-empty`() = runTest {
        // §sse-rest-fallback: the explicit user force-refresh (ChatTopBar "Force
        // refresh" → performForceRefresh → performGlobalColdStartRefresh) must
        // clear the chat slice (ColdStartChatReset) then re-fetch UNANCHORED
        // (getMessagesPagedUnanchored → since=0L) so a stale slim watermark
        // after an SSE outage cannot make the anchored /since return an empty
        // delta and leave the JUST-CLEARED window empty (clear + empty fetch =
        // worse-than-status-quo regression). Locks in: clear + forceInitialWindow
        // = true ⇒ non-empty + the unanchored fetch path.
        //
        // Driven via performGlobalColdStartRefresh(forceInitialWindow=true) —
        // the shared ①②③ primitive performForceRefresh delegates to — so the
        // assertion is NOT polluted by performForceRefresh's step ④ (testConnection)
        // / ⑤ (LoadSessions) side-effects. The unanchored fetch is ALSO the
        // precondition for bumpSlimBookmarkFromItems to advance the slim
        // watermark; the bump itself lives inside the real repo's slim branch
        // (getMessagesPagedImpl → MessageSource) and is verified by code reading
        // + repository-level tests, not observable through this mock.
        val stale = Message(id = "stale", role = "user")
        val fresh = MessageWithParts(info = Message(id = "m_fresh", role = "assistant"))
        // 3-arg signature (the old SlimCommitToken param was removed in B3 Phase 4b).
        coEvery { repository.getMessagesPagedUnanchored(any(), any(), any()) } returns
            Result.success(MessagesPage(listOf(fresh), null))
        coEvery { repository.getSessionTodos(any()) } returns Result.success(emptyList())
        val core = wire()
        core.writeChat {
            it.copy(
                currentSessionId = "s1",
                messages = listOf(stale),
                staleNotice = true,
                streamingPartTexts = mapOf("p" to "x"))
        }
        val nonceBefore = core.chatFlow.value.refreshNonce

        // explicit=true + forceInitialWindow=true = the force-refresh reset.
        core.performGlobalColdStartRefresh("s1", forceInitialWindow = true, explicit = true)
        advanceUntilIdle()

        // Step ③ verified: the UNANCHORED fetch path was used (the precondition
        // for bumpSlimBookmarkFromItems to advance the slim watermark).
        coVerify(atLeast = 1) { repository.getMessagesPagedUnanchored("s1", any(), any()) }
        // Cleared window re-populated with the fresh fetch (non-empty).
        val ids = core.chatFlow.value.messages.map { it.id }
        assertFalse("stale message wiped by ColdStartChatReset (got $ids)", ids.contains("stale"))
        assertTrue("fresh message loaded via unanchored fetch (got $ids)", ids.contains("m_fresh"))
        // Step ② verified: clear signal fired (refreshNonce bumped) + stale cleared.
        assertEquals(nonceBefore + 1, core.chatFlow.value.refreshNonce)
        assertFalse(core.chatFlow.value.staleNotice)
    }

    @Test
    fun `performForceRefresh passes retries=3 so the probe survives transient network jitter`() = runTest {
        // rev-ogpt MINOR 8 wiring guard: performForceRefresh (ChatTopBar "Force
        // refresh") must pass retries=3 to the health probe. A single-shot
        // probe (retries=0) fails under transient network jitter (DNS hiccup /
        // brief connection drop) and the force refresh cannot recover the
        // banner. Lock in retries=3 by asserting the probe is attempted 4 times
        // (1 + 3 retries) when the server stays unhealthy. A regression back to
        // retries=0 collapses this to a single attempt.
        coEvery { repository.checkHealth() } returns Result.success(
            cn.vectory.ocdroid.data.model.HealthResponse(healthy = false, version = "1.0"))
        coEvery { repository.getMessagesPagedUnanchored(any(), any(), any()) } returns
            Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getSessionTodos(any()) } returns Result.success(emptyList())
        val core = wire()
        core.writeChat { it.copy(currentSessionId = "s1") }

        core.performForceRefresh("s1")
        advanceUntilIdle()

        // retries=3 ⇒ 1 initial + 3 retries = 4 probes. A regression back to
        // retries=0 (the pre-fix default) would be exactly 1.
        coVerify(exactly = 4) { repository.checkHealth() }
    }

    @Test
    fun `explicit force-refresh surfaces feedback instead of silently swallowing when a load is in flight`() = runTest {
        // §force-refresh-guard: a user-triggered force-refresh must NOT be
        // silently swallowed when a load is already in flight. ColdStartChatReset
        // does NOT clear isLoadingMessages, so bypassing the guard would wipe the
        // chat slice while launchLoadMessages' own coalescing guard skips the
        // refill → an empty window with no fresh fetch. Instead an Info feedback
        // event is posted and the slice is left untouched; the in-flight load (or
        // a repeated tap once it settles) delivers fresh data.
        val core = wire()
        core.writeChat {
            it.copy(
                currentSessionId = "s1",
                isLoadingMessages = true,
                messages = listOf(Message(id = "keep", role = "user")))
        }

        // explicit=true = force-refresh path (the automatic cold-start keeps the
        // silent no-op). Tested via performGlobalColdStartRefresh directly —
        // performForceRefresh delegates ①②③ to it.
        core.performGlobalColdStartRefresh("s1", explicit = true)
        advanceUntilIdle()

        // Guard short-circuited: no fetch, no slice wipe. (4-arg signature
        // matches the default-token-param stub style in setUp.)
        coVerify(exactly = 0) { repository.getMessagesPagedUnanchored(any(), any(), any()) }
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
        assertTrue(
            "messages preserved (no ColdStartChatReset wipe while loading)",
            core.chatFlow.value.messages.any { it.id == "keep" })
    }

    // ── §sse-auto-unanchor (TODO 3 — real SSE-outage self-heal) ────────────────

    @Test
    fun `shouldAutoUnanchorOnColdStart is true only when Disconnected past the threshold`() {
        // Pure predicate — exhaustive branch coverage with a controlled clock.
        val threshold = 90_000L
        // Healthy phases never trigger (no white-flash on every cold-start).
        assertFalse(shouldAutoUnanchorOnColdStart(ConnectionPhase.Connected, null, 0L, threshold))
        assertFalse(shouldAutoUnanchorOnColdStart(ConnectionPhase.Connecting, null, 0L, threshold))
        assertFalse(shouldAutoUnanchorOnColdStart(ConnectionPhase.Reconnecting, null, 0L, threshold))
        assertFalse(shouldAutoUnanchorOnColdStart(ConnectionPhase.Idle, null, 0L, threshold))
        // Disconnected but NO timestamp (defensive) → no trigger.
        assertFalse(shouldAutoUnanchorOnColdStart(ConnectionPhase.Disconnected, null, 0L, threshold))
        // Disconnected but FRESH (< threshold) → no trigger (transient blip).
        assertFalse(shouldAutoUnanchorOnColdStart(ConnectionPhase.Disconnected, 0L, threshold - 1, threshold))
        // Boundary is INCLUSIVE (>=): exactly at the threshold → trigger.
        assertTrue(shouldAutoUnanchorOnColdStart(ConnectionPhase.Disconnected, 0L, threshold, threshold))
        // Disconnected past the threshold → trigger (real outage self-heal).
        assertTrue(shouldAutoUnanchorOnColdStart(ConnectionPhase.Disconnected, 0L, threshold + 1, threshold))
        assertTrue(shouldAutoUnanchorOnColdStart(ConnectionPhase.Disconnected, 0L, threshold * 5, threshold))
    }

    @Test
    fun `stampDisconnectedSince stamps on entry to Disconnected and clears on exit`() {
        // Pure phase-transition stamper — exhaustive transitions.
        val now = 1_000_000L
        val connected = ConnectionState(connectionPhase = ConnectionPhase.Connected)
        val disconnected = ConnectionState(connectionPhase = ConnectionPhase.Disconnected)
        // Entry: Idle/Connected → Disconnected stamps now.
        assertEquals(
            now,
            stampDisconnectedSince(connected, disconnected, now).disconnectedSince)
        // Entry respects an explicit non-null stamp (tests / replay simulate old disconnect).
        val oldStamp = now - 999_999L
        assertEquals(
            oldStamp,
            stampDisconnectedSince(connected, disconnected.copy(disconnectedSince = oldStamp), now).disconnectedSince)
        // Exit: Disconnected → Connected clears the stamp.
        assertNull(
            stampDisconnectedSince(disconnected.copy(disconnectedSince = oldStamp), connected, now).disconnectedSince)
        // Staying Disconnected → no change (idempotent; keeps prior stamp).
        assertEquals(
            oldStamp,
            stampDisconnectedSince(
                disconnected.copy(disconnectedSince = oldStamp),
                disconnected.copy(disconnectedSince = oldStamp),
                now).disconnectedSince)
        // Staying Connected (never disconnected) → no spurious stamp.
        assertNull(stampDisconnectedSince(connected, connected, now).disconnectedSince)
    }

    @Test
    fun `automatic cold-start upgrades to UNANCHORED when SSE has been disconnected past the threshold`() = runTest {
        // §sse-auto-unanchor (TODO 3): a real sustained SSE outage self-heals —
        // the AUTOMATIC cold-start (performGlobalColdStartRefresh with defaults,
        // e.g. the GlobalColdStartRefresh effect on a long foreground absence)
        // upgrades to clear+UNANCHORED so a stale slim watermark cannot return
        // an empty delta. No manual refresh needed.
        coEvery { repository.getMessagesPagedUnanchored(any(), any(), any()) } returns
            Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getSessionTodos(any()) } returns Result.success(emptyList())
        val core = wire()
        core.writeChat { it.copy(currentSessionId = "s1") }
        // Simulate a sustained outage: Disconnected, stamped past the threshold.
        val now = System.currentTimeMillis()
        core.writeConnection {
            it.copy(
                connectionPhase = ConnectionPhase.Disconnected,
                disconnectedSince = now - SSE_DISCONNECT_UNANCHORED_THRESHOLD_MS - 1_000L)
        }

        // Defaults = automatic cold-start path (NOT explicit force-refresh).
        core.performGlobalColdStartRefresh("s1")
        advanceUntilIdle()

        // Upgraded to UNANCHORED (the self-heal), NOT the anchored /since path.
        coVerify(atLeast = 1) { repository.getMessagesPagedUnanchored("s1", any(), any()) }
    }

    @Test
    fun `automatic cold-start keeps the ANCHORED path when SSE is healthy or freshly disconnected`() = runTest {
        // §sse-auto-unanchor (TODO 3): a healthy SSE (or a fresh blip < threshold)
        // must NOT degenerate the cold-start into a clear — the cheap anchored
        // catch-up / three-way merge is preserved.
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns
            Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getMessagesPagedUnanchored(any(), any(), any()) } returns
            Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getSessionTodos(any()) } returns Result.success(emptyList())
        val core = wire()
        core.writeChat { it.copy(currentSessionId = "s1") }

        // (a) Healthy: default ConnectionState (Idle, disconnectedSince=null).
        core.performGlobalColdStartRefresh("s1")
        advanceUntilIdle()
        coVerify(atLeast = 1) { repository.getMessagesPaged("s1", any(), any()) }
        coVerify(exactly = 0) { repository.getMessagesPagedUnanchored(any(), any(), any()) }
    }

    @Test
    fun `automatic cold-start keeps ANCHORED when SSE freshly disconnected under the threshold`() = runTest {
        // Companion to the above: a FRESH disconnect (< threshold) is treated as
        // a transient blip → anchored path (no clear), matching the throttle vs
        // real-outage split in [SSE_DISCONNECT_UNANCHORED_THRESHOLD_MS].
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns
            Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getMessagesPagedUnanchored(any(), any(), any()) } returns
            Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getSessionTodos(any()) } returns Result.success(emptyList())
        val core = wire()
        core.writeChat { it.copy(currentSessionId = "s1") }
        val now = System.currentTimeMillis()
        core.writeConnection {
            it.copy(
                connectionPhase = ConnectionPhase.Disconnected,
                // Fresh: only 1s ago (well under the 90s threshold).
                disconnectedSince = now - 1_000L)
        }

        core.performGlobalColdStartRefresh("s1")
        advanceUntilIdle()

        coVerify(atLeast = 1) { repository.getMessagesPaged("s1", any(), any()) }
        coVerify(exactly = 0) { repository.getMessagesPagedUnanchored(any(), any(), any()) }
    }

    // ── loadMessagesForEffect ─────────────────────────────────────────────────

    @Test
    fun `loadMessagesForEffect routes through launchLoadMessages and emits error on failure`() = runTest {
        // §11.1 fix-9 P0-7: loadMessagesForEffect now wires the SSE liveness
        // predicate to store.slices.sseConnected. To preserve the original
        // test intent (first-fetch failure → UiEvent.Error), we explicitly
        // mark SSE as LIVE so the P0-7 retry does NOT engage (otherwise the
        // retry would call getMessagesPagedUnanchored which the relaxed mock
        // returns success for, suppressing the error). The P0-7 retry path
        // is covered by MessageActionsTest directly.
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns Result.failure(IllegalStateException("500"))
        coEvery { repository.getSessionTodos(any()) } returns Result.success(emptyList())
        val core = wire()
        // Mark SSE as live to disable the P0-7 retry (we want to test the
        // pure first-fetch failure → error emission path here).
        core.store.mutateSseConnected(value = true, generation = 1L)
        core.writeChat { it.copy(currentSessionId = "s1") }

        core.loadMessagesForEffect("s1", resetLimit = true)
        advanceUntilIdle()

        assertFalse(core.chatFlow.value.isLoadingMessages)
        assertNotNull(core.recentTestErrors.lastOrNull())
    }

    // ── catchUpAfterDisconnectOrForeground ───────────────────────────────────

    @Test
    fun `catchUpAfterDisconnectOrForeground probes and reloads when newer exists`() = runTest {
        coEvery { repository.probeLatestMessageIdForCurrent(any()) } returns
            cn.vectory.ocdroid.data.repository.ProbeResult(ok = true, messageID = "server-new", updatedAt = 200L)
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

    // §slimapi-questions: slim path — single getSlimapiQuestions() call
    // replaces the per-workdir fan-out. Verify the call fires once.
    @Test
    fun `catchUpAfterDisconnectOrForeground fires a single global pending-questions catch-up call`() = runTest {
        // §slimapi-questions: the workdir set no longer determines the fetch — one
        // getSlimapiQuestions() returns ALL questions.
        every { repository.supportsSlimQuestions } returns true
        coEvery { repository.probeLatestMessageIdForCurrent(any()) } returns
            cn.vectory.ocdroid.data.repository.ProbeResult(ok = true, messageID = "anchor", updatedAt = 100L)
        coEvery { repository.getSlimapiQuestions(any()) } returns Result.success(
            cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Success(
                items = emptyList(),
                authoritativeDirectories = null,
                serverScope = null,
            ),
        )
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

        // Exactly ONE slimapi call — no per-dir fan-out.
        coVerify(exactly = 1) { repository.getSlimapiQuestions(any()) }
        coVerify(exactly = 0) { repository.getPendingQuestions(null) }
        coVerify(exactly = 0) { repository.getPendingQuestions("/wA") }
    }

    // ── R-20 Phase 2 复审 #2: launchCatchUp live fp provider ────────────────

    @Test
    fun `fix-2 catchUpAfterDisconnectOrForeground drops onSuccess merge when host switched during probe`() = runTest {
        // gpter 复审 #2 (glm-3 前次 #3 修复的实现错误): the previous code passed
        // currentProfileId = { fp } (captured lambda) into launchCatchUp,
        // which made the onSuccess guard `currentProfileId() !=
        // expectedProfileId` 恒等 (no-op): both sides read the SAME
        // captured snapshot. A host switch during the probe REST was never
        // detected, and the stale fp-A tail was merged into fp-B's slice.
        // After the fix, the call passes the LIVE provider
        // (core.currentProfileId) so currentProfileId() reads the
        // current host's fp each call. A mid-probe host switch makes the guard
        // fire → onSuccess early-returns without merging.
        val originalProfile = cn.vectory.ocdroid.data.model.HostProfile(
            id = "host-A",
            name = "A",
            serverUrl = "http://a")
        val switchedProfile = cn.vectory.ocdroid.data.model.HostProfile(
            id = "host-B",
            name = "B",
            serverUrl = "http://b")
        every { hostProfileStore.currentProfile() } returns originalProfile
        coEvery { repository.probeLatestMessageIdForCurrent(any()) } returns
            cn.vectory.ocdroid.data.repository.ProbeResult(ok = true, messageID = "server-new", updatedAt = 200L)
        // The probe-page REST: simulate the host switch DURING the suspend.
        // Before the page returns, flip hostProfileStore so the live
        // core.currentProfileId() provider returns fp-B.
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
            core.chatFlow.value.messages.map { it.id })
        // Loading flag cleared on the early return.
        assertFalse(core.chatFlow.value.isLoadingMessages)
    }

    // ── §P0-3: SSE-liveness wiring (catchUpAfterDisconnectOrForeground) ─────
    //
    // REST-health [ConnectionState.isConnected] is a SEPARATE axis from real
    // SSE transport liveness ([StoreState.isSseConnected]). During a transient
    // SSE outage (inter-retry gap), isConnected can still read true (no health
    // failure yet — the REST baseline is committed) while isSseConnected is
    // false (no live frame has proven delivery). The catch-up coverage gate
    // must gate on the SSE-liveness axis, NOT the REST-health axis: otherwise
    // the gate short-circuits the REST probe based on a feed that is NOT
    // actually delivering, and updates that arrived during the outage are
    // silently missed.
    @Test
    fun `P0-3 catchUpAfterDisconnectOrForeground probes when isConnected=true but SSE transport is down`() = runTest {
        // Probe + page stubs so a fired probe can complete. We assert the
        // probe WAS fired (coVerify), not the merge result.
        coEvery { repository.probeLatestMessageIdForCurrent(any()) } returns
            cn.vectory.ocdroid.data.repository.ProbeResult(ok = true, messageID = "server-new", updatedAt = 200L)
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns Result.success(MessagesPage(emptyList(), null))
        val core = wire()
        core.writeChat {
            it.copy(currentSessionId = "s1", messages = listOf(Message(id = "anchor", role = "user")))
        }
        every { settingsManager.currentWorkdir } returns "/repo"
        // Mark s1 cold-snapshotted so the coverage gate's ONLY remaining
        // discriminator is the SSE-liveness axis (sseCurrentWorkdir). With the
        // session snapshotted, a live SSE feed for "/repo" would short-circuit
        // the probe; the test asserts it does NOT when SSE is actually down.
        core.sessionSyncCoordinator.markSessionColdSnapshotted("s1")

        // REST health: connected (green dot). SSE transport: DOWN (inter-retry
        // gap — a frame has not proven delivery). These two are intentionally
        // divergent — the exact window the bug hides in.
        core.writeConnection { it.copy(isConnected = true) }
        assertTrue("precondition: REST health isConnected must be true", core.store.connectionFlow.value.isConnected)
        core.store.mutateSseConnected(value = false, generation = 1L)
        assertFalse("precondition: SSE transport isSseConnected must be false", core.store.sseConnectedFlow.value)

        core.catchUpAfterDisconnectOrForeground("s1")
        advanceUntilIdle()

        // The REST probe MUST fire: the SSE feed is NOT actually delivering,
        // so the coverage gate must NOT short-circuit even though isConnected=true.
        coVerify(exactly = 1) { repository.probeLatestMessageIdForCurrent("s1") }
    }

    @Test
    fun `P0-3 catchUpAfterDisconnectOrForeground short-circuits when SSE transport is live and workdir matches`() = runTest {
        // Complementary positive case to the RED test above: when the SSE feed IS
        // transport-live (isSseConnected=true) AND attached to the current workdir
        // AND the session has a cold-snapshot baseline, the coverage gate MUST
        // short-circuit — no REST probe. This pins the non-regression direction:
        // gating on isSseConnected must not over-fire and skip probes that the
        // legacy isConnected gate would correctly elide.
        coEvery { repository.probeLatestMessageIdForCurrent(any()) } returns
            cn.vectory.ocdroid.data.repository.ProbeResult(ok = true, messageID = "server-new", updatedAt = 200L)
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns Result.success(MessagesPage(emptyList(), null))
        val core = wire()
        core.writeChat {
            it.copy(currentSessionId = "s1", messages = listOf(Message(id = "anchor", role = "user")))
        }
        every { settingsManager.currentWorkdir } returns "/repo"
        core.sessionSyncCoordinator.markSessionColdSnapshotted("s1")

        // SSE transport is LIVE (isSseConnected=true) and the feed is attached to /repo.
        core.store.mutateSseConnected(value = true, generation = 1L)
        assertTrue("precondition: SSE transport isSseConnected must be true", core.store.sseConnectedFlow.value)

        core.catchUpAfterDisconnectOrForeground("s1")
        advanceUntilIdle()

        // The SSE feed is live for this workdir AND the session is cold-snapshotted
        // → the coverage gate short-circuits → NO REST probe.
        coVerify(exactly = 0) { repository.probeLatestMessageIdForCurrent(any()) }
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
            (event as UiEvent.Info).resId)
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
            err.args.any { it.toString().contains("boom") })
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
            event is UiEvent.Info)
        assertEquals(
            R.string.command_submitted_processing,
            (event as UiEvent.Info).resId)
    }


    // ── §unified-nav A5: materialized-session route adoption ordering ──────────

    /**
     * §unified-nav A5.3/A5.4 (item 10-A): the FIRST text-send in a freshly-
     * created draft session MUST adopt the route SYNCHRONOUSLY inside the
     * materialize CAS — BEFORE the POST. This test captures the store state at
     * the moment [repository.sendMessage] is invoked (via an answers block) and
     * asserts the 4-way CAS (nav.lastRoute + navEpoch + currentSessionId + token
     * + content envelope) was committed atomically before the POST left the
     * device. Also verifies the captured payload's text/attachments reached the
     * wire (not wiped) and a pre-filled effect bus does not interfere.
     */
    @Test
    fun `first text send in draft adopts route BEFORE the POST via the aggregate CAS`() = runTest {
        val created = Session(id = "ses_new", directory = "/proj")
        coEvery { repository.createSession(title = null, directory = any()) } returns Result.success(created)
        coEvery { repository.getMessagesPagedUnanchored(any(), any(), any()) } returns Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getSessionTodos(any()) } returns Result.success(emptyList())
        coEvery { repository.getSession(any()) } returns Result.success(created)

        // Capture the store state AT the moment repository.sendMessage is called.
        // Use a nullable holder so the answers block (set up before wire()) can
        // reference the wired core without resolving to the uninitialized field.
        var coreRef: cn.vectory.ocdroid.ui.AppCore? = null
        var capturedNavEpoch: Long = -1
        var capturedLastRoute: String? = null
        var capturedCurrentSid: String? = null
        var capturedToken: Long = -1
        var capturedContentSid: String? = null
        var capturedContentRouteInstance: Long = -1
        var sentText: String? = null
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } answers {
            val c = coreRef!!
            val nav = c.store.navFlow.value
            val chat = c.store.chatFlow.value
            capturedNavEpoch = nav.navEpoch
            capturedLastRoute = nav.lastRoute
            capturedCurrentSid = chat.currentSessionId
            capturedToken = c.store.stateFlow.value.chatRouteInstance
            capturedContentSid = chat.content?.sessionId
            capturedContentRouteInstance = chat.content?.routeInstance ?: -1
            sentText = secondArg()
            Result.success(Unit)
        }

        val core = wire().also { coreRef = it }
        val oldEpoch = core.store.navFlow.value.navEpoch
        val oldToken = core.store.stateFlow.value.chatRouteInstance
        core.writeComposer { it.copy(inputText = "hello draft", draftWorkdir = "/proj") }

        core.sendMessage()
        advanceUntilIdle()

        // BEFORE the POST, the CAS committed the route/nav/token/content.
        assertEquals("chat/ses_new", capturedLastRoute)
        assertEquals(oldEpoch + 1L, capturedNavEpoch)
        assertEquals("ses_new", capturedCurrentSid)
        assertEquals(oldToken + 1L, capturedToken)
        assertEquals("ses_new", capturedContentSid)
        assertEquals(capturedToken, capturedContentRouteInstance)
        // routeInstanceFor("ses_new") returns the token (route id + currentSessionId match).
        assertEquals(capturedToken, core.store.slices.routeInstanceFor("ses_new"))
        // Captured payload's text reached the wire (not wiped).
        assertEquals("hello draft", sentText)
    }

    /**
     * §unified-nav A5.4 (item 10-A): an SSE message.updated dispatched during
     * the initial-GET window (after the CAS, before the GET completes) lands in
     * BOTH flat messages AND content.messages keyed by the CAS token. The empty
     * initial-GET that follows does NOT wipe it (the SSE message survives).
     */
    @Test
    fun `SSE message updated during initial GET lands in flat and content keyed by CAS token`() = runTest {
        val created = Session(id = "ses_new", directory = "/proj")
        coEvery { repository.createSession(title = null, directory = any()) } returns Result.success(created)
        coEvery { repository.getSession(any()) } returns Result.success(created)

        var coreRef: cn.vectory.ocdroid.ui.AppCore? = null
        val userMsg = Message(id = "m1", role = "user")
        // The unanchored GET returns empty; we inject the SSE message DURING
        // the GET (inside the answers block, before returning the empty page).
        coEvery { repository.getMessagesPagedUnanchored(any(), any(), any()) } answers {
            val c = coreRef!!
            // Inject an SSE message.updated with the CAS token. The token is
            // chatRouteInstance at this point (the CAS already committed).
            val token = c.store.stateFlow.value.chatRouteInstance
            c.store.dispatch(
                AppAction.MessageUpdatedApplied(
                    message = userMsg,
                    expectedRouteInstance = token,
                    sessionId = "ses_new",
                )
            )
            Result.success(MessagesPage(emptyList(), null))
        }
        coEvery { repository.getSessionTodos(any()) } returns Result.success(emptyList())
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)

        val core = wire().also { coreRef = it }
        core.writeComposer { it.copy(inputText = "hi", draftWorkdir = "/proj") }
        core.sendMessage()
        advanceUntilIdle()

        val chat = core.store.chatFlow.value
        val token = core.store.stateFlow.value.chatRouteInstance
        // Flat messages contain the injected message.
        assertTrue("flat messages contain the SSE message", chat.messages.any { it.id == "m1" })
        // Content messages also contain it (synced via withRouteContentSynced).
        assertEquals("ses_new", chat.content?.sessionId)
        assertEquals(token, chat.content?.routeInstance)
        assertTrue("content messages contain the SSE message", chat.content?.messages?.any { it.id == "m1" } == true)
        // The empty initial-GET did NOT wipe the content (routeInstance unchanged).
        assertEquals(token, chat.content?.routeInstance)
    }

    /**
     * §unified-nav A5.3: adoption failure (user navigated away mid-create) is
     * list-only — the session is upserted + pendingCreate tracked, but NO
     * route/nav/content/currentSessionId transition. The captured payload is
     * still POSTed as a background send.
     */
    @Test
    fun `adoption failure when user navigated away is list-only with background send`() = runTest {
        val created = Session(id = "ses_new", directory = "/proj")
        coEvery { repository.createSession(title = null, directory = any()) } returns Result.success(created)
        coEvery { repository.getMessagesPagedUnanchored(any(), any(), any()) } returns Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getSessionTodos(any()) } returns Result.success(emptyList())
        coEvery { repository.getSession(any()) } returns Result.success(created)
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)

        val core = wire()
        core.writeComposer { it.copy(inputText = "hi", draftWorkdir = "/proj") }

        // Simulate the user navigating away DURING createSession: the answers
        // block bumps navEpoch (a route transition) BEFORE returning, so
        // stillOwnsDraftSurface fails.
        coEvery { repository.createSession(title = null, directory = any()) } answers {
            core.store.mutateNav { it.copy(navEpoch = it.navEpoch + 1L) }
            Result.success(created)
        }

        core.sendMessage()
        advanceUntilIdle()

        // List-only: session upserted + pendingCreate tracked.
        assertTrue("session upserted", core.store.sessionListFlow.value.sessions.any { it.id == "ses_new" })
        assertTrue("pendingCreate tracked", core.store.sessionListFlow.value.pendingCreateIds.contains("ses_new"))
        // NO route/nav/content transition.
        assertNotEquals("chat/ses_new", core.store.navFlow.value.lastRoute)
        assertNull("currentSessionId NOT set (list-only)", core.store.chatFlow.value.currentSessionId)
        assertNull("content NOT set (list-only)", core.store.chatFlow.value.content)
        // Background send still fired.
        coVerify { repository.sendMessage("ses_new", any(), any(), any(), any()) }
    }

    // ── §unified-nav B (item 10-B): title retry loop ────────────────────────────

    @Test
    fun `title retry stops on first non-blank title and merges title into sessions and directorySessions`() = runTest {
        val created = Session(id = "ses_new", directory = "/proj")
        val titled = created.copy(title = "Generated Title")
        coEvery { repository.createSession(title = null, directory = any()) } returns Result.success(created)
        coEvery { repository.getMessagesPagedUnanchored(any(), any(), any()) } returns Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getSessionTodos(any()) } returns Result.success(emptyList())
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        // First 2 attempts: null/blank title. Third: non-blank.
        var attempt = 0
        coEvery { repository.getSession("ses_new") } answers {
            attempt++
            Result.success(when (attempt) {
                1 -> created // title null
                2 -> created.copy(title = "  ") // blank
                else -> titled // non-blank
            })
        }

        val core = wire()
        // Pre-populate directorySessions so we can verify the title merges there too.
        core.writeSessionList {
            it.copy(directorySessions = mapOf("/proj" to listOf(created)))
        }
        core.writeComposer { it.copy(inputText = "hi", draftWorkdir = "/proj") }
        core.sendMessage()
        advanceUntilIdle()

        val st = core.store.sessionListFlow.value
        // Title merged into sessions list.
        val inSessions = st.sessions.firstOrNull { it.id == "ses_new" }
        assertEquals("Generated Title", inSessions?.title)
        // Title merged into directorySessions.
        val inDir = st.directorySessions["/proj"]?.firstOrNull { it.id == "ses_new" }
        assertEquals("Generated Title", inDir?.title)
        // Stopped after 3 attempts (not all 6).
        assertEquals(3, attempt)
    }

    @Test
    fun `title retry stops at deadline when all attempts return null title`() = runTest {
        val created = Session(id = "ses_new", directory = "/proj")
        coEvery { repository.createSession(title = null, directory = any()) } returns Result.success(created)
        coEvery { repository.getMessagesPagedUnanchored(any(), any(), any()) } returns Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getSessionTodos(any()) } returns Result.success(emptyList())
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        var attempt = 0
        coEvery { repository.getSession("ses_new") } answers {
            attempt++
            Result.success(created) // always null title
        }

        val core = wire()
        core.writeComposer { it.copy(inputText = "hi", draftWorkdir = "/proj") }
        core.sendMessage()
        advanceUntilIdle()

        // Exhausted all 6 attempts.
        assertEquals(6, attempt)
        // Title still null (never wrote back null).
        val inSessions = core.store.sessionListFlow.value.sessions.firstOrNull { it.id == "ses_new" }
        assertNull(inSessions?.title)
    }

    @Test
    fun `title retry does NOT overwrite a newer title with a null blank response`() = runTest {
        // The CAS upserts the createSession result (title="Initial") into the
        // store. The retry's getSession returns null title. The retry MUST NOT
        // overwrite the "Initial" title with null/blank.
        val initial = Session(id = "ses_new", directory = "/proj", title = "Initial Title")
        coEvery { repository.createSession(title = null, directory = any()) } returns Result.success(initial)
        coEvery { repository.getMessagesPagedUnanchored(any(), any(), any()) } returns Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getSessionTodos(any()) } returns Result.success(emptyList())
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        // getSession returns a null-title copy every time (never a non-blank).
        coEvery { repository.getSession("ses_new") } returns Result.success(initial.copy(title = null))

        val core = wire()
        core.writeComposer { it.copy(inputText = "hi", draftWorkdir = "/proj") }
        core.sendMessage()
        advanceUntilIdle()

        // The "Initial Title" survived (null/blank retry never overwrote it).
        val inSessions = core.store.sessionListFlow.value.sessions.firstOrNull { it.id == "ses_new" }
        assertEquals("Initial Title", inSessions?.title)
    }

    @Test
    fun `title retry retries on REST failure then succeeds`() = runTest {
        val created = Session(id = "ses_new", directory = "/proj")
        val titled = created.copy(title = "Final Title")
        coEvery { repository.createSession(title = null, directory = any()) } returns Result.success(created)
        coEvery { repository.getMessagesPagedUnanchored(any(), any(), any()) } returns Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getSessionTodos(any()) } returns Result.success(emptyList())
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        var attempt = 0
        coEvery { repository.getSession("ses_new") } answers {
            attempt++
            if (attempt == 1) Result.failure(java.io.IOException("timeout"))
            else Result.success(titled)
        }

        val core = wire()
        core.writeComposer { it.copy(inputText = "hi", draftWorkdir = "/proj") }
        core.sendMessage()
        advanceUntilIdle()

        // Retried after the failure, then succeeded on attempt 2.
        assertEquals(2, attempt)
        val inSessions = core.store.sessionListFlow.value.sessions.firstOrNull { it.id == "ses_new" }
        assertEquals("Final Title", inSessions?.title)
    }

    // ── §blocker1: create-during-edit preservation ──────────────────────────────

    /**
     * §blocker1: createSession is a SUSPEND boundary; the user can keep typing
     * during the await. On success, the captured payload A is POSTed (correct —
     * click-time intent), but the composer's NEWER text B must SURVIVE (compare-
     * and-clear: only clear if current == captured). Pre-fix, dispatchCapturedSend
     * UNCONDITIONALLY wiped inputText → B was lost.
     */
    @Test
    fun `create-during-edit preserves newer composer text while POSTing the captured payload`() = runTest {
        val created = Session(id = "ses_new", directory = "/proj")
        coEvery { repository.getMessagesPagedUnanchored(any(), any(), any()) } returns Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getSessionTodos(any()) } returns Result.success(emptyList())
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSession(any()) } returns Result.success(created)

        val core = wire()
        // Set up createSession to simulate the user typing MORE during the
        // suspended await. `core` (local) is in scope here.
        coEvery { repository.createSession(title = null, directory = any()) } answers {
            core.writeComposer { it.copy(inputText = "A then B") }
            Result.success(created)
        }
        core.writeComposer { it.copy(inputText = "A", draftWorkdir = "/proj") }
        core.sendMessage()
        advanceUntilIdle()

        // The CAPTURED payload "A" was POSTed (not "A then B").
        coVerify { repository.sendMessage("ses_new", eq("A"), any(), any(), any()) }
        // The composer STILL holds the user's newer text "A then B" (compare-and-
        // clear preserved it — not wiped).
        assertEquals("A then B", core.composerFlow.value.inputText)
    }

    // ── §blocker1: command create-failure payload restore ───────────────────────

    /**
     * §blocker1: a /command in draft mode that FAILS to createSession must
     * PRESERVE the command text so the user can retry. Pre-fix, executeCommand
     * cleared inputText BEFORE materializeDraftSession, and on create-failure
     * only draftWorkdir was restored — the command text was lost.
     */
    @Test
    fun `command create-failure preserves command text for retry`() = runTest {
        coEvery { repository.createSession(title = null, directory = any()) } returns Result.failure(IllegalStateException("nope"))

        val core = wire()
        core.writeComposer { it.copy(inputText = "/compact extra", draftWorkdir = "/proj") }

        core.executeCommand(command = "/compact", arguments = "extra")
        advanceUntilIdle()

        // The command text SURVIVED (user can retry).
        assertEquals("/compact extra", core.composerFlow.value.inputText)
        // draftWorkdir restored (ownership guard: still on draft surface).
        assertEquals("/proj", core.composerFlow.value.draftWorkdir)
        // Route did NOT jump (create failed).
        assertNotEquals("chat/ses_new", core.store.navFlow.value.lastRoute)
        // Error surfaced.
        assertNotNull(core.recentTestErrors.lastOrNull())
    }

    // ── §blocker2: host switch during create rejects adoption ───────────────────

    /**
     * §blocker2: a host/profile switch during the suspended createSession must
     * REJECT the adoption (list-only: session upserted, but currentSessionId /
     * nav / content NOT set, NO hydration, NO send to the wrong host). Pre-fix,
     * stillOwnsDraftSurface did not compare host identity → the old-host response
     * wrote into the WRONG host's state.
     */
    @Test
    fun `host switch during create rejects adoption and does not send to wrong host`() = runTest {
        val created = Session(id = "ses_new", directory = "/proj")
        coEvery { repository.createSession(title = null, directory = any()) } returns Result.success(created)
        coEvery { repository.getMessagesPagedUnanchored(any(), any(), any()) } returns Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getSessionTodos(any()) } returns Result.success(emptyList())
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSession(any()) } returns Result.success(created)

        val core = wire()
        // Set an initial host identity so the origin captures a non-null hostProfileId.
        core.writeHost { it.copy(currentHostProfileId = "host_A") }

        core.writeComposer { it.copy(inputText = "hi", draftWorkdir = "/proj") }

        // Simulate a host switch DURING createSession: the answers block changes
        // currentHostProfileId BEFORE returning, so stillOwnsDraftSurface fails.
        coEvery { repository.createSession(title = null, directory = any()) } answers {
            core.writeHost { it.copy(currentHostProfileId = "host_B") }
            Result.success(created)
        }

        core.sendMessage()
        advanceUntilIdle()

        // List-only: session upserted + pendingCreate tracked.
        assertTrue("session upserted", core.store.sessionListFlow.value.sessions.any { it.id == "ses_new" })
        assertTrue("pendingCreate tracked", core.store.sessionListFlow.value.pendingCreateIds.contains("ses_new"))
        // NO route/nav/content transition.
        assertNotEquals("chat/ses_new", core.store.navFlow.value.lastRoute)
        assertNull("currentSessionId NOT set (list-only)", core.store.chatFlow.value.currentSessionId)
        assertNull("content NOT set (list-only)", core.store.chatFlow.value.content)
        // NO send to the wrong host (host-changed gate dropped it).
        coVerify(exactly = 0) { repository.sendMessage(any(), any(), any(), any(), any()) }
    }

    // ── §blocker1-command: command create-SUCCESS-during-edit preservation ──────

    /**
     * §blocker1-command: a /command in draft mode that SUCCEEDS in creating a
     * session, but during the createSession suspend boundary the user typed NEW
     * content into the composer. The captured cmd/arguments are what gets
     * executed (click-time intent — correct), but the composer's NEWER text must
     * SURVIVE (compare-and-clear on the command-success path). Pre-fix, the
     * command-success branch did an UNCONDITIONAL `setInputText("")` → the
     * user's newer content was wiped.
     */
    @Test
    fun `command create-SUCCESS preserves newer composer text while executing the captured command`() = runTest {
        val created = Session(id = "ses_new", directory = "/proj")
        coEvery { repository.createSession(title = null, directory = any()) } returns Result.success(created)
        coEvery { repository.getMessagesPagedUnanchored(any(), any(), any()) } returns Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getSessionTodos(any()) } returns Result.success(emptyList())
        coEvery { repository.executeCommand(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSession(any()) } returns Result.success(created)

        val core = wire()
        // Set up createSession to simulate the user typing NEW content during
        // the suspended await. `core` (local) is in scope here.
        coEvery { repository.createSession(title = null, directory = any()) } answers {
            core.writeComposer { it.copy(inputText = "/compact extra then B") }
            Result.success(created)
        }
        core.writeComposer { it.copy(inputText = "/compact extra", draftWorkdir = "/proj") }

        core.executeCommand(command = "/compact", arguments = "extra")
        advanceUntilIdle()

        // (a) executeCommand was called with the CAPTURED cmd/arguments
        // (click-time intent — "compact" / "extra"), NOT the newer text.
        coVerify { repository.executeCommand("ses_new", "compact", "extra", any(), any()) }
        // (b) The composer STILL holds the user's newer text (compare-and-clear
        // preserved it — NOT wiped by the unconditional clear).
        assertEquals("/compact extra then B", core.composerFlow.value.inputText)
    }

    // ── §slim-storm P2: EvictSession handler self-heal ─────────────────────────

    @Test
    fun `§slim-storm P2 EvictSession with matching fp dispatches SessionDeletedLocal and removes session from list`() = runTest {
        val sid = "evict-me"
        coEvery { repository.getSession(any()) } returns Result.success(Session(id = sid, directory = "/x"))

        val core = wire()
        core.writeSessionList {
            it.copy(sessions = listOf(Session(id = sid, directory = "/x")))
        }

        val currentFp = core.currentProfileId()
        core.effectBus.emitEffect(ControllerEffect.EvictSession(profileId = currentFp, sessionId = sid))
        advanceUntilIdle()

        assertFalse(
            "EvictSession with matching fp removes session from list",
            core.store.sessionListFlow.value.sessions.any { it.id == sid },
        )
    }

    @Test
    fun `§slim-storm P2 EvictSession with stale fp leaves session in list unchanged`() = runTest {
        val sid = "stay-put"
        coEvery { repository.getSession(any()) } returns Result.success(Session(id = sid, directory = "/x"))

        val core = wire()
        core.writeSessionList {
            it.copy(sessions = listOf(Session(id = sid, directory = "/x")))
        }

        core.effectBus.emitEffect(ControllerEffect.EvictSession(profileId = "stale-fp", sessionId = sid))
        advanceUntilIdle()

        assertTrue(
            "stale-fp EvictSession must NOT remove session from current host's list",
            core.store.sessionListFlow.value.sessions.any { it.id == sid },
        )
    }

    // ── §rev-ds round-2 FIX 1: computeQuestionFanOutWorkdirs ──────────────────
    //
    // Pure helper — direct unit tests for the workdir-set computation
    // shared by BOTH pending-question fan-out sites. Pins dedup,
    // blank filtering, null-currentWorkdir, and order/distinct correctness so a
    // future refactor of the helper cannot silently drop a source.
    // Restored from pre-P3 (removed in 24ad5734).

    @Test
    fun `computeQuestionFanOutWorkdirs dedupes a workdir present in all three sources to a single entry`() {
        val result = computeQuestionFanOutWorkdirs(
            directorySessionKeys = setOf("/dup", "/a"),
            currentWorkdir = "/dup",
            recentWorkdirs = listOf("/dup", "/b"))
        assertEquals(listOf("/dup", "/a", "/b"), result)
        assertEquals("no duplicate entries", 3, result.toSet().size)
    }

    @Test
    fun `computeQuestionFanOutWorkdirs filters blank and empty entries from every source`() {
        val result = computeQuestionFanOutWorkdirs(
            directorySessionKeys = setOf("/ok", "", "   "),
            currentWorkdir = "",
            recentWorkdirs = listOf("/recent", "", "  "))
        assertEquals(listOf("/ok", "/recent"), result)
    }

    @Test
    fun `computeQuestionFanOutWorkdirs handles null currentWorkdir without crashing`() {
        val result = computeQuestionFanOutWorkdirs(
            directorySessionKeys = setOf("/a"),
            currentWorkdir = null,
            recentWorkdirs = listOf("/b"))
        assertEquals(listOf("/a", "/b"), result)
    }

    @Test
    fun `computeQuestionFanOutWorkdirs returns empty list when every source is empty or blank`() {
        val result = computeQuestionFanOutWorkdirs(
            directorySessionKeys = emptySet(),
            currentWorkdir = null,
            recentWorkdirs = emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `computeQuestionFanOutWorkdirs preserves first-seen order and drops later duplicates`() {
        val result = computeQuestionFanOutWorkdirs(
            directorySessionKeys = setOf("/x"),
            currentWorkdir = "/x",
            recentWorkdirs = listOf("/x", "/y"),
        )
        assertEquals(listOf("/x", "/y"), result)
    }

    @Test
    fun `computeQuestionFanOutWorkdirs dedups slash-variants after normalize`() {
        val result = computeQuestionFanOutWorkdirs(
            directorySessionKeys = setOf("/app", "/app/"),
            currentWorkdir = "/app",
            recentWorkdirs = listOf("/b"))
        assertEquals(listOf("/app", "/b"), result)
    }

    @Test
    fun `computeQuestionFanOutWorkdirs dedups slash entries after normalize preserving root`() {
        val result = computeQuestionFanOutWorkdirs(
            directorySessionKeys = setOf("", "/"),
            currentWorkdir = "/",
            recentWorkdirs = listOf(""))
        assertEquals(listOf("/"), result)
    }

    @Test
    fun `computeQuestionFanOutWorkdirs preserves first-seen post-normalize form`() {
        val result = computeQuestionFanOutWorkdirs(
            directorySessionKeys = setOf("/proj-a/"),
            currentWorkdir = "/proj-a",
            recentWorkdirs = listOf("/proj-b/"))
        assertEquals(listOf("/proj-a", "/proj-b"), result)
    }
}
