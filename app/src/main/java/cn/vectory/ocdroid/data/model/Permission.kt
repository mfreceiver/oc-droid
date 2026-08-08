package cn.vectory.ocdroid.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PermissionRequest(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val permission: String? = null,
    val patterns: List<String>? = null,
    val metadata: Metadata? = null,
    val always: List<String>? = null,
    val tool: ToolRef? = null,
    val directory: String? = null,
    /**
     * Slimapi HMAC the sidecar validates on the permission response POST
     * (~1h TTL). Present only when the permission arrived via slim SSE;
     * legacy/standard paths leave this null. F4a: the slim respond path
     * was collapsed into [InteractionRepository.respondPermission]; the
     * routeToken is now a client-side provenance signal only (upstream
     * spec §7:231 deleted it from the wire).
     */
    @SerialName("routeToken") val routeToken: String? = null,
) {
    @Serializable
    data class Metadata(
        val filepath: String? = null,
        @SerialName("parentDir") val parentDir: String? = null
    )

    @Serializable
    data class ToolRef(
        @SerialName("messageID") val messageId: String? = null,
        @SerialName("callID") val callId: String? = null
    )
}

enum class PermissionResponse(val value: String) {
    ONCE("once"),
    ALWAYS("always"),
    REJECT("reject")
}
