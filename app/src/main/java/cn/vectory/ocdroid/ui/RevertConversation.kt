package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.RevertCutoff
import cn.vectory.ocdroid.data.model.RevertCutoffState
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

sealed interface RevertOutcome {
    data class Success(val updatedSession: Session) : RevertOutcome
    data class Failure(val error: Throwable) : RevertOutcome
    data object Cancelled : RevertOutcome
}

/** The single destructive-revert orchestration boundary. */
class RevertConversation(private val core: AppCore) {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    suspend fun execute(sessionId: String, messageId: String, reload: (String) -> Unit): RevertOutcome {
        val chat = core.store.chatFlow.value
        val status = core.store.sessionListFlow.value.sessionStatuses[sessionId]
        if (status?.isBusy == true || status?.isRetry == true ||
            chat.currentSessionId == sessionId && (chat.streamingPartTexts.isNotEmpty() || chat.streamingReasoningPart != null) ||
            core.store.composerFlow.value.sendingSessionIds.contains(sessionId)
        ) return RevertOutcome.Failure(IllegalStateException("会话正在生成，无法回退"))
        if (!inFlight.add(sessionId)) return RevertOutcome.Cancelled
        try {
            val message = chat.messages.firstOrNull { it.id == messageId && it.isUser }
                ?: return RevertOutcome.Cancelled
            val draft = chat.partsByMessage[messageId].orEmpty().firstOrNull { it.isText }?.text?.trim().orEmpty()
            if (draft.isBlank()) return RevertOutcome.Cancelled
            val serverUpdated = core.repository.revertSession(sessionId, messageId).getOrElse { return RevertOutcome.Failure(it) }
            // The response is normally authoritative. Retain the just-accepted
            // target if an older server omits `revert`, otherwise the cache
            // projection could lose the only cold-start fail-closed signal.
            val updated = serverUpdated.copy(
                revert = serverUpdated.revert ?: Session.RevertInfo(messageId = messageId)
            )
            val cutoff = RevertCutoff(
                sessionId, messageId,
                message.time?.created?.let(RevertCutoffState::Resolved) ?: RevertCutoffState.NoTimestamp
            )
            core.writeSessionList { state -> state.copy(sessions = state.sessions.map { if (it.id == sessionId) updated else it }) }
            core.writeChat { it.copy(revertCutoffs = it.revertCutoffs + (sessionId to cutoff)) }
            // SessionCacheEntry is the sole persisted cutoff source. Disk writes
            // are best-effort: server success and the live fail-closed state win.
            runCatching {
                persistSessionCache(
                    settingsManager = core.settingsManager,
                    sessions = core.store.sessionListFlow.value.sessions,
                    openIds = core.store.sessionListFlow.value.openSessionIds,
                    currentId = core.store.chatFlow.value.currentSessionId,
                    currentWorkdir = core.settingsManager.currentWorkdir,
                    revertCutoffs = core.store.chatFlow.value.revertCutoffs
                )
            }.onFailure { DebugLog.w("RevertConversation", "persist cutoff failed: ${it.message}") }
            // §1B-FIX (I4): also clear fileReferences on revert-success
            // — the message text being restored is the original pre-revert
            // text, but the file-reference chip set was attached to the
            // later state (after the message being reverted was sent).
            // The user has no semantic link between the old draft text and
            // the chip set, so the chips must be cleared.
            core.writeComposer {
                it.copy(inputText = draft, imageAttachments = emptyList(), fileReferences = emptyList())
            }
            runCatching {
                core.settingsManager.setDraftText(core.currentServerGroupFp(), sessionId, draft)
            }.onFailure { DebugLog.w("RevertConversation", "persist draft failed: ${it.message}") }
            reload(sessionId)
            core.loadSessionsForEffect()
            return RevertOutcome.Success(updated)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return RevertOutcome.Failure(error)
        } finally {
            inFlight.remove(sessionId)
        }
    }
}
