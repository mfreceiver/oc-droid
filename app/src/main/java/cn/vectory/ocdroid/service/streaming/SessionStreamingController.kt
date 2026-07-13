package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.lifecycle.LifecycleCommand
import cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator
import cn.vectory.ocdroid.service.notify.NotificationStrings
import cn.vectory.ocdroid.service.notify.SessionStatusNotifier
import cn.vectory.ocdroid.service.status.SessionBusyStatus
import cn.vectory.ocdroid.service.status.StatusAggregator
import cn.vectory.ocdroid.service.status.StatusAggregatorInput
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
/**
 * FGS spec §1.0 / §5 / §6 — the pure-JVM orchestrator that turns
 * [StreamingLifecycleCoordinator.commands] into [ServiceShell] side-effects,
 * runs the §6 background poller, and drives the §5 START_STICKY bootstrap.
 *
 * The Android [cn.vectory.ocdroid.service.SessionStreamingService] is a thin
 * shell: it builds [NotificationStrings] from `R.string.*`, constructs this
 * controller with `this` (well — a private `ServiceShell` impl) as the shell,
 * calls [start] in `onCreate`, and calls [bootstrapAsync] from `onStartCommand`
 * after the §5 step 2 placeholder promotion. Everything else lives here.
 *
 * **Why pure-JVM**: every collaborator is either a small interface
 * ([ServiceShell], [BootstrapRunner], [SessionSnapshotProvider]) or an
 * already-pure class ([StreamingLifecycleCoordinator],
 * [ConnectionIdentityStore]). The controller is unit-tested with fakes +
 * `runTest` — no Robolectric.
 *
 * **§4.4 source-switch ordering**: the coordinator already emits commands in
 * the right order (new source active BEFORE closing old). This controller
 * forwards each command to the shell in the order received, so the shell
 * observes e.g. `startPoller()` THEN `disconnectSse()` for L2Active→L2Idle.
 *
 * **Poller idempotence** (§6): a single [pollerJob] guard prevents stacking.
 * `StartPoller` cancels any in-flight poller before launching the new one;
 * `StopPoller` cancels + nulls. The poller loop calls
 * [StatusAggregatorInput.refresh] every [pollIntervalMs]; the resulting
 * [StatusAggregator.globalState] flows back into the coordinator (L2Idle
 * poller finds busy → in-place L2Active per groker-R1).
 *
 * **§5 bootstrap (CP7)**: [bootstrapAsync] runs the §5 steps 3-6 sequence:
 *  - call [BootstrapRunner.runBootstrap];
 *  - on success → refresh the global status snapshot →
 *    [StreamingLifecycleCoordinator.onBootstrapResult] with the post-refresh
 *    [StatusAggregator.globalState];
 *  - on TOFU degraded → [StatusAggregatorInput.markRequestFailed] (Unknown
 *    keeps the source alive per CP4) + degraded notification (NO teardown);
 *  - on Failed → bounded retry with backoff; if exhausted →
 *    [StreamingLifecycleCoordinator.onDisconnect] (§5 step 6 long-retry
 *    fallback).
 */
