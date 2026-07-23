package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.di.ApplicationScope
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.status.GlobalBusyState
import cn.vectory.ocdroid.service.status.StatusAggregator
import cn.vectory.ocdroid.service.status.StatusAggregatorInput
import cn.vectory.ocdroid.service.status.StatusFanOutSummary
import cn.vectory.ocdroid.service.status.StatusSnapshot
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.exponentialBackoffMs
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
    /**
     * §final-gate I-1 (oracle §3.1): injectable slim-mode status fan-out
     * runner. Returns null when slim mode is off (legacy gate) OR when the
     * network sweep fails (the catch in [runSlimFanOut] swallows non-CE
     * throwables). Defaults to `{ _, _ -> null }` so existing direct tests
     * (pre-I-1) keep compiling + behave identically (no fan-out path
     * engaged). Production wiring constructs a [SlimStatusFanOut] and
     * routes through it (see [StreamingModule]).
     *
     * The runner receives the [ConnectionIdentity] + [StatusSnapshot] the
     * poller captured for the tick; the runner is responsible for its OWN
     * identity re-checks before issuing HTTP (the wrapper in
     * [StreamingModule] gates on `identityStore.isCurrent(identity)`
     * first, and `runSlimFanOut` re-checks before sinking the summary).
     */
    private val slimFanOutRunner:
        suspend (ConnectionIdentity, StatusSnapshot) -> StatusFanOutSummary? =
        { _, _ -> null },

    /**
     * §final-gate I-1 (oracle §3.1): sink for a non-null fan-out summary.
     * Production routes through
     * [cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator.applySlimStatusFanOutSummary]
     * which emits per-sid `EvictSession` effects (404) + the poller
     * backoff/reset effect (503 / success). Default `{}` preserves
     * existing direct tests.
     */
    private val slimFanOutSummarySink:
        (StatusFanOutSummary) -> Unit =
        {},
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
     * T13-C4 — slim on-demand fan-out backoff state. Tracks consecutive
     * retryable fan-out sweeps (503 / transport failures) so
     * [scheduleBackoff] produces a bounded exponential + jitter delay
     * (200ms → 400ms → 800ms → … capped at [BACKOFF_MAX_MS]=30s).
     * [resetBackoff] returns the state to base on a successful sweep
     * (retryableCount == 0). [currentBackoffDelayMs] exposes the pending
     * delay so a slim-fan-out scheduler can pace its next sweep.
     *
     * Guarded by [stateLock] (the poller's existing serial-write guard).
     * The bulk L3 loop ([runRefresh]) does NOT consume the backoff —
     * T13-C6 keeps the host-wide bulk path byte-for-byte unchanged; the
     * backoff is exposed for slim on-demand consumers.
     */
    private var backoffAttempt: Int = 0
    private var pendingBackoffMs: Long = 0L

    /**
     * §final-gate I-1 (oracle §3.2): the single-flight retry job for the
     * slim fan-out. Launched by [requestSlimFanOutRetry] when AppCore
     * receives a [cn.vectory.ocdroid.ui.controller.ControllerEffect.RequestPollerBackoff]
     * effect (the coordinator emits it whenever a sweep returned
     * retryableCount > 0). A new retry request cancels the prior job so
     * timers cannot stack. Cancelled by [stop] (host switch / lifecycle)
     * and by [resetBackoff] (a successful sweep arrives before the retry
     * fires).
     */
    private var slimRetryJob: Job? = null

    /**
     * §final-gate I-1 (oracle §3.2): serializes slim fan-out sweeps.
     * Separate from [mutex] (which serializes startAndAwaitFirstPoll) so
     * a sweep held on a network call does NOT block the start/stop
     * command path, and vice versa. The fan-out retry job and the
     * periodic / immediate triggers all funnel through this mutex so at
     * most one sweep is in flight at a time.
     */
    private val slimFanOutMutex = Mutex()

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

        // §final-gate I-1 (oracle §3.4): immediate slim fan-out alongside
        // the immediate bulk refresh above. Slim-only — slimFanOutRunner
        // returns null in legacy mode (no fan-out HTTP). Runs in this
        // same critical section as the first poll so the L2Idle handoff
        // sees the post-fan-out summary effects (EvictSession for stale
        // sids + backoff/reset) before the coordinator commits.
        runSlimFanOut(identity, snapshot)
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
                // §final-gate I-1 (oracle §3.4): each 30s tick now does
                // (1) re-fetch the snapshot (the session list changes
                // over time — archive / create / new directories), (2)
                // the bulk runRefresh (unchanged), (3) the slim fan-out
                // sweep over the LATEST snapshot's sids. nextSnapshot is
                // captured ONCE per tick and reused for both calls so
                // the bulk + slim paths see the same session list.
                val nextSnapshot = snapshotProvider.current()
                runRefresh(identity, nextSnapshot)
                runSlimFanOut(identity, nextSnapshot)
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
        // §final-gate I-1 (oracle §3.5): cancel BOTH the loop job and the
        // slim fan-out retry job. The retry job is independent of the loop
        // (it lives on the same ApplicationScope but is launched outside
        // the loop), so the loop's cancel does not reach it. Bumping
        // generation invalidates any retry that has already awoken on the
        // delay; the explicit cancel reaches any retry still sleeping.
        val (loop, retry) = synchronized(stateLock) {
            generation += 1
            runningIdentity = null
            val oldLoop = loopJob
            val oldRetry = slimRetryJob
            loopJob = null
            slimRetryJob = null
            oldLoop to oldRetry
        }
        loop?.cancel()
        retry?.cancel()
    }

    /**
     * T13-C4 — schedule a bounded exponential + jitter backoff for the
     * slim on-demand fan-out's next sweep. Called by the coordinator's
     * effect handler when a slim fan-out sweep returned `retryableCount > 0`
     * (503 / transport fault per §6 G2).
     *
     * Each consecutive call DOUBLES the base delay
     * ([BACKOFF_BASE_MS] = 200ms → 200/400/800/…), shifted by the
     * current [backoffAttempt] (capped at [BACKOFF_MAX_SHIFT] so the
     * exponent stops growing once the cap binds). The jittered delay is
     * then clamped to [BACKOFF_MAX_MS] (= 30s — equals [DEFAULT_INTERVAL_MS]
     * so polling never goes SLOWER than the steady-state interval; the
     * cap keeps polling responsive even under sustained 503).
     *
     * ±20% jitter ([jitter] clamped to `[-0.2, +0.2]`, per
     * [SseRecoveryPolicy.clampJitter]) — production samples a PRNG;
     * tests pass `0.0f` for the deterministic base schedule.
     *
     * Returns the computed delay so callers (effect handlers / tests) can
     * observe the value WITHOUT a separate read-modify-write race.
     *
     * **Legacy bulk L3 loop ([runRefresh]) is NOT affected** — T13-C6
     * keeps the host-wide bulk path byte-for-byte unchanged; the backoff
     * is exposed via [currentBackoffDelayMs] for slim on-demand
     * consumers (the coordinator + a future slim-fan-out scheduler).
     *
     * @param jitter a deterministic-injection point in `[-0.2, +0.2]`
     *  (production samples a PRNG; tests pass `0.0f` for the
     *  deterministic base schedule). Outside that range is clamped
     *  (defensive — the contract is ±20%). **Round-2 M2 fix:** the
     *  default [DEFAULT_BACKOFF_JITTER] sentinel (`Float.NaN`) triggers
     *  an internal PRNG sample (`Random.nextFloat() * 0.4f - 0.2f` →
     *  uniform ±20%) so production callers that omit the parameter GET
     *  jittered backoff (callers can't "forget"). Tests pass an explicit
     *  `0.0f` for determinism.
     * @return the computed next-delay in ms (always ≥ 0, ≤
     *  [BACKOFF_MAX_MS]).
     */
    fun scheduleBackoff(jitter: Float = DEFAULT_BACKOFF_JITTER): Long {
        // Round-2 M2: NaN sentinel = "sample jitter internally" so the
        // production default path is jittered without the caller having
        // to remember to pass a non-zero value.
        val sampled = if (jitter.isNaN()) {
            kotlin.random.Random.nextFloat() * 0.4f - 0.2f
        } else {
            jitter
        }
        // Clamp ±20% (mirrors [SseRecoveryPolicy.clampJitter] semantics;
        // inlined because SseRecoveryPolicy.clampJitter is an instance
        // member, not a companion function — and the poller deliberately
        // does not inject an SseRecoveryPolicy instance for a one-line
        // clamp). Belt-and-suspenders for the sampled path (the formula
        // above already produces values in range, but a future caller
        // could pass an out-of-range value explicitly).
        val j = sampled.coerceIn(-0.2f, 0.2f)
        return synchronized(stateLock) {
            val base = exponentialBackoffMs(backoffAttempt, BACKOFF_BASE_MS, BACKOFF_MAX_SHIFT)
            val next = SseRecoveryPolicy.applyJitter(base, j)
                .coerceAtMost(BACKOFF_MAX_MS)
                .coerceAtLeast(0L)
            pendingBackoffMs = next
            backoffAttempt = (backoffAttempt + 1).coerceAtMost(BACKOFF_MAX_SHIFT + 1)
            next
        }
    }

    /**
     * T13-C4 — reset the backoff state to base (no pending backoff).
     * Called by the coordinator's effect handler when a slim fan-out
     * sweep returned `retryableCount == 0` (success). Idempotent.
     */
    fun resetBackoff() {
        // §final-gate I-1 (oracle §3.5): a successful sweep arriving
        // before a pending retry fires MUST cancel the retry (no stale
        // retry stacking on top of fresh data). The retry job is
        // independent of the loop's generation — an explicit cancel
        // reaches it regardless of when it was scheduled.
        val retry = synchronized(stateLock) {
            backoffAttempt = 0
            pendingBackoffMs = 0L
            slimRetryJob.also { slimRetryJob = null }
        }
        retry?.cancel()
    }

    /**
     * T13-C4 — test/diagnostic accessor for the currently-pending backoff
     * delay. Returns 0 when no backoff is pending (the slim fan-out
     * scheduler uses the steady-state cadence).
     */
    fun currentBackoffDelayMs(): Long = synchronized(stateLock) { pendingBackoffMs }

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

    /**
     * §final-gate I-1 (oracle §3.3): the slim fan-out trigger helper.
     * Called from the immediate first poll site AND from each 30s tick
     * AND from [requestSlimFanOutRetry] (single-flight retry path).
     *
     * # Identity discipline (non-negotiable per oracle §3.3)
     *
     * A host switch during the network sweep invalidates every outcome —
     * the new host's id-space may overlap the old host's, but the
     * summary's per-sid outcomes refer to the OLD host's sessions. So:
     *   1. isCurrent check BEFORE entering the mutex (cheap fast-path;
     *      skip the sweep entirely when already stale);
     *   2. isCurrent check INSIDE the mutex (the sweep runs serially, so
     *      a host switch that landed between the outer check and mutex
     *      acquisition is caught here);
     *   3. isCurrent check AFTER the network sweep returns and BEFORE
     *      sinking the summary (a host switch during the network call
     *      invalidates the summary — drop it without invoking the sink).
     *
     * # CancellationException discipline
     *
     * CE is rethrown (per project convention). A non-CE throwable is
     * swallowed + logged + collapsed to null (the summary sink is NOT
     * invoked). This matches the [runRefresh] belt-and-suspenders style
     * — the slim-fan-out path must not kill the poller loop.
     */
    private suspend fun runSlimFanOut(
        identity: ConnectionIdentity,
        snapshot: StatusSnapshot,
    ) {
        if (!identityStore.isCurrent(identity)) return

        slimFanOutMutex.withLock {
            if (!identityStore.isCurrent(identity)) return@withLock

            val summary = try {
                slimFanOutRunner(identity, snapshot)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                DebugLog.w(TAG, "slim status fan-out failed: ${e.message}")
                null
            } ?: return@withLock

            // A host switch during the network sweep invalidates every outcome.
            if (!identityStore.isCurrent(identity)) return@withLock

            slimFanOutSummarySink(summary)
        }
    }

    /**
     * §final-gate I-1 (oracle §3.5): single-flight slim fan-out retry.
     * Called by AppCore's
     * [cn.vectory.ocdroid.ui.controller.ControllerEffect.RequestPollerBackoff]
     * effect handler with the bounded delay returned by [scheduleBackoff]
     * (200ms → 400ms → … → 30s cap).
     *
     * # Single-flight (non-negotiable per oracle §3.5)
     *
     * Each request cancels the prior retry job before launching the new
     * one — multiple backoff effects cannot stack overlapping timers.
     * This is enforced under [stateLock]: the prior job is captured AND
     * replaced atomically, then cancelled outside the lock (cancel is
     * safe to call on a job that has already completed).
     *
     * # Generation + identity re-checks (non-negotiable)
     *
     * Re-validates at EVERY await point: after the delay, before + after
     * the snapshot fetch, and inside [runSlimFanOut]. A [stop] /
     * superseding [startAndAwaitFirstPoll] bumps generation; a host
     * switch invalidates identity. Either invalidates this retry.
     */
    fun requestSlimFanOutRetry(delayMs: Long) {
        val identity = identityStore.currentIdentity.value ?: return
        val expectedGeneration = synchronized(stateLock) { generation }

        val retry = scope.launch {
            delay(delayMs.coerceAtLeast(0L))

            if (synchronized(stateLock) { generation != expectedGeneration }) {
                return@launch
            }
            if (!identityStore.isCurrent(identity)) return@launch

            val snapshot = snapshotProvider.current()

            if (synchronized(stateLock) { generation != expectedGeneration }) {
                return@launch
            }
            if (!identityStore.isCurrent(identity)) return@launch

            runSlimFanOut(identity, snapshot)
        }

        val prior = synchronized(stateLock) {
            slimRetryJob.also { slimRetryJob = retry }
        }
        prior?.cancel()
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

        // ── T13-C4 slim fan-out backoff strategy ─────────────────────────
        //
        // Pinned as `const val` so the strategy (200ms base, doubling, ±20%
        // jitter, 30s cap) is readable in one place AND so unit tests can
        // assert "scheduleBackoff grows exponentially" + "capped at
        // BACKOFF_MAX_MS" without magic numbers. Mirrors the
        // EXPAND_BACKOFF_* pinning in OpenCodeRepository.

        /**
         * T13-C4 backoff base delay in ms (jitter ±20%); doubles per
         * retry: 200 / 400 / 800 / 1600 / 3200 / 6400 / 12800 / 25600 →
         * capped at [BACKOFF_MAX_MS]. Mirrors
         * [cn.vectory.ocdroid.data.repository.OpenCodeRepository.EXPAND_BACKOFF_BASE_MS]
         * for cross-feature consistency.
         */
        const val BACKOFF_BASE_MS = 200L

        /**
         * T13-C4 backoff cap. Equals [DEFAULT_INTERVAL_MS] (30s) so the
         * slim fan-out scheduler never paces SLOWER than the steady-state
         * bulk loop — polling stays responsive even under sustained 503.
         */
        const val BACKOFF_MAX_MS = 30_000L

        /**
         * T13-C4 backoff max shift: log2([BACKOFF_MAX_MS] / [BACKOFF_BASE_MS])
         * ≈ 7.2 → shift 8 caps the exponent at 256x base. After this many
         * consecutive retryable sweeps the delay stays at [BACKOFF_MAX_MS]
         * (the [coerceAtMost] clamp in [scheduleBackoff]).
         */
        const val BACKOFF_MAX_SHIFT = 8

        /**
         * T13-C4 default jitter sentinel passed to [scheduleBackoff].
         * **Round-2 M2 fix:** `Float.NaN` is the sentinel that triggers
         * internal PRNG sampling (`Random.nextFloat() * 0.4f - 0.2f` →
         * uniform ±20%) so the production default path IS jittered
         * (callers cannot "forget" to jitter). Tests that need a
         * deterministic base pass `0.0f` explicitly; production callers
         * (e.g. AppCore's [RequestPollerBackoff] dispatch) omit the
         * parameter to get the sampled jitter.
         */
        const val DEFAULT_BACKOFF_JITTER: Float = Float.NaN
    }
}
