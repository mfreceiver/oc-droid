package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.data.model.FileDiff
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.TodoItem
import cn.vectory.ocdroid.service.status.SessionStatusKey
import cn.vectory.ocdroid.service.status.toSessionBusyStatus
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import cn.vectory.ocdroid.ui.controller.SseSideEffect
import cn.vectory.ocdroid.ui.controller.allSessionsById
import cn.vectory.ocdroid.ui.controller.applyPermissionAsked
import cn.vectory.ocdroid.ui.controller.applyQuestionAsked
import cn.vectory.ocdroid.ui.controller.applyQuestionResolved
import cn.vectory.ocdroid.ui.controller.applySessionCreated
import cn.vectory.ocdroid.ui.controller.applySessionDiff
import cn.vectory.ocdroid.ui.controller.applySessionStatus
import cn.vectory.ocdroid.ui.controller.applySessionUpsert
import cn.vectory.ocdroid.ui.controller.applyTodoUpdated
import cn.vectory.ocdroid.ui.controller.parsePermissionAskedEvent
import cn.vectory.ocdroid.ui.parseQuestionAskedEvent
import cn.vectory.ocdroid.ui.lenientJson
import cn.vectory.ocdroid.ui.parseMessagePartDeltaEvent
import cn.vectory.ocdroid.ui.parseSessionCreatedEvent
import cn.vectory.ocdroid.ui.parseSessionStatusEvent
import cn.vectory.ocdroid.ui.parseSessionUpdatedEvent
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * T2 §3.1: handler for legacy-wire [cn.vectory.ocdroid.service.legacy] session
 * lifecycle + permission/question events: `session.created`, `session.updated`,
 * `session.status`, `permission.asked`, `question.asked`,
 * `question.replied`, `question.rejected`, `todo.updated`, `session.diff`.
 *
 * **C3 invariant**: these event types are NEVER on the slim SSE wire; the
 * Router places this handler on the legacy-wire path only.
 */
class LegacySseHandler(private val host: SseDispatchHost) : SseEventHandler {

    override fun supports(type: String): Boolean = type in setOf(
        "session.created",
        "session.updated",
        "session.status",
        "permission.asked",
        "question.asked",
        "question.replied",
        "question.rejected",
        "todo.updated",
        "session.diff",
    )

    override fun handle(event: SSEEvent) {
        when (event.payload.type) {
            "session.created" -> handleSessionCreated(event)
            "session.updated" -> handleSessionUpdated(event)
            "session.status" -> handleSessionStatus(event)
            "permission.asked" -> handlePermissionAsked(event)
            "question.asked" -> handleQuestionAsked(event)
            "question.replied", "question.rejected" -> handleQuestionResolved(event)
            "todo.updated" -> handleTodoUpdated(event)
            "session.diff" -> handleSessionDiff(event)
        }
    }

    // ── session.created (SSC:969) ────────────────────────────────────────────
    private fun handleSessionCreated(event: SSEEvent) {
        val created = parseSessionCreatedEvent(event)
        if (created != null) {
            host.slices.mutateSessionList { s -> s.applySessionCreated(created.session).first }
        } else {
            host.applySseSideEffects(listOf(SseSideEffect.ReportNonFatal("Ignoring invalid session.created payload")))
        }
    }

    // ── session.updated (SSC:981) ────────────────────────────────────────────
    private fun handleSessionUpdated(event: SSEEvent) {
        val updated = parseSessionUpdatedEvent(event)
        if (updated != null) {
            if (updated.isArchived) {
                val newOpenIds = host.slices.sessionList.value.openSessionIds.filter { id -> id != updated.id }
                if (newOpenIds != host.slices.sessionList.value.openSessionIds) {
                    host.settingsManager.openSessionIds = newOpenIds
                }
                host.slices.store.dispatch(
                    AppAction.SessionArchived(
                        session = updated,
                        openSessionIds = newOpenIds,
                    )
                )
                host.effects.tryEmitEffect(
                    ControllerEffect.EvictSession(host.serverGroupFp(), updated.id)
                )
            } else {
                host.slices.mutateSessionList { s -> s.applySessionUpsert(updated).first }
            }
        } else {
            host.applySseSideEffects(listOf(SseSideEffect.ReportNonFatal("Ignoring invalid session.updated payload")))
        }
    }

