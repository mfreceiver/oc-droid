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
 * State setup uses the test-only [AppState] shim ([updateState] /
 * [AppCore.state]) so the historical `updateState { it.copy(...) }` and
 * `viewModel.state.value.X` patterns keep working without rewriting every
 * state-seed call. AppCore internals that have no VM equivalent
 * (`handleSSEEvent`, `store`, `peekSessionWindow`) are reached through
 * `core` directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionViewModelTest : MainViewModelTestBase() {

    @Test
    fun `non-current workdir is retained after cold start via recentWorkdirs even when paged out`() = runTest {
        val workdirA = "/home/user/projectA"
        val workdirB = "/home/user/projectB"
        val sessionA = cn.vectory.ocdroid.data.model.Session(id = "s-a1", directory = workdirA, title = "A chat")
        val sessionB = cn.vectory.ocdroid.data.model.Session(id = "s-b1", directory = workdirB, title = "B chat")

        // Previous run connected A then B (B most recent). currentWorkdir is
        // the single last-used value; recentWorkdirs remembers BOTH.
        every { settingsManager.currentWorkdir } returns workdirB
        every { settingsManager.recentWorkdirs } returns listOf(workdirB, workdirA)

        // Healthy connect → loadInitialData.
        coEvery { repository.checkHealth() } returns
            Result.success(HealthResponse(healthy = true, version = "1.0"))
        // Global list first page (limit=10): A's session is older and paged out;
        // only B's session is returned here (server sorts by updated desc).
        coEvery { repository.getSessions(10) } returns Result.success(listOf(sessionB))
        // loadInitialData now re-fetches directorySessions for BOTH recent workdirs.
        coEvery { repository.getSessionsForDirectory(workdirB, any()) } returns Result.success(listOf(sessionB))
        coEvery { repository.getSessionsForDirectory(workdirA, any()) } returns Result.success(listOf(sessionA))

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ConnectionViewModel(core)  // primary VM under test
        connectionVM.coldStartReconnect()
        advanceUntilIdle()

        // currentWorkdir (B) is restored into directorySessions.
        assertTrue(
            "current workdir B should be restored into directorySessions",
            sessionVM.sessionListFlow.value.directorySessions.containsKey(workdirB)
        )
        // FIX: A is ALSO restored into directorySessions via the recentWorkdirs
        // set — no longer lost when its sessions are paged out of the global list.
        assertTrue(
            "FIX: non-current workdir A is RETAINED in directorySessions via recentWorkdirs",
            sessionVM.sessionListFlow.value.directorySessions.containsKey(workdirA)
        )
        assertEquals(
            "A's directory sessions should be populated",
            listOf(sessionA),
            sessionVM.sessionListFlow.value.directorySessions[workdirA]
        )
        // loadInitialData now issues a directory fetch for A as well.
        coVerify(exactly = 1) { repository.getSessionsForDirectory(workdirA, any()) }
    }

    @Test
    fun `loadInitialData restores only currentWorkdir when recentWorkdirs is empty`() = runTest {
        val workdirB = "/home/user/projectB"
        val sessionB = cn.vectory.ocdroid.data.model.Session(id = "s-b1", directory = workdirB, title = "B chat")

        every { settingsManager.currentWorkdir } returns workdirB
        every { settingsManager.recentWorkdirs } returns emptyList()
        coEvery { repository.checkHealth() } returns
            Result.success(HealthResponse(healthy = true, version = "1.0"))
        coEvery { repository.getSessions(10) } returns Result.success(listOf(sessionB))
        coEvery { repository.getSessionsForDirectory(workdirB, any()) } returns Result.success(listOf(sessionB))

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ConnectionViewModel(core)  // primary VM under test
        connectionVM.coldStartReconnect()
        advanceUntilIdle()

        // currentWorkdir (B) still restored via the currentWorkdir fallback.
        assertTrue(sessionVM.sessionListFlow.value.directorySessions.containsKey(workdirB))
        // Exactly one directory fetch (B only — no unbounded fan-out).
        coVerify(exactly = 1) { repository.getSessionsForDirectory(any(), any()) }
    }

    @Test
    fun `testConnection skips second health check within cooldown`() = runTest {
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = false, version = "1.0"))

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = ConnectionViewModel(core)  // primary VM under test

        connectionVM.testConnection()
        connectionVM.testConnection()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.checkHealth() }
    }

}
