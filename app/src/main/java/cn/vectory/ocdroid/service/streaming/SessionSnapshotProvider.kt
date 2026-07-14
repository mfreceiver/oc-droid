package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.service.status.StatusSnapshot

/**
 * FGS spec §3 — read access to the merged `id → Session` map + the
 * registered-workdir coverage set for the global status refresh. The poller
 * and the §5 bootstrap both call
 * [StatusAggregatorInput.refresh][cn.vectory.ocdroid.service.status.StatusAggregatorInput.refresh]
 * which bins each sessionId to its workdir via `sessionsById[sessionId].directory`
 * AND uses [StatusSnapshot.registeredWorkdirs] as the all-idle coverage
 * predicate (FGS spec §3 «只有所有已登记 workdir 都取得新鲜+成功 idle 才进停流宽限期»).
 *
 * Extracted as a tiny fun-interface so [SessionStreamingController] is pure-JVM
 * testable (fake returns a fixed snapshot; production reads from
 * `SharedStateStore` + `SettingsManager`).
 *
 * **D1 (gate #5)**: the pre-D1 contract returned `Map<String, Session>` only,
 * which collapsed the universe to the sessions map — a registered workdir
 * whose sessions were all archived vanished from coverage. The new contract
 * returns [StatusSnapshot] so the aggregator can positively enforce
 * registered-workdir coverage AND positively classify an active id returned
 * by `/session/status` that is NOT in `sessionsById` as `Busy` (not skipped).
 */
fun interface SessionSnapshotProvider {
    /**
     * Returns the merged 3-source `id → Session` snapshot + the
     * registered-workdir coverage set, scoped to the current connection
     * identity's `serverGroupFp` (see [StatusSnapshot] for identity scope).
     */
    suspend fun current(): StatusSnapshot
}
