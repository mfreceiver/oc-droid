package com.yage.opencode_client.data.audio

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID

class RealtimeSpeechAudioCache(
    directory: File
) {
    val file: File = File(
        directory,
        "${AudioRecorderConfig.realtimeTempFilePrefix}${UUID.randomUUID()}${AudioRecorderConfig.realtimeTempFileSuffix}"
    )

    private var byteCountValue = 0

    init {
        directory.mkdirs()
        file.createNewFile()
    }

    val byteCount: Int
        @Synchronized get() = byteCountValue

    @Synchronized
    fun append(data: ByteArray) {
        if (data.isEmpty()) return
        FileOutputStream(file, true).use { output ->
            output.write(data)
        }
        byteCountValue += data.size
    }

    @Synchronized
    fun readChunk(offset: Int, maxBytes: Int): ByteArray {
        require(offset >= 0) { "offset must be non-negative" }
        require(maxBytes > 0) { "maxBytes must be positive" }
        if (offset >= byteCountValue) return ByteArray(0)

        val readSize = minOf(maxBytes, byteCountValue - offset)
        val buffer = ByteArray(readSize)
        RandomAccessFile(file, "r").use { input ->
            input.seek(offset.toLong())
            input.readFully(buffer)
        }
        return buffer
    }

    @Synchronized
    fun remove() {
        file.delete()
        byteCountValue = 0
    }
}
