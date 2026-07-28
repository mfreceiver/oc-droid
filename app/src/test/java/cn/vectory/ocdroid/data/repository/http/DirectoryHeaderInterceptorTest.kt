package cn.vectory.ocdroid.data.repository.http

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
 * §R18 Phase 2-E step 2: the global HostConfig workdir fallback was removed;
 * the directory is sourced ONLY from the caller-supplied
 * `X-Opencode-Directory` header. These tests assert the post-removal
 * contract:
 *  - explicit `X-Opencode-Directory` header is preserved,
 *  - no caller header → no injection (request passes through unchanged).
 *
 * Round 1B (lite-v2-dev): Skip-Dir marker stripping and query mirror logic
 * were removed from the interceptor. Tests for those behaviours have been
 * deleted.
 */
class DirectoryHeaderInterceptorTest {

    private val server = MockWebServer()
    private lateinit var client: OkHttpClient

    @Before
    fun setup() {
        server.start()
        client = OkHttpClient.Builder()
            .addInterceptor(DirectoryHeaderInterceptor())
            .build()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `omits directory header when no caller header is present`() {
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/file/status")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertNull(request.getHeader("X-Opencode-Directory"))
    }

    @Test
    fun `preserves caller-supplied directory header`() {
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(
            Request.Builder().url(server.url("/file/status"))
                .header(HttpHeaders.DIRECTORY_HEADER, "/workdir/project")
                .build()
        ).execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals("/workdir/project", request.getHeader("X-Opencode-Directory"))
    }

    @Test
    fun `POST does not mirror directory into query`() {
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(
            Request.Builder().url(server.url("/session/abc/prompt_async"))
                .header(HttpHeaders.DIRECTORY_HEADER, "/workdir/project")
                .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                .build()
        ).execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals("POST keeps the header", "/workdir/project", request.getHeader("X-Opencode-Directory"))
        assertNull("POST must not add directory query", request.requestUrl?.queryParameter("directory"))
    }

    @Test
    fun `GET preserves caller-supplied directory query without overwrite`() {
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(
            Request.Builder().url(server.url("/file/status?directory=/explicit/dir"))
                .header(HttpHeaders.DIRECTORY_HEADER, "/workdir/project")
                .build()
        ).execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals(
            "caller query must win, not be overwritten by the header",
            "/explicit/dir",
            request.requestUrl?.queryParameter("directory")
        )
        assertEquals(
            "header is still kept (double-insurance)",
            "/workdir/project",
            request.getHeader("X-Opencode-Directory")
        )
    }

    @Test
    fun `GET with no caller header adds neither header nor query`() {
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/file/status")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertNull(request.getHeader("X-Opencode-Directory"))
        assertNull(request.requestUrl?.queryParameter("directory"))
    }

    @Test
    fun `DELETE does not mirror directory into query`() {
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(
            Request.Builder().url(server.url("/session/abc"))
                .delete()
                .header(HttpHeaders.DIRECTORY_HEADER, "/workdir/project")
                .build()
        ).execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals("DELETE keeps the header", "/workdir/project", request.getHeader("X-Opencode-Directory"))
        assertNull("DELETE must not add directory query", request.requestUrl?.queryParameter("directory"))
    }

    @Test
    fun `PUT does not mirror directory into query`() {
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(
            Request.Builder().url(server.url("/session/abc"))
                .put(okhttp3.RequestBody.create(null, ByteArray(0)))
                .header(HttpHeaders.DIRECTORY_HEADER, "/workdir/project")
                .build()
        ).execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals("/workdir/project", request.getHeader("X-Opencode-Directory"))
        assertNull("PUT must not add directory query", request.requestUrl?.queryParameter("directory"))
    }

    @Test
    fun `GET under api path preserves both caller-supplied directory and location directory`() {
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(
            Request.Builder()
                .url(server.url("/api/session/abc?directory=/explicit/dir&location[directory]=/explicit/loc"))
                .header(HttpHeaders.DIRECTORY_HEADER, "/workdir/project")
                .build()
        ).execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals("/explicit/dir", request.requestUrl?.queryParameter("directory"))
        assertEquals("/explicit/loc", request.requestUrl?.queryParameter("location[directory]"))
        // Header is kept as double-insurance even when both queries are caller-supplied.
        assertEquals("/workdir/project", request.getHeader("X-Opencode-Directory"))
    }

    @Test
    fun `GET with no caller header under api path adds neither query`() {
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/api/session/abc")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertNull(request.getHeader("X-Opencode-Directory"))
        assertNull(request.requestUrl?.queryParameter("directory"))
        assertNull(request.requestUrl?.queryParameter("location[directory]"))
    }

    @Test
    fun `multiple requests are independently intercepted`() {
        // No state lives on the interceptor; each request is treated on its
        // own. Sanity check that there's no accidental caching.
        // 1) request WITH directory header
        server.enqueue(MockResponse().setBody("ok"))
        client.newCall(
            Request.Builder().url(server.url("/file/status"))
                .header(HttpHeaders.DIRECTORY_HEADER, "/a")
                .build()
        ).execute().use { /* drain */ }
        val r1 = server.takeRequest()
        assertEquals("/a", r1.getHeader("X-Opencode-Directory"))

        // 2) immediately after, request WITHOUT directory → no header
        server.enqueue(MockResponse().setBody("ok"))
        client.newCall(Request.Builder().url(server.url("/file/status")).build())
            .execute().use { /* drain */ }
        val r2 = server.takeRequest()
        assertNull(r2.getHeader("X-Opencode-Directory"))
        assertNull(r2.requestUrl?.queryParameter("directory"))
    }
}
