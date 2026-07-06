package cn.vectory.ocdroid.data.repository

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
 *   allowInsecure) become visible to a downstream `rebuildClients()` call as
 *   a single atomic group, mirroring the pre-R-18 `@Synchronized
 *   OpenCodeRepository.configure` contract.
 *
 * The `@Inject constructor` keeps Hilt happy if this holder is later wired
 * via DI; today it is constructed manually by [OpenCodeRepository] because
 * the repository's public constructor signature is locked by
 * `OpenCodeRepositoryTest` (`OpenCodeRepository(mockk(), mockk())`).
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

    @Volatile private var _allowInsecure: Boolean = false

    val baseUrl: String get() = _baseUrl
    val username: String? get() = _username
    val password: String? get() = _password

    val allowInsecure: Boolean get() = _allowInsecure

    /** True iff a complete Basic Auth credential pair is configured. */
    val hasBasicAuth: Boolean get() = _username != null && _password != null

    /**
     * Atomic host switch. See class kdoc for the synchronization rationale.
     */
    @Synchronized
    fun configure(
        baseUrl: String,
        username: String?,
        password: String?,
        allowInsecure: Boolean
    ) {
        _baseUrl = baseUrl
        _username = username
        _password = password
        _allowInsecure = allowInsecure
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
