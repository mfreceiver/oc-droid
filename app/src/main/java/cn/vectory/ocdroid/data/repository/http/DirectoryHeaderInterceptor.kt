package cn.vectory.ocdroid.data.repository.http

import okhttp3.Interceptor
import okhttp3.Response
import cn.vectory.ocdroid.util.DebugLog

/**
 * §16.1(b) directory-scoping interceptor: ensures the caller-supplied
 * `X-Opencode-Directory` header is carried through, if present. Split out
 * of the pre-R-18 combined header/auth/cache interceptor so it can be
 * unit-tested in isolation.
 *
 * Behavior:
 *
 * - Caller-supplied `X-Opencode-Directory` header is the ONLY source of the
 *   directory. The global HostConfig workdir fallback was removed; every
 *   directory-scoped endpoint now passes its directory explicitly via
 *   `@Header(HttpHeaders.DIRECTORY_HEADER)`.
 * - No caller-supplied header → pass through unchanged (no injection). The
 *   server then falls back to its own process.cwd() — callers that need
 *   workdir-scoped routing MUST pass an explicit `directory` parameter.
 *
 * Note (R-14): this interceptor is on the shared base chain, so SSE
 * requests also flow through it. That matches the pre-R-18 behavior — SSE
 * has always carried the directory header when a workdir is set — and is
 * intentionally preserved. Do not add SSE-specific bypass logic here.
 */
class DirectoryHeaderInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        // §R18 Phase 2-E step 2: the directory is sourced ONLY from the
        // caller-supplied X-Opencode-Directory header. The global HostConfig
        // workdir fallback was removed; every directory-scoped endpoint now
        // passes its directory explicitly. No header → no injection (callers
        // that need workdir routing MUST pass it).
        val effectiveDir = original.header(HttpHeaders.DIRECTORY_HEADER)

        val built = original.newBuilder().build()
        // §Phase1a→1c de-noise: only log question/session-relevant paths. The
        // interceptor fires on EVERY request; unconditionally logging floods the
        // 1000-entry DebugLog ring buffer and evicts the Issue 1/4 diagnostics
        // we added it for. Health/messages/files/VCS are high-volume + not
        // diagnosis-relevant, so they are silenced.
        val path = original.url.encodedPath
        if (DebugLog.verboseDiagEnabled && (path.contains("/question") || path.contains("/session/"))) {
            DebugLog.d(
                "Http",
                "intercept path=$path dirSent=${built.header(HttpHeaders.DIRECTORY_HEADER) ?: "null"}"
            )
        }
        return chain.proceed(built)
    }
}
