package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.status.StatusSnapshot
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.util.SettingsManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [SessionSnapshotProvider]: merges the three session sources from
 * [SharedStateStore.sessionListFlow] (sessions + directorySessions +
 * childSessions, deduped by id) — exactly the same shape as
 * `SessionTree.allSessionsById` (kept `internal` so we re-derive here to keep
 * the streaming package off the UI controller's `internal` surface).
 *
 * Singleton because the snapshot is read by both the poller and the §5
 * bootstrap path on the streaming scope; [SharedStateStore] is itself a
 * `@Singleton`, so the read is consistent across both call sites.
 *
 * **D1 (gate #5)**: now also returns the **registered-workdir coverage set**
 * — `recentWorkdirs(currentFp) + currentWorkdir + directorySessions.keys +
 * sessionsById.values.map(Session::directory)` (deduped). The aggregator uses
 * this set as the all-idle coverage predicate: `AllIdleFresh` is legal only
 * when every workdir in this set is covered by a fresh successful
 * observation (FGS spec §3). A registered workdir whose sessions were all
 * archived is represented here (not in `sessionsById`) so the all-idle
 * predicate cannot falsely pass.
 */
@Singleton
class SharedStateStoreSessionSnapshotProvider @Inject constructor(
    private val store: SharedStateStore,
    private val settingsManager: SettingsManager,
    private val identityStore: ConnectionIdentityStore,
) : SessionSnapshotProvider {

    override suspend fun current(): StatusSnapshot {
        val sl = store.sessionListFlow.value
        val sessionsById: Map<String, Session> = buildMap {
            sl.sessions.forEach { putIfAbsent(it.id, it) }
            sl.directorySessions.values.flatten().forEach { putIfAbsent(it.id, it) }
            sl.childSessions.values.flatten().forEach { putIfAbsent(it.id, it) }
        }
        val currentFp = identityStore.currentIdentity.value?.serverGroupFp
        val registeredWorkdirs: Set<String> = buildSet {
            // Recent workdirs for the current host's serverGroupFp (MRU memory
            // — survives cold start / archive).
            if (!currentFp.isNullOrEmpty()) {
                settingsManager.getRecentWorkdirs(currentFp).forEach { add(it) }
            }
            // The user's explicitly selected workdir (SettingsManager).
            settingsManager.currentWorkdir?.let { add(it) }
            // directorySessions carries one entry per workdir that has at
            // least one session — covers any workdir the host has shown us.
            sl.directorySessions.keys.forEach { add(it) }
            // Every session's directory is implicitly a registered workdir.
            sessionsById.values.forEach { add(it.directory) }
        }
        return StatusSnapshot(
            sessionsById = sessionsById,
            registeredWorkdirs = registeredWorkdirs,
        )
    }
}
