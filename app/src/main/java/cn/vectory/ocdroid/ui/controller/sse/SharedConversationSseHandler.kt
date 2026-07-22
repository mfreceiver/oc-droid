package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.BuildConfig
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import cn.vectory.ocdroid.ui.controller.SseSideEffect
import cn.vectory.ocdroid.ui.controller.applyMessageTimestampBump
import cn.vectory.ocdroid.ui.isStreamablePartType
import cn.vectory.ocdroid.ui.lenientJson
import cn.vectory.ocdroid.ui.parseMessagePartDeltaEvent
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.FLICKER_TAG
import cn.vectory.ocdroid.util.STREAMING_FLICKER_DEBUG
import android.util.Log
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * T2 §3.1: handler for legacy-wire [cn.vectory.ocdroid.service.legacy] conversation
 * SSE events: `message.created`, `message.updated`, `message.part.updated`,
 * `message.part.delta`. These carry the token-streaming + patch/insert
 * message lifecycle for the legacy `/global/event` SSE feed.
 *
 * **C3 invariant**: these event types are NEVER on the slim SSE wire; the
 * Router places this handler on the legacy-wire path only.
 */
class SharedConversationSseHandler(private val host: SseDispatchHost) : SseEventHandler {

    private val supportedTypes = setOf(
        "message.created",
        "message.updated",
        "message.part.updated",
        "message.part.delta",
    )

    override fun supports(type: String): Boolean = type in supportedTypes

    override fun handle(event: SSEEvent) {
        when (event.payload.type) {
            "message.created" -> handleMessageCreated(event)
            "message.updated" -> handleMessageUpdated(event)
            "message.part.updated" -> handleMessagePartUpdated(event)
            "message.part.delta" -> handleMessagePartDelta(event)
        }
    }

    // ── message.created (SSC:1170) ──────────────────────────────────────────
    private fun handleMessageCreated(event: SSEEvent) {
        val sessionId = event.payload.getString("sessionID")
        val isCurrent = sessionId != null && sessionId == host.slices.chat.value.currentSessionId
        DebugLog.i("Sync", "message.created: ${if (isCurrent) "reload current" else "no-op (unread is lifecycle-driven)"}")

        // §recent-sort-by-message: forward-compat parity
        if (sessionId != null) {
            val createdInfo = event.payload.getJsonObject("info")?.let {
                runCatching {
                    lenientJson.decodeFromJsonElement<Message>(it)
                }.getOrNull()
            }
            val msgCreated = createdInfo?.time?.created ?: 0L
            if (msgCreated > 0L) {
                host.slices.mutateSessionList { s ->
                    s.applyMessageTimestampBump(sessionId, msgCreated).first
                }
            }
        }

        val msgEffects = mutableListOf<SseSideEffect>()
        if (sessionId != null && sessionId == host.slices.chat.value.currentSessionId) {
            msgEffects.add(SseSideEffect.ReloadMessages(sessionId, resetLimit = true))
        }
        host.applySseSideEffects(msgEffects)
    }

    // ── message.updated (SSC:1214) ──────────────────────────────────────────
    private fun handleMessageUpdated(event: SSEEvent) {
        val eventSessionId = event.payload.getString("sessionID")
        val infoJson = event.payload.getJsonObject("info")
        val updated = infoJson?.let {
            runCatching {
                lenientJson.decodeFromJsonElement<Message>(it)
            }.getOrNull()
        }

        // §recent-sort-by-message: bump owning session's time.updated
        if (eventSessionId != null && updated != null) {
            val msgCreated = updated.time?.created ?: 0L
            if (msgCreated > 0L) {
                host.slices.mutateSessionList { s ->
                    s.applyMessageTimestampBump(eventSessionId, msgCreated).first
                }
            }
        }

        // Defensive session guard: only touch the current session's chat view.
        if (eventSessionId != null && eventSessionId != host.slices.chat.value.currentSessionId) return
        if (updated != null && updated.id.isNotEmpty()) {
            val found = host.slices.chat.value.messages.any { it.id == updated.id }
            host.slices.store.dispatch(
                AppAction.MessageUpdatedApplied(updated)
            )
            if (found) {
                DebugLog.d("Sync", "message.updated: patched")
            } else {
                DebugLog.i("Sync", "message.updated: inserted (new message, absent from local list)")
                host.effects.tryEmitEffect(
                    ControllerEffect.AppendMessageToCache(
                        serverGroupFp = host.serverGroupFp(),
                        sessionId = eventSessionId!!,
                        message = updated,
                        parts = emptyList(),
                    )
                )
            }
        }
    }

