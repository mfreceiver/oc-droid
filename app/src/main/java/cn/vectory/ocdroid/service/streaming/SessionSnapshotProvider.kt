package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.data.model.Session

/**
 * FGS spec §3 — read access to the merged `id → Session` map for the global
 * status refresh. The poller and the §5 bootstrap both call
 * [StatusAggregatorInput.refresh][cn.vectory.ocdroid.service.status.StatusAggregatorInput.refresh]
 * which bins each sessionId to its workdir via `sessionsById[sessionId].directory`.
 *
 * Extracted as a tiny fun-interface so [SessionStreamingController] is pure-JVM
 * testable (fake returns a fixed map; production reads from `SharedStateStore`).
 */
fun interface SessionSnapshotProvider {
    /**
     * Returns the merged 3-source `id → Session` snapshot (sessions +
     * directorySessions + childSessions, deduped by id — matches
     * `SessionTree.allSessionsById`).
     */
    suspend fun current(): Map<String, Session>
}
