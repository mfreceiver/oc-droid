package cn.vectory.ocdroid.service.status

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds [StatusAggregatorImpl] as the application-level [StatusAggregator] (dev-design P0.4).
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
}
