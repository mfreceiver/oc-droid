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
import kotlinx.coroutines.flow.launchIn
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

    init {
        appLifecycleMonitor.isInForeground
            .onEach { foreground ->
                if (foreground && store.connectionFlow.value.isConnected) {
                    startPolling()
                } else {
                    stopPolling()
                }
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

        val refreshed = repository.getSessions(MainViewModelTimings.sessionFullLoadLimit)
            .getOrElse {
                DebugLog.w("SessionMetadataPoller", "getSessions failed: ${it.message}")
                return
            }

        val slice = store.sessionListFlow.value
        val merged = mergeRefreshedSessionsPreservingLocalActivity(
            refreshed = refreshed,
            local = slice.sessions,
            currentSessionId = store.chatFlow.value.currentSessionId,
            openSessionIds = slice.openSessionIds.toSet(),
            pendingCreateIds = slice.pendingCreateIds,
        )

        store.mutateSessionList { current ->
            current.copy(sessions = merged)
        }
    }

    private companion object {
        private const val SESSION_METADATA_POLL_INTERVAL_MS = 30_000L
        private const val TAG = "SessionMetadataPoller"
    }
}
