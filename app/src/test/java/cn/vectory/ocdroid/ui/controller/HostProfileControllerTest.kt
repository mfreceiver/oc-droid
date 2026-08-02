package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.BasicAuthConfig
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.http.ClientCertMaterial
import cn.vectory.ocdroid.data.repository.http.SslConfig
import cn.vectory.ocdroid.data.repository.http.SslConfigFactory
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
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.UnreadState
import cn.vectory.ocdroid.ui.settings.CaStage
import cn.vectory.ocdroid.ui.settings.ClientCertEditIntent
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
import kotlinx.coroutines.test.runTest
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.KeyStore

/**
 * R-16 M3 → R-17 batch3b: independent unit test for [HostProfileController].
 *
 * Zero reflection — the controller is driven entirely through its public API
 * (saveHostProfile / duplicateHostProfile / deleteHostProfile / selectHostProfile /
 * configureServer / configureRepositoryForProfile /
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
 * profile CRUD + reconfigure flow owned by this controller.
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
    // §需求12: explicit ids "p-A" / "p-B" so the two profiles are distinct
    // (selectHostProfile's unconditional purge fires on any id change). fp ==
    // id now (serverGroupFp field deleted).
    private val profileA = HostProfile(id = "p-A", name = "Host A", serverUrl = "http://a:4096")
    private val profileB = HostProfile(
        id = "p-B",
        name = "Host B",
        serverUrl = "http://b:4096",
        basicAuth = BasicAuthConfig(username = "user-b", passwordId = "p-B"),
    )

    /**
     * §2.6 / §tofu R2: used to stub `repository.currentSslConfig()` in the
     * trust-all-host scenarios. The legacy `sslConfigFor(allowInsecure=true)`
     * → TrustAll path was REMOVED — TOFU replaced it. The factory is now
     * constructed with an [InMemoryTofuPinStore] (the test fake); production
     * wiring via [cn.vectory.ocdroid.di.TofuModule] is covered separately.
     */
    private val sslConfigFactory = cn.vectory.ocdroid.data.repository.http.SslConfigFactory()

    @Before
    fun setUp() {
        // HostProfileController.saveHostProfile may touch ESP.
        mockkStatic(Log::class)
        appStateFixture = SeedFixture()
        // §R18 Phase 4 (P0-9): SliceFlows is built via a SharedStateStore; the
        // bundle exposes read-only StateFlow views + per-slice mutateXxx.
        val stateStore = cn.vectory.ocdroid.ui.SharedStateStore()
        slices = stateStore.slices
        store = mockk(relaxed = true)
        repository = mockk(relaxed = true)
        // §2.5(a/b): configureRepositoryForProfile / configureServer 现调
        // repository.currentSslConfig() → HttpImageHolder.updateSsl(...)。relaxed
        // mock 无法为 sealed SslConfig 自动造实例，显式 stub 成 SystemDefault。
        every { repository.currentSslConfig() } returns SslConfig.SystemDefault
        settingsManager = mockk(relaxed = true)
        trafficTracker = mockk(relaxed = true)
        effects = SharedEffectBus()
        collectedEffects = mutableListOf()
        recordedEvents.clear()
        // §batch 3b: dual-scope setup. [scope] (StandardTestDispatcher) drives
        // the controller — its scope.launch bodies are queued and drained via
        // [runPending] (advanceUntilIdle), preserving the
        // synchronous-before-async-probe invariant. [collectorScope] (UnconfinedTestDispatcher)
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
            currentProfileId = { "test-fp" })
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
                // §chat-ux-batch T8 (B3): selectedAgentName removed from both
                // SeedFixture + SettingsState (deleted in T8).
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
                staleNotice = s.staleNotice,
                currentModel = s.currentModel
            )
        }
        slices.mutateSessionList {
            // §P0-A rev-gpt #8 B10 r2: sessionStatuses is no longer a public
            // factory param (sole-writer gate) — seed via withProjection.
            SessionListState(
                sessions = s.sessions,
                expandedSessionIds = s.expandedSessionIds,
                loadedSessionLimit = s.loadedSessionLimit,
                hasMoreSessions = s.hasMoreSessions,
                isLoadingMoreSessions = s.isLoadingMoreSessions,
                isRefreshingSessions = s.isRefreshingSessions,
                pendingPermissions = s.pendingPermissions,
                pendingQuestions = s.pendingQuestions,
                childSessions = s.childSessions,
                directorySessions = s.directorySessions,
                sessionTodos = s.sessionTodos
            ).withProjection(s.sessionStatuses)
        }
        slices.mutateUnread {
            UnreadState(
                unreadSessions = s.unreadSessions,
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
    fun `getSavedConnectionSettings reads the resolver URL and credentials in profile mode`() {
        // §resolver-single-source-of-truth (RESOLVER lane ②): the connection
        // form is now pre-filled from EffectiveConnectionConfigResolver, NOT a
        // direct settingsManager read. The main fixture keeps the resolver null
        // (so the select/configure branches stay on their legacy path), so this
        // test builds a controller WITH a resolver stub.
        val resolver = mockk<cn.vectory.ocdroid.service.streaming.EffectiveConnectionConfigResolver>()
        every { resolver.resolve() } returns cn.vectory.ocdroid.service.streaming.EffectiveConnectionConfig(
            source = cn.vectory.ocdroid.service.streaming.EffectiveConnectionSource.Profile,
            profileId = "p-A",
            connectionKey = "g-A",
            url = "https://profile.example",
            username = "alice",
            password = "secret",
            workdir = "/work",
            clientCertId = null,
            mtlsEnabled = false,
            slim = false)
        val controllerWithResolver = HostProfileController(
            scope = scope,
            slices = slices,
            hostProfileStore = store,
            repository = repository,
            settingsManager = settingsManager,
            trafficTracker = trafficTracker,
            effects = effects,
            currentProfileId = { "test-fp" },
            effectiveConnectionConfigResolver = resolver)

        val settings = controllerWithResolver.getSavedConnectionSettings()

        assertEquals("https://profile.example", settings.serverUrl)
        assertEquals("alice", settings.username)
        assertEquals("secret", settings.password)
    }

    @Test
    fun `getSavedConnectionSettings returns blanks and never falls back to stale settingsManager on null resolve`() {
        // §resolver null = EXPLICIT FAIL: a null resolve() returns a BLANK form,
        // NOT a stale settingsManager.serverUrl fallback. Stub a non-blank stale
        // value to prove the fallback path is dead (falling back is exactly the
        // dual-source-of-truth this lane eliminates).
        val resolver = mockk<cn.vectory.ocdroid.service.streaming.EffectiveConnectionConfigResolver>()
        every { resolver.resolve() } returns null
        val controllerWithResolver = HostProfileController(
            scope = scope,
            slices = slices,
            hostProfileStore = store,
            repository = repository,
            settingsManager = settingsManager,
            trafficTracker = trafficTracker,
            effects = effects,
            currentProfileId = { "test-fp" },
            effectiveConnectionConfigResolver = resolver)
        every { settingsManager.serverUrl } returns "http://stale-should-not-leak:4096"

        val settings = controllerWithResolver.getSavedConnectionSettings()

        assertEquals("blank url on null resolve (no stale fallback)", "", settings.serverUrl)
        assertEquals("", settings.username)
        assertEquals("", settings.password)
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

        runTest { controller.saveHostProfile(incoming, basicAuthEdited = false) }

        val savedSlot = mutableListOf<HostProfile>()
        verify { store.save(capture(savedSlot)) }
        assertEquals("p-B", savedSlot.single().basicAuth?.passwordId)
    }

    @Test
    fun `saveHostProfile writes basicAuth password when basicAuthEdited is true`() {
        runTest {
            controller.saveHostProfile(profileB, basicAuthPassword = "pw", basicAuthEdited = true)
        }

        verify { settingsManager.setBasicAuthPassword("p-B", "pw") }
        verify { store.save(profileB) }
    }

    @Test
    fun `saveHostProfile skips basicAuth password write when basicAuthEdited is false`() {
        runTest { controller.saveHostProfile(profileB, basicAuthEdited = false) }

        verify(exactly = 0) { settingsManager.setBasicAuthPassword(any(), any()) }
    }

    @Test
    fun `saveHostProfile clears orphaned password when basicAuth is null`() {
        // Defense-in-depth (#5): a profile with no basicAuth must never retain a
        // password — even though basicAuthEdited=false.
        runTest { controller.saveHostProfile(profileA, basicAuthEdited = false) }

        verify { settingsManager.setBasicAuthPassword("p-A", "") }
    }

    @Test
    fun `saveHostProfile persists profile and refreshes host profile state`() {
        runTest { controller.saveHostProfile(profileB, basicAuthEdited = false) }

        verify { store.save(profileB) }
        // refreshHostProfileState re-reads the store:
        verify(atLeast = 1) { store.profiles() }
        assertEquals("p-A", slices.host.value.currentHostProfileId)
    }

    // ── saveHostProfile (#12 + §tofu R2: live reconfigure when URL/mTLS change) ─

    @Test
    fun `saveHostProfile of active host does NOT reconfigure when only name changes`() {
        // Editing the active host's name (or any non-URL / non-mTLS field)
        // must NOT trigger a reconnect — zero regression for the
        // serverUrl-unchanged case. §tofu R2: this generalizes the former
        // "allowInsecure unchanged" test (the toggle no longer exists).
        seed { it.copy(currentHostProfileId = "p-A") }
        val renamed = profileA.copy(name = "Renamed A") // serverUrl + mTLS unchanged

        runTest { controller.saveHostProfile(renamed, basicAuthEdited = false) }
        scope.testScheduler.advanceUntilIdle()

        verify(exactly = 0) { repository.configure(any(), any(), any(), any()) }
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.ForceReconnect>().isEmpty())
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.CancelSseForReconfigure>().isEmpty())
    }

    @Test
    fun `saveHostProfile of non-current host does NOT reconfigure even when its URL changes`() {
        // Saving a NON-active host (profileB while p-A is current) must only
        // persist + refresh state, never touch the live connection — even
        // when its URL changed. The change takes effect when that host is
        // later selected.
        seed { it.copy(currentHostProfileId = "p-A") }
        val moved = profileB.copy(serverUrl = "http://moved:5050")

        runTest { controller.saveHostProfile(moved, basicAuthEdited = false) }
        scope.testScheduler.advanceUntilIdle()

        verify(exactly = 0) { repository.configure(any(), any(), any(), any()) }
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.ForceReconnect>().isEmpty())
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.CancelSseForReconfigure>().isEmpty())
    }

    // ── C-D3 rev-3 round-6 (review C1): Basic Auth edits on active host now
    //     trigger reconfigure (previously excluded → stale credentials). ─────

    @Test
    fun `saveHostProfile of non-active host basicAuth edit does NOT reconfigure`() {
        // Non-active host basicAuth edit → no reconfigure (the live host is
        // unchanged; the new credential applies when this profile is later
        // selected). Mirrors the URL/mTLS/slim symmetry.
        seed { it.copy(currentHostProfileId = "p-A") }  // p-B is non-active

        runTest {
            controller.saveHostProfile(
                profileB,
                basicAuthPassword = "new-secret",
                basicAuthEdited = true)
        }

        verify(exactly = 0) { repository.configure(any(), any(), any(), any()) }
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.ForceReconnect>().isEmpty())
        // Password write still fired (persistence on the non-active profile).
        verify { settingsManager.setBasicAuthPassword("p-B", "new-secret") }
    }

    // ── C-D3 rev-3 round-7 (review I5-R7): CancellationException discipline ──

    /**
     * C-D3 rev-3 round-7: `saveHostProfile` is wrapped in `runSuspendCatching`
     * (NOT plain `runCatching`) so a [kotlinx.coroutines.CancellationException]
     * thrown inside the boundary (e.g. viewModelScope cancelled on VM clear)
     * PROPAGATES instead of being collapsed to `Result.failure`. Swallowing CE
     * breaks structured concurrency; this matches the project's established
     * discipline (`cn.vectory.ocdroid.util.runSuspendCatching`).
     *
     * Test pattern mirrors `RunSuspendCatchingTest.rethrowsCancellationException`:
     * inject a CE via a fake barrier whose suspend `reconfigure` throws, then
     * assert the controller rethrows (not Result.failure).
     */
    @Test
    fun `selectHostProfile does NOT propagate SSL to HttpImageHolder (no runtime reconfigure)`() {
        // lite-v2: selectHostProfile does NOT call configureRepositoryForProfileRaw
        // — SSL propagation happens on restart's configure path, NOT on select.
        // HttpImageHolder must NOT be touched by selectHostProfile.
        every { store.select("p-B") } returns profileB

        controller.selectHostProfile("p-B")
        runPending()

        // HttpImageHolder.updateSsl is NOT called (no configureRepositoryForProfileRaw).
        assertNull(HttpImageHolder.lastUpdateSslMode)
    }

    @Test
    fun `selectHostProfile does NOT mirror profile serverUrl into settingsManager (no runtime reconfigure)`() {
        // lite-v2: selectHostProfile does NOT call configureRepositoryForProfileRaw,
        // so settingsManager.serverUrl is NOT updated here. The mirror happens
        // on restart's configure path, not on select. The resolver
        // (EffectiveConnectionConfigResolver) is the single source of truth.
        every { store.select("p-B") } returns profileB

        controller.selectHostProfile("p-B")
        runPending()

        // configureRepositoryForProfileRaw is never called — settingsManager.serverUrl
        // is NOT written by selectHostProfile.
        verify(exactly = 0) { settingsManager.serverUrl = any() }
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
        assertTrue("unread purged", slices.unread.value.unreadSessions.isEmpty())
        assertNull("draftWorkdir purged", slices.composer.value.draftWorkdir)
        assertTrue("availableCommands purged", slices.settings.value.availableCommands.isEmpty())
    }

    @Test
    fun `selectHostProfile persists selection and emits RestartRequired (no runtime reconfigure)`() {
        // lite-v2: selectHostProfile must NOT call configureRepositoryForProfileRaw
        // (no runtime reconfigure — restart handles it). Instead it emits
        // RestartRequired via withHostReconfiguration(needsReconfigure=true).
        // No ForceReconnect / HostProfileSwitched.
        every { store.select("p-B") } returns profileB

        controller.selectHostProfile("p-B")
        runPending()

        // Repository is NOT reconfigured at select time (restart handles it).
        verify(exactly = 0) { repository.configure(any(), any(), any(), any(), any(), any()) }
        // RestartRequired is emitted (via withHostReconfiguration).
        assertEquals(
            "selectHostProfile emits exactly one RestartRequired",
            1,
            collectedEffects.filterIsInstance<ControllerEffect.RestartRequired>().size)
        // No runtime reconnect / switch signals (restart supersedes them).
        assertTrue(
            "ForceReconnect must NOT be emitted (restart supersedes)",
            collectedEffects.filterIsInstance<ControllerEffect.ForceReconnect>().isEmpty())
        assertTrue(
            "HostProfileSwitched must NOT be emitted (restart supersedes)",
            collectedEffects.filterIsInstance<ControllerEffect.HostProfileSwitched>().isEmpty())
    }

    @Test
    fun `selectHostProfile drops session window cache and persisted session settings`() {
        every { store.select("p-B") } returns profileB

        controller.selectHostProfile("p-B")
        runPending()

        // §review-fix #5: ClearSessionWindowCache was removed from
        // purgePerHostState (over-broad nuke replaced by group-scoped
        // EvictGroup). §需求12: switching profileA (p-A) → profileB (p-B)
        // fires EvictGroup(p-A) — the previous profile's id.
        assertEquals(
            "EvictGroup(p-A) replaces ClearSessionWindowCache",
            1,
            collectedEffects.filterIsInstance<ControllerEffect.EvictGroup>().size)
        assertEquals("p-A", collectedEffects.filterIsInstance<ControllerEffect.EvictGroup>().single().profileId)
        // ClearSessionWindowCache is no longer emitted here.
        assertTrue(
            "ClearSessionWindowCache must NOT fire (EvictGroup handles group-scoped clear)",
            collectedEffects.filterIsInstance<ControllerEffect.ClearSessionWindowCache>().isEmpty())
        // §R18 Phase 2-F: currentSessionId is no longer written to
        // SettingsManager here (purgePerHostState clears it on the chat slice,
        // asserted below); the AppCore collector persists non-null changes only.
        assertNull("currentSessionId purged on chat slice", slices.chat.value.currentSessionId)
        // §B4: open-tabs-list removed — no openSessionIds verify.
        verify { settingsManager.sessionCache = emptyList() }
        verify { settingsManager.currentWorkdir = null }
        // §recent-workdirs fix: clearRecentWorkdirs was REMOVED from
        // purgePerHostState (cross-group branch). currentProfileId() reads
        // the NEW (target) fp after select(), so the call cleared the target
        // profile's history — switching back lost its recent projects. Now
        // recentWorkdirs are isolated per fp (getRecentWorkdirs(fp)) and never
        // actively cleared on switch.
        verify(exactly = 0) { settingsManager.clearRecentWorkdirs(any()) }
    }

    // §需求12阶段3: the former `selectHostProfile same-group preserves
    // recentWorkdirs` test was removed — under 需求12 profiles are independent
    // (no groups) and EvictGroup is unconditional, so the same-group preserve
    // branch + its "no EvictGroup" assertion are dead.

    // ── configureServer (direct connection form) ───────────────────────────

    @Test
    fun `configureServer defaults username and password to null`() {
        every { store.currentProfile() } returns profileA

        controller.configureServer("http://m:4096")

        verify { settingsManager.username = null }
        verify { settingsManager.password = null }
        verify { repository.configure("http://m:4096", null, null, any(), any(), any()) }
    }

    // ── configureRepositoryForProfile ──────────────────────────────────────

    @Test
    fun `configureRepositoryForProfile does not require a persisted workdir`() {
        // §R18 Phase 2-E step 2: with the global workdir fallback removed,
        // configureRepositoryForProfile no longer reads settingsManager.
        // currentWorkdir at all — it only reconfigures the repository and TLS.
        every { settingsManager.currentWorkdir } returns null

        controller.configureRepositoryForProfile(profileA)

        // Repository is still configured; no exception, no effect.
        // §tofu R2: 4th arg = hostPortFromUrl("http://a:4096") = "a:4096".
        verify {
            repository.configure(
                profileA.serverUrl,
                profileA.basicAuth?.username,
                any(),
                "a:4096",
                any(),
                any())
        }
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
    }

    @Test
    fun `resetLocalDataAndResync bumps completeness epoch instead of resetting to zero`() {
        // §gpter-residual (oracle must-fix): a reset-to-0 epoch creates an ABA
        // window where a hydration captured before the reset (epoch 0 on a fresh
        // start) could commit afterward and re-certify a stale root. The reset
        // must bump the epoch monotonically so the epoch guard drops it.
        slices.mutateSessionList {
            it.copy(completenessEpoch = 5L, completeRootIds = setOf("stale"))
        }

        controller.resetLocalDataAndResync()

        assertEquals(
            "epoch bumped from 5 to 6, not reset to 0",
            6L,
            slices.sessionList.value.completenessEpoch)
        assertTrue(
            "completeness proofs cleared on reset",
            slices.sessionList.value.completeRootIds.isEmpty())
    }

    @Test
    fun `resetLocalDataAndResync uses the same-host slim reset primitive`() {
        controller.resetLocalDataAndResync()

        verify(exactly = 1) { repository.resetSlimForLocalWipe() }
    }

    @Test
    fun `resetLocalDataAndResync wipes persisted local data and fires the full reset callback chain in order`() {
        controller.resetLocalDataAndResync()
        // remove-message-persistence Task 5: the async cache-wipe launch
        // block (clearAll → deleteDatabase → cache-listing refresh) was
        // removed with the SQLite persistence layer. The synchronous tryEmitEffect
        // chain (Clear/CancelSse/ColdStart) is still captured in
        // collectedEffects immediately; runPending is kept for parity with
        // the barrier-path test (no-op here now that there is no launch).
        runPending()

        verify { settingsManager.clearAllLocalData() }
        // resetTrafficTracker is now an inline trafficTracker.reset() call.
        verify(exactly = 1) { trafficTracker.reset() }
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.ClearSessionWindowCache>().size)
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.CancelSseForReconfigure>().size)
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.ColdStartReconnect>().size)
        // Assert ordering among effects — ClearSessionWindowCache (step 3)
        // before CancelSseForReconfigure (step 4) before ColdStartReconnect
        // (step 8).
        val clearCacheIdx = collectedEffects.indexOfFirst { it is ControllerEffect.ClearSessionWindowCache }
        val cancelSseIdx = collectedEffects.indexOfFirst { it is ControllerEffect.CancelSseForReconfigure }
        val coldStartIdx = collectedEffects.indexOfFirst { it is ControllerEffect.ColdStartReconnect }
        assertTrue(clearCacheIdx >= 0)
        assertTrue(clearCacheIdx < cancelSseIdx)
        assertTrue(cancelSseIdx < coldStartIdx)
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

    // §需求12阶段3: the former `deleteHostProfile of current profile keeps
    // model data when sibling remains in group` test was removed — under 需求12
    // the reference-counting is dead (a group can never have sibling profiles),
    // so clearModelDataForGroup + EvictGroup are both unconditional on active
    // deletion.

    // ── configureServer (URL-unchanged branch) ─────────────────────────────

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
            collectedEffects.filterIsInstance<ControllerEffect.ClearSessionWindowCache>().isEmpty())
        assertEquals(
            "EvictGroup(p-A) fires for the previous profile only",
            1,
            collectedEffects.filterIsInstance<ControllerEffect.EvictGroup>().size)
    }

    @Test
    fun `deleteHostProfile emits EvictGroup unconditionally (§需求12阶段3)`() {
        // §需求12阶段3 (oracle-assessed): the former reference-counting
        // (`remainingInGroup` / conditional EvictGroup) is dead under 需求12 —
        // a group can never have sibling profiles. EvictGroup is now
        // UNCONDITIONAL on active deletion.
        // §需求12 rev-4 blocker B: active deletion clears the COMPLETE
        // per-profile ESP lifecycle (clearAllForProfile), not just the model
        // data — drafts, recent workdirs, basic-auth password too.
        seed { it.copy(currentHostProfileId = "p-A") }
        every { store.currentProfile() } returns profileB

        controller.deleteHostProfile("p-A")
        scope.testScheduler.advanceUntilIdle()

        assertEquals(
            "EvictGroup(p-A) fires unconditionally on active deletion",
            1,
            collectedEffects.filterIsInstance<ControllerEffect.EvictGroup>().size)
        assertEquals("p-A", collectedEffects.filterIsInstance<ControllerEffect.EvictGroup>().single().profileId)
        // §需求12 rev-4 blocker B: full per-profile ESP lifecycle cleared.
        verify(exactly = 1) { settingsManager.clearAllForProfile("p-A") }
    }

    @Test
    fun `deleteHostProfile of non-current profile also clears its persisted model data (rev-3 blocker #2)`() {
        // §需求12阶段3 rev-3 blocker #2: under 需求12 profiles are fully
        // independent — a group can never have sibling profiles. The
        // per-profile-id model availability/disabled ESP keys are orphans
        // the instant their owning profile is deleted. Non-active deletion
        // MUST also call clearAllForProfile(deletedId), not just emit
        // EvictGroup — otherwise the ESP keys leak forever (UUID-suffixed
        // keys are never swept by clearOrphanGroupKeys, which only purges
        // non-UUID A/B/C/D suffixes).
        // §需求12 rev-4 blocker B: clearAllForProfile now covers the COMPLETE
        // per-profile ESP lifecycle (model data + drafts + recent workdirs +
        // basic-auth password).
        seed { it.copy(currentHostProfileId = "p-A") }

        controller.deleteHostProfile("p-B")
        scope.testScheduler.advanceUntilIdle()

        // Persisted per-profile ESP data for the deleted non-current profile
        // is cleared (full lifecycle: model + drafts + workdirs + basic-auth).
        verify(exactly = 1) { settingsManager.clearAllForProfile("p-B") }
        // EvictGroup still fires (in-memory authority/session eviction).
        assertEquals(
            "EvictGroup(p-B) fires for the deleted non-current profile",
            1,
            collectedEffects.filterIsInstance<ControllerEffect.EvictGroup>().size)
        assertEquals("p-B", collectedEffects.filterIsInstance<ControllerEffect.EvictGroup>().single().profileId)
        // Non-active deletion does NOT purge the active host's state or restart.
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.RestartRequired>().isEmpty())
    }

    // ── §fix-3 gro-1/gpt-2/glm-2: saveHostProfile mTLS live-reconfigure ──────

    /**
     * §fix-3: 构造一个有效的 PKCS12 字节（HeldCertificate 自签 CA + 签发的 client key
     * + 证书链）。用于 mTLS save 用例的真实 stagedP12（applyClientCertSave 会试构建
     * buildMutualTlsConfig，需有效 p12 才不抛）。
     */
    private fun buildValidP12(password: String = "p12pw"): ByteArray {
        val ca = HeldCertificate.Builder().commonName("test-ca").build()
        val client = HeldCertificate.Builder().commonName("test-client").signedBy(ca).build()
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry(
            "client", client.keyPair.private, password.toCharArray(),
            arrayOf(client.certificate, ca.certificate))
        val baos = ByteArrayOutputStream()
        ks.store(baos, password.toCharArray())
        return baos.toByteArray()
    }

    @Test
    fun `saveHostProfile active host enabling mTLS reconfigures and force-reconnects`() {
        // ① active host 开 mTLS（save，带有效 stagedP12）→ reconfigure + ForceReconnect。
        seed { it.copy(currentHostProfileId = "p-A") }
        val p12 = buildValidP12()

        runTest {
            controller.saveHostProfile(
                profileA,
                basicAuthEdited = false,
                clientCertEdit = ClientCertEditIntent.Update(
                    stagedP12 = p12, caStage = CaStage.Unchanged,
                    p12Password = "p12pw", p12PasswordEdited = true, hasImportedP12 = true))
        }
        scope.testScheduler.advanceUntilIdle()

        // 试构建通过 → saveClientCert 被调；mtlsEnabled false→true → mtlsChanged → reconfigure。
        verify { settingsManager.saveClientCert(any(), p12, any(), any()) }
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.RestartRequired>().size)
        // 纯 mTLS 变化（无 urlChanged）→ HostProfileSwitched 不发。
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.HostProfileSwitched>().isEmpty())
    }

    @Test
    fun `saveHostProfile active host disabling mTLS reconfigures and clears ESP cert`() {
        // ② 关 mTLS → reconfigure + 清 ESP（clearClientCert）。incoming profile 携带旧
        // clientCertId（与生产 Dialog 一致：saved = initial.copy(...) 保留该字段），供
        // Disable 分支清理。
        val mtlsProfile = profileA.copy(mtlsEnabled = true, clientCertId = "cert-old")
        seedStore(listOf(mtlsProfile, profileB), currentId = "p-A")
        seed { it.copy(currentHostProfileId = "p-A") }

        runTest {
            controller.saveHostProfile(
                mtlsProfile,  // 携带 cert-old（Disable 据此清理）
                basicAuthEdited = false,
                clientCertEdit = ClientCertEditIntent.Disable)
        }
        scope.testScheduler.advanceUntilIdle()

        verify { settingsManager.clearClientCert("cert-old") }
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.RestartRequired>().size)
    }

    @Test
    fun `saveHostProfile active host keeping mTLS but re-importing p12 reconfigures via material edit signal`() {
        // ③ 保持启用换 p12（stagedP12!=null）→ reconfigure（mtlsMaterialEdited 触发，
        // **非靠 clientCertId**——oldId 复用，id 不变）。glm-2/max-1 S1 关键用例。
        val mtlsProfile = profileA.copy(mtlsEnabled = true, clientCertId = "cert-stable")
        seedStore(listOf(mtlsProfile, profileB), currentId = "p-A")
        seed { it.copy(currentHostProfileId = "p-A") }
        val newP12 = buildValidP12()

        runTest {
            controller.saveHostProfile(
                mtlsProfile,
                basicAuthEdited = false,
                clientCertEdit = ClientCertEditIntent.Update(
                    stagedP12 = newP12, caStage = CaStage.Unchanged,
                    p12Password = "p12pw", p12PasswordEdited = true, hasImportedP12 = true))
        }
        scope.testScheduler.advanceUntilIdle()

        // id 不变（原地覆盖），但材料变了 → reconfigure 必发。
        verify { settingsManager.saveClientCert("cert-stable", newP12, any(), any()) }
        assertEquals(
            "material edit (re-import) triggers restart-required even though clientCertId unchanged",
            1, collectedEffects.filterIsInstance<ControllerEffect.RestartRequired>().size)
    }

    @Test
    fun `saveHostProfile mTLS enabled without p12 is rejected with IllegalArgumentException`() {
        // ④ mTLS=true 无 p12 → 保存拒绝。
        // C-D3 rev-3 round-6 (review I5): saveHostProfile is now suspend +
        // Result<Unit>; the IllegalArgumentException from applyClientCertSave
        // is caught by runCatching and surfaced as Result.failure (not thrown).
        // The dialog stays open with the error.
        seed { it.copy(currentHostProfileId = "p-A") }

        runTest {
            val result = controller.saveHostProfile(
                profileA, basicAuthEdited = false,
                clientCertEdit = ClientCertEditIntent.Update(
                    stagedP12 = null, caStage = CaStage.Unchanged,
                    p12Password = null, p12PasswordEdited = false, hasImportedP12 = false))
            assertTrue("save must fail when mTLS enabled without p12", result.isFailure)
            assertTrue(
                "failure cause must be IllegalArgumentException (got ${result.exceptionOrNull()})",
                result.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun `saveHostProfile mTLS enabled with corrupt p12 is rejected`() {
        // ⑤ mTLS=true p12 损坏 → 试构建失败 → 保存拒绝。Round-6: Result.failure
        // surfaces the IllegalArgumentException (was assertThrows pre-round-6).
        seed { it.copy(currentHostProfileId = "p-A") }

        runTest {
            val result = controller.saveHostProfile(
                profileA, basicAuthEdited = false,
                clientCertEdit = ClientCertEditIntent.Update(
                    stagedP12 = ByteArray(32) { it.toByte() }, caStage = CaStage.Unchanged,
                    p12Password = null, p12PasswordEdited = false, hasImportedP12 = true))
            assertTrue("save must fail when p12 is corrupt", result.isFailure)
            assertTrue(
                "failure cause must be IllegalArgumentException (got ${result.exceptionOrNull()})",
                result.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun `saveHostProfile non-active host mTLS change does NOT reconfigure live client`() {
        // ⑥ 非 active host 改 mTLS → 不 reconfigure（仅持久化，切到时才生效）。
        seed { it.copy(currentHostProfileId = "p-A") }  // p-B 非 active
        val p12 = buildValidP12()

        runTest {
            controller.saveHostProfile(
                profileB,
                basicAuthEdited = false,
                clientCertEdit = ClientCertEditIntent.Update(
                    stagedP12 = p12, caStage = CaStage.Unchanged,
                    p12Password = "p12pw", p12PasswordEdited = true, hasImportedP12 = true))
        }
        scope.testScheduler.advanceUntilIdle()

        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.ForceReconnect>().isEmpty())
    }

    @Test
    fun `saveHostProfile default clientCertEdit Unchanged does NOT clear existing ESP cert`() {
        // §fix-3 (gpt-2#3 阻断): 默认 Unchanged 不动 ESP——既有证书不被误清。
        val mtlsProfile = profileA.copy(mtlsEnabled = true, clientCertId = "cert-keep")
        seedStore(listOf(mtlsProfile, profileB), currentId = "p-A")
        seed { it.copy(currentHostProfileId = "p-A") }

        runTest { controller.saveHostProfile(mtlsProfile, basicAuthEdited = false) }  // 默认 Unchanged
        scope.testScheduler.advanceUntilIdle()

        verify(exactly = 0) { settingsManager.clearClientCert(any()) }
        // mTLS 字段未变 → 不 reconfigure。
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.ForceReconnect>().isEmpty())
    }

    @Test
    fun `configureRepositoryForProfile mtlsEnabled with missing cert emits degradation UiEvent`() {
        // §fix-3 (gro-1#2/gpt-2#2): mTLS 开但 loadClientCertMaterial 返回 null → fail-loud。
        val mtlsProfile = profileA.copy(mtlsEnabled = true, clientCertId = "cert-gone")
        every { settingsManager.loadClientCertMaterial("cert-gone") } returns null
        every { repository.lastClientCertError } returns null

        controller.configureRepositoryForProfile(mtlsProfile)

        // 降级 banner 写入 connection slice + UiEvent.Error 发出。
        assertEquals("mTLS 已开启但客户端证书缺失", slices.connection.value.mtlsDegradedError)
        assertEquals(1, recordedEvents.filterIsInstance<UiEvent.Error>().size)
        assertEquals(R.string.host_mtls_missing_cert, recordedEvents.filterIsInstance<UiEvent.Error>().single().resId)
    }

    @Test
    fun `configureRepositoryForProfile healthy mTLS clears degradation error`() {
        // §fix-3: 材料 OK → mtlsDegradedError 被清空（null），不 emit。
        val mtlsProfile = profileA.copy(mtlsEnabled = true, clientCertId = "cert-ok")
        every { settingsManager.loadClientCertMaterial("cert-ok") } returns
            ClientCertMaterial(buildValidP12(), "p12pw".toCharArray(), null)
        every { repository.lastClientCertError } returns null

        controller.configureRepositoryForProfile(mtlsProfile)

        assertNull(slices.connection.value.mtlsDegradedError)
        assertTrue(recordedEvents.filterIsInstance<UiEvent.Error>().isEmpty())
    }

    // ── §emitEffect reliability (must not use tryEmitEffect for critical effects) ──

    @Test
    fun `deleteHostProfile of current profile emits RestartRequired through suspend emitEffect not tryEmit`() {
        // Regression: RestartRequired must use emitEffect (suspend-on-full) not
        // tryEmitEffect (can drop silently). The test asserts the effect appears
        // after runPending (scope.launch { emitEffect(...) }).
        seed { it.copy(currentHostProfileId = "p-A") }
        every { store.currentProfile() } returns profileB

        controller.deleteHostProfile("p-A")
        scope.testScheduler.advanceUntilIdle()

        assertEquals(
            "RestartRequired must be emitted (suspend emitEffect, not dropped tryEmit)",
            1,
            collectedEffects.filterIsInstance<ControllerEffect.RestartRequired>().size,
        )
    }

    @Test
    fun `resetLocalDataAndResync emits three critical effects FIFO via suspend emitEffect`() {
        // Regression: ClearSessionWindowCache, CancelSseForReconfigure,
        // ColdStartReconnect must use suspend emitEffect (not tryEmitEffect)
        // and must appear in FIFO order. They are now wrapped in a single
        // scope.launch { emitEffect(A); emitEffect(B); emitEffect(C) }.
        controller.resetLocalDataAndResync()
        runPending()

        val clearIdx = collectedEffects.indexOfFirst { it is ControllerEffect.ClearSessionWindowCache }
        val cancelIdx = collectedEffects.indexOfFirst { it is ControllerEffect.CancelSseForReconfigure }
        val coldIdx = collectedEffects.indexOfFirst { it is ControllerEffect.ColdStartReconnect }

        assertTrue("ClearSessionWindowCache must be present", clearIdx >= 0)
        assertTrue("CancelSseForReconfigure must be present", cancelIdx >= 0)
        assertTrue("ColdStartReconnect must be present", coldIdx >= 0)
        assertTrue(
            "ClearSessionWindowCache must precede CancelSseForReconfigure: $clearIdx vs $cancelIdx",
            clearIdx < cancelIdx,
        )
        assertTrue(
            "CancelSseForReconfigure must precede ColdStartReconnect: $cancelIdx vs $coldIdx",
            cancelIdx < coldIdx,
        )
    }
}
