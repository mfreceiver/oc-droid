package cn.vectory.ocdroid

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
 * Slice writes use the test-only [AppState] shim ([updateState] /
 * [AppCore.state]) defined alongside this class — that's why historical
 * `updateState { copy(field = ...) }` calls keep working post-split.
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

        every { repository.connectSSE() } returns emptyFlow()
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())
        coEvery { repository.getSessionsForDirectory(any(), any()) } returns Result.success(emptyList())
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        coEvery { repository.getMessages(any(), any()) } returns Result.success(emptyList())
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getPendingPermissions() } returns Result.success(emptyList())
        coEvery { repository.getAgents() } returns Result.success(emptyList())
        coEvery { repository.getProviders() } returns Result.success(ProvidersResponse())
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        coEvery { repository.getPendingQuestions() } returns Result.success(emptyList())
    }

    @After
    open fun tearDown() {
        unmockkAll()
    }

    /** Builds a fresh [AppCore] for a single test. */
    protected fun createCore(): AppCore {
        val core = AppCore(
            SharedStateStore(),
            repository,
            settingsManager,
            hostProfileStore,
            trafficTracker,
            appLifecycleMonitor,
            cn.vectory.ocdroid.data.repository.ServerCompatProfile(),
            SharedEffectBus(),
        )
        // §R-17 batch3e: side-channel Error/Success UiEvents into a per-core
        // ring buffer so the test-only [aggregateState] can surface them as
        // `state.value.error` / `state.value.successMessage` (matching the
        // historical AppState API). The collector runs on the test's Main
        // dispatcher (unconfined so emissions reach the buffer synchronously).
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
