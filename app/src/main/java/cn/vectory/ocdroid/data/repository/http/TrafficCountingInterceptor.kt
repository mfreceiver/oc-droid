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
 * Extracted verbatim from `OpenCodeRepository.baseBuilder` in R-18.
 */
@Singleton
class TrafficCountingInterceptor @Inject constructor(
    private val trafficTracker: TrafficTracker,
    private val trafficLogger: TrafficLogger
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.encodedPath
        val method = request.method
        val sentBytes = request.body?.contentLength() ?: 0L
        val sent = if (sentBytes > 0L) sentBytes else 0L
        val startTime = System.currentTimeMillis()
        val response = chain.proceed(request)
        val elapsed = System.currentTimeMillis() - startTime
        val body = response.body
        if (body == null) {
            trafficTracker.add(sent = sent, received = 0L)
            trafficLogger.record(method, url, sent, 0L, elapsed)
            return response
        }
        val counter = object : ForwardingSource(body.source()) {
            var received = 0L
            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = super.read(sink, byteCount)
                if (read > 0L) received += read
                return read
            }
            override fun close() {
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
}
