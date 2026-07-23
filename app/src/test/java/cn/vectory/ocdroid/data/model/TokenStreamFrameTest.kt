package cn.vectory.ocdroid.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §Stage-C §3.2 — pins the [TokenStreamFrame.parse] contract:
 *  - each known event type maps to the correct frame subclass + field mapping;
 *  - unknown event strings → null (forward compatibility);
 *  - malformed JSON / missing required fields → null (never throw);
 *  - `server.heartbeat` → [TokenStreamFrame.ServerHeartbeat] (data ignored).
 *
 * Wire keys are the literal sidecar camelCase-with-capital-suffix forms
 * (`sessionID` / `messageID` / `partID`); this test asserts the parser reads
 * them verbatim and does NOT expect idiomatic `sessionId` etc.
 */
class TokenStreamFrameTest {

    // ── event-type mapping ────────────────────────────────────────────────

    @Test
    fun `server_connected maps with sessionID field`() {
        val frame = TokenStreamFrame.parse(
            "server.connected",
            """{"sessionID":"sess-1"}""",
        )
        assertEquals(TokenStreamFrame.ServerConnected("sess-1"), frame)
    }

    @Test
    fun `server_heartbeat maps regardless of data content`() {
        // Empty data (comment-style keep-alive).
        assertEquals(
            TokenStreamFrame.ServerHeartbeat,
            TokenStreamFrame.parse("server.heartbeat", ""),
        )
        // Non-JSON data — still a heartbeat (recognized by event only).
        assertEquals(
            TokenStreamFrame.ServerHeartbeat,
            TokenStreamFrame.parse("server.heartbeat", ": ping"),
        )
        // Even a JSON body is ignored for heartbeat.
        assertEquals(
            TokenStreamFrame.ServerHeartbeat,
            TokenStreamFrame.parse("server.heartbeat", """{"ignored":true}"""),
        )
    }

    @Test
    fun `part_snapshot maps all fields with done=false truncated=false defaults`() {
        val frame = TokenStreamFrame.parse(
            "message.part.snapshot",
            """{"sessionID":"s1","messageID":"m1","partID":"p1","text":"hello","done":false,"truncated":false}""",
        ) as TokenStreamFrame.PartSnapshot
        assertEquals("s1", frame.sessionId)
        assertEquals("m1", frame.messageId)
        assertEquals("p1", frame.partId)
        assertEquals("hello", frame.text)
        assertEquals(false, frame.done)
        assertEquals(false, frame.truncated)
    }

    @Test
    fun `part_snapshot omits done and truncated fields and they default to false`() {
        val frame = TokenStreamFrame.parse(
            "message.part.snapshot",
            """{"sessionID":"s1","messageID":"m1","partID":"p1","text":"hi"}""",
        ) as TokenStreamFrame.PartSnapshot
        assertEquals("hi", frame.text)
        assertEquals(false, frame.done)
        assertEquals(false, frame.truncated)
    }

    @Test
    fun `part_snapshot done=true and truncated=true parsed verbatim`() {
        val done = TokenStreamFrame.parse(
            "message.part.snapshot",
            """{"sessionID":"s1","messageID":"m1","partID":"p1","text":"final","done":true}""",
        ) as TokenStreamFrame.PartSnapshot
        assertTrue(done.done)
        assertEquals(false, done.truncated)

        val trunc = TokenStreamFrame.parse(
            "message.part.snapshot",
            """{"sessionID":"s1","messageID":"m1","partID":"p1","truncated":true}""",
        ) as TokenStreamFrame.PartSnapshot
        assertTrue(trunc.truncated)
        assertEquals(false, trunc.done)
    }

    @Test
    fun `part_snapshot text null yields null text`() {
        val frame = TokenStreamFrame.parse(
            "message.part.snapshot",
            """{"sessionID":"s1","messageID":"m1","partID":"p1","text":null}""",
        ) as TokenStreamFrame.PartSnapshot
        assertNull(frame.text)
    }

    @Test
    fun `part_delta maps all fields`() {
        val frame = TokenStreamFrame.parse(
            "message.part.delta",
            """{"sessionID":"s1","messageID":"m1","partID":"p1","text":" world"}""",
        ) as TokenStreamFrame.PartDelta
        assertEquals("s1", frame.sessionId)
        assertEquals("m1", frame.messageId)
        assertEquals("p1", frame.partId)
        assertEquals(" world", frame.text)
    }

