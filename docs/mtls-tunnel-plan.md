# ocdroid mTLS 隧道方案（stunnel + OkHttp mTLS，共享客户端证书）— v3

> **v3（实施版）**：在 v2 基础上纳入第二轮评审（gpter+glmer）全部**真实**发现。
> v2→v3 变化：① **[阻断] `checkHealthFor` 状态污染** → 新增 `SslConfigFactory.resolveProbe()` 纯参数解析，
> 与有状态 `sslConfigFor` 分离；② **冷启崩溃零防御** → `configureClientCert` 内 `runCatching`+降级+可观测
> `lastClientCertError`；③ **首导空密码歧义** → 首导分支密码当 `""` 写；④ **stagedCa 三义** → `sealed CaStage`；
> ⑤ **ESP 非原子** → 单 `saveClientCert()` 批量提交；⑥ **alias 未约束** → `require(keyAliases.size==1)`；
> ⑦ §4 补 `ConnectionViewModelFormTest` 7 处 checkHealthFor stub；⑧ CA bundle 用 `generateCertificates`；
> ⑨ SAN 显式 DNS/IP 模板；⑩ **API26 模拟器实测 `-legacy` p12 为硬发布门**。
> （gpter 第二轮「源码未闭合」类阻断系把方案当已实现审，属 §5 待实施项，已剥离。）
>
> v2 要点保留：纯 JDK mTLS（无 okhttp-tls 进 main）/ 私有 CA 严格信任 / openssl `-legacy` / Dialog 纯 UI 材料外流。

## 0. 决策摘要

| 维度 | 选型 |
|---|---|
| 服务端 TLS 终止 | **stunnel**（mTLS，`verifyPeer=yes`，stunnel ≥5.x 语法） |
| 隧道 | 现有**纯 TCP 透传**（单次 TLS 终止，无 TLS-in-TLS） |
| 端口 | 非 443（实测可过 DPI；**非抗强指纹 DPI 伪装方案**） |
| App 端 | **OkHttp mTLS**，纯 JDK KeyManager/TrustManager 构建，应用进程内存，**不入系统证书库** |
| 客户端依赖 | **零新增**（不引 okhttp-tls 到 main；仅 java.security/javax.net.ssl） |
| 证书 | 私有 CA 签发服务端证书 + **共享**客户端 PKCS12（`-legacy` 导出）；带外分发 |
| 鉴权分层 | mTLS（传输层强认证+加密）+ opencode basic auth（应用层纵深，保留） |
| 吊销 | 共享证书泄漏=全盘失效，靠**整体轮换**（含 basic auth 联动），无单设备吊销 |

## 1. 服务端（stunnel + openssl）

### 1.1 生成私有 CA / 服务端证书 / 共享客户端 PKCS12

```bash
# --- 私有 CA（完整扩展）---
openssl req -x509 -newkey rsa:4096 -days 3650 -nodes \
  -keyout ca-key.pem -out ca-cert.pem \
  -subj "/CN=opencode Private CA" \
  -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign"

# --- 服务端证书（SAN 必须匹配 app serverUrl 里的 host；严格主机名校验按此匹配）---
# 设 SAN 变量——按连接方式二选一，勿混用（v3-gpter：原命令硬编码 DNS，IP 连接会握手失败）：
#   域名连接：SAN="subjectAltName=DNS:example.com"
#   IP 连接 ：SAN="subjectAltName=IP:1.2.3.4"
SAN="subjectAltName=DNS:<SERVER_HOST>"
openssl req -newkey rsa:2048 -nodes -keyout server-key.pem -out server-csr.pem \
  -subj "/CN=<SERVER_HOST>"
openssl x509 -req -in server-csr.pem -CA ca-cert.pem -CAkey ca-key.pem -CAcreateserial \
  -out server-cert.pem -days 825 \
  -extfile <(printf "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n$SAN")

# --- 共享客户端证书（clientAuth EKU）---
openssl req -newkey rsa:2048 -nodes -keyout client-key.pem -out client-csr.pem \
  -subj "/CN=ocdroid-shared-client"
openssl x509 -req -in client-csr.pem -CA ca-cert.pem -CAkey ca-key.pem -CAcreateserial \
  -out client-cert.pem -days 825 \
  -extfile <(printf "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=clientAuth")

# --- PKCS12：-legacy 兼容 minSdk 26（openssl 3.x 默认 PBES2/AES 在 Android ≤27 读不出）---
openssl pkcs12 -export -legacy \
  -inkey client-key.pem -in client-cert.pem -certfile ca-cert.pem \
  -name ocdroid-client -out client.p12 -password pass:<P12_PASSWORD>
```

**带外分发**：`client.p12` + `<P12_PASSWORD>`（可为空）+ `ca-cert.pem`（公开，让 App 信任服务端证书）。
> `-CAcreateserial` 首签创建 `.srl`、后续自增，重跑不重复 serial（glmer S4 已核）。

### 1.2 stunnel 配置（≥5.x）

