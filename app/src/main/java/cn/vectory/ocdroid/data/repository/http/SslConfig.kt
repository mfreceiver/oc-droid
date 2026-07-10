package cn.vectory.ocdroid.data.repository.http

import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * R-01 / R-18: SSL configuration abstraction. The default path delegates to
 * the system trust store; only when a profile explicitly opts into
 * `allowInsecureConnections` do we downgrade to trust-all. Extracted from
 * `OpenCodeRepository` to give all four OkHttp clients (REST / SSE / tunnel /
 * health) a single shared entry point and remove the previously duplicated
 * `applySsl` blocks.
 *
 * §2.1: adds the [MutualTLS] branch for client-certificate mTLS (stunnel +
 * shared PKCS12). mTLS takes priority over `allowInsecure`; the mTLS path
 * NEVER overrides the hostname verifier (strict CN/SAN check against the
 * stunnel server cert).
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

    /**
     * §2.1: mTLS — presents a client certificate (PKCS12) during the TLS
     * handshake. [caBytes] non-null at build time → the [trustManager] only
     * trusts the supplied private CA(s); null → platform CA. The mTLS path
     * does NOT override the hostname verifier (strict CN/SAN), unlike
     * [TrustAll].
     */
    data class MutualTLS(
        val trustManager: X509TrustManager,
        val socketFactory: SSLSocketFactory
    ) : SslConfig

    /**
     * §tofu R2: SSH-style TOFU — accepts the server chain iff the leaf SPKI
     * matches the pinned fingerprint for this host:port. [trustManager] is a
     * [PinningTrustManager]; the pin IS the trust (no chain/path validation).
     * The hostname verifier is permissive (the SPKI pin, bound to the
     * host:port we connected to, is the identity — grill Q4/Q5).
     */
    data class TofuPinned(
        val trustManager: X509TrustManager,
        val socketFactory: SSLSocketFactory
    ) : SslConfig
}

/**
 * §2.1: mTLS 客户端证书材料。仅存于应用进程内存（configure 时从 ESP 载入），
 * **不安装进安卓系统证书库**。安全水位：静态由 AndroidKeyStore MasterKey 加密
 * （设备支持时硬件 backed，不保证普遍硬件 backed）；运行时私钥在进程堆，
 * JVM/Android 无法保证完全擦除（见 plan §3）。
 *
 * @param p12Bytes    PKCS12 bundle（客户端证书 + 私钥 [+ CA 链]）
 * @param p12Password PKCS12 导出密码（允许空字符串；openssl -password pass: 合法）
 * @param caBytes     可选私有 CA（PEM 或 DER）。非空 → **只信该 CA**（严格，
 *                    防止 WebPKI 有效证书的 MITM）；为空 → 仅信平台 CA
 *                    （服务端证书为公开签发时，如 Let's Encrypt）
 */
