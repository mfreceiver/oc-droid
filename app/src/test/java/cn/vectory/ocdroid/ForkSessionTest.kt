package cn.vectory.ocdroid

import android.content.Context
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
        every { settingsManager.themeMode } returns ThemeMode.SYSTEM

        every { settingsManager.serverUrl = any() } just runs
        every { settingsManager.username = any() } just runs
        every { settingsManager.password = any() } just runs
        every { settingsManager.currentSessionId = any() } just runs
        every { settingsManager.themeMode = any() } just runs

        every { settingsManager.getDraftText(any(), any()) } returns ""
        every { settingsManager.setDraftText(any(), any(), any()) } just runs
        // §chat-ux-batch T8 (B3): the legacy mock setup for selectedAgentName /
        // getAgentForSession / setAgentForSession was removed here (deleted APIs).

        every { repository.connectSSE(any()) } returns emptyFlow()
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        coEvery { repository.getMessages(any(), any()) } returns Result.success(emptyList())
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getPendingPermissions() } returns Result.success(emptyList())
        coEvery { repository.getPendingQuestions(any()) } returns Result.success(emptyList())
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun createCore(): AppCore {
        // §R-19 Sprint 3 P2-5: AppCore now takes the 5 controllers + scope as
        // constructor params (Hilt-injected in production). Tests construct
        // them inline with the same wiring [cn.vectory.ocdroid.di.ControllerModule]
        // uses — see MainViewModelTestBase.createCore for the same pattern.
        val appContext = io.mockk.mockk<android.content.Context>(relaxed = true)
        val store = SharedStateStore()
        val effectBus = SharedEffectBus()
        val appScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() +
                kotlinx.coroutines.Dispatchers.Main.immediate
        )
        val foregroundCatchUpController = cn.vectory.ocdroid.ui.controller.ForegroundCatchUpController(
            appLifecycleMonitor = appLifecycleMonitor,
            scope = appScope,
            store = store,
            settingsManager = settingsManager,
            effects = effectBus,
        )
        val composerController = cn.vectory.ocdroid.ui.controller.ComposerController(
            store = store,
            settingsManager = settingsManager,
            hostProfileStore = hostProfileStore,
        )
        val sessionSwitcher = cn.vectory.ocdroid.ui.controller.SessionSwitcher(
            store = store,
            settingsManager = settingsManager,
            repository = repository,
            effects = effectBus,
            currentServerGroupFp = { "test-fp" },
        )
        // CP1 (notify Phase-0): single connection-identity store.
        val identityStore = cn.vectory.ocdroid.service.identity.ConnectionIdentityStore()
        // CP3 (notify Phase-0): SSE event stream + bridge.
        val sseEventStream = cn.vectory.ocdroid.service.events.SseEventStream()
        val sseEventBridge = cn.vectory.ocdroid.service.bridge.SseEventBridge(appScope)
        val hostProfileController = cn.vectory.ocdroid.ui.controller.HostProfileController(
            scope = appScope,
            slices = store.slices,
            hostProfileStore = hostProfileStore,
            repository = repository,
            settingsManager = settingsManager,
            trafficTracker = trafficTracker,
            effects = effectBus,
            currentServerGroupFp = { "test-fp" },
            identityStore = identityStore,
        )
        val sessionSyncCoordinator = cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator(
            scope = appScope,
            slices = store.slices,
            settingsManager = settingsManager,
            effects = effectBus,
            currentServerGroupFp = { "test-fp" },
            identityStore = identityStore,
        )
        val connectionCoordinator = cn.vectory.ocdroid.ui.controller.ConnectionCoordinator(
            scope = appScope,
            slices = store.slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effectBus,
            serverCompatProfile = cn.vectory.ocdroid.data.repository.ServerCompatProfile(),
            identityStore = identityStore,
            // CP2 (notify Phase-0): delegate TOFU state.
            bootstrapCoordinator = cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator(),
            // CP9 (notify Phase-0 switchover): CC's startSSE now calls the
            // streaming Service launcher (no more repository.connectSSE).
            streamingServiceLauncher = cn.vectory.ocdroid.RecordingStreamingServiceLauncher(),
        )
        // §unread-soak: real controller for parity with MainViewModelTestBase.
        val unreadSoakController = cn.vectory.ocdroid.ui.controller.UnreadSoakController(
            appLifecycleMonitor = appLifecycleMonitor,
            scope = appScope,
            store = store,
            autoStart = false,
        )
        return AppCore(
            store,
            repository,
            settingsManager,
            hostProfileStore,
            trafficTracker,
            appLifecycleMonitor,
            cn.vectory.ocdroid.data.repository.ServerCompatProfile(),
            effectBus,
            mockk<Context>(relaxed = true),
            foregroundCatchUpController,
            composerController,
            sessionSwitcher,
            hostProfileController,
            sessionSyncCoordinator,
            connectionCoordinator,
            // §Stage-D2: token-stream coordinator (not exercised by ForkSessionTest).
            cn.vectory.ocdroid.ui.controller.sse.TokenStreamCoordinator(
                scope = appScope,
                slices = store.slices,
                streamProvider = { _, _ -> kotlinx.coroutines.flow.emptyFlow() },
                triggerSinceFetch = { _, _ -> },
            ),
            unreadSoakController,
            // §review-fix #1: fp provider (same as MainViewModelTestBase).
            { hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id } },
            appScope,
            // CP1 (notify Phase-0): single connection-identity store.
            identityStore,
            // CP3 (notify Phase-0): SSE event stream + bridge.
            sseEventStream,
            sseEventBridge,
            // T13 (round-2 review fix): ProcessStatusPoller (relaxed mock —
            // ForkSessionTest does not exercise backoff wiring).
            mockk<cn.vectory.ocdroid.service.streaming.ProcessStatusPoller>(relaxed = true),
        )
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

        val core = createCore()
        val sessionVM = SessionViewModel(core)
        // §R-17 batch2: write slices directly (no AppState mirror dependency).
        // §R18 Phase 4 (P0-9): SharedStateStore's per-slice StateFlow is read-only;
        // funnel writes through mutateXxx.
        core.store.mutateSessionList { it.copy(sessions = listOf(parentSession)) }
        core.store.mutateChat { it.copy(currentSessionId = "parent-1") }

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
        // §R18 Phase 4 (P0-9): chatFlow is a read-only StateFlow; write via mutateChat.
        core.store.mutateChat { it.copy(currentSessionId = "parent-1") }

        core.uiEvents.test {
            sessionVM.forkSession("parent-1", "msg-99")
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue("error event should be emitted on failure", event is cn.vectory.ocdroid.ui.UiEvent.Error)
            // §R18 Phase 2-G: UiEvent.Error now carries @StringRes resId + args.
            // The fork-failure path emits R.string.error_fork_session_failed with
            // the resolved exception message as the single arg.
            val errEvent = event as cn.vectory.ocdroid.ui.UiEvent.Error
            assertEquals(cn.vectory.ocdroid.R.string.error_fork_session_failed, errEvent.resId)
            val arg = errEvent.args.single().toString()
            assertTrue("error should mention fork", arg.contains("fork", ignoreCase = true))
        }
        assertEquals("parent-1", sessionVM.chatFlow.value.currentSessionId)
    }
}
