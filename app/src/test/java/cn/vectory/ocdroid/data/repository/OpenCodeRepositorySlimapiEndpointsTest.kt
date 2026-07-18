package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.PermissionResponse
import cn.vectory.ocdroid.data.model.SlimSessionDigest
import cn.vectory.ocdroid.data.model.SlimapiPermissionEntry
import cn.vectory.ocdroid.data.model.SlimapiQuestionEntry
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
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
 * Cluster A (slim SSE + data layer): wire-level tests for the slimapi
 * endpoints on [OpenCodeRepository]. Each test pins:
 *
 *  - URL path / method / query parameters hit the contract (§2).
 *  - Body shape for POSTs (routeToken in body — contract assumption).
 *  - Skeleton message parsing reads `time.updated` (§5 A2=A).
 *  - `applySlimDigest` feeds the in-memory reducer; `coldStartSlimSync`
 *    fetches sessions + q + p + (optional) messages; resync = cold-start
 *    code path.
 *
 * Pattern: `OpenCodeRepository(mockk(relaxed=true), mockk(relaxed=true))`
 * wired to a MockWebServer, then `configure(baseUrl = server.url("/"))`.
 * Slim mode is enabled via `configure(slim = true)` where needed.
 */
class OpenCodeRepositorySlimapiEndpointsTest {

    private val server = MockWebServer()
    private lateinit var repository: OpenCodeRepository

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private fun jsonResponse(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code).setBody(body)
            .setHeader("Content-Type", "application/json")

