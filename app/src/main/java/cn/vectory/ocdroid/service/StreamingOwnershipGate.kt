package cn.vectory.ocdroid.service

import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import kotlinx.coroutines.CompletableDeferred
import javax.inject.Inject
import javax.inject.Singleton

/**
 * D4-B B1 — the result the [StreamingServiceLauncher] returns to
 * [cn.vectory.ocdroid.ui.controller.ConnectionCoordinator]. The launcher
 * completes with [Ready] ONLY after the Service has marked ownership Ready
 * (Stage 2 — transport-ready), so CC publishes [cn.vectory.ui.ConnectionPhase.Connected]
 * exclusively on a verified transport. A [Starting] ownership (Stage 1) does
 * NOT satisfy the launcher's waiter.
 */
sealed interface OwnershipStartResult {
    /**
     * D4-B B1: ownership reached Stage 2 (Ready) — the SSE transport
     * delivered a valid current-identity frame AND the coordinator committed
     * the Bootstrap handoff. The launcher returns this; CC writes Connected.
     */
    data class Ready(val identity: ConnectionIdentity) : OwnershipStartResult

    data class Refused(val reason: OwnershipRefusal) : OwnershipStartResult
}

sealed interface OwnershipRefusal {
    data object Background : OwnershipRefusal
    data object AckTimeout : OwnershipRefusal
    data object StaleIdentity : OwnershipRefusal
    data class AlreadyOwned(val identity: ConnectionIdentity) : OwnershipRefusal
    data class PlatformRejected(val error: Throwable) : OwnershipRefusal
    data object ServiceStopped : OwnershipRefusal
    /** D4-B B1: bootstrap exhausted / transport rejected/timed out. */
    data object BootstrapFailed : OwnershipRefusal
}

/**
 * D4-B B1 — the two-stage ownership state machine inside [StreamingOwnershipGate].
 *
 *  - [Starting] — the Service has verified identity + claimed the bootstrap/
 *    recovery ownership, but the SSE transport has NOT yet proven readiness.
 *    The launcher's readiness waiter is NOT satisfied. Reconfigure teardown
 *    observes + releases a Starting owner (no unowned gap), but CC does NOT
 *    publish Connected.
 *  - [Ready] — the SSE transport delivered a valid current-identity frame AND
 *    the coordinator committed the Bootstrap handoff. The launcher's waiter
 *    completes with [OwnershipStartResult.Ready].
 */
sealed interface OwnershipState {
    val identity: ConnectionIdentity
    val disconnectAndJoin: suspend (Boolean) -> Unit

    data class Starting(
        override val identity: ConnectionIdentity,
        override val disconnectAndJoin: suspend (Boolean) -> Unit,
        /**
         * D4-B B1: invoked by [StreamingOwnershipGate.failStarting] when a
         * Starting owner is rolled back (bootstrap exhaustion / transport
         * timeout / stale identity / explicit cancellation). The Service
         * supplies this lambda to perform shell-level cleanup that the gate
         * cannot reach (stopForeground / stopSelf / cancel SSE / write
         * shared connection state Disconnected).
         */
        val abortStartup: () -> Unit,
    ) : OwnershipState

    data class Ready(
        override val identity: ConnectionIdentity,
        override val disconnectAndJoin: suspend (Boolean) -> Unit,
    ) : OwnershipState
}

/**
 * CP9 §A6 + D3 (gate #3) → **D4-B B1 (two-stage Starting→Ready ownership)** +
 * **D4-B (runBlocking removal / synchronized refactor)**.
 *
 * The single chokepoint that records which [ConnectionIdentity] the live
 * [SessionStreamingService] owns. The launcher ([StreamingServiceLauncher])
 * awaits ownership via [prepare]; the Service registers ownership in two
 * stages:
 *  1. [registerStarting] — Stage 1: claims the bootstrap/recovery ownership
 *     (closes the unowned window) but does NOT complete the launcher's
 *     readiness waiter.
 *  2. [markReady] — Stage 2: the SSE transport proved readiness + the
 *     coordinator committed; completes the launcher's waiter with
 *     [OwnershipStartResult.Ready].
 *
 * **Mandatory rollback** — [failStarting] is the full B1 rollback entry:
 * completes ownership waiters with refusal, releases the Starting owner,
 * and returns the extracted [OwnershipState.Starting] so the caller can
 * invoke its [OwnershipState.Starting.abortStartup] + [OwnershipState.disconnectAndJoin]
 * outside the lock.
 *
 * **Thread safety (D4-B)**: all mutable state is guarded by a plain
 * `synchronized(lock)` section — NONE of the in-lock mutations suspend.
 * [disconnectAndRelease] / [failStarting] extract the owner under the lock
 * and invoke the suspend callback OUTSIDE the lock. [releaseNow] is a
 * non-suspend entry for [SessionStreamingService.onDestroy] (no main-thread
 * blocking).
 */
