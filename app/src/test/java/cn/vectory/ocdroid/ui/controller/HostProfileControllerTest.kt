package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.data.model.BasicAuthConfig
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.AppState
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.ComposerState
import cn.vectory.ocdroid.ui.ConnectionState
import cn.vectory.ocdroid.ui.FileState
import cn.vectory.ocdroid.ui.HostState
import cn.vectory.ocdroid.ui.SessionListState
import cn.vectory.ocdroid.ui.SettingsState
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.TUNNEL_SUCCESS_TOAST
import cn.vectory.ocdroid.ui.TrafficState
import cn.vectory.ocdroid.ui.TunnelActivationState
import cn.vectory.ocdroid.ui.UnreadState
import cn.vectory.ocdroid.ui.syncSlicesFromAppState
import cn.vectory.ocdroid.ui.util.HttpImageHolder
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R-16 M3: independent unit test for [HostProfileController].
 *
 * Zero reflection — the controller is driven entirely through its public API
 * (saveHostProfile / duplicateHostProfile / deleteHostProfile / selectHostProfile /
 * configureServer / configureRepositoryForProfile / activateTunnelForCurrentHost /
 * resetLocalDataAndResync / accessors) and asserted via the
 * [RecordingHostProfileCallbacks] spy + direct StateFlow reads. Heavy service
 * dependencies (HostProfileStore / OpenCodeRepository / SettingsManager) are
 * mockk stubs; AppState + SliceFlows are real so `updateAndSync` runs and the
 * controller's state writes are observable. Follows the
 * [ForegroundCatchUpControllerTest] / [SessionSwitcherTest] pattern.
 *
 * Note: `testConnection` itself stays in MainViewModel until M4 (see controller
 * kdoc), so the state-machine coverage here targets the tunnel-activation flow
 * (Idle → Loading → Success/Error), which is the equivalent isConnecting-style
 * progression owned by this controller.
 */
class HostProfileControllerTest {

    private lateinit var state: MutableStateFlow<AppState>
    private lateinit var slices: SliceFlows
    private lateinit var store: HostProfileStore
    private lateinit var repository: OpenCodeRepository
    private lateinit var settingsManager: SettingsManager
    private lateinit var callbacks: RecordingHostProfileCallbacks
    private lateinit var scope: TestScope
    private lateinit var controller: HostProfileController

    // Real data-class fixtures (avoid relaxed-mock proxies for value types).
    private val profileA = HostProfile(id = "p-A", name = "Host A", serverUrl = "http://a:4096")
    private val profileB = HostProfile(
        id = "p-B",
        name = "Host B",
        serverUrl = "http://b:4096",
        basicAuth = BasicAuthConfig(username = "user-b", passwordId = "p-B"),
        allowInsecureConnections = true
    )

