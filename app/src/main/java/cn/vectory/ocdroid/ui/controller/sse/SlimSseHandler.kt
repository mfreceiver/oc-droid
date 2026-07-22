package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.BuildConfig
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SlimSessionLastError
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.controller.SseSideEffect
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * T2 §3.1: handler for slim-wire [cn.vectory.ocdroid.service.slimapi] events:
 * `session.digest` and `session.error`. These carry the content-update +
 * error surfaces for the slim `/slimapi/events` SSE feed.
 *
 * **C3 invariant**: these event types are NEVER on the legacy SSE wire; the
 * Router places this handler on the slim-wire path only.
 */
class SlimSseHandler(private val host: SseDispatchHost) : SseEventHandler {

    override fun supports(type: String): Boolean = type in setOf(
        "session.digest",
        "session.error",
    )

    override fun handle(event: SSEEvent) {
        when (event.payload.type) {
            "session.digest" -> host.handleSessionDigest(event)
            "session.error" -> handleSessionError(event)
        }
    }

    // ── session.error (SSC:1643) ────────────────────────────────────────────
    private fun handleSessionError(event: SSEEvent) {
        // §Phase1a instrumentation (Issue 4): capture the FULL raw properties
        DebugLog.w("Retry", "session.error raw properties=${event.payload.properties?.toString() ?: "null"}")
        val props = event.payload.properties
        val errObj = props?.get("error") as? JsonObject
        // name: top-level first, fall back to nested error.name.
        val name = (props?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: (errObj?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.content
        // data: nested-only
        val data = errObj?.get("data") as? JsonObject
        // message: top-level first, then nested, then fallback
        val rawMsg = (props?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: (data?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: (data?.get("error") as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: (errObj?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: (errObj?.get("error") as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: "Server session error"
        // at: top-level first, fall back to nested error.at.
        val at = (props?.get("at") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
            ?: (errObj?.get("at") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
        host.applySseSideEffects(listOf(SseSideEffect.SessionError(name = name, rawMsg = rawMsg)))
        val sid = event.payload.getString("sessionID")
            ?: (props?.get("sessionID") as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (sid != null && sid == host.slices.chat.value.currentSessionId) {
            host.slices.store.dispatch(
                AppAction.LastAssistantErrorAttached(
                    Message.MessageError(name = name, data = data),
                )
            )
        }
        // T12-C1 (slim-only, sid-required): durable banner
        if (sid != null && host.slimMode()) {
            val banner = SlimSessionLastError(
                name = name ?: "Unknown",
                message = rawMsg,
                at = at,
            )
            host.scope.launch {
                host.stripeFor(sid).withLock {
                    host.slices.mutateSessionList { s ->
                        s.copy(sessionErrorsById = s.sessionErrorsById + (sid to banner))
                    }
                }
            }
        }
    }
}
