package cn.vectory.ocdroid.service

import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

enum class TeardownReason { Reconfigure, Timeout, UserClose, Disconnect }

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
