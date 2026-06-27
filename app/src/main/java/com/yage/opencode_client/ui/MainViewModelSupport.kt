package com.yage.opencode_client.ui

import android.util.Log
import com.yage.opencode_client.data.model.Part
import com.yage.opencode_client.data.model.QuestionRequest
import com.yage.opencode_client.data.model.SSEEvent
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.model.SessionStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

private val lenientJson = Json { ignoreUnknownKeys = true }

internal object MainViewModelTimings {
    const val sessionPageSize = 10
    const val messageRetryDelayMs = 400L
    const val messageRefreshDelayMs = 1200L
    const val busyPollingIntervalMs = 2000L
}

internal data class SessionCreatedEvent(
    val session: Session
)

internal data class SessionStatusEvent(
    val sessionId: String,
    val status: SessionStatus
)

internal data class MessagePartDeltaEvent(
    val sessionId: String,
    val messageId: String?,
    val partId: String?,
    val partType: String,
    val delta: String?
)

internal fun errorMessageOrFallback(throwable: Throwable?, fallback: String): String {
    Log.e("OC_ERROR", "error surfaced to UI", throwable)
    val message = throwable?.message?.trim().orEmpty()
    return if (message.isEmpty()) fallback else message
}

internal fun parseSessionCreatedEvent(event: SSEEvent): SessionCreatedEvent? {
    val sessionJson = event.payload.getJsonObject("session") ?: return null
    return runCatching {
        SessionCreatedEvent(Json.decodeFromString<Session>(sessionJson.toString()))
    }.getOrNull()
}

internal fun parseSessionUpdatedEvent(event: SSEEvent): Session? {
    val sessionJson = event.payload.getJsonObject("info")
        ?: event.payload.getJsonObject("session")
        ?: return null
    return runCatching {
        Json.decodeFromString<Session>(sessionJson.toString())
    }.getOrNull()
}

internal fun upsertSession(sessions: List<Session>, session: Session): List<Session> {
    return listOf(session) + sessions.filter { it.id != session.id }
}

internal fun bumpSessionUpdated(sessions: List<Session>, sessionId: String, updated: Long): List<Session> {
    return sessions.map { session ->
        if (session.id == sessionId) {
            session.copy(time = session.time.withUpdatedAtLeast(updated))
        } else {
            session
        }
    }
}

/**
 * Merge a refreshed server snapshot of sessions ([refreshed]) into the local
 * cached list ([local]) while preserving sessions the user is actively using.
 *
 * Time/title reconciliation (the [base] pass) is unchanged: when the local
 * copy of a session carries a strictly-newer `time.updated` (e.g. it was just
 * bumped by a session.updated SSE that brought the server-authoritative
 * title), prefer the local title and lift the remote time so a concurrent
 * full refresh does not clobber it.
 *
 * The [preserve] pass (#10) appends local-only sessions that the full refresh
 * did not return, but ONLY when they are the currently-open session
 * ([currentSessionId]) or appear in the browser-tab-style open list
 * ([openSessionIds]). This prevents a loadSessions/loadMore refresh from
 * silently evicting the session the user is currently viewing (e.g. a freshly
 * created session that has not yet propagated into the global listing, or a
 * directory-session the user selected from a connected workdir). Sessions
 * outside this allow-list are allowed to drop naturally on refresh.
 */
internal fun mergeRefreshedSessionsPreservingLocalActivity(
    refreshed: List<Session>,
    local: List<Session>,
    currentSessionId: String?,
    openSessionIds: Set<String>
): List<Session> {
    val localById = local.associateBy { it.id }
    val refreshedIds = refreshed.map { it.id }.toSet()
    val base = refreshed.map { remote ->
        val localSession = localById[remote.id]
        val localUpdated = localSession?.time?.updated
        val remoteUpdated = remote.time?.updated
        if (localUpdated != null && (remoteUpdated == null || localUpdated > remoteUpdated)) {
            // The local copy is strictly newer than this refresh response (e.g. it was just
            // upserted from a session.updated SSE event that carries the server-authoritative
            // title). A concurrently-issued full refresh can return a stale snapshot that
            // predates the title generation, so prefer the local title here to avoid clobbering
            // it. The full refresh remains authoritative whenever it is at least as fresh.
            remote.copy(
                title = localSession.title ?: remote.title,
                time = remote.time.withUpdatedAtLeast(localUpdated)
            )
        } else {
            remote
        }
    }
    val preserve = local.filter {
        it.id !in refreshedIds &&
            (it.id == currentSessionId || it.id in openSessionIds)
    }
    return base + preserve
}

private fun Session.TimeInfo?.withUpdatedAtLeast(updated: Long): Session.TimeInfo {
    val currentUpdated = this?.updated
    return (this ?: Session.TimeInfo()).copy(
        updated = if (currentUpdated == null || updated > currentUpdated) updated else currentUpdated
    )
}

internal fun nextSessionFetchLimit(current: Int, pageSize: Int = MainViewModelTimings.sessionPageSize): Int {
    return maxOf(current, pageSize) + maxOf(pageSize, 1)
}

internal fun parseSessionStatusEvent(event: SSEEvent): SessionStatusEvent? {
    val sessionId = event.payload.getString("sessionID") ?: return null
    val statusJson = event.payload.getJsonObject("status") ?: return null
    return runCatching {
        SessionStatusEvent(
            sessionId = sessionId,
            status = Json.decodeFromString<SessionStatus>(statusJson.toString())
        )
    }.getOrNull()
}

internal fun parseMessagePartDeltaEvent(event: SSEEvent): MessagePartDeltaEvent? {
    val sessionId = event.payload.getString("sessionID") ?: return null
    val partObj = event.payload.getJsonObject("part")
    val messageId = (partObj?.get("messageID") as? JsonPrimitive)?.content
    val partId = (partObj?.get("id") as? JsonPrimitive)?.content
    val partType = (partObj?.get("type") as? JsonPrimitive)?.content ?: "text"
    return MessagePartDeltaEvent(
        sessionId = sessionId,
        messageId = messageId,
        partId = partId,
        partType = partType,
        delta = event.payload.getString("delta")
    )
}

internal fun parseQuestionAskedEvent(event: SSEEvent): QuestionRequest? {
    val properties = event.payload.properties ?: return null
    return runCatching {
        lenientJson.decodeFromString<QuestionRequest>(properties.toString())
    }.getOrNull()
}

internal fun reasoningPartOrNull(partType: String, partId: String, messageId: String, sessionId: String): Part? {
    return if (partType == "reasoning") {
        Part(id = partId, messageId = messageId, sessionId = sessionId, type = "reasoning")
    } else {
        null
    }
}

internal fun reportNonFatalIssue(tag: String, message: String, throwable: Throwable? = null) {
    if (throwable != null) {
        Log.w(tag, message, throwable)
    } else {
        Log.w(tag, message)
    }
}

