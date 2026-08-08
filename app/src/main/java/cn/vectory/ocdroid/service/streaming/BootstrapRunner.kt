package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.service.identity.ConnectionIdentity

/**
 * FGS spec §5 — the §5 steps 3-6 bootstrap trigger, abstracted so
 * [SessionStreamingController.bootstrapAsync] is pure-JVM unit-testable
 * (fake returns each branch of [BootstrapResult] directly; production impl
 * reads from [cn.vectory.ocdroid.service.identity.ConnectionIdentityStore]).
 *
 * **CP5-7 inert**: the actual tunnel/health/TOFU probing stays in
 * `ConnectionCoordinator` until CP9 (HARD constraint). The production impl
 * here only reads the resulting state — once CC completes its bootstrap and
 * binds the identity, this runner reports [BootstrapResult.Success].
 * CP9 moves the actual probing into a shared runner.
 */
interface BootstrapRunner {

    /**
     * Runs one bootstrap attempt (FGS spec §5 steps 3-6 abstraction).
     *
     * Returns one of:
     *  - [BootstrapResult.Success] — identity is bound; the caller refreshes
     *    the global status snapshot and feeds the lifecycle coordinator.
     *  - [BootstrapResult.Failed] — bootstrap failed (network / tunnel /
     *    SSEConnectionExhausted); the caller applies a bounded retry
     *    with backoff and falls back to the controller's teardown path
     *    if exhausted.
     */
    suspend fun runBootstrap(): BootstrapResult
}

/**
 * One bootstrap attempt's outcome (FGS spec §5).
 */
sealed interface BootstrapResult {

    /**
     * Bootstrap succeeded; [identity] is the bound identity for the caller's
     * status-authority pipeline (both the `StatusAggregatorInput` write side
     * (F1) and the aggregator read side (F6) are retired — authority state is
     * consumed via [SharedStateStore] projections directly).
     */
    data class Success(val identity: ConnectionIdentity) : BootstrapResult

    /**
     * Bootstrap failed (network down / SSEConnectionExhausted / no identity
     * bound after the connect attempt). The caller applies a bounded retry
     * with backoff and falls back to the controller's teardown path
     * if exhausted.
     */
    data object Failed : BootstrapResult
}
