package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.repository.http.SlimapiErrorCodes
import cn.vectory.ocdroid.util.DebugLog
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Budget-aware acceptance tests for the O-A expand-recovery core.
 *
 * All tests use [runBlocking] (real time) with MockWebServer IO — `runTest`'s virtual
 * time does NOT virtualize Retrofit's real socket IO, so mixing `runTest` + `withTimeout`
 * + real MockWebServer leads to spurious cancellations. Backoffs are 200/400ms, fine
 * under real time.
 *
 * Item JSON shape follows the contract used by [OpenCodeRepositorySlimapiEndpointsTest]:
 * `{"info":{"id":...,"role":"user"},"parts":[]}`. `info.time` is a `TimeInfo` OBJECT
 * (nullable) — never a bare int.
 */
class OpenCodeRepositoryExpandBudgetTest {

    private val server = MockWebServer()
    private lateinit var repository: OpenCodeRepository

    private fun jsonResponse(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code).setBody(body)
            .setHeader("Content-Type", "application/json")

    /** Envelope item with the correct MessageWithParts shape. */
    private fun item(id: String, role: String = "user"): String =
        """{"info":{"id":"$id","role":"$role"},"parts":[]}"""

    /** Bare single-message shape returned by `/full/{mid}` (fallback path). */
    private fun singleMessage(id: String): String = item(id)

    @Before
    fun setup() = runBlocking {
        DebugLog.clear()
        server.start()
        repository = OpenCodeRepository(mockk(relaxed = true), mockk(relaxed = true))
        repository.configure(baseUrl = server.url("/").toString().trimEnd('/'), slim = true)
    }

    @After
    fun teardown() {
        // Defensive seam reset (repo is recreated per test, but be explicit).
        try { repository.expandWallClockBudgetMsForTest = null } catch (_: Throwable) {}
        try { repository.thinRouteTtlMsForTest = null } catch (_: Throwable) {}
        server.shutdown()
    }

    // ── #1: peak partition concurrency ≤ 2 ────────────────────────────────
    @Test
    fun `peak partition concurrency ≤ 2`() = runBlocking {
        // N=4 → 7 partition nodes: root + 2 halves + 4 singles. All 413 response_too_large.
        repeat(7) { server.enqueue(jsonResponse("""{"code":"response_too_large"}""", 413)) }
        val outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1", "m2", "m3", "m4"))
        assertTrue("Ok outcome (merged singleton-413 failures) — got $outcome", outcome is ExpandOutcome.Ok)
        val counters = repository.lastExpandBudgetCounters!!
        assertTrue("peakConcurrent <= 2", counters.peakConcurrentPartitionRequests <= 2)
        assertTrue("peakConcurrent >= 1", counters.peakConcurrentPartitionRequests >= 1)
        assertEquals("partitionNodesCreated == 2N-1 (7)", 7, counters.partitionNodesCreated)
    }

    // ── #2: partition nodes ≤ 2N−1 (single server, no restart) ────────────
    @Test
    fun `partition nodes ≤ 2N−1`() = runBlocking {
        // N=4 → 7 nodes
        repeat(7) { server.enqueue(jsonResponse("""{"code":"response_too_large"}""", 413)) }
        repository.expandMessagesFullBatch("sess-1", listOf("m1", "m2", "m3", "m4"))
        assertEquals("N=4 nodes == 2N-1", 7, repository.lastExpandBudgetCounters!!.partitionNodesCreated)

        // N=8 → 15 nodes. Same server — enqueue more (requestCount is not asserted here).
        repeat(15) { server.enqueue(jsonResponse("""{"code":"response_too_large"}""", 413)) }
        repository.expandMessagesFullBatch("sess-1", (1..8).map { "m$it" })
        assertEquals("N=8 nodes == 2N-1", 15, repository.lastExpandBudgetCounters!!.partitionNodesCreated)
    }

    // ── #3: wall-clock ≤ 30s (withTimeout truncates during a long delay) ───
    @Test
    fun `wall-clock ≤ 30s`() = runBlocking {
        repository.expandWallClockBudgetMsForTest = 300L  // tiny budget
        // 503 with Retry-After: 5 (5000ms). The 300ms withTimeout fires during the delay.
        server.enqueue(jsonResponse("""{}""", 503).setHeader("Retry-After", "5"))
        val outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1"))
        assertTrue("Failed (timeout → exhausted)", outcome is ExpandOutcome.Failed)
        assertTrue("exhausted marker", (outcome as ExpandOutcome.Failed).exhausted)
        assertTrue("wallClockMs bounded (< 2000)", repository.lastExpandBudgetCounters!!.wallClockMs < 2_000L)
    }

