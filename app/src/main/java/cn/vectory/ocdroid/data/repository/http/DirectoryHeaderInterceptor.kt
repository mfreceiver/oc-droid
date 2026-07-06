package cn.vectory.ocdroid.data.repository.http

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §16.1(b) directory-scoping interceptor: injects `X-Opencode-Directory` for
 * the current workdir on every request that has not opted out via the
 * `X-Opencode-Skip-Dir` marker header, and strips that marker before it
 * leaves the client. Split out of the pre-R-18 combined
 * header/auth/cache interceptor so it can be unit-tested in isolation.
 *
 * Behavior (R-18 §Phase 2-E step 2):
 *
 * - Caller-supplied `X-Opencode-Directory` header is the ONLY source of the
 *   directory. The global HostConfig workdir fallback was removed; every
 *   directory-scoped endpoint now passes its directory explicitly via
 *   `@Header(HttpHeaders.DIRECTORY_HEADER)` (or `?directory` on file routes,
 *   which also carry Skip-Dir).
 * - Skip-dir marker present → strip it (caller may also have set an explicit
 *   `X-Opencode-Directory` via `@Header`; that value is left untouched),
 *   and do NOT add a directory header.
 * - No caller-supplied header → pass through unchanged (no injection). The
 *   server then falls back to its own process.cwd() — callers that need
 *   workdir-scoped routing MUST pass an explicit `directory` parameter.
 *
 * Note (R-14): this interceptor is on the shared base chain, so SSE
 * requests also flow through it. That matches the pre-R-18 behavior — SSE
 * has always carried the directory header when a workdir is set — and is
 * intentionally preserved. Do not add SSE-specific bypass logic here.
 *
 * ## ② directory query rewrite (aligned with official sdk/js `rewrite()`)
 *
 * For GET/HEAD requests carrying a directory (whether injected from the
 * workdir or supplied explicitly via `@Header`), the directory is ALSO
 * mirrored into the query string so the server can resolve it even if a
 * reverse proxy / tunnel strips custom headers (which would otherwise make
 * the server fall back to `process.cwd()` and silently route to the wrong
 * directory). The header is kept as a double-insurance; the server reads
 * `?directory` before the header (`workspace-routing.ts` / `location.ts`).
 *
 * - Non-`/api/` paths: add `?directory=<dir>` (legacy server form).
 * - `/api/` paths: additionally add `?location[directory]=<dir>` (the v2
 *   deep-object form the v2 server expects).
 * - Existing query params are never overwritten (caller-supplied wins).
 * - Only GET/HEAD: POST/PUT bodies cannot rely on query-based directory
 *   resolution, and the server does not read it for mutations.
 */
@Singleton
class DirectoryHeaderInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val skipDir = original.header(HttpHeaders.SKIP_DIR_HEADER) != null

        // §R18 Phase 2-E step 2: the directory is sourced ONLY from the
        // caller-supplied X-Opencode-Directory header. The global HostConfig
        // workdir fallback was removed; every directory-scoped endpoint now
        // passes its directory explicitly. No header → no injection (callers
        // that need workdir routing MUST pass it).
        val effectiveDir = original.header(HttpHeaders.DIRECTORY_HEADER)

        val builder = original.newBuilder()
        if (skipDir) builder.removeHeader(HttpHeaders.SKIP_DIR_HEADER)

        if (effectiveDir != null) {
            // ② mirror directory into the query for safe-for-cache GET/HEAD.
            val method = original.method
            if (method == "GET" || method == "HEAD") {
                val url = original.url
                val urlBuilder = url.newBuilder()
                var changed = false
                if (url.queryParameter(QUERY_DIRECTORY) == null) {
                    urlBuilder.addQueryParameter(QUERY_DIRECTORY, effectiveDir)
                    changed = true
                }
                if (url.encodedPath.startsWith("/api/") &&
                    url.queryParameter(QUERY_LOCATION_DIRECTORY) == null
                ) {
                    // OkHttp percent-encodes the brackets; standard URL parsers
                    // (Hono/whatwg on the server) decode them back, so the
                    // server observes `location[directory]` literally.
                    urlBuilder.addQueryParameter(QUERY_LOCATION_DIRECTORY, effectiveDir)
                    changed = true
                }
                if (changed) builder.url(urlBuilder.build())
            }
        }

        return chain.proceed(builder.build())
    }

    private companion object {
        const val QUERY_DIRECTORY = "directory"
        const val QUERY_LOCATION_DIRECTORY = "location[directory]"
    }
}
