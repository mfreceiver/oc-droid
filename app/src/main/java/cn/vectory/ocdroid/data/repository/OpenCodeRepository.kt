package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.api.SSEClient
import cn.vectory.ocdroid.data.api.*
import cn.vectory.ocdroid.data.api.v2.OpenCodeApiV2
import cn.vectory.ocdroid.data.api.v2.ModelInfoV2
import cn.vectory.ocdroid.data.model.*
import cn.vectory.ocdroid.data.repository.http.AuthInterceptor
import cn.vectory.ocdroid.data.repository.http.CacheControlInterceptor
import cn.vectory.ocdroid.data.repository.http.CachePathSanitizer
import cn.vectory.ocdroid.data.repository.http.DirectoryHeaderInterceptor
import cn.vectory.ocdroid.data.repository.http.HttpHeaders
import cn.vectory.ocdroid.data.repository.http.ClientCertMaterial
import cn.vectory.ocdroid.data.repository.http.OkHttpClientFactory
import cn.vectory.ocdroid.data.repository.http.ResponseSizeGuardInterceptor
import cn.vectory.ocdroid.data.repository.http.SlimapiContract
import cn.vectory.ocdroid.data.repository.http.SlimapiErrorCodes
import cn.vectory.ocdroid.data.repository.http.SslConfig
import cn.vectory.ocdroid.data.repository.http.SslConfigFactory
import cn.vectory.ocdroid.data.repository.http.TofuDecision
import cn.vectory.ocdroid.data.repository.http.TofuFailureReason
import cn.vectory.ocdroid.data.repository.http.TofuPinStore
import cn.vectory.ocdroid.data.repository.http.TofuValidation
import cn.vectory.ocdroid.data.repository.http.InMemoryTofuPinStore
import cn.vectory.ocdroid.data.repository.http.SlimapiVersionInterceptor
import cn.vectory.ocdroid.data.repository.http.SlimapiDebugInterceptor
import cn.vectory.ocdroid.data.repository.http.buildMutualTlsConfig
import cn.vectory.ocdroid.data.repository.http.hostPortFromUrl
import cn.vectory.ocdroid.data.repository.http.TrafficCountingInterceptor
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import cn.vectory.ocdroid.util.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.Retrofit
import java.io.IOException
import java.util.Base64
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton

