package com.yage.opencode_client.data.audio

internal object AudioRecorderConfig {
    const val outputSampleRate = 44_100
    const val outputChannelCount = 1
    const val outputBitRate = 64_000
    const val targetPcmSampleRate = 24_000
    const val targetPcmChannelCount = 1
    const val targetPcmBytesPerSample = 2
    const val targetPcmEncoding = 2
    const val pcmReadBufferSizeBytes = 4_096
    const val codecTimeoutUs = 10_000L
    const val tempFilePrefix = "opencode-recording-"
    const val tempFileSuffix = ".m4a"
    const val realtimeTempFilePrefix = "opencode-realtime-speech-"
    const val realtimeTempFileSuffix = ".pcm"
}

internal object AudioTranscriptionConfig {
    const val connectTimeoutSeconds = 15L
    const val readTimeoutSeconds = 60L
    const val writeTimeoutSeconds = 60L
    const val sendChunkSizeBytes = 240_000
    const val realtimeReplayChunkSizeBytes = 240_000
    const val realtimeHeartbeatIntervalSeconds = 12L
    const val silenceDurationMs = 1_200
    const val jsonMediaType = "application/json; charset=utf-8"
    const val realtimeCommitMessage = "{\"type\":\"commit\"}"
    const val realtimeStopMessage = "{\"type\":\"stop\"}"
}
