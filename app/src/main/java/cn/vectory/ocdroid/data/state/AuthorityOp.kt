package cn.vectory.ocdroid.data.state

import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.service.identity.ConnectionIdentity

/**
 * §P0-A (B1 option 1): the typed sealed op set that funnels EVERY session
 * status write into the pure reducer [cn.vectory.ocdroid.ui.reduceAuthority].
 * A single [AppAction.AuthorityEvent] wraps one [AuthorityOp]; dispatch lands
 * it in the single CAS via `state.update { reduce(it, action) }`.
 *
 * Purity contract: an [AuthorityOp] is pure DATA. It carries every value the
 * reducer needs (snapshots, timestamps, captured identity, request tokens)
 * so the reducer needs NO injected dependency and NO clock read. Same
 * `(state, op)` → same output (CAS retry idempotent, rev-gpt B1 gate).
 */
sealed interface AuthorityOp {

    /**
     * A single status EVENT (SSE status / digest / optimistic-on-send).
     *
     * - [origin] drives §B9 classification + the reducer's fence selection.
     * - [serverRound]: slimapi `(incarnation, turn)` when available; null for
     *   legacy SSE / optimistic (no causal fence for those in P0-A).
     * - [capturedIdentity]: §B11 event-captured identity (P0-C scope guard).
     *   P0-A carries it but the reducer does not yet gate on it.
     * - [connectionMonotonicMs]: TTL / equal-serverRound tie-break clock. NOT a
     *   causal fence by itself (v1 mis-used; v3 corrected — §3.1 line 324).
     * - [optimisticBumpTimestamp]: §B8 — when non-null, the reducer records it
     *   in [AuthorityState.pendingBumps] and applies `bumpSessionUpdated` in
     *   the SAME `state.copy` (no loss, no conflation).
     */
    data class ApplyEvent(
        val sid: String,
        val status: SessionStatus,
        val origin: EntryOrigin,
        val serverRound: ServerRound? = null,
        val capturedIdentity: ConnectionIdentity? = null,
        val scopeKey: ScopeKey,
        val connectionMonotonicMs: Long,
        val workdir: String? = null,
        val optimisticBumpTimestamp: Long? = null,
    ) : AuthorityOp

    /**
     * A whole-graph REST / TREE authoritative snapshot replacement WITHIN the
     * covered scope. This is the op behind the 4 REST/hydration writer paths
     * (StatusPollOrchestrator legacy + slim, BackgroundUnreadPoller,
     * SessionTreeHydrator, launchLoadChildSessions).
     *
     * Carries the raw inputs the reducer needs to reproduce the EXACT legacy
     * behavior:
     *  - [snapshot]: the raw fetched status map (REST omits idle entries).
     *  - [authoritativeNodeIds]: the proven tree node set — the reducer
     *    `normalizeAuthoritativeStatusSnapshot` idle-fills any node missing
     *    from [snapshot] (absence-of-known-node ≡ idle); ids OUTSIDE this set
     *    stay absent (fail-closed unknown).
     *  - [localBefore]: the projection captured at request START — drives the
     *    REST in-flight protection (an SSE update that landed during the REST
     *    round-trip must not be clobbered by the stale REST snapshot).
     *  - [sidToWorkdir] / [registeredWorkdirs] / [coveredWorkdirs] /
     *    [unmappedActiveIds] / [partialFailureWorkdirs] / [lastSuccessTimeMs]:
     *    §B3 coverage bookkeeping + slim failed-directory preservation.
     *  - [requestToken]: host + epoch captured at request start (reducer host
     *    guard — defense-in-depth on top of the caller's single-flight guard).
     */
    data class ApplySnapshot(
        val snapshot: Map<String, SessionStatus>,
        val sidToWorkdir: Map<String, String>,
        val authoritativeNodeIds: Set<String>,
        val registeredWorkdirs: Set<String>,
        val coveredWorkdirs: Set<String>,
        val unmappedActiveIds: Set<String>,
        val partialFailureWorkdirs: Set<String>,
        val lastSuccessTimeMs: Long,
        val scopeKey: ScopeKey,
        val requestToken: RequestToken,
        val localBefore: Map<String, SessionStatus>,
    ) : AuthorityOp

