package cn.vectory.ocdroid.data.repository.http

import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
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
    private lateinit var interceptor: TrafficCountingInterceptor
    private lateinit var client: OkHttpClient

    @Before
    fun setup() {
        server.start()
        tracker = mockk(relaxed = true)
        logger = mockk(relaxed = true)
        interceptor = TrafficCountingInterceptor(tracker, logger)
        client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
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

    // ── O-C weak-network §2: traffic-attribution ledger tests ───────────────

    @Test
    fun `slimapi request increments slimapi category on ledger`() {
        server.enqueue(MockResponse().setBody("ok"))
        client.newCall(Request.Builder().url(server.url("/slimapi/sessions")).build())
            .execute().use { it.body?.string() }

        val snap = interceptor.snapshot()
        println("DEBUG slimapi test: slimapiRequests=${snap.slimapiRequests} slimapiBytes=${snap.slimapiBytes}")
        org.junit.Assert.assertTrue(
            "slimapiRequests should be ≥1, got ${snap.slimapiRequests}",
            snap.slimapiRequests >= 1,
        )
        org.junit.Assert.assertTrue(
            "slimapiBytes should be sum of sent+received, got ${snap.slimapiBytes}",
            snap.slimapiBytes >= 2L /* "ok" = 2 bytes */,
        )
    }

    @Test
    fun `non-slimapi request increments tunnel category on ledger`() {
        server.enqueue(MockResponse().setBody("tunnel"))
        client.newCall(Request.Builder().url(server.url("/health")).build())
            .execute().use { it.body?.string() }

        val snap = interceptor.snapshot()
        org.junit.Assert.assertTrue(
            "tunnelRequests should be ≥1, got ${snap.tunnelRequests}",
            snap.tunnelRequests >= 1,
        )
        org.junit.Assert.assertTrue(
            "tunnelBytes should be ≥6 (length of 'tunnel'), got ${snap.tunnelBytes}",
            snap.tunnelBytes >= 6L,
        )
    }

    @Test
    fun `multiple requests accumulate correctly across categories`() {
        // slimapi request
        server.enqueue(MockResponse().setBody("a"))
        client.newCall(Request.Builder().url(server.url("/slimapi/health")).build())
            .execute().use { it.body?.string() }

        // tunnel request
        server.enqueue(MockResponse().setBody("bb"))
        client.newCall(Request.Builder().url(server.url("/rest/config")).build())
            .execute().use { it.body?.string() }

        // another tunnel request
        server.enqueue(MockResponse().setBody("ccc"))
        client.newCall(Request.Builder().url(server.url("/command/run")).build())
            .execute().use { it.body?.string() }

        val snap = interceptor.snapshot()
        org.junit.Assert.assertTrue(
            "slimapiRequests should be 1, got ${snap.slimapiRequests}",
            snap.slimapiRequests == 1L,
        )
        org.junit.Assert.assertTrue(
            "slimapiBytes should be ≥1 (body 'a' = 1 byte), got ${snap.slimapiBytes}",
            snap.slimapiBytes >= 1L,
        )
        org.junit.Assert.assertTrue(
            "tunnelRequests should be 2, got ${snap.tunnelRequests}",
            snap.tunnelRequests == 2L,
        )
        org.junit.Assert.assertTrue(
            "tunnelBytes should be ≥5 (bodies 'bb' + 'ccc' = 5 bytes), got ${snap.tunnelBytes}",
            snap.tunnelBytes >= 5L,
        )
    }
}