data class ClientCertMaterial(
    val p12Bytes: ByteArray,
    val p12Password: CharArray,
    val caBytes: ByteArray? = null
) {
    // 身份相等；非"材料相同"判定（ByteArray 内容相等语义易误判 + 性能差，
    // 且本类生命周期内不需要内容相等）。
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * §2.1: 无状态构建 mTLS [SslConfig.MutualTLS]。既被
 * [SslConfigFactory.configureClientCert] 缓存，也被
 * [OpenCodeRepository.checkHealthFor] 用于一次性探测（不污染单例状态）。
 *
 * 纯 JDK 实现（无 okhttp-tls，无新增依赖）：
 *  1. 载入 PKCS12；强制单一 key entry（多 key entry 下默认 X509KeyManager
 *     可能出示非预期证书，v3-gpter R2#1重要）。
 *  2. TrustManager：caBytes 非空→只信私有 CA（严格，防 WebPKI MITM）；
 *     空→平台 CA。CA bundle 用 `generateCertificates`（复数）支持 PEM bundle /
 *     中间 CA（v3-gpter R2#5）。
 *  3. SSLContext：客户端 KeyManager + 服务端 TrustManager。
 */
internal fun buildMutualTlsConfig(material: ClientCertMaterial): SslConfig.MutualTLS {
    // 1. 载入 PKCS12；强制单一 key entry（多 key entry 下默认 X509KeyManager
    //    可能出示非预期证书，v3-gpter）。KeyManagerFactory 用整 p12（单 key
    //    entry → KMF 必出示该 key；自动带证书链含中间 CA）。
    val p12 = KeyStore.getInstance("PKCS12").apply {
        load(ByteArrayInputStream(material.p12Bytes), material.p12Password)
    }
    val keyAliases = p12.aliases().toList().filter { p12.isKeyEntry(it) }
    require(keyAliases.size == 1) {
        "PKCS12 must contain exactly one key entry (found ${keyAliases.size})"
    }
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        .apply { init(p12, material.p12Password) }

    // 2. TrustManager：caBytes 非空→只信私有 CA（严格，防 WebPKI MITM）；
    //    空→平台 CA。
    val trustManager: X509TrustManager = run {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        if (material.caBytes != null) {
            // CA bundle：generateCertificates（复数）支持 PEM bundle / 中间 CA
            // （v3-gpter R2#5）。逐张 setCertificateEntry 进空 KeyStore 作为锚点。
            val certs = CertificateFactory.getInstance("X.509")
                .generateCertificates(ByteArrayInputStream(material.caBytes))
            val anchorKs = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            certs.forEachIndexed { i, c -> anchorKs.setCertificateEntry("opencode-ca-$i", c) }
            tmf.init(anchorKs) // 只信私有 CA（们）
        } else {
            tmf.init(null as KeyStore?) // 平台 CA
        }
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    // 3. SSLContext：客户端 KeyManager + 服务端 TrustManager。
    val ctx = SSLContext.getInstance("TLS").apply {
        init(kmf.keyManagers, arrayOf<TrustManager>(trustManager), SecureRandom())
    }
    return SslConfig.MutualTLS(trustManager, ctx.socketFactory)
}

/**
 * §tofu R2: builds a [SslConfig.TofuPinned] that accepts a server leaf iff its
 * SPKI SHA-256 == [spkiHex]. The pin IS the trust (no chain validation); the
 * hostname verifier is permissive (identity = the pin). Built per stored pin.
 */
internal fun buildTofuPinnedConfig(spkiHex: String): SslConfig.TofuPinned {
    val tm = PinningTrustManager(spkiHex)
    val ctx = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(tm), SecureRandom())
    }
    return SslConfig.TofuPinned(tm, ctx.socketFactory)
}

/**
 * Builds (and memoizes) the [SslConfig] appropriate for a given
 * [allowInsecure] flag / client certificate. `@Singleton` so the trust-all
 * SSLContext is initialized at most once per process — consistent with the
 * pre-R-18 `private val trustAllConfig: SslConfig.TrustAll by lazy { ... }`
 * field on the repository.
 *
 * §2.1: adds the mTLS branch. `sslConfigFor` returns mTLS first (priority
 * over allowInsecure); `resolveProbe` is the stateless variant for one-shot
 * health probes so probing an unrelated profile never reuses the live mTLS
 * cache (v3-gpter R2#1 阻断).
 */
