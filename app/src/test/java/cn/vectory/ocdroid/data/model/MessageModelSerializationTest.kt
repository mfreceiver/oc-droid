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
 * Round-trip + computed-property coverage for [Message] (and nested types),
 * [MessageWithParts], and the top-level pure helpers [isRenderableEmptyMessage]
 * and [isEffectivelyRenderableEmpty]. Pure kotlinx.serialization.
 */
class MessageModelSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    // ── Message round trip ────────────────────────────────────────────────

    @Test
    fun `Message round trip with all optional fields set`() {
        val message = Message(
            id = "msg-1",
            sessionId = "ses-1",
            role = "assistant",
            parentId = "msg-0",
            providerId = "anthropic",
            modelId = "claude-3",
            model = Message.ModelInfo(providerId = "anthropic", modelId = "claude-3"),
            agent = "build",
            error = Message.MessageError(name = "tool_failed", data = buildJsonObject { put("message", "boom") }),
            time = Message.TimeInfo(created = 1L, completed = 2L),
            finish = "stop",
            tokens = Message.TokenInfo(total = 10, input = 1, output = 9, reasoning = 0,
                cache = Message.TokenInfo.CacheInfo(read = 1, write = 2)),
            cost = 0.01
        )
        val encoded = json.encodeToString(message)
        val decoded = json.decodeFromString<Message>(encoded)
        assertEquals(message, decoded)
    }

    @Test
    fun `Message minimal round trip uses defaults`() {
        val message = Message(id = "m", role = "user")
        val encoded = json.encodeToString(message)
        val decoded = json.decodeFromString<Message>(encoded)
        assertEquals(message, decoded)
        assertNull(decoded.sessionId)
        assertNull(decoded.parentId)
        assertNull(decoded.model)
        assertNull(decoded.cost)
    }

    @Test
    fun `Message SerialName fields decode from camelCase server JSON`() {
        val decoded = json.decodeFromString<Message>(
            """
            {"id":"m1","sessionID":"sX","role":"user","parentID":"m0",
             "providerID":"openai","modelID":"gpt-4","finish":"stop","cost":0.5}
            """.trimIndent()
        )
        assertEquals("sX", decoded.sessionId)
        assertEquals("m0", decoded.parentId)
        assertEquals("openai", decoded.providerId)
        assertEquals("gpt-4", decoded.modelId)
        assertEquals(0.5, decoded.cost!!, 0.0001)
    }

    @Test
    fun `Message unknown fields do not crash`() {
        val decoded = json.decodeFromString<Message>(
            """{"id":"m","role":"user","unknownField":42,"nested":{"a":1}}"""
        )
        assertEquals("m", decoded.id)
    }

    // ── Message role helpers ──────────────────────────────────────────────

    @Test
    fun `Message isUser isAssistant are case-insensitive`() {
        assertTrue(Message(id = "1", role = "user").isUser)
        assertTrue(Message(id = "2", role = "USER").isUser)
        assertTrue(Message(id = "3", role = "assistant").isAssistant)
        assertTrue(Message(id = "4", role = "ASSISTANT").isAssistant)
        assertFalse(Message(id = "5", role = "user").isAssistant)
        assertFalse(Message(id = "6", role = "assistant").isUser)
    }

    @Test
    fun `Message isToolRole covers any non user-assistant role`() {
        assertTrue(Message(id = "1", role = "tool").isToolRole)
        assertTrue(Message(id = "2", role = "environment").isToolRole)
        assertTrue(Message(id = "3", role = "").isToolRole)
        assertFalse(Message(id = "4", role = "user").isToolRole)
        assertFalse(Message(id = "5", role = "assistant").isToolRole)
    }

    // ── Message.resolvedModel ─────────────────────────────────────────────

    @Test
    fun `Message resolvedModel prefers nested model object over top-level provider_model`() {
        val m = Message(
            id = "1", role = "assistant",
            providerId = "openai", modelId = "gpt-4",
            model = Message.ModelInfo(providerId = "anthropic", modelId = "claude")
        )
        assertNotNull(m.resolvedModel)
        assertEquals("anthropic", m.resolvedModel?.providerId)
        assertEquals("claude", m.resolvedModel?.modelId)
    }

    @Test
    fun `Message resolvedModel synthesizes from top-level ids when nested absent`() {
        val m = Message(id = "1", role = "assistant", providerId = "openai", modelId = "gpt-4")
        assertNotNull(m.resolvedModel)
        assertEquals("openai", m.resolvedModel?.providerId)
        assertEquals("gpt-4", m.resolvedModel?.modelId)
    }

    @Test
    fun `Message resolvedModel null when neither source present`() {
        val m = Message(id = "1", role = "assistant")
        assertNull(m.resolvedModel)
    }

    @Test
    fun `Message resolvedModel null when only one of providerId or modelId present`() {
        assertNull(Message(id = "1", role = "assistant", providerId = "openai").resolvedModel)
        assertNull(Message(id = "1", role = "assistant", modelId = "gpt-4").resolvedModel)
    }

    // ── Message.MessageError.message extraction ───────────────────────────

    @Test
    fun `MessageError message extracted from data message key`() {
        val err = Message.MessageError(
            name = "tool_failed",
            data = buildJsonObject { put("message", "boom") }
        )
        assertEquals("boom", err.message)
    }

    @Test
    fun `MessageError message falls back to data error key`() {
        val err = Message.MessageError(
            name = "x",
            data = buildJsonObject { put("error", "bad") }
        )
        assertEquals("bad", err.message)
    }

    @Test
    fun `MessageError message null when data null or message missing`() {
        assertNull(Message.MessageError(name = "x").message)
        assertNull(Message.MessageError(name = "x", data = buildJsonObject { put("foo", "bar") }).message)
    }

    @Test
    fun `MessageError message prefers message over error when both present`() {
        val err = Message.MessageError(
            name = "x",
            data = buildJsonObject {
                put("message", "primary")
                put("error", "fallback")
            }
        )
        assertEquals("primary", err.message)
    }

    // ── Nested types round trip ───────────────────────────────────────────

    @Test
    fun `Message ModelInfo round trip`() {
        val model = Message.ModelInfo(providerId = "openai", modelId = "gpt-4")
        val decoded = json.decodeFromString<Message.ModelInfo>(json.encodeToString(model))
        assertEquals(model, decoded)
    }

    @Test
    fun `Message TokenInfo round trip with cache`() {
        val t = Message.TokenInfo(
            total = 100, input = 10, output = 90, reasoning = 5,
            cache = Message.TokenInfo.CacheInfo(read = 1, write = 2)
        )
        val decoded = json.decodeFromString<Message.TokenInfo>(json.encodeToString(t))
        assertEquals(t, decoded)
        assertEquals(1, decoded.cache?.read)
        assertEquals(2, decoded.cache?.write)
    }

    @Test
    fun `Message TokenInfo nullable fields default null`() {
        val decoded = json.decodeFromString<Message.TokenInfo>("{}")
        assertNull(decoded.total)
        assertNull(decoded.cache)
    }

    @Test
    fun `Message TimeInfo round trip`() {
        val t = Message.TimeInfo(created = 1L, completed = 2L)
        val decoded = json.decodeFromString<Message.TimeInfo>(json.encodeToString(t))
        assertEquals(t, decoded)
    }

    @Test
    fun `Message MessageError with JsonObject data round trip`() {
        val err = Message.MessageError(name = "boom", data = buildJsonObject { put("k", JsonPrimitive("v")) })
        val decoded = json.decodeFromString<Message.MessageError>(json.encodeToString(err))
        assertEquals(err.name, decoded.name)
        assertEquals("v", (decoded.data!!["k"] as JsonPrimitive).content)
    }

    // ── MessageWithParts ──────────────────────────────────────────────────

    @Test
    fun `MessageWithParts round trip carries parts list`() {
        val mwp = MessageWithParts(
            info = Message(id = "m1", role = "assistant"),
            parts = listOf(
                Part(id = "p1", type = "text", text = "hi"),
                Part(id = "p2", type = "tool", tool = "bash")
            )
        )
        val encoded = json.encodeToString(mwp)
        val decoded = json.decodeFromString<MessageWithParts>(encoded)
        assertEquals(mwp.info, decoded.info)
        assertEquals(2, decoded.parts.size)
        assertEquals("p1", decoded.parts[0].id)
    }

    @Test
    fun `MessageWithParts defaults to empty parts list`() {
        val decoded = json.decodeFromString<MessageWithParts>(
            """{"info":{"id":"m","role":"user"}}"""
        )
        assertEquals("m", decoded.info.id)
        assertTrue(decoded.parts.isEmpty())
    }

    // ── isRenderableEmptyMessage (top-level pure fn) ──────────────────────

    @Test
    fun `isRenderableEmptyMessage true for non-user with empty parts and not streaming`() {
        assertTrue(isRenderableEmptyMessage(isUser = false, partsForMessage = emptyList(), isStreaming = false))
    }

    @Test
    fun `isRenderableEmptyMessage false when user`() {
        assertFalse(isRenderableEmptyMessage(isUser = true, partsForMessage = emptyList(), isStreaming = false))
    }

    @Test
    fun `isRenderableEmptyMessage false when streaming`() {
        assertFalse(isRenderableEmptyMessage(isUser = false, partsForMessage = emptyList(), isStreaming = true))
    }

    @Test
    fun `isRenderableEmptyMessage false when parts non-empty`() {
        assertFalse(isRenderableEmptyMessage(
            isUser = false,
            partsForMessage = listOf(Part(id = "p", type = "text", text = "x")),
            isStreaming = false
        ))
    }

    // ── isEffectivelyRenderableEmpty (top-level pure fn) ──────────────────

    @Test
    fun `isEffectivelyRenderableEmpty true for empty list`() {
        assertTrue(isEffectivelyRenderableEmpty(emptyList()))
    }

    @Test
    fun `isEffectivelyRenderableEmpty true when all text parts blank`() {
        val parts = listOf(
            Part(id = "1", type = "text", text = "   "),
            Part(id = "2", type = "text", text = null)
        )
        assertTrue(isEffectivelyRenderableEmpty(parts))
    }

    @Test
    fun `isEffectivelyRenderableEmpty false when any text part non-blank`() {
        val parts = listOf(
            Part(id = "1", type = "text", text = "hi"),
            Part(id = "2", type = "text", text = "")
        )
        assertFalse(isEffectivelyRenderableEmpty(parts))
    }

    @Test
    fun `isEffectivelyRenderableEmpty true when reasoning part blank`() {
        assertTrue(isEffectivelyRenderableEmpty(listOf(Part(id = "1", type = "reasoning", text = ""))))
    }

    @Test
    fun `isEffectivelyRenderableEmpty true when tool part has no name output or summary`() {
        val part = Part(
            id = "1", type = "tool", tool = null,
            state = PartState(displayString = "x") // no output/inputSummary/title
        )
        assertTrue(isEffectivelyRenderableEmpty(listOf(part)))
    }

    @Test
    fun `isEffectivelyRenderableEmpty false when tool part has name`() {
        assertFalse(isEffectivelyRenderableEmpty(listOf(Part(id = "1", type = "tool", tool = "bash"))))
    }

    @Test
    fun `isEffectivelyRenderableEmpty true when patch has no paths and blank output`() {
        val part = Part(id = "1", type = "patch")
        assertTrue(isEffectivelyRenderableEmpty(listOf(part)))
    }

    @Test
    fun `isEffectivelyRenderableEmpty false when patch has navigable path`() {
        val part = Part(
            id = "1", type = "patch",
            files = listOf(Part.FileChange(path = "src/a.kt"))
        )
        assertFalse(isEffectivelyRenderableEmpty(listOf(part)))
    }

    @Test
    fun `isEffectivelyRenderableEmpty false when pathless patch has inputSummary`() {
        // §kimo-F2: 无路径/无扩展名的 patch 但带 inputSummary（如 Makefile 的 patch 文本）
        // 不应被判空过滤——PatchCard 会用 inputSummary/output 内容回退渲染。
        val part = Part(
            id = "1", type = "patch",
            state = PartState(displayString = "x", inputSummary = "--- a/Makefile\n+b:new")
        )
        assertFalse(isEffectivelyRenderableEmpty(listOf(part)))
    }

    @Test
    fun `isEffectivelyRenderableEmpty true when file part has blank filename`() {
        assertTrue(isEffectivelyRenderableEmpty(listOf(Part(id = "1", type = "file"))))
    }

    @Test
    fun `isEffectivelyRenderableEmpty false when file part has filename`() {
        assertFalse(isEffectivelyRenderableEmpty(listOf(Part(id = "1", type = "file", filename = "a.png"))))
    }
}
