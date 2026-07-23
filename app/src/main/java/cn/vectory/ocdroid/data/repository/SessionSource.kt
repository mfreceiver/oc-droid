package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SlimSessionsPage
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.runSuspendCatching
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.decodeFromString

/**
 * ι-P1 session 域端口。签名域语言,无 slim/legacy 字眼。
 */
internal interface SessionSource {
    suspend fun getSessions(limit: Int?): Result<List<Session>>
    suspend fun getSessionsForDirectory(directory: String, limit: Int?): Result<List<Session>>
}

/**
 * Standard: 只 legacy。apiProvider lambda 防陈旧(复用 SlimGetRepository 模式)。
 */
internal class StandardSessionSource(private val apiProvider: () -> OpenCodeApi) : SessionSource {
    override suspend fun getSessions(limit: Int?) =
        runSuspendCatching { apiProvider().getSessions(limit) }

    override suspend fun getSessionsForDirectory(directory: String, limit: Int?) =
        runSuspendCatching { apiProvider().getSessions(limit = limit, directory = directory, roots = true) }
}

/**
 * Slim: 只 slim。共享态暂不需要(session list 无 watermark);bookmark 是 message 域。
 */
internal class SlimSessionSource(private val apiProvider: () -> OpenCodeApi) : SessionSource {
    override suspend fun getSessions(limit: Int?) =
        getSlimapiSessionsDelegate(apiProvider(), null, null, limit).mapCatching { it.sessions }

    override suspend fun getSessionsForDirectory(directory: String, limit: Int?) =
        getSlimapiSessionsDelegate(apiProvider(), listOf(directory), true, limit).mapCatching { it.sessions }
}

/**
 * Extracted delegate — mirrors [OpenCodeRepository.getSlimapiSessions] body
 * verbatim. Encapsulates the slimapi sessions Retrofit call + non-2xx error
 * decoding (parseErrorCode from response body) + recoverCatching rethrow
 * pattern so both [SlimSessionSource] and [OpenCodeRepository.getSlimapiSessions]
 * share identical envelope-unwrapping / error-logging behavior.
 */
internal suspend fun getSlimapiSessionsDelegate(
    api: OpenCodeApi,
    directories: List<String>?,
    roots: Boolean?,
    limit: Int?,
    search: String? = null,
): Result<SlimSessionsPage> = runSuspendCatching {
    val resp = api.getSlimapiSessions(directories, roots, limit, search)
    if (!resp.isSuccessful) {
        logSlimapiSessionErrorCode(resp)
        throw retrofit2.HttpException(resp)
    }
    val sessions = resp.body() ?: emptyList()
    val headers = resp.headers()
    SlimSessionsPage(
        sessions = sessions,
        complete = headers?.get("X-Complete")?.toBooleanStrictOrNull(),
        discoveryDirectories = headers?.get("X-Discovery-Directories")?.toIntOrNull(),
        discoveryReady = headers?.get("X-Discovery-Ready")?.toBooleanStrictOrNull(),
    )
}.recoverCatching { e ->
    if (e is retrofit2.HttpException) {
        val resp = e.response()
        if (resp != null) logSlimapiSessionErrorCode(resp)
    }
    throw e
}

/**
 * Parses and logs the sidecar's coded error envelope from a non-2xx response
 * (the same [OpenCodeRepository.parseErrorCode] + [DebugLog.w] pattern).
 *
 * Uses its own [Json] instance with the same settings as OCR's so parsing
 * shape is identical; debug tag is `"OpenCodeRepository"` so logcat output
 * is indistinguishable from the OCR-originated code log.
 */
private fun logSlimapiSessionErrorCode(resp: retrofit2.Response<*>) {
    val rawBody = runCatching { resp.errorBody()?.string() }.getOrNull() ?: return
    val code = try {
        val obj = SESSION_SOURCE_JSON.decodeFromString<JsonObject>(rawBody)
        (obj["code"] as? JsonPrimitive)?.content
    } catch (_: Exception) { null }
    if (code != null) {
        DebugLog.w("OpenCodeRepository", "slimapi sessions failed: $code")
    }
}

/**
 * Local [Json] instance with the same configuration as [OpenCodeRepository.json]
 * so error-body parsing yields identical results.
 */
private val SESSION_SOURCE_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}
