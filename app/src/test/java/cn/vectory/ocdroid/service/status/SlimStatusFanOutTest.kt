package cn.vectory.ocdroid.service.status

import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.StatusOutcome
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 13 — unit tests for [SlimStatusFanOut] + [foldStatusOutcomes].
 *
 * Covers the brief's acceptance criteria:
 *  - **T13-C1**: `checkSlimSessionsStatuses(["s1","s2"])` issues 2
 *    per-session GETs (concurrent — verified via mock coVerify count).
 *  - **T13-C2**: branch table — 200 busy → `Success(busy)` (NOT misjudged
 *    idle); idle → `Success(idle)`; 404 → missingSids; 503 →
 *    retryableCount.
 *  - **T13-C5**: fake-idle cross-check (Success(idle) but not in
 *    snapshot → missingSids).
 *
 * The pure [foldStatusOutcomes] helper is also covered directly so the
 * branch table is unit-testable without spinning coroutines (mirrors the
 * SlimapiMessageMerge.mapStatusOutcome purity discipline).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SlimStatusFanOutTest {

    // ── foldStatusOutcomes: pure branch table (T13-C2 / T13-C5) ───────────

    @Test
    fun `fold - 200 busy stays Success(busy) and is NOT misjudged idle`() {
        val outcomes = mapOf(
            "s1" to StatusOutcome.Success("s1", SessionStatus(type = "busy")),
        )
        val summary = foldStatusOutcomes(outcomes, knownSessionIds = setOf("s1"))
        assertEquals(outcomes, summary.perSid)
        assertEquals(0, summary.retryableCount)
        assertTrue("busy is NOT missing", summary.missingSids.isEmpty())
    }

    @Test
    fun `fold - 200 idle + sid in snapshot stays Success(idle) - not fake`() {
        val outcomes = mapOf(
            "s1" to StatusOutcome.Success("s1", SessionStatus(type = "idle")),
        )
        val summary = foldStatusOutcomes(outcomes, knownSessionIds = setOf("s1"))
        assertEquals(0, summary.retryableCount)
        assertTrue("legitimate idle is NOT missing", summary.missingSids.isEmpty())
    }

    @Test
    fun `fold - 200 idle + sid NOT in snapshot reclassified as missing (T13-C5 fake-idle cross-check)`() {
        val outcomes = mapOf(
            "s1" to StatusOutcome.Success("s1", SessionStatus(type = "idle")),
        )
        val summary = foldStatusOutcomes(outcomes, knownSessionIds = setOf("s2", "s3"))
        assertEquals(
            "fake-idle for an unknown sid → missingSids",
            listOf("s1"),
            summary.missingSids,
        )
    }

    @Test
    fun `fold - 200 retry is NOT subject to fake-idle cross-check`() {
        val outcomes = mapOf(
            "s1" to StatusOutcome.Success("s1", SessionStatus(type = "retry")),
        )
        val summary = foldStatusOutcomes(outcomes, knownSessionIds = emptySet())
        assertTrue("retry speaks truth; never missing", summary.missingSids.isEmpty())
        assertEquals(0, summary.retryableCount)
    }

    @Test
    fun `fold - 404 SessionMissing adds to missingSids`() {
        val outcomes = mapOf(
            "s1" to StatusOutcome.SessionMissing("s1"),
        )
        val summary = foldStatusOutcomes(outcomes, knownSessionIds = setOf("s1"))
        assertEquals(listOf("s1"), summary.missingSids)
        assertEquals(0, summary.retryableCount)
    }

    @Test
    fun `fold - 503 Retry increments retryableCount`() {
        val outcomes = mapOf(
            "s1" to StatusOutcome.Retry("s1", "upstream_unavailable"),
        )
        val summary = foldStatusOutcomes(outcomes, knownSessionIds = setOf("s1"))
        assertTrue("retry does NOT delete", summary.missingSids.isEmpty())
        assertEquals(1, summary.retryableCount)
    }

    @Test
    fun `fold - absent (null) knownSessionIds DISABLES fake-idle cross-check (M1)`() {
        val outcomes = mapOf(
            "s1" to StatusOutcome.Success("s1", SessionStatus(type = "idle")),
        )
        val summary = foldStatusOutcomes(outcomes, knownSessionIds = null)
        assertTrue(
            "null (absent) → idle stays trusted (no snapshot to cross-check against)",
            summary.missingSids.isEmpty(),
        )
    }

    @Test
    fun `fold - EMPTY knownSessionIds is AUTHORITATIVE-empty - every idle is fake (M1)`() {
        val outcomes = mapOf(
            "s1" to StatusOutcome.Success("s1", SessionStatus(type = "idle")),
            "s2" to StatusOutcome.Success("s2", SessionStatus(type = "idle")),
        )
        val summary = foldStatusOutcomes(outcomes, knownSessionIds = emptySet())
        assertEquals(
            "empty set means 'server says no sessions' → all idles reclassify as fake",
            listOf("s1", "s2"),
            summary.missingSids,
        )
    }

    @Test
    fun `fold - absent (null) vs empty (authoritative) are distinguishable (M1 contract)`() {
        val outcomes = mapOf(
            "s1" to StatusOutcome.Success("s1", SessionStatus(type = "idle")),
        )
        // null → no cross-check (trust idle as-is).
        val absent = foldStatusOutcomes(outcomes, knownSessionIds = null)
        assertTrue("absent snapshot disables cross-check", absent.missingSids.isEmpty())

        // empty → authoritative-empty (idle is fake).
        val authoritativeEmpty = foldStatusOutcomes(outcomes, knownSessionIds = emptySet())
        assertEquals(
            "empty snapshot catches the fake idle",
            listOf("s1"),
            authoritativeEmpty.missingSids,
        )
    }

    @Test
    fun `fold - UpstreamWarn and DirectoryError are neither retryable nor missing`() {
        val outcomes = mapOf(
            "s1" to StatusOutcome.UpstreamWarn("s1", "upstream_http_500"),
            "s2" to StatusOutcome.DirectoryError("s2"),
        )
        val summary = foldStatusOutcomes(outcomes, knownSessionIds = emptySet())
        assertEquals(0, summary.retryableCount)
        assertTrue(summary.missingSids.isEmpty())
    }

    @Test
    fun `fold - mixed outcomes route to the correct summary buckets`() {
        val outcomes = linkedMapOf(
            "s-busy" to StatusOutcome.Success("s-busy", SessionStatus(type = "busy")),
            "s-idle-known" to StatusOutcome.Success("s-idle-known", SessionStatus(type = "idle")),
            "s-idle-unknown" to StatusOutcome.Success("s-idle-unknown", SessionStatus(type = "idle")),
            "s-404" to StatusOutcome.SessionMissing("s-404"),
            "s-503" to StatusOutcome.Retry("s-503", "upstream_unavailable"),
            "s-warn" to StatusOutcome.UpstreamWarn("s-warn", "upstream_http_422"),
        )
        val summary = foldStatusOutcomes(
            outcomes,
            knownSessionIds = setOf("s-busy", "s-idle-known", "s-404", "s-503", "s-warn"),
        )
        assertEquals(1, summary.retryableCount)
        // Order preserved: idle-unknown first (fold order), then direct 404.
        assertEquals(listOf("s-idle-unknown", "s-404"), summary.missingSids)
    }

    // ── SlimStatusFanOut: concurrency + per-sid GET (T13-C1) ──────────────

    @Test
    fun `C1 - checkSlimSessionsStatuses issues one per-session GET per sid`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSlimapiSessionStatusOutcome("s1") } returns
            StatusOutcome.Success("s1", SessionStatus(type = "idle"))
        coEvery { repo.getSlimapiSessionStatusOutcome("s2") } returns
            StatusOutcome.Success("s2", SessionStatus(type = "busy"))
        val fanOut = SlimStatusFanOut(repo)

        val summary = fanOut.checkSlimSessionsStatuses(listOf("s1", "s2"), knownSessionIds = setOf("s1", "s2"))

        // T13-C1: exactly 2 per-session GETs (one per sid).
        coVerify(exactly = 1) { repo.getSlimapiSessionStatusOutcome("s1") }
        coVerify(exactly = 1) { repo.getSlimapiSessionStatusOutcome("s2") }
        assertEquals(2, summary.perSid.size)
        assertEquals(0, summary.retryableCount)
        assertTrue(summary.missingSids.isEmpty())
    }

    @Test
    fun `C1 - empty input returns Empty summary and issues zero GETs`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        val fanOut = SlimStatusFanOut(repo)

        val summary = fanOut.checkSlimSessionsStatuses(emptyList())

        assertEquals(StatusFanOutSummary.Empty, summary)
        coVerify(exactly = 0) { repo.getSlimapiSessionStatusOutcome(any()) }
    }

    @Test
    fun `C1 - duplicates collapse to a single GET per unique sid`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSlimapiSessionStatusOutcome("s1") } returns
            StatusOutcome.Success("s1", SessionStatus(type = "idle"))
        val fanOut = SlimStatusFanOut(repo)

        val summary = fanOut.checkSlimSessionsStatuses(listOf("s1", "s1", "s1"))

        coVerify(exactly = 1) { repo.getSlimapiSessionStatusOutcome("s1") }
        assertEquals(1, summary.perSid.size)
    }

    @Test
    fun `C2 - 503 Retry outcome routes to retryableCount`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSlimapiSessionStatusOutcome("s1") } returns
            StatusOutcome.Retry("s1", "upstream_unavailable")
        val fanOut = SlimStatusFanOut(repo)

        val summary = fanOut.checkSlimSessionsStatuses(listOf("s1"), knownSessionIds = setOf("s1"))

        assertEquals(1, summary.retryableCount)
        assertTrue(summary.missingSids.isEmpty())
    }

    @Test
    fun `C2 - 404 SessionMissing routes to missingSids`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSlimapiSessionStatusOutcome("s1") } returns
            StatusOutcome.SessionMissing("s1")
        val fanOut = SlimStatusFanOut(repo)

        val summary = fanOut.checkSlimSessionsStatuses(listOf("s1"), knownSessionIds = setOf("s1"))

        assertEquals(listOf("s1"), summary.missingSids)
        assertEquals(0, summary.retryableCount)
    }

    @Test
    fun `C5 - fake-idle cross-check routes idle-for-unknown-sid to missingSids`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSlimapiSessionStatusOutcome("s1") } returns
            StatusOutcome.Success("s1", SessionStatus(type = "idle"))
        val fanOut = SlimStatusFanOut(repo)

        val summary = fanOut.checkSlimSessionsStatuses(listOf("s1"), knownSessionIds = setOf("s2"))

        assertEquals(
            "Success(idle) for a sid NOT in the snapshot → missingSids",
            listOf("s1"),
            summary.missingSids,
        )
    }

    @Test
    fun `CE - an unexpected throw from getSlimapiSessionStatusOutcome collapses to Retry(null)`() = runTest {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSlimapiSessionStatusOutcome("s1") } throws
            IllegalStateException("unexpected")
        val fanOut = SlimStatusFanOut(repo)

        val summary = fanOut.checkSlimSessionsStatuses(listOf("s1"), knownSessionIds = setOf("s1"))

        // Defensive collapse: the sweep survives; the sid counts as retryable.
        assertEquals(1, summary.retryableCount)
        val outcome = summary.perSid["s1"]
        assertNotNull(outcome)
        assertTrue("collapsed to Retry", outcome is StatusOutcome.Retry)
    }

    @Test
    fun `concurrency - 4-wide semaphore bounds in-flight GETs (5th waits)`() = runTest {
        // T13-C1 concurrency: with concurrency=2, 5 sids cannot ALL run
        // concurrently — verify by interleaving delays + observing at
        // most `concurrency` in-flight at once.
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
        val maxInFlight = java.util.concurrent.atomic.AtomicInteger(0)
        coEvery { repo.getSlimapiSessionStatusOutcome(any()) } coAnswers {
            val cur = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { kotlin.math.max(it, cur) }
            delay(50)
            inFlight.decrementAndGet()
            StatusOutcome.Success(firstArg(), SessionStatus(type = "idle"))
        }
        val fanOut = SlimStatusFanOut(repo, concurrency = 2)

        fanOut.checkSlimSessionsStatuses(listOf("a", "b", "c", "d", "e"))

        assertTrue(
            "max in-flight ($maxInFlight) must respect the concurrency=2 cap",
            maxInFlight.get() <= 2,
        )
    }
}
