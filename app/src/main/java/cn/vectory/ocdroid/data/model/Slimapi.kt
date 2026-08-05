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
 * Cluster A: legacy slimapi permission entry shape (the V1 `GET /slimapi/permissions`
 * endpoint was removed in V2; the standard API `GET /permission` returns bare
 * [PermissionRequest] arrays). Kept for SSE/digest deserialization compatibility.
 * Same pattern as
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
 *  - [updatedAt]: epoch-ms of the latest message update. **v2 semantics**:
 *    sidecar wall-clock (was upstream `info.time`). Historically the
 *    `/slimapi/messages/{sid}/since/{ts}` anchor (§5 A2=A), but that endpoint
 *    is removed; `updatedAt` is now retained only as a marker-bookkeeping
 *    tuple分量 (paired with [messageId]) — **not** used for suppression
 *    (see docs/specs/slimapi-v2-adapt-traffic-plan.md §C2). Not monotonic
 *    across sidecar restart / NTP; treat as best-effort only.
 *  - [messageId]: the messageID of the latest update (paired with
 *    updatedAt for marker bookkeeping).
 *  - [status]: "idle" / "busy" (session.status).
 *
 * All fields nullable + defaulted so kotlinx.serialization decodes partial
 * frames (only the CHANGED fields are present) without MissingFieldException.
 * Field names match the v1 contract verbatim.
 */
/**
 * Cluster A (slimapi v0.2.2 client-adapt): the sidecar readiness scope
 * returned on the q/p aggregation envelope. Added so the client can tell
 * "sidecar allowlist not yet ready" ([directories] == 0) from
 * "authoritative empty across N ready directories" ([directories] > 0 &&
 * `items` empty). Without this signal, both cases decode to `items=[]` and
 * the [SlimAggregationOutcome.Success] full-replace branch falsely clears
 * stale local pending q/p state during the narrow startup window.
 *
 * - [directories]: count of workdirs the sidecar aggregated in this
 *   response (the size of its allowlist intersection at request time). 0
 *   means the sidecar's allowlist is empty / not ready — the client MUST
 *   treat the (possibly empty) `items` as non-authoritative and retain
 *   prior local state.
 *
 * **Null vs zero**: the field defaults to 0 (the value JSON `scope:{}`
 * yields if the object is present but the key is absent). When the WHOLE
 * `scope` key is absent (pre-0.2.2 sidecar), the parent aggregation DTO
 * keeps `scope = null` so the client preserves the original behavior.
 */
@Serializable
data class SlimapiScope(
    val directories: Int = 0,
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

/**
 * Wire shape of a single entry inside the `errors[]` array of
 * [SlimapiQuestionsEnvelope] (oc-slimapi `GET /slimapi/questions`).
 *
 * Distinct from [SlimapiAggregationError] (the decoded client model): the
 * wire DTO carries a non-null [directory] + [code] + optional [message],
 * matching the sidecar's `{directory, code, message?}` contract verbatim.
 * [cn.vectory.ocdroid.data.repository.gateway.InteractionGateway.getSlimapiQuestions]
 * maps each wire entry onto [SlimapiAggregationError] for the typed
 * [cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Partial].
 */
@Serializable
data class SlimapiAggregationErrorWire(
    val directory: String,
    val code: String,
    val message: String? = null,
)

/**
 * Wire envelope returned by `GET /slimapi/questions` (oc-slimapi thin route).
 *
 *  - [items]: each is the opencode `QuestionRequest` shape (incl. capital-ID
 *    `sessionID`) PLUS a per-item [SlimapiQuestionEntry.directory] string —
 *    the workdir the question originated from. Reuses [SlimapiQuestionEntry]
 *    verbatim (already matches the item shape).
 *  - [errors]: per-directory upstream failures; empty on full success.
 *  - [authoritativeDirectories]: `null` = globally authoritative (replace
 *    ALL local pending questions with [items]); a directory list = partial
 *    success (replace local only for those dirs, KEEP local for
 *    uncovered/failed dirs).
 *  - [scope]: sidecar readiness scope (`{directories:N}`), mirroring the
 *    permissions envelope. `N == 0` means the sidecar's allowlist is not
 *    ready yet — the (possibly empty) [items] MUST be treated as non-
 *    authoritative and the client retains prior local state (otherwise an
 *    empty response during startup would false-clear stale pending
 *    questions). Confirmed live: `GET /slimapi/questions` returns
 *    `{"items":[],"errors":[],"scope":{"directories":21}}` (v0.2.2 release
 *    test report §2.2). `null` (pre-0.2.2 sidecar / scope key absent) →
 *    original behavior.
 */
@Serializable
data class SlimapiQuestionsEnvelope(
    val items: List<SlimapiQuestionEntry> = emptyList(),
    val errors: List<SlimapiAggregationErrorWire> = emptyList(),
    val authoritativeDirectories: List<String>? = null,
    val scope: SlimapiScope? = null,
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
