package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.api.SSEClient
import cn.vectory.ocdroid.data.api.*
import cn.vectory.ocdroid.data.api.v2.OpenCodeApiV2
import cn.vectory.ocdroid.data.api.v2.ModelInfoV2
import cn.vectory.ocdroid.data.model.*
import cn.vectory.ocdroid.data.repository.http.AuthInterceptor
import cn.vectory.ocdroid.data.repository.http.CacheControlInterceptor
import cn.vectory.ocdroid.data.repository.http.CachePathSanitizer
import cn.vectory.ocdroid.data.repository.http.DirectoryHeaderInterceptor
import cn.vectory.ocdroid.data.repository.http.HttpHeaders
import cn.vectory.ocdroid.data.repository.http.ClientCertMaterial
import cn.vectory.ocdroid.data.repository.http.OkHttpClientFactory
import cn.vectory.ocdroid.data.repository.http.ResponseSizeGuardInterceptor
import cn.vectory.ocdroid.data.repository.http.SlimapiContract
import cn.vectory.ocdroid.data.repository.http.SslConfig
import cn.vectory.ocdroid.data.repository.http.SslConfigFactory
import cn.vectory.ocdroid.data.repository.http.TofuDecision
import cn.vectory.ocdroid.data.repository.http.TofuFailureReason
import cn.vectory.ocdroid.data.repository.http.TofuPinStore
import cn.vectory.ocdroid.data.repository.http.TofuValidation
import cn.vectory.ocdroid.data.repository.http.InMemoryTofuPinStore
import cn.vectory.ocdroid.data.repository.http.SlimapiVersionInterceptor
import cn.vectory.ocdroid.data.repository.http.buildMutualTlsConfig
import cn.vectory.ocdroid.data.repository.http.classifyValidation
import cn.vectory.ocdroid.data.repository.http.hostPortFromUrl
import cn.vectory.ocdroid.data.repository.http.spkiSha256Hex
import cn.vectory.ocdroid.data.repository.http.CaptureTrustManager
import cn.vectory.ocdroid.data.repository.http.TrafficCountingInterceptor
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import cn.vectory.ocdroid.util.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.Retrofit
import java.io.IOException
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/**
 * One page of cursor-paginated messages. [nextCursor] is the opaque V1 cursor
 * (`X-Next-Cursor` response header) to pass as `before` for the next older
 * page; null means no more history.
 */
data class MessagesPage(
    val items: List<MessageWithParts>,
    val nextCursor: String?
)

/**
 * §slim-v1-paging: the slimapi `/messages` and `/messages/{sid}/since/{ts}`
 * endpoints accept a server-side `?limit`. v1 ships a single bounded page
 * per fetch (no cursor follow) — this constant pins that page size in one
 * place so cold-start, resync, and the digest-triggered incremental fetch
 * all agree. The number covers the typical cases:
 *   - digest-triggered incremental tail (small — a few events),
 *   - a fresh cold-start tail for an active session.
 * Heavier history paging is deferred to v2 (cursor transparency via the
 * `X-Next-Cursor` response header + `?before=<opaque>` request query, which
 * the API surface already supports).
 */
internal const val SLIMAPI_DEFAULT_PAGE_LIMIT = 50

/**
 * OpenCode server facade. R-18 collapsed the OkHttp / SSL / interceptor
 * construction into [OkHttpClientFactory] and the per-host mutable profile
 * into [HostConfig]; this class is now a thin facade that:
 *
 *  - Wires the interceptor / factory graph in its constructor (the public
 *    constructor signature `(TrafficTracker, TrafficLogger)` is locked by
 *    `OpenCodeRepositoryTest` — `OpenCodeRepository(mockk(), mockk())`).
 *  - Holds a Retrofit [api] + [sseClient] rebuilt on every [configure].
 *  - Forwards every public suspend API 1:1 to [api] under
 *    [runSuspendCatching].
 *
 * All HTTP-level concerns (SSL trust, header injection, response-size guard,
 * traffic counting, cache, logging) live in `data/repository/http/`. The
 * public surface (every method signature below) is preserved byte-for-byte
 * from the pre-R-18 layout; no external caller needs to change.
 */
