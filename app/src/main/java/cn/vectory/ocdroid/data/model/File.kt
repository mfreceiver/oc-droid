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
    val patch: String? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val status: String? = null
) {
    val file: String get() = filePath ?: pathAlt ?: ""
    val id: String get() = file
}

/**
 * `GET /vcs` — repository metadata for the workdir. Both fields are null when
 * the workdir is not a git repo (the server still returns 200 with nulls).
 */
@Serializable
data class VcsInfo(
    val branch: String? = null,
    @SerialName("default_branch") val defaultBranch: String? = null
)

/**
 * `GET /vcs/status` entry — one changed file. [additions]/[deletions] default
 * to 0 (server may omit them). [status] is one of "added" / "deleted" /
 * "modified" / "renamed" / … (open-ended, matched case-insensitively by the
 * UI for color-coding).
 */
@Serializable
data class VcsStatusEntry(
    val file: String,
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String
)
