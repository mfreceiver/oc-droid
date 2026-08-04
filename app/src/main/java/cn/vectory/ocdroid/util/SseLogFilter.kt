package cn.vectory.ocdroid.util

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
 * ## Location rationale (Wave2.2)
 * `util` is a leaf package with no inbound layering constraint, so BOTH the
 * data layer ([cn.vectory.ocdroid.data.api.SSEClient]) and the UI layer
 * (`SessionSyncCoordinator` / `SseEventRouter`) may import it without
 * violating the Wave0 `UiMustNotImportDataApiRule` boundary (which forbids
 * `ui` → `data.api`).
 *
 * History: R-09 first moved this constant out of `ui/ViewModelSupport` into
 * `data/api` to kill a data→ui reverse dependency, but that reintroduced the
 * `ui → data.api` violation at the two UI call sites (SSC + SseEventRouter).
 * Wave2.2 relocates it to `util` so neither direction depends on the other.
 */
internal val NOISY_SSE_LOG_EVENTS: Set<String> = setOf(
    "message.part.delta", "message.part.updated", "server.heartbeat", "server.connected",
    "plugin.added", "catalog.updated", "integration.updated"
)
