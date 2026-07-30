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
 */
data class OptimisticClaim(
    val clientSeq: Long,
    val claimedAtMonotonic: Long,
    val serverEchoed: Boolean,
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
