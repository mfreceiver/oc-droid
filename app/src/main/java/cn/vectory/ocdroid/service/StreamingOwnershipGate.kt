package cn.vectory.ocdroid.service

import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.streaming.TransportAttemptToken
import javax.inject.Inject
import javax.inject.Singleton

/**
 * L2: simplified ownership gate — a single-slot identity lease.
 *
 * Replaces the D5-2 two-stage Starting→Ready ownership machine with a
 * straightforward lease model. The gate owns exactly one lease at a time
 * (identity-scoped). A caller acquires the lease via [claim]; releases it
 * via [releaseNow] / [release]. The gate provides no waiters, no prepared
 * attempts, no split deferreds, no Starting/Ready promotion.
 *
 * ## Entry points
 *  - **CC** calls [readyIdentity] (non-suspending) and [disconnectAndRelease]
 *    (suspending, same signature as D4-B B1).
 *  - **Owner** calls [claim] in [setupConnectLocked] and [releaseNow] in
 *    [disconnectLocked] / [cancelForShutdown].
 *  - **DropHandler** calls [releaseNow] after routing an unexpected drop.
 *
 * ## Thread safety
 * All mutable state is guarded by a plain `synchronized(lock)` section —
 * none of the in-lock mutations suspend. [disconnectAndRelease] is the only
 * suspend entry; it clears the lease under the lock (non-suspending work)
 * and returns.
 *
 * ## L2 removals (vs D5-2)
 *  - [prepareAttempt] / [prepare] / [registerStarting] / [expireAttempt] /
 *    [failStarting] / [failStartingIfTerminal] / [refuse] / [cancelWaiter] /
 *    [markReady] / [hasLiveAttemptOtherThan]
 *  - [PreparedOwnershipAttempt] / [StartingAck] / [RegisterStartingOutcome] /
 *    [OwnershipState] / [OwnershipAckPolicy] / [runTeardown]
 *  - `kotlinx.coroutines.CompletableDeferred` / `Deferred` usage
 *  - The waiter set, pending attempt slot, split-deferred model
 *
 * @see LeaseToken
 * @see OwnershipStartResult
 * @see OwnershipRefusal
 */
@Singleton
class StreamingOwnershipGate @Inject constructor() {

    private val lock = Any()

    /** The identity currently holding the lease, or null when unleased. */
    @Volatile
    private var leasedIdentity: ConnectionIdentity? = null

    /**
     * Monotonic lease-ID counter. Every successful [claim] allocates a fresh
     * ID so the returned [LeaseToken] is unique across the lifetime of this
     * gate (no ABA on token identity).
     */
    private var leaseCounter: Long = 0L

    /**
     * Returns the identity of the lease holder, or null when no lease is
     * held. Non-suspending — CC calls this on the main thread to decide
     * whether a bootstrap is already live.
     */
    fun readyIdentity(): ConnectionIdentity? = synchronized(lock) { leasedIdentity }

    /**
     * Acquires the lease for [identity].
     *
     * ## Cases
     *  1. **No lease held** → allocated a fresh [LeaseToken], records
     *     [identity] as the lease holder, returns the token.
     *  2. **Same identity already holds the lease** → returns a new
     *     [LeaseToken] for the same identity (idempotent; the caller can
     *     safely release with either the old or new token).
     *  3. **Different identity holds the lease** → returns `null`.
     *     The caller must NOT start a collector — a different identity owns
     *     the transport slot.
     *
     * @return a [LeaseToken] on success, `null` when a different identity
     *   already holds the lease.
     */
    fun claim(identity: ConnectionIdentity): LeaseToken? = synchronized(lock) {
        val current = leasedIdentity
        if (current != null && current != identity) return@synchronized null
        leasedIdentity = identity
        LeaseToken(leaseId = ++leaseCounter, identity = identity)
    }

    /**
     * Releases the lease identified by [token]. A no-op when the token's
     * identity does not match the current lease holder (the lease was already
     * released or superseded by a different-identity claim).
     *
     * Non-suspending — safe to call from [ForegroundTransportDropHandler]
     * (which runs under its own monitor) and from [ServiceSseConnectionOwner.disconnectLocked]
     * / [cancelForShutdown].
     */
    fun releaseNow(token: LeaseToken) {
        synchronized(lock) {
            if (leasedIdentity == token.identity) {
                leasedIdentity = null
            }
        }
    }

    /**
     * Suspend alias for [releaseNow]. Exists for callers that prefer a
     * suspend signature (e.g. [ServiceSseConnectionOwner.disconnectLocked]
     * is already a suspend function).
     */
    suspend fun release(token: LeaseToken) {
        releaseNow(token)
    }

    /**
     * Clears the current lease unconditionally. Suspending entry for CC's
     * teardown paths (reconfigure / disconnect / timeout / user-close).
     *
     * [markGap] is accepted for signature compatibility with the D4-B API
     * (CC passes it unchanged). In L2 the gap signal is emitted by the
     * Owner's [ServiceSseConnectionOwner.disconnectLocked], NOT by the
     * Gate — this parameter is ignored.
     */
    suspend fun disconnectAndRelease(markGap: Boolean = true) {
        synchronized(lock) { leasedIdentity = null }
    }

    /**
     * Non-suspend alias for identity-scoped release (convenience for
     * callers that hold a [TransportAttemptToken] and want to release
     * by identity without constructing a [LeaseToken]).
     *
     * Releases the lease if [identity] matches the current holder; no-op
     * otherwise.
     */
    fun releaseNow(identity: ConnectionIdentity) {
        synchronized(lock) {
            if (leasedIdentity == identity) {
                leasedIdentity = null
            }
        }
    }

    companion object {
        /**
         * Sentinels are no longer required in L2 — kept as reference for
         * any legacy code that may reference [StreamingOwnershipGate.NO_ATTEMPT_ID].
         * The constant is unused internally.
         */
        @Deprecated("L2: no attempt-ID model; kept for binary compatibility")
        const val NO_ATTEMPT_ID: Long = -1L
    }
}
