package cn.vectory.ocdroid.data.repository.http

/**
 * V2: machine-readable error code constants emitted by the oc-slimapi
 * sidecar's thin-route error envelope (`{"code": "…"}`). Sources:
 *
 *  - **§0 / §6 G2** `GET /slimapi/sessions/{sid}/status` —
 *    [SESSION_NOT_FOUND] (404 → clear local), [DIRECTORY_NOT_ALLOWED]
 *    (400 → user prompt), [UPSTREAM_HTTP_PREFIX] (502 → alert, keep
 *    local), [UPSTREAM_UNAVAILABLE] (503 → backoff).
 *  - **§0 thin-route envelope** — [THIN_ROUTE_NOT_FOUND] for unmapped
 *    paths the sidecar refuses to forward.
 *
 * V1 removed codes (no triggering endpoint exists in V2):
 *  - [UPSTREAM_TIMEOUT]: q/p mutation 504 deleted (spec §7:243).
 *  - [INVALID_IDS]: G6 batch endpoint deleted (spec §7:231).
 *  - [INVALID_ROUTE_TOKEN]: routeToken deleted (spec §7:231).
 *  - [MESSAGE_NOT_FOUND]: G6 envelope mid-level code (spec §7:234).
 *
 * Centralised here (rather than inlined at each catch-site) so that the
 * HTTP-status routing logic in later tasks (L2 reducer / L3
 * `StatusAggregatorImpl` / `ProcessStatusPoller`) compares against a
 * single source of truth and the prefix check
 * `code.startsWith(UPSTREAM_HTTP_PREFIX)` is uniform. `upstream_http_<N>`
 * is a *prefix*, not a literal — the sidecar suffixes the upstream status
 * code (e.g. `upstream_http_500`); callers MUST use
 * [UPSTREAM_HTTP_PREFIX] + `startsWith`, never a literal equality check.
 *
 * **Write boundary**: this file is the ONLY place these wire strings
 * live. New codes added by future contract revisions land here first,
 * then are referenced from caller catch-sites (mirrors [SlimapiContract]'s
 * write-domain discipline).
 */
object SlimapiErrorCodes {

    /** §6 G2 / §5 G6 — session does not exist upstream → clear local cache. */
    const val SESSION_NOT_FOUND = "session_not_found"

    /** §6 G2 — directory query/parameter rejected → user-visible prompt. */
    const val DIRECTORY_NOT_ALLOWED = "directory_not_allowed"

    /** §6 G2 / §5 G6 — sidecar cannot reach opencode → backoff retry. */
    const val UPSTREAM_UNAVAILABLE = "upstream_unavailable"

    /**
     * §6 G2 — prefix for `upstream_http_<N>` codes (sidecar appends the
     * upstream HTTP status, e.g. `upstream_http_500`). Compare with
     * `code.startsWith(UPSTREAM_HTTP_PREFIX)`, NEVER with equality.
     */
    const val UPSTREAM_HTTP_PREFIX = "upstream_http_"

    // V2: UPSTREAM_TIMEOUT removed (spec §7:243 — q/p mutation 504 deleted).

    /**
     * §0 thin-route envelope — the sidecar has no mapping for the
     * requested legacy path (interface drift). Programming error.
     */
    const val THIN_ROUTE_NOT_FOUND = "thin_route_not_found"

    // V2: INVALID_IDS removed (spec §7:231 — G6 batch endpoint deleted).

    /**
     * §5 G6 — cumulative full-body size exceeded the cap → reduce `ids`
     * count and retry.
     */
    const val RESPONSE_TOO_LARGE = "response_too_large"

    /**
     * 🆕 B1 §2 #4 / contract §7 — single-message HARD cap on
     * `GET /slimapi/messages/{sid}/full/{mid}` default mode (`full`)
     * stream (default 32 MiB). Distinct from [RESPONSE_TOO_LARGE]:
     * halving the batch cannot help — the offending message itself is
     * too large at any batch size. `expandBatchInternal` routes the
     * whole batch's ids to `failedIds` on this code; the single-message
     * `fallbackSingleFull` path handles the same code by routing the id
     * to `failedIds` via `runSuspendCatching` + `onFailure`. The
     * `skeleton` mode on the same endpoint still emits
     * [RESPONSE_TOO_LARGE] against the 64 MiB aggregate cap — same
     * endpoint, different code by mode.
     */
    const val MESSAGE_TOO_LARGE = "message_too_large"

