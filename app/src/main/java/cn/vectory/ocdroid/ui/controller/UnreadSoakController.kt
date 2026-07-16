package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.di.AppLifecycleMonitor
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnreadSoakController @Inject constructor(
    private val appLifecycleMonitor: AppLifecycleMonitor,
    private val scope: CoroutineScope,
    private val store: SharedStateStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val autoStart: Boolean = true,
    private val requestTreeHydration: (Set<String>) -> Unit = {},
    private val requestStatusRefresh: (onComplete: (Boolean) -> Unit) -> Boolean = { false },
) {
    private enum class RefreshState { WAITING, READY }
    private data class PendingRefresh(
        val requestId: Long,
        val requestedAtMs: Long,
        val rootsAtRequest: Set<String>,
        var state: RefreshState,
        val hostProfileId: String?,
    )

    @Volatile private var sweepJob: Job? = null
    private var pendingRefresh: PendingRefresh? = null
    private var nextRequestId = 0L
    private var retryNotBeforeMs = 0L
    private var unknownRefreshNotBeforeMs = 0L
    private var nextActiveRefreshAtMs = 0L

    init {
        if (autoStart) appLifecycleMonitor.isInForeground
            .onEach { if (it) startSweep() else stopSweep() }
            .launchIn(scope)
    }

    private fun startSweep() {
        if (sweepJob?.isActive == true) return
        sweepJob = scope.launch {
            while (isActive) {
                runCatching { tick() }.onFailure { DebugLog.w("UnreadSoak", "sweep tick failed: ${it.message}") }
                delay(SWEEP_INTERVAL_MS)
            }
        }
    }

    private fun stopSweep() {
        sweepJob?.cancel()
        sweepJob = null
        pendingRefresh = null
    }

    internal fun tick() {
        val now = clock()
        val hostId = store.hostFlow.value.currentHostProfileId
        expireOrInvalidatePending(now, hostId)
        val sl = store.sessionListFlow.value
        val incompleteIdleRoots = (sl.sessions + sl.directorySessions.values.flatten())
            .asSequence().filter { it.parentId == null && !it.isArchived }
            .filter { sl.sessionStatuses[it.id]?.isIdle == true }
            .map { it.id }.filter { it !in sl.completeRootIds }.toSet()
        if (incompleteIdleRoots.isNotEmpty()) requestTreeHydration(incompleteIdleRoots)

        val unknown = sl.completeRootIds.any { rootId ->
            subtreeIds(rootId, sl.sessions, sl.directorySessions, sl.childSessions)
                .any { it !in sl.sessionStatuses }
        }
        val readyRoots = pendingRefresh?.takeIf {
            it.state == RefreshState.READY && it.hostProfileId == hostId
        }?.rootsAtRequest ?: emptySet()
        val applied = store.evaluateAndApplyUnreadForSweep(
            now,
            readyRoots.isNotEmpty(),
            readyRoots,
        )
        val currentCandidates = applied.result.rootsToMarkUnread

        val gate = pendingRefresh
        if (gate?.state == RefreshState.READY && gate.hostProfileId == hostId) {
            pendingRefresh = null
            maybeStartRefresh(currentCandidates - gate.rootsAtRequest, now, hostId)
        } else if (gate?.state == RefreshState.WAITING &&
            gate.rootsAtRequest.intersect(currentCandidates).isEmpty() && gate.rootsAtRequest.isNotEmpty()
        ) {
            invalidatePending()
            maybeStartRefresh(currentCandidates, now, hostId)
        } else if (gate == null) {
            maybeStartRefresh(currentCandidates, now, hostId)
        }
        // /api/session/active has no SSE. Poll it through the same guarded
        // status-refresh lane often enough to cover the 10s unread soak.
        if (pendingRefresh == null && now >= nextActiveRefreshAtMs) {
            nextActiveRefreshAtMs = now + ACTIVE_REFRESH_INTERVAL_MS
            maybeStartRefresh(currentCandidates, now, hostId, allowEmpty = true)
        }
        if (unknown && pendingRefresh == null && now >= unknownRefreshNotBeforeMs) {
            unknownRefreshNotBeforeMs = now + UNKNOWN_REFRESH_COOLDOWN_MS
            maybeStartRefresh(currentCandidates, now, hostId, allowEmpty = true)
        }
    }

    private fun expireOrInvalidatePending(now: Long, hostId: String?) {
        val pending = pendingRefresh ?: return
        if (pending.hostProfileId != hostId ||
            (pending.state == RefreshState.WAITING && now - pending.requestedAtMs >= STATUS_REFRESH_TIMEOUT_MS)
        ) {
            invalidatePending()
            retryNotBeforeMs = now + RETRY_BACKOFF_MS
        }
    }

    private fun invalidatePending() { pendingRefresh = null }

    private fun maybeStartRefresh(
        roots: Set<String>,
        now: Long,
        hostId: String?,
        allowEmpty: Boolean = false,
    ) {
        if ((!allowEmpty && roots.isEmpty()) || pendingRefresh != null || now < retryNotBeforeMs) return
        val id = ++nextRequestId
        val request = PendingRefresh(id, now, roots.toSet(), RefreshState.WAITING, hostId)
        pendingRefresh = request
        val accepted = requestStatusRefresh { success ->
            val current = pendingRefresh
            if (current?.requestId == id && current.hostProfileId == store.hostFlow.value.currentHostProfileId) {
                if (success) current.state = RefreshState.READY
                else { invalidatePending(); retryNotBeforeMs = clock() + RETRY_BACKOFF_MS }
            }
        }
        if (!accepted) {
            invalidatePending()
            retryNotBeforeMs = now + RETRY_BACKOFF_MS
        } else {
            scope.launch {
                delay(STATUS_REFRESH_TIMEOUT_MS)
                val current = pendingRefresh
                if (current?.requestId == id && current.state == RefreshState.WAITING) {
                    invalidatePending()
                    retryNotBeforeMs = clock() + RETRY_BACKOFF_MS
                }
            }
        }
    }

    internal fun evaluateAndApply(now: Long): UnreadSoakResult = evaluateAndApplyUnread(now, false, emptySet()).result

    private fun evaluateAndApplyUnread(now: Long, commitMarks: Boolean, allowedMarkRoots: Set<String>): ApplyResult =
        store.evaluateAndApplyUnreadForSweep(now, commitMarks, allowedMarkRoots)

    companion object {
        const val SWEEP_INTERVAL_MS = 2_000L
        const val STATUS_REFRESH_TIMEOUT_MS = 15_000L
        const val RETRY_BACKOFF_MS = 30_000L
        const val UNKNOWN_REFRESH_COOLDOWN_MS = 30_000L
        const val ACTIVE_REFRESH_INTERVAL_MS = 4_000L
    }
}

