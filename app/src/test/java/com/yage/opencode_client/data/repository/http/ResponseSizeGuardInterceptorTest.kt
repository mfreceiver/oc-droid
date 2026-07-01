package com.yage.opencode_client.data.repository.http

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * R-18 unit tests for [ResponseSizeGuardInterceptor]. Verifies the §OOM P0
 * response-size cap's three branches:
 *
 *  1. SSE (`text/event-stream`) bypass — never capped.
 *  2. Known `Content-Length` over cap → close + throw before any byte is read.
 *  3. Unknown length (chunked, `-1`) → lazy counting source throws mid-read
 *     past the cap.
 *
 * The cap is lowered to 10 bytes via the `internal constructor(cap)` for
 * these tests so the trip path can be exercised without allocating 16 MB
 * strings. Production uses the default 32 MB cap via the `@Inject` no-arg
 * constructor.
 */
class ResponseSizeGuardInterceptorTest {

    private val server = MockWebServer()

    @Before
    fun setup() {
        server.start()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun clientWithCap(cap: Long): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(ResponseSizeGuardInterceptor(cap))
            .build()

    @Test
    fun `passes through response within cap`() {
        server.enqueue(MockResponse().setBody("hello"))

        val client = clientWithCap(cap = 100L)
        client.newCall(Request.Builder().url(server.url("/x")).build())
            .execute().use { response ->
                assertEquals("hello", response.body?.string())
            }
    }

    @Test
    fun `throws when Content-Length exceeds cap before read`() {
        // Body is 26 bytes — over the 10-byte cap. Interceptor sees
        // contentLength = 26 > cap, closes the response, and throws
        // IOException during execute().
        server.enqueue(MockResponse().setBody("abcdefghijklmnopqrstuvwxyz"))

        val client = clientWithCap(cap = 10L)
        var thrown: Throwable? = null
        try {
            client.newCall(Request.Builder().url(server.url("/x")).build()).execute()
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue(
            "expected IOException when Content-Length > cap, got ${thrown?.javaClass?.name}: ${thrown?.message}",
            thrown is IOException
        )
        val msg = thrown!!.message ?: ""
        assertTrue(
            "exception message should mention 'too large' and the path, got: $msg",
            msg.contains("too large") && msg.contains("/x")
        )
    }

    @Test
    fun `throws mid-read when streamed body exceeds cap`() {
        // Chunked body (contentLength = -1) — exercises the lazy counting
        // source: the response is returned by execute(), but reading past
        // the cap during body.string() throws IOException.
        server.enqueue(MockResponse().setChunkedBody("abcdefghijklmnopqrstuvwxyz", 4))

        val client = clientWithCap(cap = 10L)
        val response = client.newCall(Request.Builder().url(server.url("/x")).build())
            .execute()
        var thrown: Throwable? = null
        try {
            response.body?.string()
        } catch (e: Throwable) {
            thrown = e
        } finally {
            response.close()
        }
        assertTrue(
            "expected IOException when streamed body exceeds cap, got ${thrown?.javaClass?.name}: ${thrown?.message}",
            thrown is IOException
        )
    }

    @Test
    fun `does not throw on event-stream body regardless of size`() {
        // SSE bypass: the cap must never apply to text/event-stream, since
        // SSE is consumed incrementally and a byte cap would prematurely
        // kill a long stream. The body here exceeds the 10-byte cap but
        // the event-stream subtype disables the guard.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: abcdefghijklmnopqrstuvwxyz\n\n")
        )

        val client = clientWithCap(cap = 10L)
        client.newCall(Request.Builder().url(server.url("/stream")).build())
            .execute().use { response ->
                // Read the whole body — must NOT throw despite exceeding cap.
                val body = response.body?.string().orEmpty()
                assertTrue(
                    "event-stream body should be readable in full",
                    body.contains("data: abcdefghijklmnopqrstuvwxyz")
                )
            }
    }

    @Test
    fun `default cap matches the documented 32 MB constant`() {
        assertEquals(
            "MAX_RESPONSE_BYTES is the documented OOM P0 cap",
            32L * 1024 * 1024,
            ResponseSizeGuardInterceptor.MAX_RESPONSE_BYTES
        )
    }
}
