package cn.vectory.ocdroid.service.streaming

import org.junit.Assert.assertEquals
import org.junit.Test

class SseRecoveryPolicyTest {
    private val policy = SseRecoveryPolicy()

    @Test
    fun `schedule is exactly 30 seconds 2 minutes and 5 minutes`() {
        assertEquals(30_000L, policy.delayMs(1, 0f))
        assertEquals(120_000L, policy.delayMs(2, 0f))
        assertEquals(300_000L, policy.delayMs(3, 0f))
    }

    @Test
    fun `jitter is clamped to plus or minus twenty percent`() {
        assertEquals(24_000L, policy.delayMs(1, -1f))
        assertEquals(36_000L, policy.delayMs(1, 1f))
    }
}
