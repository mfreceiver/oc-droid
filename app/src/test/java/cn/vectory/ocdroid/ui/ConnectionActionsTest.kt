package cn.vectory.ocdroid.ui

import android.util.Log
import cn.vectory.ocdroid.data.model.BasicAuthConfig
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionCacheEntry
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.http.SslConfig
import cn.vectory.ocdroid.ui.util.HttpImageHolder
import cn.vectory.ocdroid.util.MarkdownFontSizes
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.ThemeMode
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §R18 Phase 5+: direct unit tests for [applySavedSettings] and
 * [applyReloadDisabledModelsForCurrentHost].
 *
 * Covers the cold-start seed path (~130 lines): repository configure with
 * BasicAuth, archived-session filtering on cached metadata, open-tabs-list
 * cleaning, ConnectionPhase.Reconnecting signal, settings slice seeding from
 * prefs, and per-host disabled-model reload.
 */
class ConnectionActionsTest {

    private lateinit var store: SharedStateStore
    private lateinit var slices: SliceFlows
    private lateinit var repository: OpenCodeRepository
    private lateinit var settingsManager: SettingsManager
    private lateinit var hostProfileStore: HostProfileStore

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        store = SharedStateStore()
        slices = store.slices
        repository = mockk(relaxed = true)
        // §2.5(c): applySavedSettings 现调 repository.currentSslConfig() →
        // HttpImageHolder.updateSsl(...)。relaxed mock 无法为 sealed SslConfig 自动
        // 造实例，显式 stub 成 SystemDefault（与冷启动默认信任策略一致）。
        every { repository.currentSslConfig() } returns SslConfig.SystemDefault
        settingsManager = mockk(relaxed = true)
        hostProfileStore = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `applySavedSettings configures repository with profile url and basic auth`() {
        val profile = HostProfile(
            name = "p",
            serverUrl = "https://example.test",
            basicAuth = BasicAuthConfig(username = "alice", passwordId = "pid"))
        every { hostProfileStore.currentProfile() } returns profile
        every { hostProfileStore.profiles() } returns listOf(profile)
        every { settingsManager.basicAuthPassword("pid") } returns "secret"

        applySavedSettings(repository, settingsManager, hostProfileStore, slices)

        verify {
            repository.configure(
                baseUrl = "https://example.test",
                username = "alice",
                password = "secret",
                hostPort = "example.test:443")
        }
    }

    /**
     * §review-blocker-#6/B1 (cold-start trustAll): a trust-all profile MUST
     * propagate trustAll=true to repository.configure at cold start, so the
     * live stack AND HttpImageHolder (updated from currentSslConfig() below the
     * configure call) reflect the per-server trust policy from boot. Pre-fix
     * this defaulted to false → a self-signed host's markdown images kept
     * failing the SSL handshake for the whole process lifetime (the bootstrap's
     * later full configure does not re-stamp HttpImageHolder). Includes the
     * final SSL-mode assertion (HttpImageHolder received TrustAll, not the
     * SystemDefault the relaxed mock would otherwise yield).
     */
    @Test
    fun `applySavedSettings propagates trustAll at cold start and stamps HttpImageHolder`() {
        HttpImageHolder.resetTestState()
        val profile = HostProfile(
            name = "self-signed",
            serverUrl = "https://selfsigned.local",
            trustAll = true,
        )
        every { hostProfileStore.currentProfile() } returns profile
        every { hostProfileStore.profiles() } returns listOf(profile)
        // After configure() with trustAll=true, currentSslConfig() must report
        // TrustAll (the cold-start path feeds it to HttpImageHolder). The
        // relaxed mock returns SystemDefault by default; stub the post-configure
        // value to TrustAll to model the real factory's behavior.
        every { repository.currentSslConfig() } returns SslConfig.TrustAll

        applySavedSettings(repository, settingsManager, hostProfileStore, slices)

        verify {
            repository.configure(
                baseUrl = "https://selfsigned.local",
                username = null,
                password = null,
                hostPort = "selfsigned.local:443",
                clientCert = null,
                slim = false,
                trustAll = true,
            )
        }
        assertEquals(
            "HttpImageHolder stamped TRUST_ALL after cold-start configure",
            "TRUST_ALL",
            HttpImageHolder.lastUpdateSslMode,
        )
    }

