package cn.vectory.ocdroid.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.di.AppLifecycleMonitor
import cn.vectory.ocdroid.service.events.IdentifiedSseEvent
import cn.vectory.ocdroid.service.events.SseEventStream
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator
import cn.vectory.ocdroid.service.notify.NotificationSpec
import cn.vectory.ocdroid.service.notify.NotificationStrings
import cn.vectory.ocdroid.service.notify.SessionStatusNotifier
import cn.vectory.ocdroid.service.streaming.BootstrapRunner
import cn.vectory.ocdroid.service.streaming.ServiceShell
import cn.vectory.ocdroid.service.streaming.SessionSnapshotProvider
import cn.vectory.ocdroid.service.streaming.SessionStreamingController
import cn.vectory.ocdroid.service.status.StatusAggregator
import cn.vectory.ocdroid.service.status.StatusAggregatorInput
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Phase 0 / dev-design P0.1 — the ForegroundService shell that owns the SSE
 * connection lifecycle and the ongoing session-status notification.
 *
 * **Architecture (FGS spec §1 / §1.0)**: this class is a thin Android-component
 * shell + SSE connection owner + FGS notification publisher. All higher-level
 * logic lives in its application-level collaborators:
 *  - [StreamingLifecycleCoordinator] — the §4 L1/L2/L3 state machine, idle
 *    debounce, FGS↔poller handoff;
 *  - [SessionStreamingController] — the pure-JVM orchestrator that consumes
 *    [StreamingLifecycleCoordinator.commands] → [ServiceShell] side-effects,
 *    runs the §6 background poller, and drives the §5 START_STICKY bootstrap;
 *  - `ConnectionBootstrapCoordinator` — cold-start bootstrap / TOFU gate;
 *  - [StatusAggregator] — authoritative busy;
 *  - [cn.vectory.ocdroid.service.bridge.SseEventBridge] — events → identity
 *    validation → `ControllerEffect.OnSseEvent`.
 *
 * **CP5-7 inert**: this Service's BODY is complete (the
 * [SessionStreamingController] consumes commands, runs the poller, runs the
 * bootstrap), but no production caller does `startForegroundService(this)`
 * yet — the manifest `<service>` entry and the trigger are CP8/CP9. So at
 * runtime through CP7 this Service cannot actually be started. We are
 * completing + unit-testing its body.
 *
 * **START_STICKY bootstrap (FGS spec §5)**: a null Intent is the sticky-rebuild
 * signal (process was killed and the system restarted the service). Per §4.3
 * this is treated as a **background** context AND is a **legal** FGS-start
 * context, so [androidx.core.app.ServiceCompat.startForeground] is called
 * unconditionally within the 5s ANR window using a placeholder notification
 * before any network work begins.
 *
 * **Foreground service type**: `dataSync` (declared in `AndroidManifest.xml`
 * at CP8 — not in this file). [ServiceCompat.startForeground] is called with
 * [FOREGROUND_SERVICE_TYPE_DATA_SYNC] so the API 34+ typed overload matches
 * the manifest declaration.
 *
 * **Channels**: NOT created here. `AppLifecycleMonitor` owns channel
 * creation for `ocdroid.decisions` / `ocdroid.errors` /
 * `ocdroid.session_status` (CP8 — all three live in
 * [AppLifecycleMonitor.createChannels]); this service references the
 * channel id only via [AppLifecycleMonitor.CHANNEL_SESSION_STATUS]
 * (single source — no duplicate const here).
 */
@AndroidEntryPoint
class SessionStreamingService : Service() {

    /**
     * Minimal persisted-config reader. Read synchronously in [onStartCommand]
     * to decide whether a valid host exists before promoting to foreground
     * (FGS spec §5 step 1: no effective host → `stopSelf`, do not burn an FGS
     * slot on a service that has nothing to connect to).
     */
    @Inject
    lateinit var settingsManager: SettingsManager

    /**
     * CP3 (notify Phase-0): the process-wide SSE event stream. The Service
     * delegates its [events] surface to this stream — so when the Service IS
     * started, its `events` field IS the same stream the
     * [cn.vectory.ocdroid.service.bridge.SseEventBridge] subscribes to.
     * Today (CP3+) the producer is `ConnectionCoordinator.launchSseCollection`;
     * the Service remains inert / not-started until CP8/CP9.
     */
    @Inject
    lateinit var sseEventStream: SseEventStream

