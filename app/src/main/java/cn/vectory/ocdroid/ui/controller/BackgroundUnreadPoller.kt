package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.MainViewModelTimings
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.di.AppLifecycleMonitor
import cn.vectory.ocdroid.util.runSuspendCatching
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

/**
 * Bug-1 (notification regeneration): STABLE dedup key for an idle unread root.
 *
 * The key intentionally DROPS the volatile `idleSince` timestamp — that value
 * is re-stamped by the unread evaluator on every fresh idle transition and is
 * NOT preserved across process death, so a key that embedded it could never
 * match a persisted dedup entry after restart. With a stable
 * `(serverId, workdir, rootId)` triple the persisted set survives process
 * death and correctly suppresses re-notification for the same logical root.
 *
 * `IdleUnreadAlert.idleSince` is retained on the data class (it carries UI
 * timing context); only the KEY is stable.
 */
internal fun idleNotificationKey(
    serverId: String,
    workdir: String?,
    rootId: String,
): String = "idle:$serverId:${workdir.orEmpty()}:$rootId"

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
        // §P0-A: capture the status projection at request start so the authority
        // reducer's ApplySnapshot can apply the REST in-flight (SSE-wins)
        // protection inside the commit CAS (an SSE status that landed during the
        // REST round-trip must not be clobbered by this background snapshot).
        val localBefore = store.sessionListFlow.value.sessionStatuses
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
        // T-R1 (slimapi R1): slim mode routes status through per-workdir slim
        // endpoint (getSlimapiSessionsStatus) instead of legacy getSessionStatus;
        // active-session ids are digest-relay-owned in slim mode (skip the
        // legacy getActiveSessionIds, preserve store snapshot → null fallback).
        val statuses = if (repository.usesSlimStatusFanOut) {
            loadSlimSessionStatus(sessions, hydration.childrenByParent)
                .getOrElse { return UnreadPollResult.Aborted }
        } else {
            repository.getSessionStatus().getOrElse { return UnreadPollResult.Aborted }
        }
        if (!identityValid()) return UnreadPollResult.Aborted
        val activeIds = if (repository.usesSlimStatusFanOut) {
            // Slim: activity is digest-relay-owned; skip the legacy endpoint
            // and fall back to the existing store snapshot (null → fail-closed
            // in the commit below matches intersected existing activeSessionIds).
            null
        } else {
            repository.getActiveSessionIds().getOrNull()
        }
        if (!identityValid()) return UnreadPollResult.Aborted
        val children = hydration.childrenByParent
        // OpenCode's authoritative status endpoint omits idle entries. A
        // successful snapshot therefore proves every fetched tree node absent
        // from the response is idle; the authority reducer's ApplySnapshot
        // normalizes that protocol encoding (idle-fill [authoritativeNodeIds]).
        // IDs outside the authoritative tree remain absent/unknown → fail-closed.
        val authoritativeNodeIds = (sessions.asSequence() + children.values.asSequence().flatten())
            .mapTo(mutableSetOf()) { it.id }

        if (!identityValid()) return UnreadPollResult.Aborted
        val now = clock()
        var committedUnread: cn.vectory.ocdroid.ui.UnreadState? = null
        var committedSessionsById: Map<String, cn.vectory.ocdroid.data.model.Session> = emptyMap()
        store.mutateState { snapshot ->
            if (!identityValid() || snapshot.host.currentHostProfileId != startHostId ||
                snapshot.sessionList.completenessEpoch != startEpoch
            ) return@mutateState snapshot
            // §P0-A (§4a.1): status flows through the PURE authority reducer
            // inside this same CAS — authority.bySid + the sessionStatuses
            // projection are updated atomically with the sessions/child/tree/
            // completenessEpoch/unread overlay below. reduceAuthority is pure →
            // safe + idempotent under CAS retry.
            val authoritativeSessions = allSessionsById(
                sessions,
                snapshot.sessionList.directorySessions,
                children,
            )
            val op = cn.vectory.ocdroid.ui.buildAuthorityApplySnapshot(
                snapshot = statuses,
                authoritativeSessions = authoritativeSessions,
                authoritativeNodeIds = authoritativeNodeIds,
                coveredWorkdirs = authoritativeSessions.values.asSequence()
                    .map { it.directory }.filter { it.isNotBlank() }.toSet(),
                partialFailureWorkdirs = emptySet(),
                unmappedActiveIds = emptySet(),
                lastSuccessTimeMs = now,
                scopeKey = store.authorityScope(),
                requestToken = cn.vectory.ocdroid.data.state.RequestToken(
                    hostProfileId = startHostId,
                    epoch = startEpoch,
                    requestStartMs = now,
                    identityEpoch = store.stateFlow.value.identityEpoch,
                ),
                localBefore = localBefore,
            )
            val withStatus = cn.vectory.ocdroid.ui.reduceAuthority(snapshot, op)
            // Bump the epoch alongside the authoritative completeness snapshot:
            // any foreground hydration captured an earlier epoch is dropped at
            // its commit (fail-closed) instead of re-certifying roots against a
            // stale session map and overwriting this background result.
            val nextSessionList = withStatus.sessionList.copy(
                sessions = sessions,
                childSessions = children,
                completeRootIds = hydration.completeRootIds,
                activeSessionIds = activeIds
                    ?.intersect(authoritativeNodeIds)
                    ?: snapshot.sessionList.activeSessionIds.intersect(authoritativeNodeIds),
                completenessEpoch = snapshot.sessionList.completenessEpoch + 1L,
            )
            val sessionsById = allSessionsById(sessions, nextSessionList.directorySessions, children)
            val archivedTreeIds = roots.filter { it.isArchived }
                .flatMap { treeIds(it.id, sessionsById) }
                .toSet()
            val provisional = snapshot.copy(
                authority = withStatus.authority,
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
                key = idleNotificationKey(serverId, workdir, rootId),
            )
            }
        // Genuine authoritative snapshot (which may be a real empty list).
        return UnreadPollResult.Authoritative(alerts)
    }

    /**
     * T-R1 (slimapi R1): slim-mode per-workdir status fetch. Replaces the
     * legacy [OpenCodeRepository.getSessionStatus] bulk call — derives the
     * distinct workdirs from the already-loaded sessions+children tree and
     * issues one concurrent [OpenCodeRepository.getSlimapiSessionsStatus]
     * per directory. Fail-closed: any per-directory failure propagates as
     * [Result.failure], matching the legacy fail-closed semantics.
     */
    private suspend fun loadSlimSessionStatus(
        sessions: List<Session>,
        childrenByParent: Map<String, List<Session>>,
    ): Result<Map<String, SessionStatus>> = runSuspendCatching {
        val directories = (sessions.asSequence() + childrenByParent.values.asSequence().flatten())
            .mapNotNull { it.directory.takeIf { d -> d.isNotBlank() } }
            .toSet()
        if (directories.isEmpty()) return@runSuspendCatching emptyMap()
        coroutineScope {
            val results = directories.map { dir ->
                async { repository.getSlimapiSessionsStatus(dir) }
            }.awaitAll()
            buildMap { results.forEach { putAll(it.getOrThrow()) } }
        }
    }
}
