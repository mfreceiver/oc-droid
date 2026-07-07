package cn.vectory.ocdroid.ui

import android.util.Log
import cn.vectory.ocdroid.data.model.BasicAuthConfig
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionCacheEntry
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
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
 * BasicAuth, archived-session filtering on cached metadata, openSessionIds
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
            basicAuth = BasicAuthConfig(username = "alice", passwordId = "pid"),
        )
        every { hostProfileStore.currentProfile() } returns profile
        every { hostProfileStore.profiles() } returns listOf(profile)
        every { settingsManager.basicAuthPassword("pid") } returns "secret"

        applySavedSettings(repository, settingsManager, hostProfileStore, slices)

        verify {
            repository.configure(
                baseUrl = "https://example.test",
                username = "alice",
                password = "secret",
                allowInsecureConnections = false,
            )
        }
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
        every { settingsManager.openSessionIds } returns listOf("s1")

        applySavedSettings(repository, settingsManager, hostProfileStore, slices)

        val restored = slices.sessionList.value.sessions
        assertTrue(restored.any { it.id == "s1" && it.title == "Cached" })
        assertEquals(listOf("s1"), slices.sessionList.value.openSessionIds)
    }

    @Test
    fun `applySavedSettings filters archived sessions out of openSessionIds`() {
        val profile = HostProfile.defaultDirect(serverUrl = "http://x")
        every { hostProfileStore.currentProfile() } returns profile
        every { hostProfileStore.profiles() } returns listOf(profile)
        val active = SessionCacheEntry(id = "active", directory = "/w")
        val archived = SessionCacheEntry(id = "archived", directory = "/w", timeArchived = 1000L)
        every { settingsManager.sessionCache } returns listOf(active, archived)
        every { settingsManager.openSessionIds } returns listOf("active", "archived")

        applySavedSettings(repository, settingsManager, hostProfileStore, slices)

        // archived evicted.
        assertEquals(listOf("active"), slices.sessionList.value.openSessionIds)
        verify { settingsManager.openSessionIds = listOf("active") }
    }

    @Test
    fun `applySavedSettings clears currentSessionId when it points at an archived session`() {
        val profile = HostProfile.defaultDirect(serverUrl = "http://x")
        every { hostProfileStore.currentProfile() } returns profile
        every { hostProfileStore.profiles() } returns listOf(profile)
        val archived = SessionCacheEntry(id = "archived", directory = "/w", timeArchived = 1000L)
        every { settingsManager.sessionCache } returns listOf(archived)
        every { settingsManager.openSessionIds } returns listOf("archived")
        every { settingsManager.currentSessionId } returns "archived"

        applySavedSettings(repository, settingsManager, hostProfileStore, slices)

        assertNull(slices.chat.value.currentSessionId)
    }

    @Test
    fun `applySavedSettings seeds settings slice from persisted prefs`() {
        val profile = HostProfile.defaultDirect(serverUrl = "http://x")
        every { hostProfileStore.currentProfile() } returns profile
        every { hostProfileStore.profiles() } returns listOf(profile)
        every { settingsManager.selectedAgentName } returns "code"
        every { settingsManager.themeMode } returns ThemeMode.DARK
        every { settingsManager.markdownFontSizes } returns MarkdownFontSizes()
        every { settingsManager.getDisabledModels(any()) } returns setOf("openai/gpt-x")
        every { settingsManager.uiFontScale } returns 1.2f
        every { settingsManager.uiContentScale } returns 0.9f

        applySavedSettings(repository, settingsManager, hostProfileStore, slices)

        val settings = slices.settings.value
        assertEquals("code", settings.selectedAgentName)
        assertEquals(ThemeMode.DARK, settings.themeMode)
        assertEquals(setOf("openai/gpt-x"), settings.disabledModels)
        assertEquals(1.2f, settings.uiFontScale, 0.0001f)
        assertEquals(0.9f, settings.uiContentScale, 0.0001f)
    }

    @Test
    fun `applySavedSettings defaults selectedAgentName to build when no pref`() {
        val profile = HostProfile.defaultDirect(serverUrl = "http://x")
        every { hostProfileStore.currentProfile() } returns profile
        every { hostProfileStore.profiles() } returns listOf(profile)
        every { settingsManager.selectedAgentName } returns null
        every { settingsManager.themeMode } returns ThemeMode.SYSTEM
        every { settingsManager.markdownFontSizes } returns MarkdownFontSizes()
        every { settingsManager.getDisabledModels(any()) } returns emptySet()
        every { settingsManager.uiFontScale } returns 1f
        every { settingsManager.uiContentScale } returns 1f

        applySavedSettings(repository, settingsManager, hostProfileStore, slices)

        assertEquals("build", slices.settings.value.selectedAgentName)
    }

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
        // R-20 Phase 5: per-serverGroupFp keying (was per-baseUrl).
        every { settingsManager.getDisabledModels(profile.serverGroupFp.ifBlank { profile.id }) } returns setOf("anthropic/claude")

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