    /**
     * §review-blocker-#6/B1 negative control: a trustAll=false (default) profile
     * MUST propagate trustAll=false — confirms the new argument path is wired
     * for both branches and HttpImageHolder stays SystemDefault.
     */
    @Test
    fun `applySavedSettings propagates trustAll=false for default profile`() {
        HttpImageHolder.resetTestState()
        val profile = HostProfile(
            name = "ca",
            serverUrl = "https://ca.example",
            trustAll = false,
        )
        every { hostProfileStore.currentProfile() } returns profile
        every { hostProfileStore.profiles() } returns listOf(profile)
        every { repository.currentSslConfig() } returns SslConfig.SystemDefault

        applySavedSettings(repository, settingsManager, hostProfileStore, slices)

        verify {
            repository.configure(
                baseUrl = "https://ca.example",
                username = null,
                password = null,
                hostPort = "ca.example:443",
                clientCert = null,
                slim = false,
                trustAll = false,
            )
        }
        assertEquals(
            "HttpImageHolder stamped SYSTEM for trustAll=false",
            "SYSTEM",
            HttpImageHolder.lastUpdateSslMode,
        )
    }

    @Test
    fun `applySavedSettings signals Reconnecting when a profile is configured`() {
        val profile = HostProfile.defaultDirect(serverUrl = "http://x")
        every { hostProfileStore.currentProfile() } returns profile
        every { hostProfileStore.profiles() } returns listOf(profile)

        applySavedSettings(repository, settingsManager, hostProfileStore, slices)

        assertEquals(ConnectionPhase.Reconnecting, slices.connection.value.connectionPhase)
    }

    @Test
    fun `applySavedSettings signals Idle when no profile is configured`() {
        val profile = HostProfile.defaultDirect(serverUrl = "http://x")
        every { hostProfileStore.currentProfile() } returns profile
        every { hostProfileStore.profiles() } returns emptyList()

        applySavedSettings(repository, settingsManager, hostProfileStore, slices)

        assertEquals(ConnectionPhase.Idle, slices.connection.value.connectionPhase)
    }

    @Test
    fun `applySavedSettings restores cached sessions into sessionList slice`() {
        val profile = HostProfile.defaultDirect(serverUrl = "http://x")
        every { hostProfileStore.currentProfile() } returns profile
        every { hostProfileStore.profiles() } returns listOf(profile)
        val cacheEntry = SessionCacheEntry(id = "s1", directory = "/workdir", title = "Cached")
        every { settingsManager.sessionCache } returns listOf(cacheEntry)

        applySavedSettings(repository, settingsManager, hostProfileStore, slices)

        val restored = slices.sessionList.value.sessions
        assertTrue(restored.any { it.id == "s1" && it.title == "Cached" })
    }

    @Test
    fun `applySavedSettings filters archived sessions out of session seed (B4)`() {
        val profile = HostProfile.defaultDirect(serverUrl = "http://x")
        every { hostProfileStore.currentProfile() } returns profile
        every { hostProfileStore.profiles() } returns listOf(profile)
        val active = SessionCacheEntry(id = "active", directory = "/w")
        val archived = SessionCacheEntry(id = "archived", directory = "/w", timeArchived = 1000L)
        every { settingsManager.sessionCache } returns listOf(active, archived)

        applySavedSettings(repository, settingsManager, hostProfileStore, slices)

        // §B4: seed sessions exclude archived; no open-tabs list.
        assertTrue(slices.sessionList.value.sessions.any { it.id == "active" })
        assertTrue(slices.sessionList.value.sessions.none { it.id == "archived" })
        assertNull(slices.chat.value.currentSessionId)
    }

    @Test
    fun `applySavedSettings clears currentSessionId when it points at an archived session`() {
        val profile = HostProfile.defaultDirect(serverUrl = "http://x")
        every { hostProfileStore.currentProfile() } returns profile
        every { hostProfileStore.profiles() } returns listOf(profile)
        val archived = SessionCacheEntry(id = "archived", directory = "/w", timeArchived = 1000L)
        every { settingsManager.sessionCache } returns listOf(archived)
        every { settingsManager.currentSessionId } returns "archived"

        applySavedSettings(repository, settingsManager, hostProfileStore, slices)

        assertNull(slices.chat.value.currentSessionId)
    }

    // ── §fix-orphan-upgrade: empty open tabs ⇒ null current on cold start ─

    @Test
    fun `applySavedSettings always nulls current on cold start (B4 list-detail)`() {
        // §B4 / §10 cold start: route is Sessions; never restore currentSessionId
        // into the detail pane. Self-heal disk.
        val profile = HostProfile.defaultDirect(serverUrl = "http://x")
        every { hostProfileStore.currentProfile() } returns profile
        every { hostProfileStore.profiles() } returns listOf(profile)
        every { settingsManager.sessionCache } returns listOf(
            SessionCacheEntry(id = "stale_ses", directory = "/w", title = "Stale"))
        every { settingsManager.currentSessionId } returns "stale_ses"

        applySavedSettings(repository, settingsManager, hostProfileStore, slices)

        assertNull("cold start → no restored current", slices.chat.value.currentSessionId)
        assertNull("content null on cold start", slices.chat.value.content)
        verify { settingsManager.currentSessionId = null }
    }