@Singleton
class StreamingOwnershipGate @Inject constructor() {
    private val lock = Any()
    private var owner: OwnershipState? = null
    private val waiters =
        mutableMapOf<ConnectionIdentity, MutableSet<CompletableDeferred<OwnershipStartResult>>>()

    /**
     * Returns the Ready owner's identity, or null when no Ready ownership
     * is held (Starting ownership does NOT count — the launcher must await
     * [markReady], not short-circuit on Starting).
     */
    fun readyIdentity(): ConnectionIdentity? = synchronized(lock) {
        (owner as? OwnershipState.Ready)?.identity
    }

    /**
     * D4-B B1: registers a waiter that completes when the Service marks the
     * matching identity Ready ([markReady]), or refuses it ([failStarting] /
     * [refuse]). If a Ready owner already matches [identity], the waiter
     * completes immediately with [OwnershipStartResult.Ready] (exact-identity
     * reuse). A Starting owner with the same identity does NOT short-circuit
     * — the launcher awaits the Stage-2 promotion.
     */
    fun prepare(identity: ConnectionIdentity): CompletableDeferred<OwnershipStartResult> =
        synchronized(lock) {
            val ready = (owner as? OwnershipState.Ready)
            if (ready != null) {
                return@synchronized CompletableDeferred<OwnershipStartResult>().also {
                    it.complete(
                        if (ready.identity == identity) OwnershipStartResult.Ready(identity)
                        else OwnershipStartResult.Refused(OwnershipRefusal.AlreadyOwned(ready.identity)),
                    )
                }
            }
            val starting = (owner as? OwnershipState.Starting)
            if (starting != null && starting.identity != identity) {
                return@synchronized CompletableDeferred<OwnershipStartResult>().also {
                    it.complete(
                        OwnershipStartResult.Refused(OwnershipRefusal.AlreadyOwned(starting.identity)),
                    )
                }
            }
            CompletableDeferred<OwnershipStartResult>().also { waiter ->
                waiters.getOrPut(identity) { linkedSetOf() }.add(waiter)
            }
        }

    fun cancelWaiter(identity: ConnectionIdentity, waiter: CompletableDeferred<OwnershipStartResult>) {
        synchronized(lock) {
            waiters[identity]?.let { set ->
                set.remove(waiter)
                if (set.isEmpty()) waiters.remove(identity)
            }
        }
    }

    /**
     * D4-B B1 Stage 1 — the Service claims the bootstrap/recovery ownership.
     * Records the Starting owner (preserving the no-terminal-unowned-gap
     * invariant) but does NOT complete the launcher's readiness waiter (that
     * is [markReady]'s job). Refuses any pending waiter for a DIFFERENT
     * identity (AlreadyOwned).
     *
     * @return true if the Starting ownership was recorded; false if a
     *   different-identity owner already held the gate (the caller should
     *   refuse the request).
     */
    fun registerStarting(
        identity: ConnectionIdentity,
        disconnectAndJoin: suspend (Boolean) -> Unit,
        abortStartup: () -> Unit,
    ): Boolean = synchronized(lock) {
        val current = owner
        if (current != null && current.identity != identity) {
            // Different identity already owns — refuse waiters for [identity].
            waiters.remove(identity)?.forEach {
                it.complete(OwnershipStartResult.Refused(OwnershipRefusal.AlreadyOwned(current.identity)))
            }
            return@synchronized false
        }
        owner = OwnershipState.Starting(identity, disconnectAndJoin, abortStartup)
        // Refuse waiters for OTHER identities (they cannot be satisfied).
        val refused = waiters.filterKeys { it != identity }
        refused.forEach { (requested, deferreds) ->
            waiters.remove(requested)
            deferreds.forEach {
                it.complete(OwnershipStartResult.Refused(OwnershipRefusal.AlreadyOwned(identity)))
            }
        }
        true
    }

