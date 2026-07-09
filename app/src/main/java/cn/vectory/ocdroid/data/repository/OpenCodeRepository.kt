package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.api.SSEClient
import cn.vectory.ocdroid.data.api.*
import cn.vectory.ocdroid.data.api.v2.OpenCodeApiV2
import cn.vectory.ocdroid.data.api.v2.ModelInfoV2
import cn.vectory.ocdroid.data.api.v2.ProviderInfoV2
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
    private val directoryHeaderInterceptor = DirectoryHeaderInterceptor()
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
    private var commandHttp: OkHttpClient = clientFactory.commandClient(hostConfig.allowInsecure)
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
        restHttp = clientFactory.restClient(hostConfig.allowInsecure)
        sseHttp = clientFactory.sseClient(hostConfig.allowInsecure)
        retrofit = buildRetrofit(restHttp, hostConfig.baseUrl)
        api = retrofit.create(OpenCodeApi::class.java)
        // §grouping-rewrite item 4: rebuild the command-side client + Retrofit
        // so a host switch (configure) refreshes baseUrl / auth on it too.
        commandHttp = clientFactory.commandClient(hostConfig.allowInsecure)
        commandRetrofit = buildRetrofit(commandHttp, hostConfig.baseUrl)
        commandApi = commandRetrofit.create(OpenCodeApi::class.java)
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
     * §v2-catalog (P2 2B): builds the model catalog from the v2
     * `GET /api/model` + `GET /api/provider` endpoints. The legacy
     * `GET /config/providers` is no longer fetched — its response carried
     * provider API keys in the raw body.
     *
     * Returns the SAME [ProvidersResponse] shape downstream consumes (model
     * picker + context-limit index), so callers are unchanged:
     *  - [ConfigProvider.id] = v2 provider id; `name` = v2 provider name;
     *  - [ProviderModel.id]/`name`/`providerId` from the v2 model
     *    (`resolvedProviderId` falls through to `providerId`);
     *  - [ProviderModelLimit] merged from the v2 model's `limit`;
     *  - `defaultByProvider` empty (v2 has no default-provider concept; the
     *    app attaches the model per-prompt anyway).
     *
     * Models whose provider has no `/api/provider` entry still render (name
     * falls back to null → picker shows the provider id). Providers with no
     * models are absent (groupBy drops them).
     *
     * §v2-tolerant-catalog (0.6.1 round-1 fix, four-reviewer consensus):
     * decode is PER-ENTRY via `decodeFromJsonElement` + `runCatching` so a
     * single WRONG-TYPE entry (e.g. `limit.context = "abc"` string→Int,
     * `limit.context = {}` object→Int, or an out-of-Int-range number —
     * values `coerceInputValues` does NOT absorb) is SKIPPED + counted
     * instead of throwing and nuking the ENTIRE catalog. This was the 0.6.1
     * round-1 root cause: one such bad entry made the atomic
     * `List<ModelInfoV2>` decode throw → /api/model surfaced as failure →
     * providers=null → "服务器没有可用模型". ModelInfoV2/ProviderInfoV2
     * fields stay nullable as defense-in-depth (missing id/providerID still
     * absorbed as null + filtered below).
     *
     * §v2-catalog last-mile defense (kimo🟡-6, 0.6.1 round-1): if `/api/model`
     * or `/api/provider` returns a STRUCTURALLY corrupt payload (non-array
     * `data`, missing `data` key, etc.) — a failure even per-entry decode
     * cannot recover from — getProviders does NOT propagate failure (which
     * would surface as "没有可用模型" in the picker). It logs + returns an
     * EMPTY catalog (Result.success) so the picker shows an empty list
     * instead of an error state; the next refresh retries cleanly.
     */
    suspend fun getProviders(): Result<ProvidersResponse> {
        // §v2-catalog last-mile defense: catch structural envelope failures
        // (non-array data / HTTP/retrofit errors) and degrade to an empty
        // catalog instead of Result.failure. CancellationException MUST be
        // rethrown (structured concurrency — see runSuspendCatching).
        val rawModelElements: List<JsonElement> = try {
            apiV2.getModels().data
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("OpenCodeRepository", "v2 catalog: /api/model structural decode failure, returning empty catalog", e)
            return Result.success(ProvidersResponse(providers = emptyList()))
        }
        // §v2-tolerant-catalog: per-entry decode so a wrong-type entry is
        // skipped + counted (logged with a 200-char preview for diagnosis)
        // rather than nuking the whole list. coerceInputValues + nullable
        // fields still absorb missing/null id/providerID/null-limit values
        // inside each successfully-decoded entry; the runCatching here only
        // catches entries that throw despite those (i.e. type mismatches).
        val decodedModels = rawModelElements.mapNotNull { elem ->
            runCatching { json.decodeFromJsonElement<ModelInfoV2>(elem) }
                .onFailure {
                    DebugLog.w(
                        "OpenCodeRepository",
                        "v2 catalog: skipping unparseable model entry: ${elem.toString().take(200)}"
                    )
                }
                .getOrNull()
        }
        val unparseableModels = rawModelElements.size - decodedModels.size
        // enabled-filter is unchanged: legacy /config/providers had no such
        // field and showed everything; this only excludes an explicit server-
        // side disable. Combined with the missing-id/providerID blank-guard
        // (entries that can't render) into one filter pass.
        val models = decodedModels
            .filter { it.enabled && !it.id.isNullOrBlank() && !it.providerId.isNullOrBlank() }
        val droppedDisabledOrMissingId = decodedModels.size - models.size
        if (unparseableModels > 0 || droppedDisabledOrMissingId > 0) {
            DebugLog.w(
                "OpenCodeRepository",
                "v2 catalog: dropped $unparseableModels unparseable + $droppedDisabledOrMissingId disabled/missing-id of ${rawModelElements.size} model entries"
            )
        }

        val rawProviderElements: List<JsonElement> = try {
            apiV2.getProviders().data
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // §v2-tolerant-catalog round-2 (glm-2 🟠): /api/provider structural
            // failure must NOT discard the already-decoded /api/model list.
            // Degrade to an empty provider-name map (models keep rendering,
            // name=null → picker shows provider id, the existing fallback)
            // instead of returning an empty catalog. (The /api/model catch
            // above DOES return empty — there a structural failure means no
            // model data exists at all.)
            DebugLog.e("OpenCodeRepository", "v2 catalog: /api/provider structural decode failure; continuing with empty provider-name map", e)
            emptyList()
        }
        // §v2-tolerant-catalog: provider entries decoded per-entry as well,
        // for the same wrong-type-tolerance reason as models. A provider entry
        // with a non-array field where an array is expected would otherwise
        // throw and nuke the provider name map.
        val providerNameById = rawProviderElements.mapNotNull { elem ->
            runCatching { json.decodeFromJsonElement<ProviderInfoV2>(elem) }
                .onFailure {
                    DebugLog.w(
                        "OpenCodeRepository",
                        "v2 catalog: skipping unparseable provider entry: ${elem.toString().take(200)}"
                    )
                }
                .getOrNull()
        }
            .filter { !it.id.isNullOrBlank() }
            .associate { it.id!! to it.name }

        val configProviders = models.groupBy { it.providerId!! }.map { (providerId, ms) ->
            ConfigProvider(
                id = providerId,
                name = providerNameById[providerId],
                models = ms.associateBy { it.id!! }.mapValues { (_, m) ->
                    ProviderModel(
                        id = m.id!!,
                        name = m.name,
                        providerId = m.providerId!!,
                        limit = m.limit?.let { ProviderModelLimit(it.context, it.input, it.output) }
                    )
                }
            )
        }
        DebugLog.i("OpenCodeRepository", "v2 catalog: ${models.size} enabled model(s), ${providerNameById.size} provider(s)")
        return Result.success(ProvidersResponse(providers = configProviders))
    }

    /**
     * §model-selection: lists the server's available models via the v2
     * `GET /api/model` endpoint. Returns the `data` array (each entry carries
     * `id`, `providerID`, `name`, `enabled`, `limit`). NOTE: [getProviders]
     * above now builds the full catalog (incl. context-limit) from this
     * endpoint + `/api/provider`; this standalone [getModels] is retained for
     * debug/console use and has no production caller.
     *
     * §v2-tolerant-catalog (0.6.1 round-1): per-entry decode (same as
     * [getProviders]) so a wrong-type entry is skipped + logged instead of
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
