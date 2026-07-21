package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.HostProfile
import java.net.URI

/**
 * G-ACL: migrates a legacy slimapi `http://<host>:4097` profile to
 * `https://<host>:14097` with `mtlsEnabled=true`. Idempotent: if already
 * migrated (https + port 14097 + mtlsEnabled) or non-slimapi (not port 4097)
 * → no-op.
 *
 * Pure function, unit-testable. Parses the [serverUrl] via [URI] so edge
 * cases (trailing slash, missing port) are handled defensively.
 */
fun HostProfile.migrateForGacl(): HostProfile {
    // 1. Parse the serverUrl to extract scheme, host, port, path.
    val url = serverUrl.trim().ifEmpty { return this }
    val uri = try { URI(url) } catch (_: Exception) { return this }
    val scheme = uri.scheme?.lowercase() ?: return this
    val host = uri.host ?: return this
    val originalPort = uri.port

    // 2. Already-migrated guard: if mtlsEnabled + https + port 14097 → no-op.
    if (mtlsEnabled && scheme == "https" && originalPort == 14097) return this

    // 3. Check if this is a legacy slimapi endpoint: http + port 4097.
    if (scheme != "http") return this
    if (originalPort != 4097) return this

    // 4. Build the migrated URL: https + same host + port 14097, preserving path.
    val migratedUri = URI("https", null, host, 14097, uri.rawPath ?: "/", uri.rawQuery, uri.rawFragment)
    val migratedUrl = migratedUri.toString()

    // 5. Return the migrated profile copy.
    return copy(
        serverUrl = migratedUrl,
        mtlsEnabled = true
        // credentials (basicAuth) and slim flag are preserved as-is.
    )
}
