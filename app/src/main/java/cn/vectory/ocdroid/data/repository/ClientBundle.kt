package cn.vectory.ocdroid.data.repository

import androidx.annotation.VisibleForTesting
import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.api.SSEClient
import cn.vectory.ocdroid.data.api.v2.OpenCodeApiV2
import cn.vectory.ocdroid.data.repository.http.SslConfig
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import retrofit2.Retrofit

/**
 * One immutable connection generation.
 *
 * Every URL, host identity, credential-bearing interceptor, slim route,
 * effective TLS configuration, API facade and generation-owned OkHttp client
 * is composed before this object is published. The repository replaces this
 * object once through its single volatile publication point; callers must not
 * cache individual fields outside the bundle.
 */
internal class ClientBundle(
    val generation: Long,
    val hostSnapshot: HostSnapshot,
    val effectiveSslConfig: SslConfig,
    val clientCertError: String?,
    val restHttp: OkHttpClient,
    val restRetrofit: Retrofit,
    val restApi: OpenCodeApi,
    val sseHttp: OkHttpClient,
    val sseClient: SSEClient,
    val commandHttp: OkHttpClient,
    val commandRetrofit: Retrofit,
    val commandApi: OpenCodeApi,
    val mutationHttp: OkHttpClient,
    val mutationRetrofit: Retrofit,
    val mutationApi: OpenCodeApi,
    val v2Retrofit: Retrofit,
    val apiV2: OpenCodeApiV2,
    ownedClients: List<OkHttpClient>,
) {
    /** Compatibility projections; the [HostSnapshot] remains the source. */
    val slimHost: Boolean get() = hostSnapshot.slimHost
    val endpointFp: String get() = hostSnapshot.baseUrl

    /** Generation resources only; the shared disk Cache is intentionally absent. */
    private val ownedGenerationClients: List<OkHttpClient> = ownedClients.toList()
    private val retired = AtomicBoolean(false)

    internal val isRetired: Boolean get() = retired.get()

    /**
     * Test-only structural copy for routing tests.
     *
     * The generation, host, transport, Retrofit, SSE and v2 API values are
     * deliberately carried over unchanged. Tests can therefore substitute a
     * recording facade without introducing mutable production mirrors or
     * publishing a partially-built client generation.
     */
    @VisibleForTesting
    internal fun withApisForTest(
        restApi: OpenCodeApi = this.restApi,
        commandApi: OpenCodeApi = this.commandApi,
        mutationApi: OpenCodeApi = this.mutationApi,
    ): ClientBundle = ClientBundle(
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
        v2Retrofit = v2Retrofit,
        apiV2 = apiV2,
        ownedClients = ownedGenerationClients,
    )

    /**
     * Retire this generation exactly once. OkHttp clients own dispatchers and
     * connection pools, while the disk Cache is owned by RepositoryNetworkGraph
     * and therefore is never closed here.
     */
    internal fun retire() {
        if (!retired.compareAndSet(false, true)) return
        ownedGenerationClients.forEach { client ->
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
    }
}
