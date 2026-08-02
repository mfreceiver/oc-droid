package cn.vectory.ocdroid.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.IBinder
import android.os.SystemClock
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
import cn.vectory.ocdroid.service.notify.ForegroundNotificationPublisher
import cn.vectory.ocdroid.service.notify.NotificationSpec
import cn.vectory.ocdroid.service.notify.NotificationStrings
import cn.vectory.ocdroid.service.notify.SessionStatusNotifier
import cn.vectory.ocdroid.service.streaming.BootstrapJobHolder
import cn.vectory.ocdroid.service.streaming.BootstrapRunner
import cn.vectory.ocdroid.service.streaming.ServiceShell
import cn.vectory.ocdroid.service.streaming.ServiceSseConnectionOwner
import cn.vectory.ocdroid.service.streaming.SessionSnapshotProvider
import cn.vectory.ocdroid.service.streaming.SessionStreamingController
import cn.vectory.ocdroid.service.streaming.SseNotificationBridge
import cn.vectory.ocdroid.service.streaming.SseTransportRuntimeStore
import cn.vectory.ocdroid.service.streaming.SseTransportState
import cn.vectory.ocdroid.service.streaming.TransportAttemptToken
import cn.vectory.ocdroid.service.streaming.TransportDropReason
import cn.vectory.ocdroid.service.streaming.UnexpectedTransportDropHandler
import cn.vectory.ocdroid.service.streaming.FencedUnexpectedTransportDropHandler
import cn.vectory.ocdroid.service.streaming.UserCloseRequestParser
import cn.vectory.ocdroid.service.status.StatusAggregator
import cn.vectory.ocdroid.service.status.StatusAggregatorInput
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
// MainActivity import removed (moved to ForegroundNotificationPublisher)
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.WorkdirPaths
// System import removed (moved to ForegroundNotificationPublisher)
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
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
 * [NotificationChannels.createChannels]); this service references the
 * channel id only via [NotificationChannels.CHANNEL_SESSION_STATUS]
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
    /**
     * L4 §2/§3 (M1A/M4): the process-level transport-truth authority. Passed
     * to [sseOwner] (so the owner is the sole producer of transport-state
     * transitions) AND to the [shutdownSeal] (which applies the onDestroy
     * disposition). The single `@Singleton` instance shared with the
     * reconnect supervisor / coordinator (I1: one transport-truth authority).
     */
    @Inject lateinit var runtimeStore: SseTransportRuntimeStore
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
    /**
     * Cluster A / Phase 2: folds [OpenCodeRepository.coldStartSlimSync] /
     * resync snapshots into UI slices. Service-owned SSE path cannot reach
     * SSC via the effect bus alone (no typed SlimColdStart effect yet), so
     * the Service injects SSC and calls [SessionSyncCoordinator.applySlimColdStartSnapshot]
     * from the onResync callback.
     */
    @Inject lateinit var sessionSyncCoordinator: cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator

    // ── D2 (gate #4 / #7): process-level poller + SSE recovery policy. ──

    @Inject lateinit var processStatusPoller: cn.vectory.ocdroid.service.streaming.ProcessStatusPoller
    @Inject lateinit var sseRecoveryPolicy: cn.vectory.ocdroid.service.streaming.SseRecoveryPolicy
    @Inject lateinit var bootstrapRetryPolicy: cn.vectory.ocdroid.service.streaming.BootstrapRetryPolicy

    /**
     * §U-P2 (Batch 2): the independent optimistic-claim watchdog coordinator.
     * Bound to the SAME connection lifetime as [processStatusPoller] —
     * [Shell.startPoller] / [Shell.ensurePoller] call [OptimisticClaimWatchdogCoordinator.start]
     * and [Shell.stopPoller] / [Shell.enterNoSourceTerminal] call
     * [OptimisticClaimWatchdogCoordinator.stop]. The watchdog runs its OWN
     * 5s timer (independent of the 30s poller tick) so a stale optimistic
     * claim is detected within one OPTIMISTIC_CONFIRM_TIMEOUT_MS window.
     */
    @Inject lateinit var watchdogCoordinator: cn.vectory.ocdroid.service.streaming.OptimisticClaimWatchdogCoordinator

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
     * Builds Android [Notification] from a pure-JVM [NotificationSpec].
     * Encapsulates the NotificationCompat.Builder + PendingIntent construction.
     */
    private lateinit var notificationPublisher: ForegroundNotificationPublisher

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
     * L4 §4.4 (M4): owns the runtime/ownership side-effects for transport
     * drops (injected into [sseOwner] as its [UnexpectedTransportDropHandler])
     * AND the onDestroy shutdown disposition. Built in [onCreate] from the
     * shared [runtimeStore] + [ownershipGate]; a pure-JVM seam so the
     * disposition contract is unit-testable without a real Android Service.
     */
    private lateinit var shutdownSeal: SseShutdownSeal

    /**
     * CP9 §C15: the bootstrap restart latch. Retained as a `Job?` so a second
     * CP9 bootstrap/start intent on the same Service instance can cancel an
     * in-flight bootstrap and run a fresh one (a `stopSelf()` + a new start
     * that overlap can no longer be stranded by a once-per-instance latch).
     * Single-flight is preserved (one active bootstrap at a time); the
     * coordinator's `onBootstrapResult()` remains the only legal L3→running
     * entry.
     */
    /**
     * §sse-zombie-fix (v4 R2/R3): the in-flight bootstrap job, held by a
     * thread-safe [BootstrapJobHolder] instead of a plain var. Install =
     * single atomic swap; invalidate = CAS on the exact expected reference —
     * a stale teardown can NEVER null/cancel a replacement attempt's job,
     * and the Dispatchers.Default registration coroutine gets proper
     * cross-thread visibility (AtomicReference happens-before).
     */
    private val bootstrapJobHolder = BootstrapJobHolder()
    /**
     * D5-3: prevents duplicate terminal teardown from expiry/abort races.
     * §sse-zombie-fix (v4): AtomicBoolean (was plain Boolean) to close the
     * check-then-set data race between the launcher-side Timeout callback and
     * onStartCommand's reset.
     */
    private val bootstrapAbortIssued = java.util.concurrent.atomic.AtomicBoolean(false)
    private var acceptedOwnershipIdentity: ConnectionIdentity? = null

    /**
     * §sse-zombie-fix (v3): per-Service bootstrap generation counter. Each
     * [registerStartingOwnership] call captures the post-increment value into
     * the disconnect/abort lambdas, so a stale teardown whose generation no
     * longer matches the current [bootstrapEpoch] becomes a strict no-op
     * (guard evaluated under [ServiceSseConnectionOwner.connectMutex] /
     * reference-checked before touching [bootstrapJob]). Closes the
     * check-then-suspend window an AtomicLong-only fence cannot (issue #2).
     */
    private val bootstrapEpoch = AtomicLong(0L)

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
            promoteToForeground(notificationPublisher.build(spec))
        override fun updateNotification(spec: NotificationSpec) =
            notifyOngoing(notificationPublisher.build(spec))
        override fun stopForeground() {
            ServiceCompat.stopForeground(
                this@SessionStreamingService,
                ServiceCompat.STOP_FOREGROUND_DETACH,
            )
        }
        override fun serviceStopSelf() {
            // M4 (Wave-1 Rework finding #2): ALL deliberate stopSelf paths are
            // INTENTIONAL. The controller routes every coordinator-driven L3
            // teardown (StopSelf command, user-close, bootstrap teardown) through
            // this single shell method, so marking here covers EVERY deliberate
            // route — not only paths that happen to call owner StopSse first.
            // System/process destruction (which does NOT go through here) retains
            // the default UNEXPECTED disposition.
            shutdownSeal.markIntentional()
            this@SessionStreamingService.stopSelf()
        }
        override suspend fun startPoller(
            identity: ConnectionIdentity,
            snapshot: cn.vectory.ocdroid.service.status.StatusSnapshot,
        ): cn.vectory.ocdroid.service.streaming.SourceActivation {
            // D2 gate #4: delegate to the process-level poller. The loop
            // runs on @ApplicationScope and survives this Service's death.
            val activation = processStatusPoller.startAndAwaitFirstPoll(identity, snapshot)
            // §U-P2: start the independent watchdog alongside the poller
            // (same connection lifetime). The watchdog's own generation
            // fence makes a repeat start() safe.
            // §rev-gpt gate r1 BLOCKER #5: do NOT start the watchdog when the
            // activation was Rejected (Superseded by a later StopPoller/EnsurePoller,
            // StaleIdentity, etc.) — a late-returning superseded activation would
            // otherwise re-arm the watchdog AFTER a stop() (e.g. the winning
            // StopPoller already tore it down). Only a Ready activation owns the
            // connection lifetime the watchdog must track.
            if (activation is cn.vectory.ocdroid.service.streaming.SourceActivation.Ready) {
                watchdogCoordinator.start()
            }
            return activation
        }
        override fun stopPoller() {
            // D2 gate #4: cancel the process-level poller loop. The
            // coordinator emits StopPoller on the L2Idle→L2Active commit OR
            // when the DebounceFire handoff cancels a non-idle poller.
            processStatusPoller.stop()
            // §U-P2: stop the watchdog with the poller (same connection
            // lifetime). Idempotent.
            watchdogCoordinator.stop()
        }
        override suspend fun ensurePoller(
            identity: ConnectionIdentity,
            snapshot: cn.vectory.ocdroid.service.status.StatusSnapshot,
        ): cn.vectory.ocdroid.service.streaming.SourceActivation {
            // D5 (#2): delegate to the process-level poller's ensureRunning.
            // Idempotent for the same identity (no cancel/restart).
            val activation = processStatusPoller.ensureRunning(identity, snapshot)
            // §U-P2: the supplemental-poller path ALSO keeps the watchdog
            // armed (a supplemental poller means the connection is still
            // active — the watchdog must keep reconciling stale claims).
            // §rev-gpt gate r2 follow-up: symmetric Ready guard (matches
            // startPoller) — a Rejected ensurePoller does NOT own the
            // connection lifetime, so it must not re-arm the watchdog.
            if (activation is cn.vectory.ocdroid.service.streaming.SourceActivation.Ready) {
                watchdogCoordinator.start()
            }
            return activation
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
        override suspend fun enterNoSourceTerminal() {
            // L4 §4.4: composite terminal teardown. Each stop is individually
            // try/caught so a failure in one does not skip the others.
            // Policy is already NO_SOURCE_TERMINAL before this is called.
            // M4: no-source terminal is an INTENTIONAL destruction — mark the
            // disposition so onDestroy publishes Stopped (never Dropped).
            shutdownSeal.markIntentional()
            DebugLog.i(TAG, "enterNoSourceTerminal: stopping all sources")
            runCatching { sseNotificationBridge?.stop() }
                .onFailure { DebugLog.w(TAG, "enterNoSourceTerminal: sseNotificationBridge.stop failed — ${it.message}") }
            runCatching { sseOwner?.disconnect(markGap = false) }
                .onFailure { DebugLog.w(TAG, "enterNoSourceTerminal: sseOwner.disconnect failed — ${it.message}") }
            runCatching { processStatusPoller.stop() }
                .onFailure { DebugLog.w(TAG, "enterNoSourceTerminal: processStatusPoller.stop failed — ${it.message}") }
            runCatching { watchdogCoordinator.stop() }
                .onFailure { DebugLog.w(TAG, "enterNoSourceTerminal: watchdogCoordinator.stop failed — ${it.message}") }
            runCatching { appLifecycleMonitor.stopBackgroundPollingForNoSource() }
                .onFailure { DebugLog.w(TAG, "enterNoSourceTerminal: stopBackgroundPollingForNoSource failed — ${it.message}") }
            runCatching {
                ServiceCompat.stopForeground(
                    this@SessionStreamingService,
                    ServiceCompat.STOP_FOREGROUND_DETACH,
                )
            }.onFailure { DebugLog.w(TAG, "enterNoSourceTerminal: stopForeground failed — ${it.message}") }
            runCatching { this@SessionStreamingService.stopSelf() }
                .onFailure { DebugLog.w(TAG, "enterNoSourceTerminal: stopSelf failed — ${it.message}") }
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
        notificationPublisher = ForegroundNotificationPublisher(this, identityStore)
        // L4 §4.4 (M4): build the shutdown-disposition seam from the shared
        // runtime store + ownership gate. It doubles as the owner's
        // UnexpectedTransportDropHandler (releases ownership then publishes
        // the drop) and applies the onDestroy disposition (Stopped vs
        // SERVICE_DESTROYED).
        shutdownSeal = SseShutdownSeal(runtimeStore, ownershipGate)
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
            // L4 §3.1: wire the foreground gate so SSE does NOT (re)connect
            // while the app is in the background. Uses the SAME
            // [AppLifecycleMonitor.isInForeground] singleton that lane 1
            // wired the token-stream gate to, ensuring a consistent
            // foreground-truth source.
            reconnectAllowed = { appLifecycleMonitor.isInForeground.value },
            onTerminalExhaustion = { coordinator.onDisconnect() },
            // Cluster A / Phase 2 (P2.4 + P2.5): resync AND first-transport-ready
            // share the same cold-start path (v1 contract §4: resync = reuse
            // cold-start). directories from directorySessions keys + current
            // workdir.
            //
            // T11 round-2 (oracle I1): wired to
            // [SessionSyncCoordinator.performSlimResync] — the SINGLE
            // orchestrator that (1) captures focus + pre-refresh known
            // SIDs, (2) calls coldStartSlimSync (metadata-only,
            // openSessionId=null), (3) folds the snapshot, (4) builds the
            // catch-up union, (5) runs performResyncCatchUp with
            // ReconcileMode.RESYNC for every sid. Round-1 called
            // coldStartSlimSync directly which left performResyncCatchUp
            // as dead code (T11 review I1).
            // ι-Q3a: 用 supportsWatermarkResync 替换原始 transport flag。此 onResync 接
            // SessionSyncCoordinator.performSlimResync（见上注释 :347-355）——slim SSE
            // cold-start + watermark catch-up（coldStartSlimSync + performResyncCatchUp），
            // 与 TokenStreamCoordinator / slimapiTokenStreamEnabled 无关。故门用
            // supportsWatermarkResync（与 performSlimResync 内部门一致），
            // 非 supportsTokenStreamResync（后者额外要求 tokenStream 特性，会在 slim +
            // 特性未探/未公告时误杀整段 resync）。
            // [rev-grok NO-GO 5.0→修复：原误用 supportsTokenStreamResync]
            onResync = onResync@{ isStillCurrent ->
                if (!isStillCurrent()) return@onResync
                if (!repository.supportsWatermarkResync) return@onResync
                val directories = buildList {
                    sharedStateStore.slices.sessionList.value.directorySessions.keys
                        .forEach { add(it) }
                    settingsManager.currentWorkdir?.let { add(it) }
                }
                    // slimapi v0.2.2 T4 (P2b): normalize each entry through
                    // the SAME server-facing [WorkdirPaths.normalizeDirectory]
                    // used by [computeQuestionFanOutWorkdirs] so this resync
                    // fan-out agrees with the q/p fan-out + the server's
                    // normalize-dedup. Without this, "/app" + "/app/" (one
                    // from directorySessions.keys, one from currentWorkdir)
                    // would survive `.distinct()` pre-normalize and ship as
                    // 2 `?directory=` entries.
                    //
                    // T4-M1 (final review D7): `.filter { isNotBlank }`
                    // BEFORE normalize mirrors [computeQuestionFanOutWorkdirs]
                    // — without it a blank key / blank currentWorkdir would
                    // normalize to "/" (root) and ship as `directory=/`,
                    // diverging from the q/p fan-out which drops blanks
                    // entirely. Same blank-handling alignment, no new
                    // imports (filter is local; normalizeDirectory already
                    // imported per T4).
                    .filter { it.isNotBlank() }
                    .map { WorkdirPaths.normalizeDirectory(it) }
                    .distinct()
                    .ifEmpty { null }
                DebugLog.i(
                    "SessionStreamingService",
                    "slim onResync directories=$directories",
                )
                // lite-v2-dev: performSlimResync retired (plan §4.1/§4.7).
                // The onResync path now ONLY triggers skeleton reload via
                // reconcileFullAfterTransportReset (which routes to
                // SkeletonReloadCoordinator.requestReload). The slim resync
                // cadence / metadata refresh / catch-up sweep have been retired.
                sessionSyncCoordinator.reconcileFullAfterTransportReset(
                    isStillCurrent = isStillCurrent,
                )
            },
            // L4 §2/§3 (M1A/M4): the shared transport-truth authority. The
            // owner is the sole producer of transport-state transitions.
            runtimeStore = runtimeStore,
            // L4 §3/§4.4 (M4): the injected drop handler. The owner routes
            // every unexpected transport drop through this seam; it releases
            // ownership BEFORE publishing the drop (I3).
            dropHandler = shutdownSeal,
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
                // §sse-zombie-fix (v4): sticky path re-reads the CURRENT job
                // via the thread-safe holder. This lambda runs on the
                // controller's Main.immediate scope — single-threaded with
                // onStartCommand's install — so the read is deterministic.
                // The gate idempotent-Accepts this same-identity registration
                // and RETAINS the original launcher-path callbacks; these
                // captures never fire as gate callbacks.
                registerStartingOwnership(
                    identity,
                    StreamingOwnershipGate.NO_ATTEMPT_ID,
                    bootstrapJobHolder.current(),
                    requireNotNull(sseOwner) { "sseOwner constructed in onCreate before any bootstrap" },
                )
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
            profileId = intent?.getStringExtra(UserCloseRequestParser.EXTRA_PROFILE_ID),
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
            profileId = intent?.getStringExtra(OwnershipRequestParser.EXTRA_PROFILE_ID),
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
            // M4: no-host stop is intentional (no transport to recover).
            shutdownSeal.markIntentional()
            stopSelf()
            return START_STICKY
        }
        // §5 step 2: startForeground within the 5s ANR window, BEFORE any async work.
        promoteToForeground(
            notificationPublisher.build(
                SessionStatusNotifier.buildPlaceholder(
                    strings,
                    // Q5: placeholder is a transient (<1s) "connecting" FGS
                    // notification. Always silent — no user value in surfacing
                    // it, and silencing here dodges OEM-ROM heads up banners on
                    // the cold-start promoteToForeground.
                    silent = true,
                ),
            ),
        )
        // Wave-1 M4 (blocker #4): re-arm the onDestroy disposition to UNEXPECTED
        // on each newly accepted bootstrap. Reaching here means a valid host
        // exists and this Service instance is starting a fresh bootstrap
        // lifecycle, so any INTENTIONAL marker left by a prior stopSelf (or
        // other intentional path) on this same instance MUST be cleared —
        // otherwise it would poison a later UNEXPECTED destruction after a
        // start overlap. Any intentional teardown that follows (bootstrap
        // rollback / no-source terminal / user close) re-marks INTENTIONAL, so
        // re-arming to the safe default here is always correct. Runs on the
        // Main thread, serialized with onDestroy (which reads the flag).
        shutdownSeal.rearmUnexpected()
        // §5 steps 3–6: async bootstrap. CP9 §A6: retain the bootstrap job so
        // a second CP9 bootstrap/start intent on the same Service instance
        // cancels the in-flight one and runs a fresh sequence (a stopSelf +
        // new start that overlap can no longer be stranded by a once-per-
        // instance latch). Single-flight preserved (one active bootstrap at a
        // time); coordinator.onBootstrapResult() remains the only legal
        // L3→running entry.
        // §sse-zombie-fix (v4 R3): single atomic swap replaces the old
        // `bootstrapJob?.cancel(); bootstrapJob = installedJob` two-step,
        // which left a window in which a stale teardown could null/cancel a
        // REPLACEMENT attempt's job. getAndSet is atomic — a stale
        // removeIfCurrent either wins BEFORE this swap (cancels the old job,
        // which we then harmlessly re-cancel below) or loses (observes the
        // new reference and no-ops). No interleaving can strand either job.
        val installedJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            controller?.bootstrapAsync()
        }
        bootstrapAbortIssued.set(false)
        bootstrapJobHolder.install(installedJob)?.cancel()
        if (requestedOwnership != null) {
            // SSE-cold-start-fix (root cause A — main-thread starvation): run
            // the Stage-1 ownership registration on Dispatchers.Default so the
            // ack (`pending.starting.complete(Accepted)` — a synchronized,
            // non-suspending op inside the gate) completes WITHOUT waiting
            // behind a busy main Looper. The bug logs showed a Stage-1
            // AckTimeout at exactly ~5002ms, matching the launcher's 5s
            // [OwnershipAckPolicy] window — i.e. the registration coroutine
            // sat in the Main dispatch queue for ~5s during cold start. The
            // gate is fully synchronized/thread-safe; [installedJob.start()]
            // and [abortExpiredStartup] are safe to invoke from any dispatcher
            // (the bootstrap job itself runs on [scope]'s Main dispatcher;
            // teardown is launched on [scope] = Main).
            //
            // The [queueDelayMs] diagnostic is KEPT so the next run can read
            // the value and confirm it is now small (Default-dispatcher
            // latency) rather than ~5000ms (Main-queue starvation).
            // §sse-zombie-fix (v4 R1/R2): capture immutable per-invocation
            // handles on the MAIN thread, BEFORE the registration coroutine
            // hops to Dispatchers.Default. registerStartingOwnership and the
            // gate callbacks it installs may reference ONLY these captures —
            // never the mutable Service fields. sseOwner is write-once after
            // onCreate (onDestroy does not null it either), so this capture is
            // a stable per-Service-instance reference.
            val sse = requireNotNull(sseOwner) {
                "onCreate constructs sseOwner before any onStartCommand"
            }
            scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                // DIAGNOSTIC TIMING (KEY MEASUREMENT): how long did the
                // dispatcher sit on this coroutine before running it? Post-fix
                // this measures Dispatchers.Default latency (expected ~0ms),
                // NOT main-Looper queueing.
                val dispatchNs = SystemClock.elapsedRealtimeNanos()
                DebugLog.i(TAG, "onStartCommand: bootstrap coroutine dispatched (queueDelayMs=${(dispatchNs - onStartEntryNs) / 1_000_000L})")
                if (identityStore.isCurrent(requestedOwnership)) {
                    // DIAGNOSTIC TIMING: measure the registerStarting call
                    // (synchronized gate + OwnershipState allocation) and
                    // total elapsed since onStartCommand entry.
                    val preRegNs = SystemClock.elapsedRealtimeNanos()
                    DebugLog.i(TAG, "onStartCommand: registerStarting pre (attemptId=$requestedAttemptId, sinceEntryMs=${(preRegNs - onStartEntryNs) / 1_000_000L})")
                    val outcome = registerStartingOwnership(
                        requestedOwnership,
                        requestedAttemptId,
                        installedJob,
                        sse,
                    )
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
                        // runs via the BootstrapFailure teardown path UNLESS a
                        // newer attempt has taken over the gate (see
                        // [abortExpiredStartup] teardown-scope guard).
                        DebugLog.w(TAG, "onStartCommand: registerStarting outcome=$outcome → abort bootstrap (elapsedSinceEntryMs=${(SystemClock.elapsedRealtimeNanos() - onStartEntryNs) / 1_000_000L})")
                        abortExpiredStartup(requestedAttemptId, expectedJob = installedJob)
                    }
                } else {
                    ownershipGate.refuse(requestedOwnership, OwnershipRefusal.StaleIdentity)
                    abortExpiredStartup(requestedAttemptId, expectedJob = installedJob)
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
        // §sse-zombie-fix (v4 R1/R2): immutable per-invocation handles passed
        // by the caller. The gate callbacks capture ONLY these (and
        // myEpoch/attemptId/bootstrapEpoch/bootstrapJobHolder.current()) —
        // NEVER the Service's mutable fields. This makes R1/R2 structurally
        // impossible (the closures literally cannot name this.sseOwner or a
        // mutable bootstrapJob var).
        job: Job?,
        sse: ServiceSseConnectionOwner,
    ): RegisterStartingOutcome {
        if (!identityStore.isCurrent(identity)) {
            ownershipGate.refuse(identity, OwnershipRefusal.StaleIdentity)
            return RegisterStartingOutcome.Expired
        }
        // §sse-zombie-fix (v3/v4): epoch fence for the SSE-side guard inside
        // disconnectWithGuard (evaluated under connectMutex). The launcher
        // path increments; the sticky path (NO_ATTEMPT_ID) does NOT — see
        // comment below (R4).
        //
        // §sse-zombie-fix-impl-rev1 (GPT impl-review critical #1): the sticky
        // registration path MUST NOT increment the epoch. The gate treats this
        // as same-identity idempotent Accepted (:300) and deliberately RETAINS
        // the ORIGINAL callbacks — which captured epoch N. If the sticky path
        // increments to N+1, those retained callbacks now permanently fail the
        // N-vs-N+1 guard, defeating the entire fence for all subsequent
        // teardowns. Only the launcher path (validated attemptId) increments;
        // the sticky path reads the current value (the launcher's epoch).
        val myEpoch = if (attemptId != StreamingOwnershipGate.NO_ATTEMPT_ID) {
            bootstrapEpoch.incrementAndGet()
        } else {
            bootstrapEpoch.get()
        }
        val outcome = ownershipGate.registerStarting(
            identity = identity,
            attemptId = attemptId,
            disconnectAndJoin = { markGap ->
                // §sse-zombie-fix (v4 R1): references ONLY the immutable [sse]
                // capture + the AtomicLong epoch guard (evaluated under
                // connectMutex inside disconnectWithGuard). No Service mutable
                // field appears in this closure — by construction. A stale
                // teardown whose epoch no longer matches is a strict no-op.
                sse.disconnectWithGuard(markGap) { bootstrapEpoch.get() == myEpoch }
            },
            // D5-3 (#4 seam): expire-after-Accept can extract this Starting
            // owner before the Service reaches Ready. The abort callback must
            // be symmetric with the late-Expired onStartCommand branch:
            // cancel bootstrap and force the terminal FGS/SSE teardown.
            //
            // SSE-cold-start-fix: capture [attemptId] so the boundary-race
            // abort (expire-after-Accept) scopes the teardown-scope guard to
            // THIS attempt — a NEWER attempt that took over the gate must NOT
            // be destroyed by this expiry teardown.
            //
            // §sse-zombie-fix (v4 R2/R3): fence on epoch + the caller-supplied
            // [job] reference (threaded into abortExpiredStartup →
            // removeIfCurrent CAS). A stale abort that fires AFTER a newer
            // attempt has taken over neither cancels the newer job nor
            // triggers a Service-wide teardown.
            abortStartup = {
                val current = bootstrapJobHolder.current()
                if (bootstrapEpoch.get() == myEpoch && (job == null || current === job)) {
                    abortExpiredStartup(attemptId, expectedJob = job)
                } else {
                    DebugLog.i(
                        TAG,
                        "abortStartup: skipped — epoch mine=$myEpoch current=${bootstrapEpoch.get()}, " +
                            "job superseded=${current !== job}; newer attempt owns the Service",
                    )
                }
            },
        )
        if (outcome is RegisterStartingOutcome.Accepted) {
            acceptedOwnershipIdentity = identity
        }
        return outcome
    }

    /**
     * Wave-3 L3c — the two bootstrap-failure rollback paths merged into a
     * single entry keyed by [BootstrapRollbackKind]. The two paths share
     * only the final `coordinator.teardownAndAwait(BootstrapFailure)` call;
     * they DIFFER semantically:
     *  - **Timeout**: cancels [bootstrapJob] + is idempotent via
     *    [bootstrapAbortIssued] (Timeout-path-ONLY guard). Does NOT perform
     *    ownership rollback — the launcher already expired the attempt
     *    (no Starting owner to release on this Service side).
     *  - **Failed**: performs the full D4-B B1 rollback —
     *    `ownershipGate.failStarting` (refuse waiters + release Starting),
     *    disconnect/join the SSE attempt, write shared connection state
     *    Disconnected — THEN teardown. Does NOT touch [bootstrapAbortIssued]
     *    (preserve prior behavior).
     *
     * **CRITICAL invariant**: the Timeout branch MUST NOT call
     * `failStarting` / ownership rollback; the Failed branch MUST NOT touch
     * [bootstrapAbortIssued]. Merging the entry must not merge the
     * side-effects.
     */
    private enum class BootstrapRollbackKind { Timeout, Failed }

    private fun rollbackBootstrap(
        kind: BootstrapRollbackKind,
        identity: ConnectionIdentity?,
        attemptId: Long = StreamingOwnershipGate.NO_ATTEMPT_ID,
        // §sse-zombie-fix (v4 R3): the caller's own job reference, threaded
        // into the BootstrapJobHolder CAS. A stale teardown whose expected
        // reference was superseded by a replacement is a strict no-op (CAS
        // fails, slot untouched). Defaults to null (sticky/internal callers
        // that have no per-invocation job identity).
        expectedJob: Job? = null,
    ) {
        // M4: bootstrap rollback (timeout / failed) is an intentional teardown
        // that leads to stopSelf — mark the disposition so onDestroy does not
        // publish a spurious SERVICE_DESTROYED drop.
        //
        // §sse-zombie-fix-impl-rev2 (rev-gpt v4-impl-review critical C5):
        // markIntentional MUST be deferred until AFTER this rollback has
        // confirmed it will actually tear down the Service. The Timeout branch
        // has two early-returns that cancel ONLY this attempt's job without a
        // service teardown (newer-attempt guard + CAS-abort-issued guard); a
        // stale rollback that enters, marks intentional, then early-returns
        // would corrupt the shutdown disposition of the (still-running)
        // replacement's Service — onDestroy would later suppress the required
        // SERVICE_DESTROYED drop. Each teardown-committing branch marks below.
        when (kind) {
            BootstrapRollbackKind.Timeout -> {
                // D5-3 (#4 seam) — terminal abort for a launcher attempt that
                // was accepted at Stage 1 but expired before Stage 2. The gate
                // invokes this callback outside its lock; keep it
                // non-suspending and launch the awaitable teardown on the
                // Service scope. [bootstrapAbortIssued] makes the path
                // idempotent when an expiry callback and another terminal
                // failure race. NO ownership rollback here (CRITICAL invariant:
                // the Timeout branch MUST NOT call failStarting / ownership
                // rollback).
                //
                // §sse-zombie-fix (v4): [bootstrapAbortIssued] is now an
                // AtomicBoolean — atomic check-and-set (was plain-var
                // check-then-set race between launcher Timeout callback and
                // onStartCommand reset).
                //
                // SSE-cold-start-fix (root cause B — teardown scope by
                // attemptId): before tearing down the shared Service component,
                // verify the gate has NOT been taken over by a NEWER attempt.
                // If it has, a full teardown (StopForeground + StopSelf) would
                // destroy that newer attempt's bootstrap too — exactly the
                // dual-fire kill seen in the bug logs. In that case cancel
                // ONLY this job; the newer attempt owns the service.
                // The check is attemptId-scoped (NOT identity — the bug is two
                // attempts for the SAME identity). [NO_ATTEMPT_ID] (sticky /
                // controller-internal registration, no launcher deadline) skips
                // the guard and teardowns as before.
                if (attemptId != StreamingOwnershipGate.NO_ATTEMPT_ID &&
                    ownershipGate.hasLiveAttemptOtherThan(attemptId)
                ) {
                    DebugLog.i(
                        TAG,
                        "rollbackBootstrap(Timeout): newer attempt holds the gate " +
                            "(expiredAttemptId=$attemptId) → cancel job only, skip service teardown",
                    )
                    // §sse-zombie-fix (v4 R3): CAS — null + cancel ONLY if the
                    // slot still holds THIS attempt's own job. A replacement
                    // installed in the window makes removeIfCurrent return null:
                    // strict no-op (the slot then still holds the replacement's
                    // job, untouched).
                    if (expectedJob != null) {
                        bootstrapJobHolder.removeIfCurrent(expectedJob)?.cancel()
                    }
                    // C5: NO markIntentional here — this path does NOT tear
                    // down the Service; the newer attempt still owns it.
                    return
                }
                // §sse-zombie-fix (v4): atomic check-and-set (was plain-var
                // check-then-set race).
                if (!bootstrapAbortIssued.compareAndSet(false, true)) return
                // C5: this branch commits to a full Service teardown → mark.
                shutdownSeal.markIntentional()
                // §sse-zombie-fix (v4 R3): same CAS discipline — cancel the
                // caller's own job if supplied, else whatever is current.
                val jobToCancel = expectedJob ?: bootstrapJobHolder.current()
                if (jobToCancel != null) {
                    bootstrapJobHolder.removeIfCurrent(jobToCancel)?.cancel()
                }
                scope.launch {
                    coordinator.teardownAndAwait(TeardownReason.BootstrapFailure)
                }
            }
            BootstrapRollbackKind.Failed -> {
                // D4-B B1 mandatory rollback — bootstrap exhaustion / transport
                // rejection / stale identity. Performs the full 6-step rollback:
                // (1) complete ownership waiters w/ refusal, (2) release Starting
                // ownership, (3) write shared connection state Disconnected,
                // (4) cancel/join any SSE attempt, (5) stop foreground + StopSelf
                // via the coordinator's BootstrapFailure teardown, (6) `stopSelf()`.
                // Does NOT touch bootstrapAbortIssued (preserve prior behavior).
                // C5: this branch always commits to a full Service teardown → mark.
                shutdownSeal.markIntentional()
                scope.launch {
                    val extracted = ownershipGate.failStarting(
                        identity,
                        OwnershipRefusal.BootstrapFailed,
                    )
                    // §sse-zombie-fix (v4 R5): route through [runTeardown] — the
                    // single legal teardown helper (try/finally guarantees abort
                    // even if disconnect throws / coroutine is cancelled).
                    extracted?.runTeardown(markGap = false)
                    // (3) write shared connection state Disconnected.
                    // §red-dot-trace: surface the silent B1 bootstrap-failure
                    // disconnect so the red indicator is traceable.
                    DebugLog.w(TAG, "session streaming service bootstrap failed (identity epoch=${identity?.epoch}) -> Disconnected")
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
        }
    }

    /**
     * Timeout-path rollback: launcher expiry / stale identity. Thin wrapper
     * over [rollbackBootstrap] (kept for external call sites + doc
     * references). NO ownership rollback; idempotent via [bootstrapAbortIssued].
     *
     * SSE-cold-start-fix: [attemptId] scopes the teardown-scope guard in
     * [rollbackBootstrap] so a superseded attempt does not teardown a newer
     * attempt's Service. Defaults to [StreamingOwnershipGate.NO_ATTEMPT_ID]
     * (sticky / internal) → no guard, teardown as before.
     *
     * §sse-zombie-fix (v4 R3): [expectedJob] threads the caller's own job
     * reference into the BootstrapJobHolder CAS.
     */
    private fun abortExpiredStartup(
        attemptId: Long = StreamingOwnershipGate.NO_ATTEMPT_ID,
        expectedJob: Job? = null,
    ) = rollbackBootstrap(BootstrapRollbackKind.Timeout, null, attemptId, expectedJob)

    /**
     * Failed-path rollback: bootstrap exhaustion / transport rejection /
     * stale identity. Thin wrapper over [rollbackBootstrap] — performs the
     * full B1 ownership rollback THEN teardown. Does NOT touch
     * [bootstrapAbortIssued] (preserve prior behavior).
     */
    private fun failStarting(identity: ConnectionIdentity?) =
        rollbackBootstrap(BootstrapRollbackKind.Failed, identity)

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
        // M4: platform dataSync timeout teardown is intentional.
        shutdownSeal.markIntentional()
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

    // ── buildNotification/buildCloseBackgroundPendingIntent/buildOpenActivityPendingIntent moved to ForegroundNotificationPublisher ──

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

    /**
     * Wave-1 M4 (blockers #1 + #2 + #3): the approved onDestroy order is:
     *  1. **capture active attempt** (from runtimeStore, before any runtime
     *     terminalization — [SseTransportRuntimeStore.currentAttempt] returns
     *     null once Dropped/Stopped);
     *  2. **release ownership + apply disposition** via [SseShutdownSeal] —
     *     ownership released FIRST (I3), then INTENTIONAL → markStopped /
     *     UNEXPECTED → publishDropped(SERVICE_DESTROYED) using the CAPTURED
     *     attempt directly. This runs BEFORE cancelForShutdown so the captured
     *     attempt is still canonical: an unacknowledged recovery attempt
     *     naturally preserves its original drop ticket (I4), and no
     *     replacement attempt / observable Connecting fallback is ever created.
     *  3. **close/cancel the owner** ([ServiceSseConnectionOwner.cancelForShutdown])
     *     — generation bump + closing marker + collector cancel ensure NO late
     *     frame can mutate the runtime AFTER the disposition. Its own
     *     markStopped is now a harmless no-op (the runtime is already terminal
     *     from step 2). [processStatusPoller] runs on @ApplicationScope and is
     *     NOT cancelled here — it survives this Service's death (§6 L3).
     *  4. **cancel scope** — structural cancellation of all remaining jobs.
     *
     * Late-frame safety (blocker #2): the disposition (step 2) terminalizes
     * the runtime to Dropped/Stopped, so a stale collector frame's markLive is
     * already rejected by the runtime's canonical-attempt validation BEFORE
     * cancelForShutdown (step 3) bumps the generation. The two are
     * complementary backstops; ordering disposition first keeps the captured
     * attempt usable.
     *
     * Idempotent: a repeated onDestroy (or a terminal path already handled by
     * the owner) is a clean no-op — [destroyGuard] prevents double-execution;
     * [SseShutdownSeal.applyDestructionDisposition] + [releaseNow] + the
     * runtime's canonical-attempt validation are all individually idempotent.
     */
    @Volatile
    private var destroyGuard: Boolean = false

    override fun onDestroy() {
        if (destroyGuard) {
            DebugLog.i(TAG, "onDestroy: already destroyed — idempotent no-op")
            return
        }
        destroyGuard = true
        DebugLog.i(TAG, "onDestroy: applying disposition + closing owner + cancelling scope")
        // Step 1: capture the active attempt BEFORE any runtime terminalization.
        // The runtime is the source of truth; currentAttempt returns null once
        // the owner already handled a terminal path (Dropped/Stopped) — a null
        // capture means "no duplicate drop" (M4-3).
        val destroyedIdentity = acceptedOwnershipIdentity
        val destroyedAttempt = destroyedIdentity?.let { runtimeStore.currentAttempt(it) }
        acceptedOwnershipIdentity = null
        // Step 2: apply the disposition FIRST, while the captured attempt is
        // still canonical. The seal releases ownership (I3) then:
        //  - INTENTIONAL → markStopped(capturedAttempt);
        //  - UNEXPECTED  → publishDropped(capturedAttempt, SERVICE_DESTROYED).
        // Using the captured attempt directly means an unacknowledged recovery
        // attempt naturally preserves its original ticket (I4) — NO replacement
        // attempt, NO observable Connecting fallback (blockers #1 + #2).
        shutdownSeal.applyDestructionDisposition(destroyedIdentity, destroyedAttempt)
        // Step 3: close/cancel the owner — generation bump + closing marker +
        // collector cancel so no late frame can mutate the runtime post-
        // disposition. Its own markStopped is now a harmless no-op (the runtime
        // is already terminal from step 2). cancelForShutdown runs on the same
        // (Main) thread as onDestroy, so no interleaving terminalization can
        // occur between capture and disposition.
        //
        // D2 gate #4: the [processStatusPoller]'s loop runs on
        // @ApplicationScope and is NOT cancelled here — it survives this
        // Service's death (§6 L3 source contract).
        sseOwner?.cancelForShutdown()
        // Step 4: clean up remaining collaborators + cancel the scope.
        // T5-C4: stop the SSE → notification bridge before the scope is
        // cancelled (the scope cancellation would also cancel the bridge's
        // collector, but explicit stop keeps the shutdown order legible:
        // bridge first → no new temp notifications during controller teardown).
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

        // CLOSE_BACKGROUND_REQUEST_CODE and DEGRADED_CONTENT_CODE moved to ForegroundNotificationPublisher
    }
}

/**
 * L4 §4.4 (M4): pure-JVM seam that owns the runtime/ownership side-effects
 * for transport drops and [SessionStreamingService] shutdown disposition.
 *
 * Extracted from the Android [Service] shell so the disposition contract is
 * JVM-testable without a real Service instance (the Service simply delegates
 * [SessionStreamingService.onDestroy] + the SSE owner's drop routing here).
 *
 * Two responsibilities:
 *  1. **Drop routing** (implements [UnexpectedTransportDropHandler]): the SSE
 *     owner routes every unexpected transport drop here. Releases ownership
 *     BEFORE publishing Dropped (I3 ordering). Ownership release is idempotent
 *     ([StreamingOwnershipGate.releaseNow] is a no-op for a non-held identity),
 *     and the runtime rejects a stale/foreign attempt — so a double-call is a
 *     safe no-op.
 *  2. **Destruction disposition** ([applyDestructionDisposition]): called from
 *     [SessionStreamingService.onDestroy]. Releases ownership, then applies the
 *     disposition recorded by [markIntentional] / [rearmUnexpected] using the
 *     **captured canonical attempt** directly:
 *     - [ShutdownDisposition.INTENTIONAL] → [SseTransportRuntimeStore.markStopped]
 *       (no auto-revive; I6);
 *     - [ShutdownDisposition.UNEXPECTED] →
 *       [SseTransportRuntimeStore.publishDropped] with
 *       [TransportDropReason.SERVICE_DESTROYED] (observable by the supervisor).
 *
 *     The Service applies the disposition BEFORE the owner's
 *     [ServiceSseConnectionOwner.cancelForShutdown] (whose markStopped would
 *     otherwise make the captured attempt stale), so an unacknowledged
 *     recovery attempt naturally preserves its original drop ticket via the
 *     runtime's recovery-ticket restore (I4) — no replacement attempt and no
 *     observable Connecting fallback (Wave-1 blockers #1 + #2).
 *
 * If the owner already handled a terminal path (runtime is Dropped/Stopped),
 * the captured attempt is null → no duplicate drop (M4-3).
 */
internal class SseShutdownSeal(
    private val runtimeStore: SseTransportRuntimeStore,
    private val ownershipGate: StreamingOwnershipGate,
) : FencedUnexpectedTransportDropHandler {

    /**
     * M4 §4.4: the destruction disposition. Defaults to [UNEXPECTED] (system /
     * process destruction without an intentional stop marker). [markIntentional]
     * flips it before any normal `stopSelf()` / no-source terminal / user-close
     * / lifecycle-timeout / bootstrap-rollback path.
     */
    internal enum class ShutdownDisposition { UNEXPECTED, INTENTIONAL }

    @Volatile
    private var disposition: ShutdownDisposition = ShutdownDisposition.UNEXPECTED

    /**
     * Marks the in-progress destruction as intentional. Called before the
     * normal `enterNoSourceTerminal` / `stopSelf` / rollback / timeout paths
     * so [applyDestructionDisposition] publishes Stopped (never Dropped).
     */
    fun markIntentional() {
        disposition = ShutdownDisposition.INTENTIONAL
    }

    /**
     * Wave-1 M4 (blocker #4): re-arm the destruction disposition to
     * [ShutdownDisposition.UNEXPECTED]. Called on each newly accepted
     * start/bootstrap (see [SessionStreamingService.onStartCommand]) so a
     * prior intentional `stopSelf` (or any other intentional path) cannot
     * poison a later unexpected destruction after a start overlap.
     *
     * The disposition field is the single in-memory flag consulted by
     * [applyDestructionDisposition]; it lives for the Service instance.
     * Without this re-arm, an intentional mark set during a superseded
     * lifecycle would incorrectly suppress the SERVICE_DESTROYED drop when
     * the system later destroys the rebuilt Service unexpectedly.
     */
    fun rearmUnexpected() {
        disposition = ShutdownDisposition.UNEXPECTED
    }

    /**
     * M1A/M4: the SSE owner routes unexpected transport drops here.
     *
     * I3 ordering: release ownership BEFORE publishing Dropped, so consumers
     * never observe `Dropped + Ready` for the same identity. Both operations
     * are idempotent — a second call (e.g. the owner's exhaustion path racing
     * onDestroy) is a safe no-op: [StreamingOwnershipGate.releaseNow] ignores
     * a non-held identity, and [SseTransportRuntimeStore.publishDropped]
     * returns null for a stale/already-terminal attempt.
     */
    override fun onUnexpectedDrop(attempt: TransportAttemptToken, reason: TransportDropReason) {
        onUnexpectedDropIfCurrent(attempt, reason)
    }

    /**
     * Atomic fence for the release-before-publish contract.  Owner
     * supersession uses the same monitor, so an old exhaustion callback cannot
     * release a newer Ready owner for the same identity.
     */
    override fun onUnexpectedDropIfCurrent(
        attempt: TransportAttemptToken,
        reason: TransportDropReason,
    ): Boolean = synchronized(this) {
        val current = runtimeStore.currentAttempt(attempt.identity)
        if (current?.attemptId != attempt.attemptId) return@synchronized false
        ownershipGate.releaseNow(attempt.identity)
        runtimeStore.publishDropped(attempt, reason) != null
    }

    /**
     * M4 §4.4 (Wave-1 blockers #1 + #2): applies the onDestroy disposition
     * using the **captured canonical attempt** directly — no replacement
     * attempt, no observable Connecting fallback.
     *
     * The Service captures the active attempt BEFORE any runtime
     * terminalization and calls this BEFORE [ServiceSseConnectionOwner.cancelForShutdown]
     * (whose own markStopped would otherwise make the captured attempt
     * stale). With the captured attempt still canonical here:
     *  - INTENTIONAL → [SseTransportRuntimeStore.markStopped] (idempotent);
     *  - UNEXPECTED  → [SseTransportRuntimeStore.publishDropped] with
     *    [TransportDropReason.SERVICE_DESTROYED].
     *
     * **Recovery-ticket conservation**: for an unacknowledged RECOVERY
     * attempt (the captured attempt carries a non-null `recoveryTicket`),
     * [publishDropped] restores the EXACT same drop ticket (I4) — same
     * dropId + same original reason. No fresh ticket is allocated and the
     * supervisor's tracked dropId survives the mid-recovery destruction.
     *
     * Ownership is released FIRST (I3: consumers never observe a terminal
     * runtime alongside a lingering Ready owner). A null [attempt] (the
     * owner already handled a terminal path → the runtime holds no
     * canonical attempt) is a clean no-op: ownership is still released (if
     * [identity] is non-null) but no drop/stopped is published — no
     * duplicate drop (M4-3). [publishDropped] / [markStopped] are
     * individually idempotent for a stale/foreign attempt, so a racing
     * terminal path is also a safe no-op.
     */
    fun applyDestructionDisposition(
        identity: ConnectionIdentity?,
        attempt: TransportAttemptToken?,
    ) = synchronized(this) {
        val token = attempt
        if (token != null) {
            val current = runtimeStore.currentAttempt(token.identity)
            if (current?.attemptId != token.attemptId) return@synchronized
        }
        // I3: release ownership FIRST (consumers never observe Dropped/Stopped
        // + Ready for the same identity). releaseNow is idempotent for a
        // non-held identity.
        if (identity != null) ownershipGate.releaseNow(identity)
        if (token == null) return@synchronized
        when (disposition) {
            ShutdownDisposition.INTENTIONAL -> runtimeStore.markStopped(token)
            ShutdownDisposition.UNEXPECTED -> runtimeStore.publishDropped(
                token,
                TransportDropReason.SERVICE_DESTROYED,
            )
        }
    }
}
