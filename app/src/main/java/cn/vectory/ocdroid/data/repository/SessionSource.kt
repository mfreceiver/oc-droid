package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SlimSessionsPage
import cn.vectory.ocdroid.data.repository.http.SlimapiErrorCodes
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.exponentialBackoffMs
import cn.vectory.ocdroid.util.runSuspendCatching
import kotlinx.coroutines.delay

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
 *
 * **v0.9.0 503 backoff** — [parseErrorCode] / [retryAfterHeaderToMs] are injected
 * as lambdas that delegate to the OCR `internal fun`s of the same name (the
 * single source of truth for coded-envelope parsing + Retry-After decoding),
 * mirroring the OCR injection pattern. No helper is re-defined
 * here.
 */
internal class SlimSessionSource(
    private val apiProvider: () -> OpenCodeApi,
    private val parseErrorCode: (retrofit2.Response<*>) -> String?,
    private val retryAfterHeaderToMs: (String?) -> Long,
) : SessionSource {
    override suspend fun getSessions(limit: Int?) =
        getSlimapiSessionsDelegate(apiProvider(), null, null, limit, parseErrorCode, retryAfterHeaderToMs)
            .mapCatching { it.sessions }

    override suspend fun getSessionsForDirectory(directory: String, limit: Int?) =
        getSlimapiSessionsDelegate(apiProvider(), listOf(directory), true, limit, parseErrorCode, retryAfterHeaderToMs)
            .mapCatching { it.sessions }
}

/**
 * Extracted delegate — mirrors [OpenCodeRepository.getSlimapiSessions] body
 * verbatim. Encapsulates the slimapi sessions Retrofit call + non-2xx error
 * decoding + the v0.9.0 `503 transform_busy` Retry-After backoff (mirrors
 * ≤3 attempts, Retry-After header honored with
 * exponential-backoff fall-back, only `503 + transform_busy` retries; every
 * other status fails immediately preserving prior behavior).
 *
 * **One-shot errorBody discipline**:
 * the sidecar's coded envelope is read EXACTLY ONCE via the injected
 * [parseErrorCode] (OkHttp buffers errorBody for one-shot consumption); the
 * parsed `code` is then used for BOTH the retry decision AND WARN-level
 * observability logging. Reading the body twice (once to branch, once to log)
 * silently swallows the log because the second read returns null.
 *
 * [parseErrorCode] / [retryAfterHeaderToMs] are injected so this top-level
 * delegate reuses the OCR `internal fun`s (single source of truth) without
 * the delegate holding an OCR reference.
 */
internal suspend fun getSlimapiSessionsDelegate(
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
        // Non-2xx: read the sidecar's coded envelope ONCE (errorBody is
        // one-shot). The parsed code drives BOTH the 503+transform_busy
        // retry decision AND the WARN observability log.
        val code = parseErrorCode(resp)
        if (resp.code() == 503 && code == SlimapiErrorCodes.TRANSFORM_BUSY && attempts < 3) {
            val retryAfterMs = retryAfterHeaderToMs(resp.headers()["Retry-After"])
            val delayMs = if (retryAfterMs > 0L) retryAfterMs else backoffMs(attempts)
            delay(delayMs)
            continue
        }
        // Non-503 / non-transform_busy / final attempt → observability + fail.
        if (code != null) {
            DebugLog.w("OpenCodeRepository", "slimapi sessions failed: $code")
        }
        lastException = retrofit2.HttpException(resp)
        break
    }
    throw lastException ?: throw AssertionError("unreachable")
}

/** Exponential backoff for sessions 503 retry: 200ms, 400ms with ±30% jitter. */
private fun backoffMs(attempt: Int): Long {
    val base = exponentialBackoffMs(attempt - 1, 200L, Int.MAX_VALUE)
    val jitterRange = (base * 0.30).toLong()
    val jitter = (Math.random() * (2.0 * jitterRange + 1.0)).toLong() - jitterRange
    return (base + jitter).coerceAtLeast(0L)
}
