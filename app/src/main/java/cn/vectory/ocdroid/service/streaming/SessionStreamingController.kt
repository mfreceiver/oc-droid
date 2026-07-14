package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.lifecycle.ActivationCommitResult
import cn.vectory.ocdroid.service.lifecycle.LifecycleCommand
import cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator
import cn.vectory.ocdroid.service.notify.NotificationStrings
import cn.vectory.ocdroid.service.notify.SessionStatusNotifier
import cn.vectory.ocdroid.service.status.SessionBusyStatus
import cn.vectory.ocdroid.service.status.StatusAggregator
import cn.vectory.ocdroid.service.status.StatusAggregatorInput
import cn.vectory.ocdroid.service.status.StatusSnapshot
import cn.vectory.ocdroid.service.TeardownReason
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
 * runs the §5 START_STICKY bootstrap, and acks source activations back into
 * the coordinator (D2 gate #4 — acknowledgeable handoff).
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
 * **§4.4 source-switch ordering + D2 acknowledgeable handoff**: the
 * coordinator emits [LifecycleCommand.StartSse] / [LifecycleCommand.StartPoller]
 * carrying a handoff token; this controller launches the corresponding
 * activation on [scope], and on completion forwards the [SourceActivation]
 * to [StreamingLifecycleCoordinator.onActivationAck]. The coordinator's
 * commit (close prior source + flip layer) runs ONLY after the activation
 * verifies — see [StreamingLifecycleCoordinator.beginHandoff].
 *
 * **§6 poller host (D2 gate #4)**: the controller no longer owns a poller
 * job — it delegates [LifecycleCommand.StartPoller] to the shell, which in
 * production forwards to [ProcessStatusPoller.startAndAwaitFirstPoll] (runs
 * the loop on `@ApplicationScope`, survives
 * [cn.vectory.ocdroid.service.SessionStreamingService.onDestroy]). The L3
 * source contract (§6: "no FGS, no SSE, poller only") is thus preserved
 * across Service death.
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
    private val bootstrapMaxAttempts: Int = DEFAULT_BOOTSTRAP_MAX_ATTEMPTS,
    private val bootstrapBackoffMs: Long = DEFAULT_BOOTSTRAP_BACKOFF_MS,
    private val bootstrapRetryPolicy: BootstrapRetryPolicy? = null,
    private val onBootstrapIdentity: suspend (ConnectionIdentity) -> Unit = {},
    /**
     * D4-B B1 — invoked when the bootstrap exhausts its retry budget OR an
     * SSE activation is rejected for transport reasons (TransportTimeout /
     * Exhausted). D5 (#3): the coordinator's [onActivationAck] now returns
     * [ActivationCommitResult.BootstrapRejected] for a current Bootstrap
     * TransportTimeout/Exhausted — the controller calls this ONLY then.
     * The Service wires this to its full B1 rollback (`failStarting` →
     * waiters refused + Starting released + shell cleanup).
     * Passes the currently-bound identity (or null if none).
     */
    private val onBootstrapFailure: (ConnectionIdentity?) -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    // Single-flight guards (read/write on `scope` = Main.immediate).
    private var commandCollectorJob: Job? = null
    private var busyObserverJob: Job? = null
    /**
     * D5-3 (#2-race): EnsurePoller activations are independent coroutines and
     * may be suspended in the poller's immediate first refresh. Track them so
     * a later StopPoller cancels the pending activation BEFORE delegating the
     * stop to the shell. The poller's generation guard is the second line of
     * defense if cancellation loses a race at the install boundary.
     */
    private val pendingEnsurePollerJobs = mutableMapOf<Long, Job>()
    private var started = false

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
     * §5 START_STICKY bootstrap (steps 3-6). The single-flight latch lives
     * in the Service (CP9 §A6: `SessionStreamingService.bootstrapJob`) — a
     * second bootstrap/start intent on the same Service instance cancels
     * the in-flight job before launching a fresh one. CP9 removed the
     * once-per-instance `bootstrapRan` flag here because it could strand a
     * reconfigure race if `stopSelf()` + a new start overlapped.
     */
    suspend fun bootstrapAsync() {
        var attempt = 0
        val retryDelays = bootstrapRetryPolicy?.delaysMs
        while (true) {
            attempt++
            DebugLog.i(TAG, "bootstrapAsync: attempt $attempt")
            when (val result = bootstrapRunner.runBootstrap()) {
                is BootstrapResult.Success -> {
                    degraded = false
                    val identity = result.identity
                    onBootstrapIdentity(identity)
                    val snapshot = sessionSnapshotProvider.current()
                    statusAggregatorInput.refresh(identity, snapshot)
                    coordinator.onBootstrapResult(identity, statusAggregator.globalState.value)
                    return
                }
                is BootstrapResult.TofuNeedsActivity -> {
                    degraded = true
                    DebugLog.w(TAG, "bootstrapAsync: TOFU degraded for ${result.hostPort}")
                    val identity = identityStore.currentIdentity.value
                    val snapshot = sessionSnapshotProvider.current()
                    if (identity != null) {
                        statusAggregatorInput.markRequestFailed(
                            identity = identity,
                            snapshot = snapshot,
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
                    val exhausted = if (retryDelays != null) {
                        attempt > retryDelays.size
                    } else {
                        attempt >= bootstrapMaxAttempts
                    }
                    if (exhausted) {
                        DebugLog.w(TAG, "bootstrapAsync: exhausted $attempt attempts → B1 rollback")
                        // D4-B B1: bootstrap exhaustion is a full Starting
                        // rollback (the Service's failStarting refuses the
                        // launcher waiter + releases Starting + tears down
                        // the shell via teardownAndAwait(BootstrapFailure)).
                        onBootstrapFailure(identityStore.currentIdentity.value)
                        return
                    }
                    val delayMs = retryDelays?.get(attempt - 1) ?: bootstrapBackoffMs
                    DebugLog.i(
                        TAG,
                        "bootstrapAsync: failed attempt=$attempt, backing off ${delayMs}ms",
                    )
                    delay(delayMs)
                }
            }
        }
    }

    /**
     * §4.4 command→action dispatch. Each command is forwarded to [shell] in
     * the order received; the coordinator's emission order already encodes
     * the §4.4 "new source active BEFORE closing old" invariant.
     *
     * D2 gate #4: [LifecycleCommand.StartSse] + [LifecycleCommand.StartPoller]
     * are acknowledgeable — the controller launches the activation on [scope]
     * and forwards the result to [StreamingLifecycleCoordinator.onActivationAck]
     * when it completes. The handoff token on the command identifies which
     * coordinator handoff is being acked; a stale activation (superseded
     * mid-flight) is dropped by the coordinator's token check.
     *
     * SSE side ([connectSse] / [disconnectSse]) is wired through to the
     * Service's [ServiceSseConnectionOwner] (CP9) — the dispatch test
     * verifies the call sequence end-to-end via a recording shell fake.
     */
    private suspend fun executeCommand(cmd: LifecycleCommand) {
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
                // D2: acknowledgeable activation. Launch on scope (non-blocking
                // so the command collector can process concurrent commands —
                // e.g., a teardown's StopSse that supersedes this activation).
                // D5 (#3): the coordinator's onActivationAck now RETURNS the
                // commit result — the controller reacts to BootstrapRejected
                // by calling onBootstrapFailure. The coordinator owns the
                // commit→markReady promotion (no fire-and-forget callback).
                val token = cmd.handoffToken
                val identity = cmd.identity
                scope.launch {
                    val activation = shell.connectSse(identity)
                    val commit = coordinator.onActivationAck(token, activation)
                    when (commit) {
                        is ActivationCommitResult.BootstrapRejected -> {
                            onBootstrapFailure(identityStore.currentIdentity.value)
                        }
                        else -> Unit
                    }
                }
            }
            LifecycleCommand.StopSse -> {
                shell.disconnectSse()
            }
            is LifecycleCommand.StartPoller -> {
                // D2 gate #4: acknowledgeable activation. The shell delegates
                // to ProcessStatusPoller.startAndAwaitFirstPoll which does
                // the immediate-first-poll (no 30s delay) + returns Ready.
                val token = cmd.handoffToken
                scope.launch {
                    val snapshot = sessionSnapshotProvider.current()
                    val activation = shell.startPoller(cmd.identity, snapshot)
                    coordinator.onActivationAck(token, activation)
                }
            }
            LifecycleCommand.StopPoller -> {
                // D5-3 (#2-race): StopPoller strictly wins over an in-flight
                // EnsurePoller. Without this cancellation, shell.stopPoller()
                // can run before ensureRunning installs its delayed loop; the
                // late EnsurePoller ack is then ignored by the coordinator and
                // no second StopPoller is emitted.
                pendingEnsurePollerJobs.values.toList().forEach { it.cancel() }
                pendingEnsurePollerJobs.clear()
                shell.stopPoller()
            }
            is LifecycleCommand.EnsurePoller -> {
                // D5 (#2): supplemental poller activation. The shell delegates
                // to ProcessStatusPoller.ensureRunning (idempotent for same
                // identity). The coordinator's onEnsurePollerAck consumes
                // the result + updates the PollerRuntime.
                val identity = cmd.identity
                val requestId = cmd.requestId
                val ensureJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    try {
                        val snapshot = sessionSnapshotProvider.current()
                        val activation = shell.ensurePoller(identity, snapshot)
                        coordinator.onEnsurePollerAck(identity, requestId, activation)
                    } finally {
                        pendingEnsurePollerJobs.remove(requestId)
                    }
                }
                pendingEnsurePollerJobs[requestId] = ensureJob
                ensureJob.start()
            }
        }
    }

    private fun currentBusyCount(): Int {
        val map = statusAggregator.statusByKey.value
        return map.values.count {
            it == SessionBusyStatus.Busy || it == SessionBusyStatus.Retry
        }
    }

    /**
     * Cancel the command collector + busy observer. The Service's
     * `onDestroy` cancels its `MainScope`, which structurally cancels these
     * too — this method is exposed for explicit / test cleanup.
     *
     * D2 gate #4: the [ProcessStatusPoller]'s loop job runs on
     * `@ApplicationScope` and is NOT cancelled here (it survives Service
     * death — §6 L3 source contract). The poller is cancelled via the
     * [LifecycleCommand.StopPoller] command, which the coordinator emits on
     * L2Idle→L2Active / Rejected activations.
     */
    fun shutdown() {
        commandCollectorJob?.cancel()
        busyObserverJob?.cancel()
        pendingEnsurePollerJobs.values.toList().forEach { it.cancel() }
        pendingEnsurePollerJobs.clear()
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
     * §16-U1 user-explicit-close entry — the non-suspend Service-side
     * forwarder. Launches [handleUserClose] on [scope] so the suspend
     * identity/status recheck + the eventual teardown run asynchronously.
     * The Service's `onStartCommand` ACTION_CLOSE_BACKGROUND branch is a
     * single-line call to this method.
     */
    fun requestUserClose(request: UserCloseRequest = UserCloseRequest(expectedIdentity = null)) {
        DebugLog.i(TAG, "requestUserClose (§16-U1) — launching handleUserClose")
        scope.launch { handleUserClose(request) }
    }

    /**
     * D2 gate #8 / §16-U1 — the §16-U1 implementation note "点击后 **重新查询
     * identity/status**" made testable. Pure-JVM (no Robolectric) — the
     * Service's PendingIntent carries the identity used to build it, parsed
     * by [UserCloseRequestParser]; the close handler revalidates BEFORE any
     * teardown side-effect.
     *
     * Decision tree (Busy is NOT a veto — explicit user command):
     *  1. Parse expected identity (already done — [request] carries it).
     *  2. Read current identity.
     *  3. If [UserCloseRequest.expectedIdentity] exists AND is not current
     *     ([ConnectionIdentityStore.isCurrent] == false) → ignore the stale
     *     action (no teardown). The host reconfigured since the notification
     *     was built; tapping its Action must not tear down the new host.
     *  4. If current identity exists: refresh the status snapshot + recheck
     *     identity AFTER the suspend. If it changed mid-refresh → abort
     *     (don't tear down the newly-configured host).
     *  5. If identity still current (or no identity — degraded/placeholder) →
     *     awaitable user-close teardown via
     *     [StreamingLifecycleCoordinator.requestUserClose].
     *
     * The status query's purpose is to (a) prevent acting on a STALE
     * notification + (b) update the final status snapshot for the
     * notification display layer. **Busy / Unknown does NOT veto** — the
     * close action must not appear broken to the user; if the status query
     * fails (Unknown) the close still proceeds after a stable-identity
     * recheck.
     */
    suspend fun handleUserClose(request: UserCloseRequest) {
        val expected = request.expectedIdentity
        if (expected == null) {
            DebugLog.i(TAG, "handleUserClose: closing no-identity degraded placeholder directly")
            shell.stopForeground()
            shell.serviceStopSelf()
            return
        }
        val current = identityStore.currentIdentity.value
        // §16-U1 step 3: stale-identity action → ignore.
        if (!UserCloseRequestParser.isStillCurrent(expected, identityStore)) {
            DebugLog.i(
                TAG,
                "handleUserClose: stale action (expected epoch=${expected.epoch}, " +
                    "current epoch=${identityStore.currentEpoch()}) — ignoring",
            )
            return
        }
        // §16-U1 step 4: refresh + recheck.
        if (current != null) {
            val snapshot = sessionSnapshotProvider.current()
            statusAggregatorInput.refresh(current, snapshot)
            // Recheck identity AFTER the suspend refresh.
            if (!identityStore.isCurrent(current)) {
                DebugLog.i(TAG, "handleUserClose: identity changed mid-refresh — aborting teardown")
                return
            }
        }
        // §16-U1 step 5: proceed to teardown (Busy/Unknown is NOT a veto).
        // D4-B (awaitable unification): handleUserClose is suspend → await
        // teardownAndAwait directly (no fire-and-forget wrapper).
        DebugLog.i(TAG, "handleUserClose: proceeding to teardown")
        coordinator.teardownAndAwait(TeardownReason.UserClose)
    }

    companion object {
        private const val TAG = "SessionStreamingCtrl"

        /**
         * §6 background poller interval. Equals the §3 status TTL (30s) so
         * each cycle produces a fresh snapshot just as the prior one would
         * age out. Delegates to [ProcessStatusPoller.DEFAULT_INTERVAL_MS]
         * (D2: the poller loop now lives on `@ApplicationScope` in
         * [ProcessStatusPoller]; the const stays here as a back-compat
         * reference for existing tests).
         */
        const val DEFAULT_POLL_INTERVAL_MS = ProcessStatusPoller.DEFAULT_INTERVAL_MS

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
