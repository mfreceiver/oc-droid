package com.yage.opencode_client.ui

import android.util.Log

import com.yage.opencode_client.data.model.SSEEvent
import com.yage.opencode_client.data.model.TodoItem
import com.yage.opencode_client.data.repository.OpenCodeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/**
 * §15.1: SSE collection coroutine. Wraps [OpenCodeRepository.connectSSE] so
 * the resulting Flow's [Result]s are unpacked and forwarded to [onEvent].
 * Failures (including the [com.yage.opencode_client.data.api.SSEConnectionExhausted]
 * raised after the §15.3 retry budget is spent) land in `AppState.error`
 * via the `catch` block so the UI can surface a "messages may be stale" banner.
 */
internal fun launchSseCollection(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    onEvent: (SSEEvent) -> Unit
): Job {
    return scope.launch {
        repository.connectSSE()
            .catch { error ->
                Log.e("OC_ERROR", "SSE collection failed", error)
                state.update { it.copy(error = "SSE Error: ${error.message}") }
            }
            .collect { result ->
                result.onSuccess { event -> onEvent(event) }
                    .onFailure { error ->
                        Log.e("OC_ERROR", "SSE event failed", error)
                        state.update { it.copy(error = "SSE Error: ${error.message}") }
                    }
            }
    }
}

/**
 * Dispatches a single SSE event. Per §15 (15.1 / 15.1.4 / 15.3) and §18 (R-A)
 * review refinements:
 *
 * - `message.updated` for the current session no longer reloads directly; it
 *   pushes into the debounced [onMessagesRefreshTriggered] signal so a burst
 *   of updates coalesces into one reload.
 * - `message.part.updated` with empty delta but non-null ids (a part status
 *   flip) does NOT clear streaming buffers or reload — the watchdog
 *   (§15.1.4) catches up. Only a true `part.created` (ids null) wipes the
 *   streaming state and reloads.
 * - Every branch that touches the current session refreshes the watchdog's
 *   "last seen alive" timestamp via [onLastSseProgress].
 * - `session.status` transitions drive the watchdog lifecycle: any -> busy
 *   starts it (via [onSessionBecameBusy]); busy -> idle forces one final
 *   reload and cancels it (via [onSessionBecameIdle]).
 */
