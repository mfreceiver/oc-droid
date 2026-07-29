package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.service.OwnershipStartResult
import cn.vectory.ocdroid.service.identity.ConnectionIdentity

// ── Drop handler (I3 ordering: ownership released → Dropped published) ─────

/**
 * Guarantees the observable ordering: ownership released → Dropped published.
 * The implementer calls [SseTransportRuntimeStore.publishDropped] only after
 * releasing ownership.
 */
interface UnexpectedTransportDropHandler {
    fun onUnexpectedDrop(
        attempt: TransportAttemptToken,
        reason: TransportDropReason,
    )
}

// ── Foreground preparation ─────────────────────────────────────────────────

enum class ForegroundTransportStartReason {
    DROPPED_TRANSPORT,
    HEALTH_CONFIRMED,
}

sealed interface ForegroundTransportStartPreparation {
    data object Ready : ForegroundTransportStartPreparation
    data object SupersededIdentity : ForegroundTransportStartPreparation
    data object NotEligible : ForegroundTransportStartPreparation
}

interface ForegroundTransportStartPreparer {
    suspend fun prepareForegroundTransportStart(
        identity: ConnectionIdentity,
        dropId: Long?,
        reason: ForegroundTransportStartReason,
    ): ForegroundTransportStartPreparation
}

// ── Reconnect supervisor ───────────────────────────────────────────────────

interface SseReconnectSupervisor {
    fun start()
    fun requestReconcile()

    suspend fun ensureConnected(
        identity: ConnectionIdentity,
        trigger: SseReconnectTrigger,
    ): OwnershipStartResult
}

enum class SseReconnectTrigger {
    DROPPED_TRANSPORT,
    HEALTH_CONFIRMED,
    EXPLICIT_RECONCILE,
}