    // ── CP5: Hilt-injected collaborators for [SessionStreamingController] ──

    @Inject lateinit var coordinator: StreamingLifecycleCoordinator
    @Inject lateinit var statusAggregator: StatusAggregator
    @Inject lateinit var statusAggregatorInput: StatusAggregatorInput
    @Inject lateinit var identityStore: ConnectionIdentityStore
    @Inject lateinit var bootstrapRunner: BootstrapRunner
    @Inject lateinit var sessionSnapshotProvider: SessionSnapshotProvider
    @Inject lateinit var appLifecycleMonitor: AppLifecycleMonitor

    /**
     * Service-lifetime [CoroutineScope] bound to [MainScope] (Main thread).
     * `startForeground` / `stopForeground` / notification updates MUST be
     * invoked on the main thread; the async bootstrap runs here too so every
     * bootstrap → lifecycle command handoff stays single-threaded with the
     * [StreamingLifecycleCoordinator] (which is `@UiApplicationScope` =
     * Main.immediate).
     */
    private val scope: CoroutineScope = MainScope()

    /**
     * Localised copy bundle (FGS spec §7). Built once in [onCreate] from
     * `R.string.notify_session_*` so [SessionStatusNotifier] stays pure-JVM.
     */
    private lateinit var strings: NotificationStrings

    /**
     * The pure-JVM orchestrator that turns coordinator commands into shell
     * side-effects. Built in [onCreate]; held until [onDestroy].
     */
    private var controller: SessionStreamingController? = null

    /**
     * CP5: the production [ServiceShell] — a private adapter that translates
     * each shell call to `ServiceCompat` / `NotificationManagerCompat` /
     * `stopSelf` / SSE stubs. Held as a field so the controller can be
     * rebuilt in tests against a fake shell instead.
     *
     * SSE side ([shellSseConnect] / [shellSseDisconnect]) are CP9 no-op stubs.
     */
    private val shell: ServiceShell = object : ServiceShell {
        override fun startForeground(spec: NotificationSpec) =
            promoteToForeground(buildNotification(spec))
        override fun updateNotification(spec: NotificationSpec) =
            notifyOngoing(buildNotification(spec))
        override fun stopForeground() {
            ServiceCompat.stopForeground(
                this@SessionStreamingService,
                ServiceCompat.STOP_FOREGROUND_DETACH,
            )
        }
        override fun serviceStopSelf() = this@SessionStreamingService.stopSelf()
        override fun startPoller() {
            // Production marker no-op — the controller owns the poller job.
            // Tests use a recording fake to assert §4.4 source-switch ordering.
        }
        override fun stopPoller() {
            // Likewise.
        }
        override fun connectSse(identity: ConnectionIdentity) {
            // CP9: SSE collector moves here from ConnectionCoordinator
            // (launchSseCollection + sseJob ownership). Today the collector
            // still lives in CC, so this is a DEBUG-only no-op stub.
            DebugLog.d(TAG, "shell.connectSse(identity epoch=${identity.epoch}) — CP9 stub (SSE still in CC)")
        }
        override fun disconnectSse() {
            // CP9: SSE collector teardown (sseJob?.cancel()) moves here.
            DebugLog.d(TAG, "shell.disconnectSse — CP9 stub (SSE still in CC)")
        }
    }

    /**
     * Process-level SSE event surface (FGS spec §1 / dev-design P0.1).
     *
     * Delegates to [sseEventStream.events] — the single process-wide stream.
     * The producer today (CP3+) is `ConnectionCoordinator.launchSseCollection`;
     * when the SSE ownership migrates here (CP9), [ServiceShell.connectSse]
     * will publish into the same stream via [SseEventStream.emit], and the
     * bridge + downstream fold path stay unchanged.
     */
    val events: SharedFlow<Result<IdentifiedSseEvent>> get() = sseEventStream.events

