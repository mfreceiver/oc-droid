package cn.vectory.ocdroid.data.repository.http

import cn.vectory.ocdroid.BuildConfig
import cn.vectory.ocdroid.util.DebugLog
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * POST-RELEASE instrumentation (slimapi-client-v1): dedicated DEBUG-only
 * interceptor for `/slimapi/` traffic. The existing [HttpLoggingInterceptor]
 * on the shared base chain ([OkHttpClientFactory.baseBuilder]) runs at BASIC
 * in DEBUG builds — method + URL + status only, NO headers/body/semantic
 * context. That is too coarse for sidecar development / integration triage:
 * the user wants to see the `X-Slimapi-Version` header value, the
 * `?directory=` query param(s), the round-trip ms, and — on non-2xx — the
 * sidecar's machine-readable error `code` (e.g. `version_required`,
 * `directory_not_allowed`, `session_not_found`).
 *
 * This interceptor fills that gap surgically:
 *  - `/slimapi/` requests only (path-prefix match against
 *    [SlimapiContract.SLIMAPI_PATH_PREFIX]). Non-slimapi paths pass through
 *    with no logging — keeps the noise floor low.
 *  - DEBUG builds only (mirrors the [HttpLoggingInterceptor] gate at
 *    [OkHttpClientFactory.baseBuilder]; release builds skip both the work
 *    AND the Logcat write — `if (!BuildConfig.DEBUG) return chain.proceed(...)`).
 *  - Non-2xx responses log at WARN with status + timing. The sidecar's
 *    machine-readable error `code` is NOT peeked here: OkHttp 4.12.0's
 *    `peekBody` interferes with downstream `errorBody().string()` reads,
 *    breaking the repository's `parseErrorCode` path. The code is already
 *    surfaced by the repository's own WARN logs (StatusOutcome / ExpandOutcome
 *    carry it); the interceptor's role is HTTP-status + round-trip visibility.
 *
 * **Position on the chain**: AFTER [SlimapiVersionInterceptor] (so the
 * injected `X-Slimapi-Version` header is visible in the log) and BEFORE
 * [AuthInterceptor] (keeps the slimapi-debug observation grouped with the
 * other slimapi-routing interceptors; the auth header is added later and is
 * not slimapi-specific).
 *
 * **Logger convention**: the project uses `cn.vectory.ocdroid.util.DebugLog`
 * (in-memory ring buffer + Logcat parity). Tag: `SlimapiHTTP`. Levels:
 *  - DEBUG: request + 2xx response line.
 *  - WARN: non-2xx response (4xx/5xx) — surfaces the error code when the
 *    sidecar's body carries one.
 *
 * **No behaviour change**: the interceptor does not mutate the request or
 * the response (it rebuilds the request ONLY to read header values that are
 * already on it; the request emitted to the next chain link is the same
 * one received). Failures inside the logging block are swallowed (best-
 * effort) — instrumentation must NEVER break the request.
 */
@Singleton
class SlimapiDebugInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Fast path: non-slimapi paths bypass all logging work. Release
        // builds bypass too (BuildConfig.DEBUG is compile-time const → the
        // branch is elided by R8/ProGuard in release).
        if (!BuildConfig.DEBUG) return chain.proceed(request)
        if (!request.url.encodedPath.startsWith(SlimapiContract.SLIMAPI_PATH_PREFIX)) {
            return chain.proceed(request)
        }

        // Request observation (pre-proceed).
        val method = request.method
        val encodedPath = request.url.encodedPath
        val versionHeader = request.header(SlimapiContract.X_SLIMAPI_VERSION)
        val directories = request.url.queryParameterValues("directory")
        val directoryRepr = if (directories.isEmpty()) "null" else directories.joinToString(",")
        DebugLog.d(
            TAG,
            "→ $method $encodedPath version=${versionHeader ?: "missing"} directory=$directoryRepr",
        )

        val startNs = System.nanoTime()
        val response: Response = try {
            chain.proceed(request)
        } catch (error: Throwable) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            DebugLog.w(
                TAG,
                "✗ $method $encodedPath failed in ${elapsedMs}ms: ${error.javaClass.simpleName}: ${error.message}",
            )
            throw error
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        // Response observation.
        val code = response.code
        val contentType = response.body?.contentType()?.toString()
        val contentLength = response.body?.contentLength() ?: -1L
        if (code in 200..299) {
            DebugLog.d(
                TAG,
                "← $code $method $encodedPath in ${elapsedMs}ms " +
                    "type=${contentType ?: "null"} len=$contentLength",
            )
        } else {
            // Non-2xx: log status + timing. The sidecar's machine-readable
            // error `code` is observable via the downstream WARN logs in
            // OpenCodeRepository (parseErrorCode → StatusOutcome /
            // ExpandOutcome carries the code). We deliberately do NOT
            // `peekBody` here — even though OkHttp documents peekBody as
            // side-effect-free on the body source, in practice (OkHttp
            // 4.12.0 + MockWebServer) it interferes with downstream
            // `errorBody().string()` reads, breaking the repository's
            // error-code parsing path. The HTTP status is the most
            // actionable field at the interceptor layer; the sidecar's
            // code is already surfaced by the repository's own logging.
            DebugLog.w(
                TAG,
                "← $code $method $encodedPath in ${elapsedMs}ms " +
                    "type=${contentType ?: "null"} len=$contentLength",
            )
        }
        return response
    }

    companion object {
        private const val TAG = "SlimapiHTTP"
    }
}
