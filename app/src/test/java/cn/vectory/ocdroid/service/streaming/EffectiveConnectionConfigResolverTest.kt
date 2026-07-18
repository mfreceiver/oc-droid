package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

class EffectiveConnectionConfigResolverTest {
    private val profile = HostProfile(
        id = "profile-a",
        name = "Profile",
        serverUrl = "https://profile.example",
        serverGroupFp = "group-a",
    )

    @Test
    fun `manual source wins over differing current profile and survives resolver recreation`() {
        val settings = mockk<SettingsManager>(relaxed = true)
        val profiles = mockk<HostProfileStore>()
        var marker: String? = null
        var url = "https://legacy.example"
        var username: String? = null
        var password: String? = null
        every { settings.effectiveConnectionSourceMarker } answers { marker }
        every { settings.effectiveConnectionSourceMarker = any() } answers { marker = firstArg() }
        every { settings.serverUrl } answers { url }
        every { settings.serverUrl = any() } answers { url = firstArg() }
        every { settings.username } answers { username }
        every { settings.username = any() } answers { username = firstArg() }
        every { settings.password } answers { password }
        every { settings.password = any() } answers { password = firstArg() }
        every { settings.currentWorkdir } returns "/work"
        every { settings.hostProfilesJson } returns "[profile]"
        every { profiles.currentProfile() } returns profile

        DefaultEffectiveConnectionConfigResolver(settings, profiles)
            .activateManual("https://manual.example", "alice", "secret")
        val recreated = DefaultEffectiveConnectionConfigResolver(settings, profiles)

        val resolved = recreated.resolve()!!
        assertEquals(EffectiveConnectionSource.Manual, resolved.source)
        assertEquals("https://manual.example", resolved.url)
        assertEquals("alice", resolved.username)
        assertEquals("secret", resolved.password)
    }

    @Test
    fun `profile activation switches explicit source and resolves selected profile`() {
        val settings = mockk<SettingsManager>(relaxed = true)
        val profiles = mockk<HostProfileStore>()
        var marker: String? = EffectiveConnectionSource.Manual.name
        every { settings.effectiveConnectionSourceMarker } answers { marker }
        every { settings.effectiveConnectionSourceMarker = any() } answers { marker = firstArg() }
        every { settings.currentWorkdir } returns "/work"
        every { profiles.select("profile-a") } returns profile
        every { profiles.currentProfile() } returns profile

        val resolver = DefaultEffectiveConnectionConfigResolver(settings, profiles)
        resolver.activateProfile("profile-a")

        assertEquals(EffectiveConnectionSource.Profile, resolver.resolve()!!.source)
        assertEquals("https://profile.example", resolver.resolve()!!.url)
        verify(exactly = 1) { profiles.select("profile-a") }
    }

    @Test
    fun `legacy migration is profile with stored profiles and manual without them`() {
        fun resolve(rawProfiles: String?): EffectiveConnectionSource {
            val settings = mockk<SettingsManager>(relaxed = true)
            val profiles = mockk<HostProfileStore>()
            var marker: String? = null
            every { settings.effectiveConnectionSourceMarker } answers { marker }
            every { settings.effectiveConnectionSourceMarker = any() } answers { marker = firstArg() }
            every { settings.hostProfilesJson } returns rawProfiles
            every { settings.serverUrl } returns "https://legacy.example"
            every { settings.currentWorkdir } returns null
            every { profiles.currentProfile() } returns profile
            return DefaultEffectiveConnectionConfigResolver(settings, profiles).resolve()!!.source
        }

        assertEquals(EffectiveConnectionSource.Profile, resolve("[profile]"))
        assertEquals(EffectiveConnectionSource.Manual, resolve(null))
    }

    // ───────────── R8 slim-mode foundation / Cluster B: slim wiring ─────
    // The resolver is the service-layer's single source of truth for the
    // effective connection's R8 attributes. slim MUST be populated from
    // HostProfile.slim on both Profile and Manual sources; legacy profiles
    // (no slim field → default false) keep slim=false so nothing activates
    // slim routing by accident.

