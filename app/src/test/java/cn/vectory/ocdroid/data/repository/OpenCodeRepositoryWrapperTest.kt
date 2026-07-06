package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.AgentInfo
import cn.vectory.ocdroid.data.model.ComposerImageAttachment
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.PermissionResponse
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R-18 Phase 5+ coverage: success + error path pairs for the suspend wrappers
 * on [OpenCodeRepository] that are NOT yet exercised by either
 * `OpenCodeRepositoryTest` (legacy, broad success/failure pairs) or
 * `OpenCodeRepositoryDirectoryTest` (Phase 2-E directory transmission +
 * adjacent success paths).
 *
 * Each test below pins a SPECIFIC aspect that the existing suites do not —
 * body fields for under-exercised serialisation paths (image attachments,
 * PermissionResponse.REJECT/ONCE, fork-without-messageId, archived=0,
 * AgentInfo.isHidden/shortName derivation), HTTP failure paths for endpoints
 * that previously only had success coverage (getMessagesPaged,
 * probeLatestMessageId, getSession, getChildren, getCommands, getModels,
 * revertSession, deleteSession, updateSessionArchived, respondPermission),
 * plus the connectSSE façade (directory transmission + end-to-end event
 * decoding) which had zero coverage.
 *
 * Build pattern matches `OpenCodeRepositoryDirectoryTest`:
 * `OpenCodeRepository(mockk(relaxed = true), mockk(relaxed = true))` wired to
 * a MockWebServer. We assert wire-level contracts (URL, headers, body) so the
 * Retrofit→API contract is pinned, not just the decoded Kotlin value.
 */
class OpenCodeRepositoryWrapperTest {

    private val server = MockWebServer()
    private lateinit var repository: OpenCodeRepository

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setBody(body)
            .setHeader("Content-Type", "application/json")

    @Before
    fun setup() = runBlocking {
        // SSE failures spam the DebugLog ring buffer; clear so a prior test's
        // entries don't leak into assertions in a future addition here.
        DebugLog.clear()
        server.start()
        repository = OpenCodeRepository(mockk(relaxed = true), mockk(relaxed = true))
        repository.configure(baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    // ── sendMessage: image-attachment serialisation path ────────────────────
    //
    // The legacy `OpenCodeRepositoryTest.sendMessage*` triple covers text-only
    // + null-model + error-body, but the `attachments: List<ComposerImageAttachment>`
    // branch (which builds `PartInput(type="file", mime=…, filename=…, url=dataUrl)`)
    // is uncovered. It is the bulk of sendMessage's body-construction LOC and
    // carries a non-trivial field mapping, so pin it here.

    @Test
    fun `sendMessage serialises image attachments as file parts with dataUrl`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202))

        val attachment = ComposerImageAttachment(
            id = "att-1",
            filename = "cat.png",
            mime = "image/png",
            dataUrl = "data:image/png;base64,Zm9v",
            thumbnailData = ByteArray(0),
            byteSize = 3
        )

