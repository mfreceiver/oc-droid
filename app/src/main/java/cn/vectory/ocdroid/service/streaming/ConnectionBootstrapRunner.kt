package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator
import cn.vectory.ocdroid.service.bootstrap.TofuState
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.util.DebugLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [BootstrapRunner] (FGS spec §5 / §10).
 *
 * **CP5-7 inert contract** (HARD constraint): the actual tunnel/health/TOFU
 * probing stays in `ConnectionCoordinator` until CP9. This runner is a
 * *reader* of the post-bootstrap state — it does NOT itself drive the
 * connect retry loop. When CC completes its bootstrap and calls
 * [ConnectionIdentityStore.bind], this runner reports [BootstrapResult.Success]
 * with the bound identity; when the shared
 * [ConnectionBootstrapCoordinator] is in the §5 「未决 TOFU 且无 Activity」
 * [TofuState.DegradedNeedsActivity] state, this runner reports
 * [BootstrapResult.TofuNeedsActivity]; otherwise (no identity + no degraded
 * TOFU) it reports [BootstrapResult.Failed] and the controller's bounded retry
 * loop kicks in.
 *
 * CP9 will move the probing itself into a shared runner; the interface
 * ([BootstrapRunner]) stays stable so the controller is untouched by that move.
 */
@Singleton
class ConnectionBootstrapRunner @Inject constructor(
    private val engine: ConnectionBootstrapEngine,
) : BootstrapRunner {

    override suspend fun runBootstrap(): BootstrapResult {
        return when (val result = engine.bootstrap()) {
            is ConnectionBootstrapOutcome.Success -> BootstrapResult.Success(result.identity)
            is ConnectionBootstrapOutcome.TofuNeedsActivity -> {
                DebugLog.i(TAG, "runBootstrap: TOFU degraded for ${result.hostPort}")
                BootstrapResult.TofuNeedsActivity(result.hostPort)
            }
            is ConnectionBootstrapOutcome.Failed -> {
                DebugLog.w(TAG, "runBootstrap failed: ${result.error.message}")
                BootstrapResult.Failed
            }
        }
    }

    private companion object {
        private const val TAG = "ConnectionBootstrapRunner"
    }
}
