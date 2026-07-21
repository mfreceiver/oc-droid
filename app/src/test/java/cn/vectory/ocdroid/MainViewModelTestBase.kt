package cn.vectory.ocdroid

import android.content.Context
import android.util.Log
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.ProvidersResponse
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.MessagesPage
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.di.AppLifecycleMonitor
import cn.vectory.ocdroid.ui.AppCore
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.ThemeMode
import cn.vectory.ocdroid.util.TrafficTracker
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule

/**
 * §R-17 batch3e: shared base for the 6 per-domain ViewModel test files split
 * out of the former [MainViewModelTest]. Owns the mocking setup that all
 * domain tests need (repository / settings / host-profile / traffic /
 * AppLifecycle mocks) and constructs an [AppCore] (the shared engine).
 *
 * §R18 Phase 4 (P2-3): slice writes go directly through the AppCore
 * `writeXxx { it.copy(field = ...) }` helpers (the former test-only
 * `AppState` shim — `updateState` / `AppCore.state` — was deleted in this
 * phase). UiEvent Error/Success assertions read the ring buffers populated
 * by [createCore] via [AppCore.recentTestErrors] / [AppCore.recentTestSuccesses].
 *
 * Each domain test file extends this class, adds the domain VM it needs
 * (e.g. `val chatVM = ChatViewModel(core)`), and writes tests that call VM
 * methods (NOT legacy `AppCore` shim extensions — those were deleted in
 * batch3e alongside this split). AppCore internals that have no VM equivalent
 * (`handleSSEEvent`, `store`, `peekSessionWindow`, `uiEvents`, `navFlow`)
 * are reached through the `core` field directly.
 */
abstract class MainViewModelTestBase {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    protected lateinit var repository: OpenCodeRepository
    protected lateinit var settingsManager: SettingsManager
    protected lateinit var hostProfileStore: HostProfileStore
    protected lateinit var trafficTracker: TrafficTracker
    protected lateinit var appLifecycleMonitor: AppLifecycleMonitor
    protected lateinit var identityStore: cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
    protected lateinit var core: AppCore
    /**
     * CP9 (notify Phase-0 switchover): the recording launcher wired into the
     * core's ConnectionCoordinator. Tests assert on [RecordingStreamingServiceLauncher.callCount]
     * instead of `repository.connectSSE` after the switchover (the SSE
     * collector moved to the Service; CC's startSSE now calls the launcher).
     */
    protected var streamingServiceLauncher: RecordingStreamingServiceLauncher = RecordingStreamingServiceLauncher()
        private set

    /**
     * T13 (round-2 review fix): the relaxed-mock poller wired into the core.
     * Tests that exercise [ControllerEffect.RequestPollerBackoff] /
     * [ControllerEffect.ResetPollerBackoff] dispatch verify on this mock
     * via `verify { processStatusPoller.scheduleBackoff(any()) }`.
     */
    protected lateinit var processStatusPoller: cn.vectory.ocdroid.service.streaming.ProcessStatusPoller
        private set

