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
class HostViewModelTest : MainViewModelTestBase() {

    @Test
    fun `saveHostProfile writes basic auth password when basicAuthEdited is true`() = runTest {
        val profile = HostProfile.defaultDirect("http://server.test").copy(
            id = "profile-1",
            basicAuth = BasicAuthConfig(username = "alice", passwordId = "profile-1")
        )

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = HostViewModel(core)  // primary VM under test
        hostVM.saveHostProfile(
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

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = HostViewModel(core)  // primary VM under test
        hostVM.saveHostProfile(
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

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = HostViewModel(core)  // primary VM under test
        hostVM.saveHostProfile(
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

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = HostViewModel(core)  // primary VM under test
        hostVM.saveHostProfile(
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

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = HostViewModel(core)  // primary VM under test
        hostVM.saveHostProfile(
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

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = HostViewModel(core)  // primary VM under test
        hostVM.saveHostProfile(
            profile,
            tunnelPassword = "",
            tunnelEdited = true
        )

        // blank → setTunnelPassword with "" which SettingsManager maps to remove.
        verify { settingsManager.setTunnelPassword("profile-1", "") }
    }

    @Test
    fun `activateTunnelForCurrentHost surfaces error when profile has no tunnelPasswordId`() = runTest {
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = HostViewModel(core)  // primary VM under test
        advanceUntilIdle()

        hostVM.activateTunnelForCurrentHost()
        advanceUntilIdle()

        // Does not call the repository, but now surfaces a specific error so the
        // user knows why activation did nothing (previously a silent no-op).
        coVerify(exactly = 0) { repository.activateTunnel(any(), any()) }
        assertTrue(connectionVM.connectionFlow.value.tunnelActivationState is TunnelActivationState.Error)
        assertNotNull(core.recentTestErrors.lastOrNull())
    }

    @Test
    fun `activateTunnelForCurrentHost surfaces error when tunnel password is empty`() = runTest {
        val profileWithTunnel = HostProfile.defaultDirect("http://server.test").copy(
            tunnelPasswordId = "profile-1"
        )
        every { hostProfileStore.currentProfile() } returns profileWithTunnel
        every { settingsManager.getTunnelPassword("profile-1") } returns null

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = HostViewModel(core)  // primary VM under test
        advanceUntilIdle()

        hostVM.activateTunnelForCurrentHost()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.activateTunnel(any(), any()) }
        assertTrue(connectionVM.connectionFlow.value.tunnelActivationState is TunnelActivationState.Error)
        assertNotNull(core.recentTestErrors.lastOrNull())
    }

    @Test
    fun `activateTunnelForCurrentHost sets Loading then Success on success`() = runTest {
        val profileWithTunnel = HostProfile.defaultDirect("http://server.test").copy(
            tunnelPasswordId = "profile-1"
        )
        every { hostProfileStore.currentProfile() } returns profileWithTunnel
        every { settingsManager.getTunnelPassword("profile-1") } returns "tunnel-secret"
        coEvery { repository.activateTunnel("http://server.test", "tunnel-secret") } returns Result.success(Unit)
        // §tunnel-refresh: mock checkHealth for auto coldStartReconnect after tunnel activation
        coEvery { repository.checkHealth() } returns
            Result.success(HealthResponse(healthy = true, version = "1.0"))

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = HostViewModel(core)  // primary VM under test
        advanceUntilIdle()

        hostVM.activateTunnelForCurrentHost()
        advanceUntilIdle()

        coVerify { repository.activateTunnel("http://server.test", "tunnel-secret") }
        assertEquals(
            cn.vectory.ocdroid.ui.TunnelActivationState.Success,
            connectionVM.connectionFlow.value.tunnelActivationState
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

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = HostViewModel(core)  // primary VM under test
        advanceUntilIdle()

        hostVM.activateTunnelForCurrentHost()
        advanceUntilIdle()

        val activationState = connectionVM.connectionFlow.value.tunnelActivationState
        assertTrue(activationState is cn.vectory.ocdroid.ui.TunnelActivationState.Error)
        assertTrue((activationState as cn.vectory.ocdroid.ui.TunnelActivationState.Error).message.contains("403"))
    }

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

        coEvery { repository.getMessagesPaged("session-A", any(), any()) } returns
            Result.success(
                MessagesPage(
                    listOf(MessageWithParts(info = Message(id = "m_a1", role = "user"))),
                    null
                )
            )

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = HostViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "session-A") }
        core.writeSessionList {
            it.copy(sessions = listOf(Session(id = "session-A", directory = "/tmp/a")))
        }
        chatVM.loadMessages("session-A")
        advanceUntilIdle()
        assertEquals(1, core.sessionWindowCacheSize())

        hostVM.selectHostProfile("other")
        advanceUntilIdle()

        assertEquals(
            "Host switch must clear the per-session message cache",
            0,
            core.sessionWindowCacheSize()
        )
    }

}
