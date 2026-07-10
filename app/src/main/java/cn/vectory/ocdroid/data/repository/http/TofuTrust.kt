package cn.vectory.ocdroid.data.repository.http

import java.net.URI
import java.security.MessageDigest
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * §tofu R2: the pure crypto/pinning/capture primitives for SSH-style TOFU.
 * No Android / OkHttp / network — fully unit-testable. The SECURITY gate is
 * [PinningTrustManager] (SPKI match). [classifyValidation] is **UI-tone only**
 * (how scary the prompt looks) and is intentionally best-effort — it can never
 * weaken security, because acceptance always goes through the SPKI pin.
 */

/**
 * Leaf certificate's SPKI (SubjectPublicKeyInfo) SHA-256 as lowercase hex.
 * [X509Certificate.getPublicKey].getEncoded() returns the SPKI DER, so this is
 * the same value OkHttp's CertificatePinner / HPKP pin to. Stable across
 * cosmetic leaf renewals that reuse the keypair (grill Q3=b); only a true key
 * rotation changes it.
 */
fun X509Certificate.spkiSha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
    return digest.joinToString("") { "%02x".format(it) }
}

/**
 * `host:port` authority from a URL (the TOFU trust key — grill Q4=a). Defaults
 * the port to the scheme default (443 https / 80 http) when absent so the same
 * endpoint always maps to the same key. null for non-authority URLs.
 */
fun hostPortFromUrl(url: String): String? = runCatching {
    val u = URI(url)
    val host = u.host ?: return null
    val port = if (u.port != -1) u.port else when (u.scheme?.lowercase()) {
        "https" -> 443; "http" -> 80; else -> return null
    }
    "$host:$port"
}.getOrNull()

/** A captured leaf + its SPKI, from a one-shot capture probe. */
data class CapturedCert(val leaf: X509Certificate, val spkiHex: String)

/** Best-effort reason a server cert failed system validation (UI tone only). */
enum class TofuFailureReason { UNKNOWN_ISSUER, EXPIRED, HOSTNAME_MISMATCH, OTHER }

/** Outcome of running the captured chain through the platform trust store. */
sealed interface TofuValidation {
    /** Chain validates against the system trust store (e.g. a public CA). */
    data object Pass : TofuValidation
    /**
     * Chain failed system validation. [reason] is best-effort classification for
     * the prompt's wording/tone; the security decision is the SPKI pin, not this.
     */
    data class Fail(val reason: TofuFailureReason, val detail: String) : TofuValidation
}

/**
 * §tofu R2: the SECURITY gate. A [X509TrustManager] that accepts the server
 * chain iff the leaf's SPKI SHA-256 matches [expectedSpkiHex]. Performs NO
 * chain/path validation — the pin IS the trust (TOFU model, grill Q5). Mismatch
 * throws [CertificateException], aborting the handshake.
 */
class PinningTrustManager(private val expectedSpkiHex: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull()
            ?: throw CertificateException("TOFU: empty server certificate chain")
        val presented = leaf.spkiSha256Hex()
        if (!presented.equals(expectedSpkiHex, ignoreCase = true)) {
            throw CertificateException(
                "TOFU SPKI mismatch (expected=$expectedSpkiHex presented=$presented)"
            )
        }
    }
}

/**
 * §tofu R2: a trust-all [X509TrustManager] that RECORDS the presented server
 * chain so a one-shot capture probe can extract the leaf + compute its SPKI for
 * the trust prompt. Used ONLY for fingerprint acquisition, never for the live
 * connection (the live connection uses [PinningTrustManager] once trusted).
 */
class CaptureTrustManager : X509TrustManager {
    @Volatile
    private var captured: Array<X509Certificate>? = null

    val capturedChain: Array<X509Certificate>? get() = captured

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (captured == null) captured = chain
    }
}

/**
 * Runs [chain] through the platform [TrustManagerFactory] + a best-effort
 * hostname check against [host], classifying the outcome. UI-tone only — never
 * a security gate. (authType "RSA" is a generic guess; classification is
 * exception-class heuristic, not handshake-precise.)
 */
fun classifyValidation(chain: Array<X509Certificate>, host: String): TofuValidation {
    if (chain.isEmpty()) return TofuValidation.Fail(TofuFailureReason.OTHER, "empty chain")
    val leaf = chain[0]

    // 1. validity window (cheap, deterministic)
    runCatching { leaf.checkValidity() }.onFailure { e ->
        if (e is CertificateExpiredException)
            return TofuValidation.Fail(TofuFailureReason.EXPIRED, "certificate has expired")
        if (e is CertificateNotYetValidException)
            return TofuValidation.Fail(TofuFailureReason.EXPIRED, "certificate not yet valid")
    }

    // 2. system trust-store path validation
    val systemTm = run {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
    runCatching { systemTm.checkServerTrusted(chain, "RSA") }.onFailure { e ->
        val msg = (e.message ?: e.javaClass.simpleName).lowercase()
        val reason = when {
            msg.contains("unable to find valid certification path") ||
                msg.contains("pkix path building failed") ||
                msg.contains("trust anchor") ->
                TofuFailureReason.UNKNOWN_ISSUER
            else -> TofuFailureReason.OTHER
        }
        return TofuValidation.Fail(reason, e.message ?: e.javaClass.simpleName)
    }

    // 3. hostname coverage (SAN/CN vs the host we connected to)
    if (!leaf.coversHost(host)) {
        return TofuValidation.Fail(
            TofuFailureReason.HOSTNAME_MISMATCH,
            "certificate does not cover '$host'"
        )
    }
    return TofuValidation.Pass
}

/**
 * Best-effort check that this cert's SAN (DNS/IP) or CN covers [host]. Supports
 * a single leading wildcard label (`*.example.com`). UI-tone only.
 */
fun X509Certificate.coversHost(host: String): Boolean {
    val sans = runCatching { subjectAlternativeNames }.getOrNull()
    if (sans != null) {
        for (san in sans) {
            val type = san[0] as Int
            val value = san[1] as? String ?: continue
            when (type) {
                2 -> if (dnsMatches(value, host)) return true   // DNS
                7 -> if (value == host) return true              // IP
            }
        }
    }
    // CN fallback (deprecated but common on self-signed).
    val cn = extractCn() ?: return false
    return dnsMatches(cn, host)
}

private fun dnsMatches(pattern: String, host: String): Boolean {
    if (pattern.equals(host, ignoreCase = true)) return true
    if (pattern.startsWith("*.")) {
        val tail = pattern.substring(2)
        // single-label wildcard only; host must have exactly one extra leading label
        val idx = host.indexOf('.')
        return idx > 0 && host.substring(idx + 1).equals(tail, ignoreCase = true)
    }
    return false
}

private fun X509Certificate.extractCn(): String? {
    val dn = subjectX500Principal.name
    // RFC2253: CN=foo,OU=... — take the first CN= value
    val cnPart = dn.split(',').firstOrNull { it.trim().startsWith("CN=", ignoreCase = true) }
        ?: return null
    return cnPart.substringAfter('=').trim()
}
