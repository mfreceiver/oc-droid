package cn.vectory.ocdroid.util

/**
 * Pure exponential backoff base calculation — no jitter, no result cap.
 *
 * Returns `baseMs * 2^min(attempt, maxShift)` as [Long].
 *
 * @param attempt 0-based attempt counter (0 → first retry at 1x base).
 * @param baseMs  base delay in milliseconds.
 * @param maxShift maximum left-shift exponent (caps the growth).
 * @return base exponential delay in milliseconds.
 */
internal fun exponentialBackoffMs(attempt: Int, baseMs: Long, maxShift: Int): Long {
    val shift = attempt.coerceAtMost(maxShift)
    return baseMs * (1L shl shift)
}
