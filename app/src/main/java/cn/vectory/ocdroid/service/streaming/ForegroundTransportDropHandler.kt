package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.service.LeaseToken
import cn.vectory.ocdroid.service.StreamingOwnershipGate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * L2 foreground drop handler.
 *
 * I3 ordering: releases ownership (via [LeaseToken]) BEFORE publishing
 * Dropped, so consumers never observe `Dropped + Ready` for the same
 * identity. Both operations are idempotent — a second call is a safe no-op.
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
     * Atomic fence for the release-before-publish contract. Uses [LeaseToken]
     * constructed from the attempt's [attemptId] and [identity] so the Gate
     * can release by token identity.
     */
    override fun onUnexpectedDropIfCurrent(
        attempt: TransportAttemptToken,
        reason: TransportDropReason,
    ): Boolean = synchronized(this) {
        val current = runtimeStore.currentAttempt(attempt.identity)
        if (current?.attemptId != attempt.attemptId) return@synchronized false
        ownershipGate.releaseNow(LeaseToken(attempt.attemptId, attempt.identity))
        runtimeStore.publishDropped(attempt, reason) != null
    }
}
