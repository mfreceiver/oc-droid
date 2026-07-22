package cn.vectory.ocdroid.data.api

import cn.vectory.ocdroid.data.model.*
import cn.vectory.ocdroid.data.repository.http.HttpHeaders
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import retrofit2.Response
import retrofit2.http.*

interface OpenCodeApi {
    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("global/health")
    suspend fun getHealth(): HealthResponse

    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("session")
    suspend fun getSessions(
        @Query("limit") limit: Int? = null,
        @Query("directory") directory: String? = null,
        @Query("roots") roots: Boolean? = null
    ): List<Session>

    // §R18 Final 终审 fix (gpter): createSession is NON-Skip-Dir (it must route
    // to the target workdir's server instance). After Phase 2-E removed the
    // global currentDirectory fallback from DirectoryHeaderInterceptor, this
    // endpoint had NO directory source → POST /session routed to the server's
    // process cwd, creating sessions in the wrong workdir. Add an explicit
    // @Header so callers thread the directory (currentWorkdir / draftWorkdir).
    @POST("session")
    suspend fun createSession(
        @Body body: CreateSessionRequest = CreateSessionRequest(),
        @Header(HttpHeaders.DIRECTORY_HEADER) directory: String?
    ): Session

    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("session/{id}")
    suspend fun getSession(@Path("id") sessionId: String): Session

    /**
     * Returns the child (sub-agent) sessions spawned by [sessionId], e.g. via
     * the `task` tool. Used to render sub-agent cards and to navigate into a
     * child session's conversation.
     */
    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("session/{id}/children")
    suspend fun getChildren(@Path("id") sessionId: String): List<Session>

    @Headers("X-Opencode-Skip-Dir: 1")
    @PATCH("session/{id}")
    suspend fun updateSession(@Path("id") sessionId: String, @Body body: UpdateSessionRequest): Session

    @Headers("X-Opencode-Skip-Dir: 1")
    @DELETE("session/{id}")
    suspend fun deleteSession(@Path("id") sessionId: String): Response<Unit>

    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("session/status")
    suspend fun getSessionStatus(): Map<String, SessionStatus>

    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("api/session/active")
    suspend fun getActiveSessions(): ActiveSessionsResponse

    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("session/{id}/message")
    suspend fun getMessages(
        @Path("id") sessionId: String,
        @Query("limit") limit: Int? = null,
        @Query("before") before: String? = null
    ): retrofit2.Response<List<MessageWithParts>>

    @POST("session/{id}/prompt_async")
    suspend fun promptAsync(
        @Path("id") sessionId: String,
        @Body body: PromptRequest
    ): Response<Unit>

    @Headers("X-Opencode-Skip-Dir: 1")
    @POST("session/{id}/abort")
    suspend fun abortSession(@Path("id") sessionId: String): Response<Unit>

    /**
     * §context-compact: triggers server-side context compaction for [sessionId]
     * (POST /session/{id}/summarize). The server summarizes old messages to
     * free up context window. Returns true; compaction runs async and the
     * resulting message/part SSE events drive the message reload
     * automatically (the app already listens to SSE).
     *
     * Carries the same Skip-Dir marker as abort/fork/revert.
     */
    @Headers("X-Opencode-Skip-Dir: 1")
    @POST("session/{id}/summarize")
    suspend fun summarizeSession(
        @Path("id") sessionId: String,
        @Body body: SummarizeRequest
    ): Response<Boolean>

    @Headers("X-Opencode-Skip-Dir: 1")
    @POST("session/{id}/fork")
    suspend fun forkSession(
        @Path("id") sessionId: String,
        @Body body: ForkSessionRequest
    ): Session

    @Headers("X-Opencode-Skip-Dir: 1")
    @POST("session/{id}/revert")
    suspend fun revertSession(
        @Path("id") sessionId: String,
        @Body body: RevertSessionRequest
    ): Session

