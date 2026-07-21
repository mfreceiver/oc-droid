package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.BasicAuthConfig
import cn.vectory.ocdroid.data.model.HostProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §G-ACL: unit tests for [HostProfile.migrateForGacl].
 *
 * Coverage:
 *  - Legacy http://host:4097 → https://host:14097 + mtlsEnabled + creds preserved.
 *  - Already-migrated (https + 14097 + mtlsEnabled) → no-op.
 *  - Non-slimapi (http other port / https / file / no port) → unchanged.
 *  - Edge cases: trailing slash, no path, empty URL, authority-less URL.
 *  - slim flag preserved as-is.
 */
class HostProfileMigratorTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun profile(
        serverUrl: String = "http://localhost:4097",
        name: String = "Test",
        username: String? = null,
        passwordId: String? = null,
        mtlsEnabled: Boolean = false,
        slim: Boolean = false,
    ): HostProfile = HostProfile(
        id = "test-id",
        name = name,
        serverUrl = serverUrl,
        basicAuth = if (username != null && passwordId != null) BasicAuthConfig(username, passwordId) else null,
        mtlsEnabled = mtlsEnabled,
        slim = slim,
        serverGroupFp = "test-id",
        lastUsedAt = 0L,
    )

    // ── tests ───────────────────────────────────────────────────────────────

    @Test
    fun `legacy http localhost 4097 migrates to https 14097 with mtlsEnabled`() {
        val original = profile(
            serverUrl = "http://localhost:4097",
            username = "alice",
            passwordId = "pw1",
            slim = true,
        )
        val migrated = original.migrateForGacl()

        assertEquals("https://localhost:14097", migrated.serverUrl)
        assertTrue(migrated.mtlsEnabled)
        // credentials preserved
        assertEquals("alice", migrated.basicAuth?.username)
        assertEquals("pw1", migrated.basicAuth?.passwordId)
        // slim flag preserved
        assertTrue(migrated.slim)
        // id/name/serverGroupFp preserved
        assertEquals(original.id, migrated.id)
        assertEquals(original.name, migrated.name)
    }

    @Test
    fun `legacy http with path 4097 migrates correctly`() {
        val original = profile(serverUrl = "http://example.com:4097/some/path")
        val migrated = original.migrateForGacl()

        assertEquals("https://example.com:14097/some/path", migrated.serverUrl)
        assertTrue(migrated.mtlsEnabled)
    }

    @Test
    fun `legacy http with trailing slash 4097 migrates`() {
        val original = profile(serverUrl = "http://host:4097/")
        val migrated = original.migrateForGacl()

        assertEquals("https://host:14097/", migrated.serverUrl)
        assertTrue(migrated.mtlsEnabled)
    }

    @Test
    fun `already migrated https 14097 mtlsEnabled is no-op`() {
        val original = profile(
            serverUrl = "https://host:14097",
            mtlsEnabled = true,
        )
        val migrated = original.migrateForGacl()

        // Same instance identity (or at least same values)
        assertEquals(original.serverUrl, migrated.serverUrl)
        assertTrue(migrated.mtlsEnabled)
        assertEquals(original, migrated)
    }

    @Test
    fun `non slimapi http non-4097 port stays unchanged`() {
        val original = profile(serverUrl = "http://host:8080")
        val migrated = original.migrateForGacl()

        assertEquals(original, migrated)
        assertFalse(migrated.mtlsEnabled)
    }

    @Test
    fun `non slimapi https any port stays unchanged`() {
        val original = profile(serverUrl = "https://host:443")
        val migrated = original.migrateForGacl()

        assertEquals(original, migrated)
    }

    @Test
    fun `non slimapi http no port stays unchanged`() {
        val original = profile(serverUrl = "http://host")
        val migrated = original.migrateForGacl()

        assertEquals(original, migrated)
    }

    @Test
    fun `file url stays unchanged`() {
        val original = profile(serverUrl = "file:///config")
        val migrated = original.migrateForGacl()

        assertEquals(original, migrated)
    }

    @Test
    fun `empty url stays unchanged`() {
        val original = profile(serverUrl = "")
        val migrated = original.migrateForGacl()

        assertEquals(original, migrated)
    }

    @Test
    fun `null authority url stays unchanged`() {
        val original = profile(serverUrl = "http://")
        val migrated = original.migrateForGacl()

        assertEquals(original, migrated)
    }

    @Test
    fun `slim false preserved on migration`() {
        val original = profile(
            serverUrl = "http://host:4097",
            slim = false,
        )
        val migrated = original.migrateForGacl()

        assertFalse(migrated.slim)
        assertEquals("https://host:14097", migrated.serverUrl)
    }

    @Test
    fun `already migrated https 14097 but mtlsEnabled false is still no-op`() {
        // Per spec idempotent: if the URL is already https:14097, the function
        // does NOT change anything — even if mtlsEnabled is false (inconsistent
        // state that shouldn't occur in practice).
        val original = profile(
            serverUrl = "https://host:14097",
            mtlsEnabled = false,
        )
        val migrated = original.migrateForGacl()

        assertEquals(original.serverUrl, migrated.serverUrl)
        assertFalse(migrated.mtlsEnabled) // unchanged
        assertEquals(original, migrated)
    }
}
