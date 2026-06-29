package com.yage.opencode_client.data.model

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
     * R-01: per-host "接受不安全连接"开关。默认 false（仅信任系统证书），
     * 设为 true 时该 host 的 REST/SSE/health/tunnel client 全部降级为 trust-all。
     * 这是把原先全局硬编码的 trustAll 收紧为按 host 的显式、可审计的开关。
     */
    @SerialName("allowInsecureConnections")
    val allowInsecureConnections: Boolean = false,
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
            return HostProfile(
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
    val serverUrl: String? = null,
    @SerialName("allowInsecureConnections")
    val allowInsecureConnections: Boolean = false
) {
    fun makeProfile(): HostProfile {
        val url = serverUrl?.trim().orEmpty()
        require(url.isNotEmpty()) {
            "Host profile requires serverURL (legacy SSH-only profiles are no longer supported)"
        }
        return HostProfile(
            name = name,
            serverUrl = url,
            allowInsecureConnections = allowInsecureConnections
        )
    }
}

@Serializable
data class HostProfileExportPayload(
    val version: Int = 1,
    val name: String,
    @SerialName("serverURL")
    val serverUrl: String,
    @SerialName("allowInsecureConnections")
    val allowInsecureConnections: Boolean = false
) {
    companion object {
        fun from(profile: HostProfile): HostProfileExportPayload {
            return HostProfileExportPayload(
                name = profile.displayName,
                serverUrl = profile.serverUrl,
                allowInsecureConnections = profile.allowInsecureConnections
            )
        }
    }
}
