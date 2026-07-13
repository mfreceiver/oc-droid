package cn.vectory.ocdroid.service.status

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
 */
interface StatusAggregator {
    /**
     * True iff any tracked session under the current connection identity is
     * [SessionBusyStatus.Busy] or [SessionBusyStatus.Retry]. Drives L1→L2 FGS
     * promotion (FGS spec §4.2) and the ongoing «N tasks in progress» notification.
     */
    val globalBusy: StateFlow<Boolean>

    /**
     * Per-composite-key status snapshot. Consumed by the notification layer for
     * ongoing/completion content and by the lifecycle coordinator for the
     * «all idle» decision. Implementations must apply the merge-timing rule
     * (FGS spec §3.1) on every update.
     */
    val statusByKey: StateFlow<Map<SessionStatusKey, SessionBusyStatus>>
}