internal fun handleIncomingSseEvent(
    state: MutableStateFlow<AppState>,
    event: SSEEvent,
    onRefreshMessages: (String, Boolean) -> Unit,
    onRefreshSessions: () -> Unit,
    onLoadPendingPermissions: () -> Unit,
    onNonFatalIssue: (String) -> Unit,
    onMessagesRefreshTriggered: () -> Unit,
    onLastSseProgress: () -> Unit,
    onSessionBecameBusy: () -> Unit,
    onSessionBecameIdle: () -> Unit
) {
    when (event.payload.type) {
        "session.created" -> {
            val created = parseSessionCreatedEvent(event)
            if (created != null) {
                state.update { it.copy(sessions = upsertSession(it.sessions, created.session)) }
            } else {
                onNonFatalIssue("Ignoring invalid session.created payload")
            }
        }
        "session.updated" -> {
            val updated = parseSessionUpdatedEvent(event)
            if (updated != null) {
                state.update { it.copy(sessions = upsertSession(it.sessions, updated)) }
            } else {
                onNonFatalIssue("Ignoring invalid session.updated payload")
            }
        }
        "session.status" -> {
            val statusEvent = parseSessionStatusEvent(event)
            if (statusEvent != null) {
                val previousStatus = state.value.sessionStatuses[statusEvent.sessionId]
                val wasBusy = previousStatus?.isBusy == true
                state.update {
                    it.copy(
                        sessionStatuses = it.sessionStatuses + (statusEvent.sessionId to statusEvent.status)
                    )
                }
                // When a temp-cleared session finishes its in-flight work
                // (busy -> idle) and is NOT the currently-open one, drop it
                // from tempClearedUnread: there is no longer any pending work
                // that would warrant re-marking it. If the session was already
                // re-marked unread (because the user navigated away while it
                // was busy), keep the badge so the user still knows there was
                // activity — the user opening the session will clear it.
                if (!statusEvent.status.isBusy &&
                    statusEvent.sessionId != state.value.currentSessionId &&
                    state.value.tempClearedUnread.contains(statusEvent.sessionId)
                ) {
                    state.update {
                        it.copy(tempClearedUnread = it.tempClearedUnread - statusEvent.sessionId)
                    }
                }
                if (statusEvent.sessionId == state.value.currentSessionId) {
                    // §15.1.4 watchdog lifecycle:
                    //  - any -> busy: start the watchdog so a 5s silent gap
                    //    during streaming kicks a fallback reload.
                    //  - busy -> idle: force one last reload to flush any
                    //    tail events the server may have already persisted,
                    //    then cancel the watchdog (no more streaming expected).
                    if (statusEvent.status.isBusy) {
                        onLastSseProgress()
                        onSessionBecameBusy()
                    } else {
                        state.update {
                            it.copy(
                                streamingPartTexts = emptyMap(),
                                streamingReasoningPart = null
                            )
                        }
                        onLastSseProgress()
                        onRefreshMessages(statusEvent.sessionId, false)
                        onSessionBecameIdle()
                    }
                }
            } else {
                onNonFatalIssue("Ignoring invalid session.status payload")
            }
        }
        "message.created" -> {
            val sessionId = event.payload.getString("sessionID")
            if (sessionId != null && sessionId == state.value.currentSessionId) {
                onLastSseProgress()
                onRefreshMessages(sessionId, true)
            } else if (sessionId != null) {
                // Mark unread: an out-of-band message arrived for a session
                // the user is not currently viewing.
                markSessionUnread(state, sessionId)
            }
        }
        "message.updated" -> {
            // Only refresh the open session's messages. Do NOT mark unread:
            // message.updated fires for every streaming delta/touch on the
            // server (token appends, tool updates, status flips), which would
            // spam the unread badge. Unread marking is reserved for
            // message.created (a genuinely new message) — see the branch above.
            //
            // §15.1: instead of reloading per-event, push into the debounced
            // trigger so a burst coalesces. selectSession/sendMessage bypass
            // this path and call loadMessages directly.
            val sessionId = event.payload.getString("sessionID")
            if (sessionId != null && sessionId == state.value.currentSessionId) {
                onLastSseProgress()
                onMessagesRefreshTriggered()
            }
        }
        "message.part.updated" -> {
            val deltaEvent = parseMessagePartDeltaEvent(event) ?: return
            if (deltaEvent.sessionId == state.value.currentSessionId) {
                onLastSseProgress()
                if (
                    deltaEvent.messageId != null &&
                    deltaEvent.partId != null &&
                    !deltaEvent.delta.isNullOrBlank()
                ) {
                    val key = "${deltaEvent.messageId}:${deltaEvent.partId}"
                    val previousValue = state.value.streamingPartTexts[key] ?: ""
                    state.update {
                        it.copy(
                            streamingPartTexts = it.streamingPartTexts + (key to (previousValue + deltaEvent.delta)),
                            streamingReasoningPart = reasoningPartOrNull(
                                partType = deltaEvent.partType,
                                partId = deltaEvent.partId,
                                messageId = deltaEvent.messageId,
                                sessionId = deltaEvent.sessionId
                            ) ?: it.streamingReasoningPart
                        )
                    }
                } else if (deltaEvent.messageId == null || deltaEvent.partId == null) {
                    // §15.1 (review B3): a true `part.created` (no message/part
                    // id yet) signals the start of a fresh streaming run —
                    // clear any stale partials and reload so we render the
                    // server-authoritative snapshot.
                    state.update {
                        it.copy(streamingPartTexts = emptyMap(), streamingReasoningPart = null)
                    }
                    onRefreshMessages(deltaEvent.sessionId, false)
                }
                // Else: ids present + empty delta = part status flip. Do
                // NOT clear streaming buffers and do NOT pull — the §15.1.4
                // watchdog covers catch-up. This was a hot path under the old
                // 2s poll loop; running it on every flip would re-introduce
                // redundant traffic.
            }
        }
        "permission.asked" -> {
            onLoadPendingPermissions()
        }
        "question.asked" -> {
            val question = parseQuestionAskedEvent(event)
            if (question != null) {
                state.update { currentState ->
                    val existing = currentState.pendingQuestions.any { it.id == question.id }
                    if (!existing) {
                        currentState.copy(pendingQuestions = currentState.pendingQuestions + question)
                    } else {
                        currentState
                    }
                }
            } else {
                onNonFatalIssue("Ignoring invalid question.asked payload")
            }
        }
        "question.replied", "question.rejected" -> {
            val requestId = event.payload.getString("requestID") 
                ?: event.payload.getString("id")
            if (requestId != null) {
                state.update { currentState ->
                    currentState.copy(
                        pendingQuestions = currentState.pendingQuestions.filter { it.id != requestId }
                    )
                }
            }
        }
        "todo.updated" -> {
            val sessionId = event.payload.getString("sessionID") ?: return
            val todosArray = event.payload.properties?.get("todos") as? kotlinx.serialization.json.JsonArray ?: return
            val todos = try {
                Json.decodeFromJsonElement<List<TodoItem>>(todosArray)
            } catch (_: Exception) {
                return
            }
            state.update { it.copy(sessionTodos = it.sessionTodos + (sessionId to todos)) }
        }
    }
}

/**
 * Mark [sessionId] as having unread activity. Skipped when the session is
 * already the currently-open one (caller guards this) and when the user has
 * never opened it before (we still want the badge in that case, so we only
 * short-circuit on identical selected session, which the caller handles).
 */
private fun markSessionUnread(
    state: MutableStateFlow<AppState>,
    sessionId: String
) {
    state.update { current ->
        if (sessionId == current.currentSessionId) {
            current
        } else {
            current.copy(unreadSessions = current.unreadSessions + sessionId)
        }
    }
}
