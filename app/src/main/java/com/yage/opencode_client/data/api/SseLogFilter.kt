package com.yage.opencode_client.data.api

/**
 * SSE event types that are pure noise in the debug log (high-frequency or
 * server-internal). Logging them floods the ring buffer and evicts signal.
 *
 * NOTE: this affects ONLY logging — event handling/dispatch is unchanged.
 *
 * R-09 (分层下沉): 此常量原定义在 `ui/MainViewModelSupport.kt`，被 data 层的
 * [com.yage.opencode_client.data.api.SSEClient] 反向 import，违反分层。现下沉到
 * data/api 包，UI 层（`MainViewModelSyncActions`）改为正向 import data 层。
 */
internal val NOISY_SSE_LOG_EVENTS: Set<String> = setOf(
    "message.part.delta", "server.heartbeat", "server.connected",
    "plugin.added", "catalog.updated", "integration.updated"
)
