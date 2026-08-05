package cn.vectory.ocdroid.data.api

import cn.vectory.ocdroid.data.model.*
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
 * ── Retained endpoints (lite-v2-dev cleanup) ───────────────────────────
 *
 * - [getSlimapiSessions]           — GET /slimapi/sessions
 * - [getSlimapiMessages]           — GET /slimapi/messages/{sid}
 * - [getSlimapiMessageFull]        — GET /slimapi/messages/{sid}/full/{mid}
 * - [getSlimapiSessionsStatus]     — GET /slimapi/sessions/status?directory= (Plan-A, §3.1)
 *
 * All Tier 3 methods (batch / since / questions / permissions /
 * routeToken) have been removed. The `X-Slimapi-Version` header is
 * injected by [cn.vectory.ocdroid.data.repository.http.SlimapiVersionInterceptor]
 * on the shared OkHttp chain, so each method does NOT set it manually.
 * `X-Opencode-Skip-Dir: 1` is set on every slimapi method to make explicit
 * these are NOT scoped by the directory-header interceptor (slimapi scopes
 * via ?directory where relevant, never via X-Opencode-Directory).
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
     * §3.1 Plan-A: bulk per-directory session status with TurnRegistry turn merge.
     * `GET /slimapi/sessions/status?directory=<required>`. Forwards upstream
     * `/session/status` (busy/idle/retry) merged with turn/turnIncarnation. The
     * returned [SessionStatus] already carries `turn`+`turnIncarnation` (nullable,
     * absent on legacy/old sidecars). `X-Opencode-Skip-Dir: 1` — slimapi scopes via
     * ?directory, not the directory-header interceptor.
     *
     * P1-7: an old v2 sidecar predating Plan-A returns 404 here — the caller
     * ([OpenCodeRepository.getSlimapiSessionsStatus]) caches that via
     * [ServerCompatProfile.supportsSlimStatus] and falls back to the standard API.
     */
    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("slimapi/sessions/status")
    suspend fun getSlimapiSessionsStatus(
        @Query("directory") directory: String,
    ): retrofit2.Response<Map<String, SessionStatus>>

    /**
     * Catalog skeleton — global command list (oc-slimapi thin route). Returns
     * the whitelist `{name, description, agent?, hints?}`; dropped fields
     * (`template`/`source`/`model`/`subtask`) are absent server-side and were
     * never modeled client-side, so deserialization is a no-op shrink.
     *
     * Additive route: an old sidecar predating catalog returns 404
     * `thin_route_not_found` — the caller ([CatalogGateway.getCommands])
     * caches that via [cn.vectory.ocdroid.data.repository.ServerCompatProfile.useSlimCatalog]
     * and falls back to the standard `GET /command`. `X-Opencode-Skip-Dir: 1`
     * — catalog is global, not scoped by the directory-header interceptor.
     * `X-Slimapi-Version` is injected by
     * [cn.vectory.ocdroid.data.repository.http.SlimapiVersionInterceptor].
     */
    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("slimapi/command")
    suspend fun getSlimapiCommands(): retrofit2.Response<List<CommandInfo>>

    /**
     * Catalog skeleton — global agent list (oc-slimapi thin route). Returns
     * the whitelist `{name, description, mode, hidden?, native?}`; dropped
     * fields (`prompt`/`permission`/`topP`/`temperature`/`color`/`variant`/
     * `options`/`steps`/`model`) are absent server-side and never modeled
     * client-side. `mode`+`hidden` feed `AgentInfo.isVisible` (agent-picker
     * filter core) and are preserved.
     *
     * Additive route with the same 404 `thin_route_not_found` → legacy
     * fallback contract as [getSlimapiCommands] (see
     * [cn.vectory.ocdroid.data.repository.gateway.CatalogGateway.getAgents]).
     */
    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("slimapi/agent")
    suspend fun getSlimapiAgents(): retrofit2.Response<List<AgentInfo>>

    /**
     * Cross-directory pending-questions aggregate (oc-slimapi thin route).
     * Returns [SlimapiQuestionsEnvelope] — `items` are the opencode
     * `QuestionRequest` shape (incl. `sessionID` capital-ID SerialName) PLUS
     * a per-item `directory` string; `errors` carries per-directory upstream
     * failures; `authoritativeDirectories` is null when the response is
     * globally authoritative (replace ALL local pending) or a directory list
     * when only partial success (replace covered, keep others).
     *
     * **Bug fix**: cold-start in slim mode previously called legacy
     * `GET /question` with `directory = null`, which upstream resolves to
     * `process.cwd()` and silently hid pending questions belonging to other
     * workdirs. This sidecar endpoint aggregates across the configured
     * workdir allowlist so cold-start sees them all.
     *
     * Additive route with the same 404 `thin_route_not_found` → fallback
     * contract as [getSlimapiCommands]: on the first 404 the caller
     * ([cn.vectory.ocdroid.data.repository.gateway.InteractionGateway.getSlimapiQuestions])
     * caches that via
     * [cn.vectory.ocdroid.data.repository.ServerCompatProfile.supportsSlimQuestions]
     * (sticky-false) and throws so the call site falls back to per-dir
     * fan-out. `X-Opencode-Skip-Dir: 1` — aggregation is global, not scoped
     * by the directory-header interceptor. `X-Slimapi-Version` is injected by
     * [cn.vectory.ocdroid.data.repository.http.SlimapiVersionInterceptor].
     */
    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("slimapi/questions")
    suspend fun getSlimapiQuestions(): retrofit2.Response<SlimapiQuestionsEnvelope>
}
