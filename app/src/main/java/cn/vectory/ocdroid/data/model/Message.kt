package cn.vectory.ocdroid.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class Message(
    val id: String,
    @SerialName("sessionID") val sessionId: String? = null,
    val role: String,
    @SerialName("parentID") val parentId: String? = null,
    @SerialName("providerID") val providerId: String? = null,
    @SerialName("modelID") val modelId: String? = null,
    val model: ModelInfo? = null,
    val agent: String? = null,
    val error: MessageError? = null,
    val time: TimeInfo? = null,
    val finish: String? = null,
    val tokens: TokenInfo? = null,
    val cost: Double? = null
) {
    @Serializable
    data class ModelInfo(
        @SerialName("providerID") val providerId: String,
        @SerialName("modelID") val modelId: String
    )

    @Serializable
    data class TokenInfo(
        val total: Int? = null,
        val input: Int? = null,
        val output: Int? = null,
        val reasoning: Int? = null,
        val cache: CacheInfo? = null
    ) {
        @Serializable
        data class CacheInfo(
            val read: Int? = null,
            val write: Int? = null
        )
    }

    @Serializable
    data class TimeInfo(
        val created: Long? = null,
        val completed: Long? = null
    )

    @Serializable
    data class MessageError(
        val name: String? = null,
        val data: JsonObject? = null
    ) {
        val message: String?
            get() = data?.let { obj ->
                (obj["message"] as? JsonPrimitive)?.content
                    ?: (obj["error"] as? JsonPrimitive)?.content
            }
    }

    val isUser: Boolean get() = role.equals("user", ignoreCase = true)
    val isAssistant: Boolean get() = role.equals("assistant", ignoreCase = true)
    val isSystem: Boolean get() = role.equals("system", ignoreCase = true)

    /**
     * True for any role that should be hidden from the chat transcript
     * (system / tool / environment / etc.) — i.e. anything that is neither
     * a user nor an assistant message.
     */
    val isToolRole: Boolean get() = !isUser && !isAssistant

    val resolvedModel: ModelInfo?
        get() = model ?: (if (providerId != null && modelId != null) {
            ModelInfo(providerId, modelId)
        } else null)
}

/**
 * True for an assistant message that has no renderable content: not a user
 * message, has no parts in [partsForMessage], and is not currently streaming
 * ([isStreaming] is false). Such messages render as a blank timestamp-only
 * bubble and are filtered at the render layer. Streaming placeholders
 * (a `Part` with `text=null` introduced by v0.2.5 streaming) are protected:
 * the placeholder Part is present in [partsForMessage] (so it is non-empty),
 * and/or [isStreaming] is true — either way this returns false.
 *
 * Pure (no Compose / state dependency) so it is unit-testable in isolation.
 */
fun isRenderableEmptyMessage(
    isUser: Boolean,
    partsForMessage: List<Part>,
    isStreaming: Boolean
): Boolean = !isUser && partsForMessage.isEmpty() && !isStreaming

/**
 * True when every part in [partsForMessage] carries no renderable content.
 *
 * Covers the persistent empty-message case that [isRenderableEmptyMessage]
 * misses: after a session goes idle and the REST reload replaces the streaming
 * overlay (which had carried the live text), the assistant message is left
 * with server-side parts that are all blank — a placeholder text Part with
 * `text=null`, a tool Part whose state never resolved, etc. Such a message
 * has non-empty `parts` (so [isRenderableEmptyMessage] returns false) yet
 * renders as a blank padded bubble. This helper inspects each part's content:
 *
 *   - text / reasoning → blank [Part.text]
 *   - tool             → blank tool name AND output AND inputSummary
 *   - patch            → no navigable file paths AND blank output
 *   - file             → blank filename
 *   - any other type   → treated as empty (defensive)
 *
 * An empty list is trivially effectively-empty. Pure (no Compose / state
 * dependency) so it is unit-testable in isolation. The streaming in-flight
 * case (parts empty + session busy) is NOT routed through this helper —
 * ChatMessageList renders an inline "生成中…" placeholder for it instead of
 * filtering, so the user sees ongoing activity rather than a disappeared row.
 */
fun isEffectivelyRenderableEmpty(partsForMessage: List<Part>): Boolean {
    if (partsForMessage.isEmpty()) return true
    return partsForMessage.all { part ->
        when {
            part.isText || part.isReasoning -> part.text.isNullOrBlank()
            part.isTool -> part.tool.isNullOrBlank() &&
                part.toolOutput.isNullOrBlank() &&
                part.toolInputSummary.isNullOrBlank()
            part.isPatch -> part.filePathsForNavigationFiltered.isEmpty() &&
                part.toolOutput.isNullOrBlank()
            part.isFile -> part.filename.isNullOrBlank()
            else -> true
        }
    }
}