```ini
setuid = stunnel
setgid = stunnel
pid = /run/stunnel/stunnel.pid

[opencode-mtls]
accept  = <PORT>              ; app serverUrl 指向的端口
connect = 127.0.0.1:4096      ; opencode serve，明文 HTTP

cert = /etc/stunnel/server-cert.pem   ; chmod 600 stunnel 可读
key  = /etc/stunnel/server-key.pem

; mTLS：强制客户端出示由本 CA 签发的证书（旧版 stunnel 用 verify = 3）
CAfile       = /etc/stunnel/ca-cert.pem
verifyPeer   = yes
requireCert  = yes
; sslVersion = TLSv1.2   ; 按部署版本确认，TLSv1.3 兼容性较窄
; CRLfile 不配：共享证书泄漏靠整体轮换 CA/客户端证书，不支持 CRL
```
`chmod 600 ca-key.pem client-key.pem server-key.pem`。

### 1.3 opencode 启动（保留 basic auth 作第二层）

```bash
export OPENCODE_SERVER_USERNAME=admin
export OPENCODE_SERVER_PASSWORD=<强应用层密码>
opencode serve --hostname 127.0.0.1 --port 4096   # 仅绑 127.0.0.1，只 stunnel 可达
```
app `serverUrl` = `https://<SERVER_HOST>:<PORT>`。**服务端证书 SAN 必须匹配 `<SERVER_HOST>`**（域名用 DNS、IP 用 IP），否则 mTLS 严格主机名校验失败。

---

## 2. 客户端改动（ocdroid）

### 2.1 `SslConfig.kt` — `MutualTLS` 分支 + `ClientCertMaterial` + 纯 JDK 构建（无 okhttp-tls）

```kotlin
package cn.vectory.ocdroid.data.repository.http

import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.PrivateKey
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
 * mTLS 客户端证书材料。仅存于应用进程内存（configure 时从 ESP 载入），
 * **不安装进安卓系统证书库**。安全水位：静态由 AndroidKeyStore MasterKey 加密
 * （设备支持时硬件 backed，不保证普遍硬件 backed）；运行时私钥在进程堆，
 * JVM/Android 无法保证完全擦除（见 §3）。
 *
 * @param p12Bytes    PKCS12 bundle（客户端证书 + 私钥 [+ CA 链]）
 * @param p12Password PKCS12 导出密码（允许空字符串；null 表未设置）
 * @param caBytes     可选私有 CA（PEM 或 DER）。非空 → **只信该 CA**（严格，
 *                    防止 WebPKI 有效证书的 MITM）；为空 → 仅信平台 CA
 *                    （服务端证书为公开签发时，如 Let's Encrypt）
 */
data class ClientCertMaterial(
    val p12Bytes: ByteArray,
    val p12Password: CharArray,
    val caBytes: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean = this === other   // 身份相等；非"材料相同"判定
    override fun hashCode(): Int = System.identityHashCode(this)
}

sealed interface SslConfig {
    data object SystemDefault : SslConfig
    data class TrustAll(val trustManager: X509TrustManager, val socketFactory: SSLSocketFactory) : SslConfig
    /** mTLS：出示客户端证书；caBytes 非空时只信私有 CA，否则平台 CA。严格主机名校验。 */
    data class MutualTLS(val trustManager: X509TrustManager, val socketFactory: SSLSocketFactory) : SslConfig
}

/**
 * 无状态构建 mTLS [SslConfig.MutualTLS]。既被 [SslConfigFactory.configureClientCert]
 * 缓存，也被 [OpenCodeRepository.checkHealthFor] 用于一次性探测（不污染单例状态）。
 */
internal fun buildMutualTlsConfig(material: ClientCertMaterial): SslConfig.MutualTLS {
    // 1. 载入 PKCS12；强制单一 key entry（多 key entry 下默认 X509KeyManager 可能出示非预期证书，v3-gpter）
    val p12 = KeyStore.getInstance("PKCS12").apply {
        load(ByteArrayInputStream(material.p12Bytes), material.p12Password)
    }
    val keyAliases = p12.aliases().toList().filter { p12.isKeyEntry(it) }
    require(keyAliases.size == 1) { "PKCS12 must contain exactly one key entry (found ${keyAliases.size})" }
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        .apply { init(p12, material.p12Password) }   // 单 key entry → KMF 必出示该 key；自动带证书链（含中间 CA）

    // 2. TrustManager：caBytes 非空→只信私有 CA（严格，防 WebPKI MITM）；空→平台 CA
    val trustManager: X509TrustManager = run {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        if (material.caBytes != null) {
            // CA bundle：generateCertificates（复数）支持 PEM bundle / 中间 CA（v3-gpter）
            val certs = CertificateFactory.getInstance("X.509")
                .generateCertificates(ByteArrayInputStream(material.caBytes))
            val anchorKs = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            certs.forEachIndexed { i, c -> anchorKs.setCertificateEntry("opencode-ca-$i", c) }
            tmf.init(anchorKs)          // 只信私有 CA（们）
        } else {
            tmf.init(null as KeyStore?) // 平台 CA
        }
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    // 3. SSLContext：客户端 KeyManager + 服务端 TrustManager
    val ctx = SSLContext.getInstance("TLS").apply {
        init(kmf.keyManagers, arrayOf<TrustManager>(trustManager), SecureRandom())
    }
    return SslConfig.MutualTLS(trustManager, ctx.socketFactory)
}

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

    @Volatile private var mutualTlsConfig: SslConfig.MutualTLS? = null
    /** 非空=最近一次客户端证书加载/解析失败原因（可观测降级标志，v3-glmer：防冷启崩溃）。null=ok 或未配置。 */
    @Volatile var lastClientCertError: String? = null
        private set

    /**
     * 设置/清除当前客户端证书。包 runCatching：p12 损坏/CA 无法解析时不抛穿栈导致冷启崩溃，
     * 而是降级 mutualTlsConfig=null（回 SystemDefault）并记 lastClientCertError 供 UI 诊断。
     */
    fun configureClientCert(material: ClientCertMaterial?) {
        if (material == null) { mutualTlsConfig = null; lastClientCertError = null; return }
        runCatching { buildMutualTlsConfig(material) }
            .onSuccess { mutualTlsConfig = it; lastClientCertError = null }
            .onFailure { mutualTlsConfig = null; lastClientCertError = it.message ?: "client cert load failed" }
    }

    /** 有状态解析（给 live client）：mTLS 优先于 allowInsecure；SystemDefault 安全兜底。 */
    fun sslConfigFor(allowInsecure: Boolean): SslConfig = when {
        mutualTlsConfig != null -> mutualTlsConfig!!
        allowInsecure -> trustAllConfig
        else -> SslConfig.SystemDefault
    }

    /**
     * 纯参数解析（给一次性 health 探测，v3-gpter 阻断修复）：**不读 held mutualTlsConfig**，
     * 完全由入参决定——否则测他 profile（clientCert=null）时会复用当前 mTLS profile 的缓存，
     * 误出示其客户端证书/只信其私有 CA，甚至泄漏客户端身份给无关 host。
     */
    fun resolveProbe(allowInsecure: Boolean, clientCert: ClientCertMaterial?): SslConfig = when {
        clientCert != null -> buildMutualTlsConfig(clientCert)
        allowInsecure -> trustAllConfig
        else -> SslConfig.SystemDefault
    }

    private fun buildTrustAllSocketFactory(): SSLSocketFactory {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustAllTrustManager), SecureRandom())
        return context.socketFactory
    }
}

/** 单一 SSL 入口。MutualTLS **不**覆盖 hostnameVerifier——严格校验 CN/SAN。 */
fun OkHttpClient.Builder.applySsl(cfg: SslConfig): OkHttpClient.Builder = when (cfg) {
    SslConfig.SystemDefault -> this
    is SslConfig.TrustAll ->
        sslSocketFactory(cfg.socketFactory, cfg.trustManager).hostnameVerifier { _, _ -> true }
    is SslConfig.MutualTLS ->
        sslSocketFactory(cfg.socketFactory, cfg.trustManager)
        // 无 hostnameVerifier 覆盖 → OkHttp 严格默认。stunnel 服务端证书 SAN 必须匹配 serverUrl host。
}
```

