package cn.vectory.ocdroid.service.status

import cn.vectory.ocdroid.data.model.SessionStatus

/**
 * Map a transport-level [SessionStatus] (server `session.status` payload, the
 * same model consumed by the UI's `sessionStatuses` slice) to the aggregator's
 * authoritative [SessionBusyStatus] (FGS spec §3).
 *
 * Used by the SSE `session.status` feed path in
 * [cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator] and shared with
 * `StatusAggregatorImpl`'s private copy so the SSE + REST paths derive the
 * SAME label for a given transport status (no second truth).
 *
 * Unknown / unrecognized types default to [SessionBusyStatus.Busy] — the
 * conservative keep-alive choice (a `type` the client doesn't recognise is
 * treated as an active task rather than risk silently dropping keep-alive).
 */
fun SessionStatus.toSessionBusyStatus(): SessionBusyStatus = when {
    isRetry -> SessionBusyStatus.Retry
    isBusy -> SessionBusyStatus.Busy
    isIdle -> SessionBusyStatus.Idle
    else -> SessionBusyStatus.Busy
}
