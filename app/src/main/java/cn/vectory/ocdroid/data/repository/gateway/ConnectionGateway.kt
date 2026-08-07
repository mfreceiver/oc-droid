package cn.vectory.ocdroid.data.repository.gateway

import cn.vectory.ocdroid.data.model.HealthResponse
import cn.vectory.ocdroid.data.repository.SlimapiFeatures
import cn.vectory.ocdroid.data.repository.SlimapiHealthPayload
import cn.vectory.ocdroid.data.repository.ClientBundle
import cn.vectory.ocdroid.data.repository.RepositoryNetworkGraph
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.data.repository.http.ClientCertMaterial
import cn.vectory.ocdroid.data.repository.http.HttpHeaders
import cn.vectory.ocdroid.data.repository.http.SlimapiContract
import cn.vectory.ocdroid.data.repository.http.SslConfig
import cn.vectory.ocdroid.data.repository.http.applyClientIdentityHeaders
import cn.vectory.ocdroid.data.repository.http.hostPortFromUrl
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64

/**
 * Gateway for connection lifecycle operations: SSL/mTLS configuration,
 * health probes, capability read-model, and token-stream client construction.
 *
 * Zero mutable state — all reads go through [bundleProvider] every call,
 * preserving the generational-consistency invariant.
 */
internal class ConnectionGateway(
    private val bundleProvider: () -> ClientBundle,
    private val serverCompatProfile: ServerCompatProfile,
    private val json: Json,
    private val networkGraph: RepositoryNetworkGraph,
    private val identityProvider: () -> String,
) {
    companion object {
        private const val TAG = "ConnectionGateway"
    }

    // ── SSL / TLS / mTLS ────────────────────────────────────────────────

    fun currentSslConfig(): SslConfig = bundleProvider().effectiveSslConfig

    fun isMutualTlsActive(): Boolean = currentSslConfig() is SslConfig.MutualTLS

    val lastClientCertError: String? get() = bundleProvider().clientCertError

    // ── Capability read-model ───────────────────────────────────────────

    val isSlimMode: Boolean get() = bundleProvider().hostSnapshot.slimHost

    val supportsWatermarkResync: Boolean get() = serverCompatProfile.slimConnection

    val supportsTokenStreamResync: Boolean get() = serverCompatProfile.slimConnection

    val usesSlimStatusFanOut: Boolean get() = serverCompatProfile.slimConnection

    // ── Token-stream client ─────────────────────────────────────────────

    fun tokenStreamClient(hostPort: String?): OkHttpClient {
        val bundle = bundleProvider()
        return networkGraph.clientFactoryFor(bundle.hostSnapshot, bundle.effectiveSslConfig)
            .tokenStreamClient(hostPort)
    }

    fun tokenStreamClient(bundle: ClientBundle): OkHttpClient =
        networkGraph.clientFactoryFor(bundle.hostSnapshot, bundle.effectiveSslConfig)
            .tokenStreamClient(bundle.hostSnapshot.hostPort)

    // ── Health probes ───────────────────────────────────────────────────

    suspend fun checkHealth(): Result<HealthResponse> = runSuspendCatching {
        val bundle = bundleProvider()
        if (!bundle.hostSnapshot.slimHost) {
            bundle.restApi.getHealth()
        } else {
            probeSlimapiHealth(
                baseUrl = bundle.hostSnapshot.baseUrl,
                username = bundle.hostSnapshot.username,
                password = bundle.hostSnapshot.password,
                sslConfig = bundle.effectiveSslConfig,
            )
        }
    }

    // TODO(test-determinism): this hardcoded withContext(Dispatchers.IO) is
    // NOT covered by the injectable networkDispatcher in ConnectionHealthProbe
    // (§network-off-main). Unlike repository.checkHealth() / engine.bootstrap()
    // — which hop via the injected dispatcher (prod = Dispatchers.IO, tests =
    // TestScope) — this slim health path always escapes to real Dispatchers.IO.
    // Currently safe because probe tests mock/avoid this path; a future
    // slim-probe test that injects a TestScope WILL see this hop escape to a
    // real IO thread and flake under advanceUntilIdle(). Thread the injected
    // dispatcher here too (or extract the health client call to a dispatcher-
    // injected seam) if such a test is added.
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
        applyClientIdentityHeaders(requestBuilder, identityProvider())
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
            serverCompatProfile.updateSlimapi(payload)
            val healthy = payload.sidecarOk == true &&
                payload.serverApiVersion != null &&
                SlimapiContract.SLIMAPI_CLIENT_VERSION in
                (payload.acceptedClientVersions?.first ?: Int.MIN_VALUE)..(payload.acceptedClientVersions?.second ?: Int.MIN_VALUE)
            // §banner-stuck-diag: healthy=false 时打印三元组细节到应用内 DebugLog
            // （设置→Debug 页可见），让"版本门 / sidecar degraded / api_version 缺失"
            // 可在应用内一秒区分，无需 adb。
            if (!healthy) {
                DebugLog.w(
                    TAG,
                    "slimapi health unhealthy: sidecarOk=${payload.sidecarOk} " +
                        "schemaDegraded=${payload.schemaDegraded} " +
                        "apiVersion=${payload.serverApiVersion} " +
                        "acceptedClientVersions=${payload.acceptedClientVersions} " +
                        "clientVersion=${SlimapiContract.SLIMAPI_CLIENT_VERSION}",
                )
            }
            HealthResponse(
                healthy = healthy,
                version = payload.serverApiVersion?.let { "$it-slim" }
            )
        }
    }

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
        val tokenStream = featuresObj?.get("tokenStream")?.safePrimitive()?.let { p ->
            p.booleanOrNull == true || p.content.equals("true", ignoreCase = true)
        } == true
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
            val resolvedHostPort = hostPort ?: hostPortFromUrl(baseUrl)
            val cfg: SslConfig = networkGraph.sslConfigFactory.resolveProbe(resolvedHostPort, clientCert, trustAll)
            val client = networkGraph.clientFactory.healthClient(cfg)
            val healthPath = if (slim) SlimapiContract.SLIMAPI_HEALTH_PATH
                else SlimapiContract.LEGACY_HEALTH_PATH
            val normalizedUrl = (if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl")
                .trimEnd('/') + healthPath
            val requestBuilder = Request.Builder()
                .url(normalizedUrl)
                .header(HttpHeaders.SKIP_DIR_HEADER, "1")
            if (slim) {
                requestBuilder.header(
                    SlimapiContract.X_SLIMAPI_VERSION,
                    SlimapiContract.SLIMAPI_CLIENT_VERSION.toString()
                )
                applyClientIdentityHeaders(requestBuilder, identityProvider())
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
                    val payload = parseSlimapiHealth(body)
                    serverCompatProfile.updateSlimapi(payload)
                    val accepted = payload.acceptedClientVersions != null &&
                        SlimapiContract.SLIMAPI_CLIENT_VERSION in
                        payload.acceptedClientVersions!!.first..payload.acceptedClientVersions!!.second
                    val healthy = payload.sidecarOk == true && accepted
                    if (!healthy) error("slimapi sidecar unhealthy or client version incompatible")
                    HealthResponse(
                        healthy = true,
                        version = payload.serverApiVersion?.let { "$it-slim" }
                    )
                } else {
                    json.decodeFromString(HealthResponse.serializer(), body)
                }
            }
        }
    }
}

private fun JsonElement.safeObject(): JsonObject? = this as? JsonObject
private fun JsonElement.safeArray(): JsonArray? = this as? JsonArray
private fun JsonElement.safePrimitive(): JsonPrimitive? = this as? JsonPrimitive
