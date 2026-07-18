package cn.vectory.ocdroid.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip + computed-property coverage for [Part] and its collaborators
 * ([PartState], [PartMetadata], [Part.FileChange], custom [PartFilesSerializer]
 * + [PartStateSerializer]). Also exercises [ComposerImageAttachment]'s
 * equals/hashCode (not @Serializable, but lives in the model package).
 */
class PartModelSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    // ── Part type checks ──────────────────────────────────────────────────

    @Test
    fun `Part type checks cover all variants`() {
        assertTrue(Part(id = "1", type = "text").isText)
        assertTrue(Part(id = "1", type = "reasoning").isReasoning)
        assertTrue(Part(id = "1", type = "tool").isTool)
        assertTrue(Part(id = "1", type = "patch").isPatch)
        assertTrue(Part(id = "1", type = "file").isFile)
        assertTrue(Part(id = "1", type = "step-start").isStepStart)
        assertTrue(Part(id = "1", type = "step-finish").isStepFinish)

        assertFalse(Part(id = "1", type = "text").isTool)
        assertFalse(Part(id = "1", type = "tool").isText)
    }

    @Test
    fun `Part isImageAttachment only when file and mime starts with image slash`() {
        assertTrue(Part(id = "1", type = "file", mime = "image/png").isImageAttachment)
        assertTrue(Part(id = "1", type = "file", mime = "image/jpeg").isImageAttachment)
        assertFalse(Part(id = "1", type = "file", mime = "application/pdf").isImageAttachment)
        assertFalse(Part(id = "1", type = "file", mime = null).isImageAttachment)
        // Not a file → never an image attachment even with image mime.
        assertFalse(Part(id = "1", type = "text", mime = "image/png").isImageAttachment)
    }

    // ── Part round trip ───────────────────────────────────────────────────

    @Test
    fun `Part round trip preserves fields`() {
        val part = Part(
            id = "p1", messageId = "m1", sessionId = "s1", type = "text",
            text = "hello", tool = null, callId = "c1", state = null,
            metadata = PartMetadata(path = "a.kt"), files = null,
            mime = null, filename = null, url = null, source = null
        )
        val encoded = json.encodeToString(part)
        val decoded = json.decodeFromString<Part>(encoded)
        assertEquals(part, decoded)
    }

    @Test
    fun `Part decodes camelCase serial names`() {
        val decoded = json.decodeFromString<Part>(
            """{"id":"p","messageID":"m","sessionID":"s","type":"text","callID":"c"}"""
        )
        assertEquals("m", decoded.messageId)
        assertEquals("s", decoded.sessionId)
        assertEquals("c", decoded.callId)
    }

    // ── Part source serializer (string vs object) ─────────────────────────

    @Test
    fun `Part source decodes string value`() {
        val part = json.decodeFromString<Part>(
            """{"id":"p","type":"file","source":"attachment"}"""
        )
        assertEquals("attachment", part.source)
    }

    @Test
    fun `Part source decodes null JSON to null`() {
        val part = json.decodeFromString<Part>(
            """{"id":"p","type":"file","source":null}"""
        )
        assertNull(part.source)
    }

    @Test
    fun `Part source decodes missing field to null`() {
        val part = json.decodeFromString<Part>(
            """{"id":"p","type":"file"}"""
        )
        assertNull(part.source)
    }

    @Test
    fun `Part source decodes object form to null without crashing`() {
        // Reproduces the slimapi v1 context-part shape:
        // {"text":{"value":"@docs/v1-impl-spec.md"}}
        val part = json.decodeFromString<Part>(
            """{"id":"p","type":"context","source":{"text":{"value":"@docs/v1-impl-spec.md"}}}"""
        )
        assertNull(part.source)
    }

    @Test
    fun `Part source round trip preserves string value`() {
        val part = Part(id = "p", type = "file", source = "attachment")
        val encoded = json.encodeToString(part)
        val decoded = json.decodeFromString<Part>(encoded)
        assertEquals("attachment", decoded.source)
    }

    // ── Part state-derived accessors ──────────────────────────────────────

    @Test
    fun `Part stateDisplay toolReason toolInputSummary toolOutput read from state`() {
        val state = PartState(
            displayString = "completed",
            title = "Running ls",
            inputSummary = "ls -la",
            output = "drwxr-xr-x"
        )
        val part = Part(id = "p", type = "tool", state = state)
        assertEquals("completed", part.stateDisplay)
        assertEquals("Running ls", part.toolReason)
        assertEquals("ls -la", part.toolInputSummary)
        assertEquals("drwxr-xr-x", part.toolOutput)
    }

    @Test
    fun `Part state-derived accessors null when state is null`() {
        val part = Part(id = "p", type = "tool")
        assertNull(part.stateDisplay)
        assertNull(part.toolReason)
        assertNull(part.toolInputSummary)
        assertNull(part.toolOutput)
    }

    @Test
    fun `Part taskSubSessionId reads sessionId then sessionID from state metadata`() {
        val fromCamel = Part(
            id = "p", type = "tool", tool = "task",
            state = PartState(displayString = "x", metadata = buildJsonObject { put("sessionId", "child-1") })
        )
        assertEquals("child-1", fromCamel.taskSubSessionId)

        val fromLegacy = Part(
            id = "p", type = "tool", tool = "task",
            state = PartState(displayString = "x", metadata = buildJsonObject { put("sessionID", "child-2") })
        )
        assertEquals("child-2", fromLegacy.taskSubSessionId)
    }

    @Test
    fun `Part taskSubSessionId null when state has no session ids`() {
        val part = Part(id = "p", type = "tool", state = PartState(displayString = "x"))
        assertNull(part.taskSubSessionId)
    }

    @Test
    fun `Part isSubAgentTask only for task tool type`() {
        assertTrue(Part(id = "p", type = "tool", tool = "task").isSubAgentTask)
        assertTrue(Part(id = "p", type = "tool", tool = "TASK").isSubAgentTask)
        assertTrue(Part(id = "p", type = "tool", tool = "Task").isSubAgentTask)
        assertFalse(Part(id = "p", type = "tool", tool = "bash").isSubAgentTask)
        assertFalse(Part(id = "p", type = "text", tool = "task").isSubAgentTask)
    }

    // ── Part.toolTodos ────────────────────────────────────────────────────

    @Test
    fun `Part toolTodos prefers metadata todos over state todos`() {
        val metadataTodo = TodoItem(content = "from metadata", status = "pending", priority = "low", id = "1")
        val stateTodo = TodoItem(content = "from state", status = "pending", priority = "low", id = "2")
        val part = Part(
            id = "p", type = "tool",
            metadata = PartMetadata(todos = listOf(metadataTodo)),
            state = PartState(displayString = "x", todos = listOf(stateTodo))
        )
        assertEquals(1, part.toolTodos.size)
        assertEquals("from metadata", part.toolTodos[0].content)
    }

    @Test
    fun `Part toolTodos falls back to state todos when metadata todos null or empty`() {
        val stateTodo = TodoItem(content = "from state", status = "pending", priority = "low", id = "2")
        val part = Part(
            id = "p", type = "tool",
            metadata = PartMetadata(todos = null),
            state = PartState(displayString = "x", todos = listOf(stateTodo))
        )
        assertEquals(1, part.toolTodos.size)
        assertEquals("from state", part.toolTodos[0].content)
    }

    @Test
    fun `Part toolTodos empty when neither source has todos`() {
        val part = Part(id = "p", type = "tool")
        assertTrue(part.toolTodos.isEmpty())
    }

    // ── Part.filePathsForNavigation ───────────────────────────────────────

    @Test
    fun `Part filePathsForNavigation aggregates files metadata path and state pathFromInput`() {
        val part = Part(
            id = "p", type = "patch",
            files = listOf(Part.FileChange(path = "src/a.kt")),
            metadata = PartMetadata(path = "README.md"),
            state = PartState(displayString = "x", pathFromInput = "src/b.kt")
        )
        // Order: files first, then metadata.path, then state.pathFromInput.
        assertEquals(listOf("src/a.kt", "README.md", "src/b.kt"), part.filePathsForNavigation)
    }

    @Test
    fun `Part filePathsForNavigation deduplicates pathFromInput when already present from files`() {
        val part = Part(
            id = "p", type = "patch",
            files = listOf(Part.FileChange(path = "src/a.kt")),
            state = PartState(displayString = "x", pathFromInput = "src/a.kt")
        )
        // No duplicate added.
        assertEquals(listOf("src/a.kt"), part.filePathsForNavigation)
    }

    @Test
    fun `Part filePathsForNavigation normalizes backslashes and trims slashes`() {
        val part = Part(
            id = "p", type = "patch",
            files = listOf(Part.FileChange(path = "\\src\\a.kt")),
            metadata = PartMetadata(path = "/docs/intro.md/")
        )
        assertEquals(listOf("src/a.kt", "docs/intro.md"), part.filePathsForNavigation)
    }

    // ── Part.filePathsForNavigationFiltered ───────────────────────────────

    @Test
    fun `Part filePathsForNavigationFiltered keeps files with extension and no trailing slash`() {
        // NOTE: paths are normalized (trim trailing /, backslash → /) BEFORE the
        // filter runs, so "a.b/" → "a.b" and then passes the dot+not-empty gates.
        val part = Part(
            id = "p", type = "patch",
            files = listOf(
                Part.FileChange(path = "src/main.kt"),     // keep
                Part.FileChange(path = "src/"),             // drop: normalized to "src" → no dot
                Part.FileChange(path = "adhoc_jobs"),       // drop: no extension
                Part.FileChange(path = "a.b/"),             // keep: normalized to "a.b" → has dot
                Part.FileChange(path = "README.md")         // keep
            )
        )
        assertEquals(listOf("src/main.kt", "a.b", "README.md"), part.filePathsForNavigationFiltered)
    }

    @Test
    fun `Part filePathsForNavigationFiltered empty when no navigable files`() {
        val part = Part(id = "p", type = "patch")
        assertTrue(part.filePathsForNavigationFiltered.isEmpty())
    }

    // ── PartState serializer (primitive + object forms) ───────────────────

    @Test
    fun `PartState deserializes from a string primitive to displayString`() {
        val state = json.decodeFromString<PartState>("\"running\"")
        assertEquals("running", state.displayString)
    }

    @Test
    fun `PartState deserializes object with status field`() {
        val state = json.decodeFromString<PartState>("{" + "\"status\":\"completed\"}")
        assertEquals("completed", state.displayString)
    }

    @Test
    fun `PartState deserializes object with title field when status absent`() {
        val state = json.decodeFromString<PartState>("{" + "\"title\":\"My Tool\"}")
        assertEquals("My Tool", state.displayString)
    }

    @Test
    fun `PartState defaults displayString to ellipsis when no status or title`() {
        val state = json.decodeFromString<PartState>("{}")
        assertEquals("…", state.displayString)
    }

    @Test
    fun `PartState serializer serializes to a bare displayString string`() {
        val state = PartState(displayString = "running", title = "ignored-on-serialize")
        val encoded = json.encodeToString(state)
        assertEquals("\"running\"", encoded)
    }

    @Test
    fun `PartState parses input object with command path and todos`() {
        val state = json.decodeFromString<PartState>(
            """
            {"status":"completed","input":{"command":"ls","path":"/tmp","todos":[
              {"content":"a","status":"completed","priority":"high"},
              {"content":"b","status":"pending","priority":"low"}
            ]}}
            """.trimIndent()
        )
        assertEquals("ls", state.inputSummary)
        assertEquals("/tmp", state.pathFromInput)
        assertEquals(2, state.todos?.size)
        assertEquals("a", state.todos!![0].content)
    }

    @Test
    fun `PartState parses input as primitive string into inputSummary`() {
        val state = json.decodeFromString<PartState>("{" + "\"status\":\"pending\",\"input\":\"raw string\"}")
        assertEquals("raw string", state.inputSummary)
    }

    @Test
    fun `PartState parses input file_path into pathFromInput`() {
        val state = json.decodeFromString<PartState>(
            """{"status":"completed","input":{"file_path":"/a/b.kt"}}"""
        )
        assertEquals("/a/b.kt", state.pathFromInput)
    }

    @Test
    fun `PartState parses input filePath camelCase into pathFromInput`() {
        val state = json.decodeFromString<PartState>(
            """{"status":"completed","input":{"filePath":"/c/d.kt"}}"""
        )
        assertEquals("/c/d.kt", state.pathFromInput)
    }

    @Test
    fun `PartState parses patchText Add File prefix into pathFromInput`() {
        val state = json.decodeFromString<PartState>(
            """{"status":"completed","input":{"patchText":"*** Add File: src/new.kt\n- old\n+ new"}}"""
        )
        assertEquals("src/new.kt", state.pathFromInput)
    }

    @Test
    fun `PartState parses patchText Update File prefix into pathFromInput`() {
        val state = json.decodeFromString<PartState>(
            """{"status":"completed","input":{"patchText":"*** Update File: src/existing.kt\nrest"}}"""
        )
        assertEquals("src/existing.kt", state.pathFromInput)
    }

    @Test
    fun `PartState output falls back to metadata output when top-level output missing`() {
        val state = json.decodeFromString<PartState>(
            """{"status":"completed","metadata":{"output":"from meta"}}"""
        )
        assertEquals("from meta", state.output)
    }

    @Test
    fun `PartState title falls back to metadata description when top-level title missing`() {
        val state = json.decodeFromString<PartState>(
            """{"status":"completed","metadata":{"description":"from meta"}}"""
        )
        assertEquals("from meta", state.title)
    }

    @Test
    fun `PartState todos fall back to metadata todos when no input todos`() {
        val state = json.decodeFromString<PartState>(
            """{"status":"completed","metadata":{"todos":[{"content":"m","status":"pending"}]}}"""
        )
        assertEquals(1, state.todos?.size)
        assertEquals("m", state.todos!![0].content)
    }

    @Test
    fun `PartState strips diagnostics from metadata to bound memory`() {
        val state = json.decodeFromString<PartState>(
            """{"status":"completed","metadata":{"diagnostics":{"big":[1,2,3]},"sessionId":"keep"}}"""
        )
        assertNotNull(state.metadata)
        assertFalse(state.metadata!!.containsKey("diagnostics"))
        assertTrue(state.metadata!!.containsKey("sessionId"))
    }

    // ── PartState.metadataString casing resolution ────────────────────────

    @Test
    fun `PartState metadataString resolves exact lowercase and uppercase keys`() {
        val state = PartState(
            displayString = "x",
            metadata = buildJsonObject {
                put("sessionId", "exact")
                put("name", "lower")
                put("TITLE", "upper")
            }
        )
        assertEquals("exact", state.metadataString("sessionId"))
        assertEquals("lower", state.metadataString("name"))
        assertEquals("lower", state.metadataString("NAME"))
        assertEquals("upper", state.metadataString("title"))
        assertEquals("upper", state.metadataString("Title"))
    }

    @Test
    fun `PartState metadataString returns null for missing key`() {
        val state = PartState(displayString = "x", metadata = buildJsonObject { put("a", "b") })
        assertNull(state.metadataString("missing"))
    }

    @Test
    fun `PartState metadataString returns null when metadata is null`() {
        assertNull(PartState(displayString = "x").metadataString("any"))
    }

    @Test
    fun `PartState metadataString ignores empty string values`() {
        val state = PartState(
            displayString = "x",
            metadata = buildJsonObject { put("k", "") }
        )
        assertNull(state.metadataString("k"))
    }

    // ── PartFilesSerializer variants ──────────────────────────────────────

    @Test
    fun `Part files decoded from array of path strings`() {
        val part = json.decodeFromString<Part>(
            """{"id":"p","type":"tool","files":["/a/b","/c/d"]}"""
        )
        assertEquals(2, part.files?.size)
        assertEquals("/a/b", part.files!![0].path)
        assertEquals("/c/d", part.files!![1].path)
    }

    @Test
    fun `Part files decoded from array of objects with path additions deletions status`() {
        val part = json.decodeFromString<Part>(
            """{"id":"p","type":"tool","files":[{"path":"a","additions":1,"deletions":2,"status":"modified"}]}"""
        )
        assertEquals(1, part.files?.size)
        val f = part.files!![0]
        assertEquals("a", f.path)
        assertEquals(1, f.additions)
        assertEquals(2, f.deletions)
        assertEquals("modified", f.status)
    }

    @Test
    fun `Part files null when JSON value is null`() {
        val part = json.decodeFromString<Part>("""{"id":"p","type":"tool","files":null}""")
        assertNull(part.files)
    }

    @Test
    fun `Part files null when JSON value is not an array`() {
        // Coerce string into JsonElement that is not an array → null.
        val part = json.decodeFromString<Part>("""{"id":"p","type":"tool","files":"oops"}""")
        assertNull(part.files)
    }

    @Test
    fun `Part files object missing path key is skipped`() {
        val part = json.decodeFromString<Part>(
            """{"id":"p","type":"tool","files":[{"additions":1},{"path":"kept"}]}"""
        )
        assertEquals(1, part.files?.size)
        assertEquals("kept", part.files!![0].path)
    }

    @Test
    fun `Part files serialize back as array of path-only objects`() {
        val part = Part(
            id = "p", type = "tool",
            files = listOf(
                Part.FileChange(path = "a.kt", additions = 5, deletions = 2, status = "M")
            )
        )
        val encoded = json.encodeToString(part)
        // Serializer always emits path-only objects (drops additions/deletions/status).
        assertTrue(encoded.contains("\"files\":[{\"path\":\"a.kt\"}]"))
    }

    // ── PartMetadata ──────────────────────────────────────────────────────

    @Test
    fun `PartMetadata round trip`() {
        val meta = PartMetadata(
            path = "a.kt", title = "T", input = "I",
            todos = listOf(TodoItem(content = "x", status = "pending", priority = "low", id = "1"))
        )
        val encoded = json.encodeToString(meta)
        val decoded = json.decodeFromString<PartMetadata>(encoded)
        assertEquals(meta.path, decoded.path)
        assertEquals(meta.title, decoded.title)
        assertEquals(meta.input, decoded.input)
        assertEquals(1, decoded.todos?.size)
    }

    @Test
    fun `PartMetadata defaults to nulls`() {
        val decoded = json.decodeFromString<PartMetadata>("{}")
        assertNull(decoded.path)
        assertNull(decoded.title)
        assertNull(decoded.input)
        assertNull(decoded.todos)
    }

    // ── PartState.object mock metadata passthrough into JsonObject ────────

    @Test
    fun `Part state metadata passthrough exposes raw JsonObject`() {
        val obj = buildJsonObject { put("foo", JsonPrimitive("bar")) }
        val state = PartState(displayString = "x", metadata = obj)
        assertNotNull(state.metadata)
        assertEquals("bar", (state.metadata!!["foo"] as JsonPrimitive).content)
    }

    // ── ComposerImageAttachment equals / hashCode ─────────────────────────

    @Test
    fun `ComposerImageAttachment equals and hashCode by content`() {
        val a = ComposerImageAttachment(
            id = "1", filename = "a.png", mime = "image/png",
            dataUrl = "data:image/png;base64,AAA",
            thumbnailData = byteArrayOf(1, 2, 3), byteSize = 100
        )
        val b = ComposerImageAttachment(
            id = "1", filename = "a.png", mime = "image/png",
            dataUrl = "data:image/png;base64,AAA",
            thumbnailData = byteArrayOf(1, 2, 3), byteSize = 100
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `ComposerImageAttachment not equal when fields differ`() {
        val base = ComposerImageAttachment(
            id = "1", filename = "a.png", mime = "image/png",
            dataUrl = "u", thumbnailData = byteArrayOf(1), byteSize = 1
        )
        // Different id, filename, mime, dataUrl, thumbnailData, byteSize.
        assertFalse(base == base.copy(id = "2"))
        val other = ComposerImageAttachment(
            id = "1", filename = "b.png", mime = "image/png",
            dataUrl = "u", thumbnailData = byteArrayOf(1), byteSize = 1
        )
        assertFalse(base == other)
    }

    @Test
    fun `ComposerImageAttachment equals handles same instance and non-attachment`() {
        val a = ComposerImageAttachment(
            id = "1", filename = "a", mime = "m", dataUrl = "u",
            thumbnailData = byteArrayOf(), byteSize = 0
        )
        assertTrue(a == a)
        // equals(null) should return false (guard against buggy null handling
        // in the override). Use .equals to bypass Kotlin's `==` null-check.
        assertFalse(a.equals(null))
        assertFalse(a.equals("not an attachment"))
    }

    private fun ComposerImageAttachment.copy(id: String = this.id): ComposerImageAttachment =
        ComposerImageAttachment(
            id = id, filename = filename, mime = mime, dataUrl = dataUrl,
            thumbnailData = thumbnailData, byteSize = byteSize
        )
}