    // ── message.part.updated (SSC:1322) ─────────────────────────────────────
    private fun handleMessagePartUpdated(event: SSEEvent) {
        val deltaEvent = parseMessagePartDeltaEvent(event) ?: return
        if (deltaEvent.sessionId == host.slices.chat.value.currentSessionId) {
            val msgId = deltaEvent.messageId
            val pId = deltaEvent.partId
            if (msgId != null && pId != null) {
                val key = pId
                // §user-part-guard: skip user message parts
                val ownerIsUser = host.slices.chat.value.messages.any { it.id == msgId && it.isUser }
                if (ownerIsUser) return
                val fullText = deltaEvent.text
                val delta = deltaEvent.delta
                // §reasoning-routing-fix
                val pType = deltaEvent.partType
                if (isStreamablePartType(pType)) {
                    val existingParts = host.slices.chat.value.partsByMessage[msgId]
                    val hasCorrectType = existingParts?.any { it.id == pId && it.type == pType } == true
                    if (!hasCorrectType) {
                        host.slices.store.dispatch(
                            AppAction.PartPlaceholderEnsured(
                                partType = pType,
                                partId = pId,
                                messageId = msgId,
                                sessionId = deltaEvent.sessionId,
                            )
                        )
                        if (STREAMING_FLICKER_DEBUG) {
                            val inStreamingTexts = key in host.slices.chat.value.streamingPartTexts
                            Log.w(
                                FLICKER_TAG,
                                "placeholder created partId=$key msgId=$msgId inStreamingTexts=$inStreamingTexts"
                            )
                        }
                    }
                }
                if (!fullText.isNullOrBlank()) {
                    if (!host.isFlushActiveForPart(key)) {
                        // Leading edge fullText
                        host.slices.store.dispatch(
                            AppAction.PartFullTextReceived(
                                partId = key,
                                fullText = fullText,
                                partType = deltaEvent.partType,
                                messageId = msgId,
                                sessionId = deltaEvent.sessionId,
                            )
                        )
                        host.scheduleDeltaFlush(key)
                        if (STREAMING_FLICKER_DEBUG) {
                            Log.w(FLICKER_TAG, "first fullText staged partId=$key msgId=$msgId")
                        }
                    } else {
                        // Trailing coalesce: buffer
                        host.slices.store.dispatch(
                            AppAction.FullTextBuffered(key, fullText)
                        )
                    }
                } else if (!delta.isNullOrBlank()) {
                    if (!host.isFlushActiveForPart(key)) {
                        // Leading edge delta
                        host.slices.store.dispatch(
                            AppAction.PartDeltaReceived(
                                partId = key,
                                delta = delta,
                                partType = deltaEvent.partType,
                                messageId = msgId,
                                sessionId = deltaEvent.sessionId,
                            )
                        )
                        host.scheduleDeltaFlush(key)
                        if (STREAMING_FLICKER_DEBUG) {
                            Log.w(FLICKER_TAG, "first delta staged partId=$key msgId=$msgId")
                        }
                    } else {
                        // Trailing coalesce: buffer
                        host.slices.store.dispatch(
                            AppAction.DeltaBuffered(key, delta)
                        )
                    }
                }
                // Else: part status flip - do nothing
            } else {
                // part.created (part object with type only, no messageID/id)
                host.clearDeltaBuffers()
                host.applySseSideEffects(listOf(
                    SseSideEffect.ReloadMessages(deltaEvent.sessionId, resetLimit = false)
                ))
            }
        }
    }

    // ── message.part.delta (SSC:1511) ───────────────────────────────────────
    private fun handleMessagePartDelta(event: SSEEvent) {
        val sessionId = event.payload.getString("sessionID") ?: return
        if (sessionId != host.slices.chat.value.currentSessionId) return
        val msgId = event.payload.getString("messageID") ?: return
        val partId = event.payload.getString("partID") ?: return
        // §user-part-guard: only assistant output streams
        if (host.slices.chat.value.messages.any { it.id == msgId && it.isUser }) return
        val field = event.payload.getString("field") ?: "text"
        val delta = event.payload.getString("delta")
        if (!delta.isNullOrEmpty()) {
            val key = partId
            val knownType = host.slices.chat.value.partsByMessage[msgId]
                ?.firstOrNull { it.id == partId }?.type ?: field
            if (!isStreamablePartType(knownType)) return
            if (!host.isFlushActiveForPart(key)) {
                // Leading edge
                host.slices.store.dispatch(
                    AppAction.PartDeltaReceived(
                        partId = key,
                        delta = delta,
                        partType = knownType,
                        messageId = msgId,
                        sessionId = sessionId,
                    )
                )
                host.scheduleDeltaFlush(key)
            } else {
                // Trailing coalesce
                host.slices.store.dispatch(
                    AppAction.DeltaBuffered(key, delta)
                )
            }
        }
    }
}
