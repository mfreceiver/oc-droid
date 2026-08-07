package cn.vectory.ocdroid.ui.controller.sse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ReconnectPolicy].
 *
 * Verifies:
 *  - exponential backoff ladder: 1s → 2s → … → 30s cap
 *  - [resetAttempts] rebases the counter
 *  - per-sid isolation
 *  - [clearSid] removes state
 */
class ReconnectPolicyTest {

    private val lock = Any()
    private val policy = ReconnectPolicy(
        initialBackoffMs = 1_000L,
        maxBackoffMs = 30_000L,
        backoffMultiplier = 2.0,
        lock = lock,
    )

    @Test
    fun `backoff ladder doubles each attempt up to cap`() {
        // attempt 0 → 1_000ms
        assertEquals(1_000L, policy.nextDelayMs("s1"))
        // attempt 1 → 2_000ms
        assertEquals(2_000L, policy.nextDelayMs("s1"))
        // attempt 2 → 4_000ms
        assertEquals(4_000L, policy.nextDelayMs("s1"))
        // attempt 3 → 8_000ms
        assertEquals(8_000L, policy.nextDelayMs("s1"))
        // attempt 4 → 16_000ms
        assertEquals(16_000L, policy.nextDelayMs("s1"))
        // attempt 5 → 32_000ms, capped at 30_000ms
        assertEquals(30_000L, policy.nextDelayMs("s1"))
        // attempt 6+ → stays at cap
        assertEquals(30_000L, policy.nextDelayMs("s1"))
    }

    @Test
    fun `resetAttempts rebases the counter`() {
        // Advance a few attempts
        policy.nextDelayMs("s2") // 0 → 1s
        policy.nextDelayMs("s2") // 1 → 2s
        assertEquals(4_000L, policy.nextDelayMs("s2")) // 2 → 4s

        policy.resetAttempts("s2")
        // After reset, next should be attempt 0 again
        assertEquals(1_000L, policy.nextDelayMs("s2"))
    }

    @Test
    fun `per-sid isolation — counter does not leak between sids`() {
        policy.nextDelayMs("s3") // s3 attempt 0 → 1s
        policy.nextDelayMs("s4") // s4 attempt 0 → 1s (independent)
        assertEquals(2_000L, policy.nextDelayMs("s3")) // s3 attempt 1 → 2s
        assertEquals(2_000L, policy.nextDelayMs("s4")) // s4 attempt 1 → 2s
    }

    @Test
    fun `clearSid removes state for the given sid`() {
        policy.nextDelayMs("s5")
        policy.nextDelayMs("s6")
        policy.clearSid("s5")
        // After clear, s5 starts fresh
        assertEquals(1_000L, policy.nextDelayMs("s5"))
        // s6 is unaffected
        assertEquals(2_000L, policy.nextDelayMs("s6"))
    }
}
