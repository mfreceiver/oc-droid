package cn.vectory.ocdroid.ui

import androidx.lifecycle.ViewModel
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * R-17 batch3 → batch3d: Chat-domain ViewModel. Owns the chat slice + the
 * message-window lifecycle (load / page / gap-close / streaming overlay),
 * plus the abort / compact / edit / refresh operations.
 *
 * **batch3d**: the method bodies that USED to live in [AppCore] (and were
 * exposed as `fun xxx() = core.xxx()` delegate shells) have been physically
 * moved HERE. The VM now calls its domain controller
 * ([AppCore.sessionSyncCoordinator], [AppCore.composerController]) and the
 * [MainViewModelMessageActions] / [MainViewModelCatchUpActions] free functions
 * directly — no more `core.<method>()` self-bypass.
 *
 * Cross-domain orchestration (`sendMessage` — composer→chat→session creation)
 * stays in [AppCore] (it spans 3 domains) and is surfaced via [sendMessage].
 *
 * State reads come from the shared [SharedStateStore] (slices are the sole
 * authoritative store; the VMs read each other's slices through it, never
 * through sibling VM references).
 *
 * Chat-screen affordances that touch OTHER domains (composer input, model
 * picker, permission/question responses, file preview) live on their own
 * domain VMs ([ComposerViewModel], [OrchestratorViewModel]) — ChatScreen
 * injects those VMs alongside this one (see the batch3d composable wiring).
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    internal val core: AppCore,
) : ViewModel() {

    val chatFlow get() = core.chatFlow
    val sessionListFlow get() = core.sessionListFlow
    val unreadFlow get() = core.unreadFlow
    val expandedParts get() = core.expandedParts
    val connectionFlow get() = core.connectionFlow
    val composerFlow get() = core.composerFlow
    val settingsFlow get() = core.settingsFlow
    val fileFlow get() = core.fileFlow
    val trafficFlow get() = core.trafficFlow
    val hostFlow get() = core.hostFlow
    val uiEvents get() = core.uiEvents

    /** §R-17 batch3e: repository exposed so ChatMessageList can pass it down
     *  to MessageRow without touching `.core.` from a Composable. */
    val repository: OpenCodeRepository get() = core.repository

    // ── Chat-domain methods (bodies moved from AppCore) ─────────────────────

    fun loadMessages(sessionId: String, resetLimit: Boolean) {
        // §R-17 batch3d: body moved verbatim from AppCore; reaches the shared
        // store/controllers/free-functions directly instead of delegating back
        // to AppCore.loadMessages.
        launchLoadMessages(
            scope = core.appScope,
            repository = core.repository,
            slices = core.store.slices,
            sessionId = sessionId,
            resetLimit = resetLimit,
            settingsManager = core.settingsManager,
            onCacheWindow = core.sessionSwitcher::writeSessionWindow,
            settingsFlow = core.store.settingsFlow,
            emit = EventEmitter { event -> core.effectBus.uiEvents.tryEmit(event) },
        )
    }

    fun loadMessages(sessionId: String) = loadMessages(sessionId, resetLimit = true)

    internal fun loadMessagesWithRetry(sessionId: String, resetLimit: Boolean = true) {
        launchLoadMessagesWithRetry(core.appScope, sessionId, core.store.slices, resetLimit, ::loadMessages)
    }

    fun loadMoreMessages() {
        val sessionId = core.store.chatFlow.value.currentSessionId ?: return
        launchLoadMoreMessages(
            scope = core.appScope,
            repository = core.repository,
            slices = core.store.slices,
            sessionId = sessionId,
            onCacheWindow = core.sessionSwitcher::writeSessionWindow,
        )
    }

    fun closeGap() {
        val sessionId = core.store.chatFlow.value.currentSessionId ?: return
        launchCloseGap(
            scope = core.appScope,
            repository = core.repository,
            slices = core.store.slices,
            sessionId = sessionId,
            onCacheWindow = core.sessionSwitcher::writeSessionWindow,
        )
    }

    fun compactSession() {
        val chatFlow = core.store.chatFlow
        if (chatFlow.value.isCompacting) return
        val sessionId = chatFlow.value.currentSessionId ?: run {
            core.effectBus.uiEvents.tryEmit(UiEvent.Error("Open or create a session before compacting"))
            return
        }
        val model = chatFlow.value.currentModel ?: run {
            core.effectBus.uiEvents.tryEmit(UiEvent.Error("No model info available for compaction"))
            return
        }
        core.writeChat { it.copy(isCompacting = true, compactStartedAt = System.currentTimeMillis()) }
        core.appScope.launch {
            core.repository.summarizeSession(sessionId, model)
                .onFailure { error ->
                    core.writeChat { it.copy(isCompacting = false, compactStartedAt = 0L) }
                    core.effectBus.uiEvents.tryEmit(UiEvent.Error(errorMessageOrFallback(error, "Compact failed")))
                }
        }
    }

    fun clearCompacting() {
        if (core.store.chatFlow.value.isCompacting) {
            core.writeChat { it.copy(isCompacting = false, compactStartedAt = 0L) }
        }
    }

    fun editFromMessage(messageId: String) {
        val chatFlow = core.store.chatFlow
        val sessionId = chatFlow.value.currentSessionId ?: return
        val message = chatFlow.value.messages.firstOrNull { it.id == messageId && it.isUser } ?: return
        val draft = (chatFlow.value.partsByMessage[messageId] ?: emptyList()).firstOrNull { it.isText }?.text?.trim().orEmpty()
        if (draft.isBlank()) return

        core.appScope.launch {
            core.repository.revertSession(sessionId, messageId)
                .onSuccess { updatedSession ->
                    core.writeSessionList { state ->
                        state.copy(sessions = state.sessions.map { session -> if (session.id == sessionId) updatedSession else session })
                    }
                    core.writeComposer { c -> c.copy(inputText = draft, imageAttachments = emptyList()) }
                    core.settingsManager.setDraftText(sessionId, draft)
                    loadMessages(sessionId, resetLimit = true)
                    core.loadSessionsForEffect()
                }
                .onFailure { error ->
                    core.effectBus.uiEvents.tryEmit(UiEvent.Error("Failed to edit message: ${errorMessageOrFallback(error, "unknown error")}"))
                }
        }
    }

    fun abortSession() {
        val sessionId = core.store.chatFlow.value.currentSessionId ?: return
        core.appScope.launch {
            core.repository.abortSession(sessionId)
                .onFailure { error ->
                    core.effectBus.uiEvents.tryEmit(UiEvent.Error(errorMessageOrFallback(error, "Failed to abort session")))
                }
        }
    }

    fun refreshCurrentSession() {
        val sessionId = core.store.chatFlow.value.currentSessionId ?: return
        if (core.store.chatFlow.value.isLoadingMessages) return
        core.performGlobalColdStartRefresh(currentId = sessionId)
        core.connectionCoordinator.testConnection(force = true, onSettled = { ok ->
            if (ok && !core.store.chatFlow.value.isLoadingMessages) {
                core.effectBus.uiEvents.tryEmit(UiEvent.Success("已刷新"))
            }
        })
    }

    fun togglePartExpand(key: String, currentValue: Boolean) {
        core.composerController.togglePartExpand(key, currentValue)
    }

    /** §R-17 batch3d: routes to the composer controller that owns expandedParts. */
    fun clearExpandedParts() {
        core.composerController.clearExpandedParts()
    }

    /** Cross-domain: composer→chat→session creation lives in [AppCore]. */
    fun sendMessage() = core.sendMessage()

    /** Test-only visibility into the session-window cache size. */
    internal fun sessionWindowCacheSize(): Int = core.sessionSwitcher.sessionWindowCacheSize()
    internal fun peekSessionWindow(sessionId: String) = core.sessionSwitcher.peekSessionWindow(sessionId)
}