    @Test
    fun `resync maps reason and optional sessionID`() {
        val withSid = TokenStreamFrame.parse(
            "resync",
            """{"reason":"reconnect_no_replay","sessionID":"s1"}""",
        ) as TokenStreamFrame.Resync
        assertEquals(ResyncReason.RECONNECT_NO_REPLAY, withSid.reason)
        assertEquals("s1", withSid.sessionId)

        val noSid = TokenStreamFrame.parse(
            "resync",
            """{"reason":"part_too_large"}""",
        ) as TokenStreamFrame.Resync
        assertEquals(ResyncReason.PART_TOO_LARGE, noSid.reason)
        assertNull(noSid.sessionId)
    }

    // ── forward compatibility: unknown / malformed → null ────────────────

    @Test
    fun `unknown event string yields null`() {
        assertNull(TokenStreamFrame.parse("brand.new.event", """{"foo":"bar"}"""))
    }

    @Test
    fun `null event yields null`() {
        assertNull(TokenStreamFrame.parse(null, """{"foo":"bar"}"""))
    }

    @Test
    fun `malformed JSON yields null not exception`() {
        assertNull(TokenStreamFrame.parse("message.part.delta", "{not json"))
        assertNull(TokenStreamFrame.parse("server.connected", ""))
    }

    @Test
    fun `non-object JSON root yields null`() {
        assertNull(TokenStreamFrame.parse("message.part.delta", """["array"]"""))
        assertNull(TokenStreamFrame.parse("server.connected", """"string""""))
    }

    @Test
    fun `snapshot missing required partID yields null`() {
        assertNull(
            TokenStreamFrame.parse(
                "message.part.snapshot",
                """{"sessionID":"s1","messageID":"m1","text":"x"}""",
            )
        )
    }

    @Test
    fun `delta missing required text yields null`() {
        assertNull(
            TokenStreamFrame.parse(
                "message.part.delta",
                """{"sessionID":"s1","messageID":"m1","partID":"p1"}""",
            )
        )
    }

    @Test
    fun `resync with unknown reason wire value yields null`() {
        assertNull(
            TokenStreamFrame.parse("resync", """{"reason":"some_future_reason"}"""),
        )
    }

    @Test
    fun `snapshot tolerates unknown extra fields (ignoreUnknownKeys)`() {
        val frame = TokenStreamFrame.parse(
            "message.part.snapshot",
            """{"sessionID":"s1","messageID":"m1","partID":"p1","text":"x","futureField":42}""",
        )
        assertTrue(frame is TokenStreamFrame.PartSnapshot)
    }

    // ── ResyncReason taxonomy ─────────────────────────────────────────────

    @Test
    fun `resync reason wire values round-trip via fromWire`() {
        assertEquals(ResyncReason.RECONNECT_NO_REPLAY, ResyncReason.fromWire("reconnect_no_replay"))
        assertEquals(ResyncReason.SUBSCRIBER_BACKPRESSURE, ResyncReason.fromWire("subscriber_backpressure"))
        assertEquals(ResyncReason.PART_TOO_LARGE, ResyncReason.fromWire("part_too_large"))
        assertEquals(ResyncReason.TOKEN_MEMORY_LIMIT, ResyncReason.fromWire("token_memory_limit"))
    }

    @Test
    fun `resync reason fromWire unknown and null yield null`() {
        assertNull(ResyncReason.fromWire("nope"))
        assertNull(ResyncReason.fromWire(null))
    }

    @Test
    fun `triggersReconnect only true for reconnect_no_replay and subscriber_backpressure`() {
        assertTrue(ResyncReason.RECONNECT_NO_REPLAY.triggersReconnect)
        assertTrue(ResyncReason.SUBSCRIBER_BACKPRESSURE.triggersReconnect)
        assertEquals(false, ResyncReason.PART_TOO_LARGE.triggersReconnect)
        assertEquals(false, ResyncReason.TOKEN_MEMORY_LIMIT.triggersReconnect)
    }
}
