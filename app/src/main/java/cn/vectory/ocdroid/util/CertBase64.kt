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
 * is total over normal failure modes: it returns `null` on any normal
 * parse/load failure (the helpers catch [Exception], not [Throwable]), so
 * callers can invoke them directly off the main thread (PKCS12 KDF is
 * CPU-heavy — the caller is responsible for staying off the UI thread) without
 * a surrounding `try/catch`. Errors that escape [Exception] (e.g.
 * [OutOfMemoryError]) are not suppressed.
 *
 * Why MIME base64: a pasted PEM (`-----BEGIN CERTIFICATE-----` … headers,
 * newlines, spaces) is first reduced to its base64 payload by [sanitizeBase64],
 * then [decodeBase64OrNull] uses the MIME decoder which ignores stray
 * non-alphabet chars and tolerates missing/extra padding — exactly the
 * "adb-paste" corruption modes the redesign hardens against.
 */

/**
 * Reduces a base64-or-PEM string to its pure base64 payload.
 *
 * First strips PEM armor tokens (`-----BEGIN …-----` / `-----END …-----`,
 * case-insensitive, including the label words between the dashes), then drops
 * every remaining non-alphabet character (`[A-Za-z0-9+/=]`): newlines,
 * carriage returns, spaces, tabs and any other junk. A pasted PEM therefore
 * reduces to just its base64 body (decodable), while pure-base64 input is
 * unaffected (it carries no armor tokens).
 */
fun sanitizeBase64(s: String): String =
    s.replace(PEM_ARMOR_REGEX, "").filter { it.isBase64Alphabet() }

/** Matches PEM armor lines, e.g. `-----BEGIN CERTIFICATE-----` (case-insensitive). */
private val PEM_ARMOR_REGEX = Regex("-----[A-Z0-9 ]+-----", RegexOption.IGNORE_CASE)

private fun Char.isBase64Alphabet(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '+' || this == '/' || this == '='

/**
 * Sanitizes [s] then decodes it. Uses [Base64.getMimeDecoder] (lenient: ignores
 * non-base64 chars and tolerates missing/extra padding). Empty/blank input
 * yields `null`. Returns `null` on any normal decode failure (it catches
 * [Exception]).
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
 * or `null` if the bytes are not a valid X.509 cert or on any normal parse
 * failure (it catches [Exception]).
 */
fun parseCaCertOrNull(bytes: ByteArray): X509Certificate? = try {
    CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(bytes)) as? X509Certificate
} catch (_: Exception) {
    null
}

/**
 * Loads [bytes] as a PKCS12 keystore and verifies it is usable as an mTLS
 * client bundle, mirroring `SslConfig.buildMutualTlsConfig`'s contract so a
 * bundle that "imports" here is actually usable at Test/Save:
 *
 * 1. EXACTLY one key entry (every alias for which `ks.isKeyEntry` holds —
 *    both private-key entries and secret-key entries count; this matches
 *    buildMutualTlsConfig, which counts all `isKeyEntry` aliases and rejects
 *    when the count is not 1). A bundle with e.g. a private-key entry AND a
 *    secret-key entry has two key entries and is rejected here too.
 * 2. That single key entry owns a non-empty certificate chain — a client cert
 *    needs a cert, so a lone secret-key entry (chain-less) is rejected.
 *
 * Returns the loaded [KeyStore], or `null` on any failure or when either check
 * fails. Normal parse/load failures return `null` (it catches [Exception]).
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
    val keyAliases = ks.aliases().toList().filter { ks.isKeyEntry(it) }
    if (keyAliases.size != 1) null
    else {
        val chain = ks.getCertificateChain(keyAliases.first())
        if (chain != null && chain.isNotEmpty()) ks else null
    }
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
