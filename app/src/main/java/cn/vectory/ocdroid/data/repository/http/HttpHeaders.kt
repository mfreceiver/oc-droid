package cn.vectory.ocdroid.data.repository.http

/**
 * R-18: shared HTTP header constants and the cache-safety whitelist used by
 * the OkHttp interceptors extracted from `OpenCodeRepository`.
 *
 * Centralising the strings here keeps the header names that the production
 * interceptors write and the unit tests assert against in lock-step, so a
 * future rename cannot silently break the contract.
 */
object HttpHeaders {

    /**
     * Header injected to scope a request to a workdir directory. Set by
     * [DirectoryHeaderInterceptor] from the current [cn.vectory.ocdroid.data.repository.HostConfig].
     */
    const val DIRECTORY_HEADER = "X-Opencode-Directory"

    /**
     * Marker header carried on Retrofit methods (via `@Headers`) to opt out
     * of automatic directory injection for global / by-id endpoints. The
     * [DirectoryHeaderInterceptor] strips it before the request leaves the
     * client so the server never observes the marker.
     */
    const val SKIP_DIR_HEADER = "X-Opencode-Skip-Dir"

    /**
     * §16.1(b) / §Stage D (gpter 阻塞 #2): relative request paths whose GET
     * responses are eligible for OkHttp caching. These 3 endpoints are
     * global / read-only / independent of user identity and workdir, so
     * caching them cannot leak data across users or directories. All other
     * endpoints (session, file, message, etc.) default to
     * `Cache-Control: no-store`.
     *
     * EXACT-MATCHED against the request path after stripping the baseUrl
     * prefix (see [CachePathSanitizer.cacheRelativePath]). Exact match — not
     * `endsWith` — so future endpoints such as `/session/{id}/agent` cannot
     * accidentally hit the whitelist. **New entries MUST be audited**: they
     * are only safe to add if the endpoint is read-only AND carries no
     * user/workdir-scoped data, AND the deployment never authenticates via
     * Basic Auth (the [CacheControlInterceptor] forces `no-store` whenever
     * credentials are configured).
     */
    val CACHEABLE_PATHS: Set<String> = setOf(
        "/global/health",
        "/agent",
        "/command",
    )
    // Note: `/config/providers` is intentionally NOT cached — its raw response
    // body carries provider API keys (`key` field) which would be written to
    // the on-device OkHttp cache. The key is dropped at deserialization (no
    // `key` field on ConfigProvider/ProviderModel + ignoreUnknownKeys), so the
    // disk-cache residue was the only leak surface; excluding it closes it.
    // §purge-linkage: if a path carrying sensitive data is ever removed from
    // this set in future, ALSO bump the marker suffix in
    // OkHttpClientFactory.applyCachePurgeIfNeeded (`okhttp-cache-purged-vN`)
    // so existing users' on-disk residue is purged once on upgrade.
}
