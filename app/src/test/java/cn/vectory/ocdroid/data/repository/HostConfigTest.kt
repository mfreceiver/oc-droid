package cn.vectory.ocdroid.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-18 Phase 5 coverage: [HostConfig] is the thread-safe holder for the
 * currently-active host profile (baseUrl / username / password /
 * allowInsecure) consumed by the OkHttp interceptors
 * (`AuthInterceptor` / `CacheControlInterceptor` / `CachePathSanitizer`).
 *
 * §R18 Phase 2-E step 2 removed the deprecated `_currentDirectory` field; this
 * suite covers the REMAINING surface — defaults, atomic [configure], the
 * [hasBasicAuth] derived flag, and the `DEFAULT_SERVER` constant mirrored by
 * `OpenCodeRepository.DEFAULT_SERVER` (locked by an existing test).
 */
class HostConfigTest {

    @Test
    fun `default baseUrl is the canonical DEFAULT_SERVER`() {
        val config = HostConfig()
        assertEquals(HostConfig.DEFAULT_SERVER, config.baseUrl)
    }

    @Test
    fun `DEFAULT_SERVER points at localhost 4096`() {
        // Mirrored by OpenCodeRepository.DEFAULT_SERVER — pinned here so a
        // future rename cannot silently break the repository test.
        assertEquals("http://localhost:4096", HostConfig.DEFAULT_SERVER)
    }

    @Test
    fun `defaults have no credentials and allowInsecure is false`() {
        val config = HostConfig()
        assertNull(config.username)
        assertNull(config.password)
        assertFalse(config.allowInsecure)
        assertFalse(config.hasBasicAuth)
    }

    @Test
    fun `configure updates baseUrl username password and allowInsecure atomically`() {
        val config = HostConfig()

        config.configure(
            baseUrl = "http://example.com:8080",
            username = "alice",
            password = "secret",
            allowInsecure = true
        )

        assertEquals("http://example.com:8080", config.baseUrl)
        assertEquals("alice", config.username)
        assertEquals("secret", config.password)
        assertTrue(config.allowInsecure)
    }

    @Test
    fun `hasBasicAuth is true only when BOTH username and password are non-null`() {
        val config = HostConfig()

        // (null, null) → false
        assertFalse(config.hasBasicAuth)

        // (user, null) → false
        config.configure("http://h", "user", null, false)
        assertFalse(config.hasBasicAuth)

        // (null, pass) → false
        config.configure("http://h", null, "pass", false)
        assertFalse(config.hasBasicAuth)

        // (user, pass) → true
        config.configure("http://h", "user", "pass", false)
        assertTrue(config.hasBasicAuth)
    }

    @Test
    fun `reconfigure clears previously-set credentials`() {
        val config = HostConfig().apply {
            configure("http://h", "alice", "secret", true)
        }
        assertTrue(config.hasBasicAuth)
        assertTrue(config.allowInsecure)

        // A subsequent configure with null credentials MUST wipe them —
        // CacheControlInterceptor / AuthInterceptor both read these
        // reflectively on every request.
        config.configure("http://h2", null, null, false)

        assertNull(config.username)
        assertNull(config.password)
        assertFalse(config.hasBasicAuth)
        assertFalse(config.allowInsecure)
        assertEquals("http://h2", config.baseUrl)
    }

    @Test
    fun `configure stores baseUrl verbatim without normalising trailing slash`() {
        // HostConfig deliberately does NOT normalise — that is Retrofit's
        // job (buildRetrofit does `trimEnd('/') + "/"`). The holder preserves
        // the raw value so CachePathSanitizer can derive the basePath from
        // whatever shape the caller supplied.
        val config = HostConfig()
        config.configure("http://host/opencode/", null, null, false)
        assertEquals("http://host/opencode/", config.baseUrl)

        config.configure("http://host/opencode", null, null, false)
        assertEquals("http://host/opencode", config.baseUrl)
    }

    @Test
    fun `empty username and password strings still count as basic auth`() {
        // The contract is non-null check (not blank check) — matches
        // AuthInterceptor which does `?: return proceed`. Empty string IS
        // a configured credential from the holder's perspective; callers
        // are expected to validate non-blank before configure().
        val config = HostConfig()
        config.configure("http://h", "", "", false)
        assertTrue(config.hasBasicAuth)
        assertEquals("", config.username)
        assertEquals("", config.password)
    }

    @Test
    fun `configure is observable from another HostConfig snapshot-free on single thread`() {
        // Single-thread sanity check: every field reflects the latest
        // configure() call. (The @Synchronized on configure + @Volatile on
        // each field gives the cross-thread guarantee exercised in
        // OpenCodeRepositoryTest.`configure is thread-safe under concurrent
        // calls`.)
        val config = HostConfig()
        repeat(5) { i ->
            config.configure(
                baseUrl = "http://h$i",
                username = "u$i",
                password = "p$i",
                allowInsecure = i % 2 == 0
            )
            assertEquals("http://h$i", config.baseUrl)
            assertEquals("u$i", config.username)
            assertEquals("p$i", config.password)
            assertEquals(i % 2 == 0, config.allowInsecure)
        }
    }

    @Test
    fun `allowInsecure toggles independently of credentials`() {
        val config = HostConfig()
        // insecure on, creds set
        config.configure("http://h", "u", "p", true)
        assertTrue(config.allowInsecure)
        assertTrue(config.hasBasicAuth)

        // insecure off, creds cleared
        config.configure("http://h", null, null, false)
        assertFalse(config.allowInsecure)
        assertFalse(config.hasBasicAuth)

        // insecure on, no creds
        config.configure("http://h", null, null, true)
        assertTrue(config.allowInsecure)
        assertFalse(config.hasBasicAuth)
    }
}