> **v1→v2 关键**：弃用 `HandshakeCertificates`（其 `heldCertificate` 仅接受 `HeldCertificate`，
> 无 `(PrivateKey, X509Certificate)` 重载——v1 编译错 B1；且需把 okhttp-tls 从 testImpl 提到 main——B2）。
> 纯 JDK 实现零新增依赖，并直接支持「私有 CA 严格模式」与「平台 CA 模式」二选一（gpter#7）。

### 2.2 `HostProfile.kt` — 加 mTLS 字段

```kotlin
@Serializable
data class HostProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    @SerialName("serverURL") val serverUrl: String,
    val basicAuth: BasicAuthConfig? = null,
    val tunnelPasswordId: String? = null,
    @SerialName("serverGroupFp") val serverGroupFp: String = "",
    @SerialName("allowInsecureConnections") val allowInsecureConnections: Boolean = false,
    /** 开启后所有客户端 TLS 握手出示 [clientCertId] 的 PKCS12。与 allowInsecure 效果互斥（mTLS 优先）。 */
    @SerialName("mtlsEnabled") val mtlsEnabled: Boolean = false,
    /** 客户端 PKCS12(+密码+可选 CA) 在 ESP 的 key 后缀。null ⇒ 无。 */
    @SerialName("clientCertId") val clientCertId: String? = null,
    val lastUsedAt: Long? = null
) { /* displayName/connectionSummary/companion 不变 */ }
```
- **向后兼容**：旧 JSON 无新字段 → `mtlsEnabled=false`/`clientCertId=null`（默认值）。
- **导入/导出**：`HostProfileExportPayload.from()` / `HostProfileImportPayload` 均不复制 mTLS 字段 → 证书材料绝不随导出离开设备。

### 2.3 `SettingsManager.kt` — PKCS12 / CA / 密码 存取（ESP）

