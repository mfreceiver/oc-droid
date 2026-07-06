package cn.vectory.ocdroid.data.repository.http

import cn.vectory.ocdroid.data.repository.HostConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R-18 unit tests for [CachePathSanitizer] — pure function of the current
 * `hostConfig.baseUrl` snapshot, no HTTP needed.
 *
 * Verifies the §Stage D (gpter 阻塞 #2) prefix-stripping contract that the
 * cache whitelist relies on for sub-path deployments (e.g. baseUrl
 * `http://host/opencode/`).
 */
class CachePathSanitizerTest {

    private val hostConfig = HostConfig()
    private val sanitizer = CachePathSanitizer(hostConfig)

    @Test
    fun `bare host returns request path unchanged`() {
        hostConfig.configure(
            baseUrl = "http://localhost:4096",
            username = null,
            password = null,
            allowInsecure = false
        )
        assertEquals("/global/health", sanitizer.cacheRelativePath("/global/health"))
        assertEquals("/session/abc", sanitizer.cacheRelativePath("/session/abc"))
    }

    @Test
    fun `bare host without protocol is also handled`() {
        hostConfig.configure(
            baseUrl = "localhost:4096",
            username = null,
            password = null,
            allowInsecure = false
        )
        assertEquals("/agent", sanitizer.cacheRelativePath("/agent"))
    }

    @Test
    fun `sub-path baseUrl strips the prefix`() {
        hostConfig.configure(
            baseUrl = "http://host.example/opencode",
            username = null,
            password = null,
            allowInsecure = false
        )
        assertEquals(
            "/agent",
            sanitizer.cacheRelativePath("/opencode/agent")
        )
        assertEquals(
            "/global/health",
            sanitizer.cacheRelativePath("/opencode/global/health")
        )
    }

    @Test
    fun `sub-path baseUrl with trailing slash strips the prefix`() {
        hostConfig.configure(
            baseUrl = "http://host.example/opencode/",
            username = null,
            password = null,
            allowInsecure = false
        )
        // trimEnd('/') normalises the trailing slash so the prefix-strip
        // behaves identically to the no-trailing-slash case.
        assertEquals(
            "/command",
            sanitizer.cacheRelativePath("/opencode/command")
        )
    }

    @Test
    fun `path that does not match prefix is returned unchanged`() {
        hostConfig.configure(
            baseUrl = "http://host.example/opencode",
            username = null,
            password = null,
            allowInsecure = false
        )
        // /session is not under /opencode, so it should be returned as-is
        // (the startsWith("$basePath/") check is intentionally strict to
        // avoid stripping substrings that merely share a prefix).
        assertEquals("/other/session", sanitizer.cacheRelativePath("/other/session"))
    }

    @Test
    fun `exact basePath without trailing slash on request is not stripped`() {
        hostConfig.configure(
            baseUrl = "http://host.example/opencode",
            username = null,
            password = null,
            allowInsecure = false
        )
        // Request path equals basePath exactly (no trailing segment) — the
        // startsWith("$basePath/") check is false, so nothing is stripped.
        // This guards against future endpoints like `/opencode2/foo` from
        // accidentally hitting the whitelist.
        assertEquals("/opencode", sanitizer.cacheRelativePath("/opencode"))
        assertEquals("/opencode2/agent", sanitizer.cacheRelativePath("/opencode2/agent"))
    }

    @Test
    fun `sanitizer reacts to hostConfig baseUrl changes`() {
        hostConfig.configure(
            baseUrl = "http://host/a",
            username = null,
            password = null,
            allowInsecure = false
        )
        assertEquals("/agent", sanitizer.cacheRelativePath("/a/agent"))

        hostConfig.configure(
            baseUrl = "http://other/b",
            username = null,
            password = null,
            allowInsecure = false
        )
        assertEquals("/agent", sanitizer.cacheRelativePath("/b/agent"))
        // The old prefix is no longer stripped.
        assertEquals("/a/agent", sanitizer.cacheRelativePath("/a/agent"))
    }

    // ---- Phase 5: branch-coverage edge cases ----

    @Test
    fun `default baseUrl leaves all request paths unchanged`() {
        // DEFAULT_SERVER has no path prefix → hostStart finds '/', pathStart
        // returns the substring from '/', trimEnd('/') yields "". Empty
        // basePath short-circuits to "return requestPath unchanged".
        // Default HostConfig uses http://localhost:4096 (no configure() call).
        assertEquals("/", sanitizer.cacheRelativePath("/"))
        assertEquals("/global/health", sanitizer.cacheRelativePath("/global/health"))
        assertEquals("/session/abc/message", sanitizer.cacheRelativePath("/session/abc/message"))
    }

    @Test
    fun `empty requestPath is returned unchanged`() {
        hostConfig.configure(
            baseUrl = "http://host/opencode",
            username = null,
            password = null,
            allowInsecure = false
        )
        // Empty string does NOT start with "$basePath/" so it falls through
        // to the else branch (return as-is). Pin this so the sanitizer
        // never produces an empty-prefix-stripped garbage value.
        assertEquals("", sanitizer.cacheRelativePath(""))
    }

    @Test
    fun `multi-segment basePath is stripped wholesale`() {
        hostConfig.configure(
            baseUrl = "http://host/api/v2",
            username = null,
            password = null,
            allowInsecure = false
        )
        // The whole "/api/v2" prefix must come off in one shot.
        assertEquals("/session", sanitizer.cacheRelativePath("/api/v2/session"))
        assertEquals("/global/health", sanitizer.cacheRelativePath("/api/v2/global/health"))
    }

    @Test
    fun `multi-segment basePath with trailing slash is stripped`() {
        hostConfig.configure(
            baseUrl = "http://host/api/v2/",
            username = null,
            password = null,
            allowInsecure = false
        )
        // trimEnd('/') normalises the trailing slash so the strip behaves
        // identically to the no-trailing-slash case.
        assertEquals("/agent", sanitizer.cacheRelativePath("/api/v2/agent"))
    }

    @Test
    fun `requestPath that shares prefix segment but not full basePath is unchanged`() {
        hostConfig.configure(
            baseUrl = "http://host/api/v2",
            username = null,
            password = null,
            allowInsecure = false
        )
        // "/api/v3/agent" only shares "/api" prefix; the strict startsWith
        // check requires the FULL basePath, so this is NOT stripped.
        assertEquals("/api/v3/agent", sanitizer.cacheRelativePath("/api/v3/agent"))
        // And a request that equals the basePath WITHOUT a trailing segment
        // is also returned unchanged (startsWith("$basePath/") is false).
        assertEquals("/api/v2", sanitizer.cacheRelativePath("/api/v2"))
    }

    @Test
    fun `baseUrl consisting of only a path is handled`() {
        // No protocol, no host — just "/opencode". The protocol-end branch
        // (protocolEnd < 0) sets hostStart=0, then the FIRST '/' is at
        // index 0, which makes pathStart=0 and basePath="/opencode".
        hostConfig.configure(
            baseUrl = "/opencode",
            username = null,
            password = null,
            allowInsecure = false
        )
        assertEquals("/agent", sanitizer.cacheRelativePath("/opencode/agent"))
        assertEquals("/opencode", sanitizer.cacheRelativePath("/opencode"))
    }

    @Test
    fun `sanitizer reads the latest baseUrl snapshot after multiple reconfigures`() {
        // Three consecutive configs; each must replace the previous basePath.
        hostConfig.configure("http://h/x", null, null, false)
        assertEquals("/agent", sanitizer.cacheRelativePath("/x/agent"))
        assertEquals("/y/agent", sanitizer.cacheRelativePath("/y/agent"))

        hostConfig.configure("http://h/y", null, null, false)
        assertEquals("/agent", sanitizer.cacheRelativePath("/y/agent"))
        assertEquals("/x/agent", sanitizer.cacheRelativePath("/x/agent"))

        hostConfig.configure("http://h", null, null, false)
        // No basePath now → both old prefixed paths returned as-is.
        assertEquals("/x/agent", sanitizer.cacheRelativePath("/x/agent"))
        assertEquals("/y/agent", sanitizer.cacheRelativePath("/y/agent"))
        assertEquals("/agent", sanitizer.cacheRelativePath("/agent"))
    }
}
