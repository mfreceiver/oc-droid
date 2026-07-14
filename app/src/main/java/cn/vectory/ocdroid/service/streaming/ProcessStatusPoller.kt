package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.di.ApplicationScope
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.status.GlobalBusyState
import cn.vectory.ocdroid.service.status.StatusAggregator
import cn.vectory.ocdroid.service.status.StatusAggregatorInput
import cn.vectory.ocdroid.service.status.StatusSnapshot
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Singleton

/**
 * D2 (gate #4 / §4.4 / §6): the **process-level** status poller — the §4.4
 * L3 / L2Idle data source that survives [cn.vectory.ocdroid.service.SessionStreamingService.onDestroy].
 *
 * **Why process-level (not Service-scope)**: the §4.4 teardown ordering +
 * the §6 L3 source contract require the poller to keep firing status
 * refreshes after the FGS Service has torn down (the spec calls this
 * "L3 = no FGS, no SSE, poller only"). A poller on the Service's
 * [kotlinx.coroutines.MainScope] would be cancelled by `Service.onDestroy`
 * — leaving L3 with no data source. By injecting `@ApplicationScope` (a
 * [SupervisorJob] + [kotlinx.coroutines.Dispatchers.Default] process-scope),
 * the poller survives Service death AND a sticky rebuild reattaches via
 * [startAndAwaitFirstPoll] without losing the L3 data feed.
 *
 * **§4.4 immediate-first-poll (the "final snapshot poll")**: when the L2-active
 * idle-debounce fires (or the L3 teardown hands off from SSE to poller), the
 * coordinator must read the time-correct post-debounce status BEFORE
 * deciding whether to commit `StopSse`. The poller's
 * [startAndAwaitFirstPoll] performs this refresh synchronously — no 30s
 * delay before the first refresh — and returns the resulting
 * [StatusAggregator.stateAtNow] verdict. The coordinator's handoff commit
 * consumes that verdict to decide `StopSse` (AllIdleFresh) vs `StopPoller`
 * (Busy / Unknown — stay L2Active).
 *
 * **Single-flight**: each [startAndAwaitFirstPoll] call cancels + joins any
 * prior poller job, then performs the immediate refresh, then launches the
 * 30s loop. At most one loop job is alive at a time; the L3 → L2Idle → L2Active
 * → L2Idle churn does not stack pollers.
 *
 * **Identity + snapshot**: each loop iteration re-reads
 * [ConnectionIdentityStore.currentIdentity] + [SessionSnapshotProvider.current]
 * (the snapshot's registered-workdir coverage set can change between
 * iterations as sessions archive / appear — D1 gate #5). The immediate
 * first poll uses the identity + snapshot the caller captured (atomic with
 * the command emission — see [SessionStreamingController.executeCommand]).
 *
 * **Does NOT authorize FGS auto-restart** (§6): the poller is a passive
 * observer; it refreshes the status snapshot, but it does NOT call
 * [cn.vectory.ocdroid.service.StreamingServiceLauncher.ensureStarted]. L3
 * recovery is by legal-entry only (user reopens app / notification action /
 * system restart), never poller-driven.
 *
 * Construction: `@Singleton` + `internal constructor` (the clock default
 * param cannot be Hilt-provided directly — mirrors
 * [cn.vectory.ocdroid.service.status.StatusAggregatorImpl]'s pattern); the
 * `@Provides` in [StreamingModule] fills the clock. The Hilt container
 * still treats this as a singleton.
 *
 * @param scope the process-lifetime [CoroutineScope] (D2: `@ApplicationScope` =
 *   SupervisorJob + Dispatchers.Default — survives Service.onDestroy).
 * @param statusAggregatorInput the §3 main-path refresh entry (REST +
 *   merge-timing + epoch guard).
 * @param snapshotProvider the §3 snapshot + registered-workdir coverage set.
 * @param identityStore the single process-level identity guard (§2 epoch).
 * @param statusAggregator the §3 read surface — [StatusAggregator.stateAtNow]
 *   is the time-correct verdict the immediate first poll returns.
 */
