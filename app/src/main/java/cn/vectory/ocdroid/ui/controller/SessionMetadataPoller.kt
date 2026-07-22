package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.di.AppLifecycleMonitor
import cn.vectory.ocdroid.di.UiApplicationScope
import cn.vectory.ocdroid.ui.MainViewModelTimings
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.mergeRefreshedSessionsPreservingLocalActivity
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Slim fallback: polls session metadata (including titles) every 30s when
 * foregrounded + connected, to compensate for the slim protocol's lack of
 * title-bearing session.updated events. Merges via
 * [mergeRefreshedSessionsPreservingLocalActivity] (fresher-wins) so this
 * NEVER conflicts with SSE-driven session list updates.
 */
@Singleton
class SessionMetadataPoller @Inject constructor(
    private val appLifecycleMonitor: AppLifecycleMonitor,
    @UiApplicationScope private val scope: CoroutineScope,
    private val repository: OpenCodeRepository,
    private val store: SharedStateStore,
) {
    private var pollJob: Job? = null
    @Volatile
    private var pollGeneration = 0L

    init {
        combine(
            appLifecycleMonitor.isInForeground,
            store.connectionFlow.map { it.isConnected },
        ) { foreground, connected -> foreground && connected }
            .distinctUntilChanged()
            .onEach { shouldPoll ->
                pollGeneration++
                if (shouldPoll) startPolling() else stopPolling()
            }
            .launchIn(scope)
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                poll()
                delay(SESSION_METADATA_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun poll() {
        if (!appLifecycleMonitor.isInForeground.value) return
        if (!store.connectionFlow.value.isConnected) return

        val generation = pollGeneration   // capture the generation before network call
        val refreshed = repository.getSessions(MainViewModelTimings.sessionFullLoadLimit)
            .getOrElse {
                DebugLog.w(TAG, "getSessions failed: ${it.message}")
                return
            }

        // Long RTT guard: re-check foreground + connected after network call
        if (!appLifecycleMonitor.isInForeground.value) return
        if (!store.connectionFlow.value.isConnected) return

        store.mutateSessionList { current ->
            // Atomic backstop: if gate changed during the poll's lifetime, skip commit
            if (generation != pollGeneration) current
            else current.copy(
                sessions = mergeRefreshedSessionsPreservingLocalActivity(
                    refreshed = refreshed,
                    local = current.sessions,
                    currentSessionId = store.chatFlow.value.currentSessionId,
                    openSessionIds = current.openSessionIds.toSet(),
                    pendingCreateIds = current.pendingCreateIds,
                )
            )
        }
    }

    private companion object {
        private const val SESSION_METADATA_POLL_INTERVAL_MS = 30_000L
        private const val TAG = "SessionMetadataPoller"
    }
}
