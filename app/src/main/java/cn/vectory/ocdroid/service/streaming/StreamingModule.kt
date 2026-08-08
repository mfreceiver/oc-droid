package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.di.ApplicationScope
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/**
 * Binds the streaming-package production implementations to their seams so
 * [cn.vectory.ocdroid.service.SessionStreamingService] can inject them by
 * interface (test-friendly — the controller takes the same interfaces and is
 * unit-tested with fakes).
 *
 * - [ConnectionBootstrapRunner] → [BootstrapRunner]
 * - [SharedStateStoreSessionSnapshotProvider] → [SessionSnapshotProvider]
 * - [AndroidStreamingServiceLauncher] → [StreamingServiceLauncher] (CP9)
 * - [SlimFanOutRetryScheduler] (Batch-1 item 17): constructed via `@Provides`
 *   (not `@Inject constructor`) so the runner lambda + summary sink can be
 *   wired with live deps ([SlimStatusFanOut], [SessionSyncCoordinator])
 *   without Hilt binding the function types.
 *
 * The first three impls are `@Singleton @Inject constructor` themselves (no
 * constructor args beyond injectable deps), so a `@Binds` is sufficient —
 * no `@Provides`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StreamingModule {

    @Binds
    @Singleton
    abstract fun bindBootstrapRunner(impl: ConnectionBootstrapRunner): BootstrapRunner

    @Binds
    @Singleton
    abstract fun bindSessionSnapshotProvider(impl: SharedStateStoreSessionSnapshotProvider): SessionSnapshotProvider

    @Binds
    @Singleton
    abstract fun bindEffectiveConnectionConfigResolver(
        impl: DefaultEffectiveConnectionConfigResolver,
    ): EffectiveConnectionConfigResolver

    @Binds
    @Singleton
    abstract fun bindBundleEndpointResolver(
        impl: DefaultEffectiveConnectionConfigResolver,
    ): BundleEndpointResolver

    @Binds
    @Singleton
    abstract fun bindDegradedBootstrapTerminator(
        impl: cn.vectory.ocdroid.service.AndroidDegradedBootstrapTerminator,
    ): cn.vectory.ocdroid.service.DegradedBootstrapTerminator
}

/**
 * Batch-1 item 17: provides the process-level [SlimFanOutRetryScheduler]
 * singleton. Extracted as a `@Provides` so the runner lambda + summary sink
 * can be wired with live deps without Hilt binding function types.
 *
 * §final-gate I-1 (oracle §3.6): wires the slim status fan-out runner
 * + summary sink. The fan-out is constructed here (not as a Hilt binding)
 * because [SlimStatusFanOut] is a plain Kotlin class with no other deps
 * than the repository (mirrors the existing slim use-case pattern). The
 * runner gates on identity + slim-mode so legacy mode issues zero fan-out
 * HTTP requests.
 *
 * The dead 30s loop machinery (startLoop/ensureRunning/startAndAwaitFirstPoll)
 * was removed in Batch 1 item 17 — the preserved backoff + single-flight-retry
 * seam is the documented re-enablement vector (see [SlimFanOutRetryScheduler] kdoc).
 */