@Singleton
class OpenCodeRepository @Inject constructor(
    private val trafficTracker: TrafficTracker,
    private val trafficLogger: TrafficLogger,
    /**
     * §tofu R2: the TOFU pin store (persistent ESP-backed in production via
     * [cn.vectory.ocdroid.di.TofuModule]; [InMemoryTofuPinStore] in unit tests).
     * Held so [applyTofuDecision] can write Accept-once / Trust decisions and
     * [captureServerCert] / [checkHealthFor] can read pinned state via the
     * shared [sslConfigFactory]. Defaults to [InMemoryTofuPinStore] so the
     * test-locked 2-arg `OpenCodeRepository(mockk(), mockk())` construction
     * keeps compiling (Hilt ignores Kotlin defaults and injects the bound
     * [EspTofuPinStore][cn.vectory.ocdroid.data.repository.http.EspTofuPinStore]
     * in production).
     */
    private val tofuStore: TofuPinStore = InMemoryTofuPinStore(),
    /**
     * §slim-reconcile-lane-repo (B3 T5): the shared [ServerCompatProfile].
     * Used by [checkHealth]'s slim branch to feed
     * [ServerCompatProfile.updateSlimapi] from the parsed
     * `/slimapi/health` body — this closes the M2 self-check loop so that
     * Phase 3 bootstrap can read [ServerCompatProfile.isSlimapiClientAccepted]
     * and fail-close on version mismatch (C3 core).
     *
     * Defaults to a fresh instance so the test-locked 2-arg constructor
     * `OpenCodeRepository(mockk(), mockk())` keeps compiling; Hilt injects
     * the bound `@Singleton` instance in production so writes from this
     * repository and reads from ConnectionBootstrapEngine hit the SAME
     * profile (the M2 invariant). Same pattern as [tofuStore].
     */
    private val serverCompatProfile: ServerCompatProfile = ServerCompatProfile(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false  // Omit null fields - server rejects model: null
        encodeDefaults = true  // Include type in parts - server needs discriminator
    }

    // R-18: host profile + OkHttp composition extracted to dedicated classes.
    // Wired manually (rather than via Hilt) so the public constructor
    // signature stays `(TrafficTracker, TrafficLogger)` and the existing
    // `OpenCodeRepositoryTest` setup `OpenCodeRepository(mockk(), mockk())`
    // keeps working unchanged. The extracted components themselves are
    // `@Inject constructor @Singleton` for reuse / future DI migration.
    private val hostConfig = HostConfig()
    private val cachePathSanitizer = CachePathSanitizer(hostConfig)
    private val directoryHeaderInterceptor = DirectoryHeaderInterceptor()
    // R8 slim-mode foundation / M1: 版本头拦截器读 hostConfig.slim——必须在
    // hostConfig 之后实例化（构造顺序），但 hostConfig 的延迟初始化由 configure()
    // 完成，此处只持有引用。
    private val slimapiVersionInterceptor = SlimapiVersionInterceptor(hostConfig)
    private val authInterceptor = AuthInterceptor(hostConfig)
    private val cacheControlInterceptor = CacheControlInterceptor(hostConfig, cachePathSanitizer)
    private val trafficCountingInterceptor = TrafficCountingInterceptor(trafficTracker, trafficLogger)
    private val responseSizeGuardInterceptor = ResponseSizeGuardInterceptor()
    // §2.4: 持有同一个 [SslConfigFactory] 实例——既给 clientFactory 构建 OkHttp client
    // （live REST/SSE/command/tunnel），也供本类的 [configure]/[checkHealthFor]/
    // [currentSslConfig] 解析 mTLS / TOFU pin。原先 inline `OkHttpClientFactory(
    // SslConfigFactory(), ...)` 每次构造一个独立 factory，configure 时注入的客户端
    // 证书无法被 healthClient 旧重载读到，也无法集中观测
    // [SslConfigFactory.lastClientCertError]。
    // §tofu R2: SslConfigFactory 现在需要 [TofuPinStore]——传入构造函数注入的 store，
    // 使生产 (ESP) / 测试 (InMemory) 都走同一 graph。
    private val sslConfigFactory = SslConfigFactory(tofuStore)
    private val clientFactory = OkHttpClientFactory(
        sslConfigFactory,
        directoryHeaderInterceptor,
        slimapiVersionInterceptor,
        authInterceptor,
        cacheControlInterceptor,
        trafficCountingInterceptor,
        responseSizeGuardInterceptor
    )

    private var restHttp: OkHttpClient = clientFactory.restClient(hostConfig.hostPort)
    private var sseHttp: OkHttpClient = clientFactory.sseClient(hostConfig.hostPort)
    private var retrofit: Retrofit = buildRetrofit(restHttp, hostConfig.baseUrl)
    private var api: OpenCodeApi = retrofit.create(OpenCodeApi::class.java)
    private var sseClient: SSEClient = SSEClient(sseHttp)

    /**
     * §grouping-rewrite item 4: dedicated OkHttp client + Retrofit instance
     * for `executeCommand` (POST /session/{id}/command). Built on
     * [OkHttpClientFactory.commandClient] (read timeout 300 s vs [restHttp]'s
     * 30 s) so a slow synchronous server-side command step does not blow past
     * the read timeout and surface as a false-negative command-failed error
     * (SSE still carries the results). The [commandApi] reuses the SAME
     * [OpenCodeApi] interface, baseUrl, JSON converter and interceptor chain
     * (auth / directory / cache / traffic) as [api]; only the OkHttp client
     * differs. All other API methods stay on [api].
     */
    private var commandHttp: OkHttpClient = clientFactory.commandClient(hostConfig.hostPort)
    private var commandRetrofit: Retrofit = buildRetrofit(commandHttp, hostConfig.baseUrl)
    private var commandApi: OpenCodeApi = commandRetrofit.create(OpenCodeApi::class.java)

    /**
     * §model-selection: a SECOND Retrofit instance rooted at
     * `<baseUrl>/api/` for the v2 model list endpoint (GET /api/model). Built
     * with the SAME OkHttp [restHttp] client as [api] so auth / cache / traffic
     * interceptors apply uniformly. Lives on its own interface ([OpenCodeApiV2])
     * and its own Retrofit root so the legacy message path ([api].getMessages /
     * promptAsync) is untouched. (The previous `POST /api/session/{id}/model`
     * switch endpoint was removed to align with the official packages/app
     * V1-per-prompt model — the model is now attached per-prompt via
     * PromptRequest.model, not switched server-side per session.)
     */
    private var v2Retrofit: Retrofit = buildV2Retrofit(restHttp, hostConfig.baseUrl)
    private var apiV2: OpenCodeApiV2 = v2Retrofit.create(OpenCodeApiV2::class.java)

    /**
     * Cluster A (slim SSE reducer/state): per-session bookmark accumulator
     * for `session.digest` frames + the `/since/{ts}` anchor (§5 A2=A).
     * Held as private state so the reducer [reduceSlimDigest] is pure (state
     * in → state out + decision). Cleared by [configure] on a host switch
     * (the bookmarks belong to the previous server).
     */
    private val slimSseState = SlimSseState()

    /**
     * §slim-reconcile-lane-repo (B2 T1): the live host's slim-mode flag.
     * True when the current [HostConfig] points at an oc-slimapi sidecar
     * entry (vs legacy opencode direct). Read by the in-repo `if(slim)`
     * branches ([getSessions] / [getSessionsForDirectory] /
     * [getMessagesPaged] / [getPendingPermissions]) to route REST calls to
     * the sidecar's `slimapi` endpoint family; Phase 2's
     * SessionSyncCoordinator reads this to decide between the slim SSE
     * digest loop vs the legacy polling pattern.
     *
     * Returns the live [HostConfig.slim] value (volatile read), so it
     * reflects the most recent [configure] call.
     */
    val isSlimMode: Boolean get() = hostConfig.slim

    private fun buildRetrofit(client: OkHttpClient, baseUrl: String): Retrofit {
        val url = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
        return Retrofit.Builder()
            .baseUrl(url.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    /**
     * §model-selection: builds the v2 Retrofit rooted at `<baseUrl>/api/`.
     * Uses the SAME converter factory (json with ignoreUnknownKeys=true) so
     * the `location` echo on GET /api/model is dropped silently, and the same
     * OkHttp client as [buildRetrofit] so the auth / cache / traffic
     * interceptors apply.
     */
    private fun buildV2Retrofit(client: OkHttpClient, baseUrl: String): Retrofit {
        val url = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
        return Retrofit.Builder()
            .baseUrl(url.trimEnd('/') + "/api/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Synchronized
    private fun rebuildClients() {
        restHttp = clientFactory.restClient(hostConfig.hostPort)
        sseHttp = clientFactory.sseClient(hostConfig.hostPort)
        retrofit = buildRetrofit(restHttp, hostConfig.baseUrl)
        api = retrofit.create(OpenCodeApi::class.java)
        // §grouping-rewrite item 4: rebuild the command-side client + Retrofit
        // so a host switch (configure) refreshes baseUrl / auth on it too.
        commandHttp = clientFactory.commandClient(hostConfig.hostPort)
        commandRetrofit = buildRetrofit(commandHttp, hostConfig.baseUrl)
        commandApi = commandRetrofit.create(OpenCodeApi::class.java)
        v2Retrofit = buildV2Retrofit(restHttp, hostConfig.baseUrl)
        apiV2 = v2Retrofit.create(OpenCodeApiV2::class.java)
        sseClient = SSEClient(sseHttp)
    }

    /**
     * §tofu R2: configure the live host. [hostPort] (the host:port authority
     * of [baseUrl]) replaces the legacy `allowInsecureConnections: Boolean`;
     * it keys the TOFU pin lookup so a previously-trusted endpoint's
     * TofuPinned config is applied to the rebuilt REST/SSE/command clients.
     * The caller may derive it via [hostPortFromUrl] (a null [hostPort] is
     * also resolved from [baseUrl] inside [HostConfig.configure]).
     *
     * §2.4: [clientCert] is the optional mTLS client certificate material
     * (PKCS12 + password + optional private CA). Loaded by the caller from
     * EncryptedSharedPreferences via
     * [cn.vectory.ocdroid.util.SettingsManager.loadClientCertMaterial] when
     * the active profile has `mtlsEnabled=true`. Default null → no client
     * cert (preserves source compatibility for pre-mTLS callers).
     * `configureClientCert(null)` clears any previously-held material so
     * switching from an mTLS profile to a plain profile stops presenting
     * the cert (no residue). MUST run before [rebuildClients] so the rebuilt
     * OkHttp clients pick up the new SSL config.
     */
    @Synchronized
    fun configure(
        baseUrl: String,
        username: String? = null,
        password: String? = null,
        hostPort: String? = null,
        clientCert: ClientCertMaterial? = null,
        /**
         * R8 slim-mode foundation: 当前 profile 是否启用省流模式（指向 oc-slimapi
         * sidecar）。透传给 [hostConfig.slim]，由 [SlimapiVersionInterceptor] 与
         * [checkHealth] / [checkHealthFor] / [captureServerCert] 路由使用。
         *
         * 默认 false（legacy 直连 opencode）——保持现有调用方（ui / service）行为
         * 完全不变；待 EffectiveConnectionConfig / 上游 controller 接入 slim 字段后
         * 端到端生效。
         */
        slim: Boolean = false
    ) {
        // §2.4: MUST precede hostConfig.configure / rebuildClients so the
        // shared sslConfigFactory holds the new mTLS material when the live
        // REST/SSE/command clients are rebuilt (they read sslConfigFor(...)).
        sslConfigFactory.configureClientCert(clientCert)
        hostConfig.configure(baseUrl, username, password, hostPort, slim = slim)
        // Cluster A: bookmarks belong to the previous server; a host switch
        // MUST reset them so a stale updatedAt doesn't skip boundary messages
        // on the new server.
        slimSseState.clear()
        rebuildClients()
    }

    /**
     * §2.4: the current effective [SslConfig] for the live host (mTLS priority
     * over TOFU pin, SystemDefault safe fallback). Callers
     * ([HttpImageHolder] / cold-start image sync) use this to mirror the same
     * trust policy onto the markdown image client. `@Synchronized` because it
     * reads the mutable [sslConfigFactory] state that [configure] writes under
     * the same monitor (v3-glmer R2).
     *
     * §tofu R2: resolves via [SslConfigFactory.sslConfigFor] keyed by the
     * current [HostConfig.hostPort] (was `allowInsecure`).
     */
    @Synchronized
    fun currentSslConfig(): SslConfig = sslConfigFactory.sslConfigFor(hostConfig.hostPort)

    /**
     * §tofu fix: 当前 live SSL 配置是否走 mTLS 路径（客户端证书已配置并加载）。
     * TOFU 触发须跳过 mTLS 主机——mTLS 优先级会忽略 TOFU pin，弹"信任"是无效的
     * 误导；mTLS 服务器证书失败应直接作连接错误呈现。镜像 [sslConfigFor] 的
     * mTLS-priority 路由（SslConfig.kt sslConfigFor: mutualTlsConfig != null → MutualTLS）。
     */
    fun isMutualTlsActive(): Boolean = currentSslConfig() is SslConfig.MutualTLS

    /**
     * §fix-3 (gro-1#2/gpt-2#2/max-1 M1): 转发 [SslConfigFactory.lastClientCertError]。
     * 非空 = 最近一次 [configure] 注入的客户端证书材料试构建失败（p12 损坏 / CA 无法
     * 解析）→ mTLS 已降级回 SystemDefault，profile 仍宣称 mtlsEnabled。controller/UI
     * 据此显示「证书加载失败」而非泛化连接失败（防 fail-open 静默降级）。null = ok 或
     * 未配置 mTLS。
     */
    val lastClientCertError: String? get() = sslConfigFactory.lastClientCertError

    // ── §tofu R2: capture probe + decision application ──────────────────────

    /**
     * §tofu R2: a captured leaf cert + its SPKI + the system-validation
     * classification. Surfaced to the UI as the trust-prompt payload; the UI's
     * [TofuDecision] is fed back via [applyTofuDecision].
     */
    data class TofuCaptureResult(
        val hostPort: String,
        val leaf: X509Certificate,
        val spkiHex: String,
        val validation: TofuValidation
    )

    /**
     * §tofu R2: one-shot TLS handshake probe that RECORDS the server's leaf
     * cert chain for the trust prompt. Used by the connection coordinator
     * when [checkHealth] / [checkHealthFor] fails with an SSL/cert error and
     * no pin yet exists for [hostPort] — i.e. the user has never trusted this
     * endpoint.
     *
     * Builds its OWN one-shot OkHttpClient (does NOT touch the live mTLS
     * cache nor the held client-cert state — mirrors [SslConfigFactory.resolveProbe]'s
     * non-polluting contract): a [CaptureTrustManager] records the chain, the
     * optional [clientCert] is presented via a fresh KeyManager (so an mTLS
     * server that requires client auth still completes the handshake far
     * enough to present its chain), and a permissive hostnameVerifier lets
     * the handshake complete for self-signed certs whose SAN doesn't match.
     *
     * Returns null on total failure (unreachable host, no cert presented,
     * UI-thread cancellation) — the coordinator surfaces the original
     * SSLHandshakeException in that case.
     */
    suspend fun captureServerCert(
        baseUrl: String,
        hostPort: String,
        clientCert: ClientCertMaterial? = null,
        /**
         * R8 slim-mode foundation / C2 fix: slim=true 时探 `{baseUrl}/slimapi/health`
         * （带 `X-Slimapi-Version` 头）；slim=false（默认）保持 legacy
         * `/global/health`。captureTrustManager 仅记录 TLS 握手 chain——path
         * 选择不影响捕获的 leaf，但路径正确性确保 sidecar 自身（而非经
         * catch-all 透传到的 opencode）的 leaf 被记录。
         */
        slim: Boolean = false
    ): TofuCaptureResult? = withContext(Dispatchers.IO) {
        val capture = CaptureTrustManager()
        // Build the one-shot SSLContext: CaptureTrustManager (records) +
        // optional KeyManagers from the supplied p12 (mTLS client auth).
        val keyManagers = clientCert?.let { material ->
            runCatching {
                val p12 = KeyStore.getInstance("PKCS12").apply {
                    load(ByteArrayInputStream(material.p12Bytes), material.p12Password)
                }
                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                    .apply { init(p12, material.p12Password) }
                kmf.keyManagers
            }.getOrNull()
        }
        val ctx = SSLContext.getInstance("TLS").apply {
            init(keyManagers, arrayOf<TrustManager>(capture), SecureRandom())
        }
        val oneShot = OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, capture)
            // §tofu R2: pin即身份 — capture 阶段放行 hostnameVerifier，使自签证书
            // （SAN 常不匹配）也能完成握手并暴露 leaf。安全由随后 SPKI pin 保证。
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
        // R8 slim-mode foundation / C2 fix: slim=true → /slimapi/health（带版本头）;
        // slim=false → /global/health（行为字节级不变）。capture 不在乎 path 的 HTTP
        // 语义——它只想要 leaf；但 path 正确性保证 leaf 是 sidecar 自己的，而非经
        // catch-all 反代暴露的 upstream。
        val healthPath = if (slim) SlimapiContract.SLIMAPI_HEALTH_PATH
            else SlimapiContract.LEGACY_HEALTH_PATH
        val normalizedUrl = (if (baseUrl.startsWith("http")) baseUrl else "https://$baseUrl")
            .trimEnd('/') + healthPath
        val requestBuilder = Request.Builder()
            .url(normalizedUrl)
            .header(HttpHeaders.SKIP_DIR_HEADER, "1")
        if (slim) {
            requestBuilder.header(
                SlimapiContract.X_SLIMAPI_VERSION,
                SlimapiContract.SLIMAPI_CLIENT_VERSION.toString()
            )
        }
        // Surface the leaf + classification regardless of whether the GET
        // itself succeeded — a 4xx/5xx after a completed handshake STILL
        // captured the chain (the handshake happens before any HTTP
        // exchange). Only total handshake/connection failures return null.
        runCatching {
            oneShot.newCall(requestBuilder.build()).execute().use { /* drain */ }
        }
        val chain = capture.capturedChain
        if (chain.isNullOrEmpty()) return@withContext null
        val leaf = chain.first()
        val spki = leaf.spkiSha256Hex()
        val host = hostPort.substringBefore(':')
        val validation = classifyValidation(chain, host)
        TofuCaptureResult(hostPort = hostPort, leaf = leaf, spkiHex = spki, validation = validation)
    }

    /**
     * §tofu R2: applies the UI's [TofuDecision] for [hostPort] to the injected
     * [TofuPinStore]. After [TofuDecision.AcceptOnce] / [TofuDecision.Trust]
     * the next [checkHealth] resolves a TofuPinned SSL config and the
     * handshake succeeds; [TofuDecision.Cancel] writes nothing (the user
     * declined — the in-flight connect is settled false by the coordinator).
     *
     * The decision is keyed by [hostPort] so two profiles reaching the same
     * endpoint share the trust state (known_hosts model — grill Q4=a).
     */
    @Synchronized
    fun applyTofuDecision(hostPort: String, decision: TofuDecision) {
        when (decision) {
            is TofuDecision.AcceptOnce -> tofuStore.acceptSession(hostPort, decision.spki)
            is TofuDecision.Trust -> tofuStore.trustPersistent(hostPort, decision.spki)
            TofuDecision.Cancel -> { /* no-op — user declined */ }
        }
        // §tofu R2 round-1 fix (cgpt/opuser/groker 一致 blocker): live OkHttp 客户端在
        // configure()/rebuildClients() 时按【决策前】的 SSL 配置(SystemDefault)快照构建，
        // socket factory 构建后不可变——只写 pin 不重建则重试仍走 SystemDefault 握手失败，
        // 而此时 pin 已存在→不再弹窗→重试耗尽→Disconnected（Accept/Trust 成死路）。故对
        // 当前后 host 重建客户端，使 sslConfigFor(hostPort) 重新解析为 TofuPinned 再重试。
        if (decision !is TofuDecision.Cancel && hostConfig.hostPort == hostPort) {
            rebuildClients()
        }
    }

    /**
     * §tofu R2: query the current pinned SPKI for [hostPort] (persistent OR
     * session tier). Used by the coordinator's "should I prompt?" guard so
     * we never re-prompt an endpoint the user has already trusted.
     */
    fun pinnedSpkiFor(hostPort: String): String? = tofuStore.pinnedSpki(hostPort)

    /**
     * §tofu R2: forget the pin for [hostPort] (both tiers). Re-prompt is
     * forced on the next connect. Used by the host management UI's "forget
     * trust" affordance.
     */
    fun clearTofuPin(hostPort: String) = tofuStore.clear(hostPort)

    // §R18 Phase 2-E step 2: the deprecated setCurrentDirectory /
    // getCurrentDirectory forwarding helpers were removed. Non-file routes
    // (SSE / /question / /command) now take an explicit `directory` parameter
    // on the API method; DirectoryHeaderInterceptor no longer reads from
    // HostConfig. File routes already took explicit parameters (R-17 batch4).

    /**
     * R8 slim-mode foundation / C3 fix: health probe against the **current**
     * configured host. Routes by [HostConfig.slim]:
     *
     *  - slim=false（legacy）: 走 `GET /global/health` via Retrofit [api]
     *    ——行为与新增本字段前**完全一致**（无路径变化 / 无额外请求头）。
     *  - slim=true: 走裸 OkHttp `GET /slimapi/health`，带
     *    `X-Slimapi-Version` 头（M1 门闩，所有 `/slimapi/` 下路径必带——含
     *    health 自身；design-v2 §9.6）。**不**走 `/global/health`——后者经
     *    slimapi catch-all 透传到 opencode，sidecar 挂时仍 200 误报健康（C3
     *    核心）。响应由 [parseSlimapiHealth] 适配为 [HealthResponse]：
     *    `healthy = sidecar.ok == true && accepted_client_versions 含
     *    SLIMAPI_CLIENT_VERSION`。
     *
     * **M2 自检的衔接**：本方法返回 [HealthResponse] 形状，但 slimapi sidecar
     * 的版本契约（api_version / accepted_client_versions / schema_degraded）
     * 不在 HealthResponse 里——调用方（ConnectionBootstrapEngine / 上层
     * controller）需要 M2 自检时，应调 [parseSlimapiHealth] 直接从 body 抽取
     * [SlimapiHealthPayload] 并喂 [ServerCompatProfile.updateSlimapi]。本方法
     * 内部已解析，但只通过返回的 [HealthResponse] 暴露 healthy/version 语义；
     * 完整 payload 走 [parseSlimapiHealth] 公开函数。
     *
     * **设计权衡**：不把 [ServerCompatProfile] 注入 [OpenCodeRepository]，
     * 保持 facade 单职责（只负责 HTTP），bootstrap engine 继续是版本契约
     * 的单一更新源——这与现有 `serverCompatProfile.update(health.version)`
     * 调用点对称。
     *
     * 注：KDoc 中避免把 `/slimapi/` 与紧跟着的 `星号星号` 连写（Kotlin lexer
     * 把斜杠+星号星号 当嵌套 KDoc 起始）；下文用 `/slimapi/health` 单独写。
     * M1 门闩对所有 `/slimapi/` 下路径生效，含 health。
     */
    suspend fun checkHealth(): Result<HealthResponse> = runSuspendCatching {
        if (!hostConfig.slim) {
            // legacy：行为字节级不变。
            api.getHealth()
        } else {
            // C3 fix：探 sidecar 自身 health，不经 catch-all 透传。
            probeSlimapiHealth(hostConfig.baseUrl, hostConfig.username, hostConfig.password)
        }
    }

    /**
     * R8 slim-mode foundation / C3 fix（共用实现）：裸 OkHttp `GET {baseUrl}/slimapi/health`
     * 带 `X-Slimapi-Version` 头，把 sidecar 响应适配为 [HealthResponse]。
     *
     * - 用 [clientFactory.healthClient]（无 base 链拦截器——避免 Directory / Auth /
     *   Cache-Control 干扰一次性探针；版本头显式注入，因为 healthClient 不挂
     *   [SlimapiVersionInterceptor]）。
     * - Basic Auth 同步注入（与 [checkHealthFor] 一致语义）。
     * - 解析失败 / sidecar.ok == false / 版本不兼容 → 抛错（`Result.failure`），
     *   不静默报健康——C3 的核心保证。
     */
    private suspend fun probeSlimapiHealth(
        baseUrl: String,
        username: String?,
        password: String?
    ): HealthResponse = withContext(Dispatchers.IO) {
        val resolvedHostPort = hostPortFromUrl(baseUrl)
        val cfg = sslConfigFactory.sslConfigFor(resolvedHostPort)
        val client = clientFactory.healthClient(cfg)
        val normalizedUrl = (if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl")
            .trimEnd('/') + SlimapiContract.SLIMAPI_HEALTH_PATH
        val requestBuilder = Request.Builder()
            .url(normalizedUrl)
            .header(HttpHeaders.SKIP_DIR_HEADER, "1")
            .header(SlimapiContract.X_SLIMAPI_VERSION, SlimapiContract.SLIMAPI_CLIENT_VERSION.toString())
        if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
            val credential = "$username:$password"
            val encoded = Base64.getEncoder().encodeToString(credential.toByteArray())
            requestBuilder.header("Authorization", "Basic $encoded")
        }
        client.newCall(requestBuilder.build()).execute().use { res ->
            if (!res.isSuccessful) error("HTTP ${res.code}")
            val body = res.body?.string().orEmpty()
            if (body.isBlank()) error("Empty response body")
            val payload = parseSlimapiHealth(body)
            // §slim-reconcile-lane-repo (B3 T5): feed the M2 self-check loop —
            // the parsed slimapi version contract MUST land in the shared
            // [ServerCompatProfile] so Phase 3's bootstrap can read
            // [ServerCompatProfile.isSlimapiClientAccepted] and fail-close on
            // version mismatch (C3 core). Without this write, the sidecar's
            // `accepted_client_versions` is parsed but discarded and the
            // fail-closed gate never sees the bounds → either always-rejects
            // (if min/max stay null) or never-rejects (silent).
            serverCompatProfile.updateSlimapi(payload)
            // 适配为 HealthResponse：healthy = sidecar.ok && 版本兼容。
            // version 字段对 slimapi 模式无直接对应（opencode semver 由独立路径
            // 探得），用合成标记让上层 UI 可观测（"slimapi/api_version=<n>"）。
            val healthy = payload.sidecarOk == true &&
                payload.serverApiVersion != null &&
                SlimapiContract.SLIMAPI_CLIENT_VERSION in
                (payload.acceptedClientVersions?.first ?: Int.MIN_VALUE)..(payload.acceptedClientVersions?.second ?: Int.MIN_VALUE)
            HealthResponse(
                healthy = healthy,
                version = payload.serverApiVersion?.let { "slimapi/api_version=$it" }
            )
        }
    }

    /**
     * R8 slim-mode foundation / M2 自检：从 `GET /slimapi/health` 响应 body 抽取
     * 版本契约业务字段，返回 [SlimapiHealthPayload] 供 [ServerCompatProfile.updateSlimapi]
     * 落库。容错：缺字段 / 类型不符 → 对应字段为 null（[ServerCompatProfile.isSlimapiClientAccepted]
     * 在 null 时 fail-closed）。
     *
     * 形状参考 docs/slim-mode-api-routing.md §3.2：
     * ```json
     * { "sidecar": {"ok": true, "version": "0.1.0"},
     *   "schema":   {"degraded": false},
     *   "server":   {"api_version": 1, "accepted_client_versions": [1, 1]} }
     * ```
     *
     * 接受 [body] 字符串（来自 OkHttp `Response.body.string()`）；不可识别的
     * JSON 结构 → 各字段 null（容错，不抛——把决策交给上层 fail-closed）。
     */
    fun parseSlimapiHealth(body: String): SlimapiHealthPayload {
        val root = runCatching { json.decodeFromString<JsonObject>(body) }.getOrNull()
            ?: return SlimapiHealthPayload(null, null, null, null)
        val sidecar = root["sidecar"]?.safeObject()
        val schema = root["schema"]?.safeObject()
        val server = root["server"]?.safeObject()
        val sidecarOk = sidecar?.get("ok")?.safePrimitive()?.let { it.content.equals("true", ignoreCase = true) }
        val schemaDegraded = schema?.get("degraded")?.safePrimitive()?.let { it.content.equals("true", ignoreCase = true) }
        val apiVersion = server?.get("api_version")?.safePrimitive()?.intOrNull
        val accepted = server?.get("accepted_client_versions")?.safeArray()
            ?.mapNotNull { it.safePrimitive()?.intOrNull }
            ?.takeIf { it.size >= 2 }
            ?.let { Pair(it[0], it[1]) }
        return SlimapiHealthPayload(
            sidecarOk = sidecarOk,
            schemaDegraded = schemaDegraded,
            serverApiVersion = apiVersion,
            acceptedClientVersions = accepted
        )
    }

    /**
     * One-shot health probe against [baseUrl] with optional Basic Auth, WITHOUT
     * mutating this repository's current configuration. Used by the host list's
     * per-row "test" action so a profile can be probed without switching hosts.
     *
     * §tofu R2: [hostPort] (host:port authority of [baseUrl]) replaces the
     * legacy `allowInsecure: Boolean`. It keys the TOFU pin lookup so a
     * previously-trusted endpoint's pin is honored during the probe.
     *
     * Builds a throwaway OkHttp client via [OkHttpClientFactory.healthClient]
     * (the SSL-trust shared entry point) and parses the same [HealthResponse]
     * shape served by `GET /global/health`.
     *
     * R8 slim-mode foundation / C3 fix: [slim] = true 时探
     * `{baseUrl}/slimapi/health`（带 `X-Slimapi-Version` 头）；`slim = false`
     * （默认）保持 legacy `{baseUrl}/global/health`。**核心**：slim 模式下
     * 必须探 sidecar 自身健康（`/slimapi/health` 的 `sidecar.ok` + 版本契约），
     * **不能**探 `/global/health`——后者经 catch-all 透传到 opencode，sidecar
     * 挂时仍 200 误报健康（C3 反例）。默认 false 保持所有现有调用方字节不变。
     */
    suspend fun checkHealthFor(
        baseUrl: String,
        username: String? = null,
        password: String? = null,
        hostPort: String? = null,
        clientCert: ClientCertMaterial? = null,
        slim: Boolean = false
    ): Result<HealthResponse> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            // v3-gpter R2#1 阻断修复：用 [SslConfigFactory.resolveProbe] 纯参数解析
            // （hostPort + clientCert），**禁止**用 sslConfigFor——后者会读 held
            // mTLS 状态，于是测他 profile（clientCert=null）时会复用当前 mTLS profile
            // 的缓存，误出示其客户端证书 / 只信其私有 CA，甚至泄漏客户端身份给无关 host。
            // §tofu R2: hostPort 替代 allowInsecure，探测也走 TOFU pin 查询。
            val resolvedHostPort = hostPort ?: hostPortFromUrl(baseUrl)
            val cfg: SslConfig = sslConfigFactory.resolveProbe(resolvedHostPort, clientCert)
            val client = clientFactory.healthClient(cfg)
            // R8 slim-mode foundation / C3: slim=true → /slimapi/health（带版本头）;
            // slim=false → /global/health（行为字节级不变）。
            val healthPath = if (slim) SlimapiContract.SLIMAPI_HEALTH_PATH
                else SlimapiContract.LEGACY_HEALTH_PATH
            val normalizedUrl = (if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl")
                .trimEnd('/') + healthPath
            val requestBuilder = Request.Builder()
                .url(normalizedUrl)
                .header(HttpHeaders.SKIP_DIR_HEADER, "1")
            // M1: slimapi 模式下版本头对所有 /slimapi/ 路径必带——含 health 自身。
            if (slim) {
                requestBuilder.header(
                    SlimapiContract.X_SLIMAPI_VERSION,
                    SlimapiContract.SLIMAPI_CLIENT_VERSION.toString()
                )
            }
            if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
                val credential = "$username:$password"
                val encoded = Base64.getEncoder().encodeToString(credential.toByteArray())
                requestBuilder.header("Authorization", "Basic $encoded")
            }
            client.newCall(requestBuilder.build()).execute().use { res ->
                if (!res.isSuccessful) error("HTTP ${res.code}")
                val body = res.body?.string().orEmpty()
                if (body.isBlank()) error("Empty response body")
                if (slim) {
                    // C3 fix: sidecar 自身 health 适配为 HealthResponse——
                    // healthy = sidecar.ok && 版本兼容；不兼容/缺字段 → 抛错
                    // （fail-closed，绝不静默报健康）。
                    val payload = parseSlimapiHealth(body)
                    // §slim-reconcile-lane-repo (Phase 3a / Lane-B3-Dialog):
                    // 镜像 [probeSlimapiHealth] 的 T5 模式——把解析后的 slimapi
                    // 版本契约喂 [ServerCompatProfile]，这样 test-connection（one-shot
                    // 探针，per-profile）也能落库版本契约字段，让上层（ConnectionViewModel
                    // / Phase 3 bootstrap）读到 [ServerCompatProfile.isSlimapiClientAccepted]
                    // 并 fail-close / 弹阻塞 dialog。不加这一行，test-connection 链根本
                    // 看不到 sidecar 的 `accepted_client_versions`，dialog 永不弹（C3 反例）。
                    serverCompatProfile.updateSlimapi(payload)
                    val accepted = payload.acceptedClientVersions != null &&
                        SlimapiContract.SLIMAPI_CLIENT_VERSION in
                        payload.acceptedClientVersions!!.first..payload.acceptedClientVersions!!.second
                    val healthy = payload.sidecarOk == true && accepted
                    if (!healthy) error("slimapi sidecar unhealthy or client version incompatible")
                    HealthResponse(
                        healthy = true,
                        version = payload.serverApiVersion?.let { "slimapi/api_version=$it" }
                    )
                } else {
                    json.decodeFromString(HealthResponse.serializer(), body)
                }
            }
        }
    }

    /**
     * §slim-reconcile-lane-repo (B2 T2): in slim mode, route to the sidecar's
     * `/slimapi/sessions` (skeleton list, each row carries its own
     * `directory` so the caller filters client-side). The slimapi DTO IS the
     * legacy [Session] shape (no separate SlimapiSession model), so the
     * adapter is identity — preserved here for symmetry with
     * [getSessionsForDirectory] / [getPendingPermissions].
     *
     * legacy (`isSlimMode == false`): byte-for-byte unchanged.
     */
    suspend fun getSessions(limit: Int? = null): Result<List<Session>> = runSuspendCatching {
        if (isSlimMode) {
            api.getSlimapiSessions(directories = null, roots = null, limit = limit)
        } else {
            api.getSessions(limit)
        }
    }

    /**
     * Fetches the root sessions whose [Session.directory] exactly matches
     * [directory]. Uses the server's `?directory` + `?roots` query params
     * (priority: query param > header > cwd, per lib-1) so the result is
     * scoped to this workdir and excludes sub-agent (child) sessions.
     *
     * §slim-reconcile-lane-repo (B2 T2): in slim mode, the same semantics
     * are achieved by passing `directories=listOf(directory)` + `roots=true`
     * — Retrofit expands the list into repeated `?directory=...` query
     * params (contract: repeated, NOT comma-joined). The sidecar filters
     * server-side identically to the legacy opencode route.
     */
    suspend fun getSessionsForDirectory(directory: String, limit: Int? = null): Result<List<Session>> =
        runSuspendCatching {
            if (isSlimMode) {
                api.getSlimapiSessions(
                    directories = listOf(directory),
                    roots = true,
                    limit = limit,
                )
            } else {
                api.getSessions(limit = limit, directory = directory, roots = true)
            }
        }

    /**
     * Fetches a single session by ID. Used to resolve a child/sub-agent session
     * that may not be present in the cached [getSessions] list.
     */
    suspend fun getSession(sessionId: String): Result<Session> = runSuspendCatching { api.getSession(sessionId) }

    // §R18 Final 终审 fix (gpter): directory now explicit (was relying on the
    // removed global currentDirectory fallback). Callers pass currentWorkdir /
    // draftWorkdir so POST /session routes to the correct workdir instance.
    suspend fun createSession(title: String? = null, directory: String? = null): Result<Session> = runSuspendCatching {
        api.createSession(CreateSessionRequest(title = title), directory)
    }

    suspend fun updateSession(sessionId: String, title: String): Result<Session> = runSuspendCatching {
        api.updateSession(sessionId, UpdateSessionRequest(title = title))
    }

    suspend fun updateSessionArchived(sessionId: String, archived: Long): Result<Session> = runSuspendCatching {
        api.updateSession(sessionId, UpdateSessionRequest(time = UpdateSessionTimeRequest(archived = archived)))
    }

    suspend fun deleteSession(sessionId: String): Result<Unit> = runSuspendCatching {
        api.deleteSession(sessionId)
    }

    suspend fun getSessionStatus(): Result<Map<String, SessionStatus>> = runSuspendCatching {
        api.getSessionStatus()
    }

    suspend fun getActiveSessionIds(): Result<Set<String>> = runSuspendCatching {
        api.getActiveSessions().data.keys
    }

    /**
     * Fetches the child (sub-agent) sessions spawned by [sessionId], typically
     * via the `task` tool.
     */
    suspend fun getChildren(sessionId: String): Result<List<Session>> = runSuspendCatching {
        api.getChildren(sessionId)
    }

    suspend fun getMessages(sessionId: String, limit: Int? = null): Result<List<MessageWithParts>> =
        runSuspendCatching {
            val response = api.getMessages(sessionId, limit, before = null)
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code()}")
            response.body() ?: emptyList()
        }

    /**
     * Cursor-paged message fetch (V1 route: cursor carried via the
     * `X-Next-Cursor` response header + the `before` query param).
     *
     * §slim-reconcile-lane-repo (B2 T3): in slim mode, route to the sidecar's
     * `/slimapi/messages/{sid}/since/{ts}` anchor-paginated endpoint. The
     * `since` ts is derived from the local slim SSE bookmark
     * ([slimSseState.get]·updatedAt, 0L if none — same source as
     * [coldStartSlimSync] uses for its open-session tail). `limit` / `before`
     * are forwarded verbatim (slimapi supports the since-ts + before-opaque-
     * cursor combination per v1 contract §5).
     *
     * The slimapi DTO IS the legacy [MessageWithParts] shape — there is no
     * separate SlimapiMessage type to map (the sidecar emits skeletons in
     * the wire format the legacy opencode route already speaks). Bookmark
     * bumping uses the same `max(time.updated)` rule as
     * [coldStartSlimSync] / [getSlimapiMessagesSince].
     *
     * `nextCursor`: v1 slim path does NOT surface the `X-Next-Cursor` header
     * (single-page decision, see [SLIMAPI_DEFAULT_PAGE_LIMIT]); the
     * [MessagesPage] returned in slim mode always has `nextCursor = null`.
     * v2 may add cursor transparency.
     *
     * legacy (`isSlimMode == false`): byte-for-byte unchanged.
     */
    suspend fun getMessagesPaged(
        sessionId: String,
        limit: Int? = null,
        before: String? = null
    ): Result<MessagesPage> = runSuspendCatching {
        if (isSlimMode) {
            // Derive anchor from local slim SSE bookmark (same source as
            // coldStartSlimSync's open-session branch).
            val since = slimSseState.get(sessionId)?.updatedAt ?: 0L
            val response = api.getSlimapiMessagesSince(sessionId, since, limit, before)
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code()}")
            val items = response.body() ?: emptyList()
            // §slim-bookmark: bump the local watermark to the max observed
            // time.updated so subsequent digests only trigger on strictly
            // newer activity (mirrors [getSlimapiMessagesSince] +
            // [coldStartSlimSync]).
            bumpSlimBookmarkFromItems(sessionId, items)
            MessagesPage(items = items, nextCursor = null)
        } else {
            val response = api.getMessages(sessionId, limit, before)
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code()}")
            val items = response.body() ?: emptyList()
            val nextCursor = response.headers()["X-Next-Cursor"]
            MessagesPage(items = items, nextCursor = nextCursor)
        }
    }

    /**
     * §Phase1B lightweight tail probe: fetches only the single newest message
     * id for [sessionId] (limit=1, desc default).
     */
    suspend fun probeLatestMessageId(sessionId: String): Result<String?> = runSuspendCatching {
        val response = api.getMessages(sessionId, limit = 1, before = null)
        if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code()}")
        response.body()?.firstOrNull()?.info?.id
    }

    suspend fun sendMessage(
        sessionId: String,
        text: String,
        agent: String? = null,
        model: Message.ModelInfo? = null,
        attachments: List<ComposerImageAttachment> = emptyList()
    ): Result<Unit> = runSuspendCatching {
        val parts = buildList {
            if (text.isNotBlank()) add(PromptRequest.PartInput(type = "text", text = text))
            attachments.forEach { attachment ->
                add(
                    PromptRequest.PartInput(
                        type = "file",
                        mime = attachment.mime,
                        filename = attachment.filename,
                        url = attachment.dataUrl
                    )
                )
            }
        }
        val request = PromptRequest(
            parts = parts,
            agent = agent,
            model = model?.let { PromptRequest.ModelInput(it.providerId, it.modelId) }
        )
        val response = api.promptAsync(sessionId, request)
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Send failed ${response.code()}: $errorBody")
        }
    }

    suspend fun abortSession(sessionId: String): Result<Unit> = runSuspendCatching {
        api.abortSession(sessionId)
    }

    /**
     * §context-compact: triggers server-side context compaction for [sessionId]
     * via POST /session/{id}/summarize. The compaction itself runs async on
     * the server; the resulting message/part SSE events drive the message
     * reload automatically. [model] is the current session model (read from
     * app state by the caller) — the server uses it to generate the summary.
     *
     * §compact-graded (Blocker-1): the returned [Result] distinguishes three
     * outcomes so the caller ([ChatViewModel.compactSession]) can grade its
     * recovery instead of swallowing every failure:
     *  - `Result.success(true)` — POST accepted and the server explicitly
     *    acknowledged (body=`true`, or body=null as in HTTP 204 where the
     *    server returned no body but the request was accepted).
     *  - `Result.failure(ServerRejectedException)` — POST reached the server,
     *    HTTP 2xx came back, but the body was `false`: the server explicitly
     *    rejected compaction (e.g. context too small to summarize, server
     *    refused). This is a *deterministic* failure — the user must be told
     *    and `isCompacting` must be cleared so a retry is possible.
     *  - `Result.failure(<IOException/HttpException>)` — transport or HTTP
     *    non-2xx failure. The caller's grading logic further splits this into
     *    read-side [java.net.SocketTimeoutException] (POST likely accepted,
     *    SSE will carry the result) vs everything else (POST never reached
     *    the server).
     *
     * Completion of the compaction itself is reported through SSE; the body
     * is NOT interpreted as the compaction result.
     */
    suspend fun summarizeSession(
        sessionId: String,
        model: Message.ModelInfo
    ): Result<Boolean> = runSuspendCatching {
        val response = api.summarizeSession(sessionId, SummarizeRequest(model.providerId, model.modelId))
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Summarize failed ${response.code()}: $errorBody")
        }
        // body == null (HTTP 204 etc.) → server accepted, no body to read.
        val accepted = response.body() ?: true
        if (!accepted) {
            // §compact-graded: server returned `false` → explicit rejection.
            // Throw so runSuspendCatching turns it into Result.failure and the
            // caller's onFailure(accepted-reject) branch can clear isCompacting.
            throw SummarizeServerRejectedException()
        }
        accepted
    }

    /**
     * §compact-graded (Blocker-1): raised by [summarizeSession] when the
     * server returns HTTP 2xx with body `false`. Distinct type so
     * [ChatViewModel.compactSession] can branch on `onFailure` +
     * `cause is SummarizeServerRejectedException` and clear `isCompacting`
     * + emit a deterministic Error (vs the read-timeout Info path).
     */
    class SummarizeServerRejectedException :
        Exception("Server rejected compaction (body=false)")

    suspend fun forkSession(sessionId: String, messageId: String? = null): Result<Session> = runSuspendCatching {
        api.forkSession(sessionId, ForkSessionRequest(messageId))
    }

    suspend fun revertSession(sessionId: String, messageId: String, partId: String? = null): Result<Session> = runSuspendCatching {
        api.revertSession(sessionId, RevertSessionRequest(messageId, partId))
    }

    /**
     * §slim-reconcile-lane-repo (B2 T4) / §rev-grok fix1: in slim mode, route
     * to the sidecar's `/slimapi/permissions` cross-directory aggregate
     * (single call replaces the legacy per-directory `/permission` poll). The
     * sidecar returns its `{items, errors}` envelope; this method flattens
     * `.items` and maps each [SlimapiPermissionEntry] to a legacy
     * [PermissionRequest] via [toPermissionRequest], **preserving
     * [SlimapiPermissionEntry.routeToken]** (Phase 3b — the slim respond
     * path needs the sidecar HMAC; `directory` is still dropped, the sidecar
     * re-injects it from the token).
     *
     * `directories = null` lets the sidecar decide scope (matches
     * [coldStartSlimSync]'s default — the upper layer that knows the user's
     * session set can pass a list explicitly via [getSlimapiPermissions]).
     *
     * Per-directory `errors` are logged at WARN (same envelope-degradation
     * policy as the other slimapi aggregate readers).
     *
     * legacy (`isSlimMode == false`): byte-for-byte unchanged.
     */
    suspend fun getPendingPermissions(): Result<List<PermissionRequest>> = runSuspendCatching {
        if (isSlimMode) {
            val aggregation = api.getSlimapiPermissions(directories = null)
            if (aggregation.errors.isNotEmpty()) {
                DebugLog.w(TAG, "slimapi/permissions partial errors: ${aggregation.errors}")
            }
            aggregation.items.map { it.toPermissionRequest() }
        } else {
            api.getPendingPermissions()
        }
    }

    suspend fun respondPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse
    ): Result<Unit> = runSuspendCatching {
        api.respondPermission(sessionId, permissionId, PermissionResponseRequest(response.value))
    }

    suspend fun getPendingQuestions(directory: String?): Result<List<QuestionRequest>> = runSuspendCatching {
        api.getPendingQuestions(directory)
    }

    suspend fun replyQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?
    ): Result<Unit> = runSuspendCatching {
        val response = api.replyQuestion(requestId, QuestionReplyRequest(answers), directory)
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Reply failed ${response.code()}: $errorBody")
        }
    }

    suspend fun rejectQuestion(requestId: String, directory: String?): Result<Unit> = runSuspendCatching {
        val response = api.rejectQuestion(requestId, directory)
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Reject failed ${response.code()}: $errorBody")
        }
    }

    /**
     * §catalog-source: builds the model catalog from `GET /config/providers` —
     * the SAME endpoint the opencode web model picker uses (verified by
     * inspecting the web bundle served by opencode 1.17.x). Returns the
     * [ProvidersResponse] downstream consumes (model picker + Model Management
     * + context-limit index + per-prompt model attachment), unchanged.
     *
     * §catalog-source-revert (from the V2 /api/model + /api/provider pair): on
     * opencode ≤1.17.x the V2 pair returns a STRICT SUBSET — only providers
     * with an explicit `options.apiKey` in config plus the free `opencode`
     * (Zen) provider — omitting most configured providers. On one 1.17.15
     * server /api/model returned 3 providers / 31 models while
     * /config/providers returned 10 providers / 61 models, so the app showed
     * far fewer models than the web. /config/providers returns the full
     * catalog the web shows.
     *
     * §forward-compat: opencode HEAD is moving the web to `/api/provider`
     * (whose `Provider` type gains a `models` map). On ≤1.17.x `/api/provider`
     * returns NO `models` field, so it cannot source the picker there; if a
     * future opencode drops /config/providers or stops populating it, revisit.
     *
     * §key-leak safety (the original reason for the V2 migration — and why it
     * is safe to revert here): /config/providers' raw body carries provider
     * `apiKey` values, BUT
     *   (a) [ConfigProvider] / [ProviderModel] have NO `options`/`apiKey`/`key`
     *       field + `ignoreUnknownKeys = true` → keys are dropped at
     *       deserialization, never held in memory or logged;
     *   (b) [cn.vectory.ocdroid.data.repository.http.HttpHeaders.CACHEABLE_PATHS]
     *       intentionally EXCLUDES `/config/providers` → no on-disk OkHttp
     *       cache residue;
     *   (c) this is a personal client ↔ personal server, so transit is to the
     *       device owner only.
     *
     * §last-mile defense: a structural failure (HTTP error / non-decodable
     * body) does NOT propagate as Result.failure (which would surface as
     * "服务器没有可用模型"); it logs + returns an EMPTY catalog
     * (Result.success) so the picker shows an empty list and the next refresh
     * retries. CancellationException is rethrown (structured concurrency).
     * Providers whose `models` map is empty are dropped (parity with the
     * former V2 builder's groupBy).
     */
    suspend fun getProviders(): Result<ProvidersResponse> {
        // §catalog-source-revert: fetch GET /config/providers — the SAME endpoint
        // the opencode web model picker uses (verified on the web bundle served
        // by opencode 1.17.x). The former V2 /api/model + /api/provider pair
        // returns a STRICT SUBSET on ≤1.17.x (only providers with an explicit
        // options.apiKey in config + the free `opencode`/Zen provider), omitting
        // most configured providers → the app showed far fewer models than the
        // web. /config/providers returns the full catalog. See the method kdoc
        // for the key-leak safety analysis that makes this revert safe.
        val response: ProvidersResponse = try {
            api.getProviders()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // §last-mile defense: do NOT propagate failure (would surface as
            // "服务器没有可用模型"); degrade to an empty catalog so the picker
            // shows an empty list and the next refresh retries. Cancellation is
            // rethrown for structured concurrency.
            DebugLog.e("OpenCodeRepository", "catalog: /config/providers fetch failed, returning empty catalog", e)
            return Result.success(ProvidersResponse(providers = emptyList()))
        }
        // Drop providers with no models (parity with the former V2 builder's
        // groupBy: a provider whose models map is empty renders no picker rows).
        val providers = response.providers.filter { it.models.isNotEmpty() }
        val totalModels = providers.sumOf { it.models.size }
        DebugLog.i("OpenCodeRepository", "catalog: ${providers.size} provider(s), $totalModels model(s) from /config/providers")
        return Result.success(
            ProvidersResponse(providers = providers, defaultByProvider = response.defaultByProvider)
        )
    }

    /**
     * §model-selection: lists the server's available models via the v2
     * `GET /api/model` endpoint. Returns the `data` array (each entry carries
     * `id`, `providerID`, `name`, `enabled`, `limit`). NOTE: [getProviders]
     * above no longer uses this endpoint — it fetches `/config/providers`,
     * which returns the full model catalog the opencode web picker shows;
     * this standalone [getModels] is retained for debug/console use and has
     * no production caller.
     *
     * §v2-tolerant-catalog (0.6.1 round-1): per-entry decode so a wrong-type
     * entry is skipped + logged instead of
     * nuking the whole list. Structural failures (non-array `data`) still
     * surface as Result.failure here (this is a debug-only path; no last-mile
     * empty-catalog fallback is needed — callers are human-facing console).
     */
    suspend fun getModels(): Result<List<ModelInfoV2>> = runSuspendCatching {
        apiV2.getModels().data.mapNotNull { elem ->
            runCatching { json.decodeFromJsonElement<ModelInfoV2>(elem) }
                .onFailure {
                    DebugLog.w(
                        "OpenCodeRepository",
                        "v2 catalog: skipping unparseable model entry: ${elem.toString().take(200)}"
                    )
                }
                .getOrNull()
        }
    }

    suspend fun getAgents(): Result<List<AgentInfo>> = runSuspendCatching { api.getAgents() }

    /**
     * Lists the server-defined slash commands.
     */
    suspend fun getCommands(): Result<List<CommandInfo>> = runSuspendCatching { api.getCommands() }

    /**
     * Executes a slash command against [sessionId]. §R18 Phase 2-E step 1:
     * the directory context is supplied EXPLICITLY by the caller (the
     * session's workdir); the OkHttp interceptor no longer injects the
     * global workdir fallback over it.
     *
     * §grouping-rewrite item 4: routes through [commandApi] (own OkHttp
     * client with a 300 s read timeout) instead of [api] (30 s) so a slow
     * synchronous server-side command step does not trip a false-negative
     * command-failed timeout — SSE still delivers the results on its own
     * 0-timeout client. See [OkHttpClientFactory.commandClient].
     */
    suspend fun executeCommand(
        sessionId: String,
        command: String,
        arguments: String = "",
        agent: String? = null,
        directory: String?
    ): Result<Unit> = runSuspendCatching {
        val response = commandApi.executeCommand(
            sessionId,
            CommandRequest(command = command, arguments = arguments, agent = agent),
            directory
        )
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Command failed ${response.code()}: $errorBody")
        }
    }

    suspend fun getSessionDiff(sessionId: String): Result<List<FileDiff>> = runSuspendCatching {
        api.getSessionDiff(sessionId)
    }

    suspend fun getSessionTodos(sessionId: String): Result<List<TodoItem>> = runSuspendCatching {
        api.getSessionTodos(sessionId)
    }

    /**
     * §R-17 batch4 / §R18 Phase 2-E step 2: lists files under [directory]
     * (absolute workdir) at the relative [path]. The directory is passed
     * EXPLICITLY to the server via `?directory` + the `X-Opencode-Skip-Dir`
     * marker on the API method (no global state involved).
     */
    suspend fun getFileTree(directory: String, path: String? = null): Result<List<FileNode>> = runSuspendCatching {
        api.getFileTree(path ?: "", directory)
    }

    /**
     * Lists the contents of an arbitrary [directory] (independent of the
     * currently selected session's workdir). Used by the directory picker.
     */
    suspend fun getFileTreeForDirectory(
        directory: String,
        path: String? = null
    ): Result<List<FileNode>> = runSuspendCatching {
        api.getFileTreeForDirectory(directory, path ?: "")
    }

    /** §R-17 batch4: see [getFileTree] for the explicit-directory rationale. */
    suspend fun getFileContent(directory: String, path: String): Result<FileContent> = runSuspendCatching {
        api.getFileContent(path, directory)
    }

    /** §R-17 batch4: see [getFileTree] for the explicit-directory rationale. */
    suspend fun getFileStatus(directory: String): Result<List<FileStatusEntry>> = runSuspendCatching {
        api.getFileStatus(directory)
    }

    // §vcs-section: read-only VCS façade for the Settings → Working directory
    // section. Thin wrappers mirroring the file* directory-scoped pattern
    // (§R-17 batch4): the directory is supplied EXPLICITLY by the caller; no
    // global workdir state. VcsInfo / VcsStatusEntry live in data.model; the
    // diff endpoint reuses the existing FileDiff shape (same as /session/{id}/diff).
    suspend fun getVcs(directory: String?): Result<VcsInfo> = runSuspendCatching {
        api.getVcs(directory)
    }

    suspend fun getVcsStatus(directory: String?): Result<List<VcsStatusEntry>> = runSuspendCatching {
        api.getVcsStatus(directory)
    }

    suspend fun getVcsDiff(mode: String, directory: String?): Result<List<FileDiff>> = runSuspendCatching {
        api.getVcsDiff(mode, directory)
    }

    /** §R-17 batch4: see [getFileTree] for the explicit-directory rationale. */
    suspend fun findFile(directory: String, query: String, limit: Int = 50): Result<List<String>> = runSuspendCatching {
        api.findFile(query, limit, directory)
    }

    /**
     * §R18 Phase 2-E step 1: SSE feed now takes an explicit [directory] so
     * the server routes events to the right InstanceState without relying on
     * the global workdir. Null defers to the interceptor's fallback (still
     * present in step 1; removed in step 2).
     *
     * Cluster A (slim SSE): gated on [HostConfig.slim]. Slim mode subscribes
     * to the sidecar's instance-level `/slimapi/events` (no directory param,
     * every frame carries its own `directory`); legacy mode preserves the
     * `/global/event?directory=` behaviour byte-for-byte. The [directory]
     * argument is IGNORED in slim mode (the sidecar is instance-level).
     */
    fun connectSSE(directory: String?): Flow<Result<SSEEvent>> =
        sseClient.connect(
            baseUrl = hostConfig.baseUrl,
            username = hostConfig.username,
            password = hostConfig.password,
            directory = directory,
            slimMode = hostConfig.slim,
        )

    /**
     * Activates a tunnel by POSTing the password to the tunnel endpoint.
     * Uses an independent OkHttpClient without any Basic Auth interceptor,
     * since tunnel authentication uses form-encoded POST (not HTTP Basic Auth).
     *
     * §tofu R2: [hostPort] (host:port authority of [tunnelUrl]) replaces the
     * legacy `allowInsecure: Boolean`; it keys the TOFU pin lookup for the
     * one-shot tunnel POST.
     */
    suspend fun activateTunnel(
        tunnelUrl: String,
        password: String,
        hostPort: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            try {
                val client = clientFactory.tunnelClient(hostPort)
                val formBody = FormBody.Builder()
                    .add("persist_auth", "off")
                    .add("pw", password)
                    .build()
                val request = okhttp3.Request.Builder()
                    .url(tunnelUrl)
                    .post(formBody)
                    .build()
                val response = client.newCall(request).execute()
                response.use {
                    if (!it.isSuccessful) {
                        val body = it.body?.string().orEmpty()
                        throw Exception("HTTP ${it.code}${if (it.message.isNotBlank()) " ${it.message}" else ""}: ${body.ifBlank { "(空响应体)" }}")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.message?.startsWith("HTTP ") == true) throw e
                throw Exception(buildString {
                    append(e::class.simpleName ?: "Exception")
                    e.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                    e.cause?.let { c ->
                        append(" ← ")
                        append(c::class.simpleName ?: "")
                        c.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                    }
                }, e)
            }
        }
    }

    // ---- Traffic debug ----

    fun flushTrafficLog() = trafficLogger.flushToDisk()
    fun dumpTrafficLog(): String = trafficLogger.dump()

    // ── Cluster A: slimapi SSE + cold-start + q/p reply façade ──────────────
    //
    // These methods expose the slimapi endpoints declared on [OpenCodeApi]
    // as thin suspend wrappers under [runSuspendCatching]. They are GATED
    // on [HostConfig.slim] at the call site (upper layers) — these methods
    // themselves make no mode check (a future legacy caller could still
    // benefit if the sidecar were ever reachable from a non-slim profile).

    /**
     * Cluster A: pure digest reducer entry point. Parses a `session.digest`
     * SSE frame's properties into [SlimSessionDigest], runs [reduceSlimDigest]
     * against the in-memory [slimSseState], and returns the fetch decision
     * (or null). The caller (slim SSE event loop) executes the fetch via
     * [getSlimapiMessagesSince] when non-null.
     *
     * Properties may carry any subset of {directory, status, messageID,
     * updatedAt, archived, deleted} (§3 debounce — only changed fields).
     * Absent fields are preserved; present fields merge onto prior state.
     *
     * Returns null if the frame is malformed or no fetch is warranted.
     */
    fun applySlimDigest(digest: SlimSessionDigest): SlimFetchMessages? {
        val parsed = digest.takeIf { it.sessionId.isNotBlank() } ?: return null
        return reduceSlimDigest(slimSseState, parsed)
    }

    /**
     * Cluster A: snapshot the per-session slim SSE state (testing + upper
     * layer queries). Returns a defensive copy.
     */
    fun snapshotSlimSseState(): Map<String, SlimSessionState> = slimSseState.all()

    /**
     * Cluster A: anchor-paginated message fetch (§5 A2=A). GETs
     * `/slimapi/messages/{sid}/since/{ts}` — server returns skeletons whose
     * `time.updated >= ts`. After a successful fetch the local bookmark is
     * bumped to the max `time.updated` observed so subsequent digests only
     * trigger on strictly newer activity.
     *
     * Pass [since] = 0L for the cold-start path (no prior bookmark).
     */
    suspend fun getSlimapiMessagesSince(
        sessionId: String,
        since: Long,
        limit: Int? = null,
        before: String? = null
    ): Result<List<MessageWithParts>> = runSuspendCatching {
        val response = api.getSlimapiMessagesSince(sessionId, since, limit, before)
        if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code()}")
        val items = response.body() ?: emptyList()
        // §slim-bookmark: bump to max time.updated observed (shared helper
        // — also used by [getMessagesPaged] slim branch +
        // [coldStartSlimSync]). Guards against a fetch returning an OLDER
        // tail than what a later digest advanced us to.
        bumpSlimBookmarkFromItems(sessionId, items)
        items
    }

    /**
     * Cluster A: cursor-paginated skeleton fetch (`/slimapi/messages/{sid}`).
     * Used for the initial tail when no anchor is known yet (mirrors the
     * legacy [getMessagesPaged] semantics).
     */
    suspend fun getSlimapiMessagesPaged(
        sessionId: String,
        limit: Int? = null,
        before: String? = null
    ): Result<List<MessageWithParts>> = runSuspendCatching {
        val response = api.getSlimapiMessages(sessionId, limit, before)
        if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code()}")
        response.body() ?: emptyList()
    }

    /**
     * Cluster A: single-message full expansion (`/slimapi/messages/{sid}/full/{mid}`).
     */
    suspend fun getSlimapiMessageFull(
        sessionId: String,
        messageId: String
    ): Result<MessageWithParts> = runSuspendCatching {
        api.getSlimapiMessageFull(sessionId, messageId)
    }

    /**
     * Cluster A: cold-start sessions snapshot (`/slimapi/sessions`). Skeleton
     * rows; each carries its own `directory` so the caller can filter
     * client-side. Excludes archived by default (server side).
     *
     * §slim-reconcile-lane-repo (B4 T6): [directories] is a [List] of workdirs
     * (Retrofit expands each entry to a separate `?directory=...` query,
     * matching the v1 contract's repeated-param requirement). null = the
     * sidecar returns all directories it is aggregating for this client.
     */
    suspend fun getSlimapiSessions(
        directories: List<String>? = null,
        roots: Boolean? = null,
        limit: Int? = null,
        search: String? = null
    ): Result<List<Session>> = runSuspendCatching {
        api.getSlimapiSessions(directories, roots, limit, search)
    }

    /**
     * Cluster A: cross-directory pending questions aggregate
     * (`/slimapi/questions`). Each entry carries `directory` + `routeToken`.
     *
     * The sidecar wraps the response as `{"items": [...], "errors": [...]}`
     * (see [SlimapiQuestionAggregation]); this method flattens to `.items`
     * for UI consumption. Per-directory `errors` are logged at debug level
     * but NOT surfaced to the UI in v1 (the sidecar already degrades —
     * partial result > no result).
     */
    suspend fun getSlimapiQuestions(
        directories: List<String>? = null
    ): Result<List<SlimapiQuestionEntry>> = runSuspendCatching {
        val aggregation = api.getSlimapiQuestions(directories)
        if (aggregation.errors.isNotEmpty()) {
            DebugLog.w(TAG, "slimapi/questions partial errors: ${aggregation.errors}")
        }
        aggregation.items
    }

    /**
     * Cluster A: cross-directory pending permissions aggregate. Same shape
     * as [getSlimapiQuestions].
     */
    suspend fun getSlimapiPermissions(
        directories: List<String>? = null
    ): Result<List<SlimapiPermissionEntry>> = runSuspendCatching {
        val aggregation = api.getSlimapiPermissions(directories)
        if (aggregation.errors.isNotEmpty()) {
            DebugLog.w(TAG, "slimapi/permissions partial errors: ${aggregation.errors}")
        }
        aggregation.items
    }

    /**
     * Cluster A: reply to a slimapi-routed question. Body carries answers +
     * routeToken; the sidecar validates the token, re-injects directory,
     * and forwards to opencode.
     */
    suspend fun replySlimapiQuestion(
        questionId: String,
        answers: List<List<String>>,
        routeToken: String?
    ): Result<Unit> = runSuspendCatching {
        val response = api.replySlimapiQuestion(
            questionId,
            SlimapiQuestionReplyRequest(answers = answers, routeToken = routeToken)
        )
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Slimapi reply failed ${response.code()}: $errorBody")
        }
    }

    /** Cluster A: reject a slimapi-routed question. See [replySlimapiQuestion]. */
    suspend fun rejectSlimapiQuestion(
        questionId: String,
        routeToken: String?
    ): Result<Unit> = runSuspendCatching {
        val response = api.rejectSlimapiQuestion(
            questionId,
            SlimapiQuestionRejectRequest(routeToken = routeToken)
        )
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Slimapi reject failed ${response.code()}: $errorBody")
        }
    }

    /**
     * Cluster A: respond to a slimapi-routed permission. Body carries the
     * response (once/always/reject) + routeToken; the sessionID is in the
     * URL path.
     */
    suspend fun respondSlimapiPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        routeToken: String?
    ): Result<Unit> = runSuspendCatching {
        val resp = api.respondSlimapiPermission(
            sessionId,
            permissionId,
            SlimapiPermissionResponseRequest(response = response.value, routeToken = routeToken)
        )
        if (!resp.isSuccessful) {
            val errorBody = resp.errorBody()?.string() ?: resp.message()
            throw Exception("Slimapi permission respond failed ${resp.code()}: $errorBody")
        }
    }

    /**
     * Cluster A: cold-start + resync snapshot (v1 contract §4). The SAME
     * code path serves BOTH the initial connect AND a `resync` frame
     * (resync = reuse cold-start per §4). Fetches:
     *  - `/slimapi/sessions` (skeleton list, default excludes archived),
     *  - `/slimapi/questions` (cross-directory pending),
     *  - `/slimapi/permissions` (cross-directory pending),
     *  - `/slimapi/messages/{openSessionId}/since/{ts}` iff [openSessionId]
     *    is supplied (uses the local bookmark, 0L if none).
     *
     * Returns the four pieces as a [SlimColdStartSnapshot]. The caller
     * (A2 bootstrap engine / resync handler) decides how to fold them into
     * UI state — this method does NOT mutate any UI slice.
     *
     * Failures degrade per-piece (each piece returns null on error; the
     * overall Result is success unless ALL pieces fail). This matches the
     * slim-sidecar-resync contract: a sidecar that lost only its messages
     * piece (e.g. opencode busy on /message) still returns sessions/q/p
     * that the client can fold; a hard sidecar-down surfaces via
     * [checkHealth] not here.
     */
    suspend fun coldStartSlimSync(
        openSessionId: String? = null,
        directories: List<String>? = null,
    ): Result<SlimColdStartSnapshot> = runSuspendCatching {
        // §slim-reconcile-lane-repo (B4 T6): forward [directories] to
        // /slimapi/sessions (was: ignored — call always hit the unfiltered
        // path). The contract's repeated `?directory` is now produced by
        // Retrofit from the list parameter (null = sidecar decides scope).
        val sessions = runCatching {
            api.getSlimapiSessions(directories = directories)
        }.getOrDefault(emptyList())
        // §slim-envelope: /questions + /permissions return {items, errors};
        // flatten `.items` for UI. Per-directory `errors` are logged here
        // (the sidecar already degrades — a 200 with partial items is the
        // expected steady-state when one upstream opencode is briefly down).
        val questions = runCatching {
            val agg = api.getSlimapiQuestions(directories)
            if (agg.errors.isNotEmpty()) {
                DebugLog.w(TAG, "slimapi/questions partial errors: ${agg.errors}")
            }
            agg.items
        }.getOrDefault(emptyList())
        val permissions = runCatching {
            val agg = api.getSlimapiPermissions(directories)
            if (agg.errors.isNotEmpty()) {
                DebugLog.w(TAG, "slimapi/permissions partial errors: ${agg.errors}")
            }
            agg.items
        }.getOrDefault(emptyList())
        val messages: List<MessageWithParts>? = openSessionId?.let { sid ->
            val since = slimSseState.get(sid)?.updatedAt ?: 0L
            // §slim-v1-paging: single bounded page; no cursor follow in v1.
            runCatching {
                val response = api.getSlimapiMessagesSince(
                    sid, since, limit = SLIMAPI_DEFAULT_PAGE_LIMIT
                )
                if (response.isSuccessful) {
                    val items = response.body() ?: emptyList()
                    bumpSlimBookmarkFromItems(sid, items)
                    items
                } else null
            }.getOrNull()
        }
        // If ALL four pieces are empty / null AND openSessionId was supplied
        // with at least one piece attempted, that's fine — surface the
        // (possibly-empty) snapshot. Only a hard transport failure that
        // threw out of runCatching surfaces as Result.failure.
        SlimColdStartSnapshot(
            sessions = sessions,
            questions = questions,
            permissions = permissions,
            messages = messages,
        )
    }

    // ── §slim-reconcile-lane-repo: shared slim helpers ────────────────────

    /**
     * §slim-reconcile-lane-repo (B2 T3): bumps the local slim SSE bookmark
     * for [sessionId] to the max `time.updated` observed in [items].
     * Consolidates the bump rule shared across three fetch sites:
     *  - [getSlimapiMessagesSince] (public slim entry point)
     *  - [getMessagesPaged] slim branch (legacy→slim routed)
     *  - [coldStartSlimSync] open-session tail
     *
     * No-op when [items] is empty or all entries lack `time.updated`. The
     * bump is monotonic — [SlimSseState.bumpUpdatedAt] only writes when the
     * new value strictly exceeds the prior watermark.
     */
    private fun bumpSlimBookmarkFromItems(sessionId: String, items: List<MessageWithParts>) {
        val maxUpdated = items.maxOfOrNull { it.info.time?.updated ?: 0L } ?: 0L
        if (maxUpdated > 0L) slimSseState.bumpUpdatedAt(sessionId, maxUpdated)
    }

    companion object {
        /**
         * Default server URL. Mirrored from [HostConfig.DEFAULT_SERVER] so
         * `OpenCodeRepositoryTest.default server URL is localhost` continues
         * to pass without change.
         */
        const val DEFAULT_SERVER = HostConfig.DEFAULT_SERVER

        /**
         * Tag for slimapi envelope-degradation warnings (per-directory
         * `errors` inside `{items, errors}`). Surfaced at WARN so the
         * sidecar's "one opencode down → 200 with partial items" path
         * remains observable in `adb logcat`.
         */
        private const val TAG = "OpenCodeRepository"
    }
}