@Singleton
class ProcessStatusPoller internal constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
    private val statusAggregatorInput: StatusAggregatorInput,
    private val snapshotProvider: SessionSnapshotProvider,
    private val identityStore: ConnectionIdentityStore,
    private val statusAggregator: StatusAggregator,
    private val clock: () -> Long,
) {

    /**
     * The current poller loop job, or null when no poller is running.
     * Read/written only from the [startAndAwaitFirstPoll] / [stop] calls
     * (serial — these are driven by the controller's command collector on
     * the Service Main scope, OR by the [ApplicationScope] in tests).
     */
    private var loopJob: Job? = null
    private val mutex = Mutex()
    private val stateLock = Any()
    private var generation: Long = 0L

    /**
     * D5 (#2) — the identity of the currently-running loop (or null when
     * no loop is active). Read/written under [stateLock]; cleared by [stop]
     * so a stale/superseded loop must not claim itself Running. Used by
     * [ensureRunning] for idempotent same-identity no-op.
     */
    @Volatile
    private var runningIdentity: ConnectionIdentity? = null

    /**
     * D2 §4.4 — the immediate-first-poll entry. Cancels + joins any prior
     * poller (single-flight), performs ONE immediate status refresh using
     * the caller-captured [identity] + [snapshot], returns the time-correct
     * [GlobalBusyState] verdict, then launches the 30s loop for subsequent
     * polls.
     *
     * The immediate refresh is the §4.4 "final snapshot poll" — the
     * coordinator consults the returned state to decide whether to commit
     * `StopSse` (AllIdleFresh) or cancel the new poller (Busy / Unknown →
     * stay L2Active). The 30s loop then keeps the data source alive in
     * L2Idle / L3, restarting the §3 aggregator's TTL window each cycle.
     *
     * @param identity the atomic identity capture from the command (the
     *  controller reads [ConnectionIdentityStore.currentIdentity.value] at
     *  command emission time — no re-read of SettingsManager).
     * @param snapshot the atomic snapshot capture from the command (the
     *  controller reads [SessionSnapshotProvider.current] at command
     *  emission time — the immediate poll uses this verbatim; subsequent
     *  loop iterations re-fetch).
     * @param intervalMs the §6 poller interval (default 30s — equals the §3
     *  status TTL so each cycle produces a fresh snapshot just as the prior
     *  one would age out).
     */
    suspend fun startAndAwaitFirstPoll(
        identity: ConnectionIdentity,
        snapshot: StatusSnapshot,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
    ): SourceActivation = mutex.withLock {
        val (myGeneration, prior) = synchronized(stateLock) {
            generation += 1
            generation to loopJob.also { loopJob = null }
        }
        prior?.cancelAndJoin()

        if (!identityStore.isCurrent(identity)) {
            return@withLock SourceActivation.Rejected.StaleIdentity
        }

        runRefresh(identity, snapshot)
        if (synchronized(stateLock) { generation != myGeneration }) {
            return@withLock SourceActivation.Rejected.Superseded
        }
        if (!identityStore.isCurrent(identity)) {
            return@withLock SourceActivation.Rejected.StaleIdentity
        }

        val firstState = statusAggregator.stateAtNow()
        DebugLog.i(TAG, "startAndAwaitFirstPoll: first state=$firstState (identity epoch=${identity.epoch})")

        val newJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            while (isActive) {
                delay(intervalMs)
                if (synchronized(stateLock) { generation != myGeneration }) break
                if (!identityStore.isCurrent(identity)) break
                runRefresh(identity, snapshotProvider.current())
            }
        }
        val accepted = synchronized(stateLock) {
            if (generation == myGeneration) {
                loopJob = newJob
                runningIdentity = identity
                true
            } else {
                false
            }
        }
        if (!accepted) {
            newJob.cancel()
            return@withLock SourceActivation.Rejected.Superseded
        }
        newJob.start()
        // D4-B M3: transport readiness carries no status verdict — the
        // coordinator reads statusAggregator.stateAtNow() at handoff commit.
        SourceActivation.Ready
    }

    /**
     * §6 — stop the poller loop (no further refreshes). Idempotent: a second
     * call without an intervening [startAndAwaitFirstPoll] is a no-op. The
     * coordinator's handoff commit calls this when the activation is
     * cancelled (Busy / Unknown final poll, Rejected activation) or when the
     * L2Idle → L2Active transition commits (the SSE source replaces the
     * poller).
     *
     * D5 (#2): clears the running identity so a stale/superseded loop must
     * not claim itself Running. A subsequent [ensureRunning] for the same
     * identity will see no running loop + start a fresh one.
     *
     * Does NOT cancel the [ApplicationScope] itself — only the loop job.
     * The process-level scope is owned by Hilt and lives for the process;
     * Service.onDestroy does not reach into it.
     */
    fun stop() {
        val job = synchronized(stateLock) {
            generation += 1
            runningIdentity = null
            loopJob.also { loopJob = null }
        }
        job?.cancel()
    }

    /**
     * D5 (#2) — the supplemental poller entry. Tracks the actually-installed
     * loop identity; if the same identity is already running, returns Ready
     * WITHOUT cancel/restart (idempotent). Otherwise starts a fresh loop
     * via [startAndAwaitFirstPoll] + awaits its first poll.
     *
     * Used by [ServiceShell.ensurePoller] which the controller delegates
     * [LifecycleCommand.EnsurePoller] to. The coordinator's
     * [StreamingLifecycleCoordinator.onEnsurePollerAck] consumes the result.
     *
     * @param identity the atomic identity capture from the command.
     * @param snapshot the atomic snapshot capture from the command.
     */
    suspend fun ensureRunning(
        identity: ConnectionIdentity,
        snapshot: StatusSnapshot,
    ): SourceActivation {
        // D5-3 (#2-race): capture the generation together with the fast-path
        // state. A concurrent StopPoller invalidates this observation by
        // incrementing generation and clearing runningIdentity; do not report
        // Ready from an observation that Stop has already superseded.
        val observed = synchronized(stateLock) {
            Triple(generation, runningIdentity, loopJob?.isActive == true)
        }
        if (observed.second == identity && observed.third &&
            synchronized(stateLock) {
                generation == observed.first &&
                    runningIdentity == identity &&
                    loopJob?.isActive == true
            }
        ) {
            // Same identity already running — no-op (idempotent), but only
            // while the generation remains live.
            return SourceActivation.Ready
        }
        val activation = startAndAwaitFirstPoll(identity, snapshot)
        return activation
    }

    private suspend fun runRefresh(identity: ConnectionIdentity, snapshot: StatusSnapshot) {
        try {
            statusAggregatorInput.refresh(identity, snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The §3 refresh is runSuspendCatching-tolerant inside the impl
            // (StatusAggregatorImpl.refresh never throws on a network error —
            // it routes the failure into markRequestFailed + Unknown). This
            // catch is belt-and-suspenders so a future caller cannot kill the
            // poller loop with an unexpected exception.
            DebugLog.w(TAG, "runRefresh failed: ${e.message}")
            statusAggregatorInput.markRequestFailed(identity, snapshot, clock())
        }
    }

    companion object {
        private const val TAG = "ProcessStatusPoller"

        /**
         * §6 background poller interval. Equals the §3 status TTL (30s) so
         * each cycle produces a fresh snapshot just as the prior one would
         * age out — the [StatusAggregator.globalState] projection never
         * reports stale `Idle` during steady-state polling. Matches
         * [SessionStreamingController.DEFAULT_POLL_INTERVAL_MS] +
         * [cn.vectory.ocdroid.di.AppLifecycleMonitor]'s legacy poller.
         */
        const val DEFAULT_INTERVAL_MS = 30_000L
    }
}
