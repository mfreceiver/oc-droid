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
import cn.vectory.ocdroid.data.repository.http.OkHttpClientFactory
import cn.vectory.ocdroid.data.repository.http.ResponseSizeGuardInterceptor
import cn.vectory.ocdroid.data.repository.http.SslConfigFactory
import cn.vectory.ocdroid.data.repository.http.TrafficCountingInterceptor
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import cn.vectory.ocdroid.util.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.Retrofit
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

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
    private val trafficLogger: TrafficLogger
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
    private val directoryHeaderInterceptor = DirectoryHeaderInterceptor(hostConfig)
    private val authInterceptor = AuthInterceptor(hostConfig)
    private val cacheControlInterceptor = CacheControlInterceptor(hostConfig, cachePathSanitizer)
    private val trafficCountingInterceptor = TrafficCountingInterceptor(trafficTracker, trafficLogger)
    private val responseSizeGuardInterceptor = ResponseSizeGuardInterceptor()
    private val clientFactory = OkHttpClientFactory(
        SslConfigFactory(),
        directoryHeaderInterceptor,
        authInterceptor,
        cacheControlInterceptor,
        trafficCountingInterceptor,
        responseSizeGuardInterceptor
    )

    private var restHttp: OkHttpClient = clientFactory.restClient(hostConfig.allowInsecure)
    private var sseHttp: OkHttpClient = clientFactory.sseClient(hostConfig.allowInsecure)
    private var retrofit: Retrofit = buildRetrofit(restHttp, hostConfig.baseUrl)
    private var api: OpenCodeApi = retrofit.create(OpenCodeApi::class.java)
    private var sseClient: SSEClient = SSEClient(sseHttp)

    /**
     * §model-selection: a SECOND Retrofit instance rooted at
     * `<baseUrl>/api/` for the v2 model endpoints (GET /api/model,
     * POST /api/session/{id}/model). Built with the SAME OkHttp [restHttp]
     * client as [api] so auth / cache / traffic interceptors apply uniformly.
     * Lives on its own interface ([OpenCodeApiV2]) and its own Retrofit root
     * so the legacy message path ([api].getMessages / promptAsync) is
     * untouched.
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
        restHttp = clientFactory.restClient(hostConfig.allowInsecure)
        sseHttp = clientFactory.sseClient(hostConfig.allowInsecure)
        retrofit = buildRetrofit(restHttp, hostConfig.baseUrl)
        api = retrofit.create(OpenCodeApi::class.java)
        v2Retrofit = buildV2Retrofit(restHttp, hostConfig.baseUrl)
        apiV2 = v2Retrofit.create(OpenCodeApiV2::class.java)
        sseClient = SSEClient(sseHttp)
    }

    /**
     * R-01: [allowInsecureConnections] controls whether this host's TLS is
     * downgraded to trust-all. Pass from the calling profile's
     * [HostProfile.allowInsecureConnections]; default false (system trust
     * store only).
     */
    @Synchronized
    fun configure(
        baseUrl: String,
        username: String? = null,
        password: String? = null,
        allowInsecureConnections: Boolean = false
    ) {
        hostConfig.configure(baseUrl, username, password, allowInsecureConnections)
        rebuildClients()
    }

    /**
     * Set the current workdir directory context injected into directory-scoped
     * requests via the `X-Opencode-Directory` header. Pass null to clear it.
     */
    fun setCurrentDirectory(dir: String?) = hostConfig.setCurrentDirectory(dir)

    /** Returns the currently configured workdir directory, or null when unset. */
    fun getCurrentDirectory(): String? = hostConfig.currentDirectory

    suspend fun checkHealth(): Result<HealthResponse> = runSuspendCatching { api.getHealth() }

    /**
     * One-shot health probe against [baseUrl] with optional Basic Auth, WITHOUT
     * mutating this repository's current configuration. Used by the host list's
     * per-row "test" action so a profile can be probed without switching hosts.
     *
     * R-01: [allowInsecure] controls whether this probe trust-all (caller
     * should pass per profile's [HostProfile.allowInsecureConnections]).
     * Default false.
     *
     * Builds a throwaway OkHttp client via [OkHttpClientFactory.healthClient]
     * (the SSL-trust shared entry point) and parses the same [HealthResponse]
     * shape served by `GET /global/health`.
     */
    suspend fun checkHealthFor(
        baseUrl: String,
        username: String? = null,
        password: String? = null,
        allowInsecure: Boolean = false
    ): Result<HealthResponse> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val client = clientFactory.healthClient(allowInsecure)
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

    /**
     * §model-selection: lists the server's available models via the v2
     * `GET /api/model` endpoint. Returns the `data` array (each entry carries
     * `id`, `providerID`, `name`, `enabled`). The legacy [getProviders]
     * (config/providers) is kept untouched and remains the source of truth
     * for the context-limit index.
     */
    suspend fun getModels(): Result<List<ModelInfoV2>> = runSuspendCatching { apiV2.getModels().data }

    suspend fun getAgents(): Result<List<AgentInfo>> = runSuspendCatching { api.getAgents() }

    /**
     * Lists the server-defined slash commands.
     */
    suspend fun getCommands(): Result<List<CommandInfo>> = runSuspendCatching { api.getCommands() }

    /**
     * Executes a slash command against [sessionId]. The current workdir context
     * is injected automatically by the OkHttp interceptor.
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
     * currently selected session's workdir). Used by the directory picker.
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

    fun connectSSE(): Flow<Result<SSEEvent>> =
        sseClient.connect(hostConfig.baseUrl, hostConfig.username, hostConfig.password)

    /**
     * Activates a tunnel by POSTing the password to the tunnel endpoint.
     * Uses an independent OkHttpClient without any Basic Auth interceptor,
     * since tunnel authentication uses form-encoded POST (not HTTP Basic Auth).
     *
     * R-01: [allowInsecure] controls whether the tunnel client trust-all.
     */
    suspend fun activateTunnel(
        tunnelUrl: String,
        password: String,
        allowInsecure: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            try {
                val client = clientFactory.tunnelClient(allowInsecure)
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