@Module
@InstallIn(SingletonComponent::class)
object SlimFanOutRetrySchedulerModule {
    @Provides
    @Singleton
    fun provideSlimFanOutRetryScheduler(
        @ApplicationScope scope: CoroutineScope,
        snapshotProvider: SessionSnapshotProvider,
        identityStore: ConnectionIdentityStore,
        repository: cn.vectory.ocdroid.data.repository.OpenCodeRepository,
        serverCompatProfile:
            cn.vectory.ocdroid.data.repository.ServerCompatProfile,
        sessionSyncCoordinator:
            cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator,
    ): SlimFanOutRetryScheduler {
        // §final-gate I-1 (oracle §3.6): construct the slim fan-out here.
        // The fan-out's only dep is the repository (T4's
        // getSlimapiSessionStatusOutcome is consume-only).
        val fanOut = cn.vectory.ocdroid.service.status.SlimStatusFanOut(repository)

        return SlimFanOutRetryScheduler(
            scope = scope,
            snapshotProvider = snapshotProvider,
            identityStore = identityStore,

            // §final-gate I-1 (oracle §3.6): slim-mode gate. Legacy repos
            // return null here for EVERY tick — zero fan-out HTTP. The
            // identity re-check is the cheap fast-path before the network
            // sweep (the inner double-check inside runSlimFanOut's mutex
            // catches a switch that landed between the outer check and
            // mutex acquisition).
            // ι-Q3a: 此 runner 是 slim **status 扇出轮询**（checkSlimSessionsStatuses），
            // 非 token-stream；故用 usesSlimStatusFanOut（≡ slimConnection）而非
            // supportsTokenStreamResync。逐字等价（usesSlimStatusFanOut = slimConnection）。
            slimFanOutRunner = runner@{ identity, snapshot ->
                if (!identityStore.isCurrent(identity)) return@runner null
                if (!repository.usesSlimStatusFanOut) return@runner null

                // P0 (B-slim-storm-fix): per-session fan-out is redundant in lite-v2-dev
                // (getSlimapiSessionStatusOutcome delegates to the bulk endpoint) AND its
                // 404 detection is defeated under delegation (a missing item — really idle —
                // is misjudged SessionMissing → EvictSession storm that never contracts the
                // snapshot). The bulk runRefresh above already issues one full query per
                // tick (the deleted `refresh` adapter → getSlimapiSessionsStatus). Short-
                // circuit the per-session sweep until the per-session endpoint is
                // independently available again. Status data flow is fully covered by bulk
                // runRefresh; stale cleanup is driven independently by the session.deleted
                // digest SSE event (→ EvictSession via handleSessionDigest).
                if (!serverCompatProfile.slimPerSessionStatusEndpointAvailable) return@runner null

                // §U-CQ5 sweep-start identity causal fence (backlog-cleanup):
                // capture store epoch BEFORE the network sweep so the summary is
                // causally tied to the identity it was born under. Stamped post-
                // sweep; validated by applySlimStatusFanOutSummary. Must be
                // captured HERE (pre-network), not after — the fence value must
                // reflect sweep-start, not sweep-end.
                val sweepStartEpoch = sessionSyncCoordinator.captureStoreIdentityEpoch()

                val sessionIds = snapshot.sessionsById.keys

                val summary = fanOut.checkSlimSessionsStatuses(
                    sids = sessionIds,
                    // §U-CQ8 (Batch 2): re-read the snapshot AFTER the network
                    // sweep (awaitAll) so the fake-idle cross-check uses the
                    // CURRENT session list, not the pre-sweep stale one. Closes
                    // the TOCTOU where a session was archived / created during
                    // the sweep (archived → its idle correctly reclassified
                    // missing; created → its idle NOT misjudged missing).
                    // snapshotProvider is captured from this @Provides closure
                    // (the param at :98) — it is the SAME provider the scheduler
                    // re-reads every retry.
                    knownSessionIdsProvider = { snapshotProvider.current().sessionsById.keys },
                )
                summary.copy(sweepStartEpoch = sweepStartEpoch)
            },

            // §final-gate I-1 (oracle §3.6): route the summary to the
            // coordinator. applySlimStatusFanOutSummary emits per-sid
            // EvictSession effects (404) + the RequestPollerBackoff /
            // ResetPollerBackoff effect (retryable / success). AppCore's
            // effect tail routes those back into this scheduler's backoff
            // state + single-flight retry.
            slimFanOutSummarySink = { summary ->
                sessionSyncCoordinator.applySlimStatusFanOutSummary(summary)
            },

        )
    }
}

