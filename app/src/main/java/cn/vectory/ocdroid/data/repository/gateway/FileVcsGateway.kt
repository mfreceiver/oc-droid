package cn.vectory.ocdroid.data.repository.gateway

import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.model.FileContent
import cn.vectory.ocdroid.data.model.FileDiff
import cn.vectory.ocdroid.data.model.FileNode
import cn.vectory.ocdroid.data.model.FileStatusEntry
import cn.vectory.ocdroid.data.model.TodoItem
import cn.vectory.ocdroid.data.model.VcsInfo
import cn.vectory.ocdroid.data.model.VcsStatusEntry
import cn.vectory.ocdroid.data.repository.ClientBundle
import cn.vectory.ocdroid.util.runSuspendCatching

/**
 * Gateway for file VCS operations: file tree, content, status, diff, VCS.
 *
 * Zero mutable state — all reads go through [bundleProvider] every call,
 * preserving the generational-consistency invariant.
 */
internal class FileVcsGateway(
    private val bundleProvider: () -> ClientBundle,
) {
    private val api: OpenCodeApi get() = bundleProvider().restApi

    suspend fun getSessionDiff(sessionId: String): Result<List<FileDiff>> =
        runSuspendCatching { api.getSessionDiff(sessionId) }

    suspend fun getSessionTodos(sessionId: String): Result<List<TodoItem>> =
        runSuspendCatching { api.getSessionTodos(sessionId) }

    suspend fun getFileTree(directory: String, path: String? = null): Result<List<FileNode>> =
        runSuspendCatching { api.getFileTree(path ?: "", directory) }

    suspend fun getFileTreeForDirectory(directory: String, path: String? = null): Result<List<FileNode>> =
        runSuspendCatching { api.getFileTreeForDirectory(directory, path ?: "") }

    suspend fun getFileContent(directory: String, path: String): Result<FileContent> =
        runSuspendCatching { api.getFileContent(path, directory) }

    suspend fun getFileStatus(directory: String): Result<List<FileStatusEntry>> =
        runSuspendCatching { api.getFileStatus(directory) }

    suspend fun getVcs(directory: String?): Result<VcsInfo> =
        runSuspendCatching { api.getVcs(directory) }

    suspend fun getVcsStatus(directory: String?): Result<List<VcsStatusEntry>> =
        runSuspendCatching { api.getVcsStatus(directory) }

    suspend fun getVcsDiff(mode: String, directory: String?): Result<List<FileDiff>> =
        runSuspendCatching { api.getVcsDiff(mode, directory) }

    suspend fun findFile(directory: String, query: String, limit: Int = 50): Result<List<String>> =
        runSuspendCatching { api.findFile(query, limit, directory) }
}