@Serializable
data class MessageWithParts(
    val info: Message,
    val parts: List<Part> = emptyList()
)

data class ComposerImageAttachment(
    val id: String,
    val filename: String,
    val mime: String,
    val dataUrl: String,
    val thumbnailData: ByteArray,
    val byteSize: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ComposerImageAttachment) return false
        return id == other.id && filename == other.filename && mime == other.mime &&
            dataUrl == other.dataUrl && thumbnailData.contentEquals(other.thumbnailData) && byteSize == other.byteSize
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + filename.hashCode()
        result = 31 * result + mime.hashCode()
        result = 31 * result + dataUrl.hashCode()
        result = 31 * result + thumbnailData.contentHashCode()
        result = 31 * result + byteSize
        return result
    }
}

@Serializable
data class Part(
    val id: String,
    @SerialName("messageID") val messageId: String? = null,
    @SerialName("sessionID") val sessionId: String? = null,
    val type: String,
    val text: String? = null,
    val tool: String? = null,
    @SerialName("callID") val callId: String? = null,
    val state: PartState? = null,
    val metadata: PartMetadata? = null,
    @Serializable(with = PartFilesSerializer::class) val files: List<FileChange>? = null,
    val mime: String? = null,
    val filename: String? = null,
    val url: String? = null,
    val source: String? = null
) {
    val isText: Boolean get() = type == "text"
    val isReasoning: Boolean get() = type == "reasoning"
    val isTool: Boolean get() = type == "tool"
    val isPatch: Boolean get() = type == "patch"
    val isFile: Boolean get() = type == "file"
    val isImageAttachment: Boolean get() = isFile && mime?.startsWith("image/") == true
    val isStepStart: Boolean get() = type == "step-start"
    val isStepFinish: Boolean get() = type == "step-finish"

    val stateDisplay: String? get() = state?.displayString
    val toolReason: String? get() = state?.title
    val toolInputSummary: String? get() = state?.inputSummary
    val toolOutput: String? get() = state?.output

    /**
     * For `task` tool parts, the spawned sub-agent session ID is stored under
     * the state's metadata `sessionId` key (opencode-web / opencode server use
     * camelCase `sessionId`). Historically this looked up `sessionID`, which
     * never matched → the sub-agent card was never clickable. Try the canonical
     * key first, then the legacy capitalization as a fallback.
     */
    val taskSubSessionId: String?
        get() = state?.metadataString("sessionId") ?: state?.metadataString("sessionID")

    /**
     * True for `task` tool parts: these spawn a sub-agent conversation that can
     * be opened in-place via [taskSubSessionId].
     */
    val isSubAgentTask: Boolean get() = isTool && tool?.lowercase() == "task"

    val toolTodos: List<TodoItem>
        get() {
            if (!metadata?.todos.isNullOrEmpty()) return metadata?.todos ?: emptyList()
            if (!state?.todos.isNullOrEmpty()) return state?.todos ?: emptyList()
            return emptyList()
        }

    val filePathsForNavigation: List<String>
        get() {
            val result = mutableListOf<String>()
            files?.forEach { result.add(it.path.normalizePath()) }
            metadata?.path?.let { result.add(it.normalizePath()) }
            state?.pathFromInput?.let { 
                val normalized = it.normalizePath()
                if (normalized !in result) result.add(normalized)
            }
            return result
        }

    /**
     * §file-detect: paths that look like files (vs. directories). The previous
     * `lastSegment.contains(".")` heuristic misclassified a directory named
     * `a.b` as a file (it has a dot) and was correct only by coincidence for
     * most cases. The improved rule treats a path as a file when:
     *  - the last segment (after the final "/", trailing "/" trimmed) is
     *    non-empty,
     *  - the path does NOT end with "/" (excludes explicit directory entries
     *    like "src/" or "a.b/"),
     *  - the last segment contains a "." (an extension separator).
     * Tradeoff: extensionless files (e.g. "README", "Makefile", "Dockerfile")
     * are dropped. This is acceptable in the patch scenario where the
     * overwhelming majority of files carry extensions; the navigation list is a
     * convenience affordance, not an authoritative file index.
     */
    val filePathsForNavigationFiltered: List<String>
        get() = filePathsForNavigation.filter { path ->
            val lastSegment = path.substringAfterLast("/").trimEnd('/')
            lastSegment.isNotEmpty() && !path.endsWith("/") && lastSegment.contains(".")
        }

    @Serializable
    data class FileChange(
        val path: String,
        val additions: Int? = null,
        val deletions: Int? = null,
        val status: String? = null
    )
}

