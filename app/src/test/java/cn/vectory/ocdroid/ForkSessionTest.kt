package cn.vectory.ocdroid

import android.util.Log
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.MessagesPage
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.di.AppLifecycleMonitor
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.AppCore
import cn.vectory.ocdroid.ui.SessionViewModel
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.SessionListState
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.ThemeMode
import cn.vectory.ocdroid.util.TrafficTracker
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * §R-17 batch3e: ForkSessionTest migrated off the legacy `AppCore.forkSession`
 * shim — the test now constructs a [SessionViewModel] (which owns
 * `forkSession`) and exercises the method through its public surface.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForkSessionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repository: OpenCodeRepository
    private lateinit var settingsManager: SettingsManager
    private lateinit var hostProfileStore: HostProfileStore
    private lateinit var trafficTracker: TrafficTracker
    private lateinit var appLifecycleMonitor: AppLifecycleMonitor

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
        trafficTracker = mockk(relaxed = true)
        appLifecycleMonitor = mockk(relaxed = true)
        every { appLifecycleMonitor.isInForeground } returns MutableStateFlow(true)

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
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        coEvery { repository.getMessages(any(), any()) } returns Result.success(emptyList())
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getPendingPermissions() } returns Result.success(emptyList())
        coEvery { repository.getPendingQuestions() } returns Result.success(emptyList())
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun createCore(): AppCore =
        AppCore(SharedStateStore(), repository, settingsManager, hostProfileStore, trafficTracker, appLifecycleMonitor, cn.vectory.ocdroid.data.repository.ServerCompatProfile(), SharedEffectBus())

    @Test
    fun `forkSession success upserts forked session and selects it`() = runTest {
        val parentSession = Session(id = "parent-1", directory = "/tmp/project", title = "Parent Session")
        val forkedSession = Session(
            id = "forked-1",
            directory = "/tmp/project",
            title = "Parent Session",
            parentId = "parent-1"
        )
        coEvery { repository.forkSession("parent-1", "msg-42") } returns Result.success(forkedSession)

        val core = createCore()
        val sessionVM = SessionViewModel(core)
        // §R-17 batch2: write slices directly (no AppState mirror dependency).
        core.store.sessionListFlow.value = core.store.sessionListFlow.value.copy(sessions = listOf(parentSession))
        core.store.chatFlow.value = core.store.chatFlow.value.copy(currentSessionId = "parent-1")

        sessionVM.forkSession("parent-1", "msg-42")
        advanceUntilIdle()

        coVerify { repository.forkSession("parent-1", "msg-42") }
        val sessions = sessionVM.sessionListFlow.value.sessions
        assertTrue("forked session should be in list", sessions.any { it.id == "forked-1" })
        assertEquals("forked-1", sessionVM.chatFlow.value.currentSessionId)
        // §R-17 batch2: success path emits NO UiEvent (errors ride _uiEvents).
        core.uiEvents.test {
            expectNoEvents()
        }
    }

    @Test
    fun `forkSession with null messageId calls repository correctly`() = runTest {
        val forkedSession = Session(id = "forked-2", directory = "/tmp/project")
        coEvery { repository.forkSession("parent-1", null) } returns Result.success(forkedSession)

        val core = createCore()
        val sessionVM = SessionViewModel(core)

        sessionVM.forkSession("parent-1", null)
        advanceUntilIdle()

        coVerify { repository.forkSession("parent-1", null) }
        assertTrue(sessionVM.sessionListFlow.value.sessions.any { it.id == "forked-2" })
    }

    @Test
    fun `forkSession failure sets error in state`() = runTest {
        coEvery { repository.forkSession(any(), any()) } returns Result.failure(RuntimeException("fork failed"))

        val core = createCore()
        val sessionVM = SessionViewModel(core)
        core.store.chatFlow.value = core.store.chatFlow.value.copy(currentSessionId = "parent-1")

        core.uiEvents.test {
            sessionVM.forkSession("parent-1", "msg-99")
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue("error event should be emitted on failure", event is cn.vectory.ocdroid.ui.UiEvent.Error)
            val message = (event as cn.vectory.ocdroid.ui.UiEvent.Error).message
            assertTrue("error should mention fork", message.contains("fork", ignoreCase = true))
        }
        assertEquals("parent-1", sessionVM.chatFlow.value.currentSessionId)
    }
}
