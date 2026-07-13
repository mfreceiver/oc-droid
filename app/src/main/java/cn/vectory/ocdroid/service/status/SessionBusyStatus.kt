package cn.vectory.ocdroid.service.status

/**
 * Authoritative per-session busy status, keyed by the composite [SessionStatusKey].
 *
 * See FGS spec §3 «权威 busy 数据源»: busy is **global**, keyed by
 * `(serverGroupFp, workdir, sessionId) → Fresh | Busy | Retry | Idle | Unknown`.
 *
 * Phase 0 main path (FGS spec §3): consume the existing host-level `getSessionStatus()` once,
 * bin each `sessionId` to its workdir via `session.directory`, and label each entry with one of
 * the values below. A failed request labels **every** known session `Unknown` — and `Unknown`
 * **must not** enter the idle grace window («请求失败 → 全局 Unknown, 不得进 idle 宽限期»).
 *
 * @property Fresh Just-fetched-this-cycle snapshot entry. Marks the value as a current-cycle
 *  observation rather than a cached/TTL-expired one; consumed by `StatusAggregator` for
 *  freshness tracking and merge timing (FGS spec §3.1).
 * @property Busy An active task is running for this session. Drives L1→L2 FGS promotion
 *  (FGS spec §4.2) and the ongoing «N tasks in progress» notification (FGS spec §7).
 * @property Retry The connection for this session is reconnecting / retrying. Treated like
 *  `Busy` for keep-alive decisions (must not enter idle grace).
 * @property Idle Authoritatively idle: a fresh + successful status fetch returned no active
 *  task. Only when **all** registered workdirs are simultaneously `Idle` (and fresh) may the
 *  lifecycle coordinator enter the idle debounce / L2-idle window (FGS spec §3 «status TTL»).
 * @property Unknown The status request failed or the session could not be resolved. Per FGS
 *  spec §3 this value **must not** enter the idle grace period — treating failure as idle
 *  would silently drop keep-alive on real busy sessions.
 */
enum class SessionBusyStatus {
    Fresh,
    Busy,
    Retry,
    Idle,
    Unknown,
}

/**
 * Composite key for per-session busy status.
 *
 * See FGS spec §3: busy is keyed by `(serverGroupFp, workdir, sessionId)` so that two
 * sessions with the same `sessionId` on different hosts / workdirs cannot collide, and
 * cross-host stale frames are rejected at the key level (host switch → different
 * `serverGroupFp` → stale entries invisible to the new connection).
 *
 * Used by `StatusAggregator` (dev-design P0.4) as the map key for
 * `(serverGroupFp, workdir, sessionId) → SessionBusyStatus`, and by the notification layer
 * as the stable identity inside `NotificationId` (dev-design §4.1).
 *
 * @property serverGroupFp Fingerprint of the server group (host/profile group).
 * @property workdir The working directory the session lives in.
 * @property sessionId The opencode session identifier.
 */
data class SessionStatusKey(
    val serverGroupFp: String,
    val workdir: String,
    val sessionId: String,
)
