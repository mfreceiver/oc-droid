package cn.vectory.ocdroid.service.status

import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.di.UiApplicationScope
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
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
 * SessionSwitcher / ConnectionCoordinator / SessionSyncCoordinator all take
 * a default-param clock and are wired via `@Provides` in [ControllerModule]).
 *
 * **D1 (gate #1)**: also injects the [UiApplicationScope] (Main.immediate)
 * [CoroutineScope] so [StatusAggregatorImpl] can schedule its passive-TTL
 * wake-up on the same scope the lifecycle coordinator uses — keeping the
 * freshness recompute + the serial state machine on the same dispatcher so
 * observers see a consistent verdict.
 *
 * This lets `StreamingLifecycleCoordinator` consume the read-only [StatusAggregator]
 * while `SessionSyncCoordinator` (and future bootstrap / AppCore feed sites) consume
 * [StatusAggregatorInput], without either depending on the concrete impl — and both
 * see the same in-memory singleton (so SSE updates from SSC are immediately visible
 * to the lifecycle coordinator's `globalState` observer).
 *
 * Installed in [SingletonComponent] because the authoritative busy source must outlive any
 * Activity / ViewModel — it is consumed by the FGS lifecycle coordinator (Lane C) and the
 * notification display layer (Phase 1), both of which run process-wide.
 *
 * **Why both `@Provides` (concrete-method) and `@Binds` (abstract) in one Module**:
 * `@Provides` constructs the singleton impl (with the clock default filled); the two
 * `@Binds` re-expose it under each interface type so any consumer depending on either
 * interface gets the same backing instance. Kotlin allows `@Provides`-annotored functions
 * inside a `companion object` of an abstract `@Module` class (Dagger-Kotlin standard
 * pattern).
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
            repository: OpenCodeRepository,
            identityStore: ConnectionIdentityStore,
            @UiApplicationScope scope: CoroutineScope,
        ): StatusAggregatorImpl = StatusAggregatorImpl(
            repository = repository,
            identityStore = identityStore,
            scope = scope,
            clock = { System.currentTimeMillis() },
        )
    }
}
