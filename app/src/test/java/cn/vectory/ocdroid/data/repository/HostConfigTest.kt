package cn.vectory.ocdroid.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-18 Phase 5 coverage: [HostConfig] is the thread-safe holder for the
 * currently-active host profile (baseUrl / username / password / hostPort)
 * consumed by the OkHttp interceptors (`AuthInterceptor` /
 * `CacheControlInterceptor` / `CachePathSanitizer`) and the SSL config
 * resolution path.
 *
 * §R18 Phase 2-E step 2 removed the deprecated `_currentDirectory` field.
 * §tofu R2 replaced the boolean `allowInsecure` field with `hostPort: String?`
 * (the host:port authority of `_baseUrl` — the TOFU pin store key). This suite
 * covers the REMAINING surface — defaults, atomic [configure], the
 * [hasBasicAuth] derived flag, hostPort derivation, and the `DEFAULT_SERVER`
 * constant mirrored by `OpenCodeRepository.DEFAULT_SERVER`.
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
    fun `defaults have no credentials and hostPort is null`() {
        val config = HostConfig()
        assertNull(config.username)
        assertNull(config.password)
        assertNull(config.hostPort)
        assertFalse(config.hasBasicAuth)
    }

    @Test
    fun `configure updates baseUrl username password and hostPort atomically`() {
        val config = HostConfig()

        config.configure(
            baseUrl = "http://example.com:8080",
            username = "alice",
            password = "secret",
            hostPort = "example.com:8080"
        )

        assertEquals("http://example.com:8080", config.baseUrl)
        assertEquals("alice", config.username)
        assertEquals("secret", config.password)
        assertEquals("example.com:8080", config.hostPort)
    }

    @Test
    fun `configure derives hostPort from baseUrl when the caller passes null`() {
        val config = HostConfig()
        // https + no explicit port → 443 default.
        config.configure("https://example.com", null, null, null)
        assertEquals("example.com:443", config.hostPort)
        // http + explicit port preserved.
        config.configure("http://example.com:8080", null, null, null)
        assertEquals("example.com:8080", config.hostPort)
        // http + no explicit port → 80 default.
        config.configure("http://example.com", null, null, null)
        assertEquals("example.com:80", config.hostPort)
    }

    @Test
    fun `hasBasicAuth is true only when BOTH username and password are non-null`() {
        val config = HostConfig()

        // (null, null) → false
        assertFalse(config.hasBasicAuth)

        // (user, null) → false
        config.configure("http://h", "user", null, null)
        assertFalse(config.hasBasicAuth)

        // (null, pass) → false
        config.configure("http://h", null, "pass", null)
        assertFalse(config.hasBasicAuth)

        // (user, pass) → true
        config.configure("http://h", "user", "pass", null)
        assertTrue(config.hasBasicAuth)
    }

    @Test
    fun `reconfigure clears previously-set credentials`() {
        val config = HostConfig().apply {
            configure("http://h", "alice", "secret", "h:443")
        }
        assertTrue(config.hasBasicAuth)
        assertEquals("h:443", config.hostPort)

        // A subsequent configure with null credentials MUST wipe them —
        // CacheControlInterceptor / AuthInterceptor both read these
        // reflectively on every request.
        config.configure("http://h2", null, null, null)

        assertNull(config.username)
        assertNull(config.password)
        assertFalse(config.hasBasicAuth)
        assertEquals("h2:80", config.hostPort) // http default port 80 (derived from baseUrl when hostPort=null)
        assertEquals("http://h2", config.baseUrl)
    }

    @Test
    fun `configure stores baseUrl verbatim without normalising trailing slash`() {
        // HostConfig deliberately does NOT normalise — that is Retrofit's
        // job (buildRetrofit does `trimEnd('/') + "/"`). The holder preserves
        // the raw value so CachePathSanitizer can derive the basePath from
        // whatever shape the caller supplied.
        val config = HostConfig()
        config.configure("http://host/opencode/", null, null, null)
        assertEquals("http://host/opencode/", config.baseUrl)

        config.configure("http://host/opencode", null, null, null)
        assertEquals("http://host/opencode", config.baseUrl)
    }

    @Test
    fun `empty username and password strings still count as basic auth`() {
        // The contract is non-null check (not blank check) — matches
        // AuthInterceptor which does `?: return proceed`. Empty string IS
        // a configured credential from the holder's perspective; callers
        // are expected to validate non-blank before configure().
        val config = HostConfig()
        config.configure("http://h", "", "", null)
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
                baseUrl = "http://h$i:8080",
                username = "u$i",
                password = "p$i",
                hostPort = "h$i:8080"
            )
            assertEquals("http://h$i:8080", config.baseUrl)
            assertEquals("u$i", config.username)
            assertEquals("p$i", config.password)
            assertEquals("h$i:8080", config.hostPort)
        }
    }

    @Test
    fun `hostPort toggles independently of credentials`() {
        val config = HostConfig()
        // hostPort set, creds set
        config.configure("http://h", "u", "p", "h:443")
        assertEquals("h:443", config.hostPort)
        assertTrue(config.hasBasicAuth)

        // hostPort null → re-derived from baseUrl; creds cleared
        config.configure("http://h2", null, null, null)
        assertEquals("h2:80", config.hostPort)
        assertFalse(config.hasBasicAuth)

        // hostPort explicit, no creds
        config.configure("http://h", null, null, "explicit:9999")
        assertEquals("explicit:9999", config.hostPort)
        assertFalse(config.hasBasicAuth)
    }
}
