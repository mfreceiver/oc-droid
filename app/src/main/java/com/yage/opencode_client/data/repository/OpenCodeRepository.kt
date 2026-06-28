package com.yage.opencode_client.data.repository

import com.yage.opencode_client.data.api.*
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
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.inject.Inject
import javax.inject.Singleton
import com.yage.opencode_client.util.TrafficTracker

/**
 * Hard cap on any single HTTP response body (32 MB). See the §OOM P0 interceptor
 * in [OpenCodeRepository.buildOkHttpClient] for why this exists.
 */
private const val MAX_RESPONSE_BYTES = 32L * 1024 * 1024

@Singleton
class OpenCodeRepository @Inject constructor(
    private val trafficTracker: TrafficTracker
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
     * inside `buildOkHttpClient()`.
     *
     * Resolves the cache directory from `java.io.tmpdir` (on Android this is
     * the app-private `/data/data/<pkg>/cache` dir, identical to
     * `Context.cacheDir`). Returns null when the directory is unavailable so
     * `buildOkHttpClient()` degrades gracefully (no cache) instead of
     * crashing — preserving the spec's safety contract.
     *
     * IMPORTANT: declared BEFORE [okHttpClient] (and friends below) because
     * the [okHttpClient] field initializer calls `buildOkHttpClient()`,
     * which reads this delegate. Property initializers run top-to-bottom, so
     * a `by lazy` declared after its first reader would still hold a null
     * delegate backing field at construction time.
     */
    private val httpCache: Cache? by lazy {
        val baseDir = System.getProperty("java.io.tmpdir")?.let(::File) ?: return@lazy null
        if (!baseDir.exists() && !baseDir.mkdirs()) return@lazy null
        // Silent degradation: if the cache dir is unwritable or the Cache
        // constructor throws, the client simply runs without a cache. We do
        // NOT android.util.Log here to keep this unit-testable without
        // returnDefaultValues/mocking — the pre-existing repository had no
        // Log usage and we preserve that invariant.
        runCatching { Cache(File(baseDir, "okhttp"), HTTP_CACHE_SIZE_BYTES) }
            .getOrNull()
    }

    private var okHttpClient: OkHttpClient = buildOkHttpClient()
    private var retrofit: Retrofit = buildRetrofit()
    private var api: OpenCodeApi = retrofit.create(OpenCodeApi::class.java)
    private var sseClient: SSEClient = SSEClient(okHttpClient)

    private fun buildOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .apply {
                sslSocketFactory(trustAllSslSocketFactory(), trustAllTrustManager)
                hostnameVerifier(HostnameVerifier { _, _ -> true })
                // §16.1(a): attach the singleton cache (if resolvable) so it
                // is reused across host switches rather than rebuilt per
                // client. See [httpCache] for singleton rationale.
                httpCache?.let { cache(it) }
            }
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
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
            // (received) byte counts. contentLength() returns -1 for unknown
            // lengths (chunked / SSE streams), which TrafficTracker.add skips,
            // so streaming responses are intentionally under-counted.
            .addInterceptor { chain ->
                val request = chain.request()
                val sentBytes = request.body?.contentLength() ?: 0L
                val response = chain.proceed(request)
                val receivedBytes = response.body?.contentLength() ?: 0L
                trafficTracker.add(
                    sent = if (sentBytes > 0L) sentBytes else 0L,
                    received = if (receivedBytes > 0L) receivedBytes else 0L
                )
                response
            }
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
                // §OOM P0: response-size guard. The retrofit kotlinx-serialization
                // converter reads the WHOLE body into a single String before
                // deserializing; a long session's getMessages can carry tens of
                // MB of verbatim tool output, and a ~124MB body → a ~248MB char
                // array that OOMs the 256MB heap. Aborting on Content-Length
                // BEFORE the body is consumed prevents the allocation and closes
                // the transfer early (saves cellular bandwidth too). getMessages
                // responses carry Content-Length; chunked/unknown (-1) pass through.
                val len = response.body?.contentLength() ?: -1L
                if (len > MAX_RESPONSE_BYTES) {
                    response.close()
                    throw java.io.IOException(
                        "Response too large (>${MAX_RESPONSE_BYTES / (1024 * 1024)}MB, Content-Length=$len): ${chain.request().url.encodedPath}"
                    )
                }
                response
            }
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
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
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Synchronized
    private fun rebuildClients() {
        okHttpClient = buildOkHttpClient()
        retrofit = buildRetrofit()
        api = retrofit.create(OpenCodeApi::class.java)
        sseClient = SSEClient(okHttpClient)
    }

    @Synchronized
    fun configure(baseUrl: String, username: String? = null, password: String? = null) {
        this.baseUrl = baseUrl
        this.username = username
        this.password = password
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

    suspend fun checkHealth(): Result<HealthResponse> = runCatching { api.getHealth() }

    /**
     * One-shot health probe against [baseUrl] with optional Basic Auth, WITHOUT
     * mutating this repository's current configuration. Used by the host list's
     * per-row "test" action so a profile can be probed without switching hosts.
     *
     * Builds a throwaway OkHttp client (trust-all, matching the main client's
     * TLS behavior) and parses the same [HealthResponse] shape served by
     * `GET /global/health`.
     */
    suspend fun checkHealthFor(
        baseUrl: String,
        username: String? = null,
        password: String? = null
    ): Result<HealthResponse> = runCatching {
        val client = OkHttpClient.Builder()
            .apply {
                sslSocketFactory(trustAllSslSocketFactory(), trustAllTrustManager)
                hostnameVerifier(HostnameVerifier { _, _ -> true })
            }
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

    suspend fun getSessions(limit: Int? = null): Result<List<Session>> = runCatching { api.getSessions(limit) }

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
        runCatching { api.getSessions(limit = limit, directory = directory, roots = true) }

    /**
     * Fetches a single session by ID. Used to resolve a child/sub-agent session
     * that may not be present in the cached [getSessions] list (e.g. when the
     * user navigates into a sub-agent before the parent's child list finished
     * loading). Best-effort: returns null on failure so callers can degrade
     * gracefully.
     */
    suspend fun getSession(sessionId: String): Result<Session> = runCatching { api.getSession(sessionId) }

    suspend fun createSession(title: String? = null): Result<Session> = runCatching {
        api.createSession(CreateSessionRequest(title = title))
    }

    suspend fun updateSession(sessionId: String, title: String): Result<Session> = runCatching {
        api.updateSession(sessionId, UpdateSessionRequest(title = title))
    }

    suspend fun updateSessionArchived(sessionId: String, archived: Long): Result<Session> = runCatching {
        api.updateSession(sessionId, UpdateSessionRequest(time = UpdateSessionTimeRequest(archived = archived)))
    }

    suspend fun deleteSession(sessionId: String): Result<Unit> = runCatching {
        api.deleteSession(sessionId)
    }

    suspend fun getSessionStatus(): Result<Map<String, SessionStatus>> = runCatching {
        api.getSessionStatus()
    }

    /**
     * Fetches the child (sub-agent) sessions spawned by [sessionId], typically
     * via the `task` tool. Used by sub-agent cards and the parent->child
     * navigation flow.
     */
    suspend fun getChildren(sessionId: String): Result<List<Session>> = runCatching {
        api.getChildren(sessionId)
    }

    suspend fun getMessages(sessionId: String, limit: Int? = null): Result<List<MessageWithParts>> =
        runCatching { api.getMessages(sessionId, limit) }

    suspend fun sendMessage(
        sessionId: String,
        text: String,
        agent: String = "build",
        model: Message.ModelInfo? = null,
        attachments: List<ComposerImageAttachment> = emptyList()
    ): Result<Unit> = runCatching {
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

    suspend fun abortSession(sessionId: String): Result<Unit> = runCatching {
        api.abortSession(sessionId)
    }

    suspend fun forkSession(sessionId: String, messageId: String? = null): Result<Session> = runCatching {
        api.forkSession(sessionId, ForkSessionRequest(messageId))
    }

    suspend fun revertSession(sessionId: String, messageId: String, partId: String? = null): Result<Session> = runCatching {
        api.revertSession(sessionId, RevertSessionRequest(messageId, partId))
    }

    suspend fun getPendingPermissions(): Result<List<PermissionRequest>> = runCatching {
        api.getPendingPermissions()
    }

    suspend fun respondPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse
    ): Result<Unit> = runCatching {
        api.respondPermission(sessionId, permissionId, PermissionResponseRequest(response.value))
    }

    suspend fun getPendingQuestions(): Result<List<QuestionRequest>> = runCatching {
        api.getPendingQuestions()
    }

    suspend fun replyQuestion(requestId: String, answers: List<List<String>>): Result<Unit> = runCatching {
        val response = api.replyQuestion(requestId, QuestionReplyRequest(answers))
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Reply failed ${response.code()}: $errorBody")
        }
    }

    suspend fun rejectQuestion(requestId: String): Result<Unit> = runCatching {
        val response = api.rejectQuestion(requestId)
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Reject failed ${response.code()}: $errorBody")
        }
    }

    suspend fun getProviders(): Result<ProvidersResponse> = runCatching { api.getProviders() }

    suspend fun getAgents(): Result<List<AgentInfo>> = runCatching { api.getAgents() }

    /**
     * Lists the server-defined slash commands. Combined in the ViewModel with a
     * small set of client-side commands (/clear, /compact, /undo, /redo) to
     * drive the composer's `/`-autocomplete.
     */
    suspend fun getCommands(): Result<List<CommandInfo>> = runCatching { api.getCommands() }

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
        arguments: Map<String, String> = emptyMap(),
        agent: String? = null
    ): Result<Unit> = runCatching {
        val response = api.executeCommand(
            sessionId,
            CommandRequest(command = command, arguments = arguments, agent = agent)
        )
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Command failed ${response.code()}: $errorBody")
        }
    }

    suspend fun getSessionDiff(sessionId: String): Result<List<FileDiff>> = runCatching {
        api.getSessionDiff(sessionId)
    }

    suspend fun getSessionTodos(sessionId: String): Result<List<TodoItem>> = runCatching {
        api.getSessionTodos(sessionId)
    }

    suspend fun getFileTree(path: String? = null): Result<List<FileNode>> = runCatching {
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
    ): Result<List<FileNode>> = runCatching {
        api.getFileTreeForDirectory(directory, path ?: "")
    }

    suspend fun getFileContent(path: String): Result<FileContent> = runCatching {
        api.getFileContent(path)
    }

    suspend fun getFileStatus(): Result<List<FileStatusEntry>> = runCatching {
        api.getFileStatus()
    }

    suspend fun findFile(query: String, limit: Int = 50): Result<List<String>> = runCatching {
        api.findFile(query, limit)
    }

    fun connectSSE(): Flow<Result<SSEEvent>> = sseClient.connect(baseUrl, username, password)

    /**
     * Activates a tunnel by POSTing the password to the tunnel endpoint.
     * Uses an independent OkHttpClient without any Basic Auth interceptor,
     * since tunnel authentication uses form-encoded POST (not HTTP Basic Auth).
     */
    suspend fun activateTunnel(tunnelUrl: String, password: String): Result<Unit> = runCatching {
        try {
            val client = buildTunnelOkHttpClient()
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
        } catch (e: Exception) {
            // Enrich network/IO/SSL failures with type + message + cause so the
            // UI surfaces something debuggable. OkHttp network errors often carry
            // a null/empty message, which previously collapsed to a useless
            // "Tunnel activation failed" fallback with no diagnostic value.
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

    private fun buildTunnelOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .sslSocketFactory(trustAllSslSocketFactory(), trustAllTrustManager)
            .hostnameVerifier { _, _ -> true }
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