    @Headers("X-Opencode-Skip-Dir: 1")
    @POST("session/{id}/permissions/{permissionId}")
    suspend fun respondPermission(
        @Path("id") sessionId: String,
        @Path("permissionId") permissionId: String,
        @Body body: PermissionResponseRequest
    ): Response<Unit>

    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("permission")
    suspend fun getPendingPermissions(): List<PermissionRequest>

    // §P0 (question routing): NO Skip-Dir — these endpoints must carry the
    // directory header so the server routes to the InstanceState that owns the
    // pending question. /question/{id}/reply has no sessionID in the URL, so
    // the server cannot reverse-lookup session.directory (unlike /session/{id}
    // routes) and falls back to process.cwd() — which is wrong in tunnel /
    // multi-directory topologies. §R18 Phase 2-E step 1: callers now pass the
    // directory EXPLICITLY via @Header; the OkHttp interceptor preserves a
    // caller-supplied X-Opencode-Directory (it does NOT overwrite it with the
    // global workdir fallback), so the marker is intentionally absent (we
    // want the interceptor's query-mirror path to run too).
    @GET("question")
    suspend fun getPendingQuestions(
        @Header(HttpHeaders.DIRECTORY_HEADER) directory: String?
    ): List<QuestionRequest>

    @POST("question/{requestId}/reply")
    suspend fun replyQuestion(
        @Path("requestId") requestId: String,
        @Body body: QuestionReplyRequest,
        @Header(HttpHeaders.DIRECTORY_HEADER) directory: String?
    ): Response<Unit>

    @POST("question/{requestId}/reject")
    suspend fun rejectQuestion(
        @Path("requestId") requestId: String,
        @Header(HttpHeaders.DIRECTORY_HEADER) directory: String?
    ): Response<Unit>

    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("config/providers")
    suspend fun getProviders(): ProvidersResponse

    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("agent")
    suspend fun getAgents(): List<AgentInfo>

    /**
     * Lists commands supported by the server (e.g. /init, /review). Used by
     * the chat composer to drive `/`-command autocompletion. Combined with a
     * single client-side command (`/clear`) plus a few client-known commands
     * (/compact, /undo, /redo) in the ViewModel before being
     * exposed to the UI.
     *
     * Marked Skip-Dir: the command list is global and not scoped to a workdir.
     */
    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("command")
    suspend fun getCommands(): List<CommandInfo>

    /**
     * Executes a slash command against [sessionId]. §R18 Phase 2-E step 1:
     * the directory context is now supplied EXPLICITLY by the caller via
     * @Header(directory) (the session's workdir). The interceptor preserves
     * the caller-supplied header (it does NOT overwrite with the global
     * workdir fallback), so this method deliberately does NOT carry Skip-Dir.
     *
     * Returns Unit on success; failures surface through the Result wrapper in
     * the repository.
     */
    @POST("session/{id}/command")
    suspend fun executeCommand(
        @Path("id") sessionId: String,
        @Body body: CommandRequest,
        @Header(HttpHeaders.DIRECTORY_HEADER) directory: String?
    ): Response<Unit>

    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("session/{id}/diff")
    suspend fun getSessionDiff(@Path("id") sessionId: String): List<FileDiff>

    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("session/{id}/todo")
    suspend fun getSessionTodos(@Path("id") sessionId: String): List<TodoItem>

    // §R-17 batch4 / §R18 Phase 2-E step 2: file* endpoints carry an EXPLICIT
    // `@Query("directory")` + the `X-Opencode-Skip-Dir` marker. The directory
    // is supplied by the caller (no global state involved). The Skip-Dir
    // marker makes [DirectoryHeaderInterceptor] skip its workdir injection,
    // and the interceptor still mirrors `?directory` into the query for
    // proxy-safe routing when the caller supplies it via `@Header` (see
    // [getFileTreeForDirectory] for the @Header variant used by the picker).
    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("file")
    suspend fun getFileTree(
        @Query("path") path: String? = "",
        @Query("directory") directory: String? = null
    ): List<FileNode>

