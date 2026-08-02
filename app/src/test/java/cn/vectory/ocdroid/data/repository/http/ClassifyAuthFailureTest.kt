package cn.vectory.ocdroid.data.repository.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * §F2: unit coverage for [classifyAuthFailure] — the pure classifier that maps
 * a terminal probe exception + optional sidecar envelope code to an
 * [AuthFailureReason] (or null when the failure is NOT auth-shaped).
 *
 * Locks the decision table:
 *  - Envelope `upstream_http_401` / `upstream_http_403` → [AuthFailureReason.HttpAuth].
 *  - `shell_not_allowed` (authorization, NOT authentication) → null.
 *  - `upstream_unavailable` / `transform_busy` (transient) → null.
 *  - `upstream_http_500` / `upstream_http_502` (transient 5xx) → null.
 *  - `IOException` (transport / TLS handshake) → null (mtlsDegradedError owns cert issues).
 *  - null exception → null.
 *  - Slim-path message fallback (`Exception("HTTP 401")`) → HttpAuth.
 *  - Unknown envelope codes → null (conservative).
 */
class ClassifyAuthFailureTest {

    // ── Envelope-driven classification (authoritative) ──────────────────────

    @Test
    fun `envelope upstream_http_401 classifies as HttpAuth`() {
        val result = classifyAuthFailure(exception = null, errorCode = "upstream_http_401")
        assertEquals(AuthFailureReason.HttpAuth(401, null), result)
    }

    @Test
    fun `envelope upstream_http_403 classifies as HttpAuth`() {
        val result = classifyAuthFailure(exception = null, errorCode = "upstream_http_403")
        assertEquals(AuthFailureReason.HttpAuth(403, null), result)
    }

    @Test
    fun `envelope upstream_http_500 is transient and returns null`() {
        assertNull(classifyAuthFailure(exception = null, errorCode = "upstream_http_500"))
    }

    @Test
    fun `envelope upstream_http_502 is transient and returns null`() {
        assertNull(classifyAuthFailure(exception = null, errorCode = "upstream_http_502"))
    }

    @Test
    fun `envelope upstream_http_404 is not auth and returns null`() {
        assertNull(classifyAuthFailure(exception = null, errorCode = "upstream_http_404"))
    }

    @Test
    fun `shell_not_allowed is authorization not authentication and returns null`() {
        assertNull(classifyAuthFailure(exception = null, errorCode = SlimapiErrorCodes.SHELL_NOT_ALLOWED))
    }

    @Test
    fun `upstream_unavailable is transient and returns null`() {
        assertNull(classifyAuthFailure(exception = null, errorCode = SlimapiErrorCodes.UPSTREAM_UNAVAILABLE))
    }

    @Test
    fun `transform_busy is transient and returns null`() {
        assertNull(classifyAuthFailure(exception = null, errorCode = SlimapiErrorCodes.TRANSFORM_BUSY))
    }

    @Test
    fun `unknown envelope code is conservative and returns null`() {
        assertNull(classifyAuthFailure(exception = null, errorCode = "session_not_found"))
        assertNull(classifyAuthFailure(exception = null, errorCode = "directory_not_allowed"))
    }

    // ── IOException exclusion ────────────────────────────────────────────────

    @Test
    fun `IOException is transport and returns null even with an envelope code`() {
        // SSLHandshakeException / generic IOException → owned by mtlsDegradedError;
        // the classifier must NOT re-classify as HttpAuth.
        val io = IOException("broken pipe")
        assertNull(classifyAuthFailure(exception = io, errorCode = "upstream_http_401"))
        assertNull(classifyAuthFailure(exception = io, errorCode = null))
    }

    @Test
    fun `null exception with null envelope returns null`() {
        assertNull(classifyAuthFailure(exception = null, errorCode = null))
    }

    // ── Message-regex fallback (slim path / no envelope) ────────────────────

    @Test
    fun `slim path message HTTP 401 falls back to HttpAuth`() {
        val exc = Exception("HTTP 401")
        val result = classifyAuthFailure(exception = exc, errorCode = null)
        assertEquals(AuthFailureReason.HttpAuth(401, "HTTP 401"), result)
    }

    @Test
    fun `slim path message HTTP 403 falls back to HttpAuth`() {
        val exc = Exception("HTTP 403")
        val result = classifyAuthFailure(exception = exc, errorCode = null)
        assertEquals(AuthFailureReason.HttpAuth(403, "HTTP 403"), result)
    }

    @Test
    fun `slim path message HTTP 500 does not classify as auth`() {
        val exc = Exception("HTTP 500")
        assertNull(classifyAuthFailure(exception = exc, errorCode = null))
    }

    @Test
    fun `non-HTTP-shaped message does not classify as auth`() {
        val exc = Exception("connection refused")
        assertNull(classifyAuthFailure(exception = exc, errorCode = null))
    }

    @Test
    fun `message regex matches embedded status in longer message`() {
        // Retrofit HttpException.message is typically "HTTP 401 ..." — verify
        // the regex finds the embedded status even in a longer string.
        val exc = RuntimeException("HTTP 401 Unauthorized")
        val result = classifyAuthFailure(exception = exc, errorCode = null)
        assertEquals(AuthFailureReason.HttpAuth(401, "HTTP 401 Unauthorized"), result)
    }

    // ── displayString helper ─────────────────────────────────────────────────

    @Test
    fun `displayString extracts detail from MtlsDegraded`() {
        assertEquals("cert missing", AuthFailureReason.MtlsDegraded("cert missing").displayString())
    }

    @Test
    fun `displayString extracts message from HttpAuth`() {
        assertEquals("HTTP 401", AuthFailureReason.HttpAuth(401, "HTTP 401").displayString())
    }

    @Test
    fun `displayString returns null for envelope-only HttpAuth with null message`() {
        assertNull(AuthFailureReason.HttpAuth(401, null).displayString())
    }

    @Test
    fun `displayString returns null for null receiver`() {
        val reason: AuthFailureReason? = null
        assertNull(reason.displayString())
    }
}
