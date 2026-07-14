package cn.vectory.ocdroid.service.streaming

import javax.inject.Inject
import javax.inject.Singleton

/**
 * D2 (gate #7 / §5 step 6): the service-level SSE recovery schedule, extracted
 * as an injectable seam so [ServiceSseConnectionOwner]'s post-SSEClient-exhaustion
 * retry cadence is unit-testable without wall-clock delays.
 *
 * The §5 step 6 spec mandates **3 additional collector attempts** after the
 * SSEClient's internal 10-attempt exhaustion, with delays `30s / 2m / 5m` and
 * `±20%` jitter. Each retry is a fresh `repository.connectSSE(workdir)` flow
 * (which itself restarts the SSEClient's internal 10-attempt budget); the
 * first valid current-identity frame across any attempt completes readiness
 * ([SourceActivation.Ready]) and resets the service-retry budget; only after
 * all 3 attempts exhaust without a frame does the owner invoke
 * [StreamingLifecycleCoordinator.onDisconnect] / emit
 * [SourceActivation.Rejected.Exhausted] (exactly once per outage).
 *
 * The default schedule produces 30s / 2m / 5m + ±20% jitter (deterministic
 * when [jitter] returns 0.0, which the unit-test fake does — production
 * supplies a `Random`-backed implementation). The contract is pure: given
 * (attempt, jitter) it returns the delay; no I/O; no clock side-effects.
 *
 * @param attempt the 1-based retry index (`attempt = 1` is the FIRST retry
 *  after the initial collector attempt fails; `attempt = 3` is the LAST
 *  retry before exhaustion).
 * @param jitter a deterministic-injection point in `[-0.2, +0.2]`
 *  (production samples a PRNG; tests pass `0.0` to read the unmodified
 *  schedule). The jittered delay is `(base * (1 + jitter)).toLong()`.
 */
@Singleton
open class SseRecoveryPolicy @Inject constructor() {

    /**
     * The number of service-level retries AFTER the initial collector attempt.
     * §5 step 6 fixes this at 3; extracted as `open val` (not a `const`) so a
     * test subclass can override if a faster schedule is needed (the
     * production schedule MUST stay at 3 — the spec's `30s / 2m / 5m`
     * budget is a product decision, not an implementation detail).
     */
    open val attempts: Int = DEFAULT_ATTEMPTS

    /**
     * The unmodified delay for the [attempt]-th service-level retry (BEFORE
     * jitter). [DEFAULT_SCHEDULE_MS] is the spec's `30s / 2m / 5m`; tests
     * override via subclass for virtual-time determinism.
     */
    open fun baseDelayMs(attempt: Int): Long {
        require(attempt in 1..attempts) {
            "attempt $attempt out of range [1..$attempts]"
        }
        return DEFAULT_SCHEDULE_MS[attempt - 1]
    }

    /**
     * Final delay applied before retry [attempt], with [jitter] folded in.
     * `jitter ∈ [-0.2, +0.2]`; outside that range is clamped (defensive —
     * the contract is ±20% so a buggy test fake does not produce negative
     * delays).
     */
    fun delayMs(attempt: Int, jitter: Float): Long {
        val base = baseDelayMs(attempt)
        val jitterClamped = jitter.coerceIn(-0.2f, 0.2f)
        return (base * (1.0f + jitterClamped)).toLong().coerceAtLeast(0L)
    }

    /**
     * Convenience for tests: deterministic (no jitter) delay schedule.
     */
    fun delayMs(attempt: Int): Long = delayMs(attempt, 0.0f)

    /**
     * The default ±20% jitter fraction (informational; production samples
     * its own source). Exposed for documentation + tests that assert the
     * clamping boundary.
     */
    fun clampJitter(jitter: Float): Float = jitter.coerceIn(-0.2f, 0.2f)

    companion object {
        /** §5 step 6: 3 service-level retries past the SSEClient's budget. */
        const val DEFAULT_ATTEMPTS = 3

        /** §5 step 6 unmodified schedule: 30s / 2m / 5m. */
        val DEFAULT_SCHEDULE_MS: LongArray = longArrayOf(30_000L, 120_000L, 300_000L)

        /**
         * Convenience for callers that round their own jitter to a delay
         * (production uses [delayMs]).
         */
        fun applyJitter(base: Long, jitter: Float): Long {
            val j = jitter.coerceIn(-0.2f, 0.2f)
            return (base * (1.0f + j)).toLong().coerceAtLeast(0L)
        }
    }
}
