package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.MessageWithParts

/**
 * §slimapi-client-impl-v1 §5 G6 (Task 3) — boundary-normalised outcome
 * of an expand-multiple-messages-full call
 * ([OpenCodeRepository.expandMessagesFullBatch]). lite-v2 runs a plain
 * N×/full loop: one `GET /slimapi/messages/{sid}/full/{mid}` per requested
 * id, with no batch engine and no retry/halving/backoff (the V1
 * ExpandBatchEngine + batch endpoint were RETIRED — see the retire note in
 * OpenCodeRepository above expandMessagesFullBatch). The loop collapses
 * into exactly one of these three shapes so the UI (T15 usecase +
 * T16 MessageRow) never pattern-matches on `retrofit2.Response` / HTTP
 * status / error-code strings.
 *
 * ## Branch table (set by `OpenCodeRepository.expandMessagesFullBatch`)
 *
 * | outcome                                  | type              | notes |
 * | ---                                      | ---               | ---   |
 * | ≥1 id resolves (200 envelope)            | [Ok]              | `items` resolved; per-message failures ride in `failures` with their parsed `{"code":…}` (HTTP stays 200 even when some ids fail) |
 * | 404 + `session_not_found` (any id)       | [SessionMissing]  | the entire session is gone upstream — UI clears local cache (mirrors G2 status handling); a single occurrence is sufficient since a missing session 404s every per-id call |
 * | every id fails (non-session_not_found)   | [Failed]          | representative `code` = first non-null envelope code; `null` when all failures are transport-level IOException. UI shows generic "expand failed" affordance with retry |
 * | Network / IO failure only                | [Failed]          | `code = null` (no sidecar envelope) |
 *
 * The `usedBatch` flag on [Ok] is always `false` in lite-v2 (per-id loop,
 * never the retired batch endpoint) and is retained only as a telemetry
 * hook for the transitional batch path that no longer exists.
 *
 * ## Legacy fallback — absent by design
 *
 * There is intentionally NO legacy `GET /session/{sid}/message` fallback.
 * The catalog's 404-fallback rule described the old batch→per-id
 * transition (slim-mode-api-routing.md §5.4 G6), which is itself retired;
 * the client is already on the per-id path and the sidecar is the sole
 * slim transport. See [OpenCodeRepository.expandMessagesFullBatch] KDoc
 * for the full rationale.
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
     * Represents a single per-message failure from the envelope or from retry exhaustion.
     * Carries both the [messageId] and the [code] from the envelope error,
     * or [code] = null for transport-level failure / exhaustion.
     */
    data class MessageFailure(
        val messageId: String,
        val code: String?,
    )

    /**
     * At least one requested id resolved. [items] carries every resolved
     * [MessageWithParts] in the request's (deduped) order; [failures]
     * carries the per-message failures that did NOT resolve, each with its
     * parsed sidecar envelope `{"code":…}` (or `null` for transport-level
     * IOException).
     *
     * [usedBatch] is always `false` in lite-v2 (the per-id N×/full loop is
     * the only path; the retired batch endpoint `/full?ids=` no longer
     * exists). The flag is retained as a telemetry hook only.
     */
    data class Ok(
        val items: List<MessageWithParts>,
        val failures: List<MessageFailure>,
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
     * Every requested id failed to resolve (and none failed with
     * `session_not_found`, which yields [SessionMissing] instead).
     * Covers other 4xx/5xx, network/IO failure, and malformed body.
     * [code] carries the sidecar's machine-readable error code from
     * `{"code": "…"}` when available — chosen as the first non-null code
     * among the per-id failures; `null` when every failure was
     * transport-level IOException (UI surfaces a generic "expand failed"
     * affordance with retry).
     *
     * [exhausted] is a vestigial flag retained for API stability: in
     * lite-v2 there is no budget/partition machinery to set it `true`
     * (the V1 ExpandBatchEngine that produced it was RETIRED), so it is
     * always `false`. The consumer still tolerates `true` defensively.
     */
    data class Failed(
        val sessionId: String,
        val code: String?,
        val exhausted: Boolean = false,
    ) : ExpandOutcome
}
