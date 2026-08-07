package cn.vectory.ocdroid.ui

import androidx.annotation.MainThread
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.di.UiApplicationScope
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.ui.controller.ConnectionCoordinator
import cn.vectory.ocdroid.ui.controller.SessionSwitcher
import cn.vectory.ocdroid.ui.controller.allSessionsById
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * §Wave2.1-split-l2: captured send dispatch orchestrator.
 * Owns [dispatchSendMessage] (existing-session send), [dispatchCapturedSend]
 * (materialize→send path), and their private helpers.
 *
 * Depends on: core-five + fp + sessionSwitcher + connectionCoordinator(diagLayer).
 *
 * NOTE: The draft-vs-existing routing lives in AppCore.sendMessage() (thin
 * router) to avoid a circular dep (DraftSessionOrchestrator depends on us).
 *
 * ~350 LOC extracted from AppCoreOrchestration.kt.
 */
@Singleton
internal class SendOrchestrator @Inject constructor(
    private val store: SharedStateStore,
    private val repository: OpenCodeRepository,
    private val settingsManager: SettingsManager,
    private val effectBus: SharedEffectBus,
    @UiApplicationScope private val appScope: CoroutineScope,
    @Named("currentProfileId") private val currentProfileId: () -> String,
    private val sessionSwitcher: SessionSwitcher,
    private val connectionCoordinator: ConnectionCoordinator,
) {

    // ═══════════════════════════════════════════════════════════════════════
    // dispatchSendMessage (existing-session send path)
    // ═══════════════════════════════════════════════════════════════════════

    fun dispatchSendMessage(sessionId: String) {
        val composer = store.composerFlow.value
        if (composer.sendingSessionIds.contains(sessionId)) return
        val text = composer.inputText.trim()
        val attachments = composer.imageAttachments
        if (text.isEmpty() && attachments.isEmpty()) return

        // §B2 rev-gpt: subagent 只读——禁止向子会话发消息
        val sl = store.sessionListFlow.value
        val sessionsById = allSessionsById(sl.sessions, sl.directorySessions, sl.childSessions)
        val targetSession = sessionId.let { sessionsById[it] }
        if (targetSession?.parentId != null) {
            DebugLog.i("Send", "dispatchSendMessage blocked — subagent session sid=$sessionId")
            return
        }

        if (cn.vectory.ocdroid.util.DebugLog.verboseDiagEnabled) {
            DebugLog.i(
                "LayerDiag",
                "dispatchSendMessage sid=$sessionId layer=${connectionCoordinator.diagLayer} " +
                    "status=${store.sessionListFlow.value.sessionStatuses[sessionId]?.type} " +
                    "sending=${store.composerFlow.value.sendingSessionIds}",
            )
        }

        store.mutateComposer { state -> state.copy(sendingSessionIds = state.sendingSessionIds + sessionId) }
        DebugLog.i("SendDiag", "optimistic sendingSessionIds set sid=$sessionId")
        val fp = currentProfileId()
        settingsManager.setDraftText(fp, sessionId, "")
        settingsManager.flushDraftText()
        store.mutateComposer { it.copy(inputText = "", imageAttachments = emptyList(), fileReferences = emptyList()) }

        sessionSwitcher.requestLatestScroll(sessionId)

        val currentSession = currentSession(store.sessionListFlow.value.sessions, store.chatFlow.value.currentSessionId)

        fun dispatchSend() {
            val chatState = store.chatFlow.value
            val visibleAgents = store.settingsFlow.value.agents
                .filter { it.isVisible }
                .map { it.name }
                .toSet()
            val agent: String? = chatState.pendingAgent
                ?: inferCurrentAgent(chatState.messages, visibleAgents)
            val model: Message.ModelInfo? = chatState.pendingModel
                ?: inferCurrentModel(chatState.messages, visibleAgents)
            launchSendMessage(
                scope = appScope,
                repository = repository,
                slices = store.slices,
                sessionId = sessionId,
                text = text,
                attachments = attachments,
                agent = agent,
                model = model,
                onRefreshMessages = { sid, reset -> loadMessagesWithRetry(sid, reset) },
                onSuccess = {
                    settingsManager.setDraftText(fp, sessionId, "")
                    settingsManager.flushDraftText()
                    store.mutateComposer { it.copy(inputText = "", imageAttachments = emptyList(), fileReferences = emptyList()) }
                },
                onComplete = {
                    store.mutateComposer { state -> state.copy(sendingSessionIds = state.sendingSessionIds - sessionId) }
                },
                emit = EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
            )
            store.mutateChat { it.copy(pendingAgent = null, pendingModel = null) }
        }

        if (currentSession?.isArchived == true) {
            appScope.launch {
                repository.updateSessionArchived(sessionId, -1L)
                    .onSuccess { updated ->
                        store.dispatch(AppAction.SessionUpserted(updated))
                        dispatchSend()
                    }
                    .onFailure { error ->
                        val currentInput = store.composerFlow.value.inputText
                        val restored = if (currentInput.isBlank()) text else currentInput
                        if (restored != currentInput) settingsManager.setDraftText(fp, sessionId, restored)
                        effectBus.tryEmitUiEvent(
                            UiEvent.Error(
                                R.string.error_restore_session_failed,
                                listOf(errorMessageOrFallback(error, "unknown error"))
                            )
                        )
                        store.mutateComposer { c ->
                            c.copy(sendingSessionIds = c.sendingSessionIds - sessionId, inputText = restored)
                        }
                    }
            }
            return
        }
        dispatchSend()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // dispatchCapturedSend (materialize→send path)
    // ═══════════════════════════════════════════════════════════════════════

    @MainThread
    fun dispatchCapturedSend(
        sessionId: String,
        payload: CapturedSendPayload,
        expectedRouteInstance: Long,
    ) {
        if (payload.text.isEmpty() && payload.attachments.isEmpty()) return
        if (store.composerFlow.value.sendingSessionIds.contains(sessionId)) return

        // §B2 rev-gpt: subagent 只读——禁止向子会话发消息
        val sl = store.sessionListFlow.value
        val sessionsById = allSessionsById(sl.sessions, sl.directorySessions, sl.childSessions)
        val targetSession = sessionId.let { sessionsById[it] }
        if (targetSession?.parentId != null) {
            DebugLog.i("Send", "dispatchCapturedSend blocked — subagent session sid=$sessionId")
            return
        }

        store.mutateComposer { state -> state.copy(sendingSessionIds = state.sendingSessionIds + sessionId) }

        val currentComposer = store.composerFlow.value
        val textMatches = currentComposer.inputText.trim() == payload.text
        val attachmentsMatch = currentComposer.imageAttachments == payload.attachments
        val fileRefsMatch = currentComposer.fileReferences == payload.fileReferences
        store.mutateComposer { c ->
            c.copy(
                inputText = if (textMatches) "" else c.inputText,
                imageAttachments = if (attachmentsMatch) emptyList() else c.imageAttachments,
                fileReferences = if (fileRefsMatch) emptyList() else c.fileReferences,
            )
        }
        if (textMatches) {
            settingsManager.setDraftText(currentProfileId(), sessionId, "")
            settingsManager.flushDraftText()
        }

        sessionSwitcher.requestLatestScroll(sessionId)

        val currentSession = currentSession(store.sessionListFlow.value.sessions, store.chatFlow.value.currentSessionId)
        if (currentSession?.isArchived == true) {
            appScope.launch {
                repository.updateSessionArchived(sessionId, -1L)
                    .onSuccess { updated ->
                        store.dispatch(AppAction.SessionUpserted(updated))
                        launchCapturedSend(sessionId, payload, expectedRouteInstance)
                    }
                    .onFailure { error ->
                        effectBus.tryEmitUiEvent(
                            UiEvent.Error(
                                R.string.error_restore_session_failed,
                                listOf(errorMessageOrFallback(error, "unknown error"))
                            )
                        )
                        store.mutateComposer { c ->
                            c.copy(sendingSessionIds = c.sendingSessionIds - sessionId)
                        }
                    }
            }
            return
        }
        launchCapturedSend(sessionId, payload, expectedRouteInstance)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Public helper (called from DraftSessionOrchestrator on adoption failure)
    // ═══════════════════════════════════════════════════════════════════════

    fun launchBackgroundSendForMaterializeFailure(
        sessionId: String,
        payload: CapturedSendPayload,
    ) {
        launchSendMessage(
            scope = appScope,
            repository = repository,
            slices = store.slices,
            sessionId = sessionId,
            text = payload.text,
            attachments = payload.attachments,
            agent = payload.agent,
            model = payload.model,
            onRefreshMessages = { _, _ -> },
            onSuccess = { },
            onComplete = { },
            emit = EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Private helpers
    // ═══════════════════════════════════════════════════════════════════════

    private fun launchCapturedSend(
        sessionId: String,
        payload: CapturedSendPayload,
        expectedRouteInstance: Long,
    ) {
        launchSendMessage(
            scope = appScope,
            repository = repository,
            slices = store.slices,
            sessionId = sessionId,
            text = payload.text,
            attachments = payload.attachments,
            agent = payload.agent,
            model = payload.model,
            onRefreshMessages = { sid, reset ->
                val liveToken = store.slices.routeInstanceFor(sid)
                if (liveToken == expectedRouteInstance) {
                    loadMessagesForEffect(sid, reset, expectedRouteInstance = expectedRouteInstance)
                }
            },
            onSuccess = { },
            onComplete = {
                store.mutateComposer { state -> state.copy(sendingSessionIds = state.sendingSessionIds - sessionId) }
            },
            emit = EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
        )
        store.mutateChat { it.copy(pendingAgent = null, pendingModel = null) }
    }

    private fun loadMessagesForEffect(
        sessionId: String,
        resetLimit: Boolean,
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
            forceInitialWindow = false,
            expectedRouteInstance = expectedRouteInstance,
            isSseLive = { store.slices.sseConnected },
        )
    }

    private fun loadMessagesWithRetry(sessionId: String, resetLimit: Boolean = true) {
        launchLoadMessagesWithRetry(appScope, sessionId, store.slices, resetLimit) { sid, reset ->
            loadMessagesForEffect(
                sessionId = sid,
                resetLimit = reset,
                expectedRouteInstance = store.slices.routeInstanceFor(sid),
            )
        }
    }
}