    override fun onCreate() {
        super.onCreate()
        strings = NotificationStrings(
            appName = getString(R.string.app_name),
            restoringConnection = getString(R.string.notify_session_restoring),
            busySingular = getString(R.string.notify_session_busy_singular),
            busyPluralFormat = getString(R.string.notify_session_busy_plural),
            connected = getString(R.string.notify_session_connected),
            idleMonitoring = getString(R.string.notify_session_idle_monitoring),
            degradedTitle = getString(R.string.notify_session_degraded_title),
            degradedContent = getString(R.string.notify_session_degraded_content),
        )
        controller = SessionStreamingController(
            coordinator = coordinator,
            statusAggregator = statusAggregator,
            statusAggregatorInput = statusAggregatorInput,
            identityStore = identityStore,
            sessionSnapshotProvider = sessionSnapshotProvider,
            bootstrapRunner = bootstrapRunner,
            shell = shell,
            strings = strings,
            inForeground = appLifecycleMonitor.isInForeground,
            scope = scope,
        ).also { it.start() }
    }

    /**
     * FGS spec §5 / §4.3 / §16-U1.
     *
     * Intent-action routing (CP8 §16-U1):
     *  - [ACTION_CLOSE_BACKGROUND] → §16-U1 user-explicit close. Forwards to
     *    [SessionStreamingController.requestUserClose] → coordinator teardown
     *    (L3: `stopForeground` + `stopSelf` + `cancelSse` + arm poller +
     *    dismiss ongoing). Returns [START_STICKY]; does NOT re-bootstrap.
     *  - null/other → §5 START_STICKY bootstrap path (unchanged from CP5-7).
     *
     * The §5 bootstrap path:
     *
     * Order (strictly §5):
     *  1. **Synchronously** read minimal persisted config (host url). No host
     *     → `stopSelf()` (no FGS slot burned on a service with nothing to do);
     *     returns [START_STICKY] so the system may still restart it if the
     *     user later configures a host.
     *  2. **Immediately** [ServiceCompat.startForeground] with the placeholder
     *     notification inside the 5s ANR window — BEFORE any network/TOFU
     *     work. The placeholder is LOW priority + ongoing.
     *  3. **Async** bootstrap (§5 steps 3–6) on [scope] via
     *     [SessionStreamingController.bootstrapAsync]: tunnel/health/TOFU
     *     (CC-owned until CP9) → global `getSessionStatus` (§3 merge) →
     *     [StreamingLifecycleCoordinator.onBootstrapResult]. The coordinator's
     *     decision matrix then drives L1/L2Active/L3 via the command stream
     *     the controller collects.
     *
     * Returns [START_STICKY] (FGS spec §5 decision 2 / §15: covers
     * process-death rebuild; does NOT guarantee timely recovery and does NOT
     * survive force-stop).
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // §16-U1: branch on the close-background action BEFORE the §5 bootstrap
        // path. The close action is delivered by the ongoing-notification
        // Action PendingIntent (CP8 §16-U1 wiring) — never via sticky rebuild.
        // Routing logic lives in [StartCommandRouter] so it is pure-JVM
        // unit-testable without a real Android Intent.
        when (val route = StartCommandRouter.routeFor(intent?.action)) {
            StartCommandRouter.Route.CloseBackground -> {
                DebugLog.i(TAG, "onStartCommand: ACTION_CLOSE_BACKGROUND (§16-U1 user-explicit close)")
                controller?.requestUserClose()
                return START_STICKY
            }
            StartCommandRouter.Route.Bootstrap -> {
                // fall through to the §5 bootstrap body below.
            }
        }
        DebugLog.i(TAG, "onStartCommand: intent=$intent (null=sticky rebuild, §5)")
        // §5 step 1: synchronous minimal persisted-config read.
        val hasValidHost = settingsManager.serverUrl.isNotBlank()
        if (!hasValidHost) {
            DebugLog.w(TAG, "onStartCommand: no effective host → stopSelf (§5 step 1)")
            stopSelf()
            return START_STICKY
        }
        // §5 step 2: startForeground within the 5s ANR window, BEFORE any async work.
        promoteToForeground(buildNotification(SessionStatusNotifier.buildPlaceholder(strings)))
        // §5 steps 3–6: async bootstrap. Controller is single-flight; safe if
        // onStartCommand fires multiple times (sticky rebuild).
        scope.launch { controller?.bootstrapAsync() }
        return START_STICKY
    }

    /**
     * FGS spec §4.1 dataSync platform time-limit callback (API 34+,
     * targetSdk 34 — single-arg overload; NOT the API 35 two-arg
     * `onTimeout(startId, fgsType)` since targetSdk=34).
     *
     * Fires when the platform's dataSync FGS budget expires (6h cumulative /
     * 24h rolling window). The service has no way to extend this — we route
     * the signal to [SessionStreamingController.onServiceTimeout] →
     * [StreamingLifecycleCoordinator.onTimeout] → L3 teardown
     * (`stopForeground` + `stopSelf` + `cancelSse` + arm poller + dismiss
     * ongoing). No automatic recovery is attempted (§4.1: legal recovery
     * entries only — user reopens app / notification action / system restart).
     */
    override fun onTimeout(startId: Int) {
        DebugLog.i(TAG, "onTimeout(startId=$startId) — §4.1 dataSync platform timeout → L3")
        controller?.onServiceTimeout()
    }

