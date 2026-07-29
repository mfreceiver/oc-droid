package cn.vectory.ocdroid.data.repository.http

import cn.vectory.ocdroid.BuildConfig
import okhttp3.Request

/**
 * §B / §B3 (slimapi-v2-adapt-traffic-plan §B): shared helper that applies
 * the 3 additive client-identity headers (`X-Client-Name` /
 * `X-Client-Version` / `X-Client-Id`) to a [Request.Builder].
 *
 * **Why a shared helper**: the health/ready/cert one-shot probes
 * ([OpenCodeRepository] `probeSlimapiHealth` / `checkHealthFor` +
 * [TofuRepository] `captureServerCert`) build their own throwaway OkHttp
 * clients WITHOUT the shared interceptor chain, so they bypass
 * [ClientIdentityInterceptor]. The version header is already manually added
 * at those call sites; this helper applies the identity set the same way so
 * both paths (interceptor + manual probe) emit a byte-identical header set.
 *
 * **Per-header tolerance**: each value is sanitized first; an invalid value
 * causes ONLY that header to be omitted (the other two are still set). This
 * matches the sidecar's additive + backward-compatible contract — a missing
 * header never breaks the request. Notably `X-Client-Id` is omitted
 * independently when the device id is absent/invalid, while
 * `X-Client-Name` / `X-Client-Version` are still sent.
 *
 * **Caller gating**: this helper does NOT check the request path. Callers
 * (the interceptor + the probe sites) gate on the `/slimapi/` prefix
 * themselves before invoking, so identity headers never leak onto non-slimapi
 * (catch-all / legacy opencode) requests.
 */
internal fun applyClientIdentityHeaders(
    builder: Request.Builder,
    clientId: String?,
) {
    // X-Client-Name: constant "ocdroid" (always valid; sanitize is defensive).
    sanitizeClientIdentityHeaderValue(SlimapiContract.CLIENT_NAME)?.let {
        builder.header(SlimapiContract.X_CLIENT_NAME, it)
    }
    // X-Client-Version: git-derived BuildConfig.VERSION_NAME.
    sanitizeClientIdentityHeaderValue(BuildConfig.VERSION_NAME)?.let {
        builder.header(SlimapiContract.X_CLIENT_VERSION, it)
    }
    // X-Client-Id: device id. Omitted independently if absent/invalid.
    sanitizeClientIdentityHeaderValue(clientId)?.let {
        builder.header(SlimapiContract.X_CLIENT_ID, it)
    }
}

/**
 * §B3 sanitize gate. Returns [value] verbatim if it is safe to set as an
 * OkHttp header value, or `null` if the header MUST be omitted.
 *
 * A value is invalid if ANY of:
 *  - null / empty / blank (whitespace-only);
 *  - UTF-8 byte length exceeds [SlimapiContract.CLIENT_IDENTITY_HEADER_MAX_BYTES];
 *  - it contains an ISO-control char (Unicode Cc category — NUL / BEL / TAB /
 *    DEL / C1 controls / ...). Client-identity values are opaque tokens
 *    (UUID / git tag / "ocdroid") that never legitimately contain control
 *    chars, so rejecting TAB (which OkHttp would accept) is harmless and
 *    keeps the wire format strict.
 *  - it contains a char that OkHttp's `Request.Builder.header()` would
 *    reject (replicates `okhttp3.Headers.checkValue`: 0x00-0x1f except TAB,
 *    and >= 0x7f). Pre-checking this means we OMIT the header instead of
 *    letting OkHttp throw `IllegalArgumentException` mid-request.
 *
 * The two char-level checks overlap but are evaluated separately to match
 * the spec's enumerated invalid cases (Cc category AND OkHttp token rules).
 */
internal fun sanitizeClientIdentityHeaderValue(value: String?): String? {
    if (value.isNullOrBlank()) return null
    if (value.toByteArray(Charsets.UTF_8).size > SlimapiContract.CLIENT_IDENTITY_HEADER_MAX_BYTES) return null
    value.forEach { c ->
        // §B3: control char (Unicode Cc category).
        if (c.isISOControl()) return null
        // §B3: OkHttp Request.Builder.header() token legality — would throw.
        if ((c.code <= 0x1f && c != '\t') || c.code >= 0x7f) return null
    }
    return value
}
