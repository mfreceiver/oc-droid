package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.model.*
import cn.vectory.ocdroid.data.api.CommandInfo
import cn.vectory.ocdroid.util.runSuspendCatching

/**
 * §L4a3: thin GET delegate extracted from [OpenCodeRepository].
 *
 * Holds only lowest-risk, pure legacy GET wrappers that forward 1:1 to the
 * Retrofit [api] instance, without any slim branching, health, TOFU, state
 * machine, or monitor/token-sensitive logic.
 *
 * Follows the [TofuRepository] / [ExpandBatchEngine] constructor-provider
 * pattern: receives an [apiProvider] lambda that reads the current
 * [OpenCodeApi] instance (which the host [OpenCodeRepository] rebuilds on
 * every [configure] call), so this delegate never holds a stale reference.
 */
internal class SlimGetRepository(
    private val apiProvider: () -> OpenCodeApi,
) {
    suspend fun getSessionStatus(): Result<Map<String, SessionStatus>> =
        runSuspendCatching { apiProvider().getSessionStatus() }

    suspend fun getActiveSessionIds(): Result<Set<String>> =
        runSuspendCatching { apiProvider().getActiveSessions().data.keys }

    suspend fun getSession(sessionId: String): Result<Session> =
        runSuspendCatching { apiProvider().getSession(sessionId) }

    suspend fun getChildren(sessionId: String): Result<List<Session>> =
        runSuspendCatching { apiProvider().getChildren(sessionId) }

    suspend fun getSessionDiff(sessionId: String): Result<List<FileDiff>> =
        runSuspendCatching { apiProvider().getSessionDiff(sessionId) }

    suspend fun getSessionTodos(sessionId: String): Result<List<TodoItem>> =
        runSuspendCatching { apiProvider().getSessionTodos(sessionId) }

    suspend fun getAgents(): Result<List<AgentInfo>> =
        runSuspendCatching { apiProvider().getAgents() }

    suspend fun getCommands(): Result<List<CommandInfo>> =
        runSuspendCatching { apiProvider().getCommands() }

    suspend fun getFileTree(directory: String, path: String? = null): Result<List<FileNode>> =
        runSuspendCatching { apiProvider().getFileTree(path ?: "", directory) }

    suspend fun getFileTreeForDirectory(directory: String, path: String? = null): Result<List<FileNode>> =
        runSuspendCatching { apiProvider().getFileTreeForDirectory(directory, path ?: "") }

    suspend fun getFileContent(directory: String, path: String): Result<FileContent> =
        runSuspendCatching { apiProvider().getFileContent(path, directory) }

    suspend fun getFileStatus(directory: String): Result<List<FileStatusEntry>> =
        runSuspendCatching { apiProvider().getFileStatus(directory) }

    suspend fun getVcs(directory: String?): Result<VcsInfo> =
        runSuspendCatching { apiProvider().getVcs(directory) }

    suspend fun getVcsStatus(directory: String?): Result<List<VcsStatusEntry>> =
        runSuspendCatching { apiProvider().getVcsStatus(directory) }

    suspend fun getVcsDiff(mode: String, directory: String?): Result<List<FileDiff>> =
        runSuspendCatching { apiProvider().getVcsDiff(mode, directory) }

    suspend fun findFile(directory: String, query: String, limit: Int = 50): Result<List<String>> =
        runSuspendCatching { apiProvider().findFile(query, limit, directory) }
}