```kotlin
import cn.vectory.ocdroid.data.repository.http.ClientCertMaterial
import java.util.Base64   // 与 OpenCodeRepository 一致（JVM，可单测）

// 放 companion object（与 basicAuthPasswordKey/tunnelPasswordKey 同风格）
companion object {
    private fun clientCertP12Key(id: String) = "client_cert_p12_$id"
    private fun clientCertPasswordKey(id: String) = "client_cert_pw_$id"
    private fun clientCertCaKey(id: String) = "client_cert_ca_$id"
}

fun getClientCertP12(id: String): ByteArray? =
    encryptedPrefs.getString(clientCertP12Key(id), null)?.let { Base64.getDecoder().decode(it) }
fun setClientCertP12(id: String, bytes: ByteArray?) {
    encryptedPrefs.edit().apply {
        if (bytes == null) remove(clientCertP12Key(id))
        else putString(clientCertP12Key(id), Base64.getEncoder().encodeToString(bytes))
    }.apply()
}

// 用 == null 判清除，允许空字符串密码（openssl -password pass: 合法）—— v1 用 isNullOrBlank 会丢空密码
fun getClientCertPassword(id: String): String? = encryptedPrefs.getString(clientCertPasswordKey(id), null)
fun setClientCertPassword(id: String, value: String?) {
    encryptedPrefs.edit().apply {
        if (value == null) remove(clientCertPasswordKey(id)) else putString(clientCertPasswordKey(id), value)
    }.apply()
}

// CA 用 ByteArray（PEM 或 DER 都二进制安全；CertificateFactory 都吃）—— v1 用 readText() 对 DER 会坏
fun getClientCertCa(id: String): ByteArray? =
    encryptedPrefs.getString(clientCertCaKey(id), null)?.let { Base64.getDecoder().decode(it) }
fun setClientCertCa(id: String, bytes: ByteArray?) {
    encryptedPrefs.edit().apply {
        if (bytes == null) remove(clientCertCaKey(id))
        else putString(clientCertCaKey(id), Base64.getEncoder().encodeToString(bytes))
    }.apply()
}

fun clearClientCert(id: String) {
    encryptedPrefs.edit()
        .remove(clientCertP12Key(id)).remove(clientCertPasswordKey(id)).remove(clientCertCaKey(id)).apply()
}

/** 单次原子提交 p12+密码+CA（v3-gpter：三个独立 apply() 非原子，崩溃/并发可半写）。null 表清除该项。 */
fun saveClientCert(id: String, p12: ByteArray?, password: String?, ca: ByteArray?) {
    encryptedPrefs.edit().apply {
        if (p12 == null) remove(clientCertP12Key(id))
        else putString(clientCertP12Key(id), Base64.getEncoder().encodeToString(p12))
        if (password == null) remove(clientCertPasswordKey(id))
        else putString(clientCertPasswordKey(id), password)
        if (ca == null) remove(clientCertCaKey(id))
        else putString(clientCertCaKey(id), Base64.getEncoder().encodeToString(ca))
    }.apply()
}

fun loadClientCertMaterial(clientCertId: String): ClientCertMaterial? {
    val p12 = getClientCertP12(clientCertId) ?: return null
    val pw = getClientCertPassword(clientCertId) ?: return null   // 空字符串密码 != null，能通过
    return ClientCertMaterial(p12, pw.toCharArray(), getClientCertCa(clientCertId))
}
```

**`clearAllLocalData()` 必改**（`SettingsManager.kt:715-759` 现仅保留 `basic_auth_password_`/`tunnel_password_`）：
把 `client_cert_p12_`/`client_cert_pw_`/`client_cert_ca_` 三前缀加入保留白名单（与连接凭据同语义）。
否则「清除本地数据」会删证书材料但保留 `KEY_HOST_PROFILES` 里的 `mtlsEnabled=true/clientCertId` → 悬空引用、mTLS 静默失效（glmer I5 / gpter 重要#6）。

### 2.4 `OpenCodeRepository.kt`

```kotlin
// 原 OkHttpClientFactory(SslConfigFactory(), ...) → 持有引用
private val sslConfigFactory = SslConfigFactory()
private val clientFactory = OkHttpClientFactory(sslConfigFactory, directoryHeaderInterceptor,
    authInterceptor, cacheControlInterceptor, trafficCountingInterceptor, responseSizeGuardInterceptor)

@Synchronized
fun configure(
    baseUrl: String, username: String? = null, password: String? = null,
    allowInsecureConnections: Boolean = false,
    clientCert: ClientCertMaterial? = null   // 新增；默认 null → 现有调用源码兼容
) {
    sslConfigFactory.configureClientCert(clientCert)   // 必须在 rebuildClients 前
    hostConfig.configure(baseUrl, username, password, allowInsecureConnections)
    rebuildClients()
}

@Synchronized
fun currentSslConfig(): SslConfig = sslConfigFactory.sslConfigFor(hostConfig.allowInsecure)
```

**`checkHealthFor` 加 clientCert（测试连接路径，gpter#3/glmer I6）**：

