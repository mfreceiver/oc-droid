package cn.vectory.ocdroid.data.repository.gateway

import cn.vectory.ocdroid.data.api.CreateSessionRequest
import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.api.UpdateSessionRequest
import cn.vectory.ocdroid.data.api.UpdateSessionTimeRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.SlimSessionsPage
import cn.vectory.ocdroid.data.repository.ClientBundle
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.data.repository.StatusOutcome
import cn.vectory.ocdroid.data.repository.http.SlimapiErrorCodes
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.exponentialBackoffMs
import cn.vectory.ocdroid.util.runSuspendCatching
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive


/**
 * Gateway for session lifecycle operations: list, tree, hydrate.
 *
 * Zero mutable state — all reads go through [bundleProvider] every call,
 * preserving the generational-consistency invariant.
 */
internal class SessionGateway(
    private val bundleProvider: () -> ClientBundle,
    private val serverCompatProfile: ServerCompatProfile,
    private val json: Json,
) {
    private val api: OpenCodeApi get() = bundleProvider().restApi
    private val mutationApi: OpenCodeApi get() = bundleProvider().mutationApi

    suspend fun getSessions(limit: Int? = null): Result<List<Session>> =
        if (serverCompatProfile.slimConnection)
            getSlimapiSessionsDelegate(api, null, null, limit, { parseErrorCode(it) }, { retryAfterHeaderToMs(it) })
                .mapCatching { it.sessions }
        else
            runSuspendCatching { api.getSessions(limit) }

    suspend fun getSessionsForDirectory(directory: String, limit: Int? = null): Result<List<Session>> =
        if (serverCompatProfile.slimConnection)
            getSlimapiSessionsDelegate(api, listOf(directory), true, limit, { parseErrorCode(it) }, { retryAfterHeaderToMs(it) })
                .mapCatching { it.sessions }
        else
            runSuspendCatching { api.getSessions(limit = limit, directory = directory, roots = true) }

    suspend fun getSession(sessionId: String): Result<Session> =
        runSuspendCatching { api.getSession(sessionId) }

    suspend fun createSession(title: String? = null, directory: String? = null): Result<Session> =
        runSuspendCatching { mutationApi.createSession(CreateSessionRequest(title = title), directory) }

    suspend fun updateSession(sessionId: String, title: String): Result<Session> =
        runSuspendCatching { api.updateSession(sessionId, UpdateSessionRequest(title = title)) }

    suspend fun updateSessionArchived(sessionId: String, archived: Long): Result<Session> =
        runSuspendCatching { api.updateSession(sessionId, UpdateSessionRequest(time = UpdateSessionTimeRequest(archived = archived))) }

    suspend fun deleteSession(sessionId: String): Result<Unit> =
        runSuspendCatching { api.deleteSession(sessionId) }

    suspend fun getSessionStatus(): Result<Map<String, SessionStatus>> =
        runSuspendCatching { api.getSessionStatus() }

    suspend fun getActiveSessionIds(): Result<Set<String>> =
        runSuspendCatching { api.getActiveSessions().data.keys }

    suspend fun getSlimapiSessionsStatus(directory: String): Result<Map<String, SessionStatus>> =
        runSuspendCatching {
            if (!serverCompatProfile.slimConnection || !serverCompatProfile.supportsSlimStatus) {
                return@runSuspendCatching api.getSessionStatus()
            }
            val resp = api.getSlimapiSessionsStatus(directory)
            if (resp.isSuccessful) {
                serverCompatProfile.markSlimStatusSupported()
                resp.body() ?: emptyMap()
            } else if (resp.code() == 404) {
                serverCompatProfile.markSlimStatusUnsupported()
                DebugLog.w("SessionGateway",
                    "slimapi /slimapi/sessions/status 404 (old sidecar) → fallback to standard API")
                api.getSessionStatus()
            } else {
                throw java.io.IOException("slimapi sessions/status HTTP ${resp.code()}")
            }
        }

    suspend fun getChildren(sessionId: String): Result<List<Session>> =
        runSuspendCatching { api.getChildren(sessionId) }

    suspend fun getSlimapiSessionStatusOutcome(sessionId: String): StatusOutcome {
        return try {
            val all = api.getSessionStatus()
            val status = all[sessionId]
            StatusOutcome.Success(sessionId, status ?: SessionStatus(type = "idle"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            StatusOutcome.Retry(sessionId, null)
        } catch (e: Exception) {
            StatusOutcome.Retry(sessionId, null)
        }
    }

    suspend fun getSlimapiSessions(
        directories: List<String>? = null,
        roots: Boolean? = null,
        limit: Int? = null,
        search: String? = null,
    ): Result<SlimSessionsPage> =
        getSlimapiSessionsDelegate(api, directories, roots, limit, { parseErrorCode(it) }, { retryAfterHeaderToMs(it) }, search)

    // ── Private helpers ─────────────────────────────────────────────────

    private fun parseErrorCode(r: retrofit2.Response<*>): String? =
        parseErrorCodeFromRaw(runCatching { r.errorBody()?.string() }.getOrNull())

    private fun parseErrorCodeFromRaw(rawBody: String?): String? {
        if (rawBody == null) return null
        return try {
            val obj = json.decodeFromString<JsonObject>(rawBody)
            (obj["code"] as? JsonPrimitive)?.content
        } catch (e: Exception) {
            null
        }
    }

    private fun retryAfterHeaderToMs(header: String?): Long {
        if (header == null) return 0L
        return ((header.toLongOrNull() ?: 0L) * 1000L).coerceIn(0L, 10_000L)
    }

    /**
     * Extracted delegate — mirrors [getSlimapiSessions] body verbatim.
     * Encapsulates the slimapi sessions Retrofit call + non-2xx error
     * decoding + the v0.9.0 `503 transform_busy` Retry-After backoff.
     */
    private suspend fun getSlimapiSessionsDelegate(
        api: OpenCodeApi,
        directories: List<String>?,
        roots: Boolean?,
        limit: Int?,
        parseErrorCode: (retrofit2.Response<*>) -> String?,
        retryAfterHeaderToMs: (String?) -> Long,
        search: String? = null,
    ): Result<SlimSessionsPage> = runSuspendCatching {
        var lastException: retrofit2.HttpException? = null
        for (attempts in 1..3) {
            val resp = api.getSlimapiSessions(directories, roots, limit, search)
            if (resp.isSuccessful) {
                val sessions = resp.body() ?: emptyList()
                val headers = resp.headers()
                return@runSuspendCatching SlimSessionsPage(
                    sessions = sessions,
                    complete = headers?.get("X-Complete")?.toBooleanStrictOrNull(),
                )
            }
            val code = parseErrorCode(resp)
            if (resp.code() == 503 && code == SlimapiErrorCodes.TRANSFORM_BUSY && attempts < 3) {
                val retryAfterMs = retryAfterHeaderToMs(resp.headers()["Retry-After"])
                val delayMs = if (retryAfterMs > 0L) retryAfterMs else backoffMs(attempts)
                delay(delayMs)
                continue
            }
            if (code != null) {
                DebugLog.w("SessionGateway", "slimapi sessions failed: $code")
            }
            lastException = retrofit2.HttpException(resp)
            break
        }
        throw lastException ?: throw AssertionError("unreachable")
    }

    companion object {
        /** Exponential backoff for sessions 503 retry: 200ms, 400ms with ±30% jitter. */
        private fun backoffMs(attempt: Int): Long {
            val base = exponentialBackoffMs(attempt - 1, 200L, Int.MAX_VALUE)
            val jitterRange = (base * 0.30).toLong()
            val jitter = (Math.random() * (2.0 * jitterRange + 1.0)).toLong() - jitterRange
            return (base + jitter).coerceAtLeast(0L)
        }
    }
}
