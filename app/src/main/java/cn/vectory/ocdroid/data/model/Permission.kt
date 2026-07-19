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
     * (~1h TTL). Present only when the permission arrived via slim SSE /
     * `/slimapi/permissions`; legacy paths leave this null. Phase 3 respond
     * path returns it on the slim write endpoints.
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