```kotlin
suspend fun checkHealthFor(
    baseUrl: String, username: String? = null, password: String? = null,
    allowInsecure: Boolean = false,
    clientCert: ClientCertMaterial? = null   // 新增
): Result<HealthResponse> = withContext(Dispatchers.IO) {
    runSuspendCatching {
        // v3-gpter 阻断修复：用 resolveProbe 纯参数解析，**不读** sslConfigFor 的 held mTLS 状态，
        // 否则测他 profile（clientCert=null）会复用当前 mTLS profile 缓存 → 误出示证书/泄漏身份。
        val cfg: SslConfig = sslConfigFactory.resolveProbe(allowInsecure, clientCert)
        val client = clientFactory.healthClient(cfg)   // 新重载（见 OkHttpClientFactory）
        // ...（原 normalizedUrl / Authorization 构造不变）
    }
}
```

**`OkHttpClientFactory` 加 `healthClient(cfg: SslConfig)` 重载**（非 mutating）：
```kotlin
fun healthClient(cfg: SslConfig): OkHttpClient =
    OkHttpClient.Builder().apply { applySsl(cfg) }
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
```
（原 `healthClient(allowInsecure)` 保留，内部改为 `healthClient(sslConfigFactory.sslConfigFor(allowInsecure))`。）

> 公共构造仍 `(TrafficTracker, TrafficLogger)` → `OpenCodeRepositoryTest` 不破。
> 切到非 mTLS profile 传 `null` → `configureClientCert(null)` 清证书，避免残留误出示。

### 2.5 三个生产调用点注入证书材料 + 图片同步

**(a) `HostProfileController.configureRepositoryForProfile`（line 550）**
```kotlin
val clientCert = if (profile.mtlsEnabled) profile.clientCertId?.let { settingsManager.loadClientCertMaterial(it) } else null
repository.configure(profile.serverUrl, profile.basicAuth?.username, password,
    allowInsecureConnections = profile.allowInsecureConnections, clientCert = clientCert)
HttpImageHolder.updateSsl(repository.currentSslConfig())
```

**(b) `HostProfileController.configureServer`（line 504）**
```kotlin
val profile = currentHostProfile()
val clientCert = if (profile.mtlsEnabled) profile.clientCertId?.let { settingsManager.loadClientCertMaterial(it) } else null
repository.configure(url, username, password, allowInsecureConnections = profile.allowInsecureConnections, clientCert = clientCert)
HttpImageHolder.updateSsl(repository.currentSslConfig())
```
> 注：`configureServer` 用 `currentHostProfile()` 读 mTLS，手动输新 URL（未存为 profile）时可能把当前 profile 的证书出示给无关主机——与现有 allowInsecure 同一对称局限；客户端证书带外公开件、风险低（glmer S9）。

**(c) `ConnectionActions.applySavedSettings`（line 35）— 冷启动**
```kotlin
val clientCert = if (currentProfile.mtlsEnabled) currentProfile.clientCertId?.let { settingsManager.loadClientCertMaterial(it) } else null
repository.configure(baseUrl = currentProfile.serverUrl, username = currentProfile.basicAuth?.username,
    password = password, allowInsecureConnections = currentProfile.allowInsecureConnections, clientCert = clientCert)
HttpImageHolder.updateSsl(repository.currentSslConfig())   // v1 漏（gpter#4）：否则冷启图片无客户端证书
```

### 2.6 `HttpImageHolder.kt` — 接收 `SslConfig` + 完整重写（含 resetTestState）

```kotlin
// 删独立 sslConfigFactory（line 102）；改持 SslConfig
@Volatile private var imageSslConfig: SslConfig = SslConfig.SystemDefault
@Volatile private var imageHttpClient: OkHttpClient = newImageHttpClient(SslConfig.SystemDefault)

@VisibleForTesting @Volatile
internal var lastUpdateSslMode: String? = null   // 取代 lastUpdateSslAllowInsecure:Boolean?

private fun newImageHttpClient(cfg: SslConfig): OkHttpClient =
    OkHttpClient.Builder().apply { applySsl(cfg) }
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

@Synchronized
fun updateSsl(cfg: SslConfig) {
    lastUpdateSslMode = when (cfg) {
        SslConfig.SystemDefault -> "SYSTEM"; is SslConfig.TrustAll -> "TRUST_ALL"; is SslConfig.MutualTLS -> "MUTUAL_TLS"
    }
    // 注：MutualTLS 每次 configure 重建为新实例（buildMutualTlsConfig 每次 new），故 mTLS host 下
    // 每次 configure 会重建 image client——可接受（configure 罕见）。no-op 守卫仅对 SYSTEM/TRUST_ALL 稳定实例有效。
    if (cfg == imageSslConfig) return
    imageSslConfig = cfg
    imageHttpClient = newImageHttpClient(cfg)
}

@VisibleForTesting @Synchronized
fun resetTestState() {   // v1 漏 → 引用已删字段编译断裂（glmer I1）
    imageSslConfig = SslConfig.SystemDefault
    imageHttpClient = newImageHttpClient(SslConfig.SystemDefault)
    lastUpdateSslMode = null
}
```

### 2.7 UI — `HostProfileEditorDialog` 回归纯 UI，材料经回调外流（gpter#6/glmer I3/I4/I7）

**对话框 state（仅内存暂存，不碰 ESP/不碰 settingsManager）**：

