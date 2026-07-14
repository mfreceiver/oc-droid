package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.mergeStatusSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class CompleteTreeHydration(
    val childrenByParent: Map<String, List<Session>>,
    val completeRootIds: Set<String>,
)

private data class RootHydration(
    val rootId: String,
    val childrenByParent: Map<String, List<Session>>,
    val complete: Boolean,
)

/** Recursively loads roots with bounded root-level concurrency. */
internal suspend fun loadCompleteSessionTrees(
    repository: OpenCodeRepository,
    roots: List<Session>,
    maxConcurrency: Int = 6,
    shouldContinue: () -> Boolean = { true },
): CompleteTreeHydration = coroutineScope {
    val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
    val hydrated = roots.distinctBy { it.id }.map { root ->
        async {
            semaphore.withPermit { hydrateRoot(repository, root, shouldContinue) }
        }
    }.awaitAll()
    CompleteTreeHydration(
        childrenByParent = buildMap {
            hydrated.filter { it.complete }.forEach { putAll(it.childrenByParent) }
        },
        completeRootIds = hydrated.filterTo(mutableSetOf()) { it.complete }.mapTo(mutableSetOf()) { it.rootId },
    )
}

private suspend fun hydrateRoot(
    repository: OpenCodeRepository,
    root: Session,
    shouldContinue: () -> Boolean,
): RootHydration {
    val children = LinkedHashMap<String, List<Session>>()
    val queue = ArrayDeque<Session>().apply { add(root) }
    val fetched = HashSet<String>()
    while (queue.isNotEmpty()) {
        if (!shouldContinue()) return RootHydration(root.id, emptyMap(), complete = false)
        val parent = queue.removeFirst()
        if (!fetched.add(parent.id)) continue
        val direct = repository.getChildren(parent.id).getOrElse {
            return RootHydration(root.id, emptyMap(), complete = false)
        }
        if (!shouldContinue()) return RootHydration(root.id, emptyMap(), complete = false)
        children[parent.id] = direct
        direct.forEach(queue::addLast)
    }
    return RootHydration(root.id, children, complete = true)
}

/** Cached foreground loader: complete roots are skipped until invalidated. */
internal class ForegroundSessionTreeHydrator(
    private val repository: OpenCodeRepository,
    private val store: SharedStateStore,
    private val scope: CoroutineScope,
) {
    private val inFlight = HashSet<String>()

    fun request(rootIds: Set<String>) {
        val snapshot = store.sessionListFlow.value
        val byId = allSessionsById(snapshot.sessions, snapshot.directorySessions, snapshot.childSessions)
        val roots = synchronized(inFlight) {
            rootIds.asSequence()
                .filter { it !in snapshot.completeRootIds && inFlight.add(it) }
                .mapNotNull(byId::get)
                .filter { it.parentId == null }
                .toList()
        }
        if (roots.isEmpty()) return
        val hostId = store.hostFlow.value.currentHostProfileId
        scope.launch {
            try {
                val result = loadCompleteSessionTrees(repository, roots)
                val statusBefore = store.sessionListFlow.value.sessionStatuses
                val statusSnapshot = repository.getSessionStatus().getOrElse { return@launch }
                store.mutateSessionList { current ->
                    val currentById = allSessionsById(current.sessions, current.directorySessions, current.childSessions)
                    val stillSameIdentity = store.hostFlow.value.currentHostProfileId == hostId
                    val validRoots = result.completeRootIds.filterTo(mutableSetOf()) { rootId ->
                        stillSameIdentity && currentById[rootId]?.directory == byId[rootId]?.directory
                    }
                    if (validRoots.isEmpty()) return@mutateSessionList current
                    val validParents = result.childrenByParent.filterKeys { parentId ->
                        validRoots.any { rootId -> parentId in treeIds(rootId, currentById + result.childrenByParent.values.flatten().associateBy { it.id }) }
                    }
                    val nextChildren = current.childSessions + validParents
                    val authoritativeIds = allSessionsById(
                        current.sessions,
                        current.directorySessions,
                        nextChildren,
                    ).keys
                    val normalizedStatuses = normalizeAuthoritativeStatusSnapshot(statusSnapshot, authoritativeIds)
                    current.copy(
                        childSessions = nextChildren,
                        completeRootIds = current.completeRootIds + validRoots,
                        sessionStatuses = mergeStatusSnapshot(statusBefore, current.sessionStatuses, normalizedStatuses),
                    )
                }
            } finally {
                synchronized(inFlight) { roots.forEach { inFlight.remove(it.id) } }
            }
        }
    }
}
