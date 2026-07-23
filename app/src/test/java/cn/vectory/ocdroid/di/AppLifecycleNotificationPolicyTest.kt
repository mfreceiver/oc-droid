package cn.vectory.ocdroid.di

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLifecycleNotificationPolicyTest {
    @Test
    fun `idle alert is eligible only in background and once per cycle key`() {
        assertFalse(shouldPostIdleNotification(true, "idle:a", emptySet()))
        assertFalse(shouldPostIdleNotification(false, "idle:a", setOf("idle:a")))
        assertTrue(shouldPostIdleNotification(false, "idle:a", emptySet()))
    }

    @Test
    fun `decisions and idle channels are independently configurable`() {
        assertNotEquals(NotificationChannels.CHANNEL_DECISIONS, NotificationChannels.CHANNEL_IDLE)
    }

    @Test
    fun `idle notification snapshot pruning keeps only active cycle keys`() {
        val notified = mutableSetOf("idle:old", "idle:active")

        pruneIdleNotificationSnapshot(notified, setOf("idle:active", "idle:new"))

        assertTrue("idle:active" in notified)
        assertFalse("idle:old" in notified)
    }
}
