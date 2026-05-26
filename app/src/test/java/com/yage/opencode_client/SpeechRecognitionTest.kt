package com.yage.opencode_client

import com.yage.opencode_client.data.audio.AIBuildersAudioClient
import com.yage.opencode_client.data.audio.AudioRecorderConfig
import com.yage.opencode_client.data.audio.AudioResampler
import com.yage.opencode_client.data.audio.AudioTranscriptionConfig
import com.yage.opencode_client.data.audio.RealtimeSpeechAudioCache
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.mergedSpeechInput
import com.yage.opencode_client.ui.speechFailureInput
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpeechRecognitionTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // ─── AIBuildersAudioClient.normalizedBaseURL ─────────────────────

    @Test
    fun `normalizedBaseURL adds https when no scheme`() {
        assertEquals("https://api.example.com", AIBuildersAudioClient.normalizedBaseURL("api.example.com"))
    }

    @Test
    fun `normalizedBaseURL preserves https`() {
        assertEquals("https://api.example.com", AIBuildersAudioClient.normalizedBaseURL("https://api.example.com"))
    }

    @Test
    fun `normalizedBaseURL preserves http`() {
        assertEquals("http://localhost:8080", AIBuildersAudioClient.normalizedBaseURL("http://localhost:8080"))
    }

    @Test
    fun `normalizedBaseURL trims whitespace`() {
        assertEquals("https://api.example.com", AIBuildersAudioClient.normalizedBaseURL("  api.example.com  "))
    }

    @Test
    fun `normalizedBaseURL handles https with trailing path`() {
        assertEquals("https://api.example.com/v1", AIBuildersAudioClient.normalizedBaseURL("https://api.example.com/v1"))
    }

    @Test
    fun `sanitizeBearerToken strips surrounding and hidden whitespace`() {
        val raw = "  abc\u200B123\uFEFF  "
        assertEquals("abc123", AIBuildersAudioClient.sanitizeBearerToken(raw))
    }

    @Test
    fun `sanitizeBearerToken keeps normal token characters`() {
        val raw = "sk-proj_ABC-123.xyz"
        assertEquals(raw, AIBuildersAudioClient.sanitizeBearerToken(raw))
    }

    @Test
    fun `audio transcription constants stay aligned with recorder pipeline`() {
        assertEquals(24_000, AudioRecorderConfig.targetPcmSampleRate)
        assertEquals(1, AudioRecorderConfig.targetPcmChannelCount)
        assertEquals(2, AudioRecorderConfig.targetPcmBytesPerSample)
        assertEquals(2, AudioRecorderConfig.targetPcmEncoding)
        assertEquals(44_100, AudioRecorderConfig.outputSampleRate)
        assertEquals(1, AudioRecorderConfig.outputChannelCount)
        assertEquals(64_000, AudioRecorderConfig.outputBitRate)
        assertEquals(240_000, AudioTranscriptionConfig.sendChunkSizeBytes)
        assertEquals(240_000, AudioTranscriptionConfig.realtimeReplayChunkSizeBytes)
        assertEquals(12L, AudioTranscriptionConfig.realtimeHeartbeatIntervalSeconds)
        assertEquals(1_200, AudioTranscriptionConfig.silenceDurationMs)
        assertEquals("{\"type\":\"commit\"}", AudioTranscriptionConfig.realtimeCommitMessage)
        assertEquals("{\"type\":\"stop\"}", AudioTranscriptionConfig.realtimeStopMessage)
    }

    @Test
    fun `realtime audio cache appends and reads by offset`() {
        val cache = RealtimeSpeechAudioCache(temporaryFolder.newFolder())

        cache.append(byteArrayOf(1, 2, 3))
        cache.append(byteArrayOf(4, 5))

        assertEquals(5, cache.byteCount)
        assertArrayEquals(byteArrayOf(1, 2), cache.readChunk(offset = 0, maxBytes = 2))
        assertArrayEquals(byteArrayOf(3, 4, 5), cache.readChunk(offset = 2, maxBytes = 10))
        assertArrayEquals(ByteArray(0), cache.readChunk(offset = 5, maxBytes = 10))
    }

    @Test
    fun `realtime audio cache ignores empty appends and removes file`() {
        val cache = RealtimeSpeechAudioCache(temporaryFolder.newFolder())
        val file = cache.file

        cache.append(ByteArray(0))
        assertEquals(0, cache.byteCount)
        assertTrue(file.exists())

        cache.append(byteArrayOf(9, 8, 7))
        cache.remove()

        assertEquals(0, cache.byteCount)
        assertFalse(file.exists())
    }

    @Test
    fun `redactedRealtimeWebSocketLogURL removes ticket query`() {
        val result = AIBuildersAudioClient.redactedRealtimeWebSocketLogURL(
            "wss://api.example.com/v1/audio/realtime/ws/session?ticket=secret&other=value"
        )
        assertEquals("wss://api.example.com/v1/audio/realtime/ws/session?ticket=redacted", result)
    }

    // ─── AIBuildersAudioClient.buildAPIURL ────────────────────────────

    @Test
    fun `buildAPIURL appends path to base`() {
        val result = AIBuildersAudioClient.buildAPIURL("https://api.example.com", "/v1/embeddings")
        assertEquals("https://api.example.com/v1/embeddings", result)
    }

    @Test
    fun `buildAPIURL handles base with trailing path`() {
        val result = AIBuildersAudioClient.buildAPIURL("https://api.example.com/prefix", "/v1/embeddings")
        assertEquals("https://api.example.com/prefix/v1/embeddings", result)
    }

    @Test
    fun `buildAPIURL handles path without leading slash`() {
        val result = AIBuildersAudioClient.buildAPIURL("https://api.example.com", "v1/embeddings")
        assertEquals("https://api.example.com/v1/embeddings", result)
    }

    @Test
    fun `buildAPIURL handles base with trailing slash`() {
        val result = AIBuildersAudioClient.buildAPIURL("https://api.example.com/", "/v1/embeddings")
        assertEquals("https://api.example.com/v1/embeddings", result)
    }

    @Test
    fun `buildAPIURL with port`() {
        val result = AIBuildersAudioClient.buildAPIURL("http://localhost:8080", "/v1/audio/realtime/sessions")
        assertEquals("http://localhost:8080/v1/audio/realtime/sessions", result)
    }

    // ─── AIBuildersAudioClient.realtimeWebSocketURL ──────────────────

    @Test
    fun `realtimeWebSocketURL converts https to wss`() {
        val result = AIBuildersAudioClient.realtimeWebSocketURL(
            "https://api.example.com",
            "/v1/audio/realtime/ws/session123"
        )
        assertEquals("wss://api.example.com/v1/audio/realtime/ws/session123", result)
    }

    @Test
    fun `realtimeWebSocketURL converts http to ws`() {
        val result = AIBuildersAudioClient.realtimeWebSocketURL(
            "http://localhost:8080",
            "/v1/audio/realtime/ws/session123"
        )
        assertEquals("ws://localhost:8080/v1/audio/realtime/ws/session123", result)
    }

    @Test
    fun `realtimeWebSocketURL preserves port`() {
        val result = AIBuildersAudioClient.realtimeWebSocketURL(
            "https://api.example.com:9443",
            "/v1/audio/realtime/ws/abc"
        )
        assertEquals("wss://api.example.com:9443/v1/audio/realtime/ws/abc", result)
    }

    @Test
    fun `realtimeWebSocketURL with base path prefix`() {
        val result = AIBuildersAudioClient.realtimeWebSocketURL(
            "https://api.example.com/prefix",
            "/v1/audio/realtime/ws/session123"
        )
        assertEquals("wss://api.example.com/prefix/v1/audio/realtime/ws/session123", result)
    }

    // ─── mergedSpeechInput ───────────────────────────────────────────

    @Test
    fun `mergedSpeechInput both non-empty`() {
        assertEquals("Hello world", mergedSpeechInput("Hello", "world"))
    }

    @Test
    fun `mergedSpeechInput empty prefix`() {
        assertEquals("world", mergedSpeechInput("", "world"))
    }

    @Test
    fun `mergedSpeechInput empty transcript`() {
        assertEquals("Hello", mergedSpeechInput("Hello", ""))
    }

    @Test
    fun `mergedSpeechInput both empty`() {
        assertEquals("", mergedSpeechInput("", ""))
    }

    @Test
    fun `mergedSpeechInput trims transcript whitespace`() {
        assertEquals("Hello world", mergedSpeechInput("Hello", "  world  "))
    }

    @Test
    fun `mergedSpeechInput whitespace-only transcript returns prefix`() {
        assertEquals("Hello", mergedSpeechInput("Hello", "   "))
    }

    @Test
    fun `speechFailureInput preserves visible partial transcript`() {
        assertEquals("Hello partial result", speechFailureInput("Hello", "Hello partial result"))
        assertEquals("partial result", speechFailureInput("", "partial result"))
    }

    @Test
    fun `speechFailureInput falls back to existing input without partial transcript`() {
        assertEquals("Hello", speechFailureInput("Hello", "   "))
    }

    // ─── AppState speech defaults ────────────────────────────────────

    @Test
    fun `AppState speech fields have correct defaults`() {
        val state = AppState()
        assertFalse(state.isRecording)
        assertFalse(state.isTranscribing)
        assertNull(state.speechError)
        assertFalse(state.aiBuilderConnectionOK)
        assertNull(state.aiBuilderConnectionError)
        assertFalse(state.isTestingAIBuilderConnection)
    }

    // ─── AudioResampler ────────────────────────────────────────────

    @Test
    fun `resample empty input returns empty`() {
        val result = AudioResampler.resample(ShortArray(0), 44100, 24000)
        assertEquals(0, result.size)
    }

    @Test
    fun `resample same rate returns copy`() {
        val input = shortArrayOf(100, 200, 300)
        val result = AudioResampler.resample(input, 24000, 24000)
        assertArrayEquals(input, result)
        assertNotSame(input, result) // must be a copy, not the same array
    }

    @Test
    fun `resample 44100 to 24000 produces correct output size`() {
        // 44100 samples at 44.1kHz = 1 second of audio
        // At 24kHz that should be ~24000 samples
        val input = ShortArray(44100) { (it % 1000).toShort() }
        val result = AudioResampler.resample(input, 44100, 24000)
        // Expected: (44100 * 24000/44100) = 24000
        assertEquals(24000, result.size)
    }

    @Test
    fun `resample preserves value range`() {
        val input = shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE)
        val result = AudioResampler.resample(input, 44100, 24000)
        for (sample in result) {
            assertTrue("Sample $sample out of range", sample >= Short.MIN_VALUE && sample <= Short.MAX_VALUE)
        }
    }

    @Test
    fun `resample single sample`() {
        val input = shortArrayOf(12345)
        val result = AudioResampler.resample(input, 44100, 24000)
        assertTrue("Result should have at least 1 sample", result.isNotEmpty())
        // Single sample resampled — output should be the same value
        assertEquals(12345, result[0].toInt())
    }

    @Test
    fun `resample upsamples correctly`() {
        // 24000 samples → 44100 samples (upsampling)
        val input = ShortArray(24000) { (it % 500).toShort() }
        val result = AudioResampler.resample(input, 24000, 44100)
        assertEquals(44100, result.size)
    }

    @Test
    fun `AppState speech fields can be set`() {
        val state = AppState(
            isRecording = true,
            isTranscribing = true,
            speechError = "mic failed",
            aiBuilderConnectionOK = true,
            aiBuilderConnectionError = "timeout",
            isTestingAIBuilderConnection = true
        )
        assertTrue(state.isRecording)
        assertTrue(state.isTranscribing)
        assertEquals("mic failed", state.speechError)
        assertTrue(state.aiBuilderConnectionOK)
        assertEquals("timeout", state.aiBuilderConnectionError)
        assertTrue(state.isTestingAIBuilderConnection)
    }
}
