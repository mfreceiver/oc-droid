package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.MainViewModelTimings
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.di.AppLifecycleMonitor
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
    private val isBackground: () -> Boolean = { true },
    private val lifecycleGeneration: () -> Long = { 0L },
) {
    @Inject
    constructor(
        repository: OpenCodeRepository,
        settingsManager: SettingsManager,
        store: SharedStateStore,
        appLifecycleMonitor: AppLifecycleMonitor,
    ) : this(
        repository,
        settingsManager,
        store,
        { System.currentTimeMillis() },
        { !appLifecycleMonitor.isInForeground.value },
        appLifecycleMonitor::currentLifecycleGeneration,
    )

    suspend fun poll(): List<IdleUnreadAlert> {
        val startGeneration = lifecycleGeneration()
        val startHostId = store.hostFlow.value.currentHostProfileId
        val startWorkdir = settingsManager.currentWorkdir
        fun identityValid(): Boolean = isBackground() &&
            lifecycleGeneration() == startGeneration &&
            store.hostFlow.value.currentHostProfileId == startHostId &&
            settingsManager.currentWorkdir == startWorkdir
        if (!identityValid()) return emptyList()
        val sessions = repository.getSessions(MainViewModelTimings.sessionFullLoadLimit)
            .getOrElse { return emptyList() }
        if (!identityValid()) return emptyList()
        val statuses = repository.getSessionStatus().getOrElse { return emptyList() }
        if (!identityValid()) return emptyList()
        val roots = sessions.filter { it.parentId == null }
        val hydration = loadCompleteSessionTrees(repository, roots, shouldContinue = ::identityValid)
        if (!identityValid()) return emptyList()
        val children = hydration.childrenByParent
        // OpenCode's authoritative status endpoint omits idle entries. A
        // successful snapshot therefore proves every fetched tree node absent
        // from the response is idle; normalize that protocol encoding to an
        // explicit status. Fetch failures returned above, so they remain
        // unknown/fail-closed rather than being mistaken for idle.
        val authoritativeNodeIds = (sessions.asSequence() + children.values.asSequence().flatten())
            .mapTo(mutableSetOf()) { it.id }
        val normalizedStatuses = normalizeAuthoritativeStatusSnapshot(statuses, authoritativeNodeIds)

        if (!identityValid()) return emptyList()
        val now = clock()
        var committedResult: UnreadSoakResult? = null
        var committedSessionsById: Map<String, cn.vectory.ocdroid.data.model.Session> = emptyMap()
        store.mutateState { snapshot ->
            if (!identityValid() || snapshot.host.currentHostProfileId != startHostId) return@mutateState snapshot
            val nextSessionList = snapshot.sessionList.copy(
                sessions = sessions,
                childSessions = children,
                completeRootIds = hydration.completeRootIds,
                sessionStatuses = normalizedStatuses,
            )
            val sessionsById = allSessionsById(sessions, nextSessionList.directorySessions, children)
            val archivedTreeIds = roots.filter { it.isArchived }
                .flatMap { treeIds(it.id, sessionsById) }
                .toSet()
            val provisional = snapshot.copy(
                sessionList = nextSessionList,
                unread = snapshot.unread.removeSessions(archivedTreeIds),
            )
            val (nextUnread, result) = provisional.evaluateAndApplyUnread(now)
            committedResult = result
            committedSessionsById = sessionsById
            provisional.copy(unread = nextUnread)
        }
        val result = committedResult ?: return emptyList()
        if (!identityValid()) return emptyList()
        val serverId = store.hostFlow.value.currentHostProfileId ?: "default"
        val workdir = settingsManager.currentWorkdir
        return result.markedIdleSince.map { (rootId, idleSince) ->
            val root = committedSessionsById[rootId]
            IdleUnreadAlert(
                rootId = rootId,
                title = root?.title?.takeIf { it.isNotBlank() } ?: rootId,
                idleSince = idleSince,
                key = idleNotificationKey(serverId, workdir, rootId, idleSince),
            )
        }
    }
}
