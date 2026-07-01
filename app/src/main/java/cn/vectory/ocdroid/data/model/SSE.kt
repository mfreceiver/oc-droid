package cn.vectory.ocdroid.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement

@Serializable
data class SSEEvent(
    val directory: String? = null,
    val payload: SSEPayload
)

@Serializable
data class SSEPayload(
    val type: String,
    val properties: JsonObject? = null
) {
    fun getString(key: String): String? {
        return (properties?.get(key) as? JsonPrimitive)?.content
    }

    fun getJsonObject(key: String): JsonObject? {
        return properties?.get(key) as? JsonObject
    }

    inline fun <reified T> getAs(key: String, parse: (JsonObject) -> T?): T? {
        return getJsonObject(key)?.let { parse(it) }
    }
}