    // ── session.status (SSC:1035) ────────────────────────────────────────────
    private fun handleSessionStatus(event: SSEEvent) {
        DebugLog.d("Retry", "session.status raw properties=${event.payload.properties?.toString() ?: "null"}")
        val statusEvent = parseSessionStatusEvent(event)
        if (statusEvent != null) {
            val aggregatorInput = host.statusAggregatorInput
            if (aggregatorInput != null) {
                val sessionsByIdNow = allSessionsById(
                    host.slices.sessionList.value.sessions,
                    host.slices.sessionList.value.directorySessions,
                    host.slices.sessionList.value.childSessions,
                )
                val target = sessionsByIdNow[statusEvent.sessionId]
                if (target != null) {
                    val key = SessionStatusKey(
                        serverGroupFp = host.serverGroupFp(),
                        workdir = target.directory,
                        sessionId = statusEvent.sessionId,
                    )
                    aggregatorInput.applySseStatus(
                        key,
                        statusEvent.status.toSessionBusyStatus(),
                        sourceTimeMs = host.sseClock(),
                    )
                }
            }
            // §streaming-state-sync-diag (runtime-gated, scoped+dedup)
            if (cn.vectory.ocdroid.util.DebugLog.verboseDiagEnabled) {
                val diagSid = statusEvent.sessionId
                val diagNewType = statusEvent.status.type
                val diagOldType = host.slices.sessionList.value.sessionStatuses[diagSid]?.type
                if (diagSid == host.slices.chat.value.currentSessionId && diagOldType != diagNewType) {
                    DebugLog.d(
                        "StatusDiag",
                        "session.status write sid=$diagSid oldType=$diagOldType newType=$diagNewType",
                    )
                }
            }
            host.slices.mutateSessionList {
                it.applySessionStatus(statusEvent.sessionId, statusEvent.status).first
            }
            val statusEffects = mutableListOf<SseSideEffect>()
            val chatSnap = host.slices.chat.value
            val isCurrent = statusEvent.sessionId == chatSnap.currentSessionId
            if (statusEvent.status.isBusy && isCurrent) {
                DebugLog.i("Sync", "session.status busy (current) → reload (cross-client message sync)")
                statusEffects.add(SseSideEffect.ReloadMessages(statusEvent.sessionId, resetLimit = true))
            }
            if (statusEvent.status.isIdle && isCurrent) {
                val overlayNonEmpty = chatSnap.streamingPartTexts.isNotEmpty() || chatSnap.streamingReasoningPart != null
                DebugLog.d("Sync", "session.status idle → ${if (overlayNonEmpty) "reload" else "no-op"}")
                if (overlayNonEmpty) {
                    statusEffects.add(SseSideEffect.ReloadMessages(statusEvent.sessionId, resetLimit = true))
                }
            }
            host.applySseSideEffects(statusEffects)
        } else {
            host.applySseSideEffects(listOf(SseSideEffect.ReportNonFatal("Ignoring invalid session.status payload")))
        }
    }

    // ── permission.asked (SSC:1580) ─────────────────────────────────────────
    private fun handlePermissionAsked(event: SSEEvent) {
        val slimPermission = parsePermissionAskedEvent(event)
        if (slimPermission != null && !slimPermission.routeToken.isNullOrBlank()) {
            host.slices.mutateSessionList { s -> s.applyPermissionAsked(slimPermission).first }
        }
        host.applySseSideEffects(listOf(SseSideEffect.LoadPendingPermissions))
    }

    // ── question.asked (SSC:1592) ───────────────────────────────────────────
    private fun handleQuestionAsked(event: SSEEvent) {
        DebugLog.d("Question", "question.asked raw properties=${event.payload.properties?.toString() ?: "null"}")
        val question = parseQuestionAskedEvent(event)
        if (question != null) {
            val duplicate = host.slices.sessionList.value.pendingQuestions.any { it.id == question.id }
            DebugLog.d(
                "Question",
                "applyQuestionAsked id=${question.id} sid=${question.sessionId} " +
                    "routeToken=${!question.routeToken.isNullOrBlank()} duplicate=$duplicate",
            )
            host.slices.mutateSessionList { currentState -> currentState.applyQuestionAsked(question).first }
        } else {
            host.applySseSideEffects(listOf(SseSideEffect.ReportNonFatal("Ignoring invalid question.asked payload")))
        }
    }

    // ── question.replied / question.rejected (SSC:1620) ───────────────────
    private fun handleQuestionResolved(event: SSEEvent) {
        val requestId = event.payload.getString("requestID")
            ?: event.payload.getString("id")
        if (requestId != null) {
            DebugLog.d("Question", "applyQuestionResolved id=$requestId")
            host.slices.mutateSessionList { currentState -> currentState.applyQuestionResolved(requestId).first }
        }
    }

    // ── todo.updated (SSC:1630) ────────────────────────────────────────────
    private fun handleTodoUpdated(event: SSEEvent) {
        val sessionId = event.payload.getString("sessionID") ?: return
        val todosArray = event.payload.properties?.get("todos") as? kotlinx.serialization.json.JsonArray ?: return
        val todos = try {
            Json.decodeFromJsonElement<List<TodoItem>>(todosArray)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            host.applySseSideEffects(listOf(SseSideEffect.ReportNonFatal("Ignoring invalid todo.updated payload: ${e.message}")))
            return
        }
        host.slices.mutateSessionList { s -> s.applyTodoUpdated(sessionId, todos).first }
    }

    // ── session.diff (SSC:1759) ──────────────────────────────────────────────
    private fun handleSessionDiff(event: SSEEvent) {
        val sessionId = event.payload.getString("sessionID") ?: return
        val diffArray = event.payload.properties?.get("diff") as? kotlinx.serialization.json.JsonArray ?: return
        val diffs = try {
            lenientJson.decodeFromJsonElement<List<FileDiff>>(diffArray)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            host.applySseSideEffects(listOf(SseSideEffect.ReportNonFatal("Ignoring invalid session.diff payload: ${e.message}")))
            return
        }
        host.slices.mutateSessionList { it.applySessionDiff(sessionId, diffs).first }
    }
}