    // ── #4: per-node HTTP attempts ≤ 3 (2×503 then success → exactly 3) ────
    @Test
    fun `per-node HTTP attempts ≤ 3`() = runBlocking {
        repeat(2) { server.enqueue(jsonResponse("""{}""", 503)) }
        server.enqueue(jsonResponse("""{"items":[${item("m1")}],"errors":[]}"""))
        val outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1"))
        assertTrue("Ok outcome — got $outcome", outcome is ExpandOutcome.Ok)
        val ok = outcome as ExpandOutcome.Ok
        assertTrue("m1 loaded", ok.items.any { it.info.id == "m1" })
        assertEquals("requestCount == 3 (1 initial + 2 retries)", 3, server.requestCount)
        assertEquals("totalHttpAttempts == 3", 3, repository.lastExpandBudgetCounters!!.totalHttpAttempts)
    }

    // ── #5: B2 response matrix — all 6 rows (single server, cumulative count) ─
    @Test
    fun `B2 response matrix all 6 rows`() = runBlocking {
        // (a) success envelope
        server.enqueue(jsonResponse("""{"items":[${item("m1")},${item("m2")}],"errors":[]}"""))
        var outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1", "m2"))
        assertTrue("(a) Ok", outcome is ExpandOutcome.Ok)
        var ok = outcome as ExpandOutcome.Ok
        assertEquals("(a) 2 items", 2, ok.items.size)
        assertTrue("(a) no failures", ok.failures.isEmpty())
        var rc = server.requestCount

        // (b) partial (success + network-failure mid) → retry only failed mid
        server.enqueue(jsonResponse("""{"items":[${item("m1")}],"errors":[{"messageID":"m2","code":"upstream_http_503"}]}"""))
        server.enqueue(jsonResponse("""{"items":[${item("m2")}],"errors":[]}"""))
        outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1", "m2"))
        assertTrue("(b) Ok — got $outcome", outcome is ExpandOutcome.Ok)
        ok = outcome as ExpandOutcome.Ok
        assertTrue("(b) m1 in items", ok.items.any { it.info.id == "m1" })
        assertTrue("(b) m2 in items (retried)", ok.items.any { it.info.id == "m2" })
        assertTrue("(b) failures empty", ok.failures.isEmpty())
        assertEquals("(b) exactly 2 requests", rc + 2, server.requestCount)
        rc = server.requestCount

        // (c) all message_not_found → terminal, NO retry (1 request)
        server.enqueue(jsonResponse("""{"items":[],"errors":[{"messageID":"m1","code":"message_not_found"},{"messageID":"m2","code":"message_not_found"}]}"""))
        outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1", "m2"))
        assertTrue("(c) Ok", outcome is ExpandOutcome.Ok)
        ok = outcome as ExpandOutcome.Ok
        assertTrue("(c) items empty", ok.items.isEmpty())
        assertEquals("(c) 2 terminal failures", 2, ok.failures.size)
        assertEquals("(c) exactly 1 request (no retry)", rc + 1, server.requestCount)
        rc = server.requestCount

        // (d) all upstream_http_5xx then success → bounded retry
        server.enqueue(jsonResponse("""{"items":[],"errors":[{"messageID":"m1","code":"upstream_http_500"}]}"""))
        server.enqueue(jsonResponse("""{"items":[${item("m1")}],"errors":[]}"""))
        outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1"))
        assertTrue("(d) Ok — got $outcome", outcome is ExpandOutcome.Ok)
        ok = outcome as ExpandOutcome.Ok
        assertTrue("(d) m1 in items", ok.items.any { it.info.id == "m1" })
        assertEquals("(d) exactly 2 requests", rc + 2, server.requestCount)
        rc = server.requestCount

        // (e) message_not_found + upstream_http (no success) → not-found terminal, upstream retried
        server.enqueue(jsonResponse("""{"items":[],"errors":[{"messageID":"m1","code":"message_not_found"},{"messageID":"m2","code":"upstream_http_500"}]}"""))
        server.enqueue(jsonResponse("""{"items":[${item("m2")}],"errors":[]}"""))
        outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1", "m2"))
        assertTrue("(e) Ok — got $outcome", outcome is ExpandOutcome.Ok)
        ok = outcome as ExpandOutcome.Ok
        assertTrue("(e) m2 in items (retried)", ok.items.any { it.info.id == "m2" })
        assertEquals("(e) 1 terminal failure (m1)", 1, ok.failures.size)
        assertEquals("(e) failure is m1", "m1", ok.failures[0].messageId)
        assertEquals("(e) exactly 2 requests (m1 not retried)", rc + 2, server.requestCount)
        rc = server.requestCount

        // (f) all RequestError → top-level 503 → batch retry budget (3) then exhausted
        repeat(3) { server.enqueue(jsonResponse("""{"code":"upstream_unavailable"}""", 503).setHeader("Retry-After", "1")) }
        outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1"))
        assertTrue("(f) Failed (exhausted) — got $outcome", outcome is ExpandOutcome.Failed)
        assertTrue("(f) exhausted marker", (outcome as ExpandOutcome.Failed).exhausted)
        assertEquals("(f) exactly 3 requests", rc + 3, server.requestCount)
    }

