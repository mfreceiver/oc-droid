package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.SessionStatus

/**
 * §slimapi-client-impl-v1 §6 G2 (Task 4) — boundary-normalised outcome of a
 * per-session status fetch
 * ([OpenCodeRepository.getSlimapiSessionStatusOutcome]).
 *
 * Every HTTP branch the sidecar can return for
 * `GET /slimapi/sessions/{sid}/status` collapses into exactly one of these
 * five shapes so the caller (T7 reconcile / T11 StatusAggregator) never
 * pattern-matches on `retrofit2.Response` / HTTP status / error-code
 * strings. Mirrors the [ExpandOutcome] discipline (T3 G6).
 *
 * ## Branch table (set by `OpenCodeRepository.getSlimapiSessionStatusOutcome`)
 *
 * | HTTP | code                       | outcome           | notes |
 * | ---  | ---                        | ---               | ---   |
 * | 200  | n/a                        | [Success]         | carries the raw [SessionStatus] — idle/busy/retry preserved as-is (NOT folded; see T4-C2) |
 * | 404  | `session_not_found` (or any code — HTTP wins) | [SessionMissing]  | session is gone upstream → caller clears local cache + removes from list |
 * | 400  | `directory_not_allowed`    | [DirectoryError]  | directory config error → caller prompts the user |
 * | 502  | `upstream_http_<N>` etc.   | [UpstreamWarn]    | upstream 4xx/5xx surfaced via the sidecar → alert, keep local |
 * | 503  | `upstream_unavailable`     | [Retry]           | transient sidecar/upstream fault → caller backs off and retries |
 * | network/IO | n/a                  | [Retry]           | `code = null` — distinguishable from 503 so callers can log "transport" vs "server busy" |
 *
 * ## Why `Success` carries the raw status (T4-C2)
 *
 * The contract (`§6` G2) warns about **false idle** risk — the status map
 * can be stale while the session is actually deleted upstream. The
 * reconcile layer (T7/T11) cross-checks an idle [Success] against the
 * sessions list before trusting it. Folding idle → SessionMissing here
 * would (a) hide a real idle status the UI needs to render and (b) break
 * the contract's "200 = status map speaks truth, caller validates"
 * invariant. So [Success.status] is the raw deserialised body — idle
 * stays idle, busy stays busy, retry stays retry.
 *
 * ## Purity
 *
 * Deliberately a plain Kotlin sealed interface with NO `retrofit2.Response`
 * / `okhttp3.*` reference on its public surface (same discipline as
 * [ExpandOutcome] / [ProbeResult]) so the reconcile layer stays
 * unit-testable without a MockWebServer.
 */
sealed interface StatusOutcome {
    /**
     * 200 + the sidecar's status payload. The status is the raw
     * [SessionStatus] deserialised from `{"type":"…"}` — idle / busy /
     * retry are preserved as-is so the caller can render and cross-check
     * (T4-C2: idle is NOT folded to SessionMissing here).
     */
    data class Success(
        val sessionId: String,
        val status: SessionStatus,
    ) : StatusOutcome

    /**
     * 404 (any code; contract pins `session_not_found`). The session is
     * gone upstream → caller clears local cache + removes from list.
     * Distinct from [Retry] / [UpstreamWarn] so the caller does NOT
     * alert or retry on a session that's simply deleted.
     */
    data class SessionMissing(val sessionId: String) : StatusOutcome

    /**
     * 400 + `directory_not_allowed`. Directory config error → caller
     * prompts the user (this is a deterministic misconfiguration, not a
     * transient fault).
     */
    data class DirectoryError(val sessionId: String) : StatusOutcome

    /**
     * 502 + `upstream_http_<N>` (or any other non-pinned non-transient
     * HTTP failure). Upstream spoke to the sidecar but the sidecar's own
     * upstream returned a status worth surfacing — alert, keep local.
     * [code] carries the sidecar's machine-readable error code for
     * observability (callers log it).
     */
    data class UpstreamWarn(
        val sessionId: String,
        val code: String?,
    ) : StatusOutcome

    /**
     * 503 + `upstream_unavailable` (transient sidecar/upstream fault)
     * OR a network/IO failure (transport-level, no sidecar envelope →
     * `code = null`). Caller backs off and retries. [code] is non-null
     * on HTTP 503 (sidecar's `upstream_unavailable`) and null on
     * transport failure — the split lets callers log "server busy" vs
     * "network" if they want.
     */
    data class Retry(
        val sessionId: String,
        val code: String?,
    ) : StatusOutcome
}
