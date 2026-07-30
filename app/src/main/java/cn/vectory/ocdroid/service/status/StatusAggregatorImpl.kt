package cn.vectory.ocdroid.service.status

import cn.vectory.ocdroid.data.state.AuthorityOp
import cn.vectory.ocdroid.data.state.EntryOrigin
import cn.vectory.ocdroid.data.state.RequestToken
import cn.vectory.ocdroid.data.state.ScopeKey
import cn.vectory.ocdroid.di.UiApplicationScope
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.StoreState
import cn.vectory.ocdroid.ui.buildAuthorityApplySnapshot
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Singleton

/**
 * Authoritative global busy-source implementation (FGS spec §3 / §3.1, dev-design P0.4).
 *
 * # §P0-A Lane 2 — DERIVED over authority (双投影同源 / R3 真根治)
 *
 * Pre-Lane-2 this class held its OWN writable `Aggregate` (a second source of
 * truth alongside [StoreState.authority]) and its `refresh` / `applySseStatus` /
 * `markRequestFailed` mutation API mutated that private map directly. That was
 * the R3 dual-source: the UI `sessionStatuses` projection (driven by authority)
 * and the lifecycle `globalState` / `statusByKey` projection (driven by this
 * class's own `Aggregate`) could drift apart.
 *
 * Lane 2 collapses them into ONE source. The READ side (`globalState` /
 * `globalBusy` / `statusByKey` / `stateAtNow`) is now a PURE DERIVATION over
 * `store.state.authority` (the SAME slice the UI's `sessionStatuses` projects
 * from) via [authorityToAggregate]. The mutation API (`refresh` /
 * `applySseStatus` / `markRequestFailed`) is preserved VERBATIM in signature
 * (the 6 call sites + ~13 test files are unchanged) but each method is now a
 * THIN ADAPTER that dispatches an [AuthorityOp] into the store's single CAS and
 * then SYNCHRONOUSLY re-derives + publishes so the B4-b synchronous read
 * (`SessionStreamingController` reads `globalState.value` immediately after
 * `refresh` returns) still sees the fresh verdict.
 *
 * ## How the derivation works
 *
 *  - A coroutine launched in `init` `collect`s `store.stateFlow`; on every
 *    emission [publishFromState] re-derives the [Aggregate] via
 *    [authorityToAggregate] and feeds it into the UNCHANGED
 *    [publishLocked] / [project] / [rescheduleFreshnessLocked] pipeline.
 *  - The adapters dispatch an [AppAction.AuthorityEvent] (which lands in the
 *    store's single CAS) and THEN call [publishFromState]`(
 *    store.stateFlow.value)` directly — NOT via the collect — so the B4-b
 *    synchronous read sees the fresh verdict before the collect coroutine even
 *    observes the emission. The collect handles SSE / other-driven authority
 *    changes that do not pass through this adapter.
 *
 * ## Semantics preserved EXACTLY (the金钟 gate)
 *
 * [project] is UNCHANGED — it encodes the SAME freshness boundary, unmapped-
 * active rule, and registered-workdir coverage predicate. The ONLY change is
 * the data source (authority → Aggregate instead of mutation → Aggregate). The
 * [GlobalBusyState] / TTL / freshness / coverage verdicts are byte-for-byte
 * identical for every case the FGS lifecycle coordinator depends on.
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
 * See [StatusAggregator] / [StatusAggregatorInput] kdocs for the full contract.
 * The rules below are unchanged from pre-Lane-2; only the data flow changed.
 *
 * **One clock domain**: the injected [clock] is the SOLE source of "now" for
 * the freshness verdict. SSE arrival time is supplied by the caller via
 * [StatusAggregatorInput.applySseStatus]'s `sourceTimeMs`.
 */