    /**
     * 🆕 B1 §2 #5 / contract §7 — catch-all shell and PTY route
     * deny-list (403): `POST /session/{sid}/shell`, `/pty/...`,
     * `/api/pty/...`. Slim mode does not invoke shell/PTY routes, so
     * this code is not actively branched on by the current client; it
     * is anchored here for exhaustiveness and to keep it out of the
     * generic `else -> Failed` bucket symbolically.
     */
    const val SHELL_NOT_ALLOWED = "shell_not_allowed"

    /**
     * 🆕 B1 §2 #7 / contract §7 — `GET /slimapi/questions` directory count
     * guard (400). The sidecar caps the number of `?directory=` repeated
     * query entries to the 1-32 range; below/above emits this code.
     * Programming error — fix the request shape and retry.
     */
    const val INVALID_DIRECTORY_COUNT = "invalid_directory_count"

    // V2: INVALID_ROUTE_TOKEN removed (spec §7:231 — routeToken deleted).

    /**
     * §5 G6 — sidecar's response-transform worker pool saturated →
     * backoff retry (transient).
     */
    const val TRANSFORM_BUSY = "transform_busy"

    // V2: MESSAGE_NOT_FOUND removed (spec §7:234 — G6 envelope code).

    /**
     * 🆕 B1 §2 S2 / contract §7 — catch-all path normalization rejected
     * `..` or `.` segment in the normalized path after collapse of
     * `//`. Terminal client error: do not retry, log and report.
     */
    const val INVALID_PATH = "invalid_path"

    /**
     * 🆕 B1 §2 S5 / contract §7 — `?directory=` or
     * `X-Opencode-Directory` header failed validation: contains `..`,
     * NUL, control characters, or exceeds 4096 bytes. Terminal client
     * error: do not retry, log and report.
     */
    const val INVALID_DIRECTORY = "invalid_directory"
}

// ════════════════════════════════════════════════════════════════════════════
// §F2: AuthFailureReason — sealed type + classifier for banner disambiguation.
// The banner must distinguish AUTH_FAILURE (401/403 / mTLS cert degradation)
// from REST_OUTAGE (transient 5xx / transport) so the user sees actionable
// "wrong credentials / cert problem" text instead of a generic "server down".
// ════════════════════════════════════════════════════════════════════════════

/**
 * §F2: sealed classification of WHY a connection attempt hit an auth-shaped
 * failure. Non-null on [ConnectionState.authFailureReason] drives the banner
 * to [BannerCategory.AUTH_FAILURE] (priority over REST_OUTAGE).
 *
 * Variants:
 *  - [MtlsDegraded] — client-cert material missing/corrupt (config-driven
 *    path; backward-compat with the legacy `mtlsDegradedError` String). The
 *    banner layer also accepts the raw String via a parallel param, but this
 *    variant lets a unified `authFailureReason` carry the same semantics.
 *  - [HttpAuth] — upstream HTTP 401/403 surfaced through the sidecar's
 *    `upstream_http_401` / `upstream_http_403` envelope (wrong Basic Auth,
 *    server-side auth denial, token revoked). `code` is the UPSTREAM status
 *    (401 or 403), NOT the sidecar's 502 wrapper. `message` is a best-effort
 *    human-readable detail (may be null when only the envelope code was
 *    available).
 */
sealed class AuthFailureReason {
    /** mTLS cert/credential degradation — [detail] is the config-path error string. */
    data class MtlsDegraded(val detail: String) : AuthFailureReason()

    /**
     * Upstream HTTP auth denial (401/403). [code] = upstream status;
     * [message] = best-effort detail (null when only the envelope code was
     * available — the UI then falls back to a default subtitle string).
     */
    data class HttpAuth(val code: Int, val message: String?) : AuthFailureReason()
}

/**
 * §F2: best-effort extract a display string from an [AuthFailureReason] for
 * the banner subtitle. Returns null when no meaningful detail is available
 * (e.g. envelope-only [AuthFailureReason.HttpAuth] with null message) — the
 * caller then falls back to the default resource string.
 */
