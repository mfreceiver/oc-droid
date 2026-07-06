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
 *  - explicit `X-Opencode-Directory` header is preserved and mirrored into
 *    `?directory` (and `?location[directory]` under /api/) for GET/HEAD,
 *  - skip-dir marker is stripped (caller header preserved),
 *  - no caller header → no injection (request passes through unchanged).
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
    fun `skip-dir marker opts out of directory injection and is stripped`() {
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

    // ---- ② directory query rewrite (GET/HEAD mirror into query string) ----

    @Test
    fun `GET mirrors explicit directory into query`() {
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(
            Request.Builder().url(server.url("/file/status"))
                .header(HttpHeaders.DIRECTORY_HEADER, "/workdir/project")
                .build()
        ).execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals("/workdir/project", request.getHeader("X-Opencode-Directory"))
        assertEquals(
            "header is kept as double-insurance",
            "/workdir/project",
            request.requestUrl?.queryParameter("directory")
        )
    }

    @Test
    fun `GET under api path adds both directory and location directory query`() {
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(
            Request.Builder().url(server.url("/api/session/abc"))
                .header(HttpHeaders.DIRECTORY_HEADER, "/workdir/project")
                .build()
        ).execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals("/workdir/project", request.requestUrl?.queryParameter("directory"))
        assertEquals(
            "/workdir/project",
            request.requestUrl?.queryParameter("location[directory]")
        )
    }

    @Test
    fun `GET outside api path does not add location directory query`() {
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(
            Request.Builder().url(server.url("/file/status"))
                .header(HttpHeaders.DIRECTORY_HEADER, "/workdir/project")
                .build()
        ).execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals("/workdir/project", request.requestUrl?.queryParameter("directory"))
        assertNull(request.requestUrl?.queryParameter("location[directory]"))
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
    fun `GET with skip-dir and explicit header still mirrors explicit dir into query`() {
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(
            Request.Builder().url(server.url("/file"))
                .header(HttpHeaders.SKIP_DIR_HEADER, "1")
                .header(HttpHeaders.DIRECTORY_HEADER, "/browse/target")
                .build()
        ).execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals("/browse/target", request.getHeader("X-Opencode-Directory"))
        assertEquals(
            "browse-picker explicit dir is mirrored too (proxy-safe)",
            "/browse/target",
            request.requestUrl?.queryParameter("directory")
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

    // ---- Phase 5: branch-coverage edge cases ----

    @Test
    fun `HEAD mirrors explicit directory into query like GET`() {
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(
            Request.Builder().url(server.url("/file/status"))
                .method("HEAD", null)
                .header(HttpHeaders.DIRECTORY_HEADER, "/workdir/project")
                .build()
        ).execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals("HEAD keeps the header", "/workdir/project", request.getHeader("X-Opencode-Directory"))
        assertEquals(
            "HEAD mirrors directory into the query (same path as GET)",
            "/workdir/project",
            request.requestUrl?.queryParameter("directory")
        )
    }

    @Test
    fun `HEAD under api path adds both directory and location directory query`() {
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(
            Request.Builder().url(server.url("/api/session/abc"))
                .method("HEAD", null)
                .header(HttpHeaders.DIRECTORY_HEADER, "/workdir/project")
                .build()
        ).execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals("/workdir/project", request.requestUrl?.queryParameter("directory"))
        assertEquals("/workdir/project", request.requestUrl?.queryParameter("location[directory]"))
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
    fun `GET under api path preserves caller-supplied location directory query without overwrite`() {
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(
            Request.Builder().url(server.url("/api/session/abc?location[directory]=/explicit/loc"))
                .header(HttpHeaders.DIRECTORY_HEADER, "/workdir/project")
                .build()
        ).execute().use { /* drain */ }

        val request = server.takeRequest()
        // Caller's location[directory] wins; interceptor does NOT overwrite.
        assertEquals(
            "/explicit/loc",
            request.requestUrl?.queryParameter("location[directory]")
        )
        // But ?directory is still added (it was absent on the caller URL).
        assertEquals("/workdir/project", request.requestUrl?.queryParameter("directory"))
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
    fun `path containing api substring but not starting with api does not add location directory`() {
        // The `/api/` check is intentionally `startsWith`, not `contains` —
        // a tunnel that happens to mount the API under /x/api/ would
        // otherwise get an unwanted location[directory] injection.
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(
            Request.Builder().url(server.url("/x/api/session"))
                .header(HttpHeaders.DIRECTORY_HEADER, "/workdir/project")
                .build()
        ).execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals("/workdir/project", request.requestUrl?.queryParameter("directory"))
        assertNull(
            "path that does not START with /api/ must not get location[directory]",
            request.requestUrl?.queryParameter("location[directory]")
        )
    }

    @Test
    fun `skip-dir marker with non-1 value is still stripped`() {
        // The marker is a presence-only flag; the interceptor checks
        // `header(...) != null`, not the value. Pin this so a future
        // "skip only when 1" tightening cannot silently regress.
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(
            Request.Builder().url(server.url("/session"))
                .header(HttpHeaders.SKIP_DIR_HEADER, "true")
                .build()
        ).execute().use { /* drain */ }

        val request = server.takeRequest()
        assertNull(request.getHeader("X-Opencode-Skip-Dir"))
        assertNull(request.getHeader("X-Opencode-Directory"))
    }

    @Test
    fun `POST with skip-dir marker strips marker and adds no query`() {
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(
            Request.Builder().url(server.url("/session/abc/prompt_async"))
                .header(HttpHeaders.SKIP_DIR_HEADER, "1")
                .header(HttpHeaders.DIRECTORY_HEADER, "/workdir/project")
                .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                .build()
        ).execute().use { /* drain */ }

        val request = server.takeRequest()
        assertNull("marker stripped on POST", request.getHeader("X-Opencode-Skip-Dir"))
        assertEquals(
            "explicit directory still preserved on POST",
            "/workdir/project",
            request.getHeader("X-Opencode-Directory")
        )
        assertNull(
            "POST must not mirror directory into query even with skip-dir",
            request.requestUrl?.queryParameter("directory")
        )
    }

    @Test
    fun `GET with no caller header under api path adds neither query`() {
        // Confirms the no-injection branch independently for /api/ paths
        // (the /api/ location[directory] injection is gated on effectiveDir
        // != null, NOT on the path alone).
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
        // 1) request WITH directory
        server.enqueue(MockResponse().setBody("ok"))
        client.newCall(
            Request.Builder().url(server.url("/file/status"))
                .header(HttpHeaders.DIRECTORY_HEADER, "/a")
                .build()
        ).execute().use { /* drain */ }
        val r1 = server.takeRequest()
        assertEquals("/a", r1.requestUrl?.queryParameter("directory"))

        // 2) immediately after, request WITHOUT directory → no injection
        server.enqueue(MockResponse().setBody("ok"))
        client.newCall(Request.Builder().url(server.url("/file/status")).build())
            .execute().use { /* drain */ }
        val r2 = server.takeRequest()
        assertNull(r2.getHeader("X-Opencode-Directory"))
        assertNull(r2.requestUrl?.queryParameter("directory"))
    }
}