@Singleton
class SslConfigFactory @Inject constructor(
    private val tofuStore: TofuPinStore
) {

    private val trustAllTrustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val trustAllConfig: SslConfig.TrustAll by lazy {
        SslConfig.TrustAll(trustAllTrustManager, buildTrustAllSocketFactory())
    }

    /**
     * §2.1: 当前客户端证书对应的 mTLS 配置（configure 时缓存）。null=未配置
     * 或最近一次加载失败（见 [lastClientCertError]）。
     */
    @Volatile
    private var mutualTlsConfig: SslConfig.MutualTLS? = null

    /**
     * §2.1 / v3-glmer R2: 非空=最近一次客户端证书加载/解析失败原因（可观测
     * 降级标志，防冷启崩溃）。null=ok 或未配置。
     */
    @Volatile
    var lastClientCertError: String? = null
        private set

    /**
     * §2.1: 设置/清除当前客户端证书。包 `runCatching`：p12 损坏/CA 无法解析
     * 时不抛穿栈导致冷启崩溃，而是降级 `mutualTlsConfig=null`（回 SystemDefault）
     * 并记 [lastClientCertError] 供 UI 诊断。
     */
    fun configureClientCert(material: ClientCertMaterial?) {
        if (material == null) {
            mutualTlsConfig = null
            lastClientCertError = null
            return
        }
        runCatching { buildMutualTlsConfig(material) }
            .onSuccess {
                mutualTlsConfig = it
                lastClientCertError = null
            }
            .onFailure {
                mutualTlsConfig = null
                lastClientCertError = it.message ?: "client cert load failed"
            }
    }

    /**
     * 有状态解析（给 live client）：mTLS 优先；否则该 host:port 有 TOFU pin →
     * TofuPinned；否则 SystemDefault（公网证书静默放行；握手失败才触发捕获）。
     * §tofu R2: [hostPort] 替代了原 allowInsecure（grill Q5/Q6）。
     */
    fun sslConfigFor(hostPort: String?): SslConfig = when {
        mutualTlsConfig != null -> mutualTlsConfig!!
        else -> hostPort?.let { tofuStore.pinnedSpki(it) }?.let { buildTofuPinnedConfig(it) }
            ?: SslConfig.SystemDefault
    }

    /**
     * §2.1 / v3-gpter R2#1 阻断修复：纯参数解析（给一次性 health 探测），
     * **不读 held [mutualTlsConfig]**，完全由入参决定——否则测他 profile
     * （clientCert=null）时会复用当前 mTLS profile 的缓存，误出示其客户端证书 /
     * 只信其私有 CA，甚至泄漏客户端身份给无关 host。
     * §tofu R2: [hostPort] 替代 allowInsecure（探测也走 TOFU pin 查询）。
     */
    fun resolveProbe(hostPort: String?, clientCert: ClientCertMaterial?): SslConfig = when {
        clientCert != null -> buildMutualTlsConfig(clientCert)
        else -> hostPort?.let { tofuStore.pinnedSpki(it) }?.let { buildTofuPinnedConfig(it) }
            ?: SslConfig.SystemDefault
    }

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
 * §2.1: [SslConfig.MutualTLS] installs the client-key socket factory + the
 * private/platform trust manager, but **does NOT override the hostname
 * verifier** — OkHttp's strict default applies, so the stunnel server cert's
 * SAN MUST match the serverUrl host (DNS or IP). This is the single canonical
 * SSL entry point — every OkHttp client built by [OkHttpClientFactory] routes
 * through here.
 */
fun OkHttpClient.Builder.applySsl(cfg: SslConfig): OkHttpClient.Builder = when (cfg) {
    SslConfig.SystemDefault -> this
    is SslConfig.TrustAll ->
        sslSocketFactory(cfg.socketFactory, cfg.trustManager)
            .hostnameVerifier { _, _ -> true }
    is SslConfig.MutualTLS ->
        sslSocketFactory(cfg.socketFactory, cfg.trustManager)
        // 无 hostnameVerifier 覆盖 → OkHttp 严格默认。stunnel 服务端证书 SAN
        // 必须匹配 serverUrl host（DNS / IP）。
    is SslConfig.TofuPinned ->
        sslSocketFactory(cfg.socketFactory, cfg.trustManager)
            .hostnameVerifier { _, _ -> true }
        // §tofu R2: pin 即身份（SPKI 绑定 host:port）。自签名证书 SAN 常不匹配
        // 连接目标，故放行 hostnameVerifier——安全由 PinningTrustManager 的 SPKI
        // 比对保证，而非主机名（grill Q4）。
}
