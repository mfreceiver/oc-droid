package cn.vectory.ocdroid.data.repository.http

import cn.vectory.ocdroid.BuildConfig
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * R-18: unified factory for the four OkHttp clients used by
 * `OpenCodeRepository` (REST / SSE / tunnel / health). Owns the singleton
 * HTTP cache, wires the per-purpose interceptor chain, and routes every
 * client through the shared [SslConfigFactory] / [applySsl] entry point so
 * the trust-all decision lives in exactly one place — this is the
 * consolidation of the four previously duplicated `applySsl(sslConfigFor(...))`
 * blocks in `buildRestClient` / `buildSseClient` / `buildTunnelOkHttpClient` /
 * `checkHealthFor`.
 *
 * Client variants:
 *  - [restClient]: base chain + response-size guard (OOM P0) + 30 s read.
 *  - [sseClient]: base chain + 0 read timeout (SSE long connection).
 *  - [tunnelClient]: SSL + 10 s/10 s timeouts, no interceptors (form POST auth).
 *  - [healthClient]: SSL + 10 s/10 s timeouts, no interceptors (one-shot probe).
 *
 * The base chain (see [baseBuilder]) composes, in order:
 *  1. SSL + cache (per [allowInsecure]).
 *  2. R-07 log gate: `BASIC` in DEBUG builds, `NONE` in release.
 *  3. [DirectoryHeaderInterceptor] → [AuthInterceptor] → [CacheControlInterceptor].
 *  4. [TrafficCountingInterceptor].
 *  5. `connectTimeout(10 s)`.
 *
 * R-04: `readTimeout` and the size guard are NOT in the base chain (REST and
 * SSE differ); they are layered by each variant.
 */
