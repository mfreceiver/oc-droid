package cn.vectory.ocdroid.data.repository

import androidx.annotation.VisibleForTesting
import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.api.SSEClient
import cn.vectory.ocdroid.data.api.*
import cn.vectory.ocdroid.data.model.*
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
) {
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
        /** ConnectionIdentity captured at slim-operation entry. */
        internal val capturedConnectionIdentity: ConnectionIdentity? = null,
        /** IdentityStore epoch captured independently of the slim marker. */
        internal val capturedIdentityEpoch: Long? = null,
        /** Published ClientBundle generation captured independently of the slim marker. */
        internal val capturedClientBundleGeneration: Long? = null,
        /** Endpoint fingerprint belonging to the captured ClientBundle. */
        internal val capturedEndpointFp: String? = null,
        /** Immutable transport/API bundle used by this operation, when available. */
        internal val capturedClientBundle: ClientBundle? = null,
    )

    // ── lite-v2-dev (plan §4.4): slim state-machine + incarnation 协议退役 ────
    //
    // 2B 删除了 slim state machine + reconfigure 方法群 + 嵌套异常类。
    // connection-barrier 系统（HostProfileController / ConnectionReconfigure
    // Barrier / ProfileMutationEngine / ConnectionBootstrapEngine）+ 多个 catch
    // 站点（MessageSource / PermissionRefreshOrchestrator / SessionSyncCoordinator
    // / ChatViewModel / AppLifecycleMonitor）仍引用 SlimCommitToken / token guard。
    // 在 lite-v2 范围内完整重写 barrier 系统超出本轮范围（C2: host 切换 = 重启 app，
    // incarnation 保护语义本身已弱化），故以 **no-op stub** 形式保留符号，使全部引用
    // 解析、barrier 系统以 best-effort 继续运行。

    // lite-v2: slim incarnation stubs removed (incarnation protocol fully retired).
    // The remaining StaleSlimCommitException is kept for PermissionRefreshOrchestrator
    // catch site — it will be removed in a follow-up cleanup.
    @Deprecated("lite-v2 compatibility shim", level = DeprecationLevel.WARNING)
    class StaleSlimCommitException internal constructor() :
        java.io.IOException("stale or not-ready slim repository incarnation")

    // lite-v2: 在 C2 约束下（host 切换 = 进程重启），跨连接 stale write 不可能发生。
    // ReloadIdentity（serverGroupFp + routeInstance）在 SkeletonReloadCoordinator 中
    // 提供了新的 stale response 防护。这些 token 方法保留为 no-op 兼容层，
    // 仅供非 skeleton 路径（permission/question/status 异步刷新）使用。
    // 后续应删除所有调用者，改用 route/serverGroup CAS。
    /**
     * lite-v2: token 使用 ConnectionIdentityStore 的 epoch + identity 做真实验证。
     * 在 C2 约束下（host 切换 = 进程重启），跨连接 stale write 不可能发生。
     * ReloadIdentity（serverGroupFp + routeInstance）在 SkeletonReloadCoordinator 中
     * 提供了新的 stale response 防护。这些 token 方法保留为兼容层。
     */
    /**
     * lite-v2: same-host local-wipe marker rotation seam. The slim incarnation
     * system is retired (identityStore.beginReconfigure() handles epoch/marker
     * rotation); this no-op shim is kept as the call-site contract for
     * resetLocalDataAndResync + testability, consistent with the kept
     * captureSlimCommitToken compatibility shim.
     */
    fun resetSlimForLocalWipe(): Unit = Unit

    @Deprecated("lite-v2 compatibility shim", level = DeprecationLevel.WARNING)
    fun captureSlimCommitToken(): SlimCommitToken {
        val identityStore = identityStoreOrFallback()
        val capture = identityStore.capture()
        val bundle = currentClientBundle()
        return SlimCommitToken(
            marker = Any(),
            issuedReady = capture.identity != null && bundle != null,
            capturedConnectionIdentity = capture.identity,
            capturedIdentityEpoch = capture.epoch,
            capturedClientBundleGeneration = bundle?.generation,
            capturedEndpointFp = bundle?.endpointFp,
            capturedClientBundle = bundle,
        )
    }

    /** lite-v2-dev: always current (slim state machine retired). */
    @Deprecated("lite-v2 compatibility shim", level = DeprecationLevel.WARNING)
    fun isSlimCommitTokenCurrent(token: SlimCommitToken): Boolean {
        val capture = identityStoreOrFallback().capture()
        val bundle = currentClientBundle()
        return token.issuedReady &&
            token.capturedIdentityEpoch == capture.epoch &&
            token.capturedConnectionIdentity == capture.identity &&
            token.capturedClientBundleGeneration == bundle?.generation &&
            token.capturedEndpointFp == bundle?.endpointFp
    }

    @Deprecated("lite-v2 compatibility shim", level = DeprecationLevel.WARNING)
    fun commitIfSlimTokenCurrent(token: SlimCommitToken, commit: () -> Unit): Boolean = synchronized(this) {
        val bundle = currentClientBundle() ?: return false
        if (!token.issuedReady) return false
        if (token.capturedClientBundleGeneration != bundle.generation) return false
        if (token.capturedEndpointFp != bundle.endpointFp) return false
        identityStoreOrFallback().commitIfCurrent(
            identity = token.capturedConnectionIdentity,
            epoch = token.capturedIdentityEpoch ?: return false,
            commit = commit,
        )
    }

    @Deprecated("lite-v2 compatibility shim", level = DeprecationLevel.WARNING)
    fun requireSlimTokenCurrent(token: SlimCommitToken) {
        if (!isSlimCommitTokenCurrent(token)) throw StaleSlimCommitException()
    }

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
     val isSlimMode: Boolean get() = requireClientBundle().hostSnapshot.slimHost

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
    val supportsWatermarkResync: Boolean get() = serverCompatProfile.slimConnection

    /** ι-A / lite-v2-dev: 是否支持 token-stream 重同步。lite-v2 起 v2 协议下
     *  `tokenStreamEnabled = slimConnection`（plan §2.5，不再 probe health
     *  features.tokenStream）；本 forwarder 直接读 slimConnection（语义等价）。 */
    val supportsTokenStreamResync: Boolean get() = serverCompatProfile.slimConnection

    /** ι-A / lite-v2-dev: StatusAggregator 是否走 slim 扇出（vs legacy bulk
     *  `/session/status`）。lite-v2 起等价于 slimConnection（plan §4.4）。 */
    val usesSlimStatusFanOut: Boolean get() = serverCompatProfile.slimConnection

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

    @Synchronized
    private fun publishClientBundle(
        candidate: ClientBundle,
        hostSnapshot: HostSnapshot,
        clientCert: ClientCertMaterial? = null,
        updateClientCert: Boolean = false,
        trustAll: Boolean = false,
        updateTrustAll: Boolean = false,
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
            networkGraph.sslConfigFactory.configureClientCert(clientCert)
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
    ) {
        // L7: set trustAll on the factory BEFORE resolveCandidateSsl so the
        // candidate SSL config (built via sslConfigFor) reflects the flag.
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
        val current = requireClientBundle()
        val candidate = buildClientBundle(
            hostSnapshot = candidateSnapshot,
            generation = current.generation + 1L,
            effectiveSslConfig = candidateSsl.config,
            clientCertError = candidateSsl.clientCertError,
        )
        // Atomic client publication, then old-generation retirement. The
        // source/readiness publication below remains after completion.
        publishClientBundle(
            candidate = candidate,
            hostSnapshot = candidateSnapshot,
            clientCert = clientCert,
            updateClientCert = true,
            trustAll = trustAll,
            updateTrustAll = true,
        )
        // ι-P1: rebuild session source to match the new connection mode.
        // lite-v2-dev: SlimSessionSource retained (skeleton list endpoint still exists);
        // SlimMessageSource retired (slimStateMachine deleted → no watermark read).
        // Both slim and legacy use StandardMessageSource for message paging now.
        sessionSource = if (slim) SlimSessionSource({ api }, { parseErrorCode(it) }, { retryAfterHeaderToMs(it) }) else StandardSessionSource({ api })
        // ι-P2: message source — lite-v2-dev always uses StandardMessageSource.
        // The slim message paging path (getMessagesPaged slim branch) now routes
        // through getSlimapiMessagesSkeleton directly (no /since watermark).
        messageSource = StandardMessageSource({ api })
        // ι-A (capability read-model): 发布能力 mode 仅在整条 ssl/host/client/readiness
        // 事务全成功后。configure() 是 fail-forward（不回滚旧 networkGraph.hostConfig），
        // 但能力模型只反映"最近一次成功 live 的 mode"，与 readiness 同语义。
        //
        // 受管写点（I8 扩展）：本行是 setSlimConnection 的唯一受管调用方；与 probe
        // 写点（update/updateSlimapi，由 checkHealthFor / probeSlimapiHealth 尾部调用）
        // 并列。仍在 configure() @Synchronized monitor 内（I5/I6/I7 不变量保持）。
        // reconfigure 中途（新栈未确认前）slimConnection 仍报旧 mode = 仍 operative 的
        // 旧连接；L4+ 无锁读到的始终是"当前仍 live 的 mode"。mode 在此刻 authoritatively
        // 确立（= networkGraph.hostConfig.slim），不能从 serverCompatProfile 现有字段推导（legacy 模式
        // 下 slimapi* 全 null；slim 模式首次 health 成功前 slimapi* 也全 null）。
        serverCompatProfile.setSlimConnection(slim)
    }

    /**
     * §2.4: the current effective [SslConfig] for the live host (mTLS priority
     * over trust-all, SystemDefault safe fallback). Callers
     * ([HttpImageHolder] / cold-start image sync) use this to mirror the same
     * trust policy onto the markdown image client. `@Synchronized` because it
     * reads the mutable [networkGraph.sslConfigFactory] state that [configure] writes under
     * the same monitor (v3-glmer R2).
     */
    @Synchronized
    fun currentSslConfig(): SslConfig = requireClientBundle().effectiveSslConfig

    /**
     * 当前 live SSL 配置是否走 mTLS 路径（客户端证书已配置并加载）。
     * Mirror of [SslConfigFactory.sslConfigFor]'s mTLS-priority routing.
     */
    fun isMutualTlsActive(): Boolean = currentSslConfig() is SslConfig.MutualTLS

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
    fun tokenStreamClient(hostPort: String?): OkHttpClient {
        val bundle = requireClientBundle()
        return networkGraph.clientFactoryFor(bundle.hostSnapshot, bundle.effectiveSslConfig)
            .tokenStreamClient(hostPort)
    }

    /**
     * Token-stream construction bound to a previously captured immutable
     * bundle. Unlike the compatibility overload above, this method never
     * re-reads the published bundle, so resolve-time URL/client/SSL identity
     * stays coherent across a concurrent configure.
     */
    internal fun tokenStreamClient(bundle: ClientBundle): OkHttpClient =
        networkGraph.clientFactoryFor(bundle.hostSnapshot, bundle.effectiveSslConfig)
            .tokenStreamClient(bundle.hostSnapshot.hostPort)

    /**
     * §fix-3 (gro-1#2/gpt-2#2/max-1 M1): 转发 [SslConfigFactory.lastClientCertError]。
     * 非空 = 最近一次 [configure] 注入的客户端证书材料试构建失败（p12 损坏 / CA 无法
     * 解析）→ mTLS 已降级回 SystemDefault，profile 仍宣称 mtlsEnabled。controller/UI
     * 据此显示「证书加载失败」而非泛化连接失败（防 fail-open 静默降级）。null = ok 或
     * 未配置 mTLS。
     */
    val lastClientCertError: String? get() = requireClientBundle().clientCertError

    // ── lite-v2-dev (plan §4.1): ExpandBatchEngine + SlimSyncEngine + ────────
    // authoritative commit stores RETIRED. The slim state machine, sync engine,
    // and authoritative committer have been deleted. expandMessagesFullBatch
    // below is now a direct N×/full loop (no batch engine). Message paging in
    // slim mode uses getSlimapiMessagesSkeleton directly.

    /**
     * ι-P1: session 域端口—Standard 或 Slim 双实现,由 [configure] 在 client 重建后选束。
     * 默认 [StandardSessionSource]（legacy）与未 configure 行为一致。
     *
     * ι-4: @Volatile — 此为并发路由位([configure] @Synchronized monitor 内写,
     * [getSessions]/[getSessionsForDirectory] 无锁读);volatile 保证 reader 线程
     * 可见最新选束,接替原 isSlimMode(读 networkGraph.hostConfig._slim @Volatile)的并发路由职责。
     * (rev-4/rev-10 stage 收尾加固)
     */
    @Volatile
    private var sessionSource: SessionSource = StandardSessionSource({ api })

    /**
     * ι-P2: message 域端口—Standard 或 Slim 双实现,由 [configure] 在 client 重建后选束。
     * 默认 [StandardMessageSource]（legacy）与未 configure 行为一致。
     * §11.1 fix-9 P2 KDoc cleanup: [SlimMessageSource] 经注入 lambda 访问共享态
     * （slimSessionUpdatedAt 只读 watermark + apiProvider token-bound +
     * requireSlimTokenCurrent token guard）。**bumpBookmark 回调已移除**（stage A:
     * `/since` 仅作 staging；watermark 由 [SlimAuthoritativeCommitter] 推进）。
     * 锁与 bookmark 状态留 OCR（I5 保持），SlimMessageSource 不持锁 / 不持状态机对象。
     *
     * ι-4: @Volatile — 同 [sessionSource]，并发路由位（configure monitor 内写、
     * [getMessagesPaged] 等无锁读），volatile 保证 reader 可见最新选束。
     */
    @Volatile
    private var messageSource: MessageSource = StandardMessageSource({ api })

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
        val bundle = requireClientBundle()
        if (!bundle.hostSnapshot.slimHost) {
            // legacy：行为字节级不变。
            bundle.restApi.getHealth()
        } else {
            // C3 fix：探 sidecar 自身 health，不经 catch-all 透传。
            probeSlimapiHealth(
                baseUrl = bundle.hostSnapshot.baseUrl,
                username = bundle.hostSnapshot.username,
                password = bundle.hostSnapshot.password,
                sslConfig = bundle.effectiveSslConfig,
            )
        }
    }

    /**
     * R8 slim-mode foundation / C3 fix（共用实现）：裸 OkHttp `GET {baseUrl}/slimapi/health`
     * 带 `X-Slimapi-Version` 头，把 sidecar 响应适配为 [HealthResponse]。
     *
     * - 用 [networkGraph.clientFactory.healthClient]（无 base 链拦截器——避免 Directory / Auth /
     *   Cache-Control 干扰一次性探针；版本头显式注入，因为 healthClient 不挂
     *   [SlimapiVersionInterceptor]）。
     * - Basic Auth 同步注入（与 [checkHealthFor] 一致语义）。
     * - 解析失败 / sidecar.ok == false / 版本不兼容 → 抛错（`Result.failure`），
     *   不静默报健康——C3 的核心保证。
     */
    private suspend fun probeSlimapiHealth(
        baseUrl: String,
        username: String?,
        password: String?,
        sslConfig: SslConfig? = null,
    ): HealthResponse = withContext(Dispatchers.IO) {
        val resolvedHostPort = hostPortFromUrl(baseUrl)
        val cfg = sslConfig ?: networkGraph.sslConfigFactory.sslConfigFor(resolvedHostPort)
        val client = networkGraph.clientFactory.healthClient(cfg)
        val normalizedUrl = (if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl")
            .trimEnd('/') + SlimapiContract.SLIMAPI_HEALTH_PATH
        val requestBuilder = Request.Builder()
            .url(normalizedUrl)
            .header(HttpHeaders.SKIP_DIR_HEADER, "1")
            .header(SlimapiContract.X_SLIMAPI_VERSION, SlimapiContract.SLIMAPI_CLIENT_VERSION.toString())
        // §B (slimapi-v2-adapt-traffic-plan §B): additive identity headers
        // on this one-shot /slimapi/health probe — it bypasses baseBuilder
        // (no ClientIdentityInterceptor), so inject via the shared helper
        // (same pattern as the manually-added version header above).
        applyClientIdentityHeaders(requestBuilder, clientIdStoreOrFallback().getDeviceId())
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
        // §defect-B-2C: diagnostic-only dual-read of thresholdedSkeleton /
        // skeletonInlineOutputMaxBytes. Same tolerant discipline as tokenStream —
        // missing/wrong-typed fields fall back to defaults. NOT wired into any
        // behaviour or compat gate (single-user; sidecar default-on).
        val thresholdedSkeleton = featuresObj?.get("thresholdedSkeleton")?.safePrimitive()?.let { p ->
            p.booleanOrNull == true || p.content.equals("true", ignoreCase = true)
        } == true
        val skeletonInlineOutputMaxBytes = featuresObj?.get("skeletonInlineOutputMaxBytes")?.safePrimitive()?.let { p ->
            p.intOrNull
        }
        return SlimapiHealthPayload(
            sidecarOk = sidecarOk,
            schemaDegraded = schemaDegraded,
            serverApiVersion = apiVersion,
            acceptedClientVersions = accepted,
            features = SlimapiFeatures(
                tokenStream = tokenStream,
                thresholdedSkeleton = thresholdedSkeleton,
                skeletonInlineOutputMaxBytes = skeletonInlineOutputMaxBytes,
            )
        )
    }

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
    suspend fun checkHealthFor(
        baseUrl: String,
        username: String? = null,
        password: String? = null,
        hostPort: String? = null,
        clientCert: ClientCertMaterial? = null,
        slim: Boolean = false,
        trustAll: Boolean = false,
    ): Result<HealthResponse> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            // L7: pure parameter routing — mTLS > trustAll > SystemDefault.
            // Never reads held mTLS cache (v3-gpter R2#1).
            val resolvedHostPort = hostPort ?: hostPortFromUrl(baseUrl)
            val cfg: SslConfig = networkGraph.sslConfigFactory.resolveProbe(resolvedHostPort, clientCert, trustAll)
            val client = networkGraph.clientFactory.healthClient(cfg)
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
                // §B: additive identity headers on the one-shot /slimapi/health
                // probe (bypasses baseBuilder → no ClientIdentityInterceptor).
                // Gated on `slim` so legacy /global/health never leaks identity.
                applyClientIdentityHeaders(requestBuilder, clientIdStoreOrFallback().getDeviceId())
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
        runSuspendCatching { api.getSession(sessionId) }

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
        runSuspendCatching { api.getSessionStatus() }

    suspend fun getActiveSessionIds(): Result<Set<String>> =
        runSuspendCatching { api.getActiveSessions().data.keys }

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
    suspend fun getSlimapiSessionsStatus(directory: String): Result<Map<String, SessionStatus>> =
        runSuspendCatching {
            // Legacy (non-slim) mode, OR P1-7 cached-unsupported (old v2 sidecar
            // 404'd the Plan-A endpoint on a prior call) → standard status API.
            // No turn merge on this path (§3.6 fallback semantics).
            if (!serverCompatProfile.slimConnection || !serverCompatProfile.supportsSlimStatus) {
                return@runSuspendCatching api.getSessionStatus()
            }
            // §3.1 Plan-A: probe/use the slim endpoint (deployed + running).
            val resp = api.getSlimapiSessionsStatus(directory)
            if (resp.isSuccessful) {
                // First 200 → sticky-support. Subsequent calls take the fast path above
                // only after a 404 flips it; a 200 keeps us on this endpoint.
                serverCompatProfile.markSlimStatusSupported()
                resp.body() ?: emptyMap()
            } else if (resp.code() == 404) {
                // §7.11 (P1-7): old v2 sidecar does not serve the Plan-A endpoint.
                // Cache unsupported (sticky until reconfigure) and fall back THIS call.
                serverCompatProfile.markSlimStatusUnsupported()
                DebugLog.w("OpenCodeRepository",
                    "slimapi /slimapi/sessions/status 404 (old sidecar) → fallback to standard API")
                api.getSessionStatus()
            } else {
                // Other non-2xx: do NOT flip the capability flag (could be transient 5xx).
                // Throw → Result.failure → caller keeps prior snapshot (unchanged semantics).
                throw java.io.IOException("slimapi sessions/status HTTP ${resp.code()}")
            }
        }

    /**
     * Fetches the child (sub-agent) sessions spawned by [sessionId], typically
     * via the `task` tool.
     */
    suspend fun getChildren(sessionId: String): Result<List<Session>> =
        runSuspendCatching { api.getChildren(sessionId) }

    suspend fun getMessages(sessionId: String, limit: Int? = null): Result<List<MessageWithParts>> =
        runSuspendCatching {
            val response = api.getMessages(sessionId, limit, before = null)
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code()}")
            response.body() ?: emptyList()
        }

    /**
     * Cursor-paged message fetch (V1 cursor-paging protocol: cursor carried via the
     * `X-Next-Cursor` response header + the `before` query param).
     *
     * §11.1 fix-10 P0-1: in slim mode, the method now DISTINGUISHES two
     * UI message-loading paths by the [before] parameter:
     *
     *  - **`before == null` (initial / reload / catch-up):** routes through
     *    the authoritative skeleton cursor drain +
     *    [SlimAuthoritativeCommitter.commitAuthoritative] using
     *    [SLIMAPI_LOCAL_HISTORY_BOUND] as the drain item bound (NOT the UI
     *    [limit]). The drain walks the cursor window to a terminal page
     *    (`nextCursor == null`) and commits the aggregate atomically. The
     *    returned `MessagesPage` has `nextCursor = null` (the drain
     *    exhausted the window — there is no "next page" for load-more
     *    until the next cold-load). The UI [limit] is IGNORED on this
     *    path — it is a PRESENTATION page size, not a drain safety bound.
     *    The drain's own [SLIMAPI_DEFAULT_PAGE_LIMIT] controls the
     *    per-HTTP page size.
     *
     *  - **`before != null` (load-more / history pagination):** routes
     *    through [getSlimapiMessagesPage] — a SINGLE-PAGE cursor fetch
     *    that forwards [before] to the HTTP query and surfaces the
     *    response's `X-Next-Cursor` header as `MessagesPage.nextCursor`.
     *    NO authoritative commit (load-more is incremental history
     *    pagination, not a completeness sync). The UI [limit] IS honoured
     *    as the per-page `limit` query param.
     *
     * This split fixes the fix-9 P0-6 regression where ALL slim calls
     * routed through the drain, breaking load-more (before cursor was
     * ignored, nextCursor was forced null, UI limit was misused as drain
     * itemBound producing Partial on the first page of long sessions).
     *
     * legacy (`isSlimMode == false`): byte-for-byte unchanged.
     */
    suspend fun getMessagesPaged(
        sessionId: String,
        limit: Int? = null,
        before: String? = null,
        token: SlimCommitToken = captureSlimCommitToken(),
    ): Result<MessagesPage> {
        if (serverCompatProfile.slimConnection) {
            // lite-v2-dev: slim message paging uses skeleton endpoint directly
            // (SlimSyncEngine + authoritative commit retired, plan §4.1).
            return runSuspendCatching {
                val page = getSlimapiMessagesSkeleton(
                    sessionId,
                    limit = limit ?: SLIMAPI_LOCAL_HISTORY_BOUND,
                    before = before,
                )
                MessagesPage(items = page.items, nextCursor = page.nextCursor)
            }
        }
        return getMessagesPagedImpl(sessionId, limit, before, token, anchored = true)
    }

    /**
     * §empty-window-fix: UNANCHORED slim initial-window fetch — same contract
     * shape as [getMessagesPaged] but forces a fresh authoritative load.
     *
     * §11.1 fix-10 P0-1: same `before`-based split as [getMessagesPaged]:
     *  - `before == null` → full drain + commit (cold-load).
     *  - `before != null` → single-page cursor fetch via
     *    [getSlimapiMessagesPage] (load-more).
     *
     * The cold-load path uses [SLIMAPI_LOCAL_HISTORY_BOUND] as the drain
     * item bound (NOT the UI [limit]). The load-more path honours [limit]
     * as the per-page query param.
     *
     * Legacy non-slim mode: byte-for-byte identical to [getMessagesPaged]'s
     * legacy branch.
     */
    suspend fun getMessagesPagedUnanchored(
        sessionId: String,
        limit: Int? = null,
        before: String? = null,
        token: SlimCommitToken = captureSlimCommitToken(),
    ): Result<MessagesPage> {
        if (serverCompatProfile.slimConnection) {
            // lite-v2-dev: slim message paging uses skeleton endpoint directly.
            return runSuspendCatching {
                val page = getSlimapiMessagesSkeleton(
                    sessionId,
                    limit = limit ?: SLIMAPI_LOCAL_HISTORY_BOUND,
                    before = before,
                )
                MessagesPage(items = page.items, nextCursor = page.nextCursor)
            }
        }
        return getMessagesPagedImpl(sessionId, limit, before, token, anchored = false)
    }

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
     * id for [sessionId] (limit=1, desc default), using the active transport
     * route without exposing its raw mode flag to callers.
     */
    suspend fun probeLatestMessageId(sessionId: String): Result<String?> = runSuspendCatching {
        val response = api.getMessages(sessionId, limit = 1, before = null)
        if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code()}")
        response.body()?.firstOrNull()?.info?.id
    }

    /**
     * Boundary facade for callers that only need the current route's latest
     * message probe. Route selection stays inside the repository and both
     * implementations expose the same [ProbeResult] contract.
     */
    suspend fun probeLatestMessageIdForCurrent(sessionId: String): ProbeResult =
        if (requireClientBundle().hostSnapshot.slimHost) {
            probeLatestSlim(sessionId)
        } else {
            probeLatestMessageId(sessionId).toProbeResult()
        }

    private fun Result<String?>.toProbeResult(): ProbeResult = fold(
        onSuccess = { messageId ->
            ProbeResult(
                ok = true,
                empty = messageId == null,
                messageID = messageId,
            )
        },
        onFailure = { error ->
            ProbeResult(
                ok = false,
                httpStatus = error.message
                    ?.removePrefix("HTTP ")
                    ?.toIntOrNull(),
            )
        },
    )

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
     * `X-Slimapi-Version: 2` is injected by [SlimapiVersionInterceptor] (no
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
        // lite-v2-dev: /slimapi/sessions/{sid}/status removed; delegate to the
        // standard bulk /session/status endpoint and look up this session.
        return try {
            val all = api.getSessionStatus()
            val status = all[sessionId]
            // lite-v2-dev delegate: the bulk /session/status sparse map OMITS idle
            // entries (absent ≡ idle, NOT missing) — confirmed by
            // normalizeAuthoritativeStatusSnapshot in SessionTree.kt which already
            // promotes omitted authoritative ids to explicit idle. Misclassifying
            // absent as SessionMissing was the /session/status storm root cause
            // (every idle session looked evictable → fan-out churned forever).
            StatusOutcome.Success(sessionId, status ?: SessionStatus(type = "idle"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            StatusOutcome.Retry(sessionId, null)
        } catch (e: Exception) {
            StatusOutcome.Retry(sessionId, null)
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
     * §slim-reconcile-lane-repo (B2 T4) / §rev-grok fix1: fetch pending
     * permissions from the standard `/permission` endpoint (slim or legacy).
     * V2 removed the `/slimapi/permissions` sidecar aggregate — always uses
     * the standard API, which returns bare [PermissionRequest] without the
     * V1 `{items, errors}` envelope.
     *
     * legacy (`isSlimMode == false`): byte-for-byte unchanged.
     */
    suspend fun getPendingPermissions(): Result<List<PermissionRequest>> = runSuspendCatching {
        // lite-v2-dev: /slimapi/permissions removed; always use standard API.
        api.getPendingPermissions()
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
     * §需求13 rev-7 #2: failure-propagating variant of [getProviders]. Same
     * fetch + filter logic, EXCEPT structural failures (HTTP error / non-
     * decodable body / network) propagate as [Result.failure] instead of
     * being masked as an empty-catalog success.
     *
     * **Why a separate method**: [getProviders] applies a "last-mile
     * defense" (catch → Result.success(empty)) so a transient failure
     * degrades to an empty picker. That defense makes the 需求13 error-
     * feedback feature DEAD: launchLoadProviders' `.onFailure` (which
     * emits UiEvent.Error for the manual-refresh snackbar) NEVER fires for
     * real network/HTTP/parse failures — they're masked as success-with-
     * empty-catalog. This method gives launchLoadProviders a way to detect
     * REAL failures and surface them, while leaving [getProviders]'
     * behavior unchanged for any latent caller that relies on the degrade-
     * to-empty contract.
     *
     * **Empty-catalog is NOT an error**: a server that legitimately returns
     * zero providers still returns [Result.success] with an empty list. Only
     * exceptions (the catch block) flip to [Result.failure].
     * [CancellationException] is rethrown for structured concurrency.
     */
    suspend fun getProvidersOrFailure(): Result<ProvidersResponse> {
        val response: ProvidersResponse = try {
            api.getProviders()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // §需求13 rev-7 #2: propagate the REAL failure — do NOT mask as
            // empty-catalog success. launchLoadProviders' .onFailure will
            // fire → UiEvent.Error → snackbar surfaces "Failed to refresh
            // model list". Cancellation is rethrown for structured concurrency.
            DebugLog.e("OpenCodeRepository", "catalog: /config/providers fetch failed (propagating as failure)", e)
            return Result.failure(e)
        }
        val providers = response.providers.filter { it.models.isNotEmpty() }
        val totalModels = providers.sumOf { it.models.size }
        DebugLog.i("OpenCodeRepository", "catalog: ${providers.size} provider(s), $totalModels model(s) from /config/providers")
        return Result.success(
            ProvidersResponse(providers = providers, defaultByProvider = response.defaultByProvider)
        )
    }

    suspend fun getAgents(): Result<List<AgentInfo>> =
        runSuspendCatching { api.getAgents() }

    /**
     * Lists the server-defined slash commands.
     */
    suspend fun getCommands(): Result<List<CommandInfo>> =
        runSuspendCatching { api.getCommands() }

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
        runSuspendCatching { api.getSessionDiff(sessionId) }

    suspend fun getSessionTodos(sessionId: String): Result<List<TodoItem>> =
        runSuspendCatching { api.getSessionTodos(sessionId) }

    /**
     * §R-17 batch4 / §R18 Phase 2-E step 2: lists files under [directory]
     * (absolute workdir) at the relative [path]. The directory is passed
     * EXPLICITLY to the server via `?directory` + the `X-Opencode-Skip-Dir`
     * marker on the API method (no global state involved).
     */
    suspend fun getFileTree(directory: String, path: String? = null): Result<List<FileNode>> =
        runSuspendCatching { api.getFileTree(path ?: "", directory) }

    /**
     * Lists the contents of an arbitrary [directory] (independent of the
     * currently selected session's workdir). Used by the directory picker.
     */
    suspend fun getFileTreeForDirectory(directory: String, path: String? = null): Result<List<FileNode>> =
        runSuspendCatching { api.getFileTreeForDirectory(directory, path ?: "") }

    /** §R-17 batch4: see [getFileTree] for the explicit-directory rationale. */
    suspend fun getFileContent(directory: String, path: String): Result<FileContent> =
        runSuspendCatching { api.getFileContent(path, directory) }

    /** §R-17 batch4: see [getFileTree] for the explicit-directory rationale. */
    suspend fun getFileStatus(directory: String): Result<List<FileStatusEntry>> =
        runSuspendCatching { api.getFileStatus(directory) }

    // §vcs-section: read-only VCS façade for the Settings → Working directory
    // section. Thin wrappers mirroring the file* directory-scoped pattern
    // (§R-17 batch4): the directory is supplied EXPLICITLY by the caller; no
    // global workdir state. VcsInfo / VcsStatusEntry live in data.model; the
    // diff endpoint reuses the existing FileDiff shape (same as /session/{id}/diff).
    suspend fun getVcs(directory: String?): Result<VcsInfo> =
        runSuspendCatching { api.getVcs(directory) }

    suspend fun getVcsStatus(directory: String?): Result<List<VcsStatusEntry>> =
        runSuspendCatching { api.getVcsStatus(directory) }

    suspend fun getVcsDiff(mode: String, directory: String?): Result<List<FileDiff>> =
        runSuspendCatching { api.getVcsDiff(mode, directory) }

    /** §R-17 batch4: see [getFileTree] for the explicit-directory rationale. */
    suspend fun findFile(directory: String, query: String, limit: Int = 50): Result<List<String>> =
        runSuspendCatching { api.findFile(query, limit, directory) }

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
     * 该路径不经过 SlimCommitToken / watermark / reconfigure 协议，直接读 sidecar
     * skeleton 端点（每次 re-GET upstream opencode，不读 sidecar 内存）。
     *
     * 排序契约（§4.3.7）：sidecar 必须按 `time.created` 升序返回（tie 按 id）。
     * 客户端在 merge 处做防御性排序（N ≤ 200，成本可忽略）。
     */
    suspend fun getSlimapiMessagesSkeleton(
        sessionId: String,
        limit: Int,
        before: String? = null,
    ): MessagesPage {
        val response = api.getSlimapiMessages(sessionId, limit, before, mode = "skeleton")
        if (!response.isSuccessful) throw IOException("HTTP ${response.code()}")
        val items = response.body() ?: throw IOException("null_body")
        return MessagesPage(items = items, nextCursor = response.headers()["X-Next-Cursor"])
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
     * lite-v2-dev shim (plan §4.4): batch expand 退化为 N × 单条 `/full/{mid}`
     * （ExpandBatchEngine 已退役）。token 形参保留以兼容 PartExpandState 调用点，
     * 但不再做 token gate——/full 在 lite-v2 是纯按需展开（无自动同步路径调用）。
     * 每条独立 [getSlimapiMessageFull]；per-message 失败归入 [ExpandOutcome.Ok.failures]。
     */
    suspend fun expandMessagesFullBatch(
        sessionId: String,
        messageIds: Set<String>,
        @Suppress("UNUSED_PARAMETER") token: SlimCommitToken? = null,
    ): ExpandOutcome {
        val items = mutableListOf<MessageWithParts>()
        val failures = mutableListOf<ExpandOutcome.MessageFailure>()
        for (mid in messageIds) {
            getSlimapiMessageFull(sessionId, mid)
                .onSuccess { items += it }
                .onFailure { failures += ExpandOutcome.MessageFailure(mid, code = null) }
        }
        return ExpandOutcome.Ok(items = items, failures = failures, usedBatch = false)
    }

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
        getSlimapiSessionsDelegate(api, directories, roots, limit, { parseErrorCode(it) }, { retryAfterHeaderToMs(it) }, search)

    /**
     * lite-v2-dev: cross-directory pending questions aggregate.
     * /slimapi/questions removed; re-routes to the standard /question endpoint
     * (per-directory). Returns a [SlimAggregationOutcome] wrapping the
     * standard [SlimapiQuestionEntry] list for caller compatibility.
     */
    suspend fun getSlimapiQuestions(
        directories: List<String>? = null,
        token: SlimCommitToken,
    ): Result<SlimAggregationOutcome<SlimapiQuestionEntry>> = runSuspendCatching {
        // lite-v2-dev: use standard API, map results to SlimapiQuestionEntry.
        val dir = directories?.firstOrNull()
        val items = api.getPendingQuestions(dir).map { q ->
            SlimapiQuestionEntry(
                id = q.id,
                sessionId = q.sessionId,
                questions = q.questions,
                tool = q.tool,
                directory = dir,
            )
        }
        SlimAggregationOutcome.Success(
            items = items,
            authoritativeDirectories = directories?.toSet(),
            serverScope = null,
        )
    }

    /**
     * lite-v2-dev: cross-directory pending permissions aggregate.
     * /slimapi/permissions removed; re-routes to the standard /permission
     * endpoint. Returns a [SlimAggregationOutcome] wrapping the standard
     * [SlimapiPermissionEntry] list for caller compatibility.
     */
    suspend fun getSlimapiPermissions(
        directories: List<String>? = null,
        token: SlimCommitToken,
    ): Result<SlimAggregationOutcome<SlimapiPermissionEntry>> = runSuspendCatching {
        // lite-v2-dev: use standard API, map results to SlimapiPermissionEntry.
        val items = api.getPendingPermissions().map { p ->
            SlimapiPermissionEntry(
                id = p.id,
                sessionId = p.sessionId,
                permission = p.permission,
                patterns = p.patterns,
                metadata = p.metadata,
                always = p.always,
                tool = p.tool,
                directory = null,
            )
        }
        SlimAggregationOutcome.Success(
            items = items,
            authoritativeDirectories = directories?.toSet(),
            serverScope = null,
        )
    }

    // aggregationOutcome moved to SlimAggregationOutcome.kt

    /**
     * lite-v2-dev: reply to a question. /slimapi/questions/{id}/reply removed;
     * re-routes to the standard /question/{id}/reply endpoint. The routeToken
     * parameter is accepted but ignored (standard API does not use it).
     */
    suspend fun replySlimapiQuestion(
        questionId: String,
        answers: List<List<String>>,
        @Suppress("UNUSED_PARAMETER") routeToken: String?
    ): Result<Unit> = runSuspendCatching {
        val response = mutationApi.replyQuestion(
            questionId,
            QuestionReplyRequest(answers = answers),
            null,
        )
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Reply failed ${response.code()}: $errorBody")
        }
    }

    /** lite-v2-dev: reject a question. Re-routes to standard /question/{id}/reject. */
    suspend fun rejectSlimapiQuestion(
        questionId: String,
        @Suppress("UNUSED_PARAMETER") routeToken: String?
    ): Result<Unit> = runSuspendCatching {
        val response = mutationApi.rejectQuestion(questionId, null)
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Reject failed ${response.code()}: $errorBody")
        }
    }

    /**
     * lite-v2-dev: respond to a permission. /slimapi/permissions removed;
     * re-routes to the standard /session/{id}/permissions/{id} endpoint.
     * The routeToken parameter is accepted but ignored.
     */
    suspend fun respondSlimapiPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        @Suppress("UNUSED_PARAMETER") routeToken: String?
    ): Result<Unit> = runSuspendCatching {
        val resp = mutationApi.respondPermission(
            sessionId,
            permissionId,
            PermissionResponseRequest(response.value)
        )
        if (!resp.isSuccessful) {
            val errorBody = resp.errorBody()?.string() ?: resp.message()
            throw Exception("Permission respond failed ${resp.code()}: $errorBody")
        }
    }

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
    suspend fun getSlimapiMessagesPage(
        sessionId: String,
        limit: Int,
        before: String?,
        @Suppress("UNUSED_PARAMETER") mode: String = "skeleton",
        @Suppress("UNUSED_PARAMETER") token: SlimCommitToken = captureSlimCommitToken(),
    ): Result<MessagesPage> = getMessagesPaged(sessionId, limit, before)
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
