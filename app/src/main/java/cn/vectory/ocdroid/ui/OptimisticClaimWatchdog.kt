package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.state.AuthorityState
import cn.vectory.ocdroid.data.state.ScopeKey

/**
 * §P0-B ITEM 4: pure watchdog support for the Tier-2 confirmation gate.
 *
 * [selectStaleClaimsForReconcile] is a PURE function (no clock read, no injected
 * dep — [now] is a parameter) that scans [authority] for stale unconfirmed
 * optimistic claims, returning the sids + scopeKeys whose claim age exceeds
 * [timeoutMs].
 *
 * Same `(authority, now, timeoutMs)` always yields the same output — pure/testable.
 */
const val OPTIMISTIC_CONFIRM_TIMEOUT_MS = 5_000L

/**
 * A stale claim identified by the watchdog. Used to carry both the sid and its
 * scopeKey (so the reconcile sink does not need to re-lookup the entry).
 *
 * @property clientSeq §P0-B generation fence: the [OptimisticClaim.clientSeq] at detection.
 */
data class StaleClaim(
    val sid: String,
    val scopeKey: ScopeKey,
    val clientSeq: Long,
)

/**
 * Pure: scan [authority.bySid] for entries whose [SessionEntry.optimisticClaim]
 * is non-null AND not [OptimisticClaim.serverEchoed] AND whose age
 * (`now - claimedAtMonotonic`) STRICTLY exceeds [timeoutMs].
 *
 * Returns a list of [StaleClaim] tuples. Empty when no stale claims found.
 *
 * ## Strict inequality
 * `age > timeoutMs` (not `>=`). At exact [timeoutMs] the claim is NOT yet
 * considered stale — the next tick (with a later [now]) triggers reconcile.
 * This avoids a race where a just-expired claim is reconciled before the
 * confirmation gate has had a chance to process the echo.
 */
internal fun selectStaleClaimsForReconcile(
    authority: AuthorityState,
    now: Long,
    timeoutMs: Long = OPTIMISTIC_CONFIRM_TIMEOUT_MS,
): List<StaleClaim> {
    val stale = mutableListOf<StaleClaim>()
    for ((sid, entry) in authority.bySid) {
        val claim = entry.optimisticClaim ?: continue
        if (claim.serverEchoed) continue
        val age = now - claim.claimedAtMonotonic
        if (age > timeoutMs) {
            stale.add(StaleClaim(sid = sid, scopeKey = entry.scopeKey ?: continue, clientSeq = claim.clientSeq))
        }
    }
    return stale
}
