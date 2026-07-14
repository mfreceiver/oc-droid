package cn.vectory.ocdroid.ui

import android.util.Log
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * §task7-coverage: JVM unit tests for the pure parse helpers in
 * [ViewModelSupport]. These functions are exercised indirectly by
 * [cn.vectory.ocdroid.ui.controller.SessionSyncCoordinatorTest] via
 * handleEvent, but several null / malformed-input branches were never
 * hit because every integration test supplied well-formed payloads.
 * This file drives the missing edge cases directly so the parse
 * contract (null on any unrecoverable input) is pinned at the unit
 * level.
 */
class ViewModelSupportParseTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun sseEvent(type: String, block: (kotlinx.serialization.json.JsonObjectBuilder).() -> Unit): SSEEvent =
        SSEEvent(payload = SSEPayload(type = type, properties = buildJsonObject(block)))

    // ── errorMessageOrFallback ──────────────────────────────────────────────

    @Test
    fun `errorMessageOrFallback returns trimmed throwable message when non-empty`() {
        assertEquals("boom", errorMessageOrFallback(RuntimeException("  boom  "), "fallback"))
    }

    @Test
    fun `errorMessageOrFallback returns fallback when throwable is null`() {
        assertEquals("fallback", errorMessageOrFallback(null, "fallback"))
        verify { Log.e("OC_ERROR", "error surfaced to UI", null) }
    }

    @Test
    fun `errorMessageOrFallback returns fallback when throwable message is null`() {
        assertEquals("fallback", errorMessageOrFallback(RuntimeException(), "fallback"))
    }

    @Test
    fun `errorMessageOrFallback returns fallback when throwable message is blank`() {
        assertEquals("fallback", errorMessageOrFallback(RuntimeException("   "), "fallback"))
    }

    // ── parseSessionCreatedEvent ────────────────────────────────────────────

    @Test
    fun `parseSessionCreatedEvent returns null when session key is absent`() {
        val ev = sseEvent("session.created") { /* no session key */ }
        assertNull(parseSessionCreatedEvent(ev))
    }

    @Test
    fun `parseSessionCreatedEvent returns null when session JSON is malformed`() {
        val ev = sseEvent("session.created") {
            put("session", JsonPrimitive("not-an-object"))
        }
        assertNull(parseSessionCreatedEvent(ev))
    }

    @Test
    fun `parseSessionCreatedEvent returns null when session JSON lacks required id field`() {
        val ev = sseEvent("session.created") {
            put("session", buildJsonObject {
                put("directory", "/tmp")
            })
        }
        assertNull(parseSessionCreatedEvent(ev))
    }

    @Test
    fun `parseSessionCreatedEvent parses a valid session`() {
        val ev = sseEvent("session.created") {
            put("session", buildJsonObject {
                put("id", "s1")
                put("directory", "/tmp")
            })
        }
        val result = parseSessionCreatedEvent(ev)
        assertNotNull(result)
        assertEquals("s1", result!!.session.id)
    }

    // ── parseSessionUpdatedEvent ────────────────────────────────────────────

    @Test
    fun `parseSessionUpdatedEvent returns null when both info and session keys are absent`() {
        val ev = sseEvent("session.updated") { /* no info / session */ }
        assertNull(parseSessionUpdatedEvent(ev))
    }

    @Test
    fun `parseSessionUpdatedEvent falls back to session key when info is absent`() {
        val ev = sseEvent("session.updated") {
            put("session", buildJsonObject {
                put("id", "from-session")
                put("directory", "/tmp")
            })
        }
        val result = parseSessionUpdatedEvent(ev)
        assertNotNull(result)
        assertEquals("from-session", result!!.id)
    }

    @Test
    fun `parseSessionUpdatedEvent prefers info key over session key`() {
        val ev = sseEvent("session.updated") {
            put("info", buildJsonObject {
                put("id", "from-info")
                put("directory", "/tmp")
            })
            put("session", buildJsonObject {
                put("id", "from-session")
                put("directory", "/tmp")
            })
        }
        assertEquals("from-info", parseSessionUpdatedEvent(ev)?.id)
    }

    @Test
    fun `parseSessionUpdatedEvent returns null when info JSON is malformed`() {
        val ev = sseEvent("session.updated") {
            put("info", JsonPrimitive(42))
        }
        assertNull(parseSessionUpdatedEvent(ev))
    }

    @Test
    fun `parseSessionUpdatedEvent returns null when session-key JSON is malformed`() {
        // Use a valid JsonObject (so getJsonObject succeeds) that fails
        // Session deserialization (missing required id+directory).
        val ev = sseEvent("session.updated") {
            put("session", buildJsonObject {
                put("foo", "bar")
            })
        }
        assertNull(parseSessionUpdatedEvent(ev))
    }

    // ── parseSessionStatusEvent ─────────────────────────────────────────────

    @Test
    fun `parseSessionStatusEvent returns null when sessionID is absent`() {
        val ev = sseEvent("session.status") {
            put("status", buildJsonObject { put("type", "idle") })
        }
        assertNull(parseSessionStatusEvent(ev))
    }

    @Test
    fun `parseSessionStatusEvent returns null when status object is absent`() {
        val ev = sseEvent("session.status") {
            put("sessionID", JsonPrimitive("s1"))
        }
        assertNull(parseSessionStatusEvent(ev))
    }

    @Test
    fun `parseSessionStatusEvent returns null when status JSON fails strict deserialization`() {
        val ev = sseEvent("session.status") {
            put("sessionID", JsonPrimitive("s1"))
            put("status", buildJsonObject {
                put("type", JsonArray(listOf(JsonPrimitive("nope"))))
            })
        }
        assertNull(parseSessionStatusEvent(ev))
    }

    @Test
    fun `parseSessionStatusEvent parses a valid status event`() {
        val ev = sseEvent("session.status") {
            put("sessionID", JsonPrimitive("s1"))
            put("status", buildJsonObject {
                put("type", "busy")
                put("attempt", 2)
            })
        }
        val result = parseSessionStatusEvent(ev)
        assertNotNull(result)
        assertEquals("s1", result!!.sessionId)
        assertEquals("busy", result.status.type)
        assertEquals(2, result.status.attempt)
    }

    // ── parseMessagePartDeltaEvent ──────────────────────────────────────────

    @Test
    fun `parseMessagePartDeltaEvent returns null when sessionID is absent`() {
        val ev = sseEvent("message.part.updated") {
            put("part", buildJsonObject {
                put("messageID", "m1")
                put("id", "p1")
            })
        }
        assertNull(parseMessagePartDeltaEvent(ev))
    }

    @Test
    fun `parseMessagePartDeltaEvent handles absent part object with defaults`() {
        val ev = sseEvent("message.part.updated") {
            put("sessionID", JsonPrimitive("s1"))
        }
        val result = parseMessagePartDeltaEvent(ev)
        assertNotNull(result)
        assertEquals("s1", result!!.sessionId)
        assertNull(result.messageId)
        assertNull(result.partId)
        assertEquals("text", result.partType)
        assertNull(result.delta)
        assertNull(result.text)
    }

    @Test
    fun `parseMessagePartDeltaEvent defaults partType to text when type field is absent`() {
        val ev = sseEvent("message.part.updated") {
            put("sessionID", JsonPrimitive("s1"))
            put("part", buildJsonObject {
                put("messageID", "m1")
                put("id", "p1")
            })
        }
        val result = parseMessagePartDeltaEvent(ev)
        assertNotNull(result)
        assertEquals("text", result!!.partType)
        assertEquals("m1", result.messageId)
        assertEquals("p1", result.partId)
    }

    @Test
    fun `parseMessagePartDeltaEvent parses all fields from a full part object`() {
        val ev = sseEvent("message.part.updated") {
            put("sessionID", JsonPrimitive("s1"))
            put("delta", JsonPrimitive("hello"))
            put("part", buildJsonObject {
                put("messageID", "m1")
                put("id", "p1")
                put("type", "reasoning")
                put("text", "accumulated text")
            })
        }
        val result = parseMessagePartDeltaEvent(ev)
        assertNotNull(result)
        assertEquals("reasoning", result!!.partType)
        assertEquals("accumulated text", result.text)
        assertEquals("hello", result.delta)
    }

    @Test
    fun `parseMessagePartDeltaEvent ignores non-primitive part fields`() {
        val ev = sseEvent("message.part.updated") {
            put("sessionID", JsonPrimitive("s1"))
            put("part", buildJsonObject {
                put("messageID", buildJsonObject { put("nested", "x") })
                put("id", JsonArray(emptyList()))
                put("type", buildJsonObject { put("wrong", 1) })
            })
        }
        val result = parseMessagePartDeltaEvent(ev)
        assertNotNull(result)
        assertNull(result!!.messageId)
        assertNull(result.partId)
        assertEquals("text", result.partType)
    }

    @Test
    fun `parseMessagePartDeltaEvent handles part with only sessionID and no part object fields`() {
        // Cover the branch where partObj is non-null but all inner fields
        // are absent (each safe-cast returns null, partType defaults to text).
        val ev = sseEvent("message.part.updated") {
            put("sessionID", JsonPrimitive("s1"))
            put("part", buildJsonObject { /* empty part object */ })
        }
        val result = parseMessagePartDeltaEvent(ev)
        assertNotNull(result)
        assertEquals("s1", result!!.sessionId)
        assertNull(result.messageId)
        assertNull(result.partId)
        assertEquals("text", result.partType)
        assertNull(result.text)
    }

    // ── parseQuestionAskedEvent ─────────────────────────────────────────────

    @Test
    fun `parseQuestionAskedEvent returns null when properties are absent`() {
        val ev = SSEEvent(payload = SSEPayload(type = "question.asked", properties = null))
        assertNull(parseQuestionAskedEvent(ev))
    }

    @Test
    fun `parseQuestionAskedEvent returns null when properties are malformed`() {
        val ev = sseEvent("question.asked") {
            put("id", JsonArray(emptyList()))
        }
        assertNull(parseQuestionAskedEvent(ev))
    }

    // ── reportNonFatalIssue ─────────────────────────────────────────────────

    @Test
    fun `reportNonFatalIssue without throwable calls Log_w with two args`() {
        reportNonFatalIssue("Tag", "message")
        verify { Log.w("Tag", "message") }
    }

    @Test
    fun `reportNonFatalIssue with throwable calls Log_w with three args`() {
        val t = RuntimeException("err")
        reportNonFatalIssue("Tag", "message", t)
        verify { Log.w("Tag", "message", t) }
    }

    // ── isStreamablePartType ────────────────────────────────────────────────

    @Test
    fun `isStreamablePartType returns true for text and reasoning`() {
        assert(isStreamablePartType("text"))
        assert(isStreamablePartType("reasoning"))
    }

    @Test
    fun `isStreamablePartType returns false for non-streamable types`() {
        assert(!isStreamablePartType("tool"))
        assert(!isStreamablePartType("patch"))
        assert(!isStreamablePartType("file"))
        assert(!isStreamablePartType(""))
    }

    // ── T5-re-review M1-R — ssePayloadJson config pinning ──────────────────

    /**
     * T5-re-review M1-R: the prior coercion test
     * (`SseNotificationBridgeTest.I4 - session_status with explicit null on
     * optional field decodes to default`) supplied null to ALREADY-nullable
     * fields (`Int? = null` / `String? = null`), which the default `Json`
     * accepts even WITHOUT `coerceInputValues`. Removing `coerceInputValues`
     * from the canonical config would have gone undetected.
     *
     * This test pins all four [ssePayloadJson.configuration] flags directly
     * so any removal (of ANY flag) is caught immediately.
     */
    @Test
    fun `M1-R - ssePayloadJson pins all four canonical configuration flags`() {
        val config = ssePayloadJson.configuration
        assertFalse("explicitNulls must be false (omitted fields → null defaults)", config.explicitNulls)
        assertTrue("ignoreUnknownKeys must be true (server additions don't drop events)", config.ignoreUnknownKeys)
        assertTrue("coerceInputValues must be true (explicit nulls on non-nullable-defaulted → defaults)", config.coerceInputValues)
        assertTrue("encodeDefaults must be true (round-trip stable)", config.encodeDefaults)
    }

    /**
     * T5-re-review M1-R: a NON-null defaulted property receiving an explicit
     * JSON `null` decodes to its default ONLY under `coerceInputValues = true`.
     * Without coercion, `kotlinx.serialization` throws on an explicit null
     * for a non-nullable type (even with `explicitNulls = false`, which only
     * affects OMITTED fields, not explicit nulls).
     *
     * This fixture uses a test-local [Serializable] data class with a
     * non-nullable `String` property that has a default. Decoding
     * `{"name": null}` succeeds (coerced to the default) — would THROW if
     * `coerceInputValues` were removed.
     */
    @Test
    fun `M1-R - explicit null on non-nullable defaulted property coerces to default under ssePayloadJson`() {
        val decoded = ssePayloadJson.decodeFromString<M1RFixture>("""{"name": null}""")
        assertEquals(
            "explicit null on non-nullable defaulted String coerced to its default",
            "fallback",
            decoded.name,
        )
    }

    /**
     * T5-re-review M1-R: without `coerceInputValues`, the default `Json`
     * THROWS on an explicit null for a non-nullable property — confirming
     * the fixture above genuinely detects removal of the flag.
     */
    @Test
    fun `M1-R - default Json throws on explicit null for non-nullable defaulted property`() {
        val strict = Json { explicitNulls = false }
        val result = runCatching { strict.decodeFromString<M1RFixture>("""{"name": null}""") }
        assertTrue(
            "default Json (no coerceInputValues) must throw on explicit null for non-nullable",
            result.isFailure,
        )
    }

    @Serializable
    private data class M1RFixture(
        val name: String = "fallback",
    )
}
