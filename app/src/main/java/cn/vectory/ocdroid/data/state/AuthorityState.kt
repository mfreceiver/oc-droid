package cn.vectory.ocdroid.data.state

import cn.vectory.ocdroid.data.model.SessionStatus

/**
 * §P0-A (B1 option 1): the SINGLE authoritative source of truth for session
 * execution status. Lives as a slice on [cn.vectory.ocdroid.ui.StoreState];
 * the UI-facing `SessionListState.sessionStatuses` map is now a PROJECTION
 * computed by the PURE reducer [cn.vectory.ocdroid.ui.reduceAuthority] in the
 * same `state.copy` (single CAS). Every status write funnels through
 * `dispatch(AppAction.AuthorityEvent(AuthorityOp))`.
 *
 * # Purity / CAS-idempotency (B1, the rev-gpt gate)
 *
 * This type is pure DATA. The reducer that mutates it
 * ([cn.vectory.ocdroid.ui.reduceAuthority]) is a pure function: it reads
 * `(state, op)` and returns a new [StoreState] with a new [AuthorityState]
 * slice + the recomputed projection. It performs NO I/O, calls NO injected
 * dependency, reads NO wall-clock (timestamps are carried IN the op / state),
 * and mutates none of its inputs. The same `(state, op)` therefore always
 * yields the same output, so the single CAS retry
 * (`state.update { reduce(it, action) }`) is idempotent — re-running the pure
 * reducer on a retried snapshot reproduces the same transition.
 *
 * # absence semantics (M9 / §2.3)
 *
 * The projection is `bySid.mapValues { it.value.status }`: an id PRESENT in
 * [bySid] appears in `sessionStatuses`; an id ABSENT from [bySid] is ABSENT
 * from `sessionStatuses` → fail-closed unknown for the single absence-reader
 * (`UnreadSoakController`: `sid !in sessionStatuses`). [applySnapshot]
 * (REST/TREE) normalizes the authoritative tree (missing nodes → idle) so a
 * loaded subtree node is always present; prune (delete/archive) removes ids.
 */
data class AuthorityState(
    /** Per-sid authority entry. Absence ≡ unknown (fail-closed). */
    val bySid: Map<String, SessionEntry> = emptyMap(),
    /** §B6 per-scope incarnation high-water (slimapi `(incarnation,turn)` fence). */
    val knownIncarnations: Map<ScopeKey, Long> = emptyMap(),
    /** §B3 whole-graph coverage bookkeeping (registered/covered workdirs, …). */
    val coverage: Map<ScopeKey, Coverage> = emptyMap(),
    /** §B8 optimistic bump timestamps awaiting application to `sessions` in the
     *  same CAS (consumed by [cn.vectory.ocdroid.ui.applyOptimisticBumps]). */
    val pendingBumps: Map<String, Long> = emptyMap(),
    /** §P1-B/E: bounded retry queue, keyed by sid. Bounded by RETRY_QUEUE_MAX_SIZE
     *  (enforced in applyRetryQueued — oldest entries evicted LRU-style when full).
     *  Cleaned on terminal status (idle/failed) in applyEvent. Pure bookkeeping. */
    val retryQueue: Map<String, RetryEntry> = emptyMap(),
) {
    companion object {
        /** Empty default so the [cn.vectory.ocdroid.ui.StoreState] slice seeds cleanly. */
        operator fun invoke(): AuthorityState = AuthorityState()
    }
}

/**
 * A single sid's authority entry. The [status] is the projection source;
 * the rest are fence/coverage metadata (causal fence, optimistic claim,
 * origin classification, freshness) that the (P0-B+) reducers consult but
 * that the UI projection ignores.
 */
data class SessionEntry(
    val status: SessionStatus,
    /** §3.1 slimapi `(incarnation, turn)` strong fence; null for legacy/optimistic/REST. */
    val serverRound: ServerRound?,
    /** §3.1 Tier-2 optimistic confirmation gate (POST success before SSE busy echo). */
    val optimisticClaim: OptimisticClaim?,
    /** How this entry's value arrived — drives §B9 ServerBusy classification. */
    val origin: EntryOrigin,
    val freshness: Freshness,
    /** TTL / equal-serverRound tie-break clock (NOT a causal fence). Carried in the op. */
    val updatedMonotonic: Long,
    /** §B3 workdir attribution (filled/updated by ApplySnapshot.sidToWorkdir). */
    val workdir: String?,
    /** §P0-A rev-gpt r2 #6: the scope this entry was written under (ApplyEvent/
     *  ApplySnapshot stamp it from op.scopeKey). Used by applyMarkFailed to
     *  filter survivors by REAL scope (not workdir approximation). Null on
     *  entries created before the field was added (backward-compat). */
    val scopeKey: ScopeKey? = null,
    /** §3.1 BLK-2: per-sid serverRound HIGH-WATER — the lexicographically-greatest
     *  `(incarnation, turn)` ever accepted for this sid. Unlike the live [serverRound]
     *  baseline, this watermark SURVIVES baseline clears (REST [applySnapshot],
     *  legacy SSE busy keepRound=null, incarnation-advance scope reset) so that a
     *  stale low-turn Tier-1 slim frame arriving AFTER the baseline was cleared is
     *  still fenced (strict-monotonic DROP). Advanced only forward (never regressed);
     *  a new server incarnation naturally dominates it via [ServerRound.compareTo].
     *  Null on cold start (the first slim frame establishes the baseline). */
    val serverRoundHighWater: ServerRound? = null,
)