    /**
     * Variant of [getFileTree] for browsing an arbitrary directory that is
     * independent of the current session's workdir. Carries the
     * `X-Opencode-Skip-Dir` marker so the OkHttp interceptor does NOT overwrite
     * the explicit `X-Opencode-Directory` header we pass for the browse target.
     * Used by the "connect new project" directory picker.
     */
    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("file")
    suspend fun getFileTreeForDirectory(
        @Header("X-Opencode-Directory") directory: String,
        @Query("path") path: String? = ""
    ): List<FileNode>

    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("file/content")
    suspend fun getFileContent(
        @Query("path") path: String,
        @Query("directory") directory: String? = null
    ): FileContent

    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("file/status")
    suspend fun getFileStatus(@Query("directory") directory: String? = null): List<FileStatusEntry>

    // §vcs-section: v1 /vcs* endpoints mirror the file* directory-scoped GET
    // style — explicit `?directory` query + the X-Opencode-Skip-Dir marker so
    // DirectoryHeaderInterceptor skips its workdir injection (the directory is
    // supplied by the caller, no global state involved). Read-only.
    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("vcs")
    suspend fun getVcs(@Query("directory") directory: String?): VcsInfo

    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("vcs/status")
    suspend fun getVcsStatus(@Query("directory") directory: String?): List<VcsStatusEntry>

    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("vcs/diff")
    suspend fun getVcsDiff(
        @Query("mode") mode: String,
        @Query("directory") directory: String?
    ): List<FileDiff>

    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("find/file")
    suspend fun findFile(
        @Query("query") query: String,
        @Query("limit") limit: Int = 50,
        @Query("directory") directory: String? = null
    ): List<String>

    // ── Cluster A: oc-slimapi sidecar endpoints (v1 contract §2) ────────────
    //
    // All paths live under `/slimapi/`. The `X-Slimapi-Version` header is
    // injected by [cn.vectory.ocdroid.data.repository.http.SlimapiVersionInterceptor]
    // on the shared OkHttp chain (the same chain [api] uses), so each method
    // does NOT set it manually. `X-Opencode-Skip-Dir: 1` is set on every
    // slimapi method to make explicit these are NOT scoped by the
    // directory-header interceptor (slimapi scopes via ?directory where
    // relevant, never via X-Opencode-Directory).

    /**
     * Cluster A: cold-start session list (v1 contract §2). Skeleton rows —
     * each carries its own `directory` field so the client can filter
     * client-side. Defaults to excluding archived.
     *
     * `?directory` (repeated 0-32) optionally filters server-side; pass a
     * [List] of workdirs and Retrofit expands each entry to a separate
     * `?directory=...` query (contract: repeated params, NOT comma-joined).
     * null = all directories the sidecar is aggregating for this client.
     * `?roots` restricts to top-level sessions; `?limit` / `?search` mirror
     * legacy semantics.
     */
    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("slimapi/sessions")
    suspend fun getSlimapiSessions(
        @Query("directory") directories: List<String>? = null,
        @Query("roots") roots: Boolean? = null,
        @Query("limit") limit: Int? = null,
        @Query("search") search: String? = null
    ): retrofit2.Response<List<Session>>

    /**
     * Cluster A: anchor-paginated message fetch (v1 contract §5, A2=A).
     * Returns skeletons whose `time.updated >= {ts}`. The boundary message
     * IS included so the caller can dedup by messageID. `?limit` + `?before`
     * cursor paginate older pages.
     */
    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("slimapi/messages/{sid}/since/{ts}")
    suspend fun getSlimapiMessagesSince(
        @Path("sid") sessionId: String,
        @Path("ts") sinceTimestamp: Long,
        @Query("limit") limit: Int? = null,
        @Query("before") before: String? = null
    ): retrofit2.Response<List<MessageWithParts>>

