package cn.vectory.ocdroid.service.identity

/**
 * Process-level connection identity — the single source of truth for the SSE collector and
 * the directory-fetch guard.
 *
 * See FGS spec §2 «连接身份与原子 reconfigure 协议»: `epoch` is bumped on every reconfigure
 * (host switch / workdir / endpoint change) and is the **single** generation counter that
 * guards **both**:
 *  1. the SSE collector (stale frames whose epoch != current must be dropped before any side
 *     effect — see [cn.vectory.ocdroid.service.events.IdentifiedSseEvent] and `SseEventBridge`);
 *  2. the directory fan-out / `loadInitialData` fetch (so that the controller cannot run a
 *     private second generation that drifts apart from the SSE epoch — FGS spec §2 «关键约束»).
 *
 * Reconfigure protocol order (FGS spec §2, strictly ordered, must not be reordered):
 *  1. increment `epoch`;
 *  2. invalidate the old SSE collector;
 *  3. rebuild repository / OkHttp client (host / profile / workdir);
 *  4. isolate / purge stale host state;
 *  5. bind a new collector to the new identity;
 *  6. publish every event carrying the new identity; identity is validated by the bridge,
 *     the status aggregator, and `SessionSyncCoordinator.fold` **before** any side effect.
 *
 * This type is a pure data carrier (Phase 0 shared contract). It holds no behavior and is not
 * wired through Hilt here — owning components bump and read it at their respective layers.
 *
 * @property epoch Monotonic generation counter bumped on every reconfigure. The single guard
 *  for both the SSE collector and the directory-fetch fan-out.
 * @property serverGroupFp Fingerprint of the server group (profile/host group) this connection
 *  belongs to — part of the composite identity that survives across reconnections within the
 *  same group but changes on group switch.
 * @property normalizedWorkdir The normalized working directory this SSE stream is bound to.
 * @property endpointFp Fingerprint of the concrete endpoint (host:port + tunnel) being dialed;
 *  distinguishes the connection within a server group.
 */
data class ConnectionIdentity(
    val epoch: Long,
    val serverGroupFp: String,
    val normalizedWorkdir: String,
    val endpointFp: String,
)
