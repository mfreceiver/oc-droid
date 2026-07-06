package cn.vectory.ocdroid.data.repository.http

import cn.vectory.ocdroid.data.repository.HostConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * R-18 unit tests for [CacheControlInterceptor]. Verifies the §16.1(b)
 * cache-safety gate:
 *  - whitelisted GET without Basic Auth → no `Cache-Control` (cacheable).
 *  - whitelisted GET with Basic Auth → `Cache-Control: no-store`.
 *  - non-whitelisted GET → `Cache-Control: no-store`.
 *  - non-GET methods → `Cache-Control: no-store`.
 */
class CacheControlInterceptorTest {

    private val server = MockWebServer()
    private lateinit var hostConfig: HostConfig
    private lateinit var client: OkHttpClient

    @Before
    fun setup() {
        server.start()
        hostConfig = HostConfig().apply {
            configure(
                baseUrl = server.url("/").toString().trimEnd('/'),
                username = null,
                password = null,
                allowInsecure = false
            )
        }
        client = OkHttpClient.Builder()
            .addInterceptor(
                CacheControlInterceptor(hostConfig, CachePathSanitizer(hostConfig))
            )
            .build()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `whitelisted GET without auth omits Cache-Control`() {
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/global/health")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertNull(
            "cacheable endpoint must NOT carry no-store",
            request.getHeader("Cache-Control")
        )
    }

    @Test
    fun `whitelisted GET with auth forces no-store`() {
        hostConfig.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = "alice",
            password = "secret",
            allowInsecure = false
        )
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/global/health")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals(
            "auth must force no-store even on whitelisted endpoints",
            "no-store",
            request.getHeader("Cache-Control")
        )
    }

    @Test
    fun `non-whitelisted GET forces no-store`() {
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/session")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals("no-store", request.getHeader("Cache-Control"))
    }

    @Test
    fun `POST method forces no-store regardless of path`() {
        server.enqueue(MockResponse().setBody("ok"))

        val body = "{}".toRequestBody("application/json".toMediaType())
        client.newCall(
            Request.Builder().url(server.url("/global/health")).post(body).build()
        ).execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals(
            "non-GET methods must force no-store",
            "no-store",
            request.getHeader("Cache-Control")
        )
    }

    @Test
    fun `all four whitelist entries are cacheable when no auth`() {
        for (path in listOf("/config/providers", "/agent", "/command")) {
            server.enqueue(MockResponse().setBody("ok"))

            client.newCall(Request.Builder().url(server.url(path)).build())
                .execute().use { /* drain */ }

            val request = server.takeRequest()
            assertNull(
                "$path should be cacheable (no Cache-Control header)",
                request.getHeader("Cache-Control")
            )
        }
    }

    // ---- Phase 5: branch-coverage edge cases ----

    @Test
    fun `HEAD method forces no-store even on whitelisted path`() {
        // The cacheable check requires method == "GET" explicitly; HEAD
        // is treated like any other non-cacheable method.
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(
            Request.Builder().url(server.url("/global/health")).method("HEAD", null).build()
        ).execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals("no-store", request.getHeader("Cache-Control"))
    }

    @Test
    fun `DELETE method forces no-store even on whitelisted path`() {
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(
            Request.Builder().url(server.url("/agent")).delete().build()
        ).execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals("no-store", request.getHeader("Cache-Control"))
    }

    @Test
    fun `sub-path baseUrl strips prefix before whitelist match`() {
        // A whitelisted endpoint deployed under a sub-path (e.g. behind a
        // reverse proxy mount) must still match the whitelist AFTER the
        // sanitizer strips the prefix. This is the §Stage D (gpter 阻塞 #2)
        // contract end-to-end through the interceptor.
        hostConfig.configure(
            baseUrl = server.url("/opencode").toString().trimEnd('/'),
            username = null,
            password = null,
            allowInsecure = false
        )
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/opencode/global/health")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertNull(
            "cacheable endpoint behind sub-path must NOT carry no-store",
            request.getHeader("Cache-Control")
        )
    }

    @Test
    fun `sub-path baseUrl still forces no-store for non-whitelisted endpoint`() {
        hostConfig.configure(
            baseUrl = server.url("/opencode").toString().trimEnd('/'),
            username = null,
            password = null,
            allowInsecure = false
        )
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/opencode/session")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals("no-store", request.getHeader("Cache-Control"))
    }

    @Test
    fun `global health whitelist entry is cacheable when no auth`() {
        // Covers the "/global/health" entry separately from the loop above
        // (which only iterates 3 of the 4 entries).
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/global/health")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertNull(request.getHeader("Cache-Control"))
    }

    @Test
    fun `non-whitelisted path that shares prefix with whitelist entry forces no-store`() {
        // Exact-match contract: "/agents" is NOT "/agent" and must force
        // no-store. Guards against a future endsWith relaxation.
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/agents")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals("no-store", request.getHeader("Cache-Control"))
    }

    @Test
    fun `configuring auth after a no-auth request flips subsequent requests to no-store`() {
        // First request: no auth, cacheable.
        server.enqueue(MockResponse().setBody("ok"))
        client.newCall(Request.Builder().url(server.url("/global/health")).build())
            .execute().use { /* drain */ }
        val r1 = server.takeRequest()
        assertNull(r1.getHeader("Cache-Control"))

        // Now configure credentials; the SAME path must now force no-store.
        hostConfig.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = "alice",
            password = "secret",
            allowInsecure = false
        )
        server.enqueue(MockResponse().setBody("ok"))
        client.newCall(Request.Builder().url(server.url("/global/health")).build())
            .execute().use { /* drain */ }
        val r2 = server.takeRequest()
        assertEquals(
            "auth config flip must force no-store on the same path",
            "no-store",
            r2.getHeader("Cache-Control")
        )
    }
}
