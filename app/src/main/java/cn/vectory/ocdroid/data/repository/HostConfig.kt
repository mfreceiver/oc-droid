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

    /**
     * R8 slim-mode foundation: 当前所选 server 是否为 oc-slimapi sidecar 入口。
     * 与 [mtlsEnabled] 路径正交（mTLS 由 [OpenCodeRepository.configure] 的
     * `clientCert` 参数注入，不存于 HostConfig——因为 client cert 的存在性本
     * 身就是 mTLS 信号；slim 则是路由属性，必须由拦截器/health 探针读取，故
     * 留在 HostConfig 上）。`SlimapiVersionInterceptor` 读本字段决定是否注入
     * `X-Slimapi-Version` 头；`checkHealth` / `checkHealthFor` /
     * `captureServerCert` 读本字段决定探 `/slimapi/health` 还是 `/global/health`。
     *
     * 默认 false（legacy opencode 直连）。
     */
    @Volatile private var _slim: Boolean = false

    val baseUrl: String get() = _baseUrl
    val username: String? get() = _username
    val password: String? get() = _password

    val hostPort: String? get() = _hostPort

    /** R8 slim-mode foundation: 当前 server 的 slimapi 路由属性。 */
    val slim: Boolean get() = _slim

    /** True iff a complete Basic Auth credential pair is configured. */
    val hasBasicAuth: Boolean get() = _username != null && _password != null

    /**
     * Atomic host switch. See class kdoc for the synchronization rationale.
     *
     * §tofu R2: takes [hostPort] (derived by the caller via [hostPortFromUrl])
     * instead of the legacy `allowInsecure` boolean — TLS trust is now
     * TOFU-pinned per host:port, not blanket-allowed per profile.
     *
     * R8 slim-mode foundation: [slim] 由所选 HostProfile 透传——true 时
     * `SlimapiVersionInterceptor` 注入版本头、health 探针改走 `/slimapi/health`。
     * 默认 false 保持 legacy 行为不变。
     */
    @Synchronized
    fun configure(
        baseUrl: String,
        username: String?,
        password: String?,
        hostPort: String?,
        slim: Boolean = false
    ) {
        _baseUrl = baseUrl
        _username = username
        _password = password
        _hostPort = hostPort ?: hostPortFromUrl(baseUrl)
        _slim = slim
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
