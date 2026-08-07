package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.di.ApplicationScope
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.status.SlimFanOutBackoffPolicy
import cn.vectory.ocdroid.service.status.StatusFanOutSummary
import cn.vectory.ocdroid.service.status.StatusSnapshot
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Singleton

/**
 * **SlimFanOutRetryScheduler** — the preserved backoff + single-flight-retry
 * seam from the former `ProcessStatusPoller` (architecture-debt Batch 1 item 17).
 *
 * The dead 30s loop machinery (startLoop/ensureRunning/startAndAwaitFirstPoll/
 * runRefresh/stop) was deleted — that background-polling design was
 * deliberately rejected in Phase 1 (后台驻留移除). What remains is the backoff
 * state machine and the single-flight retry path that fires a slim fan-out
 * sweep via [SlimFanOutBackoffPolicy]-governed delays.
 *
 * ## Preserved API
 * - [scheduleBackoff] — bounded exponential + jitter backoff.
 * - [resetBackoff] — backoff state to base + cancel pending retry.
 * - [requestSlimFanOutRetry] — single-flight retry with 3-point identity
 *   discipline (identityStore.isCurrent).
 * - [runSlimFanOut] — the core fan-out sweep (3-point identity discipline +
 *   slimFanOutMutex + CE discipline).
 *
 * ## Re-enablement vector
 * The fan-out circuit currently has **no production entry trigger**: the
 * runner is gated off by `serverCompatProfile.slimPerSessionStatusEndpointAvailable`
 * (default false, see [StreamingModule]). A future entry point would call
 * `requestSlimFanOutRetry(0)` from a new foreground-degraded-polling path.
 * See AppCore kdoc and StreamingModule kdoc.
 *
 * @param scope the process-lifetime [CoroutineScope] (D2: `@ApplicationScope` =
 *   SupervisorJob + Dispatchers.Default).
 * @param snapshotProvider the snapshot + registered-workdir coverage set.
 * @param identityStore the single process-level identity guard (§2 epoch).
 * @param slimFanOutRunner injectable slim-mode status fan-out runner. Returns
 *   null when slim mode is off (legacy gate) or on network failure.
 *   Defaults to `{ _, _ -> null }` so existing direct tests keep compiling.
 * @param slimFanOutSummarySink sink for a non-null fan-out summary. Production
 *   routes through [SessionSyncCoordinator.applySlimStatusFanOutSummary].
 *   Default `{}` preserves existing direct tests.
 */
@Singleton
class SlimFanOutRetryScheduler internal constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
    private val snapshotProvider: SessionSnapshotProvider,
    private val identityStore: ConnectionIdentityStore,
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
     * scheduler captured for the retry; the runner is responsible for its OWN
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
     * [SessionSyncCoordinator.applySlimStatusFanOutSummary]
     * which emits per-sid `EvictSession` effects (404) + the scheduler
     * backoff/reset effect (503 / success). Default `{}` preserves
     * existing direct tests.
     */
    private val slimFanOutSummarySink:
        (StatusFanOutSummary) -> Unit =
        {},
) {
    private val stateLock = Any()

    /**
     * Slim on-demand fan-out backoff state. Tracks consecutive retryable
     * fan-out sweeps (503 / transport failures) so [scheduleBackoff]
     * produces a bounded exponential + jitter delay (200ms → 400ms → 800ms
     * → … capped at [SlimFanOutBackoffPolicy.BACKOFF_MAX_MS]=30s).
     * [resetBackoff] returns the state to base on a successful sweep.
     * Guarded by [stateLock].
     */
    private var backoffAttempt: Int = 0
    private var pendingBackoffMs: Long = 0L

    /**
     * Single-flight retry job for the slim fan-out. Launched by
     * [requestSlimFanOutRetry] when AppCore receives a
     * [ControllerEffect.RequestPollerBackoff] effect. A new retry request
     * cancels the prior job. Cancelled by [resetBackoff].
     */
    private var slimRetryJob: Job? = null

    /**
     * Serializes slim fan-out sweeps. A sweep held on a network call does
     * NOT block the backoff/reset/retry command path.
     */
    private val slimFanOutMutex = Mutex()

    /**
     * Schedule a bounded exponential + jitter backoff for the slim
     * on-demand fan-out's next sweep. Called by the coordinator's effect
     * handler when a slim fan-out sweep returned `retryableCount > 0`
     * (503 / transport fault).
     *
     * Each consecutive call DOUBLES the base delay
     * ([SlimFanOutBackoffPolicy.BACKOFF_BASE_MS] = 200ms → 200/400/800/…),
     * shifted by the current [backoffAttempt] (capped at
     * [SlimFanOutBackoffPolicy.BACKOFF_MAX_SHIFT] so the exponent stops
     * growing once the cap binds). The jittered delay is then clamped to
     * [SlimFanOutBackoffPolicy.BACKOFF_MAX_MS] (= 30s).
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
     *  [SlimFanOutBackoffPolicy.BACKOFF_MAX_MS]).
     */
    fun scheduleBackoff(jitter: Float = SlimFanOutBackoffPolicy.DEFAULT_BACKOFF_JITTER): Long {
        return synchronized(stateLock) {
            val next = SlimFanOutBackoffPolicy.computeDelayMs(backoffAttempt, jitter)
            pendingBackoffMs = next
            backoffAttempt = (backoffAttempt + 1).coerceAtMost(SlimFanOutBackoffPolicy.BACKOFF_MAX_SHIFT + 1)
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

        val retry = scope.launch {
            delay(delayMs.coerceAtLeast(0L))

            if (!identityStore.isCurrent(identity)) return@launch

            val snapshot = snapshotProvider.current()

            if (!identityStore.isCurrent(identity)) return@launch

            runSlimFanOut(identity, snapshot)
        }

        val prior = synchronized(stateLock) {
            slimRetryJob.also { slimRetryJob = retry }
        }
        prior?.cancel()
    }

    /**
     * The slim fan-out trigger helper. Called from [requestSlimFanOutRetry]
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

    companion object {
        private const val TAG = "SlimFanOutRetryScheduler"
    }
}
