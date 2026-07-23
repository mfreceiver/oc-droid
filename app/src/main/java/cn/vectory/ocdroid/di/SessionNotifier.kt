package cn.vectory.ocdroid.di

import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cn.vectory.ocdroid.MainActivity
import cn.vectory.ocdroid.R

/**
 * T5-C4b: shared publish point for the §18 decision/idle notifications.
 *
 * Extracted verbatim from the prior private methods on [AppLifecycleMonitor]
 * so the SSE-side bridge ([cn.vectory.ocdroid.service.streaming.SseNotificationBridge])
 * can call the SAME publish logic the 30s poller uses. The notification id
 * is always `key.hashCode()`; both call sites pass the SAME key for the
 * SAME logical event so a bridge-fired notification and a poller-fired
 * notification for one item visually replace each other instead of stacking
 * (the shared dedup set is the primary guard; the matched id is the
 * visual fallback).
 *
 * Internal visibility: the bridge lives in a sister package and is built
 * only by [SessionStreamingService] (the same module). Not a Hilt-provided
 * object — ALM owns one instance and exposes it via
 * [AppLifecycleMonitor.notifier].
 */
internal class SessionNotifier internal constructor(
    private val application: Application,
    private val notificationManagerCompat: NotificationManagerCompat,
) {
    /**
     * Bug-1 (notification regeneration): belt-and-suspenders guard on top of
     * the persisted dedup. Even if the dedup state is somehow lost or
     * inconsistent (e.g. the persisted SharedPreferences was cleared by the
     * user, or process death happened between claim and seed), re-posting a
     * notification whose shade entry already exists would replay
     * `SOUND|VIBRATE` and confuse the user. Before posting we check whether a
     * shade entry with `id = key.hashCode()` already exists; if so, we treat
     * it as already-posted (return `true`) and skip the rebuild.
     *
     * `getActiveNotifications()` is API 23+; [AppLifecycleMonitor] targets
     * minSdk=34 so no API guard is required.
     */
    private val notificationManager: NotificationManager =
        application.getSystemService(NotificationManager::class.java)

    /**
     * Returns `true` when a notification was actually posted (permission
     * granted + [NotificationManagerCompat.notify] invoked); `false` when
     * suppressed by a missing/denied permission. Callers use the result to
     * decide whether to record the item in their dedup set (a `false` return
     * MUST roll back the dedup claim so a later attempt can retry — see
     * [NotificationDedup]).
     */
    @Suppress("MissingPermission") // see hasNotificationPermission() guard
    fun notifyDecision(sessionId: String, title: String, body: String, key: String): Boolean {
        if (!hasNotificationPermission()) return false
        val notificationId = key.hashCode()
        // Bug-1: a shade entry with this id already exists — treat as posted.
        // Bug-1-fix-A (channel-scoped guard): ALSO match channelId so a
        // theoretical hashCode collision with the FGS ongoing notification
        // (id 4241) or the error notification (id 4242) cannot suppress a
        // genuine new decision. Restricted to CHANNEL_DECISIONS only.
        if (notificationManager.activeNotifications.any {
                it.id == notificationId &&
                    it.notification.channelId == NotificationChannels.CHANNEL_DECISIONS
            }) {
            Log.d(TAG, "notifyDecision suppressed (activeNotifications hit) key=$key")
            return true
        }
        val notification = NotificationCompat.Builder(application, NotificationChannels.CHANNEL_DECISIONS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .setAutoCancel(true)
            .setContentIntent(buildContentIntent(sessionId, notificationId))
            .build()
        // §notify-fix: a thrown notify() (channel missing / transient
        // SecurityException) must report failure so the caller does NOT
        // record the item in its dedup set — otherwise it would be
        // permanently suppressed and never re-notified once the condition
        // recovers. isSuccess is false on any thrown exception.
        return runCatching { notificationManagerCompat.notify(notificationId, notification) }.isSuccess
    }

    @Suppress("MissingPermission") // see hasNotificationPermission() guard
    fun notifyIdle(rootId: String, title: String, key: String): Boolean {
        if (!hasNotificationPermission()) return false
        val notificationId = key.hashCode()
        // Bug-1: a shade entry with this id already exists — treat as posted.
        // Bug-1-fix-A (channel-scoped guard): ALSO match channelId so a
        // theoretical hashCode collision with the FGS ongoing notification
        // (id 4241) or the error notification (id 4242) cannot suppress a
        // genuine new idle. Restricted to CHANNEL_IDLE only.
        if (notificationManager.activeNotifications.any {
                it.id == notificationId &&
                    it.notification.channelId == NotificationChannels.CHANNEL_IDLE
            }) {
            Log.d(TAG, "notifyIdle suppressed (activeNotifications hit) key=$key")
            return true
        }
        val notification = NotificationCompat.Builder(application, NotificationChannels.CHANNEL_IDLE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(AppLifecycleMonitor.NOTIF_IDLE_TITLE)
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .setAutoCancel(true)
            .setContentIntent(buildContentIntent(rootId, notificationId))
            .build()
        return runCatching { notificationManagerCompat.notify(notificationId, notification) }.isSuccess
    }

    /**
     * Builds the deep-link [PendingIntent] that opens [MainActivity] on the
     * given session/root id. Internal so the bridge (which constructs its
     * own notifications only when the publisher is mocked in tests) does not
     * need to reach in here in production — the bridge delegates to
     * [notifyDecision] / [notifyIdle], which call this internally.
     */
    internal fun buildContentIntent(sessionId: String, requestCode: Int): PendingIntent {
        val intent = Intent(application, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getActivity(
            application,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun hasNotificationPermission(): Boolean {
        // POST_NOTIFICATIONS is runtime on API 33+; granted-bypass on older.
        return NotificationManagerCompat.from(application).areNotificationsEnabled()
    }

    private companion object {
        const val TAG = "SessionNotifier"
    }
}
