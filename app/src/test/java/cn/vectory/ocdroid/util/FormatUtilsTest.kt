package cn.vectory.ocdroid.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R18 Phase 5 — [formatBytes] pure-JVM coverage.
 *
 * `formatBytes` is `internal` to the app module (visible to tests in the same
 * module). It is the single canonical UI byte formatter (Settings traffic
 * counters + server-management dialog), previously 0% covered.
 *
 * ROI: 28 lines, 4 branches (B / KB / MB / GB) — trivial to fully cover with
 * locale-stable ASCII-digit assertions.
 */
class FormatUtilsTest {

    @Test
    fun `zero bytes renders plain B`() {
        assertEquals("0 B", formatBytes(0L))
    }

    @Test
    fun `sub-kibibyte values render plain B`() {
        assertEquals("1 B", formatBytes(1L))
        assertEquals("1023 B", formatBytes(1023L))
    }

    @Test
    fun `exactly one kibibyte flips to KB with one decimal`() {
        assertEquals("1.0 KB", formatBytes(1024L))
    }

    @Test
    fun `fractional KB preserves one decimal place`() {
        assertEquals("1.5 KB", formatBytes(1536L))            // 1.5 * 1024
        assertEquals("10.0 KB", formatBytes(10L * 1024))
    }

    @Test
    fun `just under 1 MiB stays in KB`() {
        assertEquals("1023.0 KB", formatBytes(1024L * 1023))
    }

    @Test
    fun `exactly one mebibyte flips to MB`() {
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
    }

    @Test
    fun `multi-megabyte values keep one decimal`() {
        assertEquals("1.5 MB", formatBytes(1024L * 1024 * 3 / 2))
        // 69 MiB — the canonical "where is the 69MB coming from" bug-bait value.
        assertEquals("69.0 MB", formatBytes(69L * 1024 * 1024))
    }

    @Test
    fun `exactly one gibibyte flips to GB with two decimals`() {
        assertEquals("1.00 GB", formatBytes(1024L * 1024 * 1024))
    }

    @Test
    fun `multi-gigabyte values use two decimal places`() {
        assertEquals("2.50 GB", formatBytes(1024L * 1024 * 1024 * 5 / 2))
    }

    @Test
    fun `output is locale-stable ASCII with period decimal separator`() {
        // Locale.US is enforced so a device in e.g. de_DE still emits "1.5 KB"
        // (period) rather than "1,5 KB" (comma) — the Settings page parses
        // this back for display, and a comma would break that.
        val kb = formatBytes(1536L)
        assertEquals(true, kb.contains('.'))
        assertEquals(false, kb.contains(','))
    }

    @Test
    fun `negative bytes are rendered as plain B since they are less than the unit threshold`() {
        // The formatter doesn't special-case negatives; this locks the
        // existing behaviour so a future "abs()" tweak is a visible diff.
        assertEquals("-1 B", formatBytes(-1L))
        assertEquals("-500 B", formatBytes(-500L))
    }
}
