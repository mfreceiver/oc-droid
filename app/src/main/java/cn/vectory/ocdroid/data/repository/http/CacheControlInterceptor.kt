package cn.vectory.ocdroid.data.repository.http

import cn.vectory.ocdroid.data.repository.HostConfig
import cn.vectory.ocdroid.data.repository.HostSnapshot
import okhttp3.Interceptor
import okhttp3.Response

/**
 * §16.1(b) cache-safety gate. OkHttp's cache key is `URL + method + Vary`
 * and IGNORES `Authorization` / `X-Opencode-Directory` headers, so by
 * default every request carries `Cache-Control: no-store` to forbid
 * cross-user / cross-workdir pollution. Only the whitelisted global
 * read-only endpoints (see [HttpHeaders.CACHEABLE_PATHS]) opt back into
 * caching, AND only when NO Basic Auth is in effect — [AuthInterceptor]
 * injects `Authorization: Basic ...` for any profile with credentials, and
 * since the cache key omits that header, caching under one credential
 * would leak cached responses to a different credential against the same
 * baseUrl. When auth is configured we fall back to the safe default
 * (`no-store` for everything).
 *
 * Split out of the pre-R-18 combined interceptor for testability.
 * Behavior preserved byte-for-byte.
 */
class CacheControlInterceptor internal constructor(
    private val hostSnapshot: HostSnapshot,
    private val pathSanitizer: CachePathSanitizer
) : Interceptor {

    /** Compatibility constructor: capture, never retain, the mutable holder. */
    constructor(hostConfig: HostConfig, pathSanitizer: CachePathSanitizer) :
        this(hostConfig.snapshot(), pathSanitizer)

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val cacheable = !hostSnapshot.hasBasicAuth &&
            original.method == "GET" &&
            pathSanitizer.cacheRelativePath(original.url.encodedPath) in HttpHeaders.CACHEABLE_PATHS
        if (!cacheable) {
            val rewritten = original.newBuilder()
                .header("Cache-Control", "no-store")
                .build()
            return chain.proceed(rewritten)
        }
        return chain.proceed(original)
    }
}
