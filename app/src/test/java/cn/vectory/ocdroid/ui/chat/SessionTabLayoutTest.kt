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
 * otherwise tabs expand to fill using equal weights with no maximum width cap.
 */
class SessionTabLayoutTest {

    private val minWidth = 96.dp

    // ── overflow threshold ─────────────────────────────────────────────────

    @Test
    fun `overflow when min-width sum exceeds content width`() {
        // 4 tabs need 384dp; content is 383dp → one dp short → scroll.
        assertEquals(
            SessionTabLayout.OverflowScroll,
            resolveSessionTabLayout(383.dp, 4, minWidth)
        )
    }

    @Test
    fun `fit when min-width sum exactly equals content width`() {
        // 4 tabs at 96dp exactly fill 384dp → still fits, weighted.
        assertEquals(
            SessionTabLayout.FitWeighted,
            resolveSessionTabLayout(384.dp, 4, minWidth)
        )
    }

    @Test
    fun `fit weighted when content width is just above min-width sum`() {
        assertEquals(
            SessionTabLayout.FitWeighted,
            resolveSessionTabLayout(385.dp, 4, minWidth)
        )
    }

    // ── generous content no longer clamps to any max ───────────────────────

    @Test
    fun `fit weighted even when per-tab share would have exceeded old max`() {
        // 2 tabs share 336dp. With the old max-width cap this would have
        // clamped to 168dp; now it stays weighted so tabs can grow freely.
        assertEquals(
            SessionTabLayout.FitWeighted,
            resolveSessionTabLayout(336.dp, 2, minWidth)
        )
    }

    @Test
    fun `fit weighted with many tabs and generous content width`() {
        // 10 tabs would each get 200dp; no max cap → weighted.
        assertEquals(
            SessionTabLayout.FitWeighted,
            resolveSessionTabLayout(2000.dp, 10, minWidth)
        )
    }

    // ── single tab ─────────────────────────────────────────────────────────

    @Test
    fun `single tab is weighted when content is above min width`() {
        assertEquals(
            SessionTabLayout.FitWeighted,
            resolveSessionTabLayout(300.dp, 1, minWidth)
        )
    }

    @Test
    fun `single tab scrolls when content is below min width`() {
        assertEquals(
            SessionTabLayout.OverflowScroll,
            resolveSessionTabLayout(95.dp, 1, minWidth)
        )
    }

    // ── tiny content / defensive ───────────────────────────────────────────

    @Test
    fun `zero content width with tabs overflows`() {
        assertEquals(
            SessionTabLayout.OverflowScroll,
            resolveSessionTabLayout(0.dp, 3, minWidth)
        )
    }

    @Test
    fun `zero tabs defaults to weighted`() {
        assertEquals(
            SessionTabLayout.FitWeighted,
            resolveSessionTabLayout(400.dp, 0, minWidth)
        )
    }

    @Test
    fun `negative tab count defaults to weighted`() {
        assertEquals(
            SessionTabLayout.FitWeighted,
            resolveSessionTabLayout(400.dp, -1, minWidth)
        )
    }
}
