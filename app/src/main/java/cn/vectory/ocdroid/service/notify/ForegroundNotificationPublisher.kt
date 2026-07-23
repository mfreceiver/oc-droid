package cn.vectory.ocdroid.service.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.di.NotificationChannels
import cn.vectory.ocdroid.MainActivity
import cn.vectory.ocdroid.service.SessionStreamingService
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.streaming.UserCloseRequestParser

/**
 * Translates a pure-JVM [NotificationSpec] into a [Notification]. Channel
 * = [NotificationChannels.CHANNEL_SESSION_STATUS] (created by
 * [NotificationChannels.createChannels] at CP8 alongside the
 * decisions/errors channels — single source, referenced by id only).
 *
 * §16-U1 「关闭后台」Action wiring + chronometer base conversion
 * (wall-clock → elapsedRealtime) per [NotificationSpec]:
 *  - [NotificationSpec.showCloseAction] → NotificationCompat.Action whose
 *    PendingIntent is [buildCloseBackgroundPendingIntent] (this Service
 *    as the target — `PendingIntent.getService`, ACTION_CLOSE_BACKGROUND).
 *    Per §16-U1 the close Action is attached on every ongoing sub-state
 *    that holds an FGS slot (L1-busy / L2-active / L2-idle / degraded);
 *    NOT on the L1-idle 「已连接」 optional surface or the L3 non-ongoing
 *    (already torn down). The [SessionStatusNotifier] carries the
 *    decision bit so this builder just attaches the real PendingIntent.
 *  - [NotificationSpec.showChronometer] → wall-clock `chronometerBaseMs`
 *    is converted to elapsedRealtime base as
 *    `SystemClock.elapsedRealtime() - (now - busySinceMs)`.
 *  - [NotificationSpec.degraded] → setContentIntent points at MainActivity
 *    so the user can resolve TOFU.
 */
class ForegroundNotificationPublisher(
    private val context: Context,
    private val identityStore: ConnectionIdentityStore,
) {

    /**
     * Builds an Android [Notification] from a [NotificationSpec].
     *
     * @param spec The pure-JVM notification description from [SessionStatusNotifier].
     */
    fun build(spec: NotificationSpec): Notification =
        // Q5: route the silent variant to CHANNEL_SESSION_STATUS_MIN
        // (IMPORTANCE_MIN), which actually suppresses OEM-ROM heads-up
        // banners; setSilent(true) on an IMPORTANCE_LOW channel is near no-op
        // and CN-ROMs still peek LOW channels. New channel id has no runtime
        // downgrade restriction.
        NotificationCompat.Builder(
            context,
            if (spec.silent) NotificationChannels.CHANNEL_SESSION_STATUS_MIN
            else NotificationChannels.CHANNEL_SESSION_STATUS
        )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(spec.title)
            .setContentText(spec.content)
            .setPriority(if (spec.silent) NotificationSpec.PRIORITY_MIN else spec.priority)
            .setOngoing(spec.ongoing)
            .apply {
                // T5-C2: silent persistent notification — PRIORITY_MIN +
                // setSilent(true) so it neither surfaces in the shade nor
                // makes sound/vibrate. The FGS slot survives because
                // `setOngoing(spec.ongoing)` above is unchanged (the FGS
                // contract only requires an ongoing notification; silent
                // is orthogonal). SSE keepalive is likewise unaffected.
                if (spec.silent) {
                    setSilent(true)
                }
                if (spec.showChronometer && spec.chronometerBaseMs != null) {
                    // §7: convert wall-clock busySinceMs to elapsedRealtime
                    // base. The notifier stays clock-agnostic (pure JVM); the
                    // Android side owns the platform clock.
                    val nowWall = System.currentTimeMillis()
                    val nowElapsed = SystemClock.elapsedRealtime()
                    val base = nowElapsed - (nowWall - spec.chronometerBaseMs)
                    setUsesChronometer(true)
                    setShowWhen(true)
                    setWhen(base)
                }
                if (spec.showCloseAction) {
                    // §16-U1: ongoing FGS notification Action 「关闭后台」
                    // → ACTION_CLOSE_BACKGROUND → controller.requestUserClose
                    // → coordinator teardown (L3). The Action is reachable
                    // because ongoing FGS notifications cannot be swiped by
                    // the user; the Action is the only exit.
                    addAction(
                        NotificationCompat.Action.Builder(
                            R.mipmap.ic_launcher,
                            context.getString(R.string.notify_close_background),
                            buildCloseBackgroundPendingIntent(),
                        ).build()
                    )
                }
                if (spec.degraded) {
                    // §5 degraded: deep-link to MainActivity so the user can
                    // resolve the TOFU prompt. Non-degraded notifications do
                    // NOT set a contentIntent (no U3 deep-link yet — that is
                    // Phase-1).
                    setContentIntent(buildOpenActivityPendingIntent())
                }
            }
            .build()

    /**
     * §16-U1 close-action target. Delivers ACTION_CLOSE_BACKGROUND to this
     * Service via `PendingIntent.getService`.
     *
     * D2 gate #8: the intent carries the identity (epoch, serverGroupFp,
     * normalizedWorkdir, endpointFp) used to build the ongoing notification;
     * [onStartCommand] routes it through [UserCloseRequestParser] so the
     * controller can revalidate against the current identity before any
     * teardown side-effect. If no identity is bound (the §5 degraded
     * placeholder), the extras are absent → the parser returns a no-identity
     * request → the close handler dismisses the placeholder directly.
     *
     * FLAG_IMMUTABLE because the carried extras are not mutated by the
     * trigger; FLAG_UPDATE_CURRENT so a subsequent rebuild of the ongoing
     * notification with a fresh identity (reconfigure epoch bump) refreshes
     * the same PendingIntent slot — the prior identity is discarded.
     */
    private fun buildCloseBackgroundPendingIntent(): PendingIntent {
        val intent = Intent(context, SessionStreamingService::class.java).apply {
            action = SessionStreamingService.ACTION_CLOSE_BACKGROUND
            // D2 gate #8: stamp the current identity into the extras so the
            // close handler can detect a stale notification (host reconfigured
            // since this notification was built).
            identityStore.currentIdentity.value?.let { id ->
                putExtra(UserCloseRequestParser.EXTRA_EPOCH, id.epoch)
                putExtra(UserCloseRequestParser.EXTRA_SERVER_GROUP_FP, id.serverGroupFp)
                putExtra(UserCloseRequestParser.EXTRA_NORMALIZED_WORKDIR, id.normalizedWorkdir)
                putExtra(UserCloseRequestParser.EXTRA_ENDPOINT_FP, id.endpointFp)
            }
        }
        return PendingIntent.getService(
            context,
            CLOSE_BACKGROUND_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * §5 degraded contentIntent: opens MainActivity so the user can resolve
     * the TOFU prompt. Mirrors [AppLifecycleMonitor.buildContentIntent] but
     * without a session id (degraded is host-wide, not per-session).
     */
    private fun buildOpenActivityPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            DEGRADED_CONTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /**
         * Request code for the §16-U1 close-action PendingIntent. Stable so
         * FLAG_UPDATE_CURRENT can refresh the same slot across notification
         * rebuilds (the spec carries new copy each rebuild but the Action
         * PendingIntent is identity-stable).
         */
        private const val CLOSE_BACKGROUND_REQUEST_CODE = 42410

        /**
         * Request code for the §5 degraded contentIntent (open Activity).
         * Distinct from the close-action slot so the two PendingIntents do
         * not collide under FLAG_UPDATE_CURRENT.
         */
        private const val DEGRADED_CONTENT_REQUEST_CODE = 42411
    }
}
