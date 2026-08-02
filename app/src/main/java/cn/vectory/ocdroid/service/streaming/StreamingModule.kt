package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.di.ApplicationScope
import cn.vectory.ocdroid.service.AndroidStreamingServiceLauncher
import cn.vectory.ocdroid.service.StreamingServiceLauncher
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.status.StatusAggregator
import cn.vectory.ocdroid.service.status.StatusAggregatorInput
import cn.vectory.ocdroid.data.state.AuthorityState
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
 * - [ProcessStatusPoller] (D2 gate #4): constructed via `@Provides` (not
 *   `@Inject constructor`) so the clock default-param can be filled without
 *   a Hilt binding for `() -> Long` (mirrors
 *   [cn.vectory.ocdroid.service.status.StatusAggregatorImpl]'s pattern).
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

    /**
     * CP9 (notify Phase-0 switchover): binds the Android launcher impl so
     * [cn.vectory.ocdroid.ui.controller.ConnectionCoordinator] can inject
     * [StreamingServiceLauncher] by interface. Tests inject a fake launcher
     * directly (no Hilt container) — see [cn.vectory.ocdroid.ui.controller.
     * ConnectionCoordinatorTest].
     */
    @Binds
    @Singleton
    abstract fun bindStreamingServiceLauncher(impl: AndroidStreamingServiceLauncher): StreamingServiceLauncher

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
 * D2 (gate #4): provides the process-level [ProcessStatusPoller] singleton.
 * Extracted as a `@Provides` so the clock default-param (`() -> Long`) can
 * be filled without a Hilt binding for the function type.
 *
 * §final-gate I-1 (oracle §3.6): ALSO wires the slim status fan-out runner
 * + summary sink. The fan-out is constructed here (not as a Hilt binding)
 * because [SlimStatusFanOut] is a plain Kotlin class with no other deps
 * than the repository (mirrors the existing slim use-case pattern). The
 * runner gates on identity + slim-mode so legacy mode issues zero fan-out
 * HTTP requests.
 */
@Module
@InstallIn(SingletonComponent::class)
object ProcessStatusPollerModule {
    @Provides
    @Singleton
    fun provideProcessStatusPoller(
        @ApplicationScope scope: CoroutineScope,
        statusAggregatorInput: StatusAggregatorInput,
        snapshotProvider: SessionSnapshotProvider,
        identityStore: ConnectionIdentityStore,
        statusAggregator: StatusAggregator,
        repository: cn.vectory.ocdroid.data.repository.OpenCodeRepository,
        serverCompatProfile:
            cn.vectory.ocdroid.data.repository.ServerCompatProfile,
        sessionSyncCoordinator:
            cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator,
    ): ProcessStatusPoller {
        // §final-gate I-1 (oracle §3.6): construct the slim fan-out here.
        // The fan-out's only dep is the repository (T4's
        // getSlimapiSessionStatusOutcome is consume-only).
        val fanOut = cn.vectory.ocdroid.service.status.SlimStatusFanOut(repository)

        return ProcessStatusPoller(
            scope = scope,
            statusAggregatorInput = statusAggregatorInput,
            snapshotProvider = snapshotProvider,
            identityStore = identityStore,
            statusAggregator = statusAggregator,
            clock = { System.currentTimeMillis() },

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
                // tick (StatusAggregatorImpl.refresh → getSlimapiSessionsStatus). Short-
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
                    // (the param at :98) — it is the SAME provider the poller
                    // re-reads every tick.
                    knownSessionIdsProvider = { snapshotProvider.current().sessionsById.keys },
                )
                summary.copy(sweepStartEpoch = sweepStartEpoch)
            },

            // §final-gate I-1 (oracle §3.6): route the summary to the
            // coordinator. applySlimStatusFanOutSummary emits per-sid
            // EvictSession effects (404) + the RequestPollerBackoff /
            // ResetPollerBackoff effect (retryable / success). AppCore's
            // effect tail routes those back into this poller's backoff
            // state + single-flight retry.
            slimFanOutSummarySink = { summary ->
                sessionSyncCoordinator.applySlimStatusFanOutSummary(summary)
            },

        )
    }
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
