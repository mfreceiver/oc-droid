package cn.vectory.ocdroid.service

import cn.vectory.ocdroid.service.identity.ConnectionIdentity

/**
 * L2 §3: a lease token returned by [StreamingOwnershipGate.claim] and
 * consumed by [StreamingOwnershipGate.releaseNow]. The [leaseId] is
 * monotonic across the gate's lifetime (no ABA). Identity comparison,
 * not referential equality, drives release logic.
 */
data class LeaseToken(val leaseId: Long, val identity: ConnectionIdentity)

/**
 * D4-B B1 — the result the [StreamingServiceLauncher] returns to
 * [cn.vectory.ocdroid.ui.controller.ConnectionCoordinator]. The launcher
 * completes with [Ready] ONLY after the Service has proven transport
 * readiness, so CC publishes
 * [cn.vectory.ocdroid.ui.ConnectionPhase.Connected] exclusively on
 * a verified transport.
 *
 * [Refused] carries the rejection reason as an [OwnershipRefusal].
 */
sealed interface OwnershipStartResult {
    /**
     * Ownership reached Stage 2 (Ready) — the SSE transport delivered
     * a valid current-identity frame and the coordinator committed the
     * handoff. CC writes Connected.
     */
    data class Ready(val identity: ConnectionIdentity) : OwnershipStartResult

    /** Ownership was refused — the bootstrap was rejected or exhausted. */
    data class Refused(val reason: OwnershipRefusal) : OwnershipStartResult
}

/**
 * Reasons why ownership was refused. Each variant corresponds to a
 * distinct failure scenario that [ConnectionCoordinator] can surface
 * as an appropriate [cn.vectory.ocdroid.ui.ConnectionPhase].
 */
sealed interface OwnershipRefusal {
    /** App in background — bootstrap suppressed. */
    data object Background : OwnershipRefusal

    /** Launcher's Starting-acceptance window expired. */
    data object AckTimeout : OwnershipRefusal

    /** The identity is no longer current (reconfigure epoch advanced). */
    data object StaleIdentity : OwnershipRefusal

    /** Another identity already holds ownership. */
    data class AlreadyOwned(val identity: ConnectionIdentity) : OwnershipRefusal

    /** Platform (e.g. Android Service binding) rejected the launch. */
    data class PlatformRejected(val error: Throwable) : OwnershipRefusal

    /** Service was stopped while ownership was pending. */
    data object ServiceStopped : OwnershipRefusal

    /** Bootstrap exhausted / transport rejected or timed out. */
    data object BootstrapFailed : OwnershipRefusal

    /**
     * The client's DEBUG `sse_disabled` flag is ON — the launcher
     * refused to start the SSE foreground service (REST-only degraded
     * mode). Surfaced as
     * [cn.vectory.ocdroid.ui.ConnectionPhase.SseDisabled].
     */
    data object SseDisabled : OwnershipRefusal
}
