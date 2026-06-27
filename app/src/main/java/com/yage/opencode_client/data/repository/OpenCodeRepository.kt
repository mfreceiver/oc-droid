package com.yage.opencode_client.data.repository

import com.yage.opencode_client.data.api.*
import com.yage.opencode_client.data.model.*
import kotlinx.coroutines.flow.Flow
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.logging.HttpLoggingInterceptor
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

    private var okHttpClient: OkHttpClient = buildOkHttpClient()
    private var retrofit: Retrofit = buildRetrofit()
    private var api: OpenCodeApi = retrofit.create(OpenCodeApi::class.java)
    private var sseClient: SSEClient = SSEClient(okHttpClient)

    private fun buildOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .apply {
                sslSocketFactory(trustAllSslSocketFactory(), trustAllTrustManager)
                hostnameVerifier(HostnameVerifier { _, _ -> true })
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
                        val u = username
                        val p = password
                        if (u != null && p != null) {
                            val credential = "$u:$p"
                            val encoded = Base64.getEncoder().encodeToString(credential.toByteArray())
                            header("Authorization", "Basic $encoded")
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
        if (!response.isSuccessful) {
            val body = response.body?.string().orEmpty()
            throw Exception("Tunnel activation failed ${response.code}: $body")
        }
        response.close()
    }

    private fun buildTunnelOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .sslSocketFactory(trustAllSslSocketFactory(), trustAllTrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    companion object {
        const val DEFAULT_SERVER = "http://localhost:4096"

        // Header injected to scope a request to a workdir directory.
        const val DIRECTORY_HEADER = "X-Opencode-Directory"
        // Marker header carried on Retrofit methods (via @Headers) to opt out of
        // automatic directory injection for global / by-id endpoints. The
        // interceptor strips it before the request leaves the client.
        const val SKIP_DIR_HEADER = "X-Opencode-Skip-Dir"
    }
}