/**
 * Cluster A: per-call cold-start / resync snapshot returned by
 * [OpenCodeRepository.coldStartSlimSync]. The four pieces are independent
 * (one failing does NOT poison the others); each is null/empty when its
 * underlying fetch failed or yielded nothing.
 */
data class SlimColdStartSnapshot(
    val sessions: List<Session>,
    val questions: List<SlimapiQuestionEntry>,
    val permissions: List<SlimapiPermissionEntry>,
    /** Null iff no openSessionId was requested; empty list iff fetched OK. */
    val messages: List<MessageWithParts>?,
)

/**
 * R8 slim-mode foundation: type-safe accessors for the tolerant
 * `/slimapi/health` body parser ([OpenCodeRepository.parseSlimapiHealth]).
 * Returning null on shape mismatch (rather than throwing ClassCastException)
 * lets the parser degrade per-field to null, where
 * [ServerCompatProfile.isSlimapiClientAccepted] then fail-closes.
 */
private fun JsonElement.safeObject(): JsonObject? = this as? JsonObject
private fun JsonElement.safeArray(): JsonArray? = this as? JsonArray
private fun JsonElement.safePrimitive(): JsonPrimitive? = this as? JsonPrimitive

/**
 * §slim-reconcile-lane-repo (B2 T4) / §rev-grok fix1: adapt a
 * [SlimapiPermissionEntry] (the slimapi aggregate shape = legacy fields +
 * slimapi-only `directory` + `routeToken`) to the legacy [PermissionRequest]
 * model that the UI / VM consumers expect.
 *
 * **Preserves [SlimapiPermissionEntry.routeToken]** (Phase 3b): the slimapi
 * respond path ([OpenCodeRepository.respondSlimapiPermission]) requires the
 * sidecar HMAC to validate the response POST (§2/B2). The upper-layer
 * [ChatScaffold] reads [PermissionRequest.routeToken] off the
 * `pendingPermissions` entry and forwards it to
 * [OrchestratorViewModel.respondPermission]; if this mapping drops the
 * token, the slim respond falls back to the legacy endpoint and the
 * sidecar rejects / misroutes the response. (`directory` stays dropped —
 * the sidecar re-injects it from the token, and the upper layer's
 * session-set already implies it.)
 *
 * Hoisted top-level so the mapping is unit-testable in isolation (see
 * [OpenCodeRepositorySlimapiEndpointsTest]`getPendingPermissions slim mode
 * maps entries to PermissionRequest`).
 */
internal fun SlimapiPermissionEntry.toPermissionRequest(): PermissionRequest =
    PermissionRequest(
        id = id,
        sessionId = sessionId,
        permission = permission,
        patterns = patterns,
        metadata = metadata,
        always = always,
        tool = tool,
        routeToken = routeToken,
    )
