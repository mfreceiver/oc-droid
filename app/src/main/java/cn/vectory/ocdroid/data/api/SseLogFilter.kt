package cn.vectory.ocdroid.data.api

/**
 * SSE event types that are pure noise in the debug log (high-frequency or
 * server-internal). Logging them floods the ring buffer and evicts signal.
 *
 * `message.part.updated` is the per-token streaming event in this client
 * (the server emits it dozens–100s/sec during AI output), so it is treated
 * as noise here alongside `message.part.delta`.
 *
 * NOTE: this affects ONLY logging — event handling/dispatch is unchanged.
 *
 * R-09 (分层下沉): 此常量原定义在 `ui/ViewModelSupport.kt`，被 data 层的
 * [cn.vectory.ocdroid.data.api.SSEClient] 反向 import，违反分层。现下沉到
 * data/api 包，UI 层（`SessionSyncCoordinator`）改为正向 import data 层。
 */
internal val NOISY_SSE_LOG_EVENTS: Set<String> = setOf(
    "message.part.delta", "message.part.updated", "server.heartbeat", "server.connected",
    "plugin.added", "catalog.updated", "integration.updated"
)
