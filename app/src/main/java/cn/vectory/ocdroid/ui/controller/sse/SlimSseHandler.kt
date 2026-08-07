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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * T2 §3.1: handler for slim-wire [cn.vectory.ocdroid.service.slimapi] events:
 * `session.digest` and `session.error`. These carry the content-update +
 * error surfaces for the slim `/slimapi/events` SSE feed.
 *
 * **C3 invariant**: these event types are NEVER on the legacy SSE wire; the
 * Router places this handler on the slim-wire path only.
 */
class SlimSseHandler(private val host: SseDispatchHost) : SseEventHandler {

    private val supportedTypes = setOf(
        "session.digest",
        "session.error",
    )

    override fun supports(type: String): Boolean = type in supportedTypes

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
        val name = (props?.get("name") as? JsonPrimitive)?.content
            ?: (errObj?.get("name") as? JsonPrimitive)?.content
        // V2 §3:95: abort (MessageAbortedError) is silently discarded by the
        // sidecar. Defensive client-side guard: if it leaks through, do NOT
        // produce an error surface (no SessionError effect, no
        // LastAssistantError, no durable banner).
        if (name == "MessageAbortedError") return

        // data: nested-only
        val data = errObj?.get("data") as? JsonObject
        // realMsg: fallback chain WITHOUT the final "Server session error"
        // — used for injecting into LastAssistantErrorAttached so the
        // MessageError.message getter can resolve it.
        val realMsg = (props?.get("message") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: (data?.get("message") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: (data?.get("error") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: (errObj?.get("message") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: (errObj?.get("error") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        // rawMsg: same chain with final fallback, for snackbar/banner display
        val rawMsg = realMsg ?: "Server session error"
        // at: top-level first, fall back to nested error.at.
        val at = (props?.get("at") as? JsonPrimitive)?.content?.toLongOrNull()
            ?: (errObj?.get("at") as? JsonPrimitive)?.content?.toLongOrNull()
        // Inject realMsg into error data so MessageError.message getter can
        // resolve it, without polluting with the "Server session error" fallback.
        val mergedData = if (realMsg != null) {
            buildJsonObject {
                data?.forEach { (k, v) -> put(k, v) }
                put("message", JsonPrimitive(realMsg))
            }
        } else {
            data
        }
        host.applySseSideEffects(listOf(SseSideEffect.SessionError(name = name, rawMsg = rawMsg)))
        val sid = event.payload.getString("sessionID")
            ?: (props?.get("sessionID") as? JsonPrimitive)?.content
        if (sid != null && sid == host.slices.chat.value.currentSessionId) {
            host.slices.store.dispatch(
                AppAction.LastAssistantErrorAttached(
                    error = Message.MessageError(name = name, data = mergedData),
                    expectedRouteInstance = host.slices.routeInstanceFor(sid),
                    sessionId = sid,
                )
            )
        }
        // T12-C1 (slim-only, sid-required): durable banner
        if (sid != null && host.supportsDurableSessionErrorBanner()) {
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
