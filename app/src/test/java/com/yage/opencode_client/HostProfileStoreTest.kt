package com.yage.opencode_client

import com.yage.opencode_client.data.model.HostProfile
import com.yage.opencode_client.data.model.HostTransport
import com.yage.opencode_client.data.model.SshTunnelConfig
import com.yage.opencode_client.data.repository.HostProfileStore
import com.yage.opencode_client.util.SettingsManager
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HostProfileStoreTest {
    private lateinit var settings: SettingsManager
    private lateinit var store: HostProfileStore
    private var hostProfilesJson: String? = null
    private var currentHostProfileId: String? = null

    @Before
    fun setUp() {
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

    @Test
    fun `migrates legacy server settings into default direct profile`() {
        val profiles = store.profiles()

        assertEquals(1, profiles.size)
        assertEquals(HostTransport.DIRECT, profiles.first().transport)
        assertEquals("https://legacy.example.com", profiles.first().serverUrl)
        assertEquals("legacy-user", profiles.first().basicAuth?.username)
        assertEquals(profiles.first().id, currentHostProfileId)
    }

    @Test
    fun `save select duplicate and delete profiles`() {
        val original = store.currentProfile()
        val ssh = HostProfile(
            name = "VPS",
            transport = HostTransport.SSH_TUNNEL,
            serverUrl = "http://127.0.0.1:4096",
            ssh = SshTunnelConfig(host = "gateway.example.com")
        )

        store.save(ssh)
        assertEquals(2, store.profiles().size)

        val selected = store.select(ssh.id)
        assertEquals(ssh.id, selected.id)
        assertEquals(ssh.id, currentHostProfileId)

        val duplicate = store.duplicate(ssh.id)
        assertNotEquals(ssh.id, duplicate.id)
        assertTrue(duplicate.name.contains("Copy"))

        store.delete(original.id)
        assertEquals(2, store.profiles().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cannot delete last profile`() {
        val only = store.currentProfile()
        store.delete(only.id)
    }
}
