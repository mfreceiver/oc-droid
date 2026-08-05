package cn.vectory.ocdroid.service.status

import cn.vectory.ocdroid.data.repository.ConnectionRepository
import cn.vectory.ocdroid.data.repository.SessionRepository
import cn.vectory.ocdroid.di.UiApplicationScope
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.ui.SharedStateStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/**
 * Binds [StatusAggregatorImpl] as the application-level [StatusAggregator] (outputs)
 * AND [StatusAggregatorInput] (the feed surface) — dev-design P0.4 + CP4 + D1.
 *
 * Both `@Binds` resolve to the SAME `@Singleton` instance produced by
 * [provideStatusAggregatorImpl]. The impl is constructed via `@Provides` (not
 * `@Inject constructor`) so the [StatusAggregatorImpl] clock default-param
 * (`{ System.currentTimeMillis() }`) is honored at the construction site —
 * matching the other controllers' pattern (ForegroundCatchUpController /
 * SessionSwitcher / ConnectionCoordinator / SessionSyncCoordinator all take a
 * default-param clock and are wired via `@Provides` in [ControllerModule]).
 *
 * **§P0-A Lane 2**: the aggregator no longer depends on [OpenCodeRepository]
 * — the REST/slim network fetch was extracted to [StatusFetchService] (provided
 * below), and the aggregator's READ side derives from [SharedStateStore].
 * `store` (the same `@Singleton` every ViewModel / controller injects) is the
 * single authority source the aggregator derives its lifecycle projection from
 * (双投影同源). The mutation API (`refresh` / `applySseStatus` /
 * `markRequestFailed`) dispatches [cn.vectory.ocdroid.data.state.AuthorityOp]s
 * into `store`'s single CAS — no second writable source.
 *
 * **D1 (gate #1)**: also injects the [UiApplicationScope] (Main.immediate)
 * [CoroutineScope] so [StatusAggregatorImpl] can schedule its passive-TTL
 * wake-up + its `store.stateFlow` collect on the same scope.
 *
 * Installed in [SingletonComponent] because the authoritative busy source must outlive any
 * Activity / ViewModel — it is consumed by the FGS lifecycle coordinator (Lane C) and the
 * notification display layer (Phase 1), both of which run process-wide.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StatusModule {

    @Binds
    @Singleton
    abstract fun bindStatusAggregator(impl: StatusAggregatorImpl): StatusAggregator

    /** CP4: bind the input surface to the same singleton impl. */
    @Binds
    @Singleton
    abstract fun bindStatusAggregatorInput(impl: StatusAggregatorImpl): StatusAggregatorInput

    companion object {
        @Provides
        @Singleton
        fun provideStatusAggregatorImpl(
            identityStore: ConnectionIdentityStore,
            store: SharedStateStore,
            statusFetchService: StatusFetchService,
            @UiApplicationScope scope: CoroutineScope,
        ): StatusAggregatorImpl = StatusAggregatorImpl(
            identityStore = identityStore,
            store = store,
            statusFetchService = statusFetchService,
            scope = scope,
            clock = { System.currentTimeMillis() },
        )

        /** §P0-A Lane 2 (B4-c): [StatusFetchService] is `@Inject constructor`-able
         *  but provided here explicitly to keep the status DI surface in one
         *  module (and to mirror the [provideStatusAggregatorImpl] seam). */
        @Provides
        @Singleton
        fun provideStatusFetchService(
            sessionRepository: SessionRepository,
            connectionRepository: ConnectionRepository,
            slimStatusFetchCache: SlimStatusFetchCache,
        ): StatusFetchService = StatusFetchService(sessionRepository, connectionRepository, slimStatusFetchCache)

        /** SlimApi P2: shared background status-fetch cache that deduplicates
         *  the two 30s background polling loops. Provided explicitly (not
         *  @Inject constructor) so the default clock param is honored. */
        @Provides
        @Singleton
        fun provideSlimStatusFetchCache(
            sessionRepository: SessionRepository,
        ): SlimStatusFetchCache = SlimStatusFetchCache(sessionRepository)
    }
}