    /**
     * Cluster A: cursor-paginated skeleton messages (v1 contract §2). Used
     * for the initial tail fetch when no anchor ts is known yet (mirrors
     * the legacy `session/{id}/message` pattern).
     *
     * `mode` (v1 contract §4 / G3): optional server-side expansion hint.
     * Slim-mode callers pass `mode = "skeleton"` for the lightweight tail
     * probe (and the cursor-paged skeleton fetch in T5); absent = server
     * default. Kept nullable + defaulted so the existing two-arg callers
     * (cold-start, since path) keep compiling byte-for-byte.
     */
    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("slimapi/messages/{sid}")
    suspend fun getSlimapiMessages(
        @Path("sid") sessionId: String,
        @Query("limit") limit: Int? = null,
        @Query("before") before: String? = null,
        @Query("mode") mode: String? = null
    ): retrofit2.Response<List<MessageWithParts>>

    /**
     * Cluster A: single-message full expansion (v1 contract §2). Loads one
     * message by id with `mode=full` semantics (server-side expand of the
     * skeleton).
     */
    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("slimapi/messages/{sid}/full/{mid}")
    suspend fun getSlimapiMessageFull(
        @Path("sid") sessionId: String,
        @Path("mid") messageId: String
    ): MessageWithParts

    /**
     * Cluster A (slimapi v1 §5 G6): batched message-full expansion. Loads
     * multiple messages by id in one round-trip
     * (`GET /slimapi/messages/{sid}/full?ids=m1,m2,…&mode=full`) — the
     * UI's "expand multiple collapsed thoughts/tools at once" path
     * (T15/T16) hits this to drop RTT vs N parallel single-full calls.
     *
     * Returns the `{items, errors}` envelope ([SlimapiMessageFullBatch])
     * so per-message failures ride alongside successes at HTTP 200
     * (`errors[].messageID` → the UI marks just that message's expand
     * state as failed). Whole-call failures (404 / 413 / 503 / 4xx)
     * surface as non-2xx and are routed by the repository's
     * [cn.vectory.ocdroid.data.repository.ExpandOutcome] sealed type.
     *
     * Wire contract §5 G6:
     *  - `ids`: comma-separated messageId list, 1–20 entries per call
     *    (server rejects more with HTTP 400 `invalid_ids`). The
     *    repository dedupes + truncates BEFORE the call — the wire
     *    value is already normalised.
     *  - `mode`: `skeleton` | `full` (default `full`); mirrors the
     *    single-message path's server-side expansion hint.
     *  - `directory` (v1 §2): optional server-side routing hint; null
     *    = the sidecar decides scope from the session id.
     *
     * **Transitional**: when the sidecar hasn't deployed the batch
     * endpoint yet, it returns HTTP 404 `thin_route_not_found`; the
     * repository's [ExpandOutcome] handling detects this and falls
     * back to N parallel single-full calls (bounded by a Semaphore),
     * so the UI's expand path works against both legacy and v1
     * sidecars without per-call branching.
     */
    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("slimapi/messages/{sid}/full")
    suspend fun getSlimapiMessagesFullBatch(
        @Path("sid") sessionId: String,
        @Query("ids") ids: String,
        @Query("mode") mode: String = "full",
        @Query("directory") directory: String? = null,
    ): retrofit2.Response<SlimapiMessageFullBatch>