    @Before
    open fun setUp() {
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
        // getAgentForSession / setAgentForSession / getModelForSession /
        // setModelForSession was removed here (those APIs were deleted).

        every { repository.connectSSE(any()) } returns emptyFlow()
        // C-D3 token guard: relaxed mock's isSlimCommitTokenCurrent defaults
        // to false (MockK Boolean default). Stub it so the coordinator's token
        // path works. Do NOT stub captureSlimCommitToken explicitly — the
        // relaxed mock auto-answers it, and explicit every blocks cause MockK
        // tracking issues with verify(exactly = N) in downstream tests.
        every { repository.isSlimCommitTokenCurrent(any()) } returns true
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())
        coEvery { repository.getSessionsForDirectory(any(), any()) } returns Result.success(emptyList())
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        coEvery { repository.getMessages(any(), any()) } returns Result.success(emptyList())
        coEvery { repository.getMessagesPaged(any(), any(), any(), any()) } returns Result.success(MessagesPage(emptyList(), null))
        // §empty-window-fix: default stub for the unanchored cold-load path
        // (VerifyAndHydrate cold-load branch). Same empty-page default as the
        // anchored stub above so tests that don't care about the slim
        // watermark bypass still drive their coVerify targets.
        coEvery { repository.getMessagesPagedUnanchored(any(), any(), any(), any()) } returns Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getPendingPermissions() } returns Result.success(emptyList())
        coEvery { repository.getAgents() } returns Result.success(emptyList())
        coEvery { repository.getProviders() } returns Result.success(ProvidersResponse())
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        coEvery { repository.getPendingQuestions(any()) } returns Result.success(emptyList())
    }

    @After
    open fun tearDown() {
        unmockkAll()
    }

    /** Builds a fresh [AppCore] for a single test. */
    protected fun createCore(): AppCore {
        // §R18 Phase 2-G: AppCore now takes a `@ApplicationContext Context` to
        // resolve UiEvent.Error's @StringRes resId for the AppLifecycleMonitor
        // notification path. The monitor itself is mocked (relaxed) so the
        // resolved text is unused; a relaxed Context mock satisfies the
        // constructor without dragging Robolectric into every test.
        val appContext = mockk<Context>(relaxed = true)
        // CP9: each test gets a fresh recording launcher.
        streamingServiceLauncher = RecordingStreamingServiceLauncher()
        // §R-19 Sprint 3 P2-5: AppCore's 5 controllers + the @UiApplicationScope
        // CoroutineScope are now Hilt @Provides-bound in production. In unit
        // tests (no Hilt container) we still construct AppCore directly, so
        // the 5 controllers + scope are built here with the SAME wiring the
        // production [cn.vectory.ocdroid.di.ControllerModule] uses. This keeps
        // the per-test instance graph identical to production (same singleton
        // scope per core instance; same controller ownership) while letting
        // the test mock the underlying repository / settingsManager / etc.
        val store = SharedStateStore()
        val effectBus = SharedEffectBus()
        // CP1 (notify Phase-0): the single connection-identity store, shared
        // by CC / SSC / HPC + AppCore (same wiring as ControllerModule).
        identityStore = cn.vectory.ocdroid.service.identity.ConnectionIdentityStore()
        val appScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() +
                kotlinx.coroutines.Dispatchers.Main.immediate
        )
        // CP3 (notify Phase-0): the process-wide SSE event stream + bridge.
        val sseEventStream = cn.vectory.ocdroid.service.events.SseEventStream()
        val sseEventBridge = cn.vectory.ocdroid.service.bridge.SseEventBridge(appScope)
        val foregroundCatchUpController = cn.vectory.ocdroid.ui.controller.ForegroundCatchUpController(
            appLifecycleMonitor = appLifecycleMonitor,
            scope = appScope,
            store = store,
            settingsManager = settingsManager,
            effects = effectBus,
        )
        // §unread-soak: real controller so its init subscribes to the
        // foreground flow + the sweep loop can run on the test's appScope.
        val unreadSoakController = cn.vectory.ocdroid.ui.controller.UnreadSoakController(
            appLifecycleMonitor = appLifecycleMonitor,
            scope = appScope,
            store = store,
            autoStart = false,
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
            currentServerGroupFp = { hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id } },
        )
        val hostProfileController = cn.vectory.ocdroid.ui.controller.HostProfileController(
            scope = appScope,
            slices = store.slices,
            hostProfileStore = hostProfileStore,
            repository = repository,
            settingsManager = settingsManager,
            trafficTracker = trafficTracker,
            effects = effectBus,
            currentServerGroupFp = { hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id } },
            identityStore = identityStore,
        )
        val sessionSyncCoordinator = cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator(
            scope = appScope,
            slices = store.slices,
            settingsManager = settingsManager,
            effects = effectBus,
            currentServerGroupFp = { hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id } },
            // CP1 (notify Phase-0): single connection-identity store.
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
            // CP2 (notify Phase-0): delegate TOFU state to the shared bootstrap
            // coordinator so the delegation is exercised in tests too.
            bootstrapCoordinator = cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator(),
            // CP9 (notify Phase-0 switchover): CC's startSSE now delegates to
            // a fake launcher (records ensureStarted calls; tests assert on
            // the call count instead of repository.connectSSE). The real
            // Android impl is Hilt-bound in production.
            streamingServiceLauncher = streamingServiceLauncher,
            // CP9: cancelSse / cancelSseForReconfigure route through the
            // lifecycle coordinator; pass null here (CC's delegates are
            // no-ops without it). Tests that exercise the teardown path
            // construct their own coordinator with a real coordinator.
            streamingLifecycleCoordinator = null,
        )
        val fpProvider: () -> String = { hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id } }
        val core = AppCore(
            store,
            repository,
            settingsManager,
            hostProfileStore,
            trafficTracker,
            appLifecycleMonitor,
            cn.vectory.ocdroid.data.repository.ServerCompatProfile(),
            effectBus,
            appContext,
            foregroundCatchUpController,
            composerController,
            sessionSwitcher,
            hostProfileController,
            sessionSyncCoordinator,
            connectionCoordinator,
            unreadSoakController,
            // §review-fix #1: same fp provider every controller uses.
            fpProvider,
            appScope,
            // CP1 (notify Phase-0): single connection-identity store.
            identityStore,
            // CP3 (notify Phase-0): SSE event stream + bridge.
            sseEventStream,
            sseEventBridge,
            // T13 (round-2 review fix): ProcessStatusPoller — relaxed mock
            // so AppCore's RequestPollerBackoff / ResetPollerBackoff
            // dispatch has somewhere to land. Tests that exercise the
            // backoff wiring (AppCoreDispatcherTest) verify on this mock.
            mockk<cn.vectory.ocdroid.service.streaming.ProcessStatusPoller>(relaxed = true).also {
                processStatusPoller = it
            },
        )
        // §R-17 batch3e → §R18 Phase 4: side-channel Error/Success UiEvents
        // into a per-core ring buffer so tests can read the most-recent event
        // via [AppCore.recentTestErrors] / [AppCore.recentTestSuccesses]
        // (former `state.value.error` / `state.value.successMessage` API
        // was removed with the TestAppStateShim). The collector runs on the
        // test's Main dispatcher (unconfined so emissions reach the buffer
        // synchronously).
        core.installTestUiEventCollector(
            kotlinx.coroutines.CoroutineScope(mainDispatcherRule.dispatcher)
        )
        return core
    }

    /** Convenience: build a core AND assign it to [core] so subclass tests
     *  can reference the shared field directly. */
    protected fun newCore(): AppCore {
        core = createCore()
        return core
    }

    /** Routes an SSE event to AppCore's internal handler. */
    protected fun handleSse(viewModel: AppCore, event: cn.vectory.ocdroid.data.model.SSEEvent) {
        viewModel.handleSSEEvent(event)
    }
}
