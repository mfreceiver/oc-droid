package com.yage.opencode_client.data.repository.http

import com.yage.opencode_client.util.TrafficLogger
import com.yage.opencode_client.util.TrafficTracker
import io.mockk.mockk
import io.mockk.verify
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * R-18 unit tests for [TrafficCountingInterceptor]. Verifies that the
 * interceptor:
 *  - records sent bytes from the request body's declared length,
 *  - records ACTUAL bytes read from the response body (drained via
 *    `body.string()`), not the (often -1) `contentLength()` header — this is
 *    the post-fix behavior that catches chunked responses,
 *  - delegates to [TrafficTracker.add] and [TrafficLogger.record] on body
 *    close with the right (sent, received) pair.
 *
 * Uses relaxed mockk for both deps so we can `verify` the recorded call
 * without exercising the real SharedPreferences-backed TrafficTracker or
 * the file-backed TrafficLogger.
 */
class TrafficCountingInterceptorTest {

    private val server = MockWebServer()
    private lateinit var tracker: TrafficTracker
    private lateinit var logger: TrafficLogger
    private lateinit var client: OkHttpClient

    @Before
    fun setup() {
        server.start()
        tracker = mockk(relaxed = true)
        logger = mockk(relaxed = true)
        client = OkHttpClient.Builder()
            .addInterceptor(TrafficCountingInterceptor(tracker, logger))
            .build()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `records sent and received bytes for POST with body`() {
        server.enqueue(MockResponse().setBody("hello"))

        val body = "abc".toRequestBody("text/plain".toMediaType())
        client.newCall(Request.Builder().url(server.url("/x")).post(body).build())
            .execute().use { response ->
                // Drain the body to trigger the counting source's close —
                // the tracker.add call happens on close, not on execute().
                response.body?.string()
            }

        // sent = 3 bytes (POST body "abc"), received = 5 bytes ("hello").
        verify(atLeast = 1) { tracker.add(sent = 3L, received = 5L) }
        verify(atLeast = 1) {
            logger.record(method = "POST", url = "/x", sent = 3L, received = 5L, elapsedMs = any())
        }
    }

    @Test
    fun `records zero sent for GET without body`() {
        server.enqueue(MockResponse().setBody("xx"))

        client.newCall(Request.Builder().url(server.url("/y")).build())
            .execute().use { response ->
                response.body?.string()
            }

        verify(atLeast = 1) { tracker.add(sent = 0L, received = 2L) }
        verify(atLeast = 1) {
            logger.record(method = "GET", url = "/y", sent = 0L, received = 2L, elapsedMs = any())
        }
    }

    @Test
    fun `records actual streamed bytes for chunked response`() {
        // Chunked responses report contentLength = -1, which is exactly the
        // case the post-fix counting source was added for: the pre-fix
        // implementation read contentLength() and counted -1 as 0 received.
        server.enqueue(MockResponse().setChunkedBody("abcdefghijklmnopqrstuvwxyz", 4))

        client.newCall(Request.Builder().url(server.url("/z")).build())
            .execute().use { response ->
                response.body?.string()
            }

        verify(atLeast = 1) { tracker.add(sent = 0L, received = 26L) }
    }
}