    /**
     * Cluster A (slimapi v1 §6 G2): per-session status fetch
     * (`GET /slimapi/sessions/{sid}/status`). Returns the sidecar's
     * status envelope (`{"type":"busy"|"idle"|"retry", …}`) which
     * deserialises directly into [SessionStatus]. Returns the raw
     * [Response] so the repository can branch on HTTP status
     * (404 / 400 / 502 / 503 → StatusOutcome routing) AND detect 200 +
     * empty body as a protocol violation: the kotlinx-serialization
     * converter throws [java.io.EOFException] on an empty stream, which
     * the repo catches distinctly from real transport IOExceptions and
     * surfaces as [StatusOutcome.UpstreamWarn] (rev-gpt IMPORTANT #1).
     *
     * Distinct from the legacy bulk [getSessionStatus] aggregator
     * (`GET /session/status`) — that path stays untouched (T13 owns
     * slim fan-out, and explicitly forbids touching the bulk
     * StatusAggregator path). This is the NEW per-session slim
     * endpoint that drives T7 (reconcile) + T11 (StatusAggregator)
     * when polling a single session's status without fanning out.
     */
    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("slimapi/sessions/{sid}/status")
    suspend fun getSlimapiSessionStatus(
        @Path("sid") sessionId: String,
    ): Response<SessionStatus>

    /**
     * Cluster A (slimapi v1 §6 G2 / docs `slim-mode-api-routing.md` A7):
     * BULK per-directory status fetch (`GET /slimapi/sessions/status`).
     *
     * Returns the sidecar's per-directory status map — the SAME shape as the
     * legacy bulk [getSessionStatus] (`Map<String, SessionStatus>`), forwarded
     * verbatim from upstream `/session/status?directory=` (oc-slimapi
     * `routes/sessions.py:statuses` → `fetch_json_mapped`). T-R1 (slimapi R1)
     * replaces the legacy bulk endpoint for slim cold-start (foreground poll)
     * AND the L2Idle/L3 background disconnect fallback.
     *
     * `directory` is REQUIRED by the sidecar (FastAPI `directory: str` — a
     * missing/blank param 422s). A caller that needs multiple workdirs issues
     * one call per directory and merges; null is intentionally NOT accepted
     * (the server rejects it). Mirrors the per-session
     * [getSlimapiSessionStatus] declaration style (headers come from
     * interceptors; `X-Opencode-Skip-Dir` makes the directory-header
     * interceptor skip — slimapi scopes via `?directory`).
     *
     * Distinct from [getSessionStatus] (legacy `GET /session/status`, host-
     * global, no directory param) — that path stays untouched in legacy mode.
     */
    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("slimapi/sessions/status")
    suspend fun getSlimapiSessionsStatus(
        @Query("directory") directory: String,
    ): Response<Map<String, SessionStatus>>

    /**
     * Cluster A: cross-directory pending-questions aggregate (v1 contract §2).
     * Each entry carries its originating `directory` + `routeToken` for the
     * reply/reject response.
     *
     * `?directory` (repeated 1-32) optionally filters server-side; null =
     * all directories the sidecar is aggregating for this client.
     *
     * **Envelope**: the sidecar wraps the list as
     * `{"items": [...], "errors": [...]}` (oc-slimapi `_aggregate`), so the
     * Retrofit return type is [SlimapiQuestionAggregation], not a bare List.
     * The repository flattens `.items` before handing up.
     */
    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("slimapi/questions")
    suspend fun getSlimapiQuestions(
        @Query("directory") directories: List<String>? = null
    ): SlimapiQuestionAggregation

    /**
     * Cluster A: cross-directory pending-permissions aggregate (v1 contract §2).
     * Same shape / behaviour as [getSlimapiQuestions].
     */
    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("slimapi/permissions")
    suspend fun getSlimapiPermissions(
        @Query("directory") directories: List<String>? = null
    ): SlimapiPermissionAggregation

    /**
     * Cluster A: question reply (v1 contract §2 / B2). Body carries the
     * chosen answers PLUS the routeToken (the sidecar re-injects the
     * originating directory from the token, then forwards to opencode).
     *
     * **Contract assumption**: routeToken transport is BODY (not header) —
     * the v1 contract specifies the sidecar validates the token but does not
     * pin its wire location. Body transport was chosen as it co-locates with
     * the answer payload; flagged as a contract question in the task output.
     */
    @Headers("X-Opencode-Skip-Dir: 1")
    @POST("slimapi/questions/{qid}/reply")
    suspend fun replySlimapiQuestion(
        @Path("qid") questionId: String,
        @Body body: SlimapiQuestionReplyRequest
    ): Response<Unit>

