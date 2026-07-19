package cn.vectory.ocdroid.service

import cn.vectory.ocdroid.data.repository.OpenCodeRepository
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

/**
 * C-D3 rev-3 round-5 (oracle §3): context handed to a [block] running inside
 * [ConnectionReconfigureBarrier.reconfigure]. Carries the [epoch] (from
 * [ConnectionIdentityStore.beginReconfigure]) and the [slimTicket] (from
 * [OpenCodeRepository.beginSlimReconfigure]) — both created BEFORE
 * [ReconfigureTeardown.teardownAndAwait] and any [block] side effect, so the
 * [block] can thread the ticket through [OpenCodeRepository.configure] to
 * activate exactly the incarnation that was invalidated up-front.
 */
data class ConnectionReconfigureContext(
    val epoch: Long,
    val slimTicket: OpenCodeRepository.SlimReconfigureTicket,
)

/**
 * Serialized host reconfigure transaction.
 *
 * Order (C-D3 rev-3 round-5 / oracle §3.1):
 *  1. [ConnectionIdentityStore.beginReconfigure] — epoch bump + identity null
 *  2. [OpenCodeRepository.beginSlimReconfigure] — slim marker rotate + clear
 *     **before** any HostStatePurged / settings / HostConfig mutation in [block]
 *  3. streaming teardown (no-source barrier)
 *  4. [block] — purge, [OpenCodeRepository.configure] (with [ConnectionReconfigureContext.slimTicket]),
 *     slice refresh, …
 *  5. [ControllerEffect.HostReconfigured]
 */
@Singleton
class ConnectionReconfigureBarrier @Inject constructor(
    private val identityStore: ConnectionIdentityStore,
    private val repository: OpenCodeRepository,
    private val teardown: ReconfigureTeardown,
    private val effects: SharedEffectBus,
) {
    private val mutex = Mutex()

    /**
     * C-D3 rev-3 round-5: barrier callers receive a [ConnectionReconfigureContext]
     * carrying both the new epoch and the slim reconfigure ticket created
     * BEFORE teardown. The ticket MUST be threaded into
     * [OpenCodeRepository.configure] so the not-ready incarnation invalidated
     * up-front is the one that gets activated on success (ticket-ownership
     * closes the T1/T2 race where a later configure's completion could re-arm
     * a ticket from a different transaction).
     */
    suspend fun <T> reconfigure(
        block: suspend (ConnectionReconfigureContext) -> T,
    ): T = mutex.withLock {
        // Transaction order is mandatory (oracle §3.1):
        //  1. identityStore.beginReconfigure
        //  2. repository.beginSlimReconfigure (slimTicket)
        //  3. teardownAndAwait
        //  4. block(context) — purges + configures with context.slimTicket
        //  5. HostReconfigured
        val epoch = identityStore.beginReconfigure()
        // C-D3 rev-3 round-5: slim incarnation invalidation is the second
        // step of the reconfigure transaction — before teardown side-effects
        // and before the caller's purge / configure block. Old in-flight
        // workflows that later hit commitIfSlimTokenCurrent(oldToken) are
        // rejected. The returned ticket threads through [block] to configure
        // so the SAME transaction both invalidates and activates — closing
        // the T1/T2 completion race.
        val slimTicket = repository.beginSlimReconfigure()
        teardown.teardownAndAwait(TeardownReason.Reconfigure)
        val context = ConnectionReconfigureContext(epoch = epoch, slimTicket = slimTicket)
        val result = block(context)
        effects.emitEffect(ControllerEffect.HostReconfigured(epoch))
        result
    }
}
