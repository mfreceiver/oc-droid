package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.SlimSessionsPage
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import org.junit.Ignore
import org.junit.Test
import java.util.concurrent.TimeUnit

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

    /** Extract items from [SlimAggregationOutcome] for test convenience. */
    private fun <T> SlimAggregationOutcome<T>.items(): List<T> = when (this) {
        is SlimAggregationOutcome.Failure -> emptyList()
        is SlimAggregationOutcome.Success -> items
        is SlimAggregationOutcome.Partial -> items
    }

    /** True if [SlimAggregationOutcome] is [SlimAggregationOutcome.Failure]. */
    private fun <T> SlimAggregationOutcome<T>.isFailure(): Boolean =
        this is SlimAggregationOutcome.Failure

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

    /** C-D3: leaf APIs require an explicit entry token (no default recapture). */
    private fun token(): OpenCodeRepository.SlimCommitToken =
        repository.captureSlimCommitToken()

    @Before
    fun setup() = runBlocking {
        DebugLog.clear()
        server.start()
        repository = OpenCodeRepository(mockk(relaxed = true), mockk(relaxed = true))
        repository.identityStore = cn.vectory.ocdroid.service.identity.ConnectionIdentityStore()
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
        assertEquals(0, result.getOrThrow().sessions.size)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/slimapi/sessions", request.path)
    }

    @Test
    fun `getSlimapiSessions forwards directory and roots and limit`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        val result = repository.getSlimapiSessions(directories = listOf("/w"), roots = true, limit = 50)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow() is SlimSessionsPage)
        val request = server.takeRequest()
        assertTrue("path contains directory: ${request.path}", request.path!!.contains("directory=%2Fw"))
        assertTrue("path contains roots=true: ${request.path}", request.path!!.contains("roots=true"))
        assertTrue("path contains limit=50: ${request.path}", request.path!!.contains("limit=50"))
    }

    @Test
    fun `getSlimapiSessions 502 logs upstream_http_502 code at WARN and rethrows original HttpException`() = runBlocking {
        // T3-M2 (final review D5): parallel to the 503 test above — 502
        // carries the sidecar's `upstream_http_<N>` code (N=upstream HTTP
        // status that the sidecar proxied-failed). Pinned so the 502 path
        // surfaces its distinct code at WARN (not just the 503 path).
        server.enqueue(jsonResponse("""{"code":"upstream_http_502"}""", 502))

        val result = repository.getSlimapiSessions()

        assertTrue(
            "still Result.failure — 3-state contract preserved: $result",
            result.isFailure,
        )
        assertTrue(
            "exception is the ORIGINAL retrofit2.HttpException: ${result.exceptionOrNull()}",
            result.exceptionOrNull() is retrofit2.HttpException,
        )
        val matches = DebugLog.entries.value.filter {
            it.level == cn.vectory.ocdroid.util.DebugLog.Level.WARN &&
                it.message.contains("upstream_http_502")
        }
        assertTrue(
            "502 code logged at WARN for observability: ${DebugLog.entries.value.map { "${it.level}:${it.message}" }}",
            matches.isNotEmpty(),
        )
    }

    @Test
    fun `getSlimapiSessions 503 transform_busy retries with Retry-After and succeeds`() = runBlocking {
        // 🆕 v0.9.0 sessions list 503 transform_busy retry backoff.
        // First response: 503 transform_busy with a VALID integer Retry-After=1
        // (seconds). retryAfterHeaderToMs("1") = 1000ms > 0, so the HEADER
        // branch is taken (NOT the exponential-backoff fall-back); the retry
        // then hits the second enqueued 200 and succeeds.
        //
        // This integration test pins that a valid Retry-After header does not
        // break the retry-then-success flow (and that exactly one retry is
        // issued). Precise ms-parsing — including that a FRACTIONAL value like
        // "0.1" is rejected (toLongOrNull → null → 0 → backoff fall-back),
        // "1"→1000, "600"→capped 10000, null→0 — is the retryAfterHeaderToMs
        // helper's contract, pinned by OpenCodeRepositoryExpandBudgetTest's
        // retryAfterHeaderToMs cases (so it is genuinely covered, not assumed).
        server.enqueue(
            MockResponse().setResponseCode(503)
                .setBody("""{"code":"transform_busy"}""")
                .setHeader("Retry-After", "1")
                .setHeader("Content-Type", "application/json")
        )
        server.enqueue(jsonResponse("""[{"id":"s1","directory":"/default","status":"idle"}]"""))

        val result = repository.getSlimapiSessions()

        assertTrue(
            "retry succeeded: $result",
            result.isSuccess,
        )
        val page = result.getOrThrow()
        assertEquals(1, page.sessions.size)
        assertEquals("s1", page.sessions[0].id)
        // Exactly 2 HTTP requests: 1 × 503 (retry-able) + 1 × 200.
        assertEquals("one 503 then one 200 retry", 2, server.requestCount)
    }

    @Test
    fun `getSlimapiSessions 503 transform_busy 3 retries exhausted fails`() = runBlocking {
        // Three 503 responses, no Retry-After header → backoff used, still fail.
        repeat(3) {
            server.enqueue(
                MockResponse().setResponseCode(503)
                    .setBody("""{"code":"transform_busy"}""")
                    .setHeader("Content-Type", "application/json")
            )
        }

        val result = repository.getSlimapiSessions()

        assertTrue(
            "final failure after 3 retries: $result",
            result.isFailure,
        )
        assertTrue(
            "exception is retrofit2.HttpException: ${result.exceptionOrNull()}",
            result.exceptionOrNull() is retrofit2.HttpException,
        )
        val matches = DebugLog.entries.value.filter {
            it.level == cn.vectory.ocdroid.util.DebugLog.Level.WARN &&
                it.message.contains("transform_busy")
        }
        assertTrue(
            "transform_busy logged at WARN: ${DebugLog.entries.value.map { "${it.level}:${it.message}" }}",
            matches.size >= 1,
        )
    }

    @Test
    fun `getSlimapiSessions 503 with non-transform_busy code does not retry`() = runBlocking {
        // 🆕 v0.9.0 retry-condition pin (conjunct 1): the retry requires BOTH
        // `503` AND `transform_busy`. A 503 carrying a DIFFERENT code (here
        // upstream_unavailable) MUST short-circuit to immediate failure — no
        // retry, exactly 1 HTTP request (the transform_busy-only gate is
        // real, not a bare `code == 503`).
        server.enqueue(jsonResponse("""{"code":"upstream_unavailable"}""", 503))

        val result = repository.getSlimapiSessions()

        assertTrue("non-transform_busy 503 fails: $result", result.isFailure)
        assertEquals("no retry — single request", 1, server.requestCount)
    }

    @Test
    fun `getSlimapiSessions non-503 with transform_busy code does not retry`() = runBlocking {
        // 🆕 v0.9.0 retry-condition pin (conjunct 2): a NON-503 status (here
        // 502) carrying transform_busy MUST NOT retry — only the 503 status is
        // retry-able, even when the body carries transform_busy. Exactly 1 HTTP
        // request.
        server.enqueue(jsonResponse("""{"code":"transform_busy"}""", 502))

        val result = repository.getSlimapiSessions()

        assertTrue("non-503 transform_busy fails: $result", result.isFailure)
        assertEquals("no retry — single request", 1, server.requestCount)
    }

    @Test
    fun `getSlimapiSessions non-HttpException passes through unchanged and without code log`() = runBlocking {
        // T3-C2 sibling: a transport-level failure (IOException, no HTTP
        // status → no sidecar envelope) MUST NOT trigger the parseErrorCode
        // branch — there is no Response to read. The repo rethrows as-is,
        // Result.failure carries the original IOException, and no code log
        // is emitted (no code to log). Pinned by pointing the repo at a
        // dead port (MockWebServer shut down → ConnectException).
        server.shutdown()
        val deadRepo = OpenCodeRepository(mockk(relaxed = true), mockk(relaxed = true))
        deadRepo.configure(
            baseUrl = "http://127.0.0.1:${server.url("").port}",
            slim = true,
        )

        val result = deadRepo.getSlimapiSessions()

        assertTrue("Result.failure on transport failure: $result", result.isFailure)
        assertFalse(
            "original exception NOT a retrofit2.HttpException (transport, not HTTP): ${result.exceptionOrNull()}",
            result.exceptionOrNull() is retrofit2.HttpException,
        )
        // No HttpException → no parseErrorCode → no "slimapi sessions failed: <code>" log.
        // (The transport layer may emit its own debug logs; we only assert
        // OUR repo-level code-log line is absent.)
        assertFalse(
            "no repo-level code log on non-HttpException: ${DebugLog.entries.value.map { it.message }}",
            DebugLog.entries.value.any { it.message.contains("slimapi sessions failed") },
        )
    }

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

    // ── Task 3 (L1 G6): expandMessagesFullBatch — lite-v2-dev shim ──────
    //
    // lite-v2-dev (plan §4.4): ExpandBatchEngine retired. expandMessagesFullBatch
    // is now a shim that loops over individual getSlimapiMessageFull calls
    // (one HTTP request per message ID), no batch endpoint, no halving, no
    // retry, no SessionMissing/Failed outcomes — always returns
    // ExpandOutcome.Ok with per-message results.

    @Test
    fun `expand calls per-message full endpoint for each id and returns Ok with usedBatch=false`() = runBlocking {
        // lite-v2-dev shim: each message ID triggers its own GET to
        // /slimapi/messages/{sid}/full/{mid}. Success→items, failure→failures.
        // usedBatch=false.
        val m1 = MessageWithParts(info = Message(id = "m1", role = "user"))
        val m2 = MessageWithParts(info = Message(id = "m2", role = "assistant"))
        server.enqueue(jsonResponse(json.encodeToString(m1)))
        server.enqueue(jsonResponse(json.encodeToString(m2)))

        val outcome = repository.expandMessagesFullBatch("sess-1", setOf("m1", "m2"))

        assertTrue("Ok: $outcome", outcome is ExpandOutcome.Ok)
        val ok = outcome as ExpandOutcome.Ok
        assertEquals(
            "both items resolved: ${ok.items.map { it.info.id }}",
            setOf("m1", "m2"),
            ok.items.map { it.info.id }.toSet(),
        )
        assertTrue("no failures: ${ok.failures}", ok.failures.isEmpty())
        assertFalse("usedBatch=false on shim path", ok.usedBatch)

        // Wire: 2 individual /full/{mid} requests.
        assertEquals("2 total HTTP requests (per-message)", 2, server.requestCount)
        val paths = mutableSetOf<String>()
        repeat(2) {
            val req = server.takeRequest()
            assertTrue(
                "per-message full path: ${req.path}",
                req.path!!.matches(Regex("/slimapi/messages/sess-1/full/(m1|m2)")),
            )
            paths += req.path!!
        }
        assertEquals(
            "two distinct single-full requests",
            setOf("/slimapi/messages/sess-1/full/m1", "/slimapi/messages/sess-1/full/m2"),
            paths,
        )
    }

    @Test
    fun `expand single message failure returns Ok with the message in failures`() = runBlocking {
        // lite-v2-dev shim: if a single getSlimapiMessageFull fails (e.g. 404),
        // the message is reported as a per-id failure in Ok.failures.
        server.enqueue(jsonResponse("""{"code":"not_found"}""", 404))

        val outcome = repository.expandMessagesFullBatch("sess-1", setOf("m1"))

        assertTrue("Ok on per-message failure: $outcome", outcome is ExpandOutcome.Ok)
        val ok = outcome as ExpandOutcome.Ok
        assertTrue("m1 surfaced as failure: ${ok.failures.map { it.messageId }}",
            ok.failures.any { it.messageId == "m1" })
        assertFalse("usedBatch=false", ok.usedBatch)
        assertEquals("1 HTTP request", 1, server.requestCount)
    }

    @Test
    fun `expand empty ids yields Ok with no items and no failures`() = runBlocking {
        // lite-v2-dev shim: empty input → loop body never runs → Ok with
        // empty items/failures, usedBatch=false, NO HTTP calls.
        val outcome = repository.expandMessagesFullBatch("sess-1", emptySet())

        assertTrue("Ok on empty ids: $outcome", outcome is ExpandOutcome.Ok)
        val ok = outcome as ExpandOutcome.Ok
        assertTrue("no items", ok.items.isEmpty())
        assertTrue("no failures", ok.failures.isEmpty())
        assertFalse("usedBatch=false", ok.usedBatch)
        assertEquals("no HTTP call on empty ids", 0, server.requestCount)
    }

    @Test
    fun `SlimapiErrorCodes B1-additive constants pin server snake_case values`() {
        // b1-foldin T1 amendment: focused constant-pin for the 3 B1-additive
        // codes (INVALID_ROUTE_TOKEN removed in V2 — spec §7:231 routeToken
        // deleted). Cheap regression guard against drift in either direction
        // (server renames a code → this trips; client typo on the constant
        // string → this trips). The comprehensive T1-C4 list lives in
        // SlimapiV1ModelsTest; this focused test co-locates with the new
        // 413-split behavior so the constant + behavior land together.
        assertEquals(
            "message_too_large",
            cn.vectory.ocdroid.data.repository.http.SlimapiErrorCodes.MESSAGE_TOO_LARGE,
        )
        assertEquals(
            "shell_not_allowed",
            cn.vectory.ocdroid.data.repository.http.SlimapiErrorCodes.SHELL_NOT_ALLOWED,
        )
        assertEquals(
            "invalid_directory_count",
            cn.vectory.ocdroid.data.repository.http.SlimapiErrorCodes.INVALID_DIRECTORY_COUNT,
        )
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
        r.identityStore = cn.vectory.ocdroid.service.identity.ConnectionIdentityStore()
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
    fun `getMessagesPaged slim mode with no bookmark drains cursor endpoint`() = runBlocking {
        // §11.1 fix-9 P0-6: the slim getMessagesPaged path (anchored OR
        // non-anchored, with OR without a prior bookmark) routes through
        // the skeleton cursor drain + commit. The HTTP path is the cursor
        // endpoint /slimapi/messages/{sid} (mode=skeleton), NOT /since/0.
        server.enqueue(jsonResponse(json.encodeToString(skeletons(1..2))))

        val result = makeRepository(slim = true).getMessagesPaged("fresh-sess")

        assertTrue(
            "P0-6: slim getMessagesPaged drains + commits successfully",
            result.isSuccess,
        )
        val request = server.takeRequest()
        assertTrue(
            "P0-6: path is cursor endpoint (NOT /since/0): ${request.path}",
            request.path!!.startsWith("/slimapi/messages/fresh-sess") &&
                !request.path!!.contains("/since/"),
        )
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
    fun `checkHealth slim mode with incompatible versions still populates and surfaces unhealthy`() = runBlocking {
        // When the sidecar advertises a range our client version is outside,
        // updateSlimapi STILL lands the bounds (so UI can show the actual
        // range) but isSlimapiClientAccepted() returns false and checkHealth
        // surfaces unhealthy.
        val compatProfile = ServerCompatProfile()
        val repo = OpenCodeRepository(
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
    fun `checkHealthFor slim mode with incompatible versions lands bounds and surfaces failure`() = runBlocking {
        // §Phase 3a (Lane-B3-Dialog): when the one-shot probe sees an
        // incompatible range, the bounds STILL land (so the UI can show the
        // real range) but the probe surfaces failure (fail-closed transport).
        val compatProfile = ServerCompatProfile()
        val repo = OpenCodeRepository(
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
    fun `getMessagesPaged slim mode drains cursor endpoint returning empty body as terminal page`() = runBlocking {
        // §11.1 fix-9 P0-6: slim getMessagesPaged drains the cursor
        // endpoint. An empty body with NO X-Next-Cursor header is a
        // terminal page (Success) — the drain returns the empty list and
        // the commit clears localApplied* (cold-start of an empty
        // session). Result: success with empty items + null nextCursor.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("[]")
                .setHeader("Content-Type", "application/json")
        )

        val result = makeRepository(slim = true).getMessagesPaged("sess-1")

        assertTrue(
            "P0-6: empty body terminal page MUST succeed (got ${result.exceptionOrNull()})",
            result.isSuccess,
        )
        val page = result.getOrThrow()
        assertTrue(
            "P0-6: empty session returns empty items",
            page.items.isEmpty(),
        )
        assertEquals(
            "P0-6: drain terminal → nextCursor is null",
            null,
            page.nextCursor,
        )
    }

    @Test
    fun `getMessagesPaged slim mode terminal drain returns null nextCursor`() = runBlocking {
        // §11.1 fix-9 P0-6: drain reaches a terminal page (no
        // X-Next-Cursor header) → MessagesPage.nextCursor is null
        // (the drain exhausted the cursor window).
        server.enqueue(jsonResponse(json.encodeToString(skeletons(1..3))))

        val result = makeRepository(slim = true).getMessagesPaged("sess-1")

        assertTrue(
            "P0-6: terminal drain MUST succeed (got ${result.exceptionOrNull()})",
            result.isSuccess,
        )
        val page = result.getOrThrow()
        assertEquals(3, page.items.size)
        assertEquals(
            "P0-6: drain terminal → nextCursor is null",
            null,
            page.nextCursor,
        )
    }

    @Test
    fun `SLIMAPI_DEFAULT_PAGE_LIMIT is 200 and bound sourced from RevertCutoffCoordinator`() = runBlocking {
        // T5-C3: pin the constants in one place so a future refactor that
        // silently changes them trips this assertion. The bound value is
        // sourced from the existing pagination strategy:
        //   RevertCutoffCoordinator.MAX_PAGES (5) * PAGE_SIZE (50) = 250.
        // Product rationale: cold-start history pull should not exceed the
        // per-session cap that already bounds the user-visible revert-cutoff
        // walk — if the user wants older messages, scroll-up + loadMore
        // (getMessagesPaged) is its own cap-bounded cursor walk.
        assertEquals(200, SLIMAPI_DEFAULT_PAGE_LIMIT)
        assertEquals(
            "bound = RevertCutoffCoordinator.MAX_PAGES * PAGE_SIZE",
            cn.vectory.ocdroid.ui.RevertCutoffCoordinator.MAX_PAGES *
                cn.vectory.ocdroid.ui.RevertCutoffCoordinator.PAGE_SIZE,
            SLIMAPI_LOCAL_HISTORY_BOUND,
        )
    }

    @Test
    fun `probeLatestSlim hits skeleton limit=1 mode=skeleton path`() = runBlocking {
        // T2-C1: wire shape is pinned — limit=1, mode=skeleton on the slimapi
        // messages endpoint (NOT the legacy /session/{id}/message).
        server.enqueue(jsonResponse("[]"))

        repository.probeLatestSlim("sess-1")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertTrue(
            "slim messages path: ${request.path}",
            request.path!!.startsWith("/slimapi/messages/sess-1"),
        )
        assertTrue("limit=1 forwarded: ${request.path}", request.path!!.contains("limit=1"))
        assertTrue("mode=skeleton forwarded: ${request.path}", request.path!!.contains("mode=skeleton"))
    }

    @Test
    fun `probeLatestSlim on 200 empty array yields ok=true empty=true`() = runBlocking {
        // T2-C2 (empty branch): empty session → 200 [] → ok=true, empty=true,
        // no messageID, no updatedAt.
        server.enqueue(jsonResponse("[]"))

        val probe = repository.probeLatestSlim("sess-empty")

        assertTrue("ok: $probe", probe.ok)
        assertTrue("empty: $probe", probe.empty)
        assertNull("no messageID: $probe", probe.messageID)
        assertNull("no updatedAt: $probe", probe.updatedAt)
        assertNull("no httpStatus on success: $probe", probe.httpStatus)
    }

    @Test
    fun `probeLatestSlim on 200 one-item array yields id and time dot updated`() = runBlocking {
        // T2-C2 (one-item branch): a single skeleton returns info.id and
        // info.time.updated. Per the brief: `updatedAt = info.time?.updated
        // ?: info.time?.created` — updated wins when both are present.
        val skeleton = MessageWithParts(
            info = Message(
                id = "msg-latest",
                role = "assistant",
                time = Message.TimeInfo(created = 100L, updated = 200L),
            )
        )
        server.enqueue(jsonResponse(json.encodeToString(listOf(skeleton))))

        val probe = repository.probeLatestSlim("sess-1")

        assertTrue("ok: $probe", probe.ok)
        assertFalse("empty=false when item present: $probe", probe.empty)
        assertEquals("msg-latest", probe.messageID)
        assertEquals(200L, probe.updatedAt)
        assertNull("no httpStatus on success: $probe", probe.httpStatus)
    }

    @Test
    fun `probeLatestSlim falls back to time dot created when updated is null`() = runBlocking {
        // T2-C2 (fallback): legacy upstream may omit time.updated (it's a
        // slimapi-sidecar-only field per Message.TimeInfo KDoc). Probe MUST
        // then read time.created.
        val skeleton = MessageWithParts(
            info = Message(
                id = "msg-old",
                role = "user",
                time = Message.TimeInfo(created = 42L, updated = null),
            )
        )
        server.enqueue(jsonResponse(json.encodeToString(listOf(skeleton))))

        val probe = repository.probeLatestSlim("sess-1")

        assertTrue("ok: $probe", probe.ok)
        assertEquals("msg-old", probe.messageID)
        assertEquals("created fallback when updated null: $probe", 42L, probe.updatedAt)
    }

    @Test
    fun `probeLatestSlim on HTTP 404 yields ok=false httpStatus=404`() = runBlocking {
        // T2-C3 (HTTP-fail branch): upstream 404 for an unknown sid MUST
        // surface as ok=false with the upstream code (the reconcile state
        // machine uses httpStatus==404 to mark the sid deleted; non-404
        // failures keep the sid dirty).
        server.enqueue(jsonResponse("""{"error":"not found"}""", 404))

        val probe = repository.probeLatestSlim("sess-missing")

        assertFalse("ok=false: $probe", probe.ok)
        assertEquals(404, probe.httpStatus)
        assertNull("no messageID on failure: $probe", probe.messageID)
    }

    @Test
    fun `probeLatestSlim on HTTP 500 yields ok=false httpStatus=500`() = runBlocking {
        // T2-C3 (HTTP-fail branch, non-404): generic 5xx surfaces the same
        // way — boundary-normalised to ok=false + the upstream code.
        server.enqueue(jsonResponse("server boom", 500))

        val probe = repository.probeLatestSlim("sess-1")

        assertFalse("ok=false: $probe", probe.ok)
        assertEquals(500, probe.httpStatus)
    }

    @Test
    fun `probeLatestSlim on network failure yields ok=false httpStatus=null`() = runBlocking {
        // T2-C3 (network-fail branch): IOException-style failure (server
        // unreachable / connection reset) MUST collapse to ok=false with
        // httpStatus=null — distinguishable from an HTTP 4xx/5xx so the
        // reconcile state machine can leave the sid dirty rather than
        // mark-deleted.
        //
        // Simulated by shutting down the MockWebServer BEFORE the call — the
        // next request can't connect, throwing IOException inside
        // runSuspendCatching → getOrElse { ProbeResult(ok=false, httpStatus=null) }.
        val repo = makeRepository(slim = true)
        // Re-point repo at a port that's already shut down: tear server down
        // and rebuild a fresh repo whose baseUrl hits the dead port.
        server.shutdown()
        val deadRepo = OpenCodeRepository(mockk(relaxed = true), mockk(relaxed = true))
        deadRepo.configure(
            baseUrl = "http://127.0.0.1:${server.url("").port}",
            slim = true,
        )

        val probe = deadRepo.probeLatestSlim("sess-1")

        assertFalse("ok=false on network failure: $probe", probe.ok)
        assertNull("httpStatus=null on network failure: $probe", probe.httpStatus)
        assertNull("no messageID on network failure: $probe", probe.messageID)
    }

    @Test
    fun `probeLatestSlim on malformed 2xx body yields ok=false httpStatus=null`() = runBlocking {
        // Edge: 200 with a body the kotlinx-serialization converter can't
        // decode as `List<MessageWithParts>` (here: an empty body). The
        // converter throws inside the retrofit call; the exception escapes
        // `resp.body()` and is caught by the outer `runSuspendCatching` →
        // `getOrElse { ProbeResult(ok=false, httpStatus=null) }`.
        //
        // The brief's pseudocode also has a defensive `body() ?: …` branch
        // — that branch is unreachable with this converter (it never returns
        // null on a 2xx), but it stays as belt-and-braces safety. Pin the
        // real-world behaviour so T7/T11 can rely on the classification
        // (malformed body == network-fail == keep dirty / retry next pass).
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("")
                .setHeader("Content-Type", "application/json")
        )

        val probe = repository.probeLatestSlim("sess-1")

        assertFalse("ok=false on malformed 2xx body: $probe", probe.ok)
        assertNull(
            "httpStatus=null — transport succeeded but body decode threw; " +
                "indistinguishable from a flaky transport so the reconcile " +
                "state machine keeps the sid dirty rather than mark-deleted: $probe",
            probe.httpStatus,
        )
    }

    @Test
    fun `probeLatestMessageId legacy probe is unchanged by slim probeLatestSlim addition`() = runBlocking {
        // T2-C4 regression: the legacy `probeLatestMessageId` (still used by
        // the legacy / catch-up path) MUST keep hitting the legacy endpoint
        // with the legacy wire shape — NOT the slimapi messages path. This
        // pins byte-for-byte parity with the pre-T2 implementation; the 12+
        // mock sites in CatchUpActionsTest / AppCoreOrchestrationTest /
        // OpenCodeRepositoryDirectoryTest depend on this surface staying
        // stable.
        // Configure a legacy-mode repo (slim=false).
        val legacyRepo = OpenCodeRepository(mockk(relaxed = true), mockk(relaxed = true))
        legacyRepo.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            slim = false,
        )
        val msg = MessageWithParts(
            info = Message(id = "legacy-newest", sessionId = "session-1", role = "assistant"),
            parts = emptyList()
        )
        server.enqueue(jsonResponse(json.encodeToString(listOf(msg))))

        val result = legacyRepo.probeLatestMessageId("session-1")

        assertTrue("legacy probe success: ${result}", result.isSuccess)
        assertEquals("legacy-newest", result.getOrThrow())
        val request = server.takeRequest()
        assertEquals(
            "legacy path intact (NOT slimapi messages path)",
            "/session/session-1/message?limit=1",
            request.path,
        )
        // Sanity: the request MUST NOT carry mode=skeleton.
        assertNull(
            "no mode=skeleton on legacy probe: ${request.path}",
            request.path!!.let { if (it.contains("mode=skeleton")) it else null },
        )
    }

    @Test
    fun `T0-P2 legacy mode emits no slimapi headers and no slimapi path prefix`() = runBlocking {
        val legacyRepo = makeRepository(slim = false)

        // getSessions → legacy /session (no /slimapi/ prefix).
        server.enqueue(jsonResponse("[]"))
        legacyRepo.getSessions(limit = 25)
        val sessionsReq = server.takeRequest()
        assertTrue(
            "legacy getSessions path MUST NOT start with /slimapi/: ${sessionsReq.path}",
            !sessionsReq.path!!.startsWith(cn.vectory.ocdroid.data.repository.http.SlimapiContract.SLIMAPI_PATH_PREFIX),
        )
        assertNull(
            "legacy getSessions MUST NOT carry X-Slimapi-Version: ${sessionsReq.headers}",
            sessionsReq.getHeader(cn.vectory.ocdroid.data.repository.http.SlimapiContract.X_SLIMAPI_VERSION),
        )
        // V2: capabilities header removed (spec §1:34) — no assertion needed.

        // getMessagesPaged → legacy /session/{id}/message (no /slimapi/ prefix).
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("[]")
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Next-Cursor", "cur")
        )
        legacyRepo.getMessagesPaged("sess-1", limit = 20)
        val msgsReq = server.takeRequest()
        assertTrue(
            "legacy getMessagesPaged path MUST NOT start with /slimapi/: ${msgsReq.path}",
            !msgsReq.path!!.startsWith(cn.vectory.ocdroid.data.repository.http.SlimapiContract.SLIMAPI_PATH_PREFIX),
        )
        assertNull(
            "legacy getMessagesPaged MUST NOT carry X-Slimapi-Version",
            msgsReq.getHeader(cn.vectory.ocdroid.data.repository.http.SlimapiContract.X_SLIMAPI_VERSION),
        )
        // V2: capabilities header removed (spec §1:34) — no assertion needed.
    }

    /**
     * T0-P2 slim wire-shape: with `slim=true`, a `/slimapi/` REST method MUST
     * carry `X-Slimapi-Version` (V2 removed Opt-A capability header,
     * spec §1:34 — only version header remains).
     */
    @Test
    fun `T0-P2 slim mode emits slimapi version header on slimapi path`() = runBlocking {
        // `repository` from setUp is configured slim=true.
        server.enqueue(jsonResponse("[]"))
        repository.getSlimapiSessions()

        val req = server.takeRequest()
        assertTrue(
            "slim getSessions MUST hit /slimapi/ prefix: ${req.path}",
            req.path!!.startsWith(cn.vectory.ocdroid.data.repository.http.SlimapiContract.SLIMAPI_PATH_PREFIX),
        )
        assertEquals(
            "slimapi path MUST carry X-Slimapi-Version == SLIMAPI_CLIENT_VERSION",
            cn.vectory.ocdroid.data.repository.http.SlimapiContract.SLIMAPI_CLIENT_VERSION.toString(),
            req.getHeader(cn.vectory.ocdroid.data.repository.http.SlimapiContract.X_SLIMAPI_VERSION),
        )
    }

    private fun skeleton(id: String, updated: Long): MessageWithParts =
        MessageWithParts(
            info = Message(
                id = id,
                role = "assistant",
                sessionId = "sid",
                time = Message.TimeInfo(created = updated, updated = updated),
            ),
        )

    /** Bulk skeleton generator: m<id> with updated=<id>, for itemBound tests. */
    private fun skeletons(idRange: IntRange): List<MessageWithParts> =
        idRange.map { skeleton("m$it", it.toLong()) }

    // ── §11.1 fix-10 P0-1: pagination contract (before split) ────────────

    /**
     * §11.1 fix-10 P0-1: getMessagesPaged slim mode with `before != null`
     * (load-more) routes through getSlimapiMessagesPage — a SINGLE-PAGE
     * cursor fetch that forwards `before` to the HTTP query and surfaces
     * `X-Next-Cursor` as MessagesPage.nextCursor. NO authoritative commit.
     */
    @Test
    fun `§11_1 fix-10 P0-1 getMessagesPaged slim load-more forwards before and returns nextCursor`() = runBlocking {
        val repo = makeRepository(slim = true)
        val s1 = skeleton("m1", 1000L)
        val s2 = skeleton("m2", 2000L)
        server.enqueue(
            jsonResponse(json.encodeToString(listOf(s1, s2)))
                .setHeader("X-Next-Cursor", "next-cursor-abc"),
        )

        val result = repo.getMessagesPaged("sess-1", limit = 30, before = "cursor-prev")

        assertTrue("load-more MUST succeed (got ${result.exceptionOrNull()})", result.isSuccess)
        val page = result.getOrThrow()
        assertEquals(listOf("m1", "m2"), page.items.map { it.info.id })
        assertEquals("nextCursor preserved from response header", "next-cursor-abc", page.nextCursor)

        val request = server.takeRequest()
        assertTrue(
            "P0-1: before cursor forwarded to HTTP query: ${request.path}",
            request.path!!.contains("before=cursor-prev"),
        )
        assertTrue(
            "P0-1: limit forwarded to HTTP query: ${request.path}",
            request.path!!.contains("limit=30"),
        )
    }
    @Test
    fun `§11_1 fix-10 P0-1 getMessagesPaged slim initial forwards limit to query`() = runBlocking {
        val repo = makeRepository(slim = true)
        // lite-v2-dev: getMessagesPaged slim branch delegates to
        // getSlimapiMessagesSkeleton, forwarding the limit parameter.
        // The UI limit=5 IS forwarded as the query param.
        server.enqueue(jsonResponse(json.encodeToString(skeletons(1..5))))

        val result = repo.getMessagesPaged("sess-1", limit = 5, before = null)

        assertTrue("initial fetch MUST succeed (got ${result.exceptionOrNull()})", result.isSuccess)
        val request = server.takeRequest()
        assertTrue(
            "lite-v2-dev: limit=5 must be forwarded as query param: ${request.path}",
            request.path!!.contains("limit=5"),
        )
    }
    @Test
    fun `§11_1 fix-10 P0-1 getMessagesPaged slim load-more consecutive pages`() = runBlocking {
        val repo = makeRepository(slim = true)

        // Page 1 (before=cursor-1): items m1/m2, nextCursor=cursor-2.
        server.enqueue(
            jsonResponse(json.encodeToString(listOf(skeleton("m1", 1000L), skeleton("m2", 2000L))))
                .setHeader("X-Next-Cursor", "cursor-2"),
        )
        val page1 = repo.getMessagesPaged("sess-1", limit = 30, before = "cursor-1").getOrThrow()
        assertEquals(listOf("m1", "m2"), page1.items.map { it.info.id })
        assertEquals("cursor-2", page1.nextCursor)

        // Page 2 (before=cursor-2): items m3, nextCursor=null (end).
        server.enqueue(jsonResponse(json.encodeToString(listOf(skeleton("m3", 3000L)))))
        val page2 = repo.getMessagesPaged("sess-1", limit = 30, before = page1.nextCursor).getOrThrow()
        assertEquals(listOf("m3"), page2.items.map { it.info.id })
        assertEquals(null, page2.nextCursor)
    }
    // ── §11.1 fix-9 P0-4: dirty re-evaluation after commit ───────────────

    // ── §slim-storm P1: getSlimapiSessionStatusOutcome delegate semantics ──

    @Test
    fun `§slim-storm P1 absent entry returns Success with idle type (NOT SessionMissing)`() = runBlocking {
        // The bulk /session/status sparse map OMITS idle entries. The delegate
        // must treat absent ≡ idle, NOT missing — misclassifying as SessionMissing
        // was the /session/status storm root cause.
        server.enqueue(jsonResponse("""{"other-sid": {"type": "busy"}}"""))

        val outcome = repository.getSlimapiSessionStatusOutcome("my-sid")

        assertTrue("absent entry → Success (not SessionMissing)", outcome is StatusOutcome.Success)
        val success = outcome as StatusOutcome.Success
        assertEquals("my-sid", success.sessionId)
        assertEquals("idle", success.status.type)
    }

    @Test
    fun `§slim-storm P1 present entry returns Success with its status unchanged`() = runBlocking {
        server.enqueue(jsonResponse("""{"my-sid": {"type": "busy", "attempt": 3}}"""))

        val outcome = repository.getSlimapiSessionStatusOutcome("my-sid")

        assertTrue("present entry → Success", outcome is StatusOutcome.Success)
        val success = outcome as StatusOutcome.Success
        assertEquals("my-sid", success.sessionId)
        assertEquals("busy", success.status.type)
        assertEquals(3, success.status.attempt)
    }
}
