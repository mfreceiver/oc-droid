package cn.vectory.ocdroid.ui.chat

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for [resolveSessionTabLayout] — the pure layout resolver
 * backing the session tab strip's expand-then-scroll behaviour. Extracted from
 * the [SessionTabStrip] composable so the mode decision is unit-testable
 * instead of only verifiable on-device.
 *
 * Contract: when tabs do not fit at their minimum width the strip must scroll;
 * when they fit but each would be at least max-width the strip clamps to max
 * and spaces tabs evenly; otherwise tabs expand to fill using equal weights.
 */
class SessionTabLayoutTest {

    private val minWidth = 96.dp
    private val maxWidth = 168.dp

    // ── overflow threshold ─────────────────────────────────────────────────

    @Test
    fun `overflow when min-width sum exceeds content width`() {
        // 4 tabs need 384dp; content is 383dp → one dp short → scroll.
        assertEquals(
            SessionTabLayout.OverflowScroll,
            resolveSessionTabLayout(383.dp, 4, minWidth, maxWidth)
        )
    }

    @Test
    fun `fit when min-width sum exactly equals content width`() {
        // 4 tabs at 96dp exactly fill 384dp → still fits, weighted.
        assertEquals(
            SessionTabLayout.FitWeighted,
            resolveSessionTabLayout(384.dp, 4, minWidth, maxWidth)
        )
    }

    @Test
    fun `fit weighted when content width is just above min-width sum`() {
        assertEquals(
            SessionTabLayout.FitWeighted,
            resolveSessionTabLayout(385.dp, 4, minWidth, maxWidth)
        )
    }

    // ── max-clamp boundary ─────────────────────────────────────────────────

    @Test
    fun `clamp to max when per-tab share equals max width`() {
        // 2 tabs share 336dp → 168dp each → clamped to max, spaced evenly.
        assertEquals(
            SessionTabLayout.FitClampedToMax,
            resolveSessionTabLayout(336.dp, 2, minWidth, maxWidth)
        )
    }

    @Test
    fun `weighted when per-tab share is just below max width`() {
        // 2 tabs share 335dp → 167.5dp each → below max → weight fill.
        assertEquals(
            SessionTabLayout.FitWeighted,
            resolveSessionTabLayout(335.dp, 2, minWidth, maxWidth)
        )
    }

    @Test
    fun `clamp to max with many tabs and generous content width`() {
        // 10 tabs would each get 200dp → exceeds max → clamp.
        assertEquals(
            SessionTabLayout.FitClampedToMax,
            resolveSessionTabLayout(2000.dp, 10, minWidth, maxWidth)
        )
    }

    // ── single tab ─────────────────────────────────────────────────────────

    @Test
    fun `single tab clamps to max when content is wide enough`() {
        assertEquals(
            SessionTabLayout.FitClampedToMax,
            resolveSessionTabLayout(300.dp, 1, minWidth, maxWidth)
        )
    }

    @Test
    fun `single tab is weighted when content is between min and max`() {
        assertEquals(
            SessionTabLayout.FitWeighted,
            resolveSessionTabLayout(120.dp, 1, minWidth, maxWidth)
        )
    }

    @Test
    fun `single tab scrolls when content is below min width`() {
        assertEquals(
            SessionTabLayout.OverflowScroll,
            resolveSessionTabLayout(95.dp, 1, minWidth, maxWidth)
        )
    }

    // ── tiny content / defensive ───────────────────────────────────────────

    @Test
    fun `zero content width with tabs overflows`() {
        assertEquals(
            SessionTabLayout.OverflowScroll,
            resolveSessionTabLayout(0.dp, 3, minWidth, maxWidth)
        )
    }

    @Test
    fun `zero tabs defaults to weighted`() {
        assertEquals(
            SessionTabLayout.FitWeighted,
            resolveSessionTabLayout(400.dp, 0, minWidth, maxWidth)
        )
    }

    @Test
    fun `negative tab count defaults to weighted`() {
        assertEquals(
            SessionTabLayout.FitWeighted,
            resolveSessionTabLayout(400.dp, -1, minWidth, maxWidth)
        )
    }
}
