package cn.vectory.ocdroid

import android.util.Log
import cn.vectory.ocdroid.data.model.BasicAuthConfig
import cn.vectory.ocdroid.data.model.ConfigProvider
import cn.vectory.ocdroid.data.model.HealthResponse
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.PermissionResponse
import cn.vectory.ocdroid.data.model.ProviderModel
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
        // §chat-ux-batch T7 (B2): switchSessionModel now writes the TRANSIENT
        // pendingModel (was: currentModel + setModelForSession). The legacy
        // writes are kept unread by T7's send/picker paths; T8 deletes them.
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

        // V1-per-prompt (T7) + T8 (B3): the choice lives in the transient
        // pendingModel. The picker / dispatch read pendingModel at send time;
        // the legacy setModelForSession path was deleted in T8 (no method to
        // verify against). The contract is "transient pending carries the
        // choice to the wire" — assert the slice state directly.
        assertEquals(
            Message.ModelInfo("openai", "gpt-5"),
            chatVM.chatFlow.value.pendingModel,
        )
    }

    @Test
    fun `switchSessionModel in draft mode updates currentModel without persisting`() = runTest {
        // §chat-ux-batch T7 (B2): in draft mode (no session yet),
        // switchSessionModel writes the transient pendingModel — same path as
        // the active-session case (no per-session storage involved either way).
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

        // pendingModel carries the draft choice so the picker reflects it and
        // dispatchSend's `pendingModel ?: infer` uses it on the first send.
        assertEquals(
            Message.ModelInfo("openai", "gpt-5"),
            chatVM.chatFlow.value.pendingModel,
        )
        // §chat-ux-batch T8 (B3): setModelForSession was deleted; the contract
        // is "transient pending carries the choice" — the assertion above is
        // the sole authority (no method left to verify against).
    }

    @Test
    fun `draft model choice is persisted when session materialises on first send`() = runTest {
        // §chat-ux-batch T7 (B2): with the pending-based contract, the draft
        // model choice rides on the transient pendingModel and is consumed by
        // dispatchSend at first send. There is no separate "persist on
        // materialise" step (the legacy setModelForSession path is now dead —
        // T8 deletes it). The outgoing prompt MUST carry the chosen model.
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
        assertEquals(Message.ModelInfo("openai", "gpt-5"), chatVM.chatFlow.value.pendingModel)

        // First send materialises the draft into a real session.
        composerVM.setInputText("hi")
        chatVM.sendMessage()
        advanceUntilIdle()

        // §chat-ux-batch T7 (B2) + T8 (B3): the legacy setModelForSession path
        // was deleted in T8; the contract is "transient pending carries the
        // choice to the wire" — asserted via the coVerify on sendMessage below.
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
        // Pending cleared after the send.
        assertNull(core.chatFlow.value.pendingModel)
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

        verify { settingsManager.setDraftText(any(), "s1", "hello") }
    }

    @Test
    fun `selectAgent with active session saves agent name per session`() = runTest {
        // §chat-ux-batch T7 (B2): selectAgent now writes the TRANSIENT
        // pendingAgent (was: selectedAgentName + setAgentForSession). The
        // legacy writes are kept unread by T7's send/picker paths; T8 deletes
        // them.
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

        // §chat-ux-batch T7 (B2) + T8 (B3): the agent pick lives in pendingAgent.
        // The legacy setAgentForSession was deleted in T8 — assert the slice
        // state directly (no method left to verify against).
        assertEquals("oracle", chatVM.chatFlow.value.pendingAgent)
    }

    // ── §provider-bulk-toggle: setProviderModelsEnabled ──
    // Mirrors the existing createCore() + ComposerViewModel(core) setup used
    // throughout this file. The providers catalog is seeded into the settings
    // slice via core.writeSettings (same path the connect-time hydrator uses).

    private fun providersFixture(): ProvidersResponse = ProvidersResponse(
        providers = listOf(
            ConfigProvider(
                id = "p1",
                name = "P1",
                models = mapOf(
                    "m1" to ProviderModel(id = "m1", name = "M1"),
                    "m2" to ProviderModel(id = "m2", name = "M2")
                )
            ),
            ConfigProvider(
                id = "p2",
                name = "P2",
                models = mapOf(
                    "m1" to ProviderModel(id = "m1", name = "M1")
                )
            )
        )
    )

    @Test
    fun `setProviderModelsEnabled false disables all of the provider's models`() = runTest {
        val core = createCore()
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        core.writeSettings { it.copy(providers = providersFixture()) }

        composerVM.setProviderModelsEnabled(providerId = "p1", enabled = false)

        // Both p1 model keys are now disabled; p2 is untouched.
        assertEquals(
            setOf("p1/m1", "p1/m2"),
            core.settingsFlow.value.disabledModels
        )
        // One batched persist call covering the whole set.
        verify { settingsManager.setDisabledModels(any(), setOf("p1/m1", "p1/m2")) }
    }

    @Test
    fun `setProviderModelsEnabled true re-enables all of the provider's models`() = runTest {
        val core = createCore()
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        // Pre-disable both of p1's models.
        core.writeSettings {
            it.copy(
                providers = providersFixture(),
                disabledModels = setOf("p1/m1", "p1/m2")
            )
        }

        composerVM.setProviderModelsEnabled(providerId = "p1", enabled = true)

        assertEquals(emptySet<String>(), core.settingsFlow.value.disabledModels)
        verify { settingsManager.setDisabledModels(any(), emptySet()) }
    }

    @Test
    fun `setProviderModelsEnabled does not touch other providers' model keys`() = runTest {
        val core = createCore()
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        // Pre-existing disabled state spanning p1, p2, and a stale entry.
        core.writeSettings {
            it.copy(
                providers = providersFixture(),
                disabledModels = setOf("p2/m1", "stale/x")
            )
        }

        composerVM.setProviderModelsEnabled(providerId = "p1", enabled = false)

        // p1 fully disabled; p2/m1 and the stale entry preserved untouched.
        assertEquals(
            setOf("p1/m1", "p1/m2", "p2/m1", "stale/x"),
            core.settingsFlow.value.disabledModels
        )
        verify {
            settingsManager.setDisabledModels(
                any(),
                setOf("p1/m1", "p1/m2", "p2/m1", "stale/x")
            )
        }
    }
}

