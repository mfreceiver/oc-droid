package cn.vectory.ocdroid.data.api

import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cluster A (slim SSE): wire-level tests for [SSEClient] slimMode switching
 * + the B1 fix for sidecar-native `event:`-typed frames.
 *
 * Drives the client against a MockWebServer and asserts:
 *  - slimMode=true connects to `/slimapi/events` with NO `X-Opencode-Directory`
 *    header (instance-level; v1 contract §3).
 *  - slimMode=false preserves the legacy `/global/event` path + optional
 *    directory header byte-for-byte (regression guard).
 *  - **B1 fix**: sidecar-native `event:`-typed frames (digest / resync /
 *    server.connected / server.heartbeat) parse via the new
 *    `parseSseEvent(data, eventType)` path — `data:` carries ONLY field
 *    values, the type lives in the SSE `event:` field.
 *  - q/p flat pass-through frames (no `event:` line, `type` inside JSON)
 *    still parse via the flat path — regression guard for the q-p lane.
 *  - Legacy `{payload:{type, properties}}` wrapping still parses.
 *
 * Wire format reference (oc-slimapi hub.py, post-B1):
 *   event: session.digest
 *   data: {"sessionID":"...","updatedAt":1000,"directory":"/w",...}
 *
 *   event: resync
 *   data: {"reason":"reconnect_no_replay"}
 *
 *   event: server.connected
 *   data: {}
 *
 *   data: {"directory":"/w","type":"question.asked","properties":{...}}
 *   (q/p pass-through — no `event:` line; type embedded in JSON)
 */
class SSEClientSlimModeTest {

    private val server = MockWebServer()
    private lateinit var client: OkHttpClient
    private lateinit var sse: SSEClient

