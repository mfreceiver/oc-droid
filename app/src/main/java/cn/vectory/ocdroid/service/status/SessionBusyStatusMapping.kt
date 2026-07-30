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

/**
 * §P0-A Lane 2: the REVERSE mapping — [SessionBusyStatus] → transport
 * [SessionStatus], used by the aggregator's `applySseStatus` adapter to funnel
 * an SSE-driven [SessionBusyStatus] back through the authority reducer (which
 * consumes [SessionStatus], the same model the UI projection + every other
 * writer uses — single truth, single shape).
 *
 * `Unknown` / `Fresh` have NO transport representation (`SessionStatus.type`
 * is `idle`/`busy`/`retry` only). [StatusAggregatorInput.applySseStatus] is
 * contracted to be called ONLY with `Busy`/`Retry`/`Idle` (SSE never emits
 * Unknown); the reverse mapper [error]s on the unmappable values so a future
 * caller cannot silently fabricate an authority entry from a non-transport
 * status.
 */
fun SessionBusyStatus.toSessionStatus(): SessionStatus = when (this) {
    SessionBusyStatus.Busy -> SessionStatus(type = "busy")
    SessionBusyStatus.Retry -> SessionStatus(type = "retry")
    SessionBusyStatus.Idle -> SessionStatus(type = "idle")
    SessionBusyStatus.Unknown,
    SessionBusyStatus.Fresh -> error("SessionBusyStatus.$this has no transport SessionStatus representation")
}
