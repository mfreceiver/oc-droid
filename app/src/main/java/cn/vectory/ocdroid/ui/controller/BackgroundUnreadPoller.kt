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
        // §gpter-residual: capture the completeness epoch at request start. An
        // SSE session.created/updated bumps the epoch without touching host /
        // generation / workdir, so those guards cannot detect that the fetched
        // sessions/children are now stale relative to the store. The epoch is
        // therefore re-checked TOCTOU-free inside the commit CAS (not in
        // identityValid(), which is also invoked AFTER this poll's own commit
        // has legitimately bumped the epoch).
        val startEpoch = store.sessionListFlow.value.completenessEpoch
        fun identityValid(): Boolean = isBackground() &&
            lifecycleGeneration() == startGeneration &&
            store.hostFlow.value.currentHostProfileId == startHostId &&
            settingsManager.currentWorkdir == startWorkdir
        if (!identityValid()) return emptyList()
        val sessions = repository.getSessions(MainViewModelTimings.sessionFullLoadLimit)
            .getOrElse { return emptyList() }
        if (!identityValid()) return emptyList()
        val roots = sessions.filter { it.parentId == null }
        val hydration = loadCompleteSessionTrees(repository, roots, shouldContinue = ::identityValid)
        if (!identityValid()) return emptyList()
        val statuses = repository.getSessionStatus().getOrElse { return emptyList() }
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
        var committedUnread: cn.vectory.ocdroid.ui.UnreadState? = null
        var committedSessionsById: Map<String, cn.vectory.ocdroid.data.model.Session> = emptyMap()
        store.mutateState { snapshot ->
            if (!identityValid() || snapshot.host.currentHostProfileId != startHostId ||
                snapshot.sessionList.completenessEpoch != startEpoch
            ) return@mutateState snapshot
            // Bump the epoch alongside the authoritative completeness snapshot:
            // any foreground hydration captured an earlier epoch is dropped at
            // its commit (fail-closed) instead of re-certifying roots against a
            // stale session map and overwriting this background result.
            val nextSessionList = snapshot.sessionList.copy(
                sessions = sessions,
                childSessions = children,
                completeRootIds = hydration.completeRootIds,
                sessionStatuses = normalizedStatuses,
                completenessEpoch = snapshot.sessionList.completenessEpoch + 1L,
            )
            val sessionsById = allSessionsById(sessions, nextSessionList.directorySessions, children)
            val archivedTreeIds = roots.filter { it.isArchived }
                .flatMap { treeIds(it.id, sessionsById) }
                .toSet()
            val provisional = snapshot.copy(
                sessionList = nextSessionList,
                unread = snapshot.unread.removeSessions(archivedTreeIds),
            )
            val (nextUnread, _) = provisional.evaluateAndApplyUnread(now)
            committedUnread = nextUnread
            committedSessionsById = sessionsById
            provisional.copy(unread = nextUnread)
        }
        val unread = committedUnread ?: return emptyList()
        if (!identityValid()) return emptyList()
        val serverId = store.hostFlow.value.currentHostProfileId ?: "default"
        val workdir = settingsManager.currentWorkdir
        return unread.idleSince
            .filterKeys { it in unread.unreadSessions }
            .map { (rootId, idleSince) ->
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
