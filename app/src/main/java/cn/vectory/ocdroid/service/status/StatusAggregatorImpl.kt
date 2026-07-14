package cn.vectory.ocdroid.service.status

import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.di.UiApplicationScope
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Singleton

/**
 * Authoritative global busy-source implementation (FGS spec §3 / §3.1, dev-design P0.4).
 *
 * Implements BOTH [StatusAggregator] (read-only outputs, consumed by the
 * lifecycle coordinator + Phase-1 notification display) and [StatusAggregatorInput]
 * (the feed surface consumed by SSC / the bootstrap coordinator / AppCore).
 *
 * Constructed via `StatusModule.provideStatusAggregatorImpl` (NOT `@Inject
 * constructor`) so the [clock] default param (`{ System.currentTimeMillis() }`)
 * is honored at the construction site — matching the other controllers' pattern
 * (ForegroundCatchUpController / SessionSwitcher / ConnectionCoordinator /
 * SessionSyncCoordinator all take a default-param clock and are wired via
 * `@Provides`). Both [StatusAggregator] and [StatusAggregatorInput] are bound
 * to the SAME `@Singleton` instance via the two `@Binds` in `StatusModule`.
 *
 * Holds the single source of truth for per-session busy status, keyed by the
 * composite [SessionStatusKey] = `(serverGroupFp, workdir, sessionId)`.
 *
 * **Authoritativeness rules enforced here** (FGS spec §3 / §3.1 / §2):
 *  - busy is **global**, keyed by [SessionStatusKey] so two sessions with the same id on
 *    different hosts / workdirs cannot collide;
 *  - the Phase-0 main path consumes the host-level `getSessionStatus()` once and bins each
 *    returned `sessionId` to its workdir via `sessionsById[sessionId].directory`;
 *  - **D1 (gate #5) unmapped-active→Busy**: a `sessionId` returned by
 *    `/session/status` that is NOT in `sessionsById` is **positively known
 *    active** → contributes [GlobalBusyState.Busy]. Pre-D1 the aggregator
 *    skipped it, so `AllIdleFresh` could pass while a returned busy session
 *    was silently dropped. Tracked in [coverage]'s `unmappedActiveIds`.
 *  - **D1 (gate #5) registered-workdir coverage**: `AllIdleFresh` is legal
 *    ONLY when every workdir in the snapshot's `registeredWorkdirs` is
 *    covered by a fresh successful observation (FGS spec §3 «只有所有已登记
 *    workdir 都取得新鲜+成功 idle 才进停流宽限期»). A registered workdir with
 *    no live session is represented by the coverage marker — it does NOT
 *    disappear from the all-idle predicate when its sessions archive.
 *  - a failed status fetch labels every known session [SessionBusyStatus.Unknown], and the
 *    merge-timing rule (below) ensures a prior fresher `Busy`/`Retry` SSE status is **not**
 *    clobbered by the failure snapshot — `Unknown` thus never wrongly clears [globalBusy]
 *    and never enters the idle grace window;
 *  - a REST response **must not** overwrite an SSE status whose source time is later than
 *    the REST request-start (merge timing, FGS spec §3.1);
 *  - **CP4 REST epoch guard** (FGS spec §2 + §3.1): `refresh` captures
 *    `epochAtRequestStart = identityStore.currentEpoch()` BEFORE the REST call. When
 *    committing the response, the entry is DROPPED if the current epoch differs — a stale
 *    old-epoch REST response cannot mutate current state;
 *  - **CP4 status TTL** (FGS spec §3, ≈30s): an entry is "fresh" only within [STATUS_TTL_MS]
 *    of its source time. [globalState] = [GlobalBusyState.AllIdleFresh] requires EVERY
 *    tracked session under the current identity to be `Idle` AND fresh. Stale `Idle`
 *    entries fall back to [GlobalBusyState.Unknown] (do NOT enter idle grace on stale/unknown);
 *    stale `Busy`/`Retry` entries stay [GlobalBusyState.Busy] (conservative — never silently
 *    drop keep-alive on a possibly-still-busy session).
 *
 * **D1 (gate #1) passive TTL**: the previous design only re-ran [recompute]
 * on map mutation, so an `AllIdleFresh` could freeze past `STATUS_TTL_MS`
 * without any write — the §4.4 idle debounce would then read a stale
 * `AllIdleFresh` from [globalState] and stop SSE on stale idle data (§3
 * violation «请求失败/过期 → 全局 Unknown, 不得进 idle 宽限期»). The fix is a
 * single [freshnessJob] that reschedules itself on every committed update to
 * the **earliest current-identity deadline that can alter the projection**
 * (each fresh-Idle entry's `sourceTimeMs + STATUS_TTL_MS + 1`, plus the
 * coverage marker's own TTL). At the deadline the job calls [recompute]
 * WITHOUT requiring a map mutation. Observers that need the verdict at an
 * exact instant (the §4.4 debounce expiry) read [stateAtNow] which performs
 * the same TTL math against the live atomic state + the aggregator's clock.
 *
 * **Thread-safety (D4-A M7)**: entries, coverage and current group live in one
 * immutable [Aggregate] held by one [AtomicReference]. Every CAS winner passes
 * that exact committed value to projection, flow publication and TTL scheduling;
 * transforms are pure and perform no retryable side effects.
 *
 * **One clock domain**: the injected [clock] is the SOLE source of "now" for both REST
 * request-start (in [refresh]) and any internally-derived freshness check. SSE arrival
 * time is supplied by the caller via [StatusAggregatorInput.applySseStatus]'s
 * `sourceTimeMs`; in production both call sites read the same wall clock
 * (`System.currentTimeMillis()`).
 */
