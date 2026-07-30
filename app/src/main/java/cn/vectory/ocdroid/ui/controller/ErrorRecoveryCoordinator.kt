package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.di.UiApplicationScope
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.SharedStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §P0-E(b)(c): the GET drain/consumer for durable error localization.
 *
 * Subscribes to [SharedStateStore.stateFlow] and drains two markers:
 *
 *  - **(b) reattach drain**: [ChatState.pendingErrorReattach] entries for the
 *    current session (`currentSessionId == sid`). These are `LastAssistantErrorAttached`
 *    payloads that couldn't attach at arrival (route mismatch / last==null /
 *    last already has error). The coordinator re-reads the transcript via
 *    [OpenCodeRepository.getMessages] to find the server-identified error-bearing
 *    assistant (B2: `session.error` carries no messageId, so GET is required).
 *
 *  - **(c) GET fallback drain**: [ChatState.pendingErrorCheck] entries where the
 *    session has a `sessionErrorsById` banner AND the last assistant in the
 *    loaded messages lacks a durable error. This handles the case where the
 *    session transitioned busy/retry → idle (AuthorityReducer marks it) but the
 *    displayed messages already include the errored assistant that the producer
 *    path missed (route didn't match at the time).
 *
 * Both markers trigger [repository.getMessages] over the same sid; the first
 * to settle clears the other via [reduceErrorLocalizationSettled] (the reducer
 * always clears both `pendingErrorReattach[sid]` + `pendingErrorCheck -= sid`).
 *
 * Singleton, constructed on [UiApplicationScope] (Main.immediate — single-
 * threaded, so [inFlight] is safe without locks). Started by the DI provider
 * in [cn.vectory.ocdroid.di.ControllerModule]; the `init` block launches the
 * collector immediately.
 */
@Singleton
class ErrorRecoveryCoordinator @Inject constructor(
    @UiApplicationScope private val scope: CoroutineScope,
    private val store: SharedStateStore,
    private val repository: OpenCodeRepository,
) {
    /** Single-threaded (Main.immediate) — safe without locks. */
    private val inFlight = mutableSetOf<String>()

    init {
        scope.launch {
            store.stateFlow.collect { state ->
                val chat = state.chat
                val sessionList = state.sessionList
                val currentSid = chat.currentSessionId
                val lastAssistant = chat.messages.lastOrNull { it.isAssistant }

                // (b) reattach drain: session.error recorded but couldn't attach;
                // localize only when the user is viewing that session so the GET's
                // assistant.id maps to the displayed message list.
                val reattachToDrain = chat.pendingErrorReattach.keys.filter { it == currentSid }
                // (c) GET fallback: round ended (pendingErrorCheck) + session-level
                // error banner present + last assistant has no durable error → recover.
                val fallbackToDrain = chat.pendingErrorCheck.filter { sid ->
                    sessionList.sessionErrorsById[sid] != null &&
                        (lastAssistant == null || (currentSid == sid && lastAssistant.error == null))
                }
                val toDrain = (reattachToDrain + fallbackToDrain).toSet() - inFlight
                for (sid in toDrain) {
                    inFlight += sid
                    scope.launch { drain(sid) }
                }
            }
        }
    }

    /**
     * Fetches messages for [sid] via [OpenCodeRepository.getMessages], finds the
     * most recent error-bearing assistant, and dispatches [AppAction.ErrorLocalizationSettled].
     * On network failure, settles as a no-op (markers cleared; banner already displays).
     */
    private suspend fun drain(sid: String) {
        try {
            val result = repository.getMessages(sid, limit = 50)
            val errAssistant = result.getOrNull()
                ?.sortedBy { it.info.time?.created ?: 0L }
                ?.lastOrNull { it.info.role == "assistant" && it.info.error != null }
                ?.info
            store.dispatch(AppAction.ErrorLocalizationSettled(
                sessionId = sid,
                attachToMessageId = errAssistant?.id,
                error = errAssistant?.error,
            ))
        } catch (_: Exception) {
            // Network failure → settle as no-op (markers cleared; banner already shows).
            store.dispatch(AppAction.ErrorLocalizationSettled(sid, null, null))
        } finally {
            inFlight -= sid
        }
    }
}
