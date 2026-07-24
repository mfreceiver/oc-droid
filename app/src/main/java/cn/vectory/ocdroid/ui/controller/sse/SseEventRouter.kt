package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.BuildConfig
import cn.vectory.ocdroid.data.api.NOISY_SSE_LOG_EVENTS
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.util.DebugLog

/**
 * T2 §3.1: the SINGLE event routing point. Holds the three per-domain
 * handlers and applies the routing policy:
 *
 *  1. Iterate [shared, legacy, slim] in order.
 *  2. For each handler, call [SseEventHandler.supports(type)].
 *  3. First match wins → call [SseEventHandler.handle(event)].
 *  4. If no match:
 *      - Explicit no-op types (sync / models-dev.refreshed / session.idle /
 *        file.watcher.updated / file.edited / command.executed) → return
 *        silently (no counter bump, no warning).
 *      - Else (unrecognized): bump the unknown-event counter via
 *        [host.bumpUnknownEventCounter] and, for non-noisy types, emit a
 *        warning + debug UI event.
 */
class SseEventRouter(
    private val shared: SharedConversationSseHandler,
    private val legacy: LegacySseHandler,
    private val slim: SlimSseHandler,
) {
    /**
     * The three per-domain handlers in priority order. Allocated once at
     * construction (not per-[route] frame) to avoid unnecessary list
     * allocation on every event.
     */
    private val handlers = listOf(shared, legacy, slim)

    /**
     * The set of event types that are explicitly recognized but require no
     * processing. These are returned early from [route] so they don't fall
     * through to the unrecognized counter.
     */
    private val explicitNoOps = setOf(
        "sync",
        "models-dev.refreshed",
        "session.idle",
        "file.watcher.updated",
        "file.edited",
        "command.executed",
    )

    /**
     * Routes a single SSE event to the appropriate handler.
     *
     * @param event the SSE event to dispatch.
     * @param host the [SseDispatchHost] used by the unrecognized fallback
     *   (for counter bump + warning emission).
     */
    fun route(event: SSEEvent, host: SseDispatchHost) {
        val type = event.payload.type

        // Explicit no-ops: recognized but silently ignored.
        if (type in explicitNoOps) return

        // Find the first handler that supports this type.
        val handler = handlers.firstOrNull { it.supports(type) }
        if (handler != null) {
            handler.handle(event)
            return
        }

        // ── else (unrecognized) ─────────────────────────────────────────────
        // §R18 Phase 3 Wave 1 (P0-7): surface unrecognized SSE event types.
        // NOISY_SSE_LOG_EVENTS skip the warning but still bump the counter.
        val evtSession = event.payload.getString("sessionID") ?: "-"
        val noisy = type in NOISY_SSE_LOG_EVENTS
        if (!noisy) {
            val keys = event.payload.properties?.keys?.take(5) ?: emptyList()
            DebugLog.w("SSE", "unrecognized event type=$type session=$evtSession payload-keys=$keys")
            if (BuildConfig.DEBUG) {
                host.effects.tryEmitUiEvent(UiEvent.Debug("unknown SSE: $type"))
            }
        }
        host.bumpUnknownEventCounter(type)
    }
}
