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
class ComposerViewModelTest : MainViewModelTestBase() {

    @Test
    fun `switchSessionModel persists per-session and updates currentModel immediately`() = runTest {
        coEvery { repository.getSessions(100) } returns Result.success(
            listOf(Session(id = "session-1", directory = "/tmp/project"))
        )

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ComposerViewModel(core)  // primary VM under test
        sessionVM.selectSession("session-1")
        advanceUntilIdle()

        composerVM.switchSessionModel("openai", "gpt-5")

        // V1-per-prompt: choice is persisted LOCALLY per session (no server call).
        verify { settingsManager.setModelForSession("session-1", "openai", "gpt-5") }
        // Synchronous in-memory update so the picker reflects the choice with no
        // launch/await (switchSessionModel is a plain fun, not a suspend/coroutine).
        assertEquals(
            Message.ModelInfo("openai", "gpt-5"),
            chatVM.chatFlow.value.currentModel
        )
    }

    @Test
    fun `switchSessionModel in draft mode updates currentModel without persisting`() = runTest {
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ComposerViewModel(core)  // primary VM under test
        sessionVM.createSessionInWorkdir("/tmp/project")
        advanceUntilIdle()

        // Draft mode sanity: no session yet.
        assertNull(chatVM.chatFlow.value.currentSessionId)

        composerVM.switchSessionModel("openai", "gpt-5")

        // In-memory currentModel MUST update so the picker reflects the choice
        // and dispatchSend()'s (getModelForSession ?: currentModel) fallback
        // uses it on the first send.
        assertEquals(
            Message.ModelInfo("openai", "gpt-5"),
            chatVM.chatFlow.value.currentModel
        )
        // No real session exists yet → must NOT persist (no valid session id).
        verify(exactly = 0) { settingsManager.setModelForSession(any(), any(), any()) }
    }

    @Test
    fun `draft model choice is persisted when session materialises on first send`() = runTest {
        coEvery { repository.createSession(any(), any()) } returns Result.success(
            Session(id = "draft-1", directory = "/tmp/project")
        )
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        // scheduleTitleRefreshAfterFirstMessage calls getSession after a delay;
        // relaxed mock returns Object → ClassCastException, so stub explicitly.
        coEvery { repository.getSession(any()) } returns Result.success(
            Session(id = "draft-1", directory = "/tmp/project")
        )

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ComposerViewModel(core)  // primary VM under test
        sessionVM.createSessionInWorkdir("/tmp/project")
        advanceUntilIdle()

        // User switches the model while still in draft (currentSessionId == null).
        composerVM.switchSessionModel("openai", "gpt-5")
        assertEquals(Message.ModelInfo("openai", "gpt-5"), chatVM.chatFlow.value.currentModel)

        // First send materialises the draft into a real session.
        composerVM.setInputText("hi")
        chatVM.sendMessage()
        advanceUntilIdle()

        // The in-memory draft choice MUST be persisted to the now-real session
        // id, so subsequent loadMessages reads it via getModelForSession instead
        // of falling back to inference.
        verify { settingsManager.setModelForSession("draft-1", "openai", "gpt-5") }
        // And the outgoing prompt carries the chosen model.
        coVerify {
            repository.sendMessage(
                "draft-1",
                "hi",
                any(),
                Message.ModelInfo("openai", "gpt-5"),
                any()
            )
        }
    }

    @Test
    fun `setInputText with active session saves draft to settings manager`() = runTest {
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ComposerViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "s1") }

        composerVM.setInputText("hello")

        verify { settingsManager.setDraftText("s1", "hello") }
    }

    @Test
    fun `selectAgent with active session saves agent name per session`() = runTest {
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ComposerViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "s1") }

        composerVM.selectAgent("oracle")

        verify { settingsManager.setAgentForSession("s1", "oracle") }
    }

}
