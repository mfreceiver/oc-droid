package cn.vectory.ocdroid.service

import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.streaming.TransportAttemptToken
import javax.inject.Inject
import javax.inject.Singleton

/**
 * L2 (v5.3 Decision 5): ABA-safe max-1 ownership arbiter for the foreground
 * SSE connection. Replaces the deleted two-stage Starting/Ready + attemptId
 * model (its Launcher→Service driver chain died in L1).
 *
 * INVARIANT: at most ONE lease is live at any instant.
 *
 * ABA GUARD: a lease is identified by [LeaseToken] = (attemptId, identity),
 * where attemptId is minted by [SseTransportRuntimeStore.beginAttempt]'s
 * monotonic counter. A same-identity reconnect mints a NEW attemptId, so a
 * LATE release(oldToken) from the dying old connection fails the token
 * match and cannot release the new connection's lease.
 *
 * THREAD SAFETY: all state is guarded by `synchronized(lock)`; no in-lock
 * mutation suspends. [disconnectAndRelease] extracts the holder under the
 * lock and invokes its suspend teardown OUTSIDE the lock.
 *
 * COMMIT ORDERING (single rule):
 *  - Connect:  Store FIRST (beginAttempt), THEN Gate (claim).
 *  - Destroy:  Gate FIRST (release), THEN Store (markStopped/publishDropped).
 */
@Singleton
class StreamingOwnershipGate @Inject constructor() {

    private val lock = Any()

    /** The single live lease, or null. THE only mutable field. */
    private var holder: LeaseHolder? = null

    private data class LeaseHolder(
        val attempt: TransportAttemptToken,
        val teardown: suspend (Boolean) -> Unit,
    ) {
        val token: LeaseToken get() = LeaseToken(attempt.attemptId, attempt.identity)
    }

    /**
     * Acquire-or-takeover. Grants a new lease iff:
     *  - no live lease exists, OR
     *  - the live lease has the SAME identity AND a STRICTLY OLDER attemptId
     *    (same-identity reconnect takeover — the atomic transfer primitive
     *    for the supersession path; the old lease's teardown is NOT invoked,
     *    the caller cancels the old collector directly).
     * Returns null (rejected) when a live lease exists for a DIFFERENT
     * identity, or for the same identity with attemptId >= the new one
     * (stale duplicate claim — defensive; attemptIds are monotonic).
     */
    fun claim(
        attempt: TransportAttemptToken,
        teardown: suspend (Boolean) -> Unit,
    ): LeaseToken? = synchronized(lock) {
        val current = holder
        when {
            current == null -> {
                holder = LeaseHolder(attempt, teardown)
                LeaseToken(attempt.attemptId, attempt.identity)
            }
            current.attempt.identity == attempt.identity &&
                current.attempt.attemptId < attempt.attemptId -> {
                holder = LeaseHolder(attempt, teardown)
                LeaseToken(attempt.attemptId, attempt.identity)
            }
            else -> null
        }
    }

    /**
     * Explicit precondition-checked handoff. Legal ONLY when the caller
     * still holds [oldToken] (current holder matches exactly) and the new
     * attempt has the same identity + a newer attemptId. Returns the new
     * token, or null if the precondition fails (oldToken already released
     * or superseded — nothing to transfer).
     */
    fun transfer(
        oldToken: LeaseToken,
        newAttempt: TransportAttemptToken,
        teardown: suspend (Boolean) -> Unit,
    ): LeaseToken? = synchronized(lock) {
        val current = holder ?: return@synchronized null
        if (current.token != oldToken) return@synchronized null
        if (newAttempt.identity != oldToken.identity) return@synchronized null
        if (newAttempt.attemptId <= oldToken.leaseId) return@synchronized null
        holder = LeaseHolder(newAttempt, teardown)
        LeaseToken(newAttempt.attemptId, newAttempt.identity)
    }

    /**
     * ABA-safe release. Releases the current lease IFF [token] matches the
     * holder exactly; a mismatched (stale/old-connection) token is REJECTED
     * and the live lease is untouched. Returns true iff a release happened.
     * Non-suspend; never invokes teardown.
     */
    fun releaseNow(token: LeaseToken): Boolean = synchronized(lock) {
        if (holder?.token == token) {
            holder = null
            true
        } else false
    }

    /** Query: is [token] the current holder? */
    fun isCurrent(token: LeaseToken): Boolean = synchronized(lock) {
        holder?.token == token
    }

    /** Diagnostics/tests: the current holder's token, or null. */
    fun currentToken(): LeaseToken? = synchronized(lock) { holder?.token }

    /**
     * CC teardown entry (the 3 ConnectionCoordinator sites). Extracts +
     * clears the current holder under the lock, then invokes its
     * token-guarded teardown suspend callback OUTSIDE the lock. The
     * teardown lambda the Owner registers is guarded by attemptId, so if a
     * NEW lease was claimed between extraction and invocation, the stale
     * teardown no-ops (ABA-safe). No-op when no lease is held.
     */
    suspend fun disconnectAndRelease(markGap: Boolean) {
        val extracted = synchronized(lock) {
            val h = holder
            holder = null
            h
        }
        extracted?.teardown?.invoke(markGap)
    }
}
