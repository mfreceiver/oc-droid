package cn.vectory.ocdroid.service.status

import cn.vectory.ocdroid.data.state.EntryOrigin
import cn.vectory.ocdroid.data.state.ScopeKey
import cn.vectory.ocdroid.data.state.scopeKeyOf
import cn.vectory.ocdroid.di.UiApplicationScope
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.StoreState
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Singleton

/**
 * Authoritative global busy-source implementation (FGS spec §3 / §3.1, dev-design P0.4).
 *
 * # DERIVED over authority (双投影同源 / R3 真根治)
 *
 * The READ side (`globalState` / `globalBusy` / `statusByKey` / `stateAtNow`) is a
 * PURE DERIVATION over `store.state.authority` via [authorityToAggregate]. A
 * coroutine launched in `init` `collect`s `store.stateFlow`; on every emission
 * [publishFromState] re-derives the [Aggregate] via [authorityToAggregate] and
 * feeds it into the [publishLocked] / [project] / [rescheduleFreshnessLocked]
 * pipeline.
 *
 * **F1 (archdebt follow-up)**: the input side (`StatusAggregatorInput` interface +
 * adapters) was **retired** — all callers were deliberately rerouted to direct
 * authority dispatch (`applyStatusViaAuthority`) in Lane 2. The interface
 * `StatusFetchService` / `SlimStatusFetchCache` that served only the dead
 * `refresh` adapter were deleted. The `refresh` / `applySseStatus` /
 * `markRequestFailed` adapter methods and the `: StatusAggregatorInput`
 * implements-clause are gone.
 *
 * ## How the derivation works
 *
 *  - A coroutine launched in `init` `collect`s `store.stateFlow`; on every
 *    emission [publishFromState] re-derives the [Aggregate] via
 *    [authorityToAggregate] and feeds it into the UNCHANGED
 *    [publishLocked] / [project] / [rescheduleFreshnessLocked] pipeline.
 *  - The `init` collect handles SSE / other-driven authority changes
 *    (SseDispatchHost applies SSE status via [applyStatusViaAuthority] dispatch
 *    directly, NOT through a dead adapter).
 *
 * ## Semantics preserved EXACTLY (the金钟 gate)
 *
 * [project] is UNCHANGED — it encodes the SAME freshness boundary, unmapped-
 * active rule, and registered-workdir coverage predicate. The [GlobalBusyState] /
 * TTL / freshness / coverage verdicts are byte-for-byte identical for every case
 * the FGS lifecycle coordinator depends on.
 *
 * **`Unknown` after failure**: authority has no `Unknown` [SessionStatus]
 * (the transport model is `idle`/`busy`/`retry` only). A REST failure dispatches
 * [AuthorityOp.MarkSourceFailed], whose reducer merge-times the `bySid` (keeps
 * fresher SSE `Busy`/`Retry`, removes older → absence ≡ unknown) AND sets
 * `coverage.lastSuccessTimeMs = -1`. The derived [project] then returns
 * [GlobalBusyState.Unknown] via the cold-start / stale-success coverage gate —
 * matching the old markFailed → Unknown lifecycle verdict (the coverage gate,
 * not Unknown status entries, is the authority).
 *
 * ## Authoritativeness rules (FGS spec §3 / §3.1 / §2) — preserved
 *
 * See [StatusAggregator] kdoc for the full contract. The rules below are unchanged
 * from pre-Lane-2; only the data flow changed.
 *
 * **One clock domain**: the injected [clock] is the SOLE source of "now" for
 * the freshness verdict. SSE arrival time is supplied by the caller via
 * [applyStatusViaAuthority]'s `connectionTimeMs`.
 */
