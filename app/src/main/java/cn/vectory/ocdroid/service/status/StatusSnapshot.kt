package cn.vectory.ocdroid.service.status

import cn.vectory.ocdroid.data.model.Session

/**
 * D1 (gate #5): merged session snapshot **+ registered-workdir coverage** for
 * the authoritative busy source (FGS spec §3).
 *
 * Carries everything [StatusAggregatorImpl.refresh] / [StatusAggregatorImpl.markRequestFailed]
 * needs to produce a coverage-complete [StatusAggregator.globalState] verdict:
 *
 *  - [sessionsById] — the merged 3-source `id → Session` map (sessions +
 *    directorySessions + childSessions, deduped by id). Same shape as
 *    `SessionTree.allSessionsById`.
 *  - [registeredWorkdirs] — the **required coverage set**: every workdir the
 *    host has ever had a session in (or the user has explicitly registered)
 *    for the current `serverGroupFp`. `AllIdleFresh` is legal ONLY when every
 *    workdir in this set is covered by a fresh successful observation (FGS
 *    spec §3 «只有所有已登记 workdir 都取得新鲜+成功 idle 才进停流宽限期»).
 *    A registered workdir with no live session is represented here (not in
 *    `sessionsById`) so the all-idle predicate cannot falsely pass when a
 *    workdir's sessions have all been archived server-side.
 *
 * D1 design rationale: the pre-D1 aggregator treated `sessionsById` as the
 * universe — a workdir whose sessions were all archived vanished from the
 * coverage check, so `AllIdleFresh` could pass while a returned-but-unmapped
 * active id was silently dropped (gate #5). Splitting the snapshot into
 * `sessionsById` (mapping resolution) + `registeredWorkdirs` (coverage
 * predicate) makes the two concerns explicit and closes the gap.
 *
 * **Identity scope**: both fields are scoped to the current connection's
 * `serverGroupFp` (the snapshot provider reads the bound identity's fp to
 * compute `recentWorkdirs(fp)`). Callers MUST pass a snapshot consistent
 * with the identity they pair it with in [StatusAggregatorInput.refresh].
 */
data class StatusSnapshot(
    val sessionsById: Map<String, Session>,
    val registeredWorkdirs: Set<String>,
) {
    companion object {
        val Empty: StatusSnapshot = StatusSnapshot(emptyMap(), emptySet())
    }
}
