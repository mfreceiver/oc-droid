package cn.vectory.ocdroid.ui

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §fix-error-storm P1-1 + P1-2: unit tests for [ErrorEventGate] using a
 * fake clock to control the dedup window deterministically.
 *
 * All tests use a default dedupWindowMs of 2000L (same as the production
 * default) unless overridden for a specific scenario.
 *
 * DebugLog.w forwards to android.util.Log.w; the JVM test harness has no
 * android.util.Log, so the static is mocked in setUp (mirroring the
 * [SharedEffectBusTest] pattern).
 */
class ErrorEventGateTest {

    /** Fake monotonic clock — advance manually via [advance]. */
    private var now: Long = 0L
    private val clock: () -> Long = { now }

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── non-error events ──────────────────────────────────────────────────

    @Test
    fun `non-error events always accepted`() {
        val gate = ErrorEventGate(clock = clock)

        assertTrue("Info accepted", gate.accept(UiEvent.Info(1)))
        assertTrue("Success accepted", gate.accept(UiEvent.Success(1)))
        assertTrue("Debug accepted", gate.accept(UiEvent.Debug("test")))

        assertEquals("suppressedCancellationCount remains 0", 0L, gate.suppressedCancellationCount())
        assertEquals("dedupedErrorCount remains 0", 0L, gate.dedupedErrorCount())
    }

    // ── P1-1: cancellation leakage ────────────────────────────────────────

    @Test
    fun `cancellation stem suppressed and counted`() {
        val gate = ErrorEventGate(clock = clock)

        assertFalse(
            "Job was cancelled → suppressed",
            gate.accept(UiEvent.Error(1, listOf("Job was cancelled")))
        )
        assertEquals(1L, gate.suppressedCancellationCount())
        assertEquals("dedup stays 0 for cancellation", 0L, gate.dedupedErrorCount())
    }

    @Test
    fun `cancellation stem variants suppressed`() {
        val gate = ErrorEventGate(clock = clock)

        assertFalse("canceled → suppressed", gate.accept(UiEvent.Error(1, listOf("canceled"))))
        assertFalse("Cancelled → suppressed", gate.accept(UiEvent.Error(1, listOf("Cancelled"))))
        assertFalse("Cancellation → suppressed", gate.accept(UiEvent.Error(1, listOf("Cancellation requested"))))

        assertEquals(3L, gate.suppressedCancellationCount())
        assertEquals("dedup stays 0 for cancellations", 0L, gate.dedupedErrorCount())
    }

    @Test
    fun `normal error accepted`() {
        val gate = ErrorEventGate(clock = clock)

        assertTrue("network error → accepted", gate.accept(UiEvent.Error(1, listOf("network error"))))

        assertEquals(0L, gate.suppressedCancellationCount())
        assertEquals(0L, gate.dedupedErrorCount())
    }

    // ── P1-2: duplicate-error dedup ───────────────────────────────────────

    @Test
    fun `duplicate within window suppressed`() {
        val gate = ErrorEventGate(clock = clock)

        assertTrue("first identical error accepted", gate.accept(UiEvent.Error(1, listOf("boom"))))
        assertEquals(0L, gate.dedupedErrorCount())

        assertFalse("second identical error within window suppressed", gate.accept(UiEvent.Error(1, listOf("boom"))))
        assertEquals(1L, gate.dedupedErrorCount())

        // third identical within window — still suppressed
        assertFalse("third identical error within window suppressed", gate.accept(UiEvent.Error(1, listOf("boom"))))
        assertEquals(2L, gate.dedupedErrorCount())
    }

    @Test
    fun `different args not deduped`() {
        val gate = ErrorEventGate(clock = clock)

        assertTrue("Error(1, a) accepted", gate.accept(UiEvent.Error(1, listOf("a"))))
        assertTrue("Error(1, b) at same time accepted", gate.accept(UiEvent.Error(1, listOf("b"))))

        assertEquals(0L, gate.dedupedErrorCount())
    }

    @Test
    fun `different resId not deduped`() {
        val gate = ErrorEventGate(clock = clock)

        assertTrue("Error(1, a) accepted", gate.accept(UiEvent.Error(1, listOf("a"))))
        assertTrue("Error(2, a) at same time accepted", gate.accept(UiEvent.Error(2, listOf("a"))))

        assertEquals(0L, gate.dedupedErrorCount())
    }

    @Test
    fun `same key after window expires accepted`() {
        val gate = ErrorEventGate(dedupWindowMs = 2000L, clock = clock)

        assertTrue("first Error(1, a) accepted at now=0", gate.accept(UiEvent.Error(1, listOf("a"))))

        now = 2001L  // just past the window
        assertTrue("same Error(1, a) after window expiry accepted", gate.accept(UiEvent.Error(1, listOf("a"))))

        assertEquals(0L, gate.dedupedErrorCount())
    }

    @Test
    fun `same key exactly at window boundary suppressed`() {
        val gate = ErrorEventGate(dedupWindowMs = 2000L, clock = clock)

        assertTrue("first Error(1, a) accepted at now=0", gate.accept(UiEvent.Error(1, listOf("a"))))

        now = 2000L  // exactly at boundary — still within window (≤)
        assertFalse("same Error(1, a) at boundary suppressed", gate.accept(UiEvent.Error(1, listOf("a"))))

        assertEquals(1L, gate.dedupedErrorCount())
    }

    // ── P1-1 + P1-2 interaction ──────────────────────────────────────────

    @Test
    fun `cancellation does not count as dedup`() {
        val gate = ErrorEventGate(clock = clock)

        assertFalse("first cancelled error suppressed", gate.accept(UiEvent.Error(1, listOf("cancelled"))))
        assertFalse("second cancelled error suppressed by cancellation, not dedup", gate.accept(UiEvent.Error(1, listOf("cancelled"))))

        assertEquals(
            "each cancellation counted independently",
            2L, gate.suppressedCancellationCount()
        )
        // dedup is only incremented when isDuplicateWithinWindow returns true,
        // which never happens because looksLikeCancellation short-circuits first
        assertEquals("dedup stays 0 — cancellations never reach dedup logic", 0L, gate.dedupedErrorCount())
    }

    // ── args type handling ────────────────────────────────────────────────

    @Test
    fun `args of non-string type stringified`() {
        val gate = ErrorEventGate(clock = clock)

        // Int 42 stringifies to "42" — no "cancel" stem, so accepted.
        assertTrue("Error with Int arg accepted", gate.accept(UiEvent.Error(1, listOf(42))))

        assertEquals(0L, gate.suppressedCancellationCount())
        assertEquals(0L, gate.dedupedErrorCount())
    }
}
