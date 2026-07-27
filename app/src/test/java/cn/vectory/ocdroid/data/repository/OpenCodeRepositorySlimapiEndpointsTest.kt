package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.PermissionResponse
import cn.vectory.ocdroid.data.model.SlimSessionDigest
import cn.vectory.ocdroid.data.model.SlimSessionsPage
import cn.vectory.ocdroid.data.model.SlimapiPermissionEntry
import cn.vectory.ocdroid.data.model.SlimapiQuestionEntry
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
    fun `getSlimapiSessions forwards list of directories as repeated query`() = runBlocking {
        // §slim-reconcile-lane-repo (B4 T6): the v1 contract pins
        // `?directory=a&directory=b` (repeated params, NOT comma-joined).
        // Retrofit expands a List<String> into repeated query entries.
        server.enqueue(jsonResponse("[]"))

        val result = repository.getSlimapiSessions(directories = listOf("/w1", "/w2", "/w3"))

        assertTrue(result.isSuccess)
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

    // ── Task 3 (slimapi v0.2.2): /sessions list coded-error observability ──
    //
    // oc-slimapi v0.2.2 changed /slimapi/sessions failure bodies from
    // FastAPI-default {"detail":...} passthrough to the sidecar's own
    // coded envelope ({"code":"upstream_unavailable"} / upstream_http_<N>
    // / etc.). Correctness-wise the existing "non-200 = failure" coarse
    // handling already worked; Task 3 makes the failure OBSERVABLE
    // (warn-level log carrying the parsed code) + CONSISTENT with the
    // other catch sites that already use [parseErrorCode], WITHOUT
    // changing the public Result shape (still Result.failure; still
    // folded to null by coldStartSlimSync's .getOrNull() → 3-state
    // preserved) and WITHOUT introducing a new exception type (grill
    // confirmed no caller does as? HttpException specialization → a
    // carrier type with a code nobody consumes = YAGNI).

    @Test
    fun `getSlimapiSessions 503 logs upstream_unavailable code at WARN and rethrows original HttpException`() = runBlocking {
        // T3-C2: on retrofit2.HttpException the repo MUST parseErrorCode
        // the sidecar's coded body, log the code at WARN (observability),
        // and rethrow the ORIGINAL exception (no wrapper type).
        server.enqueue(jsonResponse("""{"code":"upstream_unavailable"}""", 503))

        val result = repository.getSlimapiSessions()

        assertTrue(
            "still Result.failure — 3-state contract with coldStartSlimSync preserved: $result",
            result.isFailure,
        )
        assertTrue(
            "exception is the ORIGINAL retrofit2.HttpException (NOT wrapped in a new type): ${result.exceptionOrNull()}",
            result.exceptionOrNull() is retrofit2.HttpException,
        )
        // DebugLog.entries is a newest-first StateFlow; @Before already clear()ed it.
        // T3-M2 (final review D5): tighten to assert BOTH the code message
        // substring AND the level == WARN (previously only the substring
        // was checked, leaving the door open to a level regression to
        // DEBUG/INFO silently degrading observability).
        val matches = DebugLog.entries.value.filter {
            it.level == cn.vectory.ocdroid.util.DebugLog.Level.WARN &&
                it.message.contains("upstream_unavailable")
        }
        assertTrue(
            "code logged at WARN for observability: ${DebugLog.entries.value.map { "${it.level}:${it.message}" }}",
            matches.isNotEmpty(),
        )
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

    // ── /slimapi/messages/{sid}/since/{ts} (§5 A2=A) ───────────────────────

    @Test
    fun `getSlimapiMessagesSince facade returns staging-only failure after stage A`() = runBlocking {
        // §11.1 stage A: the legacy facade is retained for source/test binary
        // compatibility but is NO LONGER a reliability path. It returns
        // Result.failure(SlimSinceStagingOnlyException) unconditionally and
        // performs NO bookmark / localApplied / dirty mutation.
        val result = repository.getSlimapiMessagesSince("sess-1", since = 150L, token = token())

        assertTrue("facade must return failure", result.isFailure)
        assertTrue(
            "cause must be SlimSinceStagingOnlyException (got ${result.exceptionOrNull()?.javaClass?.name})",
            result.exceptionOrNull() is SlimSinceStagingOnlyException,
        )
    }

    @Test
    fun `fetchSinceForStageA hits since path and returns Staged`() = runBlocking {
        val skeleton = MessageWithParts(
            info = Message(
                id = "m1",
                role = "assistant",
                time = Message.TimeInfo(created = 100L, updated = 200L),
            )
        )
        server.enqueue(jsonResponse(json.encodeToString(listOf(skeleton))))

        val outcome = repository.fetchSinceForStageA("sess-1", since = 150L, token = token())

        assertTrue("Staged outcome expected", outcome is SlimSinceStageAOutcome.Staged)
        val staged = outcome as SlimSinceStageAOutcome.Staged
        assertEquals(1, staged.items.size)
        assertEquals("m1", staged.items[0].info.id)
        assertEquals(200L, staged.items[0].info.time?.updated)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(
            "/slimapi/messages/sess-1/since/150",
            request.path,
        )
    }

    @Test
    fun `fetchSinceForStageA does NOT bump local bookmark`() = runBlocking {
        // §11.1 stage A: staging-only — no bookmark advancement.
        val s1 = MessageWithParts(
            info = Message(id = "m1", role = "assistant",
                time = Message.TimeInfo(updated = 200L))
        )
        val s2 = MessageWithParts(
            info = Message(id = "m2", role = "assistant",
                time = Message.TimeInfo(updated = 300L))
        )
        server.enqueue(jsonResponse(json.encodeToString(listOf(s1, s2))))

        repository.fetchSinceForStageA("sess-1", since = 0L, token = token())

        // Bookmark MUST NOT be advanced (stage A staging-only).
        val state = repository.snapshotSlimSseState()["sess-1"]
        assertTrue("no bookmark entry should be created by staging fetch", state == null || state.updatedAt != 300L)
    }

    @Test
    fun `fetchSinceForStageA surfaces HTTP error as Failed outcome`() = runBlocking {
        server.enqueue(jsonResponse("nope", 500))

        val outcome = repository.fetchSinceForStageA("sess-1", since = 0L, token = token())

        assertTrue("Failed outcome expected", outcome is SlimSinceStageAOutcome.Failed)
        val request = server.takeRequest()
        assertEquals("/slimapi/messages/sess-1/since/0", request.path)
    }

    @Test
    fun `fetchSinceForStageA forwards limit and before cursor`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        repository.fetchSinceForStageA("sess-1", since = 100L, limit = 50, before = "abc", token = token())

        val request = server.takeRequest()
        assertTrue("limit forwarded: ${request.path}", request.path!!.contains("limit=50"))
        assertTrue("before forwarded: ${request.path}", request.path!!.contains("before=abc"))
    }

    @Test
    fun `fetchSinceForStageA reads X-Since-Complete header but stays staging-only`() = runBlocking {
        val skeleton = MessageWithParts(
            info = Message(id = "m1", role = "assistant",
                time = Message.TimeInfo(updated = 200L))
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(json.encodeToString(listOf(skeleton)))
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Since-Complete", "true")
        )

        val outcome = repository.fetchSinceForStageA("sess-1", since = 0L, token = token())

        assertTrue("Staged expected even with X-Since-Complete: true", outcome is SlimSinceStageAOutcome.Staged)
        val staged = outcome as SlimSinceStageAOutcome.Staged
        assertEquals("completeHeader must be parsed as true", true, staged.completeHeader)
        assertEquals(200, staged.statusCode)
        assertTrue("transportComplete must be true", staged.transportComplete)
        // NO bookmark bump even when complete=true.
        val state = repository.snapshotSlimSseState()["sess-1"]
        assertTrue("no bookmark entry", state == null || state.updatedAt != 200L)
    }

    // ── applySlimDigest + fetch trigger ────────────────────────────────────

    @Test
    fun `applySlimDigest on fresh session triggers fetch anchored on zero`() {
        val decision = repository.applySlimDigest(
            SlimSessionDigest(sessionId = "fresh", updatedAt = 42L),
            token = token(),
        )
        assertNotNull(decision)
        assertEquals("fresh", decision!!.sessionId)
        assertEquals(0L, decision.since)
    }

    @Test
    fun `applySlimDigest with strictly newer updatedAt triggers fetch on prior bookmark`() {
        repository.applySlimDigest(
            SlimSessionDigest(sessionId = "s1", updatedAt = 100L),
            token = token(),
        )
        val decision = repository.applySlimDigest(
            SlimSessionDigest(sessionId = "s1", updatedAt = 200L),
            token = token(),
        )
        assertNotNull(decision)
        // T6 rev-gpt Critical fix: the fetch `since` anchor is
        // localAppliedUpdatedAt (NOT max(remote, local)). No REST
        // reconcile has happened here → localAppliedUpdatedAt is null →
        // since=0L. Asserting 100L (the digest-observed remote) would
        // re-introduce the Critical consistency bug where a failed
        // reconcile + fresh digest skips the (localApplied, remote] range.
        assertEquals(0L, decision!!.since)
    }

    @Test
    fun `applySlimDigest null on blank sessionId`() {
        val decision = repository.applySlimDigest(
            SlimSessionDigest(sessionId = "", updatedAt = 1L),
            token = token(),
        )
        assertNull(decision)
    }

    @Test
    fun `applySlimDigest null when no updatedAt change`() {
        repository.applySlimDigest(
            SlimSessionDigest(sessionId = "s1", updatedAt = 100L),
            token = token(),
        )
        val decision = repository.applySlimDigest(
            SlimSessionDigest(sessionId = "s1", status = "busy"),  // no updatedAt
            token = token(),
        )
        assertNull(decision)
    }

    // ── /slimapi/questions + /slimapi/permissions ──────────────────────────

    @Test
    fun `getSlimapiQuestions hits slimapi questions path`() = runBlocking {
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))

        repository.getSlimapiQuestions(token = token())

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

        val result = repository.getSlimapiQuestions(token = token())

        assertTrue(result.isSuccess)
        val entries = result.getOrThrow().items()
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

        val result = repository.getSlimapiQuestions(token = token())

        assertTrue(result.isSuccess)
        val entries = result.getOrThrow().items()
        assertEquals(1, entries.size)
        assertEquals("q-ok", entries[0].id)
    }

    // ── Task 2 (slimapi v0.2.2): scope.directories JSON → Success.serverScope e2e ──
    //
    // T2-C2 currently pins the DTO + gating by code review; this test
    // locks the JSON→`Success.serverScope` end-to-end path through the
    // real kotlinx deserializer + aggregationOutcome wiring. Without this,
    // a future refactor that drops the `scope` field from the DTO (or
    // breaks the envelope→Success wiring) would silently re-open the
    // N==0 false-clear hole that T2-C3 pins at the coordinator layer.

    @Test
    fun `getSlimapiQuestions parses scope directories into Success serverScope`() = runBlocking {
        // sidecar envelope carries scope.directories=N → must surface as
        // Success.serverScope.directories == N (the gating signal for the
        // N==0 retain-prior branch in applyAggregationOutcome).
        val body = """{
            "items": [],
            "errors": [],
            "scope": {"directories": 2}
        }""".trimIndent()
        server.enqueue(jsonResponse(body))

        val result = repository.getSlimapiQuestions(token = token())

        assertTrue(result.isSuccess)
        val outcome = result.getOrThrow()
        assertTrue("envelope → Success: $outcome", outcome is SlimAggregationOutcome.Success)
        assertEquals(
            "scope.directories N parses into Success.serverScope.directories",
            2,
            (outcome as SlimAggregationOutcome.Success).serverScope?.directories,
        )
    }

    @Test
    fun `getSlimapiQuestions with absent scope yields null Success serverScope`() = runBlocking {
        // 503 / old sidecar / pre-0.2.2 envelope omits `scope` entirely →
        // kotlinx defaults it to null → Success.serverScope == null →
        // gating falls through to the original scope=null clear-stale
        // behavior (T2-C3 backward-compat).
        val body = """{
            "items": [],
            "errors": []
        }""".trimIndent()
        server.enqueue(jsonResponse(body))

        val result = repository.getSlimapiQuestions(token = token())

        assertTrue(result.isSuccess)
        val outcome = result.getOrThrow() as SlimAggregationOutcome.Success
        assertNull(
            "absent scope → Success.serverScope == null (backward compat)",
            outcome.serverScope,
        )
    }

    @Test
    fun `getSlimapiPermissions hits slimapi permissions path`() = runBlocking {
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))

        repository.getSlimapiPermissions(token = token())

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

        val result = repository.getSlimapiPermissions(token = token())

        assertTrue(result.isSuccess)
        val entries = result.getOrThrow().items()
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

        val result = repository.coldStartSlimSync(token = token())

        assertTrue(result.isSuccess)
        val snapshot = result.getOrThrow()
        // T11 round-2 (oracle D2): successful empty → emptyList (NOT null).
        assertNotNull("successful empty sessions → empty list, not null", snapshot.sessions)
        assertTrue("successful empty questions → Success with empty items", snapshot.questions is SlimAggregationOutcome.Success)
        assertTrue("successful empty permissions → Success with empty items", snapshot.permissions is SlimAggregationOutcome.Success)
        assertEquals(0, (snapshot.questions as SlimAggregationOutcome.Success).items.size)
        assertEquals(0, (snapshot.permissions as SlimAggregationOutcome.Success).items.size)
        assertNull("no openSessionId → messages null", snapshot.messages)

        // Three requests were made in any order (parallel-ish sequential).
        // §session-scope-narrow: assert the sessions request carries the
        // EXACT scope-narrowing query params (`roots=true` +
        // `limit=SLIM_COLDSTART_SESSION_LIMIT`) by parsing the recorded
        // URL with okhttp3.HttpUrl — NOT string-contains, which is too weak
        // (a bare `/slimapi/sessions` prefix would pass even after a
        // regression that dropped both params).
        val requests = mutableListOf<okhttp3.mockwebserver.RecordedRequest>()
        repeat(3) { requests += server.takeRequest() }
        val paths = requests.map { it.path!! }
        val sessionsRequest = requests.first { it.path!!.startsWith("/slimapi/sessions") }
        val sessionsUrl = sessionsRequest.requestUrl!!
        assertEquals(
            "roots=true forwarded on sessions call: ${sessionsRequest.path}",
            "true",
            sessionsUrl.queryParameter("roots"),
        )
        assertEquals(
            "limit=SLIM_COLDSTART_SESSION_LIMIT forwarded: ${sessionsRequest.path}",
            SlimSyncEngine.SLIM_COLDSTART_SESSION_LIMIT.toString(),
            sessionsUrl.queryParameter("limit"),
        )
        // coldStartSlimSync(token=…) with directories=null MUST NOT add
        // any ?directory= query (the sidecar decides scope server-side).
        assertNull(
            "no directory param when directories=null: ${sessionsRequest.path}",
            sessionsUrl.queryParameter("directory"),
        )
        assertTrue("questions path hit: $paths", paths.any { it == "/slimapi/questions" })
        assertTrue("permissions path hit: $paths", paths.any { it == "/slimapi/permissions" })
    }

    @Test
    fun `coldStartSlimSync with openSessionId anchored since is staging-only after stage A`() = runBlocking {
        // §11.1 stage A: cold-start's anchored `/since` branch maps
        // Staged / Incomplete / Failed to `messages = null` (keep prior).
        // NO bookmark bump, NO authoritative commit on the `/since` path.
        // §11.1 fix-6 P0-5: seed LOCAL-applied bookmark at 100 (not remote).
        // The anchor uses localAppliedUpdatedAt only — remoteUpdatedAt alone
        // does NOT trigger the anchored path.
        val seedToken = token()
        repository.applySlimDigest(
            SlimSessionDigest(sessionId = "sess-1", updatedAt = 100L),
            token = seedToken,
        )
        // Simulate a successful prior reconcile that advanced localApplied*.
        // bumpSlimBookmarkFromItems derives the tuple from items via onReconcileSuccess.
        assertTrue(
            "seed bump must succeed",
            repository.bumpSlimBookmarkFromItems(
                "sess-1",
                listOf(
                    MessageWithParts(info = Message(id = "m-seed", role = "assistant", time = Message.TimeInfo(updated = 100L)))
                ),
                seedToken,
            ),
        )
        server.enqueue(jsonResponse("[]"))               // sessions
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))   // questions
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))   // permissions
        server.enqueue(jsonResponse("[]"))               // messages since 100 (staging-only)

        val result = repository.coldStartSlimSync(openSessionId = "sess-1", token = token())

        assertTrue(result.isSuccess)
        val snapshot = result.getOrThrow()
        // §11.1 stage A: anchored /since is staging-only → messages = null.
        assertNull(
            "anchored /since is staging-only → messages = null (keep prior)",
            snapshot.messages,
        )

        // 4 requests in total. The anchored /since request IS still issued
        // (for staging/diagnostics), but its result is discarded.
        val paths = mutableListOf<String>()
        repeat(4) { paths += server.takeRequest().path!! }
        assertTrue(
            "anchored /since request still issued (staging): $paths",
            paths.any { it.startsWith("/slimapi/messages/sess-1/since/100") }
        )
    }

    @Test
    fun `coldStartSlimSync degrades per-piece on HTTP failure`() = runBlocking {
        // sessions 500, questions 200, permissions 500, no messages requested.
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.coldStartSlimSync(token = token())

        assertTrue(
            "per-piece degradation yields overall success (snapshot has empty failures)",
            result.isSuccess
        )
        val snapshot = result.getOrThrow()
        // T11 round-2 (oracle D2): HTTP-failed pieces → null (NOT empty).
        // The caller can now distinguish "keep prior" (null) from
        // "authoritative-empty" (emptyList).
        assertNull("sessions HTTP 500 → null (NOT empty list)", snapshot.sessions)
        assertTrue("questions 200 OK with empty items → Success", snapshot.questions is SlimAggregationOutcome.Success)
        assertEquals(0, (snapshot.questions as SlimAggregationOutcome.Success).items.size)
        assertTrue("permissions HTTP 500 → Failure", snapshot.permissions.isFailure())
    }

    // ── resync = reuse cold-start: same code path ──────────────────────────

    @Test
    fun `resync equals cold-start code path - same endpoints hit`() = runBlocking {
        // Simulate: cold-start ran once, then a resync fires. Both should
        // hit the SAME three endpoints (sessions / questions / permissions).
        server.enqueue(jsonResponse("[]"))  // cold-start sessions
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))  // cold-start questions
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))  // cold-start permissions

        repository.coldStartSlimSync(token = token())

        // Resync fires — same code path.
        server.enqueue(jsonResponse("[]"))
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))

        val resyncResult = repository.coldStartSlimSync(token = token())

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
            SlimSessionDigest(sessionId = "s1", updatedAt = 100L),
            token = token(),
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

    // ── Task 3 (L1 G6): expandMessagesFullBatch — full retry strategy ──────
    //
    // §slimapi-client-impl-v1 §5 G6 — the batched full-expansion entry
    // point with the COMPLETE retry/halve/backoff/fallback strategy
    // living in the repo. T15 (usecase) + T16 (UI) consume
    // [ExpandOutcome] without ever branching on HTTP status; this suite
    // pins each branch (C1-C8) via MockWebServer enqueue sequences so
    // the strategy cannot regress silently.
    //
    // Key invariant: the path assertion uses `request.path` WITHOUT a
    // "GET " prefix (v1 test bug — MockWebServer's RecordedRequest.path
    // is the path+query, NOT the request line).

    @Test
    fun `expand 200 returns deduped ordered items and surfaces per-message errors as failedIds`() = runBlocking {
        // T3-C1: 200 envelope — items returned in the request's deduped
        // order; per-message errors from `errors[]` land in failedIds.
        val item1 = MessageWithParts(info = Message(id = "m1", role = "user"))
        val item2 = MessageWithParts(info = Message(id = "m2", role = "assistant"))
        val body = """{"items":[{"info":{"id":"m1","role":"user"},"parts":[]},
                                {"info":{"id":"m2","role":"assistant"},"parts":[]}],
                        "errors":[{"messageID":"m3","code":"message_not_found"}]}"""
        server.enqueue(jsonResponse(body))

        val outcome = repository.expandMessagesFullBatch(
            "sess-1", listOf("m1", "m2", "m1", "m3"),  // dedup → m1,m2,m3
        )

        assertTrue("Ok: $outcome", outcome is ExpandOutcome.Ok)
        val ok = outcome as ExpandOutcome.Ok
        assertEquals(
            "items deduped preserve order: ${ok.items.map { it.info.id }}",
            listOf("m1", "m2"),
            ok.items.map { it.info.id },
        )
        assertEquals("failedIds from envelope errors[]: ${ok.failures.map { it.messageId }}", listOf("m3"), ok.failures.map { it.messageId })
        assertTrue("usedBatch on 200 batch path", ok.usedBatch)

        val req = server.takeRequest()
        // T3-C8: path assertion MUST NOT have "GET " prefix.
        // Retrofit URL-encodes the comma separator inside `?ids=m1,m2,m3`
        // → `m1%2Cm2%2Cm3`. Pin the actual wire form (encoded commas) so
        // the test asserts what the live sidecar will see.
        assertEquals(
            "wire path with deduped ids (no GET prefix): ${req.path}",
            "/slimapi/messages/sess-1/full?ids=m1%2Cm2%2Cm3&mode=full",
            req.path,
        )
    }

    @Test
    fun `expand 404 session_not_found yields SessionMissing`() = runBlocking {
        // T3-C2: 404 + session_not_found → SessionMissing (UI clears local cache;
        // distinct from generic Failed so the UI does not show a banner on a
        // session that's simply gone upstream).
        server.enqueue(jsonResponse("""{"code":"session_not_found"}""", 404))

        val outcome = repository.expandMessagesFullBatch("sess-missing", listOf("m1"))

        assertTrue("SessionMissing: $outcome", outcome is ExpandOutcome.SessionMissing)
        assertEquals("sess-missing", (outcome as ExpandOutcome.SessionMissing).sessionId)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `expand 404 thin_route_not_found falls back to N parallel single fulls with usedBatch=false`() = runBlocking {
        // T3-C3: transitional fallback — batch endpoint not deployed →
        // 404 thin_route_not_found → repo internally fans out to N
        // parallel single-full calls (Semaphore(4) bounded). The UI
        // sees Ok(usedBatch=false) and cannot tell the difference from
        // a batched-success path (T16 relies on this).
        server.enqueue(jsonResponse("""{"code":"thin_route_not_found"}""", 404))
        val m1 = MessageWithParts(info = Message(id = "m1", role = "user"))
        val m2 = MessageWithParts(info = Message(id = "m2", role = "assistant"))
        server.enqueue(jsonResponse(json.encodeToString(m1)))
        server.enqueue(jsonResponse(json.encodeToString(m2)))

        val outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1", "m2"))

        assertTrue("Ok on fallback: $outcome", outcome is ExpandOutcome.Ok)
        val ok = outcome as ExpandOutcome.Ok
        assertEquals(
            "both items resolved via fallback: ${ok.items.map { it.info.id }}",
            setOf("m1", "m2"),
            ok.items.map { it.info.id }.toSet(),
        )
        assertTrue("no failures on clean fallback: ${ok.failures.map { it.messageId }}", ok.failures.map { it.messageId }.isEmpty())
        assertFalse("usedBatch=false on fallback path", ok.usedBatch)

        // Wire: 1 batch request + 2 single-full requests.
        assertEquals("3 total HTTP requests (1 batch + 2 single)", 3, server.requestCount)
        val batchReq = server.takeRequest()
        assertTrue(
            "batch path with ids query: ${batchReq.path}",
            batchReq.path!!.startsWith("/slimapi/messages/sess-1/full?ids="),
        )
        val singlePaths = mutableSetOf<String>()
        repeat(2) {
            val singleReq = server.takeRequest()
            // Each single-full path: /slimapi/messages/sess-1/full/{mid} (no ?ids query).
            assertTrue(
                "single-full path (no ids query): ${singleReq.path}",
                singleReq.path!!.matches(Regex("/slimapi/messages/sess-1/full/(m1|m2)")),
            )
            singlePaths += singleReq.path!!
        }
        assertEquals(
            "two distinct single-full requests (Semaphore allows parallel): $singlePaths",
            setOf("/slimapi/messages/sess-1/full/m1", "/slimapi/messages/sess-1/full/m2"),
            singlePaths,
        )
    }

    @Test
    fun `single-full fallback retries 503 up to budget then per-id failure`() = runBlocking {
        // fetchSingleFullWithRetry: batch probe 404 thin_route → fall back to single
        // /full/{mid}; the single path retries 503 up to the budget (EXPAND_MAX_503_RETRIES
        // = 3 retries / 4 total attempts) before surfacing the message as a PER-ID
        // failure (NOT a whole-call Failed — siblings would still render). Pins the
        // single path's budget alignment with the batch drivePartition loop.
        server.enqueue(jsonResponse("""{"code":"thin_route_not_found"}""", 404))
        repeat(4) { server.enqueue(jsonResponse("""{"code":"transform_busy"}""", 503).setHeader("Retry-After", "1")) }

        val outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1"))

        assertTrue("Ok (per-id failure, not whole-call Failed): $outcome", outcome is ExpandOutcome.Ok)
        val ok = outcome as ExpandOutcome.Ok
        assertTrue("m1 surfaced as per-id failure: ${ok.failures.map { it.messageId }}",
            ok.failures.any { it.messageId == "m1" })
        assertFalse("usedBatch=false on fallback path", ok.usedBatch)
        // 1 batch probe (404) + 4 single-full attempts (1 initial + 3 retries) = 5.
        assertEquals("1 batch probe + 4 single attempts (1 initial + 3 retries)", 5, server.requestCount)
    }

    @Test
    fun `single-full fallback recovers when a 503 retry succeeds`() = runBlocking {
        // fetchSingleFullWithRetry (recovery sibling): first single /full/{mid} 503,
        // retry succeeds → Ok with the message loaded. Pins that the single retry
        // path produces Ok (not just per-id failure) and honors Retry-After.
        server.enqueue(jsonResponse("""{"code":"thin_route_not_found"}""", 404))
        server.enqueue(jsonResponse("""{"code":"transform_busy"}""", 503).setHeader("Retry-After", "1"))
        val m1 = MessageWithParts(info = Message(id = "m1", role = "user"))
        server.enqueue(jsonResponse(json.encodeToString(m1)))

        val outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1"))

        assertTrue("Ok after one 503 retry: $outcome", outcome is ExpandOutcome.Ok)
        val ok = outcome as ExpandOutcome.Ok
        assertTrue("m1 loaded: ${ok.items.map { it.info.id }}", ok.items.any { it.info.id == "m1" })
        assertFalse("usedBatch=false on fallback path", ok.usedBatch)
        // 1 batch probe (404) + 2 single attempts (1 initial 503 + 1 retry success) = 3.
        assertEquals("1 batch probe + 2 single attempts (503 then success)", 3, server.requestCount)
    }

    @Test
    fun `expand other 404 yields Failed and does NOT fall back to single fulls`() = runBlocking {
        // T3-C3 (sibling): 404 with any non-session / non-thin-route code
        // is a programming error or unmapped path — MUST NOT trigger the
        // single-full fallback (would mask the routing bug). Surfaces as
        // Failed(code) so the UI shows a retry affordance.
        server.enqueue(jsonResponse("""{"code":"some_other_code"}""", 404))

        val outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1", "m2"))

        assertTrue("Failed on other 404: $outcome", outcome is ExpandOutcome.Failed)
        val failed = outcome as ExpandOutcome.Failed
        assertEquals("sess-1", failed.sessionId)
        assertEquals("some_other_code", failed.code)
        assertEquals("NO fallback requests on other 404", 1, server.requestCount)
    }

    @Test
    fun `expand 413 halves ids down to single id each becomes terminal`() = runBlocking {
        // T3-C4: 413 → repo-internal halve retry. With 4 ids, all become
        // terminal failures after exhaustive halving.
        // Need to enqueue enough 413 responses: 1 (4 ids) + 2 (2 ids) + 4 (1 ids) = 7.
        repeat(7) { server.enqueue(jsonResponse("""{"code":"response_too_large"}""", 413)) }

        val outcome = repository.expandMessagesFullBatch(
            "sess-1", listOf("m1", "m2", "m3", "m4"),
        )

        assertTrue("Ok after halving: $outcome", outcome is ExpandOutcome.Ok)
    }

    @Test
    fun `expand 413 at single id yields mid-terminal Ok with failure`() = runBlocking {
        // Per §5 'singleton 413→mid 终态': single-id 413 results in Ok with that mid in failures.
        server.enqueue(jsonResponse("""{"code":"response_too_large"}""", 413))

        val outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1"))

        assertTrue("Ok on single-id 413: $outcome", outcome is ExpandOutcome.Ok)
        val ok = outcome as ExpandOutcome.Ok
        assertEquals("single id in failures", 1, ok.failures.size)
        assertEquals("m1", ok.failures[0].messageId)
        assertEquals("response_too_large", ok.failures[0].code)
        assertEquals("no retries at single-id 413", 1, server.requestCount)
    }

    // ── b1-foldin T3 amendment: 413 code split (message_too_large vs other) ──
    //
    // Per oc-slimapi v1-contract-implementation-status.md §2 B1 #4 + §7
    // code table: 413 now splits into `response_too_large` (batch aggregate
    // cap; halving helps) and `message_too_large` (single-message HARD cap
    // on /full/{mid} default mode, 32 MiB; halving is futile because the
    // offending message is too large at any batch size). The 413 branch
    // MUST discriminate by parsed code:
    //   - `message_too_large` → Ok(items=[], failedIds=ids, usedBatch=true)
    //     (mirrors the 200-envelope per-message errors[] semantics; T16
    //     does NOT branch on outcome type here) + NO halving (exactly 1
    //     HTTP request). Fail-fast: the batch cannot be satisfied.
    //   - `response_too_large` / null / unknown → defensive halve (existing
    //     behavior; batch-size cap, halving helps). Covered by T3-C4 above
    //     + the null-code sibling below.

    @Test
    fun `expand 413 message_too_large routes all batch ids to failedIds without halving`() = runBlocking {
        // b1-foldin T3 amendment (a): the single-message HARD cap means
        // halving is futile — the offending message would re-fail at any
        // batch size. The repo MUST fail fast: 1 HTTP request, ids routed
        // to failedIds as Ok (mirrors 200-envelope semantics), usedBatch=true.
        //
        // Discriminating power: under the OLD always-halve impl, 4 ids +
        // message_too_large would have triggered the recursive halve loop
        // (4→2→1) — 3 HTTP requests, terminating in Failed(message_too_large).
        // The assertions on `requestCount == 1` and `outcome is Ok` both
        // trip under the old impl; this test genuinely exercises the new
        // fail-fast branch.
        server.enqueue(jsonResponse("""{"code":"message_too_large"}""", 413))

        val outcome = repository.expandMessagesFullBatch(
            "sess-1", listOf("m1", "m2", "m3", "m4"),
        )

        assertTrue(
            "Ok on message_too_large (NOT Failed — mirrors 200-envelope errors[] semantics): $outcome",
            outcome is ExpandOutcome.Ok,
        )
        val ok = outcome as ExpandOutcome.Ok
        assertTrue("no items resolved on batch-level message_too_large: ${ok.items}", ok.items.isEmpty())
        assertEquals(
            "all batch ids routed to failedIds (fail-fast): ${ok.failures.map { it.messageId }}",
            listOf("m1", "m2", "m3", "m4"),
            ok.failures.map { it.messageId },
        )
        assertTrue("usedBatch=true even on message_too_large fail-fast", ok.usedBatch)
        assertEquals(
            "exactly 1 HTTP request — NO halving/retry on message_too_large",
            1,
            server.requestCount,
        )
    }

    @Test
    fun `expand 413 message_too_large at single id still routes to failedIds without retry`() = runBlocking {
        // b1-foldin T3 amendment (a) sibling: even at single-id batch size,
        // message_too_large MUST route the id to failedIds (NOT collapse to
        // Failed — the outcome type distinction matters because T16 treats
        // per-message expand-failed differently from whole-call expand-failed).
        // The old always-halve impl would have produced Failed(message_too_large)
        // here (size <= 1 branch); the new code produces Ok(failedIds=[m1]).
        server.enqueue(jsonResponse("""{"code":"message_too_large"}""", 413))

        val outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1"))

        assertTrue("Ok on single-id message_too_large: $outcome", outcome is ExpandOutcome.Ok)
        val ok = outcome as ExpandOutcome.Ok
        assertEquals("single id routed to failedIds: ${ok.failures.map { it.messageId }}", listOf("m1"), ok.failures.map { it.messageId })
        assertEquals("no retry on single-id message_too_large", 1, server.requestCount)
    }

    @Test
    fun `expand 413 with null or unknown code halves as defensive default`() = runBlocking {
        // b1-foldin T3 amendment (c): when the sidecar's 413 body has no
        // `code` field OR an unrecognized code, the branch MUST fall back
        // to the original halve-and-retry behavior (defensive default —
        // keeps the existing contract for response_too_large and any
        // future 413 code we have not yet anchored). Two enqueues = 2 ids
        // → halve to 1 → success on the halved call.
        // (Subsumes the explicit `response_too_large` code-in-body case
        // already pinned by T3-C4 above.)
        // For 2 ids, need: 1 (2 ids) + 2 (1 id) = 3 413 responses to make both terminal.
        repeat(3) { server.enqueue(jsonResponse("""{"detail":"too large"}""", 413)) }

        val outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1", "m2"))

        assertTrue(
            "Ok after defensive halving on null-code 413: $outcome",
            outcome is ExpandOutcome.Ok,
        )
        val ok = outcome as ExpandOutcome.Ok
        // Both singles become terminal failures
        assertEquals("no items resolved", 0, ok.items.size)
        assertEquals("both ids in failures", 2, ok.failures.size)
    }

    @Test
    fun `expand 413 response_too_large halves as before`() = runBlocking {
        // b1-foldin T3 amendment (b): explicit code-in-body pin that the
        // RESPONSE_TOO_LARGE branch still halves (existing behavior). The
        // old T3-C4 test enqueues this code in the body but does not
        // explicitly assert the parsed-code path is exercised — this test
        // pins the post-split behavior (response_too_large → halve;
        // message_too_large → fail-fast) so the discrimination cannot
        // silently invert.
        // For 2 ids, need: 1 (2 ids) + 2 (1 id) = 3 413 responses.
        repeat(3) { server.enqueue(jsonResponse("""{"code":"response_too_large"}""", 413)) }

        val outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1", "m2"))

        assertTrue(
            "Ok after halving on response_too_large: $outcome",
            outcome is ExpandOutcome.Ok,
        )
        val ok = outcome as ExpandOutcome.Ok
        // Both singles become terminal failures
        assertEquals("no items resolved", 0, ok.items.size)
        assertEquals("both ids in failures", 2, ok.failures.size)
    }

    @Test
    fun `SlimapiErrorCodes B1-additive constants pin server snake_case values`() {
        // b1-foldin T1 amendment: focused constant-pin for the 4 B1-additive
        // codes. Cheap regression guard against drift in either direction
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
        assertEquals(
            "invalid_route_token",
            cn.vectory.ocdroid.data.repository.http.SlimapiErrorCodes.INVALID_ROUTE_TOKEN,
        )
    }

    @Test
    fun `expand 503 retries with bounded exponential backoff then fails on exhaustion`() = runBlocking {
        // T3-C5: 503 → repo-internal bounded exponential backoff
        // (max 3 retries, base 200 ms, jitter ±30%). All 4 attempts fail
        // → Failed(code). Pinned with runTest so the delays virtualise
        // to ~0 ms (the test does NOT actually sleep 1.4 s).
        repeat(4) { server.enqueue(jsonResponse("""{"code":"upstream_unavailable"}""", 503)) }

        val outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1"))

        assertTrue("Failed after backoff exhausted: $outcome", outcome is ExpandOutcome.Failed)
        val failed = outcome as ExpandOutcome.Failed
        assertEquals("sess-1", failed.sessionId)
        assertNull("code is null on exhausted: ${failed.code}", failed.code)
        assertTrue("exhausted = true on exhausted", failed.exhausted)
        assertEquals("4 total attempts (1 initial + 3 retries) on exhaustion", 4, server.requestCount)
    }

    @Test
    fun `expand 503 succeeds on retry before exhaustion`() = runBlocking {
        // T3-C5 (sibling): backoff recovers within the budget — first
        // attempt 503, retry succeeds → Ok. Pins that the backoff path
        // can produce Ok (not just Failed).
        server.enqueue(jsonResponse("""{"code":"upstream_unavailable"}""", 503))
        val okBody = """{"items":[{"info":{"id":"m1","role":"user"},"parts":[]}],"errors":[]}"""
        server.enqueue(jsonResponse(okBody))

        val outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1"))

        assertTrue("Ok after one 503 retry: $outcome", outcome is ExpandOutcome.Ok)
        val ok = outcome as ExpandOutcome.Ok
        assertEquals(listOf("m1"), ok.items.map { it.info.id })
        assertEquals("2 total attempts (initial + 1 retry)", 2, server.requestCount)
    }

    @Test
    fun `expand 400 yields Failed without retry`() = runBlocking {
        // T3-C6 (400 branch): bad request — fix and retry upstream; the
        // repo does NOT auto-retry 4xx other than 413 (which is the
        // explicitly-retryable size-too-large case).
        server.enqueue(jsonResponse("""{"code":"invalid_ids"}""", 400))

        val outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1"))

        assertTrue("Failed on 400: $outcome", outcome is ExpandOutcome.Failed)
        assertEquals("invalid_ids", (outcome as ExpandOutcome.Failed).code)
        assertEquals("no retries on 400", 1, server.requestCount)
    }

    @Test
    fun `expand 422 yields Failed without retry`() = runBlocking {
        // T3-C6 (422 branch): programming error (ids missing /
        // malformed). Surfaced as Failed(code) — UI surfaces a generic
        // "expand failed" affordance; this is NOT a transport-retry
        // situation.
        server.enqueue(jsonResponse("""{"code":"unprocessable"}""", 422))

        val outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1"))

        assertTrue("Failed on 422: $outcome", outcome is ExpandOutcome.Failed)
        assertEquals("unprocessable", (outcome as ExpandOutcome.Failed).code)
        assertEquals("no retries on 422", 1, server.requestCount)
    }

    @Test
    fun `expand empty ids yields Failed with null code`() = runBlocking {
        // T3-C7 (empty branch): normalisation collapses an empty input
        // to Failed(sessionId, null) WITHOUT making any HTTP call (the
        // strategy is purely client-side — there is nothing to ask the
        // server). No server enqueue: any request would fail the test.
        val outcome = repository.expandMessagesFullBatch("sess-1", emptyList())

        assertTrue("Failed on empty ids: $outcome", outcome is ExpandOutcome.Failed)
        val failed = outcome as ExpandOutcome.Failed
        assertEquals("sess-1", failed.sessionId)
        assertNull("null code on empty-ids normalisation", failed.code)
        assertEquals("no HTTP call on empty ids", 0, server.requestCount)
    }

    @Test
    fun `expand more than 20 ids truncates to first 20 without throwing`() = runBlocking {
        // T3-C7 (>20 branch): the v1 contract caps `?ids` at 20. The
        // repo MUST truncate (taking the first 20 in deduped order) +
        // log a warning, NOT throw — the v1 bug was a stray `require(
        // size <= 20)` inside runSuspendCatching that the getOrElse
        // collapsed into an opaque Failed. Explicit cap with a warning
        // is the right shape; the UI just sees a normal Ok for the 20
        // it asked for (and can re-request the rest in a second call).
        val ids = (1..25).map { "m$it" }  // 25 ids → truncate to first 20
        val okBody = """{"items":[],"errors":[]}"""  // server returns empty envelope
        server.enqueue(jsonResponse(okBody))

        val outcome = repository.expandMessagesFullBatch("sess-1", ids)

        assertTrue("Ok on truncation (no throw): $outcome", outcome is ExpandOutcome.Ok)
        val req = server.takeRequest()
        // Wire: exactly 20 ids sent (m1..m20), m21..m25 dropped. Retrofit
        // URL-encodes commas inside `?ids=…` → use the decoded helper.
        val sentIds = req.path!!.decodedIdsQuery()
        assertEquals("truncated to 20 ids: $sentIds", 20, sentIds.size)
        assertEquals("first id preserved", "m1", sentIds.first())
        assertEquals("last id is m20 (m21+ dropped)", "m20", sentIds.last())
    }

    @Test
    fun `expand network failure yields Failed with null code`() = runBlocking {
        // T3-C9 (network exception): transport failure (server
        // unreachable / connection reset) collapses to
        // Failed(sessionId, null) — distinguishable from a 503 (which
        // carries the sidecar's upstream_unavailable code) so the UI
        // can show "network" vs "server busy" affordances if it wants.
        // Simulated by pointing the repo at a dead port (server shut down).
        server.shutdown()
        val deadRepo = OpenCodeRepository(mockk(relaxed = true), mockk(relaxed = true))
        deadRepo.configure(
            baseUrl = "http://127.0.0.1:${server.url("").port}",
            slim = true,
        )

        val outcome = deadRepo.expandMessagesFullBatch("sess-1", listOf("m1"))

        assertTrue("Failed on network: $outcome", outcome is ExpandOutcome.Failed)
        val failed = outcome as ExpandOutcome.Failed
        assertEquals("sess-1", failed.sessionId)
        assertNull("null code on transport failure", failed.code)
    }

    @Test
    fun `expand cancel during thin_route_not_found fallback propagates CancellationException`() = runBlocking {
        // §CE-discipline fix test (rev-grok finding): cancellation during
        // the fallback fan-out MUST propagate (not be swallowed into Ok
        // with bogus failedIds).
        //
        // Before the fix, `fallbackSingleFull` wrapped the api call in
        // plain `runCatching { ... }` — which catches ALL Throwable
        // (including kotlinx.coroutines.CancellationException). When the
        // parent scope was cancelled while a single-full async{} was
        // suspended on the HTTP response, the suspended call resumed with
        // CE, runCatching collapsed it into Result.failure(CE), the
        // async{} returned `(id to failure(CE))` "normally", coroutineScope
        // saw both asyncs complete, and expandMessagesFullBatch returned
        // `Ok(items=[], failedIds=[m1,m2])` — the cancel was silently
        // dropped (UI dispose / scope cancel broken on the thin_route
        // fallback path).
        //
        // After the fix, the wrapper is `runSuspendCatching` (the
        // project's cancellation-safe helper — rethrows CE, captures the
        // rest as Result.failure). The async{} throws CE, coroutineScope
        // propagates, expandMessagesFullBatch throws CE — matching the
        // batch path's [expandBatchInternal] explicit `catch (CE) { throw e }`
        // discipline.
        //
        // Test shape: a custom MockWebServer [Dispatcher] BLOCKS the
        // single-full responses on a [CountDownLatch] so the fallback's
        // async coroutines are deterministically suspended when we cancel
        // (setBodyDelay was tried first but did not actually delay the
        // response — OkHttp 4.12.0 body-delay proved unreliable in this
        // configuration; the dispatcher-based block is unambiguous). The
        // test polls an AtomicInteger counter until BOTH single-full
        // requests are in-flight, then cancels — the cancel reaches the
        // suspended api coroutines, which MUST observe CE.

        val singleFullsInFlight = java.util.concurrent.atomic.AtomicInteger(0)
        val releaseSingleFulls = java.util.concurrent.CountDownLatch(1)
        val dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    // Batch request → 404 thin_route_not_found → triggers fallback.
                    path.startsWith("/slimapi/messages/sess-1/full?") ->
                        jsonResponse("""{"code":"thin_route_not_found"}""", 404)
                    // Single-full request → count, then BLOCK on the latch
                    // until the test releases (or 10 s safety timeout).
                    path.matches(Regex("/slimapi/messages/sess-1/full/(m1|m2)")) -> {
                        singleFullsInFlight.incrementAndGet()
                        releaseSingleFulls.await(10, TimeUnit.SECONDS)
                        val mid = path.substringAfterLast("/")
                        jsonResponse(
                            json.encodeToString(
                                MessageWithParts(info = Message(id = mid, role = "user"))
                            )
                        )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.dispatcher = dispatcher

        var returnedOk: ExpandOutcome.Ok? = null
        var capturedCE: CancellationException? = null
        val job = launch {
            try {
                val outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1", "m2"))
                if (outcome is ExpandOutcome.Ok) returnedOk = outcome
            } catch (e: CancellationException) {
                capturedCE = e
                throw e
            }
        }

        try {
            // Deterministic in-flight wait: poll until BOTH single-full
            // requests have arrived and are blocked on the latch. Each
            // dispatcher thread is parked inside `releaseSingleFulls.await()`,
            // so the OkHttp Call on the client side is suspended waiting
            // for a response that will never come (until released).
            while (singleFullsInFlight.get() < 2) {
                delay(50)
            }
            // Cancel mid-flight — the 2 suspended api coroutines must
            // observe the cancel; the async{} must throw CE (after fix)
            // or return Result.failure(CE) (before fix — the bug);
            // coroutineScope + expandMessagesFullBatch must propagate CE
            // (after fix) or return Ok with bogus failedIds (before fix).
            job.cancel()
            job.join()

            assertNull(
                "expand MUST NOT return Ok when scope cancelled mid-fallback " +
                    "(CE was swallowed into bogus failedIds): $returnedOk",
                returnedOk,
            )
            assertNotNull(
                "CE MUST be thrown from expandMessagesFullBatch on scope cancel " +
                    "(structured-concurrency propagation): $capturedCE",
                capturedCE,
            )
        } finally {
            // Release the dispatcher's blocked threads so server.shutdown()
            // in @After can clean up cleanly (otherwise the dispatcher's
            // two threads stay parked on the latch until the 10 s safety
            // timeout, slowing the test suite).
            releaseSingleFulls.countDown()
        }
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
    fun `getMessagesPaged slim mode with before cursor routes through single-page cursor endpoint`() = runBlocking {
        // §11.1 fix-10 P0-1: getMessagesPaged slim mode with `before != null`
        // routes through getSlimapiMessagesPage (single-page cursor fetch),
        // NOT the drain. The HTTP path is /slimapi/messages/{sid} with
        // before cursor forwarded. A terminal-page response surfaces items
        // via Result.success(MessagesPage).
        val repo = makeRepository(slim = true)
        val seedToken = repo.captureSlimCommitToken()
        assertTrue(
            "seed bump must succeed",
            repo.bumpSlimBookmarkFromItems(
                "sess-1",
                listOf(MessageWithParts(info = Message(id = "m-seed", role = "assistant", time = Message.TimeInfo(updated = 500L)))),
                seedToken,
            ),
        )
        val s1 = MessageWithParts(info = Message(id = "m1", role = "assistant", time = Message.TimeInfo(updated = 1000L)))
        val s2 = MessageWithParts(info = Message(id = "m2", role = "assistant", time = Message.TimeInfo(updated = 2000L)))
        val s3 = MessageWithParts(info = Message(id = "m3", role = "assistant", time = Message.TimeInfo(updated = 3000L)))
        // Single page response (no drain — single-page cursor fetch).
        server.enqueue(jsonResponse(json.encodeToString(listOf(s1, s2, s3))))

        val result = repo.getMessagesPaged("sess-1", limit = 30, before = "cur")

        assertTrue(
            "P0-1: slim getMessagesPaged with before MUST succeed (got ${result.exceptionOrNull()})",
            result.isSuccess,
        )
        val page = result.getOrThrow()
        assertEquals(
            listOf("m1", "m2", "m3"),
            page.items.map { it.info.id },
        )

        val request = server.takeRequest()
        // P0-1: HTTP path is the cursor endpoint with before forwarded.
        assertTrue(
            "P0-1: slim path is cursor endpoint (NOT /since/{ts}): ${request.path}",
            request.path!!.startsWith("/slimapi/messages/sess-1") &&
                !request.path!!.contains("/since/"),
        )
        assertTrue(
            "P0-1: before cursor forwarded: ${request.path}",
            request.path!!.contains("before=cur"),
        )
    }

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
    fun `getMessagesPaged slim mode advances localApplied via commit on terminal page`() = runBlocking {
        // §11.1 fix-9 P0-6: a terminal-page drain drives commitAuthoritative,
        // which advances localApplied* to the tuple-max of the drained
        // items. The watermark advance happens inside the committer's
        // token-guarded critical section (P1-3 — no per-page bump).
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

        val result = repo.getMessagesPaged("sess-1")

        // §11.1 fix-9 P0-6: drain Success + commit Committed — the result
        // MUST be a success (the drain surfaces items to the UI).
        assertTrue(
            "P0-6: getMessagesPaged slim MUST succeed via drain (got ${result.exceptionOrNull()})",
            result.isSuccess,
        )
        val page = result.getOrThrow()
        assertEquals(
            "P0-6: drained items surfaced",
            listOf("m1", "m2"),
            page.items.map { it.info.id },
        )

        // §11.1 fix-9 P0-6: drain Success + commit Committed advances
        // localApplied* to the tuple-max of the drained items (m2/900L).
        val state = repo.snapshotSlimSseState()["sess-1"]
        assertEquals(
            "P0-6: localAppliedUpdatedAt advanced via commit to drain max (got ${state?.localAppliedUpdatedAt})",
            900L,
            state?.localAppliedUpdatedAt,
        )
        assertEquals(
            "P0-6: localAppliedMessageId advanced via commit (got ${state?.localAppliedMessageId})",
            "m2",
            state?.localAppliedMessageId,
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
        // §session-scope-narrow: roots=true + limit=SLIM_COLDSTART_SESSION_LIMIT
        // MUST also reach the call. Parse the recorded URL with
        // okhttp3.HttpUrl for exact query-param assertions — the v1 contract
        // pins `?directory=a&directory=b` (repeated params, NOT comma-joined)
        // so queryParameterValues("directory") must return BOTH entries.
        server.enqueue(jsonResponse("[]"))  // sessions
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))  // questions
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))  // permissions

        repository.coldStartSlimSync(directories = listOf("/alpha", "/beta"), token = token())

        val sessionsRequest = server.takeRequest()
        val sessionsUrl = sessionsRequest.requestUrl!!
        assertEquals(
            "directories forwarded as repeated query: ${sessionsRequest.path}",
            listOf("/alpha", "/beta"),
            sessionsUrl.queryParameterValues("directory"),
        )
        assertEquals(
            "roots=true forwarded alongside directories: ${sessionsRequest.path}",
            "true",
            sessionsUrl.queryParameter("roots"),
        )
        assertEquals(
            "limit=SLIM_COLDSTART_SESSION_LIMIT forwarded: ${sessionsRequest.path}",
            SlimSyncEngine.SLIM_COLDSTART_SESSION_LIMIT.toString(),
            sessionsUrl.queryParameter("limit"),
        )
    }

    // ── Task 5 (L1 G5): getSlimapiMessagesPage — cursor pagination ──────────
    //
    // §slimapi-client-impl-v1 §3 (resync) + §9 acceptance — the slim
    // cursor surface flips from "single bounded page; v2 may add cursor
    // transparency" to "X-Next-Cursor IS surfaced in v1; coldStart no-
    // bookmark branch cursor-follows". This block pins:
    //   - T5-C1: getMessagesPaged slim branch reads X-Next-Cursor header
    //     (header present → nextCursor=value; absent → null).
    //   - T5-C2: coldStartSlimSync with bookmark → /since single page
    //     (unchanged); without bookmark → getSlimapiMessagesPage(
    //     mode=skeleton, limit=200) cursor-follow until nextCursor==null.
    //   - T5-C3: SLIMAPI_DEFAULT_PAGE_LIMIT==200; SLIMAPI_LOCAL_HISTORY_BOUND
    //     sourced from RevertCutoffCoordinator.MAX_PAGES * PAGE_SIZE.
    //   - T5-C4: multi-page = exactly N requests, items aggregated, dedup
    //     by message id across pages.
    //   - T5-C5: bound reached → stop following (do NOT exceed
    //     SLIMAPI_LOCAL_HISTORY_BOUND items even if cursor keeps going).

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
    fun `coldStartSlimSync no bookmark cursor-follows skeleton pages until nextCursor null`() = runBlocking {
        // T5-C2/C4: cold-start with no prior bookmark for the open session
        // MUST cursor-follow `GET /slimapi/messages/{sid}?mode=skeleton&limit=200`
        // pages until the sidecar returns no `X-Next-Cursor`. Exactly 2
        // pages enqueued → exactly 2 message requests; items aggregated.
        val m1 = MessageWithParts(info = Message(id = "m1", role = "user"))
        val m2 = MessageWithParts(info = Message(id = "m2", role = "assistant"))
        val m3 = MessageWithParts(info = Message(id = "m3", role = "user"))

        server.enqueue(jsonResponse("[]"))  // sessions
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))  // questions
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))  // permissions
        // Page 1: 2 items + X-Next-Cursor → follow.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(json.encodeToString(listOf(m1, m2)))
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Next-Cursor", "page2-cursor")
        )
        // Page 2: 1 item, no X-Next-Cursor → stop.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(json.encodeToString(listOf(m3)))
                .setHeader("Content-Type", "application/json")
        )

        val result = repository.coldStartSlimSync(openSessionId = "sess-1", token = token())

        assertTrue(result.isSuccess)
        val snapshot = result.getOrThrow()
        assertNotNull("openSessionId → messages fetched", snapshot.messages)
        assertEquals(
            "items aggregated across pages: ${snapshot.messages!!.map { it.info.id }}",
            listOf("m1", "m2", "m3"),
            snapshot.messages!!.map { it.info.id },
        )

        // 5 total requests: sessions + q + p + 2 message pages.
        assertEquals("exactly 5 HTTP requests (3 envelope + 2 message pages)", 5, server.requestCount)

        // Identify the 2 message requests: must be /slimapi/messages/sess-1
        // (NOT /since/...), must carry mode=skeleton + limit=200, and the
        // second must carry before=page2-cursor.
        val messageReqs = (1..5).map { server.takeRequest() }.filter {
            it.path!!.startsWith("/slimapi/messages/sess-1")
        }
        assertEquals("exactly 2 message requests", 2, messageReqs.size)
        val page1 = messageReqs[0].path!!
        val page2 = messageReqs[1].path!!
        assertTrue("page 1 hits bare messages path (no /since/): $page1",
            !page1.contains("/since/"))
        assertTrue("page 1 carries mode=skeleton: $page1", page1.contains("mode=skeleton"))
        assertTrue("page 1 carries limit=200: $page1", page1.contains("limit=200"))
        assertFalse("page 1 has no before cursor: $page1", page1.contains("before="))
        assertTrue("page 2 carries before=page2-cursor: $page2", page2.contains("before=page2-cursor"))
        assertTrue("page 2 carries mode=skeleton: $page2", page2.contains("mode=skeleton"))
    }

    @Test
    fun `coldStartSlimSync no bookmark dedups message ids across cursor-followed pages`() = runBlocking {
        // T5-C4 (dedup): the slimapi `/messages` endpoint includes the
        // boundary message (per §3 "X-Next-Cursor carries the OLDEST id of
        // the current page; the next page starts at-or-before that id") so
        // adjacent pages can overlap by one messageID. The cursor-follow
        // MUST dedup by info.id (preserve first-seen order).
        val m1 = MessageWithParts(info = Message(id = "m1", role = "user"))
        val m2 = MessageWithParts(info = Message(id = "m2", role = "assistant"))
        val m3 = MessageWithParts(info = Message(id = "m3", role = "user"))

        server.enqueue(jsonResponse("[]"))  // sessions
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))  // questions
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))  // permissions
        // Page 1: m1, m2; cursor → m2 (boundary inclusive).
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(json.encodeToString(listOf(m1, m2)))
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Next-Cursor", "boundary-m2")
        )
        // Page 2: m2 (boundary-include overlap), m3; no cursor → stop.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(json.encodeToString(listOf(m2, m3)))
                .setHeader("Content-Type", "application/json")
        )

        val result = repository.coldStartSlimSync(openSessionId = "sess-1", token = token())

        assertTrue(result.isSuccess)
        val messages = result.getOrThrow().messages!!
        assertEquals(
            "deduped by id, first-seen order preserved: ${messages.map { it.info.id }}",
            listOf("m1", "m2", "m3"),
            messages.map { it.info.id },
        )
    }

    @Test
    fun `coldStartSlimSync no bookmark itemBound with non-null cursor degrades to null (not partial items)`() = runBlocking {
        // §11.5: itemBound is a SAFETY limit, NOT completeness proof.
        // Hitting it while `X-Next-Cursor` is non-null → Partial → the
        // List façade ([drainSlimapiMessagesBounded]) throws
        // [OpenCodeRepository.SlimCursorPartialException] → coldStart's
        // no-bookmark branch degrades to `messages = null` ("keep prior").
        // Partial items are NOT exposed to the cold-start merge; the
        // watermark is NOT advanced (no-bump-on-partial).
        //
        // This SUPERSEDES the pre-§11.5 behavior where hitting itemBound
        // returned Success with the capped aggregate (bumping the
        // watermark + clearing dirty on an incomplete window).
        //
        // Test shape: with SLIMAPI_DEFAULT_PAGE_LIMIT=200 + bound=250,
        // page 1 (200 items + cursor) + page 2 (101 items, m200 overlap +
        // cursor) → aggregated dedup = 300 ≥ 250 with non-null cursor →
        // Partial → coldStart messages=null.
        val page1 = (1..200).map { MessageWithParts(info = Message(id = "m$it", role = "user")) }
        val page2 = (200..300).map { MessageWithParts(info = Message(id = "m$it", role = "user")) }

        server.enqueue(jsonResponse("[]"))  // sessions
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))  // questions
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))  // permissions
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(json.encodeToString(page1))
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Next-Cursor", "more")
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(json.encodeToString(page2))
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Next-Cursor", "even-more")
        )

        val result = repository.coldStartSlimSync(openSessionId = "sess-1", token = token())

        assertTrue("coldStart itself succeeds (partial → null degradation)", result.isSuccess)
        val snapshot = result.getOrThrow()
        assertNull(
            "itemBound + non-null cursor → Partial → coldStart MUST degrade to null messages " +
                "(NOT expose partial items); messages=${snapshot.messages?.size}",
            snapshot.messages,
        )
        // Watermark NOT advanced (no-bump-on-partial).
        val state = repository.getSlimSessionState("sess-1")
        assertNull(
            "Partial MUST NOT advance localAppliedUpdatedAt; state=$state",
            state?.localAppliedUpdatedAt,
        )
        // Exactly 2 message pages (item bound stops the follow before page 3).
        val requests = (1..5).map { server.takeRequest() }
        assertEquals(
            "exactly 2 message pages: ${requests.map { it.path }}",
            2,
            requests.count { it.path!!.startsWith("/slimapi/messages/sess-1") },
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
    fun `coldStartSlimSync cancel during no-bookmark cursor-follow propagates CancellationException`() = runBlocking {
        // §CE-discipline regression (rev-gpt IMPORTANT): the two new T5
        // cold-start branches (bookmark-present single-page + no-bookmark
        // cursor-follow) MUST wrap their suspend work in `runSuspendCatching`,
        // NOT plain `runCatching`. Plain `runCatching` catches
        // CancellationException (per RunSuspendCatching.kt:8-14); a scope
        // cancel mid-drain would be swallowed into `Result.failure(CE)` →
        // `.getOrNull() == null` → bogus SlimColdStartSnapshot(messages=null)
        // returned "successfully" AFTER the drain had already bumped the
        // bookmark on the first page — inconsistent state (watermark says
        // "caught up to X", messages list says "nothing pulled").
        //
        // After the fix, both wrappers are `runSuspendCatching` (rethrows
        // CE), so cancel propagates out of coldStartSlimSync as CE —
        // matching the project-wide R-14 discipline that T3 and T4 each
        // took findings for. This test pins the no-bookmark path (the
        // cursor-follow); the bookmark-present path uses the same wrapper
        // shape, so the regression coverage is symmetric.
        //
        // Discriminating power: under the OLD plain-`runCatching` impl,
        // `coldStartSlimSync(...)` would return `Result.success(snapshot)`
        // with `snapshot.messages == null` after cancel — `capturedCE`
        // would stay null and `returnedSnapshot` would be non-null, failing
        // BOTH assertions below. Under the `runSuspendCatching` fix, the
        // suspended drain coroutine observes CE, runSuspendCatching rethrows
        // it, the outer coldStartSlimSync wrapper rethrows it, and the
        // launch{} propagates CE to the caller — `capturedCE` is non-null
        // and `returnedSnapshot` is null.
        //
        // Test shape mirrors T3's `expand cancel during thin_route_not_found
        // fallback propagates CancellationException` (Dispatcher + latch +
        // AtomicInteger in-flight gate). See lines 846-953 for the canonical
        // pattern; this test is its cold-start sibling.

        val messagesPageInFlight = java.util.concurrent.atomic.AtomicInteger(0)
        val releaseMessagesPage = java.util.concurrent.CountDownLatch(1)
        val dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    // Envelope pieces (sessions / questions / permissions) —
                    // serve immediately so coldStartSlimSync reaches the
                    // messages branch fast.
                    path.startsWith("/slimapi/sessions") -> jsonResponse("[]")
                    path.startsWith("/slimapi/questions") ->
                        jsonResponse("""{"items":[],"errors":[]}""")
                    path.startsWith("/slimapi/permissions") ->
                        jsonResponse("""{"items":[],"errors":[]}""")
                    // First /slimapi/messages/sess-1 page → count, then BLOCK
                    // on the latch until the test releases (or 10 s safety
                    // timeout). The drain is suspended on this response when
                    // we cancel.
                    path.startsWith("/slimapi/messages/sess-1") -> {
                        messagesPageInFlight.incrementAndGet()
                        releaseMessagesPage.await(10, TimeUnit.SECONDS)
                        jsonResponse(json.encodeToString(emptyList<MessageWithParts>()))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.dispatcher = dispatcher

        var returnedSnapshot: SlimColdStartSnapshot? = null
        var capturedCE: CancellationException? = null
        val job = launch {
            try {
                val result = repository.coldStartSlimSync(openSessionId = "sess-1", token = token())
                if (result.isSuccess) returnedSnapshot = result.getOrNull()
            } catch (e: CancellationException) {
                capturedCE = e
                throw e
            }
        }

        try {
            // Deterministic in-flight wait: poll until the messages page
            // request has arrived and is blocked on the latch. The drain's
            // first getSlimapiMessagesPage call is parked inside OkHttp
            // waiting for the response we are holding back.
            while (messagesPageInFlight.get() < 1) {
                delay(50)
            }
            // Cancel mid-flight — the suspended api coroutine MUST observe
            // CE; runSuspendCatching MUST rethrow it (not collapse to
            // Result.failure(CE)); coldStartSlimSync MUST propagate it.
            job.cancel()
            job.join()

            assertNull(
                "coldStartSlimSync MUST NOT return a snapshot when scope " +
                    "cancelled mid-drain (CE was swallowed into a bogus " +
                    "messages=null success after the drain already bumped " +
                    "the bookmark): $returnedSnapshot",
                returnedSnapshot,
            )
            assertNotNull(
                "CE MUST be thrown from coldStartSlimSync on scope cancel " +
                    "(structured-concurrency propagation — R-14): $capturedCE",
                capturedCE,
            )
        } finally {
            // Release the dispatcher's blocked thread so server.shutdown()
            // in @After can clean up cleanly (otherwise the dispatcher's
            // thread stays parked on the latch until the 10 s safety
            // timeout, slowing the test suite).
            releaseMessagesPage.countDown()
        }
    }

    @Test
    fun `coldStartSlimSync no bookmark transport failure mid-drain degrades to null and does NOT bump`() = runBlocking {
        // §11.5: transport failure mid-drain → Partial → the List façade
        // ([drainSlimapiMessagesBounded]) throws
        // [OpenCodeRepository.SlimCursorPartialException] → coldStart's
        // no-bookmark branch degrades to `messages = null`. The watermark
        // is NOT advanced (no-bump-on-partial); the next reconcile
        // retries the full cursor window from the prior watermark.
        //
        // This SUPERSEDES the rev-gpt round-2 "bump-on-partial" behavior:
        // bumping the watermark from a partial aggregate let the next
        // reconcile short-circuit to /since/{partial-watermark},
        // permanently losing the older pages that failed. The §11.5
        // contract is stricter: Partial NEVER bumps, NEVER exposes items
        // via the List façade.
        //
        // Test shape: page 1 succeeds (m1@200, m2@300) + X-Next-Cursor →
        // drain continues; page 2 returns HTTP 503 → drain's getOrElse
        // fires → Partial. coldStart degrades to messages=null; the
        // watermark stays absent (NOT bumped to 300).
        val m1 = MessageWithParts(
            info = Message(id = "m1", role = "user",
                time = Message.TimeInfo(updated = 200L))
        )
        val m2 = MessageWithParts(
            info = Message(id = "m2", role = "assistant",
                time = Message.TimeInfo(updated = 300L))
        )

        server.enqueue(jsonResponse("[]"))  // sessions
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))  // questions
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))  // permissions
        // Page 1: success + cursor → drain continues to page 2.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(json.encodeToString(listOf(m1, m2)))
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Next-Cursor", "page2-cursor")
        )
        // Page 2: HTTP 503 → drain Partial (no bump, partial items NOT exposed).
        server.enqueue(jsonResponse("""{"code":"upstream_unavailable"}""", 503))

        val result = repository.coldStartSlimSync(openSessionId = "sess-1", token = token())

        assertTrue("coldStart itself succeeds (partial → null degradation)", result.isSuccess)
        val snapshot = result.getOrThrow()
        assertNull(
            "transport failure → Partial → coldStart MUST degrade to null messages " +
                "(NOT expose partial items); messages=${snapshot.messages?.map { it.info.id }}",
            snapshot.messages,
        )

        // THE discriminating assertion: watermark NOT advanced (no-bump-on-partial).
        val state = repository.getSlimSessionState("sess-1")
        assertNull(
            "Partial transport failure MUST NOT advance localAppliedUpdatedAt; state=$state",
            state?.localAppliedUpdatedAt,
        )
        assertNull(
            "Partial transport failure MUST NOT advance localAppliedMessageId; state=$state",
            state?.localAppliedMessageId,
        )

        // Exactly 2 message requests: page 1 (success) + page 2 (failure).
        val requests = (1..5).map { server.takeRequest() }
        assertEquals(
            "exactly 2 message requests (1 success + 1 transport failure): ${requests.map { it.path }}",
            2,
            requests.count { it.path!!.startsWith("/slimapi/messages/sess-1") },
        )
    }

    // ── T2 (L1 G3): probeLatestSlim — boundary-normalising slim probe ───────
    //
    // §slimapi-client-impl-v1 §4 (G3 probeLatestMessageId 收敛): the slim-mode
    // probe MUST hit `GET /slimapi/messages/{sid}?limit=1&mode=skeleton` and
    // normalise every outcome (200 empty / 200 one-item / 4xx / network-fail)
    // into a single ProbeResult. This entry point feeds the reconcile state
    // machine in T7/T11 (resync) — bare-`probe[0]` access is forbidden there,
    // which is why the boundary lives HERE.
    //
    // The legacy `probeLatestMessageId` is regression-pinned (T2-C4) so the
    // 12+ mock sites in higher-level suites (CatchUpActionsTest /
    // AppCoreOrchestrationTest / OpenCodeRepositoryDirectoryTest /
    // OpenCodeRepositoryWrapperTest) keep compiling byte-for-byte.

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

    // ── Task 4 (L1 G2): getSlimapiSessionStatusOutcome — HTTP→StatusOutcome ──
    //
    // §slimapi-client-impl-v1 §6 G2 — the per-session status entry point
    // (`GET /slimapi/sessions/{sid}/status`) with the HTTP→StatusOutcome
    // routing living in the repo. T7 (reconcile) + T11 (StatusAggregator)
    // consume [StatusOutcome] without ever branching on HTTP status; this
    // suite pins each branch (C1-C3) via MockWebServer so the routing
    // cannot regress silently.
    //
    // Key invariant (T4-C2): a 200 busy/idle/retry MUST round-trip into
    // Success(status) WITHOUT folding — idle stays idle (the contract's
    // false-idle warning is the caller's problem, not the repo's).

    @Test
    fun `getSlimapiSessionStatus 200 busy hits slimapi per-session status path`() = runBlocking {
        // T4-C1: hits `GET /slimapi/sessions/{sid}/status` and 200 busy →
        // Success(busy) (NOT folded to idle — T4-C2).
        server.enqueue(jsonResponse("""{"type":"busy"}"""))

        val outcome = repository.getSlimapiSessionStatusOutcome("sess-1")

        assertTrue("Success: $outcome", outcome is StatusOutcome.Success)
        val ok = outcome as StatusOutcome.Success
        assertEquals("sess-1", ok.sessionId)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(
            "slimapi per-session status path: ${request.path}",
            "/slimapi/sessions/sess-1/status",
            request.path,
        )
        assertEquals("exactly one HTTP request", 1, server.requestCount)
    }

    @Test
    fun `getSlimapiSessionStatus 200 busy does NOT fold to idle`() = runBlocking {
        // T4-C2 (busy): the contract's §6 false-idle warning is the CALLER's
        // problem (cross-check against sessions list). The repo MUST return
        // Success(busy) so the caller can see the busy status the sidecar
        // reported. Folding busy→idle here would mask a real busy state.
        server.enqueue(jsonResponse("""{"type":"busy"}"""))

        val outcome = repository.getSlimapiSessionStatusOutcome("sess-1")

        assertTrue("Success: $outcome", outcome is StatusOutcome.Success)
        val status = (outcome as StatusOutcome.Success).status
        assertTrue("busy preserved (NOT folded): $status", status.isBusy)
        assertFalse("busy != idle: $status", status.isIdle)
        assertFalse("busy != retry: $status", status.isRetry)
    }

    @Test
    fun `getSlimapiSessionStatus 200 idle carries raw idle`() = runBlocking {
        // T4-C2 (idle): idle round-trips into Success(idle) — the reconcile
        // layer (T7/T11) cross-checks against the sessions list before
        // trusting the idle (false-idle risk per §6).
        server.enqueue(jsonResponse("""{"type":"idle"}"""))

        val outcome = repository.getSlimapiSessionStatusOutcome("sess-1")

        assertTrue("Success: $outcome", outcome is StatusOutcome.Success)
        val status = (outcome as StatusOutcome.Success).status
        assertTrue("idle preserved: $status", status.isIdle)
        assertFalse("idle != busy: $status", status.isBusy)
    }

    @Test
    fun `getSlimapiSessionStatus 200 retry carries raw retry with attempt and next`() = runBlocking {
        // T4-C2 (retry): the slim status envelope can carry `attempt` and
        // `next` on a retry status — both MUST survive the round-trip into
        // Success(retry) so the UI can show "retry in Ns" affordances.
        val body = """{"type":"retry","attempt":3,"message":"rate limited","next":1700000000}"""
        server.enqueue(jsonResponse(body))

        val outcome = repository.getSlimapiSessionStatusOutcome("sess-1")

        assertTrue("Success: $outcome", outcome is StatusOutcome.Success)
        val status = (outcome as StatusOutcome.Success).status
        assertTrue("retry preserved: $status", status.isRetry)
        assertEquals("attempt survived: $status", 3, status.attempt)
        assertEquals("next survived: $status", 1700000000L, status.next)
        assertEquals("message survived: $status", "rate limited", status.message)
    }

    @Test
    fun `getSlimapiSessionStatus 404 session_not_found yields SessionMissing`() = runBlocking {
        // T4-C3 (404): session is gone upstream → caller clears local cache +
        // removes from list. Distinct from Retry/UpstreamWarn so the caller
        // does NOT alert or retry on a deleted session.
        server.enqueue(jsonResponse("""{"code":"session_not_found"}""", 404))

        val outcome = repository.getSlimapiSessionStatusOutcome("sess-gone")

        assertTrue("SessionMissing: $outcome", outcome is StatusOutcome.SessionMissing)
        assertEquals("sess-gone", (outcome as StatusOutcome.SessionMissing).sessionId)
        assertEquals("no retries on 404", 1, server.requestCount)
    }

    @Test
    fun `getSlimapiSessionStatus 404 with other code does NOT clear local - routes to UpstreamWarn`() = runBlocking {
        // rev-gpt CRITICAL fix: a 404 with a body code OTHER than
        // `session_not_found` MUST NOT collapse to SessionMissing — that
        // would make the UI clear local cache for a session that's merely
        // hit a route-miss / unknown server error. Only `session_not_found`
        // carries the "session is deleted" semantic per the G2 contract.
        // Non-matching 404 → UpstreamWarn (non-destructive, code carried
        // for observability).
        server.enqueue(jsonResponse("""{"code":"some_other_404_code"}""", 404))

        val outcome = repository.getSlimapiSessionStatusOutcome("sess-1")

        assertFalse(
            "non-session_not_found 404 MUST NOT clear local: $outcome",
            outcome is StatusOutcome.SessionMissing,
        )
        assertTrue("UpstreamWarn: $outcome", outcome is StatusOutcome.UpstreamWarn)
        val warn = outcome as StatusOutcome.UpstreamWarn
        assertEquals("sess-1", warn.sessionId)
        assertEquals("code carried for observability: $warn", "some_other_404_code", warn.code)
    }

    @Test
    fun `getSlimapiSessionStatus 404 with null code does NOT clear local - routes to UpstreamWarn`() = runBlocking {
        // rev-gpt CRITICAL sibling: a 404 with NO body code at all (e.g.
        // an opaque proxy 404) also MUST NOT clear local — only an
        // explicit `session_not_found` is authoritative. Null code →
        // UpstreamWarn(null) (non-destructive default).
        server.enqueue(MockResponse().setResponseCode(404))

        val outcome = repository.getSlimapiSessionStatusOutcome("sess-1")

        assertFalse(
            "null-code 404 MUST NOT clear local: $outcome",
            outcome is StatusOutcome.SessionMissing,
        )
        assertTrue("UpstreamWarn: $outcome", outcome is StatusOutcome.UpstreamWarn)
        assertNull("null code preserved: $outcome", (outcome as StatusOutcome.UpstreamWarn).code)
    }

    @Test
    fun `getSlimapiSessionStatus 400 directory_not_allowed yields DirectoryError`() = runBlocking {
        // T4-C3 (400): directory config error → caller prompts the user
        // (deterministic misconfiguration, NOT a transient fault).
        server.enqueue(jsonResponse("""{"code":"directory_not_allowed"}""", 400))

        val outcome = repository.getSlimapiSessionStatusOutcome("sess-1")

        assertTrue("DirectoryError: $outcome", outcome is StatusOutcome.DirectoryError)
        assertEquals("sess-1", (outcome as StatusOutcome.DirectoryError).sessionId)
        assertEquals("no retries on 400", 1, server.requestCount)
    }

    @Test
    fun `getSlimapiSessionStatus 400 with non-directory code yields UpstreamWarn not DirectoryError`() = runBlocking {
        // rev-gpt IMPORTANT #2 fix: a 400 with a body code OTHER than
        // `directory_not_allowed` MUST NOT be misreported as DirectoryError
        // — that would mislead the UI into prompting the user about a
        // directory misconfiguration that isn't there. Route to
        // UpstreamWarn (non-destructive, code carried) instead.
        server.enqueue(jsonResponse("""{"code":"bad_request"}""", 400))

        val outcome = repository.getSlimapiSessionStatusOutcome("sess-1")

        assertFalse(
            "non-directory_not_allowed 400 MUST NOT be DirectoryError: $outcome",
            outcome is StatusOutcome.DirectoryError,
        )
        assertTrue("UpstreamWarn: $outcome", outcome is StatusOutcome.UpstreamWarn)
        val warn = outcome as StatusOutcome.UpstreamWarn
        assertEquals("sess-1", warn.sessionId)
        assertEquals("code carried: $warn", "bad_request", warn.code)
    }

    @Test
    fun `getSlimapiSessionStatus 200 with null body yields UpstreamWarn not fabricated idle`() = runBlocking {
        // rev-gpt IMPORTANT #1 fix: 200 + null body is a protocol
        // violation (the sidecar MUST return `{"type":"…"}`). Fabricating
        // `SessionStatus(type="idle")` would silently turn missing data
        // into a normal idle status — false-idle risk. Refuse and surface
        // non-destructively as UpstreamWarn(null) instead. MockWebServer
        // with a truly empty body makes Retrofit's kotlinx-serialization
        // converter call `ResponseBody.string()` + `decodeFromString("")`,
        // which throws `SerializationException` — caught by the repo's
        // SerializationException arm → UpstreamWarn (NOT Retry, NOT
        // fabricated Success).
        //
        // NOTE (rev-gpt re-review round 2): a real transport-level EOF
        // (mid-stream truncation, also surfaced as EOFException by OkHttp)
        // is NOT what this test exercises — it has its own dedicated
        // sibling test below (`... transport EOF mid-stream yields Retry
        // not UpstreamWarn`) that pins the IOException → Retry routing.
        // Empty body is converter-level (SerializationException); transport
        // EOF is connection-level (IOException, incl. EOFException as a
        // subclass) — the repo MUST keep the two apart.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(""),
        )

        val outcome = repository.getSlimapiSessionStatusOutcome("sess-1")

        assertFalse(
            "200 + null body MUST NOT fabricate idle / Success: $outcome",
            outcome is StatusOutcome.Success,
        )
        assertFalse(
            "200 + null body is a protocol violation, NOT transient transport " +
                "(MUST NOT be Retry): $outcome",
            outcome is StatusOutcome.Retry,
        )
        assertTrue("UpstreamWarn: $outcome", outcome is StatusOutcome.UpstreamWarn)
        val warn = outcome as StatusOutcome.UpstreamWarn
        assertEquals("sess-1", warn.sessionId)
        assertNull("null code (no body to parse): $warn", warn.code)
    }

    @Test
    fun `getSlimapiSessionStatus 502 upstream_http_N yields UpstreamWarn with code`() = runBlocking {
        // T4-C3 (502): upstream spoke to the sidecar but the sidecar's own
        // upstream returned a status worth surfacing → alert, keep local.
        // The code (e.g. `upstream_http_500`) MUST be passed along for
        // observability so callers can log it.
        server.enqueue(jsonResponse("""{"code":"upstream_http_500"}""", 502))

        val outcome = repository.getSlimapiSessionStatusOutcome("sess-1")

        assertTrue("UpstreamWarn: $outcome", outcome is StatusOutcome.UpstreamWarn)
        val warn = outcome as StatusOutcome.UpstreamWarn
        assertEquals("sess-1", warn.sessionId)
        assertEquals("code passed along: $warn", "upstream_http_500", warn.code)
    }

    @Test
    fun `getSlimapiSessionStatus 503 upstream_unavailable yields Retry with code`() = runBlocking {
        // T4-C3 (503): transient sidecar/upstream fault → caller backs off
        // and retries. The `upstream_unavailable` code is passed along so
        // callers can distinguish "server busy" from a transport failure
        // (Retry with null code, see next test).
        server.enqueue(jsonResponse("""{"code":"upstream_unavailable"}""", 503))

        val outcome = repository.getSlimapiSessionStatusOutcome("sess-1")

        assertTrue("Retry: $outcome", outcome is StatusOutcome.Retry)
        val retry = outcome as StatusOutcome.Retry
        assertEquals("sess-1", retry.sessionId)
        assertEquals("code passed along: $retry", "upstream_unavailable", retry.code)
    }

    @Test
    fun `getSlimapiSessionStatus network failure yields Retry with null code`() = runBlocking {
        // Transport failure (no HTTP status, no sidecar envelope) → Retry
        // with null code. Distinguishable from HTTP 503 (which carries the
        // sidecar's `upstream_unavailable` code) so callers can log
        // "transport" vs "server busy" if they want. Mirrors the G6 path's
        // Failed(null) collapse for network exceptions — here it's Retry
        // (transient) instead of Failed (terminal) because status is a
        // poll-style fetch the caller will re-fire on the next tick.
        server.shutdown()
        val deadRepo = OpenCodeRepository(mockk(relaxed = true), mockk(relaxed = true))
        deadRepo.configure(
            baseUrl = "http://127.0.0.1:${server.url("").port}",
            slim = true,
        )

        val outcome = deadRepo.getSlimapiSessionStatusOutcome("sess-1")

        assertTrue("Retry on network: $outcome", outcome is StatusOutcome.Retry)
        val retry = outcome as StatusOutcome.Retry
        assertEquals("sess-1", retry.sessionId)
        assertNull("null code on transport failure: $retry", retry.code)
    }

    @Test
    fun `getSlimapiSessionStatus transport EOF mid-stream yields Retry not UpstreamWarn`() = runBlocking {
        // rev-gpt re-review round 2 (IMPORTANT #1): a transport-level EOF
        // (mid-stream truncation, where the server drops the connection
        // while writing the response body) MUST route to Retry (transient).
        // EOFException is a subclass of IOException and is ambiguous: it
        // can mean either (a) the converter hit an empty/unparseable 2xx
        // body, OR (b) a real network truncation mid-stream. The repo
        // previously had a dedicated `catch (EOFException) -> UpstreamWarn`
        // arm that wrongly folded case (b) into the permanent UpstreamWarn
        // bucket — silently breaking the IOException → Retry (transient)
        // semantics. That arm was removed; EOFException now falls through
        // to the IOException catch → Retry. Case (a) is covered separately
        // by the SerializationException catch arm + the test above (200
        // + empty body → UpstreamWarn).
        //
        // Simulated with MockWebServer's SocketPolicy.DISCONNECT_DURING_
        // RESPONSE_BODY: the server starts writing a 200 with a JSON
        // body, then drops the socket mid-stream. OkHttp's body reader
        // sees an unexpected EOF (typically thrown as IOException, often
        // EOFException specifically from okio's RealBufferedSource) — the
        // repo's IOException catch arm must collapse it to Retry.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"type":"idle","someLongField":"this gets truncated""")
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )

        val outcome = repository.getSlimapiSessionStatusOutcome("sess-1")

        assertFalse(
            "transport EOF MUST NOT be UpstreamWarn (NOT permanent): $outcome",
            outcome is StatusOutcome.UpstreamWarn,
        )
        assertFalse(
            "transport EOF MUST NOT be SessionMissing: $outcome",
            outcome is StatusOutcome.SessionMissing,
        )
        assertTrue(
            "Retry on transport EOF (transient, NOT permanent): $outcome",
            outcome is StatusOutcome.Retry,
        )
        val retry = outcome as StatusOutcome.Retry
        assertEquals("sess-1", retry.sessionId)
        assertNull("null code on transport failure: $retry", retry.code)
    }
    // ── T11 round-3 fixes (cursor + CE discipline) ─────────────────────────

    /**
     * T11 round-3 Fix 2 (oracle I2 — cursor mid-failure distinguishable):
     * a mid-walk transport failure on `fetchSlimInitialWindowBounded`
     * returns `Result.failure(SlimCursorPartialException)` — NOT
     * `Result.success(partialItems)`. The reconciler can distinguish
     * incomplete-window from clean completion and preserve dirty.
     *
     * Round-2 bug: the façade wrapped Partial as Success — the reconciler
     * cleared dirty on an incomplete window, leaving the gap unreachable.
     */
    @Test
    fun `R3-Fix2 cursor mid-failure is distinguishable failure`() = runBlocking {
        // Page 1: success + cursor → drain continues.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(json.encodeToString(listOf(skeleton("m1", 100L))))
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Next-Cursor", "cursor-page-2"),
        )
        // Page 2: transport failure (socket disconnect → IOException).
        server.enqueue(
            MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST),
        )

        val result = repository.fetchSlimInitialWindowBounded("sid", token = token())

        // Mid-cursor failure MUST be Result.failure (distinguishable).
        assertTrue("mid-cursor failure must be Result.failure", result.isFailure)
        val cause = result.exceptionOrNull()
        assertNotNull("failure cause must be present", cause)
        // The cause should be SlimCursorPartialException wrapping the original.
        assertTrue(
            "cause must be SlimCursorPartialException (got ${cause?.javaClass?.name})",
            cause is OpenCodeRepository.SlimCursorPartialException,
        )
    }

    /**
     * T11 round-3 Fix 2b: clean cursor-null termination returns Success
     * (NOT failure). Discriminating test: confirms the new strict-failure
     * semantics don't accidentally mark clean terminations as failures.
     */
    @Test
    fun `R3-Fix2b cursor clean termination is success`() = runBlocking {
        // Single page, no X-Next-Cursor → drain terminates as Success.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(json.encodeToString(listOf(skeleton("m1", 100L))))
                .setHeader("Content-Type", "application/json"),
            // no X-Next-Cursor header
        )

        val result = repository.fetchSlimInitialWindowBounded("sid", token = token())

        assertTrue("clean termination must be Result.success", result.isSuccess)
        assertEquals(listOf("m1"), result.getOrThrow().map { it.info.id })
    }

    /**
     * T11 round-3 Fix 4 (CE discipline, R-14): cancellation during
     * coldStartSlimSync's metadata fetch propagates CE (NOT collapsed to
     * a null-piece snapshot via plain runCatching).
     *
     * Round-2 bug: plain `runCatching` around `api.getSlimapiSessions`
     * swallowed CancellationException, violating R-14. The metadata call
     * would return null (per-piece failure) instead of propagating CE,
     * making scope-cancel mid-coldStart produce a bogus partial snapshot.
     *
     * Test: enqueue no responses (server never answers); launch the
     * coldStart in a child job; cancel the job; assert CE propagates
     * (the surrounding runSuspendCatching in coldStartSlimSync re-throws).
     */
    @Test
    fun `R3-Fix4 coldStartSlimSync metadata cancellation propagates CE`() = runBlocking {
        // Enqueue nothing — the suspend Retrofit call will hang waiting
        // for a response. We cancel the calling job mid-call.
        val testScope = CoroutineScope(Dispatchers.Unconfined)
        var capturedException: Throwable? = null
        val job = testScope.launch {
            try {
                repository.coldStartSlimSync(openSessionId = null, directories = null, token = token())
            } catch (e: Throwable) {
                capturedException = e
            }
        }
        // Let the coroutine reach the suspend point + cancel.
        delay(200L)
        job.cancelAndJoin()
        assertNotNull(
            "CE must propagate (not be swallowed by plain runCatching); capturedException was null",
            capturedException,
        )
        assertTrue(
            "captured exception must be CancellationException (got ${capturedException?.javaClass?.name})",
            capturedException is CancellationException,
        )
    }

    /**
     * T11 round-4 (durable partial-cursor retry): a Partial drain outcome
     * in the T11 façade (`fetchSlimInitialWindowBounded`) does NOT advance
     * `localApplied*`. The next call to the façade re-enters the cursor
     * drain from the SAME pre-drain watermark (no `/since/{partial}`
     * short-circuit). Dirty stays preserved across retries until clean
     * Success.
     *
     * Round-3 bug: the drain bumped `localApplied*` on Partial
     * unconditionally; the next call would skip the cursor drain +
     * go `/since/{partial-watermark}`, losing older pages permanently.
     *
     * Discriminating regression test: this test FAILS under the round-3
     * buggy code (the pre-§11.5 drain bumped `localApplied*` on Partial
     * unconditionally → first Partial call advances watermark → second
     * call's path starts with `/slimapi/messages/` but the URL contains
     * `/since/` because the bookmark is now non-null → assertion fails
     * because no `/since/{partial-watermark}` request was expected).
     */
    @Test
    fun `R4-Fix2 partial drain does not advance localApplied - next call retries cursor drain`() = runBlocking {
        // Sanity: the session has NO localApplied watermark before any
        // drain (pre-condition for cursor-drain path selection).
        assertNull(
            "pre-condition: localAppliedUpdatedAt must be null",
            repository.getSlimSessionState("sid")?.localAppliedUpdatedAt,
        )

        // ── First call: page 1 (success + cursor), page 2 (socket disconnect →
        //    IOException → Partial). Assert: Result.failure (Partial) AND
        //    localAppliedUpdatedAt STILL null (partial did NOT advance).
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(json.encodeToString(listOf(skeleton("m1", 100L))))
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Next-Cursor", "cursor-page-2"),
        )
        // Socket disconnect → getSlimapiMessagesPage throws IOException →
        // drain Partial while the token remains current.
        server.enqueue(
            MockResponse().setSocketPolicy(
                okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST,
            ),
        )
        // Page 2 absorber: restClient has retryOnConnectionFailure(true) (GET
        // contract). OkHttp auto-retries the DISCONNECT_AFTER_REQUEST failure
        // exactly once. This 3rd response absorbs that retry so it does NOT
        // steal the second call's clean response from the MockResponse queue.
        server.enqueue(
            MockResponse().setResponseCode(500),
        )

        val firstResult = repository.fetchSlimInitialWindowBounded("sid", token = token())

        assertTrue("first call: Partial must be Result.failure", firstResult.isFailure)
        assertTrue(
            "first call: cause must be SlimCursorPartialException",
            firstResult.exceptionOrNull() is OpenCodeRepository.SlimCursorPartialException,
        )
        // THE discriminating assertion: localAppliedUpdatedAt is STILL null
        // (Partial did NOT advance the watermark). Round-3 bug would have
        // advanced it to 100 (m1's time.updated) AND created a state entry
        // carrying the partial watermark. Round-4 no-bump-on-partial
        // creates NO state entry (the drain touched nothing on failure).
        val stateAfterPartial = repository.getSlimSessionState("sid")
        // The state entry may or may not exist (depends on whether the
        // pre-drain state existed). The KEY assertion is that
        // localAppliedUpdatedAt is null — either the entry is absent,
        // or it's present with null localAppliedUpdatedAt.
        assertTrue(
            "Partial must NOT advance localAppliedUpdatedAt (round-4 durable retry); " +
                "state=$stateAfterPartial",
            stateAfterPartial?.localAppliedUpdatedAt == null,
        )
        assertTrue(
            "Partial must NOT advance localAppliedMessageId; state=$stateAfterPartial",
            stateAfterPartial?.localAppliedMessageId == null,
        )

        // Take the first 3 requests: page 1 + page 2 original (DISCONNECT)
        // + page 2 auto-retry (absorbed by 500). Draining all 3 prevents
        // the retry's request from being confused with the second call.
        server.takeRequest() // page 1
        server.takeRequest() // page 2 (original — DISCONNECT_AFTER_REQUEST)
        server.takeRequest() // page 2 (auto-retry absorber)

        // ── Second call: with localAppliedUpdatedAt STILL null, the
        //    coordinator-side reconcile would re-enter the cursor drain
        //    (NOT go /since/{partial-watermark}). Drive the façade again
        //    to confirm it issues a cursor-drain-style request (NOT a
        //    /since request). Enqueue full Success this time.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(json.encodeToString(listOf(skeleton("m1", 100L), skeleton("m2", 200L))))
                .setHeader("Content-Type", "application/json"),
            // no X-Next-Cursor → clean Success
        )

        val secondResult = repository.fetchSlimInitialWindowBounded("sid", token = token())
        assertTrue(
            "second call: clean Success (got: ${secondResult.exceptionOrNull()?.javaClass?.name}: ${secondResult.exceptionOrNull()?.message})",
            secondResult.isSuccess,
        )
        assertEquals(
            "second call: both items returned",
            listOf("m1", "m2"),
            secondResult.getOrThrow().map { it.info.id },
        )

        val secondRequest = server.takeRequest()
        // THE second discriminating assertion: the second call MUST hit
        // the cursor drain endpoint (NOT /since). Under the round-3 bug,
        // localAppliedUpdatedAt would be 100L → the coordinator-side
        // reconcile would branch to /since/100 (NOT this façade directly,
        // but if some caller did the watermark-branched fetch it would
        // go /since). For the façade itself, the path is always
        // /slimapi/messages/sid?limit=…&mode=skeleton — assert this
        // path is hit (no /since).
        assertEquals("GET", secondRequest.method)
        assertTrue(
            "second call MUST be a cursor-drain request (NOT /since): ${secondRequest.path}",
            secondRequest.path!!.startsWith("/slimapi/messages/sid"),
        )
        assertFalse(
            "second call MUST NOT use /since path (localAppliedUpdatedAt is null → cursor drain): ${secondRequest.path}",
            secondRequest.path!!.contains("/since/"),
        )

        // §11.1 fix-6 P0-3: the drain does NOT advance localAppliedUpdatedAt
        // internally. The caller MUST drive commitAuthoritative to bump the
        // watermark atomically. Confirms the no-bump-on-partial fix doesn't
        // accidentally prevent Success bumps.
        val stateAfterDrain = repository.getSlimSessionState("sid")
        assertTrue(
            "drain must NOT bump bookmark (P0-3)",
            stateAfterDrain == null || stateAfterDrain.localAppliedUpdatedAt == null,
        )

        // The caller drives commitAuthoritative to advance the watermark.
        val (ts, id) = maxMessageTuple(secondResult.getOrThrow())
            ?.let { it.first to it.second } ?: (null to null)
        val candidate = SlimAuthoritativeCandidate(
            sessionId = "sid",
            token = token(),
            messages = secondResult.getOrThrow(),
            localAppliedUpdatedAt = ts,
            localAppliedMessageId = id,
        )
        val commitResult = repository.commitAuthoritative(candidate)
        assertEquals("commit must succeed", SlimAuthoritativeCommitResult.Committed, commitResult)

        val stateAfterCommit = repository.getSlimSessionState("sid")!!
        assertEquals(
            "commit advances localAppliedUpdatedAt to max items' updated",
            200L,
            stateAfterCommit.localAppliedUpdatedAt,
        )
        assertEquals("m2", stateAfterCommit.localAppliedMessageId)
    }

    // ── §11.5 unified bounded-drain state contract ──────────────────────────

    /**
     * §11.5 helper: enqueue a 200 page with items + optional cursor.
     */
    private fun enqueuePage(items: List<MessageWithParts>, nextCursor: String? = null) {
        val resp = MockResponse().setResponseCode(200)
            .setBody(json.encodeToString(items))
            .setHeader("Content-Type", "application/json")
        if (nextCursor != null) resp.setHeader("X-Next-Cursor", nextCursor)
        server.enqueue(resp)
    }

    /**
     * §11.5: when [itemBound] is reached while `X-Next-Cursor` is still
     * non-null, the drain MUST return [SlimDrainOutcome.Partial]
     * (cause = [SlimDrainBoundExceededException]) — the item bound is a
     * SAFETY limit, NOT completeness proof. The facade surfaces this as
     * `Result.failure(SlimCursorPartialException)`; the watermark is NOT
     * advanced.
     */
    @Test
    fun `itemBoundWithNonNullCursorReturnsPartial`() = runBlocking {
        // itemBound=250, pageLimit=200 → page1 (200 items + cursor),
        // page2 (50 items + cursor) → aggregated=250, cursor non-null.
        enqueuePage(skeletons(1..200), nextCursor = "c2")
        enqueuePage(skeletons(201..250), nextCursor = "c3")

        val result = repository.fetchSlimInitialWindowBounded("sid", token = token())

        assertTrue("item bound + non-null cursor MUST be Result.failure", result.isFailure)
        val cause = result.exceptionOrNull()
        assertTrue(
            "cause must be SlimCursorPartialException (got ${cause?.javaClass?.name})",
            cause is OpenCodeRepository.SlimCursorPartialException,
        )
        // THE discriminating assertion: watermark NOT advanced.
        val state = repository.getSlimSessionState("sid")
        assertNull(
            "Partial MUST NOT advance localAppliedUpdatedAt; state=$state",
            state?.localAppliedUpdatedAt,
        )
        assertNull(
            "Partial MUST NOT advance localAppliedMessageId; state=$state",
            state?.localAppliedMessageId,
        )
    }

    /**
     * §11.5: when the page-count cap exhausts while `X-Next-Cursor` is
     * still non-null, the drain MUST return [SlimDrainOutcome.Partial]
     * (cause = [SlimDrainBoundExceededException]). Same no-bump invariant.
     */
    @Test
    fun `pageCapWithNonNullCursorReturnsPartial`() = runBlocking {
        // maxPages = (250+199)/200 + 1 = 3. Three small pages, each with a
        // non-null cursor and aggregated.size < itemBound → repeat exhausts
        // → Partial (page bound).
        enqueuePage(skeletons(1..1), nextCursor = "c2")
        enqueuePage(skeletons(2..2), nextCursor = "c3")
        enqueuePage(skeletons(3..3), nextCursor = "c4")

        val result = repository.fetchSlimInitialWindowBounded("sid", token = token())

        assertTrue("page cap + non-null cursor MUST be Result.failure", result.isFailure)
        val cause = result.exceptionOrNull()
        assertTrue(
            "cause must be SlimCursorPartialException (got ${cause?.javaClass?.name})",
            cause is OpenCodeRepository.SlimCursorPartialException,
        )
        val state = repository.getSlimSessionState("sid")
        assertNull(
            "page-cap Partial MUST NOT advance localAppliedUpdatedAt; state=$state",
            state?.localAppliedUpdatedAt,
        )
    }

    /**
     * §11.5: if the aggregate hits [itemBound] EXACTLY on a page whose
     * `nextCursor` is null (server signalled end-of-history), the result
     * is [SlimDrainOutcome.Success] — the terminal-page signal overrides
     * the bound. §11.1 fix-6 P0-3: the drain does NOT advance the watermark
     * internally — the caller MUST drive commitAuthoritative.
     *
     * Discriminates against `itemBoundWithNonNullCursorReturnsPartial`:
     * same item count, different cursor → different outcome.
     */
    @Test
    fun `terminalPageExactlyAtItemBoundIsSuccess`() = runBlocking {
        // page1 (200 items + cursor), page2 (50 items, NO cursor) →
        // aggregated=250, nextCursor=null → Success.
        enqueuePage(skeletons(1..200), nextCursor = "c2")
        enqueuePage(skeletons(201..250), nextCursor = null)

        val token = token()
        val result = repository.fetchSlimInitialWindowBounded("sid", token = token)

        assertTrue(
            "terminal page (cursor null) at itemBound MUST be Result.success " +
                "(got: ${result.exceptionOrNull()?.javaClass?.name}: ${result.exceptionOrNull()?.message})",
            result.isSuccess,
        )
        assertEquals(
            "all 250 items returned on Success",
            250,
            result.getOrThrow().size,
        )
        // §11.1 fix-6 P0-3: the drain does NOT advance the watermark internally.
        val stateAfterDrain = repository.getSlimSessionState("sid")
        assertTrue(
            "drain must NOT bump bookmark (P0-3)",
            stateAfterDrain == null || stateAfterDrain.localAppliedUpdatedAt == null,
        )

        // The caller MUST drive commitAuthoritative to advance the watermark.
        val (ts, id) = maxMessageTuple(result.getOrThrow())
            ?.let { it.first to it.second } ?: (null to null)
        val candidate = SlimAuthoritativeCandidate(
            sessionId = "sid",
            token = token,
            messages = result.getOrThrow(),
            localAppliedUpdatedAt = ts,
            localAppliedMessageId = id,
        )
        val commitResult = repository.commitAuthoritative(candidate)
        assertEquals("commit must succeed", SlimAuthoritativeCommitResult.Committed, commitResult)

        // After commit, the watermark IS advanced.
        val stateAfterCommit = repository.getSlimSessionState("sid")!!
        assertEquals(250L, stateAfterCommit.localAppliedUpdatedAt)
        assertEquals("m250", stateAfterCommit.localAppliedMessageId)
    }

    /**
     * §11.5: a mid-walk HTTP failure returns [SlimDrainOutcome.Partial];
     * the facade surfaces `Result.failure(SlimCursorPartialException)`;
     * the watermark is NOT advanced (no-bump-on-partial). Uses HTTP 500
     * (no OkHttp auto-retry, unlike socket disconnect).
     */
    @Test
    fun `partialTransportFailureDoesNotBumpBookmark`() = runBlocking {
        enqueuePage(skeletons(1..1), nextCursor = "c2")
        // Page 2: HTTP 500 → IOException("HTTP 500") → Partial.
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.fetchSlimInitialWindowBounded("sid", token = token())

        assertTrue("transport failure MUST be Result.failure", result.isFailure)
        assertTrue(
            "cause must be SlimCursorPartialException",
            result.exceptionOrNull() is OpenCodeRepository.SlimCursorPartialException,
        )
        val state = repository.getSlimSessionState("sid")
        assertNull(
            "Partial transport failure MUST NOT advance localAppliedUpdatedAt; state=$state",
            state?.localAppliedUpdatedAt,
        )
        assertNull(
            "Partial transport failure MUST NOT advance localAppliedMessageId; state=$state",
            state?.localAppliedMessageId,
        )
    }

    /**
     * §11.5: wall-clock timeout (30 s `withTimeout`) surfaces as
     * [SlimDrainOutcome.Partial] with a [kotlinx.coroutines.TimeoutCancellationException]
     * cause; the facade returns `Result.failure(SlimCursorPartialException)`;
     * the watermark is NOT advanced.
     *
     * IGNORED: the drain's `withTimeout(30_000L)` duration is NOT injectable
     * without a signature change (out of §11.5 scope). A real wall-clock
     * test would take >30s — too slow for the unit suite. The timeout
     * catch arm (`catch (e: TimeoutCancellationException) { Partial(...) }`)
     * feeds the SAME Partial→no-bump path as
     * `partialTransportFailureDoesNotBumpBookmark`, which covers the
     * contract. Re-enable once the timeout duration is injectable
     * (precedent: `ExpandBatchEngine.expandWallClockBudgetMsForTest`).
     */
    @Ignore("§11.5: drain timeout (30s) not injectable; covered by partial-transport no-bump test")
    @Test
    fun `timeoutDoesNotBumpBookmark`() = runBlocking {
        // Would need a >30s body delay to trip withTimeout.
    }

    /**
     * §11.5: cursor loop (server returns the same opaque cursor again)
     * surfaces as [SlimDrainOutcome.Degraded]; the facade returns
     * `Result.failure(SlimCursorPartialException)`; the watermark is NOT
     * advanced.
     */
    @Test
    fun `cursorLoopDoesNotBumpBookmark`() = runBlocking {
        // Page 1: [m1] + cursor "c2" → before becomes "c2".
        enqueuePage(skeletons(1..1), nextCursor = "c2")
        // Page 2: [m1 dup] + SAME cursor "c2" → loop detected
        // (before != null && nextCursor == before). aggregated=[m1]
        // non-empty → Degraded.
        enqueuePage(skeletons(1..1), nextCursor = "c2")

        val result = repository.fetchSlimInitialWindowBounded("sid", token = token())

        assertTrue("cursor loop MUST be Result.failure", result.isFailure)
        assertTrue(
            "cause must be SlimCursorPartialException",
            result.exceptionOrNull() is OpenCodeRepository.SlimCursorPartialException,
        )
        val state = repository.getSlimSessionState("sid")
        assertNull(
            "Degraded loop MUST NOT advance localAppliedUpdatedAt; state=$state",
            state?.localAppliedUpdatedAt,
        )
    }

    /**
     * §11.5: a page whose `X-Next-Cursor` is null is the ONLY terminal
     * condition that returns [SlimDrainOutcome.Success]. §11.1 fix-6 P0-3:
     * the drain does NOT advance the watermark internally — the caller MUST
     * drive commitAuthoritative. Discriminates against the bound/cap/loop/
     * transport paths which all return Partial/Degraded.
     */
    @Test
    fun `nullCursorIsTheOnlySuccessTerminalCondition`() = runBlocking {
        // Single page, no cursor → Success.
        enqueuePage(skeletons(1..2), nextCursor = null)

        val token = token()
        val result = repository.fetchSlimInitialWindowBounded("sid", token = token)

        assertTrue("null cursor MUST be Result.success", result.isSuccess)
        assertEquals(listOf("m1", "m2"), result.getOrThrow().map { it.info.id })

        // §11.1 fix-6 P0-3: the drain does NOT advance the watermark internally.
        val stateAfterDrain = repository.getSlimSessionState("sid")
        assertTrue(
            "drain must NOT bump bookmark (P0-3)",
            stateAfterDrain == null || stateAfterDrain.localAppliedUpdatedAt == null,
        )

        // The caller MUST drive commitAuthoritative to advance the watermark.
        val (ts, id) = maxMessageTuple(result.getOrThrow())
            ?.let { it.first to it.second } ?: (null to null)
        val candidate = SlimAuthoritativeCandidate(
            sessionId = "sid",
            token = token,
            messages = result.getOrThrow(),
            localAppliedUpdatedAt = ts,
            localAppliedMessageId = id,
        )
        val commitResult = repository.commitAuthoritative(candidate)
        assertEquals("commit must succeed", SlimAuthoritativeCommitResult.Committed, commitResult)

        // After commit, the watermark IS advanced.
        val stateAfterCommit = repository.getSlimSessionState("sid")!!
        assertEquals(2L, stateAfterCommit.localAppliedUpdatedAt)
        assertEquals("m2", stateAfterCommit.localAppliedMessageId)
    }

    /**
     * §11.5: [CancellationException] (non-timeout) propagates out of the
     * facade as a thrown CE (NOT as `Result.failure(CE)`). A scope cancel
     * mid-walk terminates the cursor follow cleanly without landing a
     * partial state mutation.
     */
    @Test
    fun `cancellationPropagates`() = runBlocking {
        // Enqueue nothing — the page fetch hangs waiting for a response.
        val testScope = CoroutineScope(Dispatchers.Unconfined)
        var capturedException: Throwable? = null
        val tok = token()
        val job = testScope.launch {
            try {
                repository.fetchSlimInitialWindowBounded("sid", token = tok)
            } catch (e: Throwable) {
                capturedException = e
            }
        }
        // Let the coroutine reach the suspend point.
        delay(300L)
        job.cancelAndJoin()

        assertNotNull(
            "CE must propagate (not be swallowed into Result.failure); capturedException was null",
            capturedException,
        )
        assertTrue(
            "captured exception must be CancellationException (got ${capturedException?.javaClass?.name})",
            capturedException is CancellationException,
        )
        // No watermark mutation on cancel.
        val state = repository.getSlimSessionState("sid")
        assertNull(
            "cancel MUST NOT advance localAppliedUpdatedAt; state=$state",
            state?.localAppliedUpdatedAt,
        )
    }

    /**
     * §11.5: the `drainSlimapiMessagesBounded` List facade MUST NOT
     * expose Partial/Degraded aggregate items. On Partial it throws
     * [OpenCodeRepository.SlimCursorPartialException]; the cold-start
     * no-bookmark caller ([coldStartSlimSync]) degrades the throw to a
     * null piece ("keep prior") — partial items never reach the merge.
     *
     * Drives the real List facade via [coldStartSlimSync]'s no-bookmark
     * branch: a partial drain MUST yield `snapshot.messages == null`
     * (NOT the partial m1 item). Under the old buggy facade (`...outcome.items`
     * unconditionally), cold-start would have received the partial [m1].
     */
    @Test
    fun `boundedListFacadeDoesNotExposePartialItems`() = runBlocking {
        // coldStartSlimSync enqueues sessions + questions + permissions, then
        // the cursor drain pages (no-bookmark branch → List facade).
        server.enqueue(jsonResponse("[]")) // sessions
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}""")) // questions
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}""")) // permissions
        // Page 1: success + cursor.
        enqueuePage(skeletons(1..1), nextCursor = "c2")
        // Page 2: HTTP 500 → Partial → List facade throws.
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.coldStartSlimSync(
            openSessionId = "sid",
            directories = null,
            token = token(),
        )

        assertTrue("coldStart itself succeeds (per-piece null degradation)", result.isSuccess)
        val snapshot = result.getOrThrow()
        assertNull(
            "Partial drain MUST degrade to null messages (NOT expose partial items); " +
                "messages=${snapshot.messages?.map { it.info.id }}",
            snapshot.messages,
        )
        // Watermark NOT advanced.
        val state = repository.getSlimSessionState("sid")
        assertNull(
            "Partial List-facade throw MUST NOT advance localAppliedUpdatedAt; state=$state",
            state?.localAppliedUpdatedAt,
        )
    }

    /**
     * §11.5 facade contract: transport failure → `Result.failure(SlimCursorPartialException)`.
     */
    @Test
    fun `partialTransportFacadeFailure`() = runBlocking {
        enqueuePage(skeletons(1..1), nextCursor = "c2")
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.fetchSlimInitialWindowBounded("sid", token = token())

        assertTrue("transport facade failure MUST be Result.failure", result.isFailure)
        assertTrue(
            "cause must be SlimCursorPartialException",
            result.exceptionOrNull() is OpenCodeRepository.SlimCursorPartialException,
        )
    }

    /**
     * §11.5 facade contract: cursor loop → `Result.failure(SlimCursorPartialException)`.
     */
    @Test
    fun `cursorLoopFacadeFailure`() = runBlocking {
        enqueuePage(skeletons(1..1), nextCursor = "c2")
        enqueuePage(skeletons(1..1), nextCursor = "c2")

        val result = repository.fetchSlimInitialWindowBounded("sid", token = token())

        assertTrue("loop facade failure MUST be Result.failure", result.isFailure)
        assertTrue(
            "cause must be SlimCursorPartialException",
            result.exceptionOrNull() is OpenCodeRepository.SlimCursorPartialException,
        )
    }

    /**
     * §11.5 facade contract: wall-clock timeout →
     * `Result.failure(SlimCursorPartialException)`.
     *
     * IGNORED: see `timeoutDoesNotBumpBookmark` — the 30s `withTimeout`
     * duration is not injectable. Re-enable once the drain accepts a
     * test-timeout hook.
     */
    @Ignore("§11.5: drain timeout (30s) not injectable; covered by partial-transport facade test")
    @Test
    fun `timeoutFacadeFailure`() = runBlocking {
        // Would need a >30s body delay to trip withTimeout.
    }

    /**
     * §11.5 facade contract: terminal cursor-null → `Result.success(items)`.
     */
    @Test
    fun `terminalCursorNullFacadeSuccess`() = runBlocking {
        enqueuePage(skeletons(1..2), nextCursor = null)

        val result = repository.fetchSlimInitialWindowBounded("sid", token = token())

        assertTrue("terminal cursor-null MUST be Result.success", result.isSuccess)
        assertEquals(listOf("m1", "m2"), result.getOrThrow().map { it.info.id })
    }

    /**
     * §11.5: a 2xx page with a null body is INCOMPLETE —
     * [getSlimapiMessagesPage] throws [SlimPageIncompleteException]; the
     * drain classifies it as [SlimDrainOutcome.Partial]; the facade
     * returns `Result.failure(SlimCursorPartialException)`; the watermark
     * is NOT advanced.
     *
     * The null body is produced by a JSON literal `"null"` (Retrofit
     * deserializes it to `response.body() == null`).
     */
    @Test
    fun `pageWithNullBodyIsIncompleteAndDoesNotAdvanceBookmark`() = runBlocking {
        // Page 1: 200 with body "null" → response.body() == null →
        // SlimPageIncompleteException → drain Partial.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("null")
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Next-Cursor", "c2"),
        )

        val result = repository.fetchSlimInitialWindowBounded("sid", token = token())

        assertTrue("null body MUST be Result.failure (incomplete)", result.isFailure)
        val cause = result.exceptionOrNull()
        assertTrue(
            "cause must be SlimCursorPartialException (got ${cause?.javaClass?.name})",
            cause is OpenCodeRepository.SlimCursorPartialException,
        )
        // THE discriminating assertion: watermark NOT advanced.
        val state = repository.getSlimSessionState("sid")
        assertNull(
            "null-body Partial MUST NOT advance localAppliedUpdatedAt; state=$state",
            state?.localAppliedUpdatedAt,
        )
    }

    // ── C-D3 v2 real-incarnation discriminators ────────────────────────────

    /**
     * T3.3-C3/C7 REST merge: real repository + MockWebServer. Request starts under
     * tokenA → configure rotates marker mid-flight AND retires the old client.
     * The in-flight HTTP call is canceled (IOException: Canceled) when the old
     * client is retired; [fetchSinceForStageA] classifies this as
     * [SlimSinceStageAOutcome.Failed] (per §11.1's "other transport/IO
     * exception → Failed" contract). B state stays empty (no watermark from
     * A's payload).
     *
     * §11.1 stage A: migrated from the legacy `getSlimapiMessagesSince` facade
     * to `fetchSinceForStageA`. The mid-flight rejection now manifests as a
     * transport failure (client retirement cancels the call) rather than a
     * post-response StaleSlimCommitException — both are "no bookmark advance"
     * outcomes, but the failure mode differs.
     *
     * Does NOT mock captureSlimCommitToken / isSlimCommitTokenCurrent.
     */
    @Test
    fun `T3-3-C3 C7 mid-flight configure rejects stale REST merge`() = runBlocking {
        val body = json.encodeToString(listOf(skeleton("m-stale", 999L)))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(body)
                .setHeader("Content-Type", "application/json")
                .setBodyDelay(400, TimeUnit.MILLISECONDS),
        )

        val tokenA = repository.captureSlimCommitToken()
        // Network MUST run on a real worker thread — runBlocking's
        // EventLoop would deadlock if takeRequest blocked the only thread
        // that can advance the Retrofit call.
        val deferred = async(Dispatchers.IO) {
            repository.fetchSinceForStageA("sess-a", since = 0L, token = tokenA)
        }

        // Prove network started before rotation.
        val started = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("request must start under tokenA before configure", started)

        // Rotate incarnation (host B). Clears slim state + marker + retires
        // the old client (which cancels the in-flight HTTP call).
        repository.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            slim = true,
        )
        assertFalse(
            "tokenA is stale after configure",
            repository.isSlimCommitTokenCurrent(tokenA),
        )

        val result = deferred.await()
        // §11.1: the in-flight HTTP call is canceled when configure retires the
        // old client. fetchSinceForStageA classifies this as Failed(IOException).
        assertTrue(
            "mid-flight configure must return Failed outcome (got $result)",
            result is SlimSinceStageAOutcome.Failed,
        )
        val cause = (result as SlimSinceStageAOutcome.Failed).cause
        assertTrue(
            "cause must be IOException (client retirement cancels the call), got ${cause.javaClass.name}",
            cause is java.io.IOException,
        )
        assertTrue(
            "new incarnation must not retain A watermark/state",
            repository.snapshotSlimSseState().isEmpty(),
        )
    }

    /**
     * C-D3 v2 §4.2: cursor second-page rotation. Page 1 succeeds under
     * tokenA; page 2 request starts; configure B; page 2 completes →
     * fetchSlimInitialWindowBounded fails with StaleSlimCommitException
     * and B has no bookmark from either page.
     */
    @Test
    fun `CD3-v2 cursor second-page configure rotation fails with StaleSlimCommitException`() = runBlocking {
        // Page 1: items + next cursor (no delay — drain advances quickly).
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(json.encodeToString(listOf(skeleton("m1", 100L))))
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Next-Cursor", "cursor-p2"),
        )
        // Page 2: delayed so we can rotate after the request starts.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(json.encodeToString(listOf(skeleton("m2", 200L))))
                .setHeader("Content-Type", "application/json")
                .setBodyDelay(400, TimeUnit.MILLISECONDS),
        )

        val tokenA = repository.captureSlimCommitToken()
        val deferred = async(Dispatchers.IO) {
            repository.fetchSlimInitialWindowBounded("sid", token = tokenA)
        }

        // Drain page 1 request.
        val page1 = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(page1)
        assertTrue(page1!!.path!!.startsWith("/slimapi/messages/sid"))

        // Wait for page 2 request to start, then rotate.
        val page2 = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("page 2 must start under tokenA", page2)
        assertTrue(
            "page 2 must carry the same cursor",
            page2!!.path!!.contains("before=cursor-p2"),
        )

        repository.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            slim = true,
        )

        val result = deferred.await()
        assertTrue("second-page rotation must fail", result.isFailure)
        assertTrue(
            "cause must be StaleSlimCommitException (got ${result.exceptionOrNull()})",
            result.exceptionOrNull() is OpenCodeRepository.StaleSlimCommitException,
        )
        assertTrue(
            "B incarnation must not keep page1/page2 bookmarks",
            repository.snapshotSlimSseState().isEmpty(),
        )
        assertFalse(
            "tokenA must be stale",
            repository.isSlimCommitTokenCurrent(tokenA),
        )
    }

    /**
     * Control for §4.2: without configure, the same two-page drain succeeds
     * and returns the aggregated items. §11.1 fix-6 P0-3: the drain does NOT
     * bump the bookmark internally — the caller MUST drive
     * commitAuthoritative to advance the watermark atomically.
     */
    @Test
    fun `CD3-v2 cursor two-page control completes and bumps once`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(json.encodeToString(listOf(skeleton("m1", 100L))))
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Next-Cursor", "cursor-p2"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(json.encodeToString(listOf(skeleton("m2", 200L))))
                .setHeader("Content-Type", "application/json"),
        )

        val token = repository.captureSlimCommitToken()
        val result = repository.fetchSlimInitialWindowBounded("sid", token = token)

        assertTrue(result.isSuccess)
        assertEquals(listOf("m1", "m2"), result.getOrThrow().map { it.info.id })

        // §11.1 fix-6 P0-3: the drain does NOT bump the bookmark internally.
        val stateAfterDrain = repository.getSlimSessionState("sid")
        assertTrue(
            "drain must NOT bump bookmark (P0-3)",
            stateAfterDrain == null || stateAfterDrain.localAppliedUpdatedAt == null,
        )

        // The caller MUST drive commitAuthoritative to advance the watermark.
        val (ts, id) = maxMessageTuple(result.getOrThrow())
            ?.let { it.first to it.second } ?: (null to null)
        val candidate = SlimAuthoritativeCandidate(
            sessionId = "sid",
            token = token,
            messages = result.getOrThrow(),
            localAppliedUpdatedAt = ts,
            localAppliedMessageId = id,
        )
        val commitResult = repository.commitAuthoritative(candidate)
        assertEquals("commit must succeed", SlimAuthoritativeCommitResult.Committed, commitResult)

        // After commit, the watermark IS advanced.
        val stateAfterCommit = repository.getSlimSessionState("sid")
        assertEquals(200L, stateAfterCommit?.localAppliedUpdatedAt)
        assertEquals("m2", stateAfterCommit?.localAppliedMessageId)
    }

    // ── C-D3 rev-3 reconfigure-boundary discriminators ─────────────────────

    /**
     * C-D3 rev-3 Critical: [OpenCodeRepository.beginSlimReconfigure] rotates
     * the marker BEFORE any hostConfig mutation. An old-token
     * [commitIfSlimTokenCurrent] is rejected immediately (no need to wait
     * for [configure] to return).
     */
    @Test
    fun `CD3-rev3 beginSlimReconfigure rejects old commitIf before configure`() {
        val tokenA = repository.captureSlimCommitToken()
        assertTrue(repository.isSlimCommitTokenCurrent(tokenA))

        repository.beginSlimReconfigure()

        assertFalse(
            "tokenA must be stale immediately after beginSlimReconfigure",
            repository.isSlimCommitTokenCurrent(tokenA),
        )
        var ran = false
        val accepted = repository.commitIfSlimTokenCurrent(tokenA) { ran = true }
        assertFalse("commitIf must reject old token after beginSlimReconfigure", accepted)
        assertFalse("commit block must not run", ran)

        // configure still rewires network (defense-in-depth second rotate is fine).
        repository.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            slim = true,
        )
        assertFalse(
            "tokenA stays stale after configure (fail-forward, no rollback)",
            repository.isSlimCommitTokenCurrent(tokenA),
        )
    }

    /**
     * C-D3 rev-3 Critical: reconfigure-transaction ordering —
     * hold old-token mid-flight network → beginSlimReconfigure →
     * (simulated HostStatePurged: no UI here) → configure → release
     * delayed response. Assert Failed(IOException) and empty slim
     * state (no A watermark).
     *
     * §11.1 stage A: migrated from the legacy `getSlimapiMessagesSince`
     * facade (which now returns SlimSinceStagingOnlyException unconditionally
     * without making the HTTP call) to `fetchSinceForStageA`, which DOES
     * make the HTTP call. When configure retires the old client, the in-flight
     * HTTP call is canceled → [SlimSinceStageAOutcome.Failed](IOException).
     */
    @Test
    fun `CD3-rev3 reconfigure transaction beginSlim then configure rejects mid-flight since`() = runBlocking {
        val body = json.encodeToString(listOf(skeleton("m-stale", 999L)))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(body)
                .setHeader("Content-Type", "application/json")
                .setBodyDelay(400, TimeUnit.MILLISECONDS),
        )

        val tokenA = repository.captureSlimCommitToken()
        val deferred = async(Dispatchers.IO) {
            repository.fetchSinceForStageA("sess-a", since = 0L, token = tokenA)
        }

        val started = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("request must start under tokenA", started)

        // Transaction order: marker rotate FIRST (as HostProfileController
        // does before HostStatePurged), then configure rewires host + retires
        // the old client (which cancels the in-flight HTTP call).
        repository.beginSlimReconfigure()
        assertFalse(repository.isSlimCommitTokenCurrent(tokenA))
        repository.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            slim = true,
        )

        val result = deferred.await()
        // §11.1: configure retires the old client → in-flight HTTP call
        // canceled → Failed(IOException).
        assertTrue(
            "mid-flight after beginSlim must return Failed outcome (got $result)",
            result is SlimSinceStageAOutcome.Failed,
        )
        val cause = (result as SlimSinceStageAOutcome.Failed).cause
        assertTrue(
            "cause must be IOException (client retirement), got ${cause.javaClass.name}",
            cause is java.io.IOException,
        )
        assertTrue(
            "new incarnation must not retain A watermark/state",
            repository.snapshotSlimSseState().isEmpty(),
        )
    }

    /**
     * C-D3 rev-3 Critical: the purge→configure window that v2 missed —
     * after beginSlimReconfigure (and a conceptual HostStatePurged) but
     * BEFORE configure() returns, an old-token commit is already rejected.
     * This is the exact window rev-gpt round-3 called out.
     */
    @Test
    fun `CD3-rev3 purge-to-configure window rejects old commitIf without waiting for configure`() {
        val tokenA = repository.captureSlimCommitToken()

        // Profile-switch step: identity bump (N/A here) + beginSlimReconfigure
        // before HostStatePurged / configure.
        repository.beginSlimReconfigure()

        // Simulated post-purge / pre-configure window: old workflow tries
        // to land a slice/effect commit.
        var ran = false
        val accepted = repository.commitIfSlimTokenCurrent(tokenA) { ran = true }
        assertFalse(
            "purge→configure window must reject old token (v2 gap)",
            accepted,
        )
        assertFalse(ran)

        // configure may still be in progress / fail; marker must not roll back.
        repository.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            slim = true,
        )
        assertFalse(repository.isSlimCommitTokenCurrent(tokenA))
    }

    /**
     * C-D3 rev-3 round-5 readiness bit discriminator (oracle §10.2):
     *
     * Proves the **issuedReady bit** — not the marker — is what rejects a
     * token captured DURING a reconfigure transaction. The pre-round-5 test
     * went through `repository.configure(...)` after `beginSlimReconfigure()`,
     * but configure's defense-in-depth `beginSlimReconfigure()` rotates the
     * marker a SECOND time, so the post-configure marker mismatch (not
     * issuedReady) was doing the rejection — the discriminator was testing
     * the wrong thing.
     *
     * Round-5 fix: thread the SAME ticket through `completeSlimReconfigure(ticket)`
     * so the marker does NOT rotate between midToken capture and completion.
     * Now midToken.marker === current marker after completion — the ONLY
     * difference is issuedReady. If `isSlimCommitTokenCurrent(midToken)`
     * returns false here, it MUST be the readiness bit doing the rejection.
     */
    @Test
    fun `CD3-rev3 readiness bit rejects mid-transaction capture even after configure completes`() {
        // Establish a ready incarnation A.
        repository.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            slim = true,
        )
        val tokenA = repository.captureSlimCommitToken()
        assertTrue("tokenA (ready incarnation) must be current", repository.isSlimCommitTokenCurrent(tokenA))

        // Reconfigure transaction begins: marker rotates to M_B, readiness
        // goes false. The returned ticket carries M_B (its marker field).
        val ticket = repository.beginSlimReconfigure()
        assertFalse("tokenA stale after beginSlimReconfigure", repository.isSlimCommitTokenCurrent(tokenA))

        // A workflow captures a token DURING the transaction (between
        // beginSlimReconfigure and completion). midToken.marker == M_B
        // (CURRENT marker); midToken.issuedReady == false (captured while
        // incarnation was not-ready). This is the exact mid-transaction
        // capture the readiness bit must reject.
        val midToken = repository.captureSlimCommitToken()
        assertFalse(
            "mid-transaction token (issuedReady=false) must be rejected before completion",
            repository.isSlimCommitTokenCurrent(midToken),
        )
        var ran = false
        val acceptedMid = repository.commitIfSlimTokenCurrent(midToken) { ran = true }
        assertFalse("mid-transaction commitIf must be rejected", acceptedMid)
        assertFalse("mid-transaction commit block must not run", ran)

        // CRITICAL: complete via the SAME ticket (no second marker rotation).
        // The marker stays at M_B; only slimIncarnationReady flips true.
        // After this, midToken.marker === current marker — so any rejection
        // of midToken MUST be issuedReady, not marker mismatch.
        repository.completeSlimReconfigure(ticket)

        // The mid-transaction token is STILL invalid: its issuedReady=false
        // is permanent. A marker-only check would WRONGLY accept it here
        // because midToken.marker === current marker. This assertion is the
        // readiness bit's core guarantee.
        assertFalse(
            "mid-transaction token must remain invalid after same-ticket completion (issuedReady permanent, NOT marker mismatch)",
            repository.isSlimCommitTokenCurrent(midToken),
        )
        var ranMidLate = false
        val acceptedMidLate = repository.commitIfSlimTokenCurrent(midToken) { ranMidLate = true }
        assertFalse(
            "mid-transaction commitIf must STILL be rejected after completion",
            acceptedMidLate,
        )
        assertFalse("mid-transaction late commit block must not run", ranMidLate)

        // A FRESH capture after completion is valid (issuedReady=true, same
        // marker M_B). This proves the incarnation IS reachable — only the
        // mid-transaction capture is permanently barred.
        val tokenB = repository.captureSlimCommitToken()
        assertTrue(
            "fresh token after same-ticket completion must be current (issuedReady=true, marker matches)",
            repository.isSlimCommitTokenCurrent(tokenB),
        )
        var ranB = false
        val acceptedB = repository.commitIfSlimTokenCurrent(tokenB) { ranB = true }
        assertTrue("fresh post-completion commitIf must be accepted", acceptedB)
        assertTrue("fresh post-completion commit block must run", ranB)
    }

    /**
     * C-D3 rev-3 round-5 ticket-ownership discriminator (oracle §1.4):
     *
     * A ticket superseded by a later [beginSlimReconfigure] throws
     * [OpenCodeRepository.SupersededSlimReconfigureException] when presented
     * to `completeSlimReconfigure` — and NEVER re-arms readiness for the
     * superseded ticket. The new (superseding) ticket still completes
     * normally; the loser stays not-ready.
     */
    @Test
    fun `CD3-rev3 round-5 superseded ticket throws and never re-arms readiness`() {
        // Establish ready incarnation A.
        repository.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            slim = true,
        )

        // Transaction T1 begins.
        val ticketT1 = repository.beginSlimReconfigure()
        // T2 supersedes T1 before T1 completes.
        val ticketT2 = repository.beginSlimReconfigure()

        // T1 completion must throw — its marker was superseded by T2.
        val thrown = org.junit.Assert.assertThrows(
            OpenCodeRepository.SupersededSlimReconfigureException::class.java,
        ) { repository.completeSlimReconfigure(ticketT1) }
        assertNotNull("superseded ticket must throw on completion", thrown)

        // Readiness stays false (T1 did NOT re-arm; T2 hasn't completed yet).
        val midToken = repository.captureSlimCommitToken()
        assertFalse(
            "incarnation must remain not-ready after superseded completion throw",
            repository.isSlimCommitTokenCurrent(midToken),
        )

        // T2 completes normally — the winner re-arms readiness.
        repository.completeSlimReconfigure(ticketT2)
        val freshToken = repository.captureSlimCommitToken()
        assertTrue(
            "fresh token after winning-ticket completion must be current",
            repository.isSlimCommitTokenCurrent(freshToken),
        )
    }

    /**
     * C-D3 rev-3 round-6 failed-configure discriminator (oracle §10.3 +
     * round-6 review §2/§6):
     *
     * Forces configure to ACTUALLY throw via a malformed Retrofit base URL —
     * the REAL production failure path. configure's catch (OpenCodeRepository.kt
     * ~752-758) is `catch (error) { throw error }` — it RETHROWS (verified
     * contract). The fail-forward guarantee is structural: beginSlimReconfigure
     * runs BEFORE the throw (marker rotates, readiness=false), and the throw
     * skips completeSlimReconfigure (readiness stays false).
     *
     * Pre-round-6 this test stubbed `completeSlimReconfigure(any())` itself to
     * throw — that's tautological: it forces the readiness write to skip by
     * mocking the very seam under test. The round-6 rewrite triggers a genuine
     * failure upstream (Retrofit.baseUrl rejects the malformed URL during
     * rebuildClients) and leaves completeSlimReconfigure REAL.
     *
     * Asserts the OBSERVABLE fail-forward contract:
     *  - configure throws (assertThrows catches the real Retrofit/OkHttp parse
     *    failure — exact type not pinned; the discriminator cares that SOME
     *    throw escapes)
     *  - old tokenA stale (marker rotated by beginSlimReconfigure pre-throw)
     *  - during-failure token permanently invalid (issuedReady=false)
     *  - recovery configure re-arms readiness for a FRESH token
     *  - during-failure token stays invalid even after recovery
     */
    @Test
    fun `CD3-rev3 readiness bit failed configure leaves incarnation not ready`() {
        // Establish ready incarnation A.
        repository.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            slim = true,
        )
        val tokenA = repository.captureSlimCommitToken()
        assertTrue("tokenA current before failure", repository.isSlimCommitTokenCurrent(tokenA))

        // Force a REAL configure failure: a malformed baseUrl that
        // Retrofit.Builder().baseUrl(String) rejects during rebuildClients().
        // Failure ordering (verified by reading OpenCodeRepository.configure):
        //   1. val ticket = beginSlimReconfigure()        ← marker rotates, ready=false
        //   2. requireCurrentReconfigureTicket(ticket)    ← passes
        //   3. sslConfigFactory.configureClientCert(null) ← runs (swallows errors internally)
        //   4. hostConfig.configure(malformed, ...)       ← runs (pure field assignment)
        //   5. rebuildClients()                           ← THROWS (Retrofit.baseUrl rejects)
        //   6. completeSlimReconfigure(ticket)            ← SKIPPED (ready stays false)
        //   7. catch (error) { throw error }              ← RETHROWS
        // The space in the host segment makes HttpUrl.get return null →
        // Retrofit throws (NullPointerException or IllegalArgumentException
        // depending on version — the discriminator only pins "some throw").
        val malformedBaseUrl = "http://host with space/"
        val configureError = org.junit.Assert.assertThrows(
            Throwable::class.java,
        ) {
            repository.configure(
                baseUrl = malformedBaseUrl,
                slim = true,
            )
        }
        assertNotNull(
            "malformed baseUrl must cause configure to throw (real Retrofit failure, not a stub)",
            configureError,
        )

        // Fail-forward contract: marker rotated pre-throw → tokenA stale.
        assertFalse(
            "tokenA must be stale after failed configure rotated the marker",
            repository.isSlimCommitTokenCurrent(tokenA),
        )
        // During-failure token: captured while readiness is false (the throw
        // skipped completeSlimReconfigure). Permanently invalid.
        val tokenDuringFailure = repository.captureSlimCommitToken()
        assertFalse(
            "token captured during failed configure (issuedReady=false) must be rejected",
            repository.isSlimCommitTokenCurrent(tokenDuringFailure),
        )

        // Recovery: a later successful configure re-arms readiness for a
        // fresh token. completeSlimReconfigure is REAL — no stubs involved.
        repository.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            slim = true,
        )
        val tokenAfterRecovery = repository.captureSlimCommitToken()
        assertTrue(
            "fresh token after recovery configure must be current",
            repository.isSlimCommitTokenCurrent(tokenAfterRecovery),
        )
        // During-failure token stays permanently invalid (issuedReady=false,
        // even though marker matches post-recovery in some rotations — the
        // issuedReady bit is what rejects it).
        assertFalse(
            "during-failure token must remain invalid even after recovery (issuedReady permanent)",
            repository.isSlimCommitTokenCurrent(tokenDuringFailure),
        )
    }

    // ── T0 (safety net) / task2 §2.3 P2: transport wire-shape isolation ────
    //
    // The slimapi interceptors (SlimapiVersionInterceptor /
    // SlimapiCapabilitiesInterceptor) are DOUBLE-GATED: they inject their
    // headers ONLY when `HostConfig.slim == true` AND the path starts with
    // `/slimapi/` (SlimapiContract.SLIMAPI_PATH_PREFIX). Legacy mode (`slim=false`)
    // MUST be byte-for-byte unchanged: no slimapi headers, no `/slimapi/` path
    // prefix on ANY REST method.
    //
    // REGRESSION GUARD: removing the `!hostConfig.slim` door in either
    // interceptor (SlimapiVersionInterceptor.kt:39 /
    // SlimapiCapabilitiesInterceptor.kt:43) MUST turn the legacy test red
    // (legacy would start emitting slimapi headers). The slim test pins the
    // inverse: both headers MUST be present together on `/slimapi/` paths
    // (removing EITHER door breaks it).

    /**
     * T0-P2 legacy wire-shape: with `slim=false`, REST methods emit NEITHER
     * `X-Slimapi-Version` / `X-Slimapi-Capabilities` headers NOR any
     * `/slimapi/` path prefix. Pins the double-door so a future "helpful"
     * removal of the `!hostConfig.slim` interceptor gate cannot silently
     * pollute legacy opencode-direct traffic.
     */
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
        assertNull(
            "legacy getSessions MUST NOT carry X-Slimapi-Capabilities: ${sessionsReq.headers}",
            sessionsReq.getHeader(cn.vectory.ocdroid.data.repository.http.SlimapiContract.X_SLIMAPI_CAPABILITIES),
        )

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
        assertNull(
            "legacy getMessagesPaged MUST NOT carry X-Slimapi-Capabilities",
            msgsReq.getHeader(cn.vectory.ocdroid.data.repository.http.SlimapiContract.X_SLIMAPI_CAPABILITIES),
        )
    }

    /**
     * T0-P2 slim wire-shape: with `slim=true`, a `/slimapi/` REST method MUST
     * carry BOTH `X-Slimapi-Version` AND `X-Slimapi-Capabilities` together
     * (the double-door AND). Pins that neither interceptor's path-prefix door
     * nor the slim-flag door can be dropped silently.
     */
    @Test
    fun `T0-P2 slim mode emits both slimapi version and capabilities headers on slimapi path`() = runBlocking {
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
        assertEquals(
            "slimapi path MUST carry X-Slimapi-Capabilities == mid-partial-envelope=1",
            cn.vectory.ocdroid.data.repository.http.SlimapiContract.MID_PARTIAL_ENVELOPE_CAPABILITY,
            req.getHeader(cn.vectory.ocdroid.data.repository.http.SlimapiContract.X_SLIMAPI_CAPABILITIES),
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

    /**
     * §11.1 fix-10 P0-1: getMessagesPaged slim mode with `before != null`
     * does NOT advance localApplied (no drain / no commit — it's a
     * single-page history pagination, not a completeness sync).
     */
    @Test
    fun `§11_1 fix-10 P0-1 getMessagesPaged slim load-more does NOT advance localApplied`() = runBlocking {
        val repo = makeRepository(slim = true)
        // Seed a low localApplied so we can detect if it changes.
        val seedToken = repo.captureSlimCommitToken()
        repo.bumpSlimBookmarkFromItems(
            "sess-1",
            listOf(MessageWithParts(info = Message(id = "m-seed", role = "assistant", time = Message.TimeInfo(updated = 500L)))),
            seedToken,
        )
        val stateBefore = repo.getSlimSessionState("sess-1")
        assertEquals(500L, stateBefore?.localAppliedUpdatedAt)

        // Load-more with before cursor.
        server.enqueue(jsonResponse(json.encodeToString(listOf(skeleton("m1", 1000L)))))
        repo.getMessagesPaged("sess-1", limit = 10, before = "cursor-prev")

        val stateAfter = repo.getSlimSessionState("sess-1")
        assertEquals(
            "P0-1: load-more MUST NOT advance localAppliedUpdatedAt (no commit)",
            500L,
            stateAfter?.localAppliedUpdatedAt,
        )
    }

    /**
     * §11.1 fix-10 P0-1: getMessagesPaged slim mode with `before == null`
     * (initial/reload) IGNORES the UI [limit] and uses the drain safety
     * bound (SLIMAPI_LOCAL_HISTORY_BOUND). The drain's per-page size is
     * SLIMAPI_DEFAULT_PAGE_LIMIT.
     */
    @Test
    fun `§11_1 fix-10 P0-1 getMessagesPaged slim initial ignores UI limit uses drain bound`() = runBlocking {
        val repo = makeRepository(slim = true)
        // The drain's per-page size is SLIMAPI_DEFAULT_PAGE_LIMIT=200.
        // Enqueue a terminal page with 5 items — the drain succeeds.
        // The UI limit=5 is IGNORED (not forwarded as a query param).
        server.enqueue(jsonResponse(json.encodeToString(skeletons(1..5))))

        val result = repo.getMessagesPaged("sess-1", limit = 5, before = null)

        assertTrue("initial drain MUST succeed (got ${result.exceptionOrNull()})", result.isSuccess)
        val request = server.takeRequest()
        // The drain's per-page limit is the default (200), NOT the UI
        // limit (5). Assert that limit=5 is NOT in the query.
        assertFalse(
            "P0-1: UI limit=5 must NOT be forwarded as query param on initial drain: ${request.path}",
            request.path!!.contains("limit=5"),
        )
    }

    /**
     * §11.1 fix-10 P0-1: getMessagesPaged slim mode with `before == null`
     * (initial) on a long session whose history exceeds the drain bound →
     * Result.failure (Partial drain — the known trade-off per §11.5).
     */
    @Test
    fun `§11_1 fix-10 P0-1 getMessagesPaged slim initial long history returns Partial failure`() = runBlocking {
        val repo = makeRepository(slim = true)
        // First page: 200 items + non-terminal cursor.
        // SLIMAPI_DEFAULT_PAGE_LIMIT=200, SLIMAPI_LOCAL_HISTORY_BOUND=250.
        // The drain fetches page 1 (200 items, cursor set), then page 2
        // (50 more items, cursor still set → itemBound hit → Partial).
        // 2 pages enqueued:
        // page 1: 200 items + X-Next-Cursor
        // page 2: at least 1 item + X-Next-Cursor (so the bound is hit
        // while cursor is non-null → Partial)
        server.enqueue(
            jsonResponse(json.encodeToString(skeletons(1..200)))
                .setHeader("X-Next-Cursor", "cursor-page-1"),
        )
        server.enqueue(
            jsonResponse(json.encodeToString(skeletons(201..251)))
                .setHeader("X-Next-Cursor", "cursor-page-2"),
        )

        val result = repo.getMessagesPaged("sess-1", limit = 20, before = null)

        // Partial drain → Result.failure(SlimCursorPartialException)
        // (itemBound=250 reached while cursor is still non-null).
        assertTrue(
            "P0-1: long history exceeding drain bound MUST be Result.failure (Partial) — got ${result.exceptionOrNull()?.let { it::class.simpleName }}",
            result.isFailure,
        )
    }

    /**
     * §11.1 fix-10 P0-1: consecutive load-more pages produce the expected
     * sequence of cursors. Page 1 → nextCursor=A, Page 2 (before=A) →
     * nextCursor=null (end of history).
     */
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

    /**
     * §11.1 fix-9 P0-4: after a successful commit advances localApplied*
     * to the candidate tuple, the commit MUST re-evaluate dirty against
     * the session's remote* tuple. If remote* > localApplied* (a digest
     * arrived mid-drain and advanced remote past what we just applied),
     * dirty MUST be re-set to `true`. This test seeds a high remote via
     * a digest, then runs a drain whose max tuple is BELOW remote. The
     * commit succeeds (candidate > prior-localApplied of null) BUT dirty
     * is re-set to true post-commit (remote > localApplied).
     */
    @Test
    fun `§11_1 fix-9 P0-4 dirty re-eval keeps dirty true when remote exceeds committed localApplied`() = runBlocking {
        val repo = makeRepository(slim = true)
        val token = repo.captureSlimCommitToken()
        // Seed remote* via a full-tuple digest (remote=5000/m_remote).
        // localApplied stays null. dirty ratchets to true via reducer.
        repo.applySlimDigest(
            SlimSessionDigest(sessionId = "sess-1", updatedAt = 5000L, messageId = "m_remote"),
            token = token,
        )
        val seeded = repo.getSlimSessionState("sess-1")!!
        assertEquals(5000L, seeded.remoteUpdatedAt)
        assertEquals("m_remote", seeded.remoteMessageId)
        assertNull(seeded.localAppliedUpdatedAt)
        assertTrue("dirty ratchets after remote advance", seeded.dirty)

        // Drain + commit a candidate whose max tuple (3000/m3) is strictly
        // less than remote (5000/m_remote). The candidate IS strictly
        // greater than prior-localApplied (null) so monotonic check passes.
        val s1 = skeleton("m1", 1000L)
        val s2 = skeleton("m2", 2000L)
        val s3 = skeleton("m3", 3000L)
        server.enqueue(jsonResponse(json.encodeToString(listOf(s1, s2, s3))))

        val result = repo.getMessagesPaged("sess-1")
        assertTrue("drain succeeds (got ${result.exceptionOrNull()})", result.isSuccess)

        val post = repo.getSlimSessionState("sess-1")!!
        assertEquals(3000L, post.localAppliedUpdatedAt)
        assertEquals("m3", post.localAppliedMessageId)
        // P0-4: dirty MUST be re-set to true post-commit because
        // remote (5000/m_remote) > localApplied (3000/m3).
        assertTrue(
            "P0-4: dirty MUST be true post-commit when remote > localApplied (got dirty=${post.dirty})",
            post.dirty,
        )
    }

    // ── §11.1 fix-9 P0-5: reconfigure clears authoritative/visible stores ─

    /**
     * §11.1 fix-9 P0-5: a host switch (configure → beginSlimReconfigure)
     * MUST clear the authoritative + visible message stores so a cross-
     * host same-sessionId collision does NOT leak the old host's messages
     * into the new host. captureAuthoritativeMessages must return empty
     * after reconfigure.
     */
    @Test
    fun `§11_1 fix-9 P0-5 reconfigure clears authoritative and visible stores`() = runBlocking {
        val repo = makeRepository(slim = true)
        val s1 = skeleton("m1", 1000L)
        val s2 = skeleton("m2", 2000L)
        // Drain + commit terminal page to populate authoritative store.
        server.enqueue(jsonResponse(json.encodeToString(listOf(s1, s2))))
        val initialResult = repo.getMessagesPaged("sess-1")
        assertTrue("initial drain succeeds", initialResult.isSuccess)
        // The authoritative store now has m1 + m2.
        val beforeReconfigure = repo.captureAuthoritativeMessages("sess-1")
        assertEquals(
            "authoritative store populated by initial commit",
            listOf("m1", "m2"),
            beforeReconfigure.map { it.info.id },
        )

        // Trigger reconfigure (simulates a host switch).
        repo.beginSlimReconfigure()

        // P0-5: authoritative store cleared.
        val afterReconfigure = repo.captureAuthoritativeMessages("sess-1")
        assertTrue(
            "P0-5: authoritative store MUST be empty after reconfigure (got ${afterReconfigure.map { it.info.id }})",
            afterReconfigure.isEmpty(),
        )
    }

    // ── rev-ogpt P1-1 / P1-2: commit's atomic hasConflict dirty decision ──

    /**
     * rev-ogpt P1-1 + P1-2: a candidate with hasConflict=true committed via
     * the REAL repository + real [SlimSseStateMachine] keeps dirty=true
     * ATOMICALLY (no separate forceSlimDirty post-write critical section).
     *
     * Setup: seed the session with a digest that aligns remote == localApplied
     * (so needsReconcile=false post-commit — the only thing keeping dirty=true
     * is the hasConflict flag, threaded atomically through the commit's
     * critical section). Without the fix, the reconciler path's `markDirty`
     * was a NO-OP here (needsReconcile gates it to false on aligned watermark).
     */
    @Test
    fun `rev-ogpt P1-1 commit with hasConflict true keeps dirty true atomically`() = runBlocking {
        val sid = "sess-conflict"
        val token = repository.captureSlimCommitToken()

        // Seed: remote == localApplied == 200 ⇒ needsReconcile=false post-commit.
        // (Remote via a digest, localApplied via a prior commit.)
        val seedDigest = SlimSessionDigest(
            sessionId = sid,
            updatedAt = 200L,
            messageId = "m-seed",
        )
        repository.applySlimDigest(seedDigest, token)
        // First commit (no conflict) seeds localApplied=200.
        val seedCandidate = SlimAuthoritativeCandidate(
            sessionId = sid,
            token = token,
            messages = listOf(skeleton("m-seed", 200L)),
            localAppliedUpdatedAt = 200L,
            localAppliedMessageId = "m-seed",
            hasConflict = false,
        )
        val seedResult = repository.commitAuthoritative(seedCandidate)
        assertEquals("seed commit must succeed", SlimAuthoritativeCommitResult.Committed, seedResult)

        // After seed: remote=200, localApplied=200, dirty=false (aligned).
        val stateAfterSeed = repository.getSlimSessionState(sid)!!
        assertEquals("seed: localApplied advanced", 200L, stateAfterSeed.localAppliedUpdatedAt)
        assertFalse(
            "seed: dirty cleared (remote == localApplied, no conflict)",
            stateAfterSeed.dirty,
        )

        // Now commit a CONFLICTING candidate (same tuple, hasConflict=true).
        // The watermark is already aligned (200), so needsReconcile=false.
        // The ONLY thing keeping dirty=true post-commit is hasConflict.
        val conflictCandidate = SlimAuthoritativeCandidate(
            sessionId = sid,
            token = token,
            messages = listOf(skeleton("m-seed", 200L)),
            localAppliedUpdatedAt = 200L,
            localAppliedMessageId = "m-seed",
            hasConflict = true,
        )
        val conflictResult = repository.commitAuthoritative(conflictCandidate)
        assertEquals(
            "hasConflict=true commit must still succeed",
            SlimAuthoritativeCommitResult.Committed,
            conflictResult,
        )

        val stateAfterConflict = repository.getSlimSessionState(sid)!!
        assertTrue(
            "P1-1: hasConflict=true MUST keep dirty=true ATOMICALLY after commit " +
                "(state=$stateAfterConflict)",
            stateAfterConflict.dirty,
        )
        assertEquals(
            "P1-2: localApplied watermark is unchanged (idempotent tuple)",
            200L,
            stateAfterConflict.localAppliedUpdatedAt,
        )
    }
}
/**
 * Task 3 helper — extracts the `?ids=…` query from a MockWebServer
 * RecordedRequest path and URL-decodes it back to a List<String>.
 *
 * Retrofit URL-encodes the comma separator inside a single `@Query`
 * String value (`?ids=m1,m2` → `?ids=m1%2Cm2`), so naive
 * `substringAfter("ids=").split(",")` would return a single
 * `%2C`-joined element. This helper decodes the value first so the
 * test asserts on the logical id list (matching what the sidecar sees
 * after it URL-decodes the query).
 */
private fun String.decodedIdsQuery(): List<String> {
    val raw = substringAfter("ids=").substringBefore("&")
    return java.net.URLDecoder.decode(raw, "UTF-8").split(",")
}
