package cn.vectory.ocdroid.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Custom serializer that handles server-side todo JSON variants:
 * - `status` (string): "completed" / "pending" / "cancelled"
 * - `completed` (boolean): true / false
 * - `isCompleted` (boolean): true / false
 *
 * Mirrors the iOS TodoItem.init(from: Decoder) compatibility layer.
 */
object TodoItemSerializer : KSerializer<TodoItem> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("TodoItem") {
        element<String>("content")
        element<String>("status")
        element<String>("priority")
        element<String>("id")
    }

    override fun serialize(encoder: Encoder, value: TodoItem) {
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, value.content)
            encodeStringElement(descriptor, 1, value.status)
            encodeStringElement(descriptor, 2, value.priority)
            encodeStringElement(descriptor, 3, value.id)
        }
    }

    override fun deserialize(decoder: Decoder): TodoItem {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject

        val content = json["content"]?.jsonPrimitive?.content?.trim()?.ifEmpty { null }
            ?: "Untitled todo"

        // Resolve status: prefer `status` string, then `completed` boolean, then `isCompleted` boolean
        val status: String = run {
            val s = json["status"]?.jsonPrimitive?.content?.trim()
            if (!s.isNullOrEmpty()) return@run s
            if (json["completed"]?.jsonPrimitive?.booleanOrNull == true) return@run "completed"
            if (json["isCompleted"]?.jsonPrimitive?.booleanOrNull == true) return@run "completed"
            if (json["completed"]?.jsonPrimitive?.booleanOrNull == false) return@run "pending"
            if (json["isCompleted"]?.jsonPrimitive?.booleanOrNull == false) return@run "pending"
            "pending"
        }

        val priority = json["priority"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: "medium"

        val id = json["id"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: java.util.UUID.randomUUID().toString()

        return TodoItem(content, status, priority, id)
    }
}

@Serializable(with = TodoItemSerializer::class)
data class TodoItem(
    val content: String,
    val status: String,
    val priority: String,
    val id: String
) {
    val isCompleted: Boolean
        get() = status == "completed" || status == "cancelled"
}
