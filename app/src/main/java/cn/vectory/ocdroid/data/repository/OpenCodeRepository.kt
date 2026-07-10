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
import cn.vectory.ocdroid.data.repository.http.SslConfig
import cn.vectory.ocdroid.data.repository.http.SslConfigFactory
import cn.vectory.ocdroid.data.repository.http.TofuDecision
import cn.vectory.ocdroid.data.repository.http.TofuFailureReason
import cn.vectory.ocdroid.data.repository.http.TofuPinStore
import cn.vectory.ocdroid.data.repository.http.TofuValidation
import cn.vectory.ocdroid.data.repository.http.InMemoryTofuPinStore
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
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
        clientCert: ClientCertMaterial? = null
    ) {
        // §2.4: MUST precede hostConfig.configure / rebuildClients so the
        // shared sslConfigFactory holds the new mTLS material when the live
        // REST/SSE/command clients are rebuilt (they read sslConfigFor(...)).
        sslConfigFactory.configureClientCert(clientCert)
        hostConfig.configure(baseUrl, username, password, hostPort)
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
        clientCert: ClientCertMaterial? = null
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
        val normalizedUrl = (if (baseUrl.startsWith("http")) baseUrl else "https://$baseUrl")
            .trimEnd('/') + "/global/health"
        val requestBuilder = Request.Builder()
            .url(normalizedUrl)
            .header(HttpHeaders.SKIP_DIR_HEADER, "1")
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
    fun applyTofuDecision(hostPort: String, decision: TofuDecision) {
        when (decision) {
            is TofuDecision.AcceptOnce -> tofuStore.acceptSession(hostPort, decision.spki)
            is TofuDecision.Trust -> tofuStore.trustPersistent(hostPort, decision.spki)
            TofuDecision.Cancel -> { /* no-op — user declined */ }
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

    suspend fun checkHealth(): Result<HealthResponse> = runSuspendCatching { api.getHealth() }

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
     */
    suspend fun checkHealthFor(
        baseUrl: String,
        username: String? = null,
        password: String? = null,
        hostPort: String? = null,
        clientCert: ClientCertMaterial? = null
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
        val normalizedUrl = (if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl")
            .trimEnd('/') + "/global/health"
        val requestBuilder = Request.Builder()
            .url(normalizedUrl)
            .header(HttpHeaders.SKIP_DIR_HEADER, "1")
        if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
            val credential = "$username:$password"
            val encoded = Base64.getEncoder().encodeToString(credential.toByteArray())
            requestBuilder.header("Authorization", "Basic $encoded")
        }
        client.newCall(requestBuilder.build()).execute().use { res ->
            if (!res.isSuccessful) error("HTTP ${res.code}")
            val body = res.body?.string().orEmpty()
            if (body.isBlank()) error("Empty response body")
            json.decodeFromString(HealthResponse.serializer(), body)
        }
        }
    }

    suspend fun getSessions(limit: Int? = null): Result<List<Session>> = runSuspendCatching { api.getSessions(limit) }

    /**
     * Fetches the root sessions whose [Session.directory] exactly matches
     * [directory]. Uses the server's `?directory` + `?roots` query params
     * (priority: query param > header > cwd, per lib-1) so the result is
     * scoped to this workdir and excludes sub-agent (child) sessions.
     */
    suspend fun getSessionsForDirectory(directory: String, limit: Int? = null): Result<List<Session>> =
        runSuspendCatching { api.getSessions(limit = limit, directory = directory, roots = true) }

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
     */
    suspend fun getMessagesPaged(
        sessionId: String,
        limit: Int? = null,
        before: String? = null
    ): Result<MessagesPage> = runSuspendCatching {
        val response = api.getMessages(sessionId, limit, before)
        if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code()}")
        val items = response.body() ?: emptyList()
        val nextCursor = response.headers()["X-Next-Cursor"]
        MessagesPage(items = items, nextCursor = nextCursor)
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
     * Returns true on success (server returns `true`); surfaces HTTP failures
     * via the same error-body pattern as [executeCommand] / [sendMessage].
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
        val body = response.body()
        if (body != null && !body) throw Exception("Summarize returned false (server declined)")
        body ?: true
    }

    suspend fun forkSession(sessionId: String, messageId: String? = null): Result<Session> = runSuspendCatching {
        api.forkSession(sessionId, ForkSessionRequest(messageId))
    }

    suspend fun revertSession(sessionId: String, messageId: String, partId: String? = null): Result<Session> = runSuspendCatching {
        api.revertSession(sessionId, RevertSessionRequest(messageId, partId))
    }

    suspend fun getPendingPermissions(): Result<List<PermissionRequest>> = runSuspendCatching {
        api.getPendingPermissions()
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
     */
    fun connectSSE(directory: String?): Flow<Result<SSEEvent>> =
        sseClient.connect(hostConfig.baseUrl, hostConfig.username, hostConfig.password, directory)

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

    companion object {
        /**
         * Default server URL. Mirrored from [HostConfig.DEFAULT_SERVER] so
         * `OpenCodeRepositoryTest.default server URL is localhost` continues
         * to pass without change.
         */
        const val DEFAULT_SERVER = HostConfig.DEFAULT_SERVER
    }
}
