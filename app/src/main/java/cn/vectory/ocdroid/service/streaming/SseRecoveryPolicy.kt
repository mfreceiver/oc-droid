package cn.vectory.ocdroid.service.streaming

import javax.inject.Inject
import javax.inject.Singleton

/**
 * D2 (gate #7 / §5 step 6): a ±20% jitter delay schedule over a 3-step
 * `30s / 2m / 5m` base — extracted as an injectable seam so delay math is
 * unit-testable without wall-clock waits.
 *
 * **L2 reality** (see [StreamingModule] L2 removals): the §5 step 6
 * **service-level SSE retry loop died** — [ServiceSseConnectionOwner]'s
 * collector is now a single attempt with NO service-level retry. So this
 * class NO LONGER drives SSE collector retries, and the 3-step budget does
 * NOT gate [SourceActivation.Rejected.Exhausted] (which fires on the FIRST
 * pre-ready break/completion — see [SourceActivation.Rejected.Exhausted],
 * NOT after 3 retries). The class survives as a schedule + jitter utility:
 *  - Production's sole main-source consumer is
 *    [ProcessStatusPoller.scheduleBackoff], which uses the companion
 *    [applyJitter] helper for its slim-fan-out backoff (a SEPARATE
 *    200ms-base exponential, NOT this 30s/2m/5m schedule — only the jitter
 *    math is shared).
 *  - The instance API ([attempts] / [baseDelayMs] / [delayMs]) is preserved
 *    for [SseRecoveryPolicyTest] and as a future reintroduction seam if the
 *    service-level retry is ever restored.
 *
 * **Contract**: the default schedule produces `30s / 2m / 5m` + ±20% jitter
 * (deterministic when [delayMs] receives `jitter = 0.0`, which the unit-test
 * fake does — production supplies a `Random`-backed implementation). Pure:
 * given (attempt, jitter) it returns the delay; no I/O; no clock side-effects.
 * [baseDelayMs] requires `attempt in 1..attempts`.
 */
@Singleton
open class SseRecoveryPolicy @Inject constructor() {

    /**
     * The schedule arity (number of delay steps). §5 step 6 fixes this at 3
     * (`30s / 2m / 5m`); extracted as `open val` (not a `const`) so a test
     * subclass can override if a faster schedule is needed (the production
     * schedule MUST stay at 3 — the spec's `30s / 2m / 5m` budget is a
     * product decision, not an implementation detail). NOTE: since L2 this
     * counts delay STEPS in the schedule utility, NOT live SSE collector
     * retries — see class kdoc.
     */
    open val attempts: Int = DEFAULT_ATTEMPTS

    /**
     * The unmodified delay for the [attempt]-th schedule step (BEFORE
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
        /** §5 step 6 schedule arity: 3 delay steps (`30s / 2m / 5m`). */
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