    /** §4c.3 host purge (mirror of reduceHostStatePurged). [preserveServerGroup]
     *  true → keep (same-group); false → clear bySid/coverage/incarnations for
     *  [scopeKey]. (P0-A host-purge path resets authority directly in the
     *  reducer copy; this op is implemented for typed completeness.) */
    data class PurgeHost(
        val scopeKey: ScopeKey,
        val preserveServerGroup: Boolean,
    ) : AuthorityOp

    /** REST source failure → covered entries become fail-closed unknown (absent
     *  in the projection). [requestToken] carries the host/epoch guard.
     *
     *  §P0-A Lane 2 (aggregator derivation): the aggregator's
     *  `markRequestFailed` adapter dispatches this op. The reducer applies
     *  MERGE TIMING — entries fresher than [monotonic] (the failure's effective
     *  time) survive (a prior SSE `Busy`/`Retry` is NOT clobbered by a stale
     *  failure); entries with `updatedMonotonic <= monotonic` are REMOVED
     *  (absence ≡ unknown, fail-closed). [registeredWorkdirs] is carried so the
     *  coverage predicate can keep gating `AllIdleFresh` (registered set
     *  preserved, coveredWorkdirs emptied, lastSuccessTimeMs=-1 → cold-start /
     *  stale guard fires → derived `project()` returns `Unknown`). */
    data class MarkSourceFailed(
        val scopeKey: ScopeKey,
        val requestToken: RequestToken,
        val monotonic: Long,
        val registeredWorkdirs: Set<String>,
    ) : AuthorityOp

    /** §B7 REST reconcile terminal outcome (watchdog / explicit reconcile). */
    data class ApplyReconcileOutcome(
        val sid: String,
        val scopeKey: ScopeKey,
        val outcome: ReconcileOutcome,
        val serverRound: ServerRound?,
        val monotonic: Long,
    ) : AuthorityOp

    /** §B5 prune: drop [sids] from bySid (delete / archive lifecycle). */
    data class PruneSessions(
        val sids: Set<String>,
        val scopeKey: ScopeKey,
    ) : AuthorityOp
}

/**
 * Host + epoch captured at REST request start. The reducer's
 * [cn.vectory.ocdroid.ui.opScopeValid] checks BOTH [hostProfileId] (vs
 * `state.host.currentHostProfileId`) AND [identityEpoch] (vs
 * [cn.vectory.ocdroid.ui.StoreState.identityEpoch]) — defense-in-depth inside
 * the CAS: the caller's own single-flight guard (`statusLoadEpoch` AtomicLong /
 * `completenessEpoch`) remains authoritative for stale-request dropping, and
 * the adapter's dispatch-side `identityStore.currentEpoch()` check catches
 * endpoint/workdir-only reconfigures.
 *
 * [hostProfileId] is nullable: the host may legitimately be unset on cold
 * start; null ≡ "could not determine" → the reducer guards leniently
 * (null currentHost passes), matching the legacy `!=` null==null semantics.
 *
 * [identityEpoch] is [cn.vectory.ocdroid.ui.StoreState.identityEpoch] captured
 * at request START (before the fetch) — NOT the current value (kills the
 * TOCTOU where a host/identity switch lands mid-fetch).
 *
 * §P0-A rev-gpt r2 #3: the dead `epoch` field (never read by the reducer —
 * it reads [identityEpoch]) was REMOVED. The [requestStartMs] is the
 * per-entry `updatedMonotonic` source.
 */
data class RequestToken(
    val hostProfileId: String?,
    val identityEpoch: Long = 0L,
    val requestStartMs: Long,
)

/** §B7 REST reconcile outcome classification. */
enum class ReconcileOutcome {
    IDLE_CONFIRMED,
    BUSY_CONFIRMED,
    FETCH_FAILED,
}