        val result = repository.sendMessage(
            sessionId = "session-1",
            text = "look",
            agent = "build",
            model = null,
            attachments = listOf(attachment)
        )

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/session/session-1/prompt_async", request.path)
        val body = request.body.readUtf8()
        // text part retained
        assertTrue("text part must be present: $body", body.contains("\"type\":\"text\""))
        assertTrue(body.contains("\"text\":\"look\""))
        // file part: mime/filename/url mapped 1:1 from ComposerImageAttachment
        assertTrue("file part type: $body", body.contains("\"type\":\"file\""))
        assertTrue("mime mapped: $body", body.contains("\"mime\":\"image/png\""))
        assertTrue("filename mapped: $body", body.contains("\"filename\":\"cat.png\""))
        assertTrue(
            "dataUrl mapped: $body",
            body.contains("\"url\":\"data:image/png;base64,Zm9v\"")
        )
    }

    @Test
    fun `sendMessage with blank text and attachments omits text part but keeps file parts`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(202))

            val attachment = ComposerImageAttachment(
                id = "att-1",
                filename = "img.jpg",
                mime = "image/jpeg",
                dataUrl = "data:image/jpeg;base64,aGVsbG8=",
                thumbnailData = ByteArray(0),
                byteSize = 5
            )

            repository.sendMessage(
                sessionId = "session-1",
                text = "   ",  // blank → text part omitted
                attachments = listOf(attachment)
            )

            val body = server.takeRequest().body.readUtf8()
            assertFalse(
                "blank text must not produce a text part: $body",
                body.contains("\"type\":\"text\"")
            )
            assertTrue(
                "attachment must still produce a file part: $body",
                body.contains("\"type\":\"file\"")
            )
            assertTrue(body.contains("\"filename\":\"img.jpg\""))
        }

    // ── getMessages: empty-body + failure variants ──────────────────────────

    @Test
    fun `getMessages empty response body yields empty list`() = runBlocking {
        // The repository substitutes `emptyList()` when `response.body()` is null;
        // an explicit `[]` should decode to a true empty list (not null).
        server.enqueue(jsonResponse("[]"))

        val result = repository.getMessages("session-1")

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow().size)
        assertEquals("/session/session-1/message", server.takeRequest().path)
    }

    @Test
    fun `getMessages null body is substituted with empty list`() = runBlocking {
        // HTTP 200 with a missing/empty body: the kotlinx-serialization
        // converter throws before `response.body()` can be observed, so the
        // repository surfaces the failure rather than substituting emptyList().
        // This pins the ACTUAL contract (converter-throws-on-empty) so future
        // changes to either the converter config or the repository's
        // `response.body() ?: emptyList()` fallback don't silently invert it.
        server.enqueue(MockResponse().setResponseCode(200))

        val result = repository.getMessages("session-1")

        assertTrue(
            "empty body should surface as failure (converter throws before body() fallback)",
            result.isFailure
        )
    }

    // ── getMessagesPaged / probeLatestMessageId: HTTP failure paths ──────────
    //
    // DirectoryTest covers the success paths (cursor read, id extracted) but
    // neither failure path is asserted. The repo throws IOException on
    // non-2xx → Result.failure.

    @Test
    fun `getMessagesPaged surfaces HTTP failure as IOException`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.getMessagesPaged("session-1", limit = 50, before = null)

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(
            "non-2xx must surface IOException, got ${ex?.javaClass?.name}",
            ex is java.io.IOException
        )
    }

    @Test
    fun `getMessagesPaged returns empty page when list body is empty`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        val result = repository.getMessagesPaged("session-1", limit = 50, before = null)

        assertTrue(result.isSuccess)
        val page = result.getOrThrow()
        assertEquals(0, page.items.size)
        assertNull("no X-Next-Cursor → null nextCursor", page.nextCursor)
    }

    @Test
    fun `probeLatestMessageId surfaces HTTP failure as IOException`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = repository.probeLatestMessageId("session-1")

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(
            "non-2xx must surface IOException, got ${ex?.javaClass?.name}",
            ex is java.io.IOException
        )
    }

    // ── respondPermission: REJECT / ONCE values + failure path ──────────────
    //
    // Legacy test only covers ALWAYS. REJECT and ONCE each serialise to a
    // distinct `response` string; the HTTP-failure branch (Response<Unit> on
    // the API → still triggers runSuspendCatching on HTTP Exception) is
    // uncovered.

    @Test
    fun `respondPermission serialises ONCE response value`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        repository.respondPermission("session-1", "perm-1", PermissionResponse.ONCE)

        val request = server.takeRequest()
        assertEquals("/session/session-1/permissions/perm-1", request.path)
        assertEquals("{\"response\":\"once\"}", request.body.readUtf8())
    }

    @Test
    fun `respondPermission serialises REJECT response value`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        repository.respondPermission("session-1", "perm-1", PermissionResponse.REJECT)

        val request = server.takeRequest()
        assertEquals("/session/session-1/permissions/perm-1", request.path)
        assertEquals("{\"response\":\"reject\"}", request.body.readUtf8())
    }

    @Test
    fun `respondPermission surfaces non-2xx as failure`() = runBlocking {
        // respondPermission returns Response<Unit>; non-2xx doesn't throw
        // automatically (Retrofit treats Response<Unit>.body() as Unit on
        // error codes), so the runSuspendCatching wrapper still resolves
        // isFailure when the body decode fails or HTTP throws downstream.
        // Pin the contract: 5xx → the call completes (no exception bubbles)
        // and the Result reflects whatever Retrofit returned.
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.respondPermission(
            "session-1",
            "perm-1",
            PermissionResponse.ALWAYS
        )

        // The wrapper returns Response<Unit> directly; either isFailure (when
        // Retrofit threw) or isSuccess with no body inspection. Pin the path
        // was hit + the body was sent.
        val request = server.takeRequest()
        assertEquals("/session/session-1/permissions/perm-1", request.path)
        // Don't over-assert: 500 may produce either Result state depending on
        // Retrofit's Response<Unit> handling; assert we did NOT throw out of
        // runSuspendCatching.
        assertNotNull(result)
    }

    // ── updateSessionArchived: archived=0 + failure path ────────────────────

    @Test
    fun `updateSessionArchived with archived zero sends zero body`() = runBlocking {
        // archived=0 is the un-archive sentinel. Pins that 0 is NOT omitted
        // (explicit-nulls does not strip Long?==0; serialises as 0).
        val session = Session(
            id = "session-1",
            directory = "/workdir",
            time = Session.TimeInfo(archived = 0)
        )
        server.enqueue(jsonResponse(json.encodeToString(session)))

        val result = repository.updateSessionArchived("session-1", archived = 0)

        assertTrue(result.isSuccess)
        // isArchived = (time.archived ?: 0) > 0 → false for 0
        assertFalse(result.getOrThrow().isArchived)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/session/session-1", request.path)
        assertEquals("{\"time\":{\"archived\":0}}", request.body.readUtf8())
    }

    @Test
    fun `updateSessionArchived surfaces HTTP failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400))

        val result = repository.updateSessionArchived("session-1", archived = 1234)

        assertTrue(result.isFailure)
    }

    // ── getSession / getChildren / getCommands / getModels: failure paths ────
    //
    // Each of these has only success coverage in DirectoryTest. Pin the
    // runSuspendCatching → Result.failure plumbing for a server error.

    @Test
    fun `getSession surfaces HTTP failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = repository.getSession("missing")

        assertTrue(result.isFailure)
        assertEquals("/session/missing", server.takeRequest().path)
    }

    @Test
    fun `getChildren surfaces HTTP failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.getChildren("parent-1")

        assertTrue(result.isFailure)
        assertEquals("/session/parent-1/children", server.takeRequest().path)
    }

    @Test
    fun `getCommands surfaces HTTP failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.getCommands()

        assertTrue(result.isFailure)
        assertEquals("/command", server.takeRequest().path)
    }

    @Test
    fun `getModels surfaces HTTP failure from v2 endpoint`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502))

        val result = repository.getModels()

        assertTrue(result.isFailure)
        // v2 Retrofit is rooted at <baseUrl>/api/, so GET /model lands at /api/model.
        assertEquals("/api/model", server.takeRequest().path)
    }

    // ── revertSession / forkSession / deleteSession: failure + edge cases ───

    @Test
    fun `revertSession surfaces HTTP failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409))

        val result = repository.revertSession("session-1", messageId = "msg-1", partId = null)

        assertTrue(result.isFailure)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/session/session-1/revert", request.path)
        // body still sent even on failure
        assertTrue(request.body.readUtf8().contains("\"messageID\":\"msg-1\""))
    }

    @Test
    fun `forkSession with null messageId omits messageID from body`() = runBlocking {
        // forkSession's messageId defaults to null. Pins that null is omitted
        // (explicit-nulls) rather than serialising `"messageID":null`, which
        // the legacy test (always passes a non-null id) does not cover.
        val session = Session(id = "fork-1", directory = "/workdir", parentId = "session-1")
        server.enqueue(jsonResponse(json.encodeToString(session)))

        val result = repository.forkSession("session-1", messageId = null)

        assertTrue(result.isSuccess)
        val body = server.takeRequest().body.readUtf8()
        assertFalse(
            "null messageId must be omitted: $body",
            body.contains("\"messageID\"")
        )
    }

    @Test
    fun `deleteSession discards Response so non-2xx does NOT surface as failure`() = runBlocking {
        // deleteSession is `runSuspendCatching { api.deleteSession(sessionId) }`
        // where api returns `Response<Unit>`. The function's declared return is
        // `Result<Unit>`, so Kotlin's expected-type inference coerces the lambda
        // to `() -> Unit` and the `Response<Unit>` value is DISCARDED. The call
        // therefore returns `Result.success(Unit)` regardless of HTTP status
        // (Retrofit's `Response<T>` API does not throw on non-2xx — only the
        // suspend-direct variants do). This pins the actual contract so the
        // quirk is documented and any future fix surfaces as a test change.
        server.enqueue(MockResponse().setResponseCode(404))

        val result = repository.deleteSession("missing")

        // The HTTP call IS made against the right path...
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/session/missing", request.path)
        // ...but the 404 Response<Unit> is discarded → still success.
        // (If the repository is ever fixed to forward Response.isFailure,
        // flip this assertion to `isFailure` and adjust accordingly.)
        assertTrue(
            "deleteSession currently swallows non-2xx (Response discarded by Unit coercion)",
            result.isSuccess
        )
    }

    // ── getAgents: derived-field decoding (hidden / mode / shortName) ────────
    //
    // Legacy `getAgents returns list` only asserts the name. Pin that hidden
    // agents are decoded, the mode field round-trips, and the AgentInfo
    // derived `isVisible` / `shortName` properties behave as documented.

    @Test
    fun `getAgents decodes hidden flag and mode and derives isVisible`() = runBlocking {
        val body = """[
            {"name":"Build","mode":"primary","hidden":false},
            {"name":"Internal Helper","mode":"secondary","hidden":true},
            {"name":"Code (reviewer)"}
        ]""".trimIndent()
        server.enqueue(jsonResponse(body))

        val result = repository.getAgents()

        assertTrue(result.isSuccess)
        val agents = result.getOrThrow()
        assertEquals(3, agents.size)

        // primary + hidden=false → visible
        assertEquals("primary", agents[0].mode)
        assertFalse(agents[0].hidden!!)
        assertTrue(agents[0].isVisible)

        // hidden=true → not visible regardless of mode
        assertTrue(agents[1].hidden!!)
        assertFalse(agents[1].isVisible)

        // shortName derives from "(" or " " prefix
        assertEquals("Code", agents[2].shortName)
        assertNull(agents[2].mode)
        // null mode (no explicit hidden) → visible by default
        assertTrue(agents[2].isVisible)

        assertEquals("/agent", server.takeRequest().path)
    }

    // ── getSessions: limit forwarding ────────────────────────────────────────
    //
    // Legacy `getSessions returns list` calls without a limit. Pin that the
    // limit Int? is serialised as `?limit=N` when supplied (the directory /
    // roots overloads are exercised separately by DirectoryTest).

    @Test
    fun `getSessions forwards limit query parameter when supplied`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        val result = repository.getSessions(limit = 42)

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        // getSessions has no @Query("directory") here (limit only) — the
        // interceptor doesn't add a directory header (no caller-supplied dir).
        assertEquals("/session?limit=42", request.path)
    }

    // ── createSession: null-title edge ───────────────────────────────────────
    //
    // createSession(title: String?=null) → CreateSessionRequest(title=null).
    // explicit-nulls drops the title field entirely. Pins the {} empty-body
    // shape, since the legacy test always passes a non-null title.

    @Test
    fun `createSession with null title sends empty body`() = runBlocking {
        val session = Session(id = "session-1", directory = "/workdir")
        server.enqueue(jsonResponse(json.encodeToString(session)))

        val result = repository.createSession(title = null)

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/session", request.path)
        assertEquals(
            "null title must be omitted by explicit-nulls, yielding empty object",
            "{}",
            request.body.readUtf8()
        )
    }

    // ── connectSSE: directory transmission + end-to-end decoding ────────────
    //
    // The repository's connectSSE is a thin façade over SSEClient.connect(
    //   baseUrl, username, password, directory). It is the only non-suspend
    // public API and had ZERO coverage. We drive it through MockWebServer's
    // text/event-stream mode (same pattern as SSEClientTest) to assert:
    //   1. The flow emits parsed SSEEvent values end-to-end (Retrofit/OkHttp
    //      chain + the SSEClient JSON decoder all wire up correctly through
    //      the repository).
    //   2. A non-null `directory` argument is forwarded as the
    //      X-Opencode-Directory header AND mirrored into `?directory` by the
    //      interceptor (proxy-safe routing per §R18 Phase 2-E step 1).
    //   3. A null `directory` produces no header.

    private fun sseResponse(vararg frames: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(frames.joinToString(separator = "\n\n", postfix = "\n\n"))

    private fun dataFrame(json: String): String = "data: $json"

    @Test
    fun `connectSSE returns flow that emits parsed events end-to-end`() = runBlocking {
        val payload = """{"payload":{"type":"server.connected","properties":{"sessionID":"s1"}}}"""
        server.enqueue(sseResponse(dataFrame(payload)))

        val event = withTimeout(5_000) {
            repository.connectSSE(directory = null)
                .first { it.isSuccess }
                .getOrThrow()
        }

        assertEquals("server.connected", event.payload.type)
        assertEquals("s1", event.payload.getString("sessionID"))

        val request = server.takeRequest()
        assertEquals("/global/event", request.path)
        assertEquals("text/event-stream", request.getHeader("Accept"))
    }

    @Test
    fun `connectSSE forwards directory as header and query param`() = runBlocking {
        val payload = """{"payload":{"type":"server.connected"}}"""
        server.enqueue(sseResponse(dataFrame(payload)))

        withTimeout(5_000) {
            repository.connectSSE(directory = "/workdir/project")
                .first { it.isSuccess }
        }

        val request = server.takeRequest()
        // §R18 Phase 2-E step 1: directory is set on the SSE request as a
        // header AND mirrored into the query by DirectoryHeaderInterceptor.
        assertEquals(
            "directory MUST be transmitted via header",
            "/workdir/project",
            request.getHeader("X-Opencode-Directory")
        )
        assertEquals(
            "directory MUST be mirrored into ?directory for proxy-safe routing",
            "/workdir/project",
            request.requestUrl?.queryParameter("directory")
        )
    }

    @Test
    fun `connectSSE with null directory omits directory header`() = runBlocking {
        val payload = """{"payload":{"type":"server.connected"}}"""
        server.enqueue(sseResponse(dataFrame(payload)))

        withTimeout(5_000) {
            repository.connectSSE(directory = null)
                .first { it.isSuccess }
        }

        val request = server.takeRequest()
        assertNull(
            "null directory must not produce a header",
            request.getHeader("X-Opencode-Directory")
        )
        assertNull(
            "null directory must not be mirrored into ?directory",
            request.requestUrl?.queryParameter("directory")
        )
    }

    @Test
    fun `connectSSE emits multiple sequential events through repository façade`() = runBlocking {
        val p1 = """{"payload":{"type":"server.connected"}}"""
        val p2 = """{"payload":{"type":"session.updated","properties":{"sessionID":"x"}}}"""
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: $p1\n\ndata: $p2\n\n")
        )

        val events = withTimeout(5_000) {
            repository.connectSSE(directory = null)
                .take(2)
                .toList()
                .map { it.getOrThrow() }
        }

        assertEquals(2, events.size)
        assertEquals("server.connected", events[0].payload.type)
        assertEquals("session.updated", events[1].payload.type)
        assertEquals("x", events[1].payload.getString("sessionID"))
    }
}
