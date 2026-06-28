package com.yage.opencode_client.data.api

import com.yage.opencode_client.data.model.SSEEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.retryWhen
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.random.Random
import java.util.Base64

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

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data.isNotBlank() && data != "[DONE]") {
                    try {
                        val event = json.decodeFromString<SSEEvent>(data)
                        trySend(Result.success(event))
                    } catch (_: Exception) {
                        // Skip malformed events silently
                    }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                close(Exception("SSE connection closed by server"))
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                close(t ?: Exception("SSE connection failed"))
            }
        }

        val eventSource = EventSources.createFactory(okHttpClient)
            .newEventSource(request, listener)

        awaitClose {
            eventSource.cancel()
        }
    }
}