/**
 * L2: provides the [ServiceSseConnectionOwner] singleton.
 *
 * Constructed via `@Provides` (not `@Inject constructor`) because the
 * constructor receives function-type parameters (onResync, onTerminalDrop)
 * that Hilt cannot bind directly.
 *
 * L2 removals:
 *  - `recoveryPolicy: SseRecoveryPolicy` (the retry loop died)
 *  - `reconnectAllowed` lambda + `appLifecycleMonitor` (no reconnect gate)
 *  - `jitterSource` (no retry jitter)
 *  - `recoveryPolicy` (the [cn.vectory.ocdroid.service.streaming.SseRecoveryPolicy]
 *    class) is retained as a schedule+jitter utility for tests + a future SSE-retry
 *    reintroduction seam, but no longer participates in the slim fan-out path
 *    (that math now lives in SlimFanOutBackoffPolicy). It is dropped from the
 *    Owner constructor + this @Provides.
 *  - Added `ownershipGate: StreamingOwnershipGate` (lease authority).
 *  - Renamed `onTerminalExhaustion` → `onTerminalDrop`.
 */
@Module
@InstallIn(SingletonComponent::class)
object ServiceSseConnectionOwnerModule {
    @Provides
    @Singleton
    fun provideServiceSseConnectionOwner(
        @ApplicationScope scope: CoroutineScope,
        repository: cn.vectory.ocdroid.data.repository.OpenCodeRepository,
        identityStore: ConnectionIdentityStore,
        sseEventStream: cn.vectory.ocdroid.service.events.SseEventStream,
        sharedStateStore: cn.vectory.ocdroid.ui.SharedStateStore,
        sharedEffectBus: cn.vectory.ocdroid.ui.SharedEffectBus,
        ownershipGate: cn.vectory.ocdroid.service.StreamingOwnershipGate,
        runtimeStore: SseTransportRuntimeStore,
        dropHandler: ForegroundTransportDropHandler,
        settingsManager: cn.vectory.ocdroid.util.SettingsManager,
        sessionSyncCoordinator:
            cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator,
    ): ServiceSseConnectionOwner = ServiceSseConnectionOwner(
        scope = scope,
        repository = repository,
        identityStore = identityStore,
        sseEventStream = sseEventStream,
        sharedStateStore = sharedStateStore,
        sharedEffectBus = sharedEffectBus,
        ownershipGate = ownershipGate,
        runtimeStore = runtimeStore,
        dropHandler = dropHandler,
        onResync = onResync@{ isStillCurrent ->
            if (!isStillCurrent()) return@onResync
            if (!repository.supportsWatermarkResync) return@onResync
            val directories = buildList {
                sharedStateStore.slices.sessionList.value.directorySessions.keys
                    .forEach { add(it) }
                settingsManager.currentWorkdir?.let { add(it) }
            }
                .filter { it.isNotBlank() }
                .map { cn.vectory.ocdroid.util.WorkdirPaths.normalizeDirectory(it) }
                .distinct()
                .ifEmpty { null }
            cn.vectory.ocdroid.util.DebugLog.i(
                "ServiceSseConnectionOwner",
                "slim onResync directories=$directories",
            )
            sessionSyncCoordinator.reconcileFullAfterTransportReset(
                isStillCurrent = isStillCurrent,
            )
        },
        onTerminalDrop = {
            sharedEffectBus.tryEmitEffect(
                cn.vectory.ocdroid.ui.controller.ControllerEffect.ColdStartReconnect,
            )
        },
    )
}

@Module
@InstallIn(SingletonComponent::class)
object ConnectionBootstrapEngineModule {
    @Provides
    @Singleton
    fun provideConnectionBootstrapEngine(
        configResolver: EffectiveConnectionConfigResolver,
        settingsManager: cn.vectory.ocdroid.util.SettingsManager,
        repository: cn.vectory.ocdroid.data.repository.OpenCodeRepository,
        identityStore: ConnectionIdentityStore,
        serverCompatProfile: cn.vectory.ocdroid.data.repository.ServerCompatProfile,
        appLifecycleMonitor: cn.vectory.ocdroid.di.AppLifecycleMonitor,
    ): ConnectionBootstrapEngine = ConnectionBootstrapEngine(
        configResolver = configResolver,
        settingsManager = settingsManager,
        repository = repository,
        identityStore = identityStore,
        serverCompatProfile = serverCompatProfile,
        hasActivity = { appLifecycleMonitor.isInForeground.value },
    )
}
