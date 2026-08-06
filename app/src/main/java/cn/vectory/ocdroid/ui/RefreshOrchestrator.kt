package cn.vectory.ocdroid.ui

import androidx.annotation.MainThread
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.di.UiApplicationScope
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.runSuspendCatching
import cn.vectory.ocdroid.ui.controller.ConnectionCoordinator
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import cn.vectory.ocdroid.ui.controller.ForegroundCatchUpController
import cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator
import cn.vectory.ocdroid.ui.controller.SessionSwitcher
import cn.vectory.ocdroid.ui.controller.subtreeIds
import cn.vectory.ocdroid.ui.controller.sse.TokenStreamCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * §Wave2.1-split-l2: session hydration + title refresh orchestrator.
 * Owns the cold-start refresh, disconnect catch-up, message/session loading,
 * title-refresh retry loop, and the token-stream open gate.
 *
 * Depends on: core-five + fp + sessionSwitcher + connectionCoordinator +
 * sessionSyncCoordinator + foregroundCatchUpController + hostProfileStore +
 * serverCompatProfile + tokenStreamCoordinator.
 *
 * ~450 LOC extracted from AppCoreOrchestration.kt.
 */
@Singleton
internal class RefreshOrchestrator @Inject constructor(
    private val store: SharedStateStore,
    private val repository: OpenCodeRepository,
    private val settingsManager: SettingsManager,
    private val effectBus: SharedEffectBus,
    @UiApplicationScope private val appScope: CoroutineScope,
    @Named("currentProfileId") private val currentProfileId: () -> String,
    private val sessionSwitcher: SessionSwitcher,
    private val connectionCoordinator: ConnectionCoordinator,
    private val sessionSyncCoordinator: SessionSyncCoordinator,
    private val foregroundCatchUpController: ForegroundCatchUpController,
    private val hostProfileStore: HostProfileStore,
    private val serverCompatProfile: ServerCompatProfile,
    private val tokenStreamCoordinator: TokenStreamCoordinator,
) {

    // ═══════════════════════════════════════════════════════════════════════
    // Cold-start + force-refresh
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Returns `true` iff the clear+reload actually ran; `false` iff the isLoading
     * guard suppressed it (when [explicit], an Info feedback was emitted on suppress).
     */
    fun performGlobalColdStartRefresh(
        currentId: String,
        forceInitialWindow: Boolean = false,
        explicit: Boolean = false,
    ): Boolean {
        if (store.chatFlow.value.isLoadingMessages || store.chatFlow.value.isLoadingMoreMessages) {
            if (explicit) {
                effectBus.tryEmitUiEvent(UiEvent.Info(R.string.info_refresh_in_progress))
            }
            return false
        }
        val expectedRouteInstance = store.slices.routeInstanceFor(currentId)
        sessionSwitcher.clearSessionWindowCache()
        store.mutateChat { it.copy(refreshNonce = it.refreshNonce + 1) }
        store.dispatch(AppAction.ColdStartChatReset)

        val conn = store.connectionFlow.value
        val autoUnanchor = !forceInitialWindow &&
            shouldAutoUnanchorOnColdStart(
                conn.connectionPhase,
                conn.disconnectedSince,
                System.currentTimeMillis(),
            )
        loadMessagesForEffect(
            currentId,
            resetLimit = true,
            forceInitialWindow = forceInitialWindow || autoUnanchor,
            expectedRouteInstance = expectedRouteInstance,
        )
        return true
    }

    fun performForceRefresh(sessionId: String) {
        val refreshed = performGlobalColdStartRefresh(
            currentId = sessionId,
            forceInitialWindow = true,
            explicit = true,
        )
        if (!refreshed) return
        // 与 coldStartReconnect 对齐 retries=3：瞬时网络抖动（DNS 解析失败 / 连接中断）下
        // 单次探测（retries=0）必败，导致 banner 无法靠硬刷新清除——必须 retries=3 才能扛过抖动。
        connectionCoordinator.testConnection(force = true, retries = 3)
        effectBus.tryEmitEffect(ControllerEffect.LoadSessions)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Disconnect / foreground catch-up
    // ═══════════════════════════════════════════════════════════════════════

    fun catchUpAfterDisconnectOrForeground(sessionId: String) {
        val fp = hostProfileStore.currentProfile().id
        val sseSnap = sessionSyncCoordinator.sseSyncStateSnapshot()
        val sseWorkdir = if (store.sseConnectedFlow.value) settingsManager.currentWorkdir else null
        launchCatchUp(
            scope = appScope,
            repository = repository,
            slices = store.slices,
            sessionId = sessionId,
            settingsManager = settingsManager,
            onCacheWindow = { sid, window -> sessionSwitcher.writeSessionWindow(fp, sid, window) },
            currentProfileId = currentProfileId,
            expectedProfileId = fp,
            sseCurrentWorkdir = sseWorkdir,
            sessionsEverColdSnapshotted = sseSnap.sessionsEverColdSnapshotted,
            onColdSnapshot = { sid -> sessionSyncCoordinator.markSessionColdSnapshotted(sid) },
            expectedRouteInstance = store.slices.routeInstanceFor(sessionId),
        )
        // §rev-ds round-2 FIX 1: restore pre-P3 directory-set computation
        // using computeQuestionFanOutWorkdirs (unions directorySessions.keys +
        // currentWorkdir + recentWorkdirs, normalizes, filters blanks, distincts).
        // Slim path ignores this argument (single global call).
        val questionWorkdirs = computeQuestionFanOutWorkdirs(
            directorySessionKeys = store.sessionListFlow.value.directorySessions.keys,
            currentWorkdir = settingsManager.currentWorkdir,
            recentWorkdirs = settingsManager.getRecentWorkdirs(currentProfileId()),
        )
        foregroundCatchUpController.catchUpPendingQuestionsAllWorkdirs(
            repository = repository,
            workdirs = questionWorkdirs,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Message + session loading
    // ═══════════════════════════════════════════════════════════════════════

    fun loadMessagesForEffect(
        sessionId: String,
        resetLimit: Boolean,
        forceInitialWindow: Boolean = false,
        expectedRouteInstance: Long = 0L,
    ) {
        val fp = currentProfileId()
        launchLoadMessages(
            scope = appScope,
            repository = repository,
            slices = store.slices,
            sessionId = sessionId,
            resetLimit = resetLimit,
            onCacheWindow = { sid, window -> sessionSwitcher.writeSessionWindow(fp, sid, window) },
            emit = EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
            expectedProfileId = fp,
            currentProfileId = currentProfileId,
            forceInitialWindow = forceInitialWindow,
            expectedRouteInstance = expectedRouteInstance,
            isSseLive = { store.slices.sseConnected },
        )
        if (shouldOpenTokenStream(
                serverCompatProfile.tokenStreamEnabled,
                store.slices.chat.value.currentSessionId,
                sessionId,
            )
        ) {
            tokenStreamCoordinator.open(sessionId, settingsManager.currentWorkdir, source = "effect-load")
        }
    }

    fun loadSessionsForEffect() {
        launchLoadSessions(
            scope = appScope,
            repository = repository,
            slices = store.slices,
            settingsManager = settingsManager,
            onSelectSession = { selectSessionForEffect(it) },
            onLoadSessionStatus = {
                launchLoadSessionStatus(
                    appScope, repository, store.slices,
                    trigger = cn.vectory.ocdroid.ui.SessionStatusLoadTrigger.COLD_START,
                )
            },
            onLoadMessages = { sessionId ->
                loadMessagesForEffect(
                    sessionId = sessionId,
                    resetLimit = true,
                    expectedRouteInstance = store.slices.routeInstanceFor(sessionId),
                )
            },
            emit = EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
            expectedProfileId = currentProfileId(),
            currentProfileId = currentProfileId,
            onArchivedSessionsDetected = { merged, hasMore, confirmedServerIds, sweepNow ->
                dispatchBulkArchivedSessions(merged, hasMore, confirmedServerIds, sweepNow)
            },
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Bulk-archive handler
    // ═══════════════════════════════════════════════════════════════════════

    private fun dispatchBulkArchivedSessions(
        mergedSessions: List<Session>,
        hasMoreSessions: Boolean,
        confirmedServerIds: Set<String>,
        sweepNow: Long,
    ) {
        val previousCurrentId = store.chatFlow.value.currentSessionId
        val routeId = routeChatSessionId(store.navFlow.value.lastRoute)
        val archivedIds = mergedSessions
            .filter { it.isArchived }
            .map { it.id }
            .toSet()
        val currentWasArchived = previousCurrentId != null && previousCurrentId in archivedIds
        val routeWasArchived = routeId != null && routeId in archivedIds
        store.dispatch(
            AppAction.BulkSessionsRefreshed(
                sessions = mergedSessions,
                hasMoreSessions = hasMoreSessions,
                confirmedServerIds = confirmedServerIds,
                sweepNow = sweepNow,
            )
        )
        if (routeWasArchived || currentWasArchived) {
            store.dispatch(AppAction.CloseDetail)
            settingsManager.currentSessionId = null
            settingsManager.lastRoute = NavRoute.Sessions.route
            store.mutateNav {
                it.copy(
                    lastRoute = NavRoute.Sessions.route,
                    navEpoch = it.navEpoch + 1L,
                )
            }
        }
        if (currentWasArchived) {
            effectBus.tryEmitEffect(
                ControllerEffect.EvictSession(currentProfileId(), previousCurrentId)
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Title refresh (bounded retry loop)
    // ═══════════════════════════════════════════════════════════════════════

    fun scheduleTitleRefreshAfterFirstMessage(sessionId: String) {
        appScope.launch {
            var attempts = 0
            while (attempts < TITLE_RETRY_MAX_ATTEMPTS) {
                delay(MainViewModelTimings.titleRefreshDelayMs)
                attempts++
                val refreshed = runSuspendCatching { repository.getSession(sessionId).getOrNull() }.getOrNull()
                val title = refreshed?.title
                if (!title.isNullOrBlank()) {
                    store.mutateSessionList { state ->
                        state.copy(
                            sessions = state.sessions.map { if (it.id == sessionId) it.copy(title = title) else it },
                            directorySessions = state.directorySessions.mapValues { (_, list) ->
                                list.map { if (it.id == sessionId) it.copy(title = title) else it }
                            },
                        )
                    }
                    return@launch
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Private helpers
    // ═══════════════════════════════════════════════════════════════════════

    private fun selectSessionForEffect(sessionId: String) {
        sessionSwitcher.switchTo(sessionId)
    }
}
