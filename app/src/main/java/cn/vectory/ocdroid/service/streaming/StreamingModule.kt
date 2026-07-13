package cn.vectory.ocdroid.service.streaming

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
 *
 * Both impls are `@Singleton @Inject constructor` themselves (no constructor
 * args beyond injectable deps), so a `@Binds` is sufficient — no `@Provides`.
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
}