    @Before
    fun setUp() {
        DebugLog.clear()
        server.start()
        client = OkHttpClient.Builder().build()
        sse = SSEClient(client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sseResponse(vararg frames: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(frames.joinToString(separator = "\n\n", postfix = "\n\n"))

    /** Single `data:` line (q/p flat pass-through — no `event:` field). */
    private fun dataFrame(json: String): String = "data: $json"

    /**
     * Sidecar-native typed frame: `event:` line + `data:` line. OkHttp's
     * `RealEventSource` parses the `event:` field and passes it as the
     * `type` argument to `onEvent`.
     */
    private fun typedFrame(eventType: String, data: String): String =
        "event: $eventType\ndata: $data"

    // ── URL & header switching ─────────────────────────────────────────────

    @Test
    fun `slimMode true connects to slimapi events path without directory header`() = runBlocking {
        // Use the real sidecar wire format for the liveness probe frame
        // (post-B1 the parser MUST accept this; pre-B1 it would skip →
        // transport-ready would never complete → DOA).
        val frame = typedFrame("server.connected", "{}")
        server.enqueue(sseResponse(frame))

        withTimeout(5_000) {
            sse.connect(
                baseUrl = server.url("/").toString().trimEnd('/'),
                slimMode = true,
            ).first { it.isSuccess }
        }

        val request = server.takeRequest()
        assertEquals(
            "slim mode MUST hit /slimapi/events",
            "/slimapi/events",
            request.path,
        )
        assertNull(
            "slim SSE is instance-level — directory header MUST NOT be sent",
            request.getHeader("X-Opencode-Directory")
        )
    }

    @Test
    fun `slimMode true ignores directory argument`() = runBlocking {
        val frame = typedFrame("server.connected", "{}")
        server.enqueue(sseResponse(frame))

        withTimeout(5_000) {
            sse.connect(
                baseUrl = server.url("/").toString().trimEnd('/'),
                directory = "/workdir/project",  // MUST be ignored in slim mode
                slimMode = true,
            ).first { it.isSuccess }
        }

        val request = server.takeRequest()
        assertEquals("/slimapi/events", request.path)
        assertNull(
            "directory header MUST NOT appear in slim mode even when arg is non-null",
            request.getHeader("X-Opencode-Directory")
        )
        assertNull(
            "?directory query MUST NOT appear in slim mode",
            request.requestUrl?.queryParameter("directory")
        )
    }

    @Test
    fun `slimMode false preserves legacy global event path`() = runBlocking {
        val payload = """{"payload":{"type":"server.connected"}}"""
        server.enqueue(sseResponse(dataFrame(payload)))

        withTimeout(5_000) {
            sse.connect(
                baseUrl = server.url("/").toString().trimEnd('/'),
                directory = null,
                slimMode = false,
            ).first { it.isSuccess }
        }

        val request = server.takeRequest()
        assertEquals(
            "legacy mode MUST hit /global/event (regression guard)",
            "/global/event",
            request.path,
        )
    }

    @Test
    fun `slimMode false with directory sends header as before`() = runBlocking {
        val payload = """{"payload":{"type":"server.connected"}}"""
        server.enqueue(sseResponse(dataFrame(payload)))

        withTimeout(5_000) {
            sse.connect(
                baseUrl = server.url("/").toString().trimEnd('/'),
                directory = "/workdir",
                slimMode = false,
            ).first { it.isSuccess }
        }

        val request = server.takeRequest()
        assertEquals("/global/event", request.path)
        assertEquals(
            "/workdir",
            request.getHeader("X-Opencode-Directory"),
        )
    }

    // ── B1 fix: sidecar-native `event:`-typed frames ───────────────────────
    //
    // These frames carry the type in the SSE `event:` field and ONLY field
    // values in `data:` (no `type` key inside JSON). Pre-B1 the parser fell
    // to the `error()` branch and skipped every such frame, breaking
    // transport-ready, digest, and resync simultaneously.

    @Test
    fun `B1 fix - session dot digest event-typed frame parses with type from event field`() = runBlocking {
        // Real wire format: type in `event:`, data has only digest fields.
        val frame = typedFrame(
            eventType = "session.digest",
            data = """{"sessionID":"s1","directory":"/w","updatedAt":1000}"""
        )
        server.enqueue(sseResponse(frame))

        val event = withTimeout(5_000) {
            sse.connect(server.url("/").toString().trimEnd('/'), slimMode = true)
                .first { it.isSuccess }
                .getOrThrow()
        }

        assertEquals("session.digest", event.payload.type)
        // Top-level directory extracted from data.
        assertEquals("/w", event.directory)
        // B1 synthesis: the WHOLE data object is properties, so digest
        // fields (sessionID, updatedAt) are reachable for the reducer.
        assertNotNull("properties MUST be non-null (B1 synthesis)", event.payload.properties)
        assertEquals("s1", event.payload.getString("sessionID"))
        // updatedAt is a JSON number; getString returns the raw content.
        assertEquals("1000", event.payload.getString("updatedAt"))
    }

    @Test
    fun `B1 fix - digest with only status field parses`() = runBlocking {
        // Debounced digest: only the changed field is present.
        val frame = typedFrame(
            eventType = "session.digest",
            data = """{"sessionID":"s2","status":"busy"}"""
        )
        server.enqueue(sseResponse(frame))

        val event = withTimeout(5_000) {
            sse.connect(server.url("/").toString().trimEnd('/'), slimMode = true)
                .first { it.isSuccess }
                .getOrThrow()
        }

        assertEquals("session.digest", event.payload.type)
        assertEquals("s2", event.payload.getString("sessionID"))
        assertEquals("busy", event.payload.getString("status"))
        // Fields absent from this digest are simply absent from properties
        // (the reducer treats absent as no-change).
        assertNull(event.payload.getString("updatedAt"))
    }

    @Test
    fun `B1 fix - resync event-typed frame parses with reason in properties`() = runBlocking {
        val frame = typedFrame(
            eventType = "resync",
            data = """{"reason":"reconnect_no_replay"}"""
        )
        server.enqueue(sseResponse(frame))

        val event = withTimeout(5_000) {
            sse.connect(server.url("/").toString().trimEnd('/'), slimMode = true)
                .first { it.isSuccess }
                .getOrThrow()
        }

        assertEquals("resync", event.payload.type)
        assertEquals(
            "reconnect_no_replay",
            event.payload.getString("reason"),
        )
    }

    @Test
    fun `B1 fix - server connected event-typed frame with empty data parses`() = runBlocking {
        val frame = typedFrame("server.connected", "{}")
        server.enqueue(sseResponse(frame))

        val event = withTimeout(5_000) {
            sse.connect(server.url("/").toString().trimEnd('/'), slimMode = true)
                .first { it.isSuccess }
                .getOrThrow()
        }

        assertEquals("server.connected", event.payload.type)
        // Empty data → properties is an empty JsonObject (not null).
        assertNotNull(event.payload.properties)
        assertTrue(
            "empty data yields empty properties",
            event.payload.properties!!.isEmpty()
        )
    }

    @Test
    fun `B1 fix - server heartbeat event-typed frame parses`() = runBlocking {
        val frame = typedFrame("server.heartbeat", "{}")
        server.enqueue(sseResponse(frame))

        val event = withTimeout(5_000) {
            sse.connect(server.url("/").toString().trimEnd('/'), slimMode = true)
                .first { it.isSuccess }
                .getOrThrow()
        }

        assertEquals("server.heartbeat", event.payload.type)
        // No sessionID in heartbeat data.
        assertNull(event.payload.getString("sessionID"))
    }

    @Test
    fun `B1 fix - transport-ready completes on event-typed server connected`() = runBlocking {
        // This is the regression for the DOA symptom: pre-B1 the first
        // server.connected frame was skipped (no `type` key in `{}`), so
        // transport-ready never completed within 30s → slim profile hung.
        // Post-B1: parse succeeds → frame emitted → readiness completes.
        val frame = typedFrame("server.connected", "{}")
        server.enqueue(sseResponse(frame))

        val event = withTimeout(5_000) {
            // .first { it.isSuccess } completing within 5s proves the
            // frame was emitted (not skipped) — pre-B1 this would time out.
            sse.connect(server.url("/").toString().trimEnd('/'), slimMode = true)
                .first { it.isSuccess }
                .getOrThrow()
        }

        assertEquals("server.connected", event.payload.type)
    }

    @Test
    fun `B1 fix - event-typed digest and flat q-p frames interleave correctly`() = runBlocking {
        // Real-world interleaving on the slim SSE feed: lifecycle frames
        // use `event:` typing, q/p pass-through frames are flat. Both must
        // parse correctly in the same connection.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    typedFrame("server.connected", "{}") + "\n\n" +
                        typedFrame(
                            "session.digest",
                            """{"sessionID":"s1","updatedAt":100}"""
                        ) + "\n\n" +
                        dataFrame(
                            """{"directory":"/w","type":"question.asked","properties":{"q":"a"}}"""
                        ) + "\n\n" +
                        typedFrame("server.heartbeat", "{}") + "\n\n"
                )
        )

        val events = withTimeout(5_000) {
            sse.connect(server.url("/").toString().trimEnd('/'), slimMode = true)
                .take(4)
                .toList()
                .map { it.getOrThrow() }
        }

        assertEquals(4, events.size)
        // Lifecycle (event-typed):
        assertEquals("server.connected", events[0].payload.type)
        assertEquals("session.digest", events[1].payload.type)
        assertEquals("s1", events[1].payload.getString("sessionID"))
        // q/p (flat — type embedded in JSON):
        assertEquals("question.asked", events[2].payload.type)
        assertEquals("/w", events[2].directory)
        assertEquals("a", events[2].payload.getString("q"))
        // Heartbeat (event-typed):
        assertEquals("server.heartbeat", events[3].payload.type)
    }

    // ── Flat / wrapping / malformed (non-event-typed) ──────────────────────
    //
    // These were the pre-B1 test shapes; they still MUST work because the
    // sidecar's q/p pass-through frames are flat (no `event:` field).

    @Test
    fun `slim flat envelope without wrapping payload parses successfully`() = runBlocking {
        // q/p-style flat frame — type embedded in JSON, no `event:` line.
        val payload = """{"directory":"/w","type":"session.digest","properties":{"sessionID":"s1","status":"busy","updatedAt":1000}}"""
        server.enqueue(sseResponse(dataFrame(payload)))

        val event = withTimeout(5_000) {
            sse.connect(server.url("/").toString().trimEnd('/'), slimMode = true)
                .first { it.isSuccess }
                .getOrThrow()
        }

        assertEquals("session.digest", event.payload.type)
        assertEquals("/w", event.directory)
        assertEquals("s1", event.payload.getString("sessionID"))
        assertEquals("busy", event.payload.getString("status"))
    }

    @Test
    fun `slim wrapping payload envelope also parses`() = runBlocking {
        // Defensive: if the sidecar DOES wrap under `payload`, the parser
        // must still produce a valid SSEEvent (legacy shape).
        val payload = """{"directory":"/w","payload":{"type":"question.asked","properties":{"foo":"bar"}}}"""
        server.enqueue(sseResponse(dataFrame(payload)))

        val event = withTimeout(5_000) {
            sse.connect(server.url("/").toString().trimEnd('/'), slimMode = true)
                .first { it.isSuccess }
                .getOrThrow()
        }

        assertEquals("question.asked", event.payload.type)
        assertEquals("/w", event.directory)
        assertEquals("bar", event.payload.getString("foo"))
    }

    @Test
    fun `flat frame without type AND without event line is skipped`() = runBlocking {
        // B1 distinction: a flat frame (no `event:` line) whose data has
        // no `type` key is TRULY malformed — the parser MUST skip it.
        // First frame: malformed flat (`{"directory":"/w"}` — no type, no event).
        // Second frame: valid flat (`{"type":"server.heartbeat"}`).
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"directory\":\"/w\"}\n\n" +
                        "data: {\"type\":\"server.heartbeat\"}\n\n"
                )
        )