@Singleton
class OkHttpClientFactory @Inject constructor(
    private val sslConfigFactory: SslConfigFactory,
    private val directoryHeaderInterceptor: DirectoryHeaderInterceptor,
    private val authInterceptor: AuthInterceptor,
    private val cacheControlInterceptor: CacheControlInterceptor,
    private val trafficCountingInterceptor: TrafficCountingInterceptor,
    private val responseSizeGuardInterceptor: ResponseSizeGuardInterceptor
) {

    /**
     * §16.1(a): OkHttp HTTP cache singleton. Lives as a `by lazy` field on
     * the factory so it survives `configure()` / host switches — OkHttp's
     * `DiskLruCache` does NOT allow two `Cache` instances on the same
     * directory (`cache is closed` crash), so we MUST NOT recreate it per
     * client build.
     *
     * Resolves the cache directory from `java.io.tmpdir` (on Android this
     * is the app-private `/data/data/<pkg>/cache` dir, identical to
     * `Context.cacheDir`). Returns null when the directory is unavailable
     * so [baseBuilder] degrades gracefully (no cache) instead of crashing —
     * preserving the spec's safety contract.
     *
     * Silent degradation: if the cache dir is unwritable or the `Cache`
     * constructor throws, the client simply runs without a cache. We do
     * NOT `android.util.Log` here to keep this unit-testable without
     * `returnDefaultValues` / mocking — the pre-existing repository had no
     * Log usage and we preserve that invariant.
     */
    private val httpCache: Cache? by lazy {
        val baseDir = System.getProperty("java.io.tmpdir")?.let(::File) ?: return@lazy null
        if (!baseDir.exists() && !baseDir.mkdirs()) return@lazy null
        runCatching { Cache(File(baseDir, "okhttp"), HTTP_CACHE_SIZE_BYTES) }
            .getOrNull()
    }

    /**
     * R-04: shared base builder — `connectTimeout`, cache, the directory /
     * auth / cache-control / traffic interceptors, and the R-07 logging
     * gate. Does NOT add the response-size cap (REST only) and does NOT
     * set `readTimeout` (REST vs SSE differ) — those are layered by each
     * variant.
     */
    private fun baseBuilder(allowInsecure: Boolean): OkHttpClient.Builder {
        return OkHttpClient.Builder()
            .apply {
                // R-01: SSL via the single shared entry point.
                applySsl(sslConfigFactory.sslConfigFor(allowInsecure))
                // §16.1(a): attach the singleton cache (if resolvable) so
                // it is reused across host switches rather than rebuilt
                // per client.
                httpCache?.let { cache(it) }
            }
            .addInterceptor(HttpLoggingInterceptor().apply {
                // R-07: release 关闭 HTTP 日志，避免 URL（含 session / 文件路径）
                // 入 logcat。仅 DEBUG 构建开 BASIC。
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                    else HttpLoggingInterceptor.Level.NONE
            })
            .addInterceptor(directoryHeaderInterceptor)
            .addInterceptor(authInterceptor)
            .addInterceptor(cacheControlInterceptor)
            .addInterceptor(trafficCountingInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
    }

    /** REST client: base chain + §OOM P0 body-size cap + 30 s read timeout. */
    fun restClient(allowInsecure: Boolean): OkHttpClient =
        baseBuilder(allowInsecure)
            .addInterceptor(responseSizeGuardInterceptor)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    /** SSE client: base chain + 0 read timeout (SSE long connection). */
    fun sseClient(allowInsecure: Boolean): OkHttpClient =
        baseBuilder(allowInsecure)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()

    /**
     * §grouping-rewrite item 4: dedicated client for `executeCommand`
     * (POST /session/{id}/command). The REST [restClient] caps read at 30 s,
     * but a slash command may force the server to do heavy synchronous work
     * BEFORE it ACKs the POST (compaction, slow skill load, ...) — a >30 s
     * wait then surfaces as [java.net.SocketTimeoutException] and gets shown
     * to the user as a false-negative `error_command_failed`, even though the
     * SSE client (read timeout 0) is still delivering the command's results
     * fine. This client bumps the read timeout to 300 s on the SAME base
     * chain (so connectTimeout 10 s + every interceptor / Basic Auth /
     * directory header / cache / traffic / logging still apply), making the
     * ACK wait effectively unbounded for the practical server-side processing
     * window. The non-fatal `SocketTimeoutException` → `UiEvent.Info` branch
     * in `AppCoreOrchestration.executeCommand` is the second line of defence
     * for the (now rare) >300 s case.
     *
     * §grouping-rewrite Round-6 F2 (kimo release review #4): the
     * [responseSizeGuardInterceptor] is now included (was previously omitted
     * because legitimate command acks are tiny JSON). Rationale: the 300 s
     * read timeout widens the window during which a compromised or
     * pathological server could stream a very large response body before the
     * read timeout fires; without the size guard, kotlinx-serialization's
     * whole-body String allocation (≈2× bytes) could OOM the 256 MB heap
     * before the timeout ever triggers. The guard's
     * [ResponseSizeGuardInterceptor.MAX_RESPONSE_BYTES] cap (32 MB) is
     * orders of magnitude above any legitimate command ack (typically < 1 KB
     * JSON), so it does not constrain real traffic — it is purely a
     * defence-in-depth bound that brings [commandClient]'s body-size
     * posture in line with [restClient]'s.
     */
    fun commandClient(allowInsecure: Boolean): OkHttpClient =
        baseBuilder(allowInsecure)
            .addInterceptor(responseSizeGuardInterceptor)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()

    /**
     * Tunnel activation client: SSL via the shared entry point + 15 s/15 s
     * timeouts, NO base interceptors — tunnel auth uses a form-encoded POST
     * (not HTTP Basic Auth), so the [AuthInterceptor] MUST NOT touch this
     * client (cf. `activateTunnel does not carry Basic Auth header`).
     */
    fun tunnelClient(allowInsecure: Boolean): OkHttpClient = bareClient(allowInsecure)

    /**
     * One-shot health-probe client: SSL via the shared entry point + 15 s /
     * 10 s timeouts, NO base interceptors — used by `checkHealthFor` to
     * probe an arbitrary baseUrl without mutating the host profile, so the
     * directory / auth / cache interceptors (which all read the live
     * `HostConfig`) would be misleading here. The probe caller supplies
     * credentials inline as needed.
     */
    fun healthClient(allowInsecure: Boolean): OkHttpClient = bareClient(allowInsecure)

    private fun bareClient(allowInsecure: Boolean): OkHttpClient =
        OkHttpClient.Builder()
            .apply { applySsl(sslConfigFactory.sslConfigFor(allowInsecure)) }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

    companion object {
        /** §16.1(a): HTTP cache size cap (50 MB) per the redesign plan. */
        private const val HTTP_CACHE_SIZE_BYTES = 50L * 1024 * 1024
    }
}
