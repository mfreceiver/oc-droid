package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.util.DebugLog
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 🆕 v0.9.0 — covers the 503 `transform_busy` Retry-After backoff path on
 * [SlimSyncEngine.getSlimapiSessionsResult] (the private sessions helper
 * exercised by [OpenCodeRepository.coldStartSlimSync]).
 *
 * This is a SEPARATE code path from the top-level
 * [getSlimapiSessionsDelegate] (covered by
 * `OpenCodeRepositorySlimapiEndpointsTest`'s `getSlimapiSessions 503 *`
 * cases). Both mirror [ExpandBatchEngine]'s 503 paradigm (≤3 attempts,
 * Retry-After header honored with exponential-backoff fall-back, only
 * `503 + transform_busy` retries); these tests pin the cold-start
 * semantics: on exhausted retries the sessions piece degrades to `null`
 * (caller preserves prior state) while questions/permissions still resolve.
 */
class SlimSyncEngineSessionsBackoffTest {

    private val server = MockWebServer()
    private lateinit var repository: OpenCodeRepository

    private fun jsonResponse(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code).setBody(body)
            .setHeader("Content-Type", "application/json")

    /** 503 transform_busy envelope (no Retry-After → exponential backoff used). */
    private fun transformBusy503(): MockResponse =
        MockResponse().setResponseCode(503)
            .setBody("""{"code":"transform_busy"}""")
            .setHeader("Content-Type", "application/json")

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

    @Test
    fun `coldStartSlimSync sessions 503 transform_busy retries then succeeds`() = runBlocking {
        // Sessions endpoint: first 503 transform_busy → retry → 200 OK with one row.
        // coldStartSlimSync issues sessions → questions → permissions sequentially,
        // so the MockWebServer FIFO queue sees them in that order.
        server.enqueue(transformBusy503())                          // sessions attempt 1
        server.enqueue(jsonResponse("""[{"id":"s1","directory":"/default","status":"idle"}]"""))  // sessions retry
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))  // questions
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))  // permissions

        val result = repository.coldStartSlimSync(token = token())

        assertTrue("cold-start succeeds after sessions retry: $result", result.isSuccess)
        val snapshot = result.getOrThrow()
        // Sessions piece populated from the retried 200.
        assertNull("no openSessionId → messages null", snapshot.messages)
        assertEquals("sessions row recovered after retry: ${snapshot.sessions}", 1, snapshot.sessions?.size)
        assertEquals("s1", snapshot.sessions!![0].id)
        // Exactly 2 sessions HTTP attempts (1 × 503 + 1 × 200).
        assertEquals("2 sessions attempts (503 then 200)", 2, countSessionsRequests())
    }

    @Test
    fun `coldStartSlimSync sessions 503 transform_busy exhausted degrades sessions piece to null`() = runBlocking {
        // Three consecutive 503 transform_busy → sessions backoff exhausted.
        // coldStartSlimSync's sessions piece degrades to null (T11 oracle D2
        // 3-state contract: "null on failure; emptyList on success" — see
        // SlimSyncEngine.getSlimapiSessionsResult, which surfaces failure as
        // Result.failure, and coldStartSlimSync folds that via `.getOrNull()`
        // into a null SlimSessionsPage piece).
        //
        // "Preserve prior sessions" is the CALLER's contract
        // (SessionSyncCoordinator.applySlimColdStartSnapshot folds a null
        // sessions piece as "keep prior"), NOT coldStartSlimSync's — so it is
        // not asserted at this layer. This test pins the cold-start-level
        // guarantee: the sessions piece is null (the 3-state degrade signal,
        // NOT an empty list) while questions and permissions still resolve
        // (per-piece degradation → overall Result.success).
        repeat(3) { server.enqueue(transformBusy503()) }            // sessions attempts 1..3
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))  // questions
        server.enqueue(jsonResponse("""{"items":[],"errors":[]}"""))  // permissions

        val result = repository.coldStartSlimSync(token = token())

        assertTrue(
            "per-piece degradation yields overall success (sessions nulled, q+p ok): $result",
            result.isSuccess,
        )
        val snapshot = result.getOrThrow()
        // T11 round-2 (oracle D2): HTTP-failed sessions piece → null (NOT empty
        // list). null is the 3-state signal the caller folds as "keep prior".
        assertNull(
            "sessions 503 exhausted → null piece (degrade, NOT empty list): ${snapshot.sessions}",
            snapshot.sessions,
        )
        assertTrue(
            "questions still resolve on sessions 503: ${snapshot.questions}",
            snapshot.questions is SlimAggregationOutcome.Success,
        )
        assertTrue(
            "permissions still resolve on sessions 503: ${snapshot.permissions}",
            snapshot.permissions is SlimAggregationOutcome.Success,
        )
        assertEquals("3 sessions attempts before giving up", 3, countSessionsRequests())
        // transform_busy observably logged at WARN at least once (final attempt).
        val matches = DebugLog.entries.value.filter {
            it.level == cn.vectory.ocdroid.util.DebugLog.Level.WARN &&
                it.message.contains("transform_busy")
        }
        assertTrue(
            "transform_busy logged at WARN on exhausted sessions: ${DebugLog.entries.value.map { "${it.level}:${it.message}" }}",
            matches.isNotEmpty(),
        )
    }

    /** Drain all recorded requests and count those hitting `/slimapi/sessions`. */
    private fun countSessionsRequests(): Int {
        val total = server.requestCount
        var n = 0
        repeat(total) {
            val req = server.takeRequest()
            if (req.path?.startsWith("/slimapi/sessions") == true) n++
        }
        return n
    }
}
