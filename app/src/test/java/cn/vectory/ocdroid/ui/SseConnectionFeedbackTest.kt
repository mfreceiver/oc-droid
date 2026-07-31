package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.ui.chat.resolveDisconnectDurationLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §sse-feedback-ux (P2-1): pure unit coverage for the SSE-disconnect feedback
 * derivation. All subjects are pure (no store / no clock side-effect), so the
 * tests drive them directly with a controlled clock. This locks:
 *  - [deriveSseConnectionFeedback] is exhaustive over every [ConnectionPhase]
 *    variant (no `else` — a new phase that forgets to map would compile-fail,
 *    and these tests pin the mapping).
 *  - [SseConnectionFeedback.showBanner] fires ONLY on a sustained terminal
 *    disconnect / debug REST-only mode — never on the transient / healthy /
 *    TOFU-pending variants (so the banner never cries wolf).
 *  - [disconnectDurationMs] derives from the stamped transition time, coerces
 *    clock skew to ≥ 0, and returns null off the happy path.
 *  - [resolveDisconnectDurationLabel] buckets elapsed time into just-now /
 *    minutes / hours at the documented boundaries.
 */
class SseConnectionFeedbackTest {

    @Test
    fun `derive is exhaustive and maps every ConnectionPhase variant`() {
        // Live: Connected + SSE delivering.
        assertEquals(
            SseConnectionFeedback.Live,
            deriveSseConnectionFeedback(ConnectionPhase.Connected, null, sseConnected = true, now = 1_000L),
        )
        // Stall: Connected but SSE not yet delivering.
        assertEquals(
            SseConnectionFeedback.WaitingForStream,
            deriveSseConnectionFeedback(ConnectionPhase.Connected, null, sseConnected = false, now = 1_000L),
        )
        assertEquals(
            SseConnectionFeedback.Idle,
            deriveSseConnectionFeedback(ConnectionPhase.Idle, null, sseConnected = false, now = 1_000L),
        )
        assertEquals(
            SseConnectionFeedback.Connecting,
            deriveSseConnectionFeedback(ConnectionPhase.Connecting, null, sseConnected = false, now = 1_000L),
        )
        assertEquals(
            SseConnectionFeedback.Reconnecting(null, null),
            deriveSseConnectionFeedback(ConnectionPhase.Reconnecting, null, sseConnected = false, now = 1_000L),
        )
        assertEquals(
            SseConnectionFeedback.Reconnecting(2, 5),
            deriveSseConnectionFeedback(ConnectionPhase.ReconnectingAttempt(2, 5), null, sseConnected = false, now = 1_000L),
        )
        assertEquals(
            SseConnectionFeedback.AwaitingTofuTrust,
            deriveSseConnectionFeedback(ConnectionPhase.AwaitingTofuTrust, null, sseConnected = false, now = 1_000L),
        )
        assertEquals(
            SseConnectionFeedback.Disabled,
            deriveSseConnectionFeedback(ConnectionPhase.SseDisabled, null, sseConnected = false, now = 1_000L),
        )
    }

    @Test
    fun `Disconnected carries the stamped transition time`() {
        assertEquals(
            SseConnectionFeedback.Disconnected(sinceMs = 1_000L, now = 5_000L),
            deriveSseConnectionFeedback(ConnectionPhase.Disconnected, disconnectedSince = 1_000L, sseConnected = false, now = 5_000L),
        )
    }

    @Test
    fun `Disconnected without a stamp falls back to now so the label is just-now, not a crash`() {
        // A test / race that sets Disconnected without a timestamp must not NPE
        // on null arithmetic; the derivation falls back to `now`.
        assertEquals(
            SseConnectionFeedback.Disconnected(sinceMs = 5_000L, now = 5_000L),
            deriveSseConnectionFeedback(ConnectionPhase.Disconnected, disconnectedSince = null, sseConnected = false, now = 5_000L),
        )
    }

    @Test
    fun `showBanner is true only for Disconnected and Disabled`() {
        assertTrue(SseConnectionFeedback.Disconnected(1_000L, 5_000L).showBanner)
        assertTrue(SseConnectionFeedback.Disabled.showBanner)

        assertFalse(SseConnectionFeedback.Live.showBanner)
        assertFalse(SseConnectionFeedback.WaitingForStream.showBanner)
        assertFalse(SseConnectionFeedback.Connecting.showBanner)
        assertFalse(SseConnectionFeedback.Reconnecting(null, null).showBanner)
        assertFalse(SseConnectionFeedback.Reconnecting(1, 3).showBanner)
        assertFalse(SseConnectionFeedback.AwaitingTofuTrust.showBanner)
        assertFalse(SseConnectionFeedback.Idle.showBanner)
    }

    @Test
    fun `disconnectDurationMs derives from the stamped transition time`() {
        assertEquals(
            4_000L,
            SseConnectionFeedback.Disconnected(sinceMs = 1_000L, now = 5_000L).disconnectDurationMs(),
        )
        // Exactly at the stamp → zero (not negative).
        assertEquals(
            0L,
            SseConnectionFeedback.Disconnected(sinceMs = 5_000L, now = 5_000L).disconnectDurationMs(),
        )
    }

    @Test
    fun `disconnectDurationMs coerces clock skew to zero`() {
        // A monotonic-clock skew (now observed BEFORE the stamp) must never
        // render a negative elapsed time.
        assertEquals(
            0L,
            SseConnectionFeedback.Disconnected(sinceMs = 5_000L, now = 1_000L).disconnectDurationMs(),
        )
    }

    @Test
    fun `disconnectDurationMs is null off the disconnected path`() {
        assertNull(SseConnectionFeedback.Live.disconnectDurationMs())
        assertNull(SseConnectionFeedback.Idle.disconnectDurationMs())
        assertNull(SseConnectionFeedback.Disabled.disconnectDurationMs())
        assertNull(SseConnectionFeedback.Reconnecting(1, 3).disconnectDurationMs())
    }

    @Test
    fun `resolveDisconnectDurationLabel buckets at the documented boundaries`() {
        val justNow = "just now"
        val minutes = "%d min ago"
        val hours = "%d h ago"

        // < 1 min → just now (inclusive of the boundary - 1ms).
        assertEquals(justNow, resolveDisconnectDurationLabel(0L, justNow, minutes, hours))
        assertEquals(justNow, resolveDisconnectDurationLabel(59_999L, justNow, minutes, hours))
        // 1 min … 59 min → minutes.
        assertEquals("1 min ago", resolveDisconnectDurationLabel(60_000L, justNow, minutes, hours))
        assertEquals("59 min ago", resolveDisconnectDurationLabel(3_599_999L, justNow, minutes, hours))
        // ≥ 1 h → hours.
        assertEquals("1 h ago", resolveDisconnectDurationLabel(3_600_000L, justNow, minutes, hours))
        assertEquals("2 h ago", resolveDisconnectDurationLabel(7_200_000L, justNow, minutes, hours))
    }

    @Test
    fun `SSE_FEEDBACK_TICK_MS is the documented coarse cadence`() {
        // Pin the constant so a future tuning does not silently change the
        // banner refresh rate (and the ticker cost) without a test update.
        assertEquals(30_000L, SSE_FEEDBACK_TICK_MS)
    }
}