    // ── #6: mid-retryable bounded retry ONLY those mids ───────────────────
    @Test
    fun `mid-retryable bounded retry ONLY those mids`() = runBlocking {
        // (1) m1/m2 success, m3 retryable, m4 terminal
        server.enqueue(jsonResponse("""{"items":[${item("m1")},${item("m2")}],"errors":[{"messageID":"m3","code":"upstream_http_503"},{"messageID":"m4","code":"message_not_found"}]}"""))
        // (2) retry m3 → success
        server.enqueue(jsonResponse("""{"items":[${item("m3")}],"errors":[]}"""))
        val outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1", "m2", "m3", "m4"))
        assertTrue("Ok — got $outcome", outcome is ExpandOutcome.Ok)
        val ok = outcome as ExpandOutcome.Ok
        assertTrue("m1 loaded", ok.items.any { it.info.id == "m1" })
        assertTrue("m2 loaded", ok.items.any { it.info.id == "m2" })
        assertTrue("m3 loaded (retried)", ok.items.any { it.info.id == "m3" })
        assertTrue("m4 terminal", ok.failures.any { it.messageId == "m4" })
        assertEquals("only 2 requests (m1/m2/m4 NOT re-requested)", 2, server.requestCount)
    }

    // ── #7: m8 thin-route cache — four scenarios (robust, no real-time TTL race) ─
    @Test
    fun `m8 four scenarios`() = runBlocking {
        // Generous TTL so (i)→(ii) stays cached reliably even on slow CI.
        repository.thinRouteTtlMsForTest = 60_000L

        // (i) fresh → batch probe (404 thin_route) + single fallback
        server.enqueue(jsonResponse("""{"code":"thin_route_not_found"}""", 404))
        server.enqueue(jsonResponse(singleMessage("m1")))
        var outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1"))
        assertTrue("(i) Ok — got $outcome", outcome is ExpandOutcome.Ok)
        var rc = server.requestCount
        assertEquals("(i) 2 requests (probe + fallback)", 2, rc)

        // (ii) within TTL → cache hit → NO batch probe, direct single fallback
        server.enqueue(jsonResponse(singleMessage("m2")))
        outcome = repository.expandMessagesFullBatch("sess-1", listOf("m2"))
        assertTrue("(ii) Ok — got $outcome", outcome is ExpandOutcome.Ok)
        assertEquals("(ii) 1 request (cache hit, no probe)", rc + 1, server.requestCount)
        rc = server.requestCount

        // (iii) after invalidate → cache cleared → re-probe
        repository.invalidateThinRouteCache()
        server.enqueue(jsonResponse("""{"code":"thin_route_not_found"}""", 404))
        server.enqueue(jsonResponse(singleMessage("m3")))
        outcome = repository.expandMessagesFullBatch("sess-1", listOf("m3"))
        assertTrue("(iii) Ok — got $outcome", outcome is ExpandOutcome.Ok)
        assertEquals("(iii) 2 requests (re-probe after invalidate)", rc + 2, server.requestCount)
        rc = server.requestCount

        // (iv) after TTL expiry → re-probe. Use a tiny TTL + short sleep to cross it.
        repository.thinRouteTtlMsForTest = 1L
        Thread.sleep(15) // reliably cross the 1ms TTL
        server.enqueue(jsonResponse("""{"code":"thin_route_not_found"}""", 404))
        server.enqueue(jsonResponse(singleMessage("m4")))
        outcome = repository.expandMessagesFullBatch("sess-1", listOf("m4"))
        assertTrue("(iv) Ok — got $outcome", outcome is ExpandOutcome.Ok)
        assertEquals("(iv) 2 requests (re-probe after TTL expiry)", rc + 2, server.requestCount)
    }

