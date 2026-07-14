package cn.vectory.ocdroid.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.service.events.IdentifiedSseEvent
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Phase 0 / dev-design P0.1 — the ForegroundService shell that owns the SSE
 * connection lifecycle and the ongoing session-status notification.
 *
 * **Architecture (FGS spec §1 / §1.0)**: this class is deliberately a thin
 * Android-component shell + SSE connection owner + FGS notification publisher.
 * All higher-level logic lives in its application-level collaborators
 * (dev-design §1.0 / §5 table):
 *  - [cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator] — the
 *    §4 L1/L2/L3 state machine, idle debounce, FGS↔poller handoff;
 *  - `ConnectionBootstrapCoordinator` (P0.5) — cold-start bootstrap / TOFU gate;
 *  - [cn.vectory.ocdroid.service.status.StatusAggregator] — authoritative busy;
 *  - [cn.vectory.ocdroid.service.bridge.SseEventBridge] — events → identity
 *    validation → `ControllerEffect.OnSseEvent`.
 *
 * Those collaborators are wired in the **switch-over lane** (dev-design §1.1
 *Lane C landing). For Lane C this service is shipped as **structure only**:
 *  - [onStartCommand] is null-Intent-safe and performs the §5 START_STICKY
 *    bootstrap sequence (sync config → placeholder foreground → async
 *    bootstrap), but the async bootstrap body is a stub;
 *  - [connectSse] / [disconnectSse] are public entry points whose bodies wire
 *    in at switch-over (today they only log);
 *  - [events] is the process-level event surface; the actual SSE collector that
 *    feeds it lands with the switch-over of `ConnectionCoordinator.sseJob`.
 *
 * **START_STICKY bootstrap (FGS spec §5)**: a null Intent is the sticky-rebuild
 * signal (process was killed and the system restarted the service). Per §4.3
 * this is treated as a **background** context AND is a **legal** FGS-start
 * context, so [startForeground] is called unconditionally within the 5s ANR
 * window using a placeholder notification before any network work begins.
 *
 * **Foreground service type**: `dataSync` (declared in `AndroidManifest.xml`
 * at P0.9 — not in this file). [ServiceCompat.startForeground] is called with
 * [FOREGROUND_SERVICE_TYPE_DATA_SYNC] so the API 34+ typed overload matches
 * the manifest declaration.
 *
 * **Channels**: NOT created here. `AppLifecycleMonitor` owns channel creation;
 * this service only references the channel id [CHANNEL_SESSION_STATUS]
 * (FGS spec §7 / dev-design P1.4).
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
     * Service-lifetime [CoroutineScope] bound to [MainScope] (Main thread).
     * `startForeground` / `stopForeground` / notification updates MUST be
     * invoked on the main thread; the async bootstrap runs here too so every
     * bootstrap → lifecycle command handoff stays single-threaded with the
     * [cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator]
     * (which is `@UiApplicationScope` = Main.immediate).
     */
    private val scope: CoroutineScope = MainScope()

    /**
     * Process-level SSE event surface (FGS spec §1 / dev-design P0.1).
     *
     * Carries [Result] so transport-level failures reach the
     * [cn.vectory.ocdroid.service.bridge.SseEventBridge] and the
     * `SessionSyncCoordinator` fold path exactly as they do today on
     * `ConnectionCoordinator.launchSseCollection` — each event is wrapped in
     * an [IdentifiedSseEvent] carrying the [ConnectionIdentity] of the
     * collector it arrived on (FGS spec §2 / gpter-MAJOR#2: identity must not
     * be stripped before the fold).
     *
     * **Switch-over seam**: the collector that feeds this flow (today
     * `repository.connectSSE(workdir)` collected in
     * `ConnectionCoordinator.launchSseCollection`) moves into [connectSse] in
     * the switch-over lane. Lane C ships the surface only.
     */
    private val _events: MutableSharedFlow<Result<IdentifiedSseEvent>> =
        MutableSharedFlow(extraBufferCapacity = SSE_EVENT_BUFFER_CAPACITY)
    val events: SharedFlow<Result<IdentifiedSseEvent>> = _events.asSharedFlow()

    /**
     * FGS spec §5 / §4.3: null Intent = sticky rebuild = legal FGS-start
     * context. The service performs the same bootstrap regardless of whether
     * the launch came from the app, an FGS promotion, or a system restart.
     *
     * Order (strictly §5):
     *  1. **Synchronously** read minimal persisted config (host url). No host
     *     → `stopSelf()` (no FGS slot burned on a service with nothing to do);
     *     returns [START_STICKY] so the system may still restart it if the
     *     user later configures a host.
     *  2. **Immediately** [ServiceCompat.startForeground] with the placeholder
     *     notification inside the 5s ANR window — BEFORE any network/TOFU
     *     work. The placeholder is LOW priority + ongoing.
     *  3. **Async** bootstrap (tunnel / health / TOFU / status) on [scope].
     *     Body is a Lane C stub; the switch-over lane wires the full §5
     *     sequence and hands the result to
     *     [cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator].
     *
     * Returns [START_STICKY] (FGS spec §5 decision 2 / §15: covers
     * process-death rebuild; does NOT guarantee timely recovery and does NOT
     * survive force-stop).
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLog.i(TAG, "onStartCommand: intent=$intent (null=sticky rebuild, §5)")
        // §5 step 1: synchronous minimal persisted-config read.
        val hasValidHost = settingsManager.serverUrl.isNotBlank()
        if (!hasValidHost) {
            DebugLog.w(TAG, "onStartCommand: no effective host → stopSelf (§5 step 1)")
            stopSelf()
            return START_STICKY
        }
        // §5 step 2: startForeground within the 5s ANR window, BEFORE any async work.
        promoteToForeground(buildPlaceholderNotification())
        // §5 steps 3–6: async bootstrap. Lane C stub; switch-over wires the real sequence.
        scope.launch { bootstrapAsync() }
        return START_STICKY
    }

    /**
     * FGS spec §5 steps 3–6 async bootstrap. Lane C ships a stub; the
     * switch-over lane wires:
     *  - tunnel activation / validation;
     *  - health probe + TLS/TOFU gate (shared `ConnectionBootstrapCoordinator`,
     *    §10);
     *  - global `getSessionStatus` (§3 merge);
     *  - busy → keep FGS (L2-active) + [connectSse]; authoritative all-idle →
     *    `stopForeground` + `stopSelf` (enter L3, poller only);
     *  - `SSEConnectionExhausted` → service-level long retry / degraded.
     *
     * The stub here simply keeps the foreground pinned until the lifecycle
     * coordinator (wired at switch-over) drives it via §4 transitions.
     */
    private suspend fun bootstrapAsync() {
        DebugLog.i(TAG, "bootstrapAsync: Lane C structure stub (switch-over wires §5 steps 3–6)")
    }

    /**
     * Starts / retargets the SSE collector for [identity] (FGS spec §1).
     *
     * **Switch-over seam**: today `ConnectionCoordinator.startSSE` +
     * `launchSseCollection` own this; the collector moves here so that the
     * process-level [events] flow is fed by the service regardless of
     * Activity / ViewModel lifecycle. Lane C ships the entry point only.
     *
     * The collector MUST tag every emitted [IdentifiedSseEvent] with the
     * [identity] passed in (FGS spec §2: identity is validated by the bridge
     * and by `SessionSyncCoordinator.fold` BEFORE any side effect; it must
     * not be stripped at the producer).
     */
    fun connectSse(identity: ConnectionIdentity) {
        DebugLog.i(TAG, "connectSse: identity epoch=${identity.epoch} (Lane C structure — switch-over wires collector)")
        // Switch-over: scope.launch { repository.connectSSE(workdir).collect { ... _events.emit(...) } }
    }

    /**
     * Tears down the in-flight SSE collector (FGS spec §1.1 / §4.4).
     *
     * Leaves the gap-dirty signal expected by the foreground catch-up path
     * (`SseSyncState.reconcileGap(Disconnected...)`) so a return-to-foreground
     * triggers reconcile — the switch-over lane ensures this stays equivalent
     * to today's `ConnectionCoordinator.cancelSse`.
     */
    fun disconnectSse() {
        DebugLog.i(TAG, "disconnectSse (Lane C structure — switch-over wires sseJob cancel)")
        // Switch-over: sseJob?.cancel(); sseJob = null
    }

    /**
     * Promotes this service to the foreground with the dataSync type and
     * [notification] (FGS spec §5 step 2). Uses the typed [ServiceCompat]
     * overload so API 34+ sees a foregroundServiceType matching the manifest
     * declaration.
     *
     * notify Phase-0 scaffolding; switchover CP8 lands the <service> decl +
     * perms, then remove this suppress. Lint (`ForegroundServiceType`) fires
     * because the `<service>` is intentionally NOT in the manifest yet (the
     * `notify-switchover` branch lands CP8 later); the suppress silences only
     * that check.
     */
    @SuppressLint("ForegroundServiceType")
    private fun promoteToForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            SESSION_STATUS_NOTIFICATION_ID,
            notification,
            FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    /**
     * Placeholder notification for the §5 START_STICKY cold-start window
     * (FGS spec §5 step 2; visual §3.1.5). LOW priority (no heads-up bubble),
     * ongoing (cannot be swiped). The real copy + i18n string resource is a
     * switch-over / P1 concern; Lane C uses [R.string.app_name] as a
     * compile-safe title placeholder.
     *
     * Small icon: the codebase standard is `R.mipmap.ic_launcher` (matches
     * `AppLifecycleMonitor.notifyDecision/notifyError`); no `stat_sys_download`
     * or single-color stat drawable exists in this project yet.
     */
    private fun buildPlaceholderNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_SESSION_STATUS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        DebugLog.i(TAG, "onDestroy: cancelling service scope")
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
         * Ongoing session-status channel id (FGS spec §7 channel matrix /
         * dev-design P1.4). IMPORTANCE_LOW. Created by
         * `AppLifecycleMonitor` (Phase 1) — NOT created in this service.
         */
        const val CHANNEL_SESSION_STATUS = "ocdroid.session_status"

        /**
         * Buffer capacity for the [events] SharedFlow. Generous so transient
         * collector-side stalls do not drop frames; the §11 control/delta
         * split happens downstream in
         * [cn.vectory.ocdroid.service.bridge.SseEventBridge].
         */
        private const val SSE_EVENT_BUFFER_CAPACITY = 256
    }
}
