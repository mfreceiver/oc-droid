package cn.vectory.ocdroid.data.api

import cn.vectory.ocdroid.data.model.*
import retrofit2.Response
import retrofit2.http.*

/**
 * Slim (oc-slimapi sidecar) API surface — every endpoint that routes through
 * the `/slimapi/` prefix.
 *
 * All methods extracted byte-for-byte from the original [OpenCodeApi] to
 * keep the proguard rule (`-keep interface cn.vectory.ocdroid.data.api.OpenCodeApi`)
 * stable: [OpenCodeApi] now extends this interface via composition and its
 * FQN is unchanged.
 *
 * ── Cluster A: oc-slimapi sidecar endpoints (v1 contract §2) ────────────
 *
 * All paths live under `/slimapi/`. The `X-Slimapi-Version` header is
 * injected by [cn.vectory.ocdroid.data.repository.http.SlimapiVersionInterceptor]
 * on the shared OkHttp chain (the same chain [api] uses), so each method
 * does NOT set it manually. `X-Opencode-Skip-Dir: 1` is set on every
 * slimapi method to make explicit these are NOT scoped by the
 * directory-header interceptor (slimapi scopes via ?directory where
 * relevant, never via X-Opencode-Directory).
 */
interface SlimApi {
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
