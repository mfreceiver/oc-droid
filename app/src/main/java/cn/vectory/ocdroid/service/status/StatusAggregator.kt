package cn.vectory.ocdroid.service.status

import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import kotlinx.coroutines.flow.StateFlow

/**
 * Authoritative global busy-source contract (FGS spec §3 / dev-design P0.4).
 *
 * Implemented by `StatusAggregatorImpl` (Lane A) and consumed by the lifecycle
 * coordinator (Lane C) and the notification display layer (Phase 1). Defined here
 * in the contract layer — ahead of any implementation — so Lane A (impl) and
 * Lane C (consumer) can be built **in parallel** against the same surface instead
 * of one blocking the other.
 *
 * **Authoritativeness rules the impl must enforce** (FGS spec §3 / §3.1):
 *  - busy is **global**, keyed by [SessionStatusKey] = `(serverGroupFp, workdir, sessionId)`;
 *  - the Phase-0 main path consumes the host-level `getSessionStatus()` once and bins each
 *    `sessionId` to its workdir via `session.directory` joined through `SessionTree.allSessionsById`;
 *  - a failed status fetch labels every known session [SessionBusyStatus.Unknown], and
 *    `Unknown` **must not** enter the idle grace window;
 *  - a REST response **must not** overwrite an SSE status whose source time is later than
 *    the REST request-start (merge timing, FGS spec §3.1);
 *  - a status TTL applies (≈30s); only when **all** registered workdirs are simultaneously
 *    fresh + `Idle` may the lifecycle coordinator enter the idle debounce.
 *
 * **CP4 (notify Phase-0)**: the previous unsafe `globalBusy: Boolean` is superseded by the
 * tri-state [globalState] (FGS spec §3 «请求失败 → 全局 Unknown, 不得进 idle 宽限期» +
 * §3.1 TTL). [globalBusy] is retained as a `Busy||Retry` convenience for non-lifecycle
 * consumers (notification display); the lifecycle coordinator MUST consume [globalState].
 */
interface StatusAggregator {
    /**
     * Tri-state, lifecycle-authoritative projection of the global busy source
     * (FGS spec §3 + §3.1, CP4). Distinct from the legacy [globalBusy] boolean:
     *
     *  - [GlobalBusyState.Busy] — at least one tracked session under the current
     *    identity's `serverGroupFp` is `Busy` or `Retry`. The lifecycle
     *    coordinator keeps the source alive and the FGS slot hot.
     *  - [GlobalBusyState.AllIdleFresh] — every tracked session under the current
     *    identity is `Idle` AND its source time is within the status TTL (≈30s,
     *    FGS spec §3). Only this state may arm the 45s idle debounce.
     *  - [GlobalBusyState.Unknown] — request failed, no fresh data yet, OR any
     *    tracked session is `Unknown` / stale. The lifecycle coordinator MUST
     *    NOT enter the idle grace window on this state (it keeps the source
     *    alive / retries).
     *
     * Empty state (no entries under the current identity — cold start before the
     * first [StatusAggregatorInput.refresh]) is [GlobalBusyState.Unknown]: the
     * aggregator refuses to authoritatively label the host idle until it has
     * observed at least one fresh successful snapshot.
     *
     * **D1 (gate #1)**: the value lags the wall clock by up to the dispatcher
     * latency of the scheduled [freshnessJob] — observers that need the
     * time-correct verdict at a specific instant (e.g. the §4.4 idle-debounce
     * expiry) MUST read [stateAtNow] instead. [globalState] is kept consistent
     * by a passive TTL wake-up at the earliest deadline that can flip the
     * projection (§3 «status TTL actively expires»).
     */
    val globalState: StateFlow<GlobalBusyState>

    /**
     * D1 (gate #1, FGS spec §3 + §4.4): time-correct projection of
     * [globalState] at the instant of the call.
     *
     * **MUST** be used at any instant where a state-transition decision is
     * made on a wall-clock boundary (the §4.4 idle-debounce expiry being the
     * canonical case). [globalState] is kept consistent by a passive TTL
     * wake-up but its `.value` reflects the last committed recompute, which
     * can lag `now` by the dispatcher latency of the wake-up coroutine. A
     * 45s debounce that fires at exactly `t = sourceTime + TTL + ε` would
     * otherwise read a stale `AllIdleFresh` from [globalState] and emit
     * `StopSse` on stale-idle data (gate #1 / §3 violation).
     *
     * The implementation reads the same atomic state map as [recompute] but
     * uses the aggregator's own [clock] for the freshness verdict (NOT the
     * `globalState.value` cache).
     */
    fun stateAtNow(): GlobalBusyState

    /**
     * True iff any tracked session under the current connection identity is
     * [SessionBusyStatus.Busy] or [SessionBusyStatus.Retry]. Convenience for
     * non-lifecycle consumers (Phase-1 notification display «N tasks in
     * progress»); **do not** use this to drive the idle-grace decision —
     * a failure yields `Unknown` (not `Idle`), so [globalBusy] would be `false`
     * while [globalState] is [GlobalBusyState.Unknown]. Use [globalState] for
     * any keep-alive / idle-debounce decision.
     */
    val globalBusy: StateFlow<Boolean>

    /**
     * Per-composite-key status snapshot. Consumed by the notification layer for
     * ongoing/completion content and by debug surfaces. Implementations must
     * apply the merge-timing rule (FGS spec §3.1) on every update.
     */
    val statusByKey: StateFlow<Map<SessionStatusKey, SessionBusyStatus>>
}

