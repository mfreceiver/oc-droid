package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.AppAction
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
        // §glmer-minor: inFlight.add must happen AFTER the byId / parentId
        // filters — otherwise an id that is filtered out (unknown / non-root)
        // but already added to inFlight is stuck forever if roots ends up
        // empty (the early-return at `if (roots.isEmpty()) return` would skip
        // the finally cleanup). Filtering inFlight last guarantees only
        // actually-launched roots are tracked.
        val roots = synchronized(inFlight) {
            rootIds.asSequence()
                .filter { it !in snapshot.completeRootIds }
                .mapNotNull(byId::get)
                .filter { it.parentId == null }
                .filter { inFlight.add(it.id) }
                .toList()
        }
        if (roots.isEmpty()) return
        val hostId = store.hostFlow.value.currentHostProfileId
        // §gpter-blocker: capture the completeness epoch BEFORE hydration
        // starts. If an invalidation (SSE session.created/updated or a REST
        // structural replace) bumps the epoch mid-flight, the commit below
        // drops the result (fail-closed) so a stale snapshot can never
        // re-certify a root whose tree was invalidated.
        val epochAtStart = snapshot.completenessEpoch
        scope.launch {
            try {
                val result = loadCompleteSessionTrees(repository, roots)
                val statusBefore = store.sessionListFlow.value.sessionStatuses
                val statusSnapshot = repository.getSessionStatus().getOrNull()
                // T1c: call-site computes validated deltas + epochAtStart;
                // SessionTreeHydrated reduce owns the epoch-guarded 3-field commit.
                // Re-read latest snapshot for validation (same as prior mutate lambda).
                val current = store.sessionListFlow.value
                // §gpter-blocker: the tree was invalidated mid-flight —
                // drop the stale result. The roots stay incomplete so the
                // next tick re-hydrates against the fresh tree.
                if (current.completenessEpoch != epochAtStart) {
                    // Stale — SessionTreeHydrated would also no-op; skip dispatch.
                } else {
                    val currentById = allSessionsById(current.sessions, current.directorySessions, current.childSessions)
                    val stillSameIdentity = store.hostFlow.value.currentHostProfileId == hostId
                    val validRoots = result.completeRootIds.filterTo(mutableSetOf()) { rootId ->
                        stillSameIdentity && currentById[rootId]?.directory == byId[rootId]?.directory
                    }
                    if (validRoots.isNotEmpty()) {
                        val validParents = result.childrenByParent.filterKeys { parentId ->
                            validRoots.any { rootId ->
                                parentId in treeIds(
                                    rootId,
                                    currentById + result.childrenByParent.values.flatten().associateBy { it.id },
                                )
                            }
                        }
                        val nextChildren = current.childSessions + validParents
                        val nextStatuses = if (statusSnapshot != null) {
                            val authoritativeIds = allSessionsById(
                                current.sessions,
                                current.directorySessions,
                                nextChildren,
                            ).keys
                            val normalizedStatuses = normalizeAuthoritativeStatusSnapshot(statusSnapshot, authoritativeIds)
                            mergeStatusSnapshot(statusBefore, current.sessionStatuses, normalizedStatuses)
                        } else {
                            current.sessionStatuses
                        }
                        store.dispatch(
                            AppAction.SessionTreeHydrated(
                                epochAtStart = epochAtStart,
                                childSessionsDelta = validParents,
                                completeRootIdsDelta = validRoots,
                                sessionStatuses = nextStatuses,
                            )
                        )
                    }
                }
            } finally {
                synchronized(inFlight) { roots.forEach { inFlight.remove(it.id) } }
            }
        }
    }
}
