package com.yage.opencode_client.ui

import android.util.Log

import com.yage.opencode_client.data.model.Message
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
 * Dispatches a single SSE event. Per the SSE-trust model (mirrors opencode-web):
 *
 * - `message.updated` for the current session does NOT reload — live text comes
 *   via `streamingPartTexts` (populated by `message.part.updated` delta/full
 *   text), and structural sync happens on `message.created` (which reloads).
 * - `message.part.updated` with empty delta but non-null ids (a part status
 *   flip) does NOT clear streaming buffers or reload. Only a true `part.created`
 *   (ids null) wipes the streaming state and reloads.
 * - `session.status` transitions only update the `sessionStatuses` map (busy/idle
 *   badge). They do NOT reload or clear streaming buffers — the finalized turn
 *   text is carried by `streamingPartTexts` until the next `message.created`
 *   reload or a foreground catch-up reconciles the persisted message list.
 * - There is no watchdog/idle-reload: a silently-stalled SSE feed recovers via
 *   connection-level retry, a foreground transition (SSE restart), or the next
 *   user action — matching opencode-web.
 */
internal fun handleIncomingSseEvent(
    state: MutableStateFlow<AppState>,
    event: SSEEvent,
    onRefreshMessages: (String, Boolean) -> Unit,
    onRefreshSessions: () -> Unit,
    onLoadPendingPermissions: () -> Unit,
    onNonFatalIssue: (String) -> Unit
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
                // SSE-trust model: session.status (busy/idle) only updates the
                // status badge. It does NOT reload or clear streaming buffers.
                // The finalized turn text is carried by streamingPartTexts
                // until the next message.created reload or a foreground catch-up
                // reconciles the persisted message list — mirroring opencode-web.
            } else {
                onNonFatalIssue("Ignoring invalid session.status payload")
            }
        }
        "message.created" -> {
            val sessionId = event.payload.getString("sessionID")
            if (sessionId != null && sessionId == state.value.currentSessionId) {
                onRefreshMessages(sessionId, true)
            } else if (sessionId != null) {
                // Mark unread: an out-of-band message arrived for a session
                // the user is not currently viewing.
                markSessionUnread(state, sessionId)
            }
        }
        "message.updated" -> {
            // SSE-trust: patch the message metadata in-place from the server's
            // authoritative `info` (mirrors opencode-web server-session.ts:706).
            // Server payload confirmed to carry a full { info: Message } object.
            // We replace the matching Message in the split `messages` store
            // (List<Message>); its parts live separately in partsByMessage and
            // are NOT touched. If the message isn't in the current view it's a
            // no-op (loaded later by message.created / loadMore). No reload,
            // no unread marking (message.updated fires per streaming delta).
            val infoJson = event.payload.getJsonObject("info")
            if (infoJson != null) {
                val updated = runCatching {
                    lenientJson.decodeFromJsonElement<Message>(infoJson)
                }.getOrNull()
                if (updated != null && updated.id.isNotEmpty()) {
                    // Folded into a single atomic update (no TOCTOU): if the id
                    // isn't in the current view, return s unchanged.
                    state.update { s ->
                        if (s.messages.none { it.id == updated.id }) {
                            s
                        } else {
                            s.copy(
                                messages = s.messages.map {
                                    if (it.id == updated.id) updated else it
                                }
                            )
                        }
                    }
                }
            }
        }
        "message.part.updated" -> {
            val deltaEvent = parseMessagePartDeltaEvent(event) ?: return
            if (deltaEvent.sessionId == state.value.currentSessionId) {
                val msgId = deltaEvent.messageId
                val pId = deltaEvent.partId
                if (msgId != null && pId != null) {
                    val key = pId
                    val fullText = deltaEvent.text
                    val delta = deltaEvent.delta
                    if (!fullText.isNullOrBlank()) {
                        // Server sent full accumulated text — use it as the
                        // authoritative streaming value (replaces delta
                        // accumulation, acts as a sync point).
                        state.update {
                            it.copy(
                                streamingPartTexts = it.streamingPartTexts + (key to fullText),
                                streamingReasoningPart = reasoningPartOrNull(
                                    partType = deltaEvent.partType,
                                    partId = pId,
                                    messageId = msgId,
                                    sessionId = deltaEvent.sessionId
                                ) ?: it.streamingReasoningPart
                            )
                        }
                    } else if (!delta.isNullOrBlank()) {
                        val previousValue = state.value.streamingPartTexts[key] ?: ""
                        state.update {
                            it.copy(
                                streamingPartTexts = it.streamingPartTexts + (key to (previousValue + delta)),
                                streamingReasoningPart = reasoningPartOrNull(
                                    partType = deltaEvent.partType,
                                    partId = pId,
                                    messageId = msgId,
                                    sessionId = deltaEvent.sessionId
                                ) ?: it.streamingReasoningPart
                            )
                        }
                    }
                    // Else: ids present + both text & delta null/blank =
                    // part status flip. Do NOT clear streaming buffers and
                    // do NOT reload — a foreground catch-up or the next
                    // message.created reconciles if needed.
                } else {
                    // §15.1 (review B3): a true `part.created` (no message/part
                    // id yet) signals the start of a fresh streaming run —
                    // clear any stale partials and reload so we render the
                    // server-authoritative snapshot.
                    state.update {
                        it.copy(streamingPartTexts = emptyMap(), streamingReasoningPart = null)
                    }
                    onRefreshMessages(deltaEvent.sessionId, false)
                }
            }
        }
        "message.part.delta" -> {
            // Web has an independent `message.part.delta` event (distinct from
            // `message.part.updated`): top-level { sessionID, messageID, partID,
            // field, delta }, field-level incremental append. Without this
            // handler, when the server emits this event the client loses all
            // streaming text. Payload shape per opencode-web server-session.ts.
            //
            // Phase 2 scope (docs/architecture-v3-sse-trust.md §246): only
            // accumulate into streamingPartTexts. Field-level in-place updates
            // on the Part object are deferred (needs field→property mapping).
            // Keyed by bare partId (S4: streamingPartTexts is rekeyed from
            // "msgId:partId" to partId, matching the UI consumers).
            val sessionId = event.payload.getString("sessionID") ?: return
            if (sessionId != state.value.currentSessionId) return
            // messageID required for a well-formed delta event (validation guard).
            event.payload.getString("messageID") ?: return
            val partId = event.payload.getString("partID") ?: return
            // `field` defaults to "text"; Phase 2 ignores it (accumulates into
            // the text overlay regardless) — field-specific handling is deferred.
            @Suppress("UNUSED_VARIABLE")
            val field = event.payload.getString("field") ?: "text"
            val delta = event.payload.getString("delta")
            if (!delta.isNullOrEmpty()) {
                val key = partId
                val previous = state.value.streamingPartTexts[key] ?: ""
                state.update {
                    it.copy(streamingPartTexts = it.streamingPartTexts + (key to (previous + delta)))
                }
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
