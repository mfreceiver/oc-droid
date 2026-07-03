package cn.vectory.ocdroid

import android.util.Log
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.MessagesPage
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.di.AppLifecycleMonitor
import cn.vectory.ocdroid.ui.AppState
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.MainViewModel
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.ThemeMode
import cn.vectory.ocdroid.util.TrafficTracker
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

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
        // §15.2: MainViewModel.init subscribes to isInForeground via
        // onEach{}.launchIn(viewModelScope). Hand back a real foreground
        // StateFlow so the init-time subscription does not NPE.
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
        // §stale-question: SessionSwitcher.switchTo now calls
        // loadPendingQuestions() at Step 6.5, so the relaxed mock needs an
        // explicit stub (otherwise it returns a non-List Result payload that
        // throws ClassCastException inside the coroutine).
        coEvery { repository.getPendingQuestions() } returns Result.success(emptyList())
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun createViewModel(): MainViewModel {
        return MainViewModel(repository, settingsManager, hostProfileStore, trafficTracker, appLifecycleMonitor, cn.vectory.ocdroid.data.repository.ServerCompatProfile())
    }

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

        val viewModel = createViewModel()
        val field = MainViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as MutableStateFlow<AppState>
        stateFlow.value = stateFlow.value.copy(
            sessions = listOf(parentSession),
            currentSessionId = "parent-1"
        )

        viewModel.forkSession("parent-1", "msg-42")
        advanceUntilIdle()

        coVerify { repository.forkSession("parent-1", "msg-42") }
        val sessions = viewModel.state.value.sessions
        assertTrue("forked session should be in list", sessions.any { it.id == "forked-1" })
        assertEquals("forked-1", viewModel.state.value.currentSessionId)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `forkSession with null messageId calls repository correctly`() = runTest {
        val forkedSession = Session(id = "forked-2", directory = "/tmp/project")
        coEvery { repository.forkSession("parent-1", null) } returns Result.success(forkedSession)

        val viewModel = createViewModel()

        viewModel.forkSession("parent-1", null)
        advanceUntilIdle()

        coVerify { repository.forkSession("parent-1", null) }
        assertTrue(viewModel.state.value.sessions.any { it.id == "forked-2" })
    }

    @Test
    fun `forkSession failure sets error in state`() = runTest {
        coEvery { repository.forkSession(any(), any()) } returns Result.failure(RuntimeException("fork failed"))

        val viewModel = createViewModel()
        val field = MainViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as MutableStateFlow<AppState>
        stateFlow.value = stateFlow.value.copy(currentSessionId = "parent-1")
        // §R-17 M5: updateState aggregates from the slices, so seed the chat
        // slice's currentSessionId too (otherwise the aggregate reads null and
        // the post-fork assertion on state.value.currentSessionId fails).
        val chatField = MainViewModel::class.java.getDeclaredField("_chatFlow")
        chatField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val chatFlow = chatField.get(viewModel) as MutableStateFlow<ChatState>
        chatFlow.value = chatFlow.value.copy(currentSessionId = "parent-1")

        viewModel.forkSession("parent-1", "msg-99")
        advanceUntilIdle()

        val error = viewModel.state.value.error
        assertNotNull("error should be set on failure", error)
        assertTrue("error should mention fork", error!!.contains("fork", ignoreCase = true))
        assertEquals("parent-1", viewModel.state.value.currentSessionId)
    }
}
