package cn.vectory.ocdroid.data.repository.http

import cn.vectory.ocdroid.BuildConfig
import okhttp3.Headers
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §B3 (slimapi-v2-adapt-traffic-plan §B): sanitize + apply helper 单测.
 *
 * 覆盖：
 *  - [sanitizeClientIdentityHeaderValue]：4 类非法值 → null；合法值 → 原样返回。
 *  - [applyClientIdentityHeaders]：某头非法时仅省略该头，另两头仍注入
 *    （per-header 容忍：additive + 向后兼容）。
 */
class ClientIdentityHeadersTest {

    // ── sanitizeClientIdentityHeaderValue ──────────────────────────────

    @Test
    fun `sanitize null returns null`() {
        assertNull(sanitizeClientIdentityHeaderValue(null))
    }

    @Test
    fun `sanitize empty returns null`() {
        assertNull(sanitizeClientIdentityHeaderValue(""))
    }

    @Test
    fun `sanitize blank returns null`() {
        assertNull(sanitizeClientIdentityHeaderValue("   "))
        assertNull(sanitizeClientIdentityHeaderValue("\t"))
    }

    @Test
    fun `sanitize valid value returns verbatim`() {
        assertEquals("ocdroid", sanitizeClientIdentityHeaderValue("ocdroid"))
        assertEquals("0.8.2-abc1234", sanitizeClientIdentityHeaderValue("0.8.2-abc1234"))
        assertEquals(
            "550e8400-e29b-41d4-a716-446655440000",
            sanitizeClientIdentityHeaderValue("550e8400-e29b-41d4-a716-446655440000")
        )
    }

    @Test
    fun `sanitize at max byte boundary is accepted`() {
        // Exactly 128 bytes (ASCII) — at the boundary, NOT over.
        val exact = "a".repeat(SlimapiContract.CLIENT_IDENTITY_HEADER_MAX_BYTES)
        assertEquals(exact, sanitizeClientIdentityHeaderValue(exact))
    }

    @Test
    fun `sanitize over max byte boundary returns null`() {
        // 129 bytes (> 128) → omit. The §B3 byte-length gate.
        val over = "a".repeat(SlimapiContract.CLIENT_IDENTITY_HEADER_MAX_BYTES + 1)
        assertNull(sanitizeClientIdentityHeaderValue(over))
    }

    @Test
    fun `sanitize over byte boundary via multibyte returns null`() {
        // 64 × 'é' = 128 UTF-8 bytes (2 bytes each) → accepted at boundary.
        val atBoundary = "é".repeat(64)
        assertEquals(128, atBoundary.toByteArray(Charsets.UTF_8).size)
        // But 'é' itself is OkHttp-illegal (>= 0x7f) → sanitized out regardless.
        // This pins that the byte-length + OkHttp checks compose (either fails → omit).
        assertNull(sanitizeClientIdentityHeaderValue(atBoundary))
    }

    @Test
    fun `sanitize control char Cc returns null`() {
        // NUL (0x00) — Unicode Cc category. Distinct from the OkHttp rule.
        assertNull(sanitizeClientIdentityHeaderValue("abc\u0000def"))
        // BEL (0x07) — Cc.
        assertNull(sanitizeClientIdentityHeaderValue("abc\u0007def"))
        // DEL (0x7f) — Cc.
        assertNull(sanitizeClientIdentityHeaderValue("abc\u007fdef"))
    }

    @Test
    fun `sanitize tab is Cc and is rejected`() {
        // TAB (0x09) is Cc (ISO-control). OkHttp would ACCEPT it, but the §B3
        // Cc gate is stricter — client-identity values never legitimately
        // contain control chars, so TAB is omitted too.
        assertNull(sanitizeClientIdentityHeaderValue("abc\tdef"))
    }

    @Test
    fun `sanitize illegal OkHttp token returns null`() {
        // 'é' (U+00E9) — category Ll (NOT Cc), but OkHttp Headers.checkValue
        // rejects it (>= 0x7f). Distinct from the Cc case.
        assertNull(sanitizeClientIdentityHeaderValue("abc\u00e9def"))
    }