```kotlin
// CA 编辑意图用显式三态（v3-gpter：ByteArray? 的 null 无法区分 未改/清除/无CA → 可静默从私有CA降级平台CA）
sealed interface CaStage {
    data object Unchanged : CaStage          // 保持已存 CA（编辑既有 mTLS profile 默认）
    data class Replace(val bytes: ByteArray) : CaStage  // 导入了新 CA
    data object Clear : CaStage              // 显式移除 CA → 转平台 CA 模式
}

var mtlsEnabled by remember(initial.id) { mutableStateOf(initial.mtlsEnabled) }
var stagedP12: ByteArray? by remember(initial.id) { mutableStateOf(null) }      // 新导入的 p12 字节；null=未导入
var caStage: CaStage by remember(initial.id) { mutableStateOf(CaStage.Unchanged) }
var p12Password by remember(initial.id) { mutableStateOf("") }
var p12PasswordEdited by remember(initial.id) { mutableStateOf(false) }
var hasImportedP12 by remember(initial.id) { mutableStateOf(initial.clientCertId != null) }
var showP12Password by remember(initial.id) { mutableStateOf(false) }
val context = LocalContext.current

val p12Launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    uri?.let { stagedP12 = context.contentResolver.openInputStream(it)?.readBytes(); hasImportedP12 = true }
}
val caLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    uri?.let { caStage = CaStage.Replace(context.contentResolver.openInputStream(it)?.readBytes() ?: ByteArray(0)) }
}
// CA「移除」按钮 → caStage = CaStage.Clear；「重新导入」先 Unchanged 再选文件
```

**互斥**：mTLS `onCheckedChange = { mtlsEnabled = it; if (it) allowInsecure = false }`（glmer I7：开启 mTLS 强制重置 allowInsecure，防日后关 mTLS 时静默降级 trust-all）。insecure Switch `enabled = !mtlsEnabled`。

**`onSave` 回调扩参**（VM 负责写 ESP，原子提交；不在 Dialog 内写 ESP/不清旧证书 → Cancel 安全，gpter#5/glmer I4）：
```kotlin
onSave: (
    profile: HostProfile, basicAuthPassword: String, basicAuthEdited: Boolean,
    tunnelPassword: String, tunnelEdited: Boolean,
    // 新增：mTLS 编辑意图
    mtlsEnabled: Boolean, stagedP12: ByteArray?, caStage: CaStage,
    p12Password: String?, p12PasswordEdited: Boolean, hasImportedP12: Boolean
) -> Unit
```
VM `save` 逻辑（用 `saveClientCert` 单次原子提交）：
- 计算生效密码（v3-glmer 首导空密码修复）：
  - `stagedP12 != null`（本次导入了新 p12）：生效密码 = `if (p12PasswordEdited) p12Password else ""`（首导未编辑→当空串写，**不沿用**——此时新 id 无已存）
  - `stagedP12 == null && hasImportedP12`（编辑既有，未重导）：生效密码 = `if (p12PasswordEdited) p12Password else <已存密码>`（沿用）
- 计算生效 CA：`caStage` → `Unchanged` 用已存、`Replace(bytes)` 用 bytes、`Clear` 用 null。
- `mtlsEnabled && hasImportedP12`：用 `profile.clientCertId ?: UUID()` 为 id；**保存前用 `buildMutualTlsConfig(p12, 生效密码, 生效CA)` 试构建**，失败则报错阻止保存（防落坏材料，与运行时 runCatching 双保险）；`saveClientCert(id, p12, 生效密码, 生效CA)` 原子写；若 id 变更，`clearClientCert(oldId)`。
- `!mtlsEnabled`：`profile.clientCertId?.let { clearClientCert(it) }`，`clientCertId=null`。
- `mtlsEnabled && !hasImportedP12`：保存被拒（mTLS 开启但无 p12）。

**`onTestConnection` 回调扩参**（gpter#3/glmer I6）：加 `clientCert: ClientCertMaterial?`，由 Dialog 用 `stagedP12`（或已存 p12 经 settingsManager 读出）+ 生效密码 + 生效 CA 现构建传入；VM 调 `checkHealthFor(..., clientCert=...)`（走 `resolveProbe`，不污染当前 host）。否则 mTLS host 测试必被 stunnel 拒。

**新增字符串**：`host_mtls_title/summary/no_cert/import_p12/p12_password/ca_optional/import_ca`。

---

## 3. 安全要点 / 已知 gotcha

