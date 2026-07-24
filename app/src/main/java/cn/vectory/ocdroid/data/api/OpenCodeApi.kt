package cn.vectory.ocdroid.data.api

import cn.vectory.ocdroid.data.model.*
import cn.vectory.ocdroid.data.repository.http.HttpHeaders
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import retrofit2.Response
import retrofit2.http.*

interface OpenCodeApi : StandardApi, SlimApi

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
