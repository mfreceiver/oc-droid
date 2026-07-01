package cn.vectory.ocdroid.data.repository.http

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
         * Hard cap on any single HTTP response body (32 MB).
         *
         * Rationale: originally 16 MB to bound the kotlinx-serialization
         * converter's whole-body String allocation (~2× bytes) against OOM on
         * a 256 MB heap. The dominant cause of giant responses — opencode's
         * LSP `state.metadata.diagnostics` (multi-MB per edit) — is now
         * mitigated at the source (server `lsp: false`), purged from the DB,
         * and additionally stripped client-side during deserialization
         * (see PartStateSerializer), so real responses are again small.
         *
         * Raised to 32 MB as a signalling safety net: with diagnostics gone,
         * tripping it indicates an unexpected pathological payload (or a
         * session whose diagnostics pre-date the cleanup) rather than the
         * everyday case. Cursor pagination (`limit`) remains the primary
         * control; this cap just prevents a single runaway body from OOMing.
         */
        const val MAX_RESPONSE_BYTES = 32L * 1024 * 1024
    }
}