1. **主机名校验不禁用**：`MutualTLS` 不覆盖 `hostnameVerifier`；服务端证书 SAN 必须匹配 serverUrl host（DNS/IP）。
2. **mTLS 与 allowInsecure 互斥**：`sslConfigFor` mTLS 优先；UI 开 mTLS 强制 `allowInsecure=false`（防静默降级）。
3. **信任模型二选一**：`caBytes` 非空 → 只信私有 CA（防 WebPKI 证书 MITM，gpter#7）；空 → 仅平台 CA。
4. **证书材料应用内私有**：PKCS12/CA/密码仅 ESP（AndroidKeyStore MasterKey 加密，**设备支持时**硬件 backed——非普遍保证）+ 进程内存；不进系统证书库、无需 root。**JVM/Android 无法保证完全擦除**敏感字节（gpter重要#5），降级表述。
5. **共享证书风险**：一泄漏全盘失效，整体轮换 SOP = 重签 client.p12 + 替换 stunnel CA/证书 + 重新分发 + 撤旧 + **同步换 opencode basic auth 密码**（泄漏设备可能已读 basic auth）。
6. **HttpImageHolder/冷启动/测试连接全覆盖**：见 §2.5/2.6/2.4，无遗漏路径（REST/SSE/command/tunnel/health/image/test 全带客户端证书）。
7. **双 TLS 不适用**：隧道纯 TCP，stunnel 单次终止。
8. **DPI 表述降级**：非 443 + stunnel（无 h2 ALPN）「实测可过当前网络」，**非抗强指纹 DPI 伪装方案**（gpter次要#1）。

## 4. 验证

- `./scripts/check.sh`（编译+单测，每次改动必做，AGENTS.md 硬规则）。
- **新增单测**：`SslConfigFactoryTest`（`buildMutualTlsConfig` 两 CA 模式 / **CA bundle 多证** / **多 key entry 抛** / mTLS 优先于 allowInsecure / **`configureClientCert` 损坏 p12 runCatching 降级且 lastClientCertError 非空** / **`resolveProbe` 不读 held mTLS**）；`HostProfileTest`（新字段序列化往返 + 旧 JSON 默认值）。
- **现有测试需改**：
  - `HttpImageHolder` 钩子 `lastUpdateSslAllowInsecure:Boolean?` → `lastUpdateSslMode:String?`，`HostProfileControllerTest` 断言行：`:398/:450/:484/:734/:1063`（改断 `assertEquals("TRUST_ALL"/"SYSTEM", …)`）、`:468`（`assertNull(HttpImageHolder.lastUpdateSslMode)`）。
  - **mockk 默认参数 arity**：`clientCert` 默认 null，生产侧与 verify 侧第 5 参同 null → 理论兼容；但 `HostProfileControllerTest:392/:732` 等 4-arg `verify{ configure(...) }` **必须 `./scripts/check.sh` 实跑确认**（glmer S2），勿仅凭推理。
  - **`ConnectionViewModelFormTest` 的 `checkHealthFor` stub/verify 7 处**（`:37/:63/:88/:113/:138` 的 `coEvery{ checkHealthFor(any(),any(),any(),any()) }` + `:168/:188` 的 `coVerify`）同属上述实跑清单（v3-glmer：§v2 遗漏）。`checkHealthFor` 加默认 `clientCert=null` 尾参后理论兼容，但 MockK 默认 arity 历史有坑，必须实跑。
- **🔴 硬发布门（v3 双方共识）**：`-legacy` p12 在 **API 26 模拟器**实测可读——**JVM 单测走 JDK 自带 PKCS12 provider（读 PBES2 无碍），与设备 BC 行为不同，会给虚假信心**。必须按 `docs/emulator-debug.md`（用前 `./scripts/emulator.sh status` 确认未占用、用后 `stop`）在 API 26 模拟器跑：① 导入 client.p12+CA 连通 REST/SSE/图片；② 无证书被 stunnel 拒。**不过此门不得发布**。

## 5. 改动文件清单

