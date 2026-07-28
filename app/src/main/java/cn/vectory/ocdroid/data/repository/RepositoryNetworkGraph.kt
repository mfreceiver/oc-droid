package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.repository.http.AuthInterceptor
import cn.vectory.ocdroid.data.repository.http.CacheControlInterceptor
import cn.vectory.ocdroid.data.repository.http.CachePathSanitizer
import cn.vectory.ocdroid.data.repository.http.DirectoryHeaderInterceptor
import cn.vectory.ocdroid.data.repository.http.OkHttpClientFactory
import cn.vectory.ocdroid.data.repository.http.ResponseSizeGuardInterceptor
import cn.vectory.ocdroid.data.repository.http.SlimapiDebugInterceptor
import cn.vectory.ocdroid.data.repository.http.SlimapiVersionInterceptor
import cn.vectory.ocdroid.data.repository.http.SslConfigFactory
import cn.vectory.ocdroid.data.repository.http.TofuPinStore
import cn.vectory.ocdroid.data.repository.http.TrafficCountingInterceptor
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker

/**
 * P11 §4 (Option B: repo-owned network graph): behavior-preserving wrapper
 * that consolidates the manual OkHttp / SSL / interceptor construction
 * previously inlined in [OpenCodeRepository]'s class body.
 *
 * **What lives here (T2A.2 scope — behavior-preserving)**:
 *  - The mutable [HostConfig] compatibility mirror (published bundles never
 *    use it as an interceptor dependency).
 *  - The single shared [SslConfigFactory] (mTLS cache + TOFU pin lookups via
 *    [TofuPinStore]) — held so the live clients built by [clientFactory] and
 *    the health/probe paths in [OpenCodeRepository] share ONE SSL resolver.
 *  - Shared host-independent interceptor collaborators and the graph-owned
 *    cache. Host-dependent interceptors are created per [HostSnapshot].
 *  - The [OkHttpClientFactory] itself — exposes the rest/sse/command/
 *    mutation/health/tunnel/token-stream client builders.
 *
 * **What does NOT live here yet (T2A.4+ scope)**:
 *  - [OpenCodeRepository.configure] / [OpenCodeRepository.rebuildClients]
 *    logic (still on OCR; only the field references are delegated).
 *  - Per-generation `ClientBundle` publishing is performed by the repository
 *    facade so source/readiness publication remains in its monitor.
 *
 * The ctor arity mirrors [OpenCodeRepository]'s 4 Hilt deps so a future
 * task can lift configure/checkHealth into the graph without re-plumbing
 * the ctor (and so the orphan-Hilt-leaf comment in
 * `di/ControllerModule.kt` stays accurate as "orphaned" rather than
 * "partially-bound"). [serverCompatProfile] is held but unused at this
 * step — it is consumed by the slim `/slimapi/health` probe in
 * [OpenCodeRepository.checkHealth], which T2A.4+ will migrate into the
 * graph alongside configure.
 *
 * **Not a Hilt type** — constructed manually by [OpenCodeRepository] so the
 * repo's public ctor arity (4 Hilt deps + 2-arg test default) stays frozen
 * (see `T3RepositoryExtractFreezeTest` §2). Graph-internal leaves carry
 * neither `@Inject` nor `@Singleton` (T2A.3-C1).
 */
internal class RepositoryNetworkGraph(
    trafficTracker: TrafficTracker,
    trafficLogger: TrafficLogger,
    tofuStore: TofuPinStore,
    @Suppress("UNUSED_PARAMETER")
    serverCompatProfile: ServerCompatProfile,
) {
    /** Per-host mutable compatibility mirror; clients capture snapshots. */
    val hostConfig: HostConfig = HostConfig()

    /** Default snapshot used only to construct the compatibility factory. */
    private val defaultSnapshot: HostSnapshot = hostConfig.snapshot()

    /** Cache-path sanitizer for the compatibility factory. */
    val cachePathSanitizer: CachePathSanitizer = CachePathSanitizer(defaultSnapshot)

    /** Stateless directory-header + query-rewrite interceptor. */
    val directoryHeaderInterceptor: DirectoryHeaderInterceptor = DirectoryHeaderInterceptor()

    /**
     * Slimapi version-header injector for the default compatibility factory.
     */
    val slimapiVersionInterceptor: SlimapiVersionInterceptor = SlimapiVersionInterceptor(defaultSnapshot)

    /** DEBUG-only slimapi traffic instrumentation (no-op in release). */
    val slimapiDebugInterceptor: SlimapiDebugInterceptor = SlimapiDebugInterceptor()

    /** Basic-Auth injector fed by the default snapshot. */
    val authInterceptor: AuthInterceptor = AuthInterceptor(defaultSnapshot)

    /** Cache-safety gate fed by the default snapshot + sanitizer. */
    val cacheControlInterceptor: CacheControlInterceptor =
        CacheControlInterceptor(defaultSnapshot, cachePathSanitizer)

    /** Per-byte traffic accounting (atomic per-category ledger). */
    val trafficCountingInterceptor: TrafficCountingInterceptor =
        TrafficCountingInterceptor(trafficTracker, trafficLogger)

    /** REST-only response-size guard (OOM defense). */
    val responseSizeGuardInterceptor: ResponseSizeGuardInterceptor = ResponseSizeGuardInterceptor(cap = ResponseSizeGuardInterceptor.MAX_RESPONSE_BYTES)

    /**
     * §2.4: the SINGLE shared SSL resolver/factory. Held here so the live
     * clients built by [clientFactory] and the health/probe paths on
     * [OpenCodeRepository] share the same mTLS cache + TOFU pin view.
     */
    val sslConfigFactory: SslConfigFactory = SslConfigFactory(tofuStore)

    /**
     * OkHttp client factory — composes [sslConfigFactory] + the
     * interceptors into rest / sse / command / mutation / health / tunnel /
     * token-stream clients.
     */
    val clientFactory: OkHttpClientFactory = OkHttpClientFactory(
        sslConfigFactory,
        directoryHeaderInterceptor,
        slimapiVersionInterceptor,
        slimapiDebugInterceptor,
        authInterceptor,
        cacheControlInterceptor,
        trafficCountingInterceptor,
        responseSizeGuardInterceptor,
    )

    /** Build a host-captured factory while retaining graph-owned resources. */
    internal fun clientFactoryFor(
        snapshot: HostSnapshot,
        sslConfig: cn.vectory.ocdroid.data.repository.http.SslConfig,
    ): OkHttpClientFactory = clientFactory.forSnapshot(snapshot, sslConfig)
}
