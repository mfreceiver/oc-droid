package cn.vectory.ocdroid.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FileNode(
    val name: String,
    val path: String,
    val absolute: String? = null,
    val type: String,
    val ignored: Boolean? = null
) {
    val id: String get() = path
    val isDirectory: Boolean get() = type == "directory"
    val isFile: Boolean get() = type == "file"
}

@Serializable
data class FileContent(
    val type: String,
    val content: String? = null
) {
    val isText: Boolean get() = type == "text"
    val isBinary: Boolean get() = type == "binary"
    val text: String? get() = if (isText) content else null
}

@Serializable
data class FileStatusEntry(
    val path: String? = null,
    val status: String? = null
)

@Serializable
data class FileDiff(
    @SerialName("file") val filePath: String? = null,
    @SerialName("path") val pathAlt: String? = null,
    val before: String? = null,
    val after: String? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val status: String? = null
) {
    val file: String get() = filePath ?: pathAlt ?: ""
    val id: String get() = file
}
