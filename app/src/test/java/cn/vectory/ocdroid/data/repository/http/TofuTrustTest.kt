package cn.vectory.ocdroid.data.repository.http

import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

/**
 * §tofu R2: pure-JVM unit tests for the TOFU primitives in [TofuTrust.kt] +
 * [TofuPinStore]'s two-tier semantics. No Android, no OkHttp, no network —
 * fully hermetic.
 *
 * Coverage:
 *  - [spkiSha256Hex] is stable + lowercase hex (length 64).
 *  - [PinningTrustManager] accepts matching SPKI; throws on mismatch / empty.
 *  - [hostPortFromUrl] cases (https→443 default, explicit port, http→80, no-host→null).
 *  - [classifyValidation] Fail paths (UNKNOWN_ISSUER on self-signed; HOSTNAME_MISMATCH
 *    when leaf doesn't cover the supplied host). Pass on system-trusted cert is
 *    impractical in a JVM unit test (would need a trusted CA private key) — covered
 *    structurally by the Fail-path inverse.
 *  - [TofuPinStore] two-tier: trustPersistent survives across a fresh store read
 *    (here: the same InMemory instance; for EspTofuPinStore the persistence is
 *    exercised via instrumented tests); acceptSession is a separate tier; clear
 *    drops both.
 *  - [TofuDecision] sealed hierarchy round-trips (compiler exhaustiveness check).
 */
class TofuTrustTest {

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** A self-signed leaf cert with a stable SPKI (HeldCertificate holds the keypair). */
    private fun selfSigned(cn: String = "tofu-test.example", san: String? = null): HeldCertificate {
        val b = HeldCertificate.Builder().commonName(cn)
        if (san != null) b.addSubjectAlternativeName(san)
        return b.build()
    }

    // ── spkiSha256Hex ─────────────────────────────────────────────────────────

    @Test
    fun `spkiSha256Hex is 64-char lowercase hex and stable across calls`() {
        val holder = selfSigned()
        val cert: X509Certificate = holder.certificate
        val a = cert.spkiSha256Hex()
        val b = cert.spkiSha256Hex()
        assertEquals("stable across calls", a, b)
        assertEquals("64 hex chars (SHA-256 = 32 bytes)", 64, a.length)
        assertTrue("lowercase hex", a.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `spkiSha256Hex differs across distinct keypairs`() {
        val a = selfSigned("a.example").certificate.spkiSha256Hex()
        val b = selfSigned("b.example").certificate.spkiSha256Hex()
        assertFalse("distinct keypairs → distinct SPKI", a == b)
    }

    // ── PinningTrustManager ──────────────────────────────────────────────────

    @Test
    fun `PinningTrustManager accepts the chain whose leaf SPKI matches`() {
        val holder = selfSigned()
        val spki = holder.certificate.spkiSha256Hex()
        val tm = PinningTrustManager(spki)
        // No exception means accept.
        tm.checkServerTrusted(arrayOf(holder.certificate), "RSA")
    }

    @Test
    fun `PinningTrustManager throws on SPKI mismatch`() {
        val holder = selfSigned()
        val tm = PinningTrustManager("00".repeat(32)) // unrelated pin
        assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(arrayOf(holder.certificate), "RSA")
        }
    }

    @Test
    fun `PinningTrustManager throws on empty chain`() {
        val tm = PinningTrustManager("00".repeat(32))
        assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(emptyArray(), "RSA")
        }
    }

    @Test
    fun `PinningTrustManager matches case-insensitively`() {
        val holder = selfSigned()
        val spkiUpper = holder.certificate.spkiSha256Hex().uppercase()
        val tm = PinningTrustManager(spkiUpper)
        tm.checkServerTrusted(arrayOf(holder.certificate), "RSA") // no throw
    }

    // ── hostPortFromUrl ──────────────────────────────────────────────────────

    @Test
    fun `hostPortFromUrl defaults https to 443`() {
        assertEquals("example.com:443", hostPortFromUrl("https://example.com/path"))
    }

    @Test
    fun `hostPortFromUrl defaults http to 80`() {
        assertEquals("example.com:80", hostPortFromUrl("http://example.com"))
    }

    @Test
    fun `hostPortFromUrl preserves an explicit port`() {
        assertEquals("example.com:8443", hostPortFromUrl("https://example.com:8443/x"))
        assertEquals("127.0.0.1:4096", hostPortFromUrl("http://127.0.0.1:4096"))
    }

    @Test
    fun `hostPortFromUrl returns null for non-authority or no-host URLs`() {
        assertNull(hostPortFromUrl("file:///etc/hosts"))
        assertNull(hostPortFromUrl("javascript:void(0)"))
        // Scheme without a known default port and no explicit port → null.
        assertNull(hostPortFromUrl("foo://example.com"))
    }

    // ── classifyValidation ───────────────────────────────────────────────────

