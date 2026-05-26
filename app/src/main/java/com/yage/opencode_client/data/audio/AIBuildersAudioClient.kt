package com.yage.opencode_client.data.audio

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class TranscriptionResponse(
    val requestId: String,
    val text: String
)

private data class RealtimeSessionResponse(
    val sessionId: String,
    val wsUrl: String
)

object AIBuildersAudioClient {
    private const val TAG = "AIBuildersAudio"

    suspend fun testConnection(baseURL: String, token: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val client = buildHttpClient()
            val url = buildAPIURL(normalizedBaseURL(baseURL), "/v1/embeddings")
            val cleanedToken = sanitizeBearerToken(token)
            if (cleanedToken.isEmpty()) {
                throw IOException("AI Builder token is empty")
            }
            val body = JSONObject().put("input", "ok").toString()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $cleanedToken")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(AudioTranscriptionConfig.jsonMediaType.toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code >= 400) {
                    throw IOException("Connection test failed with status ${response.code}")
                }
                Log.d(TAG, "Connection test passed: ${response.code}")
            }
            Unit
        }
    }

    suspend fun transcribe(
        baseURL: String,
        token: String,
        pcmAudio: ByteArray,
        language: String? = null,
        prompt: String? = null,
        terms: String? = null,
        onPartialTranscript: ((String) -> Unit)? = null
    ): Result<TranscriptionResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val client = buildHttpClient()
            val normalizedBase = normalizedBaseURL(baseURL)
            val cleanedToken = sanitizeBearerToken(token)
            if (cleanedToken.isEmpty()) {
                throw IOException("AI Builder token is empty")
            }

            val session = createRealtimeSession(
                client = client,
                baseURL = normalizedBase,
                token = cleanedToken,
                language = language,
                prompt = prompt,
                terms = terms
            )
            Log.d(TAG, "Realtime session created: ${session.sessionId}")

            val websocketURL = realtimeWebSocketURL(normalizedBase, session.wsUrl)
            Log.d(TAG, "Realtime websocket URL: ${redactedRealtimeWebSocketLogURL(websocketURL)}")

            streamPCMOverWebSocket(
                client = client,
                websocketURL = websocketURL,
                sessionId = session.sessionId,
                pcmAudio = pcmAudio,
                onPartialTranscript = onPartialTranscript
            )
        }
    }

    fun normalizedBaseURL(rawBaseURL: String): String {
        val trimmed = rawBaseURL.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        return "https://$trimmed"
    }

    fun sanitizeBearerToken(rawToken: String): String {
        return rawToken
            .trim()
            .filterNot { ch ->
                ch.isWhitespace() ||
                    Character.getType(ch) == Character.FORMAT.toInt() ||
                    ch == '\uFEFF'
            }
    }

    fun buildAPIURL(base: String, path: String): String {
        val relativePath = path.removePrefix("/")
        val baseUri = URI(base)
        var basePath = baseUri.path ?: ""
        if (basePath.isNotEmpty() && !basePath.endsWith("/")) {
            basePath += "/"
        }

        val baseForAppend = URI(
            baseUri.scheme,
            baseUri.authority,
            basePath,
            null,
            null
        )
        return baseForAppend.resolve(relativePath).toString()
    }

    fun realtimeWebSocketURL(baseURL: String, relativePath: String): String {
        val httpURL = URI(buildAPIURL(baseURL, relativePath))
        val webSocketScheme = when (httpURL.scheme) {
            "https" -> "wss"
            "http" -> "ws"
            else -> httpURL.scheme
        }

        return URI(
            webSocketScheme,
            httpURL.userInfo,
            httpURL.host,
            httpURL.port,
            httpURL.path,
            httpURL.query,
            httpURL.fragment
        ).toString()
    }

    private fun buildHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(AudioTranscriptionConfig.connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(AudioTranscriptionConfig.readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(AudioTranscriptionConfig.writeTimeoutSeconds, TimeUnit.SECONDS)
            .pingInterval(AudioTranscriptionConfig.realtimeHeartbeatIntervalSeconds, TimeUnit.SECONDS)
            .build()
    }

    internal suspend fun startRealtimeSession(
        baseURL: String,
        token: String,
        language: String? = null,
        prompt: String? = null,
        terms: String? = null
    ): RealtimeSpeechSession = withContext(Dispatchers.IO) {
        val client = buildHttpClient()
        val normalizedBase = normalizedBaseURL(baseURL)
        val cleanedToken = sanitizeBearerToken(token)
        if (cleanedToken.isEmpty()) {
            throw IOException("AI Builder token is empty")
        }

        val session = createRealtimeSession(
            client = client,
            baseURL = normalizedBase,
            token = cleanedToken,
            language = language,
            prompt = prompt,
            terms = terms
        )
        val websocketURL = realtimeWebSocketURL(normalizedBase, session.wsUrl)
        Log.d(TAG, "Realtime live websocket connecting: ${redactedRealtimeWebSocketLogURL(websocketURL)}")
        openRealtimeWebSocketSession(
            client = client,
            websocketURL = websocketURL,
            sessionId = session.sessionId
        )
    }

    fun redactedRealtimeWebSocketLogURL(url: String): String {
        return runCatching {
            val uri = URI(url)
            URI(
                uri.scheme,
                uri.userInfo,
                uri.host,
                uri.port,
                uri.path,
                if (uri.query == null) null else "ticket=redacted",
                uri.fragment
            ).toString()
        }.getOrElse { "<invalid-websocket-url>" }
    }

    private fun createRealtimeSession(
        client: OkHttpClient,
        baseURL: String,
        token: String,
        language: String?,
        prompt: String?,
        terms: String?
    ): RealtimeSessionResponse {
        val url = buildAPIURL(baseURL, "/v1/audio/realtime/sessions")
        val payload = JSONObject()
            .put("vad", false)
            .put("silence_duration_ms", AudioTranscriptionConfig.silenceDurationMs)

        val normalizedLanguage = language?.trim().orEmpty()
        if (normalizedLanguage.isNotEmpty()) {
            payload.put("language", normalizedLanguage)
        }

        val normalizedPrompt = prompt?.trim().orEmpty()
        if (normalizedPrompt.isNotEmpty()) {
            payload.put("prompt", normalizedPrompt)
        }

        val termsArray = terms
            ?.split(",")
            ?.map { term -> term.trim() }
            ?.filter { term -> term.isNotEmpty() }
            .orEmpty()
        if (termsArray.isNotEmpty()) {
            val jsonTerms = JSONArray()
            termsArray.forEach { term -> jsonTerms.put(term) }
            payload.put("terms", jsonTerms)
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(AudioTranscriptionConfig.jsonMediaType.toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code >= 400) {
                throw IOException("Create session failed with status ${response.code}")
            }

            val bodyText = response.body?.string()
                ?: throw IOException("Create session returned empty body")
            val bodyJson = JSONObject(bodyText)
            val sessionId = bodyJson.getString("session_id")
            val wsUrl = bodyJson.getString("ws_url")

            return RealtimeSessionResponse(
                sessionId = sessionId,
                wsUrl = wsUrl
            )
        }
    }

    private suspend fun streamPCMOverWebSocket(
        client: OkHttpClient,
        websocketURL: String,
        sessionId: String,
        pcmAudio: ByteArray,
        onPartialTranscript: ((String) -> Unit)?
    ): TranscriptionResponse {
        val readySignal = CompletableDeferred<Unit>()
        val resultSignal = CompletableDeferred<TranscriptionResponse>()
        val closed = AtomicBoolean(false)
        val partialBuffer = StringBuilder()
        var finalText = ""

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val event = JSONObject(text)
                    when (event.optString("type")) {
                        "session_ready" -> {
                            Log.d(TAG, "WebSocket session ready")
                            if (!readySignal.isCompleted) {
                                readySignal.complete(Unit)
                            }
                        }

                        "transcript_delta" -> {
                            val delta = event.optString("text")
                            if (delta.isNotEmpty()) {
                                partialBuffer.append(delta)
                                val partialText = partialBuffer.toString()
                                Log.d(TAG, "Partial transcript received: ${partialText.length} chars")
                                onPartialTranscript?.invoke(partialText)
                            }
                        }

                        "transcript_completed" -> {
                            finalText = event.optString("text").trim()
                            if (finalText.isEmpty()) {
                                finalText = partialBuffer.toString().trim()
                            }
                            Log.d(TAG, "Transcript completed: ${finalText.length} chars")
                            webSocket.send("{\"type\":\"stop\"}")
                        }

                        "session_stopped" -> {
                            if (closed.compareAndSet(false, true)) {
                                Log.d(TAG, "WebSocket session stopped")
                                val textResult = if (finalText.isNotEmpty()) {
                                    finalText
                                } else {
                                    partialBuffer.toString().trim()
                                }
                                resultSignal.complete(
                                    TranscriptionResponse(
                                        requestId = sessionId,
                                        text = textResult
                                    )
                                )
                            }
                        }

                        "error" -> {
                            val message = event.optString("message").ifEmpty {
                                event.optString("code", "Unknown websocket error")
                            }
                            if (closed.compareAndSet(false, true)) {
                                resultSignal.completeExceptionally(IOException(message))
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (closed.compareAndSet(false, true)) {
                        resultSignal.completeExceptionally(error)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!readySignal.isCompleted) {
                    readySignal.completeExceptionally(t)
                }
                if (closed.compareAndSet(false, true)) {
                    resultSignal.completeExceptionally(t)
                }
            }
        }

        val request = Request.Builder().url(websocketURL).build()
        val webSocket = client.newWebSocket(request, listener)

        try {
            readySignal.await()
            val chunkCount = if (pcmAudio.isEmpty()) 0 else {
                (pcmAudio.size + AudioTranscriptionConfig.sendChunkSizeBytes - 1) /
                    AudioTranscriptionConfig.sendChunkSizeBytes
            }
            Log.d(
                TAG,
                "Sending PCM audio: bytes=${pcmAudio.size}, chunks=$chunkCount, targetRate=${AudioRecorderConfig.targetPcmSampleRate}"
            )
            var chunkStart = 0
            while (chunkStart < pcmAudio.size) {
                val chunkEnd = minOf(chunkStart + AudioTranscriptionConfig.sendChunkSizeBytes, pcmAudio.size)
                val sent = webSocket.send(
                    pcmAudio
                        .copyOfRange(chunkStart, chunkEnd)
                        .toByteString()
                )
                if (!sent) {
                    throw IOException("Failed to send audio chunk")
                }
                chunkStart = chunkEnd
            }

            if (!webSocket.send("{\"type\":\"commit\"}")) {
                throw IOException("Failed to send commit event")
            }
            Log.d(TAG, "Commit event sent")

            val result = resultSignal.await()
            webSocket.close(1000, "done")
            return result
        } catch (error: Exception) {
            webSocket.cancel()
            throw error
        }
    }

    private suspend fun openRealtimeWebSocketSession(
        client: OkHttpClient,
        websocketURL: String,
        sessionId: String
    ): RealtimeSpeechSession {
        val readySignal = CompletableDeferred<Unit>()
        val events = Channel<RealtimeSocketEvent>(Channel.UNLIMITED)
        val closed = AtomicBoolean(false)

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val event = RealtimeSocketEvent(JSONObject(text))
                    if (event.type == "session_ready") {
                        if (!readySignal.isCompleted) readySignal.complete(Unit)
                    } else {
                        events.trySend(event)
                    }
                } catch (error: Exception) {
                    if (!readySignal.isCompleted) readySignal.completeExceptionally(error)
                    events.close(error)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!readySignal.isCompleted) readySignal.completeExceptionally(t)
                events.close(t)
                closed.set(true)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.set(true)
                events.close()
            }
        }

        val request = Request.Builder().url(websocketURL).build()
        val webSocket = client.newWebSocket(request, listener)
        try {
            readySignal.await()
            Log.d(TAG, "Realtime live session ready: $sessionId")
            return AIBuildersRealtimeSession(
                sessionId = sessionId,
                webSocket = webSocket,
                events = events,
                closed = closed
            )
        } catch (error: Exception) {
            webSocket.cancel()
            throw error
        }
    }
}