    @Test
    fun `applySavedSettings does not restore current even when cache has live id (B4)`() {
        val profile = HostProfile.defaultDirect(serverUrl = "http://x")
        every { hostProfileStore.currentProfile() } returns profile
        every { hostProfileStore.profiles() } returns listOf(profile)
        every { settingsManager.sessionCache } returns listOf(
            SessionCacheEntry(id = "ses_x", directory = "/w", title = "Live"))
        every { settingsManager.currentSessionId } returns "ses_x"

        applySavedSettings(repository, settingsManager, hostProfileStore, slices)

        assertNull(slices.chat.value.currentSessionId)
        verify { settingsManager.currentSessionId = null }
        // Session metadata still seeds the list.
        assertTrue(slices.sessionList.value.sessions.any { it.id == "ses_x" })
    }

    @Test
    fun `applySavedSettings seeds settings slice from persisted prefs`() {
        val profile = HostProfile.defaultDirect(serverUrl = "http://x")
        every { hostProfileStore.currentProfile() } returns profile
        every { hostProfileStore.profiles() } returns listOf(profile)
        every { settingsManager.themeMode } returns ThemeMode.DARK
        every { settingsManager.markdownFontSizes } returns MarkdownFontSizes()
        every { settingsManager.getDisabledModels(any()) } returns setOf("openai/gpt-x")
        every { settingsManager.uiFontScale } returns 1.2f
        every { settingsManager.uiContentScale } returns 0.9f

        applySavedSettings(repository, settingsManager, hostProfileStore, slices)

        val settings = slices.settings.value
        // §chat-ux-batch T8 (B3): selectedAgentName field was deleted; the
        // remaining slice seeds (theme / disabledModels / uiScale) are the
        // authoritative ones.
        assertEquals(ThemeMode.DARK, settings.themeMode)
        assertEquals(setOf("openai/gpt-x"), settings.disabledModels)
        assertEquals(1.2f, settings.uiFontScale, 0.0001f)
        assertEquals(0.9f, settings.uiContentScale, 0.0001f)
    }

    // §chat-ux-batch T8 (B3): the former test
    // `applySavedSettings leaves selectedAgentName null when no pref (server default)`
    // was DELETED here. The selectedAgentName field + seedAgent read were
    // removed (T7 rewired agent selection to the TRANSIENT pendingAgent
    // chat-slice field).

    @Test
    fun `applySavedSettings seeds host slice with profiles list`() {
        val profile = HostProfile.defaultDirect(serverUrl = "http://x")
        val otherProfile = HostProfile.defaultDirect(serverUrl = "http://y")
        every { hostProfileStore.currentProfile() } returns profile
        every { hostProfileStore.profiles() } returns listOf(profile, otherProfile)

        applySavedSettings(repository, settingsManager, hostProfileStore, slices)

        assertEquals(2, slices.host.value.hostProfiles.size)
        assertEquals(profile.id, slices.host.value.currentHostProfileId)
    }

    // ── applyReloadDisabledModelsForCurrentHost ───────────────────────────────

    @Test
    fun `applyReloadDisabledModelsForCurrentHost writes per-host disabled set into settings slice`() {
        val profile = HostProfile.defaultDirect(serverUrl = "https://h1.test")
        every { hostProfileStore.currentProfile() } returns profile
        // R-20 Phase 5: per-fp keying (was per-baseUrl). §需求12: fp == id.
        every { settingsManager.getDisabledModels(profile.id) } returns setOf("anthropic/claude")

        applyReloadDisabledModelsForCurrentHost(settingsManager, hostProfileStore, slices)

        assertEquals(setOf("anthropic/claude"), slices.settings.value.disabledModels)
    }

    @Test
    fun `applyReloadDisabledModelsForCurrentHost writes empty set when host has no disabled models`() {
        val profile = HostProfile.defaultDirect(serverUrl = "https://h2.test")
        every { hostProfileStore.currentProfile() } returns profile
        every { settingsManager.getDisabledModels(any()) } returns emptySet()

        applyReloadDisabledModelsForCurrentHost(settingsManager, hostProfileStore, slices)

        assertTrue(slices.settings.value.disabledModels.isEmpty())
    }
}