private data class ApplyResult(val result: UnreadSoakResult, val committedRoots: Set<String>)

private fun SharedStateStore.evaluateAndApplyUnreadForSweep(
    now: Long,
    commitMarks: Boolean,
    allowedMarkRoots: Set<String>,
): ApplyResult {
    lateinit var applied: ApplyResult
    mutateUnreadFromState { snapshot ->
        val (nextUnread, result) = snapshot.evaluateAndApplyUnread(now)
        val marksToCommit = if (commitMarks) result.rootsToMarkUnread.intersect(allowedMarkRoots) else emptySet()
        val viewedRootsToApply = (result.rootsToStampViewed - result.rootsToMarkUnread) + marksToCommit
        var next = nextUnread
        result.rootsToMarkUnread.filter { it in marksToCommit }.forEach { root ->
            next = next.applyMarkSessionUnread(root, snapshot.chat.currentSessionId).first
        }
        if (viewedRootsToApply.isNotEmpty()) {
            next = next.copy(lastViewedTime = next.lastViewedTime + viewedRootsToApply.associateWith { now })
        }
        applied = ApplyResult(result.copy(rootsToStampViewed = viewedRootsToApply), marksToCommit)
        next
    }
    return applied
}

/** Compatibility path for the background poller: it has no status gate. */
internal fun SharedStateStore.evaluateAndApplyUnread(now: Long): UnreadSoakResult {
    lateinit var committed: UnreadSoakResult
    mutateUnreadFromState { snapshot ->
        val (next, result) = snapshot.evaluateAndApplyUnread(now)
        committed = result
        var updated = next
        result.rootsToMarkUnread.forEach { root ->
            updated = updated.applyMarkSessionUnread(root, snapshot.chat.currentSessionId).first
        }
        updated.copy(lastViewedTime = updated.lastViewedTime + result.rootsToStampViewed.associateWith { now })
    }
    return committed
}

internal fun cn.vectory.ocdroid.ui.StoreState.evaluateAndApplyUnread(
    now: Long,
): Pair<cn.vectory.ocdroid.ui.UnreadState, UnreadSoakResult> {
    val sl = sessionList
    val result = evaluateUnread(sl.sessions, sl.sessionStatuses, sl.activeSessionIds,
        sl.childSessions, sl.directorySessions,
        chat.currentSessionId, unread.lastViewedTime, unread.idleSince, now, completeRootIds = sl.completeRootIds)
    var next = unread.copy(idleSince = result.newIdleSince)
    val sessionMap = allSessionsById(sl.sessions, sl.directorySessions, sl.childSessions)
    val currentRootId = chat.currentSessionId?.let { rootIdOf(it, sessionMap) }
    if (currentRootId != null && (currentRootId in next.unreadSessions || currentRootId in next.idleSince)) {
        next = next.copy(unreadSessions = next.unreadSessions - currentRootId, idleSince = next.idleSince - currentRootId,
            lastViewedTime = next.lastViewedTime + (currentRootId to now))
    }
    return next to result
}
