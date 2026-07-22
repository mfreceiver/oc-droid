package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.data.model.SSEEvent

/**
 * T2 §3.1: per-handler interface. Each handler declares the SSE event types
 * it [supports] and implements the fold/effect in [handle].
 */
interface SseEventHandler {
    /**
     * Returns `true` when [eventType] is owned by this handler.
     * Called by [SseEventRouter] to select the first matching handler.
     */
    fun supports(type: String): Boolean

    /**
     * Folds the [event] into the shared slice state and routes any
     * cross-domain side effects through the [host]. Throws nothing —
     * all parse/validation failures return early (no-op) via the host's
     * [SseDispatchHost.applySseSideEffects] with [SseSideEffect.ReportNonFatal].
     */
    fun handle(event: SSEEvent)
}
