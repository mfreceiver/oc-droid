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
 * **TimedRefreshWithSlimFanOut** (post-v5.3-L5) — the background process-level
 * status data source. Driven by [ensureRunning] (called by
 * [cn.vectory.ocdroid.ui.controller.ConnectionCoordinator] on foreground→background).
 *
 * **L1 reality**: the FGS + [StreamingLifecycleCoordinator] + reconnect/bootstrap
 * supervisor were deleted in L1. The poller is now a **standalone** timed
 * refresher with a 30s loop: each tick re-fetches the
 * [SessionSnapshotProvider.current] snapshot, runs the bulk
 * [StatusAggregatorInput.refresh], then runs the slim fan-out sweep
 * ([runSlimFanOut]) over the latest session set.
 *
 * **Slim fan-out coverage**: the fan-out path (3-point identity discipline +
 * [slimFanOutRunner] + [slimFanOutSummarySink]) and the associated
 * retry/backoff API ([scheduleBackoff] / [resetBackoff] /
 * [requestSlimFanOutRetry] / [currentBackoffDelayMs]) are preserved and
 * driven by AppCore via [ControllerEffect.RequestPollerBackoff].
 *
 * **Single-flight**: at most one loop job + one slim retry job are alive at a
 * time. [stop] cancels both + bumps generation; [ensureRunning] with the same
 * [runningIdentity] is idempotent (no restart).
 *
 * **Identity + snapshot**: each loop iteration re-reads
 * [ConnectionIdentityStore.currentIdentity] + [SessionSnapshotProvider.current].
 * The immediate first poll uses the caller-captured identity + snapshot;
 * subsequent ticks re-fetch the snapshot.
 *
 * **Does NOT authorize SSE/FGS restart**: passive observer — refreshes the
 * status snapshot but does NOT call
 * [cn.vectory.ocdroid.service.StreamingServiceLauncher.ensureStarted].
 * SSE/Service recovery is by legal entry only.
 *
 * Construction: `@Singleton` + `internal constructor` (the clock default
 * param cannot be Hilt-provided directly — mirrors
 * [cn.vectory.ocdroid.service.status.StatusAggregatorImpl]'s pattern); the
 * `@Provides` in [StreamingModule] fills the clock.
 *
 * @param scope the process-lifetime [CoroutineScope] (D2: `@ApplicationScope` =
 *   SupervisorJob + Dispatchers.Default — survives Service.onDestroy).
 * @param statusAggregatorInput the §3 main-path refresh entry (REST +
 *   merge-timing + epoch guard).
 * @param snapshotProvider the §3 snapshot + registered-workdir coverage set.
 * @param identityStore the single process-level identity guard (§2 epoch).
 * @param statusAggregator the §3 read surface — [StatusAggregator.stateAtNow]
 *   reports the current bulk status verdict.
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
     * Read/written only from [startLoop] / [stop] (serial — driven by
     * concurrent-safety on [mutex] for start and [synchronized] for stop).
     */
    private var loopJob: Job? = null
    private val mutex = Mutex()
    private val stateLock = Any()
    private var generation: Long = 0L

    /**
     * The identity of the currently-running loop (or null when no loop is
     * active). Read/written under [stateLock]; cleared by [stop] so a
     * stale/superseded loop must not claim itself Running. Used by
     * [ensureRunning] for idempotent same-identity no-op.
     */
    @Volatile
    private var runningIdentity: ConnectionIdentity? = null

    /**
     * Slim on-demand fan-out backoff state. Tracks consecutive retryable
     * fan-out sweeps (503 / transport failures) so [scheduleBackoff]
     * produces a bounded exponential + jitter delay (200ms → 400ms → 800ms
     * → … capped at [BACKOFF_MAX_MS]=30s). [resetBackoff] returns the
     * state to base on a successful sweep. Guarded by [stateLock].
     */
    private var backoffAttempt: Int = 0
    private var pendingBackoffMs: Long = 0L

    /**
     * Single-flight retry job for the slim fan-out. Launched by
     * [requestSlimFanOutRetry] when AppCore receives a
     * [ControllerEffect.RequestPollerBackoff] effect. A new retry request
     * cancels the prior job. Cancelled by [stop] and [resetBackoff].
     */
    private var slimRetryJob: Job? = null

    /**
     * Serializes slim fan-out sweeps. Separate from [mutex] (which serializes
     * startLoop) so a sweep held on a network call does NOT block the
     * start/stop command path, and vice versa.
     */
    private val slimFanOutMutex = Mutex()

    /**
     * **Post-L5**: thin public wrapper preserved for existing test coverage
     * ([ProcessStatusPollerTest], [SlimFanOutPollerWiringTest],
     * [SlimFanOutRunnerGateTest]). Delegates to [startLoop] which contains
     * the core implementation shared with [ensureRunning].
     *
     * Returns [SourceActivation.Ready] on success, or
     * [SourceActivation.Rejected.StaleIdentity] /
     * [SourceActivation.Rejected.Superseded] if the identity/generation
     * guard rejects.
     */
    suspend fun startAndAwaitFirstPoll(
        identity: ConnectionIdentity,
        snapshot: StatusSnapshot,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
    ): SourceActivation = startLoop(identity, snapshot, intervalMs)

    /**
     * The single live entry (called by
     * [cn.vectory.ocdroid.ui.controller.ConnectionCoordinator] on
     * foreground→background). Tracks the actually-installed loop identity;
     * if the same identity is already running, returns Ready WITHOUT
     * cancel/restart (idempotent). Otherwise starts a fresh loop via
     * [startLoop] and awaits its first poll.
     *
     * @param identity the atomic identity capture from the command.
     * @param snapshot the atomic snapshot capture from the command.
     */
    suspend fun ensureRunning(
        identity: ConnectionIdentity,
        snapshot: StatusSnapshot,
    ): SourceActivation {
        // Fast path: same identity already running — no-op.
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
            return SourceActivation.Ready
        }
        return startLoop(identity, snapshot, DEFAULT_INTERVAL_MS)
    }

    /**
     * Core loop-start logic shared by [ensureRunning] and
     * [startAndAwaitFirstPoll]. Cancels + joins any prior poller
     * (single-flight), performs ONE immediate status refresh using the
     * caller-captured [identity] + [snapshot], launches the 30s loop for
     * subsequent polls, and returns [SourceActivation.Ready] on success.
     *
     * The immediate refresh + immediate slim fan-out fire before the loop
     * starts. Subsequent ticks (every [intervalMs]) re-fetch the snapshot
     * and repeat both calls.
     *
     * @param identity the atomic identity capture from the command.
     * @param snapshot the atomic snapshot capture from the command.
     * @param intervalMs the loop interval (default 30s — equals the §3
     *  status TTL).
     */
    private suspend fun startLoop(
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

        // Immediate slim fan-out alongside the immediate bulk refresh.
        runSlimFanOut(identity, snapshot)
        if (synchronized(stateLock) { generation != myGeneration }) {
            return@withLock SourceActivation.Rejected.Superseded
        }
        if (!identityStore.isCurrent(identity)) {
            return@withLock SourceActivation.Rejected.StaleIdentity
        }

        val firstState = statusAggregator.stateAtNow()
        DebugLog.i(TAG, "startLoop: first state=$firstState (identity epoch=${identity.epoch})")

        val newJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            while (isActive) {
                delay(intervalMs)
                if (synchronized(stateLock) { generation != myGeneration }) break
                if (!identityStore.isCurrent(identity)) break
                // Re-fetch snapshot (session list changes over time) and
                // reuse for both bulk refresh and slim fan-out.
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
        SourceActivation.Ready
    }

    /**
     * Stop the poller loop (no further refreshes). Idempotent: a second
     * call without an intervening start is a no-op. Cancels BOTH the loop
     * job and the slim fan-out retry job, bumps generation, and clears
     * [runningIdentity].
     *
     * Does NOT cancel the [ApplicationScope] itself — only the loop and
     * retry job.
     */
    fun stop() {
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
     * Schedule a bounded exponential + jitter backoff for the slim
     * on-demand fan-out's next sweep. Called by the coordinator's effect
     * handler when a slim fan-out sweep returned `retryableCount > 0`
     * (503 / transport fault).
     *
     * Each consecutive call DOUBLES the base delay
     * ([BACKOFF_BASE_MS] = 200ms → 200/400/800/…), shifted by the
     * current [backoffAttempt] (capped at [BACKOFF_MAX_SHIFT] so the
     * exponent stops growing once the cap binds). The jittered delay is
     * then clamped to [BACKOFF_MAX_MS] (= 30s — equals [DEFAULT_INTERVAL_MS]
     * so polling never goes SLOWER than the steady-state interval).
     *
     * ±20% jitter ([jitter] clamped to `[-0.2, +0.2]`). Default sentinel
     * `Float.NaN` triggers internal PRNG sampling.
     *
     * Returns the computed delay so callers can observe the value WITHOUT
     * a separate read-modify-write race.
     *
     * @param jitter a deterministic-injection point in `[-0.2, +0.2]`
     *  (production samples a PRNG; tests pass `0.0f` for the
     *  deterministic base schedule). Default `Float.NaN` triggers internal
     *  PRNG sampling (uniform ±20%).
     * @return the computed next-delay in ms (always ≥ 0, ≤
     *  [BACKOFF_MAX_MS]).
     */
    fun scheduleBackoff(jitter: Float = DEFAULT_BACKOFF_JITTER): Long {
        val sampled = if (jitter.isNaN()) {
            kotlin.random.Random.nextFloat() * 0.4f - 0.2f
        } else {
            jitter
        }
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
     * Reset the backoff state to base (no pending backoff). Called by the
     * coordinator's effect handler when a slim fan-out sweep returned
     * `retryableCount == 0` (success). Idempotent. Also cancels any
     * pending retry job.
     */
    fun resetBackoff() {
        val retry = synchronized(stateLock) {
            backoffAttempt = 0
            pendingBackoffMs = 0L
            slimRetryJob.also { slimRetryJob = null }
        }
        retry?.cancel()
    }

    /**
     * Test/diagnostic accessor for the currently-pending backoff delay.
     * Returns 0 when no backoff is pending.
     */
    fun currentBackoffDelayMs(): Long = synchronized(stateLock) { pendingBackoffMs }

    private suspend fun runRefresh(identity: ConnectionIdentity, snapshot: StatusSnapshot) {
        try {
            statusAggregatorInput.refresh(identity, snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DebugLog.w(TAG, "runRefresh failed: ${e.message}")
            statusAggregatorInput.markRequestFailed(identity, snapshot, clock())
        }
    }

    /**
     * The slim fan-out trigger helper. Called from the immediate first poll
     * site AND from each 30s tick AND from [requestSlimFanOutRetry]
     * (single-flight retry path).
     *
     * # Identity discipline (non-negotiable)
     *
     * A host switch during the network sweep invalidates every outcome:
     *   1. isCurrent check BEFORE entering the mutex;
     *   2. isCurrent check INSIDE the mutex;
     *   3. isCurrent check AFTER the network sweep returns and BEFORE
     *      sinking the summary.
     *
     * # CancellationException discipline
     *
     * CE is rethrown (per project convention). A non-CE throwable is
     * swallowed + logged + collapsed to null (the summary sink is NOT
     * invoked).
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

            if (!identityStore.isCurrent(identity)) return@withLock

            slimFanOutSummarySink(summary)
        }
    }

    /**
     * Single-flight slim fan-out retry. Called by AppCore's
     * [ControllerEffect.RequestPollerBackoff] effect handler with
     * the bounded delay returned by [scheduleBackoff].
     *
     * Each request cancels the prior retry job before launching the new
     * one. Re-validates at EVERY await point: after the delay, before +
     * after the snapshot fetch, and inside [runSlimFanOut].
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

        /** 30s poller interval — equals the §3 status TTL. */
        const val DEFAULT_INTERVAL_MS = 30_000L

        // ── T13-C4 slim fan-out backoff strategy ─────────────────────────

        /** 200ms base delay for the exponential backoff (jitter ±20%). */
        const val BACKOFF_BASE_MS = 200L

        /** 30s cap — equals [DEFAULT_INTERVAL_MS]. */
        const val BACKOFF_MAX_MS = 30_000L

        /**
         * log2([BACKOFF_MAX_MS] / [BACKOFF_BASE_MS]) ≈ 7.2 → shift 8 caps
         * the exponent at 256x base.
         */
        const val BACKOFF_MAX_SHIFT = 8

        /**
         * Default jitter sentinel. `Float.NaN` triggers internal PRNG
         * sampling (uniform ±20%). Tests pass `0.0f` for deterministic
         * base.
         */
        const val DEFAULT_BACKOFF_JITTER: Float = Float.NaN
    }
}