// MessagesPage, SLIMAPI_DEFAULT_PAGE_LIMIT, SLIMAPI_LOCAL_HISTORY_BOUND moved to MessagesPage.kt

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
    private val trafficLogger: TrafficLogger,
    /**
     * §tofu R2: the TOFU pin store (persistent ESP-backed in production via
     * [cn.vectory.ocdroid.di.TofuModule]; [InMemoryTofuPinStore] in unit tests).
     * Held so [applyTofuDecision] can write Accept-once / Trust decisions and
     * [captureServerCert] / [checkHealthFor] can read pinned state via the
     * shared [sslConfigFactory]. Defaults to [InMemoryTofuPinStore] so the
     * test-locked 2-arg `OpenCodeRepository(mockk(), mockk())` construction
     * keeps compiling (Hilt ignores Kotlin defaults and injects the bound
     * [EspTofuPinStore][cn.vectory.ocdroid.data.repository.http.EspTofuPinStore]
     * in production).
     */
    private val tofuStore: TofuPinStore = InMemoryTofuPinStore(),
    /**
     * §slim-reconcile-lane-repo (B3 T5): the shared [ServerCompatProfile].
     * Used by [checkHealth]'s slim branch to feed
     * [ServerCompatProfile.updateSlimapi] from the parsed
     * `/slimapi/health` body — this closes the M2 self-check loop so that
     * Phase 3 bootstrap can read [ServerCompatProfile.isSlimapiClientAccepted]
     * and fail-close on version mismatch (C3 core).
     *
     * Defaults to a fresh instance so the test-locked 2-arg constructor
     * `OpenCodeRepository(mockk(), mockk())` keeps compiling; Hilt injects
     * the bound `@Singleton` instance in production so writes from this
     * repository and reads from ConnectionBootstrapEngine hit the SAME
     * profile (the M2 invariant). Same pattern as [tofuStore].
     */
    private val serverCompatProfile: ServerCompatProfile = ServerCompatProfile(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false  // Omit null fields - server rejects model: null
        encodeDefaults = true  // Include type in parts - server needs discriminator
    }

    /**
     * Test seam: last [ExpandBudgetCounters] from [expandMessagesFullBatch].
     * §L4a1/ζ-2: storage now lives in [expandBatchEngine]; this forwards
     * get/set so the existing test API (`repository.lastExpandBudgetCounters`)
     * resolves unchanged.
     */
    internal var lastExpandBudgetCounters: ExpandBudgetCounters?
        get() = expandBatchEngine.lastExpandBudgetCounters
        set(value) { expandBatchEngine.lastExpandBudgetCounters = value }

    /**
     * Test seam: injectable wall-clock budget (ms) for [expandMessagesFullBatch].
     * §L4a1/ζ-2: forwards to [expandBatchEngine] (storage there).
     */
    internal var expandWallClockBudgetMsForTest: Long?
        get() = expandBatchEngine.expandWallClockBudgetMsForTest
        set(value) { expandBatchEngine.expandWallClockBudgetMsForTest = value }

    /**
     * Test seam: injectable TTL (ms) for thin-route cache in `drivePartition`.
     * §L4a1/ζ-2: forwards to [expandBatchEngine] (storage there).
     */
    internal var thinRouteTtlMsForTest: Long?
        get() = expandBatchEngine.thinRouteTtlMsForTest
        set(value) { expandBatchEngine.thinRouteTtlMsForTest = value }

    // R-18: host profile + OkHttp composition extracted to dedicated classes.
    // Wired manually (rather than via Hilt) so the public constructor
    // signature stays `(TrafficTracker, TrafficLogger)` and the existing
    // `OpenCodeRepositoryTest` setup `OpenCodeRepository(mockk(), mockk())`
    // keeps working unchanged. The extracted components themselves are
    // `@Inject constructor @Singleton` for reuse / future DI migration.
    private val hostConfig = HostConfig()
    private val cachePathSanitizer = CachePathSanitizer(hostConfig)
    private val directoryHeaderInterceptor = DirectoryHeaderInterceptor()
    // R8 slim-mode foundation / M1: 版本头拦截器读 hostConfig.slim——必须在
    // hostConfig 之后实例化（构造顺序），但 hostConfig 的延迟初始化由 configure()
    // 完成，此处只持有引用。
    private val slimapiVersionInterceptor = SlimapiVersionInterceptor(hostConfig)
    // POST-RELEASE instrumentation: slimapi DEBUG-only logging interceptor.
    // Constructor-injected into OkHttpClientFactory just AFTER the version
    // interceptor (so the injected X-Slimapi-Version header is observable).
    private val slimapiDebugInterceptor = SlimapiDebugInterceptor()
    private val authInterceptor = AuthInterceptor(hostConfig)
    private val cacheControlInterceptor = CacheControlInterceptor(hostConfig, cachePathSanitizer)
    private val trafficCountingInterceptor = TrafficCountingInterceptor(trafficTracker, trafficLogger)
    private val responseSizeGuardInterceptor = ResponseSizeGuardInterceptor()
    // §2.4: 持有同一个 [SslConfigFactory] 实例——既给 clientFactory 构建 OkHttp client
    // （live REST/SSE/command/tunnel），也供本类的 [configure]/[checkHealthFor]/
    // [currentSslConfig] 解析 mTLS / TOFU pin。原先 inline `OkHttpClientFactory(
    // SslConfigFactory(), ...)` 每次构造一个独立 factory，configure 时注入的客户端
    // 证书无法被 healthClient 旧重载读到，也无法集中观测
    // [SslConfigFactory.lastClientCertError]。
    // §tofu R2: SslConfigFactory 现在需要 [TofuPinStore]——传入构造函数注入的 store，
    // 使生产 (ESP) / 测试 (InMemory) 都走同一 graph。
    private val sslConfigFactory = SslConfigFactory(tofuStore)
    private val clientFactory = OkHttpClientFactory(
        sslConfigFactory,
        directoryHeaderInterceptor,
        slimapiVersionInterceptor,
        slimapiDebugInterceptor,
        authInterceptor,
        cacheControlInterceptor,
        trafficCountingInterceptor,
        responseSizeGuardInterceptor
    )

    private var restHttp: OkHttpClient = clientFactory.restClient(hostConfig.hostPort)
    private var sseHttp: OkHttpClient = clientFactory.sseClient(hostConfig.hostPort)
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
     * differs. All other API methods stay on [api] / [mutationApi].
     *
     * **T14-C3** (`commandClient` retry): `executeCommand` is a POST, so
     * [commandHttp] sets `retryOnConnectionFailure = false` (see
     * [OkHttpClientFactory.commandClient]) — a network blip after the
     * server ran the slash command but before the ACK was read would
     * otherwise trigger a silent re-POST that re-runs the command.
     */
    private var commandHttp: OkHttpClient = clientFactory.commandClient(hostConfig.hostPort)
    private var commandRetrofit: Retrofit = buildRetrofit(commandHttp, hostConfig.baseUrl)
    private var commandApi: OpenCodeApi = commandRetrofit.create(OpenCodeApi::class.java)

    /**
     * T14 (CLIENT_CHANGES "mutation 只发一次，不因超时向 direct 重发"):
     * dedicated OkHttp client + Retrofit instance for **every POST wrapper
     * EXCEPT `executeCommand`** (which has its own [commandApi] for the 300 s
     * heavy-server-work window). Built on
     * [OkHttpClientFactory.mutationClient] (30 s read timeout — same as
     * [restHttp] for normal mutation latency; mutations don't need
     * command's 300 s window) + `retryOnConnectionFailure = false`.
     *
     * **Why a third Retrofit instance** — OkHttp's default
     * `retryOnConnectionFailure = true` can double-send a POST when a
     * connection breaks after the server applied the mutation but before
     * the client read the ACK. Routing POSTs through [restHttp] (which
     * keeps retry=true for safe idempotent GET retries) would re-introduce
     * that hazard. The [mutationApi] reuses the SAME [OpenCodeApi]
     * interface, baseUrl, JSON converter and interceptor chain (auth /
     * directory / slimapi-version / cache / traffic) as [api] — only the
     * OkHttp client's retry flag differs.
     *
     * **Routing table** (single source of truth, see
     * [OkHttpClientFactory.mutationClient]):
     *  - GET (every `api.getX` / `apiV2.getX`) → [api] / [apiV2]
     *    ([restHttp], retry=true).
     *  - POST `executeCommand` → [commandApi] ([commandHttp], 300 s,
     *    retry=false).
     *  - Every other POST → **[mutationApi]** ([mutationHttp], 30 s,
     *    retry=false). The 12 methods routed here: `createSession`,
     *    `promptAsync`, `abortSession`, `summarizeSession`, `forkSession`,
     *    `revertSession`, `respondPermission`, `replyQuestion`,
     *    `rejectQuestion`, `replySlimapiQuestion`, `rejectSlimapiQuestion`,
     *    `respondSlimapiPermission`.
     *
     * PATCH (`updateSession`/`updateSessionArchived`) and DELETE
     * (`deleteSession`) are NOT POSTs and stay on [api] — the T14 contract
     * is scoped to POST double-send risk; flag if a PATCH/DELETE double-send
     * hazard needs the same treatment in a follow-up.
     */
    private var mutationHttp: OkHttpClient = clientFactory.mutationClient(hostConfig.hostPort)
    private var mutationRetrofit: Retrofit = buildRetrofit(mutationHttp, hostConfig.baseUrl)
    private var mutationApi: OpenCodeApi = mutationRetrofit.create(OpenCodeApi::class.java)

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

    /**
     * Task 11 round-2 (oracle I3 — atomic state mutation boundary): the
     * single lock that serializes EVERY compound slim SSE state transition
     * in this repository. Held while a mutator reads `slimSseState.get`,
     * derives the new [SlimSessionState] via the T6 pure primitives
     * (`onReconcileSuccess` / `onReconcileFailure` / `clearLocal` /
     * `markDeleted` / `needsReconcile`) or the T6 reducer
     * (`reduceSlimDigest`), and writes `slimSseState.put` back.
     *
     * # Why this is needed (lost-update hazard)
     *
     * Pre-T11-round-2, each repo mutator did `get → derive → put` as THREE
     * separate calls. `SlimSseState`'s own `@Synchronized` per-call
     * serialization was insufficient: between the `get` and the `put` of
     * one mutator, ANOTHER thread (the SSE digest reducer racing a REST
     * success bump) could land a `put` carrying newer `remote*` watermarks.
     * The first mutator's stale `put` would then OVERWRITE the newer
     * `remote*` — a classic TOCTOU lost-update. The per-sid Mutex in the
     * coordinator protected only its own reconcile body; it could not
     * protect cold-start / direct repo callers, and the digest reducer
     * itself ran outside the mutex.
     *
     * The lock fixes this by making every compound transition atomic at
     * the repository layer (the single source of truth for state). All
     * callers (coordinator reconcile, digest reducer, cold-start,
     * host-switch clear) acquire it; the critical section is in-memory
     * read/derive/write ONLY — never held across network IO (REST fetches
     * happen OUTSIDE the lock; only the post-fetch state mutation holds
     * it).
     *
     * # Dirty re-evaluation inside the boundary (oracle I3 sub-point)
     *
     * The T6 pure primitives are UNCHANGED (per the locked T6 constraint)
     * and `onReconcileSuccess` always sets `dirty = false`. After applying
     * it to the freshest state inside the lock, the wrapper RE-EVALUATES
     * [needsReconcile]: if a digest advanced `remote*` during the REST
     * fetch (so `localApplied*` now trails `remote*` again), `dirty` MUST
     * ratchet back to `true`. Blindly trusting `onReconcileSuccess`'s
     * `dirty = false` would clear a real gap. See [bumpSlimBookmarkFromItems]
     * / [markSlimReconcileAligned] / [clearSlimLocalMessages] for the
     * re-evaluation sites.
     *
     * # D3 (epoch check) — release-gate gap
     *
     * TODO(release-gate): epoch check before commit (see
     * `.ocmar/workflows/slimapi-client-v1/problem-report-wip.md` C-D3). The
     * atomic boundary guarantees in-process consistency but does NOT
     * validate that the host epoch is still current at commit time. A host
     * switch mid-reconcile can land a stale-state `put` into the NEW host's
     * `slimSseState`. The fix requires the caller to capture an epoch /
     * [ConnectionIdentity] and the boundary to validate it before write —
     * out of scope for T11 (cross-cutting concurrency hardening; flagged
     * for a post-T18 hardening mini-task).
     */
    private val slimStateLock = Any()

    /**
     * §slim-sse-machine (T3 extracted): the slim state machine core.
     * Owns [slimSseState], [slimCommitMarker], [slimIncarnationReady].
     * Injected with [slimStateLock] for atomic boundary compatibility.
     */
    private val slimStateMachine = SlimSseStateMachine(slimStateLock)

    /**
     * Opaque capability for one configured slim-state incarnation (C-D3).
     *
     * Equality is referential through [marker]. [issuedReady] is captured
     * permanently at [captureSlimCommitToken] time: a token captured while
     * the incarnation was NOT ready (mid-reconfigure) remains invalid even
     * after [completeSlimReconfigure] later activates a new incarnation.
     *
     * Callers may capture and return the token to repository APIs, but
     * cannot manufacture a current token.
     */
    class SlimCommitToken internal constructor(
        internal val marker: Any,
        internal val issuedReady: Boolean,
    )

    class StaleSlimCommitException internal constructor() :
        java.io.IOException("stale or not-ready slim repository incarnation")

    /**
     * C-D3 rev-3 round-5 (oracle §1.2): handle returned by
     * [beginSlimReconfigure] and consumed by [configure]. It identifies the
     * not-yet-ready incarnation created at the transaction boundary so that
     * only the caller that BEGAN the reconfigure can COMPLETE it.
     *
     * Referential equality on [marker] keys the ownership check — a later
     * [beginSlimReconfigure] supersedes every prior ticket (its marker no
     * longer matches [slimCommitMarker]).
     *
     * Callers may pass the ticket to repository APIs, but cannot manufacture
     * one (constructor is `internal`).
     */
    class SlimReconfigureTicket internal constructor(internal val marker: Any)

    /**
     * C-D3 rev-3 round-5 (council): raised by [completeSlimReconfigure] /
     * [requireCurrentReconfigureTicket] when a ticket presented for
     * completion no longer matches the current slim incarnation — i.e. a
     * newer reconfigure transaction superseded it. Distinct from
     * [StaleSlimCommitException] (which is for stale commit tokens) so the
     * two failure modes don't share a type.
     *
     * NEVER re-arms a new transaction: the losing caller propagates the
     * throw and the winner (which owns the live ticket) is the one that
     * completes — fail-forward.
     */
    class SupersededSlimReconfigureException internal constructor() :
        java.io.IOException("superseded slim reconfigure transaction")

    /**
     * Rotated under [slimStateLock] by [beginSlimReconfigure] (and at the
     * start of [configure] as defense-in-depth). Same critical section
     * clears slimSseState so in-flight workflows carrying the previous
     * marker are rejected from that instant onward.
     */
    fun captureSlimCommitToken(): SlimCommitToken =
        slimStateMachine.captureSlimCommitToken()

    fun isSlimCommitTokenCurrent(token: SlimCommitToken): Boolean =
        slimStateMachine.isSlimCommitTokenCurrent(token)

    /**
     * C-D3 v2 §1.2: Runs a short, non-suspending commit atomically against
     * the current incarnation. The [commit] block MUST contain only in-memory
     * state/effect commits: no network, delay, blocking disk I/O, or suspend call.
     *
     * Returns `true` when [commit] ran (token was current), `false` when
     * the marker rotated first (the caller MUST treat as stale and
     * short-circuit).
     *
     * # TOCTOU mitigation (rev-gpt round-2 concern)
     *
     * Both the marker check and the [commit]() block run inside
     * [slimStateLock] — check-then-write is atomic w.r.t. rotation.
     * This closes the prior window where a repo commit + slice commit
     * could straddle a reconfigure rotation.
     */
    fun commitIfSlimTokenCurrent(
        token: SlimCommitToken,
        commit: () -> Unit,
    ): Boolean = slimStateMachine.commitIfSlimTokenCurrent(token, commit)

    /**
     * C-D3 v2 §1.2: Throws [StaleSlimCommitException] if [token] is no
     * longer the current repository incarnation. Used after every network
     * suspension (the marker may rotate while we were suspended on IO).
     */
    fun requireSlimTokenCurrent(token: SlimCommitToken) =
        slimStateMachine.requireSlimTokenCurrent(token)

    /**
     * C-D3 rev-3 reconfigure-boundary: SYNCHRONOUSLY invalidates the slim
     * repository incarnation (rotate marker + clear slim SSE state) under
     * [slimStateLock].
     *
     * # When to call
     *
     * Call at the **start** of every host-incarnation reconfigure
     * transaction — **after** [cn.vectory.ocdroid.service.identity.ConnectionIdentityStore.beginReconfigure]
     * (if used) and **before** any of:
     *  - [cn.vectory.ocdroid.ui.AppAction.HostStatePurged] / UI slice purge
     *  - settings / [HostConfig] mutation
     *  - [configure] network rewire
     *
     * This closes the purge→configure window where an old workflow could
     * still pass [commitIfSlimTokenCurrent] and write stale host state into
     * a purged new-host UI.
     *
     * # Fail-forward semantics
     *
     * If this method rotates the marker and a later [configure] fails, old
     * tokens stay stale (correct rejection) and new workflows re-capture
     * after a successful configure. There is **no** marker rollback — a
     * failed reconfigure leaves the incarnation advanced.
     *
     * Idempotent / re-entrant: calling twice produces a second rotation
     * (harmless). [configure] also invokes this at its entry as
     * defense-in-depth so a forgotten call site cannot re-open the
     * host-first window inside [configure] itself.
     *
     * @return a [SlimReconfigureTicket] identifying this transaction's
     *   not-yet-ready incarnation.
     */
    fun beginSlimReconfigure(): SlimReconfigureTicket =
        slimStateMachine.beginSlimReconfigure()

    /**
     * C-D3 rev-3 round-5 (oracle §1.4): asserts [ticket] still identifies
     * the current slim reconfigure transaction. Called by [configure] BEFORE
     * any host mutation so a stale/superseded ticket can't mutate state under
     * a wrong incarnation.
     *
     * Throws [SupersededSlimReconfigureException] if [ticket] was superseded
     * by a later [beginSlimReconfigure]; never re-arms a new transaction.
     */
    private fun requireCurrentReconfigureTicket(ticket: SlimReconfigureTicket) =
        slimStateMachine.requireCurrentReconfigureTicket(ticket)

    /**
     * C-D3 rev-3 readiness bit: re-arm [slimIncarnationReady] after a
     * successful [configure]. Called ONLY at the end of a fully-successful
     * configure transaction. If configure throws, readiness is deliberately
     * left false (fail-forward): no token can commit until a later successful
     * configure re-arms it.
     *
     * C-D3 rev-3 round-5 (oracle §1.4): ticket-ownership — only the ticket
     * that BEGAN the transaction can complete it. A superseded ticket
     * (superseded by a later [beginSlimReconfigure]) throws
     * [SupersededSlimReconfigureException] and NEVER re-arms readiness —
     * the winner's completion call owns that.
     */
    fun completeSlimReconfigure(ticket: SlimReconfigureTicket): Unit =
        slimStateMachine.completeSlimReconfigure(ticket)

    /**
     * §slim-reconcile-lane-repo (B2 T1): the live host's slim-mode flag.
     * True when the current [HostConfig] points at an oc-slimapi sidecar
     * entry (vs legacy opencode direct). Read by the in-repo `if(slim)`
     * branches ([getSessions] / [getSessionsForDirectory] /
     * [getMessagesPaged] / [getPendingPermissions]) to route REST calls to
     * the sidecar's `slimapi` endpoint family; Phase 2's
     * SessionSyncCoordinator reads this to decide between the slim SSE
     * digest loop vs the legacy polling pattern.
     *
     * Returns the live [HostConfig.slim] value (volatile read), so it
     * reflects the most recent [configure] call.
     */
     val isSlimMode: Boolean get() = hostConfig.slim

    // ── ι-A capability access surface (forwarders → ServerCompatProfile) ──
    // L4+ 消费者（协调/service/UI，多数已持 repository 句柄，部分以函数参数接收）
    // 通过这些**语义能力查询**读连接能力，而非裸 [isSlimMode]（raw mode）。
    // 这满足 plan §6「L4+ isSlimMode 零命中」验收：grep `isSlimMode` 在 L4+ 为空，
    // 这些读的是 capability（forwarder 透传到 [serverCompatProfile]，source-of-truth
    // 在 data.repository 层）。forwarder 是访问便利，非 mode 泄漏——返回的是语义能力，
    // 非 raw slim flag。详见 [ServerCompatProfile.supportsWatermarkResync] 等 KDoc
    // （mode-vs-readiness 区分：这些是 mode capability，非 health/readiness）。

    /** ι-A: 是否支持 watermark 重同步（= slim 连接）。L4+ 用此替代裸 `isSlimMode` 做重同步门。 */
    val supportsWatermarkResync: Boolean get() = serverCompatProfile.supportsWatermarkResync

    /** ι-A: 是否支持 token-stream 重同步（slim 连接 ∧ sidecar 公告 tokenStream）。 */
    val supportsTokenStreamResync: Boolean get() = serverCompatProfile.supportsTokenStreamResync

    /** ι-A: StatusAggregator 是否走 slim 扇出（vs legacy bulk `/session/status`）。 */
    val usesSlimStatusFanOut: Boolean get() = serverCompatProfile.usesSlimStatusFanOut

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
        restHttp = clientFactory.restClient(hostConfig.hostPort)
        sseHttp = clientFactory.sseClient(hostConfig.hostPort)
        retrofit = buildRetrofit(restHttp, hostConfig.baseUrl)
        api = retrofit.create(OpenCodeApi::class.java)
        // §grouping-rewrite item 4: rebuild the command-side client + Retrofit
        // so a host switch (configure) refreshes baseUrl / auth on it too.
        commandHttp = clientFactory.commandClient(hostConfig.hostPort)
        commandRetrofit = buildRetrofit(commandHttp, hostConfig.baseUrl)
        commandApi = commandRetrofit.create(OpenCodeApi::class.java)
        // T14: same for the mutation-side client + Retrofit — every POST
        // wrapper except `executeCommand` goes through mutationApi, so a host
        // switch MUST refresh baseUrl / auth / TOFU on it too.
        mutationHttp = clientFactory.mutationClient(hostConfig.hostPort)
        mutationRetrofit = buildRetrofit(mutationHttp, hostConfig.baseUrl)
        mutationApi = mutationRetrofit.create(OpenCodeApi::class.java)
        v2Retrofit = buildV2Retrofit(restHttp, hostConfig.baseUrl)
        apiV2 = v2Retrofit.create(OpenCodeApiV2::class.java)
        sseClient = SSEClient(sseHttp)
    }

    /**
     * §tofu R2: configure the live host. [hostPort] (the host:port authority
     * of [baseUrl]) replaces the legacy `allowInsecureConnections: Boolean`;
     * it keys the TOFU pin lookup so a previously-trusted endpoint's
     * TofuPinned config is applied to the rebuilt REST/SSE/command clients.
     * The caller may derive it via [hostPortFromUrl] (a null [hostPort] is
     * also resolved from [baseUrl] inside [HostConfig.configure]).
     *
     * §2.4: [clientCert] is the optional mTLS client certificate material
     * (PKCS12 + password + optional private CA). Loaded by the caller from
     * EncryptedSharedPreferences via
     * [cn.vectory.ocdroid.util.SettingsManager.loadClientCertMaterial] when
     * the active profile has `mtlsEnabled=true`. Default null → no client
     * cert (preserves source compatibility for pre-mTLS callers).
     * `configureClientCert(null)` clears any previously-held material so
     * switching from an mTLS profile to a plain profile stops presenting
     * the cert (no residue). MUST run before [rebuildClients] so the rebuilt
     * OkHttp clients pick up the new SSL config.
     */
    @Synchronized
    fun configure(
        baseUrl: String,
        username: String? = null,
        password: String? = null,
        hostPort: String? = null,
        clientCert: ClientCertMaterial? = null,
        /**
         * R8 slim-mode foundation: 当前 profile 是否启用省流模式（指向 oc-slimapi
         * sidecar）。透传给 [hostConfig.slim]，由 [SlimapiVersionInterceptor] 与
         * [checkHealth] / [checkHealthFor] / [captureServerCert] 路由使用。
         *
         * 默认 false（legacy 直连 opencode）——保持现有调用方（ui / service）行为
         * 完全不变；待 EffectiveConnectionConfig / 上游 controller 接入 slim 字段后
         * 端到端生效。
         */
        slim: Boolean = false,
        /**
         * C-D3 rev-3 round-5 (oracle §2.2): ticket-ownership for the slim
         * reconfigure transaction. Barrier callers pass the ticket created
         * BEFORE teardown / HostStatePurged / settings / profile mutation;
         * [configure] activates THAT incarnation on success. Direct/bootstrap
         * callers omit it — [configure] begins a fresh ticket at its first
         * instruction (defense-in-depth).
         *
         * Failure semantics (fail-forward): on throw, the ticket is NOT
         * completed; readiness stays false; no token can commit until a later
         * successful configure re-arms it. Superseded tickets (a later
         * [beginSlimReconfigure] ran between begin and configure) throw
         * [SupersededSlimReconfigureException] from
         * [requireCurrentReconfigureTicket] without re-arming anything.
         */
        reconfigureTicket: SlimReconfigureTicket? = null,
    ) {
        // C-D3 rev-3 round-5: ticket-ownership. Either consume the caller's
        // pre-begun ticket (barrier path — invalidation preceded purge /
        // settings / HostConfig mutation) or begin one right here (direct /
        // bootstrap path — defense-in-depth so configure itself never opens a
        // "host already changed, old token still current" window).
        val ticket = reconfigureTicket ?: beginSlimReconfigure()
        // Reject a ticket that was superseded between begin and configure
        // (or presented twice). NEVER re-arm a new transaction here — caller
        // propagates the failure.
        requireCurrentReconfigureTicket(ticket)
        try {
            // §2.4: MUST precede hostConfig.configure / rebuildClients so the
            // shared sslConfigFactory holds the new mTLS material when the live
            // REST/SSE/command clients are rebuilt (they read sslConfigFor(...)).
            sslConfigFactory.configureClientCert(clientCert)
            hostConfig.configure(baseUrl, username, password, hostPort, slim = slim)
            // Cluster A / T11: slim bookmarks were already cleared in
            // beginSlimReconfigure under slimStateLock. Only network rewire remains.
            rebuildClients()
            // C-D3 rev-3 readiness bit: only after every ssl/host/client step
            // succeeds do new tokens become valid. On throw, readiness stays
            // false (fail-forward) — no token can commit until a later
            // successful configure re-arms it. Ticket-ownership guarantees we
            // activate THIS transaction's incarnation, not a superseding one.
            completeSlimReconfigure(ticket)
            // ι-P1: rebuild session source to match the new connection mode.
            // 放在 completeSlimReconfigure 之后（与 ι-A setSlimConnection 同纪律：
            // 仅在整条事务全成功后才发布新 mode 的 source；throw 时 sessionSource
            // 保持先前 live source，与 slimConnection 不脱节）。api 已重建；
            // lambda 捕获 this，每次调用读最新 api（复用 SlimGetRepository 模式）。
            sessionSource = if (slim) SlimSessionSource({ api }) else StandardSessionSource({ api })
            // ι-P2: rebuild message source to match the new connection mode.
            // 与 sessionSource 同纪律：仅在 completeSlimReconfigure 之后（事务全成功）
            // 才发布新 mode 的 source；throw 时 messageSource 保持先前 live source。
            // SlimMessageSource 经注入的**纯函数 lambda** 访问共享态——
            //   • slimSessionUpdatedAt = { sid -> slimStateMachine.getSlimSessionState(sid)?.updatedAt ?: 0L }
            //     （只读 watermark，不暴露状态机对象给端口实现）
            //   • bumpBookmark = { sid, items, tok -> bumpSlimBookmarkFromItems(sid, items, tok) }
            //     （回调 OCR 自身 private 方法，内部 synchronized(slimStateLock)）
            // → **锁与 bookmark 状态不离开 OCR**（I5 保持）；SlimMessageSource 不持锁 /
            //   不持 slimStateMachine 对象。SlimCommitToken / StaleSlimCommitException
            //   仍嵌套于 OCR（I15 token threading 透传 + freeze 嵌套 FQN 不变）。
            messageSource = if (slim) SlimMessageSource(
                apiProvider = { api },
                slimSessionUpdatedAt = { sid -> slimStateMachine.getSlimSessionState(sid)?.updatedAt ?: 0L },
                bumpBookmark = { sid, items, tok -> bumpSlimBookmarkFromItems(sid, items, tok) },
            ) else StandardMessageSource({ api })
            // ι-A (capability read-model): 发布能力 mode 仅在整条 ssl/host/client/readiness
            // 事务全成功后——与 completeSlimReconfigure 的「readiness 仅每步成功后才发布」
            // 纪律一致（见上注释）。throw 时 slimConnection 保持先前值（= 仍 live 的旧连接
            // mode；新 mode 从未 live，故不应发布）。configure() 是 fail-forward（不回滚旧
            // hostConfig），但能力模型只反映"最近一次成功 live 的 mode"，与 readiness 同语义。
            //
            // 受管写点（I8 扩展）：本行是 setSlimConnection 的唯一受管调用方；与 probe
            // 写点（update/updateSlimapi，由 checkHealthFor / probeSlimapiHealth 尾部调用）
            // 并列。仍在 configure() @Synchronized monitor 内（I5/I6/I7 不变量保持）。
            // reconfigure 中途（新栈未确认前）slimConnection 仍报旧 mode = 仍 operative 的
            // 旧连接；L4+ 无锁读到的始终是"当前仍 live 的 mode"。mode 在此刻 authoritatively
            // 确立（= hostConfig.slim），不能从 serverCompatProfile 现有字段推导（legacy 模式
            // 下 slimapi* 全 null；slim 模式首次 health 成功前 slimapi* 也全 null）。
            serverCompatProfile.setSlimConnection(slim)
        } catch (error: Throwable) {
            // Deliberately do NOT complete: old host cannot be reinstated
            // safely after partial ssl/HostConfig/client mutation. A superseded
            // ticket (SupersededSlimReconfigureException from
            // completeSlimReconfigure) is also propagated — the loser must not
            // re-arm the winner's incarnation.
            throw error
        }
    }

    /**
     * §2.4: the current effective [SslConfig] for the live host (mTLS priority
     * over TOFU pin, SystemDefault safe fallback). Callers
     * ([HttpImageHolder] / cold-start image sync) use this to mirror the same
     * trust policy onto the markdown image client. `@Synchronized` because it
     * reads the mutable [sslConfigFactory] state that [configure] writes under
     * the same monitor (v3-glmer R2).
     *
     * §tofu R2: resolves via [SslConfigFactory.sslConfigFor] keyed by the
     * current [HostConfig.hostPort] (was `allowInsecure`).
     */
    @Synchronized
    fun currentSslConfig(): SslConfig = sslConfigFactory.sslConfigFor(hostConfig.hostPort)

    /**
     * §tofu fix: 当前 live SSL 配置是否走 mTLS 路径（客户端证书已配置并加载）。
     * TOFU 触发须跳过 mTLS 主机——mTLS 优先级会忽略 TOFU pin，弹"信任"是无效的
     * 误导；mTLS 服务器证书失败应直接作连接错误呈现。镜像 [sslConfigFor] 的
     * mTLS-priority 路由（SslConfig.kt sslConfigFor: mutualTlsConfig != null → MutualTLS）。
     */
    fun isMutualTlsActive(): Boolean = currentSslConfig() is SslConfig.MutualTLS

    /**
     * §fix-3 (gro-1#2/gpt-2#2/max-1 M1): 转发 [SslConfigFactory.lastClientCertError]。
     * 非空 = 最近一次 [configure] 注入的客户端证书材料试构建失败（p12 损坏 / CA 无法
     * 解析）→ mTLS 已降级回 SystemDefault，profile 仍宣称 mtlsEnabled。controller/UI
     * 据此显示「证书加载失败」而非泛化连接失败（防 fail-open 静默降级）。null = ok 或
     * 未配置 mTLS。
     */
    val lastClientCertError: String? get() = sslConfigFactory.lastClientCertError

    /**
     * §L4a1 (plan v3, Wave ζ): the extracted TOFU concern (capture probe +
     * decision application + pin read/clear), behavior-preserving extraction
     * out of this repository. The four public TOFU methods below are now thin
     * delegates to this instance so every existing caller
     * (ConnectionCoordinator / ConnectionBootstrapEngine / all tests)
     * resolves unchanged.
     *
     * **I4** — constructed with the SAME [tofuStore] passed to
     * [sslConfigFactory], so pin writes here are immediately visible to the
     * pin lookup in `SslConfigFactory.sslConfigFor`.
     *
     * **I9 / I7** — [TofuRepository] receives an [TofuRepository]`-wired`
     * `onTofuApplied` callback that calls this class's `@Synchronized`
     * [rebuildClients] **synchronously** (no coroutine dispatch) guarded by
     * `hostConfig.hostPort == hostPort` (original L857 semantics), so the pin
     * takes effect on the next SSL handshake and the rebuild stays serialized
     * with [configure]. [applyTofuDecision] is also `@Synchronized` on this
     * monitor — the delegate holds this monitor across write+rebuild, fully
     * preserving the original mutual exclusion with [configure] /
     * [currentSslConfig] (reentrant into [rebuildClients]).
     */
    private val tofuRepository = TofuRepository(
        tofuStore = tofuStore,
        onTofuApplied = { hostPort ->
            // §I9: synchronous rebuild ONLY for the currently-configured host
            // (original OCR L857 guard). rebuildClients() is itself
            // @Synchronized on this monitor; since applyTofuDecision's
            // delegate is also @Synchronized on this monitor, the call is
            // reentrant and pin-then-rebuild is atomic w.r.t. configure().
            if (hostConfig.hostPort == hostPort) {
                rebuildClients()
            }
        }
    )

    /**
     * §L4a1/ζ-2 (plan v3, Wave ζ): the extracted expand-batch engine
     * (budget-aware partition-tree expand + thin-route cache + single-flight
     * dedup). Behavior-preserving extraction; OCR keeps a thin
     * [expandMessagesFullBatch] delegate below so every existing caller
     * (PartExpandState / ExpandPartsUseCase / all tests) resolves unchanged.
     *
     * **I3** — `apiProvider = { api }` re-reads the `api` field on EVERY
     * expand call (a lambda that captures `this`, not a cached snapshot), so
     * [configure]'s [rebuildClients] reassignment of `api` takes effect on
     * the next expand. Caching `api` at construction would freeze a stale
     * client past a host switch (silent stale-client bug).
     *
     * **hostPort-live** — `hostPortProvider = { hostConfig.hostPort ?: "" }`
     * re-reads live; host switches re-key the engine's single-flight + cache.
     *
     * **I16** — `parseErrorCode` / `parseErrorCodeFromRaw` are passed in as
     * lambdas because they are SHARED OCR `internal fun`s (also used by the
     * drain / health / status paths) and MUST stay in OCR.
     */
    private val expandBatchEngine = ExpandBatchEngine(
        apiProvider = { api },
        hostPortProvider = { hostConfig.hostPort ?: "" },
        parseErrorCode = { parseErrorCode(it) },
        parseErrorCodeFromRaw = { parseErrorCodeFromRaw(it) },
        retryAfterHeaderToMs = { retryAfterHeaderToMs(it) },
    )

    /**
     * §L4a3: thin GET delegate — instantiated with [apiProvider] lambda
     * that reads the live [api] reference so host switches take effect.
     */
    private val slimGetRepository = SlimGetRepository(apiProvider = { api })

    /**
     * ι-P1: session 域端口—Standard 或 Slim 双实现,由 [configure] 在 client 重建后选束。
     * 默认 [StandardSessionSource]（legacy）与未 configure 行为一致。
     *
     * ι-4: @Volatile — 此为并发路由位([configure] @Synchronized monitor 内写,
     * [getSessions]/[getSessionsForDirectory] 无锁读);volatile 保证 reader 线程
     * 可见最新选束,接替原 isSlimMode(读 hostConfig._slim @Volatile)的并发路由职责。
     * (rev-4/rev-10 stage 收尾加固)
     */
    @Volatile
    private var sessionSource: SessionSource = StandardSessionSource({ api })

    /**
     * ι-P2: message 域端口—Standard 或 Slim 双实现,由 [configure] 在 client 重建后选束。
     * 默认 [StandardMessageSource]（legacy）与未 configure 行为一致。
     * [SlimMessageSource] 经注入 lambda 访问共享态（slimSessionUpdatedAt 只读
     * watermark + bumpBookmark 回调 OCR.bumpSlimBookmarkFromItems）——锁与 bookmark
     * 状态留 OCR（I5 保持），SlimMessageSource 不持锁 / 不持状态机对象。
     *
     * ι-4: @Volatile — 同 [sessionSource]，并发路由位（configure monitor 内写、
     * [getMessagesPaged] 等无锁读），volatile 保证 reader 可见最新选束。
     */
    @Volatile
    private var messageSource: MessageSource = StandardMessageSource({ api })

    // ── §tofu R2: capture probe + decision application ──────────────────────

    /**
     * §tofu R2: a captured leaf cert + its SPKI + the system-validation
     * classification. Surfaced to the UI as the trust-prompt payload; the UI's
     * [TofuDecision] is fed back via [applyTofuDecision].
     */
    data class TofuCaptureResult(
        val hostPort: String,
        val leaf: X509Certificate,
        val spkiHex: String,
        val validation: TofuValidation
    )

    /**
     * §tofu R2 / §L4a1: one-shot TLS handshake probe — DELEGATES to
     * [tofuRepository.captureServerCert] (extracted verbatim, including its
     * OWN one-shot OkHttpClient that is side-effect-free w.r.t. the live
     * REST/SSE/command clients — I3 holds). Kept on OCR's public surface so
     * every existing caller (ConnectionCoordinator /
     * ConnectionBootstrapEngine / tests) resolves unchanged — the compat
     * layer L4a3 will later formalize.
     *
     * Result type [TofuCaptureResult] STAYS nested here (I20: ~10 callers
     * reference `OpenCodeRepository.TofuCaptureResult`; Kotlin forbids member
     * typealiases, so the nested type is kept verbatim).
     */
    suspend fun captureServerCert(
        baseUrl: String,
        hostPort: String,
        clientCert: ClientCertMaterial? = null,
        slim: Boolean = false
    ): TofuCaptureResult? =
        tofuRepository.captureServerCert(baseUrl, hostPort, clientCert, slim)

    /**
     * §tofu R2 / §L4a1: applies the UI's [TofuDecision] — DELEGATES to
     * [tofuRepository.applyTofuDecision]. `@Synchronized` on THIS monitor is
     * RETAINED so the pin-then-rebuild sequence stays mutually exclusive with
     * [configure] / [currentSslConfig] / [rebuildClients] (I7 — full original
     * OCR-level serialization preserved, not merely the callback path). The
     * synchronous rebuild (I9) fires inside the delegate via the
     * `onTofuApplied` callback wired into [tofuRepository], which re-checks
     * `hostConfig.hostPort == hostPort` (original L857 guard) and calls this
     * class's [rebuildClients] — reentrant under this same held monitor.
     */
    @Synchronized
    fun applyTofuDecision(hostPort: String, decision: TofuDecision) =
        tofuRepository.applyTofuDecision(hostPort, decision)

    /**
     * §tofu R2 / §L4a1: query the current pinned SPKI for [hostPort] —
     * DELEGATES to [tofuRepository.pinnedSpkiFor] (reads the SAME shared
     * [tofuStore] that [sslConfigFactory] reads during SSL negotiation — I4).
     */
    fun pinnedSpkiFor(hostPort: String): String? = tofuRepository.pinnedSpkiFor(hostPort)

    /**
     * §tofu R2 / §L4a1: forget the pin for [hostPort] — DELEGATES to
     * [tofuRepository.clearTofuPin].
     */
    fun clearTofuPin(hostPort: String) = tofuRepository.clearTofuPin(hostPort)

    // §R18 Phase 2-E step 2: the deprecated setCurrentDirectory /
    // getCurrentDirectory forwarding helpers were removed. Non-file routes
    // (SSE / /question / /command) now take an explicit `directory` parameter
    // on the API method; DirectoryHeaderInterceptor no longer reads from
    // HostConfig. File routes already took explicit parameters (R-17 batch4).

    /**
     * R8 slim-mode foundation / C3 fix: health probe against the **current**
     * configured host. Routes by [HostConfig.slim]:
     *
     *  - slim=false（legacy）: 走 `GET /global/health` via Retrofit [api]
     *    ——行为与新增本字段前**完全一致**（无路径变化 / 无额外请求头）。
     *  - slim=true: 走裸 OkHttp `GET /slimapi/health`，带
     *    `X-Slimapi-Version` 头（M1 门闩，所有 `/slimapi/` 下路径必带——含
     *    health 自身；design-v2 §9.6）。**不**走 `/global/health`——后者经
     *    slimapi catch-all 透传到 opencode，sidecar 挂时仍 200 误报健康（C3
     *    核心）。响应由 [parseSlimapiHealth] 适配为 [HealthResponse]：
     *    `healthy = sidecar.ok == true && accepted_client_versions 含
     *    SLIMAPI_CLIENT_VERSION`。
     *
     * **M2 自检的衔接**：本方法返回 [HealthResponse] 形状，但 slimapi sidecar
     * 的版本契约（api_version / accepted_client_versions / schema_degraded）
     * 不在 HealthResponse 里——调用方（ConnectionBootstrapEngine / 上层
     * controller）需要 M2 自检时，应调 [parseSlimapiHealth] 直接从 body 抽取
     * [SlimapiHealthPayload] 并喂 [ServerCompatProfile.updateSlimapi]。本方法
     * 内部已解析，但只通过返回的 [HealthResponse] 暴露 healthy/version 语义；
     * 完整 payload 走 [parseSlimapiHealth] 公开函数。
     *
     * **设计权衡**：不把 [ServerCompatProfile] 注入 [OpenCodeRepository]，
     * 保持 facade 单职责（只负责 HTTP），bootstrap engine 继续是版本契约
     * 的单一更新源——这与现有 `serverCompatProfile.update(health.version)`
     * 调用点对称。
     *
     * 注：KDoc 中避免把 `/slimapi/` 与紧跟着的 `星号星号` 连写（Kotlin lexer
     * 把斜杠+星号星号 当嵌套 KDoc 起始）；下文用 `/slimapi/health` 单独写。
     * M1 门闩对所有 `/slimapi/` 下路径生效，含 health。
     */
    suspend fun checkHealth(): Result<HealthResponse> = runSuspendCatching {
        if (!hostConfig.slim) {
            // legacy：行为字节级不变。
            api.getHealth()
        } else {
            // C3 fix：探 sidecar 自身 health，不经 catch-all 透传。
            probeSlimapiHealth(hostConfig.baseUrl, hostConfig.username, hostConfig.password)
        }
    }

    /**
     * R8 slim-mode foundation / C3 fix（共用实现）：裸 OkHttp `GET {baseUrl}/slimapi/health`
     * 带 `X-Slimapi-Version` 头，把 sidecar 响应适配为 [HealthResponse]。
     *
     * - 用 [clientFactory.healthClient]（无 base 链拦截器——避免 Directory / Auth /
     *   Cache-Control 干扰一次性探针；版本头显式注入，因为 healthClient 不挂
     *   [SlimapiVersionInterceptor]）。
     * - Basic Auth 同步注入（与 [checkHealthFor] 一致语义）。
     * - 解析失败 / sidecar.ok == false / 版本不兼容 → 抛错（`Result.failure`），
     *   不静默报健康——C3 的核心保证。
     */
    private suspend fun probeSlimapiHealth(
        baseUrl: String,
        username: String?,
        password: String?
    ): HealthResponse = withContext(Dispatchers.IO) {
        val resolvedHostPort = hostPortFromUrl(baseUrl)
        val cfg = sslConfigFactory.sslConfigFor(resolvedHostPort)
        val client = clientFactory.healthClient(cfg)
        val normalizedUrl = (if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl")
            .trimEnd('/') + SlimapiContract.SLIMAPI_HEALTH_PATH
        val requestBuilder = Request.Builder()
            .url(normalizedUrl)
            .header(HttpHeaders.SKIP_DIR_HEADER, "1")
            .header(SlimapiContract.X_SLIMAPI_VERSION, SlimapiContract.SLIMAPI_CLIENT_VERSION.toString())
        if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
            val credential = "$username:$password"
            val encoded = Base64.getEncoder().encodeToString(credential.toByteArray())
            requestBuilder.header("Authorization", "Basic $encoded")
        }
        client.newCall(requestBuilder.build()).execute().use { res ->
            if (!res.isSuccessful) error("HTTP ${res.code}")
            val body = res.body?.string().orEmpty()
            if (body.isBlank()) error("Empty response body")
            val payload = parseSlimapiHealth(body)
            // §slim-reconcile-lane-repo (B3 T5): feed the M2 self-check loop —
            // the parsed slimapi version contract MUST land in the shared
            // [ServerCompatProfile] so Phase 3's bootstrap can read
            // [ServerCompatProfile.isSlimapiClientAccepted] and fail-close on
            // version mismatch (C3 core). Without this write, the sidecar's
            // `accepted_client_versions` is parsed but discarded and the
            // fail-closed gate never sees the bounds → either always-rejects
            // (if min/max stay null) or never-rejects (silent).
            serverCompatProfile.updateSlimapi(payload)
            // 适配为 HealthResponse：healthy = sidecar.ok && 版本兼容。
            // version 字段对 slimapi 模式无直接对应（opencode semver 由独立路径
            // 探得），用合成标记让上层 UI 可观测（"slimapi/api_version=<n>"）。
            val healthy = payload.sidecarOk == true &&
                payload.serverApiVersion != null &&
                SlimapiContract.SLIMAPI_CLIENT_VERSION in
                (payload.acceptedClientVersions?.first ?: Int.MIN_VALUE)..(payload.acceptedClientVersions?.second ?: Int.MIN_VALUE)
            HealthResponse(
                healthy = healthy,
                version = payload.serverApiVersion?.let { "slimapi/api_version=$it" }
            )
        }
    }

    /**
     * R8 slim-mode foundation / M2 自检：从 `GET /slimapi/health` 响应 body 抽取
     * 版本契约业务字段，返回 [SlimapiHealthPayload] 供 [ServerCompatProfile.updateSlimapi]
     * 落库。容错：缺字段 / 类型不符 → 对应字段为 null（[ServerCompatProfile.isSlimapiClientAccepted]
     * 在 null 时 fail-closed）。
     *
     * 形状参考 docs/specs/slim-mode-api-routing.md §3.2：
     * ```json
     * { "sidecar": {"ok": true, "version": "0.1.0"},
     *   "schema":   {"degraded": false},
     *   "server":   {"api_version": 1, "accepted_client_versions": [1, 1]} }
     * ```
     *
     * 接受 [body] 字符串（来自 OkHttp `Response.body.string()`）；不可识别的
     * JSON 结构 → 各字段 null（容错，不抛——把决策交给上层 fail-closed）。
     */
    fun parseSlimapiHealth(body: String): SlimapiHealthPayload {
        val root = runCatching { json.decodeFromString<JsonObject>(body) }.getOrNull()
            ?: return SlimapiHealthPayload(null, null, null, null)
        val sidecar = root["sidecar"]?.safeObject()
        val schema = root["schema"]?.safeObject()
        val server = root["server"]?.safeObject()
        val sidecarOk = sidecar?.get("ok")?.safePrimitive()?.let { it.content.equals("true", ignoreCase = true) }
        val schemaDegraded = schema?.get("degraded")?.safePrimitive()?.let { it.content.equals("true", ignoreCase = true) }
        val apiVersion = server?.get("api_version")?.safePrimitive()?.intOrNull
        val accepted = server?.get("accepted_client_versions")?.safeArray()
            ?.mapNotNull { it.safePrimitive()?.intOrNull }
            ?.takeIf { it.size >= 2 }
            ?.let { Pair(it[0], it[1]) }
        val featuresObj = root["features"]?.safeObject() ?: server?.get("features")?.safeObject()
        // §Stage-B S1: dual-read tokenStream — accept both a JSON boolean
        // (booleanOrNull) and a string "true" (content). Pre-fix only the
        // string form was recognized, so a sidecar emitting a native boolean
        // (`"tokenStream": true`) was silently treated as false.
        val tokenStream = featuresObj?.get("tokenStream")?.safePrimitive()?.let { p ->
            p.booleanOrNull == true || p.content.equals("true", ignoreCase = true)
        } == true
        return SlimapiHealthPayload(
            sidecarOk = sidecarOk,
            schemaDegraded = schemaDegraded,
            serverApiVersion = apiVersion,
            acceptedClientVersions = accepted,
            features = SlimapiFeatures(tokenStream = tokenStream)
        )
    }

    /**
     * One-shot health probe against [baseUrl] with optional Basic Auth, WITHOUT
     * mutating this repository's current configuration. Used by the host list's
     * per-row "test" action so a profile can be probed without switching hosts.
     *
     * §tofu R2: [hostPort] (host:port authority of [baseUrl]) replaces the
     * legacy `allowInsecure: Boolean`. It keys the TOFU pin lookup so a
     * previously-trusted endpoint's pin is honored during the probe.
     *
     * Builds a throwaway OkHttp client via [OkHttpClientFactory.healthClient]
     * (the SSL-trust shared entry point) and parses the same [HealthResponse]
     * shape served by `GET /global/health`.
     *
     * R8 slim-mode foundation / C3 fix: [slim] = true 时探
     * `{baseUrl}/slimapi/health`（带 `X-Slimapi-Version` 头）；`slim = false`
     * （默认）保持 legacy `{baseUrl}/global/health`。**核心**：slim 模式下
     * 必须探 sidecar 自身健康（`/slimapi/health` 的 `sidecar.ok` + 版本契约），
     * **不能**探 `/global/health`——后者经 catch-all 透传到 opencode，sidecar
     * 挂时仍 200 误报健康（C3 反例）。默认 false 保持所有现有调用方字节不变。
     */
    suspend fun checkHealthFor(
        baseUrl: String,
        username: String? = null,
        password: String? = null,
        hostPort: String? = null,
        clientCert: ClientCertMaterial? = null,
        slim: Boolean = false
    ): Result<HealthResponse> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            // v3-gpter R2#1 阻断修复：用 [SslConfigFactory.resolveProbe] 纯参数解析
            // （hostPort + clientCert），**禁止**用 sslConfigFor——后者会读 held
            // mTLS 状态，于是测他 profile（clientCert=null）时会复用当前 mTLS profile
            // 的缓存，误出示其客户端证书 / 只信其私有 CA，甚至泄漏客户端身份给无关 host。
            // §tofu R2: hostPort 替代 allowInsecure，探测也走 TOFU pin 查询。
            val resolvedHostPort = hostPort ?: hostPortFromUrl(baseUrl)
            val cfg: SslConfig = sslConfigFactory.resolveProbe(resolvedHostPort, clientCert)
            val client = clientFactory.healthClient(cfg)
            // R8 slim-mode foundation / C3: slim=true → /slimapi/health（带版本头）;
            // slim=false → /global/health（行为字节级不变）。
            val healthPath = if (slim) SlimapiContract.SLIMAPI_HEALTH_PATH
                else SlimapiContract.LEGACY_HEALTH_PATH
            val normalizedUrl = (if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl")
                .trimEnd('/') + healthPath
            val requestBuilder = Request.Builder()
                .url(normalizedUrl)
                .header(HttpHeaders.SKIP_DIR_HEADER, "1")
            // M1: slimapi 模式下版本头对所有 /slimapi/ 路径必带——含 health 自身。
            if (slim) {
                requestBuilder.header(
                    SlimapiContract.X_SLIMAPI_VERSION,
                    SlimapiContract.SLIMAPI_CLIENT_VERSION.toString()
                )
            }
            if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
                val credential = "$username:$password"
                val encoded = Base64.getEncoder().encodeToString(credential.toByteArray())
                requestBuilder.header("Authorization", "Basic $encoded")
            }
            client.newCall(requestBuilder.build()).execute().use { res ->
                if (!res.isSuccessful) error("HTTP ${res.code}")
                val body = res.body?.string().orEmpty()
                if (body.isBlank()) error("Empty response body")
                if (slim) {
                    // C3 fix: sidecar 自身 health 适配为 HealthResponse——
                    // healthy = sidecar.ok && 版本兼容；不兼容/缺字段 → 抛错
                    // （fail-closed，绝不静默报健康）。
                    val payload = parseSlimapiHealth(body)
                    // §slim-reconcile-lane-repo (Phase 3a / Lane-B3-Dialog):
                    // 镜像 [probeSlimapiHealth] 的 T5 模式——把解析后的 slimapi
                    // 版本契约喂 [ServerCompatProfile]，这样 test-connection（one-shot
                    // 探针，per-profile）也能落库版本契约字段，让上层（ConnectionViewModel
                    // / Phase 3 bootstrap）读到 [ServerCompatProfile.isSlimapiClientAccepted]
                    // 并 fail-close / 弹阻塞 dialog。不加这一行，test-connection 链根本
                    // 看不到 sidecar 的 `accepted_client_versions`，dialog 永不弹（C3 反例）。
                    serverCompatProfile.updateSlimapi(payload)
                    val accepted = payload.acceptedClientVersions != null &&
                        SlimapiContract.SLIMAPI_CLIENT_VERSION in
                        payload.acceptedClientVersions!!.first..payload.acceptedClientVersions!!.second
                    val healthy = payload.sidecarOk == true && accepted
                    if (!healthy) error("slimapi sidecar unhealthy or client version incompatible")
                    HealthResponse(
                        healthy = true,
                        version = payload.serverApiVersion?.let { "slimapi/api_version=$it" }
                    )
                } else {
                    json.decodeFromString(HealthResponse.serializer(), body)
                }
            }
        }
    }

    /**
     * §slim-reconcile-lane-repo (B2 T2): in slim mode, route to the sidecar's
     * `/slimapi/sessions` (skeleton list, each row carries its own
     * `directory` so the caller filters client-side). The slimapi DTO IS the
     * legacy [Session] shape (no separate SlimapiSession model), so the
     * adapter is identity — preserved here for symmetry with
     * [getSessionsForDirectory] / [getPendingPermissions].
     *
     * legacy (`isSlimMode == false`): byte-for-byte unchanged.
     *
     * T3-M1 (final review D4): the slim branch applies the SAME
     * `parseErrorCode` + `DebugLog.w` + rethrow pattern [getSlimapiSessions]
     * uses, so production session-list loads get the same warn-level code
     * log as the other slim catch sites. The legacy `api.getSessions`
     * branch is left untouched (no slim envelope on that path).
     */
    suspend fun getSessions(limit: Int? = null): Result<List<Session>> =
        sessionSource.getSessions(limit)

    /**
     * Fetches the root sessions whose [Session.directory] exactly matches
     * [directory]. Uses the server's `?directory` + `?roots` query params
     * (priority: query param > header > cwd, per lib-1) so the result is
     * scoped to this workdir and excludes sub-agent (child) sessions.
     *
     * §slim-reconcile-lane-repo (B2 T2): in slim mode, the same semantics
     * are achieved by passing `directories=listOf(directory)` + `roots=true`
     * — Retrofit expands the list into repeated `?directory=...` query
     * params (contract: repeated, NOT comma-joined). The sidecar filters
     * server-side identically to the legacy opencode route.
     *
     * T3-M1 (final review D4): slim branch observability — same
     * `parseErrorCode` + `DebugLog.w` + rethrow pattern as [getSessions] /
     * [getSlimapiSessions]; legacy branch untouched.
     */
    suspend fun getSessionsForDirectory(directory: String, limit: Int? = null): Result<List<Session>> =
        sessionSource.getSessionsForDirectory(directory, limit)

    /**
     * Fetches a single session by ID. Used to resolve a child/sub-agent session
     * that may not be present in the cached [getSessions] list.
     */
    suspend fun getSession(sessionId: String): Result<Session> =
        slimGetRepository.getSession(sessionId)

    // §R18 Final 终审 fix (gpter): directory now explicit (was relying on the
    // removed global currentDirectory fallback). Callers pass currentWorkdir /
    // draftWorkdir so POST /session routes to the correct workdir instance.
    suspend fun createSession(title: String? = null, directory: String? = null): Result<Session> = runSuspendCatching {
        mutationApi.createSession(CreateSessionRequest(title = title), directory)
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

    suspend fun getSessionStatus(): Result<Map<String, SessionStatus>> =
        slimGetRepository.getSessionStatus()

    suspend fun getActiveSessionIds(): Result<Set<String>> =
        slimGetRepository.getActiveSessionIds()

    /**
     * T-R1 (slimapi R1) — BULK slim cold-start status fetch. The slim-mode
     * replacement for the legacy [getSessionStatus] bulk endpoint: routes
     * through the sidecar's `GET /slimapi/sessions/status?directory=` and
     * returns the SAME `Map<String, SessionStatus>` shape (forwarded verbatim
     * from upstream `/session/status`), so callers ([launchLoadSessionStatus]
     * slim cold-start + [cn.vectory.ocdroid.service.status.StatusAggregatorImpl]
     * L2Idle/L3 disconnect fallback) consume it identically to the legacy map.
     *
     * `directory` is REQUIRED by the sidecar (see [OpenCodeApi.getSlimapiSessionsStatus]);
     * callers pass one registered workdir per call and merge. Non-2xx (incl.
     * the sidecar's 503 `upstream_unavailable` / 502 `upstream_http_<N>`) and
     * transport failures throw → collapse to [Result.failure] (the caller
     * treats failure as "keep prior snapshot / mark Unknown", matching the
     * legacy [getSessionStatus] failure semantics).
     *
     * Additive: legacy mode never calls this (the slim branches that invoke it
     * are gated on [isSlimMode]). The wire contract is unchanged
     * (`X-Slimapi-Version` stays 1, injected by interceptor).
     */
    suspend fun getSlimapiSessionsStatus(directory: String): Result<Map<String, SessionStatus>> =
        runSuspendCatching {
            val resp = api.getSlimapiSessionsStatus(directory)
            if (!resp.isSuccessful) {
                throw java.io.IOException("slim bulk status HTTP ${resp.code()}")
            }
            resp.body() ?: emptyMap()
        }

    /**
     * Fetches the child (sub-agent) sessions spawned by [sessionId], typically
     * via the `task` tool.
     */
    suspend fun getChildren(sessionId: String): Result<List<Session>> =
        slimGetRepository.getChildren(sessionId)

    suspend fun getMessages(sessionId: String, limit: Int? = null): Result<List<MessageWithParts>> =
        runSuspendCatching {
            val response = api.getMessages(sessionId, limit, before = null)
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code()}")
            response.body() ?: emptyList()
        }

    /**
     * Cursor-paged message fetch (V1 route: cursor carried via the
     * `X-Next-Cursor` response header + the `before` query param).
     *
     * §slim-reconcile-lane-repo (B2 T3): in slim mode, route to the sidecar's
     * `/slimapi/messages/{sid}/since/{ts}` anchor-paginated endpoint. The
     * `since` ts is derived from the local slim SSE bookmark
     * ([slimSseState.get]·updatedAt, 0L if none — same source as
     * [coldStartSlimSync] uses for its open-session tail). `limit` / `before`
     * are forwarded verbatim (slimapi supports the since-ts + before-opaque-
     * cursor combination per v1 contract §5).
     *
     * The slimapi DTO IS the legacy [MessageWithParts] shape — there is no
     * separate SlimapiMessage type to map (the sidecar emits skeletons in
     * the wire format the legacy opencode route already speaks). Bookmark
     * bumping uses the same `max(time.updated)` rule as
     * [coldStartSlimSync] / [getSlimapiMessagesSince].
     *
     * §slim-v1-paging (Task 5 / G5 cursor): the slim since path SURFACES
     * the `X-Next-Cursor` response header (was: hardcoded `null` under the
     * earlier single-page decision). Both slim and legacy branches now
     * read the header identically — the upper layer decides whether to
     * follow via `?before=<opaque>`.
     *
     * legacy (`isSlimMode == false`): byte-for-byte unchanged.
     */
    suspend fun getMessagesPaged(
        sessionId: String,
        limit: Int? = null,
        before: String? = null,
        token: SlimCommitToken = captureSlimCommitToken(),
    ): Result<MessagesPage> =
        getMessagesPagedImpl(sessionId, limit, before, token, anchored = true)

    /**
     * §empty-window-fix: UNANCHORED slim initial-window fetch — same contract
     * as [getMessagesPaged] but forces `since=0L` in slim mode (ignores the
     * cached slim SSE watermark). Used ONLY by the VerifyAndHydrate cold-load
     * path (via `launchLoadMessages(forceInitialWindow = true)` /
     * `loadMessagesForEffect(forceInitialWindow = true)`) when a resident-but-
     * EMPTY [cn.vectory.ocdroid.ui.controller.CachedSessionWindow] is treated
     * as a cache miss.
     *
     * # Why this exists (root cause of the "session opens to 暂无消息 but send
     * populates it" bug)
     *
     * An empty window gets written when
     * [cn.vectory.ocdroid.ui.controller.SessionSwitcher.captureCurrentSessionWindow]
     * snapshots an outgoing session that was still loading or already cleared.
     * Pre-fix, VerifyAndHydrate hydrated that empty window and ran a
     * `resetLimit=false` refresh → [getMessagesPaged]'s slim branch anchored on
     * the EXISTING slim watermark + `/since/{ts}`. If that watermark already
     * covered the server's latest message, the `/since` response was EMPTY, so
     * the selective merge preserved the empty UI window. Net: server had
     * messages, UI showed "暂无消息". Sending bypassed this because it triggers
     * `onRefreshMessages(sessionId, true)` via `loadMessagesWithRetry` + SSE
     * activity.
     *
     * Forcing `since=0L` makes the fetch UNANCHORED — it returns the server's
     * actual initial window regardless of any cached watermark, exactly like a
     * genuine cache-miss cold load.
     *
     * # Shape compatibility
     *
     * Returns the SAME [MessagesPage] shape (items + nextCursor) as
     * [getMessagesPaged] so the caller's selective-merge
     * (§preserveUnfetched / §Bug3) + cursor-seeding
     * (`olderMessagesCursor` / `hasMoreMessages`) logic is IDENTICAL to the
     * `resetLimit=true` path. The `X-Next-Cursor` header seeds the history
     * cursor so `loadMore` continues to work.
     *
     * Legacy non-slim mode (`isSlimMode == false`) has no watermark concept —
     * the body is byte-for-byte identical to [getMessagesPaged]'s legacy
     * branch (same [api.getMessages] call, same header read).
     *
     * See [getMessagesPaged] for the anchored (watermark-based) counterpart.
     * `getMessagesPaged`'s default behavior for all other callers is
     * unchanged.
     */
    suspend fun getMessagesPagedUnanchored(
        sessionId: String,
        limit: Int? = null,
        before: String? = null,
        token: SlimCommitToken = captureSlimCommitToken(),
    ): Result<MessagesPage> =
        getMessagesPagedImpl(sessionId, limit, before, token, anchored = false)

    /**
     * Shared implementation for [getMessagesPaged] and
     * [getMessagesPagedUnanchored]. ι-P2: now a thin forwarder to the
     * [messageSource] port (Standard / Slim 双实现，由 [configure] 选束）。
     * [anchored] selects the slim watermark: `true` reads the cached slim SSE
     * watermark ([getMessagesPaged]); `false` forces `since=0L`
     * ([getMessagesPagedUnanchored]). The watermark lookup + bookmark bump +
     * response cursor reading all live inside the [MessageSource]
     * implementation's [runSuspendCatching] block, so a watermark-read or
     * bookmark-bump failure stays in the `Result.failure` channel and there is
     * no reconfigure race between the public wrapper and the impl. Token
     * threading (I15) and bookmark / lock ownership (I5) are preserved verbatim
     * via the injected lambdas on [SlimMessageSource] — the slim branch's
     * `isSlimMode` check is now expressed as the source selection in
     * [configure], read exactly once per host switch under the same
     * `@Synchronized` monitor.
     */
    private suspend fun getMessagesPagedImpl(
        sessionId: String,
        limit: Int?,
        before: String?,
        token: SlimCommitToken,
        anchored: Boolean,
    ): Result<MessagesPage> =
        messageSource.getMessagesPaged(sessionId, limit, before, token, anchored)



    /**
     * §Phase1B lightweight tail probe: fetches only the single newest message
     * id for [sessionId] (limit=1, desc default).
     */
    suspend fun probeLatestMessageId(sessionId: String): Result<String?> = runSuspendCatching {
        val response = api.getMessages(sessionId, limit = 1, before = null)
        if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code()}")
        response.body()?.firstOrNull()?.info?.id
    }

    /**
     * §slimapi-client-impl-v1 §4 (G3 probeLatestMessageId 收敛): slim-mode
     * probe that returns the single newest message for [sessionId] against
     * the sidecar (`GET /slimapi/messages/{sid}?limit=1&mode=skeleton`) and
     * **boundary-normalises every outcome into a [ProbeResult]** — no
     * `Result<Response<...>>` for callers to pattern-match on.
     *
     * Branch table:
     *  - 200 + empty array           → `ProbeResult(ok=true,  empty=true)`
     *  - 200 + one item              → `ProbeResult(ok=true,  messageID=info.id, updatedAt=time.updated?:created)`
     *  - 200 + null body (defensive) → `ProbeResult(ok=false, httpStatus=resp.code())`
     *  - HTTP 4xx/5xx                → `ProbeResult(ok=false, httpStatus=resp.code())`
     *  - Network/IO failure          → `ProbeResult(ok=false, httpStatus=null)`
     *
     * The HTTP-fail (carries `httpStatus`) vs network-fail (`httpStatus=null`)
     * split is what lets the reconcile state machine (T7/T11) decide between
     * "sid is gone upstream" (`httpStatus == 404` → mark deleted) and
     * "transport is flaky" (`httpStatus == null` → keep dirty, retry next
     * pass). Bare `probe[0]` access is forbidden downstream; every read goes
     * through [ProbeResult].
     *
     * `X-Slimapi-Version: 1` is injected by [SlimapiVersionInterceptor] (no
     * per-call header here). The legacy [probeLatestMessageId] is left
     * byte-for-byte unchanged for the non-slim catch-up path.
     */
    suspend fun probeLatestSlim(sessionId: String): ProbeResult = runSuspendCatching {
        val resp = api.getSlimapiMessages(sessionId, limit = 1, before = null, mode = "skeleton")
        if (!resp.isSuccessful) {
            // POST-RELEASE instrumentation: per-probe outcome log for the
            // SlimapiProbe diagnostic surface. One line per probe attempt.
            DebugLog.d(
                "SlimapiProbe",
                "probe sid=$sessionId FAILED http=${resp.code()}",
            )
            return@runSuspendCatching ProbeResult(ok = false, httpStatus = resp.code())
        }
        val arr = resp.body() ?: return@runSuspendCatching ProbeResult(ok = false, httpStatus = resp.code())
        if (arr.isEmpty()) {
            DebugLog.d("SlimapiProbe", "probe sid=$sessionId EMPTY")
            ProbeResult(ok = true, empty = true)
        } else {
            val mid = arr.first().info.id
            val ts = arr.first().info.time?.updated ?: arr.first().info.time?.created
            DebugLog.d(
                "SlimapiProbe",
                "probe sid=$sessionId OK latest=$mid ts=$ts",
            )
            ProbeResult(
                ok = true,
                messageID = mid,
                updatedAt = ts,
            )
        }
    }.getOrElse { error ->
        // POST-RELEASE instrumentation: network/transport failure path —
        // distinguishes "sidecar reachable but errored" (above branches)
        // from "sidecar unreachable" (this branch).
        DebugLog.d(
            "SlimapiProbe",
            "probe sid=$sessionId TRANSPORT_FAIL ${error.javaClass.simpleName}: ${error.message}",
        )
        ProbeResult(ok = false, httpStatus = null)
    }

    /**
     * §slimapi-client-impl-v1 §6 G2 (Task 4) — per-session status fetch
     * (`GET /slimapi/sessions/{sid}/status`), boundary-normalised into a
     * [StatusOutcome] so the caller (T7 reconcile / T11 StatusAggregator)
     * never pattern-matches on `retrofit2.Response` / HTTP status / error
     * code strings. Mirrors the [expandMessagesFullBatch] outcome discipline.
     *
     * Branch table (matches the contract §6 G2 — destructive outcomes
     * route by the sidecar's body `code`, NOT by HTTP status alone, so a
     * 404/400 with an unexpected body code never silently clears or
     * misreports):
     *  - **200 + body** → [StatusOutcome.Success] carrying the raw
     *    [SessionStatus] (idle/busy/retry preserved as-is — T4-C2: idle
     *    is NOT folded to [StatusOutcome.SessionMissing]; the contract's
     *    false-idle warning is the caller's problem, cross-checked
     *    against the sessions list).
     *  - **200 + null body** → [StatusOutcome.UpstreamWarn]`(sessionId,
     *    null)` (defensive — the sidecar returned 200 but no body, which
     *    is a protocol violation; refuse to fabricate a fake idle and
     *    surface non-destructively).
     *  - **404 + `session_not_found`** → [StatusOutcome.SessionMissing]
     *    (the session is gone upstream → caller clears local cache).
     *  - **404 + other / null code** → [StatusOutcome.UpstreamWarn]
     *    (route missing / unknown error — must NOT clear local: only
     *    `session_not_found` carries the "session is deleted" semantic).
     *  - **400 + `directory_not_allowed`** → [StatusOutcome.DirectoryError]
     *    (caller prompts the user; deterministic misconfiguration).
     *  - **400 + other / null code** → [StatusOutcome.UpstreamWarn]
     *    (param/route errors — must NOT be misreported as directory config).
     *  - **502** `upstream_http_<N>` → [StatusOutcome.UpstreamWarn] with
     *    the sidecar's code (caller alerts, keeps local).
     *  - **503** `upstream_unavailable` → [StatusOutcome.Retry] with the
     *    code (transient sidecar/upstream fault → caller backs off).
     *  - Other 5xx → [StatusOutcome.Retry] (defensive — treat unknown
     *    server-side hiccups as transient so polling recovers).
     *  - Other 4xx → [StatusOutcome.UpstreamWarn] (defensive — caller
     *    surfaces, does NOT clear local on unmapped 4xx).
     *  - Network / IO failure → [StatusOutcome.Retry] with `code = null`
     *    (distinguishable from 503 so callers can log transport vs busy).
     *    This branch also catches transport-level [java.io.EOFException]
     *    thrown by OkHttp/okio when the response stream is truncated
     *    mid-body (real network EOF) — that is transient, NOT a protocol
     *    violation, so it MUST stay in the Retry bucket (rev-gpt re-review
     *    round 2: removing the prior standalone `EOFException → UpstreamWarn`
     *    arm — see [SerializationException] below for the empty-body case).
     *  - **200 + empty / unparseable body** (converter throws
     *    [kotlinx.serialization.SerializationException]) →
     *    [StatusOutcome.UpstreamWarn]`(sessionId, null)` (rev-gpt
     *    IMPORTANT #1: same protocol-violation bucket as 200 + null body
     *    above — the server replied 200 with no/bad payload, which is NOT a
     *    transient transport failure and MUST NOT be fabricated into a
     *    fake idle status).
     *
     * Cancellation is re-thrown (NOT collapsed into Retry) so the UI's
     * dispose-driven cancel propagates cleanly — matches the
     * [expandBatchInternal] CE discipline (R-14, rev-grok finding).
     */
    suspend fun getSlimapiSessionStatusOutcome(sessionId: String): StatusOutcome {
        val resp = try {
            api.getSlimapiSessionStatus(sessionId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: kotlinx.serialization.SerializationException) {
            // 200 + body present but unparseable (e.g. empty string, or
            // JSON missing the required `type` field): the kotlinx-
            // serialization converter calls `ResponseBody.string()` then
            // `decodeFromString`, which throws SerializationException on
            // empty/unparseable input. This is a server-side protocol
            // violation (200 with no/bad payload), NOT a transient
            // transport failure → surface as UpstreamWarn(null) — NOT Retry
            // (the next poll will fail the same way until the server is
            // fixed) and NOT fabricated idle (rev-gpt IMPORTANT #1).
            //
            // NOTE: a real mid-stream truncation throws IOException (incl.
            // EOFException, which is an IOException subclass) and is caught
            // by the next arm → Retry. Do NOT add an EOFException arm here:
            // it would misroute transport-level EOF (transient) into the
            // permanent UpstreamWarn bucket (rev-gpt re-review round 2).
            return StatusOutcome.UpstreamWarn(sessionId, null)
        } catch (e: java.io.IOException) {
            // Transport failure (incl. mid-stream EOF / truncation —
            // EOFException is an IOException subclass) → transient Retry
            // with null code so callers can distinguish "network" from
            // "server busy" (503 carries the sidecar's
            // upstream_unavailable code; this branch does not).
            return StatusOutcome.Retry(sessionId, null)
        } catch (e: Exception) {
            // Defensive: any other Throwable (e.g. Json decode of a 2xx body
            // throwing inside the converter) collapses to Retry(null) so the
            // next poll tick re-tries. Cancellation is re-thrown above.
            return StatusOutcome.Retry(sessionId, null)
        }
        return when {
            // 200 + status body — idle/busy/retry preserved as-is (T4-C2).
            // 200 + null body → UpstreamWarn (defensive: protocol violation;
            // do NOT fabricate a fake idle that would mask missing data).
            resp.isSuccessful -> {
                val status = resp.body()
                if (status != null) {
                    StatusOutcome.Success(sessionId, status)
                } else {
                    StatusOutcome.UpstreamWarn(sessionId, null)
                }
            }
            // 404 + session_not_found → SessionMissing (session deleted).
            // 404 + other/null code → UpstreamWarn (route missing, unknown
            // error — MUST NOT clear local; only session_not_found carries
            // the "deleted" semantic per G2 contract).
            resp.code() == 404 -> {
                val code = parseErrorCode(resp)
                if (code == SlimapiErrorCodes.SESSION_NOT_FOUND) {
                    StatusOutcome.SessionMissing(sessionId)
                } else {
                    StatusOutcome.UpstreamWarn(sessionId, code)
                }
            }
            // 400 + directory_not_allowed → DirectoryError.
            // 400 + other/null code → UpstreamWarn (param/route error; do
            // NOT misreport as a directory config error).
            resp.code() == 400 -> {
                val code = parseErrorCode(resp)
                if (code == SlimapiErrorCodes.DIRECTORY_NOT_ALLOWED) {
                    StatusOutcome.DirectoryError(sessionId)
                } else {
                    StatusOutcome.UpstreamWarn(sessionId, code)
                }
            }
            // 503 → Retry with sidecar's upstream_unavailable code.
            resp.code() == 503 -> StatusOutcome.Retry(sessionId, parseErrorCode(resp))
            // 502 → UpstreamWarn (upstream_http_<N>).
            resp.code() == 502 -> StatusOutcome.UpstreamWarn(sessionId, parseErrorCode(resp))
            // Defensive: unknown 5xx → transient Retry; unknown 4xx → UpstreamWarn.
            resp.code() >= 500 ->
                StatusOutcome.Retry(sessionId, parseErrorCode(resp))
            else ->
                StatusOutcome.UpstreamWarn(sessionId, parseErrorCode(resp))
        }
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
        val response = mutationApi.promptAsync(sessionId, request)
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Send failed ${response.code()}: $errorBody")
        }
    }

    suspend fun abortSession(sessionId: String): Result<Unit> = runSuspendCatching {
        mutationApi.abortSession(sessionId)
    }

    /**
     * §context-compact: triggers server-side context compaction for [sessionId]
     * via POST /session/{id}/summarize. The compaction itself runs async on
     * the server; the resulting message/part SSE events drive the message
     * reload automatically. [model] is the current session model (read from
     * app state by the caller) — the server uses it to generate the summary.
     *
     * §compact-graded (Blocker-1): the returned [Result] distinguishes three
     * outcomes so the caller ([ChatViewModel.compactSession]) can grade its
     * recovery instead of swallowing every failure:
     *  - `Result.success(true)` — POST accepted and the server explicitly
     *    acknowledged (body=`true`, or body=null as in HTTP 204 where the
     *    server returned no body but the request was accepted).
     *  - `Result.failure(ServerRejectedException)` — POST reached the server,
     *    HTTP 2xx came back, but the body was `false`: the server explicitly
     *    rejected compaction (e.g. context too small to summarize, server
     *    refused). This is a *deterministic* failure — the user must be told
     *    and `isCompacting` must be cleared so a retry is possible.
     *  - `Result.failure(<IOException/HttpException>)` — transport or HTTP
     *    non-2xx failure. The caller's grading logic further splits this into
     *    read-side [java.net.SocketTimeoutException] (POST likely accepted,
     *    SSE will carry the result) vs everything else (POST never reached
     *    the server).
     *
     * Completion of the compaction itself is reported through SSE; the body
     * is NOT interpreted as the compaction result.
     */
    suspend fun summarizeSession(
        sessionId: String,
        model: Message.ModelInfo
    ): Result<Boolean> = runSuspendCatching {
        val response = mutationApi.summarizeSession(sessionId, SummarizeRequest(model.providerId, model.modelId))
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Summarize failed ${response.code()}: $errorBody")
        }
        // body == null (HTTP 204 etc.) → server accepted, no body to read.
        val accepted = response.body() ?: true
        if (!accepted) {
            // §compact-graded: server returned `false` → explicit rejection.
            // Throw so runSuspendCatching turns it into Result.failure and the
            // caller's onFailure(accepted-reject) branch can clear isCompacting.
            throw SummarizeServerRejectedException()
        }
        accepted
    }

    /**
     * §compact-graded (Blocker-1): raised by [summarizeSession] when the
     * server returns HTTP 2xx with body `false`. Distinct type so
     * [ChatViewModel.compactSession] can branch on `onFailure` +
     * `cause is SummarizeServerRejectedException` and clear `isCompacting`
     * + emit a deterministic Error (vs the read-timeout Info path).
     */
    class SummarizeServerRejectedException :
        Exception("Server rejected compaction (body=false)")

    suspend fun forkSession(sessionId: String, messageId: String? = null): Result<Session> = runSuspendCatching {
        mutationApi.forkSession(sessionId, ForkSessionRequest(messageId))
    }

    suspend fun revertSession(sessionId: String, messageId: String, partId: String? = null): Result<Session> = runSuspendCatching {
        mutationApi.revertSession(sessionId, RevertSessionRequest(messageId, partId))
    }

    /**
     * §slim-reconcile-lane-repo (B2 T4) / §rev-grok fix1: in slim mode, route
     * to the sidecar's `/slimapi/permissions` cross-directory aggregate
     * (single call replaces the legacy per-directory `/permission` poll). The
     * sidecar returns its `{items, errors}` envelope; this method flattens
     * `.items` and maps each [SlimapiPermissionEntry] to a legacy
     * [PermissionRequest] via [toPermissionRequest], **preserving
     * [SlimapiPermissionEntry.routeToken]** (Phase 3b — the slim respond
     * path needs the sidecar HMAC; `directory` is still dropped, the sidecar
     * re-injects it from the token).
     *
     * `directories = null` lets the sidecar decide scope (matches
     * [coldStartSlimSync]'s default — the upper layer that knows the user's
     * session set can pass a list explicitly via [getSlimapiPermissions]).
     *
     * Per-directory `errors` are logged at WARN (same envelope-degradation
     * policy as the other slimapi aggregate readers).
     *
     * legacy (`isSlimMode == false`): byte-for-byte unchanged.
     */
    suspend fun getPendingPermissions(): Result<List<PermissionRequest>> = runSuspendCatching {
        if (isSlimMode) {
            val aggregation = api.getSlimapiPermissions(directories = null)
            if (aggregation.errors.isNotEmpty()) {
                DebugLog.w(TAG, "slimapi/permissions partial errors: ${aggregation.errors}")
            }
            aggregation.items.map { it.toPermissionRequest() }
        } else {
            api.getPendingPermissions()
        }
    }

    suspend fun respondPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse
    ): Result<Unit> = runSuspendCatching {
        mutationApi.respondPermission(sessionId, permissionId, PermissionResponseRequest(response.value))
    }

    suspend fun getPendingQuestions(directory: String?): Result<List<QuestionRequest>> = runSuspendCatching {
        api.getPendingQuestions(directory)
    }

    suspend fun replyQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?
    ): Result<Unit> = runSuspendCatching {
        val response = mutationApi.replyQuestion(requestId, QuestionReplyRequest(answers), directory)
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Reply failed ${response.code()}: $errorBody")
        }
    }

    suspend fun rejectQuestion(requestId: String, directory: String?): Result<Unit> = runSuspendCatching {
        val response = mutationApi.rejectQuestion(requestId, directory)
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Reject failed ${response.code()}: $errorBody")
        }
    }

    /**
     * §catalog-source: builds the model catalog from `GET /config/providers` —
     * the SAME endpoint the opencode web model picker uses (verified by
     * inspecting the web bundle served by opencode 1.17.x). Returns the
     * [ProvidersResponse] downstream consumes (model picker + Model Management
     * + context-limit index + per-prompt model attachment), unchanged.
     *
     * §catalog-source-revert (from the V2 /api/model + /api/provider pair): on
     * opencode ≤1.17.x the V2 pair returns a STRICT SUBSET — only providers
     * with an explicit `options.apiKey` in config plus the free `opencode`
     * (Zen) provider — omitting most configured providers. On one 1.17.15
     * server /api/model returned 3 providers / 31 models while
     * /config/providers returned 10 providers / 61 models, so the app showed
     * far fewer models than the web. /config/providers returns the full
     * catalog the web shows.
     *
     * §forward-compat: opencode HEAD is moving the web to `/api/provider`
     * (whose `Provider` type gains a `models` map). On ≤1.17.x `/api/provider`
     * returns NO `models` field, so it cannot source the picker there; if a
     * future opencode drops /config/providers or stops populating it, revisit.
     *
     * §key-leak safety (the original reason for the V2 migration — and why it
     * is safe to revert here): /config/providers' raw body carries provider
     * `apiKey` values, BUT
     *   (a) [ConfigProvider] / [ProviderModel] have NO `options`/`apiKey`/`key`
     *       field + `ignoreUnknownKeys = true` → keys are dropped at
     *       deserialization, never held in memory or logged;
     *   (b) [cn.vectory.ocdroid.data.repository.http.HttpHeaders.CACHEABLE_PATHS]
     *       intentionally EXCLUDES `/config/providers` → no on-disk OkHttp
     *       cache residue;
     *   (c) this is a personal client ↔ personal server, so transit is to the
     *       device owner only.
     *
     * §last-mile defense: a structural failure (HTTP error / non-decodable
     * body) does NOT propagate as Result.failure (which would surface as
     * "服务器没有可用模型"); it logs + returns an EMPTY catalog
     * (Result.success) so the picker shows an empty list and the next refresh
     * retries. CancellationException is rethrown (structured concurrency).
     * Providers whose `models` map is empty are dropped (parity with the
     * former V2 builder's groupBy).
     */
    suspend fun getProviders(): Result<ProvidersResponse> {
        // §catalog-source-revert: fetch GET /config/providers — the SAME endpoint
        // the opencode web model picker uses (verified on the web bundle served
        // by opencode 1.17.x). The former V2 /api/model + /api/provider pair
        // returns a STRICT SUBSET on ≤1.17.x (only providers with an explicit
        // options.apiKey in config + the free `opencode`/Zen provider), omitting
        // most configured providers → the app showed far fewer models than the
        // web. /config/providers returns the full catalog. See the method kdoc
        // for the key-leak safety analysis that makes this revert safe.
        val response: ProvidersResponse = try {
            api.getProviders()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // §last-mile defense: do NOT propagate failure (would surface as
            // "服务器没有可用模型"); degrade to an empty catalog so the picker
            // shows an empty list and the next refresh retries. Cancellation is
            // rethrown for structured concurrency.
            DebugLog.e("OpenCodeRepository", "catalog: /config/providers fetch failed, returning empty catalog", e)
            return Result.success(ProvidersResponse(providers = emptyList()))
        }
        // Drop providers with no models (parity with the former V2 builder's
        // groupBy: a provider whose models map is empty renders no picker rows).
        val providers = response.providers.filter { it.models.isNotEmpty() }
        val totalModels = providers.sumOf { it.models.size }
        DebugLog.i("OpenCodeRepository", "catalog: ${providers.size} provider(s), $totalModels model(s) from /config/providers")
        return Result.success(
            ProvidersResponse(providers = providers, defaultByProvider = response.defaultByProvider)
        )
    }

    /**
     * §model-selection: lists the server's available models via the v2
     * `GET /api/model` endpoint. Returns the `data` array (each entry carries
     * `id`, `providerID`, `name`, `enabled`, `limit`). NOTE: [getProviders]
     * above no longer uses this endpoint — it fetches `/config/providers`,
     * which returns the full model catalog the opencode web picker shows;
     * this standalone [getModels] is retained for debug/console use and has
     * no production caller.
     *
     * §v2-tolerant-catalog (0.6.1 round-1): per-entry decode so a wrong-type
     * entry is skipped + logged instead of
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

    suspend fun getAgents(): Result<List<AgentInfo>> =
        slimGetRepository.getAgents()

    /**
     * Lists the server-defined slash commands.
     */
    suspend fun getCommands(): Result<List<CommandInfo>> =
        slimGetRepository.getCommands()

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

    suspend fun getSessionDiff(sessionId: String): Result<List<FileDiff>> =
        slimGetRepository.getSessionDiff(sessionId)

    suspend fun getSessionTodos(sessionId: String): Result<List<TodoItem>> =
        slimGetRepository.getSessionTodos(sessionId)

    /**
     * §R-17 batch4 / §R18 Phase 2-E step 2: lists files under [directory]
     * (absolute workdir) at the relative [path]. The directory is passed
     * EXPLICITLY to the server via `?directory` + the `X-Opencode-Skip-Dir`
     * marker on the API method (no global state involved).
     */
    suspend fun getFileTree(directory: String, path: String? = null): Result<List<FileNode>> =
        slimGetRepository.getFileTree(directory, path)

    /**
     * Lists the contents of an arbitrary [directory] (independent of the
     * currently selected session's workdir). Used by the directory picker.
     */
    suspend fun getFileTreeForDirectory(directory: String, path: String? = null): Result<List<FileNode>> =
        slimGetRepository.getFileTreeForDirectory(directory, path)

    /** §R-17 batch4: see [getFileTree] for the explicit-directory rationale. */
    suspend fun getFileContent(directory: String, path: String): Result<FileContent> =
        slimGetRepository.getFileContent(directory, path)

    /** §R-17 batch4: see [getFileTree] for the explicit-directory rationale. */
    suspend fun getFileStatus(directory: String): Result<List<FileStatusEntry>> =
        slimGetRepository.getFileStatus(directory)

    // §vcs-section: read-only VCS façade for the Settings → Working directory
    // section. Thin wrappers mirroring the file* directory-scoped pattern
    // (§R-17 batch4): the directory is supplied EXPLICITLY by the caller; no
    // global workdir state. VcsInfo / VcsStatusEntry live in data.model; the
    // diff endpoint reuses the existing FileDiff shape (same as /session/{id}/diff).
    suspend fun getVcs(directory: String?): Result<VcsInfo> =
        slimGetRepository.getVcs(directory)

    suspend fun getVcsStatus(directory: String?): Result<List<VcsStatusEntry>> =
        slimGetRepository.getVcsStatus(directory)

    suspend fun getVcsDiff(mode: String, directory: String?): Result<List<FileDiff>> =
        slimGetRepository.getVcsDiff(mode, directory)

    /** §R-17 batch4: see [getFileTree] for the explicit-directory rationale. */
    suspend fun findFile(directory: String, query: String, limit: Int = 50): Result<List<String>> =
        slimGetRepository.findFile(directory, query, limit)

    /**
     * §R18 Phase 2-E step 1: SSE feed now takes an explicit [directory] so
     * the server routes events to the right InstanceState without relying on
     * the global workdir. Null defers to the interceptor's fallback (still
     * present in step 1; removed in step 2).
     *
     * Cluster A (slim SSE): gated on [HostConfig.slim]. Slim mode subscribes
     * to the sidecar's instance-level `/slimapi/events` (no directory param,
     * every frame carries its own `directory`); legacy mode preserves the
     * `/global/event?directory=` behaviour byte-for-byte. The [directory]
     * argument is IGNORED in slim mode (the sidecar is instance-level).
     */
    fun connectSSE(directory: String?): Flow<Result<SSEEvent>> =
        sseClient.connect(
            baseUrl = hostConfig.baseUrl,
            username = hostConfig.username,
            password = hostConfig.password,
            directory = directory,
            slimMode = hostConfig.slim,
        )

    /**
     * Activates a tunnel by POSTing the password to the tunnel endpoint.
     * Uses an independent OkHttpClient without any Basic Auth interceptor,
     * since tunnel authentication uses form-encoded POST (not HTTP Basic Auth).
     *
     * §tofu R2: [hostPort] (host:port authority of [tunnelUrl]) replaces the
     * legacy `allowInsecure: Boolean`; it keys the TOFU pin lookup for the
     * one-shot tunnel POST.
     */
    suspend fun activateTunnel(
        tunnelUrl: String,
        password: String,
        hostPort: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            try {
                val client = clientFactory.tunnelClient(hostPort)
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

    // ── Cluster A: slimapi SSE + cold-start + q/p reply façade ──────────────
    //
    // These methods expose the slimapi endpoints declared on [OpenCodeApi]
    // as thin suspend wrappers under [runSuspendCatching]. They are GATED
    // on [HostConfig.slim] at the call site (upper layers) — these methods
    // themselves make no mode check (a future legacy caller could still
    // benefit if the sidecar were ever reachable from a non-slim profile).

    /**
     * Cluster A: pure digest reducer entry point. Parses a `session.digest`
     * SSE frame's properties into [SlimSessionDigest], runs [reduceSlimDigest]
     * against the in-memory [slimSseState], and returns the fetch decision
     * (or null). The caller (slim SSE event loop) executes the fetch via
     * [getSlimapiMessagesSince] when non-null.
     *
     * Properties may carry any subset of {directory, status, messageID,
     * updatedAt, archived, deleted} (§3 debounce — only changed fields).
     * Absent fields are preserved; present fields merge onto prior state.
     *
     * Returns null if the frame is malformed or no fetch is warranted.
     *
     * T11 round-2 (oracle I3): the reducer's `get → derive → put`
     * read-modify-write is wrapped in [slimStateLock] so concurrent
     * reconciles / REST-success bumps cannot land a stale `put` between
     * the reducer's `get` and `put`. The reducer itself is UNCHANGED
     * (T6 locked).
     */
    fun applySlimDigest(digest: SlimSessionDigest, token: SlimCommitToken): SlimFetchMessages? =
        slimStateMachine.applySlimDigest(digest, token)

    /**
     * Cluster A: snapshot the per-session slim SSE state (testing + upper
     * layer queries). Returns a defensive copy.
     *
     * T11 round-2 (oracle I3): acquires [slimStateLock] for a consistent
     * read (no concurrent mutator land grab between the snapshot and the
     * caller's branching on the snapshot).
     */
    fun snapshotSlimSseState(): Map<String, SlimSessionState> =
        slimStateMachine.snapshotSlimSseState()

    /**
     * Cluster A: anchor-paginated message fetch (§5 A2=A). GETs
     * `/slimapi/messages/{sid}/since/{ts}` — server returns skeletons whose
     * `time.updated >= ts`. After a successful fetch the local bookmark is
     * bumped to the max `time.updated` observed so subsequent digests only
     * trigger on strictly newer activity.
     *
     * Pass [since] = 0L for the cold-start path (no prior bookmark).
     */
    suspend fun getSlimapiMessagesSince(
        sessionId: String,
        since: Long,
        limit: Int? = null,
        before: String? = null,
        token: SlimCommitToken,
    ): Result<List<MessageWithParts>> = runSuspendCatching {
        val response = api.getSlimapiMessagesSince(sessionId, since, limit, before)

        requireSlimTokenCurrent(token)

        if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code()}")
        val items = response.body() ?: emptyList()
        if (!bumpSlimBookmarkFromItems(sessionId, items, token)) {
            throw StaleSlimCommitException()
        }
        // POST-RELEASE instrumentation: per-resync fetch outcome for the
        // SlimapiResync diagnostic surface. One line per anchored fetch.
        DebugLog.d(
            "SlimapiResync",
            "since sid=$sessionId since=$since drained=${items.size} " +
                "newest=${items.lastOrNull()?.info?.id ?: "-"}",
        )
        items
    }

    /**
     * §slim-v1-paging (Task 5 / G5 cursor): Cluster A cursor-paginated
     * skeleton fetch (`GET /slimapi/messages/{sid}?limit=…&before=…&mode=…`).
     * Used for the no-bookmark cold-start branch in [coldStartSlimSync]
     * (which cursor-follows via [drainSlimapiMessagesBounded]) and any
     * future caller that wants to walk older history without an anchor ts.
     *
     * Surfaces BOTH the items AND the `X-Next-Cursor` response header on
     * the returned [MessagesPage] (was: the pre-G5 [getSlimapiMessagesPaged]
     * returned `Result<List<MessageWithParts>>` with no header access — the
     * cursor was discarded; that method was unused and is replaced here).
     * The legacy non-slim analogue is [getMessagesPaged] (legacy branch).
     *
     * Bookmark invariant (rev-gpt MINOR #2, Option A): when [bumpBookmark]
     * is true (default), bumps the local slim SSE watermark to
     * `max(time.updated)` over the returned items — mirrors
     * [getSlimapiMessagesSince] / [getMessagesPaged] slim branch so single-
     * page callers keep the invariant for free. The cursor-following drain
     * ([drainSlimapiMessagesBounded]) passes `bumpBookmark = false` and
     * bumps ONCE at termination from the aggregated items — otherwise each
     * page would bump individually and the "single bump" claim in the
     * drain's KDoc would be inaccurate (the result is correct either way
     * because [bumpSlimBookmarkFromItems] is monotonic, but the doc/code
     * should agree).
     */
    suspend fun getSlimapiMessagesPage(
        sessionId: String,
        limit: Int? = null,
        before: String? = null,
        mode: String? = "skeleton",
        bumpBookmark: Boolean = true,
        token: SlimCommitToken,
    ): Result<MessagesPage> = runSuspendCatching {
        val response = api.getSlimapiMessages(sessionId, limit, before, mode)

        requireSlimTokenCurrent(token)

        if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code()}")
        val items = response.body() ?: emptyList()
        if (bumpBookmark) {
            if (!bumpSlimBookmarkFromItems(sessionId, items, token)) {
                throw StaleSlimCommitException()
            }
        }
        val nextCursor = response.headers()["X-Next-Cursor"]
        MessagesPage(items = items, nextCursor = nextCursor)
    }

    /**
     * Cluster A: single-message full expansion (`/slimapi/messages/{sid}/full/{mid}`).
     */
    suspend fun getSlimapiMessageFull(
        sessionId: String,
        messageId: String
    ): Result<MessageWithParts> = runSuspendCatching {
        api.getSlimapiMessageFull(sessionId, messageId)
    }

    /**
     * §5 G6 + §O-A / §L4a1/ζ-2 — budget-aware partition tree expand for
     * multiple messages full. DELEGATES to [expandBatchEngine.expandMessagesFullBatch]
     * (extracted verbatim). Kept on OCR's public surface so every existing
     * caller (PartExpandState / ExpandPartsUseCase / all tests) resolves
     * unchanged. Per-call `api` + `hostPort` are supplied to the engine via
     * providers (I3 / hostPort-live) — see [expandBatchEngine].
     */
    suspend fun expandMessagesFullBatch(
        sessionId: String,
        ids: Collection<String>,
    ): ExpandOutcome = expandBatchEngine.expandMessagesFullBatch(sessionId, ids)

    /** §B1: extract Retry-After header value as capped ms (pure, no IO). */
    internal fun retryAfterHeaderToMs(header: String?): Long {
        if (header == null) return 0L
        return ((header.toLongOrNull() ?: 0L) * 1000L).coerceIn(0L, 10_000L)
    }

    /**
     * §slimapi-client-impl-v1 §0 / §6 — best-effort extract the sidecar's
     * machine-readable error code from a Retrofit error response. The
     * sidecar's thin-route error envelope is `{"code": "…"}`; this helper
     * parses the body defensively (unknown keys ignored; any decode
     * failure / closed body / null body returns null) so callers can
     * pattern-match on [SlimapiErrorCodes] constants without
     * re-implementing the parsing at every catch-site.
     *
     * Reads [Response.errorBody] exactly once (OkHttp buffers it for
     * one-shot consumption); safe to call from any 4xx/5xx branch.
     */
    internal fun parseErrorCode(r: retrofit2.Response<*>): String? =
        parseErrorCodeFromRaw(runCatching { r.errorBody()?.string() }.getOrNull())

    /**
     * Same parse as [parseErrorCode] but accepts the already-consumed
     * errorBody string. Used by call sites that need to log the raw body
     * snippet AND parse the code from the same one-shot buffer (calling
     * [parseErrorCode] afterwards would re-read errorBody() and get null).
     */
    internal fun parseErrorCodeFromRaw(rawBody: String?): String? {
        if (rawBody == null) return null
        return try {
            val obj = json.decodeFromString<JsonObject>(rawBody)
            (obj["code"] as? JsonPrimitive)?.content
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Cluster A: cold-start sessions snapshot (`/slimapi/sessions`). Skeleton
     * rows; each carries its own `directory` so the caller can filter
     * client-side. Excludes archived by default (server side).
     *
     * §slim-reconcile-lane-repo (B4 T6): [directories] is a [List] of workdirs
     * (Retrofit expands each entry to a separate `?directory=...` query,
     * matching the v1 contract's repeated-param requirement). null = the
     * sidecar returns all directories it is aggregating for this client.
     */
    /**
     * rev-F: return [SlimSessionsPage] with parsed headers.
     * The raw list comes from response body; headers carry discovery metadata.
     * Case-insensitive header lookup, tolerant of absent headers (null).
     */
    suspend fun getSlimapiSessions(
        directories: List<String>? = null,
        roots: Boolean? = null,
        limit: Int? = null,
        search: String? = null
    ): Result<SlimSessionsPage> =
        getSlimapiSessionsDelegate(api, directories, roots, limit, search)

    /**
     * Private generic helper for aggregating cross-directory slimapi questions/
     * permissions. Encapsulates the common pattern: fetch via [apiCall],
     * check token, log per-directory errors, and fold into
     * [SlimAggregationOutcome] via [aggregationOutcome].
     */
    private suspend fun <T> getSlimapiAggregation(
        directories: List<String>?,
        token: SlimCommitToken,
        apiCall: suspend (List<String>?) -> Triple<List<T>, List<SlimapiAggregationError>, SlimapiScope?>,
        directoryOf: (T) -> String?,
        logTag: String,
    ): Result<SlimAggregationOutcome<T>> = runSuspendCatching {
        val (items, errors, scope) = apiCall(directories)

        requireSlimTokenCurrent(token)

        if (errors.isNotEmpty()) {
            DebugLog.w(TAG, "$logTag partial errors: $errors")
        }

        aggregationOutcome(
            items = items,
            errors = errors,
            requestedDirectories = directories,
            directoryOf = directoryOf,
            serverScope = scope,
        )
    }

    /**
     * Cluster A: cross-directory pending questions aggregate
     * (`/slimapi/questions`). Each entry carries `directory` + `routeToken`.
     *
     * The sidecar wraps the response as `{"items": [...], "errors": [...]}`
     * (see [SlimapiQuestionAggregation]); this method flattens to `.items`
     * for UI consumption. Per-directory `errors` are logged at debug level
     * but NOT surfaced to the UI in v1 (the sidecar already degrades —
     * partial result > no result).
     */
    suspend fun getSlimapiQuestions(
        directories: List<String>? = null,
        token: SlimCommitToken,
    ): Result<SlimAggregationOutcome<SlimapiQuestionEntry>> =
        getSlimapiAggregation(
            directories = directories,
            token = token,
            apiCall = { dirs ->
                val agg = api.getSlimapiQuestions(dirs)
                Triple(agg.items, agg.errors, agg.scope)
            },
            directoryOf = { it: SlimapiQuestionEntry -> it.directory },
            logTag = "slimapi/questions",
        )

    /**
     * Cluster A: cross-directory pending permissions aggregate. Same shape
     * as [getSlimapiQuestions].
     */
    suspend fun getSlimapiPermissions(
        directories: List<String>? = null,
        token: SlimCommitToken,
    ): Result<SlimAggregationOutcome<SlimapiPermissionEntry>> =
        getSlimapiAggregation(
            directories = directories,
            token = token,
            apiCall = { dirs ->
                val agg = api.getSlimapiPermissions(dirs)
                Triple(agg.items, agg.errors, agg.scope)
            },
            directoryOf = { it: SlimapiPermissionEntry -> it.directory },
            logTag = "slimapi/permissions",
        )

    // aggregationOutcome moved to SlimAggregationOutcome.kt

    /**
     * Cluster A: reply to a slimapi-routed question. Body carries answers +
     * routeToken; the sidecar validates the token, re-injects directory,
     * and forwards to opencode.
     */
    suspend fun replySlimapiQuestion(
        questionId: String,
        answers: List<List<String>>,
        routeToken: String?
    ): Result<Unit> = runSuspendCatching {
        val response = mutationApi.replySlimapiQuestion(
            questionId,
            SlimapiQuestionReplyRequest(answers = answers, routeToken = routeToken)
        )
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Slimapi reply failed ${response.code()}: $errorBody")
        }
    }

    /** Cluster A: reject a slimapi-routed question. See [replySlimapiQuestion]. */
    suspend fun rejectSlimapiQuestion(
        questionId: String,
        routeToken: String?
    ): Result<Unit> = runSuspendCatching {
        val response = mutationApi.rejectSlimapiQuestion(
            questionId,
            SlimapiQuestionRejectRequest(routeToken = routeToken)
        )
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Slimapi reject failed ${response.code()}: $errorBody")
        }
    }

    /**
     * Cluster A: respond to a slimapi-routed permission. Body carries the
     * response (once/always/reject) + routeToken; the sessionID is in the
     * URL path.
     */
    suspend fun respondSlimapiPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        routeToken: String?
    ): Result<Unit> = runSuspendCatching {
        val resp = mutationApi.respondSlimapiPermission(
            sessionId,
            permissionId,
            SlimapiPermissionResponseRequest(response = response.value, routeToken = routeToken)
        )
        if (!resp.isSuccessful) {
            val errorBody = resp.errorBody()?.string() ?: resp.message()
            throw Exception("Slimapi permission respond failed ${resp.code()}: $errorBody")
        }
    }

    /**
     * Cluster A: cold-start + resync snapshot (v1 contract §4). The SAME
     * code path serves BOTH the initial connect AND a `resync` frame
     * (resync = reuse cold-start per §4). Fetches:
     *  - `/slimapi/sessions` (skeleton list, default excludes archived),
     *  - `/slimapi/questions` (cross-directory pending),
     *  - `/slimapi/permissions` (cross-directory pending),
     *  - `/slimapi/messages/{openSessionId}/since/{ts}` iff [openSessionId]
     *    is supplied (uses the local bookmark, 0L if none).
     *
     * Returns the four pieces as a [SlimColdStartSnapshot]. The caller
     * (A2 bootstrap engine / resync handler) decides how to fold them into
     * UI state — this method does NOT mutate any UI slice.
     *
     * Failures degrade per-piece (each piece returns null on error; the
     * overall Result is success unless ALL pieces fail). This matches the
     * slim-sidecar-resync contract: a sidecar that lost only its messages
     * piece (e.g. opencode busy on /message) still returns sessions/q/p
     * that the client can fold; a hard sidecar-down surfaces via
     * [checkHealth] not here.
     *
     * T11 round-2 (oracle D2 — typed null/empty outcomes): each metadata
     * piece is now NULL on failure (vs `emptyList()` on success-empty) so
     * [SessionSyncCoordinator.applySlimColdStartSnapshot] can distinguish
     * "keep prior" from "replace with empty". The CE-safe wrapper is
     * [runSuspendCatching] (per-piece); plain `runCatching` is acceptable
     * ONLY for the non-suspend Retrofit `api.getSlimapiSessions(...)`
     * call (synchronous — no CE possible) but kept consistent here.
     */
    suspend fun coldStartSlimSync(
        openSessionId: String? = null,
        directories: List<String>? = null,
        token: SlimCommitToken,
    ): Result<SlimColdStartSnapshot> = runSuspendCatching {
        // §slim-reconcile-lane-repo (B4 T6): forward [directories] to
        // /slimapi/sessions (was: ignored — call always hit the unfiltered
        // path). The contract's repeated `?directory` is now produced by
        // Retrofit from the list parameter (null = sidecar decides scope).
        //
        // T11 round-2 (oracle D2): null on failure; emptyList on success.
        //
        // T11 round-3 (CE discipline, R-14): the metadata Retrofit calls
        // are suspend — plain `runCatching` swallows CancellationException,
        // violating the explicit must-hold rule. Use [runSuspendCatching]
        // so a scope cancel mid-fetch propagates as CE instead of being
        // collapsed to a null piece (which would mask the cancellation as
        // a per-piece failure).
        val sessionsPage: SlimSessionsPage? = runSuspendCatching {
            // §session-scope-narrow: pin `roots=true` + explicit limit so the
            // cold-start snapshot fetches ONLY root/main sessions of the
            // (caller-narrowed) directory set, NOT the unbounded child fan-out
            // (subagent / task children). The default `limit=100` was silently
            // truncating the list; `roots=true` filters children server-side.
            // curl-verified on the live sidecar: roots=true drops ≈244 child
            // rows; limit=500 captures the full root set (130 roots → 120 once
            // the caller's local-project directory filter is applied).
            // Combined with [SessionSyncCoordinator.performSlimResync]'s
            // `recentWorkdirs` directory narrowing + the merge in
            // [SessionSyncCoordinator.applySlimColdStartSnapshot] (fix-4),
            // this is the second of the two scope-narrowing levers.
            getSlimapiSessions(
                directories = directories,
                roots = true,
                limit = SLIM_COLDSTART_SESSION_LIMIT,
            ).getOrNull()
        }.getOrNull()
        // §slim-envelope: /questions + /permissions return {items, errors};
        // flatten `.items` for UI. Per-directory `errors` are logged here
        // (the sidecar already degrades — a 200 with partial items is the
        // expected steady-state when one upstream opencode is briefly down).
        //
        // C-D3 v2 §1.6: a stale incarnation is NOT a per-piece Failure;
        // it invalidates the entire snapshot. StaleSlimCommitException
        // rethrows out of this block; coldStartSlimSync returns
        // Result.failure(StaleSlimCommitException) instead.
        val questions: SlimAggregationOutcome<SlimapiQuestionEntry> = runSuspendCatching {
            val agg = api.getSlimapiQuestions(directories)

            requireSlimTokenCurrent(token)

            if (agg.errors.isNotEmpty()) {
                DebugLog.w(TAG, "slimapi/questions partial errors: ${agg.errors}")
            }
            aggregationOutcome(
                items = agg.items,
                errors = agg.errors,
                requestedDirectories = directories,
                directoryOf = SlimapiQuestionEntry::directory,
                serverScope = agg.scope,
            )
        }.getOrElse { error ->
            if (error is StaleSlimCommitException) throw error
            SlimAggregationOutcome.Failure(error.message)
        }
        val permissions: SlimAggregationOutcome<SlimapiPermissionEntry> = runSuspendCatching {
            val agg = api.getSlimapiPermissions(directories)

            requireSlimTokenCurrent(token)

            if (agg.errors.isNotEmpty()) {
                DebugLog.w(TAG, "slimapi/permissions partial errors: ${agg.errors}")
            }
            aggregationOutcome(
                items = agg.items,
                errors = agg.errors,
                requestedDirectories = directories,
                directoryOf = SlimapiPermissionEntry::directory,
                serverScope = agg.scope,
            )
        }.getOrElse { error ->
            if (error is StaleSlimCommitException) throw error
            SlimAggregationOutcome.Failure(error.message)
        }
        val messages: List<MessageWithParts>? = openSessionId?.let { sid ->
            // C-D3 v2 §1.5: token-threaded anchored + cursor branches.
            // A stale incarnation rethrows out of the message fetch
            // (NOT collapses to null — that would mask a host rotation as
            // "server unreachable"). Other transport/HTTP failures degrade
            // to null as before (cold-start per-piece degradation).
            val bookmark = slimStateMachine.readBookmarkOrThrowIfStale(sid, token)

            if (bookmark != null) {
                runSuspendCatching {
                    val response = api.getSlimapiMessagesSince(
                        sid, bookmark, limit = SLIMAPI_DEFAULT_PAGE_LIMIT,
                    )

                    requireSlimTokenCurrent(token)

                    if (!response.isSuccessful) {
                        return@runSuspendCatching null
                    }

                    val items = response.body() ?: emptyList()

                    if (!bumpSlimBookmarkFromItems(sid, items, token)) {
                        throw StaleSlimCommitException()
                    }

                    items
                }.getOrElse { error ->
                    if (error is StaleSlimCommitException) throw error
                    null
                }
            } else {
                runSuspendCatching {
                    drainSlimapiMessagesBounded(
                        sessionId = sid,
                        pageLimit = SLIMAPI_DEFAULT_PAGE_LIMIT,
                        itemBound = SLIMAPI_LOCAL_HISTORY_BOUND,
                        token = token,
                    )
                }.getOrElse { error ->
                    if (error is StaleSlimCommitException) throw error
                    null
                }
            }
        }
        // If ALL four pieces are null AND openSessionId was supplied with
        // at least one piece attempted, the overall Result is still
        // success — the caller folds null pieces as "keep prior". Only a
        // hard transport failure that threw out of runCatching surfaces
        // as Result.failure.
        // §#5 belt: chronological sort the drain result so any cold-start
        // merge sees chronological input even if the serving layer reorders.
        // (optional defense, reducer has the canonical sort)
        val chronoMessages = messages?.sortedWith(
            compareBy<MessageWithParts>(
                { it.info.time?.created ?: Long.MAX_VALUE },
                { it.info.id },
            )
        )
        SlimColdStartSnapshot(
            sessions = sessionsPage?.sessions,
            questions = questions,
            permissions = permissions,
            messages = chronoMessages,
            complete = sessionsPage?.complete,
            discoveryDirectories = sessionsPage?.discoveryDirectories,
            discoveryReady = sessionsPage?.discoveryReady,
        )
    }

    // ── §slim-reconcile-lane-repo: shared slim helpers ────────────────────

    /**
     * §slim-reconcile-lane-repo (B2 T3 → T11 hand-off rewire): applies a
     * REST-success path's fetched [items] to the per-session slim SSE state.
     *
     * # Task 11 hand-off rewire (T6 invariant #2 — the critical fix)
     *
     * Pre-T11 this routed through [SlimSseState.bumpUpdatedAt], which
     * (post-T6) advances **`remoteUpdatedAt`** — semantically WRONG for a
     * REST-success path. `remote*` means "what the sidecar TOLD us via a
     * digest frame"; a REST fetch's result is "what we've APPLIED locally",
     * which is the **`localApplied*`** watermark. Conflating the two made
     * `needsReconcile` see "remote = local" after every fetch and masked
     * real gaps when a later reconcile failed.
     *
     * T11 rewires it to call [onReconcileSuccess] (T6 pure primitive in
     * [SlimapiResync]) which:
     *  - advances `localAppliedUpdatedAt` + `localAppliedMessageId` to the
     *    max-`time.updated` item observed (pair-atomic, see T6 rev-gpt M3),
     *  - clears `dirty` (invariant #3 — REST reconcile succeeded),
     *  - leaves `remote*` untouched (invariant #1 — only the digest reducer
     *    advances remote).
     *
     * Consolidates the bump rule shared across three fetch sites:
     *  - [getSlimapiMessagesSince] (public slim entry point)
     *  - [getMessagesPaged] slim branch (legacy→slim routed)
     *  - [coldStartSlimSync] open-session tail
     *
     * No-op when [items] is empty (reconcile succeeded but produced no new
     * info — [onReconcileSuccess] still clears `dirty`).
     *
     * # T11 round-2 (oracle I3 — atomic boundary + dirty re-evaluation)
     *
     * The get → derive → put is now wrapped in [slimStateLock], AND after
     * `onReconcileSuccess` clears `dirty` we RE-EVALUATE [needsReconcile]
     * against the post-apply state. If a concurrent digest advanced
     * `remote*` while the REST fetch was in flight (so `localApplied*`
     * now trails `remote*` again), `dirty` MUST ratchet back to `true`.
     * Blindly trusting `onReconcileSuccess`'s `dirty = false` would clear
     * a real gap → lost-update hazard fixed by this re-evaluation.
     */
    private fun bumpSlimBookmarkFromItems(
        sessionId: String,
        items: List<MessageWithParts>,
        token: SlimCommitToken,
    ): Boolean = slimStateMachine.bumpSlimBookmarkFromItems(sessionId, items, token)

    /**
     * T11 (§3 reconcile lane wiring): reads the per-session slim SSE state.
     * The coordinator's `reconcileSession` reads this to decide
     * `needsCatchUp` + dirty / aligned branching. Returns null when the
     * session has no state (cold path — never observed a digest nor
     * fetched).
     *
     * Pure read — no mutation. T11 round-2 (oracle I3): acquires
     * [slimStateLock] so the returned state is consistent with the latest
     * commit (no concurrent mutator land grab between this read and the
     * caller's branching).
     */
    fun getSlimSessionState(sessionId: String): SlimSessionState? =
        slimStateMachine.getSlimSessionState(sessionId)

    /**
     * T11 (§3 reconcile lane wiring): marks the session as deleted upstream
     * (the reconcile probe returned HTTP 404). Applies T6's pure
     * [markDeleted] primitive: sets [SlimSessionState.deleted] = true.
     *
     * Does NOT clear `dirty` or local-applied — irrelevant once deleted (the
     * coordinator drops the row from the session list on the
     * `MarkDeleted` result branch). Idempotent (state.copy(deleted=true)).
     *
     * T11 round-2 (oracle I3): the get → derive → put is wrapped in
     * [slimStateLock].
     */
    fun markSlimSessionDeleted(
        sessionId: String,
        token: SlimCommitToken,
    ): Boolean = slimStateMachine.markSlimSessionDeleted(sessionId, token)

    /**
     * T11 (§3 reconcile lane wiring): clears the local-applied message
     * cache watermark for [sessionId] AND clears `dirty`. Used when the
     * reconcile probe returned an EMPTY array (the session exists upstream
     * but has no messages) AND the local cache had messages for it
     * (otherwise [markSlimReconcileAligned] is enough).
     *
     * Chains T6's two pure primitives per the contract's plan-line-279
     * hand-off ("empty+本地有→clearLocalMessages+清 dirty"):
     *   1. [clearLocal] — nulls `localAppliedMessageId` /
     *      `localAppliedUpdatedAt` (we have nothing applied locally
     *      anymore; remote is untouched — the probe confirmed the session
     *      exists).
     *   2. [onReconcileSuccess]`(cleared, emptyList)` — clears `dirty`
     *      (reconcile did succeed; invariant #3). Empty items list is the
     *      explicit "clear dirty without advancing localApplied" path in
     *      [onReconcileSuccess].
     *
     * T11 round-2 (oracle I3 — dirty re-evaluation): after `clearLocal →
     * onReconcileSuccess`, we RE-EVALUATE [needsReconcile]. If a
     * concurrent digest advanced `remote*` mid-probe, dirty MUST ratchet.
     *
     * The coordinator is responsible for ALSO clearing the chat slice's
     * message cache for this sid on the `ClearLocal` result branch —
     * that's a UI-slice concern, not a state-derive concern.
     */
    fun clearSlimLocalMessages(
        sessionId: String,
        token: SlimCommitToken,
    ): Boolean = slimStateMachine.clearSlimLocalMessages(sessionId, token)

    /**
     * T11 (§3 reconcile lane wiring): records that a reconcile attempt
     * FAILED for [sessionId] (transport error, 5xx, timeout). Applies T6's
     * pure [onReconcileFailure] primitive — preserves `dirty` (the session
     * still needs reconcile) and does NOT advance local-applied
     * (invariant #2 — only [onReconcileSuccess] does that).
     *
     * The actual implementation is identity (`state` unchanged) — but the
     * explicit call site documents the failure path so the next pass knows
     * to retry. Future telemetry / backoff hooks would land here.
     *
     * T11 round-2 (oracle I3): wrapped in [slimStateLock] so the failure
     * marker commit can't race a concurrent digest reducer's `put`.
     */
    fun markSlimReconcileFailure(
        sessionId: String,
        token: SlimCommitToken,
    ): Boolean = slimStateMachine.markSlimReconcileFailure(sessionId, token)

    /**
     * T11 (§3 reconcile lane wiring): records that a session is ALIGNED —
     * the reconcile probe confirmed there's nothing to fetch (either
     * `needsCatchUp` returned false, or the probe returned empty AND the
     * local cache was already empty). Clears `dirty` without advancing
     * local-applied (no new info to apply, but reconcile did succeed).
     *
     * Applies T6's [onReconcileSuccess]`(state, emptyList)` — the
     * explicit "clear dirty, no localApplied advance" path.
     *
     * T11 round-2 (oracle I3 — dirty re-evaluation): after `onReconcileSuccess`
     * clears dirty, we RE-EVALUATE [needsReconcile]. If a concurrent
     * digest advanced `remote*` mid-probe, dirty MUST ratchet.
     */
    fun markSlimReconcileAligned(
        sessionId: String,
        token: SlimCommitToken,
    ): Boolean = slimStateMachine.markSlimReconcileAligned(sessionId, token)

    /**
     * T11 round-3 (oracle D1 — eviction invalidates localApplied*):
     * invalidate the per-session local-applied watermark when the
     * corresponding in-memory [CachedSessionWindow] is evicted. After
     * this call, the next reconcile / openSession fetch sees
     * `localAppliedUpdatedAt == null` and re-enters the bounded cursor
     * drain façade ([fetchSlimInitialWindowBounded]) to rebuild the
     * window from scratch.
     *
     * # Why this is needed (oracle D1 part b)
     *
     * Pre-round-3, evicting the cache window left `localApplied*`
     * untouched — a subsequent /since fetch anchored on the (still-set)
     * watermark would return an empty tail, never rebuilding the user's
     * previously-cached older messages. Clearing `localApplied*` on
     * eviction makes the next fetch start fresh (cursor drain).
     *
     * # Semantics
     *
     *  - Sets `localAppliedMessageId = null`, `localAppliedUpdatedAt = null`.
     *  - Does NOT touch `remote*` (the digest-observed watermark is still
     *    valid — the sidecar's view of the session is independent of our
     *    cache).
     *  - Does NOT clear dirty (the eviction creates a gap; dirty
     *    reflects the remote-vs-localApplied gap and naturally ratchets
     *    via [needsReconcile] re-eval). If `dirty` was false (aligned)
     *    before, the eviction makes localApplied null → needsReconcile
     *    returns true → dirty ratchets to true here so the next reconcile
     *    pass actually re-fetches.
     *
     * Atomic: holds [slimStateLock] for the in-memory derive+write.
     * TODO(release-gate, C-D3): epoch check before commit (see
     * .ocmar/workflows/slimapi-client-v1/problem-report-wip.md).
     */
    fun invalidateSlimLocalApplied(
        sessionId: String,
        token: SlimCommitToken,
    ): Boolean = slimStateMachine.invalidateSlimLocalApplied(sessionId, token)

    /**
     * T11 round-3 (oracle D1 — retention-bound dirty): explicitly mark
     * the session's `dirty = true` (with [needsReconcile] re-eval to
     * avoid setting dirty on a truly-aligned state).
     *
     * Used by the coordinator's reconcile flow when a fetch SUCCEEDED
     * (advancing `localApplied*` + clearing `dirty` via
     * [bumpSlimBookmarkFromItems]) BUT the cache retention step failed
     * (empty result, filtered-to-nothing, or effect-bus dropped the
     * [ControllerEffect.WriteSessionWindow]). In that case the reconciler
     * must RE-RATCHET dirty so the next pass retries the fetch.
     *
     * Atomic: holds [slimStateLock] for the in-memory derive+write.
     * TODO(release-gate, C-D3): epoch check before commit (see
     * .ocmar/workflows/slimapi-client-v1/problem-report-wip.md).
     */
    fun markSlimDirty(
        sessionId: String,
        token: SlimCommitToken,
    ): Boolean = slimStateMachine.markSlimDirty(sessionId, token)

    /**
     * §slim-v1-paging (Task 5 / G5 cursor): cursor-follows the slimapi
     * `/messages/{sid}?mode=skeleton&limit={pageLimit}` endpoint, aggregating
     * pages until EITHER the sidecar stops returning `X-Next-Cursor` OR the
     * aggregated item count reaches [itemBound]. Called by
     * [coldStartSlimSync]'s no-bookmark branch.
     *
     * Boundary rules:
     *  - **message-id dedup**: the sidecar's `X-Next-Cursor` is the OLDEST
     *    id of the current page; the next page is `?before=<that id>` and
     *    MAY include the boundary id again (boundary-inclusive, per §3).
     *    The aggregation dedups by `info.id` (first-seen wins → preserves
     *    newest-first ordering across the overlap).
     *  - **bound reached mid-page**: if a page would push aggregation past
     *    [itemBound], the bound-winning prefix of that page is kept and the
     *    follow STOPS (the next older page is NOT fetched). This means the
     *    aggregation is at most `itemBound` items (never `itemBound + pageLimit`).
     *  - **bookmark invariant (rev-gpt MINOR #2, Option A)**: passes
     *    `bumpBookmark = false` into [getSlimapiMessagesPage] per page so
     *    the per-page bump is suppressed; bumps the slim SSE watermark to
     *    `max(time.updated)` over the AGGREGATED items ONCE at termination
     *    (a single bump — true "single bump", not per-page). A follow
     *    interrupted by the bound / end-of-cursor / page-count cap still
     *    updates the watermark for whatever was pulled. Mirrors
     *    [getSlimapiMessagesSince] / [getMessagesPaged] slim branch.
     *  - **transport failure mid-walk**: returns whatever was aggregated so
     *    far (runSuspendCatching at the call site swallows HTTP/transport
     *    failures → coldStartSlimSync folds the partial result). Bumps the
     *    bookmark from the aggregate BEFORE returning so the next digest
     *    re-drives from the NEW watermark (rev-gpt round-2 IMPORTANT: this
     *    is the 4th termination path — without the bump, Option A's
     *    suppressed per-page bump would lose the successfully-fetched
     *    pages' watermark and the next digest would re-fetch already-
     *    acquired history). Cancellation is NOT swallowed —
     *    [getSlimapiMessagesPage] uses [runSuspendCatching] and CE
     *    propagates out of this loop (the bump is skipped on CE; safe
     *    because the partial aggregate would be re-fetched on retry, and
     *    the bump is monotonic so no rollback risk either way).
     *  - **page-count safety cap**: a misbehaving sidecar that keeps
     *    returning `X-Next-Cursor` with empty pages would otherwise spin
     *    forever. The loop also stops after `ceil(itemBound / pageLimit) + 1`
     *    pages (covers the genuine bound-reaching case in 2 pages for the
     *    default 200/250; +1 slack for the trailing partial).
     *
     * **Future cleanup (not in T5 scope):** the four termination paths
     * (transport-failure, bound-stop, cursor-null, page-count cap) each
     * repeat the `bumpSlimBookmarkFromItems(sessionId, aggregated)` call.
     * A `try { ... } finally { bumpSlimBookmarkFromItems(sessionId,
     * aggregated) }` wrapper would unify them — skipped here because it
     * would also bump on CE propagation (a behaviour change, however
     * safe), and the reviewer's constraint pins the three normal
     * termination points as "stays as-is".
     */
    private suspend fun drainSlimapiMessagesBounded(
        sessionId: String,
        pageLimit: Int,
        itemBound: Int,
        token: SlimCommitToken,
    ): List<MessageWithParts> = drainSlimapiMessagesBoundedOutcome(
        sessionId = sessionId,
        pageLimit = pageLimit,
        itemBound = itemBound,
        token = token,
    ).items

    /**
     * T11 round-3 (oracle I2 — cursor drain typed outcome): the underlying
     * drain returns one of:
     *
     *  - [SlimDrainOutcome.Success] — the walk terminated cleanly
     *    (cursor-null, item-bound, or page-count cap). The aggregate is
     *    complete to the sidecar's current history view; the reconciler
     *    may clear dirty. The watermark was advanced for the aggregated
     *    portion (Success ALWAYS bumps).
     *  - [SlimDrainOutcome.Partial] — a mid-walk transport / page failure
     *    terminated the walk AFTER some items were aggregated. The
     *    aggregated items ARE useful (the [coldStartSlimSync] path uses
     *    them — partial history is better than nothing for the cold-start
     *    snapshot). BUT the reconciler MUST treat this as a
     *    distinguishable failure (don't clear dirty — there may be more
     *    items we couldn't reach).
     *
     * # Watermark bump on Partial — caller-controlled (T11 round-4)
     *
     * Whether the Partial variant advances `localApplied*` is controlled
     * by the caller via [drainSlimapiMessagesBoundedOutcome]'s
     * `bumpBookmarkOnPartialFailure` parameter:
     *
     *  - cold-start (`true`, default): bump to record progress.
     *  - T11 reconcile façade (`false`): NO bump — partial must not
     *    masquerade as a complete localApplied watermark (durable retry
     *    from the prior watermark until clean Success).
     *
     * See [drainSlimapiMessagesBoundedOutcome] for the full rationale.
     */
    // SlimDrainOutcome moved to MessagesPage.kt

    /**
     * T11 round-3 (oracle I2): the drain body, returning a typed outcome.
     * See [SlimDrainOutcome] for the semantics.
     *
     * Page-count safety cap, item-bound termination, cursor-null
     * termination, and message-id dedup all match the pre-round-3
     * [drainSlimapiMessagesBounded] behaviour. ONLY the transport-failure
     * termination path changed: instead of returning the partial aggregate
     * as a plain List (indistinguishable from Success), it now returns
     * [SlimDrainOutcome.Partial] carrying the cause.
     *
     * # T11 round-4 (durable partial-cursor retry — the parameterization)
     *
     * The transport-failure termination path's bump behavior is now
     * parameterized via [bumpBookmarkOnPartialFailure]:
     *
     *  - `true` (default; cold-start path): bump the partial aggregate's
     *    watermark before returning so the next cold-start re-drives from
     *    the new anchor. Cold-start's "consume partial + record progress"
     *    semantics preserved.
     *  - `false` (T11 reconcile façade [fetchSlimInitialWindowBounded]):
     *    do NOT bump — the partial progress must NOT masquerade as a
     *    complete local watermark. Returning Partial WITHOUT advancing
     *    `localApplied*` means the next reconcile re-enters the cursor
     *    drain from the SAME pre-drain watermark (no `/since/{partial}`
     *    short-circuit that would silently drop older pages). Dirty
     *    stays preserved across retries until a clean Success.
     *
     * Pre-round-4 the bump was unconditional — Partial advanced the
     * watermark even for the T11 façade, so the next reconcile's
     * `needsCatchUp` (which compares probe vs `localApplied*`, NOT dirty)
     * could see "aligned" if the partial window included the server's
     * latest, then `markSlimReconcileAligned` cleared dirty and the
     * cursor walk switched to `/since/{partial-watermark}` → older
     * pages permanently lost.
     */
    private suspend fun drainSlimapiMessagesBoundedOutcome(
        sessionId: String,
        pageLimit: Int,
        itemBound: Int,
        bumpBookmarkOnPartialFailure: Boolean = true,
        token: SlimCommitToken,
    ): SlimDrainOutcome {
        val aggregated = mutableListOf<MessageWithParts>()
        val seen = HashSet<String>()
        var before: String? = null
        // +1 slack page for the trailing partial; ceil via Int math.
        val maxPages = (itemBound + pageLimit - 1) / pageLimit + 1

        fun commitBookmarkOrThrow() {
            if (!bumpSlimBookmarkFromItems(sessionId, aggregated, token)) {
                throw StaleSlimCommitException()
            }
        }

        // G-F1: wall-clock bound for the entire cursor walk (30s). On timeout
        // surface as Partial (preserve dirty, retain aggregated items).
        return try {
        withTimeout(30_000L) {
            repeat(maxPages) {
                // C-D3 v2 §1.4: SAME entry token on every page. No recapture.
                val page = getSlimapiMessagesPage(
                    sessionId = sessionId,
                    limit = pageLimit,
                    before = before,
                    mode = "skeleton",
                    bumpBookmark = false,
                    token = token,
                ).getOrElse { error ->
                    // Stale incarnation is NOT an ordinary partial transport
                    // result; it invalidates the entire aggregate.
                    if (error is StaleSlimCommitException) {
                        throw error
                    }

                    if (bumpBookmarkOnPartialFailure) {
                        commitBookmarkOrThrow()
                    }

                    return@withTimeout SlimDrainOutcome.Partial(
                        items = aggregated.toList(),
                        cause = error,
                    )
                }

                // Even with bumpBookmark=false, a host switch during the page
                // request invalidates that page's payload.
                requireSlimTokenCurrent(token)

                // ── G-F1 loop detection ──────────────────────────────────────
                // Loop = (a) same opaque cursor returned again, OR
                //        (b) non-null cursor with zero new mids (all dupes).
                val loopDetected =
                    (before != null && page.nextCursor == before) ||
                        (page.nextCursor != null && page.items.all { it.info.id in seen })
                if (loopDetected) {
                    // If no items aggregated at all, this is a complete failure;
                    // if some items exist, this is a degraded walk.
                    return@withTimeout if (aggregated.isEmpty()) {
                        // No progress at all — surface as a Partial with cause.
                        SlimDrainOutcome.Partial(
                            items = emptyList(),
                            cause = SlimDrainLoopException("loop detected on first page: cursor=$before"),
                        )
                    } else {
                        SlimDrainOutcome.Degraded(
                            items = aggregated.toList(),
                            cause = SlimDrainLoopException("loop detected after page: cursor=$before"),
                        )
                    }
                }

                for (item in page.items) {
                    if (seen.add(item.info.id)) {
                        aggregated += item

                        if (aggregated.size >= itemBound) {
                            commitBookmarkOrThrow()
                            return@withTimeout SlimDrainOutcome.Success(aggregated.toList())
                        }
                    }
                }

                if (page.nextCursor == null) {
                    commitBookmarkOrThrow()
                    return@withTimeout SlimDrainOutcome.Success(aggregated.toList())
                }

                before = page.nextCursor
            }
            // Page-count safety cap reached (repeat exhausted maxPages).
            commitBookmarkOrThrow()
            SlimDrainOutcome.Success(aggregated.toList())
        }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            SlimDrainOutcome.Partial(aggregated.toList(), e)
        }
    }

    /**
     * T11 round-2 (oracle I2 — watermark-branched fetch façade): fetch the
     * initial message window for [sessionId] when the client has NO
     * `localAppliedUpdatedAt` (cold path / fresh after dirty-clear). Wraps
     * [drainSlimapiMessagesBoundedOutcome] in a **STRICT [Result] type** so
     * the coordinator can distinguish:
     *
     *  - `Result.success(items)` — bounded skeleton cursor drain completed
     *    cleanly (cursor-null, item-bound, or page-count cap). The local
     *    watermark was advanced inside the drain via
     *    [bumpSlimBookmarkFromItems] (which calls `onReconcileSuccess`).
     *    The coordinator may clear dirty.
     *  - `Result.failure(SlimCursorPartialException)` — a MID-WALK
     *    transport/page failure terminated the drain AFTER some items
     *    were aggregated. The coordinator MUST treat this as failure →
     *    preserve dirty. The aggregated items ARE useful for cold-start
     *    (via [drainSlimapiMessagesBounded]); the reconciler does NOT
     *    consume them (preserves dirty for retry).
     *
     * # T11 round-3 (oracle I2 review fix)
     *
     * Pre-round-3 this façade wrapped Partial as Success — violating
     * oracle's "transport/page failure distinguishable → preserve dirty".
     * Round-3 makes Partial → Result.failure so the reconciler preserves
     * dirty on this call.
     *
     * # T11 round-4 (durable partial-cursor retry — the no-bump-on-partial)
     *
     * Round-3 had a remaining gap: the drain bumped `localApplied*` on
     * Partial (unconditionally), so the next reconcile's `needsCatchUp`
     * (which compares probe vs `localApplied*`, NOT dirty) could see
     * "aligned" if the partial window included the server's latest, then
     * `markSlimReconcileAligned` cleared dirty and the cursor walk
     * switched to `/since/{partial-watermark}` → older pages permanently
     * lost.
     *
     * Round-4 fixes that by passing `bumpBookmarkOnPartialFailure = false`
     * into [drainSlimapiMessagesBoundedOutcome]. Partial now does NOT
     * advance `localApplied*`; the next reconcile re-enters the cursor
     * drain from the SAME pre-drain watermark, eventually fetching the
     * older pages that failed. Dirty stays preserved across retries
     * until a clean Success.
     *
     * The cold-start path ([coldStartSlimSync] via
     * [drainSlimapiMessagesBounded]) keeps `bumpBookmarkOnPartialFailure
     * = true` — its "consume partial + record progress" semantics are
     * unchanged.
     *
     * # Boundary ownership (oracle I2)
     *
     * This façade owns: `mode=skeleton`, page limit
     * ([SLIMAPI_DEFAULT_PAGE_LIMIT]), history bound
     * ([SLIMAPI_LOCAL_HISTORY_BOUND]), `X-Next-Cursor` traversal,
     * message-id dedup, page-count / no-progress safety, CE propagation,
     * and the ONE final local-watermark update on Success only (via
     * [bumpSlimBookmarkFromItems] → `onReconcileSuccess` → dirty-cleared
     * with T11 round-2 re-evaluation).
 *
 * # Honest caveat (G-F1)
 *
 * The cursor-walk (`GET /slimapi/messages/{sid}` with `before=` parameter)
 * shares the upstream newest-first sort / tie-break semantics with the
 * `/since/{ts}` watermark endpoint. This means the cursor walk only AVOIDS
 * the `/since` timestamp-filter boundary — it does NOT defend against an
 * upstream sort bug. Loop detection (same cursor or zero-new-item pages)
 * provides a fast-fail signal but cannot detect every pathological ordering.
 *
 * # CE discipline (R-14)
     *
     * [drainSlimapiMessagesBoundedOutcome] uses [runSuspendCatching]
     * internally (via [getSlimapiMessagesPage]); CE propagates out of
     * this façade as a thrown [CancellationException] (NOT as
     * `Result.failure(CE)`). A scope cancel mid-walk terminates the
     * cursor follow cleanly without landing a partial state mutation.
     */
    suspend fun fetchSlimInitialWindowBounded(
        sessionId: String,
        token: SlimCommitToken,
    ): Result<List<MessageWithParts>> = runSuspendCatching {
        when (
            val outcome = drainSlimapiMessagesBoundedOutcome(
                sessionId = sessionId,
                pageLimit = SLIMAPI_DEFAULT_PAGE_LIMIT,
                itemBound = SLIMAPI_LOCAL_HISTORY_BOUND,
                bumpBookmarkOnPartialFailure = false,
                token = token,
            )
        ) {
            is SlimDrainOutcome.Success -> {
                requireSlimTokenCurrent(token)
                outcome.items
            }

            is SlimDrainOutcome.Partial -> {
                // Mid-walk transport/page failure. localApplied* is
                // unchanged (no-bump-on-partial). Surface as a
                // distinguishable failure so the reconciler preserves
                // dirty AND the next reconcile re-enters the cursor
                // drain from the same pre-drain watermark.
                throw SlimCursorPartialException(outcome.cause)
            }

            is SlimDrainOutcome.Degraded -> {
                // G-F1 loop/zero-progress detection — same contract as Partial:
                // keep dirty, no watermark advance.
                throw SlimCursorPartialException(outcome.cause)
            }
        }
    }

    /**
     * T11 round-3 (oracle I2): typed exception wrapping a mid-cursor
     * transport/page failure. Carries the original [cause] so diagnostics
     * can distinguish "transport flaky mid-walk" from a clean
     * first-page failure. The aggregated items are NOT carried here — the
     * reconciler treats any Partial as preserve-dirty regardless of how
     * much was aggregated. The localApplied watermark is NOT advanced on
     * Partial (T11 round-4 no-bump-on-partial); the next reconcile
     * retries the full cursor window from the prior watermark.
     */
    class SlimCursorPartialException(cause: Throwable) :
        java.io.IOException("slim cursor drain terminated mid-walk: ${cause.message}", cause)

    companion object {
        /**
         * Default server URL. Mirrored from [HostConfig.DEFAULT_SERVER] so
         * `OpenCodeRepositoryTest.default server URL is localhost` continues
         * to pass without change.
         */
        const val DEFAULT_SERVER = HostConfig.DEFAULT_SERVER

        /**
         * §session-scope-narrow: cold-start / resync `/slimapi/sessions` page
         * size. The default (limit=100) was silently truncating the session
         * list. 500 is "effectively all" — mirrors
         * [cn.vectory.ocdroid.ui.MainViewModelTimings.sessionFullLoadLimit]
         * (also 500) which is the page size the legacy Sessions-tab global
         * fetch uses for the same "surface every root session" reason. Kept
         * as a separate const (not imported from `ui`) to avoid a
         * `data.repository` → `ui` layering inversion; the value duplication
         * is deliberate and pinned here so a wire-contract change is local.
         */
        internal const val SLIM_COLDSTART_SESSION_LIMIT = 500

        /**
         * Tag for slimapi envelope-degradation warnings (per-directory
         * `errors` inside `{items, errors}`). Surfaced at WARN so the
         * sidecar's "one opencode down → 200 with partial items" path
         * remains observable in `adb logcat`.
         */
        private const val TAG = "OpenCodeRepository"
    }

    /**
     * §L4a1/ζ-2: invalidate the thin-route cache for the current host
     * (e.g. on server reconnect). DELEGATES to [expandBatchEngine.invalidateThinRouteCache]
     * (the cache map + this logic moved to the engine). Kept on OCR's public
     * surface — callers (coordinator) and the reflection freeze test resolve
     * unchanged.
     */
    fun invalidateThinRouteCache() = expandBatchEngine.invalidateThinRouteCache()
}

// SlimAggregationOutcome, SlimColdStartSnapshot moved to their own files

/**
 * R8 slim-mode foundation: type-safe accessors for the tolerant
 * `/slimapi/health` body parser ([OpenCodeRepository.parseSlimapiHealth]).
 * Returning null on shape mismatch (rather than throwing ClassCastException)
 * lets the parser degrade per-field to null, where
 * [ServerCompatProfile.isSlimapiClientAccepted] then fail-closes.
 */
private fun JsonElement.safeObject(): JsonObject? = this as? JsonObject
private fun JsonElement.safeArray(): JsonArray? = this as? JsonArray
private fun JsonElement.safePrimitive(): JsonPrimitive? = this as? JsonPrimitive

// SlimapiPermissionEntry.toPermissionRequest() moved to SlimAggregationOutcome.kt
