package cn.vectory.ocdroid.data.repository.http

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * R-01 / R-18: SSL configuration abstraction. The default path delegates to
 * the system trust store; only when a profile explicitly opts into
 * `allowInsecureConnections` do we downgrade to trust-all. Extracted from
 * `OpenCodeRepository` to give all four OkHttp clients (REST / SSE / tunnel /
 * health) a single shared entry point and remove the previously duplicated
 * `applySsl` blocks.
 */
sealed interface SslConfig {

    /** System trust store only — the only safe default. */
    data object SystemDefault : SslConfig

    /**
     * Trust-all manager + permissive hostname verifier. The wrapped
     * [X509TrustManager] and [SSLSocketFactory] are cached by
     * [SslConfigFactory] so SSLContext initialization happens at most once
     * per process.
     */
    data class TrustAll(
        val trustManager: X509TrustManager,
        val socketFactory: SSLSocketFactory
    ) : SslConfig
}

/**
 * Builds (and memoizes) the [SslConfig] appropriate for a given
 * [allowInsecure] flag. `@Singleton` so the trust-all SSLContext is
 * initialized at most once per process — consistent with the pre-R-18
 * `private val trustAllConfig: SslConfig.TrustAll by lazy { ... }` field on
 * the repository.
 */
@Singleton
class SslConfigFactory @Inject constructor() {

    private val trustAllTrustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val trustAllConfig: SslConfig.TrustAll by lazy {
        SslConfig.TrustAll(trustAllTrustManager, buildTrustAllSocketFactory())
    }

    /** Returns the cached trust-all config when [allowInsecure] is true. */
    fun sslConfigFor(allowInsecure: Boolean): SslConfig =
        if (allowInsecure) trustAllConfig else SslConfig.SystemDefault

    private fun buildTrustAllSocketFactory(): SSLSocketFactory {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustAllTrustManager), SecureRandom())
        return context.socketFactory
    }
}

/**
 * Applies [cfg] to this builder. No-op for [SslConfig.SystemDefault] so the
 * JVM default trust store / hostname verifier are used; for
 * [SslConfig.TrustAll] installs the permissive socket factory + hostname
 * verifier.
 *
 * This is the single canonical SSL entry point — every OkHttp client built
 * by [OkHttpClientFactory] routes through here.
 */
fun OkHttpClient.Builder.applySsl(cfg: SslConfig): OkHttpClient.Builder = when (cfg) {
    SslConfig.SystemDefault -> this
    is SslConfig.TrustAll ->
        sslSocketFactory(cfg.socketFactory, cfg.trustManager)
            .hostnameVerifier { _, _ -> true }
}
