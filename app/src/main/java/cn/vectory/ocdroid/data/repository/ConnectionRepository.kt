package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.HealthResponse
import cn.vectory.ocdroid.data.repository.http.ClientCertMaterial
import cn.vectory.ocdroid.data.repository.http.SslConfig
import okhttp3.OkHttpClient

/** Phase B narrow seam: connection lifecycle reads / health probes / SSL / mTLS / capability read-model.
 *  Implemented by [OpenCodeRepository] (composite). `configure(...)` stays on the concrete OCR class
 *  (it mutates host/bundle/profile state inside a @Synchronized CAS transaction). */
interface ConnectionRepository {
    val isSlimMode: Boolean
    val supportsWatermarkResync: Boolean
    val supportsTokenStreamResync: Boolean
    val usesSlimStatusFanOut: Boolean
    val lastClientCertError: String?
    fun currentSslConfig(): SslConfig
    fun isMutualTlsActive(): Boolean
    fun tokenStreamClient(hostPort: String?): OkHttpClient
    suspend fun checkHealth(): Result<HealthResponse>
    fun parseSlimapiHealth(body: String): SlimapiHealthPayload
    suspend fun checkHealthFor(
        baseUrl: String,
        username: String? = null,
        password: String? = null,
        hostPort: String? = null,
        clientCert: ClientCertMaterial? = null,
        slim: Boolean = false,
        trustAll: Boolean = false,
    ): Result<HealthResponse>
}