/**
 * Authoritative, lifecycle-safe global busy verdict (FGS spec §3 / §3.1).
 *
 * See [StatusAggregator.globalState] for the contract.
 *
 * **Conservative rule** (FGS spec §3 «请求失败 → 全局 Unknown, 不得进 idle 宽限期»):
 * only [AllIdleFresh] permits the idle debounce. [Unknown] is treated like
 * [Busy] for keep-alive — a failed fetch or a stale entry MUST NOT clear the
 * source / enter the idle grace window, because that would silently drop
 * keep-alive on real busy sessions.
 */
enum class GlobalBusyState {
    /**
     * At least one tracked session under the current identity is
     * [SessionBusyStatus.Busy] or [SessionBusyStatus.Retry]. Source stays
     * alive; FGS slot stays hot; no idle debounce.
     */
    Busy,

    /**
     * Every tracked session under the current identity is
     * [SessionBusyStatus.Idle] AND its source time is within the status TTL
     * (≈30s). The ONLY state that permits arming the 45s idle debounce
     * (FGS spec §3 + §4.4).
     */
    AllIdleFresh,

    /**
     * Request failure, no fresh data observed yet, OR any tracked session is
     * [SessionBusyStatus.Unknown] / stale (> TTL). MUST NOT enter the idle
     * grace window — the lifecycle coordinator keeps the source alive /
     * retries (treated like [Busy] for keep-alive).
     */
    Unknown,
}

/**
 * True iff this state is one of the "keep-alive" states — anything but
 * [GlobalBusyState.AllIdleFresh]. Used by the lifecycle coordinator to gate
 * the idle debounce: only [GlobalBusyState.AllIdleFresh] may arm it.
 */
val GlobalBusyState.isKeepAlive: Boolean get() = this != GlobalBusyState.AllIdleFresh

/**
 * Input surface for the [StatusAggregator] (FGS spec §3 + §3.1, CP4).
 *
 * Separated from the read-only [StatusAggregator] outputs so injectors
 * (`SessionSyncCoordinator`, the bootstrap coordinator, AppCore, …) can feed
 * the aggregator without depending on the concrete `StatusAggregatorImpl`.
 * `StatusAggregatorImpl` implements BOTH interfaces; Hilt binds both to the
 * same `@Singleton` instance (see `StatusModule`).
 *
 * Authoring rules enforced inside the impl:
 *  - [refresh] captures the request-start clock AND `identityStore.currentEpoch()`
 *    BEFORE the REST call, and DROPS the response if the epoch changed mid-
 *    request (host reconfigure, FGS spec §2 + §3.1).
 *  - [applySseStatus] applies merge timing (source-time wins) and does NOT
 *    require an epoch check (the SSE collector / bridge validates identity
 *    upstream — only current-identity frames reach this method).
 *  - [markRequestFailed] is the explicit failure entry for callers that did
 *    not route their REST load through [refresh] — every known session becomes
 *    `Unknown` (subject to merge timing so a fresher `Busy`/`Retry` survives).
 */
interface StatusAggregatorInput {
    /**
     * REST-driven refresh of the global busy source (FGS spec §3 «Phase 0 主路径»,
     * §3.1 merge timing, §2 epoch guard).
     *
     * Captures `requestStartMs = clock()` AND `epochAtRequestStart =
     * identityStore.currentEpoch()` BEFORE the REST call. On commit the
     * response is DROPPED if the epoch has changed mid-request (host
     * reconfigure invalidated the in-flight request).
     *
     * See `StatusAggregatorImpl.refresh` for the full success / failure
     * semantics (binning, merge-timing, Unknown-on-failure).
     *
     * @param identity The current connection identity — its `serverGroupFp`
     *  scopes the composite keys and the [StatusAggregator.globalState]
     *  projection.
     * @param snapshot The merged 3-source `id → Session` snapshot (produced
     *  upstream by `SessionTree.allSessionsById` via
     *  [SessionSnapshotProvider]) AND the registered-workdir coverage set.
     *  Used to resolve `sessionId → directory` AND to enforce the
     *  `AllIdleFresh` coverage predicate (FGS spec §3 «只有所有已登记 workdir
     *  都取得新鲜+成功 idle 才进停流宽限期»).
     */
    suspend fun refresh(identity: ConnectionIdentity, snapshot: StatusSnapshot)

    /**
     * Apply a single SSE-driven status update (FGS spec §3.1 merge timing).
     * See `StatusAggregatorImpl.applySseStatus` for the full contract.
     *
     * @param key Composite key — `serverGroupFp` must match the current
     *  connection (validated upstream by the bridge / SSC identity check).
     * @param status The SSE-observed status.
     * @param sourceTimeMs Monotonic arrival time of the SSE event; the
     *  merge-timing arbiter. The caller and the impl share one clock domain
     *  (the impl's injected `clock: () -> Long`).
     */
    fun applySseStatus(key: SessionStatusKey, status: SessionBusyStatus, sourceTimeMs: Long)

    /**
     * Explicit failure entry — every known session under [identity] becomes
     * [SessionBusyStatus.Unknown], subject to merge timing so a fresher prior
     * `Busy`/`Retry` survives (FGS spec §3 «请求失败 → 全局 Unknown»). Used by
     * callers that did NOT route their REST load through [refresh] (e.g. a
     * future poller failure path).
     *
     * @param identity The current connection identity.
     * @param snapshot The merged `id → Session` snapshot + registered-workdir
     *  coverage set (same shape as [refresh] takes — the coverage set is
     *  preserved across the failure so `AllIdleFresh` cannot falsely pass on
     *  the post-failure `Unknown` entries).
     * @param sourceTimeMs The failure's effective time — merge-timed against
     *  each entry's existing source time so a fresher observation wins.
     */
    fun markRequestFailed(
        identity: ConnectionIdentity,
        snapshot: StatusSnapshot,
        sourceTimeMs: Long,
    )
}
