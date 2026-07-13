package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.service.AndroidStreamingServiceLauncher
import cn.vectory.ocdroid.service.StreamingServiceLauncher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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
 *
 * All three impls are `@Singleton @Inject constructor` themselves (no
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
}
