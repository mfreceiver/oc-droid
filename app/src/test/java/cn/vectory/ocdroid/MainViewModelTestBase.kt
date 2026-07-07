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
    protected lateinit var core: AppCore

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
        every { settingsManager.getModelForSession(any()) } returns null
        every { settingsManager.setModelForSession(any(), any(), any()) } just runs

        every { repository.connectSSE(any()) } returns emptyFlow()
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())
        coEvery { repository.getSessionsForDirectory(any(), any()) } returns Result.success(emptyList())
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        coEvery { repository.getMessages(any(), any()) } returns Result.success(emptyList())
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns Result.success(MessagesPage(emptyList(), null))
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
        )
        val sessionSwitcher = cn.vectory.ocdroid.ui.controller.SessionSwitcher(
            store = store,
            settingsManager = settingsManager,
            repository = repository,
            effects = effectBus,
            currentServerGroupFp = { hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id } },
        )
        val cacheRepository = io.mockk.mockk<cn.vectory.ocdroid.data.cache.CacheRepository>(relaxed = true)
        // R-20 Phase 1: stub verifyAndLoad / verifyFingerprint to return
        // UnknownColdStart by default — relaxed mockk returns null for
        // non-primitive return types, which would crash AppCore's
        // dispatchSessionEffect VerifyAndHydrate `when` (sealed interface
        // without a null branch). Tests that need a different result
        // re-stub per-test.
        io.mockk.coEvery {
            cacheRepository.verifyAndLoad(any(), any(), any())
        } returns cn.vectory.ocdroid.data.cache.HydrateResult.UnknownColdStart
        io.mockk.coEvery {
            cacheRepository.verifyFingerprint(any(), any(), any())
        } returns cn.vectory.ocdroid.data.cache.FingerprintResult.UnknownColdStart
        val hostProfileController = cn.vectory.ocdroid.ui.controller.HostProfileController(
            scope = appScope,
            slices = store.slices,
            hostProfileStore = hostProfileStore,
            repository = repository,
            settingsManager = settingsManager,
            trafficTracker = trafficTracker,
            effects = effectBus,
            currentServerGroupFp = { hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id } },
            appContext = appContext,
            cacheRepository = cacheRepository,
        )
        val sessionSyncCoordinator = cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator(
            scope = appScope,
            slices = store.slices,
            settingsManager = settingsManager,
            effects = effectBus,
            currentServerGroupFp = { hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id } },
            // R-20 Phase 1 (C4): wire the cache mock so message.updated new-
            // insert appends reach the (relaxed) mock. Tests that need to
            // verify the append call re-stub per-test.
            cacheRepository = cacheRepository,
        )
        val connectionCoordinator = cn.vectory.ocdroid.ui.controller.ConnectionCoordinator(
            scope = appScope,
            slices = store.slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effectBus,
            serverCompatProfile = cn.vectory.ocdroid.data.repository.ServerCompatProfile(),
        )
        val fpProvider: () -> String = { hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id } }
        // R-20 Phase 2: gap-fill coordinator (real instance; the relaxed-mock
        // cacheRepository backs its openGap / appendOlderSlice / gapsOf calls).
        // §fix-#3: pass the SAME fp provider every controller uses so the
        // coordinator's compound-key guards resolve identically to production.
        val gapFillCoordinator = cn.vectory.ocdroid.ui.chat.GapFillCoordinator(
            repository = repository,
            cacheRepository = cacheRepository,
            currentServerGroupFp = fpProvider,
        )
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
            gapFillCoordinator,
            cacheRepository,
            // §review-fix #1: same fp provider every controller uses.
            fpProvider,
            appScope,
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

    /**
     * R-20 Phase 1: re-stub the relaxed-mock [AppCore.cacheRepository] on [core]
     * to behave like an in-memory persistent cache (putSessionWindow writes;
     * verifyAndLoad reads with the same 3-state semantics as the real impl).
     *
     * Used by tests that need to verify the Phase 1 verify-before-hydrate
     * round-trip (e.g. cached messages survive A→B→A) without dragging in
     * Robolectric/Room. The default relaxed mock returns UnknownColdStart for
     * every verifyAndLoad, which short-circuits the handler to a cold-start
     * REST fetch — fine for most tests but not for the cache-persistence
     * regressions that need a Verified window to land.
     *
     * Call AFTER [createCore] / [newCore] so the stubs install on the core's
     * cacheRepository instance.
     */
    protected fun installInMemoryPersistentCache(core: AppCore) {
        // (fp, sessionId) -> (storedCreatedAt, window)
        val cache = mutableMapOf<Pair<String, String>, Pair<Long?, cn.vectory.ocdroid.ui.CachedSessionWindow>>()
        io.mockk.coEvery {
            core.cacheRepository.putSessionWindow(any(), any(), any(), any(), any())
        } answers {
            val fp = firstArg<String>()
            val sid = secondArg<String>()
            val createdAt = thirdArg<Long?>()
            val window = arg<cn.vectory.ocdroid.ui.CachedSessionWindow>(4)
            cache[fp to sid] = createdAt to window
        }
        io.mockk.coEvery {
            core.cacheRepository.verifyAndLoad(any(), any(), any())
        } answers {
            val fp = firstArg<String>()
            val sid = secondArg<String>()
            val createdAt = thirdArg<Long?>()
            val stored = cache[fp to sid]
            when {
                createdAt == null -> cn.vectory.ocdroid.data.cache.HydrateResult.UnknownColdStart
                stored == null -> cn.vectory.ocdroid.data.cache.HydrateResult.UnknownColdStart
                stored.first != createdAt -> cn.vectory.ocdroid.data.cache.HydrateResult.MismatchEvicted
                else -> cn.vectory.ocdroid.data.cache.HydrateResult.Verified(stored.second)
            }
        }
        io.mockk.coEvery {
            core.cacheRepository.verifyFingerprint(any(), any(), any())
        } answers {
            val fp = firstArg<String>()
            val sid = secondArg<String>()
            val createdAt = thirdArg<Long?>()
            val stored = cache[fp to sid]
            when {
                createdAt == null -> cn.vectory.ocdroid.data.cache.FingerprintResult.UnknownColdStart
                stored == null -> cn.vectory.ocdroid.data.cache.FingerprintResult.UnknownColdStart
                stored.first != createdAt -> cn.vectory.ocdroid.data.cache.FingerprintResult.MismatchEvicted
                else -> cn.vectory.ocdroid.data.cache.FingerprintResult.Verified
            }
        }
    }
}
