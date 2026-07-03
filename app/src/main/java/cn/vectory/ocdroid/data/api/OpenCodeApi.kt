package cn.vectory.ocdroid.data.api

import cn.vectory.ocdroid.data.model.*
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

    @POST("session")
    suspend fun createSession(@Body body: CreateSessionRequest = CreateSessionRequest()): Session

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
    // multi-directory topologies. DirectoryHeaderInterceptor now injects
    // X-Opencode-Directory for these (currentDirectory = the open session's dir).
    @GET("question")
    suspend fun getPendingQuestions(): List<QuestionRequest>

    @POST("question/{requestId}/reply")
    suspend fun replyQuestion(
        @Path("requestId") requestId: String,
        @Body body: QuestionReplyRequest
    ): Response<Unit>

    @POST("question/{requestId}/reject")
    suspend fun rejectQuestion(@Path("requestId") requestId: String): Response<Unit>

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
     * Executes a slash command against [sessionId]. The directory context is
     * inherited from the OkHttp interceptor (i.e. the session's workdir), so
     * this endpoint deliberately does NOT carry Skip-Dir.
     *
     * Returns Unit on success; failures surface through the Result wrapper in
     * the repository.
     */
    @POST("session/{id}/command")
    suspend fun executeCommand(
        @Path("id") sessionId: String,
        @Body body: CommandRequest
    ): Response<Unit>

    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("session/{id}/diff")
    suspend fun getSessionDiff(@Path("id") sessionId: String): List<FileDiff>

    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("session/{id}/todo")
    suspend fun getSessionTodos(@Path("id") sessionId: String): List<TodoItem>

    // file* endpoints deliberately omit Skip-Dir: they require the directory
    // context to resolve the correct workdir.
    @GET("file")
    suspend fun getFileTree(@Query("path") path: String? = ""): List<FileNode>

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

    @GET("file/content")
    suspend fun getFileContent(@Query("path") path: String): FileContent

    @GET("file/status")
    suspend fun getFileStatus(): List<FileStatusEntry>

    @GET("find/file")
    suspend fun findFile(
        @Query("query") query: String,
        @Query("limit") limit: Int = 50
    ): List<String>
}

@kotlinx.serialization.Serializable
data class CreateSessionRequest(
    val title: String? = null,
    @kotlinx.serialization.SerialName("parentID") val parentId: String? = null
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
    val agent: String = "build",
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
