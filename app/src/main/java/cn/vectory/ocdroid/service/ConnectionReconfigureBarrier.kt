package cn.vectory.ocdroid.service

import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The reason a [ReconfigureTeardown.teardownAndAwait] is running. Drives the
 * dedicated teardown path inside [StreamingLifecycleCoordinator]:
 *  - [Reconfigure] — D4-B M4: intentional no-source barrier (NO replacement
 *    poller emitted; old identity is invalid post-`beginReconfigure()`).
 *  - [Timeout] / [UserClose] / [Disconnect] — generic L3 teardown (the
 *    acknowledgeable StartPoller → StopSse handoff may run if leaving L1/L2).
 *  - [BootstrapFailure] — D4-B B1: forces terminal shell cleanup EVEN WHEN
 *    the lifecycle layer is already L3 (a live placeholder Service holding
 *    Starting ownership must be fully torn down: StopSse + StopForeground +
 *    StopPoller + StopSelf + release ownership). The ordinary `L3 → return`
 *    short-circuit is invalid for this reason.
 */
enum class TeardownReason { Reconfigure, Timeout, UserClose, Disconnect, BootstrapFailure }

interface ReconfigureTeardown {
    suspend fun teardownAndAwait(reason: TeardownReason)
}

@Singleton
class ConnectionReconfigureBarrier @Inject constructor(
    private val identityStore: ConnectionIdentityStore,
    private val teardown: ReconfigureTeardown,
    private val effects: SharedEffectBus,
) {
    private val mutex = Mutex()

    suspend fun <T> reconfigure(block: suspend (epoch: Long) -> T): T = mutex.withLock {
        val epoch = identityStore.beginReconfigure()
        teardown.teardownAndAwait(TeardownReason.Reconfigure)
        val result = block(epoch)
        effects.emitEffect(ControllerEffect.HostReconfigured(epoch))
        result
    }
}
