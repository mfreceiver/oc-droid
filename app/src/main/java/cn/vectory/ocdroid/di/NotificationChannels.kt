package cn.vectory.ocdroid.di

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Single source of truth for notification channel ids + creation (§18.1 + §7
 * FGS channel matrix / dev-design P1.4). Extracted from AppLifecycleMonitor's
 * companion (refactor L1b) so every notifier references one canonical home.
 *
 * Channel ids are module-visible [const val]; the descriptive name/desc
 * strings + [TAG] are private. [createChannels] is idempotent (re-creating a
 * channel with the same id is a platform no-op) and invoked once from
 * [cn.vectory.ocdroid.OpenCodeApp.onCreate].
 */
internal object NotificationChannels {

    const val CHANNEL_DECISIONS = "ocdroid.decisions"
    const val CHANNEL_IDLE = "ocdroid.idle"
    const val CHANNEL_ERRORS = "ocdroid.errors"

    /**
     * Ongoing session-status channel id (FGS spec §7 channel matrix /
     * dev-design P1.4). IMPORTANCE_LOW. Used by
     * [cn.vectory.ocdroid.service.SessionStreamingService] for the
     * ongoing FGS notification.
     *
     * **Single-source rule**: this is the one canonical home for the
     * channel id. The Service references `NotificationChannels.CHANNEL_SESSION_STATUS`
     * rather than keeping its own copy. (Pre-CP8 the Service held its
     * own const as a placeholder; CP8 moves the source here because
     * `createChannels` — which uses the id — lives here.)
     */
    const val CHANNEL_SESSION_STATUS = "ocdroid.session_status"

    /**
     * Q5: silent counterpart of [CHANNEL_SESSION_STATUS] at
     * IMPORTANCE_MIN. MIN is the only importance that reliably suppresses
     * OEM-ROM heads-up banners (CN-ROMs often peek even LOW channels, and
     * `setSilent(true)` is near no-op on LOW). New id → no runtime
     * downgrade restriction applies. Route silent NotificationSpec
     * variants (transient placeholder + persistent-min) here.
     */
    const val CHANNEL_SESSION_STATUS_MIN = "ocdroid.session_status_min"

    // Hardcoded channel/notification strings. v2-redesign write-domain
    // excludes res/values/strings.xml, so we keep these literals inline
    // rather than fragmenting the lane by touching resources. A future
    // i18n pass can promote them to R.string entries.
    private const val CHANNEL_DECISIONS_NAME = "opencode decisions"
    private const val CHANNEL_DECISIONS_DESC = "Permission and question prompts from opencode sessions"
    private const val CHANNEL_IDLE_NAME = "opencode completions"
    private const val CHANNEL_IDLE_DESC = "Completed sessions that are ready to review"
    private const val CHANNEL_ERRORS_NAME = "opencode errors"
    private const val CHANNEL_ERRORS_DESC = "Connection and runtime errors from opencode"
    private const val CHANNEL_SESSION_STATUS_NAME = "opencode session status"
    private const val CHANNEL_SESSION_STATUS_DESC =
        "Ongoing session-status notifications while opencode is connected in the background"

    private const val TAG = "NotificationChannels"

    /**
     * Creates the notification channels required by §18.1 + §7. Wrapped
     * in try/catch and only invoked on API 26+ (NotificationChannel was
     * added in O). Channels are idempotent — re-creating with the same
     * ID is a no-op. Called from [cn.vectory.ocdroid.OpenCodeApp.onCreate].
     *
     * CP8: adds [CHANNEL_SESSION_STATUS] (FGS spec §7 IMPORTANCE_LOW)
     * alongside the existing [CHANNEL_DECISIONS] / [CHANNEL_IDLE] /
     * [CHANNEL_ERRORS].
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
            val sessionStatus = NotificationChannel(
                CHANNEL_SESSION_STATUS,
                CHANNEL_SESSION_STATUS_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = CHANNEL_SESSION_STATUS_DESC
            }
            // Q5: IMPORTANCE_MIN channel for silent FGS notifications.
            // MIN is what actually suppresses OEM-ROM heads-up banners;
            // LOW does not on many CN-ROMs. Used by the transient
            // placeholder + the persistent-min variant.
            val sessionStatusMin = NotificationChannel(
                CHANNEL_SESSION_STATUS_MIN,
                CHANNEL_SESSION_STATUS_NAME,
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Silent ongoing FGS notification. Shows only when shade expanded."
                setShowBadge(false)
            }
            manager.createNotificationChannels(listOf(decisions, idle, errors, sessionStatus, sessionStatusMin))
        }.onFailure { Log.w(TAG, "Failed to create notification channels", it) }
    }
}
