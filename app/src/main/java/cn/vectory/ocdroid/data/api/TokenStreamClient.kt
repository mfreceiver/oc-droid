package cn.vectory.ocdroid.data.api

import cn.vectory.ocdroid.data.model.TokenStreamFrame
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.atomic.AtomicBoolean

/**
 * §Stage-C §3.1 / §5.1 — dedicated SSE transport for the oc-slimapi per-
 * session token stream. Connects to
 * `GET {base}/slimapi/sessions/{sessionId}/stream` via OkHttp [EventSources]
 * (mirrors [SSEClient]'s EventSource usage), parses each frame through
 * [TokenStreamFrame.parse], and emits non-null frames as a cold [Flow].
 *
 * # Scope (Stage C — transport only)
 *
 * This class is INTENTIONALLY a bare transport. It does NOT own:
 *  - watchdog / heartbeat timeout (Stage D wraps the Flow with a watchdog),
 *  - reconnect / backoff (Stage D owns lifecycle + the [Resync] reaction),
 *  - epoch capture (Stage D tags frames via [cn.vectory.ocdroid.data.model.EpochFrame]),
 *  - session.deleted / 503 handling (Stage D coordinator).
 *
 * On transport-level failure or server-initiated close, the Flow simply
 * completes ( [callbackFlow] `close()`); the Stage-D coordinator observes the
 * completion and decides whether to reconnect.
 *
 * # URL building
 *
 * The session path is concatenated under `/slimapi/sessions/{sid}/stream`;
 * when [directory] is non-null it is added as a properly percent-encoded
 * `?directory=` query (the sidecar reads the query before any header). The
 * `X-Slimapi-Version: 1` header is auto-injected by
 * [cn.vectory.ocdroid.data.repository.http.SlimapiVersionInterceptor] on the
 * OkHttp chain (the client built by
 * [cn.vectory.ocdroid.data.repository.http.OkHttpClientFactory.tokenStreamClient]
 * carries that interceptor); this class does NOT set it manually.
 *
 * # Constructor
 *
 * Takes the pre-built [okHttpClient] (from `tokenStreamClient(hostPort)`) and
 * the resolved [baseUrl] (host:port, with or without scheme). Stage D
 * constructs one instance per active session from the live host config; no
 * DI binding here (Stage D owns the lifecycle).
 */
class TokenStreamClient(
    private val okHttpClient: OkHttpClient,
    private val baseUrl: String,
) {

    /**
     * Opens the token stream for [sessionId] and emits parsed frames.
     *
     * @param sessionId the target session id (path segment).
     * @param directory optional workdir; when non-null, added as a
     *   percent-encoded `?directory=` query so a reverse proxy / tunnel that
     *   strips custom headers still routes correctly. Null leaves the request
     *   unscoped (server falls back to its process cwd).
     */
    fun connect(sessionId: String, directory: String? = null): Flow<TokenStreamFrame> = callbackFlow {
        val resolved = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
        val trimmed = resolved.trimEnd('/')
        val base = "$trimmed/slimapi/sessions/$sessionId/stream"
        val httpUrl = base.toHttpUrlOrNull()
            ?: error("invalid token stream URL: $base")
        // OkHttp's addQueryParameter percent-encodes the value; safe for any
        // workdir string (spaces, unicode, slashes).
        val finalUrl = if (directory != null) {
            httpUrl.newBuilder().addQueryParameter("directory", directory).build()
        } else {
            httpUrl
        }
        val request = Request.Builder()
            .url(finalUrl)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()

        // §P2-8 race guard (mirrors SSEClient): onClosed / onFailure may both
        // fire after the consumer cancels; CAS ensures only the first close
        // wins so a residual callback never throws IllegalStateException into
        // the producer scope.
        val closed = AtomicBoolean(false)

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: okhttp3.Response) {
                DebugLog.i("TokenStream", "connected sid=$sessionId")
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                // Residual-in-pipeline guard (cf. SSEClient): a frame queued in
                // OkHttp's pipeline may arrive after the consumer cancelled.
                if (closed.get()) return
                // parse() returns null for unknown events + malformed frames;
                // those are silently skipped (forward compatibility). A null
                // result MUST NOT complete the flow — the stream stays open.
                val frame = TokenStreamFrame.parse(type, data) ?: return
                // Re-check after the (cheap but real) parse work.
                if (closed.get()) return
                trySend(frame)
            }

            override fun onClosed(eventSource: EventSource) {
                DebugLog.w("TokenStream", "closed by server sid=$sessionId")
                if (closed.compareAndSet(false, true)) {
                    close()
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: okhttp3.Response?,
            ) {
                DebugLog.w(
                    "TokenStream",
                    "failure sid=$sessionId ${t?.message ?: "code=${response?.code}"}",
                )
                if (closed.compareAndSet(false, true)) {
                    close(t ?: Exception("token stream failed (code=${response?.code})"))
                }
            }
        }

        DebugLog.i(
            "TokenStream",
            "connecting sid=$sessionId directory=${directory ?: "-"}",
        )
        val eventSource = EventSources.createFactory(okHttpClient)
            .newEventSource(request, listener)

        awaitClose {
            // §S1 (Stage-C should-fix): set closed BEFORE eventSource.cancel()
            // so any residual OkHttp callbacks (onFailure triggered by cancel,
            // in-flight onEvent frames queued in the pipeline) observe closed=true
            // and short-circuit (mirrors SSEClient's P2-8 race-guard ordering).
            // Without this, a post-cancel onEvent could trySend into the now-
            // closing channel and throw IllegalStateException into the producer
            // scope (cf. TokenStreamClient.onEvent's `if (closed.get()) return`
            // guard — that guard only fires if closed was already set here).
            closed.set(true)
            eventSource.cancel()
        }
    }
}
