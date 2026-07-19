package cn.vectory.ocdroid.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Cluster A (slim SSE + data layer): entry returned by `GET /slimapi/questions`
 * (cross-directory aggregate of pending questions). Mirrors the legacy
 * [QuestionRequest] shape with two additional slimapi-specific fields:
 *
 *  - [directory]: the workdir this question originated from (the sidecar
 *    aggregates across directories, so each entry MUST carry its own).
 *  - [routeToken]: the HMAC the sidecar binds to (kind + requestID +
 *    sessionID + directory, ~1h TTL). Returned to the sidecar on
 *    reply/reject so it can re-inject the directory and forward to the
 *    owning opencode instance (§2 B2).
 *
 * All fields nullable where the sidecar may omit them (forward-compat).
 * Field names mirror the legacy model so a future merge is mechanical.
 *
 * **Contract note**: the v1 contract (oc-slimapi/docs/v1-contract.md §2)
 * specifies that the routeToken is "下发" (delivered alongside) each entry,
 * but does NOT pin the exact transport (header vs body) for the reply/reject
 * request. This client sends routeToken as a top-level field of the request
 * body — see [cn.vectory.ocdroid.data.api.SlimapiQuestionReplyRequest].
 */
@Serializable
data class SlimapiQuestionEntry(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val questions: List<QuestionInfo> = emptyList(),
    val tool: QuestionRequest.ToolRef? = null,
    /** Originating workdir (sidecar aggregates across directories). */
    val directory: String? = null,
    /** HMAC the sidecar validates on reply/reject (~1h TTL). */
    @SerialName("routeToken") val routeToken: String? = null,
)

/**
 * Cluster A: entry returned by `GET /slimapi/permissions`. Same pattern as
 * [SlimapiQuestionEntry] — legacy [PermissionRequest] shape plus [directory]
 * + [routeToken] for the sidecar's cross-directory routing.
 */
@Serializable
data class SlimapiPermissionEntry(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val permission: String? = null,
    val patterns: List<String>? = null,
    val metadata: PermissionRequest.Metadata? = null,
    val always: List<String>? = null,
    val tool: PermissionRequest.ToolRef? = null,
    /** Originating workdir (sidecar aggregates across directories). */
    val directory: String? = null,
    /** HMAC the sidecar validates on the response POST (~1h TTL). */
    @SerialName("routeToken") val routeToken: String? = null,
)

/**
 * Cluster A: frame content of a `session.digest` SSE event emitted by
 * `GET /slimapi/events`. Per the v1 contract §3, each digest carries
 * sessionID + directory + ONLY the fields that changed (debounced
 * 250 ms / session). The reducer
 * ([cn.vectory.ocdroid.data.repository.reduceSlimDigest]) treats absent
 * fields as "no information" — only present fields mutate local state.
 *
 *  - [archived]: epoch-ms timestamp when the session was archived
 *    (`info.time.archived`); null = no change. >0 means archived (hidden
 *    from the session list).
 *  - [deleted]: emitted on `session.deleted`; null = no change.
 *  - [updatedAt]: epoch-ms of the latest message update — the
 *    `/slimapi/messages/{sid}/since/{ts}` anchor (§5 A2=A).
 *  - [messageId]: the messageID of the latest update (paired with
 *    updatedAt for boundary dedup).
 *  - [status]: "idle" / "busy" (session.status).
 *
 * All fields nullable + defaulted so kotlinx.serialization decodes partial
 * frames (only the CHANGED fields are present) without MissingFieldException.
 * Field names match the v1 contract verbatim.
 */
/**
 * Cluster A: envelope returned by `GET /slimapi/questions` and
 * `GET /slimapi/permissions` (oc-slimapi `routes/questions.py::_aggregate`).
 * The sidecar always returns `{"items": [...], "errors": [...]}` — a bare
 * list would have been simpler, but the sidecar needs to surface per-
 * directory upstream failures (e.g. one opencode down) without failing the
 * whole aggregate (status stays 200 unless ALL directories failed → 503).
 *
 * v1 client policy: surface [items] to the UI; log [errors] but do NOT
 * propagate per-directory failures to the user (the sidecar already degrades
 * gracefully — a partial result is preferable to no result). v2 may surface
 * a "1 directory unavailable" warning if metrics show it's actionable.
 *
 * **Contract**: oc-slimapi/docs/v1-contract.md §2 + design-v2 §1.7 + the
 * routing doc `docs/slim-mode-api-routing.md` line 273 already pin this
 * envelope — this client shape MUST mirror the sidecar's `_aggregate` output.
 */
@Serializable
data class SlimapiQuestionAggregation(
    val items: List<SlimapiQuestionEntry> = emptyList(),
    val errors: List<SlimapiAggregationError> = emptyList(),
)

/** Cluster A: permissions aggregate envelope — see [SlimapiQuestionAggregation]. */
@Serializable
data class SlimapiPermissionAggregation(
    val items: List<SlimapiPermissionEntry> = emptyList(),
    val errors: List<SlimapiAggregationError> = emptyList(),
)

/**
 * Cluster A: per-directory upstream failure reported inside an aggregation
 * envelope (questions / permissions). [directory] is the workdir that
 * failed; [code] is a machine-readable reason string emitted by the sidecar
 * (`upstream_http_<status>` / `upstream_timeout` / `upstream_error`).
 * Both fields nullable + defaulted for forward-compat.
 */
@Serializable
data class SlimapiAggregationError(
    val directory: String? = null,
    val code: String? = null,
)

@Serializable
data class SlimSessionDigest(
    @SerialName("sessionID") val sessionId: String,
    val directory: String? = null,
    val status: String? = null,
    @SerialName("messageID") val messageId: String? = null,
    val updatedAt: Long? = null,
    val archived: Long? = null,
    val deleted: Boolean? = null,
    /**
     * Task 1 (slimapi v1 §2 / §6.1) — three-state upstream-error field.
     * See [LastErrorField] + [LastErrorFieldSerializer] for the full
     * contract: ABSENT key → [LastErrorField.Omitted] (reducer preserves
     * prior banner, the default here); present-null →
     * [LastErrorField.Cleared]; present-object →
     * [LastErrorField.Set]. The custom serializer is invoked by
     * kotlinx ONLY when the key is present, which is what makes the
     * absent-vs-present-null distinction possible under the project's
     * `explicitNulls=false` Json config.
     */
    @Serializable(with = LastErrorFieldSerializer::class)
    val lastError: LastErrorField = LastErrorField.Omitted,
)
