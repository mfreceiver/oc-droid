package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.BuildConfig
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import cn.vectory.ocdroid.ui.controller.SseSideEffect
import cn.vectory.ocdroid.ui.controller.applyMessageTimestampBump
import cn.vectory.ocdroid.ui.hasActiveTokenStreamOwner
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

        val createdInfo = sessionId?.let {
            event.payload.getJsonObject("info")?.let { info ->
                runCatching {
                    lenientJson.decodeFromJsonElement<Message>(info)
                }.getOrNull()
            }
        }

        // §recent-sort-by-message: forward-compat parity
        if (sessionId != null) {
            val msgCreated = createdInfo?.time?.created ?: 0L
            if (msgCreated > 0L) {
                host.slices.mutateSessionList { s ->
                    s.applyMessageTimestampBump(sessionId, msgCreated).first
                }
            }
        }

        // lite-v2-dev (🔴-5 注入点 2)：用户发送后服务器确认 message.created →
        // 在触发 reload 前标记该消息为本地注入，消除 skeleton 打标时序窗口。
        if (sessionId != null && createdInfo != null && createdInfo.id.isNotEmpty()) {
            val found = host.slices.chat.value.messages.any { it.id == createdInfo.id }
            if (!found) {
                host.markLocallyInjected(sessionId, createdInfo.id)
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
            val sessionId = eventSessionId ?: return
            val routeInstance = sessionId.let(host.slices::routeInstanceFor)
            val found = host.slices.chat.value.messages.any { it.id == updated.id }
            if (!found) {
                // 只对新消息打标：已在本地列表中的消息不再视为「本地注入」，
                // 避免 skeleton 在 reload 时误判为服务器删除。
                host.markLocallyInjected(sessionId, updated.id)
            }
            host.slices.store.dispatch(
                AppAction.MessageUpdatedApplied(
                    message = updated,
                    expectedRouteInstance = routeInstance,
                    sessionId = sessionId,
                )
            )
            if (found) {
                DebugLog.d("Sync", "message.updated: patched")
            } else {
                DebugLog.i("Sync", "message.updated: inserted (new message, absent from local list)")
                host.effects.tryEmitEffect(
                    ControllerEffect.AppendMessageToCache(
                        serverGroupFp = host.serverGroupFp(),
                        sessionId = sessionId,
                        message = updated,
                        parts = emptyList(),
                    )
                )
            }
        }
    }

    // ── message.part.updated (SSC:1322) ─────────────────────────────────────
    private fun handleMessagePartUpdated(event: SSEEvent) {
        // §Stage-B §3.10 (opus SF-1): single-owner guard. When a token
        // stream owns animated parts (streamOwned has a STREAMING entry),
        // the legacy dual-write path must NOT touch the animated overlay —
        // the token stream is the single owner. Early-return BEFORE any
        // state mutation. (Legacy non-token-stream users have an empty
        // streamOwned → guard never trips → byte-for-byte unchanged.)
        if (host.slices.chat.value.hasActiveTokenStreamOwner()) return
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
                val routeInstance = host.slices.routeInstanceFor(deltaEvent.sessionId)
                // §reasoning-routing-fix
                val pType = deltaEvent.partType
                if (isStreamablePartType(pType)) {
                    val existingParts = host.slices.chat.value.partsByMessage[msgId]
                    val hasCorrectType = existingParts?.any { it.id == pId && it.type == pType } == true
                    if (!hasCorrectType) {
                        host.dispatchBundleBound { stamp ->
                            AppAction.PartPlaceholderEnsured(
                                partType = pType,
                                partId = pId,
                                messageId = msgId,
                                sessionId = deltaEvent.sessionId,
                                expectedRouteInstance = routeInstance,
                                bundleStamp = stamp,
                            )
                        }
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
                        host.dispatchBundleBound { stamp ->
                            AppAction.PartFullTextReceived(
                                partId = key,
                                fullText = fullText,
                                partType = deltaEvent.partType,
                                messageId = msgId,
                                sessionId = deltaEvent.sessionId,
                                expectedRouteInstance = routeInstance,
                                bundleStamp = stamp,
                            )
                        }
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
                        // §B2 rev-gpt #1: propagate the captured route token so
                        // the parameterized chat/{sessionId} route's
                        // LoadedContent mirror stays in sync (mirrors
                        // handleMessagePartDelta / bridgePartToChatState).
                        // Omitting it defaulted expectedRouteInstance=0L and
                        // left the route-owned slot stale.
                        host.dispatchBundleBound { stamp ->
                            AppAction.PartDeltaReceived(
                                partId = key,
                                delta = delta,
                                partType = deltaEvent.partType,
                                messageId = msgId,
                                sessionId = deltaEvent.sessionId,
                                expectedRouteInstance = routeInstance,
                                bundleStamp = stamp,
                            )
                        }
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
        // §Stage-B §3.10 (opus SF-1): single-owner guard — see
        // handleMessagePartUpdated. Early-return before any mutation.
        if (host.slices.chat.value.hasActiveTokenStreamOwner()) return
        val sessionId = event.payload.getString("sessionID") ?: return
        if (sessionId != host.slices.chat.value.currentSessionId) return
        val msgId = event.payload.getString("messageID") ?: return
        val partId = event.payload.getString("partID") ?: return
        // §user-part-guard: only assistant output streams
        if (host.slices.chat.value.messages.any { it.id == msgId && it.isUser }) return
        val field = event.payload.getString("field") ?: "text"
        val delta = event.payload.getString("delta")
        val routeInstance = host.slices.routeInstanceFor(sessionId)
        if (!delta.isNullOrEmpty()) {
            val key = partId
            val knownType = host.slices.chat.value.partsByMessage[msgId]
                ?.firstOrNull { it.id == partId }?.type ?: field
            if (!isStreamablePartType(knownType)) return
            if (!host.isFlushActiveForPart(key)) {
                // Leading edge
                host.dispatchBundleBound { stamp ->
                    AppAction.PartDeltaReceived(
                        partId = key,
                        delta = delta,
                        partType = knownType,
                        messageId = msgId,
                        sessionId = sessionId,
                        expectedRouteInstance = routeInstance,
                        bundleStamp = stamp,
                    )
                }
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