    /**
     * Promotes this service to the foreground with the dataSync type and
     * [notification] (FGS spec §5 step 2). Uses the typed [ServiceCompat]
     * overload so API 34+ sees a foregroundServiceType matching the manifest
     * declaration.
     */
    private fun promoteToForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            SESSION_STATUS_NOTIFICATION_ID,
            notification,
            FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    /**
     * Updates the ongoing notification WITHOUT touching FGS promotion state.
     * Used by the §5 degraded transition (no teardown, no promotion — just
     * surface the Open-app hint).
     */
    private fun notifyOngoing(notification: Notification) {
        runCatching {
            NotificationManagerCompat.from(this).notify(
                SESSION_STATUS_NOTIFICATION_ID,
                notification,
            )
        }.onFailure { DebugLog.w(TAG, "notifyOngoing failed: ${it.message}") }
    }

    /**
     * Translates a pure-JVM [NotificationSpec] into a [Notification]. Channel
     * = [AppLifecycleMonitor.CHANNEL_SESSION_STATUS] (created by
     * [AppLifecycleMonitor.createChannels] at CP8 alongside the
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
    private fun buildNotification(spec: NotificationSpec): Notification =
        NotificationCompat.Builder(this, AppLifecycleMonitor.CHANNEL_SESSION_STATUS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(spec.title)
            .setContentText(spec.content)
            .setPriority(spec.priority)
            .setOngoing(spec.ongoing)
            .apply {
                if (spec.showChronometer && spec.chronometerBaseMs != null) {
                    // §7: convert wall-clock busySinceMs to elapsedRealtime
                    // base. The notifier stays clock-agnostic (pure JVM); the
                    // Android side owns the platform clock.
                    val nowWall = System.currentTimeMillis()
                    val nowElapsed = android.os.SystemClock.elapsedRealtime()
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
                            getString(R.string.notify_close_background),
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
     * Service via `PendingIntent.getService` (FLAG_IMMUTABLE because the
     * carried intent carries no extras the trigger needs to mutate;
     * FLAG_UPDATE_CURRENT so a subsequent rebuild of the ongoing notification
     * with a fresh spec does not orphan the prior PendingIntent).
     */
    private fun buildCloseBackgroundPendingIntent(): PendingIntent {
        val intent = Intent(this, SessionStreamingService::class.java).apply {
            action = ACTION_CLOSE_BACKGROUND
        }
        return PendingIntent.getService(
            this,
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
        val intent = Intent(this, cn.vectory.ocdroid.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            DEGRADED_CONTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        DebugLog.i(TAG, "onDestroy: cancelling service scope")
        controller?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SessionStreamingService"

        /**
         * Fixed ongoing-notification id (FGS spec §7 / dev-design §2.1).
         * Stable across the process lifetime so the placeholder, L1-busy,
         * L2-active and L2-idle ongoing notifications all replace each other
         * instead of stacking. Sits below the fixed error id `4242`.
         */
        const val SESSION_STATUS_NOTIFICATION_ID = 4241

        /**
         * §16-U1 ongoing-notification 「关闭后台」Action target. Delivered to
         * [onStartCommand] via a PendingIntent that points back at this
         * Service; the Service routes it to
         * [SessionStreamingController.requestUserClose] →
         * [StreamingLifecycleCoordinator.requestUserClose] (L3 teardown).
         *
         * Declared `const val` so the test fixture can reference the literal
         * without going through the Service class.
         */
        const val ACTION_CLOSE_BACKGROUND = "cn.vectory.ocdroid.action.CLOSE_BACKGROUND"

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
