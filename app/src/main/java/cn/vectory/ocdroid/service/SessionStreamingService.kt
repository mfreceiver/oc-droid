package cn.vectory.ocdroid.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
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
import cn.vectory.ocdroid.service.streaming.ServiceSseConnectionOwner
import cn.vectory.ocdroid.service.streaming.SessionSnapshotProvider
import cn.vectory.ocdroid.service.streaming.SessionStreamingController
import cn.vectory.ocdroid.service.streaming.SseNotificationBridge
import cn.vectory.ocdroid.service.streaming.UserCloseRequestParser
import cn.vectory.ocdroid.service.status.StatusAggregator
import cn.vectory.ocdroid.service.status.StatusAggregatorInput
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * **CP9 switchover**: this Service is now the live SSE producer. The
 * [sseOwner] ([ServiceSseConnectionOwner]) owns the collector that USED to
 * live inside
 * [cn.vectory.ocdroid.ui.controller.ConnectionCoordinator] (`sseJob` +
 * `launchSseCollection`). The trigger that promotes the Service to
 * foreground is [cn.vectory.ocdroid.service.StreamingServiceLauncher]
 * (`AndroidStreamingServiceLauncher` in production): CC's `startSSE` now
 * calls `launcher.ensureStarted()` instead of `repository.connectSSE(...)`.
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
    @Inject lateinit var effectiveConnectionConfigResolver: cn.vectory.ocdroid.service.streaming.EffectiveConnectionConfigResolver

    /**
     * CP3 (notify Phase-0): the process-wide SSE event stream. The Service
     * delegates its [events] surface to this stream AND uses it as the
     * publish target for [sseOwner] (CP9). The
     * [cn.vectory.ocdroid.service.bridge.SseEventBridge] (subscribed eagerly
     * from AppCore) consumes this stream → routes through the §2 epoch guard
     * + §11 dual-channel → AppCore re-emits as OnSseEvent for SSC's fold.
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
    @Inject lateinit var ownershipGate: StreamingOwnershipGate
    /**
     * T5-C1/C2: read by [buildNotification] / [buildPlaceholder] to derive
     * the `silent` flag (`!persistentNotificationEnabled`). Injected via
     * Hilt; not held in the controller (the controller receives a
     * `silentNotifications: () -> Boolean` lambda so it stays pure-JVM).
     */
    @Inject lateinit var settingsManager: SettingsManager

    // ── CP9: Hilt-injected collaborators for [sseOwner] (the SSE collector). ──

    @Inject lateinit var repository: OpenCodeRepository
    @Inject lateinit var bootstrapCoordinator: cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator
    @Inject lateinit var sharedStateStore: SharedStateStore
    @Inject lateinit var sharedEffectBus: SharedEffectBus

    // ── D2 (gate #4 / #7): process-level poller + SSE recovery policy. ──

    @Inject lateinit var processStatusPoller: cn.vectory.ocdroid.service.streaming.ProcessStatusPoller
    @Inject lateinit var sseRecoveryPolicy: cn.vectory.ocdroid.service.streaming.SseRecoveryPolicy
    @Inject lateinit var bootstrapRetryPolicy: cn.vectory.ocdroid.service.streaming.BootstrapRetryPolicy

    /**
     * T5-C4: the SSE → notification bridge is wired into the Service
     * (the Service is the L2 SSE producer, so this is the right scope).
     * Subscribes to [sseEventBridge]'s control-class flow and fires temp
     * notifications when the process is in background. The Service injects
     * the ALM-shared [cn.vectory.ocdroid.di.SessionNotifier] + dedup sets
     * + a [SharedStateStore]-backed root-idle resolver.
     */
    @Inject lateinit var sseEventBridge: cn.vectory.ocdroid.service.bridge.SseEventBridge

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
     * CP9 (notify Phase-0 switchover): the live SSE collector. Replaces the
     * `sseJob` + `launchSseCollection` that USED to live inside
     * [cn.vectory.ocdroid.ui.controller.ConnectionCoordinator]. Built in
     * [onCreate]; torn down in [onDestroy] before [scope] is cancelled.
     *
     * The shell's [ServiceShell.connectSse] / [ServiceShell.disconnectSse]
     * forward to this owner — the Service IS the SSE producer now.
     */
    private var sseOwner: ServiceSseConnectionOwner? = null

    /**
     * CP9 §C15: the bootstrap restart latch. Retained as a `Job?` so a second
     * CP9 bootstrap/start intent on the same Service instance can cancel an
     * in-flight bootstrap and run a fresh one (a `stopSelf()` + a new start
     * that overlap can no longer be stranded by a once-per-instance latch).
     * Single-flight is preserved (one active bootstrap at a time); the
     * coordinator's `onBootstrapResult()` remains the only legal L3→running
     * entry.
     */
    private var bootstrapJob: Job? = null
    /** D5-3: prevents duplicate terminal teardown from expiry/abort races. */
    private var bootstrapAbortIssued = false
    private var acceptedOwnershipIdentity: ConnectionIdentity? = null

    /**
     * T5-C4: the SSE → temp-notification bridge. Built in [onCreate] after
     * the collaborators are injected; torn down in [onDestroy]. Lives only
     * while the Service owns the SSE connection (L2 lifetime).
     */
    private var sseNotificationBridge: SseNotificationBridge? = null

    /**
     * CP5: the production [ServiceShell] — a private adapter that translates
     * each shell call to `ServiceCompat` / `NotificationManagerCompat` /
     * `stopSelf` / SSE side-effects.
     *
     * D2 gate #4: [startPoller] delegates to [processStatusPoller] which
     * runs the loop on `@ApplicationScope` (survives this Service's
     * onDestroy); [stopPoller] cancels the loop. The shell method is
     * `suspend` so the controller's await measures the immediate-first-poll
     * latency; the loop continues independently after the suspend returns.
     *
     * SSE side ([connectSse] / [disconnectSse]) forward to [sseOwner] — the
     * collector is owned here (CP9); D2 gate #7 wires [sseRecoveryPolicy]
     * into the owner for the §5 step 6 service-level retry budget.
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
        override suspend fun startPoller(
            identity: ConnectionIdentity,
            snapshot: cn.vectory.ocdroid.service.status.StatusSnapshot,
        ): cn.vectory.ocdroid.service.streaming.SourceActivation {
            // D2 gate #4: delegate to the process-level poller. The loop
            // runs on @ApplicationScope and survives this Service's death.
            return processStatusPoller.startAndAwaitFirstPoll(identity, snapshot)
        }
        override fun stopPoller() {
            // D2 gate #4: cancel the process-level poller loop. The
            // coordinator emits StopPoller on the L2Idle→L2Active commit OR
            // when the DebounceFire handoff cancels a non-idle poller.
            processStatusPoller.stop()
        }
        override suspend fun ensurePoller(
            identity: ConnectionIdentity,
            snapshot: cn.vectory.ocdroid.service.status.StatusSnapshot,
        ): cn.vectory.ocdroid.service.streaming.SourceActivation {
            // D5 (#2): delegate to the process-level poller's ensureRunning.
            // Idempotent for the same identity (no cancel/restart).
            return processStatusPoller.ensureRunning(identity, snapshot)
        }
        override suspend fun connectSse(identity: ConnectionIdentity): cn.vectory.ocdroid.service.streaming.SourceActivation {
            // CP9 + D2 gate #4: SSE collector owned here; connect is suspend
            // returning SourceActivation. The coordinator's §4.4 ordering
            // guarantees StartSse is emitted BEFORE StopPoller (new source
            // active before retiring old); the commit (StopPoller + layer
            // flip) runs only after this returns Ready.
            return sseOwner?.connect(identity)
                ?: cn.vectory.ocdroid.service.streaming.SourceActivation.Rejected.TofuPending
        }
        override suspend fun disconnectSse() {
            // CP9 + D2 gate #7: SSE collector teardown. The coordinator emits
            // StopSse AFTER StartPoller (new source active before retiring
            // old); markGap=true so a §4.4 teardown stamps the gap-dirty
            // signal via the idempotent CancelSse effect.
            //
            // The shell contract is suspend so cancellation is joined before
            // the following StopForeground / StopSelf commands execute.
            sseOwner?.disconnect(markGap = true)
        }
    }

    /**
     * Process-level SSE event surface (FGS spec §1 / dev-design P0.1).
     *
     * Delegates to [sseEventStream.events] — the single process-wide stream.
     * CP9 producer: [sseOwner] publishes each [IdentifiedSseEvent] into the
     * same stream via [SseEventStream.emit]. The bridge + downstream fold
     * path stay byte-for-byte unchanged from CP3-8.
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
        // CP9: construct the SSE collector here (one instance per Service
        // instance). The owner publishes into the process-wide
        // [sseEventStream]; the bridge (eagerly started from AppCore init)
        // routes through the §2 epoch guard + §11 dual-channel.
        //
        // D2 gate #4 / #7: the owner now needs the status-aggregator input +
        // snapshot provider (first-frame readiness baseline) + the recovery
        // policy (§5 step 6 service-level retries). The status snapshot
        // provider is the same one the controller / poller uses.
        // D2 gate #4 / #7: the owner now needs only the recovery policy
        // (§5 step 6 service-level retries). D4-B M3: transport readiness is
        // first-frame-only — the owner no longer consumes the status
        // aggregator / snapshot provider (status authority is the
        // coordinator's concern at handoff commit).
        sseOwner = ServiceSseConnectionOwner(
            scope = scope,
            repository = repository,
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            sseEventStream = sseEventStream,
            sharedStateStore = sharedStateStore,
            sharedEffectBus = sharedEffectBus,
            recoveryPolicy = sseRecoveryPolicy,
            onTerminalExhaustion = { coordinator.onDisconnect() },
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
            bootstrapRetryPolicy = bootstrapRetryPolicy,
            onBootstrapIdentity = { identity ->
                registerStartingOwnership(identity, StreamingOwnershipGate.NO_ATTEMPT_ID)
            },
            onBootstrapFailure = { identity -> failStarting(identity) },
            silentNotifications = { !settingsManager.persistentNotificationEnabled },
        ).also { it.start() }

        // T5-C4: SSE → temp-notification bridge. Subscribes to the
        // SseEventBridge's notificationControlEvents (T5-review C1 fix: an
        // additive SharedFlow tap on the control-class flow, NOT
        // `controlEvents` itself which is a single-consumer Channel already
        // drained by AppCore). Already filtered by §2 epoch + §11 control
        // routing; fires background-only temp notifications for
        // question.asked + session.status{idle} (root + unread). Shares the
        // ALM notifier + dedup sets so a 30s-poller discovery and an SSE
        // discovery of the SAME event cannot double-fire (T5-C4a/b).
        sseNotificationBridge = SseNotificationBridge(
            events = sseEventBridge.notificationControlEvents,
            notifier = appLifecycleMonitor.notifier,
            decisionDedup = appLifecycleMonitor.notificationSnapshot,
            idleDedup = appLifecycleMonitor.idleNotificationSnapshot,
            // Race-closure: the SAME shared Mutex the poller's
            // handleIdleAlert + post-prune side-effect loop hold. Wraps the
            // bridge's idle publish critical section so a deferred-stale
            // post-prune `cancel + removePostedIdle` cannot clobber a fresh
            // bridge completion + `addPostedIdle`.
            idleMutex = appLifecycleMonitor.idleMutex,
            isInForeground = { appLifecycleMonitor.isInForeground.value },
            rootIdleResolver = ::resolveRootIdleAlert,
            // Bug-1-fix-B: an idle key completed via the SSE bridge is
            // mirrored into the durable dedup store so it survives process
            // death (otherwise lost until the poller's ≤30s post-prune
            // self-heal).
            onIdlePosted = { appLifecycleMonitor.persistIdlePosted(it) },
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
     *    [SessionStreamingController.bootstrapAsync]: tunnel/health/TOFU
     *    → global `getSessionStatus` (§3 merge) →
     *    [StreamingLifecycleCoordinator.onBootstrapResult]. The coordinator's
     *    decision matrix then drives L1/L2Active/L3 via the command stream
     *    the controller collects (StartSse → [sseOwner].connect /
     *    StopSse → [sseOwner].disconnect).
     *
     * Returns [START_STICKY] (FGS spec §5 decision 2 / §15: covers
     * process-death rebuild; does NOT guarantee timely recovery and does NOT
     * survive force-stop).
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // DIAGNOSTIC TIMING: anchor every subsequent measurement in this call.
        // MUST be the very first statement. Purely additive — cheap (SystemClock
        // only), no behavior change.
        val onStartEntryNs = SystemClock.elapsedRealtimeNanos()
        DebugLog.i(TAG, "onStartCommand: entered (startId=$startId, action=${intent?.action}, flags=$flags, realtimeMs=${SystemClock.elapsedRealtime()})")
        // §16-U1: branch on the close-background action BEFORE the §5 bootstrap
        // path. The close action is delivered by the ongoing-notification
        // Action PendingIntent (CP8 §16-U1 wiring) — never via sticky rebuild.
        // Routing logic lives in [StartCommandRouter] so it is pure-JVM
        // unit-testable without a real Android Intent.
        //
        // D2 gate #8: the close action's PendingIntent carries the identity
        // used to build it; [UserCloseRequestParser] reconstructs the
        // [ConnectionIdentity] from the Intent extras so the controller can
        // revalidate against the current identity before any teardown.
        val closeRequest = UserCloseRequestParser.parse(
            epoch = intent?.getLongExtra(UserCloseRequestParser.EXTRA_EPOCH, -1L)
                ?.takeIf { it >= 0 },
            serverGroupFp = intent?.getStringExtra(UserCloseRequestParser.EXTRA_SERVER_GROUP_FP),
            normalizedWorkdir = intent?.getStringExtra(UserCloseRequestParser.EXTRA_NORMALIZED_WORKDIR),
            endpointFp = intent?.getStringExtra(UserCloseRequestParser.EXTRA_ENDPOINT_FP),
        )
        when (val route = StartCommandRouter.routeFor(intent?.action, closeRequest)) {
            is StartCommandRouter.Route.CloseBackground -> {
                DebugLog.i(TAG, "onStartCommand: ACTION_CLOSE_BACKGROUND (§16-U1 user-explicit close)")
                controller?.requestUserClose(route.request)
                return START_STICKY
            }
            StartCommandRouter.Route.Bootstrap -> {
                // fall through to the §5 bootstrap body below.
            }
        }
        DebugLog.i(TAG, "onStartCommand: intent=$intent (null=sticky rebuild, §5)")
        val requestedOwnership = OwnershipRequestParser.parse(
            epoch = intent?.getLongExtra(OwnershipRequestParser.EXTRA_EPOCH, -1L)?.takeIf { it >= 0 },
            serverGroupFp = intent?.getStringExtra(OwnershipRequestParser.EXTRA_SERVER_GROUP_FP),
            workdir = intent?.getStringExtra(OwnershipRequestParser.EXTRA_WORKDIR),
            endpointFp = intent?.getStringExtra(OwnershipRequestParser.EXTRA_ENDPOINT_FP),
        )
        // D5-2 (#4): the launcher's monotonic attempt ID. Sticky rebuild
        // (null Intent) leaves attemptId == NO_ATTEMPT_ID — no launcher
        // deadline, validated as a back-compat / internal registration.
        val requestedAttemptId = intent?.getLongExtra(OwnershipRequestParser.EXTRA_ATTEMPT_ID, StreamingOwnershipGate.NO_ATTEMPT_ID)
            ?: StreamingOwnershipGate.NO_ATTEMPT_ID
        // §5 step 1: synchronous minimal persisted-config read.
        val hasValidHost = effectiveConnectionConfigResolver.resolve() != null
        if (!hasValidHost) {
            DebugLog.w(TAG, "onStartCommand: no effective host → stopSelf (§5 step 1)")
            stopSelf()
            return START_STICKY
        }
        // §5 step 2: startForeground within the 5s ANR window, BEFORE any async work.
        promoteToForeground(
            buildNotification(
                SessionStatusNotifier.buildPlaceholder(
                    strings,
                    // Q5: placeholder is a transient (<1s) "connecting" FGS
                    // notification. Always silent — no user value in surfacing
                    // it, and silencing here dodges OEM-ROM heads-up banners on
                    // the cold-start promoteToForeground.
                    silent = true,
                ),
            ),
        )
        // §5 steps 3–6: async bootstrap. CP9 §A6: retain the bootstrap job so
        // a second CP9 bootstrap/start intent on the same Service instance
        // cancels the in-flight one and runs a fresh sequence (a stopSelf +
        // new start that overlap can no longer be stranded by a once-per-
        // instance latch). Single-flight preserved (one active bootstrap at a
        // time); coordinator.onBootstrapResult() remains the only legal
        // L3→running entry.
        bootstrapJob?.cancel()
        val installedJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            controller?.bootstrapAsync()
        }
        bootstrapAbortIssued = false
        bootstrapJob = installedJob
        if (requestedOwnership != null) {
            scope.launch {
                // DIAGNOSTIC TIMING (KEY MEASUREMENT): how long did the main
                // Looper sit on this coroutine before running it? If this
                // queueDelayMs is ~5000ms+, the launcher's Stage-1 ack times
                // out purely due to main-thread dispatch starvation — the
                // Service's Late onStartCommand then sees outcome=Expired.
                val dispatchNs = SystemClock.elapsedRealtimeNanos()
                DebugLog.i(TAG, "onStartCommand: bootstrap coroutine dispatched (queueDelayMs=${(dispatchNs - onStartEntryNs) / 1_000_000L})")
                if (identityStore.isCurrent(requestedOwnership)) {
                    // DIAGNOSTIC TIMING: measure the registerStarting call
                    // (synchronized gate + OwnershipState allocation) and
                    // total elapsed since onStartCommand entry.
                    val preRegNs = SystemClock.elapsedRealtimeNanos()
                    DebugLog.i(TAG, "onStartCommand: registerStarting pre (attemptId=$requestedAttemptId, sinceEntryMs=${(preRegNs - onStartEntryNs) / 1_000_000L})")
                    val outcome = registerStartingOwnership(requestedOwnership, requestedAttemptId)
                    DebugLog.i(TAG, "onStartCommand: registerStarting outcome=$outcome (elapsedSinceEntryMs=${(SystemClock.elapsedRealtimeNanos() - onStartEntryNs) / 1_000_000L})")
                    if (outcome is RegisterStartingOutcome.Accepted) {
                        // Stage 1 ownership recorded — proceed with the §5 bootstrap.
                        installedJob.start()
                    } else {
                        // D5-2 (#4): Expired or Conflict — the launcher has
                        // already given up (5s AckTimeout) OR another owner
                        // holds the gate. ABORT this invocation so it does NOT
                        // register an orphan owner / hold an FGS slot with no
                        // owner. The bootstrap job is cancelled; the shell
                        // teardown (stopForeground + stopSelf + cancel SSE)
                        // runs via the BootstrapFailure teardown path.
                        DebugLog.w(TAG, "onStartCommand: registerStarting outcome=$outcome → abort bootstrap (elapsedSinceEntryMs=${(SystemClock.elapsedRealtimeNanos() - onStartEntryNs) / 1_000_000L})")
                        abortExpiredStartup()
                    }
                } else {
                    ownershipGate.refuse(requestedOwnership, OwnershipRefusal.StaleIdentity)
                    abortExpiredStartup()
                }
            }
        } else {
            installedJob.start()
        }
        return START_STICKY
    }

    /**
     * D4-B B1 Stage 1 — the Service claims the bootstrap/recovery ownership
     * (Starting). Records the disconnect + abortStartup callbacks so the gate
     * can roll back without the Service being asked again. Does NOT complete
     * the launcher's terminal waiter (that is
     * [StreamingOwnershipGate.markReady], called by the lifecycle coordinator
     * only after the acknowledged SSE activation has committed).
     *
     * D5-2 (#4): the [attemptId] (carried in the Service Intent) is validated
     * by the gate. If it has already been expired by the launcher's 5s
     * AckTimeout, the gate returns [RegisterStartingOutcome.Expired] and this
     * invocation must NOT record an owner — the caller (onStartCommand)
     * runs the abort path instead.
     */
    private suspend fun registerStartingOwnership(
        identity: ConnectionIdentity,
        attemptId: Long,
    ): RegisterStartingOutcome {
        if (!identityStore.isCurrent(identity)) {
            ownershipGate.refuse(identity, OwnershipRefusal.StaleIdentity)
            return RegisterStartingOutcome.Expired
        }
        val outcome = ownershipGate.registerStarting(
            identity = identity,
            attemptId = attemptId,
            disconnectAndJoin = { markGap -> sseOwner?.disconnectAndJoin(markGap) },
            // D5-3 (#4 seam): expire-after-Accept can extract this Starting
            // owner before the Service reaches Ready. The abort callback must
            // be symmetric with the late-Expired onStartCommand branch:
            // cancel bootstrap and force the terminal FGS/SSE teardown.
            abortStartup = { abortExpiredStartup() },
        )
        if (outcome is RegisterStartingOutcome.Accepted) {
            acceptedOwnershipIdentity = identity
        }
        return outcome
    }

    /**
     * D5-3 (#4 seam) — terminal abort for a launcher attempt that was accepted
     * at Stage 1 but expired before Stage 2. The gate invokes this callback
     * outside its lock; keep it non-suspending and launch the awaitable
     * teardown on the Service scope. [bootstrapAbortIssued] makes the path
     * idempotent when an expiry callback and another terminal failure race.
     */
    private fun abortExpiredStartup() {
        bootstrapJob?.cancel()
        bootstrapJob = null
        if (bootstrapAbortIssued) return
        bootstrapAbortIssued = true
        scope.launch {
            coordinator.teardownAndAwait(TeardownReason.BootstrapFailure)
        }
    }

    /**
     * D4-B B1 mandatory rollback — bootstrap exhaustion / transport rejection
     * / stale identity. Performs the full 6-step rollback:
     * (1) complete ownership waiters w/ refusal, (2) release Starting
     * ownership, (3) write shared connection state Disconnected,
     * (4) cancel/join any SSE attempt, (5) stop foreground + StopSelf via
     * the coordinator's BootstrapFailure teardown, (6) `stopSelf()`.
     */
    private fun failStarting(identity: ConnectionIdentity?) {
        scope.launch {
            val extracted = ownershipGate.failStarting(
                identity,
                OwnershipRefusal.BootstrapFailed,
            )
            // (4) cancel/join any SSE attempt via the extracted owner callback.
            extracted?.disconnectAndJoin?.invoke(false)
            extracted?.abortStartup?.invoke()
            // (3) write shared connection state Disconnected.
            sharedStateStore.mutateConnection {
                it.copy(
                    isConnected = false,
                    isConnecting = false,
                    connectionPhase = cn.vectory.ocdroid.ui.ConnectionPhase.Disconnected,
                )
            }
            // (5-6) coordinator BootstrapFailure teardown: force L3 +
            // StopSse + StopForeground + StopPoller + StopSelf.
            coordinator.teardownAndAwait(TeardownReason.BootstrapFailure)
        }
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
     * Updates the ongoing notification WITHOUT touching FGS promotion state.
     * Used by the §5 degraded transition (no teardown, no promotion — just
     * surface the Open-app hint).
     *
     * §lint: MissingPermission is satisfied by the runCatching guard (a
     * SecurityException from a denied POST_NOTIFICATIONS or a per-channel
     * mute is observed as a failure and logged; it does NOT crash the
     * Service). Lint's dataflow cannot track runCatching's exception
     * suppression, so the annotation is explicit (mirrors
     * [AppLifecycleMonitor.notifyDecision] / [AppLifecycleMonitor.notifyError]).
     */
    @Suppress("MissingPermission")
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
        // Q5: route the silent variant to CHANNEL_SESSION_STATUS_MIN
        // (IMPORTANCE_MIN), which actually suppresses OEM-ROM heads-up
        // banners; setSilent(true) on an IMPORTANCE_LOW channel is near no-op
        // and CN-ROMs still peek LOW channels. New channel id has no runtime
        // downgrade restriction.
        NotificationCompat.Builder(
            this,
            if (spec.silent) AppLifecycleMonitor.CHANNEL_SESSION_STATUS_MIN
            else AppLifecycleMonitor.CHANNEL_SESSION_STATUS
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
        val intent = Intent(this, SessionStreamingService::class.java).apply {
            action = ACTION_CLOSE_BACKGROUND
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

    /**
     * T5-C4: SSE-side reuse of the 30s-poller's unread-idle detection.
     *
     * When the bridge observes a `session.status{idle}` event for
     * [sessionId], this resolver decides whether to fire an idle
     * notification. It mirrors the predicate the
     * [cn.vectory.ocdroid.ui.controller.BackgroundUnreadPoller] uses
     * (root-only + the SharedStateStore's unread state has the root as
     * unread + an idleSince timestamp) and produces the SAME
     * [cn.vectory.ocdroid.ui.controller.IdleUnreadAlert.key] shape so the
     * ALM-shared dedup set correctly deduplicates a SSE discovery against a
     * concurrent poller discovery (T5-C4a).
     *
     * Returns null for non-root sessions (their idle surfaces on the root's
     * notification via the poller's tree-aware unread evaluator), for roots
     * that the store does not currently consider unread+idle (e.g. the user
     * has already opened them, or the SSE event arrived before the store
     * hydrated), and for sessions not present in the cached session list
     * (the 30s poller will catch those if they genuinely warrant a
     * notification). This is best-effort; it NEVER blocks — the SSE-side
     * bridge is an early-notification path, not the authoritative one.
     */
    private fun resolveRootIdleAlert(sessionId: String): cn.vectory.ocdroid.ui.controller.IdleUnreadAlert? {
        val sessions = sharedStateStore.sessionListFlow.value.sessions
        val session = sessions.firstOrNull { it.id == sessionId } ?: return null
        // Only roots trigger idle notifications — a child session going idle
        // surfaces on the root's notification via the poller's tree-aware
        // unread evaluator (the bridge does not re-implement tree walk).
        if (session.parentId != null) return null
        val unread = sharedStateStore.unreadFlow.value
        val idleSince = unread.idleSince[sessionId] ?: return null
        if (sessionId !in unread.unreadSessions) return null
        val serverId = sharedStateStore.hostFlow.value.currentHostProfileId ?: "default"
        val workdir = settingsManager.currentWorkdir
        return cn.vectory.ocdroid.ui.controller.IdleUnreadAlert(
            rootId = sessionId,
            title = session.title?.takeIf { it.isNotBlank() } ?: sessionId,
            idleSince = idleSince,
            key = cn.vectory.ocdroid.ui.controller.idleNotificationKey(serverId, workdir, sessionId),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        DebugLog.i(TAG, "onDestroy: disconnecting SSE + cancelling service scope")
        // Normal L3 teardown already used the suspend disconnect path and
        // joined the collector before stopSelf. This synchronous fallback only
        // invalidates/cancels a collector left by an abnormal Service destroy;
        // it deliberately emits no false transport-outage signal.
        //
        // D2 gate #4: the [processStatusPoller]'s loop runs on
        // @ApplicationScope and is NOT cancelled here — it survives this
        // Service's death (§6 L3 source contract).
        //
        // D4-B (runBlocking removal): ownership is released via the
        // non-suspend [StreamingOwnershipGate.releaseNow] under a short
        // `synchronized(lock)` section (no main-thread blocking). The prior
        // `runBlocking { release(identity) }` could stall the main thread on
        // a contended coroutine Mutex.
        sseOwner?.cancelForShutdown()
        acceptedOwnershipIdentity?.let { identity ->
            ownershipGate.releaseNow(identity)
        }
        acceptedOwnershipIdentity = null
        // T5-C4: stop the SSE → notification bridge before the scope is
        // cancelled (the scope cancellation would also cancel the bridge's
        // collector, but explicit stop keeps the shutdown order legible:
        // bridge first → no new temp notifications during controller
        // teardown).
        sseNotificationBridge?.stop()
        sseNotificationBridge = null
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
         * CP9 §C15: the bootstrap/start action. The
         * [cn.vectory.ocdroid.service.StreamingServiceLauncher] issues a
         * `startForegroundService(this).setAction(ACTION_BOOTSTRAP)` when
         * foreground AND layer is L3; this Service's [onStartCommand] routes
         * any non-`ACTION_CLOSE_BACKGROUND` action through the §5 bootstrap
         * path (placeholder promotion → async bootstrap → coordinator
         * decision matrix). Declared `const val` so the launcher can
         * reference the literal without going through the Service class.
         */
        const val ACTION_BOOTSTRAP = "cn.vectory.ocdroid.action.BOOTSTRAP"

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
