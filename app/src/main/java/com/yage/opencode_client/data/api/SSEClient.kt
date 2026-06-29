package com.yage.opencode_client.data.api

import com.yage.opencode_client.data.model.SSEEvent
import com.yage.opencode_client.ui.NOISY_SSE_LOG_EVENTS
import com.yage.opencode_client.util.DebugLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.random.Random
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Raised after the SSE reconnection budget ([SSEClient.MAX_RETRY_ATTEMPTS]) is
 * exhausted so the UI can surface a "messages may be stale" banner (v2 §15.3).
 * Propagated through [connect]'s `retryWhen` to the downstream `catch` block,
 * where the collector copies the message into `AppState.error`.
 */
class SSEConnectionExhausted : Exception(
    "SSE 长时间无法连接，消息可能不是最新"
)

class SSEClient(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val MAX_RETRY_DELAY_MS = 30000L
        private const val RETRY_MULTIPLIER = 2.0
        // §15.3: stop reconnecting after this many attempts and surface the
        // failure via [SSEConnectionExhausted] so the UI can warn the user.
        private const val MAX_RETRY_ATTEMPTS = 10L

        // §Phase1A heartbeat watchdog: server (1.17.11) emits `server.heartbeat`
        // as a DATA event every ~10s. OkHttp's onEvent fires for it, so a
        // watchdog can reset a timer on every event (incl. heartbeat) and
        // detect half-open connections that onFailure won't surface (mobile
        // NAT timeouts, silent socket death). 30s = 3× heartbeat: tolerates
        // missing 2 frames before declaring the link dead. cancel() triggers
        // onFailure -> close -> the existing retryWhen backoff reconnects.
        private const val HEARTBEAT_TIMEOUT_MS = 30_000L
        private const val HEARTBEAT_CHECK_INTERVAL_MS = 5_000L

        // Reuse a single Json instance instead of allocating one per event.
        private val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }

    fun connect(
        baseUrl: String,
        username: String? = null,
        password: String? = null
    ): Flow<Result<SSEEvent>> = connectOnce(baseUrl, username, password)
        .retryWhen { _, attempt ->
            if (attempt >= MAX_RETRY_ATTEMPTS) {
                // §15.3: budget exhausted — give up and let the custom
                // exception propagate to the collector's catch handler so
                // AppState.error reflects the "stale" banner.
                DebugLog.e("SSE", "connection exhausted — giving up")
                throw SSEConnectionExhausted()
            }
            val baseDelay = (INITIAL_RETRY_DELAY_MS * Math.pow(RETRY_MULTIPLIER, attempt.toDouble()))
                .toLong()
                .coerceAtMost(MAX_RETRY_DELAY_MS)
            // §15.3: ±30% jitter to avoid thundering-herd reconnects. The
            // spec writes `(1 + nextDouble()*0.3)`; we widen to ±30% since
            // the section header explicitly says "jitter ±30%".
            val jitterFactor = 1.0 + (Random.nextDouble() * 0.6 - 0.3)
            val delayMs = (baseDelay * jitterFactor).toLong().coerceAtLeast(1L)
            DebugLog.i("SSE", "reconnect attempt #${attempt + 1} in ${delayMs}ms")
            delay(delayMs)
            true
        }

    private fun connectOnce(
        baseUrl: String,
        username: String? = null,
        password: String? = null
    ): Flow<Result<SSEEvent>> = callbackFlow {
        val url = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
        val request = Request.Builder()
            .url("$url/global/event")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .apply {
                if (username != null && password != null) {
                    val credential = "$username:$password"
                    val encoded = Base64.getEncoder().encodeToString(credential.toByteArray())
                    header("Authorization", "Basic $encoded")
                }
            }
            .build()

        // §Phase1A: heartbeat bookkeeping shared between the onEvent callback
        // (OkHttp EventSource thread) and the watchdog coroutine (flow scope).
        // Atomic so visibility/atomicity is guaranteed without @Volatile (which
        // cannot be applied to local captures). lastEventAt seeds to now so the
        // very first check window isn't already "expired".
        val lastEventAt = AtomicLong(System.currentTimeMillis())
        val eventCount = AtomicInteger(0)

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: okhttp3.Response) {
                DebugLog.i("SSE", "connected")
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                // Any frame (including `server.heartbeat`) proves the link is
                // alive — reset the watchdog clock and count it.
                lastEventAt.set(System.currentTimeMillis())
                eventCount.incrementAndGet()
                if (data.isNotBlank() && data != "[DONE]") {
                    try {
                        val event = json.decodeFromString<SSEEvent>(data)
                        // Throttle per-token / periodic noise: message.part.delta
                        // fires dozens-100s/sec during AI streaming and
                        // server.heartbeat fires ~every 10s. Logging every one
                        // floods the 1000-entry ring buffer and evicts the
                        // valuable signal lines. Skip both — the watchdog
                        // already tracks every frame via lastEventAt above, so
                        // heartbeats still serve their liveness purpose.
                        val type = event.payload.type
                        // Throttle noise that floods the 1000-entry ring buffer
                        // and evicts signal: per-token deltas, periodic heartbeats,
                        // reconnect bursts (server.connected), and server-internal
                        // plugin/catalog/integration bursts (the latter fire in
                        // large flurries when a run starts).
                        val noisy = type in NOISY_SSE_LOG_EVENTS
                        if (!noisy) {
                            DebugLog.d("SSE", "event type=$type session=${event.payload.getString("sessionID") ?: "-"}")
                        }
                        trySend(Result.success(event))
                    } catch (_: Exception) {
                        // Skip malformed events silently
                    }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                DebugLog.w("SSE", "closed/error: connection closed by server")
                close(Exception("SSE connection closed by server"))
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                DebugLog.w("SSE", "closed/error: ${t?.message ?: "code=${response?.code}"}")
                close(t ?: Exception("SSE connection failed"))
            }
        }

        // Sanitize the URL for logging only: strip userinfo (user:pass@),
        // query, and fragment so credentials never leak into the in-app log
        // or Logcat. The actual connection still uses the real `$url` (the
        // Request above was already built with it).
        val sanitizedLogUrl = runCatching {
            val uri = android.net.Uri.parse(url)
            "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}/global/event"
        }.getOrNull() ?: "<redacted>/global/event"
        DebugLog.i("SSE", "connecting to $sanitizedLogUrl")
        val eventSource = EventSources.createFactory(okHttpClient)
            .newEventSource(request, listener)

        // §Phase1A: half-open watchdog. Periodically checks elapsed time since
        // the last received frame; if it exceeds HEARTBEAT_TIMEOUT_MS, cancel
        // the eventSource (OkHttp triggers onFailure -> close -> retryWhen).
        // Cold-start guard: while eventCount == 0 we are still waiting for the
        // first frame (server.connected / heartbeat) — don't time out, the
        // connect itself may legitimately take a few seconds. Body is wrapped
        // so any exception is isolated and can never cancel the parent flow.
        val watchdog = launch {
            try {
                while (isActive) {
                    delay(HEARTBEAT_CHECK_INTERVAL_MS)
                    if (eventCount.get() == 0) continue
                    val elapsed = System.currentTimeMillis() - lastEventAt.get()
                    if (elapsed >= HEARTBEAT_TIMEOUT_MS) {
                        DebugLog.w("SSE", "heartbeat watchdog timeout — forcing reconnect")
                        eventSource.cancel()
                        break
                    }
                }
            } catch (_: Throwable) {
                // Isolated: a watchdog failure must not tear down the SSE flow.
            }
        }

        awaitClose {
            watchdog.cancel()
            eventSource.cancel()
        }
    }
}
