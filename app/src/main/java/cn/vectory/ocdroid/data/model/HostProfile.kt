package cn.vectory.ocdroid.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Transport enum retained for backwards-compatible JSON deserialization of
 * legacy import/export payloads. New profiles no longer carry a transport
 * field — only DIRECT-style server URLs are supported.
 */
@Serializable
enum class HostTransport {
    @SerialName("direct")
    DIRECT,

    @SerialName("sshTunnel")
    SSH_TUNNEL
}

@Serializable
data class BasicAuthConfig(
    val username: String,
    val passwordId: String
)

@Serializable
data class HostProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    @SerialName("serverURL")
    val serverUrl: String,
    val basicAuth: BasicAuthConfig? = null,
    /**
     * §2.2 / R8 slim-mode foundation: 是否启用 mTLS（stunnel）。
     *
     * §tofu R2: 与原 `allowInsecureConnections`（已删——TOFU 替代 trust-all 降级）
     * 不再互斥——self-signed / unknown-issuer 证书现在由首次连接时的 TOFU 信任
     * 对话框处理，无需 profile 字段。本字段仅控制是否出示客户端证书（mTLS）。
     *
     * R8 model: 这是「4 服务器配置 = 2 正交布尔」中的 `mtls` 维度——与 [slim]
     * 正交组合形成四连接形态：直连opencode(false,false) / stunnel→opencode(true,false)
     * / 直连slim(false,true) / stunnel→slim(true,true)。
     *
     * 向后兼容：旧 JSON 无此字段 → false（默认值）。`ignoreUnknownKeys=true` 让
     * 旧 JSON 里的 `allowInsecureConnections` 字段在反序列化时静默丢弃。
     */
    @SerialName("mtlsEnabled")
    val mtlsEnabled: Boolean = false,
    /**
     * R8 slim-mode foundation: 是否启用省流模式（指向 oc-slimapi sidecar）。
     *
     * 这是「4 服务器配置 = 2 正交布尔」中的 `slim` 维度——与 [mtlsEnabled] 正交。
     * base URL 仍是用户配置的 host:port（直连 sidecar 或经 stunnel）；本字段描述
     * 该 server 的语义：true=sidecar 入口（所有 `/slimapi/` 下路径有效，须带
     * `X-Slimapi-Version` 版本头，health 探针走 `/slimapi/health`）；false=legacy
     * opencode 直连入口（行为完全不变）。
     *
     * **写域边界（R8 地基）**：本字段是数据模型 + 网络层 + repository 层的可切换
     * 属性。`HostConfig.slim` 透传给 `SlimapiVersionInterceptor`（注入版本头）、
     * `OpenCodeRepository.checkHealth/checkHealthFor/captureServerCert`（路由到
     * `/slimapi/health`）、`ServerCompatProfile`（M2 自检）。UI 编辑页（host profile
     * editor）由 designer 后续接入；本字段默认 false 保持 legacy 行为不变。
     *
     * 向后兼容：旧 JSON 无此字段 → false（kotlinx.serialization 默认值 +
     * `HostProfileStore` 的 `ignoreUnknownKeys=true`，缺字段时自动回填默认）。
     *
     * 注：避免在 KDoc 中写 `/slimapi/` + 紧跟 `**`（Kotlin lexer 把 `/**` 解析
     * 为嵌套 KDoc 起始 → 后续 `*/` 提前闭合外层 → "Unclosed comment"）。下文用
     * `/slimapi/<wildcard>` 或 `/slimapi/` 单独写。
     */
    @SerialName("slim")
    val slim: Boolean = false,
    /**
     * P1-3: per-server trust-all — trusts ALL server certificates for this
     * host (self-signed, forged, intercepted). NO MITM protection.
     *
     * P1-4: mTLS takes priority — when an mTLS client cert is presented,
     * trust-all is IGNORED (see [SslConfigFactory.sslConfigFor] and
     * [cn.vectory.ocdroid.data.repository.OpenCodeRepository.resolveCandidateSsl]:
     * both route `MutualTLS` first and fall back to `TrustAll` only when
     * `clientCert == null`). So the practical combination is "mTLS when
     * configured, else trust-all, else system CA" — NOT "trust-all bypasses
     * mTLS server verification".
     *
     * NOT exported with [HostProfileExportPayload] / [HostProfileImportPayload]
     * (same as [clientCertId] / [mtlsEnabled]).
     */
    @SerialName("trustAll")
    val trustAll: Boolean = false,
    /**
     * §2.2: 客户端 PKCS12（+密码+可选 CA）在 EncryptedSharedPreferences 的
     * key 后缀（`client_cert_p12_<id>` / `client_cert_pw_<id>` /
     * `client_cert_ca_<id>`）。null ⇒ 无材料。
     *
     * **绝不随导出 payload 离开设备**（[HostProfileExportPayload.from] /
     * [HostProfileImportPayload] 均不复制本字段 + [mtlsEnabled]）。
     * 向后兼容：旧 JSON 无此字段 → null（默认值）。
     */
    @SerialName("clientCertId")
    val clientCertId: String? = null,
    val lastUsedAt: Long? = null
) {
    val displayName: String
        get() = name.trim().ifEmpty { "Untitled" }

    val connectionSummary: String
        get() = serverUrl.trim()

    companion object {
        fun defaultDirect(
            serverUrl: String = "http://localhost:4096",
            username: String? = null,
            passwordId: String? = null
        ): HostProfile {
            val basicAuth = if (!username.isNullOrBlank() && !passwordId.isNullOrBlank()) {
                BasicAuthConfig(username = username, passwordId = passwordId)
            } else {
                null
            }
            // §需求12: profiles are fully independent — fp == id (no grouping).
            val id = UUID.randomUUID().toString()
            return HostProfile(
                id = id,
                name = "Localhost",
                serverUrl = serverUrl,
                basicAuth = basicAuth,
                lastUsedAt = System.currentTimeMillis()
            )
        }
    }
}

@Serializable
data class HostProfileImportPayload(
    val version: Int? = null,
    val name: String,
    @SerialName("serverURL")
    val serverUrl: String? = null
    // §tofu R2: legacy `allowInsecureConnections` dropped — TOFU replaces the
    // trust-all downgrade. ignoreUnknownKeys lets old imports still parse.
) {
    fun makeProfile(): HostProfile {
        val url = serverUrl?.trim().orEmpty()
        require(url.isNotEmpty()) {
            "Host profile requires serverURL (legacy SSH-only profiles are no longer supported)"
        }
        // §需求12: imported profiles are independent (fp == id, no grouping).
        val id = UUID.randomUUID().toString()
        return HostProfile(
            id = id,
            name = name,
            serverUrl = url
        )
    }
}

@Serializable
data class HostProfileExportPayload(
    val version: Int = 1,
    val name: String,
    @SerialName("serverURL")
    val serverUrl: String
    // §tofu R2: legacy `allowInsecureConnections` dropped (see HostProfileImportPayload).
) {
    companion object {
        fun from(profile: HostProfile): HostProfileExportPayload {
            // §需求12: export never carried the grouping key (plan §1: "import/
            // export 默认不导出内部 group"). The field is now gone entirely.
            return HostProfileExportPayload(
                name = profile.displayName,
                serverUrl = profile.serverUrl
            )
        }
    }
}