| 文件 | 改动 |
|---|---|
| `data/repository/http/SslConfig.kt` | `ClientCertMaterial`/`SslConfig.MutualTLS`/`buildMutualTlsConfig`(无状态,单key,bundle)/`SslConfigFactory.configureClientCert`(runCatching+`lastClientCertError`)/**`resolveProbe`**(探测专用)/`applySsl` 新分支（纯 JDK，无 okhttp-tls） |
| `data/repository/http/OkHttpClientFactory.kt` | `healthClient(cfg: SslConfig)` 重载；原 `healthClient(allowInsecure)` 委托之 |
| `data/model/HostProfile.kt` | `mtlsEnabled`/`clientCertId` 字段 |
| `util/SettingsManager.kt` | PKCS12/CA/密码 存取（ByteArray CA、`==null` 判清除、companion key）；`loadClientCertMaterial`；**`saveClientCert` 原子批量**；**`clearAllLocalData` 加 client_cert_ 保留白名单** |
| `data/repository/OpenCodeRepository.kt` | 持有 `sslConfigFactory` 引用；`configure(+clientCert)`；`currentSslConfig()`；**`checkHealthFor(+clientCert)`** |
| `ui/controller/HostProfileController.kt` | `configureRepositoryForProfile`/`configureServer` 注入 clientCert + `HttpImageHolder.updateSsl(cfg)` |
| `ui/ConnectionActions.kt` | `applySavedSettings` 注入 clientCert + **`HttpImageHolder.updateSsl(currentSslConfig())`** |
| `ui/util/HttpImageHolder.kt` | `updateSsl(SslConfig)`/`newImageHttpClient(cfg)`/`resetTestState()` v2/`lastUpdateSslMode`；删独立 SslConfigFactory |
| `ui/settings/HostProfilesManagerScreen.kt` | Dialog mTLS 区块（state 暂存，纯 UI）；`onSave`/`onTestConnection` 扩参回调；互斥重置；SAF readBytes |
| VM（save/testConnection 处理） | 写 ESP 原子化、旧 id 清理、试构建校验、测试连接受 clientCert |
| `res/values/strings.xml`(+zh) | `host_mtls_*` |
| 测试 | `SslConfigFactoryTest`/`HostProfileTest` 新增；`HostProfileControllerTest` 钩子断言行更新 + mockk arity 实跑确认 |
| **`app/build.gradle.kts`** | **不改**（v2 不需 okhttp-tls 进 main；仅 testImplementation 保留给测试的 HeldCertificate/MockWebServer） |

---
### v1→v2 评审闭合对照
- **B1**(heldCertificate 重载不存在) / **B2**(okhttp-tls 需进 main) → 弃用 HandshakeCertificates，纯 JDK，build.gradle 零改动。
- **B3**(openssl3 p12 读不出) → §1.1 `-legacy`。
- **PKCS12 alias**(gpter#2/glmer I8) → `isKeyEntry`+`getKey is PrivateKey` 安全选取。
- **测试连接**(gpter#3/glmer I6) → `checkHealthFor(+clientCert)` + `healthClient(cfg)` 非污染重载 + `onTestConnection` 扩参。
- **冷启动图片**(gpter#4) → `applySavedSettings` 加 `HttpImageHolder.updateSsl`。
- **UI 破坏证书**(gpter#5/glmer I4) → Dialog 仅暂存，VM Save 原子写，Cancel 安全。
- **Dialog 无 settingsManager**(gpter#6/glmer I3) → 纯 UI + `onSave` 扩参回调。
- **信任模型弱化**(gpter#7) → 私有 CA 模式只信该 CA。
- **resetTestState 断裂**(glmer I1) → §2.6 补 v2。
- **no-op 守卫谬误**(glmer I2) → §2.6 如实说明 mTLS 下重建。
- **clearAllLocalData**(gpter重要#6/glmer I5) → §2.3 加白名单。
- **互斥不重置值**(glmer I7) → 开 mTLS 强制 allowInsecure=false。
- **空密码不可存**(glmer I9) → `==null` 判清除。
- **CA DER 坏**(glmer I10) → CA 走 ByteArray。
- 证书 EKU/keyUsage(gpter重要#1,2)、SAN 模板(gpter#3)、hardware-backed 表述降级(gpter重要#4)、内存清零(gpter重要#5)、DPI 降级(gpter次要#1)、轮换含 basic auth(gpter次要#3) → 全部纳入。

---
### v2→v3 评审闭合对照（第二轮 gpter+glmer）
- **[阻断] checkHealthFor 状态污染**(gpter R2#1，glmer 漏抓但成立) → §2.4 改用 `SslConfigFactory.resolveProbe(allowInsecure, clientCert)` 纯参数解析，**不读** held `mutualTlsConfig`；`sslConfigFor` 仅给 live client。
- **冷启 buildMutualTlsConfig 零防御**(glmer R2) → `configureClientCert` 包 `runCatching`，失败降级 `mutualTlsConfig=null` + 记 `lastClientCertError`（可观测），与现有 `httpCache runCatching` 防御风格一致。
- **首导空密码歧义**(glmer R2) → §2.7 save：`stagedP12!=null && !edited` 生效密码当 `""` 写（非沿用，因新 id 无已存）。
- **stagedCa:null 三义**(gpter R2#3) → `sealed CaStage{Unchanged/Replace/Clear}`，防静默私有CA→平台CA降级。
- **ESP 非原子**(gpter R2#2) → `saveClientCert(id,p12,password,ca)` 单 Editor 批量提交。
- **alias 未真正约束 KeyManager**(gpter R2#1重要) → `require(keyAliases.size==1)` 强制单 key entry（KMF 必出示该 key）。
- **§4 漏 checkHealthFor 7 处 verify**(glmer R2) → 纳入 check.sh 实跑清单。
- **CA bundle/中间 CA**(gpter R2#5) → `generateCertificates`(复数) 逐张 `setCertificateEntry`。
- **SAN IP 模板**(gpter R2#4) → §1.1 `SAN` 变量 + 域名/IP 二选一显式示例。
- **mtlsEnabled&&无材料静默降级**(glmer R2次要) → §2.7 保存被拒 + `lastClientCertError` 可观测。
- **currentSslConfig 未同步**(glmer R2次要) → 加 `@Synchronized`。
- **🔴 API26 模拟器实测 `-legacy` p12**（双方硬发布门）→ §4 硬门，JVM 单测不足证。
- import 清单(glmer R2)：repo 补 `SslConfig/ClientCertMaterial/buildMutualTlsConfig`；HttpImageHolder 补 `SslConfig` 删 `SslConfigFactory`。

> gpter R2 其余「源码未闭合」类阻断（ClientCertMaterial/configure/HttpImageHolder/clearAllLocalData 不存在）系把方案当已实现审 → 归 §5 待实施项，已剥离，非方案缺陷。两方共识：**核心架构正确自洽、无编译阻断**；v3 为实施版。
