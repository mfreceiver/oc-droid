package cn.vectory.ocdroid.data.repository.http

import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Traffic accounting: records the request body (sent) and ACTUAL bytes read
 * from the response body (received) — wraps the body source with a counting
 * [ForwardingSource] that reports the true byte total when the body is
 * closed (after the converter has read it).
 *
 * The pre-R-18 implementation read `contentLength()`, which is `-1` for
 * chunked responses — so the large chunked message responses (the very ones
 * causing OOM) were counted as `0` received, hiding the real bandwidth.
 * This interceptor preserves the post-fix behavior (counts actual streamed
 * bytes via the wrapping source), including for SSE / `text/event-stream`.
 *
 * O-C weak-network §2: extends the per-byte counting with per-category
 * attribution via [TrafficLedgerSnapshot]. Category is inferred from the
 * request URL path: `/slimapi/` prefix → slimapi, everything else → tunnel.
 * The counters are atomic so cross-thread reads (via [snapshot]) see a
 * consistent (though possibly slightly stale) sum.
 *
 * Extracted verbatim from `OpenCodeRepository.baseBuilder` in R-18.
 */
@Singleton
class TrafficCountingInterceptor @Inject constructor(
    private val trafficTracker: TrafficTracker,
    private val trafficLogger: TrafficLogger
) : Interceptor {

    // §2: per-category ledger counters (atomic for thread-safe snapshot)
    private val _slimapiBytes = AtomicLong(0L)
    private val _slimapiRequests = AtomicLong(0L)
    private val _tunnelBytes = AtomicLong(0L)
    private val _tunnelRequests = AtomicLong(0L)

    /**
     * Returns an atomic snapshot of the current per-category traffic ledger.
     */
    fun snapshot(): TrafficLedgerSnapshot = TrafficLedgerSnapshot(
        slimapiBytes = _slimapiBytes.get(),
        slimapiRequests = _slimapiRequests.get(),
        tunnelBytes = _tunnelBytes.get(),
        tunnelRequests = _tunnelRequests.get(),
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.encodedPath
        val method = request.method
        val isSlimapi = url.startsWith("/slimapi/")
        val sentBytes = request.body?.contentLength() ?: 0L
        val sent = if (sentBytes > 0L) sentBytes else 0L
        val startTime = System.currentTimeMillis()
        val response = chain.proceed(request)
        val elapsed = System.currentTimeMillis() - startTime
        val body = response.body
        if (body == null) {
            record(isSlimapi, sent, 0L)
            trafficTracker.add(sent = sent, received = 0L)
            trafficLogger.record(method, url, sent, 0L, elapsed)
            return response
        }
        val counter = object : ForwardingSource(body.source()) {
            var received = 0L
            private var closed = false
            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = super.read(sink, byteCount)
                if (read > 0L) received += read
                return read
            }
            override fun close() {
                if (closed) return
                closed = true
                record(isSlimapi, sent, received)
                trafficTracker.add(sent = sent, received = received)
                trafficLogger.record(method, url, sent, received, elapsed)
                super.close()
            }
        }
        val countedBody = object : ResponseBody() {
            override fun contentType(): okhttp3.MediaType? = body.contentType()
            override fun contentLength(): Long = body.contentLength()
            override fun source(): BufferedSource = counter.buffer()
        }
        return response.newBuilder().body(countedBody).build()
    }

    /**
     * Thread-safe per-category recording: increments the byte total and request
     * count for the given category.
     */
    private fun record(isSlimapi: Boolean, sent: Long, received: Long) {
        if (isSlimapi) {
            _slimapiBytes.addAndGet(sent + received)
            _slimapiRequests.incrementAndGet()
        } else {
            _tunnelBytes.addAndGet(sent + received)
            _tunnelRequests.incrementAndGet()
        }
    }
}
