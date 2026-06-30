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

/**
 * R-18 unit tests for [DirectoryHeaderInterceptor]. Uses MockWebServer so the
 * interceptor sees a real OkHttp [okhttp3.Interceptor.Chain] (the same path
 * the production client takes) — no mocking of internal OkHttp types.
 *
 * Asserts the §16.1(b) directory-scoping contract:
 *  - directory header is injected when a workdir is set,
 *  - omitted when no workdir is set,
 *  - skip-dir marker opts out AND is stripped before the request leaves.
 */
class DirectoryHeaderInterceptorTest {

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
            .addInterceptor(DirectoryHeaderInterceptor(hostConfig))
            .build()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `injects directory header when workdir is set`() {
        hostConfig.setCurrentDirectory("/workdir/project")
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/file/status")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals("/workdir/project", request.getHeader("X-Opencode-Directory"))
    }

    @Test
    fun `omits directory header when no workdir is set`() {
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/file/status")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertNull(request.getHeader("X-Opencode-Directory"))
    }

    @Test
    fun `clears directory header after setCurrentDirectory null`() {
        hostConfig.setCurrentDirectory("/workdir/project")
        hostConfig.setCurrentDirectory(null)
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/file/status")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertNull(request.getHeader("X-Opencode-Directory"))
    }

    @Test
    fun `skip-dir marker opts out of directory injection`() {
        hostConfig.setCurrentDirectory("/workdir/project")
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(
            Request.Builder().url(server.url("/session"))
                .header(HttpHeaders.SKIP_DIR_HEADER, "1")
                .build()
        ).execute().use { /* drain */ }

        val request = server.takeRequest()
        assertNull(
            "skip-dir endpoint must not carry directory header",
            request.getHeader("X-Opencode-Directory")
        )
        assertNull(
            "skip-dir marker must be stripped before sending",
            request.getHeader("X-Opencode-Skip-Dir")
        )
    }

    @Test
    fun `skip-dir marker preserves an explicit directory header`() {
        // getFileTreeForDirectory sets BOTH headers: skip-dir marker AND an
        // explicit @Header("X-Opencode-Directory") for the browse target.
        // The interceptor must strip the marker WITHOUT touching the
        // caller-supplied directory value.
        hostConfig.setCurrentDirectory("/workdir/project")
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(
            Request.Builder().url(server.url("/file"))
                .header(HttpHeaders.SKIP_DIR_HEADER, "1")
                .header(HttpHeaders.DIRECTORY_HEADER, "/browse/target")
                .build()
        ).execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals(
            "caller-supplied directory must survive skip-dir strip",
            "/browse/target",
            request.getHeader("X-Opencode-Directory")
        )
        assertNull(
            "skip-dir marker must be stripped",
            request.getHeader("X-Opencode-Skip-Dir")
        )
    }
}
