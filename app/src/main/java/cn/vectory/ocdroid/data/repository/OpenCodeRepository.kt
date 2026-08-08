package cn.vectory.ocdroid.data.repository

import androidx.annotation.VisibleForTesting
import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.api.SSEClient
import cn.vectory.ocdroid.data.api.*
import cn.vectory.ocdroid.data.model.*
import cn.vectory.ocdroid.data.repository.gateway.CatalogGateway
import cn.vectory.ocdroid.data.repository.gateway.ConnectionGateway
import cn.vectory.ocdroid.data.repository.gateway.FileVcsGateway
import cn.vectory.ocdroid.data.repository.gateway.InteractionGateway
import cn.vectory.ocdroid.data.repository.gateway.MessageGateway
import cn.vectory.ocdroid.data.repository.gateway.SessionGateway
import cn.vectory.ocdroid.data.repository.http.HttpHeaders
import cn.vectory.ocdroid.data.repository.http.ClientCertMaterial
import cn.vectory.ocdroid.data.repository.http.SlimapiContract
import cn.vectory.ocdroid.data.repository.http.SlimapiErrorCodes
import cn.vectory.ocdroid.data.repository.http.SslConfig
import cn.vectory.ocdroid.data.repository.http.applyClientIdentityHeaders
import cn.vectory.ocdroid.data.repository.http.buildMutualTlsConfig
import cn.vectory.ocdroid.data.repository.http.hostPortFromUrl
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import cn.vectory.ocdroid.util.exponentialBackoffMs
import cn.vectory.ocdroid.util.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.Retrofit
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.identity.ConnectionIdentity

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
     * profile (the M2 invariant).
     */
    private val serverCompatProfile: ServerCompatProfile = ServerCompatProfile(),
) : ConnectionRepository, SessionRepository, MessageRepository, InteractionRepository, CatalogRepository, FileVcsRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false  // Omit null fields - server rejects model: null
        encodeDefaults = true  // Include type in parts - server needs discriminator
    }

    /**
     * Test seam: injectable wall-clock budget (ms) for [expandMessagesFullBatch].
     * lite-v2-dev: ExpandBatchEngine retired; storage is now a local field.
     */
    internal var expandWallClockBudgetMsForTest: Long? = null

    /**
     * Test seam: injectable TTL (ms) for thin-route cache. lite-v2-dev:
     * ExpandBatchEngine retired; retained as a no-op field for test compat.
     */
    internal var thinRouteTtlMsForTest: Long? = null

    // P11 §4 (Option B): the OkHttp / SSL / interceptor construction graph
    // is consolidated in [RepositoryNetworkGraph] (internal, non-Hilt).
    // The single volatile bundle below is the only client-generation
    // publication point; all API/client accessors are derived from it.
    private val networkGraph = RepositoryNetworkGraph(
        trafficTracker,
        trafficLogger,
        serverCompatProfile,
        // §B (slimapi-v2-adapt-traffic-plan §B): lazy device-id provider for
        // ClientIdentityInterceptor. Resolves from the Hilt field-injected
        // ClientIdStore (below) at request time — the graph is built during
        // this field initializer, BEFORE Hilt field injection populates
        // [clientIdStore], so the provider MUST defer the read (mirrors the
        // identityStoreOrFallback lazy pattern).
        clientIdProvider = { clientIdStoreOrFallback().getDeviceId() },
    )

    /** The sole volatile publication point for all network clients and APIs. */
    @Volatile
    private var currentClientBundle: ClientBundle? = buildClientBundle(
        hostSnapshot = networkGraph.hostConfig.snapshot(),
        generation = 0L,
        effectiveSslConfig = networkGraph.sslConfigFactory.sslConfigFor(networkGraph.hostConfig.hostPort),
    )

    /**
     * §concurrency-refactor: serializes concurrent [configure] bodies against
     * each other (all in-flight configures are mutually exclusive). The
     * blocking SSL/OkHttp/client-cert compute phase ([resolveCandidateSsl] +
     * [buildClientBundle]) runs UNDER this lock but NOT under the repo monitor
     * (`synchronized(this)`), so Main-thread readers of the published volatile
     * bundle no longer block on the blocking compute. Lock order is STRICTLY
     * `configureLock → this` — the publish critical section (Phase 2) may
     * acquire `this` while holding `configureLock`, but `configureLock` MUST
     * NEVER be acquired while holding `this` (lock-order inversion → deadlock).
     * See [configure].
     *
     * §rev-ds-🠮1 (scope accuracy): this lock covers [configure] ONLY.
     * [rebuildClients] is `@Synchronized` on `this` and does NOT acquire this
     * lock, so its compute is NOT mutually exclusive with [configure]'s
     * Phase 1. [rebuildClients] currently has ZERO production call sites
     * (dead code — grep-confirmed). Reviving it requires defining a lock
     * protocol FIRST (e.g. acquiring `configureLock`), or its read of the
     * factory mirror could race [configure]'s Phase-1 `configureTrustAll`
     * write (torn "new trustAll + old mutualTlsConfig" — self-healing but
     * observable).
     */
    private val configureLock = Any()

    /** Called while the repository monitor is held after a new bundle is published. */
    @Volatile
    internal var onBundlePublished: ((Long, String) -> Unit)? = null

    private val api: OpenCodeApi get() = requireClientBundle().restApi
    internal val sseClient: SSEClient get() = requireClientBundle().sseClient
    private val commandApi: OpenCodeApi get() = requireClientBundle().commandApi
    private val mutationApi: OpenCodeApi get() = requireClientBundle().mutationApi

    internal fun currentClientBundle(): ClientBundle? = currentClientBundle

    /**
     * Test-only API-facade substitution at the same volatile publication
     * boundary used by production. The replacement must retain every
     * generation/resource field; this prevents routing tests from creating a
     * mixed client generation or accidentally retiring clients they still
     * need. The seam never retires the old object because both bundles share
     * the same generation-owned resources.
     */
    @VisibleForTesting
    @Synchronized
    internal fun replaceClientBundleForTest(transform: (ClientBundle) -> ClientBundle) {
        val current = requireClientBundle()
        val replacement = transform(current)
        check(replacement.generation == current.generation) {
            "test bundle replacement must keep generation"
        }
        check(replacement.hostSnapshot === current.hostSnapshot) {
            "test bundle replacement must keep host snapshot"
        }
        check(replacement.effectiveSslConfig === current.effectiveSslConfig) {
            "test bundle replacement must keep SSL config"
        }
        check(replacement.clientCertError == current.clientCertError) {
            "test bundle replacement must keep certificate state"
        }
        check(replacement.restHttp === current.restHttp) {
            "test bundle replacement must keep rest client"
        }
        check(replacement.restRetrofit === current.restRetrofit) {
            "test bundle replacement must keep rest Retrofit"
        }
        check(replacement.sseHttp === current.sseHttp) {
            "test bundle replacement must keep SSE client"
        }
        check(replacement.sseClient === current.sseClient) {
            "test bundle replacement must keep SSE facade"
        }
        check(replacement.commandHttp === current.commandHttp) {
            "test bundle replacement must keep command client"
        }
        check(replacement.commandRetrofit === current.commandRetrofit) {
            "test bundle replacement must keep command Retrofit"
        }
        check(replacement.mutationHttp === current.mutationHttp) {
            "test bundle replacement must keep mutation client"
        }
        check(replacement.mutationRetrofit === current.mutationRetrofit) {
            "test bundle replacement must keep mutation Retrofit"
        }
        currentClientBundle = replacement
    }

    internal fun hostSnapshot(): HostSnapshot = requireClientBundle().hostSnapshot

    private fun requireClientBundle(): ClientBundle =
        currentClientBundle ?: error("OpenCodeRepository has no published ClientBundle")

    /**
     * CP1 (notify Phase-0): the connection identity store for epoch-guard.
     * Injected by Hilt (field injection); read lazily by [epochProvider] lambda.
     */
    @Inject lateinit var identityStore: ConnectionIdentityStore

    /**
     * Plain unit-test constructions do not receive Hilt field injection. Keep
     * token capture usable for those callers while production still replaces
     * this with the process-shared injected store before any work starts.
     */
    private val fallbackIdentityStore = ConnectionIdentityStore()

    private fun identityStoreOrFallback(): ConnectionIdentityStore =
        if (::identityStore.isInitialized) identityStore else fallbackIdentityStore

    /**
     * §B (slimapi-v2-adapt-traffic-plan §B): the device-id store backing
     * `X-Client-Id`. Injected by Hilt (field injection) — same pattern as
     * [identityStore]. Bound to the ESP-backed
     * [cn.vectory.ocdroid.data.repository.http.EspClientIdStore] in
     * production; [fallbackClientIdStore] (in-memory) is used in plain
     * unit-test constructions that bypass Hilt, so `X-Client-Id` is still
     * resolvable (with a fresh per-instance UUID) in tests.
     *
     * Read lazily (via [clientIdStoreOrFallback]) by [networkGraph]'s
     * `clientIdProvider` lambda + by the health/cert probe sites — the graph
     * is built before Hilt populates this field, so the read MUST be lazy.
     */
    @Inject lateinit var clientIdStore: cn.vectory.ocdroid.data.repository.http.ClientIdStore

    private val fallbackClientIdStore: cn.vectory.ocdroid.data.repository.http.ClientIdStore =
        cn.vectory.ocdroid.data.repository.http.InMemoryClientIdStore()

    private fun clientIdStoreOrFallback(): cn.vectory.ocdroid.data.repository.http.ClientIdStore =
        if (::clientIdStore.isInitialized) clientIdStore else fallbackClientIdStore

    // ── Stage A: internal Gateway delegates (stateless, zero mutable fields) ──
    private val connectionGateway = ConnectionGateway(
        bundleProvider = { requireClientBundle() },
        serverCompatProfile = serverCompatProfile,
        json = json,
        networkGraph = networkGraph,
        identityProvider = { clientIdStoreOrFallback().getDeviceId() ?: "" },
    )
    private val sessionGateway = SessionGateway(
        bundleProvider = { requireClientBundle() },
        serverCompatProfile = serverCompatProfile,
        json = json,
    )
    private val messageGateway = MessageGateway(
        bundleProvider = { requireClientBundle() },
        serverCompatProfile = serverCompatProfile,
        json = json,
    )
    private val interactionGateway = InteractionGateway(
        bundleProvider = { requireClientBundle() },
        serverCompatProfile = serverCompatProfile,
    )
    private val catalogGateway = CatalogGateway(
        bundleProvider = { requireClientBundle() },
        serverCompatProfile = serverCompatProfile,
    )
    private val fileVcsGateway = FileVcsGateway(
        bundleProvider = { requireClientBundle() },
    )

    // ── §B3-retirement: clean connection-stamp capture (Phase 3, Step 3.1 / Phase 4b: shim deleted) ────
    //
    // ConnectionCapture captures identityStore identity + epoch + ClientBundle
    // generation + endpoint fingerprint so a stale async response is detected.
    // This is the faithful 1:1 replacement of the retired slim-token shim
    // (SlimCommitToken), which checked issuedReady + identityEpoch + identity +
    // bundleGeneration + endpointFp.
    //
    // The generation + endpointFp checks are ESSENTIAL: call sites such as
    // ConnectionBootstrapEngine.performAttempt and applySavedSettings call
    // repository.configure(...) WITHOUT calling identityStore.beginReconfigure()
    // first. configure() bumps ClientBundle.generation but does NOT bump the
    // identityStore epoch. So a re-bootstrap can rotate the generation/endpoint
    // while epoch+identity stay the same — and a guard that only checks
    // epoch+identity would WRONGLY pass a stale response.

    /**
     * §B3-retirement: clean connection-stamp capture replacing the retired
     * slim-token shim. Captures identityStore identity + epoch + ClientBundle
     * generation + endpoint fingerprint so a stale async response (whose
     * capture predates a host reconfigure or client-bundle rotation) is
     * detected 1:1 matching the retired slim-token shim semantics.
     *
     * @property identity The [ConnectionIdentity] at capture time (null = unready).
     * @property epoch The identityStore epoch at capture time.
     * @property generation [ClientBundle.generation] at capture time (null if no bundle).
     * @property endpointFp [ClientBundle.endpointFp] at capture time (null if no bundle).
     */
    internal data class ConnectionCapture(
        val identity: ConnectionIdentity?,
        val epoch: Long,
        /** ClientBundle generation captured at the same instant (B3 retirement: faithful equivalent of the slim-token generation check). */
        val generation: Long?,
        /** Endpoint fingerprint captured from the same ClientBundle. */
        val endpointFp: String?,
    )

    /** Capture the current connection identity + epoch + bundle generation + endpointFp (call BEFORE any suspend). */
    internal fun captureConnection(): ConnectionCapture {
        val cap = identityStoreOrFallback().capture()
        val bundle = currentClientBundle()
        return ConnectionCapture(
            identity = cap.identity,
            epoch = cap.epoch,
            generation = bundle?.generation,
            endpointFp = bundle?.endpointFp,
        )
    }

    /**
     * True iff no reconfigure has bumped the epoch/rotated the identity / rotated
     * the ClientBundle generation or endpoint since [capture].
     *
     * Checks readiness (identity != null) + 4 fields (epoch, identity, generation,
     * endpointFp) — mirrors the retired isSlimCommitTokenCurrent semantics 1:1.
     */
    internal fun isConnectionCaptureCurrent(capture: ConnectionCapture): Boolean {
        val live = identityStoreOrFallback().capture()
        val bundle = currentClientBundle()
        // Readiness: a capture taken before any identity was bound (cold start /
        // post-beginReconfigure null window) is NEVER current — mirrors the old
        // issuedReady invariant.
        if (capture.identity == null) return false
        return live.epoch == capture.epoch &&
            live.identity == capture.identity &&
            bundle?.generation == capture.generation &&
            bundle?.endpointFp == capture.endpointFp
    }

    /**
     * Atomic commit gate: runs [commit] iff the connection is still at [capture]'s
     * identity/epoch/generation/endpointFp. The generation + endpointFp checks run
     * under the repository monitor (synchronized(this)) — the SAME monitor the
     * retired commitIfSlimTokenCurrent used — then delegates to
     * [ConnectionIdentityStore.commitIfCurrent] for the epoch + identity + commit
     * under the identityStore lock, so a host reconfigure cannot slip between the
     * generation check and the commit (TOCTOU closed).
     */
    internal fun commitIfConnectionCaptureCurrent(capture: ConnectionCapture, commit: () -> Unit): Boolean = synchronized(this) {
        val bundle = currentClientBundle() ?: return false
        // Readiness gate.
        if (capture.identity == null) return false
        // Generation + endpoint gate (under repo monitor, same as the retired shim).
        if (capture.generation != bundle.generation) return false
        if (capture.endpointFp != bundle.endpointFp) return false
        // Epoch + identity gate + commit (under identityStore lock — TOCTOU-closed
        // against beginReconfigure).
        identityStoreOrFallback().commitIfCurrent(
            identity = capture.identity,
            epoch = capture.epoch,
            commit = commit,
        )
    }

    /**
     * §B3-retirement (Phase 4b): retained no-op for the resetLocalDataAndResync
     * call-site contract. The slim incarnation system was fully retired in B3;
     * identityStore.beginReconfigure() handles epoch/marker rotation.
     */
    fun resetSlimForLocalWipe(): Unit = Unit

    // ── B-P0-2 slim watermark forwarders: RETIRED (lite-v2-dev plan §4.1) ─────
    // SlimSseStateMachine + SlimFullReconciler + MessageEventSeqWatermark deleted;
    // the watermark mutation surface no longer exists. The skeleton reload
    // coordinator path uses getSlimapiMessagesSkeleton directly.

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
    override val isSlimMode: Boolean get() = connectionGateway.isSlimMode

    // ── ι-A capability access surface (forwarders → ServerCompatProfile) ──
    // L4+ 消费者（协调/service/UI，多数已持 repository 句柄，部分以函数参数接收）
    // 通过这些**语义能力查询**读连接能力，而非裸 [isSlimMode]（raw mode）。
    // 这满足 plan §6「L4+ isSlimMode 零命中」验收：grep `isSlimMode` 在 L4+ 为空，
    // 这些读的是 capability（forwarder 透传到 [serverCompatProfile]，source-of-truth
    // 在 data.repository 层）。forwarder 是访问便利，非 mode 泄漏——返回的是语义能力，
    // 非 raw slim flag。详见 [ServerCompatProfile.supportsWatermarkResync] 等 KDoc
    // （mode-vs-readiness 区分：这些是 mode capability，非 health/readiness）。

    /** ι-A / lite-v2-dev: 是否支持 watermark 重同步（= slim 连接）。L4+ 用此替代
     *  裸 `isSlimMode` 做重同步门。lite-v2 起 [ServerCompatProfile] 只保留
     *  `slimConnection`（plan §4.4），本 forwarder 直接读它（语义等价：
     *  slim 连接即支持 skeleton/watermark 重同步）。 */
    override val supportsWatermarkResync: Boolean get() = connectionGateway.supportsWatermarkResync

    /** ι-A / lite-v2-dev: 是否支持 token-stream 重同步。lite-v2 起 v2 协议下
     *  `tokenStreamEnabled = slimConnection`（plan §2.5，不再 probe health
     *  features.tokenStream）；本 forwarder 直接读 slimConnection（语义等价）。 */
    override val supportsTokenStreamResync: Boolean get() = connectionGateway.supportsTokenStreamResync

    /** ι-A / lite-v2-dev: session status 是否走 slim 扇出（vs legacy bulk
     *  `/session/status`）。lite-v2 起等价于 slimConnection（plan §4.4）。 */
    override val usesSlimStatusFanOut: Boolean get() = connectionGateway.usesSlimStatusFanOut

    /** §slimapi-p3: P3 fan-out collapse — bulk session tree via slimapi
     *  is available when the connection is slim-capable (same flag as
     *  [usesSlimStatusFanOut] and other slim paths). */
    override val supportsBulkSessionTree: Boolean get() = connectionGateway.usesSlimStatusFanOut

    /** §rev-ds ISSUE 2: global question aggregation is a slim sidecar feature,
     *  independent of status-endpoint support. Same flag as [usesSlimStatusFanOut]
     *  — both map to [ConnectionGateway.slimConnection]/[ServerCompatProfile.slimConnection]. */
    override val supportsGlobalQuestionFetch: Boolean get() = connectionGateway.usesSlimStatusFanOut

    /** §slimapi-questions: sidecar serves the cross-directory `/slimapi/questions`
     *  aggregate. Fail-open default; flipped sticky-false on the first observed
     *  404 from an older sidecar. The slim-questions branch in
     *  `QuestionReconcileWorker` / `ForegroundCatchUpController` ANDs this with
     *  [supportsGlobalQuestionFetch] to decide endpoint vs per-dir fan-out. */
    override val supportsSlimQuestions: Boolean get() = serverCompatProfile.supportsSlimQuestions

    private data class CandidateSsl(
        val config: SslConfig,
        val clientCertError: String?,
    )

    /**
     * Resolve candidate TLS purely from the candidate inputs — does NOT read
     * the held [SslConfigFactory] mTLS cache (which may be stale from a prior
     * configure). This mirrors the original pre-L7 semantic (which read the
     * TOFU pin store directly, never [sslConfigFor]) and preserves the
     * "clear clientCert → SystemDefault" invariant: a null [clientCert] with
     * no pin must yield SystemDefault even if a prior configure loaded mTLS
     * material into the factory (that stale cache is cleared only later, in
     * [publishClientBundle] via [configureClientCert]).
     *
     * L7: [trustAll] routes to [SslConfig.TrustAll] when [clientCert] is null.
     */
    private fun resolveCandidateSsl(
        hostPort: String?,
        clientCert: ClientCertMaterial?,
        trustAll: Boolean,
    ): CandidateSsl {
        val preparedClientCert = clientCert?.let { material ->
            runCatching { buildMutualTlsConfig(material) }
        }
        val config = preparedClientCert?.getOrNull()
            ?: if (trustAll) SslConfig.TrustAll else SslConfig.SystemDefault
        return CandidateSsl(
            config = config,
            clientCertError = preparedClientCert?.exceptionOrNull()?.message,
        )
    }

    private fun buildClientBundle(
        hostSnapshot: HostSnapshot,
        generation: Long,
        effectiveSslConfig: SslConfig,
        clientCertError: String? = null,
    ): ClientBundle {
        val factory = networkGraph.clientFactoryFor(hostSnapshot, effectiveSslConfig)
        val ownedClients = mutableListOf<OkHttpClient>()
        return try {
            val restHttp = factory.restClient(hostSnapshot.hostPort)
            ownedClients += restHttp
            val restRetrofit = buildRetrofit(restHttp, hostSnapshot.baseUrl)
            val restApi = restRetrofit.create(OpenCodeApi::class.java)

            val sseHttp = factory.sseClient(hostSnapshot.hostPort)
            ownedClients += sseHttp
            val sseClient = SSEClient(sseHttp)

            val commandHttp = factory.commandClient(hostSnapshot.hostPort)
            ownedClients += commandHttp
            val commandRetrofit = buildRetrofit(commandHttp, hostSnapshot.baseUrl)
            val commandApi = commandRetrofit.create(OpenCodeApi::class.java)

            val mutationHttp = factory.mutationClient(hostSnapshot.hostPort)
            ownedClients += mutationHttp
            val mutationRetrofit = buildRetrofit(mutationHttp, hostSnapshot.baseUrl)
            val mutationApi = mutationRetrofit.create(OpenCodeApi::class.java)

            ClientBundle(
                generation = generation,
                hostSnapshot = hostSnapshot,
                effectiveSslConfig = effectiveSslConfig,
                clientCertError = clientCertError,
                restHttp = restHttp,
                restRetrofit = restRetrofit,
                restApi = restApi,
                sseHttp = sseHttp,
                sseClient = sseClient,
                commandHttp = commandHttp,
                commandRetrofit = commandRetrofit,
                commandApi = commandApi,
                mutationHttp = mutationHttp,
                mutationRetrofit = mutationRetrofit,
                mutationApi = mutationApi,
                ownedClients = ownedClients,
            )
        } catch (error: Throwable) {
            ownedClients.forEach { client ->
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
            }
            DebugLog.e("SlimReadiness", "configure failed; readiness remains false", error)
            throw error
        }
    }

    // §rev-ds-🟡4: this @Synchronized is LOAD-BEARING for the [rebuildClients]
    // path (which calls publishClientBundle directly, relying on this monitor
    // for mutual exclusion with readers). In the [configure] path it is
    // redundant-but-harmless: configure's Phase 2 already holds synchronized(this)
    // when calling this, and Java monitors are reentrant. Do NOT remove this
    // annotation thinking "configure already holds the lock" — [rebuildClients]
    // does not (it is @Synchronized on `this` only).
    @Synchronized
    private fun publishClientBundle(
        candidate: ClientBundle,
        hostSnapshot: HostSnapshot,
        clientCert: ClientCertMaterial? = null,
        updateClientCert: Boolean = false,
        trustAll: Boolean = false,
        updateTrustAll: Boolean = false,
        /**
         * §concurrency-refactor: the pre-built [CandidateSsl] (config + error)
         * computed OUTSIDE the repo monitor by [configure]'s Phase 1. When
         * [updateClientCert] is true, the factory's mTLS mirror is published
         * from this pre-built resolution via
         * [SslConfigFactory.publishClientCertResolution] instead of
         * re-invoking [SslConfigFactory.configureClientCert] (which would
         * parse the PKCS12 a SECOND time, inside the monitor). Default null
         * leaves the [rebuildClients] path (which passes
         * `updateClientCert=false`) byte-for-byte unaffected.
         */
        clientCertResolution: CandidateSsl? = null,
    ) {
        val previous = currentClientBundle
        // This is the sole client-generation publication write.
        currentClientBundle = candidate
        // C3: publish the StoreState stamp under the same repository monitor as
        // the volatile bundle write. Token-stream reducers therefore cannot
        // observe a new bundle paired with the previous stamp.
        onBundlePublished?.invoke(candidate.generation, candidate.endpointFp)
        networkGraph.hostConfig.configure(
            baseUrl = hostSnapshot.baseUrl,
            username = hostSnapshot.username,
            password = hostSnapshot.password,
            hostPort = hostSnapshot.hostPort,
            slim = hostSnapshot.slimHost,
            trustAll = trustAll,
        )
        // This mirror is updated only after the immutable bundle is published;
        // active clients and getters use the bundle's effective SSL value.
        if (updateClientCert) {
            // §concurrency-refactor: publish the PRE-BUILT mTLS (parsed ONCE in
            // resolveCandidateSsl, outside the monitor) instead of re-invoking
            // configureClientCert (a second p12 parse inside the monitor). The
            // three-branch semantics are byte-identical to configureClientCert:
            //   clientCertResolution == null (rebuildClients path never sets
            //   updateClientCert=true together with a null resolution) falls
            //   back to configureClientCert for safety — but the only caller
            //   that sets updateClientCert=true (configure) always supplies a
            //   non-null resolution.
            val resolution = clientCertResolution
            if (resolution != null) {
                val resolved = resolution.config as? SslConfig.MutualTLS
                networkGraph.sslConfigFactory.publishClientCertResolution(
                    material = clientCert,
                    resolved = resolved,
                    error = resolution.clientCertError,
                )
            } else {
                networkGraph.sslConfigFactory.configureClientCert(clientCert)
            }
        }
        if (updateTrustAll) {
            networkGraph.sslConfigFactory.configureTrustAll(trustAll)
        }
        previous?.retire()
    }

    private fun buildRetrofit(client: OkHttpClient, baseUrl: String): Retrofit {
        val url = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
        return Retrofit.Builder()
            .baseUrl(url.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Synchronized
    private fun rebuildClients() {
        val current = requireClientBundle()
        val candidate = buildClientBundle(
            hostSnapshot = current.hostSnapshot,
            generation = current.generation + 1L,
            effectiveSslConfig = networkGraph.sslConfigFactory.sslConfigFor(current.hostSnapshot.hostPort),
            clientCertError = current.clientCertError,
        )
        publishClientBundle(candidate, current.hostSnapshot)
    }

    /**
     * L7: configure the live host. [trustAll] enables per-server trust-all;
     * mTLS takes priority — when an mTLS client cert is presented, trust-all
     * is ignored (see [SslConfigFactory.sslConfigFor]).
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
     *
     * §concurrency-refactor: the method is NO LONGER `@Synchronized`. The
     * blocking SSL/OkHttp/client-cert compute phase (Phase 1) now runs under
     * [configureLock] but OUTSIDE the repo monitor (`synchronized(this)`),
     * so Main-thread readers of the published volatile bundle no longer
     * block on it. The design verified the Phase-1 compute reads NO shared
     * mutable state ([resolveCandidateSsl] is pure — never reads the held
     * mTLS cache; [buildClientBundle] builds from the immutable candidate
     * snapshot), so narrowing the monitor is safe. Only the generation stamp
     * + volatile publish + [onBundlePublished] dispatch + [setSlimConnection]
     * (Phase 2) run under `synchronized(this)`, preserving the C3
     * stamp-pairing contract. Lock order is strictly [configureLock] → this.
     */
    fun configure(
        baseUrl: String,
        username: String? = null,
        password: String? = null,
        hostPort: String? = null,
        clientCert: ClientCertMaterial? = null,
        /**
         * R8 slim-mode foundation: 当前 profile 是否启用省流模式（指向 oc-slimapi
         * sidecar）。透传给 [networkGraph.hostConfig.slim]，由 [SlimapiVersionInterceptor] 与
         * [checkHealth] / [checkHealthFor] 路由使用。
         *
         * 默认 false（legacy 直连 opencode）——保持现有调用方（ui / service）行为
         * 完全不变；待 EffectiveConnectionConfig / 上游 controller 接入 slim 字段后
         * 端到端生效。
         */
        slim: Boolean = false,
        /**
         * L7: per-server trust-all flag. mTLS takes priority (see HostProfile.trustAll).
         * Set on [SslConfigFactory] BEFORE [resolveCandidateSsl] so the
         * candidate SSL config reflects trust-all when no mTLS is active.
         */
        trustAll: Boolean = false,
    ) = synchronized(configureLock) {
        // ── Phase 1: compute (under configureLock; NOT under the repo monitor) ──
        // The blocking SSL/OkHttp/client-cert work (resolveCandidateSsl +
        // buildClientBundle) lives here. resolveCandidateSsl is PURE (its
        // invariant — never reads the held mTLS cache — is the load-bearing
        // fact behind the lock narrowing). The PKCS12 is parsed ONCE here;
        // the pre-built MutualTLS is carried into Phase 2 and published via
        // publishClientCertResolution (no second parse inside the monitor).

        // L7: set trustAll on the factory BEFORE resolveCandidateSsl so the
        // candidate SSL config (built via sslConfigFor) reflects the flag.
        // §concurrency-refactor: relocated from the old @Synchronized body into
        // Phase 1 (under configureLock, outside synchronized(this)) for ZERO
        // behavior delta. Redundant on the success path (publishClientBundle
        // writes the same value) but observable on the FAILURE path (a failed
        // configure today still flips the volatile trustAllEnabled). It's a
        // volatile write; no reader pairs it with monitor-held state. Do NOT
        // delete it.
        networkGraph.sslConfigFactory.configureTrustAll(trustAll)
        // P11: build every component from one immutable candidate snapshot.
        // Neither HostConfig nor the held SSL certificate state is changed
        // until this complete build succeeds.
        val candidateSnapshot = HostSnapshot.from(
            baseUrl = baseUrl,
            username = username,
            password = password,
            hostPort = hostPort,
            slimHost = slim,
            trustAllHost = trustAll,
        )
        val candidateSsl = resolveCandidateSsl(candidateSnapshot.hostPort, clientCert, trustAll)
        val preBuilt = buildClientBundle(
            hostSnapshot = candidateSnapshot,
            generation = PLACEHOLDER_GENERATION,   // sentinel; NEVER published — Phase 2 stamps the real gen
            effectiveSslConfig = candidateSsl.config,
            clientCertError = candidateSsl.clientCertError,
        )

        // ── Phase 2: stamp + publish (narrow critical section under the repo monitor) ──
        synchronized(this) {
            // Stamp the real monotonic generation (prev + 1) onto the pre-built
            // bundle. withGeneration carries ownedGenerationClients so retire()
            // coverage is preserved on the stamped copy.
            val candidate = preBuilt.withGeneration(requireClientBundle().generation + 1L)
            // Atomic client publication, then old-generation retirement. The
            // source/readiness publication below remains after completion.
            publishClientBundle(
                candidate = candidate,
                hostSnapshot = candidateSnapshot,
                clientCert = clientCert,
                clientCertResolution = candidateSsl,
                updateClientCert = true,
                trustAll = trustAll,
                updateTrustAll = true,
            )
            // Wave2-cleanup: sessionSource/messageSource routing layer removed.
            // getSessions/getSessionsForDirectory branch on serverCompatProfile.slimConnection
            // directly; getMessagesPagedImpl calls api.getMessages() inline.
            // ι-A (capability read-model): 发布能力 mode 仅在整条 ssl/host/client/readiness
            // 事务全成功后。configure() 是 fail-forward（不回滚旧 networkGraph.hostConfig），
            // 但能力模型只反映"最近一次成功 live 的 mode"，与 readiness 同语义。
            //
            // 受管写点（I8 扩展）：本行是 setSlimConnection 的唯一受管调用方；与 probe
            // 写点（update/updateSlimapi，由 checkHealthFor / probeSlimapiHealth 尾部调用）
            // 并列。仍在 configure() 同步块内（I5/I6/I7 不变量保持）——§concurrency-refactor
            // 将其留在 synchronized(this) 内（Phase 2），AFTER publish，不在 failure path。
            // reconfigure 中途（新栈未确认前）slimConnection 仍报旧 mode = 仍 operative 的
            // 旧连接；L4+ 无锁读到的始终是"当前仍 live 的 mode"。mode 在此刻 authoritatively
            // 确立（= networkGraph.hostConfig.slim），不能从 serverCompatProfile 现有字段推导（legacy 模式
            // 下 slimapi* 全 null；slim 模式首次 health 成功前 slimapi* 也全 null）。
            serverCompatProfile.setSlimConnection(slim)
        }
    }

    /**
     * §2.4: the current effective [SslConfig] for the live host (mTLS priority
     * over trust-all, SystemDefault safe fallback). Callers
     * ([HttpImageHolder] / cold-start image sync) use this to mirror the same
     * trust policy onto the markdown image client.
     *
     * §concurrency-refactor: NO LONGER `@Synchronized`. It reads the immutable
     * published bundle's [ClientBundle.effectiveSslConfig] (via
     * [ConnectionGateway.currentSslConfig] → [bundleProvider]), NOT the
     * mutable [SslConfigFactory] state — the old `@Synchronized` + "reads the
     * mutable sslConfigFactory state" kdoc rationale (v3-glmer R2) was
     * stale/wrong. The published bundle is a single volatile reference to a
     * fully-constructed immutable object, so the read is lock-free and
     * self-consistent (every field is observed together). Removing the monitor
     * eliminates Main-thread blocking for the `currentSslConfig()` readers
     * (ConnectionActions / HostProfileController) entirely.
     */
    override fun currentSslConfig(): SslConfig = connectionGateway.currentSslConfig()

    /**
     * 当前 live SSL 配置是否走 mTLS 路径（客户端证书已配置并加载）。
     * Mirror of [SslConfigFactory.sslConfigFor]'s mTLS-priority routing.
     */
    override fun isMutualTlsActive(): Boolean = connectionGateway.isMutualTlsActive()

    /**
     * §tokenstream-mtls-fix: build a token-stream OkHttp client via THIS repository's
     * [networkGraph.clientFactory] (whose [networkGraph.sslConfigFactory] holds the mTLS material loaded by
     * [configure]), so the token-stream SSE trusts the same server CA as REST/SSE.
     *
     * Before this, the token stream was wired (in [cn.vectory.ocdroid.di.ControllerModule]
     * .provideTokenStreamCoordinator) to the Hilt-singleton [OkHttpClientFactory], whose
     * own [SslConfigFactory] never received [configureClientCert] → [sslConfigFor] fell
     * back to [SslConfig.SystemDefault] → "Trust anchor for certification path not
     * found" under mTLS + slim (REST/SSE worked because they read THIS factory).
     *
     * Delegates to [OkHttpClientFactory.tokenStreamClient]; [networkGraph.sslConfigFactory] is read
     * live on each call (a fresh [OkHttpClient] is built per open), so a post-configure
     * open always picks up the current mTLS / host state — the same live-host-config
     * invariant the DI provider already honours by resolving the URL at open time.
     *
     * Additive public method — does NOT change the constructor (the
     * [T3RepositoryExtractFreezeTest] JVM-arity freeze stays GREEN).
     */
    override fun tokenStreamClient(hostPort: String?): OkHttpClient =
        connectionGateway.tokenStreamClient(hostPort)

    /**
     * Token-stream construction bound to a previously captured immutable
     * bundle. Unlike the compatibility overload above, this method never
     * re-reads the published bundle, so resolve-time URL/client/SSL identity
     * stays coherent across a concurrent configure.
     */
    internal fun tokenStreamClient(bundle: ClientBundle): OkHttpClient =
        connectionGateway.tokenStreamClient(bundle)

    /**
     * §fix-3 (gro-1#2/gpt-2#2/max-1 M1): 转发 [SslConfigFactory.lastClientCertError]。
     * 非空 = 最近一次 [configure] 注入的客户端证书材料试构建失败（p12 损坏 / CA 无法
     * 解析）→ mTLS 已降级回 SystemDefault，profile 仍宣称 mtlsEnabled。controller/UI
     * 据此显示「证书加载失败」而非泛化连接失败（防 fail-open 静默降级）。null = ok 或
     * 未配置 mTLS。
     */
    override val lastClientCertError: String? get() = connectionGateway.lastClientCertError

    // ── lite-v2-dev (plan §4.1): ExpandBatchEngine + SlimSyncEngine + ────────
    // authoritative commit stores RETIRED. The slim state machine, sync engine,
    // and authoritative committer have been deleted. expandMessagesFullBatch
    // below is now a direct N×/full loop (no batch engine). Message paging in
    // slim mode uses getSlimapiMessagesSkeleton directly.

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
    override suspend fun checkHealth(): Result<HealthResponse> = connectionGateway.checkHealth()

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
    override fun parseSlimapiHealth(body: String): SlimapiHealthPayload = connectionGateway.parseSlimapiHealth(body)

    /**
     * One-shot health probe against [baseUrl] with optional Basic Auth, WITHOUT
     * mutating this repository's current configuration. Used by the host list's
     * per-row "test" action so a profile can be probed without switching hosts.
     *
     * L7: [trustAll] skips server certificate verification for this probe.
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
    override suspend fun checkHealthFor(
        baseUrl: String,
        username: String?,
        password: String?,
        hostPort: String?,
        clientCert: ClientCertMaterial?,
        slim: Boolean,
        trustAll: Boolean,
    ): Result<HealthResponse> = connectionGateway.checkHealthFor(
        baseUrl = baseUrl,
        username = username,
        password = password,
        hostPort = hostPort,
        clientCert = clientCert,
        slim = slim,
        trustAll = trustAll,
    )

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
    override suspend fun getSessions(limit: Int?): Result<List<Session>> =
        sessionGateway.getSessions(limit)

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
    override suspend fun getSessionsForDirectory(directory: String, limit: Int?): Result<List<Session>> =
        sessionGateway.getSessionsForDirectory(directory, limit)

    /**
     * Fetches a single session by ID. Used to resolve a child/sub-agent session
     * that may not be present in the cached [getSessions] list.
     */
    override suspend fun getSession(sessionId: String): Result<Session> =
        sessionGateway.getSession(sessionId)

    override suspend fun createSession(title: String?, directory: String?): Result<Session> =
        sessionGateway.createSession(title, directory)

    override suspend fun updateSession(sessionId: String, title: String): Result<Session> =
        sessionGateway.updateSession(sessionId, title)

    override suspend fun updateSessionArchived(sessionId: String, archived: Long): Result<Session> =
        sessionGateway.updateSessionArchived(sessionId, archived)

    override suspend fun deleteSession(sessionId: String): Result<Unit> =
        sessionGateway.deleteSession(sessionId)

    override suspend fun getSessionStatus(): Result<Map<String, SessionStatus>> =
        sessionGateway.getSessionStatus()

    override suspend fun getActiveSessionIds(): Result<Set<String>> =
        sessionGateway.getActiveSessionIds()

    /**
     * T-R1 (slimapi R1) — BULK slim cold-start status fetch. The slim-mode
     * replacement for the legacy [getSessionStatus] bulk endpoint: routes
     * through the sidecar's `GET /slimapi/sessions/status?directory=` and
     * returns the SAME `Map<String, SessionStatus>` shape (forwarded verbatim
     * from upstream `/session/status`), so callers ([launchLoadSessionStatus]
     * slim cold-start) consume it identically to the legacy map.
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
     * (`X-Slimapi-Version` stays 2, injected by interceptor).
     */
    /**
     * §3.1 Plan-A: redirects to the slim `GET /slimapi/sessions/status?directory=` endpoint
     * when slim mode is active AND [ServerCompatProfile.supportsSlimStatus] is true (fail-open:
     * defaults to true, first 404 flips to false).
     *
     * P1-7: an old v2 sidecar predating Plan-A returns 404 → cached unsupported via
     * [ServerCompatProfile.markSlimStatusUnsupported]; THIS CALL falls back to the standard
     * status API (`api.getSessionStatus()`, no turn merge). Transport errors (5xx, timeout)
     * do NOT flip the flag — transient.
     *
     * Legacy (non-slim) mode always uses the standard API directly.
     */
    override suspend fun getSlimapiSessionsStatus(directory: String): Result<Map<String, SessionStatus>> =
        sessionGateway.getSlimapiSessionsStatus(directory)

    override suspend fun getChildren(sessionId: String): Result<List<Session>> =
        sessionGateway.getChildren(sessionId)

    override suspend fun getMessages(sessionId: String, limit: Int?): Result<List<MessageWithParts>> =
        messageGateway.getMessages(sessionId, limit)

    override suspend fun getMessagesPaged(
        sessionId: String,
        limit: Int?,
        before: String?,
    ): Result<MessagesPage> = messageGateway.getMessagesPaged(sessionId, limit, before)

    override suspend fun getMessagesPagedUnanchored(
        sessionId: String,
        limit: Int?,
        before: String?,
    ): Result<MessagesPage> = messageGateway.getMessagesPagedUnanchored(sessionId, limit, before)

    override suspend fun probeLatestMessageId(sessionId: String): Result<String?> =
        messageGateway.probeLatestMessageId(sessionId)

    override suspend fun probeLatestMessageIdForCurrent(sessionId: String): ProbeResult =
        messageGateway.probeLatestMessageIdForCurrent(sessionId)

    override suspend fun probeLatestSlim(sessionId: String): ProbeResult =
        messageGateway.probeLatestSlim(sessionId)

    /**
     * §slimapi-client-impl-v1 §6 G2 (Task 4) — per-session status fetch
     * (`GET /slimapi/sessions/{sid}/status`), boundary-normalised into a
     * [StatusOutcome] so the caller (T7 reconcile / slim status fan-out)
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
    override suspend fun getSlimapiSessionStatusOutcome(sessionId: String): StatusOutcome =
        sessionGateway.getSlimapiSessionStatusOutcome(sessionId)

    override suspend fun sendMessage(
        sessionId: String,
        text: String,
        agent: String?,
        model: Message.ModelInfo?,
        attachments: List<ComposerImageAttachment>,
    ): Result<Unit> = interactionGateway.sendMessage(sessionId, text, agent, model, attachments)

    override suspend fun abortSession(sessionId: String): Result<Unit> =
        interactionGateway.abortSession(sessionId)

    override suspend fun summarizeSession(
        sessionId: String,
        model: Message.ModelInfo
    ): Result<Boolean> = interactionGateway.summarizeSession(sessionId, model)

    /**
     * §compact-graded (Blocker-1): raised by [summarizeSession] when the
     * server returns HTTP 2xx with body `false`. Distinct type so
     * [ChatViewModel.compactSession] can branch on `onFailure` +
     * `cause is SummarizeServerRejectedException` and clear `isCompacting`
     * + emit a deterministic Error (vs the read-timeout Info path).
     */
    class SummarizeServerRejectedException :
        Exception("Server rejected compaction (body=false)")

    override suspend fun forkSession(sessionId: String, messageId: String?): Result<Session> =
        interactionGateway.forkSession(sessionId, messageId)

    override suspend fun revertSession(sessionId: String, messageId: String, partId: String?): Result<Session> =
        interactionGateway.revertSession(sessionId, messageId, partId)

    /**
     * §slim-reconcile-lane-repo (B2 T4) / §rev-grok fix1: fetch pending
     * permissions from the standard `/permission` endpoint (slim or legacy).
     * V2 removed the `/slimapi/permissions` sidecar aggregate — always uses
     * the standard API, which returns bare [PermissionRequest] without the
     * V1 `{items, errors}` envelope.
     *
     * legacy (`isSlimMode == false`): byte-for-byte unchanged.
     */
    override suspend fun getPendingPermissions(): Result<List<PermissionRequest>> =
        interactionGateway.getPendingPermissions()

    override suspend fun respondPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse
    ): Result<Unit> = interactionGateway.respondPermission(sessionId, permissionId, response)

    override suspend fun getPendingQuestions(directory: String?): Result<List<QuestionRequest>> =
        interactionGateway.getPendingQuestions(directory)

    override suspend fun replyQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?
    ): Result<Unit> = interactionGateway.replyQuestion(requestId, answers, directory)

    override suspend fun rejectQuestion(requestId: String, directory: String?): Result<Unit> =
        interactionGateway.rejectQuestion(requestId, directory)

    override suspend fun getProviders(): Result<ProvidersResponse> = catalogGateway.getProviders()

    override suspend fun getProvidersOrFailure(): Result<ProvidersResponse> = catalogGateway.getProvidersOrFailure()

    override suspend fun getAgents(): Result<List<AgentInfo>> = catalogGateway.getAgents()

    override suspend fun getCommands(): Result<List<CommandInfo>> = catalogGateway.getCommands()

    // ── §slimapi-directories: GET /slimapi/directories (global directory catalog) ──────

    /**
     * §slimapi-directories: fetch the global directory catalog for the
     * "past projects" picker. Returns [DirectoriesResult] pairing the outcome
     * with the [ConnectionCapture] validated during the call — the caller (VM)
     * commits its UI state write inside [commitIfConnectionCaptureCurrent] so
     * check-and-write is atomic (closes the repo-return → VM-write TOCTOU, incl.
     * bundle-generation rotation).
     *
     * **Fallback discipline**: only HTTP 404 `thin_route_not_found` (old sidecar)
     * or non-slim → [DirectoriesOutcome.Degraded] (VM shows MRU). 503
     * `transform_busy` retries (≤3×, honoring `Retry-After`); other 4xx/5xx /
     * timeout → [DirectoriesOutcome.Error] (VM retains previous + retries). The
     * capability-flag mark is guarded by [commitIfConnectionCaptureCurrent] so a
     * stale 404 cannot pollute [ServerCompatProfile.supportsSlimDirectories].
     *
     * The two early returns (identity-null / non-slim-or-flag-false) happen
     * before any suspend; the returned `cap` still travels so the VM commits
     * every outcome through the SAME atomic gate uniformly.
     */
    internal suspend fun getDirectories(): DirectoriesResult {
        // §ogpt-code-review blocker fix: capture connection + mode ATOMICALLY
        // under the repo monitor. setSlimConnection() — which resets
        // supportsSlimDirectories — runs under this same monitor inside
        // configure(); reading the flags outside it could snapshot a stale
        // capability during a bundle-generation-only rotation (new generation +
        // old flag → wrong Degraded that the 4-field commit would still accept).
        // The snapshot travels in DirectoriesResult.modeSnap and is re-validated
        // by [commitDirectoriesIfCurrent]. synchronized(this) is reentrant, so
        // captureConnection()'s identityStore read (nested lock, same order as
        // commitIfConnectionCaptureCurrent: repo → identityStore) is safe.
        val (cap, modeSnap) = synchronized(this) {
            captureConnection() to ModeSnapshot(
                slim = serverCompatProfile.slimConnection,
                supportsDirectories = serverCompatProfile.supportsSlimDirectories,
            )
        }
        val outcome: DirectoriesOutcome = when {
            cap.identity == null -> DirectoriesOutcome.Dropped
            !modeSnap.slim || !modeSnap.supportsDirectories -> DirectoriesOutcome.Degraded
            else -> fetchDirectories(cap)
        }
        return DirectoriesResult(outcome, cap, modeSnap)
    }

    /**
     * §ogpt-code-review rev-2 fix: atomic commit for directories results.
     *
     * Rejects ONLY when the capability flag was OFF at capture
     * ([DirectoriesResult.modeSnap].supportsDirectories == false → the outcome is
     * a Degraded-without-HTTP) but an external `setSlimConnection` reset has
     * since turned it ON — that makes the captured Degraded stale (the new
     * endpoint may serve the route, so the user should get a re-probe, not MRU).
     *
     * A SELF-INDUCED capability change does NOT match this predicate:
     *  - The first valid 404 path captures modeSnap.supportsDirectories=true
     *    (HTTP proceeded), then fetchDirectories' own `markUnsupported` flips the
     *    flag to false and returns Degraded. At commit `!true` is false → the
     *    reject is skipped → the legit Degraded commits. (The symmetric check in
     *    rev-1's fix wrongly rejected this — rev-2's blocker.)
     *  - `slimConnection` is intentionally NOT re-checked: it changes only inside
     *    `configure`, which rotates the bundle generation → already caught by the
     *    4-field [commitIfConnectionCaptureCurrent] below.
     */
    internal fun commitDirectoriesIfCurrent(result: DirectoriesResult, commit: () -> Unit): Boolean = synchronized(this) {
        if (!result.modeSnap.supportsDirectories && serverCompatProfile.supportsSlimDirectories) {
            return@synchronized false
        }
        commitIfConnectionCaptureCurrent(result.cap, commit)
    }

    private suspend fun fetchDirectories(cap: ConnectionCapture): DirectoriesOutcome {
        var attempt = 0
        while (true) {
            attempt++
            val resp = try {
                api.getSlimapiDirectories()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return DirectoriesOutcome.Error(DirectoriesErrorCause.Transient(e))
            }
            if (resp.isSuccessful) {
                // Note: no markSlimDirectoriesSupported() here — a 200 can only
                // occur when supportsSlimDirectories was already true at capture
                // (else fetchDirectories is never called), so re-affirming true is
                // a no-op AND a concurrent late-200 could wrongly re-true a
                // sticky-false flag (rev-2 🟠). The flag's lifecycle is driven
                // solely by 404→markUnsupported + setSlimConnection reset.
                val body = resp.body()
                return if (body != null) DirectoriesOutcome.ServerList(body.items, body.discoveryComplete)
                else DirectoriesOutcome.Error(DirectoriesErrorCause.MalformedBody)
            }
            val errorCode = parseErrorCode(resp)
            val http = resp.code()
            if (http == 503 && errorCode == SlimapiErrorCodes.TRANSFORM_BUSY && attempt < 3) {
                val retryAfterMs = retryAfterHeaderToMs(resp.headers()["Retry-After"])
                val delayMs = if (retryAfterMs > 0L) retryAfterMs
                else exponentialBackoffMs(attempt - 1, baseMs = 200L, maxShift = 3)
                delay(delayMs)
                continue
            }
            if (http == 404 && errorCode == SlimapiErrorCodes.THIN_ROUTE_NOT_FOUND) {
                commitIfConnectionCaptureCurrent(cap) {
                    serverCompatProfile.markSlimDirectoriesUnsupported()
                }
                DebugLog.w(
                    "OpenCodeRepository",
                    "slimapi /slimapi/directories 404 thin_route_not_found (old sidecar) → Degraded",
                )
                return DirectoriesOutcome.Degraded
            }
            return DirectoriesOutcome.Error(DirectoriesErrorCause.Http(http))
        }
    }

    override suspend fun executeCommand(
        sessionId: String,
        command: String,
        arguments: String,
        agent: String?,
        directory: String?
    ): Result<Unit> = interactionGateway.executeCommand(sessionId, command, arguments, agent, directory)

    override suspend fun getSessionDiff(sessionId: String): Result<List<FileDiff>> =
        fileVcsGateway.getSessionDiff(sessionId)

    override suspend fun getSessionTodos(sessionId: String): Result<List<TodoItem>> =
        fileVcsGateway.getSessionTodos(sessionId)

    /**
     * §R-17 batch4 / §R18 Phase 2-E step 2: lists files under [directory]
     * (absolute workdir) at the relative [path]. The directory is passed
     * EXPLICITLY to the server via `?directory` + the `X-Opencode-Skip-Dir`
     * marker on the API method (no global state involved).
     */
    override suspend fun getFileTree(directory: String, path: String?): Result<List<FileNode>> =
        fileVcsGateway.getFileTree(directory, path)

    override suspend fun getFileTreeForDirectory(directory: String, path: String?): Result<List<FileNode>> =
        fileVcsGateway.getFileTreeForDirectory(directory, path)

    override suspend fun getFileContent(directory: String, path: String): Result<FileContent> =
        fileVcsGateway.getFileContent(directory, path)

    override suspend fun getFileStatus(directory: String): Result<List<FileStatusEntry>> =
        fileVcsGateway.getFileStatus(directory)

    override suspend fun getVcs(directory: String?): Result<VcsInfo> =
        fileVcsGateway.getVcs(directory)

    override suspend fun getVcsStatus(directory: String?): Result<List<VcsStatusEntry>> =
        fileVcsGateway.getVcsStatus(directory)

    override suspend fun getVcsDiff(mode: String, directory: String?): Result<List<FileDiff>> =
        fileVcsGateway.getVcsDiff(mode, directory)

    override suspend fun findFile(directory: String, query: String, limit: Int): Result<List<String>> =
        fileVcsGateway.findFile(directory, query, limit)

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
     fun connectSSE(
         directory: String?,
     ): Flow<Result<SSEEvent>> =
         requireClientBundle().let { bundle ->
             bundle.sseClient.connect(
             baseUrl = bundle.hostSnapshot.baseUrl,
             username = bundle.hostSnapshot.username,
             password = bundle.hostSnapshot.password,
             directory = directory,
             slimMode = bundle.hostSnapshot.slimHost,
             )
         }

     /**
      * L4 §3.1: Set the reconnect gate on the underlying [SSEClient]. Called by
      * [ServiceSseConnectionOwner] before starting an SSE collection cycle so
      * the SSEClient's internal retry respects the background grace policy.
      * This avoids adding a parameter to [connectSSE] (which is widely mocked).
      */
     internal fun setSseReconnectAllowed(allowed: () -> Boolean) {
         this.sseClient.reconnectAllowed = allowed
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

    // ── Cluster A slim SSE state methods: RETIRED (lite-v2-dev plan §4.1) ────
    // SlimSseStateMachine deleted; applySlimDigest / snapshotSlimSseState /
    // getSlimapiMessagesSince / fetchSinceForStageA / getSlimapiMessagesPage
    // (all delegated to slimStateMachine / slimSyncEngine) removed.
    // The digest handler now routes directly to SkeletonReloadCoordinator.

    /**
     * §4.3.7 (lite-v2-dev): 无 token 版 skeleton 单页拉取。复用现有 [MessagesPage]
     * 类型。供新的 [cn.vectory.ocdroid.ui.SkeletonReloadCoordinator] 使用——
     * 该路径不经过 slim-token / watermark / reconfigure 协议，直接读 sidecar
     * skeleton 端点（每次 re-GET upstream opencode，不读 sidecar 内存）。
     *
     * 排序契约（§4.3.7）：sidecar 必须按 `time.created` 升序返回（tie 按 id）。
     * 客户端在 merge 处做防御性排序（N ≤ 200，成本可忽略）。
     */
    override suspend fun getSlimapiMessagesSkeleton(
        sessionId: String,
        limit: Int,
        before: String?,
    ): MessagesPage = messageGateway.getSlimapiMessagesSkeleton(sessionId, limit, before)

    override suspend fun getSlimapiMessageFull(
        sessionId: String,
        messageId: String
    ): Result<MessageWithParts> = messageGateway.getSlimapiMessageFull(sessionId, messageId)

    override suspend fun expandMessagesFullBatch(
        sessionId: String,
        messageIds: Set<String>,
    ): ExpandOutcome = messageGateway.expandMessagesFullBatch(sessionId, messageIds)

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
    override suspend fun getSlimapiSessions(
        directories: List<String>?,
        roots: Boolean?,
        limit: Int?,
        search: String?,
    ): Result<SlimSessionsPage> = sessionGateway.getSlimapiSessions(directories, roots, limit, search)

    override suspend fun getSlimapiQuestions(
        directories: List<String>?,
    ): Result<SlimAggregationOutcome<SlimapiQuestionEntry>> =
        interactionGateway.getSlimapiQuestions(directories)

    override suspend fun getSlimapiPermissions(
        directories: List<String>?,
    ): Result<SlimAggregationOutcome<SlimapiPermissionEntry>> =
        interactionGateway.getSlimapiPermissions(directories)

    // F4a: replySlimapiQuestion / rejectSlimapiQuestion / respondSlimapiPermission
    // forwarders were identity-equivalent to the legacy methods; collapsed in F4a.

    // ── Cluster A slim state / drain methods: RETIRED (lite-v2-dev plan §4.1) ─
    // coldStartSlimSync + bumpSlimBookmarkFromItems + getSlimSessionState +
    // markSlimSessionDeleted + clearSlimLocalMessages + markSlimReconcileFailure
    // + markSlimReconcileAligned + invalidateSlimLocalApplied + markSlimDirty
    // + forceSlimDirty + drainSlimapiMessagesBounded + fetchSlimInitialWindowBounded
    // + drainSlimSinceBounded — ALL delegated to SlimSseStateMachine / SlimSyncEngine
    // (both deleted). The slim digest path now routes to SkeletonReloadCoordinator.

    companion object {
        /**
         * Default server URL. Mirrored from [HostConfig.DEFAULT_SERVER] so
         * `OpenCodeRepositoryTest.default server URL is localhost` continues
         * to pass without change.
         */
        const val DEFAULT_SERVER = HostConfig.DEFAULT_SERVER

        /**
         * §concurrency-refactor: sentinel generation stamped onto the
         * pre-built [ClientBundle] (Phase 1, outside the repo monitor). It is
         * NEVER published — Phase 2 calls [ClientBundle.withGeneration] with
         * the real monotonic `prev + 1` before the volatile write. A negative
         * value is chosen so any accidental leak (a pre-built bundle escaping
         * the narrow publish section) is trivially distinguishable from a real
         * generation (which starts at 0 and is monotonically increasing).
         */
        private const val PLACEHOLDER_GENERATION = -1L

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

        /**
         * Tag for slimapi envelope-degradation warnings (per-directory
         * `errors` inside `{items, errors}`). Surfaced at WARN so the
         * sidecar's "one opencode down → 200 with partial items" path
         * remains observable in `adb logcat`.
         */
        private const val TAG = "OpenCodeRepository"
    }

    /**
     * lite-v2-dev: was the slim cursor-endpoint paged fetch. The slim
     * staging/since machinery (SlimSinceStagingOnlyException etc.) is retired;
     * this stub now delegates to [getMessagesPaged] so the slim/legacy branch
     * in RevertCutoffCoordinator resolves. The `mode` / `token` params are
     * ignored (kept for source-compat with the existing call site).
     */
    override suspend fun getSlimapiMessagesPage(
        sessionId: String,
        limit: Int,
        before: String?,
        @Suppress("UNUSED_PARAMETER") mode: String,
    ): Result<MessagesPage> = getMessagesPaged(sessionId, limit, before)
}

// SlimAggregationOutcome, SlimColdStartSnapshot moved to their own files
// SlimapiPermissionEntry.toPermissionRequest() moved to SlimAggregationOutcome.kt
