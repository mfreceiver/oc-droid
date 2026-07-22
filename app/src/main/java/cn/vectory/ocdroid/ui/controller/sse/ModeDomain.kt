package cn.vectory.ocdroid.ui.controller.sse

/**
 * T2 §3.1: sealed identity for the two SSE event domains.
 * Legacy-wire ([cn.vectory.ocdroid.service.legacy.LegacyWired]) and
 * Slim-wire ([cn.vectory.ocdroid.service.slimapi.SlimWired]) are the two
 * runtime modes; each mode selects which [SseEventHandler]s the router
 * delegates to.
 */
sealed interface ModeDomain {
    /** Legacy `/global/event` SSE feed. */
    data object Legacy : ModeDomain
    /** Slim `/slimapi/events` SSE feed. */
    data object Slim : ModeDomain
}
