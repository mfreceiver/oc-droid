package cn.vectory.ocdroid.ui.controller.sse

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exponential backoff ladder math + per-sid attempt counters.
 *
 * Pure computation — no coroutines, no lifecycle dependencies. Uses a
 * [ConcurrentHashMap] for per-sid isolation.
 *
 * @param initialBackoffMs Seed delay for the first reconnect attempt.
 * @param maxBackoffMs Cap for the backoff delay.
 * @param backoffMultiplier Growth factor per attempt (e.g. 2.0 = exponential).
 * @param lock The shared [bundleCommitLock] monitor (received for consistency
 *   with the other split components; methods are safe with CHM alone).
 */
internal class ReconnectPolicy(
    private val initialBackoffMs: Long,
    private val maxBackoffMs: Long,
    private val backoffMultiplier: Double,
    @Suppress("UNUSED_PARAMETER") private val lock: Any,
) {
    private val attemptBySid = ConcurrentHashMap<String, AtomicInteger>()

    /** Resets the attempt counter for [sid] to zero (alive link indicator). */
    fun resetAttempts(sid: String) {
        attemptBySid[sid]?.set(0)
    }

    /**
     * Returns the current backoff delay for [sid] and atomically advances the
     * attempt counter. Combines getAndIncrement with the backoff formula:
     * `initialBackoffMs × multiplier^attempt`, capped at [maxBackoffMs].
     */
    fun nextDelayMs(sid: String): Long {
        val attempt = attemptBySid.computeIfAbsent(sid) { AtomicInteger(0) }.getAndIncrement()
        val raw = (initialBackoffMs * Math.pow(backoffMultiplier, attempt.toDouble())).toLong()
        return raw.coerceAtMost(maxBackoffMs)
    }

    /** Removes all state for [sid] (stream teardown). */
    fun clearSid(sid: String) {
        attemptBySid.remove(sid)
    }
}
