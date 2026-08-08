package cn.vectory.ocdroid.service.status

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
 * Binds [StatusAggregatorImpl] as the application-level [StatusAggregator] —
 * dev-design P0.4 + CP4 + D1.
 *
 * The impl is constructed via `@Provides` (not `@Inject constructor`) so the
 * [StatusAggregatorImpl] clock default-param (`{ System.currentTimeMillis() }`)
 * is honored at the construction site — matching the other controllers' pattern
 * (ForegroundCatchUpController / SessionSwitcher / ConnectionCoordinator /
 * SessionSyncCoordinator all take a default-param clock and are wired via
 * `@Provides` in [ControllerModule]).
 *
 * **F1 (archdebt follow-up)**: the separate [StatusAggregatorInput] binding
 * and `StatusFetchService`/`SlimStatusFetchCache` providers were **deleted** —
 * the input feed surface and its fetch wiring were retired (all production
 * callers were deliberately rerouted to direct authority dispatch in Lane 2).
 * The aggregator's READ side derives solely from [SharedStateStore] (双投影同源).
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

    companion object {
        @Provides
        @Singleton
        fun provideStatusAggregatorImpl(
            identityStore: ConnectionIdentityStore,
            store: SharedStateStore,
            @UiApplicationScope scope: CoroutineScope,
        ): StatusAggregatorImpl = StatusAggregatorImpl(
            identityStore = identityStore,
            store = store,
            scope = scope,
            clock = { System.currentTimeMillis() },
        )
    }
}