    @Test
    fun `profile source propagates slim=true from HostProfile`() {
        val settings = mockk<SettingsManager>(relaxed = true)
        val profiles = mockk<HostProfileStore>()
        every { settings.effectiveConnectionSourceMarker } returns EffectiveConnectionSource.Profile.name
        every { settings.currentWorkdir } returns "/work"
        val slimProfile = profile.copy(slim = true)
        every { profiles.currentProfile() } returns slimProfile

        val resolved = DefaultEffectiveConnectionConfigResolver(settings, profiles).resolve()!!

        assertEquals(true, resolved.slim)
    }

    @Test
    fun `profile source propagates slim=false from legacy HostProfile`() {
        val settings = mockk<SettingsManager>(relaxed = true)
        val profiles = mockk<HostProfileStore>()
        every { settings.effectiveConnectionSourceMarker } returns EffectiveConnectionSource.Profile.name
        every { settings.currentWorkdir } returns "/work"
        // profile.slim defaults to false in the fixture above (HostProfile()
        // constructor) — covers the legacy migration case (pre-slim JSON).
        every { profiles.currentProfile() } returns profile

        val resolved = DefaultEffectiveConnectionConfigResolver(settings, profiles).resolve()!!

        assertEquals(false, resolved.slim)
    }

    @Test
    fun `manual source propagates slim from current profile`() {
        // Manual mode (user types URL directly): slim follows the current
        // profile's value (same pattern as mtlsEnabled) — manual URL is
        // typically another endpoint on the same server.
        val settings = mockk<SettingsManager>(relaxed = true)
        val profiles = mockk<HostProfileStore>()
        every { settings.effectiveConnectionSourceMarker } returns EffectiveConnectionSource.Manual.name
        every { settings.serverUrl } returns "https://manual.example"
        every { settings.username } returns null
        every { settings.password } returns null
        every { settings.currentWorkdir } returns "/work"
        every { profiles.currentProfile() } returns profile.copy(slim = true)

        val resolved = DefaultEffectiveConnectionConfigResolver(settings, profiles).resolve()!!

        assertEquals(EffectiveConnectionSource.Manual, resolved.source)
        assertEquals(true, resolved.slim)
    }

    @Test
    fun `manual source with no profile defaults slim to false`() {
        // No profile context (cold start, corrupt JSON, etc.) — fail-safe
        // to legacy direct mode.
        val settings = mockk<SettingsManager>(relaxed = true)
        val profiles = mockk<HostProfileStore>()
        every { settings.effectiveConnectionSourceMarker } returns EffectiveConnectionSource.Manual.name
        every { settings.serverUrl } returns "https://manual.example"
        every { settings.username } returns null
        every { settings.password } returns null
        every { settings.currentWorkdir } returns "/work"
        every { profiles.currentProfile() } throws IllegalStateException("no profiles")

        val resolved = DefaultEffectiveConnectionConfigResolver(settings, profiles).resolve()!!

        assertEquals(false, resolved.slim)
    }

    @Test
    fun `slim and mtlsEnabled are independent R8 dimensions`() {
        // R8 §0: mtls × slim → 4 configs. Both must be populated independently
        // from their respective HostProfile fields. This pins the orthogonality
        // so a future bug (e.g. deriving slim from mtlsEnabled, or vice versa)
        // surfaces here.
        val settings = mockk<SettingsManager>(relaxed = true)
        val profiles = mockk<HostProfileStore>()
        every { settings.effectiveConnectionSourceMarker } returns EffectiveConnectionSource.Profile.name
        every { settings.currentWorkdir } returns "/work"
        every { profiles.currentProfile() } returns profile.copy(
            slim = true,
            mtlsEnabled = true,
            clientCertId = "cert-1"
        )

        val resolved = DefaultEffectiveConnectionConfigResolver(settings, profiles).resolve()!!

        assertEquals(true, resolved.slim)
        assertEquals(true, resolved.mtlsEnabled)
        assertEquals("cert-1", resolved.clientCertId)
    }
}