private data class RealtimeSocketEvent(
    val type: String,
    val text: String?,
    val code: String?,
    val message: String?
) {
    constructor(json: JSONObject) : this(
        type = json.optString("type"),
        text = json.optString("text").ifEmpty { null },
        code = json.optString("code").ifEmpty { null },
        message = json.optString("message").ifEmpty { null }
    )
}

private class AIBuildersRealtimeSession(
    override val sessionId: String,
    private val webSocket: WebSocket,
    private val events: Channel<RealtimeSocketEvent>,
    private val closed: AtomicBoolean
) : RealtimeSpeechSession {
    private val sendMutex = Mutex()
    private val committed = AtomicBoolean(false)

    override suspend fun sendAudioChunk(chunk: ByteArray) {
        if (chunk.isEmpty() || committed.get() || closed.get()) return
        sendMutex.withLock {
            if (!webSocket.send(chunk.toByteString())) {
                throw IOException("Failed to send audio chunk")
            }
        }
    }

    override suspend fun heartbeat() {
        if (closed.get()) {
            throw IOException("Realtime speech connection is closed")
        }
    }

    override suspend fun commitAndStop(onPartialTranscript: ((String) -> Unit)?): String {
        committed.set(true)
        sendMutex.withLock {
            if (!webSocket.send(AudioTranscriptionConfig.realtimeCommitMessage)) {
                throw IOException("Failed to send commit event")
            }
        }

        var finalTranscript = ""
        val partialAccumulator = StringBuilder()
        for (event in events) {
            when (event.type) {
                "transcript_delta" -> {
                    val delta = event.text.orEmpty()
                    if (delta.isNotEmpty()) {
                        partialAccumulator.append(delta)
                        onPartialTranscript?.invoke(partialAccumulator.toString())
                    }
                }

                "transcript_completed" -> {
                    finalTranscript = event.text.orEmpty().trim()
                    sendMutex.withLock {
                        if (!webSocket.send(AudioTranscriptionConfig.realtimeStopMessage)) {
                            throw IOException("Failed to send stop event")
                        }
                    }
                }

                "session_stopped" -> {
                    closed.set(true)
                    webSocket.close(1000, "done")
                    return finalTranscript.ifEmpty { partialAccumulator.toString().trim() }
                }

                "error" -> {
                    throw IOException(event.message ?: event.code ?: "Unknown websocket error")
                }
            }
        }
        throw IOException("Realtime speech session closed before completion")
    }

    override fun cancel() {
        closed.set(true)
        events.close()
        webSocket.cancel()
    }
}
