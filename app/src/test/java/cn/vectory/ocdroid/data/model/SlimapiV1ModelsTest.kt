package cn.vectory.ocdroid.data.model

import cn.vectory.ocdroid.data.repository.http.SlimapiErrorCodes
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 1 (slimapi v1 client L0): round-trip + three-state coverage for the
 * foundation models that all later slimapi-v1 tasks depend on.
 *
 * Acceptance covered:
 *  - **T1-C1** — `Part.hasFull` / `omitted` round-trip + default null
 *    (forward-compat with `explicitNulls=false` legacy responses).
 *  - **T1-C2** — `SlimSessionDigest.lastError` three states:
 *    key-absent → [LastErrorField.Omitted]; JSON null →
 *    [LastErrorField.Cleared]; JSON object → [LastErrorField.Set].
 *  - **T1-C3** — G6 batch envelope `SlimapiMessageFullBatch` parses both
 *    `items[]` and `errors[]`.
 *  - **T1-C4** — `SlimapiErrorCodes` constant values match the impl task
 *    book verbatim; `LastErrorField` serializer descriptor uses
 *    `kotlinx.serialization.descriptors.buildClassSerialDescriptor` (pinned
 *    structurally by the three decode paths; if the descriptor import is
 *    wrong the file fails to compile).
 *
 * The Json instance mirrors the production `ssePayloadJson`
 * (ViewModelSupport.kt:42-47): `ignoreUnknownKeys + explicitNulls=false +
 * coerceInputValues + encodeDefaults`. `explicitNulls=false` is what makes
 * the `lastError` key-absent vs key-null distinction possible (kotlinx
 * uses the field default for the former, invokes the serializer for the
 * latter).
 */
class SlimapiV1ModelsTest {

    // Mirrors the brief's dispatch-mandated Json config
    // (ignoreUnknownKeys + explicitNulls=false + encodeDefaults; NO
    // coerceInputValues — see note below). This is the config the slimapi
    // v1 SSE digest path will use; explicitNulls=false is what lets
    // key-absent vs key-present-null differ (kotlinx uses the field
    // default for the former, invokes the serializer for the latter).
    //
    // NOTE: `coerceInputValues=true` (used by ui/ssePayloadJson) would
    // intercept JSON null and coerce it to the field default BEFORE the
    // serializer runs, collapsing Cleared → Omitted. The slimapi digest
    // decoder therefore MUST NOT use ssePayloadJson; later wiring tasks
    // (T2/T3) need to ensure the digest path uses a Json instance
    // without coerceInputValues. Tracked as a concern in the task-1
    // report.
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    // ── T1-C1: Part.hasFull / omitted ─────────────────────────────────────

