package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.SlimSessionLastError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §T17 slimapi v1 §6.1: JVM unit tests for the pure helpers backing the
 * LastError banner inside [StatusSlot]. The banner body composable
 * ([SessionErrorBanner]) is private; its DATA CONTRACT is exercised through
 * [lastErrorBannerTitle] + [lastErrorBannerSubtitle] which are the sole
 * inputs to the title / subtitle Text nodes.
 *
 * The contract:
 *  - title = `SlimSessionLastError.name` verbatim (the stable machine-readable
 *    error code the user matches against logs / docs).
 *  - subtitle = trimmed `message` truncated to `maxChars`; null when the
 *    sidecar sent no message OR the trimmed value is empty. A null subtitle
 *    collapses the banner to a single line (the title-only path).
 *
 * Null-safety is the hard gate (T17-C1): the composable's outer `lastError`
 * param guards the LastError branch via pick() returning LastError only when
 * lastError != null. These tests cover the data shape GIVEN the branch is
 * active; the priority-level null-safety is covered in [StatusSlotPriorityTest].
 */
class LastErrorBannerTextTest {

    @Test
    fun `title returns the error name verbatim`() {
        val error = SlimSessionLastError(name = "upstream_error", message = null, at = null)
        assertEquals("upstream_error", lastErrorBannerTitle(error))
    }

    @Test
    fun `title preserves unusual error code names (no normalization)`() {
        // The sidecar's `name` is a stable identifier — the client renders
        // it verbatim so the user can copy / match against logs. Unusual
        // shapes (snake_case, dotted namespaces, ALL_CAPS) round-trip
        // unchanged.
        val cases = listOf(
            "session_not_found",
            "UpstreamTimeout",
            "upstream.http.5xx",
            "RATE_LIMITED",
            "p95::latency::exceeded",
        )
        cases.forEach { name ->
            val error = SlimSessionLastError(name = name, message = null, at = null)
            assertEquals(name, lastErrorBannerTitle(error))
        }
    }

    @Test
    fun `subtitle returns trimmed message when present`() {
        val error = SlimSessionLastError(
            name = "upstream_error",
            message = "provider returned 503",
            at = null,
        )
        val subtitle = lastErrorBannerSubtitle(error)
        assertNotNull(subtitle)
        assertEquals("provider returned 503", subtitle)
    }

    @Test
    fun `subtitle trims leading and trailing whitespace from message`() {
        val error = SlimSessionLastError(
            name = "x",
            message = "  padded message  ",
            at = null,
        )
        assertEquals("padded message", lastErrorBannerSubtitle(error))
    }

    @Test
    fun `subtitle truncates message longer than maxChars`() {
        val longMessage = "x".repeat(500)
        val error = SlimSessionLastError(name = "x", message = longMessage, at = null)
        val subtitle = lastErrorBannerSubtitle(error, maxChars = 50)
        assertNotNull(subtitle)
        assertEquals(50, subtitle!!.length)
    }

    @Test
    fun `subtitle respects custom maxChars`() {
        val error = SlimSessionLastError(
            name = "x",
            message = "0123456789",
            at = null,
        )
        assertEquals("012", lastErrorBannerSubtitle(error, maxChars = 3))
        assertEquals("0123456789", lastErrorBannerSubtitle(error, maxChars = 100))
    }

    @Test
    fun `subtitle returns null when message is null`() {
        // Boundary: the sidecar can legitimately emit only `name` (the
        // SlimSessionLastError contract — message / at are nullable + defaulted).
        // The banner collapses to a single-line title; the subtitle row is
        // omitted entirely (verified in SessionErrorBanner).
        val error = SlimSessionLastError(name = "upstream_error", message = null, at = null)
        assertNull(lastErrorBannerSubtitle(error))
    }

    @Test
    fun `subtitle returns null when message is blank after trimming`() {
        // Defensive: a whitespace-only message is treated as no message —
        // rendering an empty / blank subtitle row would be visual noise.
        // Covers "   ", "\t\n", "" (after trim → "").
        val cases = listOf("   ", "\t\n", "", " \n \t ")
        cases.forEach { blank ->
            val error = SlimSessionLastError(name = "x", message = blank, at = null)
            assertNull(
                "blank message '$blank' must yield null subtitle",
                lastErrorBannerSubtitle(error),
            )
        }
    }

    @Test
    fun `subtitle default maxChars is 120 (banner stays compact)`() {
        // Pin the default so a future refactor doesn't silently let the
        // banner grow into a full error page. The @designer pass may tune
        // the value; until then, 120 ≈ two bodySmall lines at chat width.
        val longMessage = "y".repeat(500)
        val error = SlimSessionLastError(name = "x", message = longMessage, at = null)
        val subtitle = lastErrorBannerSubtitle(error)
        assertNotNull(subtitle)
        assertEquals(120, subtitle!!.length)
    }
}
