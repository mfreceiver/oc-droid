package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.SlimSessionsPage

/** Phase B narrow seam: session list / tree hydrate / status. Implemented by [OpenCodeRepository]. */
interface SessionRepository {
    suspend fun getSessions(limit: Int? = null): Result<List<Session>>
    suspend fun getSessionsForDirectory(directory: String, limit: Int? = null): Result<List<Session>>
    suspend fun getSession(sessionId: String): Result<Session>
    suspend fun createSession(title: String? = null, directory: String? = null): Result<Session>
    suspend fun updateSession(sessionId: String, title: String): Result<Session>
    suspend fun updateSessionArchived(sessionId: String, archived: Long): Result<Session>
    suspend fun deleteSession(sessionId: String): Result<Unit>
    suspend fun getSessionStatus(): Result<Map<String, SessionStatus>>
    suspend fun getActiveSessionIds(): Result<Set<String>>
    suspend fun getSlimapiSessionsStatus(directory: String): Result<Map<String, SessionStatus>>
    suspend fun getChildren(sessionId: String): Result<List<Session>>
    suspend fun getSlimapiSessionStatusOutcome(sessionId: String): StatusOutcome
    suspend fun getSlimapiSessions(
        directories: List<String>? = null,
        roots: Boolean? = null,
        limit: Int? = null,
        search: String? = null,
    ): Result<SlimSessionsPage>
}
