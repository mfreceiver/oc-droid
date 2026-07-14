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
}
