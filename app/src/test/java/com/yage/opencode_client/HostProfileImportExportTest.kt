package com.yage.opencode_client

import com.yage.opencode_client.data.model.HostProfile
import com.yage.opencode_client.data.model.HostProfileExportPayload
import com.yage.opencode_client.data.model.HostProfileImportPayload
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HostProfileImportExportTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `exports profile with serverURL and no secret fields`() {
        val profile = HostProfile.defaultDirect("https://opencode.example.com")

        val payload = json.encodeToString(HostProfileExportPayload.from(profile))

        assertTrue(payload.contains("\"serverURL\":\"https://opencode.example.com\""))
        assertFalse(payload.contains("password"))
        assertFalse(payload.contains("private"))
        assertFalse(payload.contains("lastUsedAt"))
    }

    @Test
    fun `imports iOS compatible profile JSON with serverURL`() {
        val payload = """
            {
              "version": 1,
              "name": "OpenCode Server",
              "serverURL": "https://opencode.example.com"
            }
        """.trimIndent()

        val profile = json.decodeFromString<HostProfileImportPayload>(payload).makeProfile()

        assertEquals("OpenCode Server", profile.name)
        assertEquals("https://opencode.example.com", profile.serverUrl)
        assertNull(profile.basicAuth)
    }

    @Test
    fun `imports legacy direct profile JSON with transport field`() {
        // Legacy payloads may still carry an explicit transport=direct marker.
        // Direct profiles (which always have a serverURL) must keep working.
        val payload = """
            {
              "version": 1,
              "name": "Home Server",
              "transport": "direct",
              "serverURL": "https://opencode.example.com"
            }
        """.trimIndent()

        val profile = json.decodeFromString<HostProfileImportPayload>(payload).makeProfile()

        assertEquals("Home Server", profile.name)
        assertEquals("https://opencode.example.com", profile.serverUrl)
    }

    @Test
    fun `legacy ssh-only profile without serverURL fails with explicit SSH message`() {
        // Real legacy SSH payloads had transport=sshTunnel and no serverURL —
        // SSH support has been removed, so these must fail explicitly rather
        // than silently producing a profile with an empty URL.
        val payload = """
            {
              "version": 1,
              "name": "VPS OpenCode",
              "transport": "sshTunnel",
              "host": "vps.example.com",
              "port": 22
            }
        """.trimIndent()

        try {
            json.decodeFromString<HostProfileImportPayload>(payload).makeProfile()
            fail("Expected IllegalArgumentException for legacy SSH-only payload")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Error message should mention SSH removal, was: ${e.message}",
                e.message?.contains("SSH", ignoreCase = true) == true
            )
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import requires serverURL`() {
        HostProfileImportPayload(name = "Broken").makeProfile()
    }
}
