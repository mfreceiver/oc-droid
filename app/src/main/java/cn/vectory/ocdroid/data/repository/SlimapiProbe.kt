package cn.vectory.ocdroid.data.repository

/**
 * §slimapi-client-impl-v1 §4 (G3 probeLatestMessageId 收敛): boundary-normalised
 * result of a slim-mode "latest message" probe against the sidecar
 * (`GET /slimapi/messages/{sid}?limit=1&mode=skeleton`).
 *
 * Every probe outcome collapses into exactly one [ProbeResult] shape so the
 * reconcile state machine (T7 pure functions in this same file — added later;
 * T11 resync loop) can pattern-match without touching `retrofit2.Response`
 * or exception types. Bare `probe[0]` access is forbidden downstream — every
 * read site goes through this type.
 *
 * ## Branch table (set by `OpenCodeRepository.probeLatestSlim`)
 *
 * | outcome                       | [ok]  | [empty] | [messageID] | [updatedAt] | [httpStatus] |
 * | ---                          | ---   | ---     | ---         | ---         | ---          |
 * | 200 + empty array            | true  | true    | null        | null        | null         |
 * | 200 + one item               | true  | false   | info.id     | `time.updated ?: time.created` | null |
 * | 200 + null body (defensive)  | false | false   | null        | null        | resp.code()  |
 * | HTTP 4xx/5xx                 | false | false   | null        | null        | resp.code()  |
 * | Network/IO failure           | false | false   | null        | null        | null         |
 *
 * The HTTP-fail vs network-fail split is what lets the reconcile state
 * machine decide between "sid is gone upstream" (`httpStatus == 404` → mark
 * deleted) and "transport is flaky" (`httpStatus == null` → keep dirty,
 * retry next pass).
 *
 * ## Purity
 *
 * Deliberately a plain Kotlin data class with NO `retrofit2.Response` /
 * `okhttp3.*` reference. T7 will add pure helper functions
 * (`needsCatchUp(sessionId, probe)`, etc.) to this same file; keeping the
 * type pure is what makes those helpers unit-testable without a MockWebServer.
 */
data class ProbeResult(
    /** Whether the probe successfully reached the server AND got a 2xx. */
    val ok: Boolean,
    /**
     * True only when [ok] is true AND the latest-messages array was empty
     * (the session exists upstream but has no messages yet). Always false
     * on the failure branches.
     */
    val empty: Boolean = false,
    /**
     * `info.id` of the single skeleton returned (limit=1). Null on the empty
     * branch and every failure branch.
     */
    val messageID: String? = null,
    /**
     * `info.time.updated ?: info.time.created` from the returned skeleton —
     * the watermark the reconcile state machine compares against the local
     * SSE bookmark to decide whether catch-up is needed.
     */
    val updatedAt: Long? = null,
    /**
     * Raw HTTP status code on the HTTP-fail branch and the defensive
     * "2xx + null body" branch; null on the success branch and the
     * network-failure branch.
     */
    val httpStatus: Int? = null,
)

// ---------------------------------------------------------------------
// T7 pure reconcile primitives (§slimapi-client-impl-v1 §3)
//
// These intentionally take the already-extracted local watermarks
// (`localAppliedId` / `localAppliedTs`) rather than the whole
// `SlimSessionState`, so they stay unit-testable without constructing
// the full state machine. The T11 reconcileSession wiring is
// responsible for reading those two fields off `SlimSessionState`
// (the split-watermark fields added in T6) and passing them in.
// ---------------------------------------------------------------------

/**
 * §3 `needsCatchUp(probe, localAppliedId, localAppliedTs)` — decides
 * whether the focus/resync loop must pull `GET /slimapi/messages/{sid}`
 * (or `/since/{ts}`) for a session, given a probe result plus the
 * caller's last-applied watermarks.
 *
 * Evaluation order is fixed by §3 (DO NOT reorder):
 *
 * 1. `probe.ok == false` → `false`. The probe failed (HTTP 4xx/5xx or
 *    network/IO). The caller MUST keep this session in `dirty` and
 *    retry next pass; claiming "catch-up needed" here would just mask
 *    a transport failure as "aligned" or trigger a noisy REST storm.
 * 2. `probe.empty` OR `probe.messageID == null` → `false`. Either the
 *    session genuinely has no messages upstream, or the probe returned
 *    a defensive null id — either way there is nothing to catch up to.
 *    (The §3 caller-side "clear local cache if local had messages"
 *    side-effect is intentionally NOT encoded here; this function only
 *    answers the catch-up boolean.)
 * 3. `localAppliedId == null` → `true`. We've never applied any message
 *    for this session locally; the server definitely has more than us.
 * 4. `probe.messageID != localAppliedId` → `true`. Server's latest
 *    skeleton id differs from the last one we applied — out of date.
 * 5. `probe.updatedAt != null` AND (`localAppliedTs == null` OR
 *    `probe.updatedAt > localAppliedTs`) → `true`. Same message id but
 *    the server's activity watermark is newer (or we have no ts
 *    watermark to compare against).
 * 6. Otherwise → `false`. Aligned; no REST pull needed.
 *
 * Pure: no `suspend`, no IO, no DI, no Android deps.
 */
fun needsCatchUp(
    probe: ProbeResult,
    localAppliedId: String?,
    localAppliedTs: Long?,
): Boolean {
    if (!probe.ok) return false
    if (probe.empty || probe.messageID == null) return false
    if (localAppliedId == null) return true
    if (probe.messageID != localAppliedId) return true
    if (probe.updatedAt != null && (localAppliedTs == null || probe.updatedAt > localAppliedTs)) {
        return true
    }
    return false
}

/**
 * §3 `catchUpSet(focus, localAll, dirty)` — the resync catch-up set is
 * the union of:
 *
 *  - `focus` (the currently-open session id, if any — may be null),
 *  - `localAll` (every locally-known session id — the cached list /
 *    store full set),
 *  - `dirty` (sessions explicitly marked for re-alignment).
 *
 * §3 explicitly forbids the degenerate `focus ∪ dirty` shortcut: a
 * non-focus, non-dirty session may still have stale content and MUST
 * be re-probed on resync. Duplicates across the three sources are
 * collapsed (the result is a [Set]).
 *
 * Pure: no `suspend`, no IO, no DI, no Android deps.
 */
fun catchUpSet(
    focus: String?,
    localAll: Collection<String>,
    dirty: Collection<String>,
): Set<String> = buildSet {
    if (focus != null) add(focus)
    addAll(localAll)
    addAll(dirty)
}