class SessionStreamingController(
    private val coordinator: StreamingLifecycleCoordinator,
    private val statusAggregator: StatusAggregator,
    private val statusAggregatorInput: StatusAggregatorInput,
    private val identityStore: ConnectionIdentityStore,
    private val sessionSnapshotProvider: SessionSnapshotProvider,
    private val bootstrapRunner: BootstrapRunner,
    private val shell: ServiceShell,
    private val strings: NotificationStrings,
    private val inForeground: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val bootstrapMaxAttempts: Int = DEFAULT_BOOTSTRAP_MAX_ATTEMPTS,
    private val bootstrapBackoffMs: Long = DEFAULT_BOOTSTRAP_BACKOFF_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    // Single-flight guards (read/write on `scope` = Main.immediate).
    private var pollerJob: Job? = null
    private var commandCollectorJob: Job? = null
    private var busyObserverJob: Job? = null
    private var started = false
    private var bootstrapRan = false

    /**
     * Chronometer base for the 「N tasks running」 notification (§7: 起点 =
     * earliest busy transition in the current aggregation). Set when the
     * aggregator's `globalBusy` flips false→true; cleared on true→false.
     */
    private var busySinceMs: Long? = null

    /**
     * §5 degraded state flag — true after a [BootstrapResult.TofuNeedsActivity]
     * until a subsequent successful bootstrap clears it. Drives the
     * [SessionStatusNotifier] degraded cell.
     */
    private var degraded = false

    /**
     * Idempotent lifecycle entry. Binds the coordinator's foreground/state
     * observer ([StreamingLifecycleCoordinator.start]), launches the command
     * collector that runs for the service lifetime, and starts the
     * busy-transition tracker for the chronometer base.
     *
     * MUST be called before [bootstrapAsync] so the coordinator's observer is
     * bound before [StreamingLifecycleCoordinator.onBootstrapResult] fires.
     */
    fun start() {
        if (started) return
        started = true
        coordinator.start(inForeground)
        commandCollectorJob = scope.launch {
            coordinator.commands.collect { cmd ->
                runCatching { executeCommand(cmd) }
                    .onFailure { DebugLog.e(TAG, "executeCommand($cmd) failed", it) }
            }
        }
        busyObserverJob = scope.launch {
            statusAggregator.globalBusy.collect { busy ->
                val now = clock()
                if (busy) {
                    if (busySinceMs == null) busySinceMs = now
                } else {
                    busySinceMs = null
                }
            }
        }
    }

    /**
     * §5 START_STICKY bootstrap (steps 3-6). Single-flight per controller
     * instance — the Service's `onStartCommand` may fire multiple times
     * (START_STICKY sticky-rebuild) but the bootstrap body runs once.
     */
    suspend fun bootstrapAsync() {
        if (bootstrapRan) {
            DebugLog.i(TAG, "bootstrapAsync: already ran — skipping (single-flight)")
            return
        }
        bootstrapRan = true

        var attempt = 0
        while (true) {
            attempt++
            DebugLog.i(TAG, "bootstrapAsync: attempt $attempt")
            when (val result = bootstrapRunner.runBootstrap()) {
                is BootstrapResult.Success -> {
                    degraded = false
                    val identity = result.identity
                    // §3 Phase-0 main path: global getSessionStatus, binned by
                    // sessionsById.directory. The aggregator applies §3.1 merge
                    // timing + §2 epoch guard internally.
                    val sessions = sessionSnapshotProvider.current()
                    statusAggregatorInput.refresh(identity, sessions)
                    // §5 decision matrix feeds the post-refresh authoritative
                    // state; the coordinator's matrix picks L1/L2Active/L3.
                    coordinator.onBootstrapResult(identity, statusAggregator.globalState.value)
                    return
                }
                is BootstrapResult.TofuNeedsActivity -> {
                    // §5 degraded: do NOT retry, do NOT teardown (Unknown keeps
                    // the source alive per CP4). Mark the request failed so the
                    // aggregator reports Unknown (keep-alive), surface the
                    // degraded notification.
                    degraded = true
                    DebugLog.w(TAG, "bootstrapAsync: TOFU degraded for ${result.hostPort}")
                    val identity = identityStore.currentIdentity.value
                    val sessions = sessionSnapshotProvider.current()
                    if (identity != null) {
                        statusAggregatorInput.markRequestFailed(
                            identity = identity,
                            sessionsById = sessions,
                            sourceTimeMs = clock(),
                        )
                    }
                    val spec = SessionStatusNotifier.build(
                        layer = coordinator.layer.value,
                        busyCount = currentBusyCount(),
                        strings = strings,
                        busySinceMs = null,
                        degraded = true,
                    )
                    shell.updateNotification(spec)
                    return
                }
                BootstrapResult.Failed -> {
                    // §5 step 6: bounded retry with backoff. SSE ownership stays
                    // in ConnectionCoordinator until CP9, so SSEConnectionExhausted
                    // isn't reachable here yet — but the retry mechanism is in
                    // place for the bootstrap itself (network down on sticky rebuild).
                    if (attempt >= bootstrapMaxAttempts) {
                        DebugLog.w(TAG, "bootstrapAsync: exhausted $attempt attempts → onDisconnect")
                        coordinator.onDisconnect()
                        return
                    }
                    DebugLog.i(
                        TAG,
                        "bootstrapAsync: failed (attempt $attempt/$bootstrapMaxAttempts), backing off ${bootstrapBackoffMs}ms",
                    )
                    delay(bootstrapBackoffMs)
                }
            }
        }
    }

    /**
     * §4.4 command→action dispatch. Each command is forwarded to [shell] in
     * the order received; the coordinator's emission order already encodes
     * the §4.4 "new source active BEFORE closing old" invariant.
     *
     * SSE side ([connectSse] / [disconnectSse]) are CP9 stubs on the shell —
     * the dispatch test still verifies the call sequence end-to-end.
     */
    private fun executeCommand(cmd: LifecycleCommand) {
        when (cmd) {
            LifecycleCommand.StartForeground -> {
                val spec = SessionStatusNotifier.build(
                    layer = coordinator.layer.value,
                    busyCount = currentBusyCount(),
                    strings = strings,
                    busySinceMs = busySinceMs,
                    degraded = degraded,
                )
                shell.startForeground(spec)
            }
            LifecycleCommand.StopForeground -> shell.stopForeground()
            LifecycleCommand.StopSelf -> shell.serviceStopSelf()
            is LifecycleCommand.StartSse -> {
                // CP9: SSE collector moves here from ConnectionCoordinator.
                // Today the shell's body is a DEBUG log only; the dispatch
                // test still routes through this so §4.4 ordering is verified.
                shell.connectSse(cmd.identity)
            }
            LifecycleCommand.StopSse -> {
                // CP9: SSE collector teardown moves here.
                shell.disconnectSse()
            }
            LifecycleCommand.StartPoller -> {
                // §6 idempotence: cancel any in-flight poller before relaunch.
                pollerJob?.cancel()
                pollerJob = scope.launch {
                    while (isActive) {
                        delay(pollIntervalMs)
                        runPollCycle()
                    }
                }
                // Observable marker — production impl is a no-op, tests record
                // the call sequence to verify §4.4 source-switch ordering.
                shell.startPoller()
            }
            LifecycleCommand.StopPoller -> {
                pollerJob?.cancel()
                pollerJob = null
                shell.stopPoller()
            }
        }
    }

    /**
     * One §3 global-status refresh cycle. The aggregator's internal §3.1
     * merge timing + §2 epoch guard apply; the resulting [StatusAggregator.globalState]
     * flows back into the lifecycle coordinator (groker-R1 in-place L2Idle→L2Active).
     */
    private suspend fun runPollCycle() {
        val identity: ConnectionIdentity = identityStore.currentIdentity.value ?: run {
            DebugLog.w(TAG, "poller: no bound identity — skipping cycle")
            return
        }
        val sessions = sessionSnapshotProvider.current()
        statusAggregatorInput.refresh(identity, sessions)
    }

    private fun currentBusyCount(): Int {
        val map = statusAggregator.statusByKey.value
        return map.values.count {
            it == SessionBusyStatus.Busy || it == SessionBusyStatus.Retry
        }
    }

    /**
     * Cancel the command collector + poller + busy observer. The Service's
     * `onDestroy` cancels its `MainScope`, which structurally cancels these
     * too — this method is exposed for explicit / test cleanup.
     */
    fun shutdown() {
        commandCollectorJob?.cancel()
        busyObserverJob?.cancel()
        pollerJob?.cancel()
        pollerJob = null
    }

    /**
     * §4.1 platform dataSync time-limit callback routed through the
     * controller for testability (the Service's `onTimeout(startId)` is a
     * single-line forwarder; routing through here lets the pure-JVM test
     * fixture verify the wiring end-to-end without Robolectric).
     *
     * Forwards to [StreamingLifecycleCoordinator.onTimeout] → L3 teardown
     * (no auto-recovery).
     */
    fun onServiceTimeout() {
        DebugLog.i(TAG, "onServiceTimeout (§4.1 dataSync platform timeout)")
        coordinator.onTimeout()
    }

    /**
     * §16-U1 user-explicit-close entry routed through the controller for
     * testability (the Service's `onStartCommand` ACTION_CLOSE_BACKGROUND
     * branch is a single-line forwarder). Forwards to
     * [StreamingLifecycleCoordinator.requestUserClose] → L3 teardown
     * (`stopForeground` + `stopSelf` + `cancelSse` + arm poller + dismiss
     * ongoing).
     */
    fun requestUserClose() {
        DebugLog.i(TAG, "requestUserClose (§16-U1 user-explicit close)")
        coordinator.requestUserClose()
    }

    companion object {
        private const val TAG = "SessionStreamingCtrl"

        /**
         * §6 background poller interval.
         *
         * Chosen to equal the §3 status TTL
         * ([StatusAggregatorImpl.STATUS_TTL_MS] = 30s) so each cycle produces
         * a fresh snapshot just as the prior one would have aged out — the
         * [StatusAggregator.globalState] projection thus never reports stale
         * `Idle` (which would wrongly permit the idle grace window) during
         * steady-state polling. Same value as
         * [cn.vectory.ocdroid.di.AppLifecycleMonitor]'s legacy background poller
         * (§18.1 R-A, D1) so the two pollers (legacy permissions/questions vs.
         * §3 status) align cadence while the switchover lanes coexist.
         */
        const val DEFAULT_POLL_INTERVAL_MS = 30_000L

        /**
         * §5 step 6 bounded retry budget. Conservative so a transient network
         * blip on sticky rebuild doesn't immediately fall through to
         * [StreamingLifecycleCoordinator.onDisconnect] (which would tear down
         * to L3 and require a legal re-entry).
         */
        const val DEFAULT_BOOTSTRAP_MAX_ATTEMPTS = 3

        /**
         * §5 step 6 backoff between bootstrap retries. Linear (not exponential)
         * — the budget is small (3 attempts) and the goal is just to outlast
         * a sub-second transient.
         */
        const val DEFAULT_BOOTSTRAP_BACKOFF_MS = 2_000L
    }
}
