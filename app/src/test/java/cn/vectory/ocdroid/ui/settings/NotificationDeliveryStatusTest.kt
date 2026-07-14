package cn.vectory.ocdroid.ui.settings

import android.app.NotificationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDeliveryStatusTest {
    @Test
    fun `runtime grant alone is insufficient when app notifications are disabled`() {
        assertFalse(notificationDeliveryEnabled(true, false, listOf(NotificationManager.IMPORTANCE_HIGH)))
    }

    @Test
    fun `all relevant channels disabled reports blocked`() {
        assertFalse(notificationDeliveryEnabled(true, true, listOf(NotificationManager.IMPORTANCE_NONE)))
    }

    @Test
    fun `enabled app and at least one relevant channel reports allowed`() {
        assertTrue(notificationDeliveryEnabled(true, true, listOf(NotificationManager.IMPORTANCE_NONE, NotificationManager.IMPORTANCE_HIGH)))
    }
}