@Singleton
class StatusAggregatorImpl internal constructor(
    private val identityStore: ConnectionIdentityStore,
    private val store: SharedStateStore,
    @UiApplicationScope private val scope: CoroutineScope,
    private val clock: () -> Long,
) : StatusAggregator {

    // NOTE: the `init` collect is declared AFTER all mutable properties below
    // (Kotlin initializes properties + init blocks strictly top-to-bottom; the
    // collect on Dispatchers.Unconfined runs eagerly during construction, so it
    // MUST run after [_globalState] / [_globalBusy] / [_statusByKey]
    // are initialized — otherwise they are null when publishFromState runs).

    /**
     * Internal per-key entry. Carries the [status] label, the [sourceTimeMs]
     * used for merge timing + the TTL freshness check (FGS spec §3.1 / §3
     * «status TTL»), and a [fresh] flag distinguishing REST-snapshot entries
     * (fresh = true) from SSE/optimistic/failure entries (fresh = false). The
     * freshness flag is informational; the authoritative freshness verdict used
     * by [globalState] is the TTL check `now - sourceTimeMs <= STATUS_TTL_MS`.
     *
     * §P0-A Lane 2: `Entry` is now DERIVED from a [SessionEntry] via
     * [authorityToAggregate] (it is no longer mutated by the input API).
     */
    private data class Entry(
        val status: SessionBusyStatus,
        val sourceTimeMs: Long,
        val fresh: Boolean,
    )

    /**
     * D1 (gate #5): coverage metadata held alongside the per-key state map.
     * See the pre-Lane-2 kdoc (unchanged): registeredWorkdirs / coveredWorkdirs
     * / unmappedActiveIds / lastSuccessTimeMs.
     *
     * §P0-A Lane 2: DERIVED from [AuthorityState.coverage]`[currentScope]` via
     * [authorityToAggregate]; defaults to [Coverage.Empty] when the current
     * scope has no coverage entry (cold-start).
     */
    private data class Coverage(
        val registeredWorkdirs: Set<String>,
        val coveredWorkdirs: Set<String>,
        val unmappedActiveIds: Set<String>,
        val lastSuccessTimeMs: Long,
    ) {
        companion object {
            val Empty: Coverage = Coverage(
                registeredWorkdirs = emptySet(),
                coveredWorkdirs = emptySet(),
                unmappedActiveIds = emptySet(),
                lastSuccessTimeMs = -1L,
            )
        }
    }

    private data class Aggregate(
        val entries: Map<SessionStatusKey, Entry>,
        val coverage: Coverage,
        val currentGroupFp: String,
    ) {
        companion object {
            val Empty = Aggregate(emptyMap(), Coverage.Empty, "")
        }
    }

    private val _globalState = MutableStateFlow(GlobalBusyState.Unknown)
    private val _globalBusy = MutableStateFlow(false)
    private val _statusByKey = MutableStateFlow<Map<SessionStatusKey, SessionBusyStatus>>(emptyMap())

    override val globalState: StateFlow<GlobalBusyState> = _globalState.asStateFlow()
    override val globalBusy: StateFlow<Boolean> = _globalBusy.asStateFlow()
    override val statusByKey: StateFlow<Map<SessionStatusKey, SessionBusyStatus>> = _statusByKey.asStateFlow()

    /**
     * D1 (gate #1): passive-TTL wake-up job. Cancelled + rescheduled on every
     * committed [publishFromState] to the earliest current-identity deadline
     * that can alter the projection. See [rescheduleFreshnessLocked].
     */
    private var freshnessJob: Job? = null

    /** §U-PUBLISH: serialization lock for [publishFromState]. Guards against
     *  concurrent Main/Default dispatcher races. */
    private val publishLock = Any()

    /** §U-PUBLISH: the [StoreState.authorityRevision] of the last published
     *  state. A call with a strictly LOWER revision is a no-op (stale publish
     *  suppressed). A call with an EQUAL revision is allowed (re-derive with
     *  fresh clock). [Long.MIN_VALUE] ensures the first publish always proceeds. */
    @Volatile
    private var maxPublishedRevision: Long = Long.MIN_VALUE

    init {
        // §P0-A rev-gpt #4 (incremental derivation): collect ONLY when
        // authorityRevision changes (distinctUntilChanged) — chat/composer/
        // settings/other StoreState mutations do NOT trigger a full re-
        // derivation. The `store.stateFlow.value` is read at collect time
        // (post-change state). Declared AFTER all mutable properties above
        // (Kotlin initializes top-to-bottom; the collect on Dispatchers.Unconfined
        // runs eagerly during construction so [_globalState] / [_globalBusy] /
        // [_statusByKey] / [publishLock] / [maxPublishedRevision] etc. MUST be
        // initialized first). Handles SSE / other-driven authority changes
        // (SseDispatchHost applies SSE status via dispatch directly, NOT through
        // this adapter). The adapter methods ALSO call publishFromState
        // synchronously after their own dispatch so the B4-b synchronous read
        // sees the fresh verdict before this collect observes the emission.
        // §U-PUBLISH: [publishFromState] is serialized by [publishLock] with
        // a [maxPublishedRevision] guard, so concurrent collect + adapter
        // publish races are resolved deterministically.
        scope.launch {
            store.stateFlow
                .map { it.authorityRevision }
                .distinctUntilChanged()
                .collect {
                    publishFromState(store.stateFlow.value)
                }
        }
    }

    // ── §P0-A Lane 2: authority → Aggregate derivation ──────────────────────

    /**
     * §P0-A Lane 2: the current connection scope, derived from the bound
     * [identityStore] identity (NOT from the per-call `identity` param — the
     * bound identity is the single source of truth for "current scope", and in
     * production the per-call identity always matches it). Used both as the
     * composite-key [profileId] for derived entries and as the
     * [AuthorityState.coverage] lookup key.
     *
     * §U-MN10 (分歧3) rev-gpt gate r1: in STEADY STATE (no concurrent
     * reconfigure), `scopeKeyOf(perCallIdentity.profileId, …)` MUST equal
     * `currentScope()`. This invariant is NOT runtime-enforced — the reads of
     * [currentEpoch] + [currentIdentity] are two independent lock-free reads,
     * so a runtime check() could fire across a legitimate cross-thread
     * reconfigure race (ProcessStatusPoller on Dispatchers.Default vs
     * controller/UI-initiated beginReconfigure). The invariant is pinned at
     * TEST level (StatusAggregatorImplTest `scope derivation is consistent`).
     */
    private fun currentScope(): ScopeKey {
        val id = identityStore.currentIdentity.value
        return scopeKeyOf(id?.profileId, id?.endpointFp)
    }

    /**
     * §P0-A Lane 2: PURE-ISH mapping from the committed [StoreState] to the
     * aggregator's internal [Aggregate] shape (the SAME shape the pre-Lane-2
     * mutation API produced). The derivation:
     *
     *  - `currentGroupFp` = the bound identity's `profileId` (scopes the
     *    composite keys + the lifecycle projection).
     *  - `entries`: each `(sid, SessionEntry)` in `state.authority.bySid` →
     *    `SessionStatusKey(currentGroupFp, workdir ?: "", sid)` →
     *    `Entry(status.toSessionBusyStatus(), sourceTimeMs = updatedAtMs,
     *    fresh = (origin == REST))`. The `origin == REST` ⇒ fresh rule matches
     *    the pre-Lane-2 success-fold `fresh = true` for REST entries (within
     *    TTL); SSE / optimistic / failure entries are `fresh = false`.
     *  - `coverage`: `state.authority.coverage[currentScope]` mapped to the
     *    aggregator's [Coverage], defaulting to [Coverage.Empty] (cold-start
     *    guard → lastSuccessTimeMs = -1 → [project] returns [GlobalBusyState.Unknown]).
     *
     * Not referentially-pure (reads [identityStore]) — but deterministic given
     * `(state, boundIdentity)`, and the bound identity is process-stable across
     * a CAS retry. This is the derivation, NOT a mutation — the purity of the
     * authority REDUCER ([reduceAuthority]) is untouched.
     */
    private fun authorityToAggregate(state: StoreState): Aggregate {
        val authority = state.authority
        val scope = currentScope()
        val currentFp = scope.profileId
        val entries = HashMap<SessionStatusKey, Entry>(authority.bySid.size)
        for ((sid, e) in authority.bySid) {
            // §P0-A r2 #4: scope-check by entry.scopeKey. An entry whose scopeKey
            // is non-null AND does NOT match the current scope is SKIPPED — it
            // belongs to a different server group/endpoint and must not contribute
            // to the current aggregation. Entries with null scopeKey (pre-r2
            // migration) are conservatively included (single-scope P0-A).
            if (e.scopeKey != null && e.scopeKey != scope) continue
            // §P0-A r2 #4: use the REAL workdir from the matched scope's
            // coverage (not e.workdir which may be null for entries from
            // different origins). For aggregator's purpose, both are equivalent
            // in single-scope P0-A; use e.workdir for backward compatibility.
            val key = SessionStatusKey(currentFp, e.workdir ?: "", sid)
            entries[key] = Entry(
                status = e.status.toSessionBusyStatus(),
                sourceTimeMs = e.updatedAtMs,
                fresh = e.origin == EntryOrigin.REST,
            )
        }
        val authCov = authority.coverage[scope]
        val coverage = if (authCov != null) {
            Coverage(
                registeredWorkdirs = authCov.registeredWorkdirs,
                coveredWorkdirs = authCov.coveredWorkdirs,
                unmappedActiveIds = authCov.unmappedActiveIds,
                lastSuccessTimeMs = authCov.lastSuccessTimeMs,
            )
        } else {
            Coverage.Empty
        }
        return Aggregate(entries = entries, coverage = coverage, currentGroupFp = currentFp)
    }

    /**
     * §U-PUBLISH: re-derive the [Aggregate] from [state] and feed it into the
     * [publishLocked] / [rescheduleFreshnessLocked] pipeline. Serialized by
     * [publishLock] so that concurrent Main/Default dispatcher calls produce
     * a consistent sequence.
     *
     * The [maxPublishedRevision] guard suppresses a stale publish whose
     * [StoreState.authorityRevision] is STRICTLY less than the latest already
     * published. An EQUAL revision is allowed (same authority, fresh clock for
     * the [project] TTL verdict — needed by the [freshnessJob] TTL wake-up and
     * synchronous adapter reads that may observe the same revision with a
     * later clock). The first publish always proceeds ([Long.MIN_VALUE]).
     *
     * Called by BOTH the `init` collect (async authority changes) AND each
     * adapter method (synchronous B4-b read) AND the [freshnessJob] TTL
     * wake-up.
     */
    @Suppress("unused")
    internal fun publishFromState(state: StoreState) {
        synchronized(publishLock) {
            val rev = state.authorityRevision
            if (rev < maxPublishedRevision) return
            maxPublishedRevision = rev
            val agg = authorityToAggregate(state)
            publishLocked(agg, clock())
        }
    }

    // ── StatusAggregator.stateAtNow (D1 gate #1) ───────────────────────────

    /**
     * D1 (gate #1): time-correct projection at the instant of the call.
     * Re-derives the [Aggregate] from the live [store.stateFlow] and recomputes
     * the verdict with the aggregator's own [clock] (no cache). The derivation
     * is O(sessions) per call — acceptable because sessions count is small and
     * [stateAtNow] callers are infrequent synchronous reads.
     */
    override fun stateAtNow(): GlobalBusyState =
        project(authorityToAggregate(store.stateFlow.value), clock())

    // ── Internal: projection publication (UNCHANGED logic) ──────────────────

    /**
     * D5 (#5) — derives + publishes the three projected [StateFlow]s for the
     * committed [Aggregate]. Called from [publishFromState] under [publishLock]
     * (§U-PUBLISH), so callers are serialized — no concurrent races.
     *
     * ## Publication-order invariant
     *
     * The write order `_statusByKey` → `_globalBusy` → `_globalState` ensures
     * that verdict observers cannot pair a new [globalState] with the previous
     * commit's status map. Serialized by [publishFromState]'s [publishLock],
     * so cross-thread ordering is guaranteed.
     *
     * After publishing, [rescheduleFreshnessLocked] arms the next TTL deadline.
     */
    private fun publishLocked(committed: Aggregate, now: Long) {
        val map = committed.entries
        val cov = committed.coverage
        val verdict = project(committed, now)
        val fp = committed.currentGroupFp
        val scoped = map.filterKeys { it.profileId == fp }
        val anyBusy = scoped.any { entry ->
            entry.value.status == SessionBusyStatus.Busy ||
                entry.value.status == SessionBusyStatus.Retry
        } || cov.unmappedActiveIds.isNotEmpty()
        _statusByKey.value = map.mapValues { it.value.status }
        _globalBusy.value = anyBusy
        _globalState.value = verdict
        rescheduleFreshnessLocked(committed, now)
    }

    /**
     * D1 (gate #1): the pure projection used by BOTH the [globalState] recompute
     * and the time-correct [stateAtNow] read. UNCHANGED from pre-Lane-2 —
     * encodes the freshness boundary, the unmapped-active rule, and the
     * registered-workdir coverage predicate. See the pre-Lane-2 kdoc for the
     * full case-by-case rules (all preserved EXACTLY).
     */
    private fun project(committed: Aggregate, now: Long): GlobalBusyState {
        val map = committed.entries
        val cov = committed.coverage
        val fp = committed.currentGroupFp
        val scoped = map.filterKeys { it.profileId == fp }

        // gate #5: positively known active unmapped ids → Busy (highest priority).
        if (cov.unmappedActiveIds.isNotEmpty()) return GlobalBusyState.Busy

        // any Busy||Retry → Busy (conservative — stale Busy stays Busy).
        val anyBusy = scoped.any { entry ->
            entry.value.status == SessionBusyStatus.Busy ||
                entry.value.status == SessionBusyStatus.Retry
        }
        if (anyBusy) return GlobalBusyState.Busy

        // any Unknown → Unknown (do NOT enter idle grace on Unknown).
        if (scoped.values.any { it.status == SessionBusyStatus.Unknown }) return GlobalBusyState.Unknown

        // any stale entry → Unknown (stale Idle is not authoritative; a stale
        // Busy would have returned above).
        if (scoped.values.any { now - it.sourceTimeMs > STATUS_TTL_MS }) return GlobalBusyState.Unknown

        // Empty state with no fresh coverage marker → Unknown (cold-start guard).
        val freshSuccess =
            cov.lastSuccessTimeMs >= 0L && now - cov.lastSuccessTimeMs <= STATUS_TTL_MS
        if (!freshSuccess) return GlobalBusyState.Unknown

        // gate #5: registered-workdir coverage predicate.
        val allWorkdirsCovered = cov.coveredWorkdirs.containsAll(cov.registeredWorkdirs)
        if (!allWorkdirsCovered) return GlobalBusyState.Unknown

        return GlobalBusyState.AllIdleFresh
    }

    /**
     * D1 (gate #1) + D5 (#5): schedule a single [freshnessJob] for the earliest
     * current-identity deadline that can alter the projection. UNCHANGED logic
     * from pre-Lane-2 (Busy/Retry need no wake-up; Idle entries flip to Unknown
     * at `sourceTimeMs + STATUS_TTL_MS + 1`; the coverage marker's own TTL is a
     * deadline source).
     *
     * Called under [publishLock] (from [publishFromState] → [publishLocked]), so
     * serialized — no concurrent [freshnessJob] races.
     *
     * §P0-A Lane 2: the wake-up coroutine calls [publishFromState] (re-derive
     * from the LIVE authority + clock) so a TTL expiry re-derives the freshest
     * authority snapshot AND applies the live clock.
     */
    private fun rescheduleFreshnessLocked(committed: Aggregate, now: Long) {
        freshnessJob?.cancel()
        val map = committed.entries
        val cov = committed.coverage
        val fp = committed.currentGroupFp
        val idleDeadlines = map
            .filter { it.key.profileId == fp && it.value.status == SessionBusyStatus.Idle }
            .values
            .map { it.sourceTimeMs + STATUS_TTL_MS + 1 }
            .filter { it > now }
        val coverageDeadline = if (cov.lastSuccessTimeMs >= 0L) {
            cov.lastSuccessTimeMs + STATUS_TTL_MS + 1
        } else {
            null
        }?.takeIf { it > now }
        val earliest = (idleDeadlines + listOfNotNull(coverageDeadline)).minOrNull() ?: return
        val delayMs = earliest - now
        freshnessJob = scope.launch {
            try {
                delay(delayMs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            }
            try {
                // §U-MN8: re-publish synchronously so clock-only TTL verdict flips
                // (project() computes from sourceTimeMs) are reflected. The
                // FreshnessTick dispatch was eliminated — freshness field had no
                // consumers (design §0.C). publishLocked → rescheduleFreshnessLocked
                // re-arms the next deadline cooperatively.
                publishFromState(store.stateFlow.value)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                DebugLog.w(TAG, "freshnessJob dispatch failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "StatusAggregatorImpl"

        /**
         * FGS spec §3 «status TTL»: an entry is "fresh" only within ≈30s of its
         * source time. UNCHANGED. See the pre-Lane-2 kdoc for the boundary
         * semantics (`now - sourceTimeMs <= STATUS_TTL_MS` fresh; the passive
         * [freshnessJob] targets `sourceTimeMs + STATUS_TTL_MS + 1`).
         */
        const val STATUS_TTL_MS = 30_000L
    }
}
