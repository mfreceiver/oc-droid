package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.BasicAuthConfig
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.ComposerState
import cn.vectory.ocdroid.ui.ConnectionPhase
import cn.vectory.ocdroid.ui.ConnectionState
import cn.vectory.ocdroid.ui.FileState
import cn.vectory.ocdroid.ui.HostState
import cn.vectory.ocdroid.ui.SessionListState
import cn.vectory.ocdroid.ui.SettingsState
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.TrafficState
import cn.vectory.ocdroid.ui.TunnelActivationState
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.UnreadState
import cn.vectory.ocdroid.ui.util.HttpImageHolder
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.TrafficTracker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R-16 M3 → R-17 batch3b: independent unit test for [HostProfileController].
 *
 * Zero reflection — the controller is driven entirely through its public API
 * (saveHostProfile / duplicateHostProfile / deleteHostProfile / selectHostProfile /
 * configureServer / configureRepositoryForProfile / activateTunnelForCurrentHost /
 * resetLocalDataAndResync / accessors) and asserted via:
 *  - the emitted [ControllerEffect]s on a real [SharedEffectBus] (a coroutine
 *    in the test scope drains every effect into [collectedEffects] + every
 *    UiEvent into [recordedEvents]), and
 *  - a mockk [TrafficTracker] for the inline resetTrafficTracker path.
 *
 * Heavy service dependencies (HostProfileStore / OpenCodeRepository /
 * SettingsManager) are mockk stubs; SliceFlows are real so the controller's
 * state writes are observable.
 *
 * Note: `testConnection` itself stays in [ConnectionCoordinator] (see
 * controller kdoc), so the state-machine coverage here targets the
 * tunnel-activation flow (Idle → Loading → Success/Error), which is the
 * equivalent isConnecting-style progression owned by this controller.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HostProfileControllerTest {

    private lateinit var slices: SliceFlows
    private lateinit var store: HostProfileStore
    private lateinit var repository: OpenCodeRepository
    private lateinit var settingsManager: SettingsManager
    private lateinit var trafficTracker: TrafficTracker
    private lateinit var effects: SharedEffectBus
    private lateinit var collectedEffects: MutableList<ControllerEffect>
    private lateinit var collectorScope: kotlinx.coroutines.CoroutineScope
    private lateinit var scope: TestScope
    private lateinit var controller: HostProfileController
    /**
     * §R-17 batch2 step e final: in-test fixture carrying the prior snapshot
     * so successive `seed { ... }` calls compose against the prior state
     * (tests routinely call seed multiple times). Production no longer has an
     * AppState mirror; this fixture exists only to drive the seed() transform
     * chain.
     */
    private var appStateFixture: SeedFixture = SeedFixture()
    /** §R-17 batch2 / §batch 3b: captures UiEvents emitted on effects.uiEvents. */
    private val recordedEvents = mutableListOf<UiEvent>()

    // Real data-class fixtures (avoid relaxed-mock proxies for value types).
    // R-20 Phase 1: explicit serverGroupFp so the two profiles are DIFFERENT
    // groups (selectHostProfile's 4-step异组 path needs this to fire). Without
    // explicit fps both default to "" → same group → the purgePerHostState
    // preserveServerGroupData branch keeps server data, breaking tests that
    // expect the full purge (currentSessionId null, ClearSessionWindowCache
    // emitted, etc).
    private val profileA = HostProfile(id = "p-A", name = "Host A", serverUrl = "http://a:4096", serverGroupFp = "g-A")
    private val profileB = HostProfile(
        id = "p-B",
        name = "Host B",
        serverUrl = "http://b:4096",
        basicAuth = BasicAuthConfig(username = "user-b", passwordId = "p-B"),
        allowInsecureConnections = true,
        serverGroupFp = "g-B"
    )

    @Before
    fun setUp() {
        // HostProfileController.activateTunnelForCurrentHost calls Log.d/Log.e.
        mockkStatic(Log::class)
        appStateFixture = SeedFixture()
        // §R18 Phase 4 (P0-9): SliceFlows is built via a SharedStateStore; the
        // bundle exposes read-only StateFlow views + per-slice mutateXxx.
        val stateStore = cn.vectory.ocdroid.ui.SharedStateStore()
        slices = stateStore.slices
        store = mockk(relaxed = true)
        repository = mockk(relaxed = true)
        settingsManager = mockk(relaxed = true)
        trafficTracker = mockk(relaxed = true)
        effects = SharedEffectBus()
        collectedEffects = mutableListOf()
        recordedEvents.clear()
        // §batch 3b: dual-scope setup. [scope] (StandardTestDispatcher) drives
        // the controller — its scope.launch bodies are queued and drained via
        // [runPending] (advanceUntilIdle), preserving the
        // `Loading set synchronously before the async probe` invariant the
        // tunnel test relies on. [collectorScope] (UnconfinedTestDispatcher)
        // drains the effects bus collector eagerly so emissions land in
        // [collectedEffects] synchronously when the controller calls tryEmit.
        scope = TestScope()
        collectorScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + UnconfinedTestDispatcher()
        )
        collectorScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { effects.effectsConsumed.toList(collectedEffects) }
        collectorScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { effects.uiEventsConsumed.toList(recordedEvents) }
        controller = HostProfileController(
            scope = scope,
            slices = slices,
            hostProfileStore = store,
            repository = repository,
            settingsManager = settingsManager,
            trafficTracker = trafficTracker,
            effects = effects,
            currentServerGroupFp = { "test-fp" },
            appContext = io.mockk.mockk<android.content.Context>(relaxed = true),
            cacheRepository = io.mockk.mockk<cn.vectory.ocdroid.data.cache.CacheRepository>(relaxed = true),
        )
        // Default store seeding; tests re-stub as needed (last stub wins).
        seedStore(listOf(profileA, profileB), currentId = "p-A")
        // #12: HttpImageHolder 是 object 单例，跨单测会残留 allowInsecure 状态——
        // 每个用例前重置为默认 (false)，确保 updateSsl 的 effective/no-op 行为确定。
        HttpImageHolder.resetTestState()
    }

    @After
    fun tearDown() {
        unmockkAll()
        // #12: 同样在 teardown 重置，避免单例状态泄漏到同 JVM 内其它测试类。
        HttpImageHolder.resetTestState()
    }

    /** Re-stubs store.profiles()/currentProfile() to deterministic fixtures. */
    private fun seedStore(profiles: List<HostProfile>, currentId: String) {
        val current = profiles.first { it.id == currentId }
        every { store.profiles() } returns profiles
        every { store.currentProfile() } returns current
    }

    /** Runs any coroutines launched on the controller's TestScope. */
    private fun runPending() {
        scope.testScheduler.advanceUntilIdle()
    }

    // R-17 batch2 step e final: seed slices directly from a SeedFixture.
    // The fixture carries the prior snapshot so successive `seed { ... }` calls
    // compose against the prior state (tests routinely call seed multiple
    // times). Production no longer has an AppState mirror; this exists only to
    // drive the test transform chain.
    //
    // §R18 Phase 4 (P0-9): per-slice StateFlow views are read-only; funnel
    // every seed write through the matching mutateXxx helper.
    private fun seed(transform: (SeedFixture) -> SeedFixture) {
        appStateFixture = transform(appStateFixture)
        val s = appStateFixture
        slices.mutateConnection {
            ConnectionState(
                isConnected = s.isConnected,
                isConnecting = s.isConnecting,
                serverVersion = s.serverVersion,
                connectionPhase = s.connectionPhase,
                tunnelActivationState = s.tunnelActivationState
            )
        }
        slices.mutateTraffic {
            TrafficState(
                trafficSent = s.trafficSent,
                trafficReceived = s.trafficReceived
            )
        }
        slices.mutateComposer {
            ComposerState(
                inputText = s.inputText,
                imageAttachments = s.imageAttachments,
                sendingSessionIds = s.sendingSessionIds,
                draftWorkdir = s.draftWorkdir
            )
        }
        slices.mutateFile {
            FileState(
                filePathToShowInFiles = s.filePathToShowInFiles,
                filePreviewOriginRoute = s.filePreviewOriginRoute,
                fileBrowserOpen = s.fileBrowserOpen,
                fileBrowserWorkdir = s.fileBrowserWorkdir
            )
        }
        slices.mutateSettings {
            SettingsState(
                themeMode = s.themeMode,
                markdownFontSizes = s.markdownFontSizes,
                selectedAgentName = s.selectedAgentName,
                agents = s.agents,
                providers = s.providers,
                availableCommands = s.availableCommands,
                disabledModels = s.disabledModels,
                uiFontScale = s.uiFontScale,
                uiContentScale = s.uiContentScale
            )
        }
        slices.mutateChat {
            it.copy(
                currentSessionId = s.currentSessionId,
                messages = s.messages,
                partsByMessage = s.partsByMessage,
                streamingPartTexts = s.streamingPartTexts,
                streamingReasoningPart = s.streamingReasoningPart,
                olderMessagesCursor = s.olderMessagesCursor,
                hasMoreMessages = s.hasMoreMessages,
                isLoadingMessages = s.isLoadingMessages,
                gapMarkers = s.gapMarkers,
                staleNotice = s.staleNotice,
                currentModel = s.currentModel
            )
        }
        slices.mutateSessionList {
            SessionListState(
                sessions = s.sessions,
                sessionStatuses = s.sessionStatuses,
                expandedSessionIds = s.expandedSessionIds,
                loadedSessionLimit = s.loadedSessionLimit,
                hasMoreSessions = s.hasMoreSessions,
                isLoadingMoreSessions = s.isLoadingMoreSessions,
                isRefreshingSessions = s.isRefreshingSessions,
                pendingPermissions = s.pendingPermissions,
                pendingQuestions = s.pendingQuestions,
                childSessions = s.childSessions,
                directorySessions = s.directorySessions,
                openSessionIds = s.openSessionIds,
                sessionTodos = s.sessionTodos
            )
        }
        slices.mutateUnread {
            UnreadState(
                unreadSessions = s.unreadSessions,
                tempClearedUnread = s.tempClearedUnread,
                lastViewedTime = s.lastViewedTime
            )
        }
        slices.mutateHost {
            HostState(
                hostProfiles = s.hostProfiles,
                currentHostProfileId = s.currentHostProfileId
            )
        }
    }

    // ── Accessors ──────────────────────────────────────────────────────────

    @Test
    fun `getHostProfiles delegates to hostProfileStore_profiles`() {
        assertEquals(listOf(profileA, profileB), controller.getHostProfiles())
        verify { store.profiles() }
    }

    @Test
    fun `currentHostProfile delegates to hostProfileStore_currentProfile`() {
        assertEquals(profileA, controller.currentHostProfile())
        verify { store.currentProfile() }
    }

    @Test
    fun `getSavedConnectionSettings reads settingsManager and coerces nulls to blank`() {
        every { settingsManager.serverUrl } returns "http://x:1234"
        every { settingsManager.username } returns null
        every { settingsManager.password } returns "secret"

        val settings = controller.getSavedConnectionSettings()

        assertEquals("http://x:1234", settings.serverUrl)
        assertEquals("null username coerced to blank", "", settings.username)
        assertEquals("secret", settings.password)
    }

    // ── refreshHostProfileState ────────────────────────────────────────────

    @Test
    fun `refreshHostProfileState writes hostProfiles list and current id to AppState`() {
        seed { it.copy(hostProfiles = emptyList(), currentHostProfileId = null) }
        seedStore(listOf(profileB), currentId = "p-B")

        controller.refreshHostProfileState()

        assertEquals(listOf(profileB), slices.host.value.hostProfiles)
        assertEquals("p-B", slices.host.value.currentHostProfileId)
    }

    // ── saveHostProfile (three-state password contract) ────────────────────

    @Test
    fun `saveHostProfile normalizes basicAuth passwordId to the profile id`() {
        // Caller passes a basicAuth with an arbitrary passwordId; the controller
        // rewrites it to profile.id so the password is keyed by the profile.
        val incoming = profileB.copy(basicAuth = BasicAuthConfig(username = "user-b", passwordId = "incoming-id"))

        controller.saveHostProfile(incoming, basicAuthEdited = false)

        val savedSlot = mutableListOf<HostProfile>()
        verify { store.save(capture(savedSlot)) }
        assertEquals("p-B", savedSlot.single().basicAuth?.passwordId)
    }

    @Test
    fun `saveHostProfile writes basicAuth password when basicAuthEdited is true`() {
        controller.saveHostProfile(profileB, basicAuthPassword = "pw", basicAuthEdited = true)

        verify { settingsManager.setBasicAuthPassword("p-B", "pw") }
        verify { store.save(profileB) }
    }

    @Test
    fun `saveHostProfile skips basicAuth password write when basicAuthEdited is false`() {
        controller.saveHostProfile(profileB, basicAuthEdited = false)

        verify(exactly = 0) { settingsManager.setBasicAuthPassword(any(), any()) }
    }

    @Test
    fun `saveHostProfile clears orphaned password when basicAuth is null`() {
        // Defense-in-depth (#5): a profile with no basicAuth must never retain a
        // password — even though basicAuthEdited=false.
        controller.saveHostProfile(profileA, basicAuthEdited = false)

        verify { settingsManager.setBasicAuthPassword("p-A", "") }
    }

    @Test
    fun `saveHostProfile writes tunnel password when tunnelEdited is true`() {
        controller.saveHostProfile(
            profileB,
            basicAuthEdited = false,
            tunnelPassword = "tpw",
            tunnelEdited = true
        )

        verify { settingsManager.setTunnelPassword("p-B", "tpw") }
    }

    @Test
    fun `saveHostProfile persists profile and refreshes host profile state`() {
        controller.saveHostProfile(profileB, basicAuthEdited = false)

        verify { store.save(profileB) }
        // refreshHostProfileState re-reads the store:
        verify(atLeast = 1) { store.profiles() }
        assertEquals("p-A", slices.host.value.currentHostProfileId)
    }

    // ── saveHostProfile (#12: live reconfigure when allowInsecure toggled) ─

    @Test
    fun `saveHostProfile of active host reconfigures and force-reconnects when allowInsecure is toggled on`() {
        // #12: profileA is the current host (id p-A, allowInsecure=false).
        // Flip the toggle to true and save → must reconfigure REST/SSE/image
        // clients + reconnect so the new TLS trust policy applies live,
        // without needing a host switch or app restart.
        seed { it.copy(currentHostProfileId = "p-A") }
        val toggled = profileA.copy(allowInsecureConnections = true)

        controller.saveHostProfile(toggled, basicAuthEdited = false)

        // Reconfigured for the updated profileA with allowInsecure=true.
        verify { repository.configure(toggled.serverUrl, any(), any(), true) }
        // forceReconnect fired so the new SSL config takes effect immediately.
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.ForceReconnect>().size)
        // configureRepositoryForProfile cancels SSE once.
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.CancelSseForReconfigure>().size)
        // #12: image client 的 TLS 信任策略也同步切到 trust-all（与 REST/SSE 对称）。
        assertEquals(true, HttpImageHolder.lastUpdateSslAllowInsecure)
    }

    @Test
    fun `saveHostProfile of active host does NOT reconfigure when allowInsecure is unchanged`() {
        // Editing the active host's name (or any non-toggle field) must NOT
        // trigger a reconnect — zero regression for the toggle-unchanged case.
        seed { it.copy(currentHostProfileId = "p-A") }
        val renamed = profileA.copy(name = "Renamed A") // allowInsecure stays false

        controller.saveHostProfile(renamed, basicAuthEdited = false)
        scope.testScheduler.advanceUntilIdle()

        verify(exactly = 0) { repository.configure(any(), any(), any(), any()) }
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.ForceReconnect>().isEmpty())
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.CancelSseForReconfigure>().isEmpty())
    }

    @Test
    fun `saveHostProfile of non-current host does NOT reconfigure even if allowInsecure toggled`() {
        // Saving a NON-active host (profileB while p-A is current) must only
        // persist + refresh state, never touch the live connection — even when
        // its toggle changed. The change takes effect when that host is later
        // selected.
        seed { it.copy(currentHostProfileId = "p-A") }
        val toggled = profileB.copy(allowInsecureConnections = false) // was true

        controller.saveHostProfile(toggled, basicAuthEdited = false)
        scope.testScheduler.advanceUntilIdle()

        verify(exactly = 0) { repository.configure(any(), any(), any(), any()) }
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.ForceReconnect>().isEmpty())
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.CancelSseForReconfigure>().isEmpty())
    }

    @Test
    fun `saveHostProfile of active host reconfigures and force-reconnects when allowInsecure is toggled off`() {
        // #12 对称覆盖（R-01 "OFF 零回归" 核心承诺）：当前 host 初始
        // allowInsecure=true，保存改为 false → 必须重配 REST/SSE/image client
        // 并强制重连，使新的 TLS 信任策略即时生效——与 ON 路径完全对称。
        seedStore(listOf(profileA, profileB), currentId = "p-B") // profileB 当前且 allowInsecure=true
        seed { it.copy(currentHostProfileId = "p-B") }
        val toggledOff = profileB.copy(allowInsecureConnections = false) // true → false

        controller.saveHostProfile(toggledOff, basicAuthEdited = false)
        scope.testScheduler.advanceUntilIdle()

        // 重配为 allowInsecure=false（切回系统信任）。
        verify { repository.configure(toggledOff.serverUrl, any(), any(), false) }
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.ForceReconnect>().size)
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.CancelSseForReconfigure>().size)
        // #12: image client 信任策略同步切回 system trust。
        assertEquals(false, HttpImageHolder.lastUpdateSslAllowInsecure)
    }

    @Test
    fun `saveHostProfile of active host does NOT reconfigure when allowInsecure stays true`() {
        // #12 对称覆盖：当前 host 已 allowInsecure=true，保存仍 true（仅改 name）
        // → 不应触发重配/重连，与现有 unchanged(false→false) 用例对称。
        seedStore(listOf(profileA, profileB), currentId = "p-B")
        seed { it.copy(currentHostProfileId = "p-B") }
        val renamed = profileB.copy(name = "Renamed B") // allowInsecure 保持 true

        controller.saveHostProfile(renamed, basicAuthEdited = false)
        scope.testScheduler.advanceUntilIdle()

        verify(exactly = 0) { repository.configure(any(), any(), any(), any()) }
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.ForceReconnect>().isEmpty())
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.CancelSseForReconfigure>().isEmpty())
        // 未走 configureRepositoryForProfile → updateSsl 不应被调用（钩子保持 null）。
        assertNull(HttpImageHolder.lastUpdateSslAllowInsecure)
    }

    // ── #12: HttpImageHolder.updateSsl sync verification ───────────────────

    @Test
    fun `selectHostProfile propagates the selected host allowInsecure flag to HttpImageHolder`() {
        // #12: selectHostProfile(profileB allowInsecure=true) 走
        // configureRepositoryForProfile → HttpImageHolder.updateSsl(true)，
        // 保证自签名 HTTPS markdown 图片在 trust-all toggle ON 时可加载。
        every { store.select("p-B") } returns profileB
        every { settingsManager.basicAuthPassword("p-B") } returns "secret-b"

        controller.selectHostProfile("p-B")
        runPending()

        assertEquals(true, HttpImageHolder.lastUpdateSslAllowInsecure)
    }

    // ── duplicateHostProfile ───────────────────────────────────────────────

    @Test
    fun `duplicateHostProfile delegates to store and refreshes state`() {
        every { store.duplicate("p-A") } returns profileB

        controller.duplicateHostProfile("p-A")

        verify { store.duplicate("p-A") }
        // refreshHostProfileState runs, re-reading profiles
        verify(atLeast = 1) { store.profiles() }
    }

    // ── deleteHostProfile ──────────────────────────────────────────────────

    @Test
    fun `deleteHostProfile of non-current profile reconfigures for current host without purge or reconnect`() {
        seed { it.copy(currentHostProfileId = "p-A") }

        controller.deleteHostProfile("p-B")
        scope.testScheduler.advanceUntilIdle()

        // Reconfigures repository for the (unchanged) current host profileA.
        verify { repository.configure(profileA.serverUrl, any(), any(), profileA.allowInsecureConnections) }
        // NOT current → no purge, no reconnect.
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.ForceReconnect>().isEmpty())
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.ClearSessionWindowCache>().isEmpty())
        // configureRepositoryForProfile cancels SSE once.
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.CancelSseForReconfigure>().size)
    }

    @Test
    fun `deleteHostProfile of current profile purges per-host state, reconfigures for replacement, and force-reconnects`() {
        seed {
            it.copy(
            currentHostProfileId = "p-A",
            currentSessionId = "sess-old",
            sessions = listOf(cn.vectory.ocdroid.data.model.Session(id = "sess-old", directory = "/d")),
            openSessionIds = listOf("sess-old")
            )
        }
        // §review-fix #6: keep BOTH profiles in store.profiles() so
        // deleteHostProfile can capture deletedProfile.serverGroupFp. The
        // prior seedStore(listOf(profileB)) re-stub dropped profileA before
        // the production code could read its fp, leaving deletedFp=null and
        // skipping EvictGroup. Only re-stub currentProfile (the replacement
        // host the store picks after deletion).
        every { store.currentProfile() } returns profileB

        controller.deleteHostProfile("p-A")
        scope.testScheduler.advanceUntilIdle()

        // Reconfigured for the replacement profileB (with its allowInsecure flag).
        verify { repository.configure(profileB.serverUrl, any(), any(), profileB.allowInsecureConnections) }
        // wasCurrent → purge + forceReconnect.
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.ForceReconnect>().size)
        // §review-fix #5/#6: ClearSessionWindowCache was removed from
        // purgePerHostState (over-broad nuke). EvictGroup(g-A) replaces it
        // (group-scoped clear) — profileA's group has no remaining siblings
        // (profilesInGroup is a relaxed mock → empty), so EvictGroup fires.
        assertEquals(
            "EvictGroup(g-A) replaces ClearSessionWindowCache (group-scoped)",
            1,
            collectedEffects.filterIsInstance<ControllerEffect.EvictGroup>().size,
        )
        assertEquals("g-A", collectedEffects.filterIsInstance<ControllerEffect.EvictGroup>().single().serverGroupFp)
        // Per-host state purged.
        assertNull("currentSessionId purged", slices.chat.value.currentSessionId)
        assertTrue("sessions purged", slices.sessionList.value.sessions.isEmpty())
        assertTrue("openSessionIds purged", slices.sessionList.value.openSessionIds.isEmpty())
        // AppState now reflects the replacement current id.
        assertEquals("p-B", slices.host.value.currentHostProfileId)
    }

    // ── importHostProfile / exportHostProfile ──────────────────────────────

    @Test
    fun `importHostProfile on success delegates to store and refreshes state`() {
        val payload = "{\"name\":\"Imported\",\"serverURL\":\"http://imp:4096\"}"
        every { store.importJson(payload) } returns profileB

        val result = controller.importHostProfile(payload)

        assertTrue(result.isSuccess)
        assertEquals(profileB, result.getOrThrow())
        verify { store.importJson(payload) }
        verify(atLeast = 1) { store.profiles() }
    }

    @Test
    fun `importHostProfile on failure returns Result_failure without refresh`() {
        every { store.importJson(any()) } throws IllegalArgumentException("bad json")

        val result = controller.importHostProfile("not-json")

        assertTrue(result.isFailure)
        verify(exactly = 0) { store.profiles() }
    }

    @Test
    fun `exportHostProfile delegates to store`() {
        every { store.exportJson(profileB) } returns "exported-payload"

        assertEquals("exported-payload", controller.exportHostProfile(profileB))
        verify { store.exportJson(profileB) }
    }

    // ── selectHostProfile (host switch) ────────────────────────────────────

    @Test
    fun `selectHostProfile purges per-host session, message, and draft state`() {
        seed {
            it.copy(
            currentSessionId = "sess-old",
            messages = listOf(cn.vectory.ocdroid.data.model.Message(id = "m1", role = "user")),
            openSessionIds = listOf("sess-old"),
            unreadSessions = setOf("sess-old"),
            draftWorkdir = "/old/proj",
            availableCommands = listOf(cn.vectory.ocdroid.data.api.CommandInfo("cmd"))
            )
        }
        every { store.select("p-B") } returns profileB

        controller.selectHostProfile("p-B")
        runPending()

        assertNull("currentSessionId purged", slices.chat.value.currentSessionId)
        assertTrue("messages purged", slices.chat.value.messages.isEmpty())
        assertTrue("openSessionIds purged", slices.sessionList.value.openSessionIds.isEmpty())
        assertTrue("unread purged", slices.unread.value.unreadSessions.isEmpty())
        assertNull("draftWorkdir purged", slices.composer.value.draftWorkdir)
        assertTrue("availableCommands purged", slices.settings.value.availableCommands.isEmpty())
    }

    @Test
    fun `selectHostProfile reconfigures repository for the selected profile with its allowInsecure flag`() {
        every { store.select("p-B") } returns profileB
        every { settingsManager.basicAuthPassword("p-B") } returns "secret-b"

        controller.selectHostProfile("p-B")
        runPending()

        verify {
            repository.configure(
                profileB.serverUrl,
                profileB.basicAuth?.username,
                "secret-b",
                profileB.allowInsecureConnections
            )
        }
    }

    @Test
    fun `selectHostProfile drops session window cache and persisted session settings`() {
        every { store.select("p-B") } returns profileB

        controller.selectHostProfile("p-B")
        runPending()

        // §review-fix #5: ClearSessionWindowCache was removed from
        // purgePerHostState (over-broad nuke replaced by group-scoped
        // EvictGroup). profileA (g-A) → profileB (g-B) is cross-group →
        // EvictGroup(g-A) fires.
        assertEquals(
            "EvictGroup(g-A) replaces ClearSessionWindowCache",
            1,
            collectedEffects.filterIsInstance<ControllerEffect.EvictGroup>().size,
        )
        assertEquals("g-A", collectedEffects.filterIsInstance<ControllerEffect.EvictGroup>().single().serverGroupFp)
        // ClearSessionWindowCache is no longer emitted here.
        assertTrue(
            "ClearSessionWindowCache must NOT fire (EvictGroup handles group-scoped clear)",
            collectedEffects.filterIsInstance<ControllerEffect.ClearSessionWindowCache>().isEmpty(),
        )
        // §R18 Phase 2-F: currentSessionId is no longer written to
        // SettingsManager here (purgePerHostState clears it on the chat slice,
        // asserted below); the AppCore collector persists non-null changes only.
        assertNull("currentSessionId purged on chat slice", slices.chat.value.currentSessionId)
        verify { settingsManager.openSessionIds = emptyList() }
        verify { settingsManager.sessionCache = emptyList() }
        verify { settingsManager.currentWorkdir = null }
        verify { settingsManager.recentWorkdirs = emptyList() }
    }

    @Test
    fun `selectHostProfile forces a reconnect and the callback order is evict-group before sse-cancel before reconnect`() {
        every { store.select("p-B") } returns profileB

        controller.selectHostProfile("p-B")
        runPending()

        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.ForceReconnect>().size)
        // §review-fix #5: EvictGroup replaces ClearSessionWindowCache in the
        // ordering assertion. EvictGroup fires from selectHostProfile's
        // cross-group branch BEFORE configureRepositoryForProfile cancels SSE.
        val evictIdx = collectedEffects.indexOfFirst { it is ControllerEffect.EvictGroup }
        val cancelIdx = collectedEffects.indexOfFirst { it is ControllerEffect.CancelSseForReconfigure }
        val reconnectIdx = collectedEffects.indexOfFirst { it is ControllerEffect.ForceReconnect }
        assertTrue("EvictGroup recorded", evictIdx >= 0)
        assertTrue("cancelSseForReconfigure recorded", cancelIdx >= 0)
        assertTrue("forceReconnect recorded", reconnectIdx >= 0)
        assertTrue("EvictGroup fires before configure cancels SSE", evictIdx < cancelIdx)
        assertTrue("configure cancels SSE before reconnect", cancelIdx < reconnectIdx)
    }

    // ── configureServer (direct connection form) ───────────────────────────

    @Test
    fun `configureServer cancels SSE, writes settings, and configures repository with current profile allowInsecure`() {
        // currentHostProfile() returns profileB (allowInsecure=true).
        every { store.currentProfile() } returns profileB

        controller.configureServer("http://manual:4096", "mu", "mp")

        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.CancelSseForReconfigure>().size)
        verify { settingsManager.serverUrl = "http://manual:4096" }
        verify { settingsManager.username = "mu" }
        verify { settingsManager.password = "mp" }
        verify { repository.configure("http://manual:4096", "mu", "mp", profileB.allowInsecureConnections) }
        // #12: configureServer 也把信任策略同步给 image client（与 REST/SSE 对称）。
        assertEquals(true, HttpImageHolder.lastUpdateSslAllowInsecure)
    }

    @Test
    fun `configureServer defaults username and password to null`() {
        every { store.currentProfile() } returns profileA

        controller.configureServer("http://m:4096")

        verify { settingsManager.username = null }
        verify { settingsManager.password = null }
        verify { repository.configure("http://m:4096", null, null, any()) }
    }

    // ── configureRepositoryForProfile ──────────────────────────────────────

    @Test
    fun `configureRepositoryForProfile cancels SSE and configures with profile creds + allowInsecure`() {
        every { settingsManager.basicAuthPassword("p-B") } returns "secret-b"
        every { settingsManager.currentWorkdir } returns "/persisted/proj"

        controller.configureRepositoryForProfile(profileB)

        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.CancelSseForReconfigure>().size)
        verify {
            repository.configure(
                profileB.serverUrl,
                profileB.basicAuth?.username,
                "secret-b",
                profileB.allowInsecureConnections
            )
        }
        // §R18 Phase 2-E step 2: the repository.setCurrentDirectory call was
        // removed; directory routing now uses explicit `directory` parameters
        // sourced from settingsManager.currentWorkdir at each callsite.
    }

    @Test
    fun `configureRepositoryForProfile does not require a persisted workdir`() {
        // §R18 Phase 2-E step 2: with the global workdir fallback removed,
        // configureRepositoryForProfile no longer reads settingsManager.
        // currentWorkdir at all — it only reconfigures the repository and TLS.
        every { settingsManager.currentWorkdir } returns null

        controller.configureRepositoryForProfile(profileA)

        // Repository is still configured; no exception, no effect.
        verify {
            repository.configure(
                profileA.serverUrl,
                profileA.basicAuth?.username,
                any(),
                profileA.allowInsecureConnections
            )
        }
    }

    // ── activateTunnelForCurrentHost (state machine) ───────────────────────

    @Test
    fun `activateTunnel with no tunnelPasswordId sets error and TunnelActivationState_Error without launching`() {
        every { store.currentProfile() } returns profileA // tunnelPasswordId == null

        controller.activateTunnelForCurrentHost()

        assertEquals(TunnelActivationState.Error("未设置隧道密码"), slices.connection.value.tunnelActivationState)
        val errorEvent = recordedEvents.filterIsInstance<UiEvent.Error>().single()
        // §R18 Phase 2-G: tunnel-password-unset path emits the dedicated resId.
        assertEquals(R.string.error_tunnel_password_unset, errorEvent.resId)
        // No network call scheduled.
        coVerify(exactly = 0) { repository.activateTunnel(any(), any(), any()) }
    }

    @Test
    fun `activateTunnel with blank stored password sets error and TunnelActivationState_Error without launching`() {
        val profile = profileA.copy(tunnelPasswordId = "t1")
        every { store.currentProfile() } returns profile
        every { settingsManager.getTunnelPassword("t1") } returns "" // blank

        controller.activateTunnelForCurrentHost()

        assertEquals(TunnelActivationState.Error("隧道密码为空"), slices.connection.value.tunnelActivationState)
        coVerify(exactly = 0) { repository.activateTunnel(any(), any(), any()) }
    }

    @Test
    fun `activateTunnel success transitions Loading then Success and surfaces TUNNEL_SUCCESS_TOAST`() {
        val profile = profileA.copy(tunnelPasswordId = "t1")
        every { store.currentProfile() } returns profile
        every { settingsManager.getTunnelPassword("t1") } returns "real-pw"
        coEvery { repository.activateTunnel(profile.serverUrl, "real-pw", profile.allowInsecureConnections) } returns
            Result.success(Unit)

        controller.activateTunnelForCurrentHost()

        // Loading is set synchronously before the launched probe runs.
        assertEquals(
            "Loading state set synchronously before the async probe",
            TunnelActivationState.Loading,
            slices.connection.value.tunnelActivationState
        )
        runPending()

        assertEquals(TunnelActivationState.Success, slices.connection.value.tunnelActivationState)
        // §success-channel / §R-17 batch2: success now rides a UiEvent.Success
        // (NOT Error) so ChatScreen renders a positive toast.
        // §R18 Phase 2-G: success-toast text is now R.string.success_tunnel_activated.
        val successEvent = recordedEvents.filterIsInstance<UiEvent.Success>().single()
        assertEquals(R.string.success_tunnel_activated, successEvent.resId)
        coVerify { repository.activateTunnel(profile.serverUrl, "real-pw", profile.allowInsecureConnections) }
    }

    @Test
    fun `activateTunnel failure transitions Loading then Error with the exception message`() {
        val profile = profileA.copy(tunnelPasswordId = "t1")
        every { store.currentProfile() } returns profile
        every { settingsManager.getTunnelPassword("t1") } returns "real-pw"
        val failure = RuntimeException("boom-network")
        coEvery { repository.activateTunnel(any(), any(), any()) } returns Result.failure(failure)

        controller.activateTunnelForCurrentHost()
        runPending()

        val tunnelState = slices.connection.value.tunnelActivationState
        assertTrue("failure yields TunnelActivationState.Error", tunnelState is TunnelActivationState.Error)
        assertEquals("boom-network", (tunnelState as TunnelActivationState.Error).message)
        val errorEvent = recordedEvents.filterIsInstance<UiEvent.Error>().single()
        // §R18 Phase 2-G: tunnel-activation failure emits the dedicated resId
        // with the resolved exception message as the single arg.
        assertEquals(R.string.error_tunnel_activation_failed, errorEvent.resId)
        assertTrue("error toast includes exception message", errorEvent.args.single().toString().contains("boom-network"))
    }

    // ── resetLocalDataAndResync ────────────────────────────────────────────

    @Test
    fun `resetLocalDataAndResync preserves hostProfiles and currentHostProfileId but wipes everything else`() {
        seed {
            it.copy(
            hostProfiles = listOf(profileA, profileB),
            currentHostProfileId = "p-A",
            currentSessionId = "sess-x",
            inputText = "stale input",
            isConnected = true,
            serverVersion = "1.2.3",
            trafficSent = 999L,
            trafficReceived = 888L
            )
        }

        controller.resetLocalDataAndResync()

        // Preserved:
        assertEquals(listOf(profileA, profileB), slices.host.value.hostProfiles)
        assertEquals("p-A", slices.host.value.currentHostProfileId)
        // Wiped:
        assertNull("currentSessionId reset", slices.chat.value.currentSessionId)
        assertEquals("", slices.composer.value.inputText)
        assertNull("serverVersion reset", slices.connection.value.serverVersion)
        // Reconnecting slice values:
        assertFalse("isConnected false", slices.connection.value.isConnected)
        assertTrue("isConnecting true", slices.connection.value.isConnecting)
        assertEquals(ConnectionPhase.Reconnecting, slices.connection.value.connectionPhase)
        assertEquals(0L, slices.traffic.value.trafficSent)
        assertEquals(0L, slices.traffic.value.trafficReceived)
        assertEquals(TunnelActivationState.Idle, slices.connection.value.tunnelActivationState)
    }

    @Test
    fun `resetLocalDataAndResync wipes persisted local data and fires the full reset callback chain in order`() {
        controller.resetLocalDataAndResync()

        verify { settingsManager.clearAllLocalData() }
        // resetTrafficTracker is now an inline trafficTracker.reset() call.
        verify(exactly = 1) { trafficTracker.reset() }
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.ClearSessionWindowCache>().size)
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.CancelSseForReconfigure>().size)
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.ColdStartReconnect>().size)

        val resetTrafficIdx = collectedEffects.indexOfFirst { it is ControllerEffect.ClearSessionWindowCache }.let {
            // resetTrafficTracker runs BEFORE any effect emission (inline, synchronous);
            // we approximated it by the ClearSessionWindowCache position. Assert
            // ordering among effects instead — ClearSessionWindowCache (step 3)
            // before CancelSseForReconfigure (step 4) before ColdStartReconnect (step 8).
            it
        }
        val clearCacheIdx = collectedEffects.indexOfFirst { it is ControllerEffect.ClearSessionWindowCache }
        val cancelSseIdx = collectedEffects.indexOfFirst { it is ControllerEffect.CancelSseForReconfigure }
        val coldStartIdx = collectedEffects.indexOfFirst { it is ControllerEffect.ColdStartReconnect }
        assertTrue(clearCacheIdx < cancelSseIdx)
        assertTrue(cancelSseIdx < coldStartIdx)
        // sanity reference to keep the unused local named above meaningful.
        assertTrue(resetTrafficIdx >= 0)
    }

    @Test
    fun `resetLocalDataAndResync does NOT call forceReconnect`() {
        // resetLocalDataAndResync uses coldStartReconnect (3 retries), not the
        // throttled forceReconnect — asserting the invariant so the two paths
        // can't silently swap.
        controller.resetLocalDataAndResync()

        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.ForceReconnect>().isEmpty())
    }

    // ── saveHostProfile (S-1: live reconfigure when active host serverUrl changes) ─

    @Test
    fun `saveHostProfile of active host reconfigures and reconnects when serverUrl changes`() {
        // S-1: editing the current host's URL persisted the new value but left
        // the existing REST/SSE/image clients pointed at the OLD endpoint. The
        // fix mirrors the allowInsecure-toggle path: when the ACTIVE host's
        // serverUrl changes, clear old-URL model data + reconfigure + reconnect
        // so the new endpoint takes effect immediately. This also covers the
        // previously-uncovered clearModelDataForUrl call (line 356).
        seed { it.copy(currentHostProfileId = "p-A") }
        val moved = profileA.copy(serverUrl = "http://new-host:4096")

        controller.saveHostProfile(moved, basicAuthEdited = false)
        scope.testScheduler.advanceUntilIdle()

        // §bug5: old-URL model data dropped so the disable set does not orphan.
        verify { settingsManager.clearModelDataForUrl("http://a:4096") }
        // Reconfigured for the updated profileA at the new URL.
        verify { repository.configure(moved.serverUrl, any(), any(), moved.allowInsecureConnections) }
        // forceReconnect fired so clients rebuild against the new endpoint.
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.ForceReconnect>().size)
        // §disabled-models-consistency: per-host state reloaded for the new baseUrl.
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.HostProfileSwitched>().size)
        // configureRepositoryForProfile cancels SSE once.
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.CancelSseForReconfigure>().size)
    }

    @Test
    fun `saveHostProfile of active host with both URL and allowInsecure changed clears old-URL data once`() {
        // Composite: URL + toggle both change on the active host. The URL-clear
        // fires exactly once (guard is urlChanged-only), the reconfigure uses
        // the new URL + new allowInsecure, and both effects emit.
        seed { it.copy(currentHostProfileId = "p-A") }
        val moved = profileA.copy(serverUrl = "http://new:4096", allowInsecureConnections = true)

        controller.saveHostProfile(moved, basicAuthEdited = false)
        scope.testScheduler.advanceUntilIdle()

        verify(exactly = 1) { settingsManager.clearModelDataForUrl("http://a:4096") }
        verify { repository.configure("http://new:4096", any(), any(), true) }
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.ForceReconnect>().size)
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.HostProfileSwitched>().size)
    }

    // ── deleteHostProfile (§bug5: clearModelDataForUrl on the deleted active host) ─

    @Test
    fun `deleteHostProfile of current profile clears model data for the deleted host serverUrl`() {
        // §bug5: when the ACTIVE host is deleted, its per-URL model data must be
        // purged so it does not leak into the replacement host's identity (same-
        // URL collision or later re-add). The existing wasCurrent test re-stubs
        // profiles() to drop the deleted entry before deleteHostProfile reads
        // it, leaving deletedServerUrl=null and skipping clearModelDataForUrl.
        // Here we keep profileA in profiles() so its serverUrl is captured.
        seed { it.copy(currentHostProfileId = "p-A") }
        // Keep the default setUp seed ([profileA, profileB], current p-A) so
        // profiles().firstOrNull { id == "p-A" } resolves. Re-stub currentProfile
        // only — the replacement host picked by the store after deletion.
        every { store.currentProfile() } returns profileB

        controller.deleteHostProfile("p-A")
        scope.testScheduler.advanceUntilIdle()

        // Deleted active host's old URL model data purged.
        verify { settingsManager.clearModelDataForUrl("http://a:4096") }
        // Reconfigured for the replacement profileB.
        verify { repository.configure(profileB.serverUrl, any(), any(), profileB.allowInsecureConnections) }
        // wasCurrent → purge + forceReconnect + hostProfileSwitched.
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.ForceReconnect>().size)
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.HostProfileSwitched>().size)
        // §review-fix #5/#6: ClearSessionWindowCache replaced by group-scoped
        // EvictGroup. profilesInGroup is a relaxed mock returning empty → no
        // remaining siblings → EvictGroup(g-A) fires.
        assertEquals(
            "EvictGroup(g-A) replaces ClearSessionWindowCache",
            1,
            collectedEffects.filterIsInstance<ControllerEffect.EvictGroup>().size,
        )
    }

    // ── configureServer (URL-unchanged branch) ─────────────────────────────

    @Test
    fun `configureServer with unchanged URL skips clearModelData and HostProfileSwitched but still cancels SSE`() {
        // The urlChanging=false branch: when the user re-submits the SAME URL
        // (e.g. just changing credentials), there is no host switch — old-URL
        // model data must NOT be cleared and HostProfileSwitched must NOT fire.
        // SSE is still cancelled before the reconfigure so events from the
        // previous credential don't land during the new probe.
        every { settingsManager.serverUrl } returns "http://same:4096"
        every { store.currentProfile() } returns profileA // allowInsecure=false

        controller.configureServer("http://same:4096", "u", "p")

        // Old-URL model data NOT cleared (URL did not change).
        verify(exactly = 0) { settingsManager.clearModelDataForUrl(any()) }
        // SSE still cancelled before reconfigure (Stage D — always fires).
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.CancelSseForReconfigure>().size)
        // Settings + repository reconfigured with the (unchanged) URL + new creds.
        verify { settingsManager.serverUrl = "http://same:4096" }
        verify { settingsManager.username = "u" }
        verify { settingsManager.password = "p" }
        verify { repository.configure("http://same:4096", "u", "p", profileA.allowInsecureConnections) }
        // No host switch → no HostProfileSwitched effect.
        assertTrue("HostProfileSwitched must NOT fire on unchanged URL",
            collectedEffects.filterIsInstance<ControllerEffect.HostProfileSwitched>().isEmpty())
        // #12: image client trust policy still synced (mirrors REST/SSE).
        assertEquals(false, HttpImageHolder.lastUpdateSslAllowInsecure)
    }

    // ── RecordingHostProfileCallbacks (removed in batch 3b) ───────────────
    // The handwritten spy was replaced by direct filtering on
    // [collectedEffects] + [trafficTracker] mockk verification. See the
    // class kdoc at the top for the new pattern.

    // ── R-20 Phase 1 review-fix #5/#6: cache eviction granularity ──────────

    @Test
    fun `review-fix 5 selectHostProfile cross-group does NOT emit ClearSessionWindowCache nuke-all`() {
        // §review-fix #5: the prior code emitted ClearSessionWindowCache
        // (nukes ALL groups' memory LRU). The fix removes it; EvictGroup
        // (group-scoped) replaces it. Assert ClearSessionWindowCache is absent.
        every { store.select("p-B") } returns profileB

        controller.selectHostProfile("p-B")
        runPending()

        assertTrue(
            "ClearSessionWindowCache (nuke-all) must NOT fire on cross-group switch — EvictGroup (group-scoped) handles it",
            collectedEffects.filterIsInstance<ControllerEffect.ClearSessionWindowCache>().isEmpty(),
        )
        assertEquals(
            "EvictGroup(g-A) fires for the previous group only",
            1,
            collectedEffects.filterIsInstance<ControllerEffect.EvictGroup>().size,
        )
    }

    @Test
    fun `review-fix 6 deleteHostProfile skips EvictGroup when sibling profile remains in group`() {
        // §review-fix #6 (gpter #5): reference-counted EvictGroup. profileA
        // and profileASibling share g-A. Deleting profileA leaves the sibling
        // → group still referenced → EvictGroup must NOT fire (would orphan
        // the sibling's hot cache).
        val profileASibling = HostProfile(
            id = "p-A2",
            name = "Sibling",
            serverUrl = "http://a:4096",
            serverGroupFp = "g-A" // same group as profileA
        )
        seed { it.copy(currentHostProfileId = "p-A") }
        // profiles() returns [profileA, profileASibling, profileB]; current → profileB (replacement)
        every { store.profiles() } returns listOf(profileA, profileASibling, profileB)
        every { store.currentProfile() } returns profileB
        // profilesInGroup("g-A") returns the two siblings (NOT including the deleted profileA).
        every { store.profilesInGroup("g-A") } returns listOf(profileA, profileASibling)

        controller.deleteHostProfile("p-A")
        scope.testScheduler.advanceUntilIdle()

        assertTrue(
            "EvictGroup must NOT fire when a sibling profile still references the group",
            collectedEffects.filterIsInstance<ControllerEffect.EvictGroup>().isEmpty(),
        )
    }

    @Test
    fun `review-fix 6 deleteHostProfile emits EvictGroup when no sibling remains in group`() {
        // Counterpart: no siblings → group is orphaned → EvictGroup fires.
        seed { it.copy(currentHostProfileId = "p-A") }
        every { store.currentProfile() } returns profileB
        // profilesInGroup returns only profileA (will be deleted → no remaining).
        every { store.profilesInGroup("g-A") } returns listOf(profileA)

        controller.deleteHostProfile("p-A")
        scope.testScheduler.advanceUntilIdle()

        assertEquals(
            "EvictGroup(g-A) fires when no sibling remains",
            1,
            collectedEffects.filterIsInstance<ControllerEffect.EvictGroup>().size,
        )
        assertEquals("g-A", collectedEffects.filterIsInstance<ControllerEffect.EvictGroup>().single().serverGroupFp)
    }
}
