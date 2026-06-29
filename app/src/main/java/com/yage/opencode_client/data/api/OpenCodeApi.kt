package com.yage.opencode_client.data.api

import com.yage.opencode_client.data.model.*
import kotlinx.serialization.json.JsonObject
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

    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("question")
    suspend fun getPendingQuestions(): List<QuestionRequest>

    @Headers("X-Opencode-Skip-Dir: 1")
    @POST("question/{requestId}/reply")
    suspend fun replyQuestion(
        @Path("requestId") requestId: String,
        @Body body: QuestionReplyRequest
    ): Response<Unit>

    @Headers("X-Opencode-Skip-Dir: 1")
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
     * small set of client-side commands (/clear, /compact, /undo, /redo) in
     * the ViewModel before being exposed to the UI.
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
 * Server-defined slash command metadata returned by GET /command. Used to drive
 * the composer's `/`-command autocomplete. The [hints] bag carries optional
 * argument schema and toolbar affordances; the client only reads a few keys.
 */
@kotlinx.serialization.Serializable
data class CommandInfo(
    val name: String,
    val description: String? = null,
    val agent: String? = null
    // §fix(command-recognition): the `hints` field is NOT declared here.
    // server 1.17.11 returns `hints` as a JSON ARRAY of strings (e.g.
    // ["$ARGUMENTS"]), but an earlier schema typed it as a CommandHints
    // OBJECT. That type mismatch made kotlinx.serialization throw on every
    // command entry → getCommands() failed → the client fell back to only the
    // 4 hardcoded local commands, so user-added / custom commands never
    // appeared in autocomplete. Since hints is unused client-side, we drop the
    // field entirely; ignoreUnknownKeys=true skips the server's hints safely.
)

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
