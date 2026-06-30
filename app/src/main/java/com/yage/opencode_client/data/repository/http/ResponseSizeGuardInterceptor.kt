package com.yage.opencode_client.data.repository.http

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §OOM P0: response-size guard (REST client only). The
 * kotlinx-serialization converter reads the WHOLE response body into a
 * single `String` before deserializing; a long agentic session's
 * `getMessages` can embed tens of MB of verbatim tool output, and a ~124 MB
 * body → ~248 MB char array that OOMs the 256 MB heap.
 *
 * Strategy:
 *  1. Skip SSE (`text/event-stream`) — consumed incrementally, not
 *     buffered, so a byte cap would prematurely kill a long stream. (The
 *     REST client should not normally see event-stream; kept as a
 *     defensive fallback per R-04.)
 *  2. Fast path: known `Content-Length` within cap → pass through; over cap
 *     → close + throw before reading.
 *  3. Unknown length (`-1`, chunked): wrap the body source with a LAZY
 *     counting [ForwardingSource] that throws mid-read past the cap (no
 *     buffering, preserves streaming). This is the fix for the confirmed
 *     chunked-bypass OOM.
 *
 * Extracted from `OpenCodeRepository.buildRestClient` in R-18; behavior
 * preserved byte-for-byte. The `cap` is fixed at [MAX_RESPONSE_BYTES] for
 * production; an `internal` secondary constructor lets unit tests exercise
 * the trip path with small bodies.
 */
@Singleton
class ResponseSizeGuardInterceptor @Inject constructor() : Interceptor {

    @Volatile
    private var maxBytes: Long = MAX_RESPONSE_BYTES

    /** Test-only: lower the cap to exercise the trip path with small bodies. */
    @Suppress("unused") // used by ResponseSizeGuardInterceptorTest
    internal constructor(cap: Long) : this() {
        maxBytes = cap
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body ?: return response
        // 1. SSE bypass — never cap an event stream.
        val mediaType = body.contentType()
        if (mediaType != null && mediaType.subtype.contains("event-stream")) {
            return response
        }
        val len = body.contentLength()
        // 2. Fast path: known length within cap → pass through.
        if (len in 1..maxBytes) return response
        // Known length over cap → close + throw before any byte is read.
        if (len > maxBytes) {
            response.close()
            throw IOException(
                "Response too large (>${maxBytes / (1024 * 1024)}MB, Content-Length=$len): ${chain.request().url.encodedPath}"
            )
        }
        // 3. Unknown length (-1 / chunked): lazy counting source that throws
        //    past the cap mid-read.
        val original = body.source()
        val counting = object : ForwardingSource(original) {
            var total = 0L
            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = super.read(sink, byteCount)
                if (read > 0L) {
                    total += read
                    if (total > maxBytes) {
                        throw IOException(
                            "Response too large (>${maxBytes / (1024 * 1024)}MB, streamed): ${chain.request().url.encodedPath}"
                        )
                    }
                }
                return read
            }
        }
        val limitedBody = object : ResponseBody() {
            override fun contentType(): okhttp3.MediaType? = body.contentType()
            override fun contentLength(): Long = body.contentLength()
            override fun source(): BufferedSource = counting.buffer()
        }
        return response.newBuilder().body(limitedBody).build()
    }

    companion object {
        /**
         * Hard cap on any single HTTP response body (16 MB).
         *
         * Rationale (0.1.13, gpter review #5): on a 256 MB heap the
         * kotlinx-serialization converter reads the whole body into a byte
         * array, then decodes into a String (~2× memory), so a 32 MB cap
         * can still allocate ~96 MB during conversion. 16 MB gives a ~48 MB
         * worst-case allocation while still accommodating sessions with
         * very large tool outputs. Beyond 16 MB the server should use
         * cursor pagination (already supported), making this a signalling
         * cap rather than an everyday limit.
         */
        const val MAX_RESPONSE_BYTES = 16L * 1024 * 1024
    }
}
