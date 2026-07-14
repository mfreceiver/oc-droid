package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.di.ApplicationScope
import cn.vectory.ocdroid.service.AndroidStreamingServiceLauncher
import cn.vectory.ocdroid.service.StreamingServiceLauncher
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.status.StatusAggregator
import cn.vectory.ocdroid.service.status.StatusAggregatorInput
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
    abstract fun bindReconfigureTeardown(
        impl: cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator,
    ): cn.vectory.ocdroid.service.ReconfigureTeardown
}

/**
 * D2 (gate #4): provides the process-level [ProcessStatusPoller] singleton.
 * Extracted as a `@Provides` so the clock default-param (`() -> Long`) can
 * be filled without a Hilt binding for the function type.
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
    ): ProcessStatusPoller = ProcessStatusPoller(
        scope = scope,
        statusAggregatorInput = statusAggregatorInput,
        snapshotProvider = snapshotProvider,
        identityStore = identityStore,
        statusAggregator = statusAggregator,
        clock = { System.currentTimeMillis() },
    )
}

@Module
@InstallIn(SingletonComponent::class)
object ConnectionBootstrapEngineModule {
    @Provides
    @Singleton
    fun provideConnectionBootstrapEngine(
        hostProfileStore: cn.vectory.ocdroid.data.repository.HostProfileStore,
        settingsManager: cn.vectory.ocdroid.util.SettingsManager,
        repository: cn.vectory.ocdroid.data.repository.OpenCodeRepository,
        identityStore: ConnectionIdentityStore,
        bootstrapCoordinator: cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator,
        serverCompatProfile: cn.vectory.ocdroid.data.repository.ServerCompatProfile,
        appLifecycleMonitor: cn.vectory.ocdroid.di.AppLifecycleMonitor,
    ): ConnectionBootstrapEngine = ConnectionBootstrapEngine(
        hostProfileStore = hostProfileStore,
        settingsManager = settingsManager,
        repository = repository,
        identityStore = identityStore,
        bootstrapCoordinator = bootstrapCoordinator,
        serverCompatProfile = serverCompatProfile,
        hasActivity = { appLifecycleMonitor.isInForeground.value },
    )
}
