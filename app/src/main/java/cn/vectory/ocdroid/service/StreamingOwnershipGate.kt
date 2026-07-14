package cn.vectory.ocdroid.service

import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

sealed interface OwnershipStartResult {
    data class Accepted(val identity: ConnectionIdentity) : OwnershipStartResult

    data class Refused(val reason: OwnershipRefusal) : OwnershipStartResult
}

sealed interface OwnershipRefusal {
    data object Background : OwnershipRefusal
    data object AckTimeout : OwnershipRefusal
    data object StaleIdentity : OwnershipRefusal
    data class AlreadyOwned(val identity: ConnectionIdentity) : OwnershipRefusal
    data class PlatformRejected(val error: Throwable) : OwnershipRefusal
    data object ServiceStopped : OwnershipRefusal
}

@Singleton
class StreamingOwnershipGate @Inject constructor() {
    private data class Owner(
        val identity: ConnectionIdentity,
        val disconnectAndJoin: suspend (Boolean) -> Unit,
    )

    private val mutex = Mutex()
    private var owner: Owner? = null
    private val waiters = mutableMapOf<ConnectionIdentity, MutableSet<CompletableDeferred<OwnershipStartResult>>>()

    suspend fun acceptedIdentity(): ConnectionIdentity? = mutex.withLock { owner?.identity }

    suspend fun prepare(identity: ConnectionIdentity): CompletableDeferred<OwnershipStartResult> =
        mutex.withLock {
            owner?.let { current ->
                return@withLock CompletableDeferred<OwnershipStartResult>().also {
                    it.complete(
                        if (current.identity == identity) OwnershipStartResult.Accepted(identity)
                        else OwnershipStartResult.Refused(OwnershipRefusal.AlreadyOwned(current.identity)),
                    )
                }
            }
            CompletableDeferred<OwnershipStartResult>().also { waiter ->
                waiters.getOrPut(identity) { linkedSetOf() }.add(waiter)
            }
        }

    suspend fun cancelWaiter(identity: ConnectionIdentity, waiter: CompletableDeferred<OwnershipStartResult>) {
        mutex.withLock {
            waiters[identity]?.let { set ->
                set.remove(waiter)
                if (set.isEmpty()) waiters.remove(identity)
            }
        }
    }

    suspend fun registerAccepted(
        identity: ConnectionIdentity,
        disconnectAndJoin: suspend (Boolean) -> Unit,
    ) {
        val accepted: List<CompletableDeferred<OwnershipStartResult>>
        val refused: List<Pair<CompletableDeferred<OwnershipStartResult>, ConnectionIdentity>>
        mutex.withLock {
            owner = Owner(identity, disconnectAndJoin)
            accepted = waiters.remove(identity)?.toList().orEmpty()
            refused = waiters.flatMap { (requested, deferreds) ->
                deferreds.map { it to requested }
            }
            waiters.clear()
        }
        accepted.forEach { it.complete(OwnershipStartResult.Accepted(identity)) }
        refused.forEach { (waiter, _) ->
            waiter.complete(OwnershipStartResult.Refused(OwnershipRefusal.AlreadyOwned(identity)))
        }
    }

    suspend fun refuse(identity: ConnectionIdentity, reason: OwnershipRefusal) {
        val pending = mutex.withLock { waiters.remove(identity)?.toList().orEmpty() }
        pending.forEach { it.complete(OwnershipStartResult.Refused(reason)) }
    }

    suspend fun disconnectAndRelease(markGap: Boolean) {
        val current = mutex.withLock {
            owner.also { owner = null }
        }
        current?.disconnectAndJoin?.invoke(markGap)
    }

    suspend fun release(identity: ConnectionIdentity) {
        mutex.withLock {
            if (owner?.identity == identity) owner = null
        }
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
