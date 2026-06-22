package com.yage.opencode_client

import com.yage.opencode_client.ssh.KnownHostCheck
import com.yage.opencode_client.ssh.KnownHostStore
import com.yage.opencode_client.util.SettingsManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KnownHostStoreTest {
    private lateinit var settings: SettingsManager
    private lateinit var store: KnownHostStore
    private var knownHostsJson: String? = null

    @Before
    fun setUp() {
        settings = mockk(relaxed = true)
        every { settings.knownHostsJson } answers { knownHostsJson }
        every { settings.knownHostsJson = any() } answers { knownHostsJson = firstArg(); Unit }
        store = KnownHostStore(settings)
    }

    @Test
    fun `trusts first use then matches same host key`() {
        val key = "host-key".toByteArray()

        val first = store.checkOrTrust("Gateway.EXAMPLE.com", 8006, key)
        val second = store.checkOrTrust("gateway.example.com", 8006, key)

        assertTrue(first is KnownHostCheck.TrustedFirstUse)
        assertTrue(second is KnownHostCheck.Match)
        assertEquals((first as KnownHostCheck.TrustedFirstUse).fingerprint, (second as KnownHostCheck.Match).fingerprint)
    }

    @Test
    fun `returns mismatch when host key changes`() {
        store.checkOrTrust("gateway.example.com", 8006, "old-key".toByteArray())

        val result = store.checkOrTrust("gateway.example.com", 8006, "new-key".toByteArray())

        assertTrue(result is KnownHostCheck.Mismatch)
    }

    @Test
    fun `clear removes trusted host`() {
        store.checkOrTrust("gateway.example.com", 8006, "old-key".toByteArray())
        store.clear("gateway.example.com", 8006)

        val result = store.checkOrTrust("gateway.example.com", 8006, "new-key".toByteArray())

        assertTrue(result is KnownHostCheck.TrustedFirstUse)
    }
}
