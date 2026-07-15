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

/**
 * T5-round-5 I1-A: the [BackgroundUnreadPoller.poll] result contract.
 *
 * The prior contract returned `List<IdleUnreadAlert>` and used `emptyList()`
 * for BOTH "authoritative snapshot that happens to contain no alerts" AND
 * "poll aborted before producing any snapshot" (identity invalidation /
 * repository failure / rejected aggregate commit / unregistered poller).
 * ALM's `runSuspendCatching ... onSuccess` therefore treated every abort as
 * a successful authoritative empty snapshot → `active = emptySet()` → the
 * fenced prune removed live `Posted` candidates → the next genuine poll
 * re-claimed → duplicate notification. The caller cannot distinguish
 * "authoritative empty" from "abort, no snapshot" on a bare list.
 *
 * The sealed result restores the distinction: [Authoritative] drives ALM's
 * prune + publish path (including genuinely-empty snapshots); [Aborted]
 * MUST skip the prune entirely (leave dedup state intact) and skip publish.
 * Cancellation is NOT an abort — `poll()` does not catch
 * [kotlinx.coroutines.CancellationException], so structured cancellation
 * still propagates through `runSuspendCatching` (which rethrows it).
 */
sealed interface UnreadPollResult {
    /**
     * Authoritative snapshot committed to the store. May be empty (a real
     * snapshot that contained no idle alerts). Drives ALM's
     * `pruneStaleCandidates(candidates, alerts.keys)` + publish path.
     */
    data class Authoritative(val alerts: List<IdleUnreadAlert>) : UnreadPollResult

    /**
     * Poll aborted WITHOUT producing an authoritative snapshot (identity /
     * lifecycle / host / workdir invalidation, repository failure, rejected
     * aggregate commit, `completenessEpoch` moved mid-poll, or unregistered
     * poller). The caller MUST treat this as "no information" — skip the
     * prune (dedup state stays intact) and skip publish. A later
     * authoritative poll will reconcile.
     */
    object Aborted : UnreadPollResult
}

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

    suspend fun poll(): UnreadPollResult {
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
        // T5-round-5 I1-A: every non-exception abort now returns Aborted (was
        // emptyList()), so the caller can distinguish "no snapshot" from a
        // genuine authoritative empty. CancellationException is NOT caught
        // here — repository calls go through runSuspendCatching at the
        // repository boundary (cancellation rethrown), and `.getOrElse` on
        // the resulting Result therefore never sees a CancellationException.
        if (!identityValid()) return UnreadPollResult.Aborted
        val sessions = repository.getSessions(MainViewModelTimings.sessionFullLoadLimit)
            .getOrElse { return UnreadPollResult.Aborted }
        if (!identityValid()) return UnreadPollResult.Aborted
        val roots = sessions.filter { it.parentId == null }
        val hydration = loadCompleteSessionTrees(repository, roots, shouldContinue = ::identityValid)
        if (!identityValid()) return UnreadPollResult.Aborted
        val statuses = repository.getSessionStatus().getOrElse { return UnreadPollResult.Aborted }
        if (!identityValid()) return UnreadPollResult.Aborted
        val children = hydration.childrenByParent
        // OpenCode's authoritative status endpoint omits idle entries. A
        // successful snapshot therefore proves every fetched tree node absent
        // from the response is idle; normalize that protocol encoding to an
        // explicit status. Fetch failures returned above, so they remain
        // unknown/fail-closed rather than being mistaken for idle.
        val authoritativeNodeIds = (sessions.asSequence() + children.values.asSequence().flatten())
            .mapTo(mutableSetOf()) { it.id }
        val normalizedStatuses = normalizeAuthoritativeStatusSnapshot(statuses, authoritativeNodeIds)

        if (!identityValid()) return UnreadPollResult.Aborted
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
            val (evaluatedUnread, result) = provisional.evaluateAndApplyUnread(now)
            // Background polling already committed an authoritative REST status
            // snapshot in this same aggregate CAS, so it has no foreground
            // status gate. Apply the evaluator's marks directly before the
            // aggregate commit (the SharedStateStore compatibility helper cannot
            // be used here because we are already inside mutateState).
            var nextUnread = evaluatedUnread
            result.rootsToMarkUnread.forEach { rootId ->
                nextUnread = nextUnread
                    .applyMarkSessionUnread(rootId, snapshot.chat.currentSessionId)
                    .first
            }
            if (result.rootsToStampViewed.isNotEmpty()) {
                nextUnread = nextUnread.copy(
                    lastViewedTime = nextUnread.lastViewedTime +
                        result.rootsToStampViewed.associateWith { now }
                )
            }
            committedUnread = nextUnread
            committedSessionsById = sessionsById
            provisional.copy(unread = nextUnread)
        }
        // committedUnread == null ⇒ the CAS rejected the commit (epoch moved
        // mid-poll) ⇒ no authoritative snapshot was produced ⇒ Aborted.
        val unread = committedUnread ?: return UnreadPollResult.Aborted
        if (!identityValid()) return UnreadPollResult.Aborted
        val serverId = store.hostFlow.value.currentHostProfileId ?: "default"
        val workdir = settingsManager.currentWorkdir
        val alerts = unread.idleSince
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
        // Genuine authoritative snapshot (which may be a real empty list).
        return UnreadPollResult.Authoritative(alerts)
    }
}
