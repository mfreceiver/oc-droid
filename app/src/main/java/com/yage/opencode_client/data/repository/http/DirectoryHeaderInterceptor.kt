package com.yage.opencode_client.data.repository.http

import com.yage.opencode_client.data.repository.HostConfig
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
 * Behavior preserved byte-for-byte from the original inline lambda:
 *
 * - Skip-dir marker present → strip it (caller may have set an explicit
 *   `X-Opencode-Directory` via `@Header`; that value is left untouched),
 *   and do NOT add a directory header from the current workdir.
 * - No marker + a workdir context is set → add
 *   `X-Opencode-Directory: <dir>` (overwriting any pre-existing value).
 * - No marker + no workdir context → pass through unchanged.
 *
 * Note (R-14): this interceptor is on the shared base chain, so SSE
 * requests also flow through it. That matches the pre-R-18 behavior — SSE
 * has always carried the directory header when a workdir is set — and is
 * intentionally preserved. Do not add SSE-specific bypass logic here.
 */
@Singleton
class DirectoryHeaderInterceptor @Inject constructor(
    private val hostConfig: HostConfig
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val skipDir = original.header(HttpHeaders.SKIP_DIR_HEADER) != null
        if (skipDir) {
            val stripped = original.newBuilder()
                .removeHeader(HttpHeaders.SKIP_DIR_HEADER)
                .build()
            return chain.proceed(stripped)
        }
        val dir = hostConfig.currentDirectory ?: return chain.proceed(original)
        val rewritten = original.newBuilder()
            .header(HttpHeaders.DIRECTORY_HEADER, dir)
            .build()
        return chain.proceed(rewritten)
    }
}