/** Handles API returning files as either ["path1","path2"] or [{path,additions,...}]. */
private object PartFilesSerializer : KSerializer<List<Part.FileChange>?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("PartFiles", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: List<Part.FileChange>?) {
        encoder.encodeSerializableValue(JsonArray.serializer(), kotlinx.serialization.json.buildJsonArray {
            value?.forEach { add(kotlinx.serialization.json.JsonObject(mapOf("path" to kotlinx.serialization.json.JsonPrimitive(it.path)))) }
        })
    }

    override fun deserialize(decoder: Decoder): List<Part.FileChange>? {
        val element = decoder.decodeSerializableValue(JsonElement.serializer())
        if (element is JsonNull) return null
        val arr = element as? JsonArray ?: return null
        return arr.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> Part.FileChange(path = item.content)
                is JsonObject -> {
                    val path = (item["path"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    Part.FileChange(
                        path = path,
                        additions = (item["additions"] as? JsonPrimitive)?.content?.toIntOrNull(),
                        deletions = (item["deletions"] as? JsonPrimitive)?.content?.toIntOrNull(),
                        status = (item["status"] as? JsonPrimitive)?.content
                    )
                }
                else -> null
            }
        }
    }
}

@Serializable(with = PartStateSerializer::class)
data class PartState(
    val displayString: String,
    val title: String? = null,
    val inputSummary: String? = null,
    val output: String? = null,
    val pathFromInput: String? = null,
    val todos: List<TodoItem>? = null,
    /**
     * Raw metadata object from the tool state. Used for tools like `task` whose
     * metadata carries a `sessionID` pointing at the spawned sub-agent session,
     * plus a human-readable `description`.
     */
    val metadata: JsonObject? = null
) {
    /** Best-effort lookup of a string metadata field, accepting both casing variants. */
    fun metadataString(key: String): String? {
        val m = metadata ?: return null
        val v = m[key] as? JsonPrimitive ?: m[key.lowercase()] as? JsonPrimitive
            ?: m[key.uppercase()] as? JsonPrimitive
        return v?.content?.takeIf { it.isNotEmpty() }
    }
}

