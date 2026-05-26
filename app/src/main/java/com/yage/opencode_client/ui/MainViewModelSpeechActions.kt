package com.yage.opencode_client.ui

import android.util.Log
import com.yage.opencode_client.data.audio.AIBuildersAudioClient
import com.yage.opencode_client.data.audio.AudioRecorderManager
import com.yage.opencode_client.data.audio.RealtimeSpeechStreamer
import com.yage.opencode_client.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

internal data class SpeechInputConfig(
    val token: String,
    val baseURL: String,
    val prompt: String,
    val terminology: String
)

internal fun currentSpeechInputConfig(settingsManager: SettingsManager): SpeechInputConfig {
    return SpeechInputConfig(
        token = AIBuildersAudioClient.sanitizeBearerToken(settingsManager.aiBuilderToken),
        baseURL = settingsManager.aiBuilderBaseURL.trim(),
        prompt = settingsManager.aiBuilderCustomPrompt.trim(),
        terminology = settingsManager.aiBuilderTerminology.trim()
    )
}

internal fun launchSpeechTranscription(
    scope: CoroutineScope,
    state: MutableStateFlow<AppState>,
    audioRecorderManager: AudioRecorderManager,
    config: SpeechInputConfig,
    recordingFile: File,
    existingInput: String,
    tag: String
) {
    scope.launch {
        try {
            Log.d(tag, "Converting recorded audio to PCM: ${recordingFile.absolutePath}")
            val pcmData = audioRecorderManager.convertToPCM(recordingFile)
            Log.d(tag, "Submitting audio for transcription: bytes=${pcmData.size}")
            val result = AIBuildersAudioClient.transcribe(
                baseURL = config.baseURL,
                token = config.token,
                pcmAudio = pcmData,
                language = null,
                prompt = config.prompt.ifEmpty { null },
                terms = config.terminology.ifEmpty { null },
                onPartialTranscript = { partial ->
                    state.update { it.copy(inputText = mergedSpeechInput(existingInput, partial)) }
                }
            )

            result.onSuccess { response ->
                val cleaned = response.text.trim()
                Log.d(tag, "Transcription success: chars=${cleaned.length}")
                state.update {
                    it.copy(
                        inputText = mergedSpeechInput(existingInput, cleaned),
                        isTranscribing = false
                    )
                }
            }.onFailure { error ->
                Log.e(tag, "Transcription failed", error)
                state.update {
                    it.copy(
                        inputText = speechFailureInput(
                            existingInput = existingInput,
                            currentInput = it.inputText
                        ),
                        isTranscribing = false,
                        speechError = errorMessageOrFallback(error, "Transcription failed")
                    )
                }
            }
        } catch (error: Exception) {
            Log.e(tag, "Speech processing failed", error)
            state.update {
                it.copy(
                    inputText = speechFailureInput(
                        existingInput = existingInput,
                        currentInput = it.inputText
                    ),
                    isTranscribing = false,
                    speechError = errorMessageOrFallback(error, "Transcription failed")
                )
            }
        }
    }
}

internal fun launchRealtimeSpeechStop(
    scope: CoroutineScope,
    state: MutableStateFlow<AppState>,
    streamer: RealtimeSpeechStreamer,
    existingInput: String,
    tag: String,
    onFinished: () -> Unit
) {
    scope.launch {
        try {
            val transcript = streamer.commitAndStop { partial ->
                state.update { it.copy(inputText = mergedSpeechInput(existingInput, partial)) }
            }
            val cleaned = transcript.trim()
            Log.d(tag, "Realtime transcription success: chars=${cleaned.length}")
            state.update {
                it.copy(
                    inputText = mergedSpeechInput(existingInput, cleaned),
                    isTranscribing = false
                )
            }
            streamer.cleanupCache()
        } catch (error: Exception) {
            Log.e(tag, "Realtime speech processing failed", error)
            state.update {
                it.copy(
                    inputText = speechFailureInput(
                        existingInput = existingInput,
                        currentInput = it.inputText
                    ),
                    isTranscribing = false,
                    speechError = errorMessageOrFallback(error, "Transcription failed")
                )
            }
            streamer.cleanupCache()
        } finally {
            onFinished()
        }
    }
}