    /** Cluster A: question reject — see [replySlimapiQuestion]. */
    @Headers("X-Opencode-Skip-Dir: 1")
    @POST("slimapi/questions/{qid}/reject")
    suspend fun rejectSlimapiQuestion(
        @Path("qid") questionId: String,
        @Body body: SlimapiQuestionRejectRequest
    ): Response<Unit>

    /**
     * Cluster A: permission response (v1 contract §2). Body carries the
     * `response: once|always|reject` PLUS routeToken; the sessionID is in
     * the URL path (the sidecar uses the path + token to route to the
     * owning opencode instance).
     */
    @Headers("X-Opencode-Skip-Dir: 1")
    @POST("slimapi/sessions/{sid}/permissions/{pid}")
    suspend fun respondSlimapiPermission(
        @Path("sid") sessionId: String,
        @Path("pid") permissionId: String,
        @Body body: SlimapiPermissionResponseRequest
    ): Response<Unit>
}

@kotlinx.serialization.Serializable
data class CreateSessionRequest(
    val title: String? = null,
    @kotlinx.serialization.SerialName("parentID") val parentId: String? = null
)

@kotlinx.serialization.Serializable
data class ActiveSessionsResponse(
    val data: Map<String, ActiveSession> = emptyMap(),
)

@kotlinx.serialization.Serializable
data class ActiveSession(
    val type: String? = null,
)

@kotlinx.serialization.Serializable
data class UpdateSessionRequest(
    val title: String? = null,
    val time: UpdateSessionTimeRequest? = null
)

@kotlinx.serialization.Serializable
data class UpdateSessionTimeRequest(
    val archived: Long? = null
)

@kotlinx.serialization.Serializable
data class PromptRequest(
    val parts: List<PartInput>,
    // §agent-default: null = 不指定，让服务端用其配置的默认 agent（如编排+glm-5.2）。
    // explicitNulls=false（OpenCodeRepository.json）会省略该字段，服务端按默认处理。
    val agent: String? = null,
    val model: ModelInput? = null
) {
    @kotlinx.serialization.Serializable
    data class PartInput(
        val type: String = "text",
        val text: String? = null,
        val mime: String? = null,
        val filename: String? = null,
        val url: String? = null
    )

    @kotlinx.serialization.Serializable
    data class ModelInput(
        @kotlinx.serialization.SerialName("providerID") val providerId: String,
        @kotlinx.serialization.SerialName("modelID") val modelId: String
    )
}

@kotlinx.serialization.Serializable
data class PermissionResponseRequest(
    val response: String
)

@kotlinx.serialization.Serializable
data class QuestionReplyRequest(
    val answers: List<List<String>>
)

@kotlinx.serialization.Serializable
data class ForkSessionRequest(
    @kotlinx.serialization.SerialName("messageID") val messageId: String? = null
)

@kotlinx.serialization.Serializable
data class RevertSessionRequest(
    @kotlinx.serialization.SerialName("messageID") val messageId: String,
    @kotlinx.serialization.SerialName("partID") val partId: String? = null
)

/**
 * §context-compact: body for POST /session/{id}/summarize. Carries the model
 * the server should use for the compaction summary. Mirrors the
 * providerID/modelID shape used by [PromptRequest.ModelInput].
 */
@kotlinx.serialization.Serializable
data class SummarizeRequest(
    @kotlinx.serialization.SerialName("providerID") val providerId: String,
    @kotlinx.serialization.SerialName("modelID") val modelId: String
)

/**
 * Server-defined slash command metadata returned by GET /command. Used to drive
 * the composer's `/`-command autocomplete. The [hints] bag carries optional
 * argument schema and toolbar affordances; the client only reads a few keys.
 */
