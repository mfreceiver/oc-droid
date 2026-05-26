package com.yage.opencode_client.data.audio

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface RealtimeSpeechSession {
    val sessionId: String

    suspend fun sendAudioChunk(chunk: ByteArray)
    suspend fun heartbeat()
    suspend fun commitAndStop(onPartialTranscript: ((String) -> Unit)? = null): String
    fun cancel()
}

internal class RealtimeSpeechStreamer(
    private val cache: RealtimeSpeechAudioCache,
    private val makeSession: suspend () -> RealtimeSpeechSession
) {
    private val mutex = Mutex()
    private var session: RealtimeSpeechSession? = null
    private var isRecovering = false

    suspend fun appendAudioChunk(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        cache.append(chunk)
        val activeSession = mutex.withLock {
            if (isRecovering) null else session
        } ?: return

        try {
            activeSession.sendAudioChunk(chunk)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            recover(error)
        }
    }

    suspend fun connectInitialSession() {
        val shouldConnect = mutex.withLock {
            if (session != null || isRecovering) {
                false
            } else {
                isRecovering = true
                true
            }
        }
        if (!shouldConnect) return

        try {
            val newSession = makeSession()
            replayCache(newSession)
            mutex.withLock { session = newSession }
            Log.d(TAG, "Realtime startup replay done bytes=${cache.byteCount}")
        } catch (error: Exception) {
            Log.e(TAG, "Realtime startup replay failed", error)
        } finally {
            mutex.withLock { isRecovering = false }
        }
    }

    suspend fun heartbeat() {
        val activeSession = mutex.withLock {
            if (isRecovering) null else session
        } ?: return

        try {
            activeSession.heartbeat()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            recover(error)
        }
    }

    suspend fun commitAndStop(onPartialTranscript: ((String) -> Unit)? = null): String {
        waitForRecovery()
        if (mutex.withLock { session == null }) {
            recover(java.io.IOException("Realtime speech session was not ready before stop"))
        }
        waitForRecovery()

        val activeSession = mutex.withLock { session }
            ?: throw java.io.IOException("Realtime speech session is unavailable")
        return try {
            activeSession.commitAndStop(onPartialTranscript)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            recover(error)
            waitForRecovery()
            val recoveredSession = mutex.withLock { session } ?: throw error
            recoveredSession.commitAndStop(onPartialTranscript)
        }
    }

    suspend fun cancel() {
        mutex.withLock {
            session?.cancel()
            session = null
            isRecovering = false
        }
        cache.remove()
    }

    fun cleanupCache() {
        cache.remove()
    }

    private suspend fun recover(reason: Throwable) {
        val oldSession = mutex.withLock {
            if (isRecovering) return
            isRecovering = true
            val current = session
            session = null
            current
        }

        Log.e(TAG, "Realtime recovery begin bytes=${cache.byteCount}", reason)
        oldSession?.cancel()
        try {
            val replacement = makeSession()
            replayCache(replacement)
            mutex.withLock { session = replacement }
            Log.d(TAG, "Realtime recovery done bytes=${cache.byteCount}")
        } catch (error: Exception) {
            Log.e(TAG, "Realtime recovery failed bytes=${cache.byteCount}", error)
        } finally {
            mutex.withLock { isRecovering = false }
        }
    }

    private suspend fun replayCache(targetSession: RealtimeSpeechSession) {
        var offset = 0
        while (true) {
            val chunk = cache.readChunk(offset, AudioTranscriptionConfig.realtimeReplayChunkSizeBytes)
            if (chunk.isEmpty()) {
                if (offset >= cache.byteCount) return
                delay(20)
                continue
            }
            targetSession.sendAudioChunk(chunk)
            offset += chunk.size
        }
    }

    private suspend fun waitForRecovery() {
        while (mutex.withLock { isRecovering }) {
            delay(100)
        }
    }

    private companion object {
        private const val TAG = "RealtimeSpeechStreamer"
    }
}
