package cn.vectory.ocdroid

import android.util.Log
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class HostProfileStoreTest {
    private lateinit var settings: SettingsManager
    private lateinit var store: HostProfileStore
    private var hostProfilesJson: String? = null
    private var currentHostProfileId: String? = null

    @Before
    fun setUp() {
        // HostProfileStore logs warnings on SSH migration and on parse failure.
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0

        settings = mockk(relaxed = true)
        every { settings.hostProfilesJson } answers { hostProfilesJson }
        every { settings.hostProfilesJson = any() } answers { hostProfilesJson = firstArg(); Unit }
        every { settings.currentHostProfileId } answers { currentHostProfileId }
        every { settings.currentHostProfileId = any() } answers { currentHostProfileId = firstArg(); Unit }
        every { settings.serverUrl } returns "https://legacy.example.com"
        every { settings.username } returns "legacy-user"
        every { settings.password } returns "legacy-password"
        every { settings.password = any() } just runs
        store = HostProfileStore(settings)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `migrates legacy server settings into default profile`() {
        val profiles = store.profiles()

        assertEquals(1, profiles.size)
        assertEquals("https://legacy.example.com", profiles.first().serverUrl)
        assertEquals("legacy-user", profiles.first().basicAuth?.username)
        assertEquals(profiles.first().id, currentHostProfileId)
    }

    @Test
    fun `save select duplicate and delete profiles`() {
        val original = store.currentProfile()
        val remote = HostProfile(
            name = "Remote",
            serverUrl = "https://opencode.example.com"
        )

        store.save(remote)
        assertEquals(2, store.profiles().size)

        val selected = store.select(remote.id)
        assertEquals(remote.id, selected.id)
        assertEquals(remote.id, currentHostProfileId)

        val duplicate = store.duplicate(remote.id)
        assertNotEquals(remote.id, duplicate.id)
        assertTrue(duplicate.name.contains("Copy"))

        store.delete(original.id)
        assertEquals(2, store.profiles().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cannot delete last profile`() {
        val only = store.currentProfile()
        store.delete(only.id)
    }

    @Test
    fun `malformed host profile list does not overwrite original data`() {
        val corrupted = "{ this is not valid JSON "
        hostProfilesJson = corrupted

        val profiles = store.profiles()

        // Decoder must fail soft: returns empty list rather than crashing.
        assertEquals(0, profiles.size)
        // Critical data-protection guarantee: the original (corrupt) JSON is
        // preserved on disk so the user can recover it. profiles() must NOT
        // trigger a saveProfiles() that would replace it with a fresh default.
        assertEquals(corrupted, hostProfilesJson)
    }

    @Test
    fun `removes legacy ssh profiles during decode and persists cleanup`() {
        val directProfile = HostProfile(
            id = "direct-1",
            name = "Direct",
            serverUrl = "https://opencode.example.com"
        )
        val rawArray = """
            [
              {"id":"ssh-1","name":"Old SSH","transport":"sshTunnel","serverURL":"https://old.example.com"},
              {"id":"direct-1","name":"Direct","serverURL":"https://opencode.example.com"}
            ]
        """.trimIndent()
        hostProfilesJson = rawArray
        currentHostProfileId = "direct-1"

        val profiles = store.profiles()

        assertEquals(1, profiles.size)
        assertEquals("direct-1", profiles.first().id)

        // The cleanup must be persisted (sshTunnel entry removed) and the
        // remaining direct profile still readable.
        val persisted = hostProfilesJson ?: error("expected persisted JSON")
        assertFalse(persisted.contains("sshTunnel"))
        assertTrue(persisted.contains("direct-1"))
    }

    @Test
    fun `preserves direct profiles with explicit transport field`() {
        val rawArray = """
            [
              {"id":"d-1","name":"Direct","transport":"direct","serverURL":"https://opencode.example.com"}
            ]
        """.trimIndent()
        hostProfilesJson = rawArray

        val profiles = store.profiles()

        assertEquals(1, profiles.size)
        assertEquals("d-1", profiles.first().id)
        assertEquals("https://opencode.example.com", profiles.first().serverUrl)
    }
}