    // ── #8: concurrent expands complete without deadlock ──────────────────
    // NOTE: per-(host,sid,mid) single-flight is enforced on the fallback (single-full)
    // path via the singleFlightMap. The batch path is per-id-SET, so two concurrent
    // calls with DIFFERENT id-sets are genuinely distinct requests (rev-glm m2). This
    // test verifies concurrent batch calls don't deadlock/crash; the per-mid single-flight
    // invariant on the fallback path is exercised by the m8/fallback scenarios above.
    @Test
    fun `concurrent batch expands complete without deadlock`() = runBlocking {
        server.enqueue(jsonResponse("""{"items":[${item("m1")},${item("m2")}],"errors":[]}"""))
        server.enqueue(jsonResponse("""{"items":[${item("m1")},${item("m3")}],"errors":[]}"""))
        val a = async { repository.expandMessagesFullBatch("sess-1", listOf("m1", "m2")) }
        val b = async { repository.expandMessagesFullBatch("sess-1", listOf("m1", "m3")) }
        val results = awaitAll(a, b)
        assertTrue("a Ok", results[0] is ExpandOutcome.Ok)
        assertTrue("b Ok", results[1] is ExpandOutcome.Ok)
        assertEquals("two distinct batch requests", 2, server.requestCount)
    }

    // ── #9: unknown envelope code → fail-open exhausted ───────────────────
    @Test
    fun `unknown envelope code fails open to exhausted`() = runBlocking {
        server.enqueue(jsonResponse("""{"items":[],"errors":[{"messageID":"m1","code":"brand_new_code"}]}"""))
        val outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1"))
        assertTrue("Failed (exhausted)", outcome is ExpandOutcome.Failed)
        assertTrue("exhausted marker", (outcome as ExpandOutcome.Failed).exhausted)
    }

    // ── #10: N=1 retryable envelope (B3 in-place retry fix) ───────────────
    @Test
    fun `N=1 retryable envelope`() = runBlocking {
        // (1) single mid retryable; (2) retry → success
        server.enqueue(jsonResponse("""{"items":[],"errors":[{"messageID":"m1","code":"upstream_http_503"}]}"""))
        server.enqueue(jsonResponse("""{"items":[${item("m1")}],"errors":[]}"""))
        val outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1"))
        assertTrue("Ok — got $outcome", outcome is ExpandOutcome.Ok)
        val ok = outcome as ExpandOutcome.Ok
        assertTrue("m1 loaded", ok.items.any { it.info.id == "m1" })
        assertTrue("failures empty", ok.failures.isEmpty())
        assertEquals("exactly 2 requests", 2, server.requestCount)
    }

    // ── #11: Retry-After cap 10s (pure helper + integration) ──────────────
    @Test
    fun `Retry-After cap 10s`() = runBlocking {
        // Pure helper: cap at 10s
        assertEquals("600 capped to 10000", 10_000L, repository.retryAfterHeaderToMs("600"))
        assertEquals("2 is 2000", 2_000L, repository.retryAfterHeaderToMs("2"))
        assertEquals("null is 0", 0L, repository.retryAfterHeaderToMs(null))
        assertEquals("abc is 0", 0L, repository.retryAfterHeaderToMs("abc"))
        assertEquals("0 is 0", 0L, repository.retryAfterHeaderToMs("0"))
        // Integration: 503 with Retry-After:1 then success
        server.enqueue(jsonResponse("""{}""", 503).setHeader("Retry-After", "1"))
        server.enqueue(jsonResponse("""{"items":[${item("m1")}],"errors":[]}"""))
        val outcome = repository.expandMessagesFullBatch("sess-1", listOf("m1"))
        assertTrue("Ok — got $outcome", outcome is ExpandOutcome.Ok)
        assertEquals("exactly 2 requests", 2, server.requestCount)
    }

    // #12 (exhausted residual → UI keeps skeleton) lives in PartExpandStateTest.kt,
    // where the ExpandOutcome→PartExpandState mapping is unit-tested (Failed(exhausted=true)
    // → PartExpandState.Exhausted, skeleton preserved, NOT Loaded).
}