object PartStateSerializer : kotlinx.serialization.KSerializer<PartState> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("PartState", kotlinx.serialization.descriptors.PrimitiveKind.STRING)

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: PartState) {
        encoder.encodeString(value.displayString)
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): PartState {
        val input = decoder.decodeSerializableValue(JsonElement.serializer())
        return parsePartState(input)
    }

    private fun parsePartState(element: JsonElement): PartState {
        return when (element) {
            is JsonPrimitive -> PartState(element.content)
            is JsonObject -> {
                val status = (element["status"] as? JsonPrimitive)?.content
                    ?: (element["title"] as? JsonPrimitive)?.content
                    ?: "…"

                var title: String? = (element["title"] as? JsonPrimitive)?.content
                var output: String? = (element["output"] as? JsonPrimitive)?.content

                // Strip LSP `diagnostics`: opencode attaches the full LSP
                // diagnostic map to state.metadata.diagnostics — it can be
                // multi-MB per edit/write part and is never read here (only
                // sessionId / todos / path / output / description are used).
                // Dropping it bounds the deserialized object + sustained heap.
                var metadata = (element["metadata"] as? JsonObject)?.let { m ->
                    if ("diagnostics" !in m) m
                    else buildJsonObject { m.forEach { (k, v) -> if (k != "diagnostics") put(k, v) } }
                }
                if (metadata != null) {
                    if (output == null) output = (metadata["output"] as? JsonPrimitive)?.content
                    if (title == null) title = (metadata["description"] as? JsonPrimitive)?.content
                }

                var inputSummary: String? = null
                var pathFromInput: String? = null
                var todos: List<TodoItem>? = null

                val inputObj = element["input"]
                if (inputObj is JsonPrimitive) {
                    inputSummary = inputObj.content
                } else if (inputObj is JsonObject) {
                    inputSummary = (inputObj["command"] as? JsonPrimitive)?.content
                        ?: (inputObj["path"] as? JsonPrimitive)?.content

                    val todosObj = inputObj["todos"]
                    if (todosObj is JsonArray) {
                        todos = parseTodos(todosObj)
                    }

                    var pathVal = (inputObj["path"] as? JsonPrimitive)?.content
                        ?: (inputObj["file_path"] as? JsonPrimitive)?.content
                        ?: (inputObj["filePath"] as? JsonPrimitive)?.content

                    if (pathVal == null) {
                        val patchText = (inputObj["patchText"] as? JsonPrimitive)?.content
                        if (patchText != null) {
                            for (prefix in listOf("*** Add File: ", "*** Update File: ")) {
                                val idx = patchText.indexOf(prefix)
                                if (idx >= 0) {
                                    val rest = patchText.substring(idx + prefix.length)
                                    pathVal = rest.split("\n").firstOrNull()?.trim()
                                    break
                                }
                            }
                        }
                    }
                    pathFromInput = pathVal
                }

                if (todos == null && metadata != null) {
                    val todosObj = metadata["todos"]
                    if (todosObj is JsonArray) {
                        todos = parseTodos(todosObj)
                    }
                }

                // §problem-7 (subagent toolbar): the `task` tool carries its
                // agent name + description in `state.input.{agent,description,
                // prompt}` (NOT in state.metadata), so without surfacing them
                // here SubAgentCard has no @agent name and falls back to the
                // flat accentText. This layer (PartState) only sees the state
                // JSON — there is no toolName to gate on — so the injection is
                // unconditional: it copies input.agent/description/prompt into
                // metadata. This is a no-op for every other tool whose input
                // lacks those keys (edit/write/bash/etc. carry command/path),
                // so the branch is dead weight for them and costs only a cheap
                // type check. Pending-state input is a JsonPrimitive (string),
                // not an object, so it is skipped here — the subagent card then
                // shows the description text and re-acquires the @name once the
                // task transitions to running (input becomes an object).
                val inputAsObj = element["input"] as? JsonObject
                if (inputAsObj != null) {
                    val agent = (inputAsObj["subagent_type"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                        ?: (inputAsObj["agent"] as? JsonPrimitive)?.content
                    val desc = (inputAsObj["description"] as? JsonPrimitive)?.content
                        ?: (inputAsObj["prompt"] as? JsonPrimitive)?.content
                    // Server-provided `metadata` wins over `input` for both keys
                    // (§problem-7 review follow-up). The check mirrors exactly
                    // how [PartState.metadataString] resolves casing (exact key,
                    // all-lowercase, all-UPPERCASE), so an existing metadata
                    // entry that metadataString would actually read is never
                    // duplicated + shadowed by a freshly-injected lowercase
                    // copy. `agent` is task-specific and effectively never
                    // collides, but the guard is symmetrical and zero-cost.
                    val baseHasAgent = metadata.containsResolving("agent")
                    val baseHasDesc = metadata.containsResolving("description")
                    val addAgent = agent != null && !baseHasAgent
                    val addDesc = !desc.isNullOrEmpty() && !baseHasDesc
                    if (addAgent || addDesc) {
                        metadata = (metadata ?: buildJsonObject {}).let { base ->
                            buildJsonObject {
                                base.forEach { (k, v) -> put(k, v) }
                                if (addAgent) put("agent", JsonPrimitive(agent))
                                if (addDesc) put("description", JsonPrimitive(desc))
                            }
                        }
                    }
                }

                PartState(
                    displayString = status,
                    title = title,
                    inputSummary = inputSummary,
                    output = output,
                    pathFromInput = pathFromInput,
                    todos = todos,
                    metadata = metadata
                )
            }
            else -> PartState("…")
        }
    }

    private fun parseTodos(array: JsonArray): List<TodoItem> {
        return array.mapNotNull { item ->
            if (item is JsonObject) {
                try {
                    val content = (item["content"] as? JsonPrimitive)?.content?.trim() ?: "Untitled todo"
                    val status = (item["status"] as? JsonPrimitive)?.content
                        ?: if ((item["completed"] as? JsonPrimitive)?.content == "true") "completed"
                        else if ((item["isCompleted"] as? JsonPrimitive)?.content == "true") "completed"
                        else "pending"
                    val priority = (item["priority"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() } ?: "medium"
                    val id = (item["id"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() } ?: java.util.UUID.randomUUID().toString()
                    TodoItem(content, status, priority, id)
                } catch (e: Exception) { null }
            } else null
        }
    }

    /**
     * Returns true when [metadata] contains [key] under any casing that
     * [PartState.metadataString] would resolve (exact, all-lowercase, or
     * all-UPPERCASE). Used by the task-field injection guard so an existing
     * metadata entry is never duplicated + shadowed by a freshly-injected
     * lowercase copy. Null-safe.
     */
    private fun JsonObject?.containsResolving(key: String): Boolean {
        val m = this ?: return false
        return m.containsKey(key) || m.containsKey(key.lowercase()) || m.containsKey(key.uppercase())
    }
}

@Serializable
data class PartMetadata(
    val path: String? = null,
    val title: String? = null,
    val input: String? = null,
    val todos: List<TodoItem>? = null
)

private fun String.normalizePath(): String {
    return this.replace("\\", "/").trim('/')
}
