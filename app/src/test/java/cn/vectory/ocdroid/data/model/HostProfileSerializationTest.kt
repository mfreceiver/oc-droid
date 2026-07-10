package cn.vectory.ocdroid.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip + computed-property coverage for [HostProfile], [BasicAuthConfig],
 * [HostTransport], [HostProfileImportPayload], [HostProfileExportPayload].
 *
 * Existing `HostProfileImportExportTest` covers the iOS-compatible import /
 * export payloads (in a different package). This file fills the remaining gaps:
 * direct [HostProfile] round trip, [HostProfile.defaultDirect] factory
 * branches, [displayName]/[connectionSummary], the legacy [HostTransport]
 * enum, and [BasicAuthConfig] serialization.
 *
 * Pure kotlinx.serialization — no Android framework.
 */
class HostProfileSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // ── HostProfile round trip ────────────────────────────────────────────

    @Test
    fun `HostProfile round trip with basic auth`() {
        val profile = HostProfile(
            id = "fixed-id",
            name = "Server",
            serverUrl = "https://opencode.example.com",
            basicAuth = BasicAuthConfig(username = "user", passwordId = "secret-1"),
            tunnelPasswordId = null,
            lastUsedAt = 1234L
        )
        val encoded = json.encodeToString(profile)
        assertTrue(encoded.contains("\"serverURL\":\"https://opencode.example.com\""))
        assertTrue(encoded.contains("\"passwordId\":\"secret-1\""))
        // §tofu R2: allowInsecureConnections field is GONE — its key must NOT
        // appear in the encoded form.
        assertFalse(encoded.contains("allowInsecureConnections"))
        val decoded = json.decodeFromString<HostProfile>(encoded)
        assertEquals(profile, decoded)
    }

    @Test
    fun `HostProfile tolerates unknown JSON fields`() {
        val decoded = json.decodeFromString<HostProfile>(
            """{"id":"x","name":"n","serverURL":"u","transport":"direct","host":"h","allowInsecureConnections":true}"""
        )
        assertEquals("x", decoded.id)
        assertEquals("u", decoded.serverUrl)
    }

    @Test
    fun `HostProfile minimal round trip uses defaults`() {
        val decoded = json.decodeFromString<HostProfile>(
            """{"id":"x","name":"n","serverURL":"u"}"""
        )
        assertEquals("x", decoded.id)
        assertNull(decoded.basicAuth)
        assertNull(decoded.tunnelPasswordId)
        assertNull(decoded.lastUsedAt)
    }

    // ── displayName / connectionSummary ───────────────────────────────────

    @Test
    fun `HostProfile displayName returns trimmed name`() {
        assertEquals("Server", HostProfile(id = "x", name = "  Server  ", serverUrl = "u").displayName)
    }

    @Test
    fun `HostProfile displayName falls back to Untitled for blank name`() {
        assertEquals("Untitled", HostProfile(id = "x", name = "   ", serverUrl = "u").displayName)
        assertEquals("Untitled", HostProfile(id = "x", name = "", serverUrl = "u").displayName)
    }

    @Test
    fun `HostProfile connectionSummary returns trimmed serverUrl`() {
        assertEquals("https://oc.example.com",
            HostProfile(id = "x", name = "n", serverUrl = "  https://oc.example.com  ").connectionSummary)
    }

    // ── defaultDirect factory ─────────────────────────────────────────────

    @Test
    fun `defaultDirect without credentials yields null basicAuth`() {
        val profile = HostProfile.defaultDirect()
        assertEquals("Localhost", profile.name)
        assertEquals("http://localhost:4096", profile.serverUrl)
        assertNull(profile.basicAuth)
        assertNotNull(profile.lastUsedAt)
    }

    @Test
    fun `defaultDirect with username and passwordId yields basicAuth`() {
        val profile = HostProfile.defaultDirect(
            serverUrl = "https://oc.example.com",
            username = "alice",
            passwordId = "pw-1"
        )
        assertEquals("https://oc.example.com", profile.serverUrl)
        assertEquals("alice", profile.basicAuth?.username)
        assertEquals("pw-1", profile.basicAuth?.passwordId)
    }

    @Test
    fun `defaultDirect with blank username yields null basicAuth`() {
        val profile = HostProfile.defaultDirect(username = "   ", passwordId = "pw-1")
        assertNull(profile.basicAuth)
    }

    @Test
    fun `defaultDirect with null passwordId yields null basicAuth`() {
        val profile = HostProfile.defaultDirect(username = "alice", passwordId = null)
        assertNull(profile.basicAuth)
    }

    // ── BasicAuthConfig ───────────────────────────────────────────────────

    @Test
    fun `BasicAuthConfig round trip`() {
        val auth = BasicAuthConfig(username = "bob", passwordId = "secret-2")
        val encoded = json.encodeToString(auth)
        val decoded = json.decodeFromString<BasicAuthConfig>(encoded)
        assertEquals(auth, decoded)
    }

    // ── HostTransport enum ────────────────────────────────────────────────

    @Test
    fun `HostTransport direct serializes as direct`() {
        val encoded = json.encodeToString(HostTransport.DIRECT)
        assertEquals("\"direct\"", encoded)
    }

    @Test
    fun `HostTransport sshTunnel serializes as sshTunnel`() {
        val encoded = json.encodeToString(HostTransport.SSH_TUNNEL)
        assertEquals("\"sshTunnel\"", encoded)
    }

    @Test
    fun `HostTransport round trips from string`() {
        val direct = json.decodeFromString<HostTransport>("\"direct\"")
        assertEquals(HostTransport.DIRECT, direct)
        val tunnel = json.decodeFromString<HostTransport>("\"sshTunnel\"")
        assertEquals(HostTransport.SSH_TUNNEL, tunnel)
    }

    // ── HostProfileExportPayload.from ─────────────────────────────────────

    @Test
    fun `HostProfileExportPayload from copies name and url`() {
        // §tofu R2: export payload no longer carries allowInsecureConnections
        // (the field is gone from HostProfile; TOFU replaces the trust toggle).
        val profile = HostProfile(
            id = "x", name = "Export Me", serverUrl = "https://oc.example.com"
        )
        val payload = HostProfileExportPayload.from(profile)
        assertEquals("Export Me", payload.name)
        assertEquals("https://oc.example.com", payload.serverUrl)
        assertEquals(1, payload.version)
    }

    @Test
    fun `HostProfileExportPayload from uses displayName not raw name`() {
        // displayName trims whitespace and falls back to "Untitled" for blank.
        val profile = HostProfile(id = "x", name = "  Spaced  ", serverUrl = "u")
        val payload = HostProfileExportPayload.from(profile)
        assertEquals("Spaced", payload.name)
    }

    @Test
    fun `HostProfileExportPayload round trip`() {
        // §tofu R2: payload dropped its allowInsecureConnections field.
        val payload = HostProfileExportPayload(
            version = 2, name = "N", serverUrl = "u"
        )
        val encoded = json.encodeToString(payload)
        val decoded = json.decodeFromString<HostProfileExportPayload>(encoded)
        assertEquals(payload, decoded)
    }

    // ── HostProfileImportPayload.makeProfile ──────────────────────────────

    @Test
    fun `HostProfileImportPayload makeProfile preserves name and trims url`() {
        // §tofu R2: payload dropped its allowInsecureConnections field;
        // makeProfile just seeds name + url.
        val payload = HostProfileImportPayload(
            version = 1,
            name = "From Import",
            serverUrl = "  https://oc.example.com  ",
        )
        val profile = payload.makeProfile()
        assertEquals("From Import", profile.name)
        // URL is trimmed before validation.
        assertEquals("https://oc.example.com", profile.serverUrl)
    }

    @Test
    fun `HostProfileImportPayload minimal defaults`() {
        val payload = json.decodeFromString<HostProfileImportPayload>(
            """{"name":"n"}"""
        )
        assertNull(payload.version)
        assertNull(payload.serverUrl)
    }
}
