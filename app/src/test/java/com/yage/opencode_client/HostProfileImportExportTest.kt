package com.yage.opencode_client

import com.yage.opencode_client.data.model.HostProfile
import com.yage.opencode_client.data.model.HostProfileExportPayload
import com.yage.opencode_client.data.model.HostProfileImportPayload
import com.yage.opencode_client.data.model.HostTransport
import com.yage.opencode_client.data.model.SshTunnelConfig
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostProfileImportExportTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `exports direct profile with iOS-compatible transport and serverURL`() {
        val profile = HostProfile.defaultDirect("https://opencode.example.com")

        val payload = json.encodeToString(HostProfileExportPayload.from(profile))

        assertTrue(payload.contains("\"transport\":\"direct\""))
        assertTrue(payload.contains("\"serverURL\":\"https://opencode.example.com\""))
        assertFalse(payload.contains("password"))
        assertFalse(payload.contains("private"))
        assertFalse(payload.contains("lastUsedAt"))
    }

    @Test
    fun `exports ssh profile without runtime or secret fields`() {
        val profile = HostProfile(
            name = "VPS",
            transport = HostTransport.SSH_TUNNEL,
            serverUrl = "http://127.0.0.1:4096",
            ssh = SshTunnelConfig(host = "gateway.example.com", port = 8006, username = "opencode", remotePort = 19001),
            lastUsedAt = 1234L
        )

        val payload = json.encodeToString(HostProfileExportPayload.from(profile))

        assertTrue(payload.contains("\"transport\":\"sshTunnel\""))
        assertTrue(payload.contains("\"host\":\"gateway.example.com\""))
        assertTrue(payload.contains("\"remotePort\":19001"))
        assertFalse(payload.contains("serverURL"))
        assertFalse(payload.contains("private"))
        assertFalse(payload.contains("fingerprint"))
        assertFalse(payload.contains("lastUsedAt"))
    }

    @Test
    fun `imports iOS ssh profile JSON`() {
        val payload = """
            {
              "version": 1,
              "name": "VPS OpenCode",
              "transport": "sshTunnel",
              "ssh": {
                "host": "gateway.example.com",
                "port": 8006,
                "username": "opencode",
                "remotePort": 19001
              }
            }
        """.trimIndent()

        val profile = json.decodeFromString<HostProfileImportPayload>(payload).makeProfile()

        assertEquals("VPS OpenCode", profile.name)
        assertEquals(HostTransport.SSH_TUNNEL, profile.transport)
        assertEquals("http://127.0.0.1:4096", profile.serverUrl)
        assertEquals("gateway.example.com", profile.ssh?.host)
        assertEquals(19001, profile.ssh?.remotePort)
        assertNull(profile.basicAuth)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `direct import requires serverURL`() {
        HostProfileImportPayload(name = "Broken", transport = HostTransport.DIRECT).makeProfile()
    }
}