@kotlinx.serialization.Serializable
data class CommandInfo(
    val name: String,
    val description: String? = null,
    val agent: String? = null,
    // ③ ServerCompat: `hints` is now captured (previously dropped — see the
    // history note below) as a raw [JsonElement] so the model never throws
    // regardless of the shape the server sends. Empirically (1.17.8–1.17.13)
    // the server emits `hints` as a JSON ARRAY of strings (e.g.
    // ["$ARGUMENTS"]); an earlier schema typed it as a [CommandHints] OBJECT.
    // Both forms (and any future third form) decode into JsonElement without
    // loss, and a typed view can be derived later when a UI consumer needs it
    // (see [hintsAsStringList]). Restoring the field also stops silently
    // discarding server data the client currently renders no opinion on.
    //
    // History: the field was previously NOT declared because the array-vs-
    // object mismatch made kotlinx.serialization throw on every command entry
    // → getCommands() failed → autocomplete fell back to 4 hardcoded local
    // commands. With `hints: JsonElement?` + ignoreUnknownKeys, neither shape
    // can break deserialization.
    val hints: JsonElement? = null
) {
    /**
     * Convenience typed view of [hints] when it is the current server's
     * array-of-strings form. Returns null for any other shape (object, scalar,
     * or absent) rather than throwing — callers should treat null as "no
     * usable hints". Returns the strings unfiltered.
     *
     * Tolerant parsing: only top-level [JsonPrimitive] elements are collected
     * as strings. Nested objects, arrays, and other non-primitive elements are
     * intentionally dropped rather than throwing, so callers should not expect
     * a 1:1 element count with the raw [hints] array.
     */
    val hintsAsStringList: List<String>?
        get() = (hints as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?.takeIf { it.isNotEmpty() }
}

@kotlinx.serialization.Serializable
data class CommandHints(
    /**
     * Optional UI affordances keyed by action name (e.g. "primary" -> "Run").
     * Opaque to the client; surfaced for future richer UI.
     */
    val actions: Map<String, String>? = null,
    /**
     * Optional argument descriptors. Kept as raw JsonObject because the server
     * schema is open-ended (per-argument validation rules, types, etc.).
     */
    val arguments: List<JsonObject>? = null,
    val locations: List<String>? = null,
    val enabled: Boolean? = null
)

/**
 * Body for POST /session/{id}/command. [arguments] is a free-form map keyed
 * by argument name; the client typically passes simple string values parsed
 * from the composer.
 */
@kotlinx.serialization.Serializable
data class CommandRequest(
    val command: String,
    // §fix(command-400): server 1.17.11 expects `arguments` as a JSON STRING
    // (the raw argument text), not an object/map. Sending `{}` 400'd every
    // server-forwarded slash command (/compact, /init, /review, …) with
    // "Expected string, got {}". The empty default is valid (no arguments).
    val arguments: String = "",
    val agent: String? = null
)

// ── Cluster A: oc-slimapi request bodies (v1 contract §2) ────────────────
//
// Slimapi-side reply/response bodies. Each carries the legacy fields PLUS
// a `routeToken` so the sidecar can validate the request and re-inject the
// originating directory before forwarding to opencode.
//
// **Contract assumption**: routeToken is sent in the BODY (not as a header).
// The v1 contract specifies the sidecar validates the token but does not
// pin its wire location; this client chose body transport to co-locate with
// the answer payload. Flagged as a contract question in the task output.

@kotlinx.serialization.Serializable
data class SlimapiQuestionReplyRequest(
    val answers: List<List<String>>,
    @kotlinx.serialization.SerialName("routeToken") val routeToken: String? = null,
)

@kotlinx.serialization.Serializable
data class SlimapiQuestionRejectRequest(
    @kotlinx.serialization.SerialName("routeToken") val routeToken: String? = null,
)

@kotlinx.serialization.Serializable
data class SlimapiPermissionResponseRequest(
    val response: String,
    @kotlinx.serialization.SerialName("routeToken") val routeToken: String? = null,
)
