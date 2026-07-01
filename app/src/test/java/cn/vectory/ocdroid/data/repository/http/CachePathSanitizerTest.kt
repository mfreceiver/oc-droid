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
}
