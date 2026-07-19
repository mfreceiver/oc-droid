package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.MessageWithParts

/**
 * §slimapi-client-impl-v1 §5 G6 (Task 3) — boundary-normalised outcome
 * of an expand-multiple-messages-full call
 * ([OpenCodeRepository.expandMessagesFullBatch]). Every retry / halve /
 * backoff / fallback decision the repository makes internally collapses
 * into exactly one of these three shapes so the UI (T15 usecase +
 * T16 MessageRow) never pattern-matches on `retrofit2.Response` / HTTP
 * status / error-code strings.
 *
 * ## Branch table (set by `OpenCodeRepository.expandMessagesFullBatch`)
 *
 * | outcome                                  | type            | notes |
 * | ---                                      | ---             | ---   |
 * | 200 + envelope                           | [Ok]            | `items` resolved; per-message failures ride in `failedIds` (HTTP stays 200 even when some ids fail) |
 * | 404 + `session_not_found`                | [SessionMissing]| the entire session is gone upstream — UI clears local cache (mirrors G2 status handling) |
 * | 404 + `thin_route_not_found` (transitional) | [Ok]         | sidecar hasn't deployed the batch endpoint; repo falls back to N parallel single-full calls (`usedBatch = false`) |
 * | 404 (other)                              | [Failed]        | programming error / unmapped route — NO fallback |
 * | 413 after exhaustive halving             | [Failed]        | even single-id is too large upstream |
 * | 503 after exhaustive backoff             | [Failed]        | repo exhausted 3 retries with exponential backoff |
 * | 400 / 422 / other 4xx 5xx                | [Failed]        | bad request — fix and retry upstream |
 * | Network / IO failure                     | [Failed]        | `code = null` (no sidecar envelope) |
 *
 * ## Purity
 *
 * Deliberately a plain Kotlin sealed interface with NO `retrofit2.Response`
 * / `okhttp3.*` / `SlimapiMessageFullBatch` reference on its public surface.
 * T15 (usecase) and T16 (UI) pattern-match on this type — keeping it pure
 * is what makes those layers unit-testable without a MockWebServer and
 * avoids leaking HTTP/Retrofit types into the ViewModel tier (same
 * discipline as [ProbeResult]).
 */
sealed interface ExpandOutcome {
    /**
     * At least one batch attempt returned a usable result (200 envelope
     * OR the per-id fallback path). [items] carries every resolved
     * [MessageWithParts] in the request's (deduped) order; [failedIds]
     * carries the message ids that did NOT resolve (either per-message
     * errors from the 200 envelope's `errors[]`, or per-id transport
     * failures from the fallback path).
     *
     * [usedBatch] distinguishes the batched-success path (`true`, used
     * `GET /slimapi/messages/{sid}/full?ids=…`) from the per-id fallback
     * path (`false`, used N × `GET /slimapi/messages/{sid}/full/{mid}`).
     * T15/T16 use this as a telemetry hook to track how often the
     * transitional fallback fires (drives prioritising server-side
     * batch endpoint deployment).
     */
    data class Ok(
        val items: List<MessageWithParts>,
        val failedIds: List<String>,
        val usedBatch: Boolean,
    ) : ExpandOutcome

    /**
     * The session is gone upstream (HTTP 404 + `session_not_found`).
     * The UI mirrors the G2 status handling: clear the local cache +
     * remove from the session list. Distinct from [Failed] so the UI
     * does not show a generic "expand failed" banner on a session
     * that no longer exists.
     */
    data class SessionMissing(val sessionId: String) : ExpandOutcome

    /**
     * Every other failure (other 4xx, 413 after exhaustive halving,
     * 503 after exhaustive backoff, network/IO failure, malformed
     * body). [code] carries the sidecar's machine-readable error code
     * from `{"code": "…"}` when available; null on transport failure
     * or unparseable body (UI surfaces a generic "expand failed"
     * affordance with retry).
     */
    data class Failed(val sessionId: String, val code: String?) : ExpandOutcome
}