@Singleton
class StatusAggregatorImpl internal constructor(
    private val identityStore: ConnectionIdentityStore,
    private val store: SharedStateStore,
    private val statusFetchService: StatusFetchService,
    @UiApplicationScope private val scope: CoroutineScope,
    private val clock: () -> Long,
) : StatusAggregator, StatusAggregatorInput {

    // NOTE: the `init` collect is declared AFTER all mutable properties below
    // (Kotlin initializes properties + init blocks strictly top-to-bottom; the
    // collect on Dispatchers.Unconfined runs eagerly during construction, so it
    // MUST run after [aggregate] / [commitPublishLock] / [_globalState] etc.
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

    /**
     * §P0-A Lane 2: the DERIVED view of the committed authority state. Held in
     * an [AtomicReference] so the lock-free [stateAtNow] read + the
     * [freshnessJob] TTL wake-up observe ONE immutable snapshot (D4-A M7).
     * Updated ONLY by [publishFromState] (inside [commitPublishLock]); never
     * mutated by the input API directly.
     */
    private val aggregate = AtomicReference(Aggregate.Empty)

    /**
     * D5 (#5): the publication lock. Serializes the derive → write [aggregate]
     * → publish derived [StateFlow]s → reschedule [freshnessJob] sequence so a
     * derived StateFlow cannot momentarily publish a verdict computed from an
     * OLDER authority snapshot while a newer one is already installed (R5).
     */
    private val commitPublishLock = Any()

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

    /**
     * §P0-A rev-gpt #4 (version-monotone publish): the highest
     * [StoreState.authorityRevision] that has been published to the derived
     * StateFlows. Guarded inside [commitPublishLock]: a stale collect emission
     * (queued before a newer synchronous adapter publish acquired the lock) is
     * DROPPED — `state.authorityRevision < lastPublishedRevision` → return.
     * Uses STRICTLY-LESS (`<`) so a SAME-revision TTL re-publish (from
     * [freshnessJob]) is NOT skipped (it re-evaluates the verdict with the live
     * clock at the same authority version — legitimate). Initialized to -1 so
     * the first publish (revision 0) always proceeds.
     */
    @Volatile
    private var lastPublishedRevision: Long = -1L

    init {
        // §P0-A rev-gpt #4 (incremental derivation): collect ONLY when
        // authorityRevision changes (distinctUntilChanged) — chat/composer/
        // settings/other StoreState mutations do NOT trigger a full re-
        // derivation. The `store.stateFlow.value` is read at collect time
        // (post-change state). Declared AFTER all mutable properties above
        // (Kotlin initializes top-to-bottom; the collect on Dispatchers.Unconfined
        // runs eagerly during construction so [aggregate] / [commitPublishLock] /
        // [_globalState] etc. MUST be initialized first). Handles SSE / other-
        // driven authority changes (SseDispatchHost applies SSE status via
        // dispatch directly, NOT through this adapter). The adapter methods ALSO
        // call publishFromState synchronously after their own dispatch so the
        // B4-b synchronous read sees the fresh verdict before this collect
        // observes the emission.
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
     * composite-key [serverGroupFp] for derived entries and as the
     * [AuthorityState.coverage] lookup key.
     */
    private fun currentScope(): ScopeKey {
        val id = identityStore.currentIdentity.value
        return ScopeKey(
            serverGroupFp = id?.serverGroupFp ?: "",
            endpointFp = id?.endpointFp ?: "",
        )
    }

    /**
     * §P0-A Lane 2: PURE-ISH mapping from the committed [StoreState] to the
     * aggregator's internal [Aggregate] shape (the SAME shape the pre-Lane-2
     * mutation API produced). The derivation:
     *
     *  - `currentGroupFp` = the bound identity's `serverGroupFp` (scopes the
     *    composite keys + the lifecycle projection).
     *  - `entries`: each `(sid, SessionEntry)` in `state.authority.bySid` →
     *    `SessionStatusKey(currentGroupFp, workdir ?: "", sid)` →
     *    `Entry(status.toSessionBusyStatus(), sourceTimeMs = updatedMonotonic,
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
        val currentFp = scope.serverGroupFp
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
                sourceTimeMs = e.updatedMonotonic,
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
     * §P0-A Lane 2: re-derive the [Aggregate] from [state], install it in the
     * [AtomicReference], and feed it into the UNCHANGED [publishLocked] /
     * [rescheduleFreshnessLocked] pipeline. Called by BOTH the `init` collect
     * (async authority changes) AND each adapter method (synchronous B4-b read)
     * AND the [freshnessJob] TTL wake-up. MUST be called inside
     * [commitPublishLock] (it is — this method acquires it).
     *
     * §P0-A rev-gpt #4 (version-monotone): inside the lock, DROPS a stale
     * emission whose [StoreState.authorityRevision] predicates
     * [lastPublishedRevision]. Uses STRICTLY-LESS (`<`) so a SAME-revision TTL
     * re-publish from the freshnessJob is NOT skipped. On a real publish,
     * stamps [lastPublishedRevision] = the current revision. This closes the
     * race where a stale collect emission (queued before a newer synchronous
     * adapter publish) acquires the lock later and overwrites the newer verdict.
     */
    private fun publishFromState(state: StoreState) {
        synchronized(commitPublishLock) {
            // §P0-A rev-gpt #4: version-monotone guard. A stale snapshot whose
            // authorityRevision is STRICTLY-LESS than what was already published
            // is dropped (a newer publish already landed). Same revision is NOT
            // dropped (freshnessJob TTL re-eval at the same authority version).
            if (state.authorityRevision < lastPublishedRevision) return
            lastPublishedRevision = state.authorityRevision
            val agg = authorityToAggregate(state)
            aggregate.set(agg)
            publishLocked(agg, clock())
        }
    }

    // ── StatusAggregator.stateAtNow (D1 gate #1) ───────────────────────────

    /**
     * D1 (gate #1): time-correct projection at the instant of the call. Reads
     * the live DERIVED [aggregate] (now from authority) and recomputes the
     * verdict with the aggregator's own [clock]. UNCHANGED logic — only the
     * source of [aggregate] changed (derived, not mutated).
     */
    override fun stateAtNow(): GlobalBusyState =
        project(aggregate.get(), clock())

    // ── StatusAggregatorInput → authority-dispatch adapters ─────────────────

    /**
     * REST-driven refresh — now a THIN ADAPTER (FGS spec §3 «Phase 0 主路径»,
     * §3.1 merge timing, §2 epoch guard). Same signature as pre-Lane-2 (call
     * sites unchanged).
     *
     *  1. Capture `requestStartMs` + `epochAtRequestStart` BEFORE the fetch.
     *  2. [StatusFetchService] fetches the REST/slim merged status map (the
     *     network work extracted out of this class).
     *  3. CP4 §2 epoch guard: drop if a reconfigure invalidated this request.
     *  4. On success → [buildAuthorityApplySnapshot] + `dispatch(
     *     AuthorityEvent(ApplySnapshot))` (the pure authority reducer does the
     *     binning / merge-timing / coverage / in-flight protection). On failure
     *     → `dispatch(AuthorityEvent(MarkSourceFailed))`.
     *  5. SYNCHRONOUS [publishFromState] so the B4-b synchronous read at
     *     `SessionStreamingController:183` (`globalState.value` immediately
     *     after `refresh` returns) sees the fresh verdict.
     */
    override suspend fun refresh(identity: ConnectionIdentity, snapshot: StatusSnapshot) {
        val requestStartMs = clock()
        val epochAtRequestStart = identityStore.currentEpoch()
        // §P0-A rev-gpt #2 (kill TOCTOU): capture hostProfileId + identityEpoch
        // at request START (before the fetch), NOT after — so a host switch
        // landing mid-fetch is detected by the reducer's epoch guard inside
        // the CAS. The dispatch-side identityStore.currentEpoch() check below
        // additionally catches endpoint/workdir-only reconfigures.
        val stateAtStart = store.stateFlow.value
        val hostAtStart = stateAtStart.host.currentHostProfileId
        val identityEpochAtStart = stateAtStart.identityEpoch
        // §3.1 merge timing (B4-b adapter-side): localBefore captures the
        // projection of entries that are NOT fresher than the REST request
        // start. Entries with `updatedMonotonic > requestStartMs` (a fresher
        // SSE observation landed before this REST started) are EXCLUDED so the
        // reducer's REST in-flight protection (mergeStatusSnapshotInFlight)
        // treats them as "changed during the round-trip" → SSE-wins overrides
        // the stale REST snapshot.
        val authorityAtStart = stateAtStart.authority
        val localBefore = authorityAtStart.bySid
            .filterValues { it.updatedMonotonic <= requestStartMs }
            .mapValues { it.value.status }
        val result = statusFetchService.fetch(snapshot)
        // CP4 §2 epoch guard: drop the response if a reconfigure invalidated
        // this request mid-flight (checked AFTER the suspend, BEFORE dispatch).
        if (identityStore.currentEpoch() != epochAtRequestStart) return
        val scopeKey = ScopeKey(
            serverGroupFp = identity.serverGroupFp,
            endpointFp = identity.endpointFp,
        )
        val token = RequestToken(
            hostProfileId = hostAtStart,
            requestStartMs = requestStartMs,
            identityEpoch = identityEpochAtStart,
        )
        result.fold(
            onSuccess = { fetch ->
                // D1 gate #5: ids returned by /session/status that are NOT in
                // sessionsById are positively known active → forces Busy. They
                // are tracked in [unmappedActiveIds] (coverage) and do NOT enter
                // bySid (no workdir mapping → no composite key). The reducer's
                // `normalizeAuthoritativeStatusSnapshot` keeps any id present in
                // the snapshot, so we filter to MAPPED sids only to prevent
                // ghosts from materializing a bySid entry.
                val unmappedActiveIds = fetch.statuses.keys - snapshot.sessionsById.keys
                val mappedStatuses = fetch.statuses.filterKeys { it in snapshot.sessionsById }
                // §P0-A rev-gpt #6 (fail-closed for failed-dir sessions): a
                // session in a FAILED workdir must NOT be idle-normalized (it
                // was not authoritatively fetched). Exclude failed-dir session
                // ids from authoritativeNodeIds so the reducer does NOT
                // idle-fill them → they stay ABSENT (fail-closed unknown).
                val failedDirSessionIds = snapshot.sessionsById.values
                    .filter { it.directory.isNotBlank() && it.directory in fetch.failedWorkdirs }
                    .map { it.id }
                    .toSet()
                val op = buildAuthorityApplySnapshot(
                    snapshot = mappedStatuses,
                    authoritativeSessions = snapshot.sessionsById,
                    authoritativeNodeIds = snapshot.sessionsById.keys - failedDirSessionIds,
                    // 方案A Issue2: exclude failed workdirs from coverage so the
                    // registered-workdir coverage predicate independently
                    // refuses AllIdleFresh on a partial slim failure.
                    coveredWorkdirs = snapshot.registeredWorkdirs - fetch.failedWorkdirs,
                    partialFailureWorkdirs = fetch.failedWorkdirs,
                    unmappedActiveIds = unmappedActiveIds,
                    lastSuccessTimeMs = requestStartMs,
                    scopeKey = scopeKey,
                    requestToken = token,
                    localBefore = localBefore,
                )
                store.dispatch(AppAction.AuthorityEvent(op))
                publishFromState(store.stateFlow.value)
            },
            onFailure = { markRequestFailedInternal(identity, snapshot, requestStartMs, token) },
        )
    }

    /**
     * Apply a single SSE-driven status update — now a THIN ADAPTER (FGS spec
     * §3.1 merge timing). Same signature as pre-Lane-2 (call sites unchanged).
     *
     * §3.1 merge timing (adapter-side): a strictly-OLDER SSE frame
     * (`sourceTimeMs < ` the current authority entry's `updatedMonotonic`) is
     * DROPPED — defensive against out-of-order SSE replay during reconnect
     * (the pre-Lane-2 aggregator did the same `sourceTimeMs >= prev.sourceTimeMs`
     * gate). Equal timestamps overwrite (matches the legacy `>=` rule). The
     * authority reducer's [ApplyEvent] is pure + lenient for P0-A (no causal
     * fence for legacy SSE without serverRound); the adapter supplies the
     * source-time merge-timing gate.
     *
     * Then dispatches [AuthorityOp.ApplyEvent] + synchronous [publishFromState]
     * for any sync reader. [SessionBusyStatus]→[SessionStatus] reverse mapping
     * via [toSessionStatus] (Busy/Retry/Idle only — SSE never emits Unknown).
     */
    override fun applySseStatus(key: SessionStatusKey, status: SessionBusyStatus, sourceTimeMs: Long) {
        // §3.1 merge timing: drop a strictly-older SSE frame (out-of-order replay).
        val current = store.stateFlow.value.authority.bySid[key.sessionId]
        if (current != null && sourceTimeMs < current.updatedMonotonic) return
        val op = AuthorityOp.ApplyEvent(
            sid = key.sessionId,
            status = status.toSessionStatus(),
            origin = EntryOrigin.SSE_LEGACY,
            scopeKey = currentScope(),
            connectionMonotonicMs = sourceTimeMs,
            workdir = key.workdir,
        )
        store.dispatch(AppAction.AuthorityEvent(op))
        publishFromState(store.stateFlow.value)
    }

    /**
     * Explicit failure entry — now a THIN ADAPTER (FGS spec §3 «请求失败 → 全局
     * Unknown»). Same signature as pre-Lane-2 (call sites unchanged).
     *
     * Dispatches [AuthorityOp.MarkSourceFailed] (the reducer merge-times bySid
     * — keeps fresher SSE Busy/Retry, removes older → absence ≡ unknown — and
     * sets coverage.lastSuccessTimeMs = -1 so the derived [project] returns
     * [GlobalBusyState.Unknown] via the cold-start / stale-success coverage
     * gate). Then synchronous [publishFromState].
     */
    override fun markRequestFailed(
        identity: ConnectionIdentity,
        snapshot: StatusSnapshot,
        sourceTimeMs: Long,
    ) {
        // §P0-A rev-gpt #2: capture host/epoch at call time (the public entry
        // is NOT suspend — no mid-call reconfigure window; the token reflects
        // the current state at the instant of the failure call).
        val currentState = store.stateFlow.value
        val token = RequestToken(
            hostProfileId = currentState.host.currentHostProfileId,
            requestStartMs = sourceTimeMs,
            identityEpoch = currentState.identityEpoch,
        )
        markRequestFailedInternal(identity, snapshot, sourceTimeMs, token)
    }

    private fun markRequestFailedInternal(
        identity: ConnectionIdentity,
        snapshot: StatusSnapshot,
        sourceTimeMs: Long,
        token: RequestToken,
    ) {
        val scopeKey = ScopeKey(
            serverGroupFp = identity.serverGroupFp,
            endpointFp = identity.endpointFp,
        )
        val op = AuthorityOp.MarkSourceFailed(
            scopeKey = scopeKey,
            requestToken = token,
            monotonic = sourceTimeMs,
            registeredWorkdirs = snapshot.registeredWorkdirs,
        )
        store.dispatch(AppAction.AuthorityEvent(op))
        publishFromState(store.stateFlow.value)
    }

    // ── Internal: projection publication (UNCHANGED logic) ──────────────────

    /**
     * D5 (#5) — derives + publishes the three projected [StateFlow]s for the
     * committed [Aggregate]. **MUST be called holding [commitPublishLock]**.
     *
     * UNCHANGED from pre-Lane-2: publication order `_statusByKey` →
     * `_globalBusy` → `_globalState` (so verdict observers cannot pair a new
     * state with the previous commit's status map), then
     * [rescheduleFreshnessLocked]. The ONLY change is that [committed] is now
     * DERIVED from authority (via [authorityToAggregate]) instead of mutated.
     */
    private fun publishLocked(committed: Aggregate, now: Long) {
        val map = committed.entries
        val cov = committed.coverage
        val verdict = project(committed, now)
        val fp = committed.currentGroupFp
        val scoped = map.filterKeys { it.serverGroupFp == fp }
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
        val scoped = map.filterKeys { it.serverGroupFp == fp }

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
     * §P0-A Lane 2: the wake-up coroutine now calls [publishFromState] (re-
     * derive from the LIVE authority + clock) instead of republishing a cached
     * aggregate — so a TTL expiry re-derives the freshest authority snapshot
     * (which may have changed since the aggregate was cached) AND applies the
     * live clock. MUST be called holding [commitPublishLock].
     */
    private fun rescheduleFreshnessLocked(committed: Aggregate, now: Long) {
        freshnessJob?.cancel()
        val map = committed.entries
        val cov = committed.coverage
        val fp = committed.currentGroupFp
        val idleDeadlines = map
            .filter { it.key.serverGroupFp == fp && it.value.status == SessionBusyStatus.Idle }
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
                // §P0-A Lane 2: re-derive from the LIVE authority + clock (NOT a
                // cached aggregate republish) so the TTL-adjusted verdict reflects
                // any authority change since the aggregate was cached. publishFromState
                // acquires [commitPublishLock] internally (D5 #5 — same memory
                // visibility as serial state-machine mutations). publishLocked →
                // rescheduleFreshnessLocked re-arms the next deadline cooperively
                // (the current coroutine is already past its suspension point).
                publishFromState(store.stateFlow.value)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                DebugLog.w(TAG, "freshnessJob recompute failed: ${e.message}")
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
