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
    val tunnelPasswordId: String? = null,
    /**
     * User-maintained server-group key.
     *
     * Supported values are exactly one of the four named shared slots
     * `"A"` / `"B"` / `"C"` / `"D"`, or this profile's own [id] for
     * "not grouped". Profiles with the same named slot intentionally share
     * chat-history cache, draft/model/agent preferences, recent workdirs and
     * sweep epoch. A profile whose value is blank or any legacy non-slot value
     * is interpreted by the editor as "not grouped"; saving without actively
     * changing the selector preserves that legacy value for soft migration.
     *
     * **Nonblank invariant**: this field is `""` ONLY as a deserialization
     * fallback for legacy JSON. The store normalizes blank values to [id] at
     * the read/write boundary; no batch rewrite or schema migration is used.
     */
    @SerialName("serverGroupFp")
    val serverGroupFp: String = "",
    /**
     * §2.2: 开启后所有客户端 TLS 握手出示 [clientCertId] 对应的 PKCS12。
     *
     * §tofu R2: 与原 `allowInsecureConnections`（已删——TOFU 替代 trust-all 降级）
     * 不再互斥——self-signed / unknown-issuer 证书现在由首次连接时的 TOFU 信任
     * 对话框处理，无需 profile 字段。本字段仅控制是否出示客户端证书（mTLS）。
     *
     * 向后兼容：旧 JSON 无此字段 → false（默认值）。`ignoreUnknownKeys=true` 让
     * 旧 JSON 里的 `allowInsecureConnections` 字段在反序列化时静默丢弃。
     */
    @SerialName("mtlsEnabled")
    val mtlsEnabled: Boolean = false,
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
            // New profiles default to "not grouped" (serverGroupFp == id).
            val id = UUID.randomUUID().toString()
            return HostProfile(
                id = id,
                name = "Localhost",
                serverUrl = serverUrl,
                basicAuth = basicAuth,
                serverGroupFp = id,
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
        // Imported profiles start as "not grouped" (serverGroupFp == id).
        val id = UUID.randomUUID().toString()
        return HostProfile(
            id = id,
            name = name,
            serverUrl = url,
            serverGroupFp = id
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
            // R-20 Phase 0: export does NOT include serverGroupFp (plan §1:
            // "import/export 默认不导出内部 group，导入新建独立组"). Importing the
            // same payload twice creates two independent groups.
            return HostProfileExportPayload(
                name = profile.displayName,
                serverUrl = profile.serverUrl
            )
        }
    }
}

/**
 * R-20 Phase 0: defensive nonblank guard. Returns a copy with
 * `serverGroupFp` set to [id] if it was blank, otherwise `this`. Used at the
 * HostProfileStore write boundary (save / saveProfiles) so a blank value can
 * never reach EncryptedSharedPreferences. Internal to the data layer — not
 * for external callers (new profiles should set serverGroupFp at construction
 * time via [HostProfile.defaultDirect] / [HostProfileImportPayload.makeProfile]
 * / [HostProfileStore.duplicate]).
 */
internal fun HostProfile.normalizeGroupFp(): HostProfile =
    if (serverGroupFp.isBlank()) copy(serverGroupFp = id) else this
