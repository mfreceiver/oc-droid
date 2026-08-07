package cn.vectory.ocdroid.service.status

import cn.vectory.ocdroid.util.exponentialBackoffMs

/**
 * Shared backoff policy for the slim fan-out retry scheduler and the
 * [StatusFanOutApplier]'s per-sid [RetryQueued] nominal backoff.
 *
 * The constants and computeDelayMs logic are the math formerly inlined in
 * the deleted `ProcessStatusPoller.scheduleBackoff` (Batch 1 item 17).
 */
object SlimFanOutBackoffPolicy {

    /** 200ms base delay for the exponential backoff (jitter ±20%). */
    const val BACKOFF_BASE_MS = 200L

    /** 30s cap. */
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

    /**
     * Pure: exponential + jitter + cap. jitter in [-0.2,+0.2] (clamped);
     * the jitter fold `(base*(1+j)).toLong().coerceAtLeast(0)` is the same shape
     * formerly in the deleted `SseRecoveryPolicy.applyJitter` (inlined here to
     * keep service/status free of a service.streaming import).
     *
     * @param jitter a deterministic-injection point in `[-0.2, +0.2]`
     *   (production samples a PRNG; tests pass `0.0f` for the
     *   deterministic base schedule). Default [DEFAULT_BACKOFF_JITTER]
     *   triggers internal PRNG sampling (uniform ±20%).
     * @return the computed delay in ms (always ≥ 0, ≤ [BACKOFF_MAX_MS]).
     */
    fun computeDelayMs(attempt: Int, jitter: Float): Long {
        val sampled = if (jitter.isNaN()) {
            kotlin.random.Random.nextFloat() * 0.4f - 0.2f
        } else {
            jitter
        }
        val j = sampled.coerceIn(-0.2f, 0.2f)
        val base = exponentialBackoffMs(attempt, BACKOFF_BASE_MS, BACKOFF_MAX_SHIFT)
        val jittered = (base * (1.0f + j)).toLong().coerceAtLeast(0L)
        return jittered.coerceAtMost(BACKOFF_MAX_MS).coerceAtLeast(0L)
    }
}