    @Before
    fun setup() = runBlocking {
        DebugLog.clear()
        server.start()
        repository = OpenCodeRepository(mockk(relaxed = true), mockk(relaxed = true))
        repository.configure(baseUrl = server.url("/").toString().trimEnd('/'), slim = true)
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    // ── /slimapi/sessions ──────────────────────────────────────────────────

    @Test
    fun `getSlimapiSessions hits slimapi sessions path`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        val result = repository.getSlimapiSessions()

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow().size)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/slimapi/sessions", request.path)
    }

    @Test
    fun `getSlimapiSessions forwards directory and roots and limit`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        repository.getSlimapiSessions(directories = listOf("/w"), roots = true, limit = 50)

        val request = server.takeRequest()
        assertTrue("path contains directory: ${request.path}", request.path!!.contains("directory=%2Fw"))
        assertTrue("path contains roots=true: ${request.path}", request.path!!.contains("roots=true"))
        assertTrue("path contains limit=50: ${request.path}", request.path!!.contains("limit=50"))
    }

    @Test
    fun `getSlimapiSessions forwards list of directories as repeated query`() = runBlocking {
        // §slim-reconcile-lane-repo (B4 T6): the v1 contract pins
        // `?directory=a&directory=b` (repeated params, NOT comma-joined).
        // Retrofit expands a List<String> into repeated query entries.
        server.enqueue(jsonResponse("[]"))

        repository.getSlimapiSessions(directories = listOf("/w1", "/w2", "/w3"))

        val request = server.takeRequest()
        val path = request.path!!
        // Each entry must appear as its own `directory=` query (URL-encoded).
        assertEquals(
            "repeated directory entries: $path",
            3,
            path.split("directory=").size - 1,
        )
        assertTrue("w1 present: $path", path.contains("directory=%2Fw1"))
        assertTrue("w2 present: $path", path.contains("directory=%2Fw2"))
        assertTrue("w3 present: $path", path.contains("directory=%2Fw3"))
    }

    // ── /slimapi/messages/{sid}/since/{ts} (§5 A2=A) ───────────────────────

    @Test
    fun `getSlimapiMessagesSince hits since path and returns skeletons`() = runBlocking {
        val skeleton = MessageWithParts(
            info = Message(
                id = "m1",
                role = "assistant",
                time = Message.TimeInfo(created = 100L, updated = 200L),
            )
        )
        server.enqueue(jsonResponse(json.encodeToString(listOf(skeleton))))

        val result = repository.getSlimapiMessagesSince("sess-1", since = 150L)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().size)
        assertEquals("m1", result.getOrThrow()[0].info.id)
        assertEquals(200L, result.getOrThrow()[0].info.time?.updated)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(
            "/slimapi/messages/sess-1/since/150",
            request.path,
        )
    }

    @Test
    fun `getSlimapiMessagesSince bumps local bookmark to max time updated`() = runBlocking {
        // Two skeletons: max time.updated = 300.
        val s1 = MessageWithParts(
            info = Message(id = "m1", role = "assistant",
                time = Message.TimeInfo(updated = 200L))
        )
        val s2 = MessageWithParts(
            info = Message(id = "m2", role = "assistant",
                time = Message.TimeInfo(updated = 300L))
        )
        server.enqueue(jsonResponse(json.encodeToString(listOf(s1, s2))))

        repository.getSlimapiMessagesSince("sess-1", since = 0L)

        // Bookmark should now be 300L.
        val state = repository.snapshotSlimSseState()["sess-1"]
        assertNotNull("bookmark entry should be created", state)
        assertEquals(300L, state!!.updatedAt)
    }

    @Test
    fun `getSlimapiMessagesSince surfaces HTTP error as failure`() = runBlocking {
        server.enqueue(jsonResponse("nope", 500))

        val result = repository.getSlimapiMessagesSince("sess-1", since = 0L)

        assertTrue(result.isFailure)
        val request = server.takeRequest()
        assertEquals("/slimapi/messages/sess-1/since/0", request.path)
    }

    @Test
    fun `getSlimapiMessagesSince forwards limit and before cursor`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        repository.getSlimapiMessagesSince("sess-1", since = 100L, limit = 50, before = "abc")

        val request = server.takeRequest()
        assertTrue("limit forwarded: ${request.path}", request.path!!.contains("limit=50"))
        assertTrue("before forwarded: ${request.path}", request.path!!.contains("before=abc"))
    }

    // ── applySlimDigest + fetch trigger ────────────────────────────────────

    @Test
    fun `applySlimDigest on fresh session triggers fetch anchored on zero`() {
        val decision = repository.applySlimDigest(
            SlimSessionDigest(sessionId = "fresh", updatedAt = 42L)
        )
        assertNotNull(decision)
        assertEquals("fresh", decision!!.sessionId)
        assertEquals(0L, decision.since)
    }

    @Test
    fun `applySlimDigest with strictly newer updatedAt triggers fetch on prior bookmark`() {
        repository.applySlimDigest(
            SlimSessionDigest(sessionId = "s1", updatedAt = 100L)
        )
        val decision = repository.applySlimDigest(
            SlimSessionDigest(sessionId = "s1", updatedAt = 200L)
        )
        assertNotNull(decision)
        assertEquals(100L, decision!!.since)
    }

    @Test
    fun `applySlimDigest null on blank sessionId`() {
        val decision = repository.applySlimDigest(
            SlimSessionDigest(sessionId = "", updatedAt = 1L)
        )
        assertNull(decision)
    }

    @Test
    fun `applySlimDigest null when no updatedAt change`() {
        repository.applySlimDigest(
            SlimSessionDigest(sessionId = "s1", updatedAt = 100L)
        )
        val decision = repository.applySlimDigest(
            SlimSessionDigest(sessionId = "s1", status = "busy")  // no updatedAt
        )
        assertNull(decision)
    }

    // ── /slimapi/questions + /slimapi/permissions ──────────────────────────

    @Test
    fun `getSlimapiQuestions hits slimapi questions path`() = runBlocking {
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))

        repository.getSlimapiQuestions()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/slimapi/questions", request.path)
    }

    @Test
    fun `getSlimapiQuestions decodes entries with routeToken`() = runBlocking {
        // §reconcile: sidecar wraps responses as {"items":[...],"errors":[...]}
        // (oc-slimapi routes/questions.py::_aggregate). Bare-list mocks would
        // have always been wrong against the live sidecar; pin the envelope.
        val body = """{
            "items": [
                {
                    "id": "q1",
                    "sessionID": "sess-1",
                    "questions": [],
                    "directory": "/w",
                    "routeToken": "tok-abc"
                }
            ],
            "errors": []
        }""".trimIndent()
        server.enqueue(jsonResponse(body))

        val result = repository.getSlimapiQuestions()

        assertTrue(result.isSuccess)
        val entries = result.getOrThrow()
        assertEquals(1, entries.size)
        assertEquals("q1", entries[0].id)
        assertEquals("sess-1", entries[0].sessionId)
        assertEquals("/w", entries[0].directory)
        assertEquals("tok-abc", entries[0].routeToken)
    }

    @Test
    fun `getSlimapiQuestions flattens items and ignores non-fatal errors`() = runBlocking {
        // §reconcile: when one upstream opencode is down, the sidecar still
        // returns 200 with partial items + an entry in `errors`. v1 client
        // surfaces `.items` and logs `.errors`; the UI does NOT see the
        // per-directory failure (sidecar already degrades).
        val body = """{
            "items": [
                {"id": "q-ok", "sessionID": "sess-1", "directory": "/w1"}
            ],
            "errors": [
                {"directory": "/w2", "code": "upstream_http_503"}
            ]
        }""".trimIndent()
        server.enqueue(jsonResponse(body))

        val result = repository.getSlimapiQuestions()

        assertTrue(result.isSuccess)
        val entries = result.getOrThrow()
        assertEquals(1, entries.size)
        assertEquals("q-ok", entries[0].id)
    }

    @Test
    fun `getSlimapiPermissions hits slimapi permissions path`() = runBlocking {
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))

        repository.getSlimapiPermissions()

        assertEquals("/slimapi/permissions", server.takeRequest().path)
    }

    @Test
    fun `getSlimapiPermissions decodes entries with routeToken`() = runBlocking {
        val body = """{
            "items": [
                {
                    "id": "p1",
                    "sessionID": "sess-1",
                    "permission": "edit",
                    "directory": "/w",
                    "routeToken": "tok-xyz"
                }
            ],
            "errors": []
        }""".trimIndent()
        server.enqueue(jsonResponse(body))

        val result = repository.getSlimapiPermissions()

        assertTrue(result.isSuccess)
        val entries = result.getOrThrow()
        assertEquals(1, entries.size)
        assertEquals("p1", entries[0].id)
        assertEquals("edit", entries[0].permission)
        assertEquals("tok-xyz", entries[0].routeToken)
    }

    // ── Reply / reject / respond with routeToken ───────────────────────────

    @Test
    fun `replySlimapiQuestion sends answers plus routeToken in body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        repository.replySlimapiQuestion(
            questionId = "q1",
            answers = listOf(listOf("yes")),
            routeToken = "tok-abc",
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/slimapi/questions/q1/reply", request.path)
        val body = request.body.readUtf8()
        assertTrue("answers present: $body", body.contains("\"answers\":[[\"yes\"]]"))
        assertTrue("routeToken present: $body", body.contains("\"routeToken\":\"tok-abc\""))
    }

    @Test
    fun `rejectSlimapiQuestion sends routeToken in body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        repository.rejectSlimapiQuestion(questionId = "q1", routeToken = "tok-abc")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/slimapi/questions/q1/reject", request.path)
        val body = request.body.readUtf8()
        assertTrue("routeToken present: $body", body.contains("\"routeToken\":\"tok-abc\""))
    }

    @Test
    fun `respondSlimapiPermission sends response value plus routeToken`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        repository.respondSlimapiPermission(
            sessionId = "sess-1",
            permissionId = "p1",
            response = PermissionResponse.ALWAYS,
            routeToken = "tok-xyz",
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/slimapi/sessions/sess-1/permissions/p1", request.path)
        val body = request.body.readUtf8()
        assertTrue("response value: $body", body.contains("\"response\":\"always\""))
        assertTrue("routeToken: $body", body.contains("\"routeToken\":\"tok-xyz\""))
    }

    // ── coldStartSlimSync (also resync path) ───────────────────────────────

    @Test
    fun `coldStartSlimSync fans out to sessions, questions, permissions`() = runBlocking {
        server.enqueue(jsonResponse("[]"))   // sessions
        // §reconcile: q + p return {items, errors} envelope now.
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))   // questions
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))   // permissions

        val result = repository.coldStartSlimSync()

        assertTrue(result.isSuccess)
        val snapshot = result.getOrThrow()
        assertEquals(0, snapshot.sessions.size)
        assertEquals(0, snapshot.questions.size)
        assertEquals(0, snapshot.permissions.size)
        assertNull("no openSessionId → messages null", snapshot.messages)

        // Three requests were made in any order (parallel-ish sequential).
        val paths = mutableListOf<String>()
        repeat(3) { paths += server.takeRequest().path!! }
        assertTrue("sessions path hit: $paths", paths.any { it == "/slimapi/sessions" })
        assertTrue("questions path hit: $paths", paths.any { it == "/slimapi/questions" })
        assertTrue("permissions path hit: $paths", paths.any { it == "/slimapi/permissions" })
    }

    @Test
    fun `coldStartSlimSync with openSessionId also fetches messages since bookmark`() = runBlocking {
        // Seed bookmark at 100.
        repository.applySlimDigest(
            SlimSessionDigest(sessionId = "sess-1", updatedAt = 100L)
        )
        server.enqueue(jsonResponse("[]"))               // sessions
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))   // questions
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))   // permissions
        server.enqueue(jsonResponse("[]"))               // messages since 100

        val result = repository.coldStartSlimSync(openSessionId = "sess-1")

        assertTrue(result.isSuccess)
        val snapshot = result.getOrThrow()
        assertNotNull("openSessionId supplied → messages list (possibly empty)", snapshot.messages)
        assertEquals(0, snapshot.messages!!.size)

        // 4 requests in total. Find the messages one.
        // §slim-v1-paging: cold-start now sends `?limit=50` on the since call,
        // so the path is `/slimapi/messages/sess-1/since/100?limit=50`; match
        // by prefix to keep the test resilient to paging-default changes.
        val paths = mutableListOf<String>()
        repeat(4) { paths += server.takeRequest().path!! }
        assertTrue(
            "messages since bookmark hit: $paths",
            paths.any { it.startsWith("/slimapi/messages/sess-1/since/100") }
        )
    }

    @Test
    fun `coldStartSlimSync degrades per-piece on HTTP failure`() = runBlocking {
        // sessions 500, questions 200, permissions 500, no messages requested.
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.coldStartSlimSync()

        assertTrue(
            "per-piece degradation yields overall success (snapshot has empty failures)",
            result.isSuccess
        )
        val snapshot = result.getOrThrow()
        assertEquals(0, snapshot.sessions.size)
        assertEquals(0, snapshot.questions.size)  // empty result, but did succeed
        assertEquals(0, snapshot.permissions.size)
    }

    // ── resync = reuse cold-start: same code path ──────────────────────────

    @Test
    fun `resync equals cold-start code path - same endpoints hit`() = runBlocking {
        // Simulate: cold-start ran once, then a resync fires. Both should
        // hit the SAME three endpoints (sessions / questions / permissions).
        server.enqueue(jsonResponse("[]"))  // cold-start sessions
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))  // cold-start questions
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))  // cold-start permissions

        repository.coldStartSlimSync()

        // Resync fires — same code path.
        server.enqueue(jsonResponse("[]"))
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))

        val resyncResult = repository.coldStartSlimSync()

        assertTrue(resyncResult.isSuccess)

        // All 6 requests together; the second 3 must mirror the first 3's paths.
        val coldPaths = mutableListOf<String>()
        repeat(3) { coldPaths += server.takeRequest().path!! }
        val resyncPaths = mutableListOf<String>()
        repeat(3) { resyncPaths += server.takeRequest().path!! }
        assertEquals(
            "resync MUST hit the same paths as cold-start (§4)",
            coldPaths.toSet(),
            resyncPaths.toSet(),
        )
    }

    // ── configure clears bookmarks ─────────────────────────────────────────

    @Test
    fun `configure clears slim SSE bookmarks`() {
        repository.applySlimDigest(
            SlimSessionDigest(sessionId = "s1", updatedAt = 100L)
        )
        assertEquals(1, repository.snapshotSlimSseState().size)

        // Reconfigure (host switch simulation).
        repository.configure(baseUrl = server.url("/").toString().trimEnd('/'), slim = true)

        assertTrue(
            "host switch MUST clear slim SSE bookmarks",
            repository.snapshotSlimSseState().isEmpty()
        )
    }

    // ── Message full ───────────────────────────────────────────────────────

    @Test
    fun `getSlimapiMessageFull hits full path`() = runBlocking {
        val full = MessageWithParts(
            info = Message(id = "m1", role = "user")
        )
        server.enqueue(jsonResponse(json.encodeToString(full)))

        val result = repository.getSlimapiMessageFull("sess-1", "m1")

        assertTrue(result.isSuccess)
        assertEquals("m1", result.getOrThrow().info.id)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/slimapi/messages/sess-1/full/m1", request.path)
    }

    // ── §slim-reconcile-lane-repo: legacy→slim internal routing ────────────
    //
    // T2/T3/T4: when `isSlimMode == true`, the legacy REST entry points
    // (`getSessions` / `getSessionsForDirectory` / `getMessagesPaged` /
    // `getPendingPermissions`) MUST internally route to the slimapi sidecar
    // endpoints. Each branch has a paired regression test that pins the
    // legacy (`isSlimMode == false`) path's wire shape stays byte-for-byte
    // unchanged.

    /** Helper: build a repository configured for either slim or legacy mode. */
    private fun makeRepository(slim: Boolean): OpenCodeRepository {
        val r = OpenCodeRepository(mockk(relaxed = true), mockk(relaxed = true))
        r.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            slim = slim,
        )
        return r
    }

    // ── T1: isSlimMode getter ──────────────────────────────────────────────

    @Test
    fun `isSlimMode reflects configure slim flag`() {
        val slimRepo = makeRepository(slim = true)
        val legacyRepo = makeRepository(slim = false)
        assertTrue("slim=true → isSlimMode true", slimRepo.isSlimMode)
        assertFalse("slim=false → isSlimMode false", legacyRepo.isSlimMode)
    }

    // ── T2: getSessions / getSessionsForDirectory ──────────────────────────

    @Test
    fun `getSessions slim mode routes to slimapi sessions endpoint`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        makeRepository(slim = true).getSessions(limit = 25)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertTrue("slim path: ${request.path}", request.path!!.startsWith("/slimapi/sessions"))
        assertTrue("limit forwarded: ${request.path}", request.path!!.contains("limit=25"))
    }

    @Test
    fun `getSessions legacy mode unchanged - hits legacy session endpoint`() = runBlocking {
        // Regression protection: slim=false MUST keep the pre-slim wire shape.
        server.enqueue(jsonResponse("[]"))

        makeRepository(slim = false).getSessions(limit = 25)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        // Legacy shape: /session with ?limit=... (Retrofit declaration order).
        assertTrue(
            "legacy path: ${request.path}",
            request.path == "/session?limit=25",
        )
    }

    @Test
    fun `getSessionsForDirectory slim mode forwards directory as repeated query and roots`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        makeRepository(slim = true).getSessionsForDirectory(directory = "/work", limit = 10)

        val request = server.takeRequest()
        assertTrue("slim path: ${request.path}", request.path!!.startsWith("/slimapi/sessions"))
        assertTrue("directory forwarded: ${request.path}", request.path!!.contains("directory=%2Fwork"))
        assertTrue("roots=true forwarded: ${request.path}", request.path!!.contains("roots=true"))
        assertTrue("limit forwarded: ${request.path}", request.path!!.contains("limit=10"))
    }

    @Test
    fun `getSessionsForDirectory legacy mode unchanged`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        makeRepository(slim = false).getSessionsForDirectory(directory = "/work", limit = 10)

        val request = server.takeRequest()
        // Legacy shape: /session with ?directory=... &roots=true &limit=...
        // (Query order is Retrofit's declaration order, not call order —
        // assert by contains, not equality, for resilience.)
        assertEquals("GET", request.method)
        assertTrue("legacy path: ${request.path}", request.path!!.startsWith("/session?"))
        assertTrue("directory: ${request.path}", request.path!!.contains("directory=%2Fwork"))
        assertTrue("roots=true: ${request.path}", request.path!!.contains("roots=true"))
        assertTrue("limit: ${request.path}", request.path!!.contains("limit=10"))
    }

    // ── T3: getMessagesPaged ───────────────────────────────────────────────

    @Test
    fun `getMessagesPaged slim mode hits slimapi since path with bookmark anchor`() = runBlocking {
        // Seed the local bookmark at 1234 — getMessagesPaged MUST derive
        // `since=1234` from slimSseState and pass it on the URL.
        val repo = makeRepository(slim = true)
        repo.applySlimDigest(SlimSessionDigest(sessionId = "sess-1", updatedAt = 1234L))
        server.enqueue(jsonResponse("[]"))

        val result = repo.getMessagesPaged("sess-1", limit = 30, before = "cur")

        assertTrue(result.isSuccess)
        val page = result.getOrThrow()
        assertEquals(0, page.items.size)
        assertNull("v1 slim path does not surface nextCursor", page.nextCursor)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertTrue(
            "slim since path with bookmark anchor: ${request.path}",
            request.path!!.startsWith("/slimapi/messages/sess-1/since/1234"),
        )
        assertTrue("limit forwarded: ${request.path}", request.path!!.contains("limit=30"))
        assertTrue("before cursor forwarded: ${request.path}", request.path!!.contains("before=cur"))
    }

    @Test
    fun `getMessagesPaged slim mode with no bookmark anchors on zero`() = runBlocking {
        // No prior digest → since=0 (cold path).
        server.enqueue(jsonResponse("[]"))

        makeRepository(slim = true).getMessagesPaged("fresh-sess")

        val request = server.takeRequest()
        assertTrue(
            "cold slim path anchored at 0: ${request.path}",
            request.path!!.startsWith("/slimapi/messages/fresh-sess/since/0"),
        )
    }

    @Test
    fun `getMessagesPaged slim mode bumps bookmark to max time updated`() = runBlocking {
        // Round-trip: items returned by slimapi feed back into the bookmark.
        val repo = makeRepository(slim = true)
        val s1 = MessageWithParts(
            info = Message(id = "m1", role = "assistant",
                time = Message.TimeInfo(updated = 500L))
        )
        val s2 = MessageWithParts(
            info = Message(id = "m2", role = "assistant",
                time = Message.TimeInfo(updated = 900L))
        )
        server.enqueue(jsonResponse(json.encodeToString(listOf(s1, s2))))

        repo.getMessagesPaged("sess-1")

        val state = repo.snapshotSlimSseState()["sess-1"]
        assertNotNull("bookmark entry created", state)
        assertEquals(900L, state!!.updatedAt)
    }

    @Test
    fun `getMessagesPaged legacy mode unchanged`() = runBlocking {
        // Regression: slim=false MUST keep the legacy wire shape (cursor in
        // the X-Next-Cursor response header + before query).
        val msg = MessageWithParts(info = Message(id = "m1", role = "user"))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(json.encodeToString(listOf(msg)))
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Next-Cursor", "next-cursor-token")
        )

        val result = makeRepository(slim = false).getMessagesPaged("sess-1", limit = 20, before = "prev")

        assertTrue(result.isSuccess)
        val page = result.getOrThrow()
        assertEquals(1, page.items.size)
        assertEquals("next-cursor-token", page.nextCursor)

        val request = server.takeRequest()
        // Legacy shape: /session/sess-1/message with ?limit=... &before=...
        // (Query order is Retrofit's declaration order — assert by contains.)
        assertEquals("GET", request.method)
        assertTrue(
            "legacy path prefix: ${request.path}",
            request.path!!.startsWith("/session/sess-1/message?"),
        )
        assertTrue("limit: ${request.path}", request.path!!.contains("limit=20"))
        assertTrue("before cursor: ${request.path}", request.path!!.contains("before=prev"))
    }

    // ── T4: getPendingPermissions ──────────────────────────────────────────

    @Test
    fun `getPendingPermissions slim mode routes to slimapi permissions`() = runBlocking {
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))

        makeRepository(slim = true).getPendingPermissions()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/slimapi/permissions", request.path)
    }

    @Test
    fun `getPendingPermissions slim mode maps entries to legacy PermissionRequest`() = runBlocking {
        // §rev-grok fix1: the slimapi entry carries directory + routeToken
        // on top of the legacy PermissionRequest shape; getPendingPermissions
        // MUST preserve routeToken (Phase 3b — slim respond path needs the
        // sidecar HMAC). `directory` is still dropped (the sidecar re-injects
        // it from the token).
        val body = """{
            "items": [
                {
                    "id": "p1",
                    "sessionID": "sess-1",
                    "permission": "edit",
                    "patterns": ["/w/file.txt"],
                    "directory": "/w",
                    "routeToken": "tok-xyz"
                }
            ],
            "errors": []
        }""".trimIndent()
        server.enqueue(jsonResponse(body))

        val result = makeRepository(slim = true).getPendingPermissions()

        assertTrue(result.isSuccess)
        val permissions = result.getOrThrow()
        assertEquals(1, permissions.size)
        val p = permissions[0]
        assertEquals("p1", p.id)
        assertEquals("sess-1", p.sessionId)
        assertEquals("edit", p.permission)
        assertEquals(listOf("/w/file.txt"), p.patterns)
        // §rev-grok fix1: routeToken MUST be preserved on the legacy shape
        // (Phase 3b — slim respond reads it from pendingPermissions).
        assertEquals("tok-xyz", p.routeToken)
    }

    @Test
    fun `getPendingPermissions slim mode preserves null routeToken when sidecar omits it`() = runBlocking {
        // Forward-compat: sidecar may omit routeToken entirely (e.g. legacy
        // upstream that doesn't issue tokens). Mapping MUST tolerate null and
        // not crash / not synthesize a placeholder.
        val body = """{
            "items": [
                {"id": "p2", "sessionID": "sess-1", "permission": "edit"}
            ],
            "errors": []
        }""".trimIndent()
        server.enqueue(jsonResponse(body))

        val result = makeRepository(slim = true).getPendingPermissions()

        assertTrue(result.isSuccess)
        val p = result.getOrThrow().single()
        assertEquals("p2", p.id)
        assertNull("omitted routeToken → null (no placeholder)", p.routeToken)
    }

    @Test
    fun `respondSlimapiPermission receives routeToken from getPendingPermissions result`() = runBlocking {
        // §rev-grok fix1 link integrity: end-to-end pin that the routeToken
        // preserved by `getPendingPermissions` is the one that reaches the
        // slim respond POST body. GetPendingPermissions → PermissionRequest
        // (token preserved) → respondSlimapiPermission(token) → POST body.
        // (ChatScaffold + OrchestratorViewModel are exercised in higher-level
        // suites; this pins the data-layer half of the chain.)
        val body = """{
            "items": [
                {"id": "p1", "sessionID": "sess-1", "permission": "edit", "routeToken": "tok-link-xyz"}
            ],
            "errors": []
        }""".trimIndent()
        server.enqueue(jsonResponse(body))

        val permissions = makeRepository(slim = true).getPendingPermissions().getOrThrow()
        val p = permissions.single()
        assertEquals("tok-link-xyz", p.routeToken)

        // The upper layer would now forward p.routeToken into the slim
        // respond call — simulate that hop and pin the wire body.
        server.enqueue(MockResponse().setResponseCode(204))
        makeRepository(slim = true).respondSlimapiPermission(
            sessionId = p.sessionId,
            permissionId = p.id,
            response = PermissionResponse.ALWAYS,
            routeToken = p.routeToken,
        )

        server.takeRequest() // GET /slimapi/permissions (skip)
        val respondReq = server.takeRequest()
        assertEquals("POST", respondReq.method)
        assertEquals("/slimapi/sessions/sess-1/permissions/p1", respondReq.path)
        val respondBody = respondReq.body.readUtf8()
        assertTrue("response value: $respondBody", respondBody.contains("\"response\":\"always\""))
        assertTrue(
            "routeToken forwarded from getPendingPermissions: $respondBody",
            respondBody.contains("\"routeToken\":\"tok-link-xyz\""),
        )
    }

    @Test
    fun `getPendingPermissions legacy mode unchanged`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        makeRepository(slim = false).getPendingPermissions()

        val request = server.takeRequest()
        assertEquals(
            "legacy path intact",
            "/permission",
            request.path,
        )
    }

    // ── T5: checkHealth slim branch feeds updateSlimapi ────────────────────

    @Test
    fun `checkHealth slim mode populates serverCompatProfile from sidecar body`() = runBlocking {
        // §slim-reconcile-lane-repo (B3 T5): the slim health branch MUST feed
        // ServerCompatProfile.updateSlimapi so Phase 3 bootstrap can read
        // isSlimapiClientAccepted() and fail-close on version mismatch.
        val compatProfile = ServerCompatProfile()
        val repo = OpenCodeRepository(
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            compatProfile,
        )
        repo.configure(baseUrl = server.url("/").toString().trimEnd('/'), slim = true)

        // Before any probe: fail-closed (null bounds).
        assertNull("pre-probe min null → fail-closed", compatProfile.slimapiAcceptedMin)
        assertNull("pre-probe max null → fail-closed", compatProfile.slimapiAcceptedMax)
        assertFalse("pre-probe isSlimapiClientAccepted == false", compatProfile.isSlimapiClientAccepted())

        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(
                    """
                    {
                      "sidecar": { "ok": true, "version": "0.1.0" },
                      "schema":   { "degraded": false },
                      "server":   { "api_version": 1, "accepted_client_versions": [1, 1] }
                    }
                    """.trimIndent()
                )
                .setHeader("Content-Type", "application/json")
        )

        val result = repo.checkHealth()

        assertTrue(result.isSuccess)
        assertTrue("healthy on compatible versions", result.getOrThrow().healthy)
        // M2 self-check loop closed:
        assertEquals(1, compatProfile.slimapiServerApiVersion)
        assertEquals(1, compatProfile.slimapiAcceptedMin)
        assertEquals(1, compatProfile.slimapiAcceptedMax)
        assertEquals(true, compatProfile.slimapiSidecarOk)
        assertEquals(false, compatProfile.slimapiSchemaDegraded)
        assertTrue("accepted after probe", compatProfile.isSlimapiClientAccepted())
    }

    @Test
    fun `checkHealth slim mode with incompatible versions still populates and surfaces unhealthy`() = runBlocking {
        // When the sidecar advertises a range our client version is outside,
        // updateSlimapi STILL lands the bounds (so UI can show the actual
        // range) but isSlimapiClientAccepted() returns false and checkHealth
        // surfaces unhealthy.
        val compatProfile = ServerCompatProfile()
        val repo = OpenCodeRepository(
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            compatProfile,
        )
        repo.configure(baseUrl = server.url("/").toString().trimEnd('/'), slim = true)

        // Advertise a range starting at v5 (current client version is way below).
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(
                    """
                    {
                      "sidecar": { "ok": true },
                      "schema":   { "degraded": false },
                      "server":   { "api_version": 5, "accepted_client_versions": [5, 9] }
                    }
                    """.trimIndent()
                )
                .setHeader("Content-Type", "application/json")
        )

        val result = repo.checkHealth()

        // Bounds land even on incompatible:
        assertEquals(5, compatProfile.slimapiAcceptedMin)
        assertEquals(9, compatProfile.slimapiAcceptedMax)
        assertFalse("client version below min → not accepted", compatProfile.isSlimapiClientAccepted())
        // And the health probe surfaces failure (runSuspendCatching wraps the
        // thrown error → Result.failure; both branches acceptable per impl —
        // here we only assert the compat profile got populated).
    }

    // ── Phase 3a (Lane-B3-Dialog): checkHealthFor slim branch feeds updateSlimapi ──

    @Test
    fun `checkHealthFor slim mode populates serverCompatProfile from sidecar body`() = runBlocking {
        // §slim-reconcile-lane-repo (Phase 3a / Lane-B3-Dialog): the one-shot
        // probe (used by host list "test" action) MUST also feed
        // [ServerCompatProfile.updateSlimapi] — mirroring [checkHealth]'s T5
        // pattern — so the ConnectionViewModel test-connection path can read
        // [isSlimapiClientAccepted] and feed [ConnectionState.slimapiVersionIncompatible]
        // (closing the M2 UX loop; without this the dialog never fires from
        // the test-connection path).
        val compatProfile = ServerCompatProfile()
        val repo = OpenCodeRepository(
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            compatProfile,
        )
        // checkHealthFor does NOT depend on configure() — it takes baseUrl
        // explicitly. No configure call needed (mirrors its one-shot semantics).
        assertNull("pre-probe min null → fail-closed", compatProfile.slimapiAcceptedMin)
        assertNull("pre-probe max null → fail-closed", compatProfile.slimapiAcceptedMax)
        assertFalse("pre-probe isSlimapiClientAccepted == false", compatProfile.isSlimapiClientAccepted())

        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(
                    """
                    {
                      "sidecar": { "ok": true, "version": "0.1.0" },
                      "schema":   { "degraded": false },
                      "server":   { "api_version": 1, "accepted_client_versions": [1, 1] }
                    }
                    """.trimIndent()
                )
                .setHeader("Content-Type", "application/json")
        )

        val result = repo.checkHealthFor(
            baseUrl = server.url("/").toString().trimEnd('/'),
            slim = true,
        )

        assertTrue(result.isSuccess)
        assertTrue("healthy on compatible versions", result.getOrThrow().healthy)
        // Phase 3a loop closed (one-shot path):
        assertEquals(1, compatProfile.slimapiServerApiVersion)
        assertEquals(1, compatProfile.slimapiAcceptedMin)
        assertEquals(1, compatProfile.slimapiAcceptedMax)
        assertEquals(true, compatProfile.slimapiSidecarOk)
        assertEquals(false, compatProfile.slimapiSchemaDegraded)
        assertTrue("accepted after one-shot probe", compatProfile.isSlimapiClientAccepted())

        // Wire-level assertion: the probe MUST hit /slimapi/health (C3 fix —
        // never /global/health in slim mode).
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertTrue(
            "probe path is /slimapi/health: ${request.path}",
            request.path!!.startsWith("/slimapi/health"),
        )
        // M1 gate: X-Slimapi-Version header is present.
        assertEquals(
            cn.vectory.ocdroid.data.repository.http.SlimapiContract.SLIMAPI_CLIENT_VERSION.toString(),
            request.getHeader(cn.vectory.ocdroid.data.repository.http.SlimapiContract.X_SLIMAPI_VERSION),
        )
    }

    @Test
    fun `checkHealthFor slim mode with incompatible versions lands bounds and surfaces failure`() = runBlocking {
        // §Phase 3a (Lane-B3-Dialog): when the one-shot probe sees an
        // incompatible range, the bounds STILL land (so the UI can show the
        // real range) but the probe surfaces failure (fail-closed transport).
        val compatProfile = ServerCompatProfile()
        val repo = OpenCodeRepository(
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            compatProfile,
        )

        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(
                    """
                    {
                      "sidecar": { "ok": true },
                      "schema":   { "degraded": false },
                      "server":   { "api_version": 5, "accepted_client_versions": [5, 9] }
                    }
                    """.trimIndent()
                )
                .setHeader("Content-Type", "application/json")
        )

        val result = repo.checkHealthFor(
            baseUrl = server.url("/").toString().trimEnd('/'),
            slim = true,
        )

        // Bounds land even on incompatible:
        assertEquals(5, compatProfile.slimapiAcceptedMin)
        assertEquals(9, compatProfile.slimapiAcceptedMax)
        assertFalse("client version below min → not accepted", compatProfile.isSlimapiClientAccepted())
        // Probe surfaces failure (fail-closed transport; either Result.failure
        // from the thrown error or any other branch — assert populates bounds).
    }

    @Test
    fun `checkHealthFor legacy mode leaves serverCompatProfile slimapi fields untouched`() = runBlocking {
        // §Phase 3a (Lane-B3-Dialog): the legacy branch (slim=false) MUST be
        // byte-for-byte unchanged — does NOT call updateSlimapi, so the SCP's
        // slimapi fields stay null (legacy probe never touches slimapi state).
        val compatProfile = ServerCompatProfile()
        val repo = OpenCodeRepository(
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            compatProfile,
        )

        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"healthy":true,"version":"1.17.13"}""")
                .setHeader("Content-Type", "application/json")
        )

        val result = repo.checkHealthFor(
            baseUrl = server.url("/").toString().trimEnd('/'),
            slim = false,
        )

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().healthy)
        // Legacy branch never touches slimapi state:
        assertNull(compatProfile.slimapiAcceptedMin)
        assertNull(compatProfile.slimapiAcceptedMax)
        assertNull(compatProfile.slimapiServerApiVersion)
        assertFalse(compatProfile.isSlimapiClientAccepted())
        // Wire: legacy path is /global/health (no version header).
        val request = server.takeRequest()
        assertTrue(
            "legacy path intact: ${request.path}",
            request.path!!.startsWith("/global/health"),
        )
        assertNull(
            "no X-Slimapi-Version header in legacy mode",
            request.getHeader(cn.vectory.ocdroid.data.repository.http.SlimapiContract.X_SLIMAPI_VERSION),
        )
    }

    // ── T6: coldStartSlimSync forwards directories ─────────────────────────

    @Test
    fun `coldStartSlimSync forwards directories to slimapi sessions endpoint`() = runBlocking {
        // §slim-reconcile-lane-repo (B4 T6): directories MUST reach the
        // /slimapi/sessions call (was: dropped on the floor in pre-T6 code).
        server.enqueue(jsonResponse("[]"))  // sessions
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))  // questions
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))  // permissions

        repository.coldStartSlimSync(directories = listOf("/alpha", "/beta"))

        val sessionsRequest = server.takeRequest()
        assertTrue(
            "directories forwarded as repeated query: ${sessionsRequest.path}",
            sessionsRequest.path!!.contains("directory=%2Falpha"),
        )
        assertTrue(
            "second directory forwarded: ${sessionsRequest.path}",
            sessionsRequest.path!!.contains("directory=%2Fbeta"),
        )
    }
}
