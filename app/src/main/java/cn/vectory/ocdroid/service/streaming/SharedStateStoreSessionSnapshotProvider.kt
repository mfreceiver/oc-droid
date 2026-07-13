package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.ui.SharedStateStore
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
 */
@Singleton
class SharedStateStoreSessionSnapshotProvider @Inject constructor(
    private val store: SharedStateStore,
) : SessionSnapshotProvider {

    override suspend fun current(): Map<String, Session> {
        val sl = store.sessionListFlow.value
        return buildMap {
            sl.sessions.forEach { putIfAbsent(it.id, it) }
            sl.directorySessions.values.flatten().forEach { putIfAbsent(it.id, it) }
            sl.childSessions.values.flatten().forEach { putIfAbsent(it.id, it) }
        }
    }
}
