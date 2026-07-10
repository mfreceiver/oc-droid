package cn.vectory.ocdroid.util

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Tolerant base64 + certificate/PKCS12 validation helpers for the mTLS
 * clipboard-import redesign (see
 * `docs/superpowers/specs/2026-07-10-mtls-clipboard-import-design.md`, §A).
 *
 * Pure JDK only — no BouncyCastle (the main classpath has none). Every function
 * is total: it returns `null` on any failure and **never throws**, so callers
 * can invoke them directly off the main thread (PKCS12 KDF is CPU-heavy — the
 * caller is responsible for staying off the UI thread) without a surrounding
 * `try/catch`.
 *
 * Why MIME base64: a pasted PEM (`-----BEGIN CERTIFICATE-----` … headers,
 * newlines, spaces) is first reduced to its base64 payload by [sanitizeBase64],
 * then [decodeBase64OrNull] uses the MIME decoder which ignores stray
 * non-alphabet chars and tolerates missing/extra padding — exactly the
 * "adb-paste" corruption modes the redesign hardens against.
 */

/**
 * Strips everything that is not a base64 alphabet character (`[A-Za-z0-9+/=]`):
 * PEM `-----BEGIN…-----` headers, newlines, carriage returns, spaces, tabs and
 * any other junk. Works for both pure-base64 and pasted-PEM inputs.
 */
fun sanitizeBase64(s: String): String =
    s.filter { it.isBase64Alphabet() }

private fun Char.isBase64Alphabet(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '+' || this == '/' || this == '='

/**
 * Sanitizes [s] then decodes it. Uses [Base64.getMimeDecoder] (lenient: ignores
 * non-base64 chars and tolerates missing/extra padding). Empty/blank input
 * yields `null`. Returns `null` on any [IllegalArgumentException]; never throws.
 */
fun decodeBase64OrNull(s: String): ByteArray? {
    val sanitized = sanitizeBase64(s)
    if (sanitized.isEmpty()) return null
    return try {
        Base64.getMimeDecoder().decode(sanitized)
    } catch (_: Exception) {
        null
    }
}

/**
 * Parses [bytes] as a DER-encoded X.509 certificate. Returns the certificate,
 * or `null` if the bytes are not a valid X.509 cert (or on any parse error).
 * Never throws.
 */
fun parseCaCertOrNull(bytes: ByteArray): X509Certificate? = try {
    CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(bytes)) as? X509Certificate
} catch (_: Exception) {
    null
}

/**
 * Loads [bytes] as a PKCS12 keystore and verifies it contains at least one key
 * entry (an alias that `isKeyEntry` AND owns a non-empty certificate chain).
 * Returns the loaded [KeyStore], or `null` on any failure. Never throws.
 *
 * NOTE: PKCS12 key-derivation (KDF) is CPU-heavy. This function is synchronous
 * and blocking; callers must run it off the main thread.
 */
fun loadClientP12OrNull(
    bytes: ByteArray,
    password: CharArray = CharArray(0),
): KeyStore? = try {
    val ks = KeyStore.getInstance("PKCS12")
    ks.load(ByteArrayInputStream(bytes), password)
    val hasKeyEntry = ks.aliases().toList().any { alias ->
        ks.isKeyEntry(alias) && ((ks.getCertificateChain(alias)?.size ?: 0) > 0)
    }
    if (hasKeyEntry) ks else null
} catch (_: Exception) {
    null
}

private val CN_RFC2253_REGEX = Regex("(?:^|,)CN=([^,]+)")

/**
 * Returns a short display label derived from [cert]'s subject: simplified to
 * `CN=<value>` when a Common Name attribute exists, otherwise the full RFC2253
 * subject name. Returns `null` if [cert] is null ("Null only if input is null",
 * per spec) — the parameter is nullable so callers can pass through a result of
 * `as? X509Certificate` without an extra null-check.
 */
fun certSubjectOrNull(cert: X509Certificate?): String? {
    if (cert == null) return null
    val rfc2253 = cert.subjectX500Principal.name
    val match = CN_RFC2253_REGEX.find(rfc2253) ?: return rfc2253
    return "CN=${match.groupValues[1]}"
}
