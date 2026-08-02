package cn.vectory.ocdroid.di

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Single source of truth for notification channel ids + creation (§18.1 +
 * AppLifecycleMonitor notifier surface). Extracted from AppLifecycleMonitor's
 * companion (refactor L1b) so every notifier references one canonical home.
 *
 * Channel ids are module-visible [const val]; the descriptive name/desc
 * strings + [TAG] are private. [createChannels] is idempotent (re-creating a
 * channel with the same id is a platform no-op) and invoked once from
 * [cn.vectory.ocdroid.OpenCodeApp.onCreate].
 *
 * L1 FGS deletion: the former `CHANNEL_SESSION_STATUS` /
 * `CHANNEL_SESSION_STATUS_MIN` channels (ongoing FGS notification surface)
 * were removed with [SessionStreamingService]. Only the three user-facing
 * channels used by [AppLifecycleMonitor] (decisions / idle / errors) remain.
 */
internal object NotificationChannels {

    const val CHANNEL_DECISIONS = "ocdroid.decisions"
    const val CHANNEL_IDLE = "ocdroid.idle"
    const val CHANNEL_ERRORS = "ocdroid.errors"

    // Hardcoded channel/notification strings. v2-redesign write-domain
    // excludes res/values/strings.xml, so we keep these literals inline
    // rather than fragmenting the lane by touching resources. A future
    // i18n pass can promote them to R.string entries.
    private const val CHANNEL_DECISIONS_NAME = "opencode 决策"
    private const val CHANNEL_DECISIONS_DESC = "来自 opencode 会话的权限和问题提示"
    private const val CHANNEL_IDLE_NAME = "opencode 完成"
    private const val CHANNEL_IDLE_DESC = "已完成、待审查的会话"
    private const val CHANNEL_ERRORS_NAME = "opencode 错误"
    private const val CHANNEL_ERRORS_DESC = "来自 opencode 的连接和运行时错误"

    private const val TAG = "NotificationChannels"

    /**
     * Creates the notification channels required by §18.1. Wrapped
     * in try/catch and only invoked on API 26+ (NotificationChannel was
     * added in O). Channels are idempotent — re-creating with the same
     * ID is a no-op. Called from [cn.vectory.ocdroid.OpenCodeApp.onCreate].
     */
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return@runCatching
            val decisions = NotificationChannel(
                CHANNEL_DECISIONS,
                CHANNEL_DECISIONS_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DECISIONS_DESC
                enableVibration(true)
                setSound(
                    Settings.System.DEFAULT_NOTIFICATION_URI,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build(),
                )
            }
            val idle = NotificationChannel(
                CHANNEL_IDLE,
                CHANNEL_IDLE_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_IDLE_DESC
                enableVibration(true)
                setSound(
                    Settings.System.DEFAULT_NOTIFICATION_URI,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build(),
                )
            }
            val errors = NotificationChannel(
                CHANNEL_ERRORS,
                CHANNEL_ERRORS_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = CHANNEL_ERRORS_DESC
            }
            manager.createNotificationChannels(listOf(decisions, idle, errors))
        }.onFailure { Log.w(TAG, "Failed to create notification channels", it) }
    }
}
