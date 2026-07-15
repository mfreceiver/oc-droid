package cn.vectory.ocdroid.ui

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * §history-load-fix: serializes message-list mutations across the three load
 * paths ([launchLoadMessages] / [launchLoadMoreMessages] / [launchCatchUp]) on
 * a per-session [Mutex].
 *
 * Why: a user "load more history" click was silently dropped while a background
 * reload ([ChatState.isLoadingMessages]) was in flight — the 0.6.0 "加载历史
 * 对话需要多次点击" regression (the three paths shared one
 * [ChatState.isLoadingMessages] flag as both a guard and a coarse lock). Now
 * each path has its own flag, so the click is never dropped; this coordinator
 * supplies the ACTUAL mutual exclusion the shared flag used to provide, so a
 * concurrent loadMore-prepend and a loadMessages-full-window-replace cannot
 * tear the list or lose an update.
 *
 * Design:
 * - Per-session (keyed by sessionId), not global: different sessions never
 *   contend. (Originally mirrored the per-session mutex pattern of a
 *   since-deleted gap-fill coordinator; retained as the catch-up / loadMore /
 *   loadMessages serialization primitive.)
 * - Guards ONLY the slice mutation critical section, NEVER the network call —
 *   in-flight requests run concurrently; only the read-compute-write of the
 *   chat slice is serialized. So a background reload and a user loadMore fetch
 *   at the same time, then apply their mutations one after the other (each
 *   re-reading fresh slice state inside the lock → correct merge).
 * - Locks are never removed (a closed session's lock lingers, a few hundred
 *   bytes each). Acceptable for a single-user client and avoids concurrency on
 *   the map itself.
 *
 * Callers MUST re-validate the compound key (sessionId + serverGroupFp) inside
 * the lock — a session/host switch while waiting for the lock must NOT write a
 * stale result into the now-current slice.
 *
 * Owned by [SharedStateStore] (a @Singleton), so every caller via
 * [SliceFlows.messageLoadCoordinator] shares one lock map. Not Hilt-provided
 * (it is stateless beyond the lock map; nothing to inject or mock).
 */
class MessageLoadCoordinator {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withSessionLock(sessionId: String, block: suspend () -> T): T =
        locks.computeIfAbsent(sessionId) { Mutex() }.withLock { block() }
}
