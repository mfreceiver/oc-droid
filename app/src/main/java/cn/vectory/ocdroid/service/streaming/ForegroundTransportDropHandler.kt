package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.service.StreamingOwnershipGate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * L1 FGS commit 1: the foreground drop handler — relocates the
 * [FencedUnexpectedTransportDropHandler] body from
 * [cn.vectory.ocdroid.service.SessionStreamingService.SseShutdownSeal].
 *
 * I3 ordering: releases ownership BEFORE publishing Dropped, so consumers
 * never observe `Dropped + Ready` for the same identity. Both operations
 * are idempotent — a second call is a safe no-op.
 *
 * The disposition half ([markIntentional] / [rearmUnexpected] /
 * [applyDestructionDisposition]) stays with the Service and dies in Commit 2.
 */
@Singleton
class ForegroundTransportDropHandler @Inject constructor(
    private val runtimeStore: SseTransportRuntimeStore,
    private val ownershipGate: StreamingOwnershipGate,
) : FencedUnexpectedTransportDropHandler {

    override fun onUnexpectedDrop(attempt: TransportAttemptToken, reason: TransportDropReason) {
        onUnexpectedDropIfCurrent(attempt, reason)
    }

    /**
     * Atomic fence for the release-before-publish contract. Owner supersession
     * uses the same monitor, so an old exhaustion callback cannot release a
     * newer Ready owner for the same identity.
     */
    override fun onUnexpectedDropIfCurrent(
        attempt: TransportAttemptToken,
        reason: TransportDropReason,
    ): Boolean = synchronized(this) {
        val current = runtimeStore.currentAttempt(attempt.identity)
        if (current?.attemptId != attempt.attemptId) return@synchronized false
        ownershipGate.releaseNow(attempt.identity)
        runtimeStore.publishDropped(attempt, reason) != null
    }
}
