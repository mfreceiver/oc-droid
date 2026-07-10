package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.repository.http.hostPortFromUrl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * R-18: thread-safe holder for the host connection profile currently in use
 * by [OpenCodeRepository] and the OkHttp interceptors extracted from it
 * (`DirectoryHeaderInterceptor` / `AuthInterceptor` / `CacheControlInterceptor`).
 *
 * Replaces the `@Volatile currentXxx` fields that previously lived inline on
 * the repository. Visibility / acquisition rules:
 *
 * - Single-field reads use `@Volatile` (cheap, sufficient for directory /
 *   username / password reads by interceptor threads).
 * - Multi-field updates (host switch via [configure]) are `@Synchronized` so
 *   the four interdependent fields (baseUrl / username / password /
 *   hostPort) become visible to a downstream `rebuildClients()` call as
 *   a single atomic group, mirroring the pre-R-18 `@Synchronized
 *   OpenCodeRepository.configure` contract.
 *
 * The `@Inject constructor` keeps Hilt happy if this holder is later wired
 * via DI; today it is constructed manually by [OpenCodeRepository] because
 * the repository's public constructor signature is locked by
 * `OpenCodeRepositoryTest` (`OpenCodeRepository(mockk(), mockk())`).
 *
 * §tofu R2: the boolean `allowInsecure` field was REPLACED by
 * `hostPort: String?` (host:port authority of `_baseUrl`, derived in
 * [configure]). The TOFU pin store is keyed by this authority (known_hosts
 * model), so every code path that previously read `allowInsecure` to
 * downgrade TLS now reads `hostPort` to look up a pinned SPKI instead.
 */
@Singleton
class HostConfig @Inject constructor() {

    @Volatile private var _baseUrl: String = DEFAULT_SERVER
    @Volatile private var _username: String? = null
    @Volatile private var _password: String? = null

    // §R18 Phase 2-E step 2: the deprecated mutable workdir field was removed.
    // Non-file routes (SSE / /question / /command) now receive their directory
    // EXPLICITLY via `@Header(HttpHeaders.DIRECTORY_HEADER)` on the API method,
    // and DirectoryHeaderInterceptor no longer reads from HostConfig.

    /**
     * §tofu R2: `host:port` authority of [_baseUrl] (derived once per
     * [configure] via [hostPortFromUrl]). The TOFU pin store is keyed by
     * this authority. null for non-authority URLs (e.g. content providers).
     */
    @Volatile private var _hostPort: String? = null

    val baseUrl: String get() = _baseUrl
    val username: String? get() = _username
    val password: String? get() = _password

    val hostPort: String? get() = _hostPort

    /** True iff a complete Basic Auth credential pair is configured. */
    val hasBasicAuth: Boolean get() = _username != null && _password != null

    /**
     * Atomic host switch. See class kdoc for the synchronization rationale.
     *
     * §tofu R2: takes [hostPort] (derived by the caller via [hostPortFromUrl])
     * instead of the legacy `allowInsecure` boolean — TLS trust is now
     * TOFU-pinned per host:port, not blanket-allowed per profile.
     */
    @Synchronized
    fun configure(
        baseUrl: String,
        username: String?,
        password: String?,
        hostPort: String?
    ) {
        _baseUrl = baseUrl
        _username = username
        _password = password
        _hostPort = hostPort ?: hostPortFromUrl(baseUrl)
    }

    companion object {
        /**
         * Canonical default server URL. Mirrored by
         * `OpenCodeRepository.DEFAULT_SERVER` (which is locked by
         * `OpenCodeRepositoryTest.default server URL is localhost`).
         */
        const val DEFAULT_SERVER = "http://localhost:4096"
    }
}
