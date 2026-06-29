package com.yage.opencode_client.data.repository

import com.yage.opencode_client.data.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import com.yage.opencode_client.data.model.*
import kotlinx.coroutines.flow.Flow
import okhttp3.Cache
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.util.Base64
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.inject.Inject
import javax.inject.Singleton
import com.yage.opencode_client.util.TrafficLogger
import com.yage.opencode_client.util.TrafficTracker
import com.yage.opencode_client.util.runSuspendCatching

/**
 * Hard cap on any single HTTP response body (16 MB).
 *
 * Rationale (0.1.13, gpter review #5): on a 256 MB heap the kotlinx-serialization
 * converter reads the whole body into a byte array, then decodes into a String
 * (~2× memory), so a 32 MB cap can still allocate ~96 MB during conversion.
 * 16 MB gives a ~48 MB worst-case allocation while still accommodating sessions
 * with very large tool outputs.  Beyond 16 MB the server should use cursor
 * pagination (which we already support), making this a signalling cap rather than
 * an everyday limit.
 *
 * See the §OOM P0 interceptor in [OpenCodeRepository.buildRestClient].
 */
private const val MAX_RESPONSE_BYTES = 16L * 1024 * 1024

/**
 * One page of cursor-paginated messages. [nextCursor] is the opaque V1 cursor
 * (`X-Next-Cursor` response header) to pass as `before` for the next older
 * page; null means no more history.
 */
data class MessagesPage(
    val items: List<MessageWithParts>,
    val nextCursor: String?
)

