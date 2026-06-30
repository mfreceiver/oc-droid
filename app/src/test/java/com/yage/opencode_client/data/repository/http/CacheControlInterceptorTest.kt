package com.yage.opencode_client.data.repository.http

import com.yage.opencode_client.data.repository.HostConfig
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
}