    @Test
    fun `Part with hasFull and omitted round-trips`() {
        val original = Part(
            id = "p1",
            type = "text",
            text = "hello",
            hasFull = true,
            omitted = listOf("tool-1", "tool-2")
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<Part>(encoded)

        assertEquals(original.id, decoded.id)
        assertEquals(true, decoded.hasFull)
        assertEquals(listOf("tool-1", "tool-2"), decoded.omitted)
    }

    @Test
    fun `Part hasFull and omitted default to null when absent`() {
        // Legacy opencode / pre-v1 slimapi response: no `hasFull` / `omitted`
        // keys. explicitNulls=false + null defaults → both fields stay null
        // and the response decodes cleanly.
        val decoded = json.decodeFromString<Part>(
            """{"id":"p1","type":"text","text":"hi"}"""
        )

        assertNull(decoded.hasFull)
        assertNull(decoded.omitted)
        assertEquals("p1", decoded.id)
    }

    // ── T1-C2: SlimSessionDigest.lastError three states ───────────────────

    @Test
    fun `lastError omitted when key absent`() {
        val digest = json.decodeFromString<SlimSessionDigest>(
            """
            {
              "sessionID": "s1",
              "status": "idle"
            }
            """
        )

        assertEquals("s1", digest.sessionId)
        assertEquals(LastErrorField.Omitted, digest.lastError)
    }

    @Test
    fun `lastError cleared when JSON null`() {
        val digest = json.decodeFromString<SlimSessionDigest>(
            """
            {
              "sessionID": "s1",
              "lastError": null
            }
            """
        )

        assertEquals("s1", digest.sessionId)
        assertEquals(LastErrorField.Cleared, digest.lastError)
    }

    @Test
    fun `lastError set when JSON object`() {
        val digest = json.decodeFromString<SlimSessionDigest>(
            """
            {
              "sessionID": "s1",
              "lastError": {
                "name": "UpstreamTimeout",
                "message": "upstream timed out after 30s",
                "at": 1718700000000
              }
            }
            """
        )

        assertEquals("s1", digest.sessionId)
        val set = digest.lastError as LastErrorField.Set
        assertEquals("UpstreamTimeout", set.error.name)
        assertEquals("upstream timed out after 30s", set.error.message)
        assertEquals(1718700000000L, set.error.at)
    }

    // ── T1-C3: G6 batch envelope ──────────────────────────────────────────

    @Test
    fun `SlimapiMessageFullBatch parses items and errors`() {
        val envelope = json.decodeFromString<SlimapiMessageFullBatch>(
            """
            {
              "items": [
                {
                  "info": {
                    "id": "msg-1",
                    "role": "assistant",
                    "sessionID": "s1"
                  },
                  "parts": [
                    {"id": "p1", "type": "text", "text": "hi", "hasFull": true, "omitted": ["x"]}
                  ]
                }
              ],
              "errors": [
                {"messageID": "msg_missing", "code": "message_not_found"}
              ]
            }
            """
        )

        assertEquals(1, envelope.items.size)
        assertEquals("msg-1", envelope.items[0].info.id)
        assertEquals(1, envelope.items[0].parts.size)
        assertEquals(true, envelope.items[0].parts[0].hasFull)
        assertEquals(listOf("x"), envelope.items[0].parts[0].omitted)

        assertEquals(1, envelope.errors.size)
        assertEquals("msg_missing", envelope.errors[0].messageId)
        assertEquals("message_not_found", envelope.errors[0].code)
    }

    // ── T1-C4: error codes + SlimapiResyncReason ──────────────────────────

    @Test
    fun `SlimapiErrorCodes constants match contract values verbatim`() {
        // §0 thin-route error code shapes + §6 status routing + §5 G6 errors.
        assertEquals("session_not_found", SlimapiErrorCodes.SESSION_NOT_FOUND)
        assertEquals("directory_not_allowed", SlimapiErrorCodes.DIRECTORY_NOT_ALLOWED)
        assertEquals("upstream_unavailable", SlimapiErrorCodes.UPSTREAM_UNAVAILABLE)
        assertEquals("upstream_http_", SlimapiErrorCodes.UPSTREAM_HTTP_PREFIX)
        assertEquals("upstream_timeout", SlimapiErrorCodes.UPSTREAM_TIMEOUT)
        assertEquals("thin_route_not_found", SlimapiErrorCodes.THIN_ROUTE_NOT_FOUND)
        assertEquals("invalid_ids", SlimapiErrorCodes.INVALID_IDS)
        assertEquals("response_too_large", SlimapiErrorCodes.RESPONSE_TOO_LARGE)
        assertEquals("transform_busy", SlimapiErrorCodes.TRANSFORM_BUSY)
        assertEquals("message_not_found", SlimapiErrorCodes.MESSAGE_NOT_FOUND)
        // 🆕 B1 §2 additive codes (b1-foldin T1 amendment): pin the exact
        // snake_case strings the sidecar emits per contract §7 code table.
        assertEquals("message_too_large", SlimapiErrorCodes.MESSAGE_TOO_LARGE)
        assertEquals("shell_not_allowed", SlimapiErrorCodes.SHELL_NOT_ALLOWED)
        assertEquals("invalid_directory_count", SlimapiErrorCodes.INVALID_DIRECTORY_COUNT)
        assertEquals("invalid_route_token", SlimapiErrorCodes.INVALID_ROUTE_TOKEN)
    }

    @Test
    fun `SlimapiResyncReason fromRaw maps wire values and rejects unknown`() {
        // §3 resync reason — three legal wire values + null/unknown → null.
        assertEquals(
            SlimapiResyncReason.RECONNECT_NO_REPLAY,
            SlimapiResyncReason.fromRaw("reconnect_no_replay")
        )
        assertEquals(
            SlimapiResyncReason.SUBSCRIBER_BACKPRESSURE,
            SlimapiResyncReason.fromRaw("subscriber_backpressure")
        )
        assertEquals(
            SlimapiResyncReason.IMPLICIT,
            SlimapiResyncReason.fromRaw("implicit")
        )
        assertNull(SlimapiResyncReason.fromRaw(null))
        assertNull(SlimapiResyncReason.fromRaw("unknown_future_value"))
    }

    @Test
    fun `SlimapiResyncReason SerialName round-trips through the enum`() {
        // Round-trip every variant — pins @SerialName values to the wire
        // strings the sidecar emits.
        for (reason in SlimapiResyncReason.entries) {
            val encoded = json.encodeToString(reason)
            val decoded = json.decodeFromString<SlimapiResyncReason>(encoded)
            assertEquals(reason, decoded)
        }
        // Sanity: encoded form is the wire string wrapped in quotes.
        val wire = json.encodeToString(SlimapiResyncReason.IMPLICIT)
        assertTrue("expected wire string, got $wire", wire.contains("implicit"))
    }
}