    /**
     * D4-B B1 Stage 2 — promote the Starting owner to Ready + complete the
     * matching launcher waiter(s) with [OwnershipStartResult.Ready]. Called
     * by the Service after the SSE transport delivers a valid current-
     * identity frame AND the coordinator commits the Bootstrap handoff.
     *
     * Idempotent: a second call for an already-Ready owner (e.g. an L2Idle-
     * exit SSE re-verifies transport) re-completes waiters harmlessly and
     * stays Ready. A mismatching identity / no owner is a no-op.
     */
    fun markReady(identity: ConnectionIdentity) {
        val toComplete: List<CompletableDeferred<OwnershipStartResult>> = synchronized(lock) {
            val current = owner ?: return@synchronized emptyList()
            if (current.identity != identity) return@synchronized emptyList()
            if (current is OwnershipState.Starting) {
                owner = OwnershipState.Ready(identity, current.disconnectAndJoin)
            }
            waiters.remove(identity)?.toList().orEmpty()
        }
        toComplete.forEach { it.complete(OwnershipStartResult.Ready(identity)) }
    }

    /**
     * D4-B B1 — refuse pending waiters for [identity] with [reason]. Does NOT
     * release an established owner (use [failStarting] / [release] for that).
     */
    fun refuse(identity: ConnectionIdentity, reason: OwnershipRefusal) {
        val pending = synchronized(lock) { waiters.remove(identity)?.toList().orEmpty() }
        pending.forEach { it.complete(OwnershipStartResult.Refused(reason)) }
    }

    /**
     * D4-B B1 mandatory rollback — the full Starting→refusal entry. Under the
     * lock: completes matching waiters with [OwnershipStartResult.Refused]([reason])
     * + extracts + nulls the Starting owner whose identity matches [identity]
     * (or ANY Starting owner when [identity] is null). Returns the extracted
     * [OwnershipState.Starting] so the caller can invoke its
     * [OwnershipState.Starting.abortStartup] + [OwnershipState.disconnectAndJoin]
     * OUTSIDE the lock (no suspension under the lock).
     *
     * A Ready owner is NOT rolled back by this method (transport already
     * proved); only Starting ownership is. Returns null when no Starting
     * owner matched.
     */
    suspend fun failStarting(identity: ConnectionIdentity?, reason: OwnershipRefusal): OwnershipState.Starting? {
        val extracted: OwnershipState.Starting? = synchronized(lock) {
            val current = owner
            val starting = current as? OwnershipState.Starting
            if (starting == null) {
                // No Starting owner — still refuse any matching waiter so the
                // launcher does not hang on a vanished bootstrap.
                if (identity != null) {
                    waiters.remove(identity)?.forEach {
                        it.complete(OwnershipStartResult.Refused(reason))
                    }
                }
                return@synchronized null
            }
            if (identity != null && starting.identity != identity) {
                return@synchronized null
            }
            owner = null
            waiters.remove(starting.identity)?.forEach {
                it.complete(OwnershipStartResult.Refused(reason))
            }
            starting
        }
        return extracted
    }

    /**
     * D4-B — extracts + nulls the current owner (any state) + invokes its
     * [OwnershipState.disconnectAndJoin] suspend callback OUTSIDE the lock.
     * Used by [StreamingLifecycleCoordinator.teardownAndAwait] for the
     * Reconfigure / Disconnect / Timeout / UserClose paths.
     */
    suspend fun disconnectAndRelease(markGap: Boolean) {
        val current = synchronized(lock) { owner.also { owner = null } }
        current?.disconnectAndJoin?.invoke(markGap)
    }

    /**
     * D4-B — release the owner if its identity matches. Non-suspend entry
     * for [SessionStreamingService.onDestroy] (replaces the prior
     * `runBlocking { release(identity) }` which blocked the main thread).
     */
    fun releaseNow(identity: ConnectionIdentity) {
        synchronized(lock) {
            if (owner?.identity == identity) owner = null
        }
    }

    /** Suspend alias kept for compatibility; prefer [releaseNow] from non-suspend callers. */
    suspend fun release(identity: ConnectionIdentity) {
        releaseNow(identity)
    }
}

@Singleton
class OwnershipAckPolicy @Inject constructor() {
    val timeoutMs: Long = 5_000L
}

object OwnershipRequestParser {
    const val EXTRA_EPOCH = "cn.vectory.ocdroid.extra.ownership.epoch"
    const val EXTRA_SERVER_GROUP_FP = "cn.vectory.ocdroid.extra.ownership.serverGroupFp"
    const val EXTRA_WORKDIR = "cn.vectory.ocdroid.extra.ownership.workdir"
    const val EXTRA_ENDPOINT_FP = "cn.vectory.ocdroid.extra.ownership.endpointFp"

    fun parse(
        epoch: Long?,
        serverGroupFp: String?,
        workdir: String?,
        endpointFp: String?,
    ): ConnectionIdentity? = if (
        epoch != null && serverGroupFp != null && workdir != null && endpointFp != null
    ) {
        ConnectionIdentity(epoch, serverGroupFp, workdir, endpointFp)
    } else {
        null
    }
}