        val event = withTimeout(5_000) {
            sse.connect(server.url("/").toString().trimEnd('/'), slimMode = true)
                .first { it.isSuccess }
                .getOrThrow()
        }

        assertEquals("server.heartbeat", event.payload.type)
    }

    @Test
    fun `B1 fix - event-typed frame without type in data is NOT skipped`() = runBlocking {
        // B1 mirror: the symmetric case to the test above. A frame WITH an
        // `event:` line and data WITHOUT a `type` key MUST parse (this is
        // exactly the sidecar's digest/resync/server.* shape). Pre-B1 this
        // was incorrectly skipped.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "event: session.digest\n" +
                        "data: {\"sessionID\":\"s1\",\"updatedAt\":42}\n\n" +
                        "event: server.heartbeat\n" +
                        "data: {}\n\n"
                )
        )

        val events = withTimeout(5_000) {
            sse.connect(server.url("/").toString().trimEnd('/'), slimMode = true)
                .take(2)
                .toList()
                .map { it.getOrThrow() }
        }

        assertEquals("session.digest", events[0].payload.type)
        assertEquals("s1", events[0].payload.getString("sessionID"))
        assertEquals("server.heartbeat", events[1].payload.type)
    }

    @Test
    fun `B1 fix - legacy global event path still uses payload wrapping`() = runBlocking {
        // Regression guard: the legacy /global/event path emits frames with
        // `{payload:{type, properties}}` wrapping (no `event:` field). The
        // B1 fix MUST NOT break this — the fast-path branch still handles it.
        val payload = """{"payload":{"type":"server.connected","properties":{"sessionID":"s1"}}}"""
        server.enqueue(sseResponse(dataFrame(payload)))

        val event = withTimeout(5_000) {
            sse.connect(
                baseUrl = server.url("/").toString().trimEnd('/'),
                slimMode = false,
            ).first { it.isSuccess }
                .getOrThrow()
        }

        assertEquals("server.connected", event.payload.type)
        assertEquals("s1", event.payload.getString("sessionID"))
    }
}