fun AuthFailureReason?.displayString(): String? = when (this) {
    is AuthFailureReason.MtlsDegraded -> detail
    is AuthFailureReason.HttpAuth -> message
    null -> null
}

/**
 * §F2: pure classifier — maps a connection exception + optional sidecar
 * envelope code to an [AuthFailureReason], or null when the failure is NOT
 * auth-shaped (transient 5xx, transport IOException, authorization denial).
 * No store, no side-effect — fully unit-testable.
 *
 * Decision logic:
 *  1. `IOException` (and NOT `HttpException`) → null. Transport / TLS handshake
 *     failures (incl. SSLHandshakeException) are owned by the separate
 *     `mtlsDegradedError` config-path; re-classifying them here would double-
 *     count. `HttpException` is a `RuntimeException`, so the `is IOException`
 *     guard never matches it.
 *  2. Envelope code present (authoritative) → delegate to
 *     [classifyEnvelopeCode]: `shell_not_allowed` (authorization, NOT
 *     authentication) and transient codes (`upstream_unavailable` /
 *     `transform_busy` / `upstream_http_5xx`) return null; only
 *     `upstream_http_401` / `upstream_http_403` yield [AuthFailureReason.HttpAuth].
 *  3. No envelope → defense-in-depth fallback from the exception message.
 *     Covers the slim path (raw OkHttp throws `Exception("HTTP 401")`) and a
 *     hypothetical direct sidecar 401/403 (`HttpException.message = "HTTP 401 ..."`).
 *     Any status other than 401/403 returns null (conservative — never
 *     classify unknowns as auth).
 *
 * @param exception the failure exception (may be null if the probe failed
 *                  with healthy=false and no exception).
 * @param errorCode the sidecar envelope `{"code":"…"}` value if parsed by the
 *                  caller (e.g. via `OpenCodeRepository.parseErrorCode`); null
 *                  when no envelope was available.
 */
internal fun classifyAuthFailure(
    exception: Throwable?,
    errorCode: String?,
): AuthFailureReason? {
    // 1. Transport / TLS handshake — NOT HTTP auth. mtlsDegradedError owns
    //    cert-degradation surfacing; do not double-classify.
    if (exception is java.io.IOException) return null

    // 2. Envelope-driven classification (authoritative when present).
    if (errorCode != null) {
        return classifyEnvelopeCode(errorCode)
    }

    // 3. No envelope: defense-in-depth fallback from the exception message.
    val status = parseHttpStatusFromMessage(exception?.message)
    if (status == 401 || status == 403) {
        return AuthFailureReason.HttpAuth(status, exception?.message)
    }
    return null
}

/**
 * §F2: envelope-code branch of [classifyAuthFailure]. Exposed as a private
 * helper so the decision table is readable and testable in isolation.
 */
private fun classifyEnvelopeCode(errorCode: String): AuthFailureReason? = when {
    // Authorization (shell/PTY deny-list, 403) is NOT authentication.
    errorCode == SlimapiErrorCodes.SHELL_NOT_ALLOWED -> null
    // Transient upstream — stay REST_OUTAGE / retry.
    errorCode == SlimapiErrorCodes.UPSTREAM_UNAVAILABLE -> null
    errorCode == SlimapiErrorCodes.TRANSFORM_BUSY -> null
    // upstream_http_<N> — only 401/403 are auth; 5xx etc. are transient.
    errorCode.startsWith(SlimapiErrorCodes.UPSTREAM_HTTP_PREFIX) -> {
        val status = errorCode.removePrefix(SlimapiErrorCodes.UPSTREAM_HTTP_PREFIX).toIntOrNull()
        if (status == 401 || status == 403) {
            AuthFailureReason.HttpAuth(status, null)
        } else {
            null
        }
    }
    else -> null
}

/** §F2: regex for the slim-path message `HTTP <code>` and Retrofit's `HTTP <code> ...`. */
private val HTTP_STATUS_REGEX = Regex("HTTP (\\d+)")

/** §F2: extract the trailing HTTP status int from an exception message, or null. */
private fun parseHttpStatusFromMessage(message: String?): Int? {
    if (message == null) return null
    return HTTP_STATUS_REGEX.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
}
