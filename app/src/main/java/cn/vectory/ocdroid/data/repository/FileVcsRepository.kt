package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.FileContent
import cn.vectory.ocdroid.data.model.FileDiff
import cn.vectory.ocdroid.data.model.FileNode
import cn.vectory.ocdroid.data.model.FileStatusEntry
import cn.vectory.ocdroid.data.model.TodoItem
import cn.vectory.ocdroid.data.model.VcsInfo
import cn.vectory.ocdroid.data.model.VcsStatusEntry

/** Phase B narrow seam: files / diff / vcs. Implemented by [OpenCodeRepository]. */
interface FileVcsRepository {
    suspend fun getSessionDiff(sessionId: String): Result<List<FileDiff>>
    suspend fun getSessionTodos(sessionId: String): Result<List<TodoItem>>
    suspend fun getFileTree(directory: String, path: String? = null): Result<List<FileNode>>
    suspend fun getFileTreeForDirectory(directory: String, path: String? = null): Result<List<FileNode>>
    suspend fun getFileContent(directory: String, path: String): Result<FileContent>
    suspend fun getFileStatus(directory: String): Result<List<FileStatusEntry>>
    suspend fun getVcs(directory: String?): Result<VcsInfo>
    suspend fun getVcsStatus(directory: String?): Result<List<VcsStatusEntry>>
    suspend fun getVcsDiff(mode: String, directory: String?): Result<List<FileDiff>>
    suspend fun findFile(directory: String, query: String, limit: Int = 50): Result<List<String>>
}
