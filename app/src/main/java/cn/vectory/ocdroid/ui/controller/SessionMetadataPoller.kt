package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.di.AppLifecycleMonitor
import cn.vectory.ocdroid.di.UiApplicationScope
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.ui.ConnectionPhase
import cn.vectory.ocdroid.ui.MainViewModelTimings
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.mergeRefreshedSessionsPreservingLocalActivity
import cn.vectory.ocdroid.ui.preserveSessionsAddedDuringRequest
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §需求10 §2.2: polls session metadata (including titles) when foregrounded AND
 * either connected (SSE healthy — baseline 30s) OR SSE is effectively down
 * (fallback REST poll with exponential backoff 10s→20s→40s→60s).  Merges via
 * [mergeRefreshedSessionsPreservingLocalActivity] (fresher-wins) so this NEVER
 * conflicts with SSE-driven session list updates.
 *
 * Host-identity guard via [ConnectionIdentityStore.commitIfCurrent] (§2.3,
 * §design-contract §0.2): a host switch during the poll's network call atomically
 * prevents the stale snapshot from committing to the new host's session list.
 */
@Singleton
class SessionMetadataPoller @Inject constructor(
    private val appLifecycleMonitor: AppLifecycleMonitor,
    @UiApplicationScope private val scope: CoroutineScope,
    private val repository: OpenCodeRepository,
    private val store: SharedStateStore,
    private val identityStore: ConnectionIdentityStore,
) {
    private var pollJob: Job? = null
    @Volatile
    private var currentPollMode: PollMode? = null
    /** §需求10 C4: previous poll mode, used to detect mode transitions and restart
     *  the polling loop so the new delay applies promptly. */
    private var previousPollMode: PollMode? = null
    private var fallbackBackoffMs = FALLBACK_POLL_INITIAL_MS

    init {
        combine(
            appLifecycleMonitor.isInForeground,
            store.connectionFlow,
            store.sseConnectedFlow,
        ) { foreground, conn, sseConnected ->
            if (!foreground) null
            else {
                val sseEffectivelyDown =
                    (conn.connectionPhase is ConnectionPhase.Connected && !sseConnected) ||
                    conn.connectionPhase is ConnectionPhase.Disconnected
                when {
                    conn.isConnected && !sseEffectivelyDown -> PollMode.BASELINE
                    sseEffectivelyDown -> PollMode.FALLBACK
                    else -> null
                }
            }
        }
            .distinctUntilChanged()
            .onEach { mode ->
                val prevMode = previousPollMode
                previousPollMode = mode
                currentPollMode = mode
                // §需求10 C4: on ANY mode transition, restart the polling loop so
                // the new delay applies promptly (interrupts the in-progress delay).
                if (prevMode != mode) {
                    // Reset exponential backoff on transition INTO FALLBACK
                    // (fresh start for the backoff sequence) and INTO null
                    // (so a subsequent FALLBACK entry starts from initial).
                    if (mode == PollMode.FALLBACK || mode == null) {
                        fallbackBackoffMs = FALLBACK_POLL_INITIAL_MS
                    }
                    stopPolling()
                    if (mode != null) startPolling()
                }
            }
            .launchIn(scope)
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                poll()
                val delayMs = when (currentPollMode) {
                    PollMode.BASELINE -> SESSION_METADATA_POLL_INTERVAL_MS
                    PollMode.FALLBACK -> {
                        val d = fallbackBackoffMs
                        fallbackBackoffMs = (d * 2).coerceAtMost(FALLBACK_POLL_MAX_MS)
                        d
                    }
                    null -> return@launch
                }
                delay(delayMs)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun poll() {
        val mode = currentPollMode ?: return
        if (!appLifecycleMonitor.isInForeground.value) return

        // §需求10 C3 (round-4, oracle cancel-and-replace): the poller is AMBIENT
        // and carries no intent — it does NOT participate in the single-flight.
        // It READS the heavyweight-refresh flag to skip its own light title-patch
        // when a full refresh (ON_RESUME / ForegroundCatchUp / reconnect) is
        // running, avoiding one duplicate cheap GET. It never acquires/cancels.
        // Self-serialized by the single pollJob; commit independently guarded by
        // commitIfCurrent + fresher-wins merge. See SharedStateStore.sessionListLoadInFlight
        // KDoc for the authoritative concurrency-model invariant set.
        if (store.sessionListLoadInFlight) {
            DebugLog.d(TAG, "poll: list load in flight, skipping")
            return
        }

        // §2.3 (§design-contract §0.2): capture identity BEFORE the network call
        // so commitIfCurrent can atomically guard the commit against a host switch.
        val cap = identityStore.capture()

        // §需求10 C1: no bound identity — poll is a no-op. The poll loop
        // keeps running (mode transitions are still reactive) but commits
        // nothing until a profile is bound. Restart-required profile-switch
        // limitation is batch 5 scope.
        if (cap.identity == null) {
            DebugLog.d(TAG, "poll: no bound identity, skipping")
            return
        }

        // §2.2: in baseline mode, skip if connection dropped during the capture
        if (mode == PollMode.BASELINE && !store.connectionFlow.value.isConnected) return

        // §需求10 C2: capture the set of session IDs present at request-start
        // so SSE-created-during-request sessions can be preserved after the merge.
        val localIdsAtRequestStart = store.sessionListFlow.value.sessions.map { it.id }.toSet()

        val refreshed = repository.getSessions(MainViewModelTimings.sessionFullLoadLimit)
            .getOrElse {
                DebugLog.w(TAG, "getSessions failed: ${it.message}")
                return
            }

        // Long RTT guard: re-check foreground + mode after network call
        if (!appLifecycleMonitor.isInForeground.value) return
        if (currentPollMode == null) return
        if (mode == PollMode.BASELINE && !store.connectionFlow.value.isConnected) return

        // §2.3: authoritative host-identity guard — commitIfCurrent replaces
        // the old pollGeneration atomic backstop (§design-contract §2.3).
        // Runs the mutation atomically under identityStore's lock, mutually
        // exclusive with beginReconfigure().
        val committed = identityStore.commitIfCurrent(cap.identity, cap.epoch) {
            store.mutateSessionList { current ->
                // §title-sync-fix (rev-gpt reviewed): patch ONLY title for
                // existing entries in each directory bucket — do NOT run the
                // full merge. `refreshed` is a global cross-directory snapshot;
                // running mergeRefreshedSessionsPreservingLocalActivity against
                // each bucket would inject other directories' sessions and
                // remove entries absent from the top-N global list.
                val mergedSessions = mergeRefreshedSessionsPreservingLocalActivity(
                    refreshed = refreshed,
                    local = current.sessions,
                    currentSessionId = store.chatFlow.value.currentSessionId,
                    pendingCreateIds = current.pendingCreateIds,
                )
                // §需求10 C2: preserve sessions that were added by SSE during the
                // REST request window (absent from the stale REST response but
                // present in the now-current local list and NOT in the request-
                // start snapshot).
                val withPreserved = preserveSessionsAddedDuringRequest(
                    merged = mergedSessions,
                    local = current.sessions,
                    localIdsAtRequestStart = localIdsAtRequestStart,
                )
                val refreshedById = refreshed.associateBy { it.id }
                val mergedDirectorySessions = current.directorySessions.mapValues { (_, list) ->
                    list.map { existing ->
                        val remote = refreshedById[existing.id]
                        if (remote != null && existing.title == null && remote.title != null) {
                            existing.copy(title = remote.title)
                        } else {
                            existing
                        }
                    }
                }
                current.copy(
                    sessions = withPreserved,
                    directorySessions = mergedDirectorySessions,
                )
            }
        }
        if (!committed) {
            DebugLog.d(TAG, "poll: identity superseded (host switched), dropping stale snapshot")
        }
    }

    private enum class PollMode { BASELINE, FALLBACK }

    private companion object {
        private const val SESSION_METADATA_POLL_INTERVAL_MS = 30_000L
        private const val FALLBACK_POLL_INITIAL_MS = 10_000L
        private const val FALLBACK_POLL_MAX_MS = 60_000L
        private const val TAG = "SessionMetadataPoller"
    }
}