/**
 * §P1-B/E: a session queued for bounded retry. Lives in
 * [AuthorityState.retryQueue] keyed by sid. Pure bookkeeping — the actual
 * retry trigger is external (SlimStatusFanOut retryableCount → poller backoff);
 * this entry makes the queued-retry state queryable, bounded, and cleaned on
 * terminal status.
 *
 *  - [attempt]: 1-based retry attempt counter (RetryQueued stamps it).
 *  - [backoffMs]: the backoff the caller requested for THIS attempt (carried in
 *    RetryQueued; the reducer does NOT compute backoff — it is pure state).
 *  - [queuedMonotonic]: clock captured at RetryQueued dispatch (for observability;
 *    the reducer needs no injected clock).
 */
data class RetryEntry(
    val attempt: Int,
    val backoffMs: Long,
    val queuedMonotonic: Long,
)

/**
 * §3.1 slimapi per-`(serverGroupFp, sid)` monotonic execution-generation token.
 * Lexicographic compareBy(incarnation, turn). NEVER compared against
 * [OptimisticClaim.clientSeq] (separate count spaces — M1 root cause).
 */
data class ServerRound(
    val incarnation: Long,
    val turn: Long,
) : Comparable<ServerRound> {
    override fun compareTo(other: ServerRound): Int =
        compareValuesBy(this, other, { it.incarnation }, { it.turn })
}

/**
 * §3.1 Tier-2 optimistic confirmation gate. [clientSeq] is the local optimistic
 * counter (NEVER compared to [ServerRound.turn]); [serverEchoed] resolves
 * cross-channel reorder (server busy lands before HTTP success); the watchdog
 * arms on [claimedAtMonotonic] + OPTIMISTIC_CONFIRM_TIMEOUT → reconcile.
 *
 * §P0-B final-fix #1: two distinct confirmation signals:
 *  - [serverEchoed] — set ONLY by real-time SSE busy/retry echo (cross-channel
 *    reorder: server confirms via SSE before the HTTP response). NEVER set by
 *    the delayed reconcile.
 *  - [reconcileConfirmed] — set ONLY by the delayed reconcile BUSY_CONFIRMED
 *    (the watchdog's GET confirmed the server is busy). NEVER set by the
 *    real-time SSE.
 * A claim is treated as confirmed (gate released, watchdog skips) iff
 * `serverEchoed || reconcileConfirmed`. A new optimistic generation starts
 * with BOTH false (never inherits reconcileConfirmed — prevents cross-generation
 * pollution).
 */
data class OptimisticClaim(
    val clientSeq: Long,
    val claimedAtMonotonic: Long,
    val serverEchoed: Boolean,
    /** §P0-B final-fix #1: set ONLY by a delayed reconcile BUSY_CONFIRMED (the
     *  watchdog's GET confirmed the server is busy). NOT inherited by a new
     *  optimistic generation (cross-generation pollution prevention). Default
     *  false for backward compat with in-memory state at upgrade. */
    val reconcileConfirmed: Boolean = false,
    val guardedIdleDrop: Boolean,
)

/**
 * §B6 scope key: the count-space boundary. Execution-generation counters
 * (`ServerRound.incarnation`) are per-scope — counters across different
 * server groups / endpoints / slimapi instances are NOT comparable.
 */
data class ScopeKey(
    val serverGroupFp: String,
    val endpointFp: String,
    val slimapiInstanceFp: String? = null,
)

/** §B3 coverage bookkeeping for a [ScopeKey]. */
data class Coverage(
    val registeredWorkdirs: Set<String>,
    val coveredWorkdirs: Set<String>,
    val unmappedActiveIds: Set<String>,
    val lastSuccessTimeMs: Long,
)

/** How a [SessionEntry]'s value arrived. §B9 ServerBusy classification consults this. */
enum class EntryOrigin {
    OPTIMISTIC,
    SSE_LEGACY,
    SSE_SLIM,
    REST,
    TREE,
}

/** Freshness classification for §B9 liveness expiry (P0-A: stored, TTL computed later). */
enum class Freshness {
    Unknown,
    Stale,
    Fresh,
}
