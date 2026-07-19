package cn.vectory.ocdroid.data.repository.http

/**
 * Task 1 (slimapi v1 client L0): machine-readable error code constants
 * emitted by the oc-slimapi sidecar's thin-route error envelope
 * (`{"code": "…"}`). Sources:
 *
 *  - **§0 / §6 G2** `GET /slimapi/sessions/{sid}/status` —
 *    [SESSION_NOT_FOUND] (404 → clear local), [DIRECTORY_NOT_ALLOWED]
 *    (400 → user prompt), [UPSTREAM_HTTP_PREFIX] + [UPSTREAM_TIMEOUT]
 *    (502 → alert, keep local), [UPSTREAM_UNAVAILABLE] (503 → backoff).
 *  - **§5 G6** `GET /slimapi/messages/{sid}/full` — [INVALID_IDS] (400),
 *    [RESPONSE_TOO_LARGE] (413), [TRANSFORM_BUSY] / [UPSTREAM_UNAVAILABLE]
 *    (503), [SESSION_NOT_FOUND] (404, same handling as G2), and the
 *    per-message [MESSAGE_NOT_FOUND] carried inside `errors[]` of the
 *    G6 envelope.
 *  - **§0 thin-route envelope** — [THIN_ROUTE_NOT_FOUND] for unmapped
 *    paths the sidecar refuses to forward.
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

    /** §0 / §6 G2 — upstream call exceeded its deadline → alert, keep local. */
    const val UPSTREAM_TIMEOUT = "upstream_timeout"

    /**
     * §0 thin-route envelope — the sidecar has no mapping for the
     * requested legacy path (interface drift). Programming error.
     */
    const val THIN_ROUTE_NOT_FOUND = "thin_route_not_found"

    /**
     * §5 G6 — `ids` query param empty / >20 / unparseable → fix and retry.
     */
    const val INVALID_IDS = "invalid_ids"

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

    /**
     * 🆕 B1 §2 #7 / contract §7 — `routeToken` HMAC verification failed
     * (400). The sidecar HMAC-signs `kind+requestID+sessionID+directory`
     * with ~1h TTL; reply/reject/permission-respond mutations check it
     * on receipt. Failure means the token is malformed, stale, or for a
     * different scope — re-fetch the aggregation (`/slimapi/questions`
     * or `/slimapi/permissions`) to obtain a fresh token.
     */
    const val INVALID_ROUTE_TOKEN = "invalid_route_token"

    /**
     * §5 G6 — sidecar's response-transform worker pool saturated →
     * backoff retry (transient).
     */
    const val TRANSFORM_BUSY = "transform_busy"

    /**
     * §5 G6 — a single `id` inside the batch could not be resolved;
     * surfaces inside the G6 envelope's `errors[]` array (HTTP stays
     * 200). The client marks just that message's expand as failed.
     */
    const val MESSAGE_NOT_FOUND = "message_not_found"
}
