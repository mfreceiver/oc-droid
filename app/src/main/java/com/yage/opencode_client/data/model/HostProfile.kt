package com.yage.opencode_client.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

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
data class SshTunnelConfig(
    val host: String = "",
    val port: Int = 8006,
    val username: String = "opencode",
    val remotePort: Int = 19001
) {
    val isValid: Boolean
        get() = validationError == null

    val validationError: String?
        get() = when {
            host.isBlank() -> "SSH gateway host is required"
            username.isBlank() -> "SSH username is required"
            port <= 0 -> "SSH port must be positive"
            remotePort <= 0 -> "Assigned remote port must be positive"
            else -> null
        }

    val summary: String
        get() = "${host.trim()}:$port -> :$remotePort"
}

@Serializable
data class HostProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val transport: HostTransport,
    @SerialName("serverURL")
    val serverUrl: String,
    val basicAuth: BasicAuthConfig? = null,
    val ssh: SshTunnelConfig? = null,
    val lastUsedAt: Long? = null
) {
    val displayName: String
        get() = name.trim().ifEmpty { "Untitled" }

    val connectionSummary: String
        get() = when (transport) {
            HostTransport.DIRECT -> serverUrl.trim()
            HostTransport.SSH_TUNNEL -> ssh?.summary ?: "SSH Tunnel"
        }

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
                transport = HostTransport.DIRECT,
                serverUrl = serverUrl,
                basicAuth = basicAuth,
                ssh = null,
                lastUsedAt = System.currentTimeMillis()
            )
        }
    }
}

@Serializable
data class HostProfileImportPayload(
    val version: Int? = null,
    val name: String,
    val transport: HostTransport,
    @SerialName("serverURL")
    val serverUrl: String? = null,
    val ssh: SshTunnelConfig? = null
) {
    fun makeProfile(): HostProfile {
        return when (transport) {
            HostTransport.DIRECT -> {
                val url = serverUrl?.trim().orEmpty()
                require(url.isNotEmpty()) { "Direct profile requires serverURL" }
                HostProfile(name = name, transport = HostTransport.DIRECT, serverUrl = url)
            }
            HostTransport.SSH_TUNNEL -> {
                val config = requireNotNull(ssh) { "SSH Tunnel profile requires ssh settings" }
                config.validationError?.let { message -> error(message) }
                HostProfile(
                    name = name,
                    transport = HostTransport.SSH_TUNNEL,
                    serverUrl = "http://127.0.0.1:4096",
                    ssh = config
                )
            }
        }
    }
}

@Serializable
data class HostProfileExportPayload(
    val version: Int = 1,
    val name: String,
    val transport: HostTransport,
    @SerialName("serverURL")
    val serverUrl: String? = null,
    val ssh: HostProfileExportSsh? = null
) {
    companion object {
        fun from(profile: HostProfile): HostProfileExportPayload {
            return when (profile.transport) {
                HostTransport.DIRECT -> HostProfileExportPayload(
                    name = profile.displayName,
                    transport = HostTransport.DIRECT,
                    serverUrl = profile.serverUrl
                )
                HostTransport.SSH_TUNNEL -> {
                    val ssh = requireNotNull(profile.ssh) { "SSH profile is missing ssh settings" }
                    HostProfileExportPayload(
                        name = profile.displayName,
                        transport = HostTransport.SSH_TUNNEL,
                        ssh = HostProfileExportSsh(
                            host = ssh.host,
                            port = ssh.port,
                            username = ssh.username,
                            remotePort = ssh.remotePort
                        )
                    )
                }
            }
        }
    }
}

@Serializable
data class HostProfileExportSsh(
    val host: String,
    val port: Int,
    val username: String,
    val remotePort: Int
)
