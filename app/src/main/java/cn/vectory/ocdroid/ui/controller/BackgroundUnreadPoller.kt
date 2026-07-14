package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.ui.MainViewModelTimings
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.util.SettingsManager
import javax.inject.Inject
import javax.inject.Singleton

data class IdleUnreadAlert(
    val rootId: String,
    val title: String,
    val idleSince: Long,
    val key: String,
)

internal fun idleNotificationKey(
    serverId: String,
    workdir: String?,
    rootId: String,
    idleSince: Long,
): String = "idle:$serverId:${workdir.orEmpty()}:$rootId:$idleSince"

/**
 * Fetches an authoritative background snapshot, writes it to the shared store,
 * then invokes the same atomic unread evaluator used by the foreground sweep.
 * Any incomplete tree/status fetch fails closed, because unknown descendants
 * must never be interpreted as idle.
 */
@Singleton
class BackgroundUnreadPoller internal constructor(
    private val repository: OpenCodeRepository,
    private val settingsManager: SettingsManager,
    private val store: SharedStateStore,
    private val clock: () -> Long,
) {
    @Inject
    constructor(
        repository: OpenCodeRepository,
        settingsManager: SettingsManager,
        store: SharedStateStore,
    ) : this(repository, settingsManager, store, { System.currentTimeMillis() })

    suspend fun poll(): List<IdleUnreadAlert> {
        val sessions = repository.getSessions(MainViewModelTimings.sessionFullLoadLimit)
            .getOrElse { return emptyList() }
        val statuses = repository.getSessionStatus().getOrElse { return emptyList() }
        val roots = sessions.filter { it.parentId == null }
        val children = LinkedHashMap<String, List<cn.vectory.ocdroid.data.model.Session>>()
        val queue = ArrayDeque(roots)
        val fetchedParents = HashSet<String>()
        while (queue.isNotEmpty()) {
            val parent = queue.removeFirst()
            if (!fetchedParents.add(parent.id)) continue
            val directChildren = repository.getChildren(parent.id).getOrElse { return emptyList() }
            children[parent.id] = directChildren
            directChildren.forEach(queue::addLast)
        }
        // OpenCode's authoritative status endpoint omits idle entries. A
        // successful snapshot therefore proves every fetched tree node absent
        // from the response is idle; normalize that protocol encoding to an
        // explicit status. Fetch failures returned above, so they remain
        // unknown/fail-closed rather than being mistaken for idle.
        val normalizedStatuses = statuses.toMutableMap()
        (sessions.asSequence() + children.values.asSequence().flatten())
            .forEach { normalizedStatuses.putIfAbsent(it.id, SessionStatus(type = "idle")) }

        store.mutateSessionList {
            it.copy(
                sessions = sessions,
                childSessions = children,
                sessionStatuses = normalizedStatuses,
            )
        }

        val sessionsById = allSessionsById(sessions, store.sessionListFlow.value.directorySessions, children)
        val archivedTreeIds = roots.filter { it.isArchived }
            .flatMap { treeIds(it.id, sessionsById) }
            .toSet()
        if (archivedTreeIds.isNotEmpty()) {
            store.mutateUnread { it.removeSessions(archivedTreeIds) }
        }

        val result = store.evaluateAndApplyUnread(clock())
        val serverId = store.hostFlow.value.currentHostProfileId ?: "default"
        val workdir = settingsManager.currentWorkdir
        return result.markedIdleSince.map { (rootId, idleSince) ->
            val root = sessionsById[rootId]
            IdleUnreadAlert(
                rootId = rootId,
                title = root?.title?.takeIf { it.isNotBlank() } ?: rootId,
                idleSince = idleSince,
                key = idleNotificationKey(serverId, workdir, rootId, idleSince),
            )
        }
    }
}