    @Before
    fun setUp() {
        // HostProfileController.activateTunnelForCurrentHost calls Log.d/Log.e.
        mockkStatic(Log::class)
        state = MutableStateFlow(AppState())
        slices = SliceFlows(
            connection = MutableStateFlow(ConnectionState()),
            traffic = MutableStateFlow(TrafficState()),
            composer = MutableStateFlow(ComposerState()),
            file = MutableStateFlow(FileState()),
            settings = MutableStateFlow(SettingsState()),
            chat = MutableStateFlow(ChatState()),
            sessionList = MutableStateFlow(SessionListState()),
            unread = MutableStateFlow(UnreadState()),
            host = MutableStateFlow(HostState())
        )
        store = mockk(relaxed = true)
        repository = mockk(relaxed = true)
        settingsManager = mockk(relaxed = true)
        callbacks = RecordingHostProfileCallbacks()
        scope = TestScope()
        controller = HostProfileController(
            scope = scope,
            state = state,
            slices = slices,
            hostProfileStore = store,
            repository = repository,
            settingsManager = settingsManager,
            callbacks = callbacks
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

    // R-17 M5: seed AppState then propagate to slices (controllers read/write slices).
    private fun seed(transform: (AppState) -> AppState) {
        state.value = transform(state.value)
        syncSlicesFromAppState(state.value, slices)
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
        assertEquals(1, callbacks.forceReconnectCalls)
        // configureRepositoryForProfile cancels SSE once.
        assertEquals(1, callbacks.cancelSseForReconfigureCalls)
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

        verify(exactly = 0) { repository.configure(any(), any(), any(), any()) }
        assertEquals(0, callbacks.forceReconnectCalls)
        assertEquals(0, callbacks.cancelSseForReconfigureCalls)
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

        verify(exactly = 0) { repository.configure(any(), any(), any(), any()) }
        assertEquals(0, callbacks.forceReconnectCalls)
        assertEquals(0, callbacks.cancelSseForReconfigureCalls)
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

        // 重配为 allowInsecure=false（切回系统信任）。
        verify { repository.configure(toggledOff.serverUrl, any(), any(), false) }
        assertEquals(1, callbacks.forceReconnectCalls)
        assertEquals(1, callbacks.cancelSseForReconfigureCalls)
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

        verify(exactly = 0) { repository.configure(any(), any(), any(), any()) }
        assertEquals(0, callbacks.forceReconnectCalls)
        assertEquals(0, callbacks.cancelSseForReconfigureCalls)
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

        // Reconfigures repository for the (unchanged) current host profileA.
        verify { repository.configure(profileA.serverUrl, any(), any(), profileA.allowInsecureConnections) }
        // NOT current → no purge, no reconnect.
        assertEquals(0, callbacks.forceReconnectCalls)
        assertEquals(0, callbacks.clearSessionWindowCacheCalls)
        // configureRepositoryForProfile cancels SSE once.
        assertEquals(1, callbacks.cancelSseForReconfigureCalls)
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
        // After deleting the current, the store picks profileB as the new current.
        seedStore(listOf(profileB), currentId = "p-B")

        controller.deleteHostProfile("p-A")

        // Reconfigured for the replacement profileB (with its allowInsecure flag).
        verify { repository.configure(profileB.serverUrl, any(), any(), profileB.allowInsecureConnections) }
        // wasCurrent → purge + forceReconnect.
        assertEquals(1, callbacks.forceReconnectCalls)
        assertEquals(1, callbacks.clearSessionWindowCacheCalls)
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

        assertEquals(1, callbacks.clearSessionWindowCacheCalls)
        verify { settingsManager.currentSessionId = null }
        verify { settingsManager.openSessionIds = emptyList() }
        verify { settingsManager.sessionCache = emptyList() }
        verify { settingsManager.currentWorkdir = null }
        verify { settingsManager.recentWorkdirs = emptyList() }
    }

    @Test
    fun `selectHostProfile forces a reconnect and the callback order is cache-clear before sse-cancel before reconnect`() {
        every { store.select("p-B") } returns profileB

        controller.selectHostProfile("p-B")
        runPending()

        assertEquals(1, callbacks.forceReconnectCalls)
        val clearIdx = callbacks.callOrder.indexOf("clearSessionWindowCache")
        val cancelIdx = callbacks.callOrder.indexOf("cancelSseForReconfigure")
        val reconnectIdx = callbacks.callOrder.indexOf("forceReconnect")
        assertTrue("clearSessionWindowCache recorded", clearIdx >= 0)
        assertTrue("cancelSseForReconfigure recorded", cancelIdx >= 0)
        assertTrue("forceReconnect recorded", reconnectIdx >= 0)
        assertTrue("purge clears cache before configure cancels SSE", clearIdx < cancelIdx)
        assertTrue("configure cancels SSE before reconnect", cancelIdx < reconnectIdx)
    }

    // ── configureServer (direct connection form) ───────────────────────────

    @Test
    fun `configureServer cancels SSE, writes settings, and configures repository with current profile allowInsecure`() {
        // currentHostProfile() returns profileB (allowInsecure=true).
        every { store.currentProfile() } returns profileB

        controller.configureServer("http://manual:4096", "mu", "mp")

        assertEquals(1, callbacks.cancelSseForReconfigureCalls)
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
    fun `configureRepositoryForProfile cancels SSE, configures with profile creds + allowInsecure, and restores workdir`() {
        every { settingsManager.basicAuthPassword("p-B") } returns "secret-b"
        every { settingsManager.currentWorkdir } returns "/persisted/proj"

        controller.configureRepositoryForProfile(profileB)

        assertEquals(1, callbacks.cancelSseForReconfigureCalls)
        verify {
            repository.configure(
                profileB.serverUrl,
                profileB.basicAuth?.username,
                "secret-b",
                profileB.allowInsecureConnections
            )
        }
        verify { repository.setCurrentDirectory("/persisted/proj") }
    }

    @Test
    fun `configureRepositoryForProfile skips setCurrentDirectory when no workdir persisted`() {
        every { settingsManager.currentWorkdir } returns null

        controller.configureRepositoryForProfile(profileA)

        verify(exactly = 0) { repository.setCurrentDirectory(any()) }
    }

    // ── activateTunnelForCurrentHost (state machine) ───────────────────────

    @Test
    fun `activateTunnel with no tunnelPasswordId sets error and TunnelActivationState_Error without launching`() {
        every { store.currentProfile() } returns profileA // tunnelPasswordId == null

        controller.activateTunnelForCurrentHost()

        assertEquals(TunnelActivationState.Error("未设置隧道密码"), slices.connection.value.tunnelActivationState)
        assertNotNull(state.value.error)
        assertTrue("error surfaced", state.value.error!!.contains("隧道激活失败"))
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
        // §success-channel: success now rides AppState.successMessage (not
        // error) so ChatScreen renders a positive toast instead of "发生错误".
        assertEquals(TUNNEL_SUCCESS_TOAST, state.value.successMessage)
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
        assertTrue("error toast includes exception message", state.value.error!!.contains("boom-network"))
        assertTrue("error toast includes failure prefix", state.value.error!!.contains("隧道激活失败"))
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
        assertEquals("reconnecting", slices.connection.value.connectionPhase)
        assertEquals(0L, slices.traffic.value.trafficSent)
        assertEquals(0L, slices.traffic.value.trafficReceived)
        assertEquals(TunnelActivationState.Idle, slices.connection.value.tunnelActivationState)
    }

    @Test
    fun `resetLocalDataAndResync wipes persisted local data and fires the full reset callback chain in order`() {
        controller.resetLocalDataAndResync()

        verify { settingsManager.clearAllLocalData() }
        assertEquals(1, callbacks.resetTrafficTrackerCalls)
        assertEquals(1, callbacks.clearSessionWindowCacheCalls)
        assertEquals(1, callbacks.cancelSseForReconfigureCalls)
        assertEquals(1, callbacks.coldStartReconnectCalls)

        val resetTrafficIdx = callbacks.callOrder.indexOf("resetTrafficTracker")
        val clearCacheIdx = callbacks.callOrder.indexOf("clearSessionWindowCache")
        val cancelSseIdx = callbacks.callOrder.indexOf("cancelSseForReconfigure")
        val coldStartIdx = callbacks.callOrder.indexOf("coldStartReconnect")
        assertTrue(resetTrafficIdx < clearCacheIdx)
        assertTrue(clearCacheIdx < cancelSseIdx)
        assertTrue(cancelSseIdx < coldStartIdx)
    }

    @Test
    fun `resetLocalDataAndResync does NOT call forceReconnect`() {
        // resetLocalDataAndResync uses coldStartReconnect (3 retries), not the
        // throttled forceReconnect — asserting the invariant so the two paths
        // can't silently swap.
        controller.resetLocalDataAndResync()

        assertEquals(0, callbacks.forceReconnectCalls)
    }

    // ── RecordingHostProfileCallbacks ──────────────────────────────────────

    /**
     * Handwritten spy (per the codebase's zero-reflection test convention) that
     * records every [HostProfileCallbacks] invocation + call order so tests can
     * assert on side effects and ordering invariants.
     */
    private class RecordingHostProfileCallbacks : HostProfileCallbacks {
        var cancelSseForReconfigureCalls = 0
        var startSseCalls = 0
        var forceReconnectCalls = 0
        var coldStartReconnectCalls = 0
        var loadInitialDataCalls = 0
        var clearSessionWindowCacheCalls = 0
        var resetTrafficTrackerCalls = 0
        val callOrder = mutableListOf<String>()

        override fun cancelSseForReconfigure() {
            cancelSseForReconfigureCalls++
            callOrder += "cancelSseForReconfigure"
        }

        override fun startSSE() {
            startSseCalls++
            callOrder += "startSSE"
        }

        override fun forceReconnect() {
            forceReconnectCalls++
            callOrder += "forceReconnect"
        }

        override fun coldStartReconnect() {
            coldStartReconnectCalls++
            callOrder += "coldStartReconnect"
        }

        override fun loadInitialData() {
            loadInitialDataCalls++
            callOrder += "loadInitialData"
        }

        override fun clearSessionWindowCache() {
            clearSessionWindowCacheCalls++
            callOrder += "clearSessionWindowCache"
        }

        override fun resetTrafficTracker() {
            resetTrafficTrackerCalls++
            callOrder += "resetTrafficTracker"
        }
    }
}