@Singleton
class OpenCodeRepository @Inject constructor(
    private val trafficTracker: TrafficTracker,
    private val trafficLogger: TrafficLogger
) {
    private var baseUrl: String = DEFAULT_SERVER
    private var username: String? = null
    private var password: String? = null

    // Current workdir context for directory-scoped requests. Read by the OkHttp
    // interceptor on IO threads, so mark @Volatile for visibility across threads.
    @Volatile
    private var currentDirectory: String? = null

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false  // Omit null fields - server rejects model: null
        encodeDefaults = true  // Include type in parts - server needs discriminator
    }

    private val trustAllTrustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /**
     * §16.1(a): OkHttp HTTP cache singleton.
     *
     * Lives as a `by lazy` field on the repository so it survives
     * `rebuildClients()` / `configure()` (host switches) — OkHttp's
     * `DiskLruCache` does NOT allow two `Cache` instances on the same
     * directory (`cache is closed` crash), so we MUST NOT recreate it
     * inside `baseBuilder()` / `buildRestClient()` / `buildSseClient()`.
     *
     * Resolves the cache directory from `java.io.tmpdir` (on Android this is
     * the app-private `/data/data/<pkg>/cache` dir, identical to
     * `Context.cacheDir`). Returns null when the directory is unavailable so
     * `baseBuilder()` degrades gracefully (no cache) instead of
     * crashing — preserving the spec's safety contract.
     *
     * IMPORTANT: declared BEFORE [restHttp]/[sseHttp] (and friends below)
     * because those field initializers call `buildRestClient()` /
     * `buildSseClient()` → `baseBuilder()`, which reads this delegate.
     * Property initializers run top-to-bottom, so a `by lazy` declared after
     * its first reader would still hold a null delegate backing field at
     * construction time.
     */
    private val httpCache: Cache? by lazy {
        val baseDir = System.getProperty("java.io.tmpdir")?.let(::File) ?: return@lazy null
        if (!baseDir.exists() && !baseDir.mkdirs()) return@lazy null
        // Silent degradation: if the cache dir is unwritable or the Cache
        // constructor throws, the client simply runs without a cache. We do
        // NOT android.util.Log here to keep this unit-testable without
        // returnDefaultValues/mocking — the pre-existing repository had no
        // Log usage and we preserve that invariant.
        // 注意：这里是普通 lazy 初始化（非 suspend），保留标准 runCatching。
        runCatching { Cache(File(baseDir, "okhttp"), HTTP_CACHE_SIZE_BYTES) }
            .getOrNull()
    }

    /**
     * R-01: 当前 profile 是否接受不安全（trust-all）TLS 连接。由
     * [configure] 在切换 host 时写入，[buildRestClient]/[buildSseClient]/
     * [buildTunnelOkHttpClient]/[checkHealthFor] 读取。@Volatile 保证被
     * OkHttp 拦截器线程可见。默认 false（仅信任系统证书）。
     */
    @Volatile
    private var currentAllowInsecure: Boolean = false

    private var restHttp: OkHttpClient = buildRestClient(currentAllowInsecure)
    private var sseHttp: OkHttpClient = buildSseClient(currentAllowInsecure)
    private var retrofit: Retrofit = buildRetrofit()
    private var api: OpenCodeApi = retrofit.create(OpenCodeApi::class.java)
    private var sseClient: SSEClient = SSEClient(sseHttp)

    /**
     * R-01: 统一的 SSL 配置抽象。默认走系统信任库；仅当 profile 显式开启
     * [HostProfile.allowInsecureConnections] 时降级为 trust-all。
     * 把原先全局硬编码的 trustAll 收紧为按 host 的可审计开关。
     *
     * [TrustAll] 实例由 [trustAllConfig] 缓存（lazy），避免每次 rebuild
     * client 都重新初始化 SSLContext。
     */
    private sealed interface SslConfig {
        data object SystemDefault : SslConfig
        data class TrustAll(val tm: X509TrustManager, val factory: SSLSocketFactory) : SslConfig
    }

    /** 缓存的 trust-all 配置（lazy 单例），避免重复初始化 SSLContext。 */
    private val trustAllConfig: SslConfig.TrustAll by lazy {
        SslConfig.TrustAll(trustAllTrustManager, trustAllSslSocketFactory())
    }

    private fun sslConfigFor(allowInsecure: Boolean): SslConfig =
        if (allowInsecure) trustAllConfig else SslConfig.SystemDefault

    private fun OkHttpClient.Builder.applySsl(cfg: SslConfig): OkHttpClient.Builder = when (cfg) {
        SslConfig.SystemDefault -> this
        is SslConfig.TrustAll -> sslSocketFactory(cfg.factory, cfg.tm).hostnameVerifier { _, _ -> true }
    }

    /**
     * R-04: 共用的 base builder —— 含 connectTimeout、缓存、auth/directory/
     * traffic 拦截器，但 **不含** body size cap（只属于 REST）也 **不含**
     * readTimeout（REST 与 SSE 时长不同，由各自的 builder 指定）。
     */
    private fun baseBuilder(allowInsecure: Boolean): OkHttpClient.Builder {
        return OkHttpClient.Builder()
            .apply {
                // R-01: 按 profile 的 allowInsecure 开关决定是否 trust-all。
                applySsl(sslConfigFor(allowInsecure))
                // §16.1(a): attach the singleton cache (if resolvable) so it
                // is reused across host switches rather than rebuilt per
                // client. See [httpCache] for singleton rationale.
                httpCache?.let { cache(it) }
            }
            .addInterceptor(HttpLoggingInterceptor().apply {
                // R-07: release 关闭 HTTP 日志，避免 URL（含 session/文件路径）
                // 入 logcat。仅 DEBUG 构建开 BASIC。
                level = if (com.yage.opencode_client.BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                    else HttpLoggingInterceptor.Level.NONE
            })
            .addInterceptor { chain ->
                val original = chain.request()
                // A request carrying the skip-dir marker opts out of directory
                // injection (used by global endpoints like session list / agents).
                val skipDir = original.header(SKIP_DIR_HEADER) != null
                val dir = currentDirectory
                // §16.1(b) / §Stage D (gpter 阻塞 #2): cache-safety gate.
                // OkHttp's cache key is URL+method+Vary and IGNORES
                // Authorization / X-Opencode-Directory headers, so by default
                // every request carries `Cache-Control: no-store` to forbid
                // cross-user / cross-workdir pollution. Only 4 global
                // read-only endpoints (health/providers/agent/commands) opt
                // back into caching, AND only when NO Basic Auth is in effect:
                // the interceptor injects `Authorization: Basic ...` for any
                // profile with credentials, and since the cache key omits that
                // header, caching under one credential would leak cached
                // responses to a different credential against the same baseUrl.
                // When auth is configured we fall back to the safe default
                // (no-store for everything).
                val u = username
                val p = password
                val hasAuth = u != null && p != null
                // Exact-match the request path against the cache whitelist.
                // We strip the configured baseUrl's path prefix first so the
                // rule still holds when the API is deployed under a sub-path
                // (e.g. http://host/opencode/agent → relativePath "/agent").
                // Exact match (not endsWith) prevents future endpoints such as
                // /session/{id}/agent from accidentally hitting the whitelist.
                val relativePath = cacheRelativePath(original.url.encodedPath)
                val cacheable = !hasAuth && original.method == "GET" &&
                    relativePath in CACHEABLE_PATHS
                val request = original.newBuilder()
                    .apply {
                        if (skipDir) {
                            removeHeader(SKIP_DIR_HEADER)
                        }
                        // Inject the directory header unless the caller opted out.
                        // Only added when a workdir context is set.
                        if (!skipDir && dir != null) {
                            header(DIRECTORY_HEADER, dir)
                        }
                        // Basic Auth injection (unchanged behavior).
                        if (u != null && p != null) {
                            val credential = "$u:$p"
                            val encoded = Base64.getEncoder().encodeToString(credential.toByteArray())
                            header("Authorization", "Basic $encoded")
                        }
                        // §16.1(b): default-deny caching. Whitelisted global
                        // endpoints omit this header so OkHttp may cache their
                        // responses (subject to server ETag/Cache-Control — see
                        // §16.1 ETag gating note in the redesign plan).
                        if (!cacheable) {
                            header("Cache-Control", "no-store")
                        }
                    }
                    .build()
                chain.proceed(request)
            }
            // Traffic accounting: record request body (sent) and response body
            // Traffic accounting: record request body (sent) and ACTUAL bytes
            // read from the response body (received). The old impl read
            // contentLength(), which is -1 for chunked responses — so the large
            // chunked message responses (the very ones causing OOM) were counted
            // as 0 received, making the traffic counter grossly under-report and
            // hiding the real bandwidth. Now we wrap the body source with a
            // counting ForwardingSource that reports the true byte total when the
            // body is closed (after the converter has read it). SSE
            // (text/event-stream) is still counted — its bytes are real too.
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.encodedPath
                val method = request.method
                val sentBytes = request.body?.contentLength() ?: 0L
                val sent = if (sentBytes > 0L) sentBytes else 0L
                val startTime = System.currentTimeMillis()
                val response = chain.proceed(request)
                val elapsed = System.currentTimeMillis() - startTime
                val body = response.body
                if (body == null) {
                    trafficTracker.add(sent = sent, received = 0L)
                    trafficLogger.record(method, url, sent, 0L, elapsed)
                    return@addInterceptor response
                }
                val counter = object : okio.ForwardingSource(body.source()) {
                    var received = 0L
                    override fun read(sink: okio.Buffer, byteCount: Long): Long {
                        val read = super.read(sink, byteCount)
                        if (read > 0L) received += read
                        return read
                    }
                    override fun close() {
                        trafficTracker.add(sent = sent, received = received)
                        trafficLogger.record(method, url, sent, received, elapsed)
                        super.close()
                    }
                }
                val countedBody = object : okhttp3.ResponseBody() {
                    override fun contentType(): okhttp3.MediaType? = body.contentType()
                    override fun contentLength(): Long = body.contentLength()
                    override fun source(): okio.BufferedSource = counter.buffer()
                }
                response.newBuilder().body(countedBody).build()
            }
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
    }

    /**
     * R-04: REST client —— 在 [baseBuilder] 之上叠加 §OOM P0 body-size cap 与
     * 30s readTimeout（单次 API 调用足够，且能防止挂死连接）。
     */
    private fun buildRestClient(allowInsecure: Boolean): OkHttpClient {
        return baseBuilder(allowInsecure)
            // §OOM P0: response-size guard. The retrofit kotlinx-serialization
            // converter reads the WHOLE response body into a single String before
            // deserializing; a long agentic session's getMessages can embed tens
            // of MB of verbatim tool output, and a ~124MB body → a ~248MB char
            // array that OOMs the 256MB heap. Wrap every response body with a
            // counting source that throws IOException past the cap, routing the
            // call to Result.onFailure ("Response too large: …") instead of
            // crashing. Covers chunked (unknown length) responses too.
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = response.body ?: return@addInterceptor response
                // §OOM P0: response-size guard. The kotlinx-serialization converter
                // reads the WHOLE body into a String before parsing; a large
                // getMessages (chunked, unknown-length, 30 msgs × full tool output)
                // can be tens of MB and OOM the heap. ADB confirmed the OOM-time
                // responses are unknown-length (chunked) — so a Content-Length-only
                // check is insufficient. Strategy:
                //  1. Skip SSE (text/event-stream) — it's consumed incrementally,
                //     not buffered, so a byte cap would prematurely kill a long
                //     stream; SSE events are individually tiny. (R-04: SSE 现在走
                //     独立 sseHttp，此 guard 理论上遇不到 event-stream，保留为
                //     防御性兜底。)
                //  2. Fast path: known Content-Length within cap → pass through;
                //     over cap → close + throw before reading.
                //  3. Unknown length (-1, chunked): wrap the body source with a
                //     LAZY counting ForwardingSource that throws mid-read past the
                //     cap (no buffering, preserves streaming). This is the fix for
                //     the confirmed chunked-bypass OOM.
                val mediaType = body.contentType()
                if (mediaType != null && mediaType.subtype.contains("event-stream")) {
                    return@addInterceptor response
                }
                val len = body.contentLength()
                if (len in 1..MAX_RESPONSE_BYTES) return@addInterceptor response
                if (len > MAX_RESPONSE_BYTES) {
                    response.close()
                    throw java.io.IOException(
                        "Response too large (>${MAX_RESPONSE_BYTES / (1024 * 1024)}MB, Content-Length=$len): ${chain.request().url.encodedPath}"
                    )
                }
                // Unknown length: lazy counting source (throws past cap mid-read).
                val original = body.source()
                val counting = object : okio.ForwardingSource(original) {
                    var total = 0L
                    override fun read(sink: okio.Buffer, byteCount: Long): Long {
                        val read = super.read(sink, byteCount)
                        if (read > 0L) {
                            total += read
                            if (total > MAX_RESPONSE_BYTES) {
                                throw java.io.IOException(
                                    "Response too large (>${MAX_RESPONSE_BYTES / (1024 * 1024)}MB, streamed): ${chain.request().url.encodedPath}"
                                )
                            }
                        }
                        return read
                    }
                }
                val limitedBody = object : okhttp3.ResponseBody() {
                    override fun contentType(): okhttp3.MediaType? = body.contentType()
                    override fun contentLength(): Long = body.contentLength()
                    override fun source(): okio.BufferedSource = counting.buffer()
                }
                response.newBuilder().body(limitedBody).build()
            }
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * R-04: SSE client —— 在 [baseBuilder] 之上仅设 readTimeout(0)（SSE 长连接
     * 不能被 readTimeout 砍断），**不** 叠加 body-size cap（SSE 增量消费、逐
     * 事件都很小，字节上限会过早杀掉长流）。
     */
    private fun buildSseClient(allowInsecure: Boolean): OkHttpClient {
        return baseBuilder(allowInsecure)
            .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun trustAllSslSocketFactory(): SSLSocketFactory {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustAllTrustManager), SecureRandom())
        return context.socketFactory
    }

    private fun buildRetrofit(): Retrofit {
        val url = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
        return Retrofit.Builder()
            .baseUrl(url.trimEnd('/') + "/")
            .client(restHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Synchronized
    private fun rebuildClients() {
        restHttp = buildRestClient(currentAllowInsecure)
        sseHttp = buildSseClient(currentAllowInsecure)
        retrofit = buildRetrofit()
        api = retrofit.create(OpenCodeApi::class.java)
        sseClient = SSEClient(sseHttp)
    }

    /**
     * R-01: [allowInsecureConnections] 控制该 host 的 TLS 是否降级为 trust-all。
     * 由调用方（[configureRepositoryForProfile] / [applySavedSettings]）从当前
     * profile 传入；默认 false（仅信任系统证书）。
     */
    @Synchronized
    fun configure(
        baseUrl: String,
        username: String? = null,
        password: String? = null,
        allowInsecureConnections: Boolean = false
    ) {
        this.baseUrl = baseUrl
        this.username = username
        this.password = password
        this.currentAllowInsecure = allowInsecureConnections
        // Switching host invalidates any workdir context.
        currentDirectory = null
        rebuildClients()
    }

    /**
     * Set the current workdir directory context injected into directory-scoped
     * requests via the `X-Opencode-Directory` header. Pass null to clear it.
     */
    fun setCurrentDirectory(dir: String?) {
        currentDirectory = dir
    }

    /** Returns the currently configured workdir directory, or null when unset. */
    fun getCurrentDirectory(): String? = currentDirectory

    suspend fun checkHealth(): Result<HealthResponse> = runSuspendCatching { api.getHealth() }

    /**
     * One-shot health probe against [baseUrl] with optional Basic Auth, WITHOUT
     * mutating this repository's current configuration. Used by the host list's
     * per-row "test" action so a profile can be probed without switching hosts.
     *
     * R-01: [allowInsecure] 控制本次探测是否 trust-all（应由调用方按 profile 的
     * [HostProfile.allowInsecureConnections] 传入）。默认 false。
     *
     * Builds a throwaway OkHttp client and parses the same [HealthResponse] shape
     * served by `GET /global/health`.
     */
    suspend fun checkHealthFor(
        baseUrl: String,
        username: String? = null,
        password: String? = null,
        allowInsecure: Boolean = false
    ): Result<HealthResponse> = runSuspendCatching {
        val client = OkHttpClient.Builder()
            .apply { applySsl(sslConfigFor(allowInsecure)) }
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val normalizedUrl = (if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl")
            .trimEnd('/') + "/global/health"
        val requestBuilder = Request.Builder()
            .url(normalizedUrl)
            .header("X-Opencode-Skip-Dir", "1")
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

    suspend fun getSessions(limit: Int? = null): Result<List<Session>> = runSuspendCatching { api.getSessions(limit) }

    /**
     * Fetches the root sessions whose [Session.directory] exactly matches
     * [directory]. Uses the server's `?directory` + `?roots` query params
     * (priority: query param > header > cwd, per lib-1) so the result is
     * scoped to this workdir and excludes sub-agent (child) sessions.
     *
     * Used by [com.yage.opencode_client.ui.MainViewModel.createSessionInWorkdir]
     * to surface existing conversations in a newly-connected project, stored
     * separately in [com.yage.opencode_client.ui.AppState.directorySessions]
     * so periodic global refreshes do not discard it.
     */
    suspend fun getSessionsForDirectory(directory: String, limit: Int? = null): Result<List<Session>> =
        runSuspendCatching { api.getSessions(limit = limit, directory = directory, roots = true) }

    /**
     * Fetches a single session by ID. Used to resolve a child/sub-agent session
     * that may not be present in the cached [getSessions] list (e.g. when the
     * user navigates into a sub-agent before the parent's child list finished
     * loading). Best-effort: returns null on failure so callers can degrade
     * gracefully.
     */
    suspend fun getSession(sessionId: String): Result<Session> = runSuspendCatching { api.getSession(sessionId) }

    suspend fun createSession(title: String? = null): Result<Session> = runSuspendCatching {
        api.createSession(CreateSessionRequest(title = title))
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
     * via the `task` tool. Used by sub-agent cards and the parent->child
     * navigation flow.
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
     * `X-Next-Cursor` response header + the `before` query param). Pass
     * `before = null` for the first (latest) page; pass the previously returned
     * [MessagesPage.nextCursor] to fetch the next older page. The server returns
     * a bare `WithParts[]` array (V1 shape); the opaque cursor is read from the
     * response header. This replaces the old `messageLimit += 30` full re-fetch
     * anti-pattern that re-downloaded the entire growing window each loadMore.
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
     * id for [sessionId] (limit=1, desc default). Used by the catch-up path to
     * decide whether a full latest-5 reload is warranted after a disconnect /
     * foreground return — if the server's newest id equals the locally-known
     * newest, the reload (and its 5-message payload) is skipped entirely.
     * Returns null when the session has no messages.
     */
    suspend fun probeLatestMessageId(sessionId: String): Result<String?> = runSuspendCatching {
        val response = api.getMessages(sessionId, limit = 1, before = null)
        if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code()}")
        response.body()?.firstOrNull()?.info?.id
    }

    suspend fun sendMessage(
        sessionId: String,
        text: String,
        agent: String = "build",
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

    suspend fun getPendingQuestions(): Result<List<QuestionRequest>> = runSuspendCatching {
        api.getPendingQuestions()
    }

    suspend fun replyQuestion(requestId: String, answers: List<List<String>>): Result<Unit> = runSuspendCatching {
        val response = api.replyQuestion(requestId, QuestionReplyRequest(answers))
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Reply failed ${response.code()}: $errorBody")
        }
    }

    suspend fun rejectQuestion(requestId: String): Result<Unit> = runSuspendCatching {
        val response = api.rejectQuestion(requestId)
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Reject failed ${response.code()}: $errorBody")
        }
    }

    suspend fun getProviders(): Result<ProvidersResponse> = runSuspendCatching { api.getProviders() }

    suspend fun getAgents(): Result<List<AgentInfo>> = runSuspendCatching { api.getAgents() }

    /**
     * Lists the server-defined slash commands. Combined in the ViewModel with
     * a small set of client-side commands (/clear, /compact, /undo, /redo) to
     * drive the composer's `/`-autocomplete.
     */
    suspend fun getCommands(): Result<List<CommandInfo>> = runSuspendCatching { api.getCommands() }

    /**
     * Executes a slash command against [sessionId]. The current workdir context
     * (set when the session was selected) is injected automatically by the OkHttp
     * interceptor; callers do NOT need to pass it explicitly.
     *
     * Returns a typed error on non-2xx responses so the ViewModel can surface
     * the server's error body to the user.
     */
    suspend fun executeCommand(
        sessionId: String,
        command: String,
        arguments: String = "",
        agent: String? = null
    ): Result<Unit> = runSuspendCatching {
        val response = api.executeCommand(
            sessionId,
            CommandRequest(command = command, arguments = arguments, agent = agent)
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

    suspend fun getFileTree(path: String? = null): Result<List<FileNode>> = runSuspendCatching {
        api.getFileTree(path ?: "")
    }

    /**
     * Lists the contents of an arbitrary [directory] (independent of the
     * currently selected session's workdir). Used by the "connect new project"
     * directory picker to browse the server's filesystem starting at `~`.
     *
     * Bypasses the OkHttp interceptor's automatic `X-Opencode-Directory`
     * injection by setting the skip-dir marker on the underlying call, then
     * supplies [directory] explicitly so the server scopes the listing there.
     * [path] is an optional sub-path relative to [directory] (empty = list the
     * directory root).
     */
    suspend fun getFileTreeForDirectory(
        directory: String,
        path: String? = null
    ): Result<List<FileNode>> = runSuspendCatching {
        api.getFileTreeForDirectory(directory, path ?: "")
    }

    suspend fun getFileContent(path: String): Result<FileContent> = runSuspendCatching {
        api.getFileContent(path)
    }

    suspend fun getFileStatus(): Result<List<FileStatusEntry>> = runSuspendCatching {
        api.getFileStatus()
    }

    suspend fun findFile(query: String, limit: Int = 50): Result<List<String>> = runSuspendCatching {
        api.findFile(query, limit)
    }

    fun connectSSE(): Flow<Result<SSEEvent>> = sseClient.connect(baseUrl, username, password)

    /**
     * Activates a tunnel by POSTing the password to the tunnel endpoint.
     * Uses an independent OkHttpClient without any Basic Auth interceptor,
     * since tunnel authentication uses form-encoded POST (not HTTP Basic Auth).
     *
     * R-01: [allowInsecure] 控制隧道 client 是否 trust-all，应由调用方按激活时
     * 的 profile.allowInsecureConnections 传入。默认 false。
     */
    suspend fun activateTunnel(
        tunnelUrl: String,
        password: String,
        allowInsecure: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            try {
                val client = buildTunnelOkHttpClient(allowInsecure)
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
                // Preserve structured concurrency — never swallow cancellation
                // (ViewModel clear / scope cancel must propagate cleanly).
                // R-14: 外层已用 runSuspendCatching，正常情况这里不会触发；
                // 保留双保险语义。
                throw e
            } catch (e: Exception) {
                // HTTP failures already carry a clean "HTTP {code}: {body}"
                // message from the !successful branch above — rethrow as-is to
                // avoid a redundant "Exception: HTTP ..." double-wrap in the UI.
                // Only enrich network/IO/SSL failures (which often have null or
                // unhelpful messages) with type + message + cause.
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

    /**
     * R-01: 隧道 client 按 [allowInsecure] 决定 TLS 策略，复用统一的
     * [applySsl]/[sslConfigFor]。
     */
    private fun buildTunnelOkHttpClient(allowInsecure: Boolean): OkHttpClient {
        return OkHttpClient.Builder()
            .apply { applySsl(sslConfigFor(allowInsecure)) }
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * §Stage D (gpter 阻塞 #2): strips the configured baseUrl's path prefix
     * from [requestPath] so the cache whitelist can EXACT-match the canonical
     * endpoint shape even when the API is deployed behind a sub-path (e.g.
     * baseUrl `http://host/opencode` + request `/opencode/agent` → `/agent`).
     *
     * Returns [requestPath] unchanged when the baseUrl has no path prefix,
     * which keeps the bare-host deployment (the common case) on the same
     * exact-match behavior. Uses simple string slicing (consistent with
     * [buildRetrofit]'s `baseUrl.trimEnd('/') + "/"`) rather than okhttp's URL
     * parser so the helper stays dependency-free and unit-testable.
     */
    private fun cacheRelativePath(requestPath: String): String {
        val protocolEnd = baseUrl.indexOf("://")
        val hostStart = if (protocolEnd >= 0) protocolEnd + 3 else 0
        val pathStart = baseUrl.indexOf('/', hostStart)
        val basePath = if (pathStart >= 0) baseUrl.substring(pathStart).trimEnd('/') else ""
        return if (basePath.isNotEmpty() && requestPath.startsWith("$basePath/")) {
            requestPath.removePrefix(basePath)
        } else {
            requestPath
        }
    }

    // ---- Traffic debug ----

    fun flushTrafficLog() = trafficLogger.flushToDisk()
    fun dumpTrafficLog(): String = trafficLogger.dump()

    companion object {
        const val DEFAULT_SERVER = "http://localhost:4096"

        // §16.1(a): HTTP cache size cap (50 MB) per the redesign plan.
        private const val HTTP_CACHE_SIZE_BYTES = 50L * 1024 * 1024

        /**
         * §16.1(b) / §Stage D (gpter 阻塞 #2): relative request paths whose
         * GET responses are eligible for OkHttp caching. These 4 endpoints are
         * global / read-only / independent of user identity and workdir, so
         * caching them cannot leak data across users or directories. All other
         * endpoints (session, file, message, etc.) default to
         * `Cache-Control: no-store`.
         *
         * EXACT-MATCHED against the request path after stripping the baseUrl
         * prefix (see [cacheRelativePath]). Exact match — not `endsWith` — so
         * future endpoints such as `/session/{id}/agent` cannot accidentally
         * hit the whitelist. **New entries MUST be audited**: they are only
         * safe to add if the endpoint is read-only AND carries no
         * user/workdir-scoped data, AND the deployment never authenticates via
         * Basic Auth (the interceptor forces no-store whenever credentials are
         * configured).
         */
        private val CACHEABLE_PATHS = setOf(
            "/global/health",
            "/config/providers",
            "/agent",
            "/command",
        )

        // Header injected to scope a request to a workdir directory.
        const val DIRECTORY_HEADER = "X-Opencode-Directory"
        // Marker header carried on Retrofit methods (via @Headers) to opt out of
        // automatic directory injection for global / by-id endpoints. The
        // interceptor strips it before the request leaves the client.
        const val SKIP_DIR_HEADER = "X-Opencode-Skip-Dir"
    }
}
