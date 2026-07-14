package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.service.identity.ConnectionIdentity

/**
 * FGS spec §5 — the §5 steps 3-6 bootstrap trigger, abstracted so
 * [SessionStreamingController.bootstrapAsync] is pure-JVM unit-testable
 * (fake returns each branch of [BootstrapResult] directly; production impl
 * reads from [cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator]
 * + [cn.vectory.ocdroid.service.identity.ConnectionIdentityStore]).
 *
 * **CP5-7 inert**: the actual tunnel/health/TOFU probing stays in
 * `ConnectionCoordinator` until CP9 (HARD constraint). The production impl
 * here only reads the resulting state — once CC completes its bootstrap and
 * binds the identity, this runner reports [BootstrapResult.Success]. When CC
 * is in the §5 「未决 TOFU 且无 Activity」 degraded state, this runner reports
 * [BootstrapResult.TofuNeedsActivity]. CP9 moves the actual probing into a
 * shared runner.
 */
interface BootstrapRunner {

    /**
     * Runs one bootstrap attempt (FGS spec §5 steps 3-6 abstraction).
     *
     * Returns one of:
     *  - [BootstrapResult.Success] — identity is bound; the caller refreshes
     *    the global status snapshot and feeds the lifecycle coordinator.
     *  - [BootstrapResult.TofuNeedsActivity] — §5 degraded state; the caller
     *    marks the request failed (Unknown keeps the source alive per CP4),
     *    surfaces the degraded notification, and does NOT teardown.
     *  - [BootstrapResult.Failed] — bootstrap failed (network / tunnel /
     *    SSEConnectionExhausted); the caller applies a bounded retry with
     *    backoff and falls back to [StreamingLifecycleCoordinator.onDisconnect]
     *    if exhausted.
     */
    suspend fun runBootstrap(): BootstrapResult
}

/**
 * One bootstrap attempt's outcome (FGS spec §5).
 */
sealed interface BootstrapResult {

    /**
     * Bootstrap succeeded; [identity] is the post-TOFU bound identity to feed
     * into [cn.vectory.ocdroid.service.status.StatusAggregatorInput.refresh]
     * and
     * [cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator.onBootstrapResult].
     */
    data class Success(val identity: ConnectionIdentity) : BootstrapResult

    /**
     * §5 「未决 TOFU 且无 Activity」 degraded — a TOFU trust prompt is owed for
     * [hostPort] but no Activity is available to show the dialog. The caller
     * surfaces a degraded notification with an Open-app action and keeps the
     * source alive (Unknown semantics per CP4).
     */
    data class TofuNeedsActivity(val hostPort: String) : BootstrapResult

    /**
     * Bootstrap failed (network down / SSEConnectionExhausted / no identity
     * bound after the connect attempt). The caller applies a bounded retry
     * with backoff and falls back to
     * [cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator.onDisconnect]
     * if exhausted.
     */
    data object Failed : BootstrapResult
}