    @Test
    fun `sanitize CR LF rejected`() {
        // CR / LF would corrupt HTTP framing — both are Cc AND OkHttp-illegal.
        assertNull(sanitizeClientIdentityHeaderValue("abc\rdef"))
        assertNull(sanitizeClientIdentityHeaderValue("abc\ndef"))
    }

    // ── applyClientIdentityHeaders: per-header omit ────────────────────

    /** Builds a Request via the helper and returns the resulting Headers. */
    private fun appliedHeaders(clientId: String?): Headers {
        val builder = Request.Builder().url("https://example.invalid/slimapi/health")
        applyClientIdentityHeaders(builder, clientId)
        return builder.build().headers
    }

    @Test
    fun `apply happy path sets all 3 headers`() {
        val headers = appliedHeaders("device-xyz")
        assertEquals(SlimapiContract.CLIENT_NAME, headers[SlimapiContract.X_CLIENT_NAME])
        assertEquals(BuildConfig.VERSION_NAME, headers[SlimapiContract.X_CLIENT_VERSION])
        assertEquals("device-xyz", headers[SlimapiContract.X_CLIENT_ID])
    }

    @Test
    fun `apply null clientId omits X-Client-Id but keeps name and version`() {
        val headers = appliedHeaders(null)
        assertEquals(SlimapiContract.CLIENT_NAME, headers[SlimapiContract.X_CLIENT_NAME])
        assertEquals(BuildConfig.VERSION_NAME, headers[SlimapiContract.X_CLIENT_VERSION])
        assertNull(headers[SlimapiContract.X_CLIENT_ID])
    }

    @Test
    fun `apply empty clientId omits X-Client-Id but keeps name and version`() {
        val headers = appliedHeaders("")
        assertEquals(SlimapiContract.CLIENT_NAME, headers[SlimapiContract.X_CLIENT_NAME])
        assertEquals(BuildConfig.VERSION_NAME, headers[SlimapiContract.X_CLIENT_VERSION])
        assertNull(headers[SlimapiContract.X_CLIENT_ID])
    }

    @Test
    fun `apply oversize clientId omits X-Client-Id only`() {
        val headers = appliedHeaders("a".repeat(SlimapiContract.CLIENT_IDENTITY_HEADER_MAX_BYTES + 1))
        assertEquals(SlimapiContract.CLIENT_NAME, headers[SlimapiContract.X_CLIENT_NAME])
        assertEquals(BuildConfig.VERSION_NAME, headers[SlimapiContract.X_CLIENT_VERSION])
        assertNull(headers[SlimapiContract.X_CLIENT_ID])
    }

    @Test
    fun `apply control-char clientId omits X-Client-Id only`() {
        val headers = appliedHeaders("bad\u0000id")
        assertEquals(SlimapiContract.CLIENT_NAME, headers[SlimapiContract.X_CLIENT_NAME])
        assertEquals(BuildConfig.VERSION_NAME, headers[SlimapiContract.X_CLIENT_VERSION])
        assertNull(headers[SlimapiContract.X_CLIENT_ID])
    }

    @Test
    fun `apply illegal-okhttp-token clientId omits X-Client-Id only`() {
        // 'é' is not Cc but OkHttp rejects it (>= 0x7f) — distinct invalid case.
        val headers = appliedHeaders("bad\u00e9id")
        assertEquals(SlimapiContract.CLIENT_NAME, headers[SlimapiContract.X_CLIENT_NAME])
        assertEquals(BuildConfig.VERSION_NAME, headers[SlimapiContract.X_CLIENT_VERSION])
        assertNull(headers[SlimapiContract.X_CLIENT_ID])
    }

    @Test
    fun `apply does not throw on illegal clientId`() {
        // No exception — the sanitize gate MUST prevent OkHttp's
        // IllegalArgumentException from surfacing.
        val builder = Request.Builder().url("https://example.invalid/slimapi/health")
        applyClientIdentityHeaders(builder, "evil\r\nheader-injection")
        // Reaching here without throwing is the assertion.
    }
}