@Singleton
class StatusAggregatorImpl internal constructor(
    private val repository: OpenCodeRepository,
    private val identityStore: ConnectionIdentityStore,
    @UiApplicationScope private val scope: CoroutineScope,
    private val clock: () -> Long,
) : StatusAggregator, StatusAggregatorInput {

    /**
     * Internal per-key entry. Carries the [status] label, the [sourceTimeMs] used for merge
     * timing + the TTL freshness check (FGS spec §3.1 / §3 «status TTL»), and a [fresh]
     * flag distinguishing entries that came from a successful REST snapshot this cycle
     * (fresh = true) from SSE-driven or failure entries (fresh = false). The freshness
     * flag is informational; the authoritative freshness verdict used by [globalState] is
     * the TTL check `now - sourceTimeMs <= [STATUS_TTL_MS]` (a REST entry can age out and
     * flip the global verdict without any new write).
     */
    private data class Entry(
        val status: SessionBusyStatus,
        val sourceTimeMs: Long,
        val fresh: Boolean,
    )

    /**
     * D1 (gate #5): coverage metadata held alongside the per-key state map.
     *
     *  - [registeredWorkdirs] — the required set from [StatusSnapshot].
     *  - [coveredWorkdirs] — the host-global response coverage marker. On
     *    success this is exactly registeredWorkdirs, including directories
     *    with zero sessions; on failure it is empty.
     *  - [unmappedActiveIds] — session ids returned by `/session/status`
     *    that were NOT in `sessionsById`. The aggregator classifies each as
     *    [GlobalBusyState.Busy] (positively known active — do NOT skip, do
     *    NOT fabricate a composite key with an invented workdir).
     *  - [lastSuccessTimeMs] — wall-clock of the most recent successful
     *    snapshot commit (REST `Result.success`), used both as the coverage
     *    marker's own TTL anchor (`AllIdleFresh` requires this within TTL)
     *    and as the deadline-source for the passive TTL wake-up. `-1` if no
     *    successful snapshot has ever landed — `AllIdleFresh` is then
     *    refused (cold-start guard).
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

    /** M7: entries, coverage and projection identity commit in one CAS. */
    private val aggregate = AtomicReference(Aggregate.Empty)

    /**
     * D5 (#5): the publication lock. The atomic [aggregate] swap alone is
     * insufficient for R5 — concurrent updaters can interleave the write of
     * the aggregate with the derivation + publication of [_statusByKey] /
     * [_globalBusy] / [_globalState] / [freshnessJob] reschedule, so a
     * derived StateFlow could momentarily publish a verdict computed from
     * an OLDER aggregate while a newer one is already installed (or vice
     * versa, an early reader could pair a new map with a stale verdict).
     *
     * The transform itself MUST stay pure + memory-only (no network work);
     * REST/SSE side-effects happen BEFORE [update] is called. Inside the
     * single critical section: write aggregate → derive map/busy/verdict →
     * publish `_statusByKey` → `_globalBusy` → `_globalState` → cancel /
     * reschedule [freshnessJob]. [stateAtNow] intentionally stays lock-free
     * (it computes from one immutable [Aggregate] snapshot via
     * [AtomicReference.get]); the publication lock only serializes
     * mutations + derived StateFlow writes, not lock-free reads.
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
     * committed [update] to the **earliest current-identity deadline that can
     * alter the projection**. Null when the current state has no Idle deadline
     * (e.g. all `Busy` / `Retry`, or empty state). Runs on the injected
     * [UiApplicationScope] (Main.immediate) so the recompute observes the same
     * memory visibility as the serial state-machine mutations.
     */
    private var freshnessJob: Job? = null

    // ── StatusAggregator.stateAtNow (D1 gate #1) ───────────────────────────

    /**
     * D1 (gate #1): time-correct projection at the instant of the call. Reads
     * the live atomic [aggregate] and recomputes the verdict with
     * the aggregator's own [clock]. Used by the §4.4 idle-debounce expiry so
     * a debounce that fires at exactly `sourceTime + TTL + ε` does not read
     * a stale `AllIdleFresh` from the cached [globalState].
     */
    override fun stateAtNow(): GlobalBusyState =
        project(aggregate.get(), clock())

    // ── StatusAggregatorInput ──────────────────────────────────────────────

    /**
     * REST-driven refresh of the global busy source (FGS spec §3 «Phase 0 主路径»,
     * §3.1 merge timing, §2 epoch guard).
     *
     * **Epoch guard (FGS spec §2 + §3.1, CP4)**: captures
     * `epochAtRequestStart = identityStore.currentEpoch()` BEFORE the REST call.
     * When committing the response (success OR failure), if the current epoch differs
     * from `epochAtRequestStart`, the response is dropped silently — a reconfigure
     * invalidated the in-flight request, and a stale old-epoch response MUST NOT mutate
     * current state.
     *
     * **D1 (gate #5)**: on success, a `sessionId` returned by `/session/status`
     * that is NOT in [StatusSnapshot.sessionsById] is recorded in
     * [Coverage.unmappedActiveIds] and forces [GlobalBusyState.Busy] (do NOT
     * skip, do NOT invent a workdir). Pre-D1 these were silently dropped.
     *
     * **D1 (gate #5)**: [StatusSnapshot.registeredWorkdirs] replaces
     * `sessionsById.values.map(directory)` as the all-idle coverage predicate.
     * A registered workdir with no live session is still required to be
     * covered (by the empty-snapshot coverage marker), so its sessions being
     * archived cannot silently flip the host to `AllIdleFresh`.
     */
    override suspend fun refresh(identity: ConnectionIdentity, snapshot: StatusSnapshot) {
        val requestStartMs = clock()
        val epochAtRequestStart = identityStore.currentEpoch()
        val result = withContext(Dispatchers.IO) { repository.getSessionStatus() }
        // CP4 §2 epoch guard: drop the response if a reconfigure invalidated this request
        // mid-flight. The check happens AFTER the suspend (the only place an epoch bump
        // could have happened) and BEFORE any state mutation.
        if (identityStore.currentEpoch() != epochAtRequestStart) return
        result.fold(
            onSuccess = { statuses ->
                update { current ->
                    val next = current.entries.toMutableMap()
                    val activeKeys = HashSet<SessionStatusKey>()
                    val unmapped = HashSet<String>()
                    for ((sessionId, serverStatus) in statuses) {
                        val session = snapshot.sessionsById[sessionId]
                        if (session == null) {
                            // D1 gate #5: positively known active — server returned
                            // it but we have no workdir mapping. Track the id and
                            // let the projection surface it as Busy.
                            unmapped.add(sessionId)
                            continue
                        }
                        val key = SessionStatusKey(identity.serverGroupFp, session.directory, sessionId)
                        activeKeys.add(key)
                        val prev = next[key]
                        if (prev == null || requestStartMs >= prev.sourceTimeMs) {
                            next[key] = Entry(serverStatus.toSessionBusyStatus(), requestStartMs, fresh = true)
                        }
                    }
                    for ((sessionId, session) in snapshot.sessionsById) {
                        val key = SessionStatusKey(identity.serverGroupFp, session.directory, sessionId)
                        if (key in activeKeys) continue
                        val prev = next[key]
                        if (prev == null || requestStartMs >= prev.sourceTimeMs) {
                            next[key] = Entry(SessionBusyStatus.Idle, requestStartMs, fresh = true)
                        }
                    }
                    current.copy(
                        entries = next.toMap(),
                        coverage = Coverage(
                            registeredWorkdirs = snapshot.registeredWorkdirs,
                            coveredWorkdirs = snapshot.registeredWorkdirs,
                            unmappedActiveIds = unmapped,
                            lastSuccessTimeMs = requestStartMs,
                        ),
                        currentGroupFp = identity.serverGroupFp,
                    )
                }
            },
            onFailure = { markRequestFailedInternal(identity, snapshot, requestStartMs) },
        )
    }

    /**
     * Apply a single SSE-driven status update (FGS spec §3.1 merge timing).
     *
     * Overwrites the entry for [key] iff [sourceTimeMs] `>=` the existing entry's
     * `sourceTimeMs` (equal timestamps also overwrite, so a same-instant SSE frame replaces
     * a prior REST snapshot of the same instant). A strictly older SSE frame is dropped
     * (defensive against out-of-order SSE replay during reconnect).
     *
     * Not a `suspend` function: SSE status frames are control-class events delivered on the
     * bridge's single-consumer channel, and the update is a single CAS step.
     *
     * @param key Composite key — `serverGroupFp` must match the current connection or the
     *  caller's identity check has failed upstream; this class does not second-guess it.
     *  The key's `serverGroupFp` also updates [currentGroupFp] so [globalState] reflects the
     *  most recently active identity (the bridge guarantees only current-identity frames
     *  reach this method).
     * @param status The SSE-observed status (typically `Busy` / `Retry` / `Idle`).
     * @param sourceTimeMs Monotonic arrival time of the SSE event; the merge-timing arbiter.
     */
    override fun applySseStatus(key: SessionStatusKey, status: SessionBusyStatus, sourceTimeMs: Long) {
        update { current ->
            val prev = current.entries[key]
            if (prev == null || sourceTimeMs >= prev.sourceTimeMs) {
                current.copy(
                    entries = current.entries + (key to Entry(status, sourceTimeMs, fresh = false)),
                    currentGroupFp = key.serverGroupFp,
                )
            } else {
                current
            }
        }
    }

    /**
     * Explicit failure entry (FGS spec §3 «请求失败 → 全局 Unknown»). See
     * [StatusAggregatorInput.markRequestFailed] for the contract.
     */
    override fun markRequestFailed(
        identity: ConnectionIdentity,
        snapshot: StatusSnapshot,
        sourceTimeMs: Long,
    ) {
        markRequestFailedInternal(identity, snapshot, sourceTimeMs)
    }

    private fun markRequestFailedInternal(
        identity: ConnectionIdentity,
        snapshot: StatusSnapshot,
        sourceTimeMs: Long,
    ) {
        update { current ->
            val next = current.entries.toMutableMap()
            for ((sessionId, session) in snapshot.sessionsById) {
                val key = SessionStatusKey(identity.serverGroupFp, session.directory, sessionId)
                val prev = next[key]
                if (prev == null || sourceTimeMs >= prev.sourceTimeMs) {
                    next[key] = Entry(SessionBusyStatus.Unknown, sourceTimeMs, fresh = false)
                }
            }
            // Preserve coverage so the all-idle predicate still gates on the
            // registered workdir set; clear unmapped-active (we did not get
            // a server snapshot this cycle) and refuse AllIdleFresh by
            // marking lastSuccessTimeMs as -1 if the prior marker is now
            // stale. The simplest correct move: keep the registered workdirs,
            // drop unmappedActiveIds (no snapshot), and reset
            // lastSuccessTimeMs to -1 so the cold-start / stale guard fires.
            current.copy(
                entries = next.toMap(),
                coverage = current.coverage.copy(
                    registeredWorkdirs = snapshot.registeredWorkdirs,
                    coveredWorkdirs = emptySet(),
                    unmappedActiveIds = emptySet(),
                    lastSuccessTimeMs = -1L,
                ),
                currentGroupFp = identity.serverGroupFp,
            )
        }
    }

    // ── Internal: serialized commit + projection publication ───────────────

    /**
     * D5 (#5) — serialized commit + publication. Replaces the prior CAS loop
     * (D4-A M7): the atomic swap alone did not serialize the derived
     * StateFlow publication, so two concurrent updaters could interleave a
     * new aggregate write with the publication of a verdict computed from
     * the older one (R5 violation).
     *
     * Contract:
     *  - [transform] is PURE + memory-only — it is invoked INSIDE the
     *    critical section, so it MUST NOT perform network/REST work or any
     *    retryable side-effect (all REST/SSE work happens BEFORE [update]
     *    is called).
     *  - Inside the single critical section: write aggregate → derive
     *    map/busy/verdict → publish `_statusByKey` → `_globalBusy` →
     *    `_globalState` → cancel/reschedule [freshnessJob].
     *
     * D1 (gate #1): after every committed swap, [publishLocked] arms the
     * single passive-TTL wake-up for the earliest deadline that can flip the
     * projection (each Idle entry's `sourceTimeMs + STATUS_TTL_MS + 1`, plus
     * the coverage marker's TTL).
     *
     * **Always recomputes**: the pre-D1 `next === current` short-circuit
     * broke the empty-snapshot path — `emptyMap().toMutableMap().toMap()`
     * returns the emptyMap singleton, so a successful REST refresh that
     * produced zero map writes (but DID update [coverage]) would skip
     * recompute and the projection never saw the new coverage marker. The
     * recompute is idempotent (a no-op if neither map nor coverage changed),
     * so unconditionally running it on every commit is cheap and the
     * coverage atomic guarantees the projection sees the latest coverage
     * value committed alongside the state map.
     */
    private inline fun update(transform: (Aggregate) -> Aggregate) {
        synchronized(commitPublishLock) {
            val current = aggregate.get()
            val next = transform(current)
            aggregate.set(next)
            publishLocked(next, clock())
        }
    }

    /**
     * D5 (#5) — derives + publishes the three projected [StateFlow]s for the
     * committed [Aggregate]. **MUST be called holding [commitPublishLock]**;
     * callers outside [update] MUST acquire the lock first (the
     * [freshnessJob] wake-up does so before recomputing the TTL-adjusted
     * verdict from the live aggregate).
     *
     * Publication order: `_statusByKey` → `_globalBusy` → `_globalState`
     * (so verdict observers cannot pair a new state with the previous
     * commit's status map), then [rescheduleFreshnessLocked].
     */
    private fun publishLocked(committed: Aggregate, now: Long) {
        val map = committed.entries
        val cov = committed.coverage
        val verdict = project(committed, now)
        // globalBusy: any Busy||Retry under the current identity OR any
        // unmapped-active id (D1 gate #5). Kept for back-compat with non-
        // lifecycle consumers.
        val fp = committed.currentGroupFp
        val scoped = map.filterKeys { it.serverGroupFp == fp }
        val anyBusy = scoped.any { entry ->
            entry.value.status == SessionBusyStatus.Busy ||
                entry.value.status == SessionBusyStatus.Retry
        } || cov.unmappedActiveIds.isNotEmpty()
        // Publish the key projection before the verdict derived from the same
        // committed Aggregate, so verdict observers cannot pair a new state
        // with the previous commit's status map.
        _statusByKey.value = map.mapValues { it.value.status }
        _globalBusy.value = anyBusy
        _globalState.value = verdict
        rescheduleFreshnessLocked(committed, now)
    }

    /**
     * D1 (gate #1): the pure projection used by BOTH the [globalState]
     * recompute (via [recompute]) and the time-correct [stateAtNow] read.
     * Encodes the freshness boundary, the unmapped-active rule, and the
     * registered-workdir coverage predicate.
     *
     * **Freshness boundary** (FGS spec §3): `now - sourceTimeMs <=
     * STATUS_TTL_MS` is fresh. Stale `Idle` → contributes `Unknown`; stale
     * `Busy` / `Retry` stays `Busy` (conservative — never silently drop
     * keep-alive on a possibly-still-busy session).
     *
     * **Unmapped-active** (D1 gate #5): any entry in
     * [Coverage.unmappedActiveIds] forces `Busy` (positively known active —
     * the server returned the id under a busy/retry status but the snapshot
     * had no workdir mapping for it).
     *
     * **Registered-workdir coverage** (D1 gate #5): `AllIdleFresh` is legal
     * ONLY when (a) [Coverage.lastSuccessTimeMs] is within TTL (cold-start /
     * stale-success guard), (b) `unmappedActiveIds` is empty, (c) every
     * workdir in [Coverage.registeredWorkdirs] is covered by a fresh-Idle
     * entry in [map] under the current identity's `serverGroupFp`, (d) every
     * known tracked session under that fp is mapped (no `Unknown`), and (e)
     * every tracked status is fresh `Idle`. A successful empty host snapshot
     * with zero registered workdirs is `AllIdleFresh` (backed by the fresh
     * coverage marker — NOT vacuous uninitialized state).
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
        // A successful empty host snapshot writes lastSuccessTimeMs = requestStartMs
        // (within TTL on the same cycle), so this guard refuses ONLY the never-
        // refreshed-yet cold-start case.
        val freshSuccess =
            cov.lastSuccessTimeMs >= 0L && now - cov.lastSuccessTimeMs <= STATUS_TTL_MS
        if (!freshSuccess) return GlobalBusyState.Unknown

        // gate #5: registered-workdir coverage predicate. Every workdir in
        // the snapshot's registeredWorkdirs must be covered by at least one
        // fresh entry in the current projection. A registered workdir with
        // no live session falls out here (its directory is in
        // registeredWorkdirs but no entry in `scoped` has that workdir) → Unknown.
        val allWorkdirsCovered = cov.coveredWorkdirs.containsAll(cov.registeredWorkdirs)
        if (!allWorkdirsCovered) return GlobalBusyState.Unknown

        // gate #5 (e/f) already enforced above: every entry fresh Idle, no
        // Unknown, no Busy. We are authoritative all-idle.
        return GlobalBusyState.AllIdleFresh
    }

    /**
     * D1 (gate #1) + D5 (#5): schedule a single [freshnessJob] for the
     * earliest current-identity deadline that can alter the projection.
     * Busy/Retry entries need no wake-up (they stay conservatively busy);
     * Idle entries flip to Unknown at `sourceTimeMs + STATUS_TTL_MS + 1`.
     * The successful-snapshot coverage marker
     * (`coverage.lastSuccessTimeMs`) is itself a deadline source: when it
     * ages out the empty-state guard can flip the verdict.
     *
     * Only **strictly future** deadlines are scheduled — past deadlines
     * were already consumed by the [publishLocked] that called this method
     * (the projection saw `now > deadline` and applied the stale/Unknown
     * verdict synchronously). Scheduling a 0-delay launch on an
     * Unconfined-style scope would otherwise infinite-loop on the same
     * stale deadline.
     *
     * D5 (#5): **MUST be called holding [commitPublishLock]** (the prior
     * `@Synchronized` monitor is replaced by the publication lock so the
     * freshness reschedule stays serialized with derived StateFlow
     * publication — a stale `freshnessJob` could otherwise race a new
     * publication). The wake-up coroutine itself ACQUIRES
     * [commitPublishLock] before recomputing + republishing so it observes
     * the same memory visibility as the serial state-machine mutations.
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
        // Also consider the coverage marker's own TTL — when lastSuccessTimeMs
        // ages out, the empty-state / cold-start guard can flip the verdict.
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
                // D5 (#5): acquire commitPublishLock so the TTL-adjusted
                // recompute + republish observe the same memory visibility
                // as serial state-machine mutations. Without the lock a
                // concurrent update could install a newer aggregate + we'd
                // publish a verdict computed from the older one (R5).
                synchronized(commitPublishLock) {
                    val live = aggregate.get()
                    val liveNow = clock()
                    publishLocked(live, liveNow)
                }
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
         * FGS spec §3 «status TTL»: an entry is "fresh" only within ≈30s of its source
         * time. Used ONLY for the [globalState] idle-grace verdict — stale `Busy`/`Retry`
         * entries stay `Busy` (conservative), stale `Idle` entries fall back to `Unknown`
         * (do not enter idle grace on stale data).
         *
         * **D1 (gate #1)**: the boundary is `now - sourceTimeMs <= STATUS_TTL_MS` (fresh),
         * flipping to stale at `STATUS_TTL_MS + 1`. The passive [freshnessJob] targets
         * `sourceTimeMs + STATUS_TTL_MS + 1` so a fresh-Idle entry autonomously expires
         * to `Unknown` even without any subsequent map mutation.
         */
        const val STATUS_TTL_MS = 30_000L
    }
}