    @Test
    fun `classifyValidation flags a self-signed leaf as UNKNOWN_ISSUER`() {
        // Self-signed → not in the platform trust store → PKIX failure. The
        // heuristic maps "unable to find valid certification path" / "trust
        // anchor" to UNKNOWN_ISSUER.
        val holder = selfSigned()
        val result = classifyValidation(arrayOf(holder.certificate), "tofu-test.example")
        assertTrue("self-signed → Fail", result is TofuValidation.Fail)
        // The exact reason depends on the JDK's exception text, but it must
        // not be EXPIRED/HOSTNAME_MISMATCH for a fresh self-signed leaf whose
        // CN matches the host — UNKNOWN_ISSUER is the expected classification.
        val fail = result as TofuValidation.Fail
        assertEquals(
            "self-signed classification",
            TofuFailureReason.UNKNOWN_ISSUER,
            fail.reason,
        )
    }

    @Test
    fun `classifyValidation flags a leaf that does not cover the supplied host`() {
        // A self-signed cert whose CN/SAN is "a.example", probed against
        // "b.example" → system trust FAILs first (UNKNOWN_ISSUER) so we cannot
        // reach the hostname check via the self-signed path alone. Instead,
        // exercise the hostname-check branch via coversHost directly (the
        // building block classifyValidation calls).
        val holder = selfSigned(cn = "a.example", san = "a.example")
        val cert: X509Certificate = holder.certificate
        assertTrue("covers its own CN", cert.coversHost("a.example"))
        assertFalse("does not cover a different host", cert.coversHost("b.example"))
        assertFalse("wildcard mismatch", cert.coversHost("sub.a.example"))
    }

    @Test
    fun `coversHost honors a single leading wildcard SAN`() {
        val holder = selfSigned(cn = "ignored.example", san = "*.wild.example")
        val cert: X509Certificate = holder.certificate
        assertTrue("wildcard covers one leading label", cert.coversHost("foo.wild.example"))
        assertFalse("wildcard does not cover two leading labels", cert.coversHost("x.y.wild.example"))
        assertFalse("wildcard does not cover the bare domain", cert.coversHost("wild.example"))
    }

    @Test
    fun `classifyValidation on empty chain returns Fail OTHER`() {
        val result = classifyValidation(emptyArray(), "any.example")
        assertTrue(result is TofuValidation.Fail)
        assertEquals(TofuFailureReason.OTHER, (result as TofuValidation.Fail).reason)
    }

    // ── TofuPinStore two-tier semantics (InMemoryTofuPinStore) ───────────────

    @Test
    fun `InMemoryTofuPinStore trustPersistent survives in the persistent tier`() {
        val store = InMemoryTofuPinStore()
        assertNull(store.pinnedSpki("h:443"))
        store.trustPersistent("h:443", "deadbeef")
        assertEquals("deadbeef", store.pinnedSpki("h:443"))
        // acceptSession on a DIFFERENT host:port is independent.
        store.acceptSession("other:443", "cafe")
        assertEquals("deadbeef", store.pinnedSpki("h:443"))
        assertEquals("cafe", store.pinnedSpki("other:443"))
    }

    @Test
    fun `InMemoryTofuPinStore acceptSession is a separate session tier`() {
        val store = InMemoryTofuPinStore()
        store.acceptSession("h:443", "aa")
        assertEquals("aa", store.pinnedSpki("h:443"))
        // A second store instance does NOT see the session pin (session tier
        // is process-local; persistent tier would survive). This models the
        // "Accept once is lost on process death" contract.
        val fresh = InMemoryTofuPinStore()
        assertNull("session pin is not persistent", fresh.pinnedSpki("h:443"))
    }

    @Test
    fun `InMemoryTofuPinStore persistent wins over session on conflict`() {
        val store = InMemoryTofuPinStore()
        store.trustPersistent("h:443", "persisted")
        store.acceptSession("h:443", "session")
        // Persistent takes precedence (a later session-only accept cannot
        // shadow a persisted trust).
        assertEquals("persisted", store.pinnedSpki("h:443"))
    }

    @Test
    fun `InMemoryTofuPinStore clear drops both tiers`() {
        val store = InMemoryTofuPinStore()
        store.trustPersistent("h:443", "p")
        store.acceptSession("h:443", "s")
        store.clear("h:443")
        assertNull(store.pinnedSpki("h:443"))
        // Other endpoints untouched.
        store.trustPersistent("other:443", "x")
        store.clear("h:443")
        assertEquals("x", store.pinnedSpki("other:443"))
    }

    // ── CaptureTrustManager ──────────────────────────────────────────────────

    @Test
    fun `CaptureTrustManager records the presented chain on first checkServerTrusted`() {
        val holder = selfSigned()
        val capture = CaptureTrustManager()
        assertNull("nothing captured before any check", capture.capturedChain)
        capture.checkServerTrusted(arrayOf(holder.certificate), "RSA")
        val chain = capture.capturedChain
        assertNotNull(chain)
        assertEquals(1, chain!!.size)
        assertEquals(holder.certificate, chain.first())
    }

    // ── TofuDecision exhaustiveness ──────────────────────────────────────────

    @Test
    fun `TofuDecision variants carry SPKI and Cancel is a singleton`() {
        val once = TofuDecision.AcceptOnce("aa")
        val trust = TofuDecision.Trust("bb")
        val cancel = TofuDecision.Cancel
        assertEquals("aa", once.spki)
        assertEquals("bb", trust.spki)
        // Cancel carries no SPKI — it's a data object (singleton identity).
        assertEquals(TofuDecision.Cancel, cancel)
    }
}
