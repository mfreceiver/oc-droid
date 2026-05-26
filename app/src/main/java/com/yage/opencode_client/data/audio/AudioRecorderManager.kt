package com.yage.opencode_client.data.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Singleton
class AudioRecorderManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var recorder: MediaRecorder? = null
    private var audioRecord: AudioRecord? = null
    private var realtimeCaptureJob: Job? = null
    private var currentFile: File? = null
    private val realtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isRecording: Boolean
        get() = recorder != null || audioRecord != null

    fun createRealtimeAudioCache(): RealtimeSpeechAudioCache {
        return RealtimeSpeechAudioCache(context.cacheDir)
    }

    fun start() {
        if (isRecording) {
            throw IllegalStateException("Recorder is already running")
        }

        val outputFile = File.createTempFile(
            AudioRecorderConfig.tempFilePrefix,
            AudioRecorderConfig.tempFileSuffix,
            context.cacheDir
        )
        val mediaRecorder = MediaRecorder()

        try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioSamplingRate(AudioRecorderConfig.outputSampleRate)
            mediaRecorder.setAudioChannels(AudioRecorderConfig.outputChannelCount)
            mediaRecorder.setAudioEncodingBitRate(AudioRecorderConfig.outputBitRate)
            mediaRecorder.setOutputFile(outputFile.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()

            recorder = mediaRecorder
            currentFile = outputFile
            Log.d(TAG, "Recording started: ${outputFile.absolutePath}")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to start recording", error)
            mediaRecorder.release()
            if (outputFile.exists()) {
                outputFile.delete()
            }
            currentFile = null
            throw error
        }
    }

    fun stop(): File? {
        val activeRecorder = recorder ?: return null
        val outputFile = currentFile

        return try {
            activeRecorder.stop()
            Log.d(TAG, "Recording stopped: ${outputFile?.absolutePath}")
            outputFile
        } catch (error: Exception) {
            Log.e(TAG, "Failed to stop recording", error)
            null
        } finally {
            activeRecorder.release()
            recorder = null
            currentFile = null
        }
    }

    fun startRealtimeCapture(
        cache: RealtimeSpeechAudioCache = RealtimeSpeechAudioCache(context.cacheDir),
        onChunk: suspend (ByteArray) -> Unit,
        onError: (Throwable) -> Unit
    ): RealtimeSpeechAudioCache {
        if (isRecording) {
            throw IllegalStateException("Recorder is already running")
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            AudioRecorderConfig.targetPcmSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioRecorderConfig.targetPcmEncoding
        )
        if (minBufferSize <= 0) {
            throw IllegalStateException("AudioRecord min buffer size is invalid: $minBufferSize")
        }

        val readBufferSize = max(minBufferSize, AudioRecorderConfig.pcmReadBufferSizeBytes)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AudioRecorderConfig.targetPcmSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioRecorderConfig.targetPcmEncoding,
            readBufferSize * 2
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }

        audioRecord = recorder
        recorder.startRecording()
        realtimeCaptureJob = realtimeScope.launch {
            val buffer = ByteArray(readBufferSize)
            try {
                while (audioRecord === recorder) {
                    val bytesRead = recorder.read(buffer, 0, buffer.size)
                    when {
                        bytesRead > 0 -> onChunk(buffer.copyOf(bytesRead))
                        bytesRead == 0 -> Unit
                        else -> throw IllegalStateException("AudioRecord read failed: $bytesRead")
                    }
                }
            } catch (error: Throwable) {
                if (audioRecord === recorder) {
                    onError(error)
                }
            }
        }
        Log.d(TAG, "Realtime PCM capture started: sampleRate=${AudioRecorderConfig.targetPcmSampleRate}, buffer=$readBufferSize")
        return cache
    }

    fun stopRealtimeCapture() {
        val recorder = audioRecord ?: return
        audioRecord = null
        realtimeCaptureJob?.cancel()
        realtimeCaptureJob = null
        try {
            recorder.stop()
        } catch (error: Exception) {
            Log.w(TAG, "Failed to stop realtime PCM capture: ${error.message}")
        } finally {
            recorder.release()
            Log.d(TAG, "Realtime PCM capture stopped")
        }
    }

    suspend fun convertToPCM(m4aFile: File): ByteArray = withContext(Dispatchers.Default) {
        Log.d(TAG, "Converting M4A to PCM: ${m4aFile.absolutePath}")
        val decodeResult = decodeM4aToPCM(m4aFile)
        val pcmSamples = if (decodeResult.sampleRate != AudioRecorderConfig.targetPcmSampleRate) {
            resamplePCM(
                decodeResult.samples,
                decodeResult.sampleRate,
                AudioRecorderConfig.targetPcmSampleRate
            )
        } else {
            decodeResult.samples
        }

        val pcmBytes = ByteBuffer
            .allocate(pcmSamples.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        pcmSamples.forEach { sample ->
            pcmBytes.putShort(sample)
        }

        Log.d(
            TAG,
            "PCM conversion complete. inputRate=${decodeResult.sampleRate}, outputRate=${AudioRecorderConfig.targetPcmSampleRate}, bytes=${pcmBytes.array().size}"
        )
        pcmBytes.array()
    }

    private fun decodeM4aToPCM(m4aFile: File): DecodedPCM {
        val extractor = MediaExtractor()
        val samples = ArrayList<Short>()

        try {
            extractor.setDataSource(m4aFile.absolutePath)
            val trackIndex = findAudioTrack(extractor)
            extractor.selectTrack(trackIndex)

            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mimeType = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("Missing audio mime type")

            val codec = MediaCodec.createDecoderByType(mimeType)
            var codecStarted = false
            try {
                codec.configure(inputFormat, null, null, 0)
                codec.start()
                codecStarted = true

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                while (!outputDone) {
                    if (!inputDone) {
                        val inputBufferIndex = codec.dequeueInputBuffer(AudioRecorderConfig.codecTimeoutUs)
                        if (inputBufferIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                                ?: throw IllegalStateException("Missing codec input buffer")
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    sampleSize,
                                    extractor.sampleTime,
                                    0
                                )
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputBufferIndex = codec.dequeueOutputBuffer(info, AudioRecorderConfig.codecTimeoutUs)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = codec.outputFormat
                            if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                        }

                        else -> {
                            if (outputBufferIndex >= 0) {
                                val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                                    ?: throw IllegalStateException("Missing codec output buffer")
                                val chunk = ByteArray(info.size)
                                outputBuffer.position(info.offset)
                                outputBuffer.limit(info.offset + info.size)
                                outputBuffer.get(chunk)
                                outputBuffer.clear()

                                appendDecodedSamples(samples, chunk, channelCount)
                                codec.releaseOutputBuffer(outputBufferIndex, false)

                                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    outputDone = true
                                }
                            }
                        }
                    }
                }

                if (samples.isEmpty()) {
                    throw IllegalStateException("Decoded PCM is empty")
                }

                return DecodedPCM(
                    sampleRate = sampleRate,
                    samples = samples.toShortArray()
                )
            } finally {
                if (codecStarted) {
                    codec.stop()
                }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                return index
            }
        }
        throw IllegalStateException("No audio track found")
    }

    private fun appendDecodedSamples(samples: MutableList<Short>, chunk: ByteArray, channelCount: Int) {
        if (chunk.isEmpty()) {
            return
        }

        val shortBuffer = ByteBuffer
            .wrap(chunk)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        val pcm = ShortArray(shortBuffer.remaining())
        shortBuffer.get(pcm)

        if (channelCount <= 1) {
            pcm.forEach { sample -> samples.add(sample) }
            return
        }

        var index = 0
        while (index < pcm.size) {
            samples.add(pcm[index])
            index += channelCount
        }
    }

    private fun resamplePCM(
        input: ShortArray,
        inputSampleRate: Int,
        outputSampleRate: Int
    ): ShortArray = AudioResampler.resample(input, inputSampleRate, outputSampleRate)

    private data class DecodedPCM(
        val sampleRate: Int,
        val samples: ShortArray
    )

    private companion object {
        private const val TAG = "AudioRecorderManager"
    }
}

/**
 * Linear-interpolation PCM resampler.
 * Extracted as a top-level object so it can be unit-tested without Android Context.
 */
object AudioResampler {
    fun resample(
        input: ShortArray,
        inputSampleRate: Int,
        outputSampleRate: Int
    ): ShortArray {
        if (input.isEmpty()) {
            return ShortArray(0)
        }
        if (inputSampleRate == outputSampleRate) {
            return input.copyOf()
        }

        val ratio = outputSampleRate.toDouble() / inputSampleRate.toDouble()
        val outputSize = max(1, (input.size * ratio).toInt())
        val output = ShortArray(outputSize)

        for (i in output.indices) {
            val sourcePosition = i / ratio
            val index = floor(sourcePosition).toInt()
            val nextIndex = min(index + 1, input.lastIndex)
            val fraction = sourcePosition - index

            val first = input[index].toDouble()
            val second = input[nextIndex].toDouble()
            val interpolated = first + ((second - first) * fraction)
            output[i] = interpolated
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }

        return output
    }
}
